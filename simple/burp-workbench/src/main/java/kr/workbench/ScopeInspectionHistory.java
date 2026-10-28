package kr.workbench;

import burp.api.montoya.MontoyaApi;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.function.Supplier;

/** Atomic project-local masked metadata history. Never serializes packets, headers or body values. */
final class ScopeInspectionHistory {
  static final int MAX_RUNS = 20, MAX_FINDINGS = 300, MAX_PAGES = 500, MAX_BYTES = 2 * 1024 * 1024;
  private static final String PROJECT_KEY = BuildInfo.storageKey("auth-setup-library-project-v1");
  record PageSummary(String url, String method, String account, int status, String classification,
                     boolean complete, String digest, String profileRevision) {}
  record Summary(String id, long savedAt, String origin, String scopeFingerprint, String conditionFingerprint,
                 String state, boolean complete, int sent, int checked, int excluded, int failed, int partial,
                 int findingsCount, List<ScopeInspection.Finding> findings, List<PageSummary> pages, String baseConditionFingerprint) {
    Summary { findings = List.copyOf(findings); pages = List.copyOf(pages); }
  }
  private record Context(String project, Path directory, String scope, String condition) {}
  private record Loaded(List<Summary> runs, boolean recovered) {}
  private final MontoyaApi api;
  private final Path fixedDirectory;
  private final String fixedProject;
  private final Supplier<String> fixedScope;
  private String problem = "";

  ScopeInspectionHistory(MontoyaApi api) {
    this.api = api; fixedDirectory = null; fixedProject = null; fixedScope = null;
  }
  /** Isolated disk fixture and embedders can supply an exact, credential-free scope snapshot. */
  ScopeInspectionHistory(Path directory, String project, Supplier<String> scope) {
    this.api = null; fixedDirectory = directory.toAbsolutePath().normalize(); fixedProject = project; fixedScope = scope;
    if (!uuid(project)) throw new IllegalArgumentException("점검 이력 프로젝트 ID 형식 오류");
  }
  synchronized String problem() { return problem; }

  synchronized void save(ScopeInspection.Options options, ScopeInspection.Snapshot snapshot) throws IOException {
    save(options, snapshot, project());
  }
  synchronized void save(ScopeInspection.Options options, ScopeInspection.Snapshot snapshot, String expectedProject) throws IOException {
    save(options, snapshot, expectedProject, scopeFingerprint());
  }
  synchronized void save(ScopeInspection.Options options, ScopeInspection.Snapshot snapshot, String expectedProject, String expectedScopeFingerprint) throws IOException {
    if (snapshot.state() == ScopeInspection.State.READY || snapshot.state() == ScopeInspection.State.RUNNING)
      throw new IOException("진행 중 점검은 완료/중지/일시정지 후 이력에 저장하세요.");
    Context context = context(options);
    if (context == null) throw new IOException(problem);
    if (!context.project().equals(expectedProject)) throw new IOException("프로젝트가 바뀌어 이전 실행의 점검 이력을 저장하지 않습니다.");
    if (!context.scope().equals(expectedScopeFingerprint)) throw new IOException("SCOPE가 바뀌어 이전 실행의 점검 이력을 저장하지 않습니다.");
    requireProject(expectedProject); requireScope(expectedScopeFingerprint);
    Summary next = summary(options, snapshot, context);
    Files.createDirectories(context.directory()); privateMode(context.directory(), "rwx------");
    Path primary = context.directory().resolve("runs-v1.json"), backup = context.directory().resolve("runs-v1.backup.json");
    Path lockFile = context.directory().resolve("runs.lock");
    try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      privateMode(lockFile, "rw-------");
      try (FileLock lock = channel.tryLock()) {
        if (lock == null) throw new IOException("다른 창이 점검 이력을 저장 중입니다. 다시 시도하세요.");
        requireProject(context.project()); requireScope(context.scope());
        Loaded loaded = load(context.directory(), context.project());
        List<Summary> runs = new ArrayList<>(loaded.runs());
        Summary old = runs.stream().filter(run -> run.id().equals(next.id())).findFirst().orElse(null);
        if (old != null && !old.baseConditionFingerprint().equals(next.baseConditionFingerprint()))
          throw new IOException("같은 실행 ID의 점검 조건이 달라 기존 이력을 보존합니다.");
        if (old != null && old.complete() && !next.complete()) return;
        runs.removeIf(run -> run.id().equals(next.id())); runs.add(next);
        while (runs.size() > MAX_RUNS) runs.remove(0);
        while (encode(runs, context.project()).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES && runs.size() > 1) runs.remove(0);
        String encoded = encode(runs, context.project());
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
          throw new IOException("현재 점검 요약이 이력 용량을 초과했습니다. 기존 이력을 보존합니다.");
        if (!loaded.recovered() && Files.exists(primary)) atomic(backup, Files.readString(primary, StandardCharsets.UTF_8));
        requireProject(context.project()); requireScope(context.scope());
        atomic(primary, encoded, () -> { requireProject(expectedProject); requireScope(expectedScopeFingerprint); });
        requireProject(context.project()); requireScope(context.scope());
        problem = loaded.recovered() ? "복구본에서 이력을 복구해 저장했습니다." : "";
      }
    } catch (OverlappingFileLockException error) {
      throw new IOException("다른 창이 점검 이력을 저장 중입니다. 다시 시도하세요.", error);
    }
  }

  synchronized List<Summary> list() {
    String project = project();
    if (!uuid(project)) { problem = "프로젝트별 점검 이력을 사용할 수 없습니다."; return List.of(); }
    try {
      Loaded loaded = load(directory(project), project); requireProject(project);
      problem = loaded.recovered() ? "복구본의 이력을 표시합니다. 기존 파일은 보존했습니다." : "";
      List<Summary> runs = new ArrayList<>(loaded.runs()); Collections.reverse(runs); return List.copyOf(runs);
    } catch (IOException error) { problem = error.getMessage(); return List.of(); }
  }

  synchronized List<ScopeInspection.Finding> changes(ScopeInspection.Options options, ScopeInspection.Snapshot snapshot) {
    return changes(options, snapshot, project());
  }
  synchronized List<ScopeInspection.Finding> changes(ScopeInspection.Options options, ScopeInspection.Snapshot snapshot, String expectedProject) {
    return changes(options, snapshot, expectedProject, scopeFingerprint());
  }
  synchronized List<ScopeInspection.Finding> changes(ScopeInspection.Options options, ScopeInspection.Snapshot snapshot, String expectedProject, String expectedScopeFingerprint) {
    Context context = context(options); if (context == null) return List.of();
    if (!context.project().equals(expectedProject)) { problem = "프로젝트가 바뀌어 이전 실행의 차이 비교를 중지했습니다."; return List.of(); }
    if (!context.scope().equals(expectedScopeFingerprint)) { problem = "SCOPE가 바뀌어 이전 실행의 차이 비교를 중지했습니다."; return List.of(); }
    Summary current = summary(options, snapshot, context);
    if (mixedRevisions(snapshot)) return comparisonContextCurrent(expectedProject, expectedScopeFingerprint) ? List.of(change("인증 revision 혼합 · 차이 비교 보류", current.origin(), "GET", "",
        "같은 계정의 서로 다른 인증 revision으로 수집한 원본이 있어 재관측 부재/해소를 비교하지 않음", "")) : List.of();
    List<Summary> history = list();
    if (!comparisonContextCurrent(expectedProject, expectedScopeFingerprint)) return List.of();
    Summary previous = history.stream().filter(run -> !run.id().equals(snapshot.id())
        && !context.scope().isEmpty() && run.conditionFingerprint().equals(current.conditionFingerprint())).findFirst().orElse(null);
    if (previous == null) return List.of();
    List<ScopeInspection.Finding> changes = new ArrayList<>();
    Map<String, List<ScopeInspection.Finding>> oldFindings = group(previous.findings().stream().filter(ScopeInspectionView::historyRelevant).toList());
    Map<String, List<ScopeInspection.Finding>> newFindings = group(current.findings().stream().filter(ScopeInspectionView::historyRelevant).toList());
    for (var finding : current.findings().stream().filter(ScopeInspectionView::historyRelevant).toList()) {
      List<ScopeInspection.Finding> same = oldFindings.getOrDefault(key(finding), List.of());
      if (same.stream().anyMatch(old -> old.fingerprint().equals(finding.fingerprint()))) continue;
      String title = same.isEmpty() ? "새 발견 후보" : "발견 내용 변경";
      changes.add(change(title, finding.url(), finding.method(), finding.account(),
          title + " · " + finding.category() + " / " + finding.title() + " · 이전 실행 " + previous.id(), finding.fingerprint()));
    }
    if (current.complete() && previous.complete()) {
      for (var finding : previous.findings()) if (ScopeInspectionView.historyRelevant(finding) && !newFindings.containsKey(key(finding))
          && current.pages().stream().anyMatch(page -> page.complete() && page.account().equals(finding.account())
              && page.url().equals(finding.url()) && page.method().equals(finding.method())))
        changes.add(change("이번 응답에서 재관측 없음", finding.url(), finding.method(), finding.account(),
            finding.category() + " / " + finding.title() + " · 취약점 해소/부재 확정 아님", finding.fingerprint()));
    }
    Map<String, PageSummary> oldPages = new LinkedHashMap<>();
    for (var page : previous.pages()) oldPages.put(pageKey(page), page);
    for (var page : current.pages()) {
      PageSummary old = oldPages.get(pageKey(page));
      String title = old == null ? "새 경로 응답" : old.status() != page.status() ? "응답 상태 변경"
          : !old.classification().equals(page.classification()) ? "응답 분류 변경"
          : !old.digest().isEmpty() && !page.digest().isEmpty() && !old.digest().equals(page.digest()) ? "응답 내용 변경" : "";
      if (!title.isEmpty()) changes.add(change(title, page.url(), page.method(), page.account(),
          title + (old == null ? "" : " · HTTP " + old.status() + " → " + page.status())
              + (!page.complete() ? " · 현재 응답 부분 검사" : ""), page.digest()));
    }
    if (!current.complete() || !previous.complete()) changes.add(change("차이 비교 범위 제한", current.origin(), "GET", "",
        "현재 또는 이전 실행에 미검사/실패/중지/요약 제한이 있어 재관측 부재·해소 판정을 하지 않음", ""));
    if (!comparisonContextCurrent(expectedProject, expectedScopeFingerprint)) return List.of();
    return changes.subList(0, Math.min(500, changes.size())).stream().map(f -> new ScopeInspection.Finding(
        f.category(), f.title(), f.url(), f.method(), label(f.account(), options), f.confidence(), f.severity(), f.state(),
        f.detail(), f.originalId(), f.line(), f.offset(), f.fingerprint())).toList();
  }
  private boolean comparisonContextCurrent(String expectedProject, String expectedScope) {
    try { requireProject(expectedProject); requireScope(expectedScope); return true; }
    catch (IOException error) { problem = error.getMessage(); return false; }
  }

  private Context context(ScopeInspection.Options options) {
    try {
      String project = project();
      if (!uuid(project)) { problem = "프로젝트 ID를 저장할 수 없어 점검 이력을 사용하지 않습니다."; return null; }
      String scope = scopeFingerprint();
      StringBuilder identity = new StringBuilder();
      options.profiles().stream().sorted(Comparator.comparing(ScopeInspection.Profile::id)).forEach(profile -> {
        identity.append(profile.id()).append('|');
        new TreeMap<>(profile.headers()).forEach((name, value) -> identity.append(name.toLowerCase(Locale.ROOT)).append(':').append(value).append('\n'));
        if (profile.identity() != null) identity.append(profile.identity().url()).append('|').append(profile.identity().identityPath()).append('|').append(profile.identity().expectedIdentity()).append('|').append(profile.identity().claims());
      });
      String condition = hash(project + "|" + scope + "|" + options.url() + "|" + options.depth() + "|" + options.budget()
          + "|" + options.delayMillis() + "|" + options.timeoutMillis() + "|" + options.maxBodyBytes() + "|" + options.maxRedirects() + "|" + options.browser() + "|" + options.compareAccounts() + "|" + identity
          + "|inspection-analysis-schema:2"
          + (options.katana() ? "|katana-1.8.0|" + options.katanaSeconds() + "|" + options.katanaPages() + "|" + options.katanaProfileId() : ""));
      return new Context(project, directory(project), scope, condition);
    } catch (RuntimeException error) { problem = "점검 이력의 프로젝트/범위 조건을 확인하지 못했습니다."; return null; }
  }
  private Summary summary(ScopeInspection.Options options, ScopeInspection.Snapshot snapshot, Context context) {
    List<ScopeInspection.Finding> findings = snapshot.findings().stream().sorted(Comparator.comparingInt(ScopeInspectionView::historyPriority)).limit(MAX_FINDINGS).map(f -> stored(f, options)).toList();
    List<PageSummary> pages = snapshot.pages().stream().limit(MAX_PAGES).map(page -> new PageSummary(
        ScopeInspectionAnalysis.safeUrl(page.url()), checkedMethod(page.method()), account(page.account(), options), page.status(),
        text(page.classification(), 100), page.complete(), digest(page.digest()), text(page.profileRevision(), 120))).toList();
    boolean complete = snapshot.state() == ScopeInspection.State.COMPLETED && snapshot.pending() == 0 && snapshot.failed() == 0 && snapshot.partial() == 0
        && snapshot.findings().size() <= MAX_FINDINGS && snapshot.pages().size() <= MAX_PAGES && pages.stream().allMatch(PageSummary::complete) && !mixedRevisions(snapshot);
    String safe = ScopeInspectionAnalysis.safeUrl(options.url());
    String origin = safe.isEmpty() ? "" : EnvironmentCatalog.origin(java.net.URI.create(safe)) + "/";
    return new Summary(text(snapshot.id(), 160), Math.max(0, snapshot.finishedAt() > 0 ? snapshot.finishedAt() : System.currentTimeMillis()),
        origin, context.scope(), revisionCondition(context.condition(), snapshot), snapshot.state().name(), complete, snapshot.sent(), snapshot.completed(), snapshot.excluded(), snapshot.failed(), snapshot.partial(),
        snapshot.findings().size(), findings, pages, context.condition());
  }
  private static ScopeInspection.Finding stored(ScopeInspection.Finding finding, ScopeInspection.Options options) {
    String title = finding.category().equals("MENU") ? "메뉴 관측" : text(finding.title(), 200);
    return new ScopeInspection.Finding(text(finding.category(), 32), title, ScopeInspectionAnalysis.safeUrl(finding.url()), checkedMethod(finding.method()),
        account(finding.account(), options), text(finding.confidence(), 64), text(finding.severity(), 32), text(finding.state(), 80),
        "과거 가림 요약 · 원본은 현재 세션 또는 저장한 증거 ZIP에서 확인", "", Math.max(0, finding.line()), Math.max(-1, finding.offset()), digest(finding.fingerprint()));
  }
  private static String account(String value, ScopeInspection.Options options) {
    return options.profiles().stream().filter(profile -> profile.id().equals(value) || profile.label().equals(value))
        .map(profile -> "account-" + hash(profile.id()).substring(0, 16)).findFirst().orElse("account-" + hash(Objects.requireNonNullElse(value, "")).substring(0, 16));
  }
  private static String label(String stored, ScopeInspection.Options options) {
    return options.profiles().stream().filter(profile -> stored.equals("account-" + hash(profile.id()).substring(0, 16)))
        .map(profile -> ScopeInspectionAnalysis.display(profile.label(), 160)).findFirst().orElse(stored);
  }
  private String project() {
    if (fixedProject != null) return fixedProject;
    return WorkspaceResume.projectIdentity(api);
  }
  private Path directory(String project) {
    return fixedDirectory != null ? fixedDirectory : Path.of(System.getProperty("user.home"), BuildInfo.STORAGE_DIRECTORY, "scope-inspections", project);
  }
  synchronized String scopeFingerprint() {
    if (fixedScope != null) return hash(Objects.requireNonNullElse(fixedScope.get(), ""));
    String manual = "", nativeScope = "", live = "";
    try { live = WorkbenchScope.current(api).inspectionFingerprint(); } catch (RuntimeException ignored) { }
    try { manual = Objects.requireNonNullElse(api.persistence().extensionData().getString(BuildInfo.storageKey("wb.scope.v1")), ""); }
    catch (RuntimeException ignored) { }
    try { nativeScope = Objects.requireNonNullElse(api.burpSuite().exportProjectOptionsAsJson("target.scope"), ""); }
    catch (RuntimeException ignored) { }
    if (manual.isEmpty() && nativeScope.isEmpty() && live.isEmpty()) return "";
    return hash(manual + "\n" + nativeScope + "\n" + live);
  }
  private void requireScope(String expected) throws IOException {
    if (!expected.equals(scopeFingerprint())) throw new IOException("SCOPE 조건이 변경되어 이전 점검 이력 저장을 중지했습니다.");
  }
  private void requireProject(String expected) throws IOException {
    if (!expected.equals(project())) throw new IOException("프로젝트가 변경되어 이전 점검 이력 저장을 중지했습니다.");
  }

  private static Loaded load(Path directory, String project) throws IOException {
    Path primary = directory.resolve("runs-v1.json"), backup = directory.resolve("runs-v1.backup.json");
    if (!Files.exists(primary) && !Files.exists(backup)) return new Loaded(List.of(), false);
    Exception failed = null;
    if (Files.exists(primary)) try { return new Loaded(read(primary, project), false); }
    catch (IOException | IllegalArgumentException error) { if (future(error)) throw new IOException("더 새로운 점검 이력 형식입니다. 기존 파일을 보존합니다.", error); failed = error; }
    if (Files.exists(backup)) try { return new Loaded(read(backup, project), true); }
    catch (IOException | IllegalArgumentException error) { if (failed != null) error.addSuppressed(failed); throw new IOException("점검 이력과 복구본을 읽을 수 없습니다. 기존 파일을 보존합니다.", error); }
    throw new IOException("점검 이력이 손상되어 저장하지 않습니다. 기존 파일을 보존합니다.", failed);
  }
  private static List<Summary> read(Path file, String project) throws IOException {
    if (Files.size(file) > MAX_BYTES) throw new IOException("점검 이력 용량 한도 초과");
    Object value = MinerJson.parse(Files.readString(file, StandardCharsets.UTF_8), MAX_BYTES, 100000);
    if (!(value instanceof Map<?, ?> root) || !root.keySet().equals(Set.of("schema", "project", "runs")) || number(root.get("schema")) != 1)
      throw new IllegalArgumentException("지원하지 않는 점검 이력 schema");
    if (!project.equals(root.get("project"))) throw new IOException("다른 프로젝트의 점검 이력입니다. 기존 파일을 보존합니다.");
    List<Summary> runs = new ArrayList<>();
    for (Object item : list(root.get("runs"), MAX_RUNS)) {
      List<?> row = list(item, 18); if (row.size() != 18) throw new IllegalArgumentException("점검 이력 행 형식 오류");
      List<ScopeInspection.Finding> findings = new ArrayList<>();
      for (Object f : list(row.get(15), MAX_FINDINGS)) {
        List<?> fields = list(f, 14); if (fields.size() != 14) throw new IllegalArgumentException("발견 요약 형식 오류");
        findings.add(new ScopeInspection.Finding(text(fields.get(0), 32), text(fields.get(1), 200), safeStoredUrl(fields.get(2)), checkedMethod(text(fields.get(3), 16)),
            text(fields.get(4), 100), text(fields.get(5), 64), text(fields.get(6), 32), text(fields.get(7), 80),
            "과거 가림 요약 · 원본은 현재 세션 또는 저장한 증거 ZIP에서 확인", "", integer(fields.get(10)), integer(fields.get(11)), digest(text(fields.get(12), 64))));
        if (!text(fields.get(8), 1000).equals("과거 가림 요약 · 원본은 현재 세션 또는 저장한 증거 ZIP에서 확인") || !text(fields.get(9), 160).isEmpty() || !text(fields.get(13), 8).equals("masked"))
          throw new IllegalArgumentException("점검 이력에 허용되지 않은 원본/본문 정보");
      }
      List<PageSummary> pages = new ArrayList<>();
      for (Object p : list(row.get(16), MAX_PAGES)) {
        List<?> page = list(p, 8); if (page.size() != 8 || !(page.get(5) instanceof Boolean complete)) throw new IllegalArgumentException("경로 요약 형식 오류");
        int status = integer(page.get(3)); if (status < 0 || status > 599) throw new IllegalArgumentException("응답 상태 형식 오류");
        pages.add(new PageSummary(safeStoredUrl(page.get(0)), checkedMethod(text(page.get(1), 16)), text(page.get(2), 100), status, text(page.get(4), 100), complete, digest(text(page.get(6), 64)), text(page.get(7), 120)));
      }
      if (!(row.get(6) instanceof Boolean complete)) throw new IllegalArgumentException("완료 상태 형식 오류");
      String state = text(row.get(5), 32); ScopeInspection.State.valueOf(state);
      Summary summary = new Summary(text(row.get(0), 160), number(row.get(1)), safeStoredUrl(row.get(2)), digest(text(row.get(3), 64)), digest(text(row.get(4), 64)),
          state, complete, integer(row.get(7)), integer(row.get(8)), integer(row.get(9)), integer(row.get(10)), integer(row.get(11)), integer(row.get(12)), findings, pages, digest(text(row.get(17), 64)));
      if (!text(row.get(13), 16).equals("metadata") || integer(row.get(14)) != 1) throw new IllegalArgumentException("점검 이력 메타데이터 형식 오류");
      if (summary.id().isBlank() || summary.savedAt() < 0 || summary.sent() < 0 || summary.checked() < 0 || summary.excluded() < 0 || summary.failed() < 0 || summary.partial() < 0 || summary.findingsCount() < 0)
        throw new IllegalArgumentException("점검 이력 수치 형식 오류");
      runs.add(summary);
    }
    return List.copyOf(runs);
  }
  private static String encode(List<Summary> runs, String project) {
    List<Object> rows = new ArrayList<>();
    for (Summary run : runs) {
      List<Object> fs = new ArrayList<>();
      for (var f : run.findings()) fs.add(List.of(f.category(), f.title(), f.url(), f.method(), f.account(), f.confidence(), f.severity(), f.state(), f.detail(), "", f.line(), f.offset(), f.fingerprint(), "masked"));
      List<Object> ps = new ArrayList<>();
      for (var p : run.pages()) ps.add(List.of(p.url(), p.method(), p.account(), p.status(), p.classification(), p.complete(), p.digest(), p.profileRevision()));
      rows.add(List.of(run.id(), run.savedAt(), run.origin(), run.scopeFingerprint(), run.conditionFingerprint(), run.state(), run.complete(), run.sent(), run.checked(),
          run.excluded(), run.failed(), run.partial(), run.findingsCount(), "metadata", 1, fs, ps, run.baseConditionFingerprint()));
    }
    return MetadataExport.json(Map.of("schema", 1, "project", project, "runs", rows));
  }
  private interface WriteGuard { void check() throws IOException; }
  private static void atomic(Path destination, String value) throws IOException { atomic(destination, value, () -> {}); }
  private static void atomic(Path destination, String value, WriteGuard guard) throws IOException {
    Path temporary = Files.createTempFile(destination.toAbsolutePath().getParent(), ".scope-history-", ".tmp");
    try {
      privateMode(temporary, "rw-------");
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(value); while (bytes.hasRemaining()) channel.write(bytes); channel.force(true);
      }
      guard.check();
      Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      privateMode(destination, "rw-------");
    } finally { Files.deleteIfExists(temporary); }
  }
  private static void privateMode(Path path, String mode) throws IOException {
    try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode)); } catch (UnsupportedOperationException ignored) { }
  }
  private static Map<String, List<ScopeInspection.Finding>> group(List<ScopeInspection.Finding> findings) {
    Map<String, List<ScopeInspection.Finding>> grouped = new LinkedHashMap<>();
    for (var finding : findings) grouped.computeIfAbsent(key(finding), ignored -> new ArrayList<>()).add(finding);
    return grouped;
  }
  private static String key(ScopeInspection.Finding f) { return f.category() + "|" + f.title() + "|" + f.url() + "|" + f.method() + "|" + f.account(); }
  private static String pageKey(PageSummary p) { return p.account() + "|" + p.profileRevision() + "|" + p.method() + "|" + p.url(); }
  private static String revisionCondition(String base, ScopeInspection.Snapshot snapshot) {
    String revisions = snapshot.pages().stream().map(page -> page.account() + "|" + page.profileRevision()).distinct().sorted().reduce("", (left, right) -> left + "\n" + right);
    return hash(base + "|revisions:" + revisions);
  }
  private static boolean mixedRevisions(ScopeInspection.Snapshot snapshot) {
    Map<String, Set<String>> revisions = new HashMap<>();
    for (var page : snapshot.pages()) revisions.computeIfAbsent(page.account(), ignored -> new HashSet<>()).add(page.profileRevision());
    return revisions.values().stream().anyMatch(values -> values.size() > 1);
  }
  private static ScopeInspection.Finding change(String title, String url, String method, String account, String detail, String fingerprint) {
    return new ScopeInspection.Finding("CHANGE", title, url, method, account, "관측", "정보", "검토 필요", detail, "", 0, -1, digest(fingerprint));
  }
  private static boolean future(Exception error) { return Objects.toString(error.getMessage(), "").contains("schema"); }
  private static String checkedMethod(String value) { if (!Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "?").contains(value)) throw new IllegalArgumentException("메서드 형식 오류"); return value; }
  private static String safeStoredUrl(Object value) { String raw = text(value, 4096), safe = ScopeInspectionAnalysis.safeUrl(raw); if (!safe.equals(raw)) throw new IllegalArgumentException("이력 URL 가림/형식 오류"); return safe; }
  private static String digest(String value) { if (value == null || value.isEmpty()) return ""; return value.matches("[a-f0-9]{16,64}") ? value : hash(value); }
  private static String text(Object value, int limit) { if (!(value instanceof String text) || text.length() > limit || text.indexOf('\0') >= 0) throw new IllegalArgumentException("점검 이력 문자열 형식 오류"); return ScopeInspectionAnalysis.display(text, limit); }
  private static List<?> list(Object value, int max) { if (!(value instanceof List<?> list) || list.size() > max) throw new IllegalArgumentException("점검 이력 목록 한도/형식 오류"); return list; }
  private static int integer(Object value) { long n = number(value); if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) throw new IllegalArgumentException("점검 이력 숫자 범위 오류"); return (int)n; }
  private static long number(Object value) { if (!(value instanceof Number number)) throw new IllegalArgumentException("점검 이력 숫자 형식 오류"); try { return new java.math.BigDecimal(number.toString()).longValueExact(); } catch (ArithmeticException error) { throw new IllegalArgumentException("점검 이력 숫자 형식 오류"); } }
  private static boolean uuid(String value) { return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); }
  private static String hash(String value) { return ScopeInspectionAnalysis.hash(value); }
}
