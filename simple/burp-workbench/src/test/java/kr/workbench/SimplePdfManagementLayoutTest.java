package kr.workbench;

import java.awt.BorderLayout;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

/** Approved report/readers retain real selection models and explicit local transformations. */
public final class SimplePdfManagementLayoutTest extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    AtomicInteger actions = new AtomicInteger();
    edt(() -> {
      var activity = new ActivityPanel(SimpleWorkflowTest.previewApi());
      var dashboard = new ScopeDashboardPanel(new ScopeDashboardPanel.Actions() {
        public void inspect() { actions.incrementAndGet(); }
        public void original(String id) { actions.incrementAndGet(); }
        public void repeater(String id) { actions.incrementAndGet(); }
        public void evidence(String id) { actions.incrementAndGet(); }
      });
      try {
        JPanel search = (JPanel) SimpleWorkflowTest.named(activity, "wb.placement.activity.filters");
        BorderLayout searchLayout = (BorderLayout) search.getLayout();
        check(searchLayout.getLayoutComponent(BorderLayout.CENTER) == activity.search
                && searchLayout.getLayoutComponent(BorderLayout.EAST) == activity.filter,
            "URL search precedes its retained filter in the approved list card");
        JPanel inputActions = (JPanel) SimpleWorkflowTest.named(activity, "wb.actions.activity.parameters");
        check(((BorderLayout) inputActions.getParent().getParent().getLayout()).getLayoutComponent(BorderLayout.SOUTH) == inputActions.getParent(),
            "input completion belongs to the footer beneath the selected input table");
        check(SimpleWorkflowTest.named(activity, "wb.card.URL 점검 목록") != null,
            "the URL list has its own card instead of sharing selected-URL context");

        String body = "첫째 관측 근거\n\n둘째 근거와 원문 <literal>";
        var row = new ScopeInspectionDashboard.Row("row-17", "API", "실제 선택 결과", "Bob", "INFO",
            "직접 확인 필요", "관찰", "GET", "https://pdf.test/api/17", body, "original-17:23", 7, 31);
        dashboard.visibleRows = List.of(row);
        dashboard.model.addRow(new Object[] {row.title(), row.account(), row.severity(), row.state(), row.confidence(), row.method(), row.url()});
        dashboard.table.setRowSelectionInterval(0, 0);
        check(dashboard.detailTitle.getText().equals(row.title()) && dashboard.detailFacts.getText().contains("계정: Bob"),
            "dashboard sections follow the actual selected model row");
        check(dashboard.detailSummary.getText().equals(body),
            "evidence summary preserves all authored paragraphs and literal source characters");
        check(dashboard.detailOrigin.getText().contains("original-17:23")
                && dashboard.detailOrigin.getText().contains("본문 줄: 7 · 문자 위치: 31")
                && dashboard.detail.getText().contains(body),
            "folded provenance retains exact original identity, offsets and the complete source document");
        dashboard.table.clearSelection();
        check(!dashboard.detailFacts.isVisible() && !dashboard.detailSummary.getText().contains(body)
                && !dashboard.detailOrigin.getText().contains("original-17:23"),
            "clearing selection removes stale facts, evidence and original identity together");
        check(actions.get() == 0, "constructing, selecting and regrouping dashboard content invokes no target or handoff action");

        var extension = new WorkbenchExtension();
        try {
          var method = WorkbenchExtension.class.getDeclaredMethod("codecPanel");
          method.setAccessible(true);
          JPanel codec = (JPanel) method.invoke(extension);
          check(SimpleWorkflowTest.named(codec, "wb.card.입력") != null
                  && SimpleWorkflowTest.named(codec, "wb.card.출력") != null,
              "codec input and output use separate retained reader cards");
          JPanel output = (JPanel) SimpleWorkflowTest.named(codec, "wb.actions.data.codec.output");
          check(output.getComponent(0).getName().equals("wb.data.codec.copy"),
              "output copy precedes secondary reuse operations");
          var input = (JTextArea) get(extension, "codecIn");
          var result = (JTextArea) get(extension, "codecOut");
          input.setText("keep 한글 & bytes");
          JComboBox<?> operation = (JComboBox<?>) SimpleWorkflowTest.named(codec, "wb.data.codec.operation");
          operation.setSelectedIndex(2);
          ((JButton) SimpleWorkflowTest.named(codec, "wb.data.codec.convert")).doClick(0);
          check(input.getText().equals("keep 한글 & bytes") && result.getText().equals(Core.transform("Base64 인코딩", input.getText())),
              "the visible conversion control preserves input and uses the existing local transformation");
        } catch (Exception error) { throw new AssertionError(error); }
      } finally { activity.close(); dashboard.close(); }
    });
    System.out.println("SimplePdfManagementLayoutTest: " + checks + " checks passed");
  }
}
