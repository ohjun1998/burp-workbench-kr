package kr.workbench;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** User-run smoke check against the immutable release; contains no target transport. */
public final class Next0542RuntimeProof {
  interface Checked { Map<String,Object> run() throws Exception; }
  static Map<String,Object> check(Checked work) {
    long started = System.nanoTime();
    Map<String,Object> out = new LinkedHashMap<>();
    try { out.putAll(work.run()); out.put("status", "passed"); }
    catch (Exception error) {
      out.put("status", "failed");
      out.put("errorType", error.getClass().getSimpleName());
      String safeCode = switch (Objects.toString(error.getMessage(), "")) {
        case CodexSubscriptionClient.AUTH_REQUIRED -> "chatgpt-authentication-required";
        case CodexSubscriptionClient.FAILED -> "codex-execution-failed";
        case CodexSubscriptionClient.TIMEOUT -> "codex-timeout";
        case CodexSubscriptionClient.CANCELLED -> "codex-cancelled";
        case CodexSubscriptionClient.OUTPUT_INVALID -> "codex-output-invalid";
        case CodexSubscriptionClient.EXECUTABLE_INVALID -> "codex-executable-invalid";
        case CodexSubscriptionClient.TEMP_FAILED -> "codex-private-workspace-failed";
        default -> "client-error";
      };
      out.put("safeProductionErrorCode", safeCode);
      boolean permission = false, timeout = false;
      for (Throwable cause = error; cause != null; cause = cause.getCause()) {
        String message = Objects.toString(cause.getMessage(), "").toLowerCase(Locale.ROOT);
        permission |= message.contains("operation not permitted") || message.contains("permission denied");
        timeout |= message.contains("timeout") || message.contains("시간") || message.contains("timed out");
      }
      out.put("failureCategory", permission ? "environment-permission" : timeout ? "timeout" : "connection-or-response");
      // Provider messages and CLI event streams are deliberately excluded from this report.
    }
    out.put("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    return out;
  }
  static String sha(String value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
  }
  static LocalLlmTasks.Input input(LocalLlmTasks.Task task) {
    return new LocalLlmTasks.Input(task,
        "연결 검사용 합성 자료. 실제 서비스가 아니며 취약점이나 접근권한을 확인하지 않았습니다. 한 행만 간단히 답하세요.",
        List.of(new LocalLlmTasks.Source("runtime-smoke-0542", "GET", "https://example.invalid/profile",
            "합성 테스트 계정 A",
            "GET /profile HTTP/1.1\r\nHost: example.invalid\r\nAccept: application/json\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"displayName\":\"Synthetic Alice\"}")));
  }
  static Map<String,Object> answer(LocalLlmClient.Reply reply, LocalLlmTasks.Input input) throws Exception {
    LocalLlmTasks.Result parsed = LocalLlmTasks.parse(reply.text(), input);
    return Map.of("responseCharacters", reply.text().length(), "responseSha256", sha(reply.text()),
        "rowCount", parsed.rows().size(), "sourceIdValidation", true,
        "finishReason", reply.finishReason(), "promptTokens", reply.promptTokens(),
        "completionTokens", reply.completionTokens(), "modelElapsedMillis", reply.elapsedMillis());
  }
  public static void main(String[] args) {
    String mode = args[0], endpoint = args[1], model = args[2], codex = args[3], subscriptionModel = args[4];
    int timeout = Integer.parseInt(args[5]), maxTokens = Integer.parseInt(args[6]);
    String reasoning = args[7];
    Map<String,Object> out = new LinkedHashMap<>();
    out.put("targetHttpRequests", 0);
    out.put("syntheticInputOnly", true);
    out.put("responseBodiesStored", false);
    out.put("nativeBurpGuiVerified", false);
    if (mode.equals("status")) {
      try (CodexSubscriptionClient client = new CodexSubscriptionClient()) {
        out.put("subscriptionLogin", check(() -> {
          client.status(codex, timeout);
          return Map.of("chatgptAuthentication", true, "apiKeyFallback", false);
        }));
      }
      out.put("health", check(() -> {
        try (LocalLlmClient client = new LocalLlmClient()) {
          LocalLlmConfig config = new LocalLlmConfig(endpoint, model,
              Objects.toString(System.getenv("WORKBENCH_HALOGEN_API_KEY"), ""), timeout, maxTokens, reasoning);
          String body = client.health(config);
          return Map.of("jsonResponseValidated", true, "responseCharacters", body.length(), "responseSha256", sha(body));
        }
      }));
      out.put("models", check(() -> {
        try (LocalLlmClient client = new LocalLlmClient()) {
          LocalLlmConfig config = new LocalLlmConfig(endpoint, model,
              Objects.toString(System.getenv("WORKBENCH_HALOGEN_API_KEY"), ""), timeout, maxTokens, reasoning);
          List<String> names = client.models(config);
          return Map.of("modelCount", names.size(), "configuredModelListed", names.contains(model));
        }
      }));
    } else if (mode.equals("local")) {
      out.put("inferenceAttempts", 1);
      out.put("localInference", check(() -> {
        try (LocalLlmClient client = new LocalLlmClient()) {
          var config = new LocalLlmConfig(endpoint, model,
              Objects.toString(System.getenv("WORKBENCH_HALOGEN_API_KEY"), ""), timeout, maxTokens, reasoning);
          var input = input(LocalLlmTasks.Task.RESPONSE);
          return answer(client.complete(config, LocalLlmTasks.system(input.task()), LocalLlmTasks.user(input), false), input);
        }
      }));
    } else if (mode.equals("subscription")) {
      out.put("inferenceAttempts", 1);
      out.put("subscriptionInference", check(() -> {
        try (CodexSubscriptionClient client = new CodexSubscriptionClient()) {
          var input = input(LocalLlmTasks.Task.FUNCTIONS);
          return answer(client.complete(codex, subscriptionModel, timeout,
              LocalLlmTasks.system(input.task()), LocalLlmTasks.user(input)), input);
        }
      }));
    } else throw new IllegalArgumentException("Unknown mode");
    System.out.println(MetadataExport.json(out));
  }
}
