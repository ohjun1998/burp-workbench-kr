package kr.packetui;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.plaf.basic.BasicTextAreaUI;
import javax.swing.text.*;

/** Local syntax painting and a logical-line gutter; the document and offsets are never rewritten. */
public final class PacketStyle {
  private PacketStyle() {}
  private static final Pattern TOKENS=Pattern.compile(
      "//[^\\n]*|/\\*.*?(?:\\*/|$)|<!--.*?(?:-->|$)|\"(?:\\\\.|[^\\\\\"])*\"|'(?:\\\\.|[^\\\\'])*'|"
      +"</?[A-Za-z][A-Za-z0-9_.:-]*|\\b(?:const|let|var|function|return|async|await|if|else|new|class|import|export|throw|try|catch|true|false|null|undefined)\\b|"
      +"(?<![A-Za-z_$])[-+]?[0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?");
  public static boolean installed(JTextArea area) { return area.getUI() instanceof SyntaxUi; }
  public static void install(JTextArea area) {
    if(!installed(area))area.setUI(new SyntaxUi());
    var scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,area);
    if(scroll!=null && (scroll.getRowHeader()==null || !(scroll.getRowHeader().getView() instanceof Numbers)))
      scroll.setRowHeaderView(new Numbers(area));
  }
  private static boolean dark(JTextArea area) {Color c=area.getBackground();return c.getRed()+c.getGreen()+c.getBlue()<360;}
  private static Color blue(JTextArea a) {return dark(a)?new Color(161,188,239):new Color(31,91,235);}
  private static Color green(JTextArea a) {return dark(a)?new Color(155,209,113):new Color(28,141,48);}
  private static Color orange(JTextArea a) {return dark(a)?new Color(245,159,79):new Color(189,91,25);}
  private static Color muted(JTextArea a) {return dark(a)?new Color(139,155,176):new Color(104,119,139);}

  private static final class SyntaxUi extends BasicTextAreaUI {
    @Override public View create(Element element) {
      JTextArea area=(JTextArea)getComponent();
      if(area.getLineWrap())return new WrappedPlainView(element,area.getWrapStyleWord()) {
        @Override protected float drawUnselectedText(Graphics2D g,float x,float y,int start,int end)throws BadLocationException {return draw(area,this,g,x,y,start,end);}
        @Override protected int drawUnselectedText(Graphics g,int x,int y,int start,int end)throws BadLocationException {return (int)draw(area,this,(Graphics2D)g,x,y,start,end);}
      };
      return new PlainView(element) {
        @Override protected float drawUnselectedText(Graphics2D g,float x,float y,int start,int end)throws BadLocationException {return draw(area,this,g,x,y,start,end);}
        @Override protected int drawUnselectedText(Graphics g,int x,int y,int start,int end)throws BadLocationException {return (int)draw(area,this,(Graphics2D)g,x,y,start,end);}
      };
    }
  }

  private static float draw(JTextArea area,TabExpander tabs,Graphics2D g,float x,float y,int start,int end)throws BadLocationException {
    Segment segment=new Segment();int length=end-start;
    area.getDocument().getText(start,Math.min(length,8192),segment);
    String line=new String(segment.array,segment.offset,segment.count);
    int cursor=0;
    int colon=line.indexOf(':');
    if(line.stripTrailing().matches("(?:HTTP/[^ ]+ .*|(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) .*|[A-Za-z][A-Za-z0-9-]*:.*)")) {
      int head=line.startsWith("HTTP/")||colon<0?line.indexOf(' '):colon+1;
      if(head>0){x=part(area,tabs,g,x,y,start,head,blue(area));cursor=head;}
    }
    Matcher matcher=TOKENS.matcher(line);
    while(matcher.find()) {
      if(matcher.start()<cursor)continue;
      if(matcher.start()>cursor)x=part(area,tabs,g,x,y,start+cursor,matcher.start()-cursor,area.getForeground());
      String token=matcher.group();Color color;
      if(token.startsWith("//")||token.startsWith("/*")||token.startsWith("<!--"))color=muted(area);
      else if(token.startsWith("\"")||token.startsWith("'")) {
        int next=matcher.end();while(next<line.length()&&Character.isWhitespace(line.charAt(next)))next++;
        color=next<line.length()&&line.charAt(next)==':'?blue(area):green(area);
      } else color=token.startsWith("<")||Character.isJavaIdentifierStart(token.charAt(0))?blue(area):orange(area);
      x=part(area,tabs,g,x,y,start+matcher.start(),matcher.end()-matcher.start(),color);cursor=matcher.end();
    }
    if(cursor<length)x=part(area,tabs,g,x,y,start+cursor,length-cursor,area.getForeground());
    return x;
  }
  private static float part(JTextArea area,TabExpander tabs,Graphics2D g,float x,float y,int start,int length,Color color)throws BadLocationException {
    Segment segment=new Segment();area.getDocument().getText(start,length,segment);g.setColor(area.isEnabled()?color:area.getDisabledTextColor());
    return Utilities.drawTabbedText(segment,x,y,g,tabs,start);
  }

  private static final class Numbers extends JComponent {
    final JTextArea area;
    Numbers(JTextArea area) {
      this.area=area;setName((area.getName()==null?"packet":area.getName())+".lines");
      // One listener per text component; replacing a ScrollPane never retains discarded gutters.
      if(!Boolean.TRUE.equals(area.getClientProperty("packet.lines.listener"))) {
        area.putClientProperty("packet.lines.listener",true);
        area.getDocument().addDocumentListener(new DocumentListener() {
          void refresh(){
            var scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,area);
            if(scroll!=null&&scroll.getRowHeader()!=null){scroll.getRowHeader().revalidate();scroll.getRowHeader().repaint();}
          }
          public void insertUpdate(DocumentEvent e){refresh();}public void removeUpdate(DocumentEvent e){refresh();}public void changedUpdate(DocumentEvent e){}
        });
      }
    }
    @Override public Dimension getPreferredSize() {
      int digits=Math.max(3,Integer.toString(area.getLineCount()).length());
      return new Dimension(Math.max(42,area.getFontMetrics(area.getFont()).charWidth('0')*digits+16),Math.max(1,area.getPreferredSize().height));
    }
    @Override protected void paintComponent(Graphics graphics) {
      Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setColor(area.getBackground());g.fillRect(0,0,getWidth(),getHeight());g.setFont(area.getFont());g.setColor(muted(area));
      FontMetrics metrics=g.getFontMetrics();Rectangle clip=g.getClipBounds();
      int position=area.viewToModel2D(new Point(0,Math.max(0,clip.y)));
      Element root=area.getDocument().getDefaultRootElement();
      int first=Math.max(0,root.getElementIndex(Math.max(0,position))-1);
      for(int line=first;line<root.getElementCount();line++)try {
        Rectangle2D glyph=area.modelToView2D(root.getElement(line).getStartOffset());
        if(glyph==null)continue;if(glyph.getY()>clip.getMaxY())break;
        String number=Integer.toString(line+1);g.drawString(number,getWidth()-9-metrics.stringWidth(number),(int)glyph.getY()+metrics.getAscent());
      }catch(BadLocationException ignored){}
      g.dispose();
    }
  }
}
