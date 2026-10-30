package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.net.URI;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

public final class JsAnalysisPanel extends JPanel implements AutoCloseable {
  final PacketView requestPacket = new PacketView(), responsePacket = new PacketView();
  final JTextArea input = new JTextArea();
  final JTextField sourceUrl = new JTextField(40);
  final JToggleButton passive = new JToggleButton("분석 ON", true);
  final DefaultTableModel
      endpoints = model("URL / 경로", "메서드", "사용 맥락", "쿼리·params 이름", "헤더 이름", "본문 키", "줄", "호출 상태", "변경"),
      secrets = model("시크릿 후보", "값 (가림)", "지문", "줄", "근거");
  final DefaultTableModel historyModel = model("출처 URL", "주요 경로", "민감한 정보", "분석 상태");
  final JTable historyTable = new JTable(historyModel);
  final JTextField historySearch = new JTextField(12);
  final JButton historyPrevious = new JButton("이전 200개"), historyNext = new JButton("다음 200개");
  final JLabel historyPageLabel = new JLabel("200개씩 표시"), archiveUsage = new JLabel("디스크 이력 · 자동 삭제 없음");
  private volatile int historyOffset;
  private volatile String historyQuery = "";
  private volatile JsArchive.Page archivePage = new JsArchive.Page(List.of(),0,0,false);
  private volatile JsArchive.Counts archiveCounts = new JsArchive.Counts(0,0,0,0);
  private volatile long displayedArchiveRevision = -1;
  private final AtomicLong historyQueryGeneration = new AtomicLong();
  private final AtomicBoolean archiveRefreshPending = new AtomicBoolean();
  private int sourceWindowStart;
  private String sourceWindowKey = "";
  final TargetWindow archiveSettings = new TargetWindow(this,"JS 이력 보존·디스크",this::archiveSettingsContent);
  final JTable urlTable = new JTable(endpoints){
    public String getToolTipText(MouseEvent event){int view=rowAtPoint(event.getPoint());if(current==null||view<0)return super.getToolTipText(event);var item=current.endpoints().get(convertRowIndexToModel(view));return "<html>"+item.url().replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")+"<br>"+JsFindingGroups.evidence(current,item).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\n","<br>")+"</html>";}
    @Override public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer,int row,int column){
      Component component=super.prepareRenderer(renderer,row,column);if(component instanceof JLabel label){int col=convertColumnIndexToModel(column);String value=Objects.toString(getValueAt(row,column),"");if(col==0){try{var uri=URI.create(value);if(uri.getHost()!=null)label.setText(uri.getRawPath());}catch(Exception ignored){}label.setToolTipText(value);}if(col==7||col==8){WorkbenchUi.Tone tone=value.equals("실제 호출")||value.equals("동일")?WorkbenchUi.Tone.SUCCESS:value.contains("충돌")?WorkbenchUi.Tone.ERROR:value.equals("신규")||value.contains("발견")?WorkbenchUi.Tone.WARNING:WorkbenchUi.Tone.NEUTRAL;label.setForeground(WorkbenchUi.toneInk(tone));label.setToolTipText(value);}}return component;
    }
  }, secretTable = new JTable(secrets);
  final JLabel status = new JLabel("분석 목록에서 응답을 선택하세요.");
  final Action toRepeater = new AbstractAction("선택 URL → Repeater") {
    public void actionPerformed(ActionEvent event) { prepareRepeater(); }
  };
  final JButton analysisList = new JButton("분석 목록"),
      analysisDetails = new JButton("분석 상세"),
      resolve = new JButton("요청 설정"),
      locate = new JButton("원본 위치");
  private final MontoyaApi api;
  private JsAnalysis.Result current;
  final JsTrafficIndex trafficIndex=new JsTrafficIndex();
  final JsVersions versions=new JsVersions();
  final Map<String,String> versionHashes=new ConcurrentHashMap<>();
  final JsModuleLinks modules=new JsModuleLinks();
  JsResultsView resultView;
  JsAnalysis.Result currentResult(){return current;}
  String currentSourceKey(){return currentSource;}

  java.util.function.BiConsumer<JsAnalysis.Result, String> coverage = (result, source) -> {};
  java.util.function.BiFunction<String,String,burp.api.montoya.http.message.HttpRequestResponse> trafficLookup=(method,url)->null;
  java.util.function.Function<JsAnalysis.Endpoint, String> observation = item -> "발견만 함";
  java.util.function.Function<Collection<String>,Map<String,Set<String>>> observedMethods=urls->Map.of();
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(100),
          r -> {
            Thread t = new Thread(r, "Workbench-JS-AST");
            t.setDaemon(true);
            return t;
          });
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicLong epoch = new AtomicLong();
  private final AtomicBoolean watching = new AtomicBoolean(true);
  private final JsSourceCache cache;
  final JsArchive archive;
  private final Map<String,String> documents=new ConcurrentHashMap<>();
  private final JsWorkQueue jobs;
  final AnalysisRetries retries = new AnalysisRetries();
  final TargetWindow retryWindow = new TargetWindow(this, "분석 처리·재시도", () -> retries.content(this::retryOriginal));
  final AtomicLong truncated = new AtomicLong();
  final AtomicLong processed = new AtomicLong(),
      duplicates = new AtomicLong(),
      excluded = new AtomicLong(),
      unsupported = new AtomicLong(),
      overflow = new AtomicLong(),
      failed = new AtomicLong();
  private JsHistoryMonitor historyMonitor;
  final JLabel progress = new JLabel("History 감시 준비 중");
  private javax.swing.Timer progressTimer;
  final JComboBox<String> sources = new JComboBox<>();
  final DefaultTableModel domainModel = model("호스트", "출처 관계", "클라우드", "줄"),
      cloudModel = model("서비스", "URL · 쿼리 가림", "줄"),
      dependencyModel = model("패키지", "scope", "출처", "조회 결과"),
      mapModel = model("소스맵", "상태"),
      recoveredModel = model("원본 이름", "map 출처", "문자 수");
  final JButton discoverMaps = new JButton("소스맵 탐색 · Scope"),
      checkNpm = new JButton("NPM 존재 조회");
  final JLabel findingCount = new JLabel("0 / 0");
  final JTabbedPane results = new JTabbedPane();
  final JsRepeater repeater;
  ResultTabs resultTabs;
  final JsAnalysisTotals totals=new JsAnalysisTotals();
  final JsFindingNavigator navigator;
  boolean preparingSource, navigating;
  private final TargetWindow targetSettings;
  private JLabel sourceSummary;
  final JTextField registry = new JTextField("https://registry.npmjs.org", 25);
  private volatile String currentSource = "";
  private final AtomicLong selectionGeneration = new AtomicLong();
  private boolean syncingSources;
  final TargetWindow analysisWindow =
      new TargetWindow(this, "분석 목록", this::analysisContent);
  final TargetWindow detailsWindow = new TargetWindow(this,"분석 상세",()->new JsAnalysisDetails(currentSource,current));
  final TargetWindow packetWindow =
      new TargetWindow(this, "발견 출처 · 요청/응답 패킷", this::packetContent);
  private String[] openedPacket;
  private String openedSource = "", openedFinding = "";
  BackgroundJobs backgroundJobs;
  private BackgroundJobs.Handle backgroundBatch;
  private long batchCompleted, batchFailed, batchCancelled, batchAnalysisFailed, batchEpoch;
  private long backgroundBatchSequence;
  private long monitorProjectGeneration, monitorAccepted, monitorCompleted, monitorFailed, monitorRunning, monitorCancelled;
  private final java.util.concurrent.atomic.AtomicBoolean backgroundRefreshPending = new java.util.concurrent.atomic.AtomicBoolean();

  static DefaultTableModel model(String... columns) {
    return new DefaultTableModel(columns, 0) {
      public boolean isCellEditable(int row, int column) {
        return false;
      }
    };
  }

  public JsAnalysisPanel(MontoyaApi api, Runnable selected) {
    this(api, selected, JsSourceCache.DEFAULT_BUDGET, JsWorkQueue.DEFAULT_BUDGET);
  }

  JsAnalysisPanel(MontoyaApi api, Runnable selected, long cacheBytes, long queueBytes) {
    super(new BorderLayout(8, 8));
    this.api = api;
    archive=JsArchive.project(api);
    repeater = new JsRepeater(this, api);
    cache = new JsSourceCache(cacheBytes);
    jobs = new JsWorkQueue(worker, queueBytes);
    jobs.onChange = this::scheduleBackgroundRefresh;
    targetSettings =
        new TargetWindow(
            this,
            "JS 추출 · 대상 설정",
            () ->
                TargetWindow.options(
                    TargetWindow.scopes(api, sourceUrl::setText),
                    WorkbenchUi.row(new JLabel("기준 / 출처 URL"), sourceUrl),
                    new JLabel("상대 경로 해석에 사용 · 트래픽에서 가져오면 자동 연결됩니다.")));
    sourceSummary = TargetWindow.bind(sourceUrl, "출처 · 직접 입력");
    navigator =
        new JsFindingNavigator(input, findingCount, this::selectFinding, this::prepareNavigation);
    setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    input.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
    input.setTabSize(2);
    input.putClientProperty("wb.packet.syntax",true);input.setEditable(false);
    passive.setName("wb.js.analysis.toggle");
    passive.putClientProperty("wb.state.toggle", true);
    passive.putClientProperty("wb.analysis.lamp", true);
    passive.setToolTipText("SCOPE 수집으로 들어온 텍스트 응답과 직접 선택한 원본을 분석합니다. OFF는 새 분석과 대기 작업을 중지합니다.");
    passive.addItemListener(e -> {
      boolean enabled = passive.isSelected();
      watching.set(enabled);
      passive.setText(enabled ? "분석 ON" : "분석 OFF");
      if (!enabled) {
        epoch.incrementAndGet();
        jobs.cancelQueued();archiveRefreshPending.set(false);
        retries.cancelPending();
      } else if (historyMonitor != null) historyMonitor.rescan();
      WorkbenchUi.style(passive);
      refreshProgress();
    });
    analysisList.setName("wb.js.analysis.list");
    analysisList.addActionListener(e -> {requestArchivePage();analysisWindow.open();});
    totals.setVisible(false);
    analysisDetails.setName("wb.js.analysis.details");
    detailsWindow.opener=JsAnalysisDetails::window;
    analysisDetails.addActionListener(e->{detailsWindow.closeWindow();detailsWindow.open();});
    JPanel header = new JPanel(new BorderLayout());
    add(header, BorderLayout.NORTH);
    urlTable.setName("wb.js.url.table");
    secretTable.setName("wb.js.secret.table");
    historyTable.setName("wb.js.history.table");
    historySearch.setName("wb.js.history.search");historyPrevious.setName("wb.js.history.previous");historyNext.setName("wb.js.history.next");
    historySearch.setToolTipText("출처 URL·분석 상태 검색 · 원문/인증값은 검색하지 않습니다.");
    historySearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){change();}public void removeUpdate(javax.swing.event.DocumentEvent e){change();}public void changedUpdate(javax.swing.event.DocumentEvent e){change();}private void change(){historyQuery=historySearch.getText();historyOffset=0;historyQueryGeneration.incrementAndGet();requestArchivePage();if(archive==null)syncSources();}});
    historyPrevious.addActionListener(e->{historyOffset=Math.max(0,historyOffset-JsArchive.PAGE_SIZE);historyQueryGeneration.incrementAndGet();requestArchivePage();});
    historyNext.addActionListener(e->{if(archivePage.more()){historyOffset+=JsArchive.PAGE_SIZE;historyQueryGeneration.incrementAndGet();requestArchivePage();}});
    urlTable.setAutoCreateRowSorter(true);
    urlTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    int[] widths = {380, 85, 170, 170, 140, 150, 55, 150, 90};
    for (int i = 0; i < widths.length; i++)
      urlTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    urlTable.moveColumn(7, 2);
    secretTable.setAutoCreateRowSorter(true);
    urlTable.setRowHeight(26);
    secretTable.setRowHeight(26);
    results.addTab("주요 경로", new JScrollPane(urlTable));
    results.addTab("민감한 정보", new JScrollPane(secretTable));
    for (var pair :
        new Object[][] {
          {"도메인", domainModel},
          {"클라우드", cloudModel},
          {"NPM 의존성", dependencyModel},
          {"소스맵", mapModel},
          {"복원 원본", recoveredModel}
        }) {
      JTable t = new JTable((DefaultTableModel) pair[1]);
      t.setAutoCreateRowSorter(true);
      results.addTab((String) pair[0], new JScrollPane(t));
    }
    historyTable.setAutoCreateRowSorter(true);
    historyTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    int[] historyWidths={380,80,105,260};
    for(int i=0;i<historyWidths.length;i++)historyTable.getColumnModel().getColumn(i).setPreferredWidth(historyWidths[i]);
    historyTable.setRowHeight(26);
    historyTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              int row = historyTable.getSelectedRow();
              if (!syncingSources && !e.getValueIsAdjusting() && row >= 0) {
                sources.setSelectedItem(historyModel.getValueAt(historyTable.convertRowIndexToModel(row), 0));
                prepareNavigation();
              }
            });
    JSplitPane workspace = WorkbenchUi.split(
        JSplitPane.HORIZONTAL_SPLIT,
        WorkbenchUi.card("분석 원문", sourcePane()), newResultsView(), .40);
    workspace.setName("wb.js.findings");
    results.setName("wb.js.results");
    add(workspace);
    for (int i=0;i<results.getTabCount();i++) {
      Component view=results.getComponentAt(i);
      if (view instanceof JScrollPane pane && pane.getViewport().getView() instanceof JTable table)
        bindPacketOpening(table);
    }
    bindPacketOpening(historyTable);
    JPanel footer = new JPanel(new BorderLayout());
    footer.add(status, BorderLayout.CENTER);
    footer.add(progress, BorderLayout.EAST);
    JButton copy = new JButton("URL 목록 복사"), report = new JButton("가림 결과 복사"),
        clear = new JButton("분석 초기화");
    sources.setName("wb.js.sources");
    sourceUrl.setColumns(22);
    JButton tools = new JButton("추가 도구"), more = new JButton("더보기");
    JPopupMenu toolMenu = new JPopupMenu(), moreMenu = new JPopupMenu();
    // Simple keeps source and observed-packet work local. Optional registry queries
    // and active source-map discovery are not part of the visible execution path.
    JMenuItem observed=new JMenuItem("관찰된 API 패킷 보기");observed.addActionListener(e->openObservedPacket());toolMenu.add(observed);
    tools.setToolTipText("선택한 API와 같은 경로에서 실제 관찰한 요청·응답을 확인합니다.");
    tools.putClientProperty("wb.simple.js.tools.menu", toolMenu);
    JMenuItem matchTraffic=new JMenuItem("트래픽 대조"),compareVersions=new JMenuItem("버전 비교");
    matchTraffic.setName("wb.js.traffic.compare");compareVersions.setName("wb.js.version.compare");
    matchTraffic.addActionListener(e->resultView.openTraffic());compareVersions.addActionListener(e->openVersionComparison());
    moreMenu.add(matchTraffic);moreMenu.add(compareVersions);moreMenu.addSeparator();
    more.putClientProperty("wb.js.more.menu",moreMenu);
    JMenuItem retryItem = new JMenuItem("분석 처리·재시도");
    retryItem.setName("wb.js.retry.open");
    retryItem.addActionListener(e -> retryWindow.open());
    moreMenu.add(retryItem);
    menu(moreMenu, "URL 목록 복사", copy);
    menu(moreMenu, "가림 결과 복사", report);
    menu(moreMenu, "분석 초기화", clear);
    tools.addActionListener(e -> toolMenu.show(tools, 0, tools.getHeight()));
    more.addActionListener(e -> moreMenu.show(more, 0, more.getHeight()));
    JButton target = targetSettings.button();
    more.setName("wb.js.more");more.setText("");more.setIcon(WorkspaceIcons.of("more"));more.setToolTipText("더보기");more.getAccessibleContext().setAccessibleName("더보기");more.setPreferredSize(new Dimension(36,34));
    resolve.setText("Repeater 준비");WorkbenchUi.primary(resolve);
    JPanel toolbar=new JPanel(new BorderLayout(0,6));toolbar.setName("wb.js.toolbar");
    toolbar.add(WorkbenchUi.row(passive,target,tools,more),BorderLayout.NORTH);
    for(JButton action:new JButton[]{analysisList,analysisDetails}){JMenuItem item=new JMenuItem(action.getText());item.addActionListener(e->action.doClick());moreMenu.add(item);}
    sources.setPreferredSize(new Dimension(290,34));sources.setName("wb.js.source.choice");
    JPanel sourceContext=new JPanel(new BorderLayout(8,0));sourceContext.add(WorkbenchUi.row(new JLabel("소스"),sources),BorderLayout.WEST);sourceContext.add(CompactWorkbenchUi.search(urlTable,"wb.js.findings.search","경로·메서드·입력값 검색"));
    toolbar.add(CompactWorkbenchUi.toolbar(sourceContext,WorkbenchUi.row(locate,resolve)),BorderLayout.SOUTH);
    resolve.addActionListener(e -> openResolvedDraft());

    header.removeAll();
    header.add(
        WorkbenchUi.header(FeatureGuide.title("js"), "JS에서 경로·입력·민감정보 후보 찾기 · 발견은 검증과 다름", null),
        BorderLayout.NORTH);
    JPanel actions=new JPanel(new BorderLayout(18,0));
    actions.setName("wb.js.header.actions");actions.add(toolbar);
    header.add(actions);
    footer.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
    status.putClientProperty("wb.font.size",11);
    progress.putClientProperty("wb.font.size",11);
    progress.setName("wb.js.progress");
    progress.setHorizontalAlignment(SwingConstants.RIGHT);
    status.addPropertyChangeListener("text",e->status.setToolTipText(status.getText()));
    add(footer, BorderLayout.SOUTH);
    WorkbenchUi.style(this);
    status.setForeground(WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));
    progress.setForeground(WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));
    KeyStroke repeaterKey=KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK);
    getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(repeaterKey,"wb.js.repeater");
    getActionMap().put("wb.js.repeater",toRepeater);
    urlTable.getInputMap(WHEN_FOCUSED).put(repeaterKey,"wb.js.repeater");
    urlTable.getActionMap().put("wb.js.repeater",toRepeater);
    urlTable.setToolTipText("더블클릭: 발견 출처 패킷 · Ctrl+R: 선택 URL의 Repeater 창 열기");
    resolve.setToolTipText("선택 URL의 경로·메서드를 확인하고 동적 부분을 입력해 Repeater 초안을 준비합니다.");
    locate.setToolTipText("선택한 URL 또는 시크릿 후보의 원문 위치로 이동합니다.");
    results.addChangeListener(e -> refreshActions());
    javax.swing.table.TableModel[] countModels={endpoints,secrets,domainModel,cloudModel};
    String[] captions={"주요 경로","민감한 정보","도메인","클라우드"};
    for(int index=0;index<countModels.length;index++){
      final int tab=index;
      countModels[index].addTableModelListener(e->results.setTitleAt(tab,captions[tab]+" "+countModels[tab].getRowCount()));
    }
    refreshActions();
    clear.addActionListener(e -> clear());
    sources.addActionListener(
        e -> {
          if (syncingSources) return;
          sources.setToolTipText(
              Objects.toString(sources.getSelectedItem(), "전체 분석 이력 · 선택한 파일의 결과를 표시합니다."));
          String chosen=Objects.toString(sources.getSelectedItem(), "");
          JsAnalysis.Result r = cache.result(chosen);
          if(r==null&&archive!=null){restore(chosen);return;}
          if (r != null) {
            selectSource(Objects.toString(sources.getSelectedItem(), ""));
            sourceUrl.setText(currentSource.equals("직접 입력") ? "" : currentSource);
            render(r);
          }
        });
    copy.addActionListener(e -> {
      String text=current==null?"":String.join("\n",current.endpoints().stream().map(JsAnalysis.Endpoint::url).distinct().toList());
      afterProtectingHistory(currentSource,()->copy(text));
    });
    report.addActionListener(e -> {String text=maskedReport();afterProtectingHistory(currentSource,()->copy(text));});
    locate.addActionListener(
        e -> {
          prepareNavigation();
          int tab = results.getSelectedIndex();
          if (tab == 0 && urlTable.getSelectedRow() >= 0)
            locateEndpoint(urlTable.convertRowIndexToModel(urlTable.getSelectedRow()));
          else if (tab == 1 && secretTable.getSelectedRow() >= 0)
            locateSecret(secretTable.convertRowIndexToModel(secretTable.getSelectedRow()));
        });
    urlTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting()) {
                if (!navigating && urlTable.getSelectedRow() >= 0) {
                  prepareNavigation();
                  locateEndpoint(urlTable.convertRowIndexToModel(urlTable.getSelectedRow()));
                }
                refreshActions();
              }
            });
    secretTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting()) {
                if (!navigating && secretTable.getSelectedRow() >= 0) {
                  prepareNavigation();
                  locateSecret(secretTable.convertRowIndexToModel(secretTable.getSelectedRow()));
                }
                refreshActions();
              }
            });
    input
        .getDocument()
        .addDocumentListener(
            new javax.swing.event.DocumentListener() {
              void changed() {
                if (!preparingSource) navigator.invalidate();
              }

              public void insertUpdate(javax.swing.event.DocumentEvent e) {
                changed();
              }

              public void removeUpdate(javax.swing.event.DocumentEvent e) {
                changed();
              }

              public void changedUpdate(javax.swing.event.DocumentEvent e) {
                changed();
              }
            });

    discoverMaps.addActionListener(e -> discover());
    checkNpm.addActionListener(e -> checkRegistry());
    refreshProgress();
  }

  private Component newResultsView(){resultTabs=new ResultTabs(results);resultView=new JsResultsView(this,resultTabs);resultView.configureTable();return resultView;}

  private void rememberVersionHash(String source,String hash){versionHashes.put(source,hash);if(versionHashes.size()>128)for(String key:new ArrayList<>(versionHashes.keySet())){if(versionHashes.size()<=128)break;if(!key.equals(currentSource)&&!key.equals(source))versionHashes.remove(key);}}

  void openVersionComparison(){
    if(archive==null){resultView.versionWindow.closeWindow();resultView.versionWindow.open();return;}
    String source=currentSource;long token=epoch.get();
    enqueue(()->{try{
      var snapshots=archive.versions(source);
      for(var snapshot:snapshots.subList(Math.max(0,snapshots.size()-8),snapshots.size())){if(token!=epoch.get()||closed.get())return;var metadata=archive.resultMetadata(snapshot.key());if(metadata!=null){versions.recordAt(source,snapshot.fingerprint(),metadata,archive.time(snapshot.key()));rememberVersionHash(snapshot.key(),snapshot.fingerprint());}}
      onEdt(()->{if(token!=epoch.get()||closed.get()||!source.equals(currentSource))return;resultView.refresh();resultView.versionWindow.closeWindow();resultView.versionWindow.open();});
    }catch(Exception ex){onEdt(()->status.setText("버전 이력 읽기 실패 · 저장 기록 확인"));}},true);
  }

  void prepareRepeater() {
    if (!toRepeater.isEnabled()) return;
          try {
            if (current == null) return;
            List<JsRequestDraft.Draft> drafts = new ArrayList<>();
            for (int row : urlTable.getSelectedRows()) {
              var item = current.endpoints().get(urlTable.convertRowIndexToModel(row));
              drafts.add(current.requestDrafts().getOrDefault(JsAnalysis.draftKey(item),JsRequestDraft.preview(item)));
            }
            List<String> unknown=drafts.stream().filter(draft->!JsMethodSelection.known(draft.method())).map(JsRequestDraft.Draft::url).toList();
            Map<String,Set<String>> observed=unknown.isEmpty()?Map.of():observedMethods.apply(unknown);
            List<JsMethodSelection.Choice> choices=new ArrayList<>();
            for(var draft:drafts){String key=ActivityTracker.safeUrl(draft.url());choices.add(JsMethodSelection.choose(draft,key==null?Set.of():observed.get(key)));}
            afterProtectingHistory(currentSource,()->{
              repeater.openPrepared(choices);
              status.setText("Repeater 창 열림 · 메서드 선택 근거와 Raw의 경로·값·인증 확인 · 전송 없음");
            });
          } catch (Exception ex) {
            Diagnostics.failure(JsAnalysisPanel.this,"JS에서 API 찾기",ex);
            status.setText(ex.getMessage());
          }
  }

  JPanel analysisContent() {
    JPanel panel=new WindowPanel();panel.setName("wb.js.analysis.window");
    JButton protect=new JButton("선택 이력 보존"),remove=new JButton("선택 이력 삭제"),settings=new JButton("보존·디스크 설정");
    protect.setName("wb.js.history.protect");remove.setName("wb.js.history.delete");settings.setName("wb.js.history.storage");
    protect.setEnabled(archive!=null);remove.setEnabled(archive!=null);settings.setEnabled(archive!=null);
    protect.addActionListener(e->{String key=selectedHistoryKey();if(!key.isEmpty())protectHistory(key);});
    remove.addActionListener(e->{String key=selectedHistoryKey();if(key.isEmpty())return;if(!GraphicsEnvironment.isHeadless()&&JOptionPane.showConfirmDialog(this,"선택 이력만 삭제합니다. 보존 표시·이전 버전 기록은 제외됩니다.","JS 이력 삭제",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
      enqueue(()->{try{archive.delete(key);onEdt(()->{cache.remove(key);displayedArchiveRevision=-1;requestArchivePage();status.setText("보존 미지정 이력 삭제 완료 · 열려 있는 결과는 다음 선택까지 유지");});}catch(Exception ex){onEdt(()->status.setText(ex.getMessage()));}},true);});
    settings.addActionListener(e->{archiveSettings.closeWindow();archiveSettings.open();});
    panel.add(TargetWindow.options(new JLabel("항목 선택: 분석 결과 보기 · 더블클릭: 발견 출처 패킷"),
        WorkbenchUi.row(new JLabel("검색"),historySearch,historyPrevious,historyNext,historyPageLabel),
        WorkbenchUi.row(protect,remove,settings),archiveUsage),BorderLayout.NORTH);
    JScrollPane scroll=new JScrollPane(historyTable);scroll.setPreferredSize(new Dimension(740,330));panel.add(scroll);return panel;
  }

  private String selectedHistoryKey(){int row=historyTable.getSelectedRow();return row<0?"":Objects.toString(historyModel.getValueAt(historyTable.convertRowIndexToModel(row),0),"");}

  public void protectHistory(String key){
    afterProtectingHistory(key,()->status.setText("JS 이력 보존 표시 · 외부 작업 연결 기록은 정리에서 제외"));
  }

  private void afterProtectingHistory(String key,Runnable action){
    if(archive==null||key==null||key.isBlank()){action.run();return;}
    long token=epoch.get();
    enqueue(()->{try{archive.protect(key);onEdt(()->{if(!closed.get()&&token==epoch.get())action.run();});}catch(Exception ex){onEdt(()->status.setText("이력 보존 표시 실패 · 디스크 확인 후 다시 실행"));}},true);
  }

  private JPanel archiveSettingsContent(){
    JCheckBox enabled=new JCheckBox("보존 기간 기준 사용 · 자동 삭제 없음");
    JSpinner days=new JSpinner(new SpinnerNumberModel(90,1,3650,1)),warning=new JSpinner(new SpinnerNumberModel(2048,1,65536,128));
    JLabel usage=new JLabel("디스크 사용량 확인 중");JButton save=new JButton("기준 저장"),prune=new JButton("기간이 지난 이력 정리");
    enabled.setName("wb.js.retention.enabled");days.setName("wb.js.retention.days");warning.setName("wb.js.retention.warning");usage.setName("wb.js.retention.usage");save.setName("wb.js.retention.save");prune.setName("wb.js.retention.prune");
    JPanel panel=TargetWindow.options(usage,enabled,WorkbenchUi.row(new JLabel("보존 일수"),days,new JLabel("디스크 알림 MiB"),warning),
        new JLabel("<html>보존 표시·이전 버전 이력은 삭제하지 않습니다.<br>증거 연결을 추적하지 못한 이력은 직접 보존 표시하세요.</html>"),WorkbenchUi.row(save,prune));
    enqueue(()->{try{var policy=archive.retention();var size=archive.usage();onEdt(()->{enabled.setSelected(policy.enabled());days.setValue(policy.days());warning.setValue((int)(policy.warningBytes()/(1024*1024)));usage.setText("디스크 "+memory(size.bytes())+" · 이력 "+size.records()+" · 보존/이전 버전 "+size.protectedRecords()+(size.bytes()>=policy.warningBytes()?" · 디스크 알림 기준 초과":""));archiveUsage.setText(usage.getText()+" · 자동 삭제 없음");});}catch(Exception ex){onEdt(()->usage.setText("디스크 정보 읽기 실패"));}},true);
    save.addActionListener(e->{var policy=new JsArchive.Retention(enabled.isSelected(),(Integer)days.getValue(),((Integer)warning.getValue()).longValue()*1024*1024);enqueue(()->{try{archive.retention(policy);onEdt(()->usage.setText("보존 기준 저장 · 정리는 버튼으로 직접 실행"));}catch(Exception ex){onEdt(()->usage.setText("설정 저장 실패"));}},true);});
    prune.addActionListener(e->{var policy=new JsArchive.Retention(enabled.isSelected(),(Integer)days.getValue(),((Integer)warning.getValue()).longValue()*1024*1024);if(!policy.enabled()){usage.setText("보존 기간 기준을 먼저 선택하세요.");return;}if(GraphicsEnvironment.isHeadless()||JOptionPane.showConfirmDialog(this,"기간이 지난 보존 미지정 이력을 삭제할까요? 이전 버전·보존 표시는 유지됩니다.","JS 이력 정리",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
      enqueue(()->{try{int removed=archive.prune(policy);onEdt(()->{usage.setText(removed+"개 정리 완료");cache.clear();displayedArchiveRevision=-1;historyOffset=0;historyQueryGeneration.incrementAndGet();requestArchivePage();});}catch(Exception ex){onEdt(()->usage.setText("이력 정리 실패 · 디스크 확인"));}},true);});
    return panel;
  }

  private void bindPacketOpening(JTable table) {
    table.addMouseListener(new MouseAdapter() {
      public void mouseClicked(MouseEvent event) {
        if (!SwingUtilities.isLeftMouseButton(event) || event.getClickCount()!=2) return;
        int row=table.rowAtPoint(event.getPoint());
        if (row<0) return;
        table.setRowSelectionInterval(row,row);
        openPacket(table, row);
      }
    });
    table.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"wb.js.packet");
    table.getActionMap().put("wb.js.packet",new AbstractAction() {
      public void actionPerformed(ActionEvent event) {if(table.getSelectedRow()>=0)openPacket(table,table.getSelectedRow());}
    });
  }

  void openPacket(JTable table,int viewRow) {
    if(closed.get() || viewRow<0 || viewRow>=table.getRowCount())return;
    int row=table.convertRowIndexToModel(viewRow);
    String source=table==historyTable?Objects.toString(historyModel.getValueAt(row,0),""):currentSource;
    var entry=cache.get(source);
    openedSource=source;
    // The selected row identifies a finding, not an HTTP request to send or fabricate.
    openedFinding=Objects.toString(table.getModel().getValueAt(row,0),"");
    openedPacket=entry==null||entry.packet()==null?null:entry.packet().clone();
    packetWindow.closeWindow();
    packetWindow.open();
  }

  void openObservedPacket(){
    int row=urlTable.getSelectedRow();if(current==null||row<0)return;var endpoint=current.endpoints().get(urlTable.convertRowIndexToModel(row));
    long token=epoch.get();status.setText("관찰된 API 패킷 찾는 중");
    enqueue(()->{try{var traffic=trafficLookup.apply(endpoint.method(),endpoint.url());if(traffic==null){onEdt(()->status.setText("해당 API의 실제 패킷이 수집 목록에 없습니다. 발견 출처 패킷은 더블클릭으로 확인하세요."));return;}
      String request=packetPreview(traffic.request().toByteArray()),raw=traffic.response()==null?"응답 없음":packetPreview(traffic.response().toByteArray());
      onEdt(()->{if(closed.get()||token!=epoch.get())return;openedSource=endpoint.url();openedFinding="실제 관찰 · "+endpoint.method()+" "+endpoint.url();openedPacket=new String[]{request,raw,raw};packetWindow.closeWindow();packetWindow.open();});
    }catch(Exception ex){onEdt(()->status.setText("관찰 패킷을 읽지 못했습니다. 수집 목록 초기화 여부를 확인하세요."));}},true);
  }

  JPanel packetContent() {
    PacketView request=new PacketView(), response=new PacketView();
    request.text.setName("wb.js.packet.request.text");response.text.setName("wb.js.packet.response.text");
    String[] snapshot=openedPacket;
    request.setPacket(snapshot==null?"해당 출처의 요청 미리보기가 없습니다.":snapshot[0]);
    response.setPacket(snapshot==null?"해당 출처의 응답 미리보기가 없습니다.":snapshot[1],snapshot==null?null:snapshot[2]);
    JPanel body=new WindowPanel();body.setName("wb.js.packet.window");
    JLabel source=new JLabel("발견 출처 · "+compact(openedSource));source.setToolTipText(openedSource);
    JLabel finding=new JLabel("선택 항목 · "+compact(openedFinding));finding.setToolTipText(openedFinding);
    body.add(TargetWindow.options(source,finding),BorderLayout.NORTH);
    var packets=WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT,
        WorkbenchUi.card("요청 REQUEST",request),WorkbenchUi.card("응답 RESPONSE",response),.5);
    packets.setName("wb.js.popup.messages");packets.setPreferredSize(new Dimension(690,330));body.add(packets);
    return body;
  }

  private static final class WindowPanel extends JPanel implements Scrollable {
    WindowPanel(){super(new BorderLayout(0,6));}
    public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
    public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction){return 20;}
    public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction){return Math.max(20,visible.height-20);}
    public boolean getScrollableTracksViewportWidth(){return true;}
    public boolean getScrollableTracksViewportHeight(){return true;}
  }

  private static String compact(String value) {return value.length()>56?value.substring(0,53)+"…":value;}

  void rememberPacket(String url, String request, String raw, String decoded) {
    cache.packet(
        safeSource(url),
        new String[] {
          EnvironmentPanel.preview(request),
          EnvironmentPanel.preview(raw),
          EnvironmentPanel.preview(decoded)
        });
    syncSources();
  }

  /** Only immutable, bounded data crosses into the worker; host packets are never queued. */
  private record AnalysisInput(
      String url,
      String mime,
      String encoding,
      String contentType,
      String document,
      short status,
      byte[] body,
      String request,
      String raw,
      String retryId) {
    long bytes() {
      return 160L
          + body.length
          + JsSourceCache.textBytes(url)
          + JsSourceCache.textBytes(mime)
          + JsSourceCache.textBytes(encoding)
          + JsSourceCache.textBytes(contentType)
          + JsSourceCache.textBytes(document)
          + JsSourceCache.textBytes(request)
          + JsSourceCache.textBytes(raw);
    }
  }

  private void requestArchivePage(){
    if(archive==null||closed.get()||!archiveRefreshPending.compareAndSet(false,true))return;
    long queryVersion=historyQueryGeneration.get(),token=epoch.get();int offset=historyOffset;String query=historyQuery;
    var cached=cache.snapshot();
    boolean accepted=enqueue(()->{try{
      var page=archive.page(offset,query,()->closed.get()||token!=epoch.get()||queryVersion!=historyQueryGeneration.get());
      if(page.cancelled())return;
      if(page.rows().isEmpty()&&offset>0){onEdt(()->{if(queryVersion==historyQueryGeneration.get()){historyOffset=(int)(Math.max(0,page.total()-1)/JsArchive.PAGE_SIZE)*JsArchive.PAGE_SIZE;historyQueryGeneration.incrementAndGet();}});return;}
      var counts=archive.countsWith(cached.values());long version=archive.revision();
      onEdt(()->{if(closed.get()||token!=epoch.get()||queryVersion!=historyQueryGeneration.get())return;archivePage=page;archiveCounts=counts;displayedArchiveRevision=version;syncSources();});
    }catch(Exception ex){onEdt(()->status.setText("분석 목록 읽기 실패 · 디스크 상태 확인"));}
    finally{archiveRefreshPending.set(false);if(queryVersion!=historyQueryGeneration.get()&&!closed.get())onEdt(this::requestArchivePage);}},true);
    if(!accepted)archiveRefreshPending.set(false);
  }

  private void syncSources() {
    Object selected=sources.getSelectedItem();String selectedHistory=selectedHistoryKey();syncingSources=true;
    try{
      var snapshot=cache.snapshot();LinkedHashSet<String> keys=new LinkedHashSet<>();
      if(archive!=null)for(var row:archivePage.rows())keys.add(row.key());
      for(var entry:snapshot.entrySet())if(entry.getValue().result()!=null)keys.add(entry.getKey());
      if(!currentSource.isBlank()&&current!=null)keys.add(currentSource);
      for(int i=sources.getItemCount()-1;i>=0;i--)if(!keys.contains(sources.getItemAt(i)))sources.removeItemAt(i);
      for(String key:keys){boolean exists=false;for(int i=0;i<sources.getItemCount();i++)if(key.equals(sources.getItemAt(i))){exists=true;break;}if(!exists)sources.addItem(key);}
      if(selected!=null&&keys.contains(selected))sources.setSelectedItem(selected);
      historyModel.setRowCount(0);
      if(archive!=null){
        for(var row:archivePage.rows())historyModel.addRow(new Object[]{row.key(),row.apis(),row.secrets(),row.status()+" · 디스크 보관"});
        totals.api.count((int)Math.min(Integer.MAX_VALUE,archiveCounts.apis()));totals.secret.count((int)Math.min(Integer.MAX_VALUE,archiveCounts.secrets()));totals.domain.count((int)Math.min(Integer.MAX_VALUE,archiveCounts.domains()));totals.cloud.count((int)Math.min(Integer.MAX_VALUE,archiveCounts.clouds()));
        historyPrevious.setEnabled(historyOffset>0);historyNext.setEnabled(archivePage.more());
        historyPageLabel.setText((archivePage.rows().isEmpty()?0:historyOffset+1)+"–"+(historyOffset+archivePage.rows().size())+" / "+archivePage.total());
        if(displayedArchiveRevision!=archive.revision())requestArchivePage();
      }else{
        String query=historyQuery.toLowerCase(Locale.ROOT);
        for(var entry:snapshot.entrySet()){var result=entry.getValue().result();if(result!=null&&(entry.getKey()+" "+result.status()).toLowerCase(Locale.ROOT).contains(query))historyModel.addRow(new Object[]{entry.getKey(),result.endpoints().size(),result.secrets().size(),result.status()});}
        totals.update(snapshot.values());historyPrevious.setEnabled(false);historyNext.setEnabled(false);historyPageLabel.setText(historyModel.getRowCount()+"개 · 메모리 목록");
      }
      if(!selectedHistory.isEmpty())for(int row=0;row<historyModel.getRowCount();row++)if(selectedHistory.equals(historyModel.getValueAt(row,0))){int view=historyTable.convertRowIndexToView(row);if(view>=0)historyTable.setRowSelectionInterval(view,view);break;}
    }finally{syncingSources=false;}
  }

  private void onEdt(Runnable action) {
    if (SwingUtilities.isEventDispatchThread()) {
      action.run();
      return;
    }
    try {
      SwingUtilities.invokeAndWait(action);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    } catch (java.lang.reflect.InvocationTargetException ex) {
      throw new IllegalStateException("JS 화면 갱신 실패", ex.getCause());
    }
  }

  static void menu(JPopupMenu menu, String text, JButton action) {
    JMenuItem item = new JMenuItem(text);
    item.addActionListener(e -> action.doClick());
    menu.add(item);
  }

  JPanel sourcePane() {
    JPanel p = new JPanel(new BorderLayout(0, 4));
    p.setName("wb.js.manual");
    JPanel actions = new JPanel();
    actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
    actions.add(
        WorkbenchUi.row(navigator.previous, navigator.next, navigator.filter, findingCount));
    navigator.previous.getAccessibleContext().setAccessibleName("이전 감지 위치");
    navigator.next.getAccessibleContext().setAccessibleName("다음 감지 위치");
    String legend = "원문 표시: 파랑 URL · 빨강 시크릿 · 초록 도메인/의존성 · 보라 소스맵";
    navigator.filter.setToolTipText(legend);
    input.setToolTipText("선택한 분석 결과의 원문 · " + legend);
    input.setName("wb.js.source");
    JScrollPane scroll = new JScrollPane(input);
    scroll.setName("wb.js.source.scroll");
    scroll.setMinimumSize(new Dimension(160, 90));
    sourceSummary.setBorder(BorderFactory.createEmptyBorder(1, 8, 3, 8));
    sourceSummary.setName("wb.js.source.summary");
    p.add(actions, BorderLayout.NORTH);
    p.add(scroll, BorderLayout.CENTER);
    p.add(sourceSummary, BorderLayout.SOUTH);
    return p;
  }

  void refreshActions() {
    boolean endpoint =
        current != null && results.getSelectedIndex() == 0 && urlTable.getSelectedRow() >= 0;
    if(resultView!=null)resultView.selection();
    toRepeater.setEnabled(endpoint);
    resolve.setEnabled(endpoint);
    locate.setEnabled(
        endpoint
            || current != null
                && results.getSelectedIndex() == 1
                && secretTable.getSelectedRow() >= 0);
  }

  public void startHistory() { startHistory(true); }

  /** Embedded capture supplies exact original references; polling still backfills history. */
  public void startHistory(boolean observeLive) {
    if (api == null) return;
    if(archive!=null)enqueue(()->{try{archive.load();onEdt(this::syncSources);}catch(Exception ex){onEdt(()->status.setText("분석 이력 불러오기 실패"));}},true);
    try {
      var proxy = api.proxy();
      if (proxy == null) return;
      historyMonitor =
          new JsHistoryMonitor(proxy, this::offer, () -> watching.get() && !closed.get(), observeLive);
      historyMonitor.start();
      progressTimer = new javax.swing.Timer(500, e -> refreshProgress());
      progressTimer.start();
      refreshProgress();
    } catch (Exception e) {
      progress.setText("History 연결 실패 · 연결 상태를 확인하세요.");
    }
  }

  private void refreshProgress() {
    refreshBackgroundJob();
    if(resultView!=null)resultView.refresh();
    String counters =
        (watching.get() ? "분석 ON" : "분석 OFF")
            + " · 대기/분석 "
            + jobs.count()
            + " · 처리 "
            + processed.get()
            + " · 중복 "
            + duplicates.get()
            + " · Scope 제외 "
            + excluded.get()
            + " · 크기/형식 제외 "
            + unsupported.get()
            + " · 큐 초과 "
            + overflow.get()
            + " (History 보완) · 실패 "
            + failed.get()
            + (historyMonitor == null
                ? ""
                : " · History 확인 "
                    + historyMonitor.checked.get()
                    + " · 조회 오류 "
                    + historyMonitor.failures.get()
                    + " · 미응답 대기 초과 "
                    + historyMonitor.pendingOverflow.get());
    String memory =
        " · 대기 "
            + memory(jobs.bytes())
            + "/"
            + memory(jobs.budget())
            + " · 캐시 추정 "
            + memory(cache.bytes())
            + "/"
            + memory(cache.budget())
            + " · 퇴출 "
            + cache.evictions()
            + " · 절단 "
            + truncated.get();
    // Keep the source selector and memory summary on one row at the minimum supported width.
    // Full counters and both byte budgets remain available in the tooltip.
    progress.setText(
        (watching.get() ? "분석 ON" : "분석 OFF")
            + " · 대기 "
            + jobs.count()
            + "/"
            + memory(jobs.bytes())
            + " · 캐시 추정 "
            + memory(cache.bytes())
            + " · 절단 "
            + truncated.get());
    progress.setToolTipText(
        "<html>" + Core.html(counters + memory + " · 캐시 보관 제외 " + cache.rejected() + " · 기존 개별 분석/미리보기 제한 유지"
            + "\n" + jobs.diagnostics() + "\n" + cache.diagnostics() + "\n" + RuntimeMetrics.heapSummary()).replace("\n", "<br>") + "</html>");
  }

  boolean idle() {
    return jobs.count() == 0;
  }

  private static String fingerprint(String text) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(
                      text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void copy(String text) {
    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
  }

  static String safeSource(String url) {
    try {
      URI u = URI.create(url);
      if (u.getHost() != null)
        return new URI(u.getScheme(), null, u.getHost(), u.getPort(), u.getPath(), null, null)
            .toString();
    } catch (Exception ignored) {
    }
    return "직접 입력";
  }

  public void rescanHistory() {
    if (historyMonitor != null) historyMonitor.rescan();
  }

  public void load(String text, String url) {
    if (closed.get()) return;
    // Keep one extra character so JsAnalysis retains its explicit partial-analysis warning.
    String bounded = text.substring(0, Math.min(text.length(), JsAnalysis.SOURCE_LIMIT + 1));
    input.setText(bounded.substring(0, Math.min(bounded.length(), JsAnalysis.SOURCE_LIMIT)));
    sourceUrl.setText(safeSource(url).equals("직접 입력") ? "" : safeSource(url));
    long token = epoch.get();
    enqueue(
        () -> analyzeText(bounded, url, true, token, null),
        true,
        JsSourceCache.textBytes(bounded) + JsSourceCache.textBytes(url));
  }

  public void loadContext(String text,String url,String type,String document){
    if(closed.get())return;long token=epoch.get();
    if(text.length()>JsBodyDecoder.WORK_LIMIT){recordFailure(url,"입력 작업 메모리 한도",token);return;}
    input.setText(text.substring(0,Math.min(text.length(),JsAnalysis.SOURCE_LIMIT)));sourceUrl.setText(safeSource(url));
    enqueue(()->analyzeText(text,url,true,token,null,Objects.toString(type,"application/javascript"),document),true,JsSourceCache.textBytes(text)+JsSourceCache.textBytes(url));
  }

  private void recordFailure(String url,String reason,long token){
    String key=safeSource(url);var result=new JsAnalysis.Result(List.of(),List.of(),"미분석 · "+reason+" · 원본은 HTTP history에서 확인",true);
    enqueue(()->{if(token!=epoch.get()||closed.get())return;
      String version=key;if(archive!=null)try{archive.load();version=archive.versionKey(key,fingerprint(reason));archive.save(version,"","","",result,null,fingerprint(reason));}catch(Exception ignored){}
      String recorded=version;
      onEdt(()->{if(token!=epoch.get()||closed.get())return;cache.analysis(recorded,"",null,result,null);syncSources();status.setText(recorded+" · "+result.status());});
    },false);
  }

  public boolean offer(HttpRequest request, HttpResponse response) {
    return offer(request, response, null);
  }

  boolean offer(HttpRequest request, HttpResponse response, DiskTrafficStore.PacketRef original) {
    if (closed.get() || !watching.get()) return false;
    if (response == null || request == null) return true;
    String retryId = null;
    try {
      if (!WorkbenchScope.contains(api, request)) { excluded.incrementAndGet(); return true; }
      if (original != null) {
        var previous = retries.get(original.id());
        if (previous != null && (previous.state() == AnalysisRetries.State.QUEUED || previous.state() == AnalysisRetries.State.RUNNING)) return true;
      }
      retryId = retries.record(original, request.method(), request.url(), AnalysisRetries.State.QUEUED, "로컬 분석 대기");
      AnalysisInput snapshot = snapshot(request, response, retryId);
      if (snapshot == null) return true;
      long token = epoch.get();
      boolean accepted = enqueue(() -> analyzeSnapshot(snapshot, token, false), false, snapshot.bytes());
      if (!accepted) retries.state(retryId, AnalysisRetries.State.QUEUE_FULL, "개수/바이트 한도 · 대기가 줄어든 뒤 선택 원본 재시도");
      return accepted;
    } catch (Exception error) {
      failed.incrementAndGet();
      if (retryId != null) retries.state(retryId, AnalysisRetries.State.FAILED, "원본 읽기/분석 준비 실패 · 선택 원본 재시도");
      return true;
    }
  }

  private AnalysisInput snapshot(HttpRequest request, HttpResponse response, String retryId) {
    String type = response.headerValue("Content-Type"), url = request.url();
    String mime = type == null ? "" : type.toLowerCase(Locale.ROOT);
    if (!TextCandidates.accepts(mime, url)) {
      unsupported.incrementAndGet();
      retries.state(retryId, AnalysisRetries.State.UNSUPPORTED, "지원 텍스트 응답이 아닙니다. 원본 트래픽에서 형식을 확인하세요.");
      return null;
    }
    if (response.body().length() > JsBodyDecoder.WORK_LIMIT) {
      unsupported.incrementAndGet();
      retries.state(retryId, AnalysisRetries.State.TOO_LARGE, "응답 작업 메모리 한도 초과 · 한도를 우회하지 않습니다.");
      recordFailure(url, "응답 작업 메모리 한도", epoch.get());
      return null;
    }
    String requestText = "", raw = "";
    try { requestText = packetPreview(request.toByteArray()); raw = packetPreview(response.toByteArray()); } catch (Exception ignored) {}
    AnalysisInput snapshot = new AnalysisInput(url, mime, response.headerValue("Content-Encoding"), type,
        request.headerValue("Referer"), response.statusCode(), response.body().getBytes(), requestText, raw, retryId);
    if (snapshot.bytes() > jobs.budget()) {
      unsupported.incrementAndGet();
      retries.state(retryId, AnalysisRetries.State.TOO_LARGE, "단일 작업이 대기/실행 바이트 한도 초과 · 원본에서 별도 확인");
      recordFailure(url, "대기/실행 작업 메모리 한도", epoch.get());
      return null;
    }
    return snapshot;
  }

  /** Resolves only the selected immutable disk reference; never calls an HTTP transport. */
  void retryOriginal(String id) {
    if (closed.get()) return;
    if (!watching.get()) { status.setText("분석 ON으로 전환한 뒤 선택 원본을 다시 분석하세요."); return; }
    var entry = retries.begin(id);
    if (entry == null) return;
    var original = entry.original();
    // Reserve raw restoration + body copy + bounded previews before reading disk packets.
    long retained = 2L * (original.requestLength() + (long) original.responseLength()) + 512 * 1024L;
    if (original.responseBodyLength() > JsBodyDecoder.WORK_LIMIT || retained > jobs.budget()) {
      retries.state(id, AnalysisRetries.State.TOO_LARGE, "보관 원본 복원/분석이 작업 바이트 한도를 초과합니다. 원본에서 별도 확인하세요.");
      return;
    }
    long token = epoch.get();
    boolean accepted = enqueue(() -> {
      if (closed.get() || token != epoch.get()) return;
      if (!WorkbenchScope.contains(api, original.url())) { retries.state(id, AnalysisRetries.State.SCOPE_EXCLUDED, "현재 Scope 밖 원본입니다. 분석하지 않았습니다."); return; }
      burp.api.montoya.http.message.HttpRequestResponse packet;
      try { packet = original.packet(); }
      catch (RuntimeException error) { retries.state(id, AnalysisRetries.State.EXPIRED, "원본 트래픽이 초기화/만료되었습니다. 다시 수집하세요."); return; }
      try {
        AnalysisInput input = snapshot(packet.request(), packet.response(), id);
        if (input != null) analyzeSnapshot(input, token, true);
      } catch (RuntimeException error) { if (token == epoch.get() && !closed.get()) retries.state(id, AnalysisRetries.State.FAILED, "로컬 재시도 실패 · 원본/지원 형식을 확인하세요."); }
    }, true, retained);
    if (!accepted) retries.state(id, AnalysisRetries.State.QUEUE_FULL, "재시도 대기열 한도 · 대기가 줄어든 뒤 다시 선택하세요.");
  }

  static String packetPreview(burp.api.montoya.core.ByteArray packet) {
    if (packet == null) return "";
    int limit = 65536;
    boolean limited = packet.length() > limit;
    // Ask the host for a bounded slice before copying or decoding a large HTTP message.
    var prefix = limited ? packet.subArray(0, limit) : packet;
    if (prefix == null) return "[패킷 미리보기 제공 불가]";
    byte[] bytes = prefix.getBytes();
    String text;
    if (limited) {
      // The byte boundary may split a UTF-8 character. Preserve complete Korean text before it.
      var decoded = java.nio.CharBuffer.allocate(bytes.length);
      var result =
          java.nio.charset.StandardCharsets.UTF_8
              .newDecoder()
              .decode(java.nio.ByteBuffer.wrap(bytes), decoded, false);
      if (result.isError()) text = HttpText.decode(bytes, null);
      else {
        decoded.flip();
        text = decoded.toString();
      }
    } else text = HttpText.decode(bytes, null);
    return EnvironmentPanel.preview(text) + (limited ? "\n[미리보기 64 KiB 제한]" : "");
  }

  private void analyzeSnapshot(AnalysisInput snapshot, long token, boolean retry) {
    if (token != epoch.get() || closed.get()) return;
    retries.state(snapshot.retryId(), AnalysisRetries.State.RUNNING, "보관 응답을 로컬에서 분석 중");
    try {
      String text = readBody(snapshot.body(), snapshot.encoding(), snapshot.contentType());
      if (token != epoch.get() || closed.get()) return;
      if (text.isBlank() || !TextCandidates.textual(text)) { unsupported.incrementAndGet(); retries.state(snapshot.retryId(), AnalysisRetries.State.UNSUPPORTED, "빈 본문/지원 텍스트 형식 아님"); return; }
      String key = safeSource(snapshot.url());
      String hash=fingerprint(text+"\nDOCUMENT:"+Objects.toString(snapshot.document(),"")+"\nMODULES:"+modules.link(snapshot.url(),text,snapshot.document()).prefix());
      if (!retry && (Objects.equals(cache.fingerprint(key),hash)||(archive!=null&&archive.has(archive.versionKey(key,hash),hash)))) {
        duplicates.incrementAndGet();
        if (token == epoch.get() && !closed.get()) retries.state(snapshot.retryId(), AnalysisRetries.State.REUSED, "동일 내용·문맥의 분석 이력이 있습니다. 기존 결과의 분석 범위를 확인하세요.");
        return;
      }
      String decoded =
          EnvironmentPanel.preview(
              PacketFormat.response(
                  snapshot.raw().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                  snapshot.status(),
                  "",
                  text));
      JsAnalysis.Result analyzed = analyzeText(
          text,
          snapshot.url(),
          false,
          token,
          new String[] {snapshot.request(), snapshot.raw(), decoded}, snapshot.contentType(), snapshot.document());
      if (token != epoch.get() || closed.get()) return;
      if (analyzed != null) retries.state(snapshot.retryId(), analyzed.status().contains("분석 실패") ? AnalysisRetries.State.FAILED : analyzed.truncated() ? AnalysisRetries.State.PARTIAL : AnalysisRetries.State.COMPLETE, analyzed.status());

    } catch (Exception e) {
      if (token != epoch.get() || closed.get()) return;
      failed.incrementAndGet();
      retries.state(snapshot.retryId(), AnalysisRetries.State.FAILED, "압축 해제/디코딩 실패 · 원본 형식 확인 후 재시도");
      recordFailure(snapshot.url(),"압축 해제/디코딩 실패 · "+Objects.toString(snapshot.encoding(),"identity"),token);
    }
  }

  private boolean enqueue(Runnable job, boolean manual) {
    return enqueue(job, manual, 128);
  }

  private synchronized boolean enqueue(Runnable job, boolean manual, long bytes) {
    if (closed.get()) return false;
    beginBackgroundJob();
    long project=monitorProjectGeneration;
    monitorAccepted++;
    if (jobs.offer(()->{
      synchronized(JsAnalysisPanel.this){if(project!=monitorProjectGeneration)return;monitorRunning++;}
      boolean success=false;
      try{job.run();success=true;}
      finally{synchronized(JsAnalysisPanel.this){if(project==monitorProjectGeneration){monitorRunning--;monitorCompleted++;if(!success)monitorFailed++;}}}
    }, bytes,()->{synchronized(JsAnalysisPanel.this){if(project==monitorProjectGeneration)monitorCancelled++;}})) {
      if (manual) status.setText("분석 대기열에 추가했습니다.");
      return true;
    }
    monitorAccepted--;
    overflow.incrementAndGet();
    scheduleBackgroundRefresh();
    if (manual) status.setText("대기열 개수/바이트 한도에 도달했습니다. 잠시 후 다시 분석하세요.");
    return false;
  }

  private synchronized void beginBackgroundJob() {
    if(backgroundJobs==null || backgroundBatch!=null)return;
    var metrics=jobs.metrics();batchCompleted=monitorCompleted;batchFailed=monitorFailed;
    batchCancelled=metrics.cancelled();batchAnalysisFailed=failed.get();batchEpoch=epoch.get();
    long batch=++backgroundBatchSequence;
    backgroundBatch=BackgroundJobs.begin(backgroundJobs,"JS 분석·이력 작업 묶음","js",()->cancelBackgroundAnalysis(batch));
  }

  private void cancelBackgroundAnalysis(long batch) {
    Runnable stop=()->{
      synchronized(JsAnalysisPanel.this){if(backgroundBatch==null || backgroundBatchSequence!=batch)return;}
      if(passive.isSelected())passive.setSelected(false);
      else { epoch.incrementAndGet();jobs.cancelQueued();retries.cancelPending(); }
      scheduleBackgroundRefresh();
    };
    if(SwingUtilities.isEventDispatchThread())stop.run();else SwingUtilities.invokeLater(stop);
  }

  private void scheduleBackgroundRefresh() {
    if(backgroundRefreshPending.compareAndSet(false,true))SwingUtilities.invokeLater(()->{
      backgroundRefreshPending.set(false);refreshBackgroundJob();
    });
  }

  private synchronized void refreshBackgroundJob() {
    if(backgroundBatch==null)return;
    var metrics=jobs.metrics();long completed=Math.max(0,monitorCompleted-batchCompleted);
    long errors=Math.max(0,monitorFailed-batchFailed)+Math.max(0,failed.get()-batchAnalysisFailed);
    if(epoch.get()!=batchEpoch || closed.get())backgroundBatch.stopping("JS 중지 요청 · 시작한 처리 종료 대기");
    long waiting=Math.max(0,monitorAccepted-monitorCompleted-monitorRunning-monitorCancelled);
    backgroundBatch.progress("대기 "+waiting+"개 · 실행 "+monitorRunning+"개 · 실패 "+errors+"개",completed,-1);
    if(jobs.count()==0){
      if(backgroundBatch.cancelRequested() || metrics.cancelled()>batchCancelled || closed.get())backgroundBatch.cancelled("JS 작업 중지 · 이미 완료한 분석 이력은 유지");
      else if(errors>0)backgroundBatch.fail("일부 분석·이력 처리 실패 · 분석 화면에서 근거 확인");
      else backgroundBatch.complete("현재 JS 분석·이력 대기열 처리 완료");
      backgroundBatch=null;
    }
  }

  void resetBackgroundProject() {
    synchronized(this) {
      monitorProjectGeneration++;backgroundBatchSequence++;backgroundBatch=null;
      monitorAccepted=monitorCompleted=monitorFailed=monitorRunning=monitorCancelled=0;
    }
    // Existing raw work is cancelled rather than being attributed to the new project's task group.
    if(passive.isSelected())passive.setSelected(false);
    else {epoch.incrementAndGet();jobs.cancelQueued();retries.cancelPending();}
  }

  static String memory(long bytes) {
    return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
  }

  long cachedBytes() {
    return cache.bytes();
  }

  long queuedBytes() {
    return jobs.bytes();
  }

  static String readBody(HttpResponse response) throws java.io.IOException {
    return readBody(
        response.body().getBytes(),
        response.headerValue("Content-Encoding"),
        response.headerValue("Content-Type"));
  }

  private static String readBody(byte[] bytes, String encoding, String contentType)
      throws java.io.IOException {
    return JsBodyDecoder.decode(bytes,encoding,contentType);
  }

  private void analyzeText(String text, String url, boolean manual, long token, String[] packet) {
    analyzeText(text,url,manual,token,packet,"");
  }

  private void analyzeText(String text, String url, boolean manual, long token, String[] packet, String contentType) {
    analyzeText(text,url,manual,token,packet,contentType,null);
  }

  private JsAnalysis.Result analyzeText(String text, String url, boolean manual, long token, String[] packet, String contentType,String document) {
    if (closed.get() || token != epoch.get()) return null;
    JsModuleLinks.Linked linked;
    try{linked=modules.link(url,text,document);}catch(RuntimeException|LinkageError|StackOverflowError error){linked=new JsModuleLinks.Linked(text,"",List.of());failed.incrementAndGet();}
    String key = safeSource(url), hash = fingerprint(text+"\nDOCUMENT:"+Objects.toString(document,"")+"\nMODULES:"+linked.prefix());
    if(archive!=null)try{archive.load();key=archive.versionKey(key,hash);}catch(Exception ex){onEdt(()->status.setText("분석 이력 읽기 실패 · 메모리 분석 계속"));}
    final String analysisKey=key;
    JsAnalysis.Result result;
    boolean moduleChanged=false;
    boolean legacy=manual&&contentType.isBlank()&&document==null;
    try {
      result = linked.used()?JsModuleLinks.analyze(linked,url,contentType,document):legacy?JsAnalysis.analyze(text,url,contentType):JsBatchAnalysis.analyze(text,url,contentType,document);
    } catch (RuntimeException|LinkageError|StackOverflowError error) {
      failed.incrementAndGet();
      try{
        var fallback=JsBatchAnalysis.analyze(text,url,contentType,document);
        result=partial(fallback,"문맥 분석 실패("+error.getClass().getSimpleName()+") · 원본 구간 후보 보존");
      }catch(RuntimeException|LinkageError|StackOverflowError fallbackError){
        result = new JsAnalysis.Result(List.of(), List.of(), "분석 실패 · "+fallbackError.getClass().getSimpleName()+" · 입력/지원 문법 확인", true);
      }
    }
    // Metadata failures must never replace already recovered endpoints with an empty result.
    try{
      moduleChanged=modules.remember(url,text,document);
      if(archive!=null&&versions.forSource(url).isEmpty()){
        var previous=archive.versions(url);if(!previous.isEmpty()){var prior=previous.get(previous.size()-1);var priorResult=archive.resultMetadata(prior.key());if(priorResult!=null)versions.recordAt(url,prior.fingerprint(),priorResult,archive.time(prior.key()));}
      }
      versions.record(url,hash,result);rememberVersionHash(analysisKey,hash);
      if(!legacy)result=JsFindingGroups.group(result);
    }catch(Exception|LinkageError|StackOverflowError error){failed.incrementAndGet();result=partial(result,"이력/묶음 처리 실패("+error.getClass().getSimpleName()+") · 발견 후보 보존");}
    if (token != epoch.get() || closed.get()) return null;
    if(moduleChanged)for(var dependent:modules.dependents(url))enqueue(()->analyzeText(dependent.text(),dependent.source(),false,token,null,"application/javascript",dependent.document()),false,JsSourceCache.textBytes(dependent.text()));
    JsAnalysis.Result done = result;
    String source = text.substring(0, Math.min(text.length(), JsAnalysis.SOURCE_LIMIT));
    documents.put(analysisKey,Objects.toString(document,manual?url:""));
    if(archive!=null&&token==epoch.get())try{archive.save(analysisKey,text,document,contentType,done,packet,hash);}catch(Exception ex){failed.incrementAndGet();onEdt(()->status.setText("분석 이력 저장 실패 · 디스크 공간/권한 확인"));}
    // Backpressure includes EDT publication: an occupied EDT cannot accumulate completed ASTs.
    onEdt(
        () -> {
          if (closed.get() || token != epoch.get()) return;
          processed.incrementAndGet();
          if (done.truncated()) truncated.incrementAndGet();
          var change = cache.analysis(analysisKey, source, hash, done, packet);
          if (!change.accepted()) {
            status.setText("분석 결과가 캐시 바이트 예산을 초과해 보관하지 않았습니다. 현재 선택 원본은 유지됩니다.");
            refreshProgress();
            return;
          }
          for(String evicted:change.evicted()){documents.remove(evicted);versionHashes.remove(evicted);}
          coverage.accept(done, analysisKey);
          syncSources();
          if (manual || current == null || Objects.equals(sources.getSelectedItem(), analysisKey)) {
            selectSource(analysisKey);
            sources.setSelectedItem(analysisKey);
            render(done);
          } else status.setText("History 분석 저장: " + analysisKey + " · 분석 목록에서 선택하세요.");
          refreshProgress();
        });
    return done;
  }

  static JsAnalysis.Result partial(JsAnalysis.Result result,String note){
    return new JsAnalysis.Result(result.endpoints(),result.secrets(),result.status()+" · "+note,true,result.miner(),result.requestDrafts());
  }

  void render(JsAnalysis.Result r) {
    navigating = true;
    current = r;
    var stored = cache.get(currentSource);
    String[] packet = stored == null ? null : stored.packet();
    if (packet != null) {
      requestPacket.setPacket(packet[0]);
      responsePacket.setPacket(packet[1], packet[2]);
    } else {
      requestPacket.setPacket("직접 분석 · 전송 패킷 없음");
      responsePacket.setPacket("직접 분석 · 응답 패킷 없음");
    }
    endpoints.setRowCount(0);
    secrets.setRowCount(0);
    for (var item : r.endpoints())
      endpoints.addRow(
          new Object[] {
            item.url(),
            item.method(),
            item.kind()+" · "+JsFindingGroups.priority(item),
            item.parameters(),
            item.headers(),
            item.bodyKeys(),
            item.line(),
            item.complete() ? observation.apply(item) : (item.evidence().contains("실행 페이지 미확정")?"실행 페이지 미확정":"동적 값 미해결"),
            "기준 없음"
          });
    for (var item : r.secrets())
      secrets.addRow(
          new Object[] {
            item.rule(), item.masked(), item.fingerprint(), item.line(), item.confidence()
          });
    if(resultView!=null)resultView.refresh();
    renderMiner(r.miner());
    status.setText(r.status());
    String code = cache.source(currentSource);
    if (code == null) code = input.getText();
    sourceWindowStart=0;sourceWindowKey="";
    navigator.bind(code, r, Objects.equals(input.getText(), code));
    navigating = false;
    refreshActions();
  }

  final JTextField resolvedUrl = new JTextField(45);
  final JComboBox<String> resolvedMethod = new JComboBox<>(new String[]{"GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS"});
  JsAnalysis.Endpoint resolving;
  JsRequestDraft.Draft resolvingDraft;
  String resolvingSource = "";
  final TargetWindow resolveWindow =
      new TargetWindow(this, "URL·메서드 확인 · Repeater 초안", this::resolvedContent);

  JPanel resolvedContent() {
    JButton prepare = new JButton("확정하고 초안 준비");
    prepare.setName("wb.js.draft.prepare");
    prepare.addActionListener(
        e -> {
          try {
            var confirmed=new JsAnalysis.Endpoint(resolving.url(),Objects.toString(resolvedMethod.getSelectedItem(),"GET"),
                resolving.kind(),resolving.parameters(),resolving.headers(),resolving.bodyKeys(),resolving.line(),
                resolving.start(),resolving.length(),resolving.complete(),resolving.evidence());
            var draft = JsRequestDraft.confirm(confirmed,resolvedUrl.getText(),confirmed.method(),resolvingDraft);
            if (!WorkbenchScope.contains(api, draft.url()))
              throw new IllegalArgumentException(
                  "Scope 밖 URL입니다. · " + WorkbenchScope.reason(api, draft.url()));
            String draftSource=resolvingSource;
            afterProtectingHistory(draftSource,()->{
            repeater.open(List.of(draft));
            var item =
                new JsAnalysis.Endpoint(
                    draft.url().split("\\?",2)[0],
                    draft.method(),
                    confirmed.kind(),
                    confirmed.parameters(),
                    confirmed.headers(),
                    confirmed.bodyKeys(),
                    confirmed.line(),
                    confirmed.start(),
                    confirmed.length(),
                    true,
                    confirmed.evidence());
            coverage.accept(
                new JsAnalysis.Result(List.of(item), List.of(), "동적 값 수동 확정", false),
                draftSource);
            status.setText("초안 준비 · 값/인증/본문 형식 확인 필요 · 전송 없음");
            });
          } catch (Exception ex) {
            Diagnostics.failure(JsAnalysisPanel.this,"JS에서 API 찾기",ex);
            status.setText(ex.getMessage());
          }
        });
    return TargetWindow.options(
        new JLabel("초안의 경로와 메서드를 확인하세요. 쿼리 값은 복사하지 않습니다."),
        WorkbenchUi.row(new JLabel("초안 메서드"),resolvedMethod),
        WorkbenchUi.row(new JLabel("확정 URL"), resolvedUrl),
        prepare);
  }

  void openResolvedDraft() {
    int row = urlTable.getSelectedRow();
    if (current == null || row < 0) return;
    resolving = current.endpoints().get(urlTable.convertRowIndexToModel(row));
    resolvingSource = currentSource;
    resolvingDraft=current.requestDrafts().get(JsAnalysis.draftKey(resolving));
    resolvedUrl.setText(resolvingDraft==null?resolving.url():resolvingDraft.url());
    resolvedMethod.setSelectedItem(List.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS").contains(resolving.method())?resolving.method():"GET");
    resolveWindow.open();
  }

  void navigateSource(String source, int line, String kind) {
    JsAnalysis.Result r = cache.result(source);
    if (r == null) {
      if(archive!=null){restore(source);return;}
      status.setText("원본이 최근 분석 보관 한도 밖입니다. 해당 파일을 다시 분석하세요.");
      return;
    }
    sources.setSelectedItem(source);
    if (kind.startsWith("source map: ")) {
      String name = kind.substring(12).split(" · GraphQL",2)[0];
      var recovered =
          r.miner().recovered().stream()
              .filter(f -> f.name().equals(name))
              .findFirst()
              .orElse(null);
      if (recovered == null) {
        status.setText("복원 원본을 찾지 못했습니다. 소스맵을 다시 분석하세요.");
        return;
      }
      preparingSource = true;
      try {
        input.setText(recovered.content());
        navigator.invalidate();
      } finally {
        preparingSource = false;
      }
      status.setText("복원 원본: " + name + " · 줄 " + line);
    } else prepareNavigation();
    String code = input.getText();
    int pos = 0;
    for (int n = 1; n < line && pos < code.length(); n++) {
      int next = code.indexOf('\n', pos);
      if (next < 0) {
        pos = code.length();
        break;
      }
      pos = next + 1;
    }
    input.setCaretPosition(pos);
    input.requestFocusInWindow();
  }

  private void locateEndpoint(int row){
    if(current==null||row<0||row>=current.endpoints().size())return;var endpoint=current.endpoints().get(row);
    if(endpoint.kind().startsWith("source map: ")){navigateSource(currentSource,endpoint.line(),endpoint.kind());int start=-1-endpoint.start();if(start>=0&&start<input.getDocument().getLength())input.setCaretPosition(start);return;}
    locatePosition(endpoint.start(),0,row);
  }

  private void locateSecret(int row){
    if(current==null||row<0||row>=current.secrets().size())return;
    locatePosition(current.secrets().get(row).start(),1,row);
  }

  private void locatePosition(int offset,int tab,int row){
    int local=offset-sourceWindowStart;
    if(archive!=null&&(local<0||local>=input.getDocument().getLength())){
      String chosen=currentSource;long generation=selectionGeneration.get();
      enqueue(()->{try{var slice=archive.sourceSlice(chosen,Math.max(0,offset),JsAnalysis.SOURCE_LIMIT);if(slice!=null)onEdt(()->{
        if(!Objects.equals(currentSource,chosen)||generation!=selectionGeneration.get())return;
        preparingSource=true;try{sourceWindowStart=slice.start();sourceWindowKey=chosen;input.setText(slice.text());navigator.bind(slice.text(),navigationSlice(current,slice.start()),true);navigator.at(tab,row);status.setText("원문 구간 "+slice.start()+"–"+(slice.start()+slice.text().length())+" / "+slice.total()+"자 · 원본 위치 "+offset);}finally{preparingSource=false;}
      });}catch(Exception ex){onEdt(()->status.setText("원문 위치 불러오기 실패 · 기록/읽기 한도 확인"));}},true);
    }else navigator.at(tab,row);
  }

  private static JsAnalysis.Result navigationSlice(JsAnalysis.Result result,int start){
    var endpoints=result.endpoints().stream().map(e->new JsAnalysis.Endpoint(e.url(),e.method(),e.kind(),e.parameters(),e.headers(),e.bodyKeys(),e.line(),e.start()<0?e.start():e.start()-start,e.length(),e.complete(),e.evidence())).toList();
    var secrets=result.secrets().stream().map(e->new JsAnalysis.Secret(e.rule(),e.masked(),e.length(),e.fingerprint(),e.line(),e.start()<0?e.start():e.start()-start,e.sourceLength(),e.confidence())).toList();
    return new JsAnalysis.Result(endpoints,secrets,result.status(),result.truncated(),result.miner());
  }

  private void restore(String chosen){
    if(archive==null||chosen.isBlank())return;long token=epoch.get();long selection=selectionGeneration.incrementAndGet();status.setText("분석 이력 불러오는 중");
    enqueue(()->{try{
      var saved=archive.readPreview(chosen);if(saved==null)return;
      String source=chosen.split("#wb-version=",2)[0];var fresh=JsBatchAnalysis.analyze(saved.source(),source,saved.type(),saved.page());
      for(var snapshot:archive.versions(chosen)){var metadata=archive.resultMetadata(snapshot.key());if(metadata!=null){versions.recordAt(chosen,snapshot.fingerprint(),metadata,archive.time(snapshot.key()));rememberVersionHash(snapshot.key(),snapshot.fingerprint());}}
      // Persisted findings retain full-bundle offsets; only visible-prefix draft values are rebuilt.
      var kept=saved.result();var restored=new JsAnalysis.Result(kept.endpoints(),kept.secrets(),kept.status(),kept.truncated(),kept.miner(),fresh.requestDrafts());
      onEdt(()->{if(closed.get()||token!=epoch.get()||selection!=selectionGeneration.get())return;
        var change=cache.analysis(chosen,saved.source().substring(0,Math.min(saved.source().length(),JsAnalysis.SOURCE_LIMIT)),null,restored,saved.packet());
        if(!change.accepted()){status.setText("분석 이력은 디스크에 보존되어 있습니다. 선택 결과가 메모리 예산을 초과했습니다.");return;}
        documents.put(chosen,saved.page());selectSource(chosen);syncSources();sources.setSelectedItem(chosen);render(restored);
      });
    }catch(Exception ex){onEdt(()->status.setText("분석 이력 불러오기 실패 · 기록 파일 확인"));}},true);
  }

  void prepareNavigation() {
    if (current == null) return;
    String code = cache.source(currentSource);
    if (code == null) return;
    if(sourceWindowStart>0&&sourceWindowKey.equals(currentSource)&&navigator.active)return;
    if (!navigator.active || !input.getText().equals(code)) {
      preparingSource = true;
      try {
        input.setText(code);
        sourceUrl.setText(currentSource.equals("직접 입력") ? "" : currentSource);
        navigator.activate();
      } finally {
        preparingSource = false;
      }
    }
  }

  void selectFinding(JsFindingNavigator.Finding f) {
    navigating = true;
    try {
      results.setSelectedIndex(f.tab());
      Component c = results.getComponentAt(f.tab());
      if (c instanceof JScrollPane pane && pane.getViewport().getView() instanceof JTable table) {
        int row = table.convertRowIndexToView(f.row());
        if (row >= 0) {
          table.setRowSelectionInterval(row, row);
          table.scrollRectToVisible(table.getCellRect(row, 0, true));
        }
      }
    } finally {
      navigating = false;
    }
  }

  String maskedReport() {
    if (current == null) return "";
    StringBuilder out = new StringBuilder(current.status()).append("\n");
    for (var u : current.endpoints())
      out.append(u.method())
          .append(" ")
          .append(u.url())
          .append(" · ")
          .append(u.kind())
          .append(" · 줄 ")
          .append(u.line())
          .append(" · params ")
          .append(u.parameters())
          .append(" · headers ")
          .append(u.headers())
          .append("\n");
    for (var map : current.miner().maps())
      out.append("MAP ")
          .append(JsMiner.safeUrl(map.url()))
          .append(" · ")
          .append(map.state())
          .append("\n");
    for (var d : current.miner().dependencies())
      out.append("NPM ").append(d.name()).append(" · ").append(d.status()).append("\n");
    for (var c : current.miner().clouds())
      out.append("CLOUD ").append(c.provider()).append(" · ").append(c.url()).append("\n");
    for (var s : current.secrets())
      out.append(s.rule())
          .append(" · ")
          .append(s.masked())
          .append(" · 지문 ")
          .append(s.fingerprint())
          .append(" · 줄 ")
          .append(s.line())
          .append(" · ")
          .append(s.confidence())
          .append("\n");
    return out.toString();
  }

  void renderMiner(JsMiner.Result r) {
    for (DefaultTableModel m :
        new DefaultTableModel[] {
          domainModel, cloudModel, dependencyModel, mapModel, recoveredModel
        }) m.setRowCount(0);
    for (var d : r.domains())
      domainModel.addRow(new Object[] {d.host(), d.relation(), d.provider(), d.line()});
    for (var c : r.clouds()) cloudModel.addRow(new Object[] {c.provider(), c.url(), c.line()});
    for (var d : r.dependencies())
      dependencyModel.addRow(new Object[] {d.name(), d.scope(), d.origin(), d.status()});
    for (var m : r.maps()) mapModel.addRow(new Object[] {JsMiner.safeUrl(m.url()), m.state()});
    for (var f : r.recovered())
      recoveredModel.addRow(new Object[] {f.name(), f.mapUrl(), f.content().length()});
  }

  private void selectSource(String key) {
    if (!Objects.equals(currentSource, key)) {
      currentSource = key;
      sourceWindowStart=0;sourceWindowKey="";
      selectionGeneration.incrementAndGet();
    }
    cache.select(key);
  }

  private record PreviewContext(long epoch, long selection, String source) {}

  private PreviewContext previewContext() {
    return new PreviewContext(epoch.get(), selectionGeneration.get(), currentSource);
  }

  JsMiner.Reply fetch(String url, boolean inScope) throws Exception {
    return fetch(url, inScope, previewContext());
  }

  private JsMiner.Reply fetch(String url, boolean inScope, PreviewContext owner) throws Exception {
    if (closed.get()) throw new InterruptedException("확장 종료");
    HttpRequest q = HttpRequest.httpRequestFromUrl(url);
    if (inScope && !WorkbenchScope.contains(api, q))
      throw new IllegalArgumentException("Scope 밖 map 요청 차단");
    var rr =
        api.http()
            .sendRequest(
                q,
                burp.api.montoya.http.RequestOptions.requestOptions()
                    .withResponseTimeout(5000)
                    .withRedirectionMode(burp.api.montoya.http.RedirectionMode.NEVER));
    if (rr == null || rr.response() == null) throw new IllegalArgumentException("응답 없음");
    if (rr.response().body().length() > JsMiner.MAP_LIMIT)
      throw new IllegalArgumentException("응답 크기 제한");
    String body = readBody(rr.response());
    String request = EnvironmentPanel.preview(q.toString()),
        raw = EnvironmentPanel.preview(rr.response().toString());
    String shown =
        EnvironmentPanel.preview(
            PacketFormat.response(
                rr.response().toByteArray().getBytes(), rr.response().statusCode(), "", body));
    onEdt(
        () -> {
          if (!closed.get()
              && owner.epoch() == epoch.get()
              && owner.selection() == selectionGeneration.get()
              && Objects.equals(owner.source(), currentSource)) {
            requestPacket.setPacket(request);
            responsePacket.setPacket(raw, shown);
          }
        });
    return new JsMiner.Reply(rr.response().statusCode(), body);
  }

  void applyMiner(String key, JsAnalysis.Result original, JsMiner.Result updated, long token) {
    var result=JsFindingGroups.group(JsMapRecovery.merge(original,updated,key,documents.get(key)));
    if(archive!=null)try{archive.updateResult(key,result);}catch(Exception ex){onEdt(()->status.setText("소스맵 분석 이력 저장 실패"));}
    onEdt(
        () -> {
          if (closed.get() || token != epoch.get() || cache.result(key) != original) return;
          if (!cache.result(key, result).accepted()) {
            status.setText("소스맵/NPM 결과가 캐시 바이트 예산을 초과해 보관하지 않았습니다.");
            refreshProgress();
            return;
          }
          coverage.accept(result, key);
          syncSources();
          if (currentSource.equals(key)) render(result);
          refreshProgress();
        });
  }

  void discover() {
    if (current == null || api == null) return;
    String key = currentSource;
    long token = epoch.get();
    PreviewContext owner = previewContext();
    enqueue(
        () -> {
          try {
            JsAnalysis.Result original = cache.result(key);
            if (original == null) return;
            var m = original.miner();
            var maps = new ArrayList<>(m.maps());
            var recovered = new ArrayList<>(m.recovered());
            for (String url : JsMiner.mapCandidates(key, m.maps())) {
              if (closed.get() || token != epoch.get()) return;
              try {
                var reply = fetch(url, true, owner);
                String state = "HTTP " + reply.status();
                if (reply.status() == 200) {
                  var files = JsMiner.sourceMap(reply.body(), url);
                  JsMiner.appendRecovered(recovered, files);
                  state += " · 원본 " + files.size() + "개";
                  String mapText = reply.body();
                  onEdt(
                      () -> {
                        if (!closed.get() && token == epoch.get()) {
                          cache.sourceOnly(JsMiner.safeUrl(url), mapText);
                          syncSources();
                        }
                      });
                }
                maps.removeIf(ref -> ref.url().equals(url));
                maps.add(new JsMiner.MapRef(url, state));
              } catch (Exception ex) {
                maps.removeIf(ref -> ref.url().equals(url));
                maps.add(new JsMiner.MapRef(url, ex.getMessage()));
              }
            }
            applyMiner(
                key,
                original,
                new JsMiner.Result(
                    m.domains(),
                    m.clouds(),
                    m.dependencies(),
                    List.copyOf(maps),
                    List.copyOf(recovered),
                    m.note()),
                token);
          } catch (Exception e) {Diagnostics.failure(JsAnalysisPanel.this,"JS에서 API 찾기",e);
            onEdt(
                () -> {
                  if (!closed.get() && token == epoch.get())
                    status.setText("소스맵 탐색: " + e.getMessage());
                });
          }
        },
        true);
  }

  void checkRegistry() {
    if (current == null || api == null) return;
    String key = currentSource, root = registry.getText().strip();
    long token = epoch.get();
    PreviewContext owner = previewContext();
    enqueue(
        () -> {
          JsAnalysis.Result original = cache.result(key);
          if (original == null) return;
          var m = original.miner();
          var checked = new ArrayList<JsMiner.Dependency>();
          for (var d : m.dependencies()) {
            if (closed.get() || token != epoch.get()) return;
            checked.add(JsMiner.checkDependency(d, root, url -> fetch(url, false, owner)));
          }
          applyMiner(
              key,
              original,
              new JsMiner.Result(
                  m.domains(), m.clouds(), List.copyOf(checked), m.maps(), m.recovered(), m.note()),
              token);
        },
        true);
  }

  public void clear() {
    retries.clear();
    retryWindow.closeWindow();
    analysisWindow.closeWindow();
    archiveSettings.closeWindow();
    detailsWindow.closeWindow();
    packetWindow.closeWindow();
    openedPacket=null;openedSource="";openedFinding="";
    resolveWindow.closeWindow();
    resolving = null;resolvingDraft=null;
    navigator.reset();
    epoch.incrementAndGet();
    jobs.cancelQueued();archiveRefreshPending.set(false);
    requestPacket.setPacket(null);
    responsePacket.setPacket(null);
    current = null;
    historyModel.setRowCount(0);
    archivePage=new JsArchive.Page(List.of(),0,0,false);historyOffset=0;historyQueryGeneration.incrementAndGet();
    cache.clear();
    documents.clear();
    modules.clear();versions.clear();versionHashes.clear();trafficIndex.clear();
    if(archive!=null&&!closed.get())enqueue(()->{try{archive.clear();onEdt(()->{archivePage=new JsArchive.Page(List.of(),0,0,false);displayedArchiveRevision=-1;requestArchivePage();});}catch(Exception ex){onEdt(()->status.setText("분석 이력 삭제 실패 · 파일 권한 확인"));}},true);
    totals.update(List.of());
    selectSource("");
    renderMiner(new JsMiner.Result(List.of(), List.of(), List.of(), List.of(), List.of(), ""));
    sources.removeAllItems();
    input.setText("");
    sourceUrl.setText("");
    endpoints.setRowCount(0);
    secrets.setRowCount(0);
    navigator.reset();
    status.setText("분석 초기화 완료");
    refreshActions();
    refreshProgress();
  }

  @Override
  public void close() {
    if(resultView!=null)resultView.close();
    targetSettings.close();
    repeater.close();
    analysisWindow.close();
    archiveSettings.close();
    retryWindow.close();
    detailsWindow.close();
    packetWindow.close();
    resolveWindow.close();
    if (historyMonitor != null) historyMonitor.close();
    if (progressTimer != null) progressTimer.stop();
    closed.set(true);
    epoch.incrementAndGet();
    jobs.close();
    clear();
  }
}

