#!/usr/bin/env python3
"""Check the shipped LLM client against a local model with one synthetic request."""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import os
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA_SOURCE = r'''
package kr.workbench;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
public final class LiveLlmProof {
  public static void main(String[] args) {
    Map<String,Object> report = new LinkedHashMap<>();
    report.put("syntheticInputOnly", true);
    report.put("targetHttpRequests", 0);
    report.put("modelCallAttempts", 0);
    try {
      var settings = new LocalLlmClient.Settings(LocalLlmClient.endpoint(args[0]), args[1], 1024, Integer.parseInt(args[2]));
      report.put("endpoint", settings.endpoint().toString()); report.put("model", settings.model());
      report.put("modelCallAttempts", 1);
      var answer = new LocalLlmClient().analyze(settings, "연결 검사: 아래 합성 관측의 한계를 한국어로 짧게 설명",
          "합성 원본 ID: local-smoke-001\nREQUEST\nGET /profile HTTP/1.1\nHost: example.invalid\n\n"
          + "RESPONSE\nHTTP/1.1 200 OK\nContent-Type: application/json\n\n{\"displayName\":\"Synthetic Alice\"}\n"
          + "이 자료만으로 타인 정보 노출을 확정할 수 있는가? 실제 검증은 수행하지 않았음.", new LocalLlmClient.Call());
      report.put("result", "passed"); report.put("elapsedMillis", answer.elapsedMillis());
      report.put("finishReason", answer.finishReason()); report.put("answerCharacters", answer.text().length());
      report.put("answerSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(answer.text().getBytes(StandardCharsets.UTF_8))));
      report.put("answerPreview", answer.text().substring(0, Math.min(400,answer.text().length())));
      report.put("meaning", "실제 배포 클라이언트의 로컬 모델 HTTP 응답 수신 확인; 분석 정확도와 native Burp GUI 검증은 별도");
    } catch (Exception e) {
      report.put("result", "failed"); report.put("errorType", e.getClass().getSimpleName());
      report.put("error", Objects.toString(e.getMessage(), "LLM check failed"));
    }
    System.out.println(MetadataExport.json(report));
  }
}
'''

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--endpoint', default='http://127.0.0.1:8731/v1')
    p.add_argument('--model', default='halogen-qwen3.8-flash-next')
    p.add_argument('--timeout', type=int, default=120)
    p.add_argument('--java'); p.add_argument('--javac')
    p.add_argument('--compile-only', action='store_true')
    p.add_argument('--out', type=Path, default=ROOT/'build/live-llm/result.json')
    a = p.parse_args()
    if not 10 <= a.timeout <= 300:
        p.error('--timeout must be 10..300 seconds')
    jar = ROOT/'downloads/extensions/0.54.0/burp-workbench-kr-0.54.0.jar'
    manifest = json.loads((jar.parent/'manifest.json').read_text())
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    if digest != manifest['files'][jar.name]:
        raise RuntimeError('배포 JAR 체크섬이 manifest와 다릅니다. 검증을 중단합니다.')
    java = a.java or shutil.which('java') or str(Path.home()/'BurpSuite/jre/bin/java')
    javac = a.javac or str(Path(java).resolve().parent/'javac')
    work = ROOT/'build/live-llm'; work.mkdir(parents=True, exist_ok=True)
    source = work/'LiveLlmProof.java'; source.write_text(JAVA_SOURCE, encoding='utf-8')
    subprocess.run([javac, '--release','17','-encoding','UTF-8','-cp',str(jar),'-d',str(work),str(source)],check=True)
    if a.compile_only:
        print('배포 JAR 기반 검증 도구 컴파일 통과 · 모델 호출 없음')
        return 0
    command = [java,'-Djava.awt.headless=true','-cp',os.pathsep.join([str(jar),str(work)]),'kr.workbench.LiveLlmProof',a.endpoint,a.model,str(a.timeout)]
    try:
        done = subprocess.run(command, capture_output=True, text=True, encoding='utf-8',timeout=a.timeout+15)
        if done.returncode:
            result = {'result':'failed','error':'Java 검증 프로세스 오류','detail':done.stderr[-2000:]}
        else:
            result = json.loads(done.stdout.strip())
    except subprocess.TimeoutExpired:
        result = {'result':'failed','error':'외부 검증 프로세스 시간 제한 초과'}
    result.update({'testedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(), 'jarSha256':digest,'java':java})
    a.out.parent.mkdir(parents=True,exist_ok=True)
    a.out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))
    print('결과 파일:', a.out)
    return 0 if result['result']=='passed' else 1

if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError,ValueError,RuntimeError,subprocess.CalledProcessError) as e:
        print('검증 준비 실패:',e,file=sys.stderr)
        raise SystemExit(1)
