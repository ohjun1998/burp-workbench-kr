package kr.workbench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JTable;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Reading/copying actual loopback results while the production inspection panel refreshes. */
public final class ScopeInspectionReadingTest extends WorkbenchTest {
  private record Reading(int start, int end, int caret, String selected) {
    static Reading capture(ScopeInspectionPanel panel) {
      return new Reading(panel.detail.getSelectionStart(), panel.detail.getSelectionEnd(),
          panel.detail.getCaretPosition(), panel.detail.getSelectedText());
    }
  }

  private static ScopeInspection.Snapshot snapshot(ScopeInspection.Snapshot source, String run,
      List<ScopeInspection.Finding> findings, String message) {
    return new ScopeInspection.Snapshot(run, source.state(), message, source.sent(), source.completed(),
        source.pending(), source.excluded(), source.failed(), source.partial(), findings,
        source.pages(), source.candidates(), source.startedAt(), source.finishedAt());
  }

  private static ScopeInspection.Finding changed(ScopeInspection.Finding source, String account, String detail) {
    return new ScopeInspection.Finding(source.category(), source.title(), source.url(), source.method(),
        account, source.confidence(), source.severity(), source.state(), detail, source.originalId(),
        source.line(), source.offset(), source.fingerprint());
  }

  private static Reading selectText(ScopeInspectionPanel panel) {
    int end = Math.min(30, panel.detail.getText().length());
    panel.detail.select(6, end);
    return Reading.capture(panel);
  }

  public static void main(String[] args) throws Exception {
    boolean probe = args.length == 2 && args[0].equals("--probe");
    var report = new LinkedHashMap<String, Object>();
    report.put("scenario", "Read and copy one original-linked finding while live snapshots refresh");
    report.put("environment", "Production Swing panel and actual isolated loopback HTTP via Montoya adapter; no native Burp GUI");
    report.put("units", Map.of("documentEvents", "events", "requests", "HTTP requests",
        "repeatRefreshes", "refreshes", "reselectionActions", "text selection actions"));
    try (var fixture = new ScopeInspectionFixture()) {
      WorkbenchScope.install(fixture.api).add(fixture.origin + "/app/", false);
      var repeated = new AtomicReference<String>();
      ScopeInspectionPanel.Actions actions = new ScopeInspectionPanel.Actions() {
        public void original(DiskTrafficStore.PacketRef packet) { repeated.set(packet.id()); }
        public void repeater(DiskTrafficStore.PacketRef packet) {}
        public void compare(DiskTrafficStore.PacketRef packet) {}
        public void evidence(List<DiskTrafficStore.PacketRef> packets, String note) {}
        public void tool(String id, DiskTrafficStore.PacketRef packet) {}
      };
      var holder = new AtomicReference<ScopeInspectionPanel>();
      edt(() -> holder.set(new ScopeInspectionPanel(fixture.api, () -> fixture.options(false).profiles(), actions)));
      ScopeInspectionPanel panel = holder.get();
      try {
        edt(() -> {
          panel.url.setText(fixture.origin + "/app/config");
          panel.depth.setValue(0); panel.delay.setValue(0); panel.timeout.setValue(3);
          panel.budget.setValue(30); panel.start.doClick(0);
        });
        ScopeInspectionUiTest.await(() -> ScopeInspectionUiTest.terminal(panel.current), "reading fixture completes");
        ScopeInspection.Snapshot live = panel.current;
        check(live.state() == ScopeInspection.State.COMPLETED, "reading scenario finishes the actual HTTP inspection");
        var finding = live.findings().stream().filter(f -> f.category().equals("SECRET") && !f.originalId().isEmpty())
            .findFirst().orElseThrow();
        int requests = fixture.visits.size();
        check(requests > 0 && live.sent() == requests, "reading scenario uses real counted loopback responses");
        check(live.pages().stream().anyMatch(p -> p.original() != null && p.original().id().equals(finding.originalId())),
            "the selected finding resolves to its exact retained HTTP original");
        var events = new AtomicInteger();
        var values = new LinkedHashMap<String, Object>();
        edt(() -> {
          // Freeze the real responses, then replay publication-only updates so timing cannot hide a reset.
          panel.accept(snapshot(live, live.id(), List.of(finding), "Reading fixture"));
          panel.pages.setSelectedIndex(ScopeInspectionUiTest.indexOf(panel.pages, "summary"));
          JTable table = panel.tables.get("summary"); table.setRowSelectionInterval(0, 0);
          panel.detail.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { events.incrementAndGet(); }
            public void removeUpdate(DocumentEvent event) { events.incrementAndGet(); }
            public void changedUpdate(DocumentEvent event) { events.incrementAndGet(); }
          });
          Reading selected = selectText(panel);
          check(selected.selected() != null && !selected.selected().isEmpty(), "the user selects detail text for copying");
          int before = events.get(), reselections = 0;
          boolean preserved = true;
          for (int i = 0; i < 5; i++) {
            panel.accept(snapshot(live, live.id(), List.of(finding), "Progress publication " + i));
            if (!Reading.capture(panel).equals(selected)) { preserved = false; reselections++; selectText(panel); }
          }
          values.put("repeatRefreshes", 5);
          values.put("selectionPreservedAcrossRefreshes", preserved);
          values.put("reselectionActions", reselections);
          values.put("documentEvents", events.get() - before);
          values.put("originalIdPreserved", panel.selectedOriginal() != null
              && panel.selectedOriginal().id().equals(finding.originalId()));

          panel.detail.select(6, 20);
          var amended = changed(finding, finding.account(), finding.detail() + "\nChanged evidence fixture");
          panel.accept(snapshot(live, live.id(), List.of(amended), "Evidence changed"));
          values.put("changedContentResetsCaret", panel.detail.getCaretPosition() == 0 && panel.detail.getSelectedText() == null);
          values.put("changedContentVisible", panel.detail.getText().contains("Changed evidence fixture"));

          // Account is a row identity but is not in the current detail text: equal text is insufficient.
          var second = changed(amended, amended.account() + " second row", amended.detail());
          panel.accept(snapshot(live, live.id(), List.of(amended, second), "Two row identities"));
          table.setRowSelectionInterval(0, 0); selectText(panel);
          String oldText = panel.detail.getText(); table.setRowSelectionInterval(1, 1);
          values.put("differentRowHasIdenticalDetailText", oldText.equals(panel.detail.getText()));
          values.put("differentRowResetsCaret", panel.detail.getCaretPosition() == 0 && panel.detail.getSelectedText() == null);

          table.setRowSelectionInterval(0, 0); selectText(panel);
          panel.accept(snapshot(live, live.id() + "-next", List.of(amended), "New run identity fixture"));
          values.put("newRunResetsCaret", panel.detail.getCaretPosition() == 0 && panel.detail.getSelectedText() == null);
          panel.original.doClick(0);
          values.put("originalActionUsesExactId", finding.originalId().equals(repeated.get()));

          Reading unchanged = selectText(panel); before = events.get();
          panel.accept(panel.current);
          values.put("identicalSnapshotPreservesSelection", unchanged.equals(Reading.capture(panel)));
          values.put("identicalSnapshotDocumentEvents", events.get() - before);
        });
        report.putAll(values);
        report.put("requestsBeforeReading", requests);
        report.put("requestsAfterReading", fixture.visits.size());
        report.put("additionalRequestsForReadingAndOriginal", fixture.visits.size() - requests);
        check(fixture.visits.size() == requests, "reading, copying, refreshing and opening originals send no additional HTTP");
        check(Boolean.TRUE.equals(values.get("originalIdPreserved")), "publication refresh keeps the exact original identity");
        check(Boolean.TRUE.equals(values.get("originalActionUsesExactId")), "original action still hands off the selected retained packet");
        check(Boolean.TRUE.equals(values.get("changedContentResetsCaret")) && Boolean.TRUE.equals(values.get("changedContentVisible")),
            "changed evidence is visible and resets reading position");
        check(Boolean.TRUE.equals(values.get("differentRowHasIdenticalDetailText")), "distinct result identities can have identical displayed detail");
        check(Boolean.TRUE.equals(values.get("differentRowResetsCaret")), "selecting a different result resets reading position despite equal text");
        check(Boolean.TRUE.equals(values.get("newRunResetsCaret")), "a new run resets reading position even when its detail text matches");
        if (!probe) {
          check(Boolean.TRUE.equals(values.get("selectionPreservedAcrossRefreshes")), "new progress snapshots preserve selected copy text and caret for the same finding");
          check(Integer.valueOf(0).equals(values.get("reselectionActions")), "five publications require zero repeated text selections");
          check(Integer.valueOf(0).equals(values.get("documentEvents")), "unchanged detail produces no document churn across five publications");
          check(Boolean.TRUE.equals(values.get("identicalSnapshotPreservesSelection")), "reaccepting the identical snapshot preserves reading selection");
          check(Integer.valueOf(0).equals(values.get("identicalSnapshotDocumentEvents")), "identical snapshots do not replace detail text");
        }
      } finally { edt(panel::close); }
    }
    if (probe) {
      Path output = Path.of(args[1]); Files.createDirectories(output.toAbsolutePath().getParent());
      Files.writeString(output, MetadataExport.json(report) + "\n");
    }
    System.out.println("READING_SCENARIO " + MetadataExport.json(report));
    System.out.println("ScopeInspectionReadingTest: " + checks + " checks passed");
  }
}
