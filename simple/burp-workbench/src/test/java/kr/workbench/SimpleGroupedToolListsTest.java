package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import javax.swing.*;
import javax.swing.table.TableModel;

/** Grouped discovery lists retain the actual table models, original values and target actions. */
public final class SimpleGroupedToolListsTest extends WorkbenchTest {
  static boolean contains(Component root, Component wanted) {
    if (root == wanted) return true;
    if (root instanceof Container parent)
      for (Component child : parent.getComponents()) if (contains(child, wanted)) return true;
    return false;
  }

  static void owns(Component root, String name, JTable table, TableModel original) {
    Component grouped = SimpleWorkflowTest.named(root, name + ".groups");
    check(grouped != null && contains(grouped, table), name + " groups the retained actual table");
    check(table.getModel() == original, name + " keeps its original model for selection and actions");
  }

  public static void main(String[] args) throws Exception {
    BountyFeaturesTest.factory();
    var entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks) PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(() -> entry.initialize(SimpleWorkflowTest.previewApi()));
    var extension = (WorkbenchExtension) get(entry, "workbench");
    var root = (JPanel) get(extension, "root");
    var auth = (AuthComparePanel) get(extension, "authCompare");
    var response = (ResponseReconPanel) get(extension, "responseRecon");
    var exposure = (ExposureResearchPanel) get(extension, "exposureResearch");
    var activity = (ActivityPanel) get(extension, "activity");
    var bounty = (BountySupportPanel) get(extension, "bountySupport");
    int sent = PayloadIntegrationTest.sent.get();
    try {
      edt(() -> {
        owns(root, "wb.auth.history.grouped", auth.history, auth.historyModel);
        owns(root, "wb.response.findings.grouped", response.findingTable, response.findings);
        owns(root, "wb.response.api.grouped", response.apiTable, response.documents);
        owns(root, "wb.response.flows.grouped", response.flowTable, response.flowModel);
        owns(root, "wb.exposure.search.grouped", exposure.exposureTable, exposure.exposureModel);
        owns(root, "wb.activity.endpoints.grouped", activity.table, activity.model);
        owns(root, "wb.activity.pending.grouped", activity.pendingTable, activity.pendingModel);
        owns(root, "wb.bounty.flow.grouped", bounty.flowTable, bounty.flowModel);
        owns(root, "wb.bounty.evidence.grouped", bounty.evidenceTable, bounty.evidenceModel);
      });
      String first = "https://grouped.example.test/admin", second = "https://grouped.example.test/api/docs/";
      edt(() -> {
        extension.openFeature("exposure");
        exposure.target.setText("https://grouped.example.test");
        exposure.importUrls(first + "\n" + second);
      });
      edt(() -> {});
      edt(() -> {
        check(exposure.exposureModel.getRowCount() == 2, "grouping preserves both imported discovery records");
        check(List.of(first, second).contains(exposure.exposureModel.getValueAt(0, 1)),
            "the discovery model keeps the complete original URL");
        exposure.exposureTable.setRowSelectionInterval(0, 0);
        var selected = exposure.selectedExposure();
        check(selected != null && exposure.exposureDetail.getText().contains(selected.url()),
            "selected discovery still opens the exact original detail");
        int column = exposure.exposureTable.convertColumnIndexToView(1);
        JLabel renderer = (JLabel) exposure.exposureTable.prepareRenderer(
            exposure.exposureTable.getCellRenderer(0, column), 0, column);
        check(!renderer.getText().contains("https://") && !renderer.getText().equals(selected.url()),
            "discovery list uses a short label while the original stays in the model");
        check(exposure.exposureRepeater.getActionListeners().length > 0,
            "grouping retains the original explicit Repeater handler");
      });
      check(PayloadIntegrationTest.sent.get() == sent,
          "grouped list construction import and selection send zero target HTTP");
    } finally { SimpleWorkflowTest.unload(); }
    System.out.println("SimpleGroupedToolListsTest: " + checks + " checks passed (actual grouped tables and original discovery detail, zero HTTP)");
  }
}
