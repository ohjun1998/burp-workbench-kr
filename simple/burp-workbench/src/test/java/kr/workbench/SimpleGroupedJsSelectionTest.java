package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JTree;
import javax.swing.JTextArea;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

/** Separate JavaScript call sites may share one function without sharing their row identity. */
public final class SimpleGroupedJsSelectionTest extends WorkbenchTest {
  private static void flush() throws Exception { edt(() -> {}); edt(() -> {}); }

  private static TreePath path(JTree tree, String label) {
    var nodes = ((DefaultMutableTreeNode) tree.getModel().getRoot()).depthFirstEnumeration();
    while (nodes.hasMoreElements()) {
      var node = (DefaultMutableTreeNode) nodes.nextElement();
      if (node.toString().equals(label)) return new TreePath(node.getPath());
    }
    throw new AssertionError("Missing function: " + label);
  }

  private static int selectedModelRow(JsAnalysisPanel panel) {
    return panel.urlTable.getSelectedRow() < 0 ? -1
        : panel.urlTable.convertRowIndexToModel(panel.urlTable.getSelectedRow());
  }

  private static String observationId(JsAnalysisPanel panel) {
    JTextArea original = (JTextArea) UiLayoutTest.named(panel, "wb.js.endpoints.grouped.original");
    return original.getText().lines().filter(line -> line.startsWith("관측 ID: ")).findFirst().orElseThrow();
  }

  public static void main(String[] args) throws Exception {
    AtomicInteger requests = new AtomicInteger();
    Http http = proxy(Http.class, (owner, method, values) -> {
      if (method.getName().equals("sendRequest")) requests.incrementAndGet();
      return def(method);
    });
    MontoyaApi api = proxy(MontoyaApi.class,
        (owner, method, values) -> method.getName().equals("http") ? http : def(method));
    AtomicReference<JsAnalysisPanel> reference = new AtomicReference<>();
    edt(() -> reference.set(new JsAnalysisPanel(api, () -> {})));
    JsAnalysisPanel panel = reference.get();
    String source = "fetch('/api/users');\nfetch('/api/users',{method:'GET'});\nfetch('/api/docs/');";
    try {
      edt(() -> panel.load(source, "https://grouped-js.test/main.js"));
      JsAnalysisPanelTest.waitDone(panel);
      flush();
      var result = panel.currentResult();
      var sourceDocument = panel.input.getDocument();
      var tableModel = panel.urlTable.getModel();
      edt(() -> {
        check(result.endpoints().size() == 3 && panel.endpoints.getRowCount() == 3,
            "manual analysis retains both calls to the same endpoint and a separate document endpoint");
        check(result.endpoints().get(0).url().equals(result.endpoints().get(1).url())
                && result.endpoints().get(0).start() != result.endpoints().get(1).start(),
            "duplicate endpoint fixture has separate original call-site offsets");
        int first = panel.urlTable.convertRowIndexToView(0);
        panel.urlTable.setRowSelectionInterval(first, first);
        String firstIdentity = observationId(panel);
        int second = panel.urlTable.convertRowIndexToView(1);
        panel.urlTable.setRowSelectionInterval(second, second);
        check(!firstIdentity.equals(observationId(panel)),
            "original disclosure assigns separate observation IDs to the two real call sites sharing one URL");
        JTree tree = (JTree) UiLayoutTest.named(panel, "wb.js.endpoints.grouped.tree");
        tree.setSelectionPath(path(tree, "회원 조회"));
        check(panel.urlTable.getRowCount() == 2,
            "one function filter includes both original call sites without merging them");
        check(panel.urlTable.getSelectedRowCount() == 1 && selectedModelRow(panel) == 1,
            "function filtering keeps the selected second call site without selecting its duplicate URL");
        panel.urlTable.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(6, SortOrder.DESCENDING)));
      });
      flush();
      edt(() -> {
        check(panel.urlTable.getSelectedRowCount() == 1 && selectedModelRow(panel) == 1,
            "sorting grouped duplicate URLs preserves the one selected original call site");
        panel.observedMethods = urls -> java.util.Map.of("https://grouped-js.test/api/users", java.util.Set.of("GET"));
        panel.resultView.refresh();
      });
      flush();
      edt(() -> {
        check(panel.urlTable.getSelectedRowCount() == 1 && selectedModelRow(panel) == 1,
            "observation metadata refresh never broadens one call-site selection to duplicate URL rows");
        check(panel.urlTable.getModel() == tableModel && panel.currentResult() == result
                && panel.input.getDocument() == sourceDocument && panel.input.getText().equals(source),
            "group filtering and status updates retain the actual result, table model and source document");
        JTree tree = (JTree) UiLayoutTest.named(panel, "wb.js.endpoints.grouped.tree");
        tree.setSelectionPath(path(tree, "전체 관측"));
        check(panel.urlTable.getRowCount() == 3 && panel.urlTable.getSelectedRowCount() == 1
                && selectedModelRow(panel) == 1,
            "returning to all observations keeps exactly the same selected call-site row");
        check(panel.endpoints.getValueAt(0, 0).equals(result.endpoints().get(0).url())
                && panel.endpoints.getValueAt(1, 0).equals(result.endpoints().get(1).url())
                && result.endpoints().get(0).start() == source.indexOf("'/api/users'")
                && result.endpoints().get(1).start() == source.indexOf("'/api/users'", source.indexOf("'/api/users'") + 1),
            "display grouping preserves duplicate original URL values and both exact source offsets");
        check(requests.get() == 0 && panel.repeater.sessions.isEmpty(),
            "call-site grouping, selection and metadata refresh send no HTTP or Repeater requests");
      });
    } finally { edt(panel::close); }
    check(requests.get() == 0, "closing the grouped JavaScript reader sends no HTTP requests");
    System.out.println("SimpleGroupedJsSelectionTest: " + checks + " checks passed");
  }
}
