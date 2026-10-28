package kr.workbench;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** One view of actual worker lifecycle metadata, without guessed percentages or time remaining. */
final class JobMonitorPanel extends JPanel implements AutoCloseable {
  final DefaultTableModel model=new DefaultTableModel(new String[]{"작업","상태","현재 단계","처리 건수","경과"},0) {
    public boolean isCellEditable(int row,int column){return false;}
  };
  final JTable table=new JTable(model);
  final JButton cancel=new JButton("선택 작업 중지"), result=new JButton("작업 화면 열기");
  final JTextArea summary=prose(2);
  final JTextArea detail=prose(3);
  private final BackgroundJobs registry;
  private final Consumer<String> open;
  private List<BackgroundJobs.View> rows=List.of();
  private final Runnable listener=this::registryChanged;
  final javax.swing.Timer timer=new javax.swing.Timer(500,e->refresh());
  private boolean refreshing, closed;
  private final JScrollPane scroll=new JScrollPane(table);
  JobMonitorPanel(BackgroundJobs registry,Consumer<String> open) {
    super(new BorderLayout(6,6));this.registry=registry;this.open=open;
    setName("wb.jobs.panel");setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
    summary.setName("wb.jobs.summary");table.setName("wb.jobs.table");
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setAutoCreateRowSorter(true);
    table.setRowHeight(27);cancel.setName("wb.jobs.cancel");result.setName("wb.jobs.result");
    table.getSelectionModel().addListSelectionListener(e->{if(!refreshing&&!e.getValueIsAdjusting())updateActions();});
    cancel.addActionListener(e->{var selected=selected();if(selected!=null)registry.requestCancel(selected.id());refresh();});
    result.addActionListener(e->{var selected=selected();if(selected!=null){if(selected.resultAvailable())registry.openResult(selected.id());else if(!selected.route().isBlank())open.accept(selected.route());}});
    add(summary,BorderLayout.NORTH);add(scroll);
    JPanel footer=new JPanel(new BorderLayout(4,4));footer.add(detail,BorderLayout.CENTER);
    footer.add(WorkbenchUi.row(cancel,result),BorderLayout.SOUTH);add(footer,BorderLayout.SOUTH);
    addHierarchyListener(e->{if((e.getChangeFlags()&java.awt.event.HierarchyEvent.SHOWING_CHANGED)!=0){
      if(isShowing())refresh();else timer.stop();
    }});
    registry.onChange(listener);refresh();WorkbenchUi.style(this);
  }
  private static JTextArea prose(int rows){JTextArea area=new JTextArea(rows,24);area.setEditable(false);area.setOpaque(false);area.setLineWrap(true);area.setWrapStyleWord(true);return area;}
  private BackgroundJobs.View selected() {
    int row=table.getSelectedRow();
    if(row<0||row>=table.getRowCount())return null;
    int index=table.convertRowIndexToModel(row);return index>=0&&index<rows.size()?rows.get(index):null;
  }
  private void registryChanged() {
    if(closed)return;
    if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::registryChanged);return;}
    if(isShowing())refresh();else timer.stop();
  }
  /** Reconcile by immutable task ID and update only cells whose displayed value changed. */
  void refresh() {
    if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::refresh);return;}
    if(closed)return;
    BackgroundJobs.View selected=selected();long id=selected==null?-1:selected.id();
    Point position=scroll.getViewport().getViewPosition();
    List<BackgroundJobs.View> next=registry.snapshot();
    List<BackgroundJobs.View> working=new ArrayList<>(rows);
    Set<Long> retained=new HashSet<>();for(var row:next)retained.add(row.id());
    refreshing=true;
    try {
      for(int i=working.size()-1;i>=0;i--)if(!retained.contains(working.get(i).id())){
        working.remove(i);model.removeRow(i);
      }
      for(int i=0;i<next.size();i++){
        var row=next.get(i);int existing=-1;
        for(int j=i;j<working.size();j++)if(working.get(j).id()==row.id()){existing=j;break;}
        if(existing<0){working.add(i,row);model.insertRow(i,values(row));}
        else {
          if(existing!=i){var moved=working.remove(existing);working.add(i,moved);model.moveRow(existing,existing,i);}
          Object[] values=values(row);
          for(int column=0;column<values.length;column++)
            if(!Objects.equals(model.getValueAt(i,column),values[column]))model.setValueAt(values[column],i,column);
          working.set(i,row);
        }
      }
      rows=next;
      if(id>=0){
        boolean found=false;
        for(int i=0;i<rows.size();i++)if(rows.get(i).id()==id){
          int view=table.convertRowIndexToView(i);
          if(view>=0){if(table.getSelectedRow()!=view)table.setRowSelectionInterval(view,view);found=true;}break;
        }
        if(!found)table.clearSelection();
      }
      scroll.getViewport().setViewPosition(position);
    } finally {refreshing=false;}
    long active=rows.stream().filter(row->!row.state().terminal()).count();
    long pending=rows.stream().filter(row->row.state()==BackgroundJobs.State.CANCEL_REQUESTED).count();
    String text=(rows.isEmpty()?"등록된 작업 없음 · 검색·분석·비교·증거 저장을 실행하면 표시됩니다.":"진행 중 "+active+"개 · 중지 종료 대기 "+pending+"개 · 최근 "+rows.size()+" / "+BackgroundJobs.LIMIT+"개")+(registry.overflowCount()>0?" · 표시 한도 초과 "+registry.overflowCount()+"건: 각 작업 화면에서 상태 확인":"");
    if(!summary.getText().equals(text))summary.setText(text);
    updateActions();
    if(isShowing()&&active>0){if(!timer.isRunning())timer.start();}else timer.stop();
  }
  private static Object[] values(BackgroundJobs.View row){return new Object[]{row.title(),row.state().label,row.stage(),row.count(),elapsed(row.elapsedMillis())};}
  private void updateActions(){var row=selected();cancel.setEnabled(row!=null&&row.cancellable());result.setText(row!=null&&row.resultAvailable()?"결과 열기":"작업 화면 열기");result.setEnabled(row!=null&&(row.resultAvailable()||!row.route().isBlank()));setDetail(row==null?"작업을 선택하세요.":row.state()==BackgroundJobs.State.CANCEL_REQUESTED?"중지 요청 후 이미 시작한 처리는 응답·타임아웃까지 대기할 수 있습니다.":row.state().terminal()?row.stage()+(row.resultAvailable()?" · 해당 실행의 보관된 결과를 엽니다.":" · 작업 화면의 현재 결과는 이후 실행으로 바뀌었을 수 있습니다."):row.cancellable()?"선택 작업에만 중지를 요청합니다.":"이 단계는 중지할 수 없습니다. 저장·정합성 처리가 끝날 때까지 기다리세요.");}
  private void setDetail(String text){if(!detail.getText().equals(text))detail.setText(text);}
  static String elapsed(long millis){long seconds=millis/1000;return seconds<60?seconds+"초":seconds/60+"분 "+seconds%60+"초";}
  @Override public void addNotify(){super.addNotify();if(isShowing())refresh();}
  @Override public void removeNotify(){timer.stop();super.removeNotify();}
  @Override public void close(){closed=true;timer.stop();registry.removeListener(listener);}
}
