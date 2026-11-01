package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;

/** The promoted sidebar controls delegate to retained controllers and never execute a scan. */
public final class SimpleCompactPrimaryNavigationTest extends WorkbenchTest {
  private static Object value(Object owner,String name){try{return get(owner,name);}catch(Exception failure){throw new AssertionError(failure);}}
  private static AbstractButton button(Component root,String name){
    Component control=SimpleWorkflowTest.named(root,name);
    if(!(control instanceof AbstractButton found))throw new AssertionError("Missing primary control: "+name);
    return found;
  }
  private static Action enter(AbstractButton button){
    Object key=button.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("ENTER"));
    Action action=key==null?null:button.getActionMap().get(key);
    if(action==null)throw new AssertionError("Missing Enter binding: "+button.getName());
    return action;
  }
  private static boolean sameOriginal(WorkbenchExtension extension,String id,byte[] request,byte[] response){
    Object current=value(extension,"current");if(current==null)return false;
    DiskTrafficStore.PacketRef packet=(DiskTrafficStore.PacketRef)value(current,"packet");
    return packet.id().equals(id)&&Arrays.equals(packet.request().toByteArray().getBytes(),request)
        &&packet.response()!=null&&Arrays.equals(packet.response().toByteArray().getBytes(),response);
  }
  private static void localControllers(){
    AtomicInteger inspections=new AtomicInteger(),dashboards=new AtomicInteger();
    JButton inspectionSource=new JButton("원래 통합 점검");JToggleButton dashboardSource=new JToggleButton("원래 대시보드");
    inspectionSource.addActionListener(e->inspections.incrementAndGet());
    dashboardSource.addActionListener(e->{dashboards.incrementAndGet();dashboardSource.setSelected(true);});
    CompactPrimaryNavigation primary=new CompactPrimaryNavigation(inspectionSource,dashboardSource);
    try{
      AbstractButton inspect=button(primary,"wb.navigation.primary.inspect"),dashboard=button(primary,"wb.navigation.primary.dashboard");
      check(inspect.getText().equals("통합 점검")&&dashboard.getText().equals("대시보드"),"two promoted controls retain their approved readable captions");
      check(inspect.getModel()!=inspectionSource.getModel()&&dashboard.getModel()!=dashboardSource.getModel(),
          "presentation buttons keep their own models and delegate explicit actions to the source controllers");
      inspect.doClick(0);check(inspections.get()==1,"a real promoted inspection click delegates exactly once");
      inspectionSource.setEnabled(false);check(!inspect.isEnabled(),"source availability immediately disables its promoted action");
      inspect.doClick(0);enter(inspect).actionPerformed(new ActionEvent(inspect,ActionEvent.ACTION_PERFORMED,"disabled-enter"));
      check(inspections.get()==1,"disabled promoted actions cannot dispatch through mouse or Enter");
      inspectionSource.setEnabled(true);check(inspect.isEnabled(),"source re-enablement restores its promoted action");
      enter(inspect).actionPerformed(new ActionEvent(inspect,ActionEvent.ACTION_PERFORMED,"enter"));
      check(inspections.get()==2,"the actual Enter binding delegates to the original preparation controller once");
      dashboard.doClick(0);dashboard.doClick(0);
      check(dashboards.get()==2&&dashboardSource.isSelected()&&dashboard.isSelected(),
          "re-clicking a source that keeps its current route selected never leaves the promoted toggle deselected");
      dashboardSource.setSelected(false);check(!dashboard.isSelected(),"programmatic source selection changes update the promoted toggle");
      dashboardSource.setModel(new JToggleButton.ToggleButtonModel());dashboardSource.setSelected(true);
      check(dashboard.isSelected(),"source model replacement retains live selection synchronization");
      primary.close();int beforeInspection=inspections.get(),beforeDashboard=dashboards.get();
      check(!inspect.isEnabled()&&!dashboard.isEnabled()&&inspectionSource.isEnabled()&&dashboardSource.isEnabled(),
          "closing promoted presentation disables its own controls without disabling the original sources");
      inspect.doClick(0);dashboard.doClick(0);enter(inspect).actionPerformed(new ActionEvent(inspect,ActionEvent.ACTION_PERFORMED,"closed-enter"));
      inspectionSource.setEnabled(false);inspectionSource.setEnabled(true);dashboardSource.setSelected(false);
      check(inspections.get()==beforeInspection&&dashboards.get()==beforeDashboard&&!inspect.isEnabled()&&!dashboard.isEnabled(),
          "disposed promoted actions remain inert after explicit calls and later source changes");
    }finally{primary.close();}
  }
  private static void layout(JPanel root,FolderNavigation navigation,int width){
    root.setSize(width,720);for(int pass=0;pass<6;pass++){UiLayoutTest.layout(root);navigation.restoreLayout();}
  }
  private static void awaitRestoration(BooleanSupplier ready,Supplier<String> diagnosis)throws Exception {
    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    AtomicReference<Boolean> restored=new AtomicReference<>();AtomicReference<String> state=new AtomicReference<>();
    while(true){
      edt(()->{restored.set(ready.getAsBoolean());state.set(diagnosis.get());});
      if(restored.get())return;
      if(System.nanoTime()>=deadline)throw new AssertionError("Back restoration did not complete: "+state.get());
      Thread.sleep(15);
    }
  }
  private static void production()throws Exception {
    BountyFeaturesTest.factory();BurpExtender entry=new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(()->entry.initialize(SimpleWorkflowTest.previewApi()));edt(()->{});
    WorkbenchExtension extension=(WorkbenchExtension)get(entry,"workbench");JPanel root=(JPanel)get(extension,"root");
    FolderNavigation navigation=(FolderNavigation)get(extension,"navigation");JTabbedPane pages=(JTabbedPane)get(extension,"tabs");
    JTree tree=(JTree)SimpleWorkflowTest.named(root,"wb.navigation.tree");JSplitPane split=(JSplitPane)SimpleWorkflowTest.named(root,"wb.navigation.split");
    JScrollPane sidebar=(JScrollPane)SimpleWorkflowTest.named(root,"wb.navigation.sidebar");
    CompactPrimaryNavigation primary=(CompactPrimaryNavigation)SimpleWorkflowTest.named(root,"wb.navigation.primary");
    AbstractButton inspect=button(primary,"wb.navigation.primary.inspect"),dashboard=button(primary,"wb.navigation.primary.dashboard");
    JTable traffic=(JTable)get(extension,"logTable");JTextArea codec=(JTextArea)get(extension,"codecIn");
    JTextArea payload=(JTextArea)get(get(entry,"payloadRunner"),"request");int sent=PayloadIntegrationTest.sent.get();
    AtomicReference<Component> opened=new AtomicReference<>();AtomicInteger opens=new AtomicInteger();
    AtomicReference<BooleanSupplier> backReady=new AtomicReference<>();AtomicReference<Supplier<String>> backState=new AtomicReference<>();
    AtomicReference<Runnable> afterRestoration=new AtomicReference<>();
    extension.dialogOpener=(owner,title,content,modal,closed)->{opened.set(content);opens.incrementAndGet();return null;};
    seedTraffic(extension,request("https://primary-navigation.test/retained","GET",true,List.of()),response(200,"PRIMARY_ORIGINAL"));
    try{
      edt(()->{
        navigation.navigate("traffic");traffic.setRowSelectionInterval(0,0);Object original=value(extension,"current");
        DiskTrafficStore.PacketRef originalPacket=(DiskTrafficStore.PacketRef)value(original,"packet");String originalId=originalPacket.id();
        byte[] originalRequest=originalPacket.request().toByteArray().getBytes(),originalResponse=originalPacket.response().toByteArray().getBytes();
        codec.setText("UNSAVED_PRIMARY_CODEC");payload.setText("UNSAVED_PRIMARY_REQUEST");
        var codecDocument=codec.getDocument();var payloadDocument=payload.getDocument();var trafficModel=traffic.getModel();
        Component trafficPage=pages.getSelectedComponent();var trafficPath=tree.getSelectionPath();
        check(primary.getComponentCount()==2&&sidebar.getColumnHeader().getView()==primary,
            "exactly two promoted actions occupy the existing sidebar column header");
        check(tree.getParent() instanceof JViewport&&split.getLeftComponent()==sidebar&&split.getRightComponent()==pages,
            "sidebar promotion preserves the existing tree viewport and both page-split component identities");
        var nodes=((DefaultMutableTreeNode)tree.getModel().getRoot()).depthFirstEnumeration();int duplicateDashboard=0;
        while(nodes.hasMoreElements()){var node=(DefaultMutableTreeNode)nodes.nextElement();if(node.isLeaf()&&FeatureGuide.title("dashboard").equals(node.toString()))duplicateDashboard++;}
        check(duplicateDashboard==0,"the promoted dashboard has no duplicate lower-folder leaf");
        check(value(extension,"scopeInspectionPanel")==null,"promotion itself does not construct or start inspection");
        List<String> recent=navigation.recentRoutes();inspect.doClick(0);
        ScopeInspectionPanel inspection=(ScopeInspectionPanel)value(extension,"scopeInspectionPanel");Object inspectionWindow=value(extension,"scopeInspectionWindow");
        check(opened.get()==inspection&&inspection.runner==null&&inspection.katana==null,
            "the actual promoted inspection action opens the retained preparation panel without either runner");
        check(pages.getSelectedComponent()==trafficPage&&tree.getSelectionPath().equals(trafficPath)&&navigation.recentRoutes().equals(recent),
            "inspection preparation preserves the active page, folder selection and route history");
        inspection.url.setText("https://primary-navigation.test/manual-target");var targetDocument=inspection.url.getDocument();inspect.doClick(0);
        check(opens.get()==2&&opened.get()==inspection&&value(extension,"scopeInspectionWindow")==inspectionWindow
                &&inspection.url.getDocument()==targetDocument&&inspection.url.getText().endsWith("/manual-target")&&inspection.runner==null,
            "repeated preparation reuses the same target-window controller and panel with authored target input intact");
        dashboard.doClick(0);dashboard.doClick(0);
        check(pages.getSelectedComponent()==value(extension,"scopeDashboard")&&dashboard.isSelected()&&tree.getSelectionPath()==null,
            "the actual promoted dashboard reuses its existing page and retains selection on repeated clicks");
        check(((JLabel)SimpleWorkflowTest.named(root,"wb.navigation.breadcrumb")).getText().contains("통합 점검"),
            "the dashboard breadcrumb describes its promoted inspection context");
        navigation.goBack();
        backReady.set(()->pages.getSelectedComponent()==trafficPage&&!dashboard.isSelected()&&trafficPath.equals(tree.getSelectionPath())
            &&traffic.getSelectedRow()==0&&sameOriginal(extension,originalId,originalRequest,originalResponse));
        backState.set(()->"page="+(pages.getSelectedComponent()==trafficPage)+", dashboardCleared="+!dashboard.isSelected()
            +", treePath="+trafficPath.equals(tree.getSelectionPath())+", row="+traffic.getSelectedRow()
            +", originalIdAndBytes="+sameOriginal(extension,originalId,originalRequest,originalResponse));
        if(!backReady.get().getAsBoolean())System.out.println("Awaiting queued Back restoration: "+backState.get().get());
        afterRestoration.set(()->{
        check(backReady.get().getAsBoolean(),
            "Back restores the retained traffic page, selected original and its folder highlight");
        navigation.goForward();
        check(pages.getSelectedComponent()==value(extension,"scopeDashboard")&&dashboard.isSelected()&&tree.getSelectionPath()==null,
            "Forward restores the promoted dashboard selection without a synthetic folder node");
        navigation.navigate("auth");check(!dashboard.isSelected()&&SimpleWorkflowTest.toolSelected(root,"auth"),
            "opening another retained tool clears promoted selection and selects its actual leaf");
        ButtonModel inspectModel=inspect.getModel(),dashboardModel=dashboard.getModel();
        for(boolean dark:new boolean[]{false,true})for(int scale:new int[]{100,150})for(int width:new int[]{1024,1280,1600}){
          kr.packetui.VisualTheme.setDarkOverride(dark);WorkbenchUi.setAppearance(scale,false);WorkbenchUi.style(root);navigation.restoreAppearance();layout(root,navigation,width);
          Rectangle first=inspect.getBounds(),second=dashboard.getBounds();
          check(first.height==42&&second.height==42&&second.y>=first.y+first.height&&first.width>100&&second.width>100
                  &&first.x>=0&&second.x>=0&&first.x+first.width<=primary.getWidth()
                  &&second.x+second.width<=primary.getWidth()&&second.y+second.height<=primary.getHeight(),
              "both real compact primary rows retain42px geometry inside the sidebar at "+width+"/"+scale+"/"+dark);
          check(inspect.getModel()==inspectModel&&dashboard.getModel()==dashboardModel
                  &&inspect.getText().equals("통합 점검")&&dashboard.getText().equals("대시보드"),
              "theme and display scaling preserve the promoted models and readable captions");
          AbstractButton toggle=button(root,"wb.navigation.toggle");toggle.doClick(0);layout(root,navigation,width);
          check(!sidebar.isVisible(),"hiding the menu also hides its promoted rows");
          toggle.doClick(0);layout(root,navigation,width);
          check(sidebar.isVisible()&&SimpleWorkflowTest.visibleComponents(root).contains(inspect)
                  &&SimpleWorkflowTest.visibleComponents(root).contains(dashboard)&&tree.getParent() instanceof JViewport,
              "reopening the sidebar restores both actions and the original scrollable tree");
        }
        check(codec.getDocument()==codecDocument&&payload.getDocument()==payloadDocument&&traffic.getModel()==trafficModel
                &&codec.getText().equals("UNSAVED_PRIMARY_CODEC")&&payload.getText().equals("UNSAVED_PRIMARY_REQUEST")
                &&sameOriginal(extension,originalId,originalRequest,originalResponse)&&traffic.getSelectedRow()==0,
            "navigation, preparations, themes and menu visibility preserve exact source models, documents and selected original");
        check(PayloadIntegrationTest.sent.get()==sent&&inspection.runner==null&&inspection.katana==null,
            "all promoted preparation/navigation actions send zero target HTTP and never start a scan");
        Component selected=pages.getSelectedComponent();int before=opens.get();navigation.close();
        inspect.doClick(0);dashboard.doClick(0);enter(inspect).actionPerformed(new ActionEvent(inspect,ActionEvent.ACTION_PERFORMED,"closed-enter"));
        check(pages.getSelectedComponent()==selected&&opens.get()==before&&!inspect.isEnabled()&&!dashboard.isEnabled()
                &&PayloadIntegrationTest.sent.get()==sent,
            "closed production navigation disables promoted actions and leaves pages, dialogs and HTTP untouched");
        });
      });
      awaitRestoration(backReady.get(),backState.get());
      edt(afterRestoration.get());
    }finally{kr.packetui.VisualTheme.setDarkOverride(null);WorkbenchUi.setAppearance(100,false);SimpleWorkflowTest.unload();}
  }
  public static void main(String[] args)throws Exception {
    edt(SimpleCompactPrimaryNavigationTest::localControllers);production();
    System.out.println("SimpleCompactPrimaryNavigationTest: "+checks+" checks passed (retained controllers, compact actual geometry, zero HTTP)");
  }
}
