#!/usr/bin/env python3
"""Export the versioned logic-upgrade run without copying private profiles or logs."""
import argparse
import collections
import csv
import hashlib
import io
import json
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def metrics(rows):
    counts = collections.Counter(cell['metric'] for row in rows for cell in row['cells'] if not cell['reference'])
    return {key: counts[key] for key in ['TP', 'FP', 'TN', 'FN', 'REVIEW']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--repo-results', type=Path, required=True)
    parser.add_argument('--check-summary', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--source-zip', type=Path, required=True)
    parser.add_argument('--target-main-js', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    run = args.results
    read = lambda name: json.loads((run / name).read_text())
    extension, browser = read('extension-results.json'), read('browser-results.json')
    check = json.loads(args.check_summary.read_text())
    if not extension['completed'] or check['result'] != 'passed':
        raise RuntimeError('Incomplete native or regression run')
    baseline = [row for row in extension['authComparisons'] if row['id'].startswith('AUTH')]
    configured = [row for row in extension['authComparisons'] if row['id'].startswith('CONF')]
    historical = root / 'audit/oss-lab/results/2026-10-18'
    previous = json.loads((historical / 'summary.json').read_text())
    previous_auth = json.loads((historical / 'auth-comparisons.json').read_text())
    with (historical / 'checks.csv').open(encoding='utf-8-sig') as source:
        old_checks = {row['id']: row for row in csv.DictReader(source)}
    summary = {
        'workbenchVersion': extension['extensionVersion'], 'target': 'OWASP Juice Shop 20.3.0',
        'nativeBurp': extension['burpVersion'], 'nativeHarnessCompleted': True,
        'testedFeatureCount': 10, 'independentMenusPreserved': True,
        'functionalChecks': dict(collections.Counter(row['status'] for row in extension['checks'])),
        'browserChecks': dict(collections.Counter(row['status'] for row in browser['checks'])),
        'defaultPolicyCells': metrics(baseline), 'targetSpecificRuleCells': metrics(configured),
        'referenceCellsExcluded': True, 'defaultComparisonCells': sum(not c['reference'] for r in baseline for c in r['cells']),
        'comparison': {
            'baselineVersion': previous['workbenchVersion'], 'baselineFunctionalChecks': previous['functionalChecks'],
            'baselineDefaultPolicyCells': metrics(previous_auth['default']),
            'baselineTargetSpecificRuleCells': metrics(previous_auth['targetSpecificRules']),
            'resolvedChecks': [row['id'] for row in extension['checks']
                               if old_checks[row['id']]['status'] == 'FAIL' and row['status'] == 'PASS'],
            'strengthenedAssertions': ['AU-07 checks empty user verdict, not only successful execution',
                                       'JS-03 rejects any retained main.js result with total analysis failure'],
        },
        'separateRegressionCheck': check,
        'remainingLimits': [
            'Empty HTTP 200 payload remains REVIEW without a target-specific denial rule',
            'HTTP 400/422 only becomes a denial when an explicit rule matches without conflicting JSON data',
            'Modern JS syntax is partially recovered, not fully parsed; unknown origins and values stay unresolved',
            'A redirect and a readback marker establish a candidate link, not durable storage or browser execution',
            'SQL error and boolean differences remain candidates requiring confirmation',
            'crAPI, public hosted demos, external OAST/BApps/archives and manual Burp GUI were not exercised',
        ],
        'crapiExecuted': False, 'rawCredentialsIncluded': False,
    }
    metadata = {
        'dateKST': datetime.now(ZoneInfo('Asia/Seoul')).isoformat(),
        'workbenchVersion': extension['extensionVersion'],
        'baseCommit': 'b63175a3f311e978f5290b4c5cbff5d58797b8fc',
        'workbenchJarSha256': digest(args.jar), 'sourceZipSha256': digest(args.source_zip),
        'burpVersion': extension['burpVersion'], 'javaVersion': extension['javaVersion'],
        'browserVersion': browser['version'], 'juiceShopVersion': '20.3.0',
        'juiceShopArchiveSha256': '47420604f215eb412cc02890973ee208a63fc0c62fe7da1d64ac5743b12c1186',
        'juiceShopMainJsSha256': digest(args.target_main_js),
        'targetContainment': 'Loopback-only listener and external Node fetch/socket rejection',
        'targetSourcePatched': False,
        'scope': 'Native headless Burp controllers and Montoya HTTP; real Chromium via private proxy; no manual Burp GUI',
    }
    checks = io.StringIO(newline='')
    writer = csv.DictWriter(checks, fieldnames=['id', 'feature', 'title', 'status', 'reason', 'millis'], lineterminator='\n')
    writer.writeheader()
    writer.writerows({**row, 'reason': row.get('reason', row['title'] + ' 조건 충족')}
                     for row in extension['checks'])
    encode = lambda value: (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode()
    files = {
        'summary.json': encode(summary), 'runtime-metadata.json': encode(metadata),
        'checks.csv': ('\ufeff' + checks.getvalue()).encode(),
        'auth-comparisons.json': encode({'default': baseline, 'targetSpecificRules': configured}),
    }
    account_rows = io.StringIO(newline='')
    account_writer = csv.DictWriter(account_rows, fieldnames=[
        'mode', 'comparison', 'path', 'actor', 'reference', 'policy', 'status',
        'protectedDataObserved', 'verdict', 'metric', 'reason'], lineterminator='\n')
    account_writer.writeheader()
    for mode, comparisons in [('default', baseline), ('targetSpecificRules', configured)]:
        for comparison in comparisons:
            for cell in comparison['cells']:
                account_writer.writerow({
                    'mode': mode, 'comparison': comparison['id'], 'path': comparison['path'],
                    **{key: cell[key] for key in ['actor', 'reference', 'policy', 'status',
                        'protectedDataObserved', 'verdict', 'metric', 'reason']},
                })
    files['account-results.csv'] = ('\ufeff' + account_rows.getvalue()).encode()
    allowed = ['extension-results.json', 'browser-results.json', 'capture-statistics.json',
               'environment-get-results.json', 'js-expected-paths.json', 'js-main-versions.json',
               'js-source-summary.json', 'payload-sqli-results.json', 'payload-sqli-context-results.json',
               'response-findings.json', 'stored-flow-result.json', 'stored-flow-2xx-result.json',
               'owned-processes-stopped.json', 'evidence-verification.json',
               'url-collection.json', 'workflow-results.json']
    for name in allowed:
        files[name] = (run / name).read_bytes()
    actors = read('private-state.json')['actors']
    tokens = [actor['token'].encode() for actor in actors]
    for name, data in files.items():
        if any(token in data for token in tokens):
            raise RuntimeError('Credential found in allowlisted export: ' + name)
    files['SHA256SUMS.json'] = encode({name: hashlib.sha256(data).hexdigest() for name, data in files.items()})
    args.repo_results.mkdir(parents=True, exist_ok=False)
    for name, data in files.items():
        (args.repo_results / name).write_bytes(data)
    print(json.dumps({'files': len(files), 'version': extension['extensionVersion'],
                      'checks': summary['functionalChecks'], 'rawTokensPresent': False}))


if __name__ == '__main__':
    main()
