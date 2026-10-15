package qa;

import burp.*;
import burp.api.montoya.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;

/** Real Burp host and HTTP, with a temporary profile; no GUI-click claim. */
public final class NativeScopeCheck implements IBurpExtender, BurpExtension {
    final BurpExtender delegate = new BurpExtender();
    static final Path OUT = Path.of(System.getProperty("qa.output"));
    static final List<String> results = new ArrayList<>();
    IBurpExtenderCallbacks callbacks;
    static Object field(Object o,String name)throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static Object call(Object o,String name,Class<?>[] types,Object...args)throws Exception {Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);results.add("PASS "+text);System.out.println("NATIVE_PASS "+text);}
    static void edt(Runnable action)throws Exception {SwingUtilities.invokeAndWait(action);}
    static int rows(Object wb)throws Exception {int[] n={0};edt(()->{try{n[0]=((List<?>)field(wb,"traffic")).size();}catch(Exception e){throw new RuntimeException(e);}});return n[0];}
    static void awaitRows(Object wb,int expected)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){if(rows(wb)==expected)return;Thread.sleep(25);}throw new AssertionError("expected "+expected+" rows, got "+rows(wb));}
    static List<?> rules(Object scope)throws Exception {return (List<?>)call(scope,"rules",new Class<?>[0]);}
    static void awaitRule(Object scope,String url)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){if(rules(scope).stream().anyMatch(r->r.toString().contains(url)))return;Thread.sleep(25);}throw new AssertionError("scope not synchronized: "+url+"; "+rules(scope));}
    static void mode(Object wb,String name)throws Exception {edt(()->{try{JButton b=(JButton)field(wb,"captureButton");for(int i=0;i<3&&!b.getText().equals(name);i++)b.doClick(0);if(!b.getText().equals(name))throw new AssertionError(name);}catch(Exception e){throw new RuntimeException(e);}});}
    static void enable(Object scope,Object rule,boolean value)throws Exception {call(scope,"setEnabled",new Class<?>[]{rule.getClass(),boolean.class},rule,value);}
    public void registerExtenderCallbacks(IBurpExtenderCallbacks c){callbacks=c;delegate.registerExtenderCallbacks(c);}
    public void initialize(MontoyaApi api){try{api.scope().includeInScope("https://preloaded.invalid/");api.siteMap().add(burp.api.montoya.http.message.HttpRequestResponse.httpRequestResponse(HttpRequest.httpRequestFromUrl("https://preloaded.invalid/"),burp.api.montoya.http.message.responses.HttpResponse.httpResponse("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")));delegate.initialize(api);new Thread(()->run(api),"native-scope-check").start();}catch(Throwable e){finish(e);}}
    void run(MontoyaApi api){HttpServer server=null;ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            results.add("BURP "+Arrays.toString(callbacks.getBurpVersion()));
            Object wb=field(delegate,"workbench"),scope=field(wb,"localScope");
            awaitRule(scope,"https://preloaded.invalid/");check(true,"existing native Target Scope with Site Map data is synchronized on extension load");
            byte[] large=new byte[2*1024*1024+17];for(int i=0;i<large.length;i++)large[i]=(byte)(i%251);
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(pool);
            server.createContext("/",x->{byte[] body=x.getRequestURI().getPath().endsWith("large")?large:new byte[]{79,75};x.getResponseHeaders().set("Content-Type","application/octet-stream");x.sendResponseHeaders(200,body.length);x.getResponseBody().write(body);x.close();});server.start();
            String origin="http://127.0.0.1:"+server.getAddress().getPort(),allowed=origin+"/allowed/";
            api.siteMap().add(burp.api.montoya.http.message.HttpRequestResponse.httpRequestResponse(HttpRequest.httpRequestFromUrl(allowed+"seen-before-add"),burp.api.montoya.http.message.responses.HttpResponse.httpResponse("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")));
            api.scope().includeInScope(allowed);awaitRule(scope,allowed);
            check(true,"native includeInScope change appears without observing a target request");
            Files.writeString(OUT.resolveSibling("target-scope.json"),api.burpSuite().exportProjectOptionsAsJson("target.scope"));
            api.http().sendRequest(HttpRequest.httpRequestFromUrl(origin+"/outside/on"));awaitRows(wb,1);check(true,"ON captures a response outside Target Scope");
            mode(wb,"OFF");api.http().sendRequest(HttpRequest.httpRequestFromUrl(allowed+"off"));Thread.sleep(350);check(rows(wb)==1,"OFF prevents new capture and preserves existing rows");
            mode(wb,"SCOPE");api.http().sendRequest(HttpRequest.httpRequestFromUrl(origin+"/outside/scope"));api.http().sendRequest(HttpRequest.httpRequestFromUrl(allowed+"yes"));awaitRows(wb,2);check(true,"SCOPE captures the checked native prefix and rejects outside traffic");
            Object rule=rules(scope).stream().filter(r->r.toString().contains(allowed)).findFirst().orElseThrow();
            enable(scope,rule,false);api.http().sendRequest(HttpRequest.httpRequestFromUrl(allowed+"unchecked"));Thread.sleep(350);check(rows(wb)==2,"unchecking a native Scope rule immediately prevents capture");
            api.scope().includeInScope(origin+"/another/");Thread.sleep(250);check(!(boolean)call(scope,"enabled",new Class<?>[]{rule.getClass()},rule),"unchecked state survives a native Scope change");
            enable(scope,rule,true);api.http().sendRequest(HttpRequest.httpRequestFromUrl(allowed+"checked"));awaitRows(wb,3);check(true,"rechecking the native rule resumes capture");
            api.scope().excludeFromScope(allowed+"excluded/");Thread.sleep(200);api.http().sendRequest(HttpRequest.httpRequestFromUrl(allowed+"excluded/no"));Thread.sleep(350);check(rows(wb)==3,"native Target Scope exclusions still prevent capture");
            api.http().sendRequest(HttpRequest.httpRequestFromUrl(allowed+"large"));awaitRows(wb,4);
            Object packet=((List<?>)field(wb,"traffic")).get(3);var response=(burp.api.montoya.http.message.responses.HttpResponse)call(packet,"response",new Class<?>[0]);
            check(Arrays.equals(response.body().getBytes(),large),"native disk reconstruction preserves every byte of a 2 MiB response");
            mode(wb,"ON");for(int i=0;i<601;i++)api.http().sendRequest(HttpRequest.httpRequestFromUrl(origin+"/stress/"+i));awaitRows(wb,605);check(true,"601 additional native requests are retained beyond the former 250-row limit");
            int seconds=Integer.getInteger("qa.soakSeconds",0),extra=0;
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
            while(System.nanoTime()<until){api.http().sendRequest(HttpRequest.httpRequestFromUrl(origin+"/soak/"+extra++));Thread.sleep(100);}
            if(seconds>0){awaitRows(wb,605+extra);check(true,"bounded "+seconds+" second native soak retains all "+extra+" additional responses");}
            check(((java.util.concurrent.atomic.AtomicInteger)field(wb,"dropped")).get()==0,"native capture drops no packets");
            check(((java.util.concurrent.atomic.AtomicLong)field(wb,"processingErrors")).get()==0,"native initialization, Scope synchronization and capture report no processing errors");
            Path file=(Path)call(field(wb,"trafficStore"),"file",new Class<?>[0]);api.extension().unload();for(int i=0;i<50&&Files.exists(file);i++)Thread.sleep(40);check(!Files.exists(file),"extension unload deletes its private raw packet store");
            finish(null);
        }catch(Throwable e){finish(e);}finally{if(server!=null)server.stop(0);pool.shutdownNow();}
    }
    static void finish(Throwable error){try{if(error!=null){StringWriter s=new StringWriter();error.printStackTrace(new PrintWriter(s));results.add("FAIL "+s);}Files.writeString(OUT,String.join("\n",results)+"\n"+(error==null?"NATIVE_SCOPE_SUCCESS":"NATIVE_SCOPE_FAILED")+"\n");}catch(Exception e){e.printStackTrace();}System.exit(error==null?0:1);}
}
