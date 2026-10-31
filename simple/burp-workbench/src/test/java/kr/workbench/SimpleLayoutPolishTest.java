package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.awt.*;
import javax.swing.*;

/** Reproduces the wasted-space and misplaced-control defects using actual components. */
public final class SimpleLayoutPolishTest extends WorkbenchTest {
  private static Component named(Component root, String name) {
    Component result = SimpleWorkflowTest.named(root, name);
    if (result == null) throw new AssertionError("Missing " + name);
    return result;
  }
  private static void layout(Component root, int width, int height) {
    root.setSize(width, height);
    for (int i = 0; i < 8; i++) UiLayoutTest.layout((Container) root);
  }
  private static void belongs(Component root, String control, String area) {
    check(SwingUtilities.isDescendingFrom(named(root, control), named(root, area)), control + " is beside the data it acts on");
  }
  public static void main(String[] args) throws Exception {
    BountyFeaturesTest.factory();
    var entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks) PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(() -> entry.initialize(SimpleWorkflowTest.previewApi()));
    var extension = (WorkbenchExtension) get(entry, "workbench");
    JPanel root = (JPanel) get(extension, "root");
    int sends = PayloadIntegrationTest.sent.get();
    try {
      edt(() -> {
        var auth = (AuthComparePanel) SimpleWorkflowTest.unchecked(extension, "authCompare");
        JPanel form = auth.preparationAccountChoices(); WorkbenchUi.style(form);
        for (int width : new int[]{600, 1000}) {
          layout(form, width, 300);
          int previous = -1;
          for (int i = 0; i < 4; i++) {
            Component field = named(form, "wb.auth.preparation.account." + i);
            Rectangle b = SwingUtilities.convertRectangle(field.getParent(), field.getBounds(), form);
            check(b.x <= 100 && b.width >= width - 110 && b.x + b.width <= width,
                "account field uses the available width from a shared left label column at " + width);
            check(b.y > previous && b.height >= 28, "account rows remain ordered and readable"); previous = b.y;
          }
        }
        var bounty = (BountySupportPanel) SimpleWorkflowTest.unchecked(extension, "bountySupport");
        extension.openFeature("bounty.replay");
        belongs(root, "wb.bounty.flow.append", "wb.actions.bounty.flow.request");
        belongs(root, "wb.bounty.flow.evidence", "wb.actions.bounty.flow.results");
        belongs(root, "wb.bounty.flow.run", "wb.actions.bounty.flow.execution");
        belongs(root, "wb.bounty.flow.stop", "wb.actions.bounty.flow.execution");
        bounty.workflow.setText("AUTHORED_REQUEST_LAYOUT_TEST");
        for (int width : new int[]{1024,1280,1600}) layout(root, width, 800);
        check(bounty.workflow.getText().equals("AUTHORED_REQUEST_LAYOUT_TEST"), "responsive rearrangement retains authored request");
        JTable empty = new JTable(new Object[0][1],new Object[]{"URL"});
        empty.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); empty.setFont(WorkbenchUi.font());
        JViewport viewport = new JViewport(); viewport.setSize(320,200); viewport.setView(empty);
        viewport.setViewSize(new Dimension(800,200));
        for (int offset : new int[]{0,150}) {
          viewport.setViewPosition(new Point(offset,0));
          java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(800,200,java.awt.image.BufferedImage.TYPE_INT_ARGB);
          Graphics2D graphics = image.createGraphics(); kr.packetui.VisualTheme.emptyTable(graphics,empty); graphics.dispose();
          int min=800,max=-1;
          for(int y=0;y<200;y++) for(int x=0;x<800;x++) if((image.getRGB(x,y)>>>24)!=0){min=Math.min(min,x);max=Math.max(max,x);}
          check(max>=min && min>=offset && max<offset+320 && Math.abs((min+max)/2-(offset+160))<12,
              "empty-table message stays centered in the actual viewport after horizontal scroll " + offset);
        }
        var activity = (ActivityPanel) SimpleWorkflowTest.unchecked(extension, "activity");
        check(activity.table.getColumnModel().getColumn(1).getWidth() >= 300,
            "progress URL column is assigned its readable width, not JTable's 75px default");
        check(ReferenceUi.step(1,"요청 준비").getPreferredSize().height == 40,
            "compact preparation step retains its marker in a 40px row");
      });
      ScopeDashboardPanel[] dashboard = new ScopeDashboardPanel[1];
      edt(() -> dashboard[0] = new ScopeDashboardPanel(new ScopeDashboardPanel.Actions() {
        public void inspect() {} public void original(String id) {} public void repeater(String id) {} public void evidence(String id) {}
      }));
      try {
        edt(() -> {
          JPanel host = new JPanel(new BorderLayout()); host.add(dashboard[0]); WorkbenchUi.style(host);
          layout(host,1300,1100);
          Component split = named(host,"wb.scope.dashboard.results.split");
          Rectangle b = SwingUtilities.convertRectangle(split.getParent(),split.getBounds(),host);
          check(b.height > 400 && host.getHeight()-b.getMaxY()<24,
              "dashboard results consume remaining tall-window height");
          layout(host,1000,450);
          JScrollPane scroll = (JScrollPane) named(host,"wb.scope.dashboard.scroll");
          check(scroll.getViewport().getViewSize().height>scroll.getViewport().getExtentSize().height,
              "small dashboard keeps vertical scrolling instead of crushing results");
        });
      } finally { edt(dashboard[0]::close); }
      check(PayloadIntegrationTest.sent.get()==sends,"layout and navigation send no target HTTP");
    } finally { SimpleWorkflowTest.unload(); }
    System.out.println("SimpleLayoutPolishTest: " + checks + " checks passed");
  }
}
