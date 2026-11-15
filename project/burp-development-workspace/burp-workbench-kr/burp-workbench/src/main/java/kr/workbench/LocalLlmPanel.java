package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;

/** Explicit, memory-only hybrid AI review. A model never owns target-request execution. */
final class LocalLlmPanel extends JPanel implements AutoCloseable {
  final MontoyaApi api;
  enum Provider {
    AUTO("작업별 자동"), SUBSCRIPTION("ChatGPT 구독(Codex CLI)"), LOCAL("로컬 Halogen");
    private final String label;
    Provider(String label) { this.label = label; }
    @Override public String toString() { return label; }
  }
  static Provider providerFor(LocalLlmTasks.Task task) {
    return task == LocalLlmTasks.Task.FUNCTIONS || task == LocalLlmTasks.Task.REPORT
        ? Provider.SUBSCRIPTION : Provider.LOCAL;
  }
  final LocalLlmClient client = new LocalLlmClient();
  final CodexSubscriptionClient subscription = new CodexSubscriptionClient();
  final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(1), r -> { Thread t = new Thread(r, "Workbench-local-llm"); t.setDaemon(true); return t; });
  final JTextField baseUrl = new JTextField("http://127.0.0.1:8731/v1", 30),
      model = new JTextField("halogen-qwen3.8-flash-next", 29),
      codexPath = new JTextField("codex", 30), cloudModel = new JTextField("", 24);
  final JPasswordField apiKey = new JPasswordField(24);
  final JSpinner timeout = new JSpinner(new SpinnerNumberModel(180, 10, 600, 10)),
      maxTokens = new JSpinner(new SpinnerNumberModel(8192, 512, 65536, 512));
  final JComboBox<String> effort = new JComboBox<>(new String[] {"low", "medium", "none", ""});
  final JComboBox<Provider> provider = new JComboBox<>(Provider.values());
  final JComboBox<LocalLlmTasks.Task> task = new JComboBox<>(LocalLlmTasks.Task.values());
  final JTextArea context = text(4, true), preview = text(10, false), detail = text(9, false),
      report = text(10, false), connectionInfo = text(5, false), metrics = text(6, false);
  final JLabel status = plainLabel("원본을 선택해 AI 검토를 준비하세요. 분석은 직접 실행합니다."),
      sourceSummary = plainLabel("원본 0개 · AI 제안 · 취약점 확정 아님"),
      destination = plainLabel("전송 대상 · 로컬 Halogen");
  final JButton analyze = new JButton("AI 분석"), stop = new JButton("중지"),
      inspectSource = new JButton("원본 위치"), repeater = new JButton("원본 → Repeater"),
      evidence = new JButton("선택 제안 → 증거"), appendReport = new JButton("보고서 초안 추가"),
      health = new JButton("로컬 연결 확인"), models = new JButton("로컬 모델 목록"),
      subscriptionStatus = new JButton("구독 로그인 확인");
  final DefaultTableModel resultModel = new DefaultTableModel(new String[] {"제안/기능", "분류", "원본 수"}, 0) {
    @Override public boolean isCellEditable(int row, int column) { return false; }
  };
  final JTable table = new JTable(resultModel) {
    private final WorkbenchUi.Cells plain = new WorkbenchUi.Cells() {
      { putClientProperty("html.disable", true); }
      @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
          boolean focused, int row, int column) {
        putClientProperty("html.disable", true);
        super.getTableCellRendererComponent(t, value, selected, focused, row, column);
        setToolTipText(null); return this;
      }
    };
    @Override public TableCellRenderer getCellRenderer(int row, int column) { return plain; }
  };
  final JComboBox<String> sourceChoice = new JComboBox<>();
  final TargetWindow settings = new TargetWindow(this, "AI 실행 설정", this::settingsContent);
  BiConsumer<List<HttpRequestResponse>, String> evidenceSink = (packets, note) -> {
    throw new IllegalArgumentException("증거 연결이 준비되지 않았습니다.");
  };
  Consumer<String> reportSink = value -> { throw new IllegalArgumentException("보고서 연결이 준비되지 않았습니다."); };
  Consumer<String> sourceNavigator = id -> {};
  volatile LocalLlmTasks.Input input;
  volatile String preparedProject = "";
  volatile Map<String, HttpRequestResponse> originals = Map.of();
  LocalLlmTasks.Result result;
  Provider resultProvider;
  Future<?> active;
  long generation;
  boolean closed, adjusting, running;
  double queueMillis;
  final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(16, .75f, true);
  int cacheCharacters;
  record Cached(LocalLlmTasks.Result result, LocalLlmClient.Reply reply, int size) {}

  LocalLlmPanel(MontoyaApi api) {
    super(new BorderLayout(6, 6)); this.api = api;
    setName("wb.local.llm"); setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    analyze.setName("wb.local.llm.analyze"); stop.setName("wb.local.llm.stop");
    context.setName("wb.local.llm.context"); preview.setName("wb.local.llm.preview");
    table.setName("wb.local.llm.results"); report.setName("wb.local.llm.report");
    sourceChoice.setName("wb.local.llm.sources");
    JButton settingsButton = new JButton("AI 설정"); settingsButton.addActionListener(e -> settings.open());
    JPanel header = new JPanel(); header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
    header.add(WorkbenchUi.row(task, analyze, stop, settingsButton));
    header.add(WorkbenchUi.row(new JLabel("실행 방식"), provider, destination));
    header.add(WorkbenchUi.row(sourceSummary)); add(header, BorderLayout.NORTH);
    JTabbedPane inputs = new JTabbedPane();
    inputs.addTab("기대 정책·기능 설명", new JScrollPane(context));
    inputs.addTab("전송 내용 미리보기", new JScrollPane(preview));
    inputs.setToolTipTextAt(1, "표시된 전송 대상과 가림·부분 분석 범위를 확인하세요. AI 분석 버튼을 눌렀을 때만 선택한 실행 방식으로 전송합니다.");
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setAutoCreateRowSorter(true);
    sourceChoice.setRenderer(new DefaultListCellRenderer() {
      { putClientProperty("html.disable", true); }
      @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
          boolean selected, boolean focused) {
        putClientProperty("html.disable", true);
        return super.getListCellRendererComponent(list, value, index, selected, focused);
      }
    });
    JPanel proposals = new JPanel(new BorderLayout(4, 4));
    proposals.add(WorkbenchUi.row(new JLabel("연결 원본"), sourceChoice, inspectSource, repeater, evidence), BorderLayout.NORTH);
    proposals.add(WorkbenchUi.split(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(detail), .48));
    JPanel reportPage = new JPanel(new BorderLayout(4, 4));
    reportPage.add(WorkbenchUi.row(appendReport, WorkbenchUi.hint("AI 초안 · 근거와 실제 영향을 직접 확인하세요.")), BorderLayout.NORTH);
    reportPage.add(new JScrollPane(report));
    JTabbedPane output = new JTabbedPane(); output.addTab("분석·기능 분류 제안", proposals); output.addTab("보고서 초안", reportPage); output.addTab("처리 시간·토큰", new JScrollPane(metrics));
    add(WorkbenchUi.split(JSplitPane.VERTICAL_SPLIT, inputs, output, .30), BorderLayout.CENTER);
    add(status, BorderLayout.SOUTH);
    analyze.addActionListener(e -> guarded(this::analyze)); stop.addActionListener(e -> cancel("분석 중지 · 완료 처리하지 않았습니다."));
    health.addActionListener(e -> connect(false)); models.addActionListener(e -> connect(true));
    subscriptionStatus.addActionListener(e -> connectSubscription());
    inspectSource.addActionListener(e -> guarded(() -> { selectedOriginal(); sourceNavigator.accept((String) sourceChoice.getSelectedItem()); }));
    repeater.addActionListener(e -> guarded(this::prepareRepeater));
    evidence.addActionListener(e -> guarded(this::captureEvidence));
    appendReport.addActionListener(e -> guarded(this::appendReport));
    table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showSelection(); });
    sourceChoice.addActionListener(e -> refreshActions());
    DocumentListener edited = new DocumentListener() {
      public void insertUpdate(DocumentEvent e) { edited(); }
      public void removeUpdate(DocumentEvent e) { edited(); }
      public void changedUpdate(DocumentEvent e) { edited(); }
    };
    for (JTextField field : List.of(baseUrl, model, apiKey, codexPath, cloudModel)) field.getDocument().addDocumentListener(edited);
    context.getDocument().addDocumentListener(edited);
    task.addActionListener(e -> edited()); effort.addActionListener(e -> edited()); provider.addActionListener(e -> edited());
    timeout.addChangeListener(e -> edited()); maxTokens.addChangeListener(e -> edited());
    WorkbenchUi.style(this); updatePreview(); refreshActions();
  }

  static JTextArea text(int rows, boolean editable) {
    JTextArea area = new JTextArea(rows, 55); area.setEditable(editable); area.setLineWrap(true); area.setWrapStyleWord(true); return area;
  }
  static JLabel plainLabel(String value) { JLabel label = new JLabel(); label.putClientProperty("html.disable", true); label.setText(value); return label; }
  Component settingsContent() {
    JPanel local = TargetWindow.options(
        WorkbenchUi.row(new JLabel("Halogen API 주소"), baseUrl),
        WorkbenchUi.row(new JLabel("로컬 모델 ID"), model),
        WorkbenchUi.row(new JLabel("Halogen API 키 · 메모리만"), apiKey),
        WorkbenchUi.row(new JLabel("Halogen 총 출력·추론 예산"), maxTokens),
        WorkbenchUi.row(new JLabel("Halogen 추론 수준 · 빈 값은 생략"), effort),
        WorkbenchUi.row(health, models),
        WorkbenchUi.hint("Halogen은 localhost/사설 IP로 연결합니다. API 키는 Codex CLI에 전달하지 않습니다."));
    JPanel cloud = TargetWindow.options(
        WorkbenchUi.row(new JLabel("Codex 실행 파일"), codexPath),
        WorkbenchUi.row(new JLabel("구독 모델 · 빈 값은 CLI 기본값"), cloudModel),
        WorkbenchUi.row(subscriptionStatus),
        WorkbenchUi.hint("M5 터미널에서 codex login으로 ChatGPT 계정에 로그인한 뒤 사용하세요."),
        WorkbenchUi.hint("로그인 창은 자동으로 열지 않습니다. 이 실행 방식은 가린 입력을 외부로 전송합니다."));
    JTabbedPane connections = new JTabbedPane();
    connections.addTab("로컬 Halogen", local); connections.addTab("ChatGPT 구독 · Codex CLI", cloud);
    return TargetWindow.options(connections,
        WorkbenchUi.row(new JLabel("공통 제한 시간(초)"), timeout), new JScrollPane(connectionInfo),
        WorkbenchUi.hint("설정·키·패킷은 자동 저장하지 않습니다. 작업별 자동은 기능 분류·보고서에 구독을 사용합니다."));
  }
  Provider effectiveProvider() {
    Provider selected = (Provider) provider.getSelectedItem();
    return selected == null || selected == Provider.AUTO ? providerFor((LocalLlmTasks.Task) task.getSelectedItem()) : selected;
  }
  String destinationText(Provider backend) {
    return backend == Provider.SUBSCRIPTION ? "ChatGPT 구독 · Codex CLI · 외부 전송" : "로컬 Halogen · 설정한 로컬/사설 서버";
  }

  void prepare(LocalLlmTasks.Input next, Map<String, HttpRequestResponse> packets) {
    if (closed) throw new IllegalStateException("LLM 화면이 종료되었습니다.");
    Objects.requireNonNull(next); Objects.requireNonNull(packets);
    String project = WorkspaceResume.projectIdentity(api);
    if (input != null && !preparedProject.equals(project)) resetProject();
    LocalLlmTasks.user(next); // Validate the complete bounded snapshot before replacing existing input.
    LinkedHashMap<String, HttpRequestResponse> retained = new LinkedHashMap<>();
    for (var source : next.sources()) {
      var original = packets.get(source.id());
      if (original == null || original.request() == null)
        throw new IllegalArgumentException("분석 원본 연결이 없습니다. 원본을 다시 선택하세요.");
      if (!WorkbenchScope.contains(api, original.request()))
        throw new IllegalArgumentException("현재 SCOPE 밖 원본은 준비할 수 없습니다.");
      retained.put(source.id(), original);
    }
    // Sanitization can hide a changed credential. Never let a matching masked prompt reuse a
    // result for a different immutable original request/response identity.
    if (!project.equals(WorkspaceResume.projectIdentity(api))) { resetProject(); throw projectChanged(); }
    boolean sameOriginals = project.equals(preparedProject) && originals.size() == retained.size() && retained.entrySet().stream().allMatch(entry -> {
      var previous = originals.get(entry.getKey()); var current = entry.getValue();
      return previous != null && previous.request() == current.request() && previous.response() == current.response();
    });
    if (!sameOriginals) { cache.clear(); cacheCharacters = 0; }
    cancel("새 원본 준비"); input = next; preparedProject = project; originals = Map.copyOf(retained);
    adjusting = true;
    try { task.setSelectedItem(next.task()); context.setText(next.context()); }
    finally { adjusting = false; }
    clearResult(); updatePreview();
    status.setText("준비 완료 · 전송 내용 미리보기를 확인한 뒤 AI 분석을 실행하세요."); refreshActions();
  }

  LocalLlmTasks.Input currentInput() {
    if (input == null) throw new IllegalArgumentException("분석할 원본을 먼저 선택하세요.");
    return new LocalLlmTasks.Input((LocalLlmTasks.Task) task.getSelectedItem(), context.getText(), input.sources());
  }
  LocalLlmConfig configuration() {
    char[] chars = apiKey.getPassword();
    try { return new LocalLlmConfig(baseUrl.getText().strip(), model.getText().strip(), new String(chars),
        ((Number) timeout.getValue()).intValue(), ((Number) maxTokens.getValue()).intValue(), (String) effort.getSelectedItem()); }
    finally { Arrays.fill(chars, '\0'); }
  }
  void edited() {
    if (adjusting || closed) return;
    cancel("입력·설정 변경 · 이전 분석 결과를 해제했습니다."); clearResult(); connectionInfo.setText(""); updatePreview(); refreshActions();
  }
  void updatePreview() {
    destination.setText("전송 대상 · " + destinationText(effectiveProvider()));
    if (input == null) { preview.setText(""); sourceSummary.setText("원본 0개 · AI 제안 · 취약점 확정 아님"); return; }
    try {
      var snapshot = currentInput();
      preview.setText("전송 대상: " + destinationText(effectiveProvider()) + "\n작업: " + snapshot.task().label()
          + "\n원본: " + snapshot.sources().size() + "개 · 계정 설명은 사용자 선언 · 인증값 가림 · 부분 입력\n"
          + "AI 분석을 눌렀을 때 다음 JSON을 위 대상으로 전송합니다.\n\n" + LocalLlmTasks.user(snapshot));
      preview.setCaretPosition(0);
      sourceSummary.setText("원본 " + snapshot.sources().size() + "개 · AI 제안 · 취약점 확정 아님");
    } catch (RuntimeException error) { preview.setText("입력 범위를 확인하세요. 분석을 준비할 수 없습니다."); }
  }
  static IllegalArgumentException projectChanged() {
    return new IllegalArgumentException("프로젝트가 변경되었습니다. AI 원본을 다시 선택하세요.");
  }
  void requireProject() {
    if (input != null && !preparedProject.equals(WorkspaceResume.projectIdentity(api))) {
      resetProject(); throw projectChanged();
    }
  }
  void requireScope(LocalLlmTasks.Input snapshot) {
    requireProject();
    for (var source : snapshot.sources()) {
      var original = originals.get(source.id());
      if (original == null || !WorkbenchScope.contains(api, original.request()))
        throw new IllegalArgumentException("현재 SCOPE 밖 또는 만료된 원본 포함 · 분석·전달 중단");
    }
  }

  void analyze() {
    if (closed || running) return;
    requireProject(); var snapshot = currentInput(); requireScope(snapshot);
    Provider backend = effectiveProvider();
    LocalLlmConfig config = backend == Provider.LOCAL ? configuration() : null;
    String executable = codexPath.getText().strip(), requestedModel = cloudModel.getText().strip();
    int budgetSeconds = ((Number) timeout.getValue()).intValue();
    String system = LocalLlmTasks.system(snapshot.task()), user = LocalLlmTasks.user(snapshot);
    String routing = backend == Provider.LOCAL
        ? config.baseUrl() + "\n" + config.model() + "\n" + digest(config.apiKey()) + "\n" + config.maxTokens() + "\n" + config.reasoningEffort()
        : executable + "\n" + requestedModel;
    String key = digest(backend.name() + "\n" + routing + "\n" + budgetSeconds + "\n" + system + "\n" + user);
    clearResult();
    Cached cached = cache.get(key);
    if (cached != null) {
      requireScope(snapshot); showResult(cached.result(), cached.reply(), true, backend); return;
    }
    Map<String, HttpRequestResponse> sourceSnapshot = originals;
    String projectSnapshot = preparedProject;
    long queuedAt = System.nanoTime();
    long token = ++generation; running = true; status.setText(destinationText(backend) + " 분석 중 · 최대 " + budgetSeconds + "초"); refreshActions();
    try {
      active = worker.submit(() -> {
        try {
          if (!projectSnapshot.equals(WorkspaceResume.projectIdentity(api))) throw projectChanged();
          for (var source : snapshot.sources()) {
            var original = sourceSnapshot.get(source.id());
            if (Thread.currentThread().isInterrupted() || original == null || !WorkbenchScope.contains(api, original.request()))
              throw new CancellationException();
          }
          double waited = (System.nanoTime() - queuedAt) / 1_000_000.0;
          LocalLlmClient.Reply reply = backend == Provider.LOCAL
              ? client.complete(config, system, user, false)
              : subscription.complete(executable, requestedModel, budgetSeconds, system, user);
          LocalLlmTasks.Result parsed = LocalLlmTasks.parse(reply.text(), snapshot);
          SwingUtilities.invokeLater(() -> {
            if (closed || token != generation) return;
            running = false; active = null;
            try { requireScope(snapshot); queueMillis = waited; putCache(key, parsed, reply); showResult(parsed, reply, false, backend); }
            catch (RuntimeException error) { clearResult(); status.setText(safeFailure(error)); refreshActions(); }
          });
        } catch (Exception error) { finishFailure(token, error); }
      });
    } catch (RejectedExecutionException error) { running = false; status.setText("이전 작업이 종료되는 중입니다. 잠시 후 다시 실행하세요."); refreshActions(); }
  }
  void connect(boolean fetchModels) {
    guarded(() -> {
      if (closed || running) return;
      requireProject(); var config = configuration(); long token = ++generation; running = true; refreshActions();
      status.setText(fetchModels ? "로컬 모델 목록 조회 중" : "로컬 서버 지원 기능 확인 중");
      try {
        active = worker.submit(() -> {
          try {
            String value = fetchModels ? String.join("\n", client.models(config)) : client.health(config);
            SwingUtilities.invokeLater(() -> {
              if (closed || token != generation) return;
              running = false; active = null; connectionInfo.setText(value); connectionInfo.setCaretPosition(0);
              status.setText(fetchModels ? "모델 목록 확인 · 사용할 ID를 모델 입력란에 지정하세요." : "연결 확인 완료 · 지원 옵션은 위 서버 응답을 확인하세요."); refreshActions();
            });
          } catch (Exception error) { finishFailure(token, error); }
        });
      } catch (RejectedExecutionException error) { running = false; status.setText("이전 작업 종료 후 다시 연결하세요."); refreshActions(); }
    });
  }
  void connectSubscription() {
    guarded(() -> {
      if (closed || running) return;
      requireProject();
      String executable = codexPath.getText().strip(); int budgetSeconds = ((Number) timeout.getValue()).intValue();
      long token = ++generation; running = true; refreshActions();
      status.setText("ChatGPT 구독 로그인 상태 확인 중 · 로그인 창은 자동으로 열지 않습니다.");
      try {
        active = worker.submit(() -> {
          try {
            String value = subscription.status(executable, budgetSeconds);
            SwingUtilities.invokeLater(() -> {
              if (closed || token != generation) return;
              running = false; active = null;
              try { requireProject(); connectionInfo.setText(value); connectionInfo.setCaretPosition(0); status.setText("ChatGPT 구독 로그인 확인 완료"); }
              catch (RuntimeException error) { status.setText(safeFailure(error)); }
              refreshActions();
            });
          } catch (Exception error) { finishFailure(token, error); }
        });
      } catch (RejectedExecutionException error) { running = false; status.setText("이전 작업 종료 후 구독 로그인을 다시 확인하세요."); refreshActions(); }
    });
  }
  void finishFailure(long token, Exception error) {
    SwingUtilities.invokeLater(() -> {
      if (closed || token != generation) return;
      running = false; active = null;
      if (input != null && !preparedProject.equals(WorkspaceResume.projectIdentity(api))) {
        resetProject(); status.setText(projectChanged().getMessage());
      } else status.setText(safeFailure(error));
      refreshActions();
    });
  }
  static String safeFailure(Exception error) {
    if (error instanceof CancellationException || error instanceof InterruptedException)
      return "분석 중지 · 완료 처리하지 않았습니다.";
    String message = Objects.toString(error.getMessage(), "");
    if (message.startsWith("ChatGPT 구독") || message.equals(CodexSubscriptionClient.EXECUTABLE_INVALID) || message.equals(CodexSubscriptionClient.BUSY)) return CodexSubscriptionClient.safeFailure(error);
    // Only owned, fixed diagnostics are shown; arbitrary exception/server messages remain hidden.
    if (SAFE_MESSAGES.contains(message)
        || message.matches("LLM 서버 HTTP [1-5][0-9]{2} · 주소·모델·인증과 지원 옵션을 확인하세요\\." )
        || message.matches("AI 결과 필드 오류: (?:title|category|observation|uncertainty|next_check|report)"))
      return message;
    if (error instanceof IllegalArgumentException && message.startsWith("JSON "))
      return "AI 결과의 JSON 형식·크기·구조를 확인하세요. 분석 미완료입니다.";
    return "분석/연결 실패 · 연결 설정·서버 상태·출력 형식·토큰 예산을 확인하세요.";
  }
  static final Set<String> SAFE_MESSAGES = Set.of(
      "현재 /health에서 JSON 출력 지원을 확인하지 못했습니다. 일반 JSON 지시 모드를 사용하세요.",
      "LLM 응답에 choices/message가 없습니다. 분석 미완료입니다.",
      "출력·추론 예산이 소진됐습니다(finish_reason=length). 분석 미완료 · 예산 또는 추론 수준을 조정하세요.",
      "LLM 응답이 정상 종료되지 않았습니다. 분석 미완료입니다.", "LLM의 최종 답변이 비어 있습니다. 분석 미완료입니다.",
      "모델 목록 응답 형식을 확인하세요.", "LLM 연결이 종료되었습니다.",
      "LLM 요청의 256 KiB 한도를 초과했습니다. 원본을 나누어 분석하세요.", "LLM 분석을 중지했습니다.",
      "이전 LLM 요청이 처리 중입니다.", "LLM 응답 제한 시간을 초과했습니다. 분석 미완료입니다.",
      "로컬 LLM 연결·응답 수신 실패 · 서버 상태·응답 크기(512 KiB 한도)를 확인하세요.",
      "LLM 서버의 JSON 응답 형식을 확인하세요.", "모델 ID를 확인하세요. 최대 200자입니다.",
      "API 키 형식을 확인하세요.", "응답 제한은 1~600초입니다.", "출력·추론 예산은 256~65536토큰입니다.",
      "지원하는 추론 수준을 선택하세요.", "로컬 LLM 주소는 localhost 또는 사설 IP의 http(s)://주소[:포트]/v1 형식입니다.",
      "분석 맥락 크기 한도 초과", "AI 입력 100,000자 한도 초과 · 패킷을 기능별로 나눠 선택하세요.",
      "AI 결과 형식 오류 · 근거 행 1~64개가 필요합니다.", "AI 결과의 원본 ID 형식이 올바르지 않습니다.",
      "AI가 입력에 없는 원본 ID를 반환했습니다. 결과를 적용하지 않습니다.",
      "AI 분류 결과에 미분류 원본이 남았으며 행 한도를 넘었습니다.", "보고서 초안에 근거 원본 ID가 없습니다. 결과를 적용하지 않습니다.",
      "프로젝트가 변경되었습니다. AI 원본을 다시 선택하세요.", "분석할 원본을 먼저 선택하세요.", "현재 SCOPE 밖 또는 만료된 원본 포함 · 분석·전달 중단",
      "분석 제안을 선택하세요.", "분석 결과를 다시 선택하세요.", "연결 원본을 선택하세요.",
      "현재 SCOPE 밖 또는 만료된 원본입니다.", "원본이 만료되었습니다.", "보고서 초안이 없습니다.",
      "원본 요청/응답 없음 또는 패킷 합계 128 KiB 초과 · 원본을 다시 선택하세요.",
      "현재 SCOPE 밖 원본 포함 · 증거 전달을 중단했습니다.", "증거 연결이 준비되지 않았습니다.", "보고서 연결이 준비되지 않았습니다.");
  void cancel(String message) {
    generation++; client.cancel(); subscription.cancel(); if (active != null) active.cancel(true); active = null;
    worker.getQueue().clear(); running = false; status.setText(message); refreshActions();
  }
  void putCache(String key, LocalLlmTasks.Result parsed, LocalLlmClient.Reply reply) {
    int size = reply.text().length();
    Cached previous = cache.remove(key); if (previous != null) cacheCharacters -= previous.size();
    if (size > 1024 * 1024) return;
    while (!cache.isEmpty() && (cache.size() >= 16 || cacheCharacters + size > 1024 * 1024)) {
      var first = cache.entrySet().iterator(); Cached removed = first.next().getValue(); first.remove(); cacheCharacters -= removed.size();
    }
    // The cache retains one parsed proposal, not a second raw model response.
    var metrics = new LocalLlmClient.Reply("", reply.finishReason(), reply.promptTokens(),
        reply.completionTokens(), reply.elapsedMillis(), reply.timings());
    cache.put(key, new Cached(parsed, metrics, size)); cacheCharacters += size;
  }
  void showResult(LocalLlmTasks.Result parsed, LocalLlmClient.Reply reply, boolean cached) {
    showResult(parsed, reply, cached, effectiveProvider());
  }
  void showResult(LocalLlmTasks.Result parsed, LocalLlmClient.Reply reply, boolean cached, Provider backend) {
    result = parsed; resultProvider = backend; resultModel.setRowCount(0);
    for (var row : parsed.rows()) resultModel.addRow(new Object[] {row.title(), row.category(), row.sourceIds().size()});
    report.setText(parsed.report()); report.setCaretPosition(0);
    StringBuilder measured = new StringBuilder("실행 방식: " + destinationText(backend) + "\n" + (cached ? "캐시된 이전 측정값 · 이번에는 모델 요청 없음\n" : "이번 분석 측정값\n"));
    boolean usageAvailable = true;
    if (backend == Provider.SUBSCRIPTION) {
      try {
        Object decoded = MinerJson.parse(reply.timings());
        usageAvailable = decoded instanceof Map<?, ?> values && Boolean.TRUE.equals(values.get("usage_available"));
      } catch (RuntimeException ignored) { usageAvailable = false; }
    }
    String inputTokens = usageAvailable ? Integer.toString(reply.promptTokens()) : "미제공";
    String outputTokens = usageAvailable ? Integer.toString(reply.completionTokens()) : "미제공";
    measured.append(String.format(Locale.ROOT, "Workbench 대기: %.1f ms\n모델 요청 전체: %d ms\n입력: %s 토큰 / 출력·추론: %s 토큰\n", cached ? 0 : queueMillis, reply.elapsedMillis(), inputTokens, outputTokens));
    try {
      Object decoded = MinerJson.parse(reply.timings());
      if (decoded instanceof Map<?, ?> values) {
        String[][] fields = {{"prompt_ms", "입력 처리(ms)"}, {"predicted_ms", "출력 생성(ms)"}, {"cache_n", "입력 캐시 토큰"}, {"prompt_per_second", "입력 토큰/초"}, {"predicted_per_second", "출력 토큰/초"}};
        for (var field : fields) if (values.get(field[0]) instanceof Number value) measured.append(field[1]).append(": ").append(value).append('\n');
      }
    } catch (RuntimeException ignored) {}
    measured.append("서버가 제공하지 않은 세부 시간은 표시하지 않습니다."); metrics.setText(measured.toString()); metrics.setCaretPosition(0);
    status.setText((cached ? "캐시 결과 · " : "분석 완료 · ") + backend + " · " + parsed.rows().size() + "개 제안 · "
        + String.format(Locale.ROOT, "%.1f", reply.elapsedMillis() / 1000.0) + "초 · 입력 " + inputTokens
        + " / 출력 " + outputTokens + "토큰 · AI 제안");
    if (!parsed.rows().isEmpty()) table.setRowSelectionInterval(0, 0);
    refreshActions();
  }
  LocalLlmTasks.Row selectedRow() {
    int row = table.getSelectedRow();
    if (result == null || row < 0) throw new IllegalArgumentException("분석 제안을 선택하세요.");
    int index = table.convertRowIndexToModel(row);
    if (index >= result.rows().size()) throw new IllegalArgumentException("분석 결과를 다시 선택하세요.");
    return result.rows().get(index);
  }
  void showSelection() {
    sourceChoice.removeAllItems(); detail.setText("");
    try {
      var row = selectedRow(); for (String id : row.sourceIds()) sourceChoice.addItem(id);
      detail.setText("제안: " + row.title() + "\n분류: " + row.category() + "\n연결 원본: " + String.join(", ", row.sourceIds())
          + "\n\n관찰·가설\n" + row.observation() + "\n\n미확인·반증 조건\n" + row.uncertainty()
          + "\n\n다음 최소 검증안\n" + row.nextCheck() + "\n\nAI 제안이며 취약점 확정·점검 완료가 아닙니다.");
      detail.setCaretPosition(0);
    } catch (IllegalArgumentException ignored) {}
    refreshActions();
  }
  HttpRequestResponse selectedOriginal() {
    requireProject();
    var row = selectedRow(); String id = (String) sourceChoice.getSelectedItem();
    if (id == null || !row.sourceIds().contains(id)) throw new IllegalArgumentException("연결 원본을 선택하세요.");
    var packet = originals.get(id);
    if (packet == null || !WorkbenchScope.contains(api, packet.request()))
      throw new IllegalArgumentException("현재 SCOPE 밖 또는 만료된 원본입니다.");
    return packet;
  }
  void prepareRepeater() {
    var packet = selectedOriginal(); api.repeater().sendToRepeater(packet.request(), "AI 검토 원본");
    status.setText("원본을 Repeater에 준비했습니다. 검증안·인증·값을 확인하고 Send를 직접 누르세요.");
  }
  void captureEvidence() {
    requireProject();
    var row = selectedRow(); List<HttpRequestResponse> packets = new ArrayList<>();
    for (String id : row.sourceIds()) {
      var packet = originals.get(id); if (packet == null) throw new IllegalArgumentException("원본이 만료되었습니다."); packets.add(packet);
    }
    String note = "AI 검토 제안 · 미검증 · " + resultProvider + " · " + row.title() + "\n관찰: " + row.observation()
        + "\n미확인: " + row.uncertainty() + "\n다음 검증: " + row.nextCheck();
    evidenceSink.accept(EvidenceLinks.checked(api, packets), note);
    status.setText("연결 원본과 AI 제안을 증거에 추가했습니다. 실제 재현·영향을 확인하세요.");
  }
  void appendReport() {
    if (result == null || result.report().isBlank()) throw new IllegalArgumentException("보고서 초안이 없습니다.");
    requireScope(currentInput()); reportSink.accept("AI 보고서 초안 · 수동 검토 필요 · " + resultProvider + "\n\n" + result.report());
    status.setText("보고서 초안을 추가했습니다. 근거·재현 절차·영향을 직접 확인하세요.");
  }
  void clearResult() {
    result = null; resultProvider = null; resultModel.setRowCount(0); sourceChoice.removeAllItems(); detail.setText(""); report.setText(""); metrics.setText(""); refreshActions();
  }
  void refreshActions() {
    analyze.setEnabled(!closed && !running && input != null); stop.setEnabled(!closed && running);
    health.setEnabled(!closed && !running); models.setEnabled(!closed && !running); subscriptionStatus.setEnabled(!closed && !running);
    boolean selected = !closed && !running && result != null && table.getSelectedRow() >= 0 && sourceChoice.getSelectedItem() != null;
    inspectSource.setEnabled(selected); repeater.setEnabled(selected); evidence.setEnabled(selected);
    appendReport.setEnabled(!closed && !running && result != null && !result.report().isBlank());
  }
  void resetProject() {
    cancel("프로젝트 변경 · AI 원본·결과·키를 해제했습니다."); input = null; preparedProject = ""; originals = Map.of(); cache.clear(); cacheCharacters = 0;
    adjusting = true; try { context.setText(""); apiKey.setText(""); connectionInfo.setText(""); }
    finally { adjusting = false; }
    clearResult(); updatePreview(); refreshActions();
  }
  @Override public void close() {
    if (closed) return; resetProject(); closed = true; client.close(); subscription.close(); worker.shutdownNow(); settings.close(); refreshActions();
  }
  void guarded(Runnable action) {
    try { action.run(); }
    catch (RuntimeException error) { status.setText(safeFailure(error)); refreshActions(); }
  }
  static String digest(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception error) { throw new IllegalStateException("분석 키 계산 실패"); }
  }
}
