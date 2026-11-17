package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import kr.payloadrunner.Engine;

/** The approved compact preparation controls still drive the original editor and engine models. */
public final class SimpleApprovedPayloadDesignTest extends WorkbenchTest {
  private static Component named(Component root,String name){Component value=ApprovedUi.find(root,name);if(value==null)throw new AssertionError("Missing retained payload control: "+name);return value;}
  private static void layout(JPanel root){for(int pass=0;pass<8;pass++)UiLayoutTest.layout(root);}
  public static void main(String[] args)throws Exception {
    BountyFeaturesTest.factory();BurpExtender entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(()->entry.initialize(SimpleWorkflowTest.previewApi()));edt(()->{});
    WorkbenchExtension extension=(WorkbenchExtension)get(entry,"workbench");JPanel root=(JPanel)get(extension,"root");Object runner=get(entry,"payloadRunner");int sent=PayloadIntegrationTest.sent.get();
    try {
      edt(()->{
        extension.openFeature("payload");root.setSize(1600,1000);layout(root);
        JTextArea request=(JTextArea)named(root,"wb.simple.payload.request");JComboBox<?> profile=(JComboBox<?>)named(root,"wb.payload.condition.profile"),encoding=(JComboBox<?>)named(root,"wb.payload.condition.encoding");
        JSpinner budget=(JSpinner)named(root,"wb.payload.condition.budget"),interval=(JSpinner)named(root,"wb.payload.condition.interval");
        try {
          check(request==get(runner,"request")&&profile==get(runner,"profile")&&encoding==get(runner,"encoding")&&budget==get(runner,"budget")&&interval==get(runner,"interval"),"approved preparation retains the exact payload engine controls");
        }catch(Exception error){throw new IllegalStateException(error);}
        Object budgetModel=budget.getModel(),intervalModel=interval.getModel(),profileModel=profile.getModel(),encodingModel=encoding.getModel();
        check(profile.getWidth()==112&&profile.getHeight()==31&&encoding.getWidth()==96&&encoding.getHeight()==31,"approved compact selection controls have independent widths and one 31px baseline");
        check(budget.getWidth()==180&&budget.getHeight()==31&&interval.getWidth()==180&&interval.getHeight()==31,"numeric controls match the approved 180 by 31 fields without changing the numeric model");
        budget.setValue(173);interval.setValue(750);profile.setSelectedItem(Engine.Profile.SQLI);encoding.setSelectedItem(Engine.Encoding.JSON);
        check(budget.getModel()==budgetModel&&interval.getModel()==intervalModel&&profile.getModel()==profileModel&&encoding.getModel()==encodingModel,"editing approved conditions keeps every original value model");
        check(budget.getValue().equals(173)&&interval.getValue().equals(750)&&profile.getSelectedItem()==Engine.Profile.SQLI&&encoding.getSelectedItem()==Engine.Encoding.JSON,"actual numeric and enum values remain exact rather than being replaced by design examples");
        root.setSize(1280,800);layout(root);
        check(budget.getWidth()==140&&interval.getWidth()==140&&budget.getValue().equals(173)&&interval.getValue().equals(750),"the approved narrow-window fields compact to 140px without changing edited values");
        root.setSize(1600,1000);layout(root);
        String before="GET /search?email=old_value HTTP/1.1\r\nHost: example.test\r\nCookie: SID=PAYLOAD_TEST_ONLY\r\n\r\n";
        request.setText(before);int start=before.indexOf("old_value");request.select(start,start+"old_value".length());
        var document=request.getDocument();((AbstractButton)named(root,"wb.payload.mark")).doClick(0);
        String marked=before.substring(0,start)+Engine.MARK+before.substring(start+"old_value".length());
        check(request.getDocument()==document&&request.getText().equals(marked),"the actual retained mark action changes exactly the selected source span through the same document");
        check(((JLabel)named(root,"wb.approved.payload.position.key")).getText().equals("Query · email"),"the position chip is derived from the authored query key rather than a sample name");
        check(((JLabel)named(root,"wb.approved.payload.limit.summary")).getText().contains("173"),"preparation metadata follows the real request budget");
        JToggleButton candidates=(JToggleButton)named(root,"wb.payload.candidates.toggle"),advanced=(JToggleButton)named(root,"wb.payload.advanced.toggle");
        candidates.doClick(0);advanced.doClick(0);layout(root);
        check(candidates.isSelected()&&advanced.isSelected()&&((Container)advanced.getParent()).getComponent(1).isVisible(),"the compact disclosure headers still reveal the original editable candidates and advanced settings");
        candidates.doClick(0);advanced.doClick(0);layout(root);
        check(candidates.getHeight()==36&&advanced.getHeight()==36,"collapsed preparation disclosures retain the approved 36px header height");
        int caret=request.getCaretPosition();String original=request.getText();
        AbstractButton theme=(AbstractButton)named(root,"wb.traffic.header.tools");
        for(int round=0;round<2;round++){
          theme.doClick(0);layout(root);
          check(request.getDocument()==document&&request.getText().equals(original)&&request.getCaretPosition()==caret,"theme changes preserve the exact marked document and caret: "+round);
          check(budget.getModel()==budgetModel&&budget.getValue().equals(173)&&interval.getModel()==intervalModel&&interval.getValue().equals(750),"theme changes preserve edited request budget and pacing models: "+round);
          JLabel notice=(JLabel)named(root,"wb.payload.results.selection");String text="선택 결과 · 반사 관찰 · 브라우저 실행 미확인";notice.setText(text);notice.setSize(700,37);
          BufferedImage picture=new BufferedImage(700,37,BufferedImage.TYPE_INT_RGB);Graphics2D graphics=picture.createGraphics();notice.paint(graphics);graphics.dispose();
          check(picture.getRGB(16,5)==WorkbenchUi.raised().getRGB()&&notice.getText().equals(text),"the selected-result notice uses the approved neutral surface while retaining its real status text: "+round);
        }
        check(PayloadIntegrationTest.sent.get()==sent,"condition edits, marking, disclosures, layout and theme changes never send a target request");
      });
    } finally {SimpleWorkflowTest.unload();}
    System.out.println("SimpleApprovedPayloadDesignTest: "+checks+" checks passed (retained production controls/documents; zero target HTTP)");
  }
}
