package kr.workbench;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Production optional-crawler UI failure and no-transmission boundaries; real Katana is a separate fixture. */
public final class ScopeKatanaUiTest extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    try(var fixture=new ScopeInspectionFixture()) {
      var scope=WorkbenchScope.install(fixture.api);scope.add(fixture.origin+"/app/",false);
      var handoffs=new AtomicInteger();
      ScopeInspectionPanel.Actions actions=new ScopeInspectionPanel.Actions(){
        public void original(DiskTrafficStore.PacketRef ref){handoffs.incrementAndGet();}
        public void repeater(DiskTrafficStore.PacketRef ref){handoffs.incrementAndGet();}
        public void compare(DiskTrafficStore.PacketRef ref){handoffs.incrementAndGet();}
        public void evidence(List<DiskTrafficStore.PacketRef> refs,String note){handoffs.incrementAndGet();}
        public void tool(String id,DiskTrafficStore.PacketRef ref){handoffs.incrementAndGet();}
      };
      var holder=new AtomicReference<ScopeInspectionPanel>();
      edt(()->holder.set(new ScopeInspectionPanel(fixture.api,()->List.of(new ScopeInspection.Profile("anonymous","비로그인",Map.of(),null)),actions)));
      var panel=holder.get();
      try {
        edt(()->{
          check(panel.runner==null&&panel.katana==null&&fixture.visits.isEmpty(),"opening Katana controls performs neither a crawl nor target HTTP");
          check(!panel.katanaMode.isSelected(),"existing ordinary inspection remains usable before an optional binary is installed");
          panel.url.setText(fixture.origin+"/app/");
          panel.katanaExecutable.setText(Path.of(System.getProperty("java.io.tmpdir"),"absent-katana-ui-"+UUID.randomUUID()).toString());
          panel.katanaCheck.doClick(0);
          check(!panel.katanaCheck.isEnabled(),"installation probe runs asynchronously and exposes its busy state");
        });
        ScopeInspectionUiTest.await(()->panel.katanaCheck.isEnabled(),"missing executable installation feedback");
        edt(()->check(panel.katanaCheckResult.getText().contains("확인하지 못"),"missing Katana has an actionable installation result"));
        check(fixture.visits.isEmpty(),"installation checking sends no target request");
        edt(()->{
          panel.budget.setValue(18);panel.delay.setValue(0);panel.depth.setValue(0);panel.katanaMode.setSelected(true);
          panel.start.doClick(0);
          check(panel.katana!=null&&panel.runner!=null,"explicit start connects the optional crawler to the real inspection coordinator");
          check(!panel.katanaMode.isEnabled()&&!panel.katanaExecutable.isEnabled(),"running crawl conditions cannot silently change mid-run");
          String id=panel.runner.id();rejects(panel::startInspection,"a second start cannot race the first UI refresh and replace a running crawler");
          check(panel.runner.id().equals(id),"rejecting a second start preserves the active runner and its original store");
        });
        ScopeInspectionUiTest.await(()->"FAILED".equals(panel.katana.snapshot().state()),"missing executable is a failed crawler");
        ScopeInspectionUiTest.await(()->Set.of(ScopeInspection.State.COMPLETED,ScopeInspection.State.STOPPED,ScopeInspection.State.FAILED).contains(panel.current.state()),"ordinary HTTP work finishes after crawler failure");
        edt(()->{
          var snapshot=panel.current;
          check(snapshot.partial()>0,"failed external discovery makes coverage explicitly incomplete");
          check(snapshot.findings().stream().anyMatch(f->f.category().equals("COVERAGE")&&f.title().contains("Katana")),"crawler failure stays visible in unconfirmed coverage findings");
          check(panel.katanaMode.isEnabled(),"a completed run unlocks crawler conditions for correction");
          check(panel.options.katana()&&panel.options.katanaPages()==100&&panel.options.katanaSeconds()==60,"durable comparison conditions retain the chosen discovery bounds");
          var packet=snapshot.pages().stream().filter(p->p.original()!=null).findFirst().orElseThrow();
          var found=new ScopeInspection.Finding("KATANA","원본 연결 확인",packet.url(),packet.method(),packet.account(),"관측","정보","원본 연결","테스트 합성 메타데이터",packet.original().id(),0,-1,"katana-ui-original");
          var view=new ScopeInspection.Snapshot("katana-ui-provenance",ScopeInspection.State.READY,"Katana 출처 연결",snapshot.sent(),snapshot.completed(),0,0,0,1,List.of(found),snapshot.pages(),List.of(),0,0);
          panel.accept(view);panel.pages.setSelectedIndex(ScopeInspectionUiTest.indexOf(panel.pages,"katana"));panel.tables.get("katana").setRowSelectionInterval(0,0);
          check(panel.selectedOriginal()!=null&&panel.selectedOriginal().id().equals(packet.original().id()),"Katana provenance selects the same exact original as the other result tabs");
        });
        int sent=fixture.visits.size();edt(()->{panel.original.doClick(0);panel.repeater.doClick(0);panel.evidence.doClick(0);});
        check(handoffs.get()==3&&fixture.visits.size()==sent,"Katana original and evidence handoff prepares existing tools without sending another request");
        edt(panel::close);
        check("CLOSED".equals(panel.katana.snapshot().state()),"unload closes optional process resources and its loopback proxy");
      } finally {edt(panel::close);WorkbenchScope.release(fixture.api);}
    }
    System.out.println("ScopeKatanaUiTest: "+checks+" checks passed (production UI and loopback API adapter; real crawler covered separately)");
  }
}
