package kr.workbench;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.*;

/** One-line evidence and one combined filter menu; all actions use original model indexes. */
final class JsResultsView extends JPanel {
  final JsAnalysisPanel owner;
  final JButton filter=WorkspaceIcons.button("filter","wb.js.filter","목록 필터");
  final JLabel count=new JLabel("0 / 0"),summary=new JLabel("경로를 선택하세요"){
    @Override public Dimension getPreferredSize(){return new Dimension(80,28);}
  },shortcut=new JLabel("Ctrl+R · Repeater");
  final JPopupMenu popup=new JPopupMenu();
  final JComboBox<String> call=new JComboBox<>(new String[]{"전체","미호출","실제 호출","메서드 충돌","미확정"});
  final JCheckBox priority=new JCheckBox("우선 검토만"),fresh=new JCheckBox("신규만");
  final TargetWindow comparison,versionWindow;
  final DefaultTableModel traffic=model("경로","분석 메서드","관찰 메서드","호출 상태","추천 이유");
  final DefaultTableModel changes=model("변경","경로","메서드","입력 차이");
  final JComboBox<String> before=new JComboBox<>(),after=new JComboBox<>();
  List<JsVersions.Version> selectedVersions=List.of();
  Map<String,String> changeLabels=Map.of();
  private boolean refreshing;
  JsResultsView(JsAnalysisPanel owner,ResultTabs tabs){
    super(new BorderLayout(0,2));this.owner=owner;setName("wb.js.list.workspace");add(tabs);
    summary.setName("wb.js.selection.summary");shortcut.setName("wb.js.selection.shortcut");
    summary.putClientProperty("wb.font.size",12);shortcut.putClientProperty("wb.font.size",11);
    JPanel line=new JPanel(new BorderLayout(8,0));line.setName("wb.js.selection.line");line.setBorder(BorderFactory.createEmptyBorder(0,8,0,6));line.add(summary);line.add(shortcut,BorderLayout.EAST);add(line,BorderLayout.SOUTH);
    count.setName("wb.js.filter.count");count.putClientProperty("wb.font.size",11);tabs.extras.add(count);tabs.extras.add(filter);
    JPanel menu=new JPanel(new GridLayout(0,1,0,5));menu.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));menu.add(new JLabel("목록 필터"));menu.add(WorkbenchUi.row(new JLabel("호출 상태"),call));menu.add(priority);menu.add(fresh);JButton reset=new JButton("필터 초기화");reset.setName("wb.js.filter.reset");menu.add(reset);popup.add(menu);
    filter.addActionListener(e->{WorkbenchUi.style(popup);popup.show(filter,Math.min(0,filter.getWidth()-260),filter.getHeight());});
    call.addActionListener(e->apply());priority.addItemListener(e->apply());fresh.addItemListener(e->apply());reset.addActionListener(e->{call.setSelectedIndex(0);priority.setSelected(false);fresh.setSelected(false);apply();});
    comparison=new TargetWindow(owner,"트래픽 대조",()->tablePanel(traffic,"수집된 패킷의 메서드·필드 이름 근거 · 추가 요청 없음"));
    versionWindow=new TargetWindow(owner,"버전 비교",this::versionContent);
    before.addActionListener(e->compareChosen());after.addActionListener(e->compareChosen());
  }
  static DefaultTableModel model(String... columns){return new DefaultTableModel(columns,0){public boolean isCellEditable(int row,int col){return false;}};}
  static JPanel tablePanel(DefaultTableModel model,String text){JPanel p=new JPanel(new BorderLayout(0,6));JLabel hint=new JLabel(text);hint.setToolTipText(text);p.add(hint,BorderLayout.NORTH);JTable table=new JTable(model);table.setAutoCreateRowSorter(true);table.setRowHeight(26);JScrollPane scroll=new JScrollPane(table);scroll.setColumnHeaderView(table.getTableHeader());p.add(scroll);for(int i=0;i<table.getColumnCount();i++){String name=table.getColumnName(i);table.getColumnModel().getColumn(i).setPreferredWidth(name.contains("경로")?430:name.contains("추천")||name.contains("차이")?260:110);}p.setPreferredSize(new Dimension(850,430));WorkbenchUi.style(p);return p;}
  void configureTable(){
    JTable t=owner.urlTable;t.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
    for(int view=t.getColumnCount()-1;view>=0;view--){int model=t.convertColumnIndexToModel(view);if(model>=2&&model<=6)t.removeColumn(t.getColumnModel().getColumn(view));}
    int[] widths={260,72,145,85};for(int i=0;i<t.getColumnCount();i++){var c=t.getColumnModel().getColumn(i);c.setPreferredWidth(widths[i]);c.setMinWidth(i==1?60:i==3?65:90);}
  }
  String callState(JsAnalysis.Endpoint endpoint,Map<String,Set<String>> observed){
    var fact=owner.trafficIndex.get(endpoint.url());if(fact==null){String safe=ActivityTracker.safeUrl(endpoint.url());Set<String> methods=safe==null?null:observed.get(safe);if(methods!=null&&!methods.isEmpty())fact=new JsTrafficIndex.Entry(methods,Set.of(),Set.of(),1);}
    String state=JsTrafficIndex.state(endpoint,fact);return fact==null&&owner.trafficIndex.evicted>0&&state.equals("JS에서만 발견")?"관찰 범위 미확인":state;
  }
  void refresh(){
    var r=owner.currentResult();if(r==null){summary.setText("경로를 선택하세요");count.setText("0 / 0");return;}
    String versionHash=owner.versionHashes.get(owner.currentSourceKey());
    changeLabels=versionHash==null?owner.versions.latestChanges(owner.currentSourceKey()):owner.versions.changesFor(owner.currentSourceKey(),versionHash);
    Map<String,Set<String>> observed=Map.of();
    refreshing=true;
    try{for(int i=0;i<r.endpoints().size()&&i<owner.endpoints.getRowCount();i++){var e=r.endpoints().get(i);String state=callState(e,observed),change=changeLabels.getOrDefault(versionKey(e),"기준 없음");if(!Objects.equals(owner.endpoints.getValueAt(i,7),state))owner.endpoints.setValueAt(state,i,7);if(!Objects.equals(owner.endpoints.getValueAt(i,8),change))owner.endpoints.setValueAt(change,i,8);}}
    finally{refreshing=false;}apply();
  }
  static String versionKey(JsAnalysis.Endpoint e){return e.url()+(e.kind().contains("GraphQL")?" | "+e.kind().substring(e.kind().indexOf("GraphQL")):"");}
  boolean eligible(int row){var r=owner.currentResult();if(r==null||row<0||row>=r.endpoints().size())return false;var e=r.endpoints().get(row);String state=Objects.toString(owner.endpoints.getValueAt(row,7),"");
    boolean stateOk=switch(call.getSelectedIndex()){case 1->state.equals("JS에서만 발견");case 2->state.equals("실제 호출");case 3->state.equals("메서드 충돌");case 4->state.contains("미확정")||state.contains("미해결")||state.contains("미확인");default->true;};
    return stateOk&&(!priority.isSelected()||!JsTrafficIndex.reasons(e,owner.trafficIndex.get(e.url())).isEmpty())&&(!fresh.isSelected()||"신규".equals(owner.endpoints.getValueAt(row,8)));
  }
  void apply(){
    if(refreshing)return;
    @SuppressWarnings("unchecked") TableRowSorter<DefaultTableModel> sorter=(TableRowSorter<DefaultTableModel>)owner.urlTable.getRowSorter();
    sorter.setRowFilter(new RowFilter<>(){public boolean include(Entry<? extends DefaultTableModel,? extends Integer> entry){return eligible(entry.getIdentifier());}});
    count.setText(owner.urlTable.getRowCount()+" / "+owner.endpoints.getRowCount());
    boolean active=call.getSelectedIndex()>0||priority.isSelected()||fresh.isSelected();filter.putClientProperty("wb.icon.active",active);filter.setBorder(BorderFactory.createLineBorder(active?WorkbenchUi.accent():WorkbenchUi.line()));
    filter.setToolTipText("목록 필터 · "+count.getText()+(active?" · 조건 적용 중":""));selection();owner.refreshActions();
  }
  void selection(){var r=owner.currentResult();int row=owner.urlTable.getSelectedRow();if(r==null||row<0||owner.results.getSelectedIndex()!=0){summary.setText("경로를 선택하세요");summary.setToolTipText(null);return;}
    var e=r.endpoints().get(owner.urlTable.convertRowIndexToModel(row));List<String> reasons=JsTrafficIndex.reasons(e,owner.trafficIndex.get(e.url()));String reason=reasons.isEmpty()?"일반 검토":compactReason(e,reasons.get(0));
    String source=JsVersions.base(owner.currentSourceKey());int slash=source.lastIndexOf('/');String shortSource=slash>=0?source.substring(slash+1):source;
    String line=reason+" · "+Objects.toString(owner.endpoints.getValueAt(owner.urlTable.convertRowIndexToModel(row),7),"").replace("JS에서만 발견","미호출")+" | "+shortSource+":"+e.line();summary.setText(line);summary.setToolTipText(JsFindingGroups.evidence(r,e)+"\n"+String.join(" · ",reasons));
  }
  static String compactReason(JsAnalysis.Endpoint e,String full){String url=e.url().toLowerCase(Locale.ROOT);if(url.matches(".*(?:admin|role|permission).*"))return "권한 관련 경로";if(url.matches(".*(?:upload|download|file).*"))return "파일 관련 경로";if(url.matches(".*(?:export|import).*"))return "데이터 입출력 경로";if(full.startsWith("응답 민감"))return "응답 민감 필드";if(full.contains("객체/계정"))return "객체·계정 식별자";return full.length()>18?full.substring(0,17)+"…":full;}
  void openTraffic(){refresh();traffic.setRowCount(0);var r=owner.currentResult();if(r!=null)for(int i=0;i<r.endpoints().size();i++){var e=r.endpoints().get(i);var fact=owner.trafficIndex.get(e.url());traffic.addRow(new Object[]{e.url(),e.method(),fact==null?"미관측":String.join(", ",fact.methods()),owner.endpoints.getValueAt(i,7),String.join(" · ",JsTrafficIndex.reasons(e,fact))});}comparison.closeWindow();comparison.open();}
  JPanel versionContent(){selectedVersions=owner.versions.forSource(owner.currentSourceKey());before.removeAllItems();after.removeAllItems();for(var v:selectedVersions){String title=v.time().substring(0,19).replace('T',' ')+" · "+v.fingerprint().substring(0,Math.min(8,v.fingerprint().length()));before.addItem(title);after.addItem(title);}if(selectedVersions.size()>=2){before.setSelectedIndex(selectedVersions.size()-2);after.setSelectedIndex(selectedVersions.size()-1);}compareChosen();JPanel p=tablePanel(changes,selectedVersions.size()<2?"같은 출처의 다른 분석 버전이 필요합니다. · 이전 이력도 불러올 수 있습니다.":"같은 출처의 API·메서드·입력 이름을 비교합니다. · 값은 표시하지 않습니다.");JPanel header=new JPanel(new BorderLayout());header.add(WorkbenchUi.row(new JLabel("이전"),before,new JLabel("현재"),after));p.add(header,BorderLayout.NORTH);return p;}
  void compareChosen(){changes.setRowCount(0);int a=before.getSelectedIndex(),b=after.getSelectedIndex();if(a<0||b<0||a>=selectedVersions.size()||b>=selectedVersions.size()||a==b)return;for(var c:JsVersions.compare(selectedVersions.get(a),selectedVersions.get(b)))changes.addRow(new Object[]{c.state(),c.url(),c.methods(),c.fields()});}
  void close(){comparison.close();versionWindow.close();}
}
