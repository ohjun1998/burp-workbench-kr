#!/usr/bin/env python3
"""Build the standalone managed Workbench; original engine bundles are excluded."""
from shade_rhino import relocate, rename
from release_info import VERSION
import argparse, pathlib, os, hashlib, json, re, subprocess, shutil, zipfile, urllib.request, sys
if hasattr(sys.stdout,"reconfigure"):sys.stdout.reconfigure(encoding="utf-8")
root=pathlib.Path(__file__).resolve().parent
p=argparse.ArgumentParser();p.add_argument('--compiler',type=pathlib.Path);p.add_argument('--java',default='java');p.add_argument('--javac',default=None);p.add_argument('--test-java',default=None);p.add_argument('--test',action='store_true');a=p.parse_args()
lock=json.loads((root/'engine-lock.json').read_text());order=lock['order'].split(',')
def sha(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
def download(url,destination,expected=None):
 destination.parent.mkdir(parents=True,exist_ok=True)
 with urllib.request.urlopen(url,timeout=60)as response:destination.write_bytes(response.read())
 if expected and sha(destination)!=expected:destination.unlink();raise RuntimeError('checksum mismatch')
api=root/'deps/montoya-2026.jar';legacy=root/'deps/legacy-api.jar'
for path,url in [(api,'https://repo.maven.apache.org/maven2/net/portswigger/burp/extensions/montoya-api/2026.7/montoya-api-2026.7.jar'),(legacy,'https://repo.maven.apache.org/maven2/net/portswigger/burp/extender/burp-extender-api/2.3/burp-extender-api-2.3.jar')]:
 if not path.exists():download(url,path)
 expected=lock.get('build.'+path.name+'.sha')
 if expected and sha(path)!=expected:raise RuntimeError('API checksum mismatch')
rhino=root/'deps/rhino.jar'
rhino_sha='12dbb224cea124a5d32968722e8eb161c967bf15f3c9a795d1b05592b1147975'
if not rhino.exists():download('https://repo.maven.apache.org/maven2/org/mozilla/rhino/1.9.1/rhino-1.9.1.jar',rhino,rhino_sha)
if sha(rhino)!=rhino_sha:raise RuntimeError('Rhino checksum mismatch')
brotli=root/'deps/brotli-dec-0.1.2.jar'
brotli_sha='615c0c3efef990d77831104475fba6a1f7971388691d4bad1471ad84101f6d52'
if not brotli.exists():download('https://repo.maven.apache.org/maven2/org/brotli/dec/0.1.2/dec-0.1.2.jar',brotli,brotli_sha)
if sha(brotli)!=brotli_sha:raise RuntimeError('Brotli checksum mismatch')
mapper=root.parents[3]/'feature-mapper'
mapper_sources=[p for p in sorted((mapper/'src/main/java/kr/featuremapper').rglob('*.java')) if p.name!='FeatureMapperExtension.java']
if not mapper_sources:raise RuntimeError('Missing embedded Feature Mapper sources; use the complete Workbench source package')
classes=root/'build/classes'  ;shutil.rmtree(classes,ignore_errors=True);classes.mkdir(parents=True)
compiler=[a.java,'-jar',str(a.compiler.resolve()),'-17','-warn:none']if a.compiler else ([a.javac or shutil.which('javac'),'--release','17'] if a.javac or shutil.which('javac') else [a.java,'com.sun.tools.javac.Main','--release','17'])
cp=os.pathsep.join(map(str,[api,legacy,rhino,brotli]))
subprocess.run(compiler+['-encoding','UTF-8','-cp',cp,'-d',str(classes)]+[str(p)for p in sorted((root/'src/main/java').rglob('*.java'))]+[str(p)for p in mapper_sources],check=True)
output=root/f'dist/burp-workbench-kr-{VERSION}.jar';output.parent.mkdir(exist_ok=True)
temporary_output=output.with_suffix('.building')
class ReleaseZip(zipfile.ZipFile):
 def writestr(self,name,data,*args,**kwargs):
  info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
  return super().writestr(info,data)
 def write(self,path,name):self.writestr(str(name),pathlib.Path(path).read_bytes())
with ReleaseZip(temporary_output,'w',zipfile.ZIP_DEFLATED)as z:
 z.writestr('META-INF/MANIFEST.MF',f'Manifest-Version: 1.0\r\nImplementation-Title: Burp Workbench KR\r\nImplementation-Version: {VERSION}\r\n\r\n')
 for path in sorted(classes.rglob('*.class')):z.writestr(path.relative_to(classes).as_posix(),relocate(path.read_bytes()))
 with zipfile.ZipFile(rhino) as dependency:
  for name in sorted(dependency.namelist()):
   if name.startswith('org/mozilla/') and not name.endswith('/'):
    z.writestr(rename(name.encode()).decode(),relocate(dependency.read(name)))
 with zipfile.ZipFile(brotli) as dependency:
  for name in sorted(dependency.namelist()):
   if name.startswith('org/brotli/') and not name.endswith('/'):
    z.writestr(rename(name.encode()).decode(),relocate(dependency.read(name)))
 for path in sorted((root/'src/main/resources').rglob('*')):
  if path.is_file() and "engines" not in path.relative_to(root/"src/main/resources").parts:z.write(path,path.relative_to(root/'src/main/resources').as_posix())
 z.write(mapper/'LICENSE','META-INF/feature-mapper/LICENSE');z.write(mapper/'THIRD_PARTY_NOTICES.md','META-INF/feature-mapper/THIRD_PARTY_NOTICES.md')
 z.write(root/'LICENSE','LICENSE');z.write(root/'THIRD_PARTY_NOTICES.md','THIRD_PARTY_NOTICES.md')
temporary_output.replace(output)
print('Built',output,output.stat().st_size,'bytes',flush=True)
if a.test:
 tests=root/'build/tests';shutil.rmtree(tests,ignore_errors=True);tests.mkdir(parents=True)
 full_cp=cp+os.pathsep+str(classes)
 subprocess.run(compiler+['-encoding','UTF-8','-cp',full_cp,'-d',str(tests)]+[str(p)for p in sorted((root/'src/test/java').rglob('*.java'))],check=True)
 options=[a.test_java or a.java,'-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8']
 font=os.environ.get('WORKBENCH_QA_FONT')
 if font:options.append('-Dworkbench.qaFont='+font)
 full_cp=cp+os.pathsep+str(output)+os.pathsep+str(tests)
 reports=[];group_counts={}
 for name in ['BuildInfoTest','PassiveProvenanceTest','CaptureProvenanceTest','JsBatchContextTest','EvidenceHardeningTest','ResponseHardeningTest','ResponseAnalysisLimitsTest','LargeResponseJsonTest','AssessmentHardeningTest','MapperIntegrationTest','BankUpgradeTest','OssLogicUpgradeTest','JsUpgradeTest','TrafficSearchUpgradeTest','JsCompactUpgradeTest','JsArchiveWorkflowTest','IncrementalCoverageTest','CapturePipelineTest','EvidenceStorageTest','JsMemoryBudgetTest','StructuredCompareTest','WorkbenchTest','EnvironmentTest','EnvironmentPanelTest','EnvironmentBatchTest','EnvironmentDiscoveryTest','EnvironmentFeedbackTest','EnvironmentUpgradeTest','TextEncodingTest','DisplayWorkflowTest','PacketUiTest','PacketInspectorTest','RenderAuthUiTest','PassiveScanTest','LayoutPassiveWorkflowTest','JsAnalysisTest','JsDraftMappingTest','JsDetailsWorkflowTest','PacketStyleTest','TextAnalysisTest','JsResponseWorkflowTest','JsRepeaterWorkflowTest','JsAnalysisPanelTest','JsNavigationTest','JsHistoryTest','PayloadIntegrationTest','UiLayoutTest','AuthRecordTest','AuthWorkspaceTest','AuthLayoutFitTest','AuthRunSummaryTest','AuthResponseWorkflowTest','AuthAccuracyWorkflowTest','AuthResponseRefinementTest','CookieSessionProbeTest','UiCleanupTest','ActivityReconTest','TrafficScopeTest','TrafficScopeModesTest','TrafficCaptureRecoveryTest','PopupWorkflowTest','TargetCleanupTest','ReviewWorkflowTest','OtherImprovementsTest','AuditRepairsTest','BountyFeaturesTest','CompletenessTest','IntegrationEdgesTest','AccountTasksTest','ReviewConditionsTest','StoredFlowStagesTest','EvidenceLinkTest','EvidencePreviewTest','AssessmentUiOptimizationTest','UiPanelsOptimizationTest','JsUiOptimizationTest','FeatureGuideWorkflowTest','FolderNavigationTest','DiagnosticsWorkflowTest','AppearanceWorkflowTest']:
  r=subprocess.run(options+['-cp',full_cp,'kr.workbench.TestLauncher','kr.workbench.'+name],cwd=root,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
  text=r.stdout+r.stderr;(root/'build'/('test-'+name+'.txt')).write_text(text,encoding='utf-8');print(text,end='');reports.append(text)
  if r.returncode:raise SystemExit(r.returncode)
  summary=re.search(r'(\d+) checks passed',text)
  if not summary:raise RuntimeError('Missing counted test summary: '+name)
  group_counts[name]=int(summary.group(1))
 (root/f'build/test-results-{VERSION}.txt').write_text('\n'.join(reports),encoding='utf-8')

if a.test:
 result=subprocess.run(options+['-cp',full_cp,'kr.workbench.TestLauncher','kr.payloadrunner.EngineTest'],cwd=root,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
 report=result.stdout+result.stderr
 (root/'build/test-PayloadRunner.txt').write_text(report,encoding='utf-8')
 print(report,end='')
 if result.returncode:raise SystemExit(result.returncode)


if a.test:
 match=re.search(r'TEST_SUMMARY passed=(\d+) failed=0',report)
 if not match:raise RuntimeError('Missing successful payload test summary')
 (root/'build/test-summary.json').write_text(json.dumps({'version':VERSION,'groups':group_counts,'regressionGroups':len(group_counts),'regressionChecks':sum(group_counts.values()),'payloadHttpChecks':int(match.group(1))},indent=2)+'\n',encoding='utf-8')

