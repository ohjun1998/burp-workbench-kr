package kr.workbench;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

public final class TrafficSearchUpgradeTest extends WorkbenchTest {
  static void edt(Runnable r)throws Exception{SwingUtilities.invokeAndWait(r);}
  static void idle(TrafficBrowser browser)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);for(;;){AtomicBoolean done=new AtomicBoolean();edt(()->done.set(browser.idle()));if(done.get())return;if(System.nanoTime()>end)throw new AssertionError("traffic search timeout");Thread.sleep(15);}}
  public static void main(String[] args)throws Exception{
    installTrafficPacketFactory();
    var q=TrafficQuery.defaults();check(q.matchesMeta("GET","https://t.test/users", "200","","users",()->false),"literal metadata search matches URL");
    check(!q.matchesText("/a?x=1",".*",()->false),"ordinary search does not treat regex symbols as patterns");
    var configured=new TrafficQuery("GET","2xx",2,false,false,true,true,false,true,"Alice",0);
    check(configured.matchesMeta("GET","https://t.test/u","201","Alice","",()->false)&&!configured.matchesMeta("POST","https://t.test/u","201","Alice","",()->false),"method status-family and account conditions combine");
    check(TrafficQuery.read(MetadataExport.json(configured.json())).equals(configured),"project settings serialize without packet values");
    check(TrafficQuery.read("bad").equals(q),"invalid settings use conservative defaults");
    check(configured.groupKey("GET","https://t.test/posts/101?q=a","A").equals(configured.groupKey("GET","https://t.test/posts/102?q=b","A")),"numeric path and query values group while query names remain");
    check(!configured.groupKey("GET","https://t.test/posts/101","A").equals(configured.groupKey("GET","https://t.test/posts/101","B")),"different sessions never share a request group");
    check(!configured.groupKey("GET","https://t.test/posts/101","A").equals(configured.groupKey("POST","https://t.test/posts/101","A")),"different methods never share a group");
    try(var disk=new DiskTrafficStore()){
      disk.append("06:00:00","PROXY",request("https://t.test/posts/101","GET",true,List.of(header("Cookie","SID=A"))),response(200,"alpha body"));
      disk.append("06:00:01","PROXY",request("https://t.test/posts/102","GET",true,List.of(header("Cookie","SID=A"))),response(403,"needle 한글"));
      disk.append("06:00:02","PROXY",request("https://t.test/posts/103","GET",true,List.of(header("Cookie","SID=B"))),response(200,"other"));
      AtomicReference<TrafficBrowser> ref=new AtomicReference<>();
      edt(()->{var model=new CaptureTableModel(row->disk.ref(row));model.publish(3);var table=new JTable(model);table.setRowSorter(new CaptureRowSorter(model,e->{throw e;}));ref.set(new TrafficBrowser(table,model,new JTextField(),null,()->disk,()->{}));});
      var browser=ref.get();try{
        var grouped=browser.scan(disk,3,new TrafficQuery("전체","전체",0,false,false,true,true,false,true,"",0),"",List.of(),Set.of(),()->false);
        check(grouped.rows().cardinality()==2&&grouped.groups().get(0).members().equals(List.of(0,1)),"same-session similar requests collapse with exact original indexes");
        check(grouped.groups().get(0).statuses().contains("200")&&grouped.groups().get(0).statuses().contains("403"),"group metadata retains mixed status distribution");
        var opened=browser.scan(disk,3,new TrafficQuery("전체","전체",0,false,false,true,true,false,true,"",0),"",List.of(),Set.of(grouped.groups().get(0).key()),()->false);
        check(opened.rows().cardinality()==3,"expanding a group reveals every original packet");
        var found=browser.scan(disk,3,new TrafficQuery("전체","전체",2,false,false,false,true,false,true,"",0),"needle 한글",List.of(),Set.of(),()->false);
        check(found.rows().get(1)&&found.rows().cardinality()==1,"response-body search finds Korean text beyond URL metadata");
        var metadata=browser.scan(disk,3,q,"needle",List.of(),Set.of(),()->false);check(metadata.rows().isEmpty(),"default metadata search does not silently include packet bodies");
        boolean cancelled=false;try{browser.scan(disk,3,q,"",List.of(),Set.of(),()->true);}catch(CancellationException e){cancelled=true;}check(cancelled,"background search honors cancellation");
        var profiles=List.of(new TrafficBrowser.Identity("https://t.test:443","Alice",Map.of("cookie","SID=A")));
        var named=browser.scan(disk,3,q,"",profiles,Set.of(),()->false);check("Alice".equals(named.accounts().get(0))&&!named.accounts().containsKey(2),"only an exact mapped credential is assigned an account label");
        edt(()->{browser.search.setText("/posts/102");});idle(browser);edt(()->check(browser.table.getRowCount()==1&&browser.table.convertRowIndexToModel(0)==1,"asynchronous search preserves selected packet identity"));
        edt(()->browser.toggleStar(1));idle(browser);check(browser.marks.get(disk.ref(1).id()).star(),"important flag attaches to the original packet");
        edt(()->browser.note(1,"권한 확인"));idle(browser);check(browser.marks.get(disk.ref(1).id()).note().equals("권한 확인"),"note is retained independently from row order");
        check(disk.size()==3&&disk.ref(1).response().statusCode()==403,"all view changes preserve original packet bytes and count");
        edt(()->{browser.search.setText("pending");browser.cancel.doClick();check(browser.idle(),"cancel also stops a pending debounced search");});
        edt(()->{var content=browser.content();check(UiLayoutTest.named(content,"wb.traffic.search.advanced")!=null&&!browser.advanced.isVisible(),"basic settings hide advanced controls by default");});
      }finally{edt(browser::close);}
    }
    try(var disk=new DiskTrafficStore()){
      String padding="x".repeat(66_000);
      disk.append("06:00:00","PROXY",request("https://t.test/posts/1","GET",true,List.of(header("X-Padding",padding),header("Cookie","SID=A"))),response(200,"a"));
      disk.append("06:00:01","PROXY",request("https://t.test/posts/2","GET",true,List.of(header("X-Padding",padding),header("Cookie","SID=B"))),response(200,"b"));
      AtomicReference<TrafficBrowser> ref=new AtomicReference<>();
      edt(()->{var model=new CaptureTableModel(disk::ref);model.publish(2);var table=new JTable(model);table.setRowSorter(new CaptureRowSorter(model,e->{throw e;}));ref.set(new TrafficBrowser(table,model,new JTextField(),null,()->disk,()->{}));});
      try{var found=ref.get().scan(disk,2,new TrafficQuery("전체","전체",0,false,false,true,true,false,true,"",0),"",List.of(),Set.of(),()->false);check(found.rows().cardinality()==2,"incomplete large headers never merge unresolved sessions");}
      finally{edt(ref.get()::close);}
    }
    var expression=new TrafficQuery("전체","전체",0,false,true,false,true,false,true,"",0);boolean invalid=false;try{expression.matchesText("x","[",()->false);}catch(IllegalArgumentException e){invalid=true;}check(invalid,"invalid regex is reported without replacing valid search results");
    System.out.println("TrafficSearchUpgradeTest: "+checks+" checks passed");
  }
}
