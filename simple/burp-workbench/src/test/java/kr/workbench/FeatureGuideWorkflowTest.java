package kr.workbench;

import burp.*;
import burp.api.montoya.MontoyaApi;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Numbered Guide replaces legacy text navigation while retaining tool inputs and original identity. */
public final class FeatureGuideWorkflowTest extends WorkbenchTest {
  private static Object value(Object owner, String field) {
    try { return get(owner, field); } catch (Exception error) { throw new IllegalStateException(error); }
  }
  private static Component named(Component root, String name) { return SimpleWorkflowTest.named(root, name); }
  private static void layout(JPanel root, int width) {
    root.setSize(width, 800); for (int pass = 0; pass < 6; pass++) UiLayoutTest.layout(root);
  }
  public static void main(String[] args) throws Exception {
    BountyFeaturesTest.factory();
    var entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks) PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(() -> entry.initialize(SimpleWorkflowTest.previewApi())); edt(() -> {});
    var extension = (WorkbenchExtension) get(entry, "workbench");
    var root = (JPanel) get(extension, "root");
    var pages = (JTabbedPane) get(extension, "tabs");
    var traffic = (JTable) get(extension, "logTable");
    var input = (JTextArea) get(extension, "codecIn");
    Object runner = get(entry, "payloadRunner");
    var payloadInput = (JTextArea) get(runner, "request");
    var guide = (JButton) named(root, "wb.visual.guide.open");
    AtomicReference<Action> f1 = new AtomicReference<>();
    int sentBefore = PayloadIntegrationTest.sent.get();
    boolean unloaded = false;
    try {
      seedTraffic(extension, request("https://guide.test/app/one", "GET", true,
          List.of(header("Cookie", "session=GUIDE_PRIVATE_FIXTURE"))), response(200, "ORIGINAL_GUIDE_RESPONSE"));
      Object original = get(extension, "current");
      check(original != null, "guide workflow begins with a selected stored original packet");
      edt(() -> {
        check(guide != null && guide.isEnabled() && guide.getText().equals("Guide"),
            "each active tool exposes a plainly labeled Guide action without executing a scan");
        var actions = (JPanel) named(root, "wb.navigation.actions");
        check(actions.getComponent(actions.getComponentCount() - 1) == guide,
            "Guide is the far-right action in the shared tool header");
        check(named(root, "wb.feature.help") == null,
            "the duplicate legacy help icon is removed from the shared header");
        for (int width : new int[]{1024, 1280, 1600}) {
          layout(root, width);
          var toolbar = named(root, "wb.navigation.toolbar");
          Rectangle bounds = SwingUtilities.convertRectangle(guide.getParent(), guide.getBounds(), toolbar);
          check(toolbar.getHeight() <= 46 && bounds.y >= 0 && bounds.y + bounds.height <= toolbar.getHeight(),
              "Guide preserves the compact one-row header at " + width + " pixels");
        }
        Object binding = root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(KeyStroke.getKeyStroke("F1"));
        f1.set(root.getActionMap().get(binding));
        check(f1.get() != null, "F1 resolves to the numbered guide action");
        input.setText("MANUALLY_EDITED_GUIDE_INPUT");
        payloadInput.setText("GET /unchanged?value=MANUALLY_EDITED_PAYLOAD HTTP/1.1\r\nHost: guide.test\r\n\r\n");
        Map<String, Component> engines = new LinkedHashMap<>();
        engines.put("traffic", (Component) value(extension, "trafficTools"));
        engines.put("mapper", (Component) value(extension, "mapperTools"));
        engines.put("js", (Component) value(extension, "jsAnalysis"));
        engines.put("environment", (Component) value(extension, "explorationTools"));
        engines.put("auth", (Component) value(extension, "authCompare"));
        engines.put("response.recon", (Component) value(extension, "responseRecon"));
        engines.put("payload", (Component) value(runner, "panel"));
        engines.put("data", (Component) value(extension, "dataTabs"));
        engines.put("activity", (Component) value(extension, "activity"));
        engines.put("bounty", (Component) value(extension, "bountySupport"));
        engines.put("dashboard", (Component) value(extension, "scopeDashboard"));
        for (var route : engines.entrySet()) {
          extension.openFeature(route.getKey());
          Component selected = pages.getSelectedComponent();
          check(selected == route.getValue() || SwingUtilities.isDescendingFrom(route.getValue(), selected),
              "guide entry retains the actual " + route.getKey() + " tool engine");
          String expected = extension.visualGuideScreen();
          guide.doClick(0);
          var window = (VisualGuideWindow) value(extension, "visualGuideWindow");
          check(window != null && window.panel.selectedId().equals(expected),
              "Guide chooses the current tool screen: " + route.getKey());
          check(window.window == null && window.owner == root,
              "headless guide keeps a modeless-content controller without inventing a native window");
          check(pages.getSelectedComponent() == selected && value(extension, "current") == original && traffic.getSelectedRow() == 0,
              "opening guide preserves current tool and exact original: " + route.getKey());
          check(input.getText().equals("MANUALLY_EDITED_GUIDE_INPUT") && payloadInput.getText().contains("MANUALLY_EDITED_PAYLOAD"),
              "opening guide preserves authored data and payload inputs: " + route.getKey());
        }
        extension.openFeature("exposure");
        var research = (ExposureResearchPanel) value(extension, "exposureResearch");
        research.tabs.setSelectedIndex(0); guide.doClick(0);
        var window = (VisualGuideWindow) value(extension, "visualGuideWindow");
        check(window.panel.selectedId().equals("exposure.search"), "search exposure tab opens its own numbered steps");
        research.tabs.setSelectedIndex(1); guide.doClick(0);
        check(window.panel.selectedId().equals("exposure.cve"), "CVE candidate tab opens version/filter/source steps");
        extension.openFeature("payload");
        var phases = (JTabbedPane) named((Component) value(runner, "panel"), "wb.payload.steps");
        phases.setSelectedIndex(1); guide.doClick(0);
        check(window.panel.selectedId().equals("payload.results"), "payload results have their own screen guide");
        phases.setSelectedIndex(0); guide.doClick(0);
        check(window.panel.selectedId().equals("payload"), "payload preparation restores preparation-stage guidance");
        extension.openFeature("bounty");
        var support = (BountySupportPanel) value(extension, "bountySupport");
        support.pages.setSelectedIndex(1); guide.doClick(0);
        check(window.panel.selectedId().equals("bounty.replay"), "manual replay guide follows the existing nested mode");
        support.pages.setSelectedIndex(3); guide.doClick(0);
        check(window.panel.selectedId().equals("bounty"), "evidence editing opens its own steps without starting replay");
        extension.openFeature("data");
        Component dataPage = pages.getSelectedComponent();
        guide.doClick(0);
        window.panel.screens.setSelectedItem(VisualGuideCatalog.screen("auth.accounts"));
        check(window.panel.selectedId().equals("auth.accounts") && pages.getSelectedComponent() == dataPage
                && input.getText().equals("MANUALLY_EDITED_GUIDE_INPUT"),
            "changing the guide's example changes only documentation, not the user's active tool or input");
        f1.get().actionPerformed(new ActionEvent(root, ActionEvent.ACTION_PERFORMED, "F1"));
        check(window.panel.selectedId().equals("data") && pages.getSelectedComponent() == dataPage,
            "F1 reopens the same numbered Guide for the active tool rather than a legacy text chooser");
        int before = PayloadIntegrationTest.sent.get();
        guide.doClick(0); guide.doClick(0);
        check(value(extension, "visualGuideWindow") == window && PayloadIntegrationTest.sent.get() == before,
            "repeated Guide clicks reuse one controller and never send a target request");
        AtomicInteger settings = new AtomicInteger();
        extension.dialogOpener = (owner, title, content, modal, closed) -> { settings.incrementAndGet(); return null; };
        pages.setSelectedIndex(pages.indexOfTab("설정"));
        check(settings.get() == 1 && pages.getSelectedComponent() == dataPage && input.getText().equals("MANUALLY_EDITED_GUIDE_INPUT"),
            "settings remains a separate action preserving the user's current tool after legacy guide cleanup");
        check(value(extension, "current") == original && traffic.getSelectedRow() == 0 && PayloadIntegrationTest.sent.get() == sentBefore,
            "all numbered guidance, screen choices, F1 and settings preserve originals and send zero target HTTP");
      });
      var window = (VisualGuideWindow) get(extension, "visualGuideWindow");
      for (var handler : List.copyOf(PayloadIntegrationTest.modernUnloads)) handler.extensionUnloaded();
      for (var handler : List.copyOf(PayloadIntegrationTest.legacyUnloads)) handler.extensionUnloaded();
      edt(() -> {}); unloaded = true;
      edt(() -> {
        String selected = window.panel.selectedId();
        guide.doClick(0); f1.get().actionPerformed(new ActionEvent(root, ActionEvent.ACTION_PERFORMED, "late-F1"));
        window.open("traffic");
        check(window.closed && window.window == null && !window.panel.imageReady() && window.panel.selectedId().equals(selected),
            "unload disposes guide resources and prevents late Guide/F1 callbacks from reopening");
      });
      check(PayloadIntegrationTest.sent.get() == sentBefore, "guide teardown sends no target HTTP requests");
    } finally {
      if (!unloaded) {
        for (var handler : List.copyOf(PayloadIntegrationTest.modernUnloads)) handler.extensionUnloaded();
        for (var handler : List.copyOf(PayloadIntegrationTest.legacyUnloads)) handler.extensionUnloaded();
        edt(() -> {});
      }
    }
    System.out.println("FeatureGuideWorkflowTest: " + checks + " checks passed (numbered local guide, retained engines and zero HTTP)");
  }
}
