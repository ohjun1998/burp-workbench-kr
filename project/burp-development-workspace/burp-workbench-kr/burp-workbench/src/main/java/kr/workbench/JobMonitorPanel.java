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
  private final Runnable listener=this::refresh;
  private final javax.swing.Timer timer=new javax.swing.Timer(500,e->refresh());
  JobMonitorPanel(BackgroundJobs registry,Consumer<String> open) {
    super(new BorderLayout(6,6));this.registry=registry;this.open=open;
    setName("wb.jobs.panel");setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
    summary.setName("wb.jobs.summary");table.setName("wb.jobs.table");
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setAutoCreateRowSorter(true);
    table.setRowHeight(27);cancel.setName("wb.jobs.cancel");result.setName("wb.jobs.result");
    table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting())updateActions();});
    cancel.addActionListener(e->{var selected=selected();if(selected!=null)registry.requestCancel(selected.id());refresh();});
    result.addActionListener(e->{var selected=selected();if(selected!=null){if(selected.resultAvailable())registry.openResult(selected.id());else if(!selected.route().isBlank())open.accept(selected.route());}});
    add(summary,BorderLayout.NORTH);add(new JScrollPane(table));
    JPanel footer=new JPanel(new BorderLayout(4,4));footer.add(detail,BorderLayout.CENTER);
    footer.add(WorkbenchUi.row(cancel,result),BorderLayout.SOUTH);add(footer,BorderLayout.SOUTH);
    registry.onChange(listener);refresh();WorkbenchUi.style(this);
  }
  private static JTextArea prose(int rows){JTextArea area=new JTextArea(rows,24);area.setEditable(false);area.setOpaque(false);area.setLineWrap(true);area.setWrapStyleWord(true);return area;}
  private BackgroundJobs.View selected() {int row=table.getSelectedRow();return row<0?null:rows.get(table.convertRowIndexToModel(row));}
  void refresh() {
    if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::refresh);return;}
    BackgroundJobs.View selected=selected();long id=selected==null?-1:selected.id();
    rows=registry.snapshot();model.setRowCount(0);long active=0,pending=0;
    for(var row:rows){model.addRow(new Object[]{row.title(),row.state().label,row.stage(),row.count(),elapsed(row.elapsedMillis())});if(!row.state().terminal())active++;if(row.state()==BackgroundJobs.State.CANCEL_REQUESTED)pending++;}
    for(int i=0;i<rows.size();i++)if(rows.get(i).id()==id){int view=table.convertRowIndexToView(i);if(view>=0)table.setRowSelectionInterval(view,view);break;}
    summary.setText((rows.isEmpty()?"등록된 작업 없음 · 검색·분석·비교·증거 저장을 실행하면 표시됩니다.":"진행 중 "+active+"개 · 중지 종료 대기 "+pending+"개 · 최근 "+rows.size()+" / "+BackgroundJobs.LIMIT+"개")+(registry.overflowCount()>0?" · 표시 한도 초과 "+registry.overflowCount()+"건: 각 작업 화면에서 상태 확인":""));updateActions();
  }
  private void updateActions(){var row=selected();cancel.setEnabled(row!=null&&row.cancellable());result.setText(row!=null&&row.resultAvailable()?"결과 열기":"작업 화면 열기");result.setEnabled(row!=null&&(row.resultAvailable()||!row.route().isBlank()));detail.setText(row==null?"작업을 선택하세요.":row.state()==BackgroundJobs.State.CANCEL_REQUESTED?"중지 요청 후 이미 시작한 처리는 응답·타임아웃까지 대기할 수 있습니다.":row.state().terminal()?row.stage()+(row.resultAvailable()?" · 해당 실행의 보관된 결과를 엽니다.":" · 작업 화면의 현재 결과는 이후 실행으로 바뀌었을 수 있습니다."):row.cancellable()?"선택 작업에만 중지를 요청합니다.":"이 단계는 중지할 수 없습니다. 저장·정합성 처리가 끝날 때까지 기다리세요.");}
  static String elapsed(long millis){long seconds=millis/1000;return seconds<60?seconds+"초":seconds/60+"분 "+seconds%60+"초";}
  @Override public void addNotify(){super.addNotify();timer.start();refresh();}
  @Override public void removeNotify(){timer.stop();super.removeNotify();}
  @Override public void close(){timer.stop();registry.removeListener(listener);}
}
