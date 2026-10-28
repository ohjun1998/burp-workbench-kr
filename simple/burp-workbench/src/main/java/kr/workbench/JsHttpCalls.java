package kr.workbench;

import java.net.URI;
import java.util.*;

/** Bounded lexical HTTP-client call recovery. Never evaluates expressions or invents a base host. */
final class JsHttpCalls {
  private static final Set<String> VERBS = Set.of("get", "post", "put", "patch", "delete", "head", "options", "request");
  private record Argument(String raw, int start, int end, String separator) {}
  private record Value(String text, boolean complete, boolean prefixUnknown) {}

  static List<JsAnalysis.Endpoint> discover(String text, String document, int limit, long deadline) {
    List<JsAnalysis.Endpoint> result = new ArrayList<>();
    var cursor = new JsSyntax.Cursor(text);
    Deque<JsSyntax.Token> previous = new ArrayDeque<>();
    JsSyntax.Token token;
    int scanned = 0;
    while (result.size() < limit && (token = cursor.next()) != null) {
      if ((++scanned & 255) == 0 && (System.nanoTime() > deadline || Thread.currentThread().isInterrupted())) break;
      if (!token.quoted() && token.text().equals("(") && previous.size() >= 3) {
        var before = previous.toArray(JsSyntax.Token[]::new);
        var verb = before[before.length - 1];
        var dot = before[before.length - 2];
        var receiver = before[before.length - 3];
        if (!verb.quoted() && VERBS.contains(verb.text()) && dot.text().equals(".")
            && !receiver.quoted() && receiver.text().matches("(?i)http|httpClient")) {
          Argument argument = argument(text, cursor, deadline);
          String method = verb.text().toUpperCase(Locale.ROOT);
          if (verb.text().equals("request")) {
            Value explicit = value(argument == null ? "" : argument.raw());
            if (explicit == null || !explicit.complete() || !explicit.text().matches("(?i)GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS")
                || !argument.separator().equals(",")) { previous.clear(); continue; }
            method = explicit.text().toUpperCase(Locale.ROOT);
            argument = argument(text, cursor, deadline);
          }
          JsAnalysis.Endpoint endpoint = endpoint(text, argument, method, document);
          if (endpoint != null) result.add(endpoint);
          previous.clear();
          continue;
        }
      }
      previous.addLast(token);
      if (previous.size() > 8) previous.removeFirst();
    }
    return List.copyOf(result);
  }

  private static Argument argument(String source, JsSyntax.Cursor cursor, long deadline) {
    JsSyntax.Token first = cursor.next();
    if (first == null || first.text().equals(")") || first.text().equals(",")) return null;
    int depth = 0, end = first.end();
    for (JsSyntax.Token token = first; token != null; token = cursor.next()) {
      if (token.end() - first.start() > 8192 || System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) return null;
      if (!token.quoted()) {
        String symbol = token.text();
        if (depth == 0 && (symbol.equals(",") || symbol.equals(")")))
          return new Argument(source.substring(first.start(), end), first.start(), end, symbol);
        if (Set.of("(", "[", "{").contains(symbol)) depth++;
        else if (Set.of(")", "]", "}").contains(symbol)) depth--;
        if (depth < 0) return null;
      }
      end = token.end();
    }
    return null;
  }

  private static JsAnalysis.Endpoint endpoint(String source, Argument argument, String method, String document) {
    if (argument == null) return null;
    Value value = value(argument.raw());
    if (value == null) return null;
    String url = value.text();
    boolean complete = value.complete();
    if (value.prefixUnknown()) {
      if (!url.startsWith("{dynamic}")) return null;
      url = url.substring("{dynamic}".length());
      complete = false;
    }
    if (!url.matches("(?s)(?:https?://|/|\\./|\\.\\./).*")) return null;
    String parameters = "";
    int question = url.indexOf('?');
    if (question >= 0) {
      Set<String> names = new LinkedHashSet<>();
      for (String part : url.substring(question + 1).split("#", 2)[0].split("&")) {
        String name = part.split("=", 2)[0];
        if (name.matches("[A-Za-z0-9_.%\\[\\]-]{1,128}") && names.size() < 32) names.add(name);
      }
      parameters = String.join(", ", names);
      url = url.substring(0, question);
    }
    url = url.split("#", 2)[0];
    try {
      URI parsed = URI.create(url.replace("{dynamic}", "REPLACE_PATH"));
      if (parsed.getUserInfo() != null || url.startsWith("//")) return null;
      if (!parsed.isAbsolute()) {
        if (document != null && !value.prefixUnknown()) url = URI.create(document).resolve(parsed).toString().replace("REPLACE_PATH", "{dynamic}");
        else complete = false;
      }
    } catch (IllegalArgumentException error) { return null; }
    int line = 1 + (int) source.substring(0, argument.start()).chars().filter(c -> c == '\n').count();
    return new JsAnalysis.Endpoint(url, method, "HTTP 클라이언트 호출 후보", parameters, "", "", line,
        argument.start(), argument.end() - argument.start(), complete,
        "http/HttpClient 메서드 호출 위치 · 실행/도달 여부 미검증"
            + (value.prefixUnknown() ? " · 선행 서버/기본 경로 변수 미확정" : "")
            + (!value.complete() ? " · 동적 값은 직접 확정" : ""));
  }

  private static Value value(String expression) {
    var tokens = JsSyntax.tokens(expression);
    if (tokens.isEmpty()) return null;
    List<String> terms = new ArrayList<>();
    int depth = 0, start = 0;
    for (var token : tokens) {
      if (token.quoted()) continue;
      if (Set.of("(", "[", "{").contains(token.text())) depth++;
      else if (Set.of(")", "]", "}").contains(token.text())) depth--;
      else if (depth == 0 && token.text().equals("+")) { terms.add(expression.substring(start, token.start())); start = token.end(); }
      else if (depth == 0 && Set.of("?", ":", "||", "&&", "??", "=", ";").contains(token.text())) return null;
    }
    terms.add(expression.substring(start));
    StringBuilder joined = new StringBuilder();
    boolean complete = true, prefixUnknown = false;
    for (int index = 0; index < terms.size(); index++) {
      String term = terms.get(index).strip();
      List<JsSyntax.Token> part = JsSyntax.tokens(term);
      Value literal = part.size() == 1 && part.get(0).quoted() ? literal(part.get(0).text()) : null;
      if (literal == null) {
        if (!term.matches("(?:this\\.)?[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*")) return null;
        joined.append("{dynamic}"); complete = false; prefixUnknown |= index == 0;
      } else {
        joined.append(literal.text()); complete &= literal.complete();
        prefixUnknown |= index == 0 && literal.prefixUnknown();
      }
    }
    return joined.length() > 8192 ? null : new Value(joined.toString(), complete, prefixUnknown);
  }

  private static Value literal(String raw) {
    if (raw.length() < 2 || raw.charAt(0) != raw.charAt(raw.length() - 1)) return null;
    char quote = raw.charAt(0);
    StringBuilder value = new StringBuilder();
    boolean complete = true, prefixUnknown = false;
    for (int i = 1; i < raw.length() - 1; i++) {
      char c = raw.charAt(i);
      if (quote == '`' && c == '$' && i + 1 < raw.length() && raw.charAt(i + 1) == '{') {
        int end = templateEnd(raw, i + 2);
        if (end < 0) return null;
        prefixUnknown |= value.isEmpty(); value.append("{dynamic}"); complete = false; i = end;
      } else if (c == '\\') {
        if (++i >= raw.length() - 1) return null;
        char escaped = raw.charAt(i);
        if (escaped == 'u' || escaped == 'x') {
          int digits = escaped == 'u' ? 4 : 2;
          if (i + digits >= raw.length() - 1) return null;
          try { value.append((char) Integer.parseInt(raw.substring(i + 1, i + 1 + digits), 16)); }
          catch (NumberFormatException error) { return null; }
          i += digits;
        } else if (escaped == '\n' || escaped == '\r') { /* line continuation */ }
        else value.append(switch (escaped) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; default -> escaped; });
      } else value.append(c);
    }
    return new Value(value.toString(), complete, prefixUnknown);
  }

  private static int templateEnd(String text, int start) {
    int depth = 1; char quote = 0;
    for (int i = start; i < text.length() - 1; i++) {
      char c = text.charAt(i);
      if (quote != 0) { if (c == '\\') i++; else if (c == quote) quote = 0; continue; }
      if (c == '\'' || c == '"' || c == '`') quote = c;
      else if (c == '{') { if (++depth > 32) return -1; }
      else if (c == '}' && --depth == 0) return i;
    }
    return -1;
  }
}
