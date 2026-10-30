package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Actual native Swing window/WM checks with synthetic API/packet adapters; no Burp or target HTTP. */
public final class WorkbenchWindowNative extends WorkbenchTest {
  static void await(BooleanSupplier ready,String reason)throws Exception {
    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
    while(System.nanoTime()<deadline){boolean[] done={false};edt(()->done[0]=ready.getAsBoolean());if(done[0])return;Thread.sleep(25);}
    throw new AssertionError("Native window timeout: "+reason);
  }
  static void key(Robot robot,int code){robot.keyPress(code);robot.keyRelease(code);robot.waitForIdle();}
  static void activate(Robot robot,JFrame frame,JPanel root)throws Exception {
    Point[] point={null};edt(()->{frame.toFront();point[0]=root.getLocationOnScreen();point[0].translate(300,12);});robot.mouseMove(point[0].x,point[0].y);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);robot.waitForIdle();await(frame::isActive,"activate workspace before native shortcut");
  }
  static void tabClick(JTabbedPane tabs,int index){Rectangle r=tabs.getBoundsAt(index);tabs.dispatchEvent(new MouseEvent(tabs,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,r.x+r.width/2,r.y+r.height/2,1,false,MouseEvent.BUTTON1));}
  static void capture(Robot robot,JFrame frame,Path output,String name)throws Exception {
    robot.waitForIdle();Thread.sleep(150);Rectangle[] bounds={null};edt(()->bounds[0]=frame.getBounds().intersection(frame.getGraphicsConfiguration().getBounds()));
    ImageIO.write(robot.createScreenCapture(bounds[0]),"png",output.resolve(name).toFile());
  }
  public static void main(String[] args)throws Exception {
    if(GraphicsEnvironment.isHeadless())throw new AssertionError("A display and window manager are required; run with -Djava.awt.headless=false");
    String theme=args.length>1?args[1]:"light";
    if(!Set.of("light","dark").contains(theme))throw new IllegalArgumentException("theme must be light or dark");
    if(theme.equals("dark")){
      for(String name:List.of("Panel.background","TabbedPane.background","Button.background","CheckBox.background","ComboBox.background","control"))UIManager.put(name,new Color(30,33,39));
      UIManager.put("TabbedPane.selected",new Color(69,58,48));
      for(String name:List.of("TabbedPane.selectHighlight","TabbedPane.highlight","TabbedPane.shadow","TabbedPane.darkShadow","controlHighlight","controlLtHighlight","controlShadow","controlDkShadow"))UIManager.put(name,new Color(68,75,86));
      UIManager.put("TabbedPane.contentAreaColor",new Color(30,33,39));
      for(String name:List.of("Label.foreground","Button.foreground","CheckBox.foreground","ComboBox.foreground","TabbedPane.foreground","textText"))UIManager.put(name,new Color(228,233,241));
    }
    Path output=Path.of(args.length==0?"tmp/window-native":args[0]);Files.createDirectories(output);Files.deleteIfExists(output.resolve("native-window-summary.json"));
    AtomicReference<JPanel> registered=new AtomicReference<>();AtomicReference<JFrame> host=new AtomicReference<>();
    WorkbenchExtension extension=null;WorkbenchWindow desktop=null;JFrame actual=null;
    int httpBefore=PayloadIntegrationTest.sent.get();
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));
    var base=SimpleWorkflowTest.previewApi();var baseUi=base.userInterface();
    var ui=proxy(UserInterface.class,(p,m,a)->{if(m.getName().equals("registerSuiteTab"))registered.set((JPanel)a[1]);return m.invoke(baseUi,a);});
    MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("userInterface")?ui:m.invoke(base,a));
    var entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    try {
      edt(()->entry.initialize(api));edt(()->{});
      extension=(WorkbenchExtension)get(entry,"workbench");desktop=(WorkbenchWindow)get(extension,"workbenchWindow");
      final var ext=extension;final var controller=desktop;var root=(JPanel)get(ext,"root");var pages=(JTabbedPane)get(ext,"tabs");
      var traffic=(JTable)get(ext,"logTable");var codec=(JTextArea)get(ext,"codecIn");
      check(registered.get()==controller.launcher&&controller.workspace==root,"registered Burp suite component is the launcher and retained workspace is separate");
      check(controller.window==null,"loading alone does not open a native window before the launcher is shown");
      JTabbedPane[] outer={null},inner={null};
      edt(()->{
        host.set(new JFrame("Synthetic Burp tab host · native window verification"));host.get().setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);host.get().setBounds(20,20,1000,720);
        inner[0]=new JTabbedPane();inner[0].addTab("Workbench Simple",controller.launcher);
        outer[0]=new JTabbedPane();outer[0].addTab("Other",new JPanel());outer[0].addTab("Workbench KR",inner[0]);host.get().setContentPane(outer[0]);host.get().setVisible(true);
      });edt(()->{});
      check(controller.window==null,"an unselected outer Burp tab does not open Workbench");
      edt(()->outer[0].setSelectedIndex(1));
      await(()->controller.window!=null&&controller.window.isShowing(),"launcher selection autoopens workspace");actual=controller.window;final JFrame frame=actual;
      await(()->(frame.getExtendedState()&Frame.MAXIMIZED_BOTH)==Frame.MAXIMIZED_BOTH&&root.getHeight()>=900,"native default maximize and layout");
      check(frame.isResizable()&&!frame.isUndecorated()&&frame.getContentPane()==root,"normal mode is a standard resizable decorated frame owning the exact workspace");
      check(SwingUtilities.getWindowAncestor(root)==frame&&SwingUtilities.getWindowAncestor(controller.launcher)==host.get(),"workspace owns its desktop frame while launcher stays in Burp");
      check(root.getHeight()>host.get().getContentPane().getHeight(),"maximized workspace gains vertical space outside the synthetic Burp chrome");
      for(int i=0;i<8;i++)seedTraffic(ext,request("https://window.example.test/api/documents/"+(100+i),"GET",true,List.of(header("Accept","application/json"))),response(i==6?403:200,"{\"id\":"+(100+i)+",\"title\":\"창 검증용 합성 문서\"}"));
      await(()->traffic.getRowCount()==8,"synthetic traffic publication");
      Object[] original={null};
      edt(()->{ext.openFeature("traffic");traffic.setRowSelectionInterval(2,2);codec.setText("NATIVE_WINDOW_UNSAVED_INPUT");try{original[0]=get(ext,"current");((JLabel)get(ext,"status")).setText("실제 Swing 별도 창 검증 · 합성 패킷 · 대상 HTTP 전송 0건");}catch(Exception e){throw new IllegalStateException(e);}});
      final Component selectedPage=pages.getSelectedComponent();
      Robot robot=new Robot();robot.setAutoDelay(60);capture(robot,frame,output,"02-maximized.png");
      edt(()->frame.setExtendedState(Frame.NORMAL));await(()->(frame.getExtendedState()&Frame.MAXIMIZED_BOTH)==0,"native maximize restore");
      edt(()->frame.setBounds(100,80,1280,800));await(()->frame.getWidth()==1280&&frame.getHeight()==800,"normal frame resize");capture(robot,frame,output,"01-window.png");
      edt(()->{frame.dispatchEvent(new WindowEvent(frame,WindowEvent.WINDOW_CLOSING));check(!frame.isVisible()&&frame.isDisplayable(),"native close hides without disposing the retained workspace");});
      edt(()->tabClick(inner[0],0));await(frame::isShowing,"already-selected inner tab click reopens hidden window");
      edt(()->{check(controller.window==frame&&frame.getContentPane()==root&&pages.getSelectedComponent()==selectedPage&&codec.getText().equals("NATIVE_WINDOW_UNSAVED_INPUT"),"inner-tab reopen retains the same frame, tool and unsaved edits");try{check(get(ext,"current")==original[0]&&traffic.getSelectedRow()==2,"reopen preserves exact original identity and selected row");}catch(Exception e){throw new IllegalStateException(e);}});
      edt(()->{controller.hide();tabClick(outer[0],1);});await(frame::isShowing,"already-selected outer Workbench tab click reopens hidden window");
      edt(()->{controller.open();controller.openButton.doClick(0);});
      long visible=Arrays.stream(Window.getWindows()).filter(w->w instanceof JFrame&&w.isShowing()&&"wb.window.frame".equals(w.getName())).count();
      check(visible==1&&controller.window==frame,"repeated opens keep one live Workbench frame");
      AtomicInteger dialogClosed=new AtomicInteger();AtomicReference<JDialog> owned=new AtomicReference<>();JTextField ownedInput=new JTextField("NATIVE_OWNED_UNSAVED_INPUT");JPanel ownedPanel=new JPanel(new BorderLayout());ownedPanel.add(ownedInput,BorderLayout.NORTH);
      edt(()->{owned.set(WorkbenchUi.settingsWindow(root,"Synthetic Workbench owned dialog",ownedPanel,false,dialogClosed::incrementAndGet));ownedPanel.setBackground(Color.MAGENTA);check(owned.get()!=null&&owned.get().getOwner()==frame&&!owned.get().isModal(),"modeless tool dialogs are owned by the separate Workbench frame");frame.toFront();controller.fullScreenButton.requestFocusInWindow();});
      robot.waitForIdle();activate(robot,frame,root);key(robot,KeyEvent.VK_F11);
      GraphicsDevice device=frame.getGraphicsConfiguration().getDevice();
      await(()->controller.isFullScreen()&&device.getFullScreenWindow()==frame,"native F11 enters fullscreen on the retained frame");
      Rectangle screen=frame.getGraphicsConfiguration().getBounds();await(()->root.getLocationOnScreen().equals(screen.getLocation())&&root.getWidth()==screen.width&&root.getHeight()==screen.height,"fullscreen uses full native screen for workspace");
      check(frame.getContentPane()==root&&controller.window==frame&&pages.getSelectedComponent()==selectedPage&&codec.getText().equals("NATIVE_WINDOW_UNSAVED_INPUT"),"fullscreen retains exact frame, working components and edits");
      edt(()->{check(owned.get().isVisible()&&owned.get().isDisplayable()&&dialogClosed.get()==0&&ownedInput.getText().equals("NATIVE_OWNED_UNSAVED_INPUT"),"F11 preserves visible owned dialog peer, close callback and unsaved input");frame.toFront();});robot.waitForIdle();
      Point[] dialogPoint={null};edt(()->{dialogPoint[0]=ownedPanel.getLocationOnScreen();dialogPoint[0].translate(ownedPanel.getWidth()/2,ownedPanel.getHeight()/2);});
      check(robot.getPixelColor(dialogPoint[0].x,dialogPoint[0].y).equals(Color.MAGENTA),"owned dialog remains visually above fullscreen parent after parent focus");
      Component nativeToolbar=SimpleWorkflowTest.named(root,"wb.navigation.toolbar.line");
      check(nativeToolbar!=null&&nativeToolbar.getHeight()<=40,"fullscreen keeps the compact navigation header on one row");
      edt(()->owned.get().setVisible(false));capture(robot,frame,output,"03-fullscreen.png");edt(()->{owned.get().setVisible(true);frame.toFront();controller.fullScreenButton.requestFocusInWindow();});robot.waitForIdle();activate(robot,frame,root);
      key(robot,KeyEvent.VK_ESCAPE);await(()->!controller.isFullScreen()&&device.getFullScreenWindow()==null&&!frame.isUndecorated(),"native Esc restores decorated window and releases fullscreen device");
      await(()->frame.getWidth()==1280&&frame.getHeight()==800,"fullscreen exit restores normal dimensions");
      edt(()->check(owned.get().isVisible()&&owned.get().isDisplayable()&&dialogClosed.get()==0&&ownedInput.getText().equals("NATIVE_OWNED_UNSAVED_INPUT"),"Esc preserves the same visible dialog and unsaved input"));
      edt(()->{controller.fullScreenButton.doClick(0);check(controller.isFullScreen(),"visible toolbar fullscreen button uses the real desktop controller");frame.dispatchEvent(new WindowEvent(frame,WindowEvent.WINDOW_CLOSING));check(!frame.isVisible()&&!owned.get().isVisible()&&owned.get().isDisplayable()&&dialogClosed.get()==0&&device.getFullScreenWindow()==null,"fullscreen close hides main and dialog and releases device without disposing inputs");controller.openButton.doClick(0);check(frame.isShowing()&&controller.isFullScreen()&&device.getFullScreenWindow()==frame&&owned.get().isVisible()&&ownedInput.getText().equals("NATIVE_OWNED_UNSAVED_INPUT"),"launcher button reapplies fullscreen and restores retained dialog inputs");});
      edt(()->{controller.hide();controller.hide();controller.open();check(owned.get().isVisible()&&ownedInput.getText().equals("NATIVE_OWNED_UNSAVED_INPUT"),"repeated hide calls preserve the dialogs scheduled for reopen");});
      check(PayloadIntegrationTest.sent.get()==httpBefore,"window navigation, maximize, fullscreen and dialogs send zero target HTTP requests");
      edt(()->{
        controller.hide();tabClick(inner[0],0);
        for(var handler:PayloadIntegrationTest.modernUnloads)handler.extensionUnloaded();
        for(var handler:PayloadIntegrationTest.legacyUnloads)handler.extensionUnloaded();
      });edt(()->{});edt(()->{});
      check(controller.closed&&controller.window==null&&!frame.isDisplayable()&&device.getFullScreenWindow()==null,"unload disposes native frame, releases fullscreen device and blocks queued tab-open callback");
      check(!owned.get().isDisplayable()&&dialogClosed.get()==1,"owned dialog is disposed with one close callback only on unload");
      edt(()->{controller.open();controller.openButton.doClick(0);controller.toggleFullScreen();check(controller.window==null&&!controller.openButton.isEnabled(),"unloaded launcher and fullscreen actions cannot reopen");});
      check(root.getActionMap().get("wb.window.fullscreen")==null&&root.getActionMap().get("wb.window.restore")==null,"unload releases global window shortcuts");
      Map<String,Object> summary=new LinkedHashMap<>(Map.of("result","passed","theme",theme,"checks",checks,"version",BuildInfo.VERSION,"display","isolated Xorg dummy + xfwm4","nativeSwing",true,"nativeBurpGui",false,"syntheticPackets",true,"targetHttpRequests",PayloadIntegrationTest.sent.get()-httpBefore,"screenshots",List.of("01-window.png","02-maximized.png","03-fullscreen.png")));
      Path loadedJar=Path.of(WorkbenchWindow.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      summary.put("jarSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(loadedJar))));
      Files.writeString(output.resolve("native-window-summary.json"),MetadataExport.json(summary)+"\n");
      System.out.println("WorkbenchWindowNative: "+checks+" checks passed (real Swing/WM, synthetic Burp adapters, zero target HTTP)");
    } finally {
      final WorkbenchWindow cleanup=desktop;edt(()->{if(cleanup!=null)cleanup.close();for(var handler:PayloadIntegrationTest.modernUnloads)try{handler.extensionUnloaded();}catch(Exception ignored){}for(var handler:PayloadIntegrationTest.legacyUnloads)try{handler.extensionUnloaded();}catch(Exception ignored){}for(Window window:Window.getWindows())window.dispose();});
    }
  }
}
