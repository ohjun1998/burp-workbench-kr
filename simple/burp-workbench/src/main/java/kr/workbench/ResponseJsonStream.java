package kr.workbench;

import java.util.*;

/** Bounded JSON traversal without retaining large strings or building a response tree. */
final class ResponseJsonStream {
  static final int CHARACTER_LIMIT = 2 * 1024 * 1024;
  static final int NODE_LIMIT = 100000;
  private static final int STRING_LIMIT = 16384;
  record Result(List<PassiveFindings.Finding> findings, int inspected, boolean complete, String note) {}

  static Result inspect(String body, String url) {
    Reader reader = new Reader(body, url);
    try {
      reader.value("", 0, null);
      reader.space();
      if (reader.position != body.length()) throw new Invalid();
      return new Result(List.copyOf(reader.findings), reader.position, true,
          "JSON 순차 검사 " + reader.position + "자 · 큰 문자열은 보관하지 않음");
    } catch (Limit e) {
      return new Result(List.copyOf(reader.findings), reader.position, false, e.getMessage());
    } catch (Invalid e) {
      // A quoted key inside malformed JSON is not evidence of a real JSON property.
      return new Result(List.of(), reader.position, false, "JSON 형식 오류 · 구조 후보 보류");
    }
  }

  private static final class Invalid extends RuntimeException {}
  private static final class Limit extends RuntimeException {
    Limit(String message) { super(message); }
  }
  private static final class Reader {
    final String text, url;
    final List<PassiveFindings.Finding> findings = new ArrayList<>();
    int position, nodes;
    Reader(String text, String url) { this.text = text; this.url = url; }
    char peek() {
      if (position >= CHARACTER_LIMIT && position < text.length())
        throw new Limit("JSON 순차 검사 " + CHARACTER_LIMIT + "자 한도 · 후반 미검사");
      return position < text.length() ? text.charAt(position) : '\0';
    }
    char take() { char c = peek(); if (position >= text.length()) throw new Invalid(); position++; return c; }
    void space() { while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') position++; }
    void require(char expected) { if (take() != expected) throw new Invalid(); }
    boolean value(String path, int depth, String key) {
      if (++nodes > NODE_LIMIT) throw new Limit("JSON 항목 " + NODE_LIMIT + "개 한도 · 나머지 미검사");
      if (depth > 64 || path.length() > 4096) throw new Limit("JSON 깊이·경로 한도 · 나머지 미검사");
      if (findings.size() >= 32) throw new Limit("JSON 후보 32개 한도 · 나머지 미검사");
      space();
      switch (peek()) {
        case '{': return object(path, depth);
        case '[': {
          take(); space(); if (peek() == ']') { take(); return false; }
          int index = 0;
          while (true) {
            value(path + "/" + index++, depth + 1, null); space();
            char next = take(); if (next == ']') return true; if (next != ',') throw new Invalid();
          }
        }
        case '"': {
          String s = string(STRING_LIMIT);
          if (s != null && key != null && SecretCandidates.named(key) && SecretCandidates.plausible(s))
            findings.add(new PassiveFindings.Finding("JSON 비밀 키 후보", url, "JSON: " + path,
                "순차 구조 검사 · [가림 · " + s.length() + "자] · 공개값 가능 · 유효성 미검증"));
          return s == null || !s.isBlank();
        }
        case 't': literal("true"); return true;
        case 'f': literal("false"); return true;
        case 'n': literal("null"); return false;
        default: number(); return true;
      }
    }
    boolean object(String path, int depth) {
      require('{'); space(); if (peek() == '}') { take(); return false; }
      String lower = path.toLowerCase(Locale.ROOT);
      boolean diagnostic = lower.endsWith("/systemdiagnostics") || lower.endsWith("/diagnostics") && lower.contains("/debug/");
      Set<String> fields = new HashSet<>();
      while (true) {
        space(); String key = string(512);
        if (key == null) throw new Limit("JSON 키 길이 한도 · 나머지 미검사");
        space(); require(':');
        String child = path + "/" + key.replace("~", "~0").replace("/", "~1");
        boolean present = value(child, depth + 1, key);
        String normalized = key.replaceAll("[_-]", "").toLowerCase(Locale.ROOT);
        if (diagnostic && present && normalized.matches(".*(?:runtime|version|database|dbhost|dbport|dbuser|connectionstring|hostname|internalpath|environment|platform|stacktrace|process|memory|cwd|nodeenv|osinfo).*"))
          fields.add(normalized);
        space(); char next = take();
        if (next == '}') break;
        if (next != ',') throw new Invalid();
      }
      if (diagnostic && fields.size() >= 2 && findings.size() < 32)
        findings.add(new PassiveFindings.Finding("JSON 진단정보 노출 후보", url, "JSON: " + path,
            "순차 구조 검사 · 런타임·DB·내부 환경 진단 키 " + fields.size() + "개 · 값 가림 · 접근 정책 수동 확인"));
      return true;
    }
    String string(int captureLimit) {
      require('"'); StringBuilder out = new StringBuilder(); boolean overflow = false;
      while (true) {
        char c = take(); if (c == '"') return overflow ? null : out.toString();
        if (c < 0x20) throw new Invalid();
        if (c == '\\') {
          c = take();
          c = switch (c) {
            case '"', '\\', '/' -> c;
            case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
            case 'u' -> { int code = 0; for (int i = 0; i < 4; i++) { char hex = take(); int digit = hex >= '0' && hex <= '9' ? hex - '0' : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10 : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1; if (digit < 0) throw new Invalid(); code = code * 16 + digit; } yield (char) code; }
            default -> throw new Invalid();
          };
        }
        if (!overflow && out.length() < captureLimit) out.append(c); else overflow = true;
      }
    }
    void literal(String expected) { for (int i = 0; i < expected.length(); i++) require(expected.charAt(i)); }
    void number() {
      if (peek() == '-') take();
      if (peek() == '0') take();
      else { if (peek() < '1' || peek() > '9') throw new Invalid(); digits(); }
      if (peek() == '.') { take(); if (!digit()) throw new Invalid(); digits(); }
      if (peek() == 'e' || peek() == 'E') { take(); if (peek() == '+' || peek() == '-') take(); if (!digit()) throw new Invalid(); digits(); }
    }
    boolean digit() { return peek() >= '0' && peek() <= '9'; }
    void digits() { while (digit()) take(); }
  }
}
