package kr.workbench;

import burp.api.montoya.http.message.requests.HttpRequest;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

/**
 * Explicit account identity and manual work completion. Credentials and bindings never leave
 * memory.
 */
final class AssessmentTracker {
  static final int ACCOUNT_LIMIT = 16,
      OPERATION_LIMIT = 10000,
      TASK_LIMIT = 1000,
      EVENT_LIMIT = 5000,
      INPUT_LIMIT = 50000;

  record AccountInput(String label, String origin, String role, String tenant, boolean anonymous) {}

  record Account(
      String id, String label, String origin, String role, String tenant, boolean anonymous) {
    public String toString() {
      return label + (anonymous ? " · 비로그인" : "");
    }
  }

  record Binding(
      String account,
      String origin,
      Map<String, String> headers,
      Set<String> names,
      Set<String> cookies) {
    Binding {
      headers = Map.copyOf(headers);
      names = Set.copyOf(names);
      cookies = Set.copyOf(cookies);
    }
  }

  record Session(
      String account, String origin, Set<String> names, Set<String> cookies, String fingerprint) {}

  record Event(String time, String account, String target, String action, String detail) {}

  record OperationView(
      String account,
      String method,
      String url,
      String state,
      long observed,
      long inspections,
      long evidence,
      String result,
      String review,
      String completedAt,
      String last,
      List<ActivityTracker.ParameterView> parameters,
      String completionContext) {}

  record TaskView(
      String id,
      String title,
      String note,
      int endpoints,
      int accounts,
      int required,
      int inspected,
      int completed,
      int evidence,
      String state,
      String last,
      boolean requiresEvidence,
      boolean strictInputs,
      boolean allEvidence,
      Set<String> criticalInputs,
      int evidenced) {}

  record EvidenceLink(String id, String filename, String time, Set<String> operations) {}

  /** Review holds metadata and an opaque state stamp, never a credential or request body. */
  record CompletionReview(String account, String method, String url, String task,
                          String summary, String stamp) {}

  static final class Operation {
    final String account, method, url;
    final LinkedHashMap<String, Boolean> inputs = new LinkedHashMap<>();
    final Set<String> evidence = new LinkedHashSet<>();
    long observed, inspections;
    boolean completed;
    String review = "", result = "", completedAt = "", completionContext = "", last = now();
    final Map<String, String> inputHashes = new HashMap<>();

    Operation(String account, String method, String url) {
      this.account = account;
      this.method = method;
      this.url = url;
    }

    String state() {
      return !review.isEmpty()
          ? "재검토 필요"
          : completed ? "점검 완료" : inspections > 0 ? "검사 결과 있음" : observed > 0 ? "요청 관측" : "미점검";
    }
  }

  static final class Task {
    final String id;
    TaskView cachedView;
    String title, note, last = now(), review = "";
    boolean completed, requiresEvidence, strictInputs, allEvidence;
    final Set<String> criticalInputs = new LinkedHashSet<>();
    final Set<String> accounts = new LinkedHashSet<>(), endpoints = new LinkedHashSet<>();

    Task(String id, String title, String note) {
      this.id = id;
      this.title = title;
      this.note = note;
    }
  }

  final LinkedHashMap<String, Account> accounts = new LinkedHashMap<>();
  final LinkedHashMap<String, Operation> operations = new LinkedHashMap<>();
  final LinkedHashMap<String, Task> tasks = new LinkedHashMap<>();
  final LinkedHashMap<String, EvidenceLink> artifacts = new LinkedHashMap<>();
  final ExecutionLedger executions = new ExecutionLedger();
  final LinkedHashMap<String, String> mapperTasks = new LinkedHashMap<>();
  final ArrayDeque<Event> events = new ArrayDeque<>();
  private final Map<String, Path> evidencePaths = new HashMap<>();
  private final Map<String, String> evidenceStatus = new HashMap<>();
  private final List<Session> bindings = new ArrayList<>();
  private byte[] salt = new byte[32];
  long revision, rejected;
  int inputCount;

  private record CachedOperation(List<String> known, OperationView view) {}

  private final LinkedHashMap<String, CachedOperation> operationViews =
      new LinkedHashMap<>(128, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, CachedOperation> entry) {
          return size() > OPERATION_LIMIT;
        }
      };
  private static final int DETAIL_CACHE_LIMIT = 32;
  private final LinkedHashMap<String, List<OperationView>> detailSnapshots =
      new LinkedHashMap<>(32, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, List<OperationView>> entry) {
          return size() > DETAIL_CACHE_LIMIT;
        }
      };

  synchronized int detailCacheSize() {
    return detailSnapshots.size();
  }

  private final Map<String, Long> accountRevisions = new HashMap<>();
  private long accountRevisionSequence;
  private List<Account> accountSnapshot;
  private List<TaskView> taskSnapshot;
  long operationViewBuilds, taskViewBuilds;

  synchronized long revision() {
    return revision;
  }

  synchronized boolean hasTasks() {
    return !tasks.isEmpty();
  }

  synchronized long accountRevision(String id) {
    return accountRevisions.getOrDefault(id, 0L);
  }

  private void invalidateTask(Task task) {
    task.cachedView = null;
    detailSnapshots.remove(task.id);
    taskSnapshot = null;
  }

  private void invalidateOperation(Operation operation) {
    operationViews.remove(opKey(operation.account, operation.method, operation.url));
    accountRevisions.put(operation.account, ++accountRevisionSequence);
    String endpoint = endpoint(operation.method, operation.url);
    for (Task task : tasks.values()) {
      if (task.accounts.contains(operation.account) && task.endpoints.contains(endpoint)) {
        invalidateTask(task);
      }
    }
  }

  private void invalidateAccount(String account) {
    accountSnapshot = null;
    accountRevisions.put(account, ++accountRevisionSequence);
    operationViews.keySet().removeIf(key -> key.startsWith(account + " "));
    for (Task task : tasks.values()) if (task.accounts.contains(account)) invalidateTask(task);
  }

  private void invalidateAll() {
    operationViews.clear();
    detailSnapshots.clear();
    accountSnapshot = null;
    taskSnapshot = null;
    // Existing panels may hold a revision for an ID reused by an import.
    accountRevisions.clear();
    for (String account : accounts.keySet())
      accountRevisions.put(account, ++accountRevisionSequence);
    for (Task task : tasks.values()) invalidateTask(task);
  }

  AssessmentTracker() {
    new SecureRandom().nextBytes(salt);
  }

  static String now() {
    return Instant.now().toString();
  }

  static String origin(String raw) {
    String safe = ActivityTracker.safeUrl(raw);
    if (safe == null) throw new IllegalArgumentException("HTTP(S) Origin이 필요합니다.");
    URI u = URI.create(safe);
    return u.getScheme() + "://" + u.getRawAuthority();
  }

  static String text(String value, int max) {
    if (value == null
        || value.length() > max
        || value.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\t'))
      throw new IllegalArgumentException("점검 메타데이터 길이/문자 오류");
    return value.strip();
  }

  static String endpoint(String method, String url) {
    String safe = ActivityTracker.safeUrl(url);
    if (safe == null || method == null || !method.matches("[A-Z]{1,24}"))
      throw new IllegalArgumentException("확정된 메서드/URL을 선택하세요.");
    return method + " " + safe;
  }

  static String opKey(String account, String method, String url) {
    return account + " " + endpoint(method, url);
  }

  synchronized String register(AccountInput input) {
    String label = text(input.label, 64),
        base = origin(input.origin),
        role = text(input.role, 64),
        tenant = text(input.tenant, 64);
    if (label.isEmpty()) throw new IllegalArgumentException("점검 계정 이름을 입력하세요.");
    if (accounts.size() >= ACCOUNT_LIMIT) throw new IllegalArgumentException("점검 계정 최대 16개");
    if (accounts.values().stream().anyMatch(a -> a.label.equals(label) && a.origin.equals(base)))
      throw new IllegalArgumentException("동일 Origin/계정 이름이 있습니다. 기존 계정을 선택하거나 다른 이름을 쓰세요.");
    String id = UUID.randomUUID().toString();
    accounts.put(id, new Account(id, label, base, role, tenant, input.anonymous));
    invalidateAccount(id);
    event(id, "", "계정 등록", label);
    return id;
  }

  synchronized void editAccount(String id, String label, String role, String tenant) {
    Account old = account(id);
    label = text(label, 64);
    role = text(role, 64);
    tenant = text(tenant, 64);
    if (label.isEmpty()) throw new IllegalArgumentException("점검 계정 이름을 입력하세요.");
    for (Account a : accounts.values())
      if (!a.id.equals(id) && a.origin.equals(old.origin) && a.label.equals(label))
        throw new IllegalArgumentException("동일 Origin/계정 이름 중복");
    accounts.put(id, new Account(id, label, old.origin, role, tenant, old.anonymous));
    if (!old.role.equals(role) || !old.tenant.equals(tenant)) {
      for (Operation op : operations.values())
        if (op.account.equals(id) && op.completed) {
          op.review = "완료 후 계정 역할/조직 변경";
          reopenTasks(op, op.review);
          op.last = now();
        }
      event(id, "", "역할/조직 변경", old.role + " / " + old.tenant + " → " + role + " / " + tenant);
    }
    invalidateAccount(id);
    event(id, "", "계정 이름/역할 변경", old.label + " → " + label);
  }

  synchronized List<Account> accounts() {
    if (accountSnapshot == null) accountSnapshot = List.copyOf(accounts.values());
    return accountSnapshot;
  }

  synchronized Account account(String id) {
    Account a = accounts.get(id);
    if (a == null) throw new IllegalArgumentException("등록된 점검 계정을 선택하세요.");
    return a;
  }

  private String fingerprint(Map<String, String> headers, Set<String> names, Set<String> cookies) {
    List<String> values = new ArrayList<>();
    headers.forEach(
        (name, value) -> {
          String n = name.toLowerCase(Locale.ROOT);
          if (!names.contains(n) || AuthComparePanel.CSRF.contains(n) || value.isBlank()) return;
          if (n.equals("cookie")) {
            List<String> pairs = new ArrayList<>();
            for (String part : value.split(";")) {
              int i = part.indexOf('=');
              if (i > 0 && (cookies.isEmpty() || cookies.contains(part.substring(0, i).strip())))
                pairs.add(part.strip());
            }
            Collections.sort(pairs);
            if (!pairs.isEmpty()) values.add(n + ":" + String.join("; ", pairs));
          } else values.add(n + ":" + value);
        });
    if (values.isEmpty()) return "anonymous";
    Collections.sort(values);
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(salt);
      for (String value : values) {
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        md.update(Integer.toString(b.length).getBytes(StandardCharsets.UTF_8));
        md.update((byte) ':');
        md.update(b);
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static Map<String, String> headers(HttpRequest q) {
    Map<String, String> out = new LinkedHashMap<>();
    for (var h : q.headers()) {
      String n = h.name().toLowerCase(Locale.ROOT);
      out.merge(n, h.value(), (a, b) -> a + (n.equals("cookie") ? "; " : "\n") + b);
    }
    return out;
  }

  synchronized void bind(List<Binding> requested) {
    List<Session> prepared = new ArrayList<>();
    for (Binding b : requested) {
      Account a = account(b.account);
      String base = origin(b.origin);
      if (!a.origin.equals(base)) throw new IllegalArgumentException("점검 계정 Origin과 요청이 다릅니다.");
      String fp = fingerprint(b.headers, b.names, b.cookies);
      if (a.anonymous != fp.equals("anonymous"))
        throw new IllegalArgumentException("비로그인/인증 계정 유형과 선택 세션이 다릅니다.");
      Session next = new Session(a.id, base, b.names, b.cookies, fp);
      for (Session old : bindings)
        if (sameBinding(old, next) && !old.account.equals(next.account))
          throw new IllegalArgumentException("같은 인증 조건이 다른 점검 계정에 연결되어 있습니다. 기존 세션 연결을 먼저 해제하세요.");
      for (Session old : prepared)
        if (sameBinding(old, next) && !old.account.equals(next.account))
          throw new IllegalArgumentException("동일 세션을 서로 다른 계정에 지정할 수 없습니다.");
      prepared.add(next);
    }
    if (bindings.size() + prepared.stream().distinct().filter(s -> !bindings.contains(s)).count()
        > 512) throw new IllegalArgumentException("세션 연결 최대 512개 · 오래된 계정 세션 연결을 해제하세요.");
    Set<String> changed = new LinkedHashSet<>();
    for (Session s : prepared) if (!bindings.contains(s)) {
      bindings.add(s);
      changed.add(s.account);
    }
    for (String id : changed) reviewAccountCompletion(id, "완료 후 인증 연결 추가 · 신원/조건 재확인");
    revision++;
  }

  private boolean sameBinding(Session a, Session b) {
    return a.origin.equals(b.origin)
        && a.names.equals(b.names)
        && a.cookies.equals(b.cookies)
        && a.fingerprint.equals(b.fingerprint);
  }

  synchronized void unbind(String id) {
    account(id);
    if (bindings.removeIf(s -> s.account.equals(id)))
      reviewAccountCompletion(id, "완료 후 인증 연결 해제 · 신원/조건 재확인");
    event(id, "", "세션 연결 해제", "점검 이력은 유지");
  }

  private void reviewAccountCompletion(String id, String reason) {
    for (Operation op : operations.values()) if (op.account.equals(id) && op.completed) {
      op.review = reason;
      op.last = now();
      reopenTasks(op, reason);
      invalidateOperation(op);
      event(id, endpoint(op.method, op.url), "완료 조건 변경", reason);
    }
    invalidateAccount(id);
  }

  synchronized String match(HttpRequest q) {
    if (q == null) return "";
    String base;
    try {
      base = origin(q.url());
    } catch (Exception e) {
      return "";
    }
    Map<String, String> headers = headers(q);
    Set<String> matches = new HashSet<>();
    for (Session s : bindings)
      if (s.origin.equals(base) && s.fingerprint.equals(fingerprint(headers, s.names, s.cookies)))
        matches.add(s.account);
    return matches.size() == 1 ? matches.iterator().next() : "";
  }

  private Operation operation(String id, String method, String url) {
    Account a = account(id);
    String safe = ActivityTracker.safeUrl(url);
    String key = opKey(id, method, url);
    if (!a.origin.equals(origin(safe)))
      throw new IllegalArgumentException("점검 계정 Origin과 대상이 다릅니다.");
    Operation op = operations.get(key);
    if (op == null) {
      if (operations.size() >= OPERATION_LIMIT) {
        rejected++;
        throw new IllegalArgumentException("계정별 작업 기록 최대 10,000개");
      }
      op = new Operation(id, method, safe);
      operations.put(key, op);
      invalidateOperation(op);
    }
    return op;
  }

  private void reopenTasks(Operation op, String reason) {
    String ep = endpoint(op.method, op.url);
    for (Task t : tasks.values())
      if (t.completed
          && t.accounts.contains(op.account)
          && t.endpoints.contains(ep)
          && t.review.isEmpty()) {
        t.review = reason;
        t.last = now();
        invalidateTask(t);
        event(op.account, t.id, "업무 재검토", reason);
      }
  }

  private void inputs(Operation op, Collection<String> names) {
    for (String name : names) {
      if (name == null || name.length() > 512 || op.inputs.containsKey(name)) continue;
      if (op.inputs.size() >= 200 || inputCount >= INPUT_LIMIT) {
        rejected++;
        continue;
      }
      op.inputs.put(name, false);
      inputCount++;
      invalidateOperation(op);
      if (op.completed) {
        op.review = "완료 후 새 입력 발견 · " + name;
        reopenTasks(op, op.review);
        event(op.account, endpoint(op.method, op.url), "새 입력", name);
      }
    }
  }

  synchronized void observed(
      String id, String method, String url, Collection<String> names, String source) {
    Operation op = operation(id, method, url);
    inputs(op, names);
    op.observed++;
    op.last = now();
    invalidateOperation(op);
    revision++;
  }

  synchronized void discovered(String method, String url, Collection<String> names) {
    String safe = ActivityTracker.safeUrl(url);
    if (safe == null) return;
    for (Operation op : operations.values())
      if (op.method.equals(method) && op.url.equals(safe)) inputs(op, names);
    revision++;
  }

  synchronized void inspected(
      String id,
      String method,
      String url,
      Collection<String> names,
      String result,
      boolean review) {
    String safeResult = text(result, 512);
    Operation op = operation(id, method, url);
    inputs(op, names);
    op.inspections++;
    op.result = safeResult;
    op.last = now();
    if (review || op.completed) {
      String reason = review ? op.result : "완료 후 새 검사 결과 · " + op.result;
      op.review = reason.substring(0, Math.min(reason.length(), 512));
      reopenTasks(op, op.review);
    }
    invalidateOperation(op);
    event(id, endpoint(method, url), "검사 결과", op.result);
  }

  /** Apply only effective conditions for existing account/API pairs; historical runs stay immutable. */
  synchronized void authConfiguration(AuthComparePanel.Context context) {
    for (int slot = 0; slot < context.accountIds().length; slot++) {
      String account = context.accountIds()[slot];
      if (account.isEmpty() || !accounts.containsKey(account) || context.profiles()[slot] == null) continue;
      for (Operation op : operations.values()) if (op.account.equals(account)
          && accounts.get(account).origin.equals(origin(op.url))) {
        String fingerprint = ExecutionLedger.fingerprint(context, slot, op.method, op.url);
        if (executions.configure(account, op.method, op.url, fingerprint)) {
          op.review = "실행 후 권한·조회 조건 변경 · 현재 조건으로 재검토";
          op.last = now(); reopenTasks(op, op.review); invalidateOperation(op);
          event(account, endpoint(op.method, op.url), "실행 조건 revision 변경", op.review);
        }
      }
    }
  }

  synchronized void invalidateAuthOrigin(String targetOrigin, String reason) {
    String normalized = origin(targetOrigin);
    String note = text(reason, 512);
    bindings.removeIf(binding -> binding.origin.equals(normalized));
    for (Operation op : operations.values()) if (origin(op.url).equals(normalized)) {
      var policy = executions.policy(op.account, op.method, op.url);
      if (policy != null) executions.configure(op.account, op.method, op.url,
          Core.hash(("invalidated:" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)));
      if (policy != null || op.completed) {
        op.review = note; op.last = now(); reopenTasks(op, note); invalidateOperation(op);
        event(op.account, endpoint(op.method, op.url), "설정 묶음 조건 재검토", note);
      }
    }
  }

  synchronized void beginAuthExecution(String id, String originalId, HttpRequest request, AuthComparePanel.Context context) {
    if (executions.runs.containsKey(id)) throw new IllegalArgumentException("이미 등록된 실행 ID입니다.");
    if (executions.runs.size() >= ExecutionLedger.RUN_LIMIT) throw new IllegalArgumentException("실행 연결 최대 2,000개 · 프로젝트를 저장하고 새 프로젝트를 사용하세요.");
    String safe = ActivityTracker.safeUrl(request.url());
    if (safe == null) throw new IllegalArgumentException("실행 대상 URL을 확인하세요.");
    List<ExecutionLedger.Binding> links = new ArrayList<>();
    for (int slot = 0; slot < context.accountIds().length; slot++) {
      String account = context.accountIds()[slot];
      if (account.isEmpty() || !accounts.containsKey(account) || context.profiles()[slot] == null) continue;
      if (!accounts.get(account).origin.equals(origin(safe))) continue;
      Operation op = operation(account, request.method(), safe);
      String fingerprint = ExecutionLedger.fingerprint(context, slot, request.method(), safe);
      if (executions.configure(account, request.method(), safe, fingerprint)) {
        op.review = "권한·조회 조건 변경 · 새 실행 결과 검토 필요"; reopenTasks(op, op.review);
      }
      var policy = executions.policy(account, request.method(), safe);
      Set<String> jobs = new LinkedHashSet<>();
      for (Task task : tasks.values()) if (task.accounts.contains(account)
          && task.endpoints.contains(endpoint(request.method(), safe))) jobs.add(task.id);
      links.add(new ExecutionLedger.Binding(account, policy.revision(), policy.fingerprint(), jobs));
      if (op.completed) { op.review = "완료 후 새 실행 시작 · 종료 결과 검토 필요"; reopenTasks(op, op.review); }
      invalidateOperation(op);
    }
    executions.begin(id, originalId, request.method(), safe, links);
    event("", id, "실행 시작", "원본 " + originalId + " · " + request.method() + " " + safe);
  }

  synchronized void finishAuthExecution(String id, ExecutionLedger.State state, String summary) {
    var before = executions.runs.get(id);
    executions.finish(id, state, summary);
    var run = executions.runs.get(id);
    if (before == null || run == before) return;
    for (var binding : run.bindings()) {
      Operation op = operations.get(opKey(binding.account(), run.method(), run.url()));
      if (op == null) continue;
      if (state != ExecutionLedger.State.COMPLETED) {
        op.review = "연결 실행 " + ExecutionLedger.label(state) + " · 완료 근거로 사용할 수 없음";
        reopenTasks(op, op.review);
      }
      invalidateOperation(op);
    }
    event("", id, "실행 종료", ExecutionLedger.label(state));
  }

  synchronized String executionSummary(String id) { return executions.summary(id); }
  synchronized ExecutionLedger.Run execution(String id) { return executions.runs.get(id); }
  synchronized List<ExecutionLedger.Run> executionHistory() { return List.copyOf(executions.runs.values()); }
  synchronized String latestExecutionSummary(String account, String method, String url) {
    var run = executions.latest(account, method, url);
    if (run == null) return "없음 · 수동 점검 기록";
    var binding = run.bindings().stream().filter(b -> b.account().equals(account)).findFirst().orElseThrow();
    return run.id() + " · r" + binding.revision() + " · " + ExecutionLedger.label(run.state())
        + (executions.current(run, account) ? "" : " · 현재 조건과 다름") + " · 증거 ZIP " + run.evidence().size();
  }
  synchronized void executionEvidenceSaved(String executionId, String evidenceHash) {
    executions.evidence(executionId, evidenceHash);
    var run = executions.runs.get(executionId);
    if (run != null) {
      for (var binding : run.bindings()) {
        Operation op = operations.get(opKey(binding.account(), run.method(), run.url()));
        if (op != null) invalidateOperation(op);
      }
      event("", executionId, "실행 증거 연결", evidenceHash);
    }
  }

  synchronized void complete(
      String id, String method, String url, Collection<String> names, boolean value) {
    if (value) {
      String problem = executions.completionProblem(id, method, url);
      if (!problem.isEmpty()) throw new IllegalArgumentException(problem);
    }
    Operation op = operation(id, method, url);
    inputs(op, names);
    if (!value && op.completed) reopenTasks(op, "요구 계정의 URL 완료 해제");
    op.completed = value;
    op.completedAt = value ? now() : "";
    if (value) {
      op.review = "";
      Account a = account(id);
      op.completionContext =
          "역할="
              + a.role
              + " · 조직="
              + a.tenant
              + " · 인증 연결="
              + (bindings.stream().anyMatch(b -> b.account.equals(id)) ? "있음 (신원은 수동 확인)" : "없음");
    }
    op.last = now();
    invalidateOperation(op);
    event(id, endpoint(method, url), value ? "수동 완료" : "완료 해제", "URL 완료와 입력별 완료는 별도 기록");
  }

  synchronized CompletionReview completionReview(
      String id, String method, String url, Collection<String> known) {
    OperationView view = view(id, method, url, known);
    Account a = account(id);
    long pending = view.parameters.stream().filter(p -> !p.state().equals("점검 완료")).count();
    String summary = "계정: " + a.label + " · 역할: " + a.role + " · 조직: " + a.tenant
        + "\n대상: " + method + " " + view.url
        + "\n현재 상태: " + view.state + "\n검사 시도: " + view.inspections
        + " · 연결된 증거 ZIP: " + view.evidence + " · 미완료 입력: " + pending
        + "\n최근 검사: " + (view.result.isBlank() ? "기록 없음" : view.result)
        + "\n실행 연결: " + latestExecutionSummary(id, method, url)
        + "\n재검토 근거: " + (view.review.isBlank() ? "없음" : view.review)
        + "\n\n이 완료 표시는 진단자의 수동 검토 기록입니다. 실제 신원·권한 정책·영향과 증거를 확인하세요."
        + "\n입력별 완료는 별도이며, 증거 개수는 파일 존재나 탐지 성공을 보장하지 않습니다.";
    return new CompletionReview(id, method, view.url, "", summary, operationStamp(view));
  }

  private String operationStamp(OperationView view) {
    Account a = account(view.account);
    Operation op = operations.get(opKey(view.account, view.method, view.url));
    List<String> files = op == null ? List.of() : op.evidence.stream().sorted()
        .map(id -> id + ":" + evidenceStatus.getOrDefault(id, "파일 재연결 필요")).toList();
    List<String> sessions = bindings.stream().filter(b -> b.account.equals(view.account))
        .map(b -> MetadataExport.json(List.of(b.fingerprint, new TreeSet<>(b.names), new TreeSet<>(b.cookies))))
        .sorted().toList();
    // Ordinary background observations do not invalidate a review; changed inputs and results do.
    return stamp(List.of(List.of(a.id, a.label, a.origin, a.role, a.tenant, a.anonymous),
        view.account, view.method, view.url, view.state, view.inspections,
        view.result, view.review, view.completedAt,
        view.parameters.stream().map(p -> List.of(p.key(), p.state())).toList(), files, sessions,
        op == null ? Map.of() : new TreeMap<>(op.inputHashes), latestExecutionSummary(view.account, view.method, view.url)));
  }

  private static String stamp(Object values) {
    return Core.hash(MetadataExport.json(values).getBytes(StandardCharsets.UTF_8));
  }

  synchronized void completeReviewed(CompletionReview review, Collection<String> known) {
    if (!review.task.isEmpty()) throw new IllegalArgumentException("URL 완료 검토를 다시 여세요.");
    CompletionReview current = completionReview(review.account, review.method, review.url, known);
    if (!current.stamp.equals(review.stamp))
      throw new IllegalArgumentException("검토 중 계정·검사·입력·증거 조건이 바뀌었습니다. 새 내용을 확인하고 다시 완료하세요.");
    complete(review.account, review.method, review.url, known, true);
  }

  synchronized CompletionReview taskCompletionReview(String id) {
    Task task = task(id);
    TaskView view = taskView(task);
    List<String> operations = operationPairs(task).stream().map(this::operationStamp).toList();
    String summary = "업무: " + view.title + "\n현재 상태: " + view.state
        + "\n계정 × API: " + view.required + " · 수동 완료: " + view.completed
        + " · 검사 시도 있음: " + view.inspected
        + "\n연결된 증거 ZIP: " + view.evidence + " · 파일 확인 조합: " + view.evidenced + " / " + view.required
        + "\n완료 조건: " + (view.requiresEvidence ? "업무 증거 필요" : "업무 증거 선택")
        + " / " + (view.allEvidence ? "모든 조합의 파일 확인 필요" : "전체 조합 파일 확인 선택")
        + " / " + (view.strictInputs ? "모든 입력 완료 필요" : "입력 완료 별도")
        + "\n재검토 근거: " + (task.review.isBlank() ? "없음" : task.review)
        + "\n\n요구 계정·대상·입력·증거와 실제 영향을 검토한 뒤 완료하세요."
        + "\n자동 분류나 검사 시도만으로 업무가 완료되지 않습니다.";
    String fingerprint = stamp(List.of(task.id, task.title, task.note,
        new TreeSet<>(task.accounts), new TreeSet<>(task.endpoints), task.completed,
        task.requiresEvidence, task.strictInputs, task.allEvidence,
        new TreeSet<>(task.criticalInputs), task.review, operations));
    return new CompletionReview("", "", "", id, summary, fingerprint);
  }

  synchronized void completeTaskReviewed(CompletionReview review) {
    if (review.task.isEmpty()) throw new IllegalArgumentException("업무 완료 검토를 다시 여세요.");
    if (!taskCompletionReview(review.task).stamp.equals(review.stamp))
      throw new IllegalArgumentException("검토 중 업무 요구 조건이나 결과가 바뀌었습니다. 새 내용을 확인하고 다시 완료하세요.");
    completeTask(review.task, true);
  }

  synchronized void completeInput(
      String id, String method, String url, String name, boolean value) {
    Operation op = operation(id, method, url);
    if (!op.inputs.containsKey(name)) throw new IllegalArgumentException("입력 이름을 선택하세요.");
    op.inputs.put(name, value);
    if (!value)
      for (Task t : tasks.values())
        if (t.completed
            && t.strictInputs
            && t.accounts.contains(id)
            && t.endpoints.contains(endpoint(method, url))) {
          t.review = "필수 입력 완료 해제";
          t.last = now();
          invalidateTask(t);
        }
    invalidateOperation(op);
    event(id, endpoint(method, url), value ? "입력 수동 완료" : "입력 완료 해제", name);
  }

  synchronized OperationView view(String id, String method, String url, Collection<String> known) {
    account(id);
    String key = opKey(id, method, url);
    List<String> names = List.copyOf(known);
    CachedOperation cached = operationViews.get(key);
    if (cached != null && cached.known().equals(names)) return cached.view();
    Operation op = operations.get(key);
    LinkedHashMap<String, Boolean> parameters = new LinkedHashMap<>();
    for (String name : names) parameters.put(name, false);
    if (op != null) parameters.putAll(op.inputs);
    OperationView view =
        new OperationView(
            id,
            method,
            ActivityTracker.safeUrl(url),
            op == null ? "미점검" : op.state(),
            op == null ? 0 : op.observed,
            op == null ? 0 : op.inspections,
            op == null ? 0 : op.evidence.size(),
            op == null ? "" : op.result,
            op == null ? "" : op.review,
            op == null ? "" : op.completedAt,
            op == null ? "" : op.last,
            parameters.entrySet().stream()
                .map(
                    parameter ->
                        new ActivityTracker.ParameterView(
                            parameter.getKey(), parameter.getValue() ? "점검 완료" : "미점검"))
                .toList(),
            op == null ? "" : op.completionContext);
    operationViews.put(key, new CachedOperation(names, view));
    operationViewBuilds++;
    return view;
  }

  synchronized String createTask(
      String title, String note, Set<String> required, boolean evidence) {
    if (tasks.size() >= TASK_LIMIT) throw new IllegalArgumentException("업무 최대 1,000개");
    String id = UUID.randomUUID().toString();
    Task task = new Task(id, "", "");
    updateTask(task, title, note, required, evidence);
    tasks.put(id, task);
    invalidateTask(task);
    event("", id, "업무 등록", task.title);
    return id;
  }

  record MapperDraft(String sourceId, String taskId, String title, List<String> endpoints,
                     List<String> added, List<String> removed, String stamp) {
    MapperDraft { endpoints = List.copyOf(endpoints); added = List.copyOf(added); removed = List.copyOf(removed); }
  }

  synchronized MapperDraft mapperDraft(String sourceId, String title, List<String> proposed) {
    if (sourceId == null || !sourceId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("기능 매핑 원본 ID를 확인하세요.");
    title = text(title, 120);
    if (title.isBlank() || proposed.isEmpty() || proposed.size() > 32)
      throw new IllegalArgumentException("기능 이름과 1~32개 API를 선택하세요. 큰 기능은 업무를 나누세요.");
    Set<String> endpoints = new LinkedHashSet<>();
    for (String value : proposed) {
      int split = value.indexOf(' ');
      if (split < 1) throw new IllegalArgumentException("기능 API는 METHOD URL 형식이어야 합니다.");
      endpoints.add(endpoint(value.substring(0, split), value.substring(split + 1)));
    }
    String id = mapperTasks.getOrDefault(sourceId, "");
    Task existing = id.isEmpty() ? null : task(id);
    Set<String> old = existing == null ? Set.of() : existing.endpoints;
    List<String> added = endpoints.stream().filter(e -> !old.contains(e)).toList();
    List<String> removed = old.stream().filter(e -> !endpoints.contains(e)).toList();
    String stamp = stamp(List.of(sourceId, id, new TreeSet<>(endpoints), existing == null ? List.of()
        : List.of(existing.title, existing.note, new TreeSet<>(existing.accounts), new TreeSet<>(existing.endpoints),
            existing.completed, existing.requiresEvidence, existing.strictInputs, existing.allEvidence,
            new TreeSet<>(existing.criticalInputs), existing.review)));
    return new MapperDraft(sourceId, id, existing == null ? title : existing.title, List.copyOf(endpoints), added, removed, stamp);
  }

  synchronized String applyMapperDraft(MapperDraft draft, Set<String> required, boolean removeMissing) {
    MapperDraft fresh = mapperDraft(draft.sourceId, draft.title, draft.endpoints);
    if (!fresh.stamp.equals(draft.stamp)) throw new IllegalArgumentException("검토 중 연결 업무가 변경되었습니다. 기능 매핑 연결을 다시 여세요.");
    if (required.isEmpty() || required.size() > ACCOUNT_LIMIT) throw new IllegalArgumentException("요구 계정을 선택하세요.");
    for (String account : required) account(account);
    Task existing = draft.taskId.isEmpty() ? null : task(draft.taskId);
    Set<String> targets = new LinkedHashSet<>(draft.endpoints);
    if (!removeMissing && existing != null) targets.addAll(existing.endpoints);
    if (targets.size() > 32) throw new IllegalArgumentException("보존할 기존 API를 포함해 업무당 최대 32개입니다.");
    for (String target : targets) {
      String origin = origin(target.substring(target.indexOf(' ') + 1));
      if (required.stream().noneMatch(id -> accounts.get(id).origin.equals(origin)))
        throw new IllegalArgumentException("모든 API Origin에 맞는 요구 계정을 선택하세요.");
    }
    if (existing != null && existing.accounts.equals(required) && existing.endpoints.equals(targets)) return existing.id;
    String id = existing == null ? createTask(draft.title, "기능 자동 매핑에서 검토 후 연결한 업무", required, true) : existing.id;
    Task task = task(id);
    task.accounts.clear(); task.accounts.addAll(required);
    task.endpoints.clear(); task.endpoints.addAll(targets);
    task.completed = false; task.last = now();
    if (existing != null) task.review = "기능 매핑의 요구 계정/API 변경 · 범위 재검토 필요";
    mapperTasks.put(draft.sourceId, id);
    invalidateTask(task);
    event("", id, "기능 매핑 업무 연결", "추가 " + draft.added.size() + " · 제외 " + (removeMissing ? draft.removed.size() : 0));
    return id;
  }

  private void updateTask(
      Task task, String title, String note, Set<String> required, boolean evidence) {
    title = text(title, 120);
    note = text(note, 1024);
    if (title.isEmpty() || required.isEmpty() || required.size() > ACCOUNT_LIMIT)
      throw new IllegalArgumentException("업무 이름과 요구 계정을 지정하세요.");
    for (String id : required) account(id);
    for (String ep : task.endpoints)
      if (required.stream()
          .noneMatch(
              id -> accounts.get(id).origin.equals(origin(ep.substring(ep.indexOf(' ') + 1)))))
        throw new IllegalArgumentException("기존 대상의 Origin에 맞는 요구 계정이 필요합니다.");
    if (task.completed) {
      task.completed = false;
      task.review = "완료 후 업무 요구 조건 변경";
    }
    task.title = title;
    task.note = note;
    task.accounts.clear();
    task.accounts.addAll(required);
    task.requiresEvidence = evidence;
    task.last = now();
    invalidateTask(task);
  }

  synchronized void editTask(
      String id, String title, String note, Set<String> required, boolean evidence) {
    Task task = task(id);
    updateTask(task, title, note, required, evidence);
    event("", id, "업무 설정 변경", task.title);
  }

  private Task task(String id) {
    Task t = tasks.get(id);
    if (t == null) throw new IllegalArgumentException("업무를 선택하세요.");
    return t;
  }

  synchronized void addTarget(String id, String method, String url) {
    Task task = task(id);
    String ep = endpoint(method, url);
    if (task.accounts.stream().noneMatch(a -> accounts.get(a).origin.equals(origin(url))))
      throw new IllegalArgumentException("대상 Origin에 맞는 요구 계정이 없습니다.");
    if (task.endpoints.contains(ep)) return;
    if (task.endpoints.size() >= 32) throw new IllegalArgumentException("업무당 대상 최대 32개");
    task.endpoints.add(ep);
    if (task.completed) {
      task.completed = false;
      task.review = "완료 후 새 대상 추가";
    }
    task.last = now();
    invalidateTask(task);
    event("", id, "업무 대상 추가", ep);
  }

  synchronized void removeTarget(String id, String method, String url) {
    Task task = task(id);
    if (task.endpoints.remove(endpoint(method, url))) {
      task.completed = false;
      task.review = "업무 대상 제외 · 요구 범위 재확인";
      task.last = now();
      invalidateTask(task);
      event("", id, "업무 대상 제외", endpoint(method, url));
    }
  }

  synchronized List<OperationView> taskOperations(String id) {
    Task task = task(id);
    List<OperationView> rows = operationPairs(task);
    detailSnapshots.put(id, rows);
    return rows;
  }

  // Summaries retain only counts/state. Detailed pairs are cached for recently
  // opened tasks, not for every potential account × API combination in a project.
  private List<OperationView> operationPairs(Task task) {
    List<OperationView> cached = detailSnapshots.get(task.id);
    if (cached != null) return cached;
    List<OperationView> rows = new ArrayList<>();
    for (String endpoint : task.endpoints) {
      int separator = endpoint.indexOf(' ');
      String method = endpoint.substring(0, separator), url = endpoint.substring(separator + 1);
      String origin = origin(url);
      for (String account : task.accounts) {
        if (accounts.get(account).origin.equals(origin))
          rows.add(view(account, method, url, List.of()));
      }
    }
    return List.copyOf(rows);
  }

  private TaskView taskView(Task t) {
    if (t.cachedView != null) return t.cachedView;
    taskViewBuilds++;
    List<OperationView> pairs = operationPairs(t);
    int inspected = (int) pairs.stream().filter(v -> v.inspections > 0).count(),
        completed = (int) pairs.stream().filter(v -> v.state.equals("점검 완료")).count();
    Set<String> evidence = new HashSet<>();
    for (OperationView pair : pairs) {
      Operation op = operations.get(opKey(pair.account, pair.method, pair.url));
      if (op != null) evidence.addAll(op.evidence);
    }
    int evidenced =
        (int)
            pairs.stream()
                .filter(
                    v -> {
                      Operation op = operations.get(opKey(v.account, v.method, v.url));
                      return op != null
                          && op.evidence.stream()
                              .anyMatch(e -> "확인됨".equals(evidenceStatus.get(e)));
                    })
                .count();
    boolean inputsReady =
        !t.strictInputs
            || pairs.stream()
                .allMatch(v -> v.parameters.stream().allMatch(p -> p.state().equals("점검 완료")));
    boolean evidenceReady =
        (!t.requiresEvidence || !evidence.isEmpty())
            && (!t.allEvidence || evidenced == pairs.size());
    boolean review =
        !t.review.isEmpty()
            || t.completed && (completed != pairs.size() || !evidenceReady || !inputsReady)
            || pairs.stream().anyMatch(v -> v.state.equals("재검토 필요"));
    String state =
        review
            ? "재검토 필요"
            : pairs.isEmpty()
                ? "대상 미등록"
                : t.completed
                    ? "업무 완료"
                    : completed == pairs.size() && evidenceReady && inputsReady
                        ? "완료 준비"
                        : inspected > 0 ? "점검 중" : "미점검";
    if (!evidenceReady && completed == pairs.size() && !pairs.isEmpty() && !review)
      state = "증거 저장 필요";
    else if (!inputsReady && completed == pairs.size() && !pairs.isEmpty() && !review)
      state = "입력 점검 필요";
    return t.cachedView =
        new TaskView(
            t.id,
            t.title,
            t.note,
            t.endpoints.size(),
            t.accounts.size(),
            pairs.size(),
            inspected,
            completed,
            evidence.size(),
            state,
            t.last,
            t.requiresEvidence,
            t.strictInputs,
            t.allEvidence,
            Set.copyOf(t.criticalInputs),
            evidenced);
  }

  synchronized List<TaskView> tasks() {
    if (taskSnapshot == null) taskSnapshot = tasks.values().stream().map(this::taskView).toList();
    return taskSnapshot;
  }

  synchronized Set<String> taskAccounts(String id) {
    return Set.copyOf(task(id).accounts);
  }

  synchronized void completeTask(String id, boolean value) {
    Task t = task(id);
    if (value) {
      TaskView v = taskView(t);
      if (v.required == 0
          || v.completed != v.required
          || t.requiresEvidence && v.evidence == 0
          || t.allEvidence && v.evidenced != v.required
          || t.strictInputs
              && taskOperations(id).stream()
                  .anyMatch(o -> o.parameters.stream().anyMatch(p -> !p.state().equals("점검 완료"))))
        throw new IllegalArgumentException("모든 요구 계정/대상을 수동 완료하고 필요한 증거를 저장하세요.");
      t.review = "";
    }
    t.completed = value;
    t.last = now();
    invalidateTask(t);
    event("", id, value ? "업무 수동 완료" : "업무 완료 해제", t.title);
  }

  synchronized void evidenceSaved(String id, String filename, List<BountyEvidence.Item> items) {
    if (artifacts.containsKey(id)) return;
    if (artifacts.size() >= 1000) throw new IllegalArgumentException("증거 묶음 최대 1,000개");
    String file = text(filename, 256);
    if (!id.matches("[A-Za-z0-9_-]{1,128}") || items.size() > 32)
      throw new IllegalArgumentException("증거 ID/개수 오류");
    Set<String> planned = new LinkedHashSet<>();
    for (var item : items)
      if (!item.account().isEmpty()
          && accounts.containsKey(item.account())
          && accounts.get(item.account()).origin.equals(origin(item.url())))
        planned.add(opKey(item.account(), item.method(), item.url()));
    long additions = planned.stream().filter(key -> !operations.containsKey(key)).count();
    if (operations.size() + additions > OPERATION_LIMIT)
      throw new IllegalArgumentException("계정별 작업 한도 초과 · 증거는 저장되었지만 현황 연결 불가");
    Set<String> refs = new LinkedHashSet<>();
    for (var item : items) {
      if (item.account().isEmpty() || !accounts.containsKey(item.account())) continue;
      Account a = accounts.get(item.account());
      if (!a.origin.equals(origin(item.url()))) continue;
      Operation op = operation(a.id, item.method(), item.url());
      op.evidence.add(id);
      op.last = now();
      invalidateOperation(op);
      refs.add(opKey(a.id, op.method, op.url));
    }
    if (refs.isEmpty()) return;
    String time = now();
    artifacts.put(id, new EvidenceLink(id, file, time, Set.copyOf(refs)));
    event("", id, "증거 ZIP 저장", file);
  }

  static Set<String> validateCritical(Collection<String> critical) {
    if (critical.size() > 32) throw new IllegalArgumentException("중요 입력 최대 32개");
    Set<String> names = new LinkedHashSet<>();
    for (String name : critical) {
      String n = text(name, 512);
      if (!n.matches("(URL|BODY|PATH|HEADER|COOKIE):.+"))
        throw new IllegalArgumentException("중요 입력은 URL:id, BODY:ownerId 같은 위치:이름 형식입니다.");
      names.add(n);
    }
    return names;
  }

  synchronized void taskRequirements(
      String id, boolean strict, boolean allEvidence, Collection<String> critical) {
    Set<String> names = validateCritical(critical);
    Task t = task(id);
    if (t.strictInputs == strict && t.allEvidence == allEvidence && t.criticalInputs.equals(names))
      return;
    t.strictInputs = strict;
    t.allEvidence = allEvidence;
    t.criticalInputs.clear();
    t.criticalInputs.addAll(names);
    if (t.completed) t.review = "완료 후 업무 요구 조건 변경";
    t.last = now();
    invalidateTask(t);
    event("", id, "엄격 완료 조건 변경", "입력/조합별 증거/중요 입력 조건 변경");
  }

  synchronized void observedValues(
      String id, String method, String url, Map<String, List<String>> values) {
    Operation op = operation(id, method, url);
    Set<String> important = new LinkedHashSet<>();
    for (Task t : tasks.values())
      if (t.accounts.contains(id) && t.endpoints.contains(endpoint(method, url)))
        important.addAll(t.criticalInputs);
    for (String name : important) {
      String value =
          fingerprint(
              Map.of("input", MetadataExport.json(values.getOrDefault(name, List.of()))),
              Set.of("input"),
              Set.of());
      if (op.inputHashes.size() >= 200 && !op.inputHashes.containsKey(name)) {
        op.review = "중요 입력 추적 한도 초과 · 범위 축소 필요";
        reopenTasks(op, op.review);
        invalidateOperation(op);
        continue;
      }
      String old = op.inputHashes.put(name, value);
      if (op.completed && (old == null || !old.equals(value))) {
        op.review = old == null ? "중요 입력 기준 재확인 · " + name : "완료 후 중요 입력 변경 · " + name;
        if (op.inputs.containsKey(name)) op.inputs.put(name, false);
        reopenTasks(op, op.review);
        invalidateOperation(op);
        event(id, endpoint(method, url), "중요 입력 재검토", name);
      }
    }
  }

  static String fileHash(Path path) throws IOException {
    if (!Files.isRegularFile(path) || Files.size(path) > 64L * 1024 * 1024)
      throw new IOException("증거 파일 없음 또는 64MiB 한도 초과");
    try (InputStream in = Files.newInputStream(path)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] block = new byte[8192];
      long total = 0;
      for (int n; (n = in.read(block)) != -1; ) {
        if ((total += n) > 64L * 1024 * 1024) throw new IOException("증거 64MiB 한도");
        digest.update(block, 0, n);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  void attachEvidenceFile(Path path) throws IOException {
    String hash = fileHash(path);
    synchronized (this) {
      if (!artifacts.containsKey(hash))
        throw new IllegalArgumentException("저장 기록과 SHA-256이 일치하는 증거 ZIP을 선택하세요.");
      Path absolute = path.toAbsolutePath().normalize();
      for (var old : evidencePaths.entrySet())
        if (!old.getKey().equals(hash) && old.getValue().equals(absolute)) {
          evidenceStatus.put(old.getKey(), "파일 교체됨");
          for (Operation op : operations.values())
            if (op.evidence.contains(old.getKey())) {
              reopenTasks(op, "증거 파일 교체됨");
              invalidateOperation(op);
            }
        }
      evidencePaths.put(hash, absolute);
      evidenceStatus.put(hash, "확인됨");
      for (Operation op : operations.values())
        if (op.evidence.contains(hash)) invalidateOperation(op);
      event("", hash, "증거 파일 확인", path.getFileName().toString());
    }
  }

  void verifyEvidenceFiles() {
    Map<String, Path> snapshot;
    synchronized (this) {
      snapshot = Map.copyOf(evidencePaths);
    }
    for (var entry : snapshot.entrySet()) {
      String status;
      try {
        status = fileHash(entry.getValue()).equals(entry.getKey()) ? "확인됨" : "해시 불일치";
      } catch (IOException e) {
        status = "파일 없음/읽기 실패";
      }
      synchronized (this) {
        if (!Objects.equals(evidencePaths.get(entry.getKey()), entry.getValue())) continue;
        evidenceStatus.put(entry.getKey(), status);
        for (Operation op : operations.values())
          if (op.evidence.contains(entry.getKey())) {
            if (!status.equals("확인됨")) reopenTasks(op, "증거 파일 재검증 실패");
            invalidateOperation(op);
          }
        event("", entry.getKey(), "증거 재검증", status);
      }
    }
  }

  synchronized List<String> evidenceFiles() {
    return artifacts.values().stream()
        .map(
            e ->
                e.filename
                    + " · "
                    + evidenceStatus.getOrDefault(e.id, "파일 재연결 필요")
                    + " · SHA-256 "
                    + e.id)
        .toList();
  }

  synchronized List<Event> history() {
    return List.copyOf(events);
  }

  private void event(String account, String target, String action, String detail) {
    if (events.size() >= EVENT_LIMIT) events.removeFirst();
    events.addLast(
        new Event(
            now(), account, target, action, detail.substring(0, Math.min(detail.length(), 512))));
    revision++;
  }

  synchronized void clear() {
    accounts.clear();
    operations.clear();
    tasks.clear();
    artifacts.clear();
    executions.clear();
    mapperTasks.clear();
    events.clear();
    bindings.clear();
    evidencePaths.clear();
    evidenceStatus.clear();
    new SecureRandom().nextBytes(salt);
    inputCount = 0;
    rejected = 0;
    invalidateAll();
    revision++;
  }

  synchronized Map<String, Object> save() {
    List<Object> ops = new ArrayList<>();
    for (Operation op : operations.values()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("account", op.account);
      row.put("method", op.method);
      row.put("url", op.url);
      row.put("observed", op.observed);
      row.put("inspections", op.inspections);
      row.put("completed", op.completed);
      row.put("completedAt", op.completedAt);
      row.put("completionContext", op.completionContext);
      row.put("last", op.last);
      row.put("review", op.review);
      row.put("result", op.result);
      row.put("inputs", op.inputs);
      row.put("evidence", op.evidence);
      ops.add(row);
    }
    List<Object> jobs = new ArrayList<>();
    for (Task t : tasks.values())
      jobs.add(
          Map.ofEntries(
              Map.entry("id", t.id),
              Map.entry("title", t.title),
              Map.entry("note", t.note),
              Map.entry("accounts", t.accounts),
              Map.entry("endpoints", t.endpoints),
              Map.entry("completed", t.completed),
              Map.entry("requiresEvidence", t.requiresEvidence),
              Map.entry("review", t.review),
              Map.entry("last", t.last),
              Map.entry("strictInputs", t.strictInputs),
              Map.entry("allEvidence", t.allEvidence),
              Map.entry("criticalInputs", t.criticalInputs)));
    return Map.of(
        "executions", executions.save(),
        "mapperTasks", new LinkedHashMap<>(mapperTasks),
        "accounts",
        accounts.values().stream()
            .map(
                a ->
                    Map.of(
                        "id",
                        a.id,
                        "label",
                        a.label,
                        "origin",
                        a.origin,
                        "role",
                        a.role,
                        "tenant",
                        a.tenant,
                        "anonymous",
                        a.anonymous))
            .toList(),
        "operations",
        ops,
        "tasks",
        jobs,
        "artifacts",
        artifacts.values().stream()
            .map(
                a ->
                    Map.of(
                        "id",
                        a.id,
                        "filename",
                        a.filename,
                        "time",
                        a.time,
                        "operations",
                        a.operations))
            .toList(),
        "events",
        events.stream()
            .map(
                e ->
                    Map.of(
                        "time", e.time, "account", e.account, "target", e.target, "action",
                        e.action, "detail", e.detail))
            .toList());
  }

  private static String field(Map<?, ?> m, String key, int max) {
    return text(Objects.toString(m.get(key), ""), max);
  }

  private static long count(Object value) {
    return value instanceof Number n ? Math.max(0, Math.min(1000000000L, n.longValue())) : 0;
  }

  private static List<?> rows(Map<?, ?> m, String key, int limit) {
    Object value = m.get(key);
    if (!(value instanceof List<?> list) || list.size() > limit)
      throw new IllegalArgumentException("점검 기록 목록/한도 오류 · " + key);
    return list;
  }

  private static String id(Map<?, ?> m, String key) {
    String id = field(m, key, 64);
    if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("점검 기록 ID 오류");
    return id;
  }

  synchronized void load(Object value) {
    if (!(value instanceof Map<?, ?> doc)) throw new IllegalArgumentException("계정/업무 기록 형식 오류");
    AssessmentTracker imported = new AssessmentTracker();
    for (Object obj : rows(doc, "accounts", ACCOUNT_LIMIT)) {
      if (!(obj instanceof Map<?, ?> row)) throw new IllegalArgumentException("계정 기록 오류");
      String id = id(row, "id"),
          label = field(row, "label", 64),
          base = origin(field(row, "origin", 8192));
      if (label.isEmpty() || imported.accounts.containsKey(id))
        throw new IllegalArgumentException("계정 ID/이름 오류");
      imported.accounts.put(
          id,
          new Account(
              id,
              label,
              base,
              field(row, "role", 64),
              field(row, "tenant", 64),
              Boolean.TRUE.equals(row.get("anonymous"))));
    }
    for (Object obj : rows(doc, "operations", OPERATION_LIMIT)) {
      if (!(obj instanceof Map<?, ?> row)) throw new IllegalArgumentException("계정별 대상 기록 오류");
      String a = id(row, "account"),
          method = field(row, "method", 24),
          url = field(row, "url", 8192);
      String key = opKey(a, method, url);
      if (imported.operations.containsKey(key)) throw new IllegalArgumentException("중복 계정별 대상");
      Operation op = imported.operation(a, method, url);
      op.observed = count(row.get("observed"));
      op.inspections = count(row.get("inspections"));
      op.completed = Boolean.TRUE.equals(row.get("completed"));
      op.completedAt = field(row, "completedAt", 64);
      op.completionContext = field(row, "completionContext", 512);
      op.last = field(row, "last", 64);
      op.review = field(row, "review", 512);
      op.result = field(row, "result", 512);
      if (!(row.get("inputs") instanceof Map<?, ?> inputs) || inputs.size() > 200)
        throw new IllegalArgumentException("입력 목록 오류");
      for (var entry : inputs.entrySet()) {
        String name = text(entry.getKey().toString(), 512);
        if (imported.inputCount >= INPUT_LIMIT) throw new IllegalArgumentException("점검 입력 한도 초과");
        op.inputs.put(name, Boolean.TRUE.equals(entry.getValue()));
        imported.inputCount++;
      }
      for (Object item : rows(row, "evidence", 1000)) op.evidence.add(text(item.toString(), 128));
    }
    for (Object obj : rows(doc, "tasks", TASK_LIMIT)) {
      if (!(obj instanceof Map<?, ?> row)) throw new IllegalArgumentException("업무 기록 오류");
      String id = id(row, "id");
      if (imported.tasks.containsKey(id)) throw new IllegalArgumentException("중복 업무 ID");
      Set<String> required = new LinkedHashSet<>();
      for (Object a : rows(row, "accounts", ACCOUNT_LIMIT)) required.add(a.toString());
      Task t = new Task(id, "", "");
      imported.updateTask(
          t,
          field(row, "title", 120),
          field(row, "note", 1024),
          required,
          Boolean.TRUE.equals(row.get("requiresEvidence")));
      imported.tasks.put(id, t);
      for (Object ep : rows(row, "endpoints", 32)) {
        String target = ep.toString();
        int n = target.indexOf(' ');
        if (n < 1) throw new IllegalArgumentException("업무 대상 오류");
        imported.addTarget(id, target.substring(0, n), target.substring(n + 1));
      }
      t.strictInputs = Boolean.TRUE.equals(row.get("strictInputs"));
      t.allEvidence = Boolean.TRUE.equals(row.get("allEvidence"));
      if (row.containsKey("criticalInputs"))
        for (Object input : rows(row, "criticalInputs", 32))
          t.criticalInputs.add(text(input.toString(), 512));
      t.completed = Boolean.TRUE.equals(row.get("completed"));
      t.review = field(row, "review", 512);
      t.last = field(row, "last", 64);
    }
    for (Object obj : rows(doc, "artifacts", 1000)) {
      if (!(obj instanceof Map<?, ?> row)) throw new IllegalArgumentException("증거 기록 오류");
      String id = field(row, "id", 128);
      if (!id.matches("[A-Za-z0-9_-]{1,128}") || imported.artifacts.containsKey(id))
        throw new IllegalArgumentException("증거 ID 오류");
      Set<String> refs = new LinkedHashSet<>();
      for (Object ref : rows(row, "operations", 32)) {
        if (!imported.operations.containsKey(ref.toString()))
          throw new IllegalArgumentException("증거 대상 참조 오류");
        refs.add(ref.toString());
      }
      imported.artifacts.put(
          id,
          new EvidenceLink(
              id, field(row, "filename", 256), field(row, "time", 64), Set.copyOf(refs)));
    }
    for (Operation op : imported.operations.values())
      for (String e : op.evidence)
        if (!imported.artifacts.containsKey(e)
            || !imported.artifacts.get(e).operations.contains(opKey(op.account, op.method, op.url)))
          throw new IllegalArgumentException("계정별 증거 참조 오류");
    imported.events.clear();
    for (Object obj : rows(doc, "events", EVENT_LIMIT)) {
      if (!(obj instanceof Map<?, ?> row)) throw new IllegalArgumentException("이력 기록 오류");
      String a = field(row, "account", 64);
      if (!a.isEmpty() && !imported.accounts.containsKey(a))
        throw new IllegalArgumentException("이력 계정 참조 오류");
      imported.events.add(
          new Event(
              field(row, "time", 64),
              a,
              field(row, "target", 8300),
              field(row, "action", 64),
              field(row, "detail", 512)));
    }
    if (doc.get("executions") instanceof Map<?, ?> executionDoc)
      imported.executions.load(executionDoc, imported.accounts.keySet(), imported.tasks.keySet());
    else if (doc.containsKey("executions")) throw new IllegalArgumentException("실행 연결 기록 형식 오류");
    if (doc.get("mapperTasks") instanceof Map<?, ?> links) {
      if (links.size() > TASK_LIMIT) throw new IllegalArgumentException("기능 업무 연결 한도 초과");
      for (var link : links.entrySet()) {
        String source = Objects.toString(link.getKey(), ""), task = Objects.toString(link.getValue(), "");
        if (!source.matches("[A-Za-z0-9_-]{1,128}") || !imported.tasks.containsKey(task))
          throw new IllegalArgumentException("기능 업무 연결 참조 오류");
        imported.mapperTasks.put(source, task);
      }
    } else if (doc.containsKey("mapperTasks")) throw new IllegalArgumentException("기능 업무 연결 형식 오류");
    replace(imported);
  }

  synchronized void replace(AssessmentTracker imported) {
    accounts.clear();
    accounts.putAll(imported.accounts);
    operations.clear();
    operations.putAll(imported.operations);
    tasks.clear();
    tasks.putAll(imported.tasks);
    artifacts.clear();
    artifacts.putAll(imported.artifacts);
    executions.replace(imported.executions);
    mapperTasks.clear(); mapperTasks.putAll(imported.mapperTasks);
    evidencePaths.clear();
    evidenceStatus.clear();
    events.clear();
    events.addAll(imported.events);
    bindings.clear();
    new SecureRandom().nextBytes(salt);
    for (Operation operation : operations.values())
      if (executions.latest(operation.account, operation.method, operation.url) != null) {
        operation.review = "가져온 실행 조건 · 계정 재연결 및 현재 정책 확인 필요";
        reopenTasks(operation, operation.review);
      }
    inputCount = imported.inputCount;
    rejected = imported.rejected;
    invalidateAll();
    revision++;
  }

  synchronized void appendWorkbook(
      LinkedHashMap<String, List<List<String>>> workbook, List<ActivityTracker.View> endpoints) {
    List<List<String>> profileRows = new ArrayList<>(),
        opRows = new ArrayList<>(),
        taskRows = new ArrayList<>(),
        eventRows = new ArrayList<>();
    profileRows.add(List.of("계정 ID", "이름", "Origin", "역할", "조직", "비로그인"));
    for (Account a : accounts.values())
      profileRows.add(
          List.of(a.id, a.label, a.origin, a.role, a.tenant, String.valueOf(a.anonymous)));
    opRows.add(
        List.of(
            "계정", "메서드", "URL", "상태", "관측", "검사", "저장 증거", "최근 결과", "재검토", "완료 시각", "완료 당시 조건"));
    for (Operation op : operations.values()) {
      OperationView v = view(op.account, op.method, op.url, List.of());
      opRows.add(
          List.of(
              accounts.get(v.account).label,
              v.method,
              v.url,
              v.state,
              "" + v.observed,
              "" + v.inspections,
              "" + v.evidence,
              v.result,
              v.review,
              v.completedAt,
              v.completionContext));
    }
    taskRows.add(
        List.of(
            "업무",
            "대상 수",
            "요구 계정 수",
            "요구 조합",
            "검사 조합",
            "완료 조합",
            "저장 증거",
            "상태",
            "증거 조합 / 요구",
            "입력 완료 필수",
            "조합별 증거 필수"));
    for (TaskView t : tasks())
      taskRows.add(
          List.of(
              t.title,
              "" + t.endpoints,
              "" + t.accounts,
              "" + t.required,
              "" + t.inspected,
              "" + t.completed,
              "" + t.evidence,
              t.state,
              t.evidenced + " / " + t.required,
              "" + t.strictInputs,
              "" + t.allEvidence));
    eventRows.add(List.of("시각", "계정", "대상/업무 ID", "작업", "설명"));
    for (Event e : events)
      eventRows.add(
          List.of(
              e.time,
              e.account.isEmpty() ? "" : accounts.get(e.account).label,
              e.target,
              e.action,
              e.detail));
    workbook.put("점검 계정", profileRows);
    workbook.put("계정별 기록", opRows);
    workbook.put("업무별 현황", taskRows);
    workbook.put("점검 이력", eventRows);
  }
}
