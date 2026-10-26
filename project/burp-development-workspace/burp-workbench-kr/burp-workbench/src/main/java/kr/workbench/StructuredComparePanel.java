package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import java.awt.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.*;

final class StructuredComparePanel extends JPanel {
  final JTextArea leftInput = new JTextArea(), rightInput = new JTextArea(),
      leftView = new JTextArea(), rightView = new JTextArea(),
      leftRaw = new JTextArea(), rightRaw = new JTextArea();
  final JCheckBox formatted = new JCheckBox("데이터 정렬", true);
  final JLabel status = new JLabel("양쪽에 데이터를 붙여넣고 비교하세요.");
  final JButton previous = new JButton("이전 차이"), next = new JButton("다음 차이"), edit = new JButton("입력 편집");
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
  final JButton captureEvidence = new JButton("원본 → 증거"),
      loadLeft = new JButton("트래픽 가져오기"), loadRight = new JButton("트래픽 가져오기");
  final JLabel[] leftSourceLabels = {new JLabel(), new JLabel()}, rightSourceLabels = {new JLabel(), new JLabel()};
  java.util.function.Supplier<List<HttpRequestResponse>> selectedTraffic = List::of;
  java.util.function.BiConsumer<List<HttpRequestResponse>, String> evidenceSink = (packets, note) -> {
    throw new IllegalArgumentException("증거 기능 준비 중");
  };
  HttpRequestResponse leftSource, rightSource;
  boolean loadingSource;

  StructuredComparePanel() { this(null); }

  StructuredComparePanel(MontoyaApi api) {
    super(new BorderLayout(8, 8)); this.api = api;
    setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    JButton compare = new JButton("비교 실행"), clear = new JButton("초기화");
    compare.setName("wb.compare.run"); edit.setName("wb.compare.edit"); clear.setName("wb.compare.clear");
    captureEvidence.setName("wb.compare.evidence"); loadLeft.setName("wb.compare.load.left"); loadRight.setName("wb.compare.load.right");
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
    content.add(resultTabs, "result");
    add(WorkbenchUi.header("응답·데이터 비교", "상태·헤더·JSON 항목 요약과 원문 차이 · 차이는 취약점 확정 아님",
        WorkbenchUi.row(compare, edit, formatted, previous, next, captureEvidence, clear)), BorderLayout.NORTH);
    add(content); add(status, BorderLayout.SOUTH);
    loadLeft.addActionListener(e -> sourceAction(() -> loadSelected(true)));
    loadRight.addActionListener(e -> sourceAction(() -> loadSelected(false)));
    captureEvidence.addActionListener(e -> sourceAction(this::captureEvidence)); captureEvidence.setEnabled(false);
    watchInput(leftInput, true); watchInput(rightInput, false);
    compare.addActionListener(e -> compare());
    edit.addActionListener(e -> { showing = false; edit.setEnabled(false); previous.setEnabled(false); next.setEnabled(false);
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
    JSplitPane body = WorkbenchUi.split(JSplitPane.VERTICAL_SPLIT, tableScroll, originals, .35);
    body.setName("wb.compare.summary.body");
    JPanel panel = new JPanel(new BorderLayout(0, 6)); panel.add(body);
    JScrollPane notes = new JScrollPane(summaryNote); notes.setPreferredSize(new Dimension(100, 65));
    notes.setMinimumSize(new Dimension(100, 45)); panel.add(notes, BorderLayout.SOUTH);
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
    showing = false; edit.setEnabled(false); previous.setEnabled(false); next.setEnabled(false);
    result = null; summary = null; current = -1;
    summaryModel.setRowCount(0); summaryNote.setText(""); leftRaw.setText(""); rightRaw.setText("");
    cards.show(content, "input");
  }
  void sourceSummary() {
    sourceStatus.setText("왼쪽: " + sourceName(leftSource) + " · 오른쪽: " + sourceName(rightSource) + " · 입력 편집 시 해당 원본 연결 해제");
    captureEvidence.setEnabled(leftSource != null && rightSource != null);
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
    var compared = StructuredCompare.compare(leftInput.getText(), rightInput.getText(), formatted.isSelected());
    int changes = compared.changes().size();
    evidenceSink.accept(packets, "데이터 비교 · 왼쪽/오른쪽 원본 순서 · 표시 줄 차이 " + changes + "곳"
        + (compared.limited() ? " · 비교 표시 제한(65,536자/1,500줄) · 전체 응답 차이 수가 아님" : "") + " · 차이는 취약점 확정 아님");
    status.setText("좌/우 원본 패킷 2개 증거 전달 · 저장은 점검 지원에서 실행하세요.");
  }
  void clearSources() {
    leftSource = null; rightSource = null; leftInput.setText(""); rightInput.setText("");
    leftView.setText(""); rightView.setText(""); invalidateResult(); sourceSummary();
  }
  JPanel pair(JTextArea a, JTextArea b) {
    boolean input = a == leftInput;
    JButton leftLoad = input ? loadLeft : new JButton("트래픽 가져오기"), rightLoad = input ? loadRight : new JButton("트래픽 가져오기");
    if (!input) { leftLoad.addActionListener(e -> sourceAction(() -> loadSelected(true))); rightLoad.addActionListener(e -> sourceAction(() -> loadSelected(false))); }
    leftLoad.setToolTipText("선택한 트래픽 1개의 응답을 왼쪽으로 가져옵니다. 서버 전송은 없습니다.");
    rightLoad.setToolTipText("선택한 트래픽 1개의 응답을 오른쪽으로 가져옵니다. 서버 전송은 없습니다.");
    JPanel left = new JPanel(new BorderLayout(0, 3)), right = new JPanel(new BorderLayout(0, 3));
    left.add(WorkbenchUi.row(leftLoad, leftSourceLabels[input ? 0 : 1]), BorderLayout.NORTH);
    right.add(WorkbenchUi.row(rightLoad, rightSourceLabels[input ? 0 : 1]), BorderLayout.NORTH);
    left.add(new JScrollPane(a)); right.add(new JScrollPane(b));
    JSplitPane split = WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT,
        WorkbenchUi.card("왼쪽 데이터", left), WorkbenchUi.card("오른쪽 데이터", right), .5);
    split.setName("wb.compare.sides"); JPanel panel = new JPanel(new BorderLayout()); panel.add(split); return panel;
  }

  void compare() {
    result = StructuredCompare.compare(leftInput.getText(), rightInput.getText(), formatted.isSelected());
    summary = ResponseDifference.compare(leftInput.getText(), rightInput.getText());
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
