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
    @Override public Dimension getPreferredSize() { Dimension d=super.getPreferredSize();return new Dimension(d.width, Math.max(32,d.height)); }
    @Override protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      Graphics2D iconGraphics=(Graphics2D)graphics.create();iconGraphics.setColor(WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));if(getClientProperty("wb.search.placeholder")!=null){Graphics2D small=(Graphics2D)iconGraphics.create();small.translate(10,(getHeight()-9)/2);small.scale(.5,.5);new ApprovedTrafficIcon("exposure",WorkbenchUi.ink()).paintIcon(this,small,0,0);small.dispose();}else WorkspaceIcons.of("search").paintIcon(this,iconGraphics,9,(getHeight()-16)/2);iconGraphics.dispose();
      if (getText().isEmpty() && !hasFocus()) {
        Graphics2D g=(Graphics2D)graphics.create();antialias(g);
        g.setColor(WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));
        g.setFont(getFont());
        String text=getClientProperty("wb.search.placeholder") instanceof String custom?custom:placeholder;
        FontMetrics fm=g.getFontMetrics();
        Shape old=g.getClip();g.clipRect(30,0,Math.max(0,getWidth()-40),getHeight());
        g.drawString(text,30,(getHeight()-fm.getHeight())/2+fm.getAscent());g.setClip(old);g.dispose();
      }
    }
  }

  static void button(AbstractButton button) {
    if (!(button.getUI() instanceof ButtonUi)) button.setUI(new ButtonUi());
    button.setOpaque(false);
    button.setContentAreaFilled(false);
    button.setRolloverEnabled(true);
    String label=button.getText()==null?"":button.getText();if(button.getIcon()==null&&!label.startsWith("<html")&&!(button instanceof JToggleButton)&&!Boolean.TRUE.equals(button.getClientProperty("wb.segment.button"))){String kind=label.contains("설정")?"gear":label.contains("Repeater")?"play":label.equals("중지")?"stop":label.contains("증거")&&label.contains("추가")?"plus":null;if(kind!=null)button.setIcon(WorkspaceIcons.of(kind));}
    button.setBorder(Boolean.TRUE.equals(button.getClientProperty("wb.compact.button"))
        ? BorderFactory.createEmptyBorder(5, 9, 5, 9)
        : BorderFactory.createEmptyBorder(4, 10, 4, 10));
  }

  private static final class ButtonUi extends BasicButtonUI {
    @Override public void update(Graphics graphics, JComponent component) { paint(graphics, component); }
    @Override public Dimension getPreferredSize(JComponent component) {
      if (Boolean.TRUE.equals(component.getClientProperty("wb.capture")))
        return new Dimension(132, 34);
      if (Boolean.TRUE.equals(component.getClientProperty("wb.analysis.lamp")))
        return new Dimension(132, 34);
      Dimension size = super.getPreferredSize(component);
      return new Dimension(size.width, Math.max(32, size.height));
    }
    @Override public void paint(Graphics graphics, JComponent component) {
      AbstractButton button = (AbstractButton) component;
      Graphics2D g = (Graphics2D) graphics.create();
      antialias(g);
      if (Boolean.TRUE.equals(button.getClientProperty("wb.capture"))) {
        capture(g, (JButton)button);
      } else if (Boolean.TRUE.equals(button.getClientProperty("wb.analysis.lamp"))) {
        lamp(g, button, button.getText(), button.isSelected()
            ? (WorkbenchUi.dark() ? new Color(57, 222, 125) : new Color(20, 130, 70))
            : captureColor("OFF"));
      } else {
        boolean primary=button.isEnabled()&&Boolean.TRUE.equals(button.getClientProperty("wb.primary"));
        Color fill = button.getBackground();
        if (Boolean.TRUE.equals(button.getClientProperty("wb.state.toggle"))) {
          if(button.getModel().isRollover() || button.getModel().isPressed())fill=fill.brighter();
        } else {
          if (!primary && button.isEnabled() && (button.getModel().isRollover() || button.isSelected())) fill = WorkbenchUi.selected();
          if (button.getModel().isPressed()) fill = primary?kr.packetui.VisualTheme.primary().darker():WorkbenchUi.selected();
        }
        boolean segment=Boolean.TRUE.equals(button.getClientProperty("wb.segment.button"));
        if(Boolean.TRUE.equals(button.getClientProperty("wb.filter.segment"))){g.setColor(button.isSelected()?WorkbenchUi.surface():WorkbenchUi.raised());g.fillRoundRect(0,0,button.getWidth()-1,button.getHeight()-1,6,6);if(button.isSelected()||button.hasFocus()){g.setColor(WorkbenchUi.accent());g.drawRoundRect(0,0,button.getWidth()-1,button.getHeight()-1,6,6);}super.paint(g,component);g.dispose();return;}
        if(!segment||button.getModel().isRollover()||button.getModel().isPressed()) {
          g.setPaint(primary&&!button.getModel().isPressed()?new GradientPaint(0,0,kr.packetui.VisualTheme.primary(),0,button.getHeight(),kr.packetui.VisualTheme.primary()):new GradientPaint(0,0,fill,0,button.getHeight(),fill));
          g.fillRoundRect(0, 0, button.getWidth()-1, button.getHeight()-1, 8, 8);
        }
        if(!segment||button.hasFocus()) {
          g.setColor(button.hasFocus() ? WorkbenchUi.accent() : primary ? kr.packetui.VisualTheme.primary() : WorkbenchUi.line());
          g.drawRoundRect(0, 0, button.getWidth()-1, button.getHeight()-1, 8, 8);
        }
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
    @Override protected JButton createArrowButton(){JButton arrow=new JButton(){protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();antialias(g);g.setColor(WorkbenchUi.surface());g.fillRect(0,0,getWidth(),getHeight());g.setColor(WorkbenchUi.ink());g.setStroke(new BasicStroke(1.3f));int x=getWidth()/2,y=getHeight()/2;g.drawPolyline(new int[]{x-4,x,x+4},new int[]{y-2,y+2,y-2},3);g.dispose();}};arrow.putClientProperty("wb.nativeEditor",true);arrow.setBorder(null);arrow.setPreferredSize(new Dimension(25,30));return arrow;}
  }
  static void scroll(JScrollBar bar) {
    if(!(bar.getUI() instanceof ScrollUi))bar.setUI(new ScrollUi());
    bar.setUnitIncrement(18);bar.setPreferredSize(bar.getOrientation()==JScrollBar.VERTICAL?new Dimension(10,0):new Dimension(0,10));
  }
  private static final class ScrollUi extends BasicScrollBarUI {
    @Override protected void configureScrollBarColors(){trackColor=WorkbenchUi.background();thumbColor=kr.packetui.VisualTheme.thumb();}
    @Override protected JButton createDecreaseButton(int direction){return arrow(direction);}
    @Override protected JButton createIncreaseButton(int direction){return arrow(direction);}
    private JButton arrow(int direction){return new BasicArrowButton(direction,WorkbenchUi.background(),WorkbenchUi.line(),WorkbenchUi.ink(),WorkbenchUi.line()){@Override public Dimension getPreferredSize(){return new Dimension(0,0);}};}
    @Override protected void paintThumb(Graphics graphics,JComponent component,Rectangle bounds){if(bounds.isEmpty()||!scrollbar.isEnabled())return;Graphics2D g=(Graphics2D)graphics.create();antialias(g);g.setColor(isThumbRollover()?WorkbenchUi.accent():kr.packetui.VisualTheme.thumb());g.fillRoundRect(bounds.x+2,bounds.y+2,Math.max(2,bounds.width-4),Math.max(2,bounds.height-4),7,7);g.dispose();}
  }

  static Color captureColor(String mode) {
    return switch (mode) {
      case "OFF" -> WorkbenchUi.dark() ? new Color(255, 110, 120) : new Color(192, 53, 67);
      default -> WorkbenchUi.accent();
    };
  }

  private static void capture(Graphics2D g, JButton button) {
    String mode = button.getText();
    String label = "수집: " + mode;
    Color color = captureColor(mode);
    lamp(g, button, label, color);
  }

  private static void lamp(Graphics2D g, AbstractButton button, String label, Color color) {
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
    g.setFont(WorkbenchUi.font());
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
            g.drawLine(getWidth()/2, getHeight()/2-14, getWidth()/2, getHeight()/2+14);
          else g.drawLine(getWidth()/2-14, getHeight()/2, getWidth()/2+14, getHeight()/2);
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
    if(Boolean.TRUE.equals(tabs.getClientProperty("wb.external.tabs"))) {
      if(!(tabs.getUI() instanceof ContentTabsUi)) {
        detachTabViewport(tabs);
        tabs.setTabLayoutPolicy(JTabbedPane.WRAP_TAB_LAYOUT);
        tabs.setUI(new ContentTabsUi());
      }
      return;
    }
    if(!(tabs.getUI() instanceof TabsUi)) {
      detachTabViewport(tabs);
      tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
      tabs.setUI(new TabsUi());
    }
  }

  /** The JS result strip owns its scroll buttons, independent of overflow visibility. */
  private static final class ContentTabsUi extends BasicTabbedPaneUI {
    @Override protected int calculateTabAreaHeight(int placement, int runs, int height) { return 0; }
    @Override protected int calculateTabAreaWidth(int placement, int runs, int width) { return 0; }
    @Override protected void paintTabArea(Graphics g, int placement, int selected) {}
    @Override protected void paintContentBorder(Graphics g, int placement, int selected) {}
    @Override protected Insets getContentBorderInsets(int placement) { return new Insets(0, 0, 0, 0); }
  }

  private static final class TabsUi extends BasicTabbedPaneUI {
    protected Insets getTabInsets(int placement,int index){return new Insets(6,10,6,10);}
    protected void paintText(Graphics g,int placement,Font font,FontMetrics metrics,int index,String title,Rectangle rect,boolean selected){g.setFont(font.deriveFont(selected?Font.BOLD:Font.PLAIN));g.setColor(selected?WorkbenchUi.accent():WorkbenchUi.ink());g.drawString(title,rect.x,rect.y+metrics.getAscent());}
    protected Insets getContentBorderInsets(int placement){return new Insets(1,0,0,0);}
    @Override public void uninstallUI(JComponent component) {
      detachTabViewport((JTabbedPane)component);
      super.uninstallUI(component);
    }
    @Override protected void paintTabBackground(Graphics graphics,int placement,int index,int x,int y,int width,int height,boolean selected) {
      graphics.setColor(selected?WorkbenchUi.selected():WorkbenchUi.surface());graphics.fillRoundRect(x+2,y+2,width-4,height-4,7,7);
    }
    @Override protected void paintTabBorder(Graphics graphics,int placement,int index,int x,int y,int width,int height,boolean selected) {
      if(tabPane.hasFocus()&&selected){graphics.setColor(WorkbenchUi.accent());graphics.drawRoundRect(x+2,y+2,width-5,height-5,7,7);}
    }
    @Override protected void paintContentBorder(Graphics graphics,int placement,int selected) {}
  }

  private static final class TableUi extends BasicTableUI {public void paint(Graphics graphics,JComponent component){super.paint(graphics,component);kr.packetui.VisualTheme.emptyTable(graphics,(JTable)component);}}

  static void progress(JProgressBar bar){bar.setUI(new BasicProgressBarUI(){protected void paintDeterminate(Graphics graphics,JComponent c){Graphics2D g=(Graphics2D)graphics.create();antialias(g);g.setColor(WorkbenchUi.raised());g.fillRect(0,0,c.getWidth(),c.getHeight());g.setFont(WorkbenchUi.font());g.setColor(WorkbenchUi.ink());String title=bar.getString();g.drawString(title,Math.max(0,c.getWidth()-g.getFontMetrics().stringWidth(title)),17);g.setColor(WorkbenchUi.line());g.fillRoundRect(0,c.getHeight()-7,c.getWidth(),6,6,6);g.setColor(bar.getValue()>=bar.getMaximum()?WorkbenchUi.toneInk(WorkbenchUi.Tone.SUCCESS):WorkbenchUi.accent());g.fillRoundRect(0,c.getHeight()-7,(int)(c.getWidth()*bar.getPercentComplete()),6,6,6);g.dispose();}});bar.setBorder(null);}

  static void table(JTable table) {
    if (!(table.getUI() instanceof TableUi)) table.setUI(new TableUi());
    table.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer() {
      @Override public Component getTableCellRendererComponent(JTable owner, Object value, boolean selected, boolean focus, int row, int column) {
        super.getTableCellRendererComponent(owner, value, selected, focus, row, column);
        boolean compact=Boolean.TRUE.equals(owner.getClientProperty("wb.table.compact"));
        setFont(WorkbenchUi.font().deriveFont(Font.PLAIN,WorkbenchUi.displaySize(11f))); setForeground(kr.packetui.VisualTheme.muted());
        setBackground(WorkbenchUi.raised());
        setIcon(null);
        if(owner.getRowSorter()!=null && !owner.getRowSorter().getSortKeys().isEmpty()) {
          var key=owner.getRowSorter().getSortKeys().get(0);
          if(key.getColumn()==owner.convertColumnIndexToModel(column))
            setIcon(UIManager.getIcon(key.getSortOrder()==SortOrder.ASCENDING?"Table.ascendingSortIcon":"Table.descendingSortIcon"));
        }
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,WorkbenchUi.line()),BorderFactory.createEmptyBorder(0,compact?5:12,0,compact?5:12)));
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
      // Embedded tools own their delegates and restoration listeners. Watching
      // their children here makes the two skins restore each other repeatedly.
      if (j.getClientProperty("wb.embedded.style") instanceof Runnable) return;
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
