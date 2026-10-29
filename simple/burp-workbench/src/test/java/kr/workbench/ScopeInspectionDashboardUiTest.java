package kr.workbench;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.RowSorter;
import javax.swing.SortOrder;

/** Production dashboard interactions and original handoffs without running an inspection. */
public final class ScopeInspectionDashboardUiTest extends WorkbenchTest {
  private static ScopeInspection.Finding finding(String category, String title, String account,
      String severity, String original, String url) {
    return new ScopeInspection.Finding(category, title, url, "GET", account, "형식 후보", severity,
        "유효성 미검증", "가림 처리한 탐지 근거", original, 7, 23, "shared-fingerprint");
  }

  private static ScopeInspection.Snapshot snapshot(String id, List<ScopeInspection.Finding> findings,
      List<ScopeInspection.Page> pages, List<ScopeInspection.Candidate> candidates) {
    return new ScopeInspection.Snapshot(id, ScopeInspection.State.RUNNING, "실행 중", 12, 10, 2,
        1, 1, 2, findings, pages, candidates, 1, 0);
  }

  private static void account(ScopeDashboardPanel panel, String value) {
    for (int i = 0; i < panel.account.getItemCount(); i++)
      if (panel.account.getItemAt(i).value().equals(value)) { panel.account.setSelectedIndex(i); return; }
    throw new AssertionError("Missing account " + value);
  }

  private static void selectOriginal(ScopeDashboardPanel panel, String original) {
    for (int row = 0; row < panel.visibleRows.size(); row++) {
      if (original.equals(panel.visibleRows.get(row).originalId())) {
        int view = panel.table.convertRowIndexToView(row);
        panel.table.setRowSelectionInterval(view, view); return;
      }
    }
    throw new AssertionError("Missing original " + original);
  }

  public static void main(String[] args) throws Exception {
    try (var fixture = new ScopeInspectionFixture(); var store = new DiskTrafficStore()) {
      var requestA = ScopeInspectionFixture.wireRequest(fixture.origin + "/app/config-a", "GET", List.of(), new byte[0]);
      var requestB = ScopeInspectionFixture.wireRequest(fixture.origin + "/app/config-b", "GET", List.of(), new byte[0]);
      var response = ScopeInspectionFixture.wireResponse(200,
          List.of(header("Content-Type", "application/json")), "{\"example\":true}".getBytes(StandardCharsets.UTF_8));
      var originalA = store.append("대시보드 UI 회귀", "Alice", requestA, response);
      var originalB = store.append("대시보드 UI 회귀", "Bob", requestB, response);
      var findings = List.of(
          finding("SECRET", "alpha · 키 후보", "Alice", "높음", originalA.id(), originalA.url()),
          finding("SECRET", "bravo · 키 후보", "Bob", "HIGH", originalB.id(), originalB.url()),
          finding("SECRET", "charlie · 연결 문자열 후보", "Alice", "MEDIUM", originalA.id(), originalA.url()),
          finding("SECRET", "delta · 원본 없는 후보", "Alice", "LOW", "", originalA.url()),
          finding("API", "API 경로 후보", "Alice", "INFO", "", fixture.origin + "/app/api"),
          finding("ENVIRONMENT", "서버 환경 단서", "Bob", "INFO", originalB.id(), originalB.url()),
          finding("ACCESS", "계정별 응답 차이", "계정 비교 · 조건 r1", "INFO", originalA.id(), originalA.url()),
          finding("COVERAGE", "일부 응답 분석", "전체", "INFO", "", fixture.origin + "/app/partial"));
      var pages = List.of(
          new ScopeInspection.Page(originalA.url(), "GET", "Alice", 200, "JSON", true, "", "a", originalA),
          new ScopeInspection.Page(originalB.url(), "GET", "Bob", 200, "JSON", true, "", "b", originalB));
      var candidates = List.of(new ScopeInspection.Candidate(fixture.origin + "/app/not-visited", "POST", "JS", "자동 실행 제외", false));
      var first = snapshot("dashboard-ui-a", findings, pages, candidates);
      var opened = new AtomicInteger();
      var selectedOriginal = new AtomicReference<String>();
      var selectedRepeater = new AtomicReference<String>();
      var selectedEvidence = new AtomicReference<String>();
      var stale = new AtomicBoolean();
      var holder = new AtomicReference<ScopeDashboardPanel>();
      var actions = new ScopeDashboardPanel.Actions() {
        public void inspect() { opened.incrementAndGet(); }
        public void original(String id) {
          if (stale.get()) throw new IllegalArgumentException("프로젝트가 바뀌어 현재 실행의 원본을 연결할 수 없습니다.");
          selectedOriginal.set(id);
        }
        public void repeater(String id) { selectedRepeater.set(id); }
        public void evidence(String id) { selectedEvidence.set(id); }
      };
      edt(() -> holder.set(new ScopeDashboardPanel(actions)));
      var panel = holder.get();
      try {
        edt(() -> {
          check(opened.get() == 0 && fixture.visits.isEmpty(), "dashboard construction neither opens inspection nor sends HTTP");
          check(panel.current.id().isEmpty() && panel.emptyMessage.getText().contains("아직 실행 결과"), "first-use state explains that an inspection is required");
          for (var card : panel.cards.values()) { check(card.count == 0 && card.isEnabled(), "zero-count dashboard cards remain inspectable"); card.doClick(0); }
          check(panel.table.getRowCount() == 0 && !panel.original.isEnabled(), "empty cards never enable invented originals");
          panel.inspect.doClick(0);
          check(opened.get() == 1, "inspection button delegates exactly once to its existing controller");
          panel.update(first, 40, fixture.origin + "/app/");
          check(panel.traffic.getText().contains("전송 12 / 한도 40") && panel.traffic.getText().contains("실패 1"), "request counters preserve budget and failure semantics without a success percentage");
          check(!panel.traffic.getText().contains("%"), "request budget is never presented as site completion rate");
          var expected = Map.of(ScopeInspectionDashboard.Group.PATHS, 2, ScopeInspectionDashboard.Group.API, 1,
              ScopeInspectionDashboard.Group.ENVIRONMENT, 1, ScopeInspectionDashboard.Group.SECRETS, 4,
              ScopeInspectionDashboard.Group.ACCESS, 1, ScopeInspectionDashboard.Group.COVERAGE, 2);
          for (var entry : expected.entrySet()) {
            var card = panel.cards.get(entry.getKey());
            check(card.count == entry.getValue(), "card count retains the expected " + entry.getKey() + " result meaning");
            card.doClick(0);
            check(panel.table.getRowCount() == card.count, "clicking " + entry.getKey() + " opens precisely the counted rows");
          }
          panel.severityButtons.get("HIGH").doClick(0);
          check(panel.group == ScopeInspectionDashboard.Group.SECRETS && panel.table.getRowCount() == 2,
              "high-importance chart opens only the two normalized high secret candidates");
          check(panel.filterSummary.getText().contains("전체 4건") && panel.filterSummary.getText().contains("2건"),
              "filtered list distinguishes the full secret count from its high-importance subset");
          account(panel, "Alice");
          check(panel.cards.get(ScopeInspectionDashboard.Group.SECRETS).count == 3 && panel.table.getRowCount() == 1,
              "account card total and severity-filtered list retain their separate counts");
          panel.update(first, 40, fixture.origin + "/app/");
          check(panel.selectedAccount().equals("Alice") && panel.selectedSeverity.equals("HIGH") && panel.table.getRowCount() == 1,
              "live same-run refresh preserves both account and importance filters");
          panel.reset.doClick(0);
          check(panel.selectedAccount().isEmpty() && panel.selectedSeverity.isEmpty() && panel.table.getRowCount() == 4,
              "clear conditions restores the full current category without changing its meaning");
          panel.table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
          selectOriginal(panel, originalB.id());
          String selectedId = panel.selectedRow().id();
          panel.detail.select(6, 24);
          String selectedDetail = panel.detail.getSelectedText();
          var reversed = new ArrayList<>(findings); Collections.reverse(reversed);
          panel.update(snapshot(first.id(), reversed, List.of(pages.get(1), pages.get(0)), candidates), 40, fixture.origin + "/app/");
          check(panel.selectedRow() != null && panel.selectedRow().id().equals(selectedId),
              "reordered live snapshot and sorted table retain the exact selected finding identity");
          check(panel.detail.getSelectionStart() == 6 && panel.detail.getSelectionEnd() == 24
              && selectedDetail.equals(panel.detail.getSelectedText()),
              "unchanged selected detail survives snapshot refresh without resetting text selection or caret");
          check(panel.detail.getText().contains(originalB.id()) && panel.detail.getText().contains("본문 줄: 7"),
              "selected detail resolves the same original and exact source position");
          panel.original.doClick(0); panel.repeater.doClick(0); panel.evidence.doClick(0);
          check(originalB.id().equals(selectedOriginal.get()) && originalB.id().equals(selectedRepeater.get())
              && originalB.id().equals(selectedEvidence.get()), "all explicit handoffs receive the selected immutable original ID");
          stale.set(true); panel.original.doClick(0); stale.set(false);
          check(panel.table.getSelectedRow() < 0 && !panel.original.isEnabled() && !panel.repeater.isEnabled()
              && !panel.evidence.isEnabled() && panel.detail.getText().contains("프로젝트가 바뀌어"),
              "a stale original callback is explained without escaping EDT or retaining an actionable selection");
          for (int i = 0; i < panel.visibleRows.size(); i++) if (panel.visibleRows.get(i).originalId().isEmpty()) {
            int view = panel.table.convertRowIndexToView(i); panel.table.setRowSelectionInterval(view, view); break;
          }
          check(!panel.original.isEnabled() && !panel.repeater.isEnabled() && !panel.evidence.isEnabled(),
              "a candidate without a current original cannot hand off stale evidence");
          panel.categoryButtons.get(ScopeInspectionDashboard.Group.API).doClick(0);
          check(panel.table.getRowCount() == 1 && panel.group == ScopeInspectionDashboard.Group.API,
              "classification bars share the same detail-navigation contract as cards");
          panel.severityButtons.get("INFO").doClick(0);
          check(panel.table.getRowCount() == 0 && panel.emptyMessage.getText().contains("조건에 맞는 결과"),
              "zero-result importance filter gives a recoverable empty state");
          check(!panel.original.isEnabled() && panel.detail.getText().contains("목록에서"),
              "empty filter clears selected detail and disables all original actions");
          panel.cards.get(ScopeInspectionDashboard.Group.SECRETS).doClick(0);
          WorkbenchUi.style(panel);
          var renderer = panel.table.getCellRenderer(0, 0);
          var rendered = (JComponent) renderer.getTableCellRendererComponent(panel.table,
              "<html><img src='https://invalid.invalid/never'>", false, false, 0, 0);
          check(Boolean.TRUE.equals(rendered.getClientProperty("html.disable"))
              && rendered.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) == null,
              "theme restyling cannot interpret untrusted result cells as Swing HTML");
          check(Boolean.TRUE.equals(panel.target.getClientProperty("html.disable")), "SCOPE text is always literal");
          var accountCell = (JComponent) panel.account.getRenderer().getListCellRendererComponent(new javax.swing.JList<>(),
              new ScopeDashboardPanel.AccountChoice("<html><img src='https://invalid.invalid/never'>"), 0, false, false);
          check(Boolean.TRUE.equals(accountCell.getClientProperty("html.disable"))
              && accountCell.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) == null,
              "account labels cannot interpret untrusted text as Swing HTML");
          var orphan = finding("SECRET", "원본 ID만 남은 후보", "Alice", "HIGH", "missing-original", originalA.url());
          panel.update(snapshot(first.id(), List.of(orphan), pages, List.of()), 40, fixture.origin + "/app/");
          panel.cards.get(ScopeInspectionDashboard.Group.SECRETS).doClick(0);
          check(panel.selectedRow() != null && !panel.original.isEnabled() && !panel.repeater.isEnabled()
              && !panel.evidence.isEnabled(), "an original ID absent from the current packet snapshot never enables handoffs");
          panel.update(first, 40, fixture.origin + "/app/");
          account(panel, "Alice"); panel.severityButtons.get("HIGH").doClick(0);
          panel.update(snapshot("dashboard-ui-b", findings, pages, candidates), 50, fixture.origin + "/app/");
          check(panel.group == ScopeInspectionDashboard.Group.PATHS && panel.selectedAccount().isEmpty()
              && panel.selectedSeverity.isEmpty() && panel.table.getSelectedRow() < 0,
              "a new execution resets group, account, importance and selected original");
          check(!panel.original.isEnabled(), "new execution does not inherit an earlier selected original");
          panel.clear();
          check(panel.current.id().isEmpty() && panel.visibleRows.isEmpty() && panel.table.getRowCount() == 0,
              "clear releases the previous snapshot and result metadata");
          var card = panel.cards.get(ScopeInspectionDashboard.Group.SECRETS);
          var bar = panel.severityButtons.get("HIGH");
          int previousScale = WorkbenchUi.appearanceScale(); boolean previousCompact = WorkbenchUi.appearanceCompact();
          try {
            WorkbenchUi.setAppearance(100, false);
            int cardHeight = card.getPreferredSize().height, barHeight = bar.getPreferredSize().height;
            WorkbenchUi.setAppearance(150, false);
            var size = card.getPreferredSize(); card.setSize(size);
            var image = new java.awt.image.BufferedImage(size.width, size.height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); card.paint(graphics); graphics.dispose();
            check(size.height > cardHeight && bar.getPreferredSize().height > barHeight
                && card.getMinimumSize().height <= size.height && bar.getMinimumSize().height <= bar.getPreferredSize().height,
                "existing metric and chart controls grow preferred and minimum paint bounds after appearance scaling");
            var metrics = card.getFontMetrics(WorkbenchUi.font().deriveFont(java.awt.Font.BOLD, WorkbenchUi.displaySize(28)));
            check(WorkbenchUi.displayPixels(58) + metrics.getDescent() < size.height,
                "scaled metric number baseline and descenders remain inside the same instance's paint area");
          } finally { WorkbenchUi.setAppearance(previousScale, previousCompact); WorkbenchUi.style(panel); }
        });
        panel.update(first, 40, fixture.origin + "/app/");
        panel.update(snapshot("dashboard-ui-latest", findings, pages, candidates), 45, fixture.origin + "/app/");
        edt(() -> check(panel.current.id().equals("dashboard-ui-latest"), "background publications coalesce to the newest execution on EDT"));
        panel.close();
        panel.update(first, 40, fixture.origin + "/app/");
        edt(() -> {
          check(panel.current.id().isEmpty() && panel.visibleRows.isEmpty() && !panel.inspect.isEnabled(),
              "close releases snapshots and ignores subsequent publications");
          panel.inspect.doClick(0); panel.original.doClick(0);
          check(opened.get() == 1, "closed dashboard does not invoke retained controller actions");
        });
        check(fixture.visits.isEmpty(), "dashboard filtering, sorting, callbacks, refresh and close send zero target HTTP");
      } finally { edt(panel::close); }
    }
    System.out.println("ScopeInspectionDashboardUiTest: " + checks + " checks passed (production Swing; no inspection or target HTTP)");
  }
}
