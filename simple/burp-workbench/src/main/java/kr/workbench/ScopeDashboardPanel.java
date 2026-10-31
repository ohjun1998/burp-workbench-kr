package kr.workbench;

import java.awt.*;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;

/** A read-only view of the live inspection snapshot. Only explicit handoffs leave this panel. */
final class ScopeDashboardPanel extends JPanel implements AutoCloseable {
  interface Actions {
    void inspect();
    void original(String originalId);
    void repeater(String originalId);
    void evidence(String originalId);
  }

  /** A deterministic worker seam; production uses the no-op hook. Never called on EDT. */
  interface ProjectionHook {
    void before(ScopeInspection.Snapshot snapshot, String account) throws InterruptedException;
  }

  record AccountChoice(String value) {
    @Override public String toString() { return value.isEmpty() ? "전체 결과 계정" : value; }
  }

  private record Update(ScopeInspection.Snapshot snapshot, int budget, String target) {}
  private record Projection(ScopeInspection.Snapshot snapshot, String account,
      Map<ScopeInspectionDashboard.Group, List<ScopeInspectionDashboard.Row>> rows, long epoch) {}
  private record ProjectionRequest(ScopeInspection.Snapshot snapshot, String account, long epoch) {}
  private record ProjectionCompletion(ProjectionRequest request, Projection projection, String error) {}
  static final int ASYNC_THRESHOLD = 500;
  private static final ScopeInspection.Snapshot EMPTY = new ScopeInspection.Snapshot(
      "", ScopeInspection.State.READY, "", 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(), 0, 0);
  private static final ScopeInspectionDashboard.Group[] CARD_GROUPS = {
      ScopeInspectionDashboard.Group.PATHS, ScopeInspectionDashboard.Group.API,
      ScopeInspectionDashboard.Group.ENVIRONMENT, ScopeInspectionDashboard.Group.SECRETS,
      ScopeInspectionDashboard.Group.ACCESS, ScopeInspectionDashboard.Group.COVERAGE
  };

  final Actions actions;
  final JComboBox<AccountChoice> account = new JComboBox<>();
  final JComboBox<String> importance = new JComboBox<>(new String[] {"전체 중요도", "높음", "중간", "낮음", "정보"});
  final JButton inspect = new JButton("통합 점검 열기"), reset = new JButton("조건 해제");
  final JButton original = new JButton("원본 보기"), repeater = new JButton("Repeater 준비"), evidence = new JButton("증거 추가");
  final JLabel target = new LiteralLabel("SCOPE: 아직 선택한 실행이 없습니다.");
  final JLabel run = new LiteralLabel("현재 실행: 없음"), traffic = new LiteralLabel("요청 기록 없음");
  final JLabel filterSummary = new LiteralLabel("통합 점검을 시작하면 결과를 확인할 수 있습니다.");
  final JTextArea detail = prose(7, 28), emptyMessage = prose(3, 35);
  final JLabel detailTitle = new LiteralLabel("선택 결과");
  final JTextArea detailFacts = prose(4, 28), detailSummary = prose(4, 28), detailOrigin = prose(3, 28);
  final Map<ScopeInspectionDashboard.Group, MetricButton> cards = new EnumMap<>(ScopeInspectionDashboard.Group.class);
  final Map<ScopeInspectionDashboard.Group, BarButton> categoryButtons = new EnumMap<>(ScopeInspectionDashboard.Group.class);
  final Map<String, BarButton> severityButtons = new LinkedHashMap<>();
  final DefaultTableModel model = new DefaultTableModel(
      new String[] {"내용", "계정", "중요도", "상태", "확실성", "메서드", "URL"}, 0) {
    @Override public boolean isCellEditable(int row, int column) { return false; }
  };
  final JTable table = new LiteralTable(model);
  final JPanel resultViews = new JPanel(new CardLayout());
  final JPanel metricGrid = new JPanel(new GridLayout(2, 3, 8, 6));
  private final JPanel resultsSection = new JPanel(new BorderLayout(0, 6));
  private final JScrollPane contentScroll;
  private final AtomicLong revealVersion = new AtomicLong();
  final KeyedTableRows<String> keyedRows = new KeyedTableRows<>();
  volatile ScopeInspection.Snapshot current = EMPTY;
  List<ScopeInspectionDashboard.Row> visibleRows = List.of();
  ScopeInspectionDashboard.Group group = ScopeInspectionDashboard.Group.PATHS;
  String selectedSeverity = "";
  volatile boolean closed;
  private boolean rebuilding;
  private List<String> knownAccounts = List.of();
  private final Object updateLock = new Object();
  private Update pending;
  private boolean scheduled;
  private volatile Projection projection;
  private final ProjectionHook projectionHook;
  private final ExecutorService projectionWorker = Executors.newSingleThreadExecutor(task -> {
    Thread thread = new Thread(task, "Workbench-dashboard-projection"); thread.setDaemon(true); return thread;
  });
  private long projectionEpoch;
  private ProjectionRequest desiredProjection, pendingProjection, failedProjection;
  private ProjectionCompletion readyProjection;
  private boolean workerRunning, completionScheduled, selectFirstWhenReady;
  private String projectionError = "";
  volatile boolean aggregating;

  ScopeDashboardPanel(Actions actions) {
    this(actions, (snapshot, account) -> {});
  }

  ScopeDashboardPanel(Actions actions, ProjectionHook hook) {
    super(new BorderLayout());
    this.actions = Objects.requireNonNull(actions);
    projectionHook = Objects.requireNonNull(hook);
    name(this, "root");
    setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    var content = new ScrollContent();
    name(content, "content");
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    name(inspect, "inspect");
    inspect.addActionListener(e -> { if (!closed) actions.inspect(); });
    inspect.putClientProperty("wb.primary", false); WorkbenchUi.primary(original);
    name(target, "target"); name(run, "run"); name(traffic, "traffic");
    target.setMinimumSize(new Dimension(0, 20));
    run.setMinimumSize(new Dimension(0, 20));
    traffic.setMinimumSize(new Dimension(0, 20));
    traffic.setToolTipText("전송 / 요청 한도는 예산 사용량입니다. 처리 수에는 실패가 포함될 수 있으며 전체 사이트 점검률이 아닙니다.");
    name(account, "account"); name(reset, "reset");
    account.setRenderer(new DefaultListCellRenderer() {
      { putClientProperty("html.disable", true); }
    });
    account.setPrototypeDisplayValue(new AccountChoice("전체 결과 계정 · 계정 이름"));
    account.addItem(new AccountChoice(""));
    account.addActionListener(e -> { if (!rebuilding && !closed) { refresh(true); revealResults(); } });
    reset.setToolTipText("선택 분류는 유지하고 계정·중요도 조건을 해제합니다.");
    reset.addActionListener(e -> {
      if (closed) return;
      rebuilding = true;
      try { account.setSelectedIndex(0); selectedSeverity = ""; }
      finally { rebuilding = false; }
      refresh(true); revealResults();
    });
    name(importance, "importance");
    // Reserve the arrow and field padding as well as the complete selected caption.
    importance.setPrototypeDisplayValue("전체 중요도 선택      ");
    importance.addActionListener(e -> {
      if (rebuilding || closed) return;
      selectedSeverity = switch (importance.getSelectedIndex()) { case 1 -> "HIGH"; case 2 -> "MEDIUM"; case 3 -> "LOW"; case 4 -> "INFO"; default -> ""; };
      if (!selectedSeverity.isEmpty()) group = ScopeInspectionDashboard.Group.SECRETS;
      table.clearSelection(); refresh(true); revealResults();
    });
    JPanel filters = WorkbenchUi.row(new JLabel("계정"), account, importance, reset);
    filters.setName("wb.actions.dashboard.filters");
    JPanel context = CompactWorkbenchUi.toolbar(target, WorkbenchUi.row(inspect));
    context.setName("wb.actions.dashboard.context");
    addSection(content, context, 2);
    JPanel metadata = CompactWorkbenchUi.toolbar(run, traffic);
    metadata.setName("wb.actions.dashboard.metadata");
    addSection(content, metadata, 4);

    name(metricGrid, "cards");
    for (var value : CARD_GROUPS) {
      var card = new MetricButton(cardLabel(value), cardHint(value));
      name(card, "card." + key(value));
      card.addActionListener(e -> choose(value));
      cards.put(value, card); metricGrid.add(card);
    }
    addSection(content, metricGrid, 7);
    addSection(content, filters, 4);

    var categories = new JPanel(new GridLayout(0, 1, 0, 2));
    for (var value : ScopeInspectionDashboard.Group.values()) {
      var bar = new BarButton(ScopeInspectionDashboard.label(value));
      name(bar, "bar." + key(value));
      bar.addActionListener(e -> choose(value));
      categoryButtons.put(value, bar); categories.add(bar);
    }
    var severities = new JPanel(new GridLayout(0, 1, 0, 4));
    for (String severity : List.of("HIGH", "MEDIUM", "LOW", "INFO")) {
      var bar = new BarButton(ScopeInspectionDashboard.severityLabel(severity));
      name(bar, "severity." + severity.toLowerCase(java.util.Locale.ROOT));
      bar.addActionListener(e -> {
        if (closed) return;
        group = ScopeInspectionDashboard.Group.SECRETS; selectedSeverity = severity;
        table.clearSelection(); refresh(true); revealResults();
      });
      severityButtons.put(severity, bar); severities.add(bar);
    }
    var charts = new JPanel(new GridLayout(1, 2, 10, 0));
    name(charts, "charts");
    charts.add(WorkbenchUi.card("분류별 결과 · 클릭하여 보기", categories));
    var secretChart = new JPanel(new BorderLayout(0, 8));
    secretChart.add(severities);
    var candidateNote = new JLabel("민감정보 후보의 중요도 · 유효성은 상세에서 확인");
    candidateNote.putClientProperty("wb.font.size", 11);
    secretChart.add(candidateNote, BorderLayout.SOUTH);
    charts.add(WorkbenchUi.card("민감정보 후보 중요도", secretChart));
    JPanel distribution = CompactWorkbenchUi.disclosure("wb.flow.dashboard.distribution", "분포 자세히", charts, false);

    name(table, "results"); table.putClientProperty("wb.table.compact", true);
    table.setAutoCreateRowSorter(true);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    table.getSelectionModel().addListSelectionListener(e -> {
      if (!e.getValueIsAdjusting() && !rebuilding) selectedChanged();
    });
    int[] widths = {220, 95, 72, 135, 100, 75, 300};
    for (int i = 0; i < widths.length; i++) { var column = table.getColumnModel().getColumn(i); column.setPreferredWidth(widths[i]); column.setWidth(widths[i]); }
    var empty = new JPanel(new BorderLayout());
    name(emptyMessage, "empty"); empty.add(emptyMessage, BorderLayout.NORTH);
    resultViews.add(new JScrollPane(table), "rows"); resultViews.add(empty, "empty");
    name(detail, "detail");
    name(original, "original"); name(repeater, "repeater"); name(evidence, "evidence");
    original.addActionListener(e -> handoff(actions::original));
    repeater.addActionListener(e -> handoff(actions::repeater));
    evidence.addActionListener(e -> handoff(actions::evidence));
    var details = new JPanel(new BorderLayout(0, 10));
    name(detailTitle, "detail.title"); name(detailFacts, "detail.facts");
    name(detailSummary, "detail.summary"); name(detailOrigin, "detail.origin");
    detailTitle.putClientProperty("wb.font.heading", true);
    detailTitle.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
    var facts = new JPanel(new BorderLayout(0, 4));
    facts.add(detailTitle, BorderLayout.NORTH);
    detailFacts.setBorder(BorderFactory.createEmptyBorder(6, 12, 10, 12));
    facts.add(detailFacts);
    details.add(facts, BorderLayout.NORTH);
    var summary = new JPanel(new BorderLayout(0, 6));
    JLabel summaryTitle = new JLabel("근거 요약");
    summaryTitle.setBorder(BorderFactory.createEmptyBorder(8, 12, 0, 12));
    summary.add(summaryTitle, BorderLayout.NORTH);
    summary.add(new JScrollPane(detailSummary));
    details.add(summary);
    var provenance = CompactWorkbenchUi.disclosure("wb.scope.dashboard.detail.provenance", "원본·처리 정보", new JScrollPane(detailOrigin), false);
    details.add(provenance, BorderLayout.SOUTH);
    // The complete document remains the authoritative model for copying/tests/error feedback.
    // Display sections mirror it, so no source identifier, offset or diagnostic is discarded.
    detail.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
      public void insertUpdate(javax.swing.event.DocumentEvent event) { presentDetail(); }
      public void removeUpdate(javax.swing.event.DocumentEvent event) { presentDetail(); }
      public void changedUpdate(javax.swing.event.DocumentEvent event) { presentDetail(); }
    });
    JPanel further = WorkflowUi.menu("wb.flow.dashboard.further", "다음 작업", repeater, evidence);
    JPanel selectionActions = CompactWorkbenchUi.toolbar(new JLabel("선택 항목 근거"), WorkbenchUi.row(original, further));
    selectionActions.setName("wb.actions.dashboard.selection");
    JPanel detailCard = WorkbenchUi.card("선택 항목 근거", details);
    detailCard.remove(((BorderLayout) detailCard.getLayout()).getLayoutComponent(BorderLayout.NORTH));
    detailCard.add(ReferenceUi.header(selectionActions, null), BorderLayout.NORTH);
    var split = WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT,
        WorkbenchUi.card("해당 결과 목록", resultViews), detailCard, .61);
    name(split, "results.split");
    split.setPreferredSize(new Dimension(850, 380));
    split.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
    name(filterSummary, "filter.summary");
    name(resultsSection, "results.area");
    // Facts and provenance must not consume the actual evidence reader in short workspaces.
    // The outer dashboard scrolls when this minimum cannot fit beneath the overview.
    resultsSection.setMinimumSize(new Dimension(0, WorkbenchUi.displayPixels(360)));
    resultsSection.add(filterSummary, BorderLayout.NORTH); resultsSection.add(split);
    addSection(content, resultsSection, 0);
    addSection(content, distribution, 7);
    contentScroll = new JScrollPane(content);
    name(contentScroll, "scroll"); contentScroll.setBorder(null);
    contentScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    contentScroll.getVerticalScrollBar().setUnitIncrement(24);
    add(contentScroll);
    WorkbenchUi.style(this);
    addComponentListener(new java.awt.event.ComponentAdapter() {
      @Override public void componentResized(java.awt.event.ComponentEvent e) {
        int columns = getWidth() >= WorkbenchUi.displayPixels(1120) ? 6 : 3;
        var layout = (GridLayout) metricGrid.getLayout();
        if (layout.getColumns() != columns) {
          layout.setRows(columns == 6 ? 1 : 2); layout.setColumns(columns);
          metricGrid.revalidate();
        }
      }
    });
    refresh(false);
  }

  private static void addSection(JPanel content, JComponent section, int gap) {
    section.setAlignmentX(Component.LEFT_ALIGNMENT);
    if (gap > 0) {
      JPanel fixed = new JPanel(new BorderLayout()) {
        public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
      };
      fixed.setAlignmentX(Component.LEFT_ALIGNMENT); fixed.add(section);
      content.add(fixed); content.add(Box.createVerticalStrut(gap));
    } else content.add(section);
  }

  private static JTextArea prose(int rows, int columns) {
    var text = new JTextArea(rows, columns);
    text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
    text.putClientProperty("wb.prose", true);
    return text;
  }

  private static void name(JComponent component, String suffix) { component.setName("wb.scope.dashboard." + suffix); }
  private static String key(ScopeInspectionDashboard.Group value) { return value.name().toLowerCase(java.util.Locale.ROOT); }
  private static String cardLabel(ScopeInspectionDashboard.Group value) {
    return switch (value) {
      case PATHS -> "관측 경로"; case API -> "API·명세 항목"; case ENVIRONMENT -> "환경·DB 단서";
      case SECRETS -> "키·민감정보 후보"; case ACCESS -> "계정·권한 검토";
      case COVERAGE -> "미확인·제한 항목"; case OBSERVATIONS -> "응답·내부정보";
    };
  }
  private static String cardHint(ScopeInspectionDashboard.Group value) {
    return switch (value) {
      case PATHS -> "선택 계정 범위에서 메서드와 전체 URL이 같은 관측 경로를 중복 제거합니다.";
      case API -> "API 후보와 문서 선언·관측 여부 등 명세 대조 항목입니다. 호출 성공 수가 아닙니다.";
      case ENVIRONMENT -> "환경·DB 탐지 근거의 건수입니다. 확정된 구성 또는 고유 기술 종류 수와 다릅니다.";
      case SECRETS -> "민감정보 후보입니다. 중요도와 유효성·확신도는 별도로 확인하세요.";
      case ACCESS -> "계정별 응답 차이와 조건 재확인 등 검토 항목입니다. 확정 응답 차이 또는 취약점 수가 아닙니다.";
      case COVERAGE -> "미확인·제외·제한 사유와 실행 불가 후보입니다. 실패 요청 수와 더하지 않습니다.";
      case OBSERVATIONS -> "응답 및 내부정보 단서입니다.";
    };
  }

  /** Coalesces background publications without retaining one queued event per snapshot. */
  void update(ScopeInspection.Snapshot snapshot, int requestBudget, String runTarget) {
    boolean enqueue = false;
    synchronized (updateLock) {
      if (closed) return;
      pending = new Update(snapshot == null ? EMPTY : snapshot, Math.max(0, requestBudget), Objects.toString(runTarget, ""));
      if (!scheduled) { scheduled = true; enqueue = true; }
    }
    if (SwingUtilities.isEventDispatchThread()) drain();
    else if (enqueue) SwingUtilities.invokeLater(this::drain);
  }

  private void drain() {
    Update next;
    synchronized (updateLock) {
      next = pending; pending = null; scheduled = false;
      if (closed || next == null) return;
    }
    boolean newRun;
    synchronized (updateLock) {
      if (closed) return;
      newRun = !current.id().equals(next.snapshot().id());
      current = next.snapshot();
      if (newRun) { invalidateProjectionLocked(); revealVersion.incrementAndGet(); }
    }
    if (newRun) {
      group = ScopeInspectionDashboard.Group.PATHS; selectedSeverity = "";
      rebuilding = true;
      try { account.setSelectedIndex(0); table.clearSelection(); model.setRowCount(0); keyedRows.clear();
        if (table.getRowSorter() != null) table.getRowSorter().setSortKeys(List.of());
      } finally { rebuilding = false; }
    }
    updateAccounts();
    boolean present = !current.id().isEmpty();
    target.setText(present ? "SCOPE: " + ScopeInspectionAnalysis.safeUrl(next.target()) : "SCOPE: 아직 선택한 실행이 없습니다.");
    target.setToolTipText(target.getText());
    run.setText(present ? "최신 실행 상태: " + current.id() + "  ·  " + state(current.state()) : "현재 실행: 없음");
    run.setToolTipText(run.getText());
    traffic.setText(present ? "최신 요청: 전송 " + current.sent() + " / 한도 " + next.budget()
        + "  ·  처리 " + current.completed() + "  ·  대기 " + current.pending()
        + "  ·  실패 " + current.failed() + "  ·  제외 " + current.excluded() : "요청 기록 없음");
    refresh(false);
  }

  private void updateAccounts() {
    var next = ScopeInspectionDashboard.accounts(current);
    if (knownAccounts.equals(next)) return;
    String previous = selectedAccount();
    rebuilding = true;
    try {
      account.removeAllItems(); account.addItem(new AccountChoice(""));
      for (String value : next) if (!value.isEmpty()) account.addItem(new AccountChoice(value));
      for (int i = 0; i < account.getItemCount(); i++)
        if (account.getItemAt(i).value().equals(previous)) account.setSelectedIndex(i);
      knownAccounts = List.copyOf(next);
    } finally { rebuilding = false; }
  }

  String selectedAccount() {
    return account.getSelectedItem() instanceof AccountChoice value ? value.value() : "";
  }

  private void choose(ScopeInspectionDashboard.Group value) {
    if (closed) return;
    group = value; selectedSeverity = ""; table.clearSelection(); refresh(true); revealResults();
  }

  /** Only an explicit navigation action reveals results; background publications keep reading position. */
  private void revealResults() {
    if (closed) return;
    long version = revealVersion.incrementAndGet();
    String runId = current.id(), accountValue = selectedAccount(), severity = selectedSeverity;
    var selectedGroup = group;
    Point position = contentScroll.getViewport().getViewPosition();
    SwingUtilities.invokeLater(() -> {
      if (closed || revealVersion.get() != version || !current.id().equals(runId)
          || !selectedAccount().equals(accountValue) || group != selectedGroup || !selectedSeverity.equals(severity)
          || !contentScroll.getViewport().getViewPosition().equals(position)) return;
      for (Component component = this; component != null; component = component.getParent())
        if (!component.isVisible()) return;
      if (resultsSection.getWidth() <= 0 || resultsSection.getHeight() <= 0) return;
      int height = Math.min(resultsSection.getHeight(), contentScroll.getViewport().getExtentSize().height);
      resultsSection.scrollRectToVisible(new Rectangle(0, 0, resultsSection.getWidth(), Math.max(1, height)));
    });
  }

  private void refresh(boolean selectFirst) {
    boolean previousRebuild = rebuilding; rebuilding = true;
    try { importance.setSelectedIndex(switch (selectedSeverity) { case "HIGH" -> 1; case "MEDIUM" -> 2; case "LOW" -> 3; case "INFO" -> 4; default -> 0; }); }
    finally { rebuilding = previousRebuild; }
    String accountValue = selectedAccount();
    Projection projected = projected(accountValue);
    if (projected == null) {
      selectFirstWhenReady |= selectFirst;
      showProjectionUnavailable(accountValue);
      return;
    }
    String failure = projectionFailure(current, accountValue);
    aggregating = projected.snapshot() != current && failure.isEmpty();
    var counts = new EnumMap<ScopeInspectionDashboard.Group, Integer>(ScopeInspectionDashboard.Group.class);
    int maximum = 1;
    for (var value : ScopeInspectionDashboard.Group.values()) {
      int count = projected.rows().get(value).size();
      counts.put(value, count); maximum = Math.max(maximum, count);
    }
    for (var entry : cards.entrySet()) {
      entry.getValue().count = counts.get(entry.getKey());
      entry.getValue().setSelected(group == entry.getKey());
      entry.getValue().setText(cardLabel(entry.getKey()) + " " + entry.getValue().count + "건");
      entry.getValue().repaint();
    }
    for (var entry : categoryButtons.entrySet()) entry.getValue().setValue(counts.get(entry.getKey()), maximum, group == entry.getKey());
    Map<String, Integer> severities = new LinkedHashMap<>();
    for (String value : List.of("HIGH", "MEDIUM", "LOW", "INFO")) severities.put(value, 0);
    for (var row : projected.rows().get(ScopeInspectionDashboard.Group.SECRETS))
      severities.merge(row.severity(), 1, Integer::sum);
    int severityMaximum = Math.max(1, severities.values().stream().mapToInt(Integer::intValue).max().orElse(0));
    for (var entry : severityButtons.entrySet()) entry.getValue().setValue(severities.getOrDefault(entry.getKey(), 0), severityMaximum,
        group == ScopeInspectionDashboard.Group.SECRETS && entry.getKey().equals(selectedSeverity));
    var nextRows = selectedSeverity.isEmpty() ? projected.rows().get(group)
        : projected.rows().get(group).stream().filter(row -> selectedSeverity.equals(row.severity())).toList();
    rebuilding = true;
    try {
      visibleRows = nextRows;
      keyedRows.update(table, nextRows, ScopeInspectionDashboard.Row::id,
          row -> new Object[] {row.title(), row.account(), ScopeInspectionDashboard.severityLabel(row.severity()),
              row.state(), row.confidence(), row.method(), row.url()});
      if (selectFirst && table.getSelectedRow() < 0 && table.getRowCount() > 0) table.setRowSelectionInterval(0, 0);
    } finally { rebuilding = false; }
    int total = counts.getOrDefault(group, 0);
    String accountLabel = accountValue.isEmpty() ? "전체 결과 계정" : accountValue;
    String freshness = projected.snapshot() != current
        ? "이전 집계 표시 · " + (failure.isEmpty() ? "새 결과 집계 중" : "새 결과 집계 실패") + " | " : "";
    updateRunStatus(freshness);
    filterSummary.setText(freshness + cardLabel(group) + " · " + accountLabel + " · 전체 " + total + "건 중 조건에 맞는 " + visibleRows.size() + "건"
        + (selectedSeverity.isEmpty() ? "" : " · 중요도 " + ScopeInspectionDashboard.severityLabel(selectedSeverity)));
    filterSummary.setToolTipText(filterSummary.getText() + " · 카드와 목록은 동일한 집계 결과이며, 상단 요청 상태는 최신 스냅샷입니다."
        + (failure.isEmpty() ? "" : " · " + failure));
    ((CardLayout) resultViews.getLayout()).show(resultViews, visibleRows.isEmpty() ? "empty" : "rows");
    replaceText(emptyMessage, current.id().isEmpty()
        ? "아직 실행 결과가 없습니다.\n통합 점검 열기에서 SCOPE와 계정을 선택한 뒤 점검을 시작하세요."
        : "현재 조건에 맞는 결과가 없습니다.\n조건 해제 또는 다른 분류를 선택하세요. 결과 0건은 점검 완료나 안전함을 뜻하지 않습니다.");
    reset.setEnabled(!closed && (!accountValue.isEmpty() || !selectedSeverity.isEmpty()));
    selectedChanged();
  }

  private Projection projected(String accountValue) {
    ScopeInspection.Snapshot snapshot = current;
    Projection existing = projection;
    if (existing != null && existing.snapshot() == snapshot && existing.account().equals(accountValue)) return existing;
    if ((long) snapshot.findings().size() + snapshot.pages().size() + snapshot.candidates().size() >= ASYNC_THRESHOLD) {
      requestProjection(snapshot, accountValue);
      existing = projection;
      return existing != null && existing.snapshot().id().equals(snapshot.id()) && existing.account().equals(accountValue)
          ? existing : null;
    }
    long epoch;
    synchronized (updateLock) { invalidateProjectionLocked(); epoch = projectionEpoch; }
    Projection next = calculateProjection(snapshot, accountValue, epoch, () -> true);
    synchronized (updateLock) { if (!closed && epoch == projectionEpoch) projection = next; }
    return next;
  }

  private static Projection calculateProjection(ScopeInspection.Snapshot snapshot, String accountValue,
      long epoch, BooleanSupplier valid) {
    var rows = new EnumMap<ScopeInspectionDashboard.Group, List<ScopeInspectionDashboard.Row>>(ScopeInspectionDashboard.Group.class);
    for (var value : ScopeInspectionDashboard.Group.values()) {
      if (!valid.getAsBoolean()) throw new CancellationException();
      rows.put(value, ScopeInspectionDashboard.rows(snapshot, new ScopeInspectionDashboard.Filter(value, accountValue, "")));
    }
    return new Projection(snapshot, accountValue, Map.copyOf(rows), epoch);
  }

  private void requestProjection(ScopeInspection.Snapshot snapshot, String accountValue) {
    synchronized (updateLock) {
      if (closed) return;
      boolean sameContext = desiredProjection != null
          ? desiredProjection.snapshot().id().equals(snapshot.id()) && desiredProjection.account().equals(accountValue)
          : projection != null && projection.snapshot().id().equals(snapshot.id()) && projection.account().equals(accountValue);
      if (!sameContext) invalidateProjectionLocked();
      if (desiredProjection != null && desiredProjection.snapshot() == snapshot && desiredProjection.account().equals(accountValue)) return;
      desiredProjection = new ProjectionRequest(snapshot, accountValue, projectionEpoch);
      pendingProjection = desiredProjection; // Exactly one pending snapshot; a newer publication replaces it.
      failedProjection = null; projectionError = "";
      if (!workerRunning) {
        workerRunning = true;
        projectionWorker.execute(this::runProjectionWorker);
      }
    }
  }

  private boolean projectionContextValid(ProjectionRequest request) {
    synchronized (updateLock) { return !closed && request.epoch() == projectionEpoch; }
  }

  private void runProjectionWorker() {
    for (;;) {
      ProjectionRequest request;
      synchronized (updateLock) {
        request = pendingProjection; pendingProjection = null;
        if (closed || request == null) { workerRunning = false; return; }
      }
      Projection calculated = null;
      String error = "";
      try {
        projectionHook.before(request.snapshot(), request.account());
        calculated = calculateProjection(request.snapshot(), request.account(), request.epoch(),
            () -> projectionContextValid(request) && !Thread.currentThread().isInterrupted());
      } catch (CancellationException ignored) { continue; }
      catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        if (!closed) Thread.interrupted();
        if (!projectionContextValid(request)) continue;
        error = "결과 집계 작업이 중단되었습니다.";
      } catch (RuntimeException failure) {
        String message = Objects.toString(failure.getMessage(), "").strip();
        error = message.isEmpty() ? "결과를 집계하지 못했습니다." : ScopeInspectionAnalysis.display(message, 400);
      }
      synchronized (updateLock) {
        if (closed || request.epoch() != projectionEpoch) continue;
        readyProjection = new ProjectionCompletion(request, calculated, error);
        if (!completionScheduled) {
          completionScheduled = true;
          SwingUtilities.invokeLater(this::acceptProjection);
        }
      }
    }
  }

  private void acceptProjection() {
    ProjectionCompletion ready;
    synchronized (updateLock) {
      ready = readyProjection; readyProjection = null; completionScheduled = false;
      if (closed || ready == null || ready.request().epoch() != projectionEpoch
          || !ready.request().snapshot().id().equals(current.id()) || !ready.request().account().equals(selectedAccount())) return;
      if (ready.projection() != null) {
        projection = ready.projection(); failedProjection = null; projectionError = "";
      } else {
        failedProjection = ready.request(); projectionError = ready.error();
      }
    }
    boolean select = selectFirstWhenReady; selectFirstWhenReady = false;
    refresh(select);
  }

  private String projectionFailure(ScopeInspection.Snapshot snapshot, String accountValue) {
    synchronized (updateLock) {
      return failedProjection != null && failedProjection.snapshot() == snapshot && failedProjection.account().equals(accountValue)
          ? projectionError : "";
    }
  }

  private void showProjectionUnavailable(String accountValue) {
    String failure = projectionFailure(current, accountValue);
    aggregating = failure.isEmpty();
    rebuilding = true;
    try { visibleRows = List.of(); keyedRows.clear(); model.setRowCount(0); table.clearSelection(); }
    finally { rebuilding = false; }
    for (var entry : cards.entrySet()) {
      var card = entry.getValue(); card.count = -1; card.setSelected(group == entry.getKey());
      card.setText(cardLabel(entry.getKey()) + (failure.isEmpty() ? " — · 집계 중" : " — · 집계 실패")); card.repaint();
    }
    for (var entry : categoryButtons.entrySet()) entry.getValue().setValue(-1, 1, group == entry.getKey());
    for (var entry : severityButtons.entrySet()) entry.getValue().setValue(-1, 1,
        group == ScopeInspectionDashboard.Group.SECRETS && entry.getKey().equals(selectedSeverity));
    String message = failure.isEmpty() ? "선택한 실행·계정의 결과를 집계하고 있습니다."
        : "선택한 실행·계정의 결과 집계에 실패했습니다.\n" + failure;
    updateRunStatus(failure.isEmpty() ? "결과 집계 중 | " : "결과 집계 실패 | ");
    filterSummary.setText(message.replace('\n', ' ')); filterSummary.setToolTipText(filterSummary.getText());
    replaceText(emptyMessage, message); replaceText(detail, message);
    ((CardLayout) resultViews.getLayout()).show(resultViews, "empty");
    for (var button : List.of(original, repeater, evidence)) {
      button.setEnabled(false); button.setToolTipText("결과 집계가 끝난 뒤 현재 실행 원본이 연결된 항목을 선택하세요.");
    }
    reset.setEnabled(!closed && (!accountValue.isEmpty() || !selectedSeverity.isEmpty()));
  }

  private void invalidateProjectionLocked() {
    projectionEpoch++; projection = null; desiredProjection = null; pendingProjection = null;
    readyProjection = null; failedProjection = null; projectionError = ""; selectFirstWhenReady = false;
  }

  private void updateRunStatus(String aggregationStatus) {
    run.setText(current.id().isEmpty() ? "현재 실행: 없음"
        : aggregationStatus + "최신 실행 상태: " + current.id() + " · " + state(current.state()));
    run.setToolTipText(run.getText());
  }

  ScopeInspectionDashboard.Row selectedRow() {
    int selected = table.getSelectedRow();
    if (selected < 0) return null;
    int index = table.convertRowIndexToModel(selected);
    return index < visibleRows.size() ? visibleRows.get(index) : null;
  }

  private void selectedChanged() {
    var row = selectedRow();
    if (row == null) replaceText(detail, current.id().isEmpty() ? "통합 점검 결과의 숫자를 누르면 해당 목록을 확인할 수 있습니다." : "목록에서 항목을 선택하면 근거와 원본 위치를 확인할 수 있습니다.");
    else replaceText(detail, detailText(row));
    boolean available = originalAvailable(row);
    for (var button : List.of(original, repeater, evidence)) {
      button.setEnabled(available);
      button.setToolTipText(available ? "선택 결과의 원본을 연결합니다. 준비·화면 이동은 요청을 전송하지 않습니다." : "현재 실행 원본이 연결된 결과를 선택하세요.");
    }
  }

  private static String detailText(ScopeInspectionDashboard.Row row) {
    return row.title() + "\n\n계정: " + row.account()
        + "\n상태: " + row.state() + "\n중요도: " + ScopeInspectionDashboard.severityLabel(row.severity())
        + "\n확실성: " + row.confidence() + "\n요청: " + row.method() + " " + row.url()
        + "\n\n" + row.detail() + "\n\n원본: " + (row.originalId().isEmpty() ? "현재 실행 원본 연결 없음" : row.originalId())
        + "\n본문 줄: " + row.line() + " · 문자 위치: " + row.offset();
  }

  private void handoff(Consumer<String> action) {
    var row = selectedRow();
    if (!originalAvailable(row)) return;
    try { action.accept(row.originalId()); }
    catch (RuntimeException error) {
      table.clearSelection(); selectedChanged();
      replaceText(detail, "원본 연결을 진행하지 못했습니다.\n"
          + ScopeInspectionAnalysis.display(Objects.toString(error.getMessage(), "현재 실행의 원본을 확인하세요."), 400)
          + "\n\n현재 프로젝트와 실행을 확인한 뒤 결과를 다시 선택하세요.");
    }
  }

  private static void replaceText(JTextArea area, String value) {
    if (!value.equals(area.getText())) {
      area.setText(value);
      area.setCaretPosition(0);
    }
  }

  private void presentDetail() {
    String text = detail.getText();
    var row = selectedRow();
    boolean selected = row != null && text.equals(detailText(row));
    detailTitle.setText(selected ? row.title() : "선택 결과");
    detailTitle.setToolTipText(detailTitle.getText());
    detailFacts.setVisible(selected);
    detailFacts.setRows(selected ? 4 : 0);
    replaceText(detailFacts, selected ? "요청: " + row.method() + " " + row.url()
        + "\n관측 계정: " + row.account() + "\n상태: " + row.state()
        + " · 중요도: " + ScopeInspectionDashboard.severityLabel(row.severity()) + "\n확실성: " + row.confidence() : "");
    replaceText(detailSummary, selected ? row.detail() : text);
    replaceText(detailOrigin, selected ? "원본: " + (row.originalId().isEmpty() ? "현재 실행 원본 연결 없음" : row.originalId())
        + "\n본문 줄: " + row.line() + " · 문자 위치: " + row.offset() : "현재 실행 원본이 연결된 결과를 선택하세요.");
  }

  private boolean originalAvailable(ScopeInspectionDashboard.Row row) {
    return !closed && row != null && !row.originalId().isEmpty() && current.pages().stream()
        .anyMatch(page -> page.original() != null && row.originalId().equals(page.original().id()));
  }

  void clear() { revealVersion.incrementAndGet(); synchronized (updateLock) { invalidateProjectionLocked(); } update(EMPTY, 0, ""); }

  @Override public void close() {
    synchronized (updateLock) { if (closed) return; closed = true; pending = null; current = EMPTY; invalidateProjectionLocked(); }
    revealVersion.incrementAndGet();
    projectionWorker.shutdownNow();
    Runnable clear = () -> {
      visibleRows = List.of(); keyedRows.clear(); model.setRowCount(0); table.clearSelection();
      selectedSeverity = ""; knownAccounts = List.of();
      rebuilding = true;
      try { account.removeAllItems(); account.addItem(new AccountChoice("")); }
      finally { rebuilding = false; }
      inspect.setEnabled(false); account.setEnabled(false); importance.setEnabled(false); reset.setEnabled(false);
      target.setText("SCOPE: 대시보드 종료"); run.setText("현재 실행: 없음"); traffic.setText("요청 기록 없음");
      refresh(false);
      for (var card : cards.values()) card.setEnabled(false);
      for (var bar : categoryButtons.values()) bar.setEnabled(false);
      for (var bar : severityButtons.values()) bar.setEnabled(false);
    };
    if (SwingUtilities.isEventDispatchThread()) clear.run(); else SwingUtilities.invokeLater(clear);
  }

  private static String state(ScopeInspection.State value) {
    return switch (value) {
      case READY -> "준비"; case RUNNING -> "점검 중"; case PAUSED -> "일시정지";
      case STOPPED -> "중지 · 미확인 포함"; case COMPLETED -> "대기열 처리 완료";
      case FAILED -> "실패 · 미확인 포함"; case CLOSED -> "종료";
    };
  }

  static final class MetricButton extends JButton {
    final String caption;
    int count;
    MetricButton(String caption, String hint) {
      this.caption = caption; setToolTipText(hint); setOpaque(false);
      putClientProperty("html.disable", true);
      setPreferredSize(new Dimension(165, 76)); setMinimumSize(new Dimension(110, 76));
      getAccessibleContext().setAccessibleDescription(hint);
    }
    @Override protected void paintComponent(Graphics graphics) {
      Graphics2D g = (Graphics2D) graphics.create(); WorkbenchSkin.antialias(g);
      g.setColor(isSelected() ? WorkbenchUi.selected() : WorkbenchUi.surface());
      g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
      g.setColor(isSelected() || hasFocus() ? WorkbenchUi.accent() : WorkbenchUi.line());
      g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
      g.setFont(WorkbenchUi.font().deriveFont(Font.PLAIN, WorkbenchUi.displaySize(12)));
      g.setColor(WorkbenchUi.ink()); g.drawString(caption, WorkbenchUi.displayPixels(12), WorkbenchUi.displayPixels(22));
      g.setFont(WorkbenchUi.font().deriveFont(Font.BOLD, WorkbenchUi.displaySize(28)));
      g.setColor(isSelected() ? WorkbenchUi.accent() : WorkbenchUi.ink());
      g.drawString(count < 0 ? "—" : Integer.toString(count), WorkbenchUi.displayPixels(12), WorkbenchUi.displayPixels(58));
      g.dispose();
    }
    @Override public Dimension getPreferredSize() { return new Dimension(WorkbenchUi.displayPixels(165), WorkbenchUi.displayPixels(76)); }
    @Override public Dimension getMinimumSize() { return new Dimension(WorkbenchUi.displayPixels(110), WorkbenchUi.displayPixels(76)); }
    @Override public JToolTip createToolTip() { var tip = super.createToolTip(); tip.putClientProperty("html.disable", true); return tip; }
  }

  static final class BarButton extends JButton {
    final String caption;
    int count, maximum = 1;
    BarButton(String caption) {
      this.caption = caption; setPreferredSize(new Dimension(280, 22));
      putClientProperty("html.disable", true); setOpaque(false);
    }
    void setValue(int value, int max, boolean selected) {
      count = value; maximum = Math.max(1, max); setSelected(selected);
      setText(caption + (count < 0 ? " —" : " " + count + "건")); repaint();
    }
    @Override protected void paintComponent(Graphics graphics) {
      Graphics2D g = (Graphics2D) graphics.create(); WorkbenchSkin.antialias(g);
      g.setColor(isSelected() ? WorkbenchUi.selected() : WorkbenchUi.surface());
      g.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
      g.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(11)));
      g.setColor(WorkbenchUi.ink());
      int labelWidth = Math.min(getWidth() / 2, Math.max(WorkbenchUi.displayPixels(108), g.getFontMetrics().stringWidth(caption) + WorkbenchUi.displayPixels(10)));
      int barWidth = Math.max(0, getWidth() - labelWidth - WorkbenchUi.displayPixels(48));
      int middle = getHeight() / 2;
      int halfBar = WorkbenchUi.displayPixels(4), barHeight = WorkbenchUi.displayPixels(8);
      g.drawString(caption, WorkbenchUi.displayPixels(7), middle + WorkbenchUi.displayPixels(4));
      g.setColor(WorkbenchUi.raised()); g.fillRoundRect(labelWidth, middle - halfBar, barWidth, barHeight, barHeight, barHeight);
      g.setColor(isSelected() ? WorkbenchUi.accent() : WorkbenchUi.toneInk(WorkbenchUi.Tone.ACTIVE));
      if (count > 0) g.fillRoundRect(labelWidth, middle - halfBar, Math.max(2, barWidth * count / maximum), barHeight, barHeight, barHeight);
      g.setColor(WorkbenchUi.ink()); g.drawString(count < 0 ? "—" : Integer.toString(count), getWidth() - WorkbenchUi.displayPixels(34), middle + WorkbenchUi.displayPixels(4));
      if (hasFocus()) { g.setColor(WorkbenchUi.accent()); g.drawRect(1, 1, getWidth() - 3, getHeight() - 3); }
      g.dispose();
    }
    @Override public Dimension getPreferredSize() { return new Dimension(WorkbenchUi.displayPixels(280), WorkbenchUi.displayPixels(22)); }
    @Override public Dimension getMinimumSize() { return new Dimension(WorkbenchUi.displayPixels(160), WorkbenchUi.displayPixels(22)); }
  }

  private static final class LiteralLabel extends JLabel {
    LiteralLabel(String text) { super(text); putClientProperty("html.disable", true); }
    @Override public JToolTip createToolTip() { var tip = super.createToolTip(); tip.putClientProperty("html.disable", true); return tip; }
  }

  private static final class LiteralTable extends JTable {
    LiteralTable(DefaultTableModel model) { super(model); }
    @Override public void setDefaultRenderer(Class<?> type, TableCellRenderer renderer) {
      if (renderer == null) { super.setDefaultRenderer(type, null); return; }
      super.setDefaultRenderer(type, (table, value, selected, focus, row, column) -> {
        if (renderer instanceof JComponent component) component.putClientProperty("html.disable", true);
        Component rendered = renderer.getTableCellRendererComponent(table, value, selected, focus, row, column);
        if (rendered instanceof JComponent component) component.putClientProperty("html.disable", true);
        return rendered;
      });
    }
    @Override public JToolTip createToolTip() { var tip = super.createToolTip(); tip.putClientProperty("html.disable", true); return tip; }
  }

  private static final class ScrollContent extends JPanel implements Scrollable {
    @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(1000, 700); }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(24, visible.height - 40); }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() {
      return getParent() instanceof JViewport viewport && viewport.getHeight() >= getPreferredSize().height;
    }
  }
}
