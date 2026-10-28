package kr.workbench;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;

/** Metadata/process-contract checks. Real Katana networking belongs to the native loopback harness. */
public final class ScopeKatanaTest extends WorkbenchTest {
  public static void main(String[] args)throws Exception {
    String origin="https://scope.test";
    Predicate<String> scoped=url->url.equals(origin+"/app")||url.startsWith(origin+"/app/");
    var config=new ScopeKatana.Config("/private/KATANA_EXECUTABLE",60,20,3,5000,1024*1024,500);
    check(!config.toString().contains("KATANA_EXECUTABLE"),"Katana settings display does not disclose a local executable path");
    rejects(()->new ScopeKatana.Config("katana",0,20,3,5000,1024,0),"Katana absolute duration must be bounded");
    rejects(()->new ScopeKatana.Config("katana",60,501,3,5000,1024,0),"Katana page budget cannot exceed the shared inspection bound");
    rejects(()->new ScopeKatana.Config("katana\n-other",60,10,3,5000,1024,0),"executable input cannot inject control characters");
    var command=ScopeKatana.command(config,origin+"/app",origin,"http://wb:LOCAL_CAPABILITY@127.0.0.1:12345",Path.of("private-config.yaml"),Path.of("private-fields.yaml"),17,8);
    for(String flag:List.of("-proxy","-config","-flc","-duc","-j","-silent","-nc","-jc","-td","-fx","-dr","-iqp","-or"))check(command.contains(flag),"standard Katana command retains required "+flag);
    for(String flag:List.of("-hl","-hh","-cwu","-H","-aff","-al","-rf","-kb","-kb-secrets","-kb-validate-secrets","-sr","-srd","-up"))check(!command.contains(flag),"Katana command excludes additional execution flag "+flag);
    check(value(command,"-retry").equals("0")&&value(command,"-c").equals("1")&&value(command,"-p").equals("1"),"Katana retries and request concurrency stay explicitly bounded");
    check(value(command,"-ct").equals("17s")&&value(command,"-mdp").equals("8"),"resume command receives remaining absolute time and page allowance");
    check(value(command,"-eof").contains("headers")&&value(command,"-eof").contains("body"),"JSONL transport excludes credentials and raw response content in addition to omit flags");
    check(!command.contains("-ob")&&Arrays.asList(value(command,"-eof").split(",")).contains("body"),"Katana JS parsing retains its response body while JSON field projection prevents body output; upstream omit-body mutates before parsing");
    String scope=value(command,"-cs");
    check((origin+"/app").matches(scope+".*")&&!"https://scope.test.evil/app".matches(scope+".*")&&!"https://scope.test:444/app".matches(scope+".*"),"CLI Scope expression fixes the exact authority while the proxy enforces live native rules");
    check(command.stream().noneMatch(s->s.contains("COOKIE_FIXTURE")||s.contains("Bearer ")),"application credentials are absent from the process command");
    check(!command.contains("-u")&&!command.contains(origin+"/app"),"target seed URL is reserved for the private stdin pipe rather than process arguments");

    var found=ScopeKatana.parseCandidate("{\"request\":{\"method\":\"GET\",\"endpoint\":\"https://scope.test/app/js-only\",\"source\":\"https://scope.test/app/main.js?token=PRIVATE_VALUE\",\"tag\":\"js\",\"attribute\":\"regex\"},\"response\":{\"status_code\":200,\"headers\":{\"set-cookie\":\"PRIVATE_VALUE\"},\"body\":\"PRIVATE_VALUE\"}}",origin,scoped);
    check(found.url().endsWith("/app/js-only")&&found.method().equals("GET")&&found.executable(),"actual Katana endpoint field creates a scoped GET candidate");
    check(found.source().contains("Katana v1.8.0")&&found.source().contains("js/regex"),"candidate records retain explicit Katana parser provenance");
    check(found.label().contains("/app/main.js")&&!found.toString().contains("PRIVATE_VALUE"),"source URL query values and ignored JSONL secrets never enter metadata");
    var query=ScopeKatana.parseCandidate("{\"request\":{\"method\":\"GET\",\"endpoint\":\"https://scope.test/app/search?key=PRIVATE_VALUE\"}}",origin,scoped);
    check(!query.executable()&&!query.url().contains("?")&&!query.toString().contains("PRIVATE_VALUE"),"query-dependent results remain redacted observations rather than guessed replays");
    var write=ScopeKatana.parseCandidate("{\"request\":{\"method\":\"POST\",\"endpoint\":\"https://scope.test/app/write\",\"body\":\"PRIVATE_VALUE\",\"tag\":\"htmx\",\"attribute\":\"hx-post\"}}",origin,scoped);
    check(write.method().equals("POST")&&!write.executable()&&write.source().contains("hx-post"),"htmx write discoveries retain provenance without becoming executable candidates");
    var forms=ScopeKatana.parseCandidates("{\"request\":{\"method\":\"GET\",\"endpoint\":\"https://scope.test/app/page\"},\"response\":{\"forms\":[{\"method\":\"post\",\"action\":\"/app/native-form\",\"parameters\":[\"PRIVATE_FIELD\"]},{\"method\":\"GET\",\"action\":\"https://other.test/outside\"}]}}",origin,scoped);
    check(forms.size()==2&&forms.get(1).method().equals("POST")&&!forms.get(1).executable()&&forms.get(1).source().equals("Katana 폼"),"actual Katana form extraction preserves a non-executable POST declaration");
    check(forms.get(1).label().contains("PRIVATE_FIELD"),"form extraction displays parameter names without reading any field values");
    var metadata=ScopeKatana.parseCandidates("{\"request\":{\"method\":\"GET\",\"endpoint\":\"https://scope.test/app/page\"},\"response\":{\"technologies\":[\"nginx\",\"PostgreSQL\",\"token=PRIVATE_VALUE\",\"https://evil.test/PRIVATE_VALUE\"],\"forms\":[{\"method\":\"GET\",\"action\":\"/app/search\",\"parameters\":[\"q\",\"csrf_token\",\"password=PRIVATE_VALUE\"]}]}}",origin,scoped);
    var environment=metadata.stream().filter(v->v.source().equals("Katana 환경")).findFirst().orElseThrow();
    check(environment.method().equals("?")&&!environment.executable()&&environment.label().contains("nginx")&&environment.label().contains("PostgreSQL"),"bounded technology names surface as non-executable Katana environment clues");
    check(metadata.stream().noneMatch(v->v.toString().contains("PRIVATE_VALUE")||v.label().contains("csrf_token")),"sensitive field names and untrusted technology strings are masked or excluded");
    var unknown=ScopeKatana.parseCandidate("{\"request\":{\"endpoint\":\"https://scope.test/app/unknown\"}}",origin,scoped);
    check(unknown.method().equals("UNKNOWN")&&!unknown.executable(),"an absent Katana method never silently becomes GET");
    for(String url:List.of("https://other.test/app","https://scope.test/apple","https://scope.test:444/app","https://scope.test/app/%252fadmin","https://user:password@scope.test/app"))check(ScopeKatana.parseCandidate("{\"request\":{\"method\":\"GET\",\"endpoint\":\""+url+"\"}}",origin,scoped)==null,"metadata Scope validation rejects "+url.replace("password","[REDACTED]"));
    rejects(()->ScopeKatana.parseCandidate("{\"request\":{},\"request\":{}}",origin,scoped),"duplicate JSON keys fail the crawler protocol check");
    rejects(()->ScopeKatana.parseCandidate("{\"url\":\"https://scope.test/app\"}",origin,scoped),"plain URLs and unrelated JSON are not accepted as Katana JSONL results");
    rejects(()->ScopeKatana.parseCandidate(" ".repeat(256*1024+1),origin,scoped),"crawler JSONL line size is bounded before parsing");

    var callbacks=new AtomicInteger();var targetCalls=new AtomicInteger();var complete=new AtomicBoolean(true);
    var stopped=new ScopeKatana(config,origin+"/app",List.of(origin+"/"),List.of(),scoped,(u,m)->{targetCalls.incrementAndGet();throw new AssertionError("must not fetch");},v->{},v->{},(ok,note)->{callbacks.incrementAndGet();complete.set(ok);},v->{});
    check(stopped.snapshot().state().equals("READY")&&targetCalls.get()==0,"constructing and configuring Katana sends no target requests");
    stopped.stop();check(stopped.snapshot().state().equals("STOPPED")&&!complete.get()&&callbacks.get()==1,"stop reports incomplete once without executing a child");
    stopped.stop();stopped.close();check(callbacks.get()==1&&stopped.snapshot().state().equals("CLOSED"),"repeated stop and unload cannot duplicate completion callbacks");
    var ended=new CountDownLatch(1);var failure=new AtomicReference<Boolean>();
    try(var missing=new ScopeKatana(new ScopeKatana.Config(Path.of(System.getProperty("java.io.tmpdir"),"missing-katana-"+UUID.randomUUID()).toString(),10,5,1,1000,1024,0),origin+"/app",List.of(origin+"/"),List.of(),scoped,(u,m)->{targetCalls.incrementAndGet();throw new AssertionError("must not fetch");},v->{},v->{},(ok,note)->{failure.set(ok);ended.countDown();},v->{})){
      long before=System.nanoTime();missing.start();check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<1000,"Katana start returns without blocking the UI for executable probing");
      check(ended.await(8,TimeUnit.SECONDS)&&Boolean.FALSE.equals(failure.get())&&missing.snapshot().state().equals("FAILED"),"an unavailable executable cannot be reported as a completed crawl");
      check(targetCalls.get()==0&&!missing.snapshot().message().contains(System.getProperty("java.io.tmpdir")),"startup failure preserves zero target requests and a generic masked diagnostic");
    }
    System.out.println("ScopeKatanaTest: "+checks+" checks passed (bounded CLI/JSONL/lifecycle; real crawler covered separately)");
  }
  private static String value(List<String> args,String flag){int index=args.indexOf(flag);return index>=0&&index+1<args.size()?args.get(index+1):"";}
}
