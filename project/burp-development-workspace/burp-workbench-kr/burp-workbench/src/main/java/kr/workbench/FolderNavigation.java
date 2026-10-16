package kr.workbench;

import burp.api.montoya.MontoyaApi;
import java.awt.*;
import java.awt.event.*;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.*;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import javax.swing.tree.*;

/** Folder navigation over the existing page instances and their shared selection model. */
final class FolderNavigation extends JPanel {
  private static final int DEFAULT_WIDTH = 220;
  private static final int MIN_WIDTH = 160;
  private static final int MAX_WIDTH = 420;
  private final MontoyaApi api;
  private final JTabbedPane pages;
  private final Map<String, DefaultMutableTreeNode> tools = new LinkedHashMap<>();
  private final JTree tree;
  private final JScrollPane sidebar;
  private final JSplitPane split;
  private final JButton toggle = new JButton();
  private final JLabel current = new JLabel();
  private int menuWidth = DEFAULT_WIDTH;
  private boolean hidden;
  private boolean adjusting;
  private boolean synchronizing;

  /** Keep the established page API for context actions, help and engine integrations, without tabs. */
  static class Pages extends JTabbedPane {
    @Override
    public void updateUI() {
      setUI(new BasicTabbedPaneUI() {
        @Override protected int calculateTabAreaHeight(int placement, int runs, int maximum) { return 0; }
        @Override protected int calculateTabAreaWidth(int placement, int runs, int maximum) { return 0; }
        @Override protected Insets getContentBorderInsets(int placement) { return new Insets(0, 0, 0, 0); }
        @Override protected void paintTabArea(Graphics graphics, int placement, int selected) {}
        @Override protected void paintContentBorder(Graphics graphics, int placement, int selected) {}
      });
    }

    @Override
    public void setTabLayoutPolicy(int policy) {
      // There is no tab strip to scroll; avoid invisible tab-scroll buttons taking focus.
      super.setTabLayoutPolicy(WRAP_TAB_LAYOUT);
    }
  }

  FolderNavigation(MontoyaApi api, JTabbedPane pages, Runnable openSettings) {
    super(new BorderLayout(0, 6));
    this.api = api;
    this.pages = pages;
    setName("wb.navigation");
    pages.setName("wb.navigation.pages");
    pages.setMinimumSize(new Dimension(360, 100));
    try {
      String width = read("width");
      if (width != null) menuWidth = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, Integer.parseInt(width)));
    } catch (NumberFormatException ignored) {}
    hidden = Boolean.parseBoolean(read("hidden"));

    DefaultMutableTreeNode root = new DefaultMutableTreeNode("도구");
    folder(root, "트래픽", "traffic");
    folder(root, "탐색·정보 수집", "js", "environment", "passive");
    folder(root, "보안 분석·검증", "auth", "response.recon", "payload");
    folder(root, "데이터 도구", "data");
    folder(root, "점검 관리·증거", "activity", "bounty");
    tree = new JTree(root);
    tree.setName("wb.navigation.tree");
    tree.setRootVisible(false);
    tree.setShowsRootHandles(true);
    tree.setRowHeight(32);
    tree.setToggleClickCount(1);
    tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    tree.getAccessibleContext().setAccessibleName("기능 폴더 목록");
    tree.setToolTipText("폴더를 펼쳐 기능 선택 · 방향키로 이동 · 선택만으로 검사를 실행하지 않습니다.");
    tree.setCellRenderer(new DefaultTreeCellRenderer() {
      @Override
      public Component getTreeCellRendererComponent(JTree owner, Object value, boolean selected,
          boolean expanded, boolean leaf, int row, boolean focus) {
        super.getTreeCellRendererComponent(owner, value, selected, expanded, leaf, row, focus);
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
        setFont(WorkbenchUi.font().deriveFont(node.getUserObject() instanceof Tool ? Font.PLAIN : Font.BOLD));
        setBackgroundNonSelectionColor(WorkbenchUi.surface());
        setBackgroundSelectionColor(WorkbenchUi.selected());
        setTextNonSelectionColor(WorkbenchUi.ink());
        setTextSelectionColor(WorkbenchUi.ink());
        setBorderSelectionColor(WorkbenchUi.accent());
        if (node.getUserObject() instanceof Tool tool) setEnabled(indexOf(tool.id()) >= 0);
        else setEnabled(true);
        return this;
      }
    });
    for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
    tree.addTreeSelectionListener(event -> {
      if (synchronizing) return;
      Object chosen = tree.getLastSelectedPathComponent();
      if (chosen instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof Tool tool) {
        int index = indexOf(tool.id());
        if (index >= 0) pages.setSelectedIndex(index);
      }
    });
    sidebar = new JScrollPane(tree);
    sidebar.setName("wb.navigation.sidebar");
    sidebar.setMinimumSize(new Dimension(MIN_WIDTH, 80));
    sidebar.setPreferredSize(new Dimension(menuWidth, 400));
    split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, pages);
    split.setName("wb.navigation.split");
    split.setContinuousLayout(true);
    split.setResizeWeight(0);
    split.setBorder(null);
    split.setDividerSize(7);
    split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> {
      if (adjusting || hidden || split.getWidth() <= 0) return;
      menuWidth = constrained(split.getDividerLocation());
      write("width", Integer.toString(menuWidth));
      constrainDivider();
    });
    split.addComponentListener(new ComponentAdapter() {
      @Override public void componentResized(ComponentEvent event) { constrainDivider(); }
    });
    toggle.setName("wb.navigation.toggle");
    toggle.addActionListener(event -> setMenuHidden(!hidden));
    current.setName("wb.navigation.current");
    current.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
    JButton settings = new JButton("설정");
    settings.setName("wb.navigation.settings");
    settings.addActionListener(event -> openSettings.run());
    JPanel toolbar = new JPanel(new BorderLayout(8, 0));
    toolbar.setBorder(BorderFactory.createEmptyBorder(4, 6, 0, 6));
    toolbar.add(toggle, BorderLayout.WEST);
    toolbar.add(current, BorderLayout.CENTER);
    toolbar.add(settings, BorderLayout.EAST);
    add(toolbar, BorderLayout.NORTH);
    add(split, BorderLayout.CENTER);
    getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
        KeyStroke.getKeyStroke("control shift M"), "toggleMenu");
    getActionMap().put("toggleMenu", new AbstractAction() {
      public void actionPerformed(ActionEvent event) { setMenuHidden(!hidden); }
    });
    pages.addChangeListener(event -> refreshSelection());
    refreshSelection();
  }

  private record Tool(String id) {
    @Override public String toString() { return FeatureGuide.title(id); }
  }

  private void folder(DefaultMutableTreeNode root, String name, String... ids) {
    DefaultMutableTreeNode group = new DefaultMutableTreeNode(name);
    root.add(group);
    for (String id : ids) {
      DefaultMutableTreeNode tool = new DefaultMutableTreeNode(new Tool(id), false);
      group.add(tool);
      tools.put(id, tool);
    }
  }

  private int indexOf(String id) {
    for (int i = 0; i < pages.getTabCount(); i++)
      if (pages.getComponentAt(i) instanceof JComponent page
          && id.equals(page.getClientProperty("wb.feature.id"))) return i;
    return -1;
  }

  void refreshSelection() {
    Component selected = pages.getSelectedComponent();
    if (!(selected instanceof JComponent page)) return;
    DefaultMutableTreeNode node = tools.get(page.getClientProperty("wb.feature.id"));
    if (node == null) return;
    TreePath path = new TreePath(node.getPath());
    synchronizing = true;
    try {
      tree.setSelectionPath(path);
      tree.scrollPathToVisible(path);
      current.setText(node.getParent() + " / " + node);
      current.setToolTipText(current.getText());
    } finally { synchronizing = false; }
    tree.repaint();
  }

  void restoreLayout() {
    tree.setBackground(WorkbenchUi.surface());
    sidebar.setVisible(!hidden);
    split.setDividerSize(hidden ? 0 : 7);
    toggle.setText(hidden ? "메뉴 보기" : "메뉴 숨기기");
    toggle.setToolTipText("왼쪽 폴더 메뉴 " + (hidden ? "보기" : "숨기기") + " · Ctrl+Shift+M");
    toggle.getAccessibleContext().setAccessibleName(toggle.getText());
    constrainDivider();
  }

  private void setMenuHidden(boolean value) {
    hidden = value;
    restoreLayout();
    write("hidden", Boolean.toString(hidden));
    revalidate();
    repaint();
  }

  private int constrained(int width) {
    int maximum = split.getWidth() > 0 ? Math.min(MAX_WIDTH, split.getWidth() - 367) : MAX_WIDTH;
    return Math.max(MIN_WIDTH, Math.min(Math.max(MIN_WIDTH, maximum), width));
  }

  private void constrainDivider() {
    adjusting = true;
    try { split.setDividerLocation(hidden ? 0 : constrained(menuWidth)); }
    finally { adjusting = false; }
  }

  private String read(String suffix) {
    try { return api.persistence().preferences().getString("wb.navigation." + suffix); }
    catch (Exception ignored) { return null; }
  }

  private void write(String suffix, String value) {
    try { api.persistence().preferences().setString("wb.navigation." + suffix, value); }
    catch (Exception ignored) { /* Layout remains usable if preferences are unavailable. */ }
  }
}
