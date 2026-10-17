package kr.workbench;

import burp.api.montoya.*;
import burp.api.montoya.http.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.internal.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** End-to-end account replays plus mixed-result aggregation and sorted table rendering. */
public final class AuthRunSummaryTest extends WorkbenchTest {
  static void packet(AuthComparePanel.Run run,int slot,int status,String note) {
    run.packets[slot].request=AuthRecordTest.q(slot==0?"A":"B");
    run.packets[slot].response=status==0?null:response(status,"{}");run.packets[slot].note=note;
  }
  static AuthComparePanel.Run run(AuthComparePanel panel,int baseline) {
    var run=new AuthComparePanel.Run(99,AuthRecordTest.q("A"),response(baseline,"{}"),panel.context());
    run.note="완료";return run;
  }
  public static void main(String[] args)throws Exception {
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->m.getName().equals("requestOptions")?proxy(RequestOptions.class,(pp,mm,aa)->pp):def(m));
    var http=proxy(Http.class,(p,m,a)->{
      if(!m.getName().equals("sendRequest"))return def(m);
      HttpRequest q=(HttpRequest)a[0];boolean blocked=q.headers().stream().anyMatch(h->h.name().equalsIgnoreCase("cookie")&&h.value().equals("B"));
      return AuthRecordTest.rr(q,response(blocked?403:200,blocked?"denied":"{}"));
    });
    var api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("http")?http:def(m));
    AtomicReference<AuthComparePanel> box=new AtomicReference<>();
    edt(()->{
      var p=new AuthComparePanel(api,()->List.of(AuthRecordTest.rr(AuthRecordTest.q("A"),response(200,"{}"))));box.set(p);
      p.observe(AuthRecordTest.q("A"),response(200,"{}"));p.observe(AuthRecordTest.q("B"),response(200,"{}"));
      var cs=new ArrayList<>(p.credentials.values());p.assign(0,cs.get(0));p.assign(1,cs.get(1));p.compareSelection();
    });
    var p=box.get();
    try {
      AuthRecordTest.idle(p);
      edt(()->{
        var live=p.runs.get(0);
        check(AuthRunSummary.reference(live.packets[0]),"original authentication identified after real adapter replay");
        check(p.historyModel.getValueAt(0,2).equals("200 · 기준"),"baseline account displays status and reference marker only");
        check(p.historyModel.getValueAt(0,3).equals("403"),"other account displays HTTP code only");
        check(p.historyModel.getValueAt(0,7).equals("차단 응답 확인"),"same-auth gray does not contaminate one blocked summary");
        check(p.history.getColumnName(3).equals("종합 결과")&&p.history.getColumnName(7).equals("실행 상태"),"single summary immediately after URL");
        p.show(live);check(p.packets.getIconAt(0)==null&&p.packets.getIconAt(1)==null,"no per-account verdict lights in packet tabs");
        check(p.judgementDetails.getText().contains("종합 결과: 차단 응답 확인")&&p.judgementDetails.getText().contains("HTTP 403"),"aggregate and account evidence remain available in details");
        var r=run(p,200);packet(r,0,200,"원본과 동일 인증 · 교차 인가 미검증");packet(r,1,200,"우회 의심 · 동일 성공 응답");
        check(AuthRunSummary.of(r).verdict()==AuthVerdict.SUSPECT,"reference plus unauthorized success gives one red summary");
        r.note="진행 중";check(AuthRunSummary.of(r).label().equals("비교 중"),"partial run is not presented as a final verdict");r.note="완료";
        packet(r,0,403,"차단 근거 확인 · HTTP 403");packet(r,1,0,"전송 오류");
        check(AuthRunSummary.of(r).verdict()==AuthVerdict.REVIEW,"partial transport failure cannot become all blocked");
        packet(r,1,404,"은닉형 차단/미발견 후보 · 404 · 재검토");check(AuthRunSummary.of(r).verdict()==AuthVerdict.REVIEW,"missing object is not a definitive blocked aggregate");
        packet(r,1,200,"우회 의심 · 동일 성공 응답");check(AuthRunSummary.of(r).verdict()==AuthVerdict.SUSPECT,"suspect response remains visible alongside blocked result");
        packet(r,0,403,"차단 근거 확인 · HTTP 403");packet(r,1,401,"차단 근거 확인 · HTTP 401");
        check(AuthRunSummary.of(r).label().equals("차단 응답 확인"),"unassigned third slot does not prevent all observed denials");
        r.note="중지";check(AuthRunSummary.of(r).verdict()==AuthVerdict.REVIEW,"stopped execution is not reported as complete blocked result");r.note="완료";
        packet(r,1,200,"공개/공유 허용 · 설정한 경로·메서드 정책 · 성공 응답 관측");
        check(AuthRunSummary.of(r).label().equals("정책 일치"),"mixed expected allow and denial are not labeled all blocked");
        packet(r,1,200,"기대 ALLOW · 성공 응답 관측 · 설정 충돌: 소유자 전용 정책 검토");
        check(AuthRunSummary.of(r).verdict()==AuthVerdict.REVIEW,"explicit policy conflict remains uncertain");
        var bad=run(p,401);packet(bad,0,401,"원본과 동일 인증 · 교차 인가 미검증");packet(bad,1,401,"판정 보류 · 기준 응답 확인 필요");
        check(AuthRunSummary.of(bad).verdict()==AuthVerdict.REVIEW,"screenshot's 401 baseline requires one baseline review");
        packet(bad,1,401,"원본과 동일 인증 · 교차 인가 미검증");check(AuthRunSummary.of(bad).verdict()==AuthVerdict.NONE,"reference-only responses are not cross-account evidence");
        var rejected=run(p,200);rejected.note="대기열 거부";
        check(AuthRunSummary.responseLabel(rejected,0).equals("응답 없음"),"rejected run is not permanently displayed as waiting");
        var failed=run(p,200);packet(failed,0,0,"전송 오류");packet(failed,1,0,"신원 검사 실패 · 비교 미전송");
        check(AuthRunSummary.of(failed).verdict()==AuthVerdict.NONE,"no valid comparison response stays unestablished");
        p.runs.add(r);p.historyModel.addRow(new Object[]{99,r.original.url(),"","","","완료","GET",""});p.refresh(r);
        p.history.getRowSorter().toggleSortOrder(0);p.history.getRowSorter().toggleSortOrder(0);
        int row=p.history.convertRowIndexToView(0),result=p.history.convertColumnIndexToView(7),account=p.history.convertColumnIndexToView(2);
        p.setSize(800,720);for(int i=0;i<5;i++)UiLayoutTest.layout(p);
        var bounds=p.history.getCellRect(row,result,true);
        check(bounds.x+bounds.width<=p.history.getParent().getWidth(),"summary remains visible without horizontal scrolling in narrow workspace");
        var cell=(JLabel)p.history.prepareRenderer(p.history.getCellRenderer(row,result),row,result);
        check(cell.getText().equals("차단 응답 확인")&&cell.getIcon()!=null&&cell.getForeground().equals(WorkbenchUi.toneInk(WorkbenchUi.Tone.SUCCESS)),"sorted aggregate uses correct run and color");
        cell=(JLabel)p.history.prepareRenderer(p.history.getCellRenderer(row,account),row,account);
        check(cell.getText().equals("200 · 기준")&&cell.getIcon()==null&&cell.getForeground().equals(WorkbenchUi.ink()),"reused summary renderer cannot leak verdict into account cell");
      });
    } finally {edt(p::close);}
    System.out.println("AuthRunSummaryTest: "+checks+" checks passed");
  }
}
