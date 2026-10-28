package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.Dimension;
import java.awt.Insets;

/** Passive, bounded form checks. Messages never contain input values and checks never send HTTP. */
final class AuthFieldValidation {
  private AuthFieldValidation() {}

  static String httpUrl(String value) {
    if (value == null || value.isBlank()) return "GET URL을 입력하세요. 예: https://example.test/api/me";
    try {
      URI uri = URI.create(value.strip());
      if (value.length() > 2048 || !Set.of("http", "https").contains(uri.getScheme())
          || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
          || uri.getPort() < -1 || uri.getPort() > 65535) throw new IllegalArgumentException();
      return "";
    } catch (RuntimeException error) { return "HTTP(S) URL 형식을 확인하세요. 예: https://example.test/api/me"; }
  }

  static String scopedUrl(String value, String origin, MontoyaApi api) {
    String problem = httpUrl(value); if (!problem.isEmpty()) return problem;
    if (origin != null && !origin.isBlank()) {
      try {
        if (!AuthEffectRules.canonicalOrigin(value.strip()).equals(AuthEffectRules.canonicalOrigin(origin)))
          return "계정·대상과 같은 Origin의 URL을 입력하세요.";
      } catch (RuntimeException error) { return "대상 Origin을 먼저 연결하세요."; }
    }
    if (api != null) {
      try {
        HttpRequest request = HttpRequest.httpRequestFromUrl(value.strip());
        if (!WorkbenchScope.contains(api, request)) return "등록된 SCOPE 안의 URL을 입력하세요.";
      } catch (RuntimeException error) { return "URL의 SCOPE 포함 여부를 확인할 수 없습니다."; }
    }
    return "";
  }

  static String jsonPath(String value, boolean required, int limit) {
    if (value == null || value.isBlank()) return required ? "JSON 경로를 입력하세요. 예: $.data.ownerId" : "";
    if (value.length() > limit || !value.strip().startsWith("$."))
      return "$.로 시작하는 JSON 경로를 입력하세요. 예: $.data.ownerId";
    return "";
  }

  static String check(Runnable validation, String message) {
    try { validation.run(); return ""; }
    catch (RuntimeException error) { return message; }
  }

  static String policyRows(String text, boolean matrix) {
    String example = matrix ? "예: GET /api/items ALLOW DENY UNKNOWN UNKNOWN"
        : "예: GET /api/items/{id} OWNER_ONLY";
    if (text.length() > 8192) return "권한 규칙은 8 KiB 이하로 입력하세요.";
    String[] rows = text.split("\\R", -1);
    int count = 0;
    for (int line = 0; line < rows.length; line++) {
      if (rows[line].isBlank()) continue;
      if (++count > 32) return "권한 규칙은 최대 32개입니다.";
      try {
        if (matrix) BountyMatrix.parse(rows[line]);
        else AuthPolicy.parse(rows[line], AuthPolicy.Access.UNSPECIFIED);
      } catch (RuntimeException error) { return (line + 1) + "행의 메서드·경로·권한을 확인하세요. " + example; }
    }
    return check(() -> { if (matrix) BountyMatrix.parse(text); else AuthPolicy.parse(text, AuthPolicy.Access.UNSPECIFIED); },
        "권한 규칙 형식을 확인하세요. " + example);
  }

  static String responseRules(String text) {
    if (text.length() > 8192) return "응답 차단 규칙은 8 KiB 이하로 입력하세요.";
    String[] rows = text.split("\\R", -1);
    for (int line = 0; line < rows.length; line++) {
      if (rows[line].isBlank()) continue;
      try { AuthResponseRules.parse(rows[line]); }
      catch (RuntimeException error) { return (line + 1) + "행 형식·정규식을 확인하세요. 예: BODY_REGEX: (?i)denied"; }
    }
    return check(() -> AuthResponseRules.parse(text), "응답 차단 규칙 형식·개수를 확인하세요. 예: BODY: denied");
  }

  /** Wraps important reasons to the actual window width, including the minimum-size settings view. */
  static final class MessageLabel extends JLabel {
    private String message = "";
    private int renderedWidth = -1;
    MessageLabel() {
      super(" ");
      addComponentListener(new java.awt.event.ComponentAdapter() {
        @Override public void componentResized(java.awt.event.ComponentEvent event) {
          int before = renderedWidth; render(); if (before != renderedWidth) revalidate();
        }
      });
    }
    void message(String value) { message = value; renderedWidth = -1; render(); }
    private void render() {
      int width = 440;
      if (getParent() != null && getParent().getWidth() > 0) {
        Insets insets = getParent().getInsets();
        width = Math.max(160, Math.min(440, getParent().getWidth() - insets.left - insets.right - 12));
      }
      if (renderedWidth == width) return;
      renderedWidth = width;
      super.setText(message == null || message.isEmpty() ? " " : "<html>"
          + message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</html>");
      // Swing CSS pixels are scaled; size the actual HTML view in component pixels instead.
      Object html = getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey);
      if (html instanceof javax.swing.text.View view) view.setSize(width, 0);
    }
    @Override public Dimension getPreferredSize() { render(); return super.getPreferredSize(); }
    @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
  }

  private static void message(JLabel label, String value) {
    if (label instanceof MessageLabel wrapping) wrapping.message(value); else label.setText(value.isEmpty() ? " " : value);
  }

  /** One listener per watched document. Reopened windows replace bindings instead of retaining buttons. */
  static final class Group {
    record Entry(JTextComponent field, JLabel message, Supplier<String> validation) {}
    record ButtonBinding(AbstractButton button, JLabel reason, BooleanSupplier allowed, String blocked) {}
    final List<Entry> entries = new ArrayList<>();
    final LinkedHashMap<String, ButtonBinding> buttons = new LinkedHashMap<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private boolean refreshing;

    JLabel watch(JTextComponent field, String name, Supplier<String> validation) {
      JLabel message = new MessageLabel(); message.setName(name); message.setFocusable(false);
      message.putClientProperty("wb.font.size", 11);
      Entry entry = new Entry(field, message, validation); entries.add(entry);
      field.getDocument().addDocumentListener(new DocumentListener() {
        public void insertUpdate(DocumentEvent event) { refresh(); }
        public void removeUpdate(DocumentEvent event) { refresh(); }
        public void changedUpdate(DocumentEvent event) { refresh(); }
      });
      return message;
    }

    JLabel issue(String name, Supplier<String> validation) {
      JLabel message = new MessageLabel(); message.setName(name); message.setFocusable(false);
      entries.add(new Entry(null, message, validation)); return message;
    }

    JLabel message(JTextComponent field) {
      return entries.stream().filter(entry -> entry.field() == field).findFirst().orElseThrow().message();
    }

    void bind(String key, AbstractButton button, JLabel reason, BooleanSupplier allowed, String blocked) {
      buttons.put(key, new ButtonBinding(button, reason, allowed, blocked)); refresh();
    }

    String firstProblem() {
      for (Entry entry : entries) {
        String problem = evaluate(entry.validation()); if (!problem.isEmpty()) return problem;
      }
      return "";
    }

    private static String evaluate(Supplier<String> validation) {
      try { return Objects.requireNonNullElse(validation.get(), ""); }
      catch (RuntimeException error) { return "입력 조건을 다시 확인하세요."; }
    }

    void refresh() {
      if (!SwingUtilities.isEventDispatchThread()) {
        if (scheduled.compareAndSet(false, true)) SwingUtilities.invokeLater(() -> { scheduled.set(false); refresh(); });
        return;
      }
      if (refreshing) return;
      refreshing = true;
      try {
        String first = "";
        for (Entry entry : entries) {
          String problem = evaluate(entry.validation());
          AuthFieldValidation.message(entry.message(), problem);
          entry.message().setForeground(WorkbenchUi.toneInk(problem.isEmpty() ? WorkbenchUi.Tone.NEUTRAL : WorkbenchUi.Tone.WARNING));
          if (entry.field() != null) entry.field().getAccessibleContext().setAccessibleDescription(problem.isEmpty() ? "입력 형식 확인 완료" : problem);
          if (first.isEmpty() && !problem.isEmpty()) first = problem;
        }
        for (ButtonBinding binding : buttons.values()) {
          boolean allowed;
          try { allowed = binding.allowed().getAsBoolean(); } catch (RuntimeException error) { allowed = false; }
          String reason = !first.isEmpty() ? first : allowed ? "" : binding.blocked();
          binding.button().setEnabled(reason.isEmpty());
          binding.button().setToolTipText(reason.isEmpty() ? "입력 형식 확인 완료 · 직접 눌러 적용" : reason);
          AuthFieldValidation.message(binding.reason(), reason.isEmpty() ? "입력 형식 확인 완료 · 직접 눌러 적용하세요." : "적용 전 확인: " + reason);
        }
      } finally { refreshing = false; }
    }
  }
}
