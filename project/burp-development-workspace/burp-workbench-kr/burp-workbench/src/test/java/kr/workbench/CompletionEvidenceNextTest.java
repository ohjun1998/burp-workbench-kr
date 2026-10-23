package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;

/** Manual review races, stale completion and full four-account evidence; no target traffic. */
public final class CompletionEvidenceNextTest extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    completion();
    uiAndEvidence();
    System.out.println("CompletionEvidenceNextTest: " + checks + " checks passed");
  }

  static void completion() {
    AssessmentTracker tracker = new AssessmentTracker();
    String alice = tracker.register(AccountTasksTest.account("Alice"));
    String bob = tracker.register(AccountTasksTest.account("Bob"));
    String url = "https://auth.test/items";
    tracker.observed(alice, "GET", url, Set.of("URL:id"), "fixture");
    var review = tracker.completionReview(alice, "GET", url, Set.of("URL:id"));
    check(review.summary().contains("검사 시도: 0") && review.summary().contains("미완료 입력: 1")
        && !tracker.view(alice, "GET", url, List.of()).state().equals("점검 완료"),
        "completion preview describes gaps without completing work");
    tracker.observed(bob, "GET", url, List.of(), "unrelated account");
    tracker.observed(alice, "GET", url, Set.of("URL:id"), "ordinary capture");
    tracker.completeReviewed(review, Set.of("URL:id"));
    check(tracker.view(alice, "GET", url, List.of()).state().equals("점검 완료"),
        "unchanged background observations do not cancel an explicit review");
    String task = tracker.createTask("reviewed task", "", Set.of(alice), false);
    tracker.addTarget(task, "GET", url);
    tracker.completeTaskReviewed(tracker.taskCompletionReview(task));
    tracker.inspected(alice, "GET", url, List.of(), "차단 근거 확인", false);
    check(tracker.view(alice, "GET", url, List.of()).review().contains("완료 후 새 검사")
        && tracker.tasks().get(0).state().equals("재검토 필요"),
        "new non-suspicious inspection reopens completed API and task for manual review");
    var stale = tracker.completionReview(alice, "GET", url, List.of());
    tracker.inspected(alice, "GET", url, List.of(), "차단 근거 확인", false);
    rejects(() -> tracker.completeReviewed(stale, List.of()),
        "same result from a new inspection invalidates the review snapshot");
    tracker.completeReviewed(tracker.completionReview(alice, "GET", url, List.of()), List.of());
    tracker.completeTaskReviewed(tracker.taskCompletionReview(task));
    tracker.bind(List.of(AccountTasksTest.binding(alice, "SID=PRIVATE_NEXT")));
    check(tracker.view(alice, "GET", url, List.of()).review().contains("인증 연결 추가")
        && tracker.tasks().get(0).state().equals("재검토 필요"),
        "new identity binding invalidates completed API and dependent task");
    tracker.completeReviewed(tracker.completionReview(alice, "GET", url, List.of()), List.of());
    tracker.bind(List.of(AccountTasksTest.binding(alice, "SID=PRIVATE_NEXT")));
    check(tracker.view(alice, "GET", url, List.of()).state().equals("점검 완료"),
        "reusing the same identity binding does not reopen work");
    tracker.unbind(alice);
    check(tracker.view(alice, "GET", url, List.of()).review().contains("인증 연결 해제"),
        "identity unlink reopens prior completion");
    tracker.completeReviewed(tracker.completionReview(alice, "GET", url, List.of()), List.of());
    var taskReview = tracker.taskCompletionReview(task);
    tracker.taskRequirements(task, true, false, Set.of());
    rejects(() -> tracker.completeTaskReviewed(taskReview), "changed task requirements reject stale confirmation");
    var inputReview = tracker.completionReview(alice, "GET", url, Set.of("URL:id"));
    rejects(() -> tracker.completeReviewed(inputReview, Set.of("URL:id", "URL:new")),
        "newly discovered input names reject stale completion before state mutation");
    check(!MetadataExport.json(tracker.save()).contains("PRIVATE_NEXT")
        && !MetadataExport.json(tracker.save()).contains("stamp"),
        "completion review neither persists credentials nor its ephemeral stamp");
  }

  static void uiAndEvidence() throws Exception {
    BountyFeaturesTest.factory();
    AtomicInteger sent = new AtomicInteger();
    var http = proxy(Http.class, (p, m, a) -> {
      if (m.getName().equals("sendRequest")) sent.incrementAndGet();
      return def(m);
    });
    var api = proxy(MontoyaApi.class, (p, m, a) -> m.getName().equals("http") ? http : def(m));
    var original = BountyFeaturesTest.rq("https://auth.test/items", "PATCH", "{\"title\":\"after\"}",
        List.of(header("Cookie", "SID=BASE_PRIVATE")));
    var response = response(200, "{\"title\":\"after\"}");
    AtomicReference<BountySupportPanel> supportBox = new AtomicReference<>();
    AtomicReference<ActivityPanel> activityBox = new AtomicReference<>();
    AtomicReference<AuthComparePanel> authBox = new AtomicReference<>();
    edt(() -> {
      var activity = new ActivityPanel(api);
      var auth = new AuthComparePanel(api, () -> List.of(AuthRecordTest.rr(original, response)));
      var support = new BountySupportPanel(api, List::of, auth, activity);
      activityBox.set(activity); authBox.set(auth); supportBox.set(support);
      var ui = activity.assessmentPanel;
      String id = ui.tracker.register(AccountTasksTest.account("Alice"));
      activity.tracker.discover(new ReconMetadata.Discovery(original.url(), "PATCH", Set.of(), Set.of(), "fixture"));
      ui.tracker.observed(id, "PATCH", original.url(), List.of(), "fixture");
      ui.refresh(); ui.selectAccount(id); ui.accountTable.setRowSelectionInterval(0, 0);
      ui.completionReviewer = review -> false;
      ui.finish(true);
      check(!ui.tracker.view(id, "PATCH", original.url(), List.of()).state().equals("점검 완료"),
          "cancelled completion review makes no status change");
      ui.completionReviewer = review -> {
        ui.tracker.inspected(id, "PATCH", original.url(), List.of(), "new result", false);
        return true;
      };
      rejects(() -> ui.finish(true), "UI completion rejects conditions changed while dialog was open");
      ui.completionReviewer = review -> true;
      ui.finish(true);
      check(ui.tracker.view(id, "PATCH", original.url(), List.of()).state().equals("점검 완료"),
          "explicit accepted UI review completes the selected account operation");

      var profile = new AuthComparePanel.Candidate(1, "a".repeat(64), "https://auth.test:443",
          java.util.Map.of("cookie", "SID=ACCOUNT_PRIVATE"), 1, "");
      auth.assign(0, profile);
      auth.anonymous(3);
      auth.matrixRules.setText("PATCH /items ALLOW DENY DENY DENY");
      var run = new AuthComparePanel.Run(7, original, response, auth.context());
      run.note = "완료 · 수동 검토";
      run.currentBaseline = response;
      run.baselineState = AuthComparePanel.Run.Baseline.WRITE;
      var anonymous = BountyFeaturesTest.rq(original.url(), "PATCH", original.bodyToString(), List.of());
      run.packets[3].request = anonymous;
      run.packets[3].response = response;
      run.packets[3].result(new AuthFinding(AuthFinding.Kind.SUSPECT, "기대 DENY · 변경 관측", "$.title : before → after"));
      var read = BountyFeaturesTest.rq(original.url(), "GET", "", List.of());
      var before = response(200, "{\"title\":\"before\"}");
      var config = new AuthEffectCheck.Config(true, "PATCH", "/items", original.url(), "$.title", "\"after\"",
          AuthEffectCheck.Mode.EQUALS, 1);
      run.packets[3].effect = new AuthEffectCheck.Evidence(config,
          new AuthEffectCheck.Snapshot(read, before, true, "before"),
          new AuthEffectCheck.Snapshot(read, response, true, "after"),
          AuthEffectCheck.Outcome.OBSERVED, "기대 상태 관측");
      var planned = AuthEvidenceSnapshot.capture(run);
      check(planned.size() == 5 && planned.stream().anyMatch(p -> p.request() == anonymous),
          "evidence plan includes fourth account, original, refreshed baseline and both write observations");
      check(planned.get(0).note().contains("실행 당시 설정 적용 범위")
          && planned.stream().anyMatch(p -> p.note().contains("권한표 기대: DENY") && p.note().contains("$.title")),
          "evidence links immutable setup, declared policy and actual comparison reasons");
      auth.matrixRules.setText("PATCH /items ALLOW ALLOW ALLOW ALLOW");
      check(AuthEvidenceSnapshot.capture(run).stream().anyMatch(p -> p.note().contains("권한표 기대: DENY")),
          "later policy edits cannot rewrite the captured run policy");
      auth.runs.add(run);
      auth.historyModel.addRow(new Object[]{7, "PATCH", original.url(), run.note, "", "", "", "", ""});
      auth.history.setRowSelectionInterval(0, 0);
      support.captureAuth();
      check(support.evidence.size() == 5 && support.evidence.stream().filter(p -> p.label().contains("쓰기")).count() == 2,
          "actual evidence capture retains before and after packets from fourth account");
      while (support.evidence.size() < 29) support.remember(original, response, "filler", "manual", "");
      int count = support.evidence.size();
      rejects(support::captureAuth, "evidence capacity checked for the complete run before adding packets");
      check(support.evidence.size() == count, "capacity rejection leaves evidence bundle unchanged");
    });
    var support = supportBox.get();
    Path zip = Files.createTempFile("next-completion-evidence-", ".zip");
    try {
      BountyEvidence.save(zip, "Next evidence", "manual", "steps", List.copyOf(support.evidence.subList(0, 5)),
          Set.copyOf(support.evidenceHeaders), Set.copyOf(support.evidenceSecrets), true, "{}", null);
      try (ZipFile archive = new ZipFile(zip.toFile())) {
        String json = new String(archive.getInputStream(archive.getEntry("evidence.json")).readAllBytes(), StandardCharsets.UTF_8);
        String report = new String(archive.getInputStream(archive.getEntry("report.md")).readAllBytes(), StandardCharsets.UTF_8);
        check(json.contains("권한표 기대: DENY") && report.contains("쓰기 후 조회") && report.contains("실행 #7"),
            "portable ZIP retains policy, observation provenance and execution grouping");
        check(!json.contains("BASE_PRIVATE") && !report.contains("ACCOUNT_PRIVATE"),
            "new evidence context keeps existing credential redaction");
      }
    } finally {
      Files.deleteIfExists(zip);
      edt(() -> {support.close(); authBox.get().close(); activityBox.get().close();});
    }
    check(sent.get() == 0, "review and evidence preparation send zero HTTP requests");
  }
}
