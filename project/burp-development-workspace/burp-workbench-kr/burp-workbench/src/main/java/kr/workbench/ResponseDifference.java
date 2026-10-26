package kr.workbench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Display-only response field differences, with positions in the untouched bounded input.
 * Values are never evaluated, sent or persisted. Line comparison remains the fallback.
 */
final class ResponseDifference {
  static final int INPUT_LIMIT = 65_536, ITEM_LIMIT = 256, NODE_LIMIT = 4_096;
  record Span(int start, int end) {
    static final Span MISSING = new Span(-1, -1);
    boolean present() { return start >= 0 && end >= start; }
  }
  record Item(String category, String path, String change, String left, String right,
              Span leftSpan, Span rightSpan) {}
  record Summary(String left, String right, List<Item> items, boolean limited, String note) {}
  private enum Type { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }
  private record Node(Type type, Object value, Span span) {}
  private record Header(String value, Span span) {}
  private record Document(String raw, boolean http, String first, Span firstSpan,
                          Map<String, List<Header>> headers, Node json, String note,
                          boolean limited) {}

  static Summary compare(String left, String right) {
    Document a = document(left), b = document(right);
    List<Item> items = new ArrayList<>();
    Collector out = new Collector(items);
    if (a.http() && b.http()) {
      if (!a.first().equals(b.first()))
        out.add("상태 줄", "HTTP", a.first(), b.first(), a.firstSpan(), b.firstSpan());
      LinkedHashSet<String> names = new LinkedHashSet<>(a.headers().keySet());
      names.addAll(b.headers().keySet());
      for (String name : names) {
        List<Header> x = a.headers().getOrDefault(name, List.of());
        List<Header> y = b.headers().getOrDefault(name, List.of());
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
          Header p = i < x.size() ? x.get(i) : null, q = i < y.size() ? y.get(i) : null;
          if (p == null || q == null || !p.value().equals(q.value()))
            out.add("헤더", name + "[" + (i + 1) + "]", p == null ? null : p.value(),
                q == null ? null : q.value(), p == null ? Span.MISSING : p.span(),
                q == null ? Span.MISSING : q.span());
        }
      }
    }
    if (a.json() != null && b.json() != null)
      fields("$", a.json(), b.json(), a.raw(), b.raw(), out);
    String note = "제외 필드 없음 · 헤더 이름의 대소문자만 동일하게 취급 · 숫자 표기 차이도 표시"
        + " · 원문 순서·공백 차이는 ‘원문·줄 차이’에서 확인 · 차이는 취약점 확정 아님";
    if (a.http() != b.http()) note += "\n양쪽 자료 형식이 다릅니다. 공통 JSON 항목과 원문 차이를 확인하세요.";
    if (!a.note().isEmpty()) note += "\n왼쪽: " + a.note();
    if (!b.note().isEmpty()) note += "\n오른쪽: " + b.note();
    boolean limited = a.limited() || b.limited() || out.limited;
    if (limited) note += "\n표시 제한 적용: 입력별 65,536자 / JSON 4,096노드·깊이 32 / 항목 256개. 전체 차이 수가 아닙니다.";
    return new Summary(a.raw(), b.raw(), List.copyOf(items), limited, note);
  }

  private static final class Collector {
    final List<Item> items;
    boolean limited;
    Collector(List<Item> items) { this.items = items; }
    void add(String category, String path, String left, String right, Span a, Span b) {
      if (items.size() >= ITEM_LIMIT) { limited = true; return; }
      items.add(new Item(category, path, left == null ? "추가" : right == null ? "제거" : "변경",
          left == null ? "— 없음" : preview(left), right == null ? "— 없음" : preview(right), a, b));
    }
  }

  @SuppressWarnings("unchecked")
  private static void fields(String path, Node a, Node b, String left, String right, Collector out) {
    if (a == null || b == null || a.type() != b.type()) {
      out.add("JSON", path, value(a, left), value(b, right),
          a == null ? Span.MISSING : a.span(), b == null ? Span.MISSING : b.span());
    } else if (a.type() == Type.OBJECT) {
      Map<String, Node> x = (Map<String, Node>) a.value(), y = (Map<String, Node>) b.value();
      LinkedHashSet<String> names = new LinkedHashSet<>(x.keySet()); names.addAll(y.keySet());
      for (String name : names) fields(path + key(name), x.get(name), y.get(name), left, right, out);
    } else if (a.type() == Type.ARRAY) {
      List<Node> x = (List<Node>) a.value(), y = (List<Node>) b.value();
      for (int i = 0; i < Math.max(x.size(), y.size()); i++)
        fields(path + "[" + i + "]", i < x.size() ? x.get(i) : null,
            i < y.size() ? y.get(i) : null, left, right, out);
    } else if (!Objects.equals(a.value(), b.value()))
      out.add("JSON", path, value(a, left), value(b, right), a.span(), b.span());
  }
  private static String key(String key) {
    return key.matches("[A-Za-z_][A-Za-z0-9_]*") ? "." + key
        : "['" + key.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "']";
  }
  private static String value(Node n, String raw) {
    return n == null ? null : raw.substring(n.span().start(), n.span().end());
  }
  private static String preview(String value) {
    StringBuilder text = new StringBuilder();
    int i = 0;
    for (; i < value.length() && text.length() < 160; i++) {
      char c = value.charAt(i);
      if (c == '\n') text.append("\\n"); else if (c == '\r') text.append("\\r");
      else if (c == '\t') text.append("\\t"); else if (c < 32) text.append('�'); else text.append(c);
    }
    return i < value.length() ? text + "…" : text.toString();
  }

  private static Document document(String value) {
    String input = Objects.requireNonNullElse(value, "");
    boolean limited = input.length() > INPUT_LIMIT;
    String raw = input.substring(0, Math.min(input.length(), INPUT_LIMIT));
    boolean http = raw.matches("(?s)^HTTP/[0-9.]+[ \\t].*");
    String first = ""; Span firstSpan = Span.MISSING;
    Map<String, List<Header>> headers = new LinkedHashMap<>();
    int body = 0;
    String note = "";
    if (http) {
      int end = lineEnd(raw, 0);
      first = raw.substring(0, end); firstSpan = new Span(0, end);
      int pos = nextLine(raw, end), headerCount = 0;
      boolean separator = false;
      while (pos < raw.length()) {
        end = lineEnd(raw, pos);
        if (end == pos) { body = nextLine(raw, end); separator = true; break; }
        String line = raw.substring(pos, end);
        int colon = line.indexOf(':');
        if (colon > 0 && line.substring(0, colon).matches("[!#$%&'*+.^_\u0060|~0-9A-Za-z-]+")) {
          if (++headerCount <= ITEM_LIMIT) {
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            headers.computeIfAbsent(name, ignored -> new ArrayList<>())
                .add(new Header(line.substring(colon + 1).strip(), new Span(pos, end)));
          } else limited = true;
        } else note = "구조화하지 못한 헤더가 있습니다. 원문·줄 차이에서 확인하세요.";
        pos = nextLine(raw, end);
      }
      if (!separator) {
        body = raw.length();
        note = "헤더·본문 구분 줄이 없습니다. JSON 본문 요약을 만들지 않았습니다.";
      }
    }
    Node json = null;
    int start = body;
    while (start < raw.length() && whitespace(raw.charAt(start))) start++;
    if (start < raw.length() && "{[\"-0123456789tfn".indexOf(raw.charAt(start)) >= 0) {
      try { json = new Parser(raw, start).parse(); }
      catch (IllegalArgumentException error) {
        note = join(note, error.getMessage() + " · JSON 항목 요약 생략, 원문·줄 차이에서 확인하세요.");
        if (error.getMessage().contains("제한")) limited = true;
      }
    } else if (start < raw.length()) note = join(note, "JSON 본문이 아닙니다. 원문·줄 차이에서 확인하세요.");
    if (input.length() > INPUT_LIMIT && json == null)
      note = join(note, "입력 앞 65,536자만 검사했습니다.");
    return new Document(raw, http, first, firstSpan, headers, json, note, limited);
  }
  private static String join(String a, String b) { return a.isEmpty() ? b : a + " " + b; }
  private static int lineEnd(String raw, int start) {
    int p = raw.indexOf('\n', start);
    if (p < 0) return raw.length();
    return p > start && raw.charAt(p - 1) == '\r' ? p - 1 : p;
  }
  private static int nextLine(String raw, int end) {
    if (end < raw.length() && raw.charAt(end) == '\r') end++;
    if (end < raw.length() && raw.charAt(end) == '\n') end++;
    return end;
  }
  private static boolean whitespace(char c) { return c == ' ' || c == '\r' || c == '\n' || c == '\t'; }

  /** Small span-preserving parser with the same data-only policy as MinerJson. */
  private static final class Parser {
    final String raw; int p, nodes;
    Parser(String raw, int start) { this.raw = raw; p = start; }
    Node parse() {
      Node n = node(0); ws();
      if (p != raw.length()) throw error("JSON 후행 데이터");
      return n;
    }
    private Node node(int depth) {
      ws(); int start = p;
      if (depth > 32 || ++nodes > NODE_LIMIT) throw error("JSON 구조 제한");
      if (p >= raw.length()) throw error("JSON 입력 미완성");
      char c = raw.charAt(p);
      Type type; Object value;
      if (c == '{') {
        p++; type = Type.OBJECT; Map<String, Node> entries = new LinkedHashMap<>(); ws();
        if (!take('}')) do {
          ws(); String name = string(); ws(); need(':');
          if (entries.containsKey(name)) throw error("JSON 중복 키");
          entries.put(name, node(depth + 1)); ws();
          if (take('}')) break;
          need(',');
        } while (true);
        value = entries;
      } else if (c == '[') {
        p++; type = Type.ARRAY; List<Node> entries = new ArrayList<>(); ws();
        if (!take(']')) do {
          entries.add(node(depth + 1)); ws();
          if (take(']')) break;
          need(',');
        } while (true);
        value = entries;
      } else if (c == '"') { type = Type.STRING; value = string(); }
      else if (raw.startsWith("true", p) || raw.startsWith("false", p)) {
        type = Type.BOOLEAN; boolean truth = raw.startsWith("true", p); value = truth; p += truth ? 4 : 5;
      } else if (raw.startsWith("null", p)) { type = Type.NULL; value = null; p += 4; }
      else {
        type = Type.NUMBER;
        while (p < raw.length() && "-+0123456789.eE".indexOf(raw.charAt(p)) >= 0) p++;
        String number = raw.substring(start, p);
        if (!number.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))
          throw error("JSON 값 오류");
        value = number;
      }
      return new Node(type, value, new Span(start, p));
    }
    private String string() {
      need('"'); StringBuilder text = new StringBuilder();
      while (p < raw.length()) {
        char c = raw.charAt(p++);
        if (c == '"') return text.toString();
        if (c < 32) throw error("JSON 문자열 제어문자");
        if (c == '\\') {
          if (p >= raw.length()) throw error("JSON 문자열 미완성");
          c = raw.charAt(p++);
          switch (c) {
            case '"', '\\', '/' -> text.append(c);
            case 'b' -> text.append('\b'); case 'f' -> text.append('\f');
            case 'n' -> text.append('\n'); case 'r' -> text.append('\r'); case 't' -> text.append('\t');
            case 'u' -> {
              if (p + 4 > raw.length()) throw error("JSON unicode 오류");
              if (!raw.substring(p, p + 4).matches("[0-9A-Fa-f]{4}")) throw error("JSON unicode 오류");
              try { text.append((char) Integer.parseInt(raw.substring(p, p + 4), 16)); }
              catch (NumberFormatException e) { throw error("JSON unicode 오류"); }
              p += 4;
            }
            default -> throw error("JSON escape 오류");
          }
        } else text.append(c);
      }
      throw error("JSON 문자열 미완성");
    }
    private void ws() { while (p < raw.length() && whitespace(raw.charAt(p))) p++; }
    private boolean take(char c) { if (p < raw.length() && raw.charAt(p) == c) { p++; return true; } return false; }
    private void need(char c) { if (!take(c)) throw error("JSON 구분자 오류"); }
    private IllegalArgumentException error(String message) { return new IllegalArgumentException(message); }
  }
}
