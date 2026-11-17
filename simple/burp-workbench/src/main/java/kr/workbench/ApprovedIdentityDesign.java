package kr.workbench;

import java.awt.*;
import javax.swing.*;
import javax.swing.plaf.basic.BasicLabelUI;

/** Approved role and environment presentation over the original controllers and labels. */
final class ApprovedIdentityDesign {
  private ApprovedIdentityDesign() {}
  static Component find(Component page,String name){return ApprovedUi.find(page,name);}
  static void title(JLabel label){label.putClientProperty("wb.font.heading",true);label.putClientProperty("wb.font.size",13);label.setBorder(BorderFactory.createEmptyBorder(0,12,0,0));}
  static void auth(JPanel page){
    if(find(page,"wb.auth.detect") instanceof AbstractButton detection){Dimension size=new Dimension(96,22);detection.setPreferredSize(size);detection.setMinimumSize(size);detection.setMaximumSize(size);}
    if(find(page,"wb.placement.auth.execution") instanceof JPanel tools&&find(page,"wb.actions.auth.filters") instanceof JPanel fields&&find(page,"wb.actions.auth.execution") instanceof JPanel actions){
      JComboBox<?> filter=(JComboBox<?>)find(page,"wb.auth.history.filter");Component search=find(page,"wb.auth.history.search");
      tools.setPreferredSize(new Dimension(644,44));ApprovedUi.layout(tools,p->{ApprovedUi.bounds(fields,0,0,484,44);ApprovedUi.bounds(actions,493,0,151,44);});
      Container filterHost=filter.getParent();ApprovedUi.layout(fields,p->{ApprovedUi.bounds(search,0,7,368,32);ApprovedUi.bounds(filterHost,376,7,108,31);});ApprovedUi.layout(filterHost,p->ApprovedUi.bounds(filter,0,0,108,31));
      ApprovedUi.layout(actions,p->{if(p.getComponentCount()>0)ApprovedUi.bounds(p.getComponent(0),0,7,70,31);if(p.getComponentCount()>1)ApprovedUi.bounds(p.getComponent(1),78,7,61,31);});
    }
    if(find(page,"wb.auth.inline") instanceof AuthInlinePreview preview){
      title(preview.selectedRequest);badge(preview.summary,"warning");
      if(preview.getLayout() instanceof BorderLayout layout&&layout.getLayoutComponent(BorderLayout.SOUTH) instanceof JLabel note){note.putClientProperty("wb.identity.instruction",true);notice(note);}
      preview.refreshApprovedHeaders();
      ApprovedUi.roundedLegacy(preview);
    }
    if(find(page,"wb.auth.tables") instanceof JSplitPane split&&split.getTopComponent() instanceof JPanel history)ApprovedUi.roundedLegacy(history);
  }
  static void environment(JPanel page){
    for(String[] action:new String[][]{{"wb.flow.environment.settings.open","설정·관리 ▾"},{"wb.environment.more","일괄 실행 ▾"}})if(find(page,action[0]) instanceof AbstractButton button){button.setText(action[1]);button.getAccessibleContext().setAccessibleName(action[1].replace(" ▾",""));}
    if(find(page,"wb.flow.environment.settings.open") instanceof AbstractButton settings)settings.setPreferredSize(new Dimension(94,31));
    for(String id:new String[]{"wb.pdf.environment.candidate.count","wb.flow.environment.selection"})if(find(page,id) instanceof JLabel label)title(label);
    if(find(page,"wb.flow.environment.selection") instanceof JLabel selected)selected.setUI(new BasicLabelUI(){
      @Override public void paint(Graphics graphics,JComponent component){JLabel label=(JLabel)component;String value=label.getText();try{java.net.URI uri=java.net.URI.create(value);if(uri.isAbsolute())value=(uri.getRawPath()==null||uri.getRawPath().isEmpty()?"/":uri.getRawPath())+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());}catch(IllegalArgumentException ignored){}
        Insets in=label.getInsets();Rectangle view=new Rectangle(in.left,in.top,Math.max(0,label.getWidth()-in.left-in.right),Math.max(0,label.getHeight()-in.top-in.bottom)),icon=new Rectangle(),text=new Rectangle();graphics.setFont(label.getFont());FontMetrics metrics=graphics.getFontMetrics();String clipped=layoutCL(label,metrics,value,null,view,icon,text);paintEnabledText(label,graphics,clipped,text.x,text.y+metrics.getAscent());
      }
    });
    if(find(page,"wb.environment.observations.toggle") instanceof AbstractButton toggle){toggle.putClientProperty("wb.approved.disclosure",true);toggle.setPreferredSize(new Dimension(0,36));}
    for(String id:new String[]{"wb.environment.filter.all","wb.environment.filter.unverified","wb.environment.filter.response"})if(find(page,id) instanceof AbstractButton button){button.putClientProperty("wb.segment.button",true);button.putClientProperty("wb.filter.segment",true);button.putClientProperty("wb.approved.filter.surface",true);button.setBorder(BorderFactory.createEmptyBorder(4,8,4,8));}
    if(page instanceof EnvironmentPanel environment)notice(environment.status);
    if(find(page,"wb.environment.workspace") instanceof JSplitPane split&&split.getLeftComponent() instanceof JPanel candidates)ApprovedUi.roundedLegacy(candidates);
    if(find(page,"wb.flow.environment.inspector") instanceof JPanel inspector){
      if(inspector.getLayout() instanceof BorderLayout layout&&layout.getLayoutComponent(BorderLayout.SOUTH) instanceof JLabel caution){caution.putClientProperty("wb.identity.instruction",true);notice(caution);caution.setBorder(BorderFactory.createEmptyBorder(0,14,0,14));caution.setVerticalAlignment(SwingConstants.TOP);}
      ApprovedUi.roundedLegacy(inspector);
    }
  }
  private static WorkbenchUi.Tone noteTone(JLabel label){WorkbenchUi.Tone tone=WorkbenchUi.tone(label.getText());return Boolean.TRUE.equals(label.getClientProperty("wb.identity.instruction"))?WorkbenchUi.Tone.WARNING:tone==WorkbenchUi.Tone.NEUTRAL?WorkbenchUi.Tone.WARNING:tone;}
  static void notice(JLabel label){label.setIcon(new Icon(){public int getIconWidth(){return 16;}public int getIconHeight(){return 16;}public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setColor(WorkbenchUi.toneInk(noteTone(label)));g.setStroke(new BasicStroke(1.2f));g.drawOval(x+2,y+2,12,12);g.fillOval(x+7,y+4,2,2);g.drawLine(x+8,y+7,x+8,y+12);g.dispose();}});label.setIconTextGap(7);label.putClientProperty("wb.font.size",11);label.setUI(new BasicLabelUI(){
    @Override protected void paintEnabledText(JLabel source,Graphics graphics,String text,int x,int y){
      graphics.setColor(WorkbenchUi.toneInk(noteTone(source)));graphics.drawString(text,x,y);
    }
  });}
  static void badge(JLabel label,String kind){label.putClientProperty("wb.identity.badge",kind);label.putClientProperty("wb.font.size",11);label.setUI(new BasicLabelUI(){
    @Override public Dimension getPreferredSize(JComponent c){return new Dimension(c.getFontMetrics(font()).stringWidth(label.getText()==null?"":label.getText())+16,22);}
    @Override public void paint(Graphics graphics,JComponent c){
      String text=label.getText();if(text==null||text.isBlank())return;Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);
      boolean blue="blue".equals(c.getClientProperty("wb.identity.badge"))&&(!Boolean.TRUE.equals(c.getClientProperty("wb.identity.link.state"))||"연결됨".equals(text));boolean warning="warning".equals(c.getClientProperty("wb.identity.badge"));
      int height=Math.min(22,c.getHeight()),top=(c.getHeight()-height)/2;
      g.setColor(blue?WorkbenchUi.selected():warning?WorkbenchUi.toneFill(WorkbenchUi.Tone.WARNING):WorkbenchUi.raised());g.fillRoundRect(0,top,c.getWidth(),height,5,5);
      g.setColor(blue?WorkbenchUi.accent():warning?WorkbenchUi.toneInk(WorkbenchUi.Tone.WARNING):kr.packetui.VisualTheme.muted());g.setFont(font());g.drawString(text,8,top+(height-g.getFontMetrics().getHeight())/2+g.getFontMetrics().getAscent());g.dispose();
    }
  });}
  private static Font font(){return WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(11f));}
  static Icon accountStatus(String caption,int slot){return new Icon(){
    public int getIconWidth(){return new JLabel().getFontMetrics(font()).stringWidth(caption)+16;}public int getIconHeight(){return 22;}
    public void paintIcon(Component component,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setColor(slot==0?WorkbenchUi.selected():WorkbenchUi.toneFill(WorkbenchUi.Tone.WARNING));g.fillRoundRect(x,y,getIconWidth(),22,5,5);g.setColor(slot==0?WorkbenchUi.accent():WorkbenchUi.toneInk(WorkbenchUi.Tone.WARNING));g.setFont(font());g.drawString(caption,x+8,y+(22-g.getFontMetrics().getHeight())/2+g.getFontMetrics().getAscent());g.dispose();}
  };}
}
