package kr.workbench;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;

/** Real export UI state, local exact-value output and zero extra target requests. */
public final class ScopeInspectionReportUiTest extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    try(var fixture=new ScopeInspectionFixture();var store=new DiskTrafficStore()) {
      var scope=WorkbenchScope.install(fixture.api);scope.add(fixture.origin+"/app/",false);
      var actions=new ScopeInspectionPanel.Actions(){
        public void original(DiskTrafficStore.PacketRef p){} public void repeater(DiskTrafficStore.PacketRef p){}
        public void compare(DiskTrafficStore.PacketRef p){} public void evidence(List<DiskTrafficStore.PacketRef> p,String n){}
        public void tool(String id,DiskTrafficStore.PacketRef p){}
      };
      var holder=new AtomicReference<ScopeInspectionPanel>();
      edt(()->holder.set(new ScopeInspectionPanel(fixture.api,()->List.of(new ScopeInspection.Profile("anonymous","비로그인",Map.of(),null)),actions)));
      var panel=holder.get();Path directory=Files.createTempDirectory("simple-report-ui-");
      try {
        edt(()->check(!panel.report.isEnabled(),"export requires a current response original"));
        String secret="REPORT_UI_EXAMPLE_PRIVATE_0123456789abcdef";
        String body="{\"api_key\":\""+secret+"\",\"email\":\"example@sample.test\"}";
        var request=ScopeInspectionFixture.wireRequest(fixture.origin+"/app/config","GET",List.of(),new byte[0]);
        var response=ScopeInspectionFixture.wireResponse(200,List.of(header("Content-Type","application/json")),body.getBytes(StandardCharsets.UTF_8));
        var ref=store.append("분류 보고서 UI 회귀","비로그인",request,response);
        var page=new ScopeInspection.Page(ref.url(),"GET","비로그인",200,"CONTENT",true,"UI 검사 원본","",ref);
        var findings=ScopeInspectionAnalysis.analyze(ref.url(),200,"Content-Type: application/json",body,"비로그인",ref.id()).findings();
        var snapshot=new ScopeInspection.Snapshot("report-ui",ScopeInspection.State.COMPLETED,"검사 종료",1,1,0,0,0,0,findings,List.of(page),List.of(),1,2);
        edt(()->{
          panel.accept(snapshot);check(panel.report.isEnabled(),"completed originals enable categorized report export without selecting a row");
          check(!ScopeInspectionUiTest.visibleText(panel).contains(secret),"ordinary inspection UI remains masked");
          panel.accept(new ScopeInspection.Snapshot(snapshot.id(),ScopeInspection.State.RUNNING,"실행 중",1,1,0,0,0,0,findings,List.of(page),List.of(),1,0));
          check(!panel.report.isEnabled(),"running export is unavailable until the result is stable");
          rejects(()->panel.saveReport(directory.resolve("running.zip")),"programmatic export also checks active state");
          panel.accept(snapshot);panel.saveReport(directory.resolve("exact.zip"));
          check(!panel.report.isEnabled()&&!panel.start.isEnabled(),"async export exposes busy state and retains current originals");
          rejects(panel::startInspection,"a new run cannot discard originals while export is reading them");
        });
        ScopeInspectionUiTest.await(()->panel.report.isEnabled(),"local exact-value report completes");
        edt(()->check(panel.feedback.getText().contains("저장 완료"),"export gives a completion result"));
        try(var zip=new ZipFile(directory.resolve("exact.zip").toFile())) {
          String json=new String(zip.getInputStream(zip.getEntry("report.json")).readAllBytes(),StandardCharsets.UTF_8);
          check(json.contains(secret)&&json.contains(ref.id()),"local report contains the exact parsed value and linked original");
        }
        check(fixture.visits.isEmpty(),"parsing and saving a report never sends a target request");
        edt(()->panel.saveReport(directory.resolve("exact.zip")));
        ScopeInspectionUiTest.await(()->panel.report.isEnabled(),"existing file refusal completes");
        edt(()->check(panel.feedback.getText().contains("덮어쓰지"),"existing report is preserved and refusal is visible"));
        edt(panel::close);check(!panel.report.isEnabled(),"closed workspace cannot export stale originals");
      } finally {
        edt(panel::close);WorkbenchScope.release(fixture.api);
        try(var paths=Files.walk(directory)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
      }
    }
    System.out.println("ScopeInspectionReportUiTest: "+checks+" checks passed (local production export UI; no target HTTP)");
  }
}
