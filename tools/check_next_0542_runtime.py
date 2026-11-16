#!/usr/bin/env python3
"""Check the exact Next 0.54.2 release and its real provider clients.

Default: integrity, tools, and selected providers' status (both by default).
--offline: integrity and compilation only, no service or login check.
--live: additionally make ONE synthetic inference per selected provider.
No test-target HTTP requests, Burp settings changes, or raw provider outputs.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WB = ROOT / 'project/burp-development-workspace/burp-workbench-kr/burp-workbench'
EXPECTED_SHA = 'd6332965eb841b839feae2053c76d140bd9fee7f7f2691a5a47711ca958551af'

def proof_process(command: list[str], timeout: int) -> subprocess.CompletedProcess:
    """Bound the bridge and its CLI descendants together on POSIX systems."""
    process = subprocess.Popen(command, text=True, encoding='utf-8', errors='replace',
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=os.name == 'posix')
    try:
        stdout, stderr = process.communicate(timeout=timeout)
        return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)
    except subprocess.TimeoutExpired:
        if os.name == 'posix':
            try: os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError: pass
        else: process.terminate()
        try: process.communicate(timeout=2)
        except subprocess.TimeoutExpired:
            if os.name == 'posix':
                try: os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError: pass
            else: process.kill()
            process.communicate()
        raise

def bounded_version(command: list[str], label: str) -> dict:
    try:
        run = subprocess.run(command, text=True, encoding='utf-8', errors='replace',
                             capture_output=True, timeout=10)
        text = run.stdout + '\n' + run.stderr
        if label == 'codex':
            match = re.search(r'codex-cli\s+([0-9.]+)', text)
        else:
            match = re.search(r'(?:javac\s+|version\s+")([0-9][0-9.+_-]*)', text)
        return {'status': 'passed' if run.returncode == 0 and match else 'failed',
                'version': match.group(1) if match else None}
    except (OSError, subprocess.TimeoutExpired):
        return {'status': 'failed', 'failureCategory': 'tool-unavailable-or-timeout'}

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path)
    parser.add_argument('--java', default=shutil.which('java'))
    parser.add_argument('--javac', default=shutil.which('javac'))
    parser.add_argument('--codex', default=shutil.which('codex') or str(Path.home()/'.local/bin/codex'))
    parser.add_argument('--endpoint', default='http://127.0.0.1:8731/v1')
    parser.add_argument('--model', default='halogen-qwen3.8-flash-next')
    parser.add_argument('--subscription-model', default='')
    parser.add_argument('--timeout', type=int, default=30, help='Per-operation seconds, 1..180; default 30')
    parser.add_argument('--max-tokens', type=int, default=8192)
    parser.add_argument('--reasoning', choices=['', 'none', 'low', 'medium', 'high', 'xhigh'], default='low')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--live', action='store_true')
    parser.add_argument('--provider', choices=['local', 'subscription', 'both'], default='both',
                        help='Check only these providers, including status; default both')
    parser.add_argument('--out', type=Path, default=ROOT/'build/next-0542-runtime/result.json')
    args = parser.parse_args()
    if not 1 <= args.timeout <= 180: parser.error('--timeout must be 1..180')
    if not 256 <= args.max_tokens <= 65536: parser.error('--max-tokens must be 256..65536')
    if args.offline and args.live: parser.error('--offline and --live cannot be combined')
    if not args.java or not args.javac: parser.error('Java and javac are required; use --java/--javac')
    jar = args.jar
    if jar is None:
        shipped = ROOT/'downloads/extensions/0.54.2/burp-workbench-kr-0.54.2.jar'
        jar = shipped if shipped.is_file() else WB/'dist/burp-workbench-kr-0.54.2.jar'
    report = {'testedAtUtc': datetime.now(timezone.utc).isoformat(), 'targetHttpRequests': 0,
              'syntheticInputOnly': True, 'rawProviderOutputsStored': False,
              'nativeBurpGuiVerified': False, 'liveRequested': args.live,
              'providerSelection': args.provider,
              'limitations': ['This is a release-client check, not native Burp GUI validation.',
                              'A valid AI response is not evidence of vulnerability-detection accuracy.']}
    try:
        jar_sha = hashlib.sha256(jar.read_bytes()).hexdigest()
        with zipfile.ZipFile(jar) as archive:
            manifest = archive.read('META-INF/MANIFEST.MF').decode('utf-8')
            crc_ok = archive.testzip() is None
        exact = jar_sha == EXPECTED_SHA and 'Implementation-Version: 0.54.2' in manifest and crc_ok
        report['releaseIntegrity'] = {'status': 'passed' if exact else 'failed',
                                      'sha256': jar_sha, 'version': '0.54.2' if exact else None,
                                      'zipCrcValid': crc_ok}
        if not exact: raise RuntimeError('release-integrity')
        report['java'] = bounded_version([args.java, '-version'], 'java')
        report['javac'] = bounded_version([args.javac, '-version'], 'javac')
        report['codex'] = bounded_version([args.codex, '--version'], 'codex')
        deps = [WB/'deps'/n for n in ['montoya-2026.jar', 'legacy-api.jar', 'rhino.jar']]
        if not all(p.is_file() for p in deps): raise RuntimeError('missing-build-dependencies')
        cp = os.pathsep.join(map(str, [jar, *deps]))
        with tempfile.TemporaryDirectory(prefix='next-0542-runtime-') as work:
            compiled = subprocess.run([args.javac, '--release', '17', '-encoding', 'UTF-8',
                '-cp', cp, '-d', work, str(ROOT/'tools/Next0542RuntimeProof.java')],
                text=True, capture_output=True, timeout=30)
            report['proofCompilation'] = {'status': 'passed' if compiled.returncode == 0 else 'failed'}
            if compiled.returncode: raise RuntimeError('proof-compilation')
            if not args.offline:
                modes = ['status' if args.provider == 'both' else args.provider+'-status']
                if args.live: modes += ['local', 'subscription'] if args.provider == 'both' else [args.provider]
                report['operations'] = []
                for mode in modes:
                    command = [args.java, '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8',
                        '-cp', cp+os.pathsep+work, 'kr.workbench.Next0542RuntimeProof', mode,
                        args.endpoint, args.model, args.codex, args.subscription_model,
                        str(args.timeout), str(args.max_tokens), args.reasoning]
                    try:
                        checks = {'status': 3, 'local-status': 2, 'subscription-status': 1}.get(mode, 1)
                        run = proof_process(command, checks*args.timeout+10)
                        if run.returncode: raise RuntimeError('proof-process')
                        operation = json.loads(run.stdout)
                        report['operations'].append({'mode': mode, **operation})
                    except subprocess.TimeoutExpired:
                        report['operations'].append({'mode': mode, 'status': 'failed', 'failureCategory': 'process-timeout'})
                    except (ValueError, RuntimeError):
                        report['operations'].append({'mode': mode, 'status': 'failed', 'failureCategory': 'proof-process'})
        failures = []
        for key in ['releaseIntegrity', 'java', 'javac', 'proofCompilation']:
            if report.get(key, {}).get('status') != 'passed': failures.append(key)
        for operation in report.get('operations', []):
            if operation.get('status') == 'failed': failures.append(operation['mode'])
            for key, value in operation.items():
                if isinstance(value, dict) and value.get('status') == 'failed': failures.append(operation['mode']+':'+key)
                if isinstance(value, dict) and value.get('configuredModelListed') is False:
                    failures.append(operation['mode']+':configured-model-not-listed')
        report['result'] = 'passed' if not failures else 'incomplete'
        report['failedChecks'] = failures
        if args.offline: report['serviceChecks'] = 'not-run-offline'
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile, subprocess.TimeoutExpired) as error:
        report['result'] = 'incomplete'
        report['preparationFailure'] = type(error).__name__
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print('결과 파일:', args.out)
    return 0 if report['result'] == 'passed' else 1

if __name__ == '__main__':
    raise SystemExit(main())
