package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Public UI boundaries require per-run consent; isolated HTTP adapters never contact a target. */
public final class ActiveBatchApprovalTest extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    BountyFeaturesTest.factory();
    AtomicInteger sends = new AtomicInteger(), confirmations = new AtomicInteger();
    List<String> sentUrls = new CopyOnWriteArrayList<>();
    var http = proxy(Http.class, (p, m, a) -> {
      if (!m.getName().equals("sendRequest")) return def(m);
      HttpRequest request = (HttpRequest) a[0];
      sends.incrementAndGet(); sentUrls.add(request.url());
      return AuthRecordTest.rr(request, BountyFeaturesTest.rs(200, "{\"ok\":true}",
          List.of(header("Content-Type", "application/json"))));
    });
    MontoyaApi api = proxy(MontoyaApi.class, (p, m, a) -> m.getName().equals("http") ? http : def(m));
    WorkbenchScope.install(api).add("https://approval.test/", true);
    var previous = ActiveRequestApproval.prompt;
    AtomicReference<ActiveRequestApproval.Plan> approved = new AtomicReference<>();
    AtomicReference<EnvironmentPanel> environment = new AtomicReference<>();
    AtomicReference<PassiveScanPanel> passive = new AtomicReference<>();
    AtomicReference<BountySupportPanel> support = new AtomicReference<>();
    AtomicReference<AuthComparePanel> auth = new AtomicReference<>();
    AtomicReference<ActivityPanel> activity = new AtomicReference<>();
    try {
      edt(() -> {
        environment.set(new EnvironmentPanel(api)); passive.set(new PassiveScanPanel(api));
        auth.set(new AuthComparePanel(api, List::of)); activity.set(new ActivityPanel(api));
        support.set(new BountySupportPanel(api, List::of, auth.get(), activity.get()));
      });
      var env = environment.get(); var urls = passive.get(); var bounty = support.get();
      ActiveRequestApproval.prompt = (owner, plan) -> { confirmations.incrementAndGet(); approved.set(plan); return false; };
      edt(() -> env.runUrls(List.of("https://approval.test/env-a")));
      check(sends.get() == 0 && env.job == null && env.scan == null,
          "denied environment scan sends no baseline/identity/target requests and creates no worker");
      check(approved.get().requestPlan().contains("전체 한도") && approved.get().requestPlan().contains("wb-missing")
          && approved.get().requestPlan().contains("요청 간격"), "environment approval describes generated requests and limits");

      edt(() -> { env.compareMissing.setSelected(false); env.follow.setSelected(false); env.delay.setValue(0); env.budget.setValue(2); });
      ActiveRequestApproval.prompt = (owner, plan) -> {
        confirmations.incrementAndGet(); approved.set(plan);
        env.budget.setValue(1); env.delay.setValue(10000); // modal re-entry cannot change approved options
        return true;
      };
      edt(() -> env.runUrls(List.of("https://approval.test/env-a", "https://approval.test/env-b")));
      EnvironmentBatchTest.idle(env);
      check(sends.get() == 2 && sentUrls.containsAll(List.of("https://approval.test/env-a", "https://approval.test/env-b")),
          "approved environment run uses frozen two-request budget/delay, not dialog-time edits");
      int count = confirmations.get();
      ActiveRequestApproval.prompt = (owner, plan) -> { confirmations.incrementAndGet(); approved.set(plan); return false; };
      edt(() -> { env.delay.setValue(0); env.runUrls(List.of("https://approval.test/env-changed")); });
      check(confirmations.get() == count + 1 && sends.get() == 2
          && approved.get().targets().contains("https://approval.test/env-changed"),
          "a changed environment run requests new approval and denial sends nothing");

      var remaining = List.of(new EnvironmentProject.Pending("https://approval.test/resume", 0));
      var options = new EnvironmentScan.Options(false, false, Set.of(), 0, 1000, 3, 0, false, true, "", List.of());
      var profile = new EnvironmentScan.Profile("anonymous", Map.of(), null);
      var paused = new EnvironmentScan(api, options, profile, remaining, r -> {}, () -> false);
      paused.pause("fixture pause", 0);
      edt(() -> { env.scan = paused; env.togglePause(); });
      check(paused.paused && sends.get() == 2 && approved.get().operation().contains("재개"),
          "denied in-place environment resume stays paused without sending");
      ActiveRequestApproval.prompt = (owner, plan) -> { confirmations.incrementAndGet(); return true; };
      edt(env::togglePause); paused.run();
      check(!paused.paused && sends.get() == 3, "approved resume permits only the remaining bounded request");
      edt(() -> env.scan = null);

      ActiveRequestApproval.prompt = (owner, plan) -> { confirmations.incrementAndGet(); approved.set(plan); return false; };
      edt(() -> { urls.allowDangerous.setSelected(true); urls.verify(List.of("https://approval.test/delete/1")); });
      check(sends.get() == 3 && urls.job == null && approved.get().targets().contains("https://approval.test/delete/1"),
          "even a single dangerous GET is denied before passive verification worker starts");
      check(approved.get().requestPlan().contains("200ms") && approved.get().requestPlan().contains("10초")
          && approved.get().requestPlan().contains("HTTP429"), "URL verification approval states actual pacing and stop rules");
      ActiveRequestApproval.prompt = (owner, plan) -> { confirmations.incrementAndGet(); approved.set(plan); return true; };
      edt(() -> {
        urls.add("https://approval.test/url-a", "가져오기"); urls.add("https://approval.test/url-b", "가져오기");
        urls.verify(List.of("https://approval.test/url-a", "https://approval.test/url-b"));
      });
      passiveIdle(urls);
      check(sends.get() == 5 && urls.packets.size() == 2, "approved URL batch sends exactly the selected GET requests");
      count = confirmations.get();
      ActiveRequestApproval.prompt = (owner, plan) -> { confirmations.incrementAndGet(); return false; };
      edt(() -> urls.verify(List.of("https://approval.test/url-next")));
      check(confirmations.get() == count + 1 && sends.get() == 5 && urls.job == null,
          "URL verification permission does not carry into the next batch");
      count=confirmations.get();
      var oversized=java.util.stream.IntStream.range(0,513).mapToObj(i->"https://approval.test/limit/"+i).toList();
      edt(() -> urls.verify(oversized));
      check(confirmations.get()==count&&sends.get()==5&&urls.job==null&&urls.status.getText().contains("나누어"),
          "oversized approval plans fail before prompting or creating a worker and explain how to split them");

      edt(() -> {
        bounty.flowOrigin.setText("https://approval.test");
        bounty.workflow.setText(raw("/flow-a") + "\n===STEP===\n" + raw("/flow-b"));
        bounty.flowBudget.setValue(2); bounty.flowModel.addRow(new Object[] {0, "GET", "old", 200, "kept"});
        bounty.runFlow();
      });
      check(!bounty.running && sends.get() == 5 && bounty.flowModel.getRowCount() == 1,
          "denied workflow starts no worker and preserves the previous result");
      ActiveRequestApproval.prompt = (owner, plan) -> {
        confirmations.incrementAndGet(); approved.set(plan);
        bounty.workflow.setText(raw("/flow-changed")); bounty.flowBudget.setValue(1); return true;
      };
      edt(bounty::runFlow); BountyFeaturesTest.idle(bounty);
      check(sends.get() == 7 && sentUrls.containsAll(List.of("https://approval.test/flow-a", "https://approval.test/flow-b"))
          && !sentUrls.contains("https://approval.test/flow-changed"), "approved workflow executes the captured steps despite dialog-time edits");
      check(approved.get().requestPlan().contains("0ms") && approved.get().requestPlan().contains("실제 데이터를 바꿀")
          && approved.get().requestPlan().contains("후속 단계를 중단"), "workflow approval includes mutation and stop behavior");
      count = confirmations.get();
      ActiveRequestApproval.prompt = (owner, plan) -> { confirmations.incrementAndGet(); approved.set(plan); return false; };
      edt(bounty::runFlow);
      check(confirmations.get() == count + 1 && sends.get() == 7
          && approved.get().targets().contains("https://approval.test/flow-changed"), "changed workflow requires new approval");
    } finally {
      ActiveRequestApproval.prompt = previous;
      edt(() -> {
        if (support.get() != null) support.get().close();
        if (auth.get() != null) auth.get().close();
        if (activity.get() != null) activity.get().close();
        if (environment.get() != null) environment.get().close();
        if (passive.get() != null) passive.get().close();
      });
      WorkbenchScope.release(api);
    }
    System.out.println("ActiveBatchApprovalTest: " + checks + " checks passed");
  }
  private static String raw(String path) { return "GET " + path + " HTTP/1.1\r\nHost: approval.test\r\n\r\n"; }
  private static void passiveIdle(PassiveScanPanel panel) throws Exception {
    for (int i = 0; i < 500; i++) { edt(() -> {}); if (panel.job == null) return; Thread.sleep(10); }
    throw new AssertionError("URL verification worker timed out");
  }
}
