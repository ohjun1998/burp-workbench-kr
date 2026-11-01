package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.swing.*;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;

/** Actual composed readers, retained menu actions and wrapped codec caret/paint behavior. */
public final class SimpleApprovedReadingDesignTest extends WorkbenchTest {
  private static Component named(Component root,String name){Component found=SimpleWorkflowTest.named(root,name);if(found==null)throw new AssertionError(name);return found;}
  private static void layout(JPanel root){root.setSize(1600,1000);for(int i=0;i<8;i++)UiLayoutTest.layout(root);}
  private static BufferedImage paint(JComponent component){BufferedImage image=new BufferedImage(component.getWidth(),component.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();component.paint(g);g.dispose();return image;}
  private static JMenuItem entry(JButton button,String name){JMenuItem item=ApprovedWorkflowTestSupport.item(button.getComponentPopupMenu(),name);if(item==null)throw new AssertionError(name);return item;}
  private static int inkHeight(BufferedImage image,Color background,int lineHeight){int first=Integer.MAX_VALUE,last=-1;for(int y=0;y<Math.min(lineHeight,image.getHeight());y++)for(int x=0;x<Math.min(120,image.getWidth());x++){Color value=new Color(image.getRGB(x,y));int difference=Math.abs(value.getRed()-background.getRed())+Math.abs(value.getGreen()-background.getGreen())+Math.abs(value.getBlue()-background.getBlue());if(difference>120){first=Math.min(first,y);last=Math.max(last,y);}}return last<first?0:last-first+1;}
  private static void spacing() throws Exception {
    edt(()->{
      try {
        for(boolean wrapped:new boolean[]{false,true})for(int width:new int[]{240,680})for(boolean dark:new boolean[]{false,true}){
          kr.packetui.VisualTheme.setDarkOverride(dark);
          String original="Ag first\nAg second\n\nTAB\tposition\n"+"ABCD0123".repeat(18)+" 한글 끝\nlast";
          JTextArea baseline=new JTextArea(original),area=new JTextArea(original);
          for(JTextArea field:new JTextArea[]{baseline,area}){field.setFont(new Font(Font.MONOSPACED,Font.PLAIN,14));field.setLineWrap(wrapped);field.setWrapStyleWord(true);field.setBackground(WorkbenchUi.surface());field.setForeground(WorkbenchUi.ink());field.setSize(width,700);}
          BufferedImage normal=paint(baseline);
          Document document=area.getDocument();UndoManager undo=new UndoManager();document.addUndoableEditListener(undo);area.select(3,11);Caret caret=area.getCaret();
          ApprovedCodeAreaUI.install(area);
          check(area.getDocument()==document&&area.getCaret()==caret&&area.getSelectionStart()==3&&area.getSelectionEnd()==11&&area.getText().equals(original),"codec leading retains the real document, caret and selected text: "+wrapped+"/"+width+"/"+dark);
          area.select(0,0);BufferedImage spaced=paint(area);
          int second=original.indexOf('\n')+1;Rectangle2D a=area.modelToView2D(0),b=area.modelToView2D(second);
          check(Math.abs(b.getY()-a.getY()-23)<.1&&Math.abs(a.getHeight()-23)<.1,"codec explicit-line baselines and hit-test cells use approved23px leading");
          boolean offsets=true;
          for(int position=0;position<original.length();position++){
            Rectangle2D expected=baseline.modelToView2D(position),actual=area.modelToView2D(position);
            int nativePosition=baseline.viewToModel2D(new Point2D.Double(expected.getX()+.1,expected.getCenterY()));
            int actualPosition=area.viewToModel2D(new Point2D.Double(actual.getX()+.1,actual.getCenterY()));
            offsets&=Math.abs(expected.getX()-actual.getX())<1&&nativePosition==actualPosition;
          }
          check(offsets,"codec wrapped long Base64, Korean, tabs and blank lines keep native horizontal offsets and caret hit testing");
          for(int pass=0;pass<2500;pass++)area.modelToView2D(pass%original.length());
          check(Math.abs(area.modelToView2D(second).getY()-area.modelToView2D(0).getY()-23)<.1&&area.getDocument()==document,"repeated codec caret mapping keeps bounded view metrics and the same line geometry");
          int nativeHeight=inkHeight(normal,baseline.getBackground(),baseline.getFontMetrics(baseline.getFont()).getHeight());
          int paintedHeight=inkHeight(spaced,area.getBackground(),23);
          check(Math.abs(nativeHeight-paintedHeight)<=1&&paintedHeight>5,"codec increases leading without vertically stretching the14px glyphs");
          if(wrapped&&width==240)check(area.getUI().getRootView(area).getView(0).getPreferredSpan(View.Y_AXIS)>8*23,"wrapped Base64 contributes its real extra visual rows to scroll height");
          area.select(3,11);SwingUtilities.updateComponentTreeUI(area);
          check(area.getUI() instanceof ApprovedCodeAreaUI&&area.getDocument()==document&&area.getCaret()==caret&&area.getSelectionStart()==3&&area.getSelectionEnd()==11,"host delegate replacement restores codec leading without dropping the original selection or document");
          document.insertString(document.getLength()," tail",null);undo.undo();
          check(area.getText().equals(original),"existing codec undo history remains usable after leading and host-UI changes");
        }
        for(boolean wrapped:new boolean[]{false,true})for(boolean transition:new boolean[]{false,true}){
          String original="ABC עברית عربي DEF";
          JTextArea baseline=new JTextArea(original),area=new JTextArea(transition?"ABC ":original);
          for(JTextArea field:new JTextArea[]{baseline,area}){field.setFont(new Font(Font.DIALOG,Font.PLAIN,14));field.setLineWrap(wrapped);field.setWrapStyleWord(true);field.setSize(400,180);}
          Document document=area.getDocument();area.select(1,3);Caret caret=area.getCaret();ApprovedCodeAreaUI.install(area);
          if(transition)document.insertString(document.getLength(),"עברית عربي DEF",null);
          paint(baseline);paint(area);
          check(Boolean.TRUE.equals(document.getProperty("i18n"))&&area.getDocument()==document&&area.getCaret()==caret&&area.getSelectionStart()==1&&area.getSelectionEnd()==3&&area.getText().equals(original),"codec preserves actual complex-script document, selection and original text when bidi mode is "+(transition?"entered by editing":"already present"));
          boolean nativePositions=true;for(int position=0;position<original.length();position++){
            Rectangle2D expected=baseline.modelToView2D(position),actual=area.modelToView2D(position);
            nativePositions&=Math.abs(expected.getX()-actual.getX())<1&&Math.abs(expected.getY()-actual.getY())<1;
          }
          check(nativePositions&&area.modelToView2D(4).getX()>area.modelToView2D(5).getX(),"codec keeps Swing's native Hebrew/Arabic shaping and right-to-left caret movement");
        }
      }catch(Exception exception){throw new IllegalStateException(exception);}
      finally{kr.packetui.VisualTheme.setDarkOverride(null);}
    });
  }
  public static void main(String[] args)throws Exception {
    spacing();BountyFeaturesTest.factory();BurpExtender entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));edt(()->entry.initialize(SimpleWorkflowTest.previewApi()));
    WorkbenchExtension extension=(WorkbenchExtension)get(entry,"workbench");JPanel root=(JPanel)get(extension,"root");int sent=PayloadIntegrationTest.sent.get();
    JTextArea codec=(JTextArea)get(extension,"codecIn");Document codecDocument=codec.getDocument();
    try {
      edt(()->{
        codec.setText("KEEP_CODEC_INPUT\n한글\t문서\n"+"ABCD".repeat(60));codec.select(3,11);String draft=codec.getText();
        ResponseReconPanel response=(ResponseReconPanel)named(root,"wb.response.recon");
        response.displayedFindingKeys=java.util.List.of("READING_ORIGINAL");response.displayedFindingPackets=java.util.Map.of("READING_ORIGINAL",AuthRecordTest.rr(request("https://reading.example.test/api/test","PATCH",true,java.util.List.of()),response(200,"{\"id\":101}")));
        response.findings.addRow(new Object[]{"오류 구현 정보 노출 후보","https://reading.example.test/api/test","Body: 42","java.lang.Exception",1});
        response.findingTable.setRowSelectionInterval(0,0);
        for(boolean dark:new boolean[]{false,true}){
          kr.packetui.VisualTheme.setDarkOverride(dark);WorkbenchUi.style(root);layout(root);
          FolderNavigation navigation=(FolderNavigation)named(root,"wb.navigation");navigation.navigate("traffic");layout(root);
          JTextField traffic=(JTextField)named(root,"wb.traffic.search");
          check(new Color(paint(traffic).getRGB(traffic.getWidth()/2,6)).equals(ApprovedReadingDesign.searchFill()),"real traffic search paints the approved subtle input fill in "+dark);
          JTable history=(JTable)named(root,"wb.traffic.history");int[] approvedWidths={98,124,442,132,114,160,308};boolean correctWidths=true;for(int column=0;column<approvedWidths.length;column++)correctWidths&=history.getColumnModel().getColumn(column).getWidth()==approvedWidths[column];
          check(correctWidths,"fresh1600px composed traffic uses approved columns rather than persisted legacy construction widths");
          navigation.navigate("js");layout(root);JTextField js=(JTextField)named(root,"wb.js.findings.search");if(js.getWidth()==0)js.setSize(695,34); // Empty result modes may defer this retained field's layout.
          check(new Color(paint(js).getRGB(js.getWidth()/2,6)).equals(ApprovedReadingDesign.searchFill()),"real JS search shares the approved input fill in "+dark);
          navigation.navigate("response.recon");layout(root);
          JToggleButton observations=(JToggleButton)named(root,"wb.response.page.0"),api=(JToggleButton)named(root,"wb.response.page.1");
          check(observations.getText().equals("응답 관찰 1")&&observations.isSelected(),"response mode caption derives the actual observation count");
          check(new Color(paint(observations).getRGB(4,4)).equals(WorkbenchUi.selected())&&new Color(paint(api).getRGB(4,4)).equals(WorkbenchUi.background()),"response modes paint borderless selected and unselected pills in "+dark);
          api.doClick(0);check(api.isSelected(),"the retained response mode button still invokes its original page controller");observations.doClick(0);layout(root);
          check(!response.findingTable.getFillsViewportHeight()&&response.findingTable.getColumnModel().getColumn(0).getHeaderValue().equals("관찰"),"response list retains actual rows with approved captions and an unframed empty region");
          check(((JLabel)named(root,"wb.approved.response.request.caption")).getText().equals("PATCH /api/test")&&((JLabel)named(root,"wb.approved.response.location.caption")).getText().equals("Body: 42"),"approved response metadata derives the actual selected packet method, path and finding location");
          check(((JLabel)named(root,"wb.approved.response.candidate")).isVisible()&&response.findingContext.getText().contains("https://reading.example.test/api/test"),"candidate chip retains the full original context label and source identity");
          byte[] wire="HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"id\":101}".getBytes(StandardCharsets.ISO_8859_1);response.findingResponse.setPacket(wire,null);
          JButton tools=(JButton)named(root,"wb.approved.response.reading.response.open");check(ApprovedWorkflowTestSupport.visible(tools,root),"response reader offers its visible approved original-view menu");
          entry(tools,"wb.response.finding.response.raw.menu").doClick(0);
          check(response.findingResponse.mode()==PacketInspector.Mode.RAW&&Arrays.equals(wire,response.findingResponse.previewBytes()),"approved original-view menu invokes the retained Raw controller and original bytes");
          entry(tools,"wb.response.finding.response.pretty.menu").doClick(0);entry(tools,"wb.response.finding.response.search.open.menu").doClick(0);
          check(response.findingResponse.mode()==PacketInspector.Mode.PRETTY&&response.findingResponse.search.isVisible(),"the approved reader menu preserves Pretty and packet-search actions");
          navigation.navigate("data");layout(root);WorkbenchUi.style(root);layout(root);
          check(codec.getUI() instanceof ApprovedCodeAreaUI&&codec.getFont().getSize()==14&&codec.getDocument()==codecDocument&&codec.getText().equals(draft)&&codec.getSelectionStart()==3&&codec.getSelectionEnd()==11,"composed codec keeps14px glyphs,23px leading and the authored document/selection through both themes");
          check(((JButton)named(root,"wb.data.codec.convert")).getIcon().getIconWidth()==15,"the retained codec conversion action includes the approved15px arrow");
        }
        check(PayloadIntegrationTest.sent.get()==sent,"reading-page styling, routing and packet mode menus send zero target HTTP");
      });
    }finally{kr.packetui.VisualTheme.setDarkOverride(null);SimpleWorkflowTest.unload();}
    System.out.println("SimpleApprovedReadingDesignTest: "+checks+" checks passed (actual controllers, codec documents and paint/hit tests)");
  }
}
