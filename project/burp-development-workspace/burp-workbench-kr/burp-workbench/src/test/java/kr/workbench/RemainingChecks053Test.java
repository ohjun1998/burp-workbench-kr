package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Read-only next-step projection, exact provenance navigation and affected-task invalidation. */
public final class RemainingChecks053Test extends WorkbenchTest {
  static final String ORIGIN = ExecutionLink052Test.ORIGIN;
  static AssessmentTracker.RemainingCheck item(AssessmentTracker tracker, String task, String account, String path) {
    return tracker.remainingChecks(task).stream().filter(row -> row.account().equals(account)
        && row.url().equals(ORIGIN + path)).findFirst().orElseThrow();
  }
  static boolean has(AssessmentTracker tracker, String task, String account, String path, AssessmentTracker.RemainingReason reason) {
    return tracker.remainingChecks(task).stream().anyMatch(row -> row.account().equals(account)
        && row.url().equals(ORIGIN + path) && row.reasons().contains(reason));
  }
  static String task(AssessmentTracker tracker, String name, String account, String path) {
    String id = tracker.createTask(name, "", Set.of(account), false);
    tracker.addTarget(id, "GET", ORIGIN + path);
    return id;
  }
  public static void main(String[] args) throws Exception {
    BountyFeaturesTest.factory();
    projection(); evidence(); exactOriginals(); ui();
    System.out.println("RemainingChecks053Test: " + checks + " checks passed");
  }
  static void projection() {
    var tracker = new AssessmentTracker();
    String alice = ExecutionLink052Test.account(tracker, "Alice"), bob = ExecutionLink052Test.account(tracker, "Bob");
    String a = task(tracker, "A", alice, "/a"), b = task(tracker, "B", bob, "/a"), unrelated = task(tracker, "Other", alice, "/b");
    long revision = tracker.revision();
    var pending = tracker.remainingChecks(a);
    check(pending.size() == 1 && pending.get(0).reasons().contains(AssessmentTracker.RemainingReason.NOT_INSPECTED),
        "unobserved required account/API is visible as uninspected");
    check(pending.get(0).originalId().isEmpty() && pending.get(0).nextAction().contains("원본 선택"),
        "missing provenance asks for explicit original selection without URL substitution");
    check(tracker.remainingChecks(a) == pending && tracker.revision() == revision && tracker.executionHistory().isEmpty(),
        "repeated projection reuses bounded metadata cache without mutating work or creating requests");
    tracker.complete(alice, "GET", ORIGIN + "/a", List.of(), true);
    check(has(tracker, a, alice, "/a", AssessmentTracker.RemainingReason.NOT_INSPECTED),
        "manual completion cannot hide absence of a recorded inspection");
    String matrix = "GET /a ALLOW DENY UNKNOWN UNKNOWN\nGET /b ALLOW DENY UNKNOWN UNKNOWN";
    var context = ExecutionLink052Test.context(alice, bob, matrix, List.of());
    ExecutionLink052Test.begin(tracker, "run-a", "/a", context);
    check(has(tracker, a, alice, "/a", AssessmentTracker.RemainingReason.RUNNING), "in-flight execution remains actionable");
    tracker.finishAuthExecution("run-a", ExecutionLedger.State.COMPLETED, "done");
    tracker.inspected(alice, "GET", ORIGIN + "/a", List.of(), "판정 근거 부족", true);
    var review = item(tracker, a, alice, "/a");
    check(review.reasons().contains(AssessmentTracker.RemainingReason.RESULT_REVIEW)
        && review.explanation().contains("판정 근거 부족") && review.executionId().equals("run-a")
        && review.originalId().equals("capture-store:run-a"), "insufficient judgment retains concrete reason and exact execution/original IDs");
    tracker.inspected(bob, "GET", ORIGIN + "/a", List.of(), "차단 근거 확인", false);
    ExecutionLink052Test.completed(tracker, alice, "/a"); ExecutionLink052Test.completed(tracker, bob, "/a");
    ExecutionLink052Test.begin(tracker, "run-b", "/b", context);
    tracker.finishAuthExecution("run-b", ExecutionLedger.State.COMPLETED, "done");
    tracker.inspected(alice, "GET", ORIGIN + "/b", List.of(), "차단 근거 확인", false);
    ExecutionLink052Test.completed(tracker, alice, "/b");
    var bobBefore = tracker.remainingChecks(b); var otherBefore = tracker.remainingChecks(unrelated);
    var changed = ExecutionLink052Test.context(alice, bob, matrix.replace("GET /a ALLOW DENY", "GET /a DENY DENY"), List.of());
    tracker.authConfiguration(changed);
    check(has(tracker, a, alice, "/a", AssessmentTracker.RemainingReason.CONDITIONS_CHANGED), "effective policy revision appears as current-condition review");
    check(tracker.remainingChecks(b) == bobBefore && tracker.remainingChecks(unrelated) == otherBefore,
        "unrelated account/API keeps its cached reasons and is not reopened");
    ExecutionLink052Test.begin(tracker, "cancelled", "/a", changed);
    tracker.finishAuthExecution("cancelled", ExecutionLedger.State.CANCELLED, "stopped");
    check(item(tracker, a, alice, "/a").executionId().equals("cancelled")
        && has(tracker, a, alice, "/a", AssessmentTracker.RemainingReason.CANCELLED), "new cancellation supersedes old successful execution guidance");
    ExecutionLink052Test.begin(tracker, "failed", "/b", changed);
    tracker.finishAuthExecution("failed", ExecutionLedger.State.FAILED, "failed");
    check(has(tracker, unrelated, alice, "/b", AssessmentTracker.RemainingReason.FAILED), "failed execution is never presented as completed work");
    ExecutionLink052Test.begin(tracker, "restore-in-flight", "/a", changed);
    var imported = new AssessmentTracker();
    imported.load(MinerJson.parse(MetadataExport.json(tracker.save()), 32 * 1024 * 1024, 3000000));
    check(has(imported, a, alice, "/a", AssessmentTracker.RemainingReason.CONDITIONS_CHANGED)
        && has(imported, a, alice, "/a", AssessmentTracker.RemainingReason.CANCELLED),
        "restored running execution requires account/configuration review and remains cancelled");
    var empty = tracker.createTask("Empty", "", Set.of(alice), false);
    check(tracker.remainingChecks(empty).get(0).action() == AssessmentTracker.RemainingAction.TASK,
        "task without targets explains the missing scope instead of showing an empty completed list");
  }
  static void evidence() throws Exception {
    var tracker = new AssessmentTracker();
    String alice = ExecutionLink052Test.account(tracker, "Alice"), bob = ExecutionLink052Test.account(tracker, "Bob");
    String task = task(tracker, "Evidence", alice, "/a");
    tracker.taskRequirements(task, true, true, Set.of());
    var context = ExecutionLink052Test.context(alice, bob, "GET /a ALLOW DENY UNKNOWN UNKNOWN", List.of());
    ExecutionLink052Test.begin(tracker, "evidence-old", "/a", context);
    tracker.finishAuthExecution("evidence-old", ExecutionLedger.State.COMPLETED, "done");
    tracker.inspected(alice, "GET", ORIGIN + "/a", List.of("URL:id"), "차단 근거 확인", false);
    check(has(tracker, task, alice, "/a", AssessmentTracker.RemainingReason.INPUTS_PENDING), "strict input requirement identifies pending input work");
    Path zip = Files.createTempFile("remaining-evidence-", ".zip");
    try {
      var item = new BountyEvidence.Item("Alice", "GET", ORIGIN + "/a", "GET /a HTTP/1.1\r\n\r\n", "HTTP/1.1 403\r\n\r\n", "manual", alice);
      BountyEvidence.save(zip, "Evidence", "impact", "steps", List.of(item), Set.of(), Set.of(), false, "{}", null, List.of());
      String hash = AssessmentTracker.fileHash(zip);
      tracker.evidenceSaved(hash, zip.getFileName().toString(), List.of(item));
      tracker.executionEvidenceSaved("evidence-old", hash);
      check(has(tracker, task, alice, "/a", AssessmentTracker.RemainingReason.EVIDENCE_UNVERIFIED), "saved ZIP metadata is distinct from a verified local file");
      tracker.attachEvidenceFile(zip);
      check(!has(tracker, task, alice, "/a", AssessmentTracker.RemainingReason.EVIDENCE_UNVERIFIED), "verified exact file clears only its evidence requirement");
      ExecutionLink052Test.begin(tracker, "evidence-new", "/a", context);
      tracker.finishAuthExecution("evidence-new", ExecutionLedger.State.COMPLETED, "done");
      check(has(tracker, task, alice, "/a", AssessmentTracker.RemainingReason.EVIDENCE_MISSING), "older run ZIP cannot silently satisfy newest execution evidence");
      tracker.executionEvidenceSaved("evidence-new", hash);
      Files.writeString(zip, "changed"); tracker.verifyEvidenceFiles();
      check(has(tracker, task, alice, "/a", AssessmentTracker.RemainingReason.EVIDENCE_UNVERIFIED), "changed evidence file reopens verification guidance");
      check(!tracker.tasks().get(0).state().equals("업무 완료"), "projection and file verification never complete the task");
    } finally { Files.deleteIfExists(zip); }
  }
  static void exactOriginals() throws Exception {
    installTrafficPacketFactory();
    try (var store = new DiskTrafficStore(); var otherStore = new DiskTrafficStore()) {
      var first = storedRequest("{\"value\":1}");
      var second = storedRequest("{\"value\":2}");
      var response = response(200, "{}");
      registerTrafficPacket(first, response); registerTrafficPacket(second, response);
      var one = store.append(first, response); var two = store.append(second, response);
      otherStore.append(second, response);
      var check = new AssessmentTracker.RemainingCheck("task", "account", "PATCH", ORIGIN + "/write",
          List.of(AssessmentTracker.RemainingReason.CONDITIONS_CHANGED), "review", AssessmentTracker.RemainingAction.PREPARE, "run", one.id());
      check(WorkbenchExtension.assessmentOriginal(store, check).id().equals(one.id())
          && !WorkbenchExtension.assessmentOriginal(store, check).id().equals(two.id()), "same URL and method keep distinct original packet identities");
      rejects(() -> WorkbenchExtension.assessmentOriginal(otherStore, check), "another project with matching sequence/URL cannot replace the original");
      var missing = new AssessmentTracker.RemainingCheck("task", "account", "PATCH", ORIGIN + "/write",
          check.reasons(), "review", check.action(), "run", one.id().substring(0, one.id().lastIndexOf(':') + 1) + "99999");
      rejects(() -> WorkbenchExtension.assessmentOriginal(store, missing), "expired original is reported without falling back to latest URL");
    }
  }
  static HttpRequest storedRequest(String body) {
    var request = BountyFeaturesTest.rq(ORIGIN + "/write", "PATCH", body, List.of());
    var service = request(ORIGIN + "/write", "PATCH", true, List.of()).httpService();
    return proxy(HttpRequest.class, (p, method, args) -> method.getName().equals("httpService")
        ? service : method.invoke(request, args));
  }
  static void ui() throws Exception {
    AtomicInteger sends = new AtomicInteger(), opened = new AtomicInteger();
    var http = proxy(Http.class, (p, m, a) -> { if (m.getName().equals("sendRequest")) sends.incrementAndGet(); return def(m); });
    var api = proxy(MontoyaApi.class, (p, m, a) -> m.getName().equals("http") ? http : def(m));
    edt(() -> {
      var panel = new ActivityPanel(api); panel.timer.stop();
      try {
        var ui = panel.assessmentPanel;
        check(!ui.remainingNext.isEnabled(), "next step is disabled until an actual task item is selected");
        String account = ExecutionLink052Test.account(ui.tracker, "Alice");
        String task = task(ui.tracker, "Review", account, "/a");
        task(ui.tracker, "Other", account, "/b");
        ui.refresh();
        ui.taskTable.setRowSelectionInterval(0, 0);
        ui.remainingTable.setRowSelectionInterval(0, 0);
        var expected = ui.selectedRemaining();
        var context = new WorkContext();
        var selection = context.snapshot();
        var navigation = ui.remainingNavigation(expected);
        check(WorkbenchExtension.assessmentNavigationMatches(ui, navigation, selection, context, 1, 1),
            "pending navigation captures the selected task/item and current work context");
        AtomicInteger lateSuccess = new AtomicInteger(), lateError = new AtomicInteger();
        Runnable pendingSuccess = () -> {
          if (WorkbenchExtension.assessmentNavigationMatches(ui, navigation, selection, context, 1, 1)) lateSuccess.incrementAndGet();
        };
        Runnable pendingError = () -> {
          if (WorkbenchExtension.assessmentNavigationMatches(ui, navigation, selection, context, 1, 1)) lateError.incrementAndGet();
        };
        ui.taskTable.setRowSelectionInterval(1, 1);
        pendingSuccess.run(); pendingError.run();
        check(lateSuccess.get() == 0 && lateError.get() == 0,
            "delayed original success and failure are discarded after the user selects another task");
        ui.taskTable.setRowSelectionInterval(0, 0); ui.remainingTable.setRowSelectionInterval(0, 0);
        check(!ui.remainingNavigationCurrent(navigation), "returning to the same task/item does not revive an older pending operation");
        var fresh = ui.remainingNavigation(ui.selectedRemaining());
        check(!WorkbenchExtension.assessmentNavigationMatches(ui, fresh, selection, context, 1, 2),
            "leaving the tool or starting a newer navigation invalidates pending work");
        context.configurationEdited();
        check(!WorkbenchExtension.assessmentNavigationMatches(ui, fresh, selection, context, 1, 1),
            "changed comparison configuration prevents stale preparation from replacing current work");
        ui.preparationNavigator = row -> { check(row.equals(expected), "navigation receives the selected exact account/API metadata"); opened.incrementAndGet(); };
        ui.remainingNext.doClick();
        check(opened.get() == 1 && sends.get() == 0 && ui.tracker.executionHistory().isEmpty(), "opening next step neither sends requests nor starts/completes an execution");
        ui.tracker.inspected(account, "GET", ORIGIN + "/a", List.of(), "검토 필요", true);
        rejects(ui::openRemainingAction, "stale selected reasons must be refreshed before navigation");
        ui.taskTable.setRowSelectionInterval(1, 1);
        check(ui.remainingTable.getSelectedRow() < 0 && !ui.remainingNext.isEnabled(), "changing tasks clears previous next-action selection");
        check(!ui.tracker.tasks().stream().filter(t -> t.id().equals(task)).findFirst().orElseThrow().state().equals("업무 완료"), "view navigation leaves task completion under explicit user control");
        ui.remainingTable.setRowSelectionInterval(0, 0);
        var removed = ui.remainingNavigation(ui.selectedRemaining());
        ui.tracker.clear();
        check(!ui.remainingNavigationCurrent(removed), "project reset discards pending navigation without throwing on removed task metadata");
      } finally { panel.close(); }
    });
  }
}
