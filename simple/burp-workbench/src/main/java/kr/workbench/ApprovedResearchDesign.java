package kr.workbench;

import java.awt.*;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.plaf.basic.BasicLabelUI;
import javax.swing.table.DefaultTableCellRenderer;

/** Approved research details, composed from the retained controllers and source documents. */
final class ApprovedResearchDesign {
  private ApprovedResearchDesign() {}
  private static Component named(Component root,String name){return ApprovedUi.find(root,name);}
  private static Component region(Container root,String constraint){return ApprovedLayouts.region(root,constraint);}
  private static JPanel panel(Component component){return (JPanel)component;}
  private static void place(Component c,int x,int y,int w,int h){ApprovedUi.bounds(c,x,y,w,h);}
  private static void text(JLabel label,int size,boolean heading){label.putClientProperty("wb.font.size",size);label.putClientProperty("wb.font.heading",heading);label.putClientProperty("html.disable",true);}

  static void exposure(JPanel root){
    if(Boolean.TRUE.equals(root.getClientProperty("wb.approved.research.details")))return;
    root.putClientProperty("wb.approved.research.details",true);
    ExposureResearchPanel page=(ExposureResearchPanel)root;
    page.subdomains.setMargin(new Insets(0,0,0,0));page.subdomains.setBorder(BorderFactory.createEmptyBorder());page.subdomains.setIconTextGap(6);
    JPanel context=panel(named(page,"wb.actions.exposure.context"));JPanel left=panel(region(context,BorderLayout.CENTER));
    JLabel targetCaption=null;for(Component child:left.getComponents())if(child instanceof JLabel label&&label!=page.scopeLabel){targetCaption=label;break;}
    JLabel targetLabel=targetCaption;
    ApprovedUi.layout(left,p->{place(targetLabel,0,0,29,34);place(page.target,29,2,180,31);place(page.subdomains,221,2,110,31);place(page.scopeLabel,334,6,Math.max(74,page.scopeLabel.getPreferredSize().width),22);});
    buttonIcon((AbstractButton)named(page,"wb.exposure.search.query.copy"),"copy");
    buttonIcon((AbstractButton)named(page,"wb.exposure.search.google"),"external");
    buttonIcon(page.exposureCopy,"copy");page.exposureRepeater.putClientProperty("wb.no.auto.icon",true);page.exposureRepeater.setIcon(null);
    buttonIcon(page.referenceOpen,"external");buttonIcon(page.cveCopy,"copy");
    search(page);cve(page);
  }

  private static void search(ExposureResearchPanel page){
    JSplitPane split=(JSplitPane)named(page,"wb.flow.exposure.search.masterdetail");
    JPanel list=panel(split.getLeftComponent()),detail=panel(split.getRightComponent());
    JPanel header=panel(region(list,BorderLayout.NORTH));JLabel count=(JLabel)region(header,BorderLayout.CENTER);count.setName("wb.approved.exposure.imported.count");text(count,13,true);
    header.setBorder(BorderFactory.createEmptyBorder(6,12,6,14));
    Runnable counted=()->count.setText("가져온 URL · "+page.exposureTable.getRowCount()+"개");page.exposureModel.addTableModelListener(e->SwingUtilities.invokeLater(counted));counted.run();
    AbstractButton imported=(AbstractButton)named(page,"wb.exposure.search.import"),more=(AbstractButton)named(page,"wb.exposure.search.more");
    imported.setPreferredSize(new Dimension(92,31));more.setPreferredSize(new Dimension(89,31));ApprovedUi.row(imported.getParent(),8);
    for(int index=1;index<4;index++)page.exposureTable.getColumnModel().getColumn(index).setCellRenderer(new ResearchCell(index));

    JLabel url=page.discoveryUrl;JPanel oldColumn=panel(url.getParent());JLabel state=null,caution=null;
    for(Component child:oldColumn.getComponents())if(child instanceof JLabel label&&label!=url){if(label.getText().startsWith("확인 상태"))state=label;else if(label.getText().startsWith("URL 가져오기"))caution=label;}
    if(state==null||caution==null)throw new IllegalStateException("Missing retained research selection notes");
    state.setToolTipText(state.getText());state.setText("확인 상태 · 미확인");state.setName("wb.approved.exposure.selection.state");
    decorate(state,WorkbenchUi.Tone.WARNING,true,false,false,false);
    decorate(url,null,false,false,false,true);
    decorate(caution,WorkbenchUi.Tone.WARNING,false,false,true,false);
    JPanel facts=panel(named(page,"wb.pdf.exposure.discovery.facts"));factRows(facts,29);
    JPanel actions=panel(named(page,"wb.actions.exposure.search.detail"));actions.setBorder(null);ApprovedUi.row(actions,8);
    JPanel original=panel(named(page,"wb.pdf.exposure.discovery.original"));disclosure(original);
    JPanel details=new SearchColumn(url,state,facts,caution,actions,original);details.setName("wb.approved.exposure.discovery.column");
    JScrollPane scroll=scroll(details);detail.remove(region(detail,BorderLayout.CENTER));detail.add(scroll);
    JLabel unverified=new JLabel("실제 노출 미확인");unverified.setName("wb.approved.exposure.unverified");decorate(unverified,WorkbenchUi.Tone.WARNING,true,false,false,false);
    JPanel detailHead=panel(region(detail,BorderLayout.NORTH));detailHead.add(badgeHost(unverified),BorderLayout.EAST);
    ApprovedUi.roundedLegacy(list);ApprovedUi.roundedLegacy(detail);
  }

  private static void cve(ExposureResearchPanel page){
    JPanel lookup=panel(named(page,"wb.actions.exposure.cve.lookup"));JPanel products=panel(region(lookup,BorderLayout.CENTER));
    JLabel caption=(JLabel)products.getComponent(0);text(caption,12,false);caption.putClientProperty("wb.secondary",true);
    JLabel evidence=null;for(Component child:products.getComponents())if(child instanceof JLabel label&&label!=caption)evidence=label;
    JLabel productEvidence=evidence;JToggleButton edit=(JToggleButton)named(page,"wb.exposure.product.edit");edit.setText("수정 ▾");edit.setIcon(null);edit.putClientProperty("wb.approved.button.insets",new Insets(4,6,4,6));
    page.observed.putClientProperty("wb.font.size",13);page.observed.putClientProperty("wb.font.heading",true);
    ApprovedUi.layout(products,p->{
      String value=java.util.Objects.toString(page.observed.getSelectedItem(),"관측 제품 없음");
      int valueWidth=Math.max(76,p.getFontMetrics(WorkbenchUi.font().deriveFont(Font.BOLD,WorkbenchUi.displaySize(13f))).stringWidth(value)+6);
      int badge=productEvidence!=null&&productEvidence.isVisible()?productEvidence.getPreferredSize().width:0;
      place(caption,0,0,49,34);place(page.observed,57,2,valueWidth,31);place(productEvidence,65+valueWidth,6,badge,22);place(edit,65+valueWidth+(badge==0?0:badge+10),2,53,31);
    });
    JPanel facts=panel(named(page,"wb.pdf.exposure.cve.facts"));facts.setBorder(BorderFactory.createEmptyBorder(12,14,12,14));
    if(facts.getLayout() instanceof GridBagLayout grid)for(Component child:facts.getComponents()){
      GridBagConstraints c=grid.getConstraints(child);c.insets=new Insets(3,0,3,c.gridx==0?13:0);grid.setConstraints(child,c);
      if(c.gridx==0){child.setPreferredSize(new Dimension(110,22));if(child instanceof JLabel label){text(label,12,false);label.putClientProperty("wb.secondary",true);}}
      else if(child instanceof JTextArea area){area.setMargin(new Insets(0,0,0,0));area.setBorder(null);area.setRows(0);area.putClientProperty("wb.approved.fact",true);}
    }
    JPanel condition=panel(facts.getParent());Component warning=region(condition,BorderLayout.SOUTH);
    if(warning instanceof JLabel label){label.setName("wb.approved.exposure.applicability.warning");decorate(label,WorkbenchUi.Tone.WARNING,false,true,false,false);condition.remove(label);condition.add(padded(label,14),BorderLayout.SOUTH);}
    JPanel actions=panel(named(page,"wb.actions.exposure.cve.detail"));actions.setBorder(null);ApprovedUi.row(actions,8);
    JPanel footer=panel(actions.getParent());footer.remove(actions);footer.add(padded(actions,14),BorderLayout.NORTH);JComponent sources=(JComponent)named(page,"wb.pdf.exposure.cve.references");if(sources!=null)disclosure(panel(sources));
    for(Component child:ApprovedLayouts.all(named(page,"wb.actions.exposure.cve.filters")))if(child instanceof JLabel label&&label!=page.filterSummary&&label!=page.freshness){String value=label.getText();if(value!=null&&(value.startsWith("CVSS ")||value.equals("원격·인증 불필요")))decorate(label,WorkbenchUi.Tone.ACTIVE,true,false,false,false);else if(value!=null&&(value.equals("미확인 포함")||value.equals("미확인 제외")))decorate(label,WorkbenchUi.Tone.NEUTRAL,true,true,false,false);}
    JSplitPane split=(JSplitPane)named(page,"wb.flow.exposure.cve.masterdetail");JPanel list=panel(split.getLeftComponent()),detail=panel(split.getRightComponent());
    JPanel header=panel(region(list,BorderLayout.NORTH));JLabel unknown=new JLabel("미확인 조건 포함");unknown.setName("wb.approved.exposure.unknown.conditions");decorate(unknown,WorkbenchUi.Tone.NEUTRAL,true,true,false,false);
    JPanel badge=badgeHost(unknown);header.add(badge,BorderLayout.EAST);
    Runnable unknownState=()->badge.setVisible(page.unknown.isSelected());page.unknown.addItemListener(e->unknownState.run());unknownState.run();
    for(int view=0;view<page.cveTable.getColumnCount();view++)if(page.cveTable.convertColumnIndexToModel(view)==1)page.cveTable.getColumnModel().getColumn(view).setCellRenderer(new ResearchCell(4));
    ApprovedUi.roundedLegacy(list);ApprovedUi.roundedLegacy(detail);
  }

  static void dashboard(JPanel root){
    if(Boolean.TRUE.equals(root.getClientProperty("wb.approved.dashboard.details")))return;root.putClientProperty("wb.approved.dashboard.details",true);
    ScopeDashboardPanel page=(ScopeDashboardPanel)root;JSplitPane split=(JSplitPane)named(page,"wb.scope.dashboard.results.split");
    for(var entry:page.cards.entrySet())entry.getValue().putClientProperty("wb.approved.metric.caption",switch(entry.getKey()){case PATHS->"관측 경로";case API->"API·명세";case ENVIRONMENT->"환경 단서";case SECRETS->"민감정보 후보";case ACCESS->"계정·권한 검토";case COVERAGE->"미확인·제한";default->entry.getValue().caption;});
    JPanel detail=panel(split.getRightComponent()),head=panel(region(detail,BorderLayout.NORTH));head.setPreferredSize(new Dimension(0,44));
    JPanel actions=panel(named(page,"wb.actions.dashboard.selection"));actions.setBorder(BorderFactory.createEmptyBorder(6,14,6,14));
    JLabel title=(JLabel)region(actions,BorderLayout.CENTER);text(title,13,true);
    page.original.setPreferredSize(new Dimension(90,31));buttonIcon(page.original,"file");
    JPanel further=panel(named(page,"wb.flow.dashboard.further"));further.setPreferredSize(new Dimension(90,31));ApprovedUi.row(page.original.getParent(),8);
    JPanel old=panel(region(detail,BorderLayout.CENTER));JPanel selected=panel(region(old,BorderLayout.NORTH));
    JPanel heading=panel(region(selected,BorderLayout.NORTH));heading.setBorder(BorderFactory.createEmptyBorder(20,14,12,14));
    Component facts=region(selected,BorderLayout.CENTER);if(facts instanceof Container rows)for(Component row:rows.getComponents())if(row instanceof Container pair&&pair.getLayout() instanceof BorderLayout layout)layout.setHgap(13);
    page.detailTitle.putClientProperty("wb.font.heading",true);page.detailTitle.putClientProperty("wb.font.size",13);
    JPanel provenance=panel(named(page,"wb.scope.dashboard.detail.provenance"));disclosure(provenance);
    page.detailSummary.putClientProperty("wb.approved.fact",false);page.detailSummary.putClientProperty("wb.prose",true);page.detailSummary.setRows(0);page.detailSummary.setColumns(0);page.detailSummary.setLineWrap(true);page.detailSummary.setWrapStyleWord(true);page.detailSummary.setMargin(new Insets(0,0,0,0));page.detailSummary.setBorder(null);page.detailSummary.setOpaque(false);
    JPanel content=new DashboardColumn(page,selected,provenance);content.setName("wb.approved.dashboard.detail.column");detail.remove(old);detail.add(scroll(content));
    page.detailSummary.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){void changed(){content.revalidate();content.repaint();}public void insertUpdate(javax.swing.event.DocumentEvent e){changed();}public void removeUpdate(javax.swing.event.DocumentEvent e){changed();}public void changedUpdate(javax.swing.event.DocumentEvent e){changed();}});
    JPanel list=panel(split.getLeftComponent());Component listHeader=region(list,BorderLayout.NORTH);if(listHeader instanceof Container h)for(Component c:ApprovedLayouts.all(h))if(c instanceof JLabel label&&"해당 결과 목록".equals(label.getText())){label.setText("실행 결과");break;}
  }

  private static JScrollPane scroll(JPanel content){JScrollPane scroll=new JScrollPane(content);scroll.putClientProperty("wb.approved.scroll.flat",true);scroll.setBorder(null);scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);scroll.getVerticalScrollBar().setUnitIncrement(24);return scroll;}
  private static JPanel padded(Component content,int horizontal){JPanel panel=new JPanel(new BorderLayout());panel.putClientProperty("wb.surface",true);panel.setBorder(BorderFactory.createEmptyBorder(0,horizontal,0,horizontal));panel.add(content);return panel;}
  private static void factRows(JPanel facts,int rowHeight){
    Component[] cells=facts.getComponents();ApprovedUi.layout(facts,p->{int y=12;for(int i=0;i+1<cells.length;i+=2){place(cells[i],14,y,110,rowHeight);place(cells[i+1],137,y,p.getWidth()-151,rowHeight);y+=rowHeight;}});
    facts.setPreferredSize(new Dimension(300,24+rowHeight*(cells.length/2)));for(Component child:cells)if(child instanceof JLabel label)text(label,12,false);
  }
  private static JPanel badgeHost(JLabel label){JPanel host=new JPanel(null){public Dimension getPreferredSize(){return new Dimension(label.getPreferredSize().width+14,39);}public void doLayout(){place(label,0,(getHeight()-22)/2,Math.max(0,getWidth()-14),22);}};host.putClientProperty("wb.surface",true);host.add(label);return host;}
  private static void disclosure(JPanel panel){Component toggle=region(panel,BorderLayout.NORTH);if(toggle instanceof AbstractButton button){button.setPreferredSize(new Dimension(0,36));button.putClientProperty("wb.font.size",11);button.putClientProperty("wb.approved.disclosure",true);button.setIcon(null);}}
  private static void buttonIcon(AbstractButton button,String kind){if(button!=null){button.setIcon(new DesignIcon(kind));button.setIconTextGap(6);}}
  private static void decorate(JLabel label,WorkbenchUi.Tone tone,boolean fill,boolean border,boolean info,boolean link){text(label,11,false);label.setBorder(BorderFactory.createEmptyBorder());label.setOpaque(false);label.setUI(new ResearchLabelUi(tone,fill,border,info,link));}

  private abstract static class ReadingColumn extends JPanel implements Scrollable {
    ReadingColumn(){super(null);putClientProperty("wb.surface",true);}
    abstract int contentHeight(int width);
    public Dimension getPreferredSize(){return new Dimension(320,contentHeight(Math.max(120,getWidth())));}
    public Dimension getPreferredScrollableViewportSize(){return new Dimension(320,400);}
    public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction){return 24;}
    public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction){return Math.max(24,visible.height-24);}
    public boolean getScrollableTracksViewportWidth(){return true;}
    public boolean getScrollableTracksViewportHeight(){return getParent() instanceof JViewport v&&getPreferredSize().height<=v.getExtentSize().height;}
  }
  private static final class SearchColumn extends ReadingColumn {
    final JLabel url,state,caution;final JPanel facts,actions,original;final JSeparator first=new JSeparator(),second=new JSeparator();
    SearchColumn(JLabel url,JLabel state,JPanel facts,JLabel caution,JPanel actions,JPanel original){this.url=url;this.state=state;this.facts=facts;this.caution=caution;this.actions=actions;this.original=original;for(Component c:new Component[]{url,state,first,facts,second,caution,actions,original})add(c);}
    int urlHeight(int width){return Math.max(22,wrapped(url,Math.max(60,width-28),0).size()*url.getFontMetrics(url.getFont()).getHeight());}
    int cautionHeight(int width){return Math.max(22,wrapped(caution,Math.max(60,width-50),0).size()*caution.getFontMetrics(caution.getFont()).getHeight());}
    int contentHeight(int width){return 363+urlHeight(width)-22+cautionHeight(width)-22+original.getPreferredSize().height+14;}
    public void doLayout(){int w=getWidth()-28,delta=urlHeight(getWidth())-22,extra=cautionHeight(getWidth())-22;place(url,14,12,w,22+delta);place(state,14,48+delta,w,22);place(first,14,88+delta,w,1);place(facts,14,105+delta,w,140);place(second,14,255+delta,w,1);place(caution,14,277+delta,w,22+extra);place(actions,14,318+delta+extra,w,31);place(original,14,363+delta+extra,w,original.getPreferredSize().height);}
  }
  private static final class DashboardColumn extends ReadingColumn {
    final ScopeDashboardPanel page;final JPanel selected,provenance;final JSeparator divider=new JSeparator();final JLabel title=new JLabel("근거 요약"),note=new JLabel("관측된 응답 차이는 실제 취약점 판정이 아닙니다.");
    DashboardColumn(ScopeDashboardPanel page,JPanel selected,JPanel provenance){this.page=page;this.selected=selected;this.provenance=provenance;text(title,13,true);decorate(note,WorkbenchUi.Tone.NEUTRAL,false,false,true,false);for(Component c:new Component[]{selected,divider,title,page.detailSummary,note,provenance})add(c);}
    int summaryHeight(int width){JTextArea measured=new JTextArea(page.detailSummary.getText());measured.setFont(page.detailSummary.getFont());measured.setMargin(new Insets(0,0,0,0));measured.setBorder(null);measured.setLineWrap(true);measured.setWrapStyleWord(true);measured.setSize(Math.max(80,width-28),Short.MAX_VALUE);return Math.max(page.detailFacts.isVisible()?24:80,measured.getPreferredSize().height);}
    int noteHeight(int width){return Math.max(20,wrapped(note,Math.max(60,width-50),0).size()*note.getFontMetrics(note.getFont()).getHeight());}
    int contentHeight(int width){return selected.getPreferredSize().height+54+summaryHeight(width)+14+noteHeight(width)+14+provenance.getPreferredSize().height+14;}
    public void doLayout(){int w=getWidth(),top=selected.getPreferredSize().height;place(selected,0,0,w,top);place(divider,14,top,w-28,1);place(title,14,top+20,w-28,20);int height=summaryHeight(w);place(page.detailSummary,14,top+54,w-28,height);int y=top+54+height+14,h=noteHeight(w);place(note,14,y,w-28,h);place(provenance,14,y+h+14,w-28,provenance.getPreferredSize().height);}
  }
  private static List<String> wrapped(JLabel label,int available,int inset){
    String text=java.util.Objects.toString(label.getText(),"");FontMetrics metrics=label.getFontMetrics(label.getFont());List<String> lines=new ArrayList<>();StringBuilder line=new StringBuilder();int width=Math.max(20,available-inset);
    for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c=='\n'||line.length()>0&&metrics.stringWidth(line.toString()+c)>width){lines.add(line.toString());line.setLength(0);if(c=='\n')continue;}line.append(c);}lines.add(line.toString());return lines;
  }
  private static final class ResearchLabelUi extends BasicLabelUI {
    final WorkbenchUi.Tone tone;final boolean fill,border,info,link;
    ResearchLabelUi(WorkbenchUi.Tone tone,boolean fill,boolean border,boolean info,boolean link){this.tone=tone;this.fill=fill;this.border=border;this.info=info;this.link=link;}
    public Dimension getPreferredSize(JComponent component){JLabel label=(JLabel)component;int pad=fill?14:border?22:info?22:0;int width=label.getFontMetrics(label.getFont()).stringWidth(java.util.Objects.toString(label.getText(),""))+pad;int available=label.getWidth()>0?label.getWidth():Math.min(600,width);return new Dimension(width,fill?22:Math.max(20,wrapped(label,available,pad).size()*label.getFontMetrics(label.getFont()).getHeight()+(border?18:0)));}
    public void paint(Graphics graphics,JComponent component){JLabel label=(JLabel)component;Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);int w=label.getWidth(),h=label.getHeight();
      if(fill||border){g.setColor(fill?tone==WorkbenchUi.Tone.ACTIVE?WorkbenchUi.selected():WorkbenchUi.toneFill(tone):WorkbenchUi.raised());g.fillRoundRect(0,0,w-1,h-1,fill?5:7,fill?5:7);if(border){g.setColor(WorkbenchUi.line());g.drawRoundRect(0,0,w-1,h-1,7,7);}}
      Color ink=link?(WorkbenchUi.dark()?new Color(154,196,255):new Color(37,93,167)):tone==null?WorkbenchUi.ink():tone==WorkbenchUi.Tone.ACTIVE?WorkbenchUi.accent():WorkbenchUi.toneInk(tone);g.setColor(ink);g.setFont(label.getFont());int x=fill?7:border?11:info?22:0;
      if(info){Graphics2D icon=(Graphics2D)g.create();icon.translate(0,Math.max(0,(h-15)/2));icon.scale(15d/24,15d/24);icon.setStroke(new BasicStroke(1.5f));icon.drawOval(3,3,18,18);DesignIcon.line(icon,12,10,12,17);icon.fillOval(11,6,2,2);icon.dispose();}
      FontMetrics metrics=g.getFontMetrics();List<String> lines=wrapped(label,w,x+(fill?7:border?11:0));int y=(h-lines.size()*metrics.getHeight())/2+metrics.getAscent();for(String line:lines){g.drawString(line,x,y);y+=metrics.getHeight();}g.dispose();
    }
  }
  private static final class ResearchCell extends DefaultTableCellRenderer {
    final int kind;String display="";WorkbenchUi.Tone chip;
    ResearchCell(int kind){this.kind=kind;putClientProperty("html.disable",true);}
    public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focus,int row,int column){super.getTableCellRendererComponent(table,value,selected,focus,row,column);display=java.util.Objects.toString(value,"");chip=null;setToolTipText(display);setFont(table.getFont());setForeground(WorkbenchUi.ink());setBackground(selected?WorkbenchUi.selected():WorkbenchUi.surface());setBorder(BorderFactory.createEmptyBorder(0,11,0,11));
      if(kind==2&&display.equals("SCOPE 포함")){display="포함";chip=WorkbenchUi.Tone.SUCCESS;}
      if(kind==4&&display.contains("버전 조건 일치"))chip=WorkbenchUi.Tone.WARNING;
      setText(chip==null?display:"");return this;}
    protected void paintComponent(Graphics graphics){
      if(chip==null){super.paintComponent(graphics);return;}
      Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);
      g.setColor(getBackground());g.fillRect(0,0,getWidth(),getHeight());g.setFont(getFont());
      FontMetrics metrics=g.getFontMetrics();int available=Math.max(0,getWidth()-38);
      Rectangle view=new Rectangle(0,0,available,Math.max(0,getHeight())),icon=new Rectangle(),text=new Rectangle();
      String caption=SwingUtilities.layoutCompoundLabel(this,metrics,display,null,SwingConstants.CENTER,SwingConstants.LEFT,SwingConstants.CENTER,SwingConstants.TRAILING,view,icon,text,0);
      int width=Math.min(Math.max(0,getWidth()-22),metrics.stringWidth(caption)+16),height=Math.min(22,Math.max(0,getHeight()-4));
      int y=(getHeight()-height)/2;g.setColor(WorkbenchUi.toneFill(chip));g.fillRoundRect(11,y,width,height,5,5);
      g.setColor(WorkbenchUi.toneInk(chip));g.drawString(caption,19,(getHeight()-metrics.getHeight())/2+metrics.getAscent());g.dispose();
    }
  }
  private static final class DesignIcon implements Icon {
    private final String kind;
    DesignIcon(String kind){this.kind=kind;}
    public int getIconWidth(){return 15;}
    public int getIconHeight(){return 15;}
    static void line(Graphics2D graphics,double... points){Path2D path=new Path2D.Double();path.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);graphics.draw(path);}
    public void paintIcon(Component component,Graphics graphics,int x,int y){
      Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.translate(x,y);g.scale(15d/24,15d/24);
      g.setColor(component==null?WorkbenchUi.ink():component.getForeground());g.setStroke(new BasicStroke(1.6f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
      switch(kind){
        case "copy"->{g.draw(new java.awt.geom.RoundRectangle2D.Double(8,8,12,12,2,2));line(g,16,8,16,4,4,4,4,16,8,16);}
        case "external"->{line(g,14,3,21,3,21,10);line(g,21,3,11,13);line(g,11,3,3,3,3,21,21,21,21,13);}
        case "info"->{g.drawOval(3,3,18,18);line(g,12,10,12,17);g.fillOval(11,6,2,2);}
        default->{line(g,5,3,14,3,19,8,19,21,5,21,5,3);line(g,14,3,14,9,19,9);line(g,8,13,16,13);line(g,8,17,14,17);}
      }
      g.dispose();
    }
  }
}
