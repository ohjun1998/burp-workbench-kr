package kr.workbench;

import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.*;

/** Four local work stages over the retained engine pages. Navigation never sends a request. */
final class SimpleNavigation extends JPanel {
  private static final String[][] TOOLS = {
      {"traffic"}, {"mapper", "js", "response.recon", "environment"}, {"auth", "payload"}, {"bounty"}
  };
  private static final String[] STAGES = {"1  수집", "2  분석", "3  검증", "4  증거"};
  private static final String[] HINTS = {
      "SCOPE 등록 → 수집 켜기 → 요청 선택",
      "선택한 원본을 유지하며 필요한 분석 도구를 여세요.",
      "계정·검사 조건을 확인한 뒤 직접 실행하세요.",
      "원본·결과 추가 → 가림 확인 → ZIP 저장"
  };
  private static final String[] NEXT = {"분석으로 →", "검증으로 →", "증거 정리로 →", "수집으로 돌아가기"};
  private final JTabbedPane pages;
  private final Runnable settings, help, logs;
  private final Map<Integer, JToggleButton> stages = new LinkedHashMap<>();
  private final Map<String, JToggleButton> tools = new LinkedHashMap<>();
  private final String[] remembered = {"traffic", "mapper", "auth", "bounty"};
  private final JPanel toolRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
  private final JLabel hint = new JLabel();
  private final JButton next = new JButton();
  private int stage;

  SimpleNavigation(JTabbedPane pages, Runnable settings, Runnable help, Runnable logs) {
    super(new BorderLayout(0, 8));
    this.pages = pages;
    this.settings = settings; this.help = help; this.logs = logs;
    setName("wb.simple.navigation");
    JPanel header = new JPanel(new BorderLayout(8, 8));
    header.setName("wb.simple.header");
    header.setBorder(BorderFactory.createEmptyBorder(12, 16, 0, 16));
    JPanel titleRow = new JPanel(new BorderLayout(8, 0));
    JLabel title = new JLabel("Workbench Simple");
    title.setName("wb.simple.brand");
    title.setFont(WorkbenchUi.font().deriveFont(Font.BOLD, 17f));
    title.setToolTipText("Simple " + BuildInfo.VERSION + " · 기존 Workbench와 설정을 별도로 보관합니다.");
    titleRow.add(title, BorderLayout.WEST);
    JButton options = new JButton("도움말·설정");
    options.setName("wb.simple.options");
    options.addActionListener(e -> {
      JPopupMenu menu = optionsMenu();
      menu.show(options, 0, options.getHeight());
    });
    titleRow.add(options, BorderLayout.EAST);
    header.add(titleRow, BorderLayout.NORTH);
    JPanel stepRow = new JPanel(new GridLayout(1, 4, 8, 0));
    ButtonGroup group = new ButtonGroup();
    for (int index = 0; index < STAGES.length; index++) {
      final int target = index;
      JToggleButton button = new JToggleButton(STAGES[index]);
      button.setName("wb.simple.step." + (index + 1));
      button.getAccessibleContext().setAccessibleDescription(HINTS[index]);
      button.addActionListener(e -> openStage(target));
      group.add(button); stages.put(index, button); stepRow.add(button);
      final String action = "simple.stage." + index;
      getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("alt " + (index + 1)), action);
      getActionMap().put(action, new AbstractAction() {
        public void actionPerformed(java.awt.event.ActionEvent e) { openStage(target); }
      });
    }
    JPanel workRow = new JPanel(new BorderLayout(8, 6));
    workRow.add(stepRow, BorderLayout.NORTH);
    toolRow.setName("wb.simple.tools");
    JPanel detail = new JPanel(new BorderLayout(8, 0));
    hint.setName("wb.simple.hint");
    hint.setFont(WorkbenchUi.font().deriveFont(12f));
    next.setName("wb.simple.next");
    next.addActionListener(e -> openStage((stage + 1) % STAGES.length));
    detail.add(hint, BorderLayout.CENTER);
    detail.add(next, BorderLayout.EAST);
    JPanel below = new JPanel(new BorderLayout(0, 6));
    below.add(toolRow, BorderLayout.NORTH); below.add(detail, BorderLayout.CENTER);
    workRow.add(below, BorderLayout.CENTER);
    header.add(workRow, BorderLayout.CENTER);
    add(header, BorderLayout.NORTH);
    // Pages retain their original identity and indices for engine and evidence callbacks.
    add(pages, BorderLayout.CENTER);
    pages.addChangeListener(e -> refreshSelection());
    refreshSelection();
  }

  JPopupMenu optionsMenu() {
    JPopupMenu menu = new JPopupMenu();
    int index = 0;
    for (Object[] action : new Object[][] {{"현재 도구 안내", help}, {"화면 설정", settings}, {"오류·수집 기록", logs}}) {
      JMenuItem item = new JMenuItem((String) action[0]);
      item.setName("wb.simple.options." + index++);
      item.addActionListener(event -> ((Runnable) action[1]).run());
      menu.add(item);
    }
    WorkbenchUi.style(menu);
    return menu;
  }

  void openStage(int target) {
    String id = remembered[target];
    if (indexOf(id) < 0) id = TOOLS[target][0];
    select(id);
  }

  private void select(String id) {
    int index = indexOf(id);
    if (index >= 0) pages.setSelectedIndex(index);
  }

  private int indexOf(String id) {
    for (int i = 0; i < pages.getTabCount(); i++)
      if (pages.getComponentAt(i) instanceof JComponent component
          && id.equals(component.getClientProperty("wb.feature.id"))) return i;
    return -1;
  }

  void refreshSelection() {
    String selected = pages.getSelectedComponent() instanceof JComponent component
        ? String.valueOf(component.getClientProperty("wb.feature.id")) : "traffic";
    for (int i = 0; i < TOOLS.length; i++) for (String id : TOOLS[i])
      if (id.equals(selected)) { stage = i; remembered[i] = id; }
    stages.get(stage).setSelected(true);
    hint.setText(HINTS[stage]); next.setText(NEXT[stage]);
    toolRow.removeAll(); tools.clear();
    ButtonGroup group = new ButtonGroup();
    if (TOOLS[stage].length > 1) for (String id : TOOLS[stage]) {
      if (indexOf(id) < 0) continue;
      JToggleButton button = new JToggleButton(FeatureGuide.title(id));
      button.setName("wb.simple.tool." + id);
      button.setSelected(id.equals(selected));
      button.addActionListener(e -> select(id));
      group.add(button); tools.put(id, button); toolRow.add(button);
      WorkbenchUi.style(button);
    }
    toolRow.setVisible(toolRow.getComponentCount() > 0);
    revalidate(); repaint();
  }
}
