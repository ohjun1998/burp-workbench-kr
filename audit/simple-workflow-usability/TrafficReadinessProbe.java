package kr.workbench;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Controlled readiness observation using synthetic original rows, no target HTTP or OS clicks. */
public final class TrafficReadinessProbe extends WorkbenchTest {
  static final String OLD="https://readiness.test/fixed/501",NEXT="https://readiness.test/vuln/501";
  static void begin(TrafficBrowser browser){try{Method method=TrafficBrowser.class.getDeclaredMethod("begin");method.setAccessible(true);method.invoke(browser);}catch(Exception error){throw new IllegalStateException("Search preparation failed",error);}}
  static void await(CountDownLatch latch,String label)throws Exception{if(!latch.await(8,TimeUnit.SECONDS))throw new AssertionError("Timeout: "+label);}
  static void waitEdt(BooleanSupplier condition,String label)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<until){var ready=new AtomicBoolean();edt(()->ready.set(condition.getAsBoolean()));if(ready.get())return;Thread.sleep(10);}throw new AssertionError("Timeout: "+label);}
  public static void main(String[] args)throws Exception{
    if(args.length<1||args.length>2)throw new IllegalArgumentException("Usage: TrafficReadinessProbe output-directory [phase]");
    Path output=Path.of(args[0]);Files.createDirectories(output);String phase=args.length==2?args[1]:"after";
    var result=new LinkedHashMap<String,Object>();result.putAll(Map.of("phase",phase,"productionVersion",BuildInfo.VERSION,"nativeBurp",false,"osClicks",false,"realTargetHTTP",false,
        "fixture","Two synthetic packet originals in the production disk/table/browser; identity-supplier and worker latches control the exact search phases"));
    ScopeInspectionFixture.installFactory();int sends=PayloadIntegrationTest.sent.get();
    var holder=new AtomicReference<TrafficBrowser>();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var prepared=new CountDownLatch(1);var workerEntered=new CountDownLatch(1);var workerRelease=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
    try(var disk=new DiskTrafficStore()){
      var old=disk.append("readiness synthetic","old",ScopeInspectionFixture.wireRequest(OLD,"GET",List.of(),new byte[0]),ScopeInspectionFixture.wireResponse(200,List.of(),"old".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      var next=disk.append("readiness synthetic","next",ScopeInspectionFixture.wireRequest(NEXT,"GET",List.of(),new byte[0]),ScopeInspectionFixture.wireResponse(200,List.of(),"next".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      edt(()->{var model=new CaptureTableModel(disk::ref);model.publish(2);var table=new JTable(model);table.setRowSorter(new CaptureRowSorter(model,error->{throw error;}));holder.set(new TrafficBrowser(table,model,new JTextField(),SimpleWorkflowTest.previewApi(),()->disk,()->{}));holder.get().search.setText(OLD);begin(holder.get());});
      var browser=holder.get();
      try{
        waitEdt(()->browser.idle()&&browser.table.convertRowIndexToView(0)>=0&&browser.table.convertRowIndexToView(1)<0,"old search is fully published");
        browser.worker.submit(()->{workerEntered.countDown();try{workerRelease.await(8,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});await(workerEntered,"worker gate entered");
        var once=new AtomicBoolean(true);browser.identities=()->{if(once.compareAndSet(true,false)){entered.countDown();try{if(!release.await(8,TimeUnit.SECONDS))throw new IllegalStateException("Preparation gate deadline");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}}return List.of();};
        SwingUtilities.invokeLater(()->{try{browser.search.setText(NEXT);begin(browser);}catch(Throwable error){failure.set(error);}finally{prepared.countDown();}});
        await(entered,"begin reaches its identity preparation gate");
        int idleReads=0;for(int i=0;i<1000;i++)if(browser.idle())idleReads++;
        boolean oldView=browser.view!=null&&browser.view.rows().get(0)&&!browser.view.rows().get(1);
        result.put("crossThreadReadSamples",1000);result.put("idleTrueReadsWhilePreparationBlocked",idleReads);result.put("oldViewRemainsPublishedWhilePreparationBlocked",oldView);
        result.put("controlledRaceReproduced",idleReads>0&&oldView);result.put("idleContractPassed",idleReads==0);
        release.countDown();await(prepared,"synchronous preparation finishes");if(failure.get()!=null)throw new AssertionError("Preparation callback failed",failure.get());
        int workerIdleReads=0;for(int i=0;i<1000;i++)if(browser.idle())workerIdleReads++;
        result.put("idleTrueReadsWhileWorkerBlocked",workerIdleReads);workerRelease.countDown();
        waitEdt(()->browser.idle()&&browser.searchViewCurrent()&&browser.table.convertRowIndexToView(1)>=0,"new original is visible once the search is ready");
        edt(()->{int view=browser.table.convertRowIndexToView(1);browser.table.setRowSelectionInterval(view,view);result.put("newOriginalSelectedExactly",disk.ref(browser.table.convertRowIndexToModel(browser.table.getSelectedRow())).id().equals(next.id()));});
        result.put("previousOriginalId",old.id());result.put("newOriginalId",next.id());
        result.put("targetHTTPDelta",PayloadIntegrationTest.sent.get()-sends);
        result.put("functionalPublicationPassed",Boolean.TRUE.equals(result.get("newOriginalSelectedExactly"))&&workerIdleReads==0);
        if(!phase.equals("before")&&!phase.equals("baseline")&&idleReads!=0)throw new AssertionError("Search preparation falsely reports idle");
        result.put("result","PASS");
        result.put("interpretation",phase.equals("before")||phase.equals("baseline")?"PASS means the controlled observation completed; idleContractPassed records whether the product contract held. This does not prove the earlier whole-workflow failure had the same cause.":"No false-idle readiness during controlled preparation or worker execution; the exact new original becomes selectable after publication.");
      }finally{release.countDown();workerRelease.countDown();edt(browser::close);}
    }catch(Throwable error){result.put("result","FAIL");result.put("failureClass",error.getClass().getSimpleName());throw error;}
    finally{Files.writeString(output.resolve("result.json"),MetadataExport.json(result)+"\n");System.out.println(MetadataExport.json(result));}
  }
}
