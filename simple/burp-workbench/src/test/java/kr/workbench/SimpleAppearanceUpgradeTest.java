package kr.workbench;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;
import javax.swing.text.Document;

/** Load a profile saved before the 1.4.20 presentation wrappers and header replacement. */
public final class SimpleAppearanceUpgradeTest extends WorkbenchTest {
  private static final String PROJECT="11111111-1111-4111-8111-111111111111";
  private static final class Store implements WorkspaceAppearance.Storage {
    String value;
    public String project(){return PROJECT;}
    public String read(){return value;}
    public void write(String value){this.value=value;}
  }
  private record Saved(String key,List<Integer> order,List<Integer> widths){}
  private static List<Integer> order(JTable table){
    List<Integer> values=new ArrayList<>();
    for(int i=0;i<table.getColumnCount();i++)values.add(table.getColumnModel().getColumn(i).getModelIndex());
    return values;
  }
  private static List<Integer> widths(JTable table){
    List<Integer> values=new ArrayList<>();
    for(int i=0;i<table.getColumnCount();i++)values.add(table.getColumnModel().getColumn(i).getWidth());
    return values;
  }
  private static Saved saveOriginal(Store store,JPanel root,JTable table){
    WorkspaceAppearance original=new WorkspaceAppearance(store,root,true,null);
    try{
      table.moveColumn(table.getColumnCount()-1,0);
      for(int i=0;i<table.getColumnCount();i++){
        var column=table.getColumnModel().getColumn(i);
        int width=i==1?333:111+i*37;column.setPreferredWidth(width);column.setWidth(width);
      }
      original.capture();
      Saved saved=new Saved(String.valueOf(table.getClientProperty("wb.appearance.id")),order(table),widths(table));
      check(WorkspaceAppearance.decode(store.value).columns().get(saved.key()).widths().contains(333),
          "the pre-upgrade hierarchy actually persists a custom333px column");
      return saved;
    }finally{original.close();}
  }
  private static void resetConstructionColumns(JTable table){
    List<Integer> canonical=new ArrayList<>(order(table));canonical.sort(Integer::compareTo);
    for(int i=0;i<canonical.size();i++)table.moveColumn(table.convertColumnIndexToView(canonical.get(i)),i);
    for(int i=0;i<table.getColumnCount();i++){
      var column=table.getColumnModel().getColumn(i);column.setPreferredWidth(75);column.setWidth(75);
    }
    table.putClientProperty("wb.appearance.columns.restored",false);
  }
  private static void restoreUpgrade(Store store,JPanel root,JTable table,Saved saved,TableModel model,String label){
    WorkspaceAppearance upgraded=new WorkspaceAppearance(store,root,true,null);
    try{
      check(saved.key().equals(table.getClientProperty("wb.appearance.id")),label+" retains its pre-upgrade persistence key");
      check(order(table).equals(saved.order())&&widths(table).equals(saved.widths()),
          label+" restores old custom widths and column order after construction values were reset");
      for(boolean dark:new boolean[]{false,true}){
        kr.packetui.VisualTheme.setDarkOverride(dark);WorkbenchUi.style(root);
        check(table.getModel()==model&&order(table).equals(saved.order())&&widths(table).equals(saved.widths()),
            label+" theme changes preserve the exact original model and restored columns");
      }
    }finally{upgraded.close();kr.packetui.VisualTheme.setDarkOverride(null);WorkbenchUi.setAppearance(100,false);}
  }
  private static JTable table(String name){
    JTable table=new JTable(new DefaultTableModel(new Object[][]{{"a","b","c"},{"d","e","f"}},new String[]{"one","two","three"}));
    table.setName(name);table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);return table;
  }
  private static void mapper(){
    JPanel root=new JPanel(new BorderLayout()),retained=new JPanel(new BorderLayout()),header=new JPanel();
    header.setName("fm.workflow.tree.header");retained.add(header,BorderLayout.NORTH);
    JTree tree=new JTree();tree.setName("fm.feature.tree");var treeModel=tree.getModel();retained.add(new JScrollPane(tree));
    JToggleButton unclassified=new JToggleButton("미분류");unclassified.setName("fm.unclassified");retained.add(unclassified,BorderLayout.SOUTH);
    JTable table=table("fm.upgrade.packets");TableModel model=table.getModel();table.setRowSelectionInterval(1,1);
    JSplitPane navigation=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,retained,new JScrollPane(table));navigation.setName("fm.navigation.split");root.add(navigation);
    int treeSlot=navigation.getComponentZOrder(retained),rightSlot=navigation.getComponentZOrder(navigation.getRightComponent());
    Store store=new Store();Saved saved=saveOriginal(store,root,table);resetConstructionColumns(table);
    ApprovedMapperDesign.install(root);
    check(navigation.getComponentZOrder(navigation.getLeftComponent())==treeSlot
        &&navigation.getComponentZOrder(navigation.getRightComponent())==rightSlot,
        "the actual Mapper wrapper occupies its old slot without shifting the right table subtree");
    check(SwingUtilities.isDescendingFrom(retained,navigation.getLeftComponent())&&tree.getModel()==treeModel,
        "the actual Mapper wrapper retains its original panel and tree model");
    restoreUpgrade(store,root,table,saved,model,"Mapper");
    check(table.getSelectedRow()==1,"Mapper wrapping and restoration preserve the selected original row");
  }
  private static void roundedCard(){
    JPanel root=new JPanel(new BorderLayout()),card=new JPanel(new BorderLayout());
    JTable table=table("wb.upgrade.card.table");TableModel model=table.getModel();card.add(new JScrollPane(table));
    root.add(new JLabel("before"),BorderLayout.NORTH);root.add(card);JLabel after=new JLabel("after");root.add(after,BorderLayout.SOUTH);
    int cardSlot=root.getComponentZOrder(card),afterSlot=root.getComponentZOrder(after);
    Store store=new Store();Saved saved=saveOriginal(store,root,table);resetConstructionColumns(table);
    JComponent layer=ApprovedUi.roundedLegacy(card);
    check(layer instanceof JLayer<?> && root.getComponentZOrder(layer)==cardSlot&&root.getComponentZOrder(after)==afterSlot,
        "the actual rounded layer replaces the old card in its original sibling slot");
    restoreUpgrade(store,root,table,saved,model,"Rounded card");
  }
  private static void response(){
    ResponseReconPanel page=new ResponseReconPanel(SimpleWorkflowTest.previewApi());
    try{
      JSplitPane split=(JSplitPane)ApprovedUi.find(page,"wb.response.finding.split");JPanel list=(JPanel)split.getLeftComponent();
      BorderLayout layout=(BorderLayout)list.getLayout();JTextField search=(JTextField)layout.getLayoutComponent(BorderLayout.NORTH);
      Component scroll=layout.getLayoutComponent(BorderLayout.CENTER);int searchSlot=list.getComponentZOrder(search),scrollSlot=list.getComponentZOrder(scroll);
      search.setText("retained query");search.select(1,5);Document document=search.getDocument();TableModel model=page.findingTable.getModel();
      Store store=new Store();Saved saved=saveOriginal(store,page,page.findingTable);resetConstructionColumns(page.findingTable);
      ApprovedReadingDesign.response(page);
      check(list.getComponentZOrder(layout.getLayoutComponent(BorderLayout.NORTH))==searchSlot&&list.getComponentZOrder(scroll)==scrollSlot,
          "the actual response header replaces the old search slot without shifting its table subtree");
      restoreUpgrade(store,page,page.findingTable,saved,model,"Response findings");
      check(search.getDocument()==document&&search.getText().equals("retained query")&&search.getSelectionStart()==1&&search.getSelectionEnd()==5,
          "response header replacement and themes preserve the original search document and selection");
    }finally{page.close();}
  }
  public static void main(String[] args)throws Exception {
    BountyFeaturesTest.factory();int sent=PayloadIntegrationTest.sent.get();
    edt(()->{mapper();roundedCard();response();});
    check(PayloadIntegrationTest.sent.get()==sent,"appearance upgrade, restored columns and theme changes send zero target HTTP");
    System.out.println("SimpleAppearanceUpgradeTest: "+checks+" checks passed (pre-upgrade profiles, actual presentation helpers, zero HTTP)");
  }
}
