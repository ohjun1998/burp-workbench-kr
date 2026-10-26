package kr.workbench;

import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

public final class BackgroundJobsTest extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    lifecycle(); projectBoundary(); bounds(); notifications(); panel(); jsProjectBoundary();
    System.out.println("BackgroundJobsTest: "+checks+" checks passed");
  }
  static void lifecycle() {
    AtomicLong clock=new AtomicLong();BackgroundJobs jobs=new BackgroundJobs(clock::get);
    AtomicInteger stopped=new AtomicInteger(),opened=new AtomicInteger();
    var task=BackgroundJobs.begin(jobs,"계정 비교","auth",stopped::incrementAndGet);
    task.progress("계정 응답 확인",2,5);long id=jobs.snapshot().get(0).id();
    check(jobs.snapshot().get(0).state()==BackgroundJobs.State.RUNNING,"admitted work starts running without invented completion");
    check(jobs.snapshot().get(0).count().equals("2 / 5"),"actual processed and known total retained");
    clock.addAndGet(1_500_000_000L);check(jobs.snapshot().get(0).elapsedMillis()==1500,"elapsed time uses monotonic clock");
    task.result(opened::incrementAndGet);check(!jobs.openResult(id),"unfinished task cannot open a claimed completed result");
    check(jobs.requestCancel(id)&&stopped.get()==1,"cancel invokes only selected task callback");
    check(!jobs.requestCancel(id)&&stopped.get()==1,"repeated cancel cannot issue duplicate producer actions");
    check(jobs.snapshot().get(0).state()==BackgroundJobs.State.CANCEL_REQUESTED,"requesting stop waits for actual worker termination");
    task.progress("진행한 요청 종료 대기",2,5);check(jobs.snapshot().get(0).state()==BackgroundJobs.State.CANCEL_REQUESTED,"progress cannot erase pending stop");
    task.cancelled("응답 종료 후 중지");check(jobs.snapshot().get(0).state()==BackgroundJobs.State.CANCELLED,"producer acknowledgement is terminal");
    clock.addAndGet(9_000_000_000L);check(jobs.snapshot().get(0).elapsedMillis()==1500,"terminal elapsed time stays frozen");
    task.complete("late success");check(jobs.snapshot().get(0).state()==BackgroundJobs.State.CANCELLED,"late completion cannot rewrite terminal outcome");
    check(jobs.openResult(id)&&opened.get()==1,"completed result invokes its exact metadata-bound callback");
    var atomicSave=BackgroundJobs.begin(jobs,"증거 ZIP 저장","evidence",null);
    long save=jobs.snapshot().get(0).id();check(!jobs.snapshot().get(0).cancellable()&&!jobs.requestCancel(save),"atomic save explicitly has no cancel control");
    atomicSave.progress("파일 검증",0,-1);check(jobs.snapshot().get(0).count().equals("건수 미정"),"unknown total does not invent percentage");
    atomicSave.stopping("종료 준비");check(jobs.snapshot().get(0).state()==BackgroundJobs.State.CANCEL_REQUESTED,"producer can mark stopping without reentering cancellation");
    atomicSave.fail("저장 실패");check(jobs.snapshot().get(0).state()==BackgroundJobs.State.FAILED,"failure stays distinct from cancellation and completion");
    var invalid=BackgroundJobs.begin(jobs,"검색","traffic",null);boolean rejected=false;
    try{invalid.progress("검색",4,3);}catch(IllegalArgumentException expected){rejected=true;}
    check(rejected,"invalid counts rejected instead of displaying over-completion");invalid.complete("완료");jobs.close();
  }
  static void projectBoundary() {
    BackgroundJobs jobs=new BackgroundJobs();AtomicInteger cancel=new AtomicInteger(),open=new AtomicInteger();
    var old=BackgroundJobs.begin(jobs,"이전 작업","auth",cancel::incrementAndGet);long oldId=jobs.snapshot().get(0).id();old.result(open::incrementAndGet);
    jobs.reset();check(cancel.get()==1&&jobs.snapshot().isEmpty(),"project reset requests active worker cancellation and clears metadata");
    old.progress("old",1,1);old.complete("old");check(jobs.snapshot().isEmpty()&&!jobs.openResult(oldId),"old-project workers and result callbacks cannot repopulate a new project");
    var next=BackgroundJobs.begin(jobs,"새 작업","js",cancel::incrementAndGet);check(jobs.snapshot().size()==1,"new project can admit its own task");
    jobs.close();check(cancel.get()==2&&jobs.snapshot().isEmpty(),"unload cancels active producers outside lock and removes result history");
    next.complete("late");BackgroundJobs.begin(jobs,"after close","traffic",null).complete("none");check(jobs.snapshot().isEmpty(),"closed registry ignores all stale and new handles");
    jobs.close();check(cancel.get()==2,"repeated close is idempotent");
  }
  static void bounds() {
    BackgroundJobs jobs=new BackgroundJobs();for(int i=0;i<100;i++)BackgroundJobs.begin(jobs,"작업 "+i,"traffic",null).complete("완료");
    check(jobs.snapshot().size()==BackgroundJobs.LIMIT,"completed task metadata is bounded");
    check(jobs.snapshot().get(0).title().equals("작업 99"),"newest completed jobs appear first");
    jobs.reset();for(int i=0;i<BackgroundJobs.LIMIT+1;i++)BackgroundJobs.begin(jobs,"진행 작업","traffic",null);
    check(jobs.snapshot().size()==BackgroundJobs.LIMIT&&jobs.overflowCount()==1,"saturated active metadata reports overflow without silently evicting another running task");jobs.close();
  }
  static void notifications() throws Exception {
    BackgroundJobs jobs=new BackgroundJobs();AtomicInteger notifications=new AtomicInteger();AtomicBoolean allEdt=new AtomicBoolean(true);
    jobs.onChange(()->{notifications.incrementAndGet();allEdt.set(allEdt.get()&&SwingUtilities.isEventDispatchThread());});
    Thread worker=new Thread(()->{var task=BackgroundJobs.begin(jobs,"worker","traffic",null);for(int i=0;i<50;i++)task.progress("검사",i,50);task.complete("완료");});worker.start();worker.join();SwingUtilities.invokeAndWait(()->{});
    check(notifications.get()>0&&allEdt.get(),"worker updates notify UI listeners on EDT");jobs.close();
  }
  static void panel() throws Exception {
    SwingUtilities.invokeAndWait(()->{
      BackgroundJobs jobs=new BackgroundJobs();AtomicInteger opens=new AtomicInteger(),cancels=new AtomicInteger();
      JobMonitorPanel panel=new JobMonitorPanel(jobs,route->opens.incrementAndGet());
      var task=BackgroundJobs.begin(jobs,"검색","traffic",cancels::incrementAndGet);panel.refresh();panel.table.setRowSelectionInterval(0,0);
      check(panel.cancel.isEnabled()&&panel.result.getText().equals("작업 화면 열기"),"generic route is labelled as task screen, not a stale exact result");
      panel.cancel.doClick();check(cancels.get()==1&&!panel.cancel.isEnabled(),"monitor stop reaches producer and prevents repeated stop");
      task.result(opens::incrementAndGet);task.cancelled("중지");panel.refresh();panel.table.setRowSelectionInterval(0,0);
      check(panel.result.getText().equals("결과 열기"),"exact completed callback has distinct result action");panel.result.doClick();check(opens.get()==1,"monitor opens exact callback once");
      check(JobMonitorPanel.elapsed(61_000).equals("1분 1초"),"elapsed time is human readable");panel.close();jobs.close();
    });
  }
  static void jsProjectBoundary() throws Exception {
    BackgroundJobs registry=new BackgroundJobs();AtomicReference<JsAnalysisPanel> view=new AtomicReference<>();
    java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
    SwingUtilities.invokeAndWait(()->{var panel=new JsAnalysisPanel(null,()->{});panel.backgroundJobs=registry;view.set(panel);enqueue(panel,()->{entered.countDown();try{release.await();}catch(InterruptedException error){Thread.currentThread().interrupt();}});});
    JsAnalysisPanel panel=view.get();
    try {
      check(entered.await(5,java.util.concurrent.TimeUnit.SECONDS),"JS fixture holds an actual worker across a project transition");
      registry.reset();
      SwingUtilities.invokeAndWait(()->{panel.resetBackgroundProject();enqueue(panel,()->{});});
      check(registry.snapshot().size()==1&&!registry.snapshot().get(0).state().terminal(),"new-project JS admission receives a visible fresh task while old worker is terminating");
      release.countDown();long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while(System.nanoTime()<end){SwingUtilities.invokeAndWait(()->{});if(panel.idle()&&!registry.snapshot().isEmpty()&&registry.snapshot().get(0).state().terminal())break;Thread.sleep(10);}
      var current=registry.snapshot().get(0);
      check(current.state()==BackgroundJobs.State.COMPLETED&&current.completed()==1,"late old-project JS completion cannot inflate the fresh project's processed count");
    } finally {release.countDown();SwingUtilities.invokeAndWait(panel::close);registry.close();}
  }
  static void enqueue(JsAnalysisPanel panel,Runnable action) {
    try{var method=JsAnalysisPanel.class.getDeclaredMethod("enqueue",Runnable.class,boolean.class,long.class);method.setAccessible(true);check(Boolean.TRUE.equals(method.invoke(panel,action,true,128L)),"JS task admitted through production queue");}
    catch(ReflectiveOperationException error){throw new AssertionError(error);}
  }
}
