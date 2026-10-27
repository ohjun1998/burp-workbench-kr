#!/usr/bin/env python3
"""Package Workbench Simple without changing any existing release artifact."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import sys
import zipfile

ROOT = Path(__file__).resolve().parent
WB = ROOT / 'burp-workbench'
sys.path.insert(0, str(WB))
from release_info import VERSION


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def source_files():
    """A complete independent build input, including pinned API dependencies and licenses."""
    inputs = []
    excluded = {'build', 'dist', 'downloads', '__pycache__', 'node_modules', '.run', '.git',
                '.classes', '.host-home', '.font-cache', '.browser-profile',
                'chromium-profile', 'playwright-report'}
    for path in ROOT.rglob('*'):
        relative = path.relative_to(ROOT)
        if path.is_file() and not path.is_symlink() and not excluded.intersection(relative.parts) and path.suffix not in ['.pyc', '.building']:
            inputs.append(path)
    return sorted(inputs)


def archive(destination, files):
    temporary = destination.with_suffix(destination.suffix + '.building')
    with zipfile.ZipFile(temporary, 'w') as output:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            output.writestr(info, data)
    temporary.replace(destination)


def companion_versions():
    mapper = ROOT / 'feature-mapper'
    text = (mapper / 'src/main/java/kr/featuremapper/BuildInfo.java').read_text(encoding='utf-8')
    version = re.search(r'public static final String VERSION\s*=\s*"(\d+\.\d+\.\d+)";', text)
    if not version:
        raise RuntimeError('Embedded Mapper version is missing')
    browser = json.loads((mapper / 'browser-helper/manifest.json').read_text(encoding='utf-8'))
    if browser.get('manifest_version') != 3 or not re.fullmatch(r'\d+\.\d+\.\d+(?:\.\d+)?', str(browser.get('version', ''))):
        raise RuntimeError('Browser helper manifest identity is invalid')
    return version.group(1), browser['version']


def browser_files():
    """Installable helper contents at ZIP root, with the preserved license notices."""
    mapper = ROOT / 'feature-mapper'
    helper = mapper / 'browser-helper'
    files = {}
    excluded = {'__pycache__', 'node_modules', '.git', '.run'}
    for path in sorted(helper.rglob('*')):
        relative = path.relative_to(helper)
        if path.is_file() and not path.is_symlink() and not excluded.intersection(relative.parts) and path.suffix not in ['.pyc', '.building']:
            files[relative.as_posix()] = path.read_bytes()
    required = {'manifest.json', 'background.js', 'content.js', 'mapping.js', 'popup.html',
                'popup.js', 'request-body.js', 'inspection.js', 'inspection-background.js'}
    if not required.issubset(files):
        raise RuntimeError('Browser inspection helper is incomplete: ' + ', '.join(sorted(required - files.keys())))
    for name in ['LICENSE', 'THIRD_PARTY_NOTICES.md']:
        files.setdefault(name, (mapper / name).read_bytes())
    return files


def verify_archive(path, expected):
    with zipfile.ZipFile(path) as packaged:
        damaged = packaged.testzip()
        if damaged:
            raise RuntimeError('ZIP CRC failed: ' + damaged)
        if set(packaged.namelist()) != set(expected):
            raise RuntimeError('ZIP entry set differs: ' + path.name)
        for name, digest in expected.items():
            if hashlib.sha256(packaged.read(name)).hexdigest() != digest:
                raise RuntimeError('ZIP content differs: ' + path.name + '/' + name)


def verify(destination, sources):
    manifest = json.loads((destination / 'manifest.json').read_text(encoding='utf-8'))
    if manifest['version'] != VERSION:
        raise RuntimeError('Release version differs')
    current = {p.relative_to(ROOT).as_posix(): sha(p) for p in sources}
    if current != manifest['sources']:
        raise RuntimeError('Source fingerprint differs: create a new release version after publication')
    for name, expected in manifest['artifacts'].items():
        if sha(destination / name) != expected:
            raise RuntimeError('Artifact hash differs: ' + name)
    verify_archive(destination / f'burp-workbench-simple-{VERSION}-source.zip', current)
    mapper_version, browser_version = companion_versions()
    if manifest.get('embeddedMapperVersion') != mapper_version or manifest.get('browserHelperVersion') != browser_version:
        raise RuntimeError('Companion version differs')
    helper = browser_files()
    helper_sources = {name: hashlib.sha256(data).hexdigest() for name, data in helper.items()}
    if manifest.get('browserHelperSources') != helper_sources:
        raise RuntimeError('Browser helper source fingerprint differs')
    helper_name = f'feature-mapper-browser-helper-{browser_version}.zip'
    if helper_name not in manifest['artifacts']:
        raise RuntimeError('Installable browser helper archive is missing')
    verify_archive(destination / helper_name, helper_sources)
    print('Simple package verified:', destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    destination = ROOT / 'downloads' / VERSION
    sources = source_files()
    if args.verify:
        verify(destination, sources)
        return
    if destination.exists():
        # Never rewrite a published version: an identical package is an idempotent success.
        verify(destination, sources)
        return
    jar = WB / f'dist/burp-workbench-simple-{VERSION}.jar'
    if not jar.exists():
        raise RuntimeError('Build first: python simple/dev.py build')
    with zipfile.ZipFile(jar) as packaged:
        text = packaged.read('META-INF/MANIFEST.MF').decode()
        if 'Implementation-Title: Burp Workbench Simple KR' not in text or f'Implementation-Version: {VERSION}\r\n' not in text:
            raise RuntimeError('JAR identity differs')
        build_inputs = json.loads(packaged.read('META-INF/simple-source.json'))
        toolchain = json.loads(packaged.read('META-INF/simple-build.json'))
        for name, expected in build_inputs.items():
            if sha(ROOT / name) != expected:
                raise RuntimeError('JAR build input has changed; rebuild before packaging: ' + name)
    staging = destination.with_name(destination.name + '.building')
    staging.mkdir(parents=True, exist_ok=False)
    shutil.copy2(jar, staging / jar.name)
    source_zip = staging / f'burp-workbench-simple-{VERSION}-source.zip'
    archive(source_zip, {p.relative_to(ROOT).as_posix(): p.read_bytes() for p in sources})
    mapper_version, browser_version = companion_versions()
    helper = browser_files()
    helper_zip = staging / f'feature-mapper-browser-helper-{browser_version}.zip'
    archive(helper_zip, helper)
    artifacts = [staging / jar.name, source_zip, helper_zip]
    checksums = {p.name: sha(p) for p in artifacts}
    (staging / 'SHA256SUMS').write_text(''.join(f'{digest}  {name}\n' for name, digest in checksums.items()), encoding='utf-8')
    (staging / 'manifest.json').write_text(json.dumps({
        'name': 'Workbench Simple', 'version': VERSION, 'baseExtensionVersion': '0.53.4',
        'embeddedMapperVersion': mapper_version, 'browserHelperVersion': browser_version,
        'browserHelperSources': {name: hashlib.sha256(data).hexdigest() for name, data in helper.items()},
        'artifacts': checksums,
        'buildToolchain': toolchain,
        'sources': {p.relative_to(ROOT).as_posix(): sha(p) for p in sources},
        'originalArtifacts': 'unchanged',
    }, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    verify(staging, sources)
    staging.replace(destination)
    print('Simple release created:', destination)


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, KeyError, zipfile.BadZipFile) as error:
        print('ERROR:', error, file=sys.stderr)
        raise SystemExit(1)
