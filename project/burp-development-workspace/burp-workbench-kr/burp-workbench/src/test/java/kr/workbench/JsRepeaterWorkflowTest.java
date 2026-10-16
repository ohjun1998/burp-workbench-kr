package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.*;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.internal.*;
import burp.api.montoya.repeater.Repeater;
import com.sun.net.httpserver.HttpServer;
import java.awt.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import static kr.workbench.WorkbenchTest.*;

/** Production controls with a disposable loopback HTTP receiver. No native Burp. */
public final class JsRepeaterWorkflowTest {
  static int checks;
  static void verify(boolean condition,String label) {if(!condition)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
  static String wire(JsRepeater.Session session) {return new String(session.request.editedBytes(),StandardCharsets.UTF_8);}
  static void waitSend(JsRepeater.Session session)throws Exception {
    session.pending.get(8,TimeUnit.SECONDS);edt(()->{});
  }
  static void shortcut(JsRepeater.Session s,JComponent focused){
    var stroke=KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SPACE,java.awt.event.InputEvent.CTRL_DOWN_MASK);
    Object binding=focused.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
    verify(binding!=null&&focused.getActionMap().get(binding)!=null,"Ctrl+Space overrides the focused editor binding");
    focused.getActionMap().get(binding).actionPerformed(new java.awt.event.ActionEvent(focused,0,"shortcut"));
  }
  static void factory(AtomicBoolean redirectsDisabled) {
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->switch(m.getName()) {
      case "byteArray"->bytes((byte[])a[0]);
      case "httpService"->proxy(HttpService.class,(q,n,b)->switch(n.getName()){case "host"->a[0];case "port"->a[1];case "secure"->a[2];default->def(n);});
      case "requestOptions"->proxy(RequestOptions.class,(q,n,b)->{if(n.getName().equals("withRedirectionMode"))redirectsDisabled.set(b[0]==RedirectionMode.NEVER);return q;});
      case "httpRequest"->{
        HttpService service=(HttpService)a[0];String raw=a[1].toString();int split=raw.indexOf("\r\n\r\n");
        String[] lines=raw.substring(0,split).split("\r\n"),first=lines[0].split(" ",3);List<HttpHeader> headers=new ArrayList<>();
        for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');headers.add(header(lines[i].substring(0,colon),lines[i].substring(colon+1).strip()));}
        String authority=service.host()+(service.port()==(service.secure()?443:80)?"":":"+service.port());
        yield BountyFeaturesTest.rq((service.secure()?"https":"http")+"://"+authority+first[1],first[0],raw.substring(split+4),headers);
      }
      default->def(m);
    });
  }

  public static void main(String[] args)throws Exception {
    AtomicInteger received=new AtomicInteger(),exported=new AtomicInteger();
    AtomicReference<String> receivedBody=new AtomicReference<>(),receivedQuery=new AtomicReference<>();
    AtomicBoolean inScope=new AtomicBoolean(true),redirectsDisabled=new AtomicBoolean();
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/api/users",exchange->{
      receivedBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
      receivedQuery.set(exchange.getRequestURI().getRawQuery());int number=received.incrementAndGet();
      byte[] body=("{\"request\":"+number+",\"ok\":true}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
      exchange.getResponseBody().write(body);exchange.close();
    });server.start();
    String origin="http://127.0.0.1:"+server.getAddress().getPort();
    java.net.http.HttpClient client=java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
    var http=proxy(burp.api.montoya.http.Http.class,(p,m,a)->{
      if(!m.getName().equals("sendRequest"))return def(m);
      HttpRequest packet=(HttpRequest)a[0];
      var outgoing=java.net.http.HttpRequest.newBuilder(URI.create(packet.url())).method(packet.method(),java.net.http.HttpRequest.BodyPublishers.ofByteArray(packet.body().getBytes()));
      for(HttpHeader header:packet.headers())if(!List.of("host","content-length").contains(header.name().toLowerCase(java.util.Locale.ROOT)))outgoing.header(header.name(),header.value());
      var reply=client.send(outgoing.build(),java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return AuthRecordTest.rr(packet,JsResponseWorkflowTest.response("application/json",reply.body()));
    });
    var api=proxy(MontoyaApi.class,(p,m,a)->switch(m.getName()) {
      case "http"->http;
      case "scope"->proxy(burp.api.montoya.scope.Scope.class,(q,n,b)->n.getName().equals("isInScope")?inScope.get()&&b[0].toString().startsWith(origin+"/"):def(n));
      case "repeater"->proxy(Repeater.class,(q,n,b)->{if(n.getName().equals("sendToRepeater"))exported.incrementAndGet();return def(n);});
      default->def(m);
    });factory(redirectsDisabled);
    AtomicReference<JsAnalysisPanel> panelRef=new AtomicReference<>();
    try {
      edt(()->panelRef.set(new JsAnalysisPanel(api,()->{})));var panel=panelRef.get();
      AtomicInteger windows=new AtomicInteger();
      edt(()->{
        panel.repeater.opener=(owner,title,body,modal,closed)->{verify(!modal&&title.startsWith("Repeater"),"Ctrl+R opens an independent modeless Repeater");windows.incrementAndGet();return null;};
        panel.load("fetch('/api/users?lang=ko',{method:'POST',headers:{Authorization:'SOURCE_SECRET'},body:JSON.stringify({name:'sample',role:userRole})});",origin+"/assets/app.js");
      });JsAnalysisPanelTest.waitDone(panel);
      edt(()->{
        panel.setSize(1260,780);for(int i=0;i<5;i++)UiLayoutTest.layout(panel);
        var toolbar=(JPanel)UiLayoutTest.named(panel,"wb.js.toolbar");
        Component[] buttons=toolbar.getComponents();
        verify(buttons[0]==panel.passive&&buttons[1].getName().equals("wb.target.settings")&&buttons[2]==panel.analysisList,"analysis, target settings and analysis list keep the approved order");
        verify(buttons[3]==panel.analysisDetails&&buttons[4].getName().equals("wb.js.actions.separator")&&buttons[5]==panel.resolve,"analysis details follows the list before the small divider and URL method actions");
        verify(panel.resolve.getY()+panel.resolve.getHeight()<=toolbar.getHeight()&&panel.passive.getWidth()>60,"actions wrap visibly alongside aggregate counters at 1280-pixel workspace");
        verify(panel.progress.getFont().getSize()==11&&panel.progress.getHorizontalAlignment()==SwingConstants.RIGHT&&!panel.progress.isOpaque(),"analysis counters are small, unboxed and right aligned");
        verify(!Boolean.TRUE.equals(panel.status.getClientProperty("wb.status")),"analysis messages no longer occupy a large colored footer");
        verify(panel.resultTabs.previous.isVisible()&&panel.resultTabs.next.isVisible(),"both tab-scroll arrows remain present");
        panel.setSize(1000,780);for(int i=0;i<5;i++)UiLayoutTest.layout(panel);
        panel.resultTabs.next.doClick(0);verify(panel.resultTabs.viewport.getViewPosition().x>0,"right arrow scrolls the result titles");
        panel.resultTabs.previous.doClick(0);verify(panel.resultTabs.viewport.getViewPosition().x==0,"left arrow returns to the first result titles");
        panel.results.setSelectedIndex(6);
      });edt(()->{});
      edt(()->{
        Rectangle selected=panel.resultTabs.titles.get(6).getBounds();int x=panel.resultTabs.viewport.getViewPosition().x;
        verify(selected.x>=x&&selected.x+selected.width<=x+panel.resultTabs.viewport.getWidth(),"programmatic and keyboard tab selection reveals the selected caption");
        panel.results.setSelectedIndex(0);panel.urlTable.setRowSelectionInterval(0,0);panel.prepareRepeater();
      });var session=panel.repeater.sessions.get(0);
      edt(()->{
        String draft=wire(session);
        verify(draft.startsWith("POST /api/users?lang=ko HTTP/1.1"),"source URL and confirmed query values are combined into the selected request");
        verify(draft.contains("\"name\":\"sample\"")&&draft.contains("\"role\":\"REPLACE_ME\""),"confirmed body values are retained while dynamic fields stay unresolved");
        verify(!draft.contains("SOURCE_SECRET")&&!draft.contains("Authorization:"),"source credentials are not silently copied into a generated request");
        verify(received.get()==0&&exported.get()==0&&windows.get()==1,"opening and selecting a draft submit no HTTP and create no native Repeater tab");
        String edited=draft.replace("lang=REPLACE_ME","lang=ko").replace("\"name\":\"sample\"","\"name\":\"홍길동\"").replace("\"role\":\"REPLACE_ME\"","\"role\":\"viewer\"");
        session.request.text.setText(edited);
        session.request.tabs.get(0).doClick(0);verify(!session.request.text.isEditable(),"Pretty is a display-only view of the edited request");
        session.request.tabs.get(2).doClick(0);verify(!session.request.text.isEditable(),"Hex is display-only");
        session.request.tabs.get(1).doClick(0);
        verify(session.request.text.isEditable()&&wire(session).equals(edited),"Raw edits, Unicode and CRLF survive Pretty/Hex round trips");
        shortcut(session,session.request.text);
        shortcut(session,session.response.search);
      });waitSend(session);
      edt(()->{
        verify(received.get()==1&&receivedQuery.get().equals("lang=ko"),"Send delivers the edited URL and query to an isolated loopback receiver");
        verify(receivedBody.get().contains("홍길동")&&receivedBody.get().contains("viewer"),"Send delivers edited UTF-8 JSON parameters");
        verify(wire(session).contains("Content-Length: "+receivedBody.get().getBytes(StandardCharsets.UTF_8).length+"\r\n"),"request history shows the actual transmitted UTF-8 Content-Length");
        verify(redirectsDisabled.get(),"Repeater submission explicitly disables automatic redirects");
        verify(session.response.text.getText().contains("\"request\": 1")&&session.result.getText().startsWith("200 ·"),"the real loopback response and measured status appear in the window");
        session.request.more.doClick(0);verify(exported.get()==1&&received.get()==1,"optional native Repeater copy preserves explicit send control");
        session.request.text.setText(wire(session).replace("viewer","member"));
        verify(session.response.text.getText().isBlank()&&session.result.getText().contains("미전송"),"editing a sent request clears the displayed old response");
        shortcut(session,session.target);
      });waitSend(session);
      edt(()->{
        verify(received.get()==2&&receivedBody.get().contains("member"),"a second Send uses the new body rather than stale initial parameters");
        session.previous.doClick(0);
        verify(wire(session).contains("viewer")&&session.response.text.getText().contains("\"request\": 1"),"history back restores the matching first request and response");
        session.next.doClick(0);
        verify(wire(session).contains("member")&&session.response.text.getText().contains("\"request\": 2"),"history forward restores the matching second pair");
        String valid=wire(session);
        for(String placeholder:List.of("REPLACE_ME","REPLACE_METHOD","REPLACE_PARAMETER","REPLACE_PARAM","REPLACE_PATH","REPLACE_BODY","REPLACE%5FPARAMETER","%2552EPLACE_ME","REPLACE\\u005fME","{dynamic}")){
          session.request.text.setText(valid.replace("member",placeholder));
          shortcut(session,session.request.text);
          verify(received.get()==2&&session.note.getText().contains("미확정")&&session.response.text.getText().isBlank(),"unresolved marker blocks shortcut and cannot display stale response: "+placeholder);
        }
        session.request.text.setText(valid);
        inScope.set(false);session.send.doClick(0);
        verify(received.get()==2&&session.note.getText().contains("Scope"),"Scope removal blocks sending an already opened request");inScope.set(true);
        session.target.setText(origin+"/unexpected");session.send.doClick(0);
        verify(received.get()==2&&session.note.getText().contains("Origin"),"target paths cannot silently change a draft destination");session.target.setText(origin);
        panel.load("fetch('/api/users/'+id);",origin+"/next.js");
      });JsAnalysisPanelTest.waitDone(panel);
      edt(()->{
        verify(wire(session).contains("member"),"background analysis does not replace an opened edited Repeater request");
        panel.urlTable.setRowSelectionInterval(0,0);panel.prepareRepeater();
        var unresolved=panel.repeater.sessions.get(1);unresolved.send.doClick(0);
        verify(wire(unresolved).contains("REPLACE_PATH")&&received.get()==2,"dynamic paths open immediately but cannot be sent unresolved");
        var unknown=ReviewWorkflowTest.item(origin+"/api/users","?","","",true);
        panel.repeater.open(List.of(JsRequestDraft.preview(unknown)));var method=panel.repeater.sessions.get(2);method.send.doClick(0);
        verify(wire(method).startsWith("REPLACE_METHOD ")&&received.get()==2,"unknown methods are explicit and block sending until edited");
        byte[] large=("GET /api/users HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Demo: "+"x".repeat(70000)+"\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        method.request.setPacket(large,null);method.send.doClick(0);
        verify(received.get()==2&&method.note.getText().contains("잘린"),"truncated request previews are never transmitted as complete packets");
        panel.close();verify(session.closed&&session.worker.isShutdown()&&session.request.closed()&&session.response.closed(),"unload closes Repeater workers and clears packet editors");
        int opened=windows.get();panel.repeater.open(List.of(JsRequestDraft.preview(unknown)));
        verify(windows.get()==opened&&panel.repeater.sessions.isEmpty(),"unloaded workspaces cannot reopen draft windows");
      });
    } finally {
      if(panelRef.get()!=null)edt(panelRef.get()::close);server.stop(0);
    }
    System.out.println("JsRepeaterWorkflowTest: "+checks+" checks passed");
  }
}
