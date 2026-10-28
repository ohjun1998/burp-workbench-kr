package kr.workbench;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** Real result controls over synthetic evidence; no target dispatch is required to inspect or filter. */
public final class ScopeInspectionResultsUiTest extends WorkbenchTest {
  static ScopeInspection.Snapshot view(List<ScopeInspection.Finding> findings,List<ScopeInspection.Page> pages,List<ScopeInspectionCoverage.Row> coverage){
    return new ScopeInspection.Snapshot("result-view",ScopeInspection.State.READY,"합성 결과 검증",1,1,1,1,0,1,findings,pages,List.of(),1,2,coverage);
  }
  static ScopeInspection.Finding finding(String category,String title,String state,String classification,String original){
    return new ScopeInspection.Finding(category,title,"https://masked.test/api/data","GET","선택 계정","관측","중간",state,"[가림] · 분류: "+classification,original,1,4,ScopeInspectionAnalysis.hash(title));
  }
  static void resumeAndRecheck()throws Exception{
    try(var fixture=new ScopeInspectionFixture()){
      var scope=WorkbenchScope.install(fixture.api);scope.add(fixture.origin+"/app/",false);
      var identity=new CookieSessionProbe.Config(fixture.origin+"/app/api/me","$.id","alice");
      var first=new ScopeInspection.Profile("alice","Alice",Map.of("Cookie",ScopeInspectionFixture.SESSION,"X-Test-Revision","r1"),identity);
      var refreshed=new ScopeInspection.Profile("alice","Alice",Map.of("Cookie",ScopeInspectionFixture.SESSION,"X-Test-Revision","r2"),identity);
      var external=new ScopeInspection.Profile("alice","Alice",Map.of("Cookie",ScopeInspectionFixture.SESSION,"X-Test-Revision","r3"),identity);
      var configured=new AtomicReference<List<ScopeInspection.Profile>>(List.of(first));
      ScopeInspectionPanel.Actions noop=new ScopeInspectionPanel.Actions(){
        public void original(DiskTrafficStore.PacketRef p){}public void repeater(DiskTrafficStore.PacketRef p){}public void compare(DiskTrafficStore.PacketRef p){}
        public void evidence(List<DiskTrafficStore.PacketRef> p,String note){}public void tool(String id,DiskTrafficStore.PacketRef p){}
      };
      var holder=new AtomicReference<ScopeInspectionPanel>();edt(()->holder.set(new ScopeInspectionPanel(fixture.api,configured::get,noop)));var panel=holder.get();
      try{
        edt(()->{panel.url.setText(fixture.origin+"/app/");panel.budget.setValue(100);panel.depth.setValue(0);panel.delay.setValue(0);panel.start.doClick(0);panel.pause.doClick(0);});
        ScopeInspectionUiTest.await(()->panel.current.state()==ScopeInspection.State.PAUSED,"UI pause before same-account credential refresh");
        configured.set(List.of(refreshed));edt(()->panel.resume.doClick(0));
        ScopeInspectionUiTest.await(()->ScopeInspectionUiTest.terminal(panel.current),"refreshed UI run completes");
        var candidate=ScopeInspectionCoverage.rows(panel.current).stream().filter(r->r.url().equals(fixture.origin+"/app/config")&&r.profileRevision().startsWith("r2:")&&r.retryable()).findFirst().orElseThrow();
        edt(()->{
          panel.pages.setSelectedIndex(ScopeInspectionUiTest.indexOf(panel.pages,"routes"));JTable table=panel.tables.get("routes");
          List<ScopeInspectionCoverage.Row> rows=ScopeInspectionCoverage.rows(panel.current);int model=0;while(!rows.get(model).key().equals(candidate.key()))model++;
          int view=table.convertRowIndexToView(model);table.setRowSelectionInterval(view,view);
          check(panel.options.profiles().get(0).headers().get("x-test-revision").equals("r1"),"original run options remain the immutable initial profile snapshot");
          check(panel.runner.profilesMatch(List.of(refreshed))&&!panel.runner.profilesMatch(List.of(first)),"runner recognizes only the explicitly accepted refreshed authentication revision");
          check(panel.recheck.isEnabled(),"same-account resumed r2 route can be rechecked despite immutable r1 options");
        });
        int before=fixture.visits.size(),sentBefore=panel.current.sent();configured.set(List.of(external));
        edt(()->{panel.applyFilters();check(!panel.recheck.isEnabled(),"unaccepted external credential change disables selective recheck consistently");
          check(panel.recheck.getToolTipText().contains("인증 조건"),"disabled recheck explains the current authentication mismatch");
          rejects(panel::recheckSelected,"unaccepted external credentials cannot be silently adopted by selective recheck");});
        check(fixture.visits.size()==before,"credential mismatch validation makes no target or identity requests");
        configured.set(List.of(refreshed));edt(()->{panel.applyFilters();check(panel.recheck.isEnabled(),"restoring the runner-accepted profile re-enables the selected current revision");panel.recheck.doClick(0);});
        ScopeInspectionUiTest.await(()->ScopeInspectionUiTest.terminal(panel.current)&&panel.current.sent()>sentBefore,"selected current-revision UI recheck completes");
        var checked=ScopeInspectionCoverage.rows(panel.current).stream().filter(r->r.key().equals(candidate.key())).findFirst().orElseThrow();
        check(checked.state().equals("ANALYZED")&&checked.status()==200&&!checked.originalId().isEmpty(),"UI selective recheck obtains an actual response under accepted r2 credentials");
        check(checked.profileRevision().startsWith("r2:")&&panel.runner.profilesMatch(List.of(refreshed)),"selective recheck preserves r2 identity and never reverts to options r1");
      }finally{edt(panel::close);WorkbenchScope.release(fixture.api);}
    }
  }
  public static void main(String[] args)throws Exception{
    try(var fixture=new ScopeInspectionFixture();var store=new DiskTrafficStore()){
      var scope=WorkbenchScope.install(fixture.api);scope.add(fixture.origin+"/app/",false);
      var packet=ScopeInspectionReportTest.packet(store,fixture.origin+"/app/config","application/json","{\"phone\":\"+1-555-0123\"}",List.of());
      var page=new ScopeInspection.Page(packet.url(),"GET","선택 계정",200,"JSON",true,"", "a".repeat(64),packet,"r1-test");
      List<ScopeInspection.Finding> findings=List.of(
          finding("SECRET","api_key","유효성 미검증","SECRET_CANDIDATE",packet.id()),
          finding("SENSITIVE","phone","관측 값·공개 범위 미확인","PERSONAL_DATA",packet.id()),
          finding("SECRET","fakeToken","예제값 가능","EXAMPLE_OR_PLACEHOLDER",packet.id()),
          finding("API","API 템플릿","동적 미해결","DYNAMIC_UNRESOLVED",packet.id()));
      var analyzed=new ScopeInspectionCoverage.Row("analyzed",packet.url(),"GET","선택 계정","r1-test","관측 링크","설정","ANALYZED","분석 범위 완료",200,packet.id(),false,List.of("DISCOVERED","SCOPE_CHECKED","QUEUED","REQUESTED","RESPONDED","ANALYZED"));
      var unresolved=new ScopeInspectionCoverage.Row("excluded","${host}/app/api/items/${id}?token=PRIVATE_QUERY","GET","선택 계정","r1-test","JS API","동적 경로","EXCLUDED","변수 미해결",0,"",false,List.of("DISCOVERED","SCOPE_CHECKED"));
      var retry=new ScopeInspectionCoverage.Row("retry",fixture.origin+"/app/api/later","GET","선택 계정","r1-test","관측 링크","후속 메뉴","UNVISITED","중지로 미방문",0,"",true,List.of("DISCOVERED","SCOPE_CHECKED","QUEUED"));
      var browser=new ScopeInspectionCoverage.Row("browser",fixture.origin+"/app/api/browser","GET","브라우저 세션","","브라우저 통신","API 요청","HTTP_RESPONDED","브라우저 응답만 관측 · 본문 미분석",200,"",false,List.of("DISCOVERED","REQUESTED","RESPONDED"));
      var relative=new ScopeInspectionCoverage.Row("relative","/app/api/items/{id}?key=PRIVATE_QUERY","GET","선택 계정","r1-test","JS API","정적 경로 조각","EXCLUDED","서버 미확정",0,"",false,List.of("DISCOVERED","SCOPE_CHECKED"));
      List<ScopeInspectionCoverage.Row> coverage=List.of(analyzed,unresolved,retry,browser,relative);
      var received=new AtomicReference<DiskTrafficStore.PacketRef>();
      ScopeInspectionPanel.Actions actions=new ScopeInspectionPanel.Actions(){
        public void original(DiskTrafficStore.PacketRef p){received.set(p);}public void repeater(DiskTrafficStore.PacketRef p){}
        public void compare(DiskTrafficStore.PacketRef p){}public void evidence(List<DiskTrafficStore.PacketRef> p,String note){}public void tool(String id,DiskTrafficStore.PacketRef p){}
      };
      var holder=new AtomicReference<ScopeInspectionPanel>();
      edt(()->holder.set(new ScopeInspectionPanel(fixture.api,()->List.of(new ScopeInspection.Profile("anonymous","비로그인",Map.of(),null)),actions)));
      var panel=holder.get();
      try{edt(()->{
        panel.accept(view(findings,List.of(page),coverage));
        panel.pages.setSelectedIndex(ScopeInspectionUiTest.indexOf(panel.pages,"secrets"));
        check(panel.tables.get("secrets").getRowCount()==3,"key-information tab includes personal data alongside secret candidates");
        panel.resultFilter.setSelectedItem(ScopeInspectionView.Filter.EXAMPLE);
        check(panel.tables.get("secrets").getRowCount()==1,"example filter separates the demo token in production tables");
        panel.resultFilter.setSelectedItem(ScopeInspectionView.Filter.IMPORTANT);
        check(panel.tables.get("secrets").getRowCount()==2,"important view excludes demo while retaining personal and key observations");
        panel.resultSearch.setText("PHONE");
        check(panel.tables.get("secrets").getRowCount()==1,"production search uses masked field metadata");
        panel.tables.get("secrets").setRowSelectionInterval(0,0);
        check(panel.selectedOriginal()==packet,"filtered row resolves the exact evidence packet");
        check(panel.detail.getText().contains("출처 응답:")&&panel.detail.getText().contains("HTTP 200")&&panel.detail.getText().contains("r1-test"),"details expose source status and authentication revision without claiming unauthorized access");
        panel.original.doClick(0);check(received.get()==packet,"original handoff uses the same immutable packet after filtering");
        panel.resultSearch.setText("");panel.resultFilter.setSelectedItem(ScopeInspectionView.Filter.UNRESOLVED);
        panel.pages.setSelectedIndex(ScopeInspectionUiTest.indexOf(panel.pages,"api"));
        check(panel.tables.get("api").getRowCount()==1,"unresolved API filter keeps dynamic declarations discoverable");
        panel.resultFilter.setSelectedItem(ScopeInspectionView.Filter.ALL);
        panel.pages.setSelectedIndex(ScopeInspectionUiTest.indexOf(panel.pages,"routes"));
        check(panel.tables.get("routes").getRowCount()==5,"route coverage includes HTTP, browser-only, excluded and unvisited traces");
        check(panel.tables.get("routes").getValueAt(1,3).equals("${host}/app/api/items/${id}")&&panel.tables.get("routes").getValueAt(4,3).equals("/app/api/items/{id}"),"dynamic and relative API paths do not disappear from route cells");
        panel.tables.get("routes").setRowSelectionInterval(1,1);
        check(panel.detail.getText().contains("${host}/app/api/items/${id}")&&!panel.detail.getText().contains("PRIVATE_QUERY"),"selected unresolved route retains its masked template in details");
        panel.routeFilter.setSelectedIndex(1);
        check(panel.tables.get("routes").getRowCount()==2,"response filter preserves browser observation as separate from analyzed HTTP");
        panel.routeFilter.setSelectedIndex(2);
        check(panel.tables.get("routes").getRowCount()==4,"unconfirmed filter retains browser body-unparsed and excluded states");
        panel.routeFilter.setSelectedIndex(3);
        check(panel.tables.get("routes").getRowCount()==2,"excluded view does not mislabel queued or unvisited work as policy exclusions");
        panel.routeFilter.setSelectedIndex(0);
        JTable table=panel.tables.get("routes");table.setRowSelectionInterval(0,0);
        check(panel.selectedOriginal()==packet,"coverage response trace links the exact original");
        table.setRowSelectionInterval(3,3);
        check(panel.selectedOriginal()==null&&!panel.original.isEnabled(),"browser metadata cannot open a fabricated HTTP original");
        check(panel.detail.getText().contains("본문 미분석"),"browser response is visibly distinct from body analysis");
        table.setRowSelectionInterval(2,2);
        check(!panel.recheck.isEnabled(),"synthetic or historic metadata cannot dispatch selective requests without its live runner");
        rejects(panel::recheckSelected,"recheck without a live retained run is rejected");
        table.setRowSelectionInterval(0,0);table.addRowSelectionInterval(2,2);
        var reversed=new ArrayList<>(coverage);Collections.reverse(reversed);panel.accept(view(findings,List.of(page),reversed));
        check(panel.selectedCoverage().stream().map(ScopeInspectionCoverage.Row::key).collect(java.util.stream.Collectors.toSet()).equals(Set.of("analyzed","retry")),"coverage multiselection survives live result reordering by stable trace keys");
        check(!ScopeInspectionUiTest.visibleText(panel).contains("+1-555-0123"),"raw personal data remains in the original rather than normal result text");
      });
      check(fixture.visits.isEmpty(),"result filtering, evidence selection and rejected recheck perform no target requests");
      String template="${host}/app/api/items/${id}";
      var script=ScopeInspectionReportTest.packet(store,fixture.origin+"/app/discovery.js","application/javascript","fetch(`"+template+"`)",List.of());
      var response=ScopeInspectionReportTest.packet(store,fixture.origin+"/app/api/items/42","application/json","{\"ok\":true}",List.of());
      var sourcePage=new ScopeInspection.Page(script.url(),"GET","선택 계정",200,"SCRIPT",true,"","b".repeat(64),script,"r1-test");
      var responsePage=new ScopeInspection.Page(response.url(),"GET","다른 계정",200,"JSON",true,"","c".repeat(64),response,"r2-other");
      var templateFinding=new ScopeInspection.Finding("API","API 템플릿",script.url(),"GET","선택 계정","관측","정보","동적 미해결",template+" · 분류: DYNAMIC_UNRESOLVED",script.id(),1,7,ScopeInspectionCoverage.key(template));
      var templateRow=new ScopeInspectionCoverage.Row("template-shape",template,"GET","선택 계정","r1-test","JS API","템플릿","EXCLUDED","동적 변수 미해결",0,"",false,List.of("DISCOVERED","SCOPE_CHECKED"));
      var actualRow=new ScopeInspectionCoverage.Row("actual-shape",response.url(),"GET","다른 계정","r2-other","관측 요청","실제 응답","ANALYZED","응답 분석",200,response.id(),false,List.of("DISCOVERED","SCOPE_CHECKED","REQUESTED","RESPONDED","ANALYZED"));
      var linkedFindings=new ArrayList<>(findings);linkedFindings.add(templateFinding);var linkedCoverage=new ArrayList<>(coverage);linkedCoverage.add(templateRow);linkedCoverage.add(actualRow);
      edt(()->{
        panel.accept(view(linkedFindings,List.of(page,sourcePage,responsePage),linkedCoverage));panel.routeFilter.setSelectedIndex(4);
        JTable table=panel.tables.get("routes");check(table.getRowCount()==2,"shape-match view shows the unresolved template and its independently observed response");
        table.setRowSelectionInterval(0,0);
        check(panel.detail.getText().contains("PATH_SHAPE_ONLY")&&panel.detail.getText().contains("변수 복원")&&panel.detail.getText().contains("선택 계정 / r1-test")&&panel.detail.getText().contains("다른 계정 / r2-other"),"correlation details preserve separate actors and revisions without claiming interpolation or authorization");
        check(panel.detail.getText().contains(script.id())&&panel.detail.getText().contains(response.id()),"template source and observed response retain separate immutable evidence IDs");
        check(panel.selectedOriginal()==script,"unvisited template opens its exact declaration source rather than the matched target response");
        check(panel.selectedCoverage().get(0).state().equals("EXCLUDED")&&!panel.selectedCoverage().get(0).retryable(),"shape correlation never promotes unresolved template execution or completion");
        table.setRowSelectionInterval(1,1);check(panel.selectedOriginal()==response,"observed correlation row opens its own response source");
      });
      check(fixture.visits.isEmpty(),"local template correlations and original handoffs generate no target requests");
      }finally{edt(panel::close);WorkbenchScope.release(fixture.api);}
    }
    resumeAndRecheck();
    System.out.println("ScopeInspectionResultsUiTest: "+checks+" checks passed (production Swing, synthetic evidence and isolated loopback HTTP; not native Burp GUI)");
  }
}
