#!/usr/bin/env python3
"""Verify the current release in a separately installed Burp, without user profiles."""
from pathlib import Path
import argparse, os, subprocess, tempfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--burp-jar', required=True, type=Path)
p.add_argument('--java', required=True, help='Java 21+ executable for Burp')
p.add_argument('--compiler-java', default='java')
p.add_argument('--accept-community-license', action='store_true')
p.add_argument('--soak-seconds', type=int, default=0)
p.add_argument('--output', type=Path, default=Path('tmp/native-scope'))
a = p.parse_args()
root = Path(__file__).resolve().parents[2]
wb = root / 'project/burp-development-workspace/burp-workbench-kr/burp-workbench'
import re
version = re.search(r'dist/burp-workbench-kr-([0-9.]+)\.jar', (wb/'build_all.py').read_text()).group(1)
jar = wb / f'dist/burp-workbench-kr-{version}.jar'
a.output.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='workbench-native-scope-') as folder:
    temp = Path(folder)
    for name in ['classes', 'home', 'data']:
        (temp/name).mkdir()
    cp = os.pathsep.join(map(str, [*wb.glob('deps/*.jar'), jar]))
    subprocess.run([a.compiler_java, 'com.sun.tools.javac.Main', '--release', '17', '-cp', cp,
                    '-d', str(temp/'classes'), str(Path(__file__).with_name('NativeScopeCheck.java'))], check=True)
    output = a.output.resolve()/'native-scope-result.txt'
    runtime = os.pathsep.join(map(str, [a.burp_jar.resolve(), temp/'classes', jar]))
    args = [a.java, '-Djava.awt.headless=true', '-Dqa.soakSeconds='+str(a.soak_seconds), '-Duser.home='+str(temp/'home'),
            '-Djava.util.prefs.userRoot='+str(temp/'home'), '-Dqa.output='+str(output), '-cp', runtime,
            'burp.StartBurp', '--data-dir='+str(temp/'data'), '--use-defaults', '--disable-auto-update',
            '--developer-extension-class-name=qa.NativeScopeCheck']
    with (a.output/'burp.log').open('w') as log:
        run = subprocess.run(args, input='y\n' if a.accept_community_license else None, text=True,
                             stdout=log, stderr=subprocess.STDOUT, timeout=240+max(0,a.soak_seconds))
    if output.exists():
        print(output.read_text())
    if run.returncode or not output.exists() or 'NATIVE_SCOPE_SUCCESS' not in output.read_text():
        raise SystemExit(1)
