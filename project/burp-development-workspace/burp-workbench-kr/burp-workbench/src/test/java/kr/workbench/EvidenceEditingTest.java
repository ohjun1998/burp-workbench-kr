package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Persistence;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;

/** Evidence edits preserve exact originals, provenance, export order and bounded undo/redo. */
public final class EvidenceEditingTest extends WorkbenchTest {
  static List<String> ids(BountySupportPanel panel) {
    return panel.evidence.stream().map(BountyEvidence.Item::evidenceId).toList();
  }
  static List<String> labels(BountySupportPanel panel) {
    return panel.evidence.stream().map(BountyEvidence.Item::label).toList();
  }
  static void select(BountySupportPanel panel, int... rows) {
    panel.evidenceTable.clearSelection();
    for (int row : rows) panel.evidenceTable.addRowSelectionInterval(row, row);
  }
  static void append(BountySupportPanel panel, String label) {
    var q = BountyFeaturesTest.rq("https://auth.test/" + label, "GET", "",
        List.of(header("Cookie", "SID=" + label + "_SECRET")));
    panel.remember(q, response(200, "{\"id\":\"" + label + "\"}"), label,
        "B_SECRET reflected; manually reviewed", "account-" + label);
  }
  public static void main(String[] args) throws Exception {
    BountyFeaturesTest.factory();
    Map<String, String> data = new HashMap<>();
    data.put(WorkspaceResume.PROJECT_KEY, "11111111-1111-1111-1111-111111111111");
    PersistedObject stored = proxy(PersistedObject.class, (p, m, a) -> switch (m.getName()) {
      case "getString" -> data.get(a[0].toString());
      case "setString" -> { data.put(a[0].toString(), a[1].toString()); yield null; }
      default -> def(m);
    });
    Persistence persistence = proxy(Persistence.class,
        (p, m, a) -> m.getName().equals("extensionData") ? stored : def(m));
    MontoyaApi api = proxy(MontoyaApi.class,
        (p, m, a) -> m.getName().equals("persistence") ? persistence : def(m));
    AtomicReference<Throwable> failure = new AtomicReference<>();
    edt(() -> {
      AuthComparePanel auth = new AuthComparePanel(api, List::of);
      ActivityPanel activity = new ActivityPanel(api);
      BountySupportPanel panel = new BountySupportPanel(api, List::of, auth, activity);
      try {
        for (String label : List.of("A", "B", "C", "D", "E")) append(panel, label);
        List<String> original = ids(panel);
        List<String> packets = panel.evidence.stream().map(BountyEvidence.Item::packetId).toList();
        for (int i = 0; i < original.size(); i++) panel.evidenceExecutions.put(original.get(i), "execution-" + i);
        check(panel.evidenceCount() == 5 && !panel.hasSavedEvidence(), "readiness counts captured evidence without claiming a saved ZIP");
        check(!panel.evidenceUndoAction.isEnabled() && !panel.evidenceRemove.isEnabled(), "edit controls require selection and available history");
        select(panel, 1, 3);
        check(panel.evidenceRemove.isEnabled(), "multiple selected evidence can be removed");
        panel.removeSelectedEvidence();
        check(labels(panel).equals(List.of("A", "C", "E")), "noncontiguous removal preserves remaining relative order");
        check(ids(panel).equals(List.of(original.get(0), original.get(2), original.get(4))), "removal retains exact remaining evidence identities");
        check(panel.evidenceExecutions.size() == 3 && !panel.evidenceExecutions.containsKey(original.get(1)), "removed originals cannot contribute execution links to export");
        check(!panel.evidencePreview().contains("B_SECRET"), "removed credential union continues to redact retained notes");
        check(panel.evidenceUndoAction.isEnabled(), "one removal undo is offered");
        panel.undoEvidenceEdit();
        check(ids(panel).equals(original) && panel.evidenceExecutions.get(original.get(3)).equals("execution-3"), "undo restores exact items, order and original execution mapping");
        check(Arrays.equals(panel.evidenceTable.getSelectedRows(), new int[]{1, 3}), "undo restores original multiselection");
        rejects(panel::undoEvidenceEdit, "the only edit cannot be undone twice without a new change");

        select(panel, 1, 2); panel.moveSelectedEvidence(-1);
        check(labels(panel).equals(List.of("B", "C", "A", "D", "E")), "contiguous selection moves up together in stable order");
        check(Arrays.equals(panel.evidenceTable.getSelectedRows(), new int[]{0, 1}), "moved evidence remains selected");
        panel.undoEvidenceEdit();
        select(panel, 0, 2); panel.moveSelectedEvidence(1);
        check(labels(panel).equals(List.of("B", "A", "D", "C", "E")), "noncontiguous selection moves down without swapping selected identities");
        check(panel.evidence.stream().allMatch(item -> packets.get(original.indexOf(item.evidenceId())).equals(item.packetId())), "reordering changes no original packet digest");
        panel.undoEvidenceEdit();
        panel.evidenceTable.setAutoCreateRowSorter(true);
        panel.evidenceTable.getRowSorter().setSortKeys(List.of(new javax.swing.RowSorter.SortKey(0, javax.swing.SortOrder.DESCENDING)));
        select(panel, 0); panel.moveSelectedEvidence(-1);
        check(labels(panel).equals(List.of("A", "B", "C", "E", "D")) && panel.evidenceTable.getRowSorter().getSortKeys().isEmpty(),
            "editing a sorted view uses selected model identity and clears display sort to match ZIP order");
        panel.undoEvidenceEdit(); panel.evidenceTable.setRowSorter(null);
        select(panel, 0); rejects(() -> panel.moveSelectedEvidence(-1), "moving beyond first item does not replace the undo snapshot");
        panel.running = true; panel.refreshActions();
        check(!panel.evidenceRemove.isEnabled() && !panel.evidenceUp.isEnabled() && !panel.evidenceDown.isEnabled(), "editing is disabled during active export or workflow");
        rejects(panel::removeSelectedEvidence, "programmatic removal also respects active worker guard");
        rejects(() -> append(panel, "active"), "capture cannot retire a store while export is starting");
        panel.running = false; panel.refreshActions();

        select(panel, 1); panel.removeSelectedEvidence();
        Path retired = panel.evidenceStore.file();
        append(panel, "F");
        long activeBytes = 0;
        for (var item : panel.evidence) {
          var ref = (EvidencePacketStore.Ref) get(item, "packet");
          activeBytes += (long) ref.request().length() + ref.response().length();
        }
        check(!Files.exists(retired) && panel.evidenceStore.diskBytes() == activeBytes, "adding after removal retains only active packet bytes instead of growing deleted storage forever");
        check(ids(panel).subList(0, 4).equals(List.of(original.get(0), original.get(2), original.get(3), original.get(4))), "bounded store compaction preserves evidence IDs");
        check(panel.evidence.get(1).packetId().equals(packets.get(2)) && panel.evidence.get(1).account().equals("account-C"), "compaction preserves full packet hash and account provenance");
        check(panel.evidenceExecutions.get(original.get(2)).equals("execution-2"), "compaction preserves execution mapping by evidence ID");
        rejects(panel::undoEvidenceEdit, "new evidence invalidates undo of an incompatible earlier bundle");

        select(panel, 4); panel.moveSelectedEvidence(-1);
        Path zip = Files.createTempFile("evidence-edit-order-", ".zip");
        try {
          panel.writeEvidence(zip, "title", "impact", "steps", List.copyOf(panel.evidence),
              panel.evidenceRedactedNames(), Set.copyOf(panel.evidenceSecrets), false,
              activity.tracker.save(), null, List.of());
          try (ZipFile saved = new ZipFile(zip.toFile())) {
            String fourth = new String(saved.getInputStream(saved.getEntry("requests/4.http")).readAllBytes(), StandardCharsets.UTF_8);
            String metadata = new String(saved.getInputStream(saved.getEntry("evidence.json")).readAllBytes(), StandardCharsets.UTF_8);
            check(fourth.startsWith("GET /F "), "ZIP numbered packet order follows the visible edited bundle");
            check(metadata.contains(panel.evidence.get(3).evidenceId()) && !metadata.contains(original.get(1)), "ZIP metadata uses retained exact evidence IDs and excludes removed evidence");
            check(!fourth.contains("F_SECRET"), "reordered packet export retains credential redaction");
          }
          check(panel.hasSavedEvidence(), "successful checked ZIP save updates first-use readiness");
        } finally { Files.deleteIfExists(zip); }

        panel.clearEvidence();
        check(panel.evidenceUndo != null && panel.evidenceStore.file() == null, "clear keeps a bounded recovery snapshot and a lazy current store");
        panel.undoEvidenceEdit();
        check(panel.evidenceCount() == 5 && panel.evidenceRedoAction.isEnabled(), "clear undo restores exact evidence and offers redo");
        panel.redoEvidenceEdit();
        check(panel.evidenceCount() == 0, "redo clear leaves active evidence empty again");
        check(panel.hasSavedEvidence(), "clearing a draft does not erase successful save history for this project");
        Set<String> maximum = new LinkedHashSet<>();
        for (int i = 0; i < BountySupportPanel.EVIDENCE_REDACTION_VALUES; i++) maximum.add("bounded-secret-" + i);
        panel.retainEvidenceRedactions(Set.of(), maximum);
        Set<String> retained = Set.copyOf(panel.evidenceSecrets);
        rejects(() -> panel.retainEvidenceRedactions(Set.of(), Set.of("new-secret")), "repeated capture cannot exceed the redaction value budget");
        check(panel.evidenceSecrets.equals(retained), "budget rejection never drops previously retained redaction values");
        panel.clearEvidence();
        rejects(() -> panel.retainEvidenceRedactions(Set.of(), Set.of("x".repeat(BountySupportPanel.EVIDENCE_REDACTION_CHARACTERS + 1))),
            "one oversized credential is rejected before it enters the retained union");
        check(panel.evidenceSecrets.isEmpty(), "redaction character-budget rejection is atomic");
        append(panel, "G"); select(panel, 0); panel.removeSelectedEvidence();
        Path oldProject = panel.evidenceStore.file();
        data.put(WorkspaceResume.PROJECT_KEY, "22222222-2222-2222-2222-222222222222");
        rejects(panel::undoEvidenceEdit, "undo independently detects a different active project");
        check(panel.evidenceUndo == null && panel.evidence.isEmpty() && !Files.exists(oldProject) && !panel.hasSavedEvidence(), "project transition retires evidence, undo and save readiness");
        append(panel, "H"); select(panel, 0); panel.removeSelectedEvidence();
        panel.resetEvidenceProject();
        rejects(panel::undoEvidenceEdit, "explicit workspace reset invalidates undo even within the same project");
        append(panel, "I"); select(panel, 0); panel.removeSelectedEvidence();
        Path unload = panel.evidenceStore.file(); panel.close();
        rejects(panel::undoEvidenceEdit, "unloaded panel cannot restore an old bundle");
        check(!Files.exists(unload) && panel.evidenceUndo == null, "unload removes all retained original and undo references");
      } catch (Throwable error) { failure.set(error); }
      finally { panel.close(); auth.close(); activity.close(); }
    });
    if (failure.get() != null) throw new AssertionError("Evidence edit workflow", failure.get());
    authCompleteness();
    copyIntegrity();
    backgroundLifecycle();
    System.out.println("EvidenceEditingTest: " + checks + " checks passed");
  }

  static void authCompleteness() throws Exception {
    MontoyaApi api = proxy(MontoyaApi.class, (p, m, a) -> def(m));
    AtomicReference<BountySupportPanel> support = new AtomicReference<>();
    AtomicReference<AuthComparePanel.Run> runRef = new AtomicReference<>();
    edt(() -> {
      var auth = new AuthComparePanel(api, List::of);
      var activity = new ActivityPanel(api);
      var panel = new BountySupportPanel(api, List::of, auth, activity); support.set(panel);
      var tracker = activity.tracker.assessments;
      String alice = ExecutionLink052Test.account(tracker, "Alice"), bob = ExecutionLink052Test.account(tracker, "Bob");
      var context = ExecutionLink052Test.context(alice, bob, "", List.of());
      var original = BountyFeaturesTest.rq(ExecutionLink052Test.ORIGIN + "/proof", "GET", "", List.of(header("Authorization", "Bearer ORIGINAL")));
      var run = new AuthComparePanel.Run(532, original, response(200, "{\"id\":1}"), context); runRef.set(run);
      run.packets[0].request = original.withHeader("Authorization", "Bearer COMPARISON");
      run.packets[0].response = response(403, "{\"error\":\"denied\"}");
      run.packets[0].result(new AuthFinding(AuthFinding.Kind.BLOCKED, "차단", "fixture"));
      auth.runs.add(run); auth.historyModel.addRow(new Object[]{532, "GET", original.url(), "완료", "", "", "", "", ""});
      auth.history.setRowSelectionInterval(0, 0);
      tracker.beginAuthExecution(run.executionId, "evidence-original:532", original, context);
      tracker.finishAuthExecution(run.executionId, ExecutionLedger.State.COMPLETED, "fixture");
      run.note = "완료"; panel.captureAuth();
      check(panel.evidenceExecutionSources.get(run.executionId).size() == 2, "auth capture records the required exact original and actual comparison packet identities");
      select(panel, 1); panel.removeSelectedEvidence();
      var coverage = (Map<?, ?>) MinerJson.parse(panel.executionCoverage("{}", List.copyOf(panel.evidence)));
      var completeness = (Map<?, ?>) ((List<?>) coverage.get("executionEvidenceCompleteness")).get(0);
      check(Boolean.FALSE.equals(completeness.get("complete")) && completeness.get("includedOriginalPackets").toString().equals("1"),
          "removing one actual comparison packet declares partial execution evidence");
    });
    BountySupportPanel panel = support.get();
    Path zip = Files.createTempFile("evidence-partial-", ".zip");
    try {
      panel.writeEvidence(zip, "partial", "impact", "steps", List.copyOf(panel.evidence), Set.copyOf(panel.evidenceHeaders),
          Set.copyOf(panel.evidenceSecrets), false, panel.activity.tracker.save(), null, List.of());
      check(panel.activity.tracker.assessments.execution(runRef.get().executionId).evidence().isEmpty(),
          "a partial edited ZIP never becomes proof that the entire execution has saved evidence");
      edt(panel::undoEvidenceEdit);
      panel.writeEvidence(zip, "full", "impact", "steps", List.copyOf(panel.evidence), Set.copyOf(panel.evidenceHeaders),
          Set.copyOf(panel.evidenceSecrets), false, panel.activity.tracker.save(), null, List.of());
      check(panel.activity.tracker.assessments.execution(runRef.get().executionId).evidence().contains(BountyEvidence.fileHash(zip)),
          "restoring all exact packets allows the verified full ZIP to attach to its immutable execution");
      edt(() -> {
        panel.clearEvidence();
        var first = runRef.get();
        var same = new AuthComparePanel.Run(533, first.original, first.response, first.context);
        same.packets[0].request = first.original; same.packets[0].response = first.response;
        same.packets[0].result(new AuthFinding(AuthFinding.Kind.BLOCKED, "차단", "fixture"));
        panel.auth.runs.add(same); panel.auth.historyModel.addRow(new Object[]{533, "GET", same.original.url(), "완료", "", "", "", "", ""});
        panel.auth.history.setRowSelectionInterval(1, 1);
        panel.activity.tracker.assessments.beginAuthExecution(same.executionId, "evidence-original:533", same.original, same.context);
        panel.activity.tracker.assessments.finishAuthExecution(same.executionId, ExecutionLedger.State.COMPLETED, "fixture");
        same.note = "완료"; panel.captureAuth();
        check(panel.evidence.get(0).packetId().equals(panel.evidence.get(1).packetId())
            && panel.evidenceExecutionSources.get(same.executionId).size() == 2,
            "byte-identical original and comparison observations keep distinct required source roles");
        select(panel, 1); panel.removeSelectedEvidence();
        var coverage = (Map<?, ?>) MinerJson.parse(panel.executionCoverage("{}", List.copyOf(panel.evidence)));
        check(Boolean.FALSE.equals(((Map<?, ?>) ((List<?>) coverage.get("executionEvidenceCompleteness")).get(0)).get("complete")),
            "one surviving identical packet cannot stand in for a removed original/account observation");
        panel.undoEvidenceEdit();
        check(panel.evidenceExecutionParts.size() == 2,
            "undo restores source-role mapping as well as packet and execution identities");
      });
    } finally {
      Files.deleteIfExists(zip);
      edt(() -> { panel.close(); panel.auth.close(); panel.activity.close(); });
    }
  }

  static void copyIntegrity() throws Exception {
    var request = BountyFeaturesTest.rq("https://auth.test/raw", "GET", "", List.of());
    try (var source = new EvidencePacketStore(); var target = new EvidencePacketStore()) {
      var original = source.append(request, null);
      var copied = target.append(original);
      check(copied.packetId().equals(original.packetId()) && !copied.hasResponse(), "bounded compaction preserves absent response identity");
      try (var bytes = new java.io.RandomAccessFile(source.file().toFile(), "rw")) { bytes.seek(0); bytes.write('X'); }
      long length = target.diskBytes();
      rejects(() -> target.append(original), "compaction refuses bytes whose stored SHA-256 no longer matches");
      check(target.diskBytes() == length, "failed integrity copy rolls back without publishing partial replacement bytes");
    }
  }

  static void backgroundLifecycle() throws Exception {
    MontoyaApi api = proxy(MontoyaApi.class, (p, m, a) -> def(m));
    AtomicReference<BountySupportPanel> reference = new AtomicReference<>();
    var started = new java.util.concurrent.CountDownLatch(1);
    var finish = new java.util.concurrent.CountDownLatch(1);
    edt(() -> {
      var auth = new AuthComparePanel(api, List::of); var activity = new ActivityPanel(api);
      var panel = new BountySupportPanel(api, List::of, auth, activity); reference.set(panel);
      panel.backgroundJobs = new BackgroundJobs();
      panel.task("증거 ZIP 저장", false, () -> {
        started.countDown();
        try { if (!finish.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("save gate timeout"); }
        catch (InterruptedException error) { throw new IllegalStateException(error); }
      });
    });
    var panel = reference.get();
    try {
      check(started.await(5, java.util.concurrent.TimeUnit.SECONDS), "background monitor starts only for an admitted evidence worker");
      edt(() -> {
        var job = panel.backgroundJobs.snapshot().get(0);
        check(!job.cancellable() && !panel.flowStop.isEnabled() && !panel.backgroundJobs.requestCancel(job.id()),
            "atomic save exposes no ineffective cancellation through either monitor or flow button");
      });
      finish.countDown(); BountyFeaturesTest.idle(panel);
      check(panel.backgroundJobs.snapshot().get(0).state() == BackgroundJobs.State.COMPLETED,
          "save monitor reaches completion only when the worker has actually returned");
      var entered = new java.util.concurrent.CountDownLatch(1);
      var stopped = new java.util.concurrent.CountDownLatch(1);
      edt(() -> panel.task("여러 요청 재현", true, () -> {
        entered.countDown();
        try { if (!stopped.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("flow gate timeout"); }
        catch (InterruptedException error) { throw new IllegalStateException(error); }
      }));
      check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "flow worker reaches the actual request-processing boundary");
      edt(() -> {
        var job = panel.backgroundJobs.snapshot().get(0);
        check(panel.flowStop.isEnabled() && panel.backgroundJobs.requestCancel(job.id()) && panel.cancel.get(),
            "flow monitor cancellation reaches the real downstream-stage cancel flag");
        check(panel.backgroundJobs.snapshot().get(0).state() == BackgroundJobs.State.CANCEL_REQUESTED && panel.running,
            "requesting flow stop does not falsely report a completed worker");
      });
      stopped.countDown(); BountyFeaturesTest.idle(panel);
      check(panel.backgroundJobs.snapshot().get(0).state() == BackgroundJobs.State.CANCELLED,
          "flow monitor reports cancellation after worker termination");
    } finally {
      finish.countDown();
      edt(() -> { panel.backgroundJobs.close(); panel.close(); panel.auth.close(); panel.activity.close(); });
    }
  }
}
