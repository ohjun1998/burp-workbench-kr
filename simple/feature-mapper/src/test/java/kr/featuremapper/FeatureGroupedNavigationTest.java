package kr.featuremapper;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.JTree;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.text.Document;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

/** The grouped production tree retains selection, documents and stored traffic without sending HTTP. */
public final class FeatureGroupedNavigationTest {
  private static int checks;

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    checks++;
    System.out.println("PASS " + message);
  }

  private static DefaultMutableTreeNode root(JTree tree) {
    return (DefaultMutableTreeNode) tree.getModel().getRoot();
  }

  private static DefaultMutableTreeNode feature(JTree tree, String id) {
    var nodes = root(tree).depthFirstEnumeration();
    while (nodes.hasMoreElements()) {
      var node = (DefaultMutableTreeNode) nodes.nextElement();
      if (node.getUserObject() instanceof FeatureMapperWorkspace.FeatureNode value && value.id().equals(id)) return node;
    }
    throw new AssertionError("Feature not rendered: " + id);
  }

  private static DefaultMutableTreeNode group(JTree tree, String key) {
    for (int i = 0; i < root(tree).getChildCount(); i++) {
      var node = (DefaultMutableTreeNode) root(tree).getChildAt(i);
      if (node.getUserObject() instanceof FeatureMapperWorkspace.GroupNode value && value.key().equals(key)) return node;
    }
    throw new AssertionError("Group not rendered: " + key);
  }

  private static Set<String> featureIds(JTree tree) {
    Set<String> ids = new HashSet<>();
    int leaves = 0;
    var nodes = root(tree).depthFirstEnumeration();
    while (nodes.hasMoreElements()) {
      var node = (DefaultMutableTreeNode) nodes.nextElement();
      if (node.getUserObject() instanceof FeatureMapperWorkspace.FeatureNode value) {
        ids.add(value.id());
        leaves++;
      }
    }
    check(ids.size() == leaves, "tree contains each feature ID only once");
    return ids;
  }

  private static void refresh(FeatureMapperWorkspace workspace) {
    try { MapperUiWorkflowTest.call(workspace, "refresh"); }
    catch (Exception error) { throw new RuntimeException(error); }
  }

  private static Object field(Object owner, String name) {
    try { return MapperUiWorkflowTest.get(owner, name); }
    catch (Exception error) { throw new RuntimeException(error); }
  }

  private static void selectFeature(JTree tree, String id) {
    tree.setSelectionPath(new TreePath(feature(tree, id).getPath()));
  }

  private static long selectedPacketId(JTable table) {
    int row = table.getSelectedRow();
    return row < 0 ? -1 : ((Number) table.getModel().getValueAt(table.convertRowIndexToModel(row), 0)).longValue();
  }

  private static void selectPacket(JTable table, long id) {
    for (int row = 0; row < table.getModel().getRowCount(); row++) {
      if (((Number) table.getModel().getValueAt(row, 0)).longValue() != id) continue;
      int view = table.convertRowIndexToView(row);
      table.setRowSelectionInterval(view, view);
      return;
    }
    throw new AssertionError("Packet not shown: " + id);
  }

  public static void main(String[] args) throws Exception {
    int sendsBefore = MapperUiWorkflowTest.sent.get();
    try (MapperUiWorkflowTest.Fixture fixture = new MapperUiWorkflowTest.Fixture()) {
      FeatureMapperWorkspace workspace = fixture.ext;
      long now = System.currentTimeMillis();
      Mapper.Packet balance = workspace.mapper.accept(MapperTest.input(now, "GET", "http://127.0.0.1:8088/check_balance/100", "", "{\"balance\":100}", "GROUPED"));
      Mapper.Packet anotherBalance = workspace.mapper.accept(MapperTest.input(now + 1, "GET", "http://127.0.0.1:8088/check_balance/101", "", "{\"balance\":101}", "GROUPED"));
      workspace.mapper.accept(MapperTest.input(now + 2, "GET", "http://127.0.0.1:8088/api/docs/", "", "{}", "GROUPED"));
      workspace.mapper.accept(MapperTest.input(now + 3, "GET", "http://127.0.0.1:8088/.env", "", "{}", "GROUPED"));
      workspace.mapper.accept(MapperTest.input(now + 4, "GET", "http://127.0.0.1:8088/wb-missing-04b27104-0dcb-4c69-bbe4-06a5acf263b1", "", "{}", "GROUPED"));
      workspace.mapper.accept(MapperTest.input(now + 5, "GET", "http://127.0.0.1:8088/check_balance/%27", "", "{}", "GROUPED"));
      byte[][] original = workspace.mapper.raw(anotherBalance.id);
      Set<String> originalIds = new HashSet<>(workspace.mapper.featureSummaries().stream().map(Mapper.FeatureSummary::id).toList());
      JTree tree = (JTree) field(workspace, "tree");
      JTable table = (JTable) MapperUiWorkflowTest.named(fixture.root, "fm.history");
      JTextField search = (JTextField) field(workspace, "search");
      JTextArea info = (JTextArea) MapperUiWorkflowTest.named(fixture.root, "fm.feature.original");
      JToggleButton toggle = (JToggleButton) MapperUiWorkflowTest.named(fixture.root, "fm.feature.original.toggle");
      Document originalDocument = info.getDocument();
      Document requestDocument = ((JTextArea) field(workspace, "requestText")).getDocument();
      MapperUiWorkflowTest.edt(() -> {
        refresh(workspace);
        check(root(tree).getChildAt(0) instanceof DefaultMutableTreeNode
            && ((DefaultMutableTreeNode) root(tree).getChildAt(0)).getUserObject() instanceof FeatureMapperWorkspace.AllFeaturesNode all
            && all.packets() == workspace.mapper.count(), "first tree row shows the original total observation count");
        for (int i = 1; i < root(tree).getChildCount(); i++) {
          var node = (DefaultMutableTreeNode) root(tree).getChildAt(i);
          var value = (FeatureMapperWorkspace.GroupNode) node.getUserObject();
          check(value.features() == node.getChildCount(), "group caption counts its actual retained feature leaves");
          check(tree.isExpanded(new TreePath(node.getPath())) == Set.of("business", "account").contains(value.key()), "only business and account groups are expanded initially");
        }
        check(featureIds(tree).equals(originalIds), "grouping retains the complete original feature ID set");
        selectFeature(tree, balance.primary);
        check(balance.primary.equals(field(workspace, "selectedFeature")) && table.getRowCount() == 2, "feature selection uses its retained ID and actual two observations");
        selectPacket(table, anotherBalance.id);
        check(selectedPacketId(table) == anotherBalance.id, "test selects the second actual packet by ID");
        TreePath business = new TreePath(group(tree, "business").getPath());
        tree.collapsePath(business);
        Object featureAfterCollapse = field(workspace, "selectedFeature");
        long packetAfterCollapse = selectedPacketId(table);
        refresh(workspace);
        check(!tree.isExpanded(new TreePath(group(tree, "business").getPath())), "refresh retains an explicitly collapsed selected business group");
        check(balance.primary.equals(field(workspace, "selectedFeature")) && selectedPacketId(table) == anotherBalance.id, "refresh retains selected feature ID and table packet ID while collapsed"
            + " (after collapse: feature=" + featureAfterCollapse + ", packet=" + packetAfterCollapse
            + "; after refresh: feature=" + field(workspace, "selectedFeature") + ", packet=" + selectedPacketId(table) + ")");
        TreePath docs = new TreePath(group(tree, "docs").getPath());
        tree.expandPath(docs);
        refresh(workspace);
        check(tree.isExpanded(new TreePath(group(tree, "docs").getPath())), "refresh retains an explicitly expanded exploration group");
        check(!toggle.isSelected() && !((JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, info)).isVisible(), "original URL information starts folded");
        toggle.doClick(0);
        check(((JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, info)).isVisible()
            && info.getText().contains(anotherBalance.url) && info.getText().contains(balance.primary)
            && info.getText().contains(workspace.mapper.features.get(balance.primary).name), "original disclosure shows the exact selected URL, feature ID and stored name");
        info.setCaretPosition(info.getDocument().getLength());
        int caret = info.getCaretPosition();
        String text = info.getText();
        refresh(workspace);
        check(info.getDocument() == originalDocument && info.getText().equals(text) && info.getCaretPosition() == caret, "unchanged refresh preserves original-info document, content and reading position"
            + " (same document=" + (info.getDocument() == originalDocument) + ", same text=" + info.getText().equals(text)
            + ", caret=" + info.getCaretPosition() + ", expected=" + caret + ")");
        toggle.doClick(0);
        toggle.doClick(0);
        check(info.getDocument() == originalDocument && ((JTextArea) field(workspace, "requestText")).getDocument() == requestDocument, "disclosure toggles retain original-info and packet editor documents");
      });

      String authored = "내가 정한 API 문서 / 제목";
      workspace.mapper.edit(balance.primary, authored, "진행 중", "원래 메모");
      workspace.mapper.accept(MapperTest.input(now + 6, "GET", "http://127.0.0.1:8088/check_balance/102", "", "{\"balance\":102}", "GROUPED"));
      MapperUiWorkflowTest.edt(() -> {
        refresh(workspace);
        var node = feature(tree, balance.primary);
        var value = (FeatureMapperWorkspace.FeatureNode) node.getUserObject();
        check(value.label().equals(authored) && value.originalName().equals(authored) && value.state().equals("진행 중"), "authored names and assessment states remain literal in the production tree");
        check(((FeatureMapperWorkspace.GroupNode) ((DefaultMutableTreeNode) node.getParent()).getUserObject()).key().equals("business"), "renaming to document words does not reclassify the original mapping group");
        check(value.packets() == 3 && selectedPacketId(table) == anotherBalance.id, "append refresh updates the feature count without moving the selected original packet");
        check(!tree.isExpanded(new TreePath(group(tree, "business").getPath())), "new observations do not reopen an explicitly collapsed group");
        tree.setSelectionPath(new TreePath(((DefaultMutableTreeNode) root(tree).getChildAt(0)).getPath()));
        check(field(workspace, "selectedFeature") == null && table.getRowCount() == workspace.mapper.count(), "all-observations row restores the complete table without changing mappings");
        search.setText("잔액");
        refresh(workspace);
        // The renamed balance feature no longer has this alias; the quote variant still does.
        check(table.getRowCount() == 1 && String.valueOf(table.getValueAt(0, 2)).endsWith("/%27"), "Korean display-name search finds input variants without requiring their encoded URL");
        search.setText("내가 정한");
        refresh(workspace);
        check(table.getRowCount() == 3, "authored display-name search returns the feature's original packet rows");
        search.setText("");
        refresh(workspace);
        check(featureIds(tree).equals(originalIds), "capture and rename keep every original feature ID through grouped refresh");
        selectFeature(tree, balance.primary);
        String assessmentSource = workspace.mapper.assessmentSourceId(balance.primary);
        check(workspace.isAssessmentSourceCurrent(assessmentSource), "selected grouped feature is the current assessment-review source");
        tree.collapsePath(new TreePath(group(tree, "business").getPath()));
        refresh(workspace);
        check(balance.primary.equals(field(workspace, "selectedFeature"))
                && workspace.isAssessmentSourceCurrent(assessmentSource) && table.getRowCount() == 3,
            "collapsing and refreshing a selected group retains its feature filter and current assessment source");
        tree.clearSelection();
        check(field(workspace, "selectedFeature") == null && !workspace.isAssessmentSourceCurrent(assessmentSource)
                && table.getRowCount() == workspace.mapper.count(),
            "explicit tree selection clearing removes the feature filter and invalidates an open assessment review");
        refresh(workspace);
        check(field(workspace, "selectedFeature") == null && !workspace.isAssessmentSourceCurrent(assessmentSource),
            "refresh cannot resurrect the assessment source after explicit selection clearing");
      });
      byte[][] after = workspace.mapper.raw(anotherBalance.id);
      check(Arrays.equals(original[0], after[0]) && Arrays.equals(original[1], after[1]), "group selection, refresh, disclosure and search preserve complete original packet bytes");
      check(workspace.mapper.features.get(balance.primary).note.equals("원래 메모"), "grouped navigation preserves the authored feature note");
      check(MapperUiWorkflowTest.sent.get() == sendsBefore, "grouped navigation and original disclosure send no HTTP requests");
    }
    check(MapperUiWorkflowTest.sent.get() == sendsBefore, "closing grouped workspace sends no HTTP requests");
    System.out.println("FeatureGroupedNavigationTest: " + checks + " checks passed");
  }
}
