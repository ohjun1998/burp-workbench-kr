package kr.workbench;

import burp.api.montoya.http.message.HttpRequestResponse;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import javax.swing.SwingUtilities;

/**
 * A cursor over the committed disk index is the pending queue. Only one packet is reconstructed at
 * a time and only its compact prepared observations reach Swing. No executor queue or EDT closure
 * retains original request/response bodies.
 */
final class CaptureObservations implements AutoCloseable {
  interface Observer {
    Runnable prepare(HttpRequestResponse packet);
  }

  private final DiskTrafficStore store;
  interface IndexedObserver {
    Runnable prepare(DiskTrafficStore.PacketRef ref, HttpRequestResponse packet);
  }

  private final IndexedObserver observer;
  private final BiConsumer<String, RuntimeException> errors;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Semaphore wake = new Semaphore(0);
  private final AtomicBoolean signalled = new AtomicBoolean();
  private final Thread thread;
  private volatile long cursor;
  private volatile long oldestPendingAt;
  private volatile long processed, failed;
  private volatile boolean active;
  private final RuntimeMetrics.Window restoreTime = new RuntimeMetrics.Window();
  private final RuntimeMetrics.Window prepareTime = new RuntimeMetrics.Window();
  private final RuntimeMetrics.Window swingWait = new RuntimeMetrics.Window();
  private final RuntimeMetrics.Window swingApply = new RuntimeMetrics.Window();

  record Metrics(long pending, long processed, long failed, long oldestPendingMillis,
      boolean active, RuntimeMetrics.Snapshot restore, RuntimeMetrics.Snapshot prepare,
      RuntimeMetrics.Snapshot swingWait, RuntimeMetrics.Snapshot swingApply) {}

  CaptureObservations(
      DiskTrafficStore store, Observer observer, BiConsumer<String, RuntimeException> errors) {
    this(store, (ref, packet) -> observer.prepare(packet), errors);
  }

  CaptureObservations(DiskTrafficStore store, IndexedObserver observer,
      BiConsumer<String, RuntimeException> errors) {
    this.store = store;
    this.observer = observer;
    this.errors = errors;
    thread = new Thread(this::run, "workbench-capture-observations");
    thread.setDaemon(true);
    thread.start();
  }

  void signal() {
    if (!closed.get() && signalled.compareAndSet(false, true)) wake.release();
  }

  long pending() {
    return closed.get() ? 0 : Math.max(0, store.size() - cursor);
  }

  long cursor() {
    return cursor;
  }

  boolean isClosed() {
    return closed.get();
  }

  boolean isAlive() {
    return thread.isAlive();
  }

  Metrics metrics() {
    long pending = pending(), capturedAt = oldestPendingAt;
    return new Metrics(pending, processed, failed,
        pending == 0 || capturedAt <= 0 ? 0 : Math.max(0, System.currentTimeMillis() - capturedAt),
        active, restoreTime.snapshot(), prepareTime.snapshot(), swingWait.snapshot(),
        swingApply.snapshot());
  }

  String diagnostics() {
    Metrics value = metrics();
    return "관찰 처리 시도 " + value.processed() + " · 실패 " + value.failed()
        + " · 대기(처리 중 포함) " + value.pending() + " · 가장 오래된 원본의 경과 "
        + value.oldestPendingMillis() + " ms"
        + "\n원본 복원 " + value.restore().summary()
        + " · 관찰 준비 " + value.prepare().summary()
        + "\n화면 반영 대기 " + value.swingWait().summary()
        + " · 화면 반영 실행 " + value.swingApply().summary()
        + "\n단계 시간은 최근 최대 " + RuntimeMetrics.WINDOW_SIZE
        + "건 실측 · 원본 경과는 보관 시각 기준(시계 변경 영향 가능)"
        + "\n" + store.appendDiagnostics();
  }

  private void run() {
    while (!closed.get())
      try {
        wake.acquire();
        signalled.set(false);
        while (!closed.get() && cursor < store.size()) {
          try {
            active = true;
            DiskTrafficStore.PacketRef ref = store.ref(cursor);
            oldestPendingAt = ref.capturedAt();
            long started = System.nanoTime();
            HttpRequestResponse packet;
            try {
              packet = ref.packet();
            } finally {
              restoreTime.since(started);
            }
            Runnable prepared;
            started = System.nanoTime();
            try {
              prepared = observer.prepare(ref, packet);
            } finally {
              prepareTime.since(started);
            }
            if (!closed.get() && prepared != null) {
              long queuedAt = System.nanoTime();
              SwingUtilities.invokeAndWait(
                  () -> {
                    if (!closed.get()) {
                      swingWait.since(queuedAt);
                      long applyingAt = System.nanoTime();
                      try {
                        prepared.run();
                      } finally {
                        swingApply.since(applyingAt);
                      }
                    }
                  });
            }
          } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
            return;
          } catch (InvocationTargetException error) {
            report("수집 관찰 화면 반영", error.getCause());
          } catch (RuntimeException error) {
            report("수집 관찰 준비", error);
          } finally {
            cursor++;
            processed++;
            active = false;
            // Metadata is read only by this worker. UI metrics never read the disk or bodies.
            oldestPendingAt = 0;
            if (!closed.get() && cursor < store.size()) {
              try {
                oldestPendingAt = store.ref(cursor).capturedAt();
              } catch (RuntimeException unavailable) {
                // The normal processing path reports storage errors; metrics are best effort.
              }
            }
          }
        }
      } catch (InterruptedException stop) {
        Thread.currentThread().interrupt();
        return;
      }
  }

  private void report(String stage, Throwable error) {
    if (!closed.get()) {
      failed++;
      errors.accept(
          stage, error instanceof RuntimeException r ? r : new IllegalStateException(error));
    }
  }

  @Override
  public void close() {
    closed.set(true);
    thread.interrupt();
    wake.release();
  }
}
