package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.collaborator.*;
import burp.api.montoya.http.message.*;
import java.awt.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/**
 * Manual bridges, serial workflows, correlated OAST and portable evidence. Passive construction
 * emits no HTTP.
 */
public final class BountySupportPanel extends JPanel implements AutoCloseable {
  final MontoyaApi api;
  final Supplier<List<HttpRequestResponse>> selected;
  final AuthComparePanel auth;
  final ActivityPanel activity;
  final Set<JDialog> previewWindows = new HashSet<>();
  final JTabbedPane pages = new JTabbedPane();
  final JLabel state = new JLabel("도구를 선택해 요청을 준비하세요. 자동 실행 없음");
  final AtomicBoolean closed = new AtomicBoolean(), cancel = new AtomicBoolean();
  volatile boolean running;
  volatile boolean runningCancellable;
  final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "Workbench-bounty-support");
            t.setDaemon(true);
            return t;
          });
  final JComboBox<String> tool =
      new JComboBox<>(
          new String[] {
            "Param Miner · 숨겨진 입력", "InQL · GraphQL", "Turbo Intruder · 동시성", "Burp 세션 규칙 · 로그인 매크로"
          });
  final JTextArea instructions = area(5),
      external = area(7),
      workflow = area(10),
      extract = area(3),
      workflowDetail = area(10),
      flowChecks = area(3);
  final JTextField flowOrigin = new JTextField(35);
  final JCheckBox flowWrites = new JCheckBox("쓰기 단계 실행 허용"),
      includeBodies = new JCheckBox("JSON/폼 본문 포함 · 민감 키 가림");
  final JSpinner flowTimeout = new JSpinner(new SpinnerNumberModel(10, 1, 120, 1)),
      flowBudget = new JSpinner(new SpinnerNumberModel(8, 1, 20, 1));
  final DefaultTableModel flowModel = model("단계", "메서드", "URL", "HTTP", "관찰");
  final JTable flowTable = new JTable(flowModel);
  final List<BountyWorkflow.Result> flowResults = new ArrayList<>();
  final TargetWindow flowSettings =
      new TargetWindow(this, "업무 흐름 · 실행 설정", this::flowSettingsContent);
  EvidencePacketStore evidenceStore = new EvidencePacketStore();
  final JTextField evidenceTitle = new JTextField("취약점 재현 증거", 28);
  final JTextField evidenceExtraNames = new JTextField(28);
  final TargetWindow evidenceRedactionSettings =
      new TargetWindow(this, "증거 가림 설정", this::evidenceRedactionContent);
  final JTextArea impact = area(3), repro = area(4);
  final List<BountyEvidence.Item> evidence = new ArrayList<>();
  final java.util.concurrent.ConcurrentMap<String, String> evidenceExecutions = new java.util.concurrent.ConcurrentHashMap<>();
  final java.util.concurrent.ConcurrentMap<String, Set<String>> evidenceExecutionSources = new java.util.concurrent.ConcurrentHashMap<>();
  final java.util.concurrent.ConcurrentMap<String, String> evidenceExecutionParts = new java.util.concurrent.ConcurrentHashMap<>();
  final Set<String> evidenceHeaders = new LinkedHashSet<>();
  final Set<String> evidenceSecrets = new LinkedHashSet<>();
  static final int EVIDENCE_REDACTION_VALUES = 256, EVIDENCE_REDACTION_CHARACTERS = 256 * 1024;
  final DefaultTableModel evidenceModel = model("이름", "메서드", "URL", "관찰");
  final JTable evidenceTable = new JTable(evidenceModel);
  final JLabel evidenceCount = new JLabel("증거 0 / 32개");
  record EvidenceEdit(List<BountyEvidence.Item> items, Map<String, String> executions,
      Map<String, String> parts, List<Integer> selected, EvidencePacketStore store, String project, boolean compact) {}
  EvidenceEdit evidenceUndo;
  boolean evidenceCompactionNeeded;
  volatile String evidenceProject;
  final AtomicLong evidenceEpoch = new AtomicLong();
  final AtomicInteger successfulEvidenceSaves = new AtomicInteger();
  BackgroundJobs backgroundJobs;
  Path browserImage;
  String browserImageSha256;
  final JButton flowRun = action("순차 실행", this::runFlow),
      flowStop =
          action(
              "중지",
              () -> {
                cancel.set(true);
                state.setText("후속 단계 중지 · 현재 요청은 응답/타임아웃까지 대기");
              }),
      flowCapture = action("결과 → 증거", this::captureFlow),
      evidencePreview = action("가림 미리보기", this::previewEvidence),
      evidenceSave = action("증거 ZIP 저장", this::saveEvidence),
      evidenceClear = action("비우기", this::clearEvidence),
      evidenceRemove = action("선택 제거", this::removeSelectedEvidence),
      evidenceUp = action("위로", () -> moveSelectedEvidence(-1)),
      evidenceDown = action("아래로", () -> moveSelectedEvidence(1)),
      evidenceUndoAction = action("실행 취소", this::undoEvidenceEdit);
  CollaboratorClient collaborator;
  final LinkedHashMap<String, OastRequest> correlations = new LinkedHashMap<>();
  final Set<String> seenInteractions = new HashSet<>();
  final DefaultTableModel oastModel = model("ID", "유형", "시각", "메서드", "URL", "관찰");
  final JTable oastTable = new JTable(oastModel);
  final JTextField payload = new JTextField(50);
  final JTextArea oastExternal = area(4);

  record OastRequest(String method, String url) {}

  static final String[] LINKS = {
    "https://portswigger.net/bappstore/17d2949a985c4b7ca092728dba871943",
    "https://github.com/doyensec/inql",
    "https://github.com/PortSwigger/turbo-intruder",
    "https://portswigger.net/burp/documentation/desktop/settings/sessions"
  };

  static DefaultTableModel model(String... c) {
    return new DefaultTableModel(c, 0) {
      public boolean isCellEditable(int r, int c) {
        return false;
      }
    };
  }

  static JTextArea area(int rows) {
    JTextArea a = new JTextArea(rows, 60);
    a.setLineWrap(false);
    a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    return a;
  }

  static JScrollPane packetScroll(JTextArea text) {text.putClientProperty("wb.packet.syntax",true);return new JScrollPane(text);}

  JButton action(String text, Runnable run) {
    JButton b = new JButton(text);
    b.addActionListener(
        e -> {
          try {
            run.run();
          } catch (Exception ex) {
            Diagnostics.failure(BountySupportPanel.this,"재현·증거 정리",ex);
            state.setText(Objects.toString(ex.getMessage(), ex.getClass().getSimpleName()));
          }
        });
    return b;
  }

  void later(Runnable r) {
    SwingUtilities.invokeLater(
        () -> {
          if (!closed.get()) r.run();
        });
  }

  void task(Runnable r) {
    task("재현·외부 연결 작업", false, r);
  }

  void task(String title, boolean cancellable, Runnable r) {
    if (closed.get() || running) throw new IllegalArgumentException("진행 중 작업을 끝내거나 중지하세요.");
    running = true;
    runningCancellable = cancellable;
    refreshActions();
    cancel.set(false);
    var background = BackgroundJobs.begin(backgroundJobs, title, "bounty",
        cancellable ? () -> { cancel.set(true); later(() -> state.setText("후속 단계 중지 요청 · 현재 요청 종료 대기")); } : null);
    background.progress("대기", 0, -1);
    try { worker.execute(
        () -> {
          try {
            background.progress("처리 중", 0, -1);
            r.run();
            if (cancel.get() || closed.get()) background.cancelled("작업 중지");
            else background.complete("작업 완료");
          } catch (Exception ex) {Diagnostics.failure(BountySupportPanel.this,"재현·증거 정리",ex);
            background.fail("작업 실패 · 해당 화면에서 원인 확인");
            later(
                () ->
                    state.setText(
                        Objects.toString(ex.getMessage(), ex.getClass().getSimpleName())));
          } finally {
            later(
                () -> {
                  running = false;
                  runningCancellable = false;
                  refreshActions();
                });
            if (closed.get()) { running = false; runningCancellable = false; }
          }
        }); } catch (RuntimeException error) {
      running = false; runningCancellable = false; refreshActions();
      background.fail("작업을 시작하지 못했습니다.");
      throw error;
    }
  }

  HttpRequestResponse one() {
    var list = selected.get();
    if (list.size() != 1) throw new IllegalArgumentException("트래픽에서 요청 하나를 선택하세요.");
    var rr = list.get(0);
    if (!WorkbenchScope.contains(api, rr.request()))
      throw new IllegalArgumentException("선택 요청이 SCOPE 밖입니다.");
    return rr;
  }

  public BountySupportPanel(
      MontoyaApi api,
      Supplier<List<HttpRequestResponse>> selected,
      AuthComparePanel auth,
      ActivityPanel activity) {
    super(new BorderLayout(8, 8));
    this.api = api;
    this.selected = selected;
    this.auth = auth;
    this.activity = activity;
    evidenceProject = WorkspaceResume.projectIdentity(api);
    setName("wb.bounty.support");
    setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    pages.addTab("외부 도구", toolsContent());
    pages.addTab("여러 요청 재현", workflowContent());
    pages.addTab("외부 연결 확인", oastContent());
    pages.setToolTipTextAt(2, "OAST: 대상에서 외부로 발생한 DNS·HTTP 연결을 확인합니다. 주소 발급만으로 대상 요청을 보내지 않습니다.");
    pages.addTab("증거 묶음", evidenceContent());
    pages.addTab("브라우저 확인", browserContent());
    add(
        WorkbenchUi.header(FeatureGuide.title("bounty"), "요청 순서 재현 → 근거 연결 → 가림 확인 → 증거 저장", null),
        BorderLayout.NORTH);
    add(pages);
    add(state, BorderLayout.SOUTH);
    flowModel.addTableModelListener(e -> refreshActions());
    evidenceModel.addTableModelListener(e -> refreshActions());
    evidenceTable.getSelectionModel().addListSelectionListener(e -> refreshActions());
    refreshActions();
    WorkbenchUi.style(this);
    WorkbenchUi.status(state);
    WorkbenchUi.feedback(state);
  }

  JPanel toolsContent() {
    JPanel p = new JPanel(new BorderLayout(8, 8));
    JPanel top = new JPanel();
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    top.add(
        WorkbenchUi.row(
            tool,
            action("선택 요청 → Repeater", this::handoff),
            action(
                "공식 안내",
                () -> {
                  try {
                    Desktop.getDesktop().browse(URI.create(LINKS[tool.getSelectedIndex()]));
                  } catch (Exception ex) {
                    Diagnostics.failure(BountySupportPanel.this,"재현·증거 정리",ex);
                    state.setText("공식 주소: " + LINKS[tool.getSelectedIndex()]);
                  }
                })));
    instructions.setEditable(false);
    instructions.setLineWrap(true);
    top.add(new JScrollPane(instructions));
    tool.addActionListener(e -> toolInstructions());
    toolInstructions();
    p.add(top, BorderLayout.NORTH);
    p.add(WorkbenchUi.card("외부 결과 · METHOD [탭] URL [탭] 위치 [탭] 이름", new JScrollPane(external)));
    p.add(
        WorkbenchUi.row(
            action("결과 → 활동 추적", this::importParameters),
            WorkbenchUi.hint("URL/BODY/HEADER/COOKIE · 파라미터 값은 가져오지 않음")),
        BorderLayout.SOUTH);
    return p;
  }

  void toolInstructions() {
    String text =
        switch (tool.getSelectedIndex()) {
          case 0 ->
              "Param Miner를 BApp Store에서 별도로 설치합니다. 선택 요청을 Repeater로 준비한 뒤 해당 도구의 우클릭 Guess 기능을"
                  + " 실행하세요. 발견 이름을 아래 TSV에 정리해 활동 추적으로 가져옵니다.";
          case 1 ->
              "InQL을 별도로 설치하고 GraphQL 요청/스키마를 분석합니다. 요청은 Repeater에서 InQL 메뉴로 전달하세요. 발견한"
                  + " operation/변수의 실제 HTTP 메서드와 입력 이름을 아래에 정리합니다.";
          case 2 ->
              "Turbo Intruder를 별도로 설치합니다. 프로그램이 허용하는 동시성 테스트에 사용할 요청을 준비하고 정상 순차 결과와 최종 상태를 비교하세요."
                  + " 이 확장은 병렬 요청을 자동 발송하지 않습니다.";
          default ->
              "복잡한 로그인·다단계 토큰 갱신은 Burp Settings > Sessions의 세션 규칙/매크로를 사용하세요. 규칙의 도구·URL 범위를 제한해 계정"
                  + " 슬롯이 섞이지 않게 설정합니다. 권한 비교는 실제 전송 인증이 바뀌면 판정을 보류합니다.";
        };
    instructions.setText(
        text
            + "\n\n외부 도구 실행은 해당 도구의 메뉴에서 직접 수행합니다. 공통 호출 API 없이 설치 상태나 실행 성공을 추측하지 않습니다.\n공식 주소: "
            + LINKS[tool.getSelectedIndex()]);
  }

  void handoff() {
    var rr = one();
    api.repeater().sendToRepeater(rr.request(), "점검 지원 · " + tool.getSelectedItem());
    state.setText("요청 준비 완료 · Repeater에서 외부 도구 메뉴 선택 · 서버 전송 없음");
  }

  void importParameters() {
    List<BountyToolBridge.Parameter> parameters = BountyToolBridge.parse(external.getText());
    for (var p : parameters)
      if (!WorkbenchScope.contains(api, p.url()))
        throw new IllegalArgumentException("결과에 SCOPE 밖 URL 포함 · 전체 가져오기 중단");
    String source = tool.getSelectedItem().toString().split(" · ")[0];
    activity.submit(
        () -> {
          for (var p : parameters)
            activity.tracker.externalParameter(p.method(), p.url(), p.location(), p.name(), source);
        });
    state.setText("외부 결과 " + parameters.size() + "개 전달 · 발견 상태 · 검증 완료 아님");
  }

  void refreshActions() {
    flowRun.setEnabled(!running);
    flowStop.setEnabled(running && runningCancellable);
    flowCapture.setEnabled(!running && flowModel.getRowCount() > 0);
    evidencePreview.setEnabled(evidenceModel.getRowCount() > 0);
    evidenceSave.setEnabled(!running && evidenceModel.getRowCount() > 0);
    evidenceClear.setEnabled(!running && (evidenceModel.getRowCount() > 0 || browserImage != null));
    int[] selected = evidenceTable.getSelectedRows();
    boolean editable = !closed.get() && !running;
    evidenceRemove.setEnabled(editable && selected.length > 0);
    evidenceUp.setEnabled(editable && canMoveEvidence(-1));
    evidenceDown.setEnabled(editable && canMoveEvidence(1));
    evidenceUndoAction.setEnabled(editable && evidenceUndo != null);
    evidenceCount.setText("증거 " + evidenceModel.getRowCount() + " / 32개 · 선택 " + selected.length + "개");
  }

  JPanel workflowContent() {
    JPanel p = new JPanel(new BorderLayout(8, 8));
    JButton append = action("선택 요청 추가", this::appendStep);
    append.setToolTipText("트래픽에서 선택한 요청 1개를 업무의 마지막 단계에 추가합니다.");
    flowRun.setName("wb.bounty.flow.run");
    flowStop.setName("wb.bounty.flow.stop");
    flowCapture.setName("wb.bounty.flow.evidence");
    WorkbenchUi.primary(flowRun);
    p.add(
        WorkbenchUi.row(append, flowSettings.button(), flowRun, flowStop, flowCapture),
        BorderLayout.NORTH);
    JSplitPane inputs =
        WorkbenchUi.split(
            JSplitPane.VERTICAL_SPLIT,
            WorkbenchUi.card("요청 단계 · ===STEP=== 줄로 구분", packetScroll(workflow)),
            WorkbenchUi.card("JSON 변수 추출 · 1.id=$.id → {{id|URL}}", new JScrollPane(extract)),
            .73);
    inputs.setName("wb.bounty.flow.inputs");
    workflowDetail.setEditable(false);
    JSplitPane results =
        WorkbenchUi.split(
            JSplitPane.VERTICAL_SPLIT,
            WorkbenchUi.card("단계별 실행 결과", new JScrollPane(flowTable)),
            WorkbenchUi.card("선택 단계 · 요청/응답", packetScroll(workflowDetail)),
            .40);
    results.setName("wb.bounty.flow.results");
    flowTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    flowTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting() && flowTable.getSelectedRow() >= 0) {
                var r =
                    flowResults.get(flowTable.convertRowIndexToModel(flowTable.getSelectedRow()));
                workflowDetail.setText(
                    r.state()
                        + "\nREQUEST\n"
                        + r.request()
                        + "\nRESPONSE\n"
                        + Objects.toString(r.response(), "응답 없음"));
                workflowDetail.setCaretPosition(0);
              }
            });
    int[] widths = {45, 70, 230, 55, 190};
    for (int i = 0; i < widths.length; i++)
      flowTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    flowTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    JSplitPane body = WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT, inputs, results, .52);
    body.setName("wb.bounty.flow.body");
    p.add(body);
    return p;
  }

  JPanel flowSettingsContent() {
    return TargetWindow.options(
        WorkbenchUi.card("단계 기대 조건 · 1.HTTP=200 / 2.$.state=READY", new JScrollPane(flowChecks)),
        WorkbenchUi.row(new JLabel("Origin"), flowOrigin),
        WorkbenchUi.row(
            flowWrites, new JLabel("대기(초)"), flowTimeout, new JLabel("총 요청 한도"), flowBudget),
        new JLabel("최대 8단계 · 같은 Origin/SCOPE · 리다이렉트 없음 · 실패 시 후속 중단"),
        new JLabel("{{id|URL}} URL 인코딩 / {{id|JSON}} JSON 값 / {{id|RAW}} 원문"));
  }

  void appendStep() {
    if (running) throw new IllegalArgumentException("작업 종료 후 편집하세요.");
    var rr = one();
    URI u = URI.create(rr.request().url());
    if (workflow.getText().isBlank())
      flowOrigin.setText(u.getScheme() + "://" + u.getRawAuthority());
    workflow.append(
        (workflow.getText().isBlank() ? "" : "\n===STEP===\n") + rr.request().toString());
    state.setText("단계 추가 · 변수/인증/최종 상태 확인 후 순차 실행");
  }

  void runFlow() {
    var plan =
        BountyWorkflow.parse(
            flowOrigin.getText().strip(),
            workflow.getText(),
            extract.getText(),
            flowWrites.isSelected(),
            (Integer) flowTimeout.getValue(),
            (Integer) flowBudget.getValue());
    var checks = BountyWorkflow.checks(plan, flowChecks.getText());
    if (running) throw new IllegalArgumentException("진행 중");
    flowResults.clear();
    flowModel.setRowCount(0);
    state.setText("순차 실행 중");
    task("여러 요청 재현", true,
        () -> {
          try {
            BountyWorkflow.run(
                api,
                plan,
                cancel,
                r ->
                    later(
                        () -> {
                          flowResults.add(r);
                          flowModel.addRow(
                              new Object[] {
                                r.step(),
                                r.request().method(),
                                Core.safeUrl(r.request().url()),
                                r.response() == null ? "없음" : r.response().statusCode(),
                                r.state()
                              });
                          state.setText(r.state());
                        }),
                checks);
          } catch (Exception e) {
            throw new IllegalArgumentException("워크플로 중단 · " + e.getMessage());
          }
        });
  }

  JPanel evidenceContent() {
    JPanel p = new JPanel(new BorderLayout(6, 6));
    JPanel top = new JPanel();
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    evidenceTitle.setColumns(24);
    includeBodies.setText("JSON/폼 본문 포함");
    includeBodies.setToolTipText("JSON/폼 본문을 증거에 포함합니다. 민감 키는 가리지만 직접 작성한 영향·재현 문장은 저장 전에 확인하세요.");
    top.add(WorkbenchUi.row(new JLabel("제목"), evidenceTitle, includeBodies,
        action("가림 설정", evidenceRedactionSettings::open)));
    evidenceSave.setName("wb.bounty.evidence.save");
    evidencePreview.setName("wb.bounty.evidence.preview");
    evidenceClear.setName("wb.bounty.evidence.clear");
    WorkbenchUi.primary(evidenceSave);
    top.add(
        WorkbenchUi.row(
            action("트래픽 추가", this::captureSelected),
            action("권한 비교 추가", this::captureAuth),
            action("업무 결과 추가", this::captureFlow),
            new JSeparator(SwingConstants.VERTICAL),
            evidencePreview,
            evidenceSave,
            evidenceClear));
    evidenceTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    evidenceTable.setToolTipText("Ctrl/Shift로 여러 증거 선택 · 화면 순서대로 ZIP에 저장합니다.");
    evidenceRemove.setName("wb.bounty.evidence.remove");
    evidenceUp.setName("wb.bounty.evidence.up");
    evidenceDown.setName("wb.bounty.evidence.down");
    evidenceUndoAction.setName("wb.bounty.evidence.undo");
    evidenceCount.setName("wb.bounty.evidence.count");
    evidenceUndoAction.setToolTipText("마지막 제거·순서 변경 1회 복구 · 새 증거 추가·비우기·프로젝트 변경 시 해제");
    top.add(WorkbenchUi.row(evidenceRemove, evidenceUp, evidenceDown, evidenceUndoAction, evidenceCount));
    p.add(top, BorderLayout.NORTH);
    for (JTextArea area : List.of(impact, repro)) {
      area.setLineWrap(true);
      area.setWrapStyleWord(true);
      area.putClientProperty("wb.prose", true);
    }
    JSplitPane text =
        WorkbenchUi.split(
            JSplitPane.HORIZONTAL_SPLIT,
            WorkbenchUi.card("실제 영향 · 직접 작성", new JScrollPane(impact)),
            WorkbenchUi.card("재현 절차 · 직접 작성", new JScrollPane(repro)),
            .5);
    text.setName("wb.bounty.evidence.notes");
    JSplitPane body =
        WorkbenchUi.split(JSplitPane.VERTICAL_SPLIT, new JScrollPane(evidenceTable), text, .5);
    body.setName("wb.bounty.evidence.body");
    p.add(body);
    int[] widths = {130, 75, 380, 240};
    for (int i = 0; i < widths.length; i++)
      evidenceTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    return p;
  }

  JPanel evidenceRedactionContent() {
    evidenceExtraNames.setName("wb.bounty.evidence.redaction.names");
    evidenceExtraNames.setToolTipText("헤더·JSON 중첩 키·폼 이름에 대소문자 구분 없이 적용합니다. 값이나 토큰을 입력하지 마세요.");
    return TargetWindow.options(
        WorkbenchUi.row(new JLabel("추가 가림 이름"), evidenceExtraNames),
        new JLabel("쉼표로 구분 · 예: customerCode, otp, X-Custom-Auth"),
        new JLabel("기본 민감 키와 지정한 이름의 값은 본문·반사 내용에서도 가립니다."),
        new JLabel("이미지와 이름이 알려지지 않은 개인정보는 가림 미리보기에서 직접 확인하세요."));
  }

  Set<String> evidenceRedactedNames() {
    Set<String> names = new LinkedHashSet<>(evidenceHeaders);
    names.addAll(BountyEvidence.extraNames(evidenceExtraNames.getText()));
    return Set.copyOf(names);
  }

  void remember(HttpRequestResponse rr, String label, String note) {
    remember(rr.request(), rr.response(), label, note);
  }

  void remember(
      burp.api.montoya.http.message.requests.HttpRequest q,
      burp.api.montoya.http.message.responses.HttpResponse response,
      String label,
      String note) {
    remember(q, response, label, note, activity.tracker.assessments.match(q));
  }

  void remember(
      burp.api.montoya.http.message.requests.HttpRequest q,
      burp.api.montoya.http.message.responses.HttpResponse response,
      String label,
      String note,
      String account) {
    if (closed.get()) throw new IllegalArgumentException("확장이 종료되었습니다.");
    if (running) throw new IllegalArgumentException("진행 중 작업이 끝난 뒤 증거를 추가하세요.");
    ensureEvidenceProject();
    if (evidence.size() >= 32) throw new IllegalArgumentException("증거 요청 최대 32개");
    if (q == null) throw new IllegalArgumentException("요청 없음");
    Set<String> names = new LinkedHashSet<>(), secrets = new LinkedHashSet<>();
    long incomingCharacters = 0;
    for (var h : q.headers())
      if (auth.authNames.contains(h.name().toLowerCase(Locale.ROOT))
          || AuthComparePanel.CSRF.contains(h.name().toLowerCase(Locale.ROOT))
          || BountyEvidence.SECRET.matcher(h.name()).find()) {
        String name = h.name().toLowerCase(Locale.ROOT), value = h.value();
        if (names.add(name)) incomingCharacters += name.length();
        if (value != null && secrets.add(value)) incomingCharacters += value.length();
        if (names.size() > EVIDENCE_REDACTION_VALUES || secrets.size() > EVIDENCE_REDACTION_VALUES
            || incomingCharacters > EVIDENCE_REDACTION_CHARACTERS)
          throw new IllegalArgumentException("추가할 증거의 가림 정보가 보관 한도를 초과했습니다. 필요한 원본을 나누어 저장하세요. 기존 증거는 유지합니다.");
      }
    retainEvidenceRedactions(names, secrets);
    evidenceUndo = null;
    compactEvidenceStore();
    // The packet is copied once in bounded chunks. No full request/response String survives
    // capture.
    var stored = evidenceStore.append(q, response);
    if (stored.response().length() > AuthComparePanel.MESSAGE_LIMIT)
      note +=
          " · 원본 HTTP 응답 "
              + stored.response().length()
              + " bytes · SHA-256 "
              + stored.response().sha256();
    BountyEvidence.Item item =
        new BountyEvidence.Item(label, q.method(), q.url(), note, account, stored);
    evidence.add(item);
    evidenceModel.addRow(new Object[] {label, q.method(), Core.safeUrl(q.url()), note});
  }

  int evidenceCount() { return evidence.size(); }

  /** Removed credentials may still be reflected elsewhere, so keep the union with a hard budget. */
  void retainEvidenceRedactions(Collection<String> names, Collection<String> secrets) {
    Set<String> nextNames = new LinkedHashSet<>(evidenceHeaders), nextSecrets = new LinkedHashSet<>(evidenceSecrets);
    long characters = nextNames.stream().mapToLong(String::length).sum()
        + nextSecrets.stream().mapToLong(String::length).sum();
    for (int kind = 0; kind < 2; kind++)
      for (String value : kind == 0 ? names : secrets) {
        if (value == null || value.isEmpty()) continue;
        Set<String> target = kind == 0 ? nextNames : nextSecrets;
        if (target.add(value)) characters += value.length();
        if (target.size() > EVIDENCE_REDACTION_VALUES || characters > EVIDENCE_REDACTION_CHARACTERS)
          throw new IllegalArgumentException("증거 가림 정보 보관 한도에 도달했습니다. 현재 증거를 저장하고 비운 뒤 새 묶음을 시작하세요. 기존 가림 정보는 유지합니다.");
      }
    evidenceHeaders.clear(); evidenceHeaders.addAll(nextNames);
    evidenceSecrets.clear(); evidenceSecrets.addAll(nextSecrets);
  }

  boolean hasSavedEvidence() { return successfulEvidenceSaves.get() > 0; }

  /** Integration calls this when the active Burp project changes. No undo may cross this boundary. */
  void resetEvidenceProject() {
    evidenceEpoch.incrementAndGet();
    evidenceUndo = null;
    evidenceCompactionNeeded = false;
    evidenceStore.close();
    evidenceStore = new EvidencePacketStore();
    evidence.clear(); evidenceExecutions.clear(); evidenceExecutionSources.clear(); evidenceExecutionParts.clear(); evidenceModel.setRowCount(0);
    evidenceHeaders.clear(); evidenceSecrets.clear();
    browserImage = null; browserImageSha256 = null;
    successfulEvidenceSaves.set(0);
    evidenceProject = WorkspaceResume.projectIdentity(api);
    refreshActions();
  }

  private void ensureEvidenceProject() {
    String project = WorkspaceResume.projectIdentity(api);
    if (!Objects.equals(project, evidenceProject)) {
      resetEvidenceProject();
      throw new IllegalArgumentException("프로젝트가 변경되어 이전 증거와 실행 취소를 비웠습니다. 현재 원본을 다시 선택하세요.");
    }
  }

  private void requireEvidenceEdit() {
    if (closed.get()) throw new IllegalArgumentException("확장이 종료되었습니다.");
    if (running) throw new IllegalArgumentException("저장 작업이 끝난 뒤 증거를 편집하세요.");
    ensureEvidenceProject();
  }

  private List<Integer> selectedEvidenceRows() {
    return Arrays.stream(evidenceTable.getSelectedRows()).filter(row -> row >= 0 && row < evidenceTable.getRowCount())
        .map(evidenceTable::convertRowIndexToModel)
        .sorted().boxed().toList();
  }

  private void rememberEvidenceEdit() {
    evidenceUndo = new EvidenceEdit(List.copyOf(evidence), Map.copyOf(evidenceExecutions),
        Map.copyOf(evidenceExecutionParts), selectedEvidenceRows(), evidenceStore, evidenceProject, evidenceCompactionNeeded);
  }

  private void rebuildEvidenceRows(Collection<String> selectedIds) {
    // Edited visible order is the archive order, even if a host has installed a column sorter.
    if (evidenceTable.getRowSorter() != null) evidenceTable.getRowSorter().setSortKeys(List.of());
    evidenceModel.setRowCount(0);
    for (var item : evidence)
      evidenceModel.addRow(new Object[] {item.label(), item.method(), Core.safeUrl(item.url()), item.note()});
    evidenceTable.clearSelection();
    for (int row = 0; row < evidence.size(); row++)
      if (selectedIds.contains(evidence.get(row).evidenceId())) {
        int view = evidenceTable.convertRowIndexToView(row);
        if (view >= 0) evidenceTable.addRowSelectionInterval(view, view);
      }
    refreshActions();
  }

  void removeSelectedEvidence() {
    requireEvidenceEdit();
    List<Integer> selected = selectedEvidenceRows();
    if (selected.isEmpty()) throw new IllegalArgumentException("제거할 증거를 선택하세요.");
    rememberEvidenceEdit();
    for (int i = selected.size() - 1; i >= 0; i--) {
      var removed = evidence.remove((int) selected.get(i));
      evidenceExecutions.remove(removed.evidenceId());
      evidenceExecutionParts.remove(removed.evidenceId());
    }
    // Keep the accumulated redaction names/values: a removed packet's credential can still be
    // reflected in another retained packet or a user-written note.
    evidenceCompactionNeeded = true;
    rebuildEvidenceRows(List.of());
    if (!evidence.isEmpty()) {
      int next = Math.min(selected.get(0), evidence.size() - 1);
      evidenceTable.setRowSelectionInterval(next, next);
    }
    state.setText("선택 증거 " + selected.size() + "개 제거 · 실행 취소로 복구 · 원래 트래픽/실행 결과 유지");
  }

  private boolean canMoveEvidence(int direction) {
    Set<Integer> rows = new HashSet<>(selectedEvidenceRows());
    return rows.stream().anyMatch(row -> row + direction >= 0 && row + direction < evidence.size()
        && !rows.contains(row + direction));
  }

  void moveSelectedEvidence(int direction) {
    requireEvidenceEdit();
    if (direction != -1 && direction != 1) throw new IllegalArgumentException("증거 이동 방향 오류");
    if (!canMoveEvidence(direction)) throw new IllegalArgumentException("이 방향으로 이동할 선택 증거가 없습니다.");
    List<String> selected = selectedEvidenceRows().stream().map(row -> evidence.get(row).evidenceId()).toList();
    rememberEvidenceEdit();
    Set<String> chosen = new HashSet<>(selected);
    for (int i = direction < 0 ? 1 : evidence.size() - 2;
        direction < 0 ? i < evidence.size() : i >= 0; i -= direction)
      if (chosen.contains(evidence.get(i).evidenceId()) && !chosen.contains(evidence.get(i + direction).evidenceId()))
        Collections.swap(evidence, i, i + direction);
    rebuildEvidenceRows(selected);
    state.setText("선택 증거 " + selected.size() + "개 " + (direction < 0 ? "위로" : "아래로") + " 이동 · ZIP 저장 순서에 반영");
  }

  void undoEvidenceEdit() {
    requireEvidenceEdit();
    EvidenceEdit previous = evidenceUndo;
    if (previous == null || previous.store() != evidenceStore || !previous.project().equals(evidenceProject))
      throw new IllegalArgumentException("복구할 증거 편집이 없습니다.");
    evidenceUndo = null;
    evidence.clear(); evidence.addAll(previous.items());
    evidenceExecutions.clear(); evidenceExecutions.putAll(previous.executions());
    evidenceExecutionParts.clear(); evidenceExecutionParts.putAll(previous.parts());
    evidenceCompactionNeeded = previous.compact();
    var ids = previous.selected().stream().map(row -> evidence.get(row).evidenceId()).toList();
    rebuildEvidenceRows(ids);
    state.setText("마지막 증거 편집 복구 · 원본·실행 연결과 순서 유지");
  }

  private void compactEvidenceStore() {
    if (!evidenceCompactionNeeded) return;
    EvidencePacketStore next = new EvidencePacketStore();
    List<BountyEvidence.Item> copied = new ArrayList<>();
    try {
      for (var item : evidence) copied.add(item.copyTo(next));
    } catch (RuntimeException error) { next.close(); throw error; }
    EvidencePacketStore previous = evidenceStore;
    evidenceStore = next;
    evidence.clear(); evidence.addAll(copied);
    evidenceCompactionNeeded = false;
    previous.close();
    evidenceExecutionSources.keySet().retainAll(new HashSet<>(evidenceExecutions.values()));
    evidenceExecutionParts.keySet().retainAll(new HashSet<>(evidenceExecutions.keySet()));
  }

  void captureObserved(List<HttpRequestResponse> packets, String note) {
    if (closed.get()) throw new IllegalArgumentException("확장이 종료되었습니다.");
    var checked = EvidenceLinks.checked(api, packets);
    if (evidence.size() + checked.size() > 32)
      throw new IllegalArgumentException("증거 요청 최대 32개 · 기존 증거를 저장/비우세요.");
    if (note == null || note.length() > 4096) throw new IllegalArgumentException("증거 설명 크기 제한");
    for (int i = 0; i < checked.size(); i++) remember(checked.get(i), "연결 원본 " + (i + 1), note);
    pages.setSelectedIndex(3);
    state.setText("원본 " + checked.size() + "개 증거 추가 · 가림 미리보기 후 저장하세요.");
  }

  String evidencePreview() {
    return BountyEvidence.preview(
        evidenceTitle.getText(),
        impact.getText(),
        repro.getText(),
        List.copyOf(evidence),
        evidenceRedactedNames(),
        Set.copyOf(evidenceSecrets),
        includeBodies.isSelected(),
        browserImage != null);
  }

  String browserProofHash(Path image, String expected) {
    try {
      byte[] data = BountyEvidence.imageBytes(image, expected);
      BountyEvidence.validateImageBytes(data);
      String hash = Core.hash(data);
      if (browserImageSha256 != null && !browserImageSha256.equals(hash))
        throw new IllegalArgumentException("이미 연결된 이미지와 다른 근거입니다. 기존 증거를 저장하고 비운 뒤 추가하세요.");
      return hash;
    } catch (Exception ex) {
      throw new IllegalArgumentException(ex.getMessage());
    }
  }

  void attachBrowserEvidence(Path image, String expected) {
    requireEvidenceEdit();
    String hash = browserProofHash(image, expected);
    evidenceUndo = null;
    browserImage = image;
    browserImageSha256 = hash;
    refreshActions();
    state.setText("브라우저 이미지 연결 · ZIP 저장 시 SHA-256 다시 확인");
  }

  void captureObservedWithImage(
      List<HttpRequestResponse> packets, String note, Path image, String expected) {
    String hash = browserProofHash(image, expected);
    captureObserved(packets, note);
    browserImage = image;
    browserImageSha256 = hash;
    refreshActions();
  }

  void previewEvidence() {
    if (evidence.isEmpty()) throw new IllegalArgumentException("증거를 먼저 추가하세요.");
    JTextArea text = area(24);
    text.setEditable(false);
    text.setText(evidencePreview());
    text.setCaretPosition(0);
    JDialog dialog =
        new JDialog(
            SwingUtilities.getWindowAncestor(this), "증거 가림 미리보기", Dialog.ModalityType.MODELESS);
    previewWindows.add(dialog);
    dialog.addWindowListener(
        new java.awt.event.WindowAdapter() {
          public void windowClosed(java.awt.event.WindowEvent e) {
            previewWindows.remove(dialog);
          }
        });
    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    JPanel content = new JPanel(new BorderLayout(6, 6));
    content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    content.add(new JLabel("현재 입력의 저장 미리보기 · 이미지/직접 작성 내용의 개인정보는 직접 확인하세요."), BorderLayout.NORTH);
    content.add(packetScroll(text));
    WorkbenchUi.style(content);
    content.add(WorkbenchUi.row(action("닫기", dialog::dispose)), BorderLayout.SOUTH);
    dialog.setContentPane(content);
    dialog.setSize(900, 700);
    dialog.setLocationRelativeTo(this);
    dialog.setVisible(true);
  }

  void captureSelected() {
    remember(one(), "선택 트래픽", "직접 재현 결과를 작성하세요.");
    state.setText("증거 추가 · 저장 전 영향/재현 절차 확인");
  }

  void captureAuth() {
    var r = auth.current();
    if (r == null) throw new IllegalArgumentException("권한 비교 History에서 결과를 선택하세요.");
    var execution = activity.tracker.assessments.execution(r.executionId);
    if (execution != null && execution.state() == ExecutionLedger.State.RUNNING)
      throw new IllegalArgumentException("비교 종료 후 증거를 추가하세요. 진행 중 패킷을 완료 근거로 고정하지 않습니다.");
    var planned = AuthEvidenceSnapshot.capture(r);
    if (evidence.size() + planned.size() > 32)
      throw new IllegalArgumentException("증거 요청 최대 32개 · 이번 비교의 원본/계정/조회 "
          + planned.size() + "개를 함께 추가할 공간이 필요합니다.");
    Set<String> profileSecrets = new LinkedHashSet<>();
    for (var c : r.context.profiles()) if (c != null) profileSecrets.addAll(c.headers().values());
    retainEvidenceRedactions(r.context.names(), profileSecrets);
    Set<String> originalPackets = new LinkedHashSet<>();
    int sourceIndex = 0;
    for (var entry : planned) {
      String account = entry.account();
      if (entry.label().equals("권한 비교 원본") || entry.label().equals("권한 비교 기준 재조회"))
        account = activity.tracker.assessments.match(entry.request());
      String provenance = execution == null ? "" : "\n\n[작업·원본·실행 연결]\n"
          + activity.tracker.assessments.executionSummary(r.executionId);
      remember(entry.request(), entry.response(), entry.label(), entry.note() + provenance, account);
      if (execution != null) {
        var item = evidence.get(evidence.size() - 1);
        evidenceExecutions.put(item.evidenceId(), r.executionId);
        // Source position identifies original/baseline/account/before/after observations even
        // when two distinct roles happened to have byte-identical HTTP packets.
        String sourcePart = sourceIndex + ":" + item.packetId();
        evidenceExecutionParts.put(item.evidenceId(), sourcePart);
        originalPackets.add(sourcePart);
      }
      sourceIndex++;
    }
    if (execution != null) evidenceExecutionSources.put(r.executionId, Set.copyOf(originalPackets));
    state.setText("선택 권한 비교 " + planned.size() + "개 증거 추가 · 실행 당시 설정·판정·조회 포함");
  }

  void captureFlow() {
    if (running) throw new IllegalArgumentException("작업 종료 후 증거를 추가하세요.");
    if (flowResults.isEmpty()) throw new IllegalArgumentException("업무 흐름 결과 없음");
    if (evidence.size() + flowResults.size() > 32)
      throw new IllegalArgumentException("증거 요청 최대 32개");
    for (var r : flowResults) remember(r.request(), r.response(), "업무 단계 " + r.step(), r.state());
    state.setText("업무 흐름 증거 추가");
  }

  void capturePayload(
      burp.api.montoya.http.HttpService service, List<kr.payloadrunner.Engine.Row> rows) {
    if (rows.isEmpty() || evidence.size() + rows.size() > 32)
      throw new IllegalArgumentException("증거 요청 최대 32개 · 기존 증거를 저장/비운 뒤 가져오세요.");
    List<burp.api.montoya.http.message.requests.HttpRequest> requests = new ArrayList<>();
    for (var row : rows) {
      var q =
          burp.api.montoya.http.message.requests.HttpRequest.httpRequest(
              service, burp.api.montoya.core.ByteArray.byteArray(row.request()));
      if (!WorkbenchScope.contains(api, q))
        throw new IllegalArgumentException("검사 증거에 SCOPE 밖 요청 포함 · 전체 가져오기 중단");
      requests.add(q);
    }
    for (int i = 0; i < rows.size(); i++) {
      var row = rows.get(i);
      var response =
          row.response() == null
              ? null
              : burp.api.montoya.http.message.responses.HttpResponse.httpResponse(
                  burp.api.montoya.core.ByteArray.byteArray(row.response().raw()));
      String note =
          row.verdict()
              + " · "
              + row.evidence()
              + (row.response() != null && row.response().truncated()
                  ? " · 응답 일부만 보존 · 완전한 재현 근거 아님"
                  : "");
      remember(requests.get(i), response, "페이로드 결과 " + row.index(), note);
      if (row.verdict().contains("후보"))
        activity.review(requests.get(i).method(), requests.get(i).url(), note);
    }
    state.setText("페이로드 기준·공격·대조 " + rows.size() + "개 증거 추가");
  }

  void clearEvidence() {
    if (closed.get()) throw new IllegalArgumentException("확장이 종료되었습니다.");
    if (running) throw new IllegalArgumentException("저장 작업 종료 후 증거를 비우세요.");
    evidenceUndo = null;
    evidenceCompactionNeeded = false;
    evidenceEpoch.incrementAndGet();
    evidenceStore.close();
    evidenceStore = new EvidencePacketStore();
    evidence.clear();
    evidenceExecutions.clear();
    evidenceExecutionSources.clear();
    evidenceExecutionParts.clear();
    evidenceModel.setRowCount(0);
    evidenceHeaders.clear();
    evidenceSecrets.clear();
    browserImage = null;
    browserImageSha256 = null;
    refreshActions();
    state.setText("증거 비움 · 원래 트래픽과 비교 결과 유지");
  }

  List<Map<String, Object>> oastSnapshot() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (int i = 0; i < oastModel.getRowCount(); i++)
      out.add(
          Map.of(
              "id",
              Objects.toString(oastModel.getValueAt(i, 0), ""),
              "type",
              Objects.toString(oastModel.getValueAt(i, 1), ""),
              "time",
              Objects.toString(oastModel.getValueAt(i, 2), ""),
              "method",
              Objects.toString(oastModel.getValueAt(i, 3), ""),
              "url",
              Objects.toString(oastModel.getValueAt(i, 4), ""),
              "note",
              Objects.toString(oastModel.getValueAt(i, 5), "")));
    return List.copyOf(out);
  }

  void saveEvidence() {
    requireEvidenceEdit();
    if (evidence.isEmpty()) throw new IllegalArgumentException("증거를 먼저 추가하세요.");
    JFileChooser f = new JFileChooser();
    f.setSelectedFile(new java.io.File("workbench-evidence.zip"));
    if (f.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
    Path path = f.getSelectedFile().toPath();
    if (Files.exists(path)
        && JOptionPane.showConfirmDialog(this, "기존 ZIP을 덮어쓸까요?", "증거 저장", JOptionPane.YES_NO_OPTION)
            != JOptionPane.YES_OPTION) return;
    List<BountyEvidence.Item> items = List.copyOf(evidence);
    Set<String> headers = evidenceRedactedNames(), secrets = Set.copyOf(evidenceSecrets);
    String title = evidenceTitle.getText(),
        effect = impact.getText(),
        steps = repro.getText(),
        coverage = activity.tracker.save();
    boolean bodies = includeBodies.isSelected();
    Path image = browserImage;
    String imageHash = browserImageSha256;
    var oast = oastSnapshot();
    task("증거 ZIP 저장", false,
        () -> {
          try {
            writeEvidence(
                path, title, effect, steps, items, headers, secrets, bodies, coverage, image, oast,
                imageHash);
            later(() -> state.setText("증거 ZIP 저장 완료 · 인증/쿼리 값 가림 · 재현에는 로컬 원본 필요"));
          } catch (Exception e) {
            throw new IllegalArgumentException("증거 저장 실패 · " + e.getMessage());
          }
        });
  }

  void writeEvidence(
      Path path,
      String title,
      String effect,
      String steps,
      List<BountyEvidence.Item> items,
      Set<String> headers,
      Set<String> secrets,
      boolean bodies,
      String coverage,
      Path image,
      List<Map<String, Object>> oast)
      throws Exception {
    writeEvidence(
        path, title, effect, steps, items, headers, secrets, bodies, coverage, image, oast, null);
  }

  void writeEvidence(
      Path path,
      String title,
      String effect,
      String steps,
      List<BountyEvidence.Item> items,
      Set<String> headers,
      Set<String> secrets,
      boolean bodies,
      String coverage,
      Path image,
      List<Map<String, Object>> oast,
      String expectedImageHash)
      throws Exception {
    String savingProject = evidenceProject;
    long savingEpoch = evidenceEpoch.get();
    BountyEvidence.save(
        path,
        title,
        effect,
        steps,
        items,
        headers,
        secrets,
        bodies,
        executionCoverage(coverage, items),
        image,
        oast,
        expectedImageHash);
    String id = BountyEvidence.fileHash(path);
    // A completed file still belongs to the project selected when the save started. Never attach
    // its proof or readiness flag to a newly opened project or an unloaded workspace.
    if (closed.get() || savingEpoch != evidenceEpoch.get() || !Objects.equals(savingProject, evidenceProject)
        || !Objects.equals(savingProject, WorkspaceResume.projectIdentity(api))) return;
    successfulEvidenceSaves.incrementAndGet();
    activity.evidenceSaved(id, path.getFileName().toString(), items, path);
    for (var entry : includedExecutionPackets(items).entrySet()) {
      Set<String> required = evidenceExecutionSources.get(entry.getKey());
      if (required != null && !required.isEmpty() && entry.getValue().containsAll(required))
        activity.tracker.assessments.executionEvidenceSaved(entry.getKey(), id);
    }
  }

  Map<String, Set<String>> includedExecutionPackets(List<BountyEvidence.Item> items) {
    Map<String, Set<String>> included = new LinkedHashMap<>();
    for (var item : items) {
      String execution = evidenceExecutions.get(item.evidenceId());
      String part = evidenceExecutionParts.get(item.evidenceId());
      if (execution != null) included.computeIfAbsent(execution, ignored -> new LinkedHashSet<>())
          .add(part == null ? "unverified:" + item.evidenceId() : part);
    }
    return included;
  }

  String executionCoverage(String coverage, List<BountyEvidence.Item> items) {
    Object parsed = MinerJson.parse(coverage, 32 * 1024 * 1024, 3000000);
    if (!(parsed instanceof Map<?, ?> existing)) throw new IllegalArgumentException("점검 기록 JSON 형식을 확인하세요.");
    Map<String, Object> result = new LinkedHashMap<>();
    existing.forEach((key, value) -> result.put(key.toString(), value));
    List<Map<String, String>> links = new ArrayList<>();
    for (var item : items) {
      String execution = evidenceExecutions.get(item.evidenceId());
      if (execution != null) links.add(Map.of("evidenceItemId", item.evidenceId(), "executionId", execution));
    }
    result.put("executionEvidenceItems", links);
    List<Map<String, Object>> completeness = new ArrayList<>();
    includedExecutionPackets(items).forEach((execution, included) -> {
      Set<String> required = evidenceExecutionSources.get(execution);
      completeness.add(Map.of("executionId", execution, "includedOriginalPackets", included.size(),
          "expectedOriginalPackets", required == null ? 0 : required.size(),
          "complete", required != null && !required.isEmpty() && included.containsAll(required)));
    });
    result.put("executionEvidenceCompleteness", completeness);
    return MetadataExport.json(result);
  }

  JPanel browserContent() {
    JTextArea help = area(9);
    help.setEditable(false);
    help.setLineWrap(true);
    help.setText(
        "1. 선택 요청을 Repeater로 준비하고 실제 응답의 출력 맥락을 확인합니다.\n"
            + "2. Burp의 브라우저에서 동일 기능을 재현합니다. JS/DOM, iframe, 이벤트, 저장 후 조회 결과를 확인합니다.\n"
            + "3. 개발자도구에서 실행·출처·시간을 확인하고 재현 절차/영향에 기록합니다.\n"
            + "4. 민감 정보가 가려진 PNG/JPEG를 첨부한 뒤 증거 ZIP을 저장합니다.\n\n"
            + "정적 분석·마커 재출력만으로 XSS 실행을 확정하지 않습니다. 브라우저 엔진을 중복 구현하지 않고 Burp 브라우저의 수동 검증 근거를 증거 묶음에"
            + " 연결합니다.");
    JPanel p = new JPanel(new BorderLayout(8, 8));
    p.add(new JScrollPane(help));
    p.add(
        WorkbenchUi.row(
            action("선택 요청 → Repeater", this::handoff),
            action(
                "브라우저 증거 이미지 첨부",
                () -> {
                  JFileChooser f = new JFileChooser();
                  if (f.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                    Path image = f.getSelectedFile().toPath();
                    try {
                      attachBrowserEvidence(image, null);
                      state.setText("첨부: " + image.getFileName() + " · 증거 ZIP 저장 시 포함");
                    } catch (Exception ex) {
                      throw new IllegalArgumentException(ex.getMessage());
                    }
                  }
                })),
        BorderLayout.SOUTH);
    return p;
  }

  JPanel oastContent() {
    JPanel p = new JPanel(new BorderLayout(8, 8));
    JPanel top = new JPanel();
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    payload.setEditable(false);
    JButton generate = action("콜백 ID 발급", this::generateOast);
    generate.setToolTipText("선택한 요청과 연결할 Collaborator 주소를 발급합니다. 대상 서버로 요청을 보내지 않습니다.");
    WorkbenchUi.primary(generate);
    top.add(WorkbenchUi.row(generate, action("콜백 조회", this::pollOast)));
    JPanel address = new JPanel(new BorderLayout(6, 0));
    address.add(new JLabel("콜백 주소"), BorderLayout.WEST);
    address.add(payload);
    address.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
    top.add(address);
    top.add(new JLabel("Pro Collaborator 연계 · 대상 요청은 자동 전송하지 않음 · 콜백 자체는 취약점 확정 아님"));
    p.add(top, BorderLayout.NORTH);
    p.add(new JScrollPane(oastTable));
    JPanel bottom = new JPanel(new BorderLayout());
    bottom.add(
        WorkbenchUi.card(
            "외부 OAST 결과 · ID [탭] DNS/HTTP/SMTP [탭] ISO 시각 [탭] METHOD [탭] URL",
            new JScrollPane(oastExternal)));
    bottom.add(WorkbenchUi.row(action("외부 콜백 결과 가져오기", this::importOast)), BorderLayout.SOUTH);
    p.add(bottom, BorderLayout.SOUTH);
    return p;
  }

  void generateOast() {
    var rr = one();
    String method = rr.request().method(), url = Core.safeUrl(rr.request().url());
    if (correlations.size() >= 50)
      throw new IllegalArgumentException("콜백 ID 최대 50개 · 새 점검은 확장 재로드");
    task(
        () -> {
          try {
            if (collaborator == null) {
              var service = api.collaborator();
              if (service == null)
                throw new IllegalArgumentException("Collaborator 사용 불가 · Pro 또는 외부 결과 가져오기 사용");
              collaborator = service.createClient();
            }
            var p = collaborator.generatePayload();
            if (p == null) throw new IllegalArgumentException("Collaborator 사용 불가");
            String id = p.id().toString(), address = p.toString();
            later(
                () -> {
                  correlations.put(id, new OastRequest(method, url));
                  payload.setText(address);
                  state.setText("ID 발급 · 해당 주소를 선택 요청의 입력값에 사용하세요 · 전송 없음");
                });
          } catch (Exception e) {
            throw new IllegalArgumentException(
                "Collaborator 발급 실패 · 사용 가능한 라이선스/서버 확인, 외부 결과 가져오기 가능");
          }
        });
  }

  void pollOast() {
    if (collaborator == null) throw new IllegalArgumentException("콜백 ID를 먼저 발급하세요.");
    Map<String, OastRequest> snapshot = Map.copyOf(correlations);
    task(
        () -> {
          try {
            List<Interaction> interactions = collaborator.getAllInteractions();
            if (interactions == null) throw new IllegalArgumentException("콜백 조회 실패");
            int n = 0;
            for (var i : interactions) {
              if (n++ >= 500 || closed.get()) break;
              String id = i.id().toString();
              OastRequest q = snapshot.get(id);
              if (q != null) {
                String type = i.type().toString(), time = i.timeStamp().toString();
                later(() -> oastRow(id, type, time, q));
              }
            }
            later(() -> state.setText("콜백 조회 완료 · 연관 요청/시각과 실제 영향 확인"));
          } catch (Exception e) {
            throw new IllegalArgumentException("콜백 조회 실패 · 서버/라이선스 확인");
          }
        });
  }

  void oastRow(String id, String type, String time, OastRequest q) {
    if (oastModel.getRowCount() >= 500) return;
    if (seenInteractions.add(id + "|" + type + "|" + time)) {
      oastModel.addRow(new Object[] {id, type, time, q.method, q.url, "상호작용 관측 · 실제 영향 수동 확인"});
      activity.review(q.method, q.url, "OAST " + type + " 상호작용 · 실제 영향 확인 필요");
    }
  }

  static boolean validTime(String value) {
    try {
      java.time.ZonedDateTime.parse(value);
      return true;
    } catch (Exception ex) {
      try {
        java.time.Instant.parse(value);
        return true;
      } catch (Exception ignored) {
        return false;
      }
    }
  }

  void importOast() {
    String text = oastExternal.getText();
    if (text.length() > 128 * 1024) throw new IllegalArgumentException("콜백 결과 크기 제한");
    List<String[]> rows = new ArrayList<>();
    for (String line : text.split("\\R")) {
      if (line.isBlank()) continue;
      String[] p = line.split("\t", -1);
      if (p.length != 5
          || !p[0].matches("[A-Za-z0-9._-]{1,128}")
          || !Set.of("DNS", "HTTP", "SMTP").contains(p[1])
          || p[2].length() > 64
          || !validTime(p[2])
          || !p[3].matches("GET|HEAD|OPTIONS|POST|PUT|PATCH|DELETE")
          || ActivityTracker.safeUrl(p[4]) == null
          || !WorkbenchScope.contains(api, p[4]))
        throw new IllegalArgumentException("외부 콜백 형식/Scope 오류 · 전체 가져오기 중단");
      rows.add(p);
      if (rows.size() > 500) throw new IllegalArgumentException("콜백 최대 500개");
    }
    if (rows.isEmpty()) throw new IllegalArgumentException("가져올 콜백 없음");
    for (String[] p : rows) oastRow(p[0], p[1], p[2], new OastRequest(p[3], Core.safeUrl(p[4])));
    state.setText("외부 콜백 메타데이터 가져오기 · 요청과의 연결은 제공한 URL 기준");
  }

  @Override
  public void close() {
    closed.set(true);
    evidenceEpoch.incrementAndGet();
    evidenceUndo = null;
    evidenceCompactionNeeded = false;
    successfulEvidenceSaves.set(0);
    for (var window : List.copyOf(previewWindows)) window.dispose();
    previewWindows.clear();
    cancel.set(true);
    worker.shutdownNow();
    evidenceStore.close();
    flowSettings.close();
    evidenceRedactionSettings.close();
    flowResults.clear();
    evidence.clear();
    evidenceExecutions.clear();
    evidenceExecutionSources.clear();
    evidenceExecutionParts.clear();
    evidenceSecrets.clear();
    evidenceHeaders.clear();
    correlations.clear();
    seenInteractions.clear();
    collaborator = null;
    browserImage = null;
    browserImageSha256 = null;
    workflow.setText("");
    extract.setText("");
    workflowDetail.setText("");
    external.setText("");
    oastExternal.setText("");
    flowModel.setRowCount(0);
    evidenceModel.setRowCount(0);
    oastModel.setRowCount(0);
  }
}
