package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.Preferences;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.Document;
import javax.swing.tree.*;
import static kr.workbench.HeaderToolsNative.*;

/** Physical compact navigation actions on an isolated Linux display and synthetic Burp adapters. */
public final class CompactPrimaryNavigationNative extends WorkbenchTest {
  private static final String DRAFT = "https://primary.example.test/draft?keep=1";
  private static final String AUTHORED = "UNSAVED_COMPACT_PRIMARY_INPUT";
  private static final List<String> ORDINARY = List.of("traffic", "mapper", "js", "environment",
      "exposure", "auth", "response.recon", "payload", "data", "activity", "bounty");
  private static final List<Map<String,Object>> geometry = new ArrayList<>();
  private static final Map<String,List<Integer>> captures = new LinkedHashMap<>();

  private static String route(FolderNavigation navigation) {
    try {
      Method method = FolderNavigation.class.getDeclaredMethod("currentRoute");
      method.setAccessible(true);
      return Objects.toString(method.invoke(navigation), "");
    } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
  }

  private static AbstractButton button(Container root, String name) {
    Component found = SimpleWorkflowTest.named(root, name);
    if (!(found instanceof AbstractButton result)) throw new AssertionError("Missing button: " + name);
    return result;
  }

  private static JDialog inspection(WorkbenchExtension extension) {
    Object owner = value(extension, "scopeInspectionWindow");
    return owner == null ? null : (JDialog)value(owner, "window");
  }

  private static void press(Robot robot, int key) throws Exception {
    robot.keyPress(key); robot.keyRelease(key);
    Toolkit.getDefaultToolkit().sync(); edt(() -> {});
  }

  private static void chord(Robot robot, int modifier, int key) throws Exception {
    robot.keyPress(modifier); robot.keyPress(key); robot.keyRelease(key); robot.keyRelease(modifier);
    Toolkit.getDefaultToolkit().sync(); edt(() -> {});
  }

  private static void ready(JFrame frame, JPanel root) throws Exception {
    edt(() -> { root.revalidate(); frame.validate(); frame.toFront(); frame.requestFocus(); });
    Rectangle[] previous = {null};
    long[] stableSince = {0};
    try {
      // Swing validates through JRootPane; the outer Frame flag can remain invalid.
      // Test the displayed bounds that actual Robot actions use, followed by row containment.
      await(() -> {
        if (!frame.isActive() || !root.isShowing() || root.getWidth() <= 0 || root.getHeight() <= 0) {
          stableSince[0] = 0; return false;
        }
        Rectangle actual = screenBounds(root);
        if (stableSince[0] == 0 || !actual.equals(previous[0])) {
          previous[0] = actual; stableSince[0] = System.nanoTime(); return false;
        }
        return System.nanoTime() - stableSince[0] >= java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(150);
      }, "active workspace with stable displayed bounds");
    } catch (AssertionError failure) {
      throw new AssertionError(failure.getMessage() + "; active=" + frame.isActive()
          + ", valid=" + frame.isValid() + ", showing=" + root.isShowing()
          + ", bounds=" + frame.getBounds(), failure);
    }
    settle();
  }

  private static void closeInspection(Robot robot, JFrame frame, WorkbenchExtension extension) throws Exception {
    JDialog dialog = inspection(extension);
    if (dialog == null || !dialog.isShowing()) throw new AssertionError("Missing visible preparation window");
    edt(() -> { dialog.toFront(); dialog.requestFocus(); });
    await(dialog::isActive, "active preparation window before Alt+F4");
    chord(robot, KeyEvent.VK_ALT, KeyEvent.VK_F4);
    await(() -> !dialog.isDisplayable() || !dialog.isShowing(), "preparation close through window manager");
    await(() -> inspection(extension) == null || !inspection(extension).isShowing(), "retained target controller observes close");
    ready(frame, (JPanel)value(extension, "root"));
  }

  private static void focusByTab(Robot robot, JFrame frame, AbstractButton target) throws Exception {
    edt(frame::toFront);
    await(frame::isActive, "main workspace active for keyboard navigation");
    long stableSince = 0;
    for (int count = 0; count < 120; count++) {
      if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == target) {
        if (stableSince == 0) stableSince = System.nanoTime();
        if (System.nanoTime() - stableSince >= java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(150)) return;
        Thread.sleep(20);
      } else {
        stableSince = 0;
        press(robot, KeyEvent.VK_TAB); Thread.sleep(20);
      }
    }
    Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
    throw new AssertionError("Tab cannot reach " + target.getName() + "; actual focus="
        + (focused == null ? "none" : focused.getClass().getName() + "/" + focused.getName()));
  }

  private static Rectangle screenBounds(Component component) {
    return new Rectangle(component.getLocationOnScreen(), component.getSize());
  }

  private static void primaryBounds(JPanel root, JScrollPane sidebar, AbstractButton inspect,
      AbstractButton dashboard, String phase) throws Exception {
    edt(() -> {
      JViewport header = sidebar.getColumnHeader();
      check(header != null && header.isShowing(), phase + ": pinned column header is visible");
      check("wb.navigation.primary".equals(header.getView().getName()),
          phase + ": both controls use the real pinned primary panel");
      Rectangle rootBounds = screenBounds(root), headerBounds = screenBounds(header);
      Rectangle first = screenBounds(inspect), second = screenBounds(dashboard);
      check(inspect.isShowing() && dashboard.isShowing() && inspect.getHeight() == 42
          && dashboard.getHeight() == 42, phase + ": two compact rows remain exactly 42 pixels high");
      check(second.y - first.y - first.height == 6, phase + ": the compact rows retain their six-pixel gap");
      check(rootBounds.contains(first) && rootBounds.contains(second)
          && headerBounds.contains(first) && headerBounds.contains(second),
          phase + ": complete rows stay inside both the sidebar header and real workspace");
      for (AbstractButton item : List.of(inspect, dashboard)) {
        Rectangle visible = item.getVisibleRect();
        check(visible.contains(new Rectangle(0, 0, item.getWidth(), item.getHeight())),
            phase + ": primary control has no clipped edge: " + item.getName());
        check(item.getIcon() != null && item.getAccessibleContext().getAccessibleName() != null
            && !item.getAccessibleContext().getAccessibleName().isBlank(),
            phase + ": primary control keeps icon and accessible caption: " + item.getName());
      }
      geometry.add(Map.of("phase", phase, "inspect", List.of(first.x, first.y, first.width, first.height),
          "dashboard", List.of(second.x, second.y, second.width, second.height),
          "header", List.of(headerBounds.x, headerBounds.y, headerBounds.width, headerBounds.height)));
    });
  }

  private static void picture(Robot robot, Rectangle bounds, Path output, String name) throws Exception {
    Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
        .getDefaultConfiguration().getBounds();
    check(screen.contains(bounds), "Robot capture stays inside isolated display: " + name);
    robot.mouseMove(screen.x + screen.width - 12, screen.y + screen.height - 12);
    settle();
    ImageIO.write(robot.createScreenCapture(bounds), "png", output.resolve(name).toFile());
    captures.put(name, List.of(bounds.width, bounds.height));
  }

  private static void snapshotPicture(Robot robot, JPanel root, Path output, String name) throws Exception {
    picture(robot, screenBounds(root), output, name);
  }

  private static void scrollTo(Robot robot, JTree tree, JScrollPane sidebar, TreePath path) throws Exception {
    for (int attempt = 0; attempt < 35; attempt++) {
      Rectangle bounds = tree.getPathBounds(path);
      Rectangle viewport = sidebar.getViewport().getViewRect();
      if (bounds != null && viewport.y <= bounds.y
          && viewport.y + viewport.height >= bounds.y + bounds.height) return;
      Point point = sidebar.getViewport().getLocationOnScreen();
      robot.mouseMove(point.x + sidebar.getViewport().getWidth() / 2,
          point.y + sidebar.getViewport().getHeight() / 2);
      robot.mouseWheel(bounds != null && bounds.y < viewport.y ? -4 : 4);
      settle();
    }
    throw new AssertionError("Physical wheel cannot expose tree path: " + path);
  }

  private static void treeClick(Robot robot, JTree tree, JScrollPane sidebar, TreePath path) throws Exception {
    scrollTo(robot, tree, sidebar, path);
    Rectangle bounds = tree.getPathBounds(path);
    Point location = tree.getLocationOnScreen();
    robot.mouseMove(location.x + bounds.x + Math.min(65, Math.max(1, bounds.width - 2)),
        location.y + bounds.y + bounds.height / 2);
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    settle();
  }

  private static Map<String,Object> historyState(FolderNavigation navigation, JTree tree,
      AbstractButton dashboard) {
    Map<String,Object> result = new LinkedHashMap<>();
    Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
    result.put("route",route(navigation)); result.put("dashboardSelected",dashboard.isSelected());
    result.put("visitIndex",value(navigation,"visitIndex"));
    result.put("visits",((List<?>)value(navigation,"visits")).stream()
        .map(visit -> Objects.toString(value(visit,"route"),"")).toList());
    result.put("focus",focus == null ? "none" : focus.getClass().getName()+"/"+focus.getName());
    KeyStroke key = KeyStroke.getKeyStroke("alt LEFT");
    result.put("treeFocusedAltLeft",Objects.toString(tree.getInputMap(JComponent.WHEN_FOCUSED).get(key),""));
    result.put("navigationAncestorAltLeft",Objects.toString(navigation.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(key),""));
    return result;
  }

  public static void main(String[] args) throws Exception {
    try { run(args); }
    catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
  }

  private static void run(String[] args) throws Exception {
    if (GraphicsEnvironment.isHeadless()) throw new AssertionError("Use isolated native Xorg");
    Path output = Path.of(args[0]); Files.createDirectories(output);
    String theme = args[1]; int width = Integer.parseInt(args[2]), height = Integer.parseInt(args[3]);
    if (!BuildInfo.VERSION.equals("1.4.21")) throw new AssertionError("Build current Simple 1.4.21 first");
    if (theme.equals("dark")) darkTheme();
    Path jar = Path.of(WorkbenchExtension.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    String jarSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
    int beforeHttp = PayloadIntegrationTest.sent.get();
    ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p,m,a) -> def(m));
    MontoyaApi fallbackApi = SimpleWorkflowTest.previewApi();
    var fallbackPersistence = fallbackApi.persistence(); var fallbackPreferences = fallbackPersistence.preferences();
    Map<String,String> saved = new ConcurrentHashMap<>();
    Preferences preferences = proxy(Preferences.class, (p,m,a) -> switch (m.getName()) {
      case "setString" -> { if (a[1] == null) saved.remove(a[0].toString()); else saved.put(a[0].toString(),a[1].toString()); yield null; }
      case "getString" -> saved.get(a[0].toString());
      default -> m.invoke(fallbackPreferences,a);
    });
    Persistence persistence = proxy(Persistence.class, (p,m,a) ->
        m.getName().equals("preferences") ? preferences : m.invoke(fallbackPersistence,a));
    MontoyaApi api = proxy(MontoyaApi.class, (p,m,a) ->
        m.getName().equals("persistence") ? persistence : m.invoke(fallbackApi,a));
    BurpExtender entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    WorkbenchWindow desktop = null;
    try {
      edt(() -> entry.initialize(api)); edt(() -> {});
      WorkbenchExtension extension = (WorkbenchExtension)get(entry,"workbench");
      JPanel root = (JPanel)get(extension,"root");
      FolderNavigation navigation = (FolderNavigation)get(extension,"navigation");
      desktop = (WorkbenchWindow)get(extension,"workbenchWindow"); final WorkbenchWindow owner = desktop;
      edt(owner::open); await(() -> owner.window != null && owner.window.isShowing(), "retained desktop open");
      JFrame frame = owner.window;
      await(() -> frame.getWidth() > 1800, "initial native maximize has settled");
      edt(() -> frame.setExtendedState(Frame.NORMAL)); settle();
      edt(() -> { frame.setBounds(30,40,width,height); extension.openFeature("traffic"); });
      await(() -> frame.getWidth() == width && frame.getHeight() == height, "requested normal native window size");
      ready(frame,root);
      AbstractButton inspect = button(root,"wb.navigation.primary.inspect");
      AbstractButton dashboard = button(root,"wb.navigation.primary.dashboard");
      JScrollPane sidebar = (JScrollPane)SimpleWorkflowTest.named(root,"wb.navigation.sidebar");
      JTree tree = (JTree)SimpleWorkflowTest.named(root,"wb.navigation.tree");
      JTabbedPane pages = (JTabbedPane)get(extension,"tabs");
      JTextArea authored = (JTextArea)get(extension,"codecIn");
      Document authoredDocument = authored.getDocument();
      for (int i=0;i<2;i++) seedTraffic(extension,request("https://primary.example.test/api/"+i,"GET",true,
          List.of(header("Accept","application/json"))),response(200,"{\"synthetic\":"+i+"}"));
      JTable traffic = (JTable)get(extension,"logTable");
      await(() -> traffic.getRowCount() == 2, "retained synthetic traffic is published");
      edt(() -> { traffic.setRowSelectionInterval(1,1); authored.setText(AUTHORED); });
      @SuppressWarnings("unchecked") Map<String,DefaultMutableTreeNode> ordinary =
          (Map<String,DefaultMutableTreeNode>)get(navigation,"tools");
      DefaultMutableTreeNode treeRoot = (DefaultMutableTreeNode)tree.getModel().getRoot();
      check(treeRoot.getChildCount() == 5 && treeRoot.getLeafCount() == 11,
          "five ordinary groups and eleven leaves remain");
      check(ordinary.keySet().containsAll(ORDINARY) && !ordinary.containsKey("dashboard"),
          "only dashboard is removed from the lower tree; every ordinary route remains");
      check(inspect instanceof JButton && dashboard instanceof JToggleButton
          && inspect.getText().equals("통합 점검") && dashboard.getText().equals("대시보드"),
          "two top rows retain their distinct action and current-page semantics");
      Robot robot = new Robot(); robot.setAutoDelay(35);
      primaryBounds(root,sidebar,inspect,dashboard,"initial");
      snapshotPicture(robot,root,output,"01-navigation.png");

      click(robot,inspect);
      await(() -> inspection(extension) != null && inspection(extension).isShowing(), "physical inspection row opens preparation");
      ScopeInspectionPanel panel = extension.scopeInspectionPanel; Object targetOwner = value(extension,"scopeInspectionWindow");
      JDialog initialDialog = inspection(extension);
      check(panel != null && panel.runner == null && panel.options == null,
          "opening the real preparation panel does not start inspection");
      edt(() -> { panel.url.setText(DRAFT); panel.budget.setValue(140); initialDialog.setLocation(610,80); });
      Document targetDocument = panel.url.getDocument();
      settle();
      picture(robot,frame.getBounds().union(initialDialog.getBounds()),output,"02-inspection.png");
      click(robot,inspect);
      await(initialDialog::isActive,"repeated inspection row brings existing dialog forward");
      check(inspection(extension) == initialDialog && extension.scopeInspectionPanel == panel
          && value(extension,"scopeInspectionWindow") == targetOwner,
          "a repeated physical click reuses the visible preparation window and controller");
      check(pages.getSelectedComponent() != extension.scopeDashboard && !dashboard.isSelected(),
          "inspection is an action and does not masquerade as dashboard selection");
      closeInspection(robot,frame,extension);

      ScopeDashboardPanel retainedDashboard = extension.scopeDashboard;
      ScopeInspection.Snapshot retainedSnapshot = new ScopeInspection.Snapshot(
          "compact-native-retained-result",ScopeInspection.State.COMPLETED,"합성 기존 결과 · 실제 전송 없음",
          0,0,0,0,0,0,List.of(),List.of(),
          List.of(new ScopeInspection.Candidate("https://primary.example.test/observed","GET",
              "합성 관측","기존 결과 보존용",false)),1,2);
      edt(() -> retainedDashboard.update(retainedSnapshot,140,"https://primary.example.test/"));
      await(() -> retainedDashboard.current == retainedSnapshot,"existing synthetic dashboard snapshot is installed");
      click(robot,dashboard);
      await(() -> pages.getSelectedComponent() == retainedDashboard && dashboard.isSelected(),
          "physical dashboard row selects the actual retained dashboard page");
      check(extension.scopeDashboard == retainedDashboard && retainedDashboard.current == retainedSnapshot,
          "dashboard navigation retains its original page and snapshot objects");
      JLabel breadcrumb = (JLabel)SimpleWorkflowTest.named(root,"wb.navigation.breadcrumb");
      JLabel current = (JLabel)SimpleWorkflowTest.named(root,"wb.navigation.current");
      check(breadcrumb.getText().contains("통합 점검") && current.getText().contains("대시보드"),
          "dashboard breadcrumb identifies integrated inspection and current dashboard");
      check(tree.isSelectionEmpty(),"dashboard removes stale lower-tree highlight");
      primaryBounds(root,sidebar,inspect,dashboard,"dashboard");
      snapshotPicture(robot,root,output,"03-dashboard.png");

      treeClick(robot,tree,sidebar,new TreePath(ordinary.get("traffic").getPath()));
      await(() -> route(navigation).equals("traffic") && !dashboard.isSelected(),"ordinary traffic page deselects dashboard row");
      // JFrame.requestFocus() in native fixture setup can leave focus on the AWT frame.
      // Enter the actual Swing focus cycle using Tab before testing ancestor key bindings.
      focusByTab(robot,frame,dashboard);
      Files.writeString(output.resolve("history-before.json"),MetadataExport.json(historyState(navigation,tree,dashboard))+"\n");
      chord(robot,KeyEvent.VK_ALT,KeyEvent.VK_LEFT);
      try {
        await(() -> pages.getSelectedComponent() == retainedDashboard && dashboard.isSelected(),"Alt+Left restores dashboard and primary selection");
      } catch (AssertionError failure) {
        Files.writeString(output.resolve("history-failure.json"),MetadataExport.json(historyState(navigation,tree,dashboard))+"\n");
        throw failure;
      }
      focusByTab(robot,frame,dashboard);
      chord(robot,KeyEvent.VK_ALT,KeyEvent.VK_RIGHT);
      await(() -> route(navigation).equals("traffic") && !dashboard.isSelected(),"Alt+Right restores ordinary route and primary state");
      await(() -> traffic.getSelectedRow() == 1,"history completes its delayed selected-row restore");
      check(traffic.getSelectedRow() == 1,"history retains exact selected traffic row");

      focusByTab(robot,frame,inspect); press(robot,KeyEvent.VK_SPACE);
      await(() -> inspection(extension) != null && inspection(extension).isShowing(),"Space on keyboard-focused inspection row opens preparation");
      check(extension.scopeInspectionPanel == panel && panel.url.getDocument() == targetDocument
          && panel.url.getText().equals(DRAFT) && ((Number)panel.budget.getValue()).intValue() == 140,
          "closing and keyboard reopening preserves the actual preparation panel, document and draft");
      check(panel.runner == null && panel.options == null,"keyboard opening also leaves inspection unstarted");
      closeInspection(robot,frame,extension);
      focusByTab(robot,frame,dashboard); press(robot,KeyEvent.VK_ENTER);
      await(() -> pages.getSelectedComponent() == retainedDashboard && dashboard.isSelected(),"Enter on keyboard-focused dashboard row selects the real page");
      check(retainedDashboard.current == retainedSnapshot,"keyboard dashboard navigation preserves existing snapshot");

      AbstractButton tools = button(root,"wb.navigation.tools.open");
      click(robot,tools); await(() -> tools.getComponentPopupMenu().isShowing(),"original header tools menu opens");
      click(robot,item(tools.getComponentPopupMenu(),"wb.scope.inspection.open.menu"));
      await(() -> inspection(extension) != null && inspection(extension).isShowing(),"retained header menu opens preparation");
      check(extension.scopeInspectionPanel == panel && value(extension,"scopeInspectionWindow") == targetOwner,
          "new primary action and original header menu delegate to the same retained preparation controller");
      closeInspection(robot,frame,extension);

      Point pinned = inspect.getLocationOnScreen();
      DefaultMutableTreeNode last = treeRoot.getLastLeaf();
      scrollTo(robot,tree,sidebar,new TreePath(last.getPath()));
      check(inspect.getLocationOnScreen().equals(pinned),
          "scrolling to the final ordinary leaf leaves the two primary rows pinned");
      primaryBounds(root,sidebar,inspect,dashboard,"scrolled-bottom");
      snapshotPicture(robot,root,output,"04-sidebar-bottom.png");
      for (String id : ORDINARY) {
        treeClick(robot,tree,sidebar,new TreePath(ordinary.get(id).getPath()));
        // The retained evidence subtab is deliberately represented by its own history alias.
        await(() -> route(navigation).equals(id)
            || id.equals("bounty") && route(navigation).equals("evidence"),
            "physical lower-tree route remains reachable: "+id);
        check(tree.getLastSelectedPathComponent() == ordinary.get(id),
            "actual ordinary tree node remains selected: "+id);
        check(!dashboard.isSelected(),"ordinary route clears primary dashboard selection: "+id);
      }
      Point scrollPosition = sidebar.getViewport().getViewPosition();
      int menuWidth = sidebar.getWidth();
      AbstractButton toggle = button(root,"wb.navigation.toggle");
      click(robot,toggle);
      await(() -> !sidebar.isVisible() && !inspect.isShowing(),"physical menu toggle hides complete sidebar");
      click(robot,toggle);
      await(() -> sidebar.isVisible() && inspect.isShowing(),"physical menu toggle restores primary and ordinary rows");
      check(sidebar.getWidth() == menuWidth && sidebar.getViewport().getViewPosition().equals(scrollPosition),
          "sidebar hide and restore retain width and ordinary scroll position");
      primaryBounds(root,sidebar,inspect,dashboard,"sidebar-restored");
      boolean beforeDark = WorkbenchUi.dark();
      AbstractButton moon = button(root,"wb.traffic.header.tools");
      click(robot,moon); await(() -> WorkbenchUi.dark() != beforeDark,"physical theme action switches local palette");
      primaryBounds(root,sidebar,inspect,dashboard,"theme-toggled");
      check(authored.getDocument() == authoredDocument && authored.getText().equals(AUTHORED)
          && panel.url.getDocument() == targetDocument && panel.url.getText().equals(DRAFT),
          "navigation, sidebar and theme preserve authored documents and pending preparation input");
      check(retainedDashboard.current == retainedSnapshot,"theme and sidebar operations retain dashboard snapshot");
      snapshotPicture(robot,root,output,"05-theme-toggled.png");
      click(robot,moon); await(() -> WorkbenchUi.dark() == beforeDark,"second physical theme action restores original palette");
      click(robot,dashboard); await(dashboard::isSelected,"final dashboard route selected");
      primaryBounds(root,sidebar,inspect,dashboard,"final");
      snapshotPicture(robot,root,output,"06-restored-navigation.png");
      check(PayloadIntegrationTest.sent.get() == beforeHttp && panel.runner == null && panel.options == null,
          "all primary actions, routes, keyboard and theme operations send zero target HTTP and start no inspection");
      Map<String,Object> result = new LinkedHashMap<>();
      result.put("result","passed"); result.put("version",BuildInfo.VERSION); result.put("theme",theme);
      result.put("windowWidth",width); result.put("windowHeight",height); result.put("checks",checks);
      result.put("jarSha256",jarSha); result.put("targetHttpRequests",PayloadIntegrationTest.sent.get()-beforeHttp);
      result.put("inspectionStarted",panel.runner!=null); result.put("primaryGeometry",geometry);
      result.put("captures",captures); result.put("nativeSwing",true); result.put("nativeBurpGui",false);
      result.put("syntheticPackets",true);
      Files.writeString(output.resolve("verification.json"),MetadataExport.json(result)+"\n");
      System.out.println("CompactPrimaryNavigationNative: "+checks+" checks passed / "+BuildInfo.VERSION
          +" / "+theme+" / "+width+"x"+height);
    } catch (Throwable failure) {
      try {
        Robot robot = new Robot();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
            .getDefaultConfiguration().getBounds();
        ImageIO.write(robot.createScreenCapture(screen),"png",output.resolve("failure.png").toFile());
        Files.writeString(output.resolve("failure.txt"),failure.toString()+"\n");
      } catch (Exception captureFailure) { failure.addSuppressed(captureFailure); }
      throw failure;
    } finally {
      final WorkbenchWindow cleanup = desktop;
      edt(() -> {
        for(var handler:List.copyOf(PayloadIntegrationTest.modernUnloads))try{handler.extensionUnloaded();}catch(Exception ignored){}
        for(var handler:List.copyOf(PayloadIntegrationTest.legacyUnloads))try{handler.extensionUnloaded();}catch(Exception ignored){}
        if(cleanup!=null)cleanup.close();
        for(Window window:Window.getWindows())window.dispose();
      });
    }
  }
}
