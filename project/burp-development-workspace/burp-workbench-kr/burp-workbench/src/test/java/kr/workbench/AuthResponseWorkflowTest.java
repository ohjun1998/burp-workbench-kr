package kr.workbench;

import burp.api.montoya.*;
import burp.api.montoya.http.*;
import burp.api.montoya.http.message.requests.*;
import burp.api.montoya.internal.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Production comparison controller with Montoya adapters; no external target traffic. */
public final class AuthResponseWorkflowTest extends WorkbenchTest {
  static void rejects(String text) {
    try { AuthResponseRules.parse(text); throw new AssertionError("invalid rule accepted"); }
    catch (IllegalArgumentException expected) { check(true, "invalid rule rejected"); }
  }
  public static void main(String[] args) throws Exception {
    var rules = AuthResponseRules.parse("BODY: 접근 권한이 없습니다\nHEADER_REGEX: (?i)x-access:\\s*denied");
    check(rules.match("", "접근 권한이 없습니다").contains("규칙 1"), "HTML denial string matched");
    check(rules.match("X-Access: DENIED", "normal").contains("규칙 2"), "case insensitive header regex matched");
    check(rules.match("접근 권한이 없습니다", "normal").isEmpty(), "body and header scopes independent");
    check(AuthResponseRules.parse("BODY_REGEX: ^denied$").match("", "ok\ndenied\nend").contains("정규식"), "multiline body regex");
    check(AuthResponseRules.parse("BODY: Denied").match("", "denied").isEmpty(), "literal matching is case sensitive");
    check(AuthResponseRules.parse("").assess(200,"","ok",200,"","ok") == null, "empty rules preserve existing judgement");
    check(rules.assess(200,"","ok",200,"","접근 권한이 없습니다").state().equals("차단 근거 확인"), "HTTP 200 logical denial");
    check(rules.assess(200,"","접근 권한이 없습니다",200,"","접근 권한이 없습니다").state().equals("판정 보류"), "denied baseline cannot become green or red");
    for (int status : new int[]{302,404,410,500})
      check(rules.assess(200,"","ok",status,"","접근 권한이 없습니다") == null, "status change does not imply denial");
    check(rules.assess(500,"","ok",200,"","접근 권한이 없습니다") == null, "unsuccessful baseline left to baseline check");
    rejects("BODY_REGEX: ["); rejects("URL: denied"); rejects("BODY: "); rejects("BODY: " + "x".repeat(513));
    rejects("BODY: x\n".repeat(17)); rejects("x".repeat(8193));
    var jsonRules=AuthJudgement.rules("","error=FORBIDDEN\nerror=UNAUTHORIZED","");
    for(String error:List.of("FORBIDDEN","UNAUTHORIZED")) {
      check(AuthJudgement.compare(200,"{\"ok\":true}",200,"{\"error\":\""+error+"\"}",jsonRules,"").state().equals("차단 근거 확인"), "same-path JSON denial rules are OR");
      check(BountyMatrix.parse("").assess(BountyMatrix.Expected.DENY,200,"{\"error\":\""+error+"\"}",jsonRules).contains("차단 근거 일치"), "matrix shares JSON denial semantics");
    }
    check(new AuthJudgement.Rules(Set.of(),Map.of("error","FORBIDDEN"),"",AuthPolicy.Access.UNSPECIFIED).denied().size()==1, "legacy audit constructor remains compatible");
    var budget = AuthResponseRules.parse("BODY_REGEX: (a+)+$").assess(200,"","ok",200,"","a".repeat(12000)+"!");
    check(budget != null && budget.state().equals("판정 보류"), "pathological regex is bounded and requires review");
    check(rules.assess(200,"","ok",200,"","x".repeat(128*1024+1)).state().equals("판정 보류"), "oversized rule input never silently passes");
    check(AuthVerdict.of("우회 의심 · 동일 성공 응답") == AuthVerdict.SUSPECT, "identical successful response is red");
    check(AuthVerdict.of("차단 근거 확인 · HTTP 403") == AuthVerdict.BLOCKED, "explicit denial is green");
    check(AuthVerdict.of("원본과 동일 인증 · 교차 인가 미검증") == AuthVerdict.NONE, "same credentials are gray");
    check(AuthVerdict.of("신원 검사 실패 · 비교 미전송") == AuthVerdict.NONE, "identity failure is gray");
    check(AuthVerdict.of("기대 DENY · 차단 근거 일치 · 소유자 재조회 2xx 동일 · 대상 HTTP 404") == AuthVerdict.REVIEW, "404 stays yellow despite matrix evidence");
    check(AuthVerdict.of("기대 ALLOW · 차단 관측 · 세션/정책 재검토") == AuthVerdict.REVIEW, "expected allow conflicts stay yellow");

    ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class,(p,m,a) -> {
      if (m.getName().equals("requestOptions")) return proxy(RequestOptions.class,(pp,mm,aa)->pp);
      return def(m);
    });
    AtomicInteger status = new AtomicInteger(200), sent = new AtomicInteger();
    AtomicReference<String> body = new AtomicReference<>("{\"secret\":\"alice\"}");
    AtomicReference<HttpRequest> actual = new AtomicReference<>();
    var http = proxy(Http.class,(p,m,a) -> {
      if (!m.getName().equals("sendRequest")) return def(m);
      actual.set((HttpRequest)a[0]); sent.incrementAndGet();
      return AuthRecordTest.rr(actual.get(), response(status.get(), body.get()));
    });
    var api = proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("http") ? http : def(m));
    var original = AuthRecordTest.q("ALICE");
    var baseline = response(200,"{\"secret\":\"alice\"}");
    AtomicReference<burp.api.montoya.http.message.responses.HttpResponse> currentBaseline = new AtomicReference<>(baseline);
    AtomicReference<AuthComparePanel> box = new AtomicReference<>();
    edt(() -> {
      var panel = new AuthComparePanel(api, ()->List.of(AuthRecordTest.rr(original,currentBaseline.get()))); box.set(panel);
      panel.observe(AuthRecordTest.q("BOB"),baseline);
      panel.assign(0,panel.credentials.values().iterator().next());
    });
    AuthComparePanel panel = box.get();
    try {
      edt(panel::compareSelection); AuthRecordTest.idle(panel);
      check(panel.runs.get(0).packets[0].note.startsWith("우회 의심"), "production pipeline finds identical success");
      check(actual.get().headers().stream().noneMatch(h->h.value().contains("ALICE")), "original Cookie Authorization CSRF all removed");
      check(actual.get().url().equals(original.url()) && actual.get().method().equals(original.method()), "same operation preserved");
      body.set("<html>접근 권한이 없습니다</html>");
      edt(() -> { panel.responseDenialRules.setText("BODY: 접근 권한이 없습니다"); panel.compareSelection(); });
      AuthRecordTest.idle(panel);
      check(panel.runs.get(1).packets[0].note.contains("응답 차단 규칙 1"), "custom rule applied to real controller result");
      edt(() -> {
        panel.history.setRowSelectionInterval(1,1);
        var cell = (JLabel)panel.history.prepareRenderer(panel.history.getCellRenderer(1,2),1,2);
        check(cell.getForeground().equals(WorkbenchUi.toneInk(WorkbenchUi.Tone.SUCCESS)) && cell.getIcon()!=null, "green verdict appears in history even for HTTP 200");
        panel.show(panel.runs.get(1));
        check(panel.packets.getIconAt(0)!=null && panel.judgementDetails.getText().contains("차단 응답 확인"), "packet tab and text show same verdict");
        var neutralCell = (JLabel)panel.history.prepareRenderer(panel.history.getCellRenderer(1,1),1,1);
        check(neutralCell.getIcon()==null, "verdict icon does not leak into URL column");
        panel.matrixRules.setText("GET /object/101 ALLOW UNKNOWN UNKNOWN"); panel.compareSelection();
      });
      AuthRecordTest.idle(panel);
      check(AuthVerdict.of(panel.runs.get(2).packets[0].note)==AuthVerdict.REVIEW, "custom denial conflicting with expected ALLOW requires review");
      edt(() -> { panel.matrixRules.setText("GET /object/101 DENY UNKNOWN UNKNOWN"); panel.responseDenialRules.setText("BODY_REGEX: [");
        try { panel.compareSelection(); throw new AssertionError(); } catch (IllegalArgumentException expected) { check(true,"invalid pattern blocked before send"); }
      });
      check(sent.get()==3,"invalid rule sends no extra requests");
      status.set(500);
      edt(() -> { panel.responseDenialRules.setText("BODY: 접근 권한이 없습니다"); panel.compareSelection(); });
      AuthRecordTest.idle(panel);
      check(AuthVerdict.of(panel.runs.get(3).packets[0].note)==AuthVerdict.REVIEW, "server error remains yellow with DENY matrix");
      status.set(200);
      edt(() -> { panel.responseDenialRules.setText("BODY: 접근 권한이 없습니다"); panel.compareSelection(); panel.responseDenialRules.setText("BODY: different"); });
      AuthRecordTest.idle(panel);
      check(panel.runs.get(4).packets[0].note.contains("응답 차단 규칙 1"), "in-flight comparison keeps fingerprint snapshot");
      edt(() -> {
        panel.history.getRowSorter().toggleSortOrder(0); panel.history.getRowSorter().toggleSortOrder(0);
        var cell=(JLabel)panel.history.prepareRenderer(panel.history.getCellRenderer(0,2),0,2);
        check(cell.getForeground().equals(WorkbenchUi.toneInk(WorkbenchUi.Tone.SUCCESS)),"sorted history uses correct run verdict");
      });
      currentBaseline.set(response(200,"{\"error\":\"FORBIDDEN\"}")); body.set("{\"secret\":\"alice\"}");
      edt(() -> { panel.responseDenialRules.setText(""); panel.compareSelection(); });
      AuthRecordTest.idle(panel);
      check(panel.runs.get(5).packets[0].note.contains("기준 응답"), "DENY matrix cannot overwrite invalid JSON baseline: " + panel.runs.get(5).packets[0].note + " / " + JsAnalysisPanel.readBody(panel.runs.get(5).response));
      check(AuthVerdict.of(panel.runs.get(5).packets[0].note)==AuthVerdict.REVIEW, "invalid baseline remains yellow");
    } finally { edt(panel::close); }
    System.out.println("AuthResponseWorkflowTest: " + checks + " checks passed");
  }
}
