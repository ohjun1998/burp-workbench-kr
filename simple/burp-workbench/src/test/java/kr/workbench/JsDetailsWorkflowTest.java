package kr.workbench;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import static kr.workbench.WorkbenchTest.*;
/** Modeless details, masked data and readable production components. */
public final class JsDetailsWorkflowTest {
 static int checks;
 static void verify(boolean value,String message){if(!value)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
 static String displayed(Component root){StringBuilder text=new StringBuilder();for(Component c:PacketUiTest.components(root)){
  if(c instanceof JLabel l)text.append(l.getText());if(c instanceof JTextArea a)text.append(a.getText());if(c instanceof JTable t)for(int r=0;r<t.getRowCount();r++)for(int col=0;col<t.getColumnCount();col++)text.append(t.getValueAt(r,col));
 }return text.toString();}
 public static void main(String[] args)throws Exception{
  AtomicReference<JsAnalysisPanel> ref=new AtomicReference<>();List<JsAnalysisDetails> windows=new ArrayList<>();
  edt(()->{
   var panel=new JsAnalysisPanel(null,()->{});ref.set(panel);
   panel.detailsWindow.opener=(owner,title,content,modal,closed)->{verify(!modal&&title.equals("분석 상세"),"details opens a modeless window");windows.add((JsAnalysisDetails)content);return null;};
   panel.analysisDetails.doClick(0);verify(displayed(windows.get(0)).contains("분석 목록에서 응답을 선택"),"empty details explains the next action without fabricated results");
   panel.load("fetch('/one?q=PRIVATE_QUERY',{method:'POST',headers:{Authorization:'PRIVATE_AUTH'},body:JSON.stringify({name:'PRIVATE_BODY',n:2})});fetch('/two');const apiKey='AIza"+"Z".repeat(35)+"';require('@org/demo');","https://app.test/source.js?token=PRIVATE_SOURCE");
  });var panel=ref.get();JsAnalysisPanelTest.waitDone(panel);
  edt(()->{
   panel.analysisDetails.doClick(0);JsAnalysisDetails detail=windows.get(1);
   var group=(JPanel)UiLayoutTest.named(panel,"wb.js.analysis.group");Component[] actions=group.getComponents();
   verify(actions[0]==panel.analysisList&&actions[1]==panel.analysisDetails,"analysis details sits directly right of analysis list");
   verify(panel.results.getTabCount()==7&&java.util.stream.IntStream.range(0,7).noneMatch(i->panel.results.getTitleAt(i).equals("분석 상세")),"main results no longer contain the old details tab");
   verify(((JLabel)UiLayoutTest.named(detail,"wb.js.details.count.api")).getText().equals("2"),"API summary card matches the selected analysis");
   verify(((JLabel)UiLayoutTest.named(detail,"wb.js.details.count.secret")).getText().equals(Integer.toString(detail.snapshot.secrets().size()))&&detail.snapshot.secrets().size()>0,"token summary counts masked candidates");
   verify(detail.sections.getTabCount()==4&&detail.apis.getRowCount()==2,"details uses grouped result tables rather than a plain report");
   verify(!detail.apis.isCellEditable(0,0)&&detail.apis.getRowSorter()!=null,"read-only result tables support sorting");
   verify(!displayed(detail).contains("PRIVATE_")&&!displayed(detail).contains("AIza"+"Z".repeat(35)),"source query body authorization and key values stay out of all visible details");
   verify(detail.source.equals("https://app.test/source.js"),"details source strips query values");
   JPanel body=WorkbenchUi.settingsBody(detail,()->{});body.setSize(980,640);for(int i=0;i<5;i++)UiLayoutTest.layout(body);
   var viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,detail.apis);
   verify(viewport.getHeight()>=140&&viewport.getWidth()>600,"default details window keeps a readable result table");
   body.setSize(620,400);for(int i=0;i<5;i++)UiLayoutTest.layout(body);
   verify(detail.getWidth()<=620&&detail.getHeight()>=500,"minimum window retains content through vertical scrolling");
   SwingUtilities.updateComponentTreeUI(body);WorkbenchUi.style(body);
   verify(displayed(detail).contains("PRIVATE_AUTH")==false&&detail.apis.getRowCount()==2,"theme changes preserve masked structured results");
   panel.load("fetch('/new');","https://app.test/new.js");
  });JsAnalysisPanelTest.waitDone(panel);
  edt(()->{
   verify(windows.get(1).apis.getRowCount()==2&&windows.get(1).source.endsWith("source.js"),"background selection does not replace the already open snapshot");
   panel.analysisDetails.doClick(0);verify(windows.get(2).apis.getRowCount()==1&&windows.get(2).source.endsWith("new.js"),"pressing details again reflects the newly selected analysis");
   panel.close();int count=windows.size();panel.analysisDetails.doClick(0);verify(windows.size()==count,"unload prevents reopening details windows");
  });
  JsAnalysisPanel totalsPanel=new JsAnalysisPanel(null,()->{});
  String key="AIza"+"Z".repeat(35);
  edt(()->totalsPanel.load("fetch('/one');const key='"+key+"';","https://app.test/first.js"));JsAnalysisPanelTest.waitDone(totalsPanel);
  edt(()->verify(totalsPanel.totals.api.count==1&&totalsPanel.totals.secret.count==1,"aggregate counters include the first analyzed source"));
  edt(()->totalsPanel.load("fetch('/one');fetch('/two');fetch('https://cdn.cloudfront.net/file');const key='"+key+"';","https://app.test/second.js"));JsAnalysisPanelTest.waitDone(totalsPanel);
  edt(()->{
    verify(totalsPanel.totals.api.count==3&&totalsPanel.totals.secret.count==1,"all-source API and key counters deduplicate repeated findings");
    verify(totalsPanel.totals.domain.count==1&&totalsPanel.totals.cloud.count==1,"aggregate counters include cloud URLs and domains");
    totalsPanel.sources.setSelectedItem("https://app.test/first.js");
    verify(totalsPanel.urlTable.getRowCount()==1&&totalsPanel.totals.api.count==3,"changing selected source cannot replace all-analysis totals with local counts");
    totalsPanel.passive.doClick(0);verify(totalsPanel.totals.api.count==3,"analysis OFF preserves already retained totals");
    totalsPanel.load("fetch('/updated');","https://app.test/first.js");
  });JsAnalysisPanelTest.waitDone(totalsPanel);
  edt(()->{
    verify(totalsPanel.totals.api.count==4&&totalsPanel.totals.secret.count==1,"reanalyzing a source replaces its contribution without double counting");
    totalsPanel.clear();verify(totalsPanel.totals.api.count==0&&totalsPanel.totals.secret.count==0&&totalsPanel.totals.domain.count==0&&totalsPanel.totals.cloud.count==0,"analysis reset clears all four aggregate lights");totalsPanel.close();
  });
  var single=JsAnalysis.analyze("fetch('/a');","https://app.test/");var other=JsAnalysis.analyze("fetch('/b');fetch('/c');","https://app.test/");
  long budget=JsSourceCache.weight("b",new JsSourceCache.Entry("s","h",other,null));var cache=new JsSourceCache(budget);
  cache.analysis("a","s","h",single,null);cache.analysis("b","s","h",other,null);
  edt(()->{JsAnalysisTotals totals=new JsAnalysisTotals();totals.update(cache.snapshot().values());verify(totals.api.count==2&&cache.result("a")==null,"cache eviction removes expired analysis from aggregate counts");});
  System.out.println("JsDetailsWorkflowTest: "+checks+" checks passed");
 }
}
