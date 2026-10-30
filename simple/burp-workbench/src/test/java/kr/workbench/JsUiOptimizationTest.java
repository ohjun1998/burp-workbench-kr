package kr.workbench;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicReference;

/** Production Swing layout and selection actions; does not launch native Burp. */
public final class JsUiOptimizationTest {
 static int checks;
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
 static void edt(Runnable job)throws Exception{SwingUtilities.invokeAndWait(job);}
 static void settle(JsAnalysisPanel panel,int width,int height)throws Exception{edt(()->panel.setSize(width,height));for(int i=0;i<6;i++)edt(()->UiLayoutTest.layout(panel));}
 static Rectangle visible(Component component,Container root){
  Rectangle bounds=SwingUtilities.convertRectangle(component.getParent(),component.getBounds(),root);
  for(Container parent=component.getParent();parent!=null&&parent!=root;parent=parent.getParent())bounds=bounds.intersection(SwingUtilities.convertRectangle(parent,new Rectangle(0,0,parent.getWidth(),parent.getHeight()),root));
  return bounds.intersection(new Rectangle(0,0,root.getWidth(),root.getHeight()));
 }
 public static void main(String[] args)throws Exception{
  AtomicReference<JsAnalysisPanel> ref=new AtomicReference<>();edt(()->ref.set(new JsAnalysisPanel(null,()->{})));JsAnalysisPanel panel=ref.get();
  try{
   edt(()->check(!panel.toRepeater.isEnabled()&&!panel.resolve.isEnabled()&&!panel.locate.isEnabled(),"selection actions start unavailable without a result"));
   for(boolean alternate:new boolean[]{false,true}){
    edt(()->WorkbenchUi.packetLayout(panel,alternate));
    String mode=alternate?"list-above-packets":"list-left-of-packets";
    for(int[] size:new int[][]{{1004,624},{1260,704},{1420,824}}){
     settle(panel,size[0],size[1]);edt(()->{
      Rectangle source=visible(panel.input,panel);
      check(source.width>=300&&source.height>=80,"editable JS source remains visible in "+mode+" at "+size[0]+"x"+size[1]+" ("+source.width+"x"+source.height+")");
      check(visible(panel.sources,panel).height==panel.sources.getHeight()&&visible(panel.navigator.previous,panel).height==panel.navigator.previous.getHeight(),"analysis list and navigation controls stay visible in "+mode+" at "+size[0]);
      Rectangle findings=visible(panel.results,panel);
      check(findings.width>=450&&findings.height>=140&&panel.resultView.detail.getParent().getHeight()>=100&&!SwingUtilities.isDescendingFrom(panel.requestPacket,panel),"findings and inline details share the released packet area in "+mode+" at "+size[0]);
     });
    }
   }
   String source="https://allowed.test/assets/"+"long-module-name-".repeat(30)+"application.js";
   edt(()->panel.load("fetch('/users'); const apiKey='ghp_"+"A1b2C3d4".repeat(5)+"';",source));JsAnalysisPanelTest.waitDone(panel);
   edt(()->{
    check(((JLabel)UiLayoutTest.named(panel,"wb.js.source.summary")).getToolTipText().equals(source),"long source URL is available in the source tooltip");
    check(SwingUtilities.isDescendingFrom(panel.sources,panel)&&panel.sources.getPreferredSize().width<=320,"inline source selector has a bounded width while full source remains in tooltip");
    check(!panel.toRepeater.isEnabled()&&!panel.resolve.isEnabled(),"analysis does not enable endpoint actions before row selection");
    panel.urlTable.setRowSelectionInterval(0,0);
    check(panel.toRepeater.isEnabled()&&panel.resolve.isEnabled()&&panel.locate.isEnabled(),"selecting an endpoint enables its actions");
    panel.results.setSelectedIndex(1);panel.secretTable.setRowSelectionInterval(0,0);
    check(!panel.toRepeater.isEnabled()&&!panel.resolve.isEnabled()&&panel.locate.isEnabled(),"secret tab exposes source navigation without stale endpoint actions");
    panel.results.setSelectedIndex(2);
    check(!panel.toRepeater.isEnabled()&&!panel.resolve.isEnabled()&&!panel.locate.isEnabled(),"unrelated result tab clears selection action availability");
    panel.results.setSelectedIndex(0);panel.urlTable.clearSelection();
    check(!panel.toRepeater.isEnabled()&&!panel.resolve.isEnabled()&&!panel.locate.isEnabled(),"clearing selected endpoint disables its actions");
    panel.clear();check(!panel.toRepeater.isEnabled()&&!panel.resolve.isEnabled()&&!panel.locate.isEnabled(),"reset removes all result action availability");
   });
  }finally{edt(panel::close);}
  System.out.println("JsUiOptimizationTest: "+checks+" checks passed");
 }
}
