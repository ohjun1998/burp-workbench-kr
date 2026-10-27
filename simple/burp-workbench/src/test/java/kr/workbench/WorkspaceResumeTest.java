package kr.workbench;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** Reopen, project isolation and provenance behavior; no Burp GUI or target service is used. */
public final class WorkspaceResumeTest extends WorkbenchTest {
  static final String A = "11111111-1111-1111-1111-111111111111";
  static final String B = "22222222-2222-2222-2222-222222222222";
  static final String TASK = "33333333-3333-3333-3333-333333333333";
  static final String FILTERS = TrafficSearchPresets.capture(TrafficQuery.defaults());
  static final class Storage implements WorkspaceResume.Storage {
    String project = A;
    final Map<String, String> records = new HashMap<>();
    int writes;
    public String project() { return project; }
    public String read() { return records.get(project); }
    public void write(String value) { records.put(project, value); writes++; }
  }
  public static void main(String[] args) throws Exception {
    reopen(); boundaries(); malformed(); exactOriginal(); panel(); integration();
    System.out.println("WorkspaceResumeTest: " + checks + " checks passed");
  }

  static void reopen() {
    Storage storage = new Storage();
    WorkspaceResume first = new WorkspaceResume(storage);
    first.capture("data", 2, TASK, A + ":7", FILTERS);
    var saved = first.snapshot();
    check(saved.feature().equals("data") && saved.subtab() == 2 && saved.task().equals(TASK),
        "last feature, inner page and exact task ID survive serialization");
    int writes = storage.writes;
    first.capture("data", 2, TASK, A + ":7", FILTERS);
    check(storage.writes == writes, "unchanged UI ticks do not repeatedly rewrite project metadata");
    WorkspaceResume reopened = new WorkspaceResume(storage);
    check(reopened.pending() && reopened.snapshot().equals(saved), "reopen exposes the last session as an explicit pending choice");
    reopened.capture("traffic", -1, "", "", FILTERS);
    check(storage.writes == writes && reopened.snapshot().equals(saved),
        "default startup navigation cannot overwrite a pending last-session bookmark");
    check(reopened.accept(saved), "explicit resume accepts the reviewed snapshot");
    reopened.capture("activity", -1, TASK, "", FILTERS);
    check(reopened.snapshot().feature().equals("activity") && storage.writes == writes + 1,
        "subsequent real navigation records the resumed work");
    String encoded = storage.records.get(A);
    check(!encoded.contains("Authorization") && !encoded.contains("Cookie") && !encoded.contains("searchText")
        && !encoded.contains("body"), "bookmark format stores filters and opaque references only");
  }

  static void boundaries() {
    Storage storage = new Storage();
    WorkspaceResume session = new WorkspaceResume(storage);
    session.capture("auth", -1, TASK, A + ":0", FILTERS);
    var previous = session.snapshot();
    storage.project = B;
    check(!session.current(previous) && !session.accept(previous), "review from another project cannot be restored or accepted");
    session.capture("auth", -1, TASK, A + ":0", FILTERS);
    check(!storage.records.containsKey(B) && session.pending(),
        "switching to a fresh project requires explicit choice before old UI state can be recorded");
    session.startFresh(); session.capture("traffic", -1, "", "", FILTERS);
    check(session.snapshot().project().equals(B) && session.snapshot().task().isEmpty(),
        "explicit fresh start creates an independent current-project bookmark");
    storage.project = A;
    check(session.snapshot().equals(previous) && session.pending(), "returning to the first project restores its own unresolved record");
    storage.project = B; storage.records.put(B, storage.records.get(A));
    session.refreshProject();
    check(session.snapshot() == null && !session.problem().isEmpty(), "copied foreign-project records are rejected without replacing their bytes");
  }

  static void malformed() {
    Storage storage = new Storage(); storage.records.put(A, "{broken");
    WorkspaceResume session = new WorkspaceResume(storage);
    session.capture("traffic", -1, "", "", FILTERS);
    check(storage.writes == 0 && storage.records.get(A).equals("{broken"),
        "damaged bookmark is preserved until explicit fresh-start choice");
    var valid = new WorkspaceResume.Snapshot(A, "traffic", -1, "", "", FILTERS, 1);
    String json = WorkspaceResume.encode(valid);
    rejects(() -> WorkspaceResume.decode(json.replace("\"schema\":1", "\"schema\":2")), "future bookmark schema is not silently interpreted");
    rejects(() -> WorkspaceResume.decode(json.substring(0, json.length() - 1) + ",\"token\":\"secret\"}"),
        "unexpected credential-bearing fields cannot enter a bookmark");
    rejects(() -> new WorkspaceResume.Snapshot(A, "traffic", -1, "", "", "{\"schema\":1,\"query\":{\"token\":\"secret\"}}", 1),
        "search filters pass the strict metadata schema before serialization");
    rejects(() -> new WorkspaceResume.Snapshot(A, "traffic", -1, "", "not-a-store:0", FILTERS, 1),
        "original references must use exact store UUID and sequence syntax");
    session.startFresh(); session.capture("traffic", -1, "", "", FILTERS);
    check(storage.writes == 1 && session.problem().isEmpty(), "explicit fresh start replaces damaged metadata with a valid bookmark");
  }

  static void exactOriginal() throws Exception {
    BountyFeaturesTest.factory(); installTrafficPacketFactory();
    var request = RemainingChecks053Test.storedRequest("{\"value\":1}");
    var second = RemainingChecks053Test.storedRequest("{\"value\":2}");
    var response = response(200, "{}"); registerTrafficPacket(request, response); registerTrafficPacket(second, response);
    try (var store = new DiskTrafficStore(); var other = new DiskTrafficStore()) {
      var original = store.append(request, response); var sameUrl = store.append(second, response); other.append(request, response);
      check(WorkspaceResume.original(store, original.id()).id().equals(original.id())
          && !WorkspaceResume.original(store, original.id()).id().equals(sameUrl.id()),
          "resume retains the selected original rather than another body at the same URL");
      check(WorkspaceResume.original(other, original.id()) == null, "same sequence in another store does not restore a foreign original");
      check(WorkspaceResume.original(store, original.id().substring(0, original.id().lastIndexOf(':') + 1) + "99999") == null,
          "expired originals produce missing-original guidance instead of fallback selection");
    }
  }

  static void panel() throws Exception {
    AtomicInteger restored = new AtomicInteger(), fresh = new AtomicInteger();
    var snapshot = new WorkspaceResume.Snapshot(A, "auth", -1, TASK, A + ":0", FILTERS, 1);
    edt(() -> {
      var panel = new WorkspaceResumePanel(snapshot, "업무 기록을 먼저 불러오세요", false, "", value -> restored.incrementAndGet(), fresh::incrementAndGet);
      check(restored.get() == 0 && fresh.get() == 0, "opening resume guidance never restores or runs work automatically");
      check(panel.details.getText().contains("원본이 현재 임시 저장소에 없습니다")
          && panel.details.getText().contains("계정") && panel.details.getText().contains("증거"),
          "missing original, account reconnect and evidence verification remain visible before restore");
      panel.resume.doClick(0);
      check(restored.get() == 1, "resume applies only through the explicit restore action");
      var empty = new WorkspaceResumePanel(null, "", false, "기록 확인 필요", value -> restored.incrementAndGet(), fresh::incrementAndGet);
      check(!empty.resume.isEnabled(), "missing or rejected bookmark cannot be resumed");
      empty.fresh.doClick(0);
      check(fresh.get() == 1, "fresh-start choice is available even when the old record is unusable");
    });
  }

  static void integration() throws Exception {
    AtomicReference<Map<String, String>> project = new AtomicReference<>(new HashMap<>());
    project.get().put(WorkspaceResume.PROJECT_KEY, A);
    var data = proxy(burp.api.montoya.persistence.PersistedObject.class, (p, m, a) -> switch (m.getName()) {
      case "getString" -> project.get().get(a[0]);
      case "setString" -> { project.get().put(a[0].toString(), a[1].toString()); yield null; }
      default -> def(m);
    });
    var fallbackPersistence = PayloadIntegrationTest.host(burp.api.montoya.persistence.Persistence.class);
    var persistence = proxy(burp.api.montoya.persistence.Persistence.class, (p, m, a) ->
        m.getName().equals("extensionData") ? data : m.invoke(fallbackPersistence, a));
    var fallback = PayloadIntegrationTest.host(burp.api.montoya.MontoyaApi.class);
    var api = proxy(burp.api.montoya.MontoyaApi.class, (p, m, a) ->
        m.getName().equals("persistence") ? persistence : m.invoke(fallback, a));
    int sends = PayloadIntegrationTest.sent.get();
    WorkbenchExtension extension = new WorkbenchExtension();
    TrafficBrowser browser = null; ActivityPanel activity = null;
    try (var disk = new DiskTrafficStore()) {
      var getRequest = request("https://resume.test/read", "GET", true, List.of());
      var postRequest = request("https://resume.test/write", "POST", true, List.of());
      var response = response(200, "body"); registerTrafficPacket(getRequest, response); registerTrafficPacket(postRequest, response);
      disk.append(getRequest, response); disk.append(postRequest, response);
      var session = WorkspaceResume.forApi(api);
      var context = (WorkContext) get(extension, "workContext");
      AtomicReference<TrafficBrowser> browserRef = new AtomicReference<>();
      AtomicReference<ActivityPanel> activityRef = new AtomicReference<>();
      edt(() -> {
        try {
          field(extension, "api", api); field(extension, "trafficStore", disk);
          field(extension, "workspaceResume", session); field(extension, "workspaceProject", A);
          field(extension, "root", new JPanel());
          var model = (CaptureTableModel) get(extension, "logModel"); model.publish(2);
          var table = new JTable(model); table.setRowSorter(new CaptureRowSorter(model, error -> { throw error; }));
          field(extension, "logTable", table);
          var filter = (JTextField) get(extension, "filter");
          var search = new TrafficBrowser(table, model, filter, api, () -> disk, () -> {}); browserRef.set(search);
          field(extension, "trafficBrowser", search);
          table.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) return;
            int row = table.getSelectedRow();
            if (row < 0) context.clear(); else context.select(disk.ref(table.convertRowIndexToModel(row)));
          });
          var tasks = new ActivityPanel(api); tasks.timer.stop(); activityRef.set(tasks); field(extension, "activity", tasks);
          var pages = new JTabbedPane();
          for (String id : List.of("traffic", "auth")) { var panel = new JPanel(); panel.putClientProperty("wb.feature.id", id); pages.addTab(id, panel); }
          field(extension, "tabs", pages);
          var traffic = new JTabbedPane(); traffic.addTab("requests", new JPanel()); traffic.addTab("data", new JPanel()); field(extension, "trafficTools", traffic);
        } catch (Exception error) { throw new IllegalStateException(error); }
      });
      browser = browserRef.get(); activity = activityRef.get();
      final TrafficBrowser currentBrowser = browser;
      var postFilters = new TrafficQuery("POST", "전체", 0, false, false, false, true, false, true, "", 0);
      TrafficIncrementalSearchTest.query(currentBrowser, new TrafficQuery("GET", "전체", 0, false, false, false, true, false, true, "", 0), "");
      check(currentBrowser.table.getRowCount() == 1 && currentBrowser.table.convertRowIndexToModel(0) == 0,
          "resume integration begins with the desired original hidden by the previous filter");
      edt(() -> {
        session.startFresh(); session.capture("traffic", -1, "", disk.ref(1).id(), TrafficSearchPresets.capture(postFilters));
        try { call(extension, "restoreWorkspace", WorkspaceResume.Snapshot.class, session.snapshot()); }
        catch (Exception error) { throw new IllegalStateException(error); }
      });
      TrafficIncrementalSearchTest.idle(currentBrowser); resumeSelectionIdle(extension);
      edt(() -> check(currentBrowser.table.getSelectedRow() >= 0
          && currentBrowser.table.convertRowIndexToModel(currentBrowser.table.getSelectedRow()) == 1
          && context.snapshot().requestId().equals(disk.ref(1).id()),
          "actual resume waits for the restored filter then selects the exact previously hidden original"));
      edt(() -> {
        session.startFresh(); session.capture("traffic", -1, "", A + ":99999", FILTERS);
        try { call(extension, "restoreWorkspace", WorkspaceResume.Snapshot.class, session.snapshot()); }
        catch (Exception error) { throw new IllegalStateException(error); }
        check(currentBrowser.table.getSelectedRow() < 0 && !context.snapshot().selected(),
            "restoring a missing original clears another current request instead of leaving it executable");
      });
      TrafficIncrementalSearchTest.idle(currentBrowser);
      edt(() -> {
        session.startFresh(); session.capture("traffic", -1, "", disk.ref(1).id(), TrafficSearchPresets.capture(postFilters));
        try { call(extension, "restoreWorkspace", WorkspaceResume.Snapshot.class, session.snapshot()); }
        catch (Exception error) { throw new IllegalStateException(error); }
        extension.openFeature("auth");
      });
      TrafficIncrementalSearchTest.idle(currentBrowser); resumeSelectionIdle(extension);
      edt(() -> check(currentBrowser.table.getSelectedRow() < 0 && !context.snapshot().selected(),
          "later user navigation cancels deferred original restoration"));
      AtomicReference<WorkspaceResumePanel> opened = new AtomicReference<>();
      edt(() -> {
        extension.dialogOpener = (owner, title, content, modal, closed) -> { opened.set((WorkspaceResumePanel) content); return null; };
        try {
          var method = WorkbenchExtension.class.getDeclaredMethod("openWorkspaceResume"); method.setAccessible(true); method.invoke(extension);
        } catch (Exception error) { throw new IllegalStateException(error); }
        Map<String, String> other = new HashMap<>(); other.put(WorkspaceResume.PROJECT_KEY, B); project.set(other);
        opened.get().fresh.doClick(0);
        check(!other.containsKey(WorkspaceResume.KEY), "stale fresh-start dialog cannot write a newly selected project's bookmark");
      });
      check(PayloadIntegrationTest.sent.get() == sends, "opening and restoring actual Workbench views sends zero requests");
    } finally {
      TrafficBrowser search = browser; ActivityPanel tasks = activity;
      edt(() -> {
        try { var timer = (javax.swing.Timer) get(extension, "resumeSelectionTimer"); if (timer != null) timer.stop(); }
        catch (Exception error) { throw new IllegalStateException(error); }
        if (search != null) search.close(); if (tasks != null) tasks.close();
      });
    }
  }

  static void field(WorkbenchExtension extension, String name, Object value) throws Exception {
    var field = WorkbenchExtension.class.getDeclaredField(name); field.setAccessible(true); field.set(extension, value);
  }

  static void resumeSelectionIdle(WorkbenchExtension extension) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    for (;;) {
      AtomicInteger active = new AtomicInteger();
      edt(() -> { try { var timer = (javax.swing.Timer) get(extension, "resumeSelectionTimer"); active.set(timer != null && timer.isRunning() ? 1 : 0); }
        catch (Exception error) { throw new IllegalStateException(error); } });
      if (active.get() == 0) return;
      if (System.nanoTime() > deadline) throw new AssertionError("resume selection did not settle");
      Thread.sleep(10);
    }
  }
}
