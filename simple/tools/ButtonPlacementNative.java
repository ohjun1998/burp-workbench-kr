package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Actual pointer/menu/window checks; synthetic Burp adapters and zero target HTTP. */
public final class ButtonPlacementNative extends WorkbenchTest {
  private static Object value(Object owner, String name) {
    try { return get(owner, name); }
    catch (Exception error) { throw new IllegalStateException(name, error); }
  }

  private static Component named(Component root, String name) {
    Component component = SimpleWorkflowTest.named(root, name);
    if (component == null) throw new AssertionError("Missing production control: " + name);
    return component;
  }

  private static void await(BooleanSupplier condition, String reason) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      boolean[] ready = {false};
      edt(() -> ready[0] = condition.getAsBoolean());
      if (ready[0]) return;
      Thread.sleep(25);
    }
    throw new AssertionError("Native button placement timeout: " + reason);
  }

  /** Flush queued EDT work with a deadline; X11 global idle may never occur during periodic repaint. */
  static void settle() {
    if(EventQueue.isDispatchThread())throw new IllegalStateException("Native settle must run outside EDT");
    java.util.concurrent.FutureTask<Void> barrier=new java.util.concurrent.FutureTask<>(()->null);
    EventQueue.invokeLater(barrier);
    try {
      barrier.get(3,java.util.concurrent.TimeUnit.SECONDS);
      Toolkit.getDefaultToolkit().sync();
      Thread.sleep(80);
    }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Native settle interrupted",interrupted);}
    catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException failure){throw new IllegalStateException("Native EDT settle deadline exceeded",failure);}
  }

  private static Rectangle onScreen(Component component) {
    return new Rectangle(component.getLocationOnScreen(), component.getSize());
  }

  private static void activate(Robot robot, JFrame frame, JPanel root) throws Exception {
    Point[] point = {null};
    edt(() -> { frame.toFront(); point[0] = root.getLocationOnScreen(); point[0].translate(300, 12); });
    robot.mouseMove(point[0].x, point[0].y);
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    settle(); await(frame::isActive, "activate native Workbench frame");
  }

  private static void click(Robot robot, Component component) throws Exception {
    Point[] point = {null};
    edt(() -> {
      if (!component.isShowing()) throw new AssertionError("Pointer target is not showing: " + component.getName());
      point[0] = component.getLocationOnScreen();
      point[0].translate(component.getWidth() / 2, component.getHeight() / 2);
    });
    robot.mouseMove(point[0].x, point[0].y);
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    settle();
  }

  /** Activate the real visible grouped entry, then point at its real menu item. */
  private static void clickAction(Robot robot, JPanel root, String name) throws Exception {
    Component source=named(root,name);
    if(source.isShowing()){click(robot,source);return;}
    ApprovedWorkflowTestSupport.MenuAccess[] holder={null};
    edt(()->holder[0]=ApprovedWorkflowTestSupport.menuAccess(source,root));
    var access=holder[0];
    if(access==null)throw new AssertionError("No visible action or menu for "+name);
    click(robot,access.open());
    await(()->access.open().getComponentPopupMenu().isShowing(),"grouped action menu opens for "+name);
    java.util.LinkedList<JMenu> path=new java.util.LinkedList<>();
    Component current=access.item();
    while(current.getParent() instanceof JPopupMenu popup && popup.getInvoker() instanceof JMenu menu){
      path.addFirst(menu);current=menu;
    }
    for(JMenu submenu:path){click(robot,submenu);await(()->submenu.getPopupMenu().isShowing(),"nested menu opens for "+name);}
    await(()->access.item().isShowing(),"real grouped menu item is visible for "+name);
    click(robot,access.item());
  }

  private static void capture(Robot robot, JFrame frame, Path output, String name) throws Exception {
    settle(); Thread.sleep(150);
    Rectangle[] bounds = {null};
    edt(() -> bounds[0] = frame.getBounds().intersection(frame.getGraphicsConfiguration().getBounds()));
    ImageIO.write(robot.createScreenCapture(bounds[0]), "png", output.resolve(name).toFile());
  }

  private static void darkTheme() {
    for (String key : List.of("Panel.background", "TabbedPane.background", "Button.background",
        "CheckBox.background", "ComboBox.background", "control")) UIManager.put(key, new Color(30, 33, 39));
    UIManager.put("TabbedPane.selected", new Color(69, 58, 48));
    for (String key : List.of("TabbedPane.selectHighlight", "TabbedPane.highlight", "TabbedPane.shadow",
        "TabbedPane.darkShadow", "controlHighlight", "controlLtHighlight", "controlShadow", "controlDkShadow"))
      UIManager.put(key, new Color(68, 75, 86));
    UIManager.put("TabbedPane.contentAreaColor", new Color(30, 33, 39));
    for (String key : List.of("Label.foreground", "Button.foreground", "CheckBox.foreground",
        "ComboBox.foreground", "TabbedPane.foreground", "textText")) UIManager.put(key, new Color(228, 233, 241));
  }

  private static void guidePosition(JPanel root) {
    Component guide = named(root, "wb.visual.guide.open");
    Component toolbar = named(root, "wb.navigation.toolbar");
    Rectangle relative = SwingUtilities.convertRectangle(guide.getParent(), guide.getBounds(), toolbar);
    check(guide.isShowing() && toolbar.getHeight() <= 36 && relative.y >= 0
        && relative.getMaxY() <= toolbar.getHeight() && relative.getMaxX() <= toolbar.getWidth()
        && toolbar.getWidth() - relative.getMaxX() <= 20,
        "Guide remains visible at the far right of the compact native header");
  }

  public static void main(String[] args) throws Exception {
    if (GraphicsEnvironment.isHeadless()) throw new AssertionError("An isolated display and WM are required");
    String theme = args.length > 1 ? args[1] : "light";
    if (!Set.of("light", "dark").contains(theme)) throw new IllegalArgumentException("theme must be light or dark");
    if (theme.equals("dark")) darkTheme();
    Path output = Path.of(args.length == 0 ? "tmp/button-native" : args[0]);
    Files.createDirectories(output); Files.deleteIfExists(output.resolve("native-button-summary.json"));
    Path loadedJar = Path.of(WorkbenchExtension.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    String jarSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(loadedJar)));
    int sentBefore = PayloadIntegrationTest.sent.get();
    ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p, m, a) -> def(m));
    var entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks) PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    WorkbenchWindow desktop = null;
    JFrame[] host = {null};
    List<Integer> sizes = new ArrayList<>();
    List<String> captures = List.of("01-traffic-settings.png", "02-payload-prepare.png", "03-payload-results.png");
    try {
      edt(() -> entry.initialize(SimpleWorkflowTest.previewApi())); edt(() -> {});
      var extension = (WorkbenchExtension) get(entry, "workbench");
      var root = (JPanel) get(extension, "root");
      desktop = (WorkbenchWindow) get(extension, "workbenchWindow");
      final WorkbenchWindow controller = desktop;
      edt(() -> {
        host[0] = new JFrame("Synthetic Burp host · button placement verification");
        host[0].setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        host[0].setBounds(20, 20, 960, 700);
        JTabbedPane tabs = new JTabbedPane(); tabs.addTab("Workbench", controller.launcher);
        host[0].add(tabs); host[0].setVisible(true);
      });
      await(() -> controller.window != null && controller.window.isShowing(), "launcher opens retained window");
      JFrame frame = controller.window;
      await(() -> root.getHeight() >= 900, "default maximized workspace layout");
      for (int i = 0; i < 3; i++)
        seedTraffic(extension, request("https://buttons.example.test/api/documents/" + (100 + i), "GET", true,
            List.of(header("Accept", "application/json"))), response(200, "{\"id\":" + (100 + i) + "}"));
      JTable traffic = (JTable) get(extension, "logTable");
      Object[] original = {null};
      edt(() -> {
        extension.openFeature("traffic"); traffic.setRowSelectionInterval(1, 1);
        original[0] = value(extension, "current");
      });
      Robot robot = new Robot(); robot.setAutoDelay(65); activate(robot, frame, root);
      edt(() -> guidePosition(root));
      JButton settings = (JButton) named(root, "wb.traffic.settings");
      JPopupMenu menu = settings.getComponentPopupMenu();
      check(menu != null, "traffic settings retains its real two-category popup");
      click(robot, settings);
      await(menu::isShowing, "native pointer opens traffic settings popup");
      edt(() -> {
        Component capture = named(menu, "wb.traffic.scope.list");
        Component search = named(menu, "wb.traffic.search.settings");
        check(capture.isShowing() && search.isShowing()
                &&named(menu,"wb.traffic.view.freeze.menu").isShowing()
                &&named(menu,"wb.traffic.view.apply.menu").isShowing(),
            "capture, search/list and view operations are visible in the real grouped popup");
        check(onScreen(menu).contains(onScreen(capture)) && onScreen(menu).contains(onScreen(search)),
            "both menu entries have complete native popup bounds");
      });
      capture(robot, frame, output, captures.get(0));
      robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE); settle();
      await(() -> !menu.isVisible(), "native Escape dismisses traffic settings popup");
      check(frame.isShowing(), "closing a popup does not hide the Workbench window");
      check(value(extension, "current") == original[0] && traffic.getSelectedRow() == 1,
          "opening and closing settings preserves exact selected traffic original");

      JTextArea codecIn = (JTextArea) get(extension, "codecIn"), codecOut = (JTextArea) get(extension, "codecOut");
      String authored = "네이티브 버튼 위치\nCONTEXT_INPUT_17";
      edt(() -> {
        extension.openFeature("data"); ((JTabbedPane) value(extension, "dataTabs")).setSelectedIndex(0);
        codecIn.setText(authored);
        ((JComboBox<?>) named(root, "wb.data.codec.operation")).setSelectedItem("Base64 인코딩");
      }); settle();
      click(robot, named(root, "wb.data.codec.convert"));
      String encoded = Base64.getEncoder().encodeToString(authored.getBytes(StandardCharsets.UTF_8));
      await(() -> codecOut.getText().equals(encoded), "native pointer activates input-local conversion");
      check(codecIn.getText().equals(authored), "native conversion preserves the exact authored UTF-8 input");
      clickAction(robot, root, "wb.data.codec.reuse");
      await(() -> codecIn.getText().equals(encoded), "native output-local reuse copies the displayed output");
      check(codecOut.getText().equals(encoded), "output-local reuse keeps the original conversion result");

      Object runner = get(entry, "payloadRunner");
      JTextArea request = (JTextArea) get(runner, "request");
      JTabbedPane workflow = (JTabbedPane) named(root, "wb.payload.steps");
      String requestText = "GET /search?q=§PAYLOAD§ HTTP/1.1\r\nHost: buttons.example.test\r\nX-Memo: UNSAVED_NATIVE\r\n\r\n";
      edt(() -> { extension.openFeature("payload"); workflow.setSelectedIndex(0); request.setText(requestText); });
      settle();
      Component evidence = named(root, "wb.simple.payload.evidence"), repeater = named(root, "wb.payload.repeater");
      check(!ApprovedWorkflowTestSupport.available(evidence,root) && !ApprovedWorkflowTestSupport.available(repeater,root),
          "native preparation screen does not expose result-only actions directly or through menus");
      check(named(root, "wb.payload.mark").isShowing()
              && ApprovedWorkflowTestSupport.available(named(root,"wb.payload.preview"),root),
          "native request editor exposes marking and its grouped preview operation");
      clickAction(robot,root,"wb.payload.preview");
      clickAction(robot,root,"wb.payload.preview");
      check(request.getText().equals(requestText),"real native grouped preview preserves authored request bytes");
      edt(() -> frame.setExtendedState(Frame.NORMAL));
      await(() -> (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == 0, "restore normal window for resize matrix");
      for (int width : List.of(1024, 1280, 1600)) {
        int height = width == 1024 ? 720 : width == 1280 ? 800 : 1000;
        edt(() -> frame.setBounds(0, 0, width, height));
        await(() -> frame.getWidth() == width && frame.getHeight() == height && root.isShowing(),
            "native resize to " + width);
        settle(); sizes.add(width);
        edt(() -> {
          Component payload = named(root, "wb.payload.root");
          Component start = named(root, "wb.simple.payload.start"), stop = named(root, "wb.simple.payload.stop");
          Rectangle viewport = onScreen(payload).intersection(frame.getGraphicsConfiguration().getBounds());
          check(start.isShowing() && stop.isShowing() && viewport.contains(onScreen(start)) && viewport.contains(onScreen(stop)),
              "execution and stop remain fully visible inside the native payload viewport at " + width + "px");
          Rectangle startBounds = onScreen(start), stopBounds = onScreen(stop);
          check(Math.abs(startBounds.y - stopBounds.y) <= 2 && startBounds.getMaxX() <= stopBounds.x,
              "execution and stop remain adjacent on the same native action row at " + width + "px");
          check(request.getText().equals(requestText) && workflow.getSelectedIndex() == 0,
              "native resize preserves the exact authored request and preparation phase at " + width + "px");
          guidePosition(root);
        });
      }
      capture(robot, frame, output, captures.get(1));
      click(robot, named(root, "wb.payload.step.results"));
      await(() -> workflow.getSelectedIndex() == 1
              && ApprovedWorkflowTestSupport.available(evidence,root)
              && ApprovedWorkflowTestSupport.available(repeater,root),
          "native results-step click reveals result-local primary and grouped controls");
      edt(() -> {
        Component actions = named(root, "wb.actions.payload.results");
        check(SwingUtilities.isDescendingFrom(evidence, (Container) actions)
            && SwingUtilities.isDescendingFrom(repeater, (Container) actions),
            "native primary and grouped result controllers belong to the results area");
        check(!((AbstractButton) evidence).isEnabled() && !((AbstractButton) repeater).isEnabled(),
            "empty native results keep evidence and Repeater disabled");
      });
      capture(robot, frame, output, captures.get(2));
      click(robot, named(root, "wb.payload.step.prepare"));
      await(() -> workflow.getSelectedIndex() == 0
              && !ApprovedWorkflowTestSupport.available(evidence,root)
              && !ApprovedWorkflowTestSupport.available(repeater,root),
          "native preparation-step click removes result-only entries from the visible workflow");
      check(request.getText().equals(requestText) && !Boolean.TRUE.equals(value(runner, "running")),
          "native stage navigation preserves request text and never starts an inspection");
      check(value(extension, "current") == original[0] && codecIn.getText().equals(encoded),
          "native tool navigation and resize retain selected original and converted input");
      edt(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
      await(() -> !frame.isVisible() && frame.isDisplayable(), "native close hides rather than disposes workspace");
      edt(controller::open);
      await(frame::isShowing, "reopen retained native workspace");
      check(controller.window == frame && request.getText().equals(requestText) && workflow.getSelectedIndex() == 0,
          "close and reopen retain the same frame and authored request phase");
      check(PayloadIntegrationTest.sent.get() == sentBefore, "all native button placement checks send zero target HTTP");
      edt(() -> {
        for (var handler : List.copyOf(PayloadIntegrationTest.modernUnloads)) handler.extensionUnloaded();
        for (var handler : List.copyOf(PayloadIntegrationTest.legacyUnloads)) handler.extensionUnloaded();
      }); edt(() -> {}); edt(() -> {});
      check(controller.closed && controller.window == null && !frame.isDisplayable(),
          "unload disposes the real native window after contextual button use");
      edt(controller::open);
      check(controller.window == null, "late launcher activation cannot recreate an unloaded workspace");
      check(jarSha.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(loadedJar)))),
          "native placement checks use one unchanged loaded JAR");
      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("result", "passed"); summary.put("version", BuildInfo.VERSION); summary.put("theme", theme);
      summary.put("checks", checks); summary.put("jarSha256", jarSha); summary.put("nativeSwing", true);
      summary.put("nativeBurpGui", false); summary.put("syntheticPackets", true);
      summary.put("display", "isolated Xorg dummy + xfwm4"); summary.put("windowWidths", sizes);
      summary.put("targetHttpRequests", PayloadIntegrationTest.sent.get() - sentBefore);
      summary.put("screenshots", captures);
      Files.writeString(output.resolve("native-button-summary.json"), MetadataExport.json(summary) + "\n");
      System.out.println("ButtonPlacementNative: " + checks + " checks passed (real pointer/menu/Swing/WM, zero target HTTP)");
    } finally {
      final WorkbenchWindow cleanup = desktop;
      edt(() -> {
        if (cleanup != null) cleanup.close();
        for (var handler : List.copyOf(PayloadIntegrationTest.modernUnloads)) try { handler.extensionUnloaded(); } catch (Exception ignored) {}
        for (var handler : List.copyOf(PayloadIntegrationTest.legacyUnloads)) try { handler.extensionUnloaded(); } catch (Exception ignored) {}
        if (host[0] != null) host[0].dispose();
        for (Window window : Window.getWindows()) window.dispose();
      });
    }
  }
}
