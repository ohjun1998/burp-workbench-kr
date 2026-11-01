package kr.featuremapper;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.ComboPopup;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import javax.swing.plaf.basic.BasicTreeUI;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.tree.DefaultTreeCellRenderer;

/** Local, theme-aware styling; never changes Burp's UIManager defaults. */
final class MapperUi {
  private static Font bundled;
  static boolean dark() { return kr.packetui.VisualTheme.dark(); }
  static Color background() {return kr.packetui.VisualTheme.background();}
  static Color surface() {return kr.packetui.VisualTheme.surface();}
  static Color raised() {return kr.packetui.VisualTheme.raised();}
  static Color ink() {return kr.packetui.VisualTheme.ink();}
  static Color line() {return kr.packetui.VisualTheme.line();}
  static Color accent() { return kr.packetui.VisualTheme.accent(); }
  static Color selected() {return kr.packetui.VisualTheme.selected();}
  static synchronized Font font() {
    if(bundled==null) try(var in=MapperUi.class.getResourceAsStream("/fonts/WorkbenchSans-Regular.otf")) { if(in!=null)bundled=Font.createFont(Font.TRUETYPE_FONT,in); } catch(Exception ignored) {}
    return bundled==null?new Font(Font.SANS_SERIF,Font.PLAIN,14):bundled.deriveFont(14f);
  }
  static JPanel row(Component... components) { JPanel p=new JPanel(new FlowLayout(FlowLayout.LEFT,8,4)); for(Component c:components)p.add(c); return p; }
  static JDialog window(Component owner,String title,Component content,Runnable closed) {
    if(GraphicsEnvironment.isHeadless())return null;
    JDialog dialog=new JDialog(SwingUtilities.getWindowAncestor(owner),title,Dialog.ModalityType.MODELESS);
    JPanel body=windowBody(content,dialog::dispose);
    dialog.setContentPane(body);dialog.setMinimumSize(new Dimension(560,300));dialog.setSize(760,480);dialog.setLocationRelativeTo(owner);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    dialog.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){closed.run();}});dialog.setVisible(true);return dialog;
  }
  static JPanel windowBody(Component content,Runnable closeAction) {
    JPanel body=new JPanel(new BorderLayout(8,8));body.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));body.add(content);
    JButton close=new JButton("닫기");close.addActionListener(e->closeAction.run());body.add(row(close),BorderLayout.SOUTH);style(body);return body;
  }
  static void install(JPanel root) {
    java.util.concurrent.atomic.AtomicBoolean pending=new java.util.concurrent.atomic.AtomicBoolean(),applying=new java.util.concurrent.atomic.AtomicBoolean();
    Runnable restore=()->{if(Boolean.TRUE.equals(root.getClientProperty("fm.closed"))||!pending.compareAndSet(false,true))return;SwingUtilities.invokeLater(()->{pending.set(false);if(Boolean.TRUE.equals(root.getClientProperty("fm.closed")))return;applying.set(true);try{style(root);}finally{applying.set(false);}});};
    java.util.function.Consumer<Component> watch=new java.util.function.Consumer<>(){
      public void accept(Component c){if(c instanceof JComponent j){if(Boolean.TRUE.equals(j.getClientProperty("fm.watched")))return;j.putClientProperty("fm.watched",true);j.addPropertyChangeListener("UI",e->{if(!applying.get())restore.run();});}if(c instanceof Container container){for(Component child:container.getComponents())accept(child);container.addContainerListener(new ContainerAdapter(){public void componentAdded(ContainerEvent e){accept(e.getChild());}});}}
    };watch.accept(root);
  }
  static JPanel card(String title,Component body) {
    JPanel p=new JPanel(new BorderLayout(0,0)); p.setName("fm.card");
    JLabel label=kr.packetui.VisualTheme.heading(title); label.putClientProperty("fm.heading",true); label.setBorder(BorderFactory.createEmptyBorder(7,12,7,12));
    p.add(label,BorderLayout.NORTH); p.add(body); p.setBorder(kr.packetui.VisualTheme.border(1,1,1,1)); return p;
  }
  static void split(JSplitPane split,double ratio) {
    split.setContinuousLayout(true); split.setDividerSize(7); split.setBorder(null); split.setResizeWeight(ratio);
    split.getLeftComponent().setMinimumSize(new Dimension(120,100)); split.getRightComponent().setMinimumSize(new Dimension(120,100));
    split.putClientProperty("fm.ratio",ratio);
    split.addComponentListener(new ComponentAdapter(){ boolean placed; public void componentResized(ComponentEvent e){ if(!placed&&split.getWidth()>160&&split.getHeight()>160){placed=true;Object width=split.getClientProperty("fm.initial.width");if(width instanceof Number n)split.setDividerLocation(n.intValue());else split.setDividerLocation(ratio);} }});
  }
  static void style(Component c) {
    if(c instanceof JComponent embedded&&embedded.getClientProperty("fm.embedded.style") instanceof Runnable apply){apply.run();return;}
    if(c instanceof JComponent j)j.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    c.setFont(font().deriveFont(12f)); c.setForeground(ink());
    if(c instanceof JPanel p)p.setBackground("fm.card".equals(p.getName())?surface():background());
    if(c instanceof JLabel l&&Boolean.TRUE.equals(l.getClientProperty("fm.heading"))) {l.setFont(font().deriveFont(Font.BOLD,13f));l.setOpaque(true);l.setBackground(surface());}
    if(c instanceof AbstractButton b && !(b instanceof JCheckBox) && !(b instanceof JRadioButton)) {if(!(b.getUI() instanceof ButtonUi))b.setUI(new ButtonUi());b.setOpaque(false);b.setContentAreaFilled(false);b.setRolloverEnabled(true);b.setBorder(BorderFactory.createEmptyBorder(4,10,4,10));b.setForeground(b.isEnabled()&&Boolean.TRUE.equals(b.getClientProperty("fm.primary"))?Color.WHITE:ink());if(!Boolean.TRUE.equals(b.getClientProperty("fm.primary.watched"))){b.putClientProperty("fm.primary.watched",true);java.beans.PropertyChangeListener paint=e->b.setForeground(b.isEnabled()&&Boolean.TRUE.equals(b.getClientProperty("fm.primary"))?Color.WHITE:ink());b.addPropertyChangeListener("enabled",paint);b.addPropertyChangeListener("fm.primary",paint);}}
    if(c instanceof JCheckBox b){b.setIcon(kr.packetui.VisualTheme.checkIcon());b.setIconTextGap(6);b.setOpaque(false);}
    if(c instanceof JTextField t){t.setBackground(surface());t.setCaretColor(ink());t.setSelectionColor(selected());t.setBorder(kr.packetui.VisualTheme.border(7,10,7,10));}
    if(c instanceof JTextArea t){t.setBackground(surface());t.setCaretColor(ink());t.setSelectionColor(selected());t.setMargin(new Insets(8,10,8,10));if(Boolean.TRUE.equals(t.getClientProperty("fm.packet"))){t.putClientProperty("wb.approved.packet",true);t.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));kr.packetui.PacketStyle.install(t);}}
    if(c instanceof JScrollPane p){p.setBorder(p.getViewport().getView() instanceof JTable?null:BorderFactory.createLineBorder(line()));p.getViewport().setBackground(surface());}
    if(c instanceof JComboBox<?> b){if(!(b.getUI() instanceof ComboUi))b.setUI(new ComboUi());b.setBackground(surface());((ComboUi)b.getUI()).refreshPalette();b.setMaximumRowCount(16);}
    if(c instanceof JScrollBar b){if(!(b.getUI() instanceof ScrollUi))b.setUI(new ScrollUi());b.setUnitIncrement(18);b.setPreferredSize(b.getOrientation()==JScrollBar.VERTICAL?new Dimension(10,0):new Dimension(0,10));}
    if(c instanceof JTable t){if(!(t.getUI() instanceof TableUi))t.setUI(new TableUi());t.setFont(font().deriveFont(12f));t.setRowHeight(35);t.setBackground(surface());t.setForeground(ink());t.setGridColor(line());t.setShowVerticalLines(false);t.setIntercellSpacing(new Dimension(0,1));t.setFillsViewportHeight(true);t.setSelectionBackground(selected());t.setSelectionForeground(ink());t.setDefaultRenderer(Object.class,new Cells());t.setDefaultRenderer(String.class,new Cells());t.getTableHeader().setFont(font().deriveFont(Font.PLAIN,11f));t.getTableHeader().setBackground(raised());t.getTableHeader().setForeground(ink());t.getTableHeader().setPreferredSize(new Dimension(0,31));t.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer(){public Component getTableCellRendererComponent(JTable owner,Object value,boolean selected,boolean focus,int row,int column){super.getTableCellRendererComponent(owner,value,selected,focus,row,column);setFont(font().deriveFont(Font.PLAIN,11f));setBackground(raised());setForeground(ink());setBorder(BorderFactory.createEmptyBorder(0,9,0,9));return this;}});return;}
    if(c instanceof JTree t){if(!(t.getUI() instanceof TreeUi))t.setUI(new TreeUi());t.setRowHeight(40);t.setBackground(surface());t.setFont(font().deriveFont(12f));t.setCellRenderer(new DefaultTreeCellRenderer(){
      public Component getTreeCellRendererComponent(JTree tree,Object value,boolean selected,boolean expanded,boolean leaf,int row,boolean focus){super.getTreeCellRendererComponent(tree,value,selected,expanded,leaf,row,focus);setFont(font().deriveFont(selected?Font.BOLD:Font.PLAIN,12f));setForeground(selected?accent():ink());setBackgroundSelectionColor(MapperUi.selected());setTextSelectionColor(accent());setBackgroundNonSelectionColor(surface());setBorderSelectionColor(null);setIconTextGap(8);setIcon(null);String original=value.toString();setToolTipText(original);if(value instanceof javax.swing.tree.DefaultMutableTreeNode node){if(node.getUserObject() instanceof FeatureMapperWorkspace.FeatureNode)setText(original.replaceFirst("^[^/]+ / ","").replaceFirst(" · /[^\\[]*", " ").replaceFirst(" \\[([0-9]+)건.*$"," ($1)"));else if(original.contains(" / ")){String[] parts=original.split(" / ",2);setText(parts[1]+" ("+node.getChildCount()+")");}}return this;}
    });}
    if(c instanceof JSplitPane s){if(!(s.getUI() instanceof SplitUi)){int location=s.getDividerLocation();s.setUI(new SplitUi());if(location>=0)s.setDividerLocation(location);}s.setBackground(background());s.setBorder(null);s.setDividerSize(7);}
    if(c instanceof JTabbedPane t){if(!(t.getUI() instanceof TabsUi))t.setUI(new TabsUi());t.setBackground(background());t.setForeground(ink());}
    if(c instanceof Container container)for(Component child:container.getComponents())style(child);
  }
  /** Wrap the context row before the query field can receive a negative width. */
  static final class Filters extends JPanel {
    private boolean compact;
    Filters(){super(new BorderLayout(10,4));}
    private void adapt(){if(getWidth()<=0)return;BorderLayout layout=(BorderLayout)getLayout();Component context=layout.getLayoutComponent(compact?BorderLayout.NORTH:BorderLayout.WEST),actions=layout.getLayoutComponent(BorderLayout.EAST);if(context==null||actions==null)return;boolean next=getWidth()<context.getPreferredSize().width+actions.getPreferredSize().width+160;if(next==compact)return;remove(context);compact=next;add(context,compact?BorderLayout.NORTH:BorderLayout.WEST);revalidate();}
    public void doLayout(){adapt();super.doLayout();}
    public Dimension getPreferredSize(){adapt();return super.getPreferredSize();}
  }
  private static final class ButtonUi extends BasicButtonUI {
    public Dimension getPreferredSize(JComponent c){if(Boolean.TRUE.equals(c.getClientProperty("fm.capture")))return new Dimension(94,22);Dimension d=super.getPreferredSize(c);return new Dimension(d.width,Math.max(32,d.height));}
    public void paint(Graphics graphics,JComponent component) {
      AbstractButton b=(AbstractButton)component;Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
      if("fm.tree.toggle".equals(b.getName())){g.setColor(surface());g.fillRect(0,0,b.getWidth(),b.getHeight());g.setFont(font().deriveFont(Font.BOLD,13f));g.setColor(ink());String count=String.valueOf(b.getClientProperty("fm.feature.count")==null?0:b.getClientProperty("fm.feature.count"));g.drawString(b.isSelected()?"기능 · "+count+"개":"기능 펼치기",12,b.getHeight()/2+5);g.dispose();return;}
      if(Boolean.TRUE.equals(b.getClientProperty("fm.capture"))){String text=b.getText();boolean active=!text.equals("OFF");g.setColor(active?(dark()?new Color(32,60,51):new Color(233,247,240)):raised());g.fillRoundRect(0,0,b.getWidth(),b.getHeight(),5,5);g.setColor(active?(dark()?new Color(103,213,164):new Color(11,119,82)):kr.packetui.VisualTheme.muted());g.setFont(font().deriveFont(11f));g.drawString(active?"수집 중 · "+text:"수집 꺼짐",8,(b.getHeight()-g.getFontMetrics().getHeight())/2+g.getFontMetrics().getAscent());g.dispose();return;}
      boolean primary=b.isEnabled()&&Boolean.TRUE.equals(b.getClientProperty("fm.primary"));
      g.setColor(primary?kr.packetui.VisualTheme.primary():b.getModel().isPressed()?selected():b.getModel().isRollover()?raised():surface());g.fillRoundRect(0,0,b.getWidth()-1,b.getHeight()-1,7,7);g.setColor(primary||b.hasFocus()?accent():line());g.drawRoundRect(0,0,b.getWidth()-1,b.getHeight()-1,7,7);g.dispose();super.paint(graphics,component);
    }
  }
  private static final class TableUi extends javax.swing.plaf.basic.BasicTableUI {public void paint(Graphics graphics,JComponent component){super.paint(graphics,component);kr.packetui.VisualTheme.emptyTable(graphics,(JTable)component);}}
  private static final class Cells extends DefaultTableCellRenderer {
    Color badge;boolean selectedRow,first;
    protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(getBackground());g.fillRect(0,0,getWidth(),getHeight());if(badge!=null){g.setColor(badge);int w=Math.min(getWidth()-12,Math.max(34,getFontMetrics(getFont()).stringWidth(getText())+16));g.fillRoundRect(11,(getHeight()-22)/2,w,22,5,5);setBorder(BorderFactory.createEmptyBorder(0,19,0,3));}g.dispose();setOpaque(false);super.paintComponent(graphics);setOpaque(true);if(selectedRow&&first){graphics.setColor(accent());graphics.fillRect(0,0,3,getHeight());}}
    public Component getTableCellRendererComponent(JTable t,Object value,boolean selected,boolean focus,int row,int col){super.getTableCellRendererComponent(t,value,selected,focus,row,col);badge=null;selectedRow=selected;first=col==0;setFont(t.getFont());setHorizontalAlignment(LEFT);setBorder(BorderFactory.createEmptyBorder(0,11,0,11));setBackground(selected?MapperUi.selected():surface());setForeground(ink());String text=getText(),name=t.getColumnName(col);setToolTipText(text);
      if(name.equals("URL"))try{var uri=java.net.URI.create(text);setText(uri.getRawPath()+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery()));}catch(IllegalArgumentException ignored){}
      if(text.matches("[1-5][0-9]{2}")||name.equals("매핑")){boolean good=text.startsWith("2")||text.equals("높음"),neutral=text.equals("401")||text.equals("404");badge=good?(dark()?new Color(32,60,51):new Color(233,247,240)):neutral?raised():(dark()?new Color(67,55,39):new Color(255,244,222));setForeground(good?(dark()?new Color(103,213,164):new Color(11,119,82)):neutral?ink():(dark()?new Color(237,186,101):new Color(171,104,11)));setFont(t.getFont().deriveFont(11f));}return this;}
  }
  private static final class ComboUi extends BasicComboBoxUI {
    void refreshPalette(){
      if(listBox!=null){listBox.setBackground(surface());listBox.setForeground(ink());listBox.setSelectionBackground(selected());listBox.setSelectionForeground(ink());}
      if(editor!=null){editor.setBackground(surface());editor.setForeground(comboBox.isEnabled()?ink():kr.packetui.VisualTheme.muted());if(editor instanceof javax.swing.text.JTextComponent text){text.setCaretColor(ink());text.setSelectionColor(selected());text.setSelectedTextColor(ink());}}
    }
    protected void configureEditor(){super.configureEditor();refreshPalette();}
    protected ComboPopup createPopup(){return new BasicComboPopup(comboBox){public void show(){refreshPalette();super.show();}};}
    public void paint(Graphics graphics,JComponent component){refreshPalette();super.paint(graphics,component);}
    public void paintCurrentValueBackground(Graphics graphics,Rectangle bounds,boolean focus){graphics.setColor(comboBox.getBackground());graphics.fillRect(bounds.x,bounds.y,bounds.width,bounds.height);}
    public void paintCurrentValue(Graphics graphics,Rectangle bounds,boolean focus){
      refreshPalette();boolean marked=focus&&!isPopupVisible(comboBox);
      Component value=comboBox.getRenderer().getListCellRendererComponent(listBox,comboBox.getSelectedItem(),-1,marked,false);
      value.setFont(comboBox.getFont());value.setForeground(comboBox.isEnabled()?ink():kr.packetui.VisualTheme.muted());value.setBackground(marked?selected():comboBox.getBackground());
      Insets in=padding==null?new Insets(0,0,0,0):padding;
      currentValuePane.paintComponent(graphics,value,comboBox,bounds.x+in.left,bounds.y+in.top,Math.max(0,bounds.width-in.left-in.right),Math.max(0,bounds.height-in.top-in.bottom),value instanceof JPanel);
    }
    protected JButton createArrowButton(){return new JButton(){protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.setColor(surface());g.fillRect(0,0,getWidth(),getHeight());g.setColor(ink());g.setStroke(new BasicStroke(1.3f));int x=getWidth()/2,y=getHeight()/2;g.drawPolyline(new int[]{x-4,x,x+4},new int[]{y-2,y+2,y-2},3);g.dispose();}public Dimension getPreferredSize(){return new Dimension(25,30);}};}
  }
  private static final class ScrollUi extends BasicScrollBarUI {
    protected void configureScrollBarColors(){trackColor=background();thumbColor=kr.packetui.VisualTheme.thumb();}
    protected void paintTrack(Graphics graphics,JComponent component,Rectangle bounds){graphics.setColor(background());graphics.fillRect(bounds.x,bounds.y,bounds.width,bounds.height);}
    protected JButton createDecreaseButton(int direction){return arrow(direction);}
    protected JButton createIncreaseButton(int direction){return arrow(direction);}
    private JButton arrow(int direction){return new BasicArrowButton(direction,background(),line(),ink(),line()){public Dimension getPreferredSize(){return new Dimension(0,0);}};}
    protected void paintThumb(Graphics graphics,JComponent c,Rectangle r){if(r.isEmpty()||!scrollbar.isEnabled())return;Graphics2D g=(Graphics2D)graphics.create();g.setColor(isThumbRollover()?accent():kr.packetui.VisualTheme.thumb());g.fillRoundRect(r.x+2,r.y+2,Math.max(2,r.width-4),Math.max(2,r.height-4),7,7);g.dispose();}
  }
  private static final class TreeUi extends BasicTreeUI {
    protected void installDefaults(){super.installDefaults();setExpandedIcon(kr.packetui.VisualTheme.chevron(true));setCollapsedIcon(kr.packetui.VisualTheme.chevron(false));setLeftChildIndent(12);setRightChildIndent(16);}
    protected void paintHorizontalLine(Graphics g,JComponent c,int y,int left,int right){}
    protected void paintVerticalLine(Graphics g,JComponent c,int x,int top,int bottom){}
    protected void paintRow(Graphics g,Rectangle clip,Insets insets,Rectangle bounds,javax.swing.tree.TreePath path,int row,boolean expanded,boolean leaf,boolean hasBeenExpanded){if(tree.isRowSelected(row)){g.setColor(selected());g.fillRect(0,bounds.y,tree.getWidth(),bounds.height);g.setColor(accent());g.fillRect(0,bounds.y,3,bounds.height);}super.paintRow(g,clip,insets,bounds,path,row,expanded,leaf,hasBeenExpanded);}
  }
  private static final class SplitUi extends BasicSplitPaneUI {
    public BasicSplitPaneDivider createDefaultDivider(){return new BasicSplitPaneDivider(this){public void paint(Graphics g){g.setColor(background());g.fillRect(0,0,getWidth(),getHeight());if(Boolean.TRUE.equals(splitPane.getClientProperty("wb.workflow.fixedLayout")))return;g.setColor(line());if(splitPane.getOrientation()==JSplitPane.HORIZONTAL_SPLIT)g.drawLine(getWidth()/2,0,getWidth()/2,getHeight());else g.drawLine(0,getHeight()/2,getWidth(),getHeight()/2);}};}
  }
  private static final class TabsUi extends BasicTabbedPaneUI {
    protected Insets getTabInsets(int placement,int index){return new Insets(6,10,6,10);}
    protected Insets getTabAreaInsets(int placement){return new Insets(0,0,10,0);}
    protected Insets getContentBorderInsets(int placement){return new Insets(0,0,0,0);}
    protected int calculateTabHeight(int placement,int index,int fontHeight){return 30;}
    protected void paintText(Graphics g,int placement,Font f,FontMetrics metrics,int index,String title,Rectangle bounds,boolean selected){g.setFont(f.deriveFont(selected?Font.BOLD:Font.PLAIN));g.setColor(selected?accent():ink());g.drawString(title,bounds.x,bounds.y+metrics.getAscent());}
    protected void paintTabBackground(Graphics g,int placement,int index,int x,int y,int w,int h,boolean selected){g.setColor(selected?MapperUi.selected():background());g.fillRoundRect(x+1,y+1,w-2,h-2,7,7);}
    protected void paintTabBorder(Graphics g,int placement,int index,int x,int y,int w,int h,boolean selected){if(tabPane.hasFocus()&&selected){g.setColor(accent());g.drawRoundRect(x+1,y+1,w-3,h-3,7,7);}}
    protected void paintContentBorder(Graphics g,int placement,int selected){}
  }
  private record FolderIcon(boolean leaf) implements Icon {
    public int getIconWidth(){return 22;}public int getIconHeight(){return 22;}
    public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);if(leaf){g.setColor(ink());g.drawRoundRect(x+5,y+3,11,16,2,2);g.drawLine(x+8,y+8,x+13,y+8);}else{g.setColor(new Color(246,147,46));g.fillRoundRect(x+2,y+5,18,14,3,3);g.fillRoundRect(x+3,y+3,8,6,2,2);g.setColor(new Color(255,185,79));g.fillRoundRect(x+2,y+9,18,10,3,3);}g.dispose();}
  }
  static final class Search extends JTextField {
    Search(){super(16);setToolTipText("URL, 메서드, 매핑 내용 검색");}
    protected void paintComponent(Graphics graphics){super.paintComponent(graphics);if(getText().isEmpty()&&!hasFocus()){Graphics2D g=(Graphics2D)graphics.create();g.setFont(font());g.setColor(dark()?new Color(170,183,200):new Color(105,117,135));g.drawString("URL, 메서드, 매핑 검색",10,(getHeight()-g.getFontMetrics().getHeight())/2+g.getFontMetrics().getAscent());g.dispose();}}
  }
}
