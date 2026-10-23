package kr.workbench;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

/** Bounded suggestions from retained packets only. No HTTP client or verdict inference. */
final class AuthSetupSuggestions {
  static final int MAX_PACKETS = 100, MAX_FIELDS = 200, MAX_BODY = 128 * 1024;
  private static final Set<String> CREDENTIAL_KEYS = Set.of("access_token", "token", "session_id", "sessionid", "jwt");
  record Read(HttpRequestResponse packet, int relation, String target, long selectionRevision) {
    Read withSelectionRevision(long revision) { return new Read(packet, relation, target, revision); }
    String url() { return packet.request().url(); }
    String reason() { return switch (relation) {
      case 0 -> "같은 경로 · 결과 조회인지 확인";
      case 1 -> "인접 경로 · 결과 조회인지 확인";
      default -> "같은 Origin · 결과 조회인지 확인";
    }; }
  }
  record Field(String path, String value) {}

  static List<Read> reads(HttpRequest target, List<HttpRequestResponse> packets, Predicate<HttpRequest> scope) {
    if (target == null || packets == null) return List.of();
    LinkedHashMap<String, Read> found = new LinkedHashMap<>();
    String origin = AuthComparePanel.origin(target), targetPath = URI.create(target.url()).getPath();
    for (int i = 0; i < Math.min(MAX_PACKETS, packets.size()); i++) {
      HttpRequestResponse packet = packets.get(i);
      if (packet == null || packet.request() == null || packet.response() == null) continue;
      HttpRequest request = packet.request();
      try {
        URI uri = URI.create(request.url());
        if (!"GET".equals(request.method()) || !origin.equals(AuthComparePanel.origin(request))
            || !scope.test(request) || !Set.of("http", "https").contains(uri.getScheme())
            || uri.getUserInfo() != null || uri.getFragment() != null || request.url().length() > 2048
            || hasCredentialQuery(uri) || packet.response().statusCode() < 200 || packet.response().statusCode() >= 300
            || packet.response().body().length() > MAX_BODY) continue;
        String path = uri.getPath();
        int relation = path.equals(targetPath) ? 0 : parent(path).equals(parent(targetPath)) ? 1 : 2;
        found.putIfAbsent(request.url(), new Read(packet, relation, target.method() + " " + target.url(), -1));
      } catch (RuntimeException ignored) { /* Malformed or no longer readable packet is not a suggestion. */ }
    }
    return found.values().stream().sorted(Comparator.comparingInt(Read::relation)).toList();
  }

  private static String parent(String path) { return path.substring(0, Math.max(0, path.lastIndexOf('/'))); }
  private static boolean hasCredentialQuery(URI uri) {
    if (uri.getRawQuery() == null) return false;
    for (String pair : uri.getRawQuery().split("&")) {
      String name = URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
      if (CREDENTIAL_KEYS.contains(name)) return true;
    }
    return false;
  }

  static List<Field> fields(HttpRequestResponse packet) {
    if (packet == null || packet.response() == null || packet.response().body().length() > MAX_BODY) return List.of();
    Map<String, Object> json;
    try { json = AuthJudgement.json(AuthEvidencePanel.readBody(packet.response())); }
    catch (java.io.IOException error) { throw new IllegalArgumentException("보관 응답 디코딩 실패 · 필드를 직접 입력하거나 원본 형식을 확인하세요."); }
    if (json == null) return List.of();
    List<Field> fields = new ArrayList<>();
    for (var entry : json.entrySet()) {
      // Match AuthEffectCheck's currently supported path grammar, without inventing unsupported selectors.
      if (!entry.getKey().startsWith("$.") || entry.getKey().length() > 256) continue;
      Object value = entry.getValue();
      if (value == AuthJudgement.Empty.ARRAY || value == AuthJudgement.Empty.OBJECT) continue;
      String display = MetadataExport.json(value);
      fields.add(new Field(entry.getKey(), display.length() > 120 ? display.substring(0, 117) + "…" : display));
      if (fields.size() == MAX_FIELDS) break;
    }
    return List.copyOf(fields);
  }

  /** Preserve existing exact and parameterized permissions; only add otherwise uncovered operations. */
  static String prefillPolicy(String existing, List<HttpRequestResponse> selected) {
    BountyMatrix matrix = BountyMatrix.parse(existing);
    StringBuilder next = new StringBuilder(existing.stripTrailing());
    Set<String> added = new HashSet<>();
    for (var packet : selected) {
      if (packet == null || packet.request() == null) continue;
      HttpRequest request = packet.request();
      String path = URI.create(request.url()).getPath(), key = request.method() + " " + path;
      boolean covered = matrix.rules.stream().anyMatch(rule ->
          (rule.method().equals("*") || rule.method().equals(request.method())) && rule.match().matcher(path).matches());
      if (!covered && added.add(key)) {
        if (next.length() > 0) next.append('\n');
        next.append(key).append(" UNKNOWN UNKNOWN UNKNOWN UNKNOWN");
      }
    }
    // Validate the complete proposal first so an over-limit or invalid path cannot partially change the editor.
    BountyMatrix.parse(next.toString());
    return next.toString();
  }
}
