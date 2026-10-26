package kr.featuremapper;

import com.sun.net.httpserver.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual-browser fixture exercises production Mapper/Bridge; this is not a Burp instance. */
public final class BrowserFixture {
    public static void main(String[] args)throws Exception{
        Path folder=Path.of(args[0]);Files.createDirectories(folder);List<String> checks=new ArrayList<>();
        try(Mapper mapper=new Mapper();BrowserBridge bridge=new BrowserBridge(mapper::browser)){
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);int port=server.getAddress().getPort();
            ExecutorService threads=Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});server.setExecutor(threads);
            server.createContext("/",x->{try{
                long time=System.currentTimeMillis();byte[] request=x.getRequestBody().readAllBytes();String path=x.getRequestURI().getPath();String mime=path.equals("/board/write")?"text/html; charset=utf-8":"application/json";byte[] body;
                if(path.equals("/board/write")){x.getResponseHeaders().add("Set-Cookie","sid=BROWSER; Path=/");body=("<!doctype html><meta charset='utf-8'><title>기능 매핑 실습</title><h1>기능 매핑 실습</h1><button id='post'>게시글 등록</button><button id='profile'>내 정보 수정</button><button id='profileSecond'>내 정보 재수정</button><div id='done'></div><script>document.querySelector('#post').onclick=async()=>{const f=await fetch('/api/files',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'}).then(r=>r.json());const p=await fetch('/api/posts',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({fileId:f.fileId})}).then(r=>r.json());await fetch('/api/posts/'+p.postId);document.querySelector('#done').textContent='등록 완료'};document.querySelector('#profile').onclick=async()=>{await fetch('/api/users/123',{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({displayName:'첫 번째 이름'})});document.querySelector('#done').textContent='수정 완료'};document.querySelector('#profileSecond').onclick=async()=>{await fetch('/api/users/123',{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({displayName:'두 번째 이름'})});document.querySelector('#done').textContent='재수정 완료'};</script>").getBytes(StandardCharsets.UTF_8);}
                else body=(path.equals("/api/files")?"{\"fileId\":\"f82\"}":path.equals("/api/posts")?"{\"postId\":\"p17\"}":"{\"ok\":true}").getBytes(StandardCharsets.UTF_8);
                Map<String,String> headers=new LinkedHashMap<>();StringBuilder raw=new StringBuilder(x.getRequestMethod()+" "+x.getRequestURI()+" HTTP/1.1\r\n");x.getRequestHeaders().forEach((k,v)->{headers.put(k.toLowerCase(Locale.ROOT),String.join("; ",v));raw.append(k).append(": ").append(String.join("; ",v)).append("\r\n");});raw.append("\r\n");
                byte[] rawResponse=MapperTest.join(("HTTP/1.1 200 OK\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(StandardCharsets.UTF_8),body);
                mapper.accept(new Mapper.Input(time,"PROXY",x.getRequestMethod(),"http://127.0.0.1:"+port+x.getRequestURI(),200,MapperTest.join(raw.toString().getBytes(StandardCharsets.UTF_8),request),rawResponse,request,body,headers,Map.of("content-type",mime)));
                x.getResponseHeaders().set("Content-Type",mime);x.sendResponseHeaders(200,body.length);x.getResponseBody().write(body);
            }catch(Exception e){e.printStackTrace();x.sendResponseHeaders(500,0);}finally{x.close();}});server.start();
            try{
                Files.writeString(folder.resolve("native-browser-connection.json"),JsonWrite.text(Map.of("base",bridge.baseUrl(),"token",bridge.token,"lab","http://127.0.0.1:"+port,"proxy","")));
                long end=System.nanoTime()+120_000_000_000L;while(!Files.exists(folder.resolve("browser-done.flag"))&&System.nanoTime()<end)Thread.sleep(100);
                if(!Files.exists(folder.resolve("browser-done.flag")))throw new AssertionError("browser client did not finish");
                Thread.sleep(300);
                List<Mapper.Packet> ps=mapper.packetList(null);
                var patch=ps.stream().filter(p->p.method.equals("PATCH")).findFirst().orElseThrow();
                if(!mapper.features.get(patch.primary).name.startsWith("내 정보 수정"))throw new AssertionError("PATCH button label was not mapped: "+JsonWrite.text(mapper.metadata()));checks.add("PASS actual browser PATCH is mapped to its visible button label");
                var post=ps.stream().filter(p->p.method.equals("POST")&&p.url.endsWith("/api/posts")).findFirst().orElseThrow();
                if(!mapper.features.get(post.primary).name.startsWith("게시글 등록"))throw new AssertionError("post button label was not mapped: "+JsonWrite.text(mapper.metadata()));checks.add("PASS actual browser post is mapped to the registration function");
                if(post.parents.size()!=1)throw new AssertionError("missing upload data dependency");checks.add("PASS browser-generated file ID joins upload and post save");
                if(mapper.packetList(post.primary).size()<3)throw new AssertionError("incomplete registration workflow");checks.add("PASS one automatic function includes upload, save and follow-up read");
                if(patch.run.equals(post.run))throw new AssertionError("two clicks merged");checks.add("PASS separate browser actions produce separate execution cases");
                if(bridge.snapshot().lastAuthenticated()==0||bridge.snapshot().receivedEvents()<4)throw new AssertionError("missing helper transport evidence");checks.add("PASS authenticated helper transport and event delivery recorded separately");
                if(mapper.browserMappedPackets()<4)throw new AssertionError("insufficient mapped browser packets");checks.add("PASS received events have distinct confirmed request correlations");
                if(patch.reasons.stream().noneMatch(s->s.contains("본문 SHA-256 일치"))||post.reasons.stream().noneMatch(s->s.contains("본문 SHA-256 일치")))throw new AssertionError("body hashes not verified");checks.add("PASS actual browser POST and PATCH match method URL time and body SHA-256");
                var patches=ps.stream().filter(p->p.method.equals("PATCH")&&p.url.endsWith("/api/users/123")).toList();
                if(patches.size()!=2||patches.get(0).bodyHash.equals(patches.get(1).bodyHash))throw new AssertionError("distinct UTF-8 PATCH bodies not captured");
                var second=patches.get(1);
                if(!mapper.features.get(second.primary).name.startsWith("내 정보 재수정")||second.run.equals(patch.run))throw new AssertionError("same URL bodies merged browser actions");
                if(second.reasons.stream().noneMatch(s->s.contains("본문 SHA-256 일치")))throw new AssertionError("second PATCH body hash not verified");
                checks.add("PASS repeated same-URL UTF-8 PATCH bodies retain their own digest action and run");
                Files.writeString(folder.resolve("browser-fixture-result.txt"),String.join("\n",checks)+"\nBROWSER_FIXTURE_SUCCESS\n");
                System.out.println(String.join("\n",checks));
            }finally{server.stop(0);threads.shutdownNow();}
        }
    }
}
