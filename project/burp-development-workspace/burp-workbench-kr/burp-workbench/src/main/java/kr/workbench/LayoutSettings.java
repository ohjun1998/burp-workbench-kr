package kr.workbench;

import burp.api.montoya.MontoyaApi;
import java.awt.*;
import javax.swing.*;

/** Project-specific display and workspace preferences, edited from the separate Settings window. */
public final class LayoutSettings extends JPanel implements AutoCloseable {
  final Component page;
  final MontoyaApi api;
  final String key;
  final JComboBox<String> choice, scale = new JComboBox<>(new String[] {"100%", "125%", "150%"});
  final JComboBox<String> density = new JComboBox<>(new String[] {"여유 있게", "촘촘하게"});
  final JTextArea hint = prose(2), status = prose(3);
  final JButton reset = new JButton("배치 초기화"), resetAll = new JButton("화면 설정 초기화");
  private WorkspaceAppearance appearance;
  private boolean syncing;

  public LayoutSettings(MontoyaApi api, Component page) {
    super(); setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); setName("wb.settings.layout.common");
    this.api = api; this.page = page; key = BuildInfo.storageKey("wb.layout.horizontal");
    choice = new JComboBox<>(new String[] {"상/하 · 목록 왼쪽", "좌/우 · 목록 위"}); choice.setName("wb.layout.choice");
    scale.setName("wb.settings.appearance.scale"); density.setName("wb.settings.appearance.density");
    status.setName("wb.settings.appearance.status"); resetAll.setName("wb.settings.appearance.reset");
    JLabel scaleLabel = new JLabel("글자 크기"), densityLabel = new JLabel("목록 간격"), layoutLabel = new JLabel("요청 / 응답 배치");
    scaleLabel.setLabelFor(scale); densityLabel.setLabelFor(density); layoutLabel.setLabelFor(choice);
    scale.getAccessibleContext().setAccessibleName("글자 크기"); density.getAccessibleContext().setAccessibleName("목록 간격");
    boolean horizontal = true;
    try {
      Boolean migrated = api.persistence().preferences().getBoolean(BuildInfo.storageKey("wb.layout.reference.v1"));
      Boolean saved = api.persistence().preferences().getBoolean(key);
      if (Boolean.TRUE.equals(migrated) && saved != null) horizontal = saved;
      api.persistence().preferences().setBoolean(BuildInfo.storageKey("wb.layout.reference.v1"), true);
    } catch (RuntimeException ignored) { }
    choice.setSelectedIndex(horizontal ? 1 : 0);
    add(WorkbenchUi.row(scaleLabel, scale, densityLabel, density));
    add(WorkbenchUi.row(layoutLabel, choice)); add(hint);
    add(WorkbenchUi.row(reset, new JLabel("열 순서·너비와 현재 배치의 분할 비율 복원")));
    add(WorkbenchUi.row(resetAll, new JLabel("현재 프로젝트의 글자·간격·열·분할 설정 초기화")));
    add(status);
    appearance = WorkspaceAppearance.forApi(api, page, horizontal, this::sync);
    choice.addActionListener(event -> apply()); scale.addActionListener(event -> apply()); density.addActionListener(event -> apply());
    reset.addActionListener(event -> appearance.resetSurfaces());
    resetAll.addActionListener(event -> appearance.resetAll());
    sync(); WorkbenchUi.style(this); applyLegacyOrientation();
  }
  private static JTextArea prose(int rows) {
    JTextArea area = new JTextArea(rows, 28); area.setEditable(false); area.setOpaque(false); area.setLineWrap(true); area.setWrapStyleWord(true);
    area.setAlignmentX(Component.LEFT_ALIGNMENT); area.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));
    area.putClientProperty("wb.prose", true); return area;
  }
  void apply() {
    if (syncing) return;
    if (appearance.state() != null) appearance.display(new int[] {100,125,150}[scale.getSelectedIndex()], density.getSelectedIndex() == 1, choice.getSelectedIndex() == 1);
    else WorkbenchUi.packetLayout(page, choice.getSelectedIndex() == 1);
    applyLegacyOrientation(); WorkbenchUi.style(this);
  }
  private void applyLegacyOrientation() {
    boolean horizontal = choice.getSelectedIndex() == 1;
    if (page instanceof JComponent component) component.putClientProperty("wb.packet.horizontal", horizontal);
    // Keep the legacy isolated preference for compatibility; project metadata is authoritative when present.
    try { api.persistence().preferences().setBoolean(key, horizontal); } catch (RuntimeException ignored) { }
    updateHint();
  }
  private void updateHint() {
    boolean horizontal = choice.getSelectedIndex() == 1;
    hint.setText(page instanceof JComponent component && Boolean.TRUE.equals(component.getClientProperty("wb.data.tools"))
        ? "변환과 JWT에 같은 배치를 적용합니다."
        : horizontal ? "목록은 위에, 요청과 응답은 아래에 나란히 표시합니다." : "목록은 왼쪽에, 요청과 응답은 오른쪽에 위아래로 표시합니다.");
  }
  private void sync() {
    if (appearance == null) return;
    syncing = true;
    try {
      WorkspaceAppearance.State state = appearance.state();
      if (state != null) {
        scale.setSelectedIndex(state.scale() == 150 ? 2 : state.scale() == 125 ? 1 : 0);
        density.setSelectedIndex(state.compact() ? 1 : 0); choice.setSelectedIndex(state.horizontal() ? 1 : 0);
      }
      String problem = appearance.problem();
      status.setText(problem.isEmpty() ? "현재 프로젝트에 자동 저장 · 열을 끌거나 분할선을 옮기면 위치도 복원합니다." : problem);
      scale.setEnabled(state != null); density.setEnabled(state != null); reset.setEnabled(state != null); resetAll.setEnabled(state != null);
      updateHint();
    } finally { syncing = false; }
  }
  public void refreshProject() { if (appearance != null) appearance.refreshProject(); }
  public void close() { if (appearance != null) appearance.close(); }
}
