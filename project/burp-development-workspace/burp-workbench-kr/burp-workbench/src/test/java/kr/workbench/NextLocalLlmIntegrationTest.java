package kr.workbench;

import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;

/** Real Next UI preparation and immutable disk identities; synthetic Montoya, no model or target network. */
public final class NextLocalLlmIntegrationTest extends WorkbenchTest {
  private interface CheckedAction { void run() throws Exception; }

  private static void onEdt(CheckedAction action) throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    edt(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
    if (failure.get() instanceof Exception error) throw error;
    if (failure.get() instanceof Error error) throw error;
  }

  private static Object invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
    Method method = owner.getClass().getDeclaredMethod(name, types);
    method.setAccessible(true);
    try { return method.invoke(owner, args); }
    catch (InvocationTargetException error) {
      if (error.getCause() instanceof Exception cause) throw cause;
      if (error.getCause() instanceof Error cause) throw cause;
      throw error;
    }
  }

  private static void prepare(WorkbenchExtension extension, LocalLlmTasks.Task task) throws Exception {
    invoke(extension, "prepareSelectedAi", new Class<?>[] {LocalLlmTasks.Task.class}, task);
  }

  private static void rejectsChecked(CheckedAction action, String message) throws Exception {
    try { action.run(); }
    catch (IllegalArgumentException expected) { check(true, message); return; }
    throw new AssertionError(message);
  }

  private static JMenuItem menu(Component component, String name) {
    if (component instanceof JMenuItem item && name.equals(item.getName())) return item;
    Component[] children = component instanceof JPopupMenu popup ? popup.getComponents()
        : component instanceof JMenu item ? item.getMenuComponents() : new Component[0];
    for (Component child : children) { JMenuItem found = menu(child, name); if (found != null) return found; }
    return null;
  }

  private static List<String> selectedIds(JTable table, DiskTrafficStore store) {
    List<String> result = new ArrayList<>();
    for (int view : table.getSelectedRows()) result.add(store.ref(table.convertRowIndexToModel(view)).id());
    return List.copyOf(result);
  }

  private static Map<String, Object> mapperSnapshot(WorkbenchExtension extension) throws Exception {
    Object mapper = get(get(extension, "featureMapper"), "mapper");
    @SuppressWarnings("unchecked") var snapshot = (Map<String, Object>) invoke(mapper, "metadata", new Class<?>[0]);
    return snapshot;
  }

  private static void showReport(LocalLlmPanel ai) {
    String evidenceId = ai.input.sources().get(0).id();
    String json = MetadataExport.json(Map.of("rows", List.of(Map.of("title", "주문 조회 관측", "category", "보고서",
        "source_ids", List.of(evidenceId), "observation", "주문 응답 관측", "uncertainty", "인가 정책 미확인",
        "next_check", "정책과 소유 관계 확인")), "report", "근거 " + evidenceId + " · 영향 미확인"));
    ai.showResult(LocalLlmTasks.parse(json, ai.input), new LocalLlmClient.Reply(json, "stop", 100, 50, 25, ""), false);
  }

  public static void main(String[] args) throws Exception {
    LocalLlmPanel closedPanel;
    try (var fixture = new TrafficScopeModesTest.Fixture()) {
      MontoyaObjectFactory packetFactory = ObjectFactoryLocator.FACTORY;
      ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p, method, values) ->
          method.getName().equals("httpRequestFromUrl")
              ? request(values[0].toString(), "GET", true, List.of())
              : method.invoke(packetFactory, values));
      WorkbenchExtension extension = fixture.ext;
      LocalLlmPanel ai = (LocalLlmPanel) get(extension, "localLlm");
      closedPanel = ai;
      BountySupportPanel bounty = (BountySupportPanel) get(extension, "bountySupport");
      JTable traffic = (JTable) get(extension, "logTable");
      JTabbedPane pages = (JTabbedPane) get(extension, "tabs");
      DiskTrafficStore store = (DiskTrafficStore) get(extension, "trafficStore");
      int initialSends = PayloadIntegrationTest.sent.get();
      check(ai != null && bounty.pages.indexOfComponent(ai) >= 0,
          "Next hosts local AI in the existing evidence workspace");
      check(pages.getTabCount() == 8 && pages.indexOfComponent(bounty) >= 0,
          "local AI keeps all original primary Next tools and routes");
      check(ai.input == null && ai.originals.isEmpty() && !ai.analyze.isEnabled(),
          "loading Next does not prepare, analyze or retain arbitrary traffic");

      var order = request("https://burp.test/api/z-orders/42?token=RAW_QUERY_SECRET", "GET", true,
          List.of(header("Cookie", "session=RAW_COOKIE_SECRET"), header("Authorization", "Bearer RAW_AUTH_SECRET")));
      var orderResponse = response(200, "{\"orderId\":42,\"owner\":\"alice@example.test\",\"amount\":1200}");
      var profile = request("https://burp.test/api/a-profile", "GET", true, List.of());
      var profileResponse = response(200, "{\"userId\":7,\"displayName\":\"Alice\"}");
      seedTraffic(extension, order, orderResponse);
      seedTraffic(extension, profile, profileResponse);
      seedTraffic(extension, order, orderResponse); // Equal bytes, distinct captured occurrence.
      MapperIntegrationTest.idle(extension);
      byte[] originalRequest = store.ref(2).request().toByteArray().getBytes();
      byte[] originalResponse = store.ref(2).response().toByteArray().getBytes();
      String duplicateId = store.ref(2).id();
      check(!store.ref(0).id().equals(duplicateId), "equal packet bytes retain separate original occurrence IDs");

      Component trafficPopup = (Component) traffic.getClientProperty("wb.traffic.popup");
      JMenuItem responseAction = menu(trafficPopup, "wb.traffic.ai.response");
      JMenuItem functionAction = menu(trafficPopup, "wb.traffic.ai.functions");
      check(responseAction != null && functionAction != null, "actual traffic popup exposes response and functional AI preparation");
      onEdt(() -> {
        traffic.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(2, SortOrder.DESCENDING)));
        traffic.clearSelection();
        traffic.addRowSelectionInterval(0, 0);
        traffic.addRowSelectionInterval(2, 2);
      });
      List<String> selected = selectedIds(traffic, store);
      Map<String, Object> beforeMapping = mapperSnapshot(extension);
      onEdt(() -> functionAction.doClick(0));
      check(ai.input.task() == LocalLlmTasks.Task.FUNCTIONS && ai.input.sources().size() == 2,
          "functional preparation carries the actual multiselection and task");
      check(ai.input.sources().stream().map(LocalLlmTasks.Source::id).toList().equals(selected),
          "sorted traffic rows resolve exact immutable disk IDs rather than view indices");
      check(ai.originals.keySet().equals(new LinkedHashSet<>(selected)),
          "every AI source has exactly one retained original link");
      check(ai.preview.getText().contains("orderId") && !ai.preview.getText().contains("RAW_QUERY_SECRET")
              && !ai.preview.getText().contains("RAW_COOKIE_SECRET") && !ai.preview.getText().contains("RAW_AUTH_SECRET"),
          "prepared input preserves useful data while hiding actual packet credentials");
      check(ai.result == null && ai.active == null && !ai.running && PayloadIntegrationTest.sent.get() == initialSends,
          "preparing a functional review starts neither inference nor a target request");
      check(mapperSnapshot(extension).equals(beforeMapping),
          "functional review preparation does not rewrite feature membership or correction rules");

      onEdt(() -> {
        int view = traffic.convertRowIndexToView(2);
        traffic.setRowSelectionInterval(view, view);
        responseAction.doClick(0);
      });
      check(ai.input.task() == LocalLlmTasks.Task.RESPONSE && ai.input.sources().get(0).id().equals(duplicateId),
          "single-response action preserves the chosen duplicate occurrence rather than matching its URL");
      check(Arrays.equals(originalRequest, store.ref(2).request().toByteArray().getBytes())
              && Arrays.equals(originalResponse, store.ref(2).response().toByteArray().getBytes()),
          "AI preparation and redaction leave raw disk request and response bytes unchanged");
      check(Arrays.equals(ai.originals.get(duplicateId).request().toByteArray().getBytes(), originalRequest),
          "linked original keeps exact credentials for explicit local source inspection");

      LocalLlmTasks.Input retained = ai.input;
      onEdt(() -> { traffic.clearSelection(); rejectsChecked(() -> prepare(extension, LocalLlmTasks.Task.RESPONSE),
          "empty traffic selection is rejected before replacing the prepared analysis"); });
      check(ai.input == retained, "failed empty selection preserves the last reviewable input");
      fixture.changed(TrafficScopeModesTest.config("https://burp.test/api/a-profile"));
      onEdt(() -> {
        int view = traffic.convertRowIndexToView(2); traffic.setRowSelectionInterval(view, view);
        rejectsChecked(() -> prepare(extension, LocalLlmTasks.Task.RESPONSE),
            "a scope change invalidates an original before AI preparation");
      });
      check(ai.input == retained, "scope rejection is atomic and does not install a partial input");
      fixture.changed(TrafficScopeModesTest.config("https://burp.test/api/"));

      onEdt(() -> {
        prepare(extension, LocalLlmTasks.Task.FUNCTIONS);
        String json = MetadataExport.json(Map.of("rows", List.of(Map.of("title", "주문 조회", "category", "주문",
            "source_ids", List.of(duplicateId), "observation", "주문 데이터가 관측됨", "uncertainty", "소유 정책 미확인",
            "next_check", "본인 테스트 계정의 정책 확인")), "report", ""));
        var result = LocalLlmTasks.parse(json, ai.input);
        ai.showResult(result, new LocalLlmClient.Reply(json, "stop", 100, 40, 25, ""), false);
        int other = traffic.convertRowIndexToView(1); traffic.setRowSelectionInterval(other, other);
        ai.inspectSource.doClick(0);
      });
      TargetWindow originalWindow = (TargetWindow) get(extension, "aiOriginalWindow");
      check(originalWindow != null && originalWindow.title.contains(duplicateId),
          "proposal source inspection identifies the exact original after user selection changes");
      onEdt(() -> {
        var split = (javax.swing.JSplitPane) originalWindow.content.get();
        check(((PacketView) split.getLeftComponent()).text.getText().contains("RAW_COOKIE_SECRET")
                && ((PacketView) split.getRightComponent()).text.getText().contains("orderId"),
            "source inspection reads retained raw evidence rather than a redacted AI preview or newly selected row");
      });
      check(mapperSnapshot(extension).equals(beforeMapping),
          "displaying a classification proposal never applies it as a persistent Mapper correction");
      check(bounty.evidence.isEmpty() && PayloadIntegrationTest.sent.get() == initialSends,
          "displaying and navigating AI proposals creates no proof and sends no HTTP");

      onEdt(() -> {
        bounty.captureObserved(List.of(ai.originals.get(duplicateId)), "실제 관측 · 정책 미확인");
        bounty.repro.setText("사용자가 작성한 재현 절차");
        bounty.aiReview.run();
      });
      check(ai.input.task() == LocalLlmTasks.Task.REPORT && ai.input.sources().get(0).id().equals(bounty.evidence.get(0).evidenceId()),
          "report entry point binds the exact evidence item identity instead of current traffic selection");
      onEdt(() -> {
        showReport(ai);
        ai.appendReport.doClick(0);
      });
      check(bounty.repro.getText().startsWith("사용자가 작성한 재현 절차") && bounty.repro.getText().contains("영향 미확인"),
          "explicit AI report append preserves existing authored reproduction text");
      check(bounty.evidence.size() == 1 && PayloadIntegrationTest.sent.get() == initialSends,
          "report drafting adds prose without fabricating evidence or sending a request");
      String appendedReport = bounty.repro.getText();
      onEdt(() -> rejectsChecked(ai::appendReport,
          "a successful append invalidates its old draft snapshot and prevents duplicate append"));
      check(bounty.repro.getText().equals(appendedReport), "duplicate append rejection leaves the first accepted report unchanged");

      String reportEvidenceId = bounty.evidence.get(0).evidenceId();
      String reportPacketId = bounty.evidence.get(0).packetId();
      onEdt(() -> {
        bounty.aiReview.run(); showReport(ai);
        bounty.evidenceTable.setRowSelectionInterval(0, 0);
        bounty.openItemEditor();
        bounty.itemName.setText("수동으로 바로잡은 주문 조회 근거");
        bounty.itemNote.setText("공유 기능일 수 있으므로 소유자 전용 정책을 추가로 확인해야 함");
        bounty.applyEvidenceDescription();
        rejectsChecked(ai::appendReport,
            "renaming and annotating the same evidence item invalidates its earlier report callback");
      });
      check(bounty.evidence.get(0).evidenceId().equals(reportEvidenceId)
              && bounty.evidence.get(0).packetId().equals(reportPacketId)
              && bounty.evidence.get(0).label().equals("수동으로 바로잡은 주문 조회 근거"),
          "metadata-staleness validation covers real editor changes while original evidence and packet IDs remain identical");
      check(bounty.repro.getText().equals(appendedReport), "metadata rejection preserves the previously accepted prose");

      String manuallyEditedReport = appendedReport + "\n재현 후 추가한 수동 설명";
      onEdt(() -> {
        bounty.aiReview.run(); showReport(ai);
        bounty.repro.setText(manuallyEditedReport);
        rejectsChecked(ai::appendReport, "manual reproduction edits invalidate an already prepared report draft");
      });
      check(bounty.repro.getText().equals(manuallyEditedReport), "stale draft cannot overwrite or append to the user's newer reproduction text");

      onEdt(() -> {
        bounty.aiReview.run(); showReport(ai);
        bounty.impact.setText("추가 검증 전 실제 영향은 미확정");
        rejectsChecked(ai::appendReport, "manual impact edits invalidate an already prepared report draft");
      });
      check(bounty.repro.getText().equals(manuallyEditedReport)
              && bounty.impact.getText().equals("추가 검증 전 실제 영향은 미확정"),
          "impact-staleness rejection preserves both the updated impact and reproduction text");

      onEdt(() -> {
        bounty.aiReview.run(); showReport(ai);
        bounty.evidenceTable.setRowSelectionInterval(0, 0);
        bounty.evidenceRemove.doClick(0);
        rejectsChecked(ai::appendReport, "changed evidence membership invalidates an old report append callback");
      });
      check(bounty.repro.getText().equals(manuallyEditedReport), "stale report rejection preserves the user's current writing");

      onEdt(() -> {
        traffic.setRowSelectionInterval(0, 1);
        prepare(extension, LocalLlmTasks.Task.LOGIC);
        check(ai.input.task() == LocalLlmTasks.Task.LOGIC && ai.input.sources().size() == 2,
            "related original traffic supports a separate business-rule review task");
        bounty.flowResults.add(new BountyWorkflow.Result(1, profile, profileResponse, "관측 완료"));
        bounty.flowResults.add(new BountyWorkflow.Result(2, order, orderResponse, "정책 미확인"));
        bounty.flowChecks.setText("2.HTTP=200");
        bounty.aiFlowReview.run();
        check(ai.input.task() == LocalLlmTasks.Task.LOGIC && ai.input.sources().get(0).url().contains("a-profile")
                && ai.input.sources().get(1).url().contains("z-orders"),
            "workflow review preserves actual step order and bound original packets");
        check(ai.input.context().contains("2.HTTP=200") && ai.input.context().contains("관측 순서 2"),
            "workflow review carries expected checks and observed state without replaying the workflow");
        check(PayloadIntegrationTest.sent.get() == initialSends,
            "business logic review preparation does not run target workflow steps");
      });

      for (int i = 3; i < 25; i++) seedTraffic(extension,
          request("https://burp.test/api/batch/" + i, "GET", true, List.of()), response(200, "{\"item\":" + i + "}"));
      MapperIntegrationTest.idle(extension);
      retained = ai.input;
      onEdt(() -> {
        traffic.setRowSelectionInterval(0, traffic.getRowCount() - 1);
        rejectsChecked(() -> prepare(extension, LocalLlmTasks.Task.FUNCTIONS),
            "oversized functional batch is rejected rather than silently dropping originals");
      });
      check(ai.input == retained, "batch rejection leaves the prior input and source mapping intact");
      onEdt(() -> {
        ai.apiKey.setText("LOCAL_KEY_ONLY");
        invoke(extension, "clearStoredTraffic", new Class<?>[0]);
      });
      check(ai.input == null && ai.originals.isEmpty() && ai.result == null && ai.cache.isEmpty(),
          "clearing retained traffic removes stale AI input, result, originals and cache");
      check(ai.apiKey.getPassword().length == 0 && ai.preview.getText().isEmpty() && !ai.analyze.isEnabled(),
          "source reset clears local credentials and disables analysis until new evidence is prepared");
      check(PayloadIntegrationTest.sent.get() == initialSends && fixture.mutations.get() == 0,
          "all preparation, rejection, navigation and reset actions preserve target HTTP and global Scope");
    }
    check(closedPanel.closed && closedPanel.worker.isShutdown() && closedPanel.originals.isEmpty(),
        "unloading the real Next extension shuts down AI jobs and releases originals");
    System.out.println("NextLocalLlmIntegrationTest: " + checks + " checks passed");
  }
}
