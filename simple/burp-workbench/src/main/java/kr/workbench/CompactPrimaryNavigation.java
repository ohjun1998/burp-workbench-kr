package kr.workbench;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.awt.geom.Path2D;
import java.beans.PropertyChangeListener;
import java.util.Objects;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.ChangeListener;

/** Two compact navigation entries backed by the retained inspection and page controllers. */
final class CompactPrimaryNavigation extends JPanel implements AutoCloseable, Scrollable {
  private static final int ROW_HEIGHT = 42;
  private static final int ROW_GAP = 6;
  private static final int HEIGHT = 114;
  private static final Border PANEL_BORDER = BorderFactory.createEmptyBorder(12, 9, 12, 9);
  private static final Border ROW_BORDER = BorderFactory.createEmptyBorder();
  private static final Icon INSPECTION_ICON = new RadarShieldIcon();
  private static final Icon DASHBOARD_ICON = WorkspaceIcons.of("dashboard");

  private final AbstractButton inspectionController;
  private final AbstractButton dashboardController;
  private final JButton inspection = new InspectionButton();
  private final JToggleButton dashboard = new DashboardButton();
  private volatile boolean closed;
  private boolean refreshPending;

  private final PropertyChangeListener sourceProperties = event -> {
    String property = event.getPropertyName();
    if ("enabled".equals(property) || "model".equals(property)
        || "toolTipText".equals(property)) sourceChanged();
  };
  private final ItemListener sourceSelection = event -> sourceChanged();
  private final ChangeListener sourceState = event -> sourceChanged();
  private final ActionListener inspectionAction;
  private final ActionListener dashboardAction;

  CompactPrimaryNavigation(AbstractButton inspectionController, AbstractButton dashboardController) {
    super(new GridLayout(2, 1, 0, ROW_GAP));
    this.inspectionController = Objects.requireNonNull(inspectionController, "inspectionController");
    this.dashboardController = Objects.requireNonNull(dashboardController, "dashboardController");
    inspectionAction = event -> forward(this.inspectionController);
    dashboardAction = event -> forward(this.dashboardController);
    setName("wb.navigation.primary");
    setBorder(PANEL_BORDER);
    setOpaque(true);
    configure(inspection, "wb.navigation.primary.inspect", "통합 점검", INSPECTION_ICON,
        "점검 범위와 실행 조건을 준비합니다.");
    configure(dashboard, "wb.navigation.primary.dashboard", "대시보드", DASHBOARD_ICON,
        "점검 결과와 근거를 확인합니다.");
    inspection.addActionListener(inspectionAction);
    dashboard.addActionListener(dashboardAction);
    add(inspection);
    add(dashboard);
    listen(inspectionController);
    listen(dashboardController);
    restoreAppearance();
  }

  private void configure(AbstractButton button, String name, String caption, Icon icon,
                         String description) {
    button.setName(name);
    button.setText(caption);
    button.setIcon(icon);
    button.putClientProperty("wb.no.auto.icon", true);
    button.putClientProperty("wb.font.size", 13);
    button.setFocusable(true);
    button.setRolloverEnabled(true);
    button.setOpaque(false);
    button.setContentAreaFilled(false);
    button.setFocusPainted(false);
    button.setBorder(ROW_BORDER);
    button.setMargin(new Insets(0, 0, 0, 0));
    button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    button.getAccessibleContext().setAccessibleName(caption);
    button.getAccessibleContext().setAccessibleDescription(description);
    button.getInputMap(JComponent.WHEN_FOCUSED).put(
        KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0, false), "wb.primary.activate");
    button.getInputMap(JComponent.WHEN_FOCUSED).put(
        KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0, true), "wb.primary.release");
    button.getActionMap().put("wb.primary.activate", new AbstractAction() {
      @Override public void actionPerformed(ActionEvent event) {
        if (!closed && button.isEnabled()) button.doClick(0);
      }
    });
    button.getActionMap().put("wb.primary.release", new AbstractAction() {
      @Override public void actionPerformed(ActionEvent event) { }
    });
  }

  private void listen(AbstractButton source) {
    source.addPropertyChangeListener(sourceProperties);
    source.addItemListener(sourceSelection);
    source.addChangeListener(sourceState);
  }

  private void unlisten(AbstractButton source) {
    source.removePropertyChangeListener(sourceProperties);
    source.removeItemListener(sourceSelection);
    source.removeChangeListener(sourceState);
  }

  private void sourceChanged() {
    if (closed) return;
    if (SwingUtilities.isEventDispatchThread()) {
      refreshSelection();
      return;
    }
    synchronized (this) {
      if (closed || refreshPending) return;
      refreshPending = true;
    }
    SwingUtilities.invokeLater(() -> {
      synchronized (CompactPrimaryNavigation.this) { refreshPending = false; }
      refreshSelection();
    });
  }

  private static boolean available(AbstractButton source) {
    return source.isEnabled() && source.getModel().isEnabled();
  }

  private void forward(AbstractButton source) {
    if (closed || !available(source)) {
      refreshSelection();
      return;
    }
    try {
      source.doClick(0);
    } finally {
      // A selected page is authoritative, even when its selected proxy was clicked again.
      refreshSelection();
    }
  }

  void refreshSelection() {
    if (closed) return;
    mirror(inspection, inspectionController, "통합 점검 준비 화면 열기");
    mirror(dashboard, dashboardController, "대시보드 열기");
    dashboard.putClientProperty("wb.icon.active", dashboard.isEnabled());
    repaint();
  }

  private static void mirror(AbstractButton proxy, AbstractButton source, String fallbackTip) {
    boolean enabled = available(source);
    if (proxy.isEnabled() != enabled) proxy.setEnabled(enabled);
    proxy.setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
    if (proxy.isSelected() != source.isSelected()) proxy.setSelected(source.isSelected());
    String tip = source.getToolTipText();
    proxy.setToolTipText(tip == null || tip.isBlank() ? fallbackTip : tip);
  }

  void restoreAppearance() {
    if (getBorder() != PANEL_BORDER) setBorder(PANEL_BORDER);
    setBackground(kr.packetui.VisualTheme.sidebar());
    Font font = navigationFont();
    for (AbstractButton button : new AbstractButton[] {inspection, dashboard}) {
      if (!font.equals(button.getFont())) button.setFont(font);
      if (button.getBorder() != ROW_BORDER) button.setBorder(ROW_BORDER);
      button.setOpaque(false);
      button.setContentAreaFilled(false);
      button.setRolloverEnabled(true);
      button.setForeground(WorkbenchUi.ink());
      button.setBackground(kr.packetui.VisualTheme.sidebar());
    }
    if (inspection.getIcon() != INSPECTION_ICON) inspection.setIcon(INSPECTION_ICON);
    if (dashboard.getIcon() != DASHBOARD_ICON) dashboard.setIcon(DASHBOARD_ICON);
    refreshSelection();
    revalidate();
    repaint();
  }

  @Override public void close() {
    if (closed) return;
    closed = true;
    unlisten(inspectionController);
    unlisten(dashboardController);
    inspection.removeActionListener(inspectionAction);
    dashboard.removeActionListener(dashboardAction);
    inspection.setEnabled(false);
    dashboard.setEnabled(false);
    dashboard.putClientProperty("wb.icon.active", false);
  }

  private static Font navigationFont() {
    return WorkbenchUi.font().deriveFont(Font.BOLD, WorkbenchUi.displaySize(13f));
  }

  private static Dimension rowSize(AbstractButton button) {
    Font font = navigationFont();
    int text = button.getFontMetrics(font).stringWidth(Objects.toString(button.getText(), ""));
    return new Dimension(Math.max(148, text + 56), ROW_HEIGHT);
  }

  @Override public Dimension getPreferredSize() {
    return new Dimension(Math.max(190, Math.max(rowSize(inspection).width,
        rowSize(dashboard).width) + 18), HEIGHT);
  }

  @Override public Dimension getMinimumSize() { return new Dimension(0, HEIGHT); }
  @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, HEIGHT); }
  @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
  @Override public boolean getScrollableTracksViewportWidth() { return true; }
  @Override public boolean getScrollableTracksViewportHeight() { return false; }
  @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
    return orientation == SwingConstants.VERTICAL ? ROW_HEIGHT + ROW_GAP : 24;
  }
  @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
    int length = orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
    return Math.max(ROW_HEIGHT + ROW_GAP, length - ROW_HEIGHT - ROW_GAP);
  }

  @Override protected void paintComponent(Graphics graphics) {
    graphics.setColor(kr.packetui.VisualTheme.sidebar());
    graphics.fillRect(0, 0, getWidth(), getHeight());
  }

  private static Color accent(boolean inspection) {
    return inspection ? WorkbenchUi.dark() ? new Color(78, 206, 188) : new Color(9, 128, 120)
        : WorkbenchUi.accent();
  }

  private static Color mix(Color base, Color color, float weight) {
    return new Color(Math.round(base.getRed() * (1 - weight) + color.getRed() * weight),
        Math.round(base.getGreen() * (1 - weight) + color.getGreen() * weight),
        Math.round(base.getBlue() * (1 - weight) + color.getBlue() * weight));
  }

  private static void paintRow(AbstractButton button, boolean inspection, Graphics graphics) {
    Graphics2D g = (Graphics2D) graphics.create();
    WorkbenchSkin.antialias(g);
    Color background = kr.packetui.VisualTheme.sidebar(), accent = accent(inspection);
    boolean enabled = button.isEnabled(), selected = button.isSelected();
    boolean hovered = button.getModel().isRollover();
    boolean pressed = button.getModel().isPressed() && button.getModel().isArmed();
    float tint = enabled ? selected ? .23f : WorkbenchUi.dark() ? .13f : .07f : .025f;
    if (enabled && hovered) tint += .055f;
    if (enabled && pressed) tint += .07f;
    g.setColor(mix(background, accent, tint));
    g.fillRoundRect(0, 0, button.getWidth(), button.getHeight(), 10, 10);
    g.setColor(enabled ? accent : mix(background, kr.packetui.VisualTheme.muted(), .45f));
    g.fillRoundRect(1, 10, 3, Math.max(0, button.getHeight() - 20), 2, 2);
    boolean narrow = button.getWidth() < 158;
    int textX = narrow ? 36 : 41, right = narrow ? 19 : 27;
    Icon icon = inspection ? INSPECTION_ICON : DASHBOARD_ICON;
    icon.paintIcon(button, g, narrow ? 10 : 14, (button.getHeight() - icon.getIconHeight()) / 2);
    Font font = navigationFont();
    g.setFont(font);
    g.setColor(enabled ? WorkbenchUi.ink() : kr.packetui.VisualTheme.muted());
    FontMetrics metrics = g.getFontMetrics();
    String text = Objects.toString(button.getText(), "");
    int textWidth = Math.max(0, button.getWidth() - textX - right);
    Shape clip = g.getClip();
    g.clipRect(textX, 3, textWidth, Math.max(0, button.getHeight() - 6));
    g.drawString(text, textX, (button.getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
    g.setClip(clip);
    g.setColor(enabled ? accent : kr.packetui.VisualTheme.muted());
    g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    int x = button.getWidth() - (narrow ? 13 : 17), y = button.getHeight() / 2;
    g.drawPolyline(new int[] {x, x + 4, x}, new int[] {y - 4, y, y + 4}, 3);
    g.dispose();
  }

  private static void paintFrame(AbstractButton button, boolean inspection, Graphics graphics) {
    Graphics2D g = (Graphics2D) graphics.create();
    WorkbenchSkin.antialias(g);
    Color accent = accent(inspection);
    float intensity = !button.isEnabled() ? .13f : button.isSelected() ? .8f
        : button.getModel().isRollover() ? .64f : .4f;
    g.setColor(mix(WorkbenchUi.line(), accent, intensity));
    g.setStroke(new BasicStroke(1f));
    g.drawRoundRect(0, 0, button.getWidth() - 1, button.getHeight() - 1, 10, 10);
    if (button.isEnabled() && button.hasFocus()) {
      g.setColor(accent);
      g.setStroke(new BasicStroke(1.3f));
      g.drawRoundRect(3, 3, button.getWidth() - 7, button.getHeight() - 7, 7, 7);
    }
    g.dispose();
  }

  private final class InspectionButton extends JButton {
    @Override public Dimension getPreferredSize() { return rowSize(this); }
    @Override public Dimension getMinimumSize() { return new Dimension(0, ROW_HEIGHT); }
    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, ROW_HEIGHT); }
    @Override protected void paintComponent(Graphics g) { paintRow(this, true, g); }
    @Override protected void paintBorder(Graphics g) { paintFrame(this, true, g); }
  }

  private final class DashboardButton extends JToggleButton {
    @Override public Dimension getPreferredSize() { return rowSize(this); }
    @Override public Dimension getMinimumSize() { return new Dimension(0, ROW_HEIGHT); }
    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, ROW_HEIGHT); }
    @Override protected void paintComponent(Graphics g) { paintRow(this, false, g); }
    @Override protected void paintBorder(Graphics g) { paintFrame(this, false, g); }
  }

  private static final class RadarShieldIcon implements Icon {
    @Override public int getIconWidth() { return 18; }
    @Override public int getIconHeight() { return 18; }
    @Override public void paintIcon(Component owner, Graphics graphics, int x, int y) {
      Graphics2D g = (Graphics2D) graphics.create();
      g.translate(x, y);
      WorkbenchSkin.antialias(g);
      g.setColor(owner.isEnabled() ? accent(true) : kr.packetui.VisualTheme.muted());
      g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      g.drawOval(1, 1, 13, 13);
      g.drawArc(4, 4, 7, 7, 70, 250);
      g.drawLine(7, 7, 11, 3);
      g.fillOval(6, 6, 2, 2);
      Path2D shield = new Path2D.Float();
      shield.moveTo(12, 8);shield.lineTo(17, 10);shield.lineTo(16, 14);
      shield.quadTo(15, 16, 12, 17);shield.quadTo(9, 16, 8, 14);
      shield.lineTo(7, 10);shield.closePath();
      g.setColor(mix(kr.packetui.VisualTheme.sidebar(), accent(true), WorkbenchUi.dark() ? .13f : .07f));
      g.fill(shield);
      g.setColor(owner.isEnabled() ? accent(true) : kr.packetui.VisualTheme.muted());
      g.draw(shield);
      g.drawPolyline(new int[] {9, 11, 14}, new int[] {12, 14, 11}, 3);
      g.dispose();
    }
  }
}
