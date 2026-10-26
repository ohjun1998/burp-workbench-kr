package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.awt.Component;
import java.awt.Container;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPOutputStream;
import javax.swing.*;

/** Actual disk originals and Swing actions: search context, recovery, project guards and inline feedback. */
public final class TrafficUsability0532Test extends WorkbenchTest {
  static TrafficQuery packets(){return new TrafficQuery("전체","전체",2,false,false,false,true,false,true,"",0);}
  static void await(BooleanSupplier condition,String message)throws Exception{
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
    while(System.nanoTime()<end){AtomicBoolean ready=new AtomicBoolean();edt(()->ready.set(condition.getAsBoolean()));if(ready.get())return;Thread.sleep(10);}
    throw new AssertionError(message);
  }
  static void settled(TrafficBrowser browser)throws Exception{await(()->browser.positionReady(browser.capturePosition()),"published search did not settle");}
  static TrafficBrowser browser(DiskTrafficStore disk,MontoyaApi api)throws Exception{
    AtomicReference<TrafficBrowser> result=new AtomicReference<>();
    edt(()->{var model=new CaptureTableModel(disk::ref);model.publish(Math.toIntExact(disk.size()));var table=new JTable(model);table.setRowSorter(new CaptureRowSorter(model,e->{throw e;}));result.set(new TrafficBrowser(table,model,new JTextField(),api,()->disk,()->{}));});
    return result.get();
  }
  static Component named(Component owner,String name){if(name.equals(owner.getName()))return owner;if(owner instanceof Container parent)for(Component child:parent.getComponents()){Component result=named(child,name);if(result!=null)return result;}return null;}
  static PersistedObject data(Map<String,String> values){return proxy(PersistedObject.class,(p,m,a)->switch(m.getName()){case "getString"->values.get(a[0]);case "setString"->{values.put(a[0].toString(),a[1].toString());yield null;}default->def(m);});}

  public static void main(String[] args)throws Exception{
    installTrafficPacketFactory();
    TrafficQuery packets=packets();String value="HTTP/1.1 200\r\nContent-Type: text/plain\r\n\r\n"+"x".repeat(72_000)+"needle 한글😀"+"y".repeat(6000)+"NEEDLE";
    var contexts=TrafficMatchContext.extract(value,"응답 원문",packets,"needle",false,()->false);
    check(contexts.pages().size()==2,"contexts retain both literal matches beyond the normal 64 KiB display");
    var first=contexts.pages().get(0);
    check(first.text().length()<=TrafficMatchContext.CONTEXT_CHARS&&first.text().substring(first.start(),first.end()).equals("needle"),"bounded context highlights the exact matched text");
    check(first.sourceOffset()+first.start()==value.indexOf("needle")&&first.location().equals("응답 원문 본문"),"context character offset and request/response body location are explicit");
    check(contexts.pages().get(1).text().substring(contexts.pages().get(1).start(),contexts.pages().get(1).end()).equals("NEEDLE"),"list case-insensitive semantics carry into context highlighting");
    var many=TrafficMatchContext.extract("needle ".repeat(300),"요청 원문",packets,"needle",true,()->false);
    check(many.pages().size()==TrafficMatchContext.MAX_PAGES&&many.capped()&&many.partial(),"context enumeration and partial wire bounds are explicit");
    check(many.pages().stream().mapToInt(p->p.text().length()).sum()<=TrafficMatchContext.MAX_PAGES*TrafficMatchContext.CONTEXT_CHARS,"retained excerpts have a fixed total memory bound");
    var expression=new TrafficQuery("전체","전체",2,true,true,false,true,false,true,"",0);
    var large=TrafficMatchContext.extract("x".repeat(20_000),"요청 원문",expression,"x+",false,()->false);
    check(large.pages().size()==1&&large.pages().get(0).text().length()<=TrafficMatchContext.CONTEXT_CHARS,"a huge regex match cannot expand the excerpt allocation");
    var invalid=TrafficMatchContext.extract("abc","요청 원문",expression,"[",false,()->false);
    check(invalid.pages().isEmpty()&&invalid.note().contains("오류"),"invalid expressions cannot publish stale contexts");
    boolean cancelled=false;try{TrafficMatchContext.extract(value,"응답 원문",packets,"needle",false,()->true);}catch(CancellationException error){cancelled=true;}
    check(cancelled,"context extraction rejects a cancelled selected original");
    check(TrafficBrowser.searchProblem("[",true).contains("정규식")&&TrafficBrowser.searchProblem("[",false).isEmpty(),"inline validation distinguishes regex mode from literal text");
    check(TrafficBrowser.searchProblem("x".repeat(513),false).contains("512"),"inline feedback validates the search length before applying");

    try(var disk=new DiskTrafficStore()){
      var request=request("https://context.test/items/1","GET",true,List.of());var response=response(200,value.substring(value.indexOf("\r\n\r\n")+4));
      disk.append(request,response);
      var original=disk.ref(0);byte[] exactResponse=response.toByteArray().getBytes();
      TrafficBrowser browser=browser(disk,null);PacketInspector[] displays=new PacketInspector[2];
      edt(()->{displays[0]=new PacketInspector("test.context.request");displays[1]=new PacketInspector("test.context.response");});
      try{
        AtomicInteger publications=new AtomicInteger();AtomicBoolean readyAtPublication=new AtomicBoolean();
        edt(()->{browser.table.setRowSelectionInterval(0,0);browser.onViewPublished(()->{publications.incrementAndGet();readyAtPublication.set(browser.positionReady(browser.capturePosition()));});});
        edt(()->{browser.query=packets;browser.loadControls();browser.search.setText("needle");});settled(browser);
        check(publications.get()>0&&readyAtPublication.get(),"host is notified after the exact view is published and no longer busy");
        check(browser.table.getSelectedRow()==0,"publication callback also covers an unchanged selected original without another selection event");
        edt(()->{displays[0].setPacket(original.requestPrefix(PacketInspector.PREVIEW_BYTES),null,original.requestLength());displays[1].setPacket(original.responsePrefix(PacketInspector.PREVIEW_BYTES),null,original.responseLength());
          check(browser.revealSelectionMatches(0,displays[0],displays[1]).contains("검색 일치 위치"),"selecting a published packet search offers linked context navigation");});
        await(()->displays[1].trafficContextNotice.getText().contains("일치 1/2"),"response context not published");
        edt(()->{
          check(displays[1].text.getText().contains("needle 한글😀")&&displays[1].text.getHighlighter().getHighlights().length==1,"real disk hit outside prefix is visible and highlighted");
          check(!displays[1].text.isEditable()&&displays[1].previewBytes().length==PacketInspector.PREVIEW_BYTES,"context is read-only and does not replace authoritative preview bytes");
          displays[1].trafficNext.doClick(0);check(displays[1].trafficContextNotice.getText().contains("일치 2/2"),"next navigates to another bounded source context");
          displays[1].trafficPrevious.doClick(0);check(displays[1].trafficContextNotice.getText().contains("일치 1/2"),"previous returns to the prior context");
          displays[1].trafficReturn.doClick(0);check(!displays[1].trafficContextBar.isVisible()&&!displays[1].text.getText().contains("needle"),"return restores the original bounded prefix view");
        });
        check(Arrays.equals(disk.ref(0).response().toByteArray().getBytes(),exactResponse),"context navigation leaves retained original HTTP bytes unchanged");
        edt(()->{browser.revealSelectionMatches(0,displays[0],displays[1]);browser.search.setText("different query");check(!displays[1].trafficContextBar.isVisible(),"changing the list query immediately invalidates linked packet context");});
        Thread.sleep(250);edt(()->check(!displays[1].trafficContextBar.isVisible()&&!displays[1].text.getText().contains("needle"),"late context for an old list query cannot publish on the same selected packet"));
        edt(()->browser.search.setText("needle"));settled(browser);
        edt(()->{displays[1].followTrafficSearch(original,true,packets,"needle");displays[1].search.setText("packet-local query");});
        await(()->displays[1].trafficContextNotice.getText().contains("일치 1/2"),"normal packet search dropped a pending list context: "+displays[1].trafficContextNotice.getText());
        edt(()->{check(displays[1].text.getText().contains("needle"),"normal packet search cannot silently discard a pending list context task");displays[1].trafficReturn.doClick(0);});
        edt(()->{displays[1].followTrafficSearch(original,true,packets,"needle");displays[1].setPacket("NEW PACKET".getBytes(StandardCharsets.UTF_8),null);});
        Thread.sleep(250);edt(()->check(displays[1].text.getText().equals("NEW PACKET")&&!displays[1].trafficContextBar.isVisible(),"stale async context cannot attach to a newly selected packet"));
        var metadata=TrafficMatchContext.locate(original,true,packets,"context.test",()->false);
        check(metadata.pages().isEmpty()&&metadata.source().equals("목록 메타데이터"),"metadata matches are not presented as response-body matches");
        edt(()->{browser.query=TrafficQuery.defaults();browser.loadControls();browser.search.setText("context.test");});settled(browser);
        edt(()->check(browser.revealSelectionMatches(0,displays[0],displays[1]).contains("목록 메타데이터"),"metadata-only list searches name their source"));
      }finally{edt(()->{browser.close();for(var display:displays)display.close();});}
    }

    // Compressed response wire is small, but the match is far into its decoded body.
    try(var disk=new DiskTrafficStore()){
      String decoded="가".repeat(40_000)+"decoded-needle";ByteArrayOutputStream out=new ByteArrayOutputStream();
      try(var gzip=new GZIPOutputStream(out)){gzip.write(decoded.getBytes(StandardCharsets.UTF_8));}
      byte[] compressed=out.toByteArray(),head="HTTP/1.1 200\r\nContent-Encoding: gzip\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n".getBytes(StandardCharsets.UTF_8);
      byte[] wire=Arrays.copyOf(head,head.length+compressed.length);System.arraycopy(compressed,0,wire,head.length,compressed.length);
      HttpResponse response=proxy(HttpResponse.class,(p,m,a)->switch(m.getName()){
        case "statusCode"->(short)200;case "body"->bytes(compressed);case "toByteArray"->bytes(wire);case "toString"->new String(wire,StandardCharsets.ISO_8859_1);
        case "headerValue"->a[0].toString().equalsIgnoreCase("Content-Encoding")?"gzip":a[0].toString().equalsIgnoreCase("Content-Type")?"text/plain; charset=UTF-8":null;default->def(m);
      });
      var request=request("https://decoded.test/a","GET",true,List.of());registerTrafficPacket(request,response);disk.append(request,response);
      var found=TrafficMatchContext.locate(disk.ref(0),true,packets,"decoded-needle",()->false);
      check(found.source().equals("응답 디코딩 본문")&&found.pages().size()==1,"compressed decoded hits have a distinct source label");
      check(found.pages().get(0).sourceOffset()+found.pages().get(0).start()==40_000,"decoded Unicode character offsets are not labelled as compressed wire bytes");
    }

    Map<String,String> firstProject=new HashMap<>(),secondProject=new HashMap<>();
    firstProject.put(BuildInfo.storageKey("auth-setup-library-project-v1"),"11111111-1111-1111-1111-111111111111");
    secondProject.put(BuildInfo.storageKey("auth-setup-library-project-v1"),"22222222-2222-2222-2222-222222222222");
    AtomicReference<PersistedObject> current=new AtomicReference<>(data(firstProject));
    var persistence=proxy(Persistence.class,(p,m,a)->m.getName().equals("extensionData")?current.get():def(m));
    var api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("persistence")?persistence:def(m));
    try(var disk=new DiskTrafficStore()){
      disk.append(request("https://recover.test/items/1","GET",true,List.of()),response(200,"ok"));
      disk.append(request("https://recover.test/items/2","POST",true,List.of()),response(403,"denied"));
      TrafficBrowser browser=browser(disk,api);BackgroundJobs jobs=new BackgroundJobs();
      try{
        edt(()->{browser.backgroundJobs=jobs;browser.method.setSelectedItem("GET");browser.response.setSelectedItem("403");browser.apply();browser.search.setText("items");});settled(browser);
        check(browser.table.getRowCount()==0&&browser.feedback.getText().contains("검색 조건에 맞는 항목이 없습니다"),"nonempty capture with zero matches explains filtering rather than missing capture");
        edt(()->check(browser.recoveryActions.isVisible()&&browser.relaxCondition.isEnabled(),"zero-result view offers a reversible individual condition action"));
        AtomicReference<TrafficBrowser.SearchPosition> before=new AtomicReference<>();edt(()->{before.set(browser.capturePosition());browser.relaxOneCondition("status");});settled(browser);
        check(browser.table.getRowCount()==1&&browser.query.method().equals("GET")&&browser.query.status().equals("전체")&&browser.search.getText().equals("items"),"one-filter relaxation preserves every other condition and exact originals");
        edt(()->browser.undoRelaxation.doClick(0));settled(browser);
        check(browser.capturePosition().equals(before.get())&&browser.table.getRowCount()==0,"undo restores both prior query and keyword");
        edt(()->{browser.relaxOneCondition("status");browser.search.setText("manual change");rejects(browser::undoRelaxation,"intervening edits prevent stale undo overwriting the current query");});
        edt(()->browser.restorePosition(before.get()));settled(browser);
        check(browser.positionReady(before.get()),"in-memory navigation waits for its exact published view");
        edt(()->{browser.search.setText("different");check(!browser.positionReady(before.get()),"old navigation state is not ready while controls differ or evaluation is pending");browser.restorePosition(before.get());});settled(browser);
        check(firstProject.values().stream().noneMatch(v->v.contains("items")||v.contains("manual change")),"free search and recovery history never enter project persistence");
        edt(()->{browser.relaxOneCondition("status");current.set(data(secondProject));browser.refresh();rejects(()->browser.restorePosition(before.get()),"cross-project navigation restore is refused");rejects(browser::undoRelaxation,"cross-project search recovery is discarded");});
        check(browser.search.getText().isEmpty()&&browser.query.equals(TrafficQuery.defaults()),"project switch clears prior keyword and recovery state");
        edt(()->{
          var settings=browser.content();browser.regex.doClick(0);browser.search.setText("[");
          JButton apply=(JButton)named(settings,"wb.traffic.search.apply");
          check(!apply.isEnabled()&&browser.settingsProblem.getText().contains("정규식")&&apply.getToolTipText().contains("정규식"),"invalid regex is explained beside the form and disabled Apply");
          browser.search.setText("users/[0-9]+");check(apply.isEnabled()&&!browser.settingsProblem.isVisible(),"fixing the input clears feedback and enables Apply immediately");
          browser.regex.doClick(0);browser.search.setText("items");browser.cancel.doClick(0);
          check(browser.feedback.getText().contains("검색 취소")&&!browser.relaxCondition.isEnabled(),"cancelled search explicitly retains old results without offering stale recovery actions");
        });
        check(jobs.snapshot().stream().anyMatch(j->j.state()==BackgroundJobs.State.COMPLETED),"successful background search is recorded only when its view is published");
        check(jobs.snapshot().stream().noneMatch(j->j.title().contains("items")||j.stage().contains("items")),"shared task metadata excludes free search values");
      }finally{edt(browser::close);jobs.close();}
    }
    try(var empty=new DiskTrafficStore()){
      TrafficBrowser browser=browser(empty,null);try{edt(browser::refresh);settled(browser);check(browser.feedback.getText().contains("수집된 트래픽이 없습니다"),"empty capture explains SCOPE/capture setup separately from zero matches");}finally{edt(browser::close);}
    }
    try(var partial=new DiskTrafficStore()){
      partial.append(request("https://partial.test/a","GET",true,List.of()),response(200,"x".repeat(TrafficBrowser.SEARCH_BYTES+100)));
      TrafficBrowser browser=browser(partial,null);try{
        var view=browser.scan(partial,1,packets,"not-present",List.of(),Set.of(),()->false);
        check(view.matched()==0&&view.partial()>0&&TrafficBrowser.resultFeedback(view).contains("검사 범위 밖의 일치 여부는 미확인"),"zero-result partial scans never claim the entire original has no match");
      }finally{edt(browser::close);}
    }
    System.out.println("TrafficUsability0532Test: "+checks+" checks passed");
  }
}
