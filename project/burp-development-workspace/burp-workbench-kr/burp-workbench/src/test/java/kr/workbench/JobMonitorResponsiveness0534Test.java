package kr.workbench;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import javax.swing.event.TableModelEvent;
import java.util.List;

/** Checks model notifications and selection directly; no wall-clock performance assertion. */
public final class JobMonitorResponsiveness0534Test {
  static int checks;
  static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
  public static void main(String[] args)throws Exception{
    SwingUtilities.invokeAndWait(()->{
      AtomicLong clock=new AtomicLong(1_000_000_000L);BackgroundJobs jobs=new BackgroundJobs(clock::get);
      JobMonitorPanel panel=new JobMonitorPanel(jobs,route->{});
      var first=BackgroundJobs.begin(jobs,"A 비교","data",()->{});panel.refresh();panel.table.setRowSelectionInterval(0,0);
      AtomicInteger notifications=new AtomicInteger(),deletions=new AtomicInteger(),elapsedEvents=new AtomicInteger();
      panel.model.addTableModelListener(event->{notifications.incrementAndGet();if(event.getType()==TableModelEvent.DELETE)deletions.incrementAndGet();if(event.getColumn()==4)elapsedEvents.incrementAndGet();});
      for(int i=0;i<5;i++)panel.refresh();
      check(notifications.get()==0,"unchanged snapshots do not repopulate or notify table rows");
      check(!panel.timer.isRunning(),"hidden job monitor does not run elapsed timer");
      first.progress("차이 분석",1,3);panel.refresh();
      check(notifications.get()==2&&deletions.get()==0,"progress updates only changed stage and count cells");
      check(panel.table.getSelectedRow()==0&&panel.cancel.isEnabled(),"selective progress update keeps selected task actionable");
      int before=notifications.get();clock.addAndGet(200_000_000L);panel.refresh();
      check(notifications.get()==before,"subsecond clock change leaves displayed elapsed text untouched");
      clock.addAndGet(2_000_000_000L);panel.refresh();
      check(notifications.get()==before+1&&elapsedEvents.get()==1,"elapsed refresh updates only displayed seconds cell");
      panel.table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(0,SortOrder.ASCENDING)));
      var second=BackgroundJobs.begin(jobs,"B 검색","traffic",()->{});panel.refresh();
      int selected=panel.table.convertRowIndexToModel(panel.table.getSelectedRow());
      check(panel.model.getValueAt(selected,0).equals("A 비교"),"new task insertion preserves selected immutable task under sorting");
      check(deletions.get()==0&&panel.model.getRowCount()==2,"new task inserts without deleting retained rows");
      panel.addNotify();panel.refresh();
      check(!panel.isShowing()||panel.timer.isRunning(),"visible active monitor runs elapsed timer only while needed");
      first.complete("완료");second.complete("완료");panel.refresh();
      check(!panel.timer.isRunning(),"all-terminal task list stops elapsed timer");
      before=notifications.get();for(int i=0;i<4;i++){clock.addAndGet(1_000_000_000L);panel.refresh();}
      check(notifications.get()==before,"completed tasks keep fixed elapsed values without repeated model updates");
      panel.setVisible(false);check(!panel.timer.isRunning(),"hidden monitor stops timer");
      jobs.reset();panel.refresh();check(panel.model.getRowCount()==0&&!panel.cancel.isEnabled(),"project reset removes stale task rows and actions");
      panel.close();int closedRows=panel.model.getRowCount();BackgroundJobs.begin(jobs,"이후 작업","data",null);panel.refresh();
      check(panel.model.getRowCount()==closedRows&&!panel.timer.isRunning(),"closed monitor ignores new registry work");
      panel.removeNotify();jobs.close();
    });
    System.out.println("Job monitor responsiveness 0.53.4: "+checks+" checks passed");
  }
}
