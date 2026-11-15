package kr.workbench;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.List;
import java.util.function.*;

/** Memory-only drafts. Selection changes never send inference or target requests. */
final class LocalLlmPanel extends JPanel implements AutoCloseable {
  record Context(String project, String text, Runnable validate, Runnable repeater, Consumer<String> evidence) {}
  final JTextField endpoint = new JTextField(LocalLlmClient.DEFAULT_URL, 26);
  final JTextField model = new JTextField(LocalLlmClient.DEFAULT_MODEL, 26);
  final JComboBox<String> purpose = new JComboBox<>(new String[]{"취약점 가설과 검증 우선순위", "계정별 응답 차이와 인가 검증", "관측 근거 기반 버그바운티 보고서 초안"});
  final JSpinner tokens = new JSpinner(new SpinnerNumberModel(2048, 256, 4096, 256));
  final JSpinner timeout = new JSpinner(new SpinnerNumberModel(120, 10, 300, 10));
  final JTextArea preview = new JTextArea(), result = new JTextArea();
  final JButton analyze = new JButton("로컬 LLM 분석"), cancel = new JButton("중지"),
      repeater = new JButton("원본을 Repeater에 준비"), evidence = new JButton("검토 메모와 원본을 증거에 추가");
  final JLabel status = new JLabel("패킷을 선택하고 ‘로컬 LLM 분석 준비’를 누르세요.");
  private final Supplier<String> project;
  interface Analyzer { LocalLlmClient.Answer analyze(LocalLlmClient.Settings settings, String purpose, String input, LocalLlmClient.Call call) throws java.io.IOException; }
  private final Analyzer client;
  private Context context;
  private LocalLlmClient.Call active;
  private long generation;
  private boolean closed;
  private String completed = "";

  LocalLlmPanel(Supplier<String> project) { this(project, new LocalLlmClient()::analyze); }
  LocalLlmPanel(Supplier<String> project, Analyzer client) {
    super(new BorderLayout(8, 8)); this.project = project; this.client = client;
    setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
    JPanel settings = new JPanel(new GridLayout(0, 2, 8, 4));
    settings.add(new JLabel("로컬 OpenAI 호환 주소 (/v1)")); settings.add(endpoint);
    settings.add(new JLabel("모델 ID")); settings.add(model);
    settings.add(new JLabel("분석 목적")); settings.add(purpose);
    JPanel limits = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    limits.add(new JLabel("출력 토큰")); limits.add(tokens); limits.add(new JLabel("제한 초")); limits.add(timeout);
    settings.add(new JLabel("한 번에 1개 분석 · 자동 재시도 없음")); settings.add(limits);
    add(settings, BorderLayout.NORTH);
    for (JTextArea area : List.of(preview, result)) { area.setLineWrap(true); area.setWrapStyleWord(true); }
    ((AbstractDocument) preview.getDocument()).setDocumentFilter(new DocumentFilter() {
      public void insertString(FilterBypass fb, int offset, String text, AttributeSet attrs) throws BadLocationException { replace(fb, offset, 0, text, attrs); }
      public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
        if (fb.getDocument().getLength() - length + (text == null ? 0 : text.length()) <= LocalLlmClient.INPUT_LIMIT)
          super.replace(fb, offset, length, text, attrs);
      }
    });
    result.setEditable(false);
    JTabbedPane text = new JTabbedPane();
    text.addTab("전송 미리보기 · 직접 편집 가능", new JScrollPane(preview));
    text.addTab("분석 결과 · 미검증 초안", new JScrollPane(result));
    add(text, BorderLayout.CENTER);
    JPanel bottom = new JPanel(new BorderLayout(4,4));
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
    for (JButton b : List.of(analyze,cancel,repeater,evidence)) actions.add(b);
    bottom.add(actions,BorderLayout.CENTER); bottom.add(status,BorderLayout.SOUTH);
    bottom.add(new JLabel("가림은 개인정보 전체 탐지가 아닙니다. 전송 미리보기를 확인하세요. 분석은 로컬 모델에만 전송됩니다."),BorderLayout.NORTH);
    add(bottom,BorderLayout.SOUTH);
    analyze.addActionListener(e -> { start(); if (active != null) text.setSelectedIndex(1); });
    cancel.addActionListener(e -> { generation++; if (active != null) active.cancel(); status.setText("중지 요청 · 서버의 생성 종료 시점은 서버 구현에 따라 다릅니다."); });
    repeater.addActionListener(e -> action(() -> { checked(); context.repeater().run(); status.setText("Repeater 준비 완료 · 실제 Send는 직접 실행하세요."); }));
    evidence.addActionListener(e -> action(() -> { checked(); if(completed.isBlank())throw new IllegalArgumentException("완료된 분석이 없습니다.");
      context.evidence().accept("[로컬 LLM 미검증 검토 메모 · 취약점 확정 아님]\n" + completed.substring(0,Math.min(3800,completed.length()))
          + (completed.length()>3800?"\n[증거 메모 길이 제한으로 일부 생략]":""));
      status.setText("원본과 미검증 메모 추가 완료 · 재현 결과와 구분해 검토하세요."); }));
    controls();
  }
  void prepare(Context next) {
    if (closed) return;
    next.validate().run();
    if (!project.get().equals(next.project())) throw new IllegalArgumentException("프로젝트가 바뀌었습니다.");
    if (next.text().length() > LocalLlmClient.INPUT_LIMIT) throw new IllegalArgumentException("준비 자료가 너무 큽니다.");
    generation++; if (active != null) active.cancel();
    context = next; completed = ""; result.setText(""); preview.setText(next.text()); preview.setCaretPosition(0);
    status.setText("준비 완료 · 미리보기를 확인한 뒤 분석하세요. 요청·응답은 자동 전송되지 않습니다."); controls();
  }
  private void checked() {
    if (closed || context == null) throw new IllegalArgumentException("분석할 원본을 먼저 준비하세요.");
    if (!project.get().equals(context.project())) { reset(); throw new IllegalArgumentException("프로젝트가 바뀌었습니다. 원본을 다시 선택하세요."); }
    context.validate().run();
  }
  private void action(Runnable runnable) { try { runnable.run(); } catch(RuntimeException ex) { status.setText(ex.getMessage()); } }
  void start() {
    if (closed || active != null) return;
    action(() -> {
      checked();
      var settings = new LocalLlmClient.Settings(LocalLlmClient.endpoint(endpoint.getText()),model.getText().strip(),(Integer)tokens.getValue(),(Integer)timeout.getValue());
      String input = preview.getText(), mode = (String)purpose.getSelectedItem();
      if(input.isBlank())throw new IllegalArgumentException("전송할 분석 자료가 비어 있습니다.");
      long revision = ++generation; Context selected = context;
      LocalLlmClient.Call call = new LocalLlmClient.Call(); active = call;
      completed = ""; result.setText(""); status.setText("로컬 모델 분석 중 · 대상 시스템에 추가 요청 0건"); controls();
      Thread worker = new Thread(() -> {
        LocalLlmClient.Answer answer = null; String problem = null;
        try { answer = client.analyze(settings, mode, input, call); }
        catch(Exception ex) { problem = ex instanceof java.io.IOException || ex instanceof IllegalArgumentException ? ex.getMessage() : "로컬 LLM 분석 오류"; }
        LocalLlmClient.Answer value = answer; String failure = problem;
        SwingUtilities.invokeLater(() -> {
          if (active == call) active = null;
          if (closed || revision != generation) { controls(); return; }
          if (!project.get().equals(selected.project())) { reset(); return; }
          try { selected.validate().run(); } catch(RuntimeException ex) { reset(); status.setText("SCOPE가 바뀌어 분석 결과를 폐기했습니다."); return; }
          if (failure != null) status.setText(failure);
          else {
            completed = "모델: " + settings.model() + "\n목적: " + mode + "\n[미검증 초안 · 원본과 실제 재현으로 확인]\n" + value.text();
            result.setText(completed); result.setCaretPosition(0);
            status.setText("분석 완료 · " + value.elapsedMillis()/1000.0 + "초" + ("stop".equals(value.finishReason()) ? " · 재현·기각 여부를 검토하세요." : " · 출력이 완결되지 않을 수 있습니다. 토큰 제한·모델 설정을 확인하세요."));
          }
          controls();
        });
      }, "wb-local-llm"); worker.setDaemon(true); worker.start();
    });
  }
  void reset() {
    generation++; if(active != null)active.cancel(); context=null; completed=""; preview.setText("");result.setText("");
    status.setText("분석 자료를 비웠습니다. 현재 프로젝트의 원본을 다시 선택하세요."); controls();
  }
  private void controls() {
    boolean idle = active == null && !closed;
    analyze.setEnabled(idle && context != null); cancel.setEnabled(active != null && !closed);
    repeater.setEnabled(idle && context != null); evidence.setEnabled(idle && context != null && !completed.isEmpty());
    preview.setEditable(idle); endpoint.setEnabled(idle); model.setEnabled(idle); purpose.setEnabled(idle);tokens.setEnabled(idle);timeout.setEnabled(idle);
  }
  public void close() { closed=true; reset(); }
}
