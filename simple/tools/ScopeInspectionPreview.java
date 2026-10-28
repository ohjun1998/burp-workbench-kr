package kr.workbench;

import burp.api.montoya.MontoyaApi;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import static kr.workbench.SimplePreview.*;

/** Real inspection presenters fed by explicitly synthetic packet and browser observations. */
public final class ScopeInspectionPreview extends WorkbenchTest {
  static final String ORIGIN = "https://scope.example.test";
  static final String API_KEY = "SCOPE_PRIVATE_API_VALUE_0123456789abcdef";
  static final String API_TEMPLATE = "${host}/app/api/documents/${id}";
  static final List<ScopeInspection.Page> packets = new ArrayList<>();
  static final List<ScopeInspection.Finding> findings = new ArrayList<>();
  static final List<ScopeInspection.Candidate> candidates = new ArrayList<>();
  static DiskTrafficStore store;

  static ScopeInspection.Page page(String path, String account, int code, String type, String body) {
    var headers = List.of(header("Content-Type", type), header("Server", "nginx/1.25 example"),
        header("X-Powered-By", "Apache Tomcat example"));
    var request = ScopeInspectionFixture.wireRequest(ORIGIN + path, "GET", account.equals("Alice")
        ? List.of(header("Cookie", "SID=SCOPE_DEMO_SESSION")) : List.of(), new byte[0]);
    var response = ScopeInspectionFixture.wireResponse(code, headers, body.getBytes(StandardCharsets.UTF_8));
    var original = store.append("가이드 합성 자료", account, request, response);
    String headerText = String.join("\r\n", headers.stream().map(h -> h.toString()).toList());
    var analysis = ScopeInspectionAnalysis.analyze(ORIGIN + path, code, headerText, body, account, original.id());
    var content = EnvironmentContent.inspect(ORIGIN + path, code, headerText, body, "가이드용 합성 응답");
    boolean targetCovered = !Set.of("LOGIN", "CHALLENGE", "RATE", "DENIED", "REDIRECT", "LIMIT", "UNKNOWN", "ERROR").contains(content.kind());
    String note = analysis.note() + (targetCovered ? "" : " · 대상 내용 미확인 · " + content.title() + " · 해당 응답의 정적 단서만 분석");
    findings.addAll(analysis.findings()); candidates.addAll(analysis.candidates());
    var result = new ScopeInspection.Page(ORIGIN + path, "GET", account, code,
        content.kind(), analysis.complete() && targetCovered, note, EnvironmentProject.hash(body), original);
    packets.add(result); return result;
  }

  static void tab(ScopeInspectionPanel panel, String key) {
    panel.pages.setSelectedIndex(ScopeInspectionUiTest.indexOf(panel.pages, key));
    JTable table = panel.tables.get(key);
    if (table.getRowCount() > 0) table.setRowSelectionInterval(0, 0);
  }
  static ScopeInspection.Snapshot snapshot(String id, ScopeInspection.State state, int pending) {
    return new ScopeInspection.Snapshot(id, state,
        "가이드용 합성 응답·Katana·브라우저 관측 예시 · 실제 대상 탐색을 실행하지 않았습니다.",
        16, packets.size(), pending, 2, 0, partial(packets), findings, packets, candidates,
        1793100000000L, state == ScopeInspection.State.COMPLETED ? 1793100005000L : 0, coverage());
  }
  /** Disclosed synthetic traces; no network coordinator is started to create these examples. */
  static List<ScopeInspectionCoverage.Row> coverage() {
    var result = new ArrayList<ScopeInspectionCoverage.Row>();
    for (var packet : packets) {
      String state = packet.status()==401 ? "DENIED" : packet.complete() ? "ANALYZED" : "PARTIAL";
      result.add(new ScopeInspectionCoverage.Row(EnvironmentProject.hash("guide|"+packet.account()+"|"+packet.url()),
          packet.url(),packet.method(),packet.account(),packet.profileRevision(),"가이드 합성 응답","합성 원본 연결",state,
          packet.status()==401 ? "합성 접근 거부 · 대상 권한 미확인" : "합성 응답의 분석 상태 · 실제 대상 점검 아님",packet.status(),
          packet.original().id(),false,List.of("DISCOVERED","SCOPE_CHECKED","QUEUED","REQUESTED","RESPONDED","ANALYZED")));
    }
    result.add(new ScopeInspectionCoverage.Row(EnvironmentProject.hash("guide-unvisited"),ORIGIN+"/app/api/pending","GET","Alice","r1",
        "JS API","조회 후보","UNVISITED","가이드 합성 미방문 예시 · 중지 전에 남은 조회 항목",0,"",true,List.of("DISCOVERED","SCOPE_CHECKED","QUEUED")));
    result.add(new ScopeInspectionCoverage.Row(EnvironmentProject.hash("guide-failure"),ORIGIN+"/app/api/slow","GET","Alice","r1",
        "관측 링크","조회 실패 예시","FAILED","가이드 합성 타임아웃 · 새 조회에는 남은 예산 필요",0,"",true,List.of("DISCOVERED","SCOPE_CHECKED","QUEUED","REQUESTED")));
    result.add(new ScopeInspectionCoverage.Row(EnvironmentProject.hash("guide-template"),ORIGIN+"/app/api/documents/{id}","GET","Alice","r1",
        "API 문서","필수 id 값 필요","EXCLUDED","동적 경로 변수 미해결 · 추측 전송하지 않음",0,"",false,List.of("DISCOVERED","SCOPE_CHECKED")));
    result.add(new ScopeInspectionCoverage.Row(EnvironmentProject.hash("guide-js-template"),API_TEMPLATE,"GET","비로그인","r1",
        "JS API","동적 미해결","EXCLUDED","합성 JS 응답에서 템플릿 관측 · 형태 연결도 변수 해소나 실행 성공이 아님",0,"",false,List.of("DISCOVERED","SCOPE_CHECKED")));
    return List.copyOf(result);
  }
  static int partial(List<ScopeInspection.Page> pages) { return (int)pages.stream().filter(page -> !page.complete()).count(); }
  static void set(Object owner, String field, Object value) {
    try { var member = owner.getClass().getDeclaredField(field); member.setAccessible(true); member.set(owner, value); }
    catch (ReflectiveOperationException error) { throw new AssertionError(error); }
  }
  static void syntheticContext(ScopeInspectionPanel panel, WorkbenchScope scope, MontoyaApi api) {
    try {
      set(panel,"runScope",scope.inspectionFingerprint());set(panel,"runProject",WorkspaceResume.projectIdentity(api));
      var inputs=ScopeInspectionPanel.class.getDeclaredMethod("inputConditions");inputs.setAccessible(true);
      set(panel,"runInputs",inputs.invoke(panel));
      // The example history below is provided explicitly; do not start a real history save for these guide traces.
      var persisted=ScopeInspectionPanel.class.getDeclaredField("persisted");persisted.setAccessible(true);
      @SuppressWarnings("unchecked") var ids=(Set<String>)persisted.get(panel);ids.add("guide-scope-run");
    } catch(ReflectiveOperationException error) {throw new AssertionError(error);}
  }
  static Map<String,Object> browserEvent(String kind, String path, String label, String method, int pages) {
    var value = new LinkedHashMap<String,Object>();
    value.put("type", "inspection"); value.put("runId", "guide-scope-run"); value.put("kind", kind);
    value.put("url", ORIGIN + path); value.put("page", ORIGIN + "/app/"); value.put("label", label);
    value.put("method", method); value.put("routeId", (pages % 2 == 0 ? "b" : "a").repeat(64));
    value.put("pages", pages); value.put("actions", 3); value.put("requests", 7); value.put("excluded", 1);
    value.put("message", "가이드용 합성 브라우저 관측 · 실제 DOM 실행 아님"); return value;
  }

  static void appearance(String fontPath) throws Exception {
    if (theme.equals("dark")) {
      for (String key : List.of("Panel.background", "TabbedPane.background", "Button.background", "CheckBox.background", "ComboBox.background", "control")) UIManager.put(key, new Color(30,33,39));
      UIManager.put("TabbedPane.selected", new Color(69,58,48));
      for (String key : List.of("TabbedPane.selectHighlight", "TabbedPane.highlight", "TabbedPane.shadow", "TabbedPane.darkShadow", "controlHighlight", "controlLtHighlight", "controlShadow", "controlDkShadow")) UIManager.put(key, new Color(68,75,86));
      UIManager.put("TabbedPane.contentAreaColor", new Color(30,33,39));
      for (String key : List.of("Label.foreground", "Button.foreground", "CheckBox.foreground", "ComboBox.foreground", "TabbedPane.foreground", "textText")) UIManager.put(key, new Color(228,233,241));
    }
    Font font = Font.createFont(Font.TRUETYPE_FONT, Path.of(fontPath).toFile());
    GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
    var keys = UIManager.getDefaults().keys();
    while (keys.hasMoreElements()) { Object key = keys.nextElement(); if (UIManager.get(key) instanceof FontUIResource) UIManager.put(key, new FontUIResource(font.deriveFont(14f))); }
  }

  public static void main(String[] args) throws Exception {
    out = Path.of(args[0]); theme = args[2]; width = Integer.parseInt(args[3]); height = Integer.parseInt(args[4]);
    Files.createDirectories(out); appearance(args[1]); ScopeInspectionFixture.installFactory();
    MontoyaApi api = SimpleWorkflowTest.previewApi();
    var scope = WorkbenchScope.install(api); scope.add(ORIGIN + "/app/", false);
    var profiles = List.of(new ScopeInspection.Profile("anonymous", "비로그인", Map.of(), null),
        new ScopeInspection.Profile("alice", "Alice", Map.of("cookie", "SID=SCOPE_DEMO_SESSION"),
            new CookieSessionProbe.Config(ORIGIN + "/app/api/me", "$.id", "alice")));
    var options = new ScopeInspection.Options(ORIGIN + "/app/", 100, 2, 300, 10000, 2*1024*1024, 3, true, true, profiles,true,60,100);
    var count = new java.util.concurrent.atomic.AtomicInteger();
    ScopeInspectionPanel.Actions actions = new ScopeInspectionPanel.Actions() {
      public void original(DiskTrafficStore.PacketRef ref) { count.incrementAndGet(); }
      public void repeater(DiskTrafficStore.PacketRef ref) { count.incrementAndGet(); }
      public void compare(DiskTrafficStore.PacketRef ref) { count.incrementAndGet(); }
      public void evidence(List<DiskTrafficStore.PacketRef> refs, String note) { count.incrementAndGet(); }
      public void tool(String id, DiskTrafficStore.PacketRef ref) { count.incrementAndGet(); }
    };
    var holder = new java.util.concurrent.atomic.AtomicReference<ScopeInspectionPanel>();
    edt(() -> holder.set(new ScopeInspectionPanel(api, () -> profiles, actions)));
    ScopeInspectionPanel panel = holder.get();
    Path historyDirectory = Files.createTempDirectory("scope-guide-history-");
    store = new DiskTrafficStore();
    try {
      edt(() -> {
        panel.url.setText(ORIGIN + "/app/");
        panel.compareAccounts.setSelected(true); panel.browserMode.setSelected(true);panel.katanaMode.setSelected(true);
        scenario("Opening the production inspection panel performs no target requests", panel.runner == null);
        capture(panel, "12-inspection-setup.png");
      });
      page("/app/", "비로그인", 200, "text/html", "<html><title>통합 점검 예시</title><nav><a href='/app/documents'>문서 메뉴</a><a href='/app/openapi.json'>API 문서</a></nav><script src='/app/assets/site.js'></script></html>");
      page("/app/openapi.json", "비로그인", 200, "application/json", "{\"openapi\":\"3.0.0\",\"paths\":{\"/app/api/me\":{\"get\":{}},\"/app/api/documents/{id}\":{\"get\":{\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"required\":true}]}}}}");
      page("/app/assets/site.js", "비로그인", 200, "application/javascript", "fetch('/app/api/me');\nfetch('/app/api/undocumented');\nfetch(`"+API_TEMPLATE+"`);\nconst api_key='"+API_KEY+"';");
      String configBody = "{\"api_key\":\""+API_KEY+"\",\"database_url\":\"postgresql://app:DB_DEMO_PASSWORD@db.internal/app\",\"diagnostic\":\"org.postgresql.util.PSQLException SQLSTATE[08006]\",\"internal_ip\":\"10.20.30.40\",\"revision\":2}";
      var config = page("/app/config.json", "비로그인", 200, "application/json", configBody);
      page("/app/api/me", "비로그인", 401, "application/json", "{\"error\":\"login required\"}");
      scenario("HTTP 401 fixture uses the production denied classifier and keeps target content unconfirmed",
          packets.get(4).classification().equals("DENIED") && !packets.get(4).complete() && packets.get(4).note().contains("대상 내용 미확인"));
      page("/app/api/me", "Alice", 200, "application/json", "{\"id\":\"alice\",\"role\":\"user\"}");
      page("/app/api/undocumented", "Alice", 200, "application/json", "{\"document\":101,\"owner\":\"alice\"}");
      page("/app/api/documents/101", "Alice", 200, "application/json", "{\"id\":101,\"owner\":\"alice\",\"title\":\"가이드 합성 문서\"}");
      findings.add(new ScopeInspection.Finding("ACCESS", "계정별 응답 차이", ORIGIN+"/app/api/me", "GET", "계정 비교",
          "응답 관측", "중간", "수동 검토", "비로그인 HTTP 401 / Alice HTTP 200 · 선언한 접근 정책과 직접 대조하세요.",
          packets.get(5).original().id(), 0, -1, EnvironmentProject.hash("account-response-difference")));
      candidates.add(new ScopeInspection.Candidate(ORIGIN+"/app/api/documents/{id}", "GET", "OpenAPI 경로 매개변수", "필수 id 값 필요", false));
      candidates.add(new ScopeInspection.Candidate(ORIGIN+"/outside/admin", "GET", "리디렉션", "선택한 SCOPE 경로 밖", false));
      findings.add(new ScopeInspection.Finding("COVERAGE", "본문 분석 범위 제한", ORIGIN+"/app/large.log", "GET", "비로그인",
          "미확인", "정보", "미검사", "가이드 예시: 선언된 대용량 파일을 자동 수집에서 제외했습니다. 전체 점검 완료를 의미하지 않습니다.", "", 0, -1, EnvironmentProject.hash("partial-guide")));
      findings.addAll(ScopeInspectionAnalysis.compareInventory(findings, candidates, packets));
      findings.add(new ScopeInspection.Finding("KATANA","JS API 경로 관측",ORIGIN+"/app/assets/site.js","GET","비로그인","관측","정보","원본 연결",
          "가이드용 합성 Katana 기록 · script src → JS fetch 경로 · 선택 HTTP 계정/요청 한도 적용",packets.get(2).original().id(),0,-1,EnvironmentProject.hash("katana-js-guide")));
      findings.add(new ScopeInspection.Finding("KATANA","폼 선언 · POST · [민감 필드]",ORIGIN+"/app/account","POST","비로그인","관측","정보","미실행",
          "가이드용 합성 폼 메타데이터 · 입력 이름만 표시 · 입력값 가림 · 자동 제출하지 않음","",0,-1,EnvironmentProject.hash("katana-form-guide")));
      findings.add(new ScopeInspection.Finding("KATANA","Scope 밖 요청 차단",ORIGIN+"/outside/sensitive","GET","비로그인","관측","정보","제외",
          "가이드용 합성 제외 · 실제 대상 전송 없이 로컬에서 거부","",0,-1,EnvironmentProject.hash("katana-blocked-guide")));
      var completed = snapshot("guide-scope-run", ScopeInspection.State.COMPLETED, 0);
      var previousPages = new ArrayList<>(packets);
      String previousBody = configBody.replace("\"revision\":2", "\"revision\":1");
      var previousOriginal = store.append("가이드 이전 합성 자료", "비로그인",
          ScopeInspectionFixture.wireRequest(config.url(), "GET", List.of(), new byte[0]),
          ScopeInspectionFixture.wireResponse(200, List.of(header("Content-Type", "application/json")),
              previousBody.getBytes(StandardCharsets.UTF_8)));
      previousPages.set(3, new ScopeInspection.Page(config.url(), config.method(), config.account(), config.status(), config.classification(), config.complete(),
          config.note(), EnvironmentProject.hash(previousBody), previousOriginal, config.profileRevision()));
      var previous = new ScopeInspection.Snapshot("guide-previous-run", ScopeInspection.State.COMPLETED, "이전 합성 실행", 14, previousPages.size(), 0, 0, 0, partial(previousPages),
          findings, previousPages, candidates, 1793010000000L, 1793010005000L);
      var history = new ScopeInspectionHistory(historyDirectory, "55555555-5555-4555-8555-555555555555", () -> "guide-scope-v1");
      history.save(options, previous);
      var changes = history.changes(options, completed);
      edt(() -> {
        panel.options=options;
        syntheticContext(panel,scope,api);
        panel.accept(snapshot("guide-scope-run", ScopeInspection.State.RUNNING, 9));
        capture(panel, "13-inspection-progress.png");
        panel.accept(completed); set(panel, "changes", changes); panel.accept(completed);
        for (String[] target : new String[][] {
            {"environment","14-inspection-environment.png"}, {"paths","15-inspection-paths.png"},
            {"api","16-inspection-api.png"}, {"secrets","17-inspection-secrets.png"},
            {"access","18-inspection-access.png"}, {"changes","19-inspection-changes.png"},
            {"coverage","20-inspection-coverage.png"}}) {
          tab(panel, target[0]);
          scenario("Production inspection " + target[0] + " table contains inspectable synthetic evidence", panel.tables.get(target[0]).getRowCount() > 0);
          if (target[0].equals("secrets")) scenario("Secret result view preserves masking", !ScopeInspectionUiTest.visibleText(panel).contains(API_KEY));
          capture(panel, target[1]);
        }
        tab(panel,"routes");
        scenario("Coverage guide distinguishes synthetic responses, unvisited candidates and retry exclusions",panel.tables.get("routes").getRowCount()>=packets.size()+3);
        capture(panel,"26-inspection-routes.png");
        panel.routeFilter.setSelectedItem("형태 일치 후보");tab(panel,"routes");
        scenario("Path-shape guide associates an unresolved observed template with separate synthetic response evidence",ScopeInspectionCoverage.correlations(panel.current).stream().anyMatch(c->c.template().equals(API_TEMPLATE)&&c.observedUrl().endsWith("/app/api/documents/101")));
        JTable linked=panel.tables.get("routes");
        for(int i=0;i<panel.current.coverage().size();i++)if(panel.current.coverage().get(i).url().equals(API_TEMPLATE)){
          int view=linked.convertRowIndexToView(i);if(view>=0){linked.setRowSelectionInterval(view,view);break;}
        }
        int association=panel.detail.getText().indexOf("PATH_SHAPE_ONLY");
        if(association>=0)panel.detail.setCaretPosition(association);
        capture(panel,"29-inspection-correlations.png");panel.routeFilter.setSelectedIndex(0);
        panel.resultFilter.setSelectedItem(ScopeInspectionView.Filter.IMPORTANT);tab(panel,"summary");
        scenario("Important result filter uses masked production findings without new HTTP",panel.tables.get("summary").getRowCount()>0&&!ScopeInspectionUiTest.visibleText(panel).contains(API_KEY));
        capture(panel,"27-inspection-filter.png");
        panel.resultFilter.setSelectedItem(ScopeInspectionView.Filter.ALL);panel.routeFilter.setSelectedItem("미확인·재점검");tab(panel,"routes");
        JTable routes=panel.tables.get("routes");
        for(int i=0;i<panel.current.coverage().size();i++)if(panel.current.coverage().get(i).retryable()){
          int view=routes.convertRowIndexToView(i);if(view>=0){routes.setRowSelectionInterval(view,view);break;}
        }
        scenario("Synthetic guide selection retains retryable route evidence but never starts a coordinator",!panel.selectedCoverage().isEmpty()&&panel.selectedCoverage().get(0).retryable()&&!panel.recheck.isEnabled()&&panel.runner==null);
        capture(panel,"28-inspection-recheck.png");
        panel.routeFilter.setSelectedIndex(0);
        panel.browser.begin("guide-scope-run", ORIGIN+"/app/", List.of(ORIGIN+"/app/"), List.of(), 15, 30, System.currentTimeMillis()+60000);
        panel.browserCommand(ORIGIN);
        panel.browserEvent(browserEvent("started", "/app/", "통합 탐색 시작", "", 1));
        panel.browserEvent(browserEvent("route", "/app/#/documents", "문서 메뉴", "GET", 2));
        panel.browserEvent(browserEvent("api", "/app/api/me", "내 계정 조회", "GET", 2));
        panel.browserEvent(browserEvent("excluded", "/outside/assets.js", "Scope 밖 리소스", "GET", 2));
        panel.browserEvent(browserEvent("done", "/app/", "합성 관측 완료", "", 2));
      });
      edt(() -> {});
      edt(() -> {
        tab(panel, "browser");
        scenario("Production browser lease retains separate browser-session attribution", panel.browser.snapshot().state().equals("COMPLETED"));
        scenario("Production browser table displays received route and API observations", panel.tables.get("browser").getRowCount() >= 2);
        capture(panel, "21-inspection-browser.png");
        var original = panel.originalContent(config.original());
        scenario("Original response bytes remain available behind masked findings", config.original().response().toString().contains(API_KEY));
        capture(original, "22-inspection-original.png");
        panel.katanaExecutable.setText("katana");panel.katanaCheckResult.setText("가이드용 합성 설치 확인 표시 · Katana 1.8.0 경로를 지정하세요.");
        JPanel conditions=panel.settingsContent();
        scenario("Katana execution conditions retain a named executable and bounded page/time controls",SimpleWorkflowTest.named(conditions,"wb.scope.inspection.katana.executable")!=null&&SimpleWorkflowTest.named(conditions,"wb.scope.inspection.katana.pages")!=null);
        capture(conditions,"23-katana-settings.png",width,height,()->{
          Rectangle executable=SwingUtilities.convertRectangle(panel.katanaExecutable.getParent(),panel.katanaExecutable.getBounds(),conditions);
          Rectangle browserTime=SwingUtilities.convertRectangle(panel.browserSeconds.getParent(),panel.browserSeconds.getBounds(),conditions);
          scenario("Execution settings stay left aligned without a separator pushing the crawler controls below a large blank area",executable.x<width/3&&executable.y-browserTime.y<120);
        });
        tab(panel,"katana");
        scenario("Katana provenance table preserves linked originals and explicitly unexecuted forms",panel.tables.get("katana").getRowCount()>=3&&panel.selectedOriginal()!=null);
        capture(panel,"24-katana-results.png",width,height,()->{
          panel.showKatanaStatus(new ScopeKatana.Snapshot("PARTIAL","가이드용 합성 기록 · 범위 밖/폼 동작 제외 사유를 확인하세요.",12,18,3,1793100000000L,1793100005000L));
          scenario("Captured Katana results use the production partial-state presenter after asynchronous layout updates",panel.katanaStatus.getText().contains("부분 수집")&&!panel.katanaStatus.getText().contains("선택해 통합 점검"));
        });
      });
      edt(()->{panel.accept(completed);tab(panel,"summary");panel.saveReport(historyDirectory.resolve("categorized-values.zip"));});
      ScopeInspectionUiTest.await(()->panel.report.isEnabled(),"guide's actual local report export");
      edt(()->{
        scenario("Categorized export saves exact values locally while keeping ordinary result views masked",Files.isRegularFile(historyDirectory.resolve("categorized-values.zip"))&&panel.feedback.getText().contains("저장 완료")&&!ScopeInspectionUiTest.visibleText(panel).contains(API_KEY));
        capture(panel,"25-inspection-report.png");
      });
      scenario("Synthetic capture never starts the target HTTP coordinator or external Katana", panel.runner == null && panel.katana==null && count.get() == 0);
    } finally {
      edt(panel::close); store.close(); WorkbenchScope.release(api);
      try (var files = Files.walk(historyDirectory)) {
        for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
      }
    }
    controls.add(0,"theme,width,height,view,label,name,controlWidth,controlHeight,fullyVisible,scrollable");
    content.add(0,"theme,width,height,view,name,visibleWidth,visibleHeight");
    scenarios.add(0,"scenario,passed");
    Files.write(out.resolve("scope-controls.csv"), controls, StandardCharsets.UTF_8);
    Files.write(out.resolve("scope-content.csv"), content, StandardCharsets.UTF_8);
    Files.write(out.resolve("scope-scenarios.csv"), scenarios, StandardCharsets.UTF_8);
    System.out.println("Captured actual Scope inspection components: " + theme + " " + width + "x" + height
        + "; synthetic packet/browser observations, zero target HTTP, not native Burp or live browser DOM.");
    System.exit(0);
  }
}
