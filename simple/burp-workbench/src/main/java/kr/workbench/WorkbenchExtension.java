package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.contextmenu.*;
import burp.api.montoya.ui.editor.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;

public final class WorkbenchExtension implements HttpHandler, ContextMenuItemsProvider {
  private final Object captureLock = new Object();
  private volatile DiskTrafficStore trafficStore;
  private CaptureObservations captureObservations;
  private volatile boolean nativeScopeFallback;
  private final java.util.Set<String> scopeCandidates =
      java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
  private MontoyaApi api;
  private WorkbenchScope localScope;
  private final DefaultTableModel scopeModel =
      new DefaultTableModel(new String[] {"수집", "범위", "등록 URL (쿼리 제외)", "포함 관계"}, 0) {
        public boolean isCellEditable(int r, int c) {
          return c == 0;
        }

        public Class<?> getColumnClass(int c) {
          return c == 0 ? Boolean.class : String.class;
        }
      };
  private boolean refreshingScope;
  private burp.api.montoya.core.Registration scopeRegistration;
  private final JTable scopeTable =
      new JTable(scopeModel) {
        public String getToolTipText(java.awt.event.MouseEvent e) {
          int row = rowAtPoint(e.getPoint()), column = columnAtPoint(e.getPoint());
          return row < 0 || column < 0 ? null : Objects.toString(getValueAt(row, column), "");
        }
      };
  private JPanel scopeContent;
  private JTextField scopeUrlInput;
  private JButton scopeEditButton;
  private WorkbenchScope.Rule scopeEditingRule;
  private JDialog scopeWindow;
  private boolean settingsActionReady;
  private long assessmentNavigationRevision;

  @FunctionalInterface
  interface DialogOpener {
    JDialog open(Component owner, String title, Component content, boolean modal, Runnable closed);
  }

  DialogOpener dialogOpener = WorkbenchUi::settingsWindow;
  private JButton scopeListButton, captureButton;

  enum CaptureMode {
    OFF,
    SCOPE;

    CaptureMode next() {
      return values()[(ordinal() + 1) % values().length];
    }
  }

  private final AtomicReference<CaptureMode> captureMode = new AtomicReference<>(CaptureMode.OFF);
  private final AtomicBoolean unloaded = new AtomicBoolean(false);
  private final AtomicLong generation = new AtomicLong();
  private final AtomicInteger dropped = new AtomicInteger();
  private final AtomicLong received = new AtomicLong(),
      sizeExcluded = new AtomicLong(),
      queueOverflow = new AtomicLong(),
      processingErrors = new AtomicLong();
  // Policy exclusions are intentional: never count them as lost packets or retain their contents.
  // Live callbacks and History imports are separate populations; current disk rows may be cleared.
  private final AtomicLong offExcluded = new AtomicLong(),
      scopeExcluded = new AtomicLong(),
      storedResponses = new AtomicLong(),
      responseStoreFailures = new AtomicLong(),
      captureDecisionErrors = new AtomicLong(),
      shutdownExcluded = new AtomicLong(),
      importAttempts = new AtomicLong(),
      importStored = new AtomicLong(),
      importOffExcluded = new AtomicLong(),
      importScopeExcluded = new AtomicLong(),
      importFailures = new AtomicLong();
  private final ConcurrentMap<String, AtomicLong> captureErrorCounts = new ConcurrentHashMap<>();
  private volatile String lastResponse = "없음", lastCaptureError = "없음";
  // Compatibility views expose only the disk index; neither duplicates row metadata.
  private final Queue<Traffic> incoming =
      new AbstractQueue<>() {
        public boolean offer(Traffic value) {
          if (captureObservations != null) captureObservations.signal();
          return true;
        }

        public int size() {
          return trafficStore == null
              ? 0
              : (int)
                  Math.min(
                      Integer.MAX_VALUE, Math.max(0, trafficStore.size() - logModel.getRowCount()));
        }

        public Traffic peek() {
          return isEmpty() ? null : trafficAt(logModel.getRowCount());
        }

        public Traffic poll() {
          throw new UnsupportedOperationException(
              "Committed capture rows are published in batches");
        }

        public void clear() {
          /* Pending entries are immutable disk rows; resetting the store clears them. */
        }

        public Iterator<Traffic> iterator() {
          int start = logModel.getRowCount(), end = start + size();
          return new Iterator<>() {
            int next = start;

            public boolean hasNext() {
              return next < end;
            }

            public Traffic next() {
              if (!hasNext()) throw new NoSuchElementException();
              return trafficAt(next++);
            }
          };
        }
      };
  private final List<Traffic> traffic =
      new AbstractList<>() {
        public int size() {
          return logModel.getRowCount();
        }

        public Traffic get(int index) {
          Objects.checkIndex(index, size());
          return trafficAt(index);
        }

        public void clear() {
          logModel.setRowCount(0);
        }
      };
  private final CaptureTableModel logModel =
      new CaptureTableModel(
          row -> {
            try {
              return trafficStore.ref(row);
            } catch (DiskTrafficStore.StorageException error) {
              if (unloaded.get()) return null;
              throw error;
            }
          });
  private JTable logTable;
  TrafficBrowser trafficBrowser;
  private final JLabel captureInfo = new JLabel();
  private Component payloadPage;
  private JDialog featureHelpWindow;
  private JPanel featureHelpContent;
  private JComboBox<FeatureGuide.Feature> featureChoices;
  private final JTextArea featureHelpText = new JTextArea(15, 55);
  private final JButton featureHelp = new JButton("기능 안내");
  private final Diagnostics diagnostics = new Diagnostics();
  private final JButton logButton = new JButton("로그");
  private JPanel logContent;
  private JDialog logWindow;
  private JTabbedPane tabs;
  private FolderNavigation navigation;
  private SimpleNavigation simpleNavigation;
  private JTabbedPane dataTabs, trafficTools, explorationTools, mapperTools;
  private final WorkContext workContext = new WorkContext();
  private final OriginalRequestIds comparisonOriginalIds = new OriginalRequestIds();
  private final WorkContextPanel workContextPanel = new WorkContextPanel(workContext);
  private final JButton resumeWorkButton = new JButton("작업 이어가기");
  private final BackgroundJobs backgroundJobs = new BackgroundJobs();
  private JobMonitorPanel jobMonitor;
  private GettingStartedPanel gettingStarted;
  private TargetWindow jobMonitorWindow, gettingStartedWindow;
  private javax.swing.Timer navigationSelectionTimer;
  private long navigationRestoreRevision, navigationInteractionRevision;
  private DiskTrafficStore checklistTrafficBaselineStore;
  private long checklistTrafficBaseline;
  private TrafficBrowser.SearchPosition revealedTrafficSearch;
  private String revealedTrafficOriginal = "", selectionMatchNote = "";
  private WorkspaceResume workspaceResume;
  private JDialog resumeWorkWindow;
  private String workspaceProject = "";
  private boolean restoringWorkspace;
  private javax.swing.Timer resumeSelectionTimer;
  private long workspaceRestoreRevision;
  private LayoutSettings commonSettings;
  private JPanel commonSettingsContent;
  private JDialog commonSettingsWindow;

  public void addPayloadRunnerPage(Component page) {
    WorkbenchUi.style(page);
    Diagnostics.bind(page, diagnostics);
    payloadPage = page;
    if (page instanceof JComponent component)
      component.putClientProperty("wb.feature.id", "payload");
    tabs.insertTab(
        FeatureGuide.title("payload"),
        null,
        page,
        FeatureGuide.feature("payload").summary(),
        tabs.indexOfComponent(bountySupport));
    WorkbenchUi.packetLayout(page, commonSettings.choice.getSelectedIndex() == 1);
    navigation.refreshSelection();
    if (simpleNavigation != null) simpleNavigation.refreshSelection();
  }

  public void selectPayloadRunnerPage() {
    if (payloadPage != null) tabs.setSelectedComponent(payloadPage);
  }

  public void capturePayloadEvidence(
      burp.api.montoya.http.HttpService service, java.util.List<kr.payloadrunner.Engine.Row> rows) {
    bountySupport.capturePayload(service, rows);
    tabs.setSelectedComponent(bountySupport);
    bountySupport.pages.setSelectedIndex(3);
  }

  private java.util.function.Consumer<HttpRequest> payloadImport;

  public void setPayloadImport(java.util.function.Consumer<HttpRequest> action) {
    payloadImport = action;
  }

  private void selectedPayload() {
    if (current == null) throw new IllegalArgumentException("요청을 선택하세요.");
    if (payloadImport == null) throw new IllegalStateException("Payload Runner 준비 중");
    payloadImport.accept(current.request());
  }

  private JPanel root;
  private EnvironmentPanel environments;
  private PassiveScanWorkspace passiveScan;
  private ActivityPanel activity;
  private StructuredComparePanel structuredCompare;
  private ResponseReconPanel responseRecon;
  private JsAnalysisPanel jsAnalysis;
  private AuthComparePanel authCompare;
  private BountySupportPanel bountySupport;
  private kr.featuremapper.FeatureMapperWorkspace featureMapper;
  private JLabel selectionInfo = new JLabel("요청을 선택하세요");
  private JLabel status = new JLabel("준비됨"), stats = new JLabel();
  private JTextField filter = new WorkbenchSkin.SearchField(24);
  private JTextArea codecIn = area("", 10),
      codecOut = area("", 10),
      jwtIn = area("", 5),
      jwtOut = area("", 12),
      jsIn = area("", 10),
      jsOut = area("", 10);
  private HttpRequestEditor requestView;
  private final PacketInspector requestPacket = new PacketInspector("wb.traffic.request"),
      responsePacket = new PacketInspector("wb.traffic.response");
  private HttpResponseEditor responseView;
  private Component requestEditorComponent, responseEditorComponent;
  private JDialog requestNativeWindow, responseNativeWindow;
  private Traffic current;
  private javax.swing.Timer timer;

  private record Traffic(String time, String source, DiskTrafficStore.PacketRef packet) {
    HttpRequest request() {
      return packet.request();
    }

    HttpResponse response() {
      return packet.response();
    }
  }

  private Traffic trafficAt(int index) {
    DiskTrafficStore.PacketRef packet = trafficStore.ref(index);
    return new Traffic(packet.time(), packet.source(), packet);
  }

  private Traffic storeTraffic(String source, HttpRequest request, HttpResponse response) {
    return storeTraffic(source, request, response, System.currentTimeMillis());
  }

  private Traffic storeTraffic(String source, HttpRequest request, HttpResponse response, long started) {
    DiskTrafficStore.PacketRef packet =
        trafficStore.append(LocalTime.now().withNano(0).toString(), source, request, response, started);
    if (captureObservations != null) captureObservations.signal();
    return new Traffic(packet.time(), source, packet);
  }

  private void startCaptureObservations() {
    captureObservations =
        new CaptureObservations(
            trafficStore, this::prepareCaptureObservation, this::captureFailure);
  }

  private Runnable prepareCaptureObservation(DiskTrafficStore.PacketRef ref, HttpRequestResponse packet) {
    HttpRequest request = packet.request();
    HttpResponse response = packet.response();
    if (featureMapper != null)
      featureMapper.observeCaptured(ref.capturedAt(), ref.source(), request, response);
    EnvironmentPanel.Observation environment = null;
    try {
      if (environments != null) environment = environments.prepareObservation(request, response);
    } catch (RuntimeException error) {
      captureFailure("서버 환경 관찰", error);
    }
    try{if(jsAnalysis!=null)jsAnalysis.trafficIndex.observe(request,response);}catch(RuntimeException error){captureFailure("JS 트래픽 대조 관찰",error);}
    final EnvironmentPanel.Observation preparedEnvironment = environment;
    // Only compact metadata crosses back to Swing; the original bytes become reclaimable here.
    return () -> {
      captureStage(
          "서버 환경 관찰",
          () -> {
            if (environments != null) environments.applyObservation(preparedEnvironment);
          });
    };
  }

  private void clearStoredTraffic() {
    synchronized (captureLock) {
      DiskTrafficStore replacement = new DiskTrafficStore();
      if (captureObservations != null) captureObservations.close();
      DiskTrafficStore previous = trafficStore;
      trafficStore = replacement;
      checklistTrafficBaselineStore = replacement;
      checklistTrafficBaseline = 0;
      generation.incrementAndGet();
      traffic.clear();
      if(trafficBrowser!=null)trafficBrowser.clear();
      current = null;
      workContext.clear();
      captureStage("임시 저장소 정리", previous::close);
      startCaptureObservations();
    }
  }

  public void initialize(MontoyaApi api) {
    this.api = api;
    trafficStore = new DiskTrafficStore();
    localScope = WorkbenchScope.install(api);
    api.extension().setName(FeatureGuide.EXTENSION_NAME);
    api.extension()
        .registerUnloadingHandler(
            () -> {
              if (scopeRegistration != null)
                captureStage("Scope 알림 해제", scopeRegistration::deregister);
              synchronized (scopeCandidates) {
                scopeCandidates.clear();
              }
              synchronized (captureLock) {
                unloaded.set(true);
                generation.incrementAndGet();
                if (captureObservations != null) captureObservations.close();
                if (featureMapper != null) featureMapper.close();
                captureStage("임시 저장소 종료", trafficStore::close);
              }
              SwingUtilities.invokeLater(
                  () -> {
                    rememberWorkspace();
                    if (resumeSelectionTimer != null) resumeSelectionTimer.stop();
                    if (navigationSelectionTimer != null) navigationSelectionTimer.stop();
                    if (navigation != null) navigation.close();
                    if (jobMonitorWindow != null) jobMonitorWindow.close();
                    if (gettingStartedWindow != null) gettingStartedWindow.close();
                    if (jobMonitor != null) jobMonitor.close();
                    if (gettingStarted != null) gettingStarted.close();
                    backgroundJobs.close();
                    if (resumeWorkWindow != null) resumeWorkWindow.dispose();
                    if (featureHelpWindow != null) featureHelpWindow.dispose();
                    if (logWindow != null) logWindow.dispose();
                    diagnostics.close();
                    if (commonSettingsWindow != null) commonSettingsWindow.dispose();
                    if (commonSettings != null) commonSettings.close();
                    if (scopeWindow != null) scopeWindow.dispose();
                    if (requestNativeWindow != null) requestNativeWindow.dispose();
                    if (responseNativeWindow != null) responseNativeWindow.dispose();
                    requestPacket.close();
                    responsePacket.close();
                    if (timer != null) timer.stop();
                    if (root != null) root.putClientProperty("wb.skin.closed", true);
                    if (structuredCompare != null) structuredCompare.close();
                    if (responseRecon != null) responseRecon.close();
                    if (jsAnalysis != null) jsAnalysis.close();
                    if (trafficBrowser != null) trafficBrowser.close();
                    if (authCompare != null) authCompare.close();
                    if (bountySupport != null) bountySupport.close();
                    traffic.clear();
                    if (environments != null) {
                      environments.close();
                    }
                    if (passiveScan != null) passiveScan.close();
                    if (activity != null) activity.close();
                    WorkbenchScope.release(api);
                  });
            });
    try {
      if (SwingUtilities.isEventDispatchThread()) buildUi();
      else SwingUtilities.invokeAndWait(this::buildUi);
      startCaptureObservations();
      scopeRegistration =
          api.scope()
              .registerScopeChangeHandler(
                  change ->
                      SwingUtilities.invokeLater(
                          () -> captureStage("Burp Scope 동기화", this::syncBurpScope)));
      if (SwingUtilities.isEventDispatchThread()) syncBurpScope();
      else SwingUtilities.invokeAndWait(this::syncBurpScope);
      // Simple processes admitted traffic or explicit selections; do not poll old History at startup.
      api.http().registerHttpHandler(this);
      api.userInterface().registerContextMenuItemsProvider(this);
      api.logging()
          .logToOutput("Workbench Simple loaded. SCOPE 수집과 선택한 요청의 직접 실행을 사용하세요.");
    } catch (Exception e) {
      if (captureObservations != null) captureObservations.close();
      if (featureMapper != null) featureMapper.close();
      captureStage("초기화 실패 저장소 정리", trafficStore::close);
      api.logging().logToError(e.toString());
      throw new IllegalStateException(e);
    }
  }

  private static DefaultTableModel model(String... names) {
    return new DefaultTableModel(names, 0) {
      @Override
      public boolean isCellEditable(int r, int c) {
        return false;
      }
    };
  }

  private static JTextArea area(String text, int rows) {
    JTextArea a = new JTextArea(text, rows, 60);
    a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
    a.setLineWrap(true);
    a.setWrapStyleWord(true);
    return a;
  }

  private JButton button(String text, Runnable action) {
    JButton b = new JButton(text);
    b.addActionListener(
        e -> {
          try {
            action.run();
          } catch (Exception ex) {
            error(ex);
          }
        });
    return b;
  }

  private static JPanel row(Component... parts) {
    return WorkbenchUi.row(parts);
  }

  private static JPanel page(Component top, Component center) {
    JPanel p = new JPanel(new BorderLayout(8, 8));
    p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    p.add(top, BorderLayout.NORTH);
    p.add(center, BorderLayout.CENTER);
    return p;
  }

  private static JPanel labeled(String label, Component c) {
    return WorkbenchUi.card(label, c);
  }

  private static JSplitPane split(int orientation, Component a, Component b) {
    return WorkbenchUi.split(orientation, a, b, .5);
  }

  private void buildUi() {
    root = new JPanel(new BorderLayout());
    root.setName("wb.root");
    tabs =
        new FolderNavigation.Pages() {
          @Override
          public void setSelectedIndex(int index) {
            if (settingsActionReady
                && index >= 0
                && index < getTabCount()
                && getComponentAt(index) instanceof JComponent component
                && "settings".equals(component.getClientProperty("wb.feature.id"))) {
              openSettings();
              return;
            }
            super.setSelectedIndex(index);
          }
        };
    tabs.addChangeListener(e -> assessmentNavigationRevision++);
    requestView = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
    responseView = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
    requestEditorComponent = requestView == null ? null : requestView.uiComponent();
    responseEditorComponent = responseView == null ? null : responseView.uiComponent();
    requestPacket.setNativeAction(requestEditorComponent == null ? null : () -> showNativePacket(true));
    responsePacket.setNativeAction(responseEditorComponent == null ? null : () -> showNativePacket(false));
    addPage("트래픽", "traffic", trafficPanel(), false);
    addPage("권한 비교", "auth", authPanel(), false);
    addPage("JS 추출", "js", jsPanel(), false);
    environments = new EnvironmentPanel(api);
    passiveScan = new PassiveScanWorkspace(api);
    explorationTools = new JTabbedPane();
    explorationTools.setName("wb.exploration.tools");
    explorationTools.addTab("서버·경로 확인", environments);
    explorationTools.setToolTipTextAt(0, "관측 환경과 경로 후보 확인 · GET 확인은 직접 실행합니다.");
    explorationTools.addTab("URL 후보 수집", passiveScan);
    explorationTools.setToolTipTextAt(1, "History·JS·문서·아카이브에서 후보 수집 · 대상 GET 확인과 구분합니다.");
    addPage("환경 탐색", "environment", explorationTools, false);
    activity = new ActivityPanel(api);
    activity.assessmentPanel.selectedTraffic =
        () ->
            selectedTraffic().stream()
                .map(t -> HttpRequestResponse.httpRequestResponse(t.request(), t.response()))
                .toList();
    activity.assessmentPanel.authNames = () -> authCompare.authNames;
    activity.assessmentPanel.cookieNames = () -> authCompare.cookieNames;
    authCompare.trackingSource = activity.tracker.assessments::accounts;
    authCompare.trackingCreate = activity.tracker.assessments::register;
    authCompare.trackingBind = activity.tracker.assessments::bind;
    authCompare.trackingSink = activity::accountInspection;
    authCompare.onExecutionTracker(activity.tracker.assessments, comparisonOriginalIds::find);
    jsAnalysis.coverage = (result, source) -> activity.analyzed(result, source);
    jsAnalysis.observation = activity::observation;
    jsAnalysis.observedMethods = activity.tracker::observedMethods;
    jsAnalysis.trafficLookup=(method,url)->{
      DiskTrafficStore store=trafficStore;for(long row=store.size()-1;row>=0&&!unloaded.get();row--){var ref=store.ref(row);if(ref.method().equals(method)&&ActivityTracker.safeUrl(ref.url()).equals(ActivityTracker.safeUrl(url)))return ref.packet();}return null;
    };
    activity.sourceNavigator =
        (source, line, kind) -> {
          tabs.setSelectedComponent(jsAnalysis);
          jsAnalysis.navigateSource(source, line, kind);
        };
    authCompare.reviewSink = (method, url, reason) -> activity.review(method, url, reason);
    environments.sessionConfig = slot -> authCompare.sessionChecks[slot];
    environments.sessions =
        (slot, url) -> {
          var candidate = authCompare.slots[slot];
          if (candidate == null) throw new IllegalArgumentException("권한 비교 계정 미지정");
          if (!candidate
              .origin()
              .equals(AuthComparePanel.origin(HttpRequest.httpRequestFromUrl(url))))
            throw new IllegalArgumentException("계정 Origin과 대상이 다릅니다.");
          return candidate.headers();
        };
    passiveScan.discovery = (url, source) -> activity.discover(url, source);
    responseRecon = new ResponseReconPanel(api);
    addPage("응답 분석", "response.recon", responseRecon, false);
    bountySupport =
        new BountySupportPanel(
            api,
            () ->
                selectedTraffic().stream()
                    .map(t -> HttpRequestResponse.httpRequestResponse(t.request(), t.response()))
                    .toList(),
            authCompare,
            activity);
    addPage("점검 지원", "bounty", bountySupport, false);
    activity.assessmentPanel.preparationNavigator = this::prepareRemainingCheck;
    activity.assessmentPanel.executionNavigator = id -> {
      AuthComparePanel.Run run = selectAssessmentExecution(id);
      if (run == null) {
        activity.assessmentPanel.showExecutionMetadata(id);
        return;
      }
      tabs.setSelectedComponent(authCompare);
      authCompare.openComparison(run);
    };
    activity.assessmentPanel.evidenceNavigator = id -> {
      AuthComparePanel.Run run = selectAssessmentExecution(id);
      tabs.setSelectedComponent(bountySupport);
      bountySupport.pages.setSelectedIndex(3);
      bountySupport.state.setText(run == null
          ? "연결 실행의 패킷이 현재 비교 이력에 없습니다. 저장 ZIP을 확인하거나 원본을 직접 준비하세요."
          : "실행 " + id + " 선택 · ‘권한 비교 추가’와 ZIP 저장은 직접 실행하세요.");
    };
    structuredCompare.evidenceSink = this::captureObserved;
    passiveScan.evidenceSink = this::captureObserved;
    responseRecon.evidenceSink = this::captureObserved;
    responseRecon.storedEvidenceSink =
        (packets, note, image, sha) -> {
          bountySupport.captureObservedWithImage(packets, note, image, sha);
          tabs.setSelectedComponent(bountySupport);
        };
    featureMapper = new kr.featuremapper.FeatureMapperWorkspace();
    JPanel mapperPage = featureMapper.initializeEmbedded(api,
        new kr.featuremapper.FeatureMapperWorkspace.EmbeddedHost() {
          public String captureMode() { return WorkbenchExtension.this.captureMode.get().name(); }
          public void toggleCapture() { captureButton.doClick(0); }
          public boolean acceptsUrl(String url) {
            return !unloaded.get() && WorkbenchExtension.this.captureMode.get() == CaptureMode.SCOPE
                && localScope.matchesTraffic(url);
          }
          public void failure(String stage, Exception error) {
            captureFailure("기능 자동 매핑 · " + stage,
                error instanceof RuntimeException runtime ? runtime : new IllegalStateException(error));
          }
          public boolean supportsAssessmentTasks() { return true; }
          public boolean allowsAssessmentUrl(String url) {
            return !unloaded.get() && localScope.matchesTraffic(url);
          }
          public void assessmentDraft(String sourceId, String title, List<String> endpoints) {
            activity.assessmentPanel.upsertMapperDraft(sourceId, title, endpoints,
                () -> !unloaded.get() && featureMapper.isAssessmentSourceCurrent(sourceId, endpoints)
                    && endpoints.stream().allMatch(endpoint ->
                        localScope.matchesTraffic(endpoint.substring(endpoint.indexOf(' ') + 1))));
            mapperTools.setSelectedComponent(activity);
          }
        });
    mapperTools = new JTabbedPane();
    mapperTools.setName("wb.mapper.tools");
    mapperTools.addTab("기능 분류·실행", mapperPage);
    mapperTools.setToolTipTextAt(0, "요청을 기능과 실행별로 묶습니다. 분류는 점검 완료가 아닙니다.");
    mapperTools.addTab("점검 진행 현황", activity);
    mapperTools.setToolTipTextAt(1, "계정·URL·업무를 직접 검토하고 완료합니다. 자동 분류와 별도 상태입니다.");
    addPage("기능 자동 매핑", "mapper", mapperTools, false);
    addPage("설정", "settings", settingsPanel(), false);
    settingsActionReady = true;
    featureHelp.setName("wb.feature.help");
    featureHelp.addActionListener(e -> openFeatureHelp());
    logButton.setName("wb.navigation.logs");
    logButton.setToolTipText("오류 기록과 수집 상태 확인");
    logButton.addActionListener(e -> openLogs());
    navigation = new FolderNavigation(api, tabs, this::openSettings, featureHelp, logButton);
    // The retained route adapter keeps engine callbacks compatible; all eight extension entry points stay directly visible.
    simpleNavigation = new SimpleNavigation(tabs, this::openSettings, this::openFeatureHelp, this::openLogs);
    root.add(simpleNavigation);
    trafficTools.addChangeListener(e -> updateFeatureHelp());
    explorationTools.addChangeListener(e -> updateFeatureHelp());
    mapperTools.addChangeListener(e -> updateFeatureHelp());
    Diagnostics.bind(root, diagnostics);
    commonSettings.apply();
    tabs.addChangeListener(e -> updateFeatureHelp());
    updateFeatureHelp();
    api.userInterface().applyThemeToComponent(root);
    WorkbenchUi.style(root);
    navigation.restoreLayout();
    api.userInterface().registerSuiteTab(FeatureGuide.SUITE_TAB, root);
    WorkbenchSkin.install(root, navigation::restoreLayout);
    workspaceResume = WorkspaceResume.forApi(api);
    workspaceProject = workspaceResume.project();
    trafficBrowser.backgroundJobs = backgroundJobs;
    authCompare.backgroundJobs = backgroundJobs;
    bountySupport.backgroundJobs = backgroundJobs;
    jsAnalysis.backgroundJobs = backgroundJobs;
    structuredCompare.backgroundJobs = backgroundJobs;
    configureUsabilityNavigation();
    workContext.onChange(this::rememberWorkspace);
    trafficBrowser.onSearchStateChanged(this::rememberWorkspace);
    tabs.addChangeListener(e -> rememberWorkspace());
    trafficTools.addChangeListener(e -> rememberWorkspace());
    explorationTools.addChangeListener(e -> rememberWorkspace());
    mapperTools.addChangeListener(e -> rememberWorkspace());
    if (dataTabs != null) dataTabs.addChangeListener(e -> rememberWorkspace());
    activity.coveragePages.addChangeListener(e -> rememberWorkspace());
    bountySupport.pages.addChangeListener(e -> rememberWorkspace());
    updateResumeButton();
    // A listener exception can leave Swing Timer coalescing latched and stop all future ticks.
    timer = new javax.swing.Timer(300, e -> captureStage("목록 갱신", this::drain));
    timer.start();
    root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        .put(KeyStroke.getKeyStroke("F1"), "featureHelp");
    root.getActionMap()
        .put(
            "featureHelp",
            new AbstractAction() {
              public void actionPerformed(java.awt.event.ActionEvent e) {
                openFeatureHelp();
              }
            });
    root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        .put(KeyStroke.getKeyStroke("control R"), "repeater");
    root.getActionMap()
        .put(
            "repeater",
            new AbstractAction() {
              public void actionPerformed(java.awt.event.ActionEvent e) {
                try {
                  selectedTraffic()
                      .forEach(t -> api.repeater().sendToRepeater(t.request(), "Workbench"));
                } catch (Exception ex) {
                  error(ex);
                }
              }
            });
    root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        .put(KeyStroke.getKeyStroke("control F"), "search");
    root.getActionMap()
        .put(
            "search",
            new AbstractAction() {
              public void actionPerformed(java.awt.event.ActionEvent e) {
                openFeature("traffic");
                filter.requestFocusInWindow();
                filter.selectAll();
              }
            });
  }

  private void addPage(String title, String id, Component body, boolean data) {
    if (body instanceof JComponent component) component.putClientProperty("wb.feature.id", id);
    tabs.addTab(FeatureGuide.title(id), null, body, FeatureGuide.feature(id).summary());
  }

  private void updateResumeButton() {
    if (workspaceResume == null) return;
    resumeWorkButton.setText(workspaceResume.pending() ? "이전 작업 이어가기" : "작업 이어가기");
    String problem = workspaceResume.problem();
    resumeWorkButton.setToolTipText(problem.isEmpty()
        ? "마지막 화면·업무·검색 조건 확인 · 인증값·검색어·원본 본문은 저장하지 않음" : problem);
  }

  /** Metadata only; unresolved startup bookmarks are protected by WorkspaceResume.capture(). */
  private void rememberWorkspace() {
    if (workspaceResume == null || restoringWorkspace || tabs == null || trafficBrowser == null || activity == null) return;
    if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::rememberWorkspace); return; }
    String project = workspaceResume.project();
    if (!workspaceProject.equals(project)) {
      workspaceProject = project;
      restoringWorkspace = true;
      try {
        navigationRestoreRevision++;
        if (navigationSelectionTimer != null) navigationSelectionTimer.stop();
        backgroundJobs.reset();
        if (navigation != null) navigation.resetHistory();
        if(bountySupport!=null)bountySupport.resetEvidenceProject();
        if(authCompare!=null)authCompare.resetChecklistProject();
        if(jsAnalysis!=null)jsAnalysis.resetBackgroundProject();
        checklistTrafficBaselineStore=trafficStore;
        checklistTrafficBaseline=trafficStore==null?0:trafficStore.size();
        if (logTable != null) logTable.clearSelection();
        workContext.clear();
        activity.assessmentPanel.clearResumeTaskSelection();
        workspaceRestoreRevision++;
        if (resumeSelectionTimer != null) resumeSelectionTimer.stop();
      } finally { restoringWorkspace = false; }
      updateResumeButton();
      return;
    }
    String feature = currentFeature();
    try {
      if (!feature.equals("settings")) workspaceResume.capture(feature, resumeSubtab(feature),
          activity.assessmentPanel.selectedTaskId(), workContext.snapshot().requestId(), trafficBrowser.captureSearchState());
    } catch (RuntimeException error) {
      resumeWorkButton.setToolTipText("작업 위치 저장 보류 · " + error.getMessage());
      return;
    }
    updateResumeButton();
  }

  private int resumeSubtab(String feature) {
    if (feature.equals("data") && dataTabs != null) return dataTabs.getSelectedIndex();
    if (feature.equals("bounty") && bountySupport != null) return bountySupport.pages.getSelectedIndex();
    if (feature.equals("activity") && activity != null) return activity.coveragePages.getSelectedIndex();
    return -1;
  }

  private void openWorkspaceResume() {
    if (workspaceResume == null || unloaded.get()) return;
    rememberWorkspace();
    if (resumeWorkWindow != null && resumeWorkWindow.isDisplayable()) { resumeWorkWindow.toFront(); return; }
    WorkspaceResume.Snapshot snapshot = workspaceResume.snapshot();
    String openingProject = workspaceResume.project();
    String task = snapshot == null || snapshot.task().isEmpty() ? "선택 기록 없음"
        : activity.assessmentPanel.resumeTaskSummary(snapshot.task());
    var panel = new WorkspaceResumePanel(snapshot, task,
        snapshot != null && WorkspaceResume.original(trafficStore, snapshot.original()) != null,
        workspaceResume.problem(), this::restoreWorkspace, () -> {
          if (!openingProject.equals(workspaceResume.project())) {
            if (resumeWorkWindow != null) resumeWorkWindow.dispose();
            rememberWorkspace();
            status.setText("프로젝트가 바뀌었습니다. 현재 프로젝트의 작업 이어가기를 다시 여세요.");
            return;
          }
          workspaceRestoreRevision++;
          if (resumeSelectionTimer != null) resumeSelectionTimer.stop();
          workspaceResume.startFresh();
          if (resumeWorkWindow != null) resumeWorkWindow.dispose();
          rememberWorkspace();
          status.setText("현재 작업 위치 저장을 시작했습니다. 요청 실행은 직접 선택하세요.");
        });
    resumeWorkWindow = dialogOpener.open(root, "Workbench Simple · 작업 이어가기", panel, false, () -> resumeWorkWindow = null);
  }

  private void restoreWorkspace(WorkspaceResume.Snapshot snapshot) {
    if (!workspaceResume.current(snapshot)) {
      status.setText("프로젝트·저장 작업이 바뀌었습니다. 작업 이어가기를 다시 여세요.");
      if (resumeWorkWindow != null) resumeWorkWindow.dispose();
      return;
    }
    restoringWorkspace = true;
    long restoreRevision = ++workspaceRestoreRevision;
    if (resumeSelectionTimer != null) resumeSelectionTimer.stop();
    StringBuilder message = new StringBuilder("화면·검색 조건 복원 · 요청 전송 없음");
    try {
      logTable.clearSelection();
      workContext.clear();
      activity.assessmentPanel.clearResumeTaskSelection();
      trafficBrowser.restoreSearchState(snapshot.filters(), snapshot.project());
      boolean taskRestored = snapshot.task().isEmpty() || activity.assessmentPanel.restoreTaskSelection(snapshot.task());
      if (!taskRestored) message.append(" · 업무 기록을 불러온 뒤 같은 업무를 다시 선택하세요");
      openFeature(snapshot.feature());
      if (snapshot.feature().equals("data") && snapshot.subtab() >= 0 && snapshot.subtab() < dataTabs.getTabCount())
        dataTabs.setSelectedIndex(snapshot.subtab());
      if (snapshot.feature().equals("bounty") && snapshot.subtab() >= 0 && snapshot.subtab() < bountySupport.pages.getTabCount())
        bountySupport.pages.setSelectedIndex(snapshot.subtab());
      if (snapshot.feature().equals("activity") && snapshot.subtab() >= 0 && snapshot.subtab() < activity.coveragePages.getTabCount())
        activity.coveragePages.setSelectedIndex(snapshot.subtab());
      if (!snapshot.original().isEmpty()) {
        var original = WorkspaceResume.original(trafficStore, snapshot.original());
        if (original == null) message.append(" · 원본 없음: 다시 수집하거나 직접 선택하세요");
        else message.append(" · 검색 완료 후 정확한 원본 선택");
      }
      if (!workspaceResume.accept(snapshot)) throw new IllegalStateException("프로젝트가 바뀌었습니다. 작업 이어가기를 다시 여세요.");
      if (resumeWorkWindow != null) resumeWorkWindow.dispose();
      status.setText(message + " · 계정 연결·증거 파일을 다시 확인하세요");
      if (!snapshot.original().isEmpty() && WorkspaceResume.original(trafficStore, snapshot.original()) != null)
        selectResumedOriginal(snapshot, restoreRevision);
    } catch (RuntimeException error) {
      status.setText("작업 위치 복원 실패 · " + error.getMessage());
    } finally { restoringWorkspace = false; updateResumeButton(); }
  }

  /** Wait for the new filter view, without selecting an old row or overriding later user navigation. */
  private void selectResumedOriginal(WorkspaceResume.Snapshot saved, long revision) {
    DiskTrafficStore store = trafficStore;
    long navigation = assessmentNavigationRevision, selection = workContext.snapshot().selectionRevision();
    long configuration = workContext.snapshot().configurationRevision();
    String task = activity.assessmentPanel.selectedTaskId();
    String searchText = filter.getText(), filters = trafficBrowser.captureSearchState();
    long started = System.nanoTime();
    resumeSelectionTimer = new javax.swing.Timer(60, event -> {
      javax.swing.Timer source = (javax.swing.Timer) event.getSource();
      try {
        if (unloaded.get() || revision != workspaceRestoreRevision || store != trafficStore
            || navigation != assessmentNavigationRevision || selection != workContext.snapshot().selectionRevision()
            || configuration != workContext.snapshot().configurationRevision()
            || !task.equals(activity.assessmentPanel.selectedTaskId())
            || !saved.feature().equals(currentFeature()) || saved.subtab() != resumeSubtab(saved.feature())
            || !saved.project().equals(WorkspaceResume.projectIdentity(api))
            || !searchText.equals(filter.getText()) || !filters.equals(trafficBrowser.captureSearchState())) {
          source.stop(); return;
        }
        if (!trafficBrowser.idle()) {
          if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(15)) {
            source.stop(); status.setText("검색이 아직 처리 중입니다. 완료 후 원본을 직접 선택하세요. 요청 전송 없음");
          }
          return;
        }
        source.stop();
        if (!trafficBrowser.searchViewCurrent()) {
          status.setText("검색 조건이 적용되지 않아 원본을 선택하지 않았습니다. 검색 상태를 확인하세요."); return;
        }
        var original = WorkspaceResume.original(store, saved.original());
        if (original == null) { status.setText("연결 원본이 현재 저장소에 없습니다. 원본을 직접 선택하세요."); return; }
        int view = logTable.convertRowIndexToView(Math.toIntExact(original.sequence()));
        if (view < 0) { status.setText("이어갈 원본은 현재 검색 조건에서 숨겨져 있습니다. 조건을 조정해 직접 선택하세요."); return; }
        logTable.setRowSelectionInterval(view, view);
        logTable.scrollRectToVisible(logTable.getCellRect(view, 0, true));
        status.setText("저장한 정확한 원본 선택 · 계정 연결·증거 파일 확인 후 실행을 직접 선택하세요.");
      } catch (RuntimeException error) {
        source.stop(); status.setText("원본 선택을 완료하지 못했습니다. 현재 검색 조건과 원본을 직접 확인하세요.");
      }
    });
    resumeSelectionTimer.start();
  }

  private String currentFeature() {
    Component selected = tabs.getSelectedComponent();
    String id = selected instanceof JComponent component
        ? Objects.toString(component.getClientProperty("wb.feature.id"), "traffic")
        : "traffic";
    if (id.equals("traffic") && trafficTools != null && trafficTools.getSelectedIndex() == 1) return "data";
    if (id.equals("environment") && explorationTools.getSelectedIndex() == 1) return "passive";
    if (id.equals("mapper") && mapperTools.getSelectedIndex() == 1) return "activity";
    return id;
  }

  private String currentNavigationRoute() {
    String route=currentFeature();
    if(route.equals("data") && dataTabs!=null && dataTabs.getSelectedIndex()==1)return "jwt";
    if(route.equals("bounty") && bountySupport!=null && bountySupport.pages.getSelectedIndex()==3)return "evidence";
    return route;
  }

  private void openUsabilityRoute(String route) {
    if(unloaded.get())return;
    switch(route) {
      case "scope" -> showScopeList();
      case "auth.settings" -> { openFeature("auth");authCompare.comparisonSettings.open(); }
      case "jwt" -> { openFeature("data");dataTabs.setSelectedIndex(1); }
      case "evidence" -> { openFeature("bounty");bountySupport.pages.setSelectedIndex(3); }
      default -> openFeature(route);
    }
  }

  private void openJobMonitor() {
    if(unloaded.get())return;
    if(jobMonitor==null) {
      jobMonitor=new JobMonitorPanel(backgroundJobs,this::openUsabilityRoute);
      jobMonitorWindow=new TargetWindow(root,"진행 중인 작업 현황",()->jobMonitor);
      jobMonitorWindow.opener=(owner,title,content,modal,closed)->dialogOpener.open(owner,title,content,modal,closed);
    }
    jobMonitor.refresh();jobMonitorWindow.open();
  }

  private void openGettingStarted() {
    if(unloaded.get())return;
    if(gettingStarted==null) {
      gettingStarted=new GettingStartedPanel(this::gettingStartedReadiness,this::openUsabilityRoute);
      gettingStartedWindow=new TargetWindow(root,"시작 준비 체크리스트",()->gettingStarted);
      gettingStartedWindow.opener=(owner,title,content,modal,closed)->dialogOpener.open(owner,title,content,modal,closed);
    }
    gettingStarted.refresh();gettingStartedWindow.open();
  }

  GettingStartedPanel.Readiness gettingStartedReadiness() {
    boolean scoped=localScope!=null && localScope.rules().stream().anyMatch(localScope::enabled);
    long projectTraffic=trafficStore==null?0:Math.max(0,trafficStore.size()-(checklistTrafficBaselineStore==trafficStore?checklistTrafficBaseline:0));
    return new GettingStartedPanel.Readiness(scoped,captureMode.get()==CaptureMode.SCOPE,
        projectTraffic,authCompare==null?0:authCompare.currentChecklistAccountCount(),
        authCompare==null?0:authCompare.currentChecklistCompletedCount(),bountySupport!=null && bountySupport.hasSavedEvidence());
  }

  private record NavigationPosition(TrafficBrowser.SearchPosition search,String project,String route,
                                    String[] originals,Point viewport) {}

  private void configureUsabilityNavigation() {
    navigation.configureRoutes(this::openUsabilityRoute,this::currentNavigationRoute,
        () -> workspaceResume==null?"":workspaceResume.project());
    navigation.setViewStateHooks(this::captureNavigationPosition,this::restoreNavigationPosition);
    for(JTabbedPane inner:new JTabbedPane[]{trafficTools,explorationTools,mapperTools,dataTabs,
        activity.coveragePages,bountySupport.pages})if(inner!=null)inner.addChangeListener(e->navigation.navigationChanged());
    logTable.addMouseListener(new java.awt.event.MouseAdapter(){public void mousePressed(java.awt.event.MouseEvent e){navigationInteractionRevision++;}});
    logTable.addKeyListener(new java.awt.event.KeyAdapter(){public void keyPressed(java.awt.event.KeyEvent e){navigationInteractionRevision++;}});
  }

  private Object captureNavigationPosition(String route) {
    // Navigation events already queued on the EDT can run after the host has closed the
    // store. Share the capture lifecycle lock so unload cannot close it mid-bookmark.
    synchronized(captureLock){
      if(unloaded.get() || trafficBrowser==null || trafficBrowser.closed || logTable==null || trafficStore==null
          || root!=null&&Boolean.TRUE.equals(root.getClientProperty("wb.skin.closed")))return null;
      DiskTrafficStore disk=trafficStore;
      int[] selected=logTable.getSelectedRows();String[] originals=new String[selected.length<=200?selected.length:0];
      for(int i=0;i<originals.length;i++){
        int raw=logTable.convertRowIndexToModel(selected[i]);if(raw<0||raw>=disk.size())return null;
        originals[i]=disk.ref(raw).id();
      }
      JViewport viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,logTable);
      return new NavigationPosition(trafficBrowser.capturePosition(),workspaceProject,route,originals,
          viewport==null?new Point():new Point(viewport.getViewPosition()));
    }
  }

  private void restoreNavigationPosition(Object value) {
    if(unloaded.get() || trafficBrowser==null || trafficBrowser.closed
        || root!=null&&Boolean.TRUE.equals(root.getClientProperty("wb.skin.closed"))
        || !(value instanceof NavigationPosition saved) || !saved.project().equals(workspaceProject))return;
    long revision=++navigationRestoreRevision;
    if(navigationSelectionTimer!=null)navigationSelectionTimer.stop();
    long interaction=navigationInteractionRevision,page=assessmentNavigationRevision;
    long configuration=workContext.snapshot().configurationRevision();
    trafficBrowser.restorePosition(saved.search());
    // The navigation component restores retained tabs/table state before this queued checkpoint.
    SwingUtilities.invokeLater(()->selectNavigationOriginals(saved,revision,interaction,page,configuration));
  }

  private void selectNavigationOriginals(NavigationPosition saved,long revision,long interaction,long page,long configuration) {
    if(unloaded.get() || revision!=navigationRestoreRevision)return;
    long started=System.nanoTime();
    navigationSelectionTimer=new javax.swing.Timer(60,event->{
      javax.swing.Timer timer=(javax.swing.Timer)event.getSource();
      try {
        if(unloaded.get() || revision!=navigationRestoreRevision || interaction!=navigationInteractionRevision
            || page!=assessmentNavigationRevision || configuration!=workContext.snapshot().configurationRevision()
            || !saved.project().equals(WorkspaceResume.projectIdentity(api))
            || !saved.route().equals(currentNavigationRoute())
            || !saved.search().equals(trafficBrowser.capturePosition())) {timer.stop();return;}
        if(!trafficBrowser.positionReady(saved.search())){
          if(System.nanoTime()-started>TimeUnit.SECONDS.toNanos(15)){timer.stop();status.setText("이전 검색 결과가 아직 준비되지 않았습니다. 완료 후 원본을 직접 선택하세요.");}
          return;
        }
        timer.stop();int[] views=new int[saved.originals().length];
        for(int i=0;i<views.length;i++) {
          var original=WorkspaceResume.original(trafficStore,saved.originals()[i]);
          if(original==null || (views[i]=logTable.convertRowIndexToView(Math.toIntExact(original.sequence())))<0){
            status.setText("이전 선택 원본이 없거나 현재 검색에서 숨겨져 있습니다. 원본을 직접 선택하세요.");return;
          }
        }
        if(views.length>0){logTable.getSelectionModel().setValueIsAdjusting(true);try{logTable.clearSelection();for(int row:views)logTable.addRowSelectionInterval(row,row);}finally{logTable.getSelectionModel().setValueIsAdjusting(false);}}
        JViewport viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,logTable);
        if(viewport!=null){Point point=saved.viewport();int max=Math.max(0,logTable.getHeight()-viewport.getHeight());viewport.setViewPosition(new Point(Math.max(0,point.x),Math.min(Math.max(0,point.y),max)));}
      } catch(RuntimeException error){timer.stop();status.setText("이전 화면의 선택을 복원하지 못했습니다. 현재 조건과 원본을 확인하세요.");}
    });
    navigationSelectionTimer.start();
  }

  /** Stable feature IDs keep old help links working after tools move into focused workspaces. */
  void openFeature(String id) {
    if (unloaded.get()) return;
    String pageId = switch (id) {
      case "data" -> "traffic";
      case "passive" -> "environment";
      case "activity" -> "mapper";
      default -> id;
    };
    for (int i = 0; i < tabs.getTabCount(); i++) {
      Component target = tabs.getComponentAt(i);
      if (target instanceof JComponent component && pageId.equals(component.getClientProperty("wb.feature.id"))) {
        tabs.setSelectedIndex(i);
        switch (id) {
          case "traffic" -> trafficTools.setSelectedIndex(0);
          case "data" -> trafficTools.setSelectedIndex(1);
          case "environment" -> explorationTools.setSelectedIndex(0);
          case "passive" -> explorationTools.setSelectedIndex(1);
          case "mapper" -> mapperTools.setSelectedIndex(0);
          case "activity" -> mapperTools.setSelectedIndex(1);
          default -> { }
        }
        return;
      }
    }
  }

  private void updateFeatureHelp() {
    String id = currentFeature();
    featureHelp.setToolTipText(FeatureGuide.feature(id).summary() + " · F1로 전체 기능과 사용 순서 보기");
  }

  private void openFeatureHelp() {
    if (unloaded.get()) return;
    if (featureHelpContent == null) {
      featureHelpContent = new FeatureGuidePanel();
      featureHelpContent.setName("wb.feature.guide");
      featureChoices = new JComboBox<>(FeatureGuide.all().toArray(FeatureGuide.Feature[]::new));
      featureChoices.setName("wb.feature.guide.choice");
      featureHelpText.setName("wb.feature.guide.text");
      featureHelpText.setEditable(false);
      featureHelpText.setLineWrap(true);
      featureHelpText.setWrapStyleWord(true);
      featureHelpText.putClientProperty("wb.prose", true);
      JButton open = new JButton("이 기능 열기");
      open.setName("wb.feature.guide.open");
      open.setToolTipText("선택한 기능 화면으로 이동합니다. 검사나 추가 요청을 실행하지 않습니다.");
      Runnable show =
          () -> {
            FeatureGuide.Feature chosen = (FeatureGuide.Feature) featureChoices.getSelectedItem();
            if (chosen == null) return;
            featureHelpText.setText(FeatureGuide.helpText(chosen.id()));
            featureHelpText.setCaretPosition(0);
            open.setEnabled(
                !chosen.id().equals("start")
                    && (!chosen.id().equals("payload") || payloadPage != null));
          };
      featureChoices.addActionListener(e -> show.run());
      open.addActionListener(
          e -> {
            if (unloaded.get()) return;
            FeatureGuide.Feature chosen = (FeatureGuide.Feature) featureChoices.getSelectedItem();
            if (chosen == null) return;
            openFeature(chosen.id());
          });
      featureHelpContent.add(
          WorkbenchUi.row(new JLabel("알아볼 기능"), featureChoices, open), BorderLayout.NORTH);
      featureHelpContent.add(featureHelpText);
      show.run();
      WorkbenchUi.style(featureHelpContent);
    }
    featureChoices.setSelectedItem(FeatureGuide.feature(currentFeature()));
    if (featureHelpWindow != null && featureHelpWindow.isDisplayable()) {
      featureHelpWindow.toFront();
      featureHelpWindow.requestFocus();
      return;
    }
    featureHelpWindow =
        dialogOpener.open(
            root,
            "Workbench Simple · 기능 안내",
            featureHelpContent,
            false,
            () -> featureHelpWindow = null);
  }

  private void updateLogButton() {
    String label = diagnostics.size() == 0 ? "로그" : "로그 (" + diagnostics.size() + ")";
    if (!label.equals(logButton.getText())) logButton.setText(label);
    logButton.setForeground(diagnostics.size() == 0 ? WorkbenchUi.ink() : WorkbenchUi.toneInk(WorkbenchUi.Tone.ERROR));
  }

  private void openLogs() {
    if (unloaded.get()) return;
    logContent = diagnostics.panel(this::captureDiagnostics);
    WorkbenchUi.style(logContent);
    if (logWindow != null && logWindow.isDisplayable()) { logWindow.toFront(); return; }
    logWindow = dialogOpener.open(root, "Workbench Simple · 로그", logContent, false, () -> logWindow = null);
    if (logWindow != null) logWindow.setMinimumSize(new Dimension(680,420));
  }

  private static final class FeatureGuidePanel extends JPanel implements Scrollable {
    FeatureGuidePanel() {
      super(new BorderLayout(0, 8));
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
      return 20;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
      return Math.max(20, visible.height - 20);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
      return false;
    }
  }

  private JPanel settingsPanel() {
    JPanel page = new JPanel(new BorderLayout(8, 8));
    page.setName("wb.common.settings");
    page.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    commonSettings = new LayoutSettings(api, root);
    commonSettingsContent = new JPanel();
    commonSettingsContent.setLayout(new BoxLayout(commonSettingsContent, BoxLayout.Y_AXIS));
    commonSettingsContent.add(WorkbenchUi.card("화면 배치", commonSettings));
    commonSettingsContent.add(WorkbenchUi.row(new JLabel("모든 도구에 공통 적용 · 데이터 도구는 입력 / 출력 방향에 적용")));
    JButton open = button("설정 창 열기", this::openSettings);
    open.setName("wb.common.settings.open");
    WorkbenchUi.primary(open);
    page.add(
        WorkbenchUi.header("설정", "공통 설정은 별도 창에서 관리합니다.", WorkbenchUi.row(open)),
        BorderLayout.NORTH);
    return page;
  }

  private void openSettings() {
    if (unloaded.get()) return;
    if (commonSettingsWindow != null && commonSettingsWindow.isDisplayable()) {
      commonSettingsWindow.toFront();
      commonSettingsWindow.requestFocus();
      return;
    }
    commonSettingsWindow =
        dialogOpener.open(
            root,
            "Workbench Simple · 화면 설정",
            commonSettingsContent,
            false,
            () -> commonSettingsWindow = null);
  }

  private void captureObserved(List<HttpRequestResponse> packets, String note) {
    bountySupport.captureObserved(packets, note);
    tabs.setSelectedComponent(bountySupport);
  }

  private JPanel dataPanel() {
    dataTabs = new JTabbedPane();
    dataTabs.addTab("인코딩·변환", withHeading("인코딩·변환", "문자열을 바꾸고 입력·출력을 함께 확인", codecPanel()));
    dataTabs.addTab("JWT 내용 확인", withHeading("JWT 내용 확인", "토큰 내용 읽기 · 서명 검증은 하지 않음", jwtPanel()));
    structuredCompare = new StructuredComparePanel(api);
    structuredCompare.selectedTraffic =
        () ->
            selectedTraffic().stream()
                .map(t -> HttpRequestResponse.httpRequestResponse(t.request(), t.response()))
                .toList();
    dataTabs.addTab("응답·데이터 비교", structuredCompare);
    JPanel page = new JPanel(new BorderLayout());
    page.setName("wb.data.tools");
    page.putClientProperty("wb.data.tools", true);
    page.add(dataTabs);
    return page;
  }

  private JMenuItem trafficAction(String title, Runnable action) {
    JMenuItem item = new JMenuItem(title);
    item.addActionListener(
        e -> {
          try {
            action.run();
          } catch (Exception ex) {
            error(ex);
          }
        });
    return item;
  }

  java.util.function.BiFunction<Component,String,String> trafficNotePrompt=(owner,current)->JOptionPane.showInputDialog(owner,"메모 (1,000자 이내)",current);

  void editTrafficNote(){
    int selected=logTable.getSelectedRow();if(selected<0||logTable.getSelectedRowCount()!=1)throw new IllegalArgumentException("메모를 편집할 요청 1개를 선택하세요.");
    int raw=logTable.convertRowIndexToModel(selected);DiskTrafficStore disk=trafficStore;String project=trafficBrowser.projectIdentity(),id=disk.ref(raw).id();
    String text=trafficNotePrompt.apply(logTable,Objects.toString(logModel.getValueAt(raw,10),""));
    if(text!=null){if(disk!=trafficStore||!project.equals(trafficBrowser.projectIdentity())||raw>=disk.size()||!id.equals(disk.ref(raw).id()))throw new IllegalArgumentException("프로젝트나 선택 원본이 바뀌었습니다. 다시 선택하세요.");trafficBrowser.note(raw,text);}
    logTable.requestFocusInWindow();
  }
  void toggleSelectedTrafficGroup(){int row=logTable.getSelectedRow();if(row>=0)trafficBrowser.toggleGroup(logTable.convertRowIndexToModel(row));logTable.requestFocusInWindow();}
  private void updateTrafficMenu(JPopupMenu menu){int count=logTable.getSelectedRowCount();for(Component c:menu.getComponents())if(c instanceof JMenuItem item){item.setEnabled(count>0&&!"wb.traffic.shortcuts".equals(item.getName()));if("wb.traffic.mark".equals(item.getName()))item.setText("모두 중요 표시 ("+count+"건)");if("wb.traffic.unmark".equals(item.getName()))item.setText("모두 중요 해제 ("+count+"건)");if("wb.traffic.stars.undo.menu".equals(item.getName()))item.setEnabled(trafficBrowser!=null&&trafficBrowser.undoStars.isEnabled());}}
  private void installTrafficKeyboard(JPopupMenu menu){
    logTable.setToolTipText("행 작업: Shift+F10 · 중요 표시 Alt+I / 해제 Alt+Shift+I · 메모 Alt+M · 묶음 Alt+G · 중요 변경 취소 Alt+Z");
    menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener(){public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e){updateTrafficMenu(menu);}public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e){logTable.requestFocusInWindow();}public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e){logTable.requestFocusInWindow();}});
    bindTrafficKey("shift F10","wb.traffic.popup",()->{int row=logTable.getSelectedRow();if(row<0)return;showTraffic(row);updateTrafficMenu(menu);Rectangle cell=logTable.getCellRect(row,0,true);if(logTable.isShowing())menu.show(logTable,cell.x,cell.y+cell.height);});
    // Both physical context-menu keys share the same table-local action.
    logTable.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_CONTEXT_MENU,0),"wb.traffic.popup");
    bindTrafficKey("alt I","wb.traffic.mark",()->trafficBrowser.setSelectedStars(true));
    bindTrafficKey("alt shift I","wb.traffic.unmark",()->trafficBrowser.setSelectedStars(false));
    bindTrafficKey("alt M","wb.traffic.note",this::editTrafficNote);
    bindTrafficKey("alt G","wb.traffic.group",this::toggleSelectedTrafficGroup);
    bindTrafficKey("alt Z","wb.traffic.stars.undo",trafficBrowser::undoStarEdit);
  }
  private void bindTrafficKey(String stroke,String name,Runnable task){logTable.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(stroke),name);logTable.getActionMap().put(name,new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){try{task.run();}catch(RuntimeException error){WorkbenchExtension.this.error(error);}}});}

  private JPanel trafficPanel() {
    captureButton =
        button(
            "OFF",
            () -> {
              synchronized (captureLock) {
                captureMode.set(captureMode.get().next());
              }
              refreshTrafficInfo();
            });
    captureButton.setName("wb.traffic.capture.mode");
    captureButton.putClientProperty("wb.capture", true);
    captureButton.setToolTipText(
        "클릭: OFF ↔ SCOPE · OFF 수집 중지 / SCOPE 목록에서 체크한 Burp Scope·수동 URL 수집");
    logTable =
        new JTable(logModel) {
          @Override public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer,int row,int column){
            Component component=super.prepareRenderer(renderer,row,column);
            if(component instanceof JLabel label&&convertColumnIndexToModel(column)==2&&trafficBrowser!=null){
              int raw=convertRowIndexToModel(row);label.setText(trafficBrowser.displayUrl(raw,Objects.toString(logModel.getValueAt(raw,2),"")));
            }
            return component;
          }

          public String getToolTipText(java.awt.event.MouseEvent e) {
            int row = rowAtPoint(e.getPoint());
            if (row < 0) return null;
            int index = convertRowIndexToModel(row);
            if(trafficBrowser!=null&&trafficBrowser.view!=null){var g=trafficBrowser.view.groups().get(index);if(g!=null&&g.members().size()>1)return "원본 "+g.members().size()+"건 · 상태 "+g.statuses()+" · 더블클릭/Alt+G 펼치기·접기 · Shift+F10 행 작업";}
            return index < traffic.size()
                ? WorkbenchScope.reason(api, traffic.get(index).request())+" · 행 작업 Shift+F10 · 메모 Alt+M"
                : null;
          }
        };
    logTable.setName("wb.traffic.history");
    logTable.putClientProperty("wb.table.compact", true);
    logTable.setRowSorter(
        new CaptureRowSorter(logModel, error -> captureFailure("트래픽 검색·정렬", error)));
    logTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    JPopupMenu trafficMenu = new JPopupMenu();
    trafficMenu.add(
        trafficAction(
            "URL 후보 수집 시작",
            () -> {
              if (current == null) throw new IllegalArgumentException("요청을 선택하세요.");
              openPassive(current.request().url());
            }));
    trafficMenu.addSeparator();
    trafficMenu.add(
        trafficAction(
            "선택 요청 계정 비교 실행",
            () -> {
              selectedTraffic();
              tabs.setSelectedIndex(1);
              authCompare.reviewSelection();
            }));
    trafficMenu.add(
        trafficAction(
            "Repeater에 요청 준비",
            () ->
                selectedTraffic()
                    .forEach(t -> api.repeater().sendToRepeater(t.request(), "Workbench"))));
    trafficMenu.add(trafficAction("입력값 검사에 요청 연결", this::selectedPayload));
    trafficMenu.add(
        trafficAction(
            "재현·증거 정리 열기",
            () -> {
              selectedTraffic();
              tabs.setSelectedComponent(bountySupport);
            }));
    trafficMenu.add(trafficAction("선택 요청 JWT 확인", this::selectedJwt));
    trafficMenu.add(trafficAction("선택 응답에서 API 찾기", this::selectedJs));
    trafficMenu.add(
        trafficAction(
            "선택 요청 변환하기",
            () -> {
              if (current == null) throw new IllegalArgumentException("요청을 선택하세요.");
              codecIn.setText(current.request().toString());
              openFeature("data");
              dataTabs.setSelectedIndex(0);
            }));
    JMenu scopeMenu = new JMenu("SCOPE 지정");
    scopeMenu.setName("wb.traffic.scope.menu");
    scopeMenu.add(trafficAction("선택 URL 경로 추가", () -> addSelectedScope(false)));
    JMenuItem siteScope = trafficAction("현재 사이트 전체 경로 추가", () -> addSelectedScope(true));
    siteScope.setToolTipText("선택 URL과 같은 프로토콜·호스트·포트의 모든 경로를 포함합니다.");
    scopeMenu.add(siteScope);
    scopeMenu.add(trafficAction("SCOPE 목록 보기", this::showScopeList));
    trafficMenu.addSeparator();
    trafficMenu.add(scopeMenu);
    logTable.putClientProperty("wb.traffic.popup", trafficMenu);
    logTable.addMouseListener(
        new java.awt.event.MouseAdapter() {
          void popup(java.awt.event.MouseEvent e) {
            if (!e.isPopupTrigger()) return;
            int row = logTable.rowAtPoint(e.getPoint());
            if (row >= 0) {
              if (!logTable.isRowSelected(row)) logTable.setRowSelectionInterval(row, row);
              showTraffic(row);
            } else logTable.clearSelection();
            updateTrafficMenu(trafficMenu);
            trafficMenu.show(logTable, e.getX(), e.getY());
          }

          public void mousePressed(java.awt.event.MouseEvent e) {
            popup(e);
          }

          public void mouseReleased(java.awt.event.MouseEvent e) {
            popup(e);
          }
        });
    for (int i = 6; i >= 4; i--) logTable.removeColumn(logTable.getColumnModel().getColumn(i));
    int[] widths = {56, 75, 500, 65};
    for (int i = 0; i < widths.length; i++) {
      logTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
      logTable.getColumnModel().getColumn(i).setWidth(widths[i]);
    }
    logTable.getColumnModel().getColumn(0).setMinWidth(56);
    logTable.getColumnModel().getColumn(1).setMinWidth(65);
    logTable.getColumnModel().getColumn(3).setMinWidth(60);
    logTable.getColumnModel().getColumn(0).setMaxWidth(75);
    logTable.getColumnModel().getColumn(1).setMaxWidth(100);
    logTable.getColumnModel().getColumn(3).setMaxWidth(85);
    TableRowSorter<?> sorter = (TableRowSorter<?>) logTable.getRowSorter();
    sorter.setComparator(
        0, (a, b) -> Long.compare(((Number) a).longValue(), ((Number) b).longValue()));
    filter.setName("wb.traffic.search");
    filter.setColumns(20);
    filter.setToolTipText("메서드·URL·상태 검색 · Ctrl+F");
    trafficBrowser=new TrafficBrowser(logTable,logModel,filter,api,()->trafficStore,this::refreshTrafficInfo);
    trafficBrowser.useSimpleView();
    trafficBrowser.onViewPublished(this::refreshSelectedSearchContext);
    trafficBrowser.identities=()->{
      if(authCompare==null)return List.of();List<TrafficBrowser.Identity> profiles=new ArrayList<>();
      for(var entry:authCompare.accounts.entrySet()){var credential=authCompare.credentials.get(entry.getKey());if(credential!=null)profiles.add(new TrafficBrowser.Identity(credential.origin(),entry.getValue().label(),credential.headers()));}
      for(int i=0;i<3;i++){var credential=authCompare.slots[i];if(credential!=null)profiles.add(new TrafficBrowser.Identity(credential.origin(),authCompare.label(i),credential.headers()));}
      return profiles;
    };
    trafficMenu.addSeparator();
    JMenuItem mark=trafficAction("모두 중요 표시",()->trafficBrowser.setSelectedStars(true));
    mark.setName("wb.traffic.mark");mark.setAccelerator(KeyStroke.getKeyStroke("alt I"));trafficMenu.add(mark);
    JMenuItem unmark=trafficAction("모두 중요 해제",()->trafficBrowser.setSelectedStars(false));
    unmark.setName("wb.traffic.unmark");unmark.setAccelerator(KeyStroke.getKeyStroke("alt shift I"));trafficMenu.add(unmark);
    JMenuItem undoMark=trafficAction("중요 표시 변경 취소",trafficBrowser::undoStarEdit);
    undoMark.setName("wb.traffic.stars.undo.menu");undoMark.setAccelerator(KeyStroke.getKeyStroke("alt Z"));trafficMenu.add(undoMark);
    JMenuItem note=trafficAction("메모 편집",this::editTrafficNote);note.setAccelerator(KeyStroke.getKeyStroke("alt M"));trafficMenu.add(note);
    JMenuItem grouped=trafficAction("묶음 펼치기 / 접기",this::toggleSelectedTrafficGroup);grouped.setAccelerator(KeyStroke.getKeyStroke("alt G"));trafficMenu.add(grouped);
    installTrafficKeyboard(trafficMenu);
    trafficMenu.addSeparator();JMenuItem shortcuts=new JMenuItem("행 작업: Shift+F10 · 메뉴 키");shortcuts.setName("wb.traffic.shortcuts");shortcuts.setEnabled(false);trafficMenu.add(shortcuts);
    // Keep only actions that continue from the selected original. Settings and utilities have
    // their own tool screens; the request menu no longer repeats scope or bulk-management UI.
    trafficMenu.removeAll();
    trafficMenu.add(trafficAction("계정 비교 준비", this::prepareSimpleComparison));
    trafficMenu.add(trafficAction("Repeater에 요청 준비", this::prepareSimpleRepeater));
    trafficMenu.add(trafficAction("입력값 검사에 요청 연결", this::selectedPayload));
    trafficMenu.add(trafficAction("선택 응답에서 API 찾기", this::selectedJs));
    trafficMenu.addSeparator();
    trafficMenu.add(trafficAction("선택 원본을 증거에 추가", this::addSimpleEvidence));
    logTable.setToolTipText("요청 선택 → 비교 준비 또는 증거에 추가 · Shift+F10 행 메뉴 · Ctrl+R Repeater 준비");
    logTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    int[] extraWidths={45,110,55,130};for(int i=0;i<4;i++){logTable.getColumnModel().getColumn(i+4).setPreferredWidth(extraWidths[i]);logTable.getColumnModel().getColumn(i+4).setWidth(extraWidths[i]);}
    trafficBrowser.updateColumns();
    logTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting()) showTraffic(logTable.getSelectedRow());
            });
    scopeListButton = button("SCOPE 목록", this::showScopeList);
    scopeListButton.setName("wb.traffic.scope.list");
    JPanel actions = new JPanel(new GridLayout(1, 2, 10, 0));
    actions.add(scopeListButton);
    actions.add(captureButton);
    JPanel top = new JPanel(new BorderLayout(10, 0));
    top.setName("wb.traffic.controls");
    JPanel searching=new JPanel(new BorderLayout(6,0));searching.add(filter);
    JPanel live=new JPanel();live.setName("wb.traffic.live.controls");live.setOpaque(false);
    live.setLayout(new BoxLayout(live,BoxLayout.X_AXIS));
    live.add(trafficBrowser.freezeView);live.add(Box.createHorizontalStrut(5));
    live.add(trafficBrowser.newTraffic);live.add(Box.createHorizontalStrut(5));live.add(trafficBrowser.settingsButton);
    searching.add(live,BorderLayout.EAST);
    top.add(searching, BorderLayout.CENTER);
    top.add(actions, BorderLayout.EAST);
    JPanel req = WorkbenchUi.card("요청  REQUEST", requestPacket),
        res = WorkbenchUi.card("응답  RESPONSE", responsePacket);
    JSplitPane viewers = WorkbenchUi.split(JSplitPane.VERTICAL_SPLIT, req, res, .5);
    viewers.setName("wb.traffic.messages");
    JPanel messages = new JPanel(new BorderLayout(0, 6));
    messages.add(viewers);
    JSplitPane body =
        WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT, trafficListPanel(), messages, .30);
    body.setName("wb.traffic.workspace");
    JPanel controls = new JPanel(new BorderLayout(0, 5));
    controls.add(top, BorderLayout.NORTH);
    controls.add(trafficBrowser.conditionBar(), BorderLayout.CENTER);
    JButton compareSelected = button("계정 비교 준비", this::prepareSimpleComparison);
    JButton repeatSelected = button("Repeater 준비", this::prepareSimpleRepeater);
    JButton evidenceSelected = button("증거에 추가", this::addSimpleEvidence);
    compareSelected.setName("wb.simple.traffic.compare");
    repeatSelected.setName("wb.simple.traffic.repeater");
    evidenceSelected.setName("wb.simple.traffic.evidence");
    WorkbenchUi.primary(compareSelected);
    JLabel selectionInfo = new JLabel("요청을 선택하면 다음 작업을 진행할 수 있습니다.");
    selectionInfo.setName("wb.simple.traffic.selection");
    Runnable selectedState = () -> {
      int count = logTable.getSelectedRowCount();
      for (JButton action : new JButton[] {compareSelected, repeatSelected, evidenceSelected}) {
        action.setEnabled(count > 0);
        action.setToolTipText(count > 0 ? "선택 원본 " + count + "건 · 대상 요청은 자동 전송하지 않습니다." : "먼저 목록에서 요청을 선택하세요.");
      }
      selectionInfo.setText(count > 0 ? "선택 " + count + "건" : "요청을 선택하세요.");
    };
    logTable.getSelectionModel().addListSelectionListener(e -> selectedState.run());
    selectedState.run();
    controls.add(row(compareSelected, repeatSelected, evidenceSelected, selectionInfo), BorderLayout.SOUTH);
    JPanel main = page(controls, body);
    main.setBorder(BorderFactory.createEmptyBorder(8, 16, 12, 16));
    main.setName("wb.traffic");
    trafficTools = new JTabbedPane();
    trafficTools.setName("wb.traffic.tools");
    trafficTools.addTab("요청·응답", main);
    trafficTools.addTab("변환·비교", dataPanel());
    trafficTools.setToolTipTextAt(1, "선택 원본을 유지하며 인코딩·JWT·응답 차이를 로컬에서 확인합니다.");
    JPanel workspace = new JPanel(new BorderLayout());
    workspace.add(trafficTools);
    refreshTrafficInfo();
    return workspace;
  }

  private void prepareSimpleComparison() {
    selectedTraffic();
    tabs.setSelectedComponent(authCompare);
    authCompare.reviewSelection();
  }

  private void prepareSimpleRepeater() {
    selectedTraffic().forEach(t -> api.repeater().sendToRepeater(t.request(), "Workbench Simple"));
  }

  private void addSimpleEvidence() {
    captureObserved(selectedTraffic().stream()
        .map(t -> HttpRequestResponse.httpRequestResponse(t.request(), t.response())).toList(),
        "선택한 원본 요청·응답");
    bountySupport.pages.setSelectedIndex(3);
  }

  private void showNativePacket(boolean request) {
    if (unloaded.get()) return;
    JDialog window = request ? requestNativeWindow : responseNativeWindow;
    if (window != null && window.isDisplayable()) { window.toFront(); return; }
    Component editor = request ? requestEditorComponent : responseEditorComponent;
    if (editor == null) return;
    JPanel nativeHost = new JPanel(new BorderLayout());
    nativeHost.setName(request ? "wb.traffic.request.native" : "wb.traffic.response.native");
    nativeHost.putClientProperty("wb.nativeEditor", true);
    nativeHost.add(editor);
    JDialog opened = dialogOpener.open(root, "Workbench KR · Burp " + (request ? "요청" : "응답"), nativeHost, false,
        () -> { if (request) requestNativeWindow = null; else responseNativeWindow = null; });
    if (request) requestNativeWindow = opened; else responseNativeWindow = opened;
  }

  private void showTraffic(int view) {
    if (view >= 0) {
      int index = logTable.convertRowIndexToModel(view);
      if (index < traffic.size()) {
        current = traffic.get(index);
        workContext.select(current.packet(), Arrays.stream(logTable.getSelectedRows()).map(logTable::convertRowIndexToModel).toArray());
        HttpRequest request = current.request();
        HttpResponse response = current.response();
        if (requestView != null) requestView.setRequest(request);
        if (responseView != null) responseView.setResponse(response);
        var requestWire = request.toByteArray();
        requestPacket.setPacket(PacketInspector.prefix(requestWire), null, requestWire.length());
        var responseWire = response == null ? null : response.toByteArray();
        responsePacket.setPacket(PacketInspector.prefix(responseWire),
            responseWire != null && responseWire.length() <= PacketInspector.PREVIEW_BYTES ? PacketFormat.decodedResponse(response) : null,
            responseWire == null ? 0 : responseWire.length());
        selectionInfo.setText(
            String.format("%03d", ((Number) logModel.getValueAt(index, 0)).longValue())
                + "   "
                + request.method()
                + "   "
                + request.url()
                + "   · "
                + (response == null
                    ? "응답 없음"
                    : response.statusCode() + " · " + response.body().length() + " bytes"));
        selectionInfo.setText(
            selectionInfo.getText() + " · " + WorkbenchScope.reason(api, request));
        selectionMatchNote="";
        updateSelectionMatchNote(trafficBrowser.revealSelectionMatches(index,requestPacket,responsePacket));
        var position=trafficBrowser.capturePosition();
        if(trafficBrowser.positionReady(position)){revealedTrafficSearch=position;revealedTrafficOriginal=current.packet().id();}
        selectionInfo.setToolTipText(selectionInfo.getText());
      }
    } else {
      current = null;
      workContext.clear();
      if (requestView != null) requestView.setRequest(null);
      if (responseView != null) responseView.setResponse(null);
      requestPacket.setPacket(null, null);
      responsePacket.setPacket(null, null);
      selectionInfo.setText("목록에서 요청을 선택하세요 · 요청과 응답을 함께 표시합니다.");
      selectionMatchNote="";revealedTrafficSearch=null;revealedTrafficOriginal="";
    }
  }

  /** A retained selection may not fire a JTable event after a new search view is published. */
  private void refreshSelectedSearchContext() {
    if(unloaded.get() || trafficBrowser==null || logTable==null || trafficStore==null)return;
    var position=trafficBrowser.capturePosition();
    if(!trafficBrowser.positionReady(position))return;
    int selected=logTable.getSelectedRow();if(selected<0)return;
    int raw=logTable.convertRowIndexToModel(selected);String id=trafficStore.ref(raw).id();
    if(current==null || !id.equals(current.packet().id())){showTraffic(selected);return;}
    if(id.equals(revealedTrafficOriginal)&&position.equals(revealedTrafficSearch))return;
    updateSelectionMatchNote(trafficBrowser.revealSelectionMatches(raw,requestPacket,responsePacket));
    revealedTrafficSearch=position;revealedTrafficOriginal=id;
  }

  private void updateSelectionMatchNote(String note) {
    String text=selectionInfo.getText(),suffix=" · "+selectionMatchNote;
    if(!selectionMatchNote.isBlank()&&text.endsWith(suffix))text=text.substring(0,text.length()-suffix.length());
    selectionMatchNote=note;selectionInfo.setText(text+(note.isBlank()?"":" · "+note));
    selectionInfo.setToolTipText(selectionInfo.getText());
  }

  void openPassive(String url) {
    openFeature("passive");
    try {
      passiveScan.open(url, true);
    } catch (IllegalArgumentException e) {
      status.setText("URL 수집 · " + e.getMessage());
    }
  }

  private void refreshTrafficInfo() {
    if (featureMapper != null) featureMapper.refreshCaptureState();
    if (logTable == null) return;
    CaptureMode mode = captureMode.get();
    if (captureButton != null) {
      captureButton.setText(mode.name());
      captureButton.getAccessibleContext().setAccessibleDescription(switch (mode) {
        case OFF -> "빨강 · 트래픽 수집 중지";
        case SCOPE -> "주황 · SCOPE 목록에서 체크한 범위만 수집";
      });
      captureButton.setToolTipText(captureTooltip());
      captureButton.repaint();
    }
    captureInfo.setText(
        "보관 "
            + traffic.size()
            + " · 표시 "
            + logTable.getRowCount()
            + " · "
            + switch (mode) {
              case OFF -> "수집 중지";
              case SCOPE -> "체크된 SCOPE 수집";
            });
    captureInfo.setToolTipText(captureTooltip());
  }

  private boolean acceptsTraffic(HttpRequest request) {
    return switch (captureMode.get()) {
      case OFF -> false;
      case SCOPE -> {
        String url = request.url();
        if (nativeScopeFallback && localScope.syncObserved(java.util.List.of(url)))
          SwingUtilities.invokeLater(this::scopeChanged);
        yield localScope.matchesTraffic(url);
      }
    };
  }

  private void syncBurpScope() {
    if (unloaded.get()) return;
    try {
      String json = api.burpSuite().exportProjectOptionsAsJson("target.scope");
      if (json != null && json.contains("\"target\"")) {
        nativeScopeFallback = false;
        if (localScope.syncBurp(json)) scopeChanged();
        return;
      }
      nativeScopeFallback = true;
      java.util.List<String> urls = new java.util.ArrayList<>();
      synchronized (scopeCandidates) {
        urls.addAll(scopeCandidates);
      }
      var history = api.proxy().history();
      if (history != null)
        for (var item : history) if (item.request() != null) urls.add(item.request().url());
      var site = api.siteMap().requestResponses();
      if (site != null)
        for (var item : site) if (item.request() != null) urls.add(item.request().url());
      for (var item : traffic) urls.add(item.packet().url());
      if (localScope.syncObserved(urls)) scopeChanged();
    } catch (RuntimeException ex) {
      captureFailure("Burp Scope 동기화", ex);
    }
  }

  private void rememberScopeCandidate(String url) {
    String safe = WorkbenchScope.canonical(url);
    if (safe == null) return;
    synchronized (scopeCandidates) {
      if (scopeCandidates.size() >= 10000)
        scopeCandidates.remove(scopeCandidates.iterator().next());
      scopeCandidates.add(safe);
    }
  }

  private JPanel withHeading(String title, String hint, JPanel content) {
    JPanel p = new JPanel(new BorderLayout(0, 4));
    p.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
    p.add(WorkbenchUi.header(title, hint, null), BorderLayout.NORTH);
    p.add(content);
    return p;
  }

  private static final class ScopePanel extends JPanel implements Scrollable {
    ScopePanel() {
      super(new BorderLayout(4, 4));
    }

    public Dimension getPreferredScrollableViewportSize() {
      return new Dimension(720, 360);
    }

    public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) {
      return 24;
    }

    public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
      return 120;
    }

    public boolean getScrollableTracksViewportWidth() {
      return true;
    }

    public boolean getScrollableTracksViewportHeight() {
      return true;
    }
  }

  private Component trafficListPanel() {
    JPanel panel = new ScopePanel();
    scopeTable.setName("wb.traffic.scope.table");
    scopeTable.setAutoCreateRowSorter(true);
    scopeTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    int[] widths = {55, 90, 350, 220};
    for (int i = 0; i < widths.length; i++)
      scopeTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    scopeTable.getColumnModel().getColumn(0).setMaxWidth(65);
    scopeTable.getColumnModel().getColumn(0).setMinWidth(55);
    scopeTable.getColumnModel().getColumn(1).setMinWidth(90);
    scopeModel.addTableModelListener(
        e -> {
          if (refreshingScope
              || e.getType() != javax.swing.event.TableModelEvent.UPDATE
              || e.getColumn() != 0) return;
          int index = e.getFirstRow();
          List<WorkbenchScope.Rule> rules = localScope.rules();
          if (index >= 0 && index < rules.size()) {
            localScope.setEnabled(
                rules.get(index), Boolean.TRUE.equals(scopeModel.getValueAt(index, 0)));
            refreshTrafficInfo();
            if (current != null && logTable.getSelectedRow() >= 0)
              showTraffic(logTable.getSelectedRow());
          }
        });
    JScrollPane rulesScroll = new JScrollPane(scopeTable);
    rulesScroll.setPreferredSize(new Dimension(720, 260));
    rulesScroll.setMinimumSize(new Dimension(0, 45));
    panel.add(rulesScroll);
    JTextArea note =
        new JTextArea("체크된 범위만 SCOPE 수집 · 수동 URL은 행 선택 후 수정");
    note.setEditable(false);
    note.setLineWrap(true);
    note.setWrapStyleWord(true);
    panel.add(note, BorderLayout.NORTH);
    JTextField urlInput = new JTextField(24);
    scopeUrlInput = urlInput;
    urlInput.setName("wb.traffic.scope.url");
    urlInput.setToolTipText("HTTP(S) URL과 하위 경로에 적용 · 쿼리 제외 · Burp Scope는 Burp에서 변경");
    Runnable add =
        () -> {
          localScope.add(urlInput.getText().trim(), false);
          scopeTable.clearSelection();
          urlInput.setText("");
          scopeChanged();
        };
    JButton addButton = button("URL 추가", add);
    addButton.setName("wb.traffic.scope.add");
    scopeEditButton = button("URL 수정", () -> {
      if (scopeTable.isEditing()) scopeTable.getCellEditor().stopCellEditing();
      WorkbenchScope.Rule selected = selectedScopeRule();
      if (selected == null) throw new IllegalArgumentException("수정할 수동 URL 한 개를 선택하세요.");
      WorkbenchScope.Rule updated = localScope.update(selected, urlInput.getText().trim());
      scopeChanged();
      selectScopeRule(updated);
      urlInput.setText(updated.url());
    });
    scopeEditButton.setName("wb.traffic.scope.edit");
    scopeEditButton.setToolTipText("수동 URL 한 행을 선택하고 입력값을 바꾼 뒤 수정합니다. 체크 상태는 유지됩니다.");
    scopeEditButton.setEnabled(false);
    scopeTable.getSelectionModel().addListSelectionListener(e -> {
      if (!e.getValueIsAdjusting() && !refreshingScope) refreshScopeEditor();
    });
    urlInput.addActionListener(
        e -> {
          try {
            add.run();
          } catch (Exception ex) {
            error(ex);
          }
        });
    JButton remove =
        button(
            "선택 삭제",
            () -> {
              if (scopeTable.isEditing()) scopeTable.getCellEditor().stopCellEditing();
              for (var rule : localScope.rules())
                if (localScope.enabled(rule)) localScope.remove(rule);
              scopeChanged();
            });
    remove.setName("wb.traffic.scope.remove");
    remove.setToolTipText("행 강조 선택과 무관하게 체크된 URL만 삭제합니다.");
    JButton clear =
        button(
            "일괄 삭제",
            () -> {
              if (scopeTable.isEditing()) scopeTable.getCellEditor().stopCellEditing();
              localScope.clear();
              scopeChanged();
            });
    clear.setName("wb.traffic.scope.clear");
    JPanel inputRow = new JPanel(new BorderLayout(8, 0));
    inputRow.add(new JLabel("URL"), BorderLayout.WEST);
    inputRow.add(urlInput);
    JPanel editor = new JPanel(new BorderLayout(0, 4));
    editor.add(inputRow, BorderLayout.NORTH);
    editor.add(row(addButton, scopeEditButton, remove, clear), BorderLayout.SOUTH);
    panel.add(editor, BorderLayout.SOUTH);
    scopeContent = panel;
    scopeContent.setName("wb.traffic.scope.content");
    WorkbenchUi.style(scopeContent);
    refreshScopeList();
    return new JScrollPane(logTable);
  }

  private void showScopeList() {
    if (unloaded.get()) return;
    if (scopeWindow != null && scopeWindow.isDisplayable()) {
      scopeWindow.toFront();
      scopeWindow.requestFocus();
      return;
    }
    refreshScopeList();
    scopeWindow =
        dialogOpener.open(
            root, "Workbench KR · SCOPE 목록", scopeContent, false, () -> scopeWindow = null);
  }

  private void refreshScopeList() {
    WorkbenchScope.Rule selected = selectedScopeRule();
    refreshingScope = true;
    try {
      scopeModel.setRowCount(0);
      for (var rule : localScope.rules())
        scopeModel.addRow(
            new Object[] {
              localScope.enabled(rule),
              rule.burpKey() != null ? "Burp Scope" : rule.originOnly() ? "도메인 전체" : "URL 경로",
              rule.url(),
              localScope.overlap(rule)
            });
    } finally {
      refreshingScope = false;
    }
    if (selected != null) selectScopeRule(selected);
    refreshScopeEditor();
    if (scopeListButton != null)
      scopeListButton.setText("SCOPE 목록 (" + scopeModel.getRowCount() + ")");
  }

  private WorkbenchScope.Rule selectedScopeRule() {
    if (scopeTable.getSelectedRowCount() != 1) return null;
    int index = scopeTable.convertRowIndexToModel(scopeTable.getSelectedRow());
    // Read the displayed identity: local rules can have changed before the UI refresh.
    if (index < 0 || index >= scopeModel.getRowCount()) return null;
    String url = Objects.toString(scopeModel.getValueAt(index, 2), "");
    String type = Objects.toString(scopeModel.getValueAt(index, 1), "");
    return localScope.rules().stream().filter(r -> r.url().equals(url)
        && (r.burpKey() != null ? "Burp Scope" : r.originOnly() ? "도메인 전체" : "URL 경로").equals(type))
        .findFirst().orElse(null);
  }

  private void selectScopeRule(WorkbenchScope.Rule rule) {
    int index = localScope.rules().indexOf(rule);
    if (index < 0) return;
    int view = scopeTable.convertRowIndexToView(index);
    if (view >= 0) {
      scopeTable.setRowSelectionInterval(view, view);
      scopeTable.scrollRectToVisible(scopeTable.getCellRect(view, 2, true));
    }
  }

  private void refreshScopeEditor() {
    if (scopeEditButton == null) return;
    WorkbenchScope.Rule selected = selectedScopeRule();
    boolean editable = selected != null && selected.burpKey() == null;
    scopeEditButton.setEnabled(editable);
    if (editable && !selected.equals(scopeEditingRule)) scopeUrlInput.setText(selected.url());
    scopeEditingRule = editable ? selected : null;
  }

  private void addSelectedScope(boolean originOnly) {
    List<Traffic> chosen = selectedTraffic();
    List<Traffic> newlyIncluded =
        traffic.stream().filter(t -> !WorkbenchScope.contains(api, t.request())).toList();
    for (Traffic t : chosen) localScope.add(t.request().url(), originOnly);
    scopeChanged();
    for (Traffic t : newlyIncluded)
      if (WorkbenchScope.contains(api, t.request())) {
        environments.observe(t.request(), t.response());

        if (responseRecon != null) responseRecon.offer(t.request(), t.response(), t.packet());
        if (activity != null) {
          activity.observe(t.request(), "트래픽 SCOPE");
          activity.response(t.request(), t.response());
        }
      }
    status.setText("SCOPE 등록 완료 · " + chosen.size() + "개 선택 · URL 경로/도메인 범위는 SCOPE 목록에서 확인하세요.");
  }

  private void scopeChanged() {
    workContext.configurationEdited();
    refreshScopeList();
    if (current != null && logTable.getSelectedRow() >= 0) showTraffic(logTable.getSelectedRow());
    if (jsAnalysis != null) jsAnalysis.rescanHistory();
    refreshTrafficInfo();
    status.setText("Workbench SCOPE " + localScope.rules().size() + "개 · 체크된 범위를 SCOPE 수집·도구에 적용");
  }

  private JPanel authPanel() {
    authCompare =
        new AuthComparePanel(
            api,
            () -> selectedTraffic().stream().map(t -> {
              HttpRequest request = t.request();
              comparisonOriginalIds.remember(request, t.packet().id());
              return HttpRequestResponse.httpRequestResponse(request, t.response());
            }).toList());
    authCompare.onWorkContext(workContext);
    authCompare.onObservedRequestRefs(() -> {
      DiskTrafficStore store = trafficStore;
      List<DiskTrafficStore.PacketRef> recent = new ArrayList<>();
      long end = store.size();
      // Metadata only here. The helper restores eligible originals on its bounded background worker.
      for (long index = end - 1; index >= Math.max(0, end - 100) && !unloaded.get(); index--) {
        DiskTrafficStore.PacketRef ref = store.ref(index);
        if (ref.method().equals("GET") && ref.hasResponse()) recent.add(ref);
      }
      return List.copyOf(recent);
    });
    return authCompare;
  }

  /** Select by immutable execution ID, never by URL or by the last visible history row. */
  private AuthComparePanel.Run selectAssessmentExecution(String id) {
    authCompare.history.clearSelection();
    for (int i = 0; i < authCompare.runs.size(); i++) {
      AuthComparePanel.Run run = authCompare.runs.get(i);
      if (!run.executionId.equals(id)) continue;
      int view = authCompare.history.convertRowIndexToView(i);
      if (view < 0) return null;
      authCompare.history.setRowSelectionInterval(view, view);
      authCompare.history.scrollRectToVisible(authCompare.history.getCellRect(view, 0, true));
      return run;
    }
    return null;
  }

  private void prepareRemainingCheck(AssessmentTracker.RemainingCheck check) {
    if (unloaded.get()) return;
    if (check.originalId().isEmpty()) {
      tabs.setSelectedIndex(0);
      status.setText("연결 원본 없음 · " + check.method() + " " + check.url()
          + "의 정확한 요청·응답을 직접 선택한 뒤 비교 준비를 여세요.");
      return;
    }
    DiskTrafficStore store = trafficStore;
    AssessmentPanel.RemainingNavigation navigation = activity.assessmentPanel.remainingNavigation(check);
    WorkContext.Snapshot selection = workContext.snapshot();
    long navigationRevision = ++assessmentNavigationRevision;
    status.setText("선택 실행의 원본을 로컬에서 읽는 중 · 요청 전송 없음");
    activity.submit(() -> {
      try {
        DiskTrafficStore.PacketRef ref = assessmentOriginal(store, check);
        if (!localScope.matchesTraffic(ref.url())) throw new IllegalArgumentException("연결 원본이 현재 SCOPE 밖입니다.");
        if ((long) ref.requestLength() + ref.responseLength() > 8L * 1024 * 1024)
          throw new IllegalArgumentException("연결 원본이 비교 준비의 8MiB 복원 한도를 넘습니다. 원본을 직접 확인하세요.");
        HttpRequestResponse packet = ref.packet();
        SwingUtilities.invokeLater(() -> {
          if (!assessmentNavigationCurrent(store, navigation, selection, navigationRevision)) return;
          try {
            if (!activity.tracker.assessments.remainingChecks(check.task()).contains(check))
              throw new IllegalArgumentException("업무·실행 조건이 바뀌었습니다. 남은 점검을 다시 선택하세요.");
            if (!localScope.matchesTraffic(ref.url())) throw new IllegalArgumentException("연결 원본이 현재 SCOPE 밖입니다.");
            comparisonOriginalIds.remember(packet.request(), ref.id());
            tabs.setSelectedComponent(authCompare);
            authCompare.stageReadiness(List.of(packet));
            authCompare.state.setText("업무 원본 준비 · 점검 계정 "
                + activity.tracker.assessments.account(check.account()).label()
                + "의 비교 계정 연결을 확인하고 ‘준비한 요청 다시 확인’을 누르세요. 요청 전송 없음.");
            for (int slot = 0; slot < authCompare.trackingAccounts.length; slot++)
              if (authCompare.trackingAccounts[slot].equals(check.account())) {
                JComboBox<?> choice = authCompare.trackingChoices[slot];
                choice.requestFocusInWindow();
                choice.scrollRectToVisible(new Rectangle(0, 0, choice.getWidth(), choice.getHeight()));
                break;
              }
            status.setText("정확한 원본 " + ref.id() + " 비교 준비 · 설정 확인 후 명시적으로 시작하세요.");
          } catch (IllegalArgumentException error) { status.setText(error.getMessage()); }
        });
      } catch (RuntimeException error) {
        SwingUtilities.invokeLater(() -> {
          if (assessmentNavigationCurrent(store, navigation, selection, navigationRevision))
            status.setText("연결 원본을 준비할 수 없습니다 · " + error.getMessage());
        });
      }
    });
  }

  private boolean assessmentNavigationCurrent(DiskTrafficStore store,
      AssessmentPanel.RemainingNavigation navigation, WorkContext.Snapshot selection, long revision) {
    return !unloaded.get() && trafficStore == store && assessmentNavigationMatches(activity.assessmentPanel,
        navigation, selection, workContext, revision, assessmentNavigationRevision);
  }

  static boolean assessmentNavigationMatches(AssessmentPanel panel,
      AssessmentPanel.RemainingNavigation navigation, WorkContext.Snapshot selection,
      WorkContext context, long expectedRevision, long currentRevision) {
    return expectedRevision == currentRevision && context.snapshot().equals(selection)
        && panel.remainingNavigationCurrent(navigation);
  }

  static DiskTrafficStore.PacketRef assessmentOriginal(DiskTrafficStore store, AssessmentTracker.RemainingCheck check) {
    try {
      int separator = check.originalId().lastIndexOf(':');
      if (separator < 0) throw new IllegalArgumentException();
      long sequence = Long.parseLong(check.originalId().substring(separator + 1));
      DiskTrafficStore.PacketRef ref = store.ref(sequence);
      if (!ref.id().equals(check.originalId()) || !ref.method().equals(check.method())
          || !Objects.equals(ActivityTracker.safeUrl(ref.url()), check.url())) throw new IllegalArgumentException();
      return ref;
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("연결 원본이 만료되었거나 현재 프로젝트에 없습니다. 같은 URL의 다른 요청으로 대체하지 않습니다.");
    }
  }

  private JPanel codecPanel() {
    codecOut.setEditable(false);
    String[] ops = {
      "URL 인코딩 (form)",
      "URL 디코딩 (form)",
      "Base64 인코딩",
      "Base64 디코딩",
      "Base64URL 인코딩",
      "Base64URL 디코딩",
      "Hex 인코딩",
      "Hex 디코딩",
      "SHA-256",
      "Gzip → Base64",
      "Base64 → Gzip 해제"
    };
    JComboBox<String> operation = new JComboBox<>(ops);
    JSplitPane editors =
        split(
            JSplitPane.HORIZONTAL_SPLIT,
            labeled("입력 (UTF-8 텍스트)", new JScrollPane(codecIn)),
            labeled("출력", new JScrollPane(codecOut)));
    editors.setName("wb.codec.messages");
    return page(
        row(
            operation,
            button(
                "변환",
                () -> {
                  try {
                    codecOut.setText(
                        Core.transform((String) operation.getSelectedItem(), codecIn.getText()));
                  } catch (Exception e) {
                    error(e);
                  }
                }),
            button("출력 → 입력", () -> codecIn.setText(codecOut.getText())),
            button("복사", () -> copy(codecOut.getText()))),
        editors);
  }

  private JPanel jwtPanel() {
    jwtOut.setEditable(false);
    JSplitPane editors =
        split(
            JSplitPane.HORIZONTAL_SPLIT,
            labeled("JWT 입력", new JScrollPane(jwtIn)),
            labeled("디코딩 결과", new JScrollPane(jwtOut)));
    editors.setName("wb.jwt.messages");
    return page(
        row(
            button("JWT 디코딩", () -> jwtOut.setText(Core.jwt(jwtIn.getText()))),
            button("선택 요청에서 가져오기", this::selectedJwt),
            new JLabel("헤더·페이로드 보기 · 서명 검증 기능 없음")),
        editors);
  }

  private JPanel jsPanel() {
    jsAnalysis = new JsAnalysisPanel(api, this::selectedJs);
    return jsAnalysis;
  }

  private void copy(String text) {
    Toolkit.getDefaultToolkit()
        .getSystemClipboard()
        .setContents(new java.awt.datatransfer.StringSelection(text), null);
  }

  private List<Traffic> selectedTraffic() {
    List<Traffic> out = new ArrayList<>();
    for (int row : logTable.getSelectedRows())
      out.add(traffic.get(logTable.convertRowIndexToModel(row)));
    if (out.isEmpty()) throw new IllegalArgumentException("트래픽에서 요청을 선택하세요.");
    return out;
  }

  private void selectedJwt() {
    if (current == null) throw new IllegalArgumentException("트래픽에서 요청을 선택하세요.");
    String token = Core.jwtFromMessage(current.request().toString());
    if (token.isEmpty())
      throw new IllegalArgumentException("요청에서 JWS 후보를 찾지 못했습니다. JWT 탭에 직접 붙여넣으세요.");
    jwtIn.setText(token);
    jwtOut.setText(Core.jwt(token));
    openFeature("data");
    dataTabs.setSelectedIndex(1);
  }

  private void selectedJs() {
    if (current == null) throw new IllegalArgumentException("응답이 있는 요청을 선택하세요.");
    HttpRequest request = current.request();
    HttpResponse response = current.response();
    if (response == null) throw new IllegalArgumentException("응답이 있는 요청을 선택하세요.");
    String text = EnvironmentPanel.decodedBody(PacketFormat.decodedResponse(response));
    jsAnalysis.rememberPacket(
        request.url(),
        EnvironmentPanel.preview(request.toString()),
        EnvironmentPanel.preview(response.toString()),
        EnvironmentPanel.preview(
            PacketFormat.response(
                response.toByteArray().getBytes(), response.statusCode(), "", text)));
    jsAnalysis.loadContext(text, request.url(),response.headerValue("Content-Type"),request.headerValue("Referer"));
    openFeature("js");
  }

  @Override
  public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent r) {
    if (!unloaded.get()) {
      captureStage("매핑 요청 시각", () -> {
        if (featureMapper != null && acceptsTraffic(r)) featureMapper.requestStarted(r.messageId());
      });
      captureStage("독립 인증 탐지",()->{if(authCompare!=null&&authCompare.detectionEnabled)authCompare.offerCredential(r,r.toolSource().toolType());});
      captureStage(
          "요청 활동 관찰",
          () -> {
            if (activity != null && acceptsTraffic(r))
              activity.observe(r, r.toolSource().toolType().toString());
          });
      captureStage(
          "계정 자동 비교 요청",
          () -> {
            if (authCompare != null) authCompare.begin(r, r.toolSource().toolType(), r.messageId());
          });
    }
    return RequestToBeSentAction.continueWith(r, r.annotations());
  }

  @Override
  public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived r) {
    if (!unloaded.get()) {
      long requestTime = System.currentTimeMillis();
      try {
        if (featureMapper != null) requestTime = featureMapper.requestFinished(r.messageId());
      } catch (RuntimeException error) { captureFailure("매핑 응답 시각", error); }
      final long started = requestTime;
      received.incrementAndGet();
      lastResponse = LocalTime.now().withNano(0).toString();
      // Passive observers only receive admitted traffic. An observer must never block Burp's action.
      AtomicBoolean admitted = new AtomicBoolean();
      AtomicReference<DiskTrafficStore.PacketRef> original = new AtomicReference<>();
      captureStage(
          "응답 수집",
          () -> {
            synchronized (captureLock) {
              if (unloaded.get()) { shutdownExcluded.incrementAndGet(); return; }
              if (captureMode.get() == CaptureMode.OFF) { offExcluded.incrementAndGet(); return; }
              HttpRequest q;
              try {
                q = r.initiatingRequest();
                if (!acceptsTraffic(q)) { scopeExcluded.incrementAndGet(); return; }
              } catch (RuntimeException ex) {
                captureDecisionErrors.incrementAndGet();
                throw ex;
              }
              admitted.set(true);
              try {
                Traffic stored = storeTraffic(r.toolSource().toolType().toString(), q, r, started);
                original.set(stored.packet());
                incoming.offer(stored);
                storedResponses.incrementAndGet();
              } catch (RuntimeException ex) {
                responseStoreFailures.incrementAndGet();
                dropped.incrementAndGet();
                throw ex;
              }
            }
          });
      captureStage(
          "응답 보안 관찰",
          () -> {
            if (admitted.get() && responseRecon != null) responseRecon.offer(r.initiatingRequest(), r, original.get());
          });
      captureStage("JS 보관 응답 분석",()->{
        if(admitted.get()&&jsAnalysis!=null)jsAnalysis.offer(r.initiatingRequest(),r,original.get());
      });
      captureStage(
          "응답 활동 관찰",
          () -> {
            if (admitted.get() && activity != null) activity.response(r.initiatingRequest(), r);
          });
      captureStage(
          "계정 자동 비교 응답",
          () -> {
            if (authCompare != null) authCompare.received(r.messageId(), r.initiatingRequest(), r, original.get());
          });
    }
    return ResponseReceivedAction.continueWith(r, r.annotations());
  }

  private void captureStage(String stage, Runnable task) {
    try {
      task.run();
    } catch (RuntimeException ex) {
      captureFailure(stage, ex);
    }
  }

  private void captureFailure(String stage, RuntimeException ex) {
    diagnostics.record(stage, ex);
    processingErrors.incrementAndGet();
    lastCaptureError = stage + " · " + ex.getClass().getSimpleName();
    long count = captureErrorCounts.computeIfAbsent(stage, k -> new AtomicLong()).incrementAndGet();
    // Log first occurrences and powers of two, without packet content or exception messages.
    if (count <= 3 || (count & (count - 1)) == 0)
      try {
        StringBuilder detail =
            new StringBuilder("Workbench 수집 · ")
                .append(stage)
                .append(" 실패 · ")
                .append(ex.getClass().getSimpleName())
                .append(" · 단계 오류 ")
                .append(count);
        Arrays.stream(ex.getStackTrace())
            .filter(frame -> frame.getClassName().startsWith("kr.workbench."))
            .limit(3)
            .forEach(frame -> detail.append("\n  at ").append(frame));
        api.logging().logToError(detail.toString());
      } catch (RuntimeException ignored) {
        /* Host logging must not stop collection either. */
      }
  }

  private String captureDiagnostics() {
    return "모드 " + captureMode.get() + " · 현재 보관 " + (trafficStore == null ? 0 : trafficStore.size())
        + "\n자동 응답 수신 " + received.get() + " · 보관 성공 " + storedResponses.get()
        + " · OFF 제외 " + offExcluded.get() + " · SCOPE 제외 " + scopeExcluded.get()
        + " · 저장 실패 " + responseStoreFailures.get() + " · 수집 판정 오류 " + captureDecisionErrors.get()
        + " · 종료 중 제외 " + shutdownExcluded.get()
        + "\n수동 가져오기 시도 " + importAttempts.get() + " · 보관 성공 " + importStored.get()
        + " · OFF 제외 " + importOffExcluded.get() + " · SCOPE 제외 " + importScopeExcluded.get()
        + " · 가져오기 실패 " + importFailures.get()
        + " · 마지막 응답 "
        + lastResponse
        + " · 대기 "
        + incoming.size()
        + " · 분석 대기 "
        + (captureObservations == null ? 0 : captureObservations.pending())
        + " · 저장 실패/누락 "
        + dropped.get()
        + " · 처리 오류 "
        + processingErrors.get()
        + " · 마지막 오류 "
        + lastCaptureError
        + "\n정책 제외 원본은 보관하지 않음 · 수신/판정/성공/오류는 확장 로드 이후 누적"
        + " · 현재 보관은 목록 초기화 후 달라질 수 있음 · 수동 가져오기는 자동 수신에 합산하지 않음"
        + " · 건수·패킷 크기 제한 없음 · 원본은 임시 디스크 보관 · 응답 없는 요청은 자동 수집 제외"
        + "\n" + (captureObservations == null ? "" : captureObservations.diagnostics())
        + "\n" + RuntimeMetrics.heapSummary();
  }

  private String captureTooltip() {
    return "<html><body style='width:520px'>" + Core.html(captureDiagnostics()).replace("\n", "<br>") + "</body></html>";
  }

  private void drain() {
    if (unloaded.get()) return;
    // Publish every committed metadata row once per tick, regardless of observer backlog.
    // JTable reads only visible/filter/sort metadata cells, never raw HTTP packet bodies.
    if (commonSettings != null) commonSettings.refreshProject();
    if(trafficBrowser!=null){trafficBrowser.publishCommittedRows();trafficBrowser.refresh();}
    else logModel.publish(Math.toIntExact(trafficStore.size()));
    rememberWorkspace();
    stats.setText(
        "수신 "
            + received.get()
            + " · 대기 "
            + incoming.size()
            + " · 분석 대기 "
            + (captureObservations == null ? 0 : captureObservations.pending())
            + " · 저장 실패/누락 "
            + dropped.get()
            + " · 오류 "
            + processingErrors.get());
    stats.setToolTipText(captureTooltip());
    updateLogButton();
    refreshTrafficInfo();
  }

  private boolean addTraffic(Traffic packet) {
    if (unloaded.get()) return false;
    if(trafficBrowser!=null)trafficBrowser.publishCommittedRows();
    else logModel.publish(Math.toIntExact(trafficStore.size()));
    captureStage("트래픽 상태 갱신", this::refreshTrafficInfo);
    return true;
  }

  @Override
  public List<Component> provideMenuItems(ContextMenuEvent e) {
    List<HttpRequestResponse> picked = new ArrayList<>(e.selectedRequestResponses());
    if (picked.isEmpty())
      e.messageEditorRequestResponse().ifPresent(m -> picked.add(m.requestResponse()));
    if (picked.isEmpty()) return List.of();
    for (var rr : picked)
      try {
        if (rr.request() != null) rememberScopeCandidate(rr.request().url());
      } catch (RuntimeException ignored) {
      }
    JMenu menu = new JMenu("Workbench Simple");
    JMenuItem bring = new JMenuItem("요청·응답 목록에 가져오기");
    bring.addActionListener(
        x ->
            SwingUtilities.invokeLater(
                () -> {
                  if (unloaded.get()) return;
                  int count = 0;
                  for (HttpRequestResponse rr : picked) {
                    if (count >= 20) break;
                    importAttempts.incrementAndGet();
                    try {
                      HttpRequest q;
                      HttpResponse response;
                      Traffic stored;
                      synchronized (captureLock) {
                        if (captureMode.get() == CaptureMode.OFF) { importOffExcluded.incrementAndGet(); continue; }
                        q = rr.request();
                        if (!acceptsTraffic(q)) { importScopeExcluded.incrementAndGet(); continue; }
                        response = rr.response();
                        stored = storeTraffic("가져오기", q, response);
                        importStored.incrementAndGet();
                      }
                      if (!addTraffic(stored)) continue;
                      count++;
                      captureStage(
                          "가져온 응답 보안 관찰",
                          () -> {
                            if (responseRecon != null) responseRecon.offer(q, response, stored.packet());
                          });
                      captureStage(
                          "가져온 요청 활동 관찰",
                          () -> {
                            if (activity != null) activity.observe(q, "가져오기");
                          });
                      captureStage(
                          "가져온 응답 활동 관찰",
                          () -> {
                            if (activity != null) activity.response(q, response);
                          });
                    } catch (RuntimeException ex) {
                      importFailures.incrementAndGet();
                      dropped.incrementAndGet();
                      captureFailure("목록 가져오기", ex);
                    }
                  }
                  if (count > 0) {
                    int row = logTable.convertRowIndexToView(traffic.size() - 1);
                    if (row >= 0)
                      captureStage("가져온 패킷 표시", () -> logTable.setRowSelectionInterval(row, row));
                    status.setText("가져오기 완료 · " + count + "건 · Workbench KR 탭에서 확인하세요.");
                  }
                }));
    menu.add(bring);
    JMenuItem repeater = new JMenuItem("Repeater에 요청 준비");
    repeater.addActionListener(
        x ->
            picked.stream()
                .limit(20)
                .forEach(rr -> api.repeater().sendToRepeater(rr.request(), "Workbench")));
    menu.add(repeater);
    return List.of(menu);
  }

  private void export() {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("메타데이터 결과 저장 (세션·본문·쿼리 제외)");
    chooser.setSelectedFile(new File("workbench-results.html"));
    if (chooser.showSaveDialog(root) != JFileChooser.APPROVE_OPTION) return;
    File f = chooser.getSelectedFile();
    if (f.exists()
        && JOptionPane.showConfirmDialog(root, "기존 파일을 덮어쓸까요?", "파일 저장", JOptionPane.YES_NO_OPTION)
            != JOptionPane.YES_OPTION) return;
    String output =
        f.getName().toLowerCase(Locale.ROOT).endsWith(".csv") ? csvReport() : htmlReport();
    try {
      Files.writeString(f.toPath(), output, StandardCharsets.UTF_8);
      status.setText("결과 저장 완료 · " + f.getName());
    } catch (IOException e) {
      error(e);
    }
  }

  String csvReport() {
    StringBuilder b = new StringBuilder("\ufeff유형,메서드,URL,프로필,원본상태,비교상태,검토결과\r\n");
    for (Traffic t : traffic)
      b.append("트래픽,")
          .append(Core.csv(t.packet.method()))
          .append(',')
          .append(Core.csv(Core.safeUrl(t.packet.url())))
          .append(",,")
          .append(t.packet.hasResponse() ? t.packet.responseStatus() : "")
          .append(",,\r\n");
    return b.toString();
  }

  String htmlReport() {
    StringBuilder b =
        new StringBuilder(
            "<!doctype html><html lang='ko'><meta charset='utf-8'><meta name='viewport'"
                + " content='width=device-width,initial-scale=1'><title>Burp Workbench"
                + " 결과</title><style>body{font:15px"
                + " system-ui;margin:32px;background:#f5f7fb;color:#17243b}table{border-collapse:collapse;width:100%;background:white}td,th{padding:10px;border:1px"
                + " solid"
                + " #d9e0eb;text-align:left;overflow-wrap:anywhere}th{background:#e4ebf5}h1{font-size:26px}</style><h1>Burp"
                + " Workbench KR · 결과</h1><p>자동 판정은 검토 후보입니다. 세션 헤더·본문·쿼리·fragment는 내보내지 않습니다. URL"
                + " 경로에는 식별자가 남을 수 있습니다.</p>");
    b.append("<h2>트래픽 목록</h2><table><tr><th>시간</th><th>도구</th><th>메서드 / URL</th><th>상태</th></tr>");
    for (Traffic t : traffic)
      b.append("<tr><td>")
          .append(Core.html(t.time))
          .append("</td><td>")
          .append(Core.html(t.source))
          .append("</td><td>")
          .append(Core.html(t.packet.method() + " " + Core.safeUrl(t.packet.url())))
          .append("</td><td>")
          .append(t.packet.responseStatus())
          .append("</td></tr>");
    return b.append("</table></html>").toString();
  }

  private void error(Exception e) {
    diagnostics.record("사용자 작업", e);
    status.setText("확인 필요 · " + e.getMessage());
    JOptionPane.showMessageDialog(
        root, e.getMessage(), "Workbench KR", JOptionPane.INFORMATION_MESSAGE);
  }
}

