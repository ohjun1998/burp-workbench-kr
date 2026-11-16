package kr.workbench;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Explicit per-run consent and bounded metadata review; no target or model traffic. */
public final class ActiveRequestApprovalTest extends WorkbenchTest {
  static final String SUMMARY = "요청 최대 12회 · 간격 250 ms · 동시 요청 1개 · 중지 시 후속 요청 취소";
  static final List<String> TARGETS = List.of("https://example.test/api/orders");

  public static void main(String[] args) throws Exception {
    var saved = ActiveRequestApproval.prompt;
    try {
      var owner = new JPanel();
      AtomicInteger decisions = new AtomicInteger();
      AtomicReference<ActiveRequestApproval.Plan> observed = new AtomicReference<>();
      ActiveRequestApproval.prompt = (parent, plan) -> {
        decisions.incrementAndGet(); observed.set(plan);
        check(SwingUtilities.isEventDispatchThread() && parent == owner, "worker approval prompt receives owner on EDT");
        return true;
      };
      check(ActiveRequestApproval.confirm(owner, "경로 후보 확인", TARGETS, SUMMARY), "explicit allow approves this run");
      var first = observed.get();
      check(first.operation().equals("경로 후보 확인") && first.requestPlan().equals(SUMMARY), "review captures concrete operation and request budget");
      check(ActiveRequestApproval.confirm(owner, "경로 후보 확인", TARGETS, SUMMARY)
          && decisions.get() == 2 && observed.get() != first, "identical repeated run creates fresh plan and fresh confirmation");

      ActiveRequestApproval.prompt = (parent, plan) -> false;
      check(!ActiveRequestApproval.confirm(owner, "경로 후보 확인", TARGETS, SUMMARY), "denial returns false after previous approval");
      try { ActiveRequestApproval.require(owner, "경로 후보 확인", TARGETS, SUMMARY); throw new AssertionError("denied require returned"); }
      catch (CancellationException expected) { check(true, "require converts denial into cancellation"); }
      ActiveRequestApproval.prompt = (parent, plan) -> true;
      ActiveRequestApproval.require(owner, "경로 후보 확인", TARGETS, SUMMARY);
      check(true, "require returns only after explicit approval");
      ActiveRequestApproval.prompt = (parent, plan) -> { throw new IllegalStateException("unavailable dialog"); };
      check(!ActiveRequestApproval.confirm(owner, "경로 후보 확인", TARGETS, SUMMARY), "prompt error fails closed");
      ActiveRequestApproval.prompt = null;
      check(!ActiveRequestApproval.confirm(owner, "경로 후보 확인", TARGETS, SUMMARY), "missing decision handler fails closed");

      ActiveRequestApproval.prompt = (parent, plan) -> {
        check(SwingUtilities.isEventDispatchThread(), "EDT caller is reviewed directly on EDT"); return true;
      };
      AtomicBoolean edtAllowed = new AtomicBoolean();
      edt(() -> edtAllowed.set(ActiveRequestApproval.confirm(owner, "경로 후보 확인", TARGETS, SUMMARY)));
      check(edtAllowed.get(), "EDT caller completes without invokeAndWait deadlock");

      var mutableTargets = new ArrayList<>(List.of("https://user:PRIVATE_PASSWORD@Example.test:8443/a%2Fb?token=PRIVATE_QUERY#PRIVATE_FRAGMENT"));
      var safe = new ActiveRequestApproval.Plan("조회", mutableTargets, SUMMARY);
      mutableTargets.set(0, "https://changed.test/");
      check(safe.targets().equals(List.of("https://example.test:8443/a%2Fb")), "safe URL strips credentials query and fragment without changing escaped path");
      try { safe.targets().add("https://changed.test/"); throw new AssertionError("mutable plan"); }
      catch (UnsupportedOperationException expected) { check(true, "plan owns an immutable target snapshot"); }
      check(!ActiveRequestApproval.reviewText(safe).contains("PRIVATE_"), "displayed review contains no removed URL credentials");
      check(new ActiveRequestApproval.Plan("조회", List.of("https://example.test", "http://[::1]:8080/a"), SUMMARY)
          .targets().equals(List.of("https://example.test/", "http://[::1]:8080/a")), "HTTP HTTPS empty paths and IPv6 are represented safely");
      check(new ActiveRequestApproval.Plan("<html>literal</html>", TARGETS, SUMMARY).operation().equals("<html>literal</html>"),
          "data-only text remains literal rather than rendered HTML");

      AtomicInteger invalidPrompts = new AtomicInteger();
      ActiveRequestApproval.prompt = (parent, plan) -> { invalidPrompts.incrementAndGet(); return true; };
      rejects(() -> ActiveRequestApproval.confirm(owner, " ", TARGETS, SUMMARY), "empty operation rejected before review");
      rejects(() -> ActiveRequestApproval.confirm(owner, "조회", TARGETS, ""), "empty request plan rejected before review");
      rejects(() -> ActiveRequestApproval.confirm(owner, "조회", null, SUMMARY), "null targets rejected before review");
      rejects(() -> ActiveRequestApproval.confirm(owner, "조회", List.of(), SUMMARY), "empty targets rejected before review");
      rejects(() -> ActiveRequestApproval.confirm(owner, "x".repeat(121), TARGETS, SUMMARY), "overlong operation rejected");
      rejects(() -> ActiveRequestApproval.confirm(owner, "조회", TARGETS, "x".repeat(4001)), "overlong request summary rejected");
      rejects(() -> new ActiveRequestApproval.Plan("조회", java.util.Collections.nCopies(513, "https://example.test/"), SUMMARY), "unbounded target count rejected");
      rejects(() -> new ActiveRequestApproval.Plan("조회", List.of("https://example.test/" + "x".repeat(2050)), SUMMARY), "overlong displayed URL rejected");
      rejects(() -> new ActiveRequestApproval.Plan("조회", List.of("https://example.test/?" + "x".repeat(8200)), SUMMARY), "oversized raw URL rejected before parsing");
      rejects(() -> new ActiveRequestApproval.Plan("조회", java.util.Collections.nCopies(100, "https://example.test/" + "x".repeat(1000)), SUMMARY), "total reviewed target text is bounded");
      for (String url : List.of("file:///etc/passwd", "javascript:alert(1)", "/relative", "https://", "https://example.test:99999/", "https://example.test:0/", "https://example.test/\nspoof"))
        rejects(() -> ActiveRequestApproval.confirm(owner, "조회", List.of(url), SUMMARY), "malformed or non-HTTP target fails closed");
      rejects(() -> ActiveRequestApproval.confirm(owner, "조회\n다른 작업", TARGETS, SUMMARY), "multiline operation rejected");
      rejects(() -> ActiveRequestApproval.confirm(owner, "조회\u202e", TARGETS, SUMMARY), "directional text spoofing rejected");
      rejects(() -> ActiveRequestApproval.confirm(owner, "조회", TARGETS, "GET /api HTTP/1.1\nHost: example.test"), "raw request packet excluded from confirmation metadata");
      rejects(() -> ActiveRequestApproval.confirm(owner, "조회", TARGETS, "Authorization: Bearer PRIVATE"), "authorization value excluded from confirmation metadata");
      rejects(() -> ActiveRequestApproval.confirm(owner, "조회", TARGETS, "cookie=session=PRIVATE"), "cookie value excluded from confirmation metadata");
      rejects(() -> ActiveRequestApproval.confirm(owner, "조회", TARGETS, "token=PRIVATE"), "generic token value excluded from confirmation metadata");
      rejects(() -> ActiveRequestApproval.confirm(owner, "조회", TARGETS, "{\"access_token\":\"PRIVATE\"}"), "quoted credential assignment excluded from confirmation metadata");
      check(invalidPrompts.get() == 0, "malformed plans never reach an approval handler");

      var pane = ActiveRequestApproval.optionPane(first);
      check(List.of(pane.getOptions()).equals(List.of("확인 후 실행", "취소")) && pane.getInitialValue().equals("취소"),
          "native confirmation presents explicit execution and defaults to cancel");
      check(!ActiveRequestApproval.approvedSelection(null)
          && !ActiveRequestApproval.approvedSelection(JOptionPane.UNINITIALIZED_VALUE)
          && !ActiveRequestApproval.approvedSelection(JOptionPane.CLOSED_OPTION)
          && !ActiveRequestApproval.approvedSelection("취소"), "closed dialog and every cancellation value deny execution");
      check(ActiveRequestApproval.approvedSelection("확인 후 실행"), "only explicit execution option permits execution");
      String review = ActiveRequestApproval.reviewText(first);
      check(review.contains("가용성") && review.contains("요청 수") && review.contains("간격")
          && review.contains("이번 실행만") && review.contains("되돌리지 못합니다"), "review explains availability budget interval and cancellation consequences");
      check(ActiveRequestApproval.RULE_VERSION.equals("active-request-approval-v1")
          && review.contains(ActiveRequestApproval.RULE_VERSION), "review identifies the exact approval rule version");
      if (java.awt.GraphicsEnvironment.isHeadless()) {
        ActiveRequestApproval.prompt = ActiveRequestApproval::defaultPrompt;
        check(!ActiveRequestApproval.confirm(owner, "조회", TARGETS, SUMMARY), "headless production default never autoapproves");
      }

      AtomicBoolean interruptedDenied = new AtomicBoolean(), interruptPreserved = new AtomicBoolean();
      AtomicInteger interruptedPrompts = new AtomicInteger();
      ActiveRequestApproval.prompt = (parent, plan) -> { interruptedPrompts.incrementAndGet(); return true; };
      Thread interrupted = new Thread(() -> {
        Thread.currentThread().interrupt();
        interruptedDenied.set(!ActiveRequestApproval.confirm(owner, "조회", TARGETS, SUMMARY));
        interruptPreserved.set(Thread.currentThread().isInterrupted());
      });
      interrupted.start(); interrupted.join(3000);
      check(!interrupted.isAlive() && interruptedDenied.get() && interruptPreserved.get() && interruptedPrompts.get() == 0,
          "interrupted worker fails closed without opening a prompt and preserves cancellation");

      CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
      AtomicReference<ActiveRequestApproval.Plan> snap = new AtomicReference<>();
      ActiveRequestApproval.prompt = (parent, plan) -> {
        snap.set(plan); reached.countDown();
        try { return release.await(3, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); return false; }
      };
      List<String> changing = new ArrayList<>(TARGETS);
      AtomicBoolean workerAllowed = new AtomicBoolean();
      Thread worker = new Thread(() -> workerAllowed.set(ActiveRequestApproval.confirm(owner, "조회", changing, SUMMARY)));
      worker.start();
      check(reached.await(3, TimeUnit.SECONDS), "worker approval arrives on event thread before mutation");
      changing.set(0, "https://changed.test/new"); release.countDown(); worker.join(3000);
      check(!worker.isAlive() && workerAllowed.get() && snap.get().targets().equals(TARGETS),
          "reviewed targets remain the original immutable snapshot during modal review");

      CountDownLatch waiting = new CountDownLatch(1), finishReview = new CountDownLatch(1);
      AtomicBoolean cancelledResult = new AtomicBoolean(true), cancelledInterrupt = new AtomicBoolean();
      ActiveRequestApproval.prompt = (parent, plan) -> {
        waiting.countDown();
        try { return finishReview.await(3, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); return false; }
      };
      Thread cancelledWorker = new Thread(() -> {
        cancelledResult.set(ActiveRequestApproval.confirm(owner, "조회", TARGETS, SUMMARY));
        cancelledInterrupt.set(Thread.currentThread().isInterrupted());
      });
      cancelledWorker.start();
      check(waiting.await(3, TimeUnit.SECONDS), "cancellable worker reaches pending manual review");
      cancelledWorker.interrupt(); cancelledWorker.join(3000); finishReview.countDown(); edt(() -> {});
      check(!cancelledWorker.isAlive() && !cancelledResult.get() && cancelledInterrupt.get(),
          "worker interruption during review cannot be approved by a late dialog response");
    } finally { ActiveRequestApproval.prompt = saved; }
    System.out.println("ActiveRequestApprovalTest: " + checks + " checks passed");
  }
}
