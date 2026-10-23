package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.HttpRequestResponse;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Suggestions are bounded local evidence, never an inferred policy or executed request. */
public final class AuthSetupSuggestionsTest extends WorkbenchTest {
  static HttpRequestResponse packet(String method, String url, int status, String body) {
    return AuthRecordTest.rr(request(url, method, true, List.of()), response(status, body));
  }
  public static void main(String[] args) throws Exception {
    BountyFeaturesTest.factory();
    var write = packet("PATCH", "https://example.test/api/items/1", 200, "{}");
    var same = packet("GET", "https://example.test/api/items/1", 200, "{\"title\":\"old\",\"meta\":{\"count\":2},\"ids\":[1],\"empty\":[],\"n\":null}");
    var near = packet("GET", "https://example.test/api/items/2", 200, "{}");
    var other = packet("GET", "https://example.test/me", 200, "{}");
    var candidates = AuthSetupSuggestions.reads(write.request(), List.of(other, near, same, same,
        packet("GET", "https://outside.test/api/items/1", 200, "{}"),
        packet("POST", "https://example.test/api/items/1", 200, "{}"),
        packet("GET", "https://example.test/private", 403, "{}"),
        packet("GET", "https://example.test/large", 200, "x".repeat(128 * 1024 + 1)),
        packet("GET", "https://example.test/api/items/1?%74oken=secret", 200, "{}"),
        packet("GET", "https://user:secret@example.test/api/items/1", 200, "{}")), q -> true);
    check(candidates.size() == 3, "same-origin successful bounded GET candidates deduplicated; credential query excluded");
    check(candidates.get(0).packet() == same && candidates.get(1).packet() == near, "same and adjacent paths prioritized without inferring semantics");
    check(AuthSetupSuggestions.reads(write.request(), List.of(same), q -> false).isEmpty(), "out-of-scope observations excluded");
    List<HttpRequestResponse> many = new ArrayList<>();
    for (int i = 0; i < 100; i++) many.add(packet("POST", "https://example.test/p" + i, 200, "{}"));
    many.add(same);
    check(AuthSetupSuggestions.reads(write.request(), many, q -> true).isEmpty(), "candidate scanning limited to first hundred retained packets");
    var fields = AuthSetupSuggestions.fields(same);
    check(fields.stream().anyMatch(f -> f.path().equals("$.meta.count") && f.value().equals("2")), "nested JSON numeric field retained with observed value");
    check(fields.stream().anyMatch(f -> f.path().equals("$.ids[0]")), "array member paths remain selectable");
    check(fields.stream().anyMatch(f -> f.path().equals("$.n") && f.value().equals("null")), "JSON null differs from missing field");
    check(fields.stream().noneMatch(f -> f.path().equals("$.empty")), "empty collection sentinel is not suggested as a scalar expectation");
    check(AuthSetupSuggestions.fields(packet("GET", "https://example.test/html", 200, "<html>login</html>")).isEmpty(), "HTML does not invent JSON fields");
    check(AuthSetupSuggestions.fields(packet("GET", "https://example.test/array", 200, "[1,2]")).isEmpty(), "unsupported root-array paths not offered");
    check(AuthSetupSuggestions.fields(packet("GET", "https://example.test/large", 200, "x".repeat(128*1024+1))).isEmpty(), "field parsing respects response byte budget");
    StringBuilder large = new StringBuilder("{");
    for (int i = 0; i < 250; i++) { if (i > 0) large.append(','); large.append('"').append('f').append(i).append("\":").append(i); }
    large.append('}');
    check(AuthSetupSuggestions.fields(packet("GET", "https://example.test/many", 200, large.toString())).size() == 200, "field suggestions capped at two hundred");

    String known = "PATCH /api/items/{id} ALLOW DENY UNKNOWN UNKNOWN";
    check(AuthSetupSuggestions.prefillPolicy(known, List.of(write)).equals(known), "parameterized policy preserved without shadowing with UNKNOWN exact rule");
    String next = AuthSetupSuggestions.prefillPolicy("", List.of(write, write, same));
    var matrix = BountyMatrix.parse(next);
    check(matrix.rules.size() == 2 && matrix.expected("PATCH", write.request().url(), 0) == BountyMatrix.Expected.UNKNOWN,
        "method/path prefill deduplicates while permissions stay explicitly unknown");
    StringBuilder full = new StringBuilder();
    for (int i = 0; i < 32; i++) full.append("GET /p").append(i).append(" UNKNOWN UNKNOWN UNKNOWN UNKNOWN\n");
    rejects(() -> AuthSetupSuggestions.prefillPolicy(full.toString(), List.of(write)), "over-limit proposal rejected before editor mutation");

    AtomicInteger sent = new AtomicInteger();
    var http = proxy(Http.class, (p, m, a) -> { if (m.getName().equals("sendRequest")) sent.incrementAndGet(); return def(m); });
    var api = proxy(MontoyaApi.class, (p, m, a) -> m.getName().equals("http") ? http : def(m));
    WorkbenchScope.install(api).add("https://example.test/", true);
    AtomicReference<List<HttpRequestResponse>> selected = new AtomicReference<>(List.of(write));
    AtomicReference<AuthComparePanel> box = new AtomicReference<>();
    edt(() -> { var panel = new AuthComparePanel(api, selected::get); panel.onObservedRequests(() -> List.of(same, near)); box.set(panel); });
    var panel = box.get();
    try {
      edt(() -> {
        panel.prefillSelectedPolicy();
        check(panel.matrixEditor.snapshot().rules.size() == 1, "readiness setup prefill populates editable policy table");
        panel.effectSettings.connectSelected(false);
        check(panel.effectSettings.path.getText().equals("/api/items/1") && panel.effectSettings.method.getSelectedItem().equals("PATCH"), "blank write target prefills from selected request");
        check(!panel.effectSettings.enabled.isSelected(), "prefill never enables write observation or replay");
        panel.effectSettings.jsonPath.setText("$.old"); panel.effectSettings.expected.setText("old expectation");
        panel.effectSettings.applyRead(panel.effectSettings.observedReads().get(0));
        check(panel.effectSettings.url.getText().equals(same.request().url()) && panel.effectSettings.jsonPath.getText().isEmpty()
            && panel.effectSettings.expected.getText().isEmpty(), "new lookup URL clears incompatible field and expectation without copying observed value");
        var stale = panel.effectSettings.observedReads().get(0);
        selected.set(List.of(packet("PATCH", "https://example.test/api/items/2", 200, "{}")));
        rejects(() -> panel.effectSettings.applyRead(stale), "changed selected operation invalidates open candidate suggestion");
        panel.effectSettings.connectSelected(false);
        check(panel.effectSettings.path.getText().equals("/api/items/1"), "opening setup preserves previously configured write target");
        panel.effectSettings.connectSelected(true);
        check(panel.effectSettings.path.getText().equals("/api/items/2"), "explicit connect action replaces target");
      });
      try (DiskTrafficStore store = new DiskTrafficStore()) {
        installTrafficPacketFactory();
        var firstRef = store.append(write.request(), write.response());
        var secondRef = store.append(write.request(), write.response());
        WorkContext context = new WorkContext();
        edt(() -> {
          selected.set(List.of(write));
          panel.effectSettings.connectSelected(true);
          panel.onWorkContext(context); context.select(firstRef); context.configure("fixture"); context.comparisonStarted();
          panel.cookieSessions.cookieModel.addRow(new Object[]{"SID", "검사", "검사", "결과"});
          panel.matrixEditor.load();
          check(!context.comparisonStale(), "result refresh and policy table reload do not invalidate comparison inputs");
          panel.effectSettings.expected.setText("new goal");
          check(context.comparisonStale(), "actual effect expectation edit invalidates comparison stamp");
          context.configure("fixture2"); context.comparisonStarted();
          var staleSameUrl = panel.effectSettings.observedReads().get(0);
          context.select(secondRef);
          rejects(() -> panel.effectSettings.applyRead(staleSameUrl), "same URL with another immutable original invalidates candidate application");
        });
      }
      check(sent.get() == 0, "all setup helpers send zero HTTP requests");
    } finally { edt(panel::close); }
    System.out.println("AuthSetupSuggestionsTest: " + checks + " checks passed");
  }
}
