package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.awt.*;
import java.util.List;
import javax.swing.*;

/** The approved management views keep authored documents and exact retained controllers. */
public final class SimpleManagementDesignTest extends WorkbenchTest {
  private static Component named(Component root,String name){Component c=SimpleWorkflowTest.named(root,name);if(c==null)throw new AssertionError("Missing management view: "+name);return c;}
  public static void main(String[] args)throws Exception {
    BountyFeaturesTest.factory();BurpExtender entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(()->entry.initialize(SimpleWorkflowTest.previewApi()));edt(()->{});
    WorkbenchExtension extension=(WorkbenchExtension)get(entry,"workbench");JPanel root=(JPanel)get(extension,"root");ActivityPanel activity=(ActivityPanel)get(extension,"activity");BountySupportPanel bounty=(BountySupportPanel)get(extension,"bountySupport");
    int sent=PayloadIntegrationTest.sent.get();
    try {
      edt(()->{
        root.setSize(1600,1000);
        activity.tracker.observe(request("https://design.example.test/api/documents/101?locale=ko","GET",true,List.of()),"DESIGN_TEST");activity.refresh();activity.table.setRowSelectionInterval(0,0);activity.showParameters();
        var selected=activity.selected();
        check(selected!=null&&activity.selectedUrl.getText().contains("/api/documents/101"),"the approved identity presentation retains the exact selected URL controller text");
        check(((JLabel)named(root,"wb.approved.activity.state")).getText().equals(selected.state()),"the structured inspector displays the actual selected model state");
        check(((JLabel)named(root,"wb.approved.activity.observations")).getText().contains(Long.toString(selected.observed())),"the inspector shows the real observed count instead of sample data");
        JCheckBox enabled=activity.enabled;enabled.doClick(0);check(!enabled.isSelected()&&!activity.recording,"the compact tracking chip still invokes the retained recording controller");enabled.doClick(0);check(enabled.isSelected()&&activity.recording,"the same tracking chip restores recording through the original handler");
        JCheckBox scope=activity.scope;scope.doClick(0);check(!scope.isSelected()&&!activity.scopeOnly,"the compact SCOPE chip retains its original scope model and callback");scope.doClick(0);
        JPanel selection=(JPanel)named(root,"wb.actions.activity.selection");JButton open=(JButton)named(selection,"wb.actions.activity.selection.open");
        check(SimpleWorkflowTest.named(open.getComponentPopupMenu(),"wb.activity.draft.menu") instanceof JMenuItem,"the selection menu retains its exact Repeater preparation action");
        check(activity.evidence.getParent()!=null&&SwingUtilities.isDescendingFrom(activity.evidence,root),"the visible evidence control remains the real discovery-evidence controller");
        var impactDocument=bounty.impact.getDocument();var reproDocument=bounty.repro.getDocument();var flowDocument=bounty.workflow.getDocument();var evidenceModel=bounty.evidenceTable.getModel();var resultModel=bounty.flowTable.getModel();
        String impact="권한 영향 **작성 중**\n둘째 줄",repro="1. 원본 선택\n2. 응답 대조",flow="GET /api/documents/101 HTTP/1.1\r\nHost: design.example.test\r\n\r\n";
        bounty.impact.setText(impact);bounty.repro.setText(repro);bounty.workflow.setText(flow);bounty.impact.setCaretPosition(3);
        check(bounty.evidencePreview.getIcon()!=null&&bounty.evidenceSave.getIcon()!=null,"preview and archive controls visibly retain distinct action icons");
        bounty.flowBudget.setValue(9);check(((JLabel)named(root,"wb.approved.replay.budget")).getText().equals("요청 한도 9"),"the replay budget chip follows the real spinner model without sending a request");
        boolean original=WorkbenchUi.dark();
        for(int i=0;i<2;i++){
          ((JButton)named(root,"wb.traffic.header.tools")).doClick(0);
          bounty.pages.setSelectedIndex(1);bounty.pages.setSelectedIndex(3);
          check(bounty.impact.getDocument()==impactDocument&&bounty.repro.getDocument()==reproDocument&&bounty.workflow.getDocument()==flowDocument,"theme and management navigation retain the same authored documents");
          check(bounty.impact.getText().equals(impact)&&bounty.repro.getText().equals(repro)&&bounty.workflow.getText().equals(flow),"theme and management navigation preserve authored Markdown and CRLF request bytes");
          check(bounty.impact.getCaretPosition()==3&&bounty.evidenceTable.getModel()==evidenceModel&&bounty.flowTable.getModel()==resultModel,"management styling preserves caret and original evidence/replay table models");
          check(activity.selected()!=null&&activity.selected().key().equals(selected.key()),"theme changes preserve the selected immutable activity endpoint");
        }
        check(WorkbenchUi.dark()==original,"management theme roundtrip restores the original palette");
        bounty.pages.setSelectedIndex(1);bounty.state.setText("순차 실행 준비 · 취소와 SCOPE 확인");
        check(bounty.state.isVisible()&&SwingUtilities.isDescendingFrom(bounty.state,root),"runtime progress and cancellation feedback remain in the actual replay view");
        check(SwingUtilities.isDescendingFrom(bounty.state,named(root,"wb.approved.replay.footer")),"runtime feedback belongs below the replay reader after the original page listener has run");
        check(bounty.flowRun.getIcon()!=null,"the original replay run controller exposes its approved play affordance");
        extension.openFeature("bounty.replay");
        for(Dimension size:new Dimension[]{new Dimension(1600,1000),new Dimension(1280,800),new Dimension(1024,768)}){
          root.setSize(size);for(int pass=0;pass<8;pass++)UiLayoutTest.layout(root);
          Component evidence=named(root,"wb.bounty.flow.evidence");Container actions=evidence.getParent();
          check(evidence.isShowing()||evidence.isVisible()&&evidence.getWidth()>0,
              "the retained replay evidence action is laid out at "+size.width+"x"+size.height);
          check(evidence.getX()>=0&&evidence.getY()>=0&&evidence.getX()+evidence.getWidth()<=actions.getWidth()&&evidence.getY()+evidence.getHeight()<=actions.getHeight(),
              "the replay evidence action fits fully inside its work row at "+size.width+"x"+size.height);
        }
        check(PayloadIntegrationTest.sent.get()==sent,"management layout, chips, theme and authored editing send zero target HTTP");
      });
    } finally {SimpleWorkflowTest.unload();}
    System.out.println("SimpleManagementDesignTest: "+checks+" checks passed (actual management controllers/documents, zero HTTP)");
  }
}
