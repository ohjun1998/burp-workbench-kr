package kr.workbench;

import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import javax.swing.event.*;

/** Keyboard and mouse feature search; navigation only, with no execution actions. */
final class QuickNavigationPanel extends JPanel {
  final JTextField query = new JTextField();
  final DefaultListModel<NavigationTargets.Target> model = new DefaultListModel<>();
  final JList<NavigationTargets.Target> choices = new JList<>(model);
  final JLabel count = new JLabel();
  private final Supplier<List<String>> recent;
  private final Consumer<String> navigate;
  private final Runnable close;

  QuickNavigationPanel(Supplier<List<String>> recent, Consumer<String> navigate, Runnable close) {
    super(new BorderLayout(6, 8)); this.recent = recent; this.navigate = navigate; this.close = close;
    setName("wb.navigation.quick.panel");
    setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    setPreferredSize(new Dimension(560, 340));
    query.setName("wb.navigation.quick.query");
    query.getAccessibleContext().setAccessibleName("기능 이름 또는 하려는 일 검색");
    query.setToolTipText("예: 권한 비교, 증거 저장, JWT · 기능 이름과 설명만 검색");
    choices.setName("wb.navigation.quick.results");
    choices.getAccessibleContext().setAccessibleName("찾은 기능과 최근 화면");
    choices.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    choices.setVisibleRowCount(8);
    choices.setFixedCellHeight(45);
    choices.setCellRenderer(new DefaultListCellRenderer() {
      @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
          boolean selected, boolean focused) {
        JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
        NavigationTargets.Target target = (NavigationTargets.Target) value;
        boolean wasRecent = query.getText().isBlank() && recent.get().contains(target.id());
        label.setText("<html><b>" + (wasRecent ? "최근 · " : "") + html(target.title())
            + "</b><br>" + html(target.detail()) + "</html>");
        label.setToolTipText(target.detail());
        label.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        return label;
      }
    });
    JPanel top = new JPanel(new BorderLayout(0, 4));
    top.add(new JLabel("기능 이름이나 하려는 일을 입력하세요."), BorderLayout.NORTH);
    top.add(query); top.add(count, BorderLayout.SOUTH);
    add(top, BorderLayout.NORTH); add(new JScrollPane(choices));
    JButton open = new JButton("선택 화면 열기"), dismiss = new JButton("닫기");
    open.setName("wb.navigation.quick.open"); dismiss.setName("wb.navigation.quick.close");
    open.addActionListener(e -> openSelected()); dismiss.addActionListener(e -> close.run());
    JPanel bottom = new JPanel(new BorderLayout(0, 4));
    bottom.add(new JLabel("Enter 열기 · Esc 닫기 · 화면 이동만 수행"), BorderLayout.NORTH);
    bottom.add(WorkbenchUi.row(dismiss, open), BorderLayout.SOUTH); add(bottom, BorderLayout.SOUTH);
    query.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent event) { refresh(); }
      public void removeUpdate(DocumentEvent event) { refresh(); }
      public void changedUpdate(DocumentEvent event) { refresh(); }
    });
    query.addActionListener(e -> openSelected());
    choices.addMouseListener(new MouseAdapter() {
      @Override public void mouseClicked(MouseEvent event) {
        if (event.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(event)) return;
        int index = choices.locationToIndex(event.getPoint());
        if (index >= 0 && choices.getCellBounds(index, index).contains(event.getPoint())) openSelected();
      }
    });
    bind(choices, "ENTER", "open", this::openSelected);
    bind(this, "ESCAPE", "dismiss", close);
    bind(query, "DOWN", "nextResult", () -> move(1));
    bind(query, "UP", "previousResult", () -> move(-1));
    refresh(); WorkbenchUi.style(this);
  }
  private static void bind(JComponent component, String key, String name, Runnable action) {
    component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), name);
    component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name);
    component.getActionMap().put(name, new AbstractAction() {
      public void actionPerformed(ActionEvent event) { action.run(); }
    });
  }
  private static String html(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
  private void move(int direction) {
    if (model.isEmpty()) return;
    int index = Math.floorMod(choices.getSelectedIndex() + direction, model.size());
    choices.setSelectedIndex(index); choices.ensureIndexIsVisible(index);
  }
  void refresh() {
    String selected = choices.getSelectedValue() == null ? "" : choices.getSelectedValue().id();
    List<NavigationTargets.Target> found = NavigationTargets.search(query.getText(), recent.get());
    model.clear(); for (NavigationTargets.Target target : found) model.addElement(target);
    int index = -1;
    for (int i = 0; i < model.size(); i++) if (model.get(i).id().equals(selected)) index = i;
    choices.setSelectedIndex(index >= 0 ? index : model.isEmpty() ? -1 : 0);
    count.setText(query.getText().length() > 256 ? "기능 검색은 256자까지 입력하세요."
        : found.isEmpty() ? "일치하는 기능이 없습니다. 다른 이름이나 목적을 입력하세요."
        : query.getText().isBlank() ? "최근 화면을 먼저 표시합니다. 기능 " + found.size() + "개"
        : "찾은 기능 " + found.size() + "개");
  }
  void reset() { query.setText(""); refresh(); }
  void openSelected() {
    NavigationTargets.Target target = choices.getSelectedValue();
    if (target == null) return;
    navigate.accept(target.id()); close.run();
  }
}
