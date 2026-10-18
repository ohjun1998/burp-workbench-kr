#!/usr/bin/env python3
"""Start an isolated H2/Tomcat site and compare production authorization verdicts."""
import argparse
import base64
import hashlib
import json
import os
import runpy
import re
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
LAB = ROOT / 'project/burp-development-workspace/community-security-lab'
WB = ROOT / 'project/burp-development-workspace/burp-workbench-kr/burp-workbench'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--mode', choices=['native', 'adapter'], default='adapter')
    p.add_argument('--port', type=int, default=0)
    p.add_argument('--java', default='java')
    p.add_argument('--extension-version', default=runpy.run_path(str(WB / 'release_info.py'))['VERSION'])
    p.add_argument('--burp-jar', type=Path)
    p.add_argument('--burp-java')
    p.add_argument('--browser', action='store_true')
    p.add_argument('--accept-community-license',action='store_true',help='Explicitly accept the vendor license in a new native test profile')
    p.add_argument('--out', type=Path, default=ROOT / 'tmp/auth-lab')
    args = p.parse_args();args.out = args.out.resolve();args.out.mkdir(parents=True, exist_ok=True)
    if args.mode == 'native' and (not args.burp_jar or not args.burp_java or not args.accept_community_license):
        p.error('native mode requires --burp-jar, --burp-java and --accept-community-license; a separate temporary profile is used')
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', args.port));port = probe.getsockname()[1]
    base = f'http://127.0.0.1:{port}'
    runtime = WB / 'validation/all-features';hashes = json.loads((runtime / 'runtime-hashes.json').read_text());jars = []
    for name, digest in hashes.items():
        path = runtime / '.run/deps' / name
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise RuntimeError('Prepare verified HTTP runtime with python dev.py http-test: ' + name)
        jars.append(str(path))
    release = ROOT / 'downloads/extensions' / args.extension_version
    version = json.loads((release / 'manifest.json').read_text())
    jar = release / f'burp-workbench-kr-{args.extension_version}.jar'
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == version['files'][jar.name]
    server = None;success = False
    with tempfile.TemporaryDirectory(prefix='auth-lab-', dir=args.out) as private:
        private = Path(private);site = private / 'site';site.mkdir();classes = private / 'classes';classes.mkdir()
        cp = os.pathsep.join(jars)
        subprocess.run([args.java, 'com.sun.tools.javac.Main', '--release', '17', '-parameters', '-encoding', 'UTF-8', '-cp', cp, '-d', str(site)] + [str(f) for f in sorted((LAB / 'src/main/java').rglob('*.java'))], check=True)
        shutil.copytree(LAB / 'src/main/resources', site, dirs_exist_ok=True)
        testcp = os.pathsep.join([str(jar), str(WB / 'deps/montoya-2026.jar'), str(WB / 'deps/legacy-api.jar'), str(runtime / '.run/classes')])
        subprocess.run([args.java, 'com.sun.tools.javac.Main', '--release', '17', '-encoding', 'UTF-8', '-cp', testcp, '-d', str(classes)] + [str(f) for f in sorted((ROOT / 'audit/auth-combinations').glob('*.java'))], check=True)
        signing_key = os.urandom(32)
        environment = dict(os.environ, PORT=str(port), DB_URL='jdbc:h2:mem:auth_' + uuid.uuid4().hex + ';MODE=Oracle;DB_CLOSE_DELAY=-1', DB_USER='sa', DB_PASSWORD='', AUTH_LAB_SIGNING_KEY_B64=base64.b64encode(signing_key).decode())
        try:
            with (args.out / 'server.log').open('w') as log:
                server = subprocess.Popen([args.java, '-cp', str(site) + os.pathsep + cp, 'lab.enterprise.DevServer'], env=environment, stdout=log, stderr=subprocess.STDOUT)
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                for _ in range(100):
                    if server.poll() is not None:raise RuntimeError('Lab startup failed; inspect server.log')
                    try:
                        with opener.open(base + '/health', timeout=2) as response:
                            if response.status == 200:break
                    except OSError:pass
                    time.sleep(.2)
                else:raise RuntimeError('Lab startup timeout')
                from auth_lab_http_checks import run
                http = run(base, signing_key)
                (args.out / 'http-results.json').write_text(json.dumps(http, ensure_ascii=False, indent=2) + '\n')
                print('HTTP authentication checks passed:', http['checks'], flush=True)
                if args.mode == 'native':
                    home = private / 'home';home.mkdir();data = private / 'burp-data';data.mkdir()
                    config = private / 'project.json';config.write_text(json.dumps({'proxy': {'request_listeners': []}}))
                    nativecp = os.pathsep.join([str(args.burp_jar.resolve()), str(classes), str(jar)])
                    command = [args.burp_java, '-Djava.awt.headless=true', '-Duser.home=' + str(home), '-Djava.util.prefs.userRoot=' + str(home), '-Dqa.base=' + base, '-Dqa.out=' + str(args.out), '-cp', nativecp, 'burp.StartBurp', '--data-dir=' + str(data), '--use-defaults', '--disable-auto-update', '--config-file=' + str(config), '--developer-extension-class-name=kr.workbench.NativeAuthLab']
                else:
                    command = [args.java, '-Djava.awt.headless=true', '-cp', str(classes) + os.pathsep + testcp, 'kr.workbench.AdapterAuthLab', base, str(args.out)]
                with (args.out / 'extension.log').open('w') as native_log:
                    result = subprocess.run(command, input='y\n' if args.accept_community_license else None, text=True, stdout=native_log, stderr=subprocess.STDOUT, timeout=420)
                if result.returncode:raise RuntimeError('Extension check failed; inspect extension.log')
                extension = json.loads((args.out / 'extension-results.json').read_text());assert extension['extensionVersion'] == args.extension_version;assert extension['result'] == 'passed'
                if args.browser:
                    subprocess.run(['node', str(ROOT / 'tools/auth_lab_ui_checks.mjs'), base, str(args.out)], check=True, cwd=ROOT)
                summary = {'result': 'passed', 'labVersion': re.search(r'<artifactId>loop-community-lab</artifactId><version>([^<]+)', (LAB/'pom.xml').read_text()).group(1), 'extensionVersion': args.extension_version, 'extensionJarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'transport': extension['transport'], 'nativeExtensionLoaded': extension['nativeExtensionLoaded'], 'httpChecks': http['checks'], 'httpTargetRequests': http['targetHttpRequests'], 'extensionComparisonRuns': extension['comparisonRuns'], 'extensionComparedAccountCells': extension['comparedAccountCells'], 'extensionIdentityChecks': len(extension['identityChecks']), 'extensionTargetRequests': extension['targetHttpRequests'], 'baseUrl': base, 'database': 'isolated H2 in Oracle compatibility mode', 'browserExecuted': args.browser, 'privateProfile': True, 'credentialsIncludedInEvidence': False}
                if args.mode == 'native':summary.update({'burpVersion': extension['burpVersion'], 'javaVersion':extension['javaVersion'], 'hostUiWarningCount':(args.out/'extension.log').read_text().count('java.lang.Error: no ComponentUI'), 'burpJarSha256': hashlib.sha256(args.burp_jar.read_bytes()).hexdigest()})
                (args.out / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n');success = True;print(json.dumps(summary, ensure_ascii=False), flush=True)
        finally:
            if server is not None:
                server.terminate()
                try:server.wait(timeout=10)
                except subprocess.TimeoutExpired:server.kill();server.wait(timeout=5)
            if success:
                with socket.socket() as probe:closed = probe.connect_ex(('127.0.0.1', port)) != 0
                summary['ownedServerStopped'] = closed
                (args.out / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
                if not closed:raise RuntimeError('Owned server did not stop')


if __name__ == '__main__':
    main()
