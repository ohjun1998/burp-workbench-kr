#!/usr/bin/env python3
"""Select immutable local-app response evidence; credentials and raw values stay outside Git."""
import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path

# Stable locations from the reviewed Simple1.3.2 report, not newly injected target data.
POSITIVE_IDS = '''V0002691 V0002692 V0001549 V0001550 V0001551 V0001552 V0002138 V0002867 V0002868 V0001324
V0013904 V0013914 V0014900 V0016577 V0016580 V0016581 V0016582 V0016583 V0016431 V0016437
V0021903 V0021900 V0027215 V0027216 V0027217 V0027218 V0027219 V0024313 V0024517 V0026880 V0026881
V0034896 V0034898 V0034899 V0034900 V0034901 V0034902 V0035083 V0035082 V0034932 V0035085 V0035086 V0035089 V0035090
V0037364 V0038333 V0038334 V0038335 V0038336 V0038337 V0038338 V0038339 V0038340 V0038341 V0038342 V0038343 V0038345 V0038346 V0038347
V0038538 V0038539 V0037672 V0037673 V0037736 V0037754 V0041885 V0041886 V0041887 V0041510 V0041242 V0041241
V0041288 V0041291 V0041292 V0041299 V0041303 V0041311 V0041391 V0041248'''.split()
# These remain observable data, but are not real credential/DB discoveries.
CONTROL_IDS = {
    'V0002504': 'public-contact', 'V0013898': 'test-cookie', 'V0013908': 'test-cookie',
    'V0016164': 'code-version', 'V0016596': '404-api-candidate', 'V0016598': '404-api-candidate',
    'V0016600': '404-api-candidate', 'V0038546': 'key-placeholder', 'V0039067': 'auth-header-name',
    'V0041852': 'explicit-demo-token',
    'V0002240': 'css-icon-noise', 'V0002247': 'css-icon-noise', 'V0002253': 'css-icon-noise',
    'V0005510': 'domtokenlist-noise', 'V0005897': 'swagger-code-fragment',
    'V0005902': 'swagger-code-fragment', 'V0023994': 'domtokenlist-noise',
}

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def utf16_units(text):
    return len(text.encode('utf-16-le')) // 2

def source_text(response, decoded, area):
    if area == 'response.headers':
        head = response.split(b'\r\n\r\n', 1)[0].decode('utf-8')
        return '\n'.join(head.split('\r\n')[1:])
    return decoded

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw-values', type=Path, required=True)
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True); args.out.chmod(0o700)
    rows = json.loads(args.raw_values.read_text())
    by_id = {v['id']: v for v in rows}
    chosen = POSITIVE_IDS + list(CONTROL_IDS)
    assert len(set(chosen)) == len(chosen)
    grouped = defaultdict(list)
    selected_runs = {}
    for ident in chosen:
        row = by_id[ident]
        assert row['version'] == '1.3.2' and row['sourceOrigin'] != 'testLoginAuth'
        assert row['source']['evidenceVerified'] is True
        grouped[(row['siteId'], row['scenario'], row['source']['url'], row['source']['responseSHA256'])].append(row)
    cases = []
    for n, ((site, scenario, url, response_sha), expected) in enumerate(grouped.items(), 1):
        run_dir = args.results / site / scenario
        run_file = run_dir / 'run.json'
        run = selected_runs.setdefault(str(run_file), json.loads(run_file.read_text()))
        original = next((v['source']['originalId'] for v in expected if v['source']['originalId']), '')
        page = next((p for p in run['pages'] if original and p['originalId'] == original), None)
        if page is None:
            # Some independently parsed identity-control transport responses had
            # no product OriginalID in the old report. Keep that limitation.
            relative = expected[0]['source']['file']
            transport = next(t for t in run['transport'] if t.get('decodedBodyFile') == relative)
            page = dict(transport, profileRevision='', originalId='')
            assert transport['url'] == url
        response = (run_dir / page['responseFile']).resolve()
        decoded = (run_dir / page['decodedBodyFile']).resolve()
        response_bytes = response.read_bytes(); decoded_text = decoded.read_text()
        assert digest(response) == expected[0]['source']['responseSHA256']
        truth = []
        for row in expected:
            source = row['source']; text = source_text(response_bytes, decoded_text, source['area'])
            start = source['offset']; length = source['length']
            if 'UTF-16' in source['offsetUnit']:
                encoded = text.encode('utf-16-le')
                exact = encoded[start*2:(start+length)*2].decode('utf-16-le')
                offset_utf16 = start
            else:
                exact = text[start:start+length]
                offset_utf16 = utf16_units(text[:start])
            assert exact == row['rawLexeme'], row['id']
            assert hashlib.sha256(row['valueExact'].encode()).hexdigest() == row['valueSHA256']
            truth.append(dict(id=row['id'], category=row['category'].upper(), name=row['name'],
                value=row['valueExact'], valueSHA256=row['valueSHA256'], rawLexeme=row['rawLexeme'],
                sourceArea=source['area'], offsetUtf16=offset_utf16, lengthUtf16=utf16_units(exact),
                expectedKind='relevant-value' if row['id'] in POSITIVE_IDS else CONTROL_IDS[row['id']],
                originalClassification=row['classification'], originalSourceOrigin=row['sourceOrigin']))
        cases.append(dict(caseId=f'C{n:03}',siteId=site,scenario=scenario,url=page['url'],status=page['status'],
            account=page['account'],profileRevision=page['profileRevision'],originalId=original,
            sourceMapping='verified-old-product-original-id' if original else 'retained-transport-only-no-old-product-id',
            responsePath=str(response),responseSHA256=digest(response),decodedPath=str(decoded),
            decodedSHA256=digest(decoded),truth=truth))
    document = dict(schemaVersion=1,sourceVersion='1.3.2',sourceRawValuesSHA256=digest(args.raw_values),
        sourceScope='retained original local applications; existing final1.3.2 response corpus; offline replay only',
        sites=sorted({c['siteId'] for c in cases}),caseCount=len(cases),relevantTruthCount=len(POSITIVE_IDS),
        controlTruthCount=len(CONTROL_IDS),cases=cases)
    private = args.out / 'private-corpus.json'
    if private.exists():
        assert json.loads(private.read_text()) == document, 'Refusing to replace a different frozen corpus'
    private.write_text(json.dumps(document, ensure_ascii=False, indent=2)+'\n'); private.chmod(0o600)
    public = dict(document); public['cases'] = [dict(caseId=c['caseId'],siteId=c['siteId'],scenario=c['scenario'],
        status=c['status'],responseSHA256=c['responseSHA256'],decodedSHA256=c['decodedSHA256'],
        sourceMapping=c['sourceMapping'],
        truth=[dict(id=t['id'],category=t['category'],expectedKind=t['expectedKind'],
            valueSHA256=t['valueSHA256']) for t in c['truth']]) for c in cases]
    public['transportOnlyCases'] = sum(c['sourceMapping']=='retained-transport-only-no-old-product-id' for c in cases)
    (args.out/'corpus-summary.json').write_text(json.dumps(public, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({k:document[k] for k in ['sites','caseCount','relevantTruthCount','controlTruthCount']}))

if __name__ == '__main__':
    main()
