package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.scope.Scope;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class ResponseAnalysisLimitsTest extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    AtomicBoolean enabled = new AtomicBoolean(true);
    var scope = proxy(Scope.class, (p,m,a) -> m.getName().equals("isInScope") ? enabled.get() && a[0].toString().startsWith("https://test/") : def(m));
    var api = proxy(MontoyaApi.class, (p,m,a) -> m.getName().equals("scope") ? scope : def(m));
    AtomicReference<ResponseReconPanel> box = new AtomicReference<>();
    edt(() -> box.set(new ResponseReconPanel(api)));
    var panel = box.get();
    try {
      var large = response(200, "x".repeat(2 * 1024 * 1024 + 1));
      panel.offer(request("https://outside.test/a", "GET", false, List.of()), large);
      check(panel.oversized.get() == 0, "scope exclusion does not retain outside URL metadata");
      panel.offer(request("https://test/export?token=PRIVATE_TEST_VALUE", "GET", true, List.of()), large);
      CountDownLatch ready = new CountDownLatch(1); panel.submit(ready::countDown);
      check(ready.await(10, TimeUnit.SECONDS), "oversized metadata completes through bounded observer queue");
      edt(() -> {});
      check(panel.oversized.get() == 1 && panel.findings.getRowCount() == 1, "oversized response produces an explicit unanalyzed finding");
      check(panel.findings.getValueAt(0,3).toString().contains("본문 미검사"), "limit finding does not imply a safe response");
      check(!panel.issues.toString().contains("PRIVATE_TEST_VALUE") && panel.findingPackets.isEmpty(), "oversized finding stores no query values or raw packet");
      check(panel.status.getToolTipText().contains("큐 제외 0") && panel.status.getToolTipText().contains("2 MiB"), "queue exclusions and byte/character budgets are individually visible");
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      panel.submit(() -> { entered.countDown(); try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
      check(entered.await(10, TimeUnit.SECONDS), "observer is paused before scope change");
      panel.offer(request("https://test/revoked", "GET", true, List.of()), large);
      enabled.set(false); release.countDown();
      CountDownLatch drained = new CountDownLatch(1); panel.submit(drained::countDown);
      check(drained.await(10, TimeUnit.SECONDS), "scope-change queue drains"); edt(() -> {});
      check(panel.findings.getRowCount() == 1 && panel.issues.values().stream().noneMatch(f -> f.url().contains("revoked")), "oversized metadata rechecks current scope before publication");
    } finally { edt(panel::close); }
    System.out.println("ResponseAnalysisLimitsTest: " + checks + " checks passed");
  }
}
