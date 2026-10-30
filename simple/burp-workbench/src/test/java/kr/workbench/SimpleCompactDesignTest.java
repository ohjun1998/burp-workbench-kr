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
        JTextArea longSource=new JTextArea("위치   "+"long-path/".repeat(300));
        JPanel facts=ReferenceUi.facts(longSource);facts.setSize(300,120);for(int pass=0;pass<6;pass++)UiLayoutTest.layout(facts);
        JScrollPane factScroll=(JScrollPane)facts.getComponent(0);
        check(factScroll.getViewport().getView().getPreferredSize().height>120,"long labeled values wrap and scroll instead of clipping the source");
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
        check(auth.inlinePreview.differences.getText().contains("403")&&auth.inlinePreview.differences.getText().contains("200"),"account difference tab compares the selected exact response status lines");
        var left=auth.inlinePreview.differenceBaseline;var right=auth.inlinePreview.differenceResponse;
        check(right.text.getText().contains("ACCOUNT_B_ORIGINAL")&&right.text.getHighlighter().getHighlights().length>0,"difference panes retain the selected packet with actual differing ranges");
        right.search.setText("ORIGINAL");right.search.setText("");
        check(right.text.getHighlighter().getHighlights().length>0,"clearing packet search preserves difference decoration");
        left.modePicker.setSelectedIndex(1);right.modePicker.setSelectedIndex(1);
        check(right.text.getHighlighter().getHighlights().length>0&&right.text.getText().contains("ACCOUNT_B_ORIGINAL"),"Raw mode reapplies differences without altering source");
        check(auth.accountCards[0].getParent()!=null&&auth.accountCards[2].getParent()==null&&auth.accountCards[3].getParent()!=null,"three summary cards occupy three cells without a blank unassigned account cell");
        auth.mapCandidate(accounts.get(0),new AuthAccount("Alice","alice","101","문서 소유자","alice@compact.test"));auth.refreshSlots();
        check(auth.accountCards[0].getAccessibleContext().getAccessibleName().contains("alice@compact.test")&&auth.accountCards[0].getAccessibleContext().getAccessibleName().contains("문서 소유자"),"account summary uses the assigned email and role without exposing session credentials");
        check(!auth.accountCards[3].getAccessibleContext().getAccessibleName().contains("alice@compact.test"),"anonymous card never inherits a login identity");
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
        check(recon.findingRequest.text.getText().contains("/api/item")&&recon.findingUrls.getText().contains(q.url()),"related-request and affected-URL tabs use the selected original mapping");
        search.setText("does-not-exist");
        check(recon.findingTable.getSelectedRow() == -1 && !recon.findingResponse.text.getText().contains("EXACT_FINDING_PACKET"), "filtering away a row clears stale packet and selection");
        check(!recon.findingRequest.text.getText().contains("/api/item")&&recon.findingUrls.getText().isEmpty(),"filtering clears both additional detail tabs");

        EvidenceEditingTest.append(bounty, "FIRST"); EvidenceEditingTest.append(bounty, "SECOND");
        bounty.evidenceTable.setRowSelectionInterval(1, 1);
        String id = bounty.evidence.get(1).evidenceId();
        check(bounty.evidencePacketPreview.getText().contains("SECOND"), "evidence reader shows selected stored response");
        bounty.impact.setText("keep authored impact"); bounty.repro.setText("keep authored steps");
        bounty.evidenceTable.setRowSelectionInterval(0, 0);
        check(bounty.impact.getText().equals("keep authored impact") && bounty.repro.getText().equals("keep authored steps"), "reading another packet does not overwrite authored report text");
        check(bounty.evidence.get(1).evidenceId().equals(id), "reading evidence does not replace immutable evidence identity");
        var evidenceSearch=(JTextField)SimpleWorkflowTest.named(bounty,"wb.bounty.evidence.search");
        evidenceSearch.setText("SECOND");bounty.evidenceTable.setRowSelectionInterval(0,0);
        check(bounty.evidencePacketPreview.getText().contains("SECOND"),"filtered evidence row resolves exact stored packet");
        bounty.removeSelectedEvidence();evidenceSearch.setText("");
        check(bounty.evidence.size()==1&&!bounty.evidence.get(0).evidenceId().equals(id),"remove through evidence search targets model identity");
        bounty.impact.setText("retain selected words");bounty.impact.select(7,15);
        JPanel editor=(JPanel)bounty.impact.getParent().getParent().getParent();SimpleWorkflowTest.clickText(editor,"B");
        check(bounty.impact.getText().equals("retain **selected** words")&&bounty.evidence.get(0).label().equals("FIRST"),"formatting changes only selected report text and preserves packet evidence");

        var environment = (EnvironmentPanel) SimpleWorkflowTest.unchecked(extension, "environments");
        var toggle = (JToggleButton) SimpleWorkflowTest.named(environment, "wb.environment.observations.toggle");
        check(!toggle.isSelected(), "environment observations start compact with candidates in focus");
        toggle.doClick(); check(toggle.isSelected() && environment.technologies.isVisible(), "observation disclosure retains the existing environment table");

        var phases = (JTabbedPane) SimpleWorkflowTest.unchecked(payload, "workflow");
        var input = (JTextArea) SimpleWorkflowTest.unchecked(payload, "request");
        String original = input.getText();
        phases.setSelectedIndex(1); phases.setSelectedIndex(0);
        check(input.getText().equals(original), "switching preparation/results preserves exact payload input");
        ((JToggleButton)SimpleWorkflowTest.named(root,"wb.payload.step.results")).doClick();check(phases.getSelectedIndex()==1,"visible results step opens the retained workflow page");
        ((JButton)SimpleWorkflowTest.named(root,"wb.payload.prepare")).doClick();check(phases.getSelectedIndex()==0&&input.getText().equals(original),"return to preparation preserves authored request");
        check(!(boolean) SimpleWorkflowTest.unchecked(payload, "running") && PayloadIntegrationTest.sent.get() == 0, "changing phase and reading details never starts a payload run or sends HTTP");

        for (String tool : List.of("traffic", "mapper", "auth", "js", "environment", "response.recon", "payload", "bounty")) {
          SimpleWorkflowTest.click(root, "wb.simple.tool." + tool);
          check(SimpleWorkflowTest.toolSelected(root, tool), "compact design keeps the exact " + tool + " folder route");
        }
        extension.openFeature("traffic");root.setSize(1600,1000);for(int i=0;i<6;i++)UiLayoutTest.layout(root);
        var brand=(JLabel)SimpleWorkflowTest.named(root,"wb.navigation.brand");var bar=SimpleWorkflowTest.named(root,"wb.navigation.toolbar");
        check(brand.getText().equals("Workbench")&&bar.getHeight()<=50,"approved shell has the compact Workbench brand bar");
        var tree=(JTree)SimpleWorkflowTest.named(root,"wb.navigation.tree");var nodes=((javax.swing.tree.DefaultMutableTreeNode)tree.getModel().getRoot()).depthFirstEnumeration();int leaves=0;while(nodes.hasMoreElements())if(((javax.swing.tree.DefaultMutableTreeNode)nodes.nextElement()).isLeaf())leaves++;check(leaves==10,"approved sidebar has ten leaves in five groups; auxiliary routes remain reachable separately");
        var nav=(JSplitPane)SimpleWorkflowTest.named(root,"wb.navigation.split");check(Math.abs(nav.getDividerLocation()-220)<10,"approved sidebar retains the reference width at 1600px");
        var traffic=(JTable)SimpleWorkflowTest.unchecked(extension,"logTable");check(java.util.stream.IntStream.range(0,traffic.getColumnCount()).mapToObj(traffic::getColumnName).toList().equals(List.of("NO","메서드","URL","상태","계정","시간","메모")),"traffic uses approved visible columns without discarding hidden metadata");
        var browser=(TrafficBrowser)SimpleWorkflowTest.unchecked(extension,"trafficBrowser");String typed=browser.search.getText();browser.quickFilter("POST","오류","Bob");check(browser.query.method().equals("POST")&&browser.query.status().equals("오류")&&browser.query.account().equals("Bob")&&browser.search.getText().equals(typed),"quick filters preserve free text and apply existing query engine");
        var query=browser.query.prepare("");check(query.matchesFields("POST","500","Bob")&&query.matchesFields("POST","403","Bob")&&!query.matchesFields("POST","302","Bob"),"error segment covers both server and client HTTP errors");browser.quickFilter("전체","전체","");check(((JComboBox<?>)SimpleWorkflowTest.named(root,"wb.traffic.account")).getSelectedItem().equals("전체")&&((JToggleButton)SimpleWorkflowTest.named(root,"wb.traffic.quick.전체")).isSelected(),"quick filter controls follow restored query state");
        extension.openFeature("mapper");for(int i=0;i<5;i++)UiLayoutTest.layout(root);var mapperSplit=(JSplitPane)SimpleWorkflowTest.named(root,"fm.navigation.split");var mapperToggle=(JToggleButton)SimpleWorkflowTest.named(root,"fm.tree.toggle");int initial=mapperSplit.getDividerLocation();mapperToggle.doClick();UiLayoutTest.layout(root);check(mapperSplit.getDividerLocation()<80,"folding the mapper tree reclaims its width");mapperToggle.doClick();UiLayoutTest.layout(root);check(Math.abs(mapperSplit.getDividerLocation()-initial)<10,"expanding restores mapper tree width");
      });
      var js=(JsAnalysisPanel)get(extension,"jsAnalysis");edt(()->js.load("fetch('/first');fetch('/second');","https://compact.test/app.js"));JsAnalysisPanelTest.waitDone(js);
      edt(()->{js.urlTable.setRowSelectionInterval(1,1);check(js.resultView.detail.getText().contains("/second"),"JS inline detail follows the selected original model row");var search=(JTextField)SimpleWorkflowTest.named(js,"wb.js.findings.search");search.setText("first");check(js.urlTable.getRowCount()==1,"inline JS search filters the existing result model");js.resultView.apply();check(js.urlTable.getRowCount()==1,"search stays combined when existing result filters refresh");js.urlTable.setRowSelectionInterval(0,0);check(js.resultView.detail.getText().contains("/first")&&!js.resultView.detail.getText().contains("/second"),"filtered JS detail cannot show the previous source row");search.setText("[missing]");check(js.urlTable.getRowCount()==0&&js.resultView.detail.getText().isEmpty(),"literal JS search clears stale inline details");check(js.resultTabs.titles.get(0).getText().endsWith("2"),"visible custom result tab shows the actual source count");});
    } finally { SimpleWorkflowTest.unload(); }
    System.out.println("SimpleCompactDesignTest: " + checks + " checks passed");
  }
}
