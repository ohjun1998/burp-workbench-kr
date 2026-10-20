package kr.workbench;

import kr.payloadrunner.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Real loopback identity controls, request-budget boundaries and bounded response negatives. */
public final class ResponseHardeningTest {
  static int checks;
  static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);checks++;}
  static void rejects(Runnable run,String reason){try{run.run();throw new AssertionError(reason);}catch(IllegalArgumentException expected){checks++;}}
  static byte[] http(int port,byte[] req)throws Exception {try(Socket socket=new Socket("127.0.0.1",port)){socket.setSoTimeout(3000);socket.getOutputStream().write(req);return socket.getInputStream().readAllBytes();}}
  static byte[] response(int status,String headers,String body){return ("HTTP/1.1 "+status+" Fixture\r\n"+headers+"\r\n\r\n"+body).getBytes(StandardCharsets.UTF_8);}
  public static void main(String[] args)throws Exception {
    var checkConfig=new PayloadSessionCheck.Config("/me","$.id","alice");
    for(String path:List.of("https://outside.invalid/me","//outside.invalid/me","/me#x","/me\r\nX-Evil: 1","/\\outside"))rejects(()->new PayloadSessionCheck.Config(path,"$.id","alice"),"external/malformed identity path rejected");
    rejects(()->new PayloadSessionCheck.Config("/me","$..id","alice"),"unsupported JSON path rejected");
    rejects(()->new PayloadSessionCheck.Config("/me","$.id",""),"blank expected identity rejected");
    byte[] absolute="GET http://app.test/?q=base HTTP/1.1\r\nHost: app.test\r\nAuthorization: secret\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
    check(!new String(PayloadSessionCheck.request(absolute,"/me",false),StandardCharsets.ISO_8859_1).contains("GET http:"),"absolute-form request line is not copied as a header");
    check(!checkConfig.toString().contains("alice"),"configuration string does not disclose identity");
    AtomicInteger mode=new AtomicInteger(),payloadRequests=new AtomicInteger(),identityRequests=new AtomicInteger();
    List<String> paths=Collections.synchronizedList(new ArrayList<>());
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{String path=exchange.getRequestURI().getPath();paths.add(path);boolean authenticated="fixture-secret".equals(exchange.getRequestHeaders().getFirst("X-Session"));int status=200;String body="[]";
      if(path.equals("/me")){
        identityRequests.incrementAndGet();
        if(mode.get()==1)body="{\"id\":\"alice\"}";
        else if(mode.get()==6&&!authenticated){status=401;body="{\"id\":\"alice\"}";}
        else if(mode.get()==2||mode.get()==3&&payloadRequests.get()>2){status=401;body="{\"error\":\"expired\"}";}
        else if(!authenticated){status=mode.get()==4?500:401;body="{\"error\":\"denied\"}";}
        else if(mode.get()==5)body="{\"id\":\"alice\",\"id\":\"bob\"}";
        else body="{\"id\":\"alice\"}";
      }else {payloadRequests.incrementAndGet();String query=Objects.toString(exchange.getRequestURI().getRawQuery(),"");if(query.contains("%27")){status=401;body="{\"error\":\"Invalid token\"}";}}
      byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Connection","close");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });server.start();int port=server.getAddress().getPort();
    String template="GET /search?q="+Engine.MARK+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nX-Session: fixture-secret\r\nAccept: application/json\r\nConnection: close\r\n\r\n";
    Engine.Transport tx=req->http(port,req);
    try{
      for(boolean adaptive:List.of(false,true)){
        paths.clear();payloadRequests.set(0);identityRequests.set(0);List<Engine.Row> rows=new ArrayList<>();
        var config=new Engine.Config(Engine.Profile.SQLI,Engine.Encoding.URL,template,"base",List.of("'","later"),0,20,"");
        var result=PayloadSessionCheck.run(config,tx,new AtomicBoolean(),rows::add,checkConfig,adaptive,Adaptive.Options.basic());
        check(result.stopped()&&result.requests()==6&&paths.size()==6,"both modes count pre/negative/post identity controls");
        check(payloadRequests.get()==3&&identityRequests.get()==3,"401 never resumes remaining payload despite valid identity");
        check(result.reason().contains("세션 유효")&&result.reason().contains("업무 오류는 미확정"),"valid identity separates ambiguous endpoint denial from expiration");
        check(rows.stream().anyMatch(r->r.verdict().equals("세션 유효 · 대상 응답 보류")),"identity evidence is visible as a separate result");
        check(rows.stream().noneMatch(r->r.evidence().contains("fixture-secret")),"identity explanations do not leak auth values");
      }
      var config=new Engine.Config(Engine.Profile.SQLI,Engine.Encoding.URL,template,"base",List.of("'","later"),0,20,"");
      paths.clear();payloadRequests.set(0);identityRequests.set(0);var unconfigured=Engine.run(config,tx,new AtomicBoolean(),r->{});
      check(unconfigured.requests()==3&&identityRequests.get()==0,"default engine performs no unconfigured identity requests");
      for(int testMode:List.of(1,2,4,5,6)){
        mode.set(testMode);paths.clear();payloadRequests.set(0);identityRequests.set(0);
        var result=PayloadSessionCheck.run(config,tx,new AtomicBoolean(),r->{},checkConfig,false,Adaptive.Options.basic());
        check(result.stopped()&&payloadRequests.get()==0,"public, expired, error or malformed identity control blocks payloads: "+testMode);
      }
      mode.set(3);paths.clear();payloadRequests.set(0);var changed=PayloadSessionCheck.run(config,tx,new AtomicBoolean(),r->{},checkConfig,false,Adaptive.Options.basic());
      check(changed.reason().contains("세션 변화/만료")&&!changed.reason().contains("세션 유효"),"post-denial identity loss stays inconclusive");
      mode.set(0);
      for(int budget:List.of(1,2,5)){
        paths.clear();payloadRequests.set(0);var limited=new Engine.Config(config.profile(),config.encoding(),config.template(),config.baseline(),config.payloads(),0,budget,"");
        var result=PayloadSessionCheck.run(limited,tx,new AtomicBoolean(),r->{},checkConfig,false,Adaptive.Options.basic());
        check(result.requests()==budget&&paths.size()==budget&&result.budgetReached(),"identity controls honor total request budget: "+budget);
        if(budget==5)check(result.reason().contains("신원 재확인 예산 없음"),"lack of post-check budget is explicit");
      }
      paths.clear();payloadRequests.set(0);AtomicBoolean stop=new AtomicBoolean();var stopped=PayloadSessionCheck.run(config,tx,stop,r->{if(r.response()!=null&&r.response().status()==401&&r.index()>0)stop.set(true);},checkConfig,false,Adaptive.Options.basic());
      check(stopped.requests()==5&&paths.size()==5&&stopped.reason().contains("취소"),"user stop prevents diagnostic followup");
    }finally{server.stop(0);}

    String jsonHeader="Content-Type: application/json";
    record Unreadable(String name,String headers,String body) {}
    List<Unreadable> unreadable=List.of(
      new Unreadable("identity-parser size limit",jsonHeader,"{\"id\":\"alice\",\"padding\":\""+"x".repeat(129*1024)+"\"}"),
      new Unreadable("malformed JSON",jsonHeader,"{\"id\":\"bob\","),
      new Unreadable("duplicate JSON keys",jsonHeader,"{\"id\":\"bob\",\"id\":\"alice\"}"),
      new Unreadable("unsupported encoding",jsonHeader+"\r\nContent-Encoding: br","{\"id\":\"alice\"}"),
      new Unreadable("truncated engine response",jsonHeader,"{\"id\":\"alice\",\"padding\":\""+"x".repeat(Engine.LIMIT+10)+"\"}"),
      new Unreadable("JSON node limit",jsonHeader,"{\"id\":\"alice\",\"values\":["+"0,".repeat(6000)+"0]}"),
      new Unreadable("unexpected identity shape",jsonHeader,"{\"id\":{\"value\":\"alice\"}}"),
      new Unreadable("non-JSON denial","Content-Type: text/html","<h1>Unauthorized</h1>"));
    var guarded=new Engine.Config(Engine.Profile.SQLI,Engine.Encoding.URL,template,"base",List.of("'","later"),0,20,"");
    for(boolean adaptive:List.of(false,true)){
      for(Unreadable fixture:unreadable){
        AtomicInteger sent=new AtomicInteger();List<Engine.Row> rows=new ArrayList<>();
        var result=PayloadSessionCheck.run(guarded,req->sent.incrementAndGet()==1?response(200,jsonHeader,"{\"id\":\"alice\"}"):response(401,fixture.headers(),fixture.body()),new AtomicBoolean(),rows::add,checkConfig,adaptive,Adaptive.Options.basic());
        check(sent.get()==2&&result.requests()==2&&result.stopped(),"unreadable anonymous401 never permits a baseline or payload: "+adaptive+" / "+fixture.name());
        check(result.reason().contains("대조 불완전")&&rows.get(1).evidence().contains("신원 미확인"),"unreadable identity is distinguished from a proven difference: "+fixture.name());
      }
      List<Long> starts=new ArrayList<>();AtomicInteger sent=new AtomicInteger();
      var paced=new Engine.Config(guarded.profile(),guarded.encoding(),guarded.template(),guarded.baseline(),guarded.payloads(),60,20,"");
      var result=PayloadSessionCheck.run(paced,req->{starts.add(System.nanoTime());int n=sent.incrementAndGet();return response(n==2||n==5?401:200,jsonHeader,n==1||n==6?"{\"id\":\"alice\"}":"{}");},new AtomicBoolean(),r->{},checkConfig,adaptive,Adaptive.Options.basic());
      check(result.requests()==6&&result.reason().contains("세션 유효"),"paced run includes preflight, baseline, payload and postflight: "+adaptive);
      for(int n=1;n<starts.size();n++)check(starts.get(n)-starts.get(n-1)>=59_000_000L,"configured interval applies at every transport boundary including handoff: "+adaptive+" / "+n);
      AtomicInteger stoppedSent=new AtomicInteger();AtomicBoolean stop=new AtomicBoolean();
      var stopped=PayloadSessionCheck.run(guarded,req->stoppedSent.incrementAndGet()==1?response(200,jsonHeader,"{\"id\":\"alice\"}"):response(401,jsonHeader,"{}"),stop,r->{if(r.index()==-11)stop.set(true);},checkConfig,adaptive,Adaptive.Options.basic());
      check(stopped.requests()==2&&stoppedSent.get()==2&&stopped.reason().contains("중지"),"stop during identity-to-engine handoff never sends a baseline: "+adaptive);
    }
    check(PayloadSessionCheck.identity(checkConfig,Engine.parse(response(401,jsonHeader,"{\"id\":\"alice\"}")))==PayloadSessionCheck.Identity.MATCH&&!PayloadSessionCheck.negativeSuitable(checkConfig,Engine.parse(response(401,jsonHeader,"{\"id\":\"alice\"}"))),"401 with expected identity is a match and an unsuitable negative control");
    check(PayloadSessionCheck.negativeSuitable(checkConfig,Engine.parse(response(401,jsonHeader,"{\"error\":\"denied\"}")))&&PayloadSessionCheck.negativeSuitable(checkConfig,Engine.parse(response(200,jsonHeader,"{\"id\":\"guest\"}"))),"complete denial and different guest identity remain usable controls");

    List<Engine.Row> blockedRows=new ArrayList<>();
    var blockedConfig=new Engine.Config(Engine.Profile.SQLI,Engine.Encoding.URL,"GET /?q="+Engine.MARK+" HTTP/1.1\r\nHost: app.test\r\n\r\n","base",List.of("'"),0,5,"");
    var blocked=Adaptive.run(blockedConfig,r->"HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\n\r\n{}".getBytes(StandardCharsets.UTF_8),new AtomicBoolean(),blockedRows::add);
    check(blocked.requests()==1&&blockedRows.get(0).verdict().equals("인증/인가 응답"),"adaptive401 baseline is not labeled an ordinary baseline");

    String large="x".repeat(70000)+"org.postgresql.util.PSQLException: ERROR";
    var missing=EnvironmentContent.inspect("https://app.test/no",404,"Content-Type: text/html",large,"");
    check(missing.kind().equals("MISSING")&&missing.reason().contains("전체 검사")&&missing.reason().contains("DB 고유 오류"),"large404 status and error-body evidence are separated");
    String huge="x".repeat(EnvironmentContent.MAX+100)+"SQLITE_ERROR: near SELECT";
    var partial=EnvironmentContent.inspect("https://app.test/no",404,"",huge,"");
    check(partial.kind().equals("MISSING")&&partial.reason().contains("중간")&&partial.reason().contains("미검사")&&partial.reason().contains("DB 고유 오류"),"oversized404 tail hints and exact partial range retained");
    check(EnvironmentContent.inspect("https://app.test/private",403,"",huge,"").kind().equals("DENIED"),"large403 never turns into unrelated body-limit status");
    check(EnvironmentContent.inspect("https://app.test/rate",429,"",huge,"").kind().equals("RATE"),"large429 retains rate-limit status");
    check(EnvironmentContent.inspect("https://app.test/spec",200,"","{\"padding\":\""+"x".repeat(70000)+"\",\"openapi\":\"3.0.0\",\"paths\":{}}","").kind().equals("OPENAPI"),"OpenAPI structure beyond64KiB is inspected");
    check(EnvironmentContent.inspect("https://app.test/spec",200,"",huge,"").kind().equals("LIMIT"),"partial large200 cannot claim content classification");
    var empty=EnvironmentControls.sample(200,"","","https://app.test/no",Set.of());
    check(!EnvironmentControls.reference(empty,empty,true,"").stable()&&EnvironmentControls.reference(empty,empty,true,"").note().contains("빈 본문"),"empty reference has specific non-suppressing cause");
    var oversized=EnvironmentControls.sample(200,"",huge,"https://app.test/no",Set.of());
    check(EnvironmentControls.reference(oversized,oversized,true,"").note().contains("본문 비교 한도"),"oversized reference has specific comparison limit cause");
    String prefix="a".repeat(70000);var first=EnvironmentControls.sample(200,"Content-Type: text/plain",prefix+"one","https://app.test/one",Set.of());
    var second=EnvironmentControls.sample(200,"Content-Type: text/plain",prefix+"two","https://app.test/two",Set.of());
    check(!first.signature().equals(second.signature())&&!EnvironmentControls.reference(first,second,true,"").stable(),"same64KiB prefix cannot suppress different tails");
    check(EnvironmentResponse.signature(huge,"https://app.test/no").isEmpty(),"oversized signature never silently truncates for equivalence");
    System.out.println("ResponseHardeningTest: "+checks+" checks passed");
  }
}
