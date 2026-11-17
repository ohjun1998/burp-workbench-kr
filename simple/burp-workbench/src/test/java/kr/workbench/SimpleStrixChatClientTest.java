package kr.workbench;

import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real owned PTY + loopback HTTP, with a synthetic Strix executable; no model/auth/target calls.
 * The fixture follows usestrix/strix commit 1152d53579d5b3af37ada8c798f197ab093b99c6:
 * interface/viewer/server.py (capability cookie, /api/run, /api/transcript, /api/agents/steer),
 * interface/tui/live_view.py (agent/chat event projection), and
 * interface/tui/internal/app/update.go (Ctrl+O and default-cancel quit confirmation).
 * This validates the owned transport against those contracts, not an installed Strix/model run.
 */
public final class SimpleStrixChatClientTest {
  private static int checks;
  private static final String TARGET = "https://example.invalid";
  private static final String SECRET = "FAKE_STRIX_CONFIG_CREDENTIAL_ONLY";
  private interface Checked { void run() throws Exception; }
  private static void check(boolean value, String message) {
    checks++; if (!value) throw new AssertionError(message); System.out.println("PASS " + message);
  }
  private static void rejected(Checked action, String message) throws Exception {
    try { action.run(); throw new AssertionError(message); }
    catch (IOException expected) { check(true, message); }
  }
  private static void await(BooleanSupplier condition, String message) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(40);
    check(condition.getAsBoolean(), message);
  }
  @SuppressWarnings("unchecked") private static Map<String,Object> observed(Path home) throws Exception {
    return (Map<String,Object>) MinerJson.parse(Files.readString(home.resolve("fixture-observed.json")), 65536, 10000);
  }
  private static SimpleStrixClient.Plan plan(SimpleStrixClient client) throws Exception {
    return client.plan(TARGET, "quick", 5, 30, "가림 처리된 테스트 관찰");
  }
  private static void mode(Path home, String value) throws Exception {
    Files.writeString(home.resolve("fixture-mode"), value);
    Files.deleteIfExists(home.resolve("fixture-observed.json"));
    Files.deleteIfExists(home.resolve("fixture-posts.json"));
  }
  private static int posts(Path home) throws Exception {
    Path file = home.resolve("fixture-posts.json");
    if (!Files.exists(file)) return 0;
    return ((Number) MinerJson.parse(Files.readString(file), 65536, 10000)).intValue();
  }
  private static void fixture(Path home) throws Exception {
    Files.createDirectories(home.resolve(".strix/bin"));
    Files.writeString(home.resolve(".strix/cli-config.json"), "{\"env\":{\"STRIX_LLM\":\"openai/halogen-qwen3.8-flash-next\",\"OPENAI_BASE_URL\":\"http://127.0.0.1:8731/v1\",\"STRIX_API_TYPE\":\"chat_completions\",\"OPENAI_API_KEY\":\"" + SECRET + "\",\"STRIX_TELEMETRY\":false}}");
    Path binary = home.resolve(".strix/bin/strix");
    Files.writeString(binary, """
#!/usr/bin/python3
import http.server,json,os,pathlib,select,shlex,signal,subprocess,sys,threading,time,tty,urllib.parse,webbrowser
home=pathlib.Path(__file__).resolve().parents[2]
argv=sys.argv[1:]
if argv==['auth','status']:
    print('Signed in (synthetic fixture only)');sys.exit(0)
mode=(home/'fixture-mode').read_text().strip()
run='fixture-'+str(os.getpid())
token='FAKE_NATIVE_VIEWER_CAPABILITY_'+('x'*32)
target=argv[argv.index('--target')+1]
run_dir=pathlib.Path.cwd()/'strix_runs'/run
run_dir.mkdir(parents=True)
record={'run_id':run,'run_name':run,'status':'running','targets_info':[{'original':target,'type':'url'}],'start_time':'2026-11-17T10:00:00Z','end_time':None}
(run_dir/'run.json').write_text(json.dumps(record))
observed={'pid':os.getpid(),'args':argv,'stdinTty':os.isatty(0),'stdoutTty':os.isatty(1),'run':run,'cwd':str(pathlib.Path.cwd()),'providerAlias':any(k.startswith(('OPENAI_','LLM_','ANTHROPIC_','LITELLM_')) for k in os.environ),'configMode':os.stat(argv[argv.index('--config')+1]).st_mode & 0o777,'updateCheckDisabled':os.environ.get('STRIX_NO_UPDATE_CHECK')=='1'}
(home/'fixture-observed.json').write_text(json.dumps(observed))
events=[{'id':'assistant_1','type':'chat','agent_id':'root','timestamp':'2026-11-17T10:00:00Z','version':0,'data':{'role':'assistant','content':'현재 진단에서 인증 흐름을 살펴보고 있습니다.'}},{'id':'tool_1','type':'tool','agent_id':'root','timestamp':'2026-11-17T10:00:01Z','version':0,'data':{'name':'terminal','output':'synthetic tool result'}}]
post_count=0
lock=threading.Lock()
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def send_json(self,data,status=200):
        raw=json.dumps(data,ensure_ascii=False).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
    def authorized(self): return self.headers.get('Cookie','')=='strix_viewer_session_'+str(self.server.server_port)+'='+token
    def do_GET(self):
        parsed=urllib.parse.urlsplit(self.path)
        if parsed.path=='/':
            payload=b'<html>synthetic native viewer</html>'
            self.send_response(200)
            if urllib.parse.parse_qs(parsed.query).get('token')==[token]: self.send_header('Set-Cookie','strix_viewer_session_'+str(self.server.server_port)+'='+token+'; Path=/; HttpOnly; SameSite=Strict')
            self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload);return
        if parsed.path=='/api/capabilities': self.send_json({'can_steer':mode!='unavailable'});return
        if not self.authorized(): self.send_json({'error':'forbidden'},403);return
        if parsed.query: self.send_json({'error':'cross-run-query-not-supported'},400);return
        if parsed.path=='/api/run':
            if mode=='redirect': self.send_response(302);self.send_header('Location','http://example.invalid/never-contact');self.end_headers();return
            data=dict(record)
            if mode=='wrong-target': data['targets_info']=[{'original':'https://different.invalid','type':'url'}]
            if mode=='wrong-run': data['run_id']='outside-run';data['run_name']='outside-run'
            self.send_json(data);return
        if parsed.path=='/api/transcript':
            agents=[{'id':'root','name':'주 진단 에이전트','parent_id':None,'status':'running'},{'id':'child','name':'인증 분석','parent_id':'root','status':'waiting'}]
            with lock: data={'agents':agents,'events':list(events)}
            if (home/'fixture-boundary').exists(): data['events'].append({'id':'assistant_boundary','type':'chat','agent_id':'root','timestamp':'2026-11-17T10:00:01Z','version':0,'data':{'role':'assistant','content':('x'*(16*1024-20))+'FAKE_STRIX_CONFIG_CREDENTIAL_ONLY'+'trailing display content'}})
            self.send_json(data);return
        self.send_json({'error':'unknown'},404)
    def do_POST(self):
        global post_count
        if not self.authorized(): self.send_json({'error':'forbidden'},403);return
        size=int(self.headers.get('Content-Length','0'));body=json.loads(self.rfile.read(size))
        with lock:
            post_count+=1;(home/'fixture-posts.json').write_text(json.dumps(post_count))
        if self.path!='/api/agents/steer': self.send_json({'error':'unknown'},404);return
        if (home/'fixture-refuse').exists(): self.send_json({'ok':False,'error':'not_delivered'});return
        if body.get('agent_id') not in ('root','child') or not body.get('message'): self.send_json({'ok':False,'error':'not_delivered'});return
        with lock:
            event_id=str(post_count)
            events.append({'id':'user_'+event_id,'type':'chat','agent_id':body['agent_id'],'timestamp':'2026-11-17T10:00:02Z','version':0,'data':{'role':'user','content':body['message']}})
            events.append({'id':'reply_'+event_id,'type':'chat','agent_id':body['agent_id'],'timestamp':'2026-11-17T10:00:03Z','version':0,'data':{'role':'assistant','content':'같은 진단의 응답: '+body['message']}})
        self.send_json({'ok':True})
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
server.daemon_threads=True
threading.Thread(target=server.serve_forever,daemon=True).start()
url='http://127.0.0.1:'+str(server.server_port)+'/?token='+token
if mode=='remote': url='http://example.invalid/?token='+token
if mode=='userinfo': url='http://user:pass@127.0.0.1:'+str(server.server_port)+'/?token='+token
if mode=='extra-query': url+='&run=outside-run'
if mode=='bad-path': url=url.replace('/?token=','/foreign?token=')
if mode=='ipv6': url=url.replace('127.0.0.1','[::1]')
tty.setraw(0)
print('synthetic terminal ready '+url,flush=True)
quit_prompt=False
confirm=False
deadline=time.monotonic()+25
try:
    while time.monotonic()<deadline:
        ready,_,_=select.select([0],[],[],.2)
        if not ready: continue
        value=os.read(0,4096)
        if not value: break
        for code in value:
            if code==15:
                webbrowser.open(url)
            elif code==17 or code==3:
                quit_prompt=True;confirm=False
            elif code==9 and quit_prompt:
                confirm=not confirm
            elif code in (10,13) and quit_prompt and confirm:
                raise SystemExit(0)
finally:
    server.shutdown();server.server_close()
""");
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
  }
  private static SimpleStrixChatClient.Snapshot connected(SimpleStrixClient client) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    SimpleStrixChatClient.Snapshot snapshot;
    do {
      snapshot = client.chatSnapshot();
      if (snapshot.connected()) return snapshot;
      Thread.sleep(80);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("native viewer fixture did not connect: " + snapshot.status());
  }
  private static void erase(Path root) throws Exception {
    try (var paths=Files.walk(root)) { for (Path path: paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
  }
  public static void main(String[] args) throws Exception {
    String capability="a".repeat(40);
    check(SimpleStrixChatClient.validatedViewerUrl("http://127.0.0.1:4321/?token="+capability).getPort()==4321,"native capability URL accepts only an explicit literal-loopback port");
    for (String unsafe:List.of("https://127.0.0.1:4321/?token=", "http://localhost:4321/?token=", "http://127.1:4321/?token=", "http://127.0.0.1:0/?token=", "http://127.0.0.1:65536/?token=", "http://127.0.0.1:4321/path?token=", "http://user@127.0.0.1:4321/?token="))
      rejected(()->SimpleStrixChatClient.validatedViewerUrl(unsafe+capability),"reject ambiguous or non-native viewer capability URL");
    rejected(()->SimpleStrixChatClient.validatedViewerUrl("http://127.0.0.1:4321/?token="+capability+"&run=other"),"capability bootstrap cannot add a foreign run query");
    rejected(()->SimpleStrixChatClient.validatedViewerUrl("http://127.0.0.1:4321/?token="+capability+"#fragment"),"capability bootstrap cannot add a fragment");
    if (!Files.isExecutable(Path.of("/usr/bin/python3")) || !FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      System.out.println("SimpleStrixChatClientTest: "+checks+" checks passed (POSIX PTY fixture unavailable; actual Strix not run)"); return;
    }
    Path home=Files.createTempDirectory("simple-strix-chat-test-");
    ExecutorService worker=Executors.newSingleThreadExecutor();
    CookieHandler priorCookies=CookieHandler.getDefault();
    ProxySelector priorProxy=ProxySelector.getDefault();
    AtomicInteger cookieCalls=new AtomicInteger(),proxyCalls=new AtomicInteger();
    CookieHandler.setDefault(new CookieHandler() {
      @Override public Map<String,List<String>> get(URI uri,Map<String,List<String>> headers) {
        cookieCalls.incrementAndGet();
        return Map.of("Cookie",List.of("FOREIGN_APPLICATION_SESSION=NEVER_FORWARD_THIS"));
      }
      @Override public void put(URI uri,Map<String,List<String>> headers) { cookieCalls.incrementAndGet(); }
    });
    ProxySelector.setDefault(new ProxySelector() {
      @Override public List<Proxy> select(URI uri) {
        proxyCalls.incrementAndGet();
        return List.of(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("198.51.100.1",1)));
      }
      @Override public void connectFailed(URI uri,SocketAddress address,IOException failure) { proxyCalls.incrementAndGet(); }
    });
    try (SimpleStrixClient client=new SimpleStrixClient(home,home.resolve("owned"))) {
      fixture(home);mode(home,"normal");
      var cancelled=client.executeInteractive(plan(client),()->false,null);
      check(cancelled.status().equals("cancelled") && !Files.exists(home.resolve("fixture-observed.json")),"interactive approval cancellation starts zero native processes");
      check(!client.chatSnapshot().connected(),"no chat session is invented before an approved run");
      Future<SimpleStrixClient.Result> pending=worker.submit(()->client.executeInteractive(plan(client),()->true,null));
      await(()->Files.exists(home.resolve("fixture-observed.json")),"approved interactive executable starts in owned PTY");
      var first=connected(client);
      check(first.connected() && first.agents().size()==2 && first.agents().get(0).parentId()==null,"native root and child agents are read from authenticated transcript");
      check(first.messages().size()==1 && first.messages().get(0).role().equals("assistant") && first.messages().get(0).content().contains("인증 흐름"),"committed real assistant text is displayed and tool events are excluded");
      var run=observed(home);
      check(Boolean.TRUE.equals(run.get("stdinTty")) && Boolean.TRUE.equals(run.get("stdoutTty")) && !run.get("args").toString().contains("--non-interactive"),"official executable receives a TTY and supported interactive CLI arguments");
      check(!Boolean.TRUE.equals(run.get("providerAlias")) && ((Number)run.get("configMode")).intValue()==384,"interactive bridge preserves provider isolation and private run config");
      check(Boolean.TRUE.equals(run.get("updateCheckDisabled")),"owned native TUI skips interactive update prompts without changing global config");
      Files.writeString(home.resolve("fixture-boundary"),"display-cut");
      var boundary=client.chatSnapshot().messages().stream().filter(message->message.id().equals("assistant_boundary")).findFirst().orElseThrow();
      check(boundary.content().contains("[REDACTED]") && !boundary.content().contains(SECRET.substring(0,20)) && boundary.content().contains("[표시 한도]"),"known credential crossing the 16 KiB display boundary is masked before truncation without leaking its prefix");
      Files.delete(home.resolve("fixture-boundary"));
      rejected(()->client.sendChat(first.generation()+1,"root","stale"),"stale generation cannot send into another diagnostic session");
      rejected(()->client.sendChat(first.generation(),"missing-agent","unknown"),"unknown agent is rejected before native HTTP send");
      rejected(()->client.sendChat(first.generation(),"root","  \n\t"),"blank followup is rejected");
      rejected(()->client.sendChat(first.generation(),"root","가".repeat(4001)),"native followup character bound is enforced before dispatch");
      check(posts(home)==0,"invalid or stale followups emit zero native steering requests");
      String question="이 진단에서 인증 경계가 어떻게 보이나요?";
      client.sendChat(first.generation(),"child",question);
      var reply=client.chatSnapshot();
      check(posts(home)==1 && reply.messages().stream().anyMatch(m->m.role().equals("user")&&m.agentId().equals("child")&&m.content().equals(question)),"accepted native steering preserves selected agent and exact question");
      check(reply.messages().stream().anyMatch(m->m.role().equals("assistant")&&m.content().equals("같은 진단의 응답: "+question)),"followup response comes from the same authenticated run transcript");
      check(cookieCalls.get()==0 && proxyCalls.get()==0,"native chat ignores JVM-global application cookies and external proxy selectors");
      client.sendChat(first.generation(),"root","😀".repeat(4000));
      check(posts(home)==2,"native 4000-character limit counts Unicode characters rather than UTF-16 units");
      rejected(()->client.sendChat(first.generation(),"root","😀".repeat(4001)),"supplementary Unicode characters cannot exceed the native followup limit");
      Files.writeString(home.resolve("fixture-refuse"),"refuse");
      rejected(()->client.sendChat(first.generation(),"root","배달 실패 확인"),"HTTP 200 with ok:false is reported as an undelivered message");
      Files.delete(home.resolve("fixture-refuse"));
      client.cancel();
      var result=pending.get(10,TimeUnit.SECONDS);
      check(result.status().equals("cancelled") && !client.busy(),"owned interactive process and HTTP viewer stop on cancel");
      check(!client.chatSnapshot().connected(),"cancel invalidates native chat capability immediately");
      rejected(()->client.sendChat(first.generation(),"root","late"),"a completed owned process rejects late chat sends");
      check(!result.logTail().contains("FAKE_NATIVE_VIEWER_CAPABILITY_") && !result.logTail().contains("?token=") && !result.logTail().contains(SECRET),"private native viewer URL and provider credentials never appear in public terminal output");
      for (String unsafe:List.of("remote","userinfo","extra-query","bad-path","ipv6","unavailable","redirect","wrong-target","wrong-run")) {
        mode(home,unsafe);
        Future<SimpleStrixClient.Result> attempt=worker.submit(()->client.executeInteractive(plan(client),()->true,null));
        await(()->Files.exists(home.resolve("fixture-observed.json")),"start isolated "+unsafe+" viewer fixture");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        boolean attached=false;
        while (System.nanoTime()<deadline) {
          try { attached|=client.chatSnapshot().connected(); } catch (IOException unavailable) { }
          Thread.sleep(80);
        }
        check(!attached,"refuse "+unsafe+" viewer attachment without claiming a native chat connection");
        check(client.busy() && posts(home)==0,"unavailable "+unsafe+" chat neither sends target work nor silently stops the owned diagnosis");
        client.cancel();
        check(attempt.get(10,TimeUnit.SECONDS).status().equals("cancelled"),"cancel "+unsafe+" native fixture independently");
      }
      mode(home,"normal");
      Future<SimpleStrixClient.Result> another=worker.submit(()->client.executeInteractive(plan(client),()->true,null));
      var fresh=connected(client);
      check(fresh.generation()!=first.generation(),"a new owned run receives a fresh chat generation");
      rejected(()->client.sendChat(first.generation(),"root","previous run"),"previous diagnostic generation cannot steer a new run");
      client.cancel();another.get(10,TimeUnit.SECONDS);
      check(cookieCalls.get()==0 && proxyCalls.get()==0,"all viewer authentication, reads and sends keep JVM-global HTTP state untouched");
    } finally {
      CookieHandler.setDefault(priorCookies);ProxySelector.setDefault(priorProxy);
      worker.shutdownNow();erase(home);
    }
    System.out.println("SimpleStrixChatClientTest: "+checks+" checks passed (synthetic native PTY/HTTP only; no Strix/model/auth/target calls)");
  }
}
