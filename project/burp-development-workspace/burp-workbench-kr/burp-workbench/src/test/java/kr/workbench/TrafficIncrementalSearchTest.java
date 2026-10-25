package kr.workbench;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import javax.swing.event.TableModelEvent;

/** Full-reference equivalence, invalidation and measured append work over 100,000 stored rows. */
public final class TrafficIncrementalSearchTest extends WorkbenchTest {
  static TrafficBrowser browser(AtomicReference<DiskTrafficStore> store)throws Exception{
    AtomicReference<TrafficBrowser> out=new AtomicReference<>();
    edt(()->{var model=new CaptureTableModel(row->store.get().ref(row));model.publish(Math.toIntExact(store.get().size()));var table=new JTable(model);table.setRowSorter(new CaptureRowSorter(model,e->{throw e;}));out.set(new TrafficBrowser(table,model,new JTextField(),null,store::get,()->{}));});return out.get();
  }
  static void begin(TrafficBrowser browser)throws Exception{
    var method=TrafficBrowser.class.getDeclaredMethod("begin");method.setAccessible(true);edt(()->{try{method.invoke(browser);}catch(Exception error){throw new IllegalStateException(error);}});
  }
  static void idle(TrafficBrowser browser)throws Exception{
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
    for(;;){AtomicBoolean done=new AtomicBoolean();edt(()->done.set(browser.idle()));if(done.get())return;if(System.nanoTime()>deadline)throw new AssertionError("incremental search timeout: "+browser.feedback.getText());Thread.sleep(10);}
  }
  static void query(TrafficBrowser browser,TrafficQuery query,String text)throws Exception{edt(()->{browser.query=query;browser.search.setText(text);});begin(browser);idle(browser);}
  static void appendRefresh(TrafficBrowser browser)throws Exception{edt(()->{browser.model.publish(Math.toIntExact(browser.store.get().size()));browser.refresh();});idle(browser);}
  static void same(TrafficBrowser.View expected,TrafficBrowser.View actual,String label){
    check(expected.rows().equals(actual.rows())&&expected.groups().equals(actual.groups())&&expected.accounts().equals(actual.accounts())&&expected.count()==actual.count()&&expected.partial()==actual.partial()&&expected.matched()==actual.matched(),label);
  }
  /** Hold the EDT until a one-shot timer has posted its action but has not dispatched it. */
  static void awaitQueuedDebounce(TrafficBrowser browser){
    try{
      var field=TrafficBrowser.class.getDeclaredField("debounce");field.setAccessible(true);var timer=(javax.swing.Timer)field.get(browser);
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
      while(timer.isRunning()&&System.nanoTime()<deadline)java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
      check(!timer.isRunning(),"one-shot timer expires while its search action is queued on the held EDT");
    }catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
  }
  static TrafficQuery settings(int range,boolean group,boolean normalize,boolean important,String account,int minutes){return new TrafficQuery("전체","전체",range,false,false,group,normalize,important,true,account,minutes);}
  public static void main(String[] args)throws Exception{
    installTrafficPacketFactory();
    queuedDebounce();largeAppend();bodyAndGroups();boundedBodies();invalidationsAndTime();cancelAndReplace();
    System.out.println("TrafficIncrementalSearchTest: "+checks+" checks passed");
  }
  static void queuedDebounce()throws Exception{
    try(var disk=new DiskTrafficStore()){
      disk.append(request("https://incremental.test/posts/101","GET",true,List.of()),response(200,"a"));
      disk.append(request("https://incremental.test/posts/102","GET",true,List.of()),response(200,"b"));
      var browser=browser(new AtomicReference<>(disk));
      try{
        var grouped=settings(0,true,true,false,"",0);query(browser,grouped,"");
        edt(()->{
          browser.toggleGroup(0);long token=browser.generation.get();awaitQueuedDebounce(browser);
          check(!browser.idle(),"queued group expansion is pending even after the Swing timer stops running");
          browser.refresh();check(browser.generation.get()==token&&!browser.busy.get(),"capture refresh leaves a queued debounce action to start exactly once");
        });
        idle(browser);
        check(browser.lastMetrics.incremental()&&browser.lastMetrics.examinedRows()==0&&browser.table.getRowCount()==2,"queued group expansion publishes the complete cached projection before idle");
        same(browser.scan(disk,2,grouped,"",List.of(),Set.copyOf(browser.expanded),()->false),browser.view,"queued expansion matches independent full evaluation");
        query(browser,settings(0,false,true,true,"",0),"");
        edt(()->{browser.toggleStar(1);awaitQueuedDebounce(browser);check(!browser.idle(),"queued important-mark invalidation cannot report the old empty result as idle");});
        idle(browser);
        check(!browser.lastMetrics.incremental()&&browser.lastMetrics.examinedRows()==2&&browser.view.rows().equals(BitSet.valueOf(new long[]{2}))&&browser.table.getRowCount()==1,"queued important-mark change publishes the exact original before idle");
        var previous=browser.view;AtomicLong cancelled=new AtomicLong();
        edt(()->{browser.toggleStar(0);awaitQueuedDebounce(browser);browser.cancel.doClick();cancelled.set(browser.generation.get());check(browser.idle(),"cancel clears a queued debounce action immediately");});
        edt(()->check(browser.generation.get()==cancelled.get()&&browser.view==previous&&browser.idle(),"a canceled queued timer cannot restart or replace the published result"));
        AtomicLong cleared=new AtomicLong();
        edt(()->{browser.schedule();awaitQueuedDebounce(browser);browser.clear();cleared.set(browser.generation.get());check(browser.idle(),"clear removes queued search work immediately");});
        edt(()->check(browser.generation.get()==cleared.get()&&browser.view==null&&browser.scanned==-1&&browser.idle(),"a queued timer cannot republish a cleared search"));
      }finally{edt(browser::close);}
    }
  }
  static void largeAppend()throws Exception{
    try(var disk=new DiskTrafficStore()){
      var match=request("https://incremental.test/match","GET",true,List.of());var miss=request("https://incremental.test/other","GET",true,List.of());var reply=response(200,"body");
      for(int row=0;row<100000;row++)disk.append(row%2==0?match:miss,reply);
      var browser=browser(new AtomicReference<>(disk));
      try{
        query(browser,TrafficQuery.defaults(),"/match");
        check(!browser.lastMetrics.incremental()&&browser.lastMetrics.examinedRows()==100000,"initial metadata search evaluates all 100,000 originals");
        long evaluated=browser.evaluatedRows.get();AtomicInteger resets=new AtomicInteger(),oldUpdates=new AtomicInteger();
        edt(()->{browser.model.addTableModelListener(e->{if(e.getType()==TableModelEvent.UPDATE&&e.getLastRow()==Integer.MAX_VALUE)resets.incrementAndGet();if(e.getType()==TableModelEvent.UPDATE&&e.getFirstRow()<100000)oldUpdates.incrementAndGet();});browser.table.setRowSelectionInterval(25,25);});
        int selected=browser.table.convertRowIndexToModel(browser.table.getSelectedRow());
        for(int row=0;row<25;row++)disk.append(row%2==0?match:miss,reply);
        appendRefresh(browser);
        check(browser.lastMetrics.incremental()&&browser.lastMetrics.examinedRows()==25&&browser.lastMetrics.reusedRows()==100000&&browser.evaluatedRows.get()-evaluated==25,"100,000 cached originals plus 25 appended rows perform exactly 25 evaluations");
        check(browser.table.getRowCount()==50013&&resets.get()==0&&oldUpdates.get()==0,"metadata append updates only new rows without a full table reset");
        check(browser.table.convertRowIndexToModel(browser.table.getSelectedRow())==selected,"incremental publication preserves the selected original row");
        var incremental=browser.view;var baseline=browser.scan(disk,100025,TrafficQuery.defaults(),"/match",List.of(),Set.of(),()->false);
        same(baseline,incremental,"100,025-row incremental metadata result equals independent full evaluation");
        System.out.println("TRAFFIC_INCREMENTAL_MEASURE existing=100000 appended=25 evaluated="+browser.lastMetrics.examinedRows()+" reused="+browser.lastMetrics.reusedRows()+" elapsed_ms="+(browser.lastMetrics.elapsedNanos()/1_000_000.0));
      }finally{edt(browser::close);}
    }
  }
  static void bodyAndGroups()throws Exception{
    try(var disk=new DiskTrafficStore()){
      var a=request("https://incremental.test/posts/101?q=1","GET",true,List.of(header("Cookie","SID=A"),header("X-Probe","request-needle")));
      var b=request("https://incremental.test/posts/102?q=2","GET",true,List.of(header("Cookie","SID=A")));
      var other=request("https://incremental.test/posts/103?q=3","GET",true,List.of(header("Cookie","SID=B")));
      disk.append(a,response(200,"body-needle 한글"));disk.append(other,response(403,"other"));
      var browser=browser(new AtomicReference<>(disk));
      try{
        var q=settings(2,false,true,false,"",0);query(browser,q,"body-needle 한글");
        disk.append(b,response(201,"body-needle 한글"));appendRefresh(browser);
        check(browser.lastMetrics.examinedRows()==1&&browser.table.getRowCount()==2,"response-body append reads and evaluates only the added original");
        same(browser.scan(disk,3,q,"body-needle 한글",List.of(),Set.of(),()->false),browser.view,"body search append preserves full-reference rows and partial counters");
        query(browser,settings(1,false,true,false,"",0),"request-needle");check(browser.view.rows().equals(BitSet.valueOf(new long[]{1})),"request-only range keeps response-only matches out");
        var profiles=List.of(new TrafficBrowser.Identity("https://incremental.test:443","Alice",Map.of("COOKIE","SID=A")));
        edt(()->browser.identities=()->profiles);var grouped=settings(0,true,true,false,"",0);query(browser,grouped,"");
        check(browser.view.groups().get(0).members().equals(List.of(0,2))&&"Alice".equals(browser.view.accounts().get(2)),"normalized account header names and session grouping agree on the same credentials");
        edt(()->browser.toggleGroup(0));idle(browser);check(browser.lastMetrics.incremental()&&browser.lastMetrics.examinedRows()==0&&browser.table.getRowCount()==3,"expanding a cached group changes projection without re-evaluating originals");
        var next=request("https://incremental.test/posts/104?q=4","GET",true,List.of(header("cookie","SID=A")));disk.append(next,response(500,"new"));appendRefresh(browser);
        check(browser.lastMetrics.examinedRows()==1&&browser.view.groups().get(0).members().equals(List.of(0,2,3))&&browser.table.getRowCount()==4,"append merges matching session members into an already expanded group");
        same(browser.scan(disk,4,grouped,"",profiles,Set.copyOf(browser.expanded),()->false),browser.view,"group append preserves paths, mixed statuses, account labels and exact original indexes");
        var labels=List.of(new TrafficBrowser.Identity("https://incremental.test:443","Renamed",Map.of("cookie","SID=A")));
        edt(()->{browser.identities=()->labels;browser.refresh();});idle(browser);
        check(!browser.lastMetrics.incremental()&&browser.lastMetrics.examinedRows()==4&&"Renamed".equals(browser.view.accounts().get(0)),"account mapping changes invalidate the cache without an appended packet");
        query(browser,settings(0,true,false,false,"",0),"");check(!browser.lastMetrics.incremental()&&browser.view.groups().size()==4,"group normalization changes perform a full and correct re-evaluation");
        check("미매핑".equals(TrafficBrowser.label("Cookie: SID=A\r\nCookie: SID=B","https://incremental.test/",profiles)),"ambiguous duplicate authentication headers are not assigned a single account label");
        check(!TrafficBrowser.sessionKey("Cookie: SID=A").equals(TrafficBrowser.sessionKey("Cookie: SID=A\r\nCookie: SID=B")),"group identity preserves duplicate authentication values rather than merging ambiguous sessions");
      }finally{edt(browser::close);}
    }
  }
  static void boundedBodies()throws Exception{
    try(var disk=new DiskTrafficStore()){
      var request=request("https://incremental.test/large","GET",true,List.of());
      disk.append(request,response(200,"body-marker "+"x".repeat(TrafficBrowser.SEARCH_BYTES+50)));
      var browser=browser(new AtomicReference<>(disk));
      try{
        var settings=settings(2,false,true,false,"",0);query(browser,settings,"body-marker");
        check(browser.view.partial()==1&&browser.view.matched()==1,"full-packet search preserves its existing 2 MiB partial-inspection count");
        disk.append(request,response(200,"absent"));appendRefresh(browser);
        check(browser.lastMetrics.examinedRows()==1&&browser.view.partial()==1,"append reuses prior partial counts without rereading the oversized response");
        same(browser.scan(disk,2,settings,"body-marker",List.of(),Set.of(),()->false),browser.view,"bounded response-prefix incremental result equals full evaluation");
        byte[] compressed;try(var out=new java.io.ByteArrayOutputStream()){try(var gzip=new java.util.zip.GZIPOutputStream(out)){gzip.write("decoded-only-needle".getBytes(java.nio.charset.StandardCharsets.UTF_8));}compressed=out.toByteArray();}
        byte[] headers="HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Type: text/plain\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);byte[] raw=new byte[headers.length+compressed.length];System.arraycopy(headers,0,raw,0,headers.length);System.arraycopy(compressed,0,raw,headers.length,compressed.length);
        var gzipResponse=proxy(burp.api.montoya.http.message.responses.HttpResponse.class,(p,m,a)->switch(m.getName()){case "statusCode"->(short)200;case "body"->bytes(compressed);case "toByteArray"->bytes(raw);case "toString"->new String(raw,java.nio.charset.StandardCharsets.ISO_8859_1);case "headerValue"->"Content-Encoding".equals(a[0])?"gzip":"text/plain";default->def(m);});
        registerTrafficPacket(request,gzipResponse);query(browser,settings,"decoded-only-needle");disk.append(request,gzipResponse);appendRefresh(browser);
        check(browser.lastMetrics.examinedRows()==1&&browser.view.rows().get(2),"append preserves decoded compressed-response searching within the existing limit");
        same(browser.scan(disk,3,settings,"decoded-only-needle",List.of(),Set.of(),()->false),browser.view,"decoded-body incremental matches equal the independent full reference");
      }finally{edt(browser::close);}
    }
  }
  static void invalidationsAndTime()throws Exception{
    try(var disk=new DiskTrafficStore()){
      long now=5_000_000;AtomicLong time=new AtomicLong(now);
      var a=request("https://incremental.test/a","GET",true,List.of());var b=request("https://incremental.test/b","GET",true,List.of());
      disk.append("","PROXY",a,response(200,"a"),now-300000);disk.append("","PROXY",b,response(403,"b"),now-1000);
      var browser=browser(new AtomicReference<>(disk));
      try{
        browser.clock=time::get;query(browser,settings(0,false,true,false,"",5),"");check(browser.view.rows().cardinality()==2,"time filter includes a row exactly on its five-minute boundary");
        time.incrementAndGet();edt(browser::refresh);idle(browser);
        check(!browser.lastMetrics.incremental()&&browser.view.rows().equals(BitSet.valueOf(new long[]{2})),"time expiry invalidates without a new packet and removes the expired row exactly");
        query(browser,settings(0,false,true,true,"",0),"");check(browser.view.rows().isEmpty(),"important-only initially excludes unmarked originals");
        edt(()->browser.toggleStar(1));idle(browser);check(!browser.lastMetrics.incremental()&&browser.lastMetrics.examinedRows()==2&&browser.view.rows().get(1),"star changes invalidate the prior membership result");
        edt(()->browser.note(1,"재확인"));idle(browser);check(!browser.lastMetrics.incremental()&&browser.lastMetrics.examinedRows()==2&&"재확인".equals(browser.extra(1,10)),"note changes invalidate cached settings and update the exact original row");
        query(browser,new TrafficQuery("GET","4xx",0,false,false,false,true,false,true,"",0),"");check(!browser.lastMetrics.incremental()&&browser.view.rows().equals(BitSet.valueOf(new long[]{2})),"structured condition changes cannot reuse incompatible previous matches");
        var complete=browser.view;AtomicInteger checksUntilCancel=new AtomicInteger();boolean cancelled=false;
        try{browser.scan(disk,2,TrafficQuery.defaults(),"a",List.of(),Set.of(),()->checksUntilCancel.incrementAndGet()>1);}catch(CancellationException expected){cancelled=true;}
        check(cancelled&&browser.view==complete,"cancelled evaluation does not mutate the published immutable result");
      }finally{edt(browser::close);}
    }
  }
  static void cancelAndReplace()throws Exception{
    try(var disk=new DiskTrafficStore();var replacement=new DiskTrafficStore()){
      var request=request("https://incremental.test/keep","GET",true,List.of());var reply=response(200,"keep");disk.append(request,reply);disk.append(request,reply);
      AtomicReference<DiskTrafficStore> current=new AtomicReference<>(disk);var browser=browser(current);
      CountDownLatch release=new CountDownLatch(1);
      try{
        query(browser,TrafficQuery.defaults(),"keep");CountDownLatch entered=new CountDownLatch(1);
        browser.worker.submit(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});check(entered.await(5,TimeUnit.SECONDS),"worker is occupied before an append search is cancelled");
        disk.append(request,reply);edt(()->browser.model.publish(3));begin(browser);edt(()->browser.cancel.doClick());release.countDown();
        edt(()->{browser.refresh();check(browser.scanned==2&&browser.view.count()==2,"cancelled append leaves the previous cache boundary and result intact");});
        edt(browser::schedule);begin(browser);idle(browser);check(browser.lastMetrics.incremental()&&browser.lastMetrics.examinedRows()==1&&browser.view.count()==3,"retry after cancellation starts from the last published boundary");
        var replacementRequest=request("https://incremental.test/replacement","GET",true,List.of());for(int i=0;i<3;i++)replacement.append(replacementRequest,reply);
        edt(()->{current.set(replacement);browser.refresh();});idle(browser);
        check(!browser.lastMetrics.incremental()&&browser.lastMetrics.examinedRows()==3&&browser.table.getRowCount()==0,"a different store with the same size invalidates all previous row identities");
        edt(()->{browser.clear();browser.model.setRowCount(0);browser.model.publish(3);});query(browser,TrafficQuery.defaults(),"replacement");
        check(!browser.lastMetrics.incremental()&&browser.lastMetrics.examinedRows()==3&&browser.table.getRowCount()==3,"clearing and replacing the store never reuses old row identities");
      }finally{release.countDown();edt(browser::close);}
    }
  }
}
