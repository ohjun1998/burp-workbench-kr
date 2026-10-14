package kr.featuremapper;

import burp.api.montoya.*;
import com.sun.net.httpserver.*;
import javax.swing.*;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Test-only real Burp entry point. Isolated loopback fixtures and profile. */
public final class NativeMapperCheck implements BurpExtension {
    private final FeatureMapperExtension extension=new FeatureMapperExtension();
    private final List<String> checks=new ArrayList<>();
    private HttpServer lab;
    private MontoyaApi api;
    private int port;
    private final Path output=Path.of(System.getProperty("qa.output"));
    private void check(boolean ok,String text){if(!ok)throw new AssertionError(text);checks.add("PASS "+text);System.out.println("NATIVE_PASS "+text);}
    public void initialize(MontoyaApi api){this.api=api;try{extension.initialize(api);new Thread(this::run,"mapper-native-qa").start();}catch(Throwable e){finish(e);}}
    private void run(){try{
        checks.add("BURP "+api.burpSuite().version());
        api.proxy().disableIntercept();api.burpSuite().importProjectOptionsFromJson("{\"proxy\":{\"request_listeners\":[{\"certificate_mode\":\"per_host\",\"custom_tls_protocols\":[],\"enable_http2\":true,\"listen_mode\":\"loopback_only\",\"listener_port\":18182,\"running\":true,\"use_custom_tls_protocols\":false}]}}");
        boolean listening=false;for(int i=0;i<100;i++){try(Socket s=new Socket()){s.connect(new InetSocketAddress("127.0.0.1",18182),200);listening=true;break;}catch(IOException ignored){Thread.sleep(100);}}
        check(listening,"isolated Burp proxy starts");
        lab=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);port=lab.getAddress().getPort();lab.createContext("/",this::fixture);lab.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;}));lab.start();
        long now=System.currentTimeMillis();request("POST","/api/files","{}","A");request("POST","/api/posts","{\"fileId\":\"f82\"}","A");request("GET","/api/posts/p17","","A");
        Mapper m=extension.mapper;List<Mapper.Packet> ps=m.packetList(null);check(ps.size()==3,"real Burp callbacks retain upload, create and follow-up packets");
        var post=ps.get(1);check(post.parents.equals(List.of(ps.get(0).id))&&ps.get(2).parents.equals(List.of(post.id)),"native response IDs form a complete functional data chain");
        check(m.packetList(post.primary).size()==3,"native post-creation feature includes all three steps");
        request("POST","/graphql","{\"operationName\":\"CreatePost\"}","A");request("POST","/graphql","{\"operationName\":\"GetUser\"}","A");ps=m.packetList(null);check(!ps.get(3).primary.equals(ps.get(4).primary),"native GraphQL operations map to separate features");
        request("GET","/api/posts/p17","","B");ps=m.packetList(null);check(ps.get(5).parents.isEmpty(),"native session B does not inherit session A's data chain");
        byte[] large=new byte[2*1024*1024+17];new Random(13).nextBytes(large);requestBytes("POST","/large",large,"A");var big=m.packetList(null).get(6);check(Arrays.equals(Signals.body(m.raw(big.id)[0]),large)&&Arrays.equals(Signals.body(m.raw(big.id)[1]),large),"native 2 MiB request and response retain exact bytes");
        var event=MapperTest.event(System.currentTimeMillis(),post.method,post.url,post.bodyHash,"native-click","1","게시글 등록");event.put("time",post.time);event.put("page","http://127.0.0.1:"+port+"/board/write");
        var response=HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(URI.create(extension.bridge.baseUrl()+"/event")).header("X-Feature-Mapper-Token",extension.bridge.token).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(JsonWrite.text(event))).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        check(response.statusCode()==200&&m.features.get(post.primary).name.startsWith("게시글 등록"),"native bridge enriches an already captured request with its function label");
        Path project=output.resolveSibling("native-mapper.fmkr");ProjectIO.save(m,project);try(Mapper reloaded=ProjectIO.load(project)){check(reloaded.count()==7&&Arrays.equals(Signals.body(reloaded.raw(big.id)[1]),large),"native captured project reopens with full original bytes");}Files.delete(project);
        if(Boolean.getBoolean("qa.browser")){
            Path info=output.resolveSibling("native-browser-connection.json");Files.writeString(info,JsonWrite.text(Map.of("base",extension.bridge.baseUrl(),"token",extension.bridge.token,"lab","http://127.0.0.1:"+port,"proxy","http://127.0.0.1:18182")));
            Path done=output.resolveSibling("browser-done.flag");long end=System.nanoTime()+120_000_000_000L;while(!Files.exists(done)&&System.nanoTime()<end)Thread.sleep(100);check(Files.exists(done),"browser test signals completion");
            ps=m.packetList(null);check(ps.stream().anyMatch(p->p.browserAction.length()>0&&m.features.get(p.primary).name.startsWith("내 정보 수정")),"real browser button maps its PATCH request inside native Burp");
        }
        for(int i=0;i<300;i++)request("GET","/api/items/"+(1000+i),"","A");check(m.count()>300,"native mapping retains more than 250 rows");
        SwingUtilities.invokeAndWait(()->{});Thread.sleep(650);
        Path file=m.store().path();String bridge=extension.bridge.baseUrl();api.extension().unload();for(int i=0;i<30&&Files.exists(file);i++)Thread.sleep(100);check(!Files.exists(file),"native unload deletes the temporary packet store");
        try(Socket s=new Socket()){s.connect(new InetSocketAddress("127.0.0.1",URI.create(bridge).getPort()),200);throw new AssertionError("bridge remained open");}catch(IOException e){check(true,"native unload closes the browser bridge");}
        finish(null);
    }catch(Throwable e){finish(e);}}
    private void request(String method,String path,String body,String session)throws Exception{requestBytes(method,path,body.getBytes(StandardCharsets.UTF_8),session);}
    private void requestBytes(String method,String path,byte[] body,String session)throws Exception{
        HttpURLConnection c=(HttpURLConnection)URI.create("http://127.0.0.1:"+port+path).toURL().openConnection(new java.net.Proxy(java.net.Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",18182)));c.setRequestMethod(method);c.setRequestProperty("Cookie","sid="+session);c.setRequestProperty("Content-Type","application/json");c.setConnectTimeout(5000);c.setReadTimeout(10000);if(body.length>0){c.setDoOutput(true);c.setFixedLengthStreamingMode(body.length);try(OutputStream out=c.getOutputStream()){out.write(body);}}c.getInputStream().readAllBytes();c.disconnect();
    }
    private void fixture(HttpExchange x)throws IOException{
        byte[] request=x.getRequestBody().readAllBytes();String path=x.getRequestURI().getPath();byte[] body;
        if(path.equals("/board/write")){x.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");x.getResponseHeaders().add("Set-Cookie","sid=BROWSER; Path=/");body=("<!doctype html><meta charset='utf-8'><title>기능 매핑 실습</title><h1>기능 매핑 실습</h1><button id='post'>게시글 등록</button><button id='profile'>내 정보 수정</button><div id='done'></div><script>document.querySelector('#post').onclick=async()=>{const f=await fetch('/api/files',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'}).then(r=>r.json());const p=await fetch('/api/posts',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({fileId:f.fileId})}).then(r=>r.json());await fetch('/api/posts/'+p.postId);document.querySelector('#done').textContent='등록 완료'};document.querySelector('#profile').onclick=async()=>{await fetch('/api/users/123',{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({displayName:'qa'})});document.querySelector('#done').textContent='수정 완료'};</script>").getBytes(StandardCharsets.UTF_8);}
        else{ x.getResponseHeaders().set("Content-Type",path.equals("/large")?"application/octet-stream":"application/json");body=path.equals("/large")?request:(path.equals("/api/files")?"{\"fileId\":\"f82\"}":path.equals("/api/posts")?"{\"postId\":\"p17\"}":"{\"ok\":true}").getBytes(StandardCharsets.UTF_8);}
        x.sendResponseHeaders(200,body.length);try(OutputStream out=x.getResponseBody()){out.write(body);}x.close();
    }
    private void finish(Throwable e){try{if(e!=null){StringWriter text=new StringWriter();e.printStackTrace(new PrintWriter(text));checks.add("FAIL "+text);}Files.writeString(output,String.join("\n",checks)+"\n"+(e==null?"NATIVE_MAPPER_SUCCESS":"NATIVE_MAPPER_FAILED")+"\n");}catch(Exception failure){failure.printStackTrace();}finally{if(lab!=null)lab.stop(0);}System.exit(e==null?0:1);}
}
