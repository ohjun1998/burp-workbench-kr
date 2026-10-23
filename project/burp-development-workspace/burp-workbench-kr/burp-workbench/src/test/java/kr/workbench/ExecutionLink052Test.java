package kr.workbench;

import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import java.util.concurrent.atomic.*;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;

/** Operation-scoped policy revisions and immutable execution provenance; no network. */
public final class ExecutionLink052Test extends WorkbenchTest {
  static final String ORIGIN = "https://example.test";
  static AuthComparePanel.Context context(String alice, String bob, String matrix, List<AuthEffectRules.Rule> effects) {
    var base = WorkContextTest.config("Bearer PRIVATE_EXECUTION_TOKEN", "", "1", "$.value");
    var profiles = base.profiles().clone();
    profiles[1] = new AuthComparePanel.Candidate(2, "bob", ORIGIN,
        Map.of("authorization", "Bearer PRIVATE_BOB_TOKEN"), 1, "");
    return new AuthComparePanel.Context(0, ORIGIN, profiles, base.names(), true, "", base.labels(), base.owners(),
        base.rules(), base.policy(), base.responseRules(), base.sessions(), BountyMatrix.parse(matrix),
        base.roles(), base.tenants(), false, new String[]{alice, bob, "", ""}, Set.of(), base.sessionChecks(),
        AuthEffectCheck.Config.disabled(), effects);
  }
  static AuthEffectRules.Rule effect(String name, String path, String expected) {
    return new AuthEffectRules.Rule(name, ORIGIN, new AuthEffectCheck.Config(true, "PATCH", path,
        ORIGIN + path, "$.value", expected, AuthEffectCheck.Mode.EQUALS, 1));
  }
  static String account(AssessmentTracker tracker, String name) {
    return tracker.register(new AssessmentTracker.AccountInput(name, ORIGIN, "reviewer", "", false));
  }
  static void begin(AssessmentTracker tracker, String id, String path, AuthComparePanel.Context context) {
    tracker.beginAuthExecution(id, "capture-store:" + id, request(ORIGIN + path, "GET", true, List.of()), context);
  }
  static void completed(AssessmentTracker tracker, String account, String path) {
    tracker.completeReviewed(tracker.completionReview(account, "GET", ORIGIN + path, List.of()), List.of());
  }
  public static void main(String[] args) throws Exception {
    AssessmentTracker tracker = new AssessmentTracker();
    String alice = account(tracker, "Alice"), bob = account(tracker, "Bob");
    String matrix = "GET /a DENY ALLOW UNKNOWN UNKNOWN\nGET /b ALLOW DENY UNKNOWN UNKNOWN";
    var initial = context(alice, bob, matrix, List.of());
    String taskA = tracker.createTask("Alice /a", "", Set.of(alice), false);
    String taskB = tracker.createTask("Bob /a", "", Set.of(bob), false);
    String unrelated = tracker.createTask("Alice /b", "", Set.of(alice), false);
    tracker.addTarget(taskA, "GET", ORIGIN + "/a"); tracker.addTarget(taskB, "GET", ORIGIN + "/a");
    tracker.addTarget(unrelated, "GET", ORIGIN + "/b");
    begin(tracker, "run-a", "/a", initial);
    check(tracker.execution("run-a").bindings().stream().anyMatch(b -> b.account().equals(alice) && b.tasks().contains(taskA)),
        "run links immutable original, operation, account and task IDs");
    rejects(() -> completed(tracker, alice, "/a"), "running execution cannot support completion");
    tracker.finishAuthExecution("run-a", ExecutionLedger.State.COMPLETED, "done");
    begin(tracker, "run-b", "/b", initial); tracker.finishAuthExecution("run-b", ExecutionLedger.State.COMPLETED, "done");
    completed(tracker, alice, "/a"); completed(tracker, bob, "/a"); completed(tracker, alice, "/b");
    tracker.completeTaskReviewed(tracker.taskCompletionReview(taskA));
    tracker.completeTaskReviewed(tracker.taskCompletionReview(taskB));
    tracker.completeTaskReviewed(tracker.taskCompletionReview(unrelated));
    var historical = tracker.execution("run-a");
    var changed = context(alice, bob, matrix.replace("GET /a DENY ALLOW", "GET /a ALLOW ALLOW"), List.of());
    tracker.authConfiguration(changed);
    check(tracker.view(alice, "GET", ORIGIN + "/a", List.of()).state().equals("재검토 필요"),
        "changed effective permission reopens the affected account and API");
    check(tracker.view(bob, "GET", ORIGIN + "/a", List.of()).state().equals("점검 완료")
        && tracker.view(alice, "GET", ORIGIN + "/b", List.of()).state().equals("점검 완료"),
        "unrelated account and operation retain their reviewed completion");
    check(tracker.tasks().stream().filter(t -> t.id().equals(taskA)).findFirst().orElseThrow().state().equals("재검토 필요")
        && tracker.tasks().stream().filter(t -> t.id().equals(taskB) || t.id().equals(unrelated)).allMatch(t -> t.state().equals("업무 완료")),
        "only dependent task becomes stale after a permission edit");
    check(tracker.execution("run-a").equals(historical), "policy edit never rewrites historical execution conditions");
    rejects(() -> completed(tracker, alice, "/a"), "old revision cannot be manually promoted to current completion");
    begin(tracker, "run-a2", "/a", changed); tracker.finishAuthExecution("run-a2", ExecutionLedger.State.COMPLETED, "done");
    completed(tracker, alice, "/a");
    check(tracker.view(alice, "GET", ORIGIN + "/a", List.of()).state().equals("점검 완료"),
        "new completed execution under current conditions permits explicit review");
    var review = tracker.completionReview(bob, "GET", ORIGIN + "/a", List.of());
    begin(tracker, "run-a3", "/a", changed);
    rejects(() -> tracker.completeReviewed(review, List.of()), "execution started during a completion dialog invalidates its stamp");
    tracker.finishAuthExecution("run-a3", ExecutionLedger.State.CANCELLED, "PRIVATE_ERROR_TOKEN");
    tracker.finishAuthExecution("run-a3", ExecutionLedger.State.COMPLETED, "late completion");
    check(tracker.execution("run-a3").state() == ExecutionLedger.State.CANCELLED, "late callback cannot convert cancellation to completion");
    rejects(() -> completed(tracker, bob, "/a"), "cancelled execution cannot support completion");
    begin(tracker, "failed", "/b", changed); tracker.finishAuthExecution("failed", ExecutionLedger.State.FAILED, "PRIVATE_ERROR_TOKEN");
    rejects(() -> completed(tracker, alice, "/b"), "failed execution cannot support completion");
    String hash = "a".repeat(64); tracker.executionEvidenceSaved("run-a", hash);
    check(tracker.execution("run-a").evidence().contains(hash)
        && tracker.execution("run-a").bindings().equals(historical.bindings()), "evidence attaches to exact past run without changing its policy revision");
    String saved = MetadataExport.json(tracker.save());
    check(!saved.contains("PRIVATE_EXECUTION_TOKEN") && !saved.contains("PRIVATE_BOB_TOKEN")
        && !saved.contains("PRIVATE_ERROR_TOKEN"), "persisted provenance excludes credentials and raw transport exceptions");
    var restored = new AssessmentTracker(); restored.load(MinerJson.parse(saved, 32 * 1024 * 1024, 3000000));
    check(restored.execution("run-a").originalId().equals("capture-store:run-a")
        && restored.execution("run-a").evidence().contains(hash), "project roundtrip preserves original, execution, revision and evidence links");
    check(!restored.executions.current(restored.execution("run-a2"), alice), "imported conditions require live account and configuration reconnection");
    restored.authConfiguration(changed);
    check(restored.executions.current(restored.execution("run-a2"), alice), "explicit current configuration confirms matching imported revision");

    var twoEffects = context(alice, bob, "", List.of(effect("A", "/write-a", "1"), effect("B", "/write-b", "1")));
    var editedEffect = context(alice, bob, "", List.of(effect("A", "/write-a", "2"), effect("B", "/write-b", "1")));
    check(!ExecutionLedger.fingerprint(twoEffects, 0, "PATCH", ORIGIN + "/write-a")
        .equals(ExecutionLedger.fingerprint(editedEffect, 0, "PATCH", ORIGIN + "/write-a")), "changed matching write rule changes operation revision");
    check(ExecutionLedger.fingerprint(twoEffects, 0, "PATCH", ORIGIN + "/write-b")
        .equals(ExecutionLedger.fingerprint(editedEffect, 0, "PATCH", ORIGIN + "/write-b")), "unrelated write rule leaves other operation revision intact");
    check(!WorkContext.authFingerprint(twoEffects).equals(WorkContext.authFingerprint(editedEffect)),
        "shared work context tracks changes to named write rules");
    begin(tracker, "in-flight", "/b", changed);
    var interrupted = new AssessmentTracker(); interrupted.load(MinerJson.parse(MetadataExport.json(tracker.save()), 32 * 1024 * 1024, 3000000));
    check(interrupted.execution("in-flight").state() == ExecutionLedger.State.CANCELLED,
        "restoring a project never resurrects an in-flight request");
    tracker.invalidateAuthOrigin(ORIGIN + ":443", "설정 묶음 적용 · 계정 재연결 필요");
    check(!tracker.executions.current(tracker.execution("run-a2"), alice), "bundle replacement invalidates existing origin conditions");
    evidenceZip();
    System.out.println("ExecutionLink052Test: " + checks + " checks passed");
  }
  static void evidenceZip() throws Exception {
    BountyFeaturesTest.factory();
    AtomicInteger sends = new AtomicInteger();
    var http = proxy(Http.class, (p, m, a) -> { if (m.getName().equals("sendRequest")) sends.incrementAndGet(); return def(m); });
    var api = proxy(MontoyaApi.class, (p, m, a) -> m.getName().equals("http") ? http : def(m));
    AtomicReference<ActivityPanel> activityRef = new AtomicReference<>();
    AtomicReference<AuthComparePanel> authRef = new AtomicReference<>();
    AtomicReference<BountySupportPanel> supportRef = new AtomicReference<>();
    AtomicReference<AuthComparePanel.Run> runRef = new AtomicReference<>();
    edt(() -> {
      var activity = new ActivityPanel(api); activityRef.set(activity);
      var auth = new AuthComparePanel(api, List::of); authRef.set(auth);
      var support = new BountySupportPanel(api, List::of, auth, activity); supportRef.set(support);
      var tracker = activity.tracker.assessments;
      String alice = account(tracker, "Alice"), bob = account(tracker, "Bob");
      var config = context(alice, bob, "GET /evidence ALLOW DENY UNKNOWN UNKNOWN", List.of());
      var original = BountyFeaturesTest.rq(ORIGIN + "/evidence", "GET", "", List.of(header("Authorization", "Bearer PRIVATE_EXECUTION_TOKEN")));
      var reply = response(200, "{\"value\":1}");
      var run = new AuthComparePanel.Run(52, original, reply, config); runRef.set(run);
      run.packets[0].request = original; run.packets[0].response = reply;
      run.packets[0].result(new AuthFinding(AuthFinding.Kind.BLOCKED, "설정 기대와 일치", "fixture"));
      auth.runs.add(run); auth.historyModel.addRow(new Object[]{52, "GET", original.url(), "완료", "", "", "", "", ""});
      auth.history.setRowSelectionInterval(0, 0);
      tracker.beginAuthExecution(run.executionId, "traffic-store:52", original, config);
      rejects(support::captureAuth, "running auth comparison cannot be frozen as completed evidence");
      check(support.evidence.isEmpty(), "running evidence rejection is atomic");
      tracker.finishAuthExecution(run.executionId, ExecutionLedger.State.COMPLETED, "fixture");
      run.note = "완료 · 판정 확인 필요";
      support.captureAuth();
      check(support.evidence.size() == 2 && support.evidenceExecutions.size() == 2,
          "actual comparison evidence capture records exact item-to-execution links");
    });
    var support = supportRef.get(); var activity = activityRef.get(); var run = runRef.get();
    Path zip = Files.createTempFile("execution-links-052-", ".zip");
    try {
      var snapshot = List.copyOf(support.evidence);
      support.writeEvidence(zip, "실행 증거", "검토 필요", "절차", snapshot, Set.copyOf(support.evidenceHeaders),
          Set.copyOf(support.evidenceSecrets), true, activity.tracker.save(), null, List.of());
      String hash = BountyEvidence.fileHash(zip);
      check(activity.tracker.assessments.execution(run.executionId).evidence().contains(hash),
          "actual ZIP save attaches its SHA-256 to the exact immutable run");
      try (var archive = new ZipFile(zip.toFile())) {
        String coverage = new String(archive.getInputStream(archive.getEntry("coverage.json")).readAllBytes(), StandardCharsets.UTF_8);
        var doc = (Map<?, ?>) MinerJson.parse(coverage, 32 * 1024 * 1024, 3000000);
        var links = (List<?>) doc.get("executionEvidenceItems");
        Set<String> captured = new HashSet<>();
        for (Object value : links) {
          var link = (Map<?, ?>) value;
          check(run.executionId.equals(link.get("executionId")), "ZIP item links point to the selected exact execution");
          captured.add(link.get("evidenceItemId").toString());
        }
        check(captured.equals(snapshot.stream().map(BountyEvidence.Item::evidenceId).collect(java.util.stream.Collectors.toSet()))
            && coverage.contains("traffic-store:52") && !coverage.contains("PRIVATE_EXECUTION_TOKEN"),
            "portable coverage ties exact evidence items to original and policy revision without credentials");
      }
      check(sends.get() == 0, "execution evidence capture and ZIP export send no requests");
    } finally {
      support.close(); authRef.get().close(); activity.close(); Files.deleteIfExists(zip); edt(() -> {});
    }
  }

}
