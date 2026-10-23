package kr.workbench;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.regex.Pattern;

/** Explicit, bounded setup exchange. This schema deliberately has no credential or account-binding fields. */
final class AuthSetupBundles {
  static final int MAX_BYTES = 128 * 1024, MAX_BUNDLES = 32;
  static final String SCHEMA = "workbench-auth-setup-1";
  static final List<String> ROLE_PLACEHOLDERS = List.of("역할 1", "역할 2", "역할 3", "비로그인");
  private static final Set<String> SECRET_QUERY = Set.of("access_token", "token", "session_id", "sessionid", "jwt", "authorization", "cookie", "api_key", "apikey", "secret", "password", "passwd", "client_secret");
  private static final Pattern CREDENTIAL_TEXT = Pattern.compile("(?im)(?:\\bBearer\\s+\\S+|(?:^|\\n)\\s*(?:Cookie|Set-Cookie|Authorization|Proxy-Authorization)\\s*[:=]\\s*\\S+|\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*)");

  record Settings(String ownerPath, String ignoredFields, String importantFields, String denialRules,
                  String responseRules, String policyRules, AuthPolicy.Access fallback,
                  String matrixRules, boolean unorderedArrays, List<AuthEffectRules.Rule> effects) {
    Settings {
      ownerPath = text(ownerPath, 128, "소유 JSON 경로");
      ignoredFields = text(ignoredFields, 2048, "제외 필드");
      importantFields = text(importantFields, 2048, "중요 필드");
      denialRules = text(denialRules, 4096, "JSON 차단 규칙");
      responseRules = text(responseRules, 8192, "응답 차단 규칙");
      policyRules = text(policyRules, 8192, "경로 정책");
      matrixRules = text(matrixRules, 8192, "권한표");
      Objects.requireNonNull(fallback, "fallback"); effects = List.copyOf(effects);
      if (effects.size() > 32) throw invalid("쓰기 조회 규칙은 최대 32개입니다.");
      var base = AuthJudgement.rules(ignoredFields, denialRules, ownerPath);
      new AuthJudgement.Rules(base.ignored(), base.denied(), base.ownerPath(), fallback,
          AuthJudgement.paths(importantFields), unorderedArrays);
      AuthResponseRules.parse(responseRules);
      AuthPolicy policy = AuthPolicy.parse(policyRules, fallback);
      BountyMatrix matrix = BountyMatrix.parse(matrixRules);
      ruleConflicts(policy.rules.stream().map(rule -> new String[]{rule.method(), rule.path()}).toList(), "경로 정책");
      ruleConflicts(matrix.rules.stream().map(rule -> new String[]{rule.method(), rule.path()}).toList(), "권한표");
      AuthEffectRules.validate(effects);
      // These are editable business rules. Obvious pasted credentials are not an export escape hatch.
      for (String value : List.of(denialRules, responseRules)) rejectCredentialText(value);
      for (var rule : effects) {
        var c = rule.config();
        // Inactive rules must not carry malformed or cross-origin values waiting to be enabled later.
        try {
          new AuthEffectCheck.Config(true, c.method(), c.path(), c.url(), c.jsonPath(), c.expected(), c.mode(), c.attempts());
          if (!AuthEffectRules.canonicalOrigin(c.url()).equals(rule.origin())) throw invalid("조회 Origin 불일치");
        } catch (RuntimeException error) { throw invalid("비활성 규칙도 유효한 같은 Origin의 GET·JSON 관측 설정이어야 합니다."); }
        rejectCredentialText(rule.config().expected());
        safeReadUrl(rule.config().url());
      }
    }
  }

  record Bundle(String name, String origin, String method, String path, long revision,
                List<String> roles, Settings settings) {
    Bundle {
      name = text(name, 64, "묶음 이름").strip();
      if (name.isBlank() || name.chars().anyMatch(Character::isISOControl)) throw invalid("묶음 이름을 입력하세요.");
      origin = AuthSetupBundles.origin(origin);
      method = text(method, 12, "메서드").strip().toUpperCase(Locale.ROOT);
      path = text(path, 512, "경로").strip();
      if (!method.matches("GET|HEAD|OPTIONS|POST|PUT|PATCH|DELETE")) throw invalid("묶음 메서드를 확인하세요.");
      AuthPolicy.parse(method + " " + path + " PUBLIC", AuthPolicy.Access.UNSPECIFIED);
      if (revision < 0 || revision > 1_000_000_000L) throw invalid("묶음 revision 범위를 확인하세요.");
      roles = List.copyOf(roles);
      if (!roles.equals(ROLE_PLACEHOLDERS)) throw invalid("묶음은 실제 계정 이름 대신 고정 역할 자리표시자 4개만 저장합니다.");
      Objects.requireNonNull(settings, "settings");
      for (var rule : settings.effects())
        if (!AuthSetupBundles.origin(rule.origin()).equals(origin)) throw invalid("쓰기 조회 규칙은 묶음과 같은 Origin이어야 합니다.");
    }
    String key() { return origin + "\n" + method + "\n" + path + "\n" + name; }
    Bundle withName(String value) { return new Bundle(value, origin, method, path, revision, roles, settings); }
    Bundle withRevision(long value) { return new Bundle(name, origin, method, path, value, roles, settings); }
    String label() { return name + " · r" + revision + " · " + method + " " + origin + path; }
    @Override public String toString() { return label(); }
  }

  private final LinkedHashMap<String, Bundle> bundles = new LinkedHashMap<>();
  synchronized List<Bundle> snapshot() { return List.copyOf(bundles.values()); }
  synchronized Bundle get(String key) { return bundles.get(key); }

  /** Compare-and-save makes reviewable revisions, without silent last-writer overwrite. */
  synchronized Bundle save(Bundle draft, long expectedRevision) {
    Bundle previous = bundles.get(draft.key());
    long actual = previous == null ? 0 : previous.revision();
    if (expectedRevision != actual) throw invalid("묶음이 검토 이후 변경되었습니다. 현재 revision을 다시 확인하세요.");
    if (previous == null && bundles.size() == MAX_BUNDLES) throw invalid("설정 묶음은 최대 32개입니다.");
    Bundle next = draft.withRevision(actual + 1);
    List<Bundle> proposed = new ArrayList<>(bundles.values());
    proposed.removeIf(bundle -> bundle.key().equals(next.key())); proposed.add(next);
    encode(proposed); // Validate the total file budget before mutating memory.
    bundles.put(next.key(), next); return next;
  }

  /** Imports are all-or-nothing; changed existing bundles require explicit local revision review. */
  synchronized void merge(List<Bundle> imported) {
    LinkedHashMap<String, Bundle> proposed = new LinkedHashMap<>(bundles);
    for (Bundle bundle : checked(imported)) {
      if (bundle.revision() < 1) throw invalid("가져올 묶음 revision은 1 이상이어야 합니다.");
      Bundle previous = proposed.get(bundle.key());
      if (previous != null && !previous.equals(bundle))
        throw invalid("기존 묶음과 충돌: " + bundle.name() + " · 기존 r" + previous.revision() + " / 가져올 r" + bundle.revision());
      proposed.putIfAbsent(bundle.key(), bundle);
    }
    encode(new ArrayList<>(proposed.values()));
    bundles.clear(); bundles.putAll(proposed);
  }

  static String encode(List<Bundle> values) {
    List<Bundle> bundles = checked(values);
    LinkedHashMap<String, Object> root = new LinkedHashMap<>();
    root.put("schema", SCHEMA);
    root.put("bundles", bundles.stream().map(AuthSetupBundles::toJson).toList());
    String json = MetadataExport.json(root) + "\n";
    if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid("묶음 JSON은 128KiB 이하여야 합니다.");
    return json;
  }

  static List<Bundle> decode(String json) {
    if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid("묶음 JSON은 128KiB 이하여야 합니다.");
    Map<String, Object> root = object(MinerJson.parse(json, MAX_BYTES, 12000), Set.of("schema", "bundles"));
    if (!SCHEMA.equals(string(root, "schema"))) throw invalid("지원하는 설정 묶음 schema가 아닙니다.");
    List<Bundle> result = new ArrayList<>();
    for (Object value : array(root, "bundles")) {
      Map<String, Object> row = object(value, Set.of("name", "origin", "method", "path", "revision", "roles", "settings"));
      Map<String, Object> settings = object(row.get("settings"), Set.of("ownerPath", "ignoredFields", "importantFields", "denialRules", "responseRules", "policyRules", "fallback", "matrixRules", "unorderedArrays", "effects"));
      List<AuthEffectRules.Rule> effects = new ArrayList<>();
      for (Object effectValue : array(settings, "effects")) {
        Map<String, Object> rule = object(effectValue, Set.of("name", "origin", "enabled", "method", "path", "url", "jsonPath", "expected", "mode", "attempts"));
        AuthEffectCheck.Config config;
        try {
          config = new AuthEffectCheck.Config(bool(rule, "enabled"), string(rule, "method"), string(rule, "path"),
              string(rule, "url"), string(rule, "jsonPath"), string(rule, "expected"),
              AuthEffectCheck.Mode.valueOf(string(rule, "mode")), Math.toIntExact(number(rule, "attempts")));
        } catch (RuntimeException error) { throw invalid("쓰기 조회 규칙 형식/값을 확인하세요."); }
        effects.add(new AuthEffectRules.Rule(string(rule, "name"), string(rule, "origin"), config));
      }
      List<String> roles = new ArrayList<>();
      for (Object role : array(row, "roles")) { if (!(role instanceof String text)) throw invalid("역할 형식을 확인하세요."); roles.add(text); }
      AuthPolicy.Access fallback;
      try { fallback = AuthPolicy.Access.valueOf(string(settings, "fallback")); }
      catch (RuntimeException error) { throw invalid("기본 접근 정책을 확인하세요."); }
      Settings payload = new Settings(string(settings, "ownerPath"), string(settings, "ignoredFields"),
          string(settings, "importantFields"), string(settings, "denialRules"), string(settings, "responseRules"),
          string(settings, "policyRules"), fallback, string(settings, "matrixRules"), bool(settings, "unorderedArrays"), effects);
      result.add(new Bundle(string(row, "name"), string(row, "origin"), string(row, "method"), string(row, "path"),
          number(row, "revision"), roles, payload));
    }
    return checked(result);
  }

  static List<Bundle> read(Path path) throws IOException {
    if (Files.size(path) > MAX_BYTES) throw invalid("가져올 JSON 파일이 128KiB를 초과합니다.");
    byte[] bytes;
    try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_BYTES + 1); }
    if (bytes.length > MAX_BYTES) throw invalid("가져올 JSON 파일이 128KiB를 초과합니다.");
    String json = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    return decode(json);
  }

  static void write(Path path, List<Bundle> values) throws IOException {
    byte[] bytes = encode(values).getBytes(StandardCharsets.UTF_8);
    Path destination = path.toAbsolutePath(), parent = destination.getParent();
    if (parent == null || !Files.isDirectory(parent)) throw invalid("저장 폴더를 확인하세요.");
    Path temporary = Files.createTempFile(parent, ".workbench-auth-setup-", ".tmp");
    try {
      try { Files.setPosixFilePermissions(temporary, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
      catch (UnsupportedOperationException ignored) { }
      Files.write(temporary, bytes);
      try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
    } finally { Files.deleteIfExists(temporary); }
  }

  static String preview(List<Bundle> bundles) {
    return "저장/가져오기 대상 전체 JSON입니다. Cookie·Authorization·인증 토큰·실제 계정 이름/소유값·세션 갱신 설정은 이 schema에 없습니다.\n"
        + "역할 1~3과 비로그인 권한 자리표시자만 저장합니다. 적용 후 실제 계정을 직접 연결하세요.\n"
        + "조회 URL·JSON 규칙·업무 기대값은 저장됩니다. 이 필드에 민감값을 넣지 않았는지 직접 확인하세요.\n"
        + "적용하면 현재 비교 계정 지정·신원/갱신 연결이 해제됩니다. 인증 후보는 유지하므로 역할 자리표시자에 맞게 다시 지정하세요.\n"
        + "묶음 적용은 설정만 변경하며, 네트워크 요청이나 비교 시작은 하지 않습니다.\n\n" + pretty(encode(bundles));
  }

  static String origin(String value) {
    try {
      URI uri = URI.create(value);
      String scheme = Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT);
      if (!Set.of("http", "https").contains(scheme) || uri.getHost() == null || uri.getUserInfo() != null
          || uri.getRawQuery() != null || uri.getRawFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
        throw invalid("Origin은 인증값·경로·쿼리 없는 http(s) 주소여야 합니다.");
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      if (host.contains(":")) host = "[" + host.replace("[", "").replace("]", "") + "]";
      int port = uri.getPort() < 0 ? (scheme.equals("https") ? 443 : 80) : uri.getPort();
      if (port < 1 || port > 65535) throw invalid("Origin 포트를 확인하세요.");
      return scheme + "://" + host + ":" + port;
    } catch (RuntimeException error) { throw invalid("Origin은 인증값·경로·쿼리 없는 http(s) 주소여야 합니다."); }
  }

  private static void safeReadUrl(String value) {
    if (value.isBlank()) return;
    URI uri;
    try { uri = URI.create(value); }
    catch (RuntimeException error) { throw invalid("조회 URL 형식을 확인하세요."); }
    if (uri.getUserInfo() != null) throw invalid("조회 URL의 인증값은 저장할 수 없습니다.");
    if (uri.getRawQuery() != null) for (String parameter : uri.getRawQuery().split("&")) {
      String key;
      try { key = URLDecoder.decode(parameter.split("=", 2)[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT); }
      catch (RuntimeException error) { throw invalid("조회 URL 쿼리 형식을 확인하세요."); }
      if (SECRET_QUERY.contains(key)) throw invalid("조회 URL의 인증/비밀 쿼리 값은 저장할 수 없습니다.");
    }
  }

  private static void rejectCredentialText(String value) {
    if (CREDENTIAL_TEXT.matcher(value).find()) throw invalid("인증 헤더/토큰 형태의 값은 설정 묶음에 저장할 수 없습니다.");
  }
  private static void ruleConflicts(List<String[]> rules, String label) {
    for (int i = 0; i < rules.size(); i++) for (int j = i + 1; j < rules.size(); j++) {
      String[] a = rules.get(i), b = rules.get(j);
      boolean method = a[0].equals("*") || b[0].equals("*") || a[0].equals(b[0]);
      String ap = a[1].replaceAll("\\{[A-Za-z_][A-Za-z0-9_]*}", "{}"), bp = b[1].replaceAll("\\{[A-Za-z_][A-Za-z0-9_]*}", "{}");
      if (method && ap.equals(bp)) throw invalid(label + "에 중복/동일 우선순위 규칙이 있습니다. 명시적으로 정리한 뒤 저장하세요.");
    }
  }
  private static List<Bundle> checked(List<Bundle> values) {
    if (values == null || values.size() > MAX_BUNDLES) throw invalid("설정 묶음은 최대 32개입니다.");
    Set<String> keys = new HashSet<>();
    for (Bundle bundle : values) if (bundle == null || !keys.add(bundle.key())) throw invalid("중복된 설정 묶음이 있습니다.");
    return List.copyOf(values);
  }
  private static LinkedHashMap<String, Object> toJson(Bundle bundle) {
    LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    map.put("name", bundle.name()); map.put("origin", bundle.origin()); map.put("method", bundle.method());
    map.put("path", bundle.path()); map.put("revision", bundle.revision()); map.put("roles", bundle.roles());
    Settings s = bundle.settings(); LinkedHashMap<String, Object> settings = new LinkedHashMap<>();
    settings.put("ownerPath", s.ownerPath()); settings.put("ignoredFields", s.ignoredFields()); settings.put("importantFields", s.importantFields());
    settings.put("denialRules", s.denialRules()); settings.put("responseRules", s.responseRules()); settings.put("policyRules", s.policyRules());
    settings.put("fallback", s.fallback().name()); settings.put("matrixRules", s.matrixRules()); settings.put("unorderedArrays", s.unorderedArrays());
    settings.put("effects", s.effects().stream().map(rule -> {
      var c = rule.config(); LinkedHashMap<String, Object> effect = new LinkedHashMap<>();
      effect.put("name", rule.name()); effect.put("origin", rule.origin()); effect.put("enabled", c.enabled());
      effect.put("method", c.method()); effect.put("path", c.path()); effect.put("url", c.url()); effect.put("jsonPath", c.jsonPath());
      effect.put("expected", c.expected()); effect.put("mode", c.mode().name()); effect.put("attempts", c.attempts()); return effect;
    }).toList());
    map.put("settings", settings); return map;
  }
  @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value, Set<String> keys) {
    if (!(value instanceof Map<?, ?> map) || !map.keySet().equals(keys)) throw invalid("알 수 없거나 누락된 JSON 필드가 있습니다. 인증 필드는 허용하지 않습니다.");
    return (Map<String, Object>) map;
  }
  private static String string(Map<String, Object> map, String key) { if (!(map.get(key) instanceof String value)) throw invalid(key + " 문자열 형식을 확인하세요."); return value; }
  private static boolean bool(Map<String, Object> map, String key) { if (!(map.get(key) instanceof Boolean value)) throw invalid(key + " 참/거짓 형식을 확인하세요."); return value; }
  private static long number(Map<String, Object> map, String key) { try { if (!(map.get(key) instanceof java.math.BigDecimal value)) throw invalid(key + " 정수 형식을 확인하세요."); return value.longValueExact(); } catch (ArithmeticException error) { throw invalid(key + " 정수 범위를 확인하세요."); } }
  private static List<?> array(Map<String, Object> map, String key) { if (!(map.get(key) instanceof List<?> value)) throw invalid(key + " 목록 형식을 확인하세요."); return value; }
  private static String text(String value, int max, String label) { if (value == null || value.length() > max || value.indexOf('\0') >= 0) throw invalid(label + " 크기/형식을 확인하세요."); return value; }
  private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
  static String pretty(String json) {
    StringBuilder out = new StringBuilder(); boolean string = false, escaped = false; int depth = 0;
    for (char c : json.toCharArray()) {
      if (string) { out.append(c); if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') string = false; continue; }
      if (c == '"') { string = true; out.append(c); }
      else if (c == '{' || c == '[') { out.append(c).append('\n'); depth++; out.append("  ".repeat(depth)); }
      else if (c == '}' || c == ']') { depth--; out.append('\n').append("  ".repeat(depth)).append(c); }
      else if (c == ',') out.append(",\n").append("  ".repeat(depth));
      else if (c == ':') out.append(": "); else if (!Character.isWhitespace(c)) out.append(c);
    }
    return out.toString();
  }
}
