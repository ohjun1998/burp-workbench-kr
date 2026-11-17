package kr.workbench;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Native Xorg/Robot capture of the shipped panel with a visibly synthetic session adapter. */
public final class StrixChatNative extends WorkbenchTest {
  static final String EXAMPLE = "합성 UI 예시 · 실제 Strix·모델·대상 호출 없음";
  static final class Session implements SimpleStrixPanel.Runner {
    volatile boolean active;
    final CountDownLatch release = new CountDownLatch(1);
    final List<SimpleStrixChatClient.Message> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<SimpleStrixChatClient.Agent> agents = List.of(
        new SimpleStrixChatClient.Agent("root", "Strix 진단 에이전트", null, "진단 중"),
        new SimpleStrixChatClient.Agent("access", "계정 권한 검증", "root", "검토 중"));
    Session() {
      messages.add(new SimpleStrixChatClient.Message("m1", "root", "assistant", "[합성 UI 예시]\n계좌 조회 응답에서 권한 확인이 필요한 후보를 관찰했습니다. 현재 자료만으로 IDOR로 확정할 수는 없습니다.", "2026-11-17T10:00:00Z"));
      messages.add(new SimpleStrixChatClient.Message("m2", "root", "user", "이 결과의 근거와 오탐 가능성을 설명해줘.", "2026-11-17T10:00:10Z"));
      messages.add(new SimpleStrixChatClient.Message("m3", "root", "assistant", "[합성 응답]\n관찰: 계좌 식별자를 포함한 조회 경로가 있습니다.\n아직 미확인: 다른 계정의 계좌를 실제로 조회할 수 있는지는 검증하지 않았습니다.\n다음 판단 근거: 소유자와 비소유자 요청의 응답·정책을 비교해야 합니다. 공유 계좌, 공개 데이터, 캐시 응답 여부도 확인할 항목입니다.\n이 예시는 화면 확인용이며 실제 요청·취약점 검증 결과가 아닙니다.", "2026-11-17T10:00:11Z"));
      messages.add(new SimpleStrixChatClient.Message("m4", "access", "assistant", "[합성 UI 예시]\n계정 A/B의 소유권 정책을 검토 중입니다. 인증값은 자동 전달되지 않습니다. 실제 추가 검증은 전송 전 확인한 범위에서 진행해야 합니다.", "2026-11-17T10:00:12Z"));
    }
    public SimpleStrixClient.ProviderInspection inspectProvider(SimpleStrixClient.Provider provider, String model) {
      return new SimpleStrixClient.ProviderInspection(provider, true, new SimpleStrixClient.ModelInfo("synthetic-ui-fixture", "", "fixture"), EXAMPLE);
    }
    public SimpleStrixClient.Plan plan(String binary, String target, String mode, int turns, int seconds,
        String context, SimpleStrixClient.Provider provider, String model) throws IOException {
      return new SimpleStrixClient.Plan(Path.of(binary), URI.create(target), mode, turns, seconds, context, provider, "synthetic-ui-fixture");
    }
    public SimpleStrixClient.Result execute(SimpleStrixClient.Plan plan, BooleanSupplier approval,
        Consumer<SimpleStrixClient.Snapshot> listener) throws IOException { return executeInteractive(plan, approval, listener); }
    public SimpleStrixClient.Result executeInteractive(SimpleStrixClient.Plan plan, BooleanSupplier approval,
        Consumer<SimpleStrixClient.Snapshot> listener) throws IOException {
      if (!approval.getAsBoolean()) return new SimpleStrixClient.Result("cancelled", -1, null, List.of(), "", 0, false);
      active = true;
      try {
        listener.accept(new SimpleStrixClient.Snapshot(EXAMPLE, "[합성 예시] 실제 CLI·모델·대상 호출 0회", 100));
        if (!release.await(80, TimeUnit.SECONDS)) throw new IOException("Synthetic capture timed out");
        return new SimpleStrixClient.Result("cancelled", 0, null, List.of(), EXAMPLE, 100, false);
      } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Capture stopped"); }
      finally { active = false; }
    }
    public SimpleStrixChatClient.Snapshot chatSnapshot() {
      return new SimpleStrixChatClient.Snapshot(41, active, "합성 연결 상태 · 실제 Strix 세션 아님", "synthetic-ui-session", active ? agents : List.of(), active ? List.copyOf(messages) : List.of());
    }
    public void sendChat(long generation, String agentId, String message) { throw new AssertionError("Capture must cancel the actual message confirmation; no dispatch permitted"); }
    public boolean busy() { return active; }
    public void cancel() { release.countDown(); }
    public void close() { cancel(); }
  }
  static Path out;
  static Robot robot;
  static final List<Map<String,Object>> views = new ArrayList<>();
  static final List<String> violations = new ArrayList<>();
  static void waitUi(BooleanSupplier predicate, String name) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
    while (System.nanoTime() < end) { boolean[] ready = {false}; edt(() -> ready[0] = predicate.getAsBoolean()); if (ready[0]) return; Thread.sleep(30); }
    throw new AssertionError("Native capture wait: " + name);
  }
  static void measure(Component c, JComponent root, List<Map<String,Object>> rows, String file) {
    if (!c.isShowing()) return;
    String name = c.getName();
    if (name != null && name.startsWith("wb.simple.strix.") && (c instanceof AbstractButton || c instanceof JComboBox<?> || c instanceof JTextField)) {
      Rectangle b = SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), root);
      boolean clipped = b.width <= 0 || b.height <= 0 || b.x < 0 || b.y < 0 || b.x+b.width > root.getWidth() || b.y+b.height > root.getHeight();
      if (c instanceof AbstractButton button && b.width+1 < button.getPreferredSize().width) clipped = true;
      rows.add(Map.of("name", name, "kind", "control", "x", b.x, "y", b.y, "width", b.width, "height", b.height, "clipped", clipped));
      if (clipped) violations.add(file + ": " + name + " " + b);
    }
    if (c instanceof JTextArea area && name != null) {
      Container parent = c.getParent();
      while (parent != null && !(parent instanceof JViewport)) parent = parent.getParent();
      if (parent instanceof JViewport viewport) {
        Rectangle b = SwingUtilities.convertRectangle(viewport.getParent(), viewport.getBounds(), root);
        int minimum = name.endsWith("chat.history") ? 80 : name.endsWith("chat.draft") ? 50 : 45;
        boolean clipped = b.height < minimum || b.width < 120;
        rows.add(Map.of("name", name, "kind", "content-viewport", "x", b.x, "y", b.y, "width", b.width, "height", b.height, "minimumHeight", minimum, "clipped", clipped));
        if (clipped) violations.add(file + ": " + name + " viewport " + b + " minimumHeight=" + minimum);
      }
    }
    if (c instanceof Container parent) for (Component child : parent.getComponents()) measure(child, root, rows, file);
  }
  static void capture(Window window, JComponent root, String file, String title) throws Exception {
    edt(() -> { window.toFront(); window.requestFocus(); }); Toolkit.getDefaultToolkit().sync(); Thread.sleep(350);
    Rectangle[] bounds = {null}; List<Map<String,Object>> controls = new ArrayList<>();
    edt(() -> { if (!window.isShowing()) throw new AssertionError("Window not visible"); bounds[0] = window.getBounds(); measure(root, root, controls, file); });
    ImageIO.write(robot.createScreenCapture(bounds[0]), "png", out.resolve(file).toFile());
    views.add(Map.of("png", file, "title", title, "width", bounds[0].width, "height", bounds[0].height, "controls", controls));
  }
  public static void main(String[] args) throws Exception {
    if (GraphicsEnvironment.isHeadless()) throw new AssertionError("Native display required");
    out = Path.of(args[0]); Files.createDirectories(out); String theme = args[1]; int w = Integer.parseInt(args[2]), h = Integer.parseInt(args[3]);
    Path jar = Path.of(args[4]).toRealPath(); Path loaded = Path.of(SimpleStrixPanel.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
    if (!jar.equals(loaded) || !loaded.toString().endsWith(".jar")) throw new AssertionError("Must load exact requested release JAR");
    Strix1421Native.theme(theme);
    Font font = WorkbenchUi.font(); GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
    var keys = UIManager.getDefaults().keys(); while (keys.hasMoreElements()) { Object key = keys.nextElement(); if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource) UIManager.put(key, new javax.swing.plaf.FontUIResource(font.deriveFont(14f))); }
    Session session = new Session(); SimpleStrixPanel[] panel = {null}; JDialog[] dialog = {null}; robot = new Robot();
    try {
      edt(() -> {
        panel[0] = new SimpleStrixPanel(session, u -> u.startsWith("https://demo-bank.example.test/"), () -> false);
        panel[0].prepare(List.of(new SimpleStrixPanel.Source("synthetic-original-001", "GET", "https://demo-bank.example.test/api/accounts/123", "HTTP 200 · JSON · 화면 확인용 합성 원본 메타데이터")), "synthetic-capture-project", () -> {});
        panel[0].context.setText("합성 UI 예시입니다. 계정 A와 B는 각자 소유한 계좌만 조회할 수 있어야 합니다. 실제 인증값·본문은 전달하지 않습니다.");
        panel[0].sourceInfo.setText(EXAMPLE + " · 원본 1개");
        panel[0].approval = (owner, plan) -> true;
        dialog[0] = new JDialog((Frame)null, "Workbench Strix 1.1.0 · 진단 대화 · 합성 UI 예시", false);
        dialog[0].setContentPane(panel[0]); WorkbenchUi.style(dialog[0].getContentPane());
        dialog[0].setBounds(80, 50, w, h); dialog[0].setVisible(true);
      });
      Strix1421Native.settleBounds(dialog[0],80,50,w,h);
      capture(dialog[0], panel[0], "01-chat-ready.png", "진단 대화 준비 · 연결 전");
      edt(() -> panel[0].start()); waitUi(() -> panel[0].sendChat.isEnabled() && panel[0].chatHistory.getText().contains("오탐"), "synthetic session connected");
      edt(() -> panel[0].chatDraft.setText("현재 계좌 조회 결과가 IDOR인지 판단하려면 어떤 근거가 더 필요해?"));
      capture(dialog[0], panel[0], "02-chat-conversation.png", "같은 진단 세션 대화 · 합성 대화 예시");
      edt(() -> panel[0].chatAgent.setSelectedIndex(1));
      capture(dialog[0], panel[0], "03-chat-subagent.png", "하위 에이전트 대화 선택 · 합성 예시");
      edt(() -> panel[0].chatAgent.setSelectedIndex(0));
      SwingUtilities.invokeLater(() -> panel[0].sendChat.doClick(0));
      // Native modal JOptionPane opens a nested EDT loop, allowing Robot capture from this thread.
      waitUi(() -> Arrays.stream(Window.getWindows()).anyMatch(x -> x instanceof JDialog d && d.isShowing() && d.getTitle().equals("Strix 질문 전송 전 확인")), "native send confirmation");
      JDialog confirmation = null; for (Window window : Window.getWindows()) if (window instanceof JDialog d && d.isShowing() && d.getTitle().equals("Strix 질문 전송 전 확인")) confirmation = d;
      if (confirmation == null) throw new AssertionError("Confirmation missing");
      capture(confirmation, (JComponent) confirmation.getContentPane(), "04-chat-confirm.png", "질문 전송 전 확인 · 취소가 기본 선택");
      robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE); robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
      waitUi(() -> !Arrays.stream(Window.getWindows()).anyMatch(x -> x instanceof JDialog d && d.isShowing() && d.getTitle().equals("Strix 질문 전송 전 확인")), "confirmation dismissed");
      Map<String,Object> report = new LinkedHashMap<>(); report.put("releaseJarSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)))); report.put("version", BuildInfo.VERSION); report.put("theme", theme); report.put("nativeSwing", true); report.put("nativeBurpValidation", false); report.put("syntheticConversation", true); report.put("modelCalls", 0); report.put("targetRequests", 0); report.put("strixExecutions", 0); report.put("capture", "Robot.createScreenCapture on live Xorg/xfwm4 windows"); report.put("views", views); report.put("layoutViolations", violations);
      Files.writeString(out.resolve("chat-native-report.json"), MetadataExport.json(report) + "\n");
      System.out.println("CHAT_NATIVE " + theme + " " + w + "x" + h + " views=" + views.size() + " violations=" + violations.size());
      if (!violations.isEmpty()) throw new AssertionError(violations.toString());
    } finally { edt(() -> { if (panel[0] != null) panel[0].close(); for (Window window : Window.getWindows()) window.dispose(); }); }
  }
}
