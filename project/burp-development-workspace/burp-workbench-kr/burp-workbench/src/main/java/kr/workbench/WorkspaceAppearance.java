package kr.workbench;

import burp.api.montoya.MontoyaApi;
import java.awt.*;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;

/** Project-local display metadata only: never reads table values, editor text or packet bytes. */
final class WorkspaceAppearance implements AutoCloseable {
  static final String KEY = BuildInfo.storageKey("workspace-appearance-v1");
  static final int MAX_SURFACES = 96, MAX_COLUMNS = 64, MAX_JSON = 131072;
  interface Storage { String project(); String read(); void write(String value); }
  record Columns(String signature, List<Integer> order, List<Integer> widths) {
    Columns {
      order = List.copyOf(order); widths = List.copyOf(widths);
      int n = order.size();
      if (signature == null || !signature.matches("[0-9a-f]{24}") || n < 1 || n > MAX_COLUMNS || widths.size() != n)
        throw new IllegalArgumentException("열 설정 형식 오류");
      Set<Integer> indices = new HashSet<>(order);
      if (indices.size() != n || indices.stream().anyMatch(i -> i < 0 || i >= n)
          || widths.stream().anyMatch(i -> i < 24 || i > 2000)) throw new IllegalArgumentException("열 설정 범위 오류");
    }
  }
  record State(String project, int scale, boolean compact, boolean horizontal,
      Map<String, Columns> columns, Map<String, Double> ratios) {
    State {
      if (!uuid(project) || scale != 100 && scale != 125 && scale != 150
          || columns.size() > MAX_SURFACES || ratios.size() > MAX_SURFACES)
        throw new IllegalArgumentException("화면 설정 범위 오류");
      columns = Map.copyOf(columns); ratios = Map.copyOf(ratios);
      for (String id : columns.keySet()) if (!surfaceKey(id)) throw new IllegalArgumentException("열 식별자 오류");
      for (var entry : ratios.entrySet())
        if (!surfaceKey(entry.getKey()) || !Double.isFinite(entry.getValue()) || entry.getValue() < .1 || entry.getValue() > .9)
          throw new IllegalArgumentException("분할 비율 오류");
    }
    State display(int nextScale, boolean nextCompact, boolean nextHorizontal) {
      return new State(project, nextScale, nextCompact, nextHorizontal, columns, ratios);
    }
  }
  private static boolean uuid(String id) { return id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); }
  private static boolean surfaceKey(String id) { return id != null && id.matches("[ts]-[0-9a-f]{24}(?:-[01])?"); }
  static State defaults(String project, boolean horizontal) { return new State(project, 100, false, horizontal, Map.of(), Map.of()); }
  static String encode(State state) {
    Map<String,Object> columns = new TreeMap<>();
    state.columns.forEach((id, value) -> columns.put(id, Map.of("signature", value.signature, "order", value.order, "widths", value.widths)));
    return MetadataExport.json(Map.of("schema", 1, "project", state.project, "scale", state.scale,
        "compact", state.compact, "horizontal", state.horizontal, "columns", columns, "ratios", new TreeMap<>(state.ratios)));
  }
  static State decode(String raw) {
    Object parsed = MinerJson.parse(raw, MAX_JSON, 18000);
    if (!(parsed instanceof Map<?,?> map) || !map.keySet().equals(Set.of("schema", "project", "scale", "compact", "horizontal", "columns", "ratios"))
        || integer(map.get("schema")) != 1 || !(map.get("project") instanceof String project)
        || !(map.get("compact") instanceof Boolean compact) || !(map.get("horizontal") instanceof Boolean horizontal)
        || !(map.get("columns") instanceof Map<?,?> rawColumns) || !(map.get("ratios") instanceof Map<?,?> rawRatios)
        || rawColumns.size() > MAX_SURFACES || rawRatios.size() > MAX_SURFACES)
      throw new IllegalArgumentException("지원하는 화면 설정 형식이 아닙니다.");
    Map<String, Columns> columns = new HashMap<>(); Map<String, Double> ratios = new HashMap<>();
    for (var entry : rawColumns.entrySet()) {
      if (!(entry.getKey() instanceof String id) || !(entry.getValue() instanceof Map<?,?> column)
          || !column.keySet().equals(Set.of("signature", "order", "widths")) || !(column.get("signature") instanceof String signature)
          || !(column.get("order") instanceof List<?> order) || !(column.get("widths") instanceof List<?> widths))
        throw new IllegalArgumentException("열 설정 형식 오류");
      columns.put(id, new Columns(signature, order.stream().map(WorkspaceAppearance::integer).toList(), widths.stream().map(WorkspaceAppearance::integer).toList()));
    }
    for (var entry : rawRatios.entrySet()) {
      if (!(entry.getKey() instanceof String id) || !(entry.getValue() instanceof Number value)) throw new IllegalArgumentException("분할 설정 형식 오류");
      ratios.put(id, value.doubleValue());
    }
    return new State(project, integer(map.get("scale")), compact, horizontal, columns, ratios);
  }
  private static int integer(Object value) {
    if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) throw new IllegalArgumentException("정수 설정 오류");
    return number.intValue();
  }
  private static String digest(String input) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))).substring(0,24); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
  static String signature(JTable table) {
    StringBuilder names = new StringBuilder();
    for (int i = 0; i < table.getModel().getColumnCount(); i++) names.append(i).append(':').append(table.getModel().getColumnName(i)).append('\u0000');
    return digest(names.toString());
  }

  private final Storage storage;
  private final Component root;
  private final Runnable changed;
  private final IdentityHashMap<JTable, TableColumnModelListener> tables = new IdentityHashMap<>();
  private final IdentityHashMap<JSplitPane, java.beans.PropertyChangeListener> splits = new IdentityHashMap<>();
  private final IdentityHashMap<JTable, Columns> tableDefaults = new IdentityHashMap<>();
  private final IdentityHashMap<JSplitPane, Double> splitDefaults = new IdentityHashMap<>();
  private final javax.swing.Timer saveTimer;
  private String project, problem = "";
  private State state;
  private boolean applying, blocked, closed, initialHorizontal;
  private long nextScan;

  WorkspaceAppearance(Storage storage, Component root, boolean horizontal, Runnable changed) {
    this.storage = Objects.requireNonNull(storage); this.root = Objects.requireNonNull(root);
    this.initialHorizontal = horizontal; this.changed = changed == null ? () -> {} : changed;
    saveTimer = new javax.swing.Timer(600, event -> capture()); saveTimer.setRepeats(false);
    refreshProject();
  }
  static WorkspaceAppearance forApi(MontoyaApi api, Component root, boolean horizontal, Runnable changed) {
    return new WorkspaceAppearance(new Storage() {
      public String project() { return WorkspaceResume.projectIdentity(api); }
      public String read() { return api.persistence().extensionData().getString(KEY); }
      public void write(String value) { api.persistence().extensionData().setString(KEY, value); }
    }, root, horizontal, changed);
  }
  State state() { return state; }
  String problem() { return problem; }
  boolean blocked() { return blocked; }
  boolean refreshProject() {
    if (closed) return false;
    String next;
    try { next = Objects.toString(storage.project(), ""); } catch (RuntimeException error) { next = ""; }
    if (next.equals(project)) { discoverIfNeeded(); return false; }
    saveTimer.stop();
    applying = true;
    try { tableDefaults.forEach(WorkspaceAppearance::restoreColumns); } finally { applying = false; }
    detach(); project = next; state = null; blocked = false; problem = "";
    if (!uuid(project)) {
      problem = "프로젝트 저장을 사용할 수 없습니다. 화면 설정은 저장되지 않습니다.";
      WorkbenchUi.setAppearance(100, false); WorkbenchUi.style(root); changed.run(); return true;
    }
    state = defaults(project, initialHorizontal);
    try {
      String raw = storage.read();
      if (raw != null && !raw.isBlank()) {
        State loaded = decode(raw);
        if (!loaded.project.equals(project)) throw new IllegalArgumentException("다른 프로젝트의 화면 설정입니다.");
        state = loaded;
      }
    } catch (RuntimeException error) { blocked = true; problem = "저장된 화면 설정을 읽지 못했습니다. 기존 기록을 유지합니다. ‘화면 설정 초기화’로 복구하세요."; }
    apply(true); changed.run(); return true;
  }
  private void discoverIfNeeded() {
    if (state == null || System.nanoTime() < nextScan) return;
    nextScan = System.nanoTime() + 1_000_000_000L;
    boolean previous = applying; applying = true;
    try { discover(root, "root", false); } finally { applying = previous; }
  }
  void display(int scale, boolean compact, boolean horizontal) {
    refreshProject();
    if (state == null) return;
    capture();
    state = state.display(scale, compact, horizontal); apply(true); persist(); changed.run();
  }
  void resetSurfaces() {
    refreshProject(); if (state == null) return;
    state = new State(project, state.scale, state.compact, state.horizontal, Map.of(), Map.of());
    applying = true;
    try {
      for (var entry : tableDefaults.entrySet()) restoreColumns(entry.getKey(), entry.getValue());
      WorkbenchUi.packetLayout(root, state.horizontal);
      for (var entry : splitDefaults.entrySet())
        if (!managedSplit(entry.getKey())) WorkbenchUi.restoreSplit(entry.getKey(), entry.getValue());
    } finally { applying = false; }
    persist(); changed.run();
  }
  void resetAll() {
    refreshProject(); if (state == null) return;
    blocked = false; state = defaults(project, state.horizontal); resetSurfaces(); apply(true); persist(); changed.run();
  }
  private void apply(boolean orientation) {
    applying = true;
    try {
      WorkbenchUi.setAppearance(state.scale, state.compact);
      WorkbenchUi.style(root);
      if (orientation) WorkbenchUi.packetLayout(root, state.horizontal);
      discover(root, "root", true);
      if (root instanceof JComponent component) { component.revalidate(); component.repaint(); }
    } finally { applying = false; }
  }
  private String id(Component component, String path, char type) {
    return type + "-" + digest(path + "/" + Objects.toString(component.getName(), component.getClass().getSimpleName()));
  }
  private void discover(Component component, String path, boolean restoreNew) {
    if (component instanceof JComponent nativeComponent && Boolean.TRUE.equals(nativeComponent.getClientProperty("wb.nativeEditor"))) return;
    if (component instanceof JTable table && table.getColumnCount() > 0 && table.getColumnCount() <= MAX_COLUMNS) {
      String key = id(component, path, 't');
      table.putClientProperty("wb.appearance.id", key);
      if (!tables.containsKey(table) && tables.size() < MAX_SURFACES) {
        tableDefaults.put(table, captureColumns(table));
        if (state.columns.containsKey(key)) restoreColumns(table, state.columns.get(key));
        TableColumnModelListener listener = new TableColumnModelListener() {
          public void columnAdded(TableColumnModelEvent event) { schedule(); }
          public void columnRemoved(TableColumnModelEvent event) { schedule(); }
          public void columnMoved(TableColumnModelEvent event) { if (event.getFromIndex() != event.getToIndex()) schedule(); }
          public void columnMarginChanged(ChangeEvent event) { schedule(); }
          public void columnSelectionChanged(ListSelectionEvent event) { }
        };
        table.getColumnModel().addColumnModelListener(listener); tables.put(table, listener);
      } else if (restoreNew && state.columns.containsKey(key)) restoreColumns(table, state.columns.get(key));
      return;
    }
    if (component instanceof JSplitPane split) {
      String key = id(component, path, 's') + "-" + split.getOrientation();
      split.putClientProperty("wb.appearance.id", key);
      if (!splits.containsKey(split) && splits.size() < MAX_SURFACES) {
        Object ratio = split.getClientProperty("wb.split.ratio");
        splitDefaults.put(split, ratio instanceof Number n ? n.doubleValue() : .5);
        if (state.ratios.containsKey(key)) WorkbenchUi.restoreSplit(split, state.ratios.get(key));
        java.beans.PropertyChangeListener listener = event -> { if (event.getPropertyName().equals("wb.split.ratio")) schedule(); };
        split.addPropertyChangeListener(listener); splits.put(split, listener);
      } else if (restoreNew && state.ratios.containsKey(key)) WorkbenchUi.restoreSplit(split, state.ratios.get(key));
    }
    if (component instanceof Container parent) {
      Component[] children = parent.getComponents();
      for (int i = 0; i < children.length; i++) discover(children[i], path + "/" + i, restoreNew);
    }
  }
  private static boolean managedSplit(JSplitPane split) {
    String name = Objects.toString(split.getName(), "");
    Component a = split.getLeftComponent(), b = split.getRightComponent();
    return name.endsWith(".messages") || name.endsWith("workspace") || name.endsWith("lists") || name.equals("wb.payload.editors")
        || a != null && b != null && Objects.toString(a.getName(), "").contains("요청") && Objects.toString(b.getName(), "").contains("응답");
  }
  private void schedule() { if (!applying && !closed && state != null && !blocked) saveTimer.restart(); }
  void capture() {
    if (closed || applying || state == null || blocked) return;
    String expected = project;
    try { if (!Objects.equals(expected, storage.project())) { refreshProject(); return; } }
    catch (RuntimeException error) { problem = "프로젝트 저장 상태를 확인하지 못했습니다. 화면 설정을 저장하지 않습니다."; changed.run(); return; }
    Map<String, Columns> columns = new HashMap<>(state.columns); Map<String, Double> ratios = new HashMap<>(state.ratios);
    for (JTable table : tables.keySet()) {
      String key = Objects.toString(table.getClientProperty("wb.appearance.id"), "");
      if (surfaceKey(key) && (columns.size() < MAX_SURFACES || columns.containsKey(key))) {
        Columns value = captureColumns(table); if (value != null) columns.put(key, value);
      }
    }
    for (JSplitPane split : splits.keySet()) {
      String key = Objects.toString(split.getClientProperty("wb.appearance.id"), ""); Object ratio = split.getClientProperty("wb.split.ratio");
      if (surfaceKey(key) && (ratios.size() < MAX_SURFACES || ratios.containsKey(key)) && ratio instanceof Number number)
        ratios.put(key, Math.max(.1, Math.min(.9, number.doubleValue())));
    }
    State next = new State(expected, state.scale, state.compact, state.horizontal, columns, ratios);
    if (!next.equals(state)) { state = next; persist(); changed.run(); }
  }
  private static Columns captureColumns(JTable table) {
    int n = table.getColumnCount(); if (n < 1 || n > MAX_COLUMNS || n != table.getModel().getColumnCount()) return null;
    List<Integer> order = new ArrayList<>(), widths = new ArrayList<>();
    for (int i = 0; i < n; i++) { TableColumn column = table.getColumnModel().getColumn(i); order.add(column.getModelIndex()); widths.add(Math.max(24,Math.min(2000,column.getWidth()))); }
    return new Columns(signature(table), order, widths);
  }
  private static void restoreColumns(JTable table, Columns columns) {
    if (columns == null || table.getColumnCount() != columns.order.size() || !signature(table).equals(columns.signature)) return;
    for (int i = 0; i < columns.order.size(); i++) {
      int wanted = columns.order.get(i), index = table.convertColumnIndexToView(wanted);
      if (index >= 0 && index != i) table.moveColumn(index, i);
      TableColumn column = table.getColumnModel().getColumn(i); int width = columns.widths.get(i);
      column.setPreferredWidth(width); column.setWidth(width);
    }
  }
  private void persist() {
    if (state == null || blocked) return;
    try {
      if (!project.equals(storage.project())) { refreshProject(); return; }
      String encoded = encode(state);
      if (encoded.length() > MAX_JSON) throw new IllegalArgumentException("화면 설정 저장 크기 제한");
      storage.write(encoded);
      if (!project.equals(storage.project())) { refreshProject(); return; }
      if (!encoded.equals(storage.read())) throw new IllegalStateException("화면 설정 저장 확인 실패");
      problem = "";
    } catch (RuntimeException error) { problem = "화면 설정을 저장하지 못했습니다. 현재 표시에는 적용되며 재시작 후 복원은 보장되지 않습니다."; }
  }
  private void detach() {
    tables.forEach((table, listener) -> table.getColumnModel().removeColumnModelListener(listener));
    splits.forEach(Component::removePropertyChangeListener);
    tables.clear(); splits.clear(); tableDefaults.clear(); splitDefaults.clear();
  }
  public void close() { if (closed) return; saveTimer.stop(); capture(); closed = true; detach(); }
}
