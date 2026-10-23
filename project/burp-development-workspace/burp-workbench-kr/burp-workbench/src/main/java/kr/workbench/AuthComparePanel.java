package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.*;
import burp.api.montoya.http.message.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.table.*;
import javax.swing.text.DefaultHighlighter;

/** Auth snapshots, explicit Record, bounded queue, no global session mutation. */
public final class AuthComparePanel extends JPanel implements AutoCloseable {
  static final int LIMIT = 250, MESSAGE_LIMIT = 128 * 1024;
  static final Set<String>
      DEFAULT_AUTH =
          Set.of(
              "cookie",
              "authorization",
              "proxy-authorization",
              "x-auth-token",
              "x-api-key",
              "x-access-token"),
      CSRF = Set.of("x-csrf-token", "x-xsrf-token", "csrf-token", "x-csrftoken");
  static final int SLOTS=4, ORIGINAL=4;
  final MontoyaApi api;
  final Supplier<List<HttpRequestResponse>> selected;
  Supplier<List<HttpRequestResponse>> observedRequests;
  Supplier<List<DiskTrafficStore.PacketRef>> observedRequestRefs;
  public void onObservedRequestRefs(Supplier<List<DiskTrafficStore.PacketRef>> source) {
    observedRequestRefs = Objects.requireNonNull(source);
  }
  List<HttpRequestResponse> setupRequests() {
    if (!preparationSelection.isEmpty() && preparationRevision != workSelectionRevision())
      throw new IllegalArgumentException("준비 중 선택 원본이 변경되었습니다. 선택 요청 비교에서 준비 화면을 다시 여세요.");
    if (preparationSelection.isEmpty()) return selected.get();
    return preparationFocus >= 0 && preparationFocus < preparationSelection.size()
        ? List.of(preparationSelection.get(preparationFocus)) : preparationSelection;
  }
  List<HttpRequestResponse> preparationSelection = List.of();
  List<String> preparationIds = List.of();
  int preparationFocus = -1, preparationSlot = -1;
  long preparationFocusRevision;
  long preparationGeneration;
  long preparationRevision = -1;
  private WorkContext workContext;
  private AssessmentTracker executionTracker;
  private java.util.function.Function<HttpRequest, String> originalIdentity = request -> "";
  public void onExecutionTracker(AssessmentTracker tracker, java.util.function.Function<HttpRequest, String> originalId) {
    executionTracker = Objects.requireNonNull(tracker); originalIdentity = Objects.requireNonNull(originalId);
  }

  public void onWorkContext(WorkContext value) {
    if (workContext == value) return;
    workContext = Objects.requireNonNull(value);
    value.observeSettings(urlFilter, ignoredFields, ownerPath, importantFields, denialRules, policyRules,
        accessPolicy, responseDenialRules, unorderedArrays, writes, identityCookies, matrixRules, effectSettings,
        extra, largeFiles, mode);
    for (Component[] inputs : new Component[][]{profileNames, profileOwners, profileRoles, profileTenants,
        refreshEnabled, refreshUrls, refreshTokenPaths, refreshCsrfPaths, refreshIdentityPaths,
        refreshTokenHeaders, refreshPrefixes, refreshCsrfHeaders}) value.observeSettings(inputs);
  }

  long workSelectionRevision() { return workContext == null ? -1 : workContext.snapshot().selectionRevision(); }

  long workConfigurationRevision() { return workContext == null ? -1 : workContext.snapshot().configurationRevision(); }
  void refreshPreparedConfiguration() { try { context(); } catch (IllegalArgumentException error) { state.setText("설정 저장 · 비교 준비에서 보완: " + error.getMessage()); } }

  void workConfigurationEdited() { if (workContext != null) workContext.configurationEdited(); }

  /** Retained recent packets only; this source must never issue network requests. */
  public void onObservedRequests(Supplier<List<HttpRequestResponse>> source) {
    observedRequests = Objects.requireNonNull(source);
  }

  void prefillSelectedPolicy() {
    editable(); matrixEditor.snapshot();
    String next = AuthSetupSuggestions.prefillPolicy(matrixRules.getText(), List.copyOf(setupRequests()));
    if (!next.equals(matrixRules.getText())) matrixRules.setText(next);
    matrixEditor.load();
    matrixEditor.feedback.setText("선택 요청의 미설정 메서드·경로 사전 입력 · 계정별 허용/차단은 업무 기준으로 지정하세요.");
  }
  final DefaultTableModel candidateModel = model("NO", "사용자 계정", "역할", "Origin", "인증 헤더 / 값", "최근 탐지"),
      historyModel = model("NO", "URL", "계정 1", "계정 2", "계정 3", "실행 상태", "메서드", "종합 결과", "비로그인");
  final JTable candidates = new AuthLayout.Table(candidateModel),
      history =
          new AuthLayout.Table(historyModel) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
              Component component = super.prepareRenderer(renderer, row, column);
              if (component instanceof JLabel cell) {
                cell.setIcon(null); cell.setToolTipText(null); cell.getAccessibleContext().setAccessibleDescription(null);
                cell.setForeground(WorkbenchUi.ink());
                cell.setBackground(isCellSelected(row, column) ? WorkbenchUi.selected() : getBackground());
                int modelColumn = convertColumnIndexToModel(column);
                Run run = runs.get(convertRowIndexToModel(row));
                if (modelColumn >= 2 && modelColumn <= 4 || modelColumn==8) {
                  cell.setText(AuthRunSummary.responseLabel(run, (modelColumn==8?3:modelColumn-2)));
                  cell.setToolTipText(run.context.labels[(modelColumn==8?3:modelColumn-2)]+" · 응답 코드 (판정 근거는 상세 창)");
                  cell.getAccessibleContext().setAccessibleDescription(cell.getToolTipText()+" · "+cell.getText());
                } else if (modelColumn == 7) {
                  var result=AuthRunSummary.of(run);
                  cell.setText(result.label()); cell.setIcon(result.verdict().icon());
                  cell.setForeground(WorkbenchUi.toneInk(result.verdict().tone));
                  cell.setBackground(isCellSelected(row,column)?WorkbenchUi.selected():WorkbenchUi.toneFill(result.verdict().tone));
                  cell.setToolTipText(result.reason());
                  cell.getAccessibleContext().setAccessibleDescription(result.label()+" · "+result.reason());
                }
              }
              return component;
            }

            @Override
            public String getToolTipText(java.awt.event.MouseEvent e) {
              int row = rowAtPoint(e.getPoint()), col = columnAtPoint(e.getPoint());
              if (row < 0 || col < 0) return null;
              Run run=runs.get(convertRowIndexToModel(row));int modelColumn=convertColumnIndexToModel(col);
              if(modelColumn==7)return AuthRunSummary.of(run).reason();
              if(modelColumn>=2&&modelColumn<=4||modelColumn==8)return run.context.labels[(modelColumn==8?3:modelColumn-2)]+" · "+AuthRunSummary.responseLabel(run,(modelColumn==8?3:modelColumn-2))+" · 판정 근거는 상세 창";
              return Objects.toString(getValueAt(row,col), "");
            }
          };
  final LinkedHashMap<String, Candidate> credentials = new LinkedHashMap<>();
  final Candidate[] slots = new Candidate[SLOTS];
  final List<Run> runs = new ArrayList<>();
  static final String[] QUICK_SLOTS={"관리자","일반","후보1"};
  final JCheckBoxMenuItem anonymousItem=new JCheckBoxMenuItem("비로그인");
  final JMenuItem unassignItem=new JMenuItem("해제"),deleteCandidateItem=new JMenuItem("삭제"),deleteHistoryItem=new JMenuItem("삭제");
  final JMenuItem[] slotAssign = new JMenuItem[3];
  final JLabel[] slotLabels = {new JLabel(), new JLabel(), new JLabel(), new JLabel()};
  final JToggleButton record = new JToggleButton("요청 비교 OFF");
  final JToggleButton detection = new JToggleButton("인증 탐지 OFF");
  volatile boolean detectionEnabled;
  final ArrayBlockingQueue<Observation> credentialQueue=new ArrayBlockingQueue<>(64);
  final AtomicBoolean credentialDrain=new AtomicBoolean();
  final AtomicLong credentialDropped=new AtomicLong();
  final Map<String,AuthAccount> accounts=new LinkedHashMap<>();
  final JPanel packetDetails=new JPanel(new BorderLayout(0,6));
  final TargetWindow comparisonWindow=new TargetWindow(this,"비교 기록 상세",()->packetDetails);
  final TargetWindow candidateWindow;
  final AuthCandidateDetails candidateDetails;
  final JsRepeater capturedRepeater;
  final JButton detailRepeaterAction=new JButton();
  final JsRepeaterShortcuts detailShortcuts=new JsRepeaterShortcuts(packetDetails,detailRepeaterAction);
  final PacketInspector[] requestViews=new PacketInspector[SLOTS+1],responseViews=new PacketInspector[SLOTS+1];
  Run detailRun;
  final JCheckBox writes = new JCheckBox("쓰기 요청 재전송 허용"), colors = new JCheckBox("차이 색상 표시", true);
  final JTextField extra = new JTextField(15), urlFilter = new JTextField(15);
  final JLabel counts = new JLabel("인증 후보 0 · 비교 기록 0");
  final JLabel state = new JLabel("인증 후보 우클릭 → 비교 계정 지정 → 선택 요청 비교"),
      detail = new JLabel("비교 기록에서 항목을 선택하세요");
  final JCheckBox largeFiles = new JCheckBox("대용량 응답 해시 비교 · 최대 4MiB");
  volatile boolean largeEnabled;
  final JCheckBox pretty = new JCheckBox("요청·응답 정렬", true);
  final JComboBox<String> mode = new JComboBox<>(new String[] {"비교 계정끼리 비교", "현재 기준과 비교"});
  final JTabbedPane packets = new JTabbedPane();
  final JTextArea[] requests = new JTextArea[SLOTS+1], responses = new JTextArea[SLOTS+1];
  final AtomicReference<Context> recording = new AtomicReference<>();
  final AtomicLong epoch = new AtomicLong();
  final AtomicBoolean closed = new AtomicBoolean();
  final AtomicInteger active = new AtomicInteger();
  final ConcurrentHashMap<Integer, Context> tickets = new ConcurrentHashMap<>();
  final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(20),
          r -> {
            Thread t = new Thread(r, "Workbench-auth-record");
            t.setDaemon(true);
            return t;
          });

  interface ReviewSink {
    void accept(String method, String url, String reason);
  }

  ReviewSink reviewSink = (method, url, reason) -> {};
  final String[] trackingAccounts = {"", "", "", ""};

  @SuppressWarnings("unchecked")
  final JComboBox<AssessmentTracker.Account>[] trackingChoices =
      new JComboBox[] {new JComboBox<>(), new JComboBox<>(), new JComboBox<>(), new JComboBox<>()};

  java.util.function.Supplier<List<AssessmentTracker.Account>> trackingSource = List::of;
  java.util.function.Function<AssessmentTracker.AccountInput, String> trackingCreate =
      input -> {
        throw new IllegalArgumentException("점검 진행 현황 준비 중");
      };
  java.util.function.Consumer<List<AssessmentTracker.Binding>> trackingBind = bindings -> {};

  interface TrackingSink {
    void accept(
        String id,
        HttpRequest expected,
        HttpRequest actual,
        String note,
        boolean review,
        Set<String> names,
        Set<String> cookies);
  }

  TrackingSink trackingSink = (id, expected, actual, note, review, names, cookies) -> {};
  boolean refreshingTracking;

  void refreshTracking() {
    refreshingTracking = true;
    for (int i = 0; i < SLOTS; i++) {
      trackingChoices[i].removeAllItems();
      trackingChoices[i].addItem(new AssessmentTracker.Account("", "미연결", "", "", "", false));
      for (var a : trackingSource.get())
        if (slots[i] != null && a.origin().equals(AssessmentTracker.origin(slots[i].origin)))
          trackingChoices[i].addItem(a);
      boolean found = false;
      for (int j = 0; j < trackingChoices[i].getItemCount(); j++)
        if (trackingChoices[i].getItemAt(j).id().equals(trackingAccounts[i])) {
          trackingChoices[i].setSelectedIndex(j);
          found = true;
          break;
        }
      if (!found) trackingAccounts[i] = "";
    }
    refreshingTracking = false;
  }

  JPanel trackingRow(int slot) {
    var combo = trackingChoices[slot];
    if (combo.getActionListeners().length == 0)
      combo.addActionListener(
          e -> {
            if (!refreshingTracking)
              try {
                editable();
                var a = (AssessmentTracker.Account) combo.getSelectedItem();
                trackingAccounts[slot] = a == null ? "" : a.id();
                workConfigurationEdited();
              } catch (Exception ex) {
                Diagnostics.failure(AuthComparePanel.this,"계정 권한 비교",ex);
                state.setText(ex.getMessage());
                refreshTracking();
              }
          });
    return WorkbenchUi.row(
        new JLabel("점검 이력 계정"),
        combo,
        button("계정 목록 갱신", this::refreshTracking),
        button(
            "이 슬롯을 새 점검 계정으로 등록",
            () -> {
              editable();
              if (slots[slot] == null) throw new IllegalArgumentException("비교 계정을 먼저 지정하세요.");
              trackingAccounts[slot] =
                  trackingCreate.apply(
                      new AssessmentTracker.AccountInput(
                          label(slot),
                          slots[slot].origin,
                          profileRoles[slot].getText(),
                          profileTenants[slot].getText(),
                          slots[slot].headers.isEmpty()));
              refreshTracking();
              state.setText("점검 계정 등록/연결 · 다음 비교부터 계정별 이력에 기록");
            }));
  }

  final JTextField[]
      profileNames =
          {
            new JTextField("비교 계정 1", 12),
            new JTextField("비교 계정 2", 12),
            new JTextField("비교 계정 3", 12),new JTextField("비로그인",12)
          },
      profileOwners = {new JTextField(12), new JTextField(12), new JTextField(12), new JTextField(12)};
  final JTextField ignoredFields = new JTextField(35), ownerPath = new JTextField(20),importantFields=new JTextField(35);
  final JCheckBox unorderedArrays=new JCheckBox("배열 순서 무시");
  final JTextArea denialRules = new JTextArea("error=FORBIDDEN\nerror=UNAUTHORIZED", 3, 35),
      judgementDetails = new JTextArea(6, 60);
  JDialog readinessDialog;
  final AuthEvidencePanel evidence = new AuthEvidencePanel(),expandedEvidence = new AuthEvidencePanel();
  final TargetWindow evidenceWindow=new TargetWindow(this,"판정 근거 크게 보기",()->expandedEvidence);
  final JComboBox<String> accessPolicy =
      new JComboBox<>(new String[] {"정책 미설정", "공개/공유 허용", "소유자 전용"});
  final JTextArea policyRules = new JTextArea(4, 40);
  final JTextField[] profileRoles = {new JTextField(10), new JTextField(10), new JTextField(10), new JTextField(10)},
      profileTenants = {new JTextField(10), new JTextField(10), new JTextField(10), new JTextField(10)};
  final JCheckBox[] refreshEnabled = {
    new JCheckBox("비교 전 갱신"), new JCheckBox("비교 전 갱신"), new JCheckBox("비교 전 갱신"), new JCheckBox("비교 전 갱신")
  };
  final JTextField[] refreshUrls = {new JTextField(35), new JTextField(35), new JTextField(35), new JTextField(35)},
      refreshTokenPaths = {new JTextField(10), new JTextField(10), new JTextField(10), new JTextField(10)},
      refreshCsrfPaths = {new JTextField(10), new JTextField(10), new JTextField(10), new JTextField(10)},
      refreshIdentityPaths = {new JTextField(10), new JTextField(10), new JTextField(10), new JTextField(10)};
  final JTextField[]
      refreshTokenHeaders =
          {
            new JTextField("authorization", 12),
            new JTextField("authorization", 12),
            new JTextField("authorization", 12),
            new JTextField("authorization", 12)
          },
      refreshPrefixes =
          {
            new JTextField("Bearer ", 8), new JTextField("Bearer ", 8), new JTextField("Bearer ", 8), new JTextField("Bearer ", 8)
          },
      refreshCsrfHeaders =
          {
            new JTextField("x-csrf-token", 12),
            new JTextField("x-csrf-token", 12),
            new JTextField("x-csrf-token", 12),
            new JTextField("x-csrf-token", 12)
          };
  final JTextArea matrixRules = new JTextArea(5, 60);
  final AuthMatrixEditor matrixEditor=new AuthMatrixEditor(this);
  final AuthEffectPanel effectSettings=new AuthEffectPanel(this);
  final AuthSetupBundlePanel setupBundles;
  final TargetWindow setupBundleWindow;
  final JTextArea responseDenialRules = new JTextArea(4, 40);
  final TargetWindow sessionSettings =
      new TargetWindow(this, "계정 세션 · 역할/조직 권한", this::sessionSettingsContent);
  final CookieSessionProbe.Config[] sessionChecks=new CookieSessionProbe.Config[SLOTS];
  CookieSessionPanel cookieSessions;
  final TargetWindow cookieSessionWindow=new TargetWindow(this,"쿠키·세션 검사",()->cookieSessions);

  record SessionKey(String candidate, BountySession.Config config) {}

  final Map<SessionKey, Map<String, String>> refreshed = new ConcurrentHashMap<>();
  final TargetWindow comparisonSettings = new TargetWindow(this, "권한 비교 설정", this::settingsContent);
  final JTextField identityCookies = new JTextField(24);
  volatile Set<String> cookieNames = Set.of();
  volatile Set<String> authNames = DEFAULT_AUTH;
  long candidateNo, historyNo, shownNo = -1;
  int dropped;

  record Candidate(
      long no, String key, String origin, Map<String, String> headers, int count, String used) {
    Candidate {
      headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }
  }

  record Context(
      long epoch,
      String origin,
      Candidate[] profiles,
      Set<String> names,
      boolean writes,
      String filter,
      String[] labels,
      String[] owners,
      AuthJudgement.Rules rules,
      AuthPolicy policy,
      AuthResponseRules responseRules,
      BountySession.Config[] sessions,
      BountyMatrix matrix,
      String[] roles,
      String[] tenants,
      boolean largeFiles,
      String[] accountIds,
      Set<String> identityCookies,
      CookieSessionProbe.Config[] sessionChecks,AuthEffectCheck.Config effect, List<AuthEffectRules.Rule> effectRules) {
    Context(long epoch, String origin, Candidate[] profiles, Set<String> names, boolean writes, String filter,
        String[] labels, String[] owners, AuthJudgement.Rules rules, AuthPolicy policy,
        AuthResponseRules responseRules, BountySession.Config[] sessions, BountyMatrix matrix,
        String[] roles, String[] tenants, boolean largeFiles, String[] accountIds, Set<String> identityCookies,
        CookieSessionProbe.Config[] sessionChecks, AuthEffectCheck.Config effect) {
      this(epoch, origin, profiles, names, writes, filter, labels, owners, rules, policy, responseRules,
          sessions, matrix, roles, tenants, largeFiles, accountIds, identityCookies, sessionChecks, effect, List.of());
    }
    AuthEffectCheck.Config effectFor(HttpRequest request) { return effectFor(request.method(), request.url()); }
    AuthEffectCheck.Config effectFor(String method, String url) {
      if (!effectRules.isEmpty()) return AuthEffectRules.select(effectRules, method, url);
      if (effect.enabled() && effect.method().equals(method)
          && AuthEffectRules.canonicalOrigin(origin).equals(AuthEffectRules.canonicalOrigin(url))
          && AuthPolicy.parse(method + " " + effect.path() + " PUBLIC", AuthPolicy.Access.UNSPECIFIED).forRequest(method, url) == AuthPolicy.Access.PUBLIC) return effect;
      return AuthEffectCheck.Config.disabled();
    }
    Context {
      AuthEffectRules.validate(effectRules); effectRules = List.copyOf(effectRules);
      sessionChecks=sessionChecks.clone();
      accountIds = accountIds.clone();
      identityCookies = Set.copyOf(identityCookies);
      sessions = sessions.clone();
      roles = roles.clone();
      tenants = tenants.clone();
      profiles = profiles.clone();
      names = Set.copyOf(names);
      labels = labels.clone();
      owners = owners.clone();
    }
  }

  static final class Packet {
    HttpRequest request;
    HttpResponse response;
    String note = "미지정", changes = "";
    AuthFinding result;
    AuthEffectCheck.Evidence effect;
    AuthFinding finding(){return result==null?AuthFinding.legacy(note,changes):result;}
    void result(AuthFinding finding){result=finding;note=finding.note();changes=finding.changes();}
  }

  static final class Run {
    final long no;
    final String executionId = UUID.randomUUID().toString();
    final HttpRequest original;
    final HttpResponse response;
    final Context context;
    final AuthReadiness.Report readiness;
    volatile HttpResponse currentBaseline;
    volatile Baseline baselineState=Baseline.LEGACY;
    volatile String baselineNote="";
    enum Baseline{LEGACY,CHECKING,VALID,INVALID,WRITE}
    HttpResponse baseline(){return currentBaseline==null?response:currentBaseline;}
    final Packet[] packets = {new Packet(), new Packet(), new Packet(), new Packet()};
    String note = "진행 중";

    Run(long no, HttpRequest q, HttpResponse s, Context c) {
      this.no = no;
      original = q;
      response = s;
      context = c;
      readiness = AuthReadiness.selected(c, List.of(q));
      for (int i = 0; i < SLOTS; i++) if (c.profiles[i] != null) packets[i].note = "대기";
    }
  }

  static DefaultTableModel model(String... columns) {
    return new DefaultTableModel(columns, 0) {
      public boolean isCellEditable(int r, int c) {
        return false;
      }
    };
  }

  public AuthComparePanel(MontoyaApi api, Supplier<List<HttpRequestResponse>> selected) {
    super(new BorderLayout(8, 8));
    this.api = api;
    this.selected = selected;
    this.observedRequests = selected;
    setupBundles = new AuthSetupBundlePanel(this, this::captureBundle, this::applyBundle, AuthSetupBundleStore.project(api));
    setupBundleWindow = new TargetWindow(this, "계정 비교 · 이름 있는 설정 묶음", () -> setupBundles);
    capturedRepeater=new JsRepeater(packetDetails,api);
    candidateDetails=new AuthCandidateDetails(this);
    candidateWindow=new TargetWindow(this,"인증 정보 상세",()->candidateDetails);
    candidateWindow.opener=(parent,title,content,modal,closed)->AuthCandidateDetails.window(parent,title,content,false,()->{candidateDetails.clear();closed.run();});
    comparisonWindow.opener=(parent,title,content,modal,closed)->AuthCandidateDetails.window(parent,title,content,false,()->{detailRun=null;detailShortcuts.close();show(null);closed.run();});
    detailRepeaterAction.addActionListener(e->safe(this::repeater));
    var shortcut=KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SPACE,java.awt.event.InputEvent.CTRL_DOWN_MASK);
    packetDetails.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(shortcut,"wb.auth.repeater");
    packetDetails.getActionMap().put("wb.auth.repeater",new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){safe(AuthComparePanel.this::repeater);}});
    detection.setName("wb.auth.detect");record.setName("wb.auth.compare");
    for(JToggleButton toggle:new JToggleButton[]{detection,record}) {toggle.putClientProperty("wb.analysis.lamp",true);toggle.putClientProperty("wb.state.toggle",true);toggle.setPreferredSize(new Dimension(144,38));}
    detection.addActionListener(e->setDetection(detection.isSelected()));
    evidenceWindow.opener=AuthCandidateDetails::window;
    cookieSessions=new CookieSessionPanel(this);
    cookieSessionWindow.opener=CookieSessionPanel::window;
    setName("wb.auth.record");
    putClientProperty("wb.auth.controller", this);
    setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    JPanel top = new JPanel();
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    JButton settings = button("비교 설정", comparisonSettings::open);
    JButton more = new JButton("더보기");
    JPopupMenu moreMenu = new JPopupMenu();
    JMenuItem csv = new JMenuItem("CSV 저장"), clear = new JMenuItem("후보 비우기");
    csv.addActionListener(e -> safe(this::export));
    clear.addActionListener(e -> safe(this::clearCandidates));
    moreMenu.add(csv);
    moreMenu.add(clear);
    more.addActionListener(e -> moreMenu.show(more, 0, more.getHeight()));
    JMenuItem session=new JMenuItem("세션 검사"),matrix=new JMenuItem("세션·권한 매트릭스");
    session.addActionListener(e->{cookieSessions.loadSlot();cookieSessionWindow.open();});
    matrix.addActionListener(e->sessionSettings.open());
    moreMenu.insert(session,0);moreMenu.insert(matrix,1);moreMenu.insert(new JSeparator(),2);
    JMenuItem bundles = new JMenuItem("설정 묶음 · 저장/불러오기"); bundles.setName("wb.auth.setup.bundles.open");
    bundles.addActionListener(e -> setupBundleWindow.open()); moreMenu.add(bundles);
    JPanel actions=WorkbenchUi.row(detection,record,button("선택 요청 비교",this::reviewSelection),button("중지",this::stop),settings,more);
    actions.setName("wb.auth.actions");top.add(actions);
    JPanel summary=new JPanel(new BorderLayout(8,0));summary.setBorder(BorderFactory.createEmptyBorder(3,8,8,8));
    summary.add(new JLabel("인증 탐지는 수집 OFF / SCOPE와 별도 동작"));summary.add(counts,BorderLayout.EAST);top.add(summary);
    ((AuthLayout.Table)candidates).emptyMessage="인증 탐지를 ON으로 켠 뒤 사이트를 이용하면 인증 후보가 표시됩니다.";
    ((AuthLayout.Table)history).emptyMessage="인증 후보를 계정에 지정하고 선택 요청 비교를 실행하세요.";
    candidates.setName("wb.auth.credentials");
    candidates.setAutoCreateRowSorter(true);
    candidates.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    candidates.putClientProperty("wb.table.compact",true);
    candidates.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
    int[] widths={48,180,100,240,300,100};
    int[] minimums={48,130,80,180,210,95};
    for(int i=0;i<widths.length;i++){var column=candidates.getColumnModel().getColumn(i);column.setPreferredWidth(widths[i]);column.setMinWidth(minimums[i]);}
    candidates.getColumnModel().getColumn(0).setMaxWidth(70);candidates.getColumnModel().getColumn(5).setMaxWidth(120);
    JPopupMenu popup = new JPopupMenu();
    for (int i = 0; i < QUICK_SLOTS.length; i++) {
      final int slot = i;
      JMenuItem assign = new JMenuItem(QUICK_SLOTS[i]);
      slotAssign[i] = assign;
      assign.addActionListener(e -> safe(() -> {assignSelected(slot);if(accounts.get(slots[slot].key())==null)profileNames[slot].setText(QUICK_SLOTS[slot]);refreshSlots();}));
      popup.add(assign);
    }
    anonymousItem.addActionListener(e->safe(()->{if(slots[3]!=null&&slots[3].headers().isEmpty())clearSlot(3);else{anonymous(3);profileNames[3].setText("비로그인");}refreshSlots();}));
    anonymousItem.setToolTipText("독립된 비로그인 비교 지정 · 다시 누르면 비로그인 해제");
    unassignItem.addActionListener(e->safe(this::unassignSelected));
    unassignItem.setToolTipText("선택한 인증 후보의 모든 계정 지정을 해제");
    deleteCandidateItem.addActionListener(e->safe(this::deleteSelectedCandidate));
    popup.add(anonymousItem);popup.add(unassignItem);popup.addSeparator();popup.add(deleteCandidateItem);
    refreshSlots();
    popup.addPopupMenuListener(
        new javax.swing.event.PopupMenuListener() {
          public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
            refreshSlots();WorkbenchUi.style(popup);
          }

          public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}

          public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });
    candidates.putClientProperty("wb.auth.popup", popup);
    candidates.addMouseListener(
        new java.awt.event.MouseAdapter() {
          void showPopup(java.awt.event.MouseEvent e) {
            if (!e.isPopupTrigger()) return;
            int row = candidates.rowAtPoint(e.getPoint());
            if (row >= 0) candidates.setRowSelectionInterval(row, row);
            else candidates.clearSelection();
            refreshSlots();
            popup.show(candidates, e.getX(), e.getY());
          }

          public void mousePressed(java.awt.event.MouseEvent e) {
            showPopup(e);
          }

          public void mouseReleased(java.awt.event.MouseEvent e) { showPopup(e); }
          public void mouseClicked(java.awt.event.MouseEvent e) {
            if(e.getClickCount()==2&&SwingUtilities.isLeftMouseButton(e)) {int row=candidates.rowAtPoint(e.getPoint());if(row>=0){candidates.setRowSelectionInterval(row,row);safe(()->openCandidate(selectedCandidate()));}}
          }
        });
    candidates
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting()) refreshSlots();
            });
    JScrollPane candidateScroll = new JScrollPane(candidates);
    candidateScroll.setPreferredSize(new Dimension(900, 95));
    JPanel candidateCard = AuthLayout.card("인증 정보 후보","더블클릭하여 계정 상세 보기",candidateScroll,null);
    candidateCard.setName("wb.card.수집한 인증 정보 · 중복 제거 · 우클릭으로 인가 지정");
    history.setName("wb.auth.history");
    history.putClientProperty("wb.table.compact",true);
    history.setAutoCreateRowSorter(true);
    history.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
    history.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    history.getColumnModel().getColumn(0).setPreferredWidth(45);
    history.getColumnModel().getColumn(1).setPreferredWidth(320);
    for (int i = 2; i < 5; i++) history.getColumnModel().getColumn(i).setPreferredWidth(210);
    history.getColumnModel().getColumn(5).setPreferredWidth(90);
    history.getColumnModel().getColumn(0).setMaxWidth(70);
    int[] historyMinimums={48,180,100,100,100,80,65,140,100};
    for(int i=0;i<9;i++)history.getColumnModel().getColumn(i).setMinWidth(historyMinimums[i]);
    history.getColumnModel().getColumn(6).setPreferredWidth(70);history.getColumnModel().getColumn(6).setMaxWidth(90);
    history.getColumnModel().getColumn(7).setPreferredWidth(160);
    history.moveColumn(6,1);
    history.moveColumn(history.convertColumnIndexToView(7),3);
    history.moveColumn(history.convertColumnIndexToView(8),7);
    history
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting() && detailRun==null) show(current());
            });
    JPopupMenu historyPopup=new JPopupMenu();historyPopup.add(deleteHistoryItem);
    history.putClientProperty("wb.auth.history.popup",historyPopup);
    deleteHistoryItem.addActionListener(e->safe(this::deleteSelectedHistory));
    history.addMouseListener(new java.awt.event.MouseAdapter(){
      void popup(java.awt.event.MouseEvent e){if(!e.isPopupTrigger())return;int row=history.rowAtPoint(e.getPoint());if(row>=0)history.setRowSelectionInterval(row,row);else history.clearSelection();deleteHistoryItem.setEnabled(current()!=null&&active.get()==0&&recording.get()==null);WorkbenchUi.style(historyPopup);historyPopup.show(history,e.getX(),e.getY());}
      public void mousePressed(java.awt.event.MouseEvent e){popup(e);}public void mouseReleased(java.awt.event.MouseEvent e){popup(e);}
    });
    history.addMouseListener(new java.awt.event.MouseAdapter(){public void mouseClicked(java.awt.event.MouseEvent e){if(e.getClickCount()==2&&SwingUtilities.isLeftMouseButton(e)){int row=history.rowAtPoint(e.getPoint());if(row>=0){history.setRowSelectionInterval(row,row);safe(()->openComparison(current()));}}}});
    for (int i = 0; i < SLOTS+1; i++) {
      requests[i] = text();
      responses[i] = text();
      requestViews[i]=new PacketInspector("wb.auth.detail.request."+i);
      responseViews[i]=new PacketInspector("wb.auth.detail.response."+i);
      packets.addTab(
          i < 3 ? "비교 계정 " + (i + 1) : i==3?"비로그인":"현재 기준",
          WorkbenchUi.split(
              JSplitPane.HORIZONTAL_SPLIT,
              WorkbenchUi.card("요청 REQUEST", requestViews[i]),
              WorkbenchUi.card("응답 RESPONSE", responseViews[i]),
              .5));
    }
    JPanel details = packetDetails;
    details.setName("wb.auth.comparison.detail");
    details.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
    details.add(
        TargetWindow.options(WorkbenchUi.row(detail,mode),WorkbenchUi.row(new JLabel("Ctrl+Space · 현재 계정 패킷을 Repeater 창으로"),legend())),
        BorderLayout.NORTH);
    judgementDetails.setEditable(false);
    judgementDetails.setLineWrap(true);
    judgementDetails.setFont(WorkbenchUi.font());
    details.add(WorkbenchUi.split(JSplitPane.VERTICAL_SPLIT, packets,
        AuthLayout.card("판정 근거","", evidence,button("크게 보기",()->{WorkbenchUi.style(expandedEvidence);expandedEvidence.showRun(detailRun==null?current():detailRun);evidenceWindow.open();})), .70));
    JPanel historyCard = AuthLayout.card("비교 기록","더블클릭하여 패킷·판정 근거 보기",new JScrollPane(history),button("기록 비우기",this::clearHistory));
    historyCard.add(AuthLayout.verdictLegend(),BorderLayout.SOUTH);
    historyCard.setName("wb.card.Record History");
    JButton closeDetails=button("닫기",comparisonWindow::closeWindow);
    details.add(WorkbenchUi.row(closeDetails),BorderLayout.SOUTH);
    JSplitPane lists=WorkbenchUi.split(JSplitPane.VERTICAL_SPLIT,candidateCard,historyCard,.38);
    lists.setName("wb.auth.tables");
    add(
        WorkbenchUi.header(FeatureGuide.title("auth"), "비교 계정 지정 → 선택 요청 비교 → 응답·근거 확인", top),
        BorderLayout.NORTH);
    add(lists);
    JPanel footer=new JPanel(new BorderLayout());footer.setBorder(BorderFactory.createEmptyBorder(4,8,0,8));footer.add(state,BorderLayout.EAST);add(footer,BorderLayout.SOUTH);
    record.setToolTipText("ON이면 이후 조건에 맞는 Proxy·Repeater 요청을 비교 계정으로 자동 재전송합니다. 단순 기록 기능이 아닙니다.");
    record.addActionListener(
        e ->
            safe(
                () -> {
                  if (record.isSelected()) reviewRecord();
                  else stop();
                }));
    mode.addActionListener(e -> show(detailRun==null?current():detailRun));
    colors.addActionListener(e -> show(detailRun==null?current():detailRun));
    pretty.addActionListener(e -> show(detailRun==null?current():detailRun));
    WorkbenchUi.style(this);
    WorkbenchUi.style(packetDetails);
    state.putClientProperty("wb.font.size",12);state.setFont(WorkbenchUi.font().deriveFont(12f));
    WorkbenchUi.feedback(state);
    show(null);
  }

  static JLabel legend() {
    JLabel label = new JLabel("결과·차이 색상 안내");
    label.setToolTipText("결과: 빨강 우회 의심 · 초록 차단 · 노랑 확인 필요 · 회색 비교 미성립. 확정 판정이 아닙니다. 패킷 차이: 보라 인증 · 주황 헤더/상태 · 노랑 본문");
    return label;
  }

  String formatResponse(HttpResponse packet) {
    if (packet != null && packet.body().length() > MESSAGE_LIMIT)
      return "HTTP "
          + packet.statusCode()
          + "\n[대용량 본문 미리보기 생략] "
          + packet.body().length()
          + " bytes · SHA-256 "
          + AuthFileEvidence.digest(packet);
    return packet == null
        ? null
        : pretty.isSelected()
            ? PacketFormat.pretty(PacketFormat.decodedResponse(packet))
            : packet.toString();
  }

  String format(String packet) {
    return packet == null ? null : pretty.isSelected() ? PacketFormat.pretty(packet) : packet;
  }

  JTextArea text() {
    JTextArea a = new JTextArea();
    a.putClientProperty("wb.packet.syntax",true);
    a.setEditable(false);
    a.setLineWrap(false);
    a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
    return a;
  }

  JButton button(String label, Runnable task) {
    JButton b = new JButton(label);
    if (label.equals("선택 요청 비교"))
      b.setToolTipText("선택 요청별 권한표·쓰기 조회 적용 범위와 판정 한계를 먼저 확인한 뒤 비교를 시작합니다.");
    b.addActionListener(e -> safe(task));
    return b;
  }

  void safe(Runnable task) {
    try {
      task.run();
    } catch (IllegalArgumentException e) {
      Diagnostics.failure(this,"계정 권한 비교",e);
      if (recording.get() == null) {
        record.setSelected(false);
        record.setText("요청 비교 OFF");record.repaint();
      }
      state.setText(e.getMessage());
    }
  }

  static String origin(HttpRequest q) {
    java.net.URI u = java.net.URI.create(q.url());
    String host = u.getHost().toLowerCase(Locale.ROOT);
    if (host.contains(":")) host = "[" + host + "]";
    return u.getScheme().toLowerCase(Locale.ROOT)
        + "://"
        + host
        + ":"
        + (u.getPort() < 0 ? (u.getScheme().equalsIgnoreCase("https") ? 443 : 80) : u.getPort());
  }

  boolean bounded(HttpRequest q, HttpResponse s) {
    return bounded(q, s, largeEnabled);
  }

  boolean bounded(HttpRequest q, HttpResponse s, boolean large) {
    return q != null
        && q.toByteArray().length() <= MESSAGE_LIMIT
        && (s == null
            || q.toByteArray().length() + s.toByteArray().length() <= MESSAGE_LIMIT
            || large
                && s.body().length() <= AuthFileEvidence.LIMIT
                && s.toByteArray().length() <= AuthFileEvidence.LIMIT + 65536);
  }

  void setDetection(boolean enabled) {
    if(closed.get())return;
    candidateEpoch.incrementAndGet();credentialQueue.clear();detectionEnabled=enabled;
    detection.setSelected(enabled);detection.setText("인증 탐지 "+(enabled?"ON":"OFF"));detection.repaint();
    state.setText(enabled?"인증 탐지 ON · 수집 OFF/SCOPE와 독립 · 실제 비교는 SCOPE 필요":"인증 탐지 OFF · 기존 후보와 매핑은 유지");
  }

  void offerCredential(HttpRequest request,ToolType source) {
    if(!detectionEnabled||closed.get()||source==ToolType.EXTENSIONS)return;
    Observation observation=prepareCredentialObservation(request);
    if(observation==null||!detectionEnabled||closed.get()||observation.generation()!=candidateEpoch.get())return;
    if(!credentialQueue.offer(observation)){credentialDropped.incrementAndGet();return;}
    if(credentialDrain.compareAndSet(false,true))SwingUtilities.invokeLater(this::drainCredentials);
  }

  void drainCredentials() {
    try {Observation observation;while((observation=credentialQueue.poll())!=null)if(detectionEnabled&&!closed.get())applyObservation(observation);}
    finally {credentialDrain.set(false);if(!credentialQueue.isEmpty()&&credentialDrain.compareAndSet(false,true))SwingUtilities.invokeLater(this::drainCredentials);}
  }

  void openCandidate(Candidate candidate){if(candidate==null)return;WorkbenchUi.style(candidateDetails);candidateDetails.load(candidate);candidateWindow.open();}
  void mapCandidate(Candidate candidate,AuthAccount account) {
    editable();if(candidate==null||!credentials.containsKey(candidate.key()))throw new IllegalArgumentException("후보가 삭제됐습니다. 다시 선택하세요.");
    accounts.put(candidate.key(),account);
    for(int i=0;i<SLOTS;i++)if(slots[i]!=null&&slots[i].key.equals(candidate.key()))applyAccountToSlot(i,slots[i]);
    refreshCandidates(candidate.key());refreshSlots();
  }
  void applyAccountToSlot(int slot,Candidate candidate){AuthAccount a=accounts.get(candidate.key());if(a!=null){profileNames[slot].setText(a.label());profileOwners[slot].setText(a.userId());profileRoles[slot].setText(a.role());}}
  void copyAccount(Candidate from,Candidate to){AuthAccount account=accounts.get(from.key());if(account!=null)accounts.put(to.key(),account);}
  void updateHistoryHeaders(Run run){if(history==null||history.getColumnCount()<5)return;for(int i=0;i<SLOTS;i++)history.getColumnModel().getColumn(history.convertColumnIndexToView((i==3?8:i+2))).setHeaderValue(run==null?slots[i]==null?"미지정 "+(char)('A'+i):label(i):run.context.labels[i]);history.getTableHeader().repaint();}
  void openComparison(Run run){if(run==null)return;detailRun=run;WorkbenchUi.style(packetDetails);show(run);detailShortcuts.install();comparisonWindow.open();}
  static void loadInspector(PacketInspector view,HttpRequest request,HttpResponse response) {
    if(request==null&&response==null){view.setPacket(null,null);return;}
    var wire=request!=null?request.toByteArray():response.toByteArray();
    String decoded=request!=null?request.toString():response.body().length()>MESSAGE_LIMIT?"HTTP "+response.statusCode()+"\n[대용량 본문 미리보기 생략]":PacketFormat.decodedResponse(response);
    view.setPacket(PacketInspector.prefix(wire),decoded,wire.length());
  }
  void highlightInspector(PacketInspector inspector,PacketDiff.View diff){if(!colors.isSelected())return;for(var range:diff.ranges())try{if(range.end()<=inspector.text.getDocument().getLength())inspector.text.getHighlighter().addHighlight(range.start(),range.end(),new DefaultHighlighter.DefaultHighlightPainter(switch(range.kind()){case AUTH->WorkbenchUi.dark()?new Color(90,60,125):new Color(231,216,252);case HEADER->WorkbenchUi.dark()?new Color(112,72,30):new Color(255,225,190);case BODY->WorkbenchUi.dark()?new Color(97,88,33):new Color(255,244,170);}));}catch(javax.swing.text.BadLocationException ignored){}}

  /** Prepared off the EDT using immutable configuration snapshots. */
  final AtomicLong candidateEpoch = new AtomicLong();

  record Observation(
      String key,
      String origin,
      Map<String, String> headers,
      Set<String> names,
      Set<String> cookies,
      long generation) {
    Observation {
      headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
      names = Set.copyOf(names);
      cookies = Set.copyOf(cookies);
    }
  }

  Observation prepareObservation(HttpRequest q, HttpResponse response) {
    if (closed.get() || !WorkbenchScope.contains(api, q) || !bounded(q, response)) return null;
    return prepareCredentialObservation(q);
  }

  Observation prepareCredentialObservation(HttpRequest q) {
    long generation = candidateEpoch.get();
    Set<String> names = authNames, cookies = cookieNames;
    Map<String, String> headers = new LinkedHashMap<>();
    int bytes=0,count=0;
    for (HttpHeader h : q.headers()) {
      if(++count>256 || (bytes+=h.name().length()+h.value().length())>65536 || h.value().length()>16384)return null;
      String n = h.name().toLowerCase(Locale.ROOT);
      if (!names.contains(n) && !CSRF.contains(n) || h.value().isBlank()) continue;
      if (headers.containsKey(n)) {
        if (n.equals("cookie")) headers.merge(n, h.value(), (a, b) -> a + "; " + b);
        else return null;
      } else headers.put(n, h.value());
    }
    Map<String, String> identity = new TreeMap<>();
    for (var h : headers.entrySet())
      if (names.contains(h.getKey())) {
        String value = h.getValue();
        if (h.getKey().equals("cookie") && !cookies.isEmpty()) {
          StringJoiner kept = new StringJoiner("; ");
          for (String pair : value.split(";")) {
            int n = pair.indexOf('=');
            if (n > 0 && cookies.contains(pair.substring(0, n).strip())) kept.add(pair.strip());
          }
          value = kept.toString();
        }
        if (!value.isBlank()) identity.put(h.getKey(), value);
      }
    if (identity.isEmpty()) return null;
    String target = origin(q);
    StringBuilder fingerprint = new StringBuilder(target);
    identity.forEach(
        (k, v) ->
            fingerprint
                .append('|')
                .append(k.length())
                .append(':')
                .append(k)
                .append(v.length())
                .append(':')
                .append(v));
    return new Observation(
        Core.hash(fingerprint.toString().getBytes(StandardCharsets.UTF_8)),
        target,
        headers,
        names,
        cookies,
        generation);
  }

  /** EDT only: applying compact metadata cannot retain a large original response. */
  void applyObservation(Observation observation) {
    if (closed.get()
        || observation == null
        || candidateEpoch.get() != observation.generation
        || !authNames.equals(observation.names)
        || !cookieNames.equals(observation.cookies)) return;
    Candidate selected = selectedCandidate();
    String key = observation.key;
    Candidate old = credentials.get(key),
        next =
            new Candidate(
                old == null ? ++candidateNo : old.no,
                key,
                observation.origin,
                observation.headers,
                old == null ? 1 : old.count + 1,
                java.time.LocalTime.now().withNano(0).toString());
    if (old == null && credentials.size() >= 100)
      credentials.remove(credentials.keySet().iterator().next());
    credentials.put(key, next);
    accounts.keySet().removeIf(k->!credentials.containsKey(k)&&Arrays.stream(slots).filter(Objects::nonNull).noneMatch(slot->slot.key.equals(k)));
    if (recording.get() == null && active.get() == 0)
      for (int i = 0; i < SLOTS; i++) if (slots[i] != null && slots[i].key.equals(key)) slots[i] = next;
    refreshCandidates(selected == null ? null : selected.key);
    refreshSlots();
  }

  void observe(HttpRequest q, HttpResponse response) {
    applyObservation(prepareObservation(q, response));
  }

  void refreshCounts(){counts.setText("인증 후보 "+credentials.size()+" · 비교 기록 "+runs.size());}

  void refreshCandidates(String selected) {
    refreshCounts();
    candidateModel.setRowCount(0);
    int model = 0;
    for (Candidate c : credentials.values()) {
      AuthAccount account=accounts.get(c.key);
      candidateModel.addRow(new Object[]{c.no,account==null?"미매핑":account.label(),account==null?"":account.role(),c.origin,
          String.join(" | ",c.headers.entrySet().stream().map(h->h.getKey()+": "+h.getValue()).toList()),c.used});
      if (c.key.equals(selected)) {
        int view = candidates.convertRowIndexToView(model);
        if (view >= 0) candidates.setRowSelectionInterval(view, view);
      }
      model++;
    }
  }

  Candidate selectedCandidate() {
    int row = candidates.getSelectedRow();
    if (row < 0) return null;
    int index = candidates.convertRowIndexToModel(row);
    return index < credentials.size() ? new ArrayList<>(credentials.values()).get(index) : null;
  }

  void editable() {
    if (recording.get() != null || active.get() > 0)
      throw new IllegalArgumentException("자동 비교/진행 중 비교를 중지하고 진행 중 요청이 끝난 뒤 프로필을 변경하세요.");
  }

  void assignSelected(int i) {
    Candidate c = selectedCandidate();
    if (c == null) throw new IllegalArgumentException("인증 후보를 선택하세요.");
    assign(i, c);
  }

  void assign(int i, Candidate c) {
    editable();
    for (int j = 0; j < SLOTS; j++)
      if (i != j && slots[j] != null && !slots[j].origin.equals(c.origin))
        throw new IllegalArgumentException("비교 계정 1/2/3은 같은 사이트(Origin)여야 합니다.");
    if (slots[i] == null || !slots[i].key.equals(c.key)) {
      trackingAccounts[i] = "";
      profileNames[i].setText("");profileOwners[i].setText("");profileRoles[i].setText("");profileTenants[i].setText("");
    }
    slots[i] = c;
    workConfigurationEdited();
    applyAccountToSlot(i,c);
    sessionChecks[i]=null;
    refreshTracking();
    refreshed.clear();
    refreshSlots();
    state.setText("비교 계정 " + (i + 1) + " 지정 · " + c.origin);
  }

  void anonymous(int i) {
    editable();
    Candidate first =
        Arrays.stream(slots)
            .filter(Objects::nonNull)
            .findFirst()
            .orElseGet(this::selectedCandidate);
    if (first == null) throw new IllegalArgumentException("먼저 대상의 인증 후보를 지정하세요.");
    assign(i, new Candidate(0, "anonymous", first.origin, Map.of(), 1, ""));profileNames[i].setText("비로그인");refreshSlots();
  }

  void clearSlot(int i) {
    editable();
    slots[i] = null;
    workConfigurationEdited();
    sessionChecks[i]=null;
    trackingAccounts[i] = "";
    refreshTracking();
    refreshSlots();
  }

  JPanel settingsContent() {
    largeFiles.addActionListener(
        e -> {
          try {
            editable();
            largeEnabled = largeFiles.isSelected();
          } catch (Exception ex) {Diagnostics.failure(AuthComparePanel.this,"계정 권한 비교",ex);
            largeFiles.setSelected(largeEnabled);
            state.setText(ex.getMessage());
          }
        });
    JPanel panel =
        TargetWindow.options(
            WorkbenchUi.row(largeFiles, new JLabel("전체 해시 관찰 · 취약점 확정 아님 · 비교 기록 본문 합계 32MiB 제한")),
            WorkbenchUi.row(new JLabel("자동 비교 URL 포함"), urlFilter, writes),
            WorkbenchUi.row(
                new JLabel("추가 인증 헤더 이름"),
                extra,
                button("헤더 적용", this::configure),
                colors,
                pretty));
    panel.add(
        WorkbenchUi.row(
            new JLabel("인증 후보 식별 쿠키 이름"),
            identityCookies,
            button(
                "쿠키 설정 적용",
                () -> {
                  editable();
                  Set<String> names = new LinkedHashSet<>();
                  for (String n : identityCookies.getText().split(",")) {
                    n = n.strip();
                    if (n.isEmpty()) continue;
                    if (!n.matches("[A-Za-z0-9_!#$%&\'*+.^`|~-]{1,128}"))
                      throw new IllegalArgumentException("쿠키 이름을 쉼표로 구분하세요.");
                    names.add(n);
                  }
                  cookieNames = Set.copyOf(names);
                  refreshed.clear();
                  clearCandidates();
                  state.setText("쿠키 식별 설정 적용 · 신규 트래픽에서 후보 재수집");
                })));
    panel.add(new JLabel("빈 값: 모든 쿠키로 구분 · 지정 시 해당 쿠키로 후보 구분, 전송에는 전체 쿠키 유지"));
    for (int i = 0; i < SLOTS; i++)
      panel.add(
          WorkbenchUi.row(
              new JLabel("계정 " + (i + 1) + " 이름"),
              profileNames[i],
              new JLabel("소유값"),
              profileOwners[i]));
    panel.add(WorkbenchUi.row(new JLabel("JSON 비교 제외 (쉼표)"), ignoredFields,button("동적 필드 후보",this::dynamicFields)));
    panel.add(WorkbenchUi.row(new JLabel("중요 JSON 필드"),importantFields,unorderedArrays));
    panel.add(new JLabel("예: $.requestId, $.meta.timestamp · 원본 응답은 변경하지 않습니다."));
    panel.add(WorkbenchUi.row(new JLabel("소유 JSON 경로"), ownerPath));
    panel.add(new JLabel("예: $.ownerId · 소유값은 같은 응답 필드 기준. 비로그인은 비워두세요."));
    panel.add(WorkbenchUi.row(new JLabel("기본 접근 정책"), accessPolicy));
    panel.add(
        WorkbenchUi.card(
            "경로별 접근 정책 · METHOD /path PUBLIC 또는 OWNER_ONLY", new JScrollPane(policyRules)));
    panel.add(
        new JLabel("예: GET /api/posts/101 PUBLIC · GET /api/posts/{id} OWNER_ONLY · 정확한 경로 규칙 우선"));
    panel.add(WorkbenchUi.card("차단 규칙 · JSON 경로=값 (행별 OR)", new JScrollPane(denialRules)));
    panel.add(WorkbenchUi.card("응답 차단 규칙 · 행별 OR · 선택 사항", new JScrollPane(responseDenialRules)));
    panel.add(new JLabel("BODY: 접근 권한이 없습니다 · HEADER: X-Access: denied"));
    panel.add(new JLabel("BODY_REGEX: (?i)access\\s+denied · HEADER_REGEX: 정규식 · 대소문자 구분, 정규식 (?i) 지원"));
    panel.add(WorkbenchUi.card("처리 결과 확인",effectSettings));
    panel.add(WorkbenchUi.row(button("준비한 요청 다시 확인", this::reviewPreparedSelection), button("설정 묶음 · 저장/불러오기", setupBundleWindow::open)));
    panel.add(
        button(
            "설정 확인",
            () -> {
              editable();
              ruleSnapshot();effectSettings.snapshot();
              AuthResponseRules.parse(responseDenialRules.getText());
              AuthPolicy.parse(
                  policyRules.getText(),
                  AuthPolicy.Access.values()[accessPolicy.getSelectedIndex()]);
              refreshSlots();
              state.setText("설정 확인 · 다음 비교부터 적용, 기존 결과는 당시 설정 유지");
              refreshPreparedConfiguration();
            }));
    return panel;
  }

  final java.util.List<TargetWindow> dynamicWindows=new ArrayList<>();
  void dynamicFields(){
    editable();var suggestions=AuthDynamicFields.suggest(runs);if(suggestions.isEmpty()){state.setText("동적 필드 후보 없음 · 같은 URL·인증의 정상 기준 응답이 두 개 이상 필요합니다.");return;}
    JPanel choices=new JPanel();choices.setLayout(new BoxLayout(choices,BoxLayout.Y_AXIS));java.util.List<JCheckBox> checks=new ArrayList<>();
    choices.add(new JLabel("값이 달라진 필드입니다. 실제 데이터 변경인지 확인한 뒤 제외할 항목만 선택하세요."));for(String path:suggestions){JCheckBox check=new JCheckBox(path);checks.add(check);choices.add(check);}
    TargetWindow window=new TargetWindow(this,"동적 필드 제외 후보",()->choices);choices.add(button("선택 항목을 비교 제외에 추가",()->{editable();Set<String> fields=new LinkedHashSet<>(Arrays.asList(ignoredFields.getText().split(",")));fields.removeIf(String::isBlank);for(JCheckBox c:checks)if(c.isSelected())fields.add(c.getText());String previous=ignoredFields.getText();ignoredFields.setText(String.join(",",fields));try{ruleSnapshot();}catch(Exception ex){ignoredFields.setText(previous);throw ex;}window.closeWindow();state.setText("선택한 동적 필드 제외 · 다음 비교부터 적용");refreshPreparedConfiguration();}));dynamicWindows.add(window);window.open();
  }

  AuthSetupBundles.Bundle captureBundle() {
    editable(); var packets = setupRequests();
    if (packets.size() != 1) throw new IllegalArgumentException("묶음 기준 요청 한 개를 선택하세요.");
    HttpRequest request = packets.get(0).request(); String origin = AuthComparePanel.origin(request);
    matrixEditor.snapshot(); ruleSnapshot();
    var effects = effectSettings.snapshotRules(origin).stream().filter(rule -> rule.origin().equals(origin)).toList();
    var settings = new AuthSetupBundles.Settings(ownerPath.getText(), ignoredFields.getText(), importantFields.getText(),
        denialRules.getText(), responseDenialRules.getText(), policyRules.getText(), AuthPolicy.Access.values()[accessPolicy.getSelectedIndex()],
        matrixRules.getText(), unorderedArrays.isSelected(), effects);
    return new AuthSetupBundles.Bundle("새 설정 묶음", origin, request.method(), java.net.URI.create(request.url()).getPath(), 0,
        AuthSetupBundles.ROLE_PLACEHOLDERS, settings);
  }

  void applyBundle(AuthSetupBundles.Bundle bundle) {
    editable(); var packets = setupRequests();
    if (packets.size() != 1) throw new IllegalArgumentException("적용할 원본 요청 한 개를 선택하세요.");
    HttpRequest request = packets.get(0).request();
    if (!AuthEffectRules.canonicalOrigin(bundle.origin()).equals(origin(request)) || !WorkbenchScope.contains(api, request)
        || AuthPolicy.parse(bundle.method() + " " + bundle.path() + " PUBLIC", AuthPolicy.Access.UNSPECIFIED).forRequest(request.method(), request.url()) != AuthPolicy.Access.PUBLIC)
      throw new IllegalArgumentException("묶음의 Origin·메서드·경로와 현재 SCOPE 요청이 일치하지 않습니다.");
    // Constructors validated the entire immutable proposal before any control is changed.
    var settings = bundle.settings(); AuthEffectRules.validate(settings.effects());
    if (executionTracker != null) executionTracker.invalidateAuthOrigin(origin(request), "설정 묶음 적용 · 계정 재연결 필요");
    for (int i = 0; i < SLOTS; i++) {
      clearSlot(i); profileNames[i].setText(AuthSetupBundles.ROLE_PLACEHOLDERS.get(i));
      profileOwners[i].setText(""); profileRoles[i].setText(""); profileTenants[i].setText("");
      refreshEnabled[i].setSelected(false); refreshUrls[i].setText(""); refreshTokenPaths[i].setText("");
      refreshCsrfPaths[i].setText(""); refreshIdentityPaths[i].setText("");
    }
    refreshed.clear(); cookieSessions.invalidateResult(); writes.setSelected(false);
    ownerPath.setText(settings.ownerPath()); ignoredFields.setText(settings.ignoredFields()); importantFields.setText(settings.importantFields());
    denialRules.setText(settings.denialRules()); responseDenialRules.setText(settings.responseRules()); policyRules.setText(settings.policyRules());
    accessPolicy.setSelectedIndex(settings.fallback().ordinal()); matrixRules.setText(settings.matrixRules()); unorderedArrays.setSelected(settings.unorderedArrays());
    effectSettings.replaceRules(settings.effects()); workConfigurationEdited(); refreshSlots();
    state.setText("설정 묶음 ‘" + bundle.name() + "’ r" + bundle.revision() + " 적용 · 계정·소유값·쓰기 허용을 다시 지정하고 준비 화면에서 확인하세요. 요청 전송 없음.");
  }

  AuthJudgement.Rules ruleSnapshot() {
    var rules=AuthJudgement.rules(ignoredFields.getText(), denialRules.getText(), ownerPath.getText());return new AuthJudgement.Rules(rules.ignored(),rules.denied(),rules.ownerPath(),rules.policy(),AuthJudgement.paths(importantFields.getText()),unorderedArrays.isSelected());
  }

  String label(int i) {
    String text = profileNames[i].getText().strip();
    return text.isBlank() ? "비교 계정 " + (i + 1) : text.substring(0, Math.min(text.length(), 32));
  }

  void refreshSlots() {
    matrixEditor.refreshNames();
    updateHistoryHeaders(detailRun==null?current():detailRun);
    for (int i = 0; i < SLOTS; i++) {
      Candidate c = slots[i];
      slotLabels[i].setText(
          c == null ? "미지정" : c.headers.isEmpty() ? "비로그인" : c.key.substring(0, 12));
      slotLabels[i].setToolTipText(
          c == null ? null : c.origin + " · " + String.join(", ", c.headers.keySet())
              +(sessionChecks[i]==null?"":" · 비교 전 신원 검사 ON"));
      if(i<slotAssign.length&&slotAssign[i]!=null){slotAssign[i].setToolTipText(QUICK_SLOTS[i]+"에 선택 후보 지정 · 현재 "+slotLabels[i].getText());slotAssign[i].setEnabled(selectedCandidate()!=null&&recording.get()==null&&active.get()==0);}
    }
    boolean idle=recording.get()==null&&active.get()==0;
    Candidate selected=selectedCandidate();
    anonymousItem.setSelected(slots[3]!=null&&slots[3].headers().isEmpty());
    anonymousItem.setEnabled(idle&&(selected!=null||Arrays.stream(slots).anyMatch(Objects::nonNull)));
    unassignItem.setEnabled(idle&&selected!=null&&Arrays.stream(slots).anyMatch(c->c!=null&&c.key().equals(selected.key())));
    deleteCandidateItem.setEnabled(idle&&selected!=null);
  }

  void unassignSelected(){editable();Candidate c=selectedCandidate();if(c==null)return;for(int i=0;i<SLOTS;i++)if(slots[i]!=null&&slots[i].key().equals(c.key()))clearSlot(i);refreshed.clear();cookieSessions.invalidateResult();refreshSlots();state.setText("선택 후보의 계정 지정 해제");}

  void deleteSelectedCandidate(){
    editable();Candidate c=selectedCandidate();if(c==null)return;
    candidateEpoch.incrementAndGet();credentialQueue.clear();
    unassignSelected();credentials.remove(c.key());accounts.remove(c.key());
    if(candidateDetails.candidate!=null&&candidateDetails.candidate.key().equals(c.key())){candidateWindow.closeWindow();candidateDetails.clear();}
    refreshCandidates(null);refreshSlots();state.setText("인증 후보 삭제 · 기존 비교 기록 유지");
  }

  void deleteSelectedHistory(){
    editable();Run run=current();if(run==null)return;
    int index=runs.indexOf(run);if(index<0)return;
    if(detailRun==run){detailRun=null;comparisonWindow.closeWindow();evidenceWindow.closeWindow();detailShortcuts.close();}
    history.clearSelection();runs.remove(index);historyModel.removeRow(index);
    refreshCounts();show(detailRun==null?current():detailRun);state.setText("선택 비교 기록 삭제");
  }

  void configure() {
    editable();
    Set<String> next = new HashSet<>(DEFAULT_AUTH);
    for (String n : extra.getText().split(",")) {
      n = n.strip().toLowerCase(Locale.ROOT);
      if (n.isEmpty()) continue;
      if (!n.matches("[a-z0-9-]+") || CSRF.contains(n))
        throw new IllegalArgumentException("추가 인증 헤더 이름을 쉼표로 구분하세요. CSRF는 보조 헤더입니다.");
      Core.headers(n + ": test");
      next.add(n);
    }
    authNames = Set.copyOf(next);
    clearCandidates();
    state.setText("헤더 설정 적용 · 신규 트래픽에서 다시 수집합니다.");
  }

  void clearCandidates() {
    editable();
    candidateEpoch.incrementAndGet();
    credentials.clear();
    accounts.clear();credentialQueue.clear();candidateWindow.closeWindow();candidateDetails.clear();
    candidateModel.setRowCount(0);
    Arrays.fill(slots, null);
    Arrays.fill(sessionChecks,null);
    cookieSessions.invalidateResult();
    Arrays.fill(trackingAccounts, "");
    refreshTracking();
    refreshSlots();
  }

  JPanel sessionSettingsContent() {
    refreshTracking();matrixEditor.load();
    JPanel panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    for (int i = 0; i < SLOTS; i++) {
      JPanel account =
          TargetWindow.options(
              trackingRow(i),
              WorkbenchUi.row(
                  new JLabel(label(i)),
                  new JLabel("역할"),
                  profileRoles[i],
                  new JLabel("조직"),
                  profileTenants[i]),
              WorkbenchUi.row(refreshEnabled[i], new JLabel("GET URL"), refreshUrls[i]),
              WorkbenchUi.row(
                  new JLabel("토큰 JSON"),
                  refreshTokenPaths[i],
                  new JLabel("헤더"),
                  refreshTokenHeaders[i],
                  new JLabel("접두사"),
                  refreshPrefixes[i]),
              WorkbenchUi.row(
                  new JLabel("CSRF JSON"),
                  refreshCsrfPaths[i],
                  new JLabel("헤더"),
                  refreshCsrfHeaders[i],
                  new JLabel("신원 JSON"),
                  refreshIdentityPaths[i]));
      panel.add(WorkbenchUi.card("계정 " + (i + 1), account));
    }
    panel.add(new JLabel("같은 Origin/SCOPE GET 갱신 · 신원 JSON은 비교 설정의 소유값과 대조 · 비로그인 갱신 금지"));
    panel.add(WorkbenchUi.card("기대 권한 · 요청별 허용/차단", matrixEditor));
    panel.add(WorkbenchUi.row(button("준비한 요청 다시 확인", this::reviewPreparedSelection), button("설정 묶음 · 저장/불러오기", setupBundleWindow::open)));
    panel.add(
        new JLabel("선택 요청을 표에 추가한 뒤 계정별 허용·차단을 선택하세요. 원문 규칙도 지원합니다."));
    panel.add(
        button(
            "설정 확인",
            () -> {
              editable();
              sessionConfigs();
              matrixEditor.snapshot();
              refreshed.clear();
              state.setText("세션·매트릭스 확인 · 다음 비교부터 적용 · 설정/토큰 파일 저장 없음");
              refreshPreparedConfiguration();
            }));
    WorkbenchUi.style(panel);
    return panel;
  }

  BountySession.Config[] sessionConfigs() {
    BountySession.Config[] configs = new BountySession.Config[SLOTS];
    for (int i = 0; i < SLOTS; i++) {
      configs[i] =
          new BountySession.Config(
              refreshEnabled[i].isSelected(),
              refreshUrls[i].getText().strip(),
              refreshTokenPaths[i].getText().strip(),
              refreshTokenHeaders[i].getText().strip(),
              refreshPrefixes[i].getText(),
              refreshCsrfPaths[i].getText().strip(),
              refreshCsrfHeaders[i].getText().strip(),
              refreshIdentityPaths[i].getText().strip());
      if (configs[i].enabled()) {
        if (slots[i] == null || slots[i].headers().isEmpty())
          throw new IllegalArgumentException("갱신할 인증 계정을 먼저 지정하세요.");
        HttpRequest q = HttpRequest.httpRequestFromUrl(configs[i].url());
        if (!WorkbenchScope.contains(api, q) || !origin(q).equals(slots[i].origin()))
          throw new IllegalArgumentException("갱신 URL은 계정과 같은 Origin/SCOPE 안이어야 합니다.");
      }
    }
    return configs;
  }

  Context context() {
    Candidate first =
        Arrays.stream(slots)
            .filter(Objects::nonNull)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("인증 후보 우클릭 → 비교 계정 1/2/3 지정이 필요합니다."));
    for (Candidate c : slots)
      if (c != null && !c.origin.equals(first.origin))
        throw new IllegalArgumentException("프로필 Origin이 다릅니다.");
    Set<String> names = new HashSet<>(authNames);
    names.addAll(CSRF);
    for (Candidate c : slots) if (c != null) names.addAll(c.headers.keySet());
    String[] labels = new String[SLOTS], owners = new String[SLOTS];
    for (int i = 0; i < SLOTS; i++) {
      labels[i] = label(i);
      owners[i] = profileOwners[i].getText().strip();
      if (owners[i].length() > 256) throw new IllegalArgumentException("계정 소유값 256자 제한");
    }
    BountySession.Config[] sessions = sessionConfigs();
    for (var cfg : sessions)
      if (cfg.enabled()) {
        names.add(cfg.tokenHeader().toLowerCase(Locale.ROOT));
        names.add(cfg.csrfHeader().toLowerCase(Locale.ROOT));
      }
    String[] roles = new String[SLOTS], tenants = new String[SLOTS];
    for (int i = 0; i < SLOTS; i++) {
      roles[i] = profileRoles[i].getText().strip();
      tenants[i] = profileTenants[i].getText().strip();
      if (roles[i].length() > 64 || tenants[i].length() > 64)
        throw new IllegalArgumentException("역할·조직은 64자 이하입니다.");
    }
    List<AssessmentTracker.Binding> links = new ArrayList<>();
    for (int i = 0; i < SLOTS; i++)
      if (!trackingAccounts[i].isEmpty()) {
        if (slots[i] == null) throw new IllegalArgumentException("점검 이력 계정에 연결할 슬롯이 없습니다.");
        links.add(
            new AssessmentTracker.Binding(
                trackingAccounts[i], slots[i].origin, slots[i].headers, names, cookieNames));
      }
    trackingBind.accept(links);
    Context result = new Context(
        epoch.get(),
        first.origin,
        slots,
        names,
        writes.isSelected(),
        urlFilter.getText().strip(),
        labels,
        owners,
        ruleSnapshot(),
        AuthPolicy.parse(
            policyRules.getText(), AuthPolicy.Access.values()[accessPolicy.getSelectedIndex()]),
        AuthResponseRules.parse(responseDenialRules.getText()),
        sessions,
        matrixEditor.snapshot(),
        roles,
        tenants,
        largeEnabled,
        trackingAccounts,
        cookieNames,
        sessionChecks,effectSettings.snapshot(),effectSettings.snapshotRules(first.origin));
    if (workContext != null) workContext.configure(WorkContext.authFingerprint(result));
    if (executionTracker != null) executionTracker.authConfiguration(result);
    return result;
  }

  void startRecord() {
    if (closed.get()) return;
    if (active.get() > 0) throw new IllegalArgumentException("진행 중 비교가 끝난 후 자동 비교를 시작하세요.");
    epoch.incrementAndGet();
    Context c = context();
    activateRecord(c);
  }

  void activateRecord(Context c) {
    if (closed.get() || c.epoch != epoch.get()) return;
    recording.set(c);
    record.setSelected(true);
    record.setText("요청 비교 ON");record.repaint();
    state.setText("자동 비교 중 · Proxy/Repeater 신규 요청 재전송 · " + c.origin + " · 프로필 고정");
  }

  void reviewRecord() {
    record.setSelected(false);
    if (closed.get()) return;
    if (active.get() > 0) throw new IllegalArgumentException("진행 중 비교가 끝난 후 자동 비교를 시작하세요.");
    epoch.incrementAndGet();
    Context c = context();
    if (reviewReadiness(c, AuthReadiness.automatic(c), List.of())) activateRecord(c);
  }

  /** User-facing entry point. Internal enqueue/compare APIs retain their existing contract. */
  public void reviewSelection() {
    if (closed.get()) return;
    if (recording.get() != null) throw new IllegalArgumentException("요청 비교를 OFF로 바꾸고 선택 요청을 비교하세요.");
    List<HttpRequestResponse> list = List.copyOf(selected.get());
    if (list.isEmpty()) throw new IllegalArgumentException("요청·응답 목록에서 요청을 선택하세요.");
    if (list.size() > 20) throw new IllegalArgumentException("최대 20개 요청입니다.");
    prepareSelection(list);
    reviewPreparedRequests(list);
  }

  /** Navigation from a remaining-check item prepares exact originals; it never starts a comparison. */
  public void stageReadiness(List<HttpRequestResponse> requests) {
    editable(); prepareSelection(requests); comparisonSettings.open();
    state.setText("선택한 " + preparationSelection.size() + "개 원본의 비교 준비 · 설정 확인 후 ‘준비한 요청 다시 확인’을 누르세요. 요청 전송 없음.");
  }

  void prepareSelection(List<HttpRequestResponse> requests) {
    List<HttpRequestResponse> list = List.copyOf(requests);
    if (list.isEmpty() || list.size() > 20) throw new IllegalArgumentException("준비할 원본은 1~20개여야 합니다.");
    Set<HttpRequest> identities = Collections.newSetFromMap(new IdentityHashMap<>());
    Set<String> ids = new HashSet<>(); List<String> ordered = new ArrayList<>();
    for (var packet : list) {
      if (packet.request() == null || !identities.add(packet.request())) throw new IllegalArgumentException("같은 원본을 두 번 준비할 수 없습니다.");
      String id = Objects.toString(originalIdentity.apply(packet.request()), "");
      if (!id.isEmpty() && !ids.add(id)) throw new IllegalArgumentException("같은 원본 ID를 두 번 준비할 수 없습니다.");
      ordered.add(id);
    }
    preparationSelection = list; preparationIds = List.copyOf(ordered); preparationRevision = workSelectionRevision();
    preparationFocus = preparationSlot = -1; preparationFocusRevision++; preparationGeneration++;
  }

  void validatePreparedSelection() {
    if (preparationSelection.isEmpty()) throw new IllegalArgumentException("먼저 선택 요청 비교에서 준비 화면을 여세요.");
    if (preparationRevision != workSelectionRevision()) throw new IllegalArgumentException("선택 원본이 변경되었습니다. 원본을 다시 선택하고 준비 화면을 여세요.");
    if (!preparationIds.isEmpty()) {
      if (preparationIds.size() != preparationSelection.size()) throw new IllegalArgumentException("준비한 원본 목록이 변경되었습니다.");
      for (int i = 0; i < preparationIds.size(); i++)
        if (!preparationIds.get(i).isEmpty() && !preparationIds.get(i).equals(originalIdentity.apply(preparationSelection.get(i).request())))
          throw new IllegalArgumentException("준비한 원본이 만료되었거나 변경되었습니다. 원본을 다시 선택하세요.");
    }
  }

  void clearPreparation() {
    preparationSelection = List.of(); preparationIds = List.of();
    preparationFocus = preparationSlot = -1; preparationFocusRevision++; preparationGeneration++;
  }

  record PreparedStamp(long selection, long generation, String configuration, String scope,
                       List<String> originalIds, List<HttpRequestResponse> originals) {
    PreparedStamp { originalIds = List.copyOf(originalIds); originals = List.copyOf(originals); }
  }

  PreparedStamp preparedStamp(Context c) {
    return new PreparedStamp(workSelectionRevision(), preparationGeneration, WorkContext.authFingerprint(c),
        WorkbenchScope.registeredRules(api).toString(), preparationIds, preparationSelection);
  }

  void validatePreparedExecution(PreparedStamp reviewed, Context c) {
    validatePreparedExecution(reviewed, c, reviewed.originals());
  }

  void validatePreparedExecution(PreparedStamp reviewed, Context c, List<HttpRequestResponse> requests) {
    validatePreparedSelection();
    if (!reviewed.equals(preparedStamp(context())))
      throw new IllegalArgumentException("검토 중 선택·계정·설정·SCOPE가 변경되었습니다. 준비한 요청을 다시 확인하세요.");
    requireSamePreparedOriginals(reviewed.originals(), requests);
    requireSamePreparedOriginals(reviewed.originals(), preparationSelection);
    // Validate the complete batch before enqueueing its first member, including observer Scope.
    for (var rr : requests) {
      validate(rr.request(), rr.response(), c);
      AuthEffectCheck.Config effect = c.effectFor(rr.request());
      if (effect.enabled() && !WorkbenchScope.contains(api, AuthEffectCheck.observer(effect, rr.request(), c.names)))
        throw new IllegalArgumentException("처리 결과 GET 조회 URL이 SCOPE 밖입니다.");
    }
  }

  private static void requireSamePreparedOriginals(List<HttpRequestResponse> reviewed, List<HttpRequestResponse> requests) {
    if (reviewed.size() != requests.size()) throw new IllegalArgumentException("검토한 원본 목록이 변경되었습니다. 준비 화면을 다시 여세요.");
    for (int i = 0; i < reviewed.size(); i++)
      if (reviewed.get(i) != requests.get(i))
        throw new IllegalArgumentException("검토한 원본의 순서·참조가 변경되었습니다. 준비 화면을 다시 여세요.");
  }

  void reviewPreparedRequests(List<HttpRequestResponse> list) {
    reviewPreparedRequests(list, this::reviewReadiness);
  }

  @FunctionalInterface
  interface ReadinessReview { boolean confirm(Context context, AuthReadiness.Report report, List<String> blocked); }

  void reviewPreparedRequests(List<HttpRequestResponse> list, ReadinessReview review) {
    validatePreparedSelection();
    requireSamePreparedOriginals(preparationSelection, list);
    Context c = context();
    PreparedStamp stamp = preparedStamp(c);
    List<String> blocked = new ArrayList<>();
    for (var rr : list) {
      try {
        validate(rr.request(), rr.response(), c);
        AuthEffectCheck.Config effect = c.effectFor(rr.request());
        if (effect.enabled()) {
          HttpRequest observer = AuthEffectCheck.observer(effect, rr.request(), c.names);
          if (!WorkbenchScope.contains(api, observer)) throw new IllegalArgumentException("처리 결과 GET 조회 URL이 SCOPE 밖입니다.");
        }
      } catch (IllegalArgumentException ex) {
        blocked.add(rr.request().method() + " " + java.net.URI.create(rr.request().url()).getPath() + " · " + ex.getMessage());
      }
    }
    AuthReadiness.Report report = AuthReadiness.selected(c, list.stream().map(HttpRequestResponse::request).toList());
    if (!review.confirm(c, report, blocked)) return;
    if (closed.get() || c.epoch != epoch.get()) return;
    validatePreparedExecution(stamp, c, list);
    // Consume the prepared batch before enqueue, so another return action cannot repeat writes.
    List<String> ids = stamp.originalIds();
    clearPreparation();
    if (workContext != null) workContext.comparisonStarted();
    for (int i = 0; i < list.size(); i++) {
      var rr = list.get(i);
      enqueue(rr.request(), rr.response(), c, ids.size() == list.size() ? ids.get(i) : originalIdentity.apply(rr.request()));
    }
  }

  void reviewPreparedSelection() {
    editable();
    validatePreparedSelection();
    comparisonSettings.closeWindow(); sessionSettings.closeWindow();
    reviewPreparedRequests(List.copyOf(preparationSelection));
  }

  record ReadinessEdit(AuthReadiness.Row row, String kind) {}

  void editReadinessRow(AuthReadiness.Row row, String kind) {
    editable();
    if (!row.url().isEmpty()) {
      validatePreparedSelection();
      if (row.requestIndex() < 0 || row.requestIndex() >= preparationSelection.size())
        throw new IllegalArgumentException("준비한 원본을 찾을 수 없습니다. 요청을 다시 선택하세요.");
      var packet = preparationSelection.get(row.requestIndex());
      if (!packet.request().method().equals(row.method()) || !packet.request().url().equals(row.url()))
        throw new IllegalArgumentException("준비한 원본이 변경되었습니다. 준비 화면을 다시 여세요.");
      preparationFocus = row.requestIndex(); preparationSlot = row.slot(); preparationFocusRevision++;
    }
    if (kind.equals("account")) {
      comparisonSettings.open();
      if (row.slot() >= 0) { profileNames[row.slot()].requestFocusInWindow(); profileNames[row.slot()].scrollRectToVisible(new Rectangle(0, 0, profileNames[row.slot()].getWidth(), profileNames[row.slot()].getHeight())); }
    } else if (kind.equals("policy")) {
      sessionSettings.open(); if (!row.url().isEmpty()) prefillSelectedPolicy();
      if (row.slot() >= 0 && !row.url().isEmpty()) {
        String path = java.net.URI.create(row.url()).getPath();
        for (int i = 0; i < matrixEditor.model.getRowCount(); i++) {
          String method = Objects.toString(matrixEditor.model.getValueAt(i, 0), ""), rulePath = Objects.toString(matrixEditor.model.getValueAt(i, 1), "");
          if (AuthPolicy.parse(method + " " + rulePath + " PUBLIC", AuthPolicy.Access.UNSPECIFIED).forRequest(row.method(), row.url()) == AuthPolicy.Access.PUBLIC) {
            int view = matrixEditor.table.convertRowIndexToView(i); matrixEditor.table.changeSelection(view, row.slot() + 2, false, false); break;
          }
        }
      }
      matrixEditor.scrollRectToVisible(new Rectangle(0, 0, matrixEditor.getWidth(), 1));
    } else if (kind.equals("effect")) {
      comparisonSettings.open();
      var existing = effectSettings.snapshotRules(AuthComparePanel.origin(setupRequests().get(0).request())).stream()
          .filter(rule -> rule.origin().equals(AuthComparePanel.origin(setupRequests().get(0).request()))
              && AuthPolicy.parse(rule.config().method() + " " + rule.config().path() + " PUBLIC", AuthPolicy.Access.UNSPECIFIED)
                  .forRequest(setupRequests().get(0).request().method(), setupRequests().get(0).request().url()) == AuthPolicy.Access.PUBLIC).findFirst();
      if (existing.isPresent()) effectSettings.loadRule(existing.get());
      else { effectSettings.loadedKey = ""; effectSettings.enabled.setSelected(false); effectSettings.ruleName.setText("쓰기 조회 " + (effectSettings.savedRules.size() + 1)); effectSettings.connectSelected(true); }
      effectSettings.scrollRectToVisible(new Rectangle(0, 0, effectSettings.getWidth(), 1));
    }
    state.setText("해당 요청·계정 설정 수정 · 원본 " + preparationSelection.size() + "개 선택 유지 · ‘준비한 요청 다시 확인’에서 전체 적용 범위를 재검토하세요. 요청 전송 없음.");
  }

  boolean reviewReadiness(Context c, AuthReadiness.Report report, List<String> blocked) {
    if (GraphicsEnvironment.isHeadless()) throw new IllegalArgumentException("비교 전 설정 확인은 Burp 화면에서 실행하세요. 요청은 전송하지 않았습니다.");
    if (readinessDialog != null) { readinessDialog.toFront(); return false; }
    long reviewedGeneration = preparationGeneration;
    String proceed = report.automatic() ? "자동 비교 시작" : report.incomplete() > 0 ? "제한 확인 후 비교" : "비교 시작";
    Object[] choices = blocked.isEmpty()
        ? new Object[]{"권한표 설정", "쓰기 조회 설정", proceed, "취소"}
        : new Object[]{"권한표 설정", "쓰기 조회 설정", "닫기"};
    Object initial = blocked.isEmpty() && report.incomplete() == 0 ? proceed : choices[0];
    JOptionPane pane = new JOptionPane(null, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, null, choices, initial);
    pane.setMessage(AuthReadiness.content(c, report, blocked, (row, kind) -> pane.setValue(new ReadinessEdit(row, kind)), preparationFocus, preparationSlot));
    JDialog dialog = pane.createDialog(this, "비교 전 설정 확인");
    readinessDialog = dialog;
    dialog.setResizable(true);
    dialog.setMinimumSize(new Dimension(720, 420));
    Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
    dialog.setSize(Math.min(1000, Math.max(720, screen.width - 80)), Math.min(680, Math.max(420, screen.height - 100)));
    dialog.setLocationRelativeTo(this);
    Object choice;
    try { dialog.setVisible(true); choice = pane.getValue(); }
    finally { dialog.dispose(); if (readinessDialog == dialog) readinessDialog = null; }
    if (closed.get() || c.epoch != epoch.get()) return false;
    if (!report.automatic() && reviewedGeneration != preparationGeneration)
      throw new IllegalArgumentException("검토 중 준비한 원본 목록이 변경되었습니다. 준비 화면을 다시 여세요.");
    if (choice instanceof ReadinessEdit edit) { editReadinessRow(edit.row(), edit.kind()); return false; }
    if ("권한표 설정".equals(choice)) {
      preparationFocus = preparationSlot = -1; preparationFocusRevision++;
      sessionSettings.open();
      if (!report.automatic()) prefillSelectedPolicy();
      SwingUtilities.invokeLater(() -> matrixEditor.scrollRectToVisible(new Rectangle(0, 0, matrixEditor.getWidth(), 1)));
    } else if ("쓰기 조회 설정".equals(choice)) {
      preparationFocus = preparationSlot = -1; preparationFocusRevision++;
      comparisonSettings.open();
      if (!report.automatic() && setupRequests().size() == 1 && Set.of("POST", "PUT", "PATCH", "DELETE").contains(setupRequests().get(0).request().method()))
        effectSettings.connectSelected(false);
      SwingUtilities.invokeLater(() -> effectSettings.scrollRectToVisible(new Rectangle(0, 0, effectSettings.getWidth(), 1)));
    }
    if (!proceed.equals(choice)) state.setText("비교 시작 전 · 설정을 확인한 뒤 다시 비교하세요. 요청은 전송하지 않았습니다.");
    if (blocked.isEmpty() && proceed.equals(choice)) {
      if (!WorkContext.authFingerprint(c).equals(WorkContext.authFingerprint(context())))
        throw new IllegalArgumentException("검토 중 계정·설정이 변경되었습니다. 비교 준비를 다시 확인하세요.");
      return true;
    }
    return false;
  }

  void closeReadiness() {
    if (readinessDialog != null) { readinessDialog.dispose(); readinessDialog = null; }
  }

  boolean eligible(HttpRequest q, Context c) {
    try {
      return WorkbenchScope.contains(api, q)
          && origin(q).equals(c.origin)
          && (c.writes || GraphqlSemantics.read(q))
          && (c.filter.isEmpty() || q.url().contains(c.filter));
    } catch (Exception e) {
      return false;
    }
  }

  /** Request-time tickets avoid retroactive capture and recursive extension replay. */
  void begin(HttpRequest q, ToolType source, int id) {
    Context c = recording.get();
    if (c != null
        && !closed.get()
        && (source == ToolType.PROXY || source == ToolType.REPEATER)
        && eligible(q, c)
        && tickets.size() < 250) tickets.put(id, c);
  }

  void received(int id, HttpRequest q, HttpResponse s) { received(id, q, s, null); }
  void received(int id, HttpRequest q, HttpResponse s, DiskTrafficStore.PacketRef original) {
    Context c = tickets.remove(id);
    if (c != null && !closed.get() && c.epoch == epoch.get())
      SwingUtilities.invokeLater(
          () -> {
            if (!closed.get() && c.epoch == epoch.get()) enqueue(q, s, c, original == null ? originalIdentity.apply(q) : original.id());
          });
  }

  void validate(HttpRequest q, HttpResponse s, Context c) {
    if (!eligible(q, c)) throw new IllegalArgumentException("Scope/Origin/메서드/URL 필터 조건을 확인하세요.");
    if (s == null || !bounded(q, s, c.largeFiles))
      throw new IllegalArgumentException("기준 응답 없음 또는 저장 한도 초과 · 기본 128KiB / 대용량 옵션 4MiB");
    for (var p : q.parameters())
      if (!p.type().toString().equals("COOKIE")
          && Set.of("access_token", "token", "session_id", "sessionid", "jwt")
              .contains(p.name().toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException("쿼리/본문의 인증값은 Repeater에서 정리 후 가져오세요.");
  }

  public void compareSelection() {
    if (closed.get()) return;
    if (recording.get() != null) throw new IllegalArgumentException("요청 비교를 OFF로 바꾸고 선택 요청을 비교하세요.");
    List<HttpRequestResponse> list = selected.get();
    if (list.isEmpty()) throw new IllegalArgumentException("요청·응답 목록에서 요청을 선택하세요.");
    if (list.size() > 20) throw new IllegalArgumentException("최대 20개 요청입니다.");
    Context c = context();
    for (var rr : list) validate(rr.request(), rr.response(), c);
    if (workContext != null) workContext.comparisonStarted();
    for (var rr : list) enqueue(rr.request(), rr.response(), c);
  }

  void enqueue(HttpRequest q, HttpResponse s, Context c) { enqueue(q, s, c, originalIdentity.apply(q)); }
  void enqueue(HttpRequest q, HttpResponse s, Context c, String originalId) {
    try {
      validate(q, s, c);
    } catch (IllegalArgumentException e) {
      Diagnostics.failure(AuthComparePanel.this,"계정 권한 비교",e);
      state.setText("기록 제외 · " + e.getMessage());
      return;
    }
    if (worker.getQueue().remainingCapacity() == 0) {
      state.setText("비교 대기열 20개 초과 · 제외 " + (++dropped));
      return;
    }
    Run run = new Run(++historyNo, q, s, c);
    if (executionTracker != null) executionTracker.beginAuthExecution(run.executionId, originalId, q, c);
    if (runs.size() >= LIMIT) {
      runs.remove(0);
      historyModel.removeRow(0);
    }
    runs.add(run);
    historyModel.addRow(new Object[] {run.no, Core.safeUrl(q.url()), "대기", "대기", "대기", run.note,q.method(),"비교 중","대기"});
    if (history.getSelectedRow() < 0)
      history.setRowSelectionInterval(history.getRowCount() - 1, history.getRowCount() - 1);
    active.incrementAndGet();
    try {
      worker.execute(() -> execute(run));
    } catch (RejectedExecutionException e) {
      active.decrementAndGet();
      run.note = "대기열 거부";
      if (executionTracker != null) executionTracker.finishAuthExecution(run.executionId, ExecutionLedger.State.FAILED, run.note);
      refresh(run);
    }
  }

  void execute(Run run) {
    Context c = run.context;
    try {
      if(!checkBaseline(run))return;
      boolean first = true;
      for (int i = 0; i < SLOTS; i++) {
        if (c.profiles[i] == null) continue;
        if(run.baselineState==Run.Baseline.INVALID)break;
        if (closed.get() || c.epoch != epoch.get()) break;
        if (!first) Thread.sleep(500);
        first = false;
        if (closed.get() || c.epoch != epoch.get()) break;
        final int refreshingSlot = i;
        Map<String, String> session = c.profiles[i].headers;
        boolean checkingIdentity=false;
        try {
          var cfg = c.sessions[i];
          if (cfg.enabled()) {
            if (closed.get() || c.epoch != epoch.get()) break;
            SessionKey key = new SessionKey(c.profiles[i].key, cfg);
            session =
                BountySession.refresh(
                    api,
                    cfg,
                    refreshed.getOrDefault(key, session),
                    c.names,
                    c.origin,
                    run.original.url(),
                    c.owners[i]);
            if (!closed.get() && c.epoch == epoch.get()) {
              if (refreshed.size() > 100) refreshed.clear();
              refreshed.put(key, session);
            }
          }
          if(c.sessionChecks[i]!=null) {
            checkingIdentity=true;
            CookieSessionProbe.verify(api,c.sessionChecks[i],session,c.names,c.origin,
                ()->closed.get()||c.epoch!=epoch.get());
          }
        } catch (Exception ex) {
          final String stage=checkingIdentity?"신원 검사 실패":"갱신 실패";
          final boolean identityFailed=checkingIdentity;
          publish(
              run,
              () -> {
                run.packets[refreshingSlot].result(new AuthFinding(identityFailed?AuthFinding.Kind.SESSION:AuthFinding.Kind.REFRESH,"비교 미전송 · "+ex.getMessage(),""));
                trackingSink.accept(
                    c.accountIds[refreshingSlot],
                    run.original,
                    null,
                    run.packets[refreshingSlot].note,
                    true,
                    c.names,
                    c.identityCookies);
                reviewSink.accept(
                    run.original.method(),
                    run.original.url(),
                    c.labels[refreshingSlot] + ": 세션 확인 필요");
              });
          continue;
        }
        if (closed.get() || c.epoch != epoch.get()) break;
        HttpRequest q = Core.sessionRequest(run.original, session, c.names);
        if (!eligible(q, c)) throw new IllegalArgumentException("전송 직전 Scope/Origin 변경");
        final int slot = i;
        AuthEffectCheck.Snapshot beforeEffect=null;
        AuthEffectCheck.Config effectConfig=c.effectFor(q);
        if(effectConfig.enabled())try{HttpRequest observer=AuthEffectCheck.observer(effectConfig,run.original,c.names);beforeEffect=AuthEffectCheck.before(effectConfig,observer,read->sendRead(read,c));}catch(Exception ex){final String message=ex.getMessage();publish(run,()->{run.packets[slot].request=q;run.packets[slot].result(new AuthFinding(AuthFinding.Kind.WRITE_SETUP_FAILED,"비교 미전송 · "+message,""));});continue;}
        HttpRequestResponse rr;
        try {
          rr =
              api.http()
                  .sendRequest(
                      q,
                      RequestOptions.requestOptions()
                          .withResponseTimeout(10000)
                          .withRedirectionMode(RedirectionMode.NEVER));
        } catch (Exception e) {
          publish(
              run,
              () -> {
                run.packets[slot].request = q;
                run.packets[slot].result(new AuthFinding(AuthFinding.Kind.ERROR,"",""));
                trackingSink.accept(
                    c.accountIds[slot], q, null, "전송 오류", true, c.names, c.identityCookies);
              });
          continue;
        }
        HttpRequest actual = rr != null && rr.request() != null ? rr.request() : q;
        HttpResponse response = rr == null ? null : rr.response();
        AuthFinding finding=new AuthFinding(AuthFinding.Kind.NO_RESPONSE,"","");
        if(response!=null){
          if(!bounded(actual,response,c.largeFiles)){response=null;finding=new AuthFinding(AuthFinding.Kind.ERROR,"응답 저장 한도 초과","");}
          else if(rr.request()==null||!sameTarget(actual,q)||!WorkbenchScope.contains(api,actual))finding=new AuthFinding(AuthFinding.Kind.CHANGED,"실제 전송 대상/메서드 변경 · Burp 규칙 확인","");
          else if(!credentialValues(actual,c.names).equals(credentialValues(q,c.names)))finding=new AuthFinding(AuthFinding.Kind.CHANGED,"실제 전송 인증 변경 · Burp 규칙 확인","");
          else if(credentialValues(q,c.names).equals(credentialValues(run.original,c.names)))finding=referenceFinding(run,response);
          else try{
            boolean exists=false;
            if((response.statusCode()==404||response.statusCode()==410)&&GraphqlSemantics.read(run.original)&&!closed.get()&&c.epoch==epoch.get()){
              var check=sendRead(run.original,c);
              exists=check!=null&&check.statusCode()>=200&&check.statusCode()<300&&run.baseline()!=null&&JsAnalysisPanel.readBody(check).equals(JsAnalysisPanel.readBody(run.baseline()));
            }
            finding=AuthComparison.assess(run,slot,q,response,exists);
          }catch(Exception ex){finding=new AuthFinding(AuthFinding.Kind.REVIEW,"응답 디코딩/구조 또는 재조회 확인 필요","");}
        }
        AuthEffectCheck.Evidence effectEvidence=null;
        if(beforeEffect!=null&&response!=null&&rr.request()!=null&&WorkbenchScope.contains(api,actual)&&sameTarget(actual,q)&&credentialValues(actual,c.names).equals(credentialValues(q,c.names)))try{effectEvidence=AuthEffectCheck.after(effectConfig,beforeEffect,read->sendRead(read,c),()->closed.get()||c.epoch!=epoch.get());finding=AuthEffectCheck.finding(effectEvidence,c.matrix.expected(q.method(),q.url(),slot),finding,response);}catch(Exception ex){effectEvidence=new AuthEffectCheck.Evidence(effectConfig,beforeEffect,null,AuthEffectCheck.Outcome.UNKNOWN,"후속 조회 실패/중지");finding=new AuthFinding(AuthFinding.Kind.WRITE_READ_FAILED,"후속 조회 실패 · 처리 결과 확인 필요",finding.changes());}
        final AuthEffectCheck.Evidence observedEffect=effectEvidence;
        final AuthFinding decision=finding;
        boolean review=finding.verdict()!=AuthVerdict.BLOCKED&&finding.kind()!=AuthFinding.Kind.REFERENCE;
        String note=finding.note(),changes=finding.changes();
        final HttpResponse reply = response;
        final String observation = note, difference = changes;
        final boolean needsReview = review;
        publish(
            run,
            () -> {
              run.packets[slot].request = actual;
              run.packets[slot].response = reply;
              run.packets[slot].result(decision);run.packets[slot].effect=observedEffect;
              trackingSink.accept(
                  c.accountIds[slot],
                  q,
                  actual,
                  observation,
                  needsReview,
                  c.names,
                  c.identityCookies);
              if (needsReview)
                reviewSink.accept(
                    run.original.method(), run.original.url(), c.labels[slot] + ": " + observation);
            });
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      publish(run, () -> run.note = "중단 · " + e.getMessage());
    } finally {
      if (closed.get()) refreshed.clear();
      SwingUtilities.invokeLater(
          () -> {
            active.decrementAndGet();
            if (!closed.get() && runs.contains(run)) {
              if (run.note.equals("진행 중")) run.note = c.epoch == epoch.get() ? "완료" : "중지";
              for (Packet p : run.packets) if (p.note.equals("대기")) p.result(new AuthFinding(AuthFinding.Kind.CANCELLED,"",""));
              refresh(run);
            }
            if (executionTracker != null) executionTracker.finishAuthExecution(run.executionId, executionState(run), run.note);
          });
    }
  }

  ExecutionLedger.State executionState(Run run) {
    if (closed.get() || run.context.epoch() != epoch.get() || run.note.equals("중지")) return ExecutionLedger.State.CANCELLED;
    if (run.baselineState == Run.Baseline.INVALID || run.note.startsWith("중단") || run.note.equals("대기열 거부")) return ExecutionLedger.State.FAILED;
    for (int i = 0; i < SLOTS; i++) {
      if (run.context.profiles()[i] == null) continue;
      var packet = run.packets[i];
      if (Set.of(AuthFinding.Kind.ERROR, AuthFinding.Kind.SESSION, AuthFinding.Kind.REFRESH,
          AuthFinding.Kind.NO_RESPONSE, AuthFinding.Kind.CHANGED, AuthFinding.Kind.WRITE_SETUP_FAILED,
          AuthFinding.Kind.WRITE_READ_FAILED).contains(packet.finding().kind())) return ExecutionLedger.State.FAILED;
      if (packet.finding().kind() == AuthFinding.Kind.CANCELLED) return ExecutionLedger.State.CANCELLED;
      if (packet.response == null) return ExecutionLedger.State.FAILED;
    }
    return ExecutionLedger.State.COMPLETED;
  }

  void cancelExecutionRecords(String reason) {
    if (executionTracker != null) for (Run run : List.copyOf(runs))
      executionTracker.finishAuthExecution(run.executionId, ExecutionLedger.State.CANCELLED, reason);
  }

  AuthFinding referenceFinding(Run run,HttpResponse response)throws Exception{
    boolean valid=response.statusCode()>=200&&response.statusCode()<300;
    if(valid&&response.body().length()<=MESSAGE_LIMIT){String body=JsAnalysisPanel.readBody(response);var json=AuthJudgement.json(body);valid=(json==null||!AuthJudgement.denied(json,run.context.rules()))&&run.context.responseRules().match(responseHeaders(response),body).isEmpty();}
    if(valid&&GraphqlSemantics.query(run.original))valid=GraphqlSemantics.response(JsAnalysisPanel.readBody(response))==GraphqlSemantics.Result.DATA;
    if(!valid){run.baselineState=Run.Baseline.INVALID;run.baselineNote="기준 계정의 재전송 응답이 비정상 · HTTP "+response.statusCode();}
    return new AuthFinding(AuthFinding.Kind.REFERENCE,valid?"교차 인가 미검증":"현재 기준 세션 확인 필요","");
  }

  HttpResponse sendRead(HttpRequest request,Context c)throws Exception{
    if(!(Set.of("GET","HEAD").contains(request.method())||GraphqlSemantics.query(request))||!origin(request).equals(c.origin)||!WorkbenchScope.contains(api,request)||closed.get()||c.epoch!=epoch.get())throw new IllegalArgumentException("기준/조회 요청 범위·취소 상태 확인");
    burp.api.montoya.http.message.HttpRequestResponse rr;try{rr=api.http().sendRequest(request,RequestOptions.requestOptions().withResponseTimeout(10000).withRedirectionMode(RedirectionMode.NEVER));}catch(Exception ex){throw new IllegalArgumentException("기준/조회 전송 오류");}
    if(closed.get()||c.epoch!=epoch.get())throw new IllegalArgumentException("중지");
    if(rr==null||rr.request()==null||rr.response()==null||!sameTarget(rr.request(),request)||!WorkbenchScope.contains(api,rr.request())||!credentialValues(rr.request(),c.names).equals(credentialValues(request,c.names))||!bounded(rr.request(),rr.response(),c.largeFiles))throw new IllegalArgumentException("현재 기준 응답 없음 또는 실제 전송 변경");
    return rr.response();
  }
  boolean checkBaseline(Run run){
    run.baselineState=Run.Baseline.CHECKING;
    if(!(Set.of("GET","HEAD").contains(run.original.method())||GraphqlSemantics.query(run.original))){run.baselineState=Run.Baseline.WRITE;run.baselineNote="기준 요청은 추가 재전송하지 않음 · 후속 조회로 처리 결과 확인";return true;}
    try{
      HttpResponse response=sendRead(run.original,run.context);run.currentBaseline=response;
      if(response.statusCode()<200||response.statusCode()>=300)throw new IllegalArgumentException("현재 기준 HTTP "+response.statusCode());
      if(response.body().length()<=MESSAGE_LIMIT){String body=JsAnalysisPanel.readBody(response);var json=AuthJudgement.json(body);if(json!=null&&AuthJudgement.denied(json,run.context.rules()))throw new IllegalArgumentException("현재 기준의 JSON 차단 규칙 일치");if(!run.context.responseRules().match(responseHeaders(response),body).isEmpty())throw new IllegalArgumentException("현재 기준의 응답 차단 규칙 일치");}
      if(GraphqlSemantics.query(run.original)&&GraphqlSemantics.response(JsAnalysisPanel.readBody(response))!=GraphqlSemantics.Result.DATA)throw new IllegalArgumentException("현재 기준 GraphQL 성공 데이터 없음/오류");
      run.baselineState=Run.Baseline.VALID;run.baselineNote="현재 기준 HTTP "+response.statusCode()+" · 비교 직전 재확인";return true;
    }catch(Exception ex){if(closed.get()||run.context.epoch()!=epoch.get()){run.baselineNote="기준 검증 중지";run.baselineState=Run.Baseline.LEGACY;}else{run.baselineNote="세션 확인 필요 · "+(ex instanceof java.io.IOException?"기준 응답 디코딩 확인":ex.getMessage());run.baselineState=Run.Baseline.INVALID;}return false;}
  }

  static String responseHeaders(HttpResponse response) {
    StringJoiner headers = new StringJoiner("\r\n");
    if (response.headers() == null) {
      String raw = response.toString();
      int end = raw.indexOf("\r\n\r\n");
      return end < 0 ? "" : raw.substring(0, end);
    }
    for (HttpHeader header : response.headers()) headers.add(header.name() + ": " + header.value());
    return headers.toString();
  }

  static boolean sameTarget(HttpRequest a, HttpRequest b) {
    try {
      java.net.URI x = java.net.URI.create(a.url()), y = java.net.URI.create(b.url());
      return a.method().equals(b.method())
          && origin(a).equals(origin(b))
          && Objects.equals(x.getRawPath(), y.getRawPath())
          && Objects.equals(x.getRawQuery(), y.getRawQuery());
    } catch (Exception e) {
      return false;
    }
  }

  static List<String> credentialValues(HttpRequest q, Set<String> names) {
    List<String> values = new ArrayList<>();
    for (HttpHeader h : q.headers())
      if (names.contains(h.name().toLowerCase(Locale.ROOT)))
        values.add(h.name().toLowerCase(Locale.ROOT) + ":" + h.value());
    Collections.sort(values);
    return values;
  }

  void publish(Run run, Runnable task) {
    SwingUtilities.invokeLater(
        () -> {
          if (!closed.get() && runs.contains(run)) {
            task.run();
            trimHistory(run);
            refresh(run);
          }
        });
  }

  void trimHistory(Run keep) {
    long bytes = 0;
    for (Run r : runs) {
      bytes += (r.currentBaseline==null?0:r.currentBaseline.body().length());
      for(Packet p:r.packets)if(p.effect!=null)bytes+=p.effect.bytes();
      bytes += r.original.body().length() + (r.response == null ? 0 : r.response.body().length());
      for (Packet p : r.packets)
        bytes +=
            (p.request == null ? 0 : p.request.body().length())
                + (p.response == null ? 0 : p.response.body().length());
    }
    while (bytes > 32L * 1024 * 1024 && runs.size() > 1) {
      int n = runs.get(0) == keep ? 1 : 0;
      Run old = runs.remove(n);
      bytes-=(old.currentBaseline==null?0:old.currentBaseline.body().length());for(Packet p:old.packets)if(p.effect!=null)bytes-=p.effect.bytes();
      historyModel.removeRow(n);
      bytes -=
          old.original.body().length() + (old.response == null ? 0 : old.response.body().length());
      for (Packet p : old.packets)
        bytes -=
            (p.request == null ? 0 : p.request.body().length())
                + (p.response == null ? 0 : p.response.body().length());
      dropped++;
    }
  }

  String summary(Run run) {
    StringJoiner j = new StringJoiner(" · ");
    for (int i = 0; i < SLOTS; i++) {
      Packet p = run.packets[i];
      j.add(
          run.context.labels[i]
              + ": "
              + (p.response == null ? "" : p.response.statusCode() + " ")
              + p.note);
    }
    return j.add(run.note).toString();
  }

  void refresh(Run run) {
    int index = runs.indexOf(run);
    if (index < 0) return;
    for (int i = 0; i < SLOTS; i++)
      historyModel.setValueAt(AuthRunSummary.responseLabel(run,i),index,i==3?8:2+i);
    historyModel.setValueAt(AuthRunSummary.of(run).label(),index,7);
    historyModel.setValueAt(run.note, index, 5);
    historyModel.setValueAt(run.original.method(),index,6);refreshCounts();
    if (detailRun == run || detailRun==null && current() == run) show(run);
    state.setText("비교 기록 " + runs.size() + " · 비교 작업 " + active.get() + " · 제외 " + dropped);
  }

  Run current() {
    int row = history.getSelectedRow();
    if (row < 0) return null;
    int index = history.convertRowIndexToModel(row);
    return index < runs.size() ? runs.get(index) : null;
  }

  void show(Run run) {
    boolean changed = shownNo != (run == null ? -1 : run.no);
    shownNo = run == null ? -1 : run.no;
    updateHistoryHeaders(run);
    Set<String> names = new HashSet<>(authNames);
    names.addAll(CSRF);
    String[] req = new String[SLOTS], res = new String[SLOTS];
    if (run != null) {
      names.addAll(run.context.names);
      for (int i = 0; i < SLOTS; i++) {
        req[i] = format(run.packets[i].request == null ? null : run.packets[i].request.toString());
        res[i] = formatResponse(run.packets[i].response);
      }
    }
    PacketDiff.View[] q = PacketDiff.compare(req, names), s = PacketDiff.compare(res, names);
    for (int i = 0; i < SLOTS; i++) {
      if (run != null && mode.getSelectedIndex() == 1) {
        q[i] = PacketDiff.compare(new String[] {format(run.original.toString()), req[i]}, names)[1];
        s[i] = PacketDiff.compare(new String[] {formatResponse(run.baseline()), res[i]}, names)[1];
      }
      paint(requests[i], q[i], changed);
      paint(responses[i], s[i], changed);
      loadInspector(requestViews[i],run==null?null:run.packets[i].request,null);
      loadInspector(responseViews[i],null,run==null?null:run.packets[i].response);
      highlightInspector(requestViews[i],q[i]);highlightInspector(responseViews[i],s[i]);
      packets.setTitleAt(i, run == null ? label(i) : run.context.labels[i]);
      packets.setIconAt(i, null);
      packets.setToolTipTextAt(i, run == null ? null : AuthRunSummary.responseLabel(run,i)+" · "+run.packets[i].note);
    }
    paint(
        requests[ORIGINAL],
        PacketDiff.compare(
            new String[] {run == null ? null : format(run.original.toString())}, names)[0],
        changed);
    paint(
        responses[ORIGINAL],
        PacketDiff.compare(new String[] {run == null ? null : formatResponse(run.baseline())}, names)[
            0],
        changed);
    loadInspector(requestViews[ORIGINAL],run==null?null:run.original,null);
    loadInspector(responseViews[ORIGINAL],null,run==null?null:run.baseline());
    detail.setText(
        run == null
            ? "비교 기록에서 항목을 선택하세요"
            : "NO "
                + String.format("%03d", run.no)
                + " · "
                + run.original.method()
                + " · "
                + "종합 결과: " + AuthRunSummary.of(run).label() + " · " + run.note);
    detail.setIcon(run==null?null:AuthRunSummary.of(run).verdict().icon());
    detail.setToolTipText(run == null ? null : run.original.url());
    StringBuilder reasons = new StringBuilder();
    if(run!=null)reasons.append("종합 결과: ").append(AuthRunSummary.of(run).label()).append("\n")
        .append(AuthRunSummary.of(run).reason()).append("\n\n계정별 근거\n");
    if (run != null)
      for (int i = 0; i < SLOTS; i++)
        reasons
            .append(run.context.labels[i])
            .append(" [역할=")
            .append(run.context.roles[i])
            .append(", 조직=")
            .append(run.context.tenants[i])
            .append("] : ")
            .append(AuthRunSummary.responseLabel(run,i)).append(" · ")
            .append(AuthRunSummary.reference(run.packets[i])?"기준 계정 · 원본과 동일 인증":run.packets[i].finding().verdict().describe(run.packets[i].note))
            .append("\n")
            .append(run.packets[i].changes)
            .append("\n\n");
    judgementDetails.setText(reasons.toString());
    judgementDetails.setCaretPosition(0);
    evidence.showRun(run);expandedEvidence.showRun(run);
  }

  void paint(JTextArea area, PacketDiff.View v, boolean changed) {
    int caret = changed ? 0 : area.getCaretPosition();
    area.setText(v.text() + (v.limited() ? "\n[미리보기 제한: 64K자 / 2,000줄]" : ""));
    area.getHighlighter().removeAllHighlights();
    if (colors.isSelected())
      for (PacketDiff.Range r : v.ranges())
        try {
          Color color =
              switch (r.kind()) {
                case AUTH -> WorkbenchUi.dark() ? new Color(90, 60, 125) : new Color(231, 216, 252);
                case HEADER ->
                    WorkbenchUi.dark() ? new Color(112, 72, 30) : new Color(255, 225, 190);
                case BODY -> WorkbenchUi.dark() ? new Color(97, 88, 33) : new Color(255, 244, 170);
              };
          area.getHighlighter()
              .addHighlight(
                  r.start(), r.end(), new DefaultHighlighter.DefaultHighlightPainter(color));
        } catch (javax.swing.text.BadLocationException ignored) {
        }
    area.setCaretPosition(Math.min(caret, area.getDocument().getLength()));
  }

  void repeater() {
    Run r=detailRun;
    if(r==null)throw new IllegalArgumentException("비교 기록을 더블클릭해 상세 창을 여세요.");
    int i=packets.getSelectedIndex();
    HttpRequest q=i==ORIGINAL?r.original:r.packets[i].request;
    HttpResponse response=i==ORIGINAL?r.baseline():r.packets[i].response;
    if(q==null)throw new IllegalArgumentException("전송한 요청이 없습니다.");
    capturedRepeater.openPacket(q,response,i==ORIGINAL?"현재 기준":r.context.labels[i]);
  }

  void stop() {
    recording.set(null);
    epoch.incrementAndGet();
    closeReadiness();
    clearPreparation();
    if(cookieSessions.busy)cookieSessions.cancel();
    tickets.clear();
    record.setSelected(false);
    record.setText("요청 비교 OFF");record.repaint();
    state.setText("중지 · 이미 발송한 요청은 응답/타임아웃 후 종료합니다.");
  }

  void clearHistory() {
    stop(); cancelExecutionRecords("비교 기록 초기화 · 실행 중 작업 중지");
    detailRun=null;comparisonWindow.closeWindow();evidenceWindow.closeWindow();detailShortcuts.close();
    runs.clear();
    historyModel.setRowCount(0);
    refreshCounts();show(null);
  }

  void export() {
    JFileChooser chooser = new JFileChooser();
    chooser.setSelectedFile(new java.io.File("workbench-auth-history.csv"));
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
    Path path = chooser.getSelectedFile().toPath();
    if (Files.exists(path)
        && JOptionPane.showConfirmDialog(this, "기존 파일을 덮어쓸까요?", "CSV 저장", JOptionPane.YES_NO_OPTION)
            != JOptionPane.YES_OPTION) return;
    StringBuilder text = new StringBuilder("\ufeffNO,URL,관찰 결과\r\n");
    for (Run r : runs)
      text.append(r.no)
          .append(',')
          .append(Core.csv(Core.safeUrl(r.original.url())))
          .append(',')
          .append(Core.csv(summary(r)))
          .append("\r\n");
    try {
      Files.writeString(path, text, StandardCharsets.UTF_8);
      state.setText("CSV 저장 완료");
    } catch (Exception e) {
      Diagnostics.failure(AuthComparePanel.this,"계정 권한 비교",e);
      state.setText("CSV 저장 실패");
    }
  }

  @Override
  public void close() {
    closed.set(true); cancelExecutionRecords("확장 종료 · 실행 중 작업 중지");
    clearPreparation();
    SwingUtilities.invokeLater(this::closeReadiness);
    detectionEnabled=false;candidateEpoch.incrementAndGet();credentialQueue.clear();
    dynamicWindows.forEach(TargetWindow::close);dynamicWindows.clear();candidateWindow.close();comparisonWindow.close();evidenceWindow.close();detailShortcuts.close();capturedRepeater.close();candidateDetails.clear();
    for(int i=0;i<SLOTS+1;i++){requestViews[i].close();responseViews[i].close();}
    accounts.clear();detailRun=null;
    comparisonSettings.close();
    effectSettings.closeSuggestions(); setupBundleWindow.close(); setupBundles.close();
    sessionSettings.close();
    cookieSessionWindow.close();
    cookieSessions.close();
    Arrays.fill(sessionChecks,null);
    refreshed.clear();
    recording.set(null);
    epoch.incrementAndGet();
    tickets.clear();
    worker.shutdownNow();
    SwingUtilities.invokeLater(
        () -> {
          runs.clear();
          credentials.clear();
          Arrays.fill(slots, null);
          historyModel.setRowCount(0);
          candidateModel.setRowCount(0);
          refreshSlots();
          record.setSelected(false);
          record.setText("요청 비교 OFF");record.repaint();
          show(null);
        });
  }
}
