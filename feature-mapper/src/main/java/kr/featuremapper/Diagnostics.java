package kr.featuremapper;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.time.LocalTime;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Bounded, per-extension diagnostics. Never retains Throwable, packet bytes or exception messages. */
public final class Diagnostics implements AutoCloseable {
  static final int LIMIT = 1000;
  static final String KEY = "burp.diagnostics";
  record Entry(long id, String time, String area, String type, String location) {
    String text() { return time + " · " + area + " · " + type + (location.isEmpty() ? "" : "\n" + location); }
  }
  private final ArrayDeque<Entry> entries = new ArrayDeque<>();
  private long sequence, discarded, revision;
  private boolean closed;
  private LogPanel panel;

  synchronized void record(String area, Throwable error) {
    if (closed || error == null) return;
    StringBuilder types = new StringBuilder();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable next = error;
    for (int i = 0; next != null && i < 4 && seen.add(next); i++, next = next.getCause()) {
      if (i > 0) types.append(" → ");
      types.append(next.getClass().getSimpleName());
    }
    String location = Arrays.stream(error.getStackTrace())
        .filter(f -> f.getClassName().startsWith("kr.workbench.") || f.getClassName().startsWith("kr.featuremapper.") || f.getClassName().startsWith("kr.payloadrunner."))
        .limit(4).map(StackTraceElement::toString).reduce((a,b) -> a + "\n" + b).orElse("");
    String source = Objects.toString(area, "작업").replaceAll("[\\r\\n\\t]", " ");
    if (source.length() > 80) source = source.substring(0, 80);
    if (entries.size() == LIMIT) { entries.removeFirst(); discarded++; }
    entries.addLast(new Entry(++sequence, LocalTime.now().withNano(0).toString(), source, types.toString(), location));
    revision++;
  }
  synchronized List<Entry> snapshot() { return List.copyOf(entries); }
  synchronized long total() { return sequence; }
  synchronized long discarded() { return discarded; }
  synchronized int size() { return entries.size(); }
  synchronized void clear() { entries.clear(); discarded = 0; revision++; }
  synchronized String text() { return entries.stream().map(Entry::text).reduce((a,b) -> a + "\n\n" + b).orElse(""); }
  static void bind(Component component, Diagnostics diagnostics) {
    if (component instanceof JComponent j) j.putClientProperty(KEY, diagnostics);
    if (component instanceof Container c) for (Component child : c.getComponents()) bind(child, diagnostics);
  }
  public static void failure(Component owner, String area, Throwable error) {
    for (Component c = owner; c != null; c = c.getParent())
      if (c instanceof JComponent j && j.getClientProperty(KEY) instanceof Diagnostics diagnostics) {
        diagnostics.record(area, error); return;
      }
  }
  JPanel panel(Supplier<String> overview) {
    if (panel == null) panel = new LogPanel(overview);
    panel.refresh();
    return panel;
  }
  void refresh() { if (panel != null) panel.refresh(); }
  @Override public void close() {
    synchronized (this) { closed = true; clear(); }
    if (panel != null) {
      panel.timer.stop();
      Runnable release = () -> { panel.table.clearSelection(); panel.shown=List.of(); panel.model.setRowCount(0); panel.details.setText(""); panel.overview.setText(""); };
      if (SwingUtilities.isEventDispatchThread()) release.run(); else SwingUtilities.invokeLater(release);
    }
  }

  private final class LogPanel extends JPanel implements Scrollable {
    final DefaultTableModel model = new DefaultTableModel(new String[]{"시간", "기능·단계", "오류", "코드 위치"}, 0) {
      public boolean isCellEditable(int row, int column) { return false; }
    };
    final JTable table = new JTable(model);
    final JTextArea details = new JTextArea(), overview = new JTextArea(2, 50);
    final JLabel count = new JLabel();
    final javax.swing.Timer timer;
    final Supplier<String> provider;
    List<Entry> shown = List.of();
    long rendered = -1;
    LogPanel(Supplier<String> provider) {
      super(new BorderLayout(8,8)); this.provider = provider; setName("diagnostics.panel");
      table.setName("diagnostics.table"); table.setAutoCreateRowSorter(true); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      details.setName("diagnostics.details"); details.setEditable(false); details.setLineWrap(true); details.setWrapStyleWord(true);
      overview.setEditable(false); overview.setLineWrap(true); overview.setWrapStyleWord(true); overview.setName("diagnostics.overview");
      JButton copy = new JButton("로그 복사"), clear = new JButton("로그 비우기");
      copy.setName("diagnostics.copy"); clear.setName("diagnostics.clear");
      copy.addActionListener(e -> {
        if (!GraphicsEnvironment.isHeadless()) Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text()), null);
      });
      clear.addActionListener(e -> { Diagnostics.this.clear(); refresh(); });
      JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0)); buttons.add(copy); buttons.add(clear);
      JPanel actions = new JPanel(new BorderLayout(8,0)); actions.add(count); actions.add(buttons,BorderLayout.EAST);
      JPanel top = new JPanel(new BorderLayout(0,6)); top.add(actions,BorderLayout.NORTH); top.add(overview);
      add(top,BorderLayout.NORTH);
      JScrollPane tableScroll = new JScrollPane(table); tableScroll.setColumnHeaderView(table.getTableHeader());
      JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,tableScroll,new JScrollPane(details));
      split.setResizeWeight(.68); split.setDividerSize(7); split.setBorder(null); split.setDividerLocation(230);
      split.getTopComponent().setMinimumSize(new Dimension(160,100)); split.getBottomComponent().setMinimumSize(new Dimension(160,70));
      add(split);
      table.getSelectionModel().addListSelectionListener(e -> {
        if (!e.getValueIsAdjusting()) {
          int row = table.getSelectedRow();
          details.setText(row < 0 ? "" : shown.get(table.convertRowIndexToModel(row)).text()); details.setCaretPosition(0);
        }
      });
      timer = new javax.swing.Timer(500,e -> { if (isShowing()) refresh(); }); timer.start();
    }
    void refresh() {
      overview.setText(Objects.toString(provider.get(), ""));
      long version;
      synchronized (Diagnostics.this) { version = revision; }
      if (rendered == version) return;
      Long selected = null;
      int row = table.getSelectedRow();
      if (row >= 0 && table.convertRowIndexToModel(row) < shown.size()) selected = shown.get(table.convertRowIndexToModel(row)).id();
      table.clearSelection(); shown = snapshot(); model.setRowCount(0);
      for (Entry entry : shown) model.addRow(new Object[]{entry.time(),entry.area(),entry.type(),entry.location().lines().findFirst().orElse("")});
      count.setText("보관 " + shown.size() + " / " + LIMIT + " · 누적 오류 " + total() + " · 이전 기록 제외 " + discarded());
      count.setToolTipText(count.getText());
      details.setText("");
      for (int i=0;i<shown.size();i++) if (Objects.equals(selected, shown.get(i).id())) { int view = table.convertRowIndexToView(i); table.setRowSelectionInterval(view,view); break; }
      rendered = version;
    }
    public Dimension getPreferredScrollableViewportSize() { return new Dimension(700,400); }
    public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction) { return 24; }
    public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction) { return 100; }
    public boolean getScrollableTracksViewportWidth() { return true; }
    public boolean getScrollableTracksViewportHeight() { return true; }
  }
}
