package kr.workbench;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.awt.*;
import java.net.URI;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
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
  final AuthSuggestionLoader loader = new AuthSuggestionLoader();
  final JTextField ruleName = new JTextField("쓰기 조회 1", 14), ruleOrigin = new JTextField(28);
  final JComboBox<String> ruleChoice = new JComboBox<>();
  final List<AuthEffectRules.Rule> savedRules = new ArrayList<>();
  String loadedKey = "";
  record SetupStamp(String target, long selection, long configuration, String settings, String scope) {}
  record FieldResult(AuthSetupSuggestions.Read read, List<AuthSetupSuggestions.Field> fields) {}
  final JLabel feedback = new JLabel("① 대상 연결 → ② 관측 GET 선택 → ③ JSON 필드·기대값 지정 → 비교 전 적용 확인");

  AuthEffectPanel(AuthComparePanel owner) {
    super(new BorderLayout()); this.owner = owner; setName("wb.auth.effect.settings");
    mode.setPreferredSize(new Dimension(180, 32)); method.setPreferredSize(new Dimension(90, 32));
    path.setName("wb.auth.effect.path"); url.setName("wb.auth.effect.url"); jsonPath.setName("wb.auth.effect.jsonpath");
    JButton connect = owner.button("선택 요청 연결", () -> connectSelected(true));
    JButton reads = owner.button("관측 GET 후보", this::openReads), fields = owner.button("보관 JSON 필드", this::openFields);
    reads.setName("wb.auth.effect.observed.reads"); fields.setName("wb.auth.effect.observed.fields");
    JPanel body = TargetWindow.options(
        WorkbenchUi.row(new JLabel("저장된 조회 규칙"), ruleChoice,
            owner.button("규칙 불러오기", this::loadSelectedRule), owner.button("선택 규칙 삭제", this::deleteSelectedRule)),
        WorkbenchUi.row(new JLabel("규칙 이름"), ruleName, owner.button("규칙 저장·갱신", this::saveRule),
            owner.button("새 규칙", () -> { owner.editable(); loadedKey = ""; ruleName.setText("쓰기 조회 " + (savedRules.size() + 1)); enabled.setSelected(false); feedback.setText("새 규칙 초안 · 대상과 조회 설정을 입력한 뒤 저장하세요."); })),
        WorkbenchUi.row(new JLabel("대상 Origin"), ruleOrigin),
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
        new JLabel("같은 Origin/SCOPE · 최대 128KiB · 최대 32개 Origin·메서드·경로 규칙 · 겹치는 규칙은 실행 전에 차단합니다."));
    add(body);
  }

  HttpRequest selectedWrite() {
    List<HttpRequestResponse> requests = owner.setupRequests();
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
    ruleOrigin.setText(AuthComparePanel.origin(request));
    feedback.setText("대상 사전 입력 · 조회 URL·필드·기대값과 사용 체크를 확인하세요. 요청은 전송하지 않았습니다.");
  }

  void requireMatchingTarget() {
    HttpRequest request = selectedWrite();
    if (path.getText().isBlank()) connectSelected(false);
    AuthPolicy policy = AuthPolicy.parse(method.getSelectedItem() + " " + path.getText().strip() + " PUBLIC", AuthPolicy.Access.UNSPECIFIED);
    if ((!ruleOrigin.getText().isBlank() && !AuthEffectRules.canonicalOrigin(ruleOrigin.getText()).equals(AuthComparePanel.origin(request)))
        || policy.forRequest(request.method(), request.url()) != AuthPolicy.Access.PUBLIC)
      throw new IllegalArgumentException("설정 대상과 현재 선택 요청이 다릅니다. ‘선택 요청 연결’을 먼저 누르세요.");
  }

  List<AuthSetupSuggestions.Read> observedReads() {
    requireMatchingTarget();
    long selectionRevision = owner.workSelectionRevision();
    return AuthSetupSuggestions.reads(selectedWrite(), owner.observedRequests.get(), request -> WorkbenchScope.contains(owner.api, request))
        .stream().map(read -> read.withSelectionRevision(selectionRevision)).toList();
  }

  SetupStamp setupStamp() {
    HttpRequest request = selectedWrite();
    return new SetupStamp(request.method() + " " + request.url(), owner.workSelectionRevision(), owner.workConfigurationRevision(),
        method.getSelectedItem() + "|" + path.getText() + "|" + url.getText() + "|" + jsonPath.getText() + "|" + expected.getText()
            + "|" + mode.getSelectedIndex() + "|" + attempts.getValue() + "|" + enabled.isSelected() + "|" + ruleOrigin.getText(),
        WorkbenchScope.registeredRules(owner.api).toString());
  }
  boolean current(SetupStamp stamp) {
    try { return stamp.equals(setupStamp()) && WorkbenchScope.contains(owner.api, selectedWrite()); }
    catch (RuntimeException ignored) { return false; }
  }
  Callable<List<AuthSetupSuggestions.Read>> readJob(SetupStamp stamp) {
    HttpRequest target = selectedWrite();
    var refsSource = owner.observedRequestRefs;
    return () -> {
      List<DiskTrafficStore.PacketRef> refs = refsSource == null ? null : List.copyOf(refsSource.get());
      List<AuthSetupSuggestions.Read> reads = refs == null
          ? AuthSetupSuggestions.reads(target, owner.observedRequests.get(), request -> WorkbenchScope.contains(owner.api, request))
          : AuthSetupSuggestions.readsFromRefs(target, refs, request -> WorkbenchScope.contains(owner.api, request));
      return reads.stream().map(read -> read.withSelectionRevision(stamp.selection())).toList();
    };
  }
  void openReads() {
    owner.editable(); requireMatchingTarget(); SetupStamp stamp = setupStamp();
    var task = readJob(stamp);
    DefaultTableModel model = readOnly("보관 GET URL", "HTTP", "연결 근거");
    JTable table = table(model, "wb.auth.effect.read.candidates");
    JLabel loading = new JLabel("보관 원본을 읽는 중 · 서버 요청 없음 · 취소할 수 있습니다.");
    AtomicReference<List<AuthSetupSuggestions.Read>> result = new AtomicReference<>(List.of());
    JPanel panel = new JPanel(new BorderLayout(6, 6));
    panel.add(AuthReadiness.note("최근 최대 100개 · 같은 Origin/SCOPE · 2xx·128KiB 이하 GET 후보입니다. 경로 유사성은 실제 처리 결과 조회의 증거가 아닙니다.", 3), BorderLayout.NORTH);
    panel.add(new JScrollPane(table));
    TargetWindow window = new TargetWindow(this, "쓰기 결과 조회 · 관측 GET 후보", () -> panel);
    JButton apply = owner.button("선택 URL을 조회 설정에 적용", () -> {
      owner.editable(); requireMatchingTarget();
      if (!current(stamp)) throw new IllegalArgumentException("선택·Scope·설정이 변경되었습니다. 후보 창을 다시 여세요.");
      int row = table.getSelectedRow();
      if (row < 0) throw new IllegalArgumentException("적용할 GET 후보를 선택하세요.");
      applyRead(result.get().get(table.convertRowIndexToModel(row))); window.closeWindow(); loader.cancel();
    });
    apply.setEnabled(false);
    panel.add(TargetWindow.options(loading, WorkbenchUi.row(apply, owner.button("취소", () -> { loader.cancel(); window.closeWindow(); }))), BorderLayout.SOUTH);
    openSuggestion(window);
    loader.load(task, reads -> {
      if (!current(stamp)) { loading.setText("선택·Scope·설정 변경 · 이전 후보 폐기, 창을 다시 여세요."); return; }
      result.set(reads);
      for (var read : reads) model.addRow(new Object[]{read.url(), read.packet().response().statusCode(), read.reason()});
      apply.setEnabled(!reads.isEmpty());
      loading.setText(reads.isEmpty() ? "범위 안의 보관 GET 후보 없음 · 직접 입력하거나 조회 트래픽을 수집하세요." : "후보 " + reads.size() + "개 · 적용할 URL을 선택하세요.");
    }, error -> loading.setText("보관 원본 읽기 실패/중지 · 직접 입력하거나 창을 다시 여세요."));
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
    owner.editable(); requireMatchingTarget(); SetupStamp stamp = setupStamp(); String currentUrl = url.getText().strip();
    var source = readJob(stamp);
    DefaultTableModel model = readOnly("JSON 경로", "과거 관측값 · 기대값 아님");
    JTable table = table(model, "wb.auth.effect.json.candidates");
    AtomicReference<FieldResult> result = new AtomicReference<>();
    JLabel loading = new JLabel("보관 GET 복원·JSON 파싱 중 · 서버 요청 없음");
    JPanel panel = new JPanel(new BorderLayout(6, 6));
    panel.add(AuthReadiness.note("보관 응답에서 최대 200개 필드를 표시합니다. 경로만 적용하며 과거 관측값을 기대값으로 자동 사용하지 않습니다.", 3), BorderLayout.NORTH);
    panel.add(new JScrollPane(table));
    TargetWindow window = new TargetWindow(this, "쓰기 결과 조회 · JSON 필드 선택", () -> panel);
    JButton apply = owner.button("선택 경로 적용", () -> {
      owner.editable(); requireMatchingTarget();
      FieldResult data = result.get(); int row = table.getSelectedRow();
      if (data == null || row < 0) throw new IllegalArgumentException("관측할 JSON 필드를 선택하세요.");
      if (!current(stamp) || !WorkbenchScope.contains(owner.api, data.read().packet().request()))
        throw new IllegalArgumentException("선택·Scope·설정이 변경되었습니다. JSON 필드 창을 다시 여세요.");
      String chosen = data.fields().get(table.convertRowIndexToModel(row)).path();
      if (!chosen.equals(jsonPath.getText().strip())) expected.setText("");
      jsonPath.setText(chosen); feedback.setText("JSON 경로 적용 · 기대값은 직접 지정하세요. 실제 조회 성공은 실행 후 확인합니다.");
      window.closeWindow(); loader.cancel();
    });
    apply.setEnabled(false);
    panel.add(TargetWindow.options(loading, WorkbenchUi.row(apply, owner.button("취소", () -> { loader.cancel(); window.closeWindow(); }))), BorderLayout.SOUTH);
    openSuggestion(window);
    loader.load(() -> {
      var read = source.call().stream().filter(candidate -> candidate.url().equals(currentUrl)).findFirst()
          .orElseThrow(() -> new IllegalArgumentException("조회 URL과 일치하는 보관 GET 응답 없음"));
      return new FieldResult(read, AuthSetupSuggestions.fields(read.packet()));
    }, data -> {
      if (!current(stamp)) { loading.setText("선택·Scope·설정 변경 · 이전 필드 폐기, 창을 다시 여세요."); return; }
      result.set(data); for (var field : data.fields()) model.addRow(new Object[]{field.path(), field.value()});
      apply.setEnabled(!data.fields().isEmpty());
      loading.setText(data.fields().isEmpty() ? "지원 JSON 필드 없음 · $. 경로를 직접 입력할 수 있습니다." : "필드 " + data.fields().size() + "개 · 기대값은 자동 지정하지 않습니다.");
    }, error -> loading.setText("일치하는 보관 JSON 없음 또는 디코딩 실패 · 직접 입력하거나 GET 후보를 확인하세요."));
  }

  void openSuggestion(TargetWindow window) {
    loader.cancel(); if (suggestionWindow != null) suggestionWindow.close();
    suggestionWindow = window;
    window.opener = (parent, title, content, modal, closed) -> WorkbenchUi.settingsWindow(parent, title, content, modal, () -> { loader.cancel(); closed.run(); });
    window.open();
  }
  void closeSuggestions() { loader.close(); if (suggestionWindow != null) suggestionWindow.close(); suggestionWindow = null; }

  AuthEffectRules.Rule draftRule(String fallbackOrigin) {
    AuthEffectCheck.Config config = snapshot();
    // Disabled stored rules still need a valid editable configuration.
    new AuthEffectCheck.Config(true, config.method(), config.path(), config.url(), config.jsonPath(), config.expected(), config.mode(), config.attempts());
    String origin = ruleOrigin.getText().strip();
    return new AuthEffectRules.Rule(ruleName.getText(), origin.isBlank() ? fallbackOrigin : origin, config);
  }
  List<AuthEffectRules.Rule> snapshotRules(String fallbackOrigin) {
    List<AuthEffectRules.Rule> result = new ArrayList<>(savedRules);
    if (enabled.isSelected() || !loadedKey.isEmpty()) {
      var draft = draftRule(fallbackOrigin);
      if (!loadedKey.isEmpty()) result.removeIf(rule -> rule.key().equals(loadedKey));
      result.add(draft);
    }
    AuthEffectRules.validate(result); return List.copyOf(result);
  }
  void saveRule() {
    owner.editable(); var draft = draftRule(AuthComparePanel.origin(selectedWrite()));
    List<AuthEffectRules.Rule> next = new ArrayList<>(savedRules);
    if (!loadedKey.isEmpty()) next.removeIf(rule -> rule.key().equals(loadedKey));
    next.add(draft); AuthEffectRules.validate(next);
    savedRules.clear(); savedRules.addAll(next); loadedKey = draft.key(); refreshRuleChoices(draft.name());
    owner.workConfigurationEdited(); owner.refreshPreparedConfiguration();
    feedback.setText("조회 규칙 저장 · " + savedRules.size() + "개 · 실행 요청마다 Origin·메서드·경로를 대조합니다.");
  }
  void refreshRuleChoices(String name) {
    ruleChoice.removeAllItems(); for (var rule : savedRules) ruleChoice.addItem(rule.name());
    ruleChoice.setSelectedItem(name);
  }
  void loadSelectedRule() {
    owner.editable(); String name = Objects.toString(ruleChoice.getSelectedItem(), "");
    var rule = savedRules.stream().filter(row -> row.name().equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException("불러올 규칙을 선택하세요."));
    loadRule(rule); feedback.setText("규칙 불러옴 · 편집 내용은 다음 비교 설정에 반영됩니다.");
  }
  void loadRule(AuthEffectRules.Rule rule) {
    loadedKey = rule.key(); var config = rule.config(); ruleName.setText(rule.name()); ruleOrigin.setText(rule.origin());
    enabled.setSelected(config.enabled()); method.setSelectedItem(config.method()); path.setText(config.path()); url.setText(config.url());
    jsonPath.setText(config.jsonPath()); expected.setText(config.expected()); mode.setSelectedIndex(config.mode().ordinal()); attempts.setValue(config.attempts());
  }
  void replaceRules(List<AuthEffectRules.Rule> rules) {
    AuthEffectRules.validate(rules); savedRules.clear(); savedRules.addAll(rules); loadedKey = "";
    if (rules.isEmpty()) { enabled.setSelected(false); refreshRuleChoices(""); }
    else { loadRule(rules.get(0)); refreshRuleChoices(rules.get(0).name()); }
  }
  void deleteSelectedRule() {
    owner.editable(); String name = Objects.toString(ruleChoice.getSelectedItem(), "");
    var rule = savedRules.stream().filter(row -> row.name().equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException("삭제할 규칙을 선택하세요."));
    savedRules.remove(rule); if (loadedKey.equals(rule.key())) { loadedKey = ""; enabled.setSelected(false); }
    refreshRuleChoices(""); owner.workConfigurationEdited(); owner.refreshPreparedConfiguration();
  }

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
