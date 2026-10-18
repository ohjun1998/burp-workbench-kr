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


def explanation(case, account, label):
    """Explain measurements using independent policy/data, separately from engine reasons."""
    read = case['method'] == 'GET'
    observation = ('권한 없는 계정에 비공개 자료 표식이 반환됨' if read else
                   '독립 전후 조회에서 저장 버전·처리 횟수 증가와 실행 계정 일치를 확인함')
    if label == 'TP':
        return '기대 권한 DENY이나 ' + observation + '; 확장이 우회 의심으로 경고함'
    if label == 'FN':
        if account['finding'] == 'BLOCKED':
            return observation + '; 응답의 차단 표시를 우선하여 실제 위반을 경고하지 못함'
        return observation + '; 권한표 또는 쓰기 전후 검증이 설정되지 않아 확장이 보류하여 엄격한 재현율에서는 미탐으로 집계함'
    if label == 'FP':
        if case['featureId'] in (18, 42) and account['status'] == 200:
            return '독립 검사상 자료 미노출; CSV/파일 기준과 JSON 오류 응답의 형식 차이로 차단 규칙을 놓치고 HTTP 200을 우회 의심으로 경고함'
        return '독립 권한·자료/저장 변경 검사에서 실제 위반이 없는데 확장이 우회 의심으로 경고함; 원응답과 규칙 수동 확인 필요'
    return ('허용된 계정의 정상 동작이며 우회 의심 경고가 없음' if account['expectedPolicy'] == 'ALLOW' else
            '독립 검사상 자료 미노출·금지된 저장 변경 없음; 우회 의심 경고가 없음. 보류이면 안전 확정을 뜻하지 않음')


def review_reason(case, account):
    if account['verdict'] != 'REVIEW':
        return ''
    kind, status = account['finding'], account['status']
    if kind.startswith('WRITE_') or account['effectOutcome'] == 'UNCHANGED':
        return {'WRITE_UNVERIFIED': '쓰기 검증 미설정: 응답만으로 실제 처리 성공을 확인할 수 없음',
                'WRITE_PENDING': '지연 처리: HTTP 202 접수 후 지정 조회 시간 안에 완료 상태 미관측',
                'WRITE_PARTIAL': '기대 상태 미도달: 조회 값은 달라졌지만 설정한 완료 값과 불일치',
                'WRITE_READ_FAILED': '후속 조회 실패: 처리 후 상태를 읽지 못해 결론을 내릴 수 없음',
                'WRITE_SETUP_FAILED': '전후 조회 준비 실패: 전 상태를 확정하지 못해 대상 쓰기 미전송'}.get(kind,
                f'처리 결과 미확인: HTTP {status}, 지정 조회 항목은 미변경이나 명시적 차단 근거 또는 업무 결과가 부족함')
    if kind == 'DENIAL_DATA':
        return '차단과 데이터 충돌: 거부 표시와 자료 필드가 함께 있어 실제 노출 확인 필요'
    if 300 <= status < 400:
        return f'리다이렉트: HTTP {status}의 이동 위치와 로그인/업무 결과 확인 필요'
    if case['setting'] == 'unconfigured':
        return '접근 정책 미설정: 응답만으로 공개·공유 허용과 권한 위반을 구분할 수 없음'
    return account['reason']


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
                             effectOutcome=a['effectOutcome'], groundTruthViolation=truth,
                             reason=a['reason'], classificationReason=explanation(c, a, label),
                             reviewReason=review_reason(c, a)))
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
    reason_rows = []
    for setting in SETTINGS:
        for (label, reason), count in sorted(Counter((r['classification'], r['classificationReason']) for r in flat if r['setting'] == setting).items()):
            reason_rows.append(dict(setting=setting, classification=label, reason=reason, count=count))
    csv_file('classification-reasons.csv', reason_rows)
    review_rows = []
    for setting in SETTINGS:
        for (kind, status, reason), count in sorted(Counter((r['finding'], r['status'], r['reviewReason']) for r in flat if r['setting'] == setting and r['verdict'] == 'REVIEW').items()):
            review_rows.append(dict(setting=setting, finding=kind, status=status, reason=reason, count=count))
    if review_rows:
        csv_file('review-reasons.csv', review_rows)
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
                  falsePositives=false_positives, classificationReasons=reason_rows, reviewReasons=review_rows,
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
