package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedObject;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** Project-local authored prose only. Never captures HTTP packets, source URLs or account objects. */
final class EvidenceDrafts {
  static final String KEY = BuildInfo.storageKey("evidence-text-draft-v1");
  static final int MAX_BYTES = 128 * 1024, MAX_ITEMS = 32;
  static final int TITLE_LIMIT = 256, IMPACT_LIMIT = 16 * 1024, REPRO_LIMIT = 32 * 1024;
  static final int NAME_LIMIT = 256, NOTE_LIMIT = 4 * 1024;
  private static final long MAX_TIME = 253402300799999L;
  private static final Object WRITE_LOCK = new Object();

  record Annotation(String name, String note) {
    Annotation {
      text(name, NAME_LIMIT, "증거 이름"); text(note, NOTE_LIMIT, "관찰 메모");
    }
  }
  record Content(String title, String impact, String repro, Map<String, Annotation> annotations) {
    Content {
      text(title, TITLE_LIMIT, "제목"); text(impact, IMPACT_LIMIT, "영향"); text(repro, REPRO_LIMIT, "재현 절차");
      if (annotations == null || annotations.size() > MAX_ITEMS)
        throw new IllegalArgumentException("증거별 메모는 최대 32개까지 저장합니다.");
      Map<String, Annotation> checked = new LinkedHashMap<>();
      annotations.forEach((id, value) -> {
        if (!uuid(id) || value == null) throw new IllegalArgumentException("증거별 메모의 원본 식별자를 확인하세요.");
        checked.put(id, value);
      });
      annotations = Collections.unmodifiableMap(checked);
    }
    static Content empty() { return new Content("", "", "", Map.of()); }
  }
  record Snapshot(String project, Content content, long savedAt) {
    Snapshot {
      if (!uuid(project) || content == null || savedAt < 0 || savedAt > MAX_TIME)
        throw new IllegalArgumentException("증거 초안의 프로젝트·저장 시각을 확인하세요.");
    }
  }

  interface Storage {
    String project();
    String read();
    void write(String value);
    default String read(String expectedProject) {
      requireProject(expectedProject, project());
      String value = read(); requireProject(expectedProject, project()); return value;
    }
    default void write(String expectedProject, String previous, String value) {
      synchronized (WRITE_LOCK) {
        requireProject(expectedProject, project());
        if (!Objects.equals(previous, read(expectedProject))) throw new Conflict();
        requireProject(expectedProject, project()); write(value);
        requireProject(expectedProject, project());
        if (!Objects.equals(value, read(expectedProject)))
          throw new IllegalArgumentException("증거 초안 저장 결과를 확인하지 못했습니다.");
      }
    }
  }
  private static final class Conflict extends IllegalArgumentException {
    Conflict() { super("다른 화면에서 증거 초안이 변경됐습니다. 기존 초안을 다시 불러오세요."); }
  }

  private final Storage storage;
  private final LongSupplier clock;
  private String project = "", lastRaw, problem = "";
  private Snapshot latest;
  private boolean blocked;

  EvidenceDrafts(Storage storage) { this(storage, System::currentTimeMillis); }
  EvidenceDrafts(Storage storage, LongSupplier clock) {
    this.storage = Objects.requireNonNull(storage); this.clock = Objects.requireNonNull(clock); refreshProject();
  }

  static EvidenceDrafts forApi(MontoyaApi api) {
    return new EvidenceDrafts(new Storage() {
      public String project() { return WorkspaceResume.projectIdentity(api); }
      private PersistedObject data(String expected) {
        if (api == null || api.persistence() == null)
          throw new IllegalArgumentException("프로젝트 저장을 사용할 수 없습니다.");
        PersistedObject data = api.persistence().extensionData();
        if (data == null) throw new IllegalArgumentException("프로젝트 저장을 사용할 수 없습니다.");
        requireProject(expected, data.getString(WorkspaceResume.PROJECT_KEY)); return data;
      }
      public String read() { return read(project()); }
      public void write(String value) { String expected = project(); write(expected, read(expected), value); }
      public String read(String expected) {
        PersistedObject data = data(expected);
        String value = data.getString(KEY);
        requireProject(expected, data.getString(WorkspaceResume.PROJECT_KEY));
        requireProject(expected, project()); return value;
      }
      public void write(String expected, String previous, String value) {
        synchronized (WRITE_LOCK) {
          // Keep the project-owned handle rather than resolving extensionData again at the write.
          PersistedObject data = data(expected);
          if (!Objects.equals(previous, data.getString(KEY))) throw new Conflict();
          requireProject(expected, data.getString(WorkspaceResume.PROJECT_KEY));
          requireProject(expected, project()); data.setString(KEY, value);
          requireProject(expected, data.getString(WorkspaceResume.PROJECT_KEY));
          requireProject(expected, project());
          if (!Objects.equals(value, data.getString(KEY)))
            throw new IllegalArgumentException("증거 초안 저장 결과를 확인하지 못했습니다.");
        }
      }
    });
  }

  /** Returns true when a new project's own record has replaced the former in-memory record. */
  synchronized boolean refreshProject() {
    String next;
    try { next = Objects.toString(storage.project(), ""); }
    catch (RuntimeException failure) { next = ""; }
    if (next.equals(project)) {
      if (!uuid(project)) { blocked = true; problem = "프로젝트 저장을 사용할 수 없습니다. 작성 내용은 자동 저장되지 않습니다."; }
      return false;
    }
    project = next; latest = null; lastRaw = null; blocked = false; problem = "";
    if (!uuid(project)) { blocked = true; problem = "프로젝트 저장을 사용할 수 없습니다. 작성 내용은 자동 저장되지 않습니다."; return true; }
    try {
      lastRaw = storage.read(project);
      if (lastRaw != null && !lastRaw.isBlank()) {
        Snapshot loaded = decode(lastRaw);
        if (!project.equals(loaded.project)) throw new IllegalArgumentException("다른 프로젝트의 증거 초안입니다.");
        latest = loaded;
      }
    } catch (RuntimeException failure) {
      blocked = true; problem = "증거 초안을 읽지 못했습니다. 기존 기록은 보존됩니다. 다시 불러오거나 ‘새 초안으로 시작’을 선택하세요.";
    }
    return true;
  }

  synchronized String project() { refreshProject(); return project; }
  synchronized Snapshot snapshot() { refreshProject(); return latest; }
  synchronized long savedAt() { refreshProject(); return latest == null ? 0 : latest.savedAt; }
  synchronized boolean blocked() { refreshProject(); return blocked; }
  synchronized String problem() { refreshProject(); return problem; }

  /** The editor's captured project is mandatory: a queued save must never adopt a new project. */
  synchronized boolean save(String expectedProject, Content content) {
    refreshProject();
    if (!uuid(expectedProject) || !project.equals(expectedProject)) {
      problem = "프로젝트가 바뀌어 이전 화면의 증거 초안을 저장하지 않았습니다."; return false;
    }
    if (blocked) return false;
    try {
      Objects.requireNonNull(content);
      if (!Objects.equals(lastRaw, storage.read(expectedProject))) throw new Conflict();
      if (latest != null && latest.content.equals(content)) { problem = ""; return true; }
      long now = clock.getAsLong();
      Snapshot next = new Snapshot(expectedProject, content, now);
      String encoded = encode(next);
      storage.write(expectedProject, lastRaw, encoded);
      latest = next; lastRaw = encoded; problem = ""; return true;
    } catch (Conflict conflict) {
      blocked = true; problem = conflict.getMessage(); return false;
    } catch (RuntimeException failure) {
      problem = "증거 초안을 저장하지 못했습니다. 작성 내용은 화면에 유지됩니다. 프로젝트 저장 상태와 초안 크기를 확인하세요."; return false;
    }
  }

  /** Explicit reload discards no durable bytes and may surface a concurrent writer's newer record. */
  synchronized void reload() {
    project = ""; latest = null; lastRaw = null; blocked = false; problem = ""; refreshProject();
  }

  /** Explicit replacement permission; durable bytes are changed only after the next successful save. */
  synchronized boolean resetCorrupt(String expectedProject) {
    refreshProject();
    if (!uuid(expectedProject) || !project.equals(expectedProject)) return false;
    try {
      lastRaw = storage.read(expectedProject); latest = null; blocked = false; problem = ""; return true;
    } catch (RuntimeException failure) {
      blocked = true; problem = "기존 초안 상태를 확인하지 못해 새 초안으로 바꾸지 않았습니다."; return false;
    }
  }

  static String encode(Snapshot value) {
    Map<String, Object> items = new LinkedHashMap<>();
    value.content.annotations.forEach((id, note) -> items.put(id, Map.of("name", note.name, "note", note.note)));
    String encoded = MetadataExport.json(Map.of("schema", 1, "project", value.project,
        "title", value.content.title, "impact", value.content.impact, "repro", value.content.repro,
        "annotations", items, "savedAt", value.savedAt));
    if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IllegalArgumentException("증거 초안은 UTF-8 기준 128 KiB 이내로 저장합니다.");
    return encoded;
  }

  static Snapshot decode(String raw) {
    if (raw == null || raw.length() > MAX_BYTES || raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IllegalArgumentException("증거 초안 크기 제한");
    Object decoded = MinerJson.parse(raw, MAX_BYTES, 512);
    if (!(decoded instanceof Map<?, ?> map) || !map.keySet().equals(Set.of("schema", "project", "title", "impact", "repro", "annotations", "savedAt"))
        || !(map.get("schema") instanceof Number schema) || schema.doubleValue() != 1
        || !(map.get("savedAt") instanceof Number time) || time.doubleValue() != time.longValue()
        || !(map.get("annotations") instanceof Map<?, ?> notes) || notes.size() > MAX_ITEMS)
      throw new IllegalArgumentException("지원하는 증거 초안 형식이 아닙니다.");
    Map<String, Annotation> items = new LinkedHashMap<>();
    notes.forEach((id, item) -> {
      if (!(id instanceof String key) || !(item instanceof Map<?, ?> note)
          || !note.keySet().equals(Set.of("name", "note")))
        throw new IllegalArgumentException("증거별 메모 형식을 확인하세요.");
      items.put(key, new Annotation(string(note, "name"), string(note, "note")));
    });
    return new Snapshot(string(map, "project"),
        new Content(string(map, "title"), string(map, "impact"), string(map, "repro"), items), time.longValue());
  }

  private static String string(Map<?, ?> map, String name) {
    if (!(map.get(name) instanceof String value)) throw new IllegalArgumentException("증거 초안의 문자열 형식을 확인하세요.");
    return value;
  }
  private static void text(String value, int limit, String name) {
    if (value == null || value.length() > limit)
      throw new IllegalArgumentException(name + "은 " + limit + "자 이내로 작성하세요.");
  }
  private static boolean uuid(String value) {
    return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }
  private static void requireProject(String expected, String actual) {
    if (!uuid(expected) || !expected.equals(actual))
      throw new IllegalArgumentException("증거 초안의 프로젝트가 바뀌었습니다.");
  }
}
