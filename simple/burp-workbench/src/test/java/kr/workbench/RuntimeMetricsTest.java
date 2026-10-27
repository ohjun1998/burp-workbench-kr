package kr.workbench;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** No target requests: bounded timings, queue lifecycle and disk-observer failure accounting. */
public final class RuntimeMetricsTest extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    rollingWindow();
    queueLifecycle();
    captureStages();
    cacheAndHeapLabels();
    System.out.println("RuntimeMetricsTest: " + checks + " checks passed");
  }

  private static void rollingWindow() {
    RuntimeMetrics.Window window = new RuntimeMetrics.Window();
    check(window.snapshot().samples() == 0 && window.snapshot().summary().equals("측정 전"),
        "unmeasured stages are not reported as zero-latency success");
    for (int i = 1; i <= 1000; i++) window.record(i * 1_000_000L);
    RuntimeMetrics.Snapshot sample = window.snapshot();
    check(sample.observations() == 1000 && sample.samples() == 256,
        "timings keep only 256 samples while preserving lifetime observation count");
    check(sample.p50Millis() == 872 && sample.p95Millis() == 988 && sample.maxMillis() == 1000,
        "quantiles use the recent window instead of old lifetime samples");
    RuntimeMetrics.Window negative = new RuntimeMetrics.Window();
    negative.record(-1);
    check(negative.snapshot().p95Millis() == 0, "negative timing input is clamped");
  }

  private static void queueLifecycle() throws Exception {
    ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(8), action -> {
          Thread thread = new Thread(action, "runtime-metrics-test");
          thread.setUncaughtExceptionHandler((ignored, expected) -> {});
          return thread;
        });
    CountDownLatch gate = new CountDownLatch(1), blocked = new CountDownLatch(1);
    executor.execute(() -> {
      blocked.countDown();
      try { gate.await(); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
    });
    check(blocked.await(5, TimeUnit.SECONDS), "fixture reserves the executor without a queue task");
    JsWorkQueue queue = new JsWorkQueue(executor, 1000);
    try {
      check(queue.offer(() -> {}, 600) && queue.offer(() -> {}, 400)
          && !queue.offer(() -> {}, 128), "retained-byte boundary remains exact with metrics");
      JsWorkQueue.Metrics waiting = queue.metrics();
      check(waiting.waiting() == 2 && waiting.running() == 0 && waiting.retainedBytes() == 1000
          && waiting.accepted() == 2 && waiting.rejected() == 1,
          "queued, running and budget-rejected work are distinguished");
      queue.cancelQueued();
      queue.cancelQueued();
      check(queue.metrics().cancelled() == 2 && queue.bytes() == 0 && queue.count() == 0,
          "repeated cancellation releases reservations and counts each queued task once");
      gate.countDown();
      check(queue.offer(() -> {}, 200)
          && queue.offer(() -> { throw new IllegalArgumentException("fixture"); }, 200),
          "success and failure fixtures are accepted after releasing the queue");
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
      JsWorkQueue.Metrics finished = queue.metrics();
      check(finished.completed() == 1 && finished.failed() == 1 && finished.waiting() == 0
          && finished.running() == 0 && finished.retainedBytes() == 0,
          "failed jobs release bytes without being counted as successful completions");
      check(finished.queueWait().observations() == 2 && finished.execution().observations() == 2,
          "only executed jobs contribute queue and execution timing samples");
    } finally {
      gate.countDown();
      queue.close();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
    check(!queue.offer(() -> {}, 128) && queue.metrics().rejected() == 2
        && queue.metrics().accepted() == 4 && queue.bytes() == 0,
        "executor shutdown rejection is counted without accepting or retaining another job");
  }

  private static void captureStages() throws Exception {
    installTrafficPacketFactory();
    AtomicInteger preparations = new AtomicInteger(), applied = new AtomicInteger(), errors = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    try (DiskTrafficStore store = new DiskTrafficStore()) {
      for (int i = 0; i < 2; i++)
        store.append("fixture", "PROXY", request("https://metrics.test/" + i, "GET", true,
            List.of()), response(200, "private-body-value"), System.currentTimeMillis() - 5000);
      try (CaptureObservations observer = new CaptureObservations(store, packet -> {
        if (preparations.incrementAndGet() == 1) {
          entered.countDown();
          try { release.await(); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
          throw new IllegalArgumentException("fixture");
        }
        return applied::incrementAndGet;
      }, (stage, error) -> errors.incrementAndGet())) {
        observer.signal();
        check(entered.await(5, TimeUnit.SECONDS), "capture observer enters preparation off the UI thread");
        CaptureObservations.Metrics blocked = observer.metrics();
        check(blocked.pending() == 2 && blocked.active() && blocked.oldestPendingMillis() >= 5000,
            "observer reports disk backlog and oldest original age while preparation is blocked");
        release.countDown();
        waitFor(() -> observer.metrics().processed() == 2);
        CaptureObservations.Metrics done = observer.metrics();
        check(done.pending() == 0 && done.failed() == 1 && errors.get() == 1 && applied.get() == 1,
            "failed observation advances cursor without blocking later work or hiding failure");
        check(done.restore().samples() == 2 && done.prepare().samples() == 2
            && done.swingWait().samples() == 1 && done.swingApply().samples() == 1,
            "restoration, preparation, UI wait and UI execution have separate measured samples");
        check(!observer.diagnostics().contains("private-body-value") && store.size() == 2,
            "diagnostics do not expose bodies or discard retained originals");
      } finally { release.countDown(); }
    }
  }

  private static void cacheAndHeapLabels() {
    JsSourceCache cache = new JsSourceCache(2048);
    cache.sourceOnly("fixture.js", "source");
    check(cache.metrics().estimatedBytes() == cache.bytes() && cache.metrics().entries() == 1
        && cache.metrics().results() == 0 && cache.diagnostics().contains("추정"),
        "cache metrics explicitly describe estimated retained bytes");
    RuntimeMetrics.Heap heap = RuntimeMetrics.heap();
    check(heap.usedBytes() >= 0 && heap.usedBytes() <= heap.committedBytes()
        && RuntimeMetrics.heapSummary().contains("Burp·모든 확장 포함"),
        "measured heap is labelled as the whole JVM rather than this extension");
  }

  private static void waitFor(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) throw new AssertionError("metrics fixture timed out");
      Thread.sleep(5);
    }
  }
}
