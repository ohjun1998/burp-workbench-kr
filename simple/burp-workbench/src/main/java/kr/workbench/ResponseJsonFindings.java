package kr.workbench;

import java.util.*;

/** Walks parsed JSON; findings retain paths and key names, never response values. */
final class ResponseJsonFindings {
  private ResponseJsonFindings() {}

  static void inspect(Object value, String path, String url, List<PassiveFindings.Finding> out) {
    if (out.size() >= 32) return;
    if (value instanceof Map<?, ?> map) {
      String lower = path.toLowerCase(Locale.ROOT);
      if ((lower.endsWith("/systemdiagnostics") || lower.endsWith("/diagnostics") && lower.contains("/debug/"))
          && diagnosticFields(map) >= 2) {
        out.add(new PassiveFindings.Finding("JSON 진단정보 노출 후보", url, "JSON: " + path,
            "런타임·DB·내부 환경 진단 키 " + diagnosticFields(map) + "개 · 값 가림 · 접근 정책 수동 확인"));
      }
      for (var entry : map.entrySet()) {
        if (out.size() >= 32) break;
        String key = entry.getKey().toString();
        String next = path + "/" + key.replace("~", "~0").replace("/", "~1");
        Object item = entry.getValue();
        if (item instanceof String text && SecretCandidates.named(key) && SecretCandidates.plausible(text))
          out.add(new PassiveFindings.Finding("JSON 비밀 키 후보", url, "JSON: " + next,
              "이름·엔트로피 단서 · [가림 · " + text.length() + "자] · 공개값 가능 · 유효성 미검증"));
        inspect(item, next, url, out);
      }
    } else if (value instanceof List<?> array) {
      for (int i = 0; i < array.size() && out.size() < 32; i++) inspect(array.get(i), path + "/" + i, url, out);
    }
  }

  private static int diagnosticFields(Map<?, ?> map) {
    int count = 0;
    for (var entry : map.entrySet()) {
      String key = entry.getKey().toString().replaceAll("[_-]", "").toLowerCase(Locale.ROOT);
      Object value = entry.getValue();
      if (value == null || value instanceof String text && text.isBlank()) continue;
      if (key.matches(".*(?:runtime|version|database|dbhost|dbport|dbuser|connectionstring|hostname|internalpath|environment|platform|stacktrace|process|memory|cwd|nodeenv|osinfo).*")
          && !(value instanceof Map<?, ?> m && m.isEmpty()) && !(value instanceof List<?> l && l.isEmpty())) count++;
      if (count >= 32) break;
    }
    return count;
  }
}
