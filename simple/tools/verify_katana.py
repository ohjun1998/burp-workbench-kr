#!/usr/bin/env python3
"""Opt-in pinned Katana native integration against isolated real HTTP/HTTPS targets.

Requires the current Simple JAR and compiled test adapters from `simple/dev.py check`.
This command compiles only the opt-in harness. It never rebuilds or modifies the JAR,
downloads Katana, installs a CA, launches Burp GUI, or assesses external targets.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import runpy
import signal
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
WB = ROOT / 'burp-workbench'
BINARY_SHA256 = '3214c11a0548667b37ec17bbb691706b7e0de1f0f24b3ead2ee12c301ebc1bbc'
ARCHIVE_SHA256 = 'cb027ae99d0757061b70c07a15c7a80f62c5a535ab52c4a329f80c4b0a7309d2'
PRIVATE_VALUES = (
    'SID=NATIVE_ONLY_AUTH_VALUE_0123456789abcdef',
    'NATIVE_ONLY_AUTH_VALUE_0123456789abcdef',
    'NATIVE_DEMO_API_PRIVATE_0123456789abcdef',
    'NATIVE_DB_PRIVATE_0123456789abcdef',
)


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def sanitized(value: str) -> str:
    for private in PRIVATE_VALUES:
        value = value.replace(private, '[REDACTED]')
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--katana', required=True, type=Path, help='Explicit executable from the pinned official 1.8.0 linux_amd64 archive')
    parser.add_argument('--out', type=Path, help='Result directory; defaults to docs/verification/<version>/katana-native')
    parser.add_argument('--java', default='java')
    parser.add_argument('--source-target', action='store_true', help='Explicit source/target 17 fallback; Java 17 platform APIs are not verified')
    args = parser.parse_args()
    version = runpy.run_path(str(WB / 'release_info.py'))['VERSION']
    binary = args.katana.expanduser().resolve()
    if not binary.is_file() or not os.access(binary, os.X_OK):
        parser.error('The explicit Katana path must be an executable regular file.')
    actual_sha = digest(binary)
    if actual_sha != BINARY_SHA256:
        parser.error('Katana binary SHA-256 does not match the pinned official 1.8.0 linux_amd64 extraction.')
    jar = WB / f'dist/burp-workbench-simple-{version}.jar'
    adapters = WB / 'build/tests'
    required_adapters = ('WorkbenchTest.class', 'SimpleWorkflowTest.class', 'ScopeInspectionFixture.class')
    if not jar.is_file() or any(not (adapters / 'kr/workbench' / name).is_file() for name in required_adapters):
        parser.error('Build and check the current Simple JAR/test adapters first; this verifier never rebuilds the JAR.')
    jar_before = digest(jar)
    output = (args.out or ROOT / 'docs/verification' / version / 'katana-native').resolve()
    output.mkdir(parents=True, exist_ok=True)
    classes, home, temporary = output / '.classes', output / '.host-home', output / '.tmp'
    for path in (classes, home, temporary):
        path.mkdir(exist_ok=True)
    cp = os.pathsep.join(map(str, [jar, *sorted((WB / 'deps').glob('*.jar')), adapters]))
    compiler = ['-source', '17', '-target', '17'] if args.source_target else ['--release', '17']
    source = WB / 'src/test/java/kr/workbench/ScopeKatanaNativeHarness.java'
    compile_result = subprocess.run(
        [args.java, 'com.sun.tools.javac.Main', *compiler, '-encoding', 'UTF-8',
         '-cp', cp, '-d', str(classes), str(source)],
        capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=120,
    )
    compile_log = sanitized(compile_result.stdout + compile_result.stderr)
    (output / 'compile.log').write_text(compile_log, encoding='utf-8')
    if compile_result.returncode:
        print(compile_log, end='')
        return compile_result.returncode
    result_file = output / 'native-results.json'
    # Remove only this verifier's own previous result so a failed run cannot reuse a success.
    result_file.unlink(missing_ok=True)
    command = [args.java, '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8',
               f'-Duser.home={home}', f'-Djava.io.tmpdir={temporary}',
               '-cp', str(classes) + os.pathsep + cp, 'kr.workbench.ScopeKatanaNativeHarness',
               str(binary), str(result_file)]
    started = datetime.now(timezone.utc).isoformat()
    execution = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                 text=True, encoding='utf-8', errors='replace', start_new_session=True)
    try:
        stdout, stderr = execution.communicate(timeout=360)
        return_code = execution.returncode
    except subprocess.TimeoutExpired:
        try:
            os.killpg(execution.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            stdout, stderr = execution.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(execution.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            stdout, stderr = execution.communicate()
        return_code = 124
    raw_log = stdout + stderr
    leaked = any(private in raw_log for private in PRIVATE_VALUES)
    log = sanitized(raw_log)
    (output / 'execution.log').write_text(log, encoding='utf-8')
    print(log, end='')
    harness = {}
    if result_file.is_file():
        text = result_file.read_text(encoding='utf-8')
        if any(private in text for private in PRIVATE_VALUES):
            leaked = True
            result_file.write_text(sanitized(text), encoding='utf-8')
        harness = json.loads(sanitized(text))
    else:
        result_file.write_text(json.dumps({'result': 'failed', 'reason': 'Harness did not produce its result; see sanitized execution.log'}, indent=2) + '\n', encoding='utf-8')
    java = subprocess.run([args.java, '-version'], capture_output=True, text=True,
                          encoding='utf-8', errors='replace', timeout=30)
    jar_after = digest(jar)
    passed = (return_code == 0 and harness.get('result') == 'passed'
              and harness.get('version') == version and jar_after == jar_before and not leaked)
    summary = {
        'result': 'passed' if passed else 'failed',
        'version': version,
        'startedAt': started,
        'artifact': jar.relative_to(ROOT).as_posix(),
        'jarSha256': jar_after,
        'jarUnchanged': jar_after == jar_before,
        'katanaVersion': '1.8.0',
        'platform': 'linux_amd64',
        'binarySha256': actual_sha,
        'officialArchiveSha256': ARCHIVE_SHA256,
        'officialArchive': 'https://github.com/projectdiscovery/katana/releases/download/v1.8.0/katana_1.8.0_linux_amd64.zip',
        'binaryIdentity': 'Pinned extraction hash from the official release archive; installer independently checks the official archive hash.',
        'toolchainMode': 'explicit-source17-target17-api-unverified' if args.source_target else 'strict-release17',
        'java17ApiCompatibilityVerified': not args.source_target,
        'javaRuntime': (java.stdout + java.stderr).strip(),
        'checks': harness.get('checks', 0),
        'cases': harness.get('cases', []),
        'privateValueLeakDetected': leaked,
        'exitCode': return_code,
        'scope': 'Actual pinned standard Katana process, production authenticated proxy and shared-budget Runner against isolated loopback HTTP/HTTPS targets. Not native Burp GUI, live browser DOM or an external target assessment.',
        'tlsTrust': 'Temporary in-memory fixture certificate trust only; no system/user CA installation.',
    }
    (output / 'katana-verification.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: summary[key] for key in ('result', 'version', 'checks', 'binarySha256', 'jarSha256', 'exitCode')}, ensure_ascii=False))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
