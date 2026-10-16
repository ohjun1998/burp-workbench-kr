package kr.workbench;

import burp.*;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.tree.*;

/** Production page selection, retained work, menu resize/hide, and preference recovery. Zero HTTP. */
public final class FolderNavigationTest extends WorkbenchTest {
  private static DefaultMutableTreeNode root(JTree tree) {
    return (DefaultMutableTreeNode) tree.getModel().getRoot();
  }

  private static TreePath path(JTree tree, String title) {
    var nodes = root(tree).depthFirstEnumeration();
    while (nodes.hasMoreElements()) {
      var node = (DefaultMutableTreeNode) nodes.nextElement();
      if (title.equals(node.toString())) return new TreePath(node.getPath());
    }
    throw new AssertionError("Missing navigation: " + title);
  }

  private static void layout(JPanel panel, int width) {
    panel.setSize(width, 720);
    for (int i = 0; i < 5; i++) UiLayoutTest.layout(panel);
  }

  private static MontoyaApi preferences(Map<String, String> saved) {
    Preferences preferences = proxy(Preferences.class, (owner, method, args) -> switch (method.getName()) {
      case "getString" -> saved.get((String) args[0]);
      case "setString" -> { saved.put((String) args[0], (String) args[1]); yield null; }
      default -> def(method);
    });
    Persistence persistence = proxy(Persistence.class,
        (owner, method, args) -> method.getName().equals("preferences") ? preferences : def(method));
    return proxy(MontoyaApi.class,
        (owner, method, args) -> method.getName().equals("persistence") ? persistence : def(method));
  }

  public static void main(String[] args) throws Exception {
    BurpExtender entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks) PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(() -> entry.initialize((MontoyaApi) PayloadIntegrationTest.host(MontoyaApi.class)));
    edt(() -> {});
    WorkbenchExtension extension = (WorkbenchExtension) get(entry, "workbench");
    JPanel panel = (JPanel) get(extension, "root");
    JTabbedPane pages = (JTabbedPane) get(extension, "tabs");
    seedTraffic(extension, request("https://folder.test/selected", "GET", true, List.of()), response(200, "original"));
    try {
      edt(() -> {
        JTree tree = (JTree) UiLayoutTest.named(panel, "wb.navigation.tree");
        JSplitPane split = (JSplitPane) UiLayoutTest.named(panel, "wb.navigation.split");
        JButton toggle = (JButton) UiLayoutTest.named(panel, "wb.navigation.toggle");
        check(tree != null && split != null && toggle != null, "folder tree, resizable split and persistent toggle exist");
        List<String> groups = new ArrayList<>();
        List<String> leaves = new ArrayList<>();
        for (int i = 0; i < root(tree).getChildCount(); i++) {
          var folder = (DefaultMutableTreeNode) root(tree).getChildAt(i);
          groups.add(folder.toString());
          for (int j = 0; j < folder.getChildCount(); j++) leaves.add(folder.getChildAt(j).toString());
        }
        check(groups.equals(List.of("트래픽", "탐색·정보 수집", "보안 분석·검증", "데이터 도구", "점검 관리·증거")),
            "exactly the five agreed folders in order");
        check(leaves.equals(List.of("요청·응답 목록", "JS에서 API 찾기", "서버·경로 탐색", "URL 후보 수집",
            "계정 권한 비교", "응답 보안 분석", "입력값 보안 검사", "데이터 변환·비교", "점검 진행 현황", "재현·증거 정리")),
            "exactly the ten existing tools in their agreed folders; no SCOPE or extra extension entry");
        JTable traffic = (JTable) unchecked(extension, "logTable");
        traffic.setRowSelectionInterval(0, 0);
        Object source = unchecked(extension, "current");
        JTextArea codec = (JTextArea) unchecked(extension, "codecIn");
        codec.setText("UNSAVED_FOLDER_INPUT");
        Object runner = unchecked(entry, "payloadRunner");
        JTextArea payload = (JTextArea) unchecked(runner, "request");
        payload.setText("UNSAVED_PAYLOAD_INPUT");
        for (String leaf : leaves) {
          tree.setSelectionPath(path(tree, leaf));
          check(pages.getTitleAt(pages.getSelectedIndex()).equals(leaf), "folder selection opens production tool: " + leaf);
        }
        check(unchecked(extension, "current") == source && traffic.getSelectedRow() == 0
            && codec.getText().equals("UNSAVED_FOLDER_INPUT") && payload.getText().equals("UNSAVED_PAYLOAD_INPUT"),
            "all ten routes preserve selected traffic and unsaved editor work");
        var auth = path(tree, "계정 권한 비교");
        tree.collapsePath(auth.getParentPath());
        pages.setSelectedIndex(pages.indexOfTab("계정 권한 비교"));
        check(tree.getSelectionPath().equals(auth) && tree.isExpanded(auth.getParentPath()),
            "programmatic tool navigation expands its folder and selects the matching leaf");
        var discovery = path(tree, "JS에서 API 찾기").getParentPath();
        tree.collapsePath(discovery);
        Component selected = pages.getSelectedComponent();
        for (int width : new int[] {1024, 1280, 1440}) {
          layout(panel, width);
          check(pages.getSelectedComponent().getY() <= 4,
              "the old horizontal tool strip reserves no page height at " + width);
          split.setDividerLocation(285);
          UiLayoutTest.layout(panel);
          check(Math.abs(split.getDividerLocation() - 285) <= 1, "menu divider accepts user width at " + width);
          int shownWidth = pages.getWidth();
          toggle.doClick();
          layout(panel, width);
          check(!split.getLeftComponent().isVisible() && pages.getWidth() > shownWidth + 250
              && toggle.isVisible() && toggle.getText().equals("메뉴 보기"),
              "hide frees content width and leaves a visible restore action at " + width);
          toggle.doClick();
          layout(panel, width);
          check(split.getLeftComponent().isVisible() && Math.abs(split.getDividerLocation() - 285) <= 1,
              "show restores the user's previous width at " + width);
          check(pages.getSelectedComponent() == selected && !tree.isExpanded(discovery)
              && codec.getText().equals("UNSAVED_FOLDER_INPUT") && unchecked(extension, "current") == source,
              "hide/show preserves tool, folder expansion, manual input and selected traffic at " + width);
        }
        split.setDividerLocation(900);
        check(split.getDividerLocation() <= 420 && pages.getMinimumSize().width == 360,
            "oversized drag is clamped to protect the workspace");
        toggle.doClick();
        pages.setSelectedIndex(pages.indexOfTab("JS에서 API 찾기"));
        check(!split.getLeftComponent().isVisible()
            && tree.getSelectionPath().equals(path(tree, "JS에서 API 찾기")),
            "programmatic navigation updates the selected tool while respecting the hidden menu");
        toggle.doClick();
        var action = panel.getActionMap().get("search");
        action.actionPerformed(new java.awt.event.ActionEvent(panel, 0, "search"));
        check(tree.getSelectionPath().equals(path(tree, "요청·응답 목록")), "Ctrl+F navigation also synchronizes the folder selection");
        List<Component> dialogs = new ArrayList<>();
        extension.dialogOpener = (owner, title, content, modal, closed) -> { dialogs.add(content); return null; };
        ((JButton) UiLayoutTest.named(panel, "wb.navigation.settings")).doClick();
        check(dialogs.size() == 1 && pages.getSelectedIndex() == 0, "settings remains a separate action and preserves the active tool");
        check(PayloadIntegrationTest.sent.get() == 0, "folder selection, resize, hide/show and settings send zero HTTP requests");
      });
      edt(() -> {
        Map<String, String> saved = new HashMap<>();
        saved.put("wb.navigation.width", "305");
        saved.put("wb.navigation.hidden", "true");
        JTabbedPane pages2 = new FolderNavigation.Pages();
        JPanel content = new JPanel();content.putClientProperty("wb.feature.id", "traffic");
        pages2.addTab("요청·응답 목록", content);
        FolderNavigation navigation = new FolderNavigation(preferences(saved), pages2, () -> {});
        WorkbenchUi.style(navigation);navigation.restoreLayout();
        layout(navigation, 1280);
        JSplitPane split = (JSplitPane) UiLayoutTest.named(navigation, "wb.navigation.split");
        JButton toggle = (JButton) UiLayoutTest.named(navigation, "wb.navigation.toggle");
        check(!split.getLeftComponent().isVisible() && split.getDividerSize() == 0, "saved hidden state restores without reserving a divider");
        toggle.doClick();layout(navigation, 1280);
        check(split.getDividerLocation() == 305 && saved.get("wb.navigation.hidden").equals("false"),
            "show restores saved width and persists visibility");
        split.setDividerLocation(250);
        check(saved.get("wb.navigation.width").equals("250"), "user width is persisted in Burp preferences");
        navigation.getActionMap().get("toggleMenu").actionPerformed(new java.awt.event.ActionEvent(navigation, 0, "toggleMenu"));
        check(saved.get("wb.navigation.hidden").equals("true"), "keyboard menu shortcut toggles and persists visibility");
        saved.put("wb.navigation.width", "broken");
        saved.put("wb.navigation.hidden", "false");
        FolderNavigation recovered = new FolderNavigation(preferences(saved), new FolderNavigation.Pages(), () -> {});
        recovered.restoreLayout();layout(recovered, 1024);
        check(((JSplitPane) UiLayoutTest.named(recovered, "wb.navigation.split")).getDividerLocation() == 220,
            "invalid saved width falls back to the default without blocking startup");
      });
    } finally {
      for (var unload : PayloadIntegrationTest.modernUnloads) unload.extensionUnloaded();
      for (var unload : PayloadIntegrationTest.legacyUnloads) unload.extensionUnloaded();
      edt(() -> {});
    }
    System.out.println("FolderNavigationTest: " + checks + " checks passed (production Swing; zero HTTP)");
  }

  private static Object unchecked(Object owner, String name) {
    try { return get(owner, name); }
    catch (Exception error) { throw new AssertionError(error); }
  }
}
