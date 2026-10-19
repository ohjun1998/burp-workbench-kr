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

  private void run() {
    while (!closed.get())
      try {
        wake.acquire();
        signalled.set(false);
        while (!closed.get() && cursor < store.size()) {
          try {
            DiskTrafficStore.PacketRef ref = store.ref(cursor);
            Runnable prepared = observer.prepare(ref, ref.packet());
            if (!closed.get() && prepared != null)
              SwingUtilities.invokeAndWait(
                  () -> {
                    if (!closed.get()) prepared.run();
                  });
          } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
            return;
          } catch (InvocationTargetException error) {
            report("수집 관찰 화면 반영", error.getCause());
          } catch (RuntimeException error) {
            report("수집 관찰 준비", error);
          } finally {
            cursor++;
          }
        }
      } catch (InterruptedException stop) {
        Thread.currentThread().interrupt();
        return;
      }
  }

  private void report(String stage, Throwable error) {
    if (!closed.get())
      errors.accept(
          stage, error instanceof RuntimeException r ? r : new IllegalStateException(error));
  }

  @Override
  public void close() {
    closed.set(true);
    thread.interrupt();
    wake.release();
  }
}
