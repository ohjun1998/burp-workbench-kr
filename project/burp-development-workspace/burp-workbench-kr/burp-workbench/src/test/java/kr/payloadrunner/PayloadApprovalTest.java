package kr.payloadrunner;

import burp.*;
import java.awt.Component;
import java.lang.reflect.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import javax.swing.*;
import kr.workbench.ActiveRequestApproval;

/** Production runner approval dispatch against a synthetic Burp callback; no target network. */
public final class PayloadApprovalTest {
  private static int checks;
  private static void check(boolean value, String reason) {
    if (!value) throw new AssertionError(reason);
    checks++;
  }
  private static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
  private static Object def(Method method) {
    Class<?> type = method.getReturnType();
    if (type == boolean.class) return false;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == byte.class) return (byte) 0;
    return null;
  }
  private static <T> T proxy(Class<T> type, InvocationHandler action) {
    return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
        (p, m, a) -> {
          if (m.getName().equals("equals")) return p == a[0];
          if (m.getName().equals("hashCode")) return System.identityHashCode(p);
          return action.invoke(p, m, a == null ? new Object[0] : a);
        }));
  }
  private static void waitIdle(PayloadRunnerPanel runner) throws Exception {
    long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while (runner.running && System.nanoTime() < end) { Thread.sleep(10); edt(() -> {}); }
    edt(() -> {});
    check(!runner.running, "synthetic approved run completed within bounded wait");
  }
  private static void configure(PayloadRunnerPanel runner) {
    runner.profile.setSelectedItem(Engine.Profile.CUSTOM);
    runner.mode.setSelectedIndex(1);
    runner.host.setText("127.0.0.1"); runner.port.setValue(19001);
    runner.request.setText("GET /check?q=§PAYLOAD§ HTTP/1.1\r\nHost: app.test\r\nAuthorization: Bearer NEVER_SHOW_SECRET\r\n\r\n");
    runner.base.setText("normal"); runner.payloads.setText("probe"); runner.interval.setValue(0);
    runner.budget.setValue(2); runner.sessionControl.setSelected(false);
  }
  public static void main(String[] args) throws Exception {
    AtomicInteger requests = new AtomicInteger();
    List<Integer> actualPorts = Collections.synchronizedList(new ArrayList<>());
    IExtensionHelpers helpers = proxy(IExtensionHelpers.class, (p, m, a) -> {
      if (m.getName().equals("buildHttpService")) {
        String host = (String) a[0]; int port = (Integer) a[1]; boolean tls = (Boolean) a[2];
        return proxy(IHttpService.class, (sp, sm, sa) -> switch (sm.getName()) {
          case "getHost" -> host; case "getPort" -> port; case "getProtocol" -> tls ? "https" : "http"; default -> def(sm);
        });
      }
      if (m.getName().equals("analyzeRequest")) {
        IHttpService service = (IHttpService) a[0];
        String first = new String((byte[]) a[1], StandardCharsets.ISO_8859_1).split("\\r\\n", 2)[0];
        URL url = new URL(service.getProtocol(), service.getHost(), service.getPort(), first.split(" ", 3)[1]);
        return proxy(IRequestInfo.class, (rp, rm, ra) -> rm.getName().equals("getUrl") ? url : def(rm));
      }
      return def(m);
    });
    IBurpExtenderCallbacks callbacks = proxy(IBurpExtenderCallbacks.class, (p, m, a) -> {
      if (m.getName().equals("getHelpers")) return helpers;
      if (m.getName().equals("isInScope")) return true;
      if (m.getName().equals("makeHttpRequest")) {
        requests.incrementAndGet(); actualPorts.add(((IHttpService) a[0]).getPort());
        byte[] response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 8\r\n\r\nbaseline".getBytes(StandardCharsets.US_ASCII);
        return proxy(IHttpRequestResponse.class, (rp, rm, ra) -> rm.getName().equals("getResponse") ? response : def(rm));
      }
      return def(m);
    });
    PayloadRunnerPanel runner = new PayloadRunnerPanel(); runner.initializeEmbedded(callbacks, () -> {});
    Field prompt = ActiveRequestApproval.class.getDeclaredField("prompt"); prompt.setAccessible(true);
    Object previous = prompt.get(null);
    List<ActiveRequestApproval.Plan> plans = new ArrayList<>();
    try {
      prompt.set(null, (BiPredicate<Component, ActiveRequestApproval.Plan>) (owner, plan) -> { plans.add(plan); return false; });
      edt(() -> { configure(runner); runner.start.doClick(0); });
      check(requests.get() == 0 && !runner.running && runner.model.getRowCount() == 0,
          "declined sequential fuzz run sends zero baseline/control/payload requests");
      check(plans.size() == 1 && plans.get(0).targets().equals(List.of("http://127.0.0.1:19001/check")),
          "confirmation shows the selected target while query values are hidden");
      String description = plans.get(0).requestPlan();
      check(description.contains("사용자 정의") && description.contains("§PAYLOAD§ 1곳") && description.contains("요청 줄")
          && description.contains("총 요청 한도: 2회") && description.contains("요청 간격: 0ms")
          && description.contains("병렬 요청: 1개") && description.contains("데이터 생성·변경"),
          "confirmation describes profile insertion position budget rate concurrency and side effects");
      check(!description.contains("NEVER_SHOW_SECRET"), "approval summary does not expose original authentication");
      for (Engine.Profile profile : Engine.Profile.values()) {
        int before = plans.size();
        edt(() -> { runner.profile.setSelectedItem(profile); runner.mode.setSelectedIndex(0); runner.start.doClick(0); });
        check(plans.size() == before + 1 && requests.get() == 0 && !runner.running,
            "every adaptive profile requires confirmation before any engine request: " + profile);
      }
      edt(() -> { configure(runner); runner.sessionControl.setSelected(true); runner.identityValue.setText("alice"); runner.start.doClick(0); });
      check(requests.get() == 0 && plans.get(plans.size() - 1).requestPlan().contains("신원 대조 GET: 사용"),
          "declined run prevents even mandatory authenticated and anonymous identity controls");
      check(plans.get(plans.size() - 1).targets().contains("http://127.0.0.1:19001/api/me"),
          "identity control path appears in the same per-run target confirmation");
      prompt.set(null, (BiPredicate<Component, ActiveRequestApproval.Plan>) (owner, plan) -> { plans.add(plan); return true; });
      int approved = plans.size();
      edt(() -> { configure(runner); runner.start.doClick(0); }); waitIdle(runner);
      check(plans.size() == approved + 1 && requests.get() == 2 && runner.model.getRowCount() == 2,
          "approved immutable plan sends exactly the bounded baseline and selected payload");
      int repeated = plans.size();
      edt(() -> runner.start.doClick(0)); waitIdle(runner);
      check(plans.size() == repeated + 1 && requests.get() == 4,
          "same configuration requires a fresh confirmation on every new run");
      edt(() -> { runner.port.setValue(19002); runner.budget.setValue(1); runner.interval.setValue(300); runner.start.doClick(0); }); waitIdle(runner);
      var changed = plans.get(plans.size() - 1);
      check(changed.targets().equals(List.of("http://127.0.0.1:19002/check")) && changed.requestPlan().contains("총 요청 한도: 1회")
          && changed.requestPlan().contains("요청 간격: 300ms") && requests.get() == 5 && actualPorts.get(actualPorts.size() - 1) == 19002,
          "target budget and rate changes are confirmed and the approved snapshot executes");
      prompt.set(null, (BiPredicate<Component, ActiveRequestApproval.Plan>) (owner, plan) -> {
        plans.add(plan); runner.budget.setValue(3); return true;
      });
      int beforeChangedDialog = requests.get();
      edt(() -> { runner.budget.setValue(2); runner.start.doClick(0); });
      check(requests.get() == beforeChangedDialog && !runner.running && runner.status.getText().contains("변경"),
          "configuration changed during confirmation invalidates approval and sends zero requests");
      prompt.set(null, (BiPredicate<Component, ActiveRequestApproval.Plan>) (owner, plan) -> {
        plans.add(plan); runner.host.setText("changed.test"); return true;
      });
      edt(() -> runner.start.doClick(0));
      check(requests.get() == beforeChangedDialog && !runner.running,
          "target changed during confirmation requires a new plan approval");
      prompt.set(null, (BiPredicate<Component, ActiveRequestApproval.Plan>) (owner, plan) -> {
        plans.add(plan); runner.startScan(); return false;
      });
      int nested = plans.size(); edt(() -> runner.start.doClick(0));
      check(plans.size() == nested + 1 && requests.get() == beforeChangedDialog,
          "reentrant start during modal confirmation cannot dispatch another run");
    } finally { prompt.set(null, previous); runner.extensionUnloaded(); edt(() -> {}); }
    System.out.println("PayloadApprovalTest: " + checks + " checks passed");
  }
}
