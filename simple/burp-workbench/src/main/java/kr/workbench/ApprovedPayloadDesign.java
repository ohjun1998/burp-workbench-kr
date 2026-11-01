package kr.workbench;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicLabelUI;
import javax.swing.text.*;
import kr.payloadrunner.Engine;

/** Approved preparation/results details over the retained payload controls and documents. */
final class ApprovedPayloadDesign {
  private ApprovedPayloadDesign() {}
  static Component find(Component root,String name){return ApprovedUi.find(root,name);}
  private static Component region(Container root,String side){return root.getLayout() instanceof BorderLayout b?b.getLayoutComponent(side):null;}
  private static void at(Component c,int x,int y,int w,int h){ApprovedUi.bounds(c,x,y,w,h);}
  private static void text(JComponent c,int size,boolean secondary){c.putClientProperty("html.disable",true);c.putClientProperty("wb.font.size",size);if(secondary)c.putClientProperty("wb.secondary",true);}
  private static void changed(Document document,Runnable action){document.addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){action.run();}public void removeUpdate(DocumentEvent e){action.run();}public void changedUpdate(DocumentEvent e){}});}
  private static List<Component> descendants(Component c){List<Component> out=new ArrayList<>();out.add(c);if(c instanceof Container p)for(Component child:p.getComponents())out.addAll(descendants(child));return out;}

  static void install(JPanel page){
    if(Boolean.TRUE.equals(page.getClientProperty("wb.approved.payload.details")))return;
    page.putClientProperty("wb.approved.payload.details",true);
    JTextArea request=(JTextArea)find(page,"wb.simple.payload.request");
    JComboBox<?> profile=(JComboBox<?>)find(page,"wb.payload.condition.profile"),encoding=(JComboBox<?>)find(page,"wb.payload.condition.encoding");
    JSpinner budget=(JSpinner)find(page,"wb.payload.condition.budget"),interval=(JSpinner)find(page,"wb.payload.condition.interval");
    JLabel position=(JLabel)find(page,"wb.payload.condition.position"),limit=(JLabel)find(page,"wb.payload.condition.limit"),scope=(JLabel)find(page,"wb.payload.condition.scope");
    JPanel body=(JPanel)find(page,"wb.payload.conditions"),candidates=(JPanel)find(page,"wb.payload.candidates"),advanced=(JPanel)find(page,"wb.payload.advanced");
    JLabel[] labels=new JLabel[4];JComponent[] controls={profile,encoding,budget,interval};String[] captions={"검사 유형","인코딩","총 요청 한도","요청 간격"};
    for(int i=0;i<controls.length;i++){
      Container row=controls[i].getParent();Component existing=region(row,BorderLayout.WEST);
      labels[i]=existing instanceof JLabel l?l:new JLabel();labels[i].setText(captions[i]);labels[i].setLabelFor(controls[i]);text(labels[i],12,true);
    }
    budget.putClientProperty("wb.approved.suffix","회");interval.putClientProperty("wb.approved.suffix"," ms");
    ApprovedUi.combo(profile,112);ApprovedUi.combo(encoding,96);
    ListCellRenderer<?> originalEncoding=encoding.getRenderer();
    @SuppressWarnings({"unchecked","rawtypes"}) ListCellRenderer<Object> renderer=(ListCellRenderer)originalEncoding;
    @SuppressWarnings({"unchecked","rawtypes"}) JComboBox<Object> encodingControl=(JComboBox)encoding;
    encodingControl.setRenderer((list,value,index,selected,focus)->{Component c=renderer.getListCellRendererComponent(list,value,index,selected,focus);if(c instanceof JLabel label)label.setText(encoding(value));return c;});
    body.removeAll();body.setBorder(null);
    for(int i=0;i<controls.length;i++){body.add(labels[i]);body.add(controls[i]);}
    JSeparator separator=new JSeparator();separator.setName("wb.approved.payload.position.separator");body.add(separator);
    JLabel heading=new JLabel("검사 위치");heading.setName("wb.approved.payload.position.heading");text(heading,12,false);heading.putClientProperty("wb.font.heading",true);body.add(heading);
    JLabel location=new JLabel();location.setName("wb.approved.payload.position.key");text(location,11,false);location.setUI(new BadgeUi(()->location.getText(),()->WorkbenchUi.selected(),()->WorkbenchUi.accent()));body.add(location);
    position.setUI(new DerivedLabelUi(()->positionCount(position.getText())));text(position,11,true);body.add(position);
    limit.setUI(new DerivedLabelUi(()->"기준·대조 요청도 총 요청 한도에 포함됩니다."));text(limit,11,true);limit.setIcon(WorkspaceIcons.of("info"));limit.setIconTextGap(7);body.add(limit);
    disclosure(candidates);disclosure(advanced);body.add(candidates);body.add(advanced);
    JScrollPane scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,body);if(scroll!=null){scroll.putClientProperty("wb.approved.scroll.flat",true);scroll.setBorder(null);}
    final int[] labelWidths={47,34,61,47},fieldOffsets={55,40,69,55},fieldWidths={112,96,180,180};
    body.setLayout(new LayoutManager(){
      public void addLayoutComponent(String n,Component c){}public void removeLayoutComponent(Component c){}
      private int height(){return 302+candidates.getPreferredSize().height+12+advanced.getPreferredSize().height+14;}
      public Dimension preferredLayoutSize(Container p){return new Dimension(283,height());}public Dimension minimumLayoutSize(Container p){return new Dimension(220,Math.min(400,height()));}
      public void layoutContainer(Container p){int inner=Math.max(0,p.getWidth()-28);for(int i=0;i<4;i++){int y=12+i*43,x=14+fieldOffsets[i],width=i>=2&&workspaceWidth(page)<=1300?140:fieldWidths[i];at(labels[i],14,y,labelWidths[i],31);at(controls[i],x,y,Math.min(width,Math.max(60,p.getWidth()-14-x)),31);}at(separator,14,188,inner,1);at(heading,14,205,inner,20);int chip=location.isVisible()?location.getPreferredSize().width:0;at(location,14,228,chip,22);at(position,14+(chip==0?0:chip+5),228,Math.max(0,inner-chip-5),22);at(limit,14,263,inner,27);int y=302,h=candidates.getPreferredSize().height;at(candidates,14,y,inner,h);y+=h+12;at(advanced,14,y,inner,advanced.getPreferredSize().height);}
    });
    JToggleButton candidateToggle=(JToggleButton)candidates.getComponent(0),advancedToggle=(JToggleButton)advanced.getComponent(0);candidateToggle.addItemListener(e->body.revalidate());advancedToggle.addItemListener(e->body.revalidate());
    JPanel requestHeader=(JPanel)find(page,"wb.placement.payload.request");
    for(Component c:descendants(requestHeader))if(c instanceof JLabel label&&"검사 요청".equals(label.getText())){text(label,13,false);label.putClientProperty("wb.font.heading",true);label.setBorder(BorderFactory.createEmptyBorder(0,12,0,0));}
    requestHeader.setBorder(new AbstractBorder(){public void paintBorder(Component c,Graphics g,int x,int y,int w,int h){g.setColor(WorkbenchUi.line());g.drawLine(x,y+h-1,x+w,y+h-1);}});
    JPanel prepareActions=(JPanel)find(page,"wb.actions.payload.prepare");Component mark=find(page,"wb.payload.mark"),edit=find(page,"wb.flow.payload.edit");prepareActions.setPreferredSize(new Dimension(168,43));ApprovedUi.layout(prepareActions,p->{at(mark,0,6,84,31);at(edit,90,6,65,31);});
    if(requestHeader.getParent() instanceof JPanel requestCard)ApprovedUi.roundedLegacy(requestCard);
    firstLine(request);
    AbstractButton start=(AbstractButton)find(page,"wb.simple.payload.start"),repeater=(AbstractButton)find(page,"wb.payload.repeater");
    start.setIcon(WorkspaceIcons.of("play"));repeater.putClientProperty("wb.no.auto.icon",true);repeater.setIcon(null);
    JLabel selected=(JLabel)find(page,"wb.payload.results.selection");
    if(selected!=null){selected.putClientProperty("wb.approved.notice",false);selected.putClientProperty("wb.embedded.style",(Runnable)()->{selected.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(11f)));if(!(selected.getUI() instanceof ResultNoticeUi))selected.setUI(new ResultNoticeUi());selected.setOpaque(false);selected.setBorder(BorderFactory.createEmptyBorder(0,11,0,11));});}
    for(Component c:descendants(page))if(c instanceof JToggleButton button&&button.getName()!=null&&button.getName().startsWith("wb.payload.results.filter.")){button.putClientProperty("wb.filter.segment",true);button.putClientProperty("wb.segment.button",true);button.putClientProperty("wb.approved.filter.surface",true);}
    JPanel facts=(JPanel)find(page,"wb.reference.summary");JTabbedPane steps=(JTabbedPane)find(page,"wb.payload.steps");JTable results=(JTable)find(page,"wb.payload.results.table");AbstractButton stop=(AbstractButton)find(page,"wb.simple.payload.stop");
    List<JLabel> fields=new ArrayList<>();for(Component c:facts.getComponents())if(c instanceof JLabel l)fields.add(l);
    JLabel target=fields.get(0),type=fields.get(1),account=fields.get(2),sourceStatus=fields.size()>3?fields.get(3):new JLabel();
    JLabel mode=new JLabel(),profileSummary=new JLabel(),requests=new JLabel(),right=new JLabel();
    mode.setName("wb.approved.payload.method.state");profileSummary.setName("wb.approved.payload.profile.summary");requests.setName("wb.approved.payload.request.count");right.setName("wb.approved.payload.limit.summary");
    for(JLabel label:new JLabel[]{target,type,account,profileSummary,requests,right,mode,scope})text(label,11,label!=mode&&label!=scope&&label!=target&&label!=requests);
    mode.setUI(new BadgeUi(mode::getText,()->steps.getSelectedIndex()==0?WorkbenchUi.selected():WorkbenchUi.toneFill(WorkbenchUi.tone(mode.getText())),()->steps.getSelectedIndex()==0?WorkbenchUi.accent():WorkbenchUi.toneInk(WorkbenchUi.tone(mode.getText()))));
    scope.setUI(new BadgeUi(()->scope.getText().contains("제한 해제")?"SCOPE 해제":"SCOPE 적용",()->scope.getText().contains("제한 해제")?WorkbenchUi.raised():WorkbenchUi.toneFill(WorkbenchUi.Tone.SUCCESS),()->scope.getText().contains("제한 해제")?kr.packetui.VisualTheme.muted():WorkbenchUi.toneInk(WorkbenchUi.Tone.SUCCESS)));
    facts.removeAll();for(Component c:new Component[]{mode,target,account,type,profileSummary,scope,requests,sourceStatus,right})facts.add(c);type.setVisible(false);sourceStatus.setVisible(false);
    Runnable refresh=()->{
      String raw=request.getText();boolean result=steps.getSelectedIndex()==1;int positions=marks(raw);String key=location(raw);location.setText(key);location.setVisible(positions>0);position.setToolTipText(position.getText());limit.setToolTipText(limit.getText());
      String state=sourceStatus.getText()==null?"":sourceStatus.getText();boolean error=state.contains("오류")||state.contains("실패"),interrupted=state.contains("중지")||state.contains("중단");mode.setText(result?(stop.isEnabled()?"검사 중":error?"검사 오류":interrupted?"검사 중단":results.getModel().getRowCount()>0?"검사 완료":"실행 대기"):method(raw));
      profileSummary.setText(String.valueOf(type.getText())+" · "+encoding(encoding.getSelectedItem()));requests.setText("실제 요청 "+results.getModel().getRowCount()+" / 최대 "+budget.getValue()+"회");
      target.setVisible(!result);scope.setVisible(!result);requests.setVisible(result);right.setText(result?(error||interrupted||state.contains("한도")?state:"중단·한도·오류 내역은 상세 근거에서 확인"):"위치 "+positions+"곳 · 최대 "+budget.getValue()+"회 전송");right.setToolTipText(result?sourceStatus.getText():limit.getText());facts.revalidate();facts.repaint();body.revalidate();body.repaint();
    };
    ApprovedUi.layout(facts,p->{boolean result=steps.getSelectedIndex()==1;int rw=Math.min(right.getPreferredSize().width,Math.max(0,p.getWidth()/2)),bound=Math.max(0,p.getWidth()-rw-16),x=0;JLabel[] row=result?new JLabel[]{mode,requests,profileSummary,account}:new JLabel[]{mode,target,account,profileSummary,scope};int desired=0;for(JLabel l:row)desired+=Math.min(l==target?180:l==account?150:l==profileSummary?190:1000,l.getPreferredSize().width)+12;int excess=Math.max(0,desired-12-bound);for(JLabel l:row){int w=Math.min(l==target?180:l==account?150:l==profileSummary?190:1000,l.getPreferredSize().width);if(excess>0&&(l==target||l==account||l==profileSummary)){int reduce=Math.min(excess,Math.max(0,w-65));w-=reduce;excess-=reduce;}at(l,x,(p.getHeight()-(l==mode||l==scope?22:26))/2,w,l==mode||l==scope?22:26);x+=w+12;}at(type,0,0,0,0);at(sourceStatus,0,0,0,0);sourceStatus.setVisible(false);at(right,p.getWidth()-rw,0,rw,p.getHeight());});
    changed(request.getDocument(),refresh);for(JLabel label:new JLabel[]{target,type,account,scope,position,sourceStatus})label.addPropertyChangeListener("text",e->refresh.run());encoding.addActionListener(e->refresh.run());profile.addActionListener(e->refresh.run());budget.addChangeListener(e->refresh.run());stop.addPropertyChangeListener("enabled",e->refresh.run());results.getModel().addTableModelListener(e->refresh.run());steps.addChangeListener(e->refresh.run());refresh.run();
  }

  private static String encoding(Object value){return value==Engine.Encoding.URL?"URL 인코딩":value==Engine.Encoding.JSON?"JSON":value==Engine.Encoding.RAW?"RAW":String.valueOf(value);}
  private static int workspaceWidth(Component component){for(Component c=component;c!=null;c=c.getParent())if("wb.root".equals(c.getName()))return c.getWidth();Component root=SwingUtilities.getRoot(component);return root==null?1600:root.getWidth();}
  private static String method(String raw){String first=raw.lines().findFirst().orElse("");int space=first.indexOf(' ');String value=space<0?"":first.substring(0,space);return value.matches("[A-Z]{1,12}")?value:"요청";}
  private static int marks(String raw){int count=0,at=0;while((at=raw.indexOf(Engine.MARK,at))>=0){count++;at+=Engine.MARK.length();}return count;}
  private static String positionCount(String raw){Matcher m=Pattern.compile("검사 위치 · (\\d+)곳").matcher(raw==null?"":raw);return m.matches()?m.group(1)+"곳 지정됨":raw==null?"":raw;}
  private static String location(String raw){String first=raw.lines().findFirst().orElse("");int q=first.indexOf('?');if(q>=0)for(String part:first.substring(q+1).split("[& ]"))if(part.contains(Engine.MARK)){int eq=part.indexOf('=');return "Query · "+shortKey(eq>0?part.substring(0,eq):"위치");}int split=raw.indexOf("\r\n\r\n");if(split<0)split=raw.indexOf("\n\n");if(split>=0&&raw.substring(split).contains(Engine.MARK)){for(String part:raw.substring(split).strip().split("&"))if(part.contains(Engine.MARK)&&part.contains("="))return "Body · "+shortKey(part.substring(0,part.indexOf('=')));return "Body";}return "요청";}
  private static String shortKey(String value){String safe=value.replaceAll("[\\p{Cntrl}<>]","");return safe.length()>24?safe.substring(0,24)+"…":safe;}
  private static void disclosure(JPanel panel){
    if(panel.getLayout() instanceof BorderLayout layout)layout.setVgap(0);
    JToggleButton toggle=(JToggleButton)panel.getComponent(0);toggle.putClientProperty("wb.approved.disclosure",true);toggle.setPreferredSize(new Dimension(0,36));
  }
  private static class DerivedLabelUi extends BasicLabelUI {
    final java.util.function.Supplier<String> value;DerivedLabelUi(java.util.function.Supplier<String> value){this.value=value;}
    public void paint(Graphics graphics,JComponent component){JLabel label=(JLabel)component;Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setFont(label.getFont());g.setColor(label.getForeground());int x=0;if(label.getIcon()!=null){label.getIcon().paintIcon(label,g,0,(label.getHeight()-label.getIcon().getIconHeight())/2);x=label.getIcon().getIconWidth()+label.getIconTextGap();}FontMetrics fm=g.getFontMetrics();g.drawString(value.get(),x,(label.getHeight()-fm.getHeight())/2+fm.getAscent());g.dispose();}
  }
  private static final class BadgeUi extends DerivedLabelUi {
    final java.util.function.Supplier<Color> fill,ink;BadgeUi(java.util.function.Supplier<String> value,java.util.function.Supplier<Color> fill,java.util.function.Supplier<Color> ink){super(value);this.fill=fill;this.ink=ink;}
    public Dimension getPreferredSize(JComponent c){return new Dimension(c.getFontMetrics(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(11f))).stringWidth(value.get())+16,22);}
    public void paint(Graphics graphics,JComponent c){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setColor(fill.get());g.fillRoundRect(0,0,c.getWidth(),c.getHeight(),5,5);g.setColor(ink.get());g.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(11f)));FontMetrics fm=g.getFontMetrics();g.drawString(value.get(),8,(c.getHeight()-fm.getHeight())/2+fm.getAscent());g.dispose();}
  }
  private static final class ResultNoticeUi extends BasicLabelUI {
    public void paint(Graphics graphics,JComponent c){JLabel label=(JLabel)c;Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setColor(WorkbenchUi.raised());g.fillRoundRect(0,0,c.getWidth()-1,c.getHeight()-1,7,7);g.setColor(WorkbenchUi.line());g.drawRoundRect(0,0,c.getWidth()-1,c.getHeight()-1,7,7);String value=label.getText()==null?"":label.getText();WorkbenchUi.Tone tone=value.contains("오류")||value.contains("실패")?WorkbenchUi.Tone.ERROR:value.contains("반사")||value.contains("관찰")||value.contains("검토")?WorkbenchUi.Tone.WARNING:WorkbenchUi.tone(value);g.setColor(WorkbenchUi.toneInk(tone));g.setFont(label.getFont());FontMetrics fm=g.getFontMetrics();g.drawString(value,11,(c.getHeight()-fm.getHeight())/2+fm.getAscent());g.dispose();}
  }
  private static void firstLine(JTextArea request){
    request.putClientProperty("wb.approved.firstline.gutter",true);
    final Object[] tag={null};final Highlighter[] owner={null};LayeredHighlighter.LayerPainter painter=new LayeredHighlighter.LayerPainter(){
      public void paint(Graphics g,int p0,int p1,Shape bounds,JTextComponent c){}
      public Shape paintLayer(Graphics graphics,int p0,int p1,Shape bounds,JTextComponent c,View view){try{Rectangle2D row=c.modelToView2D(0);if(row==null)return null;Graphics2D g=(Graphics2D)graphics.create();int y=(int)row.getY();g.setColor(WorkbenchUi.selected());g.fillRect(0,y,c.getWidth(),(int)row.getHeight());g.dispose();return new Rectangle(0,y,c.getWidth(),(int)row.getHeight());}catch(BadLocationException e){return null;}}
    };
    Runnable update=()->{try{int end=request.getText().indexOf('\n');if(end<0)end=request.getDocument().getLength();Highlighter highlighter=request.getHighlighter();if(owner[0]!=highlighter){owner[0]=highlighter;tag[0]=null;}if(highlighter==null)return;if(end==0){if(tag[0]!=null)highlighter.removeHighlight(tag[0]);tag[0]=null;}else if(tag[0]==null)tag[0]=highlighter.addHighlight(0,end,painter);else highlighter.changeHighlight(tag[0],0,end);}catch(BadLocationException invalid){throw new IllegalStateException(invalid);}};changed(request.getDocument(),update);request.addPropertyChangeListener("highlighter",e->SwingUtilities.invokeLater(update));update.run();
  }
}
