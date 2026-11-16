package kr.workbench;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Actual release-JAR panel and approval body; synthetic runner only, no Strix/model/target. */
public final class StrixPanelPreview extends WorkbenchTest {
  private static final List<Map<String, Object>> views = new ArrayList<>();
  private static final List<String> violations = new ArrayList<>();
  private static Path out;
  private static int width, height;

  static void layout(Component component) {
    if (component instanceof Container parent) {
      parent.invalidate(); // Detached headless BoxLayout trees need a real validation pass.
      parent.doLayout();
      for (Component child : parent.getComponents()) layout(child);
    }
  }
  private static boolean visible(Component component, Component root) {
    for (Component c = component; c != null; c = c.getParent()) {
      if (!c.isVisible()) return false;
      if (c == root) return true;
    }
    return false;
  }
  private static void measure(Component component, Component root, String file, List<Map<String, Object>> controls) {
    String name = component.getName();
    if (name != null && name.startsWith("wb.simple.strix.") && visible(component, root)
        && (component instanceof AbstractButton || component instanceof JTextField
            || component instanceof JComboBox<?> || component instanceof JSpinner)) {
      Rectangle bounds = SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), root);
      boolean clipped = bounds.width <= 0 || bounds.height <= 0 || bounds.x < 0 || bounds.y < 0
          || bounds.x + bounds.width > root.getWidth() || bounds.y + bounds.height > root.getHeight();
      controls.add(Map.of("name", name, "x", bounds.x, "y", bounds.y, "width", bounds.width,
          "height", bounds.height, "clipped", clipped));
      if (clipped) violations.add(file + ": " + name + " " + bounds);
    }
    if (name != null && component instanceof JTextArea && visible(component, root)) {
      Container current = component.getParent();
      while (current != null && !(current instanceof JViewport)) current = current.getParent();
      if (current instanceof JViewport viewport) {
        Rectangle bounds = SwingUtilities.convertRectangle(viewport.getParent(), viewport.getBounds(), root);
        boolean collapsed = bounds.height < 50 || bounds.width < 120;
        controls.add(Map.of("name", name, "kind", "content-viewport", "x", bounds.x, "y", bounds.y,
            "width", bounds.width, "height", bounds.height, "clipped", collapsed));
        if (collapsed) violations.add(file + ": content viewport below 50px: " + name + " " + bounds);
      }
    }
    if (component instanceof Container parent) for (Component child : parent.getComponents()) measure(child, root, file, controls);
  }
  private static void capture(Component body, String file, String title) throws Exception {
    AtomicReference<JPanel> reference = new AtomicReference<>();
    edt(() -> {
      JPanel wrapper = new JPanel(new BorderLayout(0, 8));
      if (body instanceof SimpleStrixPanel panel) {
        // Use an existing data label so the annotation does not steal product
        // viewport height from the exact size being measured.
        panel.sourceInfo.setText("합성 예시 · 원본 1개 · 실제 CLI·모델·대상 호출 0회 · 인증값·본문 자동 전달 없음");
      } else {
        JLabel caption = new JLabel("합성 예시 · 실제 Strix / 모델 / 대상 호출 0회 · " + title);
        caption.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
        caption.putClientProperty("html.disable", true); wrapper.add(caption, BorderLayout.NORTH);
      }
      wrapper.add(body);
      WorkbenchUi.style(wrapper); wrapper.setSize(width, height);
      for (int pass = 0; pass < 8; pass++) layout(wrapper);
      reference.set(wrapper);
    });
    edt(() -> {});
    edt(() -> {
      JPanel wrapper = reference.get(); for (int pass = 0; pass < 8; pass++) layout(wrapper);
      List<Map<String, Object>> controls = new ArrayList<>(); measure(wrapper, wrapper, file, controls);
      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      Graphics2D graphics = image.createGraphics(); wrapper.printAll(graphics); graphics.dispose();
      try { ImageIO.write(image, "png", out.resolve(file).toFile()); }
      catch (java.io.IOException error) { throw new IllegalStateException(error); }
      views.add(Map.of("png", file, "title", title, "controls", controls,
          "syntheticFixture", true, "targetRequests", 0, "modelCalls", 0));
    });
  }
  private static void selectTab(Component component, String title) {
    if (component instanceof JTabbedPane tabs) for (int i = 0; i < tabs.getTabCount(); i++)
      if (tabs.getTitleAt(i).equals(title)) tabs.setSelectedIndex(i);
    if (component instanceof Container parent) for (Component child : parent.getComponents()) selectTab(child, title);
  }
  private static SimpleStrixPanel prepared(SimpleStrixIntegrationTest.FakeRunner runner) throws Exception {
    var panel = SimpleStrixIntegrationTest.panel(runner, new AtomicBoolean(true), new AtomicBoolean());
    edt(() -> {
      panel.prepare(List.of(new SimpleStrixPanel.Source("synthetic-original-001", "GET",
          "https://allowed.test/orders/123", "HTTP 200 · JSON · 합성 관찰 메타데이터")), "synthetic-project", () -> {});
      panel.context.setText("합성 예시: 계정 A/B는 서로 다른 일반 사용자입니다. 각자 소유한 주문만 조회할 수 있어야 합니다.\n"
          + "실제 인증값·본문은 전달하지 않습니다. 관찰과 가설을 구분하고, 가용성 영향 검사는 하지 마세요.");
      selectTab(panel, "진단 입력 미리보기");
    }); return panel;
  }
  private static String sha(Path path) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }
  public static void main(String[] args) throws Exception {
    if (args.length < 5 || args.length > 6) throw new IllegalArgumentException("OUT THEME WIDTH HEIGHT EXPECTED_RELEASE_JAR [check]");
    if (!GraphicsEnvironment.isHeadless()) throw new IllegalArgumentException("Preview requires -Djava.awt.headless=true");
    out = Path.of(args[0]); Files.createDirectories(out); width = Integer.parseInt(args[2]); height = Integer.parseInt(args[3]);
    Path expectedJar = Path.of(args[4]).toRealPath();
    Path loaded = Path.of(SimpleStrixPanel.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
    if (!loaded.equals(expectedJar) || !loaded.getFileName().toString().endsWith(".jar"))
      throw new IllegalStateException("Production panel must load from the exact requested release JAR: " + loaded);
    String jarHash = sha(expectedJar);
    boolean dark = args[1].equals("dark");
    UIManager.put("Panel.background", dark ? new Color(18, 22, 29) : new Color(246, 248, 251));
    UIManager.put("Label.foreground", dark ? new Color(230, 236, 244) : new Color(25, 43, 65));
    Font font = WorkbenchUi.font(); GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
    var fontKeys = UIManager.getDefaults().keys();
    while (fontKeys.hasMoreElements()) {
      Object key = fontKeys.nextElement();
      if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource)
        UIManager.put(key, new javax.swing.plaf.FontUIResource(font.deriveFont(14f)));
    }
    var firstRunner = new SimpleStrixIntegrationTest.FakeRunner();
    SimpleStrixPanel first = prepared(firstRunner);
    try {
      capture(first, "01-prepared.png", "원본 준비와 진단 입력 미리보기");
      edt(() -> first.inspect.doClick(0)); SimpleStrixIntegrationTest.idle(first);
      capture(first, "02-halogen.png", "Halogen 로컬 모델 설정 확인 · 합성 설정");
      edt(() -> { first.provider.setSelectedItem(SimpleStrixClient.Provider.CHATGPT_SUBSCRIPTION); first.inspect.doClick(0); });
      SimpleStrixIntegrationTest.idle(first);
      capture(first, "03-chatgpt-auth.png", "Strix 자체 ChatGPT 인증 · 합성 미로그인 상태");
      var plan = new SimpleStrixClient.Plan(Path.of("/synthetic/.strix/bin/strix"), java.net.URI.create("https://allowed.test/"),
          "quick", 20, 600, "합성 실행계획", SimpleStrixClient.Provider.HALOGEN_LOCAL, "halogen-qwen3.8-flash-next");
      AtomicReference<JPanel> confirmation = new AtomicReference<>();
      edt(() -> confirmation.set(SimpleStrixPanel.approvalContent(plan)));
      capture(confirmation.get(), "04-execution-plan.png", "실행 전 확인 내용 · 실제 승인/실행 없음");
    } finally { edt(first::close); }

    var heldRunner = new SimpleStrixIntegrationTest.FakeRunner(); heldRunner.hold = true;
    SimpleStrixPanel held = prepared(heldRunner);
    try {
      edt(() -> { held.approval = (owner, plan) -> true; held.start(); });
      SimpleStrixIntegrationTest.await(() -> heldRunner.launches.get() == 1 && held.log.getText().contains("합성"), "synthetic running view");
      capture(held, "05-running-log.png", "진행 로그 · 가짜 Runner 상태");
      edt(() -> held.stop("중지 요청 · 합성 예시 · 컨테이너 정리는 미확인")); SimpleStrixIntegrationTest.idle(held);
      capture(held, "06-stopped.png", "중지 요청 후 상태 · 실제 프로세스 없음");
    } finally { edt(held::close); }

    var completeRunner = new SimpleStrixIntegrationTest.FakeRunner(); SimpleStrixPanel complete = prepared(completeRunner);
    try {
      edt(() -> { complete.approval = (owner, plan) -> true; complete.start(); }); SimpleStrixIntegrationTest.idle(complete);
      edt(() -> selectTab(complete, "이번 실행 보고서"));
      capture(complete, "07-reports.png", "실행별 보고서 경로 · 합성 결과, 실제 파일 없음");
    } finally { edt(complete::close); }
    if (!jarHash.equals(sha(expectedJar))) throw new IllegalStateException("Release JAR changed during preview capture");
    Files.writeString(out.resolve("measurements.json"), MetadataExport.json(Map.of(
        "description", "Actual release-JAR Simple Strix panel with synthetic runner data",
        "nativeBurpValidation", false, "releaseJar", expectedJar.toString(), "releaseJarSha256", jarHash,
        "width", width, "height", height, "theme", args[1], "views", views, "violations", violations,
        "limitations", List.of("No real Strix CLI, model, OAuth or target execution", "Approval body only; no live modal confirmation"))), StandardCharsets.UTF_8);
    System.out.println("StrixPanelPreview: " + views.size() + " synthetic views; " + violations.size() + " structural control violations; model/target/process calls 0");
    if (args.length == 6 && args[5].equals("check") && !violations.isEmpty())
      throw new IllegalStateException(String.join("\n", violations));
  }
}
