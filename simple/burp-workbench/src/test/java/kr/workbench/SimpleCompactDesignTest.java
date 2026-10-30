package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.*;

/** Compact readers preserve selection identity, raw inputs, and explicit execution. */
public final class SimpleCompactDesignTest extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    BountyFeaturesTest.factory();
    var entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks) PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(() -> entry.initialize(SimpleWorkflowTest.previewApi()));
    var extension = (WorkbenchExtension) get(entry, "workbench");
    var root = (JPanel) get(extension, "root");
    var auth = (AuthComparePanel) get(extension, "authCompare");
    var recon = (ResponseReconPanel) get(extension, "responseRecon");
    var bounty = (BountySupportPanel) get(extension, "bountySupport");
    Object payload = get(entry, "payloadRunner");
    try {
      edt(() -> {
        var request = request("https://compact.test/documents/101", "GET", true, List.of());
        auth.observe(AuthRecordTest.q("A"), response(200,"A"));
        auth.observe(AuthRecordTest.q("B"), response(200,"B"));
        var accounts=new java.util.ArrayList<>(auth.credentials.values());
        auth.assign(0,accounts.get(0));auth.assign(1,accounts.get(1));
        var run = new AuthComparePanel.Run(1, request, response(200, "BASELINE_ORIGINAL"), auth.context());
        run.packets[1].request = request;
        run.packets[1].response = response(403, "ACCOUNT_B_ORIGINAL");
        auth.inlinePreview.showRun(run);
        auth.inlinePreview.account.setSelectedIndex(1);
        check(auth.inlinePreview.baseline.text.getText().contains("BASELINE_ORIGINAL")
            && auth.inlinePreview.response.text.getText().contains("ACCOUNT_B_ORIGINAL"),
            "inline account choice displays exact baseline and selected account response");
        check(SwingUtilities.isDescendingFrom(auth.inlinePreview, auth), "comparison reader is mounted in the main workspace");
        auth.inlinePreview.showRun(null);
        check(!auth.inlinePreview.response.text.getText().contains("ACCOUNT_B_ORIGINAL"), "cleared account selection removes stale response");

        var q = request("https://compact.test/api/item?filter=[id]", "GET", true, List.of());
        var packet = burp.api.montoya.http.message.HttpRequestResponse.httpRequestResponse(q, response(200, "EXACT_FINDING_PACKET"));
        recon.displayedFindingKeys = List.of("first", "second");
        recon.displayedFindingPackets = Map.of("second", packet);
        recon.displayedAffected = Map.of("https://compact.test|rule-b|Body: 42|marker", Set.of(q.url()));
        recon.findings.addRow(new Object[]{"rule-a", "https://compact.test/aaa", "Header", "other", 1});
        recon.findings.addRow(new Object[]{"rule-b", q.url(), "Body: 42", "marker", 1});
        var search = (JTextField) SimpleWorkflowTest.named(recon, "wb.response.search");
        search.setText("[id]");
        check(recon.findingTable.getRowCount() == 1, "finding search treats bracket characters literally");
        recon.findingTable.setRowSelectionInterval(0, 0);
        check(recon.findingResponse.text.getText().contains("EXACT_FINDING_PACKET"), "filtered row uses model identity to show the correct response");
        check(recon.findingDetail.getText().contains(q.url()), "affected URL grouping retains its original normalized-location mapping");
        search.setText("does-not-exist");
        check(recon.findingTable.getSelectedRow() == -1 && !recon.findingResponse.text.getText().contains("EXACT_FINDING_PACKET"), "filtering away a row clears stale packet and selection");

        EvidenceEditingTest.append(bounty, "FIRST"); EvidenceEditingTest.append(bounty, "SECOND");
        bounty.evidenceTable.setRowSelectionInterval(1, 1);
        String id = bounty.evidence.get(1).evidenceId();
        check(bounty.evidencePacketPreview.getText().contains("SECOND"), "evidence reader shows selected stored response");
        bounty.impact.setText("keep authored impact"); bounty.repro.setText("keep authored steps");
        bounty.evidenceTable.setRowSelectionInterval(0, 0);
        check(bounty.impact.getText().equals("keep authored impact") && bounty.repro.getText().equals("keep authored steps"), "reading another packet does not overwrite authored report text");
        check(bounty.evidence.get(1).evidenceId().equals(id), "reading evidence does not replace immutable evidence identity");

        var environment = (EnvironmentPanel) SimpleWorkflowTest.unchecked(extension, "environments");
        var toggle = (JToggleButton) SimpleWorkflowTest.named(environment, "wb.environment.observations.toggle");
        check(!toggle.isSelected(), "environment observations start compact with candidates in focus");
        toggle.doClick(); check(toggle.isSelected() && environment.technologies.isVisible(), "observation disclosure retains the existing environment table");

        var phases = (JTabbedPane) SimpleWorkflowTest.unchecked(payload, "workflow");
        var input = (JTextArea) SimpleWorkflowTest.unchecked(payload, "request");
        String original = input.getText();
        phases.setSelectedIndex(1); phases.setSelectedIndex(0);
        check(input.getText().equals(original), "switching preparation/results preserves exact payload input");
        check(!(boolean) SimpleWorkflowTest.unchecked(payload, "running") && PayloadIntegrationTest.sent.get() == 0, "changing phase and reading details never starts a payload run or sends HTTP");

        for (String tool : List.of("traffic", "mapper", "auth", "js", "environment", "response.recon", "payload", "bounty")) {
          SimpleWorkflowTest.click(root, "wb.simple.tool." + tool);
          check(SimpleWorkflowTest.toolSelected(root, tool), "compact design keeps the exact " + tool + " folder route");
        }
      });
    } finally { SimpleWorkflowTest.unload(); }
    System.out.println("SimpleCompactDesignTest: " + checks + " checks passed");
  }
}
