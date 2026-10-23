package kr.workbench;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** A canceled search stays canceled during capture refresh; only the newest query can publish. */
public final class TrafficCancellationTest extends WorkbenchTest {
  public static void main(String[] args)throws Exception {
    installTrafficPacketFactory();
    try(var disk=new DiskTrafficStore()) {
      disk.append("06:00:00","PROXY",request("https://next.test/one","GET",true,List.of()),response(200,"one"));
      disk.append("06:00:01","PROXY",request("https://next.test/two","GET",true,List.of()),response(200,"two"));
      AtomicReference<TrafficBrowser> holder=new AtomicReference<>();
      edt(()->{
        var model=new CaptureTableModel(disk::ref);model.publish(2);
        var table=new JTable(model);table.setRowSorter(new CaptureRowSorter(model,e->{throw e;}));
        holder.set(new TrafficBrowser(table,model,new JTextField(),null,()->disk,()->{}));
      });
      var browser=holder.get();
      try {
        edt(()->browser.search.setText("/one"));TrafficSearchUpgradeTest.idle(browser);
        edt(()->{browser.search.setText("/two");browser.cancel.doClick();browser.refresh();
          check(browser.idle(),"capture refresh cannot restart an explicitly canceled search");
          check(browser.table.getRowCount()==1&&browser.table.convertRowIndexToModel(0)==0,"cancellation preserves previous visible results");
        });
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        browser.worker.submit(()->{started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}});
        check(started.await(2,TimeUnit.SECONDS),"test worker is occupied while queries change");
        try {
          var begin=TrafficBrowser.class.getDeclaredMethod("begin");begin.setAccessible(true);
          for(String query:List.of("old-one","old-two","/two"))edt(()->{
            browser.search.setText(query);
            try{begin.invoke(browser);}catch(Exception ex){throw new RuntimeException(ex);}
            check(browser.worker.getQueue().size()<=1,"only latest pending search is retained");
          });
        }finally{release.countDown();}
        TrafficSearchUpgradeTest.idle(browser);
        edt(()->check(browser.table.getRowCount()==1&&browser.table.convertRowIndexToModel(0)==1,"only newest query publishes after backlog clears"));
        edt(()->browser.search.setText("x".repeat(513)));
        Thread.sleep(300);edt(()->{browser.refresh();check(browser.idle(),"oversized query ends busy state and does not loop on capture refresh");});
        edt(()->{browser.query=new TrafficQuery("전체","전체",0,false,true,false,true,false,true,"",0);browser.search.setText("[");});
        Thread.sleep(300);edt(()->{browser.refresh();check(browser.idle()&&browser.feedback.getText().startsWith("정규식 오류"),"invalid regex stays paused until user changes search conditions");});
      }finally{edt(browser::close);}
    }
    System.out.println("TrafficCancellationTest: "+checks+" checks passed");
  }
}
