package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Real guide-window/WM checks with synthetic Burp adapters; never captures active user packets. */
public final class VisualGuideNative extends WorkbenchTest {
  static Object call(Object owner,String name,Class<?>[] types,Object... args) {
    try {Method method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(owner,args);}
    catch(Exception failure){throw new IllegalStateException(name,failure);}
  }
  static Object call(Object owner,String name){return call(owner,name,new Class<?>[0]);}
  static void await(BooleanSupplier condition,String reason)throws Exception {
    long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while(System.nanoTime()<end){boolean[] ready={false};edt(()->ready[0]=condition.getAsBoolean());if(ready[0])return;Thread.sleep(25);}
    throw new AssertionError("Native Guide timeout: "+reason);
  }
  static Object value(Object owner,String name){try{return get(owner,name);}catch(Exception failure){throw new IllegalStateException(name,failure);}}
  static String id(JPanel panel){return (String)call(panel,"selectedId");}
  static boolean ready(JPanel panel){return Boolean.TRUE.equals(call(panel,"imageReady"));}
  /** Flush queued EDT work with a deadline; X11 global idle may never occur during periodic repaint. */
  static void settle() {
    if(EventQueue.isDispatchThread())throw new IllegalStateException("Native settle must run outside EDT");
    java.util.concurrent.FutureTask<Void> barrier=new java.util.concurrent.FutureTask<>(()->null);
    EventQueue.invokeLater(barrier);
    try {
      barrier.get(3,java.util.concurrent.TimeUnit.SECONDS);
      Toolkit.getDefaultToolkit().sync();
      Thread.sleep(80);
    }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Native settle interrupted",interrupted);}
    catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException failure){throw new IllegalStateException("Native EDT settle deadline exceeded",failure);}
  }

  static void key(Robot robot,int key){robot.keyPress(key);robot.keyRelease(key);settle();}
  static void activate(Robot robot,Window window)throws Exception {
    Point[] point={null};edt(()->{window.toFront();window.requestFocus();point[0]=window.getLocationOnScreen();point[0].translate(320,12);});
    robot.mouseMove(point[0].x,point[0].y);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);settle();await(window::isActive,"activate native guide window");
  }
  static void capture(Robot robot,Window window,Path output,String name)throws Exception {
    Rectangle[] bounds={null};edt(()->bounds[0]=window.getBounds().intersection(window.getGraphicsConfiguration().getBounds()));robot.mouseMove(bounds[0].x+bounds[0].width/2,bounds[0].y+12);settle();Thread.sleep(120);
    ImageIO.write(robot.createScreenCapture(bounds[0]),"png",output.resolve(name).toFile());
  }
  static String text(Component component){StringBuilder result=new StringBuilder();if(component instanceof JTextComponent t)result.append(t.getText());if(component instanceof JLabel l)result.append(l.getText());if(component instanceof AbstractButton b)result.append(b.getText());if(component instanceof Container c)for(Component child:c.getComponents())result.append('\n').append(text(child));return result.toString();}
  static long redPixels(Robot robot,Component component)throws Exception {
    Rectangle[] bounds={null};edt(()->bounds[0]=new Rectangle(component.getLocationOnScreen(),component.getSize()).intersection(component.getGraphicsConfiguration().getBounds()));
    BufferedImage image=robot.createScreenCapture(bounds[0]);long count=0;
    for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){Color c=new Color(image.getRGB(x,y));if(c.getRed()>180&&c.getGreen()<100&&c.getBlue()<110)count++;}
    return count;
  }
  static double luminance(Color color){double[] v={color.getRed()/255.0,color.getGreen()/255.0,color.getBlue()/255.0};for(int i=0;i<v.length;i++)v[i]=v[i]<=.04045?v[i]/12.92:Math.pow((v[i]+.055)/1.055,2.4);return .2126*v[0]+.7152*v[1]+.0722*v[2];}
  static Color backdrop(Component component){for(Component current=component;current!=null;current=current.getParent())if(current instanceof JComponent c&&c.isOpaque())return c.getBackground();return WorkbenchUi.background();}
  static boolean readableSteps(Component steps){int bodies=0;for(Component component:SimpleWorkflowTest.visibleComponents(steps))if(component instanceof JTextArea body&&!body.getText().isBlank()){bodies++;double fg=luminance(body.getForeground()),bg=luminance(backdrop(body));double ratio=(Math.max(fg,bg)+.05)/(Math.min(fg,bg)+.05);if(ratio<4.5)return false;}return bodies>=4;}
  static void darkTheme(){
    for(String name:List.of("Panel.background","TabbedPane.background","Button.background","CheckBox.background","ComboBox.background","control"))UIManager.put(name,new Color(30,33,39));
    UIManager.put("TabbedPane.selected",new Color(69,58,48));
    for(String name:List.of("TabbedPane.selectHighlight","TabbedPane.highlight","TabbedPane.shadow","TabbedPane.darkShadow","controlHighlight","controlLtHighlight","controlShadow","controlDkShadow"))UIManager.put(name,new Color(68,75,86));
    UIManager.put("TabbedPane.contentAreaColor",new Color(30,33,39));
    for(String name:List.of("Label.foreground","Button.foreground","CheckBox.foreground","ComboBox.foreground","TabbedPane.foreground","textText"))UIManager.put(name,new Color(228,233,241));
  }
  public static void main(String[] args)throws Exception {
    if(GraphicsEnvironment.isHeadless())throw new AssertionError("Guide native checks require an isolated display and WM");
    String theme=args.length>1?args[1]:"light";if(!Set.of("light","dark").contains(theme))throw new IllegalArgumentException("light or dark");if(theme.equals("dark"))darkTheme();
    String captureMode=args.length>2?args[2]:"full";if(!Set.of("full","quick").contains(captureMode))throw new IllegalArgumentException("full or quick captures");
    Path output=Path.of(args.length==0?"tmp/guide-native":args[0]);Files.createDirectories(output);Files.deleteIfExists(output.resolve("native-guide-summary.json"));
    Path loadedJar=Path.of(WorkbenchExtension.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    String loadedJarSha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(loadedJar)));
    int beforeHttp=PayloadIntegrationTest.sent.get();Object visual=null;WorkbenchWindow desktop=null;JFrame host=null;
    List<String> screens=new ArrayList<>();boolean[] captionsMatch={true};
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));
    var entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    try {
      edt(()->entry.initialize(SimpleWorkflowTest.previewApi()));edt(()->{});
      var extension=(WorkbenchExtension)get(entry,"workbench");var root=(JPanel)get(extension,"root");desktop=(WorkbenchWindow)get(extension,"workbenchWindow");final WorkbenchWindow controller=desktop;
      AtomicReference<JFrame> burpHost=new AtomicReference<>();
      edt(()->{JFrame frame=new JFrame("Synthetic Burp host · Guide verification");frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);frame.setBounds(20,20,960,700);JTabbedPane tabs=new JTabbedPane();tabs.addTab("Workbench",controller.launcher);frame.add(tabs);frame.setVisible(true);burpHost.set(frame);});host=burpHost.get();
      await(()->controller.window!=null&&controller.window.isShowing(),"open retained Workbench desktop");final JFrame main=controller.window;
      var originalTabs=(JTabbedPane)get(extension,"tabs");var traffic=(JTable)get(extension,"logTable");var codec=(JTextArea)get(extension,"codecIn");
      for(int i=0;i<3;i++)seedTraffic(extension,request("https://guide.example.test/api/documents/"+(100+i),"GET",true,List.of(header("Cookie","PRIVATE_NATIVE_GUIDE_SENTINEL"))),response(200,"{\"id\":"+(100+i)+"}"));
      Object[] selected={null};edt(()->{extension.openFeature("traffic");traffic.setRowSelectionInterval(1,1);codec.setText("PRIVATE_NATIVE_GUIDE_SENTINEL");selected[0]=value(extension,"current");});
      AbstractButton guide=(AbstractButton)SimpleWorkflowTest.named(root,"wb.visual.guide.open");check(guide!=null&&guide.getText().equals("Guide"),"active tool offers a visible top-right Guide text button");
      Component toolbar=SimpleWorkflowTest.named(root,"wb.navigation.toolbar");check(toolbar!=null&&toolbar.getHeight()<=46,"Guide stays inside the approved PDF single-row header");
      final Component selectedTool=originalTabs.getSelectedComponent();
      edt(()->guide.doClick(0));await(()->value(extension,"visualGuideWindow")!=null&&value(value(extension,"visualGuideWindow"),"window") instanceof JDialog d&&d.isShowing(),"Guide action opens its modeless dialog");
      visual=get(extension,"visualGuideWindow");final Object guides=visual;final JDialog window=(JDialog)value(guides,"window");final JPanel panel=(JPanel)value(guides,"panel");
      await(()->ready(panel),"decode packaged traffic guide screenshot");
      check(window.getOwner()==main&&!window.isModal()&&window.isResizable(),"guide is a separate resizable modeless window owned by Workbench");
      check(window.getContentPane()==panel||SwingUtilities.isDescendingFrom(panel,window.getContentPane()),"guide uses the actual production annotated panel");
      check(id(panel).equals("traffic"),"Guide opens the currently selected traffic screen");
      check(readableSteps(SimpleWorkflowTest.named(panel,"wb.visual.guide.steps")),"guide step descriptions retain readable contrast against their actual theme surfaces");
      edt(()->guide.doClick(0));check(value(guides,"window")==window,"repeated Guide clicks reuse one guide window");
      check(main.getContentPane()==root&&originalTabs.getSelectedComponent()==selectedTool&&value(extension,"current")==selected[0]&&traffic.getSelectedRow()==1&&codec.getText().equals("PRIVATE_NATIVE_GUIDE_SENTINEL"),"opening guidance retains main components, original identity, selection and edits");
      check(!text(panel).contains("PRIVATE_NATIVE_GUIDE_SENTINEL"),"guide explanatory widgets contain no private current-work sentinel");
      Robot robot=new Robot();robot.setAutoDelay(60);activate(robot,window);settle();
      Component canvas=SimpleWorkflowTest.named(panel,"wb.visual.guide.image");check(canvas!=null&&canvas.isShowing(),"annotated guide image is visibly rendered");
      check(redPixels(robot,canvas)>700,"real guide rendering contains visible red numeric callouts");
      edt(()->call(panel,"selectStep",new Class<?>[]{int.class},2));check(((Number)call(panel,"selectedStep")).intValue()==2,"selecting explanation updates the highlighted numbered step");
      try {
        Point local=(Point)call(canvas,"markerCenter",new Class<?>[]{int.class},3);Point[] screen={null};edt(()->{screen[0]=canvas.getLocationOnScreen();screen[0].translate(local.x,local.y);});
        robot.mouseMove(screen[0].x,screen[0].y);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);settle();
        check(((Number)call(panel,"selectedStep")).intValue()==3,"clicking red marker synchronizes the corresponding explanation");
      }catch(IllegalStateException missing){if(missing.getCause() instanceof NoSuchMethodException)throw new AssertionError("Native marker click requires markerCenter on canvas",missing);throw missing;}
      capture(robot,window,output,"01-guide-window.png");
      AbstractButton maximize=(AbstractButton)SimpleWorkflowTest.named(window,"wb.visual.guide.maximize");check(maximize!=null,"guide offers its own maximize action");
      Dimension normalSize=window.getSize();edt(()->maximize.doClick(0));await(()->window.getWidth()>=1500&&window.getHeight()>=950,"maximize guide to desktop usable bounds");capture(robot,window,output,"02-guide-maximized.png");
      edt(()->maximize.doClick(0));await(()->window.getSize().equals(normalSize),"restore guide window size");
      activate(robot,window);key(robot,KeyEvent.VK_F11);await(()->window.getWidth()>=1500&&window.getHeight()>=950,"native Guide F11 maximizes its own dialog");
      key(robot,KeyEvent.VK_F11);await(()->window.getSize().equals(normalSize),"native Guide F11 restores its own dimensions");
      check(!controller.isFullScreen()&&window.getSize().equals(normalSize),"native F11 roundtrip affects only the Guide window and preserves main mode");
      edt(()->extension.openFeature("auth"));edt(()->guide.doClick(0));await(()->id(panel).equals("auth")&&ready(panel),"reopen guide follows selected account tool");
      check(value(guides,"window")==window,"context refresh retains the same guide window");
      var research=(ExposureResearchPanel)get(extension,"exposureResearch");
      edt(()->{extension.openFeature("exposure");research.tabs.setSelectedIndex(0);guide.doClick(0);});await(()->id(panel).equals("exposure.search")&&ready(panel),"Guide follows search-exposure subtab");
      edt(()->{research.tabs.setSelectedIndex(1);guide.doClick(0);});await(()->id(panel).equals("exposure.cve")&&ready(panel),"Guide follows CVE subtab rather than reusing search image");
      JComboBox<?> screenChoice=(JComboBox<?>)SimpleWorkflowTest.named(panel,"wb.visual.guide.screen");check(screenChoice!=null&&screenChoice.getItemCount()>=16,"all primary and extended tool guides are available locally");
      for(int i=0;i<screenChoice.getItemCount();i++){
        Object screen=screenChoice.getItemAt(i);String screenId=(String)call(screen,"id");String screenTitle=(String)call(screen,"title");screens.add(screenId);
        edt(()->screenChoice.setSelectedItem(screen));await(()->id(panel).equals(screenId)&&ready(panel),"render guide "+screenId);
        edt(()->{captionsMatch[0]&=window.getTitle().equals("Guide · "+screenTitle);call(panel,"selectStep",new Class<?>[]{int.class},1);});if(captureMode.equals("full")||Set.of("traffic","auth","exposure.cve").contains(screenId))capture(robot,window,output,"guide-"+screenId.replace('.','-')+".png");
      }
      check(captionsMatch[0],"native guide window caption follows every dropdown-selected screen");
      Component beforeGuideHide=originalTabs.getSelectedComponent();edt(()->window.dispatchEvent(new WindowEvent(window,WindowEvent.WINDOW_CLOSING)));await(()->!window.isVisible(),"close only guide window");
      check(main.isVisible()&&originalTabs.getSelectedComponent()==beforeGuideHide&&codec.getText().equals("PRIVATE_NATIVE_GUIDE_SENTINEL"),"closing guidance leaves ongoing main work intact");
      edt(()->{controller.toggleFullScreen();guide.doClick(0);});await(()->controller.isFullScreen()&&window.isVisible()&&ready(panel),"show owned guide above fullscreen main");
      JPanel glass=new JPanel();glass.setOpaque(true);glass.setBackground(Color.MAGENTA);edt(()->{window.setGlassPane(glass);glass.setVisible(true);main.toFront();});settle();
      Point[] colorPoint={null};edt(()->{colorPoint[0]=glass.getLocationOnScreen();colorPoint[0].translate(glass.getWidth()/2,glass.getHeight()/2);});
      check(robot.getPixelColor(colorPoint[0].x,colorPoint[0].y).equals(Color.MAGENTA),"owned guide is visually above fullscreen Workbench after parent focus");
      edt(()->glass.setVisible(false));activate(robot,window);
      edt(()->{Component focus=KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();Window focusWindow=focus==null?null:SwingUtilities.getWindowAncestor(focus);System.out.println("INFO native Guide Escape focus="+(focus==null?"null":focus.getClass().getName())+" owner="+(focusWindow==null?"null":focusWindow.getClass().getName())+" guideActive="+window.isActive()+" guideFocused="+window.isFocused());});
      key(robot,KeyEvent.VK_ESCAPE);await(()->!window.isVisible(),"Esc hides guide without exiting main fullscreen");
      check(controller.isFullScreen()&&main.getGraphicsConfiguration().getDevice().getFullScreenWindow()==main,"guide Escape preserves parent fullscreen state");
      edt(()->guide.doClick(0));await(window::isVisible,"reopen guide after Escape");
      check(PayloadIntegrationTest.sent.get()==beforeHttp,"guide controls, image loading and navigation send zero target HTTP");
      edt(()->{for(var handler:List.copyOf(PayloadIntegrationTest.modernUnloads))handler.extensionUnloaded();for(var handler:List.copyOf(PayloadIntegrationTest.legacyUnloads))handler.extensionUnloaded();});edt(()->{});edt(()->{});
      check(!window.isDisplayable()&&Boolean.TRUE.equals(value(guides,"closed")),"unloading disposes guidance and closes its controller");
      edt(()->{guide.doClick(0);call(guides,"open",new Class<?>[]{String.class},"traffic");});edt(()->{});
      check(!window.isDisplayable()&&value(guides,"window")==null,"late Guide actions cannot resurrect a window after unload");
      check(loadedJarSha.equals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(loadedJar)))),"native guide run uses one unchanged packaged JAR throughout");
      Map<String,Object> summary=new LinkedHashMap<>(Map.of("result","passed","version",BuildInfo.VERSION,"theme",theme,"checks",checks,"nativeSwing",true,"nativeBurpGui",false,"syntheticPackets",true,"targetHttpRequests",PayloadIntegrationTest.sent.get()-beforeHttp,"screens",screens));
      summary.put("jarSha256",loadedJarSha);summary.put("captureMode",captureMode);
      Files.writeString(output.resolve("native-guide-summary.json"),MetadataExport.json(summary)+"\n");
      System.out.println("VisualGuideNative: "+checks+" checks passed (real Swing/WM; synthetic Burp adapters; zero target HTTP)");
    } finally {
      final Object guideCleanup=visual;final WorkbenchWindow desktopCleanup=desktop;final JFrame hostCleanup=host;
      edt(()->{if(guideCleanup!=null)try{call(guideCleanup,"close");}catch(Exception ignored){}if(desktopCleanup!=null)desktopCleanup.close();for(var handler:List.copyOf(PayloadIntegrationTest.modernUnloads))try{handler.extensionUnloaded();}catch(Exception ignored){}for(var handler:List.copyOf(PayloadIntegrationTest.legacyUnloads))try{handler.extensionUnloaded();}catch(Exception ignored){}if(hostCleanup!=null)hostCleanup.dispose();for(Window window:Window.getWindows())window.dispose();});
    }
  }
}
