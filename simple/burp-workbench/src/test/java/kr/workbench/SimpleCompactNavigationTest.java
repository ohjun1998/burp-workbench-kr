package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.*;
import javax.swing.tree.*;

/** Production compact header and visible navigation state, with retained engines and no HTTP. */
public final class SimpleCompactNavigationTest extends WorkbenchTest {
  private static Object value(Object owner,String key){try{return get(owner,key);}catch(Exception e){throw new IllegalStateException(e);}}
  private static void layout(JPanel root,int width){root.setSize(width,720);for(int i=0;i<6;i++)UiLayoutTest.layout(root);}
  private static int pixel(JTree tree,int x,int y){BufferedImage image=new BufferedImage(tree.getWidth(),tree.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();tree.paint(g);g.dispose();return image.getRGB(x,y);}
  public static void main(String[] args)throws Exception {
    BountyFeaturesTest.factory();var entry=new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(()->entry.initialize(SimpleWorkflowTest.previewApi()));
    var extension=(WorkbenchExtension)get(entry,"workbench");var root=(JPanel)get(extension,"root");
    var tree=(JTree)SimpleWorkflowTest.named(root,"wb.navigation.tree");
    var toolbar=(JPanel)SimpleWorkflowTest.named(root,"wb.navigation.toolbar");
    var actions=(JPanel)SimpleWorkflowTest.named(root,"wb.navigation.actions");
    var window=(WorkbenchWindow)get(extension,"workbenchWindow");
    var pages=(JTabbedPane)get(extension,"tabs");
    Object retainedAuth=get(extension,"authCompare"),retainedResearch=get(extension,"exposureResearch");
    int sent=PayloadIntegrationTest.sent.get();
    try {
      edt(()->{
        check(SimpleWorkflowTest.named(root,"wb.navigation.brand") instanceof JLabel brand && brand.getText().equals("Workbench"),"approved PDF header includes the compact Workbench wordmark once");
        for(int width:new int[]{1024,1280,1600}){
          layout(root,width);
          check(toolbar.getHeight()==46,"approved PDF header remains one 46px row at "+width+" pixels");
          Rectangle line=SwingUtilities.convertRectangle(actions.getParent(),actions.getBounds(),toolbar);
          check(line.y>=0&&line.y+line.height<=toolbar.getHeight(),"header actions stay inside the same row at "+width);
          for(Component c:actions.getComponents())if(c instanceof AbstractButton button){
            check(Boolean.TRUE.equals(button.getClientProperty("wb.guide.button"))?button.getText().equals("Guide")&&button.getWidth()>=60:button.getIcon()!=null&&button.getText().isEmpty(),"compact action keeps the requested Guide caption and other visible icons: "+button.getName());
            check(button.getAccessibleContext().getAccessibleName()!=null&&!button.getAccessibleContext().getAccessibleName().isBlank()&&button.getToolTipText()!=null,"compact action retains an accessible name and tooltip: "+button.getName());
          }
        }
        var tools=(JButton)SimpleWorkflowTest.named(root,"wb.navigation.tools.open");
        JPopupMenu toolsMenu=tools.getComponentPopupMenu();
        int sourceCount=0;
        for(Component c:actions.getComponents())if(c instanceof AbstractButton source){
          sourceCount++;
          var menu=(JMenuItem)SimpleWorkflowTest.named(toolsMenu,source.getName()+".menu");
          check(menu!=null && menu.getText()!=null && !menu.getText().isBlank(),
              "the actual shared header popup has no blank action row: "+source.getName());
          if(source.getText().isBlank()){
            check(menu.getText().equals(source.getAccessibleContext().getAccessibleName())
                    && menu.getIcon()==source.getIcon() && menu.getIcon()!=null,
                "the actual header menu restores the compact source caption and icon: "+source.getName());
          }
        }
        check(sourceCount==toolsMenu.getComponentCount(),
            "the header popup has one readable row for every retained shortcut");
        window.fullScreenButton.setText("전체화면 해제");layout(root,1024);
        check(window.fullScreenButton.getText().isEmpty()&&window.fullScreenButton.getAccessibleContext().getAccessibleName().contains("해제")&&toolbar.getHeight()==46,"fullscreen exit state updates accessibility without widening the PDF toolbar");
        var fullScreenMenu=(JMenuItem)SimpleWorkflowTest.named(toolsMenu,"wb.window.fullscreen.menu");
        check("전체화면 해제".equals(fullScreenMenu.getText()),
            "the assembled header popup follows fullscreen state after the compact source clears its text");
        window.fullScreenButton.getAccessibleContext().setAccessibleName("전체화면");
        check("전체화면".equals(fullScreenMenu.getText()),
            "an accessible-only fullscreen update replaces the assembled menu caption immediately");
        var dashboardMenu=(JCheckBoxMenuItem)SimpleWorkflowTest.named(toolsMenu,"wb.simple.dashboard.open.menu");
        dashboardMenu.doClick(0);
        check(dashboardMenu.isSelected()&&pages.getSelectedComponent()==value(extension,"scopeDashboard"),
            "the visible header dashboard menu opens the retained page and reflects its selected state");
        extension.openFeature("traffic");
        check(!dashboardMenu.isSelected(),
            "leaving the dashboard clears the actual header menu state through its retained model");
        ((JMenuItem)SimpleWorkflowTest.named(toolsMenu,"wb.visual.guide.open.menu")).doClick(0);
        var visualGuide=(VisualGuideWindow)value(extension,"visualGuideWindow");
        check(visualGuide!=null&&"traffic".equals(visualGuide.panel.selectedId()),
            "the actual header Guide row opens the current tool documentation controller");
        var modelRoot=(DefaultMutableTreeNode)tree.getModel().getRoot();int leaves=0;
        for(int i=0;i<modelRoot.getChildCount();i++)leaves+=modelRoot.getChildAt(i).getChildCount();
        check(modelRoot.getChildCount()==5&&leaves==12,"approved sidebar retains all five folders and twelve actual tool leaves including the dashboard");
        for(int row=0;row<tree.getRowCount();row++)tree.expandRow(row);
        layout(root,1024);Rectangle last=tree.getRowBounds(tree.getRowCount()-1);
        check(last.y+last.height<=((JViewport)tree.getParent()).getExtentSize().height,"all approved sidebar rows fit within the actual viewport");
        TreePath discovery=SimpleWorkflowTest.toolPath(root,"js");Rectangle hovered=tree.getPathBounds(discovery);
        int x=tree.getWidth()-12,y=hovered.y+hovered.height/2;int before=pixel(tree,x,y);
        tree.dispatchEvent(new MouseEvent(tree,MouseEvent.MOUSE_MOVED,System.currentTimeMillis(),0,x,y,0,false));
        check(pixel(tree,x,y)!=before,"hover paints a visible background beyond the text without a timer");
        tree.dispatchEvent(new MouseEvent(tree,MouseEvent.MOUSE_EXITED,System.currentTimeMillis(),0,x,y,0,false));
        check(pixel(tree,x,y)==before,"leaving the sidebar clears the hover decoration");
        tree.setSelectionPath(discovery);layout(root,1024);
        check(pages.getSelectedComponent()==value(extension,"jsAnalysis")||SimpleWorkflowTest.selectedFeature(pages).equals("js"),"compact sidebar selection opens the existing JS route");
        Rectangle selected=tree.getPathBounds(discovery);
        check(pixel(tree,12,selected.y+selected.height/2)==WorkbenchUi.selected().getRGB(),"selected tool uses the approved full-row blue selection fill");
        MouseEvent tipEvent=new MouseEvent(tree,MouseEvent.MOUSE_MOVED,System.currentTimeMillis(),0,40,selected.y+selected.height/2,0,false);
        check(tree.getToolTipText(tipEvent).equals(FeatureGuide.feature("js").summary()),"tool hover exposes the actual feature explanation");
        var group=(DefaultMutableTreeNode)modelRoot.getChildAt(1);TreePath groupPath=new TreePath(group.getPath());tree.collapsePath(groupPath);
        check(!tree.isExpanded(groupPath),"folder collapse remains available in the compact tree");tree.expandPath(groupPath);
        extension.openFeature("auth");extension.openFeature("exposure");
        check(value(extension,"authCompare")==retainedAuth&&pages.getSelectedComponent()==retainedResearch,"compact navigation retains auth and CVE engine instances");
        check(PayloadIntegrationTest.sent.get()==sent,"compact menu selection hover and fullscreen labels send zero target requests");
      });
    }finally{
      for(var h:List.copyOf(PayloadIntegrationTest.modernUnloads))h.extensionUnloaded();
      for(var h:List.copyOf(PayloadIntegrationTest.legacyUnloads))h.extensionUnloaded();edt(()->{});
    }
    System.out.println("SimpleCompactNavigationTest: "+checks+" checks passed (production Swing controls; zero HTTP)");
  }
}
