package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.Preferences;
import java.awt.Component;
import java.awt.Container;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

/** Production Simple routes, retained originals and settings isolation; no remote HTTP. */
public final class SimpleWorkflowTest extends WorkbenchTest {
  static MontoyaApi previewApi() {
    Map<String, Map<String, Object>> values = new ConcurrentHashMap<>();
    var data = proxy(PersistedObject.class, (p, m, a) -> {
      String name = m.getName();
      if (name.equals("toString")) return "Isolated Simple preview persistence";
      for (String operation : List.of("get", "set", "delete")) {
        if (!name.startsWith(operation) || a == null || a.length == 0) continue;
        var store = values.computeIfAbsent(name.substring(operation.length()), unused -> new ConcurrentHashMap<>());
        String key = a[0].toString();
        if (operation.equals("get")) return store.get(key);
        if (operation.equals("delete")) { store.remove(key); return null; }
        if (a[1] == null) store.remove(key); else store.put(key, a[1]);
        return null;
      }
      if (name.endsWith("Keys")) {
        String kind = name.substring(0, name.length() - 4);
        kind = Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
        return Set.copyOf(values.getOrDefault(kind, Map.of()).keySet());
      }
      return def(m);
    });
    data.setString(BuildInfo.storageKey("auth-setup-library-project-v1"), "22222222-2222-4222-8222-222222222222");
    var fallbackPersistence = PayloadIntegrationTest.host(Persistence.class);
    var persistence = proxy(Persistence.class, (p, m, a) ->
        m.getName().equals("extensionData") ? data : m.invoke(fallbackPersistence, a));
    var fallback = PayloadIntegrationTest.host(MontoyaApi.class);
    return proxy(MontoyaApi.class, (p, m, a) ->
        m.getName().equals("persistence") ? persistence : m.invoke(fallback, a));
  }

  static Component named(Component root, String name) {
    if (name.equals(root.getName())) return root;
    if (root instanceof Container parent) for (var child : parent.getComponents()) {
      Component result = named(child, name);
      if (result != null) return result;
    }
    return null;
  }

  static List<Component> visibleComponents(Component root) {
    if (!root.isVisible()) return List.of();
    var values = new ArrayList<Component>(); values.add(root);
    if (root instanceof Container parent) for (var child : parent.getComponents()) values.addAll(visibleComponents(child));
    return values;
  }

  static void click(Component root, String name) {
    if (name.startsWith("wb.simple.tool.")) {
      JTree tree = (JTree) named(root, "wb.navigation.tree");
      TreePath path = toolPath(root, name.substring("wb.simple.tool.".length()));
      tree.expandPath(path.getParentPath());
      tree.setSelectionPath(path);
      tree.scrollPathToVisible(path);
      return;
    }
    Component value = named(root, name);
    if (!(value instanceof AbstractButton button)) throw new AssertionError("Missing control: " + name);
    button.doClick(0);
  }

  static TreePath toolPath(Component root, String id) {
    Component navigation = named(root, "wb.navigation.tree");
    if (!(navigation instanceof JTree tree)) throw new AssertionError("Missing folder navigation");
    var nodes = ((DefaultMutableTreeNode) tree.getModel().getRoot()).depthFirstEnumeration();
    while (nodes.hasMoreElements()) {
      var node = (DefaultMutableTreeNode) nodes.nextElement();
      if (node.isLeaf() && FeatureGuide.title(id).equals(node.toString())) return new TreePath(node.getPath());
    }
    throw new AssertionError("Missing navigation tool: " + id);
  }

  static boolean toolVisible(Component root, String id) {
    JTree tree = (JTree) named(root, "wb.navigation.tree");
    return tree != null && tree.isVisible() && tree.getRowForPath(toolPath(root, id)) >= 0;
  }

  static boolean toolSelected(Component root, String id) {
    JTree tree = (JTree) named(root, "wb.navigation.tree");
    return tree != null && toolPath(root, id).equals(tree.getSelectionPath());
  }

  static void clickText(Component root, String text) {
    var action = visibleComponents(root).stream().filter(c -> c instanceof AbstractButton button && text.equals(button.getText()))
        .map(c -> (AbstractButton)c).findFirst().orElseThrow(() -> new AssertionError("Missing action: " + text));
    action.doClick(0);
  }

  static String selectedFeature(JTabbedPane tabs) {
    return Objects.toString(((JComponent) tabs.getSelectedComponent()).getClientProperty("wb.feature.id"), "");
  }

  static void unload() throws Exception {
    for (var handler : PayloadIntegrationTest.modernUnloads) handler.extensionUnloaded();
    for (var handler : PayloadIntegrationTest.legacyUnloads) handler.extensionUnloaded();
    edt(() -> {});
  }

  public static void main(String[] args) throws Exception {
    check(BuildInfo.DISPLAY_NAME.contains("Simple") && FeatureGuide.SUITE_TAB.contains("Simple"), "Simple identity is visible in extension and suite tab");
    check(BuildInfo.STORAGE_DIRECTORY.equals(".burp-workbench-simple") && BuildInfo.storageKey("x").equals("simple.x"), "Simple uses its own disk and persistence namespaces");
    Map<String, Object> saved = new HashMap<>();
    saved.put("next.wb.layout.horizontal", false);
    saved.put("next.wb.layout.reference.v1", true);
    saved.put("wb.layout.horizontal", false);
    var before = Map.copyOf(saved);
    var preferences = proxy(Preferences.class, (p, m, a) -> switch (m.getName()) {
      case "getBoolean", "getString" -> saved.get(a[0]);
      case "setBoolean", "setString" -> { saved.put(a[0].toString(), a[1]); yield null; }
      default -> def(m);
    });
    var persistence = proxy(Persistence.class, (p, m, a) -> m.getName().equals("preferences") ? preferences : def(m));
    var settingsApi = proxy(MontoyaApi.class, (p, m, a) -> m.getName().equals("persistence") ? persistence : def(m));
    edt(() -> {
      var layout = new LayoutSettings(settingsApi, new JPanel());
      layout.choice.setSelectedIndex(0);
      check(Boolean.FALSE.equals(saved.get("simple.wb.layout.horizontal")), "Simple settings write only their own key");
      layout.close();
    });
    for (var entry : before.entrySet()) check(Objects.equals(saved.get(entry.getKey()), entry.getValue()), "existing setting preserved: " + entry.getKey());

    ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p, m, a) -> def(m));
    var entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks) PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(() -> entry.initialize(previewApi())); edt(() -> {});
    var extension = (WorkbenchExtension) get(entry, "workbench");
    var root = (JPanel) get(extension, "root");
    var tabs = (JTabbedPane) get(extension, "tabs");
    var auth = (AuthComparePanel) get(extension, "authCompare");
    var evidence = (BountySupportPanel) get(extension, "bountySupport");
    int sends = PayloadIntegrationTest.sent.get();
    try {
      edt(() -> {
        check(named(root, "wb.navigation.tree") instanceof JTree, "classic folder navigation is attached to production workspace");
        var browser = (TrafficBrowser) unchecked(extension, "trafficBrowser");
        check(!visibleComponents(root).contains(named(root, "wb.traffic.conditions.saved")), "saved-filter management is absent from the running list");
        check(!browser.recoveryActions.isVisible(), "empty unfiltered list omits meaningless recovery controls");
        var searchSettings = browser.content();
        var savedChoice = named(searchSettings, "wb.traffic.conditions.saved");
        check(savedChoice != null && savedChoice.isVisible(), "saved-filter management remains available in actual search settings");
        check(!SwingUtilities.isDescendingFrom(savedChoice, root), "search settings do not restore saved-filter clutter in the main list");
        check(!((JButton) named(root, "wb.simple.traffic.evidence")).isEnabled(), "next-request actions explain disabled state before selecting an original");
        String[] features = {"traffic", "mapper", "auth", "js", "environment", "response.recon", "payload", "bounty"};
        var enginePages = new LinkedHashMap<String, Component>();
        for (String feature : features) {
          check(toolVisible(root, feature), "folder navigation exposes retained " + feature + " tool");
          click(root, "wb.simple.tool." + feature);
          check(selectedFeature(tabs).equals(feature), "direct navigation opens retained " + feature + " engine");
          enginePages.put(feature, tabs.getSelectedComponent());
        }
        check(Arrays.stream(features).allMatch(feature -> toolVisible(root, feature)), "all eight existing major tools remain available together");
        check(evidence.pages.getSelectedIndex() == 3, "replay/evidence tool starts on fixed evidence route index 3");
        click(root, "wb.simple.bounty.replay");
        check(evidence.pages.getSelectedIndex() == 1 && named(evidence,"wb.bounty.flow.run") == evidence.flowRun, "manual request replay is a visible core function at stable index 1");
        click(root, "wb.simple.bounty.evidence");
        check(evidence.pages.getSelectedIndex() == 3, "evidence mode remains directly accessible at stable index 3");
        for (int page : new int[] {0, 2, 4}) {
          evidence.pages.setSelectedIndex(page);
          check(evidence.pages.getSelectedIndex() == 3, "auxiliary replay/evidence slot " + page + " cannot open an empty helper");
        }
        var js = (JsAnalysisPanel) unchecked(extension,"jsAnalysis"); js.input.setText("fetch('/api/retained-source');");
        var runner = unchecked(entry,"payloadRunner");
        var payloadInput = (JTextArea) unchecked(runner,"request"); payloadInput.setText("MANUALLY_EDITED_PAYLOAD_INPUT");
        for (String feature : features) {
          click(root,"wb.simple.tool."+feature);
          check(tabs.getSelectedComponent()==enginePages.get(feature), "navigation retains the same " + feature + " engine instance");
        }
        check(js.input.getText().equals("fetch('/api/retained-source');") && payloadInput.getText().equals("MANUALLY_EDITED_PAYLOAD_INPUT"), "switching tools preserves JS and payload engine input");
        click(root,"wb.simple.tool.payload");
        for (String name : List.of("settings","start","stop","evidence")) check(named(root,"wb.simple.payload."+name)!=null, "input testing keeps direct " + name + " control");
        check(!((AbstractButton)named(root,"wb.simple.payload.evidence")).isEnabled(), "input-testing evidence remains disabled without completed results");
        click(root,"wb.simple.tool.js");
        var toolsMenu=visibleComponents(js).stream().filter(c -> c instanceof JComponent j && j.getClientProperty("wb.simple.js.tools.menu") instanceof JPopupMenu)
            .map(c -> (JPopupMenu)((JComponent)c).getClientProperty("wb.simple.js.tools.menu")).findFirst().orElseThrow();
        var menuLabels=Arrays.stream(toolsMenu.getComponents()).filter(c -> c instanceof JMenuItem).map(c -> ((JMenuItem)c).getText()).toList();
        check(menuLabels.contains("관찰된 API 패킷 보기"), "JS tools retain observed source packet inspection");
        check(menuLabels.stream().noneMatch(text -> text.toLowerCase(Locale.ROOT).contains("npm") || text.contains("소스맵") || text.toLowerCase(Locale.ROOT).contains("source map")), "JS auxiliary network discovery is absent from the running tools menu");
        click(root, "wb.simple.tool.auth");
        check(named(root, "wb.simple.auth.review") != null, "explicit comparison remains available");
        check(!visibleComponents(root).contains(auth.record), "automatic retransmission control is removed from visible Simple flow");
        check(!auth.record.isSelected() && auth.recording.get() == null, "Simple starts without automatic retransmission");
        var names = visibleComponents(root).stream().map(Component::getName).filter(Objects::nonNull).toList();
        check(!names.contains("wb.simple.header") && !names.contains("wb.work.context")
            && names.contains("wb.navigation.tree"), "classic folders replace the large Simple header without an extra context strip");
        var input = (JTextArea) unchecked(extension, "codecIn"); input.setText("SIMPLE_PENDING_LOCAL_INPUT");
        var dialog = new AtomicReference<Component>();
        extension.dialogOpener = (owner, title, body, modal, closed) -> {
          check(!modal && title.contains("Simple"), "consolidated utility opens a modeless Simple window");
          dialog.set(body); return null;
        };
        check(named(root, "wb.feature.help") instanceof AbstractButton
            && named(root, "wb.navigation.settings") instanceof AbstractButton
            && named(root, "wb.navigation.logs") instanceof AbstractButton,
            "compact top bar exposes help, appearance and diagnostics");
        click(root, "wb.feature.help");
        check(dialog.get() != null && named(dialog.get(), "wb.feature.guide.text") != null, "current-tool help is reachable from the actual top bar");
        root.getActionMap().get("featureHelp").actionPerformed(new java.awt.event.ActionEvent(root, 0, "F1"));
        check(named(dialog.get(), "wb.feature.guide.choice") != null, "F1 opens the same retained tool guidance");
        click(root, "wb.navigation.settings");
        check(named(dialog.get(), "wb.settings.layout.common") != null, "appearance settings remain reachable from the top bar");
        click(root, "wb.navigation.logs");
        check(named(dialog.get(), "diagnostics.table") != null, "collection and error diagnostics remain reachable from the top bar");
        check(input.getText().equals("SIMPLE_PENDING_LOCAL_INPUT") && selectedFeature(tabs).equals("auth"), "opening utilities preserves the current tool and unsaved input");
      });
      var origin = "https://simple.example.test";
      edt(() -> ((WorkbenchScope) unchecked(extension, "localScope")).add(origin + "/", true));
      var original = request(origin + "/api/documents/101", "GET", true,
          List.of(header("Cookie", "SID=SIMPLE_PRIVATE_SESSION"), header("Accept", "application/json")));
      var reply = response(200, "{\"id\":101,\"title\":\"검토 문서\"}");
      check(((JButton) get(extension, "captureButton")).getText().equals("OFF"), "Simple collector starts OFF");
      importTraffic(extension, original, reply);
      check(((JTable) get(extension, "logTable")).getRowCount() == 0, "History import does not collect while OFF");
      edt(() -> ((JButton) unchecked(extension, "captureButton")).doClick(0));
      importTraffic(extension, original, reply);
      check(((JTable) get(extension, "logTable")).getRowCount() == 1, "SCOPE mode imports the approved original");
      var outside = request("https://outside.example.test/api/documents/101", "GET", false, List.of());
      importTraffic(extension, outside, reply);
      check(((JTable) get(extension, "logTable")).getRowCount() == 1, "SCOPE mode rejects an outside original");
      edt(() -> ((JButton) unchecked(extension, "captureButton")).doClick(0));
      importTraffic(extension, original, reply);
      check(((JTable) get(extension, "logTable")).getRowCount() == 1, "turning capture OFF prevents another History import");
      edt(() -> {
        click(root, "wb.simple.tool.traffic");
        var table = (JTable) unchecked(extension, "logTable");
        table.setRowSelectionInterval(0, 0);
        click(root,"wb.simple.tool.bounty"); click(root,"wb.simple.bounty.replay");
        clickText(evidence,"선택 요청 추가");
        check(evidence.workflow.getText().equals(original.toString()), "manual replay appends the exact selected request without rewriting its source");
        check(evidence.flowOrigin.getText().equals(origin) && !evidence.running && evidence.flowModel.getRowCount()==0, "replay preparation retains target origin and waits for explicit execution");
        click(root,"wb.simple.tool.js"); click(root,"wb.simple.tool.bounty");
        check(evidence.pages.getSelectedIndex()==1 && evidence.workflow.getText().equals(original.toString()), "returning to replay preserves prepared request and mode");
        click(root,"wb.simple.tool.traffic");
        click(root, "wb.simple.traffic.evidence");
        check(selectedFeature(tabs).equals("bounty"), "traffic evidence action opens the retained replay/evidence tool");
        check(evidence.evidence.size() == 1, "selected captured original is added to evidence after tool navigation");
        check(evidence.evidence.get(0).request().contains("SIMPLE_PRIVATE_SESSION"), "private evidence retains exact original request bytes");
        check(!evidence.evidencePreview().contains("SIMPLE_PRIVATE_SESSION"), "submission preview redacts captured session secret");
        check(evidence.evidenceSave.isEnabled() && evidence.evidencePreview.isEnabled(), "preview and ZIP actions activate for retained evidence");
        click(root, "wb.simple.tool.traffic");
        check(table.getSelectedRow() == 0, "tool navigation preserves selected original row");
      });
      check(PayloadIntegrationTest.sent.get() == sends, "loading, navigating, preparing and previewing Simple sends zero HTTP");
    } finally { unload(); }
    System.out.println("SimpleWorkflowTest: " + checks + " checks passed");
  }

  static Object unchecked(Object owner, String field) {
    try { return get(owner, field); } catch (Exception error) { throw new AssertionError(error); }
  }
}
