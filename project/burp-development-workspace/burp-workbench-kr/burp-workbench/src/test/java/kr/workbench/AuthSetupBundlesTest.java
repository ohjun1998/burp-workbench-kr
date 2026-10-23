package kr.workbench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit configuration exchange, revision/conflict behavior, and review-before-mutation. */
public final class AuthSetupBundlesTest extends WorkbenchTest {
  static AuthSetupBundles.Settings settings(String matrix, List<AuthEffectRules.Rule> effects) {
    return new AuthSetupBundles.Settings("$.ownerId", "$.timestamp", "$.value", "error=FORBIDDEN", "BODY: 접근 권한이 없습니다", "",
        AuthPolicy.Access.UNSPECIFIED, matrix, false, effects);
  }
  static AuthEffectRules.Rule effect(String name, String path) {
    return new AuthEffectRules.Rule(name, "https://example.test", new AuthEffectCheck.Config(true, "PATCH", path,
        "https://example.test" + path.replace("{id}", "1"), "$.value", "1", AuthEffectCheck.Mode.EQUALS, 1));
  }
  static AuthSetupBundles.Bundle bundle(String name, long revision) {
    return new AuthSetupBundles.Bundle(name, "https://EXAMPLE.test/", "PATCH", "/items/{id}", revision,
        AuthSetupBundles.ROLE_PLACEHOLDERS, settings("PATCH /items/{id} ALLOW DENY UNKNOWN UNKNOWN", List.of(effect("항목 변경", "/items/{id}"))));
  }
  public static void main(String[] args) throws Exception {
    var source = bundle("항목 권한", 1);
    check(source.origin().equals("https://example.test:443"), "bundle origin normalized without identity/query");
    String json = AuthSetupBundles.encode(List.of(source));
    check(AuthSetupBundles.decode(json).equals(List.of(source)), "named settings including multiple-rule model round trip exactly");
    check(!json.contains("credentials") && !json.contains("authorization") && !json.contains("cookie")
        && !json.contains("profileOwners") && !json.contains("sessionChecks"), "credential and actual account fields absent from schema");
    check(AuthSetupBundles.preview(List.of(source)).contains("역할 1~3") && AuthSetupBundles.preview(List.of(source)).contains("업무 기대값"), "review explains role placeholders and persisted business fields");
    rejects(() -> AuthSetupBundles.decode(json.replace("\"schema\":", "\"cookie\":\"SID=SECRET\",\"schema\":")), "root credential injection rejected");
    rejects(() -> AuthSetupBundles.decode(json.replace("\"settings\":{", "\"settings\":{\"authorization\":\"Bearer SECRET\",")), "nested credential injection rejected");
    rejects(() -> AuthSetupBundles.decode(json.replace("\"mode\":\"EQUALS\"", "\"mode\":\"UNKNOWN\"")), "unknown effect mode rejected");
    rejects(() -> AuthSetupBundles.decode(json.replace("\"revision\":1", "\"revision\":1.5")), "fractional revision rejected");
    rejects(() -> AuthSetupBundles.decode(json.replace("\"schema\":\"workbench-auth-setup-1\"", "\"schema\":\"other\"")), "unknown schema rejected");
    rejects(() -> AuthSetupBundles.decode(json + " {}"), "trailing JSON cannot append hidden fields");
    rejects(() -> AuthSetupBundles.decode(" ".repeat(AuthSetupBundles.MAX_BYTES + 1)), "import byte bound enforced before parse");
    rejects(() -> new AuthSetupBundles.Bundle("name", "https://user:SECRET@example.test", "PATCH", "/items/{id}", 1,
        AuthSetupBundles.ROLE_PLACEHOLDERS, source.settings()), "userinfo cannot enter bundle origin");
    rejects(() -> new AuthSetupBundles.Bundle("name", "https://example.test?token=SECRET", "PATCH", "/items/{id}", 1,
        AuthSetupBundles.ROLE_PLACEHOLDERS, source.settings()), "origin query cannot persist credentials");
    rejects(() -> new AuthSetupBundles.Bundle("name", "https://example.test", "PATCH", "/items/{id}", 1,
        List.of("alice", "bob", "admin", "anonymous"), source.settings()), "actual account labels cannot replace fixed role placeholders");
    rejects(() -> settings("GET /items/{id} ALLOW UNKNOWN UNKNOWN UNKNOWN\nGET /items/{item} DENY UNKNOWN UNKNOWN UNKNOWN", List.of()),
        "equivalent parameterized permission patterns cannot hide conflicting rules");
    rejects(() -> settings("", List.of(new AuthEffectRules.Rule("secret URL", "https://example.test", new AuthEffectCheck.Config(true, "PATCH", "/items/1",
        "https://example.test/items/1?%74oken=SECRET", "$.value", "1", AuthEffectCheck.Mode.EQUALS, 1)))), "encoded token query is excluded from settings persistence");
    rejects(() -> settings("", List.of(new AuthEffectRules.Rule("secret value", "https://example.test", new AuthEffectCheck.Config(true, "PATCH", "/items/1",
        "https://example.test/items/1", "$.value", "Bearer SECRET", AuthEffectCheck.Mode.EQUALS, 1)))), "obvious pasted bearer credential cannot be serialized as business expected value");
    rejects(() -> settings("", List.of(new AuthEffectRules.Rule("inactive malformed", "https://example.test", new AuthEffectCheck.Config(false, "PATCH", "/items/1",
        "", "", "", AuthEffectCheck.Mode.EQUALS, 1)))), "inactive rule cannot smuggle malformed future configuration");
    rejects(() -> settings("", List.of(new AuthEffectRules.Rule("inactive other origin", "https://example.test", new AuthEffectCheck.Config(false, "PATCH", "/items/1",
        "https://other.test/items/1", "$.value", "1", AuthEffectCheck.Mode.EQUALS, 1)))), "inactive rule cannot bypass same-origin import validation");
    check(AuthSetupBundles.origin("http://[::1]:8080/").equals("http://[::1]:8080"), "IPv6 origin normalization preserves one bracket pair");
    var two = settings("", List.of(effect("항목 A", "/a"), effect("항목 B", "/b")));
    check(two.effects().size() == 2, "multiple distinct write observations survive bundle validation");

    AuthSetupBundles store = new AuthSetupBundles();
    var first = store.save(source.withRevision(0), 0);
    check(first.revision() == 1 && store.snapshot().size() == 1, "new named setup begins at revision one");
    var second = store.save(source.withRevision(0), 1);
    check(second.revision() == 2 && store.snapshot().size() == 1, "explicit same-key save increments revision");
    rejects(() -> store.save(source, 1), "stale reviewed revision cannot overwrite newer setup");
    check(store.get(source.key()).revision() == 2, "failed revision update leaves saved settings unchanged");
    store.merge(List.of(second));
    check(store.snapshot().size() == 1, "identical imported bundle is idempotent");
    rejects(() -> store.merge(List.of(bundle("새로운 항목", 1), source)), "conflicting import rejected as one batch");
    check(store.snapshot().size() == 1, "conflicting import never partially registers earlier entries");
    rejects(() -> AuthSetupBundles.encode(List.of(source, source)), "duplicate names for the same operation rejected");
    List<AuthSetupBundles.Bundle> tooMany = new ArrayList<>();
    for (int i = 0; i < 33; i++) tooMany.add(bundle("묶음 " + i, 1));
    rejects(() -> store.merge(tooMany), "bundle count bounded at thirty two");
    rejects(() -> store.merge(List.of(bundle("revision 없는 초안", 0))), "unversioned drafts cannot enter imported store");

    var directory = Files.createTempDirectory("auth-setup-bundles-test-");
    var file = directory.resolve("settings.json");
    try {
      AuthSetupBundles.write(file, List.of(second));
      check(AuthSetupBundles.read(file).equals(List.of(second)), "explicit JSON save/load persists exact validated revision");
      Files.writeString(file, "가".repeat(AuthSetupBundles.MAX_BYTES / 2), StandardCharsets.UTF_8);
      rejects(() -> { try { AuthSetupBundles.read(file); } catch (java.io.IOException error) { throw new IllegalArgumentException(error); } }, "file import uses UTF-8 byte bound rather than character count");
    } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory); }

    AtomicInteger captures = new AtomicInteger(), applies = new AtomicInteger(), reviews = new AtomicInteger();
    AtomicReference<AuthSetupBundlePanel> box = new AtomicReference<>();
    edt(() -> {
      var panel = new AuthSetupBundlePanel(null, () -> { captures.incrementAndGet(); return source.withRevision(0); }, selected -> applies.incrementAndGet());
      box.set(panel); panel.name.setText("항목 권한");
      panel.review = (title, values) -> { reviews.incrementAndGet(); return false; };
      panel.saveCurrent();
      check(panel.bundles.snapshot().isEmpty() && applies.get() == 0, "cancelled save review leaves no bundle and performs no apply");
      panel.review = (title, values) -> { reviews.incrementAndGet(); return true; };
      panel.saveCurrent();
      check(panel.bundles.snapshot().size() == 1 && applies.get() == 0, "saving a reviewed bundle does not apply settings");
      panel.review = (title, values) -> false; panel.applySelected();
      check(applies.get() == 0, "cancelled apply review leaves active settings untouched");
      panel.review = (title, values) -> true; panel.applySelected();
      check(applies.get() == 1, "explicit reviewed apply invokes settings callback once");
      panel.importReviewed(List.of(bundle("다른 묶음", 1)));
      check(panel.bundles.snapshot().size() == 2 && applies.get() == 1, "import adds named setup without automatic active configuration change");
      panel.close();
      var closing = new AuthSetupBundlePanel(null, () -> source, ignored -> applies.incrementAndGet());
      closing.bundles.merge(List.of(source)); closing.refresh(source.key());
      closing.review = (title, values) -> { closing.close(); return true; };
      closing.applySelected();
      check(applies.get() == 1, "unload during confirmation cannot apply settings after panel closure");
    });
    check(captures.get() == 2 && reviews.get() == 2, "only explicit reviewed captures execute and no transport API is required");
    System.out.println("AuthSetupBundlesTest: " + checks + " checks passed");
  }
}
