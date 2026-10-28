package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Persistence;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Durable masked history, exact condition binding, partial-run negatives and recovery. */
public final class ScopeInspectionHistoryTest extends WorkbenchTest {
  static final String PROJECT = "11111111-1111-4111-8111-111111111111";
  static final String OTHER_PROJECT = "22222222-2222-4222-8222-222222222222";
  static final String AUTH = "PRIVATE_AUTH_SESSION_AbCdEf123456789";
  static ScopeInspection.Options options(String auth) {
    return new ScopeInspection.Options("https://history.test/app?token=PRIVATE_URL_TOKEN", 30, 1, 0, 2000, 1024 * 1024, 2,
        false, true, List.of(new ScopeInspection.Profile("role-admin", "관리자", Map.of("Authorization", auth), null)));
  }
  static ScopeInspection.Finding finding(String fingerprint) {
    return new ScopeInspection.Finding("SECRET", "키 후보", "https://history.test/api/data?key=PRIVATE_URL_TOKEN", "GET", "관리자", "형식 후보", "중간", "유효성 미검증",
        "Authorization: " + AUTH + "\nBODY_PRIVATE_VALUE", "session-original-1", 4, 20, fingerprint);
  }
  static ScopeInspection.Snapshot snapshot(String id, String fingerprint, int status, boolean complete) {
    return new ScopeInspection.Snapshot(id, complete ? ScopeInspection.State.COMPLETED : ScopeInspection.State.STOPPED,
        "raw status must not be persisted " + AUTH, 4, 2, complete ? 0 : 2, 0, 0, complete ? 0 : 1,
        List.of(finding(fingerprint)), List.of(new ScopeInspection.Page("https://history.test/api/data?key=PRIVATE_URL_TOKEN", "GET", "관리자", status,
            status == 200 ? "JSON" : "DENIED", complete, "RAW_BODY_PRIVATE", fingerprint, null)), List.of(), 10, 20);
  }
  static void fails(Io action, String message) throws Exception { try { action.run(); throw new AssertionError(message); } catch (java.io.IOException expected) { check(true, message); } }
  interface Io { void run() throws Exception; }
  public static void main(String[] args) throws Exception {
    Path directory = Files.createTempDirectory("scope-history-test-");
    AtomicReference<String> scope = new AtomicReference<>("include=https://history.test/app;exclude=/app/private");
    var store = new ScopeInspectionHistory(directory, PROJECT, scope::get);
    var options = options(AUTH); var first = snapshot("run-first", "a".repeat(64), 200, true);
    Path staleDirectory = directory.resolve("stale-scope");
    var staleScopeStore = new ScopeInspectionHistory(staleDirectory, PROJECT, scope::get);
    String capturedScope = staleScopeStore.scopeFingerprint();
    scope.set("include=https://history.test/app;exclude=/app/changed-before-save");
    fails(() -> staleScopeStore.save(options, first, PROJECT, capturedScope), "a captured old Scope cannot save an old snapshot under current Scope conditions");
    check(!Files.exists(staleDirectory), "stale Scope rejection creates no history, lock or temporary files");
    staleScopeStore.save(options, snapshot("new-scope-baseline", "a".repeat(64), 200, true));
    var changedScopeRun = snapshot("new-scope-comparison", "b".repeat(64), 200, true);
    check(!staleScopeStore.changes(options, changedScopeRun).isEmpty(), "current Scope fixture has actual comparable historical changes");
    check(staleScopeStore.changes(options, changedScopeRun, PROJECT, capturedScope).isEmpty(), "a stale captured Scope cannot compare an old run against current Scope history");
    scope.set("include=https://history.test/app;exclude=/app/private");
    store.save(options, first);
    String raw = Files.readString(directory.resolve("runs-v1.json"));
    for (String privateValue : List.of(AUTH, "BODY_PRIVATE_VALUE", "PRIVATE_URL_TOKEN", "RAW_BODY_PRIVATE", "session-original-1", "관리자"))
      check(!raw.contains(privateValue), "history excludes credentials/body/identity/source detail: " + privateValue.substring(0, Math.min(6, privateValue.length())));
    check(store.list().size() == 1 && store.list().get(0).complete(), "complete metadata history survives readback");
    check(store.list().get(0).findings().get(0).originalId().isEmpty(), "past metadata never pretends to retain a live original packet");
    check(store.list().get(0).findings().get(0).url().equals("https://history.test/api/data"), "history URL has no query values");
    check(store.changes(options, first).isEmpty(), "the same run is not compared with itself");
    var second = snapshot("run-second", "b".repeat(64), 403, true);
    var changes = store.changes(options, second);
    check(changes.stream().anyMatch(f -> f.title().equals("발견 내용 변경")), "new key fingerprint produces a changed finding");
    check(changes.stream().anyMatch(f -> f.title().equals("응답 상태 변경")), "status changes remain explicit");
    check(changes.stream().allMatch(f -> !f.account().startsWith("account-") || f.account().isBlank()), "current comparison restores the current readable account label");
    check(store.changes(options("ROTATED_PRIVATE_AUTH"), second).isEmpty(), "changed authentication revision cannot reuse prior comparison");
    scope.set("include=https://history.test/app;exclude=/app/new-private");
    check(store.changes(options, second).isEmpty(), "changed included/excluded scope conditions cannot mix historical results");
    scope.set("include=https://history.test/app;exclude=/app/private");
    var katanaStore=new ScopeInspectionHistory(directory.resolve("katana-conditions"),PROJECT,scope::get);
    var katanaOptions=new ScopeInspection.Options(options.url(),options.budget(),options.depth(),options.delayMillis(),options.timeoutMillis(),options.maxBodyBytes(),options.maxRedirects(),options.browser(),options.compareAccounts(),options.profiles(),true,60,100);
    katanaStore.save(katanaOptions,first);
    check(katanaStore.changes(katanaOptions,second).stream().anyMatch(f->f.title().equals("발견 내용 변경")),"same Katana configuration retains masked history comparison");
    check(katanaStore.changes(options,second).isEmpty(),"ordinary inspection cannot claim a change against a Katana crawl baseline");
    var shorterCrawl=new ScopeInspection.Options(options.url(),options.budget(),options.depth(),options.delayMillis(),options.timeoutMillis(),options.maxBodyBytes(),options.maxRedirects(),options.browser(),options.compareAccounts(),options.profiles(),true,30,100);
    check(katanaStore.changes(shorterCrawl,second).isEmpty(),"different Katana time coverage does not reuse a broader crawl baseline");
    var fewerPages=new ScopeInspection.Options(options.url(),options.budget(),options.depth(),options.delayMillis(),options.timeoutMillis(),options.maxBodyBytes(),options.maxRedirects(),options.browser(),options.compareAccounts(),options.profiles(),true,60,20);
    check(katanaStore.changes(fewerPages,second).isEmpty(),"different Katana page coverage does not reuse a broader crawl baseline");
    var anonymous=new ScopeInspection.Profile("anonymous","비로그인",Map.of(),null);
    var authenticatedFirst=new ScopeInspection.Options(options.url(),options.budget(),options.depth(),options.delayMillis(),options.timeoutMillis(),options.maxBodyBytes(),options.maxRedirects(),options.browser(),true,List.of(options.profiles().get(0),anonymous),true,60,100);
    var anonymousFirst=new ScopeInspection.Options(options.url(),options.budget(),options.depth(),options.delayMillis(),options.timeoutMillis(),options.maxBodyBytes(),options.maxRedirects(),options.browser(),true,List.of(anonymous,options.profiles().get(0)),true,60,100);
    var seedAccounts=new ScopeInspectionHistory(directory.resolve("katana-seed-accounts"),PROJECT,scope::get);seedAccounts.save(authenticatedFirst,first);
    check(seedAccounts.changes(anonymousFirst,second).isEmpty(),"same comparison accounts with a different Katana discovery identity cannot share a history baseline");
    check(seedAccounts.changes(authenticatedFirst,second).stream().anyMatch(f->f.title().equals("발견 내용 변경")),"the same declared Katana discovery identity can compare repeated observations");
    var partial = snapshot("run-partial", "b".repeat(64), 403, false);
    check(store.changes(options, partial).stream().anyMatch(f -> f.title().equals("차이 비교 범위 제한")), "partial runs show a comparison-range limitation");
    check(store.changes(options, partial).stream().noneMatch(f -> f.title().contains("재관측 없음")), "partial runs never claim an old finding disappeared");
    var noFinding = new ScopeInspection.Snapshot("run-absent", ScopeInspection.State.COMPLETED, "", 4, 2, 0, 0, 0, 0,
        List.of(), first.pages(), List.of(), 10, 20);
    check(store.changes(options, noFinding).stream().anyMatch(f -> f.title().equals("이번 응답에서 재관측 없음") && f.detail().contains("확정 아님")), "complete exact-page reinspection may record absence without declaring remediation");
    var unseen = new ScopeInspection.Snapshot("run-unseen", ScopeInspection.State.COMPLETED, "", 4, 2, 0, 0, 0, 0,
        List.of(), List.of(), List.of(), 10, 20);
    check(store.changes(options, unseen).stream().noneMatch(f -> f.title().contains("재관측 없음")), "an unvisited old URL cannot be declared resolved");
    var revisedPage = new ScopeInspection.Page("https://history.test/api/data", "GET", "관리자", 200, "JSON", true, "", "b".repeat(64), null, "r2-new-auth");
    var revised = new ScopeInspection.Snapshot("run-revised", ScopeInspection.State.COMPLETED, "", 4, 2, 0, 0, 0, 0,
        List.of(finding("b".repeat(64))), List.of(revisedPage), List.of(), 10, 20);
    check(store.changes(options, revised).isEmpty(), "a new credential revision cannot match an old run despite identical initial options");
    var mixed = new ScopeInspection.Snapshot("run-mixed", ScopeInspection.State.COMPLETED, "", 4, 2, 0, 0, 0, 0,
        List.of(finding("b".repeat(64))), List.of(first.pages().get(0), revisedPage), List.of(), 10, 20);
    check(store.changes(options, mixed).stream().anyMatch(f -> f.title().contains("revision 혼합")), "mixed credential revisions explicitly hold historical absence comparisons");
    store.save(options, mixed);
    check(store.list().stream().anyMatch(run -> run.id().equals("run-mixed") && !run.complete()), "mixed credential run stays incomplete in durable history");
    store.save(options, second);
    check(store.list().size() == 3 && store.list().get(0).id().equals("run-second"), "newest history appears first");
    store.save(options, second);
    check(store.list().size() == 3, "saving the same execution is idempotent");
    var recreated = new ScopeInspectionHistory(directory, PROJECT, scope::get);
    check(recreated.list().size() == 3 && recreated.changes(options, snapshot("run-third", "c".repeat(64), 200, true)).stream().anyMatch(f -> f.title().equals("발견 내용 변경")), "restarted store preserves comparison fingerprints without raw values");
    try (FileChannel lockChannel = FileChannel.open(directory.resolve("runs.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = lockChannel.lock()) {
      String before = Files.readString(directory.resolve("runs-v1.json"));
      fails(() -> store.save(options, snapshot("locked", "c".repeat(64), 200, true)), "concurrent writer is rejected without overwrite");
      check(Files.readString(directory.resolve("runs-v1.json")).equals(before), "lock conflict preserves committed bytes");
    }
    Files.writeString(directory.resolve("runs-v1.json"), "{damaged");
    check(!recreated.list().isEmpty() && recreated.problem().contains("복구"), "damaged primary restores its valid prior metadata backup");
    recreated.save(options, snapshot("recovered", "c".repeat(64), 200, true));
    check(recreated.list().stream().anyMatch(run -> run.id().equals("recovered")), "saving recovered history repairs primary atomically");
    String future = "{\"schema\":2,\"project\":\"" + PROJECT + "\",\"runs\":[]}";
    Files.writeString(directory.resolve("runs-v1.json"), future);
    fails(() -> recreated.save(options, snapshot("future", "d".repeat(64), 200, true)), "future history schema is preserved rather than rolled back");
    check(Files.readString(directory.resolve("runs-v1.json")).equals(future), "future schema bytes are unchanged");
    var other = new ScopeInspectionHistory(directory, OTHER_PROJECT, scope::get);
    check(other.list().isEmpty(), "a file from another project is not listed as this project's history");
    fails(() -> other.save(options, snapshot("other", "d".repeat(64), 200, true)), "another project's file cannot be overwritten");

    Map<String, String> data = new HashMap<>(); data.put(BuildInfo.storageKey("auth-setup-library-project-v1"), OTHER_PROJECT);
    var persisted = proxy(PersistedObject.class, (p,m,a) -> switch(m.getName()) { case "getString" -> data.get(a[0]); case "setString" -> { data.put(a[0].toString(), a[1].toString()); yield null; } default -> def(m); });
    var persistence = proxy(Persistence.class, (p,m,a) -> m.getName().equals("extensionData") ? persisted : def(m));
    var api = proxy(MontoyaApi.class, (p,m,a) -> m.getName().equals("persistence") ? persistence : def(m));
    var switched = new ScopeInspectionHistory(api);
    fails(() -> switched.save(options, first, PROJECT), "an old async run cannot save into a newly active project");
    check(switched.changes(options, second, PROJECT).isEmpty(), "an old async comparison cannot use a new project's history");
    try (var paths = Files.walk(directory)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    System.out.println("ScopeInspectionHistoryTest: " + checks + " checks passed (isolated masked disk metadata)");
  }
}
