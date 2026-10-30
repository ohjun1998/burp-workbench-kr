package kr.workbench;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Controlled cross-thread readiness and exact original publication; no target HTTP. */
public final class SimpleTrafficReadinessTest extends WorkbenchTest {
  static final String OLD="https://readiness.test/fixed/501",NEXT="https://readiness.test/vuln/501",THIRD="https://readiness.test/new/501";

  static void begin(TrafficBrowser browser){
    try{Method method=TrafficBrowser.class.getDeclaredMethod("begin");method.setAccessible(true);method.invoke(browser);}
    catch(Exception error){throw new IllegalStateException("Search preparation failed",error);}
  }
  static void await(CountDownLatch latch,String label)throws Exception{if(!latch.await(8,TimeUnit.SECONDS))throw new AssertionError("Timeout: "+label);}
  static void waitEdt(BooleanSupplier condition,String label)throws Exception{
    long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
    while(System.nanoTime()<until){var ready=new AtomicBoolean();edt(()->ready.set(condition.getAsBoolean()));if(ready.get())return;Thread.sleep(10);}
    throw new AssertionError("Timeout: "+label);
  }
  static int idleReads(TrafficBrowser browser){int idle=0;for(int i=0;i<1000;i++)if(browser.idle())idle++;return idle;}
  static DiskTrafficStore.PacketRef append(DiskTrafficStore store,String url){return store.append("readiness synthetic","test",ScopeInspectionFixture.wireRequest(url,"GET",List.of(),new byte[0]),ScopeInspectionFixture.wireResponse(200,List.of(),url.getBytes(StandardCharsets.UTF_8)));}

  public static void main(String[] args)throws Exception{
    ScopeInspectionFixture.installFactory();int sends=PayloadIntegrationTest.sent.get();
    var created=new AtomicReference<TrafficBrowser>();
    try(var disk=new DiskTrafficStore()){
      append(disk,OLD);var wanted=append(disk,NEXT);var api=SimpleWorkflowTest.previewApi();
      edt(()->{var model=new CaptureTableModel(disk::ref);model.publish(2);var table=new JTable(model);table.setRowSorter(new CaptureRowSorter(model,error->{throw error;}));created.set(new TrafficBrowser(table,model,new JTextField(),api,()->disk,()->{}));created.get().search.setText(OLD);begin(created.get());});
      TrafficBrowser browser=created.get();
      var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var prepared=new CountDownLatch(1);
      var workerEntered=new CountDownLatch(1);var workerRelease=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
      try{
        waitEdt(()->browser.idle()&&browser.table.convertRowIndexToView(0)>=0&&browser.table.convertRowIndexToView(1)<0,"old original search published");
        browser.worker.submit(()->{workerEntered.countDown();try{workerRelease.await(8,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});await(workerEntered,"worker held independently of search");
        var first=new AtomicBoolean(true);
        browser.identities=()->{if(first.compareAndSet(true,false)){entered.countDown();try{if(!release.await(8,TimeUnit.SECONDS))throw new IllegalStateException("Preparation latch deadline");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}}return List.of();};
        SwingUtilities.invokeLater(()->{try{browser.search.setText(NEXT);begin(browser);}catch(Throwable error){failure.set(error);}finally{prepared.countDown();}});
        await(entered,"search identity preparation entered");
        check(browser.view!=null&&browser.view.rows().get(0)&&!browser.view.rows().get(1),"the controlled preparation gate still shows the previous original result");
        check(idleReads(browser)==0,"one blocked preparation interval yields zero false-idle observations across 1000 cross-thread reads");
        release.countDown();await(prepared,"preparation returns");check(failure.get()==null,"preparation continues without an EDT exception");
        check(idleReads(browser)==0,"queued worker execution is also reported as active");workerRelease.countDown();
        waitEdt(()->browser.idle()&&browser.searchViewCurrent()&&browser.table.convertRowIndexToView(1)>=0,"new exact original is published");
        edt(()->{int row=browser.table.convertRowIndexToView(1);browser.table.setRowSelectionInterval(row,row);check(disk.ref(browser.table.convertRowIndexToModel(browser.table.getSelectedRow())).id().equals(wanted.id()),"ready search selects the intended immutable original rather than a prior view index");});

        // A valid saved regex mode can receive a malformed new keyword later.
        edt(()->{browser.regex.setSelected(true);browser.apply();browser.search.setText("[");begin(browser);check(browser.idle()&&browser.feedback.getText().contains("정규식 오류"),"invalid regex stops cleanly without leaving preparation busy");});
        edt(()->{browser.regex.setSelected(false);browser.search.setText(NEXT);browser.apply();begin(browser);});
        waitEdt(()->browser.idle()&&browser.table.convertRowIndexToView(1)>=0,"corrected query recovers");
        check(disk.ref(1).id().equals(wanted.id()),"query-error recovery keeps the exact stored original");

        browser.identities=()->{throw new IllegalStateException("Synthetic local identity source unavailable");};
        edt(()->{browser.search.setText(OLD);begin(browser);check(browser.idle()&&browser.feedback.getText().contains("검색 준비 실패"),"a synchronous identity lookup failure reports a recoverable idle error");check(browser.view!=null&&browser.view.rows().get(1),"preparation failure preserves the previous completed view");});
        browser.identities=List::of;edt(()->{browser.search.setText(OLD);begin(browser);});
        waitEdt(()->browser.idle()&&browser.table.convertRowIndexToView(0)>=0,"identity source recovery");

        // Cancelling a queued search retains the previous result and permits another search.
        var held=new CountDownLatch(1);var finish=new CountDownLatch(1);
        browser.worker.submit(()->{held.countDown();try{finish.await(8,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});await(held,"cancellation worker gate");
        try{edt(()->{browser.search.setText(NEXT);begin(browser);check(!browser.idle(),"a queued search stays active before explicit cancellation");browser.cancel.doClick(0);check(browser.idle()&&browser.feedback.getText().contains("검색 취소"),"explicit cancellation clears logical pending work");check(browser.view.rows().get(0),"cancelled search keeps the previous completed original view");});}finally{finish.countDown();}
        edt(()->{browser.search.setText(NEXT);begin(browser);});waitEdt(()->browser.idle()&&browser.table.convertRowIndexToView(1)>=0,"retry after cancellation");

        edt(()->browser.setViewFrozen(true));var added=append(disk,THIRD);
        edt(()->{browser.publishCommittedRows();browser.search.setText(THIRD);begin(browser);});
        waitEdt(()->browser.idle(),"frozen view search finishes");
        edt(()->{check(browser.model.getRowCount()==2&&browser.pendingRows()==1,"frozen view retains its published boundary while the new original is stored");browser.setViewFrozen(false);begin(browser);});
        waitEdt(()->browser.idle()&&browser.table.convertRowIndexToView(2)>=0,"unfrozen new original publishes");
        check(disk.ref(2).id().equals(added.id()),"unfreezing publishes the same newly retained original");

        var synchronousReady=new AtomicBoolean();browser.onViewPublished(()->synchronousReady.set(browser.idle()));
        edt(()->{api.persistence().extensionData().setString(BuildInfo.storageKey("auth-setup-library-project-v1"),"33333333-3333-4333-8333-333333333333");browser.refresh();begin(browser);check(browser.search.getText().isEmpty()&&browser.idle(),"project change clears the old query without leaving preparation pending");check(synchronousReady.get(),"synchronous full-list publication notifies listeners only when its view is ready");});
        edt(()->{browser.close();begin(browser);check(browser.idle()&&browser.worker.isShutdown(),"closed browser rejects preparation and leaves no active worker");});
      }finally{release.countDown();workerRelease.countDown();edt(browser::close);}
    }
    check(PayloadIntegrationTest.sent.get()==sends,"readiness, validation, retry, cancellation, frozen view and project transitions send no HTTP");
    System.out.println("SimpleTrafficReadinessTest: "+checks+" checks passed");
  }
}
