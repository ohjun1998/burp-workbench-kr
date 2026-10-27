package kr.workbench;

import java.util.Arrays;
import java.util.Locale;

/** Bounded, body-free timing samples. Durations use the monotonic clock. */
final class RuntimeMetrics {
  static final int WINDOW_SIZE = 256;

  record Snapshot(long observations, int samples, double p50Millis, double p95Millis,
      double maxMillis) {
    String summary() {
      if (samples == 0) return "측정 전";
      return String.format(Locale.ROOT, "최근 %d건 p50 %.2f ms / p95 %.2f ms", samples,
          p50Millis, p95Millis);
    }
  }

  static final class Window {
    private final long[] nanos = new long[WINDOW_SIZE];
    private int next, size;
    private long observations;

    synchronized void record(long durationNanos) {
      nanos[next] = Math.max(0, durationNanos);
      next = (next + 1) % nanos.length;
      if (size < nanos.length) size++;
      observations++;
    }

    void since(long startedNanos) {
      record(System.nanoTime() - startedNanos);
    }

    synchronized Snapshot snapshot() {
      if (size == 0) return new Snapshot(observations, 0, 0, 0, 0);
      long[] sorted = Arrays.copyOf(nanos, size);
      Arrays.sort(sorted);
      // Nearest-rank quantiles over the retained recent window, not lifetime percentiles.
      return new Snapshot(observations, size, millis(sorted[(size - 1) / 2]),
          millis(sorted[(int) Math.ceil(size * .95) - 1]), millis(sorted[size - 1]));
    }
  }

  record Heap(long usedBytes, long committedBytes, long maxBytes) {}

  static Heap heap() {
    Runtime runtime = Runtime.getRuntime();
    long committed = runtime.totalMemory();
    return new Heap(Math.max(0, committed - runtime.freeMemory()), committed,
        runtime.maxMemory());
  }

  static String heapSummary() {
    Heap heap = heap();
    return "JVM 전체 heap 사용 " + memory(heap.usedBytes()) + " / 확보 "
        + memory(heap.committedBytes()) + " / 최대 " + memory(heap.maxBytes())
        + " (Burp·모든 확장 포함, 캐시 추정치와 별개)";
  }

  static String memory(long bytes) {
    return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
  }

  private static double millis(long nanos) {
    return nanos / 1_000_000.0;
  }

  private RuntimeMetrics() {}
}
