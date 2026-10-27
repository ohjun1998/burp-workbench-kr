package kr.workbench;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.Pattern;

/** Pinned standard Katana process. Every target request must pass the guarded broker proxy. */
final class ScopeKatana implements AutoCloseable {
  record Config(String executable,int seconds,int maxPages,int depth,int timeoutMillis,
                int maxBodyBytes,int delayMillis) {
    Config {
      if(executable==null||executable.isBlank()||executable.length()>4096||executable.chars().anyMatch(c->c<32)
          ||seconds<1||seconds>900||maxPages<1||maxPages>500||depth<0||depth>5
          ||timeoutMillis<1000||timeoutMillis>30000||maxBodyBytes<1024||maxBodyBytes>5*1024*1024
          ||delayMillis<0||delayMillis>10000)throw new IllegalArgumentException("Katana 실행 설정 범위를 확인하세요.");
      executable=executable.strip();
    }
    @Override public String toString(){return "ScopeKatana.Config[executable=선택한 로컬 파일, seconds="+seconds+", maxPages="+maxPages+"]";}
  }
  record Snapshot(String state,String message,int records,int endpoints,int excluded,long startedAt,long finishedAt) {}
  private static final int LINE_LIMIT=256*1024, OUTPUT_LIMIT=8*1024*1024, STDERR_LIMIT=64*1024, RECORD_LIMIT=5000, ENDPOINT_LIMIT=2000;
  private static final Pattern VERSION=Pattern.compile("(?<![A-Za-z0-9.])v?1\\.8\\.0(?![A-Za-z0-9._+-])");
  private final Object lock=new Object();
  private final Config config;private final String startUrl,origin;
  private final Predicate<String> liveAllowed;private final ScopeKatanaProxy.Fetcher fetcher;
  private final Consumer<ScopeInspection.Candidate> candidate;private final Consumer<ScopeKatanaProxy.Blocked> blocked;
  private final BiConsumer<Boolean,String> finished;private final Consumer<Snapshot> changed;
  private final ExecutorService controller=Executors.newSingleThreadExecutor(daemon("workbench-katana"));
  private final Set<String> endpoints=new LinkedHashSet<>(),fetched=new HashSet<>();
  private String state="READY",message="Katana v1.8.0 실행 대기";
  private int records,excluded;private long startedAt,finishedAt,deadline,generation;
  private boolean completedCallback,closed;private Process process;private ScopeKatanaProxy proxy;private Future<?> job;

  ScopeKatana(Config config,String startUrl,List<String> includes,List<String> excludes,
              Predicate<String> liveAllowed,ScopeKatanaProxy.Fetcher fetcher,
              Consumer<ScopeInspection.Candidate> candidate,Consumer<ScopeKatanaProxy.Blocked> blocked,
              BiConsumer<Boolean,String> finished,Consumer<Snapshot> changed) {
    this.config=Objects.requireNonNull(config);URI start=ScopeInspectionBrowser.valid(startUrl);
    if(start.getRawFragment()!=null)throw new IllegalArgumentException("Katana 표준 크롤링은 HTTP(S) URL을 사용하세요.");
    this.startUrl=start.toASCIIString();this.origin=ScopeInspectionBrowser.origin(start);
    Objects.requireNonNull(includes);Objects.requireNonNull(excludes);
    this.liveAllowed=Objects.requireNonNull(liveAllowed);this.fetcher=Objects.requireNonNull(fetcher);
    this.candidate=Objects.requireNonNull(candidate);this.blocked=Objects.requireNonNull(blocked);
    this.finished=Objects.requireNonNull(finished);this.changed=Objects.requireNonNull(changed);
    if(!allowed(this.startUrl))throw new IllegalArgumentException("Katana 시작 URL이 현재 Scope 밖입니다.");
  }
  void start(){synchronized(lock){if(closed)throw new IllegalStateException("Katana 연결 종료됨");if(!state.equals("READY"))return;startedAt=System.currentTimeMillis();deadline=startedAt+config.seconds()*1000L;launchLocked();}publish();}
  void resume(){boolean expired=false;synchronized(lock){if(closed||!state.equals("PAUSED"))return;if(System.currentTimeMillis()>=deadline||fetched.size()>=config.maxPages())expired=true;else launchLocked();}if(expired)terminalPaused();else publish();}
  private void launchLocked(){state="RUNNING";message="현재 Scope와 선택 계정으로 Katana 크롤링 준비";long epoch=++generation;job=controller.submit(()->run(epoch));}
  void pause(){Resources resources;synchronized(lock){if(closed||!state.equals("RUNNING"))return;state="PAUSED";message="Katana 일시정지 · 재개 시 같은 절대 시간/공통 예산 적용";generation++;resources=resourcesLocked();}dispose(resources);publish();}
  void stop(){terminate("STOPPED","Katana 크롤링 중지 · 확인한 결과만 유지",false);}
  @Override public void close(){synchronized(lock){if(closed)return;closed=true;}terminate("CLOSED","Katana 연결 종료",false);controller.shutdownNow();}
  Snapshot snapshot(){synchronized(lock){return new Snapshot(state,message,records,endpoints.size(),excluded,startedAt,finishedAt);}}

  private void run(long epoch) {
    Path work=null;Process child=null;ScopeKatanaProxy bridge=null;ExecutorService readers=null;
    Capture stdout=new Capture(),stderr=new Capture();int responses=0;
    try {
      if(!current(epoch))return;
      probe(config.executable());
      if(!current(epoch))return;
      if(System.currentTimeMillis()>=deadline){complete(epoch,false,"PARTIAL","Katana 준비 중 시간 한도 · 대상 요청을 시작하지 않았습니다.");return;}
      work=workspace();Path cfg=work.resolve("config.yaml"),fields=work.resolve("fields.yaml");
      writePrivate(cfg,"{}\n");writePrivate(fields,"[]\n");
      final long owner=epoch;final int[] seenResponses={0};
      bridge=new ScopeKatanaProxy(origin,url->current(owner)&&allowed(url),(url,method)->{
        synchronized(lock){if(!currentLocked(owner)||System.currentTimeMillis()>=deadline)throw new IOException("Katana 실행 중지 또는 시간 한도");if(!Set.of("GET","HEAD").contains(method))throw new IOException("Katana 조회 전용 정책");if(!fetched.contains(method+" "+url)&&fetched.size()>=config.maxPages())throw new IOException("Katana 페이지 한도");}
        if(!allowed(url))throw new IOException("Katana 현재 Scope 제외");
        ScopeKatanaProxy.Response response=fetcher.fetch(url,method);
        synchronized(lock){if(!currentLocked(owner))throw new IOException("Katana 실행 중지");fetched.add(method+" "+url);seenResponses[0]++;}
        return response;
      },value->onBlocked(owner,value));
      int seconds,remaining;synchronized(lock){if(!currentLocked(epoch))return;proxy=bridge;seconds=(int)Math.max(1,(deadline-System.currentTimeMillis()+999)/1000);remaining=Math.max(1,config.maxPages()-fetched.size());}
      ProcessBuilder builder=new ProcessBuilder(command(config,startUrl,origin,bridge.address(),cfg,fields,seconds,remaining));
      configure(builder,work);child=builder.start();
      synchronized(lock){if(!currentLocked(epoch)){forceKill(child);return;}process=child;message="Katana 표준 크롤링 실행 중 · 전송은 Scope 조회 전용 프록시 경유";}
      try(OutputStream input=child.getOutputStream()){input.write((startUrl+"\n").getBytes(StandardCharsets.UTF_8));input.flush();}publish();
      readers=Executors.newFixedThreadPool(2,daemon("workbench-katana-stream"));
      Process running=child;
      Future<?> out=readers.submit(()->read(running.getInputStream(),stdout,OUTPUT_LIMIT,LINE_LIMIT,line->record(owner,line)));
      Future<?> err=readers.submit(()->read(running.getErrorStream(),stderr,STDERR_LIMIT,8192,line->{}));
      boolean timed=false;
      while(!child.waitFor(100,TimeUnit.MILLISECONDS)){
        if(!current(epoch))return;
        if(System.currentTimeMillis()>=deadline){timed=true;break;}
        if(stdout.limit||stderr.limit||stdout.invalid){break;}
      }
      if(timed||stdout.limit||stderr.limit||stdout.invalid) {bridge.close();forceKill(child);}
      awaitReader(out);awaitReader(err);
      synchronized(lock){responses=seenResponses[0];}
      bridge.close();forceKill(child);
      if(!current(epoch))return;
      boolean pages;int lines;synchronized(lock){pages=fetched.size()>=config.maxPages();lines=records;}
      if(stdout.invalid)complete(epoch,false,"FAILED","Katana 출력 형식 확인 실패 · 원문 출력은 보관하지 않았습니다.");
      else if(stdout.limit||stderr.limit||lines>=RECORD_LIMIT)complete(epoch,false,"PARTIAL","Katana 출력 한도 · 확인한 결과만 유지");
      else if(timed||System.currentTimeMillis()>=deadline||pages)complete(epoch,false,"PARTIAL","Katana 시간/페이지 한도 · 남은 경로는 미확인");
      else if(child.exitValue()!=0||stderr.fatal||responses==0||lines==0)complete(epoch,false,"FAILED","Katana 실행을 확인하지 못했습니다. 버전·연결·Scope와 남은 예산을 확인하세요.");
      else if(stderr.nonblank)complete(epoch,false,"PARTIAL","Katana가 추가 실행 메시지를 반환했습니다. 확인한 응답만 결과에 반영했습니다.");
      else complete(epoch,true,"COMPLETED","Katana 크롤링 완료 · 현재 Scope에서 확인한 응답과 경로 반영");
    } catch(InterruptedException cancelled){Thread.currentThread().interrupt();if(current(epoch))complete(epoch,false,"PARTIAL","Katana 실행 중단 · 확인한 결과만 유지");}
    catch(Exception failure){if(current(epoch))complete(epoch,false,"FAILED","Katana v1.8.0 실행 파일·연결·현재 Scope를 확인하세요.");}
    finally {
      if(bridge!=null)bridge.close();if(child!=null)forceKill(child);if(readers!=null)readers.shutdownNow();deleteWorkspace(work);
      synchronized(lock){if(process==child)process=null;if(proxy==bridge)proxy=null;}
    }
  }

  private void record(long epoch,String line) {
    if(line.isBlank()||!current(epoch))return;
    List<ScopeInspection.Candidate> values=parseCandidates(line,origin,this::allowed);
    synchronized(lock){if(!currentLocked(epoch))return;if(++records>RECORD_LIMIT)throw new OutputLimit();if(values.isEmpty()){excluded++;return;}}
    for(var value:values){synchronized(lock){if(!currentLocked(epoch))return;if(endpoints.size()>=ENDPOINT_LIMIT)throw new OutputLimit();String key=value.source()+" "+value.method()+" "+value.url();if(!endpoints.add(key))continue;}candidate.accept(value);}publish();
  }
  static ScopeInspection.Candidate parseCandidate(String line,String origin,Predicate<String> allowed) {
    List<ScopeInspection.Candidate> values=parseCandidates(line,origin,allowed);return values.isEmpty()?null:values.get(0);
  }
  static List<ScopeInspection.Candidate> parseCandidates(String line,String origin,Predicate<String> allowed) {
    Object parsed=MinerJson.parse(line,LINE_LIMIT,10000);
    if(!(parsed instanceof Map<?,?> result)||!(result.get("request") instanceof Map<?,?> request))throw new IllegalArgumentException("Katana JSONL request 필요");
    String raw=string(request.get("endpoint"),8192);URI url;
    try{url=ScopeInspectionBrowser.valid(raw);if(!ScopeInspectionBrowser.origin(url).equals(origin)||!allowed.test(raw))return List.of();}catch(RuntimeException invalid){return List.of();}
    String method=string(request.get("method"),16).toUpperCase(Locale.ROOT);if(!method.matches("[A-Z]{1,16}"))method="UNKNOWN";
    String clean=safe(raw);if(clean.isEmpty())return List.of();
    String tag=token(request.get("tag")),attribute=token(request.get("attribute"));
    String source=safe(string(request.get("source"),8192));
    String provenance="Katana v1.8.0"+(tag.isEmpty()?"":" · "+tag)+(attribute.isEmpty()?"":"/"+attribute);
    String label=(source.isEmpty()?"Katana가 기록한 경로":"발견 출처: "+source);
    boolean executable=Set.of("GET","HEAD").contains(method)&&url.getRawQuery()==null&&url.getRawFragment()==null&&clean.equals(raw);
    var values=new ArrayList<ScopeInspection.Candidate>();
    values.add(new ScopeInspection.Candidate(clean,method,provenance,label.substring(0,Math.min(160,label.length())),executable));
    if(result.get("response") instanceof Map<?,?> response&&response.get("forms") instanceof List<?> forms){
      for(Object item:forms.stream().limit(100).toList())if(item instanceof Map<?,?> form){
        String action=string(form.get("action"),8192),formMethod=string(form.get("method"),16).toUpperCase(Locale.ROOT);
        if(!Set.of("GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS").contains(formMethod))formMethod="?";
        var names=new LinkedHashSet<String>();
        if(form.get("parameters") instanceof List<?> parameters)for(Object parameter:parameters.stream().limit(16).toList()){String name=metadataName(parameter,true);if(!name.isEmpty())names.add(name);}
        String formLabel="폼 선언 · 입력: "+(names.isEmpty()?"이름 미확인":String.join(", ",names))+" · 제출하지 않음";
        try{String target=url.resolve(action).toASCIIString();URI formUrl=ScopeInspectionBrowser.valid(target);if(!ScopeInspectionBrowser.origin(formUrl).equals(origin)||!allowed.test(target))continue;String display=safe(target);if(!display.isEmpty())values.add(new ScopeInspection.Candidate(display,formMethod,"Katana 폼",formLabel.substring(0,Math.min(160,formLabel.length())),false));}catch(RuntimeException invalid){}
      }
    }
    if(result.get("response") instanceof Map<?,?> response&&response.get("technologies") instanceof List<?> technologies){var names=new LinkedHashSet<String>();for(Object technology:technologies.stream().limit(24).toList()){String name=metadataName(technology,false);if(!name.isEmpty())names.add(name);}if(!names.isEmpty()){String detail="기술 단서 · "+String.join(", ",names);values.add(new ScopeInspection.Candidate(clean,"?","Katana 환경",detail.substring(0,Math.min(160,detail.length())),false));}}
    return List.copyOf(values);
  }
  static List<String> command(Config config,String start,String origin,String proxy,Path cfg,Path fields,int seconds,int pages) {
    // Katana 1.8.0 -ob clears Response.Body before its later JS parser runs.
    // JSON field projection excludes body without mutating the response being crawled.
    var args=new ArrayList<>(List.of(config.executable(),"-proxy",proxy,"-config",cfg.toString(),"-flc",fields.toString(),
        "-duc","-j","-silent","-nc","-jc","-td","-fx","-dr","-retry","0","-c","1","-p","1","-iqp",
        "-ct",Math.max(1,seconds)+"s","-mdp",Integer.toString(Math.max(1,pages)),"-mrs",Integer.toString(config.maxBodyBytes()),
        "-d",Integer.toString(config.depth()),"-timeout",Integer.toString(Math.max(1,(config.timeoutMillis()+999)/1000)),
        "-cs","^"+Pattern.quote(origin)+"(?:/|\\?|$)","-fs","fqdn","-or","-eof","raw,body,headers,custom_fields,knowledgebase,xhr_requests"));
    args.addAll(List.of("-rl",Integer.toString(config.delayMillis()==0?10:Math.max(1,1000/config.delayMillis()))));
    if(config.delayMillis()>=1000)args.addAll(List.of("-rd",Integer.toString((config.delayMillis()+999)/1000)));
    return List.copyOf(args);
  }

  static String probe(String executable) throws IOException {
    if(executable==null||executable.isBlank()||executable.length()>4096||executable.chars().anyMatch(c->c<32))throw new IOException("Katana 실행 파일 경로를 확인하세요.");
    Path work=null;Process child=null;ExecutorService streams=null;
    try {
      work=workspace();Path cfg=work.resolve("config.yaml");writePrivate(cfg,"{}\n");
      ProcessBuilder builder=new ProcessBuilder(executable,"-version","-duc","-nc","-config",cfg.toString());configure(builder,work);
      child=builder.start();child.getOutputStream().close();streams=Executors.newFixedThreadPool(2,daemon("workbench-katana-probe"));
      Process running=child;Future<String> out=streams.submit(()->small(running.getInputStream())),err=streams.submit(()->small(running.getErrorStream()));
      if(!child.waitFor(5,TimeUnit.SECONDS))throw new IOException("Katana 버전 확인 시간 한도");
      String version=out.get(1,TimeUnit.SECONDS)+"\n"+err.get(1,TimeUnit.SECONDS);
      version=version.replaceAll("\\u001B\\[[;\\d]*[ -/]*[@-~]","");
      if(child.exitValue()!=0||!VERSION.matcher(version).find())throw new IOException("공식 Katana v1.8.0 실행 파일이 필요합니다.");
      return "Katana v1.8.0 · 표준 크롤러 연결 확인";
    }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Katana 버전 확인 중단");}
    catch(Exception error){throw new IOException("Katana v1.8.0 실행 파일을 확인할 수 없습니다.");}
    finally{if(child!=null)forceKill(child);if(streams!=null)streams.shutdownNow();deleteWorkspace(work);}
  }

  private void onBlocked(long epoch,ScopeKatanaProxy.Blocked value){
    if(value.url().equals("http://burpsuite/"))return; // Katana's local proxy fingerprint probe; never a target fetch.
    synchronized(lock){if(!currentLocked(epoch))return;excluded++;}
    blocked.accept(new ScopeKatanaProxy.Blocked(safe(value.url()),token(value.method()),mask(value.reason(),180)));publish();
  }
  private boolean allowed(String url){try{return ScopeInspectionBrowser.origin(ScopeInspectionBrowser.valid(url)).equals(origin)&&liveAllowed.test(url);}catch(RuntimeException invalid){return false;}}
  private boolean current(long epoch){synchronized(lock){return currentLocked(epoch);}}
  private boolean currentLocked(long epoch){return !closed&&generation==epoch&&state.equals("RUNNING");}
  private void complete(long epoch,boolean success,String next,String note){boolean notify;synchronized(lock){if(!currentLocked(epoch))return;state=next;message=note;finishedAt=System.currentTimeMillis();notify=!completedCallback;completedCallback=true;}publish();if(notify)finishCallback(success,note);}
  private void terminalPaused(){boolean notify;synchronized(lock){if(closed||!state.equals("PAUSED"))return;state="PARTIAL";message="Katana 재개 한도 · 같은 절대 시간/공통 예산을 유지합니다.";finishedAt=System.currentTimeMillis();notify=!completedCallback;completedCallback=true;}publish();if(notify)finishCallback(false,snapshot().message());}
  private void terminate(String next,String note,boolean success){Resources resources;boolean notify;synchronized(lock){if(state.equals("CLOSED")||completedCallback&&next.equals("STOPPED"))return;generation++;state=next;message=note;finishedAt=System.currentTimeMillis();notify=!completedCallback;completedCallback=true;resources=resourcesLocked();}dispose(resources);publish();if(notify)finishCallback(success,note);}
  private Resources resourcesLocked(){return new Resources(proxy,process,job);}
  private record Resources(ScopeKatanaProxy proxy,Process process,Future<?> job){}
  private static void dispose(Resources resources){if(resources.proxy()!=null)resources.proxy().close();if(resources.process()!=null)forceKill(resources.process());if(resources.job()!=null)resources.job().cancel(true);}
  private void publish(){try{changed.accept(snapshot());}catch(RuntimeException ignored){}}
  private void finishCallback(boolean success,String note){try{finished.accept(success,note);}catch(RuntimeException ignored){}}

  private static final class OutputLimit extends RuntimeException {private static final long serialVersionUID=1L;}
  private static final class Capture {volatile boolean limit,invalid,nonblank,fatal;}
  private static void read(InputStream source,Capture capture,int totalLimit,int lineLimit,Consumer<String> consumer){
    int total=0;var line=new ByteArrayOutputStream();
    try(InputStream stream=source){byte[] chunk=new byte[4096];for(int count;(count=stream.read(chunk))>=0;){if((total+=count)>totalLimit){capture.limit=true;return;}for(int i=0;i<count;i++){int value=chunk[i]&255;if(value=='\n'){consume(line,capture,consumer);line.reset();}else if(line.size()>=lineLimit){capture.limit=true;return;}else line.write(value);}}if(line.size()>0)consume(line,capture,consumer);}
    catch(OutputLimit limit){capture.limit=true;}
    catch(Exception invalid){capture.invalid=true;}
  }
  private static void consume(ByteArrayOutputStream bytes,Capture capture,Consumer<String> consumer){String line=bytes.toString(StandardCharsets.UTF_8).strip();if(!line.isEmpty()){capture.nonblank=true;if(line.matches("(?is).*(?:\\[FTL\\]|\\[ERR\\]|fatal|panic:|runtime error|could not (?:create runner|read flags|execute crawling)).*"))capture.fatal=true;consumer.accept(line);}}
  private static void awaitReader(Future<?> reader)throws InterruptedException {try{reader.get(1500,TimeUnit.MILLISECONDS);}catch(ExecutionException|TimeoutException failed){reader.cancel(true);}}
  private static String small(InputStream source)throws IOException {try(InputStream stream=source){byte[] bytes=stream.readNBytes(32769);if(bytes.length>32768)throw new IOException("Katana 버전 출력 한도");return new String(bytes,StandardCharsets.UTF_8);}}
  private static ThreadFactory daemon(String label){return task->{Thread thread=new Thread(task,label);thread.setDaemon(true);return thread;};}
  private static void forceKill(Process child){try{List<ProcessHandle> children=child.descendants().toList();for(int i=children.size()-1;i>=0;i--)children.get(i).destroyForcibly();child.destroyForcibly();}catch(RuntimeException ignored){}}
  private static Path workspace()throws IOException{Path path=Files.createTempDirectory("workbench-katana-");try{Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}return path;}
  private static void writePrivate(Path path,String text)throws IOException{Files.writeString(path,text,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);try{Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}}
  private static void configure(ProcessBuilder builder,Path work)throws IOException{builder.directory(work.toFile());Map<String,String> environment=builder.environment(),saved=new HashMap<>(environment);Set<String> keep=Set.of("PATH","SYSTEMROOT","SYSTEMDRIVE","WINDIR","HOME","USERPROFILE","HOMEDRIVE","HOMEPATH","COMSPEC","PATHEXT","LANG","LC_ALL","LC_CTYPE");environment.clear();for(var entry:saved.entrySet())if(keep.contains(entry.getKey().toUpperCase(Locale.ROOT)))environment.put(entry.getKey(),entry.getValue());String temporary=Files.createDirectories(work.resolve("tmp")).toString();environment.put("TMP",temporary);environment.put("TEMP",temporary);environment.put("TMPDIR",temporary);environment.put("XDG_CONFIG_HOME",Files.createDirectories(work.resolve("xdg")).toString());environment.put("APPDATA",Files.createDirectories(work.resolve("appdata")).toString());environment.put("LOCALAPPDATA",Files.createDirectories(work.resolve("localappdata")).toString());}
  private static void deleteWorkspace(Path path){if(path==null)return;try(var files=Files.walk(path)){for(Path entry:files.sorted(Comparator.reverseOrder()).toList())try{Files.deleteIfExists(entry);}catch(IOException ignored){}}catch(IOException ignored){}}
  private static String string(Object value,int max){if(!(value instanceof String text)||text.length()>max)return "";return text;}
  private static String token(Object value){String token=string(value,40);return token.matches("[A-Za-z0-9_.:-]{0,40}")?token:"";}
  private static String metadataName(Object value,boolean field){String name=string(value,64).strip();if(name.isEmpty()||name.matches("(?is).*(?:AKIA[A-Z0-9]{16}|ASIA[A-Z0-9]{16}|AIza[A-Za-z0-9_-]{20,}|gh[pousr]_|sk_(?:live|test)_|eyJ[A-Za-z0-9_-]{8,}).*"))return "";if(field){if(!name.matches("[A-Za-z_][A-Za-z0-9_.:\\[\\]-]{0,63}"))return "";return name.matches("(?i).*(?:password|secret|token|authorization|cookie|api[_-]?key).*")?"[민감 필드]":name;}return name.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} ._+()/-]{0,63}")&&!name.matches("(?i).*(?:password|secret|token|authorization|cookie|api.?key|bearer).*")?name:"";}
  private static String safe(String raw){try{URI uri=ScopeInspectionBrowser.valid(raw);String value=ScopeInspectionAnalysis.safeUrl(ScopeInspectionBrowser.origin(uri)+(uri.getRawPath()==null||uri.getRawPath().isEmpty()?"/":uri.getRawPath()));return value.length()>4096?"":value;}catch(RuntimeException invalid){return "";}}
  private static String mask(String value,int limit){String clean=Objects.toString(value,"").replaceAll("[\\r\\n\\t]"," ").replaceAll("https?://[^\\s<>\"']+","[URL]").replaceAll("(?i)((?:api[_-]?key|token|secret|password|authorization|cookie)\\s*[:=]\\s*)[^ ,;]+","$1[REDACTED]");return clean.substring(0,Math.min(limit,clean.length()));}
}
