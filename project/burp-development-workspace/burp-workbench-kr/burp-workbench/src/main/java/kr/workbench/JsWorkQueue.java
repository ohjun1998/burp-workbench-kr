package kr.workbench;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Releases reservations on completion, rejection, clear and shutdown, exactly once. */
final class JsWorkQueue {
  static final long DEFAULT_BUDGET = 16L * 1024 * 1024;
  private final ThreadPoolExecutor executor;
  private final long budget;
  private long bytes;
  private int count;

  JsWorkQueue(ThreadPoolExecutor executor, long budget) {
    if (budget < 1) throw new IllegalArgumentException("positive queue budget required");
    this.executor = executor;
    this.budget = budget;
  }

  boolean offer(Runnable work, long retainedBytes) {
    long cost = Math.max(128, retainedBytes);
    synchronized (this) {
      if (cost > budget - bytes) return false;
      bytes += cost;
      count++;
    }
    Task task = new Task(work, cost);
    try {
      executor.execute(task);
      return true;
    } catch (RejectedExecutionException ex) {
      task.release();
      return false;
    }
  }

  void cancelQueued() {
    for (Runnable runnable : executor.getQueue().toArray(Runnable[]::new)) {
      if (runnable instanceof Task task && executor.remove(task)) task.release();
    }
  }

  void close() {
    for (Runnable runnable : executor.shutdownNow())
      if (runnable instanceof Task task) task.release();
  }

  synchronized long bytes() {
    return bytes;
  }

  synchronized int count() {
    return count;
  }

  long budget() {
    return budget;
  }

  private final class Task implements Runnable {
    private Runnable work;
    private final long cost;
    private final AtomicBoolean released = new AtomicBoolean();

    Task(Runnable work, long cost) {
      this.work = work;
      this.cost = cost;
    }

    @Override
    public void run() {
      try {
        Runnable action = work;
        if (action != null) action.run();
      } finally {
        release();
      }
    }

    void release() {
      if (released.compareAndSet(false, true)) {
        work = null;
        synchronized (JsWorkQueue.this) {
          bytes -= cost;
          count--;
        }
      }
    }
  }
}
