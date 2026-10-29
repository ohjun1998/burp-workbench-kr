package kr.workbench;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.event.TableModelEvent;

/** A publication updates data without losing the user's sort, columns, copy selection or original. */
public final class ScopeInspectionTableUpdateTest extends WorkbenchTest {
  private static ScopeInspection.Snapshot snapshot(ScopeInspection.Snapshot source,
      List<ScopeInspection.Finding> findings, List<ScopeInspection.Candidate> candidates, ScopeInspection.State state) {
    return new ScopeInspection.Snapshot(source.id(),state,"table publication",source.sent(),source.completed(),
        0,source.excluded(),source.failed(),source.partial(),findings,source.pages(),candidates,source.startedAt(),source.finishedAt());
  }
  public static void main(String[] args) throws Exception {
    try(var fixture=new ScopeInspectionFixture()) {
      WorkbenchScope.install(fixture.api).add(fixture.origin+"/app/",false);
      var options=ScopeInspectionRunnerTest.options(fixture,"/app/config",30,0,false,ScopeInspectionRunnerTest.anonymous());
      try(var runner=new ScopeInspectionRunner(fixture.api,options,url->url.startsWith(fixture.origin+"/app/"),value->{})) {
        runner.start();ScopeInspectionRunnerTest.await(()->ScopeInspectionRunnerTest.terminal(runner),"table fixture");
        var live=runner.snapshot();
        var original=live.pages().stream().filter(p->p.original()!=null).findFirst().orElseThrow().original();
        var api=new ScopeInspection.Finding("API","API A",original.url(),"GET","Alice","관측","INFO","후보","detail A",original.id(),1,0,"same");
        var secret=new ScopeInspection.Finding("SECRET","Secret B",original.url(),"GET","Bob","관측","HIGH","후보","detail B",original.id(),2,10,"same");
        var changed=new ScopeInspection.Finding("API","API changed",api.url(),api.method(),api.account(),api.confidence(),api.severity(),api.state(),"changed detail",api.originalId(),api.line(),api.offset(),api.fingerprint());
        var handed=new AtomicReference<String>();
        ScopeInspectionPanel.Actions actions=new ScopeInspectionPanel.Actions(){
          public void original(DiskTrafficStore.PacketRef ref){handed.set(ref.id());}
          public void repeater(DiskTrafficStore.PacketRef ref){} public void compare(DiskTrafficStore.PacketRef ref){}
          public void evidence(List<DiskTrafficStore.PacketRef> refs,String note){} public void tool(String id,DiskTrafficStore.PacketRef ref){}
        };
        var holder=new AtomicReference<ScopeInspectionPanel>();
        edt(()->holder.set(new ScopeInspectionPanel(fixture.api,ScopeInspectionRunnerTest::anonymous,actions)));
        var panel=holder.get();int requests=fixture.visits.size();
        try{edt(()->{
          var initial=snapshot(live,List.of(api,secret,api),List.of(),ScopeInspection.State.READY);
          panel.accept(initial);panel.pages.setSelectedIndex(ScopeInspectionUiTest.indexOf(panel.pages,"summary"));
          JTable summary=panel.tables.get("summary");
          var events=new HashMap<String,AtomicInteger>();var structures=new AtomicInteger();
          panel.tables.forEach((key,table)->{var count=new AtomicInteger();events.put(key,count);table.getModel().addTableModelListener(event->{count.incrementAndGet();if(event.getFirstRow()==TableModelEvent.HEADER_ROW)structures.incrementAndGet();});});
          summary.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(2,SortOrder.DESCENDING)));
          int view=summary.convertRowIndexToView(1);summary.setRowSelectionInterval(view,view);
          summary.getColumnModel().getColumn(0).setPreferredWidth(187);summary.getColumnModel().getColumn(0).setWidth(187);
          summary.moveColumn(0,1);int column=summary.getColumnModel().getColumn(1).getModelIndex();
          panel.detail.select(3,12);String selected=panel.detail.getSelectedText();
          panel.accept(snapshot(live,new ArrayList<>(List.of(api,secret,api)),List.of(),ScopeInspection.State.READY));
          check(events.values().stream().allMatch(count->count.get()==0),"equal publication produces no table events, including hidden tabs");
          check(Objects.equals(selected,panel.detail.getSelectedText()),"unchanged table publication keeps the detail copy selection");
          check(panel.selectedFinding().equals(secret),"a sorted duplicate fingerprint still selects the exact account result");
          check(summary.getRowCount()==3,"exact duplicate finding rows remain visible");
          check(summary.getColumnModel().getColumn(1).getWidth()==187,"column width survives an equal publication");
          panel.accept(snapshot(live,List.of(changed,secret,api),List.of(),ScopeInspection.State.READY));
          check(events.get("summary").get()==1&&events.get("api").get()==1,"one changed API replaces only summary and API data once each");
          check(events.entrySet().stream().filter(e->!Set.of("summary","api").contains(e.getKey())).allMatch(e->e.getValue().get()==0),"unaffected hidden tables receive no model churn");
          check(structures.get()==0,"data publications never emit structure changes");
          check(summary.getRowSorter().getSortKeys().equals(List.of(new RowSorter.SortKey(2,SortOrder.DESCENDING))),"sort keys survive changed data");
          check(summary.getColumnModel().getColumn(1).getModelIndex()==column&&summary.getColumnModel().getColumn(1).getWidth()==187,"changed data preserves column order and width");
          check(panel.selectedFinding().equals(secret),"changed neighbor rows preserve the selected result identity");
          panel.original.doClick(0);check(original.id().equals(handed.get()),"original handoff uses the selected real loopback packet");
          panel.accept(snapshot(live,List.of(secret,api,changed),List.of(),ScopeInspection.State.READY));
          check(panel.selectedFinding().equals(secret),"input reordering preserves the exact selected result");
          check(panel.tables.get("api").getRowCount()==2,"hidden API tab receives the reordered data");
          var candidate=new ScopeInspection.Candidate(fixture.origin+"/app/pending","GET","JS","pending",false);
          panel.accept(snapshot(live,List.of(secret,api,changed),List.of(candidate),ScopeInspection.State.RUNNING));
          check(panel.tables.get("coverage").getRowCount()==1,"candidate-only publications refresh the hidden coverage table");
          check(panel.pause.isEnabled()&&!panel.start.isEnabled()&&panel.progress.getText().contains("점검 중"),"unchanged result data does not suppress live state and action changes");
          panel.accept(snapshot(live,List.of(),List.of(),ScopeInspection.State.READY));
          check(summary.getRowCount()==0&&!panel.original.isEnabled(),"empty result publications clear selection and original actions");
        });}finally{edt(panel::close);}
        check(fixture.visits.size()==requests,"table publications, sorting and original handoff issue zero extra HTTP");
      }
    }
    System.out.println("ScopeInspectionTableUpdateTest: "+checks+" checks passed");
  }
}
