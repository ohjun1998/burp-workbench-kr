package kr.workbench;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Objects;

/** Releases reservations on completion, rejection, clear and shutdown, exactly once. */
final class JsWorkQueue {
  static final long DEFAULT_BUDGET = 16L * 1024 * 1024;
  private final ThreadPoolExecutor executor;
  private final long budget;
  private long bytes;
  private int count;
  private int running;
  private long accepted, rejected, completed, failed, cancelled;
  private final RuntimeMetrics.Window queueWait = new RuntimeMetrics.Window();
  private final RuntimeMetrics.Window execution = new RuntimeMetrics.Window();

  record Metrics(int waiting, int running, long retainedBytes, long budgetBytes,
      long oldestQueuedMillis, long accepted, long rejected, long completed, long failed,
      long cancelled, RuntimeMetrics.Snapshot queueWait, RuntimeMetrics.Snapshot execution) {}

  JsWorkQueue(ThreadPoolExecutor executor, long budget) {
    if (budget < 1) throw new IllegalArgumentException("positive queue budget required");
    this.executor = executor;
    this.budget = budget;
  }

  boolean offer(Runnable work, long retainedBytes) {
    Objects.requireNonNull(work, "work");
    long cost = Math.max(128, retainedBytes);
    synchronized (this) {
      if (cost > budget - bytes) {
        rejected++;
        return false;
      }
      bytes += cost;
      count++;
      accepted++;
    }
    Task task = new Task(work, cost);
    try {
      executor.execute(task);
      return true;
    } catch (RejectedExecutionException ex) {
      synchronized (this) {
        accepted--;
        rejected++;
      }
      task.release(false);
      return false;
    }
  }

  void cancelQueued() {
    for (Runnable runnable : executor.getQueue().toArray(Runnable[]::new)) {
      if (runnable instanceof Task task && executor.remove(task)) task.release(true);
    }
  }

  void close() {
    for (Runnable runnable : executor.shutdownNow())
      if (runnable instanceof Task task) task.release(true);
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

  Metrics metrics() {
    long now = System.nanoTime(), oldest = 0;
    // Inspect the existing bounded executor queue; never retain another list of tasks or bodies.
    for (Runnable runnable : executor.getQueue())
      if (runnable instanceof Task task && !task.released.get())
        oldest = Math.max(oldest, Math.max(0, now - task.queuedAt));
    synchronized (this) {
      return new Metrics(Math.max(0, count - running), running, bytes, budget,
          oldest / 1_000_000, accepted, rejected, completed, failed, cancelled,
          queueWait.snapshot(), execution.snapshot());
    }
  }

  String diagnostics() {
    Metrics value = metrics();
    return "JS 작업 대기 " + value.waiting() + " · 실행 " + value.running()
        + " · 최장 대기 " + value.oldestQueuedMillis() + " ms"
        + " · 완료 " + value.completed() + " · 실패 " + value.failed()
        + " · 접수 거부 " + value.rejected() + " · 대기 취소 " + value.cancelled()
        + "\n대기 시간 " + value.queueWait().summary()
        + " · 작업 실행 " + value.execution().summary()
        + "\n작업 보유량 추정 " + RuntimeMetrics.memory(value.retainedBytes())
        + " / " + RuntimeMetrics.memory(value.budgetBytes());
  }

  private final class Task implements Runnable {
    private Runnable work;
    private final long cost;
    private final AtomicBoolean released = new AtomicBoolean();
    private final long queuedAt = System.nanoTime();

    Task(Runnable work, long cost) {
      this.work = work;
      this.cost = cost;
    }

    @Override
    public void run() {
      long started = System.nanoTime();
      synchronized (JsWorkQueue.this) {
        if (released.get()) return;
        running++;
      }
      queueWait.record(started - queuedAt);
      boolean succeeded = false;
      try {
        Runnable action = work;
        if (action != null) action.run();
        succeeded = true;
      } finally {
        execution.since(started);
        synchronized (JsWorkQueue.this) {
          running--;
          if (succeeded) completed++;
          else failed++;
        }
        release(false);
      }
    }

    void release(boolean wasCancelled) {
      if (released.compareAndSet(false, true)) {
        work = null;
        synchronized (JsWorkQueue.this) {
          bytes -= cost;
          count--;
          if (wasCancelled) cancelled++;
        }
      }
    }
  }
}
