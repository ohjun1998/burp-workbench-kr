package kr.workbench;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Production panel and queues; synthetic native-session transport, no target/model/auth operations. */
public final class SimpleStrixChatPanelTest extends WorkbenchTest {
  private static final SimpleStrixChatClient.Agent ROOT = new SimpleStrixChatClient.Agent("root-1", "Main", null, "running");
  private static final SimpleStrixChatClient.Agent CHILD = new SimpleStrixChatClient.Agent("child-1", "Access review", "root-1", "running");
  private record Sent(long generation, String agentId, String message) {}
  private static SimpleStrixChatClient.Snapshot snapshot(List<SimpleStrixChatClient.Message> messages) {
    // Root deliberately comes second; root selection must not depend on array order.
    return new SimpleStrixChatClient.Snapshot(7, true, "합성 세션 연결", "fixture-run", List.of(CHILD, ROOT), messages);
  }
  private static final class FakeRunner implements SimpleStrixPanel.Runner {
    final AtomicInteger starts = new AtomicInteger(), sends = new AtomicInteger(), closes = new AtomicInteger(), polls = new AtomicInteger();
    final List<Sent> delivered = new CopyOnWriteArrayList<>();
    final CountDownLatch releaseRun = new CountDownLatch(1);
    volatile CountDownLatch releasePoll, pollEntered, releaseSend, sendEntered;
    volatile boolean active, failSend;
    volatile SimpleStrixChatClient.Snapshot current = snapshot(List.of(
        new SimpleStrixChatClient.Message("m0", "root-1", "assistant", "합성 원본 결과: 추가 확인 필요", ""),
        new SimpleStrixChatClient.Message("c0", "child-1", "assistant", "합성 하위 에이전트 결과", "")));
    public SimpleStrixClient.ProviderInspection inspectProvider(SimpleStrixClient.Provider provider, String model) {
      return new SimpleStrixClient.ProviderInspection(provider, true, null, "합성 설정");
    }
    public SimpleStrixClient.Plan plan(String executable, String target, String mode, int turns, int seconds,
        String context, SimpleStrixClient.Provider provider, String model) throws IOException {
      return new SimpleStrixClient.Plan(Path.of(executable), URI.create(target), mode, turns, seconds,
          context, provider, "fixture-model");
    }
    public SimpleStrixClient.Result execute(SimpleStrixClient.Plan plan, BooleanSupplier approval,
        Consumer<SimpleStrixClient.Snapshot> listener) { throw new AssertionError("chat tests must use native interactive path"); }
    public SimpleStrixClient.Result executeInteractive(SimpleStrixClient.Plan plan, BooleanSupplier approval,
        Consumer<SimpleStrixClient.Snapshot> listener) throws IOException {
      if (!approval.getAsBoolean()) return new SimpleStrixClient.Result("cancelled", -1, null, List.of(), "", 0, false);
      starts.incrementAndGet(); active = true;
      try {
        if (!releaseRun.await(60, TimeUnit.SECONDS)) throw new IOException("fixture run timed out");
        return new SimpleStrixClient.Result("completed", 0, null, List.of(), "", 1, false);
      } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("fixture interrupted"); }
      finally { active = false; }
    }
    public SimpleStrixChatClient.Snapshot chatSnapshot() throws IOException {
      polls.incrementAndGet(); var captured = current;
      CountDownLatch entered = pollEntered, release = releasePoll;
      if (entered != null) entered.countDown();
      awaitLatch(release); return captured;
    }
    public void sendChat(long generation, String agentId, String message) throws IOException {
      sends.incrementAndGet();
      CountDownLatch entered = sendEntered, release = releaseSend;
      if (entered != null) entered.countDown();
      awaitLatch(release);
      if (failSend) throw new IOException("합성 전달 실패");
      if (generation != current.generation()) throw new IOException("합성 이전 세션 차단");
      delivered.add(new Sent(generation, agentId, message));
    }
    private static void awaitLatch(CountDownLatch latch) throws IOException {
      if (latch == null) return;
      try { if (!latch.await(8, TimeUnit.SECONDS)) throw new IOException("fixture latch timed out"); }
      catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("fixture interrupted"); }
    }
    public boolean busy() { return active; }
    public void cancel() { releaseRun.countDown(); }
    public void close() { closes.incrementAndGet(); releaseRun.countDown(); }
  }
  private static final class Fixture implements AutoCloseable {
    final FakeRunner runner = new FakeRunner();
    final AtomicBoolean scope = new AtomicBoolean(true), current = new AtomicBoolean(true), otherBusy = new AtomicBoolean();
    final AtomicInteger validations = new AtomicInteger();
    final SimpleStrixPanel panel;
    Fixture() throws Exception {
      AtomicReference<SimpleStrixPanel> value = new AtomicReference<>();
      edt(() -> {
        var p = new SimpleStrixPanel(runner, url -> scope.get() && url.startsWith("https://allowed.test/"), otherBusy::get);
        p.prepare(List.of(new SimpleStrixPanel.Source("original-a", "GET", "https://allowed.test/account/123",
            "Authorization: Bearer source-secret\n관찰된 상태: 200")), "project-a", () -> {}, () -> {
              validations.incrementAndGet();
              if (!current.get()) throw new IllegalArgumentException("원본·프로젝트 변경");
            });
        p.approval = (owner, plan) -> true; value.set(p);
      });
      panel = value.get();
    }
    void start() throws Exception {
      edt(panel::start); await(() -> runner.active, "live interactive run");
      edt(() -> panel.chatTimer.stop()); poll();
      await(() -> panel.sendChat.isEnabled(), "connected chat");
    }
    void poll() throws Exception {
      await(() -> !privateBoolean(panel, "chatPolling"), "previous poll callback finished");
      edt(() -> {
        try { Method method = SimpleStrixPanel.class.getDeclaredMethod("pollChat"); method.setAccessible(true); method.invoke(panel); }
        catch (Exception error) { throw new IllegalStateException(error); }
      });
    }
    public void close() throws Exception {
      if (runner.releasePoll != null) runner.releasePoll.countDown();
      if (runner.releaseSend != null) runner.releaseSend.countDown();
      edt(panel::close); await(() -> !panel.busy(), "closed worker");
    }
  }
  private static boolean privateBoolean(SimpleStrixPanel panel, String name) {
    try { return Boolean.TRUE.equals(get(panel, name)); }
    catch (Exception error) { throw new IllegalStateException(error); }
  }
  private static void await(BooleanSupplier condition, String description) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
    while (System.nanoTime() < deadline) {
      AtomicBoolean result = new AtomicBoolean(); edt(() -> result.set(condition.getAsBoolean()));
      if (result.get()) return;
      Thread.sleep(10);
    }
    throw new AssertionError("chat panel wait: " + description);
  }
  private static void latch(CountDownLatch latch, String description) throws Exception {
    check(latch.await(4, TimeUnit.SECONDS), description);
  }
  public static void main(String[] args) throws Exception {
    check(GraphicsEnvironment.isHeadless(), "adapter chat tests never show an actual approval or contact a target");
    try (Fixture f = new Fixture()) {
      edt(() -> {
        check(f.panel.interactive.isSelected(), "new runs offer chat while retaining an explicit ordinary-run option");
        check(!f.panel.sendChat.isEnabled() && f.runner.polls.get() == 0 && f.runner.starts.get() == 0,
            "preparing sources performs no native-session connection or process start");
      });
      f.start();
      edt(() -> {
        check(((SimpleStrixChatClient.Agent) f.panel.chatAgent.getSelectedItem()).id().equals("root-1"), "root agent is selected independently of native array order");
        check(f.panel.chatHistory.getText().contains("합성 원본 결과") && !f.panel.chatHistory.getText().contains("하위 에이전트 결과"),
            "conversation displays the selected agent's committed history");
        f.panel.chatDraft.setText("이 결과의 근거는?"); f.panel.sendChat();
        check(f.runner.sends.get() == 0 && f.panel.chatDraft.getText().contains("근거"), "default-cancel question confirmation delivers zero messages and retains the draft");
      });
      AtomicReference<String> confirmed = new AtomicReference<>();
      edt(() -> {
        f.panel.chatApproval = (owner, body) -> { confirmed.set(body); return true; };
        f.panel.chatDraft.setText("오탐 검토\nAuthorization: Bearer typed-secret");
        f.panel.attachSources.setSelected(true); f.panel.sendChat();
      });
      await(() -> f.runner.delivered.size() == 1 && f.panel.sendChat.isEnabled(), "acknowledged delivery");
      Sent delivered = f.runner.delivered.get(0);
      check(delivered.generation() == 7 && delivered.agentId().equals("root-1"), "question is sent to the frozen native session and chosen agent");
      check(delivered.message().contains("original-a") && delivered.message().contains("account/123") && !delivered.message().contains("source-secret")
          && !delivered.message().contains("typed-secret"), "optional attachment keeps source identity and redacts credentials before transfer");
      check(confirmed.get().contains(delivered.message()) && confirmed.get().contains("추가 검증 요청·도구"), "confirmation shows the exact redacted payload and possible native tool execution");
      edt(() -> {
        check(f.panel.chatDraft.getText().isEmpty(), "accepted native delivery clears only the submitted draft");
        check(!f.panel.chatHistory.getText().contains("오탐 검토"), "delivery acknowledgment does not fabricate a committed user turn or assistant answer");
        f.panel.chatAgent.setSelectedItem(CHILD);
        check(f.panel.chatHistory.getText().contains("하위 에이전트 결과") && !f.panel.chatHistory.getText().contains("원본 결과"), "agent switch isolates the displayed conversation");
      });
      f.poll(); await(() -> f.panel.sendChat.isEnabled(), "selection refresh");
      edt(() -> check(((SimpleStrixChatClient.Agent) f.panel.chatAgent.getSelectedItem()).id().equals("child-1"), "polling retains a manually selected subagent"));
      f.runner.current = snapshot(List.of(new SimpleStrixChatClient.Message("c1", "child-1", "assistant", "<script>literal</script>\nCookie: hidden-chat-cookie", "")));
      f.poll(); await(() -> f.panel.chatHistory.getText().contains("literal"), "native response refresh");
      edt(() -> check(f.panel.chatHistory.getText().contains("<script>literal</script>") && !f.panel.chatHistory.getText().contains("hidden-chat-cookie"),
          "native answer renders as literal text and redacts credential lines"));
      check(f.validations.get() >= 2, "question validates current source/project both around confirmation and before native dispatch");
    }

    try (Fixture f = new Fixture()) {
      f.start(); AtomicInteger approvals = new AtomicInteger();
      edt(() -> {
        f.panel.chatApproval = (owner, body) -> { approvals.incrementAndGet(); return true; };
        f.panel.chatDraft.setText(" "); rejects(f.panel::sendChat, "blank question is blocked before confirmation");
        f.panel.chatDraft.setText("x".repeat(4001)); rejects(f.panel::sendChat, "overlong question is blocked before confirmation");
        f.panel.chatDraft.setText("x".repeat(3950)); f.panel.attachSources.setSelected(true);
        rejects(f.panel::sendChat, "question limit includes the full optional metadata attachment");
        check(approvals.get() == 0 && f.runner.sends.get() == 0, "invalid payloads neither request approval nor reach the native agent");
        f.panel.attachSources.setSelected(false); f.panel.chatDraft.setText("😀".repeat(4000)); f.panel.sendChat();
      });
      await(() -> f.runner.delivered.size() == 1 && f.panel.sendChat.isEnabled(), "unicode boundary delivery");
      check(f.runner.delivered.get(0).message().codePointCount(0, f.runner.delivered.get(0).message().length()) == 4000,
          "4000 Unicode characters are accepted without counting surrogate pairs twice");
      f.runner.failSend = true;
      edt(() -> { f.panel.chatDraft.setText("재현 조건을 설명해줘"); f.panel.sendChat(); });
      await(() -> f.runner.sends.get() == 2 && f.panel.sendChat.isEnabled(), "failed native delivery");
      edt(() -> check(f.panel.chatDraft.getText().equals("재현 조건을 설명해줘") && f.panel.chatDelivery.getText().contains("수신 확인 실패"),
          "transport failure preserves the draft and persistent delivery error"));
      check(f.runner.delivered.size() == 1, "failed transport does not claim an accepted native message");
    }

    for (String changed : List.of("scope", "project", "other-job")) {
      try (Fixture f = new Fixture()) {
        f.start();
        edt(() -> {
          f.panel.chatDraft.setText("확인해줘");
          f.panel.chatApproval = (owner, body) -> {
            switch (changed) { case "scope" -> f.scope.set(false); case "project" -> f.current.set(false); default -> f.otherBusy.set(true); }
            return true;
          };
          rejects(f.panel::sendChat, "question is blocked when " + changed + " changes during confirmation");
        });
        check(f.runner.sends.get() == 0, "changed " + changed + " reaches no native dispatch");
      }
    }

    try (Fixture f = new Fixture()) {
      f.start(); f.runner.releaseSend = new CountDownLatch(1); f.runner.sendEntered = new CountDownLatch(1);
      edt(() -> { f.panel.chatApproval = (owner, body) -> true; f.panel.chatDraft.setText("진행 중 질문"); f.panel.sendChat(); });
      latch(f.runner.sendEntered, "native send is held while panel remains responsive");
      edt(() -> {
        rejects(f.panel::sendChat, "duplicate send is blocked during an in-flight native request");
        f.panel.chatDraft.setText("다음 질문 초안");
      });
      f.runner.releaseSend.countDown();
      await(() -> f.runner.delivered.size() == 1 && f.panel.sendChat.isEnabled(), "held send acknowledged");
      edt(() -> check(f.panel.chatDraft.getText().equals("다음 질문 초안"), "acknowledgment cannot erase a newer user draft"));
    }

    try (Fixture f = new Fixture()) {
      f.start(); f.runner.releasePoll = new CountDownLatch(1); f.runner.pollEntered = new CountDownLatch(1);
      f.runner.current = snapshot(List.of(new SimpleStrixChatClient.Message("late", "root-1", "assistant", "late-session-text", "")));
      f.poll(); latch(f.runner.pollEntered, "native snapshot held across project reset");
      edt(() -> f.panel.reset("프로젝트 전환")); f.runner.releasePoll.countDown();
      await(() -> !f.panel.busy() && f.panel.chatWorker.getActiveCount() == 0, "reset workers settled");
      edt(() -> check(f.panel.chatHistory.getText().isEmpty() && f.panel.chatAgent.getItemCount() == 0 && !f.panel.sendChat.isEnabled(),
          "late native snapshot cannot repopulate a reset project's conversation"));
      edt(() -> { f.panel.close(); f.panel.close(); });
      check(f.runner.closes.get() == 1, "close owns chat/run cleanup exactly once");
    }
    try (Fixture f = new Fixture()) {
      f.start(); f.runner.releasePoll = new CountDownLatch(1); f.runner.pollEntered = new CountDownLatch(1);
      f.runner.current = snapshot(List.of(new SimpleStrixChatClient.Message("late-finish", "root-1", "assistant", "late-after-normal-completion", "")));
      f.poll(); latch(f.runner.pollEntered, "native snapshot held across ordinary run completion");
      f.runner.releaseRun.countDown();
      await(() -> !f.panel.running && f.panel.chatStatus.getText().contains("세션 종료"), "ordinary run completed before poll returned");
      f.runner.releasePoll.countDown();
      await(() -> !privateBoolean(f.panel, "chatPolling") && f.panel.chatWorker.getActiveCount() == 0, "late completion snapshot settled");
      edt(() -> check(!f.panel.chatHistory.getText().contains("late-after-normal-completion")
          && f.panel.chatStatus.getText().contains("세션 종료") && !f.panel.sendChat.isEnabled(),
          "late native snapshot cannot revive chat or overwrite a normally completed session's status"));
    }
    System.out.println("SimpleStrixChatPanelTest: " + checks + " checks passed (production panel, synthetic native session, no target/model/auth)");
  }
}
