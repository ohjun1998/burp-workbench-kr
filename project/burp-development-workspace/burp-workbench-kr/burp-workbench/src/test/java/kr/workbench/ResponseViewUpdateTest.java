package kr.workbench;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SortOrder;
import javax.swing.RowSorter;

/** A busy EDT must retain the latest complete view without retaining every intermediate table. */
public final class ResponseViewUpdateTest extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    var panel = new ResponseReconPanel(ResponseBudgetTest.api(new AtomicBoolean(true)));
    var original = proxy(burp.api.montoya.http.message.HttpRequestResponse.class, (p,m,a) -> def(m));
    var latest = proxy(burp.api.montoya.http.message.HttpRequestResponse.class, (p,m,a) -> def(m));
    try {
      panel.worker.submit(() -> {
        for (int row = 0; row < 400; row++) {
          String key = "finding-" + row, url = "https://budget.test/items/" + row;
          panel.issues.put(key, new PassiveFindings.Finding("rule", url, "Body", "observed"));
          panel.counts.put(key, 1);
          panel.inventory.put("GET " + url, new ApiInventory.Endpoint(url, "GET", Set.of(),
              Set.of(), Set.of(), "application/json", "observed"));
        }
        panel.cachePacket(panel.findingPackets,"finding-17",original);
        panel.refresh(panel.generation.get());
      }).get(5, TimeUnit.SECONDS);
      edt(() -> {});
      AtomicInteger events = new AtomicInteger();
      edt(() -> {
        panel.findingTable.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.DESCENDING)));
        panel.findingTable.setRowSelectionInterval(panel.findingTable.convertRowIndexToView(17),
            panel.findingTable.convertRowIndexToView(17));
        panel.apiTable.setRowSelectionInterval(23, 23);
        panel.apiTable.getColumnModel().getColumn(1).setPreferredWidth(377);
        panel.findings.addTableModelListener(event -> events.incrementAndGet());
      });
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      javax.swing.SwingUtilities.invokeLater(() -> {
        entered.countDown();
        try { release.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
      });
      check(entered.await(5, TimeUnit.SECONDS), "EDT is blocked while a burst of responses completes");
      try {
        panel.worker.submit(() -> {
          for (int version = 2; version <= 20; version++) {
            panel.counts.replaceAll((key, value) -> value + 1);
            panel.cachePacket(panel.findingPackets,"finding-17",latest);
            panel.refresh(panel.generation.get());
          }
        }).get(10, TimeUnit.SECONDS);
        check(panel.pendingView.get() != null && panel.viewUpdatePending.get(),
            "busy EDT holds one complete latest view");
      } finally { release.countDown(); }
      edt(() -> {});
      edt(() -> {
        check(events.get() == 1, "nineteen result updates publish one table data event");
        check(panel.findings.getRowCount() == 400 && panel.documents.getRowCount() == 400
            && panel.findings.getValueAt(17, 4).equals(20), "coalescing preserves every row and latest observation count");
        check(panel.displayedFindingKeys.get(panel.findingTable.convertRowIndexToModel(
            panel.findingTable.getSelectedRow())).equals("finding-17"), "sorted finding selection follows stable finding identity");
        check(panel.displayedFindingPackets.get("finding-17") == latest && panel.findingCapture.isEnabled(),
            "coalesced finding keeps the exact latest evidence packet and selected action");
        check(panel.apiTable.convertRowIndexToModel(panel.apiTable.getSelectedRow()) == 23
            && panel.apiTable.getColumnModel().getColumn(1).getPreferredWidth() == 377,
            "API selection and column widths survive a data refresh");
        check(panel.viewLatency.snapshot().observations() == 2,
            "view timings measure actual publications rather than intermediate analysis states");
      });
      long previous = panel.generation.get();
      edt(() -> panel.generation.incrementAndGet());
      panel.worker.submit(() -> panel.refresh(previous)).get(5, TimeUnit.SECONDS);
      edt(() -> {});
      check(events.get() == 1 && panel.pendingView.get() == null, "stale generation cannot publish or retain old evidence");
      edt(() -> {
        panel.refresh(panel.generation.get());
        panel.close();
        check(panel.pendingView.get() == null, "unload releases the pending view immediately");
      });
      edt(() -> {});
      check(events.get() == 1 && !panel.viewUpdatePending.get(), "queued UI callback cannot update an unloaded panel");
    } finally { panel.close(); }
    closeDuringSnapshot();
    System.out.println("ResponseViewUpdateTest: " + checks + " checks passed");
  }

  static void closeDuringSnapshot() throws Exception {
    var panel = new ResponseReconPanel(ResponseBudgetTest.api(new AtomicBoolean(true)));
    AtomicReference<Thread> worker = new AtomicReference<>();
    CountDownLatch entered = new CountDownLatch(1), finished = new CountDownLatch(1);
    try {
      synchronized(panel.packetLock) {
        panel.worker.execute(() -> {
          worker.set(Thread.currentThread());entered.countDown();
          try { panel.refresh(panel.generation.get()); }
          finally { finished.countDown(); }
        });
        check(entered.await(5,TimeUnit.SECONDS), "snapshot worker enters before unload race");
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(worker.get().getState()!=Thread.State.BLOCKED && System.nanoTime()<deadline) Thread.yield();
        check(worker.get().getState()==Thread.State.BLOCKED, "snapshot waits on packet lock at unload");
        panel.close();
      }
      check(finished.await(5,TimeUnit.SECONDS) && panel.pendingView.get()==null,
          "snapshot finishing after unload cannot retain evidence or queue a new view");
      edt(() -> {});
      check(panel.viewLatency.snapshot().observations()==0, "unload-racing snapshot is never published");
    } finally { panel.close(); }
  }
}
