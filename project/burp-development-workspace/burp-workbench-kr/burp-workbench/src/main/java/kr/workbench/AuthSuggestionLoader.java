package kr.workbench;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/** One bounded local-reading worker. Superseded/cancelled jobs cannot publish to Swing. */
final class AuthSuggestionLoader implements AutoCloseable {
  final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(1), runnable -> { Thread thread = new Thread(runnable, "Workbench-auth-setup"); thread.setDaemon(true); return thread; });
  private final AtomicLong generation = new AtomicLong();
  private Future<?> pending;
  private volatile boolean closed;
  synchronized <T> void load(Callable<T> action, Consumer<T> success, Consumer<Throwable> failure) {
    cancel(); if (closed) return;
    long token = generation.get();
    try {
      pending = worker.submit(() -> {
        try {
          T value = action.call();
          SwingUtilities.invokeLater(() -> { if (!closed && token == generation.get()) success.accept(value); });
        } catch (Throwable error) {
          if (error instanceof ThreadDeath death) throw death;
          SwingUtilities.invokeLater(() -> { if (!closed && token == generation.get()) failure.accept(error); });
        }
      });
    } catch (RejectedExecutionException error) { failure.accept(error); }
  }
  synchronized void cancel() {
    generation.incrementAndGet();
    if (pending != null) pending.cancel(true);
    pending = null; worker.purge();
  }
  @Override public synchronized void close() { closed = true; cancel(); worker.shutdownNow(); }
}
