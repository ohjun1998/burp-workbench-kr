package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Next setup flow: async local reads, immutable row identity, named rules and explicit bundle apply. */
public final class AuthSetupWorkflowTest extends WorkbenchTest {
  /** Simulate a callback delivered while the modal readiness review is awaiting confirmation. */
  static void reviewDuring(AuthComparePanel panel, List<HttpRequestResponse> requests, Runnable callback) {
    panel.prepareSelection(requests);
    panel.reviewPreparedRequests(requests, (context, report, blocked) -> {
      if (!blocked.isEmpty()) throw new AssertionError("Expected executable readiness report: " + blocked);
      callback.run(); return true;
    });
  }
  static AuthEffectRules.Rule rule(String name, String path, String read) {
    return new AuthEffectRules.Rule(name, "https://example.test", new AuthEffectCheck.Config(true, "PATCH", path,
        "https://example.test" + read, "$.value", "1", AuthEffectCheck.Mode.EQUALS, 1));
  }
  static void waitLoader(CountDownLatch signal) throws Exception {
    if (!signal.await(5, TimeUnit.SECONDS)) throw new AssertionError("local setup worker timed out"); edt(() -> {});
  }
  public static void main(String[] args) throws Exception {
    var firstRule = rule("첫 조회", "/api/items/1", "/state/1");
    var secondRule = rule("두 번째 조회", "/api/items/2", "/state/2");
    var rules = List.of(firstRule, secondRule); AuthEffectRules.validate(rules);
    check(AuthEffectRules.select(rules, "PATCH", "https://example.test/api/items/1").url().endsWith("/state/1"), "first operation selects its own write observation");
    check(AuthEffectRules.select(rules, "PATCH", "https://example.test/api/items/2").url().endsWith("/state/2"), "second operation selects another observation in same comparison batch");
    check(!AuthEffectRules.select(rules, "GET", "https://example.test/api/items/1").enabled(), "read operation cannot inherit write rule");
    check(!AuthEffectRules.select(rules, "PATCH", "https://outside.test/api/items/1").enabled(), "same path across another origin cannot inherit observation");
    rejects(() -> AuthEffectRules.validate(List.of(firstRule, rule("겹침", "/api/items/{id}", "/state/1"))), "exact and parameterized overlap is rejected before execution");
    rejects(() -> AuthEffectRules.validate(List.of(firstRule, firstRule)), "duplicate rule rejected");

    try (AuthSuggestionLoader loader = new AuthSuggestionLoader()) {
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
      AtomicInteger delivered = new AtomicInteger(), errors = new AtomicInteger();
      edt(() -> loader.load(() -> { entered.countDown(); while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) {} } return 1; }, delivered::set, error -> errors.incrementAndGet()));
      check(entered.await(5, TimeUnit.SECONDS), "local restore starts on worker");
      AtomicBoolean responsive = new AtomicBoolean(); edt(() -> responsive.set(true));
      check(responsive.get(), "EDT stays responsive while old disk restore is blocked");
      edt(() -> loader.load(() -> 2, value -> { delivered.set(value); finished.countDown(); }, error -> { errors.incrementAndGet(); finished.countDown(); }));
      release.countDown(); waitLoader(finished);
      check(delivered.get() == 2 && errors.get() == 0, "superseded local result cannot overwrite latest candidate list");
      CountDownLatch cancelledEntered = new CountDownLatch(1), cancelledRelease = new CountDownLatch(1);
      edt(() -> loader.load(() -> { cancelledEntered.countDown(); while (cancelledRelease.getCount() > 0) { try { cancelledRelease.await(); } catch (InterruptedException ignored) {} } return 3; }, delivered::set, error -> errors.incrementAndGet()));
      check(cancelledEntered.await(5, TimeUnit.SECONDS), "cancellable local parse started");
      edt(loader::cancel); cancelledRelease.countDown();
      loader.worker.submit(() -> {}).get(5, TimeUnit.SECONDS); edt(() -> {});
      check(delivered.get() == 2, "closing or cancelling suggestions blocks late publication");
    }

    BountyFeaturesTest.factory(); AtomicInteger sends = new AtomicInteger(), writes = new AtomicInteger();
    Map<String, Integer> states = new ConcurrentHashMap<>(); states.put("1", 0); states.put("2", 0);
    var http = proxy(Http.class, (p, m, a) -> {
      if (!m.getName().equals("sendRequest")) return def(m);
      sends.incrementAndGet(); HttpRequest q = (HttpRequest) a[0]; String key = q.url().endsWith("/2") ? "2" : "1";
      if (q.method().equals("PATCH")) { writes.incrementAndGet(); states.put(key, 1); }
      return AuthRecordTest.rr(q, response(200, "{\"value\":" + states.get(key) + "}"));
    });
    var api = proxy(MontoyaApi.class, (p, m, a) -> m.getName().equals("http") ? http : def(m));
    WorkbenchScope.install(api).add("https://example.test/", true);
    HttpRequest first = BountyFeaturesTest.rq("https://example.test/api/items/1", "PATCH", "{\"value\":1}", List.of(header("Cookie", "ADMIN")));
    HttpRequest second = BountyFeaturesTest.rq("https://example.test/api/items/2", "PATCH", "{\"value\":1}", List.of(header("Cookie", "ADMIN")));
    HttpRequest sameUrlDifferentBody = BountyFeaturesTest.rq(first.url(), "PATCH", "{\"value\":9}", first.headers());
    var base = response(200, "{\"value\":0}");
    HttpRequestResponse firstPacket = AuthRecordTest.rr(first, base), secondPacket = AuthRecordTest.rr(second, base), alternate = AuthRecordTest.rr(sameUrlDifferentBody, base);
    AtomicReference<List<HttpRequestResponse>> selected = new AtomicReference<>(List.of(firstPacket));
    AtomicReference<AuthComparePanel> box = new AtomicReference<>();
    edt(() -> {
      var panel = new AuthComparePanel(api, selected::get); box.set(panel);
      panel.observe(BountyFeaturesTest.rq(first.url(), "PATCH", "{}", List.of(header("Cookie", "BOB"))), base);
      panel.assign(0, panel.credentials.values().iterator().next()); panel.writes.setSelected(true);
      panel.effectSettings.savedRules.addAll(rules);
      panel.comparisonSettings.opener = (parent, title, body, modal, closed) -> null;
      panel.sessionSettings.opener = (parent, title, body, modal, closed) -> null;
    });
    AuthComparePanel panel = box.get();
    try {
      CountDownLatch scanEntered = new CountDownLatch(1), scanRelease = new CountDownLatch(1);
      AtomicBoolean scanOffEdt = new AtomicBoolean(), windowBeforeScan = new AtomicBoolean();
      edt(() -> {
        panel.onObservedRequestRefs(() -> {
          scanOffEdt.set(!javax.swing.SwingUtilities.isEventDispatchThread());
          windowBeforeScan.set(panel.effectSettings.suggestionWindow != null);
          scanEntered.countDown();
          while (scanRelease.getCount() > 0) { try { scanRelease.await(); } catch (InterruptedException ignored) {} }
          return List.of();
        });
        panel.effectSettings.connectSelected(true); panel.effectSettings.openReads();
      });
      check(scanEntered.await(5, TimeUnit.SECONDS) && scanOffEdt.get(), "production candidate metadata supplier runs off EDT");
      check(windowBeforeScan.get(), "production candidate window exists before retained metadata restore starts");
      edt(() -> { panel.effectSettings.loader.cancel(); panel.effectSettings.suggestionWindow.close(); });
      scanRelease.countDown(); panel.effectSettings.loader.worker.submit(() -> {}).get(5, TimeUnit.SECONDS); edt(() -> {});
      check(sends.get() == 0, "cancelled production candidate loading sends zero HTTP requests");
      edt(() -> {
        var context = panel.context();
        check(context.effectRules().size() == 2 && context.effectFor(second).url().endsWith("/state/2"), "immutable context retains all named observation rules");
        panel.prepareSelection(List.of(firstPacket, alternate));
        var report = AuthReadiness.selected(context, List.of(first, sameUrlDifferentBody));
        check(report.rows().get(1).requestIndex() == 1, "readiness keeps exact position for same URL and method originals");
        panel.editReadinessRow(report.rows().get(1), "policy");
        check(panel.preparationSelection.equals(List.of(firstPacket, alternate)) && panel.setupRequests().get(0) == alternate,
            "row repair focuses second exact original while preserving full batch and order");
        check(panel.matrixEditor.table.getSelectedColumn() == 2, "row repair focuses the affected account policy cell");
        panel.effectSettings.connectSelected(true);
        var focusedCandidateStamp = panel.effectSettings.setupStamp();
        panel.editReadinessRow(report.rows().get(0), "policy");
        check(!panel.effectSettings.current(focusedCandidateStamp), "candidate results cannot cross same-URL originals when the edited row changes");
        panel.editReadinessRow(report.rows().get(1), "policy");
        var reviewed = panel.context();
        var content = AuthReadiness.content(reviewed, AuthReadiness.selected(reviewed, List.of(first, sameUrlDifferentBody)), List.of(), null,
            panel.preparationFocus, panel.preparationSlot);
        var readinessTable = findTable(content);
        check(readinessTable != null && readinessTable.getSelectedRow() == 1, "return to readiness preserves edited request and account row");
        var reviewStamp = panel.preparedStamp(reviewed);
        panel.validatePreparedExecution(reviewStamp, reviewed);
        panel.profileOwners[0].setText("changed-owner");
        rejects(() -> panel.validatePreparedExecution(reviewStamp, reviewed), "settings changed after review block the entire prepared batch before any send");
        panel.profileOwners[0].setText("");
        var scopeStamp = panel.preparedStamp(panel.context());
        WorkbenchScope.install(api).add("https://example.test/state/1", false);
        rejects(() -> panel.validatePreparedExecution(scopeStamp, reviewed), "Scope change after review blocks whole batch before first write");
        WorkbenchScope.install(api).add("https://example.test/", true);
        rejects(() -> panel.prepareSelection(List.of(firstPacket, firstPacket)), "same exact original cannot enter a prepared batch twice");
        panel.stageReadiness(List.of(firstPacket, secondPacket));
        check(panel.preparationSelection.equals(List.of(firstPacket, secondPacket)) && sends.get() == 0, "remaining-check navigation stages originals without network or comparison start");
        rejects(() -> reviewDuring(panel, List.of(firstPacket), () -> panel.stageReadiness(List.of(secondPacket))),
            "replacing prepared batch during modal confirmation rejects old execution even with unchanged work selection");
        check(panel.preparationSelection.equals(List.of(secondPacket)) && panel.runs.isEmpty() && sends.get() == 0,
            "replaced batch keeps its own preparation and never lends IDs to the old reviewed request");
        rejects(() -> reviewDuring(panel, List.of(firstPacket, alternate), () -> panel.prepareSelection(List.of(alternate, firstPacket))),
            "reordering same-URL different-body originals during confirmation rejects stale batch");
        rejects(() -> reviewDuring(panel, List.of(firstPacket, alternate), () -> panel.prepareSelection(List.of(firstPacket, alternate))),
            "replacing a batch with identical packet references still invalidates its previous review generation");
        panel.prepareSelection(List.of(firstPacket, alternate));
        var unchanged = panel.context(); var exactStamp = panel.preparedStamp(unchanged);
        rejects(() -> panel.validatePreparedExecution(exactStamp, unchanged, List.of(alternate, firstPacket)),
            "execution list must match reviewed original references and order independently of current selection");
        panel.prepareSelection(List.of(firstPacket)); panel.effectSettings.connectSelected(true);
        var stamp = panel.effectSettings.setupStamp(); panel.effectSettings.expected.setText("changed");
        check(!panel.effectSettings.current(stamp), "candidate result is stale after configuration edit");
        panel.effectSettings.expected.setText(""); panel.effectSettings.enabled.setSelected(false); panel.effectSettings.loadedKey = "";
        AuthSetupBundles.Bundle bundle = panel.captureBundle();
        check(bundle.roles().equals(AuthSetupBundles.ROLE_PLACEHOLDERS), "saved bundle uses role placeholders instead of credential accounts");
        int candidateCount = panel.credentials.size(); panel.applyBundle(bundle);
        check(Arrays.stream(panel.slots).allMatch(Objects::isNull) && panel.credentials.size() == candidateCount, "bundle application detaches account bindings while preserving observed candidates");
        check(!panel.writes.isSelected() && panel.effectSettings.savedRules.size() == 2, "bundle apply never enables write replay and retains both observation rules");
        rejects(() -> panel.context(), "bundle needs explicit role/account reassignment before comparison");
        selected.set(List.of(secondPacket)); panel.preparationSelection = List.of(); panel.preparationIds = List.of();
        rejects(() -> panel.applyBundle(bundle), "bundle cannot apply to another method/path silently");
        check(sends.get() == 0, "readiness repair and bundle capture/apply send zero HTTP requests");
        panel.assign(0, panel.credentials.values().iterator().next()); panel.writes.setSelected(true);
        panel.effectSettings.loadedKey = ""; panel.effectSettings.enabled.setSelected(false);
        selected.set(List.of(firstPacket, secondPacket)); panel.compareSelection();
      });
      AuthRecordTest.idle(panel); edt(() -> {});
      check(panel.runs.size() == 2 && writes.get() == 2, "production batch sends each configured write exactly once");
      check(panel.runs.get(0).packets[0].effect != null && panel.runs.get(0).packets[0].effect.config().url().endsWith("/state/1"), "first execution evidence preserves matching observation configuration");
      check(panel.runs.get(1).packets[0].effect != null && panel.runs.get(1).packets[0].effect.config().url().endsWith("/state/2"), "second execution evidence preserves different matching observation configuration");
      check(!panel.runs.get(0).executionId.equals(panel.runs.get(1).executionId), "each execution has a stable distinct evidence link id");
      check(panel.executionState(panel.runs.get(0)) == ExecutionLedger.State.COMPLETED, "completed operation has a terminal execution state independent of verdict");
      edt(() -> {
        Map<HttpRequest, String> identities = new IdentityHashMap<>(); identities.put(first, "capture:first"); identities.put(second, "capture:second");
        panel.onExecutionTracker(new AssessmentTracker(), request -> identities.getOrDefault(request, ""));
        panel.prepareSelection(List.of(firstPacket, secondPacket));
        check(panel.preparationIds.equals(List.of("capture:first", "capture:second")), "prepared batch preserves exact original ID order");
        var identifiedContext = panel.context(); var identifiedStamp = panel.preparedStamp(identifiedContext);
        check(identifiedStamp.originalIds().equals(List.of("capture:first", "capture:second")), "review stamp owns ordered original IDs independently of later preparation");
        panel.preparationIds = List.of("capture:first", "capture:changed"); identities.put(second, "capture:changed");
        rejects(() -> panel.validatePreparedExecution(identifiedStamp, identifiedContext, List.of(firstPacket, secondPacket)), "changed prepared IDs cannot be attached to requests from an older review");
        identities.put(second, "capture:second");
        panel.prepareSelection(List.of(firstPacket, secondPacket));
        identities.remove(second);
        rejects(panel::validatePreparedSelection, "expired original identity prevents resuming a prepared batch");
        identities.put(second, "capture:second"); panel.validatePreparedSelection();
        rejects(() -> panel.prepareSelection(Collections.nCopies(21, firstPacket)), "readiness still bounds a batch at twenty originals");
        panel.stop();
        check(panel.preparationSelection.isEmpty() && panel.preparationIds.isEmpty(), "stop releases prepared packet references and prevents accidental resume");
      });
    } finally { edt(panel::close); WorkbenchScope.release(api); }
    System.out.println("AuthSetupWorkflowTest: " + checks + " checks passed");
  }
  static javax.swing.JTable findTable(java.awt.Component component) {
    if (component instanceof javax.swing.JTable table && "wb.auth.readiness.table".equals(table.getName())) return table;
    if (component instanceof java.awt.Container container) for (var child : container.getComponents()) {
      var found = findTable(child); if (found != null) return found;
    }
    return null;
  }
}
