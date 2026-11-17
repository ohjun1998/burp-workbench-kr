package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Predicate;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.DefaultTableModel;

/** Two-tab research workspace. Construction, import and navigation never send target HTTP. */
final class ExposureResearchPanel extends JPanel implements AutoCloseable {
  final JTextField target=new JTextField(23), vendor=new JTextField(10), product=new JTextField(16), version=new JTextField(10);
  final JCheckBox subdomains=new JCheckBox("하위 도메인 포함",true),remote=new JCheckBox("원격 · 인증 불필요",true),kevOnly=new JCheckBox("KEV만"),unknown=new JCheckBox("미확인 포함",true);
  final JComboBox<String> category=new JComboBox<>(ExposureResearch.DORKS.keySet().toArray(String[]::new));
  final JComboBox<ExposureResearch.Product> observed=new JComboBox<>();
  final JSpinner minimum=new JSpinner(new SpinnerNumberModel(7.0,0.0,10.0,.1));
  final JTextField query=new JTextField();
  final JTabbedPane tabs=new JTabbedPane();
  final JLabel status=new JLabel("대상을 입력하거나 SCOPE 응답을 수집하세요. · 자동 조회 없음"), freshness=new JLabel("NVD · KEV · EPSS / 아직 조회하지 않음"),scopeLabel=new JLabel("SCOPE 미확인");
  final DefaultTableModel exposureModel=model("분류","결과 URL","범위","확인 상태"),cveModel=model("CVE","판정","CVSS","KEV","EPSS","원격·무인증");
  final JTable exposureTable=new JTable(exposureModel),cveTable=new JTable(cveModel);
  final JTextArea exposureDetail=reader(),conditions=reader(),evidence=reader(),observedDetail=reader(),affectedDetail=reader(),fixedDetail=reader();
  final JComboBox<String> references=new JComboBox<>();
  final JButton lookup=new JButton("CVE 후보 조회"),cancel=new JButton("취소");
  final JButton exposureRepeater=new JButton("Repeater 준비"),exposureCopy=new JButton("근거 복사"),
      referenceOpen=new JButton("출처 열기"),cveCopy=new JButton("CVE 근거 복사");
  final JMenuItem exposureRemove=new JMenuItem("선택 삭제");
  final JLabel selectedCveTitle=new JLabel("선택 CVE 상세"),filterSummary=new JLabel();
  final JLabel discoveryUrl=new JLabel("URL을 선택하세요"),discoveryCategory=new JLabel("—"),
      discoveryScope=new JLabel("—"),discoveryQuery=new JLabel("—"),cveScore=new JLabel("CVSS 미확인"),
      cveVerdict=new JLabel("조건 미확인"),cveAccess=new JLabel("미확인");
  final JTabbedPane cveDetailTabs=new JTabbedPane();
  final List<ExposureResearch.Exposure> exposures=new ArrayList<>();
  final List<ExposureResearch.Candidate> visible=new ArrayList<>();
  final Map<String,List<ExposureResearch.Product>> observations=new LinkedHashMap<>();
  final Map<String,ExposureResearch.Batch> cache=new LinkedHashMap<>();
  ExposureResearch.Batch batch;
  ExposureResearch.Product selectedProduct;
  private final MontoyaApi api;
  private final Predicate<String> scope;
  private final VulnerabilityClient client;
  private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"workbench-cve");t.setDaemon(true);return t;});
  private Future<?> job;
  private long generation;
  private volatile long projectEpoch;
  private boolean closed,refreshing;
  ExposureResearchPanel(MontoyaApi api,Predicate<String> scope){this(api,scope,new VulnerabilityClient());}
  ExposureResearchPanel(MontoyaApi api,Predicate<String> scope,VulnerabilityClient client){
    super(new BorderLayout(0,8));this.api=api;this.scope=scope;this.client=client;setName("wb.exposure");setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
    category.setName("wb.exposure.category");exposureTable.setName("wb.exposure.search.results");cveTable.setName("wb.exposure.cve.results");observed.setName("wb.exposure.observed");
    minimum.setName("wb.exposure.filter.minimum");remote.setName("wb.exposure.filter.remote");kevOnly.setName("wb.exposure.filter.kev");unknown.setName("wb.exposure.filter.unknown");
    target.setName("wb.exposure.target");target.setToolTipText("점검 대상 도메인 또는 HTTP(S) URL");observed.setPrototypeDisplayValue(new ExposureResearch.Product("","관측 제품 선택", "", "", "", ""));
    JPanel context=CompactWorkbenchUi.toolbar(flow(new JLabel("대상"),target,subdomains),flow(scopeLabel));
    context.setName("wb.actions.exposure.context");add(context,BorderLayout.NORTH);
    tabs.setName("wb.exposure.tabs");tabs.addTab("검색 노출",searchPage());tabs.addTab("CVE 후보",cvePage());add(tabs);add(status,BorderLayout.SOUTH);
    query.setEditable(false);cancel.setEnabled(false);WorkbenchUi.primary(lookup);
    conditions.setName("wb.exposure.conditions");evidence.setName("wb.exposure.evidence");exposureDetail.setName("wb.exposure.discovery");
    watch(target,()->{invalidateQuery();refreshProducts();refreshQuery();refreshExposures();});
    for(JTextField f:List.of(vendor,product,version))watch(f,()->{if(!refreshing)invalidateQuery();});
    subdomains.addActionListener(e->{refreshQuery();refreshExposures();});category.addActionListener(e->refreshQuery());
    observed.addActionListener(e->{if(!refreshing&&observed.getSelectedItem() instanceof ExposureResearch.Product p){invalidateQuery();fillProduct(p);}});
    lookup.addActionListener(e->start());cancel.addActionListener(e->{invalidateQuery();status.setText("조회 취소 · 세션 캐시는 유지됩니다.");});
    for(JCheckBox b:List.of(remote,kevOnly,unknown))b.addActionListener(e->refreshCves());minimum.addChangeListener(e->refreshCves());
    exposureTable.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting())showExposure();});
    cveTable.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting())showCve();});
    references.addActionListener(e->updateCveActions());
    CompactWorkbenchUi.columns(exposureTable,120,520,95,110);CompactWorkbenchUi.columns(cveTable,170,180,75,95,95,125);
    for(JTable table:List.of(exposureTable,cveTable)){table.setAutoCreateRowSorter(true);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setFillsViewportHeight(true);}
    updateExposureActions();updateCveActions();
  }
  static DefaultTableModel model(String... headers){return new DefaultTableModel(headers,0){public boolean isCellEditable(int r,int c){return false;}};}
  static JTextArea reader(){JTextArea a=new JTextArea();a.setEditable(false);a.setLineWrap(true);a.setWrapStyleWord(true);a.setMargin(new Insets(9,10,9,10));return a;}
  static JPanel flow(Component... cs){JPanel p=new JPanel(new WorkbenchUi.WrapLayout(FlowLayout.LEFT,7,3));for(Component c:cs)p.add(c);return p;}
  /** Keep filter controls intact before allocating the remaining width to retrieval metadata. */
  static JPanel filterRow(Component... cs){
    JPanel p=new JPanel(new WorkbenchUi.WrapLayout(FlowLayout.LEFT,7,3)){
      @Override public Dimension getMinimumSize(){
        FlowLayout layout=(FlowLayout)getLayout();Insets insets=getInsets();
        int width=insets.left+insets.right+2*layout.getHgap(),height=0,count=0;
        for(Component child:getComponents())if(child.isVisible()){
          Dimension preferred=child.getPreferredSize();
          if(count++>0)width+=layout.getHgap();
          width+=preferred.width;height=Math.max(height,preferred.height);
        }
        return new Dimension(width,height+insets.top+insets.bottom+2*layout.getVgap());
      }
    };
    for(Component c:cs)p.add(c);return p;
  }
  static JPanel card(String title,Component body){return WorkbenchUi.card(title,body);}
  /** The selected CVE keeps its natural reading order and scrolls as one column. */
  static final class DetailColumn extends JPanel implements Scrollable {
    DetailColumn(){setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));}
    public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
    public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction){return 24;}
    public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction){return Math.max(24,visible.height-24);}
    public boolean getScrollableTracksViewportWidth(){return true;}
    public boolean getScrollableTracksViewportHeight(){return false;}
  }
  static final class DetailViewport extends JViewport {
    @Override public void setViewPosition(Point position){
      // The width-tracking column has no horizontal scrolling range. Caret or
      // descendant visibility requests must not leave it at a negative x offset
      // while nested readers and the outer split are being laid out.
      super.setViewPosition(new Point(0,Math.max(0,position.y)));
    }
  }
  static JPanel facts(String name,String[] captions,JComponent... values){
    JPanel panel=new JPanel(new GridBagLayout());panel.setName(name);panel.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
    for(int row=0;row<captions.length;row++){
      JLabel label=new JLabel(captions[row]);label.putClientProperty("wb.secondary",true);
      GridBagConstraints key=new GridBagConstraints();key.gridx=0;key.gridy=row;key.anchor=GridBagConstraints.NORTHWEST;key.insets=new Insets(5,0,5,14);panel.add(label,key);
      GridBagConstraints value=new GridBagConstraints();value.gridx=1;value.gridy=row;value.weightx=1;value.fill=GridBagConstraints.HORIZONTAL;value.anchor=GridBagConstraints.NORTHWEST;value.insets=new Insets(5,0,5,0);
      values[row].setMinimumSize(new Dimension(0,22));values[row].putClientProperty("html.disable",true);panel.add(values[row],value);
    }return panel;
  }
  static JSplitPane split(int orientation,Component first,Component second,double weight){JSplitPane s=WorkbenchUi.split(orientation,first,second,weight);first.setMinimumSize(new Dimension(100,90));second.setMinimumSize(new Dimension(100,90));return s;}
  JButton button(String title,Runnable action){JButton b=new JButton(title);b.addActionListener(e->guard(action));return b;}
  JButton namedButton(String name,String title,Runnable action){JButton b=button(title,action);b.setName(name);return b;}
  JPanel actions(String name,Component context,Component actions){JPanel bar=CompactWorkbenchUi.toolbar(context,actions);bar.setName(name);return bar;}
  void guard(Runnable action){try{action.run();}catch(Exception e){status.setText(Objects.toString(e.getMessage(),"작업 실패"));}}
  JPanel searchPage(){
    JPanel p=new JPanel(new BorderLayout(0,8));
    JPanel expression=new JPanel(new BorderLayout(7,0));
    expression.add(flow(new JLabel("검색식"),category),BorderLayout.WEST);expression.add(query);
    query.setMinimumSize(new Dimension(120,30));query.setName("wb.exposure.query");
    JButton google=namedButton("wb.exposure.search.google","Google에서 열기",()->{ExposureResearch.host(target.getText());browse("https://www.google.com/search?q="+URLEncoder.encode(query.getText(),StandardCharsets.UTF_8));});
    WorkbenchUi.primary(google);
    JButton copyQuery=namedButton("wb.exposure.search.query.copy","검색식 복사",()->copy(query.getText()));
    p.add(actions("wb.actions.exposure.search.query",expression,flow(copyQuery,google)),BorderLayout.NORTH);

    JButton more=new JButton("목록 관리 ▾");more.setName("wb.exposure.search.more");more.setToolTipText("선택 URL 관리");more.getAccessibleContext().setAccessibleName("선택 URL 더보기");
    JPopupMenu menu=new JPopupMenu();menu.add(exposureRemove);more.setComponentPopupMenu(menu);exposureRemove.setName("wb.exposure.search.remove");
    exposureRemove.addActionListener(e->guard(this::removeExposure));more.addActionListener(e->menu.show(more,0,more.getHeight()));
    JPanel list=new JPanel(new BorderLayout(0,4));
    list.add(actions("wb.actions.exposure.search.list",new JLabel("가져온 URL · 실제 노출 미확인"),flow(namedButton("wb.exposure.search.import","URL 가져오기",this::importDialog),more)),BorderLayout.NORTH);
    list.add(new JScrollPane(exposureTable));

    exposureRepeater.setName("wb.exposure.search.repeater");exposureCopy.setName("wb.exposure.search.copy");
    exposureRepeater.addActionListener(e->guard(this::repeater));exposureCopy.addActionListener(e->guard(()->copy(exposureDetail.getText())));
    JPanel discovery=new JPanel();discovery.setLayout(new BoxLayout(discovery,BoxLayout.Y_AXIS));
    discoveryUrl.setName("wb.pdf.exposure.discovery.url");discoveryUrl.putClientProperty("html.disable",true);
    discoveryUrl.setBorder(BorderFactory.createEmptyBorder(10,12,8,12));discovery.add(discoveryUrl);
    JLabel unverified=WorkbenchUi.hint("확인 상태 · 미확인 / 실제 노출 미확인");unverified.setVisible(true);unverified.setBorder(BorderFactory.createEmptyBorder(0,12,8,12));discovery.add(unverified);
    JPanel discoveryFacts=facts("wb.pdf.exposure.discovery.facts",new String[]{"분류","SCOPE","발견 경로","연결 검색식"},discoveryCategory,discoveryScope,new JLabel("사용자 URL 가져오기"),discoveryQuery);discovery.add(discoveryFacts);
    JLabel caution=WorkbenchUi.hint("URL 가져오기는 실제 노출 확인이 아닙니다. Repeater에서 응답을 직접 확인하세요.");
    caution.setVisible(true);
    caution.setBorder(BorderFactory.createEmptyBorder(8,12,8,12));discovery.add(caution);
    JPanel selectedUrl=flow(exposureRepeater,exposureCopy);selectedUrl.setBorder(BorderFactory.createEmptyBorder(0,6,0,6));
    selectedUrl.setName("wb.actions.exposure.search.detail");discovery.add(selectedUrl);
    JPanel original=CompactWorkbenchUi.disclosure("wb.pdf.exposure.discovery.original","발견 정보 원문",new JScrollPane(exposureDetail),false);
    original.getComponent(1).setPreferredSize(new Dimension(300,140));discovery.add(original);
    JPanel discoveryBody=new JPanel(new BorderLayout());discoveryBody.add(discovery,BorderLayout.NORTH);
    JPanel detail=card("선택 URL 근거",discoveryBody);
    JSplitPane body=split(JSplitPane.HORIZONTAL_SPLIT,list,detail,.61);
    body.setName("wb.flow.exposure.search.masterdetail");body.putClientProperty("wb.workflow.fixedLayout",true);
    p.add(body);return p;
  }
  JPanel cvePage(){
    JPanel p=new JPanel(new BorderLayout(0,8));JPanel controls=new JPanel();controls.setLayout(new BoxLayout(controls,BoxLayout.Y_AXIS));
    JPanel mapping=flow(new JLabel("CPE 공급사"),vendor,new JLabel("제품"),product,new JLabel("관측 버전"),version);mapping.setVisible(false);
    JToggleButton edit=new JToggleButton("제품 수정");edit.setName("wb.exposure.product.edit");edit.setToolTipText("자동 관측 제품을 필요할 때만 수정합니다.");edit.addActionListener(e->{mapping.setVisible(edit.isSelected());p.revalidate();});
    lookup.setName("wb.exposure.lookup");cancel.setName("wb.exposure.cancel");
    JButton more=new JButton("더보기 ▾");more.setName("wb.exposure.cve.more");more.setToolTipText("CVE 조회 더보기");
    JPopupMenu menu=new JPopupMenu();more.setComponentPopupMenu(menu);JMenuItem cached=new JMenuItem("세션 캐시 보기");cached.setName("wb.exposure.cve.cache");cached.addActionListener(e->guard(this::restoreCache));menu.add(cached);more.addActionListener(e->menu.show(more,0,more.getHeight()));
    controls.add(actions("wb.actions.exposure.cve.lookup",flow(new JLabel("관측 제품"),observed,edit),flow(lookup,cancel,more)));
    controls.add(mapping);
    JPanel filterInputs=filterRow(new JLabel("CVSS ≥"),minimum,remote,kevOnly,unknown);
    JPanel filterDisclosure=CompactWorkbenchUi.disclosure("wb.flow.exposure.cve.filters","필터",filterInputs,false);
    JToggleButton filterToggle=(JToggleButton)filterDisclosure.getComponent(0);
    filterSummary.setName("wb.exposure.filter.summary");filterSummary.setMinimumSize(new Dimension(0,24));
    filterSummary.putClientProperty("wb.secondary",true);
    filterDisclosure.remove(filterToggle);
    filterDisclosure.add(CompactWorkbenchUi.toolbar(filterSummary,flow(filterToggle)),BorderLayout.NORTH);
    filterDisclosure.setName("wb.actions.exposure.cve.filters");controls.add(filterDisclosure);
    p.add(controls,BorderLayout.NORTH);refreshFilterSummary();
    freshness.setName("wb.exposure.cve.freshness");freshness.setMinimumSize(new Dimension(0,24));
    JPanel cveResults=new JPanel(new BorderLayout(0,4));cveResults.add(new JScrollPane(cveTable));cveResults.add(freshness,BorderLayout.SOUTH);
    cveResults.setName("wb.flow.exposure.cve.results");

    affectedDetail.setRows(2);fixedDetail.setRows(2);
    affectedDetail.putClientProperty("wb.prose",true);fixedDetail.putClientProperty("wb.prose",true);
    JPanel applicability=new JPanel(new BorderLayout(0,8));
    JPanel appliedFacts=facts("wb.pdf.exposure.cve.facts",new String[]{"영향 범위","원격·인증","모듈·운영체제","배포판·백포트","수정 버전"},affectedDetail,cveAccess,new JLabel("추가 확인 필요"),new JLabel("공급사 공지와 패치 확인 필요"),fixedDetail);
    JPanel conditionSummary=new JPanel(new BorderLayout(0,8));conditionSummary.add(appliedFacts);
    JLabel applicabilityNote=WorkbenchUi.hint("조건 일치는 실제 취약점 확인이 아닙니다. 모듈·설정·패치 조건을 함께 확인하세요.");applicabilityNote.setVisible(true);applicabilityNote.setBorder(BorderFactory.createEmptyBorder(6,12,8,12));conditionSummary.add(applicabilityNote,BorderLayout.SOUTH);
    applicability.add(conditionSummary,BorderLayout.NORTH);
    JPanel rawConditions=CompactWorkbenchUi.disclosure("wb.pdf.exposure.cve.conditions","적용 조건 설명 원문",new JScrollPane(conditions),false);
    rawConditions.getComponent(1).setPreferredSize(new Dimension(360,150));applicability.add(rawConditions,BorderLayout.SOUTH);
    cveDetailTabs.setName("wb.flow.exposure.cve.detail.tabs");
    cveDetailTabs.addTab("대상 적용 조건",applicability);cveDetailTabs.addTab("관측 근거",new JScrollPane(evidence));
    JPanel detail=new JPanel(new BorderLayout(0,7));
    detail.add(cveDetailTabs);
    references.setPrototypeDisplayValue("https://공급사.example/security/advisory");references.setMinimumSize(new Dimension(100,30));
    references.setName("wb.exposure.cve.references");selectedCveTitle.setName("wb.exposure.cve.selection");
    referenceOpen.setName("wb.exposure.cve.source");cveCopy.setName("wb.exposure.cve.copy");
    referenceOpen.addActionListener(e->guard(()->{if(references.getSelectedItem()!=null)browse(references.getSelectedItem().toString());}));cveCopy.addActionListener(e->guard(()->copy(cveNote())));
    JPanel detailHeader=new JPanel(new BorderLayout(0,5));
    detailHeader.add(ReferenceUi.header(selectedCveTitle,null),BorderLayout.NORTH);
    JPanel metrics=flow(cveScore,cveVerdict);metrics.setName("wb.pdf.exposure.cve.metrics");detailHeader.add(metrics);
    detail.add(detailHeader,BorderLayout.NORTH);
    JPanel sourceActions=flow(referenceOpen,cveCopy);sourceActions.setName("wb.actions.exposure.cve.detail");sourceActions.setBorder(BorderFactory.createEmptyBorder(0,6,0,6));
    JPanel referenceSources=CompactWorkbenchUi.disclosure("wb.pdf.exposure.cve.references","출처 선택",references,false);
    JPanel detailFooter=new JPanel(new BorderLayout(0,6));detailFooter.add(sourceActions,BorderLayout.NORTH);detailFooter.add(referenceSources);
    detail.add(detailFooter,BorderLayout.SOUTH);detail.setBorder(BorderFactory.createLineBorder(WorkbenchUi.line()));
    // The observed product stays in the top selector. Its retained text model is
    // still the source for copied evidence, without a second product card.
    JPanel retained=new JPanel();retained.setVisible(false);retained.add(observedDetail);detailFooter.add(retained,BorderLayout.SOUTH);
    // Keep source actions directly after the selected facts rather than pinning
    // them to the bottom of an otherwise empty tall card.
    detail.removeAll();JPanel selectedContent=new DetailColumn();selectedContent.setName("wb.pdf.exposure.cve.column");
    for(JComponent section:List.of(detailHeader,cveDetailTabs,detailFooter)){section.setAlignmentX(Component.LEFT_ALIGNMENT);selectedContent.add(section);}
    JScrollPane detailScroll=new JScrollPane();detailScroll.setViewport(new DetailViewport());detailScroll.setViewportView(selectedContent);detailScroll.setName("wb.pdf.exposure.cve.scroll");detailScroll.setBorder(null);detailScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);detailScroll.getVerticalScrollBar().setUnitIncrement(24);
    detail.add(detailScroll);detail.putClientProperty("wb.card",Boolean.TRUE);detail.putClientProperty("wb.surface",Boolean.TRUE);
    JSplitPane body=split(JSplitPane.HORIZONTAL_SPLIT,cveResults,detail,.54);
    body.setName("wb.flow.exposure.cve.masterdetail");body.putClientProperty("wb.workflow.fixedLayout",true);
    p.add(body);return p;
  }
  JPanel compactFact(String title,JTextArea value){
    JPanel fact=new JPanel(new BorderLayout(0,3));JLabel label=new JLabel(title);label.putClientProperty("wb.secondary",true);
    fact.add(label,BorderLayout.NORTH);JScrollPane scroll=new JScrollPane(value);scroll.setBorder(null);fact.add(scroll);return fact;
  }
  void refreshFilterSummary(){
    String text="CVSS ≥ "+minimum.getValue()+(remote.isSelected()?" · 원격·인증 불필요":"")+(kevOnly.isSelected()?" · KEV만":"")+(unknown.isSelected()?" · 미확인 포함":" · 확인된 조건만");
    filterSummary.setText(text);filterSummary.setToolTipText(text);
  }
  void updateExposureActions(){boolean selected=!closed&&selectedExposure()!=null;exposureRepeater.setEnabled(selected);exposureCopy.setEnabled(selected);exposureRemove.setEnabled(selected);}
  void updateCveActions(){boolean selected=!closed&&selectedCve()!=null&&selectedProduct!=null;cveCopy.setEnabled(selected);referenceOpen.setEnabled(selected&&references.getSelectedItem()!=null);}
  static void watch(JTextField f,Runnable r){f.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){r.run();}public void removeUpdate(DocumentEvent e){r.run();}public void changedUpdate(DocumentEvent e){r.run();}});}
  void refreshQuery(){try{query.setText(ExposureResearch.query(target.getText(),category.getSelectedItem().toString(),subdomains.isSelected()));scopeLabel.setText(scope.test("https://"+ExposureResearch.host(target.getText())+"/")?"SCOPE 포함":"SCOPE 확인 필요");}catch(RuntimeException e){query.setText("");scopeLabel.setText("SCOPE 미확인");}}
  void invalidateQuery(){generation++;if(job!=null)job.cancel(true);job=null;lookup.setEnabled(true);cancel.setEnabled(false);batch=null;selectedProduct=null;refreshCves();freshness.setText("조건 변경 · 조회하거나 세션 캐시를 선택하세요.");}
  void fillProduct(ExposureResearch.Product p){refreshing=true;try{vendor.setText(p.vendor());product.setText(p.name());version.setText(p.version());}finally{refreshing=false;}}
  void refreshProducts(){refreshing=true;try{observed.removeAllItems();String h;try{h=ExposureResearch.host(target.getText());}catch(RuntimeException e){fillProduct(new ExposureResearch.Product("","","","","",""));return;}for(var p:observations.getOrDefault(h,List.of()))observed.addItem(p);if(observed.getItemCount()>0)fillProduct(observed.getItemAt(0));else fillProduct(new ExposureResearch.Product("","","","","",""));}finally{refreshing=false;}}
  void offer(HttpRequest request,HttpResponse response,Object originalId){if(request==null||response==null)return;long epoch=projectEpoch;String url=request.url();String headers=EnvironmentPanel.responseHeaders(response);List<ExposureResearch.Product> found=ExposureResearch.observe(url,headers,Objects.toString(originalId,""));if(found.isEmpty())return;SwingUtilities.invokeLater(()->{if(closed||epoch!=projectEpoch)return;String h=ExposureResearch.host(url);List<ExposureResearch.Product> list=new ArrayList<>(observations.getOrDefault(h,List.of()));boolean changed=false;for(var p:found)if(list.stream().noneMatch(o->o.vendor().equals(p.vendor())&&o.name().equals(p.name())&&o.version().equals(p.version()))&&list.size()<24){list.add(p);changed=true;}if(!changed)return;if(observations.size()>=100&&!observations.containsKey(h))observations.remove(observations.keySet().iterator().next());observations.put(h,List.copyOf(list));if(target.getText().isBlank())target.setText(h);else if(ExposureResearch.within(url,target.getText(),false)){if(vendor.getText().isBlank())refreshProducts();else{refreshing=true;try{for(var p:found){boolean exists=false;for(int i=0;i<observed.getItemCount();i++){var o=observed.getItemAt(i);if(o.vendor().equals(p.vendor())&&o.name().equals(p.name())&&o.version().equals(p.version()))exists=true;}if(!exists)observed.addItem(p);}}finally{refreshing=false;}}}});}
  void importDialog(){JTextArea input=new JTextArea(10,60);if(JOptionPane.showConfirmDialog(this,new JScrollPane(input),"결과 URL · 한 줄에 하나, 최대 500개",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)importUrls(input.getText());}
  void importUrls(String text){if(text.length()>500000)throw new IllegalArgumentException("가져오기 크기 제한");ExposureResearch.host(target.getText());int added=0,rejected=0;for(String line:text.split("\\R")){if(line.isBlank())continue;try{String u=ExposureResearch.url(line);if(!ExposureResearch.within(u,target.getText(),subdomains.isSelected())){rejected++;continue;}if(exposures.stream().anyMatch(x->x.url().equals(u)))continue;if(exposures.size()>=500)throw new IllegalArgumentException("URL 최대 500개");exposures.add(new ExposureResearch.Exposure(u,category.getSelectedItem().toString(),query.getText(),Instant.now()));added++;}catch(RuntimeException e){rejected++;}}refreshExposures();status.setText("URL "+added+"개 가져옴 · 제외 "+rejected+"개 · 실제 노출 미확인");}
  final List<ExposureResearch.Exposure> displayedExposures=new ArrayList<>();
  void refreshExposures(){displayedExposures.clear();exposureModel.setRowCount(0);for(var x:exposures)if(ExposureResearch.within(x.url(),target.getText(),subdomains.isSelected())){displayedExposures.add(x);exposureModel.addRow(new Object[]{x.category(),x.url(),scope.test(x.url())?"SCOPE 포함":"범위 밖","미확인"});}tabs.setTitleAt(0,"검색 노출  "+displayedExposures.size());exposureDetail.setText("");updateDiscoveryFacts(null);updateExposureActions();}
  ExposureResearch.Exposure selectedExposure(){int row=exposureTable.getSelectedRow();return row<0||row>=exposureTable.getRowCount()||exposureTable.convertRowIndexToModel(row)>=displayedExposures.size()?null:displayedExposures.get(exposureTable.convertRowIndexToModel(row));}
  void showExposure(){var x=selectedExposure();exposureDetail.setText(x==null?"URL을 선택하면 검색식과 발견 근거가 표시됩니다.":x.url()+"\n\n분류  "+x.category()+"\n발견 경로  사용자 URL 가져오기 · "+x.discovered()+"\n검색식  "+x.query()+"\n\n현재 SCOPE  "+(scope.test(x.url())?"포함":"범위 밖")+"\n확인 상태  미확인 · URL 가져오기는 실제 노출 확인이 아닙니다.");exposureDetail.setCaretPosition(0);updateDiscoveryFacts(x);updateExposureActions();}
  void updateDiscoveryFacts(ExposureResearch.Exposure value){
    setFact(discoveryUrl,value==null?"URL을 선택하세요":value.url());setFact(discoveryCategory,value==null?"—":value.category());
    setFact(discoveryScope,value==null?"—":scope.test(value.url())?"현재 범위에 포함":"범위 밖");
    setFact(discoveryQuery,value==null?"—":value.category()+" 검색식");if(value!=null)discoveryQuery.setToolTipText(value.query());
  }
  static void setFact(JLabel label,String value){
    // A provider/source string is data, including when it starts with Swing's
    // HTML marker. Disable interpretation before publishing the first value.
    label.putClientProperty("html.disable",Boolean.TRUE);
    label.setText(value);
    label.setToolTipText(value!=null&&javax.swing.plaf.basic.BasicHTML.isHTMLString(value)?"원문: "+value:value);
  }
  void removeExposure(){var x=selectedExposure();if(x!=null){exposures.remove(x);refreshExposures();}}
  void repeater(){var x=selectedExposure();if(x==null)throw new IllegalArgumentException("결과 URL을 선택하세요.");if(!scope.test(x.url())||!ExposureResearch.within(x.url(),target.getText(),subdomains.isSelected()))throw new IllegalArgumentException("현재 SCOPE에 포함된 URL만 준비할 수 있습니다.");if(api!=null)api.repeater().sendToRepeater(HttpRequest.httpRequestFromUrl(x.url()),"검색 노출 확인");status.setText("Repeater 준비 완료 · 대상 요청은 전송하지 않았습니다.");}
  ExposureResearch.Product inputProduct(){String h=ExposureResearch.host(target.getText());String v=vendor.getText().strip().toLowerCase(Locale.ROOT),n=product.getText().strip().toLowerCase(Locale.ROOT),ver=version.getText().strip();var o=observed.getSelectedItem() instanceof ExposureResearch.Product p?p:null;boolean same=o!=null&&o.vendor().equals(v)&&o.name().equals(n)&&o.version().equals(ver);return new ExposureResearch.Product(v,n,ver,same?o.evidence():"사용자 입력 · 관측값 검증 필요"+(o==null?"":"\n관측 원본: "+o.evidence()),o!=null?o.source():"https://"+h+"/",o!=null?o.originalId():"");}
  String cacheKey(ExposureResearch.Product p){return ExposureResearch.host(target.getText())+"|"+p.vendor()+"|"+p.name();}
  void start(){guard(()->{ExposureResearch.Product p=inputProduct();if(!p.vendor().matches("[a-z0-9_.-]{1,100}")||!p.name().matches("[a-z0-9_.-]{1,100}"))throw new IllegalArgumentException("CPE 공급사·제품을 입력하세요.");invalidateQuery();long token=generation;String key=cacheKey(p);lookup.setEnabled(false);cancel.setEnabled(true);status.setText("NVD 조회 중 · 제품 식별자만 제공자에 전송 · 대상 URL/인증 정보 제외");job=worker.submit(()->{try{var result=client.lookup(p,()->Thread.currentThread().isInterrupted());SwingUtilities.invokeLater(()->{if(closed||token!=generation)return;if(cache.size()>=30&&!cache.containsKey(key))cache.remove(cache.keySet().iterator().next());cache.put(key,result);display(result,p,false);finish();});}catch(Exception error){SwingUtilities.invokeLater(()->{if(closed||token!=generation)return;finish();var old=cache.get(key);if(old!=null)display(old,p,true);status.setText("CVE 조회 실패 · "+error.getClass().getSimpleName()+" · "+(old==null?"연결 상태를 확인하세요.":"이전 캐시를 표시합니다."));});}});});}
  void finish(){job=null;lookup.setEnabled(true);cancel.setEnabled(false);}
  void restoreCache(){var p=inputProduct();var saved=cache.get(cacheKey(p));if(saved==null)throw new IllegalArgumentException("이 대상·제품의 세션 캐시가 없습니다.");invalidateQuery();display(saved,p,true);}
  void display(ExposureResearch.Batch result,ExposureResearch.Product p,boolean cached){batch=result;selectedProduct=p;refreshCves();freshness.setText((cached?"세션 캐시":"조회 완료")+" · KEV "+result.kevStatus()+" · EPSS "+result.epssStatus());freshness.setToolTipText((cached?"세션 캐시":"조회 완료")+" · "+result.fetched()+" · KEV "+result.kevStatus()+" · EPSS "+result.epssStatus());status.setText(result.truncated()?"최대 100건 표시 · NVD에 추가 결과가 있습니다. 전체 조회 결과가 아닙니다.":"조건 일치는 실제 취약점 확인이 아닙니다. · 캐시는 확장 종료 시 삭제됩니다.");}
  void refreshCves(){refreshFilterSummary();visible.clear();cveModel.setRowCount(0);clearDetail();if(batch!=null&&selectedProduct!=null)for(var c:batch.candidates()){
    var match=ExposureResearch.applicability(c,selectedProduct);var access=ExposureResearch.remoteAnonymous(c);boolean kevKnown=batch.kevStatus().equals("최신 조회 완료");
    if(c.score()!=null&&c.score()<((Number)minimum.getValue()).doubleValue())continue;
    if(remote.isSelected()&&(access==ExposureResearch.Truth.NO||access==ExposureResearch.Truth.UNKNOWN&&!unknown.isSelected()))continue;
    if(kevOnly.isSelected()&&(kevKnown&&!batch.kev().contains(c.id())||!kevKnown&&!unknown.isSelected()))continue;
    if(!unknown.isSelected()&&(match==ExposureResearch.Truth.UNKNOWN||c.score()==null))continue;
    visible.add(c);cveModel.addRow(new Object[]{c.id(),ExposureResearch.verdict(match),c.score()==null?"미확인":c.score(),kevKnown?(batch.kev().contains(c.id())?"등재":"미등재"):"미확인",batch.epss().containsKey(c.id())?String.format(Locale.ROOT,"%.2f%%",batch.epss().get(c.id())*100):"미확인",access==ExposureResearch.Truth.YES?"조건 일치":access==ExposureResearch.Truth.NO?"조건 제외":"미확인"});
  }tabs.setTitleAt(1,"CVE 후보  "+visible.size());}
  void clearDetail(){for(var a:List.of(observedDetail,affectedDetail,fixedDetail,conditions,evidence))a.setText("");references.removeAllItems();selectedCveTitle.setText("선택 CVE 상세");setFact(cveScore,"CVSS 미확인");setFact(cveVerdict,"조건 미확인");setFact(cveAccess,"미확인");updateCveActions();}
  ExposureResearch.Candidate selectedCve(){int row=cveTable.getSelectedRow();return row<0||row>=cveTable.getRowCount()||cveTable.convertRowIndexToModel(row)>=visible.size()?null:visible.get(cveTable.convertRowIndexToModel(row));}
  static String truthLabel(ExposureResearch.Truth t){return t==ExposureResearch.Truth.YES?"조건 일치":t==ExposureResearch.Truth.NO?"조건 제외":"미확인";}
  void showCve(){var c=selectedCve();if(c==null||selectedProduct==null){updateCveActions();return;}var p=selectedProduct;selectedCveTitle.setText(c.id());
    setFact(cveScore,"CVSS "+Objects.toString(c.score(),"미확인"));setFact(cveVerdict,ExposureResearch.verdict(ExposureResearch.applicability(c,p)));
    setFact(cveAccess,truthLabel(ExposureResearch.remoteAnonymous(c))+" · 원격·인증 불필요 조건");
    observedDetail.setText(p.vendor()+" / "+p.name()+"\n"+(p.version().isBlank()?"버전 미확인":p.version()));affectedDetail.setText(ExposureResearch.affected(c,p));fixedDetail.setText("공급사 공지 확인 필요\n영향 범위 끝값 ≠ 수정 버전");
    conditions.setText(ExposureResearch.verdict(ExposureResearch.applicability(c,p))+"\n\n원격·인증 불필요  "+truthLabel(ExposureResearch.remoteAnonymous(c))+"\nCVSS  "+Objects.toString(c.score(),"미확인")+"\n"+c.vector()+"\n\n운영체제·모듈·설정  추가 확인 필요\n백포트·배포판 패치  추가 확인 필요\n\n조건 일치 ≠ 실제 취약점 확인\n\n"+c.description());
    evidence.setText("관측 원본\n"+p.evidence()+"\n"+p.source()+"\n원본 ID  "+(p.originalId().isBlank()?"연결 없음":p.originalId())+"\n\nNVD 수정일  "+c.modified()+"\nCVSS 출처  "+c.metricSource()+"\n조회 시각  "+batch.fetched()+"\nKEV  "+batch.kevStatus()+"\nEPSS  "+batch.epssStatus());
    references.removeAllItems();references.addItem("https://nvd.nist.gov/vuln/detail/"+c.id());for(String r:c.references())references.addItem(r);for(var a:List.of(observedDetail,affectedDetail,fixedDetail,conditions,evidence))a.setCaretPosition(0);updateCveActions();
  }
  String cveNote(){var c=selectedCve();if(c==null)throw new IllegalArgumentException("CVE 후보를 선택하세요.");return "# "+c.id()+"\n\n"+observedDetail.getText()+"\n영향 버전: "+affectedDetail.getText()+"\n수정 버전: "+fixedDetail.getText()+"\n\n"+conditions.getText()+"\n\n"+evidence.getText()+"\n\n"+String.join("\n",c.references());}
  void copy(String value){if(value.isBlank())throw new IllegalArgumentException("복사할 내용이 없습니다.");Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value),null);status.setText("클립보드에 복사했습니다.");}
  void browse(String value){String normalized=ExposureResearch.url(value);try{if(!Desktop.isDesktopSupported()||!Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))throw new IllegalStateException("브라우저를 열 수 없습니다. URL을 복사하세요.");Desktop.getDesktop().browse(URI.create(normalized));}catch(java.io.IOException e){throw new IllegalStateException("브라우저 열기 실패",e);}}
  void resetProject(){projectEpoch++;invalidateQuery();cache.clear();observations.clear();exposures.clear();target.setText("");refreshProducts();refreshExposures();status.setText("프로젝트 변경 · 조사 세션을 초기화했습니다.");}
  public void close(){closed=true;generation++;if(job!=null)job.cancel(true);worker.shutdownNow();cache.clear();observations.clear();exposures.clear();}
}
