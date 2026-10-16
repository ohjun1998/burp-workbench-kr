package kr.workbench;

import burp.api.montoya.MontoyaApi;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.*;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import javax.swing.plaf.basic.BasicTreeUI;
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
  private final TreeCellRenderer renderer = new NavigationRenderer();
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
    this(api, pages, openSettings, null, null);
  }

  FolderNavigation(MontoyaApi api, JTabbedPane pages, Runnable openSettings, JButton help, JButton logs) {
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
    tree = new NavigationTree(root);
    tree.setName("wb.navigation.tree");
    tree.setRootVisible(false);
    tree.setShowsRootHandles(true);
    tree.setRowHeight(40);
    tree.setToggleClickCount(1);
    tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    tree.getAccessibleContext().setAccessibleName("기능 폴더 목록");
    tree.setToolTipText("폴더를 펼쳐 기능 선택 · 방향키로 이동 · 선택만으로 검사를 실행하지 않습니다.");
    tree.setCellRenderer(renderer);
    /* Renderer labels stay transparent so the selected background spans the whole row. */
    for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
    tree.addTreeSelectionListener(event -> {
      if (synchronizing) return;
      Object chosen = tree.getLastSelectedPathComponent();
      if (chosen instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof Tool tool) {
        int index = indexOf(tool.id());
        if (index >= 0) pages.setSelectedIndex(index);
      }
      tree.repaint();
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
    JPanel actions = new JPanel(new GridLayout(1, 0, 8, 0));
    actions.setName("wb.navigation.actions");
    if (help != null) actions.add(help);
    actions.add(settings);
    if (logs != null) actions.add(logs);
    toolbar.add(actions, BorderLayout.EAST);
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

  private boolean active(Object value) {
    if (!(value instanceof DefaultMutableTreeNode node)
        || !(node.getUserObject() instanceof Tool tool)) return false;
    int index = indexOf(tool.id());
    return index >= 0 && index == pages.getSelectedIndex();
  }

  /** Local UI delegate and vector icons avoid OS-dependent folders and tree guide lines. */
  private final class NavigationTree extends JTree {
    private int hovered = -1;

    NavigationTree(TreeNode root) {
      super(root);
      setOpaque(false);
      setBorder(BorderFactory.createEmptyBorder(6, 0, 8, 0));
      addMouseMotionListener(new MouseMotionAdapter() {
        @Override public void mouseMoved(MouseEvent event) {
          int row = rowAt(event);
          if (hovered != row) { hovered = row; repaint(); }
        }
      });
      addMouseListener(new MouseAdapter() {
        @Override public void mouseExited(MouseEvent event) { hovered = -1; repaint(); }
      });
      addFocusListener(new FocusAdapter() {
        @Override public void focusGained(FocusEvent event) { repaint(); }
        @Override public void focusLost(FocusEvent event) { repaint(); }
      });
    }

    @Override public void updateUI() {
      setUI(new BasicTreeUI() {
        @Override protected void installDefaults() {
          super.installDefaults();
          setLeftChildIndent(10);
          setRightChildIndent(12);
          setExpandedIcon(new NavigationIcon(IconKind.DOWN));
          setCollapsedIcon(new NavigationIcon(IconKind.RIGHT));
        }
        @Override protected void paintHorizontalLine(Graphics g, JComponent c, int y, int left, int right) {}
        @Override protected void paintVerticalLine(Graphics g, JComponent c, int x, int top, int bottom) {}
      });
    }

    private int rowAt(MouseEvent event) {
      int row = getClosestRowForLocation(event.getX(), event.getY());
      Rectangle bounds = row < 0 ? null : getRowBounds(row);
      return bounds != null && event.getY() >= bounds.y && event.getY() < bounds.y + bounds.height ? row : -1;
    }

    @Override protected void processMouseEvent(MouseEvent event) {
      // Make the highlighted blank part of a row clickable too; leave chevrons to BasicTreeUI.
      int row = rowAt(event);
      Rectangle bounds = row < 0 ? null : getRowBounds(row);
      if (event.getID() == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(event)
          && bounds != null && event.getX() >= bounds.x + bounds.width) {
        requestFocusInWindow();
        TreePath path = getPathForRow(row);
        setSelectionPath(path);
        if (!getModel().isLeaf(path.getLastPathComponent())) {
          if (isExpanded(path)) collapsePath(path); else expandPath(path);
        }
        event.consume();
        return;
      }
      super.processMouseEvent(event);
    }

    @Override protected void paintComponent(Graphics graphics) {
      Graphics2D g = (Graphics2D) graphics.create();
      g.setColor(WorkbenchUi.surface());
      g.fillRect(0, 0, getWidth(), getHeight());
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      Rectangle clip = g.getClipBounds();
      for (int row = 0; row < getRowCount(); row++) {
        Rectangle bounds = getRowBounds(row);
        if (bounds == null || clip != null && !clip.intersects(0, bounds.y, getWidth(), bounds.height)) continue;
        boolean currentTool = active(getPathForRow(row).getLastPathComponent());
        if (currentTool || isRowSelected(row) || row == hovered) {
          g.setColor(currentTool ? WorkbenchUi.selected() : WorkbenchUi.background());
          g.fillRoundRect(4, bounds.y + 2, getWidth() - 8, bounds.height - 4, 8, 8);
          if (currentTool) {
            g.setColor(WorkbenchUi.accent());
            g.fillRoundRect(4, bounds.y + 6, 3, bounds.height - 12, 3, 3);
          }
        }
      }
      g.dispose();
      super.paintComponent(graphics);
      if (hasFocus() && getLeadSelectionPath() != null) {
        Rectangle bounds = getPathBounds(getLeadSelectionPath());
        if (bounds != null) {
          g = (Graphics2D) graphics.create();
          g.setColor(WorkbenchUi.accent());
          g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[] {2, 2}, 0));
          g.drawRoundRect(8, bounds.y + 4, getWidth() - 17, bounds.height - 9, 5, 5);
          g.dispose();
        }
      }
    }
  }

  private final class NavigationRenderer extends JLabel implements TreeCellRenderer {
    @Override public void updateUI() { setUI(new javax.swing.plaf.basic.BasicLabelUI()); }
    NavigationRenderer() {
      setOpaque(false);
      setIconTextGap(8);
      setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));
    }

    @Override
    public Component getTreeCellRendererComponent(JTree owner, Object value, boolean selected,
        boolean expanded, boolean leaf, int row, boolean focus) {
      DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
      boolean toolNode = node.getUserObject() instanceof Tool;
      setText(node.toString());
      setFont(WorkbenchUi.font().deriveFont(!toolNode ? Font.BOLD : Font.PLAIN, 15f));
      Icon icon = new NavigationIcon(toolNode ? IconKind.PAGE : expanded ? IconKind.OPEN_FOLDER : IconKind.FOLDER);
      setIcon(icon);
      // The payload page attaches after navigation. Reserve its icon width even while unavailable.
      setDisabledIcon(icon);
      boolean available = !toolNode || indexOf(((Tool) node.getUserObject()).id()) >= 0;
      setEnabled(available);
      setForeground(available ? WorkbenchUi.ink() : WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));
      return this;
    }

    @Override protected void paintComponent(Graphics graphics) {
      Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      super.paintComponent(g);
      g.dispose();
    }
  }

  private enum IconKind { FOLDER, OPEN_FOLDER, PAGE, RIGHT, DOWN }

  /** Java2D paths keep their edges sharp at the host's display scale. */
  private static final class NavigationIcon implements Icon {
    private final IconKind kind;
    NavigationIcon(IconKind kind) { this.kind = kind; }
    public int getIconWidth() { return kind == IconKind.RIGHT || kind == IconKind.DOWN ? 12 : 20; }
    public int getIconHeight() { return getIconWidth(); }

    public void paintIcon(Component component, Graphics graphics, int x, int y) {
      Graphics2D g = (Graphics2D) graphics.create();
      g.translate(x, y);
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      if (kind == IconKind.RIGHT || kind == IconKind.DOWN) {
        g.setColor(WorkbenchUi.dark() ? new Color(169, 181, 199) : new Color(99, 111, 129));
        Path2D arrow = new Path2D.Float();
        if (kind == IconKind.RIGHT) { arrow.moveTo(4, 2); arrow.lineTo(8, 6); arrow.lineTo(4, 10); }
        else { arrow.moveTo(2, 4); arrow.lineTo(6, 8); arrow.lineTo(10, 4); }
        g.draw(arrow);
      } else if (kind == IconKind.PAGE) {
        g.setColor(WorkbenchUi.dark() ? new Color(183, 195, 212) : new Color(101, 116, 137));
        Path2D page = new Path2D.Float();
        page.moveTo(5, 2); page.lineTo(11, 2); page.lineTo(16, 7); page.lineTo(16, 18);
        page.lineTo(5, 18); page.closePath();
        g.draw(page);
        g.drawLine(11, 2, 11, 7); g.drawLine(11, 7, 16, 7);
      } else {
        Path2D folder = new Path2D.Float();
        folder.moveTo(1, 5); folder.quadTo(1, 3, 3, 3); folder.lineTo(8, 3);
        folder.lineTo(10, 5); folder.lineTo(17, 5); folder.quadTo(19, 5, 19, 7);
        folder.lineTo(19, 16); folder.quadTo(19, 18, 17, 18); folder.lineTo(3, 18);
        folder.quadTo(1, 18, 1, 16); folder.closePath();
        g.setColor(new Color(225, 113, 26)); g.fill(folder);
        g.setColor(new Color(255, 169, 62)); g.drawLine(3, 6, 16, 6);
        if (kind == IconKind.OPEN_FOLDER) {
          Path2D front = new Path2D.Float();
          front.moveTo(1, 9); front.lineTo(20, 9); front.lineTo(18, 17);
          front.quadTo(18, 18, 16, 18); front.lineTo(3, 18); front.closePath();
          g.setColor(new Color(255, 150, 43)); g.fill(front);
        } else {
          g.setColor(new Color(255, 150, 43)); g.fillRoundRect(2, 8, 16, 9, 2, 2);
        }
      }
      g.dispose();
    }
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

  void restoreAppearance() {
    if (tree.getUI().getClass().getEnclosingClass() != NavigationTree.class) tree.updateUI();
    tree.setCellRenderer(renderer);
    tree.setRowHeight(40);
    tree.setOpaque(false);
    tree.setFont(WorkbenchUi.font().deriveFont(15f));
    tree.setBackground(WorkbenchUi.surface());
    tree.treeDidChange();
  }

  void restoreLayout() {
    restoreAppearance();
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
