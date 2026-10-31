package kr.featuremapper;

import burp.api.montoya.*;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.awt.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import javax.swing.table.*;
import javax.swing.tree.*;

/** Reusable mapping workspace. The standalone entry point and Workbench share this implementation. */
public final class FeatureMapperWorkspace implements HttpHandler, AutoCloseable {
  public interface EmbeddedHost {
    String captureMode();
    void toggleCapture();
    boolean acceptsUrl(String url);
    void failure(String stage, Exception error);
    default Map<String,Object> inspectionCommand(String origin) { return Map.of("state","idle"); }
    default void inspectionEvent(Map<String,Object> event) {}
    default boolean supportsAssessmentTasks() { return false; }
    default boolean allowsAssessmentUrl(String url) { return acceptsUrl(url); }
    default void assessmentDraft(String sourceId, String title, List<String> endpoints) {
      throw new UnsupportedOperationException("점검 업무 연결을 지원하지 않는 호스트입니다.");
    }
  }

  private EmbeddedHost embeddedHost;

  public JPanel initializeEmbedded(MontoyaApi api, EmbeddedHost host) {
    embeddedHost = Objects.requireNonNull(host);
    initialize(api);
    return root;
  }

  public JPanel component() { return root; }

  public void refreshCaptureState() { updateIndicators(); }

  public boolean isAssessmentSourceCurrent(String sourceId) {
    if (closed.get() || selectedFeature == null || mapper == null) return false;
    return Objects.equals(sourceId, mapper.assessmentSourceId(selectedFeature));
  }

  public boolean isAssessmentSourceCurrent(String sourceId, List<String> endpoints) {
    if (!isAssessmentSourceCurrent(sourceId)) return false;
    try { return mapper.assessmentCandidate(selectedFeature).endpoints().equals(endpoints); }
    catch (IllegalArgumentException expired) { return false; }
  }

  @Override public void close() { unload(); }
  volatile Mapper mapper;
  BrowserBridge bridge;
  private String browserUnavailable = "브라우저 연결 준비 전";
  private MontoyaApi api;
  private final Object projectLock = new Object();
  private final ConcurrentHashMap<Integer, Long> starts = new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicLong revision = new AtomicLong(), errors = new AtomicLong();
  private final ThreadPoolExecutor io =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(16),
          r -> {
            Thread t = new Thread(r, "feature-mapper-files");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());
  private final AtomicLong previewCoalesced = new AtomicLong();
  private final ThreadPoolExecutor previews =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(1),
          r -> {
            Thread t = new Thread(r, "feature-mapper-preview");
            t.setDaemon(true);
            return t;
          },
          (task, executor) -> {
            if (executor.isShutdown()) return;
            if (executor.getQueue().poll() != null) previewCoalesced.incrementAndGet();
            if (!executor.getQueue().offer(task)) throw new RejectedExecutionException("미리보기 종료 중");
          });
  private JPanel root;
  private final Diagnostics diagnostics = new Diagnostics();
  private final JButton logButton = new JButton("로그");
  private final JButton captureButton = new JButton();
  private JButton featureEditButton, featureAssessmentButton, classifyButton, repeaterButton;
  interface DialogOpener { JDialog open(Component owner, String title, Component content, Runnable closed); }
  DialogOpener dialogOpener = MapperUi::window;
  private JDialog helpWindow, settingsWindow, logWindow;
  private JPanel settingsContent, logContent;
  private final JLabel status = new JLabel("사이트를 사용하면 기능을 자동으로 분류합니다.");
  private final JTextArea browserStatus = new JTextArea(4, 48);
  private final JCheckBox scopeOnly = new JCheckBox("Burp Scope만 수집", false);
  private final JCheckBox enabled = new JCheckBox("자동 수집", true);
  private final AtomicBoolean collect = new AtomicBoolean(true), scope = new AtomicBoolean(false);
  private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("자동 발견 기능");
  private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
  private final JTree tree = new JTree(treeModel);
  private final JToggleButton treeToggle = new JToggleButton("기능 목록 · 접기",true);
  private final DefaultTableModel rows =
      new DefaultTableModel(new String[] {"NO", "메서드", "URL", "상태", "세션", "실행", "매핑"}, 0) {
        public boolean isCellEditable(int r, int c) {
          return false;
        }
      };
  private final JTable table = new JTable(rows);
  private final JTextArea requestText = new JTextArea(),
      responseText = new JTextArea(),
      explanation = new JTextArea();
  private final JComboBox<String> runs = new JComboBox<>();
  private final JComboBox<String> sessions = new JComboBox<>();
  private final JTextField search = new MapperUi.Search();
  private List<Mapper.PacketRow> shown = List.of();
  private Mapper shownOwner;
  private String selectedFeature;
  private javax.swing.Timer timer;
  private long rendered = -1;
  private boolean rebuilding;
  private final AtomicLong selectionGeneration = new AtomicLong();

  public void initialize(MontoyaApi api) {
    if (this.api != null) throw new IllegalStateException("Mapper는 이미 초기화됐습니다.");
    this.api = Objects.requireNonNull(api);
    if (embeddedHost == null) api.extension().setName("Feature Mapper KR " + BuildInfo.VERSION);
    try {
      mapper = new Mapper();
      try {
        bridge =
          new BrowserBridge(
              event -> {
                if ("inspection".equals(event.get("type"))) {
                  if(!closed.get()&&embeddedHost!=null)embeddedHost.inspectionEvent(event);
                  return;
                }
                synchronized (projectLock) {
                  if (!closed.get() && (embeddedHost == null
                      || embeddedHost.acceptsUrl(Objects.toString(event.get("url"), "")))) {
                    mapper.browser(event);
                    revision.incrementAndGet();
                  }
                }
              }, origin -> !closed.get()&&embeddedHost!=null?embeddedHost.inspectionCommand(origin):Map.of("state","idle"));
      } catch (NoClassDefFoundError missing) {
        // Burp may use a reduced runtime or an extension loader without access to this module.
        // Do not turn an unrelated linkage defect into an apparently successful initialization.
        if (missing.getMessage() == null
            || !missing.getMessage().replace('.', '/').startsWith("com/sun/net/httpserver/")) throw missing;
        browserUnavailable = "현재 Burp Java에서 jdk.httpserver 클래스를 불러올 수 없습니다. "
            + "브라우저 도우미·SPA 연결을 사용할 수 없습니다. "
            + "사용 중인 Burp 버전이 지원하는 완전한 JDK로 Burp JAR를 실행하고 "
            + "jdk.httpserver 모듈 접근을 확인한 뒤 확장을 다시 추가하세요.";
        bridgeUnavailable(missing);
      } catch (IOException unavailable) {
        browserUnavailable = "브라우저 연결용 로컬 서버를 시작하지 못했습니다. "
            + "브라우저 도우미·SPA 연결을 사용할 수 없습니다. "
            + "Burp의 로컬 loopback 연결 권한을 확인한 뒤 확장을 다시 추가하세요.";
        bridgeUnavailable(unavailable);
      }
      if (SwingUtilities.isEventDispatchThread()) buildUi();
      else SwingUtilities.invokeAndWait(this::buildUi);
      if (embeddedHost == null) {
        api.http().registerHttpHandler(this);
        api.extension().registerUnloadingHandler(this::unload);
      }
      api.logging().logToOutput("Feature Mapper KR " + BuildInfo.VERSION + " · 자동 매핑 준비 완료");
    } catch (Exception e) {
      unload();
      throw new IllegalStateException("Feature Mapper 초기화 실패", e);
    }
  }

  private void bridgeUnavailable(Throwable error) {
    diagnostics.record("브라우저 연결 시작", error);
    api.logging().logToOutput("Feature Mapper · 브라우저 연결 사용 불가 · " + browserUnavailable
        + " HTTP 수집·자동 매핑과 HTTP 통합 점검은 계속 사용할 수 있습니다.");
  }

  public void requestStarted(int messageId) {
    if (!closed.get()) {
      starts.put(messageId, System.currentTimeMillis());
      if (starts.size() > 10000) {
        long cutoff = System.currentTimeMillis() - 120000;
        starts.entrySet().removeIf(e -> e.getValue() < cutoff);
      }
    }
  }

  public long requestFinished(int messageId) {
    Long started = starts.remove(messageId);
    return started == null ? System.currentTimeMillis() : started;
  }

  public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent request) {
    requestStarted(request.messageId());
    return RequestToBeSentAction.continueWith(request, request.annotations());
  }

  public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived response) {
    long started = requestFinished(response.messageId());
    if (!closed.get() && collect.get()) {
      HttpRequest request = response.initiatingRequest();
      if (!scope.get() || request.isInScope())
        observeCaptured(started, response.toolSource().toolType().toString(), request, response);
    }
    return ResponseReceivedAction.continueWith(response, response.annotations());
  }

  /** Receives one packet already admitted by the host. Workbench calls this off the HTTP callback. */
  public void observeCaptured(long started, String tool, HttpRequest request,
      burp.api.montoya.http.message.responses.HttpResponse response) {
    if (closed.get()) return;
    try {
      Mapper.Input input = new Mapper.Input(started, tool, request.method(), request.url(),
          response == null ? 0 : response.statusCode(), request.toByteArray().getBytes(),
          response == null ? new byte[0] : response.toByteArray().getBytes(),
          request.body().getBytes(), response == null ? new byte[0] : response.body().getBytes(),
          headerMap(request.headers()), response == null ? Map.of() : headerMap(response.headers()));
      synchronized (projectLock) {
        if (!closed.get()) {
          mapper.accept(input);
          revision.incrementAndGet();
        }
      }
    } catch (Exception error) { failure("패킷 보관·매핑", error); }
  }

  private static Map<String, String> headerMap(
      List<burp.api.montoya.http.message.HttpHeader> headers) {
    Map<String, String> out = new LinkedHashMap<>();
    for (var h : headers == null ? List.<burp.api.montoya.http.message.HttpHeader>of() : headers) {
      // Mapping metadata is best effort; malformed header values must not discard the original.
      if (h != null && h.name() != null && h.value() != null)
        out.merge(h.name().toLowerCase(Locale.ROOT), h.value(), (a, b) -> a + "; " + b);
    }
    return out;
  }

  private void failure(String stage, Exception error) {
    diagnostics.record(stage, error);
    if (embeddedHost != null) embeddedHost.failure(stage, error);
    long n = errors.incrementAndGet();
    if (n <= 3 || (n & (n - 1)) == 0)
      try {
        api.logging()
            .logToError(
                "Feature Mapper · "
                    + stage
                    + " · "
                    + error.getClass().getSimpleName()
                    + " · "
                    + n
                    + "회");
      } catch (Exception ignored) {
      }

  }

  private JButton button(String name, Runnable action) {
    JButton b = new JButton(name);
    b.addActionListener(
        e -> {
          try {
            action.run();
          } catch (Exception ex) {
            failure(name, ex);
          }
        });
    return b;
  }

  /** Mapper is independently packaged, so its responsive context bar stays local. */
  private static JPanel localToolbar(Component context,Component actions){
    JPanel bar=new JPanel(new BorderLayout(8,4)){
      boolean stacked;
      void adapt(){
        boolean next=getWidth()>0&&getWidth()<context.getMinimumSize().width+actions.getPreferredSize().width+16;
        if(next!=stacked){stacked=next;remove(actions);add(actions,next?BorderLayout.SOUTH:BorderLayout.EAST);}
      }
      @Override public void doLayout(){adapt();super.doLayout();}
      @Override public Dimension getPreferredSize(){adapt();return super.getPreferredSize();}
    };
    bar.add(context);bar.add(actions,BorderLayout.EAST);return bar;
  }

  private void refreshFeatureActions(){
    boolean selected=false;
    synchronized(projectLock){selected=mapper!=null&&selectedFeature!=null&&mapper.features.containsKey(selectedFeature);}
    if(featureEditButton!=null)featureEditButton.setEnabled(selected);
    if(featureAssessmentButton!=null)featureAssessmentButton.setEnabled(selected);
  }

  private void refreshPacketActions(){
    boolean selected=!selectedPackets().isEmpty();
    if(classifyButton!=null)classifyButton.setEnabled(selected);
    if(repeaterButton!=null)repeaterButton.setEnabled(selected);
  }

  private void buildUi() {
    root = new JPanel(new BorderLayout(8, 8));
    root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    root.setName("fm.root");
    if (embeddedHost != null)
      root.putClientProperty("wb.embedded.style", (Runnable) () -> MapperUi.style(root));
    enabled.addActionListener(e -> { collect.set(enabled.isSelected()); updateIndicators(); });
    scopeOnly.addActionListener(e -> { scope.set(scopeOnly.isSelected()); updateIndicators(); });
    JButton help = button("기능 안내", this::showHelp), settings = button("설정", this::showSettings);
    help.setName("fm.navigation.help"); settings.setName("fm.navigation.settings"); logButton.setName("fm.navigation.logs");
    logButton.addActionListener(e -> showLogs());
    JPanel header = new JPanel(new BorderLayout(8,0));
    header.add(new JLabel("기능·트래픽 매핑"));
    JPanel navigation = MapperUi.row(help,settings,logButton); navigation.setName("fm.navigation.actions");
    header.add(navigation,BorderLayout.EAST);
    captureButton.setName("fm.capture"); captureButton.putClientProperty("fm.capture",true); captureButton.addActionListener(e -> {
      if (embeddedHost == null) enabled.doClick(0);
      else { embeddedHost.toggleCapture(); updateIndicators(); }
    });
    JPanel projectActions = MapperUi.row(new JLabel("프로젝트"),button("열기 ▾", () -> chooseFile(false)),button("저장", () -> chooseFile(true)));
    projectActions.setName("wb.actions.mapper.project");
    JPanel captureActions=MapperUi.row(captureButton);captureActions.setName("wb.actions.mapper.capture");
    if(embeddedHost!=null)captureActions.add(button("매핑 설정",this::showSettings));
    JPanel toolbar = new JPanel(new BorderLayout(0,6)); if (embeddedHost == null) toolbar.add(header,BorderLayout.NORTH);
    JPanel context=localToolbar(projectActions,captureActions);context.setName("wb.actions.mapper.context");toolbar.add(context);
    root.add(toolbar, BorderLayout.NORTH);
    requestText.setName("fm.request");
    responseText.setName("fm.response");
    explanation.setName("fm.explanation");
    tree.setName("fm.feature.tree");tree.setRootVisible(false);tree.setShowsRootHandles(true);javax.swing.ToolTipManager.sharedInstance().registerComponent(tree);
    DefaultTreeCellRenderer tr = new DefaultTreeCellRenderer();
    tr.putClientProperty("html.disable", true);
    tree.setCellRenderer(tr);
    tree.addTreeSelectionListener(
        e -> {
          if (rebuilding) return;
          Object selected = tree.getLastSelectedPathComponent();
          if (selected instanceof DefaultMutableTreeNode node
              && node.getUserObject() instanceof FeatureNode f) selectedFeature = f.id;
          else selectedFeature = null;
          fillRows();refreshFeatureActions();
        });
    JPanel left = new JPanel(new BorderLayout(4, 4));
    left.add(new JScrollPane(tree));
    featureEditButton=button("편집",this::editFeature);featureEditButton.setName("fm.feature.edit");
    featureEditButton.setToolTipText("선택 기능의 이름·상태·메모 편집");
    featureEditButton.getAccessibleContext().setAccessibleName("기능 이름·상태·메모");
    JPanel actions=MapperUi.row(featureEditButton);actions.setName("wb.actions.mapper.feature");
    if (embeddedHost != null && embeddedHost.supportsAssessmentTasks()) {
      featureAssessmentButton = button("업무 연결", this::linkAssessment);
      featureAssessmentButton.setName("fm.assessment.link");
      featureAssessmentButton.getAccessibleContext().setAccessibleName("점검 업무로 연결");
      featureAssessmentButton.setToolTipText("선택 기능의 API로 업무 초안을 만듭니다. 계정·범위 검토 후 연결하며 완료로 표시하지 않습니다.");
      actions.add(featureAssessmentButton);
    }
    JPanel treeBody = new JPanel(new BorderLayout(0, 4));
    treeBody.add(left.getComponent(0)); treeBody.add(actions, BorderLayout.NORTH);refreshFeatureActions();
    left.removeAll();

    treeToggle.setName("fm.tree.toggle");
    left.add(treeToggle, BorderLayout.NORTH); left.add(treeBody);
    treeToggle.addActionListener(e -> {
      treeBody.setVisible(treeToggle.isSelected());
      treeToggle.setText(treeToggle.isSelected() ? "기능 목록 · 접기" : "기능 목록 · 펼치기");
      left.revalidate(); left.repaint();
    });
    table.setName("fm.history");
    table.setAutoCreateRowSorter(true);
    table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    int[] widths={50,75,400,65,150,80,80},minimums={40,60,220,55,80,60,60};
    for(int i=0;i<widths.length;i++){table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);table.getColumnModel().getColumn(i).setWidth(widths[i]);table.getColumnModel().getColumn(i).setMinWidth(minimums[i]);}
    DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
    renderer.putClientProperty("html.disable", true);
    table.setDefaultRenderer(Object.class, renderer);
    table
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting()) {showSelected();refreshPacketActions();}
            });
    runs.setPreferredSize(new Dimension(135,36)); sessions.setPreferredSize(new Dimension(135,36));
    JPanel filterContext=MapperUi.row(new JLabel("실행"),runs,new JLabel("세션"),sessions);
    JPanel filters = new JPanel(new BorderLayout(10,4)) {
      boolean stacked;
      private void adapt(){
        boolean next=getWidth()>0&&getWidth()<filterContext.getPreferredSize().width+170;
        if(next!=stacked){stacked=next;remove(filterContext);add(filterContext,next?BorderLayout.NORTH:BorderLayout.WEST);}
      }
      @Override public void doLayout(){adapt();super.doLayout();}
      @Override public Dimension getPreferredSize(){adapt();return super.getPreferredSize();}
    };
    filters.setName("fm.filters");
    filters.add(filterContext,BorderLayout.WEST);
    classifyButton=button("분류 수정",this::movePackets);classifyButton.setName("fm.packet.classify");
    repeaterButton=button("Repeater",this::sendToRepeater);repeaterButton.setName("fm.packet.repeater");
    JPanel packetActions=MapperUi.row(classifyButton,repeaterButton);packetActions.setName("wb.actions.mapper.packets");
    filters.add(search);refreshPacketActions();
    JPanel selectionFooter=localToolbar(new JLabel("선택 요청 작업"),packetActions);
    selectionFooter.setName("wb.placement.mapper.selection");
    selectionFooter.setBorder(BorderFactory.createMatteBorder(1,0,0,0,MapperUi.line()));
    runs.addActionListener(
        e -> {
          if (!rebuilding) fillRows();
        });
    sessions.addActionListener(
        e -> {
          if (!rebuilding) fillRows();
        });
    search
        .getDocument()
        .addDocumentListener(
            new javax.swing.event.DocumentListener() {
              public void insertUpdate(javax.swing.event.DocumentEvent e) {
                fillRows();
              }

              public void removeUpdate(javax.swing.event.DocumentEvent e) {
                fillRows();
              }

              public void changedUpdate(javax.swing.event.DocumentEvent e) {
                fillRows();
              }
            });
    for (JTextArea a : List.of(requestText, responseText, explanation)) {
      a.setEditable(false);
      a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
    }
    requestText.putClientProperty("fm.packet",true); responseText.putClientProperty("fm.packet",true);
    explanation.setLineWrap(true);
    explanation.setWrapStyleWord(true);
    JSplitPane packets =
        new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            scroll("요청", requestText),
            scroll("응답", responseText));
    packets.setName("fm.messages"); MapperUi.split(packets,.5);
    JTabbedPane details = new JTabbedPane();
    details.addTab("요청·응답", packets);
    details.addTab("매핑 근거", new JScrollPane(explanation));
    JPanel top = new JPanel(new BorderLayout());
    top.add(filters, BorderLayout.NORTH);
    top.add(new JScrollPane(table));
    top.add(selectionFooter,BorderLayout.SOUTH);
    JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, details);
    main.setName("fm.workspace"); MapperUi.split(main,.40);
    JSplitPane all = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, main);
    all.setName("fm.navigation.split"); MapperUi.split(all,.23);
    all.putClientProperty("fm.initial.width",230); all.setResizeWeight(0);
    all.setDividerLocation(230);
    treeToggle.addActionListener(e->{boolean expanded=treeToggle.isSelected();left.setMinimumSize(new Dimension(expanded?160:40,60));all.setDividerLocation(expanded?230:48);all.putClientProperty("fm.initial.width",expanded?230:48);treeToggle.setText(expanded?"기능 목록 · 접기":">");treeToggle.setToolTipText(expanded?"기능 목록 접기":"기능 목록 펼치기");treeToggle.getAccessibleContext().setAccessibleName(treeToggle.getToolTipText());});
    root.add(all);

    api.userInterface().applyThemeToComponent(root);
    MapperUi.style(root); Diagnostics.bind(root,diagnostics); MapperUi.install(root);
    if (embeddedHost == null) api.userInterface().registerSuiteTab("기능 자동 매핑", root);
    timer =
        new javax.swing.Timer(
            500,
            e -> {
              try {
                if (root.isShowing()) { updateIndicators(); if (revision.get() != rendered) refresh(); }
              } catch (Exception ex) {
                failure("화면 갱신", ex);
              }
            });
    timer.start();
    refresh(); updateIndicators();
  }

  private JPanel scroll(String title, JTextArea a) {
    return MapperUi.card(title,new JScrollPane(a));
  }

  private void updateIndicators() {
    captureButton.setText(embeddedHost == null
        ? (collect.get() ? (scope.get() ? "SCOPE 수집" : "전체 수집") : "OFF")
        : embeddedHost.captureMode());
    captureButton.setToolTipText(embeddedHost == null ? "수집 전환 · 세부 범위는 설정에서 변경"
        : "Workbench 공통 수집: OFF ↔ SCOPE · 요청·응답 목록과 함께 변경");
    String label = diagnostics.size() == 0 ? "로그" : "로그 (" + diagnostics.size() + ")";
    if (!label.equals(logButton.getText())) logButton.setText(label);
    logButton.setForeground(diagnostics.size() == 0 ? MapperUi.ink() : new Color(210,70,80));
    if (mapper != null) {
      String summary = browserSummary();
      if (!summary.equals(browserStatus.getText())) browserStatus.setText(summary);
    }
  }

  private void showHelp() {
    if (closed.get()) return;
    if(helpWindow!=null&&helpWindow.isDisplayable()){helpWindow.toFront();return;}
    JTextArea text = new JTextArea("1. Burp를 통해 사이트를 사용하면 요청·응답이 기능별로 연결됩니다.\n\n2. 왼쪽에서 기능을 선택하고 실행·세션·검색으로 좁힙니다. 매핑 근거 탭에서 연결 이유를 확인하세요.\n\n3. Repeater에 준비는 요청만 옮깁니다. 실제 전송은 Repeater의 Send에서 직접 실행합니다.\n\n4. 설정에서 자동 수집과 Burp Scope 조건을 바꾸고 브라우저 도우미를 연결할 수 있습니다. 브라우저의 분석할 사이트를 활성화한 뒤 새로고침하세요.\n\n5. 프로젝트 저장에는 전체 원본 패킷이 포함됩니다. 로그에는 오류 단계와 코드 위치만 기록합니다.");
    text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);
    helpWindow=dialogOpener.open(root,"Feature Mapper · 기능 안내",new JScrollPane(text),()->helpWindow=null);
  }

  private void showSettings() {
    if (closed.get()) return;
    if(settingsWindow!=null&&settingsWindow.isDisplayable()){settingsWindow.toFront();return;}
    if(settingsContent==null){
      settingsContent = new JPanel(); settingsContent.setLayout(new BoxLayout(settingsContent,BoxLayout.Y_AXIS)); settingsContent.setName("fm.settings");
      if (embeddedHost == null) settingsContent.add(MapperUi.row(enabled,scopeOnly));
      else settingsContent.add(new JLabel("수집 범위는 Workbench 요청·응답 목록의 OFF / SCOPE를 따릅니다."));
      settingsContent.add(MapperUi.row(button("브라우저 연결",this::showBridge),button("새 프로젝트",this::newProject)));
      browserStatus.setName("fm.browser.status");
      browserStatus.setEditable(false); browserStatus.setLineWrap(true); browserStatus.setWrapStyleWord(true);
      browserStatus.setText(browserSummary());
      settingsContent.add(browserStatus);
    }
    settingsWindow=dialogOpener.open(root,"Feature Mapper · 설정",settingsContent,()->settingsWindow=null);
  }

  private void showLogs() {
    if (closed.get()) return;
    if(logWindow!=null&&logWindow.isDisplayable()){logWindow.toFront();return;}
    logContent=diagnostics.panel(()->"패킷 "+mapper.count()+" · 오류 "+errors.get()+" · "+(collect.get()?(scope.get()?"Burp Scope 수집":"전체 수집"):"수집 중지"));
    MapperUi.style(logContent);
    logWindow=dialogOpener.open(root,"Feature Mapper · 로그",logContent,()->logWindow=null);
    if(logWindow!=null)logWindow.setMinimumSize(new Dimension(680,420));
  }

  record FeatureNode(String id, String label) {
    public String toString() {
      return label;
    }
  }

  private void refresh() {
    if (closed.get()) return;
    rendered = revision.get();
    rebuilding = true;
    try {
      // Feature counts are indexed: no feature-by-feature full packet scan.
      List<Mapper.FeatureSummary> summaries;
      synchronized (projectLock) {
        summaries = mapper.featureSummaries();
      }
      treeToggle.putClientProperty("fm.feature.count",summaries.size());treeToggle.repaint();
      Map<String, DefaultMutableTreeNode> existing = new HashMap<>();
      for (int i = 0; i < treeRoot.getChildCount(); i++) {
        var group = (DefaultMutableTreeNode) treeRoot.getChildAt(i);
        for (int j = 0; j < group.getChildCount(); j++) {
          var node = (DefaultMutableTreeNode) group.getChildAt(j);
          if (node.getUserObject() instanceof FeatureNode f) existing.put(f.id, node);
        }
      }
      Map<String, DefaultMutableTreeNode> groups = new LinkedHashMap<>();
      for (int i = 0; i < treeRoot.getChildCount(); i++) {
        var group = (DefaultMutableTreeNode) treeRoot.getChildAt(i);
        groups.put(String.valueOf(group.getUserObject()), group);
      }
      Set<String> active = new HashSet<>();
      for (var f : summaries) {
        active.add(f.id());
        String key = f.origin() + " / " + f.entity();
        var parent = groups.get(key);
        if (parent == null) {
          parent = new DefaultMutableTreeNode(key);
          groups.put(key, parent);
          treeModel.insertNodeInto(parent, treeRoot, treeRoot.getChildCount());
        }
        FeatureNode value =
            new FeatureNode(f.id(), f.name() + " [" + f.packets() + "건 · " + f.state() + "]");
        var node = existing.get(f.id());
        if (node == null) {
          node = new DefaultMutableTreeNode(value);
          treeModel.insertNodeInto(node, parent, parent.getChildCount());
        } else if (!value.equals(node.getUserObject())) {
          node.setUserObject(value);
          treeModel.nodeChanged(node);
        }
      }
      for (var entry : existing.entrySet())
        if (!active.contains(entry.getKey())) treeModel.removeNodeFromParent(entry.getValue());
      for (var group : groups.values())
        if (group.getChildCount() == 0) treeModel.removeNodeFromParent(group);
      tree.expandPath(new TreePath(treeRoot.getPath()));
      for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
    } finally {
      rebuilding = false;
    }
    fillRows();
    try {
      status.setText(
          "보관 "
              + mapper.count()
              + "건 · 기능 "
              + mapper.featureSummaries().size()
              + "개 · 디스크 "
              + mapper.diskBytes()
              + " bytes · 파일 대기 "
              + io.getQueue().size()
              + " · 미리보기 병합 "
              + previewCoalesced.get()
              + " · 브라우저 압력 "
              + (bridge == null ? "사용 불가" : bridge.backpressureCount())
              + " · 오류 "
              + errors.get());
    } catch (IOException e) {
      failure("저장 크기", e);
    }
  }

  private static void updateOptions(JComboBox<String> combo, Set<String> options, String selected) {
    boolean same = combo.getItemCount() == options.size();
    int index = 0;
    if (same)
      for (String option : options)
        if (!option.equals(combo.getItemAt(index++))) {
          same = false;
          break;
        }
    if (!same) {
      combo.removeAllItems();
      options.forEach(combo::addItem);
    }
    combo.setSelectedItem(options.contains(selected) ? selected : "전체");
  }

  private void fillRows() {
    if (rebuilding || mapper == null) return;
    Mapper previousOwner = shownOwner;
    Set<Long> keepIds = new HashSet<>();
    for (var p : selectedPackets()) keepIds.add(p.id);
    rebuilding = true;
    try {
      String run = String.valueOf(runs.getSelectedItem()),
          session = String.valueOf(sessions.getSelectedItem());
      List<Mapper.PacketRow> packets;
      synchronized (projectLock) {
        shownOwner = mapper;
        packets = mapper.packetRows(selectedFeature);
      }
      Set<String> allRuns = new LinkedHashSet<>(), allSessions = new LinkedHashSet<>();
      allRuns.add("전체");
      allSessions.add("전체");
      for (var p : packets) {
        allRuns.add(p.run());
        allSessions.add(p.session());
      }
      updateOptions(runs, allRuns, run);
      updateOptions(sessions, allSessions, session);
      run = String.valueOf(runs.getSelectedItem());
      session = String.valueOf(sessions.getSelectedItem());
      String term = search.getText().toLowerCase(Locale.ROOT);
      List<Mapper.PacketRow> visible = new ArrayList<>();
      for (var p : packets) {
        if (!run.equals("전체") && !p.run().equals(run)
            || !session.equals("전체") && !p.session().equals(session)
            || !(p.method() + " " + p.url()).toLowerCase(Locale.ROOT).contains(term)) continue;
        visible.add(p);
      }
      // Normal capture appends rows. Update only changed cells; filter changes reconcile IDs.
      int row = 0;
      for (var p : visible) {
        Object[] values = {
          p.id(), p.method(), p.url(), p.status(), p.session(), p.run(), p.confidence()
        };
        if (row < rows.getRowCount() && Objects.equals(rows.getValueAt(row, 0), p.id())) {
          for (int col = 1; col < values.length; col++)
            if (!Objects.equals(rows.getValueAt(row, col), values[col]))
              rows.setValueAt(values[col], row, col);
        } else if (row == rows.getRowCount()) rows.addRow(values);
        else {
          for (int col = 0; col < values.length; col++)
            if (!Objects.equals(rows.getValueAt(row, col), values[col]))
              rows.setValueAt(values[col], row, col);
        }
        row++;
      }
      if (rows.getRowCount() > row) rows.setRowCount(row);
      shown = List.copyOf(visible);
      table.clearSelection();
      for (int i = 0; i < shown.size(); i++)
        if (keepIds.contains(shown.get(i).id())) {
          int view = table.convertRowIndexToView(i);
          if (view >= 0) table.addRowSelectionInterval(view, view);
        }
    } finally {
      rebuilding = false;
    }
    if (previousOwner != shownOwner || !keepIds.isEmpty() && selectedPackets().isEmpty())
      showSelected();
    refreshFeatureActions();refreshPacketActions();
  }

  private List<Mapper.Packet> selectedPackets() {
    List<Mapper.Packet> out = new ArrayList<>();
    synchronized (projectLock) {
      if (mapper != shownOwner) return out;
      for (int index : table.getSelectedRows()) {
        int model = table.convertRowIndexToModel(index);
        if (model < shown.size()) {
          Mapper.Packet packet = mapper.packet(shown.get(model).id());
          if (packet != null) out.add(packet);
        }
      }
    }
    return out;
  }

  private void submitIo(Runnable task) {
    if (closed.get()) return;
    try {
      io.execute(task);
    } catch (RejectedExecutionException e) {
      failure("파일 작업 대기열이 가득 찼습니다. 완료 후 다시 시도하세요", e);
    }
  }

  private void showSelected() {
    if (rebuilding) return;
    long generation = selectionGeneration.incrementAndGet();
    List<Mapper.Packet> selected = selectedPackets();
    if (selected.isEmpty()) {
      requestText.setText("");
      responseText.setText("");
      explanation.setText("");
      return;
    }
    Mapper.Packet p = selected.get(0);
    Mapper owner = shownOwner;
    try {
      previews.execute(
          () -> {
            try {
              Mapper.Preview raw;
              synchronized (projectLock) {
                if (owner != mapper || generation != selectionGeneration.get()) return;
                raw = owner.preview(p.id);
              }
              String q = preview(raw.request(), raw.requestBytes());
              String s = preview(raw.response(), raw.responseBytes());
              SwingUtilities.invokeLater(
                  () -> {
                    if (generation != selectionGeneration.get() || closed.get()) return;
                    requestText.setText(q);
                    responseText.setText(s);
                    requestText.setCaretPosition(0);
                    responseText.setCaretPosition(0);
                    explanation.setText(raw.explanation());
                  });
            } catch (Exception e) {
              if (generation == selectionGeneration.get()) failure("원본 읽기", e);
            }
          });
    } catch (RejectedExecutionException e) {
      if (!closed.get()) failure("미리보기", e);
    }
  }

  private static String preview(byte[] bytes, int originalBytes) {
    return new String(bytes, StandardCharsets.ISO_8859_1)
        + (bytes.length < originalBytes ? "\n\n[미리보기 생략 · 전체 원본은 저장·Repeater에서 사용]" : "");
  }

  private void sendToRepeater() {
    List<Mapper.Packet> selected = selectedPackets();
    Mapper owner = shownOwner;
    submitIo(
        () -> {
          for (var p : selected)
            try {
              byte[][] raw;
              synchronized (projectLock) {
                if (owner != mapper || closed.get()) return;
                raw = owner.raw(p.id);
              }
              URI u = URI.create(p.url);
              HttpService service =
                  HttpService.httpService(
                      u.getHost(),
                      u.getPort() < 0 ? (u.getScheme().equals("https") ? 443 : 80) : u.getPort(),
                      u.getScheme().equals("https"));
              api.repeater()
                  .sendToRepeater(
                      HttpRequest.httpRequest(service, ByteArray.byteArray(raw[0])),
                      "기능 매핑 / " + p.operation);
            } catch (Exception e) {
              failure("Repeater 준비", e);
            }
        });
  }

  String browserSummary() {
    return (bridge == null ? "브라우저 연결 사용 불가 · " + browserUnavailable
        + "\nHTTP 수집·자동 매핑과 HTTP 통합 점검은 계속 사용할 수 있습니다."
        : bridge.snapshot().description(System.currentTimeMillis()))
        + "\nHTTP 자동 분류 " + mapper.count() + "건 · 브라우저 연결 근거 " + mapper.browserMappedPackets() + "건"
        + "\n이벤트 수신과 수집 반영은 별개입니다. OFF·범위 밖·중복 요청은 연결되지 않을 수 있습니다.";
  }

  private void showBridge() {
    if (closed.get()) return;
    JTextArea text =
        new JTextArea(
            (bridge == null ? browserSummary() : "연결 주소: "
                + bridge.baseUrl()
                + "\n연결 토큰: "
                + bridge.token
                + "\n\n" + browserSummary()
                + "\n\n브라우저 보조 확장의 연결 설정에 입력하세요.\n브라우저 보조 확장에서 분석할 사이트를 활성화한 뒤 새로고침합니다."),
            7,
            70);
    text.setEditable(false);
    text.setLineWrap(true);
    MapperUi.style(text);
    JOptionPane.showMessageDialog(
        root, new JScrollPane(text), "브라우저 연결", JOptionPane.INFORMATION_MESSAGE);
  }

  private void chooseFile(boolean save) {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle(save ? "원본 패킷 포함 프로젝트 저장 (.fmkr)" : "프로젝트 열기 (.fmkr)");
    chooser.setSelectedFile(new File("feature-mapper.fmkr"));
    if ((save ? chooser.showSaveDialog(root) : chooser.showOpenDialog(root))
        != JFileChooser.APPROVE_OPTION) return;
    Path path = chooser.getSelectedFile().toPath();
    status.setText("프로젝트 " + (save ? "저장" : "불러오기") + " 중…");
    submitIo(
        () -> {
          try {
            if (save) {
              Mapper.SaveSnapshot snapshot;
              synchronized (projectLock) {
                if (closed.get()) return;
                snapshot = mapper.snapshot();
              }
              try (snapshot) {
                ProjectIO.save(snapshot, path);
              }
            } else {
              Mapper loaded = ProjectIO.load(path);
              synchronized (projectLock) {
                if (closed.get()) {
                  loaded.close();
                  return;
                }
                Mapper old = mapper;
                mapper = loaded;
                old.close();
                selectionGeneration.incrementAndGet();
                revision.incrementAndGet();
              }
            }
            SwingUtilities.invokeLater(
                () -> {
                  selectedFeature = null;
                  refresh();
                  status.setText("프로젝트 " + (save ? "저장" : "불러오기") + " 완료 · 원본 패킷 포함");
                });
          } catch (Exception e) {
            failure("프로젝트 파일", e);
          }
        });
  }

  private void newProject() {
    if (JOptionPane.showConfirmDialog(
            root, "저장하지 않은 수집 내용을 비우고 새 프로젝트를 시작할까요?", "새 프로젝트", JOptionPane.YES_NO_OPTION)
        != JOptionPane.YES_OPTION) return;
    submitIo(
        () -> {
          try {
            Mapper next = new Mapper();
            synchronized (projectLock) {
              if (closed.get()) {
                next.close();
                return;
              }
              Mapper old = mapper;
              mapper = next;
              old.close();
              selectionGeneration.incrementAndGet();
              revision.incrementAndGet();
            }
            SwingUtilities.invokeLater(
                () -> {
                  selectedFeature = null;
                  refresh();
                });
          } catch (Exception e) {
            failure("새 프로젝트", e);
          }
        });
  }

  private void linkAssessment() {
    if (closed.get() || embeddedHost == null || !embeddedHost.supportsAssessmentTasks()) return;
    String feature = selectedFeature;
    if (feature == null) throw new IllegalArgumentException("왼쪽에서 연결할 기능을 선택하세요.");
    Mapper owner = mapper;
    status.setText("선택 기능의 API를 준비하고 있습니다.");
    io.execute(() -> {
      try {
        Mapper.AssessmentCandidate candidate = owner.assessmentCandidate(feature);
        if (candidate.endpoints().isEmpty()) throw new IllegalArgumentException("연결할 API가 없습니다.");
        SwingUtilities.invokeLater(() -> {
          if (closed.get() || owner != mapper || !Objects.equals(feature, selectedFeature)
              || !isAssessmentSourceCurrent(candidate.sourceId(), candidate.endpoints())) {
            status.setText("선택 기능이나 프로젝트가 변경됐습니다. 다시 연결하세요.");
            return;
          }
          try {
            for (String endpoint : candidate.endpoints())
              if (!embeddedHost.allowsAssessmentUrl(endpoint.substring(endpoint.indexOf(' ') + 1)))
                throw new IllegalArgumentException("현재 SCOPE 밖 API가 포함되어 있습니다. 범위를 확인한 뒤 다시 연결하세요.");
            embeddedHost.assessmentDraft(candidate.sourceId(), candidate.title(), candidate.endpoints());
            status.setText("업무 연결 검토 종료 · 점검 진행 현황에서 계정·API·근거를 확인하세요.");
          } catch (Exception ex) { failure("점검 업무 연결", ex); }
        });
      } catch (Exception ex) {
        SwingUtilities.invokeLater(() -> {
          if (!closed.get() && owner == mapper) failure("점검 업무 준비", ex);
        });
      }
    });
  }

  private void editFeature() {
    if (selectedFeature == null) return;
    Mapper.Feature f = mapper.features.get(selectedFeature);
    if (f == null) return;
    JTextField name = new JTextField(f.name, 35);
    JComboBox<String> state = new JComboBox<>(new String[] {"미점검", "진행 중", "완료", "재확인"});
    state.setSelectedItem(f.state);
    JTextArea note = new JTextArea(f.note, 6, 35);
    JPanel form = new JPanel(new GridLayout(0, 1));
    form.add(new JLabel("기능 이름"));
    form.add(name);
    form.add(state);
    form.add(new JScrollPane(note));
    if (JOptionPane.showConfirmDialog(root, form, "기능 기록", JOptionPane.OK_CANCEL_OPTION)
        == JOptionPane.OK_OPTION) {
      synchronized (projectLock) {
        mapper.edit(f.id, name.getText(), String.valueOf(state.getSelectedItem()), note.getText());
        revision.incrementAndGet();
      }
    }
  }

  private void movePackets() {
    List<Mapper.Packet> ps = selectedPackets();
    if (ps.isEmpty()) return;
    List<Mapper.Feature> options =
        mapper.featureList().stream().filter(f -> f.origin.equals(ps.get(0).origin)).toList();
    FeatureNode[] choices =
        options.stream().map(f -> new FeatureNode(f.id, f.name)).toArray(FeatureNode[]::new);
    Object chosen =
        JOptionPane.showInputDialog(
            root,
            "선택 패킷을 연결할 기능 · 다음 동일 요청에도 수정 규칙 적용",
            "분류 수정",
            JOptionPane.QUESTION_MESSAGE,
            null,
            choices,
            choices[0]);
    if (chosen instanceof FeatureNode f) {
      synchronized (projectLock) {
        for (var p : ps) mapper.move(p.id, f.id);
        revision.incrementAndGet();
      }
    }
  }

  private void unload() {
    if (!closed.compareAndSet(false, true)) return;
    diagnostics.close();
    if (bridge != null) bridge.close();
    io.shutdownNow();
    previews.shutdownNow();
    starts.clear();
    synchronized (projectLock) {
      if (mapper != null)
        try {
          mapper.close();
        } catch (IOException ignored) {
        }
    }
    SwingUtilities.invokeLater(
        () -> {
          if (timer != null) timer.stop();
              if(root!=null)root.putClientProperty("fm.closed",true);
              if(helpWindow!=null)helpWindow.dispose(); if(settingsWindow!=null)settingsWindow.dispose(); if(logWindow!=null)logWindow.dispose();
        });
  }
}
