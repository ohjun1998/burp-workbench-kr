package kr.workbench;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.TableModel;

/**
 * External before/after probe. Compile this file only into a separate /tmp directory.
 * Runtime arguments: before|after output.json [1000..3000 rows, default 2000].
 * Uses production Swing components and two actual loopback HTTP responses through the
 * existing test adapter. Result metadata is synthetic repeated observation load, not
 * native Burp execution, distinct endpoint coverage, or a correctness-rate benchmark.
 */
public final class InspectionUpdateProbe extends WorkbenchTest {
  private static final int WARMUPS = 2, SAMPLES = 5;
  private static final String[] CATEGORIES = {"SECRET", "API", "ENVIRONMENT", "RESPONSE", "ACCESS",
      "COVERAGE", "KATANA", "INTERNAL", "INVENTORY", "DATABASE"};
  private enum Scenario { SAME_SNAPSHOT, ONE_FINDING_CHANGED, SORTED_FINDING_CHANGED, REORDERED_PATHS, SORT_ONLY }
  private record Data(ScopeInspection.Snapshot base, ScopeInspection.Snapshot changed,
      ScopeInspection.Snapshot reversed, String targetId, String targetAccount) {}
  private record Measurement(double edtWallMs, Long edtAllocatedBytes, Map<String,Object> events,
      Map<String,Boolean> invariants) {}

  private static final class Counter implements TableModelListener {
    long total, inserted, updated, deleted, structure, fullData, affectedRows, replacements;
    @Override public void tableChanged(TableModelEvent event) {
      total++;
      if (event.getFirstRow() == TableModelEvent.HEADER_ROW) { structure++; return; }
      if (event.getType() == TableModelEvent.INSERT) inserted++;
      else if (event.getType() == TableModelEvent.DELETE) deleted++;
      else updated++;
      if (event.getFirstRow() == 0 && event.getLastRow() == Integer.MAX_VALUE) fullData++;
      else if (event.getLastRow() >= event.getFirstRow()) affectedRows += (long)event.getLastRow() - event.getFirstRow() + 1;
    }
    void reset() { total = inserted = updated = deleted = structure = fullData = affectedRows = replacements = 0; }
    Map<String,Object> json() {
      var out = new LinkedHashMap<String,Object>();
      out.put("total", total); out.put("insert", inserted); out.put("update", updated);
      out.put("delete", deleted); out.put("structure", structure); out.put("fullData", fullData);
      out.put("affectedRowsExcludingFullData", affectedRows); out.put("modelReplacements", replacements);
      return out;
    }
  }

  /** Follow model replacement as well as events; replacing a model cannot fake a zero-event result. */
  private static final class Events {
    final Map<String,Counter> counters = new LinkedHashMap<>();
    Events(ScopeInspectionPanel panel) {
      panel.tables.forEach((key, table) -> {
        var counter = new Counter(); counters.put(key, counter);
        table.getModel().addTableModelListener(counter);
        table.addPropertyChangeListener("model", event -> {
          ((TableModel)event.getOldValue()).removeTableModelListener(counter);
          ((TableModel)event.getNewValue()).addTableModelListener(counter);
          counter.replacements++;
        });
      });
    }
    void reset() { counters.values().forEach(Counter::reset); }
    Map<String,Object> json() {
      var out = new LinkedHashMap<String,Object>();
      long total = 0, structure = 0, replacements = 0;
      for (var entry : counters.entrySet()) {
        out.put(entry.getKey(), entry.getValue().json()); total += entry.getValue().total;
        structure += entry.getValue().structure; replacements += entry.getValue().replacements;
      }
      return Map.of("total", total, "structure", structure, "modelReplacements", replacements, "byTable", out);
    }
  }

  private static final class Allocations {
    final com.sun.management.ThreadMXBean bean;
    final String capability;
    Allocations() {
      var platform = ManagementFactory.getThreadMXBean();
      com.sun.management.ThreadMXBean usable = null;
      String status = "Thread allocation measurement is unsupported";
      if (platform instanceof com.sun.management.ThreadMXBean candidate && candidate.isThreadAllocatedMemorySupported()) try {
        if (!candidate.isThreadAllocatedMemoryEnabled()) candidate.setThreadAllocatedMemoryEnabled(true);
        if (candidate.isThreadAllocatedMemoryEnabled()) { usable = candidate; status = "supported and enabled"; }
        else status = "supported but disabled";
      } catch (RuntimeException unavailable) { status = "supported but unavailable: " + unavailable.getClass().getSimpleName(); }
      bean = usable; capability = status;
    }
    long bytes() { return bean == null ? -1 : bean.getThreadAllocatedBytes(Thread.currentThread().getId()); }
  }

  private static <T> T onEdt(java.util.function.Supplier<T> action) throws Exception {
    var result = new AtomicReference<T>(); edt(() -> result.set(action.get())); return result.get();
  }
  private static ScopeInspection.Snapshot snapshot(String id, List<ScopeInspection.Finding> findings, List<ScopeInspection.Page> pages) {
    return new ScopeInspection.Snapshot(id, ScopeInspection.State.READY, "합성 반복 관측 부하 · 실제 분석 판정 아님",
        2, 2, 0, 0, 0, 0, findings, pages, List.of(), 100, 0);
  }

  private static Data fixtureData(ScopeInspectionFixture fixture, DiskTrafficStore store, int count) {
    String url = fixture.origin + "/app/private";
    var anonymousRequest = ScopeInspectionFixture.wireRequest(url, "GET", List.of(), new byte[0]);
    var aliceRequest = ScopeInspectionFixture.wireRequest(url, "GET", List.of(header("Cookie", ScopeInspectionFixture.SESSION)), new byte[0]);
    var anonymous = fixture.api.http().sendRequest(anonymousRequest);
    var alice = fixture.api.http().sendRequest(aliceRequest);
    if (anonymous.response().statusCode() != 403 || alice.response().statusCode() != 200)
      throw new AssertionError("Loopback HTTP originals did not retain expected separate account responses");
    var findings = new ArrayList<ScopeInspection.Finding>();
    var pages = new ArrayList<ScopeInspection.Page>();
    int targetIndex = -1;
    for (int i = 0; i < count; i++) {
      boolean asAlice = (i & 1) == 0;
      var original = asAlice ? alice : anonymous;
      String account = asAlice ? "Alice" : "비로그인";
      var packet = store.append("synthetic-" + i, "합성 반복 관측 #" + i, original.request(), original.response());
      String category = i < 2 ? "SECRET" : CATEGORIES[(i - 2) % CATEGORIES.length];
      findings.add(new ScopeInspection.Finding(category, String.format(java.util.Locale.ROOT, "합성 근거 %05d", i),
          url, "GET", account, "합성 예제", "INFO", "수동 검토", "실제 loopback 원본을 연결한 합성 근거입니다. 판정·위치는 부하용 예제입니다.",
          packet.id(), i + 1, i, i < 2 ? "same-fingerprint-two-accounts" : "synthetic-fingerprint-" + i));
      pages.add(new ScopeInspection.Page(url, "GET", account, original.response().statusCode(), "JSON", true,
          "합성 반복 관측 메타데이터", "synthetic-digest-" + i, packet));
      if (targetIndex < 0 && i >= count / 2 && category.equals("SECRET")) targetIndex = i;
    }
    // Duplicate finding keys are legal input; account/duplicate rows must remain present.
    findings.set(count - 1, findings.get(0));
    var last = pages.get(count - 1);
    pages.set(count - 1, new ScopeInspection.Page(last.url(), last.method(), "원본 미연결", last.status(), last.classification(),
        false, "합성 원본 미연결 메타데이터", last.digest(), null));
    var base = snapshot("inspection-update-load", findings, pages);
    var changed = new ArrayList<>(findings);
    var target = findings.get(targetIndex);
    changed.set(targetIndex, new ScopeInspection.Finding(target.category(), "~ 한 항목 제목 변경", target.url(), target.method(), target.account(),
        target.confidence(), target.severity(), target.state(), target.detail(), target.originalId(), target.line(), target.offset(), target.fingerprint()));
    var reverseFindings = new ArrayList<>(findings); Collections.reverse(reverseFindings);
    var reversePages = new ArrayList<>(pages); Collections.reverse(reversePages);
    return new Data(base, snapshot(base.id(), changed, pages), snapshot(base.id(), reverseFindings, reversePages), target.originalId(), target.account());
  }

  private static boolean matches(String key, String category) {
    return switch (key) {
      case "summary" -> true;
      case "environment" -> List.of("ENVIRONMENT", "DATABASE").contains(category);
      case "api" -> List.of("API", "INVENTORY").contains(category);
      case "secrets" -> category.equals("SECRET");
      case "observations" -> List.of("INTERNAL", "RESPONSE").contains(category);
      case "access" -> List.of("ACCESS", "ACCOUNT", "PERMISSION").contains(category);
      case "coverage" -> category.equals("COVERAGE");
      case "katana" -> category.equals("KATANA");
      default -> false;
    };
  }
  private static void tab(ScopeInspectionPanel panel, String key) {
    for (int i = 0; i < panel.pages.getTabCount(); i++)
      if (("wb.scope.inspection.tab." + key).equals(panel.pages.getComponentAt(i).getName())) { panel.pages.setSelectedIndex(i); return; }
    throw new AssertionError("Missing inspection tab: " + key);
  }
  private static void select(ScopeInspectionPanel panel, String key, String originalId) {
    tab(panel, key); JTable table = panel.tables.get(key);
    int model = -1;
    if (key.equals("paths")) {
      for (int i = 0; i < panel.current.pages().size(); i++) {
        var original = panel.current.pages().get(i).original();
        if (original != null && original.id().equals(originalId)) { model = i; break; }
      }
    } else {
      var values = panel.rows.getOrDefault(key, List.of());
      for (int i = 0; i < values.size(); i++) if (values.get(i).originalId().equals(originalId)) { model = i; break; }
    }
    if (model < 0) throw new AssertionError("Cannot select original-linked row in " + key);
    table.setRowSelectionInterval(table.convertRowIndexToView(model), table.convertRowIndexToView(model));
  }
  private static Map<String,List<Integer>> widths(ScopeInspectionPanel panel) {
    var result = new LinkedHashMap<String,List<Integer>>();
    panel.tables.forEach((key, table) -> {
      var values = new ArrayList<Integer>();
      for (int i = 0; i < table.getColumnCount(); i++) values.add(table.getColumnModel().getColumn(i).getPreferredWidth());
      result.put(key, values);
    });
    return result;
  }

  /** Validate every hidden model as well as visible sorted selection; validation is outside the timed region. */
  private static Map<String,Boolean> verify(ScopeInspectionPanel panel, ScopeInspection.Snapshot expected, String targetId,
      String targetAccount, String key, List<? extends RowSorter.SortKey> sortKeys, Map<String,List<Integer>> previousWidths) {
    boolean allCounts = true, allRows = true;
    for (var entry : panel.tables.entrySet()) {
      TableModel model = entry.getValue().getModel(); String tableKey = entry.getKey();
      if (tableKey.equals("browser")) { allCounts &= model.getRowCount() == 0; continue; }
      if (tableKey.equals("paths")) {
        allCounts &= model.getRowCount() == expected.pages().size();
        if (model.getRowCount() == expected.pages().size()) for (int i = 0; i < expected.pages().size(); i++) {
          var page = expected.pages().get(i);
          allRows &= Objects.equals(model.getValueAt(i, 0), page.account()) && Objects.equals(model.getValueAt(i, 1), page.method())
              && Objects.equals(model.getValueAt(i, 2), ScopeInspectionAnalysis.safeUrl(page.url())) && Objects.equals(model.getValueAt(i, 3), page.status());
        }
      } else {
        var rows = expected.findings().stream().filter(f -> matches(tableKey, f.category())).toList();
        allCounts &= model.getRowCount() == rows.size();
        if (model.getRowCount() == rows.size()) for (int i = 0; i < rows.size(); i++) {
          var finding = rows.get(i);
          allRows &= Objects.equals(model.getValueAt(i, 1), finding.title()) && Objects.equals(model.getValueAt(i, 2), finding.account())
              && Objects.equals(model.getValueAt(i, 6), finding.url());
        }
      }
    }
    var selected = panel.selectedOriginal();
    boolean original = selected != null && selected.id().equals(targetId);
    boolean account = key.equals("paths") ? original : panel.selectedFinding() != null && panel.selectedFinding().account().equals(targetAccount);
    var result = new LinkedHashMap<String,Boolean>();
    result.put("allTableCountsExact", allCounts); result.put("allModelRowsInSourceOrder", allRows);
    result.put("exactOriginalSelectionPreserved", original); result.put("selectedAccountPreserved", account);
    result.put("sortKeysPreserved", panel.tables.get(key).getRowSorter().getSortKeys().equals(sortKeys));
    result.put("columnWidthsPreserved", widths(panel).equals(previousWidths));
    result.put("noStructureEvents", true); // Filled from the measurement recorder by the caller.
    return result;
  }

  private static Measurement measure(ScopeInspectionPanel panel, Data data, Scenario scenario, Events events, Allocations allocations) {
    String key = scenario == Scenario.REORDERED_PATHS ? "paths" : "secrets";
    panel.accept(data.base());
    JTable table = panel.tables.get(key);
    List<RowSorter.SortKey> beforeSort = scenario == Scenario.SORTED_FINDING_CHANGED || scenario == Scenario.REORDERED_PATHS || scenario == Scenario.SORT_ONLY
        ? List.of(new RowSorter.SortKey(key.equals("paths") ? 0 : 1, SortOrder.ASCENDING)) : List.of();
    table.getRowSorter().setSortKeys(beforeSort); select(panel, key, data.targetId());
    var beforeWidths = widths(panel);
    events.reset();
    long allocated = allocations.bytes(), started = System.nanoTime();
    ScopeInspection.Snapshot expected = data.base();
    if (scenario == Scenario.ONE_FINDING_CHANGED || scenario == Scenario.SORTED_FINDING_CHANGED) {
      expected = data.changed(); panel.accept(expected);
    } else if (scenario == Scenario.REORDERED_PATHS) {
      expected = data.reversed(); panel.accept(expected);
    } else if (scenario == Scenario.SORT_ONLY) table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.DESCENDING)));
    else panel.accept(expected);
    long elapsed = System.nanoTime() - started, afterAllocated = allocations.bytes();
    var recorded = events.json();
    var expectedSort = scenario == Scenario.SORT_ONLY ? List.of(new RowSorter.SortKey(1, SortOrder.DESCENDING)) : beforeSort;
    var invariants = verify(panel, expected, data.targetId(), data.targetAccount(), key, expectedSort, beforeWidths);
    invariants.put("noStructureEvents", ((Number)recorded.get("structure")).longValue() == 0);
    return new Measurement(elapsed / 1_000_000.0, allocated < 0 || afterAllocated < 0 ? null : afterAllocated - allocated, recorded, invariants);
  }
  private static Map<String,Object> sample(Measurement value, int index) {
    var out = new LinkedHashMap<String,Object>();
    out.put("index", index); out.put("edtWallMs", value.edtWallMs()); out.put("edtAllocatedBytes", value.edtAllocatedBytes());
    out.put("modelEvents", value.events()); out.put("invariants", value.invariants()); return out;
  }
  private static Map<String,Object> stats(List<Double> values) {
    var sorted = values.stream().sorted().toList();
    return Map.of("median", sorted.get(sorted.size() / 2), "min", sorted.get(0), "max", sorted.get(sorted.size() - 1));
  }
  private static String runtimeVersion() throws Exception {
    var field = BuildInfo.class.getDeclaredField("VERSION"); field.setAccessible(true); return Objects.toString(field.get(null), "");
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2) throw new IllegalArgumentException("usage: InspectionUpdateProbe before|after output.json [rows=2000]");
    int count = args.length > 2 ? Integer.parseInt(args[2]) : 2000;
    if (count < 1000 || count > 3000) throw new IllegalArgumentException("rows must be between 1000 and 3000");
    Path output = Path.of(args[1]).toAbsolutePath(); Files.createDirectories(output.getParent());
    var document = new LinkedHashMap<String,Object>();
    document.put("phase", args[0]); document.put("createdAtUtc", Instant.now().toString());
    document.put("environment", "Production Swing panel with a Montoya test adapter and two actual isolated loopback HTTP responses; NOT native Burp GUI");
    document.put("load", Map.of("syntheticFindings", count, "syntheticPages", count,
        "distinctHTTPRequestsForFixtures", 2, "description", "Repeated original-linked synthetic observations, not distinct endpoint discovery or vulnerability findings"));
    document.put("warmupsPerScenario", WARMUPS); document.put("samplesPerScenario", SAMPLES);
    document.put("measurementBoundary", "EDT action only: accept(snapshot) or sort-only; fixture construction, preparation, initial sort, validation and invokeAndWait waiting are excluded");
    document.put("allocationMeaning", "ThreadMXBean allocated bytes on EDT during the action; not retained heap, process RSS, background allocations or peak memory");
    document.put("summaryRule", "Two warmups are recorded separately and excluded from five-sample median/min/max; no timing pass/fail threshold");
    document.put("javaVersion", System.getProperty("java.version")); document.put("runtimeVersion", runtimeVersion());
    document.put("productCodeSource", ScopeInspectionPanel.class.getProtectionDomain().getCodeSource().getLocation().toString());
    document.put("harnessCodeSource", InspectionUpdateProbe.class.getProtectionDomain().getCodeSource().getLocation().toString());
    document.put("classOrigins", Map.of("ScopeInspectionPanel", ScopeInspectionPanel.class.getProtectionDomain().getCodeSource().getLocation().toString(),
        "DiskTrafficStore", DiskTrafficStore.class.getProtectionDomain().getCodeSource().getLocation().toString(),
        "KeyedTableRows", KeyedTableRows.class.getProtectionDomain().getCodeSource().getLocation().toString(),
        "WorkbenchTest", WorkbenchTest.class.getProtectionDomain().getCodeSource().getLocation().toString(),
        "ScopeInspectionFixture", ScopeInspectionFixture.class.getProtectionDomain().getCodeSource().getLocation().toString()));
    var allocations = new Allocations(); document.put("threadAllocationSupported", allocations.bean != null);
    document.put("threadAllocationCapability", allocations.capability);
    var originalAction = new AtomicReference<String>();
    var failures = new ArrayList<String>();
    var results = new LinkedHashMap<String,Object>();
    try (var fixture = new ScopeInspectionFixture(); var store = new DiskTrafficStore()) {
      Data data = fixtureData(fixture, store, count);
      var scope = WorkbenchScope.install(fixture.api); scope.add(fixture.origin + "/app/", true);
      var panel = onEdt(() -> new ScopeInspectionPanel(fixture.api, () -> List.of(new ScopeInspection.Profile("anonymous", "비로그인", Map.of(), null)),
          new ScopeInspectionPanel.Actions() {
            public void original(DiskTrafficStore.PacketRef packet) { originalAction.set(packet.id()); }
            public void repeater(DiskTrafficStore.PacketRef packet) {}
            public void compare(DiskTrafficStore.PacketRef packet) {}
            public void evidence(List<DiskTrafficStore.PacketRef> packets, String note) {}
            public void tool(String id, DiskTrafficStore.PacketRef packet) {}
          }));
      int requestsBefore = fixture.visits.size();
      try {
        edt(() -> {
          try { ((javax.swing.Timer)get(panel, "updates")).stop(); }
          catch (Exception error) { throw new AssertionError("Cannot isolate the panel's publication timer", error); }
        });
        document.put("panelPublicationTimerStopped", true);
        Events events = onEdt(() -> new Events(panel));
        for (Scenario scenario : Scenario.values()) {
          var warmups = new ArrayList<Map<String,Object>>(); var samples = new ArrayList<Map<String,Object>>();
          var times = new ArrayList<Double>(); var bytes = new ArrayList<Double>(); var eventTotals = new ArrayList<Double>();
          for (int i = 0; i < WARMUPS + SAMPLES; i++) {
            Measurement measurement = onEdt(() -> measure(panel, data, scenario, events, allocations));
            boolean warmup = i < WARMUPS;
            (warmup ? warmups : samples).add(sample(measurement, warmup ? i : i - WARMUPS));
            for (var invariant : measurement.invariants().entrySet()) if (!invariant.getValue())
              failures.add(scenario + "/" + (warmup ? "warmup-" + i : "sample-" + (i - WARMUPS)) + ": " + invariant.getKey());
            if (!warmup) {
              times.add(measurement.edtWallMs());
              if (measurement.edtAllocatedBytes() != null) bytes.add(measurement.edtAllocatedBytes().doubleValue());
              eventTotals.add(((Number)measurement.events().get("total")).doubleValue());
            }
          }
          var result = new LinkedHashMap<String,Object>();
          result.put("warmupsExcludedFromSummary", warmups); result.put("samples", samples);
          result.put("edtWallMs", stats(times)); result.put("edtAllocatedBytes", bytes.isEmpty() ? null : stats(bytes));
          result.put("modelEventCount", stats(eventTotals)); results.put(scenario.name().toLowerCase(java.util.Locale.ROOT), result);
        }
        var safety = onEdt(() -> {
          panel.accept(data.base()); select(panel, "secrets", data.targetId()); panel.original.doClick(0);
          boolean exactCallback = data.targetId().equals(originalAction.get());
          JTable paths = panel.tables.get("paths"); tab(panel, "paths"); paths.getRowSorter().setSortKeys(List.of());
          int index = panel.current.pages().size() - 1; paths.setRowSelectionInterval(index, index);
          boolean unlinked = panel.selectedOriginal() == null && !panel.original.isEnabled() && !panel.repeater.isEnabled() && !panel.evidence.isEnabled();
          var sameFingerprint = panel.current.findings().stream().filter(f -> f.fingerprint().equals("same-fingerprint-two-accounts")).toList();
          boolean duplicateRows = panel.tables.get("summary").getRowCount() == count && sameFingerprint.size() == 3
              && sameFingerprint.stream().map(ScopeInspection.Finding::account).distinct().count() == 2;
          return Map.of("originalCallbackUsesExactId", exactCallback, "unlinkedOriginalActionsDisabled", unlinked,
              "sameFingerprintOtherAccountAndDuplicateRowsRetained", duplicateRows);
        });
        document.put("safety", safety);
        for (var invariant : safety.entrySet()) if (!invariant.getValue()) failures.add("safety: " + invariant.getKey());
        document.put("requestsBeforeMeasuredActions", requestsBefore); document.put("requestsAfterMeasuredActions", fixture.visits.size());
        boolean noRequests = fixture.visits.size() == requestsBefore;
        document.put("additionalHTTPForUpdateSortAndOriginalActions", fixture.visits.size() - requestsBefore);
        if (!noRequests) failures.add("update/sort/original actions sent additional HTTP");
      } finally { edt(panel::close); }
    }
    document.put("scenarios", results); document.put("failures", failures); document.put("allInvariantsPassed", failures.isEmpty());
    Files.writeString(output, MetadataExport.json(document) + "\n", StandardCharsets.UTF_8);
    System.out.println("InspectionUpdateProbe: " + args[0] + " · " + count + " synthetic findings/pages · output " + output
        + " · invariants " + (failures.isEmpty() ? "passed" : "FAILED " + failures.size()));
    if (!failures.isEmpty()) throw new AssertionError("Probe invariant failures were saved to " + output);
  }
}
