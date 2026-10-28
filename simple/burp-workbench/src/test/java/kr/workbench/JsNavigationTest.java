package kr.workbench;
import javax.swing.*;
import java.util.*;
public final class JsNavigationTest extends WorkbenchTest {
 public static void main(String[] args)throws Exception {
  JsAnalysisPanel panel=new JsAnalysisPanel(null,()->{});String code="fetch('/api/first');\nconst apiKey='AIza"+"Z".repeat(35)+"';\nfetch('/api/second');require('@site/widget');";
  edt(()->panel.load(code,"https://test/app.js"));JsAnalysisPanelTest.waitDone(panel);
  edt(()->{
   check(panel.input.getHighlighter().getHighlights().length>=4,"all native source findings highlighted");
   panel.navigator.next.doClick();check(panel.findingCount.getText().startsWith("1 /"),"next arrow selects first finding");check(panel.urlTable.getSelectedRow()>=0&&panel.results.getSelectedIndex()==0,"arrow selects endpoint table row");int first=panel.input.getCaretPosition();panel.navigator.next.doClick();check(panel.input.getCaretPosition()>first,"next finding moves in source order");panel.navigator.previous.doClick();check(panel.input.getCaretPosition()==first,"previous arrow returns to earlier finding");
   panel.navigator.filter.setSelectedItem("시크릿");check(panel.navigator.visible.size()==1,"secret-only navigation filter");panel.navigator.next.doClick();check(panel.results.getSelectedIndex()==1&&panel.secretTable.getSelectedRow()==0,"secret arrow selects masked secret row");check(panel.input.getHighlighter().getHighlights()[0].getPainter() instanceof javax.swing.text.DefaultHighlighter.DefaultHighlightPainter,"source highlight painter");
   panel.input.append("\n// edit");check(!panel.navigator.active&&panel.input.getHighlighter().getHighlights().length==0,"editing invalidates stale positions");panel.navigator.next.doClick();check(panel.input.getText().equals(code)&&panel.navigator.active,"explicit arrow restores captured source");
   panel.navigator.filter.setSelectedItem("전체 감지");((javax.swing.table.TableRowSorter<?>)panel.urlTable.getRowSorter()).setSortKeys(List.of(new RowSorter.SortKey(0,SortOrder.DESCENDING)));panel.urlTable.setRowSelectionInterval(0,0);check(panel.input.getCaretPosition()==code.indexOf("'/api/second'"),"sorted result selection resolves source offset");
  });
  edt(panel::clear);var q=request("https://test/auto.js","GET",true,List.of());edt(()->panel.offer(q,response(200,"fetch('/automatic');")));JsAnalysisPanelTest.waitDone(panel);
  edt(()->{check(panel.input.getText().isEmpty(),"passive capture does not expose raw source automatically");panel.navigator.next.doClick();check(panel.input.getText().contains("/automatic")&&panel.navigator.active,"arrow opens passive captured source explicitly");panel.clear();check(!panel.navigator.next.isEnabled()&&panel.findingCount.getText().equals("0 / 0"),"clear resets navigation");panel.close();});
  System.out.println("JsNavigationTest: "+checks+" checks passed");
 }
}
