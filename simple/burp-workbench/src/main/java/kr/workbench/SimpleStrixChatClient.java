package kr.workbench;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Native Strix's authenticated, run-owned viewer protocol; never a model/OAuth client.
 * Verified against usestrix/strix 1152d53579d5b3af37ada8c798f197ab093b99c6.
 */
public final class SimpleStrixChatClient implements AutoCloseable {
  public record Agent(String id, String name, String parentId, String status) {}
  public record Message(String id, String agentId, String role, String content, String timestamp) {}
  public record Snapshot(long generation, boolean connected, String status, String runId,
      List<Agent> agents, List<Message> messages) {
    public Snapshot { agents=List.copyOf(agents); messages=List.copyOf(messages); }
  }
  private static final int HTTP_LIMIT=4*1024*1024, MESSAGE_LIMIT=4000;
  private final long generation;
  private final Path directory;
  private final URI target;
  private final BooleanSupplier active;
  private final List<String> secrets;
  private volatile boolean closed;
  private URI base;
  private String capability, cookie, runId="";
  private Set<String> agentIds=Set.of();
  private String unavailable="Strix 자체 진단 창 연결 대기 · 지원하는 실행에서 자동 연결됩니다.";

  SimpleStrixChatClient(long generation, Path directory, URI target, BooleanSupplier active, List<String> secrets) {
    this.generation=generation; this.directory=directory; this.target=target;
    this.active=active; this.secrets=List.copyOf(secrets);
  }
  static Snapshot disconnected(long generation, String status) {
    return new Snapshot(generation,false,status,"",List.of(),List.of());
  }
  public synchronized Snapshot snapshot() throws IOException {
    if(closed||!active.getAsBoolean())return disconnected(generation,"Strix 실행이 종료되어 대화 연결을 닫았습니다.");
    if(!connect())return disconnected(generation,unavailable);
    verifyOwnedRun(request("GET","/api/run",null));
    Object value=request("GET","/api/transcript",null);
    if(!(value instanceof Map<?,?> transcript)||!(transcript.get("agents") instanceof List<?> rawAgents)||!(transcript.get("events") instanceof List<?> events))
      throw failure("Strix 대화 기록 형식을 확인할 수 없습니다.");
    List<Agent> agents=new ArrayList<>(); Set<String> ids=new LinkedHashSet<>();
    for(Object raw:rawAgents) {
      if(agents.size()>=256)break;
      if(!(raw instanceof Map<?,?> agent))continue;
      String id=identifier(agent.get("id")); if(id.isEmpty())continue;
      Object rawParent=agent.get("parent_id"); String parent=rawParent==null?null:identifier(rawParent);
      agents.add(new Agent(id,clean(text(agent.get("name")),1024),parent,clean(text(agent.get("status")),128)));
      ids.add(id);
    }
    List<Message> messages=new ArrayList<>(); int total=0;
    // Keep a bounded recent conversation; preserve actual agent/role identity.
    for(int index=events.size()-1;index>=0&&messages.size()<200;index--) {
      if(!(events.get(index) instanceof Map<?,?> event)||!"chat".equals(event.get("type"))||!(event.get("data") instanceof Map<?,?> data))continue;
      String agent=identifier(event.get("agent_id")),role=text(data.get("role"));
      if(!ids.contains(agent)||!Set.of("user","assistant").contains(role))continue;
      String content=clean(text(data.get("content")),16*1024);
      if(content.isBlank())continue;
      if(total+content.length()>128*1024)break; total+=content.length();
      messages.add(new Message(identifier(event.get("id")),agent,role,content,clean(text(event.get("timestamp")),128)));
    }
    Collections.reverse(messages); agentIds=Set.copyOf(ids);
    if(closed||!active.getAsBoolean())return disconnected(generation,"Strix 실행이 종료되어 대화 연결을 닫았습니다.");
    return new Snapshot(generation,true,"같은 Strix 진단 에이전트에 연결됨 · 추가 지시는 진단 도구를 실행할 수 있습니다.",runId,agents,messages);
  }
  /** Queue acceptance is not a model answer. Native Strix may interrupt tools and execute more tools. */
  public synchronized void send(long expectedGeneration,String agentId,String message) throws IOException {
    if(expectedGeneration!=generation||closed||!active.getAsBoolean())throw failure("종료되거나 다른 실행의 대화입니다. 현재 진단을 다시 확인하세요.");
    String value=Objects.requireNonNullElse(message,"").strip();
    if(value.isEmpty()||value.codePointCount(0,value.length())>MESSAGE_LIMIT)throw failure("대화는 1~4,000자로 입력하세요.");
    Snapshot current=snapshot();
    if(!current.connected()||!agentIds.contains(agentId))throw failure("이번 실행의 연결된 에이전트를 선택하세요.");
    value=SimpleStrixClient.cleanChat(value,allSecrets(),64*1024);
    if(value.codePointCount(0,value.length())>MESSAGE_LIMIT)throw failure("가림 처리한 대화가 4,000자 한도를 초과했습니다.");
    if(closed||!active.getAsBoolean())throw failure("진단이 중지되어 메시지를 보내지 않았습니다.");
    String body="{\"agent_id\":"+json(agentId)+",\"message\":"+json(value)+"}";
    Object answer=request("POST","/api/agents/steer",body);
    if(!(answer instanceof Map<?,?> map)||!Boolean.TRUE.equals(map.get("ok")))throw failure("Strix가 메시지 수신을 확인하지 못했습니다. 자동 재전송하지 않았습니다.");
  }
  private boolean connect() throws IOException {
    if(base!=null)return true;
    Path handoff=directory.resolve("viewer-session.json");
    if(!Files.exists(handoff,LinkOption.NOFOLLOW_LINKS))return false;
    SimpleStrixClient.requireNoLinks(directory,handoff);
    if(!Files.isRegularFile(handoff,LinkOption.NOFOLLOW_LINKS)||Files.size(handoff)>4096)throw failure("소유한 Strix 대화 연결 정보를 확인할 수 없습니다.");
    try(var input=Files.newInputStream(handoff,LinkOption.NOFOLLOW_LINKS)) {
      Object raw=MinerJson.parse(new String(input.readNBytes(4097),StandardCharsets.UTF_8),4096,128);
      if(!(raw instanceof Map<?,?> object))throw failure("Strix 대화 연결 형식이 올바르지 않습니다.");
      URI uri=validatedViewerUrl(text(object.get("url")));
      String token=uri.getRawQuery().substring("token=".length());
      URI server=new URI("http",null,"127.0.0.1",uri.getPort(),null,null,null);
      // The capability was generated by this owned native TUI, never read from OAuth storage.
      String expectedCookie="strix_viewer_session_"+uri.getPort()+"="+token;
      Response response=exchange(uri,"GET",null,null,256*1024);
      if(response.status()!=200)throw failure("Strix 자체 대화 인증을 시작하지 못했습니다.");
      String received=response.headers().get("set-cookie");
      if(received==null||!received.split(";",2)[0].equals(expectedCookie))throw failure("Strix 자체 대화 인증 응답이 일치하지 않습니다.");
      base=server; capability=token; cookie=expectedCookie;
      Object caps=request("GET","/api/capabilities",null);
      if(!(caps instanceof Map<?,?> map)||!Boolean.TRUE.equals(map.get("can_steer"))) {
        unavailable="이 Strix 실행은 실시간 대화 전송을 지원하지 않습니다. 진단은 계속됩니다.";
        resetConnection(); return false;
      }
      verifyOwnedRun(request("GET","/api/run",null));
      return true;
    } catch(URISyntaxException|IllegalArgumentException error) {
      resetConnection(); throw failure("소유한 Strix 대화 연결 형식을 확인하세요.");
    } catch(IOException error) {resetConnection(); throw failure("Strix 자체 대화 연결을 확인하지 못했습니다. 진단은 계속됩니다.");}
  }
  /** Strict literals avoid DNS, proxy routing, userinfo, redirects and URL injection. */
  static URI validatedViewerUrl(String raw) throws IOException {
    try {
      if(raw.length()>1024||raw.chars().anyMatch(c->c<=32||c==127))throw new IllegalArgumentException();
      URI uri=URI.create(raw); String query=uri.getRawQuery();
      if(!"http".equals(uri.getScheme())||!"127.0.0.1".equals(uri.getHost())||uri.getPort()<1||uri.getPort()>65535
          ||!uri.getRawAuthority().equals("127.0.0.1:"+uri.getPort())||!"/".equals(uri.getRawPath())||uri.getRawFragment()!=null
          ||query==null||!query.matches("token=[A-Za-z0-9_-]{32,128}"))throw new IllegalArgumentException();
      return uri;
    } catch(RuntimeException error) {throw failure("Strix 대화 주소는 이번 실행의 인증된 127.0.0.1 연결이어야 합니다.");}
  }
  private void verifyOwnedRun(Object raw) throws IOException {
    if(!(raw instanceof Map<?,?> record))throw failure("Strix 실행 정보를 확인할 수 없습니다.");
    String name=text(record.get("run_name")); if(name.isBlank())name=text(record.get("run_id"));
    if(!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}")||!runId.isEmpty()&&!runId.equals(name))throw failure("Strix 대화가 이번 실행과 일치하지 않습니다.");
    Path local=directory.resolve("strix_runs").resolve(name).resolve("run.json");
    SimpleStrixClient.requireNoLinks(directory,local);
    if(!Files.isRegularFile(local,LinkOption.NOFOLLOW_LINKS)||Files.size(local)>128*1024)throw failure("이번 실행의 Strix 기록을 확인할 수 없습니다.");
    try(var input=Files.newInputStream(local,LinkOption.NOFOLLOW_LINKS)) {
      Object localRaw=MinerJson.parse(new String(readBounded(input,128*1024),StandardCharsets.UTF_8),128*1024,10000);
      if(!(localRaw instanceof Map<?,?> localRecord)||!targetMatches(record)||!targetMatches(localRecord))throw failure("Strix 대화의 대상이 승인한 Origin과 다릅니다.");
      String localName=text(localRecord.get("run_name"));if(localName.isBlank())localName=text(localRecord.get("run_id"));
      if(!name.equals(localName))throw failure("Strix 실행 기록과 대화가 일치하지 않습니다.");
    }
    runId=name;
  }
  private boolean targetMatches(Map<?,?> record) {
    if(!(record.get("targets_info") instanceof List<?> targets)||targets.size()!=1||!(targets.get(0) instanceof Map<?,?> info))return false;
    String original=text(info.get("original"));
    return original.equals(target.toASCIIString())||original.equals(target.toASCIIString()+"/");
  }
  private Object request(String method,String path,String body) throws IOException {
    if(closed||!active.getAsBoolean()||base==null)throw failure("Strix 대화 연결이 종료됐습니다.");
    try {
      Response response=exchange(URI.create(base.toASCIIString()+path),method,body,cookie,HTTP_LIMIT);
      int status=response.status();
      if(status!=200)throw failure(status==403?"Strix 대화 인증이 만료됐거나 이 실행을 지원하지 않습니다.":"Strix 대화 요청을 완료하지 못했습니다. 자동 재전송하지 않았습니다.");
      return MinerJson.parse(new String(response.body(),StandardCharsets.UTF_8),HTTP_LIMIT,100000);
    } catch(IOException error) {throw failure("Strix 대화 요청을 완료하지 못했습니다. 자동 재전송하지 않았습니다.");}
    catch(RuntimeException error) {throw failure("Strix 대화 응답을 확인할 수 없습니다.");}
  }
  private record Response(int status,Map<String,String> headers,byte[] body) {}
  /** Native viewer uses bounded Content-Length HTTP/1.x. An owned literal socket
   * avoids JVM-global proxies, CookieHandler and Authenticator entirely. */
  private Response exchange(URI uri,String method,String body,String sessionCookie,int limit) throws IOException {
    byte[] payload=body==null?new byte[0]:body.getBytes(StandardCharsets.UTF_8);
    try(Socket socket=new Socket(Proxy.NO_PROXY)) {
      socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),uri.getPort()),1500);
      long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1500);
      String path=uri.getRawPath();if(uri.getRawQuery()!=null)path+="?"+uri.getRawQuery();
      String headers=method+" "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+uri.getPort()+"\r\nConnection: close\r\nAccept: application/json\r\n"
          +(sessionCookie==null?"":"Cookie: "+sessionCookie+"\r\n")
          +(body==null?"":"Content-Type: application/json; charset=utf-8\r\nContent-Length: "+payload.length+"\r\n")+"\r\n";
      if(closed||!active.getAsBoolean())throw failure("Strix 대화 연결이 종료됐습니다.");
      OutputStream output=socket.getOutputStream();output.write(headers.getBytes(StandardCharsets.US_ASCII));output.write(payload);output.flush();
      InputStream input=socket.getInputStream();ByteArrayOutputStream prefix=new ByteArrayOutputStream();
      int matched=0;byte[] ending={13,10,13,10};
      while(matched<4) {
        socket.setSoTimeout(remaining(deadline));int next=input.read();
        if(next<0||prefix.size()>=32*1024)throw failure("Strix 대화 HTTP 응답을 확인할 수 없습니다.");
        prefix.write(next);matched=next==ending[matched]?matched+1:next==13?1:0;
      }
      String[] lines=prefix.toString(StandardCharsets.US_ASCII).split("\r\n");
      if(lines.length<1||!lines[0].matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?"))throw failure("Strix 대화 HTTP 응답 형식이 올바르지 않습니다.");
      int status=Integer.parseInt(lines[0].substring(9,12));Map<String,String> received=new LinkedHashMap<>();
      for(int index=1;index<lines.length;index++) {
        int colon=lines[index].indexOf(':');if(colon<1)throw failure("Strix 대화 HTTP 헤더 형식이 올바르지 않습니다.");
        String key=lines[index].substring(0,colon).toLowerCase(Locale.ROOT),value=lines[index].substring(colon+1).strip();
        if(received.putIfAbsent(key,value)!=null)throw failure("중복된 Strix 대화 HTTP 헤더를 거부했습니다.");
      }
      String rawLength=received.get("content-length");
      if(received.containsKey("transfer-encoding")||rawLength==null||!rawLength.matches("[0-9]{1,9}"))throw failure("Strix 자체 대화의 HTTP 응답 길이를 확인하세요.");
      int length=Integer.parseInt(rawLength);if(length>limit)throw failure("Strix 대화 응답 크기 한도를 초과했습니다.");
      byte[] bytes=new byte[length];int offset=0;
      while(offset<length) {
        socket.setSoTimeout(remaining(deadline));int count=input.read(bytes,offset,length-offset);
        if(count<0)throw failure("Strix 대화 응답이 중단됐습니다.");offset+=count;
      }
      return new Response(status,Map.copyOf(received),bytes);
    }
  }
  private static int remaining(long deadline) throws IOException {
    long nanos=deadline-System.nanoTime();if(nanos<=0)throw failure("Strix 대화 응답 대기 시간을 초과했습니다.");
    return (int)Math.max(1,TimeUnit.NANOSECONDS.toMillis(nanos));
  }
  private static byte[] readBounded(InputStream input,int limit) throws IOException {
    byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw failure("Strix 대화 응답 크기 한도를 초과했습니다.");return bytes;
  }
  private String clean(String text,int limit) throws IOException {
    // The HTTP response is already bounded. Mask the full value before a
    // display cut, otherwise a credential crossing the cut leaks its prefix.
    String value=SimpleStrixClient.cleanChat(text,allSecrets(),HTTP_LIMIT);
    return value.length()>limit?value.substring(0,limit)+"\n[표시 한도]":value;
  }
  private List<String> allSecrets() {List<String> values=new ArrayList<>(secrets);if(capability!=null)values.add(capability);return values;}
  private void resetConnection(){base=null;capability=null;cookie=null;agentIds=Set.of();}
  /** Cancellation never waits for an HTTP worker or its socket timeout. */
  @Override public void close(){closed=true;}
  private static String identifier(Object value){String text=text(value);return text.matches("[A-Za-z0-9_.:-]{1,256}")?text:"";}
  private static String text(Object value){return value instanceof String string?string:"";}
  private static IOException failure(String message){return new IOException(message);}
  private static String json(String text){StringBuilder out=new StringBuilder("\"");for(char c:text.toCharArray()){if(c=='\\'||c=='\"')out.append('\\').append(c);else if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}return out.append('"').toString();}
}
