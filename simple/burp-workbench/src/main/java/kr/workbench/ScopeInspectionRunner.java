package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.RedirectionMode;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.net.URI;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** One explicitly started, bounded inspection. Construction and result navigation send no HTTP. */
final class ScopeInspectionRunner implements AutoCloseable {
  private static final int MAX_CANDIDATES = 500, MAX_TASKS = 2000, MAX_FINDINGS = 5000;
  private static final int MAX_ENVIRONMENT_GUESSES = 12;
  private static final Set<String> IGNORED_FIELDS = Set.of("timestamp", "nonce", "csrf", "traceId", "requestId");
  private static final Set<String> FALLBACK = Set.of("SOFT404", "SIMILAR", "LOGIN", "CHALLENGE", "LIMIT");
  private static final Set<String> TEXT_TYPES = Set.of("text/", "json", "javascript", "xml", "yaml", "graphql", "x-www-form-urlencoded");
  private final MontoyaApi api;
  private final ScopeInspection.Options options;
  private final Predicate<String> scope;
  private final Consumer<ScopeInspection.Snapshot> changed;
  private final String id = UUID.randomUUID().toString(), origin, runRoot;
  private final DiskTrafficStore originals = new DiskTrafficStore();
  private final Object lock = new Object();
  private final AtomicLong generation = new AtomicLong();
  private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
    Thread thread = new Thread(task, "workbench-scope-inspection"); thread.setDaemon(true); return thread;
  });
  private final PriorityQueue<Task> queue = new PriorityQueue<>(Comparator.comparingInt(Task::priority)
      .thenComparingLong(Task::order));
  private final Set<String> queued = new LinkedHashSet<>(), candidateKeys = new LinkedHashSet<>();
  private final LinkedHashMap<String, ScopeInspection.Finding> findings = new LinkedHashMap<>();
  private final List<ScopeInspection.Candidate> candidates = new ArrayList<>();
  private final List<ScopeInspection.Page> pages = new ArrayList<>();
  private final Map<String, DiskTrafficStore.PacketRef> referencesById = new LinkedHashMap<>();
  private final Map<String, ScopeInspection.Profile> profiles = new LinkedHashMap<>();
  private final Map<String, Integer> profileVersions = new LinkedHashMap<>();
  private long conditionRevision = 1;
  private final Map<String, EnvironmentControls.Reference> missingReferences = new HashMap<>();
  private final Map<String, Integer> verifiedAt = new HashMap<>();
  private final Map<String, String> verifiedOriginals = new HashMap<>();
  private final Set<String> environmentGuesses = new LinkedHashSet<>();
  private final Set<String> redirectTargets = new HashSet<>();
  private final Map<String, EnvironmentCatalog> catalogs = new HashMap<>();
  private final Map<String, List<ScopeInspection.Page>> accessComparisons = new HashMap<>();
  private final ArrayDeque<KatanaTask> katanaQueue = new ArrayDeque<>();
  private final Map<String, KatanaTask> katanaPending = new LinkedHashMap<>();
  private final Map<String, Task> katanaDeferred = new LinkedHashMap<>();
  private final Map<String, DiskTrafficStore.PacketRef> responseCache = new LinkedHashMap<>();
  private final Set<String> analyzedOriginals = new HashSet<>();
  private final LinkedHashMap<String, Trace> coverage = new LinkedHashMap<>();
  private final LinkedHashMap<String, ScopeInspectionCoverage.Row> browserCoverage = new LinkedHashMap<>();
  private final List<Task> suspendedTasks = new ArrayList<>();
  private boolean selectiveRecheck;
  private final Set<String> discoveredSecrets = new LinkedHashSet<>();
  private int discoveredSecretBytes;
  private boolean secretLimit;
  private boolean katanaRegistered, katanaFinished;
  private String katanaProfileId;
  private long katanaDeadline;
  private KatanaTask activeKatana;
  private volatile ScopeInspection.State state = ScopeInspection.State.READY;
  private volatile boolean closed, browserFinished, browserConnected;
  private volatile long browserLastEvent;
  private volatile String message = "실행 준비 · 시작 전에는 요청을 전송하지 않습니다.";
  private volatile long retryAt, startedAt, finishedAt, lastSend;
  private volatile int sent, completed, excluded, failed, partial;
  private long order;
  private boolean candidateLimit, findingLimit;
  private Future<?> pending;

  private record Task(ScopeInspection.Candidate candidate, String profileId, int depth, int priority, long order) {}
  private record Received(HttpRequestResponse packet, DiskTrafficStore.PacketRef original, String headers, String body,
                          boolean complete, String note) {}
  private record Identity(boolean match, boolean different, boolean primaryMatch) {}
  private record DiscoveryInputs(Set<String> blockedPaths, Set<String> groundedGets) {}
  private record KatanaTask(String key, Task task, long condition, CompletableFuture<DiskTrafficStore.PacketRef> result) {}
  private static final class Trace {
    final String key, profileId, revision;
    ScopeInspection.Candidate candidate;
    final LinkedHashSet<String> stages = new LinkedHashSet<>(List.of("DISCOVERED"));
    String state = "DISCOVERED", reason = "경로 발견 · 아직 전송하지 않음", originalId = "";
    int status;
    boolean retryAllowed = true;
    Trace(String key, String profileId, String revision, ScopeInspection.Candidate candidate) {
      this.key=key;this.profileId=profileId;this.revision=revision;this.candidate=candidate;
    }
  }
  private static final class Halt extends RuntimeException { Halt(String text) { super(text); } }
  private static final class Retry extends RuntimeException {}
  private static final class Excluded extends RuntimeException { Excluded(String text) { super(text); } }
  private static final class KatanaCancelled extends RuntimeException {}

  ScopeInspectionRunner(MontoyaApi api, ScopeInspection.Options options, Predicate<String> scope,
                        Consumer<ScopeInspection.Snapshot> changed) {
    this.api = Objects.requireNonNull(api); this.options = Objects.requireNonNull(options);
    this.scope = Objects.requireNonNull(scope); this.changed = Objects.requireNonNull(changed);
    String normalized = normalized(options.url());
    if (normalized == null) throw new IllegalArgumentException("올바른 HTTP(S) 시작 URL을 입력하세요.");
    origin = EnvironmentCatalog.origin(URI.create(normalized));
    runRoot = scopeRoot(normalized);
    for (var profile : options.profiles()) {
      if (profile.id() == null || profile.id().isBlank() || profiles.putIfAbsent(profile.id(), profile) != null)
        throw new IllegalArgumentException("비교 계정 ID가 없거나 중복됩니다.");
      if (profile.identity() != null && !origin.equals(EnvironmentCatalog.origin(URI.create(profile.identity().url()))))
        throw new IllegalArgumentException("신원 확인 URL과 시작 URL의 Origin이 다릅니다.");
      // Validate profile header names and values without creating or sending a target request.
      Map<String,String> normalizedHeaders = Core.headers(String.join("\n", profile.headers().entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList()));
      profiles.put(profile.id(), new ScopeInspection.Profile(profile.id(), profile.label(), normalizedHeaders, profile.identity()));
      profileVersions.put(profile.id(), 1);
    }
  }

  String id() { return id; }
  ScopeInspection.Options options() { return options; }

  /** Validate the current in-memory credential revision without exposing credentials to the UI. */
  boolean profilesMatch(List<ScopeInspection.Profile> fresh){
    synchronized(lock){
      if(closed||fresh==null||fresh.size()!=profiles.size())return false;
      Map<String,ScopeInspection.Profile> supplied=new HashMap<>();
      for(var value:fresh)if(value==null||supplied.putIfAbsent(value.id(),value)!=null)return false;
      if(!supplied.keySet().equals(profiles.keySet()))return false;
      return profiles.entrySet().stream().allMatch(e->e.getValue().equals(supplied.get(e.getKey())));
    }
  }

  /** Register the finite producer before start, without creating any target request. */
  void registerKatana(String profileId, long deadlineMillis) {
    synchronized (lock) {
      long now = System.currentTimeMillis();
      if (closed || state != ScopeInspection.State.READY || katanaRegistered)
        throw new IllegalStateException("새 실행을 시작하기 전에 Katana를 연결하세요.");
      if (!profiles.containsKey(profileId) || !options.compareAccounts() && !profiles.keySet().iterator().next().equals(profileId))
        throw new IllegalArgumentException("현재 실행에서 선택한 HTTP 계정을 연결하세요.");
      if (options.katana() && !options.katanaProfileId().equals(profileId))
        throw new IllegalArgumentException("Katana 수집 계정과 저장할 검사 조건이 다릅니다.");
      if (deadlineMillis <= now || deadlineMillis - now > 30 * 60_000L)
        throw new IllegalArgumentException("Katana 완료 시한은 현재부터 30분 이내여야 합니다.");
      katanaRegistered = true; katanaProfileId = profileId; katanaDeadline = deadlineMillis;
      addFindingLocked(finding("COVERAGE", "Katana 탐색 대기", options.url(), "GET", profile(profileId).label(), "미확인", "INFO", "대기",
          "선택 HTTP 계정의 표준 크롤러 요청을 공통 전송·예산·원본 분석으로 처리합니다.", "", "katana-wait"));
    }
  }

  /** The guard proxy supplies only URL/method; crawler cookies, headers and bodies are never adopted. */
  CompletableFuture<DiskTrafficStore.PacketRef> fetchKatana(String url, String method) {
    synchronized (lock) {
      try {
        if (closed || selectiveRecheck || !katanaRegistered || katanaFinished || state != ScopeInspection.State.RUNNING && state != ScopeInspection.State.PAUSED)
          throw new IllegalStateException("Katana 전송을 받을 실행이 아닙니다.");
        String raw = normalized(url), verb = Objects.requireNonNullElse(method, "").toUpperCase(Locale.ROOT);
        if (raw == null || !allowed(raw) || !Set.of("GET", "HEAD").contains(verb) || !readOnlyUrl(raw) || secretInput(raw))
          throw new IllegalArgumentException("Katana 요청이 허용 URL·SCOPE·조회 메서드·민감값 입력 정책 밖입니다.");
        if (System.currentTimeMillis() >= katanaDeadline) {
          finishKatanaLocked(false, "제한 시간 안에 크롤러 완료를 확인하지 못했습니다.");
          throw new IllegalStateException("Katana 완료 시한이 지났습니다.");
        }
        var selected = profile(katanaProfileId);
        String key = responseKey(selected, raw, verb);
        KatanaTask existing = katanaPending.get(key);
        if (existing != null && !existing.result().isCancelled()) return existing.result();
        if (katanaPending.size() >= MAX_CANDIDATES) throw new IllegalStateException("Katana 대기 요청 한도에 도달했습니다.");
        if (candidates.size() >= MAX_CANDIDATES && !candidateKeys.contains(verb + "|" + raw))
          throw new IllegalStateException("이 실행의 후보 개수 한도에 도달했습니다.");
        addCandidateLocked(new ScopeInspection.Candidate(raw, verb, "Katana 실제 요청", "표준 크롤러 경로", true), Math.min(1, options.depth()), 1);
        Task task = queue.stream().filter(q -> q.profileId().equals(katanaProfileId) && q.candidate().url().equals(raw)
            && q.candidate().method().equals(verb)).findFirst().orElseGet(() ->
                new Task(new ScopeInspection.Candidate(raw, verb, "Katana 실제 요청", "표준 크롤러 경로", true), katanaProfileId, Math.min(1, options.depth()), 1, order++));
        if (!responseCache.containsKey(key) && !queue.contains(task)) queue.add(task);
        katanaDeferred.remove(taskKey(task));
        var future = new CompletableFuture<DiskTrafficStore.PacketRef>();
        var work = new KatanaTask(key, task, conditionRevision, future);
        katanaPending.put(key, work); katanaQueue.add(work); lock.notifyAll();
        return future;
      } catch (RuntimeException rejected) { return CompletableFuture.failedFuture(rejected); }
    }
  }

  /** JSONL is a metadata source; original HTTP evidence comes only from fetchKatana. */
  void offerKatana(ScopeInspection.Candidate candidate) {
    if (candidate == null) return;
    synchronized (lock) {
      if (closed || !katanaRegistered || katanaFinished || state != ScopeInspection.State.RUNNING && state != ScopeInspection.State.PAUSED) return;
      boolean sensitive = secretInput(candidate.url());
      boolean blocked = Objects.toString(candidate.source(), "").contains("차단");
      String declaredSource = Objects.toString(candidate.source(), "");
      String source = blocked ? "Katana 차단" : Set.of("Katana 환경", "Katana 폼").contains(declaredSource) ? declaredSource : "Katana JSONL";
      String label = displayText(candidate.label(), 160);
      String method = Objects.toString(candidate.method(), "?").toUpperCase(Locale.ROOT);
      String normalized = normalized(candidate.url());
      var original = normalized == null ? null : responseCache.get(responseKey(profile(katanaProfileId), normalized, method));
      if (original == null && normalized != null && source.equals("Katana 환경")) {
        original = responseCache.get(responseKey(profile(katanaProfileId), normalized, "GET"));
        if (original == null) original = responseCache.get(responseKey(profile(katanaProfileId), normalized, "HEAD"));
      }
      addFindingLocked(finding("KATANA", blocked || sensitive ? "Katana 요청 차단" : source.equals("Katana 환경") ? "Katana 환경 관측" : source.equals("Katana 폼") ? "Katana 폼 관측" : "Katana 경로 관측", candidate.url(), method, profile(katanaProfileId).label(),
          "크롤러 메타데이터", "INFO", blocked || sensitive ? "제외" : "관측", source + " · " + label + " · 원본은 공통 Montoya 전송 저장본에서만 연결합니다.",
          original == null ? "" : original.id(), "katana-metadata:" + method + ":" + Objects.toString(normalized, candidate.url()) + ":" + blocked));
      rememberLimitedCandidateLocked(new ScopeInspection.Candidate(sensitive ? origin + "/%5BREDACTED%5D" : candidate.url(), method, source, label, false),
          "Katana 발견 메타데이터 · 실제 프록시 조회 없이 추가 HTTP를 예약하지 않음");
      lock.notifyAll();
    }
    publish();
  }

  void finishKatana(boolean complete, String note) {
    synchronized (lock) {
      if (closed || !katanaRegistered || katanaFinished) return;
      finishKatanaLocked(complete, note); lock.notifyAll();
    }
    publish();
  }

  private void finishKatanaLocked(boolean complete, String note) {
    katanaFinished = true;
    findings.entrySet().removeIf(e -> e.getValue().title().equals("Katana 탐색 대기"));
    if (!complete) partial++;
    addFindingLocked(finding("COVERAGE", complete ? "Katana 생산 종료" : "Katana 탐색 미완료", options.url(), "GET", profile(katanaProfileId).label(),
        "관측", "INFO", complete ? "생산 종료" : "부분 검사", bounded(ScopeInspectionAnalysis.display(note, 300), 300)
            + " · 이미 받은 후보와 HTTP 대기열은 이어서 처리합니다. 전체 사이트 탐색을 확정하지 않습니다.", "", "katana-finished"));
  }

  void start() {
    synchronized (lock) {
      if (closed || state != ScopeInspection.State.READY) throw new IllegalStateException("새 실행을 준비하세요.");
      if (!allowed(options.url())) throw new IllegalArgumentException("시작 URL이 현재 고정 SCOPE 밖입니다.");
      startedAt = System.currentTimeMillis(); state = ScopeInspection.State.RUNNING;
      message = "시작 URL과 관련 경로를 확인합니다.";
      addCandidateLocked(new ScopeInspection.Candidate(options.url(), "GET", "시작 URL", "선택한 SCOPE 시작 URL", true), 0, 0);
      String app = runRoot;
      for (String path : List.of("/robots.txt", "/sitemap.xml", "/.well-known/security.txt", "/openapi.json", "/swagger.json", "/v3/api-docs"))
        addCandidateLocked(new ScopeInspection.Candidate(origin + app + path, "GET", "공통 경로", "초기 공통 후보", true), 0,
            path.equals("/robots.txt") || path.equals("/sitemap.xml") ? 30 : path.contains("security.txt") ? 70 : 35);
      addCandidateLocked(new ScopeInspection.Candidate(origin + app + "/.env", "GET", "선별 환경 파일", "허용 앱 루트의 단일 .env 형식 확인", true), 0, 34);
      if (!app.isEmpty()) for (String path : List.of("/robots.txt", "/sitemap.xml"))
        addCandidateLocked(new ScopeInspection.Candidate(origin + path, "GET", "공통 경로", "사이트 경로 선언", true), 0, 32);
      if (options.browser()) addFindingLocked(finding("COVERAGE", "브라우저 탐색 대기", options.url(), "GET", "browser-session",
          "미확인", "INFO", "대기", "브라우저 도우미의 별도 세션을 기다립니다. HTTP 계정과 자동으로 같은 신원으로 취급하지 않습니다.", "", "browser-wait"));
      long token = generation.incrementAndGet(); pending = executor.submit(() -> run(token));
    }
    publish();
  }

  void pause() {
    synchronized (lock) {
      if (closed || state != ScopeInspection.State.RUNNING) return;
      state = ScopeInspection.State.PAUSED; message = "사용자 일시정지 · 현재 응답 후 다음 전송을 기다립니다."; lock.notifyAll();
    }
    publish();
  }

  void resume() { resume(null); }

  /** Refresh credentials only for the same declared identities; historical packets remain immutable. */
  void resume(List<ScopeInspection.Profile> refreshed) {
    synchronized (lock) {
      if (closed || state != ScopeInspection.State.PAUSED && state != ScopeInspection.State.STOPPED)
        throw new IllegalStateException("일시정지한 실행을 선택하세요.");
      if (System.currentTimeMillis() < retryAt) throw new IllegalArgumentException("Retry-After 대기 후 재개하세요.");
      if (sent >= options.budget()) throw new IllegalArgumentException("이 실행의 요청 한도가 소진됐습니다. 새 실행을 준비하세요.");
      if (!allowed(options.url())) throw new IllegalArgumentException("고정 SCOPE 또는 프로젝트가 바뀌었습니다. 새 실행을 시작하세요.");
      if (refreshed != null) {
        Map<String, ScopeInspection.Profile> replacements = new LinkedHashMap<>();
        for (var next : refreshed) replacements.put(next.id(), next);
        if (!replacements.keySet().equals(profiles.keySet())) throw new IllegalArgumentException("동일 계정 구성으로 재개하세요.");
        for (var entry : profiles.entrySet()) {
          var next = replacements.get(entry.getKey());
          if (!Objects.equals(entry.getValue().identity(), next.identity()) || !entry.getValue().label().equals(next.label()))
            throw new IllegalArgumentException("다른 신원·권한 조건은 새 실행으로 검사하세요.");
          Core.headers(String.join("\n", next.headers().entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList()));
        }
        for (var next : replacements.values()) {
          if (!profiles.get(next.id()).headers().equals(next.headers())) {
            profileVersions.merge(next.id(), 1, Integer::sum);
            addFindingLocked(finding("AUTH", "동일 계정 인증 갱신 후 재개", options.url(), "GET", next.label(), "설정 스냅샷", "INFO", "조건 변경",
                "새 인증 revision r" + profileVersions.get(next.id()) + " · 이전 응답·원본과 구분해 보관합니다.", "", "profile-revision:" + next.id() + ":" + profileVersions.get(next.id())));
          }
        }
        profiles.clear();
        for (var next : replacements.values()) {
          Map<String,String> normalizedHeaders = Core.headers(String.join("\n", next.headers().entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList()));
          profiles.put(next.id(), new ScopeInspection.Profile(next.id(), next.label(), normalizedHeaders, next.identity()));
        }
      }
      verifiedAt.clear(); verifiedOriginals.clear(); redirectTargets.clear(); missingReferences.clear(); retryAt = 0; conditionRevision++;
      restoreSuspendedLocked(); selectiveRecheck=false;
      for (var deferred : katanaDeferred.values()) if (!queue.contains(deferred)) queue.add(deferred);
      katanaDeferred.clear();
      boolean restart = state == ScopeInspection.State.STOPPED;
      state = ScopeInspection.State.RUNNING; finishedAt = 0; message = "재개 · 신원과 공통 응답 기준을 다시 확인합니다.";
      if (restart) { long token = generation.incrementAndGet(); pending = executor.submit(() -> run(token)); }
      lock.notifyAll();
    }
    publish();
  }

  void stop() {
    synchronized (lock) {
      if (closed || state == ScopeInspection.State.COMPLETED || state == ScopeInspection.State.FAILED) return;
      state = ScopeInspection.State.STOPPED; finishedAt = System.currentTimeMillis();
      message = "중지 · 원본과 남은 경로를 보존했습니다."; generation.incrementAndGet();
      failKatanaPendingLocked(new CancellationException("통합 점검 중지"));
      if (pending != null) pending.cancel(true); lock.notifyAll();
    }
    publish();
  }

  ScopeInspection.Snapshot snapshot() {
    synchronized (lock) {
      int waiting = pendingCountLocked();
      List<ScopeInspection.Page> visiblePages=new ArrayList<>(pages);
      Set<String> linked=new HashSet<>();for(var page:visiblePages)if(page.original()!=null)linked.add(page.original().id());
      int pendingAnalysis=0;
      // A response can already be durably retained when cancellation interrupts decoding
      // or analysis. Publish that exact original without decoding, hashing or completing it.
      for(Trace trace:coverage.values()){
        if(trace.status<=0||!trace.stages.contains("RESPONDED")||trace.originalId.isBlank()||linked.contains(trace.originalId))continue;
        var original=referencesById.get(trace.originalId);var profile=profiles.get(trace.profileId);
        if(original==null||!original.hasResponse()||profile==null)continue;
        linked.add(trace.originalId);pendingAnalysis++;
        visiblePages.add(new ScopeInspection.Page(displayUrl(original.url()),original.method(),displayText(profile.label(),160),trace.status,
            "RESPONSE_PENDING_ANALYSIS",false,"원문 응답 수신·보관 · 디코딩/분석 미완료 · 원본만 연결하며 분석 완료로 처리하지 않음", "",original,trace.revision));
      }
      return new ScopeInspection.Snapshot(id, state, message, sent, completed, waiting, excluded, failed, partial+pendingAnalysis,
          new ArrayList<>(findings.values()), visiblePages, new ArrayList<>(candidates), startedAt, finishedAt, coverageRowsLocked());
    }
  }

  /** Explicitly recheck only selected unresolved read-only routes; other pending work is retained. */
  int recheck(List<String> keys) {
    int count;
    synchronized (lock) {
      if (closed || state != ScopeInspection.State.COMPLETED && state != ScopeInspection.State.STOPPED)
        throw new IllegalStateException("완료 또는 중지한 실행에서 미확인 경로를 선택하세요.");
      if (keys == null || keys.isEmpty() || keys.size() > 100) throw new IllegalArgumentException("재점검 경로를 1~100개 선택하세요.");
      if (sent >= options.budget()) throw new IllegalArgumentException("이 실행의 요청 한도가 소진됐습니다. 새 실행을 준비하세요.");
      if (System.currentTimeMillis() < retryAt) throw new IllegalArgumentException("Retry-After 대기 후 재점검하세요.");
      if (!allowed(options.url())) throw new IllegalArgumentException("고정 SCOPE 또는 프로젝트가 바뀌었습니다. 새 실행을 준비하세요.");
      LinkedHashSet<String> selected = new LinkedHashSet<>(keys);
      List<Trace> work = new ArrayList<>();
      for (String key : selected) {
        Trace trace = coverage.get(key);
        if (trace == null || !retryableLocked(trace)) throw new IllegalArgumentException("선택 경로의 인증 revision·조회 정책·미확인 상태를 다시 확인하세요.");
        work.add(trace);
      }
      // All validation precedes queue/cache mutation. No credentials are imported or refreshed here.
      if (!selectiveRecheck) suspendedTasks.addAll(queue);
      else suspendedTasks.addAll(queue.stream().filter(t -> !suspendedTasks.contains(t)).toList());
      queue.clear(); selectiveRecheck = true;
      for (Trace trace : work) {
        var profile = profile(trace.profileId); String raw = normalized(trace.candidate.url());
        suspendedTasks.removeIf(t->t.profileId().equals(trace.profileId)&&t.candidate().method().equals(trace.candidate.method())&&t.candidate().url().equals(raw));
        responseCache.remove(responseKey(profile, raw, trace.candidate.method()));
        queue.add(new Task(new ScopeInspection.Candidate(raw,trace.candidate.method(),trace.candidate.source(),trace.candidate.label(),true),
            trace.profileId, options.depth(), -10, order++));
        trace.state="QUEUED";trace.reason="선택한 경로만 조회 재점검 · 원래 인증 revision·Scope·총 예산 유지 · 추가 탐색 깊이 확장 없음";trace.stages.add("QUEUED");
      }
      verifiedAt.clear(); verifiedOriginals.clear(); missingReferences.clear(); redirectTargets.clear();
      conditionRevision++; state=ScopeInspection.State.RUNNING; finishedAt=0;
      message="선택 경로 조회 재점검 · 다른 대기 경로는 보존";
      long token=generation.incrementAndGet();pending=executor.submit(()->run(token));count=work.size();
      lock.notifyAll();
    }
    publish();return count;
  }

  private Collection<ScopeInspection.Profile> runProfiles() {
    return options.compareAccounts()?profiles.values():List.of(profiles.values().iterator().next());
  }

  private Trace traceLocked(ScopeInspection.Candidate candidate, ScopeInspection.Profile profile) {
    String revision=revisionOf(profile),method=Objects.toString(candidate.method(),"?").toUpperCase(Locale.ROOT);
    String key=ScopeInspectionCoverage.key(id+"|"+profile.id()+"|"+revision+"|"+method+"|"+candidate.url());
    Trace trace=coverage.get(key);
    if(trace==null && coverage.size()<MAX_TASKS+500){
      trace=new Trace(key,profile.id(),revision,new ScopeInspection.Candidate(candidate.url(),method,candidate.source(),candidate.label(),candidate.executable()));
      coverage.put(key,trace);
    } else if(trace!=null && candidate.executable() && !trace.candidate.executable()) trace.candidate=candidate;
    return trace;
  }

  private void traceStateLocked(Task task,String state,String reason) {
    Trace trace=traceLocked(task.candidate(),profile(task.profileId()));
    if(trace!=null){trace.state=state;trace.reason=reason;}
  }

  private boolean retryableLocked(Trace trace) {
    var profile=profiles.get(trace.profileId);
    if(profile==null||!trace.revision.equals(revisionOf(profile))||!trace.retryAllowed||!trace.candidate.executable())return false;
    if(!Set.of("FAILED","UNVISITED","PARTIAL","QUEUED").contains(trace.state))return false;
    if(Set.of(401,403,429).contains(trace.status))return false;
    String raw=normalized(trace.candidate.url());
    return raw!=null&&allowed(raw)&&readOnlyUrl(raw)&&!secretInput(raw)&&Set.of("GET","HEAD").contains(trace.candidate.method());
  }

  private List<ScopeInspectionCoverage.Row> coverageRowsLocked() {
    List<ScopeInspectionCoverage.Row> result=new ArrayList<>();
    for(Trace trace:coverage.values()){
      var profile=profiles.get(trace.profileId);if(profile==null)continue;
      String phase=trace.state,reason=trace.reason;
      boolean current=trace.revision.equals(revisionOf(profile));
      if(!current){phase="STALE";reason="이전 인증 revision의 관측·원본 보존 · 현재 조건으로 재사용하지 않음";}
      else if(phase.equals("QUEUED")&&(state==ScopeInspection.State.STOPPED||state==ScopeInspection.State.FAILED)){
        phase="UNVISITED";reason="대기 경로 미방문 · "+message;
      }else if(phase.equals("QUEUED")&&state==ScopeInspection.State.PAUSED)reason="다음 전송 보류 · "+message;
      boolean retryable=current&&retryableLocked(trace)&&!closed&&(state==ScopeInspection.State.COMPLETED||state==ScopeInspection.State.STOPPED)&&sent<options.budget();
      result.add(new ScopeInspectionCoverage.Row(trace.key,candidateDisplay(trace.candidate),trace.candidate.method(),displayText(profile.label(),160),
          trace.revision,displayText(trace.candidate.source(),120),displayText(trace.candidate.label(),160),phase,displayText(reason,1200),
          trace.status,trace.originalId,retryable,new ArrayList<>(trace.stages),trace.candidate.url().indexOf('?')>=0||trace.candidate.url().indexOf('#')>=0));
    }
    result.addAll(browserCoverage.values());return List.copyOf(result);
  }

  DiskTrafficStore.PacketRef original(String originalId) {
    synchronized (lock) { return closed ? null : referencesById.get(originalId); }
  }

  /** Authenticated helper metadata is still rechecked against this run and its live scope. */
  void offerBrowser(Map<String, Object> event) {
    if (event == null || !options.browser() || !id.equals(Objects.toString(event.get("runId"), ""))) return;
    synchronized (lock) {
      if (closed || state != ScopeInspection.State.RUNNING && state != ScopeInspection.State.PAUSED) return;
      String kind = Objects.toString(event.get("kind"), Objects.toString(event.get("state"), ""));
      if (!Set.of("started", "route", "menu", "api", "excluded", "progress", "done", "error").contains(kind)) return;
      String networkStage=Objects.toString(event.get("networkStage"),"");
      // The Runner can also be embedded without the browser bridge. Validate transport
      // evidence again before it can update progress, findings, queues or coverage.
      if(kind.equals("api")&&!networkStage.isEmpty()
          &&(!Set.of("request","response","error").contains(networkStage)
              ||!Objects.toString(event.get("requestId"),"").matches("[a-fA-F0-9]{64}")
              ||networkStage.equals("response")&&browserStatus(event)==0))return;
      browserConnected = true; browserLastEvent = System.currentTimeMillis();
      String url = Objects.toString(event.get("url"), Objects.toString(event.get("page"), options.url()));
      String method = Objects.toString(event.get("method"), "GET").toUpperCase(Locale.ROOT);
      String routeId = Objects.toString(event.get("routeId"), "");
      String pageRouteId = bounded(Objects.toString(event.get("pageRouteId"), ""), 80);
      String label = bounded(Objects.toString(event.get("label"), kind), 120);
      if (kind.equals("done") || kind.equals("error")) {
        browserFinished = true;
        boolean incomplete = kind.equals("error") || "partial".equals(Objects.toString(event.get("state"), ""));
        if (incomplete) {
          partial++; addFindingLocked(finding("COVERAGE", "브라우저 탐색 미완료", options.url(), "GET", "browser-session", "관측", "INFO", "부분 검사",
              kind.equals("error") ? "브라우저 작업이 오류로 끝났습니다. 이미 관측한 메뉴와 HTTP 원본은 유지합니다."
                  : "브라우저 작업이 부분 종료되었습니다. 로그인 세션과 미확인 메뉴를 확인하세요. 이미 관측한 메뉴와 HTTP 원본은 유지합니다.", "", "browser-incomplete"));
        }
        message = incomplete ? "브라우저 탐색 미완료 · HTTP 후보 처리 중" : "브라우저 탐색 완료 · 남은 HTTP 후보 처리 중";
      } else if (kind.equals("route") || kind.equals("menu") || kind.equals("api")) {
        if (!allowed(url)) {
          excluded++; addFindingLocked(finding("COVERAGE", "브라우저 범위 밖 관측", url, method, "browser-session", "관측", "INFO", "제외",
              "현재 실행 SCOPE 밖이므로 HTTP 재조회를 예약하지 않았습니다.", "", "browser-outside:" + routeId));
        } else {
          boolean queryRequired = Boolean.TRUE.equals(event.get("requiresQuery"));
          if(kind.equals("api"))traceBrowserLocked(event,url,method,label,routeId);
          String networkNote=networkStage.equals("response")?" · 브라우저 HTTP "+browserStatus(event)+" 응답 관측 · 본문/원본 미수집·HTTP 계정 접근 성공과 별도"
              :networkStage.equals("error")?" · 브라우저 통신 오류 · 자동 HTTP 재요청 예약하지 않음":networkStage.equals("request")?" · 브라우저 실제 요청 관측":"";
          addFindingLocked(finding(kind.equals("api") ? "API" : "MENU", label, url, method, "browser-session", "브라우저 관측", "INFO", "관측",
              "브라우저 별도 세션 · 라우트 지문 " + bounded(routeId, 80)
                  + (pageRouteId.isBlank() ? "" : " · 페이지 라우트 지문 " + pageRouteId + " · 탭·현재 라우트 관측 문맥, JS 인과 확정 아님")
                  + (queryRequired ? " · 쿼리 값 원본 필요" : " · HTTP 재조회는 선택한 계정으로 별도 수행")+networkNote, "", "browser:" + kind + ":" + routeId + ":" + pageRouteId + ":" + method+":"+networkStage));
          if(!kind.equals("api")||networkStage.isEmpty()||networkStage.equals("request"))
            addCandidateLocked(new ScopeInspection.Candidate(url, method, kind.equals("api") ? "브라우저 API" : "메뉴 라우트", label,
                !queryRequired && Set.of("GET", "HEAD").contains(method)), Math.min(1, options.depth()), 10);
        }
      } else if (kind.equals("excluded")) {
        excluded++; addFindingLocked(finding("COVERAGE", "브라우저 동작 제외", url, method, "browser-session", "관측", "INFO", "제외",
            "범위·조회 메서드·메뉴 정책에 따라 브라우저 동작을 제외했습니다.", "", "browser-excluded:" + routeId + ":" + label));
      }
      lock.notifyAll();
    }
    publish();
  }

  private static int browserStatus(Map<String,Object> event){
    Object value=event.get("statusCode");
    if(!(value instanceof Number n))return 0;
    double status=n.doubleValue();
    return Double.isFinite(status)&&status==Math.rint(status)&&status>=100&&status<=599?(int)status:0;
  }

  private void traceBrowserLocked(Map<String,Object> event,String url,String method,String label,String routeId){
    String phase=Objects.toString(event.get("networkStage"),""),request=Objects.toString(event.get("requestId"),"");
    if(!Set.of("request","response","error").contains(phase)||!request.matches("[a-fA-F0-9]{64}"))return;
    String key=ScopeInspectionCoverage.key(id+"|browser-session|"+request);
    var old=browserCoverage.get(key);if(old==null&&browserCoverage.size()>=MAX_CANDIDATES)return;
    LinkedHashSet<String> stages=new LinkedHashSet<>(old==null?List.of("DISCOVERED","SCOPE_CHECKED"):old.stages());
    stages.add("REQUESTED");int status=phase.equals("response")?browserStatus(event):0;String state,reason;
    if(phase.equals("response")){
      stages.add("RESPONDED");state="HTTP_RESPONDED";
      reason="브라우저 세션의 응답 메타데이터 관측 · 본문/원본 미수집 · 선택 HTTP 계정과 별도 · 라우트 지문 "+bounded(routeId,80);
    }else if(phase.equals("error")){
      state="FAILED";reason="브라우저 통신 오류 · "+bounded(Objects.toString(event.get("errorCode"),"NETWORK_ERROR"),80)+" · 자동 HTTP 재요청 없음";
    }else{
      state="REQUESTING";reason="브라우저 세션 실제 요청 관측 · 응답·본문 분석은 미확인";
      if(old!=null&&(old.state().equals("HTTP_RESPONDED")||old.state().equals("FAILED"))){state=old.state();reason=old.reason();status=old.status();}
    }
    browserCoverage.put(key,new ScopeInspectionCoverage.Row(key,displayUrl(url),method,"browser-session","browser-session", "브라우저 실제 통신",displayText(label,160),state,
        displayText(reason,1200),status,"",false,new ArrayList<>(stages),Boolean.TRUE.equals(event.get("requiresQuery"))||url.indexOf('?')>=0||url.indexOf('#')>=0||old!=null&&old.queryUnresolved()));
  }

  private void run(long token) {
    try {
      while (true) {
        ready(token);
        KatanaTask crawl;
        synchronized (lock) {
          expireKatanaLocked(); crawl = selectiveRecheck?null:katanaQueue.peek();
        }
        if (crawl != null) {
          inspectKatana(crawl, token); publish(); continue;
        }
        Task task;
        synchronized (lock) { task = queue.peek(); if (!selectiveRecheck&&holdGuessedLocked(task)) task = null; }
        if (task == null) {
          synchronized(lock){
            if(selectiveRecheck){
              restoreSuspendedLocked();selectiveRecheck=false;addInventoryLocked();
              state=queue.isEmpty()&&katanaDeferred.isEmpty()?ScopeInspection.State.COMPLETED:ScopeInspection.State.STOPPED;
              finishedAt=System.currentTimeMillis();
              message="선택 경로 재점검 종료 · 다른 대기 경로 "+pendingCountLocked()+"건 보존 · 전체 확인을 의미하지 않음";
              publish();return;
            }
          }
          if (awaitSources(token)) continue;
          ready(token);
          synchronized (lock) {
            if (!queue.isEmpty() || !katanaQueue.isEmpty()) continue;
            if (!katanaDeferred.isEmpty()) throw new Halt("취소된 Katana 요청을 보류했습니다. 재개하면 남은 후보를 이어서 검사합니다.");
            addInventoryLocked();
            state = ScopeInspection.State.COMPLETED; finishedAt = System.currentTimeMillis();
            message = "발견한 대기열 처리 완료 · 미확인 " + (partial + failed) + "건 · 전체 사이트 확인을 의미하지 않습니다.";
          }
          publish(); return;
        }
        try {
          inspect(task, token);
          ready(token);
          synchronized (lock) { queue.remove(task); completed++; }
        } catch (Retry retry) {
          // The same immutable task stays at the head for explicit resume.
        } catch (Excluded skip) {
          synchronized (lock) {
            queue.remove(task); excluded++;
            traceStateLocked(task,"EXCLUDED",skip.getMessage());
            addFindingLocked(finding("COVERAGE", "후보 확인 제외", task.candidate().url(), task.candidate().method(), profile(task.profileId()).label(),
                "관측", "INFO", "제외", skip.getMessage(), "", "excluded:" + task.profileId() + ":" + task.candidate().url()));
          }
        } catch (Halt halted) {
          throw halted;
        } catch (Exception error) {
          synchronized (lock) {
            queue.remove(task); failed++; completed++;
            traceStateLocked(task,"FAILED","원인: "+error.getClass().getSimpleName()+" · 실제 응답·분석 완료 근거 없음");
            addFindingLocked(finding("COVERAGE", "요청·분석 실패", task.candidate().url(), task.candidate().method(), profile(task.profileId()).label(),
                "미확인", "INFO", "실패", "원인: " + error.getClass().getSimpleName() + " · 해당 경로 확인을 완료하지 못했습니다.", "", "error:" + task.profileId() + ":" + task.candidate().url()));
          }
        }
        publish();
      }
    } catch (Halt halt) {
      synchronized (lock) {
        if (token != generation.get() || closed) return;
        state = ScopeInspection.State.STOPPED; finishedAt = System.currentTimeMillis(); message = halt.getMessage();
        failKatanaPendingLocked(new CancellationException(message));
        addFindingLocked(finding("COVERAGE", "실행 중단·미검사 경로", options.url(), "GET", "전체", "관측", "INFO", "미완료",
            message + " · 남은 후보 " + pendingCountLocked() + "건", "", "halt:" + sent));
      }
      publish();
    } catch (RuntimeException | LinkageError | AssertionError error) {
      synchronized (lock) {
        if (token != generation.get() || closed) return;
        failed++; state = ScopeInspection.State.FAILED; finishedAt = System.currentTimeMillis();
        message = "통합 점검 오류 · " + error.getClass().getSimpleName() + " · 완료로 처리하지 않았습니다.";
        failKatanaPendingLocked(new IllegalStateException(message));
      }
      publish();
    }
  }

  private void inspect(Task task, long token) throws Exception {
    String url = task.candidate().url(); String method = task.candidate().method();
    ScopeInspection.Profile profile = profile(task.profileId());
    if (!allowed(url)) throw new Excluded("현재 고정 SCOPE/Origin 밖 경로");
    if (!readOnlyUrl(url) || !Set.of("GET", "HEAD").contains(method) || secretInput(url)) throw new Excluded("쓰기·동적 값·대용량·민감값 입력 경로는 직접 검증 필요");
    synchronized (lock) {
      if (responseCache.containsKey(responseKey(profile, url, method))) return;
    }
    identity(profile, token);
    preflight(task, profile, token);
    EnvironmentControls.Reference reference = reference(url, profile, token);
    Received received = fetch(url, method, profile, task.depth(), token, false);
    guard(token);
    analyzeReceived(task, profile, received, reference);
    transientState(profile, received.packet().response().statusCode(), received.headers(), received.body(), received.original(), token);
  }

  private void analyzeReceived(Task task, ScopeInspection.Profile profile, Received received, EnvironmentControls.Reference reference) {
    synchronized (lock) {
      if (!analyzedOriginals.add(received.original().id())) return;
    }
    String url = received.packet().request().url(), method = received.packet().request().method();
    HttpResponse response = received.packet().response(); int status = response.statusCode();
    var analysis = ScopeInspectionAnalysis.analyze(url, status, received.headers(), received.body(), profile.label(), received.original().id());
    DiscoveryInputs inputs = discoveryInputs(analysis.candidates());
    rememberSecrets(received, analysis.findings().stream().anyMatch(f -> f.category().equals("SECRET")));
    String comparison = EnvironmentControls.state(status, received.headers(), received.body(), url, reference, IGNORED_FIELDS);
    boolean incidentalForm = incidentalPasswordForm(profile, url, status, received.body());
    var content = received.complete() ? EnvironmentContent.inspect(url, status, received.headers(), received.body(), comparison, incidentalForm)
        : new EnvironmentContent.Finding("LIMIT", "본문 분석 미완료", received.note(), "미검사 원본과 범위를 확인하세요.");
    boolean referenceComplete = reference != null && reference.stable();
    boolean targetCovered = !Set.of("LOGIN", "CHALLENGE", "RATE", "DENIED", "REDIRECT", "LIMIT", "UNKNOWN", "ERROR", "SOFT404", "SIMILAR").contains(content.kind());
    boolean complete = received.complete() && referenceComplete && targetCovered;
    String note = received.note() + " · " + comparison;
    if (task.candidate().source().contains("Katana")) note += " · Katana 표준 크롤러 · 공통 Montoya 원본";
    if (!targetCovered) note += " · 대상 내용 미확인 · " + content.title() + " · 해당 응답의 정적 단서만 분석";
    if (!profile.headers().isEmpty() && profile.identity() == null) note += " · 선택 계정 신원 미확인";
    if (!referenceComplete) note += " · 공통 응답 대조 미완료";
    if (incidentalForm) note += identityVerified(profile) ? " · 비밀번호 폼과 별개로 선택 신원 조건 확인" : " · 비밀번호 폼만으로 세션 만료를 판정하지 않음 · 선택 신원 미확인";
    ScopeInspection.Page page = new ScopeInspection.Page(displayUrl(url), method, profile.label(), status, content.kind(), complete,
        note, digest(received.headers(), received.body(), url), received.original(), revisionOf(profile));
    synchronized (lock) {
      addPageLocked(page);
      Trace trace=traceLocked(new ScopeInspection.Candidate(url,method,task.candidate().source(),task.candidate().label(),true),profile);
      if(trace!=null){
        trace.stages.add("ANALYZED");trace.status=status;trace.originalId=received.original().id();
        trace.state=status==401||status==403?"DENIED":Set.of("LOGIN","CHALLENGE","RATE").contains(content.kind())?"AUTH_REQUIRED":complete&&analysis.complete()?"ANALYZED":"PARTIAL";
        trace.reason=note+(!analysis.complete()?" · 정적 분석 일부 미완료 · "+analysis.note():"");
        if(Set.of("SOFT404","SIMILAR","LOGIN","CHALLENGE","RATE","DENIED","REDIRECT","LIMIT").contains(content.kind())||status==401||status==403)trace.retryAllowed=false;
      }
      addFindingLocked(finding("PATH", content.title(), url, method, profile.label(), "응답 관측", severity(content.kind()), complete ? "내용 확인" : "부분 검사",
          content.reason() + " · " + comparison + (received.complete() ? "" : " · " + received.note()), received.original().id(), "path:" + content.kind()));
    }
    synchronized (lock) {
      for (var item : analysis.findings()) addFindingLocked(item);
      if (incidentalForm && !profile.headers().isEmpty()) {
        boolean verified = identityVerified(profile);
        addFindingLocked(finding("AUTH", verified ? "비밀번호 폼 포함 화면 · 선택 신원 확인" : "비밀번호 폼 · 신원 확인 보류", url, method, profile.label(),
            verified ? "신원 조건 대조" : "신원 미확인", "INFO", verified ? "조건 확인" : "확인 필요",
            "회원가입·비밀번호 변경·공통 로그인 모달의 입력 필드만으로 세션 만료를 판단하지 않습니다."
                + (verified ? " 신원 대조 원본: " + verifiedOriginals.getOrDefault(profile.id(), "") : " 기존 계정의 신원 확인 조건을 연결해 실제 계정을 대조하세요."),
            received.original().id(), "incidental-password-form"));
      }
      if (!analysis.complete()) {
        partial++;
        addFindingLocked(finding("COVERAGE", "정적 분석 일부 미완료", url, method, profile.label(), "관측", "INFO", "부분 검사",
            analysis.note(), received.original().id(), "analysis-partial"));
      }
    }
    boolean startShell = task.candidate().source().equals("시작 URL") && Set.of("SOFT404", "SIMILAR").contains(content.kind());
    if ((!FALLBACK.contains(content.kind()) || startShell) && status >= 200 && status < 300 && received.complete()) {
      List<ScopeInspection.Candidate> discovered = startShell ? analysis.candidates().stream().filter(c -> priority(c) < 20).toList() : analysis.candidates();
      if (task.depth() < options.depth()) {
        synchronized (lock) {
          for (var candidate : discovered) {
            if (candidate.source().equals("환경 경로") && blockedReference(candidate.url(), inputs))
              rememberLimitedCandidateLocked(candidate, "서버·메서드·필수값 미확정 선언과 겹치는 인용 경로 · GET 추정하지 않음");
            else addCandidateLocked(candidate, task.depth() + 1, priority(candidate));
          }
        }
      } else synchronized (lock) {
        for (var candidate : discovered) rememberLimitedCandidateLocked(candidate, "탐색 깊이 한도 · 미검사");
      }
      if (!startShell) learnEnvironment(url, received.headers(), received.body(), profile, task.depth(), inputs);
    }
    compareAccess(page, profile);
  }

  private void inspectKatana(KatanaTask crawl, long token) {
    activeKatana = crawl;
    boolean retry = false;
    try {
      if (crawl.result().isCancelled()) throw new KatanaCancelled();
      Task task = crawl.task(); String url = task.candidate().url(), method = task.candidate().method();
      ScopeInspection.Profile selected = profile(task.profileId());
      if (!allowed(url) || !Set.of("GET", "HEAD").contains(method) || !readOnlyUrl(url) || secretInput(url))
        throw new Excluded("Katana 전송 직전 Scope·조회·민감값 입력 정책 제외");
      DiskTrafficStore.PacketRef cached;
      synchronized (lock) { cached = responseCache.get(responseKey(selected, url, method)); }
      if (cached != null) {
        ready(token);
        if (crawl.result().isCancelled()) throw new KatanaCancelled();
        if (!allowed(url)) throw new Excluded("Katana 캐시 전달 직전 SCOPE 변경");
        boolean analyzed;
        synchronized (lock) { analyzed = analyzedOriginals.contains(cached.id()); }
        if (!analyzed) {
          Received stored = readCached(cached, selected);
          EnvironmentControls.Reference baseline;
          synchronized (lock) { baseline = missingReferences.get(selected.id() + "|" + runRoot); }
          Task traced = new Task(new ScopeInspection.Candidate(url, method, "Katana 실제 요청", "공통 원본 재사용", true), task.profileId(), task.depth(), task.priority(), task.order());
          guard(token);
          analyzeReceived(traced, selected, stored, baseline);
          offerRedirectCandidate(stored, task.depth());
        }
        synchronized (lock) {
          addFindingLocked(finding("KATANA", "Katana 원본 재사용", url, method, selected.label(), "동일 계정·인증 revision", "INFO", "재사용",
              "동일 URL·메서드의 기존 공통 Montoya 응답 원본을 크롤러에 반환했습니다. 추가 대상 HTTP는 전송하지 않았습니다.", cached.id(), "katana-cache:" + crawl.key()));
        }
        crawl.result().complete(cached);
        synchronized (lock) { queue.remove(task); katanaDeferred.remove(taskKey(task)); }
        return;
      }
      identity(selected, token);
      preflight(task, selected, token);
      EnvironmentControls.Reference baseline = reference(url, selected, token);
      Received received = sendOnce(url, method, selected, token, false);
      Task traced = new Task(new ScopeInspection.Candidate(url, method, "Katana 실제 요청", "표준 크롤러 경로", true), task.profileId(), task.depth(), task.priority(), task.order());
      guard(token);
      analyzeReceived(traced, selected, received, baseline);
      synchronized (lock) {
        addFindingLocked(finding("KATANA", "Katana HTTP 응답", url, method, selected.label(), "공통 Montoya 전송", "INFO", "응답 관측",
            "HTTP " + received.packet().response().statusCode() + " · 선택 계정·SCOPE·공통 요청 예산 적용 · 이 hop의 원본 응답을 반환합니다.",
            received.original().id(), "katana-http:" + crawl.key()));
      }
      offerRedirectCandidate(received, task.depth());
      ready(token);
      if (!crawl.result().isCancelled() && allowed(url)) crawl.result().complete(received.original());
      transientState(selected, received.packet().response().statusCode(), received.headers(), received.body(), received.original(), token);
      synchronized (lock) { queue.remove(task); katanaDeferred.remove(taskKey(task)); completed++; }
    } catch (Retry paused) {
      retry = true;
    } catch (KatanaCancelled cancelled) {
      synchronized (lock) {
        if (conditionRevision == crawl.condition()) {
          queue.remove(crawl.task()); katanaDeferred.put(taskKey(crawl.task()), crawl.task());
          addFindingLocked(finding("KATANA", "Katana 요청 보류", crawl.task().candidate().url(), crawl.task().candidate().method(), profile(crawl.task().profileId()).label(),
              "취소 관측", "INFO", "미검사", "프록시 요청이 취소되어 같은 HTTP 계정의 일반 대기열에서도 보류했습니다. 새 크롤러 요청 또는 명시적 재개 후 처리합니다.", "", "katana-cancel:" + crawl.key()));
        }
      }
    } catch (Halt halted) {
      crawl.result().completeExceptionally(new CancellationException(halted.getMessage()));
      throw halted;
    } catch (Excluded excludedRequest) {
      synchronized (lock) {
        queue.remove(crawl.task()); excluded++;
        traceStateLocked(crawl.task(),"EXCLUDED",excludedRequest.getMessage());
        addFindingLocked(finding("KATANA", "Katana 요청 제외", crawl.task().candidate().url(), crawl.task().candidate().method(), profile(crawl.task().profileId()).label(),
            "관측", "INFO", "미검사", excludedRequest.getMessage(), "", "katana-excluded:" + crawl.key()));
      }
      crawl.result().completeExceptionally(new IllegalArgumentException(excludedRequest.getMessage()));
    } catch (Exception error) {
      synchronized (lock) {
        queue.remove(crawl.task()); failed++; completed++;
        traceStateLocked(crawl.task(),"FAILED","Katana 공통 전송·분석 실패 · "+error.getClass().getSimpleName());
        addFindingLocked(finding("COVERAGE", "Katana 공통 전송·분석 실패", crawl.task().candidate().url(), crawl.task().candidate().method(), profile(crawl.task().profileId()).label(),
            "미확인", "INFO", "실패", "원인: " + error.getClass().getSimpleName() + " · 해당 경로 확인을 완료하지 못했습니다.", "", "katana-error:" + crawl.key()));
      }
      crawl.result().completeExceptionally(new IllegalStateException("Katana 공통 전송·분석 실패: " + error.getClass().getSimpleName()));
    } finally {
      activeKatana = null;
      synchronized (lock) {
        if (!retry || crawl.result().isDone()) {
          katanaQueue.remove(crawl); katanaPending.remove(crawl.key(), crawl);
        }
      }
    }
  }

  private void offerRedirectCandidate(Received received, int depth) {
    int status = received.packet().response().statusCode();
    if (status < 300 || status >= 400) return;
    String location = EnvironmentResponse.header(received.headers(), "Location");
    if (location.isBlank()) return;
    try {
      String next = URI.create(received.packet().request().url()).resolve(location).toString();
      synchronized (lock) {
        if (depth < options.depth()) addCandidateLocked(new ScopeInspection.Candidate(next, "GET", "Katana 이동 응답", "Location 관측", !secretInput(next)), depth + 1, 2);
        else rememberLimitedCandidateLocked(new ScopeInspection.Candidate(next, "GET", "Katana 이동 응답", "Location 관측", false), "탐색 깊이 한도 · 이동 대상 미검사");
      }
    } catch (RuntimeException invalid) {
      synchronized (lock) { partial++; }
    }
  }

  private Received readCached(DiskTrafficStore.PacketRef original, ScopeInspection.Profile profile) throws Exception {
    HttpRequestResponse packet = original.packet();
    String headers = EnvironmentPanel.responseHeaders(packet.response());
    int bytes = packet.response().body().length(); long declared = contentLength(headers);
    boolean complete = bytes <= options.maxBodyBytes() && (declared < 0 || declared <= options.maxBodyBytes());
    String body = "", note = "기존 공통 원본 재사용 · 인증 스냅샷 " + revisionOf(profile);
    if (complete) {
      String type = EnvironmentResponse.header(headers, "Content-Type").toLowerCase(Locale.ROOT);
      boolean compressed = !EnvironmentResponse.header(headers, "Content-Encoding").isBlank()
          && !EnvironmentResponse.header(headers, "Content-Encoding").equalsIgnoreCase("identity");
      if ((!type.isBlank() && TEXT_TYPES.stream().noneMatch(type::contains) && !type.contains("octet-stream")) || !compressed && binary(packet.response())) complete = false;
      else try { body = JsBodyDecoder.decode(packet.response().body().getBytes(), EnvironmentResponse.header(headers,"Content-Encoding"), type, options.maxBodyBytes()); }
      catch (java.io.IOException | RuntimeException incomplete) { complete = false; }
    }
    if (!complete) note += " · 본문 분석 한도·형식으로 미검사 · 전체 wire 원본 유지";
    return new Received(packet, original, headers, body, complete, note);
  }

  private void preflight(Task task, ScopeInspection.Profile profile, long token) throws Exception {
    String url = task.candidate().url();
    if (!task.candidate().method().equals("GET") || !url.matches("(?i).*(?:\\.(?:log|bak|backup)|/(?:logs?|backups?|downloads?|dumps?))(?:$|[/?#].*)")) return;
    Received head = fetch(url, "HEAD", profile, task.depth(), token, true);
    synchronized (lock) {
      addPageLocked(new ScopeInspection.Page(displayUrl(head.packet().request().url()), "HEAD", profile.label(), head.packet().response().statusCode(),
          "HEAD_PREFLIGHT", true, "대용량 후보의 메타데이터만 먼저 확인 · 본문을 내려받지 않았습니다.", "", head.original(), revisionOf(profile)));
    }
    transientState(profile, head.packet().response().statusCode(), head.headers(), head.body(), head.original(), token);
    long declared = contentLength(head.headers());
    String type = EnvironmentResponse.header(head.headers(), "Content-Type").toLowerCase(Locale.ROOT);
    if (declared < 0 || declared > options.maxBodyBytes() || head.packet().response().statusCode() >= 400
        || type.matches(".*(?:zip|octet-stream|pdf|image/|audio/|video/|x-tar).*")) {
      synchronized (lock) {
        addFindingLocked(finding("COVERAGE", "대용량 후보 본문 미수집", url, "GET", profile.label(), "헤더 관측", "INFO", "미검사",
            declared < 0 ? "Content-Length 미확인 · GET 자동 다운로드 대신 직접 확인 필요" : "헤더 크기·형식·상태 기준으로 자동 GET 제외",
            head.original().id(), "large-preflight"));
      }
      throw new Excluded("대용량/형식/미확정 크기 경로 · HEAD 원본만 보관");
    }
  }

  private Received fetch(String url, String method, ScopeInspection.Profile profile, int depth,
                         long token, boolean control) throws Exception {
    String current = normalized(url); Set<String> visited = new HashSet<>(); int hops = 0;
    while (true) {
      if (current == null || !allowed(current)) throw new Excluded("리디렉션 또는 요청 URL이 현재 고정 SCOPE 밖입니다.");
      if (!visited.add(current)) throw new Excluded("리디렉션 순환 · 미검사");
      Received received = sendOnce(current, method, profile, token, control);
      int status = received.packet().response().statusCode();
      if (status < 300 || status >= 400) return received;
      String location = EnvironmentResponse.header(received.headers(), "Location");
      if (location.isBlank()) return received;
      synchronized (lock) {
        addPageLocked(new ScopeInspection.Page(displayUrl(current), method, profile.label(), status, control ? "CONTROL_REDIRECT" : "REDIRECT", false,
            "이동 응답 원본 · 대상 내용 미확인 · Location은 이동 전 다시 검사합니다.", digest(received.headers(), received.body(), current), received.original(), revisionOf(profile)));
        if(!control){
          Trace trace=traceLocked(new ScopeInspection.Candidate(current,method,"실제 HTTP 요청","리디렉션 원본",true),profile);
          if(trace!=null){trace.state="PARTIAL";trace.reason="리디렉션 hop 응답 · 최종 대상 내용과 별도 원본";trace.retryAllowed=false;}
        }
      }
      String target;
      try { target = URI.create(current).resolve(location).toString(); } catch (RuntimeException error) { target = ""; }
      String normalizedTarget = normalized(target);
      if (normalizedTarget == null || !allowed(normalizedTarget) || !readOnlyUrl(normalizedTarget)) {
        synchronized (lock) {
          excluded++;
          addFindingLocked(finding("COVERAGE", "리디렉션 제외", current, method, profile.label(), "응답 관측", "INFO", "미완료",
              "이동 대상이 허용 Origin/SCOPE·조회 정책 밖이거나 URL이 미확정입니다. 인증정보를 전달하지 않았습니다.", received.original().id(), "redirect-outside"));
        }
        return new Received(received.packet(), received.original(), received.headers(), received.body(), false, "리디렉션 범위 밖 · 최종 내용 미확인");
      }
      if (hops++ >= options.maxRedirects()) return new Received(received.packet(), received.original(), received.headers(), received.body(), false, "리디렉션 횟수 한도 · 최종 내용 미확인");
      transientState(profile, status, received.headers(), received.body(), received.original(), token);
      current = normalizedTarget;
      if (status == 303 && !method.equals("HEAD")) method = "GET";
    }
  }

  private Received sendOnce(String url, String method, ScopeInspection.Profile profile, long token, boolean control) throws Exception {
    guard(token);
    if (!allowed(url)) throw new Excluded("전송 직전 고정 SCOPE/프로젝트 경계 제외");
    HttpRequest request = HttpRequest.httpRequestFromUrl(url).withMethod(method);
    request = Core.sessionRequest(request, profile.headers(), credentialNames(profile));
    beforeSend(token);
    if (!allowed(url)) throw new Excluded("전송 직전 SCOPE가 바뀌었습니다.");
    checkKatanaCancellation();
    synchronized (lock) {
      sent++; lastSend = System.currentTimeMillis(); message = "요청 " + sent + " / " + options.budget() + " · 남은 후보 " + queue.size();
      if(!control){
        Trace trace=traceLocked(new ScopeInspection.Candidate(url,method,"실제 HTTP 요청","공통 HTTP 전송",true),profile);
        if(trace!=null){trace.stages.add("SCOPE_CHECKED");trace.stages.add("REQUESTED");trace.state="REQUESTING";trace.reason="동일 계정·인증 revision·Scope·공통 예산으로 전송";trace.status=0;trace.originalId="";}
      }
    }
    HttpRequestResponse packet = api.http().sendRequest(request, RequestOptions.requestOptions()
        .withResponseTimeout(options.timeoutMillis()).withRedirectionMode(RedirectionMode.NEVER));
    guard(token);
    if (packet == null || packet.response() == null || packet.request() == null) throw new IllegalStateException("응답 또는 실제 전송 요청이 없습니다.");
    if (!AuthComparePanel.sameTarget(packet.request(), request) || !method.equals(packet.request().method()) || !allowed(packet.request().url())
        || !AuthComparePanel.credentialValues(packet.request(), credentialNames(profile)).equals(AuthComparePanel.credentialValues(request, credentialNames(profile))))
      throw new IllegalStateException("실제 전송 대상·메서드·인증 변경");
    DiskTrafficStore.PacketRef original = originals.append(LocalTime.now().toString(), "Scope " + id + " · " + profile.id() + (control ? " · 대조" : ""), packet.request(), packet.response());
    synchronized (lock) { referencesById.put(original.id(), original); }
    HttpResponse response = packet.response();
    if(!control)synchronized(lock){
      Trace trace=traceLocked(new ScopeInspection.Candidate(url,method,"실제 HTTP 요청","공통 HTTP 전송",true),profile);
      if(trace!=null){trace.stages.add("RESPONDED");trace.status=response.statusCode();trace.originalId=original.id();trace.state="PARTIAL";trace.reason="실제 HTTP 응답 수신 · 본문 분석 대기";}
    }
    String headers = EnvironmentPanel.responseHeaders(response);
    if (response.statusCode() >= 300 && response.statusCode() < 400) try {
      String target = normalized(URI.create(url).resolve(EnvironmentResponse.header(headers, "Location")).toString());
      if (target != null && allowed(target)) synchronized (lock) { redirectTargets.add(profile.id() + "|" + target); }
    } catch (RuntimeException ignored) { }
    int bytes = response.body() == null ? 0 : response.body().length();
    long declared = contentLength(headers);
    boolean complete = bytes <= options.maxBodyBytes() && (declared < 0 || declared <= options.maxBodyBytes());
    String note = "수신 " + bytes + " bytes · 전체 원본 보관 · 인증 스냅샷 " + revisionOf(profile);
    String body = "";
    if (!complete) note += " · 분석 바이트 상한 초과, 본문 미검사 (Montoya 전송 계층의 다운로드 상한은 별개)";
    else {
      String type = EnvironmentResponse.header(headers, "Content-Type").toLowerCase(Locale.ROOT);
      boolean compressed = !EnvironmentResponse.header(headers, "Content-Encoding").isBlank()
          && !EnvironmentResponse.header(headers, "Content-Encoding").equalsIgnoreCase("identity");
      boolean text = type.isBlank() || TEXT_TYPES.stream().anyMatch(type::contains) || type.contains("octet-stream");
      if (!text || !compressed && binary(response)) { complete = false; note += " · 비텍스트 본문 미검사"; }
      else {
        try {
          body = JsBodyDecoder.decode(response.body() == null ? new byte[0] : response.body().getBytes(),
              EnvironmentResponse.header(headers, "Content-Encoding"), type, options.maxBodyBytes());
          if (body.length() > options.maxBodyBytes()) {
            body = body.substring(0, options.maxBodyBytes()); complete = false;
            note += " · 압축 해제/문자 길이 분석 한도 · 뒷부분 미검사";
          }
        } catch (java.io.IOException | RuntimeException error) {
          complete = false; note += " · 디코딩·압축 해제 한도 또는 형식 오류 · 본문 미검사";
        }
      }
    }
    if (!control && response.statusCode() != 429 && (response.statusCode() != 401 || profile.headers().isEmpty())
        && (!EnvironmentContent.login(body) || incidentalPasswordForm(profile, url, response.statusCode(), body)) && !EnvironmentContent.challenge(body)) {
      synchronized (lock) { responseCache.put(responseKey(profile, url, method), original); }
    }
    publish();
    return new Received(packet, original, headers, body, complete, note);
  }

  private void identity(ScopeInspection.Profile profile, long token) throws Exception {
    if (profile.headers().isEmpty()) return;
    CookieSessionProbe.Config config = profile.identity();
    if (config == null) {
      synchronized (lock) { addFindingLocked(finding("AUTH", "선택 계정 신원 미확인", options.url(), "GET", profile.label(), "미확인", "INFO", "확인 필요",
          "기존 계정 설정에 신원 확인 조건이 없습니다. 인증값은 사용자가 선택한 범위에서만 사용하며 권한 판정은 확정하지 않습니다.", "", "identity-not-configured")); }
      return;
    }
    synchronized (lock) { if (verifiedAt.containsKey(profile.id()) && sent - verifiedAt.get(profile.id()) < 25) return; }
    if (!allowed(config.url())) throw new Excluded("계정 신원 확인 URL이 고정 SCOPE 밖입니다.");
    Received authenticated = fetch(config.url(), "GET", profile, 0, token, true);
    addControlPage(authenticated, config.url(), profile, "IDENTITY_CONTROL");
    transientState(profile, authenticated.packet().response().statusCode(), authenticated.headers(), authenticated.body(), authenticated.original(), token);
    Identity full = identityOutcome(authenticated, config);
    if (!full.match()) {
      autoPause("계정 신원 확인 실패 · 새 인증정보를 연결한 뒤 재개하세요.", 0);
      throw new Retry();
    }
    ScopeInspection.Profile anonymous = new ScopeInspection.Profile(profile.id() + ":identity-anonymous", "신원 비로그인 대조", Map.of(), null);
    Received without = fetch(config.url(), "GET", anonymous, 0, token, true);
    addControlPage(without, config.url(), anonymous, "IDENTITY_CONTROL");
    transientState(anonymous, without.packet().response().statusCode(), without.headers(), without.body(), without.original(), token);
    Identity empty = identityOutcome(without, config);
    if (!empty.different() || empty.primaryMatch()) {
      autoPause("비로그인 신원 대조가 구분되지 않습니다. 계정·신원 조건을 확인한 뒤 재개하세요.", 0);
      throw new Retry();
    }
    synchronized (lock) { verifiedAt.put(profile.id(), sent); verifiedOriginals.put(profile.id(), authenticated.original().id() + " / " + without.original().id()); }
  }

  private Identity identityOutcome(Received received, CookieSessionProbe.Config config) {
    int status = received.packet().response().statusCode();
    if (status == 401 || status == 403) return new Identity(false, true, false);
    if (!received.complete() || status < 200 || status >= 300 || received.body().length() > CookieSessionProbe.MAX_RESPONSE)
      return new Identity(false, false, false);
    Map<String, Object> json = AuthJudgement.json(received.body());
    if (json == null) return new Identity(false, false, false);
    Object value = AuthJudgement.value(json, config.identityPath());
    boolean primary = scalar(value) && config.expectedIdentity().equals(String.valueOf(value));
    boolean claims = primary;
    if (primary) for (var claim : config.claims()) {
      if (json.entrySet().stream().noneMatch(e -> AuthJudgement.match(e.getKey(), claim.path()) && scalar(e.getValue()) && claim.expected().equals(String.valueOf(e.getValue())))) {
        claims = false; break;
      }
    }
    return new Identity(primary && claims, !primary || !claims, primary);
  }

  private EnvironmentControls.Reference reference(String url, ScopeInspection.Profile profile, long token) throws Exception {
    String app = runRoot, key = profile.id() + "|" + app;
    synchronized (lock) { if (missingReferences.containsKey(key)) return missingReferences.get(key); }
    EnvironmentControls.Sample first = null, second = null;
    String issue = "기준 요청/SCOPE/본문 형식 확인 불가";
    for (int i = 0; i < 2; i++) {
      String missing = origin + app + "/wb-missing-" + UUID.randomUUID();
      if (!allowed(missing)) { issue = "없는 경로 대조가 허용 SCOPE 밖"; break; }
      try {
        Received received = fetch(missing, "GET", profile, 0, token, true);
        addControlPage(received, missing, profile, "MISSING_CONTROL");
        transientState(profile, received.packet().response().statusCode(), received.headers(), received.body(), received.original(), token);
        if (!received.complete()) { issue = "기준 본문이 미검사/부분 검사"; break; }
        var sample = EnvironmentControls.sample(received.packet().response().statusCode(), received.headers(), received.body(), missing, IGNORED_FIELDS);
        if (i == 0) first = sample; else second = sample;
      } catch (Halt | Retry | KatanaCancelled halted) { throw halted; }
      catch (Excluded excluded) { issue = excluded.getMessage(); break; }
      catch (Exception error) { issue = "기준 전송/디코딩 오류 · " + error.getClass().getSimpleName(); break; }
    }
    EnvironmentControls.Reference reference = EnvironmentControls.reference(first, second, true, issue);
    synchronized (lock) {
      missingReferences.put(key, reference);
      if (!reference.stable()) addFindingLocked(finding("COVERAGE", "공통 응답 대조 미완료", url, "GET", profile.label(), "미확인", "INFO", "확인 필요",
          reference.note(), "", "reference:" + app));
    }
    return reference;
  }

  private void addControlPage(Received received, String url, ScopeInspection.Profile profile, String classification) {
    synchronized (lock) {
      addPageLocked(new ScopeInspection.Page(displayUrl(url), "GET", profile.label(), received.packet().response().statusCode(), classification,
          received.complete(), received.note(), digest(received.headers(), received.body(), url), received.original(), revisionOf(profile)));
    }
  }

  private void transientState(ScopeInspection.Profile profile, int status, String headers, String body,
                              DiskTrafficStore.PacketRef original, long token) throws Exception {
    guard(token);
    if (status == 429) { autoPause("HTTP 429 · Retry-After 이후 직접 재개하세요.", EnvironmentScan.retryTime(headers)); throw new Retry(); }
    if (EnvironmentContent.challenge(body)) { autoPause("보안 확인 화면 감지 · 브라우저와 세션 확인 후 재개하세요.", 0); throw new Retry(); }
    if (!profile.headers().isEmpty()) {
      String location = EnvironmentResponse.header(headers, "Location");
      boolean loginRedirect = false;
      if (status >= 300 && status < 400 && !location.isBlank()) try { loginRedirect = loginPath(URI.create(original.url()).resolve(location).toString()); } catch (RuntimeException ignored) { }
      boolean redirectedForm = status >= 200 && status < 300 && EnvironmentContent.login(body) && dedicatedLoginForm(original.url(), body)
          && redirectTargets.contains(profile.id() + "|" + original.url());
      Map<String, String> cookies = CookieSessionProbe.cookies(profile.headers());
      for (String line : headers.split("\\r?\\n")) if (line.regionMatches(true, 0, "set-cookie:", 0, 11)) {
        String value = line.substring(11).strip(), pair = value.split(";", 2)[0]; int equal = pair.indexOf('=');
        if (equal > 0) {
          String name = pair.substring(0, equal).strip();
          if (cookies.containsKey(name) && (!cookies.get(name).equals(pair.substring(equal + 1)) || value.matches("(?i).*;\\s*max-age=\\s*(?:0|-[0-9]+)(?:;.*)?"))) {
            synchronized (lock) { verifiedAt.remove(profile.id()); }
            synchronized (lock) { responseCache.entrySet().removeIf(e -> e.getValue().id().equals(original.id())); }
            autoPause("세션 쿠키 변경 감지 · 새 인증정보를 수집한 뒤 재개하세요.", 0); throw new Retry();
          }
        }
      }
      if (status == 401 || loginRedirect || redirectedForm) {
        if (status == 401 && !EnvironmentContent.login(body) && !loginPath(original.url())
            && !loginRedirect && !redirectedForm && recheckDeniedIdentity(profile, original, token)) return;
        synchronized (lock) { verifiedAt.remove(profile.id()); }
        autoPause(status == 401 ? "로그인 세션 확인 필요 · 새 인증정보 연결 후 재개하세요." : "로그인 경로 이동 감지 · 계정·권한 조건을 확인한 뒤 재개하세요.", 0); throw new Retry();
      }
    }
  }

  /** A resource's 401 need not mean the already verified account session expired. */
  private boolean recheckDeniedIdentity(ScopeInspection.Profile profile, DiskTrafficStore.PacketRef denied, long token) throws Exception {
    CookieSessionProbe.Config config = profile.identity();
    if (config == null || !identityVerified(profile) || Objects.equals(normalized(config.url()), normalized(denied.url()))
        || !allowed(config.url()) || !readOnlyUrl(config.url()) || secretInput(config.url())) return false;
    synchronized (lock) { if (sent + 2 > options.budget()) return false; }
    Received fresh, freshAnonymous;
    try {
      // Bypass both caches: require a fresh authenticated/anonymous identity contrast.
      fresh = sendOnce(config.url(), "GET", profile, token, true);
      addControlPage(fresh, config.url(), profile, "IDENTITY_RECHECK_CONTROL");
      transientState(profile, fresh.packet().response().statusCode(), fresh.headers(), fresh.body(), fresh.original(), token);
      if (!identityOutcome(fresh, config).match()) return false;
      ScopeInspection.Profile anonymous = new ScopeInspection.Profile(profile.id() + ":identity-anonymous", "신원 비로그인 대조", Map.of(), null);
      freshAnonymous = sendOnce(config.url(), "GET", anonymous, token, true);
      addControlPage(freshAnonymous, config.url(), anonymous, "IDENTITY_RECHECK_CONTROL");
      transientState(anonymous, freshAnonymous.packet().response().statusCode(), freshAnonymous.headers(), freshAnonymous.body(), freshAnonymous.original(), token);
      Identity empty = identityOutcome(freshAnonymous, config);
      if (!empty.different() || empty.primaryMatch()) return false;
    } catch (Retry | Halt | KatanaCancelled stopped) { throw stopped; }
    catch (Exception failure) { return false; }
    synchronized (lock) {
      verifiedAt.put(profile.id(), sent);
      verifiedOriginals.put(profile.id(), fresh.original().id() + " / " + freshAnonymous.original().id());
      responseCache.put(responseKey(profile, denied.url(), denied.method()), denied);
      addFindingLocked(finding("ACCESS", "개별 경로 401 · 선택 신원 재확인", denied.url(), denied.method(), profile.label(),
          "신원 재확인·접근 거부 관측", "INFO", "미확인",
          "이 경로는 HTTP 401이지만 같은 인증 스냅샷의 새 신원 확인 응답은 설정한 신원·claim 조건과 일치합니다. "
              + "새 비로그인 대조도 선택 신원과 구분됩니다. 별도 인증 영역·필요 권한은 수동 확인하며 접근 성공이나 취약점으로 판정하지 않습니다. 신원 재확인 원본: "
              + fresh.original().id() + " / " + freshAnonymous.original().id(),
          denied.id(), "endpoint-401:" + profile.id() + ":" + denied.url()));
    }
    return true;
  }

  private boolean identityVerified(ScopeInspection.Profile profile) {
    synchronized (lock) { return profile.identity() != null && verifiedAt.containsKey(profile.id()) && sent - verifiedAt.get(profile.id()) < 25; }
  }

  private boolean incidentalPasswordForm(ScopeInspection.Profile profile, String url, int status, String body) {
    if (status < 200 || status >= 300 || !EnvironmentContent.login(body) || EnvironmentContent.challenge(body)) return false;
    boolean dedicated = dedicatedLoginForm(url, body);
    if (dedicated && redirectTargets.contains(profile.id() + "|" + url)) return false;
    return !dedicated || identityVerified(profile);
  }

  private static boolean loginPath(String url) {
    try { return Objects.toString(URI.create(url).getPath(), "").matches("(?i).*/(?:login|log[-_]?in|signin|sign[-_]?in)(?:\\.(?:php|aspx?|jsp|html?))?/?$"); }
    catch (RuntimeException invalid) { return false; }
  }

  private static boolean dedicatedLoginForm(String url, String body) {
    if (loginPath(url)) return true;
    try {
      if (!Objects.toString(URI.create(url).getPath(), "").matches("(?i).*/(?:auth|authentication|session)/?")) return false;
      var form = java.util.regex.Pattern.compile("(?is)<form\\b[^>]{0,4096}\\baction\\s*=\\s*['\"]([^'\"<>]{1,1024})['\"]").matcher(body);
      return form.find() && loginPath(URI.create(url).resolve(form.group(1)).toString());
    } catch (RuntimeException invalid) { return false; }
  }

  private void learnEnvironment(String url, String headers, String body, ScopeInspection.Profile profile, int depth, DiscoveryInputs inputs) {
    synchronized (lock) {
      EnvironmentCatalog catalog = catalogs.computeIfAbsent(profile.id(), key -> new EnvironmentCatalog());
      EnvironmentCatalog.Site site = catalog.observe(url, headers, body);
      for (var candidate : catalog.candidates(site, runRoot, true, 40)) {
        ScopeInspection.Candidate next = new ScopeInspection.Candidate(candidate.url(), "GET", candidate.source(), candidate.purpose(), true);
        if (blockedReference(next.url(), inputs))
          rememberLimitedCandidateLocked(next, "서버·메서드·필수값 미확정 선언과 겹치는 인용 경로 · GET 추정하지 않음");
        else if (depth < options.depth())
          addCandidateLocked(next, depth + 1, priority(next));
        else rememberLimitedCandidateLocked(next, "탐색 깊이 한도 · 환경 후보 미검사");
      }
    }
  }

  private DiscoveryInputs discoveryInputs(List<ScopeInspection.Candidate> discovered) {
    Set<String> blocked = new HashSet<>(), grounded = new HashSet<>();
    for (var candidate : discovered) {
      String source = Objects.toString(candidate.source(), ""), verb = Objects.toString(candidate.method(), "");
      String normalized = normalized(candidate.url());
      if (candidate.executable() && Set.of("GET", "HEAD").contains(verb)
          && (source.contains("JS") || source.contains("API 문서") || source.contains("관측 링크")) && normalized != null) grounded.add(normalized);
      if (!candidate.executable() && (source.contains("JS") || source.contains("API 문서"))) {
        boolean retainedScript = source.contains("JS") && Objects.toString(candidate.label(), "").startsWith("HTML · src")
            && discovered.stream().anyMatch(other -> other.executable() && other.method().equals("GET") && other.source().equals("환경 경로")
                && other.label().equals("실제 응답 참조") && Objects.equals(other.url(), candidate.url()));
        if (retainedScript && normalized != null) grounded.add(normalized);
        else {
          String path = referencePath(candidate.url()); if (path != null) blocked.add(path);
        }
      }
    }
    return new DiscoveryInputs(blocked, grounded);
  }

  private boolean blockedReference(String url, DiscoveryInputs inputs) {
    return inputs.blockedPaths().contains(referencePath(url)) && !inputs.groundedGets().contains(normalized(url));
  }

  /** Project unresolved relative paths only to veto a guessed GET, never to schedule one. */
  private String referencePath(String raw) {
    try {
      URI uri = URI.create(raw.replace("{", "%7B").replace("}", "%7D"));
      if (uri.getHost() != null) return EnvironmentCatalog.origin(uri) + Objects.toString(uri.getRawPath(), "/");
      if (uri.getScheme() == null && uri.getRawAuthority() == null && Objects.toString(uri.getRawPath(), "").startsWith("/")) return origin + uri.getRawPath();
    } catch (RuntimeException invalid) { }
    return null;
  }

  private void compareAccess(ScopeInspection.Page page, ScopeInspection.Profile profile) {
    if (!options.compareAccounts() || page.classification().endsWith("CONTROL")) return;
    synchronized (lock) {
      String key = conditionRevision + "|" + page.method() + "|" + page.url();
      List<ScopeInspection.Page> existing = accessComparisons.computeIfAbsent(key, ignored -> new ArrayList<>());
      existing.removeIf(previous -> previous.account().equals(page.account())); existing.add(page);
      if (existing.size() < 2) return;
      var first = existing.get(0);
      boolean difference = existing.stream().anyMatch(p -> p.status() != first.status() || !p.classification().equals(first.classification()) || !p.digest().equals(first.digest()));
      if (difference) {
        boolean verified = profiles.values().stream().filter(p -> !p.headers().isEmpty()).allMatch(p -> p.identity() != null && verifiedAt.containsKey(p.id()));
        String detail = String.join(" · ", existing.stream().map(p -> p.account() + ": HTTP " + p.status() + " / " + p.classification()).toList());
        addFindingLocked(finding("ACCESS", "계정별 응답 차이", page.url(), page.method(), "계정 비교 · 조건 " + conditionRevision, verified ? "조건 확인·응답 차이" : "신원/정책 확인 필요", "MEDIUM", "수동 검토",
            detail + " · 기대 권한 정책과 실제 영향을 별도 확인하세요. 응답 차이는 취약점 확정이 아닙니다.", page.original().id(), "access:" + key));
      }
    }
  }

  private boolean awaitSources(long token) {
    long browserDeadline = startedAt + Math.max(30_000, Math.min(90_000, options.timeoutMillis() * 6L));
    while (true) {
      ready(token);
      synchronized (lock) {
        if (!katanaQueue.isEmpty() || !queue.isEmpty() && !holdGuessedLocked(queue.peek())) return true;
        long now = System.currentTimeMillis();
        if (options.browser() && !browserFinished && now >= browserDeadline) {
          browserFinished = true; partial++;
          addFindingLocked(finding("COVERAGE", browserConnected ? "브라우저 탐색 제한 시간" : "브라우저 미연결·미탐색", options.url(), "GET", "browser-session", "관측", "INFO", "부분 검사",
              browserConnected ? "제한 시간 안에 브라우저 완료를 확인하지 못했습니다. 수집한 라우트와 HTTP 결과는 유지합니다." : "브라우저 도우미가 이 실행에 연결되지 않았습니다. SPA 메뉴 전체를 검사했다고 표시하지 않습니다.", "", "browser-timeout"));
        }
        expireKatanaLocked();
        boolean browserWaiting = options.browser() && !browserFinished, katanaWaiting = katanaRegistered && !katanaFinished;
        if (!browserWaiting && !katanaWaiting) return false;
        message = katanaWaiting ? "HTTP 대기열 처리 완료 · Katana 경로 수집 대기"
            : browserConnected ? "브라우저 메뉴·API 후보를 기다립니다." : "HTTP 대기열 처리 완료 · 브라우저 도우미 연결 대기";
        try { lock.wait(200); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Halt("검사 중지"); }
      }
    }
  }

  private void addInventoryLocked() {
    for (var item : ScopeInspectionAnalysis.compareInventory(new ArrayList<>(findings.values()), candidates, pages)) addFindingLocked(item);
  }

  private void addCandidateLocked(ScopeInspection.Candidate candidate, int depth, int priority) {
    String raw = normalized(candidate.url());
    String method = Objects.toString(candidate.method(), "?").toUpperCase(Locale.ROOT);
    String display = candidateDisplay(candidate);
    String key = method + "|" + Objects.toString(raw, display);
    boolean first = !candidateKeys.contains(key);
    if (first && candidates.size() >= MAX_CANDIDATES) { candidateLimitLocked(); return; }
    boolean executable = candidate.executable() && raw != null && Set.of("GET", "HEAD").contains(method) && readOnlyUrl(raw) && allowed(raw) && !secretInput(raw);
    for(var profile:runProfiles()){
      Trace trace=traceLocked(candidate,profile);
      if(trace!=null&&trace.state.equals("DISCOVERED")){
        if(raw!=null)trace.stages.add("SCOPE_CHECKED");
        if(!executable){trace.state="EXCLUDED";trace.retryAllowed=false;trace.reason=raw==null?"URL·필수값·동적 변수 미확정":!allowed(raw)?"허용 Origin/SCOPE 밖":secretInput(raw)?"관측 민감값을 요청 입력으로 재사용하지 않음":!candidate.executable()||!Set.of("GET","HEAD").contains(method)?"메서드·필수값 원본 확인 필요":"변경 동작·대용량 경로 직접 확인 필요";}
      }
    }
    if (executable && priority >= 80 && !environmentGuesses.contains(raw)) {
      boolean existingObserved = queue.stream().anyMatch(t -> t.candidate().url().equals(raw) && t.candidate().method().equals(method) && t.priority() < 80);
      if (!existingObserved) {
        if (environmentGuesses.size() >= MAX_ENVIRONMENT_GUESSES) {
          rememberLimitedCandidateLocked(candidate, "환경 추정 경로 자동 확인 한도 12개 · 실제 참조/API 선언 우선"); return;
        }
        environmentGuesses.add(raw);
      }
    }
    if (first) candidateKeys.add(key);
    ScopeInspection.Candidate safeCandidate = new ScopeInspection.Candidate(display, method, displayText(candidate.source(), 120), displayText(candidate.label(), 160), executable);
    if (first) candidates.add(safeCandidate);
    else if (executable) {
      for (int i = 0; i < candidates.size(); i++) {
        var old = candidates.get(i);
        if (old.url().equals(display) && old.method().equals(method) && (!old.executable() || priority < priority(old))) { candidates.set(i, safeCandidate); break; }
      }
    }
    if (!first && !executable) return;
    if (!executable) {
      excluded++;
      String reason = raw == null ? "URL·필수 값 미확정" : !allowed(raw) ? "허용 Origin/SCOPE 밖" : secretInput(raw) ? "관측 민감값을 요청 입력으로 재사용하지 않음" : !candidate.executable() || !Set.of("GET", "HEAD").contains(method) ? "메서드·필수 값 원본 확인 필요" : "변경 동작·대용량 경로 직접 확인 필요";
      addFindingLocked(finding("COVERAGE", "자동 요청 제외", display, method, "전체", "관측", "INFO", "미검사", reason + " · " + bounded(candidate.source(), 100), "", "candidate-excluded:" + key));
      return;
    }
    for (var profile : runProfiles()) {
      String taskKey = profile.id() + "|" + key;
      if (queued.add(taskKey)) {
        if (queue.size() >= MAX_TASKS) { partial++; continue; }
        Task next=new Task(new ScopeInspection.Candidate(raw, method, candidate.source(), candidate.label(), true), profile.id(), depth, priority, order++);
        if(selectiveRecheck)suspendedTasks.add(next);else queue.add(next);
        Trace trace=traceLocked(candidate,profile);
        if(trace!=null){trace.stages.add("QUEUED");trace.state="QUEUED";trace.reason=selectiveRecheck?"선택 재점검 중 발견 · 다른 대기 경로로 보존":"조회 후보 큐 등록 · 아직 대상 응답 없음";trace.retryAllowed=true;}
      } else {
        Task existing = queue.stream().filter(t -> t.profileId().equals(profile.id()) && t.candidate().url().equals(raw) && t.candidate().method().equals(method)).findFirst().orElse(null);
        if (existing != null && priority < existing.priority()) {
          queue.remove(existing);
          queue.add(new Task(new ScopeInspection.Candidate(raw, method, candidate.source(), candidate.label(), true), profile.id(), Math.min(depth, existing.depth()), priority, existing.order()));
        }
      }
    }
  }

  private void rememberLimitedCandidateLocked(ScopeInspection.Candidate candidate, String reason) {
    String key = candidate.method() + "|" + Objects.toString(normalized(candidate.url()), candidateDisplay(candidate));
    if (candidateKeys.contains(key)) return;
    if (candidates.size() >= MAX_CANDIDATES) { candidateLimitLocked(); return; }
    candidateKeys.add(key);
    for(var profile:runProfiles()){
      Trace trace=traceLocked(candidate,profile);
      if(trace!=null&&!trace.stages.contains("REQUESTED")){trace.state="UNVISITED";trace.reason=reason;trace.retryAllowed=candidate.executable();}
    }
    if (candidates.size() < MAX_CANDIDATES) candidates.add(new ScopeInspection.Candidate(candidateDisplay(candidate), candidate.method(), candidate.source(), displayText(candidate.label(),160), false));
    excluded++;
    addFindingLocked(finding("COVERAGE", "발견했지만 자동 방문하지 않음", candidate.url(), candidate.method(), "전체", "관측", "INFO", "미검사", reason, "", "limited:" + key));
  }

  private void candidateLimitLocked() {
    if (candidateLimit) return;
    candidateLimit = true; partial++;
    addFindingLocked(finding("COVERAGE", "후보 개수 한도", options.url(), "GET", "전체", "관측", "INFO", "부분 검사", "최대 500개 후보까지만 보관합니다. 추가 경로는 미검사입니다.", "", "candidate-limit"));
  }

  private void addFindingLocked(ScopeInspection.Finding finding) {
    String key = finding.category() + "|" + finding.url() + "|" + finding.method() + "|" + finding.account() + "|" + finding.title() + "|" + finding.fingerprint();
    if (findings.size() < MAX_FINDINGS || findings.containsKey(key)) findings.put(key, finding);
    else if (!findingLimit) {
      findingLimit = true; partial++;
      var limited = finding("COVERAGE", "분석 결과 개수 한도", options.url(), "GET", "전체", "관측", "INFO", "부분 검사", "최대 5000개 결과를 표시합니다. 추가 원본은 보관하지만 요약 결과는 일부 제외되었습니다.", "", "finding-limit");
      findings.put("finding-limit", limited);
    }
  }

  private void addPageLocked(ScopeInspection.Page page) {
    pages.add(page); if (!page.complete()) partial++;
  }

  private ScopeInspection.Profile profile(String profileId) {
    synchronized (lock) { return Objects.requireNonNull(profiles.get(profileId), "계정 스냅샷 없음"); }
  }

  private void autoPause(String reason, long until) {
    synchronized (lock) {
      state = ScopeInspection.State.PAUSED; retryAt = until; message = reason;
      addFindingLocked(finding("COVERAGE", "자동 일시정지", options.url(), "GET", "전체", "관측", "INFO", "미완료", reason, "", "pause:" + reason)); lock.notifyAll();
    }
    publish();
  }

  private void ready(long token) {
    guard(token);
    synchronized (lock) {
      while (state == ScopeInspection.State.PAUSED) {
        guard(token);
        try { lock.wait(100); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Halt("검사 중지"); }
      }
    }
    guard(token);
  }

  private void guard(long token) {
    if (closed || token != generation.get() || Thread.currentThread().isInterrupted() || state == ScopeInspection.State.STOPPED || state == ScopeInspection.State.CLOSED)
      throw new Halt("검사 중지 · 이미 수집한 원본과 대기 경로 유지");
    if (startedAt != 0 && !allowed(options.url())) throw new Halt("고정 SCOPE 또는 프로젝트 변경 · 새 실행이 필요합니다.");
  }

  private void beforeSend(long token) {
    ready(token);
    checkKatanaCancellation();
    if (sent >= options.budget()) throw new Halt("요청 한도 도달 · 남은 경로는 미검사입니다.");
    long remaining;
    while ((remaining = options.delayMillis() - (System.currentTimeMillis() - lastSend)) > 0) {
      try { Thread.sleep(Math.min(100, remaining)); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Halt("검사 중지"); }
      ready(token);
      checkKatanaCancellation();
    }
    guard(token);
    checkKatanaCancellation();
  }

  private void checkKatanaCancellation() {
    if (activeKatana != null && activeKatana.result().isCancelled()) throw new KatanaCancelled();
  }

  private String responseKey(ScopeInspection.Profile profile, String url, String method) {
    return profile.id() + "|" + revisionOf(profile) + "|" + method + "|" + url;
  }

  private void failKatanaPendingLocked(RuntimeException reason) {
    for (var crawl : katanaPending.values()) crawl.result().completeExceptionally(reason);
    katanaQueue.clear(); katanaPending.clear();
  }

  private static String taskKey(Task task) { return task.profileId() + "|" + task.candidate().method() + "|" + task.candidate().url(); }

  private int pendingCountLocked() {
    return queue.size() + suspendedTasks.size() + katanaDeferred.size() + (int)katanaQueue.stream().filter(k -> !queue.contains(k.task())).count();
  }

  private void restoreSuspendedLocked(){
    Set<String> present=new HashSet<>();for(Task task:queue)present.add(taskKey(task));
    for(Task task:suspendedTasks)if(present.add(taskKey(task)))queue.add(task);
    suspendedTasks.clear();
  }

  private boolean holdGuessedLocked(Task task) { return task != null && katanaRegistered && !katanaFinished && task.priority() >= 20; }

  private void expireKatanaLocked() {
    if (katanaRegistered && !katanaFinished && System.currentTimeMillis() >= katanaDeadline)
      finishKatanaLocked(false, "제한 시간 안에 Katana 완료를 확인하지 못했습니다.");
  }

  private void rememberSecrets(Received received, boolean classifiedSecret) {
    String body = received.body(), headers = received.headers();
    String lower = body.toLowerCase(Locale.ROOT);
    if (!classifiedSecret && !lower.contains("key") && !lower.contains("secret") && !lower.contains("password") && !lower.contains("token")
        && !lower.contains("authorization") && !lower.contains("cookie") && !lower.contains("jdbc:") && !lower.contains("://")
        && !headers.toLowerCase(Locale.ROOT).matches("(?s).*(?:set-cookie:|authorization:|x-api-key:).*")) return;
    try {
      for (String value : ScopeInspectionAnalysis.redactionValues(headers, body)) synchronized (lock) {
        if (discoveredSecrets.contains(value)) continue;
        int bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (discoveredSecrets.size() >= 256 || discoveredSecretBytes + bytes > 256 * 1024) throw new IllegalArgumentException("민감값 입력 검사 한도");
        discoveredSecrets.add(value); discoveredSecretBytes += bytes;
      }
    } catch (RuntimeException limited) {
      synchronized (lock) {
        if (secretLimit) return;
        secretLimit = true; partial++;
        addFindingLocked(finding("COVERAGE", "민감값 입력 대조 일부 미완료", received.packet().request().url(), received.packet().request().method(), "전체", "미확인", "INFO", "부분 검사",
            "민감값 식별·개수·용량 한도에 도달했습니다. 확인된 값만 요청 입력에서 제외하며 미관측 값의 안전을 확정하지 않습니다.", received.original().id(), "secret-input-limit"));
      }
    }
  }

  private boolean secretInput(String url) {
    if (url == null) return false;
    String decoded;
    try { decoded = java.net.URLDecoder.decode(url, java.nio.charset.StandardCharsets.UTF_8); }
    catch (RuntimeException malformed) { decoded = url; }
    synchronized (lock) {
      for (String value : discoveredSecrets) if (!value.isEmpty() && (url.contains(value) || decoded.contains(value))) return true;
    }
    return false;
  }

  private String displayUrl(String url) { return secretInput(url) ? origin + "/%5BREDACTED%5D" : safe(url); }

  private String candidateDisplay(ScopeInspection.Candidate candidate) {
    String raw = candidate.url();
    if(!candidate.executable()&&raw!=null&&raw.contains("${")&&raw.length()<=2048)
      return secretInput(raw)?"/%5BREDACTED%5D":displayText(raw.split("[?#]",2)[0],2048);
    if (!candidate.executable() && Objects.toString(candidate.source(), "").contains("JS") && raw != null && raw.startsWith("/") && !raw.startsWith("//") && raw.length() <= 2048) try {
      URI path = URI.create(raw.replace("{", "%7B").replace("}", "%7D"));
      if (path.getScheme() == null && path.getRawAuthority() == null) {
        if (secretInput(raw)) return "/%5BREDACTED%5D";
        return displayText(Objects.toString(path.getRawPath(), "").replace("%7B", "{").replace("%7D", "}")
            .replaceAll("(?i)(/(?:token|api[_-]?key|secret|password|authorization)/)[^/]+", "$1%5BREDACTED%5D"), 2048);
      }
    } catch (RuntimeException invalid) { }
    return displayUrl(raw);
  }

  private String displayText(String text, int max) {
    String clean = ScopeInspectionAnalysis.display(text, max);
    synchronized (lock) { for (String value : discoveredSecrets) if (!value.isEmpty()) clean = clean.replace(value, "[가림]"); }
    return bounded(clean, max);
  }

  private Set<String> credentialNames(ScopeInspection.Profile profile) {
    Set<String> names = new LinkedHashSet<>(AuthComparePanel.DEFAULT_AUTH);
    names.addAll(AuthComparePanel.CSRF); names.addAll(profile.headers().keySet()); return names;
  }

  private String revisionOf(ScopeInspection.Profile profile) {
    synchronized (lock) {
      int version = profileVersions.getOrDefault(profile.id(), 1);
      return "r" + version + ":" + EnvironmentProject.hash(new TreeMap<>(profile.headers()).toString()).substring(0, 16);
    }
  }

  private boolean allowed(String raw) {
    String url = normalized(raw);
    if (url == null || !origin.equals(EnvironmentCatalog.origin(URI.create(url)))) return false;
    try { return scope.test(url); } catch (RuntimeException error) { return false; }
  }

  static String normalized(String raw) {
    try {
      if (raw == null || raw.isBlank() || raw.length() > 4096 || raw.chars().anyMatch(c -> c <= 32 || c == 127)) return null;
      URI uri = URI.create(raw);
      if (uri.getUserInfo() != null || uri.getHost() == null || uri.getFragment() != null || !Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT))) return null;
      String rawPath = Objects.toString(uri.getRawPath(), "/");
      if (Arrays.stream(rawPath.split("/", -1)).anyMatch(segment -> segment.equals(".") || segment.equals(".."))
          || rawPath.matches("(?i).*(?:%2e|%2f|%5c|%25|%00|;).*")) return null;
      if (WorkbenchScope.canonical(raw) == null || raw.matches("(?is).*(?:\\{[^}]*}|\\$\\{|REPLACE_|/[A-Za-z_]*:[A-Za-z_]).*")) return null;
      String path = uri.getRawPath();
      if (path == null || path.isEmpty()) path = "/";
      return EnvironmentCatalog.origin(uri) + path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    } catch (RuntimeException error) { return null; }
  }

  static boolean readOnlyUrl(String url) {
    if (url == null || EnvironmentKnowledge.manualOnly(url)) return false;
    try {
      URI uri = URI.create(url);
      String decoded = java.net.URLDecoder.decode(uri.getRawPath(), java.nio.charset.StandardCharsets.UTF_8);
      if (decoded.matches("(?i).*/(?:logout|logoff|signout|delete|remove|shutdown|restart|reset|reboot|destroy|drop|terminate|unsubscribe|purchase|checkout|payment|confirm|activate|deactivate)[A-Za-z0-9_./;-]*$")) return false;
      String query = uri.getRawQuery() == null ? null : java.net.URLDecoder.decode(uri.getRawQuery(), java.nio.charset.StandardCharsets.UTF_8);
      return query == null || !query.matches("(?i).*(?:^|&)(?:action|cmd|do|operation)=(?:delete|remove|drop|logout|shutdown|restart|reset|destroy|purchase|payment)(?:&.*)?$");
    } catch (RuntimeException error) { return false; }
  }

  static String safe(String url) {
    String value = ScopeInspectionAnalysis.safeUrl(url);
    return value.isEmpty() ? "URL 확인 필요" : value;
  }
  private static String bounded(String text, int max) { return Objects.requireNonNullElse(text, "").replaceAll("[\\r\\n\\t]", " ").substring(0, Math.min(max, Objects.requireNonNullElse(text, "").length())); }
  private static boolean scalar(Object value) { return value instanceof String || value instanceof Number || value instanceof Boolean; }
  private static long contentLength(String headers) {
    String value = EnvironmentResponse.header(headers, "Content-Length");
    try { return value.isBlank() ? -1 : Long.parseLong(value); } catch (NumberFormatException error) { return -1; }
  }
  private static boolean binary(HttpResponse response) {
    if (response.body() == null) return false;
    byte[] prefix = response.body().subArray(0, Math.min(512, response.body().length())).getBytes();
    for (byte value : prefix) if (value == 0) return true;
    return false;
  }
  private static String digest(String headers, String body, String url) {
    var sample = EnvironmentControls.sample(200, headers, body, url, IGNORED_FIELDS);
    return sample.signature().isEmpty() ? "" : EnvironmentProject.hash(EnvironmentContent.normalize(sample.signature()));
  }
  private static int priority(ScopeInspection.Candidate candidate) {
    String source = Objects.toString(candidate.source(), ""), basis = source + " " + Objects.toString(candidate.label(), "");
    if (source.equals("시작 URL")) return 0;
    if (source.equals("소스맵 참조")) return 5;
    if (basis.contains("소스맵 후보")) return 85;
    if (source.contains("관측") || source.contains("브라우저") || source.contains("메뉴") || basis.contains("실제 응답 참조") || basis.contains("실제 링크") || basis.contains("실제 선언")) return 5;
    if (source.contains("API 문서") || basis.contains("OpenAPI GET 선언") || basis.contains("Swagger 명세 참조")) return 10;
    if (source.contains("JS")) return 15;
    if (source.equals("선별 환경 파일")) return 34;
    return basis.contains("공통 탐색") ? 95 : 80;
  }
  private static String severity(String kind) { return Set.of("ENV", "CONFIG_JSON", "GIT", "PHPINFO").contains(kind) ? "MEDIUM" : "INFO"; }
  private ScopeInspection.Finding finding(String category, String title, String url, String method, String account,
                                                  String confidence, String severity, String state, String detail,
                                                  String originalId, String fingerprint) {
    return new ScopeInspection.Finding(category, title, displayUrl(url), method, account, confidence, severity, state, detail, originalId, 0, -1, EnvironmentProject.hash(fingerprint));
  }
  /** The first checked path prefix is a stable per-run soft404 control root. */
  private String scopeRoot(String raw) {
    if (allowed(origin + "/")) return "";
    String path = Objects.toString(URI.create(raw).getRawPath(), "/");
    int end = 1;
    while (end <= path.length()) {
      int slash = path.indexOf('/', end);
      if (slash < 0) slash = path.length();
      String prefix = path.substring(0, slash);
      if (!prefix.isEmpty() && (allowed(origin + prefix) || allowed(origin + prefix + "/"))) return prefix;
      if (slash == path.length()) break;
      end = slash + 1;
    }
    return path.equals("/") ? "" : path.replaceAll("/+$", "");
  }

  private void publish() {
    if (closed) return;
    try { changed.accept(snapshot()); } catch (RuntimeException ignored) { /* UI observer failure cannot replay a request. */ }
  }

  @Override public void close() {
    synchronized (lock) {
      if (closed) return;
      closed = true; generation.incrementAndGet(); state = ScopeInspection.State.CLOSED;
      message = "통합 점검 종료"; finishedAt = System.currentTimeMillis();
      failKatanaPendingLocked(new CancellationException("통합 점검 종료"));
      if (pending != null) pending.cancel(true); executor.shutdownNow(); lock.notifyAll();
      queue.clear(); referencesById.clear(); findings.clear(); pages.clear(); candidates.clear();
      coverage.clear();browserCoverage.clear();suspendedTasks.clear();
      responseCache.clear(); katanaDeferred.clear(); analyzedOriginals.clear(); discoveredSecrets.clear(); discoveredSecretBytes = 0;
      verifiedOriginals.clear(); environmentGuesses.clear(); redirectTargets.clear();
    }
    originals.close();
  }
}
