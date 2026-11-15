package kr.workbench;

import burp.api.montoya.http.message.HttpRequestResponse;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JPanel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import javax.swing.text.JTextComponent;

/** Actual Next production Swing components, with synthetic inputs and injected results only.
 * No analyze, connection, login, Repeater or evidence actions are clicked. The test host
 * never contacts a target. This is offscreen layout QA, not native Burp or model validation.
 * Deliberately avoid addNotify(): detached lightweight peers have no native Window host.
 */
public final class LocalLlmUiPreview extends WorkbenchTest {
  private static final List<Map<String, Object>> views = new ArrayList<>();
  private static final List<Map<String, Object>> controls = new ArrayList<>();
  private static final List<Map<String, Object>> content = new ArrayList<>();
  private static final List<Map<String, Object>> violations = new ArrayList<>();
  private static final List<Throwable> renderingErrors = new CopyOnWriteArrayList<>();
  private static Map<String, Object> approvalState = Map.of();
  private static String theme;
  private static int width, height;

  private interface CheckedAction { void run() throws Exception; }
  private static void onEdt(CheckedAction action) throws Exception {
    AtomicReference<Throwable> error = new AtomicReference<>();
    edt(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
    if (error.get() instanceof Exception failure) throw failure;
    if (error.get() instanceof Error failure) throw failure;
  }

  private static HttpRequestResponse packet(String path, String method, String body) {
    var request = request("https://burp.test/api/" + path, method, true,
        List.of(header("Cookie", "session=SYNTHETIC_PREVIEW_SECRET")));
    var response = response(200, body);
    registerTrafficPacket(request, response);
    return proxy(HttpRequestResponse.class, (p, m, a) -> switch (m.getName()) {
      case "request" -> request;
      case "response" -> response;
      default -> def(m);
    });
  }

  private static void prepareFunctions(LocalLlmPanel panel) {
    var packets = new LinkedHashMap<String, HttpRequestResponse>();
    packets.put("11111111-1111-4111-8111-111111111111", packet("orders/101", "GET", "{\"orderId\":101,\"ownerId\":7,\"state\":\"paid\"}"));
    packets.put("22222222-2222-4222-8222-222222222222", packet("orders/102", "GET", "{\"orderId\":102,\"ownerId\":7,\"state\":\"pending\"}"));
    packets.put("33333333-3333-4333-8333-333333333333", packet("profile", "GET", "{\"userId\":7,\"displayName\":\"Demo\"}"));
    packets.put("44444444-4444-4444-8444-444444444444", packet("uploads", "POST", "{\"fileId\":88,\"status\":\"accepted\"}"));
    var sources = packets.entrySet().stream()
        .map(entry -> LocalLlmTasks.capture(entry.getKey(), entry.getValue(), "합성 계정 A · 일반 사용자"))
        .toList();
    panel.prepare(new LocalLlmTasks.Input(LocalLlmTasks.Task.FUNCTIONS,
        "[합성 예제] 주문 조회, 프로필 조회, 파일 업로드 패킷을 기능별로 묶습니다.\n"
        + "주문은 소유자만 조회할 수 있다는 가정이며 실제 정책은 아직 확인하지 않았습니다.\n"
        + "분류 제안에는 원본 ID를 연결하고 관찰, 미확인 사항, 다음 검증을 구분합니다.", sources), packets);
    var rows = List.of(
        new LocalLlmTasks.Row("주문 상세 조회", "주문 / 읽기", List.copyOf(packets.keySet()).subList(0, 2),
            "경로와 응답 구조가 같은 두 주문 조회를 하나의 기능으로 제안합니다. ownerId와 주문 상태가 관측됩니다.",
            "계정 소유 관계와 서버 인가 정책은 미확인입니다. 정상 응답만으로 인가 취약점을 확정할 수 없습니다.",
            "허용된 테스트 계정의 소유 관계를 확인한 뒤 원본을 Repeater에서 비교합니다."),
        new LocalLlmTasks.Row("내 프로필 조회", "계정 / 읽기", List.of(sources.get(2).id()),
            "프로필 객체와 사용자 식별자를 반환하는 조회 요청입니다.",
            "다른 사용자 객체를 지정할 수 있는지 관측되지 않았습니다.", "기대되는 계정별 응답 정책을 문서화합니다."),
        new LocalLlmTasks.Row("파일 업로드", "파일 / 쓰기", List.of(sources.get(3).id()),
            "업로드 접수 상태와 파일 식별자를 반환합니다.", "저장 위치, 조회 권한, 파일 형식 검증은 미확인입니다.",
            "합성 파일의 업로드와 후속 조회를 연결하여 기능 흐름을 확인합니다."));
    String report = "[합성 예제 · 실제 진단 결과 아님]\n\n주문 조회 기능 관측\n"
        + "근거: " + sources.get(0).id() + ", " + sources.get(1).id() + "\n\n"
        + "두 요청은 주문 식별자만 다른 동일 조회 기능으로 분류할 수 있습니다.\n"
        + "인가 정책과 소유 관계는 아직 확인하지 않았습니다. 확인된 영향은 없습니다.\n\n"
        + "다음 검증: 허용된 두 테스트 계정에서 동일한 객체에 대한 기대 정책과 응답을 비교합니다.\n"
        + "패킷 원본과 재현 조건을 검토한 뒤 보고서에 반영합니다.";
    panel.showResult(new LocalLlmTasks.Result(rows, report),
        new LocalLlmClient.Reply("SYNTHETIC_RESULT", "stop", 1350, 420, 2800, ""), false);
    panel.status.setText("합성 예제 · 실제 모델 실행 없음 · 기능 분류 3개 · 자동 반영 없음");
  }

  private static JTabbedPane tabsFor(Component child) {
    for (Container parent = child.getParent(); parent != null; parent = parent.getParent())
      if (parent instanceof JTabbedPane tabs) return tabs;
    throw new IllegalStateException("Production tab ancestor missing for " + child.getName());
  }

  private static void selectPage(Component child) {
    Component page = child;
    while (!(page.getParent() instanceof JTabbedPane)) {
      page = page.getParent();
      if (page == null) throw new IllegalStateException("Missing page");
    }
    ((JTabbedPane) page.getParent()).setSelectedComponent(page);
  }

  private static Rectangle bounds(Container root, Component component) {
    return component == root ? new Rectangle(0, 0, root.getWidth(), root.getHeight())
        : SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), root);
  }

  private static Rectangle visible(Container root, Component component, Rectangle rect) {
    Rectangle result = new Rectangle(rect);
    for (Container parent = component.getParent(); parent != null && parent != root; parent = parent.getParent())
      result = result.intersection(bounds(root, parent));
    result = result.intersection(new Rectangle(0, 0, root.getWidth(), root.getHeight()));
    return result.width < 0 || result.height < 0 ? new Rectangle() : result;
  }

  private static Map<String, Object> rectangle(Rectangle rect) {
    return Map.of("x", rect.x, "y", rect.y, "width", rect.width, "height", rect.height);
  }

  private static void inspect(Container root, Component component, String view) {
    if (!component.isVisible()) return;
    if (component instanceof AbstractButton button && button.getText() != null && !button.getText().isBlank()) {
      Rectangle rect = bounds(root, component), clipped = visible(root, component, rect);
      boolean scrollable = false;
      for (Container parent = component.getParent(); parent != null && parent != root; parent = parent.getParent())
        if (parent instanceof JViewport) scrollable = true;
      boolean fullyVisible = rect.width > 0 && rect.height > 0 && clipped.contains(rect);
      boolean reachableByScroll = scrollable && (clipped.width == 0 || clipped.height == 0);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("view", view); row.put("label", button.getText()); row.put("enabled", button.isEnabled());
      row.put("name", component.getName() == null ? "" : component.getName());
      row.put("bounds", rectangle(rect)); row.put("visible", rectangle(clipped));
      row.put("fullyVisible", fullyVisible); row.put("scrollable", scrollable);
      row.put("reachableByScroll", reachableByScroll); controls.add(row);
      if (button.isEnabled() && !fullyVisible && !reachableByScroll)
        violations.add(Map.of("view", view, "kind", "clipped-enabled-button", "label", button.getText()));
    }
    if (component instanceof JTextComponent && !(component instanceof javax.swing.JTextField)
        || component instanceof JTable) {
      Component viewport = component.getParent() instanceof JViewport parent ? parent : component;
      Rectangle clipped = visible(root, viewport, bounds(root, viewport));
      String name = component.getName() == null ? component.getClass().getSimpleName() : component.getName();
      content.add(Map.of("view", view, "name", name, "viewport", rectangle(clipped),
          "component", rectangle(visible(root, component, bounds(root, component)))));
    }
    if (component instanceof Container container)
      for (Component child : container.getComponents()) inspect(root, child, view);
  }

  private static void render(Container root, Path out, String name, String... required) throws Exception {
    installTableHeaders(root);
    root.setSize(width, height);
    for (int pass = 0; pass < 6; pass++) {
      UiLayoutTest.layout(root);
      var loop = java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
      SwingUtilities.invokeLater(loop::exit); loop.enter();
    }
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics(); root.paint(graphics); graphics.dispose();
    ImageIO.write(image, "png", out.resolve(name + ".png").toFile());
    inspect(root, root, name);
    for (String field : required) {
      Map<String, Object> measured = content.stream()
          .filter(row -> name.equals(row.get("view")) && field.equals(row.get("name"))).findFirst().orElse(null);
      @SuppressWarnings("unchecked") Map<String, Integer> rect = measured == null ? Map.of() : (Map<String, Integer>) measured.get("viewport");
      if (rect.getOrDefault("height", 0) < 50 || rect.getOrDefault("width", 0) < 100)
        violations.add(Map.of("view", name, "kind", "collapsed-content", "name", field,
            "minimumHeight", 50, "minimumWidth", 100, "measured", rect));
    }
    views.add(Map.of("name", name, "png", name + ".png", "requiredContent", List.of(required)));
  }

  // JTable normally installs its header in addNotify(); detached offscreen panels
  // intentionally skip native peers, so reproduce that one attachment explicitly.
  private static void installTableHeaders(Component component) {
    if (component instanceof JTable table && table.getParent() instanceof JViewport viewport
        && viewport.getParent() instanceof JScrollPane scroll)
      scroll.setColumnHeaderView(table.getTableHeader());
    if (component instanceof Container container)
      for (Component child : container.getComponents()) installTableHeaders(child);
  }

  private static void appearance(Path fontPath) throws Exception {
    if (theme.equals("dark")) {
      for (String key : List.of("Panel.background", "TabbedPane.background", "Button.background", "CheckBox.background", "ComboBox.background", "ScrollBar.background", "control"))
        UIManager.put(key, new java.awt.Color(30, 33, 39));
      UIManager.put("TabbedPane.selected", new java.awt.Color(69, 58, 48));
      for (String key : List.of("TabbedPane.selectHighlight", "TabbedPane.highlight", "TabbedPane.shadow", "TabbedPane.darkShadow", "controlHighlight", "controlLtHighlight", "controlShadow", "controlDkShadow"))
        UIManager.put(key, new java.awt.Color(68, 75, 86));
      UIManager.put("TabbedPane.contentAreaColor", new java.awt.Color(30, 33, 39));
      for (String key : List.of("Label.foreground", "Button.foreground", "CheckBox.foreground", "ComboBox.foreground", "TabbedPane.foreground", "textText"))
        UIManager.put(key, new java.awt.Color(228, 233, 241));
    }
    Font font = Font.createFont(Font.TRUETYPE_FONT, fontPath.toFile());
    GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
    var keys = UIManager.getDefaults().keys();
    while (keys.hasMoreElements()) {
      Object key = keys.nextElement();
      if (UIManager.get(key) instanceof FontUIResource)
        UIManager.put(key, new FontUIResource(font.deriveFont(14f)));
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 5) throw new IllegalArgumentException("out font theme width height required");
    Path out = Path.of(args[0]); theme = args[2]; width = Integer.parseInt(args[3]); height = Integer.parseInt(args[4]);
    Files.createDirectories(out); appearance(Path.of(args[1]));
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { renderingErrors.add(error); error.printStackTrace(); });
    int before = PayloadIntegrationTest.sent.get();
    try (var fixture = new TrafficScopeModesTest.Fixture()) {
      LocalLlmPanel panel = fixture.ext.localLlm;
      BountySupportPanel bounty = (BountySupportPanel) get(fixture.ext, "bountySupport");
      onEdt(() -> {
        bounty.pages.setSelectedComponent(panel);
        panel.detail.setName("preview.ai.detail"); panel.connectionInfo.setName("preview.ai.connection");
        render(panel, out, "empty-input", "wb.local.llm.context", "wb.local.llm.results", "preview.ai.detail");
        prepareFunctions(panel);
        render(panel, out, "function-classifications", "wb.local.llm.context", "wb.local.llm.results", "preview.ai.detail");
        selectPage(panel.preview);
        render(panel, out, "outbound-preview", "wb.local.llm.preview", "wb.local.llm.results", "preview.ai.detail");
        var reportResult = panel.result;
        panel.prepare(new LocalLlmTasks.Input(LocalLlmTasks.Task.REPORT,
            "[합성 예제] 연결된 원본에서 관측된 사실과 아직 확인하지 않은 영향을 구분해 보고서 초안을 작성합니다.",
            panel.input.sources()), panel.originals);
        panel.showResult(reportResult, new LocalLlmClient.Reply("SYNTHETIC_REPORT", "stop", 1350, 420, 2800, ""), false);
        panel.status.setText("합성 보고서 예제 · 실제 모델 실행 없음 · 원본과 영향 검토 필요");
        selectPage(panel.context); selectPage(panel.report);
        render(panel, out, "report-draft", "wb.local.llm.context", "wb.local.llm.report");
        panel.connectionInfo.setText("합성 화면 미리보기\n연결 확인·로그인 확인은 실행하지 않았습니다.\n설정은 실제 Next UI이며 입력과 결과는 합성 데이터입니다.");
        JPanel settings = WorkbenchUi.settingsBody(panel.settingsContent(), () -> {});
        render(settings, out, "settings-local", "preview.ai.connection");
        tabsFor(panel.codexPath).setSelectedIndex(1);
        render(settings, out, "settings-subscription", "preview.ai.connection");
        var approval = ActiveRequestApproval.optionPane(new ActiveRequestApproval.Plan(
            "합성 예제 · 경로 후보 반복 조회",
            List.of("https://burp.test/api/orders", "https://burp.test/api/uploads"),
            "요청 최대 30회 · 동시 요청 1개 · 요청 간격 250ms · 요청별 제한 10초\n"
                + "기준·후속 조회를 포함한 한도입니다. 현재 SCOPE 안에서만 진행합니다.\n"
                + "합성 화면 예제이며 요청을 예약하거나 실행하지 않습니다."));
        approvalState = Map.of("options", List.of(approval.getOptions()),
            "defaultSelection", approval.getInitialValue(),
            "decisionUninitialized", approval.getValue() == JOptionPane.UNINITIALIZED_VALUE,
            "modalShown", false, "promptInvoked", false);
        if (!List.of(approval.getOptions()).equals(List.of("확인 후 실행", "취소"))
            || !"취소".equals(approval.getInitialValue())
            || approval.getValue() != JOptionPane.UNINITIALIZED_VALUE)
          throw new IllegalStateException("Approval preview must start undecided with Cancel selected");
        render(approval, out, "request-approval", "wb.active.requests.approval");
        if (panel.running || panel.active != null) throw new IllegalStateException("Preview unexpectedly started a worker");
      });
    }
    int targetRequests = PayloadIntegrationTest.sent.get() - before;
    if (targetRequests != 0) violations.add(Map.of("kind", "unexpected-target-request", "count", targetRequests));
    for (Throwable error : renderingErrors) violations.add(Map.of("kind", "rendering-error", "message", error.toString()));
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("theme", theme); report.put("width", width); report.put("height", height);
    report.put("nativeBurpValidation", false); report.put("syntheticFixturesOnly", true);
    report.put("targetRequests", targetRequests); report.put("inferenceActionsInvoked", 0);
    report.put("approval", approvalState);
    report.put("pngCount", views.size()); report.put("views", views); report.put("controls", controls);
    report.put("content", content); report.put("violations", violations);
    Files.writeString(out.resolve("measurements.json"), MetadataExport.json(report));
    System.out.println("Rendered " + views.size() + " actual Next AI views (synthetic only), " + violations.size() + " layout violations.");
    if (!renderingErrors.isEmpty() || targetRequests != 0) throw new IllegalStateException("Preview isolation failed");
  }
}
