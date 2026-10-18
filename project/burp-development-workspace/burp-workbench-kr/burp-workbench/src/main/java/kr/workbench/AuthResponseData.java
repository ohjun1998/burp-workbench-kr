package kr.workbench;

import java.util.Map;
import java.util.function.Predicate;

/** Distinguishes empty payload envelopes and denial messages from returned application data. */
final class AuthResponseData {
  private AuthResponseData() {}

  static boolean material(Object value) {
    return value != null && value != AuthJudgement.Empty.OBJECT
        && value != AuthJudgement.Empty.ARRAY
        && !(value instanceof String text && text.isBlank());
  }

  private static boolean envelope(String path) {
    return path.matches("\\$\\.(?:user|data|result|payload|items|records|content)(?:\\..*|\\[.*|$)");
  }

  private static boolean metadata(String path) {
    // Only scalar envelope metadata is exempt. data.message and error.details may contain real data.
    return path.matches("(?i)\\$\\.(?:status|success|ok|error|errors|message|messages|code|errorCode|timestamp|requestId|traceId)");
  }

  static boolean emptyPayload(Map<String, Object> values) {
    if (values == null) return false;
    if (values.values().stream().noneMatch(AuthResponseData::material)) return true;
    boolean hasEnvelope = values.keySet().stream().anyMatch(AuthResponseData::envelope);
    return hasEnvelope && values.entrySet().stream()
        .noneMatch(entry -> !metadata(entry.getKey()) && material(entry.getValue()));
  }

  static boolean protectedData(Map<String, Object> values, AuthJudgement.Rules rules,
                               Predicate<String> explicitDenial) {
    if (values == null) return false;
    for (var entry : values.entrySet()) {
      if (!material(entry.getValue())) continue;
      String path = entry.getKey();
      if (rules != null && ((!rules.ownerPath().isBlank() && AuthJudgement.match(path, rules.ownerPath()))
          || rules.important().stream().anyMatch(field -> AuthJudgement.match(path, field)))) return true;
      if (metadata(path)) continue;
      if (entry.getValue() instanceof String text && explicitDenial.test(text)) continue;
      return true;
    }
    return false;
  }
}
