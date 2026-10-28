package kr.workbench;

import burp.api.montoya.MontoyaApi;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.event.TableModelEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static kr.workbench.BountyFeaturesTest.factory;

/** Cache reuse must never retain stale completion or lose a sorted user's selection. */
public final class IncrementalCoverageTest extends WorkbenchTest {
    private static final String BASE = "https://auth.test";

    public static void main(String[] args) throws Exception {
        factory();
        activitySnapshots();
        assessmentSnapshots();
        edt(IncrementalCoverageTest::tableUpdates);
        System.out.println("IncrementalCoverageTest: " + checks + " checks passed");
    }

    private static void discover(ActivityTracker tracker, String path) {
        tracker.discover(new ReconMetadata.Discovery(BASE + path, "GET", Set.of("id"), Set.of(), "fixture"));
    }

    private static void activitySnapshots() {
        ActivityTracker tracker = new ActivityTracker();
        discover(tracker, "/one");
        discover(tracker, "/two");
        var before = tracker.snapshot();
        long structure = tracker.structureRevision();
        check(tracker.snapshot() == before && tracker.views() == before.rows(),
                "unchanged endpoint snapshot and summary are reused");
        tracker.complete(before.rows().get(0).key(), true);
        tracker.completeParameter(before.rows().get(0).key(), "URL:id", true);
        var after = tracker.snapshot();
        check(after.rows().get(1) == before.rows().get(1) && after.rows().get(0) != before.rows().get(0),
                "one endpoint mutation retains every unrelated immutable view");
        check(after.completed() == 1 && after.incompleteInputs() == 1,
                "cached summary reflects endpoint and input completion");
        check(tracker.structureRevision() == structure, "completion does not invalidate account endpoint structure");
        tracker.observe(request(BASE + "/two?id=2", "GET", true, List.of()), "PROXY");
        check(tracker.structureRevision() == structure, "repeated traffic does not invalidate account endpoint structure");
        tracker.externalParameter("GET", BASE + "/one", "BODY", "ownerId", "fixture");
        check(tracker.structureRevision() > structure && tracker.snapshot().rows().get(0).state().equals("재검토 필요"),
                "new input invalidates structure and previously completed endpoint");
        var stable = tracker.snapshot();
        var pending = new JsAnalysis.Endpoint(BASE + "/{dynamic}", "GET", "fetch", "id", "", "", 1, 0, 0, false, "");
        tracker.pending(pending, BASE + "/app.js");
        check(tracker.snapshot() == stable && tracker.pendingViews() == tracker.pendingViews(),
                "pending-only discovery reuses endpoint and pending snapshots");
        tracker.clear();
        check(tracker.snapshot().rows().isEmpty() && tracker.snapshot().incompleteInputs() == 0,
                "clear invalidates all cached endpoint counts");
    }

    private static void assessmentSnapshots() {
        AssessmentTracker tracker = new AssessmentTracker();
        String alice = tracker.register(AccountTasksTest.account("Alice"));
        String bob = tracker.register(AccountTasksTest.account("Bob"));
        String first = tracker.createTask("first", "", Set.of(alice), false);
        String second = tracker.createTask("second", "", Set.of(bob), false);
        tracker.addTarget(first, "GET", BASE + "/one");
        tracker.addTarget(second, "GET", BASE + "/two");
        tracker.observed(alice, "GET", BASE + "/one", List.of("URL:id"), "fixture");
        var initial = tracker.tasks();
        check(tracker.detailCacheSize() == 0, "summary calculation does not retain every task's account/API detail list");
        var pairs = tracker.taskOperations(first);
        long builds = tracker.taskViewBuilds;
        check(tracker.tasks() == initial && tracker.taskOperations(first) == pairs && tracker.taskViewBuilds == builds,
                "unchanged tasks reuse summaries and account/API combinations");
        var operation = tracker.view(alice, "GET", BASE + "/one", List.of());
        try {
            tracker.inspected(alice, "GET", BASE + "/one", List.of(), "x".repeat(513), false);
            throw new AssertionError("Oversized result accepted");
        } catch (IllegalArgumentException expected) {
            check(tracker.view(alice, "GET", BASE + "/one", List.of()) == operation
                            && tracker.operations.get(AssessmentTracker.opKey(alice, "GET", BASE + "/one")).inspections == 0,
                    "rejected inspection cannot partially mutate state behind a cached view");
        }
        long accountRevision = tracker.accountRevision(alice);
        tracker.observed(bob, "GET", BASE + "/unrelated", List.of(), "fixture");
        check(tracker.tasks() == initial && tracker.taskViewBuilds == builds,
                "unrelated operation does not recalculate any task summary");
        check(tracker.accountRevision(alice) == accountRevision
                        && tracker.view(alice, "GET", BASE + "/one", List.of()) == operation,
                "another account leaves the selected account view cache intact");
        tracker.complete(alice, "GET", BASE + "/one", List.of(), true);
        var changed = tracker.tasks();
        check(changed.get(1) == initial.get(1) && tracker.taskViewBuilds == builds + 1,
                "related operation recalculates only its task");
        tracker.completeTask(first, true);
        check(tracker.tasks().get(0).state().equals("업무 완료"), "cached task supports explicit completion");
        tracker.editAccount(alice, "Alice", "ADMIN", "OTHER");
        check(tracker.tasks().get(0).state().equals("재검토 필요")
                        && tracker.taskOperations(first).get(0).state().equals("재검토 필요"),
                "role and tenant changes invalidate cached task and operation security state");
        tracker.taskRequirements(first, true, false, Set.of("URL:id"));
        tracker.observedValues(alice, "GET", BASE + "/one", Map.of("URL:id", List.of("1")));
        tracker.completeInput(alice, "GET", BASE + "/one", "URL:id", true);
        tracker.complete(alice, "GET", BASE + "/one", List.of(), true);
        tracker.completeTask(first, true);
        tracker.tasks();
        tracker.observedValues(alice, "GET", BASE + "/one", Map.of("URL:id", List.of("2")));
        check(tracker.tasks().get(0).state().equals("재검토 필요")
                        && tracker.taskOperations(first).get(0).parameters().get(0).state().equals("미점검"),
                "critical input changes invalidate warm task and input caches");
        for (int i = 0; i < 40; i++) {
            String id = tracker.createTask("cache-" + i, "", Set.of(alice), false);
            tracker.addTarget(id, "GET", BASE + "/one");
            tracker.taskOperations(id);
        }
        check(tracker.detailCacheSize() == 32, "task detail LRU remains bounded without dropping actual tasks");
        long oldRevision = tracker.accountRevision(alice);
        AssessmentTracker imported = new AssessmentTracker();
        imported.load(MinerJson.parse(MetadataExport.json(tracker.save()), 32 * 1024 * 1024, 3000000));
        tracker.replace(imported);
        check(tracker.accountRevision(alice) > oldRevision, "import cannot reuse a panel's old account cache revision");
        tracker.clear();
        check(tracker.tasks().isEmpty() && tracker.accounts().isEmpty(), "clear removes cached account and task snapshots");
    }

    private static void tableUpdates() {
        MontoyaApi api = proxy(MontoyaApi.class, (proxy, method, arguments) -> def(method));
        ActivityPanel panel = new ActivityPanel(api);
        panel.timer.stop();
        try {
            discover(panel.tracker, "/one");
            discover(panel.tracker, "/two");
            AssessmentPanel ui = panel.assessmentPanel;
            String alice = ui.tracker.register(AccountTasksTest.account("Alice"));
            String bob = ui.tracker.register(AccountTasksTest.account("Bob"));
            ui.tracker.observed(alice, "GET", BASE + "/one", List.of("URL:id"), "fixture");
            String task = ui.tracker.createTask("task", "", Set.of(alice), false);
            ui.tracker.addTarget(task, "GET", BASE + "/one");
            panel.refresh();
            ui.selectAccount(alice);
            panel.table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.DESCENDING)));
            panel.table.setRowSelectionInterval(panel.table.convertRowIndexToView(0), panel.table.convertRowIndexToView(0));
            panel.parameterTable.setRowSelectionInterval(0, 0);
            ui.accountTable.setRowSelectionInterval(0, 0);
            ui.inputTable.setRowSelectionInterval(0, 0);
            ui.taskTable.setRowSelectionInterval(0, 0);
            ui.detailTable.setRowSelectionInterval(0, 0);
            List<TableModelEvent> activityEvents = new ArrayList<>(), accountEvents = new ArrayList<>(), taskEvents = new ArrayList<>();
            panel.model.addTableModelListener(activityEvents::add);
            ui.accountModel.addTableModelListener(accountEvents::add);
            ui.taskModel.addTableModelListener(taskEvents::add);
            AtomicInteger choices = new AtomicInteger();
            ui.accountChoice.addActionListener(event -> choices.incrementAndGet());
            panel.refresh();
            check(activityEvents.isEmpty() && accountEvents.isEmpty() && taskEvents.isEmpty() && choices.get() == 0,
                    "unchanged refresh emits no table events or account-selection resets");
            panel.tracker.complete(panel.tracker.views().get(0).key(), true);
            panel.refresh();
            check(activityEvents.size() == 1 && activityEvents.get(0).getType() == TableModelEvent.UPDATE
                            && activityEvents.get(0).getFirstRow() == activityEvents.get(0).getLastRow(),
                    "single endpoint change emits one precise row update");
            check(panel.selected().url().equals(BASE + "/one") && panel.parameterTable.getSelectedRow() == 0
                            && panel.table.getRowSorter().getSortKeys().get(0).getSortOrder() == SortOrder.DESCENDING,
                    "endpoint and input selection survive sorted incremental updates");
            check(accountEvents.isEmpty() && taskEvents.isEmpty(), "global URL completion does not rebuild account/task tables");
            ui.tracker.observed(bob, "GET", BASE + "/two", List.of("URL:id"), "fixture");
            panel.refresh();
            check(accountEvents.isEmpty() && taskEvents.isEmpty(), "unrelated account traffic emits no selected-table updates");
            ui.tracker.complete(alice, "GET", BASE + "/one", List.of(), true);
            panel.refresh();
            check(accountEvents.size() == 1 && taskEvents.size() == 1
                            && accountEvents.get(0).getType() == TableModelEvent.UPDATE,
                    "account completion updates only the affected operation and task rows");
            check(ui.inputTable.getSelectedRow() == 0 && ui.detailTable.getSelectedRow() == 0
                            && ui.selected().account().equals(alice),
                    "account input and task-detail selections survive updated rows");
            activityEvents.clear();
            panel.tracker.review("GET", BASE + "/one", "fixture review");
            panel.refreshVisible();
            check(activityEvents.isEmpty(), "hidden parent tab defers timer-driven rendering");
            panel.refresh();
            check(activityEvents.size() == 1 && panel.model.getValueAt(0, 2).equals("재검토 필요"),
                    "explicit headless refresh catches up deferred metadata");
            accountEvents.clear();
            ui.tracker.inspected(alice, "GET", BASE + "/one", List.of(), "needs review", true);
            ui.refresh(true);
            check(accountEvents.isEmpty(), "visible-only refresh defers unselected account subtab");
            panel.coveragePages.setSelectedComponent(ui.accountsPage);
            ui.refresh(true);
            check(accountEvents.size() == 1 && ui.accountModel.getValueAt(0, 2).equals("재검토 필요"),
                    "opening account subtab catches up the correct review state");
            panel.search.setText("no-match");
            check(panel.selected() == null && panel.params.getRowCount() == 0,
                    "filtering away a selection clears stale input details");
            panel.search.setText("");
            panel.table.setRowSelectionInterval(0, 0);
            check(panel.params.getRowCount() == 1, "restoring filtered rows retains valid parameter model mapping");
        } finally {
            panel.close();
        }
    }
}
