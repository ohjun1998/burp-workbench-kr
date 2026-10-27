package kr.featuremapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual loopback transport checks, isolated from Burp/browser user profiles and external hosts. */
public final class InspectionBridgeTest {
  static int checks;
  static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);System.out.println("PASS "+name);}
  public static void main(String[] args)throws Exception{
    var received=new ArrayList<Map<String,Object>>();var commands=new AtomicInteger();
    var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    try(var bridge=new BrowserBridge(received::add,origin->{commands.incrementAndGet();return Map.of("state","run","runId","bridge-fixture","origin",origin,"includes",List.of(origin+"/app"));})){
      String url=bridge.baseUrl()+"/inspection/command?origin="+URLEncoder.encode("https://scope.test",StandardCharsets.UTF_8);
      var unauth=client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofString());
      check(unauth.statusCode()==401&&commands.get()==0,"unpaired callers cannot obtain inspection commands");
      var request=HttpRequest.newBuilder(URI.create(url)).header("X-Feature-Mapper-Token",bridge.token).GET().build();
      var response=client.send(request,HttpResponse.BodyHandlers.ofString());
      check(response.statusCode()==200&&response.body().contains("bridge-fixture")&&commands.get()==1,"authenticated helper obtains its data-only command");
      check(response.headers().firstValue("content-type").orElse("").startsWith("application/json"),"command transport is JSON rather than executable script");
      check(response.headers().firstValue("cache-control").orElse("").equals("no-store"),"command task details are not cached");
      check(response.headers().firstValue("access-control-allow-origin").isEmpty(),"bridge never exposes command replies to arbitrary web origins");
      var post=client.send(HttpRequest.newBuilder(URI.create(url)).header("X-Feature-Mapper-Token",bridge.token).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
      check(post.statusCode()==405,"command endpoint accepts only authenticated GET");
      for(String query:List.of("origin=not-a-url","origin=https%3A%2F%2Fscope.test%2Fapp","origin=https%3A%2F%2Fscope.test&extra=x")){
        var bad=client.send(HttpRequest.newBuilder(URI.create(bridge.baseUrl()+"/inspection/command?"+query)).header("X-Feature-Mapper-Token",bridge.token).GET().build(),HttpResponse.BodyHandlers.ofString());
        check(bad.statusCode()==400,"malformed or path-bearing origin cannot obtain a command");
      }
      var event=Map.of("type","inspection","kind","route","runId","bridge-fixture","url","https://scope.test/app");
      var accepted=client.send(HttpRequest.newBuilder(URI.create(bridge.baseUrl()+"/event")).header("X-Feature-Mapper-Token",bridge.token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JsonWrite.text(event))).build(),HttpResponse.BodyHandlers.ofString());
      check(accepted.statusCode()==200&&received.size()==1&&received.get(0).get("type").equals("inspection"),"explicit browser inspection uses the authenticated existing event transport");
      check(bridge.snapshot().receivedEvents()==1,"inspection transport still reports delivery separately from Scope acceptance");
    }
    System.out.println("InspectionBridgeTest: "+checks+" checks passed (loopback only)");
  }
}
