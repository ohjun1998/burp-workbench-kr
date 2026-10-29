package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.awt.event.FocusEvent;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.JList;

/** Target/account completion workflow across two actual loopback HTTP origins. */
public final class ScopeInspectionTargetFlowTest extends WorkbenchTest {
  private static String account(ScopeInspectionPanel panel) {
    return panel.profile.getSelectedItem() instanceof ScopeInspection.Profile p ? p.id() : "";
  }

  private static void select(ScopeInspectionPanel panel, String id) {
    for (int i = 0; i < panel.profile.getItemCount(); i++) {
      if (panel.profile.getItemAt(i).id().equals(id)) { panel.profile.setSelectedIndex(i); return; }
    }
    throw new AssertionError("Expected target account is absent");
  }

  private static void completeByFocus(ScopeInspectionPanel panel) {
    var event = new FocusEvent(panel.url, FocusEvent.FOCUS_LOST);
    for (var listener : panel.url.getFocusListeners()) listener.focusLost(event);
  }

  public static void main(String[] args) throws Exception {
    boolean probe = args.length == 2 && args[0].equals("--probe");
    var report = new LinkedHashMap<String, Object>();
    report.put("scenario", "Change target origin and explicitly choose the matching HTTP account");
    report.put("environment", "Production Swing panel, two actual loopback HTTP origins, Montoya adapter; no native Burp GUI");
    report.put("units", Map.of("actions", "explicit UI actions", "requests", "HTTP requests"));
    try (var first = new ScopeInspectionFixture(); var second = new ScopeInspectionFixture()) {
      String firstCookie = "SID=REALM_A_ONLY";
      var anonymous = new ScopeInspection.Profile("anonymous", "비로그인", Map.of(), null);
      var firstAccount = new ScopeInspection.Profile("realm-a", "Realm A", Map.of("cookie", firstCookie), null);
      var secondAccount = new ScopeInspection.Profile("realm-b", "Realm B", Map.of("cookie", ScopeInspectionFixture.SESSION), null);
      Http http = proxy(Http.class, (owner, method, values) -> {
        if (!method.getName().equals("sendRequest")) return def(method);
        HttpRequest request = (HttpRequest)values[0];
        if (request.url().startsWith(first.origin + "/")) return first.api.http().sendRequest(request);
        if (request.url().startsWith(second.origin + "/")) return second.api.http().sendRequest(request);
        throw new AssertionError("The target-flow fixture attempted external HTTP");
      });
      MontoyaApi api = proxy(MontoyaApi.class, (owner, method, values) -> method.getName().equals("http")
          ? http : method.invoke(first.api, values));
      var scope = WorkbenchScope.install(api);
      scope.add(first.origin + "/app/", false); scope.add(second.origin + "/app/", false);
      var lookups = new AtomicInteger();
      var original = new AtomicReference<String>();
      ScopeInspectionPanel.Actions actions = new ScopeInspectionPanel.Actions() {
        public void original(DiskTrafficStore.PacketRef packet) { original.set(packet.id()); }
        public void repeater(DiskTrafficStore.PacketRef packet) {}
        public void compare(DiskTrafficStore.PacketRef packet) {}
        public void evidence(List<DiskTrafficStore.PacketRef> packets, String note) {}
        public void tool(String id, DiskTrafficStore.PacketRef packet) {}
      };
      var holder = new AtomicReference<ScopeInspectionPanel>();
      edt(() -> holder.set(new ScopeInspectionPanel(api, target -> {
        lookups.incrementAndGet();
        URI.create(target);
        if(target.endsWith("/lookup-failure"))throw new IllegalStateException("Temporary account lookup failure fixture");
        if (target.startsWith(second.origin + "/")) {
          // Authenticated first on purpose: changing target must never implicitly choose it.
          return target.contains("no-anonymous") ? List.of(secondAccount) : List.of(secondAccount, anonymous);
        }
        return List.of(anonymous, firstAccount);
      }, actions)));
      var panel = holder.get();
      try {
        edt(() -> {
          panel.url.setText(first.origin + "/app/config"); select(panel, "realm-a");
          panel.depth.setValue(0); panel.delay.setValue(0); panel.timeout.setValue(3); panel.budget.setValue(30);
          panel.start.doClick(0);
          int before = lookups.get(); panel.url.postActionEvent(); completeByFocus(panel);
          report.put("activeRunDoesNotRefreshProfiles", before == lookups.get() && account(panel).equals("realm-a"));
        });
        ScopeInspectionUiTest.await(() -> ScopeInspectionUiTest.terminal(panel.current), "first origin inspection completes");
        var firstRun = panel.current;
        check(firstRun.state() == ScopeInspection.State.COMPLETED && firstRun.sent() == first.visits.size(),
            "first target completes an actual counted HTTP inspection");
        check(firstRun.pages().stream().allMatch(p -> p.original() != null
            && firstCookie.equals(p.original().request().headerValue("Cookie"))), "first-origin originals contain only its explicit account cookie");
        int firstRequests = first.visits.size();
        var flow = new ArrayList<String>();
        edt(() -> {
          int before = lookups.get(); flow.add("edit target URL"); panel.url.setText(second.origin + "/app/config");
          report.put("noPerKeystrokeProfileLookup", before == lookups.get());
          flow.add("complete URL with Enter"); panel.url.postActionEvent();
          report.put("targetCompletionSelectsAnonymous", account(panel).equals("anonymous"));
          report.put("targetCompletionUsesNewProfileList", panel.profile.getItemCount() == 2
              && panel.profile.getItemAt(0).id().equals("realm-b"));
          report.put("requestsBeforeExplicitStart", first.visits.size() - firstRequests + second.visits.size());
          if (!account(panel).equals("anonymous") && !account(panel).equals("realm-b")) {
            flow.add("failed start attempt"); panel.start.doClick(0);
            report.put("staleAccountFailureObserved", panel.feedback.getText().contains("계정 목록"));
            flow.add("refresh target list"); SimpleWorkflowTest.click(panel, "wb.scope.inspection.refresh");
          } else report.put("staleAccountFailureObserved", false);
          flow.add("explicitly choose Realm B account"); select(panel, "realm-b");
          flow.add("start inspection"); panel.start.doClick(0);
        });
        ScopeInspectionUiTest.await(() -> ScopeInspectionUiTest.terminal(panel.current), "second origin inspection completes");
        var secondRun = panel.current;
        check(secondRun.state() == ScopeInspection.State.COMPLETED && secondRun.sent() == second.visits.size(),
            "the explicitly selected new target completes with exact request accounting");
        check(secondRun.sent() <= panel.options.budget(), "changing the target preserves the configured strict request budget");
        check(panel.options.profiles().size() == 1 && panel.options.profiles().get(0).id().equals("realm-b"),
            "only the explicitly chosen new-origin profile enters the run options");
        check(secondRun.pages().stream().allMatch(p -> p.original() != null
            && p.original().url().startsWith(second.origin + "/")
            && ScopeInspectionFixture.SESSION.equals(p.original().request().headerValue("Cookie"))),
            "new-origin originals contain its account cookie and never the prior origin's cookie");
        check(first.visits.size() == firstRequests, "editing and running the new target does not contact the former origin");
        int totalRequests = first.visits.size() + second.visits.size();
        edt(() -> {
          panel.pages.setSelectedIndex(ScopeInspectionUiTest.indexOf(panel.pages, "paths"));
          panel.tables.get("paths").setRowSelectionInterval(0, 0);
          String id = panel.selectedOriginal().id(); panel.original.doClick(0);
          report.put("exactNewOriginalAction", id.equals(original.get())
              && secondRun.pages().stream().anyMatch(p -> p.original() != null && p.original().id().equals(id)));
          panel.url.setText(second.origin + "/app/config?view=2"); completeByFocus(panel);
          report.put("focusCompletionPreservesAvailableAccount", account(panel).equals("realm-b"));
          panel.url.setText(first.origin + "/app/config"); completeByFocus(panel);
          report.put("focusCompletionRefreshesTarget", panel.profile.getItemCount() == 2
              && panel.profile.getItemAt(1).id().equals("realm-a") && account(panel).equals("anonymous"));
          panel.url.setText(second.origin + "/app/no-anonymous"); completeByFocus(panel);
          report.put("missingAnonymousLeavesNoSelection", panel.profile.getSelectedItem() == null);
          Object rendered = panel.profile.getRenderer().getListCellRendererComponent(new JList<>(), null, -1, false, false);
          report.put("nullSelectionExplainsRequiredChoice", rendered instanceof JLabel label && label.getText().contains("계정 선택"));
          if (panel.profile.getSelectedItem() == null) {
            panel.start.doClick(0); report.put("missingAccountStartIsBlocked", panel.feedback.getText().contains("계정을 선택"));
          } else report.put("missingAccountStartIsBlocked", false);
          panel.url.setText("http://[invalid"); panel.url.postActionEvent();
          report.put("malformedCompletionClearsOldAccount", panel.profile.getSelectedItem() == null
              && panel.profile.getItemCount() == 0 && panel.feedback.getText().contains("URL"));
          panel.start.doClick(0);
          report.put("malformedStartSendsNoHttp", first.visits.size() + second.visits.size() == totalRequests);
          panel.url.setText(second.origin + "/app/config"); completeByFocus(panel); select(panel, "realm-b");
          panel.url.setText(first.origin + "/app/lookup-failure"); completeByFocus(panel);
          report.put("lookupFailureClearsOldAccount", panel.profile.getSelectedItem() == null
              && panel.profile.getItemCount() == 0 && panel.feedback.getText().contains("계정 목록"));
          panel.url.setText(second.origin + "/app/config"); panel.url.postActionEvent();
          report.put("completionRecoversAfterLookupFailure", account(panel).equals("anonymous")
              && panel.profile.getItemCount() == 2 && panel.feedback.getText().contains("갱신했습니다")
              && !panel.feedback.getText().contains("못했습니다"));
        });
        report.put("flowActions", flow); report.put("actionCount", flow.size());
        report.put("recoveryActionCount", flow.contains("failed start attempt") ? 2 : 0);
        report.put("firstOriginRequests", firstRequests); report.put("secondOriginRequests", second.visits.size());
        report.put("additionalRequestsForProfileCompletionAndOriginal", first.visits.size() + second.visits.size() - totalRequests);
        check(Boolean.TRUE.equals(report.get("activeRunDoesNotRefreshProfiles")), "Enter/focus completion cannot change accounts during an active run");
        check(Boolean.TRUE.equals(report.get("noPerKeystrokeProfileLookup")), "typing the target performs no account refresh per keystroke");
        check(Integer.valueOf(0).equals(report.get("requestsBeforeExplicitStart")), "target input and account-list completion send no HTTP");
        check(Boolean.TRUE.equals(report.get("exactNewOriginalAction")), "the new target keeps its exact original action identity");
        check(Boolean.TRUE.equals(report.get("focusCompletionPreservesAvailableAccount")), "an unchanged available account is preserved on URL completion");
        check(Integer.valueOf(0).equals(report.get("additionalRequestsForProfileCompletionAndOriginal")), "profile completion and original display send no additional HTTP");
        if (!probe) {
          check(Boolean.TRUE.equals(report.get("targetCompletionUsesNewProfileList")), "Enter refreshes the profile list for the entered origin");
          check(Boolean.TRUE.equals(report.get("targetCompletionSelectsAnonymous")), "a disappearing account explicitly falls back to anonymous even if an authenticated account is first");
          check(Integer.valueOf(4).equals(report.get("actionCount")), "changing target and starting the selected account takes four explicit actions without recovery");
          check(Integer.valueOf(0).equals(report.get("recoveryActionCount")), "the target workflow needs no failed start or manual refresh");
          check(Boolean.TRUE.equals(report.get("focusCompletionRefreshesTarget")), "leaving the URL field refreshes accounts for a different origin");
          check(Boolean.TRUE.equals(report.get("missingAnonymousLeavesNoSelection")), "a target without anonymous leaves no authenticated account implicitly selected");
          check(Boolean.TRUE.equals(report.get("nullSelectionExplainsRequiredChoice")), "an unselected account renders an explicit choice prompt");
          check(Boolean.TRUE.equals(report.get("missingAccountStartIsBlocked")), "start still rejects an unselected account without HTTP");
          check(Boolean.TRUE.equals(report.get("malformedCompletionClearsOldAccount")), "malformed URL completion reports feedback and removes the old authenticated account");
          check(Boolean.TRUE.equals(report.get("malformedStartSendsNoHttp")), "the existing start validation rejects malformed targets without HTTP");
          check(Boolean.TRUE.equals(report.get("lookupFailureClearsOldAccount")), "a temporary account lookup failure leaves no stale authenticated selection");
          check(Boolean.TRUE.equals(report.get("completionRecoversAfterLookupFailure")), "correcting the target recovers the profile list with an explicit anonymous fallback");
        }
      } finally { edt(panel::close); }
    }
    if (probe) {
      Path output = Path.of(args[1]); Files.createDirectories(output.toAbsolutePath().getParent());
      Files.writeString(output, MetadataExport.json(report) + "\n");
    }
    System.out.println("TARGET_FLOW_SCENARIO " + MetadataExport.json(report));
    System.out.println("ScopeInspectionTargetFlowTest: " + checks + " checks passed");
  }
}
