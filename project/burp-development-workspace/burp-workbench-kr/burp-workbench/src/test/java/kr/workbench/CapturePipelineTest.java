package kr.workbench;

import burp.api.montoya.http.message.responses.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import javax.swing.event.TableModelEvent;
import javax.swing.table.TableRowSorter;

/** Adapter regressions for disk backlog, lazy rows, bounded observer handoff and cleanup. */
public final class CapturePipelineTest extends WorkbenchTest {
    private static void waitFor(java.util.function.BooleanSupplier done)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!done.getAsBoolean()){
            if(System.nanoTime()>deadline)throw new AssertionError("capture observer timed out");
            Thread.sleep(10);
        }
    }
    public static void main(String[] args)throws Exception {
        installTrafficPacketFactory();
        independentReaderPositionsAndConcurrentEviction();
        Path raw,index;
        try(DiskTrafficStore store=new DiskTrafficStore()){
            raw=store.file();index=store.indexFile();
            HttpResponse response=response(200,"A\0B"+"x".repeat(1024));
            for(int i=0;i<800;i++)store.append("12:34:56","PROXY",request("https://capture.test/api/"+i,"GET",true,List.of()),response);
            check(store.size()==800&&store.cachedRows()<=DiskTrafficStore.CACHE_ROWS,"metadata cache stays bounded after 800 original packets");
            var first=store.ref(0);var last=store.ref(799);
            check(first.url().endsWith("/0")&&last.url().endsWith("/799")&&first.source().equals("PROXY"),"evicted oldest metadata remains available from disk index");
            check(first.request().httpService().host().equals("capture.test"),"disk index restores the original HTTP destination");
            check(java.util.Arrays.equals(first.response().toByteArray().getBytes(),response.toByteArray().getBytes()),"evicted original binary packet remains byte exact");
            check(!first.id().equals(last.id())&&first.id().equals(store.ref(0).id()),"packet identifiers remain stable and distinct across metadata eviction");
            AtomicInteger inserts=new AtomicInteger();AtomicReference<CaptureTableModel> modelRef=new AtomicReference<>();
            edt(()->{
                CaptureTableModel model=new CaptureTableModel(row->store.ref(row));modelRef.set(model);
                model.addTableModelListener(e->{if(e.getType()==TableModelEvent.INSERT)inserts.incrementAndGet();});
                model.publish(800);model.publish(800);
                check(inserts.get()==1&&model.getRowCount()==800,"one range insertion publishes the entire burst and unchanged ticks do nothing");
                check(model.getDataVector().isEmpty(),"lazy table retains no duplicate DefaultTableModel row vectors");
                TableRowSorter<CaptureTableModel> sorter=new TableRowSorter<>(model);
                sorter.setRowFilter(RowFilter.regexFilter("/api/(0|799)$",2));
                check(sorter.getViewRowCount()==2,"filter searches old and newest disk metadata without a capture cutoff");
            });
            AtomicReference<Throwable> readFailure=new AtomicReference<>();
            Thread read=new Thread(()->{try{for(int i=0;i<300;i++)if(store.ref(0).response().toByteArray().length()!=response.toByteArray().length())throw new AssertionError();}catch(Throwable t){readFailure.set(t);}});
            read.start();for(int i=0;i<100;i++)store.append(request("https://capture.test/concurrent/"+i,"GET",true,List.of()),response);read.join();
            check(readFailure.get()==null&&store.size()==900,"positional reads preserve old bytes during concurrent appends");
        }
        check(!Files.exists(raw)&&!Files.exists(index),"unload deletes original data and disk index");
        boundedObserverBacklog();
        observerFailureRecovery();
        closeDuringPreparation();
        staleCredentialsAreDiscarded();
        staleEnvironmentIsDiscarded();
        failedPublicationRetriesWithoutNewPackets();
        failedUserFilterAndSortRecover();
        System.out.println("CapturePipelineTest: "+checks+" checks passed");
    }
    private static void independentReaderPositionsAndConcurrentEviction()throws Exception {
        java.nio.channels.FileChannel rawReader,indexReader;
        try(DiskTrafficStore store=new DiskTrafficStore()){
            HttpResponse response=response(200,"binary\0"+"x".repeat(4096));
            byte[] expectedResponse=response.toByteArray().getBytes();
            for(int i=0;i<600;i++)store.append(request("https://capture.test/isolation/"+i,"GET",true,List.of()),response);
            var rawField=DiskTrafficStore.class.getDeclaredField("reader");rawField.setAccessible(true);
            var indexField=DiskTrafficStore.class.getDeclaredField("indexReader");indexField.setAccessible(true);
            var dataField=DiskTrafficStore.class.getDeclaredField("data");dataField.setAccessible(true);
            var pointerField=DiskTrafficStore.class.getDeclaredField("index");pointerField.setAccessible(true);
            rawReader=(java.nio.channels.FileChannel)rawField.get(store);
            indexReader=(java.nio.channels.FileChannel)indexField.get(store);
            var writer=(java.io.RandomAccessFile)dataField.get(store);
            var pointerWriter=(java.io.RandomAccessFile)pointerField.get(store);
            long rawPosition=writer.getFilePointer(),indexPosition=pointerWriter.getFilePointer();
            rawReader.position(0);indexReader.position(0);
            check(writer.getFilePointer()==rawPosition,"raw read position cannot change the append writer's file pointer");
            check(pointerWriter.getFilePointer()==indexPosition,"index read position cannot change the append writer's file pointer");
            CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
            AtomicReference<Throwable> failure=new AtomicReference<>();
            Thread[] readers=new Thread[2];
            for(int n=0;n<readers.length;n++){
                final int salt=n;
                readers[n]=new Thread(()->{ready.countDown();try{
                    start.await();
                    for(int i=0;i<1200;i++){
                        int row=(i*73+salt*281)%600;
                        var ref=store.ref(row);
                        if(!ref.url().equals("https://capture.test/isolation/"+row))throw new AssertionError("metadata changed");
                        if(!java.util.Arrays.equals(ref.response().toByteArray().getBytes(),expectedResponse))throw new AssertionError("original bytes changed");
                    }
                }catch(Throwable error){failure.compareAndSet(null,error);}},"capture-index-reader-"+n);
                readers[n].start();
            }
            if(!ready.await(5,TimeUnit.SECONDS))throw new AssertionError("readers failed to start");
            start.countDown();
            for(int i=600;i<1800;i++)store.append(request("https://capture.test/isolation/"+i,"GET",true,List.of()),response);
            for(Thread thread:readers){thread.join(10000);if(thread.isAlive()){thread.interrupt();throw new AssertionError("concurrent reader timed out");}}
            check(failure.get()==null&&store.size()==1800,"concurrent cache eviction, metadata reads and appends preserve all packets");
            boolean exact=true;
            for(int i=0;i<1800;i++){
                var ref=store.ref(i);
                if(!ref.url().equals("https://capture.test/isolation/"+i)||!java.util.Arrays.equals(ref.response().toByteArray().getBytes(),expectedResponse)){exact=false;break;}
            }
            check(exact,"every old and newly appended metadata record and original response remains byte exact");
        }
        check(!rawReader.isOpen()&&!indexReader.isOpen(),"unload closes both independent read channels before deleting files");
    }
    private static void boundedObserverBacklog()throws Exception {
        AtomicInteger prepared=new AtomicInteger(),applied=new AtomicInteger(),errors=new AtomicInteger();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicBoolean background=new AtomicBoolean(),edtApplied=new AtomicBoolean();
        try(DiskTrafficStore store=new DiskTrafficStore()){
            for(int i=0;i<600;i++)store.append(request("https://capture.test/backlog/"+i,"GET",true,List.of()),response(200,"ok"));
            try(CaptureObservations observer=new CaptureObservations(store,packet->{
                background.set(!SwingUtilities.isEventDispatchThread());prepared.incrementAndGet();entered.countDown();
                try{release.await();}catch(InterruptedException stop){Thread.currentThread().interrupt();return null;}
                return ()->{edtApplied.set(SwingUtilities.isEventDispatchThread());applied.incrementAndGet();};
            },(stage,error)->errors.incrementAndGet())){
                observer.signal();check(entered.await(5,TimeUnit.SECONDS),"observer starts on its worker");
                for(int i=0;i<1000;i++)observer.signal();
                check(prepared.get()==1&&observer.pending()==600,"blocked analysis retains one active packet and keeps 600 pending references on disk");
                edt(()->{CaptureTableModel model=new CaptureTableModel(row->store.ref(row));model.publish(600);check(model.getRowCount()==600,"EDT publishes capture rows while analysis is blocked");});
                release.countDown();waitFor(()->observer.pending()==0);
                check(prepared.get()==600&&applied.get()==600&&background.get()&&edtApplied.get(),"each packet prepares once off EDT and applies compact metadata on EDT");
                observer.close();store.append(request("https://capture.test/closed","GET",true,List.of()),response(200,"new"));observer.signal();
                edt(()->{});check(observer.isClosed()&&applied.get()==600&&errors.get()==0,"closed observer ignores new disk records and leaves no queued UI work");
            }
        }
    }
    private static void observerFailureRecovery()throws Exception {
        AtomicInteger prepared=new AtomicInteger(),applied=new AtomicInteger(),errors=new AtomicInteger();
        try(DiskTrafficStore store=new DiskTrafficStore()){
            for(int i=0;i<3;i++)store.append(request("https://capture.test/recovery/"+i,"GET",true,List.of()),response(200,"ok"));
            try(CaptureObservations observer=new CaptureObservations(store,packet->{
                if(prepared.incrementAndGet()==1)throw new IllegalArgumentException("fixture");
                return applied::incrementAndGet;
            },(stage,error)->errors.incrementAndGet())){
                observer.signal();waitFor(()->observer.pending()==0);
                check(errors.get()==1&&applied.get()==2&&store.size()==3,"observer failure advances analysis cursor without dropping original packets or blocking later work");
            }
        }
    }
    private static void closeDuringPreparation()throws Exception {
        AtomicInteger applied=new AtomicInteger(),errors=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1);
        try(DiskTrafficStore store=new DiskTrafficStore()){
            store.append(request("https://capture.test/closing","GET",true,List.of()),response(200,"ok"));
            CaptureObservations observer=new CaptureObservations(store,packet->{
                entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException stop){Thread.currentThread().interrupt();}
                return applied::incrementAndGet;
            },(stage,error)->errors.incrementAndGet());
            observer.signal();check(entered.await(5,TimeUnit.SECONDS),"close regression enters background preparation");
            edt(observer::close);waitFor(()->!observer.isAlive());edt(()->{});
            check(applied.get()==0&&errors.get()==0,"unload interrupts preparation without late Swing mutations or false errors");
        }
    }
    private static void staleCredentialsAreDiscarded()throws Exception {
        var api=proxy(burp.api.montoya.MontoyaApi.class,(p,m,a)->def(m));
        AtomicReference<AuthComparePanel> box=new AtomicReference<>();edt(()->box.set(new AuthComparePanel(api,List::of)));
        AuthComparePanel panel=box.get();
        try{
            var observation=panel.prepareObservation(request("https://capture.test/session","GET",true,List.of(header("Cookie","sid=before-clear"))),response(200,"ok"));
            edt(()->{panel.clearCandidates();panel.applyObservation(observation);check(panel.credentials.isEmpty(),"candidate clear invalidates already prepared background credentials");});
        }finally{panel.close();}
    }

    private static void staleEnvironmentIsDiscarded()throws Exception {
        var api=proxy(burp.api.montoya.MontoyaApi.class,(p,m,a)->def(m));
        AtomicReference<EnvironmentPanel> box=new AtomicReference<>();edt(()->box.set(new EnvironmentPanel(api)));
        EnvironmentPanel panel=box.get();
        try{
            var q=request("https://capture.test/environment","GET",true,List.of());
            var response=response(200,"<script src='/wp-content/plugins/woocommerce/main.js'></script>");
            var prepared=panel.prepareObservation(q,response);
            check(prepared!=null&&!prepared.site().detections.isEmpty(),"environment observation is prepared with actual fingerprint evidence");
            edt(()->{panel.clearAnalysis();panel.applyObservation(prepared);check(panel.catalog.sites.isEmpty()&&panel.origins.getItemCount()==0,"environment clear rejects an already prepared observation");});
            CountDownLatch decoding=new CountDownLatch(1),continueDecoding=new CountDownLatch(1);
            HttpResponse blocked=proxy(HttpResponse.class,(p,m,a)->{
                if(m.getName().equals("toByteArray")){decoding.countDown();continueDecoding.await();}
                return m.invoke(response,a);
            });
            AtomicReference<EnvironmentPanel.Observation> raced=new AtomicReference<>();
            Thread worker=new Thread(()->raced.set(panel.prepareObservation(q,blocked)));worker.start();
            check(decoding.await(5,TimeUnit.SECONDS),"environment race pauses inside background packet decoding");
            edt(panel::clearAnalysis);continueDecoding.countDown();worker.join();
            edt(()->{panel.applyObservation(raced.get());check(raced.get()!=null&&panel.catalog.sites.isEmpty(),"environment clear also rejects preparation that finishes after the clear");});
            var fresh=panel.prepareObservation(q,response);
            edt(()->{panel.applyObservation(fresh);check(panel.catalog.sites.size()==1&&panel.origins.getItemCount()==1,"a new observation after environment clear is accepted");});
        }finally{edt(panel::close);}
    }

    private static void failedPublicationRetriesWithoutNewPackets()throws Exception {
        try(DiskTrafficStore store=new DiskTrafficStore()){
            store.append(request("https://capture.test/match","GET",true,List.of()),response(200,"one"));
            store.append(request("https://capture.test/other","GET",true,List.of()),response(200,"two"));
            edt(()->{
                AtomicBoolean failOnce=new AtomicBoolean(true);
                CaptureTableModel model=new CaptureTableModel(row->{if(failOnce.getAndSet(false))throw new DiskTrafficStore.StorageException("fixture transient read");return store.ref(row);});
                JTable table=new JTable(model);TableRowSorter<CaptureTableModel> sorter=new TableRowSorter<>(model);table.setRowSorter(sorter);
                sorter.setRowFilter(RowFilter.regexFilter("/match$",2));
                boolean failed=false;try{model.publish(2);}catch(DiskTrafficStore.StorageException expected){failed=true;}
                check(failed&&model.getRowCount()==2&&table.getRowCount()==0,"transient metadata read failure is reproduced after model count publication");
                model.publish(2);
                check(table.getRowCount()==1&&table.getValueAt(0,2).equals("https://capture.test/match"),"next tick resynchronizes failed sorter publication without requiring a new packet");
            });
        }
    }

    private static void failedUserFilterAndSortRecover()throws Exception {
        try(DiskTrafficStore store=new DiskTrafficStore()){
            store.append(request("https://capture.test/match","GET",true,List.of()),response(200,"one"));
            store.append(request("https://capture.test/other","GET",true,List.of()),response(200,"two"));
            edt(()->{
                AtomicBoolean failOnce=new AtomicBoolean();AtomicInteger failures=new AtomicInteger();
                CaptureTableModel model=new CaptureTableModel(row->{if(failOnce.getAndSet(false))throw new DiskTrafficStore.StorageException("fixture transient read");return store.ref(row);});
                JTable table=new JTable(model);CaptureRowSorter sorter=new CaptureRowSorter(model,error->failures.incrementAndGet());table.setRowSorter(sorter);model.publish(2);
                failOnce.set(true);sorter.setRowFilter(RowFilter.regexFilter("/match$",2));model.publish(2);
                check(failures.get()==1&&table.getRowCount()==1,"user filter metadata failure stays inside its boundary and recovers on the next tick");
                sorter.setRowFilter(null);failOnce.set(true);sorter.toggleSortOrder(2);model.publish(2);
                check(failures.get()==2&&table.getRowCount()==2&&table.getValueAt(0,2).equals("https://capture.test/match"),"header-sort metadata failure is reported and recovers without new captured traffic");
            });
        }
    }

}
