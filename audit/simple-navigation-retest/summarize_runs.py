#!/usr/bin/env python3
"""Summarize a fresh paired matrix without exporting response/authentication values."""
import argparse
import collections
import hashlib
import importlib.util
import json
from datetime import datetime, timezone
from pathlib import Path

SITES = ('juice-shop', 'benchmarkjava', 'vulnerableapp', 'oopssec', 'dvbank', 'vulnbank', 'loop')
PREVIOUS = Path(__file__).resolve().parents[1] / 'simple-retest' / 'summarize_runs.py'
spec = importlib.util.spec_from_file_location('simple131_summary_readonly', PREVIOUS)
previous = importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--metadata-root', type=Path, required=True)
    parser.add_argument('--before', default='1.3.1')
    parser.add_argument('--after', default='1.3.2')
    parser.add_argument('--before-sha', required=True)
    parser.add_argument('--after-sha', required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    assert args.before != args.after
    for sha in (args.before_sha, args.after_sha):
        assert len(sha) == 64 and all(c in '0123456789abcdef' for c in sha)
    raw, stages, private_snapshots = {}, {}, {}
    for version in (args.before, args.after):
        rows = []
        for site in SITES:
            for scenario in ('root', 'direct' if site in ('benchmarkjava', 'vulnerableapp') else 'protected'):
                path = args.results / version / site / scenario / 'run.json'
                data, row = previous.read_case(path)
                assert data['version'] == version, (version, site, scenario, 'unexpected JAR version')
                raw[version, site, scenario] = data
                private_snapshots[version, site, scenario] = path.with_name('private-config.json').read_bytes()
                rows.append(row)
        stages[version] = {
            'runs': rows, 'runCount': len(rows),
            **{key: sum(row[key] for row in rows) for key in ('attempts', 'responseSuccess', 'transportErrors')},
            'rootAttempts': sum(row['attempts'] for row in rows if row['scenario'] == 'root'),
            'directContrastAttempts': sum(row['attempts'] for row in rows if row['scenario'] != 'root'),
            'states': dict(collections.Counter(row['state'] for row in rows)),
            'jarSHA256': sorted({row['jarSHA256'] for row in rows}),
        }
    all_rows = [row for stage in stages.values() for row in stage['runs']]
    checks = {
        'all28RunsPresent': all(stage['runCount'] == 14 for stage in stages.values()),
        'beforeUsesFrozenJar': stages[args.before]['jarSHA256'] == [args.before_sha],
        'afterUsesFrozenJar': stages[args.after]['jarSHA256'] == [args.after_sha],
        'allJarsUnchanged': all(row['jarUnchanged'] for row in all_rows),
        'allRunsStrictJava17': all(row['strictJava17'] for row in all_rows),
        'allBudgetsRespected': all(row['integrity']['budgetRespected'] for row in all_rows),
        'allReadMethodsOnly': all(row['integrity']['readMethodsOnly'] for row in all_rows),
        'actualAttemptsAccounted': all(row['integrity']['attemptCountMatchesTransportRecords'] and row['integrity']['engineCounterMatchesActualAttempts'] for row in all_rows),
        'allRequestedConditionsMatch': all(row['integrity']['requestedParametersMatch'] and row['integrity']['configModificationPredatesRun'] for row in all_rows),
        'allConfiguredCredentialSnapshotsMatchRequests': all(row['integrity']['requestCredentialSnapshotsMatchPrivateConfig'] for row in all_rows),
        'allProductExportsAvailable': all(row['productExporterAvailable'] and not row['productExporterError'] for row in all_rows),
        'allPrivateInputsSnapshotted': all(row['integrity']['inputConfigSnapshotStored'] for row in all_rows),
        'pairedInputsByteIdentical': all(private_snapshots[args.before, site, scenario] == private_snapshots[args.after, site, scenario] for site in SITES for scenario in ('root', 'direct' if site in ('benchmarkjava', 'vulnerableapp') else 'protected')),
    }
    pins = []
    for site in SITES:
        candidates = (args.metadata_root / site / 'metadata.json', args.metadata_root / (site + '-metadata.json'))
        path = next((path for path in candidates if path.exists()), None)
        if path:
            data = json.loads(path.read_text())
            pins.append({'siteId': site, **{key: data[key] for key in previous.PUBLIC_FIELDS if key in data}})
    pause = {version: [row['siteId'] + '/' + row['scenario'] for row in stage['runs'] if row['state'] == 'PAUSED'] for version, stage in stages.items()}
    realm_contrast = {}
    for version in stages:
        data = raw[version, 'vulnbank', 'root']
        config = json.loads(private_snapshots[version, 'vulnbank', 'root'])
        profile = next(row for row in config['profiles'] if row['id'] == 'alice')
        transport = data['transport']
        denials = [index for index, row in enumerate(transport) if row['url'].split('?', 1)[0].endswith('/api/v1/merchants/me') and row['status'] == 401 and row['account'] == profile['label']]
        denial = denials[0] if denials else None
        rechecks = [index for index, row in enumerate(transport) if denial is not None and index > denial and row['url'] == profile['identity']['url'] and row['status'] == 200 and row['account'] == profile['label']]
        verified_rechecks = []
        case_directory = (args.results / version / 'vulnbank' / 'root').resolve()
        for index in rechecks:
            row = transport[index]
            body = (case_directory / row['bodyFile']).resolve()
            decoded = (case_directory / row['decodedBodyFile']).resolve()
            assert body.is_relative_to(case_directory) and decoded.is_relative_to(case_directory)
            assert hashlib.sha256(body.read_bytes()).hexdigest() == row['bodySHA256']
            value = json.loads(decoded.read_text())
            for key in profile['identity']['path'].removeprefix('$.').split('.'):
                value = value.get(key) if isinstance(value, dict) else None
            if str(value) == str(profile['identity']['expected']):
                verified_rechecks.append(index)
        realm_contrast[version] = {
            'state': data['state'], 'attempts': data['actualTransportRequests'],
            'merchant401Observed': bool(denials), 'configuredIdentity200AfterDenial': bool(rechecks),
            'expectedIdentityValueVerifiedAfterDenial': bool(verified_rechecks),
            'continuedAfterIdentityRecheck': bool(verified_rechecks) and verified_rechecks[0] < len(transport) - 1,
        }
    checks['afterContinuesWithVerifiedIdentityAfterOtherRealm401'] = (
        realm_contrast[args.after]['expectedIdentityValueVerifiedAfterDenial']
        and realm_contrast[args.after]['continuedAfterIdentityRecheck']
        and realm_contrast[args.after]['state'] != 'PAUSED'
    )
    apis = []
    for prefix, method in (('/rest/user/login', 'POST'), ('/rest/basket', 'GET'), ('/rest/products/search', 'GET')):
        observations = {}
        for version in stages:
            values = [row for row in raw[version, 'juice-shop', 'root']['findings'] if row['category'] == 'API' and row['method'] == method and prefix in row['url'] + ' ' + row['detail']]
            observations[version] = {'count': len(values), 'evidence': [{key: row[key] for key in ('originalId', 'line', 'offset')} for row in values]}
        apis.append({'pathOrPrefix': prefix, 'method': method, 'observations': observations, 'meaning': 'Static source observations; unresolved methods/IDs/origins are not guessed or automatically executed.'})
    bundles = {}
    for version in stages:
        values = [row['bodySHA256'] for row in raw[version, 'juice-shop', 'root']['transport'] if row['status'] == 200 and row['url'].split('?', 1)[0].endswith('/main.js')]
        bundles[version] = {'responses': len(values), 'sha256': sorted(set(values))}
    checks['sameObservedJuiceMainBundle'] = bool(bundles[args.before]['sha256']) and bundles[args.before]['sha256'] == bundles[args.after]['sha256']
    checks['representativeJuiceAPIsMaintained'] = all(observation['count'] > 0 for api in apis for observation in api['observations'].values())
    direct_path = Path(__file__).with_name('realm-direct-verification.json')
    direct = json.loads(direct_path.read_text()) if direct_path.exists() else None
    checks['separateControlledRealmDirectPairVerified'] = bool(direct) and (
        direct['result'] == 'passed' and all(direct['checks'].values())
        and direct['before']['jarSHA256'] == args.before_sha and direct['after']['jarSHA256'] == args.after_sha
    )
    summary = {
        'schemaVersion': 1, 'generatedAtUTC': datetime.now(timezone.utc).isoformat(),
        'beforeVersion': args.before, 'afterVersion': args.after,
        'scope': 'Fresh paired runs of seven original local applications via production ScopeInspectionRunner/Katana and reconstructed HTTP/1.1 adapters. Setup/login/health traffic is excluded. Not native Burp GUI, raw TCP capture, or a whole-site vulnerability benchmark.',
        'result': 'passed' if all(checks.values()) else 'review',
        'resultMeaning': 'Integrity and the explicitly listed regressions only; real STOPPED/PAUSED/partial/failure states are retained and do not mean every application or vulnerability passed.',
        'checks': checks, 'stages': stages,
        'originalApplicationPins': pins, 'representativeJuiceAPIs': apis, 'automaticAuthPauses': pause,
        'separateMerchantRealmContrast': realm_contrast,
        'mainBundleParity': bundles,
        'controlledRealmDirectPair': direct,
        'metadataPinsArePreservedSetupEvidence': True,
        'limitations': [
            'COMPLETED means discovered queue processing ended; partial/unconfirmed responses remain.',
            'Benchmark root may reach the 200-request budget; unvisited cases are not counted as tested.',
            'The 105-second audit wall limit is a harness stop, distinct from a product authentication pause.',
            'Java HttpClient content-length/EOF failures and cancellation are errors, not complete responses.',
            'Development caches, response timestamps and asynchronous queue timing can vary. Transmission counts are not an accuracy or performance score.',
            'Both versions use current credentials and byte-identical private input snapshots; earlier report measurements are not reused.',
            'Original applications were restarted. Volatile LOOP/VulnerableApp H2 data was recreated from original seeds; Juice training account was recreated through normal registration before both matrices.',
            'Source artifacts, old configs, old results and old reports are preserved. Raw responses and authentication remain outside public Git.',
            'The main root-discovery comparison did not visit merchant/me in the final after run, so its automatic matched-endpoint gate remains false/review. Two separate controlled direct-seed runs verify the same merchant401 with fresh selected-identity200 and anonymous401 controls. Those2 supplemental runs are not added to the28 matrix totals.',
        ],
    }
    retry_path = Path(__file__).with_name('retry-observations.json')
    if retry_path.exists():
        retry = json.loads(retry_path.read_text())
        summary['retryObservations'] = {
            key: retry[key] for key in ('reason', 'initialExcludedOopsRuns', 'oneTargetedPairedRetryOopsRuns', 'maximumRetrySets', 'actualRetrySets', 'executionConcurrencyForBothVersions', 'originalSourceOrConfigChanges', 'oldCorpusPreserved', 'furtherRetriesPlanned')
        }
        summary['limitations'].append('An initial final-JAR Oops root run paused after28 attempts on a real configured-identity HTTP500. The original Next development log showed JSON/webpack errors, and the same frozen credentials later matched identity200 and the expected JSON value; exact causality was not established. All four initial Oops runs (both versions/scenarios) were preserved under /workspace/simple-navigation-retest-runtime/retry-exclusions/oops-transient-development-500 and replaced only by one new paired four-run set. The retry is serial for both versions with unchanged config/credentials; any remaining failure is kept without further retries.')
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'result': summary['result'], 'checks': checks, 'totals': {version: {key: stage[key] for key in ('runCount', 'attempts', 'responseSuccess', 'transportErrors')} for version, stage in stages.items()}}, ensure_ascii=False, indent=2))
    if not all(checks.values()):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
