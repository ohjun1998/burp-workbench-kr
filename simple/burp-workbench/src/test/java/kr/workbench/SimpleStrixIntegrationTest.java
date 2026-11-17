package kr.workbench;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Actual panel with an injected runner. No process, target, authentication or model calls. */
public final class SimpleStrixIntegrationTest extends WorkbenchTest {
  static final Path DIRECTORY = Path.of("/synthetic/strix/run-example");
  static final Path REPORT = DIRECTORY.resolve("report.md");
  static final class FakeRunner implements SimpleStrixPanel.Runner {
    final AtomicInteger inspections = new AtomicInteger(), launches = new AtomicInteger(),
        cancellations = new AtomicInteger(), closes = new AtomicInteger(), checkedReports = new AtomicInteger();
    final List<SimpleStrixClient.Plan> plans = new CopyOnWriteArrayList<>();
    final CountDownLatch release = new CountDownLatch(1);
    volatile Consumer<SimpleStrixClient.Snapshot> listener;
    volatile boolean active, hold, failSubscription, subscriptionSignedIn;
    volatile List<Path> reportPaths = List.of(REPORT);
    public SimpleStrixClient.ProviderInspection inspectProvider(SimpleStrixClient.Provider provider, String model) {
      inspections.incrementAndGet();
      boolean local = provider == SimpleStrixClient.Provider.HALOGEN_LOCAL;
      return new SimpleStrixClient.ProviderInspection(provider, local || subscriptionSignedIn,
          new SimpleStrixClient.ModelInfo(local ? "halogen-qwen3.8-flash-next" : model,
              local ? "http://127.0.0.1:8731/v1" : "", local ? "chat_completions" : "native-chatgpt"),
          local ? "합성 로컬 설정 · 연결 검사 없음" : subscriptionSignedIn
              ? "합성 Strix 구독 로그인 상태" : "합성 미로그인 · strix auth login chatgpt");
    }
    public SimpleStrixClient.Plan plan(String executable, String target, String mode, int turns,
        int seconds, String context, SimpleStrixClient.Provider provider, String model) throws IOException {
      if (failSubscription && provider == SimpleStrixClient.Provider.CHATGPT_SUBSCRIPTION)
        throw new IOException("합성 구독 오류 · 자동 전환 없음");
      var plan = new SimpleStrixClient.Plan(Path.of(executable), URI.create(target), mode, turns,
          seconds, context, provider, provider == SimpleStrixClient.Provider.HALOGEN_LOCAL
              ? "halogen-qwen3.8-flash-next" : model);
      plans.add(plan); return plan;
    }
    public SimpleStrixClient.Result execute(SimpleStrixClient.Plan plan, BooleanSupplier approval,
        Consumer<SimpleStrixClient.Snapshot> listener) throws IOException {
      this.listener = listener;
      if (!approval.getAsBoolean()) return new SimpleStrixClient.Result("cancelled", -1, null,
          List.of(), "", 0, false);
      active = true; launches.incrementAndGet();
      try {
        listener.accept(new SimpleStrixClient.Snapshot("running", "[합성 예시] 실제 Strix·모델·대상 호출 없음\n진행 로그", 200));
        if (hold && !release.await(8, TimeUnit.SECONDS)) throw new IOException("합성 Runner 대기 초과");
        return new SimpleStrixClient.Result("completed", 0, DIRECTORY, reportPaths,
            "[합성 예시] 실제 Strix·모델·대상 호출 없음\n가설·추가 검증 필요 · 실제 보고서 파일 없음", 350, false);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt(); throw new IOException("합성 실행 중지");
      } finally { active = false; }
    }
    public Path checkedReport(Path path) throws IOException {
      checkedReports.incrementAndGet();
      if (!REPORT.equals(path)) throw new IOException("합성 검사: 이번 실행 밖 보고서 차단");
      return path;
    }
    public String previewReport(Path path) throws IOException {
      checkedReport(path); return "# 합성 보고서\n실제 취약점 검증 결과가 아닙니다.";
    }
    public void cancel() { cancellations.incrementAndGet(); release.countDown(); }
    public boolean busy() { return active; }
    public void close() { closes.incrementAndGet(); release.countDown(); }
  }
  static SimpleStrixPanel.Source source(String id, String path) {
    return new SimpleStrixPanel.Source(id, "GET", "https://allowed.test/" + path,
        "Authorization: Bearer synthetic-secret\n관측 메타데이터 · 합성 예시");
  }
  static void await(BooleanSupplier condition, String name) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
    while (System.nanoTime() < deadline) {
      AtomicBoolean ready = new AtomicBoolean(); edt(() -> ready.set(condition.getAsBoolean()));
      if (ready.get()) return;
      Thread.sleep(10);
    }
    throw new AssertionError("Strix UI wait: " + name);
  }
  static SimpleStrixPanel panel(FakeRunner runner, AtomicBoolean inScope, AtomicBoolean external) throws Exception {
    AtomicReference<SimpleStrixPanel> value = new AtomicReference<>();
    edt(() -> {
      value.set(new SimpleStrixPanel(runner,
          url -> inScope.get() && url.startsWith("https://allowed.test/"), external::get));
      // These existing run/provider/report checks deliberately exercise the retained headless path.
      value.get().interactive.setSelected(false);
    });
    return value.get();
  }
  static void prepare(SimpleStrixPanel panel) throws Exception {
    edt(() -> panel.prepare(List.of(source("source-a", "orders/123")), "project-a", () -> {}));
  }
  static void idle(SimpleStrixPanel panel) throws Exception { await(() -> !panel.busy(), "worker idle"); }

  public static void main(String[] args) throws Exception {
    check(GraphicsEnvironment.isHeadless(), "this adapter test never opens a native approval window");
    FakeRunner first = new FakeRunner(); SimpleStrixPanel p = panel(first, new AtomicBoolean(true), new AtomicBoolean());
    try {
      var selected = new ArrayList<>(List.of(source("source-a", "orders/123"), source("source-b", "profile")));
      edt(() -> {
        p.prepare(selected, "project-a", () -> {}); selected.clear();
        check(first.plans.isEmpty() && first.inspections.get() == 0 && first.launches.get() == 0,
            "preparing originals performs no runner, provider or process action");
        check(p.preview.getText().contains("source-a") && p.preview.getText().contains("source-b"),
            "selection IDs survive changes to the caller's list");
        check(p.target.getText().equals("https://allowed.test/"), "selected path prepares the explicit origin without silently extending Scope");
        check(!p.preview.getText().contains("synthetic-secret"), "source metadata does not expose the synthetic credential");
        check(p.sourceInfo.getText().contains("인증값·본문 자동 전달 없음"), "authentication and raw bodies are explicitly excluded from automatic transfer");
        check(!p.subscriptionModel.isEnabled() && !p.openReport.isEnabled(), "unselected subscription model and absent report remain disabled");
        p.start();
      }); idle(p);
      check(first.launches.get() == 0, "default headless confirmation cancels without an approved launch");
      edt(() -> {
        p.provider.setSelectedItem(SimpleStrixClient.Provider.CHATGPT_SUBSCRIPTION);
        p.subscriptionModel.setText("chatgpt/test-model"); p.context.setText("비공개 주문은 소유자만 조회 · 인증값 없음");
        p.approval = (owner, plan) -> true; p.start();
      }); idle(p);
      var last = first.plans.get(first.plans.size() - 1);
      check(first.launches.get() == 1 && last.provider() == SimpleStrixClient.Provider.CHATGPT_SUBSCRIPTION
              && last.model().equals("chatgpt/test-model"), "new execution uses the fresh selected provider/model in one immutable plan");
      check(last.context().contains("source-a") && last.context().contains("source-b"), "execution retains the prepared originals rather than a later traffic selection");
      edt(() -> {
        check(p.reports.getText().contains(DIRECTORY.toString()) && p.reports.getText().contains("미확인"),
            "report location is per run and unverified container cleanup is visible");
        AtomicReference<Path> opened = new AtomicReference<>(); p.reportOpener = opened::set; p.openReport.doClick(0);
        check(REPORT.equals(opened.get()) && first.checkedReports.get() == 1,
            "report action validates the owned report before invoking a text-preview opener");
      });
    } finally { edt(p::close); }

    for (String change : List.of("scope", "project", "other-job", "provider", "target")) {
      FakeRunner runner = new FakeRunner(); AtomicBoolean allowed = new AtomicBoolean(true), external = new AtomicBoolean();
      SimpleStrixPanel view = panel(runner, allowed, external); AtomicBoolean current = new AtomicBoolean(true);
      try {
        edt(() -> {
          view.prepare(List.of(source("kept-id", "profile")), "project-a", () -> {
            if (!current.get()) throw new IllegalArgumentException("원본·프로젝트 변경");
          });
          view.approval = (owner, plan) -> {
            switch (change) {
              case "scope" -> allowed.set(false);
              case "project" -> current.set(false);
              case "other-job" -> external.set(true);
              case "provider" -> view.provider.setSelectedItem(SimpleStrixClient.Provider.CHATGPT_SUBSCRIPTION);
              case "target" -> view.target.setText("https://allowed.test/changed");
            }
            return true;
          };
          view.start();
        }); idle(view);
        check(runner.launches.get() == 0, "approval is discarded after a concurrent " + change + " change");
      } finally { edt(view::close); }
    }

    FakeRunner rejected = new FakeRunner(); rejected.failSubscription = true;
    SimpleStrixPanel rejectView = panel(rejected, new AtomicBoolean(true), new AtomicBoolean());
    try {
      prepare(rejectView); edt(() -> {
        rejectView.provider.setSelectedItem(SimpleStrixClient.Provider.CHATGPT_SUBSCRIPTION);
        rejectView.approval = (owner, plan) -> true; rejectView.start();
      }); idle(rejectView);
      check(rejected.launches.get() == 0 && rejected.plans.isEmpty(), "subscription plan failure does not retry through the local provider");
      edt(() -> check(rejectView.status.getText().contains("자동 전환 없음"), "provider failure remains a visible failure"));
    } finally { edt(rejectView::close); }

    FakeRunner held = new FakeRunner(); held.hold = true; AtomicBoolean current = new AtomicBoolean(true);
    SimpleStrixPanel active = panel(held, new AtomicBoolean(true), new AtomicBoolean());
    try {
      edt(() -> {
        active.prepare(List.of(source("held-id", "orders/123")), "project-a", () -> {}, () -> {
          if (!current.get()) throw new IllegalArgumentException("프로젝트 변경");
        }); active.approval = (owner, plan) -> true; active.start();
      }); await(() -> held.launches.get() == 1, "approved fake run");
      edt(() -> {
        check(!active.start.isEnabled() && active.stop.isEnabled() && !active.provider.isEnabled(),
            "running state disables a second launch and keeps Stop adjacent and available");
        rejects(active::start, "programmatic duplicate start is also rejected");
      });
      held.listener.accept(new SimpleStrixClient.Snapshot("running", "x".repeat(SimpleStrixPanel.LOG_LIMIT + 400), 400));
      await(() -> active.log.getText().contains("앞선 로그 생략"), "bounded log tail");
      edt(() -> check(active.log.getText().length() < SimpleStrixPanel.LOG_LIMIT + 100, "visible runner log remains bounded"));
      edt(() -> { current.set(false); active.verifyRunCurrent(); }); idle(active);
      check(held.cancellations.get() > 0, "a changed running project requests cancellation");
      edt(() -> {
        active.reset("프로젝트 전환"); held.listener.accept(new SimpleStrixClient.Snapshot("late", "late-secret-result", 500));
      }); edt(() -> {});
      edt(() -> check(active.log.getText().isEmpty() && active.reports.getText().isEmpty() && !active.start.isEnabled(),
          "reset clears originals/reports and ignores stale log callbacks"));
      edt(() -> { active.close(); active.close(); held.listener.accept(new SimpleStrixClient.Snapshot("late", "after-close", 600)); }); edt(() -> {});
      edt(() -> check(held.closes.get() == 1 && !active.start.isEnabled() && active.log.getText().isEmpty(),
          "close is idempotent, owns runner cleanup and rejects later callbacks"));
    } finally { edt(active::close); }

    FakeRunner badReport = new FakeRunner(); badReport.reportPaths = List.of(Path.of("/outside/untrusted.html"));
    SimpleStrixPanel reportView = panel(badReport, new AtomicBoolean(true), new AtomicBoolean());
    try {
      prepare(reportView); edt(() -> { reportView.approval = (owner, plan) -> true; reportView.start(); }); idle(reportView);
      AtomicBoolean opened = new AtomicBoolean(); edt(() -> { reportView.reportOpener = path -> opened.set(true); reportView.openReport.doClick(0); });
      check(!opened.get(), "an unowned report cannot reach a browser or text-preview opener");
    } finally { edt(reportView::close); }
    System.out.println("SimpleStrixIntegrationTest: " + checks + " checks passed (actual UI, injected runner, no process/model/target)");
  }
}
