package kr.workbench;

import java.awt.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.TableColumn;

/** Screen-specific composition from the approved design, over the existing engines and controllers. */
final class ApprovedLayouts {
  private ApprovedLayouts() {}
  static Component n(Component root,String name){return ApprovedUi.find(root,name);}
  static Container box(Component c){return (Container)c;}
  static JPanel panel(Component c){return (JPanel)c;}
  static Component region(Container p,String side){return p.getLayout() instanceof BorderLayout b?b.getLayoutComponent(side):null;}
  static void at(Component c,int x,int y,int w,int h){ApprovedUi.bounds(c,x,y,w,h);}
  static void simplePage(JPanel page,int headerHeight,int gap,int bottom){
    Component north=region(page,BorderLayout.NORTH),center=region(page,BorderLayout.CENTER),south=region(page,BorderLayout.SOUTH);
    page.putClientProperty("wb.approved.page.footer",south);
    page.setBorder(BorderFactory.createEmptyBorder());
    ApprovedUi.layout(page,p->{int w=p.getWidth()-32,h=p.getHeight();at(north,16,14,w,headerHeight);at(center,16,14+headerHeight+gap,w,h-14-headerHeight-gap-bottom);if(south!=null&&south.getParent()==page)at(south,16,h-30,w,22);});
  }
  static void listFooter(JPanel page,Container list,int height){Component footer=region(page,BorderLayout.SOUTH);if(footer==null)footer=(Component)page.getClientProperty("wb.approved.page.footer");if(footer==null)return;footer.setPreferredSize(new Dimension(0,height));list.add(footer,BorderLayout.SOUTH);}
  static void widths(JTable table,int[] models,String[] headers,int... sizes){
    for(int i=0;i<models.length;i++){int view=table.convertColumnIndexToView(models[i]);TableColumn column;
      if(view<0){column=new TableColumn(models[i]);table.addColumn(column);view=table.getColumnCount()-1;}else column=table.getColumnModel().getColumn(view);
      table.moveColumn(view,i);column.setMinWidth(1);column.setMaxWidth(Integer.MAX_VALUE);column.setPreferredWidth(sizes[i]);column.setWidth(sizes[i]);if(headers!=null)column.setHeaderValue(headers[i]);}
    for(int i=table.getColumnCount()-1;i>=models.length;i--)table.removeColumn(table.getColumnModel().getColumn(i));
    table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
  }
  static void install(Component workspace){
    JPanel auth=(JPanel)n(workspace,"wb.auth.record");if(auth!=null)auth(auth);
    JPanel bounty=(JPanel)n(workspace,"wb.bounty.support");if(bounty!=null)bounty(bounty);
    JPanel js=ancestor(n(workspace,"wb.js.findings"),JsAnalysisPanel.class);if(js!=null)js(js);
    JPanel environment=ancestor(n(workspace,"wb.environment.workspace"),EnvironmentPanel.class);if(environment!=null)environment(environment);
    JPanel response=(JPanel)n(workspace,"wb.response.recon");if(response!=null)response(response);
    JPanel exposure=(JPanel)n(workspace,"wb.exposure");if(exposure!=null)exposure(exposure);
    JPanel activity=(JPanel)n(workspace,"wb.activity");if(activity!=null)activity(activity);
    JPanel data=(JPanel)n(workspace,"wb.data.tools");if(data!=null)data(data);
    JPanel payload=(JPanel)n(workspace,"wb.payload.root");if(payload!=null)payload(payload);
    JPanel dashboard=(JPanel)n(workspace,"wb.scope.dashboard.root");if(dashboard!=null)dashboard(dashboard);
    JPanel mapper=(JPanel)n(workspace,"fm.root");if(mapper!=null)mapper(mapper);
    decorate(workspace);
    JPanel traffic=ancestor(n(workspace,"wb.traffic.account"),ApprovedTrafficPanel.class);if(traffic!=null)ApprovedReadingDesign.traffic(traffic);
    if(auth!=null)ApprovedIdentityDesign.auth(auth);
    if(bounty!=null)ApprovedEvidenceDesign.bounty(bounty);
    if(js!=null)ApprovedReadingDesign.js(js);
    if(environment!=null)ApprovedIdentityDesign.environment(environment);
    if(response!=null)ApprovedReadingDesign.response(response);
    if(exposure!=null)ApprovedResearchDesign.exposure(exposure);
    if(activity!=null)ApprovedEvidenceDesign.activity(activity);
    if(data!=null)ApprovedReadingDesign.data(data);
    if(payload!=null)ApprovedPayloadDesign.install(payload);
    if(dashboard!=null)ApprovedResearchDesign.dashboard(dashboard);
    if(mapper!=null)ApprovedMapperDesign.install(mapper);
  }
  static JPanel ancestor(Component c,Class<?> type){for(Component at=c;at!=null;at=at.getParent())if(type.isInstance(at))return (JPanel)at;return null;}
  static void decorate(Component root){
    if(root instanceof JComboBox<?> combo)ApprovedUi.combo(combo,Math.min(180,Math.max(80,combo.getPreferredSize().width)));
    if(root instanceof JTextArea text&&Boolean.TRUE.equals(text.getClientProperty("wb.packet.syntax"))){text.putClientProperty("wb.approved.packet",true);text.setLineWrap(false);}
    if(root instanceof Container p)for(Component child:p.getComponents())decorate(child);
  }
  static void auth(JPanel page){
    JPanel top=panel(n(page,"wb.auth.actions").getParent());
    JPanel actions=panel(n(page,"wb.auth.actions")),cards=panel(n(page,"wb.auth.account.cards"));
    JSplitPane lists=(JSplitPane)n(page,"wb.auth.tables");JPanel history=panel(lists.getTopComponent());
    JPanel filters=panel(n(page,"wb.placement.auth.execution"));JPanel filterFields=panel(n(page,"wb.actions.auth.filters"));
    JTable table=(JTable)n(page,"wb.auth.history");history.putClientProperty("wb.card",true);history.putClientProperty("wb.surface",true);((BorderLayout)history.getLayout()).setVgap(0);
    widths(table,new int[]{6,1,2,3,8,7},new String[]{"메서드","요청 경로","계정 1","계정 2","비로그인","검토 상태"},154,402,165,165,183,300);
    table.getColumnModel().getColumn(1).setCellRenderer(new WorkbenchUi.Cells());
    ((AuthComparePanel)page).updateHistoryHeaders(((AuthComparePanel)page).current());
    simplePage(page,85,10,14);
    ApprovedUi.layout(top,p->{int w=p.getWidth();at(actions,0,0,w,34);at(cards,0,44,w,41);for(Component c:p.getComponents())if(c!=actions&&c!=cards)at(c,0,0,0,0);});
    cards.setBorder(null);cards.setLayout(new GridLayout(1,0,10,0));
    JPanel title=new JPanel(new BorderLayout());JLabel count=new JLabel();count.putClientProperty("wb.font.heading",true);count.putClientProperty("wb.font.size",13);count.setBorder(BorderFactory.createEmptyBorder(0,12,0,0));
    Runnable titleText=()->count.setText("비교 기록 · "+table.getRowCount()+"건");table.getModel().addTableModelListener(e->titleText.run());titleText.run();title.add(count);title.add(filters,BorderLayout.EAST);title.putClientProperty("wb.surface",true);
    history.remove(region(history,BorderLayout.NORTH));history.add(title,BorderLayout.NORTH);title.setPreferredSize(new Dimension(0,44));
    filterFields.remove(n(page,"wb.auth.history.visible"));
    filters.setPreferredSize(new Dimension(644,44));filterFields.setPreferredSize(new Dimension(486,34));
    Component filterRight=region(filterFields,BorderLayout.EAST);ApprovedUi.layout(filterFields,p->{at(n(page,"wb.auth.history.search"),0,6,Math.max(100,p.getWidth()-128),31);at(filterRight,p.getWidth()-120,6,110,31);});
    ApprovedUi.row(box(filterRight),0);
    Component context=n(page,"wb.actions.auth.context");ApprovedUi.row(box(context),8);
    JLabel origin=((AuthComparePanel)page).accountOrigin;origin.setPreferredSize(new Dimension(180,31));origin.setBorder(kr.packetui.VisualTheme.border(3,8,3,8));origin.setOpaque(true);origin.setBackground(WorkbenchUi.surface());
    Component detection=n(page,"wb.auth.detect");if(detection instanceof AbstractButton b)b.putClientProperty("wb.approved.analysis",true);
    inline(panel(n(page,"wb.auth.inline")));listFooter(page,history,22);
    fixedSplit(lists,JSplitPane.VERTICAL_SPLIT,10,p->Math.max(150,Math.round(p.getHeight()*.2688f)));
  }
  static void inline(JPanel page){
    page.putClientProperty("wb.card",true);page.putClientProperty("wb.surface",true);((BorderLayout)page.getLayout()).setVgap(0);
    Component heading=n(page,"wb.auth.inline.heading");JTabbedPane tabs=(JTabbedPane)n(page,"wb.auth.inline.tabs");JPanel readers=panel(tabs.getParent());
    JPanel account=panel(n(page,"wb.auth.inline.account.actions"));ApprovedUi.combo((JComboBox<?>)n(page,"wb.auth.inline.account"),129);((BorderLayout)readers.getLayout()).setVgap(0);JPanel choices=ApprovedUi.tabBar(tabs);
    JPanel tools=CompactWorkbenchUi.toolbar(choices,account);tools.setBorder(BorderFactory.createEmptyBorder(8,12,8,12));readers.remove(account);readers.add(tools,BorderLayout.NORTH);tools.setPreferredSize(new Dimension(0,50));
    if(heading instanceof JPanel p)p.setPreferredSize(new Dimension(0,39));
    JLabel note=new JLabel("응답 차이를 확인한 뒤 실제 권한 정책과 대조하세요.");note.putClientProperty("wb.font.size",11);note.putClientProperty("wb.secondary",true);note.setBorder(BorderFactory.createEmptyBorder(10,12,10,12));note.setPreferredSize(new Dimension(0,42));page.add(note,BorderLayout.SOUTH);
    ((AuthInlinePreview)page).approvedHeaders=true;((AuthInlinePreview)page).refreshApprovedHeaders();
    JSplitPane packets=(JSplitPane)tabs.getComponentAt(0);fixedSplit(packets,JSplitPane.HORIZONTAL_SPLIT,10,p->(p.getWidth()-10)/2);
  }
  static void bounty(JPanel page){
    Component sections=n(page,"wb.simple.bounty.sections");if(sections!=null)sections.setVisible(false);page.setBorder(null);
    JTabbedPane pages=((BountySupportPanel)page).pages;ApprovedUi.tabs(pages);
    JPanel evidence=(JPanel)pages.getComponentAt(3);simplePage(evidence,51,12,14);
    JPanel document=panel(n(page,"wb.flow.bounty.document"));Component top=document.getParent();
    ApprovedUi.layout(box(top),p->{at(document,0,0,p.getWidth(),51);});
    JPanel title=panel(n(page,"wb.placement.bounty.title"));Component draft=n(page,"wb.bounty.evidence.draft.state");Component titleField=null;
    for(Component c:title.getComponents())if(c instanceof JTextField)titleField=c;final Component field=titleField;
    JLabel metadata=new JLabel();metadata.putClientProperty("html.disable",true);metadata.putClientProperty("wb.font.size",11);metadata.putClientProperty("wb.secondary",true);title.add(metadata);draft.setVisible(false);
    Runnable draftText=()->{String text=((JTextArea)draft).getText();metadata.setText(text.replace("\n"," · "));metadata.setToolTipText(text);};((JTextArea)draft).getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){draftText.run();}public void removeUpdate(javax.swing.event.DocumentEvent e){draftText.run();}public void changedUpdate(javax.swing.event.DocumentEvent e){draftText.run();}});draftText.run();
    ApprovedUi.layout(title,p->{at(field,0,0,p.getWidth(),31);at(metadata,0,34,p.getWidth(),17);at(draft,0,0,0,0);});((BorderLayout)document.getLayout()).setHgap(13);
    JPanel actions=panel(n(page,"wb.actions.bounty.document"));actions.setPreferredSize(new Dimension(330,51));ApprovedUi.row(actions,8);
    JSplitPane body=(JSplitPane)n(page,"wb.bounty.evidence.body"),detail=(JSplitPane)n(page,"wb.bounty.evidence.detail");
    fixedSplit(body,JSplitPane.HORIZONTAL_SPLIT,12,p->Math.round((p.getWidth()-24)*.228571f));fixedSplit(detail,JSplitPane.HORIZONTAL_SPLIT,12,p->Math.round((p.getWidth()-12)*.592593f));
    JSplitPane notes=(JSplitPane)n(page,"wb.bounty.evidence.notes");fixedSplit(notes,JSplitPane.VERTICAL_SPLIT,12,p->Math.min(180,p.getHeight()/3));
    JPanel list=panel(n(page,"wb.bounty.evidence.list"));list.putClientProperty("wb.card",true);list.putClientProperty("wb.surface",true);Component listHead=region(list,BorderLayout.NORTH);listHead.setPreferredSize(new Dimension(0,44));((BorderLayout)list.getLayout()).setVgap(0);((BountySupportPanel)page).evidenceListTitle.setBorder(BorderFactory.createEmptyBorder(0,12,0,0));
    JTextField search=(JTextField)n(page,"wb.bounty.evidence.search");JPanel searchGroup=panel(search.getParent());searchGroup.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
    search.setPreferredSize(new Dimension(0,31));Component itemBar=n(page,"wb.flow.bounty.items");searchGroup.setPreferredSize(new Dimension(0,99));ApprovedUi.layout(searchGroup,p->{at(search,12,12,p.getWidth()-24,31);at(itemBar,12,55,p.getWidth()-24,31);});Component itemMenu=region(box(itemBar),BorderLayout.CENTER),undoGroup=region(box(itemBar),BorderLayout.EAST);ApprovedUi.row(box(undoGroup),0);ApprovedUi.layout(box(itemBar),p->{at(itemMenu,0,0,89,31);at(undoGroup,p.getWidth()-89,0,89,31);});
    if(n(page,"wb.bounty.evidence.undo") instanceof JButton undo){undo.setText("실행 취소");undo.setPreferredSize(new Dimension(89,31));}
    JTable evidenceTable=((BountySupportPanel)page).evidenceTable; widths(evidenceTable,new int[]{0,3},new String[]{"증거","유형"},200,100);evidenceTable.putClientProperty("wb.approved.rowHeight",32);evidenceTable.putClientProperty("wb.approved.evidence.types",(java.util.function.IntFunction<String>)row->row>=0&&row<((BountySupportPanel)page).evidence.size()?((BountySupportPanel)page).evidence.get(row).sourceKind():"");
    BountySupportPanel controller=(BountySupportPanel)page;JPanel selectedContext=panel(n(page,"wb.bounty.evidence.selected.context"));selectedContext.setPreferredSize(new Dimension(0,28));for(Component c:selectedContext.getComponents())if(c instanceof JLabel label){label.putClientProperty("wb.font.size",11);label.putClientProperty("wb.secondary",true);}ApprovedUi.chip((JLabel)n(page,"wb.bounty.evidence.draft.badge"));
    for(String id:new String[]{"impact","repro"}){JPanel fieldPanel=panel(n(page,"wb.bounty.evidence.field."+id));Component caption=region(fieldPanel,BorderLayout.NORTH),editor=region(fieldPanel,BorderLayout.CENTER);ApprovedUi.layout(fieldPanel,p->{at(caption,0,0,p.getWidth(),18);int y=id.equals("impact")?25:22;at(editor,0,y,p.getWidth(),p.getHeight()-y);});}
    fixedSplit(notes,JSplitPane.VERTICAL_SPLIT,20,p->Math.min(179,p.getHeight()/3));JPanel writer=panel(selectedContext.getParent());Component quiet=region(writer,BorderLayout.SOUTH);quiet.setPreferredSize(new Dimension(0,32));if(quiet instanceof JTextArea area)area.putClientProperty("wb.approved.note",true);
    JPanel replay=(JPanel)pages.getComponentAt(1);simplePage(replay,34,12,14);
    JSplitPane replayBody=(JSplitPane)n(page,"wb.bounty.flow.body");fixedSplit(replayBody,JSplitPane.HORIZONTAL_SPLIT,12,p->Math.round((p.getWidth()-12)*.59183f));
    JSplitPane results=(JSplitPane)n(page,"wb.bounty.flow.results");fixedSplit(results,JSplitPane.VERTICAL_SPLIT,10,p->Math.min(243,Math.max(150,Math.round(p.getHeight()*.31f))));
    page.setLayout(new BorderLayout());page.add(pages);if(sections!=null){page.add(sections,BorderLayout.NORTH);sections.setVisible(false);}
    JPanel evidenceStatus=new JPanel(new BorderLayout()),replayStatus=new JPanel(new BorderLayout());list.add(evidenceStatus,BorderLayout.SOUTH);box(results.getTopComponent()).add(replayStatus,BorderLayout.SOUTH);
    Runnable statusPosition=()->{JLabel status=((BountySupportPanel)page).state;Container host=pages.getSelectedIndex()==3?evidenceStatus:pages.getSelectedIndex()==1?replayStatus:page;host.add(status,BorderLayout.SOUTH);page.revalidate();};pages.addChangeListener(e->statusPosition.run());statusPosition.run();
  }
  interface Divider {int location(JSplitPane p);}
  static void fixedSplit(JSplitPane pane,int orientation,int size,Divider divider){
    pane.putClientProperty("wb.workflow.fixedLayout",true);pane.putClientProperty("wb.split.pending",false);pane.setOrientation(orientation);pane.setDividerSize(size);pane.putClientProperty("wb.approved.divider",(Runnable)()->{pane.setDividerSize(size);pane.setDividerLocation(Math.max(0,divider.location(pane)));});
    pane.addComponentListener(new java.awt.event.ComponentAdapter(){public void componentResized(java.awt.event.ComponentEvent e){((Runnable)pane.getClientProperty("wb.approved.divider")).run();}});
  }
  static void js(JPanel page){
    simplePage(page,34,10,14);JSplitPane split=(JSplitPane)n(page,"wb.js.findings");fixedSplit(split,JSplitPane.HORIZONTAL_SPLIT,14,p->Math.round((p.getWidth()-14)*.509804f));
    JPanel actions=panel(n(page,"wb.js.header.actions"));Component toolbar=region(actions,BorderLayout.CENTER);actions.setBorder(null);if(toolbar!=null)toolbar.setPreferredSize(new Dimension(0,34));
    JPanel source=panel(n(page,"wb.actions.js.source"));JComboBox<?> choice=(JComboBox<?>)n(page,"wb.js.source.choice");ApprovedUi.combo(choice,123);choice.setRenderer(new DefaultListCellRenderer(){{putClientProperty("html.disable",true);}public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){super.getListCellRendererComponent(list,value,index,selected,focus);putClientProperty("html.disable",true);String url=String.valueOf(value);try{var uri=java.net.URI.create(url);if(uri.getHost()!=null)setText(uri.getRawPath().replaceFirst("^/",""));}catch(IllegalArgumentException ignored){}setToolTipText(url);return this;}});
    Component lamp=null;JPanel context=panel(n(page,"wb.actions.js.context"));for(Component c:context.getComponents())if(c instanceof JToggleButton)lamp=c;
    if(lamp!=null){context.remove(lamp);source.add(lamp);if(lamp instanceof JComponent c)c.putClientProperty("wb.approved.analysis",true);}
    ApprovedUi.row(source,8);ApprovedUi.row(context,8);
    JPanel sourceReader=panel(n(page,"wb.workflow.js.source"));JPanel card=panel(n(page,"wb.pdf.js.call.source"));
    card.setBorder(ApprovedUi.CARD_BORDER);JPanel manual=panel(n(page,"wb.js.manual"));JPanel navigation=panel(n(page,"wb.workflow.js.navigation"));
    JPanel evidence=panel(n(page,"wb.workflow.js.evidence"));((BorderLayout)evidence.getLayout()).setVgap(2);AbstractButton navToggle=(AbstractButton)navigation.getComponent(0),evidenceToggle=(AbstractButton)evidence.getComponent(0);navToggle.setVisible(false);evidenceToggle.setVisible(false);
    JPanel analysisMenu=WorkflowUi.menu("wb.approved.js.evidence","분석 근거",evidenceToggle,navToggle);JLabel note=new JLabel("원문에서 추출한 후보입니다.");note.putClientProperty("wb.font.size",11);note.putClientProperty("wb.secondary",true);evidence.add(CompactWorkbenchUi.toolbar(note,analysisMenu),BorderLayout.NORTH);
    JsAnalysisPanel owner=(JsAnalysisPanel)page;JButton types=(JButton)n(page,"wb.workflow.js.types");ApprovedUi.row(owner.resultTabs.extras,8);owner.resultView.filter.setVisible(false);owner.resultView.count.setVisible(false);JMenuItem listFilter=new JMenuItem("목록 필터");listFilter.setName("wb.js.filter.menu");listFilter.addActionListener(e->{WorkbenchUi.style(owner.resultView.popup);owner.resultView.popup.show(types,0,types.getHeight());});types.getComponentPopupMenu().addSeparator();types.getComponentPopupMenu().add(listFilter);
    Component selected=n(page,"wb.workflow.js.selection");if(selected!=null)selected.setPreferredSize(new Dimension(0,34));
    JPanel list=panel(split.getLeftComponent());Component listHeader=region(list,BorderLayout.NORTH);if(listHeader!=null)listHeader.setPreferredSize(new Dimension(0,78));
    if(n(page,"wb.js.repeater.prepare") instanceof JButton b){b.putClientProperty("wb.no.auto.icon",true);b.setIcon(null);}owner.urlTable.putClientProperty("wb.table.flat",true);owner.urlTable.putClientProperty("wb.table.floating",true);owner.urlTable.putClientProperty("wb.approved.rowHeight",32);
    JPanel findingHeader=panel(region(owner.resultTabs,BorderLayout.NORTH));((BorderLayout)findingHeader.getLayout()).setVgap(10);region(findingHeader,BorderLayout.NORTH).setPreferredSize(new Dimension(0,34));n(page,"wb.js.findings.search").setPreferredSize(new Dimension(0,34));((BorderLayout)owner.resultTabs.getLayout()).setVgap(6);
    widths(owner.urlTable,new int[]{1,0,7,6},new String[]{"메서드","발견 경로","확인 상태","호출 위치"},104,274,163,156);
    listFooter(page,list,42);
    ((BorderLayout)sourceReader.getLayout()).setVgap(10);
  }
  static void environment(JPanel page){
    EnvironmentPanel controller=(EnvironmentPanel)page;JPanel toolbar=panel(region(page,BorderLayout.NORTH));Component workspace=region(page,BorderLayout.CENTER),footer=region(page,BorderLayout.SOUTH);
    Component context=n(page,"wb.actions.environment.context"),observations=n(page,"wb.environment.observations");JToggleButton reveal=(JToggleButton)n(page,"wb.environment.observations.toggle");page.setBorder(null);
    ApprovedUi.layout(page,p->{int extra=reveal.isSelected()?104:0,h=80+extra,w=p.getWidth()-32;at(toolbar,16,14,w,h);at(workspace,16,24+h,w,p.getHeight()-h-76);at(footer,16,p.getHeight()-30,w,22);});
    toolbar.removeAll();toolbar.add(context);toolbar.add(observations);ApprovedUi.layout(toolbar,p->{at(context,0,0,p.getWidth(),34);at(observations,0,44,p.getWidth(),36+(reveal.isSelected()?104:0));});
    JPanel observationHeader=panel(n(page,"wb.actions.environment.filters"));Container oldActions=null;for(Component c:observationHeader.getComponents())if(c!=reveal&&c instanceof Container p)oldActions=p;
    observationHeader.removeAll();observationHeader.setLayout(new BorderLayout());observationHeader.add(reveal);if(oldActions!=null){oldActions.setVisible(false);observationHeader.add(oldActions,BorderLayout.SOUTH);}
    if(n(page,"wb.flow.environment.settings.open") instanceof JButton open&&open.getComponentPopupMenu()!=null)open.getComponentPopupMenu().add(WorkflowUi.proxy((AbstractButton)n(page,"wb.environment.all")));
    Runnable observed=()->reveal.setText("관찰 환경 · "+controller.tech.getRowCount()+"개 단서");controller.tech.addTableModelListener(e->observed.run());observed.run();
    JPanel left=panel(region(box(context),BorderLayout.CENTER));JLabel targetLabel=null;for(Component c:left.getComponents())if(c instanceof JLabel l&&l.getText().equals("대상"))targetLabel=l;
    Component originLabel=targetLabel;JLabel scope=new JLabel();scope.setName("wb.approved.environment.scope");ApprovedUi.chip(scope);left.add(scope);
    Runnable scopeText=()->{String url=java.util.Objects.toString(controller.origins.getSelectedItem(),"");scope.setText(url.isBlank()?"SCOPE 미확인":WorkbenchScope.contains(controller.api,url+"/")?"SCOPE 안":"SCOPE 밖");};controller.origins.addActionListener(e->scopeText.run());controller.paths.addTableModelListener(e->scopeText.run());scopeText.run();
    for(Component c:left.getComponents())if(c instanceof JLabel l&&l!=scope&&l!=originLabel)l.setVisible(false);
    ApprovedUi.layout(left,p->{at(originLabel,0,0,29,34);at(controller.origins,29,2,180,31);at(controller.account,217,2,82,31);at(scope,309,6,Math.max(74,scope.getPreferredSize().width),22);});
    JSplitPane split=(JSplitPane)workspace;fixedSplit(split,JSplitPane.HORIZONTAL_SPLIT,12,p->Math.round((p.getWidth()-12)*.537037f));
    JPanel candidates=panel(split.getLeftComponent());candidates.putClientProperty("wb.card",true);candidates.putClientProperty("wb.surface",true);candidates.setBorder(ApprovedUi.CARD_BORDER);((BorderLayout)candidates.getLayout()).setVgap(0);region(candidates,BorderLayout.NORTH).setPreferredSize(new Dimension(0,43));
    JPanel list=panel(region(candidates,BorderLayout.CENTER));list.setBorder(null);((BorderLayout)list.getLayout()).setVgap(0);
    ApprovedUi.row(n(page,"wb.environment.more").getParent(),0);
    JPanel search=panel(n(page,"wb.actions.environment.candidates"));search.setBorder(BorderFactory.createEmptyBorder(9,11,10,11));search.setPreferredSize(new Dimension(0,50));
    widths(controller.pathTable,new int[]{0,1,3},new String[]{"경로","발견 근거","확인 상태"},354,200,170);
    JPanel inspector=panel(n(page,"wb.flow.environment.inspector"));inspector.putClientProperty("wb.card",true);inspector.putClientProperty("wb.surface",true);inspector.setBorder(ApprovedUi.CARD_BORDER);((BorderLayout)inspector.getLayout()).setVgap(0);
    JPanel execution=panel(n(page,"wb.placement.environment.execution"));execution.setPreferredSize(new Dimension(0,133));Component title=region(execution,BorderLayout.NORTH);if(title!=null)title.setPreferredSize(new Dimension(0,39));
    JPanel actions=panel(n(page,"wb.actions.environment.execution"));for(Component c:actions.getComponents())if(c instanceof AbstractButton b){b.putClientProperty("wb.font.size",11);b.putClientProperty("wb.compact.button",true);}JPanel metadata=new JPanel(new BorderLayout());JLabel status=new JLabel(),who=new JLabel();ApprovedUi.chip(status);who.putClientProperty("wb.font.size",11);who.putClientProperty("wb.secondary",true);metadata.add(status,BorderLayout.WEST);metadata.add(who,BorderLayout.EAST);execution.add(metadata);
    ApprovedUi.layout(execution,p->{at(title,0,0,p.getWidth(),39);at(actions,0,39,p.getWidth(),46);at(metadata,14,98,p.getWidth()-28,22);});
    ApprovedUi.layout(actions,p->{at(p.getComponent(0),14,14,120,31);int count=p.getComponentCount();if(count>1)at(p.getComponent(1),p.getWidth()-162,14,80,31);if(count>2)at(p.getComponent(2),p.getWidth()-72,14,60,31);});
    Runnable selection=()->{int row=controller.pathTable.getSelectedRow();if(row>=controller.pathTable.getRowCount())row=-1;String state=row<0?"미선택":String.valueOf(controller.pathTable.getModel().getValueAt(controller.pathTable.convertRowIndexToModel(row),3));status.setText(state);who.setText("GET · "+java.util.Objects.toString(controller.account.getSelectedItem(),"비로그인"));};controller.pathTable.getSelectionModel().addListSelectionListener(e->selection.run());controller.account.addActionListener(e->selection.run());controller.paths.addTableModelListener(e->selection.run());selection.run();
    JTabbedPane messages=(JTabbedPane)n(page,"wb.environment.messages");externalTabs(messages,inspector,BorderLayout.CENTER,34);
    java.util.List<AbstractButton> packetCommands=new java.util.ArrayList<>();for(int i=0;i<2;i++){Component area=i==0?controller.responseView:controller.requestView;Container packet=area.getParent();while(packet!=null&&region(packet,BorderLayout.NORTH)==null)packet=packet.getParent();if(packet!=null){Component packetHeader=region(packet,BorderLayout.NORTH);if(i==0)for(Component c:all(packetHeader))if(c instanceof AbstractButton b){b.setName(b.getText().equals("Raw")?"wb.environment.packet.raw":b.getText().equals("JSON")?"wb.environment.packet.pretty":"wb.environment.packet.evidence");packetCommands.add(b);}packetHeader.setVisible(false);}}
    JPanel messageHost=panel(messages.getParent()),strip=panel(region(messageHost,BorderLayout.NORTH));messageHost.remove(strip);JPanel messageHeader=CompactWorkbenchUi.toolbar(strip,WorkflowUi.menu("wb.approved.environment.packet","원문 도구",packetCommands.toArray(AbstractButton[]::new)));messageHeader.setPreferredSize(new Dimension(0,34));messageHost.add(messageHeader,BorderLayout.NORTH);
  }
  static void response(JPanel page){
    simplePage(page,34,10,14);JSplitPane split=(JSplitPane)n(page,"wb.response.finding.split");if(split==null)return;fixedSplit(split,JSplitPane.HORIZONTAL_SPLIT,14,p->Math.round((p.getWidth()-14)*.3812f));
    JPanel source=panel(split.getLeftComponent());Component scroll=region(source,BorderLayout.CENTER);if(scroll instanceof JScrollPane sp)sp.setBorder(null);
    Component search=region(source,BorderLayout.NORTH);if(search!=null)search.setPreferredSize(new Dimension(0,34));
    JTabbedPane pages=(JTabbedPane)n(page,"wb.response.finding.pages");if(pages!=null)ApprovedUi.tabs(pages);
    JPanel reader=panel(n(page,"wb.card.pdf.response.reader"));Component head=region(reader,BorderLayout.NORTH);if(head!=null)head.setPreferredSize(new Dimension(0,44));
    JPanel detail=panel(split.getRightComponent());Component summary=region(detail,BorderLayout.NORTH);if(summary!=null)summary.setPreferredSize(new Dimension(0,84));((BorderLayout)detail.getLayout()).setVgap(10);
    listFooter(page,box(split.getLeftComponent()),46);
  }
  static void exposure(JPanel page){
    ExposureResearchPanel controller=(ExposureResearchPanel)page;JTabbedPane tabs=controller.tabs;externalTabs(tabs,page,BorderLayout.CENTER,40);simplePage(page,34,10,14);
    JPanel context=panel(n(page,"wb.actions.exposure.context"));JPanel left=panel(region(context,BorderLayout.CENTER)),right=panel(region(context,BorderLayout.EAST));JLabel targetLabel=null;for(Component c:left.getComponents())if(c instanceof JLabel label)targetLabel=label;final JLabel targetCaption=targetLabel;
    left.add(controller.scopeLabel);right.remove(controller.scopeLabel);JLabel project=new JLabel("현재 프로젝트");project.putClientProperty("wb.font.size",11);project.putClientProperty("wb.secondary",true);right.add(project);ApprovedUi.chip(controller.scopeLabel);controller.subdomains.putClientProperty("wb.font.size",11);
    ApprovedUi.layout(left,p->{at(targetCaption,0,0,29,34);at(controller.target,29,2,180,31);at(controller.subdomains,221,2,105,31);at(controller.scopeLabel,334,6,Math.max(74,controller.scopeLabel.getPreferredSize().width),22);});ApprovedUi.row(right,0);
    JPanel search=(JPanel)tabs.getComponentAt(0);Component searchTop=region(search,BorderLayout.NORTH),searchBody=region(search,BorderLayout.CENTER),searchNote=region(search,BorderLayout.SOUTH);
    ApprovedUi.layout(search,p->{at(searchTop,0,0,p.getWidth(),34);at(searchBody,0,44,p.getWidth(),p.getHeight()-82);at(searchNote,0,p.getHeight()-28,p.getWidth(),22);});
    JPanel queryBar=panel(n(page,"wb.actions.exposure.search.query"));((BorderLayout)queryBar.getLayout()).setHgap(12);JPanel expression=panel(region(queryBar,BorderLayout.CENTER));((BorderLayout)expression.getLayout()).setHgap(8);JPanel category=panel(controller.category.getParent());for(Component c:category.getComponents())if(c!=controller.category)category.remove(c);ApprovedUi.combo(controller.category,85);category.setPreferredSize(new Dimension(85,34));ApprovedUi.row(category,0);
    JButton copyQuery=(JButton)n(page,"wb.exposure.search.query.copy");copyQuery.setPreferredSize(new Dimension(101,31));Container queryActions=copyQuery.getParent();for(Component c:queryActions.getComponents())if(c instanceof JButton b&&b!=copyQuery)b.setPreferredSize(new Dimension(130,31));ApprovedUi.row(queryActions,9);
    JSplitPane searchSplit=(JSplitPane)n(page,"wb.flow.exposure.search.masterdetail");fixedSplit(searchSplit,JSplitPane.HORIZONTAL_SPLIT,12,p->Math.round((p.getWidth()-12)*.62963f));
    JPanel imported=panel(searchSplit.getLeftComponent());imported.putClientProperty("wb.card",true);imported.putClientProperty("wb.surface",true);((BorderLayout)imported.getLayout()).setVgap(0);Component importedHeader=region(imported,BorderLayout.NORTH);importedHeader.setPreferredSize(new Dimension(0,43));
    widths(controller.exposureTable,new int[]{0,1,2,3},new String[]{"분류","URL","SCOPE","확인 상태"},130,494,110,126);
    JPanel cve=(JPanel)tabs.getComponentAt(1);JPanel cveTop=panel(region(cve,BorderLayout.NORTH));Component cveBody=region(cve,BorderLayout.CENTER);
    Component lookup=n(page,"wb.actions.exposure.cve.lookup"),filters=n(page,"wb.actions.exposure.cve.filters");Component mapping=null;for(Component part:cveTop.getComponents())if(part!=lookup&&part!=filters)mapping=part;final Component productEditor=mapping;
    JPanel productRow=panel(region(box(lookup),BorderLayout.CENTER));Component productCaption=productRow.getComponent(0);JToggleButton edit=(JToggleButton)n(page,"wb.exposure.product.edit");edit.setText("수정");edit.setIcon(new ApprovedTrafficIcon("chevron",WorkbenchUi.ink()));edit.setHorizontalTextPosition(SwingConstants.LEFT);controller.observed.putClientProperty("wb.approved.product",true);
    controller.observed.setRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){super.getListCellRendererComponent(list,value,index,selected,focus);putClientProperty("html.disable",true);setOpaque(index>=0);setFont(WorkbenchUi.font().deriveFont(Font.BOLD,WorkbenchUi.displaySize(13f)));if(value==null)setText("관측 제품 없음");return this;}});
    JLabel productEvidence=new JLabel();ApprovedUi.chip(productEvidence);productRow.add(productEvidence);Runnable productEvidenceText=()->{ExposureResearch.Product chosen=(ExposureResearch.Product)controller.observed.getSelectedItem();productEvidence.setVisible(chosen!=null);String evidence=chosen==null?"":chosen.evidence();productEvidence.setText(evidence.stripLeading().startsWith("Server:")?"응답 Server 헤더":evidence.stripLeading().startsWith("X-Powered-By:")?"X-Powered-By":"관측 근거");productEvidence.setToolTipText(evidence);productRow.revalidate();};controller.observed.addActionListener(e->productEvidenceText.run());productEvidenceText.run();
    ApprovedUi.layout(productRow,p->{String value=java.util.Objects.toString(controller.observed.getSelectedItem(),"관측 제품 없음");int width=Math.max(76,p.getFontMetrics(WorkbenchUi.font().deriveFont(Font.BOLD,13f)).stringWidth(value)+4),badge=productEvidence.isVisible()?productEvidence.getPreferredSize().width:0;at(productCaption,0,0,49,34);at(controller.observed,57,2,width,31);at(productEvidence,65+width,6,badge,22);at(edit,65+width+(badge==0?0:badge+10),2,53,31);});
    Component filterInputs=region(box(filters),BorderLayout.CENTER);java.util.function.IntSupplier extras=()->(productEditor!=null&&productEditor.isVisible()?productEditor.getPreferredSize().height+8:0)+(filterInputs!=null&&filterInputs.isVisible()?filterInputs.getPreferredSize().height+4:0);
    ApprovedUi.layout(cve,p->{int h=78+extras.getAsInt();at(cveTop,0,0,p.getWidth(),h);at(cveBody,0,h+10,p.getWidth(),p.getHeight()-h-10);});
    ApprovedUi.layout(cveTop,p->{int productHeight=productEditor!=null&&productEditor.isVisible()?productEditor.getPreferredSize().height+8:0;at(lookup,0,0,p.getWidth(),34);at(productEditor,0,42,p.getWidth(),productHeight==0?0:productHeight-8);at(filters,0,44+productHeight,p.getWidth(),34+(filterInputs!=null&&filterInputs.isVisible()?filterInputs.getPreferredSize().height+4:0));});
    JSplitPane cveSplit=(JSplitPane)n(page,"wb.flow.exposure.cve.masterdetail");fixedSplit(cveSplit,JSplitPane.HORIZONTAL_SPLIT,12,p->Math.round((p.getWidth()-12)*.5614f));
    JPanel candidates=panel(cveSplit.getLeftComponent());candidates.putClientProperty("wb.card",true);candidates.putClientProperty("wb.surface",true);((BorderLayout)candidates.getLayout()).setVgap(0);
    JPanel filterHeader=panel(region(box(filters),BorderLayout.NORTH));Component filterActions=region(filterHeader,BorderLayout.EAST);JPanel pills=new JPanel();ApprovedUi.row(pills,8);JLabel scoreFilter=new JLabel(),remoteFilter=new JLabel(),unknownFilter=new JLabel();for(JLabel label:new JLabel[]{scoreFilter,remoteFilter,unknownFilter}){ApprovedUi.chip(label);pills.add(label);}ApprovedUi.row(box(filterActions),0);pills.add(filterActions);JPanel retainedFilter=new JPanel();retainedFilter.add(controller.filterSummary);retainedFilter.setVisible(false);filterHeader.add(retainedFilter,BorderLayout.SOUTH);filterHeader.add(pills);filterHeader.add(controller.freshness,BorderLayout.EAST);controller.freshness.putClientProperty("wb.font.size",11);controller.freshness.putClientProperty("wb.secondary",true);
    Runnable filterChips=()->{scoreFilter.setText("CVSS "+controller.minimum.getValue()+" 이상");remoteFilter.setText("원격·인증 불필요");remoteFilter.setVisible(controller.remote.isSelected());unknownFilter.setText(controller.unknown.isSelected()?"미확인 포함":"미확인 제외");pills.revalidate();};controller.minimum.addChangeListener(e->filterChips.run());controller.remote.addItemListener(e->filterChips.run());controller.unknown.addItemListener(e->filterChips.run());filterChips.run();
    JLabel count=new JLabel();count.putClientProperty("wb.font.size",13);count.putClientProperty("wb.font.heading",true);count.setBorder(BorderFactory.createEmptyBorder(0,12,0,0));JPanel candidateHeader=ReferenceUi.header(count,null);candidateHeader.setPreferredSize(new Dimension(0,39));candidates.add(candidateHeader,BorderLayout.NORTH);
    Runnable counted=()->count.setText("검토할 CVE 후보 · "+controller.cveTable.getRowCount()+"개");controller.cveModel.addTableModelListener(e->SwingUtilities.invokeLater(counted));counted.run();
    widths(controller.cveTable,new int[]{0,2,5,3,1},new String[]{"CVE","CVSS","접근 조건","KEV","대상 판정"},208,102,157,102,198);
    JPopupMenu more=((JButton)n(page,"wb.exposure.cve.more")).getComponentPopupMenu();for(String id:new String[]{"wb.pdf.exposure.cve.conditions","wb.pdf.exposure.cve.references"}){JPanel disclosure=panel(n(page,id));if(disclosure!=null&&disclosure.getComponentCount()>0&&disclosure.getComponent(0) instanceof AbstractButton toggle){toggle.setVisible(false);more.add(WorkflowUi.proxy(toggle));}}
    for(String id:new String[]{"wb.pdf.exposure.cve.facts","wb.pdf.exposure.discovery.facts"}){
      JPanel facts=panel(n(page,id));for(Component value:facts.getComponents()){if(value instanceof JLabel label)label.putClientProperty("wb.font.size",12);if(value instanceof JTextArea area){area.putClientProperty("wb.approved.fact",true);area.setRows(0);}}
    }
    JPanel metrics=panel(n(page,"wb.pdf.exposure.cve.metrics"));JPanel selectedHeader=panel(metrics.getParent());JPanel title=ReferenceUi.header(controller.selectedCveTitle,null);controller.selectedCveTitle.setBorder(BorderFactory.createEmptyBorder(0,12,0,12));controller.selectedCveTitle.putClientProperty("wb.font.heading",true);controller.selectedCveTitle.putClientProperty("wb.font.size",13);
    selectedHeader.removeAll();selectedHeader.add(title);selectedHeader.add(metrics);selectedHeader.setPreferredSize(new Dimension(0,85));ApprovedUi.layout(selectedHeader,p->{at(title,0,0,p.getWidth(),39);at(metrics,12,51,p.getWidth()-24,22);});ApprovedUi.row(metrics,8);ApprovedUi.chip(controller.cveScore);ApprovedUi.chip(controller.cveVerdict);
    for(JSplitPane split:new JSplitPane[]{searchSplit,cveSplit})for(Component part:new Component[]{split.getLeftComponent(),split.getRightComponent()})if(part instanceof JPanel p)p.setBorder(ApprovedUi.CARD_BORDER);
    JTabbedPane detail=(JTabbedPane)n(page,"wb.flow.exposure.cve.detail.tabs");if(detail!=null)externalTabs(detail,detail.getParent(),BorderLayout.CENTER,40);
    listFooter(page,candidates,30);
  }
  static void activity(JPanel page){
    JTabbedPane tabs=((ActivityPanel)page).coveragePages;externalTabs(tabs,page,BorderLayout.CENTER,40);simplePage(page,34,10,14);
    Component endpoint=tabs.getComponentAt(0);JSplitPane split=null;for(Component c:all(endpoint))if(c instanceof JSplitPane p){split=p;break;}
    if(split!=null)fixedSplit(split,JSplitPane.HORIZONTAL_SPLIT,12,p->Math.round((p.getWidth()-12)*.65517f));
    if(split!=null)listFooter(page,box(split.getLeftComponent()),28);
    JPanel inspector=panel(n(page,"wb.flow.activity.inspector"));inspector.setBorder(ApprovedUi.CARD_BORDER);Component context=region(inspector,BorderLayout.NORTH);if(context!=null)context.setPreferredSize(new Dimension(0,258));
  }
  static void data(JPanel page){
    JTabbedPane tabs=(JTabbedPane)page.getComponent(0);page.setBorder(null);externalTabs(tabs,page,BorderLayout.CENTER,40);
    JPanel codec=(JPanel)tabs.getComponentAt(0);codec.setBorder(BorderFactory.createEmptyBorder(2,0,0,0));Component inner=region(codec,BorderLayout.CENTER);if(inner instanceof JPanel p)codec=p;
    Component controls=n(page,"wb.actions.data.codec.input"),messages=n(page,"wb.codec.messages");
    if(controls!=null&&messages!=null){Component controlBar=controls.getParent();controlBar.setPreferredSize(new Dimension(0,34));Component[] inputs=box(controls).getComponents();ApprovedUi.layout(box(controls),p->{if(inputs.length>=3){if(inputs[0] instanceof JComponent label)label.putClientProperty("wb.font.size",11);at(inputs[0],0,2,48,31);at(inputs[1],54,2,230,31);at(inputs[2],292,2,65,31);}});JPanel body=panel(messages.getParent());Component note=region(body,BorderLayout.SOUTH);body.removeAll();body.setLayout(new BorderLayout(0,12));body.add(controlBar,BorderLayout.NORTH);body.add(messages);if(note!=null){note.setPreferredSize(new Dimension(0,28));body.add(note,BorderLayout.SOUTH);}}
    for(String id:new String[]{"wb.codec.input","wb.codec.output"}){JPanel body=panel(n(page,id));Component caption=region(body,BorderLayout.SOUTH);caption.setPreferredSize(new Dimension(0,31));if(caption instanceof JLabel label){label.putClientProperty("wb.font.size",11);label.putClientProperty("wb.secondary",true);label.setBorder(BorderFactory.createEmptyBorder(0,14,0,14));}for(Component c:all(body))if(c instanceof JTextArea text)text.putClientProperty("wb.approved.codec",true);}
    JButton copy=(JButton)n(page,"wb.data.codec.copy");copy.setIcon(WorkspaceIcons.of("copy"));JPanel outputActions=panel(n(page,"wb.actions.data.codec.output"));Component reuse=region(outputActions,BorderLayout.EAST);if(reuse==null)for(Component c:outputActions.getComponents())if(c instanceof JPanel)reuse=c;final Component reuseMenu=reuse;outputActions.setPreferredSize(new Dimension(178,43));ApprovedUi.layout(outputActions,p->{at(copy,1,6,65,31);at(reuseMenu,74,6,91,31);});Container outputHead=outputActions.getParent();Component outputTitle=region(outputHead,BorderLayout.CENTER);if(outputTitle instanceof JLabel label){label.setBorder(BorderFactory.createEmptyBorder(0,12,0,0));label.putClientProperty("wb.font.size",13);label.putClientProperty("wb.font.heading",true);}
    page.setBorder(BorderFactory.createEmptyBorder(14,16,14,16));
    JSplitPane editors=(JSplitPane)n(page,"wb.codec.messages");fixedSplit(editors,JSplitPane.HORIZONTAL_SPLIT,12,p->(p.getWidth()-12)/2);
  }
  static void payload(JPanel page){
    JTabbedPane steps=(JTabbedPane)n(page,"wb.payload.steps");Component header=region(page,BorderLayout.NORTH),footer=region(page,BorderLayout.SOUTH);page.setBorder(null);
    ApprovedUi.layout(page,p->{boolean results=steps.getSelectedIndex()==1;int h=results?78:74,w=p.getWidth()-32;at(header,16,14,w,h);at(steps,16,24+h,w,p.getHeight()-24-h-(results?14:52));at(footer,16,p.getHeight()-30,w,results?0:22);});steps.addChangeListener(e->page.revalidate());
    Component execution=n(page,"wb.placement.payload.execution"),facts=n(page,"wb.reference.summary");Container top=execution.getParent();
    ApprovedUi.layout(top,p->{at(execution,0,0,p.getWidth(),34);at(facts,0,44,p.getWidth(),steps.getSelectedIndex()==1?34:30);});
    if(facts instanceof JPanel row){java.util.List<Component> values=new java.util.ArrayList<>();for(Component part:row.getComponents())if(part instanceof Container field){Component value=region(field,BorderLayout.CENTER);if(value!=null)values.add(value);}row.removeAll();for(Component value:values)row.add(value);row.setBorder(null);ApprovedUi.row(row,14);}
    JPanel preparation=(JPanel)steps.getComponentAt(0);((BorderLayout)preparation.getLayout()).setHgap(12);Component req=region(preparation,BorderLayout.CENTER);if(req instanceof JPanel p){p.setBorder(ApprovedUi.CARD_BORDER);p.putClientProperty("wb.card",true);p.putClientProperty("wb.surface",true);Component h=region(p,BorderLayout.NORTH);if(h!=null)h.setPreferredSize(new Dimension(0,43));}
    for(String id:new String[]{"wb.payload.step.prepare","wb.payload.step.results"})if(n(page,id) instanceof JToggleButton b)b.putClientProperty("wb.approved.step",true);
    Component arrow=null;Container stepBar=n(page,"wb.payload.step.prepare").getParent();for(Component c:stepBar.getComponents())if(c instanceof JLabel)arrow=c;if(arrow!=null)stepBar.remove(arrow);ApprovedUi.row(stepBar,3);
    JSplitPane results=(JSplitPane)n(page,"wb.payload.workspace");fixedSplit(results,JSplitPane.VERTICAL_SPLIT,10,p->Math.round(p.getHeight()*.263f));
    JSplitPane packets=(JSplitPane)n(page,"wb.payload.results.messages");fixedSplit(packets,JSplitPane.HORIZONTAL_SPLIT,12,p->(p.getWidth()-12)/2);
    Component selection=n(page,"wb.payload.results.selection"),reason=n(page,"wb.payload.results.reason");Container resultDetail=selection.getParent();ApprovedUi.layout(resultDetail,p->{int expanded=reason instanceof JPanel panel&&panel.getComponentCount()>1&&panel.getComponent(1).isVisible()?reason.getPreferredSize().height:36;at(selection,0,0,p.getWidth(),37);at(packets,0,48,p.getWidth(),p.getHeight()-48-expanded-10);at(reason,0,p.getHeight()-expanded,p.getWidth(),expanded);});
    if(selection instanceof JLabel label){label.putClientProperty("wb.approved.notice",true);label.putClientProperty("wb.font.size",11);label.putClientProperty("html.disable",true);}
    for(Component c:all(n(page,"wb.placement.payload.results")))if(c instanceof JToggleButton button&&button.getName()!=null&&button.getName().startsWith("wb.payload.results.filter.")){button.putClientProperty("wb.filter.segment",true);button.addItemListener(e->button.setForeground(button.isSelected()?WorkbenchUi.accent():kr.packetui.VisualTheme.muted()));}
    Component resultHeading=region(box(n(page,"wb.placement.payload.results")),BorderLayout.CENTER);if(resultHeading instanceof JLabel label){label.setBorder(BorderFactory.createEmptyBorder(0,12,0,0));label.putClientProperty("wb.font.heading",true);label.putClientProperty("wb.font.size",13);}
    Container resultList=((JSplitPane)n(page,"wb.payload.workspace")).getTopComponent() instanceof Container c?c:null;if(resultList instanceof JPanel p){p.setBorder(ApprovedUi.CARD_BORDER);p.putClientProperty("wb.card",true);p.putClientProperty("wb.surface",true);((BorderLayout)p.getLayout()).setVgap(0);n(page,"wb.placement.payload.results").setPreferredSize(new Dimension(0,42));}
    JLabel live=new JLabel();live.putClientProperty("html.disable",true);live.putClientProperty("wb.font.size",11);live.putClientProperty("wb.secondary",true);JLabel sourceStatus=null;JProgressBar progress=null;for(Component c:all(footer)){if(c instanceof JLabel label&&sourceStatus==null)sourceStatus=label;if(c instanceof JProgressBar bar)progress=bar;}final JLabel statusModel=sourceStatus;final JProgressBar progressModel=progress;Runnable liveState=()->{String text=(statusModel==null?"":statusModel.getText())+(progressModel==null?"":" · "+progressModel.getString());live.setText(text);live.setToolTipText(text);live.setVisible(steps.getSelectedIndex()==1);};if(statusModel!=null)statusModel.addPropertyChangeListener("text",e->liveState.run());if(progressModel!=null)progressModel.addChangeListener(e->liveState.run());steps.addChangeListener(e->liveState.run());liveState.run();
    JPanel factsRow=(JPanel)facts;Component[] values=factsRow.getComponents();factsRow.add(live);ApprovedUi.layout(factsRow,p->{int x=0;for(Component c:values){int width=c.getPreferredSize().width;at(c,x,0,width,p.getHeight());x+=width+14;}at(live,x+12,0,Math.max(0,p.getWidth()-x-12),p.getHeight());});
    packetMenu((PacketView)n(page,"wb.payload.result.request"),"요청 보기","wb.approved.payload.request.tools");packetMenu((PacketView)n(page,"wb.payload.result.response"),"응답 보기","wb.approved.payload.response.tools");
    JTable table=(JTable)n(page,"wb.payload.results.table");if(table!=null)widths(table,new int[]{0,1,2,3,4,5},new String[]{"번호","검사값","HTTP","크기","응답 시간","관측 결과"},128,501,171,128,200,250);
  }
  static void dashboard(JPanel page){
    JScrollPane scroll=(JScrollPane)n(page,"wb.scope.dashboard.scroll");page.setBorder(BorderFactory.createEmptyBorder(14,16,14,16));scroll.setBorder(null);
    JPanel content=panel(n(page,"wb.scope.dashboard.content"));Component context=n(page,"wb.actions.dashboard.context"),metadata=n(page,"wb.actions.dashboard.metadata"),cards=n(page,"wb.scope.dashboard.cards"),filters=n(page,"wb.actions.dashboard.filters"),results=n(page,"wb.scope.dashboard.results.area");
    Component distribution=n(page,"wb.flow.dashboard.distribution");if(distribution==null)for(Component c:content.getComponents())if(c instanceof JPanel p&&n(p,"wb.flow.dashboard.distribution.toggle")!=null)distribution=c;
    JPanel contextPanel=(JPanel)context;JLabel target=((ScopeDashboardPanel)page).target,run=((ScopeDashboardPanel)page).run,traffic=((ScopeDashboardPanel)page).traffic;
    JPanel current=new JPanel();ApprovedUi.row(current,10);current.add(run);current.add(target);current.add(traffic);contextPanel.add(current,BorderLayout.CENTER);((JPanel)metadata).setVisible(false);
    Component summary=n(page,"wb.scope.dashboard.filter.summary");if(summary!=null){summary.getParent().remove(summary);box(filters).add(summary);}
    final Component distributions=distribution;content.removeAll();for(Component part:new Component[]{context,metadata,cards,filters,results,distribution})if(part!=null)content.add(part);
    content.setLayout(new LayoutManager(){
      public void addLayoutComponent(String name,Component c){}public void removeLayoutComponent(Component c){}
      int extra(){return distributions==null?0:Math.max(0,distributions.getPreferredSize().height-36);}
      int cardHeight(){return ((GridLayout)((JPanel)cards).getLayout()).getColumns()==6?88:176;}
      public Dimension preferredLayoutSize(Container p){int base=p.getParent() instanceof JViewport v?v.getHeight():850;return new Dimension(600,Math.max(base,850+cardHeight()-88)+extra());}
      public Dimension minimumLayoutSize(Container p){return new Dimension(360,850);}
      public void layoutContainer(Container p){int w=p.getWidth(),h=Math.max(p.getHeight(),850),delta=cardHeight()-88,expanded=extra();at(context,0,0,w,34);at(metadata,0,34,w,0);at(cards,0,46,w,cardHeight());at(filters,0,146+delta,w,34);at(results,0,192+delta,w,Math.max(360,h-240-delta-expanded));at(distributions,0,h-36-expanded,w,36+expanded);}
    });
    JTable dashboardTable=(JTable)n(page,"wb.scope.dashboard.results");widths(dashboardTable,new int[]{0,1,2,3},new String[]{"내용","계정","중요도","상태"},313,177,140,257);
    JSplitPane split=(JSplitPane)n(page,"wb.scope.dashboard.results.split");fixedSplit(split,JSplitPane.HORIZONTAL_SPLIT,12,p->Math.round((p.getWidth()-12)*.649123f));
    ApprovedDashboard.apply((ScopeDashboardPanel)page);
  }
  static void mapper(JPanel page){
    JPanel project=panel(n(page,"wb.actions.mapper.project"));Component capture=n(page,"fm.capture");project.add(capture);ApprovedUi.row(project,9);project.setBorder(null);ApprovedUi.row(box(n(page,"wb.actions.mapper.capture")),0);
    for(Component c:all(page))if(c instanceof JPanel p&&p!=project&&p.getName()!=null&&p.getName().contains("workflow")&&p.getLayout() instanceof FlowLayout)ApprovedUi.row(p,0);
    for(Component c:all(page))if(c instanceof JComboBox<?> combo){combo.setPreferredSize(new Dimension(180,31));}
    JPanel packetActions=panel(n(page,"wb.actions.mapper.packets"));if(packetActions.getComponentCount()==2){Component first=packetActions.getComponent(0);packetActions.remove(first);packetActions.add(first);}ApprovedUi.row(packetActions,8);
    simplePage(page,34,10,14);JSplitPane all=(JSplitPane)n(page,"fm.navigation.split"),main=(JSplitPane)n(page,"fm.workspace");
    fixedSplit(all,JSplitPane.HORIZONTAL_SPLIT,12,p->((JToggleButton)n(page,"fm.tree.toggle")).isSelected()?218:48);all.putClientProperty("fm.initial.width",218);
    fixedSplit(main,JSplitPane.VERTICAL_SPLIT,10,p->328);
    JPanel listHeader=panel(n(page,"fm.workflow.list.header"));listHeader.setPreferredSize(new Dimension(0,88));Component filters=n(page,"fm.filters"),selection=n(page,"wb.placement.mapper.selection");
    ApprovedUi.layout(listHeader,p->{at(filters,0,0,p.getWidth(),34);at(selection,0,44,p.getWidth(),34);});
    Container filter=box(filters);JTextField search=null;Container filterOptions=null;for(Component c:filter.getComponents())if(c instanceof JTextField f)search=f;else if(c instanceof Container p)filterOptions=p;
    final JTextField field=search;final Container options=filterOptions;ApprovedUi.layout(options,p->{Component[] c=p.getComponents();if(c.length>=4){at(c[0],0,2,29,31);at(c[1],29,2,180,31);at(c[2],217,2,29,31);at(c[3],246,2,180,31);}});ApprovedUi.layout(filter,p->{at(field,0,0,Math.max(100,p.getWidth()-438),34);at(options,p.getWidth()-426,0,426,34);});
    JTable table=(JTable)n(page,"fm.history");if(table!=null)widths(table,new int[]{1,2,3,4,6},new String[]{"메서드","URL","상태","세션","매핑"},158,506,151,176,146);
    JSplitPane packets=(JSplitPane)n(page,"fm.messages");fixedSplit(packets,JSplitPane.HORIZONTAL_SPLIT,10,p->(p.getWidth()-10)/2);
    for(int i=0;i<2;i++){String kind=i==0?"request":"response";PacketInspector inspector=(PacketInspector)page.getClientProperty("wb.mapper."+kind+".inspector");if(inspector!=null){Component original=i==0?packets.getLeftComponent():packets.getRightComponent();JPanel card=WorkbenchUi.card(i==0?"요청":"응답",inspector);JPanel retained=new JPanel(new BorderLayout());retained.add(original);retained.setVisible(false);card.add(retained,BorderLayout.SOUTH);card.putClientProperty("fm.embedded.style",(Runnable)()->WorkbenchUi.style(card));if(i==0)packets.setLeftComponent(card);else packets.setRightComponent(card);}}
    Runnable owner=page.getClientProperty("wb.embedded.style") instanceof Runnable r?r:null;
    if(owner!=null)page.putClientProperty("wb.embedded.style",(Runnable)()->{owner.run();for(Component c:all(page))if(c instanceof JSplitPane s&&s.getClientProperty("wb.approved.divider") instanceof Runnable layout)layout.run();});
  }
  static void packetMenu(PacketView packet,String caption,String name){
    if(packet==null||!(packet.getParent() instanceof JPanel card))return;Component choices=region(packet,BorderLayout.NORTH);java.util.List<AbstractButton> buttons=new java.util.ArrayList<>();if(choices instanceof Container p)for(Component c:p.getComponents())if(c instanceof AbstractButton b)buttons.add(b);
    packet.remove(choices);JPanel menu=WorkflowUi.menu(name,caption,buttons.toArray(AbstractButton[]::new));JPanel head=(JPanel)region(card,BorderLayout.NORTH);JPanel inset=new JPanel(new BorderLayout());inset.putClientProperty("wb.surface",true);inset.setBorder(BorderFactory.createEmptyBorder(6,0,6,12));inset.setPreferredSize(new Dimension(104,43));inset.add(menu);head.add(inset,BorderLayout.EAST);head.setPreferredSize(new Dimension(0,43));
  }
  static JPanel externalTabs(JTabbedPane tabs,Container parent,String side,int height){
    ApprovedUi.tabs(tabs);JPanel strip=ApprovedUi.tabBar(tabs);strip.setPreferredSize(new Dimension(0,height));
    JPanel host=new JPanel(new BorderLayout());host.setName("wb.approved.tabs."+tabs.getName());int position=java.util.Arrays.asList(parent.getComponents()).indexOf(tabs);parent.remove(tabs);host.add(strip,BorderLayout.NORTH);host.add(tabs);if(parent.getLayout() instanceof BorderLayout)parent.add(host,side);else parent.add(host,Math.max(0,position));return host;
  }
  static java.util.List<Component> all(Component root){var result=new java.util.ArrayList<Component>();result.add(root);if(root instanceof Container p)for(Component c:p.getComponents())result.addAll(all(c));return result;}
}
