#!/usr/bin/env python3
"""One synthetic structured-plan request using the actual 0.55.0 client; no target traffic."""
from pathlib import Path
import subprocess,shutil,os,json,hashlib,datetime
ROOT=Path(__file__).resolve().parents[1]
jar=ROOT/'downloads/extensions/0.55.0/burp-workbench-kr-0.55.0.jar'
if not jar.exists(): jar=ROOT/'project/burp-development-workspace/burp-workbench-kr/burp-workbench/dist/burp-workbench-kr-0.55.0.jar'
java=shutil.which('java') or str(Path.home()/'BurpSuite/jre/bin/java')
javac=shutil.which('javac') or str(Path(java).resolve().parent/'javac')
work=ROOT/'build/live-auto-llm';work.mkdir(parents=True,exist_ok=True)
source=work/'LiveAutoLlmProof.java'
source.write_text('''package kr.workbench;
import java.util.*;
public final class LiveAutoLlmProof {
 public static void main(String[] args) {
  Map<String,Object> out=new LinkedHashMap<>();out.put("targetHttpRequests",0);out.put("modelCallAttempts",1);out.put("syntheticInputOnly",true);
  try {
   var settings=new LocalLlmClient.Settings(LocalLlmClient.endpoint(LocalLlmClient.DEFAULT_URL),LocalLlmClient.DEFAULT_MODEL,1024,120);
   long began=System.nanoTime();
   var plan=new LocalLlmClient().plan(settings,"원본 ID: synthetic-auto-001\\nGET /profile HTTP/1.1\\nHost: example.invalid\\nCookie: [REDACTED]\\n\\nHTTP/1.1 200 OK\\nContent-Type: application/json\\n\\n{\\"owner\\":\\"Synthetic Alice\\"}\\n허가된 합성 테스트 계정 A/B의 조회 비교. 실제 대상 요청은 실행하지 않음.",new LocalLlmClient.Call());
   out.put("result","passed");out.put("action",plan.action());out.put("availabilityRisk",plan.availabilityRisk());out.put("reason",plan.reason());out.put("elapsedMillis",(System.nanoTime()-began)/1000000);
  } catch(Exception e) {out.put("result","failed");out.put("errorType",e.getClass().getSimpleName());out.put("error",Objects.toString(e.getMessage(),""));}
  System.out.println(MetadataExport.json(out));
 }
}
''',encoding='utf-8')
try:
    digest=hashlib.sha256(jar.read_bytes()).hexdigest()
    subprocess.run([javac,'--release','17','-encoding','UTF-8','-cp',str(jar),'-d',str(work),str(source)],check=True)
    done=subprocess.run([java,'-Djava.awt.headless=true','-cp',os.pathsep.join((str(jar),str(work))),'kr.workbench.LiveAutoLlmProof'],capture_output=True,text=True,encoding='utf-8',timeout=135,check=True)
    result=json.loads(done.stdout.strip());result.update(jarSha256=digest,jar=str(jar),testedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())
    path=work/'result.json';path.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps(result,ensure_ascii=False,indent=2));print('결과:',path)
    raise SystemExit(0 if result['result']=='passed' else 1)
except (OSError,ValueError,subprocess.SubprocessError) as e:
    print('검증 준비/실행 실패:',e);raise SystemExit(1)
