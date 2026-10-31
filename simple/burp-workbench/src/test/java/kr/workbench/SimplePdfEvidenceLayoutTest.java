package kr.workbench;

import java.awt.Component;
import java.awt.Container;
import java.util.List;
import javax.swing.*;

/** The approved PDF's writing/source layout keeps the same report and original packets. */
public final class SimplePdfEvidenceLayoutTest extends WorkbenchTest {
  private static Component named(Component root,String name) {
    Component result=SimpleWorkflowTest.named(root,name);
    if(result==null)throw new AssertionError("Missing PDF evidence component: "+name);
    return result;
  }
  private static boolean exposed(Component part,Component root) {
    for(Component at=part;at!=null;at=at.getParent()) {
      if(!at.isVisible())return false;
      if(at==root)return true;
    }
    return false;
  }
  public static void main(String[] args)throws Exception {
    BountyFeaturesTest.factory();
    int sent=PayloadIntegrationTest.sent.get();
    edt(()->{
      var api=SimpleWorkflowTest.previewApi();
      var auth=new AuthComparePanel(api,List::of);
      var activity=new ActivityPanel(api);
      var panel=new BountySupportPanel(api,List::of,auth,activity);
      try {
        Component report=named(panel,"wb.bounty.evidence.report");
        check(SwingUtilities.isDescendingFrom(panel.impact,(Container)report)
            && SwingUtilities.isDescendingFrom(panel.repro,(Container)report),
            "both original editors belong to the single approved report-writing card");
        Component source=named(panel,"wb.bounty.evidence.source.card");
        check(!SwingUtilities.isDescendingFrom(panel.impact,(Container)source)
            && SwingUtilities.isDescendingFrom(panel.evidencePacketPreview,(Container)source),
            "the source comparison card contains the packet reader, separate from authored report text");
        var packetTabs=(JTabbedPane)named(panel,"wb.bounty.evidence.packet.tabs");
        check(packetTabs.getTabCount()==2&&packetTabs.getTitleAt(0).equals("응답")&&packetTabs.getTitleAt(1).equals("요청"),
            "source comparison retains response then request tabs from the PDF");
        check(!exposed(panel.evidenceSourceInfo,panel),
            "connected-original information starts folded without hiding the main packet reader");
        panel.impact.setText("KEEP exact authored 영향 <&>\nsecond line");
        panel.repro.setText("KEEP exact authored 재현 1.\r\n2.");
        String impact=panel.impact.getText(),repro=panel.repro.getText();
        EvidenceEditingTest.append(panel,"FIRST");EvidenceEditingTest.append(panel,"SECOND");
        var original=panel.evidence.get(1);String originalId=original.evidenceId();
        String request=original.request(),response=original.response();
        var filter=(JTextField)named(panel,"wb.bounty.evidence.search");
        filter.setText("SECOND");panel.evidenceTable.setRowSelectionInterval(0,0);
        check(panel.evidencePacketLabel.getText().equals("선택 근거: SECOND")
            &&panel.evidenceSourceInfo.getText().contains(originalId)
            &&panel.evidenceAccount.getText().contains("account-SECOND"),
            "filtered selection supplies the exact evidence's context, source ID and connected account");
        check(panel.evidencePacketPreview.getText().contains("SECOND")
            &&panel.evidenceRequestPreview.getText().contains("/SECOND"),
            "both packet tabs read the selected immutable original");
        packetTabs.setSelectedIndex(1);packetTabs.setSelectedIndex(0);
        check(panel.impact.getText().equals(impact)&&panel.repro.getText().equals(repro),
            "selection and packet-tab changes keep authored report prose byte-for-byte");
        check(original.evidenceId().equals(originalId)&&original.request().equals(request)&&original.response().equals(response),
            "layout and context summaries do not rewrite original IDs or stored request/response strings");
        filter.setText("missing evidence");
        check(panel.evidencePacketPreview.getText().isEmpty()&&panel.evidenceRequestPreview.getText().isEmpty()
            &&panel.evidenceSourceInfo.getText().isEmpty(),
            "filtering away a selected original clears every source reader rather than showing stale context");

        panel.pages.setSelectedIndex(1);
        var q=BountyFeaturesTest.rq("https://auth.test/replay","GET","",List.of(header("Host","auth.test")));
        var s=response(200,"EXACT_REPLAY_RESPONSE");
        panel.flowResults.add(new BountyWorkflow.Result(1,q,s,"관측 완료"));
        panel.flowModel.addRow(new Object[]{1,"GET",q.url(),200,"관측 완료"});
        panel.flowTable.setRowSelectionInterval(0,0);
        var replayTabs=(JTabbedPane)named(panel,"wb.bounty.flow.packet.tabs");
        check(replayTabs.getTitleAt(0).equals("응답")&&replayTabs.getTitleAt(1).equals("요청")
            &&panel.workflowDetail.getText().equals(s.toString())&&panel.workflowRequestDetail.getText().equals(q.toString()),
            "replay's approved response/request tabs show their corresponding exact result packets");
        check(panel.flowModel.getColumnCount()==5&&panel.flowTable.getColumnCount()==4,
            "the visible replay request column combines the retained method/URL model without discarding metadata");
        Container conditions=panel.flowChecks.getParent();
        panel.flowSettingsContent();
        check(panel.flowChecks.getParent()==conditions,
            "opening execution settings cannot steal the retained expected-condition editor from its disclosure");
        check(panel.impact.getText().equals(impact)&&panel.repro.getText().equals(repro),
            "changing evidence/replay pages preserves the same report draft");
      } finally {panel.close();activity.close();auth.close();WorkbenchScope.release(api);}
    });
    check(PayloadIntegrationTest.sent.get()==sent,"layout, source selection and settings construction send zero target HTTP");
    System.out.println("SimplePdfEvidenceLayoutTest: "+checks+" checks passed");
  }
}
