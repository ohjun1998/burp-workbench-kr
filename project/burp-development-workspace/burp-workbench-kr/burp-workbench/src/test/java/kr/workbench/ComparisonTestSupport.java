package kr.workbench;

import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Waits for production async publication while continuing to dispatch EDT events. */
public final class ComparisonTestSupport {
  private ComparisonTestSupport() {}
  public static void compareAndWait(StructuredComparePanel panel) {
    onEdt(() -> { panel.compare(); await(panel); });
  }
  public static void await(StructuredComparePanel panel) {
    if(!SwingUtilities.isEventDispatchThread()){onEdt(()->await(panel));return;}
    if(!panel.isComparing())return;
    SecondaryLoop loop=Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
    long deadline=System.nanoTime()+10_000_000_000L;
    boolean[] timedOut={false};
    Timer timer=new Timer(10,event->{
      if(!panel.isComparing()||System.nanoTime()>=deadline){
        timedOut[0]=panel.isComparing();((Timer)event.getSource()).stop();loop.exit();
      }
    });
    timer.start();
    try{if(!loop.enter())throw new AssertionError("Could not enter comparison event loop");}
    finally{timer.stop();}
    if(timedOut[0])throw new AssertionError("Comparison did not terminate within10seconds");
  }
  private static void onEdt(Runnable action){
    if(SwingUtilities.isEventDispatchThread()){action.run();return;}
    AtomicReference<Throwable> failure=new AtomicReference<>();
    try{SwingUtilities.invokeAndWait(()->{try{action.run();}catch(Throwable error){failure.set(error);}});}
    catch(Exception error){throw new AssertionError(error);}
    if(failure.get()!=null)throw new AssertionError(failure.get());
  }
}
