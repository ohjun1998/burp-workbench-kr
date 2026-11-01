package kr.workbench;

import java.awt.*;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import javax.swing.tree.*;

/** A presentation-only navigation around an existing result table and its sorter. */
final class GroupedResults extends JPanel {
  record Item(String rowId,String groupKey,String groupLabel,String functionKey,String label,String original) {
    Item {
      rowId=text(rowId);groupKey=text(groupKey);groupLabel=text(groupLabel);
      functionKey=text(functionKey);label=text(label);original=text(original);
    }
  }
  private record Node(String group,String function,String label,int count) {
    public String toString(){return label;}
  }
  private final JTable table;
  private final IntFunction<Item> projection;
  private final int[] shortColumns;
  private final JTree tree;
  private final JSplitPane split;
  private JScrollPane tableScroll;
  private final DefaultMutableTreeNode root=new DefaultMutableTreeNode("관측 그룹");
  private final DefaultTreeModel treeModel=new DefaultTreeModel(root);
  private final Map<String,Boolean> expanded=new LinkedHashMap<>();
  private final JTextArea originals=new JTextArea(4,30);
  private List<Item> snapshot=List.of();
  private DefaultRowSorter<TableModel,Integer> sorter;
  private RowFilter<? super TableModel,? super Integer> externalFilter;
  private TableModel observedModel;
  private String selectedGroup="",selectedFunction="";
  private boolean applying,rebuilding,queued,closed,collapsing,filterDirty=true;
  private final boolean customSearch;
  private final Runnable previousSearch;
  private final Runnable searchApply;
  private final TableModelListener modelListener=e->modelChanged();
  private final RowSorterListener sorterListener=e->{
    if(!applying&&e.getType()==RowSorterEvent.Type.SORTED){captureExternalFilter();schedule();}
  };
  private final PropertyChangeListener tableProperties=e->{
    if("model".equals(e.getPropertyName())){observeModel();schedule();}
    if("rowSorter".equals(e.getPropertyName())){observeSorter();schedule();}
    if("columnModel".equals(e.getPropertyName()))schedule();
    if("autoResizeMode".equals(e.getPropertyName()))preserveColumnWidths();
  };
  private final RowFilter<TableModel,Integer> combined=new RowFilter<>() {
    public boolean include(Entry<? extends TableModel,? extends Integer> row){
      int index=row.getIdentifier();Item item=item(index);
      return externalIncludes(row)&&queryIncludes(row,item)
          &&(selectedGroup.isEmpty()||selectedGroup.equals(item.groupKey()))
          &&(selectedFunction.isEmpty()||selectedFunction.equals(item.functionKey()));
    }
  };

  static JComponent attach(JScrollPane scroll,String name,IntFunction<Item> projection,int... shortColumns){
    if(!(scroll.getViewport().getView() instanceof JTable table))return scroll;
    if(table.getClientProperty("wb.grouped.results") instanceof GroupedResults existing){
      if(existing.tableScroll!=scroll){existing.tableScroll=scroll;existing.split.setRightComponent(scroll);}
      return existing;
    }
    return new GroupedResults(table,scroll,name,projection,shortColumns);
  }

  static void revealRow(JTable table,int modelRow){
    if(table.getClientProperty("wb.grouped.results") instanceof GroupedResults grouped
        &&modelRow>=0&&modelRow<table.getModel().getRowCount()&&table.convertRowIndexToView(modelRow)<0){
      grouped.selectedGroup="";grouped.selectedFunction="";grouped.filterDirty=true;grouped.refresh();
    }
  }

  private GroupedResults(JTable table,JScrollPane scroll,String name,IntFunction<Item> projection,int[] shortColumns){
    super(new BorderLayout(0,6));this.table=table;this.projection=projection;this.shortColumns=shortColumns.clone();
    setName(name+".groups");putClientProperty("wb.surface",true);table.putClientProperty("wb.grouped.results",this);tableScroll=scroll;
    tree=new JTree(treeModel){
      protected void paintComponent(Graphics g){setBackground(WorkbenchUi.surface());setForeground(WorkbenchUi.ink());setRowHeight(WorkbenchUi.displayPixels(36));super.paintComponent(g);}
      public void collapsePath(TreePath path){boolean previous=collapsing;collapsing=true;try{super.collapsePath(path);}finally{collapsing=previous;}}
    };
    tree.setName(name+".tree");tree.setRootVisible(false);tree.setShowsRootHandles(true);
    tree.setCellRenderer(new NavigationRenderer());tree.setRowHeight(WorkbenchUi.displayPixels(36));
    tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    tree.addTreeSelectionListener(e->{
      if(rebuilding||collapsing)return;
      Object value=tree.getLastSelectedPathComponent();
      if(value instanceof DefaultMutableTreeNode node&&node.getUserObject() instanceof Node n){
        selectedGroup=n.group();selectedFunction=n.function();filterDirty=true;applyFilter();
      }
    });
    tree.addTreeExpansionListener(new TreeExpansionListener(){
      public void treeExpanded(TreeExpansionEvent e){remember(e,true);}
      public void treeCollapsed(TreeExpansionEvent e){remember(e,false);}
      private void remember(TreeExpansionEvent e,boolean open){
        if(!rebuilding&&e.getPath().getLastPathComponent() instanceof DefaultMutableTreeNode node
            &&node.getUserObject() instanceof Node n&&!n.group().isEmpty()&&n.function().isEmpty())expanded.put(n.group(),open);
      }
    });
    JPanel navigation=new JPanel(new BorderLayout());navigation.putClientProperty("wb.surface",true);
    JLabel heading=new JLabel("그룹");heading.setBorder(BorderFactory.createEmptyBorder(5,8,5,4));
    JToggleButton toggle=new JToggleButton("원본 정보 보기");toggle.setName(name+".original.toggle");
    JPanel navigationHeader=new JPanel(new BorderLayout(4,0));navigationHeader.putClientProperty("wb.surface",true);
    navigationHeader.add(heading,BorderLayout.WEST);navigationHeader.add(toggle,BorderLayout.EAST);
    navigation.add(navigationHeader,BorderLayout.NORTH);navigation.add(new JScrollPane(tree));
    split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,navigation,scroll){
      private int mode=-1,horizontalDivider=218;
      private void adapt(){
        if(getWidth()<=0||getHeight()<=0)return;
        preserveColumnWidths();
        int next=getWidth()>=560?0:getHeight()>=300?1:2;
        if(next!=mode){
          if(mode==0&&getDividerLocation()>0)horizontalDivider=getDividerLocation();
          setOrientation(next==1?JSplitPane.VERTICAL_SPLIT:JSplitPane.HORIZONTAL_SPLIT);
          if(next==1)setDividerLocation(Math.min(160,Math.max(140,getHeight()-160)));
          else setDividerLocation(next==0?horizontalDivider:Math.min(horizontalDivider,Math.max(150,getWidth()/3)));
          mode=next;
        }else if(mode==1){
          int maximum=Math.min(160,getHeight()-160);
          if(getDividerLocation()>maximum)setDividerLocation(Math.max(140,maximum));
        }
      }
      public void doLayout(){adapt();super.doLayout();}
    };
    split.setName(name+".split");split.setResizeWeight(0);split.setDividerLocation(218);split.setDividerSize(8);
    navigation.setMinimumSize(new Dimension(150,50));scroll.setMinimumSize(new Dimension(120,50));
    add(split);
    originals.setName(name+".original");originals.setEditable(false);originals.setLineWrap(true);originals.setWrapStyleWord(true);
    JScrollPane originalScroll=new JScrollPane(originals);originalScroll.setVisible(false);
    toggle.addActionListener(e->{originalScroll.setVisible(toggle.isSelected());revalidate();});
    add(originalScroll,BorderLayout.SOUTH);
    table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!applying)showOriginal();});
    previousSearch=table.getClientProperty("wb.search.apply") instanceof Runnable run?run:null;
    customSearch=previousSearch==null;
    searchApply=()->{if(previousSearch!=null)previousSearch.run();captureExternalFilter();filterDirty=true;applyFilter();schedule();};
    table.putClientProperty("wb.search.apply",searchApply);
    observeModel();observeSorter();table.addPropertyChangeListener(tableProperties);
    preserveColumnWidths();
    refresh();
  }

  private static String text(String value){return value==null?"":value;}
  private void preserveColumnWidths(){
    if(table.getAutoResizeMode()!=JTable.AUTO_RESIZE_OFF)table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
  }
  private void modelChanged(){
    if(!selectedGroup.isEmpty()||!text((String)table.getClientProperty("wb.search.query")).isEmpty())filterDirty=true;
    schedule();
  }
  private Item item(int row){
    if(row>=0&&row<snapshot.size())return snapshot.get(row);
    return new Item("row-"+row,"other","기타 관측","row-"+row,"관측 항목","");
  }
  private void observeModel(){
    if(observedModel!=null)observedModel.removeTableModelListener(modelListener);
    observedModel=table.getModel();observedModel.addTableModelListener(modelListener);
  }
  @SuppressWarnings("unchecked") private void observeSorter(){
    if(sorter!=null)sorter.removeRowSorterListener(sorterListener);
    sorter=table.getRowSorter() instanceof DefaultRowSorter<?,?> current
        ?(DefaultRowSorter<TableModel,Integer>)current:null;
    externalFilter=sorter==null?null:sorter.getRowFilter();
    if(externalFilter==combined||(customSearch&&externalFilter==table.getClientProperty("wb.search.filter")))externalFilter=null;
    if(sorter!=null)sorter.addRowSorterListener(sorterListener);
  }
  private void captureExternalFilter(){
    if(sorter!=null&&sorter.getRowFilter()!=combined){
      externalFilter=sorter.getRowFilter();
      if(customSearch&&externalFilter==table.getClientProperty("wb.search.filter"))externalFilter=null;
      filterDirty=true;
    }
  }
  private boolean externalIncludes(RowFilter.Entry<? extends TableModel,? extends Integer> row){
    return externalFilter==null||externalFilter.include(row);
  }
  private boolean queryIncludes(RowFilter.Entry<? extends TableModel,? extends Integer> row,Item item){
    if(!customSearch)return true;
    String query=text((String)table.getClientProperty("wb.search.query")).toLowerCase(java.util.Locale.ROOT);
    if(query.isEmpty()||(item.label()+" "+item.groupLabel()+" "+item.original()).toLowerCase(java.util.Locale.ROOT).contains(query))return true;
    for(int column=0;column<row.getValueCount();column++)if(row.getStringValue(column).toLowerCase(java.util.Locale.ROOT).contains(query))return true;
    return false;
  }
  private RowFilter.Entry<TableModel,Integer> entry(int row){
    return new RowFilter.Entry<>(){
      public TableModel getModel(){return table.getModel();}
      public int getValueCount(){return getModel().getColumnCount();}
      public Object getValue(int index){return getModel().getValueAt(row,index);}
      public String getStringValue(int index){
        if(sorter instanceof TableRowSorter<?> tableSorter&&tableSorter.getStringConverter()!=null)
          return text(tableSorter.getStringConverter().toString(getModel(),row,index));
        Object value=getValue(index);return value==null?"":String.valueOf(value);
      }
      public Integer getIdentifier(){return row;}
    };
  }
  private void schedule(){if(closed||queued)return;queued=true;SwingUtilities.invokeLater(()->{queued=false;if(!closed)refresh();});}
  private void refresh(){
    if(closed)return;
    List<Item> next=new ArrayList<>();
    for(int row=0;row<table.getModel().getRowCount();row++){
      Item projected=projection.apply(row);next.add(projected==null?new Item("row-"+row,"other","기타 관측","row-"+row,"관측 항목",""):projected);
    }
    snapshot=List.copyOf(next);
    if(!selectedGroup.isEmpty()&&snapshot.stream().noneMatch(item->selectedGroup.equals(item.groupKey())
        &&(selectedFunction.isEmpty()||selectedFunction.equals(item.functionKey())))){
      selectedGroup="";selectedFunction="";filterDirty=true;
    }
    Map<String,List<Item>> groups=new LinkedHashMap<>();
    for(int row=0;row<snapshot.size();row++)if(externalIncludes(entry(row))&&queryIncludes(entry(row),item(row)))
      groups.computeIfAbsent(item(row).groupKey(),ignored->new ArrayList<>()).add(item(row));
    rebuilding=true;
    try{
      root.removeAllChildren();DefaultMutableTreeNode selection=null;
      int all=groups.values().stream().mapToInt(List::size).sum();
      DefaultMutableTreeNode allNode=new DefaultMutableTreeNode(new Node("","","전체 관측",all));root.add(allNode);
      if(selectedGroup.isEmpty())selection=allNode;
      for(var group:groups.entrySet()){
        List<Item> items=group.getValue();String label=items.get(0).groupLabel();
        DefaultMutableTreeNode parent=new DefaultMutableTreeNode(new Node(group.getKey(),"",label,items.size()));root.add(parent);
        if(selectedGroup.equals(group.getKey())&&selectedFunction.isEmpty())selection=parent;
        Map<String,List<Item>> functions=new LinkedHashMap<>();
        for(Item item:items)functions.computeIfAbsent(item.functionKey(),ignored->new ArrayList<>()).add(item);
        Map<String,Integer> labels=new LinkedHashMap<>();
        for(List<Item> function: functions.values())labels.merge(function.get(0).label(),1,Integer::sum);
        for(var function:functions.entrySet()){
          String caption=function.getValue().get(0).label();
          if(labels.get(caption)>1)caption+=" · "+suffix(group.getKey()+"|"+function.getKey());
          DefaultMutableTreeNode child=new DefaultMutableTreeNode(new Node(group.getKey(),function.getKey(),caption,function.getValue().size()));parent.add(child);
          if(selectedGroup.equals(group.getKey())&&selectedFunction.equals(function.getKey()))selection=child;
        }
      }
      treeModel.reload();tree.expandPath(new TreePath(root.getPath()));
      for(int i=1;i<root.getChildCount();i++){
        DefaultMutableTreeNode group=(DefaultMutableTreeNode)root.getChildAt(i);Node n=(Node)group.getUserObject();
        if(expanded.getOrDefault(n.group(),n.group().equals("business")||n.group().equals("account")||root.getChildCount()==2))tree.expandPath(new TreePath(group.getPath()));
      }
      boolean expands=tree.getExpandsSelectedPaths();tree.setExpandsSelectedPaths(false);
      try{if(selection!=null)tree.setSelectionPath(new TreePath(selection.getPath()));}finally{tree.setExpandsSelectedPaths(expands);}
    }finally{rebuilding=false;}
    installRenderers();applyFilter();showOriginal();
  }
  private Set<String> selectedIds(){
    Set<String> ids=new LinkedHashSet<>();
    for(int row:table.getSelectedRows()){int model=table.convertRowIndexToModel(row);if(model>=0&&model<snapshot.size())ids.add(item(model).rowId());}
    return ids;
  }
  private void applyFilter(){
    if(applying||closed)return;
    if(sorter==null&&table.getRowSorter()==null&&table.getModel().getRowCount()>0
        &&(!selectedGroup.isEmpty()||!text((String)table.getClientProperty("wb.search.query")).isEmpty())){
      table.setRowSorter(new TableRowSorter<>(table.getModel()));
    }
    if(sorter==null)return;
    captureExternalFilter();
    if(!filterDirty&&sorter.getRowFilter()==combined)return;
    Set<String> selected=selectedIds();applying=true;
    try{
      filterDirty=false;sorter.setRowFilter(combined);
      Set<String> wanted=new LinkedHashSet<>();
      for(int model=0;model<snapshot.size();model++)if(selected.contains(item(model).rowId())&&table.convertRowIndexToView(model)>=0)wanted.add(item(model).rowId());
      if(!selectedIds().equals(wanted)){
        boolean adjusting=table.getSelectionModel().getValueIsAdjusting();table.getSelectionModel().setValueIsAdjusting(true);
        try{
          table.clearSelection();
          for(int model=0;model<snapshot.size();model++)if(wanted.contains(item(model).rowId())){
            int view=table.convertRowIndexToView(model);if(view>=0)table.addRowSelectionInterval(view,view);
          }
        }finally{table.getSelectionModel().setValueIsAdjusting(adjusting);}
      }
    }finally{applying=false;}
    showOriginal();
  }
  private void installRenderers(){
    for(int model:shortColumns)for(int view=0;view<table.getColumnCount();view++){
      TableColumn column=table.getColumnModel().getColumn(view);
      if(column.getModelIndex()==model&&!(column.getCellRenderer() instanceof ShortRenderer))column.setCellRenderer(new ShortRenderer());
    }
  }
  private void showOriginal(){
    StringBuilder value=new StringBuilder();int displayed=0;
    for(int view:table.getSelectedRows()){
      int model=table.convertRowIndexToModel(view);if(model<0||model>=snapshot.size())continue;
      Item item=item(model);if(value.length()>0)value.append("\n\n");
      value.append(item.label()).append("\n관측 ID: ").append(item.rowId()).append("\n").append(item.original());
      if(++displayed==20){if(table.getSelectedRowCount()>20)value.append("\n\n외 ").append(table.getSelectedRowCount()-20).append("건 선택");break;}
    }
    if(value.length()==0)value.append("목록에서 관측 항목을 선택하면 원본 정보를 확인할 수 있습니다.");
    if(!originals.getText().equals(value.toString())){originals.setText(value.toString());originals.setCaretPosition(0);}
  }
  private final class ShortRenderer extends DefaultTableCellRenderer {
    ShortRenderer(){putClientProperty("html.disable",true);}
    public Component getTableCellRendererComponent(JTable owner,Object value,boolean selected,boolean focus,int row,int column){
      Item item=item(owner.convertRowIndexToModel(row));String original=value==null?"":String.valueOf(value);String shortName=ResultLabels.shortName(original);
      super.getTableCellRendererComponent(owner,shortName.isBlank()?item.label():shortName,selected,focus,row,column);
      putClientProperty("html.disable",true);setToolTipText("<html>"+escape(original)+"</html>");
      return this;
    }
  }
  private final class NavigationRenderer extends JLabel implements TreeCellRenderer {
    private String caption="",count="";private boolean selected;
    NavigationRenderer(){setOpaque(true);putClientProperty("html.disable",true);}
    public Component getTreeCellRendererComponent(JTree owner,Object value,boolean selected,boolean open,boolean leaf,int row,boolean focus){
      this.selected=selected;Node node=((DefaultMutableTreeNode)value).getUserObject() instanceof Node n?n:new Node("","",String.valueOf(value),0);
      caption=node.label();count=node.count()+"건";setToolTipText(caption+" · "+count);
      getAccessibleContext().setAccessibleName(caption+" · "+count);
      setPreferredSize(new Dimension(Math.max(90,owner.getWidth()-42),WorkbenchUi.displayPixels(36)));return this;
    }
    protected void paintComponent(Graphics graphics){
      Graphics2D g=(Graphics2D)graphics.create();kr.packetui.VisualTheme.smooth(g);
      g.setColor(selected?WorkbenchUi.selected():WorkbenchUi.surface());g.fillRoundRect(0,1,getWidth(),Math.max(0,getHeight()-2),7,7);
      g.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(11f)));FontMetrics fm=g.getFontMetrics();int countWidth=fm.stringWidth(count);
      int baseline=(getHeight()-fm.getHeight())/2+fm.getAscent();g.setColor(kr.packetui.VisualTheme.muted());g.drawString(count,Math.max(0,getWidth()-countWidth-7),baseline);
      String visible=caption;int available=Math.max(0,getWidth()-countWidth-21);
      while(!visible.isEmpty()&&fm.stringWidth(visible)>available)visible=visible.substring(0,visible.offsetByCodePoints(visible.length(),-1));
      if(!visible.equals(caption)&&!visible.isEmpty())visible=visible.substring(0,visible.offsetByCodePoints(visible.length(),-1))+"…";
      g.setColor(selected?WorkbenchUi.accent():WorkbenchUi.ink());g.drawString(visible,5,baseline);g.dispose();
    }
  }
  private static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
  private static String suffix(String value){
    try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,6);}
    catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
  }
}
