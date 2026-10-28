package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** Passive editing, constant diagnostics and true comparison cancellation; no live targets. */
public final class AuthInlineValidationTest extends WorkbenchTest {
  static Component named(Component component, String name) {
    if (name.equals(component.getName())) return component;
    if (component instanceof Container parent) for (Component child : parent.getComponents()) {
      Component found = named(child, name); if (found != null) return found;
    }
    return null;
  }
  static String text(AuthFieldValidation.Group group, javax.swing.text.JTextComponent field) { return group.message(field).getText(); }

  public static void main(String[] args) throws Exception {
    BountyFeaturesTest.factory();
    check(AuthFieldValidation.httpUrl("https://example.test/api/me").isEmpty(), "ordinary HTTPS GET URL passes passive format check");
    check(!AuthFieldValidation.httpUrl("javascript:alert(1)").isEmpty(), "non HTTP schemes have an inline URL example");
    check(!AuthFieldValidation.httpUrl("https://user:PRIVATE_VALUE@example.test/").contains("PRIVATE_VALUE"), "URL diagnostic never echoes embedded credentials");
    check(!AuthFieldValidation.httpUrl("https://example.test/#fragment").isEmpty(), "fragment URL is diagnosed before apply");
    check(AuthFieldValidation.jsonPath("", false, 256).isEmpty(), "optional JSON path can remain blank");
    check(!AuthFieldValidation.jsonPath("data.ownerId", true, 256).isEmpty(), "required JSON path includes rooted path example");
    check(AuthFieldValidation.jsonPath("$.data.ownerId", true, 256).isEmpty(), "supported JSON path passes");
    check(AuthFieldValidation.policyRows("GET /api/items PUBLIC\nBROKEN PRIVATE_VALUE", false).startsWith("2행"), "policy syntax diagnostic identifies precise row");
    check(!AuthFieldValidation.policyRows("GET /api/items PUBLIC\nBROKEN PRIVATE_VALUE", false).contains("PRIVATE_VALUE"), "policy diagnostic does not copy input values");
    check(AuthFieldValidation.responseRules("BODY_REGEX: [PRIVATE_VALUE").contains("정규식"), "malformed denial regex has a local syntax diagnostic");
    check(!AuthFieldValidation.responseRules("BODY_REGEX: [PRIVATE_VALUE").contains("PRIVATE_VALUE"), "regex error excludes pattern content");

    AtomicInteger sends = new AtomicInteger(); CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    var reply = response(200, "{\"ownerId\":1}");
    var http = proxy(Http.class, (p, m, a) -> {
      if (!m.getName().equals("sendRequest")) return def(m);
      sends.incrementAndGet(); started.countDown();
      if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
      return AuthRecordTest.rr((HttpRequest) a[0], reply);
    });
    var api = proxy(MontoyaApi.class, (p, m, a) -> m.getName().equals("http") ? http : def(m));
    var scope = WorkbenchScope.install(api); scope.add("https://example.test/", true);
    HttpRequest write = BountyFeaturesTest.rq("https://example.test/items/1", "PATCH", "{}", List.of(header("Cookie", "PRIVATE_VALUE")));
    HttpRequest read = BountyFeaturesTest.rq("https://example.test/items/1", "GET", "", write.headers());
    AtomicReference<List<HttpRequestResponse>> selected = new AtomicReference<>(List.of(AuthRecordTest.rr(write, reply)));
    AtomicReference<AuthComparePanel> holder = new AtomicReference<>(); BackgroundJobs jobs = new BackgroundJobs();
    edt(() -> {
      AuthComparePanel panel = new AuthComparePanel(api, selected::get); holder.set(panel); panel.backgroundJobs = jobs;
      panel.observe(read, reply); panel.assign(0, panel.credentials.values().iterator().next());
    });
    AuthComparePanel panel = holder.get();
    try {
      edt(() -> {
        JPanel settings = panel.settingsContent();
        JButton confirm = (JButton) named(settings, "wb.auth.settings.confirm");
        check(confirm != null && confirm.isEnabled(), "valid default comparison settings expose an enabled confirmation");
        panel.responseDenialRules.setText("BODY_REGEX: [PRIVATE_VALUE");
        check(!confirm.isEnabled() && text(panel.comparisonValidation, panel.responseDenialRules).contains("정규식"), "malformed regex disables confirmation and explains the affected field");
        check(!text(panel.comparisonValidation, panel.responseDenialRules).contains("PRIVATE_VALUE"), "field-local validation never echoes malformed pattern");
        panel.responseDenialRules.setText("BODY_REGEX: (?i)denied");
        check(confirm.isEnabled(), "correcting regex immediately enables confirmation without applying or sending");
        panel.denialRules.setText("BROKEN PRIVATE_VALUE");
        check(!confirm.isEnabled() && text(panel.comparisonValidation, panel.denialRules).contains("경로=값"), "malformed JSON denial is diagnosed only beside denial input");
        check(text(panel.comparisonValidation, panel.ignoredFields).isBlank() && text(panel.comparisonValidation, panel.importantFields).isBlank()
            && text(panel.comparisonValidation, panel.ownerPath).isBlank() && panel.comparisonRuleConflict.getText().isBlank(),
            "one malformed field does not light unrelated valid inputs or cross-field conflicts");
        panel.denialRules.setText("error=FORBIDDEN"); panel.ownerPath.setText("$.ownerId"); panel.ignoredFields.setText("$.ownerId");
        check(!confirm.isEnabled() && panel.comparisonRuleConflict.getText().contains("함께 지정"), "valid individual grammars expose a separate cross-field exclusion conflict");
        check(text(panel.comparisonValidation, panel.ownerPath).isBlank() && text(panel.comparisonValidation, panel.ignoredFields).isBlank(),
            "cross-field conflict does not mislabel individual path grammar");
        panel.ignoredFields.setText(""); panel.ownerPath.setText("");
        AuthFieldValidation.MessageLabel wrapping = new AuthFieldValidation.MessageLabel();
        JPanel narrow = new JPanel(); narrow.setLayout(new BoxLayout(narrow, BoxLayout.Y_AXIS)); narrow.setSize(280, 200); narrow.add(wrapping);
        wrapping.message("계정·대상과 같은 Origin의 HTTP(S) URL을 입력하고 등록된 SCOPE 포함 여부를 확인하세요. 예: https://example.test/api/me");
        check(wrapping.getPreferredSize().width <= 280 && wrapping.getPreferredSize().height > wrapping.getFontMetrics(wrapping.getFont()).getHeight(),
            "important validation reasons wrap to a narrow parent instead of overflowing into a tooltip " + wrapping.getPreferredSize());
        panel.policyRules.setText("GET /items/1 PUBLIC\nBROKEN PRIVATE_VALUE");
        check(!confirm.isEnabled() && text(panel.comparisonValidation, panel.policyRules).contains("2행"), "invalid policy row gives a row number and blocks confirmation");
        panel.policyRules.setText("GET /items/1 PUBLIC");
        int listeners = ((javax.swing.text.AbstractDocument) panel.policyRules.getDocument()).getDocumentListeners().length;
        int watched = panel.comparisonValidation.entries.size();
        panel.settingsContent();
        check(((javax.swing.text.AbstractDocument) panel.policyRules.getDocument()).getDocumentListeners().length == listeners && panel.comparisonValidation.entries.size() == watched,
            "reopening settings does not accumulate passive document listeners");
        check(panel.comparisonValidation.buttons.size() == 1, "reopened settings replace old button bindings");

        AuthEffectPanel effect = panel.effectSettings;
        effect.connectSelected(true); effect.url.setText("https://example.test/items/1"); effect.jsonPath.setText("$.ownerId");
        effect.refreshValidation();
        check(effect.saveRuleButton.isEnabled(), "valid connected write lookup can be saved explicitly");
        effect.url.setText("https://other.test/items/1");
        check(!effect.saveRuleButton.isEnabled() && text(effect.validation, effect.url).contains("Origin"), "foreign lookup Origin is shown beside URL before save");
        effect.url.setText("https://user:PRIVATE_VALUE@example.test/items/1");
        check(!text(effect.validation, effect.url).contains("PRIVATE_VALUE"), "lookup URL errors do not leak credential values");
        effect.url.setText("https://example.test/items/1"); effect.jsonPath.setText("ownerId");
        check(!effect.saveRuleButton.isEnabled() && text(effect.validation, effect.jsonPath).contains("$.data.ownerId"), "unrooted result path provides a field-local example");
        effect.jsonPath.setText("$.ownerId"); effect.mode.setSelectedIndex(AuthEffectCheck.Mode.DELTA_ABOVE.ordinal()); effect.expected.setText("PRIVATE_VALUE");
        check(!effect.saveRuleButton.isEnabled() && text(effect.validation, effect.expected).contains("숫자"), "numeric change boundary validates before apply");
        effect.expected.setText("1"); check(effect.saveRuleButton.isEnabled(), "valid numeric boundary restores save action");
        effect.mode.setSelectedIndex(AuthEffectCheck.Mode.ABSENT.ordinal()); effect.jsonPath.setText("");
        check(effect.saveRuleButton.isEnabled(), "whole resource absence keeps optional blank path semantics");
        effect.mode.setSelectedIndex(AuthEffectCheck.Mode.CHANGED.ordinal()); effect.jsonPath.setText("$.ownerId");
        scope.remove(scope.rules().get(0)); effect.refreshValidation();
        check(!effect.saveRuleButton.isEnabled() && text(effect.validation, effect.url).contains("SCOPE"), "current Scope revocation is rechecked without requesting URL");
        scope.add("https://example.test/", true); effect.refreshValidation();
        check(effect.saveRuleButton.isEnabled(), "restored Scope enables local rule save without applying it");

        JPanel session = panel.sessionSettingsContent(); JButton sessionConfirm = (JButton) named(session, "wb.auth.session.confirm");
        panel.refreshEnabled[0].setSelected(true); panel.refreshUrls[0].setText("https://other.test/me"); panel.refreshInlineValidation();
        check(!sessionConfirm.isEnabled() && text(panel.sessionValidation, panel.refreshUrls[0]).contains("Origin"), "session refresh Origin mismatch is inline and blocks confirmation");
        panel.refreshUrls[0].setText("https://example.test/me"); panel.refreshTokenPaths[0].setText("token");
        check(!sessionConfirm.isEnabled() && text(panel.sessionValidation, panel.refreshTokenPaths[0]).contains("$.data.ownerId"), "session extraction path explains rooted JSON syntax");
        panel.refreshTokenPaths[0].setText("$.token");
        check(sessionConfirm.isEnabled(), "valid session configuration can be confirmed explicitly");
        panel.refreshTokenHeaders[0].setText("Host");
        check(!sessionConfirm.isEnabled() && text(panel.sessionValidation, panel.refreshTokenHeaders[0]).contains("헤더"), "transport header cannot be used as token output header");
        panel.refreshTokenHeaders[0].setText("authorization"); panel.refreshEnabled[0].setSelected(false); panel.refreshInlineValidation();
        panel.matrixRules.setText("GET /items/1 ALLOW UNKNOWN UNKNOWN UNKNOWN");
        panel.matrixEditor.model.setValueAt("INVALID", 0, 0);
        check(panel.matrixEditor.feedback.getText().contains("1행") && !sessionConfirm.isEnabled(), "policy table identifies invalid row and disables confirmation without discarding draft");
        check(panel.matrixEditor.model.getValueAt(0, 0).equals("INVALID"), "invalid matrix draft remains editable");
        panel.matrixEditor.model.setValueAt("GET", 0, 0);
        check(sessionConfirm.isEnabled(), "corrected matrix draft enables confirmation");
        panel.active.incrementAndGet(); panel.refreshInlineValidation();
        check(!sessionConfirm.isEnabled() && panel.sessionApplyReason.getText().contains("진행 중"), "busy action explains why confirmation is unavailable");
        panel.active.decrementAndGet(); panel.refreshInlineValidation();
        check(sends.get() == 0, "all validation, Scope checks and draft correction send zero HTTP requests");
        check(panel.currentChecklistAccountCount() == 1, "first-use readiness counts explicit account assignment");
        panel.resetChecklistProject(); check(panel.currentChecklistAccountCount() == 0, "project switch does not credit prior project account assignment");
        panel.assign(0, panel.slots[0]); check(panel.currentChecklistAccountCount() == 1, "explicit same-account reconnection confirms new project readiness");
        selected.set(List.of(AuthRecordTest.rr(read, reply))); panel.compareSelection();
      });
      check(started.await(2, TimeUnit.SECONDS), "comparison worker reaches controlled HTTP fixture");
      edt(() -> {
        check(jobs.snapshot().size() == 1 && jobs.snapshot().get(0).state() == BackgroundJobs.State.RUNNING, "one admitted comparison creates one bounded batch job");
        panel.stop();
        check(jobs.snapshot().get(0).state() == BackgroundJobs.State.CANCEL_REQUESTED, "Stop remains pending while an issued HTTP request is blocked");
        check(!jobs.snapshot().toString().contains("PRIVATE_VALUE") && !jobs.snapshot().toString().contains("example.test"), "job metadata contains neither packet values nor target URLs");
      });
      release.countDown();
      for (int i = 0; i < 100 && panel.active.get() > 0; i++) { Thread.sleep(20); edt(() -> {}); }
      edt(() -> check(panel.active.get() == 0 && jobs.snapshot().get(0).state() == BackgroundJobs.State.CANCELLED,
          "comparison job becomes cancelled only after actual worker termination"));
      edt(() -> {
        panel.comparisonWindow.opener = (parent, title, content, modal, closed) -> null;
        String expectedId = panel.runs.get(0).executionId;
        int before = sends.get();
        check(jobs.openResult(jobs.snapshot().get(0).id()) && panel.detailRun != null && panel.detailRun.executionId.equals(expectedId),
            "job result opens an exact member of its own comparison batch without replay");
        panel.clearHistory();
        var unrelated = new AuthComparePanel.Run(999, read, reply, panel.context()); unrelated.note = "완료";
        panel.runs.add(unrelated); panel.historyModel.addRow(new Object[]{999, read.url(), "", "", "", "완료", "GET", "", ""});
        jobs.openResult(jobs.snapshot().get(0).id());
        check(panel.detailRun == null && panel.state.getText().contains("현재 이력에 없습니다"), "expired batch result never substitutes a newer unrelated run");
        check(sends.get() == before, "opening or resolving an expired job result sends no HTTP");
      });
    } finally { release.countDown(); edt(panel::close); jobs.close(); WorkbenchScope.release(api); }
    System.out.println("AuthInlineValidationTest: " + checks + " checks passed");
  }
}
