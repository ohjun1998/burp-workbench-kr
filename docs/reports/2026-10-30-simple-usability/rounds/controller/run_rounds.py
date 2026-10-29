from pathlib import Path
from datetime import datetime, timezone
import hashlib, json, shutil, subprocess, sys, time

ROOT=Path(__file__).resolve().parent
REPO=Path('/workspace/burp-workbench-kr')
JAVA=REPO/'.dev-tools/jdk-17.0.21+9/bin/java'
JAVAC=JAVA.with_name('javac')
OUT=ROOT/'execution-v1'
STAGE=ROOT/'staging'
MODULES=('traffic','mapper','auth','js','environment','response.recon','payload','bounty')
PROBES=('reading','target','tables','urls','dashboard')
FINAL_SHA='ebda707fd762dcac91f641c7f149a6bf5e52865008d0f70e8861e502d8a5bbf1'

def now(): return datetime.now(timezone.utc).isoformat()
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def read(path): return json.loads(path.read_text())
def write(path,data):
 path.parent.mkdir(parents=True,exist_ok=True)
 temporary=path.with_suffix('.writing');temporary.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n');temporary.replace(path)
def run(command,log):
 log.parent.mkdir(parents=True,exist_ok=True)
 with log.open('w') as stream: subprocess.run([str(p) for p in command],cwd=REPO,stdout=stream,stderr=subprocess.STDOUT,check=True,timeout=600)
def probe(kind,jar,folder,label):
 run([sys.executable,REPO/'audit/simple-usability/run_probes.py','--probe',kind,'--jar',jar,'--java',JAVA,'--out',folder,'--phase',label],folder.with_suffix('.launcher.log'))
 meta=read(folder/'metadata.json')
 assert meta['status']=='completed' and meta['inputJarUnchanged'] and meta['runtimeIsolation']['privateRuntimeRemoved']
 assert meta['productJar']['sha256']==sha(jar)
 return read(folder/'results'/('summary.json' if kind in ('whole','dashboard') else 'result.json')),meta
def validate_whole(result):
 assert result['result']=='PASS' and result['failed']==0 and result['nativeBurp'] is False
 assert result['baseURL']=='http://127.0.0.1:18888' and result['engineHttpRequests']>0
 assert set(result['modules'])==set(MODULES)
 for name in MODULES:
  module=result['modules'][name]
  assert module['coverage'] is True and module['status']=='PASS' and module['actionCount']>0
  assert module['assertions'] and all(a['passed'] for a in module['assertions'])
def compare(number,before,after):
 if number==1:
  assert before['reselectionActions']>0 and after['reselectionActions']==0
  assert after['selectionPreservedAcrossRefreshes'] and after['additionalRequestsForReadingAndOriginal']==0
  return {'metric':'reselectionActions','before':before['reselectionActions'],'after':after['reselectionActions']}
 if number==2:
  assert before['staleAccountFailureObserved'] and not after['staleAccountFailureObserved']
  assert after['actionCount']<before['actionCount'] and after['recoveryActionCount']==0
  return {'metric':'explicit UI actions','before':before['actionCount'],'after':after['actionCount']}
 if number==3:
  assert before['allInvariantsPassed'] and after['allInvariantsPassed']
  assert before['scenarios']['same_snapshot']['modelEventCount']['median']>0
  assert after['scenarios']['same_snapshot']['modelEventCount']['median']==0
  assert after['additionalHTTPForUpdateSortAndOriginalActions']==0
  return {'metric':'same snapshot','before':{k:v for k,v in before['scenarios']['same_snapshot'].items() if k not in ('samples','warmupsExcludedFromSummary')},'after':{k:v for k,v in after['scenarios']['same_snapshot'].items() if k not in ('samples','warmupsExcludedFromSummary')}}
 if number==4:
  assert all(s['filterSets']==1 and s['retainedRows']==1000 and s['queryValuesRemainDistinct'] and s['sourceAndMethodProvenancePreserved'] and s['queryFilterTargetsExactOriginal'] and s['targetRequests']==0 for s in after['samples'])
  assert all(s['filterSets']>1 for s in before['samples'])
  return {'metric':'import 1000 URLs','before':{k:before[k] for k in ('medianMs','medianEdtAllocatedBytes','targetRequests')},'after':{k:after[k] for k in ('medianMs','medianEdtAllocatedBytes','targetRequests')},'filterSetsBefore':before['samples'][0]['filterSets'],'filterSetsAfter':after['samples'][0]['filterSets']}
 assert before['additional_explicit_scroll_required']>0 and after['additional_explicit_scroll_required']==0
 assert after['all_original_account_empty_checks_passed'] and after['all_automatic_refresh_scroll_checks_passed'] and after['target_http_calls']==0
 return {'metric':'additional explicit scrolling across 12 observations','before':before['additional_explicit_scroll_required'],'after':after['additional_explicit_scroll_required']}

if OUT.exists(): raise SystemExit('Fresh execution folder required; existing evidence is preserved')
OUT.mkdir();(OUT/'products').mkdir()
state={'definition':'Five complete before/improve/after rounds; all eight tools in each independent whole-workflow execution','startedAtUtc':now(),'completedRounds':0,'wholeWorkflowRuns':0,'rounds':[],'currentStep':'ready','nativeBurp':False,'earlierFocusedCasesArePreliminary':True,'afterRunsReusedAsNextBefore':False}
write(OUT/'state.json',state)
current=REPO/'simple/downloads/1.4.0/burp-workbench-simple-1.4.0.jar'
whole_source=sha(REPO/'audit/simple-usability/WholeWorkbenchWorkflowAudit.java')
try:
 for number,kind in enumerate(PROBES,1):
  folder=OUT/f'round-{number}';folder.mkdir()
  record={'round':number,'startedAtUtc':now(),'beforeJarSHA256':sha(current),'wholeHarnessSHA256':whole_source,'appliedPatch':f'changes/round-{number}/change.patch','completed':False}
  state['rounds'].append(record)
  def step(value):
   assert sha(REPO/'audit/simple-usability/WholeWorkbenchWorkflowAudit.java')==whole_source,'Whole harness changed during the round series'
   state['currentRound']=number;state['currentStep']=value;write(OUT/'state.json',state)
  step('before-whole')
  before_whole,before_meta=probe('whole',current,folder/'before-whole',f'round-{number}-before')
  validate_whole(before_whole);state['wholeWorkflowRuns']+=1
  step('before-focused-problem')
  before_focus,_=probe(kind,current,folder/'before-focus',f'round-{number}-before')
  step('apply-improvement')
  run([sys.executable,ROOT/'apply_round.py',number],folder/'apply.log')
  step('build-new-product')
  run([sys.executable,STAGE/'simple/dev.py','build','--java',JAVA,'--javac',JAVAC],folder/'build.log')
  version='1.4.1' if number==5 else '1.4.0'
  candidate=OUT/'products'/f'round-{number}-after.jar'
  shutil.copy2(STAGE/f'simple/burp-workbench/dist/burp-workbench-simple-{version}.jar',candidate);candidate.chmod(0o444)
  if number==5: assert sha(candidate)==FINAL_SHA,'Final staged source differs from the frozen final product'
  record['afterJarSHA256']=sha(candidate)
  step('after-focused-problem')
  after_focus,_=probe(kind,candidate,folder/'after-focus',f'round-{number}-after')
  record['improvement']=compare(number,before_focus,after_focus)
  step('after-whole')
  after_whole,after_meta=probe('whole',candidate,folder/'after-whole',f'round-{number}-after')
  validate_whole(after_whole);state['wholeWorkflowRuns']+=1
  assert before_meta['harnessSource']['sha256']==after_meta['harnessSource']['sha256']==whole_source
  record.update({'completed':True,'finishedAtUtc':now(),'beforeChecks':before_whole['passed'],'afterChecks':after_whole['passed'],'beforeHttp':before_whole['engineHttpRequests'],'afterHttp':after_whole['engineHttpRequests'],'beforeModules':list(before_whole['modules']),'afterModules':list(after_whole['modules']),'beforeWholeReused':False,'afterWholeReused':False})
  write(folder/'round-summary.json',record)
  state['completedRounds']=number;current=candidate
  step('round-complete')
 state['currentStep']='all-rounds-complete';state['finishedAtUtc']=now();write(OUT/'state.json',state)
 assert state['completedRounds']==5 and state['wholeWorkflowRuns']==10
 print(json.dumps({'completedRounds':5,'independentWholeWorkflowRuns':10,'moduleCompletions':80,'finalJarSHA256':sha(current)}))
except BaseException as error:
 state['failure']={'class':type(error).__name__,'step':state.get('currentStep'),'round':state.get('currentRound')};state['finishedAtUtc']=now();write(OUT/'state.json',state)
 raise
