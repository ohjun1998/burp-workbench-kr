package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Persistence;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

/** Durable authored prose, strict bounds, project races and failed-write preservation. */
public final class EvidenceDraftsTest extends WorkbenchTest {
  static final String A = "11111111-1111-1111-1111-111111111111";
  static final String B = "22222222-2222-2222-2222-222222222222";
  static final String ITEM = "33333333-3333-3333-3333-333333333333";
  static final class Storage implements EvidenceDrafts.Storage {
    String project = A;
    final Map<String, String> records = new HashMap<>();
    int writes, reads;
    boolean fail, ignore;
    Runnable onRead;
    public String project() { return project; }
    public String read() { reads++; if (onRead != null) { Runnable action = onRead; onRead = null; action.run(); } return records.get(project); }
    public void write(String value) {
      if (fail) throw new IllegalArgumentException("storage quota fixture");
      writes++; if (!ignore) records.put(project, value);
    }
  }
  static EvidenceDrafts.Content prose(String title) {
    return new EvidenceDrafts.Content(title, "관측한 영향", "사전 조건\n1. 계정 비교\n관측 결과", Map.of(
        ITEM, new EvidenceDrafts.Annotation("계정별 비교", "확인한 결과 메모")));
  }
  public static void main(String[] args) throws Exception {
    reopen(); projects(); corruption(); writes(); parallelWriters(); bounds(); host();
    System.out.println("EvidenceDraftsTest: " + checks + " checks passed");
  }

  static void reopen() {
    Storage storage = new Storage(); AtomicLong time = new AtomicLong(1000);
    EvidenceDrafts draft = new EvidenceDrafts(storage, time::get);
    check(draft.snapshot() == null && draft.savedAt() == 0 && !draft.blocked(), "new project has no invented saved draft");
    check(draft.save(A, prose("증거 제목")), "authored draft saves to the captured current project");
    var saved = draft.snapshot();
    check(saved.content().equals(prose("증거 제목")) && saved.savedAt() == 1000, "saved title, impact, steps, item descriptions and clock survive");
    EvidenceDrafts reopened = new EvidenceDrafts(storage);
    check(reopened.snapshot().equals(saved) && reopened.savedAt() == 1000, "a new controller restores the same durable text draft");
    int writes = storage.writes;
    time.set(2000); check(draft.save(A, prose("증거 제목")) && storage.writes == writes && draft.savedAt() == 1000,
        "unchanged autosave neither rewrites storage nor invents a new save time");
    String json = storage.records.get(A);
    check(!json.contains("request") && !json.contains("response") && !json.contains("packet")
        && !json.contains("Cookie") && !json.contains("Authorization") && !json.contains("https://"),
        "draft format captures authored prose and opaque item IDs without packet, URL or credential fields");
    Map<String, EvidenceDrafts.Annotation> source = new LinkedHashMap<>();
    source.put(ITEM, new EvidenceDrafts.Annotation("name", "note"));
    var copied = new EvidenceDrafts.Content("", "", "", source); source.clear();
    check(copied.annotations().size() == 1, "content owns a defensive annotation-map copy");
    boolean immutable = false;
    try { copied.annotations().clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
    check(immutable, "draft annotation snapshots are immutable");
  }

  static void projects() {
    Storage storage = new Storage(); EvidenceDrafts draft = new EvidenceDrafts(storage);
    draft.save(A, prose("project A")); var a = draft.snapshot();
    storage.project = B;
    check(draft.snapshot() == null && draft.project().equals(B), "project transition drops the other project's in-memory text");
    check(!draft.save(A, prose("stale A queued save")) && !storage.records.containsKey(B),
        "a delayed old-project autosave cannot adopt the new project");
    check(draft.save(B, prose("project B")), "new project saves only after the caller captures its own identity");
    var b = draft.snapshot(); storage.project = A;
    check(draft.snapshot().equals(a), "returning to the first project restores only its own draft");
    storage.project = B;
    check(draft.snapshot().equals(b), "returning to the second project keeps independent text and timestamp");
    storage.records.put(B, storage.records.get(A)); draft.reload();
    check(draft.blocked() && draft.snapshot() == null && !draft.save(B, prose("replacement")),
        "a foreign-project payload is rejected and never silently replaced");
    Storage racing = new Storage(); EvidenceDrafts queued = new EvidenceDrafts(racing);
    racing.onRead = () -> racing.project = B;
    check(!queued.save(A, prose("old project")) && racing.writes == 0 && !racing.records.containsKey(B),
        "project movement during guarded storage read prevents all writes");
    storage.project = "";
    check(draft.snapshot() == null && draft.blocked() && !draft.problem().isBlank()
        && !draft.save("", prose("no project")), "unavailable project storage is visible and cannot save unbound text");
    storage.project = A; draft.reload(); check(draft.snapshot().equals(a), "reload returns to a valid project's durable record");
    storage.project = ""; draft.reload();
    check(draft.snapshot() == null && draft.savedAt() == 0 && draft.blocked(),
        "reload after storage becomes unavailable cannot retain another project's former snapshot");
  }

  static void corruption() {
    Storage storage = new Storage(); storage.records.put(A, "{broken");
    EvidenceDrafts draft = new EvidenceDrafts(storage);
    check(draft.blocked() && draft.savedAt() == 0 && !draft.problem().isBlank(), "damaged persisted text exposes a recoverable failure");
    check(!draft.save(A, prose("new")) && storage.writes == 0 && storage.records.get(A).equals("{broken"),
        "autosave preserves malformed original bytes until explicit replacement");
    check(!draft.resetCorrupt(B) && storage.records.get(A).equals("{broken"), "a foreign-project replacement choice is refused");
    check(draft.resetCorrupt(A) && storage.records.get(A).equals("{broken"), "explicit fresh choice itself does not erase durable data");
    check(draft.save(A, prose("new")) && !draft.blocked() && draft.snapshot().content().title().equals("new"),
        "a successful save replaces the old record after explicit fresh choice");
    String valid = storage.records.get(A);
    rejects(() -> EvidenceDrafts.decode(valid.replace("\"schema\":1", "\"schema\":2")), "future draft schema is refused");
    rejects(() -> EvidenceDrafts.decode(valid.substring(0, valid.length() - 1) + ",\"request\":\"raw\"}"),
        "unexpected packet-bearing fields do not enter the durable draft schema");
    rejects(() -> EvidenceDrafts.decode(valid.replace("\"savedAt\":", "\"savedAt\":0.5,\"oldTime\":")),
        "altered timestamp shape and unknown fields are rejected");
    rejects(() -> EvidenceDrafts.decode("[]"), "a non-object record is refused");
    rejects(() -> EvidenceDrafts.decode(valid.replace("\"annotations\":{", "\"annotations\":{\"bad\":{\"name\":\"x\",\"note\":\"y\"},")),
        "non-opaque evidence identifiers cannot be restored as linked item descriptions");
  }

  static void writes() {
    Storage storage = new Storage(); EvidenceDrafts first = new EvidenceDrafts(storage);
    first.save(A, prose("baseline")); String before = storage.records.get(A); var saved = first.snapshot();
    storage.fail = true;
    check(!first.save(A, prose("unsaved editor content")) && first.snapshot().equals(saved)
        && storage.records.get(A).equals(before) && !first.problem().isBlank(),
        "quota or persistence exception retains last confirmed snapshot and durable bytes");
    storage.fail = false;
    check(first.save(A, prose("retry")) && first.problem().isEmpty(), "a storage failure can be retried without discarding editor text");
    EvidenceDrafts second = new EvidenceDrafts(storage); first.save(A, prose("newer writer"));
    String newer = storage.records.get(A);
    check(!second.save(A, prose("stale writer")) && second.blocked() && storage.records.get(A).equals(newer),
        "concurrent controller changes cannot be silently overwritten by stale autosave");
    second.reload();
    check(!second.blocked() && second.snapshot().content().title().equals("newer writer"), "explicit reload obtains the newer concurrent draft");
    check(second.save(A, prose("reviewed subsequent edit")), "editing after an explicit reload can save normally");
    Storage ignored = new Storage(); ignored.ignore = true; EvidenceDrafts undurable = new EvidenceDrafts(ignored);
    check(!undurable.save(A, prose("not stored")) && undurable.snapshot() == null && undurable.savedAt() == 0,
        "an ignored write is never shown as saved merely because setString returned");
    Storage compare = new Storage(); EvidenceDrafts atomic = new EvidenceDrafts(compare);
    compare.records.put(A, "concurrent value");
    check(!atomic.save(A, prose("old")) && compare.records.get(A).equals("concurrent value"),
        "optimistic predecessor comparison protects an initially absent record");
  }

  static void bounds() {
    rejects(() -> new EvidenceDrafts.Content("x".repeat(EvidenceDrafts.TITLE_LIMIT + 1), "", "", Map.of()), "oversized title is refused rather than truncated");
    rejects(() -> new EvidenceDrafts.Content("", "x".repeat(EvidenceDrafts.IMPACT_LIMIT + 1), "", Map.of()), "oversized impact is refused rather than truncated");
    rejects(() -> new EvidenceDrafts.Content("", "", "x".repeat(EvidenceDrafts.REPRO_LIMIT + 1), Map.of()), "oversized reproduction is refused rather than truncated");
    rejects(() -> new EvidenceDrafts.Annotation("x".repeat(EvidenceDrafts.NAME_LIMIT + 1), ""), "oversized per-item name is refused");
    rejects(() -> new EvidenceDrafts.Annotation("", "x".repeat(EvidenceDrafts.NOTE_LIMIT + 1)), "oversized per-item note is refused");
    Map<String, EvidenceDrafts.Annotation> many = new HashMap<>();
    for (int i = 0; i <= EvidenceDrafts.MAX_ITEMS; i++) many.put(String.format("%08x-1111-1111-1111-111111111111", i), new EvidenceDrafts.Annotation("", ""));
    rejects(() -> new EvidenceDrafts.Content("", "", "", many), "per-item annotations cannot exceed the existing evidence capacity");
    rejects(() -> new EvidenceDrafts.Snapshot(A, prose("x"), -1), "negative save timestamp is refused");
    rejects(() -> new EvidenceDrafts.Snapshot(B + "x", prose("x"), 1), "invalid project identity is refused");
    var large = new EvidenceDrafts.Content("", "한".repeat(EvidenceDrafts.IMPACT_LIMIT), "글".repeat(EvidenceDrafts.REPRO_LIMIT), Map.of());
    rejects(() -> EvidenceDrafts.encode(new EvidenceDrafts.Snapshot(A, large, 1)), "combined UTF-8 draft byte budget applies to multibyte text");
    rejects(() -> EvidenceDrafts.decode(" ".repeat(EvidenceDrafts.MAX_BYTES + 1)), "oversized raw draft is rejected before parsing");
    Storage storage = new Storage(); EvidenceDrafts draft = new EvidenceDrafts(storage); draft.save(A, prose("kept")); String kept = storage.records.get(A);
    check(!draft.save(A, large) && storage.records.get(A).equals(kept) && draft.snapshot().content().title().equals("kept"),
        "aggregate-size rejection is atomic and preserves last confirmed prose");
  }

  static void parallelWriters() throws Exception {
    Map<String, String> records = new ConcurrentHashMap<>();
    CyclicBarrier readTogether = new CyclicBarrier(2);
    AtomicBoolean race = new AtomicBoolean();
    Map<Thread, Boolean> reached = new ConcurrentHashMap<>();
    var storage = new EvidenceDrafts.Storage() {
      public String project() { return A; }
      public String read() {
        String value = records.get(A);
        if (race.get() && reached.putIfAbsent(Thread.currentThread(), true) == null) {
          try { readTogether.await(5, TimeUnit.SECONDS); }
          catch (Exception failure) { throw new IllegalArgumentException(failure); }
        }
        return value;
      }
      public void write(String value) { records.put(A, value); }
    };
    var first = new EvidenceDrafts(storage); var second = new EvidenceDrafts(storage);
    AtomicInteger successes = new AtomicInteger(); race.set(true);
    Thread a = new Thread(() -> { if (first.save(A, prose("parallel first"))) successes.incrementAndGet(); });
    Thread b = new Thread(() -> { if (second.save(A, prose("parallel second"))) successes.incrementAndGet(); });
    a.start(); b.start(); a.join(6000); b.join(6000); race.set(false);
    check(!a.isAlive() && !b.isAlive() && successes.get() == 1,
        "simultaneous stale controllers cannot both commit and silently overwrite one another");
    check(first.blocked() != second.blocked(), "the losing parallel writer retains a visible conflict for explicit reload");
  }

  static void host() {
    Map<String, String> a = new HashMap<>(), b = new HashMap<>(); a.put(WorkspaceResume.PROJECT_KEY, A); b.put(WorkspaceResume.PROJECT_KEY, B);
    AtomicReference<Map<String, String>> selected = new AtomicReference<>(a);
    Persistence persistence = proxy(Persistence.class, (p, m, args) -> {
      if (!m.getName().equals("extensionData")) return def(m);
      Map<String, String> bound = selected.get();
      return proxy(PersistedObject.class, (q, method, values) -> switch (method.getName()) {
        case "getString" -> bound.get(values[0].toString());
        case "setString" -> { bound.put(values[0].toString(), values[1].toString()); yield null; }
        default -> def(method);
      });
    });
    MontoyaApi api = proxy(MontoyaApi.class, (p, m, args) -> m.getName().equals("persistence") ? persistence : def(m));
    EvidenceDrafts draft = EvidenceDrafts.forApi(api);
    check(draft.save(A, prose("native persistence adapter")) && a.containsKey(EvidenceDrafts.KEY),
        "Montoya adapter writes text to project extension data");
    selected.set(b);
    check(!draft.save(A, prose("stale A")) && !b.containsKey(EvidenceDrafts.KEY), "adapter rejects a prior project's delayed edit");
    check(draft.save(B, prose("B draft")) && b.containsKey(EvidenceDrafts.KEY), "adapter independently persists the selected project");
    selected.set(a);
    check(draft.snapshot().content().title().equals("native persistence adapter"), "adapter reopen retains first project's prose");
    EvidenceDrafts unavailable = EvidenceDrafts.forApi(null);
    check(unavailable.blocked() && !unavailable.save(A, prose("none")), "missing host persistence is visible without constructor failure");
  }
}
