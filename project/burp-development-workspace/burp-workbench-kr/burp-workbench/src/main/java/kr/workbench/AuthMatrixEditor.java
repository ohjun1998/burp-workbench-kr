package kr.workbench;

import java.awt.*;
import java.net.URI;
import java.util.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Editable policy table in the existing settings window; text rules remain an advanced view. */
final class AuthMatrixEditor extends JPanel {
  final AuthComparePanel owner;
  final DefaultTableModel model=new DefaultTableModel(new String[]{"메서드","경로","계정 1","계정 2","계정 3","비로그인"},0);
  final JTable table=new AuthLayout.Table(model);
  final JLabel feedback=new JLabel("허용·차단·미지정은 실행 당시 설정으로 보관됩니다.");
  boolean loading,tableDirty;
  AuthMatrixEditor(AuthComparePanel owner){
    super(new BorderLayout(6,6));this.owner=owner;setName("wb.auth.policy.editor");table.setName("wb.auth.policy.table");
    table.putClientProperty("wb.table.compact",true);table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    for(int i=2;i<6;i++)table.getColumnModel().getColumn(i).setCellEditor(new DefaultCellEditor(new JComboBox<>(new String[]{"미지정","허용","차단"})));
    table.getColumnModel().getColumn(0).setPreferredWidth(75);table.getColumnModel().getColumn(1).setPreferredWidth(250);for(int i=2;i<6;i++)table.getColumnModel().getColumn(i).setPreferredWidth(120);
    JTabbedPane tabs=new JTabbedPane();JScrollPane scroll=new JScrollPane(table);scroll.setPreferredSize(new Dimension(680,185));tabs.addTab("권한 표",scroll);tabs.addTab("규칙 원문",new JScrollPane(owner.matrixRules));
    add(WorkbenchUi.row(owner.button("선택 요청 추가",this::selected),owner.button("행 추가",()->{owner.editable();model.addRow(new Object[]{"GET","/path","미지정","미지정","미지정","미지정"});}),owner.button("선택 행 삭제",()->{owner.editable();int row=table.getSelectedRow();if(row>=0)model.removeRow(table.convertRowIndexToModel(row));})),BorderLayout.NORTH);
    add(tabs);add(feedback,BorderLayout.SOUTH);
    model.addTableModelListener(e->{if(!loading){tableDirty=true;try{owner.editable();write();}catch(IllegalArgumentException ex){feedback.setText(ex.getMessage());}}});
    owner.matrixRules.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){void change(){if(!loading){tableDirty=false;try{load();}catch(IllegalArgumentException ex){feedback.setText(ex.getMessage());}}}public void insertUpdate(javax.swing.event.DocumentEvent e){change();}public void removeUpdate(javax.swing.event.DocumentEvent e){change();}public void changedUpdate(javax.swing.event.DocumentEvent e){change();}});
  }
  static String show(BountyMatrix.Expected value){return switch(value){case ALLOW->"허용";case DENY->"차단";case UNKNOWN->"미지정";};}
  static String parse(Object value){return switch(Objects.toString(value,"")){case "허용","ALLOW"->"ALLOW";case "차단","DENY"->"DENY";case "미지정","UNKNOWN"->"UNKNOWN";default->throw new IllegalArgumentException("권한은 허용·차단·미지정 중 선택하세요.");};}
  void refreshNames(){for(int i=0;i<4;i++)table.getColumnModel().getColumn(i+2).setHeaderValue(i==3?"비로그인":owner.label(i));table.getTableHeader().repaint();}
  void load(){refreshNames();var parsed=BountyMatrix.parse(owner.matrixRules.getText());loading=true;try{model.setRowCount(0);for(var rule:parsed.rules){Object[] row=new Object[6];row[0]=rule.method();row[1]=rule.path();for(int i=0;i<4;i++)row[i+2]=show(rule.slots().get(i));model.addRow(row);}}finally{loading=false;}}
  void write(){StringBuilder out=new StringBuilder();for(int row=0;row<model.getRowCount();row++){out.append(Objects.toString(model.getValueAt(row,0),"").strip()).append(' ').append(Objects.toString(model.getValueAt(row,1),"").strip());for(int i=2;i<6;i++)out.append(' ').append(parse(model.getValueAt(row,i)));out.append('\n');}BountyMatrix.parse(out.toString());loading=true;try{owner.matrixRules.setText(out.toString());tableDirty=false;feedback.setText("권한 표 반영 · 다음 비교부터 적용");}finally{loading=false;}}
  BountyMatrix snapshot(){if(table.isEditing()&&!table.getCellEditor().stopCellEditing())throw new IllegalArgumentException("권한 표 편집을 완료하세요.");if(tableDirty)write();return BountyMatrix.parse(owner.matrixRules.getText());}
  void selected(){owner.editable();for(var rr:owner.selected.get()){String method=rr.request().method(),path=URI.create(rr.request().url()).getPath();boolean exists=false;for(int i=0;i<model.getRowCount();i++)if(method.equals(model.getValueAt(i,0))&&path.equals(model.getValueAt(i,1)))exists=true;if(!exists)model.addRow(new Object[]{method,path,"미지정","미지정","미지정","미지정"});}snapshot();}
}
