package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import java.awt.*;
import java.util.List;
import java.util.Objects;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;
import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.*;

final class StructuredComparePanel extends JPanel implements AutoCloseable {
  final JTextArea leftInput = new JTextArea(), rightInput = new JTextArea(),
      leftView = new JTextArea(), rightView = new JTextArea(),
      leftRaw = new JTextArea(), rightRaw = new JTextArea();
  final JCheckBox formatted = new JCheckBox("정렬", true);
  final JLabel status = new JLabel("양쪽에 데이터를 붙여넣고 비교하세요.");
  final JButton previous = new JButton("이전"), next = new JButton("다음"), edit = new JButton("입력 편집"),
      cancel = new JButton("중지");
  final CardLayout cards = new CardLayout();
  final JPanel content = new JPanel(cards);
  final JTabbedPane resultTabs = new JTabbedPane();
  final DefaultTableModel summaryModel = new DefaultTableModel(
      new String[] {"분류", "항목", "변경", "왼쪽", "오른쪽"}, 0) {
    @Override public boolean isCellEditable(int row, int column) { return false; }
  };
  final JTable summaryTable = new JTable(summaryModel) {
    private final WorkbenchUi.Cells plain = new WorkbenchUi.Cells() {
      { putClientProperty("html.disable", true); }
      @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
          boolean focus, int row, int column) {
        super.getTableCellRendererComponent(table, value, selected, focus, row, column);
        setToolTipText(null); return this;
      }
    };
    @Override public TableCellRenderer getCellRenderer(int row, int column) { return plain; }
  };
  final JTextArea summaryNote = new JTextArea();
  StructuredCompare.Result result;
  ResponseDifference.Summary summary;
  int current = -1;
  boolean showing;
  final MontoyaApi api;
  final JLabel sourceStatus = new JLabel("원본 연결 없음 · 붙여넣은 데이터만으로는 원본 증거를 만들지 않습니다.");
  final JButton captureEvidence = new JButton("증거"),
      loadLeft = new JButton("트래픽 가져오기"), loadRight = new JButton("트래픽 가져오기");
  final JLabel[] leftSourceLabels = {new JLabel(), new JLabel()}, rightSourceLabels = {new JLabel(), new JLabel()};
  java.util.function.Supplier<List<HttpRequestResponse>> selectedTraffic = List::of;
  java.util.function.BiConsumer<List<HttpRequestResponse>, String> evidenceSink = (packets, note) -> {
    throw new IllegalArgumentException("증거 기능 준비 중");
  };
  HttpRequestResponse leftSource, rightSource;
  boolean loadingSource;
  private JDialog criteriaWindow;
  BackgroundJobs backgroundJobs;
  private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(1), runnable -> { Thread t = new Thread(runnable, "Workbench-data-compare"); t.setDaemon(true); return t; });
  private long revision, publishedRevision = -1;
  private boolean publishedFormatted, closed;
  private ComparisonTask currentTask, evidenceTask;
  java.util.function.Supplier<String> projectIdentity;
  record Compared(StructuredCompare.Result lines, ResponseDifference.Summary fields) {}
  interface Computation { Compared compute(String left, String right, boolean formatted); }
  Computation computation = (left, right, format) -> {
    var lines = StructuredCompare.compare(left, right, format);
    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    return new Compared(lines, ResponseDifference.compare(left, right));
  };

  private final class ComparisonTask implements Runnable {
    final long revision;
    final String project, left, right;
    final boolean formatted;
    final Computation compute;
    final BackgroundJobs.Handle job;
    private boolean cancelled;
    private Thread runner;
    ComparisonTask(long revision, String project, String left, String right, boolean formatted) {
      this.revision = revision; this.project = project; this.left = left; this.right = right;
      this.formatted = formatted; this.compute = computation;
      // Finished metadata must not retain the request/response snapshot through its cancellation callback.
      WeakReference<ComparisonTask> reference = new WeakReference<>(this);
      job = BackgroundJobs.begin(backgroundJobs, "응답·데이터 비교", "data", () -> {
        ComparisonTask task = reference.get(); if (task != null) task.requestCancel();
      });
    }
    synchronized void requestCancel() {
      cancelled = true;
      if (runner != null) runner.interrupt();
      job.stopping("비교 중지 요청 · 계산 종료 대기");
    }
    synchronized boolean cancelled() { return cancelled; }
    @Override public void run() {
      synchronized (this) { Thread.interrupted(); runner = Thread.currentThread(); }
      Compared value = null; Throwable failure = null;
      try {
        if (cancelled()) throw new CancellationException();
        job.progress("원문·항목 차이 계산", 0, -1);
        value = compute.compute(left, right, formatted);
      } catch (Throwable error) { failure = error; }
      finally {
        synchronized (this) { runner = null; }
        Compared completed = value; Throwable error = failure;
        SwingUtilities.invokeLater(() -> finishComparison(this, completed, error));
      }
    }
  }


  StructuredComparePanel() { this(null); }

  StructuredComparePanel(MontoyaApi api) {
    super(new BorderLayout(8, 8)); this.api = api;
    projectIdentity = () -> WorkspaceResume.projectIdentity(api);
    setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    JButton compare = new JButton("비교"), more = new JButton("더보기");
    JMenuItem clear = new JMenuItem("초기화");
    compare.setName("wb.compare.run"); edit.setName("wb.compare.edit"); clear.setName("wb.compare.clear");
    more.setName("wb.compare.more");
    JPopupMenu options = new JPopupMenu(); options.setName("wb.actions.compare.more"); options.add(clear);
    more.setComponentPopupMenu(options); more.addActionListener(e -> { if (more.isShowing()) options.show(more, 0, more.getHeight()); });
    cancel.setName("wb.compare.cancel"); cancel.setEnabled(false);
    cancel.setToolTipText("비교만 중지하고 이전 성공 결과를 유지합니다."); cancel.addActionListener(e -> cancelComparison());
    captureEvidence.setName("wb.compare.evidence"); loadLeft.setName("wb.compare.load.left"); loadRight.setName("wb.compare.load.right");
    compare.getAccessibleContext().setAccessibleName("비교 실행"); compare.setToolTipText("응답·데이터 비교 실행");
    cancel.getAccessibleContext().setAccessibleName("비교 중지");
    previous.getAccessibleContext().setAccessibleName("이전 원문·줄 차이"); next.getAccessibleContext().setAccessibleName("다음 원문·줄 차이");
    formatted.getAccessibleContext().setAccessibleName("데이터 정렬"); formatted.setToolTipText("JSON·XML 데이터를 정렬한 뒤 원문 줄 차이 비교");
    captureEvidence.getAccessibleContext().setAccessibleName("원본 → 증거"); captureEvidence.setToolTipText("연결한 왼쪽·오른쪽 원본을 증거로 전달");
    edit.setEnabled(false); WorkbenchUi.primary(compare);
    previous.setToolTipText("이전 원문·줄 차이"); next.setToolTipText("다음 원문·줄 차이");
    previous.setEnabled(false); next.setEnabled(false);
    for (JTextArea area : new JTextArea[] {leftInput, rightInput, leftView, rightView, leftRaw, rightRaw}) {
      area.putClientProperty("wb.packet.syntax", true); area.setLineWrap(false);
      area.setFont(WorkbenchUi.textFont("가나다"));
    }
    leftView.setEditable(false); rightView.setEditable(false); leftRaw.setEditable(false); rightRaw.setEditable(false);
    leftRaw.setName("wb.compare.raw.left"); rightRaw.setName("wb.compare.raw.right");
    content.add(pair(leftInput, rightInput), "input");
    resultTabs.setName("wb.compare.results");
    resultTabs.addTab("항목별 요약", summaryContent());
    resultTabs.addTab("원문·줄 차이", pair(leftView, rightView));
    JPanel resultActions = WorkbenchUi.row(edit, previous, next, captureEvidence);
    resultActions.setName("wb.actions.compare.results");
    JPanel resultWorkspace = new JPanel(new BorderLayout(0, 4));
    resultWorkspace.setName("wb.compare.result.workspace");
    resultWorkspace.add(CompactWorkbenchUi.toolbar(new JLabel("비교 결과"), resultActions), BorderLayout.NORTH);
    resultWorkspace.add(resultTabs);
    content.add(resultWorkspace, "result");
    add(content);
    JButton criteria = new JButton("비교 설정"); criteria.setName("wb.compare.criteria");
    criteria.getAccessibleContext().setAccessibleName("비교 범위와 기준 열기");
    criteria.setToolTipText("전체 비교 범위·제한·제외 항목과 파싱 안내 확인");
    criteria.addActionListener(e -> {
      if (criteriaWindow != null) { criteriaWindow.toFront(); return; }
      if (summary == null) summaryNote.setText("아직 비교한 결과가 없습니다. 비교는 입력별 앞65,536자·최대1,500줄, JSON4,096노드·깊이32와 항목256개를 표시합니다. 제한된 결과는 전체 응답의 모든 차이를 뜻하지 않으며 차이 자체로 취약점이 확정되지는 않습니다.");
      JPanel settings = new JPanel(new BorderLayout(0, 6)); settings.setName("wb.compare.settings");
      settings.add(WorkbenchUi.row(formatted), BorderLayout.NORTH);
      settings.add(new JScrollPane(summaryNote));
      criteriaWindow = WorkbenchUi.settingsWindow(this, "비교 설정·범위·기준", settings, false, () -> criteriaWindow = null);
    });
    JPanel settingsActions = WorkbenchUi.row(criteria, more); settingsActions.setName("wb.actions.compare.settings");
    JPanel executionActions = WorkbenchUi.row(compare, cancel); executionActions.setName("wb.actions.compare.execution");
    add(WorkbenchUi.header("응답·데이터 비교", "상태·헤더·JSON 항목 요약과 원문 차이 · 차이는 취약점 확정 아님",
        CompactWorkbenchUi.toolbar(settingsActions, executionActions)), BorderLayout.NORTH);
    JPanel footer = new JPanel(new BorderLayout(6, 0)); footer.add(status);
    add(footer, BorderLayout.SOUTH); status.addPropertyChangeListener("text", e -> status.setToolTipText(status.getText()));
    loadLeft.addActionListener(e -> sourceAction(() -> loadSelected(true)));
    loadRight.addActionListener(e -> sourceAction(() -> loadSelected(false)));
    captureEvidence.addActionListener(e -> sourceAction(this::captureEvidence)); captureEvidence.setEnabled(false);
    watchInput(leftInput, true); watchInput(rightInput, false);
    compare.addActionListener(e -> compare());
    edit.addActionListener(e -> { cancelComparison(false); showing = false; edit.setEnabled(false); previous.setEnabled(false); next.setEnabled(false);
      cards.show(content, "input"); status.setText("입력 편집 중 · 비교 실행으로 결과를 갱신하세요."); });
    clear.addActionListener(e -> { clearSources(); status.setText("양쪽에 데이터를 붙여넣고 비교하세요."); });
    formatted.addActionListener(e -> { if (showing) compare(); });
    previous.addActionListener(e -> move(-1)); next.addActionListener(e -> move(1));
    sourceSummary(); WorkbenchUi.style(this);

  }

  private JPanel summaryContent() {
    summaryTable.setName("wb.compare.summary");
    summaryTable.getAccessibleContext().setAccessibleName("상태·헤더·JSON 항목별 차이");
    summaryTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    summaryTable.setAutoCreateRowSorter(true); summaryTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    int[] widths = {70, 240, 65, 190, 190};
    for (int i = 0; i < widths.length; i++) summaryTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    summaryTable.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) selectSummary(); });
    summaryNote.setName("wb.compare.summary.note"); summaryNote.setEditable(false);
    summaryNote.setLineWrap(true); summaryNote.setWrapStyleWord(true); summaryNote.setRows(3);
    summaryNote.putClientProperty("wb.prose", true);
    JScrollPane tableScroll = new JScrollPane(summaryTable);
    tableScroll.setMinimumSize(new Dimension(100, 75));
    JSplitPane originals = WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT,
        WorkbenchUi.card("왼쪽 원문 · 선택 항목", new JScrollPane(leftRaw)),
        WorkbenchUi.card("오른쪽 원문 · 선택 항목", new JScrollPane(rightRaw)), .5);
    originals.setName("wb.compare.originals");
    // Fit actual table rows and raw text after host navigation, tabs and scaled headers take their space.
    // A fixed percentage alone can leave only the table header at 125/150% in a 1024px workspace.
    JSplitPane body = WorkbenchUi.split(JSplitPane.VERTICAL_SPLIT, tableScroll, originals, .4);
    body.setName("wb.compare.summary.body");
    JPanel panel = new JPanel(new BorderLayout()) {
      @Override public void doLayout() {
        super.doLayout();
        int usable = body.getHeight() - body.getDividerSize();
        int tableMinimum = summaryTable.getTableHeader().getPreferredSize().height + 60
            + tableScroll.getHorizontalScrollBar().getPreferredSize().height + 2;
        Component rawCard = originals.getLeftComponent();
        int titleHeight = rawCard instanceof JPanel card ? card.getComponent(0).getPreferredSize().height + 8 : 50;
        int rawMinimum = titleHeight + 70 + tableScroll.getHorizontalScrollBar().getPreferredSize().height + 2;
        if (usable >= tableMinimum + rawMinimum) {
          int desired = Math.max(tableMinimum, Math.min(usable - rawMinimum, body.getDividerLocation()));
          if (body.getDividerLocation() != desired) body.setDividerLocation(desired);
        }
      }
    };
    panel.add(body);
    return panel;
  }

  void sourceAction(Runnable action) {
    try { action.run(); } catch (Exception ex) {
      Diagnostics.failure(this, "데이터 변환·비교", ex); status.setText(ex.getMessage());
    }
  }
  void watchInput(JTextArea input, boolean left) {
    input.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
      public void insertUpdate(javax.swing.event.DocumentEvent e) { changed(); }
      public void removeUpdate(javax.swing.event.DocumentEvent e) { changed(); }
      public void changedUpdate(javax.swing.event.DocumentEvent e) { changed(); }
      void changed() {
        if (loadingSource) return;
        if (left) leftSource = null; else rightSource = null;
        invalidateResult(); sourceSummary();
      }
    });
  }
  private void invalidateResult() {
    revision++; cancelComparison(false); evidenceTask = null;
    showing = false; edit.setEnabled(false); previous.setEnabled(false); next.setEnabled(false);
    result = null; summary = null; current = -1;
    summaryModel.setRowCount(0); summaryNote.setText(""); leftRaw.setText(""); rightRaw.setText("");
    cards.show(content, "input");
  }
  void sourceSummary() {
    sourceStatus.setText("왼쪽: " + sourceName(leftSource) + " · 오른쪽: " + sourceName(rightSource) + " · 입력 편집 시 해당 원본 연결 해제");
    captureEvidence.setEnabled(!closed && leftSource != null && rightSource != null);
    sourceLabels(leftSourceLabels, leftSource); sourceLabels(rightSourceLabels, rightSource);
  }
  void sourceLabels(JLabel[] labels, HttpRequestResponse rr) {
    for (JLabel label : labels) {
      label.setText(rr == null ? "원본 없음" : "원본 HTTP " + rr.response().statusCode());
      label.setToolTipText(sourceName(rr) + " · 입력 편집 시 원본 연결 해제");
    }
  }
  static String sourceName(HttpRequestResponse rr) {
    return rr == null ? "원본 연결 없음" : rr.request().method() + " " + Core.safeUrl(rr.request().url()) + " [HTTP " + rr.response().statusCode() + "]";
  }
  void loadSelected(boolean left) {
    List<HttpRequestResponse> packets = selectedTraffic.get();
    if (packets.size() != 1) throw new IllegalArgumentException("트래픽에서 원본 요청 1개를 선택하세요.");
    loadSource(left, packets.get(0));
  }
  void loadSource(boolean left, HttpRequestResponse rr) {
    EvidenceLinks.checked(api, List.of(rr)); loadingSource = true;
    try { if (left) { leftInput.setText(rr.response().toString()); leftSource = rr; }
      else { rightInput.setText(rr.response().toString()); rightSource = rr; }
    } finally { loadingSource = false; }
    invalidateResult(); sourceSummary();
    status.setText("원본 응답 연결 · 전송 없이 가져옴 · 데이터 비교는 취약점 확정이 아닙니다.");
  }
  void captureEvidence() {
    if (leftSource == null || rightSource == null) throw new IllegalArgumentException("양쪽에 원본 트래픽을 연결하세요. 편집한 입력은 다시 가져와야 합니다.");
    var packets = EvidenceLinks.checked(api, List.of(leftSource, rightSource));
    if (closed) throw new IllegalArgumentException("비교 화면이 종료되었습니다.");
    if (result == null || publishedRevision != revision || publishedFormatted != formatted.isSelected()) {
      if (currentTask == null || currentTask.revision != revision || currentTask.formatted != formatted.isSelected()) compare();
      evidenceTask = currentTask;
      status.setText("원본 비교 후 증거 전달 준비 중 · 비교 중지 시 전달하지 않습니다.");
      return;
    }
    var compared = result;
    int changes = compared.changes().size();
    evidenceSink.accept(packets, "데이터 비교 · 왼쪽/오른쪽 원본 순서 · 표시 줄 차이 " + changes + "곳"
        + (compared.limited() ? " · 비교 표시 제한(65,536자/1,500줄) · 전체 응답 차이 수가 아님" : "") + " · 차이는 취약점 확정 아님");
    status.setText("좌/우 원본 패킷 2개 증거 전달 · 저장은 점검 지원에서 실행하세요.");
  }
  void clearSources() {
    if (criteriaWindow != null) { criteriaWindow.dispose(); criteriaWindow = null; }
    leftSource = null; rightSource = null; leftInput.setText(""); rightInput.setText("");
    leftView.setText(""); rightView.setText(""); invalidateResult(); sourceSummary();
  }
  JPanel pair(JTextArea a, JTextArea b) {
    boolean input = a == leftInput;
    loadLeft.setToolTipText("선택한 트래픽 1개의 응답을 왼쪽으로 가져옵니다. 서버 전송은 없습니다.");
    loadRight.setToolTipText("선택한 트래픽 1개의 응답을 오른쪽으로 가져옵니다. 서버 전송은 없습니다.");
    JPanel left = dataSide("왼쪽 데이터", a, leftSourceLabels[input ? 0 : 1], input ? loadLeft : null, "left");
    JPanel right = dataSide("오른쪽 데이터", b, rightSourceLabels[input ? 0 : 1], input ? loadRight : null, "right");
    JSplitPane split = WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT, left, right, .5);
    split.setName("wb.compare.sides"); JPanel panel = new JPanel(new BorderLayout()); panel.add(split); return panel;
  }

  private JPanel dataSide(String title, JTextArea area, JLabel source, JButton importer, String side) {
    JPanel card = WorkbenchUi.card(title, new JScrollPane(area));
    Component heading = ((BorderLayout) card.getLayout()).getLayoutComponent(BorderLayout.NORTH);
    card.remove(heading);
    JPanel context = WorkbenchUi.row(heading, source);
    JPanel actions = importer == null ? new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0)) : WorkbenchUi.row(importer);
    actions.setName("wb.actions.compare." + (importer == null ? "original." : "input.") + side);
    card.add(CompactWorkbenchUi.toolbar(context, actions), BorderLayout.NORTH);
    return card;
  }

  /** Snapshot only the bounded inspected prefix; all computation happens off the EDT. */
  void compare() {
    if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::compare); return; }
    if (closed) return;
    cancelComparison(false); evidenceTask = null;
    String project = Objects.toString(projectIdentity.get(), "");
    ComparisonTask task = new ComparisonTask(revision, project, input(leftInput), input(rightInput), formatted.isSelected());
    currentTask = task; cancel.setEnabled(true);
    status.setText("비교 계산 중 · 이전 성공 결과 유지 · 화면 이동과 중지 가능");
    try { worker.execute(task); }
    catch (RejectedExecutionException error) { finishComparison(task, null, error); }
  }
  private static String input(JTextArea area) {
    try { return area.getText(0, Math.min(area.getDocument().getLength(), ResponseDifference.INPUT_LIMIT + 1)); }
    catch (BadLocationException error) { throw new IllegalStateException(error); }
  }
  boolean isComparing() { return currentTask != null; }
  void cancelComparison() { cancelComparison(true); }
  private void cancelComparison(boolean explain) {
    ComparisonTask task = currentTask;
    if (task == null) return;
    task.requestCancel(); evidenceTask = null; cancel.setEnabled(false);
    if (explain) status.setText("비교 중지 요청 · 계산 종료 대기 · 이전 성공 결과 유지");
    // Queued work has never begun, so removing it acknowledges actual termination.
    if (worker.remove(task)) finishComparison(task, null, new CancellationException());
  }
  private void finishComparison(ComparisonTask task, Compared value, Throwable failure) {
    boolean active = currentTask == task;
    boolean projectCurrent = !closed && Objects.equals(task.project, Objects.toString(projectIdentity.get(), ""));
    boolean valid = active && projectCurrent && task.revision == revision && task.formatted == formatted.isSelected();
    if (task.cancelled() || failure instanceof CancellationException || !valid) {
      task.job.cancelled("비교 중지 완료 · 결과 미반영");
      if (active && !closed) status.setText("비교 중지 완료 · 이전 성공 결과 유지");
    } else if (failure != null) {
      task.job.fail("비교 실패 · 이전 결과 유지");
      status.setText("비교 실패 · 이전 성공 결과 유지 · 입력을 확인한 뒤 다시 실행하세요.");
    } else {
      result = value.lines(); summary = value.fields();
      publishedRevision = revision; publishedFormatted = task.formatted;
      publishComparison(); task.job.complete("비교 결과 반영 완료");
    }
    if (active) { currentTask = null; cancel.setEnabled(false); }
    if (active && !projectCurrent && !closed) {
      clearSources(); status.setText("프로젝트 변경 · 이전 비교 결과와 원본 연결을 해제했습니다.");
    }
    boolean capture = evidenceTask == task && valid && !task.cancelled() && failure == null;
    if (evidenceTask == task) evidenceTask = null;
    if (capture) sourceAction(this::captureEvidence);
  }
  private void publishComparison() {
    leftView.setText(result.left()); rightView.setText(result.right());
    leftView.getHighlighter().removeAllHighlights(); rightView.getHighlighter().removeAllHighlights();
    for (var change : result.changes()) {
      highlight(leftView, change.leftStart(), change.leftEnd(), leftColor());
      highlight(rightView, change.rightStart(), change.rightEnd(), rightColor());
    }
    summaryModel.setRowCount(0);
    for (var item : summary.items()) summaryModel.addRow(new Object[] {item.category(), item.path(), item.change(), item.left(), item.right()});
    summaryNote.setText(summary.note()); summaryNote.setCaretPosition(0);
    leftRaw.setText(summary.left()); rightRaw.setText(summary.right());
    leftRaw.getHighlighter().removeAllHighlights(); rightRaw.getHighlighter().removeAllHighlights();
    leftRaw.setCaretPosition(0); rightRaw.setCaretPosition(0);
    current = -1; showing = true; edit.setEnabled(true); cards.show(content, "result"); resultTabs.setSelectedIndex(0);
    previous.setEnabled(!result.changes().isEmpty()); next.setEnabled(!result.changes().isEmpty());
    status.setText("항목 요약 " + summary.items().size() + "개 · 줄 차이 " + result.changes().size() + "곳"
        + (summary.limited() || result.limited() ? " · 표시 제한 적용, 전체 차이 수가 아님" : " · 항목을 선택하면 원문 위치 표시"));
    leftView.setCaretPosition(0); rightView.setCaretPosition(0);
  }
  @Override public void close() {
    if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::close); return; }
    if (closed) return;
    closed = true; revision++; cancelComparison(false); evidenceTask = null;
    for (Runnable queued : worker.shutdownNow())
      if (queued instanceof StructuredComparePanel.ComparisonTask task) finishComparison(task, null, new CancellationException());
    clearSources(); cancel.setEnabled(false); captureEvidence.setEnabled(false);
  }
  void selectSummary() {
    int row = summaryTable.getSelectedRow();
    if (summary == null || row < 0) return;
    int model = summaryTable.convertRowIndexToModel(row);
    if (model >= summary.items().size()) return;
    var item = summary.items().get(model);
    markRaw(leftRaw, item.leftSpan(), leftColor()); markRaw(rightRaw, item.rightSpan(), rightColor());
    resultTabs.setSelectedIndex(0);
    status.setText(item.category() + " · " + item.path() + " · " + item.change()
        + " · 왼쪽 " + location(item.leftSpan()) + " / 오른쪽 " + location(item.rightSpan()));
  }
  private static String location(ResponseDifference.Span span) { return span.present() ? (span.start() + 1) + "자 위치" : "해당 항목 없음"; }
  private static void markRaw(JTextArea area, ResponseDifference.Span span, Color color) {
    area.getHighlighter().removeAllHighlights();
    if (span.present() && span.end() <= area.getDocument().getLength()) {
      highlight(area, span.start(), span.end(), color);
      area.setCaretPosition(span.start()); area.moveCaretPosition(span.end());
    } else area.setCaretPosition(0);
  }
  void move(int step) {
    if (result == null || result.changes().isEmpty()) return;
    current = current < 0 ? (step > 0 ? 0 : result.changes().size() - 1) : Math.floorMod(current + step, result.changes().size());
    var change = result.changes().get(current);
    resultTabs.setSelectedIndex(1); leftView.setCaretPosition(change.leftStart()); rightView.setCaretPosition(change.rightStart());
    status.setText("차이 " + (current + 1) + " / " + result.changes().size() + (result.limited() ? " · 표시 제한 적용" : ""));
  }
  private static Color leftColor() { return WorkbenchUi.dark() ? new Color(100, 43, 50) : new Color(255, 190, 190); }
  private static Color rightColor() { return WorkbenchUi.dark() ? new Color(31, 82, 53) : new Color(175, 230, 190); }
  static void highlight(JTextArea area, int start, int end, Color color) {
    try { if (end > start) area.getHighlighter().addHighlight(start, end, new DefaultHighlighter.DefaultHighlightPainter(color)); }
    catch (BadLocationException ignored) {}
  }
}
