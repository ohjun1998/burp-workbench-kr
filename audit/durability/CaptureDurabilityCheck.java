package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ToolSource;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.extension.Extension;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import burp.api.montoya.logging.Logging;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Real production callback, Swing timer and disk writes; the Montoya host is an adapter. */
public final class CaptureDurabilityCheck extends WorkbenchTest {
    static final Map<String,Object> report = new LinkedHashMap<>();
    static final List<String> passed = new ArrayList<>();
    static void require(boolean ok,String name) {
        if (!ok) throw new AssertionError(name);
        passed.add(name);
        System.out.println("PASS " + name);
    }
    static long usedHeap() {
        Runtime r=Runtime.getRuntime(); return r.totalMemory()-r.freeMemory();
    }
    static final class Fixture implements AutoCloseable {
        final WorkbenchExtension ext = new WorkbenchExtension();
        final AtomicReference<ExtensionUnloadingHandler> unload = new AtomicReference<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final ResponseReceivedAction action = proxy(ResponseReceivedAction.class,(p,m,a)->def(m));
        final ToolSource source = proxy(ToolSource.class,(p,m,a)->m.getName().equals("toolType")?ToolType.PROXY:def(m));
        Fixture() throws Exception {
            ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->
                    m.getReturnType()==ResponseReceivedAction.class?action:def(m));
            installTrafficPacketFactory();
            Extension extension=proxy(Extension.class,(p,m,a)->{
                if(m.getName().equals("registerUnloadingHandler")) unload.set((ExtensionUnloadingHandler)a[0]);
                return m.getReturnType().isInterface()?PayloadIntegrationTest.host(m.getReturnType()):def(m);
            });
            Logging logging=proxy(Logging.class,(p,m,a)->{
                if(m.getName().equals("logToError"))errors.add(String.valueOf(a[0]));return def(m);
            });
            String scopeJson=TrafficScopeModesTest.config("https://durability.invalid/","https://disk-full.invalid/");
            var nativeScope=proxy(burp.api.montoya.scope.Scope.class,(p,m,a)->m.getName().equals("isInScope")?BurpScopeConfig.parse(scopeJson).stream().anyMatch(e->e.matches((String)a[0])):def(m));
            var nativeSuite=proxy(burp.api.montoya.burpsuite.BurpSuite.class,(p,m,a)->m.getName().equals("exportProjectOptionsAsJson")?scopeJson:def(m));
            MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->switch(m.getName()){
                case "extension"->extension; case "logging"->logging;
                case "scope"->nativeScope; case "burpSuite"->nativeSuite;
                default->PayloadIntegrationTest.host(m.getReturnType());
            });
            ext.initialize(api); edt(()->{});edt(()->{try{((javax.swing.JButton)get(ext,"captureButton")).doClick(0);}catch(Exception e){throw new RuntimeException(e);}});
        }
        void send(HttpRequest q,HttpResponse r) {
            HttpResponseReceived event=proxy(HttpResponseReceived.class,(p,m,a)->switch(m.getName()){
                case "initiatingRequest"->q; case "toolSource"->source; case "annotations"->null; case "messageId"->101;
                default->HttpResponse.class.getMethod(m.getName(),m.getParameterTypes()).invoke(r,a);
            });
            if(ext.handleHttpResponseReceived(event)!=action)throw new AssertionError("Burp continueWith action lost");
        }
        DiskTrafficStore store()throws Exception{return (DiskTrafficStore)get(ext,"trafficStore");}
        long metric(String name)throws Exception{return ((AtomicLong)get(ext,name)).get();}
        int dropped()throws Exception{return ((AtomicInteger)get(ext,"dropped")).get();}
        int rows()throws Exception {
            AtomicInteger n=new AtomicInteger();edt(()->{try{n.set(((List<?>)get(ext,"traffic")).size());}
                catch(Exception e){throw new RuntimeException(e);}});return n.get();
        }
        DiskTrafficStore.PacketRef ref(int index)throws Exception {
            AtomicReference<DiskTrafficStore.PacketRef> result=new AtomicReference<>();
            edt(()->{try{
                Object row=((List<?>)get(ext,"traffic")).get(index);
                var method=row.getClass().getDeclaredMethod("packet");method.setAccessible(true);
                result.set((DiskTrafficStore.PacketRef)method.invoke(row));
            }catch(Exception e){throw new RuntimeException(e);}});return result.get();
        }
        void drain(int count)throws Exception {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(System.nanoTime()<until){if(rows()==count)return;Thread.sleep(20);}
            throw new AssertionError("UI failed to retain all accepted packets: "+rows()+" / "+count);
        }
        boolean running()throws Exception{return ((javax.swing.Timer)get(ext,"timer")).isRunning();}
        @Override public void close()throws Exception {if(unload.get()!=null)unload.get().extensionUnloaded();edt(()->{});}
    }
    static void exact(DiskTrafficStore.PacketRef ref,HttpRequest q,HttpResponse r) {
        require(Arrays.equals(ref.request().toByteArray().getBytes(),q.toByteArray().getBytes())&&
                Arrays.equals(ref.response().toByteArray().getBytes(),r.toByteArray().getBytes()),
                "retained packet restores exact request and response bytes");
    }
    static void soak(int seconds,int interval)throws Exception {
        int count=0;long start=System.nanoTime(),heap=usedHeap();Path file;
        // Reuse the same synthetic input so the test host's packet registry does not grow per send.
        HttpRequest q=request("https://durability.invalid/packet","GET",false,List.of());
        HttpResponse r=response(200,"A\u0000B"+"x".repeat(4096));
        try(Fixture f=new Fixture()) {
            file=f.store().file();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds),next=start;
            while(System.nanoTime()<until) {
                f.send(q,r);count++;
                if(count%500==0){f.drain(count);System.out.println("SOAK_PROGRESS accepted="+count);}
                next+=TimeUnit.MILLISECONDS.toNanos(interval);
                long remaining=next-System.nanoTime();if(remaining>0)TimeUnit.NANOSECONDS.sleep(remaining);
            }
            f.drain(count);
            report.put("received",f.metric("received"));report.put("drops",f.dropped());
            report.put("processingErrors",f.metric("processingErrors"));
            report.put("errorStages",f.errors.stream().map(s->s.lines().findFirst().orElse("")).distinct().toList());
            require(count>250,"collection exceeds the former 250-row limit");
            require(f.store().size()==count&&f.rows()==count,"every accepted packet remains in disk and UI");
            require(f.metric("received")==count&&f.dropped()==0&&f.metric("processingErrors")==0,
                    "all response callbacks complete with zero drops and processing errors");
            require(f.running(),"real Swing collection timer remains active");
            exact(f.ref(0),q,r);exact(f.ref(count/2),q,r);exact(f.ref(count-1),q,r);
            report.put("acceptedResponses",count);report.put("retainedRows",f.rows());
            report.put("diskBytes",f.store().diskBytes());report.put("drops",f.dropped());
            report.put("processingErrors",f.metric("processingErrors"));
            report.put("heapBeforeBytes",heap);report.put("heapAfterBytes",usedHeap());
        }
        require(!Files.exists(file),"unload deletes the active private traffic store");
        report.put("elapsedSeconds",(System.nanoTime()-start)/1_000_000_000.0);
        report.put("requestedSeconds",seconds);report.put("intervalMillis",interval);
    }
    static void diskFull()throws Exception {
        Path folder=Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        FileStore fs=Files.getFileStore(folder);
        require(fs.type().equals("tmpfs")&&fs.getTotalSpace()<=64L*1024*1024,
                "disk exhaustion is confined to an explicitly selected tmpfs of at most 64 MiB");
        report.put("fileSystemType",fs.type());report.put("fileSystemBytes",fs.getTotalSpace());
        Path reserve=Files.createTempFile(folder,"qa-recovery-reserve-",".bin"),storeFile=null;
        try {
            Files.write(reserve,new byte[4*1024*1024]);
            HttpRequest q=request("https://disk-full.invalid/packet","GET",false,List.of());
            HttpResponse small=response(200,"unchanged\u0000original"),large=response(200,"x".repeat(1024*1024));
            try(Fixture f=new Fixture()) {
                storeFile=f.store().file();f.send(q,small);f.drain(1);
                long beforeBytes=0,beforeCount=0;boolean full=false;String ioMessage="";
                for(int i=0;i<65;i++) {
                    beforeBytes=f.store().diskBytes();beforeCount=f.store().size();f.send(q,large);
                    if(f.dropped()>0){full=true;break;}
                }
                require(full,"real filesystem capacity causes a capture write failure");
                // Probe the same filesystem independently; no injected exception or mocked writer.
                Path probe=folder.resolve("qa-enospc-probe.bin");
                try { Files.write(probe,new byte[2*1024*1024]); }
                catch(IOException e){ioMessage=String.valueOf(e.getMessage());}
                finally { Files.deleteIfExists(probe); }
                require(ioMessage.toLowerCase(Locale.ROOT).contains("no space left"),"OS reports ENOSPC on the isolated filesystem");
                require(f.store().size()==beforeCount&&f.store().diskBytes()==beforeBytes,
                        "failed partial append rolls back file length and packet count");
                f.drain((int)beforeCount);exact(f.ref(0),q,small);
                f.send(q,large);
                require(f.dropped()==2&&f.metric("processingErrors")==2,
                        "repeated write failures count exactly two dropped packets without breaking callbacks");
                require(f.store().size()==beforeCount&&f.store().diskBytes()==beforeBytes,
                        "repeated failures preserve all previously accepted records");
                Files.delete(reserve);f.send(q,large);f.drain((int)beforeCount+1);
                require(f.store().size()==beforeCount+1&&f.dropped()==2&&f.metric("processingErrors")==2,
                        "freeing disk space resumes capture without reloading the extension");
                require(f.metric("received")==beforeCount+3&&f.running(),"received counters and live Swing timer survive disk exhaustion");
                exact(f.ref(0),q,small);exact(f.ref((int)beforeCount),q,large);
                require(f.errors.stream().allMatch(s->!s.contains("unchanged")&&!s.contains("disk-full.invalid")),
                        "host error diagnostics contain no synthetic packet text or target URL");
                report.put("retainedRows",f.rows());report.put("expectedDrops",f.dropped());
                report.put("expectedProcessingErrors",f.metric("processingErrors"));report.put("recovered",true);
            }
            require(!Files.exists(storeFile),"unload removes the traffic file after disk recovery");
        } finally { Files.deleteIfExists(reserve); }
    }
    public static void main(String[] args) {
        int exit=0;Path output=Path.of(System.getProperty("qa.output"));
        report.put("host","Montoya adapter; production callback, Swing timer and disk store");
        report.put("nativeBurp",false);report.put("nativeGuiClicks",false);
        report.put("os",System.getProperty("os.name"));report.put("java",System.getProperty("java.version"));
        String mode=System.getProperty("qa.mode","soak");report.put("mode",mode);
        try {
            if(mode.equals("disk-full"))diskFull();else if(mode.equals("soak"))
                soak(Integer.getInteger("qa.seconds",180),Integer.getInteger("qa.interval",50));
            else throw new IllegalArgumentException("Unknown mode");
            report.put("result","passed");
        }catch(Throwable e){exit=1;report.put("result","failed");report.put("errorClass",e.getClass().getName());e.printStackTrace();}
        report.put("checks",passed.size());report.put("passedChecks",passed);
        try {Files.createDirectories(output.toAbsolutePath().getParent());Files.writeString(output,MetadataExport.json(report)+"\n");}
        catch(Exception e){exit=1;e.printStackTrace();}
        System.exit(exit);
    }
}
