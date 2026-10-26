package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Next-action filtering keeps exact provenance and never executes work. */
public final class RemainingWorkUsabilityTest extends WorkbenchTest {
  static final String ORIGIN = ExecutionLink052Test.ORIGIN;

  public static void main(String[] args) throws Exception {
    BountyFeaturesTest.factory();
    projections(); filteredNavigation();
    System.out.println("RemainingWorkUsabilityTest: " + checks + " checks passed");
  }

  static String prepare(AssessmentTracker tracker) {
    String alice = ExecutionLink052Test.account(tracker, "Alice");
    String bob = ExecutionLink052Test.account(tracker, "Bob");
    String task = tracker.createTask("점검 이어가기", "", Set.of(alice), false);
    tracker.addTarget(task, "GET", ORIGIN + "/a");
    tracker.addTarget(task, "GET", ORIGIN + "/b");
    var context = ExecutionLink052Test.context(alice, bob,
        "GET /a ALLOW DENY UNKNOWN UNKNOWN\nGET /b ALLOW DENY UNKNOWN UNKNOWN", List.of());
    ExecutionLink052Test.begin(tracker, "failed-a", "/a", context);
    tracker.finishAuthExecution("failed-a", ExecutionLedger.State.FAILED, "전송 실패");
    return task;
  }

  static void projections() {
    var tracker = new AssessmentTracker();
    String task = prepare(tracker);
    var source = tracker.remainingChecks(task);
    long revision = tracker.revision();
    var uninspected = AssessmentTracker.RemainingFocus.NOT_INSPECTED.select(source);
    check(uninspected.size() == 1 && uninspected.get(0).url().endsWith("/b"),
        "uninspected filter excludes an actual failed execution and finds the untouched API");
    var failures = AssessmentTracker.RemainingFocus.EXECUTION.select(source);
    check(failures.size() == 1 && failures.get(0).executionId().equals("failed-a")
        && failures.get(0).originalId().equals("capture-store:failed-a"),
        "failure filter preserves exact execution and original identities");
    check(AssessmentTracker.RemainingFocus.ORIGINAL_MISSING.select(source).equals(uninspected),
        "unlinked originals remain distinct from a same-project execution with provenance");
    check(AssessmentTracker.RemainingFocus.EVIDENCE.count(source) == 2,
        "evidence filter includes missing ZIP for both failed and unexecuted work");
    check(AssessmentTracker.RemainingFocus.CONDITIONS_CHANGED.select(source).isEmpty(),
        "execution failure is not mislabeled as changed configuration");
    check(failures.get(0).causeLabel().contains("실행 실패")
        && failures.get(0).causeLabel().contains("증거 미저장"),
        "compact cause labels retain multiple independent unresolved requirements");
    check(tracker.revision() == revision && tracker.remainingChecks(task) == source
        && tracker.executionHistory().size() == 1,
        "filtering and overlapping cause counts leave work state and execution history untouched");
    var taskOnly = new AssessmentTracker.RemainingCheck("task", "", "", "",
        List.of(AssessmentTracker.RemainingReason.TASK_REVIEW), "요구 계정/API 필요",
        AssessmentTracker.RemainingAction.TASK, "", "");
    check(!AssessmentTracker.RemainingFocus.ORIGINAL_MISSING.accepts(taskOnly)
        && AssessmentTracker.RemainingFocus.REVIEW.accepts(taskOnly),
        "empty task setup is actionable review rather than a missing request packet");
  }

  static void filteredNavigation() throws Exception {
    AtomicInteger sends = new AtomicInteger(), opened = new AtomicInteger();
    var http = proxy(Http.class, (p, method, args) -> {
      if (method.getName().equals("sendRequest")) sends.incrementAndGet();
      return def(method);
    });
    var api = proxy(MontoyaApi.class, (p, method, args) -> method.getName().equals("http") ? http : def(method));
    edt(() -> {
      var owner = new ActivityPanel(api); owner.timer.stop();
      try {
        var ui = owner.assessmentPanel;
        String task = prepare(ui.tracker); ui.refresh();
        check(ui.restoreTaskSelection(task) && ui.selectedTaskId().equals(task),
            "explicit task resume restores the exact saved task without executing it");
        check(!ui.restoreTaskSelection("removed-task") && ui.selectedTaskId().equals(task),
            "missing saved task is reported without substituting another task");
        check(ui.resumeTaskSummary(task).contains("현재 파일 확인은 별도"),
            "resume guidance distinguishes stored evidence metadata from verified current files");
        ui.remainingFilter.setSelectedItem(AssessmentTracker.RemainingFocus.EXECUTION);
        check(ui.remainingTable.getRowCount() == 1 && ui.remainingSummary.getText().equals("표시 1 / 전체 2"),
            "cause selection updates visible rows and total without concealing other work");
        ui.remainingTable.setRowSelectionInterval(0, 0);
        var selected = ui.selectedRemaining();
        var pending = ui.remainingNavigation(selected);
        check(ui.remainingNext.getText().equals(selected.nextAction())
            && ui.remainingExplanation.getText().contains(selected.originalId()),
            "selected row exposes its concrete next action and exact original in details");
        ui.remainingFilter.setSelectedItem(AssessmentTracker.RemainingFocus.NOT_INSPECTED);
        check(!ui.remainingNavigationCurrent(pending) && ui.remainingTable.getSelectedRow() < 0,
            "switching cause filter invalidates pending navigation and clears a hidden selection");
        ui.remainingTable.setRowSelectionInterval(0, 0);
        var untouched = ui.selectedRemaining();
        ui.preparationNavigator = check -> {
          check(check.equals(untouched) && check.url().endsWith("/b") && check.originalId().isEmpty(),
              "filtered navigation receives the exact unlinked account/API, not a visible row index");
          opened.incrementAndGet();
        };
        ui.remainingNext.doClick();
        check(opened.get() == 1 && sends.get() == 0 && ui.tracker.executionHistory().size() == 1,
            "opening the filtered next action sends no request and creates no execution");
        ui.remainingFilter.setSelectedItem(AssessmentTracker.RemainingFocus.CONDITIONS_CHANGED);
        check(ui.remainingTable.getRowCount() == 0 && !ui.remainingNext.isEnabled()
            && ui.remainingExplanation.getText().contains("현재 조건에 해당하는 항목이 없습니다"),
            "empty cause view explains how to find other work and offers no stale action");
      } finally { owner.close(); }
    });
  }
}
