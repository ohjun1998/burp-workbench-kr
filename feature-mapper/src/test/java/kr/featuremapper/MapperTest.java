package kr.featuremapper;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public final class MapperTest {
    static int checks;
    static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);System.out.println("PASS "+text);}
    static Mapper.Input input(long time,String method,String url,String qBody,String sBody,String cookie){return input(time,method,url,qBody.getBytes(StandardCharsets.UTF_8),sBody.getBytes(StandardCharsets.UTF_8),cookie,200,"application/json");}
    static Mapper.Input input(long time,String method,String url,byte[] qBody,byte[] sBody,String cookie,int code,String mime){
        Map<String,String> qHeaders=new LinkedHashMap<>();qHeaders.put("content-type","application/json");if(!cookie.isBlank())qHeaders.put("cookie","sid="+cookie);
        byte[] q=join((method+" "+URI.create(url).getRawPath()+" HTTP/1.1\r\nHost: "+URI.create(url).getHost()+"\r\nContent-Type: application/json\r\n"+(cookie.isBlank()?"":"Cookie: sid="+cookie+"\r\n")+"\r\n").getBytes(StandardCharsets.UTF_8),qBody);
        byte[] s=join(("HTTP/1.1 "+code+" OK\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(StandardCharsets.UTF_8),sBody);
        return new Mapper.Input(time,"PROXY",method,url,code,q,s,qBody,sBody,qHeaders,Map.of("content-type",mime));
    }
    static byte[] join(byte[] a,byte[] b){byte[] r=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,r,a.length,b.length);return r;}
    static Map<String,Object> event(long time,String method,String url,String hash,String action,String tab,String label){Map<String,Object> e=new LinkedHashMap<>();e.put("type","network");e.put("time",time);e.put("method",method);e.put("url",url);e.put("bodyHash",hash);e.put("actionId",action);e.put("tab",tab);e.put("frame","0");e.put("label",label);e.put("page","https://lab.test/board/write");e.put("title","글 작성");return e;}
    static void relations()throws Exception{
        long t=System.currentTimeMillis();try(Mapper m=new Mapper()){
            var upload=m.accept(input(t,"POST","https://lab.test/api/files","{}","{\"fileId\":\"f82\"}","A"));
            var post=m.accept(input(t+200,"POST","https://lab.test/api/posts","{\"fileId\":\"f82\"}","{\"postId\":\"p17\"}","A"));
            var read=m.accept(input(t+400,"GET","https://lab.test/api/posts/p17","","{}","A"));
            check(post.parents.equals(List.of(upload.id)),"response file ID links upload to post creation");
            check(read.parents.equals(List.of(post.id)),"created post ID links follow-up detail request");
            check(m.packetList(post.primary).size()==3,"one creation feature contains prerequisite upload, save and follow-up read");
            check(read.signature.contains("/posts/{id}"),"observed dynamic IDs normalize without deleting original URL");
            check(m.raw(upload.id)[0].length>0&&upload.url.endsWith("files"),"prerequisite retains its original packet and function");
            var other=m.accept(input(t+500,"GET","https://lab.test/api/posts/p17","","{}","B"));
            check(other.parents.isEmpty()&&!other.session.equals(post.session),"cross-account packets cannot inherit another session's ID chain");
            var external=m.accept(input(t+600,"GET","https://other.test/api/posts/p17","","{}","A"));
            check(external.parents.isEmpty(),"cross-origin responses cannot create data dependencies");
            var unrelated=m.accept(input(t+700,"GET","https://lab.test/api/users/p17","","{}","A"));
            check(unrelated.parents.isEmpty(),"matching ID in a different entity path is not a link");
            var patch=m.accept(input(t+800,"PATCH","https://lab.test/api/posts/p17","{}","{}","A"));
            check(!patch.primary.equals(post.primary)&&patch.operation.equals("수정"),"method change creates a distinct functional action");
            var later=m.accept(input(t+40000,"POST","https://lab.test/api/posts","{}","{}","A"));
            check(!later.run.equals(post.run),"same feature after an idle gap starts a fresh execution");
            var shortId=m.accept(input(t+40100,"POST","https://lab.test/api/orders","{}","{\"id\":1}","A"));
            var shortRead=m.accept(input(t+40200,"GET","https://lab.test/api/orders/1","","{}","A"));
            check(shortRead.parents.equals(List.of(shortId.id)),"typed one-digit object IDs still link within the same entity and session");
            var repeat=m.accept(input(t+40100,"POST","https://lab.test/api/posts","{}","{}","A"));
            check(repeat.primary.equals(later.primary)&&repeat.run.equals(later.run),"close repetitions share an execution while preserving both packets");
        }
    }
    static void semantics()throws Exception{
        long t=System.currentTimeMillis();try(Mapper m=new Mapper()){
            var a=m.accept(input(t,"POST","https://lab.test/graphql","{\"operationName\":\"CreatePost\",\"variables\":{}}","{}","A"));
            var b=m.accept(input(t+10,"POST","https://lab.test/graphql","{\"operationName\":\"GetUser\"}","{}","A"));
            check(!a.primary.equals(b.primary),"GraphQL operations separate despite identical method and URL");
            var c=m.accept(input(t+20,"POST","https://lab.test/graphql","{\"query\":\"mutation UpdatePost { updatePost(id:1){id} }\"}","{}","A"));
            check(c.operation.equals("UpdatePost"),"GraphQL named mutation is inferred without operationName");
            var d=m.accept(input(t+30,"POST","https://lab.test/api/action","{\"action\":\"refund\"}","{}","A"));
            var e=m.accept(input(t+40,"POST","https://lab.test/api/action","{\"action\":\"purchase\"}","{}","A"));
            check(!d.primary.equals(e.primary),"body action values remain meaningful mapping signals");
            var password=m.accept(input(t+50,"POST","https://lab.test/api/users","{\"oldPassword\":\"a\",\"newPassword\":\"b\"}","{}","A"));
            check(password.operation.equals("비밀번호 변경"),"password-change fields infer a functional label");
            var asset=m.accept(input(t+60,"GET","https://lab.test/app.js",new byte[0],new byte[]{1,2,3},"A",200,"application/javascript"));
            check(asset.common&&m.raw(asset.id)[1].length>3,"static resource remains available under common requests");
            var doc=m.accept(input(t+70,"GET","https://lab.test/openapi.json","","{\"paths\":{\"/api/orders/{orderId}\":{\"get\":{\"summary\":\"주문 상세 확인\"}}}}","A"));
            var order=m.accept(input(t+80,"GET","https://lab.test/api/orders/1234","","{}","A"));
            check(m.features.get(order.primary).name.equals("주문 상세 확인"),"observed OpenAPI summary supplies a function name");
            var bad=m.accept(input(t+90,"POST","https://lab.test/api/posts","{broken","{broken","A"));
            check(bad.id>doc.id,"invalid JSON does not interrupt raw traffic retention");
            var multi=m.accept(input(t+100,"POST","https://lab.test/graphql","[{\"operationName\":\"A\"},{\"operationName\":\"B\"}]","[]","A"));
            check(multi.operation.equals("batch:A+B"),"GraphQL batch operations retain distinct batch semantics");
            var noAuth=m.accept(input(t+110,"GET","https://lab.test/api/posts","","{}",""));
            check(noAuth.session.equals("미인증/미확인"),"no selected auth material does not invent an account identity");
        }
    }
    static void browserAndCorrections()throws Exception{
        long t=System.currentTimeMillis();try(Mapper m=new Mapper()){
            var i=input(t,"POST","https://lab.test/api/posts","{\"title\":\"one\"}","{}","A");
            m.browser(event(t,"POST",i.url(),Signals.digest(i.requestBody()),"click1","1","게시글 등록"));
            var p=m.accept(i);
            check(m.features.get(p.primary).name.startsWith("게시글 등록"),"early browser event maps a packet to its screen action");
            check(p.browserTab.equals("1")&&p.confidence.equals("중간"),"browser timing evidence identifies context without claiming causal certainty");
            var q=m.accept(input(t+700,"POST","https://lab.test/api/posts","{\"title\":\"two\"}","{}","A"));
            m.browser(event(t+700,"POST",q.url,q.bodyHash,"click2","2","게시글 등록"));
            check(q.primary.equals(p.primary)&&!q.run.equals(p.run),"same feature in another browser tab has a separate execution");
            var ambiguous=m.accept(input(t+800,"POST","https://lab.test/api/posts","{\"title\":\"two\"}","{}","A"));
            check(q.browserAction.isBlank()&&ambiguous.browserAction.isBlank(),"indistinguishable parallel packets retract a tentative browser assignment");
            var response=input(t+1500,"POST","https://lab.test/api/posts","{\"title\":\"three\"}","{}","A");
            var late=m.accept(response);m.browser(event(t+1500,"POST",late.url,late.bodyHash,"click3","3","새 글 저장"));
            check(!late.browserAction.isBlank(),"late bridge events can enrich already-retained packets");
            var feature=m.featureList().stream().filter(f->f.name.contains("게시글 등록")).findFirst().orElseThrow();
            m.move(late.id,feature.id);m.edit(feature.id,"수정한 기능 이름","진행 중","메모");
            var next=m.accept(input(t+5000,"POST","https://lab.test/api/posts","{\"title\":\"four\"}","{}","A"));
            check(next.primary.equals(feature.id)&&next.reasons.stream().anyMatch(x->x.contains("수정 규칙")),"manual correction becomes an automatic project rule");
            check(m.features.get(next.primary).state.equals("진행 중"),"function status persists across newly collected traffic");
            var outside=event(t,"POST","https://other.test/api/posts","","wrong","1","다른 사이트");m.browser(outside);
            check(m.featureList().stream().noneMatch(f->f.name.startsWith("다른 사이트")),"screen context cannot map packets on a different origin");
        }
    }
    static void persistenceAndLargePackets()throws Exception{
        Path project=Files.createTempFile("mapper-qa-",".fmkr"),rawPath;
        byte[] binary=new byte[2*1024*1024+17];new Random(7).nextBytes(binary);
        try(Mapper m=new Mapper()){
            rawPath=m.store().path();long t=System.currentTimeMillis();var i=input(t,"POST","https://lab.test/api/files",binary,binary,"PRIVATE",201,"application/octet-stream");var p=m.accept(i);
            for(int n=0;n<600;n++)m.accept(input(t+n+1,"GET","https://lab.test/api/items/"+(n+100),"","{}","PRIVATE"));
            check(m.count()==601,"capture retains more than the prior 250-row limit");
            check(m.diskBytes()>4*1024*1024&&p.partial,"large wire packets are preserved while analysis has independent bounds");
            check(Arrays.equals(m.raw(p.id)[0],i.request())&&Arrays.equals(m.raw(p.id)[1],i.response()),"2 MiB request and response preserve exact binary bytes");
            m.edit(p.primary,"대용량 업로드","재확인","확인할 내용");ProjectIO.save(m,project);
            try(Mapper loaded=ProjectIO.load(project)){
                check(loaded.count()==601,"project import restores all traffic rows");
                check(Arrays.equals(loaded.raw(p.id)[0],i.request())&&Arrays.equals(loaded.raw(p.id)[1],i.response()),"project import restores exact request and response bytes");
                check(loaded.features.get(p.primary).name.equals("대용량 업로드")&&loaded.features.get(p.primary).state.equals("재확인"),"project restores function labels and review state");
                var same=loaded.accept(input(t+1000,"GET","https://lab.test/api/items/9999","","{}","PRIVATE"));
                check(same.session.equals(p.session),"saved session salt keeps fingerprint grouping stable across reload");
                check(loaded.count()==602,"capture continues after reopening a project");
            }
            Path corrupt=Files.createTempFile("mapper-invalid-",".fmkr");try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(corrupt))){z.putNextEntry(new ZipEntry("../unsafe"));z.write(1);z.closeEntry();}
            try{ProjectIO.load(corrupt);throw new AssertionError("invalid archive accepted");}catch(IOException expected){check(m.count()==601,"invalid import is rejected without changing the active project");}Files.delete(corrupt);
            Map<String,Object> meta=m.metadata();String text=JsonWrite.text(meta);check(!text.contains("PRIVATE"),"session credentials are not stored in mapping metadata");
            check(Files.exists(rawPath),"active raw store exists before unload");
        }finally{Files.deleteIfExists(project);}
        check(!Files.exists(rawPath),"close removes private temporary raw storage");
    }
    static void bridge()throws Exception{
        List<Map<String,Object>> received=Collections.synchronizedList(new ArrayList<>());
        BrowserBridge bridge=new BrowserBridge(received::add);String base=bridge.baseUrl();HttpClient client=HttpClient.newHttpClient();
        try{
            check(URI.create(base).getHost().equals("127.0.0.1"),"bridge binds exclusively to loopback");
            var noAuth=client.send(HttpRequest.newBuilder(URI.create(base+"/health")).GET().build(),HttpResponse.BodyHandlers.ofString());check(noAuth.statusCode()==401,"bridge rejects unpaired callers");
            var health=client.send(HttpRequest.newBuilder(URI.create(base+"/health")).header("X-Feature-Mapper-Token",bridge.token).GET().build(),HttpResponse.BodyHandlers.ofString());check(health.statusCode()==200,"paired health check succeeds");
            var event=event(System.currentTimeMillis(),"GET","https://lab.test/api/posts","","test","1","조회");
            var send=client.send(HttpRequest.newBuilder(URI.create(base+"/event")).header("X-Feature-Mapper-Token",bridge.token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JsonWrite.text(event))).build(),HttpResponse.BodyHandlers.ofString());
            check(send.statusCode()==200&&received.size()==1,"real loopback HTTP bridge delivers browser metadata");
            var missing=client.send(HttpRequest.newBuilder(URI.create(base+"/event")).header("X-Feature-Mapper-Token",bridge.token).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());check(missing.statusCode()==415,"bridge requires JSON content type");
            var invalid=client.send(HttpRequest.newBuilder(URI.create(base+"/event")).header("X-Feature-Mapper-Token",bridge.token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{bad")).build(),HttpResponse.BodyHandlers.ofString());check(invalid.statusCode()==400&&received.size()==1,"malformed events do not reach mapper");
        }finally{bridge.close();}
        try(Socket socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",URI.create(base).getPort()),300);throw new AssertionError("bridge still open");}catch(IOException expected){check(true,"close shuts down the bridge listener");}
    }
    public static void main(String[] args)throws Exception{relations();semantics();browserAndCorrections();persistenceAndLargePackets();bridge();System.out.println("MapperTest: "+checks+" checks passed");}
}
