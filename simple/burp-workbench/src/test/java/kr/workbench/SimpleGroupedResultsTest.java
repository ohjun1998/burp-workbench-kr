package kr.workbench;

import java.awt.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import javax.swing.table.*;
import javax.swing.tree.*;

/** Shared result grouping composes view filters without changing any original row values. */
public final class SimpleGroupedResultsTest extends WorkbenchTest {
  private static final class Fixture {
    final DefaultTableModel model=new DefaultTableModel(new String[]{"ID","URL","group","HTTP"},0);
    final JTable table=new JTable(model);
    final TableRowSorter<TableModel> sorter;
    final JTextField search;
    final JComponent grouped;
    final JTree tree;
    Fixture(){this("");}
    Fixture(String initialQuery){
      model.addRow(new Object[]{"a","https://grouped.test/check_balance/1","business",200});
      model.addRow(new Object[]{"b","https://grouped.test/check_balance/2","business",200});
      model.addRow(new Object[]{"c","https://grouped.test/login","account",401});
      model.addRow(new Object[]{"d","https://grouped.test/api/docs/","docs",404});
      sorter=new TableRowSorter<>(model);table.setRowSorter(sorter);search=CompactWorkbenchUi.search(table,"grouped.search","검색");
      int[] widths={90,260,120,100};
      for(int index=0;index<widths.length;index++){
        table.getColumnModel().getColumn(index).setPreferredWidth(widths[index]);
        table.getColumnModel().getColumn(index).setWidth(widths[index]);
      }
      search.setText(initialQuery);
      if(initialQuery.isEmpty())table.setRowSelectionInterval(1,1);
      grouped=GroupedResults.attach(new JScrollPane(table),"grouped.test",row->{
        String group=String.valueOf(model.getValueAt(row,2));
        String label=group.equals("business")?"잔액 조회":group.equals("account")?"로그인":"API 문서 경로";
        return new GroupedResults.Item(String.valueOf(model.getValueAt(row,0)),group,ResultLabels.groupLabel(group),group,label,String.valueOf(model.getValueAt(row,1)));
      },1);
      tree=(JTree)named(grouped,"grouped.test.tree");
    }
    String selected(){int row=table.getSelectedRow();return row<0?"":String.valueOf(model.getValueAt(table.convertRowIndexToModel(row),0));}
    TreePath path(String caption){
      var root=(DefaultMutableTreeNode)tree.getModel().getRoot();var nodes=root.depthFirstEnumeration();
      while(nodes.hasMoreElements()){var node=(DefaultMutableTreeNode)nodes.nextElement();if(node.toString().equals(caption))return new TreePath(node.getPath());}
      throw new AssertionError("No node: "+caption);
    }
  }
  private static Component named(Component component,String name){
    if(name.equals(component.getName()))return component;
    if(component instanceof Container parent)for(Component child:parent.getComponents()){Component found=named(child,name);if(found!=null)return found;}
    return null;
  }
  private static void flush()throws Exception{edt(()->{});edt(()->{});}
  public static void main(String[] args)throws Exception{
    Fixture[] holder={null};edt(()->holder[0]=new Fixture());Fixture f=holder[0];flush();
    Object columnModel=f.table.getColumnModel();int modelListeners=f.model.getTableModelListeners().length;
    AtomicInteger selectionEvents=new AtomicInteger();
    edt(()->{
      check(f.table.getModel()==f.model&&f.table.getRowSorter()==f.sorter&&f.table.getColumnModel()==columnModel,"grouping retains original model, sorter and column model");
      check(f.selected().equals("b"),"initial selected stable row ID remains selected");
      check(f.model.getValueAt(0,1).equals("https://grouped.test/check_balance/1"),"URL values stay exact in the original table model");
      var renderer=f.table.getCellRenderer(0,1);var label=(JLabel)renderer.getTableCellRendererComponent(f.table,f.table.getValueAt(0,1),false,false,0,1);
      check(label.getText().equals("잔액 조회")&&label.getToolTipText().contains("check_balance/1"),"URL renderer shows the short function while retaining the original tooltip");
      f.tree.setSelectionPath(f.path("잔액 조회"));
      check(f.table.getRowCount()==2&&f.selected().equals("b"),"function selection filters only the view and retains the selected ID");
      f.table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting())selectionEvents.incrementAndGet();});
    });
    flush();
    edt(()->f.sorter.setSortKeys(List.of(new RowSorter.SortKey(0,SortOrder.DESCENDING))));flush();
    edt(()->{
      check(f.selected().equals("b"),"sorting retains selected original row identity");
      f.tree.collapsePath(f.path("핵심 업무"));
      check(f.table.getRowCount()==2,"collapsing the selected function parent keeps its view filter");
      f.model.setValueAt(201,0,3);
    });flush();
    edt(()->{
      check(!f.tree.isExpanded(f.path("핵심 업무")),"collapsed parent state survives a model update");
      check(f.table.getRowCount()==2&&f.selected().equals("b"),"model refresh retains function filtering and selected ID");
      f.sorter.setRowFilter(new RowFilter<>(){public boolean include(Entry<? extends TableModel,? extends Integer> row){return row.getValue(0).equals("a");}});
    });flush();
    edt(()->{
      check(f.table.getRowCount()==1&&f.table.getValueAt(0,0).equals("a"),"an externally replaced RowFilter composes with the group filter");
      f.sorter.setRowFilter(null);
    });flush();
    edt(()->{
      check(f.table.getRowCount()==2,"clearing the external filter keeps the active function filter");
      GroupedResults.revealRow(f.table,2);
      check(f.table.convertRowIndexToView(2)>=0&&f.table.getRowCount()==4,"programmatic reveal clears only group filtering");
      f.search.setText("로그인");
      check(f.table.getRowCount()==1&&f.table.getValueAt(0,0).equals("c"),"existing literal search also finds the short Korean function name");
      f.search.setText("");
    });flush();
    edt(()->{
      f.tree.setSelectionPath(f.path("로그인"));check(f.table.getRowCount()==1,"account function selects its original row");
      f.model.removeRow(2);
    });flush();
    edt(()->{
      check(f.table.getRowCount()==3,"a replacement dataset without the selected function resets to all rows");
      JComponent again=GroupedResults.attach(new JScrollPane(f.table),"grouped.test",row->null,1);
      check(again==f.grouped&&f.model.getTableModelListeners().length==modelListeners,"reattaching the same table reuses one grouping controller and its listeners");
      check(SwingUtilities.isDescendingFrom(f.table,f.grouped),"reattachment keeps the original table inside the reused wrapper");
      check(f.model.getRowCount()==3&&f.model.getValueAt(0,0).equals("a"),"grouping never rewrites or removes original model rows");
    });
    // A repeated paint/sort notification must not clear an unchanged selection.
    JTextArea reader=new JTextArea("retained packet body");Object readerDocument=reader.getDocument();
    edt(()->{
      f.table.setRowSelectionInterval(0,0);selectionEvents.set(0);reader.setCaretPosition(8);
      f.table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()){reader.setText(f.selected());reader.setCaretPosition(0);}});
      f.sorter.sort();
    });flush();
    edt(()->{
      check(selectionEvents.get()==0,"unchanged group refresh does not emit an artificial empty selection to owner readers");
      check(reader.getDocument()==readerDocument&&reader.getCaretPosition()==8&&reader.getText().equals("retained packet body"),"unchanged refresh preserves the owner's packet document and reading position");
      Fixture retained=new Fixture("login");
      check(retained.table.getRowCount()==1,"an existing literal search remains applied when grouping attaches");
      retained.search.setText("잔액");
      check(retained.table.getRowCount()==2,"changing a search that predates grouping replaces its old literal condition");
      retained.sorter.setRowFilter(new RowFilter<>(){public boolean include(Entry<? extends TableModel,? extends Integer> row){return row.getValue(3).equals(200);}});
      retained.search.setText("로그인");
      check(retained.table.getRowCount()==0,"a genuine external filter stays AND-composed with an alias search");
      retained.search.setText("");
      check(retained.table.getRowCount()==2,"clearing alias search preserves the genuine external filter");
      DefaultTableModel duplicateModel=new DefaultTableModel(new Object[][]{{"x","/api/docs/"},{"y","/v3/api-docs"}},new String[]{"ID","URL"});
      JTable duplicates=new JTable(duplicateModel);
      JComponent duplicateGroups=GroupedResults.attach(new JScrollPane(duplicates),"duplicates",row->new GroupedResults.Item(
          String.valueOf(duplicateModel.getValueAt(row,0)),"docs","문서 탐색",String.valueOf(duplicateModel.getValueAt(row,1)),"API 문서 경로",String.valueOf(duplicateModel.getValueAt(row,1))),1);
      JTree duplicateTree=(JTree)named(duplicateGroups,"duplicates.tree");
      DefaultMutableTreeNode duplicateRoot=(DefaultMutableTreeNode)duplicateTree.getModel().getRoot();
      DefaultMutableTreeNode docs=(DefaultMutableTreeNode)duplicateRoot.getChildAt(1);
      String first=docs.getChildAt(0).toString(),second=docs.getChildAt(1).toString();
      check(!first.equals(second)&&first.matches("API 문서 경로 · [0-9a-f]{6}")&&second.matches("API 문서 경로 · [0-9a-f]{6}"),"different function keys sharing a label receive stable visible suffixes");
      check(duplicateModel.getValueAt(0,1).equals("/api/docs/")&&duplicateModel.getValueAt(1,1).equals("/v3/api-docs"),"label disambiguation never merges or changes original function rows");
    });
    edt(()->{
      String selectedId=f.selected();
      WorkbenchUi.style(f.grouped);
      JSplitPane split=(JSplitPane)named(f.grouped,"grouped.test.split");
      f.grouped.setSize(400,520);for(int pass=0;pass<4;pass++)UiLayoutTest.layout(f.grouped);
      check(split.getOrientation()==JSplitPane.VERTICAL_SPLIT&&split.getDividerLocation()>=140&&split.getDividerLocation()<=160,
          "a narrow tall result card places its groups above the full-width table");
      JScrollPane scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,f.table);
      check(scroll.getViewport().getExtentSize().width>=370&&scroll.getViewport().getExtentSize().height>=250,
          "the400px result card gives actual result columns a readable full-width viewport");
      f.grouped.setSize(300,150);for(int pass=0;pass<4;pass++)UiLayoutTest.layout(f.grouped);
      check(split.getOrientation()==JSplitPane.HORIZONTAL_SPLIT&&scroll.getViewport().getExtentSize().height>=100,
          "a short replay result card keeps at least100px of table viewport instead of stacking groups");
      f.table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
      for(int pass=0;pass<4;pass++)UiLayoutTest.layout(f.grouped);
      check(f.table.getAutoResizeMode()==JTable.AUTO_RESIZE_OFF&&scroll.getHorizontalScrollBar().isVisible(),
          "later host resize settings cannot compress original columns into unreadable ellipses");
      boolean readable=true;
      FontMetrics metrics=f.table.getTableHeader().getFontMetrics(f.table.getTableHeader().getFont());
      for(int index=0;index<f.table.getColumnCount();index++){
        TableColumn column=f.table.getColumnModel().getColumn(index);
        readable&=column.getWidth()>=metrics.stringWidth(String.valueOf(column.getHeaderValue()))+12;
      }
      check(readable,"all actual fixture headers retain their readable text width inside the narrow scrollable table");
      f.table.getColumnModel().getColumn(1).setPreferredWidth(333);f.table.getColumnModel().getColumn(1).setWidth(333);
      f.grouped.setSize(700,400);for(int pass=0;pass<4;pass++)UiLayoutTest.layout(f.grouped);
      check(split.getOrientation()==JSplitPane.HORIZONTAL_SPLIT&&split.getDividerLocation()==218,
          "a wide workspace restores the left218px group navigation");
      check(f.table.getColumnModel()==columnModel&&f.table.getColumnModel().getColumn(1).getPreferredWidth()==333
          &&f.table.getColumnModel().getColumn(1).getWidth()==333&&f.selected().equals(selectedId),
          "responsive transitions preserve authored column widths, original columns and selected row ID");
    });
    System.out.println("SimpleGroupedResultsTest: "+checks+" checks passed (view-only groups, external filter composition and stable selection)");
  }
}
