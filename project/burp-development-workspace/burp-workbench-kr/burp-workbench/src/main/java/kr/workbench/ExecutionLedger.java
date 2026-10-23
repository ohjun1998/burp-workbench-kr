package kr.workbench;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Bounded metadata provenance. Historical runs keep their original conditions and evidence links. */
final class ExecutionLedger {
  static final int RUN_LIMIT = 2000, POLICY_LIMIT = 10000;
  enum State { RUNNING, COMPLETED, CANCELLED, FAILED }
  record Policy(String account, String method, String url, String fingerprint, long revision) {}
  record Binding(String account, long revision, String fingerprint, Set<String> tasks) {
    Binding { tasks = Set.copyOf(tasks); }
  }
  record Run(String id, String originalId, String method, String url, String started, String finished,
             State state, String summary, List<Binding> bindings, Set<String> evidence) {
    Run { bindings = List.copyOf(bindings); evidence = Set.copyOf(evidence); }
  }
  final LinkedHashMap<String, Policy> policies = new LinkedHashMap<>();
  final LinkedHashMap<String, Run> runs = new LinkedHashMap<>();
  private final Set<String> livePolicies = new HashSet<>();
  private final Map<String, String> latestRuns = new HashMap<>();
  private long revision;

  static String key(String account, String method, String url) {
    return account + "\n" + method + "\n" + Objects.requireNonNull(ActivityTracker.safeUrl(url));
  }
  boolean configure(String account, String method, String url, String fingerprint) {
    String key = key(account, method, url);
    Policy old = policies.get(key);
    if (old != null && old.fingerprint.equals(fingerprint)) { livePolicies.add(key); return false; }
    if (old == null && policies.size() >= POLICY_LIMIT) throw new IllegalArgumentException("실행 조건 최대 10,000개 · 프로젝트를 나누세요.");
    policies.put(key, new Policy(account, method, ActivityTracker.safeUrl(url), fingerprint, ++revision));
    livePolicies.add(key);
    return old != null;
  }
  Policy policy(String account, String method, String url) { return policies.get(key(account, method, url)); }
  void begin(String id, String originalId, String method, String url, List<Binding> bindings) {
    if (runs.containsKey(id)) throw new IllegalArgumentException("이미 등록된 실행 ID입니다.");
    if (runs.size() >= RUN_LIMIT) throw new IllegalArgumentException("실행 연결 최대 2,000개 · 현재 프로젝트를 저장하고 새 프로젝트를 사용하세요.");
    runs.put(id, new Run(id, bounded(originalId, 160), method, ActivityTracker.safeUrl(url),
        Instant.now().toString(), "", State.RUNNING, "진행 중", bindings, Set.of()));
    for (Binding binding : bindings) latestRuns.put(key(binding.account, method, url), id);
  }
  void finish(String id, State state, String summary) {
    Run run = runs.get(id);
    if (run == null || run.state != State.RUNNING) return;
    if (state == State.RUNNING) throw new IllegalArgumentException("종료 상태를 지정하세요.");
    String safeSummary = switch (state) {
      case COMPLETED -> "모든 지정 계정의 실행 종료 · 판정은 별도 검토";
      case CANCELLED -> "실행 중지 · 완료 근거로 사용 불가";
      case FAILED -> "실행 실패 · 상세 원인은 권한 비교 기록에서 확인";
      default -> throw new IllegalArgumentException("종료 상태를 지정하세요.");
    };
    runs.put(id, new Run(run.id, run.originalId, run.method, run.url, run.started,
        Instant.now().toString(), state, safeSummary, run.bindings, run.evidence));
  }
  void evidence(String id, String hash) {
    Run run = runs.get(id);
    if (run == null) return;
    if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("증거 SHA-256 형식을 확인하세요.");
    Set<String> files = new LinkedHashSet<>(run.evidence);
    if (files.size() >= 64 && !files.contains(hash)) throw new IllegalArgumentException("실행별 증거 ZIP 최대 64개");
    files.add(hash);
    runs.put(id, new Run(run.id, run.originalId, run.method, run.url, run.started, run.finished,
        run.state, run.summary, run.bindings, files));
  }
  Run latest(String account, String method, String url) {
    return runs.get(latestRuns.get(key(account, method, url)));
  }
  boolean current(Run run, String account) {
    Policy policy = policy(account, run.method, run.url);
    return policy != null && livePolicies.contains(key(account, run.method, run.url))
        && run.bindings.stream().anyMatch(b -> b.account.equals(account)
        && b.revision == policy.revision && b.fingerprint.equals(policy.fingerprint));
  }
  String completionProblem(String account, String method, String url) {
    Run run = latest(account, method, url);
    if (run == null) return ""; // Existing manual workflows without an execution link remain manual.
    if (run.state != State.COMPLETED) return "최근 연결 실행이 " + label(run.state) + "입니다. 완료 실행의 근거를 먼저 확인하세요.";
    if (!current(run, account)) return "최근 실행 뒤 권한·조회 조건이 변경되었습니다. 현재 조건으로 다시 비교하세요.";
    return "";
  }
  String summary(String id) {
    Run run = runs.get(id);
    if (run == null) return "실행 연결 없음";
    return "실행 ID: " + run.id + "\n원본 ID: " + run.originalId + "\n대상: " + run.method + " " + run.url
        + "\n상태: " + label(run.state) + " · " + run.summary
        + "\n조건 revision: " + run.bindings.stream().map(b -> b.account + " / r" + b.revision).toList()
        + "\n업무 ID (최대 32개 표시): " + run.bindings.stream().flatMap(b -> b.tasks.stream()).distinct().limit(32).toList()
        + "\n증거 ZIP SHA-256: " + run.evidence;
  }
  static String label(State state) { return switch (state) {
    case RUNNING -> "진행 중"; case COMPLETED -> "실행 종료 (판정 검토 필요)";
    case CANCELLED -> "취소됨"; case FAILED -> "실행 실패";
  }; }
  void clear() { policies.clear(); runs.clear(); livePolicies.clear(); latestRuns.clear(); revision = 0; }
  void replace(ExecutionLedger other) {
    clear(); policies.putAll(other.policies); runs.putAll(other.runs); revision = other.revision;
    for (Run run : runs.values())
      for (Binding binding : run.bindings) latestRuns.put(key(binding.account, run.method, run.url), run.id);
  }
  Map<String, Object> save() {
    return Map.of("revision", revision, "policies", policies.values().stream().map(p -> Map.of(
        "account", p.account, "method", p.method, "url", p.url, "fingerprint", p.fingerprint, "revision", p.revision)).toList(),
        "runs", runs.values().stream().map(r -> Map.ofEntries(Map.entry("id", r.id), Map.entry("originalId", r.originalId),
            Map.entry("method", r.method), Map.entry("url", r.url), Map.entry("started", r.started), Map.entry("finished", r.finished),
            Map.entry("state", r.state.name()), Map.entry("summary", r.summary), Map.entry("evidence", r.evidence),
            Map.entry("bindings", r.bindings.stream().map(b -> Map.of("account", b.account, "revision", b.revision,
                "fingerprint", b.fingerprint, "tasks", b.tasks)).toList()))).toList());
  }
  void load(Map<?, ?> doc, Set<String> accounts, Set<String> tasks) {
    ExecutionLedger next = new ExecutionLedger();
    for (Object obj : list(doc.get("policies"), POLICY_LIMIT)) {
      Map<?, ?> row = map(obj); String account = text(row, "account", 64), method = text(row, "method", 32), url = text(row, "url", 8192);
      if (!accounts.contains(account) || ActivityTracker.safeUrl(url) == null) throw new IllegalArgumentException("실행 조건 계정/URL 참조 오류");
      Policy p = new Policy(account, method, url, digest(row), number(row.get("revision")));
      if (p.revision < 1 || next.policies.putIfAbsent(key(account, method, url), p) != null) throw new IllegalArgumentException("실행 조건 중복/revision 오류");
      next.revision = Math.max(next.revision, p.revision);
    }
    for (Object obj : list(doc.get("runs"), RUN_LIMIT)) {
      Map<?, ?> row = map(obj); String id = text(row, "id", 64), method = text(row, "method", 32), url = text(row, "url", 8192);
      if (!id.matches("[A-Za-z0-9_-]{1,64}") || ActivityTracker.safeUrl(url) == null) throw new IllegalArgumentException("실행 ID/URL 오류");
      List<Binding> bindings = new ArrayList<>();
      for (Object value : list(row.get("bindings"), 4)) {
        Map<?, ?> binding = map(value); String account = text(binding, "account", 64);
        Set<String> jobs = strings(binding.get("tasks"), 1000, 64);
        if (!accounts.contains(account) || !tasks.containsAll(jobs)) throw new IllegalArgumentException("실행 계정/업무 참조 오류");
        long policyRevision = number(binding.get("revision"));
        if (policyRevision < 1 || policyRevision > next.revision) throw new IllegalArgumentException("실행 조건 revision 참조 오류");
        bindings.add(new Binding(account, policyRevision, digest(binding), jobs));
      }
      State state; try { state = State.valueOf(text(row, "state", 32)); } catch (Exception e) { throw new IllegalArgumentException("실행 상태 오류"); }
      // A restarted project cannot resume an in-flight comparison.
      if (state == State.RUNNING) state = State.CANCELLED;
      Set<String> evidence = strings(row.get("evidence"), 64, 64);
      if (evidence.stream().anyMatch(hash -> !hash.matches("[0-9a-f]{64}"))) throw new IllegalArgumentException("실행 증거 해시 오류");
      Run run = new Run(id, text(row, "originalId", 160), method, url, text(row, "started", 64),
          text(row, "finished", 64), state, text(row, "summary", 512), bindings, evidence);
      if (next.runs.putIfAbsent(id, run) != null) throw new IllegalArgumentException("실행 ID 중복");
    }
    replace(next);
  }
  private static String digest(Map<?, ?> row) { String value = text(row, "fingerprint", 64); if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("조건 fingerprint 오류"); return value; }
  private static long number(Object value) { if (!(value instanceof Number n) || n.longValue() < 0) throw new IllegalArgumentException("실행 revision 오류"); return n.longValue(); }
  private static Map<?, ?> map(Object value) { if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("실행 기록 형식 오류"); return map; }
  private static List<?> list(Object value, int max) { if (!(value instanceof List<?> list) || list.size() > max) throw new IllegalArgumentException("실행 기록 개수 오류"); return list; }
  private static Set<String> strings(Object value, int max, int length) { Set<String> values = new LinkedHashSet<>(); for (Object item : list(value, max)) values.add(bounded(Objects.toString(item, ""), length)); return values; }
  private static String text(Map<?, ?> map, String key, int max) { return bounded(Objects.toString(map.get(key), ""), max); }
  private static String bounded(String value, int max) { if (value == null || value.length() > max || value.indexOf('\0') >= 0) throw new IllegalArgumentException("실행 메타데이터 길이/형식 오류"); return value; }

  /** Operation-specific effective conditions: unrelated permission/effect rows cannot invalidate it. */
  static String fingerprint(AuthComparePanel.Context c, int slot, String method, String url) {
    var rules = c.rules(); var profile = c.profiles()[slot]; var effect = c.effectFor(method, url);
    var identity = c.sessionChecks()[slot];
    List<Object> parts = new ArrayList<>();
    parts.add(List.of(c.matrix().expected(method, url, slot), c.policy().forRequest(method, url),
        new TreeSet<>(rules.ignored()), new TreeSet<>(rules.important()), rules.ownerPath(), rules.denied(), rules.unorderedArrays(),
        c.owners()[slot], c.roles()[slot], c.tenants()[slot], c.writes(), c.largeFiles(),
        c.filter().isEmpty() || url.contains(c.filter()), new TreeSet<>(c.names()), new TreeSet<>(c.identityCookies())));
    parts.add(profile == null ? List.of() : List.of(profile.origin(), new TreeMap<>(profile.headers())));
    parts.add(c.responseRules().rules); parts.add(Objects.toString(c.sessions()[slot], ""));
    parts.add(identity == null ? List.of() : List.of(identity.url(), identity.identityPath(), identity.expectedIdentity(), identity.claims()));
    parts.add(effect == null || !effect.enabled() ? List.of("no-effect") : List.of(effect.method(), effect.path(), effect.url(),
        effect.jsonPath(), effect.expected(), effect.mode(), effect.attempts()));
    return Core.hash(MetadataExport.json(parts).getBytes(StandardCharsets.UTF_8));
  }
}
