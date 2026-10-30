package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import burp.api.montoya.repeater.Repeater;
import java.awt.Component;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;

/** Actual extension navigation and original handoffs through the dashboard; no native Burp or target HTTP. */
public final class SimpleDashboardIntegrationTest extends WorkbenchTest {
  private static Object field(Object owner, String name) {
    try { return get(owner, name); }
    catch (Exception error) { throw new AssertionError(error); }
  }

  private static void set(Object owner, String name, Object value) {
    try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
    catch (Exception error) { throw new AssertionError(error); }
  }

  private static void invoke(Object owner, String name) {
    try { var method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(owner); }
    catch (InvocationTargetException error) { throw new AssertionError(error.getCause()); }
    catch (Exception error) { throw new AssertionError(error); }
  }

  private static String feature(Component page) {
    return Objects.toString(((JComponent) page).getClientProperty("wb.feature.id"), "");
  }

  private static ScopeInspection.Snapshot empty() {
    return new ScopeInspection.Snapshot("", ScopeInspection.State.READY, "", 0, 0, 0, 0, 0, 0,
        List.of(), List.of(), List.of(), 0, 0);
  }

  public static void main(String[] args) throws Exception {
    var baseline = SimpleWorkflowTest.previewApi();
    var repeated = new AtomicReference<HttpRequest>();
    var repeater = proxy(Repeater.class, (p, method, arguments) -> {
      if (method.getName().equals("sendToRepeater")) repeated.set((HttpRequest) arguments[0]);
      return def(method);
    });
    var api = proxy(MontoyaApi.class, (p, method, arguments) ->
        method.getName().equals("repeater") ? repeater : method.invoke(baseline, arguments));
    ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p, method, arguments) -> def(method));
    var entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks) PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    int sentBefore = PayloadIntegrationTest.sent.get();
    edt(() -> entry.initialize(api)); edt(() -> {});
    var extension = (WorkbenchExtension) field(entry, "workbench");
    var root = (JPanel) field(extension, "root");
    var tabs = (JTabbedPane) field(extension, "tabs");
    var dashboard = (ScopeDashboardPanel) field(extension, "scopeDashboard");
    var evidence = (BountySupportPanel) field(extension, "bountySupport");
    var dialog = new AtomicReference<Component>();
    extension.dialogOpener = (owner, title, body, modal, closed) -> { dialog.set(body); return null; };
    try (var store = new DiskTrafficStore()) {
      edt(() -> {
        check(feature(tabs.getComponentAt(0)).equals("traffic") && feature(tabs.getComponentAt(1)).equals("auth"),
            "dashboard preserves traffic/auth engine indices 0 and 1");
        check(tabs.getComponentAt(tabs.getTabCount() - 1) == dashboard && feature(dashboard).equals("dashboard"),
            "dashboard is appended with its own stable feature ID");
        check(field(extension, "scopeInspectionPanel") == null, "extension startup does not construct or start inspection");
        SimpleWorkflowTest.click(root, "wb.simple.dashboard.open");
        check(tabs.getSelectedComponent() == dashboard, "production top-bar dashboard action opens the appended page");
        check(SimpleWorkflowTest.toolSelected(root,"dashboard") && !SimpleWorkflowTest.toolSelected(root,"traffic"),
            "dashboard folder selection replaces the previous tool highlight");
        SimpleWorkflowTest.click(root, "wb.simple.dashboard.open");
        check(((javax.swing.AbstractButton) SimpleWorkflowTest.named(root,"wb.simple.dashboard.open")).isSelected(),
            "clicking the current dashboard keeps its navigation highlight selected");
        check(field(extension, "scopeInspectionPanel") == null && dashboard.current.id().isEmpty(),
            "opening an empty dashboard remains read-only and leaves inspection unconstructed");
        var js = (JsAnalysisPanel) field(extension, "jsAnalysis");
        var payloadInput = (JTextArea) field(field(entry, "payloadRunner"), "request");
        js.input.setText("fetch('/api/dashboard-retained-input');");
        payloadInput.setText("DASHBOARD_MANUALLY_PREPARED_INPUT");
        String[] tools = {"traffic", "mapper", "auth", "js", "environment", "response.recon", "payload", "bounty"};
        var pages = new LinkedHashMap<String, Component>();
        for (String tool : tools) {
          SimpleWorkflowTest.click(root, "wb.simple.tool." + tool);
          pages.put(tool, tabs.getSelectedComponent());
        }
        SimpleWorkflowTest.click(root, "wb.simple.dashboard.open");
        boolean retained = true;
        for (String tool : tools) {
          SimpleWorkflowTest.click(root, "wb.simple.tool." + tool);
          retained &= feature(tabs.getSelectedComponent()).equals(tool) && tabs.getSelectedComponent() == pages.get(tool);
        }
        check(retained && js.input.getText().equals("fetch('/api/dashboard-retained-input');")
            && payloadInput.getText().equals("DASHBOARD_MANUALLY_PREPARED_INPUT"),
            "all eight original tools retain engine instances and unsaved inputs across dashboard round trips");
        SimpleWorkflowTest.click(root, "wb.simple.dashboard.open");
        check(tabs.getSelectedComponent() == dashboard, "return navigation reuses the same dashboard instance");
        SimpleWorkflowTest.click(dashboard, "wb.scope.dashboard.inspect");
        check(field(extension, "scopeInspectionPanel") instanceof ScopeInspectionPanel
            && dialog.get() == field(extension, "scopeInspectionPanel"),
            "dashboard inspect action opens the existing production integration window");
      });
      var inspection = (ScopeInspectionPanel) field(extension, "scopeInspectionPanel");
      check(inspection.runner == null, "opening inspection from dashboard does not start an HTTP runner");
      String url = "https://dashboard.example.test/api/report";
      var aliceRequest = request(url, "GET", true, List.of(header("Cookie", "SID=ALICE_DASHBOARD")));
      var bobRequest = request(url, "GET", true, List.of(header("Cookie", "SID=BOB_DASHBOARD")));
      var aliceReply = response(200, "{\"account\":\"Alice\",\"document\":101}");
      var bobReply = response(403, "{\"account\":\"Bob\",\"message\":\"denied\"}");
      registerTrafficPacket(aliceRequest, aliceReply); registerTrafficPacket(bobRequest, bobReply);
      var aliceOriginal = store.append("", "대시보드 검증 Alice", aliceRequest, aliceReply);
      var bobOriginal = store.append("", "대시보드 검증 Bob", bobRequest, bobReply);
      var snapshot = new ScopeInspection.Snapshot("dashboard-integration-fixture", ScopeInspection.State.READY, "합성 원본 검증", 0, 2, 0, 0, 0, 0,
          List.of(new ScopeInspection.Finding("SECRET", "예제 키 후보", url, "GET", "Alice", "형식 후보", "HIGH", "유효성 미검증", "가림 예제", aliceOriginal.id(), 3, 11, "shared-candidate"),
              new ScopeInspection.Finding("SECRET", "예제 키 후보", url, "GET", "Bob", "형식 후보", "HIGH", "유효성 미검증", "가림 예제", bobOriginal.id(), 4, 21, "shared-candidate")),
          List.of(new ScopeInspection.Page(url, "GET", "Alice", 200, "JSON", true, "", "alice-digest", aliceOriginal),
              new ScopeInspection.Page(url, "GET", "Bob", 403, "DENIED", true, "", "bob-digest", bobOriginal)), List.of(), 100, 0);
      edt(() -> {
        ((WorkbenchScope) field(extension, "localScope")).add("https://dashboard.example.test/", true);
        inspection.options = new ScopeInspection.Options(url, 20, 1, 0, 2000, 1024 * 1024, 2, false, true,
            List.of(new ScopeInspection.Profile("alice", "Alice", Map.of(), null), new ScopeInspection.Profile("bob", "Bob", Map.of(), null)));
        set(inspection, "runProject", WorkspaceResume.projectIdentity(api));
        set(inspection, "runScope", ((WorkbenchScope) field(extension, "localScope")).inspectionFingerprint());
        inspection.accept(snapshot);
        check(dashboard.current == snapshot && dashboard.target.getText().contains("dashboard.example.test"),
            "actual inspection accept publishes the same run and target into dashboard");
        check(dashboard.cards.get(ScopeInspectionDashboard.Group.PATHS).count == 1
            && dashboard.cards.get(ScopeInspectionDashboard.Group.SECRETS).count == 2,
            "production listener shows one shared path while preserving two account-specific candidates");
        SimpleWorkflowTest.click(dashboard, "wb.scope.dashboard.card.secrets");
        for (int i = 0; i < dashboard.account.getItemCount(); i++)
          if (dashboard.account.getItemAt(i).value().equals("Bob")) dashboard.account.setSelectedIndex(i);
        check(dashboard.table.getRowCount() == 1 && dashboard.selectedRow().originalId().equals(bobOriginal.id()),
            "account drill-down selects Bob's own original despite a shared candidate fingerprint");
        SimpleWorkflowTest.click(dashboard, "wb.scope.dashboard.original");
        boolean correctOriginal = dialog.get() instanceof JComponent content
            && content.getName().equals("wb.scope.inspection.original")
            && SimpleWorkflowTest.visibleComponents(content).stream().anyMatch(c -> c instanceof JLabel label && label.getText().contains(bobOriginal.id()));
        check(correctOriginal, "original action resolves the selected ID into Bob's actual packet inspector");
        SimpleWorkflowTest.click(dashboard, "wb.scope.dashboard.repeater");
        check(repeated.get() == bobRequest && repeated.get().headerValue("Cookie").equals("SID=BOB_DASHBOARD"),
            "Repeater preparation receives the exact selected original request without changing account identity");
        check(PayloadIntegrationTest.sent.get() == sentBefore, "inspector and Repeater preparation send zero target HTTP");
        SimpleWorkflowTest.click(dashboard, "wb.scope.dashboard.evidence");
        check(evidence.evidence.size() == 1 && evidence.evidence.get(0).account().equals("Bob")
            && evidence.evidence.get(0).request().contains("SID=BOB_DASHBOARD")
            && !evidence.evidence.get(0).request().contains("SID=ALICE_DASHBOARD")
            && evidence.evidence.get(0).note().contains(bobOriginal.id()),
            "evidence capture keeps Bob's selected bytes, account and original-ID attribution together");
        check(SimpleWorkflowTest.selectedFeature(tabs).equals("bounty") && evidence.pages.getSelectedIndex() == 3,
            "evidence handoff reaches the retained evidence route at index 3");
        rejects(() -> dashboard.actions.original("unknown-original"), "unknown original IDs never resolve to a nearby packet");
        SimpleWorkflowTest.click(root, "wb.simple.dashboard.open");
        check(dashboard.selectedRow() != null && dashboard.selectedRow().originalId().equals(bobOriginal.id()),
            "returning from evidence preserves the current filtered selection");
        inspection.accept(empty());
        check(dashboard.current.id().isEmpty() && dashboard.table.getRowCount() == 0
            && !dashboard.original.isEnabled() && !dashboard.repeater.isEnabled() && !dashboard.evidence.isEnabled(),
            "clearing the live snapshot clears dashboard rows and disables old packet actions");
        rejects(() -> dashboard.actions.original(bobOriginal.id()), "cleared original cannot be reopened through a stale action");
        rejects(() -> dashboard.actions.repeater(bobOriginal.id()), "cleared original cannot be sent to Repeater through a stale action");
        rejects(() -> dashboard.actions.evidence(bobOriginal.id()), "cleared original cannot be added as evidence through a stale action");
        inspection.accept(snapshot);
        check(dashboard.current == snapshot && dashboard.table.getRowCount() > 0, "a newly published live snapshot restores available dashboard results");
        api.persistence().extensionData().setString(WorkspaceResume.PROJECT_KEY, "33333333-3333-4333-8333-333333333333");
        invoke(extension, "rememberWorkspace");
        check(inspection.current.pages().isEmpty() && dashboard.current.id().isEmpty()
            && dashboard.table.getRowCount() == 0 && evidence.evidence.isEmpty(),
            "project transition clears inspection originals, dashboard results and previous-project evidence together");
        rejects(() -> dashboard.actions.original(bobOriginal.id()), "previous-project original is rejected after project transition");
        rejects(() -> dashboard.actions.repeater(bobOriginal.id()), "previous-project Repeater handoff is rejected after project transition");
        rejects(() -> dashboard.actions.evidence(bobOriginal.id()), "previous-project evidence handoff is rejected after project transition");
      });
      check(PayloadIntegrationTest.sent.get() == sentBefore, "loading, filtering, navigation, handoffs and lifecycle cleanup perform zero target HTTP");
    } finally { SimpleWorkflowTest.unload(); }
    System.out.println("SimpleDashboardIntegrationTest: " + checks + " checks passed (production adapter, no native Burp/HTTP)");
  }
}
