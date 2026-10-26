package kr.workbench;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Retained production navigation, guarded history and local purpose lookup; no target requests. */
public final class Navigation0532Test extends WorkbenchTest {
  private static JPanel page(String id, Component content) {
    JPanel page = new JPanel(new BorderLayout()); page.putClientProperty("wb.feature.id", id); page.add(content); return page;
  }
  public static void main(String[] args) throws Exception {
    check(NavigationTargets.search("JWT", List.of()).get(0).id().equals("jwt"), "JWT alias opens its precise retained subpage");
    check(NavigationTargets.search("증거 저장", List.of()).get(0).id().equals("evidence"), "evidence goal ranks its precise route first");
    check(NavigationTargets.search("권한 비교", List.of()).get(0).id().equals("auth"), "Korean purpose locates comparison");
    check(NavigationTargets.search("BASE64", List.of()).get(0).id().equals("data"), "purpose search is case insensitive");
    check(NavigationTargets.search("unrelated_secret_9C9C", List.of()).isEmpty(), "purpose search only scans fixed feature metadata");
    check(NavigationTargets.search("JWT ".repeat(100_000), List.of()).isEmpty(), "oversized pasted search input is rejected before catalog scanning");
    check(NavigationTargets.search("", List.of("auth", "stale-id", "traffic")).get(0).id().equals("auth"), "recent routes precede full feature catalog");
    AtomicReference<String> project = new AtomicReference<>("project-a"), filter = new AtomicReference<>("GET + recent30m");
    AtomicReference<FolderNavigation> owner = new AtomicReference<>();
    AtomicReference<JTable> tableRef = new AtomicReference<>(); AtomicReference<JScrollPane> scrollRef = new AtomicReference<>();
    AtomicReference<JTabbedPane> toolsRef = new AtomicReference<>(); AtomicReference<DefaultTableModel> modelRef = new AtomicReference<>();
    AtomicReference<JTabbedPane> pagesRef = new AtomicReference<>();
    AtomicReference<String> restoredRoute = new AtomicReference<>("");
    List<String> opened = new ArrayList<>(); List<Component> dialogs = new ArrayList<>();
    edt(() -> {
      JTabbedPane pages = new FolderNavigation.Pages();
      DefaultTableModel model = new DefaultTableModel(new Object[] {"ID", "내용"}, 0);
      for (int i = 0; i < 100; i++) model.addRow(new Object[] {i, "retained-" + i});
      JTable table = new JTable(model); table.setName("navigation.test.traffic"); table.setRowHeight(22); table.setAutoCreateRowSorter(true);
      JScrollPane scroll = new JScrollPane(table); scroll.setPreferredSize(new Dimension(500, 200));
      JTabbedPane tools = new JTabbedPane(); tools.addTab("목록", scroll); tools.addTab("JWT", new JTextField("UNSAVED_EDITOR_TEXT"));
      pages.addTab(FeatureGuide.title("traffic"), page("traffic", tools));
      pages.addTab(FeatureGuide.title("auth"), page("auth", new JTextField("UNSAVED_AUTH_EDITOR")));
      pages.addTab(FeatureGuide.title("bounty"), page("bounty", new JPanel()));
      FolderNavigation navigation = new FolderNavigation(null, pages, () -> {});
      navigation.configureRoutes(route -> {
        opened.add(route);
        switch (route) {
          case "traffic" -> { pages.setSelectedIndex(0); tools.setSelectedIndex(0); }
          case "data", "jwt" -> { pages.setSelectedIndex(0); tools.setSelectedIndex(1); }
          case "auth" -> pages.setSelectedIndex(1);
          case "bounty", "evidence" -> pages.setSelectedIndex(2);
          default -> { }
        }
      }, () -> pages.getSelectedIndex() == 0 ? tools.getSelectedIndex() == 0 ? "traffic" : "data"
          : pages.getSelectedIndex() == 1 ? "auth" : "bounty", project::get);
      navigation.setViewStateHooks(filter::get, saved -> filter.set((String) saved));
      tools.addChangeListener(event -> navigation.navigationChanged());
      navigation.setQuickWindowOpener((component, title, body, modal, closed) -> { dialogs.add(body); return null; });
      navigation.setSize(1024, 720); for (int i = 0; i < 4; i++) UiLayoutTest.layout(navigation);
      table.setRowSelectionInterval(18, 18); scroll.getViewport().setViewPosition(new Point(0, 220));
      navigation.navigate("auth");
      check(navigation.canGoBack(), "opening a second view creates a history entry");
      filter.set("POST + 4xx"); table.setRowSelectionInterval(2, 2); scroll.getViewport().setViewPosition(new Point(0, 0));
      navigation.goBack();
      check(pages.getSelectedIndex() == 0 && tools.getSelectedIndex() == 0, "back opens exact retained page and subtab");
      check(table.convertRowIndexToModel(table.getSelectedRow()) == 18, "back restores selected original row while model is unchanged");
      check(scroll.getViewport().getViewPosition().y == 220, "back restores previous viewport");
      check(filter.get().equals("GET + recent30m"), "external in-memory traffic query hook restores prior filter");
      check(navigation.canGoForward(), "back exposes forward navigation");
      navigation.goForward();
      check(pages.getSelectedIndex() == 1 && filter.get().equals("POST + 4xx"), "forward restores next page and its query checkpoint");
      navigation.goBack(); navigation.navigate("evidence");
      check(!navigation.canGoForward(), "new route after back discards obsolete forward branch");
      navigation.goBack();
      check(pages.getSelectedIndex() == 0, "special evidence route retains its origin in history");
      navigation.navigate("auth");
      model.setValueAt("changed", 18, 1); table.setRowSelectionInterval(3, 3);
      navigation.goBack();
      check(table.getSelectedRow() == 3, "changed table model never restores a stale row index");
      navigation.navigate("auth");
      table.setModel(new DefaultTableModel(new Object[][] {{"replacement"}}, new String[] {"ID"}));
      table.setRowSelectionInterval(0, 0); navigation.goBack();
      check(table.getSelectedRow() == 0 && table.getModel() != model, "replacement model never receives obsolete selection");
      navigation.openQuickNavigation();
      check(dialogs.size() == 1 && dialogs.get(0) == navigation.quickPanel(), "Ctrl+K palette reuses local search panel");
      QuickNavigationPanel palette = navigation.quickPanel(); palette.query.setText("JWT");
      palette.openSelected();
      check(opened.get(opened.size() - 1).equals("jwt") && tools.getSelectedIndex() == 1,
          "palette Enter opens exact JWT alias without executing actions");
      check(palette.query.getText().isEmpty(), "closing palette clears transient search text");
      palette.query.setText("not-a-feature"); int before = opened.size(); palette.openSelected();
      check(opened.size() == before && palette.choices.getSelectedIndex() < 0, "no-match Enter has no navigation side effect");
      palette.getActionMap().get("dismiss").actionPerformed(new ActionEvent(palette, 0, "escape"));
      check(palette.query.getText().isEmpty(), "Escape closes and resets palette");
      JTabbedPane recoveredPages = new FolderNavigation.Pages();
      recoveredPages.addTab(FeatureGuide.title("traffic"), page("traffic", new JPanel()));
      FolderNavigation recovered = new FolderNavigation(null, recoveredPages, () -> {}, new JButton("기능 안내"), new JButton("로그"));
      // Host docking/theme replacement can briefly lay out a component before its usable width is restored.
      recovered.setSize(280, 600); WorkbenchUi.style(recovered);
      for (int i = 0; i < 5; i++) UiLayoutTest.layout(recovered);
      SwingUtilities.updateComponentTreeUI(recovered); WorkbenchUi.style(recovered); recovered.restoreLayout();
      recovered.setSize(1024, 720); for (int i = 0; i < 6; i++) UiLayoutTest.layout(recovered);
      Component recoveredBack = UiLayoutTest.named(recovered, "wb.navigation.back");
      Component recoveredQuick = UiLayoutTest.named(recovered, "wb.navigation.quick");
      check(SwingUtilities.convertPoint(recoveredBack, 0, 0, recovered).y
          == SwingUtilities.convertPoint(recoveredQuick, 0, 0, recovered).y,
          "restoring wide host after temporary narrow layout and theme recovery keeps navigation controls on one row");
      recovered.setSize(560, 600); for (int i = 0; i < 6; i++) UiLayoutTest.layout(recovered);
      Point quickPoint = SwingUtilities.convertPoint(recoveredQuick, 0, 0, recovered);
      check(quickPoint.x >= 0 && quickPoint.x + recoveredQuick.getWidth() <= 560,
          "recovered navigation still exposes feature search at560minimum width");
      recovered.close();
      navigation.setSize(560, 600); for (int i = 0; i < 5; i++) UiLayoutTest.layout(navigation);
      for (String name : List.of("wb.navigation.back", "wb.navigation.forward", "wb.navigation.quick", "wb.navigation.settings")) {
        Component button = UiLayoutTest.named(navigation, name);
        Point point = SwingUtilities.convertPoint(button, 0, 0, navigation);
        check(button.getWidth() > 0 && point.x >= 0 && point.x + button.getWidth() <= navigation.getWidth(),
            "compact560 navigation action remains visible: " + name);
      }
      palette.setSize(560, 300); for (int i = 0; i < 5; i++) UiLayoutTest.layout(palette);
      Component openButton = UiLayoutTest.named(palette, "wb.navigation.quick.open");
      Point openPoint = SwingUtilities.convertPoint(openButton, 0, 0, palette);
      check(palette.query.getWidth() > 300 && palette.choices.getHeight() > 70
          && openPoint.x + openButton.getWidth() <= 560 && openPoint.y + openButton.getHeight() <= 300,
          "quick search input, results and open action fit560x300 minimum window");
      check(navigation.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(KeyStroke.getKeyStroke("control K")) != null,
          "Ctrl+K is scoped to focused Workbench descendants");
      check(navigation.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("control K")) == null,
          "palette installs no window/global shortcut that captures other Burp tabs");
      project.set("project-b");
      check(!navigation.canGoBack() && !navigation.canGoForward() && navigation.recentRoutes().isEmpty(),
          "project switch clears navigation state and recent screens before restoration");
      navigation.navigate("auth"); navigation.navigate("traffic");
      check(navigation.canGoBack(), "new project builds its own fresh history");
      navigation.resetHistory(); check(!navigation.canGoBack(), "explicit reset clears prior work navigation");
      project.set(""); navigation.navigate("auth"); navigation.navigate("traffic");
      check(!navigation.canGoBack(), "unresolved project disables cross-view restoration");
      project.set("project-c"); navigation.resetHistory();
      for (int i = 0; i < 40; i++) navigation.navigate(i % 2 == 0 ? "auth" : "traffic");
      int backCount = 0; while (navigation.canGoBack()) { navigation.goBack(); backCount++; }
      check(backCount == 23 && navigation.recentRoutes().size() <= 8, "history is bounded to24entries and recents to8metadata routes");
      owner.set(navigation); tableRef.set(table); scrollRef.set(scroll); toolsRef.set(tools); modelRef.set(model); pagesRef.set(pages);
    });
    edt(() -> {});
    edt(() -> {
      FolderNavigation navigation = owner.get();
      navigation.navigate("traffic"); toolsRef.get().setSelectedIndex(1);
    });
    edt(() -> {});
    edt(() -> {
      FolderNavigation navigation = owner.get(); navigation.goBack();
      check(toolsRef.get().getSelectedIndex() == 0, "nested subtab events enter history after coalesced navigation completes");
      navigation.goForward(); check(toolsRef.get().getSelectedIndex() == 1, "forward restores nested tab selection");
      navigation.navigate("traffic");
      navigation.setViewStateHooks(route -> route, saved -> restoredRoute.set((String) saved));
      pagesRef.get().setSelectedIndex(1);
    });
    edt(() -> {});
    edt(() -> {
      FolderNavigation navigation = owner.get(); navigation.goBack();
      check(restoredRoute.get().equals("traffic"), "after-change callback captures bookmarked route rather than newly selected destination");
      project.set("project-d");
      navigation.quickPanel().query.setText("JWT");
      check(navigation.recentRoutes().isEmpty(), "project change during palette document notification clears history without recursive edits");
      navigation.close();
      check(!navigation.canGoBack() && navigation.recentRoutes().isEmpty(), "unload releases history and recent metadata");
      int before = opened.size(); navigation.navigate("auth"); navigation.openQuickNavigation();
      check(opened.size() == before, "closed navigation never reopens retained work");
    });
    System.out.println("Navigation0532Test: " + checks + " checks passed (retained Swing views; no network execution)");
  }
}
