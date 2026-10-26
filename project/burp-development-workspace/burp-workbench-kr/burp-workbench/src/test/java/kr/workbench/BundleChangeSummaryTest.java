package kr.workbench;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Setup diffs, cancellation, stale review rejection, and explicit account reconnection. */
public final class BundleChangeSummaryTest extends WorkbenchTest {
  static AuthEffectRules.Rule effect(String name, String path, String expected) {
    return new AuthEffectRules.Rule(name, "https://example.test", new AuthEffectCheck.Config(true, "PATCH", path,
        "https://example.test" + path, "$.value", expected, AuthEffectCheck.Mode.EQUALS, 1));
  }
  static AuthSetupBundles.Settings settings(boolean changed) {
    return new AuthSetupBundles.Settings(changed ? "$.owner2" : "$.owner", changed ? "updatedAt" : "timestamp", "$.value",
        changed ? "$.error=DENIED" : "$.error=FORBIDDEN", "BODY: denied",
        changed ? "GET /same OWNER_ONLY\nGET /added PUBLIC" : "GET /same PUBLIC\nGET /removed PUBLIC",
        changed ? AuthPolicy.Access.PUBLIC : AuthPolicy.Access.UNSPECIFIED,
        changed ? "GET /same ALLOW DENY UNKNOWN UNKNOWN" : "GET /same ALLOW ALLOW UNKNOWN UNKNOWN", changed,
        changed ? List.of(effect("same", "/same", "2"), effect("new", "/added", "1"))
            : List.of(effect("same", "/same", "1"), effect("old", "/removed", "1")));
  }
  static AuthSetupBundles.Bundle bundle(boolean changed) {
    return new AuthSetupBundles.Bundle("권한 설정", "https://example.test", "PATCH", "/same", changed ? 2 : 1,
        AuthSetupBundles.ROLE_PLACEHOLDERS, settings(changed));
  }
  public static void main(String[] args) throws Exception {
    var delta = BundleChangeSummary.changes(Map.of("same", "old", "gone", "1"), Map.of("same", "new", "added", "1"));
    check(delta.added().equals(List.of("added")) && delta.changed().equals(List.of("same"))
        && delta.removed().equals(List.of("gone")) && delta.total() == 3, "diff distinguishes rule creation, changes and removal");
    check(BundleChangeSummary.changes(Map.of("a", "1", "b", "2"), Map.of("b", "2", "a", "1")).total() == 0,
        "map order does not create a false configuration change");
    var before = bundle(false); var after = bundle(true);
    String summary = BundleChangeSummary.compare(before, after);
    check(summary.contains("권한 설정 · r2") && summary.contains("https://example.test:443") && summary.contains("PATCH /same"),
        "summary identifies incoming version and exact target");
    check(summary.contains("경로 정책: 추가 1 / 변경 1 / 제거 1"), "policy diff uses method and path identities");
    check(summary.contains("계정별 권한표: 추가 0 / 변경 1 / 제거 0"), "matrix role permission change is visible");
    check(summary.contains("쓰기 결과 조회: 추가 1 / 변경 1 / 제거 1"), "same-path expected-value change is counted as a change");
    check(summary.contains("JSON 차단: 추가 0 / 변경 1 / 제거 0"), "JSON denial value changes are paired by field");
    check(summary.contains("기본 비교 설정 변경 4개") && summary.contains("배열 순서 무시") && summary.contains("기본 접근 정책"),
        "base comparison settings list what will change");
    check(summary.contains("인증 후보는 유지") && summary.contains("쓰기 허용은 해제") && summary.contains("신원/갱신"),
        "summary explains reconnect requirements instead of implying identity restoration");
    check(BundleChangeSummary.compare(null, after).contains("변경 수를 계산하지 않았습니다"),
        "unavailable current draft is distinguished from zero changes");
    check(BundleChangeSummary.describe(after).contains("쓰기 결과 조회 2개"), "standalone preview describes actual included settings");

    edt(() -> {
      AtomicReference<AuthSetupBundles.Bundle> current = new AtomicReference<>(before);
      AtomicInteger applies = new AtomicInteger(), reconnects = new AtomicInteger();
      var panel = new AuthSetupBundlePanel(null, current::get, ignored -> applies.incrementAndGet());
      panel.onReconnect = reconnects::incrementAndGet;
      panel.bundles.merge(List.of(after)); panel.refresh(after.key());
      check(panel.details.getText().contains("포함된 설정") && !panel.showJson.isSelected(), "list preview begins with readable summary");
      panel.showJson.doClick();
      check(panel.details.getText().contains("workbench-auth-setup-1"), "JSON remains available behind explicit expansion");
      panel.showJson.doClick();
      panel.review = (title, values) -> false; panel.applySelected();
      check(applies.get() == 0 && !panel.reconnect.isVisible(), "cancelling review preserves active settings and reconnect state");
      panel.review = (title, values) -> { current.set(after); return true; };
      rejects(panel::applySelected, "changing active settings during review requires a new review");
      check(applies.get() == 0, "stale review cannot apply the bundle");
      current.set(before); panel.review = (title, values) -> true; panel.applySelected();
      check(applies.get() == 1 && panel.reconnect.isVisible() && reconnects.get() == 0,
          "accepted settings application exposes explicit reconnection without navigating or executing automatically");
      check(panel.details.getText().contains("경로 정책: 추가 1 / 변경 1 / 제거 1"), "applied review retains the before-and-after summary");
      panel.reconnect.doClick();
      check(reconnects.get() == 1 && applies.get() == 1, "reconnection button invokes only the account editor callback");
      panel.close();
      var invalidDraft = new AuthSetupBundlePanel(null, () -> { throw new IllegalArgumentException("invalid draft"); }, ignored -> applies.incrementAndGet());
      invalidDraft.bundles.merge(List.of(after)); invalidDraft.refresh(after.key()); invalidDraft.review = (title, values) -> true;
      invalidDraft.applySelected();
      check(applies.get() == 2, "a valid bundle can replace an invalid draft after explicit review");
      invalidDraft.close();
    });
    System.out.println("BundleChangeSummaryTest: " + checks + " checks passed");
  }
}
