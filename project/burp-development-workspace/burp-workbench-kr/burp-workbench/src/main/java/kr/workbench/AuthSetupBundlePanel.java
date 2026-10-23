package kr.workbench;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Explicit review-first setup exchange. Reading a file never applies rules or starts comparison. */
final class AuthSetupBundlePanel extends JPanel implements AutoCloseable, Scrollable {
  final AuthSetupBundles bundles = new AuthSetupBundles();
  final JTextField name = new JTextField("새 설정 묶음", 24);
  final DefaultListModel<AuthSetupBundles.Bundle> model = new DefaultListModel<>();
  final JList<AuthSetupBundles.Bundle> choices = new JList<>(model) {
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
  };
  final JTextArea details = new JTextArea();
  final JLabel status = new JLabel("역할 자리표시자만 저장 · 실제 계정은 적용 후 연결 · 네트워크 전송 없음");
  final JButton save, apply, read, write, reload, remove;
  private final AuthSetupBundleStore store;
  private boolean persistenceReady = true;
  private final Supplier<AuthSetupBundles.Bundle> capture;
  private final Consumer<AuthSetupBundles.Bundle> applySettings;
  private SwingWorker<?, ?> fileWork;
  private boolean closed;
  BiPredicate<String, List<AuthSetupBundles.Bundle>> review = this::confirm;

  AuthSetupBundlePanel(Component owner, Supplier<AuthSetupBundles.Bundle> capture,
                       Consumer<AuthSetupBundles.Bundle> applySettings) {
    this(owner, capture, applySettings, null);
  }

  AuthSetupBundlePanel(Component owner, Supplier<AuthSetupBundles.Bundle> capture,
                       Consumer<AuthSetupBundles.Bundle> applySettings, AuthSetupBundleStore store) {
    super(new BorderLayout(8, 8)); this.capture = Objects.requireNonNull(capture); this.applySettings = Objects.requireNonNull(applySettings);
    this.store = store;
    setName("wb.auth.setup.bundles"); name.setName("wb.auth.setup.bundle.name"); choices.setName("wb.auth.setup.bundle.list");
    choices.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    choices.setVisibleRowCount(2); choices.setPrototypeCellValue(new AuthSetupBundles.Bundle("설정 이름", "https://example.test", "PATCH", "/api/items/{id}", 1,
        AuthSetupBundles.ROLE_PLACEHOLDERS, new AuthSetupBundles.Settings("", "", "", "", "", "", AuthPolicy.Access.UNSPECIFIED, "", false, List.of())));
    details.setEditable(false); details.setLineWrap(true); details.setWrapStyleWord(true); details.setName("wb.auth.setup.bundle.preview");
    save = action("현재 설정 묶음 등록", this::saveCurrent);
    apply = action("선택 묶음 적용", this::applySelected); apply.setEnabled(false);
    read = action("JSON 불러오기", this::chooseRead);
    write = action("JSON 저장·내보내기", this::chooseWrite);
    reload = action("저장 목록 다시 읽기", this::restore);
    remove = action("선택 묶음 삭제", this::removeSelected); remove.setEnabled(false);
    save.setName("wb.auth.setup.bundle.save"); apply.setName("wb.auth.setup.bundle.apply");
    read.setName("wb.auth.setup.bundle.import"); write.setName("wb.auth.setup.bundle.export");
    reload.setName("wb.auth.setup.bundle.reload"); reload.setVisible(store != null);
    remove.setName("wb.auth.setup.bundle.remove");
    choices.addListSelectionListener(event -> {
      if (event.getValueIsAdjusting()) return;
      var selected = choices.getSelectedValue();
      if (selected == null) { details.setText(""); apply.setEnabled(false); remove.setEnabled(false); return; }
      name.setText(selected.name()); details.setText(AuthSetupBundles.preview(List.of(selected))); details.setCaretPosition(0);
      apply.setEnabled(!closed && fileWork == null && persistenceReady);
      remove.setEnabled(!closed && fileWork == null && persistenceReady);
    });
    JPanel nameRow = new JPanel(new BorderLayout(8, 0));
    name.setMinimumSize(new Dimension(80, 28));
    nameRow.add(new JLabel("묶음 이름"), BorderLayout.WEST); nameRow.add(name); nameRow.add(save, BorderLayout.EAST);
    JPanel actions = new JPanel(new WorkbenchUi.WrapLayout(FlowLayout.LEFT, 8, 0));
    actions.add(apply); actions.add(read); actions.add(write); actions.add(remove); actions.add(reload);
    JLabel hint = new JLabel(store == null ? "프로젝트 저장소 없음 · 재사용하려면 JSON 저장" : "프로젝트별 목록 자동 보관 · 복원 후에도 적용은 직접 선택");
    hint.setToolTipText(store == null
        ? "이 호스트는 프로젝트 저장소를 제공하지 않습니다. 목록은 현재 실행 중 유지되며 JSON으로 저장해 다시 불러올 수 있습니다."
        : "검토한 등록·불러오기 목록을 현재 Burp 프로젝트에 연결된 로컬 폴더에 보관합니다. 같은 프로젝트를 다시 열면 목록만 복원합니다. 계정 연결·활성 비교 설정·쓰기 허용은 자동 복원하지 않습니다. JSON 내보내기는 별도 백업·다른 PC 이동에 사용하세요.");
    JPanel top = new JPanel(new BorderLayout(0, 4));
    top.add(nameRow, BorderLayout.NORTH); top.add(actions); top.add(hint, BorderLayout.SOUTH);
    add(top, BorderLayout.NORTH);
    JScrollPane listScroll = new JScrollPane(choices), previewScroll = new JScrollPane(details);
    listScroll.setPreferredSize(new Dimension(160, 66)); listScroll.setMinimumSize(new Dimension(160, 34));
    previewScroll.setPreferredSize(new Dimension(160, 200)); previewScroll.setMinimumSize(new Dimension(160, 42));
    JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, listScroll, previewScroll);
    split.setResizeWeight(.15); split.setBorder(null); add(split); add(status, BorderLayout.SOUTH);
    status.setToolTipText(status.getText()); status.addPropertyChangeListener("text", event -> status.setToolTipText(status.getText()));
    setPreferredSize(new Dimension(900, 600)); WorkbenchUi.style(this);
    if (store != null) restore();
  }

  @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
  @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
  @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(24, (orientation == SwingConstants.VERTICAL ? visible.height : visible.width) - 24); }
  @Override public boolean getScrollableTracksViewportWidth() { return true; }
  @Override public boolean getScrollableTracksViewportHeight() { return true; }

  private JButton action(String title, Runnable operation) {
    JButton button = new JButton(title); button.addActionListener(event -> {
      try { if (!closed) operation.run(); }
      catch (RuntimeException error) { status.setText(Objects.toString(error.getMessage(), "설정 묶음 작업 실패")); }
    }); return button;
  }

  void saveCurrent() {
    if (closed || fileWork != null) return;
    requireReady();
    var draft = capture.get().withName(name.getText());
    var existing = bundles.get(draft.key()); long expected = existing == null ? 0 : existing.revision();
    var proposal = draft.withRevision(expected + 1);
    if (!review.test("현재 설정 묶음 등록 · r" + proposal.revision(), List.of(proposal)) || closed) return;
    List<AuthSetupBundles.Bundle> before = bundles.snapshot();
    AuthSetupBundles proposed = new AuthSetupBundles(); proposed.merge(before);
    var stored = proposed.save(draft, expected);
    publish(before, proposed.snapshot(), stored.key(), "묶음 등록 r" + stored.revision());
  }

  void applySelected() {
    if (closed || fileWork != null) return;
    requireReady();
    var selected = choices.getSelectedValue();
    if (selected == null) throw new IllegalArgumentException("적용할 설정 묶음을 선택하세요.");
    if (!review.test("선택 묶음 적용 · 현재 계정 지정과 신원/갱신 연결 해제", List.of(selected)) || closed) return;
    if (!selected.equals(bundles.get(selected.key()))) throw new IllegalArgumentException("묶음이 검토 중 변경되었습니다. 다시 선택하세요.");
    applySettings.accept(selected);
    status.setText("설정 묶음 r" + selected.revision() + " 적용 · 실제 계정/권한 자리 대응을 확인하고 준비 화면에서 다시 검토하세요. 요청 전송 없음.");
  }

  void importReviewed(List<AuthSetupBundles.Bundle> imported) {
    if (closed || fileWork != null) return;
    requireReady();
    // Preflight in an independent store; a conflict is shown before approval, without partial changes.
    List<AuthSetupBundles.Bundle> before = bundles.snapshot();
    AuthSetupBundles proposed = new AuthSetupBundles(); proposed.merge(before); proposed.merge(imported);
    if (!review.test("JSON 불러오기 · 아직 현재 비교 설정에 적용하지 않음", imported) || closed) return;
    if (!before.equals(bundles.snapshot())) throw new IllegalArgumentException("검토 중 목록이 변경되었습니다. 다시 검토하세요.");
    publish(before, proposed.snapshot(), imported.isEmpty() ? "" : imported.get(0).key(), imported.size() + "개 묶음 불러옴");
  }

  void removeSelected() {
    if (closed || fileWork != null) return;
    requireReady();
    var selected = choices.getSelectedValue();
    if (selected == null) throw new IllegalArgumentException("삭제할 설정 묶음을 선택하세요.");
    List<AuthSetupBundles.Bundle> before = bundles.snapshot();
    if (!review.test("선택 묶음을 저장 목록에서 삭제 · 현재 적용 설정은 유지", List.of(selected)) || closed) return;
    if (!before.equals(bundles.snapshot())) throw new IllegalArgumentException("검토 중 목록이 변경되었습니다. 다시 검토하세요.");
    publish(before, before.stream().filter(bundle -> !bundle.key().equals(selected.key())).toList(), "", "선택 묶음 삭제");
  }

  private void requireReady() {
    if (!persistenceReady) throw new IllegalArgumentException("저장 목록을 먼저 다시 읽으세요. 읽지 못한 파일을 덮어쓰지 않았습니다.");
  }

  /** Commit the complete reviewed snapshot first; a failed write never partially changes the list. */
  private void publish(List<AuthSetupBundles.Bundle> before, List<AuthSetupBundles.Bundle> next, String key, String message) {
    if (store == null) {
      bundles.replace(next); refresh(key);
      status.setText(message + " · 현재 실행 중 보관 · 재사용하려면 JSON 저장 · 요청 전송 없음.");
      return;
    }
    SwingWorker<Void, Void> job = new SwingWorker<>() {
      protected Void doInBackground() throws Exception { store.commit(before, next); return null; }
      protected void done() {
        if (closed || fileWork != this) return;
        fileWork = null;
        try {
          get(); bundles.replace(next); refresh(key);
          status.setText(message + " · 프로젝트 목록 저장 완료 · 활성 설정 적용·요청 전송 없음.");
        } catch (Exception error) { failed(error); }
        busy(false);
      }
    };
    fileWork = job; busy(true); status.setText("검토한 프로젝트 설정 목록 저장 중"); job.execute();
  }

  void restore() {
    if (closed || store == null || fileWork != null) return;
    String selectedKey = choices.getSelectedValue() == null ? "" : choices.getSelectedValue().key();
    persistenceReady = false;
    SwingWorker<AuthSetupBundleStore.Loaded, Void> job = new SwingWorker<>() {
      protected AuthSetupBundleStore.Loaded doInBackground() throws Exception { return store.load(); }
      protected void done() {
        if (closed || fileWork != this) return;
        fileWork = null;
        try {
          var loaded = get(); bundles.replace(loaded.bundles()); persistenceReady = true; refresh(selectedKey);
          status.setText(loaded.bundles().size() + "개 프로젝트 설정 목록 " + (loaded.recovered() ? "복구본에서 복원" : "복원")
              + " · 활성 설정·계정 연결 변경 없음 · 적용할 묶음을 직접 선택하세요.");
        } catch (Exception error) { failed(error); }
        busy(false);
      }
    };
    fileWork = job; busy(true); status.setText("프로젝트 설정 목록 읽는 중 · 활성 설정 변경 없음"); job.execute();
  }

  private void chooseRead() {
    if (fileWork != null) return;
    JFileChooser picker = picker();
    if (picker.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    Path path = picker.getSelectedFile().toPath();
    SwingWorker<List<AuthSetupBundles.Bundle>, Void> job = new SwingWorker<>() {
      protected List<AuthSetupBundles.Bundle> doInBackground() throws Exception { return AuthSetupBundles.read(path); }
      protected void done() {
        if (closed || fileWork != this) return;
        fileWork = null; busy(false);
        try { importReviewed(get()); }
        catch (Exception error) { failed(error); }
      }
    };
    fileWork = job; busy(true); status.setText("설정 JSON 읽는 중 · 아직 설정에 적용하지 않았습니다."); job.execute();
  }

  private void chooseWrite() {
    if (fileWork != null) return;
    List<AuthSetupBundles.Bundle> snapshot = bundles.snapshot();
    if (snapshot.isEmpty()) throw new IllegalArgumentException("내보낼 설정 묶음을 먼저 등록하세요.");
    if (!review.test("JSON 저장·내보내기 · 저장할 전체 필드 검토", snapshot) || closed) return;
    JFileChooser picker = picker(); picker.setSelectedFile(new java.io.File("workbench-auth-setups.json"));
    if (picker.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
    Path destination = picker.getSelectedFile().toPath();
    if (Files.exists(destination) && JOptionPane.showConfirmDialog(this, "선택한 JSON 파일을 현재 묶음 목록으로 교체합니다.\n" + destination.getFileName(),
        "기존 JSON 파일 교체", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
    SwingWorker<Void, Void> job = new SwingWorker<>() {
      protected Void doInBackground() throws Exception { AuthSetupBundles.write(destination, snapshot); return null; }
      protected void done() {
        if (closed || fileWork != this) return;
        fileWork = null; busy(false);
        try { get(); status.setText(snapshot.size() + "개 묶음 JSON 저장 완료 · " + destination.getFileName()); }
        catch (Exception error) { failed(error); }
      }
    };
    fileWork = job; busy(true); status.setText("검토한 설정 JSON 저장 중"); job.execute();
  }

  private JFileChooser picker() {
    JFileChooser picker = new JFileChooser(); picker.setFileFilter(new FileNameExtensionFilter("Workbench 설정 묶음 JSON", "json")); return picker;
  }
  private void busy(boolean value) {
    save.setEnabled(!closed && !value && persistenceReady); read.setEnabled(!closed && !value && persistenceReady);
    write.setEnabled(!closed && !value); reload.setEnabled(!closed && !value); name.setEnabled(!closed && !value);
    choices.setEnabled(!closed && !value); apply.setEnabled(!closed && !value && persistenceReady && choices.getSelectedValue() != null);
    remove.setEnabled(!closed && !value && persistenceReady && choices.getSelectedValue() != null);
  }
  private void failed(Exception error) {
    Throwable cause = error instanceof java.util.concurrent.ExecutionException && error.getCause() != null ? error.getCause() : error;
    status.setText(Objects.toString(cause.getMessage(), "설정 파일 작업 실패"));
  }
  private boolean confirm(String title, List<AuthSetupBundles.Bundle> values) {
    JTextArea text = new JTextArea(AuthSetupBundles.preview(values)); text.setEditable(false); text.setCaretPosition(0);
    JScrollPane scroll = new JScrollPane(text); scroll.setPreferredSize(new Dimension(850, 530));
    JPanel content = new JPanel(new BorderLayout(4, 4)); content.add(scroll);
    content.add(AuthReadiness.note("확인하면 위에 표시된 설정만 저장/가져오기/적용합니다. Cookie·Authorization·실제 계정은 포함하지 않습니다. 사용자 지정 규칙/기대값에 민감값이 없는지 확인하세요.", 3), BorderLayout.SOUTH);
    return JOptionPane.showConfirmDialog(this, content, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
  }
  void refresh(String key) {
    model.clear(); for (var bundle : bundles.snapshot()) model.addElement(bundle);
    for (int i = 0; i < model.size(); i++) if (model.get(i).key().equals(key)) { choices.setSelectedIndex(i); break; }
  }
  @Override public void close() { closed = true; if (fileWork != null) fileWork.cancel(true); fileWork = null; }
}
