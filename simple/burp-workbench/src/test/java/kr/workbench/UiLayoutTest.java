package kr.workbench;
import burp.*;
import burp.api.montoya.MontoyaApi;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/** Layout/selection regressions, not a mock claim of native Burp execution. */
public final class UiLayoutTest {
 static int checks;
 static void check(boolean b,String name){if(!b)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
 static Component named(Component c,String name){if(name.equals(c.getName()))return c;if(c instanceof Container co)for(Component x:co.getComponents()){Component found=named(x,name);if(found!=null)return found;}return null;}
 static int requestResponseTabs(Component c){int n=0;if(c instanceof JTabbedPane p)for(int i=0;i<p.getTabCount();i++)if(Set.of("요청","응답","원본 요청","원본 응답","비교 요청","비교 응답").contains(p.getTitleAt(i)))n++;if(c instanceof Container co)for(Component x:co.getComponents())n+=requestResponseTabs(x);return n;}
 static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container co)layout(co);}
 public static void main(String[] args)throws Exception{
  BurpExtender entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
  SwingUtilities.invokeAndWait(()->entry.initialize((MontoyaApi)PayloadIntegrationTest.host(MontoyaApi.class)));SwingUtilities.invokeAndWait(()->{});
  WorkbenchExtension w=(WorkbenchExtension)WorkbenchTest.get(entry,"workbench");JPanel root=(JPanel)WorkbenchTest.get(w,"root");JTabbedPane tabs=(JTabbedPane)WorkbenchTest.get(w,"tabs");JTable table=(JTable)WorkbenchTest.get(w,"logTable");
  SwingUtilities.invokeAndWait(()->((PassiveScanWorkspace)unchecked(w,"passiveScan")).open("https://ui.test/",false));
  check(named(root,"wb.traffic.clear")==null&&named(root,"wb.traffic.export")==null&&named(root,"wb.traffic.search.clear")==null,"removed traffic controls are absent");
  SwingUtilities.invokeAndWait(()->{JButton action=new JButton("실행");WorkbenchUi.primary(action);WorkbenchUi.style(action);action.setEnabled(false);check(action.getBackground().equals(WorkbenchUi.surface()),"disabled primary action has neutral background");action.setEnabled(true);check(action.getBackground().equals(kr.packetui.VisualTheme.primary())&&action.getForeground().equals(Color.WHITE),"ready primary action restores the PDF primary fill with white caption");});
  check(table.getColumnCount()==7,"traffic shows the approved account/time/note columns and retains complete metadata model");
  check(List.of("NO","메서드","URL","상태").equals(java.util.stream.IntStream.range(0,4).mapToObj(table::getColumnName).toList()),"NO/method/URL/status order");
  check(((JSplitPane)named(root,"wb.traffic.messages")).getOrientation()==JSplitPane.HORIZONTAL_SPLIT,"traffic requests and responses side by side below list");
  var environmentWorkspace=(JSplitPane)named(root,"wb.environment.workspace");
  var environmentMessages=(JTabbedPane)named(root,"wb.environment.messages");
  check(environmentWorkspace.getOrientation()==JSplitPane.HORIZONTAL_SPLIT
      &&Boolean.TRUE.equals(environmentWorkspace.getClientProperty("wb.workflow.fixedLayout"))
      &&named(root,"wb.environment.observations.toggle") instanceof JToggleButton
      &&java.util.stream.IntStream.range(0,environmentMessages.getTabCount()).mapToObj(environmentMessages::getTitleAt).toList().equals(List.of("응답","요청","판정 근거")),
      "environment keeps candidates beside a fixed inspector with explicit response, request and decision views");
  SwingUtilities.invokeAndWait(()->{
   EnvironmentPanel environment=(EnvironmentPanel)unchecked(w,"environments");
   String requestText=environment.requestView.getText(),responseText=environment.responseView.getText();
   check(SwingUtilities.isDescendingFrom(environment.responseView,environmentMessages.getComponentAt(0))
       &&SwingUtilities.isDescendingFrom(environment.requestView,environmentMessages.getComponentAt(1))
       &&SwingUtilities.isDescendingFrom(environment.decisionView,environmentMessages.getComponentAt(2)),
       "each environment inspector tab retains its original packet or decision model");
   int before=PayloadIntegrationTest.sent.get();
   for(int index=0;index<environmentMessages.getTabCount();index++)environmentMessages.setSelectedIndex(index);
   environmentMessages.setSelectedIndex(0);
   check(environment.requestView.getText().equals(requestText)&&environment.responseView.getText().equals(responseText)
       &&PayloadIntegrationTest.sent.get()==before,"switching environment inspector views preserves originals and sends no HTTP");
  });
  check(((JSplitPane)named(root,"wb.auth.tables")).getOrientation()==JSplitPane.VERTICAL_SPLIT&&named(root,"wb.auth.workspace")==null,"auth main stacks credentials and history without embedded packets");
  check(named(root,"wb.js.messages")==null&&((JSplitPane)named(root,"wb.passive.messages")).getOrientation()==JSplitPane.HORIZONTAL_SPLIT,"JS has no embedded packet area and passive keeps the shared packet layout");
  check(((JSplitPane)named(root,"wb.payload.workspace")).getOrientation()==JSplitPane.VERTICAL_SPLIT,"payload editors and results above packets in reference layout");
  check(requestResponseTabs(root)==6 && named(root,"wb.bounty.evidence.packet.tabs") instanceof JTabbedPane && named(root,"wb.bounty.flow.packet.tabs") instanceof JTabbedPane,"environment, stored evidence and replay expose both original request and response in explicit tabs; count="+requestResponseTabs(root));
  check(table.getRowHeight()==32&&table.getRowHeight()>=table.getFontMetrics(table.getFont()).getHeight()+2,"compact rows fit Gothic text with vertical padding");
  check(table.getDefaultRenderer(Object.class) instanceof WorkbenchUi.Cells,"semantic method/status renderer installed");
  for(int size:new int[]{1000,1280,1600})SwingUtilities.invokeAndWait(()->{
   root.setSize(size,900);for(int i=0;i<tabs.getTabCount();i++){tabs.setSelectedIndex(i);layout(root);check(tabs.getComponentAt(i).getWidth()>0,"tab "+i+" laid out at "+size);}
   tabs.setSelectedIndex(0);layout(root);JSplitPane outer=(JSplitPane)named(root,"wb.traffic.workspace"),messages=(JSplitPane)named(root,"wb.traffic.messages");outer.setDividerLocation(.30);messages.setDividerLocation(.5);layout(root);
   check(table.getColumnModel().getColumn(0).getWidth()>=56&&table.getColumnModel().getColumn(1).getWidth()>=65&&table.getColumnModel().getColumn(3).getWidth()>=60,"traffic identifiers retain readable column widths at "+size);
   check(messages.getLeftComponent().getWidth()>100&&messages.getRightComponent().getWidth()>100,"both message panes usable at "+size);
   check(outer.getRightComponent()==messages.getParent(),"no right-side vertical action sidebar at "+size);
  });
  for(int i=0;i<12;i++)WorkbenchTest.seedTraffic(w,WorkbenchTest.request("https://ui.test/route/"+i,i%2==0?"GET":"POST",true,List.of()),WorkbenchTest.response(i%3==0?403:200,"body"));
  SwingUtilities.invokeAndWait(()->{
   DefaultTableModel m=(DefaultTableModel)table.getModel();check(((Number)m.getValueAt(11,0)).longValue()==12,"stable monotonically increasing traffic NO");
   ((TableRowSorter<?>)table.getRowSorter()).setSortKeys(List.of(new RowSorter.SortKey(0,SortOrder.DESCENDING)));table.setRowSelectionInterval(0,0);
   try{Object current=WorkbenchTest.get(w,"current");var f=current.getClass().getDeclaredMethod("request");f.setAccessible(true);check(f.invoke(current).toString().contains("/route/11"),"sorted NO selection resolves correct captured request");}catch(Exception e){throw new RuntimeException(e);}
   ((JTextField)unchecked(w,"filter")).setText("/route/2");

  });
  TrafficSearchUpgradeTest.idle(w.trafficBrowser);
  SwingUtilities.invokeAndWait(()->{check(table.getRowCount()==1,"typing schedules background traffic filtering");((JTextField)unchecked(w,"filter")).setText("");});
  TrafficSearchUpgradeTest.idle(w.trafficBrowser);
  SwingUtilities.invokeAndWait(()->{check(table.getRowCount()==12,"empty input restores all rows without reset button");table.clearSelection();check(unchecked(w,"current")==null,"cleared selection resets stale request");});
  SwingUtilities.invokeAndWait(()->{check(table.getRowCount()==12&&named(root,"wb.traffic.clear")==null&&named(root,"wb.traffic.export")==null,"traffic stays retained without removed file action controls");});
  Color old=UIManager.getColor("Panel.background");UIManager.put("Panel.background",new Color(30,30,30));check(WorkbenchUi.dark()&&WorkbenchUi.ink().getRed()>200,"dark palette has readable foreground");UIManager.put("Panel.background",old);
  for(var h:PayloadIntegrationTest.modernUnloads)h.extensionUnloaded();for(var h:PayloadIntegrationTest.legacyUnloads)h.extensionUnloaded();SwingUtilities.invokeAndWait(()->{});System.out.println("UiLayoutTest: "+checks+" checks passed");
 }
 static Object unchecked(Object o,String n){try{return WorkbenchTest.get(o,n);}catch(Exception e){throw new RuntimeException(e);}}
}
