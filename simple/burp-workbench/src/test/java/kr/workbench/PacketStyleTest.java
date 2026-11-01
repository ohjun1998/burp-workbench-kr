package kr.workbench;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.geom.Point2D;
import javax.swing.*;
import javax.swing.text.*;
import kr.packetui.PacketStyle;
/** Painting must preserve source positions, editing and existing finding highlights. */
public final class PacketStyleTest extends WorkbenchTest {
 static void paint(Component component){BufferedImage image=new BufferedImage(component.getWidth(),component.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();component.paint(g);g.dispose();}
 static void verify(JTextArea area){
  check(PacketStyle.installed(area),"packet/source area has shared syntax UI: "+area.getName());
  var scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,area);
  check(scroll!=null&&scroll.getRowHeader()!=null&&scroll.getRowHeader().getView()!=null,"packet/source area has logical line numbers");
 }
 static void verifyApprovedLineAdvance(){
  // Approved prototype style.css: .code has 12px font-size and line-height:1.78.
  String raw="GET /space HTTP/1.1\nHost: example.test\n\nbody";
  for(int size:new int[]{12,15,18}){
   JTextArea area=new JTextArea(raw);area.putClientProperty("wb.packet.syntax",true);area.putClientProperty("wb.approved.packet",true);area.setFont(new Font(Font.MONOSPACED,Font.PLAIN,size));
   JScrollPane scroll=new JScrollPane(area);PacketStyle.install(area);scroll.setSize(540,170);for(int pass=0;pass<4;pass++)UiLayoutTest.layout(scroll);
   Document document=area.getDocument();int host=raw.indexOf("Host:");area.setCaretPosition(host);
   try{
    area.getHighlighter().addHighlight(host,host+4,new DefaultHighlighter.DefaultHighlightPainter(Color.YELLOW));
    var first=area.modelToView2D(0);var second=area.modelToView2D(host);int expected=Math.max(area.getFontMetrics(area.getFont()).getHeight(),Math.round(size*1.78f));
    check(first!=null&&second!=null&&Math.round(second.getY()-first.getY())==expected,"approved code line advance follows the reference 1.78 ratio at "+size+"px");
    int hit=area.viewToModel2D(new Point2D.Double(second.getX()+1,second.getY()+second.getHeight()/2));
    check(hit==host,"line hit-testing still resolves the exact original source offset at "+size+"px");
    paint(scroll);
    check(area.getDocument()==document&&area.getText().equals(raw)&&area.getCaretPosition()==host&&!area.getLineWrap(),"approved line spacing preserves source, document, caret and nonwrapped mode at "+size+"px");
    var highlights=area.getHighlighter().getHighlights();check(highlights.length==1&&highlights[0].getStartOffset()==host&&highlights[0].getEndOffset()==host+4,"approved line spacing preserves retained finding spans at "+size+"px");
    Component gutter=scroll.getRowHeader().getView();area.putClientProperty("wb.approved.firstline.gutter",true);BufferedImage marked=new BufferedImage(gutter.getWidth(),gutter.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D mg=marked.createGraphics();gutter.paint(mg);mg.dispose();int rowY=(int)first.getY()+2;
    check(marked.getRGB(3,rowY)==kr.packetui.VisualTheme.selected().getRGB()&&marked.getRGB(0,rowY)==kr.packetui.VisualTheme.accent().getRGB(),"the optional preparation highlight includes its logical-line gutter and outer accent at "+size+"px");
    area.putClientProperty("wb.approved.firstline.gutter",false);BufferedImage normal=new BufferedImage(gutter.getWidth(),gutter.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D ng=normal.createGraphics();gutter.paint(ng);ng.dispose();check(normal.getRGB(3,rowY)==area.getBackground().getRGB(),"ordinary reader gutters keep their normal background when the preparation-only flag is absent at "+size+"px");
   }catch(BadLocationException error){throw new IllegalStateException(error);}
  }
 }
 public static void main(String[] args)throws Exception{
  edt(()->{
   verifyApprovedLineAdvance();
   String raw="const api = '/users';\nfetch(api, {method: 'POST'});\n{\"count\":2,\"enabled\":true}\n<node id=\"x\">text</node>\n";
   JTextArea area=new JTextArea(raw);area.setName("style.fixture");area.putClientProperty("wb.packet.syntax",true);JScrollPane scroll=new JScrollPane(area);WorkbenchUi.style(scroll);
   Document document=area.getDocument();try{area.getHighlighter().addHighlight(6,9,new DefaultHighlighter.DefaultHighlightPainter(Color.YELLOW));}catch(BadLocationException e){throw new RuntimeException(e);}
   var highlight=area.getHighlighter().getHighlights()[0];scroll.setSize(540,160);for(int i=0;i<4;i++)UiLayoutTest.layout(scroll);verify(area);
   paint(scroll);check(area.getText().equals(raw)&&area.getDocument()==document,"syntax painting preserves exact source and document identity");
   check(highlight.getStartOffset()==6&&highlight.getEndOffset()==9&&area.getHighlighter().getHighlights().length==1,"finding highlights and source offsets survive painting");
   BufferedImage image=new BufferedImage(area.getWidth(),area.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();area.paint(g);g.dispose();boolean blue=false,green=false;
   for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){Color c=new Color(image.getRGB(x,y));blue|=c.equals(new Color(31,91,235))||c.equals(new Color(161,188,239));green|=c.equals(new Color(28,141,48))||c.equals(new Color(155,209,113));}
   check(blue&&green,"renderer paints distinct Burp-style syntax colors rather than plain monochrome text");
   area.setLineWrap(true);area.setWrapStyleWord(true);area.append("// "+"wrapped ".repeat(80)+"\nlast");for(int i=0;i<4;i++)UiLayoutTest.layout(scroll);paint(scroll);check(area.getLineCount()==6,"wrapped visual lines do not change logical source line count");
   area.setText("const x='"+"a".repeat(90000)+"';\nnext");for(int i=0;i<3;i++)UiLayoutTest.layout(scroll);paint(scroll);check(area.getText().length()>90000,"long source lines remain intact beyond syntax scan budget");
   SwingUtilities.updateComponentTreeUI(scroll);WorkbenchUi.style(scroll);verify(area);check(area.getDocument()==document,"host theme restoration keeps the original document");
   PacketView view=new PacketView();verify(view.text);view.setPacket("GET / HTTP/1.1\r\nHost: example.test\r\n\r\n");view.formatted.doClick(0);verify(view.text);
   StructuredComparePanel compare=new StructuredComparePanel(null);for(JTextArea text:new JTextArea[]{compare.leftInput,compare.rightInput,compare.leftView,compare.rightView})verify(text);
   AuthComparePanel auth=new AuthComparePanel(null,java.util.List::of);for(Component c:PacketUiTest.components(auth))if(c instanceof JTextArea text&&Boolean.TRUE.equals(text.getClientProperty("wb.packet.syntax")))verify(text);auth.close();
   EnvironmentPanel environment=new EnvironmentPanel(null);verify(environment.requestView);verify(environment.responseView);environment.close();
  });
  JsAnalysisPanel panel=new JsAnalysisPanel(null,()->{});String code="fetch('/first');\nfetch('/second');";edt(()->panel.load(code,"https://test/source.js"));JsAnalysisPanelTest.waitDone(panel);
  edt(()->{verify(panel.input);check(panel.input.getText().equals(code),"analysis source retains raw text with syntax and line gutter");panel.urlTable.setRowSelectionInterval(1,1);check(panel.input.getCaretPosition()==code.indexOf("'/second'"),"result navigation still selects the original source position");panel.close();});
  System.out.println("PacketStyleTest: "+checks+" checks passed");
 }
}
