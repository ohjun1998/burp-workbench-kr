package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.*;
import burp.api.montoya.scope.Scope;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/** Real panel/disk lifecycle with an isolated project store; no native Burp or network. */
public final class JsArchiveWorkflowTest extends WorkbenchTest {
  public static void main(String[] args)throws Exception{
    Path home=Files.createTempDirectory("wb-js-project-");String previousHome=System.getProperty("user.home");
    System.setProperty("user.home",home.toString());Map<String,String> project=new HashMap<>();
    var data=proxy(PersistedObject.class,(p,m,a)->switch(m.getName()){case "getString"->project.get(a[0]);case "setString"->{project.put(a[0].toString(),a[1].toString());yield null;}default->def(m);});
    var persistence=proxy(Persistence.class,(p,m,a)->m.getName().equals("extensionData")?data:def(m));
    var api=proxy(MontoyaApi.class,(p,m,a)->switch(m.getName()){case "persistence"->persistence;case "scope"->proxy(Scope.class,(q,n,b)->n.getName().equals("isInScope")?true:def(n));default->def(m);});
    AtomicReference<JsAnalysisPanel> holder=new AtomicReference<>();String source="https://cdn.test/bundle.js",page="https://app.test/account/";
    try{
      SwingUtilities.invokeAndWait(()->holder.set(new JsAnalysisPanel(api,()->{})));var panel=holder.get();
      check(panel.archive!=null,"real persistence capability enables a project-isolated analysis archive");
      var request=request(source,"GET",true,List.of(header("Referer",page)));
      SwingUtilities.invokeAndWait(()->panel.offer(request,JsResponseWorkflowTest.response("application/javascript","fetch('/first');")));JsAnalysisPanelTest.waitDone(panel);
      check(panel.maskedReport().contains("https://app.test/first")&&panel.archive.snapshot().size()==1,"captured Referer context reaches both panel results and disk archive");
      SwingUtilities.invokeAndWait(()->panel.offer(request,JsResponseWorkflowTest.response("application/javascript","fetch('/second');")));JsAnalysisPanelTest.waitDone(panel);
      check(panel.sources.getItemCount()==2&&panel.historyModel.getRowCount()==2,"same script URL retains both content versions in the existing analysis list");
      var cache=(JsSourceCache)get(panel,"cache");SwingUtilities.invokeAndWait(cache::clear);
      SwingUtilities.invokeAndWait(()->panel.sources.setSelectedItem(source));JsAnalysisPanelTest.waitDone(panel);
      check(panel.maskedReport().contains("/first")&&cache.result(source)!=null,"an evicted source restores asynchronously through the existing analysis selector");
      String large="/*"+"x".repeat(2200*1024)+"*/;fetch('/large-tail');";
      SwingUtilities.invokeAndWait(()->panel.offer(request("https://cdn.test/large.js","GET",true,List.of(header("Referer",page))),JsResponseWorkflowTest.response("application/javascript",large)));JsAnalysisPanelTest.waitDone(panel);
      check(panel.archive.snapshot().values().stream().anyMatch(s->s.key().contains("large.js")&&s.apis()==1),"response above the former 2 MiB exclusion is analyzed and archived");
      Path archivePath=panel.archive.directory;SwingUtilities.invokeAndWait(panel::close);
      check(Files.list(archivePath).anyMatch(p->p.toString().endsWith(".data")),"unloading the extension preserves project analysis history");
      SwingUtilities.invokeAndWait(()->holder.set(new JsAnalysisPanel(api,()->{})));var reopened=holder.get();
      SwingUtilities.invokeAndWait(reopened::startHistory);JsAnalysisPanelTest.waitDone(reopened);
      check(reopened.sources.getItemCount()==3,"a new panel restores all project analyses without Proxy history availability");
      SwingUtilities.invokeAndWait(reopened::clear);JsAnalysisPanelTest.waitDone(reopened);
      check(reopened.archive.snapshot().isEmpty(),"explicit analysis initialization clears the project archive rather than only its cache");
      SwingUtilities.invokeAndWait(reopened::close);
    }finally{
      System.setProperty("user.home",previousHome);try(var paths=Files.walk(home)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    System.out.println("JsArchiveWorkflowTest: "+checks+" checks passed");
  }
}
