package kr.featuremapper;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
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
  static boolean dark() { Color c=UIManager.getColor("Panel.background"); return c!=null&&c.getRed()+c.getGreen()+c.getBlue()<360; }
  static Color background() { return dark()?new Color(17,22,29):new Color(246,249,253); }
  static Color surface() { return dark()?new Color(23,29,37):Color.WHITE; }
  static Color raised() { return dark()?new Color(31,39,49):new Color(245,249,253); }
  static Color ink() { return dark()?new Color(226,232,240):new Color(36,45,60); }
  static Color line() { return dark()?new Color(51,61,74):new Color(222,231,241); }
  static Color accent() { return kr.packetui.VisualTheme.accent(); }
  static Color selected() { return dark()?new Color(88,54,39):new Color(255,235,224); }
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
    JLabel label=kr.packetui.VisualTheme.heading(title); label.putClientProperty("fm.heading",true); label.setBorder(BorderFactory.createEmptyBorder(8,10,6,10));
    p.add(label,BorderLayout.NORTH); p.add(body); p.setBorder(kr.packetui.VisualTheme.border(1,1,1,1)); return p;
  }
  static void split(JSplitPane split,double ratio) {
    split.setContinuousLayout(true); split.setDividerSize(7); split.setBorder(null); split.setResizeWeight(ratio);
    split.getLeftComponent().setMinimumSize(new Dimension(120,100)); split.getRightComponent().setMinimumSize(new Dimension(120,100));
    split.putClientProperty("fm.ratio",ratio);
    split.addComponentListener(new ComponentAdapter(){ boolean placed; public void componentResized(ComponentEvent e){ if(!placed&&split.getWidth()>160&&split.getHeight()>160){placed=true;Object width=split.getClientProperty("fm.initial.width");if(width instanceof Number n)split.setDividerLocation(n.intValue());else split.setDividerLocation(ratio);} }});
  }
  static void style(Component c) {
    if(c instanceof JComponent j)j.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    c.setFont(font()); c.setForeground(ink());
    if(c instanceof JPanel p)p.setBackground("fm.card".equals(p.getName())?surface():background());
    if(c instanceof JLabel l&&Boolean.TRUE.equals(l.getClientProperty("fm.heading"))) {l.setFont(font().deriveFont(Font.BOLD,16f));l.setOpaque(true);l.setBackground(raised());}
    if(c instanceof AbstractButton b && !(b instanceof JCheckBox) && !(b instanceof JRadioButton)) {b.setUI(new ButtonUi());b.setOpaque(false);b.setContentAreaFilled(false);b.setRolloverEnabled(true);b.setBorder(BorderFactory.createEmptyBorder(7,12,7,12));}
    if(c instanceof JCheckBox b){b.setIcon(kr.packetui.VisualTheme.checkIcon());b.setIconTextGap(6);b.setOpaque(false);}
    if(c instanceof JTextField t){t.setBackground(surface());t.setCaretColor(ink());t.setSelectionColor(selected());t.setBorder(kr.packetui.VisualTheme.border(7,10,7,10));}
    if(c instanceof JTextArea t){t.setBackground(surface());t.setCaretColor(ink());t.setSelectionColor(selected());t.setMargin(new Insets(8,10,8,10));if(Boolean.TRUE.equals(t.getClientProperty("fm.packet"))){t.setFont(new Font(Font.MONOSPACED,Font.PLAIN,14));kr.packetui.PacketStyle.install(t);}}
    if(c instanceof JScrollPane p){p.setBorder(BorderFactory.createLineBorder(line()));p.getViewport().setBackground(surface());}
    if(c instanceof JComboBox<?> b){if(!(b.getUI() instanceof ComboUi))b.setUI(new ComboUi());b.setBackground(surface());b.setMaximumRowCount(16);}
    if(c instanceof JScrollBar b){if(!(b.getUI() instanceof ScrollUi))b.setUI(new ScrollUi());b.setUnitIncrement(18);b.setPreferredSize(b.getOrientation()==JScrollBar.VERTICAL?new Dimension(10,0):new Dimension(0,10));}
    if(c instanceof JTable t){if(!(t.getUI() instanceof TableUi))t.setUI(new TableUi());t.setFont(font());t.setRowHeight(32);t.setBackground(surface());t.setForeground(ink());t.setGridColor(line());t.setShowVerticalLines(true);t.setFillsViewportHeight(true);t.setSelectionBackground(selected());t.setSelectionForeground(ink());t.setDefaultRenderer(Object.class,new Cells());t.setDefaultRenderer(String.class,new Cells());t.getTableHeader().setFont(font().deriveFont(Font.BOLD,12f));t.getTableHeader().setBackground(raised());t.getTableHeader().setForeground(ink());t.getTableHeader().setPreferredSize(new Dimension(0,32));t.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer(){public Component getTableCellRendererComponent(JTable owner,Object value,boolean selected,boolean focus,int row,int column){super.getTableCellRendererComponent(owner,value,selected,focus,row,column);setFont(font().deriveFont(Font.BOLD,12f));setBackground(raised());setForeground(ink());setBorder(BorderFactory.createEmptyBorder(0,9,0,9));return this;}});return;}
    if(c instanceof JTree t){if(!(t.getUI() instanceof TreeUi))t.setUI(new TreeUi());t.setRowHeight(40);t.setBackground(surface());t.setFont(font());t.setCellRenderer(new DefaultTreeCellRenderer(){
      public Component getTreeCellRendererComponent(JTree tree,Object value,boolean selected,boolean expanded,boolean leaf,int row,boolean focus){super.getTreeCellRendererComponent(tree,value,selected,expanded,leaf,row,focus);setFont(font().deriveFont(selected?Font.BOLD:Font.PLAIN));setForeground(selected?accent():ink());setBackgroundSelectionColor(MapperUi.selected());setTextSelectionColor(accent());setBackgroundNonSelectionColor(surface());setBorderSelectionColor(null);setIconTextGap(8);setIcon(kr.packetui.VisualTheme.document());String original=value.toString();setToolTipText(original);if(value instanceof javax.swing.tree.DefaultMutableTreeNode node){if(node.getUserObject() instanceof FeatureMapperWorkspace.FeatureNode)setText(original.replaceFirst(" \\[([0-9]+)건.*$"," ($1)"));else if(original.contains(" / ")){String[] parts=original.split(" / ",2);setText(parts[1]+" ("+node.getChildCount()+")");}}return this;}
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
    public Dimension getPreferredSize(JComponent c){if(Boolean.TRUE.equals(c.getClientProperty("fm.capture")))return new Dimension(132,34);return super.getPreferredSize(c);}
    public void paint(Graphics graphics,JComponent component) {
      AbstractButton b=(AbstractButton)component;Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
      if("fm.tree.toggle".equals(b.getName())){g.setColor(surface());g.fillRect(0,0,b.getWidth(),b.getHeight());g.setFont(font().deriveFont(Font.BOLD,15f));g.setColor(ink());g.drawString(b.isSelected()?"기능":"",12,b.getHeight()/2+5);if(b.isSelected()){g.setFont(font());g.drawString(String.valueOf(b.getClientProperty("fm.feature.count")==null?0:b.getClientProperty("fm.feature.count")),52,b.getHeight()/2+5);}g.setStroke(new BasicStroke(1.4f));int x=b.getWidth()-20,y=b.getHeight()/2,d=b.isSelected()?-1:1;for(int offset:new int[]{0,5})g.drawPolyline(new int[]{x+offset-2*d,x+offset+2*d,x+offset-2*d},new int[]{y-4,y,y+4},3);g.dispose();return;}
      if(Boolean.TRUE.equals(b.getClientProperty("fm.capture"))){String text=b.getText();Color color=text.equals("OFF")?(dark()?new Color(255,110,120):new Color(192,53,67)):text.contains("SCOPE")?accent():(dark()?new Color(52,220,123):new Color(21,133,71));g.setColor(dark()?new Color(color.getRed()/7,color.getGreen()/7+9,color.getBlue()/7+5):new Color(247,249,251));g.fillRoundRect(0,0,b.getWidth()-1,b.getHeight()-1,7,7);g.setColor(color);g.drawRoundRect(0,0,b.getWidth()-1,b.getHeight()-1,7,7);g.fillOval(14,b.getHeight()/2-5,10,10);g.setColor(ink());g.setFont(font().deriveFont(Font.BOLD));g.drawString("수집: "+text,30,(b.getHeight()-g.getFontMetrics().getHeight())/2+g.getFontMetrics().getAscent());g.dispose();return;}
      g.setColor(b.getModel().isRollover()?raised():surface());g.fillRoundRect(0,0,b.getWidth()-1,b.getHeight()-1,7,7);g.setColor(b.hasFocus()?accent():line());g.drawRoundRect(0,0,b.getWidth()-1,b.getHeight()-1,7,7);g.dispose();super.paint(graphics,component);
    }
  }
  private static final class TableUi extends javax.swing.plaf.basic.BasicTableUI {public void paint(Graphics graphics,JComponent component){super.paint(graphics,component);kr.packetui.VisualTheme.emptyTable(graphics,(JTable)component);}}
  private static final class Cells extends DefaultTableCellRenderer {
    Color badge;
    protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(getBackground());g.fillRect(0,0,getWidth(),getHeight());if(badge!=null){g.setColor(badge);int width=Math.min(getWidth()-8,Math.max(48,getFontMetrics(getFont()).stringWidth(getText())+20));g.fillRoundRect((getWidth()-width)/2,5,width,getHeight()-10,8,8);}g.dispose();setOpaque(false);super.paintComponent(graphics);setOpaque(true);}
    public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focus,int row,int column){super.getTableCellRendererComponent(table,value,selected,focus,row,column);badge=null;setHorizontalAlignment(LEFT);setFont(font());setBorder(BorderFactory.createEmptyBorder(0,9,0,9));setBackground(selected?MapperUi.selected():row%2==0?surface():background());setForeground(ink());String name=table.getColumnName(column);if(name.equals("메서드")){setHorizontalAlignment(CENTER);setForeground(dark()?new Color(110,175,255):new Color(36,99,185));badge=dark()?new Color(38,58,87):new Color(225,237,255);}else if(name.equals("상태")){setHorizontalAlignment(CENTER);boolean good=getText().startsWith("2");setForeground(good?(dark()?new Color(50,210,130):new Color(20,128,70)):(dark()?new Color(255,190,85):new Color(160,93,13)));badge=good?(dark()?new Color(26,66,51):new Color(222,244,231)):(dark()?new Color(83,62,29):new Color(255,240,213));}if(name.equals("메서드")||getText().matches("[1-5][0-9]{2}"))badge=null;if(name.equals("메서드")&&getText().equals("POST"))setForeground(dark()?new Color(50,210,130):new Color(20,128,70));if(name.equals("상태")&&getText().matches("[45][0-9]{2}"))setForeground(dark()?new Color(255,115,125):new Color(189,46,60));setToolTipText(getText());return this;}
  }
  private static final class ComboUi extends BasicComboBoxUI {
    protected JButton createArrowButton(){return new JButton(){protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.setColor(surface());g.fillRect(0,0,getWidth(),getHeight());g.setColor(ink());g.setStroke(new BasicStroke(1.3f));int x=getWidth()/2,y=getHeight()/2;g.drawPolyline(new int[]{x-4,x,x+4},new int[]{y-2,y+2,y-2},3);g.dispose();}public Dimension getPreferredSize(){return new Dimension(25,30);}};}
  }
  private static final class ScrollUi extends BasicScrollBarUI {
    protected void configureScrollBarColors(){trackColor=background();thumbColor=line();}
    protected JButton createDecreaseButton(int direction){return arrow(direction);}
    protected JButton createIncreaseButton(int direction){return arrow(direction);}
    private JButton arrow(int direction){return new BasicArrowButton(direction,background(),line(),ink(),line()){public Dimension getPreferredSize(){return new Dimension(0,0);}};}
    protected void paintThumb(Graphics graphics,JComponent c,Rectangle r){if(r.isEmpty()||!scrollbar.isEnabled())return;Graphics2D g=(Graphics2D)graphics.create();g.setColor(isThumbRollover()?accent():line());g.fillRoundRect(r.x+2,r.y+2,Math.max(2,r.width-4),Math.max(2,r.height-4),7,7);g.dispose();}
  }
  private static final class TreeUi extends BasicTreeUI {
    protected void installDefaults(){super.installDefaults();setExpandedIcon(kr.packetui.VisualTheme.chevron(true));setCollapsedIcon(kr.packetui.VisualTheme.chevron(false));setLeftChildIndent(12);setRightChildIndent(16);}
    protected void paintHorizontalLine(Graphics g,JComponent c,int y,int left,int right){}
    protected void paintVerticalLine(Graphics g,JComponent c,int x,int top,int bottom){}
    protected void paintRow(Graphics g,Rectangle clip,Insets insets,Rectangle bounds,javax.swing.tree.TreePath path,int row,boolean expanded,boolean leaf,boolean hasBeenExpanded){if(tree.isRowSelected(row)){g.setColor(selected());g.fillRect(0,bounds.y,tree.getWidth(),bounds.height);g.setColor(accent());g.fillRect(0,bounds.y,3,bounds.height);}super.paintRow(g,clip,insets,bounds,path,row,expanded,leaf,hasBeenExpanded);}
  }
  private static final class SplitUi extends BasicSplitPaneUI {
    public BasicSplitPaneDivider createDefaultDivider(){return new BasicSplitPaneDivider(this){public void paint(Graphics g){g.setColor(background());g.fillRect(0,0,getWidth(),getHeight());g.setColor(line());if(splitPane.getOrientation()==JSplitPane.HORIZONTAL_SPLIT)g.drawLine(getWidth()/2,0,getWidth()/2,getHeight());else g.drawLine(0,getHeight()/2,getWidth(),getHeight()/2);}};}
  }
  private static final class TabsUi extends BasicTabbedPaneUI {
    protected void paintTabBackground(Graphics g,int placement,int index,int x,int y,int w,int h,boolean selected){g.setColor(surface());g.fillRect(x,y,w,h);}
    protected void paintTabBorder(Graphics g,int placement,int index,int x,int y,int w,int h,boolean selected){g.setColor(selected?accent():line());g.fillRect(x,y+h-(selected?2:1),w,selected?2:1);}
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
