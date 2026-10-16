package kr.workbench;
import java.awt.*;
import java.awt.image.BufferedImage;
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
 public static void main(String[] args)throws Exception{
  edt(()->{
   String raw="const api = '/users';\nfetch(api, {method: 'POST'});\n{\"count\":2,\"enabled\":true}\n<node id=\"x\">text</node>\n";
   JTextArea area=new JTextArea(raw);area.setName("style.fixture");area.putClientProperty("wb.packet.syntax",true);JScrollPane scroll=new JScrollPane(area);WorkbenchUi.style(scroll);
   Document document=area.getDocument();try{area.getHighlighter().addHighlight(6,9,new DefaultHighlighter.DefaultHighlightPainter(Color.YELLOW));}catch(BadLocationException e){throw new RuntimeException(e);}
   var highlight=area.getHighlighter().getHighlights()[0];scroll.setSize(540,160);for(int i=0;i<4;i++)UiLayoutTest.layout(scroll);verify(area);
   paint(scroll);check(area.getText().equals(raw)&&area.getDocument()==document,"syntax painting preserves exact source and document identity");
   check(highlight.getStartOffset()==6&&highlight.getEndOffset()==9&&area.getHighlighter().getHighlights().length==1,"finding highlights and source offsets survive painting");
   BufferedImage image=new BufferedImage(area.getWidth(),area.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();area.paint(g);g.dispose();boolean blue=false,green=false;
   for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){Color c=new Color(image.getRGB(x,y));blue|=c.equals(new Color(47,80,149))||c.equals(new Color(161,188,239));green|=c.equals(new Color(36,119,47))||c.equals(new Color(155,209,113));}
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
