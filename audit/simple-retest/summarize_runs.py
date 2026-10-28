#!/usr/bin/env python3
"""Write a counts-only public integrity summary from the private real-app retest corpus."""
import argparse,collections,hashlib,json
from datetime import datetime,timezone
from pathlib import Path

SITES=['juice-shop','benchmarkjava','vulnerableapp','oopssec','dvbank','vulnbank','loop']
FINAL_SHA='9b50905cebb5485274e986445aefb198dbc56c3d0d9cb1bfeef69a0c4d0f969d'
BUNDLE_SHA='a029a0ee1822c924fe7eef8aa9316e4c196ff54f3279c0286abb472971b375a4'
PUBLIC_FIELDS=('name','version','sourceURL','repository','sourceCommit','archiveURL','archiveUrl','archiveSHA256','archiveSha256','warSHA256','builtArtifactSha256','containerImage','coverage','dbImage','database','deployment','limitations','sourceIntegrity','originalSourceCheck','sourceModified')

def request_headers(path):
 text=path.read_bytes().split(b'\r\n\r\n',1)[0].decode('utf-8',errors='replace')
 return {line.split(':',1)[0].lower():line.split(':',1)[1].strip() for line in text.split('\r\n')[1:] if ':' in line}

def read_case(path):
 data=json.loads(path.read_text());runtime=json.loads(path.with_name('runtime.json').read_text());transport=data.get('transport',[])
 success=sum(row.get('status',0)>0 for row in transport);errors=len(transport)-success
 config_path=Path(runtime['config']);config=json.loads(config_path.read_text());snapshot=path.with_name('private-config.json')
 profile_by_label={row['label']:row for row in config['profiles']};credential_requests=0;credentials_match=True
 for record in transport:
  headers=request_headers(path.parent/record['requestFile']);profile=profile_by_label.get(record['account'])
  if profile is None:credentials_match=False;continue
  expected={key.lower():value for key,value in profile.get('headers',{}).items()}
  if expected:credential_requests+=1
  credentials_match &= all(headers.get(key)==value for key,value in expected.items())
  credentials_match &= all(key in expected or key not in headers for key in ('cookie','authorization','proxy-authorization'))
 expected_parameters={'budget':config.get('budget',200),'depth':config.get('depth',2),'delayMillis':config.get('delayMillis',0),'timeoutMillis':config.get('timeoutMillis',5000),'maxBodyBytes':config.get('maxBodyBytes',5*1024*1024),'katanaSeconds':config.get('katanaSeconds',30),'katanaPages':config.get('katanaPages',50),'auditWallSeconds':config.get('wallSeconds',config.get('katanaSeconds',30)+75),'katanaProfile':config.get('katanaProfile',config['profiles'][-1]['id'])}
 actual_parameters=data.get('parameters',{})
 parameters_match=(data['budget']==expected_parameters['budget'] and data['startURL']==config['startURL'] and data['scopeURL']==config['scopeURL'])
 if actual_parameters:parameters_match &= all(actual_parameters.get(key)==value for key,value in expected_parameters.items())
 config_original=config_path.stat().st_mtime*1000<=data['startedAt']
 if snapshot.exists():parameters_match &= snapshot.read_bytes()==config_path.read_bytes()
 public={'siteId':data['siteId'],'scenario':data['scenario'],'state':data['state'],'attempts':data['actualTransportRequests'],'engineSent':data['sent'],'responseSuccess':success,'transportErrors':errors,'httpStatuses':dict(collections.Counter(str(row.get('status',0)) for row in transport)),'transportErrorTypes':dict(collections.Counter(row.get('error','unknown') for row in transport if not row.get('status',0))),'completedQueueItems':data['completed'],'pending':data['pending'],'excluded':data['excluded'],'failed':data['failed'],'partial':data['partial'],'wallLimitReached':bool(data.get('wallLimitReached',False)),'auditStopReason':data.get('auditStopReason',''),'katanaState':data['katana']['state'],'katanaRecords':data['katana']['records'],'productExporterAvailable':data['productReport']['available'],'productExporterError':data['productReport']['error'],'jarSHA256':data['jarSHA256'],'jarUnchanged':runtime['jarUnchanged'],'strictJava17':runtime['strictJava17'],'parameters':expected_parameters,'integrity':{'attemptCountMatchesTransportRecords':data['actualTransportRequests']==len(transport),'engineCounterMatchesActualAttempts':data['sent']==data['actualTransportRequests'],'budgetRespected':data['sent']<=data['budget'],'readMethodsOnly':all(row['method'] in ('GET','HEAD') for row in transport),'requestCredentialSnapshotsMatchPrivateConfig':credentials_match,'configuredCredentialRequestsChecked':credential_requests,'requestedParametersMatch':bool(parameters_match),'configModificationPredatesRun':config_original,'inputConfigSnapshotStored':snapshot.exists()},'findingCounts':dict(collections.Counter(row['category'] for row in data['findings']))}
 return data,public

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',type=Path,default=Path('/workspace/simple-sites'));parser.add_argument('--out',type=Path,default=Path(__file__).with_name('results-summary.json'));args=parser.parse_args()
 raw={};stages={}
 for version in ('1.3.0','1.3.1'):
  rows=[]
  for site in SITES:
   for scenario in ('root','direct' if site in ('benchmarkjava','vulnerableapp') else 'protected'):
    path=args.root/'results'/version/site/scenario/'run.json';data,row=read_case(path);raw[(version,site,scenario)]=data;rows.append(row)
  stages[version]={'runs':rows,'runCount':len(rows),'attempts':sum(row['attempts'] for row in rows),'responseSuccess':sum(row['responseSuccess'] for row in rows),'transportErrors':sum(row['transportErrors'] for row in rows),'rootAttempts':sum(row['attempts'] for row in rows if row['scenario']=='root'),'directContrastAttempts':sum(row['attempts'] for row in rows if row['scenario']!='root'),'states':dict(collections.Counter(row['state'] for row in rows)),'jarSHA256':sorted({row['jarSHA256'] for row in rows})}
 before=raw[('1.3.0','juice-shop','root')];after=raw[('1.3.1','juice-shop','root')]
 apis=[]
 for path,method in [('/rest/user/login','POST'),('/rest/basket','GET'),('/rest/products/search','GET')]:
  def matches(data):return [finding for finding in data['findings'] if finding['category']=='API' and finding['method']==method and path in (finding['url']+' '+finding['detail'])]
  old,new=matches(before),matches(after);apis.append({'pathOrPrefix':path,'method':method,'beforeObservations':len(old),'afterObservations':len(new),'afterEvidence':[{'originalId':finding['originalId'],'line':finding['line'],'offset':finding['offset']} for finding in new],'meaning':'Static source observation; unresolved templates/origins are not invented or automatically executed.'})
 bundle={}
 for version,data in [('1.3.0',before),('1.3.1',after)]:
  hashes=[row.get('bodySHA256') for row in data['transport'] if row.get('status')==200 and row['url'].split('?',1)[0].endswith('/main.js')]
  bundle[version]={'successfulResponses':len(hashes),'allMatchPinnedOriginal':bool(hashes) and all(value==BUNDLE_SHA for value in hashes),'expectedSHA256':BUNDLE_SHA}
 pause={version:[row['siteId'] for row in value['runs'] if row['scenario']=='root' and row['state']=='PAUSED'] for version,value in stages.items()}
 env={}
 for version in stages:
  pages=[page for page in raw[(version,'vulnbank','root')]['pages'] if page['url'].split('?',1)[0].endswith('/.env')]
  env[version]={'capturedPages':len(pages),'statuses':sorted({page['status'] for page in pages}),'classifications':sorted({page['classification'] for page in pages}),'source':'Root automatic discovery, not an added direct seed.'}
 metadata=[]
 for site in SITES:
  options=[args.root/site/'metadata.json',args.root/(site+'-metadata.json')]
  path=next((path for path in options if path.exists()),None)
  if path:
   data=json.loads(path.read_text());metadata.append({'siteId':site,**{key:data[key] for key in PUBLIC_FIELDS if key in data}})
 checks={'all28RunsPresent':all(stage['runCount']==14 for stage in stages.values()),'allFinalRunsUseFrozenJar':stages['1.3.1']['jarSHA256']==[FINAL_SHA],'allJarsUnchanged':all(row['jarUnchanged'] for stage in stages.values() for row in stage['runs']),'allRunsStrictJava17':all(row['strictJava17'] for stage in stages.values() for row in stage['runs']),'allBudgetsRespected':all(row['integrity']['budgetRespected'] for stage in stages.values() for row in stage['runs']),'allReadMethodsOnly':all(row['integrity']['readMethodsOnly'] for stage in stages.values() for row in stage['runs']),'actualAttemptsAccounted':all(row['integrity']['attemptCountMatchesTransportRecords'] and row['integrity']['engineCounterMatchesActualAttempts'] for stage in stages.values() for row in stage['runs']),'sameRequestedConditions':all(row['integrity']['requestedParametersMatch'] and row['integrity']['configModificationPredatesRun'] for stage in stages.values() for row in stage['runs']),'sameConfiguredCredentialSnapshots':all(row['integrity']['requestCredentialSnapshotsMatchPrivateConfig'] for stage in stages.values() for row in stage['runs']),'allFinalProductExportsAvailable':all(row['productExporterAvailable'] and not row['productExporterError'] for row in stages['1.3.1']['runs']),'rootThreeRepresentativeAPIsRecovered':all(row['afterObservations']>0 and row['beforeObservations']==0 for row in apis),'samePinnedJuiceMainBundle':all(row['allMatchPinnedOriginal'] for row in bundle.values()),'noFinalAutomaticAuthPause':not pause['1.3.1']}
 summary={'generatedAtUTC':datetime.now(timezone.utc).isoformat(),'scope':'Actual original local applications, released production Runner/Katana through reconstructed HTTP/1.1 Montoya-compatible adapters; not raw TCP capture, native Burp GUI, browser DOM or a full vulnerability accuracy benchmark. Setup/login/health checks are outside these request counts.','result':'passed' if all(checks.values()) else 'review','checks':checks,'stages':stages,'originalApplicationPins':metadata,'representativeJuiceAPIs':apis,'mainBundleParity':bundle,'rootAutomaticAuthPauses':pause,'automaticEnvDiscovery':env,'limitations':['COMPLETED means the discovered queue was processed; partial/unconfirmed responses remain.','OopsSec final runs reached the explicit105-second audit wall limit; these are test-harness stops, not product session-expiry pauses.','Benchmark root reached the200-request budget; unvisited cases are not counted as tested.','Juice FTP content-length mismatches and OopsSec transport failures remain separately counted, not successful responses.','Baseline input config copies were not stored at initial launch; parity uses the recorded same config paths, predating modification times, known harness defaults and exact captured credential-header comparisons. Final inputs are privately snapshotted.','Values/authentication dumps and raw response corpus remain outside Git. Product detections, supplementary parsing and runtime login authentication are separate report origins.','The previous shared PDF and full nine-item improvement list were unavailable; this report does not invent missing items or reuse old test counts.']}
 args.out.parent.mkdir(parents=True,exist_ok=True);args.out.write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'result':summary['result'],'checks':checks,'totals':{version:{key:stage[key] for key in ('runCount','attempts','responseSuccess','transportErrors')} for version,stage in stages.items()}},ensure_ascii=False,indent=2))
 if not all(checks.values()):raise SystemExit(1)
if __name__=='__main__':main()
