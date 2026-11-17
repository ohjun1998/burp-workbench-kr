package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.awt.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Identical native-window fixture for Simple1.4.21 and the Strix port.
 * Does not link Strix-only classes at compile time, does not paint to a buffer.
 */
public final class Strix1421Native extends WorkbenchTest {
  static Path out;static JPanel root;static Robot robot;
  static final List<Map<String,Object>> views=new ArrayList<>();
  static Object field(Object o,String n){try{return get(o,n);}catch(Exception e){throw new IllegalStateException(e);}}
  static Object call(Object o,String n){try{Method m=o.getClass().getDeclaredMethod(n);m.setAccessible(true);return m.invoke(o);}catch(Exception e){throw new IllegalStateException(e);}}
  static void namedClick(Component body,String name){Component c=SimpleWorkflowTest.named(body,name);if(!(c instanceof AbstractButton b))throw new AssertionError("Named button missing: "+name);b.doClick(0);}
  static void selectTab(Component c,String title){if(c instanceof JTabbedPane t)for(int i=0;i<t.getTabCount();i++)if(t.getTitleAt(i).equals(title))t.setSelectedIndex(i);if(c instanceof Container p)for(Component x:p.getComponents())selectTab(x,title);}
  static void waitUi(java.util.function.BooleanSupplier test,String reason)throws Exception{
    long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while(System.nanoTime()<until){boolean[] result={false};edt(()->result[0]=test.getAsBoolean());if(result[0])return;Thread.sleep(25);}throw new AssertionError("Native wait timeout: "+reason);
  }
  static void collect(Component c,Map<String,Integer> seen,List<Map<String,Object>> rows){
    if(!c.isShowing())return;String name=c.getName();
    if(name!=null&&name.startsWith("wb.")&&c.getWidth()>0&&c.getHeight()>0){
      int i=seen.merge(name,1,Integer::sum);Rectangle r=SwingUtilities.convertRectangle(c.getParent(),c.getBounds(),root);
      Map<String,Object> row=new LinkedHashMap<>();row.put("key",name+"#"+i);row.put("name",name);row.put("class",c.getClass().getName());row.put("x",r.x);row.put("y",r.y);row.put("width",r.width);row.put("height",r.height);rows.add(row);
    }
    if(c instanceof Container p)for(Component child:p.getComponents())collect(child,seen,rows);
  }
  static void capture(Window window,String file,boolean measure)throws Exception{
    edt(()->{window.toFront();window.requestFocus();});Toolkit.getDefaultToolkit().sync();Thread.sleep(500);
    Rectangle[] bounds={null};List<Map<String,Object>> rows=new ArrayList<>();String[] title={""};
    edt(()->{if(!window.isShowing()||!window.isDisplayable())throw new AssertionError("Not a live window");bounds[0]=window.getBounds();title[0]=window instanceof Frame f?f.getTitle():((Dialog)window).getTitle();if(measure)collect(root,new HashMap<>(),rows);});
    ImageIO.write(robot.createScreenCapture(bounds[0]),"png",out.resolve(file).toFile());
    views.add(Map.of("png",file,"title",title[0],"x",bounds[0].x,"y",bounds[0].y,"width",bounds[0].width,"height",bounds[0].height,"controls",rows));
  }
  static void theme(String theme){
    if(!theme.equals("dark"))return;
    for(String k:List.of("Panel.background","TabbedPane.background","Button.background","CheckBox.background","ComboBox.background","control"))UIManager.put(k,new Color(30,33,39));
    UIManager.put("TabbedPane.selected",new Color(69,58,48));
    for(String k:List.of("TabbedPane.selectHighlight","TabbedPane.highlight","TabbedPane.shadow","TabbedPane.darkShadow","controlHighlight","controlLtHighlight","controlShadow","controlDkShadow","TabbedPane.contentAreaColor"))UIManager.put(k,new Color(38,44,53));
    for(String k:List.of("Label.foreground","Button.foreground","CheckBox.foreground","ComboBox.foreground","TabbedPane.foreground","textText"))UIManager.put(k,new Color(228,233,241));
  }
  public static void main(String[] args)throws Exception{
    if(GraphicsEnvironment.isHeadless())throw new AssertionError("Actual Xorg display required");
    out=Path.of(args[0]);boolean nativeMenu=args.length>5&&args[5].equals("menu");String edition=args[1],chosenTheme=args[2];int width=Integer.parseInt(args[3]),height=Integer.parseInt(args[4]);Files.createDirectories(out);theme(chosenTheme);
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));
    BurpExtender entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    MontoyaApi base=SimpleWorkflowTest.previewApi();
    var scope=proxy(burp.api.montoya.scope.Scope.class,(p,m,a)->m.getName().equals("isInScope")&&a!=null ? "demo-bank.example.test".equals(java.net.URI.create(a[0].toString()).getHost()) : def(m));
    MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("scope")?scope:m.invoke(base,a));
    edt(()->entry.initialize(api));edt(()->{});
    WorkbenchExtension ext=(WorkbenchExtension)get(entry,"workbench");WorkbenchWindow desktop=(WorkbenchWindow)get(ext,"workbenchWindow");root=(JPanel)get(ext,"root");JFrame[] frame={null};
    int before=PayloadIntegrationTest.sent.get();
    try{
      edt(()->{desktop.open();frame[0]=desktop.window;frame[0].setExtendedState(JFrame.NORMAL);frame[0].setBounds(80,50,width,height);});
      robot=new Robot();Toolkit.getDefaultToolkit().sync();Thread.sleep(300);
      for(var item:List.of(new String[]{"/api/accounts/balance","잔액 조회"},new String[]{"/api/accounts/transactions","거래 내역"},new String[]{"/api/transfers","이체"}))seedTraffic(ext,request("https://demo-bank.example.test"+item[0],"GET",true,List.of(header("Accept","application/json"))),response(200,"{\"feature\":\""+item[1]+"\",\"example\":true}"));
      edt(()->{ext.openFeature("traffic");JTable traffic=(JTable)field(ext,"logTable");traffic.setRowSelectionInterval(0,0);});
      waitUi(()->((JTable)field(ext,"logTable")).getRowCount()==3,"same synthetic traffic rows");
      capture(frame[0],"01-main.png",true);
      edt(()->{
        // Use the actual compact primary controller rather than selecting hidden tabs.
        namedClick(root,"wb.navigation.primary.dashboard");
        ScopeDashboardPanel panel=(ScopeDashboardPanel)field(ext,"scopeDashboard");
        DiskTrafficStore.PacketRef ref=((DiskTrafficStore)field(ext,"trafficStore")).ref(1);
        var finding=new ScopeInspection.Finding("SECRET","설정 정보 후보 · 합성 예시",ref.url(),"GET","사용자 A","형식 후보","높음","유효성 미검증","화면 비교용 합성 근거",ref.id(),1,0,"same-fixture");
        var page=new ScopeInspection.Page(ref.url(),"GET","사용자 A",200,"JSON",true,"합성 예시","same-fixture",ref);
        panel.update(new ScopeInspection.Snapshot("동일 화면 비교용 합성 결과",ScopeInspection.State.COMPLETED,"합성 예시 · 실제 점검 실행 아님",1,1,0,0,0,0,List.of(finding),List.of(page),List.of(),0,0),10,"https://demo-bank.example.test");
      });
      ScopeDashboardPanel dash=(ScopeDashboardPanel)field(ext,"scopeDashboard");waitUi(()->!dash.aggregating&&!dash.visibleRows.isEmpty(),"same synthetic dashboard projection");
      edt(()->dash.table.setRowSelectionInterval(0,0));capture(frame[0],"02-dashboard.png",true);
      if(edition.equals("strix")){
        edt(()->{ext.openFeature("traffic");((JTable)field(ext,"logTable")).setRowSelectionInterval(0,0);});
        JTable sourceTable=(JTable)field(ext,"logTable");JPopupMenu popup=(JPopupMenu)sourceTable.getClientProperty("wb.traffic.popup");
        JMenuItem[] selectedMenu={null};
        edt(()->{for(Component item:popup.getComponents())if(item instanceof JMenuItem m&&"wb.simple.strix.prepare".equals(m.getName()))selectedMenu[0]=m;});
        if(selectedMenu[0]==null)throw new AssertionError("Final Simple21 popup lost Strix item after removeAll");
        if(nativeMenu){
          Point[] point={null};edt(()->{point[0]=sourceTable.getLocationOnScreen();Rectangle cell=sourceTable.getCellRect(0,2,true);point[0].translate(cell.x+Math.min(100,cell.width/2),cell.y+cell.height/2);});
          robot.mouseMove(point[0].x,point[0].y);robot.mousePress(java.awt.event.InputEvent.BUTTON3_DOWN_MASK);robot.mouseRelease(java.awt.event.InputEvent.BUTTON3_DOWN_MASK);
          waitUi(()->popup.isShowing()&&selectedMenu[0].isShowing(),"actual right-click Strix item visible");
          Point[] menuPoint={null};edt(()->{if(!selectedMenu[0].isEnabled())throw new AssertionError("Prepared Strix menu action disabled");menuPoint[0]=selectedMenu[0].getLocationOnScreen();menuPoint[0].translate(selectedMenu[0].getWidth()/2,selectedMenu[0].getHeight()/2);});
          robot.mouseMove(menuPoint[0].x,menuPoint[0].y);Thread.sleep(300);ImageIO.write(robot.createScreenCapture(frame[0].getBounds()),"png",out.resolve("06-strix-popup.png").toFile());
          robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);Thread.sleep(300);
        }else edt(()->selectedMenu[0].doClick(0));
        JDialog dialog=null;for(Window w:Window.getWindows())if(w instanceof JDialog d&&d.isShowing()&&d.getTitle().contains("Strix 진단"))dialog=d;
        if(dialog==null)throw new AssertionError("Actual Strix production dialog missing");final JDialog d=dialog;
        Object panel=field(ext,"strixPanel");
        edt(()->{((JTextArea)field(panel,"context")).setText("화면 확인용 합성 예시입니다.\n계정 A와 B는 각자 소유한 계좌 정보만 조회할 수 있어야 합니다.\n이 캡처에서는 실제 Strix·모델·대상 요청을 실행하지 않습니다.");d.setBounds(300,90,1260,960);});
        capture(d,"03-strix-prepare.png",false);
        edt(()->selectTab((Component)panel,"모델·Strix 설정"));capture(d,"04-strix-model.png",false);
        edt(()->{JComboBox<?> provider=(JComboBox<?>)field(panel,"provider");for(int i=0;i<provider.getItemCount();i++)if(provider.getItemAt(i).toString().contains("ChatGPT"))provider.setSelectedIndex(i);});capture(d,"05-strix-chatgpt.png",false);
      }
      if(PayloadIntegrationTest.sent.get()!=before)throw new AssertionError("Capture unexpectedly sent HTTP");
      Class<?> identity=Class.forName("kr.workbench.BuildInfo");String version=(String)identity.getField("VERSION").get(null),display=(String)identity.getField("DISPLAY_NAME").get(null);
      Path loaded=Path.of(identity.getProtectionDomain().getCodeSource().getLocation().toURI());String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(loaded)));
      Map<String,Object> result=new LinkedHashMap<>();result.put("edition",edition);result.put("theme",chosenTheme);result.put("version",version);result.put("displayName",display);result.put("jar",loaded.toString());result.put("jarSha256",sha);result.put("frameWidth",width);result.put("frameHeight",height);result.put("nativeSwing",true);result.put("nativeBurp",false);result.put("syntheticHostAndPackets",true);result.put("headless",false);result.put("targetRequests",0);result.put("strixExecutions",0);result.put("modelCalls",0);result.put("capture","Robot.createScreenCapture of live Xorg/xfwm4 windows, no printAll or image editing");result.put("views",views);result.put("strixMenuVerified",edition.equals("strix"));result.put("strixMenuActivation",nativeMenu?"Robot right-click and real popup item click":"JMenuItem.doClick");
      Files.writeString(out.resolve("native-report.json"),MetadataExport.json(result)+"\n");System.out.println("NATIVE_CAPTURE "+edition+" "+version+" "+chosenTheme+" "+width+"x"+height+" "+views.size()+" windows, zero target/model calls");
    }finally{edt(()->{for(var h:PayloadIntegrationTest.modernUnloads)try{h.extensionUnloaded();}catch(Exception ignored){}for(var h:PayloadIntegrationTest.legacyUnloads)try{h.extensionUnloaded();}catch(Exception ignored){}desktop.close();for(Window w:Window.getWindows())w.dispose();});}
  }
}
