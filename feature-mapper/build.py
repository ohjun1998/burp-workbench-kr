#!/usr/bin/env python3
"""Build the standalone component, run regressions and create reproducible artifacts."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import zipfile
from release_info import VERSION, source_files

ROOT = Path(__file__).resolve().parent
API_SHA = 'cb852b7b883290fcc1d21b13d80fa10f5b13a0f57335d710d2b1e5956ecb2a94'


def archive(path, files):
    temporary = path.with_suffix(path.suffix + '.building')
    with zipfile.ZipFile(temporary, 'w') as output:
        for name, content in sorted(files.items()):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            output.writestr(info, content)
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--test', action='store_true')
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    api = ROOT / 'deps/montoya-api-2026.7.jar'
    if not api.exists() or hashlib.sha256(api.read_bytes()).hexdigest() != API_SHA:
        raise SystemExit('Montoya API dependency checksum mismatch')
    classes = ROOT / 'build/classes'
    shutil.rmtree(classes, ignore_errors=True)
    classes.mkdir(parents=True)
    compiler = [args.java, 'com.sun.tools.javac.Main', '--release', '17', '-encoding', 'UTF-8']
    subprocess.run(compiler + ['-cp', str(api), '-d', str(classes)] +
                   [str(x) for x in sorted((ROOT / 'src/main/java').rglob('*.java'))], check=True)
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    jar = dist / f'feature-mapper-kr-{VERSION}.jar'
    files = {x.relative_to(classes).as_posix(): x.read_bytes() for x in classes.rglob('*.class')}
    files['META-INF/MANIFEST.MF'] = (
        'Manifest-Version: 1.0\r\nImplementation-Title: Feature Mapper KR\r\n'
        f'Implementation-Version: {VERSION}\r\n\r\n').encode()
    files['LICENSE'] = (ROOT / 'LICENSE').read_bytes()
    archive(jar, files)
    if args.test:
        tests = ROOT / 'build/tests'
        shutil.rmtree(tests, ignore_errors=True)
        tests.mkdir()
        cp = os.pathsep.join(map(str, [api, jar]))
        subprocess.run(compiler + ['-cp', cp, '-d', str(tests)] +
                       [str(x) for x in sorted((ROOT / 'src/test/java').rglob('*.java'))], check=True)
        run = subprocess.run([args.java, '-Djava.awt.headless=true', '-cp',
                              cp + os.pathsep + str(tests), 'kr.featuremapper.MapperTest'],
                             capture_output=True, text=True, encoding='utf-8', timeout=180)
        (ROOT / 'build/test-results.txt').write_text(run.stdout + run.stderr, encoding='utf-8')
        print(run.stdout + run.stderr)
        if run.returncode:
            raise SystemExit(run.returncode)
        subprocess.run(['node', '--test', str(ROOT / 'tests/browser-helper.test.mjs')], check=True)
    helper = {x.relative_to(ROOT / 'browser-helper').as_posix(): x.read_bytes()
              for x in (ROOT / 'browser-helper').rglob('*') if x.is_file()}
    manifest = json.loads(helper['manifest.json'])
    if manifest['version'] != VERSION:
        raise SystemExit('Browser manifest and runtime release version differ')
    archive(dist / f'feature-mapper-browser-{VERSION}.zip', helper)
    sources = source_files()
    archive(dist / f'feature-mapper-kr-{VERSION}-source.zip',
            {x.relative_to(ROOT).as_posix(): x.read_bytes() for x in sources})
    artifacts = [jar, dist / f'feature-mapper-browser-{VERSION}.zip',
                 dist / f'feature-mapper-kr-{VERSION}-source.zip']
    checksums = {x.name: hashlib.sha256(x.read_bytes()).hexdigest() for x in artifacts}
    (dist / 'SHA256SUMS').write_text(''.join(h + '  ' + n + '\n' for n, h in checksums.items()))
    (dist / 'manifest.json').write_text(json.dumps({
        'version': VERSION, 'artifacts': checksums, 'api_sha256': API_SHA,
        'sources': {x.relative_to(ROOT).as_posix(): hashlib.sha256(x.read_bytes()).hexdigest()
                    for x in sources}}, indent=2) + '\n')
    print('Built', jar, 'and browser/source ZIPs')


if __name__ == '__main__':
    main()
