package kr.workbench;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicBoolean;import java.util.function.Consumer;
/** Real CLI adapters. No shell interpolation, no active crawling during collection. */
final class PassiveUrls {
 static final int LIMIT=1000,LINE_LIMIT=8192;
 enum SourceKind {
  OBSERVED("직접 관측"), STATIC("정적 추정"), ARCHIVE("외부 아카이브"), MANUAL("수동 가져오기"), UNKNOWN("출처 미확정");
  final String label;
  SourceKind(String label){this.label=label;}
 }
 /** Source attribution describes evidence, not reachability or authorization. */
 static SourceKind sourceKind(String source){
  String value=Objects.toString(source,"");
  if(value.equals("Burp History")||value.startsWith("Burp History "))return SourceKind.OBSERVED;
  if(value.equals("JS")||value.startsWith("JS ")||value.equals("HTML")||value.startsWith("HTML ")||value.equals("OpenAPI")||value.startsWith("OpenAPI "))return SourceKind.STATIC;
  if(Set.of("waybackurls","gau","Wayback 내장").contains(value))return SourceKind.ARCHIVE;
  if(Set.of("가져오기","이전 DB").contains(value))return SourceKind.MANUAL;
  return SourceKind.UNKNOWN;
 }
 static String sourceMethod(String source){
  String value=Objects.toString(source,"");
  for(String prefix:List.of("Burp History ","OpenAPI ","HTML form ","HTML ","JS ")){
   if(value.startsWith(prefix)){
    String method=value.substring(prefix.length());
    // HTTP methods are case-sensitive tokens; do not turn inferred/manual labels into observed GETs.
    if(method.equals("form"))return "";
    return method.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,32}")?method:"";
   }
  }
  return "";
 }
 static String identityPolicy(){return "HTTP URL 문자열이 같은 후보만 병합 · fragment 제외/URI ASCII 표현 · 경로·쿼리 순서·중복 키·퍼센트 인코딩 유지";}
 static String domain(String input){String text=input.strip();if(text.contains("://")){URI u=URI.create(text);if(!Set.of("http","https").contains(u.getScheme())||u.getUserInfo()!=null)throw new IllegalArgumentException("HTTP(S) 도메인이 필요합니다.");text=u.getHost();}if(text==null||text.isBlank())throw new IllegalArgumentException("도메인을 입력하세요.");text=IDN.toASCII(text).toLowerCase(Locale.ROOT);if(text.length()>253||!text.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?"))throw new IllegalArgumentException("도메인 형식을 확인하세요.");for(String label:text.split("\\.",-1))if(label.length()>63||!label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))throw new IllegalArgumentException("도메인 형식을 확인하세요.");return text;}
 static String url(String input,String domain,boolean subs){try{if(input.length()>LINE_LIMIT)return null;URI u=URI.create(input.strip());String host=u.getHost();if(host==null||u.getUserInfo()!=null||!Set.of("http","https").contains(u.getScheme()))return null;host=host.toLowerCase(Locale.ROOT);if(!host.equals(domain)&&!(subs&&host.endsWith("."+domain)))return null;if(u.getPort()>65535)return null;String ascii=u.toASCIIString();int fragment=ascii.indexOf('#');return fragment<0?ascii:ascii.substring(0,fragment);}catch(Exception e){return null;}}
 static List<String> command(String tool,String binary,boolean subs){if(binary.isBlank())throw new IllegalArgumentException("도구 경로를 입력하세요.");List<String> args=new ArrayList<>(List.of(binary.strip()));if(tool.equals("gau")){args.addAll(List.of("--threads","2","--timeout","10","--retries","1"));if(subs)args.add("--subs");}else if(tool.equals("waybackurls")){if(!subs)args.add("-no-subs");}else throw new IllegalArgumentException("지원하지 않는 도구");return args;}
 static void terminate(Process p){if(p==null)return;p.descendants().forEach(child->{try{child.destroyForcibly();}catch(Exception ignored){}});p.destroyForcibly();}
 static void collect(String tool,String binary,String domain,boolean subs,AtomicBoolean stop,Consumer<Process> started,Consumer<String> found)throws Exception{
  Process p=new ProcessBuilder(command(tool,binary,subs)).start();started.accept(p);AtomicBoolean timedOut=new AtomicBoolean();ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"Passive-deadline");t.setDaemon(true);return t;});StringBuilder errors=new StringBuilder();Thread err=new Thread(()->{try(var stream=p.getErrorStream()){int n;while((n=stream.read())!=-1)if(errors.length()<2048)errors.append((char)n);}catch(IOException ignored){}},"Passive-stderr");err.setDaemon(true);err.start();watchdog.schedule(()->{timedOut.set(true);terminate(p);},120,TimeUnit.SECONDS);
  try{try(var stdin=p.getOutputStream()){stdin.write((domain+"\n").getBytes(StandardCharsets.UTF_8));}try(Reader reader=new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8)){StringBuilder line=new StringBuilder();boolean oversized=false;int ch,total=0;while(!stop.get()&&(ch=reader.read())!=-1){if(++total>32*1024*1024)throw new IOException("수집 출력 32MB 제한");if(ch=='\n'){if(!oversized){String normalized=url(line.toString(),domain,subs);if(normalized!=null)found.accept(normalized);}line.setLength(0);oversized=false;}else if(ch!='\r'){if(line.length()<LINE_LIMIT)line.append((char)ch);else oversized=true;}}if(!stop.get()&&!oversized&&line.length()>0){String normalized=url(line.toString(),domain,subs);if(normalized!=null)found.accept(normalized);}}
   if(stop.get())return;if(timedOut.get())throw new IOException("수집 제한 시간 120초 초과");int code=p.waitFor();err.join(1000);if(code!=0)throw new IOException(tool+" 종료 코드 "+code+" · "+errors.toString().strip());
  }finally{watchdog.shutdownNow();terminate(p);started.accept(null);}
 }
}
