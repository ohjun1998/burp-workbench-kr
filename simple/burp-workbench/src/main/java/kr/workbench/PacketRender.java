package kr.workbench;

import java.io.*;
import java.util.*;
import javax.swing.*;
import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;

/** Static HTML from captured bytes only. No URL attributes, CSS imports, scripts or active views. */
final class PacketRender {
  private PacketRender() {}
  private static final Set<String> TAGS=Set.of("html","body","p","div","span","br","hr","b","strong","i","em","u","s","small","big","h1","h2","h3","h4","h5","h6","pre","code","blockquote","ul","ol","li","dl","dt","dd","table","thead","tbody","tfoot","tr","td","th","caption","center","sup","sub");
  static JEditorPane pane(){
    JEditorPane pane=new JEditorPane();pane.setEditable(false);pane.setEditorKit(new HTMLEditorKit(){
      private final ViewFactory views=new HTMLFactory(){
        @Override public View create(Element element){Object tag=element.getAttributes().getAttribute(StyleConstants.NameAttribute);if(tag==HTML.Tag.IMG||tag==HTML.Tag.OBJECT||tag==HTML.Tag.INPUT||tag==HTML.Tag.FRAME||tag==HTML.Tag.LINK)return new LabelView(element);return super.create(element);}
      };
      @Override public ViewFactory getViewFactory(){return views;}
    });
    pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES,true);return pane;
  }
  static String escape(String text){return Objects.toString(text,"").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
  static String document(String packet,boolean limited){
    int boundary=packet.indexOf("\r\n\r\n"),skip=4;if(boundary<0){boundary=packet.indexOf("\n\n");skip=2;}
    String header=boundary<0?"":packet.substring(0,boundary),body=boundary<0?packet:packet.substring(boundary+skip);
    boolean html=header.toLowerCase(Locale.ROOT).contains("text/html")||header.toLowerCase(Locale.ROOT).contains("application/xhtml+xml")
        ||header.isEmpty()&&body.stripLeading().matches("(?is)<(?:!doctype\\s+html|html|body|h[1-6]|div|p)(?:[\\s>].*)?");
    String content=html?sanitize(body):"<pre>"+escape(body)+"</pre>";
    return "<html><body>"+(limited?"<p><b>앞 64 KiB 미리보기 · 본문 일부가 생략될 수 있습니다.</b></p>":"")+content+"</body></html>";
  }
  static String sanitize(String body){
    StringBuilder out=new StringBuilder();
    try{new ParserDelegator().parse(new StringReader(body),new HTMLEditorKit.ParserCallback(){
      int hidden, nodes;final Deque<Boolean> emitted=new ArrayDeque<>();
      boolean hiddenTag(HTML.Tag t){return t==HTML.Tag.HEAD||t==HTML.Tag.SCRIPT||t==HTML.Tag.STYLE||t==HTML.Tag.OBJECT||t==HTML.Tag.APPLET;}
      @Override public void handleStartTag(HTML.Tag tag,MutableAttributeSet attrs,int pos){if(hiddenTag(tag)){hidden++;return;}if(hidden==0&&TAGS.contains(tag.toString())){boolean emit=emitted.size()<64&&nodes++<4096;emitted.push(emit);if(emit)out.append('<').append(tag).append('>');}}
      @Override public void handleEndTag(HTML.Tag tag,int pos){if(hiddenTag(tag)){hidden=Math.max(0,hidden-1);return;}if(hidden==0&&TAGS.contains(tag.toString())&&!emitted.isEmpty()&&emitted.pop())out.append("</").append(tag).append('>');}
      @Override public void handleSimpleTag(HTML.Tag tag,MutableAttributeSet attrs,int pos){if(hidden==0&&TAGS.contains(tag.toString()))out.append('<').append(tag).append('>');else if(hidden==0&&tag==HTML.Tag.IMG)out.append(" [이미지 제외] ");}
      @Override public void handleText(char[] data,int pos){if(hidden==0)out.append(escape(new String(data)));}
    },true);}catch(IOException ex){return "<pre>"+escape(body)+"</pre>";}
    return out.toString();
  }
}
