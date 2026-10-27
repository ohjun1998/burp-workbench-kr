package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.scope.Scope;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Queue inputs, exact reservation release and passive source/scope preservation. */
public final class ResponseBudgetTest extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    reservations();
    queueLifecycle();
    coalescedDrain();
    referenceAndSnapshot();
    rejectionAndExpiry();
    appendMetrics();
    System.out.println("ResponseBudgetTest: " + checks + " checks passed");
  }

  static MontoyaApi api(AtomicBoolean enabled) {
    var scope = proxy(Scope.class, (p,m,a) -> m.getName().equals("isInScope")
        ? enabled.get() && a[0].toString().startsWith("https://budget.test/") : def(m));
    return proxy(MontoyaApi.class, (p,m,a) -> {
      if (m.getName().equals("scope")) return scope;
      if (m.getName().equals("http")) throw new AssertionError("passive analysis must not access HTTP transport");
      return def(m);
    });
  }

  static void reservations() {
    var budget = new ResponseAnalysisBudget(1000, 2000);
    var queued = budget.queued(1000); var running = budget.working(1500);
    check(queued != null && running != null && budget.queued(1) == null && budget.working(501) == null,
        "retained inputs and restoration work have independent exact byte limits");
    queued.close(); queued.close(); running.close(); running.close();
    check(budget.metrics().queuedBytes() == 0 && budget.metrics().workingBytes() == 0
        && budget.metrics().rejected() == 2, "reservations return exactly once, including repeated cleanup");
    check(budget.diagnostics().contains("JVM 전체 heap 상한 아님"),
        "working reservations are not presented as measured JVM heap limits");
  }

  static void queueLifecycle() throws Exception {
    var executor = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(3), work -> {
      Thread thread = new Thread(work); thread.setUncaughtExceptionHandler((t,e) -> {}); return thread;
    });
    var queue = new ResponseAnalysisQueue(executor); var budget = new ResponseAnalysisBudget(1000,2000);
    CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(1);
    executor.execute(() -> await(entered, release));
    check(entered.await(5,TimeUnit.SECONDS), "queue lifecycle fixture blocks the running worker");
    check(queue.offer(() -> {}, budget.queued(600)) && queue.offer(() -> {}, budget.queued(400)),
        "queued work reserves retained inputs before execution");
    queue.cancelQueued(); queue.cancelQueued();
    check(budget.metrics().queuedBytes() == 0 && executor.getQueue().isEmpty(),
        "clearing queued jobs releases all inputs exactly once");
    release.countDown();
    queue.offer(() -> { try(var working = budget.working(800)) { throw new IllegalArgumentException("fixture"); } },budget.queued(300));
    executor.submit(() -> {}).get(5,TimeUnit.SECONDS);
    check(budget.metrics().queuedBytes() == 0 && budget.metrics().workingBytes() == 0,
        "exceptional analysis releases retained and active work reservations");
    CountDownLatch active = new CountDownLatch(1), stop = new CountDownLatch(1);
    queue.offer(() -> { try(var working = budget.working(900)) { await(active,stop); } },budget.queued(200));
    check(active.await(5,TimeUnit.SECONDS), "active reservation fixture enters before unload");
    queue.offer(() -> {},budget.queued(200));queue.close();
    check(executor.awaitTermination(5,TimeUnit.SECONDS) && budget.metrics().queuedBytes() == 0
        && budget.metrics().workingBytes() == 0, "unload interrupts active work and releases waiting inputs");
    check(!queue.offer(() -> {},budget.queued(200)) && budget.metrics().queuedBytes() == 0,
        "executor rejection cannot leak an already acquired reservation");
    var unavailable = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),
        action -> { throw new IllegalStateException("thread creation fixture"); });
    try {
      try {new ResponseAnalysisQueue(unavailable).offer(()->{},budget.queued(200));throw new AssertionError("failure expected");}
      catch(IllegalStateException expected){}
      check(budget.metrics().queuedBytes()==0,"worker creation failure also releases the retained-input reservation");
    } finally {unavailable.shutdownNow();}
  }

  static void referenceAndSnapshot() throws Exception {
    installTrafficPacketFactory();
    AtomicBoolean allowed = new AtomicBoolean(true); var panel = new ResponseReconPanel(api(allowed));
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    panel.worker.execute(() -> await(entered,release));
    check(entered.await(5,TimeUnit.SECONDS), "analysis worker paused before reference-only admission");
    try(var disk = new DiskTrafficStore()) {
      var request = request("https://budget.test/original", "GET", true,List.of());
      var response = response(200, "x".repeat(512 * 1024));
      var original = disk.append(request,response);
      AtomicInteger copied = new AtomicInteger();
      HttpRequest guarded = proxy(HttpRequest.class,(p,m,a) -> {
        if(m.getName().equals("toByteArray")){copied.incrementAndGet();throw new AssertionError("automatic queue copied request");}
        return m.invoke(request,a);
      });
      panel.offer(guarded,response,original);
      Object automatic = queuedInput(panel.worker.getQueue().peek());
      check(automatic instanceof ResponseAnalysisInput.Original && copied.get() == 0
          && panel.analysisBudget.metrics().queuedBytes() < 4096,
          "automatic queue retains only immutable disk metadata, never a second HTTP packet");
      var manualRequest = request("https://budget.test/manual", "GET", true,List.of());
      var manualResponse = response(500,"BadSqlGrammarException");
      panel.offer(manualRequest,manualResponse);
      var tasks = panel.worker.getQueue().toArray();
      Object manual = queuedInput(tasks[tasks.length-1]);
      check(manual instanceof ResponseAnalysisInput.Snapshot
          && java.util.Arrays.stream(manual.getClass().getDeclaredFields())
              .noneMatch(f -> f.getType() == HttpRequest.class || f.getType() == HttpResponse.class),
          "manual queue snapshot contains owned bytes and metadata instead of host request/response objects");
      release.countDown(); drain(panel);
      check(panel.retries.get(original.id()).state() == AnalysisRetries.State.COMPLETE
          && panel.findings.getRowCount() > 0, "disk and detached manual inputs both produce passive results");
      check(panel.analysisBudget.metrics().queuedBytes() == 0 && panel.analysisBudget.metrics().workingBytes() == 0
          && panel.analysisBudget.metrics().peakWorkingBytes() > 0,
          "actual restoration and decoding acquire and return working reservations");
      check(disk.ref(0).id().equals(original.id()) && java.util.Arrays.equals(
          disk.ref(0).response().toByteArray().getBytes(),response.toByteArray().getBytes()),
          "passive analysis preserves retained source identity and exact original response bytes");
    } finally {release.countDown();panel.close();}
  }

  static void coalescedDrain() throws Exception {
    var panel = new ResponseReconPanel(api(new AtomicBoolean(true)));
    CountDownLatch applying = new CountDownLatch(1), release = new CountDownLatch(1), barrier = new CountDownLatch(1);
    AtomicBoolean first = new AtomicBoolean(true), newerViewPendingAtBarrier = new AtomicBoolean();
    AtomicInteger rowsAtBarrier = new AtomicInteger(-1);
    try {
      edt(() -> panel.findings.addTableModelListener(event -> {
        if (first.compareAndSet(true, false)) await(applying, release);
      }));
      panel.worker.submit(() -> panel.refresh(panel.generation.get())).get(5, TimeUnit.SECONDS);
      check(applying.await(5, TimeUnit.SECONDS), "first response view is applying while a newer analysis completes");
      panel.worker.submit(() -> {
        panel.issues.put("new-finding", new PassiveFindings.Finding("fixture", "https://budget.test/latest", "Body", "latest"));
        panel.counts.put("new-finding", 1); panel.refresh(panel.generation.get());
      }).get(5, TimeUnit.SECONDS);
      // Reproduce the old drain ordering: its barrier is already queued when the first
      // publication's finally block schedules the newer coalesced publication.
      javax.swing.SwingUtilities.invokeLater(() -> {
        newerViewPendingAtBarrier.set(panel.pendingView.get() != null && panel.viewUpdatePending.get());
        rowsAtBarrier.set(panel.findings.getRowCount()); barrier.countDown();
      });
      release.countDown();
      check(barrier.await(5, TimeUnit.SECONDS) && newerViewPendingAtBarrier.get() && rowsAtBarrier.get() == 0,
          "one EDT barrier can precede a rescheduled coalesced response view");
      drain(panel);
      edt(() -> check(panel.findings.getRowCount() == 1 && panel.pendingView.get() == null && !panel.viewUpdatePending.get(),
          "response drain waits for the latest coalesced view before reading result tables"));
    } finally { release.countDown(); panel.close(); }
  }

  static void rejectionAndExpiry() throws Exception {
    installTrafficPacketFactory();
    AtomicBoolean allowed = new AtomicBoolean(true); var panel = new ResponseReconPanel(api(allowed));
    try {
      var request = request("https://budget.test/huge-request", "POST", true,List.of());
      AtomicInteger copies = new AtomicInteger();
      ByteArray virtual = proxy(ByteArray.class,(p,m,a) -> switch(m.getName()) {
        case "length" -> ResponseAnalysisBudget.WIRE_LIMIT + 1;
        case "getBytes" -> { copies.incrementAndGet(); throw new AssertionError("oversized body copied"); }
        default -> def(m);
      });
      HttpRequest huge = proxy(HttpRequest.class,(p,m,a) -> m.getName().equals("toByteArray") ? virtual : m.invoke(request,a));
      panel.offer(huge,response(200,"ok"));
      check(copies.get() == 0 && panel.retries.snapshot().get(0).state() == AnalysisRetries.State.TOO_LARGE
          && panel.analysisBudget.metrics().queuedBytes() == 0,
          "oversized manual request is rejected from byte lengths before copying or queueing");
      try(var disk = new DiskTrafficStore()) {
        var original = disk.append(request("https://budget.test/working-budget","GET",true,List.of()),response(200,"ok"));
        try(var reserved = panel.analysisBudget.working(ResponseAnalysisBudget.WORKING_LIMIT)) {
          panel.offer(original.request(),original.response(),original);drain(panel);
          check(panel.retries.get(original.id()).state()==AnalysisRetries.State.QUEUE_FULL
              && panel.analysisBudget.metrics().queuedBytes()==0,
              "unavailable restoration budget leaves an actionable original without leaking queued inputs");
        }
        panel.retryOriginal(original.id());drain(panel);
        check(panel.retries.get(original.id()).state()==AnalysisRetries.State.COMPLETE
            && panel.analysisBudget.metrics().workingBytes()==0,
            "the same original can be retried after its working reservation becomes available");
      }
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      panel.worker.execute(() -> await(entered,release)); entered.await(5,TimeUnit.SECONDS);
      try(var disk = new DiskTrafficStore()) {
        var original = disk.append(request("https://budget.test/revoked","GET",true,List.of()),response(200,"ok"));
        panel.offer(original.request(),original.response(),original);allowed.set(false);release.countDown();drain(panel);
        check(panel.retries.get(original.id()).state() == AnalysisRetries.State.SCOPE_EXCLUDED
            && panel.analysisBudget.metrics().queuedBytes() == 0 && panel.analysisBudget.metrics().workingBytes() == 0,
            "queued original rechecks current Scope before restoration and releases its reservation");
      } finally {release.countDown();}
      allowed.set(true);
      CountDownLatch expiredEntered = new CountDownLatch(1), expiredRelease = new CountDownLatch(1);
      panel.worker.execute(() -> await(expiredEntered,expiredRelease)); expiredEntered.await(5,TimeUnit.SECONDS);
      DiskTrafficStore expired = new DiskTrafficStore();
      var original = expired.append(request("https://budget.test/expired","GET",true,List.of()),response(200,"ok"));
      panel.offer(original.request(),original.response(),original);expired.close();expiredRelease.countDown();drain(panel);
      check(panel.retries.get(original.id()).state() == AnalysisRetries.State.EXPIRED
          && panel.analysisBudget.metrics().queuedBytes() == 0 && panel.analysisBudget.metrics().workingBytes() == 0,
          "expired disk reference reports unavailable original and returns all reservations");
      byte[] malformed = {31,(byte)139,8,0,0,0,0,0,0,0,0};
      var base=response(200,new String(malformed,StandardCharsets.ISO_8859_1));
      HttpResponse compressed=proxy(HttpResponse.class,(p,m,a)->switch(m.getName()){
        case "body" -> bytes(malformed);
        case "headerValue" -> a[0].equals("Content-Encoding")?"gzip":null;
        default -> m.invoke(base,a);
      });
      panel.offer(request("https://budget.test/broken","GET",true,List.of()),compressed);drain(panel);
      check(panel.retries.snapshot().stream().anyMatch(e->e.url().endsWith("/broken")&&e.state()==AnalysisRetries.State.FAILED)
          && panel.analysisBudget.metrics().queuedBytes()==0&&panel.analysisBudget.metrics().workingBytes()==0,
          "decode failure releases input, restoration and expansion reservations");
    } finally {panel.close();}
  }

  static void appendMetrics() throws Exception {
    try(var disk = new DiskTrafficStore()) {
      var request = request("https://budget.test/store","GET",true,List.of());
      disk.append(request,response(200,"exact"));
      HttpRequest broken=proxy(HttpRequest.class,(p,m,a)->{
        if(m.getName().equals("toByteArray"))throw new IllegalArgumentException("fixture");
        return m.invoke(request,a);
      });
      try{disk.append(broken,response(200,"x"));throw new AssertionError("failure expected");}
      catch(IllegalArgumentException expected){}
      var metrics=disk.appendMetrics();
      check(metrics.attempts()==2&&metrics.failures()==1&&metrics.total().samples()==2
          &&metrics.lockWait().samples()==2&&metrics.prepare().samples()==2&&metrics.write().samples()==1,
          "append timings distinguish lock wait, packet preparation and actual disk write attempts");
      check(disk.size()==1&&disk.appendDiagnostics().contains("영구 매체 flush 시간 아님"),
          "failed preparation adds no record and diagnostics describe OS-return timing accurately");
    }
  }

  static Object queuedInput(Object task) throws Exception {
    for(Field f:task.getClass().getDeclaredFields()) {
      f.setAccessible(true);Object value=f.get(task);
      if(value instanceof Runnable action)for(Field captured:action.getClass().getDeclaredFields()) {
        captured.setAccessible(true);Object input=captured.get(action);
        if(input instanceof ResponseAnalysisInput)return input;
      }
    }
    throw new AssertionError("queued analysis input was not found");
  }
  static void await(CountDownLatch entered,CountDownLatch release) {
    entered.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
  }
  static void drain(ResponseReconPanel panel)throws Exception {
    panel.worker.submit(()->{}).get(10,TimeUnit.SECONDS);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    AtomicBoolean settled = new AtomicBoolean();
    do {
      edt(() -> settled.set(panel.pendingView.get() == null && !panel.viewUpdatePending.get()));
      if (settled.get()) return;
    } while (System.nanoTime() < deadline);
    throw new AssertionError("response view did not finish publishing after analysis completed");
  }
}
