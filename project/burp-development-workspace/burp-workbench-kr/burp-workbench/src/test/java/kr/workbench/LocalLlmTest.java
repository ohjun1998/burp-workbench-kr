package kr.workbench;

import javax.swing.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;

public final class LocalLlmTest {
  private static int checks;
  static void check(boolean value, String label) { if(!value)throw new AssertionError(label); checks++; }
  static void rejects(Runnable action) { try { action.run(); throw new AssertionError("accepted invalid input"); } catch(IllegalArgumentException expected){checks++;} }
  static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
  public static void main(String[] args) throws Exception {
    for(String invalid : new String[]{"https://example.com:443/v1", "http://localhost:8731/v1", "http://127.0.0.1:80/other", "http://127.0.0.1:80/v1?q=x", "http://user@127.0.0.1:80/v1", "file:///v1", "http://127.0.0.1/v1", "http://127.0.0.1:65536/v1", "http://127.0.0.1.evil:80/v1", "http://2130706433:80/v1", "http://[::ffff:127.0.0.1]:80/v1"})
      rejects(()->LocalLlmClient.endpoint(invalid));
    check(LocalLlmClient.endpoint("http://127.0.0.1:8731/v1/").toString().equals(LocalLlmClient.DEFAULT_URL),"canonical endpoint");
    check(LocalLlmClient.endpoint("http://[::1]:11434/v1").getHost().equals("[::1]"),"IPv6 loopback");
    rejects(()->new LocalLlmClient.Settings(URI.create(LocalLlmClient.DEFAULT_URL),"",2048,120));
    rejects(()->new LocalLlmClient.Settings(URI.create(LocalLlmClient.DEFAULT_URL),"x\ny",2048,120));
    rejects(()->new LocalLlmClient.Settings(URI.create(LocalLlmClient.DEFAULT_URL),"model",100000,120));
    rejects(()->new LocalLlmClient.Settings(URI.create(LocalLlmClient.DEFAULT_URL),"model",2048,1000));
    var settings = new LocalLlmClient.Settings(URI.create(LocalLlmClient.DEFAULT_URL),"model",256,10);
    try { new LocalLlmClient().analyze(settings,"test","x".repeat(48001),new LocalLlmClient.Call());throw new AssertionError(); }
    catch(IllegalArgumentException expected){checks++;}
    var cancelled = new LocalLlmClient.Call();cancelled.cancel();
    try { new LocalLlmClient().analyze(settings,"test","data",cancelled);throw new AssertionError(); }
    catch(java.io.IOException expected){checks++;}
    ui();
    if(args.length == 0 || !args[0].equals("--no-http")) http();
    System.out.println(checks+" checks passed");
  }
  static void ui() throws Exception {
    AtomicReference<String> project = new AtomicReference<>("A");
    AtomicInteger calls = new AtomicInteger(), sent = new AtomicInteger(), saved = new AtomicInteger();
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    LocalLlmPanel[] p = new LocalLlmPanel[1];
    edt(()->{
      p[0] = new LocalLlmPanel(project::get,(s,m,i,c)->{calls.incrementAndGet();started.countDown();try { release.await(5,TimeUnit.SECONDS); }catch(InterruptedException e){Thread.currentThread().interrupt();}return new LocalLlmClient.Answer("미검증 SQL 가설","stop",100);});
      check(!p[0].analyze.isEnabled(),"requires selection");
      p[0].prepare(new LocalLlmPanel.Context("A","ID: original-123\nREDACTED",()->{},sent::incrementAndGet,n->saved.incrementAndGet()));
      check(calls.get()==0 && sent.get()==0 && saved.get()==0,"prepare has no effects");
      p[0].preview.append("x".repeat(48001));check(p[0].preview.getText().length()<48000,"bounded preview");
      p[0].start(); p[0].start();
      check(!p[0].analyze.isEnabled() && !p[0].evidence.isEnabled(),"in-flight actions");
    });
    check(started.await(2,TimeUnit.SECONDS),"worker started");check(calls.get()==1,"one request");
    edt(()->{p[0].cancel.doClick();p[0].prepare(new LocalLlmPanel.Context("A","new selection",()->{},sent::incrementAndGet,n->saved.incrementAndGet()));});
    release.countDown(); awaitIdle(p[0]);
    edt(()->{check(p[0].result.getText().isEmpty(),"late result discarded");check(p[0].preview.getText().equals("new selection"),"new input retained");p[0].start();});
    awaitIdle(p[0]);
    edt(()->{
      check(p[0].result.getText().contains("미검증"),"unverified label");
      check(p[0].evidence.isEnabled(),"evidence on success");
      check(sent.get()==0&&saved.get()==0,"success never sends or saves automatically");
      p[0].repeater.doClick();p[0].evidence.doClick();check(sent.get()==1&&saved.get()==1,"explicit actions");
      project.set("B");p[0].repeater.doClick();check(sent.get()==1,"project changed blocks send");
      check(p[0].preview.getText().isEmpty()&&p[0].result.getText().isEmpty(),"project clears state");
      p[0].close();check(!p[0].analyze.isEnabled(),"unload disables");
    });
  }
  static void awaitIdle(LocalLlmPanel p) throws Exception {
    for(int i=0;i<100;i++) { AtomicBoolean idle=new AtomicBoolean();edt(()->idle.set(p.analyze.isEnabled()));if(idle.get())return;Thread.sleep(20); }
    throw new AssertionError("worker failed to finish");
  }
  static void http() throws Exception {
    AtomicReference<String> request = new AtomicReference<>(), response = new AtomicReference<>("{\"choices\":[{\"message\":{\"content\":\"한국어 분석\"},\"finish_reason\":\"stop\"}]}");
    AtomicInteger code=new AtomicInteger(200),hits=new AtomicInteger();
    HttpServer server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v1/chat/completions",exchange->{hits.incrementAndGet();request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
      if(code.get()==302)exchange.getResponseHeaders().add("Location","/redirect");
      byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(code.get(),bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);} });
    server.createContext("/redirect",exchange->{hits.addAndGet(100);exchange.sendResponseHeaders(500,-1);exchange.close();});
    server.start();
    try {
      var settings=new LocalLlmClient.Settings(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1"),"test-model",1024,10);
      var client=new LocalLlmClient();var answer=client.analyze(settings,"가설","ID:123\n\"quoted\"",new LocalLlmClient.Call());
      check(answer.text().equals("한국어 분석"),"real HTTP UTF-8");
      var payload=(java.util.Map<?,?>)MinerJson.parse(request.get());
      check(payload.get("model").equals("test-model")&&payload.get("stream").equals(false),"wire config");
      check(!payload.containsKey("tools"),"no tools in request");
      check(((java.util.List<?>)payload.get("messages")).size()==2,"instructions isolated from data");
      for(String invalid:new String[]{"not json","{\"choices\":[]}","{\"choices\":[{\"message\":{\"content\":null}}]}","x".repeat(LocalLlmClient.RESPONSE_LIMIT+1)}){
        response.set(invalid);try{client.analyze(settings,"test","data",new LocalLlmClient.Call());throw new AssertionError("invalid response");}catch(java.io.IOException expected){checks++;}
      }
      code.set(302);response.set("redirect");int before=hits.get();
      try{client.analyze(settings,"test","data",new LocalLlmClient.Call());throw new AssertionError("redirect followed");}catch(java.io.IOException expected){checks++;}
      check(hits.get()==before+1,"no redirects and no retries");
    } finally {server.stop(0);}
  }
}
