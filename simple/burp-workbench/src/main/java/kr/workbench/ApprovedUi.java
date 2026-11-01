package kr.workbench;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import javax.swing.plaf.LayerUI;

/** Shared measurements from the approved screens; controllers and documents remain authoritative. */
final class ApprovedUi {
  private ApprovedUi() {}
  static Component find(Component root,String name){return ApprovedTrafficPanel.named(root,name);}
  static final AbstractBorder CARD_BORDER=new AbstractBorder(){
    public Insets getBorderInsets(Component c){return new Insets(1,1,1,1);}
    public void paintBorder(Component c,Graphics graphics,int x,int y,int w,int h){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setColor(WorkbenchUi.line());g.drawRoundRect(x,y,w-1,h-1,20,20);g.dispose();}
  };
  static JPanel card(String title,Component body){
    JPanel card=new JPanel(new BorderLayout()){
      protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setColor(WorkbenchUi.surface());g.fillRoundRect(0,0,getWidth(),getHeight(),20,20);g.dispose();}
      protected void paintChildren(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.clip(new RoundRectangle2D.Double(0,0,getWidth(),getHeight(),20,20));super.paintChildren(g);g.dispose();CARD_BORDER.paintBorder(this,graphics,0,0,getWidth(),getHeight());}
    };
    card.setName("wb.card."+title);card.putClientProperty("wb.card",true);card.putClientProperty("wb.surface",true);card.setOpaque(false);card.setBorder(CARD_BORDER);card.setMinimumSize(new Dimension(120,80));
    JLabel heading=new JLabel(title);heading.putClientProperty("wb.font.size",13);heading.putClientProperty("wb.font.heading",true);heading.setBorder(BorderFactory.createEmptyBorder(0,12,0,0));
    JPanel header=new JPanel(new BorderLayout()){
      protected void paintChildren(Graphics graphics){super.paintChildren(graphics);graphics.setColor(WorkbenchUi.line());graphics.drawLine(0,getHeight()-1,getWidth()-1,getHeight()-1);}
    };
    header.putClientProperty("wb.surface",true);header.setName("wb.approved.card.header");header.setPreferredSize(new Dimension(0,body instanceof PacketInspector?43:39));header.add(heading);
    if(body instanceof PacketInspector packet){header.add(packet.titleActions(),BorderLayout.EAST);packet.approvedTrafficPresentation();}
    card.add(header,BorderLayout.NORTH);card.add(body);return card;
  }
  static void layout(Container parent,Consumer<Container> apply){if(parent instanceof JComponent component)component.putClientProperty("wb.approved.layout",true);parent.setLayout(new LayoutManager(){
    public void addLayoutComponent(String name,Component c){}public void removeLayoutComponent(Component c){}
    public Dimension preferredLayoutSize(Container c){return new Dimension(600,400);}public Dimension minimumLayoutSize(Container c){return new Dimension(100,80);}
    public void layoutContainer(Container c){apply.accept(c);}
  });}
  static void bounds(Component c,int x,int y,int w,int h){if(c==null)return;Component target=c;if(c instanceof JComponent component&&component.getClientProperty("wb.approved.rounded.owner") instanceof JLayer<?> layer&&layer.getView()==c&&c.getParent()==layer)target=layer;target.setBounds(x,y,Math.max(0,w),Math.max(0,h));if(target instanceof JLayer<?> layer)layer.doLayout();}
  static void split(JSplitPane pane,int orientation,int divider,int position){pane.putClientProperty("wb.workflow.fixedLayout",true);pane.setOrientation(orientation);pane.setDividerSize(divider);pane.setDividerLocation(position);pane.setBorder(null);}
  static void row(Container row,int gap){row.setLayout(new LayoutManager(){
    public void addLayoutComponent(String n,Component c){}public void removeLayoutComponent(Component c){}
    public Dimension preferredLayoutSize(Container p){int width=0;for(Component c:p.getComponents())if(c.isVisible())width+=c.getPreferredSize().width+gap;return new Dimension(Math.max(0,width-gap),34);}
    public Dimension minimumLayoutSize(Container p){return preferredLayoutSize(p);}
    public void layoutContainer(Container p){int x=0;boolean tabs=p instanceof JComponent j&&Boolean.TRUE.equals(j.getClientProperty("wb.approved.tabs"));for(Component c:p.getComponents())if(c.isVisible()){Dimension d=c.getPreferredSize();int h=Math.min(tabs?30:Math.min(31,d.height),p.getHeight());bounds(c,x,tabs?0:(p.getHeight()-h)/2,d.width,h);x+=d.width+gap;}}
  });}
  static void tabs(JTabbedPane pane){pane.putClientProperty("wb.external.tabs",true);WorkbenchSkin.tabs(pane);pane.setBorder(null);}
  static JPanel tabBar(JTabbedPane pane){tabs(pane);JPanel bar=new JPanel();bar.putClientProperty("wb.approved.tabs",true);row(bar,2);ButtonGroup group=new ButtonGroup();java.util.List<JToggleButton> buttons=new java.util.ArrayList<>();
    for(int i=0;i<pane.getTabCount();i++){int index=i;JToggleButton b=ReferenceUi.tab(pane.getTitleAt(i));b.setName(pane.getName()+".tab."+i);group.add(b);bar.add(b);buttons.add(b);b.setSelected(pane.getSelectedIndex()==i);b.addActionListener(e->pane.setSelectedIndex(index));}
    pane.addChangeListener(e->{for(int i=0;i<buttons.size();i++)buttons.get(i).setSelected(pane.getSelectedIndex()==i);});return bar;
  }
  static void combo(JComboBox<?> box,int width){box.putClientProperty("wb.approved.combo",true);box.setPreferredSize(new Dimension(width,31));}
  /** Clip legacy card children without replacing their panel, model or controller identities. */
  static JComponent roundedLegacy(JPanel card){
    Container parent=card.getParent();if(parent instanceof JLayer<?> layer)return layer;
    if(parent==null)return card;
    LayoutManager layout=parent.getLayout();boolean split=parent instanceof JSplitPane;
    boolean approvedAbsolute=parent instanceof JComponent component&&Boolean.TRUE.equals(component.getClientProperty("wb.approved.layout"));
    if(!split&&!approvedAbsolute&&!(layout instanceof BorderLayout)&&!(layout instanceof BoxLayout)&&!(layout instanceof FlowLayout))return card;
    Object constraint=layout instanceof BorderLayout border?border.getConstraints(card):null;
    boolean left=split&&((JSplitPane)parent).getLeftComponent()==card;int index=java.util.Arrays.asList(parent.getComponents()).indexOf(card);
    JLayer<JPanel> layer=new JLayer<>(card,new LayerUI<>(){
      @Override public void paint(Graphics graphics,JComponent component){
        Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.clip(new RoundRectangle2D.Double(0,0,component.getWidth(),component.getHeight(),20,20));super.paint(g,component);g.dispose();
        CARD_BORDER.paintBorder(component,graphics,0,0,component.getWidth(),component.getHeight());
      }
    });
    layer.setName("wb.approved.rounded.layer");layer.setOpaque(false);
    card.putClientProperty("wb.approved.rounded.owner",layer);
    if(split){if(left)((JSplitPane)parent).setLeftComponent(layer);else((JSplitPane)parent).setRightComponent(layer);}
    else if(layout instanceof BorderLayout)parent.add(layer,constraint);
    else parent.add(layer,Math.min(index,parent.getComponentCount()));
    if(index>=0)parent.setComponentZOrder(layer,index);
    parent.revalidate();return layer;
  }
  static void component(Component c){
    if(c instanceof JLabel label&&Boolean.TRUE.equals(label.getClientProperty("wb.approved.notice"))){if(!(label.getUI() instanceof NoticeUi))label.setUI(new NoticeUi());label.setForeground(WorkbenchUi.toneInk(noticeTone(label)));label.setBorder(BorderFactory.createEmptyBorder(0,12,0,12));label.setOpaque(false);}
    if(c instanceof JComponent j&&Boolean.TRUE.equals(j.getClientProperty("wb.approved.combo"))&&c instanceof JComboBox<?> box){if(Boolean.TRUE.equals(box.getClientProperty("wb.approved.product"))){if(!(box.getUI() instanceof ProductCombo))box.setUI(new ProductCombo());box.setBackground(WorkbenchUi.background());box.setBorder(null);box.setOpaque(false);}else{if(!(box.getUI() instanceof FlatCombo))box.setUI(new FlatCombo());box.setBackground(WorkbenchUi.surface());box.setBorder(ApprovedInputs.BORDER);}}
    if(c instanceof AbstractButton button&&button.getText()!=null&&button.getText().endsWith(" ▾")&&button instanceof JButton b)ApprovedTrafficPanel.dropdown(b,b.getText().substring(0,b.getText().length()-2));
    if(c instanceof javax.swing.JTextArea text&&Boolean.TRUE.equals(text.getClientProperty("wb.approved.fact"))){text.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(12f)));text.setMargin(new Insets(0,0,0,0));text.setBorder(null);}
    if(c instanceof javax.swing.JTextArea text&&Boolean.TRUE.equals(text.getClientProperty("wb.approved.packet"))){text.setMargin(new Insets(0,0,0,0));text.setBorder(BorderFactory.createEmptyBorder(14,8,14,14));text.setFont(WorkbenchUi.textFont(text.getText()).deriveFont(WorkbenchUi.displaySize(12f)));}
    if(c instanceof JScrollPane scroll&&scroll.getViewport().getView() instanceof JComponent view&&Boolean.TRUE.equals(view.getClientProperty("wb.approved.packet")))scroll.setBorder(null);
    if(c instanceof JScrollPane scroll&&Boolean.TRUE.equals(scroll.getClientProperty("wb.approved.scroll.flat")))scroll.setBorder(null);
    if(c instanceof JScrollPane scroll&&scroll.getViewport().getView() instanceof JTable table&&Boolean.TRUE.equals(table.getClientProperty("wb.table.floating"))){scroll.getViewport().setBackground(WorkbenchUi.background());scroll.setBorder(null);}
    if(c instanceof JTextArea text&&Boolean.TRUE.equals(text.getClientProperty("wb.approved.codec"))){text.setFont(WorkbenchUi.textFont(text.getText()).deriveFont(WorkbenchUi.displaySize(14f)));text.setMargin(new Insets(0,0,0,0));text.setBorder(BorderFactory.createEmptyBorder(18,15,14,15));}
    if(c instanceof JTextArea text&&Boolean.TRUE.equals(text.getClientProperty("wb.approved.note"))){text.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(11f)));text.setMargin(new Insets(0,0,0,0));}
    if(c instanceof JSplitPane split&&split.getClientProperty("wb.approved.divider") instanceof Runnable apply)apply.run();
    if(c instanceof JPanel panel&&Boolean.TRUE.equals(panel.getClientProperty("wb.card")))panel.setBorder(CARD_BORDER);
  }
  static class FlatCombo extends WorkbenchSkin.ComboUi {
    protected JButton createArrowButton(){JButton b=new JButton(){protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setColor(WorkbenchUi.ink());g.setStroke(new BasicStroke(1.3f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));int x=getWidth()/2,y=getHeight()/2;g.drawPolyline(new int[]{x-3,x,x+3},new int[]{y-1,y+2,y-1},3);g.dispose();}};b.putClientProperty("wb.nativeEditor",true);b.setBorder(null);b.setContentAreaFilled(false);b.setPreferredSize(new Dimension(18,25));return b;}
  }
  private static final class ProductCombo extends FlatCombo {
    protected void installDefaults(){super.installDefaults();squareButton=false;}
    protected JButton createArrowButton(){JButton button=super.createArrowButton();button.setPreferredSize(new Dimension(0,0));button.setVisible(false);return button;}
    protected Rectangle rectangleForCurrentValue(){Insets insets=comboBox.getInsets();return new Rectangle(insets.left,insets.top,Math.max(0,comboBox.getWidth()-insets.left-insets.right),Math.max(0,comboBox.getHeight()-insets.top-insets.bottom));}
    public void paintCurrentValueBackground(Graphics graphics,Rectangle bounds,boolean focus){}
  }
  private static final class NoticeUi extends javax.swing.plaf.basic.BasicLabelUI {
    public void paint(Graphics graphics,JComponent component){JLabel label=(JLabel)component;Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setColor(Boolean.TRUE.equals(label.getClientProperty("wb.approved.notice.neutral"))?WorkbenchUi.raised():WorkbenchUi.toneFill(noticeTone(label)));g.fillRoundRect(0,0,component.getWidth()-1,component.getHeight()-1,10,10);g.setColor(WorkbenchUi.line());g.drawRoundRect(0,0,component.getWidth()-1,component.getHeight()-1,10,10);super.paint(g,component);g.dispose();}
  }
  private static WorkbenchUi.Tone noticeTone(JLabel label){return label.getClientProperty("wb.approved.notice.tone") instanceof WorkbenchUi.Tone tone?tone:WorkbenchUi.tone(label.getText());}
  static void chip(JLabel label){
    label.putClientProperty("html.disable",true);label.setUI(new javax.swing.plaf.basic.BasicLabelUI(){
      public Dimension getPreferredSize(JComponent c){return new Dimension(c.getFontMetrics(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(11f))).stringWidth(label.getText()==null?"":label.getText())+16,22);}
      public void paint(Graphics graphics,JComponent c){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);String text=label.getText()==null?"":label.getText();WorkbenchUi.Tone tone=WorkbenchUi.tone(text);if(text.contains("포함")||text.equals("SCOPE 안"))tone=WorkbenchUi.Tone.SUCCESS;if(text.contains("버전 조건 일치"))tone=WorkbenchUi.Tone.WARNING;if(text.startsWith("CVSS "))try{double score=Double.parseDouble(text.substring(5));tone=score>=9?WorkbenchUi.Tone.ERROR:score>=7?WorkbenchUi.Tone.WARNING:WorkbenchUi.Tone.NEUTRAL;}catch(NumberFormatException ignored){}g.setColor(WorkbenchUi.toneFill(tone));g.fillRoundRect(0,0,c.getWidth(),c.getHeight(),5,5);g.setColor(WorkbenchUi.toneInk(tone));g.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(11f)));g.drawString(text,8,(c.getHeight()-g.getFontMetrics().getHeight())/2+g.getFontMetrics().getAscent());g.dispose();}
    });
  }
  static String path(String url){try{var uri=java.net.URI.create(url);return uri.getRawPath()+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());}catch(IllegalArgumentException invalid){return url;}}
  static void attach(JPanel page,Runnable styling){
    page.putClientProperty("wb.embedded.style",(Runnable)()->{page.setBackground(WorkbenchUi.background());for(Component child:page.getComponents())WorkbenchUi.style(child);styling.run();});
    styling.run();page.revalidate();
  }
}
