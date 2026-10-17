package kr.workbench;

import java.util.*;
import java.util.regex.Pattern;

/** Explicit denial fingerprints. Limits and regex budgets fail closed to manual review. */
final class AuthResponseRules {
  record Rule(String target, String value, boolean regex) {}
  final List<Rule> rules;
  AuthResponseRules(List<Rule> rules) { this.rules = List.copyOf(rules); }

  static AuthResponseRules parse(String text) {
    if (text.length() > 8192) throw new IllegalArgumentException("응답 차단 규칙 8KiB 제한");
    List<Rule> result = new ArrayList<>();
    for (String line : text.split("\\R")) {
      if (line.isBlank()) continue;
      int split = line.indexOf(':');
      if (split < 1) throw new IllegalArgumentException("차단 규칙: BODY: 문구 또는 HEADER_REGEX: 정규식");
      String type = line.substring(0, split).strip(), value = line.substring(split + 1).strip();
      if (!Set.of("BODY", "BODY_REGEX", "HEADER", "HEADER_REGEX").contains(type)
          || value.isEmpty() || value.length() > 512)
        throw new IllegalArgumentException("BODY / BODY_REGEX / HEADER / HEADER_REGEX와 1~512자 값을 사용하세요.");
      boolean regex = type.endsWith("_REGEX");
      if (regex) {
        try { Pattern.compile(value); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("응답 차단 규칙 정규식 오류"); }
      }
      result.add(new Rule(type.startsWith("BODY") ? "본문" : "헤더", value, regex));
      if (result.size() > 16) throw new IllegalArgumentException("응답 차단 규칙 최대 16개");
    }
    return new AuthResponseRules(result);
  }

  String match(String headers, String body) {
    if (rules.isEmpty()) return "";
    if (headers.length() > 65536 || body.length() > 128 * 1024)
      throw new IllegalArgumentException("응답 차단 규칙 검사 크기 제한 · 확인 필요");
    for (int i = 0; i < rules.size(); i++) {
      Rule rule = rules.get(i);
      String text = rule.target.equals("본문") ? body : headers;
      boolean matched;
      if (rule.regex) {
        var found = PacketInspector.find(text, rule.value, true, true, Thread.currentThread()::isInterrupted);
        if (found.error() != null) throw new IllegalArgumentException("응답 차단 정규식 검사 제한 · 확인 필요");
        matched = !found.spans().isEmpty();
      } else matched = text.contains(rule.value);
      // Report the rule number, never echo response data, tokens or user-entered patterns.
      if (matched) return "응답 차단 규칙 " + (i + 1) + " · " + rule.target + (rule.regex ? " 정규식" : " 문자열");
    }
    return "";
  }

  AuthJudgement.Result assess(int baselineStatus, String baselineHeaders, String baseline,
                              int status, String headers, String body) {
    if (rules.isEmpty() || baselineStatus < 200 || baselineStatus >= 300) return null;
    try {
      if (!match(baselineHeaders, baseline).isEmpty())
        return new AuthJudgement.Result("판정 보류", "기준 응답에 응답 차단 규칙 일치 · 규칙/기준 확인", "");
      // Redirects, missing objects and server errors stay uncertain even when a phrase matches.
      if (status == 401 || status == 403 || status >= 200 && status < 300) {
        String evidence = match(headers, body);
        if (!evidence.isEmpty()) return new AuthJudgement.Result("차단 근거 확인", evidence, "");
      }
      return null;
    } catch (IllegalArgumentException e) {
      return new AuthJudgement.Result("판정 보류", e.getMessage(), "");
    }
  }
}
