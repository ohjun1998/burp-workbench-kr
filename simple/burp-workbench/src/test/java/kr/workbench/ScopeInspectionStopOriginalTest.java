package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

/** Reproduces cancellation after a wire original is retained but before target decoding/analysis completes. */
public final class ScopeInspectionStopOriginalTest extends WorkbenchTest {
  public static void main(String[] args)throws Exception{
    try(var fixture=new ScopeInspectionFixture()){
      String target=fixture.origin+"/app/decode-stop";
      fixture.server.createContext("/app/decode-stop",exchange->{
        fixture.visits.add(new ScopeInspectionFixture.Visit(exchange.getRequestMethod(),"/app/decode-stop","anonymous"));
        ScopeInspectionRunnerTest.reply(exchange,200,"application/json","{\"email\":\"retained@loopback.test\",\"marker\":\"STOP_BOUNDARY_PACKET\"}");
      });
      CountDownLatch decoding=new CountDownLatch(1),release=new CountDownLatch(1),released=new CountDownLatch(1);
      AtomicInteger bodyReads=new AtomicInteger();Http transport=fixture.api.http();
      Http blocking=proxy(Http.class,(p,m,a)->{
        Object result=m.invoke(transport,a);
        if(!m.getName().equals("sendRequest")||!(result instanceof HttpRequestResponse packet)||!packet.request().url().equals(target))return result;
        HttpResponse actual=packet.response();
        HttpResponse response=proxy(HttpResponse.class,(rp,rm,ra)->{
          if(rm.getName().equals("body")&&bodyReads.incrementAndGet()==2){
            // append() read the first body length and stored the full packet. The next
            // body read belongs to decode preparation, after the RESPONDED trace.
            decoding.countDown();boolean interrupted=false;
            while(true)try{release.await();break;}catch(InterruptedException stopped){interrupted=true;}
            released.countDown();if(interrupted)Thread.currentThread().interrupt();
          }
          return rm.invoke(actual,ra);
        });
        return proxy(HttpRequestResponse.class,(pp,pm,pa)->pm.getName().equals("response")?response:pm.invoke(packet,pa));
      });
      MontoyaApi host=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("http")?blocking:m.invoke(fixture.api,a));
      var options=new ScopeInspection.Options(target,30,0,0,3000,1024*1024,2,false,false,ScopeInspectionRunnerTest.anonymous());
      try(var runner=new ScopeInspectionRunner(host,options,url->url.startsWith(fixture.origin+"/app/"),s->{})){
        runner.start();check(decoding.await(10,TimeUnit.SECONDS),"latch reaches decoding only after actual target response retention");
        var before=runner.snapshot();var trace=before.coverage().stream().filter(r->r.url().equals(target)&&r.stages().contains("RESPONDED")).findFirst().orElseThrow();
        check(trace.status()==200&&!trace.originalId().isBlank()&&!trace.stages().contains("ANALYZED"),"latched trace has an actual response original and no analysis-complete stage");
        var beforePage=before.pages().stream().filter(page->page.original()!=null&&page.original().id().equals(trace.originalId())).findFirst().orElseThrow();
        check(beforePage.classification().equals("RESPONSE_PENDING_ANALYSIS")&&!beforePage.complete()&&beforePage.digest().isBlank(),"snapshot publishes pending response original without hashing or claiming analysis");
        int sent=runner.snapshot().sent(),visits=fixture.visits.size(),findings=runner.snapshot().findings().size();runner.stop();
        var stopped=runner.snapshot();var page=stopped.pages().stream().filter(value->value.original()!=null&&value.original().id().equals(trace.originalId())).findFirst().orElseThrow();
        check(stopped.state()==ScopeInspection.State.STOPPED&&stopped.partial()>0&&!page.complete(),"stop preserves the received original as explicitly partial pending analysis");
        check(page.status()==200&&page.account().equals(trace.account())&&page.profileRevision().equals(trace.profileRevision())&&page.method().equals("GET")&&page.url().equals(target),"pending original page preserves actual URL, method, account, revision and response status");
        check(runner.original(trace.originalId())==page.original()&&page.original().response().bodyToString().contains("STOP_BOUNDARY_PACKET"),"UI page linkage resolves to the exact retained wire response");
        check(stopped.pages().stream().filter(value->value.original()!=null&&value.original().id().equals(trace.originalId())).count()==1,"pending response projection does not duplicate an OriginalID");
        check(runner.snapshot().pages().size()==stopped.pages().size()&&runner.snapshot().partial()==stopped.partial(),"repeated snapshots do not append pages or inflate partial counters");
        boolean immutable=false;try{stopped.pages().clear();}catch(UnsupportedOperationException expected){immutable=true;}
        check(immutable,"pending response snapshot page list is immutable");
        var document=ScopeInspectionReport.collect(stopped);
        check(document.pages().stream().anyMatch(value->value.originalId().equals(trace.originalId())&&!value.complete())&&!document.complete(),"explicit raw report includes pending response source and keeps it incomplete");
        check(document.values().stream().anyMatch(value->value.source().originalId().equals(trace.originalId())&&value.value().equals("retained@loopback.test")),"explicit export can parse retained values with the same source OriginalID");
        Path folder=Files.createTempDirectory("scope-stop-original-");Path zip=folder.resolve("pending-original.zip");
        try{
          var written=ScopeInspectionReport.write(zip,stopped);check(!written.complete()&&written.partialPages()>0,"pending-response report ZIP remains a partial export");
          try(var archive=new ZipFile(zip.toFile())){
            String json=new String(archive.getInputStream(archive.getEntry("report.json")).readAllBytes(),StandardCharsets.UTF_8);
            check(json.contains(trace.originalId())&&json.contains("STOPPED")&&json.contains("retained@loopback.test"),"exported JSON retains stopped-run source identity and exact observed value");
          }
        }finally{Files.deleteIfExists(zip);Files.deleteIfExists(folder);}
        check(runner.snapshot().sent()==sent&&fixture.visits.size()==visits&&runner.snapshot().findings().size()==findings,"snapshot and explicit local report export send no HTTP or resume product analysis");
        release.countDown();check(released.await(5,TimeUnit.SECONDS),"interrupted decode fixture can be released for cleanup");
        // Drain the already interrupted worker via its retained single-thread executor.
        var executorField=ScopeInspectionRunner.class.getDeclaredField("executor");executorField.setAccessible(true);
        ((ExecutorService)executorField.get(runner)).submit(()->{}).get(5,TimeUnit.SECONDS);
        var settled=runner.snapshot();
        check(settled.pages().stream().anyMatch(value->value.original()!=null&&value.original().id().equals(trace.originalId())&&value.classification().equals("RESPONSE_PENDING_ANALYSIS")&&!value.complete()),"interrupted fetch cannot start target analysis after stop and overwrite pending status");
        check(settled.findings().size()==findings&&settled.sent()==sent&&fixture.visits.size()==visits,"settling cancelled decoding creates no target findings or further requests");
        check(before.state()==ScopeInspection.State.RUNNING&&beforePage.classification().equals("RESPONSE_PENDING_ANALYSIS")&&!beforePage.complete(),"later stop and cleanup cannot mutate earlier snapshot evidence");
      }finally{release.countDown();}
    }
    System.out.println("ScopeInspectionStopOriginalTest: "+checks+" checks passed (actual retained-response cancellation boundary)");
  }
}
