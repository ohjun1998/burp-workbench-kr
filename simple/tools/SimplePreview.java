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
  static final List<String> content = new ArrayList<>();
  static final List<String> scenarios = new ArrayList<>();

  static void layout(Component component) {
    if (component instanceof Container parent) {
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
  static Rectangle visible(Container root, Component component, Rectangle bounds, int w, int h) {
    var result = new Rectangle(bounds);
    for (var parent = component.getParent(); parent != null && parent != root; parent = parent.getParent())
      result = result.intersection(SwingUtilities.convertRectangle(parent, new Rectangle(0, 0, parent.getWidth(), parent.getHeight()), root));
    return result.intersection(new Rectangle(0, 0, w, h));
  }
  static void inspect(Container root, Component component, String view, int w, int h) {
    if (!component.isVisible()) return;
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
        var scope = (WorkbenchScope) value(extension,"localScope"); scope.add(origin+"/", true); invoke(extension,"scopeChanged");
        var dialog = new AtomicReference<Component>();
        extension.dialogOpener = (owner,title,body,modal,closed) -> { dialog.set(body); return null; };
        ((JButton) value(extension,"scopeListButton")).doClick(0);
        scenario("Scope editor opens exact production body", dialog.get() != null);
        var body = WorkbenchUi.settingsBody(dialog.get(), () -> {});
        capture(body,"00-scope-editor.png", Math.min(width,900), Math.min(height,600));
        click(root,"wb.simple.step.1"); capture(root,"00-start.png");
        ((JButton) value(extension,"captureButton")).doClick(0);
      });
      seedTraffic(extension,request(origin+"/assets/app.js","GET",true,List.of(header("Accept","application/javascript"))),
          response(200,"fetch('/api/documents/101');\nfetch('/api/users/me');"));
      seedTraffic(extension,request(origin+"/api/documents/101","GET",true,List.of(header("Cookie","SID=ALICE_DEMO_SECRET"), header("Accept","application/json"))),
          response(200,"{\"id\":101,\"title\":\"권한 점검용 문서\",\"ownerId\":\"alice\"}"));
      seedTraffic(extension,request(origin+"/api/users/me","GET",true,List.of(header("Cookie","SID=BOB_DEMO_SECRET"))),
          response(200,"{\"id\":\"bob\",\"role\":\"일반 사용자\",\"diagnostic\":\"java.lang.Exception\"}"));
      var recon = (ResponseReconPanel) get(extension,"responseRecon");
      var analyzed = new java.util.concurrent.CountDownLatch(1);
      recon.submit(() -> { recon.refresh(recon.generation.get()); analyzed.countDown(); });
      if (!analyzed.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic response analysis did not finish");
      edt(() -> {});
      edt(() -> {
        var browser=(TrafficBrowser)value(extension,"trafficBrowser");
        browser.refresh();
        awaitUi(()->browser.positionReady(browser.capturePosition()),"published traffic search snapshot");
        scenario("Traffic feedback and visible originals share a settled snapshot",browser.positionReady(browser.capturePosition())&&table.getRowCount()==3);
        capture(root,"01-capture.png");
        table.setRowSelectionInterval(1,1); capture(root,"02-request.png");
        click(root,"wb.simple.step.2"); click(root,"wb.simple.tool.response.recon");
        scenario("Production response analysis displays a synthetic observation", recon.findingTable.getRowCount() > 0);
        recon.findingTable.setRowSelectionInterval(0,0); capture(root,"03-analysis.png");
        click(root,"wb.simple.tool.mapper"); capture(root,"08-mapper.png");
        click(root,"wb.simple.tool.environment"); capture(root,"09-environment.png");
        click(root,"wb.simple.step.3"); click(root,"wb.simple.tool.payload"); capture(root,"07-payload.png");
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
        capture(WorkbenchUi.settingsBody(auth.preparationAccountChoices(),()->{}),"04-auth-accounts.png",Math.min(width,1000),300);
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
        capture(root,"04-auth-results.png");
        click(root,"wb.simple.step.4");
        evidence.captureAuth();
        evidence.evidenceTitle.setText("문서 접근 권한 비교 · 작성 예시");
        evidence.impact.setText("다른 사용자와 비로그인 응답을 확인하고 접근 정책과 대조했습니다.\n이 가이드 화면의 패킷과 결과는 합성 데이터입니다.");
        evidence.repro.setText("1. 점검 대상 URL을 SCOPE에 추가합니다.\n2. 원본 요청을 선택하고 계정 1/2와 비로그인을 연결합니다.\n3. 비교 결과와 응답 원문을 확인합니다.\n4. 가림 미리보기를 확인한 뒤 ZIP을 저장합니다.");
        evidence.impact.setName("wb.simple.evidence.impact"); evidence.repro.setName("wb.simple.evidence.repro");
        if(evidence.evidenceTable.getRowCount()>0)evidence.evidenceTable.setRowSelectionInterval(0,0);
        scenario("Original and comparison packets are captured together", evidence.evidenceCount() >= 3);
        capture(root,"05-evidence.png");
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
