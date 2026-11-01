package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.Preferences;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.plaf.basic.ComboPopup;
import javax.swing.text.Document;

/** Real header-popup pixels and actions using isolated synthetic Burp adapters. */
public final class HeaderToolsNative extends WorkbenchTest {
  static Object value(Object owner,String field) {
    try {return get(owner,field);}catch(Exception error){throw new IllegalStateException(field,error);}
  }
  static void await(BooleanSupplier predicate,String description)throws Exception {
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
    while(System.nanoTime()<deadline){boolean[] done={false};edt(()->done[0]=predicate.getAsBoolean());if(done[0])return;Thread.sleep(30);}
    throw new AssertionError("Header popup timeout: "+description);
  }
  static void settle()throws Exception {edt(()->{});Toolkit.getDefaultToolkit().sync();Thread.sleep(150);}
  static void click(Robot robot,Component component)throws Exception {
    Point[] point={null};edt(()->{if(component==null||!component.isShowing())throw new AssertionError("Missing visible native control");point[0]=component.getLocationOnScreen();point[0].translate(component.getWidth()/2,component.getHeight()/2);});
    robot.mouseMove(point[0].x,point[0].y);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);settle();
  }
  static void escape(Robot robot)throws Exception {robot.keyPress(KeyEvent.VK_ESCAPE);robot.keyRelease(KeyEvent.VK_ESCAPE);settle();}
  static JMenuItem item(JPopupMenu popup,String name) {
    for(Component component:popup.getComponents())if(component instanceof JMenuItem item&&name.equals(item.getName()))return item;
    throw new AssertionError("Missing menu controller: "+name);
  }
  static void capture(Robot robot,JPanel root,Path destination)throws Exception {
    Rectangle[] bounds={null};edt(()->bounds[0]=new Rectangle(root.getLocationOnScreen(),root.getSize()));
    robot.mouseMove(bounds[0].x+20,bounds[0].y+55);settle();
    ImageIO.write(robot.createScreenCapture(bounds[0]),"png",destination.toFile());
  }
  static double luminance(Color color) {
    double[] channels={color.getRed()/255d,color.getGreen()/255d,color.getBlue()/255d};
    for(int i=0;i<channels.length;i++)channels[i]=channels[i]<=.04045?channels[i]/12.92:Math.pow((channels[i]+.055)/1.055,2.4);
    return channels[0]*.2126+channels[1]*.7152+channels[2]*.0722;
  }
  static void comboTheme(Robot robot,JComboBox<?> combo,String description)throws Exception {
    Point[] sample={null};edt(()->{sample[0]=combo.getLocationOnScreen();sample[0].translate(combo.getWidth()-35,combo.getHeight()/2);});
    settle();check(robot.getPixelColor(sample[0].x,sample[0].y).equals(WorkbenchUi.surface()),description+" actual displayed current-value background uses the local theme");
    edt(()->combo.setPopupVisible(true));settle();
    ComboPopup popup=(ComboPopup)combo.getUI().getAccessibleChild(combo,0);JList<?> list=popup.getList();
    edt(()->{
      check(list.isShowing()&&list.getBackground().equals(WorkbenchUi.surface())&&list.getForeground().equals(WorkbenchUi.ink()),description+" actual detached dropdown list uses local text/surface colors");
      check(list.getSelectionBackground().equals(WorkbenchUi.selected())&&list.getSelectionForeground().equals(WorkbenchUi.ink()),description+" actual detached dropdown selection uses local colors");
      combo.setPopupVisible(false);
    });settle();
  }
  static void trafficScrollTheme(Robot robot,JTable traffic,String description)throws Exception {
    JScrollPane scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,traffic);JScrollBar bar=scroll.getHorizontalScrollBar();
    if(!bar.isShowing())return;
    Point[] sample={null};edt(()->{sample[0]=bar.getLocationOnScreen();sample[0].translate(bar.getWidth()-4,bar.getHeight()/2);});
    settle();check(robot.getPixelColor(sample[0].x,sample[0].y).equals(WorkbenchUi.background()),description+" actual visible scrollbar track uses the current local theme");
  }
  static void mapperTheme(Robot robot,WorkbenchExtension extension)throws Exception {
    Object mapper=value(extension,"featureMapper");JComboBox<?> runs=(JComboBox<?>)value(mapper,"runs"),sessions=(JComboBox<?>)value(mapper,"sessions");
    edt(()->extension.openFeature("mapper"));await(runs::isShowing,"actual Mapper filters become visible");
    comboTheme(robot,runs,"embedded Mapper execution selector");comboTheme(robot,sessions,"embedded Mapper session selector");
    edt(()->extension.openFeature("traffic"));settle();
  }
  static void darkTheme() {
    for(String name:List.of("Panel.background","TabbedPane.background","Button.background","CheckBox.background","ComboBox.background","control"))UIManager.put(name,new Color(30,33,39));
    for(String name:List.of("Label.foreground","Button.foreground","CheckBox.foreground","ComboBox.foreground","TabbedPane.foreground","textText"))UIManager.put(name,new Color(228,233,241));
  }
  public static void main(String[] args)throws Exception {
    try{run(args);}catch(Throwable failure){failure.printStackTrace();edt(()->{for(Window window:Window.getWindows())window.dispose();});System.exit(1);}
  }
  static void run(String[] args)throws Exception {
    if(GraphicsEnvironment.isHeadless())throw new AssertionError("Run on isolated native Xorg display");
    Path output=Path.of(args[0]);Files.createDirectories(output);String theme=args[1];
    int width=Integer.parseInt(args[2]),height=Integer.parseInt(args[3]);boolean observe=args.length>4&&args[4].equals("observe");
    if(theme.equals("dark"))darkTheme();
    Path jar=Path.of(WorkbenchExtension.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
    int beforeHttp=PayloadIntegrationTest.sent.get();ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));
    var baseApi=SimpleWorkflowTest.previewApi();var basePersistence=baseApi.persistence();var basePreferences=basePersistence.preferences();
    Map<String,String> savedPreferences=new java.util.concurrent.ConcurrentHashMap<>();
    Preferences preferences=proxy(Preferences.class,(p,m,a)->switch(m.getName()){
      case "setString"->{if(a[1]==null)savedPreferences.remove(a[0].toString());else savedPreferences.put(a[0].toString(),a[1].toString());yield null;}
      case "getString"->savedPreferences.get(a[0].toString());
      default->m.invoke(basePreferences,a);
    });
    Persistence persistence=proxy(Persistence.class,(p,m,a)->m.getName().equals("preferences")?preferences:m.invoke(basePersistence,a));
    MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("persistence")?persistence:m.invoke(baseApi,a));
    BurpExtender entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    WorkbenchWindow desktop=null;
    try {
      edt(()->entry.initialize(api));edt(()->{});
      WorkbenchExtension extension=(WorkbenchExtension)get(entry,"workbench");JPanel root=(JPanel)get(extension,"root");
      desktop=(WorkbenchWindow)get(extension,"workbenchWindow");final WorkbenchWindow controller=desktop;
      edt(controller::open);await(()->controller.window!=null&&controller.window.isShowing(),"open retained Workbench desktop");JFrame frame=controller.window;
      await(()->frame.getWidth()>1800,"initial native maximize completes");
      edt(()->frame.setExtendedState(Frame.NORMAL));settle();
      edt(()->{frame.setBounds(30,40,width,height);extension.openFeature("traffic");});
      await(()->frame.getWidth()==width&&frame.getHeight()==height,"normal window geometry");
      for(int i=0;i<3;i++)seedTraffic(extension,request("https://header.example.test/api/documents/"+(100+i),"GET",true,List.of(header("Accept","application/json"))),response(200,"{\"id\":"+(100+i)+"}"));
      JTable traffic=(JTable)get(extension,"logTable");JTextArea codec=(JTextArea)get(extension,"codecIn");
      edt(()->{traffic.setRowSelectionInterval(1,1);codec.setText("UNSAVED_SYNTHETIC_HEADER_INPUT");});
      Robot robot=new Robot();robot.setAutoDelay(65);settle();
      JButton moon=(JButton)SimpleWorkflowTest.named(root,"wb.traffic.header.tools");
      if(!observe){
        JComboBox<?> account=(JComboBox<?>)SimpleWorkflowTest.named(root,"wb.traffic.account"),target=(JComboBox<?>)SimpleWorkflowTest.named(root,"wb.traffic.target");
        JTextField editor=(JTextField)account.getEditor().getEditorComponent();Document editorDocument=editor.getDocument();Object accountFilter=account.getSelectedItem();String originalEditorText=editor.getText();
        edt(()->editor.setText("계정 초안"));
        boolean originalDark=WorkbenchUi.dark();Color originalBackground=root.getBackground(),hostBackground=UIManager.getColor("Panel.background");
        click(robot,moon);await(()->WorkbenchUi.dark()!=originalDark,"moon directly changes local theme");
        check(moon.getComponentPopupMenu()==null&&MenuSelectionManager.defaultManager().getSelectedPath().length==0,"theme action opens no tools popup");
        check(!root.getBackground().equals(originalBackground),"theme click repaints the real workspace background");
        check(Objects.equals(savedPreferences.get(BuildInfo.storageKey("ui.theme.v1")),WorkbenchUi.dark()?"dark":"light"),"direct theme choice is persisted through the configured API");
        check(Objects.equals(UIManager.getColor("Panel.background"),hostBackground),"local theme switch preserves host UI defaults");
        check(traffic.getSelectedRow()==1&&codec.getText().equals("UNSAVED_SYNTHETIC_HEADER_INPUT"),"theme toggle preserves selection and authored input");
        edt(()->{
          check(account.getEditor().getEditorComponent()==editor&&editor.getDocument()==editorDocument&&editor.getText().equals("계정 초안")&&Objects.equals(account.getSelectedItem(),accountFilter),"theme switch preserves actual combo editor/document and pending draft without committing its filter");
          check(editor.getBackground().equals(WorkbenchUi.surface())&&editor.getForeground().equals(WorkbenchUi.ink()),"actual editable account field uses local theme background and text");
          editor.setText(originalEditorText);
        });
        comboTheme(robot,account,"account after first moon click");comboTheme(robot,target,"target after first moon click");
        trafficScrollTheme(robot,traffic,"after first moon click");mapperTheme(robot,extension);
        capture(robot,root,output.resolve("theme-toggled.png"));
        edt(()->editor.setText("계정 초안"));
        click(robot,moon);await(()->WorkbenchUi.dark()==originalDark,"second moon click restores original theme");
        check(root.getBackground().equals(originalBackground),"theme roundtrip restores actual workspace colors");
        check(Objects.equals(savedPreferences.get(BuildInfo.storageKey("ui.theme.v1")),originalDark?"dark":"light"),"restored local theme is persisted");
        edt(()->{
          check(account.getEditor().getEditorComponent()==editor&&editor.getDocument()==editorDocument&&editor.getText().equals("계정 초안")&&Objects.equals(account.getSelectedItem(),accountFilter),"theme roundtrip preserves exact combo editor/document and pending draft without committing its filter");
          check(editor.getBackground().equals(WorkbenchUi.surface())&&editor.getForeground().equals(WorkbenchUi.ink()),"restored editable account field uses local theme background and text");
          editor.setText(originalEditorText);
        });
        comboTheme(robot,account,"account after theme roundtrip");comboTheme(robot,target,"target after theme roundtrip");
        trafficScrollTheme(robot,traffic,"after theme roundtrip");mapperTheme(robot,extension);
      }
      JButton tools=observe?moon:(JButton)SimpleWorkflowTest.named(root,"wb.navigation.tools.open");JPopupMenu popup=tools.getComponentPopupMenu();
      click(robot,tools);await(popup::isShowing,"real header tools popup");
      List<Map<String,Object>> records=new ArrayList<>();
      edt(()->{
        check(popup.getComponentCount()==6,"actual header popup retains six original controllers");
        Rectangle popupBounds=new Rectangle(popup.getLocationOnScreen(),popup.getSize());
        check(frame.getGraphicsConfiguration().getBounds().contains(popupBounds),"native popup remains inside display bounds");
        if(!observe)check(new Rectangle(root.getLocationOnScreen(),root.getSize()).contains(popupBounds),"header tools popup remains fully inside the working window");
        for(Component component:popup.getComponents())if(component instanceof JMenuItem item){
          String label=Objects.toString(item.getText(),"");Insets insets=item.getInsets();FontMetrics metrics=item.getFontMetrics(item.getFont());
          double foreground=luminance(item.getForeground()),background=luminance(item.getBackground());double contrast=(Math.max(foreground,background)+.05)/(Math.min(foreground,background)+.05);
          records.add(Map.of("name",Objects.toString(item.getName(),""),"text",label,"icon",item.getIcon()!=null,"width",item.getWidth(),"height",item.getHeight(),"contrast",contrast));
          if(!observe){
            check(!label.isBlank(),"header entry has a readable caption: "+item.getName());
            check(item.getWidth()>metrics.stringWidth(label)+insets.left+insets.right+(item.getIcon()==null?0:item.getIcon().getIconWidth()),"menu caption/icon fits its native row: "+label);
            check(item.getHeight()>=metrics.getHeight()&&item.getHeight()<=50,"menu entry has compact readable height: "+label);
            check(contrast>=4.5,"menu caption contrasts against its actual theme background: "+label);
            if(!label.equals("Guide"))check(item.getIcon()!=null,"compact source icon remains visible in menu: "+label);
          }
        }
      });
      capture(robot,root,output.resolve("header-tools-menu.png"));
      if(!observe){
        JMenuItem guide=item(popup,"wb.visual.guide.open.menu");click(robot,guide);
        await(()->value(extension,"visualGuideWindow")!=null&&value(value(extension,"visualGuideWindow"),"window") instanceof JDialog dialog&&dialog.isShowing(),"menu Guide opens real modeless guide");
        JDialog guideWindow=(JDialog)value(value(extension,"visualGuideWindow"),"window");
        check(!guideWindow.isModal()&&guideWindow.getOwner()==frame,"Guide menu retains real owned modeless window");
        edt(()->guideWindow.setVisible(false));
        check(traffic.getSelectedRow()==1&&codec.getText().equals("UNSAVED_SYNTHETIC_HEADER_INPUT"),"opening Guide preserves traffic selection and authored input");
        click(robot,tools);await(popup::isShowing,"reopen header tools");click(robot,item(popup,"wb.window.fullscreen.menu"));
        await(controller::isFullScreen,"native menu enters real fullscreen");
        await(()->frame.getWidth()>1800,"fullscreen peer geometry completes");
        check(item(popup,"wb.window.fullscreen.menu").getText().equals("창 모드"),"fullscreen proxy caption updates while source remains icon-only");
        click(robot,tools);await(popup::isShowing,"fullscreen header tools");click(robot,item(popup,"wb.window.fullscreen.menu"));await(()->!controller.isFullScreen(),"menu restores real window mode");
        await(()->frame.getWidth()==width&&frame.getHeight()==height,"fullscreen roundtrip restores original window geometry");
        check(item(popup,"wb.window.fullscreen.menu").getText().equals("전체화면"),"restored fullscreen caption stays synchronized");
        click(robot,tools);await(popup::isShowing,"route menu");click(robot,item(popup,"wb.simple.dashboard.open.menu"));
        await(()->((JTabbedPane)value(extension,"tabs")).getSelectedComponent()==value(extension,"scopeDashboard"),"menu opens real existing dashboard");
        check(codec.getText().equals("UNSAVED_SYNTHETIC_HEADER_INPUT"),"dashboard route preserves authored source input");
        edt(()->extension.openFeature("traffic"));
        click(robot,tools);await(popup::isShowing,"final menu reopen");escape(robot);await(()->!popup.isVisible(),"Escape closes only header popup");
      } else escape(robot);
      check(PayloadIntegrationTest.sent.get()==beforeHttp,"header menu and guidance send zero target HTTP");
      Map<String,Object> summary=new LinkedHashMap<>();summary.put("result",observe?"observed":"passed");summary.put("sourceVersion",BuildInfo.VERSION);summary.put("theme",theme);summary.put("windowWidth",width);summary.put("windowHeight",height);summary.put("checks",checks);summary.put("entries",records);summary.put("jarSha256",sha);summary.put("targetHttpRequests",PayloadIntegrationTest.sent.get()-beforeHttp);summary.put("nativeSwing",true);summary.put("nativeBurpGui",false);summary.put("syntheticPackets",true);
      Files.writeString(output.resolve("verification.json"),MetadataExport.json(summary)+"\n");System.out.println("HeaderToolsNative: "+checks+" checks "+(observe?"observed":"passed")+" / "+BuildInfo.VERSION+" / "+theme+" / "+width+"x"+height);
    } finally {
      final WorkbenchWindow cleanup=desktop;edt(()->{for(var handler:List.copyOf(PayloadIntegrationTest.modernUnloads))try{handler.extensionUnloaded();}catch(Exception ignored){}for(var handler:List.copyOf(PayloadIntegrationTest.legacyUnloads))try{handler.extensionUnloaded();}catch(Exception ignored){}if(cleanup!=null)cleanup.close();for(Window window:Window.getWindows())window.dispose();});
    }
    System.exit(0);
  }
}
