package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ToolSource;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.extension.Extension;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;

/** Real Swing timer recovery against a fake Montoya host; no network or Burp instance. */
public final class TrafficCaptureRecoveryTest extends WorkbenchTest {
    private static final String PRIVATE_TEXT="DO_NOT_LOG_PACKET_TEXT";
    private static final ToolSource SOURCE=proxy(ToolSource.class,(p,m,a)->
        m.getName().equals("toolType")?ToolType.PROXY:def(m));

    private static Object delegate(Object target,Class<?> type,Method method,Object[] args)throws Throwable {
        try{return type.getMethod(method.getName(),method.getParameterTypes()).invoke(target,args);}
        catch(InvocationTargetException ex){throw ex.getCause();}
    }

    private static HttpRequest plain(String path){return request("https://capture.test/"+path,"GET",false,List.of());}
    private static HttpRequest malformed(){return request("https://capture.test/api/profile","GET",true,
        List.of(header("X-Auth-Token",null)));}

    private static HttpResponseReceived event(HttpRequest request,HttpResponse response){
        return event(request,response,new AtomicInteger(),0);
    }
    private static HttpResponseReceived event(HttpRequest request,HttpResponse response,AtomicInteger accesses,int failAt){
        return proxy(HttpResponseReceived.class,(p,m,a)->switch(m.getName()){
            case "initiatingRequest"->{if(accesses.incrementAndGet()==failAt)throw new IllegalStateException(PRIVATE_TEXT);yield request;}
            case "toolSource"->SOURCE;
            case "messageId"->101;
            case "annotations"->null;
            default->delegate(response,HttpResponse.class,m,a);
        });
    }

    private static HttpRequestResponse packet(HttpRequest request,HttpResponse response){
        return proxy(HttpRequestResponse.class,(p,m,a)->switch(m.getName()){
            case "request"->request;
            case "response"->response;
            default->def(m);
        });
    }

    private static final class Fixture implements AutoCloseable {
        final WorkbenchExtension ext=new WorkbenchExtension();
        final List<String> errors=new CopyOnWriteArrayList<>();
        final AtomicBoolean rejectLogging=new AtomicBoolean();
        final AtomicReference<ExtensionUnloadingHandler> unload=new AtomicReference<>();
        final AtomicInteger responseActions=new AtomicInteger(),requestActions=new AtomicInteger();
        final ResponseReceivedAction responseAction=proxy(ResponseReceivedAction.class,(p,m,a)->def(m));
        final RequestToBeSentAction requestAction=proxy(RequestToBeSentAction.class,(p,m,a)->def(m));
        final MontoyaApi api;

        Fixture()throws Exception {
            ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->{
                if(m.getReturnType()==ResponseReceivedAction.class){responseActions.incrementAndGet();return responseAction;}
                if(m.getReturnType()==RequestToBeSentAction.class){requestActions.incrementAndGet();return requestAction;}
                return def(m);
            });
            installTrafficPacketFactory();
            Extension extension=proxy(Extension.class,(p,m,a)->{
                if(m.getName().equals("registerUnloadingHandler"))unload.set((ExtensionUnloadingHandler)a[0]);
                return m.getReturnType().isInterface()?PayloadIntegrationTest.host(m.getReturnType()):def(m);
            });
            Logging logging=proxy(Logging.class,(p,m,a)->{
                if(m.getName().equals("logToError")){
                    errors.add(String.valueOf(a[0]));
                    if(rejectLogging.get())throw new IllegalStateException(PRIVATE_TEXT);
                }
                return def(m);
            });
            String scopeJson=TrafficScopeModesTest.config("https://capture.test/");
            var nativeScope=proxy(burp.api.montoya.scope.Scope.class,(p,m,a)->m.getName().equals("isInScope")?BurpScopeConfig.parse(scopeJson).stream().anyMatch(e->e.matches((String)a[0])):def(m));
            var nativeSuite=proxy(burp.api.montoya.burpsuite.BurpSuite.class,(p,m,a)->m.getName().equals("exportProjectOptionsAsJson")?scopeJson:def(m));
            api=proxy(MontoyaApi.class,(p,m,a)->switch(m.getName()){
                case "extension"->extension;
                case "logging"->logging;
                case "scope"->nativeScope; case "burpSuite"->nativeSuite;
                default->PayloadIntegrationTest.host(m.getReturnType());
            });
            ext.initialize(api);edt(()->{});edt(()->{try{((javax.swing.JButton)get(ext,"captureButton")).doClick(0);}catch(Exception e){throw new RuntimeException(e);}});
        }
        Timer timer()throws Exception{return (Timer)get(ext,"timer");}
        long metric(String name)throws Exception{return ((AtomicLong)get(ext,name)).get();}
        int dropped()throws Exception{return ((AtomicInteger)get(ext,"dropped")).get();}
        @SuppressWarnings("unchecked") Queue<?> queue()throws Exception{return (Queue<?>)get(ext,"incoming");}
        int rows()throws Exception {
            AtomicInteger count=new AtomicInteger();edt(()->{try{count.set(((List<?>)get(ext,"traffic")).size());}
                catch(Exception ex){throw new IllegalStateException(ex);}});return count.get();
        }
        List<String> urls()throws Exception {
            List<String> urls=new ArrayList<>();edt(()->{try{
                DefaultTableModel model=(DefaultTableModel)get(ext,"logModel");
                for(int i=0;i<model.getRowCount();i++)urls.add((String)model.getValueAt(i,2));
            }catch(Exception ex){throw new IllegalStateException(ex);}});return urls;
        }
        void send(HttpRequest request,HttpResponse response){
            check(ext.handleHttpResponseReceived(event(request,response))==responseAction,"response callback returns continueWith");
        }
        void stop()throws Exception{Timer timer=timer();edt(timer::stop);}
        void start()throws Exception{Timer timer=timer();edt(timer::start);}
        void awaitRows(int expected)throws Exception {
            long deadline=System.nanoTime()+10_000_000_000L;
            while(System.nanoTime()<deadline){if(rows()==expected&&((CaptureObservations)get(ext,"captureObservations")).pending()==0)return;Thread.sleep(20);}
            throw new AssertionError("Timer did not retain "+expected+" rows; retained "+rows()+", pending "+queue().size());
        }
        void await(BooleanSupplier condition,String failure)throws Exception {
            long deadline=System.nanoTime()+5_000_000_000L;
            while(System.nanoTime()<deadline){edt(()->{});if(condition.getAsBoolean())return;Thread.sleep(20);}
            throw new AssertionError(failure);
        }
        @Override public void close()throws Exception {if(unload.get()!=null)unload.get().extensionUnloaded();edt(()->{});}
    }

    private static void set(Object object,String name,Object value)throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }

    private static void timerRecoversAfterAuthCandidateFailure()throws Exception {
        try(Fixture fixture=new Fixture()){
            HttpResponse response=response(200,"{}");
            for(int i=0;i<145;i++)fixture.ext.handleHttpResponseReceived(event(plain("normal/"+i),response));
            fixture.awaitRows(145);
            check(fixture.timer().isRunning(),"actual collection timer runs after the first 145 responses");
            AtomicBoolean rejected=new AtomicBoolean();edt(()->{try{
                ((AuthComparePanel)get(fixture.ext,"authCompare")).observe(malformed(),response);
            }catch(RuntimeException expected){rejected.set(true);}catch(Exception ex){throw new IllegalStateException(ex);}});
            check(rejected.get(),"native-scoped invalid credential metadata triggers the real auth candidate observer failure");
            AuthComparePanel auth=(AuthComparePanel)get(fixture.ext,"authCompare");edt(()->auth.setDetection(true));
            HttpRequest bad=malformed();
            HttpRequestToBeSent outgoing=proxy(HttpRequestToBeSent.class,(p,m,a)->switch(m.getName()){
                case "toolSource"->SOURCE;case "messageId"->102;case "annotations"->null;
                default->delegate(bad,HttpRequest.class,m,a);
            });
            check(fixture.ext.handleHttpRequestToBeSent(outgoing)==fixture.requestAction,"independent auth detector failure still returns continueWith");
            fixture.send(bad,response);fixture.awaitRows(146);
            fixture.send(plain("after-malformed"),response);fixture.awaitRows(147);
            check(fixture.urls().contains("https://capture.test/api/profile")&&fixture.urls().get(146).endsWith("after-malformed"),
                "failed auth observation retains its packet and the next timer tick captures a normal packet");
            check(fixture.metric("processingErrors")==1&&fixture.dropped()==0,
                "observer failure counts as processing error without reporting a retained packet as dropped");
            check(fixture.metric("received")==147&&fixture.queue().isEmpty(),"all 147 response callbacks drain through the real timer");
            check(fixture.errors.stream().anyMatch(log->log.contains("독립 인증 탐지")),"observer failure identifies its stage in the host error log");
        }
    }

    private static void badMetadataDoesNotBlockNextPacket()throws Exception {
        try(Fixture fixture=new Fixture()){
            HttpResponse base=response(200,"{}");
            HttpResponse broken=proxy(HttpResponse.class,(p,m,a)->{
                if(m.getName().equals("statusCode"))throw new IllegalStateException(PRIVATE_TEXT);
                return delegate(base,HttpResponse.class,m,a);
            });
            fixture.stop();fixture.send(plain("bad-metadata"),broken);fixture.send(plain("after-bad-metadata"),base);fixture.start();
            fixture.awaitRows(1);
            check(fixture.urls().equals(List.of("https://capture.test/after-bad-metadata")),"bad row metadata excludes only that packet and drains the following packet");
            check(fixture.metric("processingErrors")==1&&fixture.dropped()==1,"metadata failure reports exactly one processing error and dropped packet");
            fixture.send(plain("later-metadata"),base);fixture.awaitRows(2);
            check(fixture.errors.stream().noneMatch(log->log.contains(PRIVATE_TEXT)),"diagnostics omit exception messages and packet text");
        }
    }

    private static final class FailingLabel extends JLabel {
        volatile boolean armed;
        @Override public void setText(String text){if(armed){armed=false;throw new IllegalStateException(PRIVATE_TEXT);}super.setText(text);}
    }

    private static void timerBoundaryAndLoggingRecover()throws Exception {
        try(Fixture fixture=new Fixture()){
            fixture.stop();FailingLabel label=new FailingLabel();label.armed=true;set(fixture.ext,"stats",label);
            fixture.rejectLogging.set(true);
            fixture.send(plain("before-stat-error"),response(200,"{}"));fixture.start();fixture.awaitRows(1);
            AtomicLong errors=(AtomicLong)get(fixture.ext,"processingErrors");
            fixture.await(()->errors.get()>0,"timer's display failure was not caught");
            fixture.send(plain("after-stat-error"),response(200,"{}"));fixture.awaitRows(2);
            check(fixture.timer().isRunning()&&errors.get()==1,"timer display failure and failing host logging cannot disable later timer events");
            check(fixture.dropped()==0,"timer status display failure does not mark retained packets as dropped");
        }
    }

    private static void optionalCallbackStagesCannotAbortBurpActions()throws Exception {
        try(Fixture fixture=new Fixture()){
            HttpRequest request=plain("callback-stages");HttpResponse response=response(200,"{}");
            AtomicInteger requests=new AtomicInteger();
            HttpResponseReceived reconFailure=event(request,response,requests,2);
            check(fixture.ext.handleHttpResponseReceived(reconFailure)==fixture.responseAction,
                "response observer exception still returns Burp continueWith action");
            fixture.awaitRows(1);
            check(requests.get()>=4&&fixture.metric("processingErrors")==1,
                "response observer exception permits later activity and auth callback stages");

            Thread callbackThread=Thread.currentThread();AtomicBoolean activityFault=new AtomicBoolean();
            HttpResponse activityFailure=proxy(HttpResponse.class,(p,m,a)->{
                // Target the activity stage itself; adding another bounded observer must not move the fault.
                if(m.getName().equals("body")&&Thread.currentThread()==callbackThread
                    &&StackWalker.getInstance().walk(frames->frames.anyMatch(f->f.getClassName().equals(ActivityPanel.class.getName())&&f.getMethodName().equals("response")))
                    &&activityFault.compareAndSet(false,true))
                    throw new IllegalStateException(PRIVATE_TEXT);
                return delegate(response,HttpResponse.class,m,a);
            });
            check(fixture.ext.handleHttpResponseReceived(event(request,activityFailure))==fixture.responseAction,
                "activity observer exception still returns Burp continueWith action");
            fixture.awaitRows(2);
            check(fixture.metric("processingErrors")==2&&fixture.errors.stream().anyMatch(log->log.contains("응답 활동 관찰")),
                "synchronous activity failure is isolated and reported separately");

            AtomicInteger tools=new AtomicInteger();
            HttpRequestToBeSent outgoing=proxy(HttpRequestToBeSent.class,(p,m,a)->switch(m.getName()){
                case "toolSource"->{if(tools.incrementAndGet()==1)throw new IllegalStateException(PRIVATE_TEXT);yield SOURCE;}
                case "messageId"->102;
                case "annotations"->null;
                default->delegate(request,HttpRequest.class,m,a);
            });
            check(fixture.ext.handleHttpRequestToBeSent(outgoing)==fixture.requestAction&&tools.get()==2,
                "request activity failure permits auth stage and returns Burp continueWith action");
            check(fixture.metric("processingErrors")==3&&fixture.dropped()==0,"callback observer failures preserve all captured rows");
        }
    }

    private static void manualImportContinuesAfterBadSelection()throws Exception {
        try(Fixture fixture=new Fixture()){
            HttpResponse base=response(200,"{}");
            HttpRequestResponse unavailable=proxy(HttpRequestResponse.class,(p,m,a)->{
                if(m.getName().equals("request"))throw new IllegalStateException(PRIVATE_TEXT);return def(m);
            });
            HttpResponse metadataFailure=proxy(HttpResponse.class,(p,m,a)->{
                if(m.getName().equals("statusCode"))throw new IllegalStateException(PRIVATE_TEXT);
                return delegate(base,HttpResponse.class,m,a);
            });
            List<HttpRequestResponse> picked=List.of(unavailable,packet(plain("manual-bad-metadata"),metadataFailure),
                packet(malformed(),base),packet(plain("manual-good"),base));
            ContextMenuEvent menuEvent=proxy(ContextMenuEvent.class,(p,m,a)->switch(m.getName()){
                case "selectedRequestResponses"->picked;
                case "messageEditorRequestResponse"->Optional.empty();
                default->def(m);
            });
            edt(()->((JMenuItem)((JMenu)fixture.ext.provideMenuItems(menuEvent).get(0)).getMenuComponent(0)).doClick());fixture.awaitRows(2);
            check(fixture.rows()==2&&fixture.urls().get(1).endsWith("manual-good"),
                "one manual import skips inaccessible and invalid packets, retaining the observer-fault packet and later valid packet");
            check(fixture.metric("processingErrors")==2&&fixture.dropped()==2,"manual import reports packet failures without running independent auth detection");
            check(((AuthComparePanel)get(fixture.ext,"authCompare")).credentials.isEmpty(),"History import does not backfill credentials with detection OFF");
            check(fixture.metric("received")==0,"manual imports do not inflate the live response count");
        }
    }

    private static void unlimitedCaptureAndCleanup()throws Exception {
        java.nio.file.Path lastFile;
        try(Fixture fixture=new Fixture()){
            fixture.stop();javax.swing.JButton mode=(javax.swing.JButton)get(fixture.ext,"captureButton");
            HttpRequest outside=request("https://outside.test/counters","GET",false,List.of());HttpResponse small=response(200,"{}");
            edt(()->mode.doClick(0));fixture.send(outside,small);
            check(fixture.queue().isEmpty()&&fixture.dropped()==0,"OFF does not retain or count loss");
            edt(()->mode.doClick(0));fixture.send(outside,small);
            check(fixture.queue().isEmpty(),"SCOPE exclusion is preserved");
            HttpResponse large=response(200,"A\u0000B"+"x".repeat(2*1024*1024));
            fixture.send(plain("large"),large);
            for(int i=0;i<600;i++)fixture.ext.handleHttpResponseReceived(event(plain("burst/"+i),small));
            check(fixture.queue().size()==601,"burst queue accepts more than 250 packets without loss");
            DiskTrafficStore store=(DiskTrafficStore)get(fixture.ext,"trafficStore");
            check(store.size()==601&&store.diskBytes()>2*1024*1024,"complete large packets are stored on disk before UI drains");
            fixture.start();fixture.awaitRows(601);
            check(fixture.urls().get(0).endsWith("large")&&fixture.urls().get(600).endsWith("burst/599"),"oldest packet remains after more than 250 rows");
            Object first=((List<?>)get(fixture.ext,"traffic")).get(0);
            Method packet=first.getClass().getDeclaredMethod("packet");packet.setAccessible(true);
            DiskTrafficStore.PacketRef ref=(DiskTrafficStore.PacketRef)packet.invoke(first);
            check(java.util.Arrays.equals(ref.response().toByteArray().getBytes(),large.toByteArray().getBytes()),"2 MiB binary response round-trips with exact bytes");
            check(fixture.dropped()==0&&fixture.metric("processingErrors")==0,"large packet and burst produce no capture losses");
            java.nio.file.Path previous=store.file();
            edt(()->{try{var clear=WorkbenchExtension.class.getDeclaredMethod("clearStoredTraffic");clear.setAccessible(true);clear.invoke(fixture.ext);((DefaultTableModel)get(fixture.ext,"logModel")).setRowCount(0);}catch(Exception e){throw new RuntimeException(e);}});
            check(!java.nio.file.Files.exists(previous)&&fixture.rows()==0&&fixture.queue().isEmpty(),"clear deletes old packets and pending references");
            fixture.send(plain("after-clear"),small);fixture.awaitRows(1);
            lastFile=((DiskTrafficStore)get(fixture.ext,"trafficStore")).file();
            check(java.nio.file.Files.exists(lastFile),"capture resumes into a new private temporary file");
        }
        check(!java.nio.file.Files.exists(lastFile),"unload removes the active temporary file");
    }

    public static void main(String[] args)throws Exception {
        timerRecoversAfterAuthCandidateFailure();
        badMetadataDoesNotBlockNextPacket();
        timerBoundaryAndLoggingRecover();
        optionalCallbackStagesCannotAbortBurpActions();
        manualImportContinuesAfterBadSelection();
        unlimitedCaptureAndCleanup();
        System.out.println("TrafficCaptureRecoveryTest: "+checks+" checks passed");
    }
}
