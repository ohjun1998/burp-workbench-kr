package kr.workbench;

import java.awt.BorderLayout;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Bounded processing metadata. Only disk references are retained, never a second packet/body copy. */
final class AnalysisRetries {
  static final int LIMIT = 256;
  enum State {
    QUEUED("대기"), RUNNING("분석 중"), COMPLETE("분석 완료"), REUSED("기존 결과 확인"), PARTIAL("일부 분석"),
    QUEUE_FULL("큐 제외"), TOO_LARGE("크기 제한 · 미분석"), UNSUPPORTED("형식 제외"),
    FAILED("분석 실패"), CANCELLED("분석 중지"), EXPIRED("원본 만료"), SCOPE_EXCLUDED("Scope 제외");
    final String label;
    State(String label) { this.label=label; }
    boolean retryable() { return this==QUEUE_FULL||this==FAILED||this==CANCELLED||this==PARTIAL||this==SCOPE_EXCLUDED; }
  }
  record Entry(String id, String method, String url, DiskTrafficStore.PacketRef original, State state, String note) {
    boolean retryable() { return original!=null&&state.retryable(); }
    String originalLabel() { return original==null?"연결 없음":"#"+(original.sequence()+1); }
  }
  private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
  private final AtomicBoolean refreshPending=new AtomicBoolean();
  final DefaultTableModel model=new DefaultTableModel(new String[]{"원본","메서드","URL (쿼리 제외)","분석 상태","이유 · 다음 행동"},0) {
    @Override public boolean isCellEditable(int row,int column) { return false; }
  };
  final JTable table=new JTable(model);
  final JButton retry=new JButton("선택 원본 다시 분석");
  final JLabel summary=new JLabel();
  private List<Entry> displayed=List.of();
  private long evicted;
  private JPanel content;

  synchronized String record(DiskTrafficStore.PacketRef original,String method,String url,State state,String note) {
    String id=original==null?UUID.randomUUID().toString():original.id();
    if(!entries.containsKey(id)&&entries.size()>=LIMIT){entries.remove(entries.keySet().iterator().next());evicted++;}
    String safe=ActivityTracker.safeUrl(url);
    DiskTrafficStore.PacketRef retained=original!=null&&original.url().length()<=8192&&original.method().length()<=32?original:null;
    entries.put(id,new Entry(id,bounded(Objects.toString(method,""),32),bounded(safe==null?"출처 확인 필요":safe,2048),retained,state,bounded(Objects.toString(note,""),1200)));
    changed();return id;
  }
  synchronized void state(String id,State state,String note) {
    Entry entry=entries.get(id);if(entry==null)return;
    entries.put(id,new Entry(entry.id(),entry.method(),entry.url(),entry.original(),state,bounded(Objects.toString(note,""),1200)));changed();
  }
  private static String bounded(String value,int limit){return value.length()>limit?value.substring(0,limit)+"…":value;}
  synchronized Entry begin(String id) {
    Entry entry=entries.get(id);if(entry==null||!entry.retryable())return null;
    state(id,State.QUEUED,"보관된 동일 원본으로 재시도 · 네트워크 전송 없음");return entry;
  }
  synchronized Entry get(String id){return entries.get(id);}
  synchronized List<Entry> snapshot(){return List.copyOf(entries.values());}
  synchronized void cancelPending(){for(Entry entry:new ArrayList<>(entries.values()))if(entry.state()==State.QUEUED||entry.state()==State.RUNNING)state(entry.id(),State.CANCELLED,"분석이 중지되었습니다. 원본이 남아 있으면 다시 분석할 수 있습니다.");}
  synchronized void clear(){entries.clear();evicted=0;changed();}
  JPanel content(Consumer<String> callback) {
    if(content!=null)return content;
    table.setName("wb.analysis.retry.table");table.setAutoCreateRowSorter(true);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    int[] widths={80,80,290,150,420};for(int i=0;i<widths.length;i++)table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    retry.setName("wb.analysis.retry.selected");retry.setEnabled(false);
    retry.setToolTipText("선택 행과 연결된 보관 원본만 로컬에서 분석합니다. 서버에 요청하지 않으며 크기 한도는 유지됩니다.");
    table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()){Entry selected=selected();retry.setEnabled(selected!=null&&selected.retryable());}});
    retry.addActionListener(e->{Entry selected=selected();if(selected!=null&&selected.retryable())callback.accept(selected.id());});
    content=new JPanel(new BorderLayout(6,6));content.setName("wb.analysis.retry");
    content.add(TargetWindow.options(WorkbenchUi.row(retry),new JLabel("원본 선택 → 다시 분석 → 상태 확인 · 추가 요청 없음 · 크기/형식 한도 유지")),BorderLayout.NORTH);
    JScrollPane scroll=new JScrollPane(table);scroll.setPreferredSize(new java.awt.Dimension(760,340));content.add(scroll);content.add(summary,BorderLayout.SOUTH);content.addHierarchyListener(e->{if((e.getChangeFlags()&java.awt.event.HierarchyEvent.SHOWING_CHANGED)!=0&&content.isShowing())refresh();});refresh();return content;
  }
  private Entry selected(){int row=table.getSelectedRow();if(row<0)return null;int index=table.convertRowIndexToModel(row);return index<displayed.size()?displayed.get(index):null;}
  private void changed(){if(!refreshPending.compareAndSet(false,true))return;SwingUtilities.invokeLater(()->{refreshPending.set(false);if(content!=null&&content.isShowing())refresh();});}
  private void refresh(){Entry selected=selected();String selectedId=selected==null?"":selected.id();displayed=snapshot();model.setRowCount(0);for(Entry entry:displayed)model.addRow(new Object[]{entry.originalLabel(),entry.method(),entry.url(),entry.state().label,entry.note()+(entry.original()==null?" · 원본 연결 없음: 트래픽 수집 원본에서 분석하세요.":"")});for(int row=0;row<displayed.size();row++)if(displayed.get(row).id().equals(selectedId)){int view=table.convertRowIndexToView(row);if(view>=0)table.setRowSelectionInterval(view,view);break;}Entry now=selected();retry.setEnabled(now!=null&&now.retryable());long dropped;synchronized(this){dropped=evicted;}summary.setText("최근 처리 "+displayed.size()+" / "+LIMIT+"건 · 메타데이터 퇴출 "+dropped+" · 원본 트래픽 초기화 시 재시도 불가");}
}
