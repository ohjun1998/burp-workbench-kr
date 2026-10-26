package kr.workbench;

import burp.api.montoya.MontoyaApi;
import java.util.*;

/** A small project bookmark. Never owns packets, credentials, search terms, or active executions. */
final class WorkspaceResume {
  static final String KEY = BuildInfo.storageKey("workspace-resume-v1");
  static final String PROJECT_KEY = BuildInfo.storageKey("auth-setup-library-project-v1");
  private static final Set<String> FEATURES = Set.of("traffic", "data", "mapper", "activity", "auth",
      "js", "environment", "passive", "response.recon", "payload", "bounty");
  interface Storage {
    String project();
    String read();
    void write(String value);
  }
  record Snapshot(String project, String feature, int subtab, String task, String original, String filters, long savedAt) {
    Snapshot {
      if (!uuid(project) || !FEATURES.contains(feature) || subtab < -1 || subtab > 20 || !task.isEmpty() && !uuid(task)
          || !original.isEmpty() && !validOriginal(original) || savedAt < 0 || savedAt > 253402300799999L)
        throw new IllegalArgumentException("이어가기 기록의 프로젝트·화면·원본 식별자를 확인하세요.");
      filters = TrafficSearchPresets.capture(TrafficSearchPresets.restore(filters));
    }
    boolean sameWork(Snapshot other) {
      return other != null && project.equals(other.project) && feature.equals(other.feature) && subtab == other.subtab
          && task.equals(other.task) && original.equals(other.original) && filters.equals(other.filters);
    }
  }
  private final Storage storage;
  private String project = "", problem = "";
  private Snapshot latest;
  private boolean pending, blocked;

  WorkspaceResume(Storage storage) { this.storage = Objects.requireNonNull(storage); refreshProject(); }

  static WorkspaceResume forApi(MontoyaApi api) {
    return new WorkspaceResume(new Storage() {
      public String project() { return projectIdentity(api); }
      public String read() { return api.persistence().extensionData().getString(KEY); }
      public void write(String value) { api.persistence().extensionData().setString(KEY, value); }
    });
  }

  static String projectIdentity(MontoyaApi api) {
    try {
      if (api == null || api.persistence() == null || api.persistence().extensionData() == null) return "";
      var data = api.persistence().extensionData();
      String id = data.getString(PROJECT_KEY);
      if (id == null) {
        id = UUID.randomUUID().toString(); data.setString(PROJECT_KEY, id);
        if (!id.equals(data.getString(PROJECT_KEY))) return "";
      }
      return uuid(id) ? id : "";
    } catch (RuntimeException error) { return ""; }
  }

  /** Project changes discard in-memory bookmarks before reading the new project's own record. */
  boolean refreshProject() {
    String next;
    try { next = Objects.toString(storage.project(), ""); }
    catch (RuntimeException error) { next = ""; }
    if (next.equals(project)) {
      if (!uuid(project)) problem = "프로젝트 저장을 사용할 수 없습니다. 현재 작업은 저장하지 않습니다.";
      return false;
    }
    boolean switched = !project.isEmpty();
    project = next; latest = null; pending = false; blocked = false; problem = "";
    if (!uuid(project)) { problem = "프로젝트 저장을 사용할 수 없습니다. 현재 작업은 저장하지 않습니다."; return true; }
    try {
      String raw = storage.read();
      if (raw != null && !raw.isBlank()) {
        Snapshot loaded = decode(raw);
        if (!loaded.project.equals(project)) throw new IllegalArgumentException("다른 프로젝트의 이어가기 기록입니다.");
        latest = loaded; pending = true;
      }
    } catch (RuntimeException error) {
      blocked = true; problem = "이어가기 기록을 읽을 수 없습니다. 기존 기록을 유지합니다. ‘새 작업으로 시작’으로 새 기록을 저장할 수 있습니다.";
    }
    if (switched && latest == null && !blocked) {
      pending = true;
      problem = "프로젝트가 바뀌었습니다. ‘새 작업으로 시작’을 선택한 뒤 현재 프로젝트의 작업 위치를 저장합니다.";
    }
    return true;
  }

  Snapshot snapshot() { refreshProject(); return latest; }
  boolean pending() { refreshProject(); return pending; }
  String problem() { refreshProject(); return problem; }
  String project() { refreshProject(); return project; }

  /** Merely navigating the default startup view must not erase an unresolved last-session record. */
  void capture(String feature, int subtab, String task, String original, String filters) {
    refreshProject();
    if (pending || blocked || !uuid(project)) return;
    Snapshot next = new Snapshot(project, feature, subtab, task, original, filters, System.currentTimeMillis());
    if (next.sameWork(latest)) return;
    if (!project.equals(storage.project())) { refreshProject(); return; }
    try { storage.write(encode(next)); latest = next; problem = ""; }
    catch (RuntimeException error) { problem = "작업 위치를 저장하지 못했습니다. 프로젝트 저장 상태를 확인하세요."; }
  }

  boolean current(Snapshot expected) {
    refreshProject();
    return expected != null && expected.equals(latest) && project.equals(expected.project);
  }

  /** Called after an explicit user choice. No execution or network action belongs in this model. */
  boolean accept(Snapshot expected) {
    if (!current(expected)) return false;
    pending = false; blocked = false; problem = ""; return true;
  }

  void startFresh() {
    refreshProject();
    pending = false; blocked = false; latest = null; problem = "";
  }

  static String encode(Snapshot snapshot) {
    return MetadataExport.json(Map.of("schema", 1, "project", snapshot.project, "feature", snapshot.feature, "subtab", snapshot.subtab,
        "task", snapshot.task, "original", snapshot.original, "filters", MinerJson.parse(snapshot.filters),
        "savedAt", snapshot.savedAt));
  }

  static Snapshot decode(String raw) {
    Object parsed = MinerJson.parse(raw, 32768, 200);
    if (!(parsed instanceof Map<?, ?> map) || !map.keySet().equals(Set.of("schema", "project", "feature", "subtab", "task", "original", "filters", "savedAt"))
        || !(map.get("schema") instanceof Number schema) || schema.doubleValue() != 1
        || !(map.get("savedAt") instanceof Number saved) || saved.doubleValue() != saved.longValue()
        || !(map.get("subtab") instanceof Number tab) || tab.doubleValue() != tab.intValue())
      throw new IllegalArgumentException("지원하는 이어가기 기록 형식이 아닙니다.");
    return new Snapshot(text(map, "project"), text(map, "feature"), tab.intValue(), text(map, "task"), text(map, "original"),
        MetadataExport.json(map.get("filters")), saved.longValue());
  }

  private static String text(Map<?, ?> map, String key) {
    if (!(map.get(key) instanceof String text) || text.length() > 128) throw new IllegalArgumentException("이어가기 항목 형식 오류");
    return text;
  }
  private static boolean uuid(String value) {
    return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }
  private static boolean validOriginal(String value) {
    int colon = value.lastIndexOf(':');
    return colon > 0 && uuid(value.substring(0, colon)) && value.substring(colon + 1).matches("[0-9]{1,18}");
  }

  /** Exact store UUID + sequence only; another request at the same URL is never a replacement. */
  static DiskTrafficStore.PacketRef original(DiskTrafficStore store, String id) {
    if (store == null || id == null || id.isEmpty()) return null;
    try {
      int colon = id.lastIndexOf(':');
      if (colon < 0) return null;
      long sequence = Long.parseLong(id.substring(colon + 1));
      if (sequence < 0 || sequence >= store.size()) return null;
      var ref = store.ref(sequence);
      return ref.id().equals(id) ? ref : null;
    } catch (RuntimeException error) { return null; }
  }
}
