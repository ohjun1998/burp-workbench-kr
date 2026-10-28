package kr.workbench;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/** Uses real worker/EDT barriers to test cancellation and stale publication, without timing claims. */
public final class ComparisonResponsiveness0534Test {
  static int checks;
  static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
  static void edt(Runnable work)throws Exception{SwingUtilities.invokeAndWait(work);}
  static final class Gate implements StructuredComparePanel.Computation {
    final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    final AtomicBoolean onEdt=new AtomicBoolean(),interrupted=new AtomicBoolean();
    volatile String left,right;
    @Override public StructuredComparePanel.Compared compute(String a,String b,boolean formatted){
      onEdt.set(SwingUtilities.isEventDispatchThread());left=a;right=b;entered.countDown();
      boolean waiting=true;while(waiting)try{release.await();waiting=false;}catch(InterruptedException error){interrupted.set(true);}
      return new StructuredComparePanel.Compared(StructuredCompare.compare(a,b,formatted),ResponseDifference.compare(a,b));
    }
    void started()throws Exception{check(entered.await(5,TimeUnit.SECONDS),"comparison reaches real worker barrier");}
  }
  public static void main(String[] args)throws Exception{
    AtomicReference<StructuredComparePanel> box=new AtomicReference<>();
    BackgroundJobs jobs=new BackgroundJobs();AtomicReference<String> project=new AtomicReference<>("project-a");
    edt(()->{var p=new StructuredComparePanel();p.backgroundJobs=jobs;p.projectIdentity=project::get;box.set(p);
      p.leftInput.setText("{\"v\":1}");p.rightInput.setText("{\"v\":2}");ComparisonTestSupport.compareAndWait(p);
      check(p.result!=null&&p.summaryModel.getRowCount()==1,"successful async comparison publishes inspectable differences");});
    StructuredComparePanel p=box.get();
    try {
      AtomicReference<StructuredCompare.Result> previous=new AtomicReference<>();Gate cancelled=new Gate();
      edt(()->{previous.set(p.result);p.computation=cancelled;p.compare();
        check(p.result==previous.get()&&p.summaryModel.getRowCount()==1,"replacement computation preserves previous successful result");
        check(p.cancel.isEnabled()&&p.isComparing(),"running comparison exposes a local stop action");});
      cancelled.started();
      AtomicInteger dispatched=new AtomicInteger();edt(dispatched::incrementAndGet);
      check(dispatched.get()==1&&!cancelled.onEdt.get(),"EDT continues dispatching while comparison is blocked off-thread");
      edt(()->{p.cancelComparison();check(p.isComparing()&&!p.cancel.isEnabled(),"stop request waits for actual worker termination");});
      check(jobs.snapshot().get(0).state()==BackgroundJobs.State.CANCEL_REQUESTED,"job monitor distinguishes requested cancellation from termination");
      cancelled.release.countDown();ComparisonTestSupport.await(p);
      edt(()->{check(p.result==previous.get(),"cancelled replacement cannot overwrite previous successful result");
        check(!p.isComparing()&&p.status.getText().contains("중지 완료"),"worker termination acknowledges stop in UI");});
      check(cancelled.interrupted.get()&&jobs.snapshot().get(0).state()==BackgroundJobs.State.CANCELLED,"real worker receives interrupt and job terminates cancelled");

      Gate stale=new Gate();edt(()->{p.leftInput.setText("{\"v\":3}");p.computation=stale;p.compare();});stale.started();
      edt(()->p.rightInput.setText("{\"v\":99}"));stale.release.countDown();ComparisonTestSupport.await(p);
      edt(()->{check(p.result==null&&p.summary==null&&p.summaryModel.getRowCount()==0,"editing input prevents stale line and semantic publication");
        check(stale.right.equals("{\"v\":2}"),"worker uses immutable old snapshot rather than current text components");});

      Gate replaced=new Gate();edt(()->{p.computation=replaced;p.compare();});replaced.started();
      edt(()->{p.rightInput.setText("{\"v\":100}");p.computation=(a,b,f)->new StructuredComparePanel.Compared(StructuredCompare.compare(a,b,f),ResponseDifference.compare(a,b));p.compare();});
      replaced.release.countDown();ComparisonTestSupport.await(p);
      edt(()->check(p.summary.right().contains("100")&&!p.summary.right().contains("99"),"queued latest revision wins after cancelled older worker terminates"));

      Gate switched=new Gate();edt(()->{p.computation=switched;p.compare();});switched.started();project.set("project-b");switched.release.countDown();ComparisonTestSupport.await(p);
      edt(()->check(p.result==null&&p.leftInput.getText().isEmpty()&&p.rightInput.getText().isEmpty(),"project identity change drops pending result and old-project context"));

      Gate closing=new Gate();edt(()->{p.leftInput.setText("x".repeat(200_000));p.rightInput.setText("y");p.computation=closing;p.compare();});closing.started();
      check(closing.left.length()==ResponseDifference.INPUT_LIMIT+1,"snapshot bounds inspected text while retaining partial-input marker");
      edt(p::close);closing.release.countDown();ComparisonTestSupport.await(p);
      edt(()->{check(p.result==null&&!p.cancel.isEnabled()&&!p.captureEvidence.isEnabled(),"unload rejects late publication and disables source actions");p.compare();check(!p.isComparing(),"closed comparison cannot start another worker");});
    } finally {edt(p::close);jobs.close();}
    System.out.println("Comparison responsiveness 0.53.4: "+checks+" checks passed");
  }
}
