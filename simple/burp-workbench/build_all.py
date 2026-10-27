#!/usr/bin/env python3
"""Build the isolated Workbench Simple; integrated engines are preserved."""
from shade_rhino import relocate, rename
from release_info import VERSION
import argparse, pathlib, os, hashlib, json, re, subprocess, shutil, zipfile, urllib.request, sys
if hasattr(sys.stdout,"reconfigure"):sys.stdout.reconfigure(encoding="utf-8")
root=pathlib.Path(__file__).resolve().parent
p=argparse.ArgumentParser();p.add_argument('--compiler',type=pathlib.Path);p.add_argument('--java',default='java');p.add_argument('--javac',default=None);p.add_argument('--test-java',default=None);p.add_argument('--test',action='store_true');p.add_argument('--test-group',action='append',default=[]);p.add_argument('--skip-payload',action='store_true');p.add_argument('--source-target',action='store_true',help='Explicit source/target 17 compilation without Java 17 API compatibility verification');a=p.parse_args()
if a.source_target:
 print('WARNING: Explicit --source-target mode uses -source 17 -target 17 on the selected JDK. Java 17 API compatibility is NOT verified; strict --release 17 remains the default.',flush=True)
toolchain_mode='explicit-source17-target17-api-unverified' if a.source_target else 'strict-release17'
source_options=['-source','17','-target','17'] if a.source_target else ['--release','17']
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
mapper=root.parent/'feature-mapper'
mapper_sources=[p for p in sorted((mapper/'src/main/java/kr/featuremapper').rglob('*.java')) if p.name!='FeatureMapperExtension.java']
if not mapper_sources:raise RuntimeError('Missing embedded Feature Mapper sources; use the complete Workbench source package')
classes=root/'build/classes'  ;shutil.rmtree(classes,ignore_errors=True);classes.mkdir(parents=True)
if a.compiler and a.source_target:raise SystemExit('--source-target requires the selected JDK compiler, not --compiler')
compiler=[a.java,'-jar',str(a.compiler.resolve()),'-17','-warn:none']if a.compiler else ([a.javac or shutil.which('javac')]+source_options if a.javac or shutil.which('javac') else [a.java,'com.sun.tools.javac.Main']+source_options)
cp=os.pathsep.join(map(str,[api,legacy,rhino,brotli]))
subprocess.run(compiler+['-encoding','UTF-8','-cp',cp,'-d',str(classes)]+[str(p)for p in sorted((root/'src/main/java').rglob('*.java'))]+[str(p)for p in mapper_sources],check=True)
output=root/f'dist/burp-workbench-simple-{VERSION}.jar';output.parent.mkdir(exist_ok=True)
temporary_output=output.with_suffix('.building')
class ReleaseZip(zipfile.ZipFile):
 def writestr(self,name,data,*args,**kwargs):
  info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
  return super().writestr(info,data)
 def write(self,path,name):self.writestr(str(name),pathlib.Path(path).read_bytes())
with ReleaseZip(temporary_output,'w',zipfile.ZIP_DEFLATED)as z:
 z.writestr('META-INF/MANIFEST.MF',f'Manifest-Version: 1.0\r\nImplementation-Title: Burp Workbench Simple KR\r\nImplementation-Version: {VERSION}\r\n\r\n')
 inputs=[*sorted((root/'src/main/java').rglob('*.java')),*mapper_sources]
 inputs += [p for p in (root/'src/main/resources').rglob('*') if p.is_file() and 'engines' not in p.relative_to(root/'src/main/resources').parts]
 inputs += [root/name for name in ['build_all.py','release_info.py','shade_rhino.py','engine-lock.json','LICENSE','THIRD_PARTY_NOTICES.md']]
 inputs += [api,legacy,rhino,brotli,mapper/'LICENSE',mapper/'THIRD_PARTY_NOTICES.md']
 z.writestr('META-INF/simple-build.json',json.dumps({'toolchainMode':toolchain_mode,'java17ApiCompatibilityVerified':not a.source_target},sort_keys=True)+'\n')
 z.writestr('META-INF/simple-source.json',json.dumps({p.relative_to(root.parent).as_posix():sha(p) for p in sorted(inputs)},sort_keys=True,indent=2)+'\n')
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
 reports=[];group_counts={};failures=[]
 excluded={
  'UiCleanupTest':'Asserts removed WorkContext header, exact 15-item auxiliary menu and absence of direct next-step buttons; replaced by SimpleWorkflowTest and Simple layout rendering',
  'FolderNavigationTest':'Asserts removed folder sidebar, sidebar resize/hide controls and NEXT badge; replaced by SimpleWorkflowTest four-stage routing and retained-input checks',
  'AppearanceWorkflowTest':'Asserts removed folder JTree renderer, orange icons and sidebar row geometry; theme/traffic/capture/input checks replaced by SimpleAppearanceTest'
 }
 adapted={
  'BuildInfoTest':'Assert Simple artifact filename while retaining packaged JAR manifest/runtime consistency checks',
  'NextIsolationTest':'Assert Simple display and storage namespace; preserve both stable and Next preference/archive fixtures',
  'FeatureGuideWorkflowTest':'Use actual Simple options help item and current bounty title/four-stage introductory guidance; retain routing/input/no-HTTP assertions',
  'DiagnosticsWorkflowTest':'Use actual consolidated Simple options/logs menu; retain bounded concurrency, redaction, error selection/clear, lifecycle and no-HTTP checks',
  'UiPanelsOptimizationTest':'Replace removed serial-workflow viewport assertions with evidence-route redirect and unconstructed auxiliary editors; retain comparison, evidence, response, worker and image-state checks',
  'AuditRepairsTest':'Use the same original community JS fixture copied into Simple test resources, so regressions run independently of the original directory layout',
  'AuthSetupBundlesTest':'Assert exact BuildInfo.storageKey project UUID key instead of the historical Next prefix; retain bundle exchange, disk integrity, capacity and project-isolation checks',
  'AuthLayoutFitTest':'Assert four named Simple controls instead of the former six-control toolbar; retain table minimum widths, viewport sizing, wrapping and account-detail layout checks',
  'LayoutPassiveWorkflowTest':'Use retained explicit passive route instead of former traffic menu position zero; preserve collection fixture, target/packet isolation, shared layout, capacity and unload checks',
  'TrafficScopeTest':'Use actual primary SCOPE button/editor instead of removed duplicate context submenu; preserve scope-rule operations and verify coverage/environment through admitted SCOPE traffic rather than removed selected-packet backfill'
 }
 test_groups=['EvidenceDraftsTest','EditHistoryTest','Appearance0534Test','WorkspaceAppearanceProjectIsolationTest','ComparisonResponsiveness0534Test','JobMonitorResponsiveness0534Test','TrafficStabilization0534Test','NavigationLifecycle0534Test','EvidenceStabilizationTest','StabilizationIntegration0534Test','BuildInfoTest','TrafficUsability0532Test','EvidenceEditingTest','AuthInlineValidationTest','Navigation0532Test','ResponseDifferenceTest','ResponseDifferenceUiTest','BackgroundJobsTest','GettingStartedPanelTest','UsabilityIntegration0532Test','BundleChangeSummaryTest','RemainingWorkUsabilityTest','AuthUsability0531Test','TrafficSearchPresetsTest','TrafficSearchConditionsUiTest','WorkspaceResumeTest','OriginalRequestIdsTest','JsArchivePagingTest','AuthSetupWorkflowTest','AuthSetupBundlesTest','ExecutionLink052Test','RemainingChecks053Test','MapperTaskDraft052Test','TrafficIncrementalSearchTest','TrafficSearchLifecycleTest','ResponseViewUpdateTest','ResponseBudgetTest','NextIsolationTest','TrafficCancellationTest','RuntimeMetricsTest','WorkContextTest','AuthSetupSuggestionsTest','AnalysisRetryTest','CompletionEvidenceNextTest','PassiveProvenanceTest','CaptureProvenanceTest','JsBatchContextTest','EvidenceHardeningTest','ResponseHardeningTest','ResponseAnalysisLimitsTest','LargeResponseJsonTest','AssessmentHardeningTest','MapperIntegrationTest','BankUpgradeTest','OssLogicUpgradeTest','JsUpgradeTest','TrafficSearchUpgradeTest','JsCompactUpgradeTest','JsArchiveWorkflowTest','IncrementalCoverageTest','CapturePipelineTest','EvidenceStorageTest','JsMemoryBudgetTest','StructuredCompareTest','WorkbenchTest','EnvironmentTest','EnvironmentPanelTest','EnvironmentBatchTest','EnvironmentDiscoveryTest','EnvironmentFeedbackTest','EnvironmentUpgradeTest','TextEncodingTest','DisplayWorkflowTest','PacketUiTest','PacketInspectorTest','RenderAuthUiTest','PassiveScanTest','LayoutPassiveWorkflowTest','JsAnalysisTest','JsDraftMappingTest','JsDetailsWorkflowTest','PacketStyleTest','TextAnalysisTest','JsResponseWorkflowTest','JsRepeaterWorkflowTest','JsAnalysisPanelTest','JsNavigationTest','JsHistoryTest','PayloadIntegrationTest','UiLayoutTest','AuthRecordTest','AuthWorkspaceTest','AuthLayoutFitTest','AuthRunSummaryTest','AuthResponseWorkflowTest','AuthAccuracyWorkflowTest','AuthResponseRefinementTest','CookieSessionProbeTest','UiCleanupTest','ActivityReconTest','TrafficScopeTest','TrafficScopeModesTest','TrafficCaptureRecoveryTest','PopupWorkflowTest','TargetCleanupTest','ReviewWorkflowTest','OtherImprovementsTest','AuditRepairsTest','BountyFeaturesTest','CompletenessTest','IntegrationEdgesTest','AccountTasksTest','ReviewConditionsTest','StoredFlowStagesTest','EvidenceLinkTest','EvidencePreviewTest','AssessmentUiOptimizationTest','UiPanelsOptimizationTest','JsUiOptimizationTest','FeatureGuideWorkflowTest','FolderNavigationTest','DiagnosticsWorkflowTest','AppearanceWorkflowTest']
 test_groups=[name for name in test_groups if name not in excluded]
 test_groups+=sorted(p.stem for p in (root/'src/test/java/kr/workbench').glob('Simple*Test.java') if p.stem not in test_groups)
 if a.test_group:
  unknown=set(a.test_group)-set(test_groups)
  if unknown:raise SystemExit('Unknown test groups: '+', '.join(sorted(unknown)))
  test_groups=a.test_group
 for name in test_groups:
  test_home=root/'build/test-home'/name;test_home.mkdir(parents=True,exist_ok=True)
  r=subprocess.run(options+['-Duser.home='+str(test_home),'-cp',full_cp,'kr.workbench.TestLauncher','kr.workbench.'+name],cwd=root,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
  text=r.stdout+r.stderr;(root/'build'/('test-'+name+'.txt')).write_text(text,encoding='utf-8');print(text,end='');reports.append(text)
  if r.returncode:
   failures.append(name);continue
  summary=re.search(r'(\d+) checks passed',text)
  if not summary:raise RuntimeError('Missing counted test summary: '+name)
  group_counts[name]=int(summary.group(1))
 (root/f'build/test-results-{VERSION}.txt').write_text('\n'.join(reports),encoding='utf-8')

if a.test and not a.skip_payload:
 test_home=root/'build/test-home/PayloadRunner';test_home.mkdir(parents=True,exist_ok=True)
 result=subprocess.run(options+['-Duser.home='+str(test_home),'-cp',full_cp,'kr.workbench.TestLauncher','kr.payloadrunner.EngineTest'],cwd=root,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
 report=result.stdout+result.stderr
 (root/'build/test-PayloadRunner.txt').write_text(report,encoding='utf-8')
 print(report,end='')
 if result.returncode:failures.append('PayloadRunner')


if a.test:
 match=None if a.skip_payload else re.search(r'TEST_SUMMARY passed=(\d+) failed=0',report)
 if not a.skip_payload and not match and 'PayloadRunner' not in failures:failures.append('PayloadRunner summary missing')
 summary={'version':VERSION,'toolchainMode':toolchain_mode,'java17ApiCompatibilityVerified':not a.source_target,'groups':group_counts,'regressionGroups':len(group_counts),'regressionChecks':sum(group_counts.values()),'payloadHttpChecks':int(match.group(1)) if match else 0,'excludedGroups':excluded,'adaptedGroups':adapted,'failedGroups':failures,'result':'failed' if failures else 'passed','subset':bool(a.test_group)}
 (root/'build/test-summary.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
 print('SIMPLE_CHECK_SUMMARY',json.dumps(summary),flush=True)
 if failures:raise SystemExit(1)

