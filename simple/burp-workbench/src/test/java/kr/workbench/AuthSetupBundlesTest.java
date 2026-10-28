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
    check(captures.get() == 5 && reviews.get() == 2, "registration captures and explicit apply review snapshots execute without a transport API");
    persistence();
    System.out.println("AuthSetupBundlesTest: " + checks + " checks passed");
  }

  static void fileIdle(AuthSetupBundlePanel panel) throws Exception {
    var finished = new java.util.concurrent.CountDownLatch(1);
    edt(() -> {
      try {
        var worker = (javax.swing.SwingWorker<?, ?>) get(panel, "fileWork");
        if (worker == null) { finished.countDown(); return; }
        worker.addPropertyChangeListener(event -> {
          if (event.getPropertyName().equals("state") && event.getNewValue() == javax.swing.SwingWorker.StateValue.DONE) finished.countDown();
        });
      } catch (Exception error) { throw new AssertionError(error); }
    });
    if (!finished.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("bundle file worker did not finish");
    edt(() -> {});
  }

  @FunctionalInterface interface FileAction { void run() throws Exception; }
  static void fileRejects(FileAction action, String label) throws Exception {
    try { action.run(); throw new AssertionError(label); }
    catch (java.io.IOException | IllegalArgumentException expected) { check(true, label); }
  }

  static void persistence() throws Exception {
    var root = Files.createTempDirectory("auth-bundle-persistence-");
    var store = new AuthSetupBundleStore(root.resolve("project-a"));
    var initial = bundle("영구 설정", 1); var revised = initial.withRevision(2);
    List<AuthSetupBundlePanel> panels = new ArrayList<>();
    try {
      check(store.load().bundles().isEmpty(), "new project library starts empty without global fallback");
      store.commit(List.of(), List.of(initial));
      check(new AuthSetupBundleStore(store.directory).load().bundles().equals(List.of(initial)), "new library instance restores name target settings and revision");
      store.commit(List.of(initial), List.of(revised));
      check(AuthSetupBundles.read(store.backup).equals(List.of(initial)), "last committed generation remains as validated recovery backup");
      fileRejects(() -> new AuthSetupBundleStore(store.directory).commit(List.of(initial), List.of(initial.withRevision(3))), "stale project writer cannot replace another window's revision");
      check(store.load().bundles().equals(List.of(revised)), "conflicting save leaves committed snapshot intact");
      try (var channel = java.nio.channels.FileChannel.open(store.directory.resolve("library.lock"), java.nio.file.StandardOpenOption.WRITE);
           var held = channel.lock()) {
        fileRejects(() -> store.commit(List.of(revised), List.of(initial.withRevision(3))), "concurrent file lock causes explicit retry rather than overwrite");
      }
      Files.writeString(store.primary, "{broken");
      check(store.load().recovered() && store.load().bundles().equals(List.of(initial)), "damaged primary recovers complete prior snapshot without partially parsed rows");
      store.commit(List.of(initial), List.of(revised));
      check(!store.load().recovered() && AuthSetupBundles.read(store.backup).equals(List.of(initial)), "saving after recovery repairs primary and preserves good backup");
      Files.writeString(store.primary, AuthSetupBundles.encode(List.of(revised)).replace(AuthSetupBundles.SCHEMA, "workbench-auth-setup-99"));
      fileRejects(store::load, "future schema preserves data instead of silently rolling back to old backup");
      AuthSetupBundles.write(store.primary, List.of(revised));

      AtomicInteger applied = new AtomicInteger(); AtomicReference<AuthSetupBundlePanel> box = new AtomicReference<>();
      edt(() -> {
        var panel = new AuthSetupBundlePanel(null, () -> initial.withRevision(0), value -> applied.incrementAndGet(), store);
        panels.add(panel); box.set(panel); panel.review = (title, values) -> true;
      });
      var first = box.get(); fileIdle(first);
      check(first.bundles.snapshot().equals(List.of(revised)) && applied.get() == 0, "automatic project restore populates library only and never applies active settings");
      edt(() -> { first.name.setText(initial.name()); first.saveCurrent(); }); fileIdle(first);
      check(first.bundles.get(initial.key()).revision() == 3 && store.load().bundles().get(0).revision() == 3, "reviewed registration publishes memory only after durable project save");
      check(applied.get() == 0, "project autosave never applies a bundle or enables request replay");
      edt(first::close);
      edt(() -> {
        var panel = new AuthSetupBundlePanel(null, () -> initial.withRevision(0), value -> applied.incrementAndGet(), new AuthSetupBundleStore(store.directory));
        panels.add(panel); box.set(panel); panel.review = (title, values) -> true;
      });
      var reopened = box.get(); fileIdle(reopened);
      check(reopened.bundles.get(initial.key()).revision() == 3 && applied.get() == 0, "restarted panel restores exact saved revision without account or setting activation");
      var external = initial.withRevision(4); store.commit(List.of(initial.withRevision(3)), List.of(external));
      edt(() -> { reopened.name.setText(initial.name()); reopened.saveCurrent(); }); fileIdle(reopened);
      check(reopened.bundles.get(initial.key()).revision() == 3 && store.load().bundles().equals(List.of(external)), "failed stale async save changes neither in-memory review nor newer disk state");
      edt(reopened::restore); fileIdle(reopened);
      check(reopened.bundles.get(initial.key()).revision() == 4, "reload resolves external library update before a new review");
      edt(() -> reopened.importReviewed(List.of(bundle("불러온 설정", 1)))); fileIdle(reopened);
      check(store.load().bundles().size() == 2 && applied.get() == 0, "reviewed JSON import persists complete library without applying imported settings");
      edt(() -> { reopened.review = (title, values) -> false; reopened.removeSelected(); });
      check(store.load().bundles().size() == 2, "cancelled deletion preserves the durable library");
      edt(() -> { reopened.review = (title, values) -> true; reopened.removeSelected(); }); fileIdle(reopened);
      check(store.load().bundles().equals(List.of(external)) && applied.get() == 0, "explicit library deletion frees capacity without changing active settings");
      String saved = Files.readString(store.primary);
      check(!saved.contains("Authorization") && !saved.contains("Cookie") && !saved.contains("sessionChecks") && !saved.contains("profileOwners"), "project persistence uses only existing credential-free exchange schema");
      var other = new AuthSetupBundleStore(root.resolve("project-b"));
      check(other.load().bundles().isEmpty(), "distinct projects cannot inherit another project's library");
      Files.writeString(store.primary, "{broken"); Files.writeString(store.backup, "{broken-too");
      edt(reopened::restore); fileIdle(reopened);
      check(!reopened.save.isEnabled() && !reopened.read.isEnabled() && reopened.reload.isEnabled(), "unreadable primary and backup block overwrites while exposing retry");
      check(Files.readString(store.primary).equals("{broken"), "failed restore leaves corrupt source available for recovery");
      Map<String, String> projectData = new HashMap<>();
      var data = proxy(burp.api.montoya.persistence.PersistedObject.class, (p, m, a) -> switch (m.getName()) {
        case "getString" -> projectData.get(a[0]); case "setString" -> { projectData.put((String) a[0], (String) a[1]); yield null; } default -> def(m);
      });
      var persistence = proxy(burp.api.montoya.persistence.Persistence.class, (p, m, a) -> m.getName().equals("extensionData") ? data : def(m));
      var api = proxy(burp.api.montoya.MontoyaApi.class, (p, m, a) -> m.getName().equals("persistence") ? persistence : def(m));
      var projectStore = AuthSetupBundleStore.project(api);
      check(projectStore != null && projectStore.directory.equals(AuthSetupBundleStore.project(api).directory), "Burp project identity resolves same local library after restart");
      check(projectData.size() == 1 && projectData.keySet().iterator().next().equals(BuildInfo.storageKey("auth-setup-library-project-v1")), "project metadata stores only the exact Simple-namespaced UUID key rather than credentials or rules");
    } finally {
      edt(() -> panels.forEach(AuthSetupBundlePanel::close));
      try (var paths = Files.walk(root)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
  }
}
