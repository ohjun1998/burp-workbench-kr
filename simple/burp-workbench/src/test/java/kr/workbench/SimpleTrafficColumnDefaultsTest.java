package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Only known pre-design default metadata is migrated; project-owned custom widths remain exact. */
public final class SimpleTrafficColumnDefaultsTest extends WorkbenchTest {
  private static final String A="11111111-1111-4111-8111-111111111111",B="22222222-2222-4222-8222-222222222222";
  private static final List<Integer> ORDER=List.of(0,1,2,3,8,4,10),LEGACY=List.of(65,80,430,75,105,175,215),APPROVED=List.of(98,124,442,132,114,160,308);
  private static final class Store implements WorkspaceAppearance.Storage {
    String project=A;final Map<String,String> values=new HashMap<>();
    public String project(){return project;}public String read(){return values.get(project);}public void write(String value){values.put(project,value);}
  }
  private static List<Integer> order(JTable table){List<Integer> result=new ArrayList<>();for(int i=0;i<table.getColumnCount();i++)result.add(table.getColumnModel().getColumn(i).getModelIndex());return result;}
  private static List<Integer> widths(JTable table){List<Integer> result=new ArrayList<>();for(int i=0;i<table.getColumnCount();i++)result.add(table.getColumnModel().getColumn(i).getWidth());return result;}
  private static void canonical(JTable table){
    for(int i=0;i<ORDER.size();i++){int view=table.convertColumnIndexToView(ORDER.get(i));table.moveColumn(view,i);var column=table.getColumnModel().getColumn(i);column.setPreferredWidth(APPROVED.get(i));column.setWidth(APPROVED.get(i));}
  }
  private static JTable table(AtomicInteger resets){
    JTable table=new JTable(new DefaultTableModel(new String[]{"#","메서드","URL","상태","시간","길이","출처","중요","계정","세션","메모"},0));
    table.setName("wb.traffic.history");table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    for(int i=table.getColumnCount()-1;i>=0;i--)if(!ORDER.contains(table.getColumnModel().getColumn(i).getModelIndex()))table.removeColumn(table.getColumnModel().getColumn(i));
    canonical(table);table.putClientProperty("wb.approved.traffic.columns",true);table.putClientProperty("wb.approved.columns.pending",true);table.putClientProperty("wb.approved.columns.reset",(Runnable)()->{resets.incrementAndGet();canonical(table);});return table;
  }
  private static WorkspaceAppearance.State state(String project,String key,JTable table,List<Integer> order,List<Integer> widths,int scale,boolean compact){return new WorkspaceAppearance.State(project,scale,compact,true,Map.of(key,new WorkspaceAppearance.Columns(WorkspaceAppearance.signature(table),order,widths)),Map.of());}
  private static void freshAndMigration(){
    Store store=new Store();AtomicInteger resets=new AtomicInteger();JTable table=table(resets);JPanel root=new JPanel(new BorderLayout());root.add(table);
    WorkspaceAppearance appearance=new WorkspaceAppearance(store,root,true,null);String key=String.valueOf(table.getClientProperty("wb.appearance.id"));
    appearance.display(100,false,true);
    check(!appearance.state().columns().containsKey(key)&&!Boolean.TRUE.equals(table.getClientProperty("wb.appearance.columns.restored")),"fresh construction widths are not captured/restored as a user profile before real traffic geometry");
    root.setSize(1378,300);table.setSize(1378,300);table.putClientProperty("wb.approved.columns.pending",false);appearance.capture();
    check(appearance.state().columns().get(key).widths().equals(APPROVED),"first real traffic viewport records the approved column widths");appearance.close();
    store.values.put(A,WorkspaceAppearance.encode(state(A,key,table,ORDER,LEGACY,100,false)));table.putClientProperty("wb.approved.columns.pending",true);table.putClientProperty("wb.appearance.columns.restored",false);canonical(table);
    appearance=new WorkspaceAppearance(store,root,true,null);
    check(resets.get()==1&&widths(table).equals(APPROVED)&&!appearance.state().columns().containsKey(key),"the exact old default width tuple migrates to approved geometry instead of being treated as a manual profile");
    table.putClientProperty("wb.approved.columns.pending",false);appearance.capture();
    check(WorkspaceAppearance.decode(store.values.get(A)).columns().get(key).widths().equals(APPROVED),"migrated defaults persist only after approved real geometry is available");appearance.close();
  }
  private static void customProjects(){
    Store store=new Store();AtomicInteger resets=new AtomicInteger();JTable table=table(resets);JPanel root=new JPanel(new BorderLayout());root.add(table);
    WorkspaceAppearance discovery=new WorkspaceAppearance(store,root,true,null);String key=String.valueOf(table.getClientProperty("wb.appearance.id"));discovery.close();
    List<Integer> customOrder=List.of(0,1,2,3,4,8,10),customWidths=List.of(65,80,333,75,175,105,215);
    String custom=WorkspaceAppearance.encode(state(A,key,table,customOrder,customWidths,100,false));store.values.put(A,custom);store.values.put(B,WorkspaceAppearance.encode(state(B,key,table,ORDER,LEGACY,100,false)));
    WorkspaceAppearance appearance=new WorkspaceAppearance(store,root,true,null);
    try{
      check(order(table).equals(customOrder)&&widths(table).equals(customWidths)&&resets.get()==0,"genuinely custom333px width and reordered columns restore exactly");
      store.project=B;appearance.refreshProject();
      check(order(table).equals(ORDER)&&widths(table).equals(APPROVED)&&resets.get()==1,"project B independently migrates its own exact legacy defaults");
      table.putClientProperty("wb.approved.columns.pending",false);appearance.capture();
      check(store.values.get(A).equals(custom),"project B migration never rewrites project A's custom column metadata");
      store.project=A;appearance.refreshProject();
      check(order(table).equals(customOrder)&&widths(table).equals(customWidths)&&Boolean.TRUE.equals(table.getClientProperty("wb.appearance.columns.restored")),"returning to project A preserves its exact manual order and widths after migration elsewhere");
    }finally{appearance.close();WorkbenchUi.setAppearance(100,false);}
  }
  private static void boundedMatching(int scale,boolean compact,boolean reordered,boolean differentName){
    Store store=new Store();AtomicInteger resets=new AtomicInteger();JTable table=table(resets);if(differentName)table.setName("wb.other.history");JPanel root=new JPanel(new BorderLayout());root.add(table);WorkspaceAppearance discovery=new WorkspaceAppearance(store,root,true,null);String key=String.valueOf(table.getClientProperty("wb.appearance.id"));discovery.close();
    List<Integer> retainedOrder=reordered?List.of(1,0,2,3,8,4,10):ORDER;
    store.values.put(A,WorkspaceAppearance.encode(state(A,key,table,retainedOrder,LEGACY,scale,compact)));
    WorkspaceAppearance appearance=new WorkspaceAppearance(store,root,true,null);
    try{check(resets.get()==0&&order(table).equals(retainedOrder)&&widths(table).equals(LEGACY),"migration excludes custom scale/density/order and nontraffic surfaces: "+scale+"/"+compact+"/"+reordered+"/"+differentName);}
    finally{appearance.close();WorkbenchUi.setAppearance(100,false);}
  }
  private static void actualHeaderDrag()throws Exception {
    BountyFeaturesTest.factory();BurpExtender entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));edt(()->entry.initialize(SimpleWorkflowTest.previewApi()));
    WorkbenchExtension extension=(WorkbenchExtension)get(entry,"workbench");JPanel root=(JPanel)get(extension,"root");JTable traffic=(JTable)get(extension,"logTable");
    WorkspaceAppearance original=(WorkspaceAppearance)get(get(extension,"commonSettings"),"appearance");int sent=PayloadIntegrationTest.sent.get();
    try{edt(()->{
      original.close();root.setSize(1600,1000);for(int i=0;i<8;i++)UiLayoutTest.layout(root);
      Store store=new Store();WorkspaceAppearance appearance=new WorkspaceAppearance(store,root,true,null);
      try{
        check(widths(traffic).equals(APPROVED)&&!Boolean.TRUE.equals(traffic.getClientProperty("wb.appearance.columns.restored")),"programmatic first-layout columns remain the approved baseline without claiming a manual profile");
        var header=traffic.getTableHeader();var column=traffic.getColumnModel().getColumn(2);Rectangle bounds=header.getHeaderRect(2);int x=bounds.x+bounds.width-1,y=bounds.height/2,target=x+333-column.getWidth();long time=System.currentTimeMillis();
        header.dispatchEvent(new MouseEvent(header,MouseEvent.MOUSE_PRESSED,time,MouseEvent.BUTTON1_DOWN_MASK,x,y,1,false,MouseEvent.BUTTON1));
        check(header.getResizingColumn()==column,"the actual table-header mouse handler starts the URL resize gesture");
        header.dispatchEvent(new MouseEvent(header,MouseEvent.MOUSE_DRAGGED,time+1,MouseEvent.BUTTON1_DOWN_MASK,target,y,0,false,MouseEvent.NOBUTTON));
        header.dispatchEvent(new MouseEvent(header,MouseEvent.MOUSE_RELEASED,time+2,0,target,y,1,false,MouseEvent.BUTTON1));
        check(column.getWidth()==333&&Boolean.TRUE.equals(traffic.getClientProperty("wb.appearance.columns.restored")),"a real header drag owns its333px width immediately before debounced persistence");
        for(int i=0;i<8;i++)UiLayoutTest.layout(root);
        check(column.getWidth()==333,"normal composed traffic relayout preserves the fresh user's drag before the save timer fires");
        appearance.capture();String key=String.valueOf(traffic.getClientProperty("wb.appearance.id"));store.project=B;appearance.refreshProject();for(int i=0;i<8;i++)UiLayoutTest.layout(root);store.project=A;appearance.refreshProject();for(int i=0;i<8;i++)UiLayoutTest.layout(root);
        check(traffic.getColumnModel().getColumn(2).getWidth()==333&&appearance.state().columns().get(key).widths().get(2)==333,"the actual dragged width restores exactly after another project's baseline is visited");
        appearance.resetSurfaces();for(int i=0;i<8;i++)UiLayoutTest.layout(root);appearance.capture();
        check(widths(traffic).equals(APPROVED)&&!Boolean.TRUE.equals(traffic.getClientProperty("wb.appearance.columns.restored"))&&appearance.state().columns().get(key).widths().equals(APPROVED),"resetting surfaces releases manual ownership and restores actual approved traffic geometry");
        check(PayloadIntegrationTest.sent.get()==sent,"header resizing, project restoration and display reset send zero target HTTP");
      }finally{appearance.close();}
    });}finally{SimpleWorkflowTest.unload();}
  }
  public static void main(String[] args)throws Exception {
    edt(()->{freshAndMigration();customProjects();boundedMatching(125,false,false,false);boundedMatching(100,true,false,false);boundedMatching(100,false,true,false);boundedMatching(100,false,false,true);});
    actualHeaderDrag();
    System.out.println("SimpleTrafficColumnDefaultsTest: "+checks+" checks passed (bounded metadata migration; no network)");
  }
}
