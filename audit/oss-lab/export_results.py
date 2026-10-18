#!/usr/bin/env python3
"""Publish only allowlisted, token-checked result files; never copy private profiles or logs."""
import argparse,collections,csv,hashlib,io,json,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--results',type=Path,required=True);p.add_argument('--metadata',type=Path,required=True);p.add_argument('--report',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--repo-results',type=Path,required=True);a=p.parse_args();r=a.results
j=json.loads((r/'extension-results.json').read_text());actors=json.loads((r/'private-state.json').read_text())['actors'];tokens=[x['token'] for x in actors]
base=[x for x in j['authComparisons'] if x['id'].startswith('AUTH')];configured=[x for x in j['authComparisons'] if x['id'].startswith('CONF')]
def metric(rows):return dict(collections.Counter(c['metric'] for row in rows for c in row['cells']))
summary={'workbenchVersion':j['extensionVersion'],'target':'OWASP Juice Shop 20.3.0','nativeBurp':j['burpVersion'],'nativeHarnessCompleted':j['completed'],'testedFeatureCount':10,'functionalChecks':dict(collections.Counter(c['status'] for c in j['checks'])),'browserChecks':dict(collections.Counter(c['status'] for c in json.loads((r/'browser-results.json').read_text())['checks'])),'defaultPolicyCells':metric(base),'targetSpecificRuleCells':metric(configured),'referenceCellsExcluded':True,'knownIssues':['F01: anonymous 200 empty user response causes one default-policy false positive; targeted regex resolves it','F02: three representative API paths absent from all retained main.js result versions','F03: SQLITE_ERROR absent from passive and payload error patterns; target-specific boolean SQL test does find a candidate','F04: stored readback is not connected after a 302 write; 201/200 control works'],'additionalUnmetGoal':'AU-08: configured response rules remove the false positive but two HTTP 400 cells stay REVIEW','crapiExecuted':False,'separateRegressionCheck':{'command':'python dev.py check','passed':True,'checks':2890},'rawCredentialsIncluded':False}
checks=io.StringIO(newline='');writer=csv.DictWriter(checks,fieldnames=['id','feature','title','status','reason','millis'],lineterminator='\n');writer.writeheader();writer.writerows(j['checks'])
files={'summary.json':json.dumps(summary,ensure_ascii=False,indent=2).encode(),'checks.csv':('\ufeff'+checks.getvalue()).encode(),'auth-comparisons.json':json.dumps({'default':base,'targetSpecificRules':configured},ensure_ascii=False,indent=2).encode(),'extension-results.json':(r/'extension-results.json').read_bytes(),'runtime-metadata.json':a.metadata.read_bytes()}
allowed=['browser-results.json','capture-statistics.json','environment-observation.json','environment-get-results.json','url-collection.json','js-main-analysis.json','js-main-versions.json','js-expected-paths.json','js-source-summary.json','payload-sqli-results.json','payload-sqli-context-results.json','response-findings.json','stored-flow-result.json','stored-flow-2xx-result.json','workflow-results.json','evidence-verification.json','activity-metadata.json','owned-processes-stopped.json','target-groundtruth.json']
for name in allowed:
 if (r/name).is_file():files['details/'+name]=(r/name).read_bytes()
files['workbench-redacted-evidence.zip']=(r/'workbench-redacted-evidence.zip').read_bytes()
files['README.txt']='''Workbench 0.44.0 / Juice Shop 20.3.0 실제 검증 자료\n\n읽는 순서: summary.json → checks.csv → auth-comparisons.json → details/\n기능 체크 33개 중 28개 충족, 5개 미충족. 브라우저 5개 통과.\n기본 권한표의 기준 계정 6칸을 제외한 18칸: TP 6, FP 1, TN 9, REVIEW 2, FN 0.\n대상 규칙 재검사 6칸: TN 4, REVIEW 2. FP는 해소됐으나 400 보류는 유지됩니다.\n서로 다른 집계를 합쳐 전체 정확도로 표현하지 마세요.\n\n실제 Burp Community Montoya HTTP와 Chromium을 사용했습니다.\ncrAPI·외부 OAST/BApps/아카이브·Web3/AI·Burp GUI 수동 조작은 미검증입니다.\n임시 사용자 자격 증명과 프로필, 원본 데이터베이스, 버전별 배포 JAR는 포함하지 않습니다.\n재현 방법은 reproducer/README_KR.md를 확인하세요.\n'''.encode()
root=Path(__file__).resolve().parent
for name in ['run_lab.py','NativeOssLab.java','browser_checks.mjs','offline-loopback.cjs','JsBundleProbe.java','README_KR.md']:
 if (root/name).exists():files['reproducer/'+name]=(root/name).read_bytes()
# Verify portable evidence recursively, including the nested Workbench-generated ZIP.
for name,b in files.items():
 if name.endswith('.zip'):
  with zipfile.ZipFile(io.BytesIO(b)) as z:
   for item in z.namelist():
    raw=z.read(item);assert all(t.encode() not in raw for t in tokens),(name,item,'raw token')
 else:assert all(t.encode() not in b for t in tokens),(name,'raw token')
a.repo_results.mkdir(parents=True,exist_ok=True)
for name in ['summary.json','checks.csv','auth-comparisons.json','runtime-metadata.json']:(a.repo_results/name).write_bytes(files[name])
for name in ['js-main-versions.json','js-expected-paths.json','environment-get-results.json','payload-sqli-results.json','payload-sqli-context-results.json','stored-flow-result.json','stored-flow-2xx-result.json','capture-statistics.json','owned-processes-stopped.json']:(a.repo_results/name).write_bytes(files['details/'+name])
files['report/'+a.report.name]=a.report.read_bytes();manifest={name:hashlib.sha256(b).hexdigest() for name,b in files.items()};files['SHA256SUMS.json']=json.dumps(manifest,ensure_ascii=False,indent=2).encode();a.out.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(a.out,'w',zipfile.ZIP_DEFLATED) as z:
 for name,b in files.items():z.writestr(name,b)
print(json.dumps({'zip':str(a.out),'entries':len(files),'bytes':a.out.stat().st_size,'rawTokensPresent':False,'summary':summary},ensure_ascii=False))
