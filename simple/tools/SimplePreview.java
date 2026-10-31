package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.plaf.FontUIResource;

/** Captures production Simple Swing components with isolated synthetic packets, never Burp GUI. */
public final class SimplePreview extends WorkbenchTest {
  static String theme;
  static int width, height;
  static Path out;
  static final List<String> controls = new ArrayList<>();
  static final List<String> anchors = new ArrayList<>();
  static final List<String> content = new ArrayList<>();
  static final List<String> scenarios = new ArrayList<>();

  /** Synthetic result fixture only; never used by the extension or sent to an HTTP transport. */
  static void payloadFixture(Object runner) {
    try {
      ((JTextField)value(runner,"host")).setText("simple.example.test");
      ((JSpinner)value(runner,"port")).setValue(443);
      ((JTextArea)value(runner,"request")).setText("GET /search?q=§PAYLOAD§ HTTP/1.1\r\nHost: simple.example.test\r\nCookie: SID=ALICE_DEMO_SECRET\r\n\r\n");
      Class<?> entry=Class.forName("kr.payloadrunner.PayloadRunnerPanel$Entry");
      var constructor=entry.getDeclaredConstructor(kr.payloadrunner.Engine.Row.class,burp.IHttpService.class);constructor.setAccessible(true);
      @SuppressWarnings("unchecked") var entries=(java.util.List<Object>)value(runner,"entries");
      var model=(javax.swing.table.DefaultTableModel)value(runner,"model");
      String[] payloads={"baseline","WB_CANARY_101","<b>WB_CANARY_101</b>","WB_CONTROL_101"};
      for(int i=0;i<payloads.length;i++){
        byte[] request=("GET /search?q="+java.net.URLEncoder.encode(payloads[i],StandardCharsets.UTF_8)+" HTTP/1.1\r\nHost: simple.example.test\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        String body="<html>\n  <body>\n    <p>"+payloads[i].replace("<","&lt;").replace(">","&gt;")+"</p>\n  </body>\n</html>";
        byte[] wire=("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n"+body).getBytes(StandardCharsets.UTF_8);
        var response=new kr.payloadrunner.Engine.Response(200,wire.length,"text/html",body,false,wire,true);
        var row=new kr.payloadrunner.Engine.Row(i+1,payloads[i],22+i,response,i==0?"기준 응답":"반사 관찰","화면 검증용 합성 데이터 · 브라우저 실행 미확인",request);
        entries.add(constructor.newInstance(row,proxy(burp.IHttpService.class,(p,m,a)->switch(m.getName()){case "getHost"->"simple.example.test";case "getPort"->443;case "getProtocol"->"https";default->def(m);})));model.addRow(new Object[]{row.index(),row.payload(),200,wire.length,row.millis(),row.verdict(),row.evidence()});
      }
      ((JTable)value(runner,"table")).setRowSelectionInterval(2,2);
      var progress=(JProgressBar)value(runner,"progress");progress.setMaximum(4);progress.setValue(4);progress.setString("예시 · 4 / 4");
      ((JLabel)value(runner,"status")).setText("합성 화면 검증 데이터 · 실제 HTTP 전송 0건 · 브라우저 실행 미확인");
    } catch(Exception e){throw new IllegalStateException("Synthetic payload fixture",e);}
  }

  static void layout(Component component) {
    if (component instanceof Container parent) {
      // Detached headless trees never become valid. Explicitly invalidate cached
      // BoxLayout requirements before simulating a real window validation pass.
      parent.invalidate();
      parent.doLayout();
      for (Component child : parent.getComponents()) layout(child);
    }
  }
  static void headers(Component component) {
    if (component instanceof JTable table) {
      var scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
      if (scroll != null) scroll.setColumnHeaderView(table.getTableHeader());
    }
    if (component instanceof Container parent) for (Component child : parent.getComponents()) headers(child);
  }
  static void capture(Container root, String name, int w, int h) { capture(root,name,w,h,()->{}); }
  static void capture(Container root, String name, int w, int h, Runnable beforePaint) {
    try {
      headers(root); root.setSize(w, h); root.setVisible(true);
      for (int pass = 0; pass < 6; pass++) {
        layout(root);
        var loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        SwingUtilities.invokeLater(loop::exit); loop.enter();
      }
      beforePaint.run();
      var picture = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
      var graphics = picture.createGraphics(); root.paint(graphics); graphics.dispose();
      ImageIO.write(picture, "png", out.resolve(name).toFile());
      inspect(root, root, name, w, h);
    } catch (Exception error) { throw new IllegalStateException("Capture failed: " + name, error); }
  }
  static void capture(Container root, String name) { capture(root, name, width, height); }
  static String csv(String value) { return "\"" + Objects.toString(value, "").replace("\"", "\"\"") + "\""; }
  static void awaitUi(java.util.function.BooleanSupplier ready, String reason) {
    if (ready.getAsBoolean()) return;
    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    var loop=Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
    var timer=new javax.swing.Timer(10,event->{if(ready.getAsBoolean()||System.nanoTime()>=deadline)loop.exit();});
    timer.start(); try{loop.enter();}finally{timer.stop();}
    if(!ready.getAsBoolean())throw new IllegalStateException("Preview fixture timeout: "+reason);
  }
  static void scenario(String name, boolean passed) {
    scenarios.add(csv(name) + "," + passed);
    if (!passed) throw new AssertionError(name);
  }
  static void dashboardBounds(Container root) {
    for (String card : List.of("paths", "api", "environment", "secrets", "access", "coverage"))
      dashboardArea(root, "card." + card, 110, 76);
    var distribution = (AbstractButton) SimpleWorkflowTest.named(root, "wb.flow.dashboard.distribution.toggle");
    scenario("Dashboard keeps optional charts in an explicit disclosure", distribution != null && !distribution.isSelected());
    scenario("Dashboard importance filter stays directly available", SimpleWorkflowTest.named(root, "wb.scope.dashboard.importance") != null);
    for (String bar : List.of("paths", "api", "environment", "secrets", "access", "coverage", "observations"))
      scenario("Optional dashboard chart retains its original controller: " + bar, SimpleWorkflowTest.named(root, "wb.scope.dashboard.bar." + bar) != null);
    for (String severity : List.of("high", "medium", "low", "info"))
      scenario("Optional importance chart retains its original controller: " + severity, SimpleWorkflowTest.named(root, "wb.scope.dashboard.severity." + severity) != null);
  }
  static void dashboardResults(Container root) {
    var scroll = (JScrollPane) SimpleWorkflowTest.named(root, "wb.scope.dashboard.scroll");
    scenario("Dashboard result area remains reachable in its vertical scroll pane", scroll != null);
    JViewport viewport = scroll.getViewport();
    viewport.setViewPosition(new Point(0, Math.max(0,
        viewport.getView().getHeight() - viewport.getExtentSize().height)));
  }
  static void dashboardRevealed(Container root, boolean empty) {
    Component list = SimpleWorkflowTest.named(root, empty ? "wb.scope.dashboard.empty" : "wb.scope.dashboard.results");
    Component detail = SimpleWorkflowTest.named(root, "wb.scope.dashboard.detail");
    Rectangle listBounds = SwingUtilities.convertRectangle(list.getParent(), list.getBounds(), root);
    Rectangle detailBounds = SwingUtilities.convertRectangle(detail.getParent(), detail.getBounds(), root);
    Rectangle listShown = visible(root, list, listBounds, root.getWidth(), root.getHeight());
    Rectangle detailShown = visible(root, detail, detailBounds, root.getWidth(), root.getHeight());
    scenario("Explicit dashboard selection reveals its " + (empty ? "empty recovery message" : "result list")
            + " and detail without fixture scrolling",
        listShown.width >= 150 && listShown.height >= (empty ? 40 : 100)
            && detailShown.width >= 120 && detailShown.height >= 80);
  }
  static void dashboardArea(Container root, String part, int minimumWidth, int minimumHeight) {
    String name = "wb.scope.dashboard." + part;
    Component component = SimpleWorkflowTest.named(root, name);
    scenario("Dashboard named visual exists: " + part, component != null);
    Rectangle bounds = SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), root);
    Rectangle shown = visible(root, component, bounds, root.getWidth(), root.getHeight());
    scenario("Dashboard visual keeps readable visible bounds: " + part,
        component.isVisible() && shown.width >= minimumWidth && shown.height >= minimumHeight
            && shown.contains(bounds));
    if (component instanceof ScopeDashboardPanel.MetricButton card) {
      Font captionFont = WorkbenchUi.font().deriveFont(Font.PLAIN, WorkbenchUi.displaySize(12));
      Font valueFont = WorkbenchUi.font().deriveFont(Font.BOLD, WorkbenchUi.displaySize(28));
      int padding = WorkbenchUi.displayPixels(24);
      scenario("Dashboard metric caption and number retain their painted width: " + part,
          card.getFontMetrics(captionFont).stringWidth(card.caption) <= card.getWidth() - padding
              && card.getFontMetrics(valueFont).stringWidth(Integer.toString(card.count)) <= card.getWidth() - padding);
    } else if (component instanceof ScopeDashboardPanel.BarButton bar) {
      Font font = WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(11));
      int labelWidth = Math.min(bar.getWidth() / 2,
          Math.max(108, bar.getFontMetrics(font).stringWidth(bar.caption) + 10));
      scenario("Dashboard chart retains separate caption, bar and count areas: " + part,
          bar.getFontMetrics(font).stringWidth(bar.caption) + 7 <= labelWidth
              && bar.getWidth() - labelWidth - 48 >= 20
              && bar.getFontMetrics(font).stringWidth(Integer.toString(bar.count)) <= 30);
    }
  }
  static Rectangle visible(Container root, Component component, Rectangle bounds, int w, int h) {
    var result = new Rectangle(bounds);
    for (var parent = component.getParent(); parent != null && parent != root; parent = parent.getParent())
      result = result.intersection(SwingUtilities.convertRectangle(parent, new Rectangle(0, 0, parent.getWidth(), parent.getHeight()), root));
    return result.intersection(new Rectangle(0, 0, w, h));
  }
  static void inspect(Container root, Component component, String view, int w, int h) {
    if (!component.isVisible()) return;
    if (component.getName() != null && !component.getName().isBlank()) {
      Rectangle bounds = component == root ? new Rectangle(0,0,w,h)
          : SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), root);
      Rectangle shown = component == root ? bounds : visible(root, component, bounds, w, h);
      anchors.add(String.join(",", csv(theme), ""+w, ""+h, csv(view), csv(component.getName()),
          ""+bounds.x, ""+bounds.y, ""+bounds.width, ""+bounds.height,
          ""+(bounds.width>0&&bounds.height>0&&shown.contains(bounds))));
      Rectangle selection = null;
      if (component instanceof JTable table && table.getSelectedRow() >= 0 && table.getColumnCount() > 0)
        selection = table.getCellRect(table.getSelectedRow(), 0, true)
            .union(table.getCellRect(table.getSelectedRow(), table.getColumnCount()-1, true));
      if (component instanceof JTree tree && tree.getMinSelectionRow() >= 0)
        selection = tree.getRowBounds(tree.getMinSelectionRow());
      if (selection != null) {
        Rectangle selected = SwingUtilities.convertRectangle(component, selection, root);
        Rectangle selectedShown = visible(root, component, selected, w, h);
        anchors.add(String.join(",", csv(theme), ""+w, ""+h, csv(view), csv(component.getName()+".selected"),
            ""+selected.x, ""+selected.y, ""+selected.width, ""+selected.height,
            ""+(selected.width>0&&selected.height>0&&selectedShown.contains(selected))));
        // An off-screen table row can be wider than its viewport. Keep the full-row
        // diagnostic above and separately expose its visible part for guide markers.
        anchors.add(String.join(",", csv(theme), ""+w, ""+h, csv(view), csv(component.getName()+".selected.visible"),
            ""+selectedShown.x, ""+selectedShown.y, ""+selectedShown.width, ""+selectedShown.height,
            ""+(selectedShown.width>0&&selectedShown.height>0)));
      }
    }
    boolean control = component instanceof AbstractButton button && button.getText()!=null && !button.getText().isBlank()
        || component instanceof JTextField || component instanceof JComboBox<?>;
    if (control) {
      var bounds = SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), root);
      var clipping = visible(root, component, bounds, w, h);
      boolean scrollable = false;
      for (var parent = component.getParent(); parent != null && parent != root; parent = parent.getParent()) if (parent instanceof JViewport) scrollable = true;
      String label = component instanceof AbstractButton b ? b.getText() : component.getName();
      boolean fullyVisible = bounds.width > 0 && bounds.height > 0 && clipping.contains(bounds);
      controls.add(String.join(",", csv(theme), ""+w, ""+h, csv(view), csv(label), csv(component.getName()),
          ""+bounds.width, ""+bounds.height, ""+fullyVisible, ""+scrollable));
    }
    if (component instanceof JTable || component instanceof javax.swing.text.JTextComponent) {
      var bounds = SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), root);
      var clipping = visible(root, component, bounds, w, h);
      content.add(String.join(",", csv(theme), ""+w, ""+h, csv(view), csv(component.getName()), ""+Math.max(0,clipping.width), ""+Math.max(0,clipping.height)));
    }
    if (component instanceof Container parent) for (Component child : parent.getComponents()) inspect(root, child, view, w, h);
  }
  static void click(Component root, String name) { SimpleWorkflowTest.click(root, name); }
  static Object value(Object owner, String name) { return SimpleWorkflowTest.unchecked(owner, name); }
  static void invoke(Object owner, String name) {
    try { var method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(owner); }
    catch (Exception error) { throw new IllegalStateException(error); }
  }

  public static void main(String[] args) throws Exception {
    out = Path.of(args[0]); theme = args[2]; width = Integer.parseInt(args[3]); height = Integer.parseInt(args[4]);
    Files.createDirectories(out);
    if (theme.equals("dark")) {
      for (String key : List.of("Panel.background", "TabbedPane.background", "Button.background", "CheckBox.background", "ComboBox.background", "control")) UIManager.put(key, new Color(30,33,39));
      UIManager.put("TabbedPane.selected", new Color(69,58,48));
      for (String key : List.of("TabbedPane.selectHighlight", "TabbedPane.highlight", "TabbedPane.shadow", "TabbedPane.darkShadow", "controlHighlight", "controlLtHighlight", "controlShadow", "controlDkShadow")) UIManager.put(key, new Color(68,75,86));
      UIManager.put("TabbedPane.contentAreaColor", new Color(30,33,39));
      for (String key : List.of("Label.foreground", "Button.foreground", "CheckBox.foreground", "ComboBox.foreground", "TabbedPane.foreground", "textText")) UIManager.put(key, new Color(228,233,241));
    }
    Font font = Font.createFont(Font.TRUETYPE_FONT, Path.of(args[1]).toFile());
    GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
    var fontKeys = UIManager.getDefaults().keys();
    while (fontKeys.hasMoreElements()) {
      Object key = fontKeys.nextElement();
      if (UIManager.get(key) instanceof FontUIResource) UIManager.put(key, new FontUIResource(font.deriveFont(14f)));
    }
    ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p,m,a) -> def(m));
    MontoyaApi api = SimpleWorkflowTest.previewApi();
    var entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks) PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(() -> entry.initialize(api)); edt(() -> {});
    var extension = (WorkbenchExtension) get(entry,"workbench");
    var root = (JPanel) get(extension,"root");
    var auth = (AuthComparePanel) get(extension,"authCompare");
    var evidence = (BountySupportPanel) get(extension,"bountySupport");
    var table = (JTable) get(extension,"logTable");
    final String origin = "https://simple.example.test";
    try {
      edt(() -> {
        scenario("All eight retained primary tools are visible in the classic folders",List.of("traffic","mapper","auth","js","environment","response.recon","payload","bounty").stream()
            .allMatch(id -> SimpleWorkflowTest.toolVisible(root,id)));
        var scope = (WorkbenchScope) value(extension,"localScope"); scope.add(origin+"/", true); invoke(extension,"scopeChanged");
        var dialog = new AtomicReference<Component>();
        extension.dialogOpener = (owner,title,body,modal,closed) -> { dialog.set(body); return null; };
        ((JButton) value(extension,"scopeListButton")).doClick(0);
        scenario("Scope editor opens exact production body", dialog.get() != null);
        var body = WorkbenchUi.settingsBody(dialog.get(), () -> {});
        capture(body,"00-scope-editor.png", Math.min(width,900), Math.min(height,600));
        click(root,"wb.simple.dashboard.open");
        var dashboard = (ScopeDashboardPanel) value(extension,"scopeDashboard");
        scenario("Main dashboard opens the production appended page without starting inspection",
            dashboard != null && ((JTabbedPane)value(extension,"tabs")).getSelectedComponent() == dashboard
                && value(extension,"scopeInspectionPanel") == null);
        scenario("Empty dashboard has no manufactured result rows", dashboard.table.getRowCount() == 0);
        capture(root,"26-dashboard-empty.png",width,height,()->dashboardBounds(root));
        click(root,"wb.simple.tool.traffic"); capture(root,"00-start.png");
        ((JButton) value(extension,"captureButton")).doClick(0);
      });
      seedTraffic(extension,request(origin+"/assets/app.js","GET",true,List.of(header("Accept","application/javascript"))),
          response(200,"fetch('/api/documents/101');\nfetch('/api/users/me');"));
      seedTraffic(extension,request(origin+"/api/documents/101","GET",true,List.of(header("Cookie","SID=ALICE_DEMO_SECRET"), header("Accept","application/json"))),
          response(200,"{\"id\":101,\"title\":\"권한 점검용 문서\",\"ownerId\":\"alice\"}"));
      seedTraffic(extension,request(origin+"/api/users/me","GET",true,List.of(header("Cookie","SID=BOB_DEMO_SECRET"))),
          response(200,"{\"id\":\"bob\",\"role\":\"일반 사용자\",\"diagnostic\":\"java.lang.Exception\"}"));
      for(String path:new String[]{"/api/projects","/api/documents/102","/api/admin/users","/api/auth/login","/api/projects/12/notes"}){
        int status=path.contains("admin")?403:path.contains("102")?404:200;
        seedTraffic(extension,request(origin+path,"GET",true,List.of(header("Accept","application/json"))),response(status,status==200?"{\"items\":[]}":"{\"error\":\"unavailable\"}"));
      }
      var recon = (ResponseReconPanel) get(extension,"responseRecon");
      var analyzed = new java.util.concurrent.CountDownLatch(1);
      recon.submit(() -> { recon.refresh(recon.generation.get()); analyzed.countDown(); });
      if (!analyzed.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic response analysis did not finish");
      edt(() -> {});
      edt(() -> {
        var browser=(TrafficBrowser)value(extension,"trafficBrowser");
        browser.refresh();
        awaitUi(()->browser.positionReady(browser.capturePosition()),"published traffic search snapshot");
        scenario("Traffic feedback and visible originals share a settled snapshot",browser.positionReady(browser.capturePosition())&&table.getRowCount()==8);
        table.setRowSelectionInterval(1,1);capture(root,"01-capture.png");
        table.setRowSelectionInterval(1,1); capture(root,"02-request.png");
        click(root,"wb.simple.tool.mapper"); click(root,"wb.simple.tool.response.recon");
        scenario("Production response analysis displays a synthetic observation", recon.findingTable.getRowCount() > 0);
        recon.findingTable.setRowSelectionInterval(0,0); capture(root,"03-analysis.png");
        click(root,"wb.simple.tool.js"); table.setRowSelectionInterval(0,0); invoke(extension,"selectedJs");
        var js=(JsAnalysisPanel)value(extension,"jsAnalysis");
        awaitUi(()->{var current=(JsAnalysis.Result)value(js,"current");return current!=null&&!current.endpoints().isEmpty();},"selected original JS analysis");
        scenario("JS analysis retains the selected HTTP source",js.sourceUrl.getText().startsWith(origin+"/assets/app.js")&&js.input.getText().contains("fetch("));
        if(js.urlTable.getRowCount()>0)js.urlTable.setRowSelectionInterval(0,0);
        capture(root,"10-js.png");scenario("Approved JS reader retains the selected source with a separate detail disclosure",SimpleWorkflowTest.named(root,"wb.js.inline.detail")!=null&&js.input.getText().contains("fetch("));table.setRowSelectionInterval(1,1);
        click(root,"wb.simple.tool.mapper");
        var mapper=(kr.featuremapper.FeatureMapperWorkspace)value(extension,"featureMapper");
        mapper.observeCaptured(System.currentTimeMillis(),"합성 UI 검증",request(origin+"/api/documents/101","GET",true,List.of()),response(200,"{\"id\":101,\"title\":\"문서 예시\"}"));invoke(mapper,"refresh");
        var featureTree=(JTree)value(mapper,"tree");
        var featureNodes=((javax.swing.tree.DefaultMutableTreeNode)featureTree.getModel().getRoot()).depthFirstEnumeration();
        while(featureNodes.hasMoreElements()){
          var node=(javax.swing.tree.DefaultMutableTreeNode)featureNodes.nextElement();
          if(node.getUserObject()!=null&&node.getUserObject().getClass().getSimpleName().equals("FeatureNode")){
            featureTree.setSelectionPath(new javax.swing.tree.TreePath(node.getPath()));break;
          }
        }
        scenario("Mapper guide selects an actual feature before presenting local edit/link actions",
            ((AbstractButton)SimpleWorkflowTest.named(root,"fm.feature.edit")).isEnabled());
        JTable mapperTable=(JTable)value(mapper,"table");if(mapperTable.getRowCount()>0)mapperTable.setRowSelectionInterval(0,0);
        capture(root,"08-mapper.png");
        click(root,"wb.simple.tool.environment"); capture(root,"09-environment.png");
        extension.openFeature("exposure");
        var research=(ExposureResearchPanel)value(extension,"exposureResearch");
        research.target.setText("simple.example.test");
        research.importUrls(origin+"/swagger/index.html\n"+origin+"/api-docs\n"+origin+"/openapi.json");
        research.category.setSelectedItem("공개 문서");research.importUrls(origin+"/documents/guide.pdf\n"+origin+"/public/manual.pdf");
        research.category.setSelectedItem("관리 화면");research.importUrls(origin+"/admin/login");
        research.category.setSelectedItem("API 문서");research.exposureTable.setRowSelectionInterval(0,0);
        research.status.setText("화면 검증용 합성 URL · 대상 HTTP 전송 0건 · 실제 검색 결과 아님");
        capture(root,"12-exposure-search.png");
        var technology=new ExposureResearch.Product("nginx","nginx","1.22.1","Server: nginx/1.22.1",origin+"/api/documents/101","SYNTHETIC-UI:101");
        research.observed.addItem(technology);research.fillProduct(technology);
        var fixture=SimpleExposureResearchTest.fixture();
        var examples=new ArrayList<ExposureResearch.Candidate>();
        for(int i=1;i<=4;i++)examples.addAll(ExposureResearch.parseNvd(fixture.replace("CVE-2099-10001","CVE-2099-1000"+i).replace("9.8",i==1?"9.8":i==2?"8.1":"7.5")));
        research.display(new ExposureResearch.Batch(examples,Set.of("CVE-2099-10001"),Map.of("CVE-2099-10001",.234,"CVE-2099-10002",.042),java.time.Instant.parse("2026-10-30T12:00:00Z"),"최신 조회 완료","최신 조회 완료",false),technology,false);
        research.tabs.setSelectedIndex(1);research.cveTable.setRowSelectionInterval(0,0);
        research.status.setText("화면 검증용 가상 CVE · 실제 취약점/조회 결과 아님 · 외부 API 호출 0건");
        capture(root,"13-exposure-cve.png");
        research.cveDetailTabs.setSelectedIndex(1);capture(root,"13-exposure-cve-evidence.png");
        scenario("CVE evidence tab retains exact synthetic original metadata without a provider request",research.evidence.getText().contains("SYNTHETIC-UI:101"));
        research.cveDetailTabs.setSelectedIndex(0);
        scenario("Research has two tabs and separate observed/affected/fixed details",research.tabs.getTabCount()==2&&research.evidence.getText().contains("SYNTHETIC-UI:101")&&research.fixedDetail.getText().contains("공지 확인"));

        click(root,"wb.simple.tool.auth"); click(root,"wb.simple.tool.payload"); payloadFixture(value(entry,"payloadRunner"));capture(root,"07-payload.png");
        var phases=(JTabbedPane)SimpleWorkflowTest.named(root,"wb.payload.steps");
        phases.setSelectedIndex(1);capture(root,"07-payload-results.png");
        scenario("Payload results phase opens without starting inspection",!(boolean)value(value(entry,"payloadRunner"),"running"));
        phases.setSelectedIndex(0);
        click(root,"wb.simple.tool.auth");
        auth.setDetection(true);
        var alice = request(origin+"/api/documents/101","GET",true,List.of(header("Cookie","SID=ALICE_DEMO_SECRET")));
        var bob = request(origin+"/api/documents/101","GET",true,List.of(header("Cookie","SID=BOB_DEMO_SECRET")));
        auth.observe(alice,response(200,"{\"id\":101}")); auth.observe(bob,response(403,"접근 권한 없음"));
        var credentials = new ArrayList<>(auth.credentials.values());
        scenario("Synthetic account candidates are retained", credentials.size() >= 2);
        auth.mapCandidate(credentials.get(0),new AuthAccount("Alice","alice","101","문서 소유자","alice@example.test"));
        auth.mapCandidate(credentials.get(1),new AuthAccount("Bob","bob","102","다른 사용자","bob@example.test"));
        auth.assign(0,credentials.get(0)); auth.assign(1,credentials.get(1)); auth.anonymous(3);
        JPanel accountChoicesBody=WorkbenchUi.settingsBody(auth.preparationAccountChoices(),()->{});
        for(Component child:((Container)((BorderLayout)accountChoicesBody.getLayout()).getLayoutComponent(BorderLayout.SOUTH)).getComponents())
          if(child instanceof JButton close)close.setName("wb.auth.preparation.close");
        capture(accountChoicesBody,"04-auth-accounts.png",Math.min(width,1000),300);
        var baseline = response(200,"{\"id\":101,\"title\":\"권한 점검용 문서\",\"ownerId\":\"alice\"}");
        var run = new AuthComparePanel.Run(1,alice,baseline,auth.context());
        run.baselineState=AuthComparePanel.Run.Baseline.VALID;
        run.baselineNote="가이드용 합성 원본 · 네트워크 비교를 실행하지 않았습니다.";
        for (int index : new int[]{0,1,3}) {
          run.packets[index].request = index == 0 ? alice : index == 1 ? bob : request(origin+"/api/documents/101","GET",true,List.of());
          run.packets[index].response = response(index==0?200:403,index==0?baseline.bodyToString():"{\"error\":\"접근 권한 없음\"}");
          run.packets[index].note = "가이드용 합성 응답 · 실제 판정 예시";
        }
        run.note="예시 · 완료"; auth.runs.add(run);
        auth.historyModel.addRow(new Object[]{1,alice.url(),"","","","예시 · 완료"});
        auth.refresh(run); auth.history.setRowSelectionInterval(0,0); auth.show(run);
        auth.inlinePreview.account.setSelectedIndex(1);
        capture(root,"04-auth-results.png");
        click(root,"wb.simple.tool.bounty"); click(root,"wb.simple.bounty.replay");
        SimpleWorkflowTest.clickText(evidence,"선택 요청 추가");
        scenario("Manual replay prepares the selected request without running",evidence.pages.getSelectedIndex()==1&&evidence.workflow.getText().contains("GET /api/documents/101")&&!evidence.running&&evidence.flowModel.getRowCount()==0);
        evidence.workflow.setName("wb.simple.replay.request");
        evidence.workflowDetail.setName("wb.simple.replay.result");
        capture(root,"11-replay.png");
        click(root,"wb.simple.bounty.evidence"); evidence.captureAuth();
        evidence.evidenceTitle.setText("문서 접근 권한 비교 · 작성 예시");
        evidence.impact.setText("다른 사용자와 비로그인 응답을 확인하고 접근 정책과 대조했습니다.\n이 가이드 화면의 패킷과 결과는 합성 데이터입니다.");
        evidence.repro.setText("1. 점검 대상 URL을 SCOPE에 추가합니다.\n2. 원본 요청을 선택하고 계정 1/2와 비로그인을 연결합니다.\n3. 비교 결과와 응답 원문을 확인합니다.\n4. 가림 미리보기를 확인한 뒤 ZIP을 저장합니다.");
        evidence.impact.setCaretPosition(0);evidence.repro.setCaretPosition(0);
        evidence.impact.setName("wb.simple.evidence.impact"); evidence.repro.setName("wb.simple.evidence.repro");
        if(evidence.evidenceTable.getRowCount()>0)evidence.evidenceTable.setRowSelectionInterval(0,0);
        scenario("Original and comparison packets are captured together", evidence.evidenceCount() >= 3);
        capture(root,"05-evidence.png");
        extension.openFeature("data");
        var codecInput=(JTextArea)value(extension,"codecIn");var codecOutput=(JTextArea)value(extension,"codecOut");
        codecInput.setText("{\n  \"target\": \"simple.example.test\",\n  \"purpose\": \"도구별 화면 확인용 합성 입력\"\n}");
        var codecChoice=(JComboBox<?>)SimpleWorkflowTest.visibleComponents(root).stream().filter(c->c instanceof JComboBox<?> combo&&combo.getItemCount()>0&&"URL 인코딩 (form)".equals(combo.getItemAt(0))).findFirst().orElseThrow();
        codecChoice.setSelectedItem("Base64 인코딩");SimpleWorkflowTest.clickText(((JTabbedPane)value(extension,"dataTabs")).getSelectedComponent(),"변환");
        scenario("Data tool capture uses the real local conversion action",!codecOutput.getText().isBlank());
        codecInput.setCaretPosition(0);codecOutput.setCaretPosition(0);capture(root,"14-data.png");
        extension.openFeature("activity");
        var progressPanel=(ActivityPanel)value(extension,"activity");
        progressPanel.tracker.observe(request(origin+"/api/documents/101?view=summary","GET",true,List.of()),"화면 검증용 합성 요청");
        progressPanel.tracker.observe(request(origin+"/api/projects","POST",true,List.of()),"화면 검증용 합성 요청");
        progressPanel.refresh();if(progressPanel.table.getRowCount()>0)progressPanel.table.setRowSelectionInterval(0,0);
        scenario("Progress capture derives rows from the real tracker using synthetic requests",progressPanel.table.getRowCount()>=2);capture(root,"15-progress.png");
        extension.openFeature("dashboard");
        var dashboard=(ScopeDashboardPanel)value(extension,"scopeDashboard");
        var exampleOriginal=((DiskTrafficStore)value(extension,"trafficStore")).ref(1);
        var exampleFinding=new ScopeInspection.Finding("SECRET","설정 정보 후보 · 합성 예시",exampleOriginal.url(),
            "GET","Alice","형식 후보","높음","유효성 미검증","화면 검증용 합성 근거",exampleOriginal.id(),1,0,"button-layout-guide");
        var examplePage=new ScopeInspection.Page(exampleOriginal.url(),"GET","Alice",200,"JSON",true,"합성 예시","guide",exampleOriginal);
        dashboard.update(new ScopeInspection.Snapshot("화면 검증용 합성 결과",ScopeInspection.State.COMPLETED,
            "합성 예시 · 실제 점검 실행 아님",1,1,0,0,0,0,List.of(exampleFinding),List.of(examplePage),List.of(),0,0),10,origin);
        awaitUi(()->!dashboard.aggregating&&!dashboard.visibleRows.isEmpty(),"synthetic dashboard projection");
        dashboard.table.setRowSelectionInterval(0,0);
        scenario("Dashboard guide actions retain a real synthetic stored original",dashboard.original.isEnabled()&&dashboard.repeater.isEnabled()&&dashboard.evidence.isEnabled());
        capture(root,"16-dashboard.png");

        String redacted = evidence.evidencePreview();
        scenario("Production preview excludes synthetic session values", !redacted.contains("ALICE_DEMO_SECRET")&&!redacted.contains("BOB_DEMO_SECRET"));
        var preview=evidence.evidencePreviewContent(()->{});
        var previewText=(JTextArea)SimpleWorkflowTest.named(preview,"wb.simple.evidence.preview.text");
        int masked=previewText.getText().indexOf("Cookie: [REDACTED]");
        scenario("Production redaction preview contains a masked Cookie example",masked>=0);
        capture(preview,"06-redaction.png",Math.min(width,1100),Math.min(height,800),()->{
          try {
            var anchor=previewText.modelToView2D(masked).getBounds();
            var viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,previewText);
            viewport.setViewPosition(new Point(0,Math.max(0,anchor.y-100)));
          }catch(Exception error){throw new IllegalStateException(error);}
        });
      });
      scenario("Guide navigation sends zero HTTP",PayloadIntegrationTest.sent.get()==0);
    } finally { SimpleWorkflowTest.unload(); }
    anchors.add(0,"theme,width,height,view,name,x,y,anchorWidth,anchorHeight,fullyVisible");
    Files.write(out.resolve("anchors.csv"),anchors,StandardCharsets.UTF_8);
    controls.add(0,"theme,width,height,view,label,name,controlWidth,controlHeight,fullyVisible,scrollable");
    content.add(0,"theme,width,height,view,name,visibleWidth,visibleHeight");
    scenarios.add(0,"scenario,passed");
    Files.write(out.resolve("controls.csv"),controls,StandardCharsets.UTF_8);
    Files.write(out.resolve("content.csv"),content,StandardCharsets.UTF_8);
    Files.write(out.resolve("scenarios.csv"),scenarios,StandardCharsets.UTF_8);
    System.out.println("Captured production Simple Swing components: "+theme+" "+width+"x"+height+"; synthetic packets, zero HTTP, not actual Burp GUI.");
    System.exit(0);
  }
}
