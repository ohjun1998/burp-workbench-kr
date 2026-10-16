package kr.workbench;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

/** Byte pressure, selection retention and cancelled queue lifetime regressions. */
public final class JsMemoryBudgetTest {
  private static int checks;

  private static void check(boolean value, String description) {
    if (!value) throw new AssertionError(description);
    checks++;
    System.out.println("PASS " + description);
  }

  private static void edt(Runnable action) throws Exception {
    SwingUtilities.invokeAndWait(action);
  }

  private static JsAnalysis.Result result(String recovered) {
    return new JsAnalysis.Result(
        List.of(),
        List.of(),
        "bounded result",
        false,
        new JsMiner.Result(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            recovered.isEmpty()
                ? List.of()
                : List.of(new JsMiner.Recovered("map", "file.js", recovered)),
            ""));
  }

  private static void cacheBudget() {
    var small = result("");
    var sample = new JsSourceCache.Entry("x".repeat(1000), "hash", small, null);
    long entryBytes = JsSourceCache.weight("a", sample);
    var cache = new JsSourceCache(entryBytes * 3);
    for (String key : List.of("a", "b", "c"))
      check(
          cache.analysis(key, sample.source(), sample.fingerprint(), small, null).accepted(),
          "cache accepts initial " + key);
    cache.get("a");
    var change = cache.analysis("d", sample.source(), "hash", small, null);
    check(
        change.evicted().equals(List.of("b")),
        "access order evicts least recently used source as one unit");
    check(cache.bytes() <= cache.budget(), "all source and result bytes stay within one budget");
    cache.select("c");
    cache.analysis("e", sample.source(), "hash", small, null);
    cache.analysis("f", sample.source(), "hash", small, null);
    check(cache.result("c") == small, "selected source survives repeated background eviction");
    long before = cache.bytes();
    int count = cache.snapshot().size();
    change =
        cache.analysis("z", "huge".repeat(10000), "hash", result("recover".repeat(1000)), null);
    check(
        !change.accepted() && cache.bytes() == before && cache.snapshot().size() == count,
        "oversize update rejects atomically without evicting existing sources");

    var packet = new String[] {"request".repeat(50), "raw".repeat(100), "decoded".repeat(100)};
    var rich =
        new JsSourceCache.Entry(sample.source(), "hash", result("original".repeat(100)), packet);
    check(
        JsSourceCache.weight("a", rich) > entryBytes + 3000,
        "packet previews and recovered text contribute to shared byte accounting");
    var mapped=new JsAnalysis.Result(List.of(),List.of(),"draft",false,small.miner(),java.util.Map.of("draft",new JsRequestDraft.Draft("https://test/","POST","x".repeat(9000))));
    check(JsSourceCache.weight("a",new JsSourceCache.Entry(sample.source(),"hash",mapped,null))>entryBytes+18000,"mapped request bodies count toward the existing cache budget");
    var fits = new JsSourceCache(JsSourceCache.weight("a", rich));
    check(
        fits.analysis("a", rich.source(), rich.fingerprint(), rich.result(), packet).accepted(),
        "combined source preview and recovered result fits exact budget");
    check(
        !fits.sourceOnly("map", "map text".repeat(1000)).accepted()
            || fits.bytes() <= fits.budget(),
        "additional source maps use the same budget");
    fits.clear();
    check(fits.bytes() == 0 && fits.snapshot().isEmpty(), "clear releases every cache category");

    var bounded = new JsSourceCache(1024 * 1024);
    for (int i = 0; i < 101; i++) bounded.analysis("file" + i, "x", "hash", small, null);
    check(
        bounded.snapshot().size() == 100 && bounded.result("file0") == null,
        "existing 100-result limit remains in addition to byte limit");
  }

  private static void queueBudget() throws Exception {
    var taskErrors = new AtomicInteger();
    var executor =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(100),
            action -> {
              Thread thread = new Thread(action, "JS-memory-fixture");
              thread.setUncaughtExceptionHandler(
                  (failedThread, failure) -> taskErrors.incrementAndGet());
              return thread;
            });
    var gate = new CountDownLatch(1);
    executor.execute(
        () -> {
          try {
            gate.await();
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
        });
    var queue = new JsWorkQueue(executor, 1000);
    try {
      check(queue.offer(() -> {}, 600), "queue accepts within retained-byte budget");
      check(
          !queue.offer(() -> {}, 500) && queue.count() == 1 && queue.bytes() == 600,
          "queue byte rejection does not leak reservations");
      check(
          queue.offer(() -> {}, 400) && queue.bytes() == 1000, "queue accepts exact byte boundary");
      queue.cancelQueued();
      check(
          queue.bytes() == 0 && queue.count() == 0 && executor.getQueue().isEmpty(),
          "clear immediately releases queued payloads and counters before running task ends");
      gate.countDown();
      check(
          queue.offer(
              () -> {
                throw new IllegalStateException("intentional fixture");
              },
              300),
          "queue accepts exception fixture");
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
      check(
          queue.count() == 0 && queue.bytes() == 0,
          "exceptional task releases reservation exactly once");
    } finally {
      gate.countDown();
      queue.close();
    }
    check(
        !queue.offer(() -> {}, 128) && queue.bytes() == 0 && queue.count() == 0,
        "shutdown rejection releases count and byte reservation");
  }

  private static void panelSelection() throws Exception {
    var panel = new JsAnalysisPanel(null, () -> {}, 16000, JsWorkQueue.DEFAULT_BUDGET);
    try {
      for (int i = 0; i < 5; i++) {
        int id = i;
        edt(
            () ->
                panel.load(
                    "fetch('/source" + id + "');" + " ".repeat(2200),
                    "https://test/source" + id + ".js"));
        JsAnalysisPanelTest.waitDone(panel);
      }
      edt(
          () -> {
            check(
                panel.cachedBytes() <= 16000 && panel.sources.getItemCount() < 5,
                "panel applies byte eviction to source selector and history");
            check(
                panel.sources.getSelectedItem().equals("https://test/source4.js")
                    && panel.maskedReport().contains("/source4"),
                "manual selection and findings remain consistent after eviction");
            panel.navigateSource("https://test/source0.js", 1, "fetch");
            check(
                panel.status.getText().contains("보관 한도"),
                "navigation explains an evicted source instead of showing stale offsets");
            panel.navigateSource("https://test/source4.js", 1, "fetch");
            check(
                panel.input.getText().contains("/source4"),
                "retained source navigation still restores original source");
            check(
                panel.historyModel.getRowCount() == panel.sources.getItemCount(),
                "eviction keeps history and source selector aligned");
          });
    } finally {
      edt(panel::close);
    }
  }

  private static void detachedQueueAndClear() throws Exception {
    var panel = new JsAnalysisPanel(null, () -> {}, JsSourceCache.DEFAULT_BUDGET, 40000);
    var executor = (ThreadPoolExecutor) WorkbenchTest.get(panel, "worker");
    var gate = new CountDownLatch(1);
    executor.execute(
        () -> {
          try {
            gate.await();
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
        });
    var afterOffer = new AtomicBoolean();
    var delegateRequest = WorkbenchTest.request("https://test/detached.js", "GET", true, List.of());
    var delegateResponse = WorkbenchTest.response(200, "fetch('/detached');" + " ".repeat(10000));
    HttpRequest request =
        WorkbenchTest.proxy(
            HttpRequest.class,
            (proxy, method, args) -> {
              if (afterOffer.get()) throw new AssertionError("queued task accessed host request");
              return method.invoke(delegateRequest, args);
            });
    HttpResponse response =
        WorkbenchTest.proxy(
            HttpResponse.class,
            (proxy, method, args) -> {
              if (afterOffer.get()) throw new AssertionError("queued task accessed host response");
              return method.invoke(delegateResponse, args);
            });
    try {
      check(panel.offer(request, response), "bounded packet snapshot accepted for queued analysis");
      afterOffer.set(true);
      check(
          !panel.offer(delegateRequest, delegateResponse),
          "byte saturation returns retryable History rejection");
      check(
          panel.queuedBytes() > 0 && panel.queuedBytes() <= 40000,
          "queued retained bytes remain bounded");
      gate.countDown();
      JsAnalysisPanelTest.waitDone(panel);
      check(
          panel.failed.get() == 0 && panel.maskedReport().contains("/detached"),
          "worker analyzes detached snapshot without rereading host packet objects");
      check(panel.queuedBytes() == 0 && panel.idle(), "completed analysis releases queue bytes");
      edt(panel::clear);
      check(
          panel.cachedBytes() == 0 && panel.sources.getItemCount() == 0,
          "clear releases completed cache and UI history");
    } finally {
      gate.countDown();
      edt(panel::close);
    }

    var blocked = new JsAnalysisPanel(null, () -> {});
    var blockedWorker = (ThreadPoolExecutor) WorkbenchTest.get(blocked, "worker");
    var pending = new CountDownLatch(1);
    blockedWorker.execute(
        () -> {
          try {
            pending.await();
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
        });
    try {
      check(
          blocked.offer(delegateRequest, delegateResponse),
          "pending snapshot queued for clear regression");
      edt(blocked::clear);
      check(
          blocked.queuedBytes() == 0 && blocked.idle(),
          "panel clear releases queued snapshot before gate opens");
      pending.countDown();
      JsAnalysisPanelTest.waitDone(blocked);
      check(
          blocked.sources.getItemCount() == 0,
          "cleared pending analysis never restores stale source");
    } finally {
      pending.countDown();
      edt(blocked::close);
    }
  }

  private static void boundedPreviewAndRecovery() throws Exception {
    var packet =
        WorkbenchTest.proxy(
            burp.api.montoya.core.ByteArray.class,
            (proxy, method, args) -> {
              return switch (method.getName()) {
                case "length" -> 10 * 1024 * 1024;
                case "subArray" -> {
                  if (!args[0].equals(0) || !args[1].equals(65536))
                    throw new AssertionError("unexpected preview slice");
                  yield WorkbenchTest.bytes(
                      "x".repeat(65536).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                case "getBytes" ->
                    throw new AssertionError("large packet must not be copied in full for preview");
                default -> WorkbenchTest.def(method);
              };
            });
    String preview = JsAnalysisPanel.packetPreview(packet);
    check(
        preview.startsWith("xxx") && preview.contains("64 KiB 제한") && preview.length() < 66000,
        "large HTTP preview copies only a bounded host byte slice and labels truncation");
    byte[] korean = "한글".repeat(20000).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var koreanPacket =
        WorkbenchTest.proxy(
            burp.api.montoya.core.ByteArray.class,
            (proxy, method, args) -> {
              return switch (method.getName()) {
                case "length" -> korean.length;
                case "subArray" -> WorkbenchTest.bytes(java.util.Arrays.copyOf(korean, 65536));
                default -> WorkbenchTest.def(method);
              };
            });
    check(
        JsAnalysisPanel.packetPreview(koreanPacket).startsWith("한글한글"),
        "bounded preview preserves Korean text when byte boundary splits a UTF-8 character");
    var panel = new JsAnalysisPanel(null, () -> {});
    try {
      String map =
          "{\"version\":3,\"sources\":[\"original.js\"],\"sourcesContent\":[\"fetch('/recovered');\"]}";
      edt(() -> panel.load(map, "https://test/app.js.map"));
      JsAnalysisPanelTest.waitDone(panel);
      edt(
          () -> {
            panel.navigateSource("https://test/app.js.map", 1, "source map: original.js");
            check(
                panel.input.getText().equals("fetch('/recovered');"),
                "recovered source navigation reads retained content from the unified cache");
          });
    } finally {
      edt(panel::close);
    }
  }

  private static final class PreviewFixture implements AutoCloseable {
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    final AtomicInteger requests = new AtomicInteger(), displayedRemote = new AtomicInteger();
    final JsAnalysisPanel panel;

    PreviewFixture() throws Exception {
      var http =
          WorkbenchTest.proxy(
              burp.api.montoya.http.Http.class,
              (proxy, method, args) -> {
                if (!method.getName().equals("sendRequest")) return WorkbenchTest.def(method);
                HttpRequest request = (HttpRequest) args[0];
                if (requests.incrementAndGet() == 1) {
                  entered.countDown();
                  if (!release.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("preview fixture timeout");
                }
                var response = WorkbenchTest.response(200, "FETCHED:" + request.url());
                return WorkbenchTest.proxy(
                    burp.api.montoya.http.message.HttpRequestResponse.class,
                    (p, m, a) ->
                        switch (m.getName()) {
                          case "request" -> request;
                          case "response" -> response;
                          default -> WorkbenchTest.def(m);
                        });
              });
      var api =
          WorkbenchTest.proxy(
              burp.api.montoya.MontoyaApi.class,
              (proxy, method, args) ->
                  method.getName().equals("http") ? http : WorkbenchTest.def(method));
      panel = new JsAnalysisPanel(api, () -> {});
      edt(
          () -> {
            panel.rememberPacket("https://test/a.js", "REQUEST_A", "RESPONSE_A", "RESPONSE_A");
            panel.load("require('@wb/pkg');", "https://test/a.js");
          });
      JsAnalysisPanelTest.waitDone(panel);
      edt(
          () -> {
            panel.rememberPacket("https://test/b.js", "REQUEST_B", "RESPONSE_B", "RESPONSE_B");
            panel.load("fetch('/b');", "https://test/b.js");
          });
      JsAnalysisPanelTest.waitDone(panel);
      edt(
          () -> {
            panel.sources.setSelectedItem("https://test/a.js");
            panel
                .responsePacket
                .text
                .getDocument()
                .addDocumentListener(
                    new javax.swing.event.DocumentListener() {
                      void changed() {
                        if (panel.responsePacket.text.getText().contains("FETCHED:"))
                          displayedRemote.incrementAndGet();
                      }

                      public void insertUpdate(javax.swing.event.DocumentEvent event) {
                        changed();
                      }

                      public void removeUpdate(javax.swing.event.DocumentEvent event) {
                        changed();
                      }

                      public void changedUpdate(javax.swing.event.DocumentEvent event) {
                        changed();
                      }
                    });
          });
    }

    @Override
    public void close() throws Exception {
      release.countDown();
      edt(panel::close);
    }
  }

  private static void selectionPreviewRaces() throws Exception {
    var previousFactory = burp.api.montoya.internal.ObjectFactoryLocator.FACTORY;
    burp.api.montoya.internal.ObjectFactoryLocator.FACTORY =
        WorkbenchTest.proxy(
            burp.api.montoya.internal.MontoyaObjectFactory.class,
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "httpRequestFromUrl" ->
                      WorkbenchTest.request((String) args[0], "GET", true, List.of());
                  case "requestOptions" ->
                      WorkbenchTest.proxy(
                          burp.api.montoya.http.RequestOptions.class, (p, m, a) -> p);
                  default -> WorkbenchTest.def(method);
                });
    try {
      try (var fixture = new PreviewFixture()) {
        edt(fixture.panel::discover);
        check(
            fixture.entered.await(5, TimeUnit.SECONDS),
            "map preview fixture begins request for source A");
        edt(() -> fixture.panel.sources.setSelectedItem("https://test/b.js"));
        fixture.release.countDown();
        JsAnalysisPanelTest.waitDone(fixture.panel);
        check(
            fixture.requests.get() >= 2
                && fixture.displayedRemote.get() == 0
                && fixture.panel.responsePacket.text.getText().equals("RESPONSE_B"),
            "completed and subsequent source A map requests never overwrite selected B packets");
      }
      try (var fixture = new PreviewFixture()) {
        edt(fixture.panel::checkRegistry);
        check(
            fixture.entered.await(5, TimeUnit.SECONDS),
            "NPM preview fixture begins request for source A");
        edt(
            () -> {
              fixture.panel.sources.setSelectedItem("https://test/b.js");
              fixture.panel.sources.setSelectedItem("https://test/a.js");
            });
        fixture.release.countDown();
        JsAnalysisPanelTest.waitDone(fixture.panel);
        check(
            fixture.displayedRemote.get() == 0
                && fixture.panel.responsePacket.text.getText().equals("RESPONSE_A"),
            "selection generation rejects stale NPM preview after A to B to A navigation");
      }
      try (var fixture = new PreviewFixture()) {
        var executor = (ThreadPoolExecutor) WorkbenchTest.get(fixture.panel, "worker");
        var gate = new CountDownLatch(1);
        executor.execute(
            () -> {
              try {
                gate.await();
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
              }
            });
        try {
          edt(fixture.panel::discover);
          edt(() -> fixture.panel.sources.setSelectedItem("https://test/b.js"));
          gate.countDown();
          check(
              fixture.entered.await(5, TimeUnit.SECONDS),
              "queued source A request starts after selecting B");
          fixture.release.countDown();
          JsAnalysisPanelTest.waitDone(fixture.panel);
          check(
              fixture.displayedRemote.get() == 0,
              "preview ownership is captured at action time rather than request start");
        } finally {
          gate.countDown();
        }
      }
      try (var fixture = new PreviewFixture()) {
        edt(fixture.panel::checkRegistry);
        check(
            fixture.entered.await(5, TimeUnit.SECONDS),
            "unchanged selection preview fixture begins");
        fixture.release.countDown();
        JsAnalysisPanelTest.waitDone(fixture.panel);
        check(
            fixture.displayedRemote.get() > 0,
            "matching source selection still displays requested remote preview");
      }
    } finally {
      burp.api.montoya.internal.ObjectFactoryLocator.FACTORY = previousFactory;
    }
  }

  private static void lateDiscoveryFailure(boolean close) throws Exception {
    var panel =
        new JsAnalysisPanel(
            WorkbenchTest.proxy(
                burp.api.montoya.MontoyaApi.class,
                (proxy, method, args) -> WorkbenchTest.def(method)),
            () -> {});
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var fail = new AtomicBoolean();
    var maps =
        new java.util.AbstractList<JsMiner.MapRef>() {
          public JsMiner.MapRef get(int index) {
            throw new IndexOutOfBoundsException();
          }

          public int size() {
            return 0;
          }

          @Override
          public Object[] toArray() {
            if (fail.get()) {
              entered.countDown();
              // Model a provider that finishes after cancellation instead of honoring interruption.
              boolean interrupted = false;
              while (true) {
                try {
                  release.await();
                  break;
                } catch (InterruptedException ex) {
                  interrupted = true;
                }
              }
              if (interrupted) Thread.interrupted();
              throw new IllegalStateException("late discovery fixture");
            }
            return new Object[0];
          }
        };
    try {
      edt(() -> panel.load("fetch('/a');", "https://test/a.js"));
      JsAnalysisPanelTest.waitDone(panel);
      var cache = (JsSourceCache) WorkbenchTest.get(panel, "cache");
      var original = cache.result("https://test/a.js");
      cache.result(
          "https://test/a.js",
          new JsAnalysis.Result(
              original.endpoints(),
              original.secrets(),
              original.status(),
              false,
              new JsMiner.Result(List.of(), List.of(), List.of(), maps, List.of(), "")));
      fail.set(true);
      edt(panel::discover);
      check(
          entered.await(5, TimeUnit.SECONDS),
          "late discovery failure fixture enters before " + (close ? "close" : "clear"));
      edt(
          () -> {
            if (close) panel.close();
            else panel.clear();
            panel.status.setText("NEW_STATE");
          });
      release.countDown();
      var executor = (ThreadPoolExecutor) WorkbenchTest.get(panel, "worker");
      if (close) {
        check(
            executor.awaitTermination(5, TimeUnit.SECONDS),
            "closed discovery worker terminates after late error");
        edt(() -> {});
      } else JsAnalysisPanelTest.waitDone(panel);
      check(
          panel.status.getText().equals("NEW_STATE"),
          "late discovery error cannot overwrite status after " + (close ? "close" : "clear"));
    } finally {
      release.countDown();
      edt(panel::close);
    }
  }

  private static void truncationVisibility() throws Exception {
    var panel = new JsAnalysisPanel(null, () -> {});
    try {
      edt(
          () ->
              panel.load(
                  "fetch('/bounded');" + " ".repeat(JsAnalysis.SOURCE_LIMIT),
                  "https://test/large.js"));
      JsAnalysisPanelTest.waitDone(panel);
      check(
          panel.truncated.get() == 1 && panel.progress.getText().contains("절단 1"),
          "truncation stays explicit after bounded manual queue input");
      check(
          panel.progress.getText().contains("캐시 추정") && panel.progress.getText().contains("MiB"),
          "Korean progress exposes queue bytes and estimated cache budget");
    } finally {
      edt(panel::close);
    }
  }

  public static void main(String[] args) throws Exception {
    cacheBudget();
    queueBudget();
    panelSelection();
    detachedQueueAndClear();
    boundedPreviewAndRecovery();
    truncationVisibility();
    selectionPreviewRaces();
    lateDiscoveryFailure(false);
    lateDiscoveryFailure(true);
    System.out.println("JsMemoryBudgetTest: " + checks + " checks passed");
  }
}
