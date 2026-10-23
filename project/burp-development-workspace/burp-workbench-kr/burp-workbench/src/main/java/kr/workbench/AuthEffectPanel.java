package kr.workbench;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.awt.*;
import java.net.URI;
import java.util.List;
import java.util.Set;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Explicit local setup aids; choosing an observed response never sends a GET or a write. */
final class AuthEffectPanel extends JPanel {
  final AuthComparePanel owner;
  final JCheckBox enabled = new JCheckBox("쓰기 요청 전후 조회로 처리 결과 확인");
  final JComboBox<String> method = new JComboBox<>(new String[]{"POST", "PUT", "PATCH", "DELETE"}),
      mode = new JComboBox<>(new String[]{"기대값으로 변경", "값 변경 관측", "대상/필드 삭제", "숫자 증가 상한 초과", "숫자 감소 하한 미달"});
  final JTextField path = new JTextField(22), url = new JTextField(35), jsonPath = new JTextField(20), expected = new JTextField(18);
  final JSpinner attempts = new JSpinner(new SpinnerNumberModel(1, 1, 3, 1));
  TargetWindow suggestionWindow;
  final JLabel feedback = new JLabel("① 대상 연결 → ② 관측 GET 선택 → ③ JSON 필드·기대값 지정 → 비교 전 적용 확인");

  AuthEffectPanel(AuthComparePanel owner) {
    super(new BorderLayout()); this.owner = owner; setName("wb.auth.effect.settings");
    mode.setPreferredSize(new Dimension(180, 32)); method.setPreferredSize(new Dimension(90, 32));
    path.setName("wb.auth.effect.path"); url.setName("wb.auth.effect.url"); jsonPath.setName("wb.auth.effect.jsonpath");
    JButton connect = owner.button("선택 요청 연결", () -> connectSelected(true));
    JButton reads = owner.button("관측 GET 후보", this::openReads), fields = owner.button("보관 JSON 필드", this::openFields);
    reads.setName("wb.auth.effect.observed.reads"); fields.setName("wb.auth.effect.observed.fields");
    JPanel body = TargetWindow.options(
        WorkbenchUi.row(enabled),
        WorkbenchUi.row(new JLabel("① 대상 요청"), method, path, connect),
        WorkbenchUi.row(new JLabel("② 조회 GET URL"), url, reads),
        WorkbenchUi.row(new JLabel("③ 결과 JSON"), jsonPath, fields),
        WorkbenchUi.row(new JLabel("기대값 / 변화 경계"), expected, mode),
        WorkbenchUi.row(new JLabel("최대 조회"), attempts),
        feedback,
        new JLabel("후보 선택은 보관 응답만 읽습니다. 실제 GET·쓰기는 비교 시작 후 전송됩니다."),
        new JLabel("관측값은 과거 값이며 기대값을 자동 지정하지 않습니다. 업무상 목표값을 직접 입력하세요."),
        new JLabel("기준 요청의 인증으로 전후 조회합니다. 쓰기는 계정당 한 번만 보내며 되돌리지 않습니다."),
        new JLabel("같은 Origin/SCOPE · 최대 128KiB · 조회 설정은 1개 메서드/경로 규칙에만 적용됩니다."));
    add(body);
  }

  HttpRequest selectedWrite() {
    List<HttpRequestResponse> requests = owner.selected.get();
    if (requests.size() != 1) throw new IllegalArgumentException("쓰기 요청 한 개를 선택하세요.");
    HttpRequest request = requests.get(0).request();
    if (!Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.method()))
      throw new IllegalArgumentException("POST·PUT·PATCH·DELETE 쓰기 요청을 선택하세요.");
    return request;
  }

  void connectSelected(boolean replace) {
    owner.editable(); HttpRequest request = selectedWrite();
    if (!replace && !path.getText().isBlank()) {
      feedback.setText("기존 대상 설정 유지 · 다른 요청을 검사하려면 ‘선택 요청 연결’을 누르세요."); return;
    }
    method.setSelectedItem(request.method()); path.setText(URI.create(request.url()).getPath());
    feedback.setText("대상 사전 입력 · 조회 URL·필드·기대값과 사용 체크를 확인하세요. 요청은 전송하지 않았습니다.");
  }

  void requireMatchingTarget() {
    HttpRequest request = selectedWrite();
    if (path.getText().isBlank()) connectSelected(false);
    AuthPolicy policy = AuthPolicy.parse(method.getSelectedItem() + " " + path.getText().strip() + " PUBLIC", AuthPolicy.Access.UNSPECIFIED);
    if (policy.forRequest(request.method(), request.url()) != AuthPolicy.Access.PUBLIC)
      throw new IllegalArgumentException("설정 대상과 현재 선택 요청이 다릅니다. ‘선택 요청 연결’을 먼저 누르세요.");
  }

  List<AuthSetupSuggestions.Read> observedReads() {
    requireMatchingTarget();
    long selectionRevision = owner.workSelectionRevision();
    return AuthSetupSuggestions.reads(selectedWrite(), owner.observedRequests.get(), request -> WorkbenchScope.contains(owner.api, request))
        .stream().map(read -> read.withSelectionRevision(selectionRevision)).toList();
  }

  void openReads() {
    owner.editable(); var candidates = observedReads();
    if (candidates.isEmpty()) throw new IllegalArgumentException("최근 보관 요청 100개에 같은 Origin/SCOPE의 2xx GET 후보가 없습니다. 조회 URL을 직접 입력하거나 실제 조회 트래픽을 먼저 수집하세요.");
    DefaultTableModel model = readOnly("보관 GET URL", "HTTP", "연결 근거");
    for (var read : candidates) model.addRow(new Object[]{read.url(), read.packet().response().statusCode(), read.reason()});
    JTable table = table(model, "wb.auth.effect.read.candidates");
    JPanel panel = new JPanel(new BorderLayout(6, 6));
    panel.add(AuthReadiness.note("보관 GET 후보 · 최근 최대 100개에서 같은 Origin/SCOPE의 2xx·128KiB 이하 응답만 확인합니다. 경로 유사성은 실제 처리 결과 조회라는 증거가 아닙니다.", 3), BorderLayout.NORTH);
    panel.add(new JScrollPane(table));
    TargetWindow window = new TargetWindow(this, "쓰기 결과 조회 · 관측 GET 후보", () -> panel);
    panel.add(owner.button("선택 URL을 조회 설정에 적용", () -> {
      owner.editable(); requireMatchingTarget(); int row = table.getSelectedRow();
      if (row < 0) throw new IllegalArgumentException("적용할 GET 후보를 선택하세요.");
      var candidate = candidates.get(table.convertRowIndexToModel(row));
      applyRead(candidate); window.closeWindow();
    }), BorderLayout.SOUTH);
    openSuggestion(window);
  }

  void applyRead(AuthSetupSuggestions.Read candidate) {
    owner.editable(); requireMatchingTarget();
    HttpRequest target = selectedWrite();
    if (candidate.selectionRevision() != owner.workSelectionRevision()
        || !candidate.target().equals(target.method() + " " + target.url())
        || !AuthComparePanel.origin(target).equals(AuthComparePanel.origin(candidate.packet().request()))
        || !WorkbenchScope.contains(owner.api, candidate.packet().request()))
      throw new IllegalArgumentException("선택 요청 또는 SCOPE가 변경되었습니다. GET 후보를 다시 여세요.");
    if (!url.getText().strip().equals(candidate.url())) { jsonPath.setText(""); expected.setText(""); }
    url.setText(candidate.url());
    feedback.setText("보관 GET URL 적용 · ‘보관 JSON 필드’에서 필드를 고르고 기대값을 직접 지정하세요. 실행 성공은 미확인입니다.");
  }

  void openFields() {
    owner.editable(); String current = url.getText().strip();
    var read = observedReads().stream().filter(candidate -> candidate.url().equals(current)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("이 조회 URL과 정확히 일치하는 보관 GET 응답이 없습니다. 필드를 직접 입력하거나 GET 후보를 선택하세요."));
    String sourceTarget = selectedWrite().method() + " " + selectedWrite().url();
    long selectionRevision = owner.workSelectionRevision();
    var fields = AuthSetupSuggestions.fields(read.packet());
    if (fields.isEmpty()) throw new IllegalArgumentException("선택 가능한 JSON 필드가 없습니다. 128KiB 이하 JSON 객체의 $. 경로만 지원하며, 조회 필드는 직접 입력할 수 있습니다.");
    DefaultTableModel model = readOnly("JSON 경로", "과거 관측값 · 기대값 아님");
    for (var field : fields) model.addRow(new Object[]{field.path(), field.value()});
    JTable table = table(model, "wb.auth.effect.json.candidates");
    JPanel panel = new JPanel(new BorderLayout(6, 6));
    panel.add(AuthReadiness.note("보관 응답에서 최대 200개 필드를 표시합니다. 선택은 경로만 적용하며, 과거 관측값을 변경 후 기대값으로 자동 사용하지 않습니다.", 3), BorderLayout.NORTH);
    panel.add(new JScrollPane(table));
    TargetWindow window = new TargetWindow(this, "쓰기 결과 조회 · JSON 필드 선택", () -> panel);
    panel.add(owner.button("선택 경로 적용", () -> {
      owner.editable(); requireMatchingTarget(); int row = table.getSelectedRow();
      if (row < 0) throw new IllegalArgumentException("관측할 JSON 필드를 선택하세요.");
      if (selectionRevision != owner.workSelectionRevision() || !current.equals(url.getText().strip()) || !sourceTarget.equals(selectedWrite().method() + " " + selectedWrite().url())
          || !WorkbenchScope.contains(owner.api, read.packet().request()))
        throw new IllegalArgumentException("대상·조회 URL 또는 SCOPE가 변경되었습니다. JSON 필드 창을 다시 여세요.");
      String chosen = fields.get(table.convertRowIndexToModel(row)).path();
      if (!chosen.equals(jsonPath.getText().strip())) expected.setText("");
      jsonPath.setText(chosen);
      feedback.setText("JSON 경로 적용 · 기대값은 업무 기준으로 직접 지정하세요. 실제 조회 성공은 비교 후 근거에서 확인합니다.");
      window.closeWindow();
    }), BorderLayout.SOUTH);
    openSuggestion(window);
  }

  void openSuggestion(TargetWindow window) {
    if (suggestionWindow != null) suggestionWindow.close();
    suggestionWindow = window; window.open();
  }
  void closeSuggestions() { if (suggestionWindow != null) suggestionWindow.close(); suggestionWindow = null; }

  private static DefaultTableModel readOnly(String... columns) {
    return new DefaultTableModel(columns, 0) { public boolean isCellEditable(int row, int column) { return false; } };
  }
  private static JTable table(DefaultTableModel model, String name) {
    JTable table = new AuthLayout.Table(model); table.setName(name); table.setAutoCreateRowSorter(true);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setPreferredScrollableViewportSize(new Dimension(760, 300));
    return table;
  }
  AuthEffectCheck.Config snapshot() {
    return new AuthEffectCheck.Config(enabled.isSelected(), (String) method.getSelectedItem(), path.getText().strip(), url.getText().strip(), jsonPath.getText().strip(), expected.getText(), AuthEffectCheck.Mode.values()[mode.getSelectedIndex()], (Integer) attempts.getValue());
  }
}
