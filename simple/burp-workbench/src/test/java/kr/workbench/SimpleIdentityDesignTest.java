package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

/** The approved compact roles still operate on the original account choices and assignments. */
public final class SimpleIdentityDesignTest extends WorkbenchTest {
  public static void main(String[] args)throws Exception{
    BountyFeaturesTest.factory();var entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));edt(()->entry.initialize(SimpleWorkflowTest.previewApi()));
    var extension=(WorkbenchExtension)get(entry,"workbench");var root=(JPanel)get(extension,"root");var auth=(AuthComparePanel)get(extension,"authCompare");
    try{
      var alice=request("https://identity.example.test/documents/101","GET",true,List.of(header("Cookie","SID=IDENTITY_ALICE")));
      var bob=request("https://identity.example.test/documents/101","GET",true,List.of(header("Cookie","SID=IDENTITY_BOB")));
      seedTraffic(extension,alice,response(200,"identity original"));edt(()->{auth.setDetection(true);auth.observe(alice,response(200,"identity original"));auth.observe(bob,response(403,"identity original"));
      var candidates=new ArrayList<>(auth.credentials.values());auth.assign(0,candidates.get(0));auth.assign(1,candidates.get(1));auth.anonymous(3);
      var original0=auth.slots[0];var original1=auth.slots[1];JPanel card=auth.accountConnectionsContent();card.setSize(900,447);for(int pass=0;pass<6;pass++)UiLayoutTest.layout(card);
      var choice=(JComboBox<?>)ApprovedUi.find(card,"wb.auth.preparation.account.3");var model=choice.getModel();
      var use=(JToggleButton)ApprovedUi.find(card,"wb.auth.connections.anonymous.use");var count=(JLabel)ApprovedUi.find(card,"wb.auth.connections.role.count");
      check(use!=null&&use.isVisible()&&!choice.isVisible()&&use.isSelected(),"compact anonymous control replaces only the visible chooser presentation");
      check(count.getText().equals("비교 역할 3개"),"role badge counts the real assigned account choices");
      use.doClick(0);check(auth.slots[3]==null&&choice.getSelectedIndex()==0&&!use.isSelected(),"visible anonymous control delegates removal to the original assignment model");
      check(count.getText().equals("비교 역할 2개"),"removing anonymous role updates the actual role count");
      use.doClick(0);check(auth.slots[3]!=null&&choice.getSelectedIndex()>0&&use.isSelected(),"visible anonymous control delegates anonymous assignment to the retained chooser");
      choice.setSelectedIndex(0);check(!use.isSelected(),"original chooser updates the visible compact control without a second action");
      check(choice.getModel()==model&&auth.slots[0]==original0&&auth.slots[1]==original1,"compact role changes preserve chooser model and both authenticated original assignments");
      var extra=(JToggleButton)ApprovedUi.find(card,"wb.auth.preparation.extra.toggle");extra.doClick(0);card.setSize(900,517);for(int pass=0;pass<6;pass++)UiLayoutTest.layout(card);
      check(ApprovedUi.find(card,"wb.auth.preparation.account.2").isVisible(),"the original optional role remains available through the approved disclosure");extra.doClick(0);
      var detector=(AbstractButton)ApprovedUi.find(root,"wb.auth.detect");check(detector.getPreferredSize().width==96&&detector.getPreferredSize().height==22,"authentication detector uses the approved compact indicator without changing its action");
      var environment=(EnvironmentPanel)SimpleWorkflowTest.unchecked(extension,"environments");
      check(ApprovedUi.find(environment,"wb.environment.observations.toggle") instanceof AbstractButton toggle&&Boolean.TRUE.equals(toggle.getClientProperty("wb.approved.disclosure")),"observed environment control retains its original toggle model in the approved disclosure");
      check(PayloadIntegrationTest.sent.get()==0,"presentation and explicit account linking send no target HTTP");
    });}finally{SimpleWorkflowTest.unload();}
    System.out.println("SimpleIdentityDesignTest: "+checks+" checks passed (retained account models, no HTTP)");
  }
}
