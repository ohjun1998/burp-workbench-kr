package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.*;
import java.util.function.IntFunction;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

public final class EnvironmentPanel extends JPanel implements AutoCloseable {
  private final TargetWindow targetSettings;
  private final JLabel targetSummary = new JLabel("대상 · 트래픽에서 선택하세요");
  final EnvironmentCatalog catalog = new EnvironmentCatalog();
  final JComboBox<String> origins = new JComboBox<>();
  final JTextField prefix = new JTextField("", 14);
  final JCheckBox compareMissing = new JCheckBox("없는 경로 응답과 비교", true),
      unverifiedOnly = new JCheckBox("일괄 검증은 미검증 후보만", true);
  final JCheckBox repeatMissing = new JCheckBox("기준 응답 2회 안정성 확인", true);
  final JTextField volatileFields = new JTextField(30);
  final TargetWindow candidateSettings =
      new TargetWindow(this, "환경 탐색 · 후보 설정", this::settingsContent);
  final JCheckBox common = new JCheckBox("공통 후보 포함", true);
  final JComboBox<Integer> limit = new JComboBox<>(new Integer[] {30, 100, 300});
  final DefaultTableModel tech = model("분류", "탐지 환경", "근거 수준", "관찰 근거"),
      paths = model("URL", "추천 근거", "진단 항목", "상태");
  final JTable
      pathTable =
          new JTable(paths) {
            public String getToolTipText(java.awt.event.MouseEvent e) {
              int view = rowAtPoint(e.getPoint());
              if (view < 0) return null;
              int row = convertRowIndexToModel(view);
              if (row >= candidates.size()) return null;
              var c = candidates.get(row);
              return c.source()
                  + " · "
                  + c.condition()
                  + " · "
                  + WorkbenchScope.reason(api, c.url());
            }
          },
      technologies = new JTable(tech);
  String category;
  boolean refreshing;
  final JLabel selection = new JLabel("전체 환경");
  final JCheckBox formatted = new JCheckBox("요청·응답 정렬", true);
  List<EnvironmentCatalog.Candidate> candidates = List.of();
  final JLabel status = new JLabel("SCOPE 내 응답 분석 · GET 확인 시 후보 설정의 계정으로 검증합니다.");
  final JComboBox<String> account =
      new JComboBox<>(new String[] {"비로그인", "비교 계정 1", "비교 계정 2", "비교 계정 3"});

  interface SessionProvider {
    Map<String, String> headers(int slot, String url);
  }

  SessionProvider sessions =
      (slot, url) -> {
        throw new IllegalArgumentException("권한 비교에서 계정을 지정하세요.");
      };
  IntFunction<CookieSessionProbe.Config> sessionConfig = slot -> null;
  EnvironmentProject project = new EnvironmentProject();
  final Map<String,String> packetAccounts = new HashMap<>();
  final JSpinner delay = new JSpinner(new SpinnerNumberModel(200,0,10000,100)),
      timeout = new JSpinner(new SpinnerNumberModel(10,1,30,1)),
      budget = new JSpinner(new SpinnerNumberModel(150,1,500,10)),
      depth = new JSpinner(new SpinnerNumberModel(1,0,3,1));
  final JCheckBox follow = new JCheckBox("확인한 응답의 경로도 탐색", true);
  final JButton pause = new JButton("일시정지");
  final JMenuItem remaining = new JMenuItem("남은 경로 이어서 확인");
  final TargetWindow resultDetails = new TargetWindow(this,"서버·경로 탐색 · 판정 근거",this::evidenceContent);
  final TargetWindow changes = new TargetWindow(this,"서버·경로 탐색 · 변경 기록",this::changesContent);
  volatile EnvironmentScan scan;
  boolean ioBusy;
  final javax.swing.Timer scanTimer = new javax.swing.Timer(250,e -> scanStatus());
  final MontoyaApi api;
  final JTextArea requestView = message(), responseView = message();
  final JButton start = new JButton("후보 목록 GET 확인"),
      single = new JButton("선택 경로 GET 확인"),
      stop = new JButton("중지");
  final LinkedHashMap<String, Packet> packets = new LinkedHashMap<>();
  final AtomicBoolean stopping = new AtomicBoolean(), closed = new AtomicBoolean();
  SwingWorker<Void, Packet> job;

  record Packet(String url, String request, String response, String state, String decoded,
      EnvironmentScan.Result result,String account) {
    Packet(String url,String request,String response,String state){this(url,request,response,state,response,null,"anonymous");}
    Packet(String url,String request,String response,String state,String decoded){this(url,request,response,state,decoded,null,"anonymous");}
  }

  static DefaultTableModel model(String... c) {
    return new DefaultTableModel(c, 0) {
      public boolean isCellEditable(int r, int col) {
        return false;
      }
    };
  }

  static JTextArea message() {
    JTextArea a = new JTextArea();
    a.putClientProperty("wb.packet.syntax",true);
    a.setEditable(false);
    return a;
  }

  public EnvironmentPanel(MontoyaApi api) {
    super(new BorderLayout(8, 8));
    this.api = api;
    targetSettings =
        new TargetWindow(
            this,
            "서버·경로 탐색 · 대상 설정",
            () ->
                TargetWindow.options(
                    TargetWindow.scopes(
                        api,
                        url -> {
                          String origin = EnvironmentCatalog.origin(java.net.URI.create(url));
                          boolean observed = false;
                          for (int i = 0; i < origins.getItemCount(); i++)
                            if (origin.equals(origins.getItemAt(i))) observed = true;
                          if (observed) origins.setSelectedItem(origin);
                          else status.setText("등록된 대상의 응답을 요청·응답 목록에서 먼저 관찰하세요.");
                        }),
                    WorkbenchUi.row(targetSummary),
                    new JLabel("SCOPE 내 관찰된 응답을 기준으로 환경 후보를 구성합니다.")));
    setName("wb.environment");
    setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
    limit.setSelectedItem(100);
    limit.setPreferredSize(new Dimension(90,32));
    origins.setPreferredSize(new Dimension(260, 34));
    origins.setName("wb.environment.origin");account.setName("wb.environment.account");
    account.setPreferredSize(new Dimension(140,30));
    JMenuItem send = new JMenuItem("선택 URL → 비로그인 GET Repeater");
    stop.setEnabled(false);
    pause.setEnabled(false);
    remaining.setEnabled(false);
    JButton settings = new JButton("후보 설정"), more = new JButton("더보기"), all = new JButton("전체 환경");
    single.setName("wb.environment.single");start.setName("wb.environment.start");
    pause.setName("wb.environment.pause");stop.setName("wb.environment.stop");
    settings.setName("wb.environment.candidate.settings");more.setName("wb.environment.more");all.setName("wb.environment.all");
    JPanel toolbar = new JPanel();
    toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));
    JPanel context=CompactWorkbenchUi.toolbar(WorkbenchUi.row(new JLabel("대상"),origins,new JLabel("계정"),account),WorkbenchUi.row(targetSettings.button()));
    context.setName("wb.actions.environment.context");toolbar.add(context);

    WorkbenchUi.primary(single);
    start.setToolTipText("전체 후보를 확인합니다. 실제 GET 요청을 전송합니다.");
    add(
        WorkbenchUi.header(
            FeatureGuide.title("environment"), "서버 환경 관찰 → 경로 후보 선택 → GET 응답 확인", toolbar),
        BorderLayout.NORTH);
    settings.addActionListener(
        e -> {
          if (job == null) candidateSettings.open();
        });
    JPopupMenu menu = new JPopupMenu();
    JMenuItem copyItem = new JMenuItem("URL 목록 복사"), clearItem = new JMenuItem("분석 초기화");
    JMenuItem saveProject = new JMenuItem("탐색 결과 저장"), loadProject = new JMenuItem("탐색 결과 불러오기"), history = new JMenuItem("변경 기록");
    menu.add(remaining);menu.add(send);menu.add(history);menu.add(saveProject);menu.add(loadProject);
    remaining.addActionListener(e->resumeRemaining());history.addActionListener(e->changes.open());
    saveProject.addActionListener(e->projectFile(false));loadProject.addActionListener(e->projectFile(true));
    menu.add(copyItem);
    menu.add(clearItem);
    more.addActionListener(e -> menu.show(more, 0, more.getHeight()));
    technologies.setAutoCreateRowSorter(true);
    technologies.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    technologies.setName("wb.environment.technologies");
    pathTable.setAutoCreateRowSorter(true);
    pathTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
    technologies.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
    int[] techWidths = {120, 180, 130, 350};
    for (int i = 0; i < 4; i++)
      technologies.getColumnModel().getColumn(i).setPreferredWidth(techWidths[i]);
    pathTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    pathTable.setName("wb.environment.paths");
    technologies
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (refreshing || e.getValueIsAdjusting()) return;
              int row = technologies.getSelectedRow();
              category =
                  row < 0
                      ? null
                      : tech.getValueAt(technologies.convertRowIndexToModel(row), 0).toString();
              refresh();
            });
    all.addActionListener(
        e -> {
          category = null;
          refresh();
        });
    JPanel observations = CompactWorkbenchUi.disclosure("wb.environment.observations", "관찰 환경", new JScrollPane(technologies), false);
    observations.getComponent(1).setPreferredSize(new Dimension(600, 100));
    Component observationsToggle=observations.getComponent(0);observations.remove(observationsToggle);
    JPanel observationHeader=CompactWorkbenchUi.toolbar(observationsToggle,WorkbenchUi.row(selection,all));
    observationHeader.setName("wb.actions.environment.filters");observations.add(observationHeader,BorderLayout.NORTH);
    JPanel lists = new JPanel(new BorderLayout(0, 4));
    lists.setName("wb.environment.candidates");
    lists.add(observations, BorderLayout.NORTH);
    JPanel candidatePanel=candidatePane();
    JTextField search=CompactWorkbenchUi.search(pathTable,"wb.environment.search","경로·상태·근거 검색");
    JPanel execution=WorkbenchUi.row(single,start,pause,stop);execution.setName("wb.actions.environment.execution");
    JPanel candidateActions=WorkbenchUi.row(settings,more);candidateActions.setName("wb.actions.environment.candidate.settings");
    JPanel searchAndSettings=new JPanel(new BorderLayout(8,0));searchAndSettings.add(search);searchAndSettings.add(candidateActions,BorderLayout.EAST);
    JPanel commands=CompactWorkbenchUi.toolbar(searchAndSettings,execution);commands.setName("wb.actions.environment.candidates");
    candidatePanel.add(commands,BorderLayout.NORTH);lists.add(candidatePanel);
    JSplitPane messages = WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT,
        WorkbenchUi.card("요청", packetTabs(requestView)),
        WorkbenchUi.card("응답", responsePane()), .5);
    messages.setName("wb.environment.messages");
    JSplitPane workspace = WorkbenchUi.split(JSplitPane.VERTICAL_SPLIT, lists, messages, .4);
    workspace.setName("wb.environment.workspace");
    add(workspace);
    add(status, BorderLayout.SOUTH);
    WorkbenchUi.style(this);
    WorkbenchUi.status(status);
    WorkbenchUi.feedback(status);
    int[] widths = {440, 210, 300, 190};
    for (int i = 0; i < widths.length; i++)
      pathTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    pathTable.moveColumn(3, 0);CompactWorkbenchUi.columns(pathTable,110,480,250,300);
    pathTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!refreshing && !e.getValueIsAdjusting()) showSelected();
            });
    account.addActionListener(e->refresh());
    limit.addActionListener(e -> refresh());
    common.addActionListener(e -> refresh());
    origins.addActionListener(e -> refresh());
    prefix.addActionListener(e -> refresh());
    clearItem.addActionListener(e -> clearAnalysis());
    copyItem.addActionListener(
        e ->
            Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(
                    new StringSelection(
                        String.join(
                            "\n",
                            candidates.stream().map(EnvironmentCatalog.Candidate::url).toList())),
                    null));
    send.addActionListener(
        e -> {
          try {
            for (int view : pathTable.getSelectedRows()) {
              var c = candidates.get(pathTable.convertRowIndexToModel(view));
              HttpRequest r = HttpRequest.httpRequestFromUrl(c.url());
              if (!WorkbenchScope.contains(api, r))
                throw new IllegalArgumentException("Scope 밖 URL입니다: " + c.url());
              api.repeater().sendToRepeater(r, "환경 경로");
            }
            status.setText("Repeater에 준비했습니다. 전송은 Repeater에서 실행하세요.");
          } catch (Exception ex) {
            Diagnostics.failure(EnvironmentPanel.this,"서버·경로 탐색",ex);
            status.setText(ex.getMessage());
          }
        });
    single.addActionListener(e -> startOne());
    start.setToolTipText("목록의 후보를 실제 GET 요청으로 확인합니다. 없는 경로 기준 응답도 설정에 따라 추가 요청합니다.");
    single.setToolTipText("선택한 경로를 GET으로 확인합니다. 후보 하나에도 기준 응답을 위한 추가 요청이 생길 수 있습니다.");
    start.addActionListener(e -> start());
    formatted.addActionListener(e -> showSelected());
    stop.addActionListener(e -> stop());
    pause.addActionListener(e->togglePause());
    pathTable.addMouseListener(new java.awt.event.MouseAdapter(){public void mouseClicked(java.awt.event.MouseEvent e){if(e.getClickCount()==2&&pathTable.rowAtPoint(e.getPoint())>=0)resultDetails.open();}});
    showSelected();
  }

  String selectedUrl() {
    int view = pathTable.getSelectedRow();
    if (view < 0) return null;
    int row = pathTable.convertRowIndexToModel(view);
    return row < candidates.size() ? candidates.get(row).url() : null;
  }

  static String preview(String raw) {
    return raw.length() > 65536 ? raw.substring(0, 65536) + "\n[미리보기 64K자 제한]" : raw;
  }

  private boolean previewReady,previewFormatted;
  private String previewUrl,previewAccount,displayedAccount;
  private Packet previewPacket;
  private EnvironmentProject.Entry previewSaved;

  void showSelected() {
    if(refreshing)return;
    String url=selectedUrl(),owner=accountKey();Packet packet=url==null?null:currentPacket(url);
    var saved=url==null?null:project.latest.get(owner+" GET "+url);
    boolean pretty=formatted.isSelected();
    if(previewReady&&Objects.equals(previewUrl,url)&&Objects.equals(previewAccount,owner)
        &&previewPacket==packet&&Objects.equals(previewSaved,saved)&&previewFormatted==pretty)return;
    if(url==null){
      requestView.setText("후보 경로를 선택하세요.");
      responseView.setText("검증 전 응답은 없습니다. 시작 버튼으로 검증하세요.");
    }else if(packet!=null){
      requestView.setText(pretty?PacketFormat.pretty(packet.request):packet.request);
      responseView.setText(pretty?PacketFormat.pretty(packet.decoded):packet.response);
    }else{
      try{
        String request=preview(HttpRequest.httpRequestFromUrl(url).toString());
        requestView.setText(pretty?PacketFormat.pretty(request):request);
      }catch(Exception error){requestView.setText("GET "+url+"\n[검증 전 요청 미리보기]");}
      responseView.setText(saved==null?"미검증 · 시작 버튼으로 응답을 수집하세요.":"저장된 판정 · HTTP "+saved.status()+" · "+EnvironmentContent.title(saved.kind())+" · "+saved.change()+"\n원본 패킷은 저장하지 않습니다. 다시 GET 확인하면 현재 응답을 볼 수 있습니다.");
    }
    requestView.setCaretPosition(0);responseView.setCaretPosition(0);
    previewReady=true;previewUrl=url;previewAccount=owner;previewPacket=packet;previewSaved=saved;previewFormatted=pretty;
  }

  final AtomicLong observationEpoch = new AtomicLong();

  record Observation(EnvironmentCatalog.Site site, long generation) {}

  void clearAnalysis() {
    if (job != null||ioBusy) {
      status.setText("중지 후 검증 종료를 기다려주세요.");
      return;
    }
    observationEpoch.incrementAndGet();
    catalog.sites.clear();
    packets.clear();packetAccounts.clear();project=new EnvironmentProject();remaining.setEnabled(false);
    origins.removeAllItems();
    refresh();
  }

  /** Decode and fingerprint without touching Swing or the UI-owned catalog. */
  Observation prepareObservation(HttpRequest request, HttpResponse response) {
    if (closed.get() || response == null || !WorkbenchScope.contains(api, request)) return null;
    long generation = observationEpoch.get();
    try {
      return new Observation(
          new EnvironmentCatalog()
              .observe(
                  request.url(),
                  responseHeaders(response),
                  decodedBody(PacketFormat.decodedResponse(response))),
          generation);
    } catch (Exception ignored) {
      return null;
    }
  }

  /** Merge a bounded metadata result on the EDT; no HTTP packets or bodies are retained. */
  void applyObservation(Observation observation) {
    if (closed.get() || observation == null || observation.generation() != observationEpoch.get())
      return;
    EnvironmentCatalog.Site observed=observation.site();
    EnvironmentCatalog.Site site=catalog.sites.get(observed.origin);boolean changed=false,selectedRemoved=false;
    if(site==null){
      if(catalog.sites.size()>=50){String retired=catalog.sites.keySet().iterator().next();catalog.sites.remove(retired);selectedRemoved=Objects.equals(retired,origins.getSelectedItem());}
      site=new EnvironmentCatalog.Site(observed.origin);catalog.sites.put(site.origin,site);changed=true;
    }
    for(var entry:observed.detections.entrySet())if(site.detections.size()<40||site.detections.containsKey(entry.getKey())){
      if(!Objects.equals(site.detections.put(entry.getKey(),entry.getValue()),entry.getValue()))changed=true;
    }
    for(String path:observed.observed)if(site.observed.size()<100)changed|=site.observed.add(path);
    for(String value:observed.prefixes)if(site.prefixes.size()<8)changed|=site.prefixes.add(value);
    for(String value:observed.managementRoots)if(site.managementRoots.size()<8)changed|=site.managementRoots.add(value);
    for(var entry:observed.discovered.entrySet()){
      var old=site.discovered.get(entry.getKey());
      if((old==null&&site.discovered.size()<500)||(old!=null&&old.score()<entry.getValue().score())){
        site.discovered.put(entry.getKey(),entry.getValue());site.associations.put(entry.getKey(),observed.associations.get(entry.getKey()));changed=true;
      }
    }
    boolean found=false;for(int i=0;i<origins.getItemCount();i++)if(origins.getItemAt(i).equals(site.origin))found=true;
    if(!found){
      boolean previous=refreshing;refreshing=true;
      try{origins.addItem(site.origin);if(origins.getSelectedItem()==null)origins.setSelectedItem(site.origin);}
      finally{refreshing=previous;}
    }
    if(selectedRemoved||!Objects.equals(displayedAccount,accountKey())
        ||(changed&&Objects.equals(origins.getSelectedItem(),site.origin)))refresh();
  }

  public void observe(HttpRequest request, HttpResponse response) {
    applyObservation(prepareObservation(request, response));
  }

  /** Only changed data produces a table event; column order, renderers and sort keys stay intact. */
  static boolean replaceRows(DefaultTableModel model,List<Object[]> rows){
    boolean equal=model.getRowCount()==rows.size();
    if(equal)for(int row=0;row<rows.size()&&equal;row++)for(int column=0;column<model.getColumnCount();column++)
      if(!Objects.equals(model.getValueAt(row,column),rows.get(row)[column])){equal=false;break;}
    if(equal)return false;
    var data=model.getDataVector();data.clear();
    for(Object[] row:rows)data.add(new Vector<>(Arrays.asList(row)));
    model.fireTableDataChanged();return true;
  }

  void refresh() {
    if(refreshing)return;
    TargetWindow.show(targetSummary,Objects.toString(origins.getSelectedItem(),""),"대상 · 트래픽에서 선택하세요");
    String selected=selectedUrl(),owner=accountKey();refreshing=true;
    try{
      var site=catalog.sites.get(origins.getSelectedItem());
      List<Object[]> technologiesRows=new ArrayList<>(),pathRows=new ArrayList<>();
      if(site==null){
        category=null;candidates=List.of();replaceRows(tech,technologiesRows);replaceRows(paths,pathRows);
        technologies.clearSelection();pathTable.clearSelection();selection.setText("전체 환경");return;
      }
      for(var detection:site.detections.values())technologiesRows.add(new Object[]{detection.category(),detection.name(),detection.confidence(),detection.evidence()});
      replaceRows(tech,technologiesRows);
      if(category!=null&&site.detections.values().stream().noneMatch(d->d.category().equals(category)))category=null;
      if(category==null)technologies.clearSelection();
      else for(int i=0;i<tech.getRowCount();i++)if(tech.getValueAt(i,0).equals(category)){
        int view=technologies.convertRowIndexToView(i);if(view>=0)technologies.setRowSelectionInterval(view,view);break;
      }
      selection.setText(category==null?"전체 환경":category+" 관련 후보");
      EnvironmentCatalog.Site filtered=site;
      if(category!=null){
        filtered=new EnvironmentCatalog.Site(site.origin);
        for(var entry:site.detections.entrySet())if(entry.getValue().category().equals(category))filtered.detections.put(entry.getKey(),entry.getValue());
        filtered.prefixes.addAll(site.prefixes);filtered.feedback.putAll(site.feedback);
        if(filtered.detections.containsKey("Spring Boot / Actuator"))filtered.managementRoots.addAll(site.managementRoots);
        for(var entry:site.discovered.entrySet())if(category.equals(site.associations.get(entry.getKey()))){filtered.discovered.put(entry.getKey(),entry.getValue());filtered.associations.put(entry.getKey(),category);}
      }
      candidates=catalog.candidates(filtered,prefix.getText(),category==null&&common.isSelected(),(Integer)limit.getSelectedItem());
      for(var candidate:candidates){
        Packet packet=currentPacket(candidate.url());var saved=project.latest.get(owner+" GET "+candidate.url());
        String state=packet!=null?packet.state:saved!=null?"HTTP "+saved.status()+" · "+EnvironmentContent.title(saved.kind())+" · "+saved.change():EnvironmentKnowledge.manualOnly(candidate.url())?"단일 검토":"미검증";
        pathRows.add(new Object[]{candidate.url(),candidate.source(),candidate.purpose(),state});
      }
      replaceRows(paths,pathRows);
      int selectedView=-1;
      if(selected!=null)for(int i=0;i<candidates.size();i++)if(candidates.get(i).url().equals(selected)){selectedView=pathTable.convertRowIndexToView(i);break;}
      if(selectedView>=0)pathTable.setRowSelectionInterval(selectedView,selectedView);else pathTable.clearSelection();
      if(job==null)status.setText("환경 "+site.detections.size()+" · 후보 URL "+candidates.size()+" · 응답 근거 우선 · 존재/취약점 미확인");
    }catch(Exception error){
      candidates=List.of();replaceRows(paths,List.of());pathTable.clearSelection();
      Diagnostics.failure(EnvironmentPanel.this,"서버·경로 탐색",error);status.setText(error.getMessage());
    }finally{displayedAccount=owner;refreshing=false;showSelected();}
  }

  JPanel settingsContent() {
    JButton words = new JButton("경로 목록 가져오기");
    words.addActionListener(
        e -> {
          if (job == null) importWords();
        });
    JButton apply = new JButton("설정 확인");
    apply.addActionListener(
        e -> {
          try {
            AuthJudgement.rules(volatileFields.getText(), "", "");
            if (job == null) refresh();
          } catch (Exception ex) {
            Diagnostics.failure(EnvironmentPanel.this,"서버·경로 탐색",ex);
            status.setText(ex.getMessage());
          }
        });
    return TargetWindow.options(
        new JLabel("계정은 메인 화면에서 선택 · 캡처된 세션 갱신은 계정 비교에서 확인"),
        WorkbenchUi.row(new JLabel("앱 접두 경로"), prefix, new JLabel("최대 후보 수"), limit, common),
        WorkbenchUi.row(compareMissing, repeatMissing, unverifiedOnly),
        WorkbenchUi.row(new JLabel("JSON 동적 필드 제외"), volatileFields),
        new JLabel("예: $.requestId, $.meta.timestamp · 2회 기준 비교는 추가 GET 1건을 보냅니다."),
        WorkbenchUi.row(new JLabel("요청 간격(ms)"),delay,new JLabel("타임아웃(초)"),timeout),
        WorkbenchUi.row(new JLabel("전체 요청 한도"),budget,new JLabel("후속 깊이"),depth),
        WorkbenchUi.row(follow),
        new JLabel("기준·신원 확인도 요청 한도에 포함 · 로그인 계정은 권한 비교의 세션 검사 적용 필요"),
        new JLabel("결과 저장은 판정·변경 이력·남은 경로만 포함 · 인증값·응답 본문 제외"),
        WorkbenchUi.row(words, apply));
  }

  JPanel candidatePane() {
    JPanel p = new JPanel(new BorderLayout(0, 4));
    p.add(selection, BorderLayout.NORTH);
    p.add(new JScrollPane(pathTable));
    return p;
  }

  JPanel packetTabs(JTextArea area){return packetTabs(area,null);}
  JPanel packetTabs(JTextArea area,JComponent action){JPanel p=new JPanel(new BorderLayout());JToggleButton raw=ReferenceUi.tab("Raw"),pretty=ReferenceUi.tab("JSON");ButtonGroup group=new ButtonGroup();group.add(raw);group.add(pretty);pretty.setSelected(formatted.isSelected());raw.setSelected(!formatted.isSelected());raw.addActionListener(e->{if(formatted.isSelected())formatted.doClick();});pretty.addActionListener(e->{if(!formatted.isSelected())formatted.doClick();});formatted.addItemListener(e->{pretty.setSelected(formatted.isSelected());raw.setSelected(!formatted.isSelected());});p.add(ReferenceUi.header(ReferenceUi.stepRow(raw,pretty),action==null?null:WorkbenchUi.row(action)),BorderLayout.NORTH);p.add(new JScrollPane(area));return p;}

  JPanel responsePane() {
    JPanel p = new JPanel(new BorderLayout(0, 4));
    JButton evidence=new JButton("판정 근거");evidence.addActionListener(e->resultDetails.open());
    responseView.setLineWrap(true);
    responseView.setWrapStyleWord(false);
    p.add(packetTabs(responseView,evidence));
    return p;
  }

  void startOne() {
    String url = selectedUrl();
    if (url == null) {
      status.setText("검증할 후보 1건을 선택하세요.");
      return;
    }
    runUrls(List.of(url));
  }

  void start() {
    if (candidates.isEmpty()) {
      status.setText("검증할 후보가 없습니다.");
      return;
    }
    var urls =
        candidates.stream()
            .map(EnvironmentCatalog.Candidate::url)
            .distinct()
            .filter(u -> !EnvironmentKnowledge.manualOnly(u))
            .filter(u -> !unverifiedOnly.isSelected() || !verified(u))
            .toList();
    if (urls.isEmpty()) {
      status.setText("일괄 검증할 미검증 후보가 없습니다. 단일 시작으로 다시 확인할 수 있습니다.");
      return;
    }
    runUrls(urls);
  }

  void runUrls(List<String> urls) {try{runTasks(urls.stream().map(u->new EnvironmentProject.Pending(u,0)).toList());}catch(Exception e){status.setText(e.getMessage());}}

  String accountKey(){int slot=account.getSelectedIndex()-1;if(slot<0)return "anonymous";var config=sessionConfig.apply(slot);return "slot-"+slot+(config==null?"":"-"+EnvironmentProject.hash(config.identityPath()+"\n"+config.expectedIdentity()+"\n"+config.claims()).substring(0,16));}
  Packet currentPacket(String url){Packet packet=packets.get(url);return packet!=null&&(!packetAccounts.containsKey(url)||accountKey().equals(packetAccounts.get(url)))?packet:null;}
  boolean verified(String url){var e=project.latest.get(accountKey()+" GET "+url);return e!=null?e.status()!=0&&e.status()!=429:currentPacket(url)!=null;}
  EnvironmentScan.Profile profile(String url){
    int slot=account.getSelectedIndex()-1;
    if(slot<0)return new EnvironmentScan.Profile("anonymous",Map.of(),null);
    var config=sessionConfig.apply(slot);
    if(config==null)throw new IllegalArgumentException("권한 비교 → 세션 검사에서 신원 조건을 적용한 뒤 실행하세요.");
    if(!EnvironmentCatalog.origin(java.net.URI.create(config.url())).equals(EnvironmentCatalog.origin(java.net.URI.create(url))))throw new IllegalArgumentException("신원 검사 URL과 대상 Origin이 다릅니다.");
    Map<String,String> headers=Map.copyOf(sessions.headers(slot,url));if(headers.isEmpty())throw new IllegalArgumentException("로그인 계정의 인증 정보가 없습니다.");
    return new EnvironmentScan.Profile(accountKey(),headers,config);
  }
  void runTasks(List<EnvironmentProject.Pending> tasks){
    if(closed.get()||job!=null||ioBusy||tasks.isEmpty())return;
    try{
      for(var task:tasks)if(!WorkbenchScope.contains(api,HttpRequest.httpRequestFromUrl(task.url())))throw new IllegalArgumentException("SCOPE 밖 대기 경로 · 범위를 확인하세요.");
      var profile=profile(tasks.get(0).url());
      var ignored=AuthJudgement.rules(volatileFields.getText(),"","").ignored();
      String app=EnvironmentCatalog.prefix(prefix.getText());var site=catalog.sites.get(EnvironmentCatalog.origin(java.net.URI.create(tasks.get(0).url())));
      var options=new EnvironmentScan.Options(compareMissing.isSelected(),repeatMissing.isSelected(),ignored,(Integer)delay.getValue(),1000*(Integer)timeout.getValue(),(Integer)budget.getValue(),(Integer)depth.getValue(),follow.isSelected(),true,app,site==null?List.of():List.copyOf(site.prefixes));
      stopping.set(false);
      job=new SwingWorker<>(){
        EnvironmentScan worker=new EnvironmentScan(api,options,profile,tasks,r->publish(new Packet(r.url(),r.request(),r.response(),r.state(),r.decoded(),r,profile.account())),()->closed.get()||stopping.get());
        {scan=worker;}
        protected Void doInBackground(){worker.run();return null;}
        protected void process(List<Packet> updates){
          if(closed.get())return;
          for(Packet packet:updates){
            String change=project.add(packet.url,packet.account,packet.result.status(),packet.result.finding().kind(),packet.result.fingerprint());
            var updated=new Packet(packet.url,packet.request,packet.response,packet.state+" · "+change,packet.decoded,packet.result,packet.account);
            if(packets.size()>=300&&!packets.containsKey(packet.url)){String first=packets.keySet().iterator().next();packets.remove(first);packetAccounts.remove(first);}
            packets.put(packet.url,updated);packetAccounts.put(packet.url,packet.account);project.candidate(packet.url,"검증한 경로");
            if(packet.result.learned()!=null&&(!packet.result.learned().detections.isEmpty()||!packet.result.learned().observed.isEmpty()||!packet.result.learned().discovered.isEmpty()))applyObservation(new Observation(packet.result.learned(),observationEpoch.get()));
            if(packet.result.status()>0)catalog.feedback(packet.url,packet.result.status(),packet.state);
            for(int i=0;i<candidates.size();i++)if(candidates.get(i).url().equals(packet.url))paths.setValueAt(updated.state,i,3);
          }
          showSelected();scanStatus();
        }
        protected void done(){
          job=null;scanTimer.stop();
          if(closed.get())return;
          try{get();}catch(Exception error){Diagnostics.failure(EnvironmentPanel.this,"서버·경로 탐색",error);worker.note="작업 오류 · 남은 경로 유지";}
          project.pending=worker.remaining();project.pendingAccount=profile.account();setRunning(false);if(catalog.sites.containsKey(origins.getSelectedItem()))refresh();
          status.setText(worker.note+" · 경로 "+worker.completed+" · 요청 "+worker.sent+" / "+options.budget()+" · 남음 "+project.pending.size());scan=null;
        }
      };
      setRunning(true);scanTimer.start();job.execute();
    }catch(Exception error){Diagnostics.failure(this,"서버·경로 탐색",error);status.setText(error.getMessage());}
  }
  void setRunning(boolean running){
    start.setEnabled(!running&&!ioBusy);single.setEnabled(!running&&!ioBusy);stop.setEnabled(running);pause.setEnabled(running);pause.setText("일시정지");
    for(JComponent c:List.of(technologies,origins,prefix,limit,common,repeatMissing,volatileFields,compareMissing,account,delay,timeout,budget,depth,follow))c.setEnabled(!running&&!ioBusy);
    remaining.setEnabled(!running&&!ioBusy&&!project.pending.isEmpty());
  }
  void scanStatus(){var worker=scan;if(worker==null)return;pause.setText(worker.paused?"재개":"일시정지");status.setText((worker.paused?"일시정지 · "+worker.note:"검증 중")+" · 경로 "+worker.completed+" · 요청 "+worker.sent+" · 남음 "+worker.queue.size());}
  void togglePause(){var worker=scan;if(worker==null)return;try{if(worker.paused)worker.resume(profile(worker.queue.peek().url()));else worker.pause("사용자 일시정지",0);scanStatus();}catch(Exception error){status.setText(error.getMessage());}}
  void resumeRemaining(){if(!project.pendingAccount.equals(accountKey())){status.setText("저장 당시 계정으로 세션을 연결하고 재개하세요.");return;}runTasks(project.pending);}
  JPanel evidenceContent(){
    String url=selectedUrl();Packet packet=url==null?null:currentPacket(url);JTextArea text=message();text.setLineWrap(true);text.setWrapStyleWord(true);
    if(packet!=null&&packet.result!=null){var f=packet.result.finding();text.setText("결과: "+f.title()+"\n\n판단 근거: "+f.reason()+"\n\n비교: "+packet.state+"\n\n다음 확인: "+f.next()+"\n\nURL: "+url);}
    else{var saved=url==null?null:project.latest.get(accountKey()+" GET "+url);text.setText(saved==null?"검사한 경로를 선택하세요.":"저장된 판정: "+EnvironmentContent.title(saved.kind())+" · HTTP "+saved.status()+"\n변경: "+saved.change()+"\n검사 시각: "+java.time.Instant.ofEpochMilli(saved.time())+"\n원본 패킷은 저장하지 않습니다. 현재 상태는 다시 검사하세요.");}
    JPanel body=new JPanel(new BorderLayout());body.add(new JScrollPane(text));body.setPreferredSize(new Dimension(700,420));return body;
  }
  static String accountName(String key){return key.equals("anonymous")?"비로그인":"비교 계정 "+(Character.digit(key.charAt(5),10)+1);}
  JPanel changesContent(){
    var model=model("검사 시각","계정","HTTP","내용 분류","변경","URL");
    var clock=java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(java.time.ZoneId.systemDefault());
    for(var e:project.history)model.addRow(new Object[]{clock.format(java.time.Instant.ofEpochMilli(e.time())),accountName(e.account()),e.status(),EnvironmentContent.title(e.kind()),e.change(),e.url()});
    JTable table=new JTable(model);table.setAutoCreateRowSorter(true);table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    int[] widths={125,110,65,140,100,400};for(int i=0;i<widths.length;i++)table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    JPanel body=new JPanel(new BorderLayout());body.add(new JScrollPane(table));body.setPreferredSize(new Dimension(900,500));return body;
  }

  EnvironmentProject projectSnapshot(){
    EnvironmentProject copy=EnvironmentProject.parse(project.json());
    for(var site:catalog.sites.values())for(var c:catalog.candidates(site,"",false,300))copy.candidate(c.url(),c.source());
    if(scan!=null){copy.pending=scan.remaining();copy.pendingAccount=scan.profile.account();}return copy;
  }
  void applyProject(EnvironmentProject loaded){
    if(job!=null)throw new IllegalArgumentException("중지 후 불러오세요.");
    observationEpoch.incrementAndGet();project=loaded;packets.clear();packetAccounts.clear();catalog.sites.clear();origins.removeAllItems();
    LinkedHashMap<String,String> urls=new LinkedHashMap<>();loaded.candidates.values().forEach(c->urls.put(c.url(),c.source()));loaded.latest.values().forEach(e->urls.putIfAbsent(e.url(),"저장된 검사 결과"));loaded.pending.forEach(t->urls.putIfAbsent(t.url(),"남은 경로"));
    for(var e:urls.entrySet()){String origin=EnvironmentCatalog.origin(java.net.URI.create(e.getKey()));var site=catalog.sites.get(origin);if(site==null){if(catalog.sites.size()>=50)continue;site=new EnvironmentCatalog.Site(origin);catalog.sites.put(origin,site);}if(site.discovered.size()<500)site.discovered.put(e.getKey(),new EnvironmentCatalog.Candidate(e.getKey(),e.getValue(),EnvironmentCatalog.purpose(e.getKey()),90,"저장한 메타데이터 · 재검증 필요"));}
    catalog.sites.keySet().forEach(origins::addItem);remaining.setEnabled(!loaded.pending.isEmpty());refresh();
  }
  void projectFile(boolean load){
    if(ioBusy||job!=null){status.setText("검증을 중지한 뒤 저장·불러오기를 실행하세요.");return;}
    JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("서버경로_탐색.json"));int choice=load?chooser.showOpenDialog(this):chooser.showSaveDialog(this);if(choice!=JFileChooser.APPROVE_OPTION)return;
    Path file=chooser.getSelectedFile().toPath();EnvironmentProject snapshot=load?null:projectSnapshot();ioBusy=true;setRunning(false);
    new SwingWorker<EnvironmentProject,Void>(){protected EnvironmentProject doInBackground()throws Exception{if(load)return EnvironmentProject.read(file);snapshot.write(file);return snapshot;}protected void done(){ioBusy=false;try{var result=get();if(!closed.get()){if(load)applyProject(result);status.setText(load?"탐색 결과 불러옴 · 계정 재연결 후 명시적으로 재개하세요.":"탐색 결과 저장 완료 · 인증값·본문 제외");}}catch(Exception error){if(!closed.get())status.setText("저장/불러오기 실패 · 기존 결과 유지");}if(!closed.get())setRunning(false);}}.execute();
  }

  static String responseHeaders(HttpResponse r) {
    return r.headers() == null
        ? ""
        : String.join("\n", r.headers().stream().map(h -> h.name() + ": " + h.value()).toList());
  }

  static String decodedBody(String packet) {
    int p = packet.indexOf("\r\n\r\n");
    if (p >= 0) return packet.substring(p + 4);
    p = packet.indexOf("\n\n");
    return p >= 0 ? packet.substring(p + 2) : packet;
  }

  String baselineRoot(String url) {
    java.net.URI uri = java.net.URI.create(url);
    String origin = EnvironmentCatalog.origin(uri),
        app = EnvironmentCatalog.prefix(prefix.getText());
    if (!app.isEmpty() && !uri.getPath().equals(app) && !uri.getPath().startsWith(app + "/"))
      app = "";
    var site = catalog.sites.get(origin);
    if (app.isEmpty() && site != null)
      for (String root : site.prefixes)
        if ((uri.getPath().equals(root) || uri.getPath().startsWith(root + "/"))
            && root.length() > app.length()) app = root;
    return origin + app;
  }

  void importWords() {
    if (job != null) return;
    var site = catalog.sites.get(origins.getSelectedItem());
    if (site == null) {
      status.setText("관찰된 대상을 먼저 선택하세요.");
      return;
    }
    JFileChooser chooser = new JFileChooser();
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    try {
      var file = chooser.getSelectedFile().toPath();
      if (java.nio.file.Files.size(file) > 1024 * 1024)
        throw new IllegalArgumentException("목록 파일은 1MiB 이하로 줄여주세요.");
      var result =
          EnvironmentDiscovery.importWords(
              site, java.nio.file.Files.readString(file), file.getFileName().toString());
      category = "사용자 목록";
      refresh();
      status.setText("가져옴 " + result.accepted() + "개 · 중복/형식/제한 제외 " + result.skipped() + "개");
    } catch (Exception e) {
      Diagnostics.failure(EnvironmentPanel.this,"서버·경로 탐색",e);
      status.setText("목록 가져오기 실패 · " + e.getMessage());
    }
  }

  void stop() {
    stopping.set(true);if(scan!=null)scan.stop();
    status.setText("중지 중 · 이미 전송한 요청은 응답/설정한 타임아웃 후 종료합니다.");
  }

  public void close() {
    targetSettings.close();resultDetails.close();changes.close();scanTimer.stop();if(scan!=null)scan.stop();
    candidateSettings.close();
    closed.set(true);
    stopping.set(true);
    packets.clear();packetAccounts.clear();project=new EnvironmentProject();
    catalog.sites.clear();
    origins.removeAllItems();
    refresh();
  }
}
