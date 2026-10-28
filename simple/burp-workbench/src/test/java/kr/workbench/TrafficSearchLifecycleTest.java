package kr.workbench;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.swing.Timer;

/** Exercises the TimerQueue/EDT gap directly instead of relying on OS timing. */
public final class TrafficSearchLifecycleTest extends WorkbenchTest {
  static Timer timer(TrafficBrowser browser)throws Exception{
    Field field=TrafficBrowser.class.getDeclaredField("debounce");field.setAccessible(true);return (Timer)field.get(browser);
  }
  // Run on the EDT: hold the callback until the one-shot timer has left its
  // scheduling queue. The callback is now posted, but has not run yet.
  static void posted(Timer timer){
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
    while(timer.isRunning()){
      if(System.nanoTime()>deadline)throw new AssertionError("TimerQueue did not post the debounce action");
      LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(100));
    }
  }
  public static void main(String[] args)throws Exception{
    installTrafficPacketFactory();
    try(var disk=new DiskTrafficStore()){
      disk.append(request("https://lifecycle.test/posts/101","GET",true,List.of()),response(200,"first"));
      disk.append(request("https://lifecycle.test/posts/102","GET",true,List.of()),response(200,"second"));
      var browser=TrafficIncrementalSearchTest.browser(new AtomicReference<>(disk));
      Timer timer=timer(browser);
      try{
        edt(()->timer.setInitialDelay(1));
        var grouped=TrafficIncrementalSearchTest.settings(0,true,true,false,"",0);
        TrafficIncrementalSearchTest.query(browser,grouped,"");
        check(browser.table.getRowCount()==1,"initial group is collapsed");
        edt(()->{
          browser.toggleGroup(0);posted(timer);
          check(!browser.idle(),"posted group expansion remains pending until its EDT action runs");
          long generation=browser.generation.get();browser.refresh();
          check(browser.generation.get()==generation,"capture refresh cannot replace an already-posted debounce action");
        });
        TrafficIncrementalSearchTest.idle(browser);
        check(browser.table.getRowCount()==2&&browser.lastMetrics.examinedRows()==0,"group expansion publishes every original without a new scan");
        TrafficIncrementalSearchTest.query(browser,TrafficIncrementalSearchTest.settings(0,false,true,true,"",0),"");
        edt(()->{
          browser.toggleStar(1);posted(timer);
          check(!browser.idle(),"posted star-filter update cannot be mistaken for completed work");
        });
        TrafficIncrementalSearchTest.idle(browser);
        check(browser.table.getRowCount()==1&&browser.table.convertRowIndexToModel(0)==1,"star filtering publishes the exact marked original");
        var previous=browser.view;
        edt(()->{
          browser.search.setText("missing");posted(timer);browser.cancel.doClick();browser.refresh();
          check(browser.idle(),"cancel clears posted debounce work and stays paused");
        });
        edt(()->check(browser.view==previous,"a canceled posted timer cannot overwrite the previous result"));
        edt(()->{
          browser.search.setText("posts");posted(timer);browser.clear();
          check(browser.idle(),"clear stops both queued debounce and worker state");
        });
        edt(()->check(browser.scanned==-1&&browser.view==null,"clear cannot be undone by a previously queued timer callback"));
        edt(()->{
          browser.search.setText("next");posted(timer);browser.close();
          check(browser.idle(),"unload consumes pending search work");
        });
        edt(()->check(browser.view==null,"unload discards every late result"));
      }finally{edt(browser::close);}
    }
    matcherSemantics();
    System.out.println("TrafficSearchLifecycleTest: "+checks+" checks passed");
  }
  static void matcherSemantics(){
    String value="GET /한글/İstanbul?x=1\nGET /second 200\n";
    String[] queries={"한글","istanbul",".*","GET","^GET","(?=GET)","(?=GET)|한글","absent"};
    for(boolean regex:List.of(false,true))for(boolean sensitive:List.of(false,true)){
      var query=new TrafficQuery("전체","전체",0,sensitive,regex,false,true,false,true,"",0);
      for(String text:queries){
        var reference=PacketInspector.find(value,text,regex,sensitive,()->false);
        check(reference.error()==null&&query.prepare(text).matchesText(value,()->false)==!reference.spans().isEmpty(),"prepared filter preserves literal/regex/unicode/nonempty semantics: "+text+" / "+regex+" / "+sensitive);
      }
    }
    check(TrafficQuery.defaults().prepare(" ").matchesText("anything",()->false),"blank filter keeps every row");
    boolean invalid=false;try{new TrafficQuery("전체","전체",0,false,true,false,true,false,true,"",0).prepare("[");}catch(IllegalArgumentException expected){invalid=true;}
    check(invalid,"invalid regex fails before scanning any original");
    AtomicInteger guards=new AtomicInteger();boolean cancelled=false;
    try{TrafficQuery.defaults().prepare("absent").matchesText("x".repeat(100000),()->guards.incrementAndGet()>2);}catch(CancellationException expected){cancelled=true;}
    check(cancelled,"prepared matcher checks cancellation within a long nonmatching body");
    boolean limited=false;try{new TrafficQuery("전체","전체",0,false,true,false,true,false,true,"",0).prepare("(a+)+$").matchesText("a".repeat(100000)+"!",()->false);}catch(IllegalArgumentException expected){limited="검색 제한".equals(expected.getMessage());}
    check(limited,"pathological regular expression retains the per-body search budget");
  }
}
