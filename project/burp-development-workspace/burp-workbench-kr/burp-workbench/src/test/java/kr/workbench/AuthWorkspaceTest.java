package kr.workbench;

import burp.api.montoya.core.*;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.requests.*;
import java.awt.event.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Production capture callback and popup actions; no native Burp or external HTTP. */
public final class AuthWorkspaceTest extends WorkbenchTest {
  static void emit(TrafficScopeModesTest.Fixture f,HttpRequest request,ToolType type){
    ToolSource source=proxy(ToolSource.class,(p,m,a)->m.getName().equals("toolType")?type:def(m));
    var event=proxy(HttpRequestToBeSent.class,(p,m,a)->switch(m.getName()){
      case "toolSource"->source;case "messageId"->991;case "annotations"->null;
      default->HttpRequest.class.getMethod(m.getName(),m.getParameterTypes()).invoke(request,a);
    });
    f.ext.handleHttpRequestToBeSent(event);
  }
  static KeyEvent key(JComponent source,int id){return new KeyEvent(source,id,System.currentTimeMillis(),InputEvent.CTRL_DOWN_MASK,KeyEvent.VK_SPACE,' ');}
  public static void main(String[] args)throws Exception {
    try(var f=new TrafficScopeModesTest.Fixture()){
      AuthComparePanel p=(AuthComparePanel)get(f.ext,"authCompare");
      AtomicInteger reads=new AtomicInteger();
      HttpRequest forbidden=proxy(HttpRequest.class,(o,m,a)->{reads.incrementAndGet();throw new AssertionError("OFF touched request "+m.getName());});
      emit(f,forbidden,ToolType.PROXY);edt(()->{});
      check(reads.get()==0&&p.credentials.isEmpty(),"detection OFF skips credentials before request reads");
      edt(()->p.detection.doClick(0));
      HttpRequest alice=request("https://outside.test/admin","GET",false,List.of(header("Cookie","SID=alice-secret"),header("Authorization","Bearer alice-token")));
      emit(f,alice,ToolType.PROXY);edt(()->{});
      check(p.credentials.size()==1&&f.mode().getText().equals("OFF")&&f.queued()==0,"detection ON works outside Scope while traffic OFF");
      edt(()->f.mode().doClick(0));
      HttpRequest bob=request("https://outside.test/admin","GET",false,List.of(header("Cookie","SID=bob-secret")));
      emit(f,bob,ToolType.PROXY);edt(()->{});
      check(p.credentials.size()==2&&f.mode().getText().equals("SCOPE"),"credential detection independent of SCOPE filtering");
      emit(f,AuthRecordTest.q("extension"),ToolType.EXTENSIONS);edt(()->{});
      check(p.credentials.size()==2,"extension replays never create candidates");
      edt(()->{emit(f,AuthRecordTest.q("queued"),ToolType.PROXY);p.detection.doClick(0);});edt(()->{});
      check(p.credentials.size()==2&&!p.detectionEnabled&&p.credentialQueue.isEmpty(),"OFF invalidates queued detection without deleting saved candidates");
      edt(()->{
        var candidates=new ArrayList<>(p.credentials.values());var a=candidates.get(0);var b=candidates.get(1);
        p.mapCandidate(a,new AuthAccount("Alice","alice","101","관리자","alice@example.test"));p.assign(0,a);
        check(p.label(0).contains("Alice")&&p.profileOwners[0].getText().equals("101"),"manual account mapping updates assigned user and id");
        p.openCandidate(a);check(p.candidateDetails.name.getText().equals("Alice"),"candidate popup loads selected mapped identity");
        p.assign(0,b);check(!p.label(0).contains("Alice")&&p.profileOwners[0].getText().isEmpty(),"reassignment clears previous user's metadata");
        p.mapCandidate(b,new AuthAccount("Bob","bob","202","사용자",""));
        check(p.candidateModel.getValueAt(1,1).toString().contains("Bob"),"candidate table shows mapped account");
        check(!p.candidateModel.getValueAt(0,4).toString().contains("alice-secret")&&!p.candidateModel.getValueAt(0,4).toString().contains("alice-token"),"candidate list masks Cookie and token values");
        check(!SwingUtilities.isDescendingFrom(p.packetDetails,p),"packet and verdict panel only lives in detached detail window");
        check(PacketUiTest.buttons(p.packetDetails).stream().noneMatch(s->s.contains("Repeater")),"detail window has no Repeater button");
        var original=AuthRecordTest.q("original");var response=response(200,"{\"owner\":\"alice\"}");
        var run=new AuthComparePanel.Run(7,original,response,p.context());
        run.packets[0].request=bob;run.packets[0].response=response;
        p.comparisonWindow.opener=(owner,title,body,modal,closed)->null;
        AtomicReference<JsRepeater.Session> opened=new AtomicReference<>();
        p.capturedRepeater.opener=(owner,title,body,modal,closed)->{opened.set((JsRepeater.Session)body);return null;};
        p.openComparison(run);p.packets.setSelectedIndex(0);
        check(p.history.getColumnModel().getColumn(2).getHeaderValue().toString().contains("Bob"),"history header uses run's mapped identity");
        check(p.detailShortcuts.handle(key(p.packetDetails,KeyEvent.KEY_PRESSED),p.requestViews[0].text),"Ctrl+Space handled from selected packet editor");
        var session=opened.get();check(session!=null&&session.entries.size()==1,"Ctrl+Space opens a separate Repeater session");
        check(new String(session.request.editedBytes(),java.nio.charset.StandardCharsets.UTF_8).contains("bob-secret"),"Repeater receives selected account's exact authentication");
        var binary=new PacketInspector("test.binary",true);byte[] originalBytes={71,69,84,32,(byte)0xff,0};
        binary.setPacket(originalBytes,null);check(Arrays.equals(binary.editedBytes(),originalBytes),"unchanged Raw editor preserves non-UTF8 bytes");binary.close();
        check(session.pending==null,"opening Repeater does not send HTTP");
        session.shortcuts.handle(key(session,KeyEvent.KEY_PRESSED),session.request.text);
        check(session.pending==null,"held opening shortcut cannot send request in Repeater");
        session.shortcuts.handle(key(session,KeyEvent.KEY_RELEASED),session.request.text);
        check(!p.detailShortcuts.handle(key(new JPanel(),KeyEvent.KEY_PRESSED),new JPanel()),"detail shortcut is limited to detail focus");
        check(p.record.getText().equals("요청 비교 OFF"),"request comparison has explicit OFF label");
      });
    }
    System.out.println("AuthWorkspaceTest: "+checks+" checks passed");
  }
}
