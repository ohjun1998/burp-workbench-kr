package kr.workbench;

import burp.api.montoya.http.message.HttpRequestResponse;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.Predicate;
import javax.swing.*;
import javax.swing.table.*;

/** Two metadata-only views. Neither selecting accounts nor completing work sends HTTP. */
final class AssessmentPanel implements AutoCloseable {
  final ActivityPanel owner;
  final AssessmentTracker tracker;
  final JPanel accountsPage = new JPanel(new BorderLayout(6, 6)),
      tasksPage = new JPanel(new BorderLayout(6, 6));
  final JComboBox<AssessmentTracker.Account> accountChoice = new JComboBox<>();
  final JComboBox<String> accountFilter =
      new JComboBox<>(new String[] {"전체", "미점검", "재검토 필요", "점검 완료", "검사 시도 없음", "증거 미저장"});
  final DefaultTableModel
      accountModel =
          EnvironmentPanel.model(
              "메서드",
              "URL",
              "상태",
              "트래픽 관측",
              "검사 시도",
              "저장 증거",
              "미완료 입력",
              "최근 결과",
              "재검토 근거",
              "완료 당시 조건"),
      inputModel = EnvironmentPanel.model("입력 이름", "상태"),
      taskModel =
          EnvironmentPanel.model(
              "업무", "대상", "계정", "시도 / 요구", "완료 / 요구", "저장 증거", "상태", "파일 확인 조합 / 요구"),
      detailModel = EnvironmentPanel.model("계정", "메서드", "URL", "상태", "검사", "증거");
  final JTable accountTable = new JTable(accountModel),
      inputTable = new JTable(inputModel),
      taskTable = new JTable(taskModel),
      detailTable = new JTable(detailModel);
  final JLabel accountSummary = new JLabel(), taskSummary = new JLabel();
  List<AssessmentTracker.OperationView> operations = List.of(), taskDetails = List.of();
  List<AssessmentTracker.TaskView> tasks = List.of();
  boolean refreshing;
  String shownOperation = "";
  final JTextField accountName = new JTextField(22),
      accountOrigin = new JTextField(34),
      role = new JTextField(12),
      tenant = new JTextField(12),
      taskTitle = new JTextField(30);
  final JCheckBox anonymous = new JCheckBox("비로그인 계정"),
      needsEvidence = new JCheckBox("완료 전 업무에 연결된 증거 ZIP 1개 이상");
  final JCheckBox strictInputs = new JCheckBox("모든 입력 수동 완료 필수"),
      allEvidence = new JCheckBox("모든 계정×API의 파일 확인 증거 필수");
  final JTextField criticalInputs = new JTextField(40);
  final JTextArea taskNote = new JTextArea(3, 45);
  final DefaultListModel<AssessmentTracker.Account> requiredModel = new DefaultListModel<>();
  final JList<AssessmentTracker.Account> requiredAccounts = new JList<>(requiredModel);
  final JComboBox<String> targetTask = new JComboBox<>();
  List<String> targetTaskIds = List.of();
  String editingTask = "", editingAccount = "", pendingMethod = "", pendingUrl = "";
  final JLabel targetPreview = new JLabel();
  final TargetWindow accountWindow, taskWindow, targetWindow, historyWindow;
  final JButton accountEdit,
      accountBind,
      accountUnbind,
      operationFinish,
      operationUndo,
      inputFinish,
      accountTarget,
      taskEdit,
      taskTarget,
      taskRemove,
      taskFinish,
      taskUndo;
  final KeyedTableRows<String> operationRows = new KeyedTableRows<>();
  final KeyedTableRows<String> inputRows = new KeyedTableRows<>();
  final KeyedTableRows<String> taskRows = new KeyedTableRows<>();
  final KeyedTableRows<String> detailRows = new KeyedTableRows<>();
  List<AssessmentTracker.Account> shownAccounts = List.of();
  String shownAccount = "", shownTask = "";
  long shownStructure = -1, shownAccountRevision = -1;
  int shownFilter = -1;
  boolean updatingTables;
  Supplier<List<HttpRequestResponse>> selectedTraffic = List::of;
  Predicate<AssessmentTracker.CompletionReview> completionReviewer = this::confirmCompletion;
  Supplier<Set<String>> authNames = () -> AuthComparePanel.DEFAULT_AUTH, cookieNames = Set::of;

  AssessmentPanel(ActivityPanel owner) {
    this.owner = owner;
    tracker = owner.tracker.assessments;
    accountWindow =
        new TargetWindow(
            owner,
            "점검 계정 등록/설정",
            () ->
                TargetWindow.options(
                    WorkbenchUi.row(new JLabel("계정 이름"), accountName),
                    WorkbenchUi.row(new JLabel("Origin"), accountOrigin),
                    WorkbenchUi.row(new JLabel("역할"), role, new JLabel("조직"), tenant, anonymous),
                    new JLabel("이름/역할은 진단자 지정 · 쿠키/토큰을 입력하지 마세요."),
                    row(button("계정 설정 저장", this::registerAccount))));
    taskWindow =
        new TargetWindow(
            owner,
            "업무 설정",
            () ->
                TargetWindow.options(
                    row(new JLabel("업무 이름"), taskTitle),
                    new JScrollPane(taskNote),
                    WorkbenchUi.card(
                        "요구 계정 · Ctrl/Shift로 여러 계정 선택", new JScrollPane(requiredAccounts)),
                    row(needsEvidence),
                    row(strictInputs, allEvidence),
                    row(new JLabel("중요 입력 (쉼표 구분)"), criticalInputs),
                    new JLabel("예: URL:id, BODY:ownerId · 값은 저장하지 않으며 변경 시 재검토"),
                    row(button("설정 저장", this::saveTask))));
    targetWindow =
        new TargetWindow(
            owner,
            "선택 대상 → 업무 연결",
            () ->
                TargetWindow.options(
                    row(new JLabel("업무"), targetTask),
                    targetPreview,
                    new JLabel("활동 추적 또는 계정별 목록의 선택 API를 추가합니다. 자동 요청 없음."),
                    row(button("업무에 추가", this::attachTarget))));
    historyWindow = new TargetWindow(owner, "계정·업무 점검 이력", this::historyContent);
    for (JTable t : List.of(accountTable, inputTable, taskTable, detailTable)) {
      t.setAutoCreateRowSorter(true);
      t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      t.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    }
    widths(accountTable, 72, 300, 110, 90, 80, 80, 100, 230, 240, 240);
    widths(inputTable, 180, 110);
    widths(taskTable, 220, 65, 65, 105, 105, 90, 110, 145);
    widths(detailTable, 130, 72, 320, 110, 70, 70);
    inputTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
    accountChoice.setPrototypeDisplayValue(
        new AssessmentTracker.Account("", "점검 계정 선택 · 비로그인", "", "", "", false));
    accountChoice.setToolTipText("계정을 선택하면 해당 계정의 점검 상태만 표시합니다.");
    accountFilter.setToolTipText("수동 완료 상태와 별개로 검사 시도·증거 저장이 없는 API를 찾습니다. 검사 시도는 전체 프로필 검증 완료를 뜻하지 않습니다.");
    accountEdit = action("account.edit", "계정 설정", "선택 계정의 이름·역할·조직을 편집합니다.", this::editAccount);
    accountBind =
        action("account.bind", "트래픽 연결", "트래픽 탭에서 선택한 요청 1~20개를 이 계정에 연결합니다.", this::bindSelected);
    accountUnbind =
        action(
            "account.unbind",
            "연결 해제",
            "선택 계정의 세션 연결만 해제합니다. 점검 기록은 유지됩니다.",
            () -> {
              tracker.unbind(selectedAccount());
              refresh();
            });
    operationFinish =
        action("operation.finish", "URL 완료", "선택 계정의 API를 수동 점검 완료로 표시합니다.", () -> finish(true));
    WorkbenchUi.primary(operationFinish);
    operationUndo = action("operation.undo", "완료 해제", "선택 계정의 API 완료를 해제합니다.", () -> finish(false));
    inputFinish =
        action("input.finish", "입력 완료 / 해제", "오른쪽 표에서 선택한 입력의 완료 상태를 전환합니다.", this::finishInput);
    accountTarget =
        action("account.target", "업무에 추가", "선택 API를 기존 업무에 연결합니다. 먼저 업무를 등록하세요.", this::openTarget);
    accountsPage.add(
        TargetWindow.options(
            row(
                new JLabel("계정"),
                accountChoice,
                button("계정 등록", this::openAccount),
                accountEdit,
                accountBind,
                accountUnbind),
            row(
                accountFilter,
                operationFinish,
                operationUndo,
                inputFinish,
                accountTarget,
                button("점검 이력", this::openHistory))),
        BorderLayout.NORTH);
    accountsPage.add(
        WorkbenchUi.split(
            JSplitPane.HORIZONTAL_SPLIT,
            new JScrollPane(accountTable),
            WorkbenchUi.card("선택 URL의 입력", new JScrollPane(inputTable)),
            .72));
    accountsPage.setToolTipText(accountSummary.getText());
    accountSummary.addPropertyChangeListener("text", e -> accountsPage.setToolTipText(accountSummary.getText()));
    taskEdit = action("task.edit", "업무 설정", "선택 업무의 요구 계정·완료 조건을 편집합니다.", () -> openTask(true));
    taskTarget =
        action("task.target", "업무에 추가", "아래 상세 표의 선택 API를 다른 업무에 연결합니다.", this::openTarget);
    taskRemove =
        action("task.remove", "API 제외", "아래 상세 표의 선택 API를 현재 업무에서 제외합니다.", this::removeTarget);
    taskFinish =
        action(
            "task.finish", "업무 완료", "요구 계정·API·입력·증거 조건을 확인하고 수동 완료합니다.", () -> finishTask(true));
    WorkbenchUi.primary(taskFinish);
    taskUndo = action("task.undo", "완료 해제", "선택 업무의 완료를 해제합니다.", () -> finishTask(false));
    JButton files = new JButton("증거 파일 ▾");
    files.setToolTipText("증거 파일 상태 확인·ZIP 재연결·파일 재검증");
    JPopupMenu fileMenu = new JPopupMenu();
    menu(fileMenu, "파일 상태 확인", this::showEvidenceFiles);
    menu(fileMenu, "증거 ZIP 재연결", this::attachEvidenceFile);
    menu(
        fileMenu,
        "모든 증거 재검증",
        () ->
            owner.submit(
                () -> {
                  tracker.verifyEvidenceFiles();
                  SwingUtilities.invokeLater(owner::refresh);
                }));
    WorkbenchUi.style(fileMenu);
    files.addActionListener(e -> fileMenu.show(files, 0, files.getHeight()));
    tasksPage.add(
        row(
            button("업무 등록", () -> openTask(false)),
            taskEdit,
            taskFinish,
            taskUndo,
            files,
            button("점검 이력", this::openHistory)),
        BorderLayout.NORTH);
    JPanel details = new JPanel(new BorderLayout(4, 4));
    details.add(
        row(taskTarget, taskRemove, WorkbenchUi.hint("선택 API에 적용 · Origin이 같은 계정만 조합")),
        BorderLayout.NORTH);
    details.add(new JScrollPane(detailTable));
    tasksPage.add(
        WorkbenchUi.split(
            JSplitPane.VERTICAL_SPLIT,
            new JScrollPane(taskTable),
            WorkbenchUi.card("요구 계정 × API", details),
            .45));
    tasksPage.setToolTipText(taskSummary.getText());
    taskSummary.addPropertyChangeListener("text", e -> tasksPage.setToolTipText(taskSummary.getText()));
    accountChoice.addActionListener(
        e -> {
          if (!refreshing) refreshOperations();
        });
    accountFilter.addActionListener(e -> refreshOperations());
    accountTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!updatingTables && !e.getValueIsAdjusting()) showInputs();
            });
    inputTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!updatingTables && !e.getValueIsAdjusting()) updateActions();
            });
    taskTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!updatingTables && !e.getValueIsAdjusting()) showTask();
            });
    detailTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!updatingTables && !e.getValueIsAdjusting()) updateActions();
            });
    updateActions();
    WorkbenchUi.style(accountsPage);
    WorkbenchUi.style(tasksPage);
  }

  static void widths(JTable table, int... values) {
    for (int i = 0; i < values.length; i++)
      table.getColumnModel().getColumn(i).setPreferredWidth(values[i]);
  }

  JButton action(String name, String text, String hint, Runnable work) {
    JButton b = button(text, work);
    b.setName("wb.assessment." + name);
    b.setToolTipText(hint);
    return b;
  }

  void menu(JPopupMenu menu, String text, Runnable work) {
    JMenuItem item = new JMenuItem(text);
    item.addActionListener(
        e -> {
          try {
            work.run();
            owner.refresh();
          } catch (Exception ex) {
            Diagnostics.failure(owner,"계정·업무 관리",ex);
            owner.summary.setText(Objects.toString(ex.getMessage(), "처리 실패"));
          }
        });
    menu.add(item);
  }

  void updateActions() {
    boolean account = accountChoice.getSelectedItem() != null,
        operation = selected() != null,
        task = selectedTask() != null;
    boolean detail =
        task
            && detailTable.getSelectedRow() >= 0
            && detailTable.convertRowIndexToModel(detailTable.getSelectedRow())
                < taskDetails.size();
    accountEdit.setEnabled(account);
    accountBind.setEnabled(account);
    accountUnbind.setEnabled(account);
    operationFinish.setEnabled(operation);
    operationUndo.setEnabled(operation);
    inputFinish.setEnabled(operation && inputTable.getSelectedRow() >= 0);
    accountTarget.setEnabled(operation && tracker.hasTasks());
    taskEdit.setEnabled(task);
    taskFinish.setEnabled(task);
    taskUndo.setEnabled(task);
    taskTarget.setEnabled(detail && tracker.hasTasks());
    taskRemove.setEnabled(detail);
  }

  static JPanel row(Component... c) {
    return WorkbenchUi.row(c);
  }

  JButton button(String text, Runnable action) {
    JButton b = new JButton(text);
    b.addActionListener(
        e -> {
          try {
            action.run();
            owner.refresh();
          } catch (Exception ex) {
            Diagnostics.failure(owner,"계정·업무 관리",ex);
            owner.summary.setText(Objects.toString(ex.getMessage(), "처리 실패"));
          }
        });
    return b;
  }

  String selectedAccount() {
    var a = (AssessmentTracker.Account) accountChoice.getSelectedItem();
    if (a == null) throw new IllegalArgumentException("점검 계정을 등록/선택하세요.");
    return a.id();
  }

  AssessmentTracker.OperationView selected() {
    int row = accountTable.getSelectedRow();
    int index = row < 0 ? -1 : accountTable.convertRowIndexToModel(row);
    return index < 0 || index >= operations.size() ? null : operations.get(index);
  }

  AssessmentTracker.TaskView selectedTask() {
    int row = taskTable.getSelectedRow();
    int index = row < 0 ? -1 : taskTable.convertRowIndexToModel(row);
    return index < 0 || index >= tasks.size() ? null : tasks.get(index);
  }

  void openAccount() {
    editingAccount = "";
    accountOrigin.setEnabled(true);
    anonymous.setEnabled(true);
    accountName.setText("");
    role.setText("");
    tenant.setText("");
    anonymous.setSelected(false);
    var endpoint = owner.selected();
    if (accountChoice.getSelectedItem() instanceof AssessmentTracker.Account a)
      accountOrigin.setText(a.origin());
    else if (endpoint != null) accountOrigin.setText(AssessmentTracker.origin(endpoint.url()));
    accountWindow.open();
  }

  void editAccount() {
    var a = tracker.account(selectedAccount());
    editingAccount = a.id();
    accountName.setText(a.label());
    accountOrigin.setText(a.origin());
    role.setText(a.role());
    tenant.setText(a.tenant());
    anonymous.setSelected(a.anonymous());
    accountOrigin.setEnabled(false);
    anonymous.setEnabled(false);
    accountWindow.open();
  }

  void registerAccount() {
    String id = editingAccount;
    if (id.isEmpty())
      id =
          tracker.register(
              new AssessmentTracker.AccountInput(
                  accountName.getText(),
                  accountOrigin.getText(),
                  role.getText(),
                  tenant.getText(),
                  anonymous.isSelected()));
    else tracker.editAccount(id, accountName.getText(), role.getText(), tenant.getText());
    refresh();
    selectAccount(id);
    owner.summary.setText("점검 계정 등록 · 선택 트래픽 또는 권한 비교에서 세션을 명시적으로 연결하세요.");
  }

  void selectAccount(String id) {
    for (int i = 0; i < accountChoice.getItemCount(); i++)
      if (accountChoice.getItemAt(i).id().equals(id)) {
        accountChoice.setSelectedIndex(i);
        break;
      }
  }

  void bindSelected() {
    String id = selectedAccount();
    var traffic = selectedTraffic.get();
    if (traffic.isEmpty() || traffic.size() > 20)
      throw new IllegalArgumentException("트래픽에서 1~20개 요청을 선택하세요.");
    List<AssessmentTracker.Binding> batch = new ArrayList<>();
    Set<String> names = Set.copyOf(authNames.get()), cookies = Set.copyOf(cookieNames.get());
    for (var rr : traffic) {
      if (!owner.allowed(rr.request()))
        throw new IllegalArgumentException("선택 트래픽에 SCOPE 밖 요청 포함 · 연결 중단");
      batch.add(
          new AssessmentTracker.Binding(
              id, rr.request().url(), AssessmentTracker.headers(rr.request()), names, cookies));
    }
    tracker.bind(batch);
    for (var rr : traffic) {
      owner.tracker.discover(
          new ReconMetadata.Discovery(
              rr.request().url(),
              rr.request().method(),
              ReconMetadata.parameters(rr.request().url()),
              Set.of(),
              "계정 수동 연결"));
      tracker.observed(
          id,
          rr.request().method(),
          rr.request().url(),
          owner.tracker.parameterNames(rr.request().method(), rr.request().url()),
          "선택 트래픽");
    }
    owner.summary.setText("세션 연결 · 이후 일치하는 인증 조건만 계정에 귀속 · 신원은 진단자 확인");
    refresh();
  }

  void finish(boolean value) {
    var v = selected();
    if (v == null) throw new IllegalArgumentException("계정별 API를 선택하세요.");
    if (value) {
      var review = tracker.completionReview(
          v.account(), v.method(), v.url(), owner.tracker.parameterNames(v.method(), v.url()));
      if (!completionReviewer.test(review)) return;
      tracker.completeReviewed(review, owner.tracker.parameterNames(v.method(), v.url()));
    } else {
      tracker.complete(v.account(), v.method(), v.url(),
          owner.tracker.parameterNames(v.method(), v.url()), false);
    }
    refreshOperations();
  }

  void finishInput() {
    var v = selected();
    int row = inputTable.getSelectedRow();
    if (v == null || row < 0) throw new IllegalArgumentException("계정별 입력을 선택하세요.");
    int index = inputTable.convertRowIndexToModel(row);
    tracker.completeInput(
        v.account(),
        v.method(),
        v.url(),
        inputModel.getValueAt(index, 0).toString(),
        !inputModel.getValueAt(index, 1).equals("점검 완료"));
    refreshOperations();
  }

  void showInputs() {
    var selected = selected();
    String key = selected == null ? "" : operationKey(selected);
    if (!key.equals(shownOperation)) inputTable.clearSelection();
    shownOperation = key;
    inputRows.update(
        inputTable,
        selected == null ? List.of() : selected.parameters(),
        ActivityTracker.ParameterView::key,
        parameter -> new Object[] {parameter.key(), parameter.state()});
    updateActions();
  }

  void refresh() {
    refresh(false);
  }

  void refresh(boolean visibleOnly) {
    Component selectedPage = owner.coveragePages.getSelectedComponent();
    if (!visibleOnly || selectedPage == accountsPage) {
      refreshAccounts();
      refreshOperations();
    }
    if (!visibleOnly || selectedPage == tasksPage) {
      List<AssessmentTracker.TaskView> latest = tracker.tasks();
      if (tasks != latest) {
        updatingTables = true;
        try {
          tasks = latest;
          taskRows.update(
              taskTable,
              tasks,
              AssessmentTracker.TaskView::id,
              task ->
                  new Object[] {
                    task.title(),
                    task.endpoints(),
                    task.accounts(),
                    task.inspected() + " / " + task.required(),
                    task.completed() + " / " + task.required(),
                    task.evidence(),
                    task.state(),
                    task.evidenced() + " / " + task.required()
                  });
        } finally {
          updatingTables = false;
        }
      }
      showTask();
    }
    updateActions();
  }

  private void refreshAccounts() {
    List<AssessmentTracker.Account> latest = tracker.accounts();
    if (shownAccounts == latest) return;
    String selectedId =
        accountChoice.getSelectedItem() instanceof AssessmentTracker.Account account
            ? account.id()
            : "";
    refreshing = true;
    try {
      for (int i = 0; i < latest.size(); i++) {
        if (i < accountChoice.getItemCount() && accountChoice.getItemAt(i).equals(latest.get(i)))
          continue;
        if (i < accountChoice.getItemCount()) accountChoice.removeItemAt(i);
        accountChoice.insertItemAt(latest.get(i), i);
      }
      while (accountChoice.getItemCount() > latest.size())
        accountChoice.removeItemAt(latest.size());
      if (selectedId.isEmpty() && !latest.isEmpty()) accountChoice.setSelectedIndex(0);
      else selectAccount(selectedId);
      shownAccounts = latest;
    } finally {
      refreshing = false;
    }
  }

  private static String operationKey(AssessmentTracker.OperationView view) {
    return view.account() + " " + view.method() + " " + view.url();
  }

  void refreshOperations() {
    var account = (AssessmentTracker.Account) accountChoice.getSelectedItem();
    String accountId = account == null ? "" : account.id();
    long structure = owner.tracker.structureRevision();
    long accountRevision = tracker.accountRevision(accountId);
    int choice = accountFilter.getSelectedIndex();
    if (shownAccount.equals(accountId)
        && shownStructure == structure
        && shownAccountRevision == accountRevision
        && shownFilter == choice) {
      updateActions();
      return;
    }
    List<AssessmentTracker.OperationView> rows = new ArrayList<>();
    if (account != null) {
      for (var endpoint : owner.tracker.views()) {
        if (endpoint.method().equals("?")
            || !AssessmentTracker.origin(endpoint.url()).equals(account.origin())) continue;
        var operation =
            tracker.view(
                account.id(),
                endpoint.method(),
                endpoint.url(),
                endpoint.parameters().stream().map(ActivityTracker.ParameterView::key).toList());
        if (choice == 0
            || choice == 1 && operation.state().equals("미점검")
            || choice == 2 && operation.state().equals("재검토 필요")
            || choice == 3 && operation.state().equals("점검 완료")
            || choice == 4 && operation.inspections() == 0
            || choice == 5 && operation.evidence() == 0) rows.add(operation);
      }
    }
    updatingTables = true;
    try {
      operations = List.copyOf(rows);
      operationRows.update(
          accountTable,
          operations,
          AssessmentPanel::operationKey,
          operation ->
              new Object[] {
                operation.method(),
                operation.url(),
                operation.state(),
                operation.observed(),
                operation.inspections(),
                operation.evidence(),
                operation.parameters().stream()
                    .filter(input -> !input.state().equals("점검 완료"))
                    .count(),
                operation.result(),
                operation.review(),
                operation.completionContext()
              });
    } finally {
      updatingTables = false;
    }
    shownAccount = accountId;
    shownStructure = structure;
    shownAccountRevision = accountRevision;
    shownFilter = choice;
    showInputs();
    accountSummary.setText(
        "계정 " + tracker.accounts().size() + " · 대상 " + rows.size() + " · URL·입력을 선택해 수동 완료하세요.");
    accountSummary.setToolTipText("이름·역할은 진단자 지정입니다. 인증 연결은 기록을 불러온 뒤 다시 지정하세요.");
  }

  void openTask(boolean edit) {
    var selected = selectedTask();
    if (edit && selected == null) throw new IllegalArgumentException("업무를 선택하세요.");
    editingTask = edit ? selected.id() : "";
    taskTitle.setText(edit ? selected.title() : "");
    taskNote.setText(edit ? selected.note() : "");
    needsEvidence.setSelected(edit && selected.requiresEvidence());
    strictInputs.setSelected(edit && selected.strictInputs());
    allEvidence.setSelected(edit && selected.allEvidence());
    criticalInputs.setText(edit ? String.join(", ", selected.criticalInputs()) : "");
    Set<String> ids =
        edit
            ? tracker.taskAccounts(selected.id())
            : accountChoice.getSelectedItem() instanceof AssessmentTracker.Account a
                ? Set.of(a.id())
                : Set.of();
    requiredModel.clear();
    for (var a : tracker.accounts()) requiredModel.addElement(a);
    for (int i = 0; i < requiredModel.size(); i++)
      if (ids.contains(requiredModel.get(i).id())) requiredAccounts.addSelectionInterval(i, i);
    taskWindow.open();
  }

  void saveTask() {
    Set<String> critical =
        AssessmentTracker.validateCritical(
            Arrays.stream(criticalInputs.getText().split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList());
    Set<String> ids = new LinkedHashSet<>();
    for (var a : requiredAccounts.getSelectedValuesList()) ids.add(a.id());
    String id = editingTask;
    if (id.isEmpty())
      id =
          tracker.createTask(
              taskTitle.getText(), taskNote.getText(), ids, needsEvidence.isSelected());
    else
      tracker.editTask(
          id, taskTitle.getText(), taskNote.getText(), ids, needsEvidence.isSelected());
    tracker.taskRequirements(id, strictInputs.isSelected(), allEvidence.isSelected(), critical);
    editingTask = id;
    refresh();
    for (int i = 0; i < tasks.size(); i++)
      if (tasks.get(i).id().equals(id)) {
        int row = taskTable.convertRowIndexToView(i);
        taskTable.setRowSelectionInterval(row, row);
        break;
      }
  }

  void openTarget() {
    var currentPage = owner.coveragePages.getSelectedComponent();
    var account = selected();
    var endpoint = owner.selected();
    if (currentPage == accountsPage && account != null) {
      pendingMethod = account.method();
      pendingUrl = account.url();
    } else if (currentPage == tasksPage && detailTable.getSelectedRow() >= 0) {
      var op = taskDetails.get(detailTable.convertRowIndexToModel(detailTable.getSelectedRow()));
      pendingMethod = op.method();
      pendingUrl = op.url();
    } else if (currentPage == owner.coveragePages.getComponentAt(0) && endpoint != null) {
      pendingMethod = endpoint.method();
      pendingUrl = endpoint.url();
    } else throw new IllegalArgumentException("현재 보이는 목록에서 API를 선택하세요.");
    if (!owner.allowedUrl(pendingUrl)) throw new IllegalArgumentException("선택 API가 SCOPE 밖입니다.");
    targetPreview.setText(pendingMethod + " " + pendingUrl);
    targetTask.removeAllItems();
    List<String> ids = new ArrayList<>();
    for (var t : tracker.tasks()) {
      targetTask.addItem(t.title() + " · " + t.id().substring(0, 8));
      ids.add(t.id());
    }
    targetTaskIds = List.copyOf(ids);
    if (ids.isEmpty()) throw new IllegalArgumentException("요구 계정과 업무를 먼저 등록하세요.");
    targetWindow.open();
  }

  void attachTarget() {
    int index = targetTask.getSelectedIndex();
    if (index < 0) throw new IllegalArgumentException("업무를 선택하세요.");
    if (pendingMethod.isEmpty() || pendingUrl.isEmpty() || !owner.allowedUrl(pendingUrl))
      throw new IllegalArgumentException("대상/SCOPE를 다시 확인하세요.");
    tracker.addTarget(targetTaskIds.get(index), pendingMethod, pendingUrl);
    refresh();
    owner.summary.setText("업무 대상 추가 · 계정별 요구 조합을 확인하세요.");
  }

  void removeTarget() {
    var task = selectedTask();
    int row = detailTable.getSelectedRow();
    if (task == null || row < 0) throw new IllegalArgumentException("업무 상세에서 제외할 API를 선택하세요.");
    var op = taskDetails.get(detailTable.convertRowIndexToModel(row));
    tracker.removeTarget(task.id(), op.method(), op.url());
    refresh();
  }

  void finishTask(boolean value) {
    var task = selectedTask();
    if (task == null) throw new IllegalArgumentException("업무를 선택하세요.");
    if (value) {
      var review = tracker.taskCompletionReview(task.id());
      if (!completionReviewer.test(review)) return;
      tracker.completeTaskReviewed(review);
    } else tracker.completeTask(task.id(), false);
    refresh();
  }

  private boolean confirmCompletion(AssessmentTracker.CompletionReview review) {
    // A headless host cannot supply a manual confirmation. Tests inject a reviewer explicitly.
    if (GraphicsEnvironment.isHeadless()) return false;
    JTextArea text = new JTextArea(review.summary(), 13, 65);
    text.setName("wb.assessment.completion.review");
    text.setEditable(false);
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.setCaretPosition(0);
    JScrollPane content = new JScrollPane(text);
    WorkbenchUi.style(content);
    return JOptionPane.showOptionDialog(owner, content, "완료 전 결과·근거 검토",
        JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
        new String[]{"검토 후 완료", "취소"}, "취소") == 0;
  }

  void showEvidenceFiles() {
    JTextArea text = new JTextArea(String.join("\n", tracker.evidenceFiles()), 16, 80);
    text.setEditable(false);
    JOptionPane.showMessageDialog(
        owner, new JScrollPane(text), "증거 파일 상태 · 마지막 확인 결과", JOptionPane.INFORMATION_MESSAGE);
  }

  void attachEvidenceFile() {
    JFileChooser chooser = new JFileChooser();
    if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
    var path = chooser.getSelectedFile().toPath();
    owner.submit(
        () -> {
          try {
            tracker.attachEvidenceFile(path);
            SwingUtilities.invokeLater(owner::refresh);
          } catch (Exception e) {Diagnostics.failure(owner,"계정·업무 관리",e);
            SwingUtilities.invokeLater(() -> owner.summary.setText(e.getMessage()));
          }
        });
  }

  void showTask() {
    var selected = selectedTask();
    String key = selected == null ? "" : selected.id();
    if (!key.equals(shownTask)) detailTable.clearSelection();
    shownTask = key;
    taskDetails = selected == null ? List.of() : tracker.taskOperations(selected.id());
    updatingTables = true;
    try {
      detailRows.update(
          detailTable,
          taskDetails,
          AssessmentPanel::operationKey,
          operation ->
              new Object[] {
                tracker.account(operation.account()).label(), operation.method(), operation.url(),
                operation.state(), operation.inspections(), operation.evidence()
              });
    } finally {
      updatingTables = false;
    }
    taskSummary.setText(
        selected == null
            ? "업무를 등록하고 API와 요구 계정을 연결하세요."
            : selected.title()
                + " · "
                + selected.state()
                + " · 완료 "
                + selected.completed()
                + " / "
                + selected.required());
    taskSummary.setToolTipText(
        selected == null
            ? taskSummary.getText()
            : selected.note() + " · 검사 관측과 수동 완료/ZIP 저장은 별도 단계");
    updateActions();
  }

  JPanel historyContent() {
    var model = EnvironmentPanel.model("시각", "계정", "대상/업무 ID", "작업", "설명");
    for (var e : tracker.history())
      model.addRow(
          new Object[] {
            e.time(),
            e.account().isEmpty() ? "" : tracker.account(e.account()).label(),
            e.target(),
            e.action(),
            e.detail()
          });
    JTable table = new JTable(model);
    table.setAutoCreateRowSorter(true);
    return TargetWindow.options(
        new JLabel("최근 최대 5,000개 메타데이터 · 인증값/요청 본문 없음"), new JScrollPane(table));
  }

  void openHistory() {
    historyWindow.closeWindow();
    historyWindow.open();
  }

  public void close() {
    for (var w : List.of(accountWindow, taskWindow, targetWindow, historyWindow)) w.close();
    operationRows.clear();
    inputRows.clear();
    taskRows.clear();
    detailRows.clear();
  }
}
