package kr.workbench;

import burp.api.montoya.MontoyaApi;
import java.awt.*;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.table.*;

/** The production, modeless one-touch inspection workspace; opening it performs no requests. */
final class ScopeInspectionPanel extends JPanel implements AutoCloseable {
  interface Actions {
    void original(DiskTrafficStore.PacketRef packet);
    void repeater(DiskTrafficStore.PacketRef packet);
    void compare(DiskTrafficStore.PacketRef packet);
    void evidence(List<DiskTrafficStore.PacketRef> packets, String note);
    void tool(String id, DiskTrafficStore.PacketRef packet);
    default void observed(DiskTrafficStore.PacketRef packet) {}
    default void fullOriginal(DiskTrafficStore.PacketRef packet, boolean request) {}
    default void originalsCleared() {}
  }
  final MontoyaApi api;
  final JTextField url = new JTextField(35);
  final JComboBox<String> scopeChoices = new JComboBox<>();
  final JComboBox<ScopeInspection.Profile> profile = new JComboBox<>();
  final JCheckBox browserMode = new JCheckBox("SPA 메뉴 탐색"), compareAccounts = new JCheckBox("계정별 노출 비교"), katanaMode = new JCheckBox("Katana 탐색");
  final JButton start = new JButton("통합 점검 시작"), pause = new JButton("일시정지"), resume = new JButton("재개"), stop = new JButton("중지");
  final JButton report = new JButton("분류 보고서 저장");
  final JSpinner budget = new JSpinner(new SpinnerNumberModel(100, 1, 500, 10));
  final JSpinner depth = new JSpinner(new SpinnerNumberModel(2, 0, 3, 1));
  final JSpinner delay = new JSpinner(new SpinnerNumberModel(300, 0, 10000, 100));
  final JSpinner timeout = new JSpinner(new SpinnerNumberModel(10, 1, 30, 1));
  final JSpinner maxBody = new JSpinner(new SpinnerNumberModel(2, 1, 5, 1));
  final JSpinner redirectLimit = new JSpinner(new SpinnerNumberModel(3, 0, 5, 1));
  final JSpinner browserPages = new JSpinner(new SpinnerNumberModel(15, 1, 100, 1));
  final JSpinner browserActions = new JSpinner(new SpinnerNumberModel(30, 1, 300, 1));
  final JSpinner browserSeconds = new JSpinner(new SpinnerNumberModel(60, 10, 300, 10));
  final JTextField katanaExecutable = new JTextField("katana", 35);
  final JSpinner katanaSeconds = new JSpinner(new SpinnerNumberModel(60, 10, 300, 10));
  final JSpinner katanaPages = new JSpinner(new SpinnerNumberModel(100, 1, 500, 10));
  final JButton katanaCheck = new JButton("설치 확인");
  final JLabel katanaCheckResult = new JLabel("Katana 1.8.0 실행파일을 지정하세요.");
  final JTextArea katanaStatus = new JTextArea(3, 60);
  final JTabbedPane pages = new JTabbedPane();
  final JLabel progress = new JLabel("SCOPE URL과 계정을 선택하면 시작할 수 있습니다."), feedback = new JLabel(" ");
  final JTextArea detail = new JTextArea(5, 60), browserStatus = new JTextArea(3, 60);
  final Map<String, JTable> tables = new LinkedHashMap<>();
  final Map<String, List<ScopeInspection.Finding>> rows = new HashMap<>();
  final JButton original = new JButton("원본 보기"), repeater = new JButton("Repeater 준비"), compare = new JButton("계정 비교 준비"), evidence = new JButton("증거 추가");
  final JButton js = new JButton("API 찾기로"), payload = new JButton("입력값 검사로");
  final ScopeInspectionBrowser browser;
  final TargetWindow settings;
  volatile ScopeInspectionRunner runner;
  volatile ScopeKatana katana;
  ScopeInspection.Options options;
  ScopeInspection.Snapshot current = empty();
  private final Function<String, List<ScopeInspection.Profile>> profiles;
  private final Actions actions;
  private final ScopeInspectionHistory history;
  private final ExecutorService historyWorker = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r,"Scope-inspection-history");t.setDaemon(true);return t; });
  private final ExecutorService katanaChecks = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r,"Scope-Katana-install-check");t.setDaemon(true);return t; });
  private final ExecutorService reportWorker = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r,"Scope-inspection-report");t.setDaemon(true);return t; });
  private volatile boolean reportBusy;
  private final List<ScopeInspectionRunner> retained = new ArrayList<>();
  private final Set<String> observed = new HashSet<>(), persisted = new HashSet<>();
  private List<ScopeInspection.Finding> changes = List.of();
  private String runScope = "", runProject = "", runHistoryScope = "";
  private volatile boolean closed;
  private boolean rebuilding, accepting;
  private volatile ScopeInspection.Snapshot pending;
  private volatile long generation;
  private final javax.swing.Timer updates;
  private final List<PacketInspector> inspectors = new ArrayList<>();

  ScopeInspectionPanel(MontoyaApi api, Supplier<List<ScopeInspection.Profile>> profiles, Actions actions) {
    this(api, ignored -> profiles.get(), actions);
  }
  ScopeInspectionPanel(MontoyaApi api, Function<String,List<ScopeInspection.Profile>> profiles, Actions actions) {
    super(new BorderLayout(8,8)); this.api=api;this.profiles=profiles;this.actions=actions;
    history=new ScopeInspectionHistory(api);
    browser=new ScopeInspectionBrowser(this::browserObserved, target -> WorkbenchScope.contains(api,target));
    settings=new TargetWindow(this,"통합 점검 · 실행 조건",this::settingsContent);
    setName("wb.scope.inspection");setBorder(BorderFactory.createEmptyBorder(10,12,10,12));
    name(url,"url");name(scopeChoices,"scopes");name(profile,"profile");name(browserMode,"browser.mode");name(compareAccounts,"accounts");name(katanaMode,"katana.mode");
    name(katanaExecutable,"katana.executable");name(katanaSeconds,"katana.seconds");name(katanaPages,"katana.pages");name(katanaCheck,"katana.check");name(katanaCheckResult,"katana.check.result");name(katanaStatus,"katana.status");
    try { String saved=api.persistence().extensionData().getString(BuildInfo.storageKey("scope-katana-executable-v1"));if(saved!=null&&!saved.isBlank())katanaExecutable.setText(saved); } catch(RuntimeException ignored) {}
    katanaCheck.addActionListener(e->checkKatana());
    for (Object[] entry : new Object[][] {{start,"start"},{pause,"pause"},{resume,"resume"},{stop,"stop"},{budget,"budget"},{depth,"depth"},{delay,"delay"},{timeout,"timeout"},{maxBody,"max.body"},{redirectLimit,"redirects"},{browserPages,"browser.pages"},{browserActions,"browser.actions"},{browserSeconds,"browser.seconds"}}) name((JComponent)entry[0],(String)entry[1]);
    profile.setRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){putClientProperty("html.disable",true);return super.getListCellRendererComponent(list,value instanceof ScopeInspection.Profile p?p.label():"비로그인",index,selected,focus);}});
    profile.setPreferredSize(new Dimension(150,32));
    scopeChoices.addItem("SCOPE에서 선택");for(var rule:WorkbenchScope.registeredRules(api))scopeChoices.addItem(rule.url());
    scopeChoices.setPreferredSize(new Dimension(200,32));
    scopeChoices.addActionListener(e->{if(scopeChoices.getSelectedIndex()>0){url.setText(Objects.toString(scopeChoices.getSelectedItem(),""));refreshProfiles();}});
    if(scopeChoices.getItemCount()>1){scopeChoices.setSelectedIndex(1);}else refreshProfiles();
    JButton reload=new JButton("목록 새로고침");name(reload,"refresh");reload.addActionListener(e->refreshTargets());
    JButton conditions=new JButton("실행 조건");name(conditions,"settings");conditions.addActionListener(e->settings.open());
    JPanel target=new JPanel(new BorderLayout(8,0));target.add(url);target.add(WorkbenchUi.row(profile,conditions),BorderLayout.EAST);
    JPanel top=new JPanel(new BorderLayout(0,7));top.add(WorkbenchUi.row(scopeChoices,reload,katanaMode,browserMode,compareAccounts),BorderLayout.NORTH);top.add(target);
    start.addActionListener(e->safe(this::startInspection));pause.addActionListener(e->safe(()->{if(runner!=null)runner.pause();if(katana!=null)katana.pause();}));
    resume.addActionListener(e->safe(this::resumeInspection));stop.addActionListener(e->safe(this::stopInspection));
    name(report,"report");report.setToolTipText("현재 응답의 API·토큰·키·민감정보를 원문 값과 위치별로 분류한 로컬 ZIP을 저장합니다.");
    report.addActionListener(e->safe(this::chooseReport));
    WorkbenchUi.primary(start);top.add(WorkbenchUi.row(start,pause,resume,stop,report),BorderLayout.SOUTH);add(top,BorderLayout.NORTH);
    name(pages,"pages");
    addFindingsTab("요약","summary");addFindingsTab("환경·DB","environment");addPathsTab();
    addFindingsTab("API·명세 대조","api");addFindingsTab("키·민감정보","secrets");addFindingsTab("응답·내부정보","observations");
    addFindingsTab("계정별 차이","access");addFindingsTab("커버리지","coverage");addFindingsTab("이전 실행과 비교","changes");
    addFindingsTab("Katana 수집","katana");
    katanaStatus.setEditable(false);katanaStatus.setLineWrap(true);katanaStatus.setWrapStyleWord(true);
    ((JPanel)pages.getComponentAt(pages.getTabCount()-1)).add(katanaStatus,BorderLayout.NORTH);
    JPanel browserPage=new JPanel(new BorderLayout(5,5));browserStatus.setEditable(false);browserStatus.setLineWrap(true);browserStatus.setWrapStyleWord(true);name(browserStatus,"browser.status");
    browserPage.add(browserStatus,BorderLayout.NORTH);browserPage.add(new JScrollPane(createTable("browser",new String[]{"계정/실행 주체","화면·API","상태","근거"})));
    JButton connect=new JButton("기능 매핑에서 브라우저 연결");name(connect,"browser.connect");connect.addActionListener(e->actions.tool("mapper",null));
    browserPage.add(WorkbenchUi.row(connect,new JLabel("도우미 0.4.0 연결 · 현재 사이트 활성화 · SPA 통합 탐색 허용")),BorderLayout.SOUTH);pages.addTab("브라우저 메뉴",browserPage);
    detail.setEditable(false);detail.setLineWrap(true);detail.setWrapStyleWord(true);name(detail,"detail");
    JPanel result=new JPanel(new BorderLayout(0,6));result.add(pages);result.add(new JScrollPane(detail),BorderLayout.SOUTH);
    add(result,BorderLayout.CENTER);
    original.addActionListener(e->safe(()->actions.original(requireOriginal())));repeater.addActionListener(e->safe(()->actions.repeater(requireOriginal())));
    compare.addActionListener(e->safe(()->actions.compare(requireOriginal())));evidence.addActionListener(e->safe(this::captureEvidence));
    name(original,"original");name(repeater,"repeater");name(compare,"compare");name(evidence,"evidence");
    name(js,"js");name(payload,"payload");
    js.addActionListener(e->safe(()->actions.tool("js",requireOriginal())));payload.addActionListener(e->safe(()->actions.tool("payload",requireOriginal())));
    JPanel bottom=new JPanel(new BorderLayout(0,4));name(progress,"progress");name(feedback,"feedback");
    bottom.add(WorkbenchUi.row(original,repeater,compare,evidence,js,payload),BorderLayout.NORTH);
    JPanel notes=new JPanel(new GridLayout(0,1,0,3));notes.add(progress);notes.add(feedback);bottom.add(notes);add(bottom,BorderLayout.SOUTH);
    pages.addChangeListener(e->selectedChanged());
    updates=new javax.swing.Timer(150,e->{if(pending!=null){var next=pending;pending=null;accept(next);}checkScope();refreshBrowser();refreshKatana();});updates.start();
    WorkbenchUi.style(this);refreshActions();refreshBrowser();refreshKatana();
  }
  private static ScopeInspection.Snapshot empty(){return new ScopeInspection.Snapshot("",ScopeInspection.State.READY,"준비",0,0,0,0,0,0,List.of(),List.of(),List.of(),0,0);}
  private static void name(JComponent component,String suffix){component.setName("wb.scope.inspection."+suffix);}
  private static DefaultTableModel model(String[] columns){return new DefaultTableModel(columns,0){public boolean isCellEditable(int r,int c){return false;}};}
  private JTable createTable(String key,String[] columns){
    JTable table=new JTable(model(columns));name(table,key);table.setAutoCreateRowSorter(true);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    DefaultTableCellRenderer renderer=new DefaultTableCellRenderer();renderer.putClientProperty("html.disable",true);table.setDefaultRenderer(Object.class,renderer);
    table.setRowHeight(26);table.setFillsViewportHeight(true);table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!rebuilding)selectedChanged();});
    tables.put(key,table);return table;
  }
  private void addFindingsTab(String title,String key){
    JTable table=createTable(key,new String[]{"분류","내용","계정","상태","확실성","중요도","URL"});
    table.getColumnModel().getColumn(1).setPreferredWidth(230);table.getColumnModel().getColumn(6).setPreferredWidth(300);
    JPanel body=new JPanel(new BorderLayout());body.setName("wb.scope.inspection.tab."+key);body.add(new JScrollPane(table));pages.addTab(title,body);
  }
  private void addPathsTab(){JTable table=createTable("paths",new String[]{"계정","메서드","URL","HTTP","분류","분석 범위"});table.getColumnModel().getColumn(2).setPreferredWidth(340);JPanel body=new JPanel(new BorderLayout());body.setName("wb.scope.inspection.tab.paths");body.add(new JScrollPane(table));pages.addTab("경로·응답",body);}
  JPanel settingsContent(){
    JButton choose=new JButton("파일 선택");name(choose,"katana.choose");choose.addActionListener(e->{
      if(GraphicsEnvironment.isHeadless()){katanaCheckResult.setText("실행파일의 절대경로를 입력하세요.");return;}
      JFileChooser picker=new JFileChooser();picker.setFileSelectionMode(JFileChooser.FILES_ONLY);
      if(picker.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)katanaExecutable.setText(picker.getSelectedFile().getAbsolutePath());
    });
    JPanel body=TargetWindow.options(
      WorkbenchUi.row(new JLabel("전체 HTTP 요청 한도"),budget,new JLabel("탐색 깊이"),depth),
      WorkbenchUi.row(new JLabel("요청 간격(ms)"),delay,new JLabel("응답 시간(초)"),timeout),
      WorkbenchUi.row(new JLabel("본문 분석 한도(MiB)"),maxBody,new JLabel("리디렉션 한도"),redirectLimit),
      WorkbenchUi.row(new JLabel("브라우저 화면 한도"),browserPages,new JLabel("동작 한도"),browserActions),
      WorkbenchUi.row(new JLabel("브라우저 시간(초)"),browserSeconds),
      new JSeparator(),
      WorkbenchUi.row(new JLabel("Katana 실행파일 · 1.8.0"),katanaExecutable,choose,katanaCheck),
      katanaCheckResult,
      WorkbenchUi.row(new JLabel("Katana 페이지 한도"),katanaPages,new JLabel("수집 시간(초)"),katanaSeconds),
      new JLabel("Katana는 별도 설치합니다. HTML·JS·폼 경로를 수집하며, 동적 메뉴는 SPA 탐색을 함께 사용하세요."),
      new JLabel("Katana 요청도 선택한 HTTP 계정·현재 SCOPE·전체 요청 한도를 사용합니다."),
      new JLabel("HTTP 조회는 순차 실행하며 기준·신원·리디렉션 요청도 같은 한도에 포함합니다."),
      new JLabel("본문 분석 한도는 수신 다운로드 자체의 강제 상한이 아닙니다. 덤프·대용량 경로는 별도 검토합니다."),
      new JLabel("브라우저 리소스 요청 수는 HTTP 검사 요청 예산과 별도로 표시합니다."),
      new JLabel("비교 계정은 기존 계정 비교 도구에서 지정합니다. 발견한 키를 인증값으로 사용하지 않습니다."));
    Component[] fields=body.getComponents();body.removeAll();body.setLayout(new GridBagLayout());
    int row=0;
    for(Component field:fields){
      if(field instanceof JLabel note&&field!=katanaCheckResult){
        JTextArea explanation=new JTextArea(note.getText(),2,1);explanation.setEditable(false);explanation.setLineWrap(true);explanation.setWrapStyleWord(true);explanation.setOpaque(false);field=explanation;
      }
      if(field instanceof JSeparator)field.setPreferredSize(new Dimension(1,2));
      GridBagConstraints cell=new GridBagConstraints();cell.gridx=0;cell.gridy=row++;cell.weightx=1;cell.fill=GridBagConstraints.HORIZONTAL;cell.anchor=GridBagConstraints.NORTHWEST;cell.insets=new Insets(3,8,3,8);body.add(field,cell);
    }
    GridBagConstraints space=new GridBagConstraints();space.gridx=0;space.gridy=row;space.weighty=1;space.weightx=1;space.fill=GridBagConstraints.BOTH;body.add(Box.createGlue(),space);
    WorkbenchUi.style(body);body.setName("wb.scope.inspection.settings.fields");
    JPanel content=new JPanel(new BorderLayout());content.setName("wb.scope.inspection.settings.content");content.add(new JScrollPane(body));return content;
  }
  private void checkKatana(){
    String executable=katanaExecutable.getText().strip();katanaCheck.setEnabled(false);katanaCheckResult.setText("Katana 설치와 버전을 확인합니다…");
    katanaChecks.execute(()->{String result;boolean success=false;try{result=ScopeKatana.probe(executable);success=true;}catch(Exception error){result="Katana 1.8.0을 확인하지 못했습니다. 설치 위치와 실행 권한을 확인하세요.";}
      final String message=result;final boolean ok=success;
      SwingUtilities.invokeLater(()->{if(closed)return;katanaCheck.setEnabled(true);if(!executable.equals(katanaExecutable.getText().strip()))return;katanaCheckResult.setText(message);if(ok)try{api.persistence().extensionData().setString(BuildInfo.storageKey("scope-katana-executable-v1"),executable);}catch(RuntimeException ignored){}});
    });
  }
  void refreshTargets(){
    String value=url.getText();scopeChoices.removeAllItems();scopeChoices.addItem("SCOPE에서 선택");for(var rule:WorkbenchScope.registeredRules(api))scopeChoices.addItem(rule.url());url.setText(value);refreshProfiles();
  }
  void refreshProfiles(){
    String selected=profile.getSelectedItem() instanceof ScopeInspection.Profile p?p.id():"anonymous";
    List<ScopeInspection.Profile> available=profiles.apply(url.getText());profile.removeAllItems();
    for(var p:available){profile.addItem(p);if(p.id().equals(selected))profile.setSelectedItem(p);}
    if(profile.getItemCount()==0)profile.addItem(new ScopeInspection.Profile("anonymous","비로그인",Map.of(),null));
  }
  void startInspection(){
    if(closed)throw new IllegalArgumentException("종료된 통합 점검입니다.");
    if(reportBusy)throw new IllegalArgumentException("현재 보고서 저장이 끝나면 새 점검을 시작할 수 있습니다.");
    if(runner!=null&&(runner.snapshot().state()==ScopeInspection.State.RUNNING||runner.snapshot().state()==ScopeInspection.State.PAUSED))throw new IllegalArgumentException("진행 중 점검을 먼저 중지하세요.");
    String target=url.getText().strip();URI uri=URI.create(target);
    if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||!WorkbenchScope.contains(api,target))throw new IllegalArgumentException("등록된 SCOPE 안의 HTTP(S) URL을 선택하세요.");
    List<ScopeInspection.Profile> available=List.copyOf(profiles.apply(target));
    ScopeInspection.Profile selected=(ScopeInspection.Profile)profile.getSelectedItem();
    if(selected==null)throw new IllegalArgumentException("계정을 선택하세요.");
    List<ScopeInspection.Profile> chosen;
    if(compareAccounts.isSelected())chosen=katanaMode.isSelected()?available.stream().sorted(Comparator.comparingInt(p->p.id().equals(selected.id())?0:1)).toList():available;
    else chosen=available.stream().filter(p->p.id().equals(selected.id())).limit(1).toList();
    if(chosen.isEmpty())throw new IllegalArgumentException("대상과 계정이 바뀌었습니다. 계정 목록을 새로고침하세요.");
    options=new ScopeInspection.Options(target,number(budget),number(depth),number(delay),number(timeout)*1000,number(maxBody)*1024*1024,number(redirectLimit),browserMode.isSelected(),compareAccounts.isSelected(),chosen,katanaMode.isSelected(),number(katanaSeconds),number(katanaPages));
    ScopeKatana.Config katanaConfig=options.katana()?new ScopeKatana.Config(katanaExecutable.getText().strip(),options.katanaSeconds(),options.katanaPages(),options.depth(),options.timeoutMillis(),options.maxBodyBytes(),options.delayMillis()):null;
    WorkbenchScope scope=scope();runScope=scope.inspectionFingerprint();runProject=WorkspaceResume.projectIdentity(api);runHistoryScope=history.scopeFingerprint();
    List<String> include=options.browser()?scope.browserIncludes(target):List.of(), exclude=options.browser()?scope.browserExclusions(target):List.of();
    if(runner!=null){retained.add(runner);if(retained.size()>2)retained.remove(0).close();}
    String expectedScope=runScope,expectedProject=runProject;
    long token=++generation;
    if(katana!=null){katana.close();katana=null;}
    runner=new ScopeInspectionRunner(api,options,t->!closed&&expectedScope.equals(scope.inspectionFingerprint())&&expectedProject.equals(WorkspaceResume.projectIdentity(api))&&WorkbenchScope.contains(api,t),snap->{if(!closed&&token==generation&&expectedProject.equals(WorkspaceResume.projectIdentity(api)))pending=snap;});
    if(options.katana()){
      ScopeInspectionRunner owner=runner;ScopeInspection.Options frozen=options;
      owner.registerKatana(selected.id(),System.currentTimeMillis()+options.katanaSeconds()*1000L+5000);
      String origin=EnvironmentCatalog.origin(uri);
      Predicate<String> admitted=t->!closed&&token==generation&&expectedScope.equals(scope.inspectionFingerprint())&&expectedProject.equals(WorkspaceResume.projectIdentity(api))&&WorkbenchScope.contains(api,t);
      katana=new ScopeKatana(katanaConfig,target,List.of(origin+"/"),List.of(),admitted,
        (address,method)->katanaResponse(owner,address,method,frozen.maxBodyBytes()),
        candidate->{if(!closed&&token==generation)owner.offerKatana(candidate);},
        blocked->{if(!closed&&token==generation)owner.offerKatana(new ScopeInspection.Candidate(blocked.url(),blocked.method(),"Katana 차단",blocked.reason(),false));},
        (complete,note)->{if(!closed&&token==generation)owner.finishKatana(complete,note);},ignored->{});
      try{api.persistence().extensionData().setString(BuildInfo.storageKey("scope-katana-executable-v1"),katanaExecutable.getText().strip());}catch(RuntimeException ignored){}
    }
    ((DefaultTableModel)tables.get("browser").getModel()).setRowCount(0);
    changes=List.of();observed.clear();current=runner.snapshot();
    if(options.browser())browser.begin(current.id(),target,include,exclude,number(browserPages),number(browserActions),System.currentTimeMillis()+number(browserSeconds)*1000L);
    else browser.stop();
    accept(current);runner.start();accept(runner.snapshot());if(katana!=null)katana.start();
  }
  static ScopeKatanaProxy.Response katanaResponse(ScopeInspectionRunner owner,String address,String method,int maximum) throws Exception {
    CompletableFuture<DiskTrafficStore.PacketRef> future=owner.fetchKatana(address,method);
    DiskTrafficStore.PacketRef packet;
    try{packet=future.get();}catch(InterruptedException error){future.cancel(false);Thread.currentThread().interrupt();throw error;}
    var response=packet.response();if(response==null)throw new IllegalStateException("Katana에 연결할 응답 원본이 없습니다.");
    byte[] body=response.body()==null?new byte[0]:response.body().getBytes();if(body.length>maximum)body=Arrays.copyOf(body,maximum);
    return new ScopeKatanaProxy.Response(response.statusCode(),response.headers().stream().map(h->new ScopeKatanaProxy.Header(h.name(),h.value())).toList(),body);
  }
  private WorkbenchScope scope(){return WorkbenchScope.current(api);}
  private static int number(JSpinner spinner){return ((Number)spinner.getValue()).intValue();}
  private void resumeInspection(){
    if(runner==null)return;
    if(!runScope.equals(scope().inspectionFingerprint())||!runProject.equals(WorkspaceResume.projectIdentity(api)))throw new IllegalArgumentException("SCOPE 또는 프로젝트가 바뀌었습니다. 새 점검으로 시작하세요.");
    List<ScopeInspection.Profile> all=profiles.apply(options.url());List<ScopeInspection.Profile> fresh=new ArrayList<>();
    for(var previous:options.profiles()){var candidate=all.stream().filter(p->p.id().equals(previous.id())).findFirst().orElseThrow(()->new IllegalArgumentException("같은 계정을 다시 연결하세요. 다른 계정은 새 점검으로 시작합니다."));fresh.add(candidate);}
    runner.resume(fresh);if(katana!=null&&"PAUSED".equals(katana.snapshot().state()))katana.resume();
  }
  void stopInspection(){if(runner!=null)runner.stop();if(katana!=null)katana.stop();browser.stop();}
  void scopeChanged(){
    if(!runProject.isEmpty()&&!runProject.equals(WorkspaceResume.projectIdentity(api))){
      generation++;pending=null;browser.stop();if(katana!=null){katana.close();katana=null;}actions.originalsCleared();closeInspectors();if(runner!=null)runner.close();runner=null;for(var old:retained)old.close();retained.clear();options=null;changes=List.of();observed.clear();runProject="";runScope="";accept(empty());
      feedback.setText("프로젝트가 바뀌어 이전 실행 원본을 정리했습니다. 새 SCOPE를 선택하세요.");return;
    }
    if(runner!=null&&(current.state()==ScopeInspection.State.RUNNING||current.state()==ScopeInspection.State.PAUSED)){generation++;pending=null;stopInspection();accept(runner.snapshot());feedback.setText("SCOPE 변경으로 중지했습니다. 새 조건으로 시작하세요.");}
  }
  private void checkScope(){if(runner!=null&&(!runScope.equals(scope().inspectionFingerprint())||!runProject.equals(WorkspaceResume.projectIdentity(api))))scopeChanged();}
  Map<String,Object> browserCommand(String origin){return browser.command(origin);}
  void browserEvent(Map<String,Object> event){browser.event(event);}
  private void browserObserved(Map<String,Object> event){
    if(runner!=null)runner.offerBrowser(event);
    SwingUtilities.invokeLater(()->{
      if(closed||!Objects.toString(event.get("runId"),"").equals(current.id()))return;
      DefaultTableModel model=(DefaultTableModel)tables.get("browser").getModel();
      if(model.getRowCount()<500)model.addRow(new Object[]{"브라우저 세션",Objects.toString(event.get("url"),Objects.toString(event.get("page"),"")),Objects.toString(event.get("kind"),""),Objects.toString(event.get("label"),"")+" · "+Objects.toString(event.get("routeId"),"")});
      refreshBrowser();
    });
  }
  private void refreshBrowser(){
    if(closed)return;var value=browser.snapshot();browserStatus.setText("브라우저 세션 · "+value.state()+" · "+value.message()+"\n화면 "+value.pages()+" · 동작 "+value.actions()+" · 브라우저 리소스 요청 "+value.requests()+" · 제외 "+value.excluded()+"\nHTTP 재조회는 선택한 검사 계정을 사용하며 브라우저 로그인 계정과 별도입니다.");
  }
  private void refreshKatana(){
    if(closed)return;
    if(katana==null){katanaStatus.setText("Katana 탐색을 선택해 통합 점검을 시작하세요. 실행 조건에서 설치 위치를 확인할 수 있습니다.\nHTML·JS·폼 경로 → 환경·API·민감정보·계정 차이·이력·미확인 항목으로 연결합니다.");return;}
    var status=katana.snapshot();showKatanaStatus(status);
    if(current.state()==ScopeInspection.State.PAUSED&&"RUNNING".equals(status.state()))katana.pause();
    if((current.state()==ScopeInspection.State.STOPPED||current.state()==ScopeInspection.State.FAILED)&&"RUNNING".equals(status.state()))katana.stop();
  }
  void showKatanaStatus(ScopeKatana.Snapshot status){
    katanaStatus.setText("Katana · "+katanaState(status.state())+" · "+status.message()+"\n수집 기록 "+status.records()+" · 경로 후보 "+status.endpoints()+" · 제외 "+status.excluded()+"\n실제 요청은 선택한 HTTP 계정과 전체 요청 한도를 공유합니다. 동적 SPA 메뉴는 브라우저 탐색에서 확인하세요.");
  }
  private static String katanaState(String state){return switch(state){case "READY"->"준비";case "RUNNING"->"수집 중";case "PAUSED"->"일시정지";case "COMPLETED"->"수집 큐 완료";case "PARTIAL"->"부분 수집";case "FAILED"->"실행 실패";case "STOPPED"->"중지";case "CLOSED"->"종료";default->state;};}
  void accept(ScopeInspection.Snapshot value){
    if(closed||accepting)return;if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(()->accept(value));return;}
    accepting=true;rebuilding=true;
    try{
      Map<String,Object> selectedBefore=new HashMap<>();Map<String,Integer> positionsBefore=new HashMap<>();
      for(var entry:tables.entrySet()){positionsBefore.put(entry.getKey(),entry.getValue().getSelectedRow());Object selected=selectedId(entry.getValue(),entry.getKey());if(selected!=null)selectedBefore.put(entry.getKey(),selected);}
      current=value;progress.setText(state(value.state())+" · 요청 "+value.sent()+" / "+(options==null?"—":options.budget())+" · 확인 "+value.completed()+" · 대기 "+value.pending()+" · 제외 "+value.excluded()+" · 실패 "+value.failed()+" · 부분 "+value.partial());
      progress.setToolTipText(value.message());feedback.setText(value.message());
      for(var entry:tables.entrySet()){
        String key=entry.getKey();if(key.equals("browser"))continue;
        DefaultTableModel model=(DefaultTableModel)entry.getValue().getModel();int selection=positionsBefore.getOrDefault(key,-1);Object selectedId=selectedBefore.get(key);model.setRowCount(0);
        if(key.equals("paths")){for(var page:value.pages())model.addRow(new Object[]{page.account(),page.method(),ScopeInspectionAnalysis.safeUrl(page.url()),page.status(),page.classification(),page.complete()?"검사 범위 내 완료":"부분 · "+page.note()});}
        else {
          List<ScopeInspection.Finding> selected=key.equals("changes")?changes:value.findings().stream().filter(f->matches(key,f.category())).toList();
          if(key.equals("coverage")){
            var extras=new ArrayList<>(selected);for(var candidate:value.candidates())if(!candidate.executable())extras.add(new ScopeInspection.Finding("COVERAGE",candidate.label(),ScopeInspectionAnalysis.safeUrl(candidate.url()),candidate.method(),"", "미확인","정보","필수값·원본 확인",candidate.source(),"",0,-1,""));selected=List.copyOf(extras);
          }
          rows.put(key,selected);for(var f:selected)model.addRow(new Object[]{category(f.category()),f.title(),f.account(),f.state(),f.confidence(),severity(f.severity()),f.url()});
        }
        restoreSelection(entry.getValue(),key,selectedId,selection);
      }
      for(var page:value.pages())if(page.original()!=null&&observed.add(page.original().id()))try{actions.observed(page.original());}catch(RuntimeException error){feedback.setText("기존 도구 연결 일부 실패 · 원본은 통합 점검에 보관됩니다.");}
      if(options!=null&&terminal(value.state())&&!value.id().isEmpty()&&persisted.add(value.id())){if(browser.active())browser.stop();saveHistory(options,value,runProject,runScope,runHistoryScope);}
      refreshActions();
    }finally{rebuilding=false;accepting=false;}
    selectedChanged();
  }
  private void saveHistory(ScopeInspection.Options savedOptions,ScopeInspection.Snapshot snapshot,String project,String scopeFingerprint,String historyScopeFingerprint){
    historyWorker.execute(()->{
      try{if(closed||!project.equals(WorkspaceResume.projectIdentity(api))||!scopeFingerprint.equals(scope().inspectionFingerprint()))return;List<ScopeInspection.Finding> delta=history.changes(savedOptions,snapshot,project,historyScopeFingerprint);history.save(savedOptions,snapshot,project,historyScopeFingerprint);
        SwingUtilities.invokeLater(()->{if(!closed&&current.id().equals(snapshot.id())&&project.equals(WorkspaceResume.projectIdentity(api))){changes=delta;accept(current);}});
      }catch(Exception error){SwingUtilities.invokeLater(()->{if(!closed)feedback.setText("현재 결과는 유지되지만 실행 요약 저장에 실패했습니다. 증거 ZIP을 저장하세요.");});}
    });
  }
  private static boolean terminal(ScopeInspection.State value){return value==ScopeInspection.State.COMPLETED||value==ScopeInspection.State.STOPPED||value==ScopeInspection.State.FAILED;}
  private static String state(ScopeInspection.State value){return switch(value){case READY->"준비";case RUNNING->"점검 중";case PAUSED->"일시정지";case STOPPED->"중지·미확인 포함";case COMPLETED->"대기열 처리 완료";case FAILED->"실패·미확인 포함";case CLOSED->"종료";};}
  private static String category(String value){return switch(value){case "ENVIRONMENT"->"환경";case "DATABASE"->"DB";case "API"->"API";case "SECRET"->"키·민감정보";case "INTERNAL"->"내부정보";case "RESPONSE"->"응답";case "COVERAGE"->"점검 범위";case "INVENTORY"->"명세 대조";case "CHANGE"->"변경";case "ACCESS"->"계정 차이";case "KATANA"->"Katana";default->value;};}
  private static String severity(String value){return switch(value){case "INFO"->"정보";case "LOW"->"낮음";case "MEDIUM"->"중간";case "HIGH"->"높음";default->value;};}
  private static boolean matches(String key,String category){return switch(key){case "summary"->true;case "environment"->Set.of("ENVIRONMENT","DATABASE").contains(category);case "api"->Set.of("API","INVENTORY").contains(category);case "secrets"->category.equals("SECRET");case "observations"->Set.of("INTERNAL","RESPONSE").contains(category);case "access"->Set.of("ACCESS","ACCOUNT","PERMISSION").contains(category);case "coverage"->category.equals("COVERAGE");case "katana"->category.equals("KATANA");default->false;};}
  private JTable selectedTable(){int n=pages.getSelectedIndex();if(n<0)return null;Component body=pages.getComponentAt(n);for(var e:tables.entrySet())if(SwingUtilities.isDescendingFrom(e.getValue(),body))return e.getValue();return null;}
  private String key(JTable table){return tables.entrySet().stream().filter(e->e.getValue()==table).map(Map.Entry::getKey).findFirst().orElse("");}
  private record FindingSelection(String category,String url,String method,String account,String original,int line,int offset,String fingerprint){}
  private static FindingSelection findingSelection(ScopeInspection.Finding finding){return new FindingSelection(finding.category(),finding.url(),finding.method(),finding.account(),finding.originalId(),finding.line(),finding.offset(),finding.fingerprint());}
  private Object selectedId(JTable table,String key){int row=table.getSelectedRow();if(row<0)return null;row=table.convertRowIndexToModel(row);if(key.equals("paths"))return row<current.pages().size()&&current.pages().get(row).original()!=null?current.pages().get(row).original().id():null;var entries=rows.getOrDefault(key,List.of());return row<entries.size()?findingSelection(entries.get(row)):null;}
  private void restoreSelection(JTable table,String key,Object selected,int old){if(table.getRowCount()==0)return;int row=Math.min(Math.max(0,old),table.getRowCount()-1);if(selected!=null){if(key.equals("paths")){for(int i=0;i<current.pages().size();i++)if(current.pages().get(i).original()!=null&&selected.equals(current.pages().get(i).original().id())){row=i;break;}}else{var list=rows.getOrDefault(key,List.of());for(int i=0;i<list.size();i++)if(selected.equals(findingSelection(list.get(i)))){row=i;break;}}}if(row<table.getModel().getRowCount()){int view=table.convertRowIndexToView(row);if(view>=0)table.setRowSelectionInterval(view,view);}}
  ScopeInspection.Finding selectedFinding(){JTable table=selectedTable();if(table==null||table.getSelectedRow()<0)return null;String key=key(table);int row=table.convertRowIndexToModel(table.getSelectedRow());var list=rows.getOrDefault(key,List.of());return row<list.size()?list.get(row):null;}
  DiskTrafficStore.PacketRef selectedOriginal(){JTable table=selectedTable();if(table!=null&&key(table).equals("paths")&&table.getSelectedRow()>=0){int n=table.convertRowIndexToModel(table.getSelectedRow());return n<current.pages().size()?current.pages().get(n).original():null;}var finding=selectedFinding();if(finding==null||finding.originalId().isEmpty())return null;for(var page:current.pages())if(page.original()!=null&&finding.originalId().equals(page.original().id()))return page.original();return null;}
  private DiskTrafficStore.PacketRef requireOriginal(){var ref=selectedOriginal();if(ref==null)throw new IllegalArgumentException("현재 실행의 원본이 연결된 항목을 선택하세요. 이전 요약·제외 항목은 원본을 대신 만들지 않습니다.");return ref;}
  private void selectedChanged(){if(rebuilding)return;var f=selectedFinding();if(f!=null)detail.setText(f.title()+"\n"+f.state()+" · "+f.confidence()+" · "+f.severity()+"\n"+f.detail()+"\n원본: "+f.originalId()+" · 본문 줄 "+f.line()+" · 문자 위치 "+f.offset());else{var ref=selectedOriginal();detail.setText(ref==null?"원본이 연결된 결과를 선택하세요.":"원본 "+ref.id()+"\n"+ref.method()+" "+ScopeInspectionAnalysis.safeUrl(ref.url())+"\n요청 "+ref.requestLength()+" bytes · 응답 "+ref.responseLength()+" bytes");}detail.setCaretPosition(0);refreshActions();}
  private void captureEvidence(){var packet=requireOriginal();actions.evidence(List.of(packet),"통합 점검 "+current.id()+" · 선택 계정/원본 연결 · "+(selectedFinding()==null?"경로 응답":selectedFinding().title()));}
  private void chooseReport(){
    if(GraphicsEnvironment.isHeadless()){feedback.setText("분류 보고서는 데스크톱의 저장 위치 선택 창에서 저장합니다. 원문 값이 포함됩니다.");return;}
    JFileChooser picker=new JFileChooser();picker.setDialogTitle("분류 보고서 저장 · 응답의 원문 값 포함");
    picker.setSelectedFile(new java.io.File("Workbench_Simple_"+BuildInfo.VERSION+"_"+current.id()+"_report.zip"));
    if(picker.showSaveDialog(this)==JFileChooser.APPROVE_OPTION)saveReport(picker.getSelectedFile().toPath());
  }
  void saveReport(java.nio.file.Path destination){
    if(closed||reportBusy)throw new IllegalArgumentException("보고서 저장 상태를 확인하세요.");
    if(current.state()==ScopeInspection.State.RUNNING||current.state()==ScopeInspection.State.PAUSED)throw new IllegalArgumentException("점검을 완료하거나 중지한 뒤 현재 결과를 저장하세요.");
    if(current.pages().stream().noneMatch(p->p.original()!=null))throw new IllegalArgumentException("현재 실행의 응답 원본이 있어야 분류 보고서를 저장할 수 있습니다.");
    ScopeInspection.Snapshot frozen=current;long token=generation;reportBusy=true;refreshActions();
    feedback.setText("응답 원문을 API·민감정보·토큰·키·환경별로 분류해 저장합니다…");
    reportWorker.execute(()->{
      String message;
      try{var saved=ScopeInspectionReport.write(destination,frozen);message="분류 보고서 저장 완료 · "+saved.values()+"개 값 · 원문 값 포함"+(saved.complete()?"":" · 부분·미확인 범위 포함");}
      catch(Exception error){message="분류 보고서 저장 실패 · 원본이나 저장 위치를 확인하세요. 기존 파일은 덮어쓰지 않습니다.";}
      final String result=message;
      SwingUtilities.invokeLater(()->{reportBusy=false;if(closed)return;if(token==generation&&current.id().equals(frozen.id()))feedback.setText(result);refreshActions();});
    });
  }
  private void refreshActions(){boolean active=current.state()==ScopeInspection.State.RUNNING||current.state()==ScopeInspection.State.PAUSED;start.setEnabled(!closed&&!active&&!reportBusy);report.setEnabled(!closed&&!active&&!reportBusy&&current.pages().stream().anyMatch(p->p.original()!=null));pause.setEnabled(!closed&&current.state()==ScopeInspection.State.RUNNING);resume.setEnabled(!closed&&current.state()==ScopeInspection.State.PAUSED);stop.setEnabled(!closed&&active);for(JComponent c:List.of(url,scopeChoices,profile,browserMode,compareAccounts,katanaMode,katanaExecutable,katanaPages,katanaSeconds))c.setEnabled(!active&&!closed);boolean linked=!closed&&selectedOriginal()!=null;for(JButton b:List.of(original,repeater,compare,evidence,js,payload)){b.setEnabled(linked);b.setToolTipText(linked?"선택 원본을 연결합니다. 준비·화면 이동은 대상 요청을 보내지 않습니다.":"원본이 연결된 결과를 먼저 선택하세요.");}}
  JPanel originalContent(DiskTrafficStore.PacketRef packet){
    PacketInspector request=new PacketInspector("wb.scope.inspection.original.request"),response=new PacketInspector("wb.scope.inspection.original.response");inspectors.add(request);inspectors.add(response);
    request.setPacket(packet.requestPrefix(PacketInspector.PREVIEW_BYTES),null,packet.requestLength());response.setPacket(packet.responsePrefix(PacketInspector.PREVIEW_BYTES),null,packet.responseLength());
    request.setNativeAction(()->safe(()->actions.fullOriginal(packet,true)));response.setNativeAction(packet.hasResponse()?()->safe(()->actions.fullOriginal(packet,false)):null);
    JPanel content=new JPanel(new BorderLayout(5,5));content.setName("wb.scope.inspection.original");content.add(new JLabel("원본 "+packet.id()+" · 전체 바이트 보관 / 화면 앞 64 KiB 미리보기"),BorderLayout.NORTH);
    content.putClientProperty("wb.scope.inspection.inspectors",List.of(request,response));content.add(WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT,WorkbenchUi.card("요청 원본",request),WorkbenchUi.card("응답 원본",response),.5));WorkbenchUi.style(content);return content;
  }
  void closeOriginalContent(JComponent content){Object value=content.getClientProperty("wb.scope.inspection.inspectors");if(value instanceof List<?> list)for(Object item:list)if(item instanceof PacketInspector inspector){inspector.close();inspectors.remove(inspector);}content.putClientProperty("wb.scope.inspection.inspectors",null);}
  private void closeInspectors(){for(var inspector:inspectors)inspector.close();inspectors.clear();}
  private void safe(Runnable action){try{action.run();}catch(RuntimeException error){feedback.setText(Objects.requireNonNullElse(error.getMessage(),"실행 조건을 확인하세요."));}}
  @Override public void close(){if(closed)return;closed=true;report.setEnabled(false);updates.stop();browser.close();if(katana!=null)katana.close();actions.originalsCleared();if(runner!=null)runner.close();for(var old:retained)old.close();retained.clear();closeInspectors();settings.close();historyWorker.shutdownNow();katanaChecks.shutdownNow();reportWorker.shutdownNow();}
}
