package kr.workbench;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Shape-only correlations are bounded metadata associations; fixtures never evaluate templates or transmit them. */
public final class ScopeInspectionCorrelationTest extends WorkbenchTest {
  private static ScopeInspectionCoverage.Row template(String key,String text,String method){
    return new ScopeInspectionCoverage.Row(key,text,method,"Alice","r1","JS API","동적 미해결","EXCLUDED","변수 미해결",0,"",false,List.of("DISCOVERED"));
  }
  private static ScopeInspectionCoverage.Row observed(String key,String url,String method,String actor,String state,int status,boolean query){
    return new ScopeInspectionCoverage.Row(key,url,method,actor,actor.equals("browser-session")?"browser-session":"r2","HTTP 응답","원본 관측",state,"",status,
        actor.equals("browser-session")?"":"packet-"+key,false,List.of("DISCOVERED","SCOPE_CHECKED","REQUESTED","RESPONDED"),query);
  }
  private static ScopeInspection.Finding source(ScopeInspectionCoverage.Row template){
    return new ScopeInspection.Finding("API","API 원문","https://scope.test/assets/app.js",template.method(),template.account(),"미확인","정보","동적 미해결",
        template.url(),"source-"+template.key(),1,0,ScopeInspectionCoverage.key(template.url()));
  }
  private static ScopeInspection.Snapshot snapshot(List<ScopeInspectionCoverage.Row> templates,List<ScopeInspectionCoverage.Row> actual){
    var rows=new ArrayList<>(templates);rows.addAll(actual);
    return new ScopeInspection.Snapshot("correlation-fixture",ScopeInspection.State.COMPLETED,"",0,0,0,0,0,0,
        templates.stream().map(ScopeInspectionCorrelationTest::source).toList(),List.of(),List.of(),0,0,rows);
  }
  public static void main(String[] args)throws Exception{
    var dynamic=template("dynamic","${host}/api/users/${id}","GET");
    var openapi=template("openapi","/api/users/{id}","GET");
    var actual=observed("actual","https://scope.test/api/users/42","GET","Bob","ANALYZED",200,false);
    var browser=observed("browser","https://scope.test/api/users/81","GET","browser-session","HTTP_RESPONDED",200,false);
    var result=ScopeInspectionCoverage.correlations(snapshot(List.of(dynamic,openapi),List.of(actual,browser)));
    check(result.size()==4,"known JS and OpenAPI single-segment templates associate with actual same-origin response paths");
    var association=result.stream().filter(c->c.templateKey().equals("dynamic")&&c.observedKey().equals("actual")).findFirst().orElseThrow();
    check(association.template().equals("${host}/api/users/${id}")&&association.observedUrl().equals(actual.url()),"association preserves unresolved template text separately from observed URL");
    check(association.templateOriginalId().equals("source-dynamic")&&association.observedOriginalId().equals("packet-actual"),"source and actual response originals are independently identified");
    check(association.templateAccount().equals("Alice")&&association.observedAccount().equals("Bob")&&association.note().contains("동일 계정·인증·권한"),"path similarity cannot prove the same account or authorization context");
    check(result.stream().anyMatch(c->c.observedAccount().equals("browser-session")&&c.observedOriginalId().isBlank()&&c.observedState().equals("HTTP_RESPONDED")),"browser metadata remains a separate actor without invented body original");
    check(result.stream().allMatch(c->c.note().contains("PATH_SHAPE_ONLY")&&c.note().contains("추가 요청 없음")&&c.note().contains("평가·복원하지 않음")),"correlation explicitly records shape-only nonexecution and unresolved variables");
    check(!dynamic.retryable()&&dynamic.state().equals("EXCLUDED"),"correlating an observed path never promotes a template to executable or resolved");
    var minimal=template("minimal","/api/{id}","GET");
    check(ScopeInspectionCoverage.correlations(snapshot(List.of(minimal),List.of(observed("health","https://scope.test/api/health","GET","Bob","ANALYZED",200,false)))).size()==1,"a literal API namespace with one bounded placeholder is retained as a shape candidate");
    var hostOnly=template("host-only","${this.hostServer}/rest/languages","GET");
    var language=observed("languages","https://scope.test/rest/languages","GET","Bob","ANALYZED",200,false);
    check(ScopeInspectionCoverage.correlations(snapshot(List.of(hostOnly),List.of(language))).size()==1,"a known host-only template associates with an observed two-segment literal API path without resolving its host expression");
    check(ScopeInspectionCoverage.correlations(snapshot(List.of(template("host-prefix-only","${host}/api","GET")),List.of(observed("api-root","https://scope.test/api","GET","Bob","ANALYZED",200,false)))).isEmpty(),"a host-only namespace without a function path remains too broad to associate");
    List<ScopeInspectionCoverage.Row> rejected=List.of(
        observed("cross-origin","https://other.test/api/users/42","GET","Bob","ANALYZED",200,false),
        observed("query","https://scope.test/api/users/42","GET","Bob","ANALYZED",200,true),
        observed("raw-query","https://scope.test/api/users/42?role=admin","GET","Bob","ANALYZED",200,false),
        observed("fragment","https://scope.test/api/users/42#panel","GET","Bob","ANALYZED",200,false),
        observed("post","https://scope.test/api/users/42","POST","Bob","ANALYZED",200,false),
        observed("multi-segment","https://scope.test/api/users/42/orders","GET","Bob","ANALYZED",200,false),
        observed("status-zero","https://scope.test/api/users/42","GET","Bob","FAILED",0,false),
        observed("stale","https://scope.test/api/users/42","GET","Bob","STALE",200,false));
    check(ScopeInspectionCoverage.correlations(snapshot(List.of(dynamic),rejected)).isEmpty(),"cross-origin, query, fragment, POST, extra path segments, status0 and stale-revision observations cannot be associated");
    var pending=new ScopeInspectionCoverage.Row("pending",actual.url(),"GET","Bob","r2","request","","REQUESTING","",0,"",false,List.of("DISCOVERED","SCOPE_CHECKED","REQUESTED"));
    check(ScopeInspectionCoverage.correlations(snapshot(List.of(dynamic),List.of(pending))).isEmpty(),"a request event without a response cannot establish a template response correlation");
    check(ScopeInspectionCoverage.correlations(snapshot(List.of(template("unknown-method",dynamic.url(),"?")),List.of(actual))).isEmpty(),"unknown template method is never inferred as GET from a similar path");
    var withoutSource=new ScopeInspection.Snapshot("source-missing",ScopeInspection.State.COMPLETED,"",0,0,0,0,0,0,List.of(),List.of(),List.of(),0,0,List.of(dynamic,actual));
    check(ScopeInspectionCoverage.correlations(withoutSource).isEmpty(),"an ungrounded template without an original-linked source finding cannot be associated");
    for(String unsupported:List.of("${path}","/${path}","/api/${a}/${b}","/api/users/*","/api/users/${getId()}","${host}/api/users/${id}?role=${role}","/api/users/{id}#panel","https://other.test/api/users/{id}"))
      check(ScopeInspectionCoverage.correlations(snapshot(List.of(template("unsupported",unsupported,"GET")),List.of(actual))).isEmpty(),"broad, expression, query, fragment or external-host templates remain unassociated");
    check(ScopeInspectionCoverage.correlations(snapshot(List.of(template("post-template",dynamic.url(),"POST")),List.of(actual))).isEmpty(),"POST templates are not converted into safe GET correlations");
    check(ScopeInspectionCoverage.correlations(snapshot(List.of(template("change-route","/api/admin/{action}","GET")),List.of(observed("delete-action","https://scope.test/api/admin/deleteUser","GET","Bob","ANALYZED",200,false)))).isEmpty(),"a destructive-looking GET route cannot become a safe association candidate");
    var denied=observed("denied",actual.url(),"GET","Bob","DENIED",403,false);
    var deniedAssociation=ScopeInspectionCoverage.correlations(snapshot(List.of(dynamic),List.of(denied))).get(0);
    check(deniedAssociation.status()==403&&deniedAssociation.observedState().equals("DENIED"),"an actual denied response may be linked only with its unresolved denial state intact");
    var manyTemplates=new ArrayList<ScopeInspectionCoverage.Row>();var manyActual=new ArrayList<ScopeInspectionCoverage.Row>();
    for(int i=0;i<110;i++)manyTemplates.add(template("template-"+i,"${host"+i+"}/api/users/${id}","GET"));
    for(int i=0;i<20;i++)manyActual.add(observed("actual-"+i,"https://scope.test/api/users/"+i,"GET","Bob","ANALYZED",200,false));
    check(ScopeInspectionCoverage.correlations(snapshot(manyTemplates,manyActual)).size()==ScopeInspectionCoverage.MAX_CORRELATIONS,"template association output has a fixed bound rather than unbounded fanout");

    try(var fixture=new ScopeInspectionFixture()){
      AtomicInteger maps=new AtomicInteger();
      fixture.server.createContext("/app/explicit-map-root.js",exchange->{
        fixture.visits.add(new ScopeInspectionFixture.Visit(exchange.getRequestMethod(),"/app/explicit-map-root.js","anonymous"));
        exchange.getResponseHeaders().set("Server","nginx/1.25");exchange.getResponseHeaders().set("X-Powered-By","Apache Tomcat");
        ScopeInspectionRunnerTest.reply(exchange,200,"application/javascript","const harmless=1;\n//# sourceMappingURL=/app/observed-original.js.map");
      });
      fixture.server.createContext("/app/observed-original.js.map",exchange->{
        maps.incrementAndGet();fixture.visits.add(new ScopeInspectionFixture.Visit(exchange.getRequestMethod(),"/app/observed-original.js.map","anonymous"));
        ScopeInspectionRunnerTest.reply(exchange,200,"application/json","{\"version\":3,\"sources\":[\"original.js\"],\"names\":[],\"mappings\":\"\"}");
      });
      var allowed=(java.util.function.Predicate<String>)url->url.startsWith(fixture.origin+"/app/");
      try(var runner=new ScopeInspectionRunner(fixture.api,ScopeInspectionRunnerTest.options(fixture,"/app/explicit-map-root.js",80,1,false,ScopeInspectionRunnerTest.anonymous()),allowed,s->{})){
        runner.start();ScopeInspectionRunnerTest.await(()->ScopeInspectionRunnerTest.terminal(runner),"explicit map observed priority");
        check(maps.get()==1&&runner.snapshot().pages().stream().anyMatch(p->p.url().endsWith("/app/observed-original.js.map")),"an explicitly observed source map bypasses the guessed-environment path cap and receives actual bounded HTTP");
        check(runner.snapshot().findings().stream().anyMatch(f->f.detail().contains("환경 추정 경로 자동 확인 한도")),"the source-map fixture also exercises exhaustion of the independent guessed-environment allowance");
        check(fixture.visits.stream().noneMatch(v->v.path().equals("/app/explicit-map-root.js.map")),"source-map discovery follows the exact observed reference without guessing names");
      }
    }
    System.out.println("ScopeInspectionCorrelationTest: "+checks+" checks passed (bounded shape associations and actual observed-map HTTP)");
  }
}
