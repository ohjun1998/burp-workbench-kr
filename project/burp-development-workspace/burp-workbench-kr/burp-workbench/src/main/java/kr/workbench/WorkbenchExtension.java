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
  private JDialog scopeWindow;
  private boolean settingsActionReady;

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
  private JTabbedPane dataTabs;
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
  private final PacketView requestPacket = new PacketView(), responsePacket = new PacketView();
  private HttpResponseEditor responseView;
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
    DiskTrafficStore.PacketRef packet =
        trafficStore.append(LocalTime.now().withNano(0).toString(), source, request, response);
    if (captureObservations != null) captureObservations.signal();
    return new Traffic(packet.time(), source, packet);
  }

  private void startCaptureObservations() {
    captureObservations =
        new CaptureObservations(
            trafficStore, this::prepareCaptureObservation, this::captureFailure);
  }

  private Runnable prepareCaptureObservation(HttpRequestResponse packet) {
    HttpRequest request = packet.request();
    HttpResponse response = packet.response();
    EnvironmentPanel.Observation environment = null;
    AuthComparePanel.Observation credentials = null;
    try {
      if (environments != null) environment = environments.prepareObservation(request, response);
    } catch (RuntimeException error) {
      captureFailure("서버 환경 관찰", error);
    }
    try {
      if (authCompare != null) credentials = authCompare.prepareObservation(request, response);
    } catch (RuntimeException error) {
      captureFailure("인증 후보 관찰", error);
    }
    final EnvironmentPanel.Observation preparedEnvironment = environment;
    final AuthComparePanel.Observation preparedCredentials = credentials;
    // Only compact metadata crosses back to Swing; the original bytes become reclaimable here.
    return () -> {
      captureStage(
          "서버 환경 관찰",
          () -> {
            if (environments != null) environments.applyObservation(preparedEnvironment);
          });
      captureStage(
          "인증 후보 관찰",
          () -> {
            if (authCompare != null) authCompare.applyObservation(preparedCredentials);
          });
    };
  }

  private void clearStoredTraffic() {
    synchronized (captureLock) {
      DiskTrafficStore replacement = new DiskTrafficStore();
      if (captureObservations != null) captureObservations.close();
      DiskTrafficStore previous = trafficStore;
      trafficStore = replacement;
      generation.incrementAndGet();
      traffic.clear();
      current = null;
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
                captureStage("임시 저장소 종료", trafficStore::close);
              }
              SwingUtilities.invokeLater(
                  () -> {
                    if (featureHelpWindow != null) featureHelpWindow.dispose();
                    if (logWindow != null) logWindow.dispose();
                    diagnostics.close();
                    if (commonSettingsWindow != null) commonSettingsWindow.dispose();
                    if (scopeWindow != null) scopeWindow.dispose();
                    if (timer != null) timer.stop();
                    if (root != null) root.putClientProperty("wb.skin.closed", true);
                    if (structuredCompare != null) structuredCompare.clearSources();
                    if (responseRecon != null) responseRecon.close();
                    if (jsAnalysis != null) jsAnalysis.close();
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
      SwingUtilities.invokeLater(jsAnalysis::startHistory);
      api.http().registerHttpHandler(this);
      api.userInterface().registerContextMenuItemsProvider(this);
      api.logging()
          .logToOutput("Burp Workbench KR loaded. 계정 비교 재전송은 선택 실행 또는 새 요청 자동 비교 ON에서 실행합니다.");
    } catch (Exception e) {
      if (captureObservations != null) captureObservations.close();
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
    requestView = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
    responseView = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
    addPage("트래픽", "traffic", trafficPanel(), false);
    addPage("권한 비교", "auth", authPanel(), false);
    addPage("데이터 도구", "data", dataPanel(), true);
    addPage("JS 추출", "js", jsPanel(), false);
    environments = new EnvironmentPanel(api);
    addPage("환경 탐색", "environment", environments, false);
    passiveScan = new PassiveScanWorkspace(api);
    addPage("URL 수집", "passive", passiveScan, false);
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
    jsAnalysis.coverage = (result, source) -> activity.analyzed(result, source);
    jsAnalysis.observation = activity::observation;
    activity.sourceNavigator =
        (source, line, kind) -> {
          tabs.setSelectedComponent(jsAnalysis);
          jsAnalysis.navigateSource(source, line, kind);
        };
    authCompare.reviewSink = (method, url, reason) -> activity.review(method, url, reason);
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
    addPage("활동 추적", "activity", activity, false);
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
    structuredCompare.evidenceSink = this::captureObserved;
    passiveScan.evidenceSink = this::captureObserved;
    responseRecon.evidenceSink = this::captureObserved;
    responseRecon.storedEvidenceSink =
        (packets, note, image, sha) -> {
          bountySupport.captureObservedWithImage(packets, note, image, sha);
          tabs.setSelectedComponent(bountySupport);
        };
    addPage("설정", "settings", settingsPanel(), false);
    settingsActionReady = true;
    featureHelp.setName("wb.feature.help");
    featureHelp.addActionListener(e -> openFeatureHelp());
    logButton.setName("wb.navigation.logs");
    logButton.setToolTipText("오류 기록과 수집 상태 확인");
    logButton.addActionListener(e -> openLogs());
    navigation = new FolderNavigation(api, tabs, this::openSettings, featureHelp, logButton);
    root.add(navigation);
    Diagnostics.bind(root, diagnostics);
    commonSettings.apply();
    tabs.addChangeListener(e -> updateFeatureHelp());
    updateFeatureHelp();
    api.userInterface().applyThemeToComponent(root);
    WorkbenchUi.style(root);
    navigation.restoreLayout();
    api.userInterface().registerSuiteTab(FeatureGuide.SUITE_TAB, root);
    WorkbenchSkin.install(root, navigation::restoreLayout);
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
                tabs.setSelectedIndex(0);
                filter.requestFocusInWindow();
                filter.selectAll();
              }
            });
  }

  private void addPage(String title, String id, Component body, boolean data) {
    if (body instanceof JComponent component) component.putClientProperty("wb.feature.id", id);
    tabs.addTab(FeatureGuide.title(id), null, body, FeatureGuide.feature(id).summary());
  }

  private String currentFeature() {
    Component selected = tabs.getSelectedComponent();
    return selected instanceof JComponent component
        ? Objects.toString(component.getClientProperty("wb.feature.id"), "traffic")
        : "traffic";
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
            for (int i = 0; i < tabs.getTabCount(); i++) {
              Component target = tabs.getComponentAt(i);
              if (target instanceof JComponent component
                  && chosen.id().equals(component.getClientProperty("wb.feature.id"))) {
                tabs.setSelectedIndex(i);
                return;
              }
            }
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
            "Workbench KR · 기능 안내",
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
    logContent = diagnostics.panel(() -> "모드 " + captureMode.get() + " · 수신 " + received.get() + " · 보관 " + trafficStore.size() + " · 대기 " + incoming.size()
        + "\n분석 대기 " + (captureObservations == null ? 0 : captureObservations.pending()) + " · 누락 " + dropped.get() + " · 처리 오류 " + processingErrors.get() + " · 마지막 응답 " + lastResponse);
    WorkbenchUi.style(logContent);
    if (logWindow != null && logWindow.isDisplayable()) { logWindow.toFront(); return; }
    logWindow = dialogOpener.open(root, "Workbench KR · 로그", logContent, false, () -> logWindow = null);
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
            "Workbench KR · 공통 설정",
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
        "클릭: OFF ↔ SCOPE · OFF 수집 금지 / SCOPE HTTP history → Add to scope로 등록한 Burp Scope만 수집");
    logTable =
        new JTable(logModel) {
          public String getToolTipText(java.awt.event.MouseEvent e) {
            int row = rowAtPoint(e.getPoint());
            if (row < 0) return null;
            int index = convertRowIndexToModel(row);
            return index < traffic.size()
                ? WorkbenchScope.reason(api, traffic.get(index).request())
                : null;
          }
        };
    logTable.setName("wb.traffic.history");
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
              authCompare.compareSelection();
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
              tabs.setSelectedIndex(2);
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
            for (Component c : trafficMenu.getComponents())
              if (c instanceof JMenuItem item) item.setEnabled(row >= 0);
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
    int[] widths = {55, 75, 340, 65};
    for (int i = 0; i < widths.length; i++)
      logTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
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
    filter
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              public void insertUpdate(DocumentEvent e) {
                change();
              }

              public void removeUpdate(DocumentEvent e) {
                change();
              }

              public void changedUpdate(DocumentEvent e) {
                change();
              }

              void change() {
                sorter.setRowFilter(
                    filter.getText().isEmpty()
                        ? null
                        : RowFilter.regexFilter("(?i)" + Pattern.quote(filter.getText()), 1, 2, 3));
                refreshTrafficInfo();
              }
            });
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
    top.add(filter, BorderLayout.CENTER);
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
    JPanel main = page(top, body);
    main.setBorder(BorderFactory.createEmptyBorder(8, 16, 12, 16));
    main.setName("wb.traffic");
    refreshTrafficInfo();
    return main;
  }

  private void showTraffic(int view) {
    if (view >= 0) {
      int index = logTable.convertRowIndexToModel(view);
      if (index < traffic.size()) {
        current = traffic.get(index);
        HttpRequest request = current.request();
        HttpResponse response = current.response();
        requestView.setRequest(request);
        responseView.setResponse(response);
        requestPacket.setPacket(request.toString());
        responsePacket.setPacket(
            response == null ? null : response.toString(), PacketFormat.decodedResponse(response));
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
        selectionInfo.setToolTipText(selectionInfo.getText());
      }
    } else {
      current = null;
      requestView.setRequest(null);
      responseView.setResponse(null);
      requestPacket.setPacket(null);
      responsePacket.setPacket(null);
      selectionInfo.setText("목록에서 요청을 선택하세요 · 요청과 응답을 함께 표시합니다.");
    }
  }

  void openPassive(String url) {
    tabs.setSelectedComponent(passiveScan);
    try {
      passiveScan.open(url, true);
    } catch (IllegalArgumentException e) {
      status.setText("URL 수집 · " + e.getMessage());
    }
  }

  private void refreshTrafficInfo() {
    if (logTable == null) return;
    CaptureMode mode = captureMode.get();
    if (captureButton != null) {
      captureButton.setText(mode.name());
      captureButton.getAccessibleContext().setAccessibleDescription(switch (mode) {
        case OFF -> "빨강 · 트래픽 수집 중지";
        case SCOPE -> "주황 · 체크된 Burp Target Scope만 수집";
      });
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
              case SCOPE -> "Burp SCOPE 수집";
            });
    captureInfo.setToolTipText(captureDiagnostics());
  }

  private boolean acceptsTraffic(HttpRequest request) {
    return switch (captureMode.get()) {
      case OFF -> false;
      case SCOPE -> {
        String url = request.url();
        if (nativeScopeFallback && localScope.syncObserved(java.util.List.of(url)))
          SwingUtilities.invokeLater(this::scopeChanged);
        yield localScope.matchesNative(url);
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
        new JTextArea("Burp Scope만 수집 · 수동 URL은 도구 대상용\n선택 삭제는 체크된 URL만 삭제합니다.");
    note.setEditable(false);
    note.setLineWrap(true);
    note.setWrapStyleWord(true);
    panel.add(note, BorderLayout.NORTH);
    JTextField urlInput = new JTextField(24);
    urlInput.setName("wb.traffic.scope.url");
    urlInput.setToolTipText("수동 URL은 도구 대상용 · 트래픽 수집은 Burp HTTP history → Add to scope 사용 · 쿼리 제외");
    Runnable add =
        () -> {
          localScope.add(urlInput.getText().trim(), false);
          urlInput.setText("");
          scopeChanged();
        };
    JButton addButton = button("URL 추가", add);
    addButton.setName("wb.traffic.scope.add");
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
    panel.add(row(new JLabel("URL"), urlInput, addButton, remove, clear), BorderLayout.SOUTH);
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
    if (scopeListButton != null)
      scopeListButton.setText("SCOPE 목록 (" + scopeModel.getRowCount() + ")");
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
        authCompare.observe(t.request(), t.response());
        if (responseRecon != null) responseRecon.offer(t.request(), t.response());
        if (activity != null) {
          activity.observe(t.request(), "트래픽 SCOPE");
          activity.response(t.request(), t.response());
        }
      }
    status.setText("SCOPE 등록 완료 · " + chosen.size() + "개 선택 · URL 경로/도메인 범위는 SCOPE 목록에서 확인하세요.");
  }

  private void scopeChanged() {
    refreshScopeList();
    if (current != null && logTable.getSelectedRow() >= 0) showTraffic(logTable.getSelectedRow());
    if (jsAnalysis != null) jsAnalysis.rescanHistory();
    status.setText("Workbench SCOPE " + localScope.rules().size() + "개 · 체크된 범위만 도구에 적용");
  }

  private JPanel authPanel() {
    authCompare =
        new AuthComparePanel(
            api,
            () ->
                selectedTraffic().stream()
                    .map(t -> HttpRequestResponse.httpRequestResponse(t.request(), t.response()))
                    .toList());
    return authCompare;
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
    tabs.setSelectedIndex(2);
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
    jsAnalysis.load(text, request.url());
    tabs.setSelectedIndex(3);
  }

  @Override
  public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent r) {
    if (!unloaded.get()) {
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
      received.incrementAndGet();
      lastResponse = LocalTime.now().withNano(0).toString();
      // Passive observers only receive admitted traffic. An observer must never block Burp's action.
      AtomicBoolean admitted = new AtomicBoolean();
      captureStage(
          "응답 수집",
          () -> {
            synchronized (captureLock) {
              if (unloaded.get()) return;
              if (captureMode.get() == CaptureMode.OFF) return;
              HttpRequest q = r.initiatingRequest();
              if (!acceptsTraffic(q)) return;
              admitted.set(true);
              try {
                incoming.offer(storeTraffic(r.toolSource().toolType().toString(), q, r));
              } catch (RuntimeException ex) {
                dropped.incrementAndGet();
                throw ex;
              }
            }
          });
      captureStage(
          "응답 보안 관찰",
          () -> {
            if (admitted.get() && responseRecon != null) responseRecon.offer(r.initiatingRequest(), r);
          });
      captureStage(
          "응답 활동 관찰",
          () -> {
            if (admitted.get() && activity != null) activity.response(r.initiatingRequest(), r);
          });
      captureStage(
          "계정 자동 비교 응답",
          () -> {
            if (authCompare != null) authCompare.received(r.messageId(), r.initiatingRequest(), r);
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
    return "건수·패킷 크기 제한 없음 · 원본은 임시 디스크 보관 · 응답 없는 요청은 자동 수집 제외"
        + " · 수신 "
        + received.get()
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
        + " · SCOPE는 체크된 URL만 수집 · 수신/오류는 확장 로드 이후 누적";
  }

  private void drain() {
    if (unloaded.get()) return;
    // Publish every committed metadata row once per tick, regardless of observer backlog.
    // JTable reads only visible/filter/sort metadata cells, never raw HTTP packet bodies.
    logModel.publish(Math.toIntExact(trafficStore.size()));
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
    stats.setToolTipText(captureDiagnostics());
    updateLogButton();
    refreshTrafficInfo();
  }

  private boolean addTraffic(Traffic packet) {
    if (unloaded.get()) return false;
    logModel.publish(Math.toIntExact(trafficStore.size()));
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
    JMenu menu = new JMenu("Workbench KR");
    JMenuItem bring = new JMenuItem("요청·응답 목록에 가져오기");
    bring.addActionListener(
        x ->
            SwingUtilities.invokeLater(
                () -> {
                  if (unloaded.get()) return;
                  int count = 0;
                  for (HttpRequestResponse rr : picked) {
                    if (count >= 20) break;
                    try {
                      HttpRequest q = rr.request();
                      HttpResponse response = rr.response();
                      if (!acceptsTraffic(q)) continue;
                      if (!addTraffic(storeTraffic("가져오기", q, response))) continue;
                      count++;
                      captureStage(
                          "가져온 응답 보안 관찰",
                          () -> {
                            if (responseRecon != null) responseRecon.offer(q, response);
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
