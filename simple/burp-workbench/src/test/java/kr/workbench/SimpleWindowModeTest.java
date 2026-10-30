package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.UserInterface;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** Headless production integration; native frame/monitor behavior is verified separately. */
public final class SimpleWindowModeTest extends WorkbenchTest {
  private static Object value(Object owner, String field) {
    try { return get(owner, field); }
    catch (Exception error) { throw new IllegalStateException(error); }
  }

  private static void unload() throws Exception {
    for (var handler : List.copyOf(PayloadIntegrationTest.modernUnloads)) handler.extensionUnloaded();
    for (var handler : List.copyOf(PayloadIntegrationTest.legacyUnloads)) handler.extensionUnloaded();
    edt(() -> {});
  }

  public static void main(String[] args) throws Exception {
    check(GraphicsEnvironment.isHeadless(), "window adapter test runs headless without creating a native frame");
    BountyFeaturesTest.factory();
    MontoyaApi fallback = SimpleWorkflowTest.previewApi();
    UserInterface originalUi = fallback.userInterface();
    AtomicReference<Component> registered = new AtomicReference<>();
    AtomicReference<String> registeredTitle = new AtomicReference<>();
    AtomicInteger registrations = new AtomicInteger();
    UserInterface ui = proxy(UserInterface.class, (p, method, arguments) -> {
      if (method.getName().equals("registerSuiteTab")) {
        registrations.incrementAndGet();
        registeredTitle.set((String) arguments[0]);
        registered.set((Component) arguments[1]);
        return null;
      }
      return method.invoke(originalUi, arguments);
    });
    MontoyaApi api = proxy(MontoyaApi.class, (p, method, arguments) ->
        method.getName().equals("userInterface") ? ui : method.invoke(fallback, arguments));
    var entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks) PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(() -> entry.initialize(api));
    var extension = (WorkbenchExtension) get(entry, "workbench");
    var root = (JPanel) get(extension, "root");
    var controller = (WorkbenchWindow) get(extension, "workbenchWindow");
    var tabs = (JTabbedPane) get(extension, "tabs");
    var codec = (JTextArea) get(extension, "codecIn");
    var payload = (JTextArea) get(get(entry, "payloadRunner"), "request");
    Map<String, Object> engines = new LinkedHashMap<>();
    for (String field : List.of("authCompare", "responseRecon", "jsAnalysis", "bountySupport", "environments", "featureMapper", "exposureResearch", "payloadPage"))
      engines.put(field, get(extension, field));
    JTabbedPane burpTabs = new JTabbedPane();
    int originalTabMouseListeners = burpTabs.getMouseListeners().length;
    AtomicReference<Action> fullScreen = new AtomicReference<>();
    AtomicReference<Action> escape = new AtomicReference<>();
    AtomicReference<HierarchyListener[]> lateHierarchy = new AtomicReference<>();
    boolean didUnload = false;
    int sentBefore = PayloadIntegrationTest.sent.get();
    try {
      seedTraffic(extension, request("https://window-mode.test/api/record/42", "GET", true, List.of()),
          response(200, "ORIGINAL_WINDOW_PACKET"));
      Object original = get(extension, "current");
      check(original != null, "synthetic source packet is selected before window actions");
      edt(() -> {
        check(registrations.get() == 1 && FeatureGuide.SUITE_TAB.equals(registeredTitle.get()),
            "only one production Workbench suite tab is registered");
        check(registered.get() == controller.launcher && registered.get() != root,
            "Burp receives the launcher instead of the complete workspace");
        check(controller.workspace == root && !SwingUtilities.isDescendingFrom(root, controller.launcher),
            "the launcher keeps the original workspace independent of Burp's tab chrome");
        check(SimpleWorkflowTest.named(root, "wb.window.fullscreen") == controller.fullScreenButton,
            "the production fullscreen action is installed in the workspace");
        check(SwingUtilities.isDescendingFrom(controller.fullScreenButton,
                (JPanel) SimpleWorkflowTest.named(root, "wb.navigation.actions")),
            "fullscreen uses the existing navigation actions without an extra toolbar row");
        Object fullKey = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("F11"));
        Object escapeKey = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("ESCAPE"));
        fullScreen.set(root.getActionMap().get(fullKey));
        escape.set(root.getActionMap().get(escapeKey));
        check(fullScreen.get() != null && escape.get() != null, "F11 and Escape resolve to live production window actions");
        codec.setText("UNSAVED_WINDOW_CODEC_42");
        payload.setText("GET /original?value=UNSAVED_42 HTTP/1.1\r\nHost: window-mode.test\r\n\r\n");
        extension.openFeature("js");
        Component chosen = tabs.getSelectedComponent();
        controller.openButton.doClick(0);
        controller.hide();
        controller.open();
        controller.fullScreenButton.doClick(0);
        fullScreen.get().actionPerformed(new ActionEvent(root, ActionEvent.ACTION_PERFORMED, "F11"));
        escape.get().actionPerformed(new ActionEvent(root, ActionEvent.ACTION_PERFORMED, "ESCAPE"));
        check(controller.window == null && !controller.isFullScreen(),
            "headless launch and fullscreen actions remain safe and do not invent a native fullscreen state");
        check(controller.status.getText().contains("그래픽 환경"), "headless launch reports the missing graphical display");
        check(tabs.getSelectedComponent() == chosen && value(extension, "current") == original,
            "open hide reopen and keyboard actions preserve the selected tool and original packet");
        check(codec.getText().equals("UNSAVED_WINDOW_CODEC_42") && payload.getText().contains("UNSAVED_42"),
            "unsaved converter and payload edits survive all window actions");
        for (var engine : engines.entrySet())
          check(value(extension, engine.getKey()) == engine.getValue(), "window actions retain the existing " + engine.getKey() + " instance");
        extension.selectPayloadRunnerPage();
        check(tabs.getSelectedComponent() == engines.get("payloadPage"), "runner callback still selects the retained payload page");
        extension.openFeature("exposure");
        check(tabs.getSelectedComponent() == engines.get("exposureResearch"), "programmatic navigation still selects the CVE research page");
        burpTabs.addTab("Workbench", controller.launcher);
      });
      edt(() -> {});
      edt(() -> {
        check(burpTabs.getMouseListeners().length == originalTabMouseListeners + 1,
            "the launcher watches its real tab ancestor for repeated selection clicks");
        lateHierarchy.set(controller.launcher.getHierarchyListeners().clone());
        check(lateHierarchy.get().length > 0, "the launcher installs its hierarchy lifecycle hook");
        check(PayloadIntegrationTest.sent.get() == sentBefore, "window and tool navigation issue zero target HTTP requests");
      });
      unload(); didUnload = true;
      edt(() -> {
        check(controller.closed && controller.window == null, "extension unload closes the independent window controller");
        check(!controller.openButton.isEnabled() && !controller.fullScreenButton.isEnabled(),
            "unload disables launcher and fullscreen actions");
        check(burpTabs.getMouseListeners().length == originalTabMouseListeners,
            "unload removes its listener from the Burp tab ancestor");
        check(root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("F11")) == null
                && root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("ESCAPE")) == null,
            "unload removes both window keyboard bindings");
        String closedStatus = controller.status.getText();
        for (HierarchyListener listener : lateHierarchy.get()) listener.hierarchyChanged(
            new HierarchyEvent(controller.launcher, HierarchyEvent.HIERARCHY_CHANGED,
                controller.launcher, burpTabs, HierarchyEvent.SHOWING_CHANGED));
        controller.open(); controller.hide(); controller.toggleFullScreen();
        fullScreen.get().actionPerformed(new ActionEvent(root, ActionEvent.ACTION_PERFORMED, "late-F11"));
        escape.get().actionPerformed(new ActionEvent(root, ActionEvent.ACTION_PERFORMED, "late-ESCAPE"));
        check(controller.window == null && controller.closed && controller.status.getText().equals(closedStatus),
            "late callbacks cannot resurrect the closed window or overwrite its shutdown state");
      });
      edt(() -> {});
      check(controller.window == null && PayloadIntegrationTest.sent.get() == sentBefore,
          "deferred callbacks after unload create no window and send no target HTTP");
    } finally {
      if (!didUnload) unload();
    }
    System.out.println("SimpleWindowModeTest: " + checks + " checks passed (production headless Swing integration; no native GUI)");
  }
}
