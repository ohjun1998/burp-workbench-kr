package kr.workbench;

import burp.api.montoya.core.*;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import kr.featuremapper.FeatureMapperWorkspace;

/** Production capture pipeline and embedded workspace, plus the real loopback metadata bridge. */
public final class MapperIntegrationTest extends WorkbenchTest {
  static Object invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
    Method method = owner.getClass().getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method.invoke(owner, args);
  }

  static long count(Object model) throws Exception {
    return ((Number) invoke(model, "count", new Class<?>[0])).longValue();
  }

  static void idle(WorkbenchExtension ext) throws Exception {
    CaptureObservations worker = (CaptureObservations) get(ext, "captureObservations");
    for (int i = 0; i < 250; i++) {
      edt(() -> {});
      if (worker.pending() == 0) return;
      Thread.sleep(20);
    }
    throw new AssertionError("Mapper observation worker did not finish");
  }

  static long send(TrafficScopeModesTest.Fixture f, String url, int id) throws Exception {
    HttpRequest q = request(url, "GET", false, List.of(header("Cookie", "SESSION=DEMO")));
    HttpResponse r = response(200, "{\"id\":101,\"name\":\"demo\"}");
    registerTrafficPacket(q, r);
    ToolSource source = proxy(ToolSource.class, (p, m, a) ->
        m.getName().equals("toolType") ? ToolType.PROXY : def(m));
    var request = proxy(HttpRequestToBeSent.class, (p, m, a) -> switch (m.getName()) {
      case "messageId" -> id;
      case "toolSource" -> source;
      case "annotations" -> null;
      default -> HttpRequest.class.getMethod(m.getName(), m.getParameterTypes()).invoke(q, a);
    });
    f.ext.handleHttpRequestToBeSent(request);
    var workspace = (FeatureMapperWorkspace) get(f.ext, "featureMapper");
    Long started = ((Map<Integer, Long>) get(workspace, "starts")).get(id);
    var response = proxy(HttpResponseReceived.class, (p, m, a) -> switch (m.getName()) {
      case "messageId" -> id;
      case "initiatingRequest" -> q;
      case "toolSource" -> source;
      case "annotations" -> null;
      default -> HttpResponse.class.getMethod(m.getName(), m.getParameterTypes()).invoke(r, a);
    });
    f.ext.handleHttpResponseReceived(response);
    idle(f.ext);
    check(!((Map<?, ?>) get(workspace, "starts")).containsKey(id),
        "response releases request timing metadata: " + id);
    return started == null ? -1 : started;
  }

  static int browser(HttpClient client, String base, String token, String url, String action)
      throws Exception {
    String json = MetadataExport.json(Map.of("type", "network", "url", url, "method", "GET",
        "time", System.currentTimeMillis(), "actionId", action, "label", "내 정보 조회",
        "page", "https://burp.test/profile", "tab", "demo-tab", "frame", "0"));
    var request = java.net.http.HttpRequest.newBuilder(URI.create(base + "/event"))
        .header("Content-Type", "application/json").header("X-Feature-Mapper-Token", token)
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json)).build();
    return client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
  }

  public static void main(String[] args) throws Exception {
    Path raw = null;
    String bridgeUrl = null;
    FeatureMapperWorkspace workspace = null;
    try (TrafficScopeModesTest.Fixture f = new TrafficScopeModesTest.Fixture()) {
      workspace = (FeatureMapperWorkspace) get(f.ext, "featureMapper");
      Object model = get(workspace, "mapper");
      JPanel root = (JPanel) get(f.ext, "root");
      JTabbedPane pages = (JTabbedPane) get(f.ext, "tabs");
      JButton mapperCapture = (JButton) UiLayoutTest.named(root, "fm.capture");
      check(pages.getComponentAt(pages.indexOfTab("기능 자동 매핑")) == workspace.component(),
          "Workbench navigation hosts the actual Mapper workspace");
      check(PayloadIntegrationTest.suiteTabs.get() == 1, "embedding registers one Burp suite tab");
      check(PayloadIntegrationTest.names.stream().noneMatch(name -> name.startsWith("Feature Mapper")),
          "embedded Mapper preserves Workbench extension identity");
      check(MapperIntegrationTest.class.getResource("/kr/featuremapper/FeatureMapperExtension.class") == null,
          "Workbench JAR excludes the standalone Montoya entry point");
      check(UiLayoutTest.named(root, "fm.navigation.actions") == null,
          "embedded page uses the Workbench guidance/settings/log header once");
      check(PacketUiTest.buttons(workspace.component()).contains("매핑 설정"),
          "browser connection and project reset remain reachable");
      check(mapperCapture.getText().equals("OFF") && count(model) == 0,
          "shared collection starts OFF and Mapper stores no packets");
      send(f, "https://burp.test/api/profile", 1);
      check(count(model) == 0, "OFF response never enters Mapper");
      edt(() -> mapperCapture.doClick(0));
      check(f.mode().getText().equals("SCOPE") && mapperCapture.getText().equals("SCOPE"),
          "Mapper capture button changes the shared Workbench mode");
      send(f, "https://outside.test/api/profile", 2);
      check(count(model) == 0, "SCOPE rejects outside traffic before mapping");
      long started = send(f, "https://burp.test/api/profile", 3);
      DiskTrafficStore store = (DiskTrafficStore) get(f.ext, "trafficStore");
      check(count(model) == 1 && store.size() == 1, "one admitted response maps exactly once");
      Object packet = ((Map<?, ?>) get(model, "packets")).values().iterator().next();
      check(started > 0 && store.ref(0).capturedAt() == started
              && ((Number) get(packet, "time")).longValue() == started,
          "request start time survives the disk worker for browser association");
      send(f, "https://burp.test/api/profile", 4);
      check(count(model) == 2, "two observed responses retain two originals without duplicate callbacks");
      f.bring("https://burp.test/api/imported"); idle(f.ext);
      check(count(model) == 3, "admitted History import uses the same mapping pipeline");
      f.changed(TrafficScopeModesTest.excludedConfig("https://burp.test/api/excluded"));
      send(f, "https://burp.test/api/excluded", 5);
      check(count(model) == 3, "native Burp exclusion applies to Mapper");

      Object bridge = get(workspace, "bridge");
      bridgeUrl = (String) invoke(bridge, "baseUrl", new Class<?>[0]);
      String token = (String) get(bridge, "token");
      HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
      edt(() -> f.mode().doClick(0));
      check(mapperCapture.getText().equals("OFF"), "traffic capture button updates Mapper immediately");
      check(browser(client, bridgeUrl, token, "https://burp.test/api/profile", "off") == 200
              && ((Map<?, ?>) get(model, "browserEvents")).isEmpty(),
          "OFF discards browser metadata without retaining it");
      edt(() -> f.mode().doClick(0));
      browser(client, bridgeUrl, token, "https://outside.test/api/profile", "outside");
      check(((Map<?, ?>) get(model, "browserEvents")).isEmpty(),
          "browser metadata follows the same Scope filter");
      check(browser(client, bridgeUrl, token, "https://burp.test/api/profile", "inside") == 200
              && ((Map<?, ?>) get(model, "browserEvents")).size() == 1,
          "paired loopback browser event reaches the integrated mapper");

      send(f, "https://burp.test/api/browser-case", 6);
      browser(client, bridgeUrl, token, "https://burp.test/api/browser-case", "unique-browser-action");
      Object mapped = ((Map<?, ?>) get(model, "packets")).get(4L);
      Object feature = ((Map<?, ?>) get(model, "features")).get(get(mapped, "primary"));
      check("unique-browser-action".equals(get(mapped, "browserAction"))
              && "내 정보 조회 · /profile".equals(get(feature, "name")),
          "browser button label classifies the matching integrated packet");

      HttpRequest timingRequest = request("https://burp.test/api/timing-fallback", "GET", false, List.of());
      HttpResponse timingResponse = response(200, "{}");
      registerTrafficPacket(timingRequest, timingResponse);
      ToolSource timingSource = proxy(ToolSource.class, (p,m,a) ->
          m.getName().equals("toolType") ? ToolType.PROXY : def(m));
      var timingEvent = proxy(HttpResponseReceived.class, (p,m,a) -> switch(m.getName()) {
        case "messageId" -> throw new IllegalStateException("test timing metadata unavailable");
        case "initiatingRequest" -> timingRequest;
        case "toolSource" -> timingSource;
        case "annotations" -> null;
        default -> HttpResponse.class.getMethod(m.getName(),m.getParameterTypes()).invoke(timingResponse,a);
      });
      f.ext.handleHttpResponseReceived(timingEvent); idle(f.ext);
      check(count(model) == 5 && store.size() == 5,
          "unavailable mapping timing cannot abort original response capture");

      Path project = Files.createTempFile("embedded-mapper-", ".fmkr");
      try {
        Class<?> io = Class.forName("kr.featuremapper.ProjectIO");
        Method save = io.getDeclaredMethod("save", model.getClass(), Path.class);save.setAccessible(true);
        Method load = io.getDeclaredMethod("load", Path.class);load.setAccessible(true);
        save.invoke(null, model, project);
        Object restored = load.invoke(null, project);
        try {
          check(count(restored) == count(model), "existing fmkr format restores integrated packet count");
          byte[][] original = (byte[][]) invoke(model, "raw", new Class<?>[]{long.class}, 1L);
          byte[][] copy = (byte[][]) invoke(restored, "raw", new Class<?>[]{long.class}, 1L);
          check(Arrays.deepEquals(original, copy), "fmkr round trip preserves original request/response bytes");
        } finally { ((AutoCloseable) restored).close(); }
      } finally { Files.deleteIfExists(project); }
      Object packetStore = invoke(model, "store", new Class<?>[0]);
      raw = (Path) invoke(packetStore, "path", new Class<?>[0]);
      check(Files.exists(raw), "integrated Mapper owns a live temporary packet store");
      check(PayloadIntegrationTest.sent.get() == 0, "mapping and project round trip send no target requests");
    }
    edt(() -> {});
    check(raw != null && !Files.exists(raw), "Workbench unload removes Mapper temporary originals");
    check(Boolean.TRUE.equals(workspace.component().getClientProperty("fm.closed")),
        "Workbench unload stops the Mapper UI lifecycle");
    check(((java.util.concurrent.ExecutorService) get(workspace, "io")).isShutdown()
            && ((java.util.concurrent.ExecutorService) get(workspace, "previews")).isShutdown(),
        "Workbench unload stops Mapper file and preview workers");
    try (java.net.Socket socket = new java.net.Socket()) {
      socket.connect(new java.net.InetSocketAddress("127.0.0.1", URI.create(bridgeUrl).getPort()), 200);
      throw new AssertionError("unloaded bridge still accepts connections");
    } catch (java.io.IOException expected) { check(true, "Workbench unload closes the browser bridge"); }
    workspace.close();
    check(!Files.exists(raw), "closing Mapper twice is safe");
    System.out.println("MapperIntegrationTest: " + checks + " checks passed (API adapter and loopback bridge)");
  }
}
