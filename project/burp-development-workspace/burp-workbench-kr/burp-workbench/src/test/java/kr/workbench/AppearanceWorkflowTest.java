package kr.workbench;

import burp.*;
import burp.api.montoya.MontoyaApi;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import javax.swing.plaf.basic.*;
import javax.swing.tree.DefaultTreeCellRenderer;

/** Simulated host re-theming after registration, not a native Burp GUI claim. */
public final class AppearanceWorkflowTest extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    java.util.concurrent.atomic.AtomicInteger asynchronousErrors = new java.util.concurrent.atomic.AtomicInteger();
    var previousHandler = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread,error)->{asynchronousErrors.incrementAndGet();error.printStackTrace();});
    BurpExtender entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(() -> entry.initialize((MontoyaApi)PayloadIntegrationTest.host(MontoyaApi.class)));
    edt(() -> {});
    WorkbenchExtension ext = (WorkbenchExtension)get(entry,"workbench");
    JPanel root = (JPanel)get(ext,"root");
    JTree tree = (JTree)UiLayoutTest.named(root,"wb.navigation.tree");
    JButton mode = (JButton)get(ext,"captureButton");
    var renderer = tree.getCellRenderer();
    try {
      edt(() -> {
        root.setSize(1280,800);for(int i=0;i<6;i++)UiLayoutTest.layout(root);
        check(((JSplitPane)UiLayoutTest.named(root,"wb.traffic.workspace")).getOrientation()==JSplitPane.VERTICAL_SPLIT
            && ((JSplitPane)UiLayoutTest.named(root,"wb.traffic.messages")).getOrientation()==JSplitPane.HORIZONTAL_SPLIT,
            "reference layout shows full-width history above both packet panes");
        check(UiLayoutTest.named(root,"wb.traffic.title").getFont().getSize()>=24,"prominent traffic heading survives initial host registration");
        check(WorkbenchUi.font().canDisplayUpTo("요청·응답 목록 / 입력값 보안 검사")<0,"bundled sans font supports Korean captions");
        Dimension stable = mode.getPreferredSize();
        for(String expected:new String[]{"OFF","SCOPE"}) {
          mode.setSize(stable);
          var image=new BufferedImage(stable.width,stable.height,BufferedImage.TYPE_INT_RGB);
          var g=image.createGraphics();mode.paint(g);g.dispose();
          check(mode.getText().equals(expected)&&image.getRGB(19,stable.height/2)==WorkbenchSkin.captureColor(expected).getRGB(),
              "mode text and painted status lamp agree: "+expected);
          check(mode.getPreferredSize().equals(stable),"status changes keep button geometry stable: "+expected);
          mode.doClick(0);
        }
        check(mode.getText().equals("OFF"),"status lamp preserves OFF SCOPE OFF semantics");
        JTextArea input=(JTextArea)getUnchecked(ext,"codecIn");input.setText("UNCHANGED_HOST_THEME_INPUT");
        tree.setCellRenderer(new DefaultTreeCellRenderer());tree.setRowHeight(18);tree.setFont(new Font(Font.SERIF,Font.PLAIN,18));
        tree.setUI(new BasicTreeUI());
        mode.setUI(new BasicButtonUI());
        SwingUtilities.updateComponentTreeUI(root);
      });
      edt(() -> {});edt(() -> {});
      edt(() -> {
        for(int i=0;i<6;i++)UiLayoutTest.layout(root);
        check(tree.getCellRenderer()==renderer&&tree.getRowHeight()==40,"custom folders and spacing recover after host renderer and UI replacement");
        check(!(tree.getCellRenderer() instanceof DefaultTreeCellRenderer),"host gray folder renderer is removed after re-theming");
        check(UiLayoutTest.named(root,"wb.traffic.title").getFont().getSize()>=24,"heading size recovers after look-and-feel reset");
        check(((JTextArea)getUnchecked(ext,"codecIn")).getText().equals("UNCHANGED_HOST_THEME_INPUT"),"re-theming preserves user work");
        mode.setSize(mode.getPreferredSize());var image=new BufferedImage(mode.getWidth(),mode.getHeight(),BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();mode.paint(g);g.dispose();
        check(image.getRGB(19,mode.getHeight()/2)==WorkbenchSkin.captureColor("OFF").getRGB(),"colored capture lamp recovers after host button UI replacement");
        var path=tree.getSelectionPath();var bounds=tree.getPathBounds(path);
        Component cell=tree.getCellRenderer().getTreeCellRendererComponent(tree,path.getLastPathComponent(),true,false,true,1,false);
        check(cell instanceof JLabel label&&label.getIcon()!=null,"selected tool keeps its custom document icon");
        var group=tree.getPathForRow(0);cell=tree.getCellRenderer().getTreeCellRendererComponent(tree,group.getLastPathComponent(),false,true,false,0,false);
        Icon folder=((JLabel)cell).getIcon();var icon=new BufferedImage(24,24,BufferedImage.TYPE_INT_ARGB);g=icon.createGraphics();folder.paintIcon(tree,g,1,1);g.dispose();
        Color pixel=new Color(icon.getRGB(8,13),true);
        check(pixel.getRed()>200&&pixel.getGreen()>80&&pixel.getBlue()<100,"folder artwork remains orange after host re-theming");
        check(asynchronousErrors.get()==0,"host theme replacement emits no asynchronous EDT errors");
        check(PayloadIntegrationTest.sent.get()==0,"appearance updates and status cycling send zero HTTP requests");
      });
      edt(() -> root.setBackground(Color.MAGENTA));
      edt(() -> {});edt(() -> {});
      edt(() -> check(root.getBackground().equals(WorkbenchUi.background()),"root palette recovers when host changes only colors"));
    } finally {
      for(var unload:PayloadIntegrationTest.modernUnloads)unload.extensionUnloaded();
      for(var unload:PayloadIntegrationTest.legacyUnloads)unload.extensionUnloaded();
      edt(() -> {});
      Thread.setDefaultUncaughtExceptionHandler(previousHandler);
    }
    System.out.println("AppearanceWorkflowTest: "+checks+" checks passed (production Swing; simulated host re-theming; zero HTTP)");
  }
  private static Object getUnchecked(Object owner,String name) {try{return get(owner,name);}catch(Exception e){throw new AssertionError(e);}}
}
