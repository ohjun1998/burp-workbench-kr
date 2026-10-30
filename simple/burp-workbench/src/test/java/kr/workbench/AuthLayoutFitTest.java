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
      check(panel.history.getColumnName(1).equals("URL")&&panel.history.convertColumnIndexToView(6)==-1&&panel.historyModel.getColumnCount()==9,"history combines method and URL while retaining original account model indices");
      var actions=(JPanel)UiLayoutTest.named(panel,"wb.auth.actions");
      var accountControls=SimpleWorkflowTest.visibleComponents(actions).stream()
          .filter(c->c instanceof AbstractButton).map(Component::getName).toList();
      var execution=UiLayoutTest.named(panel,"wb.actions.auth.execution");
      check(accountControls.size()==4&&accountControls.containsAll(List.of("wb.auth.detect", "wb.simple.auth.settings", "wb.auth.history.more", "wb.auth.candidates.toggle"))
          &&SwingUtilities.isDescendingFrom(UiLayoutTest.named(panel,"wb.simple.auth.review"),(Container)execution)
          &&SwingUtilities.isDescendingFrom(UiLayoutTest.named(panel,"wb.simple.auth.stop"),(Container)execution),
          "detection/settings stay above cards in adaptive groups and comparison/stop remain together beside result filters");
      check(!SwingUtilities.isDescendingFrom(panel.packetDetails,panel) && SwingUtilities.isDescendingFrom(panel.inlinePreview,panel),"inline comparison remains mounted while enlarged packet window stays independent");
      for(int width:new int[]{800,1024,1280,1600}){
        panel.setSize(width,800);for(int i=0;i<5;i++)UiLayoutTest.layout(panel);
        for(JTable table:new JTable[]{panel.history}){
          var viewport=(JViewport)table.getParent();int available=viewport.getWidth();
          int minimum=0;for(int c=0;c<table.getColumnCount();c++)minimum+=table.getColumnModel().getColumn(c).getMinWidth();
          check(available>=minimum?Math.abs(table.getWidth()-available)<=1:table.getWidth()>=minimum,"table fills available width or scrolls at readable minimum: "+width+" "+table.getName());
          int total=0;for(int c=0;c<table.getColumnCount();c++){var column=table.getColumnModel().getColumn(c);total+=column.getWidth();check(column.getWidth()>=column.getMinWidth(),"column remains above readable minimum");}
          check(Math.abs(total-table.getWidth())<=1,"no unused header strip at "+width);
        }
        for(String name:accountControls){
          Component control=UiLayoutTest.named(panel,name);
          Rectangle bounds=SwingUtilities.convertRectangle(control.getParent(),control.getBounds(),actions);
          check(bounds.width>0&&bounds.height>0&&new Rectangle(0,0,actions.getWidth(),actions.getHeight()).contains(bounds),
              "account action stays fully visible inside adaptive toolbar: "+name+" at "+width);
        }
        Component origin=(Component)SimpleWorkflowTest.unchecked(panel,"accountOrigin");
        Rectangle originBounds=SwingUtilities.convertRectangle(origin.getParent(),origin.getBounds(),actions);
        check(originBounds.width>0&&originBounds.height>0&&new Rectangle(0,0,actions.getWidth(),actions.getHeight()).contains(originBounds),
            "account origin remains visible inside adaptive context at "+width);
      }
      ((JToggleButton)UiLayoutTest.named(panel,"wb.auth.candidates.toggle")).doClick();UiLayoutTest.layout(panel);check(panel.candidates.getParent().getWidth()>0,"candidate controls remain accessible through disclosure");
      var details=panel.candidateDetails;details.setSize(820,600);details.load(new AuthComparePanel.Candidate(1,"demo","https://example.test:443",java.util.Map.of(),1,""));
      for(int i=0;i<4;i++)UiLayoutTest.layout(details);
      check(details.name.getY()==details.role.getY()&&details.name.getX()<details.role.getX(),"account form uses two aligned columns");
      check(details.login.getY()==details.email.getY(),"login and email share second row");
      check(details.table.getParent().getHeight()>=100,"credential table retains usable height in compact dialog");panel.close();
    });edt(()->{});System.out.println("AuthLayoutFitTest: "+checks+" checks passed");
  }
}
