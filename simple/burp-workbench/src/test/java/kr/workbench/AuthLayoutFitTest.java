package kr.workbench;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** Viewport/resize and real component positioning, not a native Burp GUI test. */
public final class AuthLayoutFitTest extends WorkbenchTest {
  public static void main(String[] args)throws Exception {
    var api=proxy(burp.api.montoya.MontoyaApi.class,(p,m,a)->def(m));
    final AuthComparePanel[] box={null};edt(()->box[0]=new AuthComparePanel(api,List::of));var panel=box[0];
    edt(()->{
      check(panel.history.getColumnName(1).equals("메서드")&&panel.history.getColumnName(2).equals("URL"),"history shows method before URL without changing account model indices");
      var actions=(JPanel)UiLayoutTest.named(panel,"wb.auth.actions");
      check(java.util.Arrays.stream(actions.getComponents()).map(Component::getName).toList().equals(List.of("wb.auth.detect", "wb.simple.auth.review", "wb.simple.auth.stop", "wb.simple.auth.settings")),"Simple toolbar contains only named detection, explicit comparison, stop and account/settings controls");
      check(!SwingUtilities.isDescendingFrom(panel.packetDetails,panel),"main keeps packet inspector in separate detail window");
      for(int width:new int[]{800,1024,1280,1600}){
        panel.setSize(width,800);for(int i=0;i<5;i++)UiLayoutTest.layout(panel);
        for(JTable table:new JTable[]{panel.candidates,panel.history}){
          var viewport=(JViewport)table.getParent();int available=viewport.getWidth();
          int minimum=0;for(int c=0;c<table.getColumnCount();c++)minimum+=table.getColumnModel().getColumn(c).getMinWidth();
          check(available>=minimum?Math.abs(table.getWidth()-available)<=1:table.getWidth()>=minimum,"table fills available width or scrolls at readable minimum: "+width+" "+table.getName());
          int total=0;for(int c=0;c<table.getColumnCount();c++){var column=table.getColumnModel().getColumn(c);total+=column.getWidth();check(column.getWidth()>=column.getMinWidth(),"column remains above readable minimum");}
          check(Math.abs(total-table.getWidth())<=1,"no unused header strip at "+width);
        }
        for(Component control:actions.getComponents())check(control.getY()+control.getHeight()<=actions.getHeight(),"toolbar wraps within available height");
      }
      var details=panel.candidateDetails;details.setSize(820,600);details.load(new AuthComparePanel.Candidate(1,"demo","https://example.test:443",java.util.Map.of(),1,""));
      for(int i=0;i<4;i++)UiLayoutTest.layout(details);
      check(details.name.getY()==details.role.getY()&&details.name.getX()<details.role.getX(),"account form uses two aligned columns");
      check(details.login.getY()==details.email.getY(),"login and email share second row");
      check(details.table.getParent().getHeight()>=100,"credential table retains usable height in compact dialog");panel.close();
    });edt(()->{});System.out.println("AuthLayoutFitTest: "+checks+" checks passed");
  }
}
