package kr.workbench;
import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.awt.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
public final class CveNarrowNativeProbe extends WorkbenchTest {
  static Object value(Object o,String field){return SimpleWorkflowTest.unchecked(o,field);}
  static Map<String,Object> rect(Rectangle r){return Map.of("x",r.x,"y",r.y,"width",r.width,"height",r.height);}
  static Rectangle screen(Component c){return new Rectangle(c.getLocationOnScreen(),c.getSize());}
  static Map<String,Object> control(JComponent c){
    Rectangle full=screen(c), visible=new Rectangle(full);List<Object> ancestors=new ArrayList<>();
    for(Component p=c.getParent();p!=null;p=p.getParent()){
      if(!p.isShowing()){visible=new Rectangle();break;}
      visible=visible.intersection(screen(p));
      ancestors.add(Map.of("name",Objects.toString(p.getName(),""),"class",p.getClass().getName(),"bounds",rect(screen(p))));
    }
    Rectangle local=c.getVisibleRect();
    return Map.of("name",Objects.toString(c.getName(),""),"full",rect(full),"ancestorIntersection",rect(visible),"getVisibleRect",rect(local),"fullyVisible",full.equals(visible)&&local.equals(new Rectangle(0,0,c.getWidth(),c.getHeight())),"preferred",Map.of("width",c.getPreferredSize().width,"height",c.getPreferredSize().height),"ancestors",ancestors);
  }
  static void settle()throws Exception{for(int i=0;i<5;i++){edt(()->{});Thread.sleep(120);}}
  static void await(java.util.function.BooleanSupplier condition,String reason)throws Exception {for(int i=0;i<100;i++){boolean[] ok={false};edt(()->ok[0]=condition.getAsBoolean());if(ok[0])return;Thread.sleep(40);}throw new AssertionError("Native wait failed: "+reason);}
  static void resize(JFrame frame,int width,int height)throws Exception {edt(()->frame.setBounds(0,0,width,height));await(()->frame.getWidth()==width&&frame.getHeight()==height,"resize "+width);settle();if(frame.getWidth()!=width||frame.getHeight()!=height)throw new AssertionError("WM overrode requested geometry "+frame.getBounds());}
  public static void main(String[] args)throws Exception {
    Path output=Path.of(args[0]);Files.createDirectories(output);String theme=args[1];if(theme.equals("dark"))VisualGuideNative.darkTheme();
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));
    var entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(()->entry.initialize(SimpleWorkflowTest.previewApi()));edt(()->{});
    var ext=(WorkbenchExtension)get(entry,"workbench");var root=(JPanel)get(ext,"root");var desktop=(WorkbenchWindow)get(ext,"workbenchWindow");var research=(ExposureResearchPanel)get(ext,"exposureResearch");JFrame[] host={null};
    try {
      edt(()->{host[0]=new JFrame("Synthetic CVE narrow diagnostic");host[0].setBounds(10,10,900,600);JTabbedPane tabs=new JTabbedPane();tabs.addTab("Workbench",desktop.launcher);host[0].add(tabs);host[0].setVisible(true);});
      for(int i=0;i<40&&desktop.window==null;i++){Thread.sleep(50);edt(()->{});}if(desktop.window==null)throw new AssertionError("No native window");
      JFrame frame=desktop.window;await(()->(frame.getExtendedState()&Frame.MAXIMIZED_BOTH)==Frame.MAXIMIZED_BOTH&&root.getHeight()>=900,"initial maximize");settle();edt(()->frame.setExtendedState(Frame.NORMAL));await(()->(frame.getExtendedState()&Frame.MAXIMIZED_BOTH)==0,"restore normal state");settle();resize(frame,1024,720);
      edt(()->{
        ext.openFeature("exposure");research.target.setText("simple.example.test");
        var technology=new ExposureResearch.Product("nginx","nginx","1.22.1","Server: nginx/1.22.1","https://simple.example.test/api/documents/101","SYNTHETIC-UI:101");research.observed.addItem(technology);research.fillProduct(technology);
        var candidates=ExposureResearch.parseNvd(SimpleExposureResearchTest.fixture());research.display(new ExposureResearch.Batch(candidates,Set.of("CVE-2099-10001"),Map.of("CVE-2099-10001",.234),Instant.parse("2026-10-30T12:00:00Z"),"최신 조회 완료","최신 조회 완료",false),technology,false);
        research.tabs.setSelectedIndex(1);research.cveTable.setRowSelectionInterval(0,0);
      });settle();
      List<Object> samples=new ArrayList<>();Robot robot=new Robot();
      for(int pass=0;pass<2;pass++){
        if(pass==1){resize(frame,1600,1000);resize(frame,1024,720);}
        int iteration=pass;
        edt(()->{
          var bar=(JPanel)SimpleWorkflowTest.named(root,"wb.actions.exposure.cve.filters");var layout=(BorderLayout)bar.getLayout();Component context=layout.getLayoutComponent(BorderLayout.CENTER);Component freshness=research.freshness;
          Map<String,Object> sample=new LinkedHashMap<>();sample.put("pass",iteration);sample.put("frameBounds",rect(frame.getBounds()));sample.put("rootSize",Map.of("width",root.getWidth(),"height",root.getHeight()));sample.put("filterBar",rect(screen(bar)));sample.put("filterBarPreferredHeight",bar.getPreferredSize().height);sample.put("filterContext",rect(screen(context)));sample.put("freshness",rect(screen(freshness)));sample.put("freshnessConstraint",Objects.toString(layout.getConstraints(freshness),""));sample.put("freshnessText",research.freshness.getText());
          List<Object> flags=new ArrayList<>();for(JComponent c:List.of(research.minimum,research.remote,research.kevOnly,research.unknown))flags.add(control(c));sample.put("controls",flags);samples.add(sample);
          System.out.println("MEASUREMENT "+passName(iteration)+" "+MetadataExport.json(sample));
        });
        Rectangle[] bounds={null};edt(()->bounds[0]=frame.getBounds().intersection(frame.getGraphicsConfiguration().getBounds()));ImageIO.write(robot.createScreenCapture(bounds[0]),"png",output.resolve("cve-1024-pass-"+pass+".png").toFile());
      }
      Map<String,Object> result=new LinkedHashMap<>();result.put("theme",theme);result.put("nativeSwing",true);result.put("nativeBurpGui",false);result.put("syntheticPackets",true);result.put("targetHttpRequests",PayloadIntegrationTest.sent.get());result.put("samples",samples);result.put("jar",WorkbenchExtension.class.getProtectionDomain().getCodeSource().getLocation().toString());
      Files.writeString(output.resolve("native-cve-fit.json"),MetadataExport.json(result)+"\n");
    }finally{edt(()->{desktop.close();for(var h:PayloadIntegrationTest.modernUnloads)h.extensionUnloaded();for(var h:PayloadIntegrationTest.legacyUnloads)h.extensionUnloaded();for(Window w:Window.getWindows())w.dispose();});}
  }
  static String passName(int n){return n==0?"initial narrow":"wide/narrow resize";}
}
