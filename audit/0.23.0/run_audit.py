"""Bounded local bounty workflow audit. Run after dev.py check prepares runtime dependencies.

Uses the current extension release JAR and compiles current lab source. No native
Burp, browser DOM, Docker, external targets, or historical WAR application code.
"""
from pathlib import Path
import runpy
import argparse
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--workspace', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--port', type=int, default=18399)
    parser.add_argument('--java', default='java')
    parser.add_argument('--javac')
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        raise SystemExit('port range: 1024..65535')
    root = args.workspace.resolve()
    wb = root / 'project/burp-development-workspace/burp-workbench-kr/burp-workbench'
    lab = root / 'project/burp-development-workspace/community-security-lab'
    here = Path(__file__).resolve().parent
    output = here / '.run'
    output.mkdir(exist_ok=True)
    classes, site = output / 'classes', output / 'site'
    for directory in (classes, site):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir()
    version = runpy.run_path(str(wb / 'release_info.py'))['VERSION']
    lab_version = re.search(r'<artifactId>loop-community-lab</artifactId><version>([^<]+)', (lab / 'pom.xml').read_text()).group(1)
    jar = wb / f'dist/burp-workbench-kr-{version}.jar'
    if not jar.is_file():
        raise SystemExit('Build the current release JAR first: python dev.py build')
    runtime = wb / 'validation/all-features'
    deps = runtime / '.run/deps'
    hashes = json.loads((runtime / 'runtime-hashes.json').read_text())
    def digest(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()
    for name, expected in hashes.items():
        path = deps / name
        if not path.is_file() or digest(path) != expected:
            raise SystemExit('Missing/mismatched runtime: ' + name + '; run python dev.py http-test first')
    # Only allowlisted dependencies enter the classpath; no arbitrary local JARs.
    dependency_cp = os.pathsep.join(str(deps / name) for name in sorted(hashes))
    test_cp = os.pathsep.join([str(jar), str(wb / 'deps/montoya-2026.jar'), str(wb / 'deps/legacy-api.jar'), dependency_cp])
    compiler = [args.javac] if args.javac else [args.java, 'com.sun.tools.javac.Main']
    source_paths = [path for folder in (wb / 'src/main', lab / 'src') for path in sorted(folder.rglob('*')) if path.is_file()]
    before = {str(path.relative_to(root)): digest(path) for path in source_paths}
    subprocess.run(compiler + ['--release', '17', '-parameters', '-encoding', 'UTF-8', '-cp', dependency_cp, '-d', str(site)] + [str(path) for path in sorted((lab / 'src/main/java').rglob('*.java'))], check=True)
    shutil.copytree(lab / 'src/main/resources', site, dirs_exist_ok=True)
    subprocess.run(compiler + ['--release', '17', '-encoding', 'UTF-8', '-cp', test_cp, '-d', str(classes)] + [str(path) for path in sorted((wb / 'src/test/java').rglob('*.java'))], check=True)
    test_cp = str(classes) + os.pathsep + test_cp
    subprocess.run(compiler + ['--release', '17', '-encoding', 'UTF-8', '-cp', test_cp, '-d', str(classes), str(runtime / 'CommunityExtensionAudit.java'), str(here / 'BountyWorkflowAudit.java')], check=True)
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', args.port))
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    server = None
    result = None
    for name in ('results.json', 'integrity.json', 'http-evidence.zip'):
        (output / name).unlink(missing_ok=True)
    with (output / 'server.log').open('w', encoding='utf-8') as log:
        try:
            server = subprocess.Popen([args.java, '-cp', str(site) + os.pathsep + dependency_cp, 'lab.enterprise.DevServer'], env=dict(os.environ, PORT=str(args.port)), stdout=log, stderr=subprocess.STDOUT)
            for _ in range(100):
                if server.poll() is not None:
                    raise RuntimeError('Lab startup failed; see .run/server.log')
                try:
                    with opener.open(f'http://127.0.0.1:{args.port}/health', timeout=1) as response:
                        if response.status == 200:
                            break
                except OSError:
                    time.sleep(.2)
            else:
                raise RuntimeError('Lab startup timeout')
            result = subprocess.run([args.java, '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8', '-cp', test_cp, 'kr.workbench.BountyWorkflowAudit', str(args.port), str(output), version, lab_version], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=240)
            (output / 'audit.log').write_text(result.stdout + result.stderr, encoding='utf-8')
            print(result.stdout + result.stderr)
        finally:
            if server:
                server.terminate()
                try:
                    server.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait(timeout=5)
            with socket.socket() as probe:
                closed = probe.connect_ex(('127.0.0.1', args.port)) != 0
            unchanged = before == {str(path.relative_to(root)): digest(path) for path in source_paths}
            integrity = {'extensionVersion': version, 'labVersion': lab_version, 'jarSha256': digest(jar), 'sourceFiles': len(before), 'sourceFingerprint': hashlib.sha256(json.dumps(before, sort_keys=True).encode()).hexdigest(), 'productionFilesUnchanged': unchanged, 'serverPortClosed': closed, 'nativeBurp': False, 'browserDOM': False, 'docker': False, 'database': 'isolated H2 Oracle mode', 'currentLabSourceCompiled': True}
            (output / 'integrity.json').write_text(json.dumps(integrity, indent=2) + '\n', encoding='utf-8')
    if not unchanged or not closed:
        raise SystemExit('Audit isolation/integrity check failed')
    print('Bounty workflow results:', output / 'results.json')
    return result.returncode


if __name__ == '__main__':
    raise SystemExit(main())
