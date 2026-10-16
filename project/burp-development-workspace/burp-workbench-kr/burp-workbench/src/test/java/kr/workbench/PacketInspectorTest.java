package kr.workbench;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.swing.*;
import javax.swing.text.StyleConstants;

/** Real tab, byte, formatting, search/navigation and cancellation regressions on the EDT. */
public final class PacketInspectorTest extends WorkbenchTest {
  static void idle(PacketInspector p)throws Exception {
    long end=System.nanoTime()+3_000_000_000L;
    while(System.nanoTime()<end) {
      boolean[] ready={false};edt(()->ready[0]=!p.debounce.isRunning()&&(p.pending==null||p.pending.isDone())&&!p.count.getText().equals("…"));
      if(ready[0]){edt(()->{});return;}Thread.sleep(10);
    }
    throw new AssertionError("search did not settle");
  }
  static void query(PacketInspector p,String q)throws Exception {edt(()->p.search.setText(q));idle(p);}
  static void mode(PacketInspector p,int index)throws Exception {edt(()->p.tabs.get(index).doClick(0));idle(p);}
  public static void main(String[] args)throws Exception {
    PacketInspector[] ref=new PacketInspector[2];edt(()->{ref[0]=new PacketInspector("test.request");ref[1]=new PacketInspector("test.response");});
    PacketInspector p=ref[0],other=ref[1];
    String raw="POST /api/profile HTTP/1.1\r\nHost: example.test\r\nContent-Type: application/json\r\n\r\n{\"userId\":9007199254740993,\"name\":\"demo\",\"role\":\"viewer\"}";
    byte[] original=raw.getBytes(StandardCharsets.UTF_8),copy=original.clone();
    try {
      edt(()->{p.setPacket(original,null);check(!p.text.isEditable()&&p.tabs.size()==3&&p.mode()==PacketInspector.Mode.PRETTY,"three read-only views start in Pretty");check(p.text.getText().contains("\n  \"userId\": 9007199254740993"),"Pretty indents JSON without rounding its integer");
        int key=p.text.getText().indexOf("userId"),value=p.text.getText().indexOf("demo");
        check(!StyleConstants.getForeground(p.text.getStyledDocument().getCharacterElement(key).getAttributes()).equals(StyleConstants.getForeground(p.text.getStyledDocument().getCharacterElement(value).getAttributes())),"Pretty paints JSON keys and string values differently");});
      mode(p,1);edt(()->check(p.text.getText().equals(raw),"Raw preserves the original text and CRLF without formatting"));
      edt(()->p.text.getDocument().putProperty(javax.swing.text.DefaultEditorKit.EndOfLineStringProperty,"\r\n"));
      check(p.text.getText().equals(raw),"Windows editor-kit serialization cannot duplicate raw carriage returns");
      query(p,"userId");check(p.matches().size()==1&&p.matches().get(0).start()==raw.indexOf("userId"),"Windows search offsets use document text rather than OS-serialized line endings: count="+p.count.getText()+" spans="+p.matches()+" expected="+raw.indexOf("userId"));
      edt(()->p.search.setText(""));
      mode(p,2);edt(()->check(p.text.getText().startsWith("00000000  50 4F 53 54")&&p.text.getText().contains("00000010"),"Hex shows byte offsets and the actual HTTP bytes"));
      check(Arrays.equals(original,copy)&&Arrays.equals(p.previewBytes(),copy),"view changes do not alter original bytes");
      byte[] binary={0,10,13,32,65,(byte)128,(byte)255};edt(()->p.setPacket(binary,null));
      check(p.text.getText().contains("00 0A 0D 20 41 80 FF")&&p.text.getText().contains("... A.."),"Hex represents non-text bytes without lossy Unicode conversion");
      binary[0]=99;check(p.previewBytes()[0]==0,"inspector owns a bounded copy rather than mutating or aliasing input");
      edt(()->p.setPacket("한글😀".getBytes(StandardCharsets.UTF_8),null));mode(p,1);
      check(p.text.getText().equals("한글😀"),"Raw displays valid UTF-8 Korean and supplementary characters");
      edt(()->p.setPacket("userId USERID userid a.b aXb".getBytes(StandardCharsets.UTF_8),null));
      query(p,"userid");check(p.matches().size()==3&&p.count.getText().equals("1 / 3"),"Search defaults to case-insensitive literal matches and displays count");
      edt(()->p.next.doClick(0));check(p.currentMatch()==1&&p.count.getText().equals("2 / 3"),"next navigates to the second match");
      edt(()->{p.next.doClick(0);p.next.doClick(0);});check(p.currentMatch()==0,"next wraps from last match to first");
      edt(()->p.previous.doClick(0));check(p.currentMatch()==2,"previous wraps from first match to last");
      edt(()->p.caseSensitive.doClick(0));idle(p);check(p.matches().size()==1,"Aa restricts matching to the exact case");
      query(p,"a.b");check(p.matches().size()==1,"literal Search treats regex punctuation as text");
      edt(()->p.regex.doClick(0));idle(p);check(p.matches().size()==2,"regex option searches the displayed packet using the expression");
      query(p,"[");check(p.matches().isEmpty()&&p.text.getHighlighter().getHighlights().length==0&&!p.next.isEnabled(),"invalid regex clears stale matches and disables navigation");
      query(p,"(?=a)");check(p.matches().isEmpty(),"zero-length regex does not create invisible highlights or navigation loops");
      edt(()->{p.regex.doClick(0);p.caseSensitive.doClick(0);});query(p,"not-found");check(p.count.getText().equals("0 / 0")&&!p.previous.isEnabled(),"unmatched search shows zero with no stale navigation");
      query(p,"userid");edt(()->p.search.getActionMap().get("next").actionPerformed(new ActionEvent(p.search,0,"")));check(p.currentMatch()==1,"Enter action navigates without changing the search text");
      mode(p,2);check(p.search.getText().equals("userid")&&p.mode()==PacketInspector.Mode.HEX&&p.matches().equals(PacketInspector.find(p.text.getText(),"userid",false,false,()->false).spans()),"mode change preserves query and recomputes against Hex byte and ASCII display");
      query(p,"75 73 65 72");check(!p.matches().isEmpty(),"Hex searches visible byte notation");
      mode(p,1);query(p,"userId");edt(()->{other.setPacket("other userId".getBytes(StandardCharsets.UTF_8),null);other.search.setText("other");});idle(other);
      check(p.search.getText().equals("userId")&&other.matches().size()==1,"request and response maintain independent Search state");
      edt(()->{p.setPacket("no old query".getBytes(StandardCharsets.UTF_8),null);p.search.setText("old");p.setPacket("NEW PACKET".getBytes(StandardCharsets.UTF_8),null);p.search.setText("NEW");});idle(p);
      check(p.matches().size()==1&&p.matches().get(0).start()==0&&p.text.getText().equals("NEW PACKET"),"late search results cannot attach to a newly selected packet");
      edt(()->{p.setPacket("x".repeat(3000).getBytes(StandardCharsets.UTF_8),null);p.search.setText("x");});idle(p);
      check(p.matches().size()==PacketInspector.MAX_MATCHES&&p.count.getText().endsWith("+"),"large match sets are capped and explicitly marked");
      edt(()->{p.next.doClick(0);});check(p.count.getText().endsWith("+"),"capped result marker remains during navigation");
      edt(()->p.clear.doClick(0));check(p.search.getText().isEmpty()&&p.matches().isEmpty()&&p.count.getText().equals("0 / 0"),"clear removes query count and highlights immediately");
      long started=System.nanoTime();var slow=PacketInspector.find("a".repeat(20000)+"!","(a+)+$",true,true,()->false);
      check(slow.error()!=null&&(System.nanoTime()-started)<1_500_000_000L,"catastrophic regex is stopped by the guarded text deadline");
      var cancelled=PacketInspector.find("abc ".repeat(1000),"abc",false,false,()->true);
      check(cancelled.error()!=null,"cancelled search exits without returning stale matches");
      check(PacketInspector.find("abc","a",false,false,()->false).spans().size()==1,"normal search still works after a rejected complex expression");
      check(PacketInspector.find("abc","x".repeat(513),true,false,()->false).error()!=null,"oversized regex query is rejected before compilation");
      byte[] large=new byte[200000];Arrays.fill(large,(byte)'A');edt(()->p.setPacket(large,null));
      check(p.previewBytes().length==PacketInspector.PREVIEW_BYTES&&p.getToolTipText().contains("64 KiB")&&large.length==200000,"preview bound is explicit and leaves the complete input untouched");
      var boundedWire=proxy(burp.api.montoya.core.ByteArray.class,(owner,method,values)->switch(method.getName()){
        case "length"->10_000_000;case "getBytes"->throw new AssertionError("large wire copied in full");
        case "subArray"->{check((int)values[0]==0&&(int)values[1]==PacketInspector.PREVIEW_BYTES,"large wire requests only the displayed byte range");yield bytes(new byte[PacketInspector.PREVIEW_BYTES]);}default->def(method);
      });
      check(PacketInspector.prefix(boundedWire).length==PacketInspector.PREVIEW_BYTES,"preview never requests the entire large native ByteArray");
      edt(()->{other.setPacket("COMPRESSED_WIRE".getBytes(StandardCharsets.UTF_8),"HTTP/1.1 200\r\n\r\n{\"decoded\":true}");});
      check(other.text.getText().contains("\n  \"decoded\": true"),"Pretty accepts a separately decoded response without changing the wire");
      mode(other,1);check(other.text.getText().equals("COMPRESSED_WIRE"),"Raw retains original compressed response bytes after Pretty decoding");
      edt(()->{p.setPacket(copy,null);p.search.setText("userId");});idle(p);Color old=UIManager.getColor("Panel.background");
      edt(()->{UIManager.put("Panel.background",new Color(25,25,25));WorkbenchUi.style(p);});
      check(p.search.getText().equals("userId")&&p.matches().size()==1&&p.mode()==PacketInspector.Mode.RAW,"theme update preserves mode query and matches");
      edt(()->UIManager.put("Panel.background",old));
      edt(()->{p.search.setText("demo");p.close();});idle(p);
      check(p.closed()&&p.text.getText().isEmpty()&&p.matches().isEmpty()&&p.previewBytes().length==0,"unload cancels work and clears retained preview and search content");
      edt(()->p.setPacket(copy,null));check(p.text.getText().isEmpty(),"closed inspector ignores later packet updates");
    } finally {edt(()->{p.close();other.close();});}
    System.out.println("PacketInspectorTest: "+checks+" checks passed");
  }
}
