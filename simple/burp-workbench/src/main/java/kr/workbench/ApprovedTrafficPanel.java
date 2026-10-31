package kr.workbench;

import java.awt.*;
import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.table.DefaultTableCellRenderer;

/** Approved traffic composition. Retains the live table, original controllers and packet documents. */
final class ApprovedTrafficPanel extends JPanel {
  final JPanel controls, selection; final JSplitPane body, readers; final JTable table;
  final JLabel summary; final JComboBox<?> target, account; final JButton capture, settings, repeater;
  final JTextField search; final JPanel use; final PacketInspector request, response; final TrafficBrowser browser;
  private Component feedback; private final JLabel totals=new JLabel(), notice=new JLabel("요청 준비는 서버에 전송하지 않습니다.");
  ApprovedTrafficPanel(JPanel controls,JSplitPane body,JSplitPane readers,JTable table,JPanel selection,JLabel summary,
      JComboBox<?> target,JComboBox<?> account,JButton capture,JTextField search,JButton settings,JButton repeater,JPanel use,
      PacketInspector request,PacketInspector response,TrafficBrowser browser){
    super(null);this.controls=controls;this.body=body;this.readers=readers;this.table=table;this.selection=selection;
    this.summary=summary;this.target=target;this.account=account;this.capture=capture;this.search=search;this.settings=settings;
    this.repeater=repeater;this.use=use;this.request=request;this.response=response;this.browser=browser;
    Component context=named(controls,"wb.actions.traffic.context"), config=named(controls,"wb.actions.traffic.settings"), query=named(controls,"wb.actions.traffic.search");
    controls.removeAll();controls.setLayout(null);controls.add(context);controls.add(config);controls.add(query);
    Container list=(Container)body.getLeftComponent();list.add(selection,BorderLayout.SOUTH);
    putClientProperty("wb.embedded.style",(Runnable)this::applyReferenceTheme);
    add(controls);add(body);add(totals);add(notice);
    body.putClientProperty("wb.workflow.fixedLayout",true);readers.putClientProperty("wb.workflow.fixedLayout",true);
    totals.setName("wb.traffic.reference.counts");notice.setName("wb.traffic.reference.notice");
    table.getModel().addTableModelListener(e->{refreshTotals();SwingUtilities.invokeLater(this::styleReference);});table.getSelectionModel().addListSelectionListener(e->refreshTotals());
    search.putClientProperty("wb.search.placeholder","URL · 메서드 · 상태로 검색");
  }
  void setFeedback(Component c){feedback=c;add(c);}
  private void applyReferenceTheme(){WorkbenchUi.style(controls);WorkbenchUi.style(body);if(feedback!=null)WorkbenchUi.style(feedback);styleReference();}
  private void refreshTotals(){totals.setText("전체 "+table.getModel().getRowCount()+"건    표시 "+table.getRowCount()+"건    선택 "+table.getSelectedRowCount()+"건");}
  static Component named(Component c,String name){return SimpleFind.find(c,name);}
  static final class SimpleFind {static Component find(Component c,String name){if(name.equals(c.getName()))return c;if(c instanceof Container p)for(Component child:p.getComponents()){Component v=find(child,name);if(v!=null)return v;}return null;}}
  @Override public void doLayout(){
    int w=getWidth()-32,h=getHeight();controls.setBounds(16,14,w,78);body.setBounds(16,102,w,Math.max(220,h-143));
    controls.setLayout(null);Component context=named(controls,"wb.actions.traffic.context"),config=named(controls,"wb.actions.traffic.settings"),query=named(controls,"wb.actions.traffic.search");
    context.setBounds(0,0,600,34);config.setBounds(w-92,0,92,34);query.setBounds(0,44,w,34);
    Container ctx=(Container)context;ctx.setLayout(null);Component[] cs=ctx.getComponents();
    if(cs.length==5){cs[0].setBounds(0,0,29,34);target.setBounds(29,2,180,31);cs[2].setBounds(217,0,29,34);account.setBounds(246,2,180,31);capture.setBounds(434,6,94,22);}
    ((Container)config).setLayout(null);settings.setBounds(0,2,91,31);
    Container q=(Container)query;q.setLayout(null);search.setBounds(0,0,w-195,34);Component methods=q.getComponent(1);methods.setBounds(w-183,2,183,30);
    body.setOrientation(JSplitPane.VERTICAL_SPLIT);body.setDividerSize(0);int list=Math.max(160,Math.round((h-144)*.3924f));body.setDividerLocation(list+54);
    body.doLayout();selection.setPreferredSize(new Dimension(w,54));selection.setLayout(null);summary.setBounds(0,12,Math.max(100,w-215),31);
    Component actions=named(selection,"wb.actions.traffic.selection");if(actions instanceof Container p){p.setLayout(null);p.setBounds(w-196,12,196,31);use.setBounds(0,0,89,31);repeater.setBounds(97,0,98,31);}
    readers.setOrientation(JSplitPane.HORIZONTAL_SPLIT);readers.setDividerSize(12);readers.setDividerLocation((w-12)/2);
    totals.setBounds(16,h-30,w/2,22);notice.setBounds(Math.max(16,getWidth()-335),h-30,319,22);
    boolean filtered=!browser.search.getText().isBlank()||!browser.query.equals(TrafficQuery.defaults());
    totals.setVisible(!filtered);notice.setVisible(!filtered);if(feedback!=null)feedback.setBounds(16,h-35,w,filtered?32:0);
    styleReference();refreshTotals();
  }
  void styleReference(){
    setBackground(WorkbenchUi.background());
    for(JComponent c:new JComponent[]{target,account,search,settings,repeater,use,summary,totals,notice,capture})c.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(12f)));
    for(JComboBox<?> box:new JComboBox<?>[]{target,account}){if(!(box.getUI() instanceof FlatCombo))box.setUI(new FlatCombo());box.setBackground(WorkbenchUi.surface());box.setForeground(WorkbenchUi.ink());box.setBorder(kr.packetui.VisualTheme.border(3,8,3,8));}
    search.setBackground(WorkbenchUi.dark()?WorkbenchUi.raised():new Color(241,242,244));search.setBorder(kr.packetui.VisualTheme.border(5,28,5,8));
    summary.setUI(new SelectionUi());
    dropdown(settings,"수집 설정");repeater.setIcon(null);
    JButton useButton=(JButton)named(use,"wb.workflow.traffic.use.open");if(useButton!=null)dropdown(useButton,"요청 활용");
    if(!(capture.getUI() instanceof CaptureUi))capture.setUI(new CaptureUi());
    totals.setForeground(kr.packetui.VisualTheme.muted());notice.setForeground(kr.packetui.VisualTheme.muted());notice.setHorizontalAlignment(SwingConstants.RIGHT);
    table.setRowHeight(35);table.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(12f)));table.getTableHeader().setPreferredSize(new Dimension(0,31));
    JScrollPane ts=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,table);if(ts!=null)ts.setBorder(null);table.setShowHorizontalLines(true);table.setGridColor(WorkbenchUi.line());table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    int[] widths={98,124,442,132,114,160,308};int available=Math.max(500,getWidth()-32);boolean memo=browser.query.memo();if(!memo)widths[2]+=widths[6];
    for(int i=0;i<table.getColumnCount();i++){
      var col=table.getColumnModel().getColumn(i);int slot=switch(col.getModelIndex()){case 0->0;case 1->1;case 2->2;case 3->3;case 8->4;case 4->5;case 10->6;default->-1;};if(slot<0)continue;
      col.setMaxWidth(Integer.MAX_VALUE);col.setMinWidth(1);int used=0;for(int before=0;before<6;before++)used+=Math.round(widths[before]*available/1378f);
      int width=slot==6?available-used:Math.round(widths[slot]*available/1378f);if(slot==6&&!memo){col.setMinWidth(0);col.setMaxWidth(0);width=0;}
      col.setPreferredWidth(Math.max(0,width));col.setWidth(Math.max(0,width));if(slot==0)col.setHeaderValue("번호");col.setCellRenderer(new ReferenceCell());
    }
    for(Component child:readers.getComponents())if(child instanceof JPanel card){Component head=((BorderLayout)card.getLayout()).getLayoutComponent(BorderLayout.NORTH);if(head!=null)head.setPreferredSize(new Dimension(200,43));}
    for(Component child:readers.getComponents())if(child instanceof JPanel card){
      card.setBorder(new javax.swing.border.AbstractBorder(){public Insets getBorderInsets(Component c){return new Insets(1,1,1,1);}public void paintBorder(Component c,Graphics graphics,int x,int y,int w,int h){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setColor(WorkbenchUi.line());g.drawRoundRect(x,y,w-1,h-1,10,10);g.dispose();}});
    }
    request.approvedTrafficPresentation();response.approvedTrafficPresentation();
    for(Component child:controls.getComponents())if(child instanceof Container p)for(Component c:p.getComponents())if(c instanceof JLabel label){label.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(12f)));label.setForeground(kr.packetui.VisualTheme.muted());}
    Container methods=(Container)((Container)named(controls,"wb.actions.traffic.search")).getComponent(1);
    for(Component c:methods.getComponents())if(c instanceof AbstractButton b){b.setBorder(BorderFactory.createEmptyBorder());b.setIcon(null);b.setUI(new QuickUi());b.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(12f)));}
    table.getTableHeader().setDefaultRenderer(new HeaderCell());
  }
  static void dropdown(JButton button,String caption){button.setText(caption);button.setIcon(new ApprovedTrafficIcon("chevron",WorkbenchUi.ink()));button.setHorizontalTextPosition(SwingConstants.LEFT);button.setIconTextGap(4);}
  static JPanel packetCard(String title,PacketInspector packet){
    JPanel card=new JPanel(new BorderLayout()){
      protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setColor(WorkbenchUi.surface());g.fillRoundRect(0,0,getWidth(),getHeight(),10,10);g.dispose();}
      protected void paintChildren(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.clip(new java.awt.geom.RoundRectangle2D.Double(0,0,getWidth(),getHeight(),10,10));super.paintChildren(g);g.dispose();}
    };
    card.setOpaque(false);card.setName("wb.card."+title);card.putClientProperty("wb.card",true);card.putClientProperty("wb.surface",true);
    JLabel heading=new JLabel(title);heading.putClientProperty("wb.font.size",13);heading.putClientProperty("wb.font.heading",true);heading.setBorder(BorderFactory.createEmptyBorder(0,12,0,0));
    JPanel header=new JPanel(new BorderLayout()){
      protected void paintChildren(Graphics graphics){super.paintChildren(graphics);graphics.setColor(WorkbenchUi.line());graphics.drawLine(0,getHeight()-1,getWidth()-1,getHeight()-1);}
    };
    header.putClientProperty("wb.surface",true);header.add(heading);header.add(packet.titleActions(),BorderLayout.EAST);
    header.setPreferredSize(new Dimension(200,43));card.add(header,BorderLayout.NORTH);card.add(packet);card.setMinimumSize(new Dimension(120,80));return card;
  }
  private static Color chipFill(String status){boolean success=status!=null&&status.startsWith("2"),warning=status!=null&&status.matches("[45][0-9]{2}")&&!status.equals("404");return WorkbenchUi.dark()?success?new Color(32,60,51):warning?new Color(67,55,39):WorkbenchUi.raised():success?new Color(233,247,240):warning?new Color(255,244,222):new Color(248,249,251);}
  private static Color chipInk(String status){boolean success=status!=null&&status.startsWith("2"),warning=status!=null&&status.matches("[45][0-9]{2}")&&!status.equals("404");return WorkbenchUi.dark()?success?new Color(103,213,164):warning?new Color(237,186,101):kr.packetui.VisualTheme.muted():success?new Color(11,119,82):warning?new Color(171,104,11):new Color(96,109,128);}
  private static final class SelectionUi extends javax.swing.plaf.basic.BasicLabelUI {
    public void paint(Graphics graphics,JComponent c){JLabel label=(JLabel)c;Object value=label.getClientProperty("wb.traffic.selection.parts");if(!(value instanceof String[] parts)){super.paint(graphics,c);return;}
      Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setFont(WorkbenchUi.textFont(parts[0]).deriveFont(Font.BOLD,WorkbenchUi.displaySize(12f)));g.setColor(WorkbenchUi.ink());g.drawString(parts[0],0,20);int x=g.getFontMetrics().stringWidth(parts[0])+13;
      String status=parts[1]+("200".equals(parts[1])?" OK":"");g.setFont(label.getFont().deriveFont(WorkbenchUi.displaySize(11f)));int width=g.getFontMetrics().stringWidth(status)+16;g.setColor(chipFill(parts[1]));g.fillRoundRect(x,4,width,22,5,5);g.setColor(chipInk(parts[1]));g.drawString(status,x+8,19);
      g.setColor(kr.packetui.VisualTheme.muted());g.setFont(label.getFont());g.drawString(parts[2]+" · "+parts[3],x+width+8,20);g.dispose();
    }
  }
  private static final class QuickUi extends BasicButtonUI {public void paint(Graphics graphics,JComponent c){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);AbstractButton b=(AbstractButton)c;if(b.isSelected()){g.setColor(WorkbenchUi.selected());g.fillRoundRect(0,0,c.getWidth(),c.getHeight(),7,7);}g.setColor(b.isSelected()?WorkbenchUi.accent():kr.packetui.VisualTheme.muted());g.setFont(c.getFont().deriveFont(b.isSelected()?Font.BOLD:Font.PLAIN));g.drawString(b.getText(),(c.getWidth()-g.getFontMetrics().stringWidth(b.getText()))/2,20);g.dispose();}}
  private static final class HeaderCell extends DefaultTableCellRenderer {public Component getTableCellRendererComponent(JTable t,Object value,boolean selected,boolean focus,int row,int column){super.getTableCellRendererComponent(t,value,selected,focus,row,column);setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(11f)));setForeground(kr.packetui.VisualTheme.muted());setBackground(WorkbenchUi.raised());setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,WorkbenchUi.line()),BorderFactory.createEmptyBorder(0,11,0,0)));return this;}}
  private static final class FlatCombo extends BasicComboBoxUI {
    protected JButton createArrowButton(){JButton b=new JButton();b.setVisible(false);return b;}
    protected Rectangle rectangleForCurrentValue(){Insets i=comboBox.getInsets();return new Rectangle(i.left,i.top,comboBox.getWidth()-i.left-i.right,comboBox.getHeight()-i.top-i.bottom);}
  }
  private static final class CaptureUi extends BasicButtonUI {public Dimension getPreferredSize(JComponent c){return new Dimension(94,22);}public void paint(Graphics graphics,JComponent c){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);boolean on=((JButton)c).getText().contains("SCOPE");g.setColor(chipFill(on?"200":""));g.fillRoundRect(0,0,c.getWidth(),c.getHeight(),5,5);g.setColor(chipInk(on?"200":""));g.setFont(c.getFont().deriveFont(WorkbenchUi.displaySize(11f)));g.drawString(((JButton)c).getText().contains("SCOPE")?"수집 중 · SCOPE":"수집 꺼짐",8,15);g.dispose();}}
  private static final class ReferenceCell extends DefaultTableCellRenderer {
    private String badge; private boolean selectedRow, first;
    public Component getTableCellRendererComponent(JTable t,Object value,boolean selected,boolean focus,int row,int column){
      super.getTableCellRendererComponent(t,value,selected,focus,row,column);selectedRow=selected;first=t.convertColumnIndexToModel(column)==0;setFont(t.getFont());setBorder(BorderFactory.createEmptyBorder(0,11,0,11));setBackground(selected?WorkbenchUi.selected():WorkbenchUi.surface());setForeground(WorkbenchUi.ink());setHorizontalAlignment(LEFT);badge=t.convertColumnIndexToModel(column)==3?String.valueOf(value):null;
      if(t.convertColumnIndexToModel(column)==8&&"미매핑".equals(value))setText("—");
      if(t.convertColumnIndexToModel(column)==2)try{java.net.URI url=java.net.URI.create(String.valueOf(value));setText(url.getRawPath()+(url.getRawQuery()==null?"":"?"+url.getRawQuery()));}catch(IllegalArgumentException ignored){}
      return this;
    }
    protected void paintComponent(Graphics g){if(badge==null){super.paintComponent(g);if(selectedRow&&first){g.setColor(WorkbenchUi.accent());g.fillRect(0,0,3,getHeight());}return;}Graphics2D gg=(Graphics2D)g.create();WorkbenchSkin.antialias(gg);gg.setColor(getBackground());gg.fillRect(0,0,getWidth(),getHeight());gg.setColor(chipFill(badge));gg.fillRoundRect(11,6,34,22,5,5);gg.setColor(chipInk(badge));gg.setFont(getFont().deriveFont(WorkbenchUi.displaySize(11f)));gg.drawString(badge,19,21);gg.dispose();}
  }
}
