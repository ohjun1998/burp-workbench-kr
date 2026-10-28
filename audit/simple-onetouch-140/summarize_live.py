#!/usr/bin/env python3
"""Validate paired original-app runs and publish counts without source/auth values."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import zipfile

SITES=('juice-shop','benchmarkjava','vulnerableapp','oopssec','dvbank','vulnbank','loop')
SOURCE=Path(__file__).resolve().parents[1]/'simple-retest/summarize_runs.py'
spec=importlib.util.spec_from_file_location('unchanged_simple_live_reader',SOURCE)
legacy=importlib.util.module_from_spec(spec);spec.loader.exec_module(legacy)

def coverage_summary(path,data):
    archive=path.parent/'product-export'
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        report=json.loads(z.read('report.json'))
    rows=report.get('coverage',[])
    correlations=report.get('correlations',[])
    known={p['originalId'] for p in data['pages'] if p.get('originalId')}
    pages_by_id={p['originalId']:p for p in data['pages'] if p.get('originalId')}
    def field(row,name):
        return row.get(name,[] if name=='stages' else '')
    return dict(schemaVersion=report.get('schemaVersion'),
        rows=len(rows),states=dict(Counter(field(r,'state') for r in rows)),
        sourceTypes=dict(Counter(field(r,'source') for r in rows)),
        requested=sum('REQUESTED' in field(r,'stages') for r in rows),
        responded=sum('RESPONDED' in field(r,'stages') for r in rows),
        analyzed=sum(field(r,'state')=='ANALYZED' for r in rows),
        unconfirmed=sum(field(r,'state')!='ANALYZED' for r in rows),
        originalIdsResolve=all(not field(r,'originalId') or field(r,'originalId') in known for r in rows),
        responseStatusMatchesOriginal=all(not field(r,'originalId') or
            (field(r,'originalId') in pages_by_id and r.get('responseStatus')==pages_by_id[field(r,'originalId')]['status']) for r in rows),
        pendingAnalysisOriginals=sum(p.get('classification')=='RESPONSE_PENDING_ANALYSIS' for p in data['pages']),
        pendingAnalysisRemainsPartial=all(not p.get('complete') and not p.get('digest')
            for p in data['pages'] if p.get('classification')=='RESPONSE_PENDING_ANALYSIS'),
        pathShapeCorrelationCandidates=len(correlations),
        correlationOriginalsResolve=all(c.get('templateOriginalId') in known and
            (not c.get('observedOriginalId') or c['observedOriginalId'] in known) for c in correlations),
        note='Candidate/account rows within this discovered run; not all-site coverage or vulnerability count.')

def summarize(results,before,after,before_sha,after_sha):
    raw={}; stages={}; configs={}
    for version,expected_sha in [(before,before_sha),(after,after_sha)]:
        assert len(expected_sha)==64 and all(c in '0123456789abcdef' for c in expected_sha)
        runs=[]
        for site in SITES:
            for scenario in ('root','direct' if site in ('benchmarkjava','vulnerableapp') else 'protected'):
                p=results/version/site/scenario/'run.json'
                data,row=legacy.read_case(p)
                assert data['version']==version
                assert row['jarSHA256']==expected_sha
                assert data['siteId']==site and data['scenario']==scenario
                raw[version,site,scenario]=data
                configs[version,site,scenario]=p.with_name('private-config.json').read_bytes()
                row['configSHA256']=hashlib.sha256(configs[version,site,scenario]).hexdigest()
                row['coverage']=coverage_summary(p,data)
                runs.append(row)
        stages[version]=dict(runCount=len(runs),runs=runs,
            attempts=sum(r['attempts'] for r in runs),httpResponses=sum(r['responseSuccess'] for r in runs),
            transportErrors=sum(r['transportErrors'] for r in runs),
            states=dict(Counter(r['state'] for r in runs)),jarSHA256=expected_sha)
    all_rows=[r for stage in stages.values() for r in stage['runs']]
    checks=dict(
        all28FreshRunsPresent=all(s['runCount']==14 for s in stages.values()),
        frozenJarsUnchanged=all(r['jarUnchanged'] for r in all_rows),
        strictJava17=all(r['strictJava17'] for r in all_rows),
        requestBudgetsRespected=all(r['integrity']['budgetRespected'] for r in all_rows),
        readMethodsOnly=all(r['integrity']['readMethodsOnly'] for r in all_rows),
        exactAttemptAccounting=all(r['integrity']['attemptCountMatchesTransportRecords'] and r['integrity']['engineCounterMatchesActualAttempts'] for r in all_rows),
        requestedConditionsMatch=all(r['integrity']['requestedParametersMatch'] and r['integrity']['configModificationPredatesRun'] for r in all_rows),
        configuredCredentialsMatchRequests=all(r['integrity']['requestCredentialSnapshotsMatchPrivateConfig'] for r in all_rows),
        exportsAvailable=all(r['productExporterAvailable'] and not r['productExporterError'] for r in all_rows),
        pairedConfigBytesIdentical=all(configs[before,site,scenario]==configs[after,site,scenario] for site in SITES for scenario in ('root','direct' if site in ('benchmarkjava','vulnerableapp') else 'protected')),
        newCoverageHasSourceEvidence=all(r['coverage']['rows']>0 and r['coverage']['originalIdsResolve']
            and r['coverage']['responseStatusMatchesOriginal'] and r['coverage']['pendingAnalysisRemainsPartial'] for r in stages[after]['runs']),
        newCorrelationOriginalsResolve=all(r['coverage']['correlationOriginalsResolve'] for r in stages[after]['runs']),
    )
    route_observations={}
    for version in (before,after):
        root=raw[version,'vulnbank','root']
        route_observations[version]=dict(
            rootEnvResponses=sum(p['url'].split('?',1)[0].endswith('/.env') and p['status']==200 for p in root['pages']),
            merchantMeRequests=sum(t['url'].split('?',1)[0].endswith('/api/v1/merchants/me') for t in root['transport']),
            merchantMeDeniedResponses=sum(t['url'].split('?',1)[0].endswith('/api/v1/merchants/me') and t['status'] in (401,403) for t in root['transport']))
    return dict(result='passed' if all(checks.values()) else 'review',checks=checks,stages=stages,
        beforeVersion=before,afterVersion=after,vulnbankRootObservations=route_observations,
        limitations=[
          'Fresh real local applications with released production Runner and pinned Katana through Montoya-compatible HTTP adapters; not native Burp GUI.',
          'Both versions use the same freshly snapshotted inputs. Runs of each site are serial; at most two different sites run concurrently.',
          'Setup/normal login/root smoke requests are outside engine request totals.',
          'COMPLETED means discovered queue processing ended; partial, failed, denied and unvisited items remain distinct.',
          'HTTP responses include controls and non-2xx responses. Transport errors are not responses.',
          'Source strings, template variables, API declarations and linked PEM files do not prove target access or private-key acquisition.',
          'Candidate/account rows and changed request counts are not all-site recall, accuracy, performance or vulnerability counts.',
          'No previously reported direct merchant realm proof is reused as new automatic discovery evidence.',
          'Raw packets, values and credential snapshots remain outside public Git.'])

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--results',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    p.add_argument('--before',default='1.3.2');p.add_argument('--after',default='1.4.0')
    p.add_argument('--before-sha',required=True);p.add_argument('--after-sha',required=True)
    a=p.parse_args();assert a.before!=a.after
    summary=summarize(a.results,a.before,a.after,a.before_sha,a.after_sha)
    a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(dict(result=summary['result'],checks=summary['checks'],
        stages={v:{k:s[k] for k in ['runCount','attempts','httpResponses','transportErrors','states']} for v,s in summary['stages'].items()}),ensure_ascii=False,indent=2))
    if summary['result']!='passed':raise SystemExit(1)

if __name__=='__main__':main()
