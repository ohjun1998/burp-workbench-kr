package kr.workbench;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** Controlled large-projection scheduling, EDT progress and stale-context rejection; no target HTTP. */
public final class ScopeInspectionDashboardAsyncTest extends WorkbenchTest {
  private static final long DEADLINE_SECONDS = 30;

  private static final class Gate {
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
  }

  private static final class Barrier implements ScopeDashboardPanel.ProjectionHook {
    final ConcurrentHashMap<String, Gate> gates = new ConcurrentHashMap<>();
    final List<String> started = new CopyOnWriteArrayList<>();
    final Set<Long> threads = ConcurrentHashMap.newKeySet();
    final AtomicInteger active = new AtomicInteger(), maximumActive = new AtomicInteger();
    final AtomicBoolean offEdt = new AtomicBoolean(true), daemon = new AtomicBoolean(true);

    Gate hold(String marker, String account) {
      var gate = new Gate();
      gates.put(marker + "|" + account, gate);
      return gate;
    }

    @Override public void before(ScopeInspection.Snapshot snapshot, String account) throws InterruptedException {
      String key = snapshot.message() + "|" + account;
      started.add(key);
      threads.add(Thread.currentThread().getId());
      if (SwingUtilities.isEventDispatchThread()) offEdt.set(false);
      if (!Thread.currentThread().isDaemon()) daemon.set(false);
      maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
      Gate gate = gates.get(key);
      try {
        if (gate != null) {
          gate.entered.countDown();
          if (!gate.release.await(DEADLINE_SECONDS, TimeUnit.SECONDS))
            throw new IllegalStateException("Controlled dashboard projection was not released: " + key);
        }
      } finally {
        active.decrementAndGet();
        if (gate != null) gate.exited.countDown();
      }
    }

    void releaseAll() { for (Gate gate : gates.values()) gate.release.countDown(); }
  }

  private static final class Actions implements ScopeDashboardPanel.Actions {
    final AtomicInteger inspect = new AtomicInteger(), handoffs = new AtomicInteger();
    public void inspect() { inspect.incrementAndGet(); }
    public void original(String id) { handoffs.incrementAndGet(); }
    public void repeater(String id) { handoffs.incrementAndGet(); }
    public void evidence(String id) { handoffs.incrementAndGet(); }
  }

  private static ScopeInspection.Finding finding(int index, String account) {
    return new ScopeInspection.Finding("SECRET", "비동기 예제 후보 " + index,
        "https://dashboard-async.test/resource/" + index, "GET", account, "형식 후보",
        index % 2 == 0 ? "HIGH" : "MEDIUM", "유효성 미검증", "가림된 예제 근거",
        "async-original-" + account + "-" + index, index + 1, index, "async-fingerprint-" + index);
  }

  private static ScopeInspection.Snapshot snapshot(String run, String marker, List<ScopeInspection.Finding> findings) {
    return new ScopeInspection.Snapshot(run, ScopeInspection.State.RUNNING, marker, 9, 7, 2, 1, 2, 1,
        findings, List.of(), List.of(), 100, 0);
  }

  private static <T> T onEdt(Supplier<T> value) throws Exception {
    var result = new AtomicReference<T>();
    edt(() -> result.set(value.get()));
    return result.get();
  }

  private static void entered(Gate gate, String description) throws Exception {
    if (!gate.entered.await(DEADLINE_SECONDS, TimeUnit.SECONDS)) throw new AssertionError("Worker did not enter " + description);
  }

  private static void awaitEdt(BooleanSupplier condition, String description) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
    while (!onEdt(condition::getAsBoolean)) {
      if (System.nanoTime() >= deadline) throw new AssertionError("Dashboard did not reach " + description);
      Thread.sleep(10);
    }
  }

  /** Reading a worker flag under its lock never waits for EDT or a worker while holding that lock. */
  private static void idle(ScopeDashboardPanel panel) throws Exception {
    Object lock = get(panel, "updateLock");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
    for (;;) {
      boolean running;
      synchronized (lock) { running = (boolean) get(panel, "workerRunning"); }
      if (!running) break;
      if (System.nanoTime() >= deadline) throw new AssertionError("Dashboard projection worker did not become idle");
      Thread.sleep(10);
    }
    edt(() -> {});
  }

  private static boolean unknown(ScopeDashboardPanel panel) {
    return panel.cards.values().stream().allMatch(card -> card.count == -1 && card.getText().contains("—"));
  }

  private static void selectAccount(ScopeDashboardPanel panel, String account) {
    for (int i = 0; i < panel.account.getItemCount(); i++) if (panel.account.getItemAt(i).value().equals(account)) {
      panel.account.setSelectedIndex(i); return;
    }
    throw new AssertionError("Missing account: " + account);
  }

  public static void main(String[] args) throws Exception {
    var barrier = new Barrier();
    var actions = new Actions();
    var panel = onEdt(() -> new ScopeDashboardPanel(actions, barrier));
    var findings = new ArrayList<ScopeInspection.Finding>();
    for (int i = 0; i < 5000; i++) findings.add(finding(i, i % 2 == 0 ? "Alice" : "Bob"));
    List<ScopeInspection.Finding> all = List.copyOf(findings), latestFindings = List.copyOf(findings.subList(0, 4999));
    int networkBefore = PayloadIntegrationTest.sent.get();
    try (var originals = new DiskTrafficStore()) {
      String baselineUrl = "https://dashboard-async.test/small-original";
      var request = request(baselineUrl, "GET", true, List.of());
      var response = response(200, "{\"example\":true}");
      registerTrafficPacket(request, response);
      var packet = originals.append("", "비동기 전환 원본 예제", request, response);
      var baseline = new ScopeInspection.Snapshot("baseline", ScopeInspection.State.RUNNING, "small-baseline", 0, 1, 0, 0, 0, 0,
          List.of(new ScopeInspection.Finding("SECRET", "작은 예제 후보", baselineUrl, "GET", "Alice", "형식 후보", "HIGH",
              "유효성 미검증", "가림된 예제 근거", packet.id(), 1, 0, "baseline-fingerprint")),
          List.of(new ScopeInspection.Page(baselineUrl, "GET", "Alice", 200, "JSON", true, "", "baseline-digest", packet)),
          List.of(), 100, 0);
      edt(() -> {
        panel.update(baseline, 20, "https://dashboard-async.test/");
        panel.cards.get(ScopeInspectionDashboard.Group.SECRETS).doClick(0);
        check(!panel.aggregating && panel.table.getRowCount() == 1 && panel.original.isEnabled()
            && barrier.started.isEmpty(), "small snapshots remain synchronous and usable without scheduling a projection worker");
      });

      var first = snapshot("live-run", "first", all);
      var latest = snapshot("live-run", "latest", latestFindings);
      Gate firstGate = barrier.hold("first", ""), latestGate = barrier.hold("latest", "");
      var returned = new CountDownLatch(1);
      var nextAction = new CountDownLatch(1);
      SwingUtilities.invokeLater(() -> { panel.update(first, 30, "https://dashboard-async.test/"); returned.countDown(); });
      SwingUtilities.invokeLater(() -> { panel.inspect.doClick(0); nextAction.countDown(); });
      entered(firstGate, "first large snapshot");
      check(returned.await(DEADLINE_SECONDS, TimeUnit.SECONDS) && nextAction.await(DEADLINE_SECONDS, TimeUnit.SECONDS)
          && actions.inspect.get() == 1, "a blocked 5000-finding projection returns control and permits the next real EDT action");
      check(barrier.offEdt.get() && barrier.daemon.get(), "large projection starts on a daemon worker rather than EDT");
      edt(() -> {
        panel.original.doClick(0);
        check(panel.aggregating && unknown(panel) && panel.visibleRows.isEmpty() && !panel.original.isEnabled()
            && !panel.repeater.isEnabled() && !panel.evidence.isEnabled() && actions.handoffs.get() == 0,
            "unknown large counts show dashes rather than zero and disable previous original handoffs");
        for (int i = 0; i < 20; i++) panel.update(snapshot("live-run", "skipped-" + i, all), 30, "https://dashboard-async.test/");
        panel.update(latest, 30, "https://dashboard-async.test/");
        check(panel.current == latest && barrier.started.equals(List.of("first|")),
            "an active projection retains only the latest pending publication without starting intermediate work");
      });
      firstGate.release.countDown();
      entered(latestGate, "latest coalesced snapshot");
      edt(() -> check(panel.aggregating && panel.current == latest
          && panel.cards.get(ScopeInspectionDashboard.Group.SECRETS).count == 5000
          && panel.filterSummary.getText().contains("이전 집계 표시 · 새 결과 집계 중"),
          "a completed same-context projection remains explicitly marked as previous while the latest snapshot is pending"));
      latestGate.release.countDown();
      awaitEdt(() -> !panel.aggregating && panel.cards.get(ScopeInspectionDashboard.Group.SECRETS).count == 4999, "latest counts");
      check(onEdt(() -> panel.current == latest && !panel.filterSummary.getText().contains("이전 집계 표시")),
          "the latest publication eventually replaces the previous aggregate and its stale-result marker");
      check(barrier.started.equals(List.of("first|", "latest|")) && barrier.maximumActive.get() == 1,
          "twenty intermediate updates are skipped by a single active worker and one latest pending slot");

      int cachedComputations = barrier.started.size();
      edt(() -> {
        panel.cards.get(ScopeInspectionDashboard.Group.SECRETS).doClick(0);
        check(panel.table.getRowCount() == 4999 && panel.visibleRows.size() == panel.cards.get(ScopeInspectionDashboard.Group.SECRETS).count,
            "completed large card and detail rows come from the same projection");
        panel.cards.get(ScopeInspectionDashboard.Group.API).doClick(0);
        panel.severityButtons.get("HIGH").doClick(0);
        panel.cards.get(ScopeInspectionDashboard.Group.PATHS).doClick(0);
        check(barrier.started.size() == cachedComputations && !panel.aggregating,
            "card and severity navigation reuse cached projected rows without scheduling more calculations");
      });

      Gate bobGate = barrier.hold("latest", "Bob"), aliceGate = barrier.hold("latest", "Alice");
      edt(() -> selectAccount(panel, "Bob"));
      entered(bobGate, "Bob account projection");
      edt(() -> check(panel.selectedAccount().equals("Bob") && panel.aggregating && unknown(panel)
          && panel.visibleRows.isEmpty() && !panel.original.isEnabled(),
          "changing accounts immediately hides the previous account's aggregate and original actions"));
      edt(() -> selectAccount(panel, "Alice"));
      bobGate.release.countDown();
      entered(aliceGate, "newest Alice account projection");
      edt(() -> check(panel.selectedAccount().equals("Alice") && panel.aggregating && unknown(panel) && panel.visibleRows.isEmpty(),
          "a completed obsolete account projection cannot populate the newly selected account"));
      aliceGate.release.countDown();
      awaitEdt(() -> !panel.aggregating && panel.cards.get(ScopeInspectionDashboard.Group.SECRETS).count == 2500, "Alice aggregate");
      edt(() -> {
        panel.cards.get(ScopeInspectionDashboard.Group.SECRETS).doClick(0);
        check(panel.table.getRowCount() == 2500 && panel.visibleRows.stream().allMatch(row -> row.account().equals("Alice")),
            "latest account completion exposes only that account's detail population");
      });

      Gate oldRunGate = barrier.hold("old-run-held", "Alice");
      edt(() -> panel.update(snapshot("live-run", "old-run-held", all), 30, "https://dashboard-async.test/"));
      entered(oldRunGate, "old run before transition");
      var fresh = snapshot("fresh-run", "new-small-run", List.of(finding(9000, "Carol")));
      edt(() -> {
        panel.update(fresh, 30, "https://fresh-dashboard.test/");
        panel.cards.get(ScopeInspectionDashboard.Group.SECRETS).doClick(0);
        check(panel.current == fresh && !panel.aggregating && panel.table.getRowCount() == 1
            && panel.visibleRows.get(0).account().equals("Carol"),
            "a new small run replaces the previous context immediately while old worker work is held");
      });
      oldRunGate.release.countDown(); idle(panel);
      edt(() -> check(panel.current == fresh && panel.cards.get(ScopeInspectionDashboard.Group.SECRETS).count == 1
          && panel.visibleRows.stream().allMatch(row -> row.account().equals("Carol")),
          "old-run completion cannot resurrect earlier accounts or counts after a run transition"));

      Gate clearGate = barrier.hold("clear-held", "");
      edt(() -> panel.update(snapshot("clear-run", "clear-held", all), 30, "https://dashboard-async.test/"));
      entered(clearGate, "projection before clear");
      edt(() -> {
        panel.clear();
        check(panel.current.id().isEmpty() && !panel.aggregating && panel.model.getRowCount() == 0
            && panel.cards.values().stream().allMatch(card -> card.count == 0) && !panel.original.isEnabled(),
            "clear displays an actually empty context immediately rather than an unfinished previous aggregate");
      });
      clearGate.release.countDown(); idle(panel);
      edt(() -> check(panel.current.id().isEmpty() && panel.visibleRows.isEmpty() && panel.model.getRowCount() == 0
          && panel.cards.values().stream().allMatch(card -> card.count == 0),
          "late pre-clear completion is discarded after the worker and EDT callbacks have drained"));

      Gate closeGate = barrier.hold("close-held", "");
      edt(() -> panel.update(snapshot("close-run", "close-held", all), 30, "https://dashboard-async.test/"));
      entered(closeGate, "projection before close");
      edt(panel::close);
      closeGate.release.countDown(); idle(panel);
      edt(() -> {
        panel.update(first, 30, "https://dashboard-async.test/");
        check(panel.closed && panel.current.id().isEmpty() && panel.model.getRowCount() == 0
            && !panel.original.isEnabled() && !panel.repeater.isEnabled() && !panel.evidence.isEnabled()
            && !panel.inspect.isEnabled(),
            "close rejects both in-flight completion and subsequent updates and disables all original actions");
      });
      check(barrier.maximumActive.get() == 1 && barrier.threads.size() == 1 && barrier.offEdt.get() && barrier.daemon.get(),
          "all large calculations across context transitions stay on one daemon worker with at most one active calculation");
      check(PayloadIntegrationTest.sent.get() == networkBefore && actions.handoffs.get() == 0,
          "aggregation, queued navigation and lifecycle transitions perform no target HTTP or implicit original handoffs");
    } finally {
      barrier.releaseAll();
      edt(panel::close);
    }
    System.out.println("ScopeInspectionDashboardAsyncTest: " + checks + " checks passed (controlled projection worker, no HTTP)");
  }
}
