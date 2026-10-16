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
  final MontoyaApi api;
  final Supplier<List<HttpRequestResponse>> selected;
  final DefaultTableModel candidateModel = model("NO", "Origin", "인증 헤더 · 값", "지문"),
      historyModel = model("NO", "URL", "계정 1", "계정 2", "계정 3", "실행 상태");
  final JTable candidates = new JTable(candidateModel),
      history =
          new JTable(historyModel) {
            @Override
            public String getToolTipText(java.awt.event.MouseEvent e) {
              int row = rowAtPoint(e.getPoint()), col = columnAtPoint(e.getPoint());
              return row < 0 || col < 0 ? null : Objects.toString(getValueAt(row, col), "");
            }
          };
  final LinkedHashMap<String, Candidate> credentials = new LinkedHashMap<>();
  final Candidate[] slots = new Candidate[3];
  final List<Run> runs = new ArrayList<>();
  final JMenu[] slotMenus = new JMenu[3];
  final JMenuItem[] slotAssign = new JMenuItem[3];
  final JLabel[] slotLabels = {new JLabel(), new JLabel(), new JLabel()};
  final JToggleButton record = new JToggleButton("새 요청 자동 비교 OFF");
  final JCheckBox writes = new JCheckBox("쓰기 요청 재전송 허용"), colors = new JCheckBox("차이 색상 표시", true);
  final JTextField extra = new JTextField(15), urlFilter = new JTextField(15);
  final JLabel state = new JLabel("인증 후보 우클릭 → 비교 계정 지정 → 선택 요청 비교"),
      detail = new JLabel("비교 기록에서 항목을 선택하세요");
  final JCheckBox largeFiles = new JCheckBox("대용량 응답 해시 비교 · 최대 4MiB");
  volatile boolean largeEnabled;
  final JCheckBox pretty = new JCheckBox("요청·응답 정렬", true);
  final JComboBox<String> mode = new JComboBox<>(new String[] {"비교 계정끼리 비교", "원본과 비교"});
  final JTabbedPane packets = new JTabbedPane();
  final JTextArea[] requests = new JTextArea[4], responses = new JTextArea[4];
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
  final String[] trackingAccounts = {"", "", ""};

  @SuppressWarnings("unchecked")
  final JComboBox<AssessmentTracker.Account>[] trackingChoices =
      new JComboBox[] {new JComboBox<>(), new JComboBox<>(), new JComboBox<>()};

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
    for (int i = 0; i < 3; i++) {
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
            new JTextField("비교 계정 3", 12)
          },
      profileOwners = {new JTextField(12), new JTextField(12), new JTextField(12)};
  final JTextField ignoredFields = new JTextField(35), ownerPath = new JTextField(20);
  final JTextArea denialRules = new JTextArea("error=FORBIDDEN\nerror=UNAUTHORIZED", 3, 35),
      judgementDetails = new JTextArea(6, 60);
  final JComboBox<String> accessPolicy =
      new JComboBox<>(new String[] {"정책 미설정", "공개/공유 허용", "소유자 전용"});
  final JTextArea policyRules = new JTextArea(4, 40);
  final JTextField[] profileRoles = {new JTextField(10), new JTextField(10), new JTextField(10)},
      profileTenants = {new JTextField(10), new JTextField(10), new JTextField(10)};
  final JCheckBox[] refreshEnabled = {
    new JCheckBox("비교 전 갱신"), new JCheckBox("비교 전 갱신"), new JCheckBox("비교 전 갱신")
  };
  final JTextField[] refreshUrls = {new JTextField(35), new JTextField(35), new JTextField(35)},
      refreshTokenPaths = {new JTextField(10), new JTextField(10), new JTextField(10)},
      refreshCsrfPaths = {new JTextField(10), new JTextField(10), new JTextField(10)},
      refreshIdentityPaths = {new JTextField(10), new JTextField(10), new JTextField(10)};
  final JTextField[]
      refreshTokenHeaders =
          {
            new JTextField("authorization", 12),
            new JTextField("authorization", 12),
            new JTextField("authorization", 12)
          },
      refreshPrefixes =
          {
            new JTextField("Bearer ", 8), new JTextField("Bearer ", 8), new JTextField("Bearer ", 8)
          },
      refreshCsrfHeaders =
          {
            new JTextField("x-csrf-token", 12),
            new JTextField("x-csrf-token", 12),
            new JTextField("x-csrf-token", 12)
          };
  final JTextArea matrixRules = new JTextArea(5, 60);
  final TargetWindow sessionSettings =
      new TargetWindow(this, "계정 세션 · 역할/조직 권한", this::sessionSettingsContent);

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
      BountySession.Config[] sessions,
      BountyMatrix matrix,
      String[] roles,
      String[] tenants,
      boolean largeFiles,
      String[] accountIds,
      Set<String> identityCookies) {
    Context {
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
  }

  static final class Run {
    final long no;
    final HttpRequest original;
    final HttpResponse response;
    final Context context;
    final Packet[] packets = {new Packet(), new Packet(), new Packet()};
    String note = "진행 중";

    Run(long no, HttpRequest q, HttpResponse s, Context c) {
      this.no = no;
      original = q;
      response = s;
      context = c;
      for (int i = 0; i < 3; i++) if (c.profiles[i] != null) packets[i].note = "대기";
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
    top.add(
        WorkbenchUi.row(
            record,
            button("선택 요청 비교", this::compareSelection),
            button("중지", this::stop),
            button("비교 기록 비우기", this::clearHistory),
            settings,
            button("세션·권한 매트릭스", sessionSettings::open),
            more));
    top.add(state);
    candidates.setName("wb.auth.credentials");
    candidates.setAutoCreateRowSorter(true);
    candidates.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    candidates.getColumnModel().getColumn(0).setPreferredWidth(45);
    candidates.getColumnModel().getColumn(1).setPreferredWidth(180);
    candidates.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    candidates.getColumnModel().getColumn(2).setPreferredWidth(420);
    candidates.getColumnModel().getColumn(3).setPreferredWidth(110);
    JPopupMenu popup = new JPopupMenu();
    for (int i = 0; i < 3; i++) {
      final int slot = i;
      JMenu group = new JMenu();
      slotMenus[i] = group;
      JMenuItem assign = new JMenuItem("선택 후보 지정"),
          anonymous = new JMenuItem("비로그인"),
          clearSlot = new JMenuItem("해제");
      slotAssign[i] = assign;
      assign.addActionListener(e -> safe(() -> assignSelected(slot)));
      anonymous.addActionListener(e -> safe(() -> anonymous(slot)));
      clearSlot.addActionListener(e -> safe(() -> clearSlot(slot)));
      group.add(assign);
      group.add(anonymous);
      group.add(clearSlot);
      popup.add(group);
    }
    refreshSlots();
    popup.addPopupMenuListener(
        new javax.swing.event.PopupMenuListener() {
          public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
            refreshSlots();
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

          public void mouseReleased(java.awt.event.MouseEvent e) {
            showPopup(e);
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
    JPanel candidateCard = WorkbenchUi.card("인증 정보 후보", candidateScroll);
    candidateCard.setName("wb.card.수집한 인증 정보 · 중복 제거 · 우클릭으로 인가 지정");
    history.setName("wb.auth.history");
    history.setAutoCreateRowSorter(true);
    history.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    history.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    history.getColumnModel().getColumn(0).setPreferredWidth(45);
    history.getColumnModel().getColumn(1).setPreferredWidth(320);
    for (int i = 2; i < 5; i++) history.getColumnModel().getColumn(i).setPreferredWidth(210);
    history.getColumnModel().getColumn(5).setPreferredWidth(90);
    history.getColumnModel().getColumn(0).setMaxWidth(65);
    history
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting()) show(current());
            });
    for (int i = 0; i < 4; i++) {
      requests[i] = text();
      responses[i] = text();
      packets.addTab(
          i < 3 ? "비교 계정 " + (i + 1) : "원본",
          WorkbenchUi.split(
              JSplitPane.VERTICAL_SPLIT,
              WorkbenchUi.card("요청 REQUEST", new JScrollPane(requests[i])),
              WorkbenchUi.card("응답 RESPONSE", new JScrollPane(responses[i])),
              .5));
    }
    JPanel details = new JPanel(new BorderLayout(0, 4));
    details.add(
        WorkbenchUi.row(detail, mode, button("현재 패킷 → Repeater", this::repeater), legend()),
        BorderLayout.NORTH);
    judgementDetails.setEditable(false);
    judgementDetails.setLineWrap(true);
    judgementDetails.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    details.add(WorkbenchUi.split(JSplitPane.VERTICAL_SPLIT, packets,
        WorkbenchUi.card("판정 근거 · JSON 필드 차이", new JScrollPane(judgementDetails)), .70));
    JPanel historyCard = WorkbenchUi.card("비교 기록", new JScrollPane(history));
    historyCard.setName("wb.card.Record History");
    JSplitPane lists =
        WorkbenchUi.split(JSplitPane.VERTICAL_SPLIT, candidateCard, historyCard, .35);
    lists.setName("wb.auth.lists");
    JSplitPane workspace = WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT, lists, details, .45);
    workspace.setName("wb.auth.workspace");
    add(
        WorkbenchUi.header(FeatureGuide.title("auth"), "비교 계정 지정 → 선택 요청 비교 → 응답·근거 확인", top),
        BorderLayout.NORTH);
    add(workspace);
    record.setToolTipText("ON이면 이후 조건에 맞는 Proxy·Repeater 요청을 비교 계정으로 자동 재전송합니다. 단순 기록 기능이 아닙니다.");
    record.addActionListener(
        e ->
            safe(
                () -> {
                  if (record.isSelected()) startRecord();
                  else stop();
                }));
    mode.addActionListener(e -> show(current()));
    colors.addActionListener(e -> show(current()));
    pretty.addActionListener(e -> show(current()));
    WorkbenchUi.style(this);
    WorkbenchUi.status(state);
    WorkbenchUi.feedback(state);
    show(null);
  }

  static JLabel legend() {
    JLabel label = new JLabel("차이 색상 안내");
    label.setToolTipText("보라: 인증 · 주황: 헤더/상태 · 노랑: 본문");
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
      b.setToolTipText("목록에서 선택한 요청을 지정 계정으로 실제 재전송합니다. 응답과 예상 정책을 함께 확인하세요.");
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
        record.setText("새 요청 자동 비교 OFF");
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
    long generation = candidateEpoch.get();
    Set<String> names = authNames, cookies = cookieNames;
    Map<String, String> headers = new LinkedHashMap<>();
    for (HttpHeader h : q.headers()) {
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
    if (recording.get() == null && active.get() == 0)
      for (int i = 0; i < 3; i++) if (slots[i] != null && slots[i].key.equals(key)) slots[i] = next;
    refreshCandidates(selected == null ? null : selected.key);
    refreshSlots();
  }

  void observe(HttpRequest q, HttpResponse response) {
    applyObservation(prepareObservation(q, response));
  }

  void refreshCandidates(String selected) {
    candidateModel.setRowCount(0);
    int model = 0;
    for (Candidate c : credentials.values()) {
      candidateModel.addRow(
          new Object[] {
            c.no,
            c.origin,
            String.join(
                " | ",
                c.headers.entrySet().stream().map(h -> h.getKey() + ": " + h.getValue()).toList()),
            c.key.substring(0, 12)
          });
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
    for (int j = 0; j < 3; j++)
      if (i != j && slots[j] != null && !slots[j].origin.equals(c.origin))
        throw new IllegalArgumentException("비교 계정 1/2/3은 같은 사이트(Origin)여야 합니다.");
    if (slots[i] == null || !slots[i].key.equals(c.key)) trackingAccounts[i] = "";
    slots[i] = c;
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
    assign(i, new Candidate(0, "anonymous", first.origin, Map.of(), 1, ""));
  }

  void clearSlot(int i) {
    editable();
    slots[i] = null;
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
    for (int i = 0; i < 3; i++)
      panel.add(
          WorkbenchUi.row(
              new JLabel("계정 " + (i + 1) + " 이름"),
              profileNames[i],
              new JLabel("소유값"),
              profileOwners[i]));
    panel.add(WorkbenchUi.row(new JLabel("JSON 비교 제외 (쉼표)"), ignoredFields));
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
    panel.add(
        button(
            "설정 확인",
            () -> {
              editable();
              ruleSnapshot();
              AuthPolicy.parse(
                  policyRules.getText(),
                  AuthPolicy.Access.values()[accessPolicy.getSelectedIndex()]);
              refreshSlots();
              state.setText("설정 확인 · 다음 비교부터 적용, 기존 결과는 당시 설정 유지");
            }));
    return panel;
  }

  AuthJudgement.Rules ruleSnapshot() {
    return AuthJudgement.rules(ignoredFields.getText(), denialRules.getText(), ownerPath.getText());
  }

  String label(int i) {
    String text = profileNames[i].getText().strip();
    return text.isBlank() ? "비교 계정 " + (i + 1) : text.substring(0, Math.min(text.length(), 32));
  }

  void refreshSlots() {
    for (int i = 0; i < 3; i++) {
      Candidate c = slots[i];
      slotLabels[i].setText(
          c == null ? "미지정" : c.headers.isEmpty() ? "비로그인" : c.key.substring(0, 12));
      slotLabels[i].setToolTipText(
          c == null ? null : c.origin + " · " + String.join(", ", c.headers.keySet()));
      if (slotMenus[i] != null) {
        slotMenus[i].setText(label(i) + " · " + slotLabels[i].getText());
        slotMenus[i].setToolTipText(slotLabels[i].getToolTipText());
        slotAssign[i].setEnabled(
            selectedCandidate() != null && recording.get() == null && active.get() == 0);
        for (int k = 1; k < slotMenus[i].getItemCount(); k++)
          slotMenus[i].getItem(k).setEnabled(recording.get() == null && active.get() == 0);
      }
    }
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
    candidateModel.setRowCount(0);
    Arrays.fill(slots, null);
    Arrays.fill(trackingAccounts, "");
    refreshTracking();
    refreshSlots();
  }

  JPanel sessionSettingsContent() {
    refreshTracking();
    JPanel panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    for (int i = 0; i < 3; i++) {
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
    panel.add(WorkbenchUi.card("기대 권한 · METHOD /path 계정1 계정2 계정3", new JScrollPane(matrixRules)));
    panel.add(
        new JLabel("예: GET /api/posts/102 DENY ALLOW DENY · ALLOW/DENY/UNKNOWN · 역할/조직은 진단자 입력"));
    panel.add(
        button(
            "설정 확인",
            () -> {
              editable();
              sessionConfigs();
              BountyMatrix.parse(matrixRules.getText());
              refreshed.clear();
              state.setText("세션·매트릭스 확인 · 다음 비교부터 적용 · 설정/토큰 파일 저장 없음");
            }));
    WorkbenchUi.style(panel);
    return panel;
  }

  BountySession.Config[] sessionConfigs() {
    BountySession.Config[] configs = new BountySession.Config[3];
    for (int i = 0; i < 3; i++) {
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
    String[] labels = new String[3], owners = new String[3];
    for (int i = 0; i < 3; i++) {
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
    String[] roles = new String[3], tenants = new String[3];
    for (int i = 0; i < 3; i++) {
      roles[i] = profileRoles[i].getText().strip();
      tenants[i] = profileTenants[i].getText().strip();
      if (roles[i].length() > 64 || tenants[i].length() > 64)
        throw new IllegalArgumentException("역할·조직은 64자 이하입니다.");
    }
    List<AssessmentTracker.Binding> links = new ArrayList<>();
    for (int i = 0; i < 3; i++)
      if (!trackingAccounts[i].isEmpty()) {
        if (slots[i] == null) throw new IllegalArgumentException("점검 이력 계정에 연결할 슬롯이 없습니다.");
        links.add(
            new AssessmentTracker.Binding(
                trackingAccounts[i], slots[i].origin, slots[i].headers, names, cookieNames));
      }
    trackingBind.accept(links);
    return new Context(
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
        sessions,
        BountyMatrix.parse(matrixRules.getText()),
        roles,
        tenants,
        largeEnabled,
        trackingAccounts,
        cookieNames);
  }

  void startRecord() {
    if (closed.get()) return;
    if (active.get() > 0) throw new IllegalArgumentException("진행 중 비교가 끝난 후 자동 비교를 시작하세요.");
    epoch.incrementAndGet();
    Context c = context();
    recording.set(c);
    record.setSelected(true);
    record.setText("새 요청 자동 비교 ON");
    state.setText("자동 비교 중 · Proxy/Repeater 신규 요청 재전송 · " + c.origin + " · 프로필 고정");
  }

  boolean eligible(HttpRequest q, Context c) {
    try {
      return WorkbenchScope.contains(api, q)
          && origin(q).equals(c.origin)
          && (c.writes || Core.readMethod(q.method()))
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

  void received(int id, HttpRequest q, HttpResponse s) {
    Context c = tickets.remove(id);
    if (c != null && !closed.get() && c.epoch == epoch.get())
      SwingUtilities.invokeLater(
          () -> {
            if (!closed.get() && c.epoch == epoch.get()) enqueue(q, s, c);
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
    if (recording.get() != null) throw new IllegalArgumentException("새 요청 자동 비교를 끄고 선택 요청을 비교하세요.");
    List<HttpRequestResponse> list = selected.get();
    if (list.isEmpty()) throw new IllegalArgumentException("요청·응답 목록에서 요청을 선택하세요.");
    if (list.size() > 20) throw new IllegalArgumentException("최대 20개 요청입니다.");
    Context c = context();
    for (var rr : list) validate(rr.request(), rr.response(), c);
    for (var rr : list) enqueue(rr.request(), rr.response(), c);
  }

  void enqueue(HttpRequest q, HttpResponse s, Context c) {
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
    if (runs.size() >= LIMIT) {
      runs.remove(0);
      historyModel.removeRow(0);
    }
    runs.add(run);
    historyModel.addRow(new Object[] {run.no, Core.safeUrl(q.url()), "대기", "대기", "대기", run.note});
    if (history.getSelectedRow() < 0)
      history.setRowSelectionInterval(history.getRowCount() - 1, history.getRowCount() - 1);
    active.incrementAndGet();
    try {
      worker.execute(() -> execute(run));
    } catch (RejectedExecutionException e) {
      active.decrementAndGet();
      run.note = "대기열 거부";
      refresh(run);
    }
  }

  void execute(Run run) {
    Context c = run.context;
    try {
      boolean first = true;
      for (int i = 0; i < 3; i++) {
        if (c.profiles[i] == null) continue;
        if (closed.get() || c.epoch != epoch.get()) break;
        if (!first) Thread.sleep(500);
        first = false;
        if (closed.get() || c.epoch != epoch.get()) break;
        final int refreshingSlot = i;
        Map<String, String> session = c.profiles[i].headers;
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
        } catch (Exception ex) {
          publish(
              run,
              () -> {
                run.packets[refreshingSlot].note = "갱신 실패 · 비교 미전송 · " + ex.getMessage();
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
                    c.labels[refreshingSlot] + ": 세션 갱신 확인 필요");
              });
          continue;
        }
        if (closed.get() || c.epoch != epoch.get()) break;
        HttpRequest q = Core.sessionRequest(run.original, session, c.names);
        if (!eligible(q, c)) throw new IllegalArgumentException("전송 직전 Scope/Origin 변경");
        final int slot = i;
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
                run.packets[slot].note = "전송 오류";
                trackingSink.accept(
                    c.accountIds[slot], q, null, "전송 오류", true, c.names, c.identityCookies);
              });
          continue;
        }
        HttpRequest actual = rr != null && rr.request() != null ? rr.request() : q;
        HttpResponse response = rr == null ? null : rr.response();
        String note = "응답 없음", changes = "";
        boolean review = false, existenceVerified = false;
        if (response != null) {
          if (!bounded(actual, response, c.largeFiles)) {
            response = null;
            note = "응답 저장 한도 초과";
          } else if (!sameTarget(actual, q) || !WorkbenchScope.contains(api, actual))
            note = "판정 보류 · 실제 전송 대상/메서드 변경 · Burp 규칙 확인";
          else if (!credentialValues(actual, c.names).equals(credentialValues(q, c.names)))
            note = "전송 세션 변경 · Burp 규칙 확인";
          else if (credentialValues(q, c.names).equals(credentialValues(run.original, c.names)))
            note = "원본과 동일 인증 · 교차 인가 미검증";
          else if (c.largeFiles
              && (run.response.toByteArray().length() > MESSAGE_LIMIT
                  || response.toByteArray().length() > MESSAGE_LIMIT)) {
            note = AuthFileEvidence.note(run.response, response);
            changes =
                "원본 "
                    + run.response.body().length()
                    + " bytes · SHA-256 "
                    + AuthFileEvidence.digest(run.response)
                    + "\n대상 "
                    + response.body().length()
                    + " bytes · SHA-256 "
                    + AuthFileEvidence.digest(response);
            review = !note.startsWith("차단 근거 확인");
          } else
            try {
              if ((response.statusCode() == 404 || response.statusCode() == 410)
                  && Set.of("GET", "HEAD").contains(run.original.method())
                  && eligible(run.original, c)
                  && !closed.get()
                  && c.epoch == epoch.get()) {
                try {
                  var check =
                      api.http()
                          .sendRequest(
                              run.original,
                              RequestOptions.requestOptions()
                                  .withResponseTimeout(10000)
                                  .withRedirectionMode(RedirectionMode.NEVER));
                  existenceVerified =
                      check != null
                          && check.request() != null
                          && sameTarget(check.request(), run.original)
                          && WorkbenchScope.contains(api, check.request())
                          && credentialValues(check.request(), c.names)
                              .equals(credentialValues(run.original, c.names))
                          && check.response() != null
                          && run.response.statusCode() >= 200
                          && run.response.statusCode() < 300
                          && check.response().statusCode() >= 200
                          && check.response().statusCode() < 300
                          && bounded(run.original, check.response())
                          && JsAnalysisPanel.readBody(check.response())
                              .equals(JsAnalysisPanel.readBody(run.response));
                } catch (Exception ignored) {
                }
              }
              var result =
                  AuthJudgement.compare(
                      run.response.statusCode(),
                      JsAnalysisPanel.readBody(run.response),
                      response.statusCode(),
                      JsAnalysisPanel.readBody(response),
                      new AuthJudgement.Rules(
                          c.rules.ignored(),
                          c.rules.denied(),
                          c.rules.ownerPath(),
                          c.policy.forRequest(run.original.method(), run.original.url())),
                      c.owners[slot],
                      existenceVerified);
              note = result.note();
              changes = result.changes();
              review = !Set.of("차단 근거 확인", "공개/공유 허용", "은닉형 접근 차단 후보").contains(result.state());
            } catch (Exception ex) {
              note = "판정 보류 · 응답 디코딩/구조 제한";
              review = true;
            }
        }
        if (response != null
            && c.matrix.expected(run.original.method(), run.original.url(), slot)
                != BountyMatrix.Expected.UNKNOWN
            && sameTarget(actual, q)
            && WorkbenchScope.contains(api, actual)
            && credentialValues(actual, c.names).equals(credentialValues(q, c.names))
            && !credentialValues(q, c.names).equals(credentialValues(run.original, c.names))
            && run.response.statusCode() >= 200
            && run.response.statusCode() < 300) {
          try {
            String matrix =
                c.matrix.assess(
                    c.matrix.expected(run.original.method(), run.original.url(), slot),
                    response.statusCode(),
                    response.body().length() > MESSAGE_LIMIT
                        ? "[대용량 본문 · 해시 관찰]"
                        : JsAnalysisPanel.readBody(response),
                    c.rules,
                    existenceVerified);
            if (!matrix.isEmpty()) {
              note =
                  matrix
                      + (response.body().length() > MESSAGE_LIMIT
                          ? " · " + AuthFileEvidence.note(run.response, response)
                          : "");
              review = matrix.contains("후보") || matrix.contains("보류") || matrix.contains("재검토");
              if (c.matrix.expected(run.original.method(), run.original.url(), slot)
                      == BountyMatrix.Expected.ALLOW
                  && c.policy.forRequest(run.original.method(), run.original.url())
                      == AuthPolicy.Access.OWNER_ONLY) note += " · 설정 충돌: 매트릭스 우선, 소유자 전용 정책 검토";
            }
          } catch (Exception ex) {
            note = "매트릭스 판정 보류 · 응답 디코딩 확인 · " + note;
            review = true;
          }
        }
        final HttpResponse reply = response;
        final String observation = note, difference = changes;
        final boolean needsReview = review;
        publish(
            run,
            () -> {
              run.packets[slot].request = actual;
              run.packets[slot].response = reply;
              run.packets[slot].note = observation;
              run.packets[slot].changes = difference;
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
              for (Packet p : run.packets) if (p.note.equals("대기")) p.note = "중지 / 미전송";
              refresh(run);
            }
          });
    }
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
      bytes += r.original.body().length() + (r.response == null ? 0 : r.response.body().length());
      for (Packet p : r.packets)
        bytes +=
            (p.request == null ? 0 : p.request.body().length())
                + (p.response == null ? 0 : p.response.body().length());
    }
    while (bytes > 32L * 1024 * 1024 && runs.size() > 1) {
      int n = runs.get(0) == keep ? 1 : 0;
      Run old = runs.remove(n);
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
    for (int i = 0; i < 3; i++) {
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
    for (int i = 0; i < 3; i++)
      historyModel.setValueAt(
          run.context.labels[i]
              + ": "
              + (run.packets[i].response == null ? "" : run.packets[i].response.statusCode() + " ")
              + run.packets[i].note,
          index,
          2 + i);
    historyModel.setValueAt(run.note, index, 5);
    if (current() == run) show(run);
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
    Set<String> names = new HashSet<>(authNames);
    names.addAll(CSRF);
    String[] req = new String[3], res = new String[3];
    if (run != null) {
      names.addAll(run.context.names);
      for (int i = 0; i < 3; i++) {
        req[i] = format(run.packets[i].request == null ? null : run.packets[i].request.toString());
        res[i] = formatResponse(run.packets[i].response);
      }
    }
    PacketDiff.View[] q = PacketDiff.compare(req, names), s = PacketDiff.compare(res, names);
    for (int i = 0; i < 3; i++) {
      if (run != null && mode.getSelectedIndex() == 1) {
        q[i] = PacketDiff.compare(new String[] {format(run.original.toString()), req[i]}, names)[1];
        s[i] = PacketDiff.compare(new String[] {formatResponse(run.response), res[i]}, names)[1];
      }
      paint(requests[i], q[i], changed);
      paint(responses[i], s[i], changed);
      packets.setTitleAt(i, run == null ? label(i) : run.context.labels[i]);
      packets.setIconAt(
          i,
          run == null
              ? null
              : WorkbenchUi.statusIcon(
                  run.packets[i].response == null
                      ? run.packets[i].note
                      : String.valueOf(run.packets[i].response.statusCode())));
      packets.setToolTipTextAt(i, run == null ? null : run.packets[i].note);
    }
    paint(
        requests[3],
        PacketDiff.compare(
            new String[] {run == null ? null : format(run.original.toString())}, names)[0],
        changed);
    paint(
        responses[3],
        PacketDiff.compare(new String[] {run == null ? null : formatResponse(run.response)}, names)[
            0],
        changed);
    detail.setText(
        run == null
            ? "비교 기록에서 항목을 선택하세요"
            : "NO "
                + String.format("%03d", run.no)
                + " · "
                + run.original.method()
                + " · "
                + run.note);
    detail.setToolTipText(run == null ? null : run.original.url());
    StringBuilder reasons = new StringBuilder();
    if (run != null)
      for (int i = 0; i < 3; i++)
        reasons
            .append(run.context.labels[i])
            .append(" [역할=")
            .append(run.context.roles[i])
            .append(", 조직=")
            .append(run.context.tenants[i])
            .append("] : ")
            .append(run.packets[i].note)
            .append("\n")
            .append(run.packets[i].changes)
            .append("\n\n");
    judgementDetails.setText(reasons.toString());
    judgementDetails.setCaretPosition(0);
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
    Run r = current();
    if (r == null) throw new IllegalArgumentException("비교 기록에서 항목을 선택하세요.");
    int i = packets.getSelectedIndex();
    HttpRequest q = i == 3 ? r.original : r.packets[i].request;
    if (q == null) throw new IllegalArgumentException("전송한 요청이 없습니다.");
    api.repeater().sendToRepeater(q, (i == 3 ? "원본" : "비교 계정 " + (i + 1)) + " · NO " + r.no);
  }

  void stop() {
    recording.set(null);
    epoch.incrementAndGet();
    tickets.clear();
    record.setSelected(false);
    record.setText("새 요청 자동 비교 OFF");
    state.setText("중지 · 이미 발송한 요청은 응답/타임아웃 후 종료합니다.");
  }

  void clearHistory() {
    stop();
    runs.clear();
    historyModel.setRowCount(0);
    show(null);
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
    comparisonSettings.close();
    sessionSettings.close();
    refreshed.clear();
    closed.set(true);
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
          record.setText("새 요청 자동 비교 OFF");
          show(null);
        });
  }
}
