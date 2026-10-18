#!/usr/bin/env python3
"""Validate benchmark coverage/ground truth and recalculate detection metrics.

Detection errors are measurements, not a failing assertion. Missing cases,
wrong identities, unexercised controls and inconsistent evidence are failures.
"""
import argparse
from collections import Counter
import csv
import hashlib
import itertools
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
PROFILES = ['session', 'token', 'jwt', 'session-token', 'session-jwt', 'token-jwt', 'all']
ACTORS = ['admin', 'alice', 'bob', 'anonymous']
SETTINGS = ['unconfigured', 'configured']
MODES = ['fixed', 'vuln']


def metrics(rows):
    c = Counter(row['classification'] for row in rows)
    result = {k: c[k] for k in ('TP', 'FP', 'TN', 'FN')}
    result.update(precision=c['TP'] / (c['TP'] + c['FP']) if c['TP'] + c['FP'] else None,
                  recall=c['TP'] / (c['TP'] + c['FN']) if c['TP'] + c['FN'] else None,
                  falsePositiveRate=c['FP'] / (c['FP'] + c['TN']) if c['FP'] + c['TN'] else None,
                  reviewCount=sum(row['verdict'] == 'REVIEW' for row in rows), samples=len(rows))
    return result


def classification(truth, alert):
    return ('TP' if alert else 'FN') if truth else ('FP' if alert else 'TN')


def verify(run_dir, out):
    policy_path = ROOT / 'audit/auth50/expected-policy.csv'
    with policy_path.open(encoding='utf-8', newline='') as f:
        policy = {int(r['id']): r for r in csv.DictReader(f)}
    native = json.loads((run_dir / 'extension-results.json').read_text())
    http = json.loads((run_dir / 'http-results.json').read_text())
    assert set(policy) == set(range(1, 51))
    assert len({(r['method'], r['path']) for r in policy.values()}) == 50
    expected_keys = set(itertools.product(PROFILES, SETTINGS, MODES, policy))
    seen = set(); flat = []; run_rows = []; writes = 0
    for c in native['comparisons']:
        key = (c['profile'], c['setting'], c['mode'], c['featureId'])
        assert key in expected_keys and key not in seen, ('coverage', key)
        seen.add(key); p = policy[c['featureId']]
        assert c['method'] == p['method'] and c['baselineActor'] == p['baseline']
        assert c['path'] == f"/api/auth-lab/{c['profile']}/{c['mode']}/features/{p['path']}"
        assert Counter(a['actor'] for a in c['accounts']) == Counter(ACTORS)
        reference_count = 0; truths = []; alerts = []
        for a in c['accounts']:
            actor = a['actor']; expected = p[actor] == 'ALLOW'
            should_access = expected or (c['mode'] == 'vuln' and actor != 'anonymous')
            assert a['expectedPolicy'] == p[actor] and 100 <= a['status'] <= 599
            assert a['observedAccess'] == should_access, ('unexercised fixture', key, actor)
            assert re.fullmatch('[0-9a-f]{64}', a['responseSha256'])
            if c['method'] == 'GET':
                marker = bool(re.search(r'\bAUTH50_(?:PRIVATE|PUBLIC)_' + str(c['featureId']) + r'\b', a['responseExcerpt']))
                assert marker == should_access, ('read ground truth', key, actor)
            else:
                writes += 1; e = a['independentWriteEvidence']
                assert e['actor'] == actor and e['status'] == a['status']
                before, after = e['before'], e['after']
                if should_access:
                    assert after['revision'] == before['revision'] + 1
                    assert after['eventCount'] == before['eventCount'] + 1
                    assert int(after['lastOperation']) == c['featureId'] and after['lastActor'] == actor
                else:
                    assert before == after
                assert e['stateChanged'] == should_access
            truth = not expected and should_access
            assert a['groundTruthViolation'] == truth
            assert a['alert'] == (a['verdict'] == 'SUSPECT')
            if a['reference']:
                reference_count += 1
                assert actor == p['baseline'] and expected and a['classification'] == 'REFERENCE'
                continue
            label = classification(truth, a['alert']); assert label == a['classification']
            truths.append(truth); alerts.append(a['alert'])
            flat.append(dict(setting=c['setting'], profile=c['profile'], mode=c['mode'], featureId=c['featureId'],
                             name=p['name'], actor=actor, method=p['method'], path=c['path'], expectedPolicy=p[actor],
                             status=a['status'], classification=label, verdict=a['verdict'], finding=a['finding'],
                             effectOutcome=a['effectOutcome'], groundTruthViolation=truth))
        assert reference_count == 1
        truth, alert = any(truths), any(alerts)
        assert c['groundTruthViolation'] == truth and c['classification'] == classification(truth, alert)
        assert (c['aggregateVerdict'] == 'SUSPECT') == alert
        run_rows.append(dict(setting=c['setting'], classification=c['classification'], verdict=c['aggregateVerdict']))
    assert seen == expected_keys and len(flat) == 4200 and writes == 3024
    assert len(native['identityChecks']) == 21 and all(r['identityMatched'] and r['anonymousRejected'] for r in native['identityChecks'])
    recalculated = {}
    for setting in SETTINGS:
        cells = [r for r in flat if r['setting'] == setting]
        recalculated[setting] = dict(account=metrics(cells), run=metrics([r for r in run_rows if r['setting'] == setting]))
        assert recalculated[setting] == native['metrics'][setting]
        assert sum(r['groundTruthViolation'] for r in cells) == 392
    http_keys = set(itertools.product(PROFILES, MODES, policy, ACTORS)); seen_http = set()
    for c in http['cases']:
        key = (c['profile'], c['mode'], c['featureId'], c['actor'])
        assert key in http_keys and key not in seen_http and c['passed']; seen_http.add(key)
        p = policy[c['featureId']]; expected = p[c['actor']] == 'ALLOW'
        observed = c['actualStateChanged'] if p['method'] != 'GET' else c['dataExposed']
        assert observed == (expected or (c['mode'] == 'vuln' and c['actor'] != 'anonymous'))
        assert c['groundTruthViolation'] == (not expected and observed)
    assert seen_http == http_keys and len(http['invalidCombinationChecks']) == 33
    assert all(r['passed'] and r['status'] == 401 for r in http['invalidCombinationChecks'])
    out.mkdir(parents=True, exist_ok=True)
    def csv_file(name, rows):
        with (out / name).open('w', encoding='utf-8-sig', newline='') as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0])); w.writeheader(); w.writerows(rows)
    csv_file('account-results.csv', flat)
    function_rows = []
    actor_rows = []; profile_rows = []
    for setting in SETTINGS:
        for fid, p in policy.items():
            rows = [r for r in flat if r['setting'] == setting and r['featureId'] == fid]
            function_rows.append(dict(setting=setting, featureId=fid, group=p['group'], name=p['name'], method=p['method'],
                                      path=p['path'], baseline=p['baseline'],
                                      allowedActors='/'.join(a for a in ACTORS if p[a] == 'ALLOW'), **metrics(rows)))
        for actor in ACTORS:
            actor_rows.append(dict(setting=setting, actor=actor, **metrics([r for r in flat if r['setting'] == setting and r['actor'] == actor])))
        for profile in PROFILES:
            profile_rows.append(dict(setting=setting, profile=profile, **metrics([r for r in flat if r['setting'] == setting and r['profile'] == profile])))
    for name, rows in [('function-results.csv', function_rows), ('actor-results.csv', actor_rows), ('profile-results.csv', profile_rows)]:
        csv_file(name, rows)
    false_positives = [r for r in flat if r['classification'] == 'FP']
    result = dict(result='passed', nativeComparisons=len(seen), referenceExcluded=True, accountComparisons=len(flat),
                  independentWriteObservations=writes, httpCases=len(seen_http), invalidAuthProbes=33,
                  distinctFunctions=50, methods=dict(Counter(p['method'] for p in policy.values())),
                  knownViolationCellsPerSetting=392, metrics=recalculated, actors=actor_rows, profiles=profile_rows,
                  falsePositives=false_positives,
                  review=dict((s, dict(total=sum(r['verdict'] == 'REVIEW' for r in flat if r['setting'] == s),
                                        violations=sum(r['verdict'] == 'REVIEW' and r['groundTruthViolation'] for r in flat if r['setting'] == s),
                                        definiteBlockedViolations=sum(r['verdict'] == 'BLOCKED' and r['groundTruthViolation'] for r in flat if r['setting'] == s))) for s in SETTINGS),
                  inputsSha256={p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in [policy_path, run_dir / 'extension-results.json', run_dir / 'http-results.json']},
                  interpretation='TN means no false SUSPECT alert; REVIEW is not confirmed secure. FN includes REVIEW for strict recall.')
    (out / 'analysis.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k: result[k] for k in ['result', 'nativeComparisons', 'accountComparisons', 'httpCases', 'metrics', 'review']}, ensure_ascii=False))
    return result


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run-dir', type=Path, required=True)
    p.add_argument('--out', type=Path, default=ROOT / 'tmp/auth50-analysis')
    a = p.parse_args(); verify(a.run_dir, a.out)
