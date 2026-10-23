package kr.workbench;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** Context identity, stale-input tracking and redaction; no network or native Burp involved. */
public final class WorkContextTest extends WorkbenchTest {
  static AuthComparePanel.Context config(String credential, String matrix, String expected, String field) {
    var profiles = new AuthComparePanel.Candidate[4];
    profiles[0] = new AuthComparePanel.Candidate(1, "same-account", "https://example.test",
        Map.of("authorization", credential), 1, "");
    String[] empty = {"", "", "", ""};
    var refresh = new BountySession.Config[] {BountySession.Config.off(), BountySession.Config.off(),
        BountySession.Config.off(), BountySession.Config.off()};
    return new AuthComparePanel.Context(0, "https://example.test", profiles, Set.of("authorization"),
        true, "", empty, empty, AuthJudgement.rules("", "error=DENIED", ""),
        AuthPolicy.parse("", AuthPolicy.Access.UNSPECIFIED), AuthResponseRules.parse(""), refresh,
        BountyMatrix.parse(matrix), empty, empty, false, empty, Set.of(),
        new CookieSessionProbe.Config[4], new AuthEffectCheck.Config(true, "PATCH", "/item",
            "https://example.test/item", field, expected, AuthEffectCheck.Mode.EQUALS, 1));
  }

  public static void main(String[] args) throws Exception {
    WorkContext context = new WorkContext();
    check(!context.snapshot().selected() && context.comparisonStarted() == null,
        "empty context cannot fabricate a comparison stamp");
    try (var store = new DiskTrafficStore()) {
      var first = store.append("12:00", "fixture", request(
          "https://example.test/item?token=SHOULD_NOT_APPEAR#fragment", "GET", true,
          List.of(header("Authorization", "Bearer SHOULD_NOT_APPEAR"))), response(200, "{}"), 0);
      var second = store.append("12:01", "fixture", request(
          "https://example.test/item?token=DIFFERENT", "GET", true, List.of()), response(200, "{}"), 0);
      context.select(first, new int[] {0, 1});
      var selected = context.snapshot();
      check(selected.selectedCount() == 2 && selected.requestId().equals(first.id())
          && selected.description().startsWith("원본 #1"),
          "selection carries retained original identity and batch size");
      check(!selected.toString().contains("SHOULD_NOT_APPEAR") && !selected.target().contains("?")
          && !selected.target().contains("#"), "context has no credentials, query values or fragments");
      context.select(first, new int[] {1, 0});
      check(context.snapshot().selectionRevision() == selected.selectionRevision(),
          "selection order changes do not invalidate the same batch");
      context.configure("configuration-A");
      context.comparisonStarted();
      check(!context.comparisonStale() && context.status().contains("시작 기록")
          && context.status().contains("완료·성공 여부"), "start stamp never asserts completion or success");
      context.select(first, new int[] {0, 2});
      check(context.comparisonStale(), "same leading request and count with different batch is stale");
      context.comparisonStarted();
      context.select(second, new int[] {0, 2});
      check(context.comparisonStale(), "identical displayed path cannot alias another stored request");
      context.comparisonStarted();
      context.configurationEdited();
      check(context.comparisonStale(), "input editing immediately invalidates a prior stamp");
      long revision = context.snapshot().configurationRevision();
      context.configurationEdited();
      check(context.snapshot().configurationRevision() == revision, "repeated edit notifications stay bounded");
      context.configure("configuration-A");
      check(context.comparisonStale(), "editing back to same values does not silently reuse a prior run");
      context.comparisonStarted();
      context.configure("configuration-B");
      check(context.comparisonStale(), "effective configuration changes invalidate a run without UI events");
      context.clear();
      check(!context.snapshot().selected() && context.comparisonStale(), "clearing traffic removes current context");
    }
    check(WorkContext.displayUrl("https://user:SECRET@example.test/p%20ath?secret=value#fragment")
        .equals("https://example.test/p%20ath"), "display URL drops user info and preserves escaped paths");
    check(!WorkContext.displayUrl("bad-url-SECRET").contains("SECRET"), "invalid URL is never echoed");

    String a = WorkContext.authFingerprint(config("Bearer FIRST", "PATCH /item DENY UNKNOWN UNKNOWN UNKNOWN", "1", "$.value"));
    check(a.equals(WorkContext.authFingerprint(config("Bearer FIRST", "PATCH /item DENY UNKNOWN UNKNOWN UNKNOWN", "1", "$.value"))),
        "equal effective configurations produce a stable fingerprint");
    check(!a.equals(WorkContext.authFingerprint(config("Bearer SECOND", "PATCH /item DENY UNKNOWN UNKNOWN UNKNOWN", "1", "$.value"))),
        "credential replacement changes configuration identity");
    check(!a.equals(WorkContext.authFingerprint(config("Bearer FIRST", "PATCH /item ALLOW UNKNOWN UNKNOWN UNKNOWN", "1", "$.value"))),
        "expected permission changes configuration identity");
    check(!a.equals(WorkContext.authFingerprint(config("Bearer FIRST", "PATCH /item DENY UNKNOWN UNKNOWN UNKNOWN", "2", "$.value"))),
        "write expected value changes configuration identity");
    check(!a.equals(WorkContext.authFingerprint(config("Bearer FIRST", "PATCH /item DENY UNKNOWN UNKNOWN UNKNOWN", "1", "$.different"))),
        "write JSON field changes configuration identity despite redacted Config.toString");
    check(a.matches("[0-9a-f]{64}") && !a.contains("Bearer"), "fingerprint stores only a fixed length digest");

    AtomicReference<WorkContextPanel> panel = new AtomicReference<>();
    edt(() -> {
      var input = new JTextField("old");
      context.observeSettings(input, input);
      context.configure("prepared");
      long before = context.snapshot().configurationRevision();
      input.setText("new");
      check(context.snapshot().configurationRevision() == before + 1,
          "explicit settings listener is idempotent and detects actual edits");
      panel.set(new WorkContextPanel(context));
      check(panel.get().getName().equals("wb.work.context")
          && panel.get().request.getToolTipText().contains("선택"), "shared strip has accessible selection guidance");
    });
    System.out.println("WorkContextTest: " + checks + " checks passed");
  }
}
