#!/usr/bin/env python3
"""Compare frozen-response product links and false-positive controls, without publishing raw values."""
import argparse
from collections import Counter
import json
from pathlib import Path

BENIGN_CLASSIFICATIONS = {'EXAMPLE_OR_PLACEHOLDER','PUBLIC_IDENTIFIER','PERSONAL_DATA',
    'OBSERVED_VALUE','COOKIE_VALUE','DYNAMIC_UNRESOLVED','SCHEMA_METADATA','METADATA'}
NOISE_KINDS = {'css-icon-noise','domtokenlist-noise','swagger-code-fragment'}

def elevated_secret(check):
    """Evaluate semantics across candidate categories; category SECRET alone is insufficient."""
    if 'SECRET_CANDIDATE' in check.get('exportClassifications',[]) and set(check.get('exportCategories',[])) & {'SECRET','KEY','TOKEN','SENSITIVE','DATABASE'}:
        return True
    for item in check.get('matchedSemantic', []):
        category=item.get('category',''); classification=item.get('classification','')
        state=item.get('state','').replace(' ','')
        if classification in BENIGN_CLASSIFICATIONS or any(x in state for x in ['예제','공개식별자','동적','쿠키']):
            continue
        if classification=='SECRET_CANDIDATE':
            return True
        if category=='SECRET':
            return True
        if category in {'KEY','TOKEN','SENSITIVE'} and ('유효성미검증' in state or item.get('severity') in {'높음','중간','High','Medium'}):
            return True
    return False

def control_classification_problem(check,kind):
    if not check.get('reportValuePresent'):
        return ''
    classifications=set(check.get('exportClassifications',[]))
    if kind in {'key-placeholder','explicit-demo-token'} and 'EXAMPLE_OR_PLACEHOLDER' not in classifications:
        return 'retained example/placeholder lacks explicit classification'
    if kind=='auth-header-name' and set(check.get('exportCategories',[])) & {'KEY','TOKEN'} and classifications & {'SECRET_CANDIDATE','UNVERIFIED'}:
        return 'header name is classified as an unverified key/token value'
    return ''

def validate_run(result,runtime,version):
    if result.get('version') != version:
        raise ValueError(f'Expected version {version}; a missing/different producer is not a valid run')
    if runtime.get('exitCode') != 0 or runtime.get('jarUnchanged') is not True or runtime.get('strictJava17') is not True:
        raise ValueError('Run did not complete with an unchanged JAR and strict Java17')
    if result.get('networkRequests') != 0 or runtime.get('networkRequests') != 0:
        raise ValueError('Offline run contains network activity')
    if not runtime.get('jarSHA256') or not runtime.get('corpusSHA256') or not runtime.get('harnessSHA256'):
        raise ValueError('Run lacks required JAR/corpus/harness fingerprints')
    cases=result.get('cases',[])
    if result.get('caseCount') != len(cases) or not cases:
        raise ValueError('Run lacks a complete case list')
    checks={}
    for case in cases:
        if not case.get('responseSHA256') or not case.get('decodedSHA256'):
            raise ValueError('Case lacks a frozen source fingerprint')
        for check in case.get('checks',[]):
            key=(case['caseId'],check['id'])
            if key in checks:
                raise ValueError('Duplicate truth check')
            checks[key]=(case,check)
    return checks

def compare(before,before_runtime,after,after_runtime,corpus,before_version='1.3.2',after_version='1.4.0'):
    old=validate_run(before,before_runtime,before_version);new=validate_run(after,after_runtime,after_version)
    if before_runtime['corpusSHA256']!=after_runtime['corpusSHA256'] or before_runtime['harnessSHA256']!=after_runtime['harnessSHA256']:
        raise ValueError('Before/after corpus or harness differs')
    truth={(c['caseId'],t['id']):(c,t) for c in corpus['cases'] for t in c['truth']}
    if set(old)!=set(new) or set(old)!=set(truth):
        raise ValueError('Before/after truth coverage differs or checks are missing')
    rows=[]
    for key,(source,expected) in truth.items():
        oc,ov=old[key];nc,nv=new[key]
        for case,value in [(oc,ov),(nc,nv)]:
            if case['responseSHA256']!=source['responseSHA256'] or case['decodedSHA256']!=source['decodedSHA256']:
                raise ValueError('Frozen response/decoded SHA differs')
            if value.get('valueSHA256')!=expected['valueSHA256'] or value.get('expectedKind')!=expected['expectedKind']:
                raise ValueError('Expected truth changed')
        rows.append(dict(caseId=key[0],id=key[1],siteId=source['siteId'],category=expected['category'],
            expectedKind=expected['expectedKind'],valueSHA256=expected['valueSHA256'],
            before=dict(ov,elevatedSecret=elevated_secret(ov)),after=dict(nv,elevatedSecret=elevated_secret(nv))))
    positive=[r for r in rows if r['expectedKind']=='relevant-value']
    control=[r for r in rows if r['expectedKind']!='relevant-value']
    def counts(selected,side):
        return dict(truthItems=len(selected),productLocationOrFingerprintLinked=sum(r[side]['productLocationOrFingerprintMatch'] for r in selected),
            distinctSiteValueTruthItems=len({(r['siteId'],r['valueSHA256']) for r in selected}),
            productLinkedDistinctSiteValues=len({(r['siteId'],r['valueSHA256']) for r in selected if r[side]['productLocationOrFingerprintMatch']}),
            exactExportValueAndRaw=sum(r[side]['reportExactValueAndRawMatch'] for r in selected),
            exactExportValueAndSourceSpan=sum(r[side].get('reportExactValueAndSourceSpan',False) for r in selected),
            exportValuePresent=sum(r[side]['reportValuePresent'] for r in selected),
            exporterProductLinked=sum('PRODUCT_FINDING' in r[side]['exportProvenance'] for r in selected))
    regressions=[r['id'] for r in positive if r['before']['productLocationOrFingerprintMatch'] and not r['after']['productLocationOrFingerprintMatch']]
    remaining=[r['id'] for r in positive if not r['after']['productLocationOrFingerprintMatch']]
    noisy=[r['id'] for r in control if r['expectedKind'] in NOISE_KINDS and r['after']['reportValuePresent']]
    elevated=[r['id'] for r in control if r['after']['elevatedSecret']]
    inappropriate=[dict(id=r['id'],reason=control_classification_problem(r['after'],r['expectedKind']))
        for r in control if control_classification_problem(r['after'],r['expectedKind'])]
    all_cases=before['cases']+after['cases']
    gates=dict(sameFrozenSources=True,sameHarness=True,allTruthChecksPresent=True,
        noPreviouslyLinkedValueRegression=not regressions,knownNoiseExcludedFromExport=not noisy,
        controlsNotElevatedAsSecret=not elevated,retainedExamplesAndMetadataClassified=not inappropriate)
    gates.update(adapterWireMatchesOriginal=all(c.get('adapterWireMatchesOriginal',False) for c in all_cases),
        allExportSourceSpansVerified=all(c.get('allExportSourceSpansVerified',False) for c in all_cases),
        allExportResponseHashesMatchOriginal=all(c.get('allExportResponseHashesMatchOriginal',False) for c in all_cases))
    return dict(schemaVersion=1,result='passed' if all(gates.values()) else 'review',
        beforeVersion=before_version,afterVersion=after_version,networkRequests=0,caseCount=len(corpus['cases']),
        siteCount=len(corpus['sites']),relevantTruthItems=len(positive),controlTruthItems=len(control),
        before=counts(positive,'before'),after=counts(positive,'after'),
        perSite={site:dict(before=counts([r for r in positive if r['siteId']==site],'before'),
            after=counts([r for r in positive if r['siteId']==site],'after')) for site in corpus['sites']},
        gates=gates,regressedTruthIds=regressions,remainingUnlinkedTruthIds=remaining,
        remainingNoiseIds=noisy,elevatedControlIds=elevated,inappropriateControlClassifications=inappropriate,checks=rows,
        fingerprints=dict(beforeJar=before_runtime['jarSHA256'],afterJar=after_runtime['jarSHA256'],
            corpus=before_runtime['corpusSHA256'],harness=before_runtime['harnessSHA256']),
        limitations=[
            'Known truth is a hand-reviewed selection from retained original responses; counts are not whole-site recall or precision.',
            'Truth items retain reviewed field/location entries, including one same-site value classified as both DB_HOST and internal host; distinct site/value counts are provided separately.',
            'A location/fingerprint link and exporter PRODUCT_FINDING provenance are separate matching methods and are not added together.',
            'exactExportValueAndRaw compares the old supplemental raw lexeme literally; exactExportValueAndSourceSpan verifies the new exporter rawText against its actual original UTF16 source substring and response hash. Whole-backtick evidence may validly differ from the old inner-only lexeme.',
            'Offline replay makes no new target requests and does not verify live crawling, authentication, permissions or external key validity.',
            'Observed API literals, public data, examples, cookies and HTTP404 source responses remain distinct from successful target access.',
            'Original credentials, exact values and response corpus stay outside public Git; this summary contains identifiers, categories and fingerprints only.'
        ])

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--before',type=Path,required=True);p.add_argument('--after',type=Path,required=True)
    p.add_argument('--corpus',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    a=p.parse_args()
    read=lambda path:json.loads(path.read_text())
    result=compare(read(a.before/'result.json'),read(a.before/'runtime.json'),read(a.after/'result.json'),
        read(a.after/'runtime.json'),read(a.corpus))
    a.out.parent.mkdir(parents=True,exist_ok=True)
    a.out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:result[k] for k in ['result','caseCount','siteCount','before','after','gates']},ensure_ascii=False))

if __name__=='__main__':
    main()
