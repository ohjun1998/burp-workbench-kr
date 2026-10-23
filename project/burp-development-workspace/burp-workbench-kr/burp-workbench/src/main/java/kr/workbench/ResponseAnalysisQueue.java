package kr.workbench;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Releases retained-input reservations on every task lifecycle edge. */
final class ResponseAnalysisQueue {
  private final ThreadPoolExecutor worker;
  ResponseAnalysisQueue(ThreadPoolExecutor worker) { this.worker = worker; }

  boolean offer(Runnable action, ResponseAnalysisBudget.Reservation reservation) {
    if (reservation == null) return false;
    Task task = new Task(action, reservation);
    try { worker.execute(task); return true; }
    catch (RejectedExecutionException rejected) { task.release(); return false; }
    catch (RuntimeException | Error failure) { task.release(); throw failure; }
  }

  void cancelQueued() {
    for (Runnable candidate : worker.getQueue().toArray(Runnable[]::new))
      if (candidate instanceof Task task && worker.remove(task)) task.release();
  }

  void close() {
    for (Runnable candidate : worker.shutdownNow())
      if (candidate instanceof Task task) task.release();
  }

  private static final class Task implements Runnable {
    private Runnable action;
    private final ResponseAnalysisBudget.Reservation reservation;
    private final AtomicBoolean released = new AtomicBoolean();
    Task(Runnable action, ResponseAnalysisBudget.Reservation reservation) {
      this.action = action; this.reservation = reservation;
    }
    @Override public void run() {
      try { Runnable current = action; if (current != null) current.run(); }
      finally { release(); }
    }
    void release() {
      if (released.compareAndSet(false, true)) { action = null; reservation.close(); }
    }
  }
}
