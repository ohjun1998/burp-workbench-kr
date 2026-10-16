#!/usr/bin/env python3
"""Portable entry point for the supplied Burp Workbench development workspace."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shlex
import shutil
import socket
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT / 'project/burp-development-workspace'
WB = PROJECT / 'burp-workbench-kr/burp-workbench'


def run(command, **kwargs):
    print('+', ' '.join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), check=True, **kwargs)


def toolchain(java, javac):
    runtime = shutil.which(java)
    if not runtime:
        raise RuntimeError('Java 17+ is required; use --java with the executable path.')
    probe = subprocess.run([runtime, 'com.sun.tools.javac.Main', '-version'],
                           capture_output=True, text=True)
    compiler = shutil.which(javac) if javac else shutil.which('javac')
    if javac and not compiler:
        raise RuntimeError('The specified --javac executable was not found.')
    if not compiler and probe.returncode:
        raise RuntimeError('A JDK compiler is required, not a runtime-only JRE.')
    if not compiler and os.name != 'nt':
        folder = ROOT / '.dev-tools'
        folder.mkdir(exist_ok=True)
        wrapper = folder / 'javac'
        wrapper.write_text('#!/bin/sh\nexec ' + shlex.quote(runtime) +
                           ' com.sun.tools.javac.Main "$@"\n')
        wrapper.chmod(0o755)
        compiler = str(wrapper)
    return runtime, compiler


def doctor(runtime, compiler):
    run([runtime, '-version'])
    run([compiler, '-version'] if compiler else
        [runtime, 'com.sun.tools.javac.Main', '-version'])
    print('Python:', sys.version.split()[0])
    print('Project:', PROJECT)
    for name in ['docker', 'mvn', 'git']:
        print(name + ':', shutil.which(name) or 'not installed')
    print('Burp: supplied separately; native/GUI validation is not part of check.')
    print('Build/test: ready. HTTP test:', 'ready' if compiler else
          'requires a native javac executable on Windows.')


def build(runtime, compiler, test=False):
    command = [sys.executable, WB / 'build_all.py', '--java', runtime]
    if compiler:
        command += ['--javac', compiler]
    if test:
        command += ['--test']
    run(command, cwd=WB)


def http_test(runtime, compiler, port):
    if not compiler:
        raise RuntimeError('HTTP tests require javac. Install a JDK and set --javac.')
    # Do not attach to an unrelated service on the selected port.
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', port))
    deps = WB / 'validation/all-features/.run/deps'
    deps.mkdir(parents=True, exist_ok=True)
    hashes = json.loads((WB / 'validation/all-features/runtime-hashes.json').read_text())
    for source in (ROOT / 'reproduction/http-runtime-cache').glob('*.jar'):
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        if hashes.get(source.name) != digest:
            raise RuntimeError('Bundled runtime checksum mismatch: ' + source.name)
        shutil.copy2(source, deps / source.name)
    run([sys.executable, WB / 'validation/all-features/run.py', '--java', runtime,
         '--javac', compiler, '--port', str(port)], cwd=WB)


def bounty_test(runtime, compiler, port):
    if not compiler:
        raise RuntimeError('Community audit requires a JDK compiler.')
    run([sys.executable, ROOT / 'audit/0.23.0/run_audit.py', '--workspace', ROOT,
         '--java', runtime, '--javac', compiler, '--port', str(port)], cwd=ROOT)


def mapper_test(runtime):
    run([sys.executable, ROOT / 'feature-mapper/build.py', '--test', '--java', runtime], cwd=ROOT)


def write_check_summary():
    summary = json.loads((WB / 'build/test-summary.json').read_text())
    for key, path in [
        ('allFeaturesHttpChecks', WB / 'validation/all-features/.run/results.json'),
        ('bountyHttpChecks', ROOT / 'audit/0.23.0/.run/results.json'),
    ]:
        result = json.loads(path.read_text())
        if result['failed'] or result['passed'] != len(result['checks']):
            raise RuntimeError('Incomplete or failed check report: ' + str(path))
        summary[key] = result['passed']
    summary['totalChecks'] = sum(summary[key] for key in [
        'regressionChecks', 'payloadHttpChecks', 'allFeaturesHttpChecks', 'bountyHttpChecks'])
    summary['mapper'] = 'Java and browser-helper regressions passed; see feature-mapper/build/test-results.txt'
    summary['result'] = 'passed'
    (WB / 'build/check-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print('WORKBENCH_CHECK_SUMMARY', json.dumps({k: v for k, v in summary.items() if k != 'groups'}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['doctor', 'build', 'test', 'http-test', 'bounty-test', 'check', 'release'])
    parser.add_argument('--java', default='java')
    parser.add_argument('--javac')
    parser.add_argument('--port', type=int, default=18099)
    parser.add_argument('--bounty-port', type=int, default=18399,
                        help='Separate loopback port for the bounty stage of check/release')
    args = parser.parse_args()
    if any(not 1024 <= port <= 65535 for port in (args.port, args.bounty_port)):
        parser.error('--port and --bounty-port must be between 1024 and 65535')
    if args.command in ('check', 'release') and args.port == args.bounty_port:
        parser.error('check/release require distinct --port and --bounty-port values')
    try:
        runtime, compiler = toolchain(args.java, args.javac)
        if args.command == 'doctor':
            doctor(runtime, compiler)
        elif args.command in ['build', 'test']:
            build(runtime, compiler, args.command == 'test')
        elif args.command == 'http-test':
            http_test(runtime, compiler, args.port)
        elif args.command == 'bounty-test':
            bounty_test(runtime, compiler, args.port)
        else:
            build(runtime, compiler, test=True)
            http_test(runtime, compiler, args.port)
            bounty_test(runtime, compiler, args.bounty_port)
            mapper_test(runtime)
            write_check_summary()
            if args.command == 'release':
                run([sys.executable, ROOT / 'tools/package_downloads.py'])
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print('ERROR:', error, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
