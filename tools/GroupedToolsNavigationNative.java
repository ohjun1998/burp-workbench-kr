package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.*;
import javax.swing.text.Document;
import javax.swing.tree.*;
import static kr.workbench.HeaderToolsNative.*;

/** All production Simple tool windows, synthetic local metadata, zero target sends. */
public final class GroupedToolsNavigationNative extends WorkbenchTest {
  private static final String ORIGIN="https://grouped.example.test";
  private static final List<String> ROUTES=List.of("traffic","mapper","js","environment","exposure","auth","response.recon","payload","data","activity","bounty","dashboard");
  private static final List<String> PATHS=List.of("/dashboard","/check_balance/1791279111","/admin","/login","/merchant/login","/register","/merchant/register","/api/docs/","/swagger-ui.html","/openapi.json","/healthz","/.env","/wb-missing-11111111-2222-4333-8444-555555555555","/check_balance/%27","/api/ai/chat/anonymous","/privacy","/assets/app.js");
  private static final List<Throwable> asynchronous=Collections.synchronizedList(new ArrayList<>());
  private static final List<Map<String,Object>> groups=new ArrayList<>();
  private static final Map<String,List<Integer>> captures=new LinkedHashMap<>();
  private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
  private static void key(Robot robot,int code){robot.keyPress(code);robot.keyRelease(code);robot.waitForIdle();}
  private static void picture(Robot robot,JComponent view,Path out,String name)throws Exception{
    Rectangle[] bounds={null};edt(()->bounds[0]=new Rectangle(view.getLocationOnScreen(),view.getSize()));
    robot.mouseMove(1870,1150);settle();ImageIO.write(robot.createScreenCapture(bounds[0]),"png",out.resolve(name).toFile());captures.put(name,List.of(bounds[0].width,bounds[0].height));
  }
  private static void treeClick(Robot robot,JTree tree,TreePath path)throws Exception{
    edt(()->{tree.scrollPathToVisible(path);reveal(tree,tree.getPathBounds(path));tree.requestFocusInWindow();});settle();
    Point[] point={null};edt(()->{Rectangle bounds=tree.getPathBounds(path);if(bounds==null)throw new AssertionError("Missing grouped row");point[0]=tree.getLocationOnScreen();point[0].translate(bounds.x+Math.min(45,Math.max(2,bounds.width-4)),bounds.y+bounds.height/2);});
    robot.mouseMove(point[0].x,point[0].y);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);settle();
  }
  private static void reveal(Component component,Rectangle local){
    if(local==null)return;for(Component current=component;current!=null;current=current.getParent())if(current instanceof JComponent view)view.scrollRectToVisible(SwingUtilities.convertRectangle(component,local,view));
  }
  private static List<GroupedResults> displayed(Component component){
    List<GroupedResults> result=new ArrayList<>();if(component instanceof GroupedResults group&&group.isShowing())result.add(group);
    if(component instanceof Container parent)for(Component child:parent.getComponents())result.addAll(displayed(child));return result;
  }
  private static List<List<Object>> values(TableModel model){
    List<List<Object>> result=new ArrayList<>();for(int row=0;row<model.getRowCount();row++){List<Object> cells=new ArrayList<>();for(int column=0;column<model.getColumnCount();column++)cells.add(model.getValueAt(row,column));result.add(cells);}return result;
  }
  private static List<Map<String,Object>> originals(DiskTrafficStore store)throws Exception{
    List<Map<String,Object>> rows=new ArrayList<>();for(int i=0;i<store.size();i++){var ref=store.ref(i);rows.add(Map.of("id",ref.id(),"url",ref.url(),"request",sha(ref.requestPrefix(Integer.MAX_VALUE)),"response",sha(ref.responsePrefix(Integer.MAX_VALUE))));}return rows;
  }
  private static void verifyTraffic(Robot robot,WorkbenchExtension extension)throws Exception{
    TrafficBrowser browser=extension.trafficBrowser;TableModel model=browser.table.getModel();TableColumnModel columns=browser.table.getColumnModel();
    JPanel root=(JPanel)value(extension,"root");AbstractButton settings=(AbstractButton)SimpleWorkflowTest.named(root,"wb.traffic.settings");
    click(robot,settings);JMenuItem grouped=item(settings.getComponentPopupMenu(),"wb.traffic.grouped");
    check(grouped.isShowing(),"Traffic exposes A grouping through its retained collection settings menu");
    if(!browser.query.group())click(robot,grouped);else escape(robot);TrafficSearchUpgradeTest.idle(browser);
    check(browser.query.group(),"Traffic A-group display uses the original asynchronous query engine");
    TrafficBrowser.Group group=browser.view.groups().values().stream().filter(g->g.members().size()>1).findFirst().orElseThrow(()->new AssertionError("Missing normalized traffic group fixture"));
    int collapsed=browser.table.getRowCount();String caption=browser.displayUrl(group.first(),Objects.toString(model.getValueAt(group.first(),2),""));
    check(!caption.contains("https://")&&!caption.contains("/check_balance/"),"Traffic group caption shows a short function label");
    int view=browser.table.convertRowIndexToView(group.first());edt(()->{browser.table.setRowSelectionInterval(view,view);browser.table.requestFocusInWindow();});
    robot.keyPress(KeyEvent.VK_ALT);key(robot,KeyEvent.VK_G);robot.keyRelease(KeyEvent.VK_ALT);TrafficSearchUpgradeTest.idle(browser);
    check(browser.table.getRowCount()==collapsed+group.members().size()-1,"Traffic group expansion exposes every original member");
    robot.keyPress(KeyEvent.VK_ALT);key(robot,KeyEvent.VK_G);robot.keyRelease(KeyEvent.VK_ALT);TrafficSearchUpgradeTest.idle(browser);
    check(browser.table.getRowCount()==collapsed,"Traffic group collapse restores its compact view");
    check(browser.table.getModel()==model&&browser.table.getColumnModel()==columns,"Traffic grouping retains its existing model and column objects");
  }
  private static void verifyMapper(Robot robot,WorkbenchExtension extension)throws Exception{
    Object mapper=value(extension,"featureMapper");JTree tree=(JTree)value(mapper,"tree");JTable table=(JTable)value(mapper,"table");
    await(()->{var items=((DefaultMutableTreeNode)tree.getModel().getRoot()).depthFirstEnumeration();while(items.hasMoreElements())if(((DefaultMutableTreeNode)items.nextElement()).getUserObject().getClass().getSimpleName().equals("FeatureNode"))return true;return false;},"embedded Mapper publishes captured feature leaves");
    DefaultMutableTreeNode root=(DefaultMutableTreeNode)tree.getModel().getRoot(),leaf=null;
    var nodes=root.depthFirstEnumeration();while(nodes.hasMoreElements()){var node=(DefaultMutableTreeNode)nodes.nextElement();if(node.getUserObject().getClass().getSimpleName().equals("FeatureNode")){leaf=node;break;}}
    check(leaf!=null,"Embedded Mapper retains original feature leaves inside the Workbench");
    TreePath feature=new TreePath(leaf.getPath()),parent=feature.getParentPath();Object nodeValue=leaf.getUserObject();
    var idMethod=nodeValue.getClass().getDeclaredMethod("id");idMethod.setAccessible(true);String id=(String)idMethod.invoke(nodeValue);
    treeClick(robot,tree,feature);await(()->id.equals(value(mapper,"selectedFeature"))&&table.getRowCount()>0,"embedded Mapper feature selected");
    check(table.isShowing(),"Embedded Mapper uses the retained visible request table");
    treeClick(robot,tree,parent);key(robot,KeyEvent.VK_LEFT);await(()->!tree.isExpanded(parent),"embedded Mapper group collapsed");
    edt(()->{try{var refresh=mapper.getClass().getDeclaredMethod("refresh");refresh.setAccessible(true);refresh.invoke(mapper);}catch(Exception error){throw new IllegalStateException(error);}});
    check(!tree.isExpanded(parent),"Embedded Mapper collapse survives the real workspace refresh");key(robot,KeyEvent.VK_RIGHT);
    treeClick(robot,tree,new TreePath(((DefaultMutableTreeNode)root.getChildAt(0)).getPath()));
    check(value(mapper,"selectedFeature")==null,"Embedded Mapper all-observations clears only the view selection");
    treeClick(robot,tree,feature);
  }
  private static void verifyGroup(Robot robot,GroupedResults owner,String route)throws Exception{
    JTable table=(JTable)value(owner,"table");JTree tree=(JTree)value(owner,"tree");String prefix=owner.getName().replaceFirst("\\.groups$","");
    TableModel model=table.getModel();TableColumnModel columns=table.getColumnModel();List<List<Object>> before=values(model);
    int visibleBefore=table.getRowCount();DefaultMutableTreeNode root=(DefaultMutableTreeNode)tree.getModel().getRoot();
    if(visibleBefore>0&&table.getTableHeader()!=null){
      FontMetrics metrics=table.getTableHeader().getFontMetrics(table.getTableHeader().getFont());
      for(int i=0;i<columns.getColumnCount();i++){
        TableColumn column=columns.getColumn(i);String title=Objects.toString(column.getHeaderValue(),"");
        int minimum=Math.min(90,metrics.stringWidth(title)+12);
        check(column.getWidth()>=minimum,route+": displayed column header stays readable: "+title+" ("+column.getWidth()+">="+minimum+")");
      }
    }
    check(tree.isShowing()&&!tree.isRootVisible(),route+": grouped tree displayed without technical root");
    check(root.getChildCount()>0,route+": grouped view retains all-observations entry");
    int testedLeaves=0;
    if(root.getChildCount()>1){
      DefaultMutableTreeNode group=(DefaultMutableTreeNode)root.getChildAt(1);TreePath groupPath=new TreePath(group.getPath());
      treeClick(robot,tree,groupPath);key(robot,KeyEvent.VK_LEFT);await(()->!tree.isExpanded(groupPath),"group physically collapses "+route);
      edt(()->{try{var refresh=GroupedResults.class.getDeclaredMethod("refresh");refresh.setAccessible(true);refresh.invoke(owner);}catch(Exception error){throw new IllegalStateException(error);}});
      DefaultMutableTreeNode refreshed=(DefaultMutableTreeNode)((DefaultMutableTreeNode)tree.getModel().getRoot()).getChildAt(1);
      check(!tree.isExpanded(new TreePath(refreshed.getPath())),route+": group collapse survives model refresh");
      key(robot,KeyEvent.VK_RIGHT);await(()->tree.isExpanded(new TreePath(refreshed.getPath())),"group physically expands "+route);
      if(refreshed.getChildCount()>0){
        DefaultMutableTreeNode leaf=(DefaultMutableTreeNode)refreshed.getChildAt(0);treeClick(robot,tree,new TreePath(leaf.getPath()));
        check(table.getRowCount()>0&&table.getRowCount()<=visibleBefore,route+": function selection filters the retained table");
        check(table.getModel()==model&&table.getColumnModel()==columns,route+": selection retains model and column objects");
        RowSorter<?> activeSorter=table.getRowSorter();
        for(int row=0;row<Math.min(3,table.getRowCount());row++){
          final int selected=row;edt(()->table.setRowSelectionInterval(selected,selected));
          int modelRow=table.convertRowIndexToModel(row);check(modelRow>=0&&modelRow<model.getRowCount(),route+": selected view row resolves original model row");
        }
        int[] shortColumns=(int[])value(owner,"shortColumns");
        for(int index:shortColumns){int view=table.convertColumnIndexToView(index);if(view<0||table.getRowCount()==0)continue;Component cell=table.prepareRenderer(table.getCellRenderer(0,view),0,view);if(cell instanceof JLabel label)check(!label.getText().contains("https://"),route+": displayed URL column uses short caption");}
        JToggleButton disclosure=(JToggleButton)SimpleWorkflowTest.named(owner,prefix+".original.toggle");JTextArea detail=(JTextArea)value(owner,"originals");
        edt(()->reveal(disclosure,new Rectangle(0,0,disclosure.getWidth(),disclosure.getHeight())));settle();
        click(robot,disclosure);await(detail::isShowing,"original disclosure "+route);check(!detail.getText().isBlank(),route+": selected original metadata remains available");click(robot,disclosure);
        DefaultMutableTreeNode currentRoot=(DefaultMutableTreeNode)tree.getModel().getRoot();treeClick(robot,tree,new TreePath(((DefaultMutableTreeNode)currentRoot.getChildAt(0)).getPath()));
        check(table.getRowCount()==visibleBefore,route+": all-observations restores prior visible row count");
        check(table.getRowSorter()==activeSorter,route+": grouping retains the active sorter object");testedLeaves++;
      }
    }
    check(table.getModel()==model&&table.getColumnModel()==columns&&before.equals(values(model)),route+": grouping leaves every source cell unchanged");
    groups.add(Map.of("route",route,"component",owner.getName(),"modelRows",model.getRowCount(),"viewRows",table.getRowCount(),"selectedFunctionsTested",testedLeaves));
  }
  public static void main(String[] args)throws Exception{
    if(GraphicsEnvironment.isHeadless())throw new AssertionError("Use isolated native Xorg");
    Path out=Path.of(args[0]);Files.createDirectories(out);String theme=args[1];int width=Integer.parseInt(args[2]),height=Integer.parseInt(args[3]);
    String lookAndFeel=System.getProperty("qa.lookAndFeel","metal");UIManager.setLookAndFeel(lookAndFeel.equals("nimbus")?"javax.swing.plaf.nimbus.NimbusLookAndFeel":"javax.swing.plaf.metal.MetalLookAndFeel");
    if(theme.equals("dark"))darkTheme();Thread.setDefaultUncaughtExceptionHandler((thread,error)->{asynchronous.add(error);error.printStackTrace();});
    int beforeHttp=PayloadIntegrationTest.sent.get();ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));
    BurpExtender entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    try{
      edt(()->entry.initialize(SimpleWorkflowTest.previewApi()));edt(()->{});
      WorkbenchExtension extension=(WorkbenchExtension)get(entry,"workbench");JPanel root=(JPanel)get(extension,"root");WorkbenchWindow desktop=(WorkbenchWindow)get(extension,"workbenchWindow");
      edt(desktop::open);await(()->desktop.window!=null&&desktop.window.isShowing(),"production Workbench opens");JFrame frame=desktop.window;
      await(()->frame.getWidth()>1800,"initial maximize settled");edt(()->frame.setExtendedState(Frame.NORMAL));settle();edt(()->frame.setBounds(30,40,width,height));
      await(()->frame.getWidth()==width&&frame.getHeight()==height,"native dimensions applied");
      var environment=(EnvironmentPanel)get(extension,"environments");var auth=(AuthComparePanel)get(extension,"authCompare");var js=(JsAnalysisPanel)get(extension,"jsAnalysis");var exposure=(ExposureResearchPanel)get(extension,"exposureResearch");var mapper=(kr.featuremapper.FeatureMapperWorkspace)get(extension,"featureMapper");var bounty=(BountySupportPanel)get(extension,"bountySupport");
      edt(()->((WorkbenchScope)value(extension,"localScope")).add(ORIGIN+"/",true));
      List<HttpRequestResponse> evidence=new ArrayList<>();StringBuilder source=new StringBuilder();
      for(String path:PATHS){
        int status=path.contains("missing")||path.contains("swagger")?404:path.contains("%27")?500:path.contains("anonymous")?405:200;
        var q=request(ORIGIN+path,"GET",true,List.of(header("Cookie","sid=GROUPED_SYNTHETIC")));
        var s=response(status,"{\"synthetic\":true,\"note\":\"UI grouping fixture\"}");
        seedTraffic(extension,q,s);
        source.append("fetch('").append(ORIGIN).append(path).append("');\n");
        evidence.add(proxy(HttpRequestResponse.class,(p,m,a)->switch(m.getName()){case "request"->q;case "response"->s;default->def(m);}));
      }
      for(int id=1791279112;id<1791279115;id++)seedTraffic(extension,request(ORIGIN+"/check_balance/"+id,"GET",true,List.of(header("Cookie","sid=GROUPED_SYNTHETIC"))),response(200,"{\"synthetic\":true}"));
      MapperIntegrationTest.idle(extension);
      edt(()->{js.load(source.toString(),ORIGIN+"/assets/ui-fixture.js");exposure.target.setText(ORIGIN);for(String path:PATHS)exposure.exposures.add(new ExposureResearch.Exposure(ORIGIN+path,"합성 관측 URL","UI fixture · 외부 조회 미실행",Instant.EPOCH));exposure.refreshExposures();bounty.captureObserved(evidence.subList(0,3),"합성 UI 원본 · 보안 판정 아님");});
      await(()->js.urlTable.getRowCount()>0,"local JavaScript analysis ready");
      DiskTrafficStore store=(DiskTrafficStore)get(extension,"trafficStore");List<Map<String,Object>> originalPackets=originals(store);
      var pages=new ArrayList<ScopeInspection.Page>();var findings=new ArrayList<ScopeInspection.Finding>();
      for(int i=0;i<PATHS.size();i++){String url=ORIGIN+PATHS.get(i);pages.add(new ScopeInspection.Page(url,"GET","합성 계정",200,"합성 관측",false,"UI 표시용 · 점검 미실행","",store.ref(i)));findings.add(new ScopeInspection.Finding("API","합성 관측 항목",url,"GET","합성 계정","미확인","INFO","미점검","UI 표시 fixture · 실행 결과 아님",store.ref(i).id(),0,0,"synthetic-"+i));}
      ScopeInspection.Snapshot snapshot=new ScopeInspection.Snapshot("synthetic-grouped-ui",ScopeInspection.State.COMPLETED,"합성 UI 표시 · 점검 미실행",0,0,0,0,0,0,findings,pages,List.of(),1,2);
      edt(()->extension.scopeDashboard.update(snapshot,100,ORIGIN));
      JTextArea codec=(JTextArea)get(extension,"codecIn");Document codecDocument=codec.getDocument();edt(()->codec.setText("GROUPED_UI_UNSAVED_INPUT"));
      Robot robot=new Robot();robot.setAutoDelay(40);
      for(String route:ROUTES){
        edt(()->extension.openFeature(route));settle();check(root.isShowing(),"Production root remains displayed for "+route);
        if(route.equals("traffic"))verifyTraffic(robot,extension);
        if(route.equals("mapper"))verifyMapper(robot,extension);
        for(GroupedResults owner:displayed(root))verifyGroup(robot,owner,route);
        picture(robot,root,out,route.replace('.','-')+".png");
      }
      AbstractButton inspect=(AbstractButton)SimpleWorkflowTest.named(root,"wb.navigation.primary.inspect");click(robot,inspect);
      await(()->extension.scopeInspectionPanel!=null&&value(extension,"scopeInspectionWindow")!=null,"retained integrated inspection panel opens");
      Object scopeOwner=value(extension,"scopeInspectionWindow");JDialog dialog=(JDialog)value(scopeOwner,"window");await(dialog::isShowing,"integrated inspection dialog visible");
      check(extension.scopeInspectionPanel.runner==null,"Opening integration starts no inspection runner");
      edt(()->{extension.scopeInspectionPanel.accept(snapshot);dialog.setBounds(40,50,Math.min(width,1200),Math.min(height,850));});settle();
      for(GroupedResults owner:displayed(dialog))verifyGroup(robot,owner,"scope");
      picture(robot,dialog.getRootPane(),out,"scope.png");
      check(extension.scopeInspectionPanel.runner==null&&PayloadIntegrationTest.sent.get()==beforeHttp,"All13 tool views and grouping send zero requests or start inspections");
      check(codec.getDocument()==codecDocument&&codec.getText().equals("GROUPED_UI_UNSAVED_INPUT"),"All-tool grouping preserves authored data document");
      check(originalPackets.equals(originals(store)),"Every captured original ID,URL and exact request/response bytes retained");
      check(asynchronous.isEmpty(),"All-tool display and grouped navigation emit zero asynchronous Swing failures");
      Path jar=Path.of(WorkbenchExtension.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      Map<String,Object> result=new LinkedHashMap<>();result.put("result","passed");result.put("version",BuildInfo.VERSION);result.put("theme",theme);result.put("lookAndFeel",lookAndFeel);result.put("windowWidth",width);result.put("windowHeight",height);result.put("checks",checks);result.put("jarSha256",sha(Files.readAllBytes(jar)));result.put("nativeSwing",true);result.put("nativeBurpGui",false);result.put("syntheticPackets",true);result.put("syntheticSnapshot",true);result.put("targetHttpRequests",0);result.put("inspectionStarted",false);result.put("routes",ROUTES);result.put("groupedComponents",groups);result.put("captures",captures);
      Files.writeString(out.resolve("verification.json"),MetadataExport.json(result)+"\n");System.out.println("GroupedToolsNavigationNative: "+checks+" checks passed / "+theme+" / "+width+"x"+height);
    }catch(Throwable failure){failure.printStackTrace();try{ImageIO.write(new Robot().createScreenCapture(new Rectangle(0,0,1920,1200)),"png",out.resolve("failure.png").toFile());}catch(Exception ignored){}throw failure;
    }finally{edt(()->{for(Window window:Window.getWindows())window.dispose();});SimpleWorkflowTest.unload();}
  }
}
