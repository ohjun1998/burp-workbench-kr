package kr.workbench;

import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import javax.swing.*;
import javax.swing.plaf.basic.*;
import javax.swing.table.DefaultTableCellRenderer;

/** Per-component painting only. No UIManager defaults or Burp-wide settings are changed. */
final class WorkbenchSkin {
  private WorkbenchSkin() {}

  static void antialias(Graphics2D g) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
  }

  static final class SearchField extends JTextField {
    private final String placeholder;
    SearchField(int columns) { this(columns,"URL, 메서드, 상태 코드 검색"); }
    SearchField() { this(0); }
    SearchField(int columns,String placeholder) { super(columns); this.placeholder=placeholder; }
    @Override public Dimension getPreferredSize() { Dimension d=super.getPreferredSize();return new Dimension(d.width, Math.max(40,d.height)); }
    @Override protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      if (getText().isEmpty() && !hasFocus()) {
        Graphics2D g=(Graphics2D)graphics.create();antialias(g);
        g.setColor(WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));
        g.setFont(WorkbenchUi.font());
        String text=placeholder;
        FontMetrics fm=g.getFontMetrics();
        Shape old=g.getClip();g.clipRect(12,0,Math.max(0,getWidth()-24),getHeight());
        g.drawString(text,12,(getHeight()-fm.getHeight())/2+fm.getAscent());g.setClip(old);g.dispose();
      }
    }
  }

  static void button(AbstractButton button) {
    if (!(button.getUI() instanceof ButtonUi)) button.setUI(new ButtonUi());
    button.setOpaque(false);
    button.setContentAreaFilled(false);
    button.setRolloverEnabled(true);
    button.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
  }

  private static final class ButtonUi extends BasicButtonUI {
    @Override public void update(Graphics graphics, JComponent component) { paint(graphics, component); }
    @Override public Dimension getPreferredSize(JComponent component) {
      if (Boolean.TRUE.equals(component.getClientProperty("wb.capture")))
        return new Dimension(174, 38);
      Dimension size = super.getPreferredSize(component);
      return new Dimension(size.width, Math.max(32, size.height));
    }
    @Override public void paint(Graphics graphics, JComponent component) {
      AbstractButton button = (AbstractButton) component;
      Graphics2D g = (Graphics2D) graphics.create();
      antialias(g);
      if (Boolean.TRUE.equals(button.getClientProperty("wb.capture"))) {
        capture(g, (JButton)button);
      } else {
        Color fill = button.getBackground();
        if (button.isEnabled() && (button.getModel().isRollover() || button.isSelected())) fill = WorkbenchUi.selected();
        if (button.getModel().isPressed()) fill = WorkbenchUi.selected();
        g.setColor(fill);
        g.fillRoundRect(0, 0, button.getWidth()-1, button.getHeight()-1, 8, 8);
        g.setColor(button.hasFocus() ? WorkbenchUi.accent() : WorkbenchUi.line());
        g.drawRoundRect(0, 0, button.getWidth()-1, button.getHeight()-1, 8, 8);
        super.paint(g, component);
      }
      g.dispose();
    }
    @Override protected void paintFocus(Graphics g, AbstractButton b, Rectangle view, Rectangle text, Rectangle icon) {}
  }

  static void combo(JComboBox<?> combo) {
    if(!(combo.getUI() instanceof ComboUi))combo.setUI(new ComboUi());
  }
  private static final class ComboUi extends BasicComboBoxUI {
    @Override public Dimension getPreferredSize(JComponent component){Dimension size=super.getPreferredSize(component);return new Dimension(size.width,Math.max(32,size.height));}
    @Override protected JButton createArrowButton(){return new BasicArrowButton(SwingConstants.SOUTH,WorkbenchUi.raised(),WorkbenchUi.line(),WorkbenchUi.ink(),WorkbenchUi.line());}
  }
  static void scroll(JScrollBar bar) {
    if(!(bar.getUI() instanceof ScrollUi))bar.setUI(new ScrollUi());
    bar.setUnitIncrement(18);
  }
  private static final class ScrollUi extends BasicScrollBarUI {
    @Override protected void configureScrollBarColors(){trackColor=WorkbenchUi.background();thumbColor=WorkbenchUi.line();}
    @Override protected JButton createDecreaseButton(int direction){return arrow(direction);}
    @Override protected JButton createIncreaseButton(int direction){return arrow(direction);}
    private JButton arrow(int direction){return new BasicArrowButton(direction,WorkbenchUi.background(),WorkbenchUi.line(),WorkbenchUi.ink(),WorkbenchUi.line()){@Override public Dimension getPreferredSize(){return new Dimension(0,0);}};}
    @Override protected void paintThumb(Graphics graphics,JComponent component,Rectangle bounds){if(bounds.isEmpty()||!scrollbar.isEnabled())return;Graphics2D g=(Graphics2D)graphics.create();antialias(g);g.setColor(isThumbRollover()?WorkbenchUi.accent():WorkbenchUi.line());g.fillRoundRect(bounds.x+2,bounds.y+2,Math.max(2,bounds.width-4),Math.max(2,bounds.height-4),7,7);g.dispose();}
  }

  static Color captureColor(String mode) {
    return switch (mode) {
      case "OFF" -> WorkbenchUi.dark() ? new Color(255, 110, 120) : new Color(192, 53, 67);
      default -> WorkbenchUi.dark() ? new Color(255, 179, 66) : new Color(162, 96, 14);
    };
  }

  private static void capture(Graphics2D g, JButton button) {
    String mode = button.getText();
    String label = mode + "  |  " + switch (mode) {
      case "OFF" -> "수집 중지";
      default -> "범위 수집";
    };
    Color color = captureColor(mode);
    g.setColor(WorkbenchUi.dark() ? new Color(color.getRed()/7, color.getGreen()/7+9, color.getBlue()/7+5)
        : new Color((color.getRed()+2295)/10, (color.getGreen()+2295)/10, (color.getBlue()+2295)/10));
    g.fillRoundRect(0, 0, button.getWidth()-1, button.getHeight()-1, 10, 10);
    g.setColor(color.darker());
    g.drawRoundRect(0, 0, button.getWidth()-1, button.getHeight()-1, 10, 10);
    if (button.hasFocus()) { g.setColor(color); g.drawRoundRect(2, 2, button.getWidth()-5, button.getHeight()-5, 8, 8); }
    int cy = button.getHeight()/2;
    g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 40));
    g.fillOval(11, cy-8, 16, 16);
    g.setColor(color); g.fillOval(14, cy-5, 10, 10);
    g.setFont(WorkbenchUi.font().deriveFont(Font.BOLD));
    g.setColor(WorkbenchUi.dark() ? WorkbenchUi.ink() : color.darker());
    g.drawString(label, 34, (button.getHeight()-g.getFontMetrics().getHeight())/2+g.getFontMetrics().getAscent());
  }

  static void split(JSplitPane split) {
    if (!(split.getUI() instanceof SplitUi)) split.setUI(new SplitUi());
  }

  private static final class SplitUi extends BasicSplitPaneUI {
    @Override public BasicSplitPaneDivider createDefaultDivider() {
      BasicSplitPaneDivider divider = new BasicSplitPaneDivider(this) {
        @Override public void paint(Graphics graphics) {
          Graphics2D g = (Graphics2D) graphics.create();
          g.setColor(WorkbenchUi.background()); g.fillRect(0, 0, getWidth(), getHeight());
          g.setColor(WorkbenchUi.line());
          if (splitPane.getOrientation() == JSplitPane.HORIZONTAL_SPLIT)
            g.drawLine(getWidth()/2, 7, getWidth()/2, getHeight()-7);
          else g.drawLine(7, getHeight()/2, getWidth()-7, getHeight()/2);
          g.dispose();
        }
      };
      divider.setBorder(null);
      divider.addMouseListener(new MouseAdapter() {
        @Override public void mouseReleased(MouseEvent event) {
          int size = (splitPane.getOrientation()==JSplitPane.HORIZONTAL_SPLIT ? splitPane.getWidth() : splitPane.getHeight())-splitPane.getDividerSize();
          if (size > 160 && splitPane.getClientProperty("wb.split.ratio") != null) {
            double ratio = Math.max(.1, Math.min(.9, (double)splitPane.getDividerLocation()/size));
            splitPane.putClientProperty("wb.split.ratio", ratio); splitPane.setResizeWeight(ratio);
          }
        }
      });
      return divider;
    }
  }

  private static void detachTabViewport(JTabbedPane tabs) {
    // Remove listeners owned by the discarded tab UI, including queued viewport resize events.
    for(Component child:tabs.getComponents())
      if(child instanceof JViewport viewport && child instanceof javax.swing.plaf.UIResource)
        for(var listener:viewport.getChangeListeners())viewport.removeChangeListener(listener);
  }

  static void tabs(JTabbedPane tabs) {
    if(tabs instanceof FolderNavigation.Pages)return;
    if(!(tabs.getUI() instanceof TabsUi)) {
      detachTabViewport(tabs);
      tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
      tabs.setUI(new TabsUi());
    }
  }

  private static final class TabsUi extends BasicTabbedPaneUI {
    @Override public void uninstallUI(JComponent component) {
      detachTabViewport((JTabbedPane)component);
      super.uninstallUI(component);
    }
    @Override protected void paintTabBackground(Graphics graphics,int placement,int index,int x,int y,int width,int height,boolean selected) {
      graphics.setColor(selected?WorkbenchUi.selected():WorkbenchUi.raised());graphics.fillRect(x,y,width,height);
    }
    @Override protected void paintTabBorder(Graphics graphics,int placement,int index,int x,int y,int width,int height,boolean selected) {
      graphics.setColor(selected?WorkbenchUi.accent():WorkbenchUi.line());graphics.drawLine(x,y+height-1,x+width,y+height-1);
    }
    @Override protected void paintContentBorder(Graphics graphics,int placement,int selected) {}
  }

  static void table(JTable table) {
    if (!(table.getUI().getClass() == BasicTableUI.class)) table.setUI(new BasicTableUI());
    table.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer() {
      @Override public Component getTableCellRendererComponent(JTable owner, Object value, boolean selected, boolean focus, int row, int column) {
        super.getTableCellRendererComponent(owner, value, selected, focus, row, column);
        setFont(WorkbenchUi.font().deriveFont(Font.BOLD)); setForeground(WorkbenchUi.ink());
        setBackground(WorkbenchUi.raised());
        setIcon(null);
        if(owner.getRowSorter()!=null && !owner.getRowSorter().getSortKeys().isEmpty()) {
          var key=owner.getRowSorter().getSortKeys().get(0);
          if(key.getColumn()==owner.convertColumnIndexToModel(column))
            setIcon(UIManager.getIcon(key.getSortOrder()==SortOrder.ASCENDING?"Table.ascendingSortIcon":"Table.descendingSortIcon"));
        }
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,WorkbenchUi.line()),BorderFactory.createEmptyBorder(0,12,0,12)));
        return this;
      }
    });
  }

  /** Reassert after registration, look-and-feel replacement and later host customization. */
  static void install(JComponent root, Runnable after) {
    Runnable apply = new Runnable() {
      boolean pending;
      public void run() {
        if (pending) return;
        pending = true;
        SwingUtilities.invokeLater(() -> {
          try {
            if (Boolean.TRUE.equals(root.getClientProperty("wb.skin.closed"))) return;
            WorkbenchUi.style(root); after.run(); root.revalidate(); root.repaint();
          } finally { pending = false; }
        });
      }
    };
    root.putClientProperty("wb.skin.refresh", apply);
    watch(root, apply);
    root.addPropertyChangeListener(event->{if("background".equals(event.getPropertyName())||"font".equals(event.getPropertyName()))apply.run();});
    root.addHierarchyListener(event -> {
      if ((event.getChangeFlags() & (HierarchyEvent.SHOWING_CHANGED|HierarchyEvent.DISPLAYABILITY_CHANGED)) != 0 && root.isDisplayable()) apply.run();
    });
    apply.run();
  }

  private static void watch(Component component, Runnable apply) {
    if (component instanceof JComponent j) {
      if (Boolean.TRUE.equals(j.getClientProperty("wb.nativeEditor")) || Boolean.TRUE.equals(j.getClientProperty("wb.skin.watched"))) return;
      j.putClientProperty("wb.skin.watched", true);
      j.addPropertyChangeListener((PropertyChangeEvent event) -> {
        String key = event.getPropertyName();
        if ("UI".equals(key) || "cellRenderer".equals(key) || "rowHeight".equals(key)) apply.run();
      });
    }
    if (component instanceof Container container) {
      for (Component child : container.getComponents()) watch(child, apply);
      container.addContainerListener(new ContainerAdapter() {
        @Override public void componentAdded(ContainerEvent event) { watch(event.getChild(), apply); }
      });
    }
  }
}
