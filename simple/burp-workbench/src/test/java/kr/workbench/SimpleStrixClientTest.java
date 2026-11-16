package kr.workbench;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;

/** Fake wrapper processes only: no Strix, Docker, model, auth service or target is contacted. */
public final class SimpleStrixClientTest {
  private static int checks;
  private static final String KEY="FAKE_STRIX_API_KEY_0542_ONLY", CAPTURED="FAKE_CAPTURED_AUTH_TOKEN_ONLY";
  private interface Checked {void run() throws Exception;}
  private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);System.out.println("PASS "+message);}
  private static void rejected(Checked call,String message)throws Exception{try{call.run();throw new AssertionError(message);}catch(IOException expected){check(true,message);}}
  private static void config(Path home,String base,String model,String api)throws Exception{
    Path p=home.resolve(".strix/cli-config.json");Files.createDirectories(p.getParent());
    Files.writeString(p,"{\"env\":{\"STRIX_LLM\":\""+model+"\",\"OPENAI_BASE_URL\":\""+base+"\",\"STRIX_API_TYPE\":\""+api+"\",\"OPENAI_API_KEY\":\""+KEY+"\",\"STRIX_DOCKER_SANDBOX_NETWORK\":\"fake-network\",\"LLM_PREFLIGHT_TIMEOUT\":\"10\",\"LLM_STREAM_FIRST_EVENT_TIMEOUT\":30,\"LLM_TIMEOUT\":15.5,\"STRIX_CONTEXT_AUTO_COMPACT\":true,\"STRIX_CONTEXT_AUTO_COMPACT_BUFFER_TOKENS\":8192,\"STRIX_REASONING_EFFORT\":\"low\",\"STRIX_TOOL_OUTPUT_MAX_BYTES\":65536,\"STRIX_TOOL_OUTPUT_MAX_LINES\":100,\"STRIX_TOOL_OUTPUT_MAX_TOKENS\":\"8192\",\"STRIX_TELEMETRY\":false,\"LLM_EXTRA_HEADERS\":\"never-copy\",\"LLM_EXTRA_BODY\":\"never-copy\",\"DEDUPE_LLM_API_BASE\":\"https://outside.invalid\",\"STRIX_DEDUPE_LLM\":\"openai/never\"}}");
  }
  private static void config(Path home)throws Exception{config(home,"http://127.0.0.1:8731/v1","openai/halogen-qwen3.8-flash-next","chat_completions");}
  private static void wrapper(Path home)throws Exception{
    Path file=home.resolve(".strix/bin/strix");Files.createDirectories(file.getParent());
    String program="""
#!/usr/bin/python3
import json,os,pathlib,signal,stat,subprocess,sys,time
h=pathlib.Path(__file__).resolve().parents[2]
if sys.argv[1:]==['auth','status']:
    if (h/'signed-in').exists(): print('Signed in as synthetic@example.invalid');sys.exit(0)
    print('Not signed in');sys.exit(1)
a=sys.argv[1:]; c=pathlib.Path(a[a.index('--config')+1]); ins=pathlib.Path(a[a.index('--instruction-file')+1]); e=json.loads(c.read_text())['env']; n=int(a[a.index('--max-turns')+1]); key=e.get('LLM_API_KEY','')
if n==4: signal.signal(signal.SIGTERM,signal.SIG_IGN)
record={'args':a,'env':e,'instruction':ins.read_text(),'configMode':stat.S_IMODE(c.stat().st_mode),'instructionMode':stat.S_IMODE(ins.stat().st_mode),'workMode':stat.S_IMODE(pathlib.Path.cwd().stat().st_mode),'cwd':str(pathlib.Path.cwd()),'pid':os.getpid(),'providerAliasesInherited':any(k.startswith(('OPENAI_','ANTHROPIC_','LITELLM_','LLM_','DEDUPE_LLM_','STRIX_DEDUPE_')) or k in ('STRIX_LLM','STRIX_API_TYPE','STRIX_DOCKER_SANDBOX_NETWORK') for k in os.environ)}
(h/'last-run.json').write_text(json.dumps(record))
if n==2:
    child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(30)']);pathlib.Path('child.pid').write_text(str(child.pid));print('waiting',flush=True);time.sleep(30)
if n==4: print('ignoring SIGTERM',flush=True);time.sleep(30)
if n==3: os.write(1,b'X'*100000)
os.write(1,('LIMITS 8192 true\\nkey '+key[:len(key)//2]).encode());time.sleep(.01);os.write(1,(key[len(key)//2:]+' FAKE_CAPTURED_AUTH_TOKEN_ONLY END\\n').encode())
r=pathlib.Path('strix_runs/fake');r.mkdir(parents=True);(r/'report.md').write_text('Synthetic report '+key+' FAKE_CAPTURED_AUTH_TOKEN_ONLY')
(r/'metadata.json').write_text('{"metadata":true}');(r/'penetration_test_report.md').write_text('Synthetic final report '+key+' FAKE_CAPTURED_AUTH_TOKEN_ONLY')
(r/'vulnerabilities.json').write_text('{"vulnerabilities":[]}');(r/'vulnerabilities.csv').write_text('id,severity\\n')
(h/'outside.md').write_text('outside');(r/'linked.md').symlink_to(h/'outside.md')
# Native persistence may rewrite ONLY the supplied private runtime config.
c.write_text(json.dumps({'env':e}))
""";
    Files.writeString(file,program);Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rwx------"));
  }
  @SuppressWarnings("unchecked")private static Map<String,Object> observed(Path home)throws Exception{return (Map<String,Object>)MinerJson.parse(Files.readString(home.resolve("last-run.json")),128*1024,10000);}
  private static SimpleStrixClient.Plan plan(SimpleStrixClient c,int turns,int timeout)throws Exception{return c.plan("https://example.invalid","quick",turns,timeout,"Authorization: Bearer "+CAPTURED+"\nCookie: session="+CAPTURED+"\n{\"password\":\""+CAPTURED+"\"}");}
  private static boolean running(long pid){
    if(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))return false;
    try{return !Files.readString(Path.of("/proc/"+pid+"/stat")).matches("(?s).*\\) Z .* ");}catch(Exception ignored){return false;}
  }
  private static void erase(Path root)throws Exception{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
  public static void main(String[]args)throws Exception{
    if(!Files.isExecutable(Path.of("/usr/bin/python3"))||!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")){
      System.out.println("SimpleStrixClientTest: 0 checks passed (POSIX fake-process fixture unavailable; native Strix not run)");return;
    }
    Path home=Files.createTempDirectory("simple-strix-client-test-");Path work=home.resolve("owned");
    Process unrelated=null;
    try(SimpleStrixClient client=new SimpleStrixClient(home,work)){
      config(home);wrapper(home);byte[] original=Files.readAllBytes(home.resolve(".strix/cli-config.json"));
      var info=client.inspectLocalModel();check(info.model().equals("openai/halogen-qwen3.8-flash-next"),"local model metadata only");
      check(info.baseUrl().equals("http://127.0.0.1:8731/v1"),"OPENAI_BASE_URL fallback validated");
      var first=plan(client,1,10);check(!first.context().contains(CAPTURED),"selected credentials removed from instructions");
      check(!first.toString().contains(KEY)&&!first.toString().contains(CAPTURED),"plan summary excludes credentials");
      var cancel=client.execute(first,()->false,null);check(cancel.status().equals("cancelled"),"default-cancel result");
      check(!Files.exists(home.resolve("last-run.json"))&&!Files.exists(work),"approval rejection starts zero fake processes and creates no run");
      check(client.execute(first,null,null).status().equals("cancelled"),"missing approval cancels");
      for(String target:List.of("https://user:pass@example.invalid","https://example.invalid/path","https://example.invalid/?token=x","https://example.invalid/#x","https://example.invalid\r\nX:yes","file:///tmp/x","https://example.invalid:0","https://example.invalid."))rejected(()->client.plan(target,"quick",1,10,""),"reject ambiguous/outside-origin target");
      rejected(()->client.plan(home.resolve(".strix/libexec/strix").toString(),"https://example.invalid","quick",1,10,""),"reject direct libexec bypass");
      rejected(()->client.plan("https://example.invalid","shell",1,10,""),"reject arbitrary scan mode");
      rejected(()->client.plan("https://example.invalid","quick",0,10,""),"bound agent turns");
      rejected(()->client.plan("https://example.invalid","quick",1,9,""),"bound elapsed timeout");
      config(home,"https://api.example.invalid/v1","openai/halogen-qwen3.8-flash-next","chat_completions");rejected(client::inspectLocalModel,"reject public model endpoint");
      config(home,"http://127.0.0.1:8731/v1","openai/other-model","chat_completions");rejected(client::inspectLocalModel,"reject unexpected local model");
      config(home,"http://127.0.0.1:8731/v1","openai/halogen-qwen3.8-flash-next","responses");rejected(client::inspectLocalModel,"reject wrong local API type");config(home);
      var changed=plan(client,1,10);rejected(()->client.execute(changed,()->{try{config(home,"http://127.0.0.1:9999/v1","openai/halogen-qwen3.8-flash-next","chat_completions");}catch(Exception e){throw new RuntimeException(e);}return true;},null),"revalidate frozen config after approval");
      check(!client.busy(),"failed preparation releases busy state");config(home);
      var result=client.execute(plan(client,1,10),()->true,null);check(result.status().equals("completed")&&result.exitCode()==0,"approved wrapper process completes");
      var run=observed(home);check(!run.get("args").toString().contains(KEY)&&!run.get("args").toString().contains(CAPTURED),"argv contains no key or captured credential");
      check(((Number)run.get("configMode")).intValue()==384&&((Number)run.get("instructionMode")).intValue()==384&&((Number)run.get("workMode")).intValue()==448,"private runtime config/instructions/workdir permissions");
      check(!Boolean.TRUE.equals(run.get("providerAliasesInherited")),"provider environment aliases cleared");
      Map<?,?> limits=(Map<?,?>)run.get("env");
      check(Boolean.TRUE.equals(limits.get("STRIX_CONTEXT_AUTO_COMPACT"))&&Boolean.FALSE.equals(limits.get("STRIX_TELEMETRY")),"preserve boolean operational settings");
      check(((Number)limits.get("LLM_TIMEOUT")).doubleValue()==15.5&&((Number)limits.get("STRIX_CONTEXT_AUTO_COMPACT_BUFFER_TOKENS")).intValue()==8192&&limits.get("STRIX_TOOL_OUTPUT_MAX_TOKENS").equals("8192"),"preserve numeric and string operational limits");
      check(!limits.containsKey("LLM_EXTRA_HEADERS")&&!limits.containsKey("LLM_EXTRA_BODY")&&!limits.containsKey("DEDUPE_LLM_API_BASE")&&!limits.containsKey("STRIX_DEDUPE_LLM"),"exclude extra headers/body and dedupe provider config");
      check(!run.get("instruction").toString().contains(KEY)&&!run.get("instruction").toString().contains(CAPTURED),"private instructions remain redacted");
      check(!result.logTail().contains(KEY)&&!result.logTail().contains(CAPTURED)&&result.logTail().contains("[REDACTED]"),"redact known credentials across output chunks");
      check(result.logTail().contains("8192 true"),"operational token count is not mistaken for credential");
      check(!Files.exists(result.workDirectory().resolve("runtime-config.json"))&&!Files.exists(result.workDirectory().resolve("instructions.txt")),"delete per-run sensitive inputs on completion");
      check(Arrays.equals(original,Files.readAllBytes(home.resolve(".strix/cli-config.json"))),"native persistence leaves global config unchanged");
      check(result.reports().size()==5&&result.reports().stream().noneMatch(p->p.getFileName().toString().equals("linked.md")),"discover only owned regular report files");
      check(result.reports().stream().map(p->p.getFileName().toString()).toList().equals(List.of("penetration_test_report.md","vulnerabilities.json","vulnerabilities.csv","report.md","metadata.json")),"final Markdown precedes vulnerability JSON/CSV and intermediate metadata in stable report order");
      check(!client.previewReport(result.reports().get(0)).contains(KEY)&&!client.previewReport(result.reports().get(0)).contains(CAPTURED),"report preview credentials redacted");
      rejected(()->client.checkedReport(home.resolve("outside.md")),"reject unrelated prior report path");
      Path report=result.reports().get(0);Files.delete(report);Files.createSymbolicLink(report,home.resolve("outside.md"));rejected(()->client.previewReport(report),"reject report replaced with symlink");
      var bounded=client.execute(plan(client,3,10),()->true,null);check(bounded.logTail().length()<=SimpleStrixClient.LOG_LIMIT&&bounded.logTail().endsWith("END\n"),"bound stdout tail while draining full process output");
      check(!bounded.dockerCleanupVerified(),"never claim Docker residual cleanup");
      var chat=client.plan(home.resolve(".strix/bin/strix").toString(),"https://example.invalid","quick",1,10,"",SimpleStrixClient.Provider.CHATGPT_SUBSCRIPTION,"chatgpt/test-model");
      check(!client.inspectProvider(SimpleStrixClient.Provider.CHATGPT_SUBSCRIPTION).supported(),"unsigned native subscription unavailable");
      rejected(()->client.execute(chat,()->true,null),"unsigned subscription blocks scan");
      Files.writeString(home.resolve("signed-in"),"fake");check(client.inspectProvider(SimpleStrixClient.Provider.CHATGPT_SUBSCRIPTION).supported(),"native subscription status accepted without exposing account output");
      var chatResult=client.execute(chat,()->true,null);check(chatResult.status().equals("completed"),"signed native subscription uses same wrapper");
      Map<?,?> chatEnv=(Map<?,?>)observed(home).get("env");check(chatEnv.get("STRIX_LLM").equals("chatgpt/test-model")&&!chatEnv.containsKey("LLM_API_KEY")&&!chatEnv.containsKey("LLM_API_BASE")&&!chatEnv.containsKey("STRIX_API_TYPE"),"subscription run config contains no local key/base/type");
      check(Arrays.equals(original,Files.readAllBytes(home.resolve(".strix/cli-config.json"))),"subscription selection does not change global model");
      byte[] saved=Files.readAllBytes(home.resolve(".strix/cli-config.json"));Files.delete(home.resolve(".strix/cli-config.json"));
      var noLocalConfig=client.plan(home.resolve(".strix/bin/strix").toString(),"https://example.invalid","quick",1,10,"",SimpleStrixClient.Provider.CHATGPT_SUBSCRIPTION,"chatgpt/test-model");
      check(client.execute(noLocalConfig,()->true,null).status().equals("completed"),"subscription can run without Halogen global config");
      Files.write(home.resolve(".strix/cli-config.json"),saved);
      Files.deleteIfExists(home.resolve("last-run.json"));
      unrelated=new ProcessBuilder("/usr/bin/python3","-c","import time;time.sleep(30)").start();
      ExecutorService executor=Executors.newSingleThreadExecutor();
      try{
        Future<SimpleStrixClient.Result> pending=executor.submit(()->client.execute(plan(client,2,10),()->true,null));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!Files.exists(home.resolve("last-run.json"))&&System.nanoTime()<until)Thread.sleep(20);
        check(client.busy(),"one active wrapper process tracked");rejected(()->client.execute(plan(client,1,10),()->true,null),"reject concurrent scans");
        client.cancel();var stopped=pending.get(6,TimeUnit.SECONDS);check(stopped.status().equals("cancelled"),"cancel reports cancelled rather than failed");
        check(unrelated.isAlive(),"cancel preserves unrelated process");check(!client.busy(),"cancel releases active state");
      }finally{executor.shutdownNow();}
      Files.deleteIfExists(home.resolve("last-run.json"));ExecutorService stubbornExecutor=Executors.newSingleThreadExecutor();
      try{
        Future<SimpleStrixClient.Result> pending=stubbornExecutor.submit(()->client.execute(plan(client,4,10),()->true,null));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!Files.exists(home.resolve("last-run.json"))&&System.nanoTime()<until)Thread.sleep(20);
        long pid=((Number)observed(home).get("pid")).longValue();
        check(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"SIGTERM-ignoring owned wrapper is running before cancel");
        client.cancel();var forced=pending.get(8,TimeUnit.SECONDS);
        check(forced.status().equals("cancelled")&&!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"cancel waits for forced owned-wrapper termination");
        check(!client.busy(),"no owned process remains alive before allowing another start");
        check(client.execute(plan(client,1,10),()->true,null).status().equals("completed"),"new scan starts only after prior forced termination confirmed");
      }finally{stubbornExecutor.shutdownNow();}
      var timed=client.execute(plan(client,2,10),()->true,null);check(timed.status().equals("timed_out"),"elapsed deadline terminates owned wrapper");
      check(!Files.exists(timed.workDirectory().resolve("runtime-config.json")),"timeout removes private config");
      Map<String,String> aliases=new HashMap<>();for(String key:List.of("OPENAI_API_KEY","OPENAI_BASE_URL","LLM_API_BASE","LLM_API_TYPE","LLM_EXTRA_HEADERS","LLM_EXTRA_BODY","DEDUPE_LLM_API_BASE","STRIX_DEDUPE_LLM","STRIX_DOCKER_SANDBOX_NETWORK","ANTHROPIC_API_KEY","STRIX_LLM"))aliases.put(key,"x");aliases.put("PATH","p");aliases.put("HOME","h");SimpleStrixClient.clearProviderEnvironment(aliases);check(aliases.equals(Map.of("PATH","p","HOME","h")),"clear provider/extra/dedupe/network aliases without repurposing HOME or PATH");
    }finally{if(unrelated!=null)unrelated.destroyForcibly();erase(home);}
    System.out.println("SimpleStrixClientTest: "+checks+" checks passed (fake wrapper only; no Strix/model/target calls)");
  }
}
