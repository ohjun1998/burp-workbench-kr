package qa;
import burp.*;
import burp.api.montoya.*;
import java.nio.file.*;
import java.net.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;
import com.sun.net.httpserver.HttpServer;
/** Runs the release entry point against a real Burp host, using isolated loopback traffic. */
public final class NativeCaptureCheck implements IBurpExtender,BurpExtension {
    final BurpExtender delegate=new BurpExtender();
    IBurpExtenderCallbacks callbacks;
    static final Path OUT=Path.of(System.getProperty("qa.output"));
    static final List<String> results=new ArrayList<>();
    static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static Object invoke(Object target,String name)throws Exception{Method m=target.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(target);}
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);results.add("PASS "+text);System.out.println("NATIVE_PASS "+text);}
    public void registerExtenderCallbacks(IBurpExtenderCallbacks c){callbacks=c;delegate.registerExtenderCallbacks(c);}
    public void initialize(MontoyaApi api){
        try{delegate.initialize(api);new Thread(()->run(api),"native-capture-check").start();}
        catch(Throwable e){finish(e);}
    }
    static int rows(Object wb)throws Exception{int[] n={0};SwingUtilities.invokeAndWait(()->{try{n[0]=((List<?>)field(wb,"traffic")).size();}catch(Exception e){throw new RuntimeException(e);}});return n[0];}
    static void awaitRows(Object wb,int n)throws Exception{long end=System.nanoTime()+30_000_000_000L;while(System.nanoTime()<end){if(rows(wb)==n)return;Thread.sleep(100);}throw new AssertionError("expected "+n+" rows, got "+rows(wb));}
    void run(MontoyaApi api){
        HttpServer server=null;ExecutorService pool=Executors.newFixedThreadPool(8);
        try{
            results.add("BURP "+Arrays.toString(callbacks.getBurpVersion()));
            Object wb=field(delegate,"workbench");
            api.proxy().disableIntercept();
            // A separate process/data directory owns this proxy and never changes user settings.
            callbacks.loadConfigFromJson("{\"proxy\": {\"request_listeners\": [{\"certificate_mode\": \"per_host\", \"custom_tls_protocols\": [], \"enable_http2\": true, \"listen_mode\": \"loopback_only\", \"listener_port\": 18182, \"running\": true, \"use_custom_tls_protocols\": false}]}}");
            Files.writeString(OUT.resolveSibling("native-proxy-config.json"),callbacks.saveConfigAsJson());
            boolean ready=false;for(int i=0;i<100;i++){try(Socket probe=new Socket()){probe.connect(new InetSocketAddress("127.0.0.1",18182),200);ready=true;break;}catch(IOException waiting){Thread.sleep(100);}}
            check(ready,"isolated native proxy listener starts");
            byte[] large=new byte[2*1024*1024+17];for(int i=0;i<large.length;i++)large[i]=(byte)(i%251);
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(pool);server.createContext("/",x->{byte[] input=x.getRequestBody().readAllBytes();byte[] body=x.getRequestURI().getPath().equals("/large")?large:(x.getRequestURI().getPath().equals("/large-request")?input:new byte[]{79,75});x.getResponseHeaders().set("Content-Type","application/octet-stream");x.sendResponseHeaders(200,body.length);x.getResponseBody().write(body);x.close();});server.start();
            int port=server.getAddress().getPort();
            for(int i=0;i<601;i++){
                URL url=URI.create("http://127.0.0.1:"+port+(i==0?"/large":i==1?"/large-request":"/small/"+i)).toURL();
                HttpURLConnection c=(HttpURLConnection)url.openConnection(new java.net.Proxy(java.net.Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",18182)));
                c.setConnectTimeout(5000);c.setReadTimeout(10000);
                if(i==1){c.setDoOutput(true);c.setRequestMethod("POST");c.setFixedLengthStreamingMode(large.length);try(OutputStream out=c.getOutputStream()){out.write(large);}}
                byte[] received=c.getInputStream().readAllBytes();c.disconnect();
                if(i==1)check(Arrays.equals(received,large),"proxy forwards the complete 2 MiB request body");
                if(i==0)check(Arrays.equals(received,large),"proxy returns the complete 2 MiB binary response");
            }
            awaitRows(wb,601);
            check(api.proxy().history().size()==601,"native HTTP history contains 601 requests");
            check(rows(wb)==601,"Workbench retains all 601 requests without a 250-row cap");
            Object first=((List<?>)field(wb,"traffic")).get(0);
            var response=(burp.api.montoya.http.message.responses.HttpResponse)invoke(first,"response");
            check(Arrays.equals(response.body().getBytes(),large),"native Montoya reconstruction preserves exact large response bytes");
            Object second=((List<?>)field(wb,"traffic")).get(1);
            var request=(burp.api.montoya.http.message.requests.HttpRequest)invoke(second,"request");
            check(Arrays.equals(request.body().getBytes(),large),"native disk reconstruction preserves the complete 2 MiB request body");
            Object store=field(wb,"trafficStore");Path old=(Path)invoke(store,"file");
            check(Files.size(old)>large.length,"raw packets are backed by the private temporary file");
            check(((java.util.concurrent.atomic.AtomicInteger)field(wb,"dropped")).get()==0,"no captured packets were dropped");
            check(((java.util.concurrent.atomic.AtomicLong)field(wb,"processingErrors")).get()==0,"native capture and observers report no processing errors");
            SwingUtilities.invokeAndWait(()->{try{((JButton)field(wb,"clearTraffic")).doClick(0);}catch(Exception e){throw new RuntimeException(e);}});
            check(rows(wb)==0&&!Files.exists(old),"clear removes both native table rows and stored raw packets");
            Path active=(Path)invoke(field(wb,"trafficStore"),"file");
            api.extension().unload();
            for(int i=0;i<30&&Files.exists(active);i++)Thread.sleep(100);
            check(!Files.exists(active),"native extension unload deletes the current temporary store");
            finish(null);
        }catch(Throwable e){finish(e);}finally{if(server!=null)server.stop(0);pool.shutdownNow();}
    }
    static void finish(Throwable error){
        try{if(error!=null){StringWriter w=new StringWriter();error.printStackTrace(new PrintWriter(w));results.add("FAIL "+w);}
            Files.writeString(OUT,String.join("\n",results)+"\n"+(error==null?"NATIVE_CAPTURE_SUCCESS":"NATIVE_CAPTURE_FAILED")+"\n");}
        catch(Exception e){e.printStackTrace();}
        System.exit(error==null?0:1);
    }
}
