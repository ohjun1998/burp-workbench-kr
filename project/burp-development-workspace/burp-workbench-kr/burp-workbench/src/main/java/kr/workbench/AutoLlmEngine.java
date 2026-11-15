package kr.workbench;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Bounded coordinator. Models describe plans; only the fixed three-request recipe can send. */
final class AutoLlmEngine implements AutoCloseable {
  record Config(String project, String origin, Set<String> paths, int requests, int models, int intervalMs, int slowMs) {
    Config {
      URI target=URI.create(origin);
      if(target.getQuery()!=null || !(target.getPath().isEmpty()||target.getPath().equals("/")))throw new IllegalArgumentException("Origin에는 경로·쿼리를 넣지 마세요.");
      origin = AutoLlmEngine.origin(origin); paths = Set.copyOf(paths);
      if (paths.isEmpty() || paths.size()>50 || paths.stream().anyMatch(p -> !p.startsWith("/") || p.contains("?") || p.contains("#") || p.contains("*") || p.length()>512 || !URI.create(p).normalize().getRawPath().equals(p)))
        throw new IllegalArgumentException("조회 경로를 정확히 지정하세요. 와일드카드·쿼리는 허용하지 않습니다.");
      if (requests<3 || requests>300 || models<1 || models>100 || intervalMs<1000 || intervalMs>60000 || slowMs<1000 || slowMs>10000)
        throw new IllegalArgumentException("요청 3~300, 모델 1~100, 간격 1~60초, 지연 중지 1~10초 범위입니다.");
    }
  }
  record Source(String id, String method, String url, String tool, int bytes, Supplier<Capture> restore) {}
  record Capture(Object original, String preview) {}
  record Reply(int status, String body, long millis, Object packet) {}
  record Plan(String action, boolean availabilityRisk, String reason) {
    static Plan parse(String text) {
      Object value=MinerJson.parse(text.strip(),8000,64);
      if (!(value instanceof Map<?,?> m) || !m.keySet().equals(Set.of("action","availabilityRisk","reason"))
          || !(m.get("action") instanceof String a) || !Set.of("COMPARE_ACCOUNTS","REVIEW").contains(a)
          || !(m.get("availabilityRisk") instanceof Boolean risk) || !(m.get("reason") instanceof String reason)
          || reason.isBlank() || reason.length()>1500) throw new IllegalArgumentException("모델 검사안 형식 오류 · 전송 없음");
      return new Plan(a,risk,reason);
    }
  }
  record Result(String id, Source source, String state, String note, Plan plan, Capture capture, List<Reply> replies, long expiresAt) {
    Result { replies=List.copyOf(replies); }
  }
  record Snapshot(boolean running, boolean busy, int requests, int models, int queued, int excluded, String status, List<Result> results) {}
  interface Host {
    void validate(Config config, Source source);
    Reply send(Capture original, int account) throws Exception;
  }
  interface Planner { Plan plan(Capture input, LocalLlmClient.Call call) throws Exception; }
  private record Work(Source source, Result approval) {}
  private final LongSupplier clock;
  private final Host host;
  private final Planner planner;
  private final Consumer<Snapshot> changed;
  private final ArrayBlockingQueue<Work> queue=new ArrayBlockingQueue<>(32);
  private final LinkedHashMap<String,Result> results=new LinkedHashMap<>();
  private final Set<String> seen=new HashSet<>();
  private Config config;
  private boolean running, busy, closed;
  private long generation, lastSent;
  private int sent, models, excluded;
  private String status="중지됨";
  private LocalLlmClient.Call call;
  AutoLlmEngine(Host host, Planner planner, Consumer<Snapshot> changed) { this(host,planner,changed,System::currentTimeMillis); }
  AutoLlmEngine(Host host,Planner planner,Consumer<Snapshot> changed,LongSupplier clock) {this.host=host;this.planner=planner;this.changed=changed;this.clock=clock;}
  static String origin(String value) {
    URI u=URI.create(value);String h=u.getHost();
    if (!Set.of("http","https").contains(u.getScheme()) || h==null || u.getUserInfo()!=null || u.getFragment()!=null || u.getPort()==0 || u.getPort()>65535)
      throw new IllegalArgumentException("HTTP(S) 대상 Origin을 확인하세요.");
    return u.getScheme()+"://"+h.toLowerCase(Locale.ROOT)+":"+(u.getPort()<0?(u.getScheme().equals("https")?443:80):u.getPort());
  }
  static boolean admitted(Config c, Source s) {
    try { return s!=null && Set.of("PROXY","REPEATER").contains(s.tool().toUpperCase(Locale.ROOT))
        && s.bytes()>0 && s.bytes()<=128*1024 && s.url().length()<=4096
        && origin(s.url()).equals(c.origin()) && c.paths().contains(URI.create(s.url()).getRawPath()); }
    catch(RuntimeException e) {return false;}
  }
  static boolean readOnly(Source s) {
    URI u=URI.create(s.url());
    // Explicit allowlist is still required: GET itself is not a read-only guarantee.
    String target=Objects.toString(u.getPath(),"")+"?"+Objects.toString(u.getQuery(),"");
    return s.method().equals("GET") && !target.toLowerCase(Locale.ROOT).matches(".*(?:logout|delete|remove|reset|purchase|transfer|withdraw|checkout|sendmail|export|download|report|sleep|benchmark|stress|token|password|session|api[_-]?key|authorization).*" );
  }
  synchronized void start(Config c) {
    if(closed || busy)throw new IllegalStateException("진행 중 요청이 끝난 뒤 시작하세요.");
    config=c; generation++;running=true;busy=true;sent=0;models=0;excluded=0;lastSent=0;
    queue.clear();results.clear();seen.clear();status="신규 Proxy·Repeater 응답 대기";
    long run=generation;Thread worker=new Thread(()->work(run),"wb-auto-llm");worker.setDaemon(true);worker.start();publish();
  }
  synchronized void offer(Source s) {
    if(!running)return;
    if(!admitted(config,s)){excluded++;return;}
    String key=s.method()+" "+s.url();
    if(seen.contains(key))return;
    if(seen.size()>=128 || !queue.offer(new Work(s,null))){excluded++;return;}
    seen.add(key);
  }
  synchronized Snapshot snapshot() { return new Snapshot(running,busy,sent,models,queue.size(),excluded,status,List.copyOf(results.values())); }
  private synchronized void publish(){changed.accept(snapshot());}
  synchronized void stop(String reason) {
    running=false;queue.clear();status=reason;
    if(call!=null)call.cancel();
    results.replaceAll((id,r)->r.state().equals("사용자 확인 대기")||r.state().equals("승인됨")?
        new Result(r.id(),r.source(),"승인 취소",reason,r.plan(),r.capture(),r.replies(),0):r);
    publish();
  }
  synchronized void reset(){stop("프로젝트 변경 · 자료 삭제");generation++;results.clear();seen.clear();publish();}
  synchronized boolean approve(String id) {
    Result r=results.get(id);
    if(!running || r==null || !r.state().equals("사용자 확인 대기") || clock.getAsLong()>r.expiresAt())return false;
    try{host.validate(config,r.source());}catch(RuntimeException ex){stop("현재 SCOPE/계정/프로젝트 확인 필요");return false;}
    if(!readOnly(r.source()) || sent+3>config.requests() || !queue.offer(new Work(r.source(),r)))return false;
    results.put(id,new Result(id,r.source(),"승인됨","이번 고정 검사 1회만 승인",r.plan(),r.capture(),List.of(),r.expiresAt()));publish();return true;
  }
  private void check(long run, Source s) {
    synchronized(this){if(!running || run!=generation || closed)throw new CancellationException();}
    host.validate(config,s);
  }
  private void work(long run) {
    try {
      while(true) {
        synchronized(this){if(!running || run!=generation)return;}
        Work w=queue.poll(250,TimeUnit.MILLISECONDS); if(w==null)continue;
        Source s=w.source();String id=w.approval()==null?UUID.randomUUID().toString():w.approval().id();
        Capture captured=null;Plan plan=null;List<Reply> replies=new ArrayList<>();
        try {
          check(run,s);
          if(!readOnly(s)) { put(run,new Result(id,s,"수동 검사 필요","상태 변경·고비용·인증 쿼리·비조회 요청은 자동 실행하지 않습니다. 개별 재현안을 검토하세요.",null,null,List.of(),0));continue; }
          captured=s.restore().get();check(run,s);
          if(w.approval()==null) {
            synchronized(this){if(models>=config.models()){stop("모델 분석 예산 소진");continue;}models++;call=new LocalLlmClient.Call();status="모델 분석 중";publish();}
            try {plan=planner.plan(captured,call);} finally {synchronized(this){call=null;}}
            check(run,s);
            if(plan.action().equals("REVIEW")){put(run,new Result(id,s,"추가 검토",plan.reason(),plan,captured,List.of(),0));continue;}
            if(plan.availabilityRisk()) {
              put(run,new Result(id,s,"사용자 확인 대기",plan.reason(),plan,captured,List.of(),clock.getAsLong()+180000));continue;
            }
          } else {
            plan=w.approval().plan();
            if(clock.getAsLong()>w.approval().expiresAt()) {put(run,new Result(id,s,"승인 만료","3분 승인 유효기간 만료 · 새 실행 필요",plan,captured,List.of(),0));continue;}
          }
          synchronized(this){if(sent+3>config.requests()){stop("대상 요청 예산 소진 · 비교는 3건 필요");continue;}status="계정 A → B → A 재현 중";publish();}
          for(int account:new int[]{0,1,0}) {
            long wait; synchronized(this){wait=config.intervalMs()-(System.nanoTime()-lastSent)/1000000;}
            while(wait>0){check(run,s);Thread.sleep(Math.min(wait,100));synchronized(this){wait=config.intervalMs()-(System.nanoTime()-lastSent)/1000000;}}
            check(run,s);
            synchronized(this){if(!running || run!=generation)throw new CancellationException();sent++;lastSent=System.nanoTime();publish();}
            Reply response=host.send(captured,account);replies.add(response);
            check(run,s);
            if(response.status()==429 || response.status()>=500 || response.millis()>=config.slowMs()) {
              put(run,new Result(id,s,"가용성 중지","오류·429 또는 지연 한도 도달 · 후속 요청 중단",plan,captured,replies,0));stop("가용성 보호 중지 · 확인 후 새 실행 필요");break;
            }
          }
          if(replies.size()==3 && snapshot().running())put(run,new Result(id,s,"검사 완료",verdict(replies),plan,captured,replies,0));
        } catch(CancellationException e) {put(run,new Result(id,s,"중지됨","중지/프로젝트 변경 · 이미 시작한 요청은 반환까지 대기",plan,captured,replies,0));}
          catch(Exception e) {put(run,new Result(id,s,"오류·중지","검사 실패 · "+e.getClass().getSimpleName()+" · 자동 재시도 없음",plan,captured,replies,0));stop("모델/전송/범위 오류 · 확인 후 새 실행 필요");}
      }
    } catch(InterruptedException e){Thread.currentThread().interrupt();}
    finally{synchronized(this){busy=false;call=null;publish();}}
  }
  static String verdict(List<Reply> r) {
    Reply a=r.get(0),b=r.get(1),control=r.get(2);
    if(a.status()!=200 || control.status()!=200 || a.body().isBlank() || !a.body().equals(control.body()))return "판단 불가 · A 기준 응답이 성공·비어 있지 않음·재현 안정성 조건을 충족하지 않음";
    if(b.status()==401 || b.status()==403)return "B 차단 응답 재현 · 해당 요청에서 접근 성공 미관측";
    if(b.status()==200 && b.body().equals(a.body()))return "동일 데이터 반환 재현 · 인가 취약점 후보, 기대 권한·데이터 소유 확인 필요";
    return "계정별 응답 차이 관측 · 권한 정책과 반환 데이터 추가 검토";
  }
  private synchronized void put(long run, Result r){if(run==generation){if(running)status=r.state().equals("사용자 확인 대기")?"신규 응답 대기 · 가용성 우려 항목은 개별 승인 필요":"신규 응답 대기";results.put(r.id(),r);while(results.size()>100)results.remove(results.keySet().iterator().next());publish();}}
  public synchronized void close(){closed=true;reset();}
}
