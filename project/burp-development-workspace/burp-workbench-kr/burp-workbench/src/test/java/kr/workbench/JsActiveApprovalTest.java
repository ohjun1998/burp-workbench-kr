package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Active JS discovery needs fresh runtime consent; passive parsing remains request-free. */
public final class JsActiveApprovalTest extends WorkbenchTest {
  static HttpRequestResponse pair(HttpRequest q) {
    return proxy(HttpRequestResponse.class, (p,m,a) -> switch (m.getName()) {
      case "request" -> q; case "response" -> response(404, "{}"); default -> def(m);
    });
  }
  static long accepted(JsAnalysisPanel panel) throws Exception {
    return ((JsWorkQueue) get(panel, "jobs")).metrics().accepted();
  }
  public static void main(String[] args) throws Exception {
    var previousFactory = ObjectFactoryLocator.FACTORY;
    var previousPrompt = ActiveRequestApproval.prompt;
    AtomicInteger requests = new AtomicInteger(), prompts = new AtomicInteger();
    List<String> sent = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<ActiveRequestApproval.Plan> approved = new AtomicReference<>();
    var http = proxy(Http.class, (p,m,a) -> {
      if (!m.getName().equals("sendRequest")) return def(m);
      requests.incrementAndGet(); HttpRequest q = (HttpRequest)a[0]; sent.add(q.url()); return pair(q);
    });
    AtomicReference<String> project = new AtomicReference<>("11111111-1111-1111-1111-111111111111");
    var data = proxy(burp.api.montoya.persistence.PersistedObject.class, (p,m,a) -> {
      if (m.getName().equals("getString")) {
        if (WorkspaceResume.PROJECT_KEY.equals(a[0])) return project.get();
        throw new IllegalStateException("fixture has no disk archive");
      }
      return def(m);
    });
    var persistence = proxy(burp.api.montoya.persistence.Persistence.class, (p,m,a) -> m.getName().equals("extensionData") ? data : def(m));
    var api = proxy(MontoyaApi.class, (p,m,a) -> switch (m.getName()) {
      case "http" -> http; case "persistence" -> persistence; default -> def(m);
    });
    ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p,m,a) -> switch (m.getName()) {
      case "httpRequestFromUrl" -> request((String)a[0], "GET", ((String)a[0]).startsWith("https://allowed.test/"), List.of());
      case "requestOptions" -> proxy(RequestOptions.class, (pp,mm,aa) -> pp);
      default -> def(m);
    });
    AtomicReference<JsAnalysisPanel> ref = new AtomicReference<>();
    try {
      ActiveRequestApproval.prompt = (owner, plan) -> { prompts.incrementAndGet(); approved.set(plan); return false; };
      edt(() -> {
        var panel = new JsAnalysisPanel(api, () -> {}); ref.set(panel);
        panel.load("require('first-pkg');require('@org/second-pkg');fetch('/api/orders');//# sourceMappingURL=app.js.map", "https://allowed.test/assets/app.js?token=source-secret");
      });
      var panel = ref.get(); JsAnalysisPanelTest.waitDone(panel);
      check(requests.get() == 0 && prompts.get() == 0, "passive JS parsing starts neither target requests nor approval dialogs");
      long before = accepted(panel);
      edt(panel::discover); JsAnalysisPanelTest.waitDone(panel);
      check(prompts.get() == 1 && requests.get() == 0 && accepted(panel) == before,
          "denied source-map run queues no active job and emits zero requests");
      check(approved.get().requestPlan().contains("상한 10회") && approved.get().requestPlan().contains("5초")
          && approved.get().targets().size() == 2 && approved.get().targets().stream().allMatch(url -> url.startsWith("https://allowed.test/"))
          && !approved.get().toString().contains("source-secret"), "source-map consent shows bounded same-origin Scope plan without query secrets");
      edt(() -> { panel.registry.setText("https://registry.test"); panel.checkRegistry(); }); JsAnalysisPanelTest.waitDone(panel);
      check(prompts.get() == 2 && requests.get() == 0 && accepted(panel) == before,
          "denied NPM batch queues no active job and emits zero provider requests");
      check(approved.get().requestPlan().contains("상한 100회") && approved.get().requestPlan().contains("패키지 이름")
          && approved.get().targets().size() == 2 && approved.get().targets().stream().allMatch(url -> url.startsWith("https://registry.test/")),
          "registry confirmation identifies provider disclosure count and timeout");
      ActiveRequestApproval.prompt = (owner, plan) -> { prompts.incrementAndGet(); approved.set(plan); return true; };
      edt(panel::discover); JsAnalysisPanelTest.waitDone(panel);
      check(prompts.get() == 3 && requests.get() == 2 && sent.stream().allMatch(url -> url.startsWith("https://allowed.test/assets/")),
          "approved map run sends only its frozen requested candidate list");
      ActiveRequestApproval.prompt = (owner, plan) -> { prompts.incrementAndGet(); return false; };
      int completed = requests.get(); long completedJobs = accepted(panel);
      edt(panel::discover); JsAnalysisPanelTest.waitDone(panel);
      check(prompts.get() == 4 && requests.get() == completed && accepted(panel) == completedJobs,
          "repeating a source-map run asks again and cannot reuse prior approval");
      ActiveRequestApproval.prompt = (owner, plan) -> { prompts.incrementAndGet(); approved.set(plan); return true; };
      edt(panel::checkRegistry); JsAnalysisPanelTest.waitDone(panel);
      check(prompts.get() == 5 && requests.get() == completed + 2 && sent.stream().filter(url -> url.startsWith("https://registry.test/")).count() == 2,
          "approved NPM batch sends only approved registry and package snapshot");
      ActiveRequestApproval.prompt = (owner, plan) -> { prompts.incrementAndGet(); panel.registry.setText("https://other-registry.test"); return true; };
      completed = requests.get(); completedJobs = accepted(panel);
      edt(panel::checkRegistry); JsAnalysisPanelTest.waitDone(panel);
      check(prompts.get() == 6 && requests.get() == completed && accepted(panel) == completedJobs,
          "registry changed during confirmation invalidates approval before queueing");
      ActiveRequestApproval.prompt = (owner, plan) -> { prompts.incrementAndGet(); project.set("22222222-2222-2222-2222-222222222222"); return true; };
      completed = requests.get(); completedJobs = accepted(panel);
      edt(panel::discover); JsAnalysisPanelTest.waitDone(panel);
      check(prompts.get() == 7 && requests.get() == completed && accepted(panel) == completedJobs,
          "project changed during confirmation invalidates old source approval before queueing");
      project.set("11111111-1111-1111-1111-111111111111");
      ActiveRequestApproval.prompt = (owner, plan) -> { prompts.incrementAndGet(); panel.clear(); return true; };
      completed = requests.get();
      edt(panel::discover); JsAnalysisPanelTest.waitDone(panel);
      check(prompts.get() == 8 && requests.get() == completed,
          "source reset during confirmation cannot dispatch the old approved batch");
    } finally {
      if (ref.get() != null) edt(ref.get()::close);
      ActiveRequestApproval.prompt = previousPrompt; ObjectFactoryLocator.FACTORY = previousFactory;
    }
    System.out.println("JsActiveApprovalTest: " + checks + " checks passed");
  }
}
