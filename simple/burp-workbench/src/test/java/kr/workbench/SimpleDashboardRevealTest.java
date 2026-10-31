package kr.workbench;

import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** User drill-down reveals results; automatic publications and invalidated requests never scroll. */
public final class SimpleDashboardRevealTest extends WorkbenchTest {
  private static void layout(Component component) {
    if (component instanceof JTable table) {
      var scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
      if (scroll != null && (scroll.getColumnHeader() == null || scroll.getColumnHeader().getView() != table.getTableHeader()))
        scroll.setColumnHeaderView(table.getTableHeader());
    }
    if (component instanceof Container container) {
      // Detached headless trees need explicit invalidation when disclosures change size.
      container.invalidate();
      container.doLayout(); for (var child : container.getComponents()) layout(child);
    }
  }
  private static void settle(JPanel host) throws Exception {
    for (int i = 0; i < 5; i++) { edt(() -> layout(host)); edt(() -> {}); }
  }
  private static JScrollPane scroll(ScopeDashboardPanel panel) {
    return (JScrollPane) SimpleWorkflowTest.named(panel, "wb.scope.dashboard.scroll");
  }
  private static int y(ScopeDashboardPanel panel) { return scroll(panel).getViewport().getViewPosition().y; }
  private static void position(ScopeDashboardPanel panel, int y) { scroll(panel).getViewport().setViewPosition(new Point(0, y)); }
  private static Rectangle shown(JPanel host, Component component) {
    if (!component.isVisible()) return new Rectangle();
    var result = SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), host);
    for (var parent = component.getParent(); parent != null && parent != host; parent = parent.getParent()) {
      if (!parent.isVisible()) return new Rectangle();
      result = result.intersection(SwingUtilities.convertRectangle(parent, new Rectangle(0, 0, parent.getWidth(), parent.getHeight()), host));
    }
    return result.intersection(new Rectangle(0, 0, host.getWidth(), host.getHeight()));
  }
  private static boolean resultsVisible(JPanel host, ScopeDashboardPanel panel) {
    return shown(host, panel.table).height >= 80 && shown(host, panel.detail).height >= 80;
  }
  private static void account(ScopeDashboardPanel panel, String value) {
    for (int i = 0; i < panel.account.getItemCount(); i++)
      if (panel.account.getItemAt(i).value().equals(value)) { panel.account.setSelectedIndex(i); return; }
    throw new AssertionError("Missing account " + value);
  }
  private static ScopeInspection.Finding finding(String category, String title, String account,
      String severity, DiskTrafficStore.PacketRef original) {
    return new ScopeInspection.Finding(category, title, original.url(), "GET", account, "형식 후보", severity,
        "유효성 미검증", "합성 예제의 가림 처리된 근거", original.id(), 3, 12, title);
  }
  private static ScopeInspection.Snapshot snapshot(String id, List<ScopeInspection.Finding> findings,
      List<ScopeInspection.Page> pages, int sent) {
    return new ScopeInspection.Snapshot(id, ScopeInspection.State.READY, "합성 UI 검사", sent, pages.size(), 0, 0, 0, 0,
        findings, pages, List.of(), 1, 0);
  }
  public static void main(String[] args) throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    try (var fixture = new ScopeInspectionFixture(); var store = new DiskTrafficStore()) {
      var aliceRequest = ScopeInspectionFixture.wireRequest(fixture.origin + "/app/alice", "GET", List.of(), new byte[0]);
      var bobRequest = ScopeInspectionFixture.wireRequest(fixture.origin + "/app/bob", "GET", List.of(), new byte[0]);
      var response = ScopeInspectionFixture.wireResponse(200, List.of(header("Content-Type", "application/json")),
          "{\"synthetic\":true}".getBytes(StandardCharsets.UTF_8));
      var alice = store.append("대시보드 이동 합성 원본", "Alice", aliceRequest, response);
      var bob = store.append("대시보드 이동 합성 원본", "Bob", bobRequest, response);
      var pages = List.of(new ScopeInspection.Page(alice.url(), "GET", "Alice", 200, "JSON", true, "", "alice", alice),
          new ScopeInspection.Page(bob.url(), "GET", "Bob", 200, "JSON", true, "", "bob", bob));
      var findings = List.of(finding("API", "Alice API 후보", "Alice", "INFO", alice),
          finding("API", "Bob API 후보", "Bob", "INFO", bob),
          finding("SECRET", "Alice 높은 중요도 후보", "Alice", "HIGH", alice),
          finding("SECRET", "Bob 높은 중요도 후보", "Bob", "HIGH", bob),
          finding("SECRET", "Alice 중간 중요도 후보", "Alice", "MEDIUM", alice));
      var first = snapshot("dashboard-reveal", findings, pages, 0);
      var selectedOriginal = new AtomicReference<String>();
      var holder = new AtomicReference<ScopeDashboardPanel>(); var host = new JPanel(new BorderLayout());
      edt(() -> {
        var header = new JPanel(); header.setPreferredSize(new Dimension(1024, 130)); host.add(header, BorderLayout.NORTH);
        var panel = new ScopeDashboardPanel(new ScopeDashboardPanel.Actions() {
          public void inspect() {}
          public void original(String id) { selectedOriginal.set(id); }
          public void repeater(String id) {}
          public void evidence(String id) {}
        }, (source, selectedAccount) -> {
          if (source.findings().size() > 500) { entered.countDown(); release.await(); }
        });
        holder.set(panel); host.add(panel); host.setSize(1024, 720); host.setVisible(true);
        panel.update(first, 40, fixture.origin + "/app/");
      });
      var panel = holder.get();
      try {
        settle(host);
        edt(() -> {
          Component cards=SimpleWorkflowTest.named(panel,"wb.scope.dashboard.cards");
          check(y(panel)==0 && cards.getHeight()>0 && shown(host,cards).height==cards.getHeight(),
              "initial compact dashboard retains its full overview at scroll position zero: y="+y(panel));
        });
        for (String control : List.of("card.api", "bar.api", "severity.high")) {
          edt(() -> position(panel, 0));
          if(!control.startsWith("card.")){
            edt(() -> {
              var distribution=(JToggleButton)SimpleWorkflowTest.named(panel,"wb.flow.dashboard.distribution.toggle");
              if(!distribution.isSelected())distribution.doClick(0);
            });
            settle(host);
            edt(() -> {
              var chart=(JComponent)SimpleWorkflowTest.named(panel,"wb.scope.dashboard."+control);
              chart.scrollRectToVisible(new Rectangle(0,0,chart.getWidth(),chart.getHeight()));
            });
            settle(host);
            edt(() -> {
              Component chart=SimpleWorkflowTest.named(panel,"wb.scope.dashboard."+control);
              check(shown(host,chart).height==chart.getHeight()&&chart.getHeight()>0,
                  "expanded distribution makes the actual "+control+" action visible before use: bounds="+chart.getBounds()
                      +" shown="+shown(host,chart)+" scroll="+scroll(panel).getViewport().getViewPosition()
                      +" view="+scroll(panel).getViewport().getViewSize()+" extent="+scroll(panel).getViewport().getExtentSize());
            });
          }
          edt(() -> SimpleWorkflowTest.click(panel, "wb.scope.dashboard." + control));
          settle(host);
          edt(() -> check(resultsVisible(host, panel) && panel.selectedRow() != null,
              "one explicit " + control + " action reveals both the result list and selected detail"));
        }
        edt(() -> ((JToggleButton)SimpleWorkflowTest.named(panel,"wb.flow.dashboard.distribution.toggle")).doClick(0));
        settle(host);
        edt(() -> position(panel, 0));
        edt(() -> account(panel, "Bob")); settle(host);
        edt(() -> {
          check(resultsVisible(host, panel) && panel.selectedAccount().equals("Bob")
              && panel.selectedRow().originalId().equals(bob.id()), "account selection reveals Bob's precise original without changing the filter");
          panel.original.doClick(0);
          check(bob.id().equals(selectedOriginal.get()), "revealed row hands off the same immutable Bob original");
          position(panel, 0);
        });
        edt(() -> panel.importance.setSelectedIndex(4)); settle(host);
        edt(() -> check(panel.table.getRowCount() == 0 && shown(host, panel.emptyMessage).height >= 40
            && shown(host, panel.detail).height >= 80 && !panel.original.isEnabled(),
            "an explicit zero-result filter reveals its recovery message and clears original actions"));
        edt(() -> position(panel, 0));
        edt(() -> panel.reset.doClick(0)); settle(host);
        edt(() -> check(resultsVisible(host, panel) && panel.table.getRowCount() == 3 && panel.selectedAccount().isEmpty(),
            "condition reset reveals the recovered list while preserving the selected category"));

        edt(() -> { position(panel, 42); panel.update(snapshot(first.id(), findings, pages, 1), 40, fixture.origin + "/app/"); });
        settle(host);
        edt(() -> check(y(panel) == 42, "automatic same-run publication preserves the user's reading position"));
        edt(() -> {
          position(panel, 0); panel.cards.get(ScopeInspectionDashboard.Group.API).doClick(0);
          position(panel, 42); panel.update(snapshot(first.id(), findings, pages, 2), 40, fixture.origin + "/app/");
        }); settle(host);
        edt(() -> check(y(panel) == 42, "a later manual viewport change cancels an already queued user reveal"));

        var many = new ArrayList<>(findings);
        for (int i = 0; i < 501; i++) many.add(finding("API", "추가 합성 API " + i, "Alice", "INFO", alice));
        edt(() -> { position(panel, 42); panel.update(snapshot(first.id(), many, pages, 3), 40, fixture.origin + "/app/"); });
        check(entered.await(15, TimeUnit.SECONDS), "large automatic projection enters the controlled background worker");
        edt(() -> check(y(panel) == 42 && panel.aggregating, "background aggregation does not reveal results on its own"));
        release.countDown(); ScopeInspectionUiTest.await(() -> !panel.aggregating, "background dashboard projection completes");
        settle(host);
        edt(() -> check(y(panel) == 42, "background projection completion preserves the reading position"));

        edt(() -> {
          position(panel, 0); panel.cards.get(ScopeInspectionDashboard.Group.API).doClick(0);
          panel.update(snapshot("replacement-run", findings, pages, 0), 40, fixture.origin + "/app/");
        }); settle(host);
        edt(() -> check(y(panel) == 0 && panel.current.id().equals("replacement-run"),
            "a new run rejects a reveal queued by the preceding execution"));
        // Clearing read-only prose can trigger Swing's existing caret visibility/layout adjustment.
        // Compare with the identical lifecycle action after its explicit reveal has already settled.
        var controlHolder = new AtomicReference<ScopeDashboardPanel>();
        var controlHost = new JPanel(new BorderLayout());
        edt(() -> {
          var header = new JPanel(); header.setPreferredSize(new Dimension(1024, 130));
          controlHost.add(header, BorderLayout.NORTH);
          var control = new ScopeDashboardPanel(new ScopeDashboardPanel.Actions() {
            public void inspect() {} public void original(String id) {}
            public void repeater(String id) {} public void evidence(String id) {}
          });
          controlHolder.set(control); controlHost.add(control); controlHost.setSize(1024, 720); controlHost.setVisible(true);
          control.update(first, 40, fixture.origin + "/app/");
        });
        var control = controlHolder.get(); settle(controlHost);
        edt(() -> control.cards.get(ScopeInspectionDashboard.Group.API).doClick(0)); settle(controlHost);
        edt(() -> { position(control, 0); control.clear(); }); settle(controlHost);
        var clearPosition = new java.util.concurrent.atomic.AtomicInteger();
        edt(() -> clearPosition.set(y(control)));
        edt(() -> control.update(first, 40, fixture.origin + "/app/")); settle(controlHost);
        edt(() -> control.cards.get(ScopeInspectionDashboard.Group.API).doClick(0)); settle(controlHost);
        edt(() -> { position(control, 0); control.close(); }); settle(controlHost);
        var closePosition = new java.util.concurrent.atomic.AtomicInteger();
        edt(() -> closePosition.set(y(control)));
        edt(() -> {
          panel.update(first, 40, fixture.origin + "/app/"); position(panel, 0);
          panel.cards.get(ScopeInspectionDashboard.Group.API).doClick(0); panel.clear();
        }); settle(host);
        edt(() -> check(y(panel) == clearPosition.get() && panel.current.id().isEmpty(),
            "clear or project invalidation with queued reveal ends at the same position as the settled-reveal control"));
        edt(() -> {
          panel.update(first, 40, fixture.origin + "/app/"); position(panel, 0);
          panel.cards.get(ScopeInspectionDashboard.Group.API).doClick(0); panel.close();
        }); settle(host);
        edt(() -> check(y(panel) == closePosition.get() && panel.current.id().isEmpty() && !panel.original.isEnabled(),
            "close with queued reveal matches the settled-reveal control and cannot restore a previous original"));
        check(fixture.visits.isEmpty(), "user reveal, filters, background publications and original handoffs send zero target HTTP");
      } finally { release.countDown(); edt(panel::close); }
    }
    System.out.println("SimpleDashboardRevealTest: " + checks + " checks passed (production Swing, synthetic packets, no native Burp or target HTTP)");
  }
}
