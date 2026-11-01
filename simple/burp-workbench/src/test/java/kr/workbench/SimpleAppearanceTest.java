package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.Preferences;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;

/** Simulated host look-and-feel replacement preserves Simple work and core controls. */
public final class SimpleAppearanceTest extends WorkbenchTest {
  private static Object value(Object owner,String name) {
    try {return get(owner,name);}catch(Exception error){throw new IllegalStateException(error);}
  }
  public static void main(String[] args) throws Exception {
    var errors = new AtomicInteger();
    var previousHandler = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { errors.incrementAndGet(); error.printStackTrace(); });
    ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p,m,a) -> def(m));
    var entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks) PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    var saved = new ConcurrentHashMap<String,Object>();
    var preview = SimpleWorkflowTest.previewApi();
    var preferences = proxy(Preferences.class, (p,m,a) -> switch(m.getName()) {
      case "getString", "getBoolean", "getInteger" -> saved.get(a[0].toString());
      case "setString", "setBoolean", "setInteger" -> { saved.put(a[0].toString(),a[1]); yield null; }
      default -> def(m);
    });
    var persistence = proxy(Persistence.class, (p,m,a) -> m.getName().equals("preferences")?preferences:m.invoke(preview.persistence(),a));
    var api = proxy(MontoyaApi.class, (p,m,a) -> m.getName().equals("persistence")?persistence:m.invoke(preview,a));
    edt(() -> entry.initialize(api)); edt(() -> {});
    var extension = (WorkbenchExtension) get(entry,"workbench");
    var root = (JPanel) get(extension,"root");
    var mode = (JButton) get(extension,"captureButton");
    var traffic = (JTable) get(extension,"logTable");
    var input = (JTextArea) get(extension,"codecIn");
    var evidence = (BountySupportPanel) get(extension,"bountySupport");
    try {
      edt(() -> {
        check(WorkbenchUi.font().canDisplayUpTo("요청·응답 목록 · 기능 자동 매핑 · 계정 권한 비교 · 요청 재현·증거") < 0, "bundled font displays the retained tool captions");
        Dimension stable = mode.getPreferredSize();
        for (String expected : new String[]{"OFF","SCOPE"}) {
          mode.setSize(stable);
          var picture = new BufferedImage(stable.width,stable.height,BufferedImage.TYPE_INT_RGB);
          var graphics = picture.createGraphics(); mode.paint(graphics); graphics.dispose();
          check(mode.getText().equals(expected) && picture.getRGB(3,3)==(expected.equals("SCOPE")?new Color(233,247,240):new Color(248,249,251)).getRGB(), "capture state and approved status chip agree: "+expected);
          check(mode.getPreferredSize().equals(stable), "capture state preserves button geometry: "+expected);
          mode.doClick(0);
        }
        input.setText("SIMPLE_UNSAVED_INPUT"); evidence.evidenceTitle.setText("저장 전 작성 중인 제목");
        traffic.setRowHeight(48); traffic.setFont(new Font(Font.SERIF,Font.PLAIN,18));
        mode.setUI(new BasicButtonUI());
        SwingUtilities.updateComponentTreeUI(root);
      });
      edt(() -> {}); edt(() -> {});
      edt(() -> {
        check(input.getText().equals("SIMPLE_UNSAVED_INPUT") && evidence.evidenceTitle.getText().equals("저장 전 작성 중인 제목"), "host theme replacement preserves unsaved input and evidence draft");
        check(traffic.getRowHeight()==35 && traffic.getFont().getSize()==12, "PDF table typography recovers after host theme replacement");
        check(mode.getText().equals("OFF"), "host theme replacement preserves capture OFF state");
        mode.setSize(mode.getPreferredSize());
        var picture=new BufferedImage(mode.getWidth(),mode.getHeight(),BufferedImage.TYPE_INT_RGB);
        var graphics=picture.createGraphics();mode.paint(graphics);graphics.dispose();
        check(picture.getRGB(3,3)==new Color(248,249,251).getRGB(), "capture status chip recovers after host button UI replacement");
        SimpleWorkflowTest.click(root,"wb.simple.tool.bounty");
        SimpleWorkflowTest.click(root,"wb.simple.tool.traffic");
        check(SimpleWorkflowTest.toolSelected(root,"traffic"), "folder navigation remains selected and usable after host theme replacement");
        check(input.getText().equals("SIMPLE_UNSAVED_INPUT"), "moving between retained tools preserves local conversion input");
        check(PayloadIntegrationTest.sent.get()==0, "appearance and capture-state changes send zero HTTP");
        check(errors.get()==0, "simulated host look-and-feel change emits no asynchronous UI errors");
      });
      seedTraffic(extension,request("https://theme.example.test/api/theme","GET",true,List.of()),response(200,"{\"id\":7}"));
      edt(() -> {
        var moon=(JButton)SimpleWorkflowTest.named(root,"wb.traffic.header.tools");
        var gear=(JButton)SimpleWorkflowTest.named(root,"wb.navigation.tools.open");
        check(moon.getComponentPopupMenu()==null&&gear.getComponentPopupMenu()!=null,
            "the moon changes theme directly while the separately named gear owns window tools");
        ((JButton)SimpleWorkflowTest.named(root,"wb.traffic.guide.open")).doClick(0);
        var guide=(VisualGuideWindow)value(extension,"visualGuideWindow");
        guide.panel.selectStep(3);
        var inputDocument=input.getDocument();var trafficModel=traffic.getModel();
        var account=(JComboBox<?>)SimpleWorkflowTest.named(root,"wb.traffic.account");
        var target=(JComboBox<?>)SimpleWorkflowTest.named(root,"wb.traffic.target");
        var accountEditor=(JTextField)account.getEditor().getEditorComponent();
        var accountDocument=accountEditor.getDocument();var accountModel=account.getModel();var accountRenderer=account.getRenderer();var accountUi=account.getUI();
        String appliedAccount=((TrafficBrowser)value(extension,"trafficBrowser")).account.getText();
        accountEditor.setText("입력 중인 계정");accountEditor.setCaretPosition(3);
        var generic=new JComboBox<>(new String[]{"일반 선택"});WorkbenchUi.style(generic);
        var genericRenderer=generic.getRenderer();
        var scrollbar=new JScrollBar(JScrollBar.HORIZONTAL,50,10,0,100);WorkbenchUi.style(scrollbar);
        var scrollbarUi=scrollbar.getUI();
        var packet=(PacketInspector)value(extension,"responsePacket");
        var packetDocument=packet.text.getDocument();
        var selected=value(extension,"current");
        int row=traffic.getSelectedRow();
        String originalText=packet.text.getText();
        var hostBackground=UIManager.getColor("Panel.background");
        var hostButton=UIManager.getColor("Button.background");
        boolean initial=WorkbenchUi.dark();
        for(boolean dark:new boolean[]{!initial,initial}) {
          moon.doClick(0);
          WorkbenchUi.style(generic);
          WorkbenchUi.style(scrollbar);scrollbar.setSize(180,10);scrollbar.doLayout();
          var scrollbarImage=new BufferedImage(180,10,BufferedImage.TYPE_INT_RGB);
          var scrollbarGraphics=scrollbarImage.createGraphics();scrollbar.paint(scrollbarGraphics);scrollbarGraphics.dispose();
          check(scrollbar.getUI()==scrollbarUi&&scrollbarImage.getRGB(3,5)==WorkbenchUi.background().getRGB(),
              "the retained horizontal scrollbar paints the new track palette in "+dark);
          check(WorkbenchUi.dark()==dark&&kr.packetui.VisualTheme.dark()==dark,
              "one moon click switches the Workbench palette immediately to "+dark);
          check(root.getBackground().equals(kr.packetui.VisualTheme.background())
                  &&input.getBackground().equals(kr.packetui.VisualTheme.surface())
                  &&packet.text.getBackground().equals(kr.packetui.VisualTheme.surface()),
              "theme toggle refreshes the real root, authored editor and packet reader in "+dark);
          check(account.getUI()==accountUi&&account.getModel()==accountModel&&account.getRenderer()==accountRenderer
                  &&account.getEditor().getEditorComponent()==accountEditor&&accountEditor.getDocument()==accountDocument
                  &&accountEditor.getText().equals("입력 중인 계정")&&accountEditor.getCaretPosition()==3
                  &&((TrafficBrowser)value(extension,"trafficBrowser")).account.getText().equals(appliedAccount),
              "theme switching retains the live account editor, unfinished text and caret without applying its filter in "+dark
                  +" [ui="+(account.getUI()==accountUi)+" "+accountUi.getClass().getName()+"->"+account.getUI().getClass().getName()+", model="+(account.getModel()==accountModel)+", renderer="+(account.getRenderer()==accountRenderer)
                  +", editor="+(account.getEditor().getEditorComponent()==accountEditor)+", document="+(accountEditor.getDocument()==accountDocument)
                  +", draft="+accountEditor.getText()+", caret="+accountEditor.getCaretPosition()+", filter="+((TrafficBrowser)value(extension,"trafficBrowser")).account.getText()+"]");
          check(accountEditor.getBackground().equals(WorkbenchUi.surface())&&accountEditor.getForeground().equals(WorkbenchUi.ink()),
              "the actual editable account field follows the local palette in "+dark);
          root.setSize(1600,1000);for(int pass=0;pass<5;pass++)UiLayoutTest.layout(root);
          var editorImage=new BufferedImage(accountEditor.getWidth(),accountEditor.getHeight(),BufferedImage.TYPE_INT_RGB);
          var editorGraphics=editorImage.createGraphics();accountEditor.paint(editorGraphics);editorGraphics.dispose();
          check(editorImage.getRGB(accountEditor.getWidth()-5,accountEditor.getHeight()/2)==WorkbenchUi.surface().getRGB(),
              "the actual account editor paints the chosen surface instead of its host background in "+dark);
          for(JComboBox<?> combo:new JComboBox<?>[]{account,target,generic}){
            combo.setSize(Math.max(180,combo.getWidth()),32);combo.doLayout();
            var comboImage=new BufferedImage(combo.getWidth(),32,BufferedImage.TYPE_INT_RGB);
            var comboGraphics=comboImage.createGraphics();combo.paint(comboGraphics);comboGraphics.dispose();
            var popup=(javax.swing.plaf.basic.BasicComboPopup)combo.getUI().getAccessibleChild(combo,0);
            var list=popup.getList();
            Component plain=list.getCellRenderer().getListCellRendererComponent(list,combo.getItemAt(0),0,false,false);
            check(plain.getBackground().equals(WorkbenchUi.surface())&&plain.getForeground().equals(WorkbenchUi.ink()),
                "detached combo popup rows use the local normal palette in "+dark+" / "+combo.getName());
            Component marked=list.getCellRenderer().getListCellRendererComponent(list,combo.getItemAt(0),0,true,false);
            check(marked.getBackground().equals(WorkbenchUi.selected())&&marked.getForeground().equals(WorkbenchUi.ink()),
                "detached combo popup rows use the local selected palette in "+dark+" / "+combo.getName());
          }
          check(generic.getRenderer()==genericRenderer,"theme styling retains a generic combo renderer instance in "+dark);
          var dropdown=(JButton)SimpleWorkflowTest.named(root,"wb.workflow.traffic.use.open");
          var iconImage=new BufferedImage(15,15,BufferedImage.TYPE_INT_ARGB);
          var iconGraphics=iconImage.createGraphics();dropdown.getIcon().paintIcon(dropdown,iconGraphics,0,0);iconGraphics.dispose();
          int inkPixels=0;Color expectedInk=dropdown.getForeground();
          for(int y=0;y<15;y++)for(int x=0;x<15;x++){
            Color pixel=new Color(iconImage.getRGB(x,y),true);
            if(pixel.getAlpha()>32&&Math.abs(pixel.getRed()-expectedInk.getRed())<4
                &&Math.abs(pixel.getGreen()-expectedInk.getGreen())<4
                &&Math.abs(pixel.getBlue()-expectedInk.getBlue())<4)inkPixels++;
          }
          check(inkPixels>5,"the retained dropdown chevron paints the new readable ink after a theme toggle in "+dark);
          check(saved.get(WorkbenchTheme.KEY).equals(dark?"dark":"light"),
              "theme selection is saved to the isolated extension preference in "+dark);
          check(UIManager.getColor("Panel.background").equals(hostBackground)
                  &&UIManager.getColor("Button.background").equals(hostButton),
              "theme toggle leaves Burp-wide Swing defaults unchanged in "+dark);
          check(input.getDocument()==inputDocument&&input.getText().equals("SIMPLE_UNSAVED_INPUT")
                  &&traffic.getModel()==trafficModel&&traffic.getSelectedRow()==row
                  &&value(extension,"current")==selected&&packet.text.getDocument()==packetDocument
                  &&packet.text.getText().equals(originalText),
              "theme toggle preserves authored documents, selected original and packet contents in "+dark);
          check(guide.panel.selectedStep()==3&&guide.panel.canvas.imageKey.endsWith("|"+dark),
              "the already-open guide follows the palette and retains its selected numbered explanation in "+dark);
          try {
            var mapperDark=Class.forName("kr.featuremapper.MapperUi").getDeclaredMethod("dark");mapperDark.setAccessible(true);
            check(mapperDark.invoke(null).equals(dark),"embedded Mapper status painting follows the chosen palette in "+dark);
          } catch (ReflectiveOperationException error) {throw new IllegalStateException(error);}
        }
        check(PayloadIntegrationTest.sent.get()==0,"theme and retained tool menus never send target HTTP");
        // A fresh controller reads the saved choice; closing it returns to the host-following default.
        var restored=new WorkbenchTheme(api);
        check(WorkbenchUi.dark()==initial,"a fresh theme controller restores the saved light/dark choice");
        restored.close();
        Color host=UIManager.getColor("Panel.background");
        check(WorkbenchUi.dark()==(host!=null&&host.getRed()+host.getGreen()+host.getBlue()<360),
            "disposing the theme controller releases its local override");
      });
    } finally { SimpleWorkflowTest.unload(); Thread.setDefaultUncaughtExceptionHandler(previousHandler); }
    System.out.println("SimpleAppearanceTest: "+checks+" checks passed (production Swing; simulated host theme; zero HTTP)");
  }
}
