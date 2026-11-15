package kr.workbench;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** A fresh, explicit user decision before each planned automated target-request run.
 * This review grants no session permission and never sends or schedules requests.
 * Callers must freeze their request plan before review and reject changed state afterwards.
 */
public final class ActiveRequestApproval {
  public static final String RULE_VERSION = "active-request-approval-v1";
  static final int MAX_OPERATION = 120, MAX_PLAN = 4000, MAX_TARGETS = 512;
  static final int MAX_URL_INPUT = 8192, MAX_URL_DISPLAY = 2048, MAX_TARGET_TEXT = 65536;
  static final List<String> OPTIONS = List.of("확인 후 실행", "취소");
  private static final Pattern PACKET = Pattern.compile(
      "(?im)(?:^|\\n)\\s*(?:(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE|CONNECT)\\s+\\S+\\s+HTTP/\\d|HTTP/\\d(?:\\.\\d)?\\s+\\d{3})");
  private static final Pattern CREDENTIAL = Pattern.compile(
      "(?i)(?:\\b(?:authorization|proxy-authorization|cookie|set-cookie|password|passwd|secret|token|access[_-]?token|refresh[_-]?token|api[_-]?key|session(?:id)?|csrf|xsrf|jwt)[\\\"']?\\s*[:=]\\s*\\S+|\\bBearer\\s+\\S+|\\b(?:sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[A-Z0-9]{16}|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,})\\b)");
  // Test-only injection seam. Production has no preference, saved grant or bypass switch.
  static volatile BiPredicate<Component, Plan> prompt = ActiveRequestApproval::defaultPrompt;

  private ActiveRequestApproval() {}

  public record Plan(String operation, List<String> targets, String requestPlan) {
    public Plan {
      operation = summary(operation, MAX_OPERATION, false, "작업 이름");
      requestPlan = summary(requestPlan, MAX_PLAN, true, "요청 계획");
      if (targets == null || targets.isEmpty() || targets.size() > MAX_TARGETS)
        throw invalid("대상 수");
      List<String> safe = new ArrayList<>(targets.size());
      int characters = 0;
      for (String target : targets) {
        String url = safeUrl(target);
        characters += url.length();
        if (characters > MAX_TARGET_TEXT) throw invalid("대상 목록 크기");
        safe.add(url);
      }
      targets = List.copyOf(safe);
    }
  }

  public static boolean confirm(Component owner, String operation, Collection<String> targets, String requestPlan) {
    if (targets == null || targets.isEmpty() || targets.size() > MAX_TARGETS) throw invalid("대상 수");
    Plan plan = new Plan(operation, new ArrayList<>(targets), requestPlan);
    if (Thread.currentThread().isInterrupted()) return false;
    BiPredicate<Component, Plan> reviewer = prompt;
    if (reviewer == null) return false;
    if (SwingUtilities.isEventDispatchThread()) return decide(reviewer, owner, plan);
    AtomicBoolean allowed = new AtomicBoolean(), cancelled = new AtomicBoolean();
    try {
      SwingUtilities.invokeAndWait(() -> {
        if (!cancelled.get()) allowed.set(decide(reviewer, owner, plan));
      });
      return !cancelled.get() && !Thread.currentThread().isInterrupted() && allowed.get();
    } catch (InterruptedException interrupted) {
      cancelled.set(true); Thread.currentThread().interrupt(); return false;
    } catch (InvocationTargetException failed) {
      return false;
    }
  }

  public static void require(Component owner, String operation, Collection<String> targets, String requestPlan) {
    if (!confirm(owner, operation, targets, requestPlan))
      throw new CancellationException("사용자가 자동 요청 실행을 승인하지 않았습니다.");
  }

  private static boolean decide(BiPredicate<Component, Plan> reviewer, Component owner, Plan plan) {
    try { return reviewer.test(owner, plan); }
    catch (RuntimeException failed) { return false; }
  }

  static boolean defaultPrompt(Component owner, Plan plan) {
    // A headless process cannot obtain a user's decision; never infer approval.
    if (GraphicsEnvironment.isHeadless()) return false;
    JOptionPane pane = optionPane(plan);
    JDialog dialog = pane.createDialog(owner, "자동 요청 실행 전 확인");
    try {
      dialog.setModal(true); dialog.setVisible(true);
      return approvedSelection(pane.getValue());
    } finally { dialog.dispose(); }
  }

  static JOptionPane optionPane(Plan plan) {
    JTextArea text = new JTextArea(reviewText(plan), 19, 72);
    text.setName("wb.active.requests.approval");
    text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true); text.setCaretPosition(0);
    JScrollPane content = new JScrollPane(text); WorkbenchUi.style(content);
    return new JOptionPane(content, JOptionPane.WARNING_MESSAGE, JOptionPane.DEFAULT_OPTION,
        null, OPTIONS.toArray(), OPTIONS.get(1));
  }

  static boolean approvedSelection(Object selection) { return OPTIONS.get(0).equals(selection); }

  static String reviewText(Plan plan) {
    return "가용성 주의 · 자동 요청이 서비스 지연, 부하 또는 상태 변경을 일으킬 수 있습니다.\n"
        + "작업: " + plan.operation() + "\n\n"
        + "대상 " + plan.targets().size() + "개 · 인증정보·쿼리·프래그먼트 제외\n"
        + String.join("\n", plan.targets()) + "\n\n요청 수·예상 범위·간격\n" + plan.requestPlan()
        + "\n\n이번 실행만 승인합니다. 다시 실행하거나 재개하면 다시 확인합니다.\n"
        + "취소하거나 창을 닫으면 이번 실행의 요청을 시작하지 않습니다.\n"
        + "실행 후 중지는 이후 요청을 막지만 이미 전송한 요청이나 서버의 변경을 되돌리지 못합니다.\n"
        + "실행 확인 규칙: " + RULE_VERSION;
  }

  private static String summary(String value, int limit, boolean multiline, String field) {
    if (value == null || value.isBlank() || value.length() > limit) throw invalid(field);
    for (int i = 0; i < value.length();) {
      int code = value.codePointAt(i); i += Character.charCount(code);
      if ((Character.isISOControl(code) && !(multiline && (code == '\n' || code == '\t')))
          || Character.getType(code) == Character.FORMAT || Character.getType(code) == Character.SURROGATE)
        throw invalid(field);
    }
    // Plans contain only execution metadata, never raw requests, headers or credential values.
    if (PACKET.matcher(value).find() || CREDENTIAL.matcher(value).find()) throw invalid(field);
    return value.strip();
  }

  private static String safeUrl(String value) {
    if (value == null || value.isBlank() || value.length() > MAX_URL_INPUT
        || value.codePoints().anyMatch(code -> Character.isISOControl(code) || Character.getType(code) == Character.FORMAT))
      throw invalid("대상 URL");
    try {
      URI uri = URI.create(value.strip());
      String scheme = uri.getScheme(), host = uri.getHost();
      if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
          || uri.isOpaque() || host == null || host.isBlank() || uri.getPort() == 0 || uri.getPort() > 65535)
        throw invalid("대상 URL");
      String path = uri.getRawPath();
      if (path == null || path.isEmpty()) path = "/";
      String safeHost = host.toLowerCase(Locale.ROOT);
      if (safeHost.indexOf(':') >= 0 && !safeHost.startsWith("[")) safeHost = "[" + safeHost + "]";
      String safe = URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + safeHost
          + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + path).toASCIIString();
      if (safe.length() > MAX_URL_DISPLAY) throw invalid("대상 URL 길이");
      return safe;
    } catch (IllegalArgumentException malformed) { throw invalid("대상 URL"); }
  }

  private static IllegalArgumentException invalid(String field) {
    return new IllegalArgumentException("자동 요청 실행 계획을 확인하세요: " + field);
  }
}
