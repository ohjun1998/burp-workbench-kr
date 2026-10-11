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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['doctor', 'build', 'test', 'http-test', 'check'])
    parser.add_argument('--java', default='java')
    parser.add_argument('--javac')
    parser.add_argument('--port', type=int, default=18099)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error('--port must be between 1024 and 65535')
    try:
        runtime, compiler = toolchain(args.java, args.javac)
        if args.command == 'doctor':
            doctor(runtime, compiler)
        elif args.command in ['build', 'test']:
            build(runtime, compiler, args.command == 'test')
        elif args.command == 'http-test':
            http_test(runtime, compiler, args.port)
        else:
            build(runtime, compiler, test=True)
            http_test(runtime, compiler, args.port)
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print('ERROR:', error, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
