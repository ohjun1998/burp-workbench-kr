package kr.workbench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;

/** Fake executable only. Never starts a real Codex inference or reads the CLI's stored credentials. */
public final class CodexSubscriptionClientTest {
  private static int checks;
  private static void check(boolean value, String label) {
    if (!value) throw new AssertionError(label);
    checks++;
  }
  @FunctionalInterface private interface Call { void run() throws Exception; }
  private static String rejected(Call action) throws Exception {
    try { action.run(); throw new AssertionError("expected explicit failure"); }
    catch (IOException expected) { checks++; return expected.getMessage(); }
  }
  public static void main(String[] args) throws Exception {
    validation();
    if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) fakeExecutable();
    System.out.println("CodexSubscriptionClientTest: " + checks + " checks passed");
  }
  private static void validation() throws Exception {
    try (var client = new CodexSubscriptionClient()) {
      check(rejected(() -> client.status("", 5)).equals(CodexSubscriptionClient.EXECUTABLE_INVALID), "empty path rejected");
      check(rejected(() -> client.status("path\nnext", 5)).equals(CodexSubscriptionClient.EXECUTABLE_INVALID), "control path rejected");
      check(rejected(() -> client.status("codex", 0)).equals(CodexSubscriptionClient.SETTINGS_INVALID), "timeout bounds");
      check(rejected(() -> client.complete("codex", "bad\nmodel", 5, "system", "data"))
          .equals(CodexSubscriptionClient.SETTINGS_INVALID), "model controls rejected before process");
      check(rejected(() -> client.complete("codex", "--option", 5, "system", "data"))
          .equals(CodexSubscriptionClient.SETTINGS_INVALID), "model cannot become a CLI option");
      check(rejected(() -> client.complete("codex", "", 5, "system", "x".repeat(CodexSubscriptionClient.INPUT_LIMIT)))
          .equals(CodexSubscriptionClient.INPUT_INVALID), "input bound before process");
      check(CodexSubscriptionClient.safeFailure(new IOException("session-key-private-packet"))
          .equals(CodexSubscriptionClient.FAILED), "unowned errors cannot echo secrets");
      client.close();
      check(rejected(() -> client.status("codex", 5)).equals(CodexSubscriptionClient.CLOSED), "closed client blocks process");
    }
  }
  private static void fakeExecutable() throws Exception {
    Path fixture = Files.createTempDirectory("codex-subscription-fixture-");
    Path executable = fixture.resolve("fake codex.py"), settings = fixture.resolve("fake codex.config.json"),
        log = fixture.resolve("fake codex.log");
    Files.writeString(executable, fake(), StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
    try (var client = new CodexSubscriptionClient()) {
      setting(settings, "chatgpt", "ok", 0, 0);
      Process unsupported = new ProcessBuilder(executable.toString(), "exec", "--strict-config",
          "-c", "tools.view_image=false").redirectErrorStream(true).start();
      try {
        check(unsupported.waitFor(5, TimeUnit.SECONDS), "strict-config fixture rejects legacy override promptly");
        check(unsupported.exitValue() == 2
            && new String(unsupported.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .contains("unknown field `view_image`"), "legacy tools.view_image reproduces configuration-load failure");
      } finally { unsupported.destroyForcibly(); }
      check(client.status(executable.toString(), 5).equals(CodexSubscriptionClient.AUTH_OK), "ChatGPT status is sanitized");
      var result = client.complete(executable.toString(), "fixture-model", 5, "system-value", "packet-json-value");
      check(result.text().contains("fixture-1") && result.finishReason().equals("stop"), "final JSON returned");
      check(result.promptTokens() == 40 && result.completionTokens() == 12 && result.timings().contains("\"usage_available\":true"), "official JSON event usage");
      List<Map<?, ?>> calls = calls(log);
      Map<?, ?> exec = calls.stream().filter(c -> c.get("phase").equals("exec")).findFirst().orElseThrow();
      List<?> arguments = (List<?>) exec.get("args");
      for (String flag : List.of("--ignore-user-config", "--strict-config", "--ephemeral", "--sandbox", "read-only",
          "--skip-git-repo-check", "--output-schema", "--output-last-message", "--json", "fixture-model"))
        check(arguments.contains(flag), "isolated CLI flags supplied");
      check(!arguments.contains("--ignore-rules") && arguments.stream().noneMatch(a -> a.toString().contains("dangerously")), "policy rules and sandbox stay active");
      for (String config : List.of("model_provider=\"openai\"", "forced_login_method=\"chatgpt\"",
          "features.shell_tool=false", "features.apps=false", "features.hooks=false", "features.plugins=false",
          "features.browser_use=false", "features.computer_use=false", "features.view_image=false", "features.multi_agent=false",
          "web_search=\"disabled\"", "mcp_servers={}", "project_doc_max_bytes=0"))
        check(arguments.contains(config), "tools/config isolation supplied");
      check(arguments.stream().noneMatch(a -> a.toString().startsWith("tools.view_image=")),
          "unsupported legacy image-tool override is absent under strict-config");
      check(arguments.get(arguments.size() - 1).equals("-") && exec.get("stdin").toString().contains("system-value")
          && exec.get("stdin").toString().contains("packet-json-value")
          && arguments.stream().noneMatch(a -> a.toString().contains("packet-json-value")), "prompt only goes to stdin");
      List<?> environment = (List<?>) exec.get("env_keys");
      check(environment.stream().noneMatch(e -> Set.of("OPENAI_API_KEY", "CODEX_API_KEY", "OPENAI_BASE_URL",
          "CODEX_EXEC_SERVER_REMOTE_BASE_URL", "CODEX_SESSION_ID", "CODEX_THREAD_ID").contains(e)), "API and cloud-session environment removed");
      check(Objects.equals(exec.get("home"), System.getenv("HOME"))
          && Objects.equals(exec.get("codex_home"), System.getenv("CODEX_HOME")), "existing HOME and CODEX_HOME unchanged");
      check(exec.get("cwd_private").equals(true) && exec.get("schema_private").equals(true)
          && exec.get("output_private").equals(true), "temporary working data has private permissions");
      check(exec.get("additional_properties").equals(false) && ((List<?>) exec.get("required")).containsAll(List.of("rows", "report")), "schema requires grounded rows/report");
      cleanup(calls);

      for (String auth : List.of("api", "mixed", "none")) {
        setting(settings, auth, "ok", 0, 0); Files.deleteIfExists(log);
        check(rejected(() -> client.complete(executable.toString(), "", 5, "system", "data"))
            .equals(CodexSubscriptionClient.AUTH_REQUIRED), "non-subscription auth rejected");
        var attempts = calls(log);
        check(attempts.size() == 1 && attempts.get(0).get("phase").equals("login"), "no exec or API fallback after rejected auth");
        cleanup(attempts);
      }
      for (String mode : List.of("error", "malformed", "missing", "large-output", "large-result")) {
        setting(settings, "chatgpt", mode, 0, 0); Files.deleteIfExists(log);
        String failure = rejected(() -> client.complete(executable.toString(), "", 5, "system", "data"));
        check(!failure.contains("private") && !failure.contains("sensitive-secret"), "failure diagnostics do not echo child output");
        cleanup(calls(log));
      }
      setting(settings, "chatgpt", "refusal", 0, 0);
      check(client.complete(executable.toString(), "", 5, "system", "data").text().contains("refusal"),
          "structured refusal remains data for task-schema validation, never marked as a finding");
      setting(settings, "chatgpt", "ok", .7, .7); Files.deleteIfExists(log);
      long start = System.nanoTime();
      check(rejected(() -> client.complete(executable.toString(), "", 1, "system", "data"))
          .equals(CodexSubscriptionClient.TIMEOUT), "one deadline covers login and generation");
      check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2500, "deadline is not restarted after login");
      cleanup(calls(log));

      setting(settings, "chatgpt", "wait", 0, 0); Files.deleteIfExists(log);
      ExecutorService worker = Executors.newSingleThreadExecutor();
      try {
        Future<String> pending = worker.submit(() -> {
          try { client.complete(executable.toString(), "", 30, "system", "data"); return "success"; }
          catch (Exception failure) { return failure.getMessage(); }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Map<?, ?>> waiting = List.of();
        while (System.nanoTime() < deadline) {
          waiting = Files.exists(log) ? calls(log) : List.of();
          if (waiting.stream().anyMatch(c -> c.get("phase").equals("exec"))) break;
          Thread.sleep(20);
        }
        check(waiting.stream().anyMatch(c -> c.get("phase").equals("exec")), "fixture generation process started");
        client.cancel();
        check(pending.get(3, TimeUnit.SECONDS).equals(CodexSubscriptionClient.CANCELLED), "cancel stops process promptly");
        cleanup(calls(log));
      } finally { worker.shutdownNow(); }
      setting(settings, "chatgpt", "ok", 0, 0);
      check(client.status(executable.toString(), 5).equals(CodexSubscriptionClient.AUTH_OK), "new explicit work after cancel");

      Path marker = fixture.resolve("injection-marker");
      String dangerousPath = executable + " ; touch " + marker;
      check(rejected(() -> client.status(dangerousPath, 5)).equals(CodexSubscriptionClient.EXECUTABLE_INVALID)
          && !Files.exists(marker), "executable input is never interpreted as a shell command");
    } finally {
      try (var files = Files.walk(fixture)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
  }
  private static void cleanup(List<Map<?, ?>> calls) {
    for (Map<?, ?> call : calls) check(!Files.exists(Path.of(call.get("cwd").toString())), "private work directory removed after completion/failure/cancel");
  }
  private static void setting(Path file, String auth, String mode, double loginDelay, double execDelay) throws IOException {
    Files.writeString(file, MetadataExport.json(Map.of("auth", auth, "mode", mode, "login_delay", loginDelay, "exec_delay", execDelay)));
  }
  private static List<Map<?, ?>> calls(Path file) throws IOException {
    if (!Files.exists(file)) return List.of();
    List<Map<?, ?>> calls = new ArrayList<>();
    for (String line : Files.readAllLines(file)) if (!line.isBlank()) calls.add((Map<?, ?>) MinerJson.parse(line));
    return calls;
  }
  private static String fake() {
    return """
#!/usr/bin/python3
import json, os, pathlib, stat, sys, time
script = pathlib.Path(__file__)
cfg = json.loads(script.with_suffix('.config.json').read_text())
log = script.with_suffix('.log')
args = sys.argv[1:]
entry = {'phase': 'login' if args == ['login', 'status'] else 'exec', 'args': args,
         'cwd': os.getcwd(), 'env_keys': list(os.environ), 'home': os.environ.get('HOME'),
         'codex_home': os.environ.get('CODEX_HOME'), 'cwd_private': stat.S_IMODE(os.stat('.').st_mode) == 0o700}
if entry['phase'] == 'login':
    with log.open('a') as out: out.write(json.dumps(entry) + '\\n')
    time.sleep(cfg['login_delay'])
    if cfg['auth'] == 'api': print('Logged in using an API key - sensitive-secret'); sys.exit(0)
    if cfg['auth'] == 'mixed': print('Logged in using ChatGPT\\nAPI key sensitive-secret'); sys.exit(0)
    if cfg['auth'] == 'none': print('private login failure sensitive-secret'); sys.exit(1)
    print('Logged in using ChatGPT', file=sys.stderr); sys.exit(0)
if '--strict-config' in args and any(value.startswith('tools.view_image=') for value in args):
    print('Error loading configuration: unknown field `view_image` in `tools`', file=sys.stderr); sys.exit(2)
entry['stdin'] = sys.stdin.read()
schema = pathlib.Path(args[args.index('--output-schema') + 1])
output = pathlib.Path(args[args.index('--output-last-message') + 1])
shape = json.loads(schema.read_text())
entry.update({'schema_private': stat.S_IMODE(schema.stat().st_mode) == 0o600,
              'output_private': stat.S_IMODE(output.stat().st_mode) == 0o600,
              'additional_properties': shape['additionalProperties'], 'required': shape['required']})
with log.open('a') as out: out.write(json.dumps(entry) + '\\n')
time.sleep(cfg['exec_delay'])
mode = cfg['mode']
if mode == 'wait': time.sleep(60)
if mode == 'error': print('private server quota failure sensitive-secret', file=sys.stderr); sys.exit(1)
if mode == 'malformed': output.write_text('private malformed sensitive-secret'); sys.exit(0)
if mode == 'missing': sys.exit(0)
if mode == 'large-output': print('x' * (513 * 1024)); sys.exit(0)
if mode == 'large-result': output.write_text('x' * (129 * 1024)); sys.exit(0)
if mode == 'refusal': output.write_text(json.dumps({'refusal': 'unable to complete'})); sys.exit(0)
output.write_text(json.dumps({'rows': [{'title': 'fixture', 'category': 'test', 'source_ids': ['fixture-1'],
    'observation': 'observed', 'uncertainty': 'review', 'next_check': 'manual'}], 'report': ''}))
print(json.dumps({'type': 'turn.completed', 'usage': {'input_tokens': 40, 'cached_input_tokens': 5, 'output_tokens': 12}}))
""";
  }
}
