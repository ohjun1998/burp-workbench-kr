package kr.workbench;

import burp.api.montoya.*;
import burp.api.montoya.core.*;
import burp.api.montoya.extension.*;
import burp.api.montoya.http.*;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.*;
import burp.api.montoya.http.message.params.*;
import burp.api.montoya.http.message.requests.*;
import burp.api.montoya.http.message.responses.*;
import burp.api.montoya.internal.*;
import burp.api.montoya.logging.*;
import burp.api.montoya.ui.*;
import burp.api.montoya.ui.contextmenu.*;
import burp.api.montoya.ui.editor.*;
import javax.swing.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.awt.Component;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/** Fake Montoya host. Tests do not contact any target or run Burp Suite. */
public class WorkbenchTest {
    interface Call {Object run(Object proxy,Method method,Object[] args) throws Throwable;}
    static int checks;
    private static final Map<String,HttpRequest> packetRequests=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String,HttpResponse> packetResponses=new java.util.concurrent.ConcurrentHashMap<>();
    private static MontoyaObjectFactory trafficFactory;
    /** Restore exact registered test packets when production reads its temporary disk store. */
    static synchronized void installTrafficPacketFactory(){
        if(ObjectFactoryLocator.FACTORY==trafficFactory&&trafficFactory!=null)return;
        MontoyaObjectFactory previous=ObjectFactoryLocator.FACTORY;
        trafficFactory=proxy(MontoyaObjectFactory.class,(p,m,a)->{
            if(m.getName().equals("byteArray")&&a.length==1&&a[0] instanceof byte[] raw)return bytes(raw);
            if(m.getName().equals("httpService")&&a.length==3&&a[0] instanceof String)return proxy(HttpService.class,(sp,sm,sa)->switch(sm.getName()){case "host"->a[0];case "port"->a[1];case "secure"->a[2];default->def(sm);});
            if(m.getName().equals("httpRequest")&&a.length==2&&a[1] instanceof ByteArray raw){HttpRequest q=packetRequests.get(Base64.getEncoder().encodeToString(raw.getBytes()));if(q!=null){Object suppliedService=a[0];if(suppliedService==q.httpService())return q;return proxy(HttpRequest.class,(rp,rm,ra)->rm.getName().equals("httpService")?suppliedService:rm.invoke(q,ra));}}
            if(m.getName().equals("httpResponse")&&a.length==1&&a[0] instanceof ByteArray raw){HttpResponse r=packetResponses.get(Base64.getEncoder().encodeToString(raw.getBytes()));if(r!=null)return r;}
            if(m.getName().equals("httpRequestResponse")&&a.length>=2)return proxy(HttpRequestResponse.class,(pp,mm,aa)->switch(mm.getName()){case "request"->a[0];case "response"->a[1];default->def(mm);});
            return previous==null?def(m):m.invoke(previous,a);
        });
        ObjectFactoryLocator.FACTORY=trafficFactory;
    }
    static void registerTrafficPacket(HttpRequest request,HttpResponse response){
        if(request!=null)packetRequests.put(Base64.getEncoder().encodeToString(request.toByteArray().getBytes()),request);
        if(response!=null)packetResponses.put(Base64.getEncoder().encodeToString(response.toByteArray().getBytes()),response);
        installTrafficPacketFactory();
    }
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,Call c){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->{
        if(m.getName().equals("equals"))return p==a[0];if(m.getName().equals("hashCode"))return System.identityHashCode(p);
        return c.run(p,m,a==null?new Object[0]:a);
    });}
    static Object def(Method m){Class<?> t=m.getReturnType();if(t==boolean.class)return false;if(t==int.class)return 0;if(t==short.class)return (short)0;if(t==long.class)return 0L;return null;}
    static void check(boolean truth,String name){checks++;if(!truth)throw new AssertionError(name);System.out.println("PASS "+name);}
    static void rejects(Runnable r,String name){try{r.run();throw new AssertionError(name);}catch(IllegalArgumentException e){check(true,name);}}
    static ByteArray bytes(byte[] b){return proxy(ByteArray.class,(p,m,a)->switch(m.getName()){case "length"->b.length;case "getBytes"->b.clone();case "getByte"->b[(int)a[0]];case "subArray"->{int from,to;if(a.length==1){Range range=(Range)a[0];from=range.startIndexInclusive();to=range.endIndexExclusive();}else{from=(int)a[0];to=(int)a[1];}Objects.checkFromToIndex(from,to,b.length);yield bytes(Arrays.copyOfRange(b,from,to));}case "toString"->new String(b,StandardCharsets.UTF_8);default->def(m);});}
    static HttpHeader header(String n,String v){return proxy(HttpHeader.class,(p,m,a)->switch(m.getName()){case "name"->n;case "value"->v;case "toString"->n+": "+v;default->def(m);});}
    static HttpRequest request(String url,String method,boolean scope,List<HttpHeader> h){
        java.net.URI u=java.net.URI.create(url);HttpService s=proxy(HttpService.class,(p,m,a)->switch(m.getName()){case "host"->u.getHost();case "port"->u.getPort()<0?443:u.getPort();case "secure"->u.getScheme().equals("https");default->def(m);});
        HttpRequest created=proxy(HttpRequest.class,(p,m,a)->{
            return switch(m.getName()){
                case "body"->bytes(new byte[0]);case "bodyToString"->"";case "url"->url;case "method"->method;case "isInScope"->scope;case "httpService"->s;case "headers"->h;case "headerValue"->h.stream().filter(v->v.name().equalsIgnoreCase(a[0].toString())).map(HttpHeader::value).findFirst().orElse(null);case "parameters"->List.of();
                case "toString"->method+" "+u.getRawPath()+" HTTP/1.1\r\n"+h.stream().map(HttpHeader::toString).reduce("",(x,y)->x+y+"\r\n")+"\r\n";
                case "toByteArray"->bytes((method+" "+u.getRawPath()+(u.getRawQuery()==null?"":"?"+u.getRawQuery())+" HTTP/1.1\r\nHost: "+u.getRawAuthority()+"\r\n"+h.stream().map(HttpHeader::toString).reduce("",(x,y)->x+y+"\r\n")+"\r\n").getBytes(StandardCharsets.UTF_8));
                case "withRemovedHeader"->{List<HttpHeader> n=new ArrayList<>(h);if(a[0] instanceof HttpHeader)n.remove(a[0]);else n.removeIf(x->x.name().equalsIgnoreCase((String)a[0]));yield request(url,method,scope,n);}
                case "withHeader"->{List<HttpHeader> n=new ArrayList<>(h);n.removeIf(x->x.name().equalsIgnoreCase((String)a[0]));n.add(header((String)a[0],(String)a[1]));yield request(url,method,scope,n);}
                default->def(m);
            };
        });
        packetRequests.put(Base64.getEncoder().encodeToString(created.toByteArray().getBytes()),created);return created;
    }
    static HttpResponse response(int code,String body){HttpResponse created=proxy(HttpResponse.class,(p,m,a)->switch(m.getName()){case "statusCode"->(short)code;case "body"->bytes(body.getBytes(StandardCharsets.UTF_8));case "bodyToString"->body;case "toByteArray"->bytes(("HTTP/1.1 "+code+"\r\n\r\n"+body).getBytes(StandardCharsets.UTF_8));case "toString"->"HTTP/1.1 "+code+"\r\n\r\n"+body;default->def(m);});packetResponses.put(Base64.getEncoder().encodeToString(created.toByteArray().getBytes()),created);return created;}
    static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static Object call(Object o,String name,Class<?> type,Object arg)throws Exception{Method m=o.getClass().getDeclaredMethod(name,type);m.setAccessible(true);try{return m.invoke(o,arg);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}}
    static void edt(Runnable r)throws Exception{SwingUtilities.invokeAndWait(r);}
    /** Synthetic UI data only; policy is tested through real callbacks/context menus separately. */
    static void seedTraffic(WorkbenchExtension ext,HttpRequest q,HttpResponse s)throws Exception{
        registerTrafficPacket(q,s);
        edt(()->{try{
            Method store=WorkbenchExtension.class.getDeclaredMethod("storeTraffic",String.class,HttpRequest.class,HttpResponse.class);store.setAccessible(true);
            Object row=store.invoke(ext,"시연 데이터",q,s);
            Method add=WorkbenchExtension.class.getDeclaredMethod("addTraffic",row.getClass());add.setAccessible(true);add.invoke(ext,row);
            ((ResponseReconPanel)get(ext,"responseRecon")).offer(q,s);
            ((ActivityPanel)get(ext,"activity")).observe(q,"시연 데이터");
            ((ActivityPanel)get(ext,"activity")).response(q,s);
            JTable table=(JTable)get(ext,"logTable");if(table.getRowCount()>0)table.setRowSelectionInterval(table.getRowCount()-1,table.getRowCount()-1);
        }catch(Exception e){throw new IllegalStateException(e);}});edt(()->{});
    }
    /** Exercises the actual History context-menu import with the current capture policy. */
    static void importTraffic(WorkbenchExtension ext,HttpRequest q,HttpResponse s)throws Exception{
        registerTrafficPacket(q,s);
        HttpRequestResponse rr=proxy(HttpRequestResponse.class,(p,m,a)->switch(m.getName()){case "request"->q;case "response"->s;default->def(m);});
        ContextMenuEvent event=proxy(ContextMenuEvent.class,(p,m,a)->switch(m.getName()){case "selectedRequestResponses"->List.of(rr);case "messageEditorRequestResponse"->Optional.empty();default->def(m);});
        edt(()->((JMenuItem)((JMenu)ext.provideMenuItems(event).get(0)).getMenuComponent(0)).doClick());edt(()->{});
    }
    static void runAuth(WorkbenchExtension ext)throws Exception{
        AtomicReference<Exception> failure=new AtomicReference<>();edt(()->{try{Method selected=ext.getClass().getDeclaredMethod("selectedTraffic");selected.setAccessible(true);call(ext,"startAuth",List.class,selected.invoke(ext));}catch(Exception e){failure.set(e);}});if(failure.get()!=null)throw failure.get();
    }
    static void waitIdle(WorkbenchExtension ext)throws Exception{
        for(int i=0;i<300;i++){if(!(boolean)get(ext,"busy")){edt(()->{});return;}Thread.sleep(20);}throw new AssertionError("worker timed out");
    }
    public static void main(String[] args)throws Exception{
        String fontPath=System.getProperty("workbench.qaFont");
        if(fontPath!=null){java.awt.Font font=java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,new File(fontPath));java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);var keys=UIManager.getDefaults().keys();while(keys.hasMoreElements()){Object key=keys.nextElement();if(UIManager.get(key) instanceof javax.swing.plaf.FontUIResource)UIManager.put(key,new javax.swing.plaf.FontUIResource(font.deriveFont(14f)));}}
        String ko="한글 + & <test>";
        check(Core.transform("Base64 디코딩",Core.transform("Base64 인코딩",ko)).equals(ko),"UTF-8 Base64 round trip");
        check(Core.transform("URL 디코딩 (form)",Core.transform("URL 인코딩 (form)",ko)).equals(ko),"form URL round trip");
        check(Core.transform("Base64 → Gzip 해제",Core.transform("Gzip → Base64",ko)).equals(ko),"Gzip round trip");
        check(Core.transform("Hex 디코딩",Core.transform("Hex 인코딩",ko)).equals(ko),"Hex round trip");
        String bomb=Base64.getEncoder().encodeToString(compress("a".repeat(Core.TEXT_LIMIT+1)));try{Core.transform("Base64 → Gzip 해제",bomb);throw new AssertionError("gzip limit");}catch(IllegalArgumentException e){check(true,"Gzip expansion cap");}
        rejects(()->Core.headers("Host: evil.test"),"profile cannot change destination");
        rejects(()->Core.headers("Cookie: a\ncookie: b"),"case-insensitive duplicate profile rejected");
        rejects(()->Core.headers("Cookie: abc\rX: bad"),"CR header injection rejected");
        check(Core.csv("=1+1").startsWith("\"'"),"CSV formula injection neutralized");
        check(Core.html("<img src=x>").equals("&lt;img src=x&gt;"),"HTML escaping");
        check(Core.safeUrl("https://test/p?token=secret#key").equals("https://test/p"),"export strips query and fragment");
        check(Core.jsPaths("fetch('/api/v1/users'); let x='/api/v1/users';\n//# sourceMappingURL=main.js.map").equals(List.of("/api/v1/users","[source map] main.js.map")),"JS paths deduplicated and sourcemap extracted");
        String jwt=Core.transform("Base64URL 인코딩","{\"alg\":\"none\"}")+"."+Core.transform("Base64URL 인코딩","{\"sub\":\"1\"}")+".";
        check(Core.jwt(jwt).contains("alg=none")&&Core.jwt(jwt).contains("수행하지 않음"),"JWT does not claim verified signature");
        check(Core.compare(200,new byte[]{1},200,new byte[]{1}).contains("검증 필요"),"identical response is candidate, not confirmed vulnerability");
        HttpRequest original=request("https://example.test/api?token=hidden","GET",true,List.of(header("Cookie","ADMIN"),header("cookie","OTHER"),header("Authorization","Bearer admin"),header("X-Auth-Token","admin"),header("Accept","application/json")));
        HttpRequest replay=Core.sessionRequest(original,Core.headers("Cookie: USER"),Core.removedHeaders("X-Auth-Token"));
        check(replay.headers().stream().filter(h->h.name().equalsIgnoreCase("cookie")).count()==1,"duplicate original cookies removed");
        check(replay.headers().stream().noneMatch(h->h.name().equalsIgnoreCase("authorization")||h.name().equalsIgnoreCase("x-auth-token")),"original privileged credentials removed");
        check(original.headers().size()==5,"original request immutable");

        ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));
        MontoyaApi api=(MontoyaApi)PayloadIntegrationTest.host(MontoyaApi.class);WorkbenchExtension ext=new WorkbenchExtension();ext.initialize(api);
        JTabbedPane tabs=(JTabbedPane)get(ext,"tabs");check(tabs.getTabCount()==10,"eight built-in tools including CVE research and Mapper plus shared Settings and appended dashboard; payload attaches separately");
        check(java.util.stream.IntStream.range(0,tabs.getTabCount()).noneMatch(i->tabs.getTitleAt(i).equals("공통 세션")),"common session tab removed");
        HttpRequest q=request("https://example.test/api","GET",true,List.of(header("Cookie","admin")));HttpResponse reply=response(200,"body");
        seedTraffic(ext,q,reply);check(((List<?>)get(ext,"traffic")).size()==1,"synthetic UI packet loaded");
        for(int i=0;i<260;i++)seedTraffic(ext,q,reply);check(((List<?>)get(ext,"traffic")).size()==261,"all seeded rows survive beyond the old 250-row window");
        for(var h:PayloadIntegrationTest.modernUnloads)h.extensionUnloaded();edt(()->{});
        check(((List<?>)get(ext,"traffic")).isEmpty(),"unload clears traffic");check(((AtomicBoolean)get(ext,"unloaded")).get(),"unload closes extension");
        System.out.println("WorkbenchTest: "+checks+" checks passed");
    }
    static byte[] compress(String s)throws Exception{var b=new java.io.ByteArrayOutputStream();try(var g=new java.util.zip.GZIPOutputStream(b)){g.write(s.getBytes(StandardCharsets.UTF_8));}return b.toByteArray();}

}
