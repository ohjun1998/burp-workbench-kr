package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.awt.*;
import javax.swing.*;

/** Approved workflows supersede the old footer rule; hierarchy must remain usable at narrow widths. */
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
  private static Component exposed(Component root,String name) {
    Component action=named(root,name);
    Component visible=ApprovedWorkflowTestSupport.representation(action,root);
    if(visible==null)throw new AssertionError("No visible entry for "+name);
    return visible;
  }
  private static boolean adjacent(Component root,String first,String second) {
    Rectangle a=bounds(named(root,first),root),b=bounds(named(root,second),root);
    return shown(named(root,first),root)&&shown(named(root,second),root)
        &&Math.abs(a.y-b.y)<=2&&a.getMaxX()<=b.x;
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
          Component repeater=named(root,"wb.simple.traffic.repeater");
          check(shown(repeater,root)
                  &&Boolean.TRUE.equals(((JComponent)repeater).getClientProperty("wb.primary")),
              "traffic makes selected-request preparation the visible primary action at " + width);
          boolean grouped=true;
          for(String name:new String[]{"wb.simple.traffic.evidence","wb.simple.traffic.compare"}) {
            Component action=named(root,name),menuEntry=exposed(root,name);
            var access=ApprovedWorkflowTestSupport.menuAccess(action,root);
            grouped &= !ApprovedWorkflowTestSupport.visible(action,root) && access!=null
                && shown(menuEntry,root) && access.item().isEnabled()==((AbstractButton)action).isEnabled();
          }
          check(grouped,"secondary request actions share a real visible menu without duplicate buttons at " + width);
          Component trafficTable=named(root,"wb.traffic.history");
          JScrollPane tableScroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,trafficTable);
          check(tableScroll!=null&&bounds(tableScroll,root).height>=90
                  &&shown(named(root,"wb.traffic.search"),root),
              "finding requests keeps a readable result area and visible search at " + width);

          extension.openFeature("auth"); layout(root,width);
          check(adjacent(root,"wb.simple.auth.review","wb.simple.auth.stop"),
              "comparison preparation and stop stay together and visible at " + width);
          check(Boolean.TRUE.equals(((JComponent)named(root,"wb.simple.auth.review")).getClientProperty("wb.primary"))
                  &&shown(named(root,"wb.auth.history.search"),root),
              "comparison has one clear preparation entry beside its visible record search at " + width);
          Component role=named(root,"wb.auth.account.card.0");
          check(shown(role,root)&&role.getHeight()<=50,
              "compact role summary reserves space for response reading at " + width);

          extension.openFeature("payload");
          JTabbedPane phases = (JTabbedPane) named(root,"wb.payload.steps");
          phases.setSelectedIndex(0);
          Component payload = named(root,"wb.payload.root"), execution = named(root,"wb.actions.payload.execution");
          JTextArea request = (JTextArea) named(root,"wb.simple.payload.request");
          request.setText("GET /authored-placement-test?q=KEEP HTTP/1.1\r\nHost: example.test\r\n\r\n");
          String authored = request.getText();
          JToggleButton candidates=(JToggleButton)named(root,"wb.payload.candidates.toggle");
          JTextArea values=(JTextArea)named(root,"wb.simple.payload.values");
          String authoredCandidates="SYNTHETIC_CANDIDATE_17\n한글 & <KEEP>";
          values.setText(authoredCandidates);
          check(!candidates.isSelected(),"candidate editing starts folded while the request remains primary at " + width);
          candidates.doClick(0); layout(root,width);
          check(candidates.isSelected()&&ApprovedWorkflowTestSupport.visible(values,root),
              "the actual candidates entry reveals the retained editor at " + width);
          candidates.doClick(0); layout(root,width);
          check(values.getText().equals(authoredCandidates),"folding candidates preserves authored values at " + width);
          for (int phase = 0; phase < 2; phase++) {
            phases.setSelectedIndex(phase); layout(root,width);
            Component start = named(root,"wb.simple.payload.start"), stop = named(root,"wb.simple.payload.stop");
            check(SwingUtilities.isDescendingFrom(start,execution) && SwingUtilities.isDescendingFrom(stop,execution)
                    && !SwingUtilities.isDescendingFrom(execution,phases),
                "payload execution and stop remain outside either phase at " + width + "/" + phase);
            String geometry = " start=" + bounds(start,root) + " stop=" + bounds(stop,root)
                + " execution=" + bounds(execution,root);
            check(shown(start,root) && shown(stop,root),
                "payload start and stop stay fully visible at " + width + "/" + phase + geometry);
            check(adjacent(root,"wb.simple.payload.start","wb.simple.payload.stop"),
                "payload execution and stop stay adjacent at " + width + "/" + phase + geometry);
          }
          check(request.getText().equals(authored), "phase changes and responsive layout preserve authored request at " + width);
        }
      });
      check(PayloadIntegrationTest.sent.get()==before, "navigation, resizing and phase changes send zero target HTTP");
    } finally { SimpleWorkflowTest.unload(); }
    System.out.println("SimpleButtonPlacementCorrectionTest: " + checks + " checks passed");
  }
}
