package kr.workbench;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public final class AutoLlmEngineTest {
  static int checks;
  static void ok(boolean condition,String why){if(!condition)throw new AssertionError(why);checks++;}
  static AutoLlmEngine.Config cfg(int requests,int models){return new AutoLlmEngine.Config("P","https://example.test",Set.of("/profile"),requests,models,1000,3000);}
  static AutoLlmEngine.Source src(String id){return source(id,"GET","https://example.test/profile?id="+id,"PROXY",100);}
  static AutoLlmEngine.Source source(String id,String method,String url,String tool,int size){return new AutoLlmEngine.Source(id,method,url,tool,size,()->new AutoLlmEngine.Capture(id,"redacted "+id));}
  static AutoLlmEngine.Plan plan(boolean risk){return new AutoLlmEngine.Plan("COMPARE_ACCOUNTS",risk,"테스트 근거");}
  static void await(BooleanSupplier done)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!done.getAsBoolean()){if(System.nanoTime()>until)throw new AssertionError("timed out");Thread.sleep(10);}}
  static class Host implements AutoLlmEngine.Host {
    AtomicInteger sent=new AtomicInteger();List<Integer> accounts=Collections.synchronizedList(new ArrayList<>());List<Long> times=Collections.synchronizedList(new ArrayList<>());volatile boolean scope=true;volatile int status=200;volatile long delay=20;
    public void validate(AutoLlmEngine.Config c,AutoLlmEngine.Source s){if(!scope)throw new IllegalArgumentException("out of scope");}
    public AutoLlmEngine.Reply send(AutoLlmEngine.Capture c,int account){sent.incrementAndGet();accounts.add(account);times.add(System.nanoTime());return new AutoLlmEngine.Reply(status,"private body",delay,"packet");}
  }
  public static void main(String[] args)throws Exception{
    for(String invalid:List.of("{}","{\"action\":\"COMPARE_ACCOUNTS\",\"availabilityRisk\":false,\"reason\":\"x\",\"url\":\"https://evil.test\"}","{\"action\":\"SHELL\",\"availabilityRisk\":false,\"reason\":\"x\"}","```json\n{}\n```","{\"action\":\"COMPARE_ACCOUNTS\",\"availabilityRisk\":\"false\",\"reason\":\"x\"}")){
      try{AutoLlmEngine.Plan.parse(invalid);throw new AssertionError("unsafe plan");}catch(IllegalArgumentException e){checks++;}}
    ok(AutoLlmEngine.Plan.parse("{\"action\":\"REVIEW\",\"availabilityRisk\":true,\"reason\":\"unknown\"}").availabilityRisk(),"strict risk accepted");
    var c=cfg(6,2);
    ok(AutoLlmEngine.admitted(c,src("1")),"allowed source");
    for(var s:List.of(source("x","GET","https://evil.test/profile","PROXY",100),source("x","GET","https://example.test/profile","EXTENSIONS",100),source("x","GET","https://example.test/profile/child","PROXY",100),source("x","GET","https://example.test/profile","PROXY",131073)))ok(!AutoLlmEngine.admitted(c,s),"admission block");
    for(var s:List.of(source("x","POST","https://example.test/profile","PROXY",100),source("x","GET","https://example.test/profile?action=delete","PROXY",100),source("x","GET","https://example.test/profile?token=secret","PROXY",100)))ok(!AutoLlmEngine.readOnly(s),"unsafe request block");
    try{new AutoLlmEngine.Config("P","https://example.test",Set.of("/*"),3,1,1000,3000);throw new AssertionError();}catch(IllegalArgumentException e){checks++;}
    Host h=new Host();try(var e=new AutoLlmEngine(h,(s,k)->plan(false),s->{})){
      e.start(cfg(3,5));e.offer(src("1"));e.offer(src("1"));await(()->e.snapshot().results().size()==1);
      ok(h.accounts.equals(List.of(0,1,0)),"exact A B A recipe");ok(e.snapshot().models()==1,"duplicate does not infer again");
      ok(h.times.get(1)-h.times.get(0)>=TimeUnit.MILLISECONDS.toNanos(990),"rate spacing");
      ok(e.snapshot().results().get(0).note().contains("후보"),"not automatically confirmed");
      e.offer(src("2"));await(()->!e.snapshot().busy());ok(h.sent.get()==3,"global budget including controls");
    }
    h=new Host();Host riskHost=h;try(var e=new AutoLlmEngine(h,(s,k)->plan(true),s->{})){
      e.start(cfg(3,1));e.offer(src("1"));await(()->e.snapshot().results().size()==1);
      var r=e.snapshot().results().get(0);ok(r.state().equals("사용자 확인 대기")&&h.sent.get()==0,"availability requires consent");
      ok(!e.approve("wrong-id"),"approval bound to plan");ok(e.approve(r.id()),"one explicit approval");ok(!e.approve(r.id()),"approval not reusable");
      await(()->riskHost.sent.get()==3);e.stop("done");await(()->!e.snapshot().busy());
    }
    for(int code:new int[]{429,500,503}){Host bad=new Host();bad.status=code;try(var e=new AutoLlmEngine(bad,(s,k)->plan(false),s->{})){e.start(c);e.offer(src("1"));await(()->!e.snapshot().busy());ok(bad.sent.get()==1,"availability stop "+code);ok(e.snapshot().results().get(0).state().equals("가용성 중지"),"stop evidence retained");}}
    Host slow=new Host();slow.delay=3000;try(var e=new AutoLlmEngine(slow,(s,k)->plan(false),s->{})){e.start(c);e.offer(src("1"));await(()->!e.snapshot().busy());ok(slow.sent.get()==1,"slow circuit breaker");}
    Host changed=new Host();try(var e=new AutoLlmEngine(changed,(s,k)->{changed.scope=false;return plan(false);},s->{})){e.start(c);e.offer(src("1"));await(()->!e.snapshot().busy());ok(changed.sent.get()==0,"scope changed during inference");}
    Host stopped=new Host();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    try(var e=new AutoLlmEngine(stopped,(s,k)->{entered.countDown();release.await(3,TimeUnit.SECONDS);return plan(false);},s->{})){
      e.start(c);e.offer(src("1"));ok(entered.await(2,TimeUnit.SECONDS),"inference started");e.stop("user");release.countDown();await(()->!e.snapshot().busy());ok(stopped.sent.get()==0,"stop while model pending sends nothing");
    }
    Host review=new Host();try(var e=new AutoLlmEngine(review,(s,k)->new AutoLlmEngine.Plan("REVIEW",false,"public"),s->{})){
      e.start(c);e.offer(src("1"));await(()->!e.snapshot().results().isEmpty());ok(review.sent.get()==0,"review creates no traffic");e.reset();await(()->!e.snapshot().busy());ok(e.snapshot().results().isEmpty(),"project reset clears originals");
    }
    Host expiring=new Host();AtomicLong clock=new AtomicLong(1000);
    try(var e=new AutoLlmEngine(expiring,(input,k)->plan(true),x->{},clock::get)){
      e.start(c);e.offer(src("expiring"));await(()->!e.snapshot().results().isEmpty());String id=e.snapshot().results().get(0).id();clock.addAndGet(180001);
      ok(!e.approve(id)&&expiring.sent.get()==0,"expired approval cannot send");e.stop("stop");ok(!e.approve(id),"stop revokes approvals");
    }
    Host exhausted=new Host();try(var e=new AutoLlmEngine(exhausted,(input,k)->new AutoLlmEngine.Plan("REVIEW",false,"review"),x->{})){
      e.start(cfg(3,1));e.offer(src("one"));await(()->!e.snapshot().results().isEmpty());e.offer(src("two"));await(()->!e.snapshot().busy());ok(e.snapshot().models()==1&&exhausted.sent.get()==0,"model budget is independent of target budget");
    }
    Host failure=new Host();try(var e=new AutoLlmEngine(failure,(input,k)->{throw new IllegalArgumentException("malformed plan");},x->{})){
      e.start(c);e.offer(src("1"));await(()->!e.snapshot().busy());ok(failure.sent.get()==0,"malformed model plan fails closed");
    }
    List<AutoLlmEngine.Reply> unstable=List.of(new AutoLlmEngine.Reply(200,"a",1,null),new AutoLlmEngine.Reply(200,"a",1,null),new AutoLlmEngine.Reply(200,"b",1,null));ok(AutoLlmEngine.verdict(unstable).startsWith("판단 불가"),"control response mismatch");
    System.out.println(checks+" checks passed");
  }
}
