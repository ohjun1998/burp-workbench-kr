package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.repeater.Repeater;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Real loopback model transport with fake Montoya originals; never contacts a target. */
public final class LocalLlmPanelTest extends WorkbenchTest {
  static void idle(LocalLlmPanel panel) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < until) {
      AtomicBoolean running = new AtomicBoolean(); edt(() -> running.set(panel.running));
      if (!running.get()) { edt(() -> {}); return; }
      Thread.sleep(10);
    }
    throw new AssertionError("local AI UI did not finish");
  }
  static String output(String report) {
    return MetadataExport.json(Map.of("rows", List.of(
        Map.of("title", "주문 조회", "category", "주문", "source_ids", List.of("B"), "observation", "주문 응답 관측", "uncertainty", "소유 정책 미확인", "next_check", "테스트 계정 소유 관계 확인"),
        Map.of("title", "<html><img src='https://invalid.invalid/a'>", "category", "공유 기능", "source_ids", List.of("A", "B"), "observation", "두 원본을 함께 검토", "uncertainty", "공유 API 가능", "next_check", "기능 맥락 확인")), "report", report));
  }
  static HttpRequestResponse pair(burp.api.montoya.http.message.requests.HttpRequest q,
      burp.api.montoya.http.message.responses.HttpResponse s) {
    return proxy(HttpRequestResponse.class, (p,m,a) -> switch (m.getName()) {
      case "request" -> q; case "response" -> s; default -> def(m);
    });
  }
  static void hybridReview(MontoyaApi api, HttpRequestResponse a, HttpRequestResponse b,
      LocalLlmTasks.Input input, String localUrl, AtomicInteger localCalls) throws Exception {
    // POSIX executable fixture exercises the actual programmatic CLI transport. Routing assertions
    // above remain platform-independent; native Windows CLI execution has its own transport tests.
    if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
    java.nio.file.Path folder = java.nio.file.Files.createTempDirectory("wb-hybrid-panel-");
    java.nio.file.Path executable = folder.resolve("codex-fixture"), observed = folder.resolve("observed-input.txt"), executions = folder.resolve("executions.txt");
    String encoded = Base64.getEncoder().encodeToString(output("").getBytes(StandardCharsets.UTF_8));
    String script = "#!/usr/bin/python3\nimport sys,pathlib,base64,json\n"
        + "root=pathlib.Path(__file__).parent\n"
        + "if sys.argv[1:]==['login','status']:\n print('Logged in using ChatGPT');sys.exit(0)\n"
        + "data=sys.stdin.read()\n(root/'observed-input.txt').write_text(data)\n"
        + "with (root/'executions.txt').open('a') as f:f.write('exec\\n')\n"
        + "pathlib.Path(sys.argv[sys.argv.index('--output-last-message')+1]).write_text(base64.b64decode('" + encoded + "').decode())\n"
        + "print(json.dumps({'type':'turn.completed','usage':{'input_tokens':77,'output_tokens':33}}))\n";
    java.nio.file.Files.writeString(executable, script); executable.toFile().setExecutable(true);
    AtomicReference<LocalLlmPanel> ref = new AtomicReference<>(); int initial = localCalls.get();
    try {
      edt(() -> {
        var panel = new LocalLlmPanel(api); ref.set(panel); panel.codexPath.setText(executable.toString()); panel.baseUrl.setText(localUrl);
        panel.prepare(input, Map.of("A", a, "B", b));
        check(panel.provider.getSelectedItem() == LocalLlmPanel.Provider.AUTO && panel.effectiveProvider() == LocalLlmPanel.Provider.SUBSCRIPTION,
            "hybrid default uses subscription for functional grouping");
        check(panel.destination.getText().contains("외부 전송") && panel.preview.getText().contains("ChatGPT 구독") && panel.preview.getText().contains("외부 전송"),
            "external destination is visible in header and exact-input preview before dispatch");
        check(!java.nio.file.Files.exists(executions) && localCalls.get() == initial, "hybrid preparation neither starts CLI nor uploads packets");
        panel.provider.setSelectedItem(LocalLlmPanel.Provider.LOCAL);
        check(panel.preview.getText().contains("로컬 Halogen") && !panel.preview.getText().contains("외부 전송"), "explicit local override updates destination before analysis");
        panel.analyze();
      }); idle(ref.get());
      edt(() -> {
        var panel = ref.get(); check(panel.resultProvider == LocalLlmPanel.Provider.LOCAL && localCalls.get() == initial + 1, "manual local override executes actual Halogen fixture");
        panel.provider.setSelectedItem(LocalLlmPanel.Provider.SUBSCRIPTION); panel.apiKey.setText("halogen-only-secret");
        panel.analyze();
      }); idle(ref.get());
      String received = java.nio.file.Files.readString(observed);
      check(!received.contains("halogen-only-secret") && !received.contains("private-token"), "Codex receives sanitized prompt without Halogen credential");
      int cliCount = java.nio.file.Files.readAllLines(executions).size();
      edt(() -> {
        var panel = ref.get(); check(panel.result != null && panel.resultProvider == LocalLlmPanel.Provider.SUBSCRIPTION && panel.status.getText().contains("ChatGPT 구독"),
            "subscription execution result identifies its backend");
        panel.provider.setSelectedItem(LocalLlmPanel.Provider.LOCAL); panel.apiKey.setText(""); panel.analyze();
        check(!panel.running && panel.status.getText().contains("캐시") && panel.resultProvider == LocalLlmPanel.Provider.LOCAL,
            "switching back selects only matching local-provider cache");
        panel.provider.setSelectedItem(LocalLlmPanel.Provider.SUBSCRIPTION); panel.analyze();
        check(!panel.running && panel.status.getText().contains("캐시") && panel.resultProvider == LocalLlmPanel.Provider.SUBSCRIPTION,
            "subscription cache is separated from local-provider cache");
        panel.task.setSelectedItem(LocalLlmTasks.Task.RESPONSE);
        check(panel.effectiveProvider() == LocalLlmPanel.Provider.SUBSCRIPTION, "explicit subscription override also supports other review tasks");
        panel.baseUrl.setText("https://public.invalid/v1"); panel.analyze();
      }); idle(ref.get());
      edt(() -> {
        var panel = ref.get(); check(panel.result != null && panel.resultProvider == LocalLlmPanel.Provider.SUBSCRIPTION,
            "subscription execution does not validate or use unrelated Halogen endpoint/key settings");
        panel.resetProject(); check(panel.input == null && !panel.running && panel.apiKey.getPassword().length == 0,
            "hybrid reset cancels both execution modes and clears prepared secrets");
      });
      check(localCalls.get() == initial + 1 && java.nio.file.Files.readAllLines(executions).size() == cliCount + 1,
          "provider cache hits emit no duplicate local or subscription request");
    } finally {
      if (ref.get() != null) edt(ref.get()::close);
      try (var paths = java.nio.file.Files.walk(folder)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path); }
    }
  }
  public static void main(String[] args) throws Exception {
    for (var task : LocalLlmTasks.Task.values()) {
      var expected = task == LocalLlmTasks.Task.FUNCTIONS || task == LocalLlmTasks.Task.REPORT
          ? LocalLlmPanel.Provider.SUBSCRIPTION : LocalLlmPanel.Provider.LOCAL;
      check(LocalLlmPanel.providerFor(task) == expected, "automatic provider routing matches task " + task.name());
    }
    AtomicInteger calls = new AtomicInteger(), targetSends = new AtomicInteger(), prepared = new AtomicInteger();
    AtomicReference<String> modelReport = new AtomicReference<>("");
    AtomicReference<CountDownLatch> gate = new AtomicReference<>();
    AtomicReference<CountDownLatch> entered = new AtomicReference<>();
    AtomicReference<Object> repeated = new AtomicReference<>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ExecutorService httpWorkers = Executors.newCachedThreadPool(); server.setExecutor(httpWorkers);
    server.createContext("/v1/chat/completions", exchange -> {
      calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
      var waiting = gate.get(); var started = entered.get(); if (started != null) started.countDown();
      if (waiting != null) try { waiting.await(8, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      byte[] body = MetadataExport.json(Map.of("choices", List.of(Map.of("message", Map.of("content", output(modelReport.get())), "finish_reason", "stop")),
          "usage", Map.of("prompt_tokens", 123, "completion_tokens", 45), "timings", Map.of("prompt_ms", 5))).getBytes(StandardCharsets.UTF_8);
      try { exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); }
      catch (java.io.IOException cancelled) {} finally { exchange.close(); }
    });
    server.start();
    var http = proxy(Http.class, (p,m,a) -> { if (m.getName().equals("sendRequest")) targetSends.incrementAndGet(); return def(m); });
    var repeat = proxy(Repeater.class, (p,m,a) -> { if (m.getName().equals("sendToRepeater")) { prepared.incrementAndGet(); repeated.set(a[0]); } return def(m); });
    AtomicReference<String> projectId = new AtomicReference<>("11111111-1111-1111-1111-111111111111");
    var persisted = proxy(PersistedObject.class, (p,m,a0) -> m.getName().equals("getString") && WorkspaceResume.PROJECT_KEY.equals(a0[0]) ? projectId.get() : def(m));
    var persistence = proxy(Persistence.class, (p,m,a0) -> m.getName().equals("extensionData") ? persisted : def(m));
    MontoyaApi api = proxy(MontoyaApi.class, (p,m,a) -> switch (m.getName()) { case "http" -> http; case "repeater" -> repeat; case "persistence" -> persistence; default -> def(m); });
    var a = pair(request("https://example.com/a?token=private-token", "GET", true, List.of(header("Authorization", "Bearer private-token"))), response(200, "{\"ownerId\":1}"));
    var b = pair(request("https://example.com/b", "GET", true, List.of()), response(200, "{\"ownerId\":2}"));
    var sources = List.of(LocalLlmTasks.capture("A", a, "계정 A"), LocalLlmTasks.capture("B", b, "계정 B"));
    var input = new LocalLlmTasks.Input(LocalLlmTasks.Task.FUNCTIONS, "각 기능과 공유 API를 분류", sources);
    AtomicReference<LocalLlmPanel> ref = new AtomicReference<>();
    AtomicReference<List<HttpRequestResponse>> captured = new AtomicReference<>();
    AtomicReference<String> note = new AtomicReference<>(), source = new AtomicReference<>(), draft = new AtomicReference<>();
    try {
      edt(() -> {
        var panel = new LocalLlmPanel(api); ref.set(panel);
        panel.provider.setSelectedItem(LocalLlmPanel.Provider.LOCAL);
        panel.baseUrl.setText("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        panel.evidenceSink = (packets, text) -> { captured.set(packets); note.set(text); };
        panel.sourceNavigator = source::set; panel.reportSink = draft::set;
        panel.prepare(input, Map.of("A", a, "B", b));
        check(calls.get() == 0 && targetSends.get() == 0, "construction and preparation never contact model or target");
        check(panel.preview.getText().contains("REDACTED") && !panel.preview.getText().contains("private-token"), "visible model preview is redacted exact bounded input");
        panel.analyze(); check(panel.running && panel.stop.isEnabled() && !panel.analyze.isEnabled(), "explicit analysis exposes stop and disables duplicate dispatch");
      });
      var panel = ref.get(); idle(panel);
      edt(() -> {
        check(panel.result != null && panel.result.rows().size() == 2 && panel.resultModel.getRowCount() == 2, "real model reply becomes grounded multi-function proposals");
        check(panel.status.getText().contains("123") && panel.status.getText().contains("45"), "completion shows actual model input and output counts");
        check(panel.metrics.getText().contains("입력 처리(ms): 5") && panel.metrics.getText().contains("Workbench 대기"), "engine input timing and measured queue delay are inspectable");
        check(LocalLlmPanel.safeFailure(new java.io.IOException("출력·추론 예산이 소진됐습니다(finish_reason=length). 분석 미완료 · 예산 또는 추론 수준을 조정하세요.")).contains("예산"), "owned incomplete-budget diagnostic remains actionable");
        check(!LocalLlmPanel.safeFailure(new java.io.IOException("private-token leaked")).contains("private-token"), "arbitrary transport exception text is never exposed");
        panel.table.getRowSorter().toggleSortOrder(0);
        int bRow = -1; for (int row = 0; row < panel.table.getRowCount(); row++) if (panel.table.getValueAt(row, 0).equals("주문 조회")) bRow = row;
        panel.table.setRowSelectionInterval(bRow, bRow); panel.inspectSource.doClick(); panel.prepareRepeater(); panel.captureEvidence();
        check(source.get().equals("B") && repeated.get() == b.request(), "sorted proposal routes selected original identity to source and Repeater");
        check(captured.get().equals(List.of(b)) && note.get().contains("미검증"), "evidence gets complete original plus explicitly unverified AI note");
        check(prepared.get() == 1 && targetSends.get() == 0, "Repeater preparation emits no target request");
        for (int row = 0; row < panel.table.getRowCount(); row++) if (panel.table.getValueAt(row, 0).toString().startsWith("<html>")) {
          var component = (JComponent) panel.table.getCellRenderer(row, 0).getTableCellRendererComponent(panel.table, panel.table.getValueAt(row, 0), false, false, row, 0);
          check(Boolean.TRUE.equals(component.getClientProperty("html.disable")), "untrusted classification text cannot activate Swing HTML");
        }
        panel.analyze(); check(!panel.running && panel.status.getText().contains("캐시"), "unchanged analysis reuses bounded memory cache");
      });
      check(calls.get() == 1, "cache hit performs no second model request");
      WorkbenchScope scope = WorkbenchScope.install(api); scope.add("https://example.com/a", false);
      edt(() -> {
        panel.sourceChoice.setSelectedItem("B");
        rejects(panel::prepareRepeater, "scope narrowing blocks Repeater preparation");
        rejects(panel::captureEvidence, "scope narrowing blocks evidence transfer");
        rejects(panel::analyze, "scope narrowing blocks cached and new analysis");
      });
      check(calls.get() == 1, "out-of-scope review never dispatches to model"); WorkbenchScope.release(api);
      CountDownLatch waiting = new CountDownLatch(1), started = new CountDownLatch(1); gate.set(waiting); entered.set(started);
      edt(() -> { panel.context.append(" 새 맥락"); panel.analyze(); });
      check(started.await(3, TimeUnit.SECONDS), "slow model test reached actual HTTP handler");
      edt(() -> { panel.context.append(" 입력 변경"); check(!panel.running && panel.result == null, "editing context cancels active analysis and invalidates proposals immediately"); });
      waiting.countDown(); gate.set(null); entered.set(null); Thread.sleep(100); edt(() -> {});
      edt(() -> check(panel.result == null, "cancelled old response cannot overwrite changed input"));
      modelReport.set("원본 A와 B에서 주문 조회를 관측했습니다. 권한 정책은 미확인입니다. [A] [B]");
      edt(() -> { panel.task.setSelectedItem(LocalLlmTasks.Task.REPORT); panel.analyze(); }); idle(panel);
      edt(() -> {
        check(panel.result != null && panel.appendReport.isEnabled(), "report task exposes grounded draft only after successful parse");
        panel.appendReport(); check(draft.get().contains("[A]") && draft.get().contains("수동 검토 필요"), "explicit report callback carries source references and review label");
        int beforeProjectChange = calls.get();
        projectId.set("22222222-2222-2222-2222-222222222222");
        rejects(panel::prepareRepeater, "same-page project switch blocks old Repeater source");
        check(panel.input == null && panel.result == null && panel.originals.isEmpty() && panel.cache.isEmpty(), "project identity guard releases old inputs even without navigation");
        check(calls.get() == beforeProjectChange, "project source guard emits no model request");
        projectId.set("11111111-1111-1111-1111-111111111111"); panel.prepare(input, Map.of("A", a, "B", b));
        projectId.set("22222222-2222-2222-2222-222222222222");
        rejects(panel::analyze, "same-page project switch blocks model dispatch");
        projectId.set("11111111-1111-1111-1111-111111111111"); panel.prepare(input, Map.of("A", a, "B", b));
        projectId.set("22222222-2222-2222-2222-222222222222");
        rejects(panel::captureEvidence, "same-page project switch blocks old evidence source");
        projectId.set("11111111-1111-1111-1111-111111111111"); panel.prepare(input, Map.of("A", a, "B", b));
        panel.apiKey.setText("memory-only-key");
        check(panel.result == null && !panel.appendReport.isEnabled(), "credential or effective configuration changes invalidate old actions");
        var replacement = pair(request("https://example.com/b", "GET", true, List.of()), response(200, "{\"ownerId\":2}"));
        panel.prepare(input, Map.of("A", a, "B", replacement));
        check(panel.cache.isEmpty(), "a different immutable original invalidates cache even when sanitized input IDs match");
        panel.resetProject();
        check(panel.input == null && panel.originals.isEmpty() && panel.cache.isEmpty() && panel.apiKey.getPassword().length == 0, "project reset releases originals cache prompts and session credential");
        check(!panel.analyze.isEnabled() && !panel.repeater.isEnabled() && panel.preview.getText().isEmpty(), "reset leaves no stale source or analysis actions");
        panel.close(); check(panel.closed && panel.worker.isShutdown() && !panel.health.isEnabled(), "unload closes worker and all network actions");
      });
      hybridReview(api, a, b, input, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", calls);
      check(targetSends.get() == 0, "all AI review paths preserve zero target requests");
    } finally {
      if (ref.get() != null) edt(ref.get()::close); server.stop(0); httpWorkers.shutdownNow(); WorkbenchScope.release(api);
    }
    System.out.println("LocalLlmPanelTest: " + checks + " checks passed");
  }
}
