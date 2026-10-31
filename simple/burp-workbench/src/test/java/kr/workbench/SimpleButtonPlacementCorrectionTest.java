package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.awt.*;
import javax.swing.*;

/** Verify the selected-data commands actually moved below the readers, including narrow windows. */
public final class SimpleButtonPlacementCorrectionTest extends WorkbenchTest {
  private static Component named(Component root, String name) {
    Component result = SimpleWorkflowTest.named(root, name);
    if (result == null) throw new AssertionError("Missing production component: " + name);
    return result;
  }
  private static Rectangle bounds(Component component, Component root) {
    return SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), root);
  }
  private static void layout(JPanel root, int width) {
    root.setSize(width, width == 1024 ? 720 : 1000);
    for (int pass = 0; pass < 8; pass++) UiLayoutTest.layout(root);
  }
  private static boolean shown(Component component, Component root) {
    Rectangle area = bounds(component, root);
    if (area.width <= 0 || area.height <= 0) return false;
    for (Component at = component; at != root; at = at.getParent()) {
      if (at == null || !at.isVisible()) return false;
      if (at != component && !bounds(at, root).contains(area)) return false;
    }
    return new Rectangle(0, 0, root.getWidth(), root.getHeight()).contains(area);
  }
  private static boolean belowTable(Component root, String tableName, String footerName) {
    Component table = named(root, tableName), footer = named(root, footerName);
    Component reader = SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
    if (reader == null) return false;
    Rectangle a = bounds(reader, root), b = bounds(footer, root);
    return a.height > 0 && b.height > 0 && b.y >= a.getMaxY() && b.x < a.getMaxX() && b.getMaxX() > a.x;
  }
  public static void main(String[] args) throws Exception {
    BountyFeaturesTest.factory();
    var entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks) PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(() -> entry.initialize(SimpleWorkflowTest.previewApi()));
    var extension = (WorkbenchExtension) get(entry, "workbench");
    JPanel root = (JPanel) get(extension, "root");
    int before = PayloadIntegrationTest.sent.get();
    try {
      edt(() -> {
        for (int width : new int[]{1024, 1280, 1600}) {
          extension.openFeature("traffic"); layout(root, width);
          Component trafficFooter = named(root, "wb.placement.traffic.selection");
          check(belowTable(root, "wb.traffic.history", "wb.placement.traffic.selection"),
              "traffic commands occupy the fixed strip below the result table at " + width);
          boolean trafficActions = true;
          for (String name : new String[]{"wb.simple.traffic.repeater", "wb.simple.traffic.evidence", "wb.simple.traffic.compare"}) {
            Component action = named(root, name);
            trafficActions &= SwingUtilities.isDescendingFrom(action, trafficFooter) && shown(action, root);
          }
          check(trafficActions, "all selected-request commands remain visible in that strip at " + width);
          check(!SwingUtilities.isDescendingFrom(trafficFooter, named(root,"wb.traffic.controls")),
              "selected-request strip is separate from the top search/filter region at " + width);

          extension.openFeature("auth"); layout(root, width);
          Component authFooter = named(root, "wb.placement.auth.execution");
          check(belowTable(root, "wb.auth.history", "wb.placement.auth.execution"),
              "account comparison commands sit below history and above its packet preview at " + width);
          check(SwingUtilities.isDescendingFrom(named(root,"wb.simple.auth.review"),authFooter)
                  && SwingUtilities.isDescendingFrom(named(root,"wb.simple.auth.stop"),authFooter)
                  && shown(named(root,"wb.simple.auth.review"),root) && shown(named(root,"wb.simple.auth.stop"),root),
              "account comparison and stop stay together and visible at " + width);

          extension.openFeature("payload");
          JTabbedPane phases = (JTabbedPane) named(root,"wb.payload.steps");
          Component payload = named(root,"wb.payload.root"), footer = named(root,"wb.placement.payload.execution");
          JTextArea request = (JTextArea) named(root,"wb.simple.payload.request");
          request.setText("GET /authored-placement-test?q=KEEP HTTP/1.1\r\nHost: example.test\r\n\r\n");
          String authored = request.getText();
          for (int phase = 0; phase < 2; phase++) {
            phases.setSelectedIndex(phase); layout(root,width);
            Component start = named(root,"wb.simple.payload.start"), stop = named(root,"wb.simple.payload.stop");
            check(SwingUtilities.isDescendingFrom(start,footer) && SwingUtilities.isDescendingFrom(stop,footer)
                    && !SwingUtilities.isDescendingFrom(footer,phases) && shown(start,root) && shown(stop,root)
                    && bounds(footer,payload).y >= bounds(phases,payload).getMaxY(),
                "payload execution stays below both phases with visible start/stop at " + width + "/" + phase);
          }
          check(request.getText().equals(authored), "phase changes and responsive layout preserve authored request at " + width);
        }
      });
      check(PayloadIntegrationTest.sent.get()==before, "navigation, resizing and phase changes send zero target HTTP");
    } finally { SimpleWorkflowTest.unload(); }
    System.out.println("SimpleButtonPlacementCorrectionTest: " + checks + " checks passed");
  }
}
