#!/usr/bin/env python3
"""Benign real Burp smoke; never accepts a license or uses an existing user profile."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--burp-jar', type=Path, required=True)
parser.add_argument('--java', required=True, help='Java 21+ for Burp')
parser.add_argument('--compiler-java', default='java')
parser.add_argument('--workbench-jar', type=Path, required=True)
parser.add_argument('--output', type=Path, default=Path('tmp/native-smoke-052'))
a = parser.parse_args()
root = Path(__file__).resolve().parents[2]
wb = root / 'project/burp-development-workspace/burp-workbench-kr/burp-workbench'
out = a.output.resolve()
out.mkdir(parents=True, exist_ok=True)
result = out / 'native-smoke-result.txt'
if any((out/name).exists() for name in ['native-smoke-result.txt', 'burp.log', 'compile.log', 'summary.json']):
    raise SystemExit('Choose a new output directory; previous native smoke evidence is preserved.')

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

summary = {'scope': 'installation, eight tools plus Settings, OFF/SCOPE, owned loopback hello GET, raw byte storage and unload only',
           'workbenchSha256': digest(a.workbench_jar), 'burpSha256': digest(a.burp_jar),
           'manualDesktopClicks': False, 'licenseAcceptedByHarness': False, 'existingProfileUsed': False}
start = time.monotonic()
with tempfile.TemporaryDirectory(prefix='native-smoke-profile-', dir=out) as folder:
    temp = Path(folder)
    for name in ['classes', 'home', 'data']:
        (temp / name).mkdir()
    cp = os.pathsep.join(map(str, [*wb.glob('deps/*.jar'), a.workbench_jar.resolve()]))
    compile_run = subprocess.run([a.compiler_java, 'com.sun.tools.javac.Main', '--release', '17', '-encoding', 'UTF-8',
                                 '-cp', cp, '-d', str(temp/'classes'), str(Path(__file__).with_name('NativeSmoke.java'))],
                                text=True, capture_output=True)
    (out/'compile.log').write_text(compile_run.stdout + compile_run.stderr)
    if compile_run.returncode:
        summary.update(status='COMPILE_FAILED', returncode=compile_run.returncode)
    else:
        runtime = os.pathsep.join(map(str, [a.burp_jar.resolve(), temp/'classes', a.workbench_jar.resolve()]))
        command = [a.java, '-Djava.awt.headless=true', '-Duser.home=' + str(temp/'home'),
                   '-Djava.util.prefs.userRoot=' + str(temp/'home'), '-Dqa.output=' + str(result),
                   '-cp', runtime, 'burp.StartBurp', '--data-dir=' + str(temp/'data'), '--use-defaults',
                   '--disable-auto-update', '--developer-extension-class-name=qa.NativeSmoke']
        with (out/'burp.log').open('w') as log:
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            summary['ownedPid'] = process.pid
            timed_out = False
            try:
                deadline = time.monotonic() + 90
                license_wait = False
                while process.poll() is None and time.monotonic() < deadline:
                    time.sleep(.25)
                    if any(prompt in (out/'burp.log').read_text(errors='replace') for prompt in ['Do you accept the terms and conditions? (y/n)', 'Do you accept the license agreement? (y/n)']):
                        license_wait = True
                        break
                if process.poll() is None:
                    timed_out = not license_wait
                    os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)
            finally:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
            summary.update(returncode=process.returncode, timedOut=timed_out)
            try:
                os.killpg(process.pid, 0)
                summary['ownedProcessGroupStopped'] = False
            except ProcessLookupError:
                summary['ownedProcessGroupStopped'] = True
        log_text = (out/'burp.log').read_text(errors='replace')
        native = result.read_text(errors='replace') if result.exists() else ''
        summary['checksPassed'] = sum(line.startswith('PASS ') for line in native.splitlines())
        summary['licensePromptObserved'] = any(prompt in log_text for prompt in ['Do you accept the terms and conditions? (y/n)', 'Do you accept the license agreement? (y/n)'])
        summary['status'] = ('PASS' if process.returncode == 0 and 'NATIVE_SMOKE_SUCCESS' in native
                             else 'LICENSE_REQUIRED' if summary['licensePromptObserved'] and not native
                             else 'BLOCKED_OR_FAILED')
summary['temporaryProfileRemoved'] = not temp.exists()
summary['durationSeconds'] = round(time.monotonic() - start, 2)
(out/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(summary, ensure_ascii=False, indent=2))
if result.exists(): print(result.read_text())
elif summary.get('licensePromptObserved'):
    print('Burp requested license acceptance before extension startup. No acceptance was supplied; no native feature checks ran. See burp.log.')
else: print((out/'burp.log').read_text(errors='replace')[-3500:] if (out/'burp.log').exists() else (out/'compile.log').read_text())
raise SystemExit(0 if summary['status'] == 'PASS' else 2)
