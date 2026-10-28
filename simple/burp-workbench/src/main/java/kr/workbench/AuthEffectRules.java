package kr.workbench;

import burp.api.montoya.http.message.requests.HttpRequest;
import java.net.URI;
import java.util.*;

/** Named origin-bound write observations. Ambiguous overlapping rules fail closed. */
final class AuthEffectRules {
  static final int LIMIT = 32;
  record Rule(String name, String origin, AuthEffectCheck.Config config) {
    Rule {
      name = Objects.requireNonNull(name).strip();
      if (name.isEmpty() || name.length() > 64 || name.chars().anyMatch(c -> c < 32))
        throw new IllegalArgumentException("조회 규칙 이름은 1~64자입니다.");
      origin = canonicalOrigin(origin); Objects.requireNonNull(config);
      if (config.enabled() && !canonicalOrigin(config.url()).equals(origin))
        throw new IllegalArgumentException("조회 규칙과 GET URL의 Origin이 다릅니다.");
    }
    String key() { return origin + " " + config.method() + " " + config.path(); }
    @Override public String toString() { return name + " · " + config.method() + " " + config.path(); }
  }
  static String canonicalOrigin(String value) {
    try {
      URI uri = URI.create(value);
      String scheme = Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT);
      if (!Set.of("http", "https").contains(scheme) || uri.getHost() == null || uri.getUserInfo() != null)
        throw new IllegalArgumentException();
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      if (host.contains(":")) host = "[" + host.replace("[", "").replace("]", "") + "]";
      int port = uri.getPort() < 0 ? (scheme.equals("https") ? 443 : 80) : uri.getPort();
      return scheme + "://" + host + ":" + port;
    } catch (RuntimeException error) { throw new IllegalArgumentException("유효한 HTTP(S) Origin을 지정하세요."); }
  }
  static void validate(List<Rule> rules) {
    if (rules == null || rules.size() > LIMIT) throw new IllegalArgumentException("쓰기 조회 규칙은 최대 32개입니다.");
    Set<String> names = new HashSet<>(), keys = new HashSet<>();
    for (int i = 0; i < rules.size(); i++) {
      Rule rule = Objects.requireNonNull(rules.get(i));
      if (!names.add(rule.name()) || !keys.add(rule.key())) throw new IllegalArgumentException("중복 조회 규칙 이름 또는 Origin·메서드·경로입니다.");
      for (int j = 0; j < i; j++) {
        Rule other = rules.get(j);
        if (rule.config().enabled() && other.config().enabled() && rule.origin().equals(other.origin())
            && rule.config().method().equals(other.config().method()) && overlaps(rule.config().path(), other.config().path()))
          throw new IllegalArgumentException("쓰기 조회 규칙 충돌: " + other.name() + " / " + rule.name() + " · 경로 적용 범위를 분리하세요.");
      }
    }
  }
  static boolean overlaps(String first, String second) {
    String[] a = first.split("/", -1), b = second.split("/", -1);
    if (a.length != b.length) return false;
    for (int i = 0; i < a.length; i++) if (!a[i].equals(b[i]) && !variable(a[i]) && !variable(b[i])) return false;
    return true;
  }
  private static boolean variable(String value) { return value.matches("\\{[A-Za-z_][A-Za-z0-9_]*}"); }
  static AuthEffectCheck.Config select(List<Rule> rules, HttpRequest request) { return select(rules, request.method(), request.url()); }
  static AuthEffectCheck.Config select(List<Rule> rules, String method, String url) {
    AuthEffectCheck.Config found = null; String origin = canonicalOrigin(url);
    for (Rule rule : rules) if (rule.config().enabled() && rule.origin().equals(origin) && rule.config().method().equals(method)
        && AuthPolicy.parse(method + " " + rule.config().path() + " PUBLIC", AuthPolicy.Access.UNSPECIFIED).forRequest(method, url) == AuthPolicy.Access.PUBLIC) {
      if (found != null) throw new IllegalArgumentException("이 요청에 조회 규칙이 둘 이상 적용됩니다. 충돌을 먼저 해소하세요.");
      found = rule.config();
    }
    return found == null ? AuthEffectCheck.Config.disabled() : found;
  }
}
