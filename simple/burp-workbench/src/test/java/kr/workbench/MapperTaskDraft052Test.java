package kr.workbench;

import java.util.*;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import kr.featuremapper.FeatureMapperWorkspace;

/** Mapper proposals are explicit, bounded task edits; classification never completes work. */
public final class MapperTaskDraft052Test extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    AssessmentTracker tracker = new AssessmentTracker();
    String account = tracker.register(new AssessmentTracker.AccountInput("Reviewer", "https://example.test", "viewer", "", false));
    var first = tracker.mapperDraft("feature-one", "게시물 수정", List.of("GET https://example.test/items", "PATCH https://example.test/items/1"));
    check(tracker.tasks().isEmpty(), "building Mapper review never creates a task");
    rejects(() -> tracker.applyMapperDraft(first, Set.of(), false), "Mapper task requires explicit account selection");
    String id = tracker.applyMapperDraft(first, Set.of(account), false);
    check(tracker.tasks().size() == 1 && tracker.tasks().get(0).completed() == 0
        && !tracker.tasks().get(0).state().equals("점검 완료"), "approved Mapper proposal creates an incomplete task");
    var same = tracker.mapperDraft("feature-one", "same feature renamed", first.endpoints());
    check(tracker.applyMapperDraft(same, Set.of(account), false).equals(id) && tracker.tasks().size() == 1,
        "stable feature identity upserts instead of duplicating the task");
    var changed = tracker.mapperDraft("feature-one", "게시물 수정", List.of("GET https://example.test/items", "DELETE https://example.test/items/1"));
    check(changed.added().equals(List.of("DELETE https://example.test/items/1"))
        && changed.removed().equals(List.of("PATCH https://example.test/items/1")), "proposal separates added and removed APIs");
    tracker.applyMapperDraft(changed, Set.of(account), false);
    check(tracker.tasks().get(0).endpoints() == 3, "unconfirmed removals preserve the existing task scope");
    var remove = tracker.mapperDraft("feature-one", "게시물 수정", changed.endpoints());
    tracker.applyMapperDraft(remove, Set.of(account), true);
    check(tracker.tasks().get(0).endpoints() == 2 && tracker.tasks().get(0).state().equals("재검토 필요"),
        "explicit removal changes scope and requires review");
    var stale = tracker.mapperDraft("feature-one", "게시물 수정", changed.endpoints());
    tracker.editTask(id, "manual change", "new condition", Set.of(account), true);
    rejects(() -> tracker.applyMapperDraft(stale, Set.of(account), true), "concurrent task edits reject stale Mapper approval");
    var cross = tracker.mapperDraft("another-feature", "다른 Origin", List.of("GET https://outside.test/path"));
    rejects(() -> tracker.applyMapperDraft(cross, Set.of(account), false), "cross-origin API requires a matching account");
    check(tracker.tasks().size() == 1, "failed Mapper validation does not partially create a task");
    rejects(() -> tracker.mapperDraft("bad", "too many", java.util.stream.IntStream.range(0, 33)
        .mapToObj(i -> "GET https://example.test/" + i).toList()), "task API bound enforced before proposal mutation");
    String saved = MetadataExport.json(tracker.save());
    var loaded = new AssessmentTracker(); loaded.load(MinerJson.parse(saved, 32 * 1024 * 1024, 3000000));
    var afterReload = loaded.mapperDraft("feature-one", "reopen", changed.endpoints());
    check(afterReload.taskId().equals(id) && loaded.applyMapperDraft(afterReload, Set.of(account), false).equals(id),
        "project roundtrip preserves feature-to-task identity without duplicate tasks");
    integratedMapperReview();
    System.out.println("MapperTaskDraft052Test: " + checks + " checks passed");
  }
  static void integratedMapperReview() throws Exception {
    try (var fixture = new TrafficScopeModesTest.Fixture()) {
      var workspace = (FeatureMapperWorkspace) get(fixture.ext, "featureMapper");
      Object mapper = get(workspace, "mapper");
      var activity = (ActivityPanel) get(fixture.ext, "activity");
      var panel = activity.assessmentPanel;
      String account = panel.tracker.register(new AssessmentTracker.AccountInput("Mapper reviewer", "https://burp.test", "viewer", "", false));
      edt(() -> fixture.mode().doClick(0));
      MapperIntegrationTest.send(fixture, "https://burp.test/api/items/12?private=value", 5201);
      Object original = ((Map<?, ?>) get(mapper, "packets")).values().iterator().next();
      String feature = (String) get(original, "primary");
      selectFeature(workspace, feature);
      JButton link = (JButton) UiLayoutTest.named(workspace.component(), "fm.assessment.link");
      TargetWindow first = openReview(panel, link);
      JList<?> accounts = (JList<?>) UiLayoutTest.named(first.content.get(), "wb.assessment.mapper.accounts");
      JButton apply = (JButton) UiLayoutTest.named(first.content.get(), "wb.assessment.mapper.apply");
      check(accounts.getSelectedIndices().length == 0 && panel.tracker.tasks().isEmpty(),
          "real Mapper callback opens explicit account review without creating a task");
      edt(() -> { accounts.setSelectedIndex(0); apply.doClick(0); });
      check(panel.tracker.tasks().size() == 1 && panel.tracker.taskAccounts(panel.tracker.tasks().get(0).id()).size() == 1
          && panel.tracker.taskAccounts(panel.tracker.tasks().get(0).id()).contains(account),
          "real Mapper source ID passes Workbench callback and approved review creates a linked task");
      String task = panel.tracker.tasks().get(0).id();
      check(panel.tracker.tasks.get(task).endpoints.equals(Set.of("GET https://burp.test/api/items/12")),
          "linked task uses exact safe API without query credentials");
      TargetWindow same = openReview(panel, link);
      edt(() -> ((JButton) UiLayoutTest.named(same.content.get(), "wb.assessment.mapper.apply")).doClick(0));
      check(panel.tracker.tasks().size() == 1 && panel.tracker.tasks().get(0).id().equals(task),
          "reopening the same real feature updates its existing task instead of creating a duplicate");

      TargetWindow stale = openReview(panel, link);
      MapperIntegrationTest.send(fixture, "https://burp.test/api/changed-target", 5202);
      Object added = ((Map<?, ?>) get(mapper, "packets")).values().stream()
          .filter(packet -> { try { return !get(packet, "id").equals(get(original, "id")); } catch (Exception ex) { throw new RuntimeException(ex); } })
          .findFirst().orElseThrow();
      MapperIntegrationTest.invoke(mapper, "move", new Class<?>[]{long.class, String.class}, ((Number) get(added, "id")).longValue(), feature);
      edt(() -> ((JButton) UiLayoutTest.named(stale.content.get(), "wb.assessment.mapper.apply")).doClick(0));
      check(panel.tracker.tasks.get(task).endpoints.size() == 1
          && ((JLabel) UiLayoutTest.named(stale.content.get(), "wb.assessment.mapper.feedback")).getText().contains("변경"),
          "API reclassification during a real Mapper review rejects stale approval");
      TargetWindow current = openReview(panel, link);
      edt(() -> ((JButton) UiLayoutTest.named(current.content.get(), "wb.assessment.mapper.apply")).doClick(0));
      check(panel.tracker.tasks.get(task).endpoints.size() == 2 && panel.tracker.tasks().size() == 1,
          "reopened real review includes the new API and updates the same task");
      TargetWindow scopeChanged = openReview(panel, link);
      edt(() -> fixture.table().setValueAt(false, 0, 0));
      edt(() -> ((JButton) UiLayoutTest.named(scopeChanged.content.get(), "wb.assessment.mapper.apply")).doClick(0));
      check(panel.tracker.tasks.get(task).endpoints.size() == 2 && panel.tracker.tasks().size() == 1
          && ((JLabel) UiLayoutTest.named(scopeChanged.content.get(), "wb.assessment.mapper.feedback")).getText().contains("변경"),
          "scope change during the real review preserves existing task scope");
    }
  }

  static void selectFeature(FeatureMapperWorkspace workspace,String feature) throws Exception {
    edt(() -> {
      try {
        // Hidden headless fixtures do not run the showing-only refresh timer. Use the
        // actual tree selection so the contextual button follows production UI state.
        MapperIntegrationTest.invoke(workspace,"refresh",new Class<?>[0]);
        JTree tree=(JTree)get(workspace,"tree");
        var nodes=((DefaultMutableTreeNode)tree.getModel().getRoot()).depthFirstEnumeration();
        while(nodes.hasMoreElements()) {
          var node=(DefaultMutableTreeNode)nodes.nextElement();Object value=node.getUserObject();
          if(value!=null&&value.getClass().getSimpleName().equals("FeatureNode")
              &&feature.equals(get(value,"id"))) {
            tree.setSelectionPath(new TreePath(node.getPath()));return;
          }
        }
        throw new AssertionError("Mapper feature tree fixture was not rebuilt: "+feature);
      } catch(Exception error) { throw new RuntimeException(error); }
    });
  }

  static TargetWindow openReview(AssessmentPanel panel, JButton button) throws Exception {
    TargetWindow before = panel.mapperDraftWindow;
    edt(() -> button.doClick(0));
    for (int i = 0; i < 250; i++) {
      edt(() -> {});
      if (panel.mapperDraftWindow != null && panel.mapperDraftWindow != before) return panel.mapperDraftWindow;
      Thread.sleep(10);
    }
    throw new AssertionError("Real Mapper callback did not open the Assessment review");
  }

}
