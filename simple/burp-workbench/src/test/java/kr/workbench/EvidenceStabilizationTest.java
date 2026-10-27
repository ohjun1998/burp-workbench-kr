package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.zip.ZipFile;

/** Authored prose survives reopen; bounded edits preserve packets, roles and failed-capture recovery. */
public final class EvidenceStabilizationTest extends WorkbenchTest {
  static final String A = "11111111-1111-1111-1111-111111111111", B = "22222222-2222-2222-2222-222222222222";
  static final class Fixture {
    final Map<String, Map<String, String>> projects = new HashMap<>();
    final AtomicReference<String> current = new AtomicReference<>(A);
    final AtomicBoolean failWrites = new AtomicBoolean();
    final MontoyaApi api;
    Fixture() {
      for (String id : List.of(A, B)) { var values = new HashMap<String, String>(); values.put(WorkspaceResume.PROJECT_KEY, id); projects.put(id, values); }
      Map<String, PersistedObject> stores = new HashMap<>();
      for (String id : projects.keySet()) {
        var values = projects.get(id);
        stores.put(id, proxy(PersistedObject.class, (p, m, a) -> switch (m.getName()) {
          case "getString" -> values.get(a[0].toString());
          case "setString" -> { if (failWrites.get()) throw new IllegalArgumentException("disk unavailable"); values.put(a[0].toString(), a[1].toString()); yield null; }
          default -> def(m);
        }));
      }
      Persistence persistence = proxy(Persistence.class, (p, m, a) -> m.getName().equals("extensionData") ? stores.get(current.get()) : def(m));
      api = proxy(MontoyaApi.class, (p, m, a) -> m.getName().equals("persistence") ? persistence : def(m));
    }
    BountySupportPanel panel() {
      var auth = new AuthComparePanel(api, List::of); var activity = new ActivityPanel(api);
      var panel = new BountySupportPanel(api, List::of, auth, activity);
      panel.itemEditor.opener = (owner, title, content, modal, closed) -> null;
      return panel;
    }
    String saved(String project) { return projects.get(project).get(EvidenceDrafts.KEY); }
  }
  static void close(BountySupportPanel panel) { panel.close(); panel.auth.close(); panel.activity.close(); }
  static void append(BountySupportPanel panel, String label) {
    panel.remember(BountyFeaturesTest.rq("https://auth.test/" + label, "GET", "", List.of(header("Cookie", "SID=" + label + "_SECRET"))),
        response(200, "{\"value\":\"" + label + "\"}"), label, "SOURCE_OBSERVATION " + label, "account-" + label);
  }
  static List<String> ids(BountySupportPanel panel) { return panel.evidence.stream().map(BountyEvidence.Item::evidenceId).toList(); }
  static void select(BountySupportPanel panel, int row) { panel.evidenceTable.setRowSelectionInterval(row, row); }
  static void edit(BountySupportPanel panel, int row, String name, String note) {
    select(panel, row); panel.openItemEditor(); panel.itemName.setText(name); panel.itemNote.setText(note); panel.applyEvidenceDescription();
  }
  public static void main(String[] args) throws Exception {
    BountyFeaturesTest.factory(); AtomicReference<Throwable> failure = new AtomicReference<>();
    edt(() -> {
      try { proseAndExport(); historyAndFailures(); projectAndStorage(); writingAids(); }
      catch (Throwable error) { failure.set(error); }
    });
    if (failure.get() != null) throw new AssertionError("Evidence stabilization", failure.get());
    System.out.println("EvidenceStabilizationTest: " + checks + " checks passed");
  }
  static void proseAndExport() throws Exception {
    Fixture fixture = new Fixture(); BountySupportPanel panel = fixture.panel();
    try {
      append(panel, "A"); append(panel, "B");
      check(fixture.saved(A) == null, "capturing packets does not silently persist packet-derived prose");
      panel.evidenceTitle.setText("Observed access difference"); panel.impact.setText("Different account could read another record");
      panel.repro.setText("사전 조건\nTwo accounts\n재현 단계\n1. Compare the recorded request\n관측 결과\nThe second account sees the same record");
      check(EvidenceDrafts.decode(fixture.saved(A)).content().title().equals(panel.evidenceTitle.getText()), "authored title is saved immediately after editing");
      check(panel.draftState.getText().contains("초안 저장 ") && !panel.draftState.getText().contains("저장 확인:"), "the editor shows an actual successful save time");
      select(panel, 0); panel.openItemEditor(); panel.itemNote.setText("Unapplied note A"); panel.itemEditor.closeWindow();
      select(panel, 1); panel.openItemEditor(); panel.itemNote.setText("Unapplied note B"); panel.itemEditor.closeWindow();
      var notes = EvidenceDrafts.decode(fixture.saved(A)).content().annotations();
      check(notes.size() == 2 && notes.get(panel.evidence.get(0).evidenceId()).note().equals("Unapplied note A"), "opening a second item does not erase the first saved unapplied note");
      select(panel, 0); panel.openItemEditor();
      check(panel.itemNote.getText().equals("Unapplied note A"), "reopening an item restores its unapplied authored buffer");
      panel.evidenceDrafts.reload(); panel.loadEvidenceDraft();
      select(panel, 0); panel.openItemEditor(); panel.applyEvidenceDescription();
      check(panel.evidence.get(0).note().contains("Unapplied note A"), "reloaded saved item prose can still be applied to its actual live evidence");
      panel.undoEvidenceEdit();
      check(!panel.evidence.get(0).note().contains("Unapplied note A"), "undo after saved-prose reload restores the original observation");
      panel.redoEvidenceEdit();
      panel.evidenceDrafts.reload(); panel.loadEvidenceDraft();
      select(panel, 1); panel.removeSelectedEvidence(); panel.undoEvidenceEdit();
      check(panel.evidence.get(0).note().contains("Unapplied note A") && panel.evidence.size() == 2,
          "same-project draft reload preserves existing history continuity for the next edit");
      check(!fixture.saved(A).contains("SOURCE_OBSERVATION") && !fixture.saved(A).contains("_SECRET") && !fixture.saved(A).contains("https://"), "drafts do not copy generated observations, raw credentials or original URLs");
      panel.clearEvidenceHistory(); select(panel, 0); panel.openItemEditor();
      String id = panel.evidence.get(0).evidenceId(), packet = panel.evidence.get(0).packetId();
      panel.evidenceExecutions.put(id, "execution-one"); panel.evidenceExecutionParts.put(id, "original-one");
      panel.evidenceExecutionSources.put("execution-one", Set.of("original-one"));
      panel.itemName.setText("Account <difference>"); panel.itemNote.setText("Manual <observation>"); panel.applyEvidenceDescription();
      var edited = panel.evidence.get(0);
      check(edited.evidenceId().equals(id) && edited.packetId().equals(packet) && edited.account().equals("account-A"), "description edits preserve evidence, packet and account identities");
      check(edited.note().contains("SOURCE_OBSERVATION A") && edited.note().contains("Manual <observation>"), "manual notes retain the original observation instead of replacing it");
      check(panel.evidenceExecutionParts.get(id).equals("original-one"), "editing a name cannot change the execution source role");
      panel.undoEvidenceEdit();
      check(panel.evidence.get(0).label().equals("A") && panel.evidenceRedoAction.isEnabled(), "description change can be undone with redo available");
      panel.redoEvidenceEdit();
      check(panel.evidence.get(0).label().equals("Account <difference>"), "redo restores the authored description");
      Path zip = Files.createTempFile("evidence-stabilization-", ".zip");
      try {
        panel.writeEvidence(zip, panel.evidenceTitle.getText(), panel.impact.getText(), panel.repro.getText(), List.copyOf(panel.evidence),
            panel.evidenceRedactedNames(), Set.copyOf(panel.evidenceSecrets), false, "{}", null, List.of());
        try (ZipFile saved = new ZipFile(zip.toFile())) {
          String md = new String(saved.getInputStream(saved.getEntry("report.md")).readAllBytes(), StandardCharsets.UTF_8);
          String html = new String(saved.getInputStream(saved.getEntry("report.html")).readAllBytes(), StandardCharsets.UTF_8);
          String metadata = new String(saved.getInputStream(saved.getEntry("evidence.json")).readAllBytes(), StandardCharsets.UTF_8);
          check(md.contains("Account <difference>") && md.contains("Manual <observation>") && md.contains("사전 조건"), "ZIP report includes authored item description and reproduction structure");
          check(html.contains("Account &lt;difference&gt;") && html.contains("Manual &lt;observation&gt;"), "HTML report escapes edited prose");
          check(metadata.contains(id) && metadata.contains(packet) && metadata.contains("Manual <observation>"), "evidence metadata retains exact identity alongside edited prose");
          check(!md.contains("A_SECRET") && !html.contains("A_SECRET"), "description support leaves credential redaction intact");
        }
      } finally { Files.deleteIfExists(zip); }
      close(panel); panel = fixture.panel();
      check(panel.evidenceTitle.getText().equals("Observed access difference") && panel.impact.getText().startsWith("Different account"), "restarting the panel restores title and impact from the same project");
      check(panel.repro.getText().contains("The second account") && panel.evidence.isEmpty(), "prose restoration does not fabricate missing raw evidence");
      check(panel.evidenceDraftContent().annotations().values().stream().anyMatch(note -> note.note().equals("Manual <observation>")), "authored item notes survive reopen as unlinked drafts");
    } finally { close(panel); }
  }
  static void historyAndFailures() throws Exception {
    Fixture fixture = new Fixture(); var panel = fixture.panel();
    try {
      for (String label : List.of("A", "B", "C")) append(panel, label);
      var original = ids(panel); Path raw = panel.evidenceStore.file();
      for (String id : original) { panel.evidenceExecutions.put(id, "run"); panel.evidenceExecutionParts.put(id, "role-" + id); }
      panel.evidenceExecutionSources.put("run", Set.copyOf(panel.evidenceExecutionParts.values()));
      select(panel, 1); panel.removeSelectedEvidence(); select(panel, 1); panel.moveSelectedEvidence(-1); panel.clearEvidence();
      check(panel.evidenceHistory.undoCount() == 3 && Files.exists(raw), "clear retains earlier removal and reordering recovery with raw bytes available");
      panel.undoEvidenceEdit(); check(ids(panel).equals(List.of(original.get(2), original.get(0))), "first undo restores the state before clear");
      select(panel, 0); panel.undoEvidenceEdit(); check(ids(panel).equals(List.of(original.get(0), original.get(2))), "selection changes do not invalidate the next undo");
      panel.undoEvidenceEdit(); check(ids(panel).equals(original) && panel.evidenceExecutionSources.get("run").size() == 3, "multi-step undo restores every exact original and execution role");
      check(panel.evidenceSecrets.contains("SID=B_SECRET"), "recovered evidence retains its credential redaction union");
      panel.redoEvidenceEdit(); panel.redoEvidenceEdit(); panel.redoEvidenceEdit();
      check(panel.evidence.isEmpty() && panel.evidenceHistory.redoCount() == 0, "redo replays the same removal, ordering and clear sequence");
      var request = BountyFeaturesTest.rq("https://auth.test/fail", "GET", "", List.of());
      HttpRequest failing = proxy(HttpRequest.class, (p, m, a) -> { if (m.getName().equals("toByteArray")) throw new IllegalArgumentException("simulated write input failure"); return m.invoke(request, a); });
      rejects(() -> panel.remember(failing, response(200, ""), "fail", "fail", ""), "new capture failure after clear is surfaced");
      check(panel.evidenceHistory.undoCount() == 3 && Files.exists(raw), "failed capture after clear preserves all recoverable history and raw bytes");
      panel.undoEvidenceEdit(); panel.undoEvidenceEdit();
      int before = panel.evidenceHistory.undoCount(); var state = ids(panel);
      rejects(() -> panel.remember(failing, response(200, ""), "fail", "fail", ""), "new capture failure during compaction is surfaced");
      check(panel.evidenceHistory.undoCount() == before && ids(panel).equals(state) && Files.exists(raw), "failed staged compaction leaves active evidence and undo history intact");
      panel.undoEvidenceEdit(); check(ids(panel).equals(original), "undo still reads all original packets after the failed new capture");
      for (int i = 0; i < 25; i++) panel.clearEvidence();
      check(panel.evidenceHistory.undoCount() == 20 && panel.evidenceStores.size() <= 21, "history and retained packet stores have a fixed twenty-step bound");
      check(!Files.exists(raw), "raw storage is retired when no retained history state can reach it");
      append(panel, "D"); check(!panel.evidenceHistory.canUndo() && !panel.evidenceHistory.canRedo() && panel.evidenceStores.size() == 1, "a successful new capture ends incompatible history and closes its old stores");
    } finally { close(panel); }
  }
  static void projectAndStorage() {
    Fixture fixture = new Fixture(); var panel = fixture.panel();
    try {
      panel.evidenceTitle.setText("Project A draft"); String saved = fixture.saved(A);
      fixture.failWrites.set(true); panel.impact.setText("Still visible after failure");
      check(panel.impact.getText().equals("Still visible after failure") && fixture.saved(A).equals(saved), "failed autosave retains the authored field and prior durable record");
      check(panel.draftState.getText().contains("저장하지 못했습니다"), "autosave failure is visible beside the editor");
      fixture.failWrites.set(false); panel.saveEvidenceDraft();
      check(EvidenceDrafts.decode(fixture.saved(A)).content().impact().equals("Still visible after failure"), "retry saves the preserved prose after storage recovers");
      fixture.current.set(B); panel.resetEvidenceProject();
      check(panel.impact.getText().isEmpty() && panel.draftProject.equals(B) && fixture.saved(B) == null, "project switch loads only that project's own draft");
      panel.evidenceTitle.setText("Project B draft"); fixture.current.set(A); panel.resetEvidenceProject();
      check(panel.evidenceTitle.getText().equals("Project A draft") && panel.impact.getText().equals("Still visible after failure"), "switching back restores the original project's prose");
      check(EvidenceDrafts.decode(fixture.saved(B)).content().title().equals("Project B draft"), "project B durable prose is never overwritten by project A");
    } finally { close(panel); }
    Fixture corrupt = new Fixture(); corrupt.projects.get(A).put(EvidenceDrafts.KEY, "{bad"); var blocked = corrupt.panel();
    try {
      blocked.evidenceTitle.setText("Keep current prose");
      check(corrupt.saved(A).equals("{bad") && blocked.evidenceTitle.getText().equals("Keep current prose"), "malformed saved drafts are preserved while the editor remains usable");
      check(blocked.draftState.getText().contains("기존 기록은 보존"), "malformed draft recovery is explained visibly");
      check(blocked.evidenceDrafts.resetCorrupt(blocked.draftProject), "explicit new-draft choice unlocks replacement");
      blocked.saveEvidenceDraft();
      check(EvidenceDrafts.decode(corrupt.saved(A)).content().title().equals("Keep current prose"), "explicit replacement saves the current authored prose");
    } finally { close(blocked); }
  }
  static void writingAids() {
    String template = EvidenceReportText.template("");
    check(EvidenceReportText.missing("취약점 재현 증거", "", template).size() == 5, "empty writing template still identifies all missing authored sections");
    check(EvidenceReportText.template("Existing observation").startsWith("Existing observation"), "inserting a template retains existing user prose");
    check(EvidenceReportText.template(template).equals(template), "inserting the same complete section structure is idempotent");
    check(EvidenceReportText.missing("Specific title", "Verified impact", "사전 조건\nAccounts\n재현 단계\n1. Compare\n관측 결과\nDifferent data").isEmpty(), "filled writing structure has no missing-input reminders");
  }
}
