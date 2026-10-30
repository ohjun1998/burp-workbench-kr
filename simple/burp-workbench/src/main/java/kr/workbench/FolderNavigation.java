package kr.workbench;

import burp.api.montoya.MontoyaApi;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import javax.swing.plaf.basic.BasicTreeUI;
import javax.swing.tree.*;

/** Folder navigation over the existing page instances and their shared selection model. */
final class FolderNavigation extends JPanel implements AutoCloseable {
  private static final int DEFAULT_WIDTH = 220;
  private static final int MIN_WIDTH = 160;
  private static final int MAX_WIDTH = 420;
  private final MontoyaApi api;
  private final JTabbedPane pages;
  private final boolean classic;
  private final Map<String, DefaultMutableTreeNode> tools = new LinkedHashMap<>();
  private final JTree tree;
  private final TreeCellRenderer renderer = new NavigationRenderer();
  private final JScrollPane sidebar;
  private final JSplitPane split;
  private final JButton toggle = new JButton();
  private final JLabel current = new JLabel();
  private final JButton back = new JButton("←"), forward = new JButton("→"), quick = new JButton("기능 찾기");
  private final NavigationViewState.ModelVersions modelVersions = new NavigationViewState.ModelVersions();
  private final List<Visit> visits = new ArrayList<>();
  private final List<String> recent = new ArrayList<>();
  private final TargetWindow quickWindow;
  private QuickNavigationPanel quickPanel;
  private Consumer<String> routeOpener = this::openLocalRoute;
  private Supplier<String> routeProvider = this::selectedRoute, projectProvider = () -> "";
  private Function<String, Object> captureExtra = route -> null;
  private Consumer<Object> restoreExtra = state -> {};
  private String historyProject = "";
  private int visitIndex = -1;
  private long historyGeneration;
  private boolean changingHistory, recordPending, closed;
  private static final int HISTORY_LIMIT = 24, RECENT_LIMIT = 8;
  private static final class Visit {
    final String route;
    NavigationViewState view;
    Object extra;
    Visit(String route, NavigationViewState view, Object extra) { this.route = route; this.view = view; this.extra = extra; }
  }
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
    this(api, pages, openSettings, help, logs, false);
  }

  FolderNavigation(MontoyaApi api, JTabbedPane pages, Runnable openSettings, JButton help, JButton logs,
      boolean classic, AbstractButton... shortcuts) {
    super(new BorderLayout(0, 0));
    this.api = api;
    this.pages = pages;
    this.classic = classic;
    quickWindow = new TargetWindow(this, "기능 바로 찾기 · Ctrl+K", this::quickPanel);
    setName("wb.navigation");
    pages.setName("wb.navigation.pages");
    pages.setMinimumSize(new Dimension(360, 100));
    try {
      String width = read("width");
      if (width != null) menuWidth = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, Integer.parseInt(width)));
    } catch (NumberFormatException ignored) {}
    hidden = Boolean.parseBoolean(read("hidden"));

    DefaultMutableTreeNode root = new DefaultMutableTreeNode("도구");
    folder(root, "트래픽", "traffic", "mapper");
    if (classic) folder(root, "탐색·정보 수집", "js", "environment");
    else folder(root, "탐색·정보 수집", "js", "environment");
    folder(root, "보안 분석·검증", "auth", "response.recon", "payload");
    if (classic) {
      folder(root, "데이터 도구", "data");
      folder(root, "점검 관리·증거", "activity", "bounty");
    } else folder(root, "점검 관리·증거", "bounty");
    tree = new NavigationTree(root);
    tree.setName("wb.navigation.tree");
    tree.setRootVisible(false);
    tree.setShowsRootHandles(false);
    tree.setRowHeight(0);
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
        if (index >= 0) navigate(tool.id());
      }
      tree.repaint();
    });
    sidebar = new JScrollPane(tree);
    sidebar.setName("wb.navigation.sidebar");
    sidebar.setMinimumSize(new Dimension(MIN_WIDTH, 80));sidebar.setBorder(null);
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
    toggle.setName("wb.navigation.toggle");toggle.setIcon(WorkspaceIcons.of("menu"));toggle.putClientProperty("wb.segment.button",true);toggle.setPreferredSize(new Dimension(40,38));
    toggle.addActionListener(event -> setMenuHidden(!hidden));
    back.setName("wb.navigation.back"); forward.setName("wb.navigation.forward");
    quick.setName("wb.navigation.quick");
    back.setToolTipText("이전 작업 화면 · Alt+←"); forward.setToolTipText("다음 작업 화면 · Alt+→");
    quick.setToolTipText("기능 이름이나 목적으로 찾기 · Ctrl+K");
    back.getAccessibleContext().setAccessibleName("이전 작업 화면");
    forward.getAccessibleContext().setAccessibleName("다음 작업 화면");
    back.addActionListener(e -> goBack()); forward.addActionListener(e -> goForward());
    quick.addActionListener(e -> openQuickNavigation());
    current.setName("wb.navigation.current");
    current.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
    JButton settings = new JButton("설정");
    settings.setName("wb.navigation.settings");
    settings.setToolTipText("Workbench Simple " + BuildInfo.VERSION + " · 화면 설정");
    settings.addActionListener(event -> openSettings.run());
    JLabel version = new JLabel("NEXT " + BuildInfo.VERSION);
    version.setName("wb.next.version");
    version.setFont(WorkbenchUi.font().deriveFont(Font.BOLD, WorkbenchUi.displaySize(13f)));
    version.setForeground(WorkbenchUi.accent());
    version.setToolTipText("Workbench Next · 별도 배포 채널 · 기존 Workbench와 구분");
    JPanel navigationLine = new JPanel(new BorderLayout(8, 0));
    navigationLine.setName("wb.navigation.toolbar.line");
    JPanel navigationControls = new JPanel();
    navigationControls.setLayout(new BoxLayout(navigationControls, BoxLayout.X_AXIS));
    navigationControls.setBorder(classic ? BorderFactory.createEmptyBorder()
        : BorderFactory.createEmptyBorder(5, 8, 5, 8));
    Component[] navigationButtons = classic ? new Component[] {toggle}
        : new Component[] {toggle, version, back, forward, quick};
    for (Component control : navigationButtons) {
      if (navigationControls.getComponentCount() > 0) navigationControls.add(Box.createHorizontalStrut(8));
      navigationControls.add(control);
    }
    if (classic) {
      JLabel brand=new JLabel("Workbench");brand.setName("wb.navigation.brand");brand.putClientProperty("wb.font.size",18);brand.putClientProperty("wb.font.heading",true);
      navigationControls.add(Box.createHorizontalStrut(10));navigationControls.add(brand);
      navigationControls.setPreferredSize(new Dimension(DEFAULT_WIDTH-12,40));
      navigationControls.setBorder(BorderFactory.createMatteBorder(0,0,0,1,WorkbenchUi.line()));
    }
    navigationLine.add(navigationControls, BorderLayout.WEST);
    navigationLine.add(current, BorderLayout.CENTER);
    JPanel actions = new JPanel(classic
        ? new WorkbenchUi.WrapLayout(FlowLayout.RIGHT, 6, 0) : new GridLayout(1, 0, 8, 0));
    actions.setName("wb.navigation.actions");
    for (AbstractButton shortcut : shortcuts) if (shortcut != null) {
      if(classic && shortcut.getText().contains("Scope")){shortcut.setToolTipText(shortcut.getText());shortcut.getAccessibleContext().setAccessibleName(shortcut.getText());shortcut.setText("");shortcut.setIcon(WorkspaceIcons.of("target"));shortcut.putClientProperty("wb.compact.button",true);}
      actions.add(shortcut);
    }
    if(classic && help!=null)help.setText("도움말");
    if (help != null) actions.add(help);
    actions.add(settings);
    if (logs != null) actions.add(logs);
    for(Component c:actions.getComponents())if(c instanceof AbstractButton b)b.putClientProperty("wb.segment.button",true);
    navigationLine.add(actions, BorderLayout.EAST);
    JPanel toolbar = new ResponsiveToolbar(navigationLine, actions);
    add(toolbar, BorderLayout.NORTH);
    add(split, BorderLayout.CENTER);
    getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
        KeyStroke.getKeyStroke("control shift M"), "toggleMenu");
    getActionMap().put("toggleMenu", new AbstractAction() {
      public void actionPerformed(ActionEvent event) { setMenuHidden(!hidden); }
    });
    bindNavigation("control K", "quickNavigation", this::openQuickNavigation);
    bindNavigation("alt LEFT", "navigationBack", this::goBack);
    bindNavigation("alt RIGHT", "navigationForward", this::goForward);
    if (classic) {
      String[] retained = {"traffic", "mapper", "auth", "js", "environment", "response.recon", "payload", "bounty"};
      for (int i = 0; i < retained.length; i++) {
        String id = retained[i];
        bindNavigation("alt " + (i + 1), "classic.tool." + id, () -> navigate(id));
      }
    }
    pages.addChangeListener(event -> { refreshSelection(); navigationChanged(); });
    refreshSelection();
    updateHistoryActions();
  }

  /** Route callbacks only open retained UI components; the host must not execute network actions. */
  void configureRoutes(Consumer<String> opener, Supplier<String> currentRoute, Supplier<String> projectIdentity) {
    routeOpener = Objects.requireNonNull(opener); routeProvider = Objects.requireNonNull(currentRoute);
    projectProvider = Objects.requireNonNull(projectIdentity); resetHistory();
  }

  /** Additional in-memory traffic query/original-ID checkpoint; never serialized by navigation. */
  void setViewStateHooks(Supplier<Object> capture, Consumer<Object> restore) {
    Objects.requireNonNull(capture); setViewStateHooks(route -> capture.get(), restore);
  }
  /** A page-change event is delivered after selection changed, so capture uses the bookmarked route. */
  void setViewStateHooks(Function<String, Object> capture, Consumer<Object> restore) {
    captureExtra = Objects.requireNonNull(capture); restoreExtra = Objects.requireNonNull(restore);
    if (visitIndex >= 0) visits.get(visitIndex).extra = captureExtra.apply(visits.get(visitIndex).route);
  }

  /** The host calls this after changes in nested work tabs, using the same event coalescing as pages. */
  void navigationChanged() {
    if (closed || changingHistory) return;
    refreshSelection();
    ensureProject(); captureCurrent();
    if (recordPending) return;
    recordPending = true;
    long token = historyGeneration;
    SwingUtilities.invokeLater(() -> {
      if (token != historyGeneration) return;
      recordPending = false;
      if (!closed && !changingHistory) recordDestination(currentRoute());
    });
  }

  void navigate(String route) {
    if (closed || NavigationTargets.target(route) == null) return;
    ensureProject(); captureCurrent(); historyGeneration++; recordPending = false; changingHistory = true;
    try { routeOpener.accept(route); }
    finally { changingHistory = false; }
    String actualRoute = currentRoute();
    recordDestination(NavigationTargets.target(actualRoute) == null ? route : actualRoute); refreshSelection();
  }

  void goBack() { moveHistory(-1); }
  void goForward() { moveHistory(1); }
  boolean canGoBack() { ensureProject(); return visitIndex > 0; }
  boolean canGoForward() { ensureProject(); return visitIndex >= 0 && visitIndex + 1 < visits.size(); }
  List<String> recentRoutes() { ensureProject(); return List.copyOf(recent); }

  private void moveHistory(int direction) {
    if (closed) return;
    ensureProject(); int next = visitIndex + direction;
    if (next < 0 || next >= visits.size() || historyProject.isBlank()) return;
    captureCurrent(); Visit visit = visits.get(next); String expectedProject = historyProject;
    long token = ++historyGeneration; recordPending = false; changingHistory = true;
    try {
      routeOpener.accept(visit.route);
      if (!expectedProject.equals(currentProject())) { resetHistory(); return; }
      if (visit.extra != null) restoreExtra.accept(visit.extra);
      visit.view.restore(modelVersions); visitIndex = next;
      rememberRecent(visit.route); refreshSelection();
    } finally { changingHistory = false; updateHistoryActions(); }
    SwingUtilities.invokeLater(() -> {
      if (!closed && token == historyGeneration && expectedProject.equals(currentProject())
          && visitIndex >= 0 && visits.get(visitIndex) == visit) visit.view.restoreScrolls();
    });
  }

  private void captureCurrent() {
    if (visitIndex < 0 || visitIndex >= visits.size()) return;
    Visit currentVisit = visits.get(visitIndex);
    currentVisit.view = currentVisit.view.refresh(modelVersions); currentVisit.extra = captureExtra.apply(currentVisit.route);
  }

  private void recordDestination(String route) {
    ensureProject();
    if (closed || historyProject.isBlank() || NavigationTargets.target(route) == null
        || pages.getSelectedComponent() == null) { updateHistoryActions(); return; }
    NavigationViewState view = NavigationViewState.capture(pages.getSelectedComponent(), modelVersions);
    if (visitIndex >= 0 && visits.get(visitIndex).route.equals(route) && visits.get(visitIndex).view.sameTabs(view)) {
      visits.get(visitIndex).view = view; visits.get(visitIndex).extra = captureExtra.apply(route);
    } else {
      while (visits.size() > visitIndex + 1) visits.remove(visits.size() - 1);
      visits.add(new Visit(route, view, captureExtra.apply(route)));
      if (visits.size() > HISTORY_LIMIT) visits.remove(0);
      visitIndex = visits.size() - 1;
    }
    rememberRecent(route); updateHistoryActions();
  }

  private void rememberRecent(String route) {
    recent.remove(route); recent.add(0, route);
    if (recent.size() > RECENT_LIMIT) recent.remove(recent.size() - 1);
  }

  void resetHistory() {
    historyGeneration++; recordPending = false; visits.clear(); recent.clear(); visitIndex = -1;
    historyProject = currentProject();
    modelVersions.close(); quickWindow.closeWindow(); if (quickPanel != null) quickPanel.reset();
    if (!closed && !changingHistory && !historyProject.isBlank() && pages.getSelectedComponent() != null)
      recordDestination(currentRoute());
    updateHistoryActions();
  }

  private void ensureProject() {
    if (!historyProject.equals(currentProject())) {
      historyGeneration++; recordPending = false; visits.clear(); recent.clear(); visitIndex = -1;
      historyProject = currentProject();
      modelVersions.close(); quickWindow.closeWindow();
      // This guard can run from the palette's DocumentListener. Reset outside that notification.
      long token = historyGeneration;
      if (quickPanel != null) SwingUtilities.invokeLater(() -> {
        if (token == historyGeneration && quickPanel != null) quickPanel.reset();
      });
      updateHistoryActions();
    }
  }

  private String currentProject() {
    try { return Objects.toString(projectProvider.get(), ""); }
    catch (RuntimeException error) { return ""; }
  }
  private String currentRoute() {
    try { return Objects.toString(routeProvider.get(), ""); }
    catch (RuntimeException error) { return ""; }
  }
  private String selectedRoute() {
    return pages.getSelectedComponent() instanceof JComponent page
        ? Objects.toString(page.getClientProperty("wb.feature.id"), "") : "";
  }
  private void openLocalRoute(String route) {
    String primary = switch (route) {
      case "data", "jwt" -> "traffic";
      case "passive" -> "environment";
      case "activity" -> "mapper";
      case "evidence" -> "bounty";
      default -> route;
    };
    int index = indexOf(primary); if (index >= 0) pages.setSelectedIndex(index);
  }
  private void updateHistoryActions() {
    back.setEnabled(!closed && visitIndex > 0);
    forward.setEnabled(!closed && visitIndex >= 0 && visitIndex + 1 < visits.size());
  }
  QuickNavigationPanel quickPanel() {
    if (quickPanel == null) quickPanel = new QuickNavigationPanel(this::recentRoutes, this::navigate, () -> {
      quickWindow.closeWindow(); if (quickPanel != null) quickPanel.reset();
    });
    return quickPanel;
  }
  void openQuickNavigation() {
    if (closed) return;
    ensureProject(); quickPanel().reset(); quickWindow.open();
    SwingUtilities.invokeLater(() -> { if (quickPanel != null && quickPanel.isShowing()) quickPanel.query.requestFocusInWindow(); });
  }
  void setQuickWindowOpener(WorkbenchExtension.DialogOpener opener) { quickWindow.opener = Objects.requireNonNull(opener); }
  private void bindNavigation(String key, String name, Runnable action) {
    getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), name);
    getActionMap().put(name, new AbstractAction() {
      public void actionPerformed(ActionEvent event) { action.run(); }
    });
  }
  public void close() { closed = true; resetHistory(); quickWindow.close(); }

  /** Relayout is synchronous so a small window never needs a resize-event round trip to expose actions. */
  private static final class ResponsiveToolbar extends JPanel {
    private final JPanel line, actions;
    private boolean compact;
    ResponsiveToolbar(JPanel line, JPanel actions) {
      super(new BorderLayout(8, 4)); this.line = line; this.actions = actions;
      setName("wb.navigation.toolbar");
      setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,WorkbenchUi.line()),BorderFactory.createEmptyBorder(0,6,0,8))); add(line, BorderLayout.NORTH);
    }
    private void adapt() {
      boolean next = getWidth() > 0 && getWidth() < 900;
      if (compact == next) return;
      compact = next;
      if (next) { line.remove(actions); add(actions, BorderLayout.SOUTH); }
      else { remove(actions); line.add(actions, BorderLayout.EAST); }
    }
    @Override public void doLayout() { adapt(); super.doLayout(); }
    @Override public Dimension getPreferredSize() { if (line != null) adapt(); return super.getPreferredSize(); }
  }

  private boolean active(Object value) {
    if (!(value instanceof DefaultMutableTreeNode node)
        || !(node.getUserObject() instanceof Tool tool)) return false;
    return tool.id().equals(selectedToolId());
  }

  /** Local UI delegate and vector icons avoid OS-dependent folders and tree guide lines. */
  private final class NavigationTree extends JTree {
    private int hovered = -1;

    NavigationTree(TreeNode root) {
      super(root);
      setOpaque(false);
      setBorder(BorderFactory.createEmptyBorder(6, 14, 8, 0));
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
          setLeftChildIndent(8);
          setRightChildIndent(10);
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
      g.setColor(WorkbenchUi.background());
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
      setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
    }

    @Override
    public Component getTreeCellRendererComponent(JTree owner, Object value, boolean selected,
        boolean expanded, boolean leaf, int row, boolean focus) {
      DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
      boolean toolNode = node.getUserObject() instanceof Tool;
      setText(node.toString());
      setFont(WorkbenchUi.font().deriveFont(!toolNode ? Font.BOLD : Font.PLAIN, WorkbenchUi.displaySize(14f)));
      setBorder(BorderFactory.createEmptyBorder(toolNode?8:row==0?18:30, 2, toolNode?8:10, 8));
      Icon icon = WorkspaceIcons.of(toolNode?((Tool)node.getUserObject()).id():"group");
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
    if (classic) {
      String host = switch (id) {
        case "data", "jwt" -> "traffic";
        case "passive" -> "environment";
        case "activity" -> "mapper";
        case "evidence" -> "bounty";
        default -> id;
      };
      if (!host.equals(id)) return indexOf(host);
    }
    return -1;
  }

  private String selectedToolId() {
    String route = currentRoute();
    String id = switch (route) {
      case "jwt" -> "data";
      case "evidence" -> "bounty";
      default -> route;
    };
    return tools.containsKey(id) ? id : selectedRoute();
  }

  void refreshSelection() {
    Component selected = pages.getSelectedComponent();
    if (!(selected instanceof JComponent page)) return;
    DefaultMutableTreeNode node = tools.get(selectedToolId());
    synchronizing = true;
    try {
      if (node == null) {
        tree.clearSelection();
        current.setText(FeatureGuide.title(Objects.toString(page.getClientProperty("wb.feature.id"), "")));
      } else {
        TreePath path = new TreePath(node.getPath());
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
        current.setText(node.getParent() + " / " + node);
      }
      current.setToolTipText(current.getText());
    } finally { synchronizing = false; }
    tree.repaint();
  }

  void restoreAppearance() {
    if (tree.getUI().getClass().getEnclosingClass() != NavigationTree.class) tree.updateUI();
    tree.setCellRenderer(renderer);
    tree.setRowHeight(0);
    tree.setOpaque(false);
    tree.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(14f)));
    tree.setBackground(WorkbenchUi.surface());
    tree.treeDidChange();
    sidebar.setMinimumSize(new Dimension(minimumMenuWidth(),80));
    constrainDivider();
  }

  void restoreLayout() {
    restoreAppearance();
    tree.setBackground(WorkbenchUi.surface());
    sidebar.setVisible(!hidden);
    split.setDividerSize(hidden ? 0 : 7);
    toggle.setText("");toggle.putClientProperty("wb.segment.button",true);
    toggle.setToolTipText("왼쪽 폴더 메뉴 " + (hidden ? "보기" : "숨기기") + " · Ctrl+Shift+M");
    toggle.getAccessibleContext().setAccessibleName(hidden ? "메뉴 보기" : "메뉴 숨기기");
    constrainDivider();
  }

  private void setMenuHidden(boolean value) {
    hidden = value;
    restoreLayout();
    write("hidden", Boolean.toString(hidden));
    revalidate();
    repaint();
  }

  private int minimumMenuWidth() {
    if(WorkbenchUi.appearanceScale()<=100)return MIN_WIDTH;
    int required=MIN_WIDTH;
    for(int row=0;row<tree.getRowCount();row++){
      TreePath path=tree.getPathForRow(row);Rectangle bounds=tree.getRowBounds(row);if(path==null||bounds==null)continue;
      Component label=renderer.getTreeCellRendererComponent(tree,path.getLastPathComponent(),false,tree.isExpanded(row),tree.getModel().isLeaf(path.getLastPathComponent()),row,false);
      required=Math.max(required,bounds.x+label.getPreferredSize().width+sidebar.getVerticalScrollBar().getPreferredSize().width+8);
    }
    return Math.min(MAX_WIDTH,required);
  }
  private int constrained(int width) {
    int minimum=minimumMenuWidth();
    int maximum = split.getWidth() > 0 ? Math.min(MAX_WIDTH, split.getWidth() - 367) : MAX_WIDTH;
    return Math.max(minimum, Math.min(Math.max(minimum, maximum), width));
  }

  private void constrainDivider() {
    adjusting = true;
    try { split.setDividerLocation(hidden ? 0 : constrained(menuWidth)); }
    finally { adjusting = false; }
  }

  private String read(String suffix) {
    try { return api.persistence().preferences().getString(BuildInfo.storageKey("wb.navigation." + suffix)); }
    catch (Exception ignored) { return null; }
  }

  private void write(String suffix, String value) {
    try { api.persistence().preferences().setString(BuildInfo.storageKey("wb.navigation." + suffix), value); }
    catch (Exception ignored) { /* Layout remains usable if preferences are unavailable. */ }
  }
}
