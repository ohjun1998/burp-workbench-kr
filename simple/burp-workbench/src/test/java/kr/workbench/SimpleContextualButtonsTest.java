package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.awt.Component;
import java.awt.Rectangle;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

/** Moved production actions still edit the same data and require explicit execution. */
public final class SimpleContextualButtonsTest extends WorkbenchTest {
  private static Component named(Component root, String name) {
    Component value = SimpleWorkflowTest.named(root, name);
    if (value == null) throw new AssertionError("Missing production control: " + name);
    return value;
  }

  private static AbstractButton button(Component root, String name) {
    return (AbstractButton) named(root, name);
  }

  private static void local(Component root, String control, String context) {
    check(SwingUtilities.isDescendingFrom(named(root, control), named(root, context)),
        control + " belongs to its current data area " + context);
  }

  /** Visibility relative to the workspace is meaningful even in a headless host. */
  private static boolean visible(Component value, Component workspace) {
    for (Component current = value; current != null; current = current.getParent()) {
      if (!current.isVisible()) return false;
      if (current == workspace) return true;
    }
    return false;
  }

  private static void invalidateTree(Component component){
    if(component instanceof java.awt.Container parent){
      parent.invalidate();for(Component child:parent.getComponents())invalidateTree(child);
    }
  }

  private static boolean fullyVisible(Component control,Component workspace){
    if(!visible(control,workspace))return false;
    Rectangle bounds=SwingUtilities.convertRectangle(control.getParent(),control.getBounds(),workspace);
    if(bounds.width<=0||bounds.height<=0)return false;
    for(Component parent=control.getParent();parent!=null;parent=parent.getParent()){
      Rectangle viewport=SwingUtilities.convertRectangle(parent,new Rectangle(0,0,parent.getWidth(),parent.getHeight()),workspace);
      if(!viewport.contains(bounds))return false;
      if(parent==workspace)return true;
    }
    return false;
  }

  private static String segment(String text) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  private static void selectedTrafficContext(WorkbenchExtension extension,JPanel root,JTable table,
      int model,String method,String path,byte[] wire) {
    int view=table.convertRowIndexToView(model);
    check(view>=0,"selected original remains reachable in the current sorted or filtered view: "+path);
    table.setRowSelectionInterval(view,view);
    JLabel context=(JLabel)named(root,"wb.simple.traffic.selection");
    Object selected=((List<?>)SimpleWorkflowTest.unchecked(extension,"traffic")).get(model);
    var selectedPacket=(DiskTrafficStore.PacketRef)SimpleWorkflowTest.unchecked(selected,"packet");
    TrafficBrowser browser=(TrafficBrowser)SimpleWorkflowTest.unchecked(extension,"trafficBrowser");
    String expected=method+" "+path+" · "+selectedPacket.responseStatus()+" · "+browser.extra(model,8)+" · "+selectedPacket.time()+" · 선택 1건";
    check(context.getText().equals(expected)&&context.getToolTipText().equals(expected),
        "selection context immediately names the actual selected method/path/status/account/time: "+expected);
    Object current=SimpleWorkflowTest.unchecked(extension,"current");
    var currentPacket=(DiskTrafficStore.PacketRef)SimpleWorkflowTest.unchecked(current,"packet");
    PacketInspector reader=(PacketInspector)SimpleWorkflowTest.unchecked(extension,"requestPacket");
    var summary=(JLabel)SimpleWorkflowTest.named(root,"wb.simple.traffic.selection");
    check(summary.getText().contains(selectedPacket.responseStatus())&&summary.getText().contains(selectedPacket.time()),
        "selection context shows the same original response status and time after sorting/filtering: "+path);
    check(currentPacket.id().equals(selectedPacket.id())
            &&java.util.Arrays.equals(reader.previewBytes(),wire),
        "selection context and reader retain the same exact original identity and bytes: "+path);
  }

  private static void trafficSelectionContext(WorkbenchExtension extension,JPanel root,
      burp.api.montoya.http.message.requests.HttpRequest original) throws Exception {
    var post=request("https://buttons.example.test/api/context-zeta?token=KEEP_POST", "POST",true,List.of());
    var delete=request("https://buttons.example.test/api/context-alpha?token=KEEP_DELETE", "DELETE",true,List.of());
    seedTraffic(extension,post,response(201,"CONTEXT_POST_ORIGINAL"));
    seedTraffic(extension,delete,response(202,"CONTEXT_DELETE_ORIGINAL"));
    JTable table=(JTable)SimpleWorkflowTest.unchecked(extension,"logTable");
    TrafficBrowser browser=(TrafficBrowser)SimpleWorkflowTest.unchecked(extension,"trafficBrowser");
    int before=PayloadIntegrationTest.sent.get();
    edt(() -> {
      table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(0,SortOrder.DESCENDING)));
      selectedTrafficContext(extension,root,table,1,"POST","/api/context-zeta",post.toByteArray().getBytes());
      selectedTrafficContext(extension,root,table,0,"GET","/api/17",original.toByteArray().getBytes());
      browser.search.setText("/api/context-");
    });
    TrafficSearchUpgradeTest.idle(browser);
    edt(() -> {
      check(table.getRowCount()==2,"filtered selection context uses only the two matching original rows");
      selectedTrafficContext(extension,root,table,1,"POST","/api/context-zeta",post.toByteArray().getBytes());
      selectedTrafficContext(extension,root,table,2,"DELETE","/api/context-alpha",delete.toByteArray().getBytes());
      browser.search.setText("");
    });
    TrafficSearchUpgradeTest.idle(browser);
    edt(() -> {
      table.getRowSorter().setSortKeys(List.of());
      selectedTrafficContext(extension,root,table,0,"GET","/api/17",original.toByteArray().getBytes());
      check(PayloadIntegrationTest.sent.get()==before,
          "sorted and filtered original selection updates context without target HTTP");
    });
  }

  private static void researchActions() throws Exception {
    AtomicInteger providerCalls = new AtomicInteger();
    var client = new VulnerabilityClient(uri -> {
      providerCalls.incrementAndGet();
      return new VulnerabilityClient.Reply(200, SimpleExposureResearchTest.fixture(), "");
    });
    ExposureResearchPanel[] holder = new ExposureResearchPanel[1];
    try {
      edt(() -> {
        var panel = new ExposureResearchPanel(null, url -> url.startsWith("https://example.test/"), client);
        holder[0] = panel;
        panel.target.setText("example.test");
        panel.fillProduct(SimpleExposureResearchTest.p("1.22.1"));
        local(panel, "wb.exposure.lookup", "wb.actions.exposure.cve.lookup");
        local(panel, "wb.exposure.cancel", "wb.actions.exposure.cve.lookup");
        local(panel, "wb.exposure.cve.source", "wb.actions.exposure.cve.detail");
        local(panel, "wb.exposure.cve.copy", "wb.actions.exposure.cve.detail");
        local(panel, "wb.exposure.search.repeater", "wb.actions.exposure.search.detail");
        local(panel, "wb.exposure.search.import", "wb.actions.exposure.search.list");
        check(!button(panel, "wb.exposure.cve.source").isEnabled()
            && !button(panel, "wb.exposure.cve.copy").isEnabled(),
            "CVE detail actions are disabled until a candidate is selected");
        var result = new ExposureResearch.Batch(
            ExposureResearch.parseNvd(SimpleExposureResearchTest.fixture()), Set.of(), Map.of(),
            Instant.parse("2026-10-31T00:00:00Z"), "미확인", "미확인", false);
        panel.display(result, SimpleExposureResearchTest.p("1.22.1"), false);
        panel.tabs.setSelectedIndex(1);
        panel.cveTable.setRowSelectionInterval(0, 0);
        check(button(panel, "wb.exposure.cve.source").isEnabled()
            && button(panel, "wb.exposure.cve.copy").isEnabled(),
            "selected CVE activates the same source and evidence actions");
        check(panel.evidence.getText().contains("fixture-store:42")
            && panel.version.getText().equals("1.22.1") && panel.references.getItemCount() == 2
            && panel.references.getItemAt(0).equals("https://nvd.nist.gov/vuln/detail/CVE-2099-10001")
            && panel.references.getItemAt(1).equals("https://example.test/security/advisory"),
            "selecting CVE detail retains observed version, original identity and real source reference");
        var filterToggle=(JToggleButton)named(panel,"wb.flow.exposure.cve.filters.toggle");
        check(!filterToggle.isSelected(),"advanced CVE filters are folded by default without changing their models");
        filterToggle.doClick(0);
        check(filterToggle.isSelected(),"the real filter entry reveals every retained advanced condition");
        for(int width:new int[]{760,1000,1300}){
          panel.setSize(width,720);
          for(int pass=0;pass<8;pass++){invalidateTree(panel);UiLayoutTest.layout(panel);}
          Component filters=named(panel,"wb.actions.exposure.cve.filters");
          for(String suffix:List.of("minimum","remote","kev","unknown")){
            Component control=named(panel,"wb.exposure.filter."+suffix);
            Rectangle bounds=SwingUtilities.convertRectangle(control.getParent(),control.getBounds(),filters);
            check(fullyVisible(control,panel)&&bounds.width>0&&bounds.height>0
                &&new Rectangle(0,0,filters.getWidth(),filters.getHeight()).contains(bounds),
                "CVE filter "+suffix+" remains fully visible at "+width+"px content width");
          }
          check(panel.cveTable.getSelectedRow()==0&&panel.evidence.getText().contains("fixture-store:42"),
              "CVE metadata reflow preserves selected candidate and original evidence at "+width+"px");
        }
        panel.minimum.setValue(10.0);
        check(panel.cveTable.getRowCount() == 0 && panel.evidence.getText().isBlank()
            && !button(panel, "wb.exposure.cve.source").isEnabled()
            && !button(panel, "wb.exposure.cve.copy").isEnabled(),
            "filtering away a CVE clears its source actions and stale evidence together");
        panel.tabs.setSelectedIndex(0);
        panel.importUrls("https://example.test/docs\nhttps://example.test/api-docs");
        panel.exposureTable.setRowSelectionInterval(1, 1);
        check(panel.exposureDetail.getText().contains("https://example.test/api-docs")
            && button(panel, "wb.exposure.search.repeater").isEnabled(),
            "URL selection enables the detail-local action for the exact selected URL");
        panel.exposureTable.clearSelection();
        check(!button(panel, "wb.exposure.search.repeater").isEnabled(),
            "clearing URL selection disables preparation without changing button location");
        check(providerCalls.get() == 0 && SimpleWorkflowTest.unchecked(panel, "job") == null,
            "tab changes, filters, selected details and imports perform zero provider lookups");
      });
    } finally {
      if (holder[0] != null) edt(holder[0]::close);
    }
  }

  private static void dashboardActions() throws Exception {
    AtomicInteger opened = new AtomicInteger();
    List<String> handoffs = new ArrayList<>();
    ScopeDashboardPanel[] holder = new ScopeDashboardPanel[1];
    edt(() -> holder[0] = new ScopeDashboardPanel(new ScopeDashboardPanel.Actions() {
      public void inspect() { opened.incrementAndGet(); }
      public void original(String id) { handoffs.add("original:" + id); }
      public void repeater(String id) { handoffs.add("repeater:" + id); }
      public void evidence(String id) { handoffs.add("evidence:" + id); }
    }));
    var panel = holder[0];
    try (var originals = new DiskTrafficStore()) {
      var packet = originals.append(request("https://example.test/api/17", "GET", true, List.of()),
          response(200, "{\"example\":17}"));
      String originalId = packet.id();
      try {
      edt(() -> {
        for (String suffix : List.of("original", "repeater", "evidence"))
          local(panel, "wb.scope.dashboard." + suffix, "wb.actions.dashboard.selection");
        check(!panel.original.isEnabled() && !panel.repeater.isEnabled() && !panel.evidence.isEnabled(),
            "empty dashboard keeps selected-result actions disabled");
        check(opened.get() == 0 && handoffs.isEmpty(),
            "dashboard construction does not navigate or prepare any result");
        var finding = new ScopeInspection.Finding("SECRET", "선택 원본 예시", "https://example.test/api/17",
            "GET", "Alice", "형식 후보", "높음", "유효성 미검증", "합성 근거", originalId,
            1, 5, "contextual-buttons-fixture");
        panel.update(new ScopeInspection.Snapshot("contextual-buttons", ScopeInspection.State.COMPLETED,
            "완료", 1, 1, 0, 0, 0, 0, List.of(finding),
            List.of(new ScopeInspection.Page("https://example.test/api/17", "GET", "Alice", 200,
                "JSON", true, "", "contextual-buttons", packet)), List.of(), 0, 0), 10, "example.test");
      });
      edt(() -> {});
      edt(() -> {
        panel.cards.get(ScopeInspectionDashboard.Group.SECRETS).doClick(0);
        panel.table.setRowSelectionInterval(0, 0);
        check(handoffs.isEmpty(), "selecting a dashboard result does not invoke a handoff");
        panel.original.doClick(0); panel.repeater.doClick(0); panel.evidence.doClick(0);
        check(handoffs.equals(List.of("original:" + originalId, "repeater:" + originalId,
            "evidence:" + originalId)),
            "all relocated result actions hand off the exact immutable selected original");
        panel.table.clearSelection();
        check(!panel.original.isEnabled() && !panel.repeater.isEnabled() && !panel.evidence.isEnabled(),
            "cleared dashboard selection disables all local original actions");
        panel.inspect.doClick(0);
        check(opened.get() == 1 && handoffs.size() == 3,
            "inspection-open remains explicit navigation separate from selected-result actions");
      });
      } finally { edt(panel::close); }
    }
  }

  public static void main(String[] args) throws Exception {
    BountyFeaturesTest.factory();
    var entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks) PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(() -> entry.initialize(SimpleWorkflowTest.previewApi()));
    var extension = (WorkbenchExtension) get(entry, "workbench");
    var root = (JPanel) get(extension, "root");
    Object runner = get(entry, "payloadRunner");
    int sends = PayloadIntegrationTest.sent.get();
    try {
      var contextOriginal=request("https://buttons.example.test/api/17", "GET", true, List.of());
      seedTraffic(extension,contextOriginal,response(200,"CONTEXT_BUTTON_ORIGINAL"));
      trafficSelectionContext(extension,root,contextOriginal);
      Object original = get(extension, "current");
      edt(() -> {
        extension.openFeature("traffic");
        local(root, "wb.simple.traffic.compare", "wb.actions.traffic.selection");
        local(root, "wb.simple.traffic.repeater", "wb.actions.traffic.selection");
        local(root, "wb.simple.traffic.evidence", "wb.actions.traffic.selection");
        var settings = (JComponent) named(root, "wb.traffic.settings");
        JPopupMenu menu = settings.getComponentPopupMenu();
        check(menu != null,
            "one traffic settings entry retains the original categories and view operations");
        List<String> menuNames = java.util.Arrays.stream(menu.getComponents()).map(Component::getName).toList();
        check(menuNames.containsAll(List.of("wb.traffic.scope.list", "wb.traffic.search.settings",
                "wb.traffic.view.freeze.menu", "wb.traffic.view.apply.menu")),
            "combined settings preserve capture, search/list, freeze and new-traffic operations");
        for (Component item : menu.getComponents()) {
          if(item instanceof JMenuItem action)
            check(action.getActionListeners().length>0,"traffic menu entries retain actual handlers");
          else check(item instanceof JPopupMenu.Separator,"traffic menu separates configuration and view operations");
        }

        extension.openFeature("data");
        local(root, "wb.data.codec.convert", "wb.actions.data.codec.input");
        local(root, "wb.data.codec.reuse", "wb.actions.data.codec.output");
        local(root, "wb.data.codec.copy", "wb.actions.data.codec.output");
        JTextArea input = (JTextArea) SimpleWorkflowTest.unchecked(extension, "codecIn");
        JTextArea output = (JTextArea) SimpleWorkflowTest.unchecked(extension, "codecOut");
        var operation = (JComboBox<?>) named(root, "wb.data.codec.operation");
        String authored = "버튼 배치\nORIGINAL_INPUT_17";
        input.setText(authored); operation.setSelectedItem("Base64 인코딩");
        button(root, "wb.data.codec.convert").doClick(0);
        String encoded = Base64.getEncoder().encodeToString(authored.getBytes(StandardCharsets.UTF_8));
        check(output.getText().equals(encoded) && input.getText().equals(authored),
            "input-local conversion writes exact UTF-8 output without changing authored input");
        button(root, "wb.data.codec.reuse").doClick(0);
        check(input.getText().equals(encoded) && output.getText().equals(encoded),
            "output-local reuse copies the same result into input");
        local(root, "wb.data.jwt.decode", "wb.actions.data.jwt.input");
        local(root, "wb.data.jwt.import", "wb.actions.data.jwt.input");
        String jwt = segment("{\"alg\":\"HS256\"}") + "." + segment("{\"sub\":\"context-17\"}") + ".signature";
        JTextArea jwtInput = (JTextArea) SimpleWorkflowTest.unchecked(extension, "jwtIn");
        JTextArea jwtOutput = (JTextArea) SimpleWorkflowTest.unchecked(extension, "jwtOut");
        jwtInput.setText(jwt); button(root, "wb.data.jwt.decode").doClick(0);
        check(jwtInput.getText().equals(jwt) && jwtOutput.getText().contains("context-17")
            && jwtOutput.getText().contains("서명 검증: 수행하지 않음"),
            "input-local JWT action retains token and clearly reports decoding without signature validation");

        extension.openFeature("payload");
        JTabbedPane stages = (JTabbedPane) named(root, "wb.payload.steps");
        Component workspace = named(root, "wb.payload.root");
        local(root, "wb.simple.payload.start", "wb.actions.payload.execution");
        local(root, "wb.simple.payload.stop", "wb.actions.payload.execution");
        local(root, "wb.simple.payload.settings", "wb.actions.payload.execution");
        local(root, "wb.payload.mark", "wb.actions.payload.prepare");
        local(root, "wb.payload.preview", "wb.actions.payload.prepare");
        local(root, "wb.simple.payload.evidence", "wb.actions.payload.results");
        local(root, "wb.payload.repeater", "wb.actions.payload.results");
        JTextArea payload = (JTextArea) SimpleWorkflowTest.unchecked(runner, "request");
        String request = "GET /search?q=original HTTP/1.1\r\nHost: buttons.example.test\r\nX-Memo: KEEP\r\n\r\n";
        payload.setText(request); int at = request.indexOf("original"); payload.select(at, at + 8);
        stages.setSelectedIndex(0);
        check(!visible(named(root, "wb.simple.payload.evidence"), workspace)
            && !ApprovedWorkflowTestSupport.available(named(root, "wb.payload.repeater"), workspace),
            "request preparation omits result-only evidence and Repeater controls");
        button(root, "wb.payload.mark").doClick(0);
        String marked = request.replace("q=original", "q=§PAYLOAD§");
        check(payload.getText().equals(marked),
            "relocated marker action replaces only the selected value in the authored request");
        button(root, "wb.payload.preview").doClick(0);
        button(root, "wb.payload.preview").doClick(0);
        check(payload.getText().equals(marked), "local formatted preview leaves request bytes unchanged");
        button(root, "wb.payload.step.results").doClick(0);
        check(stages.getSelectedIndex() == 1 && visible(named(root, "wb.simple.payload.evidence"), workspace)
            && ApprovedWorkflowTestSupport.available(named(root, "wb.payload.repeater"), workspace),
            "results step reveals the retained result-only actions in the result header");
        check(!button(root, "wb.payload.repeater").isEnabled(),
            "result Repeater cannot prepare an unselected result");
        button(root, "wb.payload.step.prepare").doClick(0);
        check(stages.getSelectedIndex() == 0 && payload.getText().equals(marked),
            "returning to preparation retains the marked original request");
        for (int width : new int[] {1024, 1280, 1600}) {
          root.setSize(width, width == 1024 ? 720 : 1000);
          for (int pass = 0; pass < 6; pass++) UiLayoutTest.layout(root);
          for (String action : List.of("wb.simple.payload.start", "wb.simple.payload.stop")) {
            Component control = named(root, action);
            Rectangle bounds = SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), workspace);
            check(visible(control, workspace) && bounds.width > 0 && bounds.height > 0
                && bounds.x >= 0 && bounds.y >= 0 && bounds.getMaxX() <= workspace.getWidth()
                && bounds.getMaxY() <= workspace.getHeight(),
                action + " stays visible inside the payload workspace at " + width + "px");
          }
        }

        var evidence = (BountySupportPanel) SimpleWorkflowTest.unchecked(extension, "bountySupport");
        extension.openFeature("bounty");
        local(root, "wb.bounty.evidence.remove", "wb.actions.bounty.items");
        local(root, "wb.bounty.evidence.undo", "wb.flow.bounty.items");
        check(visible(named(root, "wb.bounty.evidence.undo"), evidence),
            "evidence undo stays directly available beside the item menu");
        EvidenceEditingTest.append(evidence, "FIRST_BUTTON_PACKET");
        EvidenceEditingTest.append(evidence, "SECOND_BUTTON_PACKET");
        String selectedId = evidence.evidence.get(evidence.evidence.size() - 1).evidenceId();
        evidence.impact.setText("Authored impact stays **exact**");
        JTextField filter = (JTextField) named(evidence, "wb.bounty.evidence.search");
        filter.setText("SECOND_BUTTON_PACKET"); evidence.evidenceTable.setRowSelectionInterval(0, 0);
        button(root, "wb.bounty.evidence.remove").doClick(0);
        check(evidence.evidence.stream().noneMatch(item -> item.evidenceId().equals(selectedId)),
            "list-local delete resolves the filtered selected immutable evidence ID");
        button(root, "wb.bounty.evidence.undo").doClick(0); filter.setText("");
        check(evidence.evidence.stream().anyMatch(item -> item.evidenceId().equals(selectedId))
            && evidence.impact.getText().equals("Authored impact stays **exact**"),
            "list-local undo restores the same evidence ID without changing authored report text");
        check(SimpleWorkflowTest.unchecked(extension, "current") == original,
            "local editing and workflow changes preserve the selected traffic original instance");
        check(button(root, "wb.visual.guide.open").getText().equals("Guide"),
            "shared Guide entry remains available after contextual action relocation");
      });
      researchActions();
      dashboardActions();
      check(PayloadIntegrationTest.sent.get() == sends,
          "moved-action editing, navigation and selected-original handoffs send zero target HTTP");
    } finally { SimpleWorkflowTest.unload(); }
    System.out.println("SimpleContextualButtonsTest: " + checks + " checks passed (actual moved actions, exact originals, zero HTTP)");
  }
}
