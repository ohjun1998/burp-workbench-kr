package kr.workbench;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import javax.swing.*;
import javax.accessibility.AccessibleContext;

/** Illustrated, local-only guidance. It never captures the user's workspace or sends a request. */
final class VisualGuidePanel extends JPanel implements AutoCloseable {
  static final Color NUMBER_RED = new Color(221, 48, 57);
  final JComboBox<VisualGuideCatalog.Screen> screens;
  final JComboBox<String> zoom = new JComboBox<>(new String[]{"화면 맞춤", "100%"});
  final JButton maximize = new JButton("최대화");
  final GuideCanvas canvas = new GuideCanvas();
  final StepsPanel steps = new StepsPanel();
  final JScrollPane imageScroll = new JScrollPane(canvas);
  final JScrollPane stepsScroll = new JScrollPane(steps);
  final JLabel status = new JLabel("합성 데이터로 만든 사용 예시 · 빨간 번호와 같은 번호의 단계를 확인하세요.");
  final JTextArea notice = new JTextArea();
  private final LinkedHashMap<String, BufferedImage> images = new LinkedHashMap<>(4, .75f, true) {
    @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> entry) { return size() > 3; }
  };
  private SwingWorker<BufferedImage, Void> imageWorker;
  private VisualGuideCatalog.Screen screen;
  private volatile String selectedId = "";
  private volatile int selectedStep;
  private volatile boolean closed;
  private boolean selecting;
  private long generation;

  VisualGuidePanel() {
    super(new BorderLayout(0, 8));
    setName("wb.visual.guide.panel");
    setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
    screens = new JComboBox<>(VisualGuideCatalog.all().toArray(VisualGuideCatalog.Screen[]::new));
    screens.setName("wb.visual.guide.screen");
    screens.setPrototypeDisplayValue(VisualGuideCatalog.all().stream()
        .max(Comparator.comparingInt(value -> value.title().length())).orElse(null));
    screens.getAccessibleContext().setAccessibleName("사용 방법을 볼 도구 화면");
    screens.setPreferredSize(new Dimension(280, 34));
    screens.setMinimumSize(new Dimension(200, 34));
    zoom.setName("wb.visual.guide.zoom");
    zoom.setPreferredSize(new Dimension(132, 34));
    zoom.setMinimumSize(new Dimension(120, 34));
    zoom.getAccessibleContext().setAccessibleName("가이드 화면 확대 비율");
    zoom.setToolTipText("화면 맞춤 또는 원본 크기 · 100%에서는 가로/세로로 스크롤할 수 있습니다.");
    maximize.setName("wb.visual.guide.maximize");
    maximize.setToolTipText("가이드 창 최대화/복원 · F11");
    add(CompactWorkbenchUi.toolbar(WorkbenchUi.row(new JLabel("화면"), screens),
        WorkbenchUi.row(zoom, maximize)), BorderLayout.NORTH);
    canvas.setName("wb.visual.guide.image");
    canvas.getAccessibleContext().setAccessibleName("사용 단계의 빨간 번호를 표시한 도구 화면 예시");
    canvas.setToolTipText("빨간 번호를 누르면 해당 단계의 설명을 선택합니다.");
    steps.setName("wb.visual.guide.steps");
    steps.getAccessibleContext().setAccessibleName("화면 번호에 대응하는 사용 단계");
    stepsScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    stepsScroll.setMinimumSize(new Dimension(280, 140));
    imageScroll.setMinimumSize(new Dimension(250, 160));
    imageScroll.getHorizontalScrollBar().setUnitIncrement(24);
    imageScroll.getVerticalScrollBar().setUnitIncrement(24);
    stepsScroll.getVerticalScrollBar().setUnitIncrement(24);
    JSplitPane split = WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT, imageScroll, stepsScroll, .72);
    // WorkbenchUi.split sets generic minima; explanations need a readable line length.
    stepsScroll.setMinimumSize(new Dimension(280, 140));
    imageScroll.setMinimumSize(new Dimension(250, 160));
    split.setName("wb.visual.guide.split");
    add(split, BorderLayout.CENTER);
    status.setName("wb.visual.guide.status");
    status.setToolTipText("이 안내 이미지는 합성 데이터입니다. 실제 대상의 요청·응답 또는 판정 결과가 아닙니다.");
    notice.setName("wb.visual.guide.notice");
    notice.setEditable(false); notice.setLineWrap(true); notice.setWrapStyleWord(true);
    notice.setRows(2); notice.setOpaque(false); notice.putClientProperty("wb.prose", true);
    notice.setFont(WorkbenchUi.font()); notice.setBorder(null);
    JPanel footer = new JPanel(new BorderLayout(0, 3)); footer.add(status, BorderLayout.NORTH); footer.add(notice);
    add(footer, BorderLayout.SOUTH);
    screens.addActionListener(event -> {
      if (!selecting && !closed && screens.getSelectedItem() instanceof VisualGuideCatalog.Screen chosen)
        select(chosen.id());
    });
    zoom.addActionListener(event -> {
      if (closed) return;
      canvas.fit = zoom.getSelectedIndex() == 0;
      canvas.revalidate();
      imageScroll.getViewport().setViewPosition(new Point());
      canvas.repaint();
    });
    WorkbenchUi.style(this);
    if (!VisualGuideCatalog.all().isEmpty()) select(VisualGuideCatalog.all().get(0).id());
  }

  void select(String id) {
    if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> select(id)); return; }
    if (closed) return;
    VisualGuideCatalog.Screen chosen = VisualGuideCatalog.screen(id);
    boolean dark = WorkbenchUi.dark();
    String key = chosen.image() + "|" + dark;
    if (screen != null && selectedId.equals(id) && canvas.image != null && key.equals(canvas.imageKey)) return;
    long token = ++generation;
    if (imageWorker != null) imageWorker.cancel(true);
    imageWorker = null;
    screen = chosen;
    String previousId=selectedId;
    selectedId = chosen.id();
    firePropertyChange("guide.screen",previousId,selectedId);
    notice.setText(chosen.notice()); notice.setCaretPosition(0);
    selecting = true;
    try { screens.setSelectedItem(chosen); } finally { selecting = false; }
    selectedStep = chosen.steps().isEmpty() ? 0 : chosen.steps().get(0).number();
    rebuildSteps();
    canvas.image = null;
    canvas.imageKey = key;
    canvas.message = "가이드 화면을 불러오는 중입니다…";
    status.setText("합성 화면 예시 · 빨간 번호와 단계별 설명을 함께 확인하세요.");
    imageScroll.getViewport().setViewPosition(new Point());
    canvas.revalidate(); canvas.repaint();
    BufferedImage cached = images.get(key);
    if (cached != null) { applyImage(cached, token, key); return; }
    imageWorker = new SwingWorker<>() {
      @Override protected BufferedImage doInBackground() throws Exception {
        return VisualGuideCatalog.readImage(chosen, dark);
      }
      @Override protected void done() {
        if (closed || token != generation || !key.equals(canvas.imageKey)) return;
        imageWorker = null;
        try {
          BufferedImage loaded = get();
          if (loaded == null) throw new IllegalStateException("가이드 이미지 형식 오류");
          images.put(key, loaded);
          applyImage(loaded, token, key);
        } catch (CancellationException ignored) {
          // A newer selection owns the image now.
        } catch (Exception error) {
          canvas.message = "가이드 이미지를 불러오지 못했습니다. 다른 화면을 선택하거나 다시 열어주세요.";
          status.setText("화면 이미지 읽기 실패 · 다른 화면을 선택하거나 가이드를 다시 열어주세요.");
          canvas.repaint();
        }
      }
    };
    imageWorker.execute();
  }

  private void applyImage(BufferedImage loaded, long token, String key) {
    if (closed || token != generation || !key.equals(canvas.imageKey)) return;
    canvas.image = loaded; canvas.message = "";
    canvas.revalidate(); canvas.repaint();
  }

  String selectedId() { return selectedId; }
  boolean imageReady() { return !closed && canvas.image != null; }
  int selectedStep() { return selectedStep; }

  void selectStep(int number) {
    if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> selectStep(number)); return; }
    if (closed || screen == null || screen.steps().stream().noneMatch(step -> step.number() == number)) return;
    selectedStep = number;
    canvas.repaint();
    for (Component component : steps.getComponents()) if (component instanceof StepCard card) {
      card.updateSelection();
      if (card.step.number() == number) card.scrollRectToVisible(new Rectangle(0, 0, card.getWidth(), card.getHeight()));
    }
  }

  private void rebuildSteps() {
    steps.removeAll();
    for (VisualGuideCatalog.Step step : screen.steps()) {
      steps.add(new StepCard(step));
      steps.add(Box.createVerticalStrut(8));
    }
    WorkbenchUi.style(this);
    steps.revalidate(); steps.repaint();
    stepsScroll.getViewport().setViewPosition(new Point());
  }

  @Override public void close() {
    if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::close); return; }
    if (closed) return;
    closed = true; generation++;
    if (imageWorker != null) imageWorker.cancel(true);
    imageWorker = null; images.clear();
    canvas.image = null; canvas.imageKey = "";
    steps.removeAll();
    screens.setEnabled(false); zoom.setEnabled(false); maximize.setEnabled(false);
  }

  private static void paintNumber(Graphics2D graphics, int number, int x, int y, boolean selected) {
    Graphics2D g = (Graphics2D) graphics.create();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    if (selected) {
      g.setColor(new Color(255, 65, 75, 65));
      g.fillOval(x - 20, y - 20, 40, 40);
    }
    g.setColor(Color.WHITE); g.fillOval(x - 16, y - 16, 32, 32);
    g.setColor(NUMBER_RED); g.fillOval(x - 14, y - 14, 28, 28);
    g.setColor(Color.WHITE); g.setFont(WorkbenchUi.font().deriveFont(Font.BOLD, 14f));
    String label = Integer.toString(number); FontMetrics metrics = g.getFontMetrics();
    g.drawString(label, x - metrics.stringWidth(label) / 2, y + (metrics.getAscent() - metrics.getDescent()) / 2);
    g.dispose();
  }

  final class GuideCanvas extends JComponent implements Scrollable {
    volatile BufferedImage image;
    String imageKey = "", message = "";
    boolean fit = true;
    GuideCanvas() {
      setOpaque(true); setFocusable(true);
      addMouseListener(new MouseAdapter() {
        @Override public void mouseClicked(MouseEvent event) {
          if (closed || image == null || screen == null) return;
          for (VisualGuideCatalog.Step step : screen.steps()) {
            Point point = markerPoint(step.number());
            if (point.distance(event.getPoint()) <= 18) { selectStep(step.number()); requestFocusInWindow(); return; }
          }
        }
      });
    }
    @Override public AccessibleContext getAccessibleContext() {
      if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {};
      return accessibleContext;
    }
    Point markerCenter(int number) { return markerPoint(number); }
    Rectangle imageBounds() {
      BufferedImage current = image;
      if (current == null) return new Rectangle();
      double scale = fit ? Math.min(Math.max(1, getWidth() - 32) / (double) current.getWidth(),
          Math.max(1, getHeight() - 32) / (double) current.getHeight()) : 1;
      int width = Math.max(1, (int)Math.round(current.getWidth() * scale));
      int height = Math.max(1, (int)Math.round(current.getHeight() * scale));
      return new Rectangle(Math.max(16, (getWidth() - width) / 2), Math.max(16, (getHeight() - height) / 2), width, height);
    }
    Point markerPoint(int number) {
      Rectangle bounds = imageBounds();
      if (screen != null) for (VisualGuideCatalog.Step step : screen.steps()) if (step.number() == number)
        return new Point(bounds.x + (int)Math.round(step.x() * bounds.width), bounds.y + (int)Math.round(step.y() * bounds.height));
      return new Point(-100, -100);
    }
    @Override public Dimension getPreferredSize() {
      BufferedImage current = image;
      return current == null ? new Dimension(760, 500) : new Dimension(current.getWidth() + 32, current.getHeight() + 32);
    }
    @Override protected void paintComponent(Graphics graphics) {
      Graphics2D g = (Graphics2D)graphics.create();
      g.setColor(WorkbenchUi.background()); g.fillRect(0, 0, getWidth(), getHeight());
      BufferedImage current = image;
      if (current == null) {
        g.setFont(WorkbenchUi.font()); g.setColor(WorkbenchUi.ink());
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(message, Math.max(12, (getWidth() - metrics.stringWidth(message)) / 2), Math.max(24, getHeight() / 2));
      } else {
        Rectangle bounds = imageBounds();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(current, bounds.x, bounds.y, bounds.width, bounds.height, null);
        g.setColor(WorkbenchUi.line()); g.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);
        if (screen != null) for (VisualGuideCatalog.Step step : screen.steps()) {
          Point point = markerPoint(step.number());
          paintNumber(g, step.number(), point.x, point.y, selectedStep == step.number());
        }
      }
      g.dispose();
    }
    @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(850, 600); }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 32; }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
      return Math.max(32, (orientation == SwingConstants.HORIZONTAL ? visible.width : visible.height) - 32);
    }
    @Override public boolean getScrollableTracksViewportWidth() { return fit; }
    @Override public boolean getScrollableTracksViewportHeight() { return fit; }
  }

  final class StepCard extends JPanel {
    final VisualGuideCatalog.Step step;
    final JTextArea description;
    StepCard(VisualGuideCatalog.Step step) {
      super(new BorderLayout(0, 5)); this.step = step;
      setName("wb.visual.guide.step." + step.number());
      setAlignmentX(Component.LEFT_ALIGNMENT);
      getAccessibleContext().setAccessibleName(step.number() + ". " + step.title() + " · " + step.description());
      JPanel heading = new JPanel(new BorderLayout(7, 0)); heading.setOpaque(false);
      JComponent badge = new JComponent() {
        @Override public Dimension getPreferredSize() { return new Dimension(34, 34); }
        @Override protected void paintComponent(Graphics graphics) { paintNumber((Graphics2D)graphics, step.number(), 17, 17, false); }
      };
      JLabel title = new JLabel(step.title()); title.setForeground(WorkbenchUi.ink()); title.setFont(WorkbenchUi.font().deriveFont(Font.BOLD));
      title.setToolTipText(step.title());
      heading.add(badge, BorderLayout.WEST); heading.add(title, BorderLayout.CENTER);
      description = new JTextArea(step.description()) {
        @Override public Dimension getPreferredSize() {
          int width = Math.max(180, StepCard.this.getWidth() - 24);
          if (getWidth() != width) setSize(width, Short.MAX_VALUE);
          Dimension preferred = super.getPreferredSize();
          return new Dimension(width, Math.max(getFontMetrics(getFont()).getHeight() * 2, preferred.height));
        }
      };
      description.setName("wb.visual.guide.step." + step.number() + ".text");
      description.setEditable(false); description.setLineWrap(true); description.setWrapStyleWord(true);
      description.setOpaque(false); description.setBorder(null); description.setFont(WorkbenchUi.font());
      description.setForeground(WorkbenchUi.ink());
      description.putClientProperty("wb.prose", true);
      add(heading, BorderLayout.NORTH); add(description, BorderLayout.CENTER);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      MouseAdapter select = new MouseAdapter() { @Override public void mouseClicked(MouseEvent event) { selectStep(step.number()); } };
      addMouseListener(select); heading.addMouseListener(select); title.addMouseListener(select); badge.addMouseListener(select); description.addMouseListener(select);
      setFocusable(true);
      getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "chooseStep");
      getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("SPACE"), "chooseStep");
      getActionMap().put("chooseStep", new AbstractAction() {
        @Override public void actionPerformed(ActionEvent event) { selectStep(step.number()); }
      });
      putClientProperty("wb.embedded.style", (Runnable) () -> {
        WorkbenchUi.style(heading); WorkbenchUi.style(description); updateSelection();
      });
      updateSelection();
    }
    void updateSelection() {
      boolean selected = selectedStep == step.number();
      setBackground(selected ? WorkbenchUi.selected() : WorkbenchUi.surface());
      setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(selected ? NUMBER_RED : WorkbenchUi.line(), 1, true),
          BorderFactory.createEmptyBorder(8, 10, 9, 10)));
      repaint();
    }
    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    @Override public Dimension getMinimumSize() { return new Dimension(200, getPreferredSize().height); }
  }

  static final class StepsPanel extends JPanel implements Scrollable {
    StepsPanel() { setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6)); }
    @Override public void doLayout() { ((BoxLayout)getLayout()).invalidateLayout(this); super.doLayout(); }
    @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(310, 650); }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(24, visible.height - 24); }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
  }
}
