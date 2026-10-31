package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

/** Removes proven duplicate controls while retaining the original working components and actions. */
public final class SimpleScreenCleanupTest extends WorkbenchTest {
  static List<AbstractButton> buttons(Component component,String text){
    var result=new ArrayList<AbstractButton>();
    if(component instanceof AbstractButton button&&text.equals(button.getText()))result.add(button);
    if(component instanceof Container container)for(Component child:container.getComponents())result.addAll(buttons(child,text));
    return result;
  }
  public static void main(String[] args)throws Exception {
    BountyFeaturesTest.factory();
    var entry=new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(()->entry.initialize(SimpleWorkflowTest.previewApi()));
    var extension=(WorkbenchExtension)get(entry,"workbench");
    var root=(JPanel)get(extension,"root");
    Object runner=get(entry,"payloadRunner");
    var mapper=(kr.featuremapper.FeatureMapperWorkspace)get(extension,"featureMapper");
    var mapperComponent=mapper.component();
    Object mapperModel=get(mapper,"mapper");
    var mapperTable=(JTable)get(mapper,"table");
    var originalModel=mapperTable.getModel();
    int sentBefore=PayloadIntegrationTest.sent.get();
    try {
      seedTraffic(extension,request("https://cleanup.example.test/documents/101","GET",true,List.of()),response(200,"CLEANUP_ORIGINAL"));
      Object selectedOriginal=get(extension,"current");
      check(selectedOriginal!=null,"fixture retains a selected original before cleanup navigation");
      edt(()->{
        var phases=(JTabbedPane)SimpleWorkflowTest.named(root,"wb.payload.steps");
        var preparation=(JToggleButton)SimpleWorkflowTest.named(root,"wb.payload.step.prepare");
        var results=(JToggleButton)SimpleWorkflowTest.named(root,"wb.payload.step.results");
        check(SimpleWorkflowTest.named(root,"wb.payload.prepare")==null,"duplicate top-right payload preparation control is removed");
        check(buttons(root,"요청 준비로 돌아가기").isEmpty(),"duplicate payload return label is absent");
        check(preparation!=null&&results!=null&&phases.getTabCount()==2,"both numbered payload phases remain reachable");
        JTextArea input;
        try {input=(JTextArea)get(runner,"request");}catch(Exception error){throw new IllegalStateException(error);}
        String edited="POST /keep HTTP/1.1\r\nHost: cleanup.example.test\r\n\r\nq=§PAYLOAD§&memo=UNSAVED_CLEANUP";
        input.setText(edited);results.doClick();
        check(phases.getSelectedIndex()==1&&results.isSelected(),"retained results step opens the same result workspace");
        preparation.doClick();
        check(phases.getSelectedIndex()==0&&preparation.isSelected()&&input.getText().equals(edited),"retained request step replaces the removed shortcut without losing authored bytes");
        check(SimpleWorkflowTest.named(root,"wb.simple.payload.start")!=null&&SimpleWorkflowTest.named(root,"wb.simple.payload.settings")!=null,"scan start and all inspection settings remain available");
        var evidence=(BountySupportPanel)SimpleWorkflowTest.unchecked(extension,"bountySupport");
        check(buttons(evidence,"B").isEmpty()&&buttons(evidence,"</>").isEmpty(),"auxiliary quick-format toolbars are retired from both evidence text areas");
        evidence.impact.setText("User-authored **Markdown** remains exact");
        check(evidence.impact.getText().equals("User-authored **Markdown** remains exact"),"plain report editing retains authored Markdown text without helper buttons");
        check(SimpleWorkflowTest.named(root,"wb.card.URL 점검 현황")==null,"repeated progress title card is removed while the URL table stays reachable");
        extension.openFeature("mapper");
        check(buttons(mapperComponent,"선택 패킷 분류 수정").isEmpty(),"duplicate lower mapper classification control is removed");
        var classify=buttons(mapperComponent,"분류 수정");
        check(classify.size()==1&&classify.get(0).getActionListeners().length>0,"one real mapper classification action remains in the main filter toolbar");
        mapperTable.clearSelection();int rows=mapperTable.getRowCount();
        classify.get(0).doClick();
        check(mapperTable.getRowCount()==rows&&mapperTable.getModel()==originalModel,"retained classification action safely handles no selection and keeps the original table model");
        var featureEdit=(AbstractButton)SimpleWorkflowTest.named(mapperComponent,"fm.feature.edit");
        check(featureEdit!=null&&featureEdit.getAccessibleContext().getAccessibleName().equals("기능 이름·상태·메모")&&featureEdit.getActionListeners().length>0,"compact feature edit retains naming and review metadata editing with its full accessible label");
      });
      check(get(extension,"featureMapper")==mapper&&get(mapper,"mapper")==mapperModel,"cleanup keeps the exact mapper workspace and model engines");
      check(get(entry,"payloadRunner")==runner&&get(extension,"current")==selectedOriginal,"cleanup preserves the exact payload engine and selected original identity");
      check(kr.featuremapper.BuildInfo.VERSION.equals("0.3.15"),"modified embedded mapper receives its own new component version");
      check(PayloadIntegrationTest.sent.get()==sentBefore,"guide-independent cleanup navigation and retained preparation actions send zero HTTP");
    }finally{SimpleWorkflowTest.unload();}
    System.out.println("SimpleScreenCleanupTest: "+checks+" checks passed (retained production controls/models, zero HTTP)");
  }
}
