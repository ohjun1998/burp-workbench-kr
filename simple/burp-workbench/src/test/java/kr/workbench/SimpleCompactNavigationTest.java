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
        check(SimpleWorkflowTest.named(root,"wb.navigation.brand")==null,"compact header omits the repeated large Workbench brand");
        for(int width:new int[]{1024,1280,1600}){
          layout(root,width);
          check(toolbar.getHeight()>=32&&toolbar.getHeight()<=36,"header remains one compact row at "+width+" pixels");
          Rectangle line=SwingUtilities.convertRectangle(actions.getParent(),actions.getBounds(),toolbar);
          check(line.y>=0&&line.y+line.height<=toolbar.getHeight(),"header actions stay inside the same row at "+width);
          for(Component c:actions.getComponents())if(c instanceof AbstractButton button){
            check(Boolean.TRUE.equals(button.getClientProperty("wb.guide.button"))?button.getText().equals("Guide")&&button.getWidth()>=60:button.getIcon()!=null&&button.getText().isEmpty(),"compact action keeps the requested Guide caption and other visible icons: "+button.getName());
            check(button.getAccessibleContext().getAccessibleName()!=null&&!button.getAccessibleContext().getAccessibleName().isBlank()&&button.getToolTipText()!=null,"compact action retains an accessible name and tooltip: "+button.getName());
          }
        }
        window.fullScreenButton.setText("전체화면 해제");layout(root,1024);
        check(window.fullScreenButton.getText().isEmpty()&&window.fullScreenButton.getAccessibleContext().getAccessibleName().contains("해제")&&toolbar.getHeight()<=36,"fullscreen exit state updates accessibility without widening the toolbar");
        var modelRoot=(DefaultMutableTreeNode)tree.getModel().getRoot();int leaves=0;
        for(int i=0;i<modelRoot.getChildCount();i++)leaves+=modelRoot.getChildAt(i).getChildCount();
        check(modelRoot.getChildCount()==5&&leaves==11,"compact sidebar retains all five folders and eleven actual tool leaves");
        for(int row=0;row<tree.getRowCount();row++)tree.expandRow(row);
        layout(root,1024);Rectangle last=tree.getRowBounds(tree.getRowCount()-1);
        check(last.y+last.height<=570,"all sidebar rows fit vertically without the former permanent group gaps");
        TreePath discovery=SimpleWorkflowTest.toolPath(root,"js");Rectangle hovered=tree.getPathBounds(discovery);
        int x=tree.getWidth()-12,y=hovered.y+hovered.height/2;int before=pixel(tree,x,y);
        tree.dispatchEvent(new MouseEvent(tree,MouseEvent.MOUSE_MOVED,System.currentTimeMillis(),0,x,y,0,false));
        check(pixel(tree,x,y)!=before,"hover paints a visible background beyond the text without a timer");
        tree.dispatchEvent(new MouseEvent(tree,MouseEvent.MOUSE_EXITED,System.currentTimeMillis(),0,x,y,0,false));
        check(pixel(tree,x,y)==before,"leaving the sidebar clears the hover decoration");
        tree.setSelectionPath(discovery);layout(root,1024);
        check(pages.getSelectedComponent()==value(extension,"jsAnalysis")||SimpleWorkflowTest.selectedFeature(pages).equals("js"),"compact sidebar selection opens the existing JS route");
        Rectangle selected=tree.getPathBounds(discovery);
        check(pixel(tree,5,selected.y+6)==WorkbenchUi.accent().getRGB(),"selected tool has a strong full-row accent marker");
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
