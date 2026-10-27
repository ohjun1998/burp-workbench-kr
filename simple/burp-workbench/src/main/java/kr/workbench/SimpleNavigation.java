package kr.workbench;

import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.*;

/** Direct access to the eight retained extensions, without grouping their entry points. */
final class SimpleNavigation extends JPanel {
  private static final String[] IDS = {
      "traffic", "mapper", "auth", "js", "environment", "response.recon", "payload", "bounty"
  };
  private static final String[] LABELS = {
      "요청·응답", "기능 매핑", "계정 비교", "API 찾기", "서버·경로", "응답 분석", "입력값 검사", "재현·증거"
  };
  private static final Map<String, String> HINTS = Map.of(
      "traffic", "SCOPE 등록 → 수집 켜기 → 요청 선택 → 필요한 도구로 연결",
      "mapper", "기능·실행 선택 → 패킷과 분류 근거 확인 → 프로젝트 저장",
      "auth", "인증 후보를 계정에 지정 → 준비 검토 → 직접 비교 → 근거 확인",
      "js", "응답 분석 → API와 원본 위치 확인 → Repeater에 요청 준비",
      "environment", "대상·후보 선택 → 범위와 실행 한도 확인 → 직접 GET → 결과 확인",
      "response.recon", "응답 관찰 → 원본과 근거 확인 → 필요한 관찰을 증거로 연결",
      "payload", "요청 연결 → 입력 위치 지정 → 조건 확인 → 검사 실행 → 결과·증거",
      "bounty", "요청을 순서대로 재현하거나, 원본·결과를 모아 가림 확인 후 ZIP 저장"
  );
  private final JTabbedPane pages;
  private final Runnable settings, help, logs;
  private final Map<String, JToggleButton> tools = new LinkedHashMap<>();
  private final JPanel toolRow = new JPanel(new GridLayout(1, IDS.length, 6, 0));
  private final JLabel hint = new JLabel();

  SimpleNavigation(JTabbedPane pages, Runnable settings, Runnable help, Runnable logs) {
    this(pages,settings,help,logs,null);
  }
  SimpleNavigation(JTabbedPane pages, Runnable settings, Runnable help, Runnable logs, Runnable inspection) {
    super(new BorderLayout(0, 8));
    this.pages = pages; this.settings = settings; this.help = help; this.logs = logs;
    setName("wb.simple.navigation");
    JPanel header = new JPanel(new BorderLayout(8, 8));
    header.setName("wb.simple.header");
    header.setBorder(BorderFactory.createEmptyBorder(10, 16, 0, 16));
    JPanel titleRow = new JPanel(new BorderLayout(8, 0));
    JLabel title = new JLabel("Workbench Simple · 8개 도구");
    title.setName("wb.simple.brand");
    title.setFont(WorkbenchUi.font().deriveFont(Font.BOLD, 17f));
    title.setToolTipText("Simple " + BuildInfo.VERSION + " · 기존 Workbench와 별도 설정을 사용합니다.");
    titleRow.add(title, BorderLayout.WEST);
    JButton options = new JButton("도움말·설정");
    options.setName("wb.simple.options");
    options.addActionListener(e -> optionsMenu().show(options, 0, options.getHeight()));
    JPanel mainActions=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
    if(inspection!=null){JButton inspect=new JButton("Scope 통합 점검");inspect.setName("wb.scope.inspection.open");inspect.addActionListener(e->inspection.run());WorkbenchUi.primary(inspect);mainActions.add(inspect);}
    mainActions.add(options);titleRow.add(mainActions, BorderLayout.EAST);
    header.add(titleRow, BorderLayout.NORTH);
    toolRow.setName("wb.simple.tools");
    ButtonGroup group = new ButtonGroup();
    for (int i = 0; i < IDS.length; i++) {
      String id = IDS[i];
      JToggleButton button = new JToggleButton(LABELS[i]);
      button.setName("wb.simple.tool." + id);
      button.setToolTipText(FeatureGuide.title(id) + " · Alt+" + (i + 1));
      button.getAccessibleContext().setAccessibleName(FeatureGuide.title(id));
      button.getAccessibleContext().setAccessibleDescription(HINTS.get(id));
      button.addActionListener(e -> select(id));
      group.add(button); tools.put(id, button); toolRow.add(button);
      String action = "simple.tool." + id;
      getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("alt " + (i + 1)), action);
      getActionMap().put(action, new AbstractAction() {
        public void actionPerformed(java.awt.event.ActionEvent event) { select(id); }
      });
    }
    hint.setName("wb.simple.hint");
    hint.setFont(WorkbenchUi.font().deriveFont(12f));
    JPanel navigation = new JPanel(new BorderLayout(0, 7));
    navigation.add(toolRow, BorderLayout.NORTH); navigation.add(hint, BorderLayout.SOUTH);
    header.add(navigation, BorderLayout.CENTER);
    add(header, BorderLayout.NORTH);
    // Page identities and indices stay stable for all engine and evidence callbacks.
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
      item.addActionListener(e -> ((Runnable) action[1]).run()); menu.add(item);
    }
    WorkbenchUi.style(menu); return menu;
  }

  private int indexOf(String id) {
    for (int i = 0; i < pages.getTabCount(); i++)
      if (pages.getComponentAt(i) instanceof JComponent component
          && id.equals(component.getClientProperty("wb.feature.id"))) return i;
    return -1;
  }

  private void select(String id) {
    int index = indexOf(id);
    if (index >= 0) pages.setSelectedIndex(index);
  }

  void refreshSelection() {
    String selected = pages.getSelectedComponent() instanceof JComponent component
        ? String.valueOf(component.getClientProperty("wb.feature.id")) : "traffic";
    for (var entry : tools.entrySet()) {
      entry.getValue().setEnabled(indexOf(entry.getKey()) >= 0);
      entry.getValue().setSelected(entry.getKey().equals(selected));
    }
    hint.setText(HINTS.getOrDefault(selected, "필요한 도구를 선택하세요. 화면 이동은 대상 요청을 보내지 않습니다."));
    revalidate(); repaint();
  }
}
