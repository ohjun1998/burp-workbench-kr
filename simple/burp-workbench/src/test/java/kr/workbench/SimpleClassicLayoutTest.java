package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

/** The requested 0.39.2-style shell hosts the current Simple engines and original selections. */
public final class SimpleClassicLayoutTest extends WorkbenchTest {
  private static final List<String> PRIMARY = List.of(
      "traffic", "mapper", "auth", "js", "environment", "response.recon", "payload", "bounty");

  private static TreePath route(JTree tree, String id) {
    String title = FeatureGuide.title(id);
    var nodes = ((DefaultMutableTreeNode) tree.getModel().getRoot()).depthFirstEnumeration();
    while (nodes.hasMoreElements()) {
      var node = (DefaultMutableTreeNode) nodes.nextElement();
      if (node.isLeaf() && title.equals(node.toString())) return new TreePath(node.getPath());
    }
    throw new AssertionError("Missing actual folder route: " + id + " / " + title);
  }

  private static void select(JTree tree, String id) {
    TreePath path = route(tree, id);
    tree.expandPath(path.getParentPath());
    tree.setSelectionPath(path);
  }

  private static boolean visible(Component root, String name) {
    Component value = SimpleWorkflowTest.named(root, name);
    return value != null && SimpleWorkflowTest.visibleComponents(root).contains(value);
  }

  private static void layout(Container root) {
    for (int pass = 0; pass < 6; pass++) UiLayoutTest.layout(root);
  }

  private static Object value(Object owner, String field) {
    try { return get(owner, field); }
    catch (Exception error) { throw new AssertionError(error); }
  }

  private static void inside(JPanel root, String name, String context) {
    Component action = SimpleWorkflowTest.named(root, name);
    check(action instanceof AbstractButton && visible(root, name), context + " exposes " + name);
    Point point = SwingUtilities.convertPoint(action, 0, 0, root);
    check(action.getWidth() > 0 && action.getHeight() > 0 && point.x >= 0 && point.y >= 0
            && point.x + action.getWidth() <= root.getWidth()
            && point.y + action.getHeight() <= root.getHeight(),
        context + " keeps " + name + " inside the available workspace");
  }

  private static void referenceTypography() throws Exception {
    Font reference;
    try (var resource = WorkbenchUi.class.getResourceAsStream("/fonts/NanumGothic-Regular.ttf")) {
      check(resource != null, "the packaged classic UI includes the reference Nanum Gothic font");
      reference = Font.createFont(Font.TRUETYPE_FONT, resource);
    }
    check(WorkbenchUi.font().getPSName().equals(reference.getPSName())
            && WorkbenchUi.font().canDisplayUpTo("요청·응답 목록 · 기능 자동 매핑") < 0,
        "classic UI typography uses the bundled reference font with readable Korean tool names");
    String ascii = "GET /api/example HTTP/1.1\r\nHost: classic-layout.test";
    check(WorkbenchUi.textFont(ascii).getFamily(Locale.ROOT).equals(Font.MONOSPACED),
        "ASCII request text retains the reference monospaced code typography");
    String korean = "요청 원본과 응답 본문";
    Font text = WorkbenchUi.textFont(korean);
    check(text.canDisplayUpTo(korean) < 0, "packet text selects a font that displays Korean response content");
    check(new Font(Font.MONOSPACED, Font.PLAIN, 14).canDisplayUpTo(korean) < 0
            || text.getPSName().equals(reference.getPSName()),
        "when the host monospace lacks Korean glyphs packet text falls back to the bundled font");
    int previousScale = Math.round(WorkbenchUi.displaySize(100f));
    boolean compact = WorkbenchUi.appearanceCompact();
    try {
      for (int scale : new int[] {125, 150}) {
        WorkbenchUi.setAppearance(scale, compact);
        check(Math.abs(WorkbenchUi.font().getSize2D() - 14f * scale / 100f) < .01f
                && WorkbenchUi.font().getPSName().equals(reference.getPSName()),
            "classic UI font retains its resource identity and requested " + scale + "% scale");
        check(Math.abs(WorkbenchUi.textFont(ascii).getSize2D() - 14f * scale / 100f) < .01f,
            "ASCII packet typography follows the same " + scale + "% display scale");
      }
    } finally { WorkbenchUi.setAppearance(previousScale, compact); }
  }

  public static void main(String[] args) throws Exception {
    ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (owner, method, arguments) -> def(method));
    var entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks) PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(() -> entry.initialize(SimpleWorkflowTest.previewApi()));
    edt(() -> {});
    var extension = (WorkbenchExtension) get(entry, "workbench");
    var root = (JPanel) get(extension, "root");
    var pages = (JTabbedPane) get(extension, "tabs");
    var navigation = (FolderNavigation) get(extension, "navigation");
    var tree = (JTree) SimpleWorkflowTest.named(root, "wb.navigation.tree");
    var split = (JSplitPane) SimpleWorkflowTest.named(root, "wb.navigation.split");
    var table = (JTable) get(extension, "logTable");
    var codec = (JTextArea) get(extension, "codecIn");
    var payload = (JTextArea) get(get(entry, "payloadRunner"), "request");
    Color previousBackground = UIManager.getColor("Panel.background");
    try {
      referenceTypography();
      check(tree != null && split != null && SwingUtilities.isDescendingFrom(pages, navigation)
              && SwingUtilities.isDescendingFrom(navigation, root),
          "the production Simple workspace mounts the real folder navigation and retained pages");
      edt(() -> {
        root.setSize(1280, 800); navigation.restoreLayout(); layout(root);
        check(!visible(root, "wb.simple.navigation") && !visible(root, "wb.simple.tools"),
            "the large eight-button Simple header is replaced by the requested classic shell");
        check(!visible(root, "wb.next.version") && !visible(root, "wb.work.context"),
            "classic layout omits the later Next badge and extra work-context header");
        var groups = new ArrayList<String>();
        var modelRoot = (DefaultMutableTreeNode) tree.getModel().getRoot();
        for (int index = 0; index < modelRoot.getChildCount(); index++)
          groups.add(modelRoot.getChildAt(index).toString());
        check(groups.equals(List.of("트래픽", "탐색·정보 수집", "보안 분석·검증", "데이터 도구", "점검 관리·증거")),
            "the five task folders follow the attached 0.39.2 layout");
        for (String id : PRIMARY)
          check(tree.getRowForPath(route(tree, id)) >= 0, "expanded folders expose the retained " + id + " tool");
      });

      seedTraffic(extension, request("https://classic-layout.test/selected", "GET", true, List.of()),
          response(200, "CLASSIC_LAYOUT_ORIGINAL"));
      edt(() -> {
        select(tree, "traffic");
        table.setRowSelectionInterval(0, 0);
        Object original = value(extension, "current");
        codec.setText("UNSAVED_CLASSIC_CODEC");
        payload.setText("UNSAVED_CLASSIC_PAYLOAD");
        var enginePages = new LinkedHashMap<String, Component>();
        for (String id : PRIMARY) {
          select(tree, id);
          check(SimpleWorkflowTest.selectedFeature(pages).equals(id), "actual folder selection opens " + id);
          enginePages.put(id, pages.getSelectedComponent());
        }
        for (String id : PRIMARY) {
          select(tree, id);
          check(pages.getSelectedComponent() == enginePages.get(id), "folder navigation reuses the existing " + id + " engine");
        }
        for (int index = 0; index < PRIMARY.size(); index++) {
          String id = PRIMARY.get(index);
          Object binding = navigation.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
              .get(KeyStroke.getKeyStroke("alt " + (index + 1)));
          var action = binding == null ? null : navigation.getActionMap().get(binding);
          check(action != null, "classic navigation retains the Alt+" + (index + 1) + " tool shortcut");
          action.actionPerformed(new ActionEvent(navigation, ActionEvent.ACTION_PERFORMED, "classic-shortcut"));
          check(pages.getSelectedComponent() == enginePages.get(id)
                  && tree.getSelectionPath().equals(route(tree, id)),
              "Alt+" + (index + 1) + " opens the retained " + id + " engine and selects its folder leaf");
        }
        var trafficTools = (JTabbedPane) value(extension, "trafficTools");
        var exploration = (JTabbedPane) value(extension, "explorationTools");
        var mapper = (JTabbedPane) value(extension, "mapperTools");
        for (Object[] alias : new Object[][] {
            {"data", "traffic", trafficTools}, {"passive", "environment", exploration}, {"activity", "mapper", mapper}}) {
          select(tree, (String) alias[0]);
          check(pages.getSelectedComponent() == enginePages.get(alias[1])
                  && ((JTabbedPane) alias[2]).getSelectedIndex() == 1,
              "classic " + alias[0] + " leaf opens the retained nested tool rather than a new engine");
          check(tree.getSelectionPath().equals(route(tree, (String) alias[0])),
              "nested route keeps the actual " + alias[0] + " leaf selected");
          JTabbedPane nested = (JTabbedPane) alias[2];
          Component retained = nested.getComponentAt(1);
          layout(root);
          Insets border = nested.getInsets();
          check(retained.getY() <= border.top + 1 && retained.getX() <= border.left + 1
                  && retained.getHeight() >= nested.getHeight() - border.top - border.bottom - 2,
              "classic " + alias[0] + " content fills the working pane without a duplicate tab-strip row");
          select(tree, (String) alias[1]);
          select(tree, (String) alias[0]);
          check(nested.getSelectedComponent() == retained && nested.getSelectedIndex() == 1,
              "hidden inner tabs retain the exact " + alias[0] + " controller across folder navigation");
        }
        check(value(extension, "current") == original && table.getSelectedRow() == 0
                && codec.getText().equals("UNSAVED_CLASSIC_CODEC")
                && payload.getText().equals("UNSAVED_CLASSIC_PAYLOAD"),
            "primary and alias routes preserve the selected original and authored input");
        select(tree, "dashboard");
        check(pages.getSelectedComponent() == value(extension, "scopeDashboard")
                && tree.getSelectionPath().equals(route(tree, "dashboard")),
            "dashboard folder entry selects the production page and its own tree leaf");
        var current = (JLabel) SimpleWorkflowTest.named(root, "wb.navigation.current");
        check(current.getText().contains(FeatureGuide.title("dashboard")),
            "compact breadcrumb describes the dashboard instead of the previously selected tool");
        select(tree, "auth");
        SimpleWorkflowTest.click(root, "wb.simple.dashboard.open");
        check(pages.getSelectedComponent() == value(extension, "scopeDashboard")
                && tree.getSelectionPath().equals(route(tree, "dashboard")),
            "compact dashboard action synchronizes the same page and folder selection");
      });

      var opened = new AtomicReference<Component>();
      edt(() -> {
        extension.dialogOpener = (owner, title, content, modal, closed) -> { opened.set(content); return null; };
        Component selectedPage = pages.getSelectedComponent();
        SimpleWorkflowTest.click(root, "wb.scope.inspection.open");
        check(opened.get() instanceof ScopeInspectionPanel && opened.get() == value(extension, "scopeInspectionPanel"),
            "compact Scope action opens the current production inspection controller");
        check(pages.getSelectedComponent() == selectedPage,
            "opening Scope settings retains the active dashboard and original page instances");
        SimpleWorkflowTest.click(root, "wb.navigation.settings");
        check(opened.get() == value(extension, "commonSettingsContent") && pages.getSelectedComponent() == selectedPage,
            "classic Settings action keeps the active tool and opens current display settings");
      });

      for (boolean dark : new boolean[] {false, true}) {
        for (int[] size : new int[][] {{1024, 720}, {1280, 800}}) {
          String context = (dark ? "dark" : "light") + " " + size[0] + "x" + size[1];
          edt(() -> {
            UIManager.put("Panel.background", dark ? new Color(30, 33, 39) : new Color(245, 247, 250));
            WorkbenchUi.style(root);
            select(tree, "traffic");
            root.setSize(size[0], size[1]);
            navigation.restoreLayout();
            layout(root);
          });
          edt(() -> {});
          edt(() -> {
            layout(root);
            check(WorkbenchUi.dark() == dark, context + " uses the selected host palette");
            check(split.getLeftComponent().isVisible() && split.getLeftComponent().getWidth() >= 160
                    && pages.getWidth() >= 360,
                context + " retains a readable sidebar and working area");
            Component toolbar = SimpleWorkflowTest.named(root, "wb.navigation.toolbar.line");
            check(toolbar != null && toolbar.getHeight() > 0 && toolbar.getHeight() <= 64,
                context + " keeps the classic navigation toolbar compact");
            for (String name : List.of("wb.navigation.toggle", "wb.feature.help", "wb.navigation.settings",
                "wb.navigation.logs", "wb.simple.dashboard.open", "wb.scope.inspection.open")) inside(root, name, context);
            var messages = (JSplitPane) SimpleWorkflowTest.named(root, "wb.traffic.messages");
            var workspace = (JSplitPane) SimpleWorkflowTest.named(root, "wb.traffic.workspace");
            check(workspace.getOrientation() == JSplitPane.VERTICAL_SPLIT
                    && messages.getOrientation() == JSplitPane.HORIZONTAL_SPLIT,
                context + " retains the reference list-above and request/response-side-by-side arrangement");
            check(messages.getLeftComponent().getWidth() >= 100 && messages.getRightComponent().getWidth() >= 100,
                context + " leaves both packet readers usable beside the folder menu");
          });
        }
      }

      edt(() -> {
        var toggle = (JButton) SimpleWorkflowTest.named(root, "wb.navigation.toggle");
        split.setDividerLocation(275);
        layout(root);
        Component selectedPage = pages.getSelectedComponent();
        int originalWidth = pages.getWidth();
        toggle.doClick(0); layout(root);
        check(!split.getLeftComponent().isVisible() && pages.getWidth() > originalWidth + 200,
            "classic menu hide immediately returns its width to the existing working page");
        toggle.doClick(0); layout(root);
        check(split.getLeftComponent().isVisible() && Math.abs(split.getDividerLocation() - 275) <= 1,
            "classic menu restore preserves the user's chosen sidebar width");
        check(pages.getSelectedComponent() == selectedPage && codec.getText().equals("UNSAVED_CLASSIC_CODEC")
                && payload.getText().equals("UNSAVED_CLASSIC_PAYLOAD"),
            "menu resize and hide/show preserve the active page and unsaved editors");
        check(PayloadIntegrationTest.sent.get() == 0,
            "classic layout, all folder routes, dashboard and settings opening send no target requests");
      });
    } finally {
      UIManager.put("Panel.background", previousBackground);
      SimpleWorkflowTest.unload();
    }
    System.out.println("SimpleClassicLayoutTest: " + checks + " checks passed (production Swing/API adapters; zero target HTTP)");
  }
}
