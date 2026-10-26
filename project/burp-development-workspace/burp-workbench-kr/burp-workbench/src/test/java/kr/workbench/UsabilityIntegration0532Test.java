package kr.workbench;

import burp.*;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.internal.*;
import burp.api.montoya.persistence.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Production workspace routing and exact search/navigation wiring, using isolated API adapters. */
public final class UsabilityIntegration0532Test extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));
    AtomicReference<Map<String,String>> project=new AtomicReference<>(new HashMap<>());
    project.get().put(WorkspaceResume.PROJECT_KEY,WorkspaceResumeTest.A);
    PersistedObject data=proxy(PersistedObject.class,(p,m,a)->switch(m.getName()){
      case "getString"->project.get().get(a[0]);
      case "setString"->{project.get().put(a[0].toString(),a[1].toString());yield null;}
      default->def(m);
    });
    Object fallbackPersistence=PayloadIntegrationTest.host(Persistence.class);
    Persistence persistence=proxy(Persistence.class,(p,m,a)->m.getName().equals("extensionData")?data:m.invoke(fallbackPersistence,a));
    Object fallback=PayloadIntegrationTest.host(MontoyaApi.class);
    MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("persistence")?persistence:m.invoke(fallback,a));
    BurpExtender entry=new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(()->entry.initialize(api));edt(()->{});
    WorkbenchExtension extension=(WorkbenchExtension)get(entry,"workbench");
    TrafficBrowser browser=(TrafficBrowser)get(extension,"trafficBrowser");
    FolderNavigation navigation=(FolderNavigation)get(extension,"navigation");
    JTable table=(JTable)get(extension,"logTable");BackgroundJobs registry=(BackgroundJobs)get(extension,"backgroundJobs");
    AtomicReference<Component> dialog=new AtomicReference<>();int sends=PayloadIntegrationTest.sent.get();
    try {
      edt(()->{
        extension.dialogOpener=(owner,title,content,modal,closed)->{check(!modal,"workflow helpers remain modeless");dialog.set(content);return null;};
        check(browser.backgroundJobs==registry,"traffic search uses the workspace registry");
        check(((AuthComparePanel)unchecked(extension,"authCompare")).backgroundJobs==registry,"account batches use the same workspace registry");
        check(((BountySupportPanel)unchecked(extension,"bountySupport")).backgroundJobs==registry,"evidence save uses the same workspace registry");
        check(((JsAnalysisPanel)unchecked(extension,"jsAnalysis")).backgroundJobs==registry,"JS work uses the same workspace registry");
        invoke(extension,"openGettingStarted");check(dialog.get() instanceof GettingStartedPanel,"production helper opens actionable checklist");
        invoke(extension,"openJobMonitor");check(dialog.get() instanceof JobMonitorPanel,"production helper opens actual job registry view");
        invokeRoute(extension,"jwt");check(((JTabbedPane)unchecked(extension,"dataTabs")).getSelectedIndex()==1,"quick JWT goal opens actual JWT subpage");
        invokeRoute(extension,"evidence");check(((BountySupportPanel)unchecked(extension,"bountySupport")).pages.getSelectedIndex()==3,"evidence goal opens actual export workspace");
      });
      var getRequest=request("https://usability.test/read","GET",true,List.of());
      var postRequest=request("https://usability.test/write","POST",true,List.of());
      seedTraffic(extension,getRequest,response(200,"other"));
      seedTraffic(extension,postRequest,response(200,"x".repeat(72_000)+"needle"));
      TrafficQuery post=new TrafficQuery("POST","전체",2,false,false,false,true,false,true,"",0);
      TrafficIncrementalSearchTest.query(browser,post,"needle");
      edt(()->{extension.openFeature("traffic");table.setRowSelectionInterval(0,0);navigation.resetHistory();});
      PacketInspector response=(PacketInspector)get(extension,"responsePacket");
      TrafficUsability0532Test.await(()->response.trafficContextNotice.getText().contains("일치"),"production selected packet did not reveal list search context");
      edt(()->check(response.text.getText().contains("needle"),"actual traffic selection reveals bounded match beyond prefix"));
      edt(()->navigation.navigate("auth"));edt(()->{});
      TrafficIncrementalSearchTest.query(browser,TrafficQuery.defaults(),"");
      edt(()->navigation.goBack());
      TrafficUsability0532Test.await(()->browser.positionReady(browser.capturePosition())&&!timerRunning(extension),"navigation search/original restore did not settle");
      edt(()->{
        check(browser.capturePosition().query().method().equals("POST")&&browser.capturePosition().text().equals("needle"),"back restores prior in-memory query and free text");
        check(table.getSelectedRow()>=0&&table.convertRowIndexToModel(table.getSelectedRow())==1,"back resolves exact saved original after asynchronous query publication");
        check(extension.gettingStartedReadiness().traffic()==2,"checklist observes actual capture store instead of displayed filtered count");
        var pending=BackgroundJobs.begin(registry,"프로젝트 작업","traffic",null);
        project.set(new HashMap<>(Map.of(WorkspaceResume.PROJECT_KEY,WorkspaceResumeTest.B)));
        invoke(extension,"rememberWorkspace");
        check(registry.snapshot().isEmpty(),"project transition removes previous job metadata");
        pending.complete("late old project");check(registry.snapshot().isEmpty(),"late old-project completion cannot reappear");
        check(!extension.gettingStartedReadiness().savedEvidence(),"old evidence save completion is not current-project readiness");
      });
      check(PayloadIntegrationTest.sent.get()==sends,"all new helper navigation and search context actions send zero HTTP");
    } finally {
      for(var unload:PayloadIntegrationTest.modernUnloads)unload.extensionUnloaded();
      for(var unload:PayloadIntegrationTest.legacyUnloads)unload.extensionUnloaded();edt(()->{});
    }
    check(registry.snapshot().isEmpty(),"unload clears job history and callbacks");
    System.out.println("UsabilityIntegration0532Test: "+checks+" checks passed");
  }
  private static boolean timerRunning(WorkbenchExtension extension){var timer=(javax.swing.Timer)unchecked(extension,"navigationSelectionTimer");return timer!=null&&timer.isRunning();}
  private static Object unchecked(Object owner,String field){try{return get(owner,field);}catch(Exception error){throw new AssertionError(error);}}
  private static void invoke(Object owner,String method){try{var m=owner.getClass().getDeclaredMethod(method);m.setAccessible(true);m.invoke(owner);}catch(Exception error){throw new AssertionError(error);}}
  private static void invokeRoute(WorkbenchExtension extension,String route){try{call(extension,"openUsabilityRoute",String.class,route);}catch(Exception error){throw new AssertionError(error);}}
}
