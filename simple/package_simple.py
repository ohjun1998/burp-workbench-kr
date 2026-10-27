#!/usr/bin/env python3
"""Package Workbench Simple without changing any existing release artifact."""
from pathlib import Path
import argparse
import hashlib
import json
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
                '.classes', '.host-home', '.font-cache'}
    for path in ROOT.rglob('*'):
        relative = path.relative_to(ROOT)
        if path.is_file() and not excluded.intersection(relative.parts) and path.suffix not in ['.pyc', '.building']:
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
    source_zip = destination / f'burp-workbench-simple-{VERSION}-source.zip'
    with zipfile.ZipFile(source_zip) as archive_file:
        if archive_file.testzip():
            raise RuntimeError('Source ZIP CRC failed')
        if set(archive_file.namelist()) != set(current):
            raise RuntimeError('Source ZIP entry set differs')
        for name, expected in current.items():
            if hashlib.sha256(archive_file.read(name)).hexdigest() != expected:
                raise RuntimeError('Source ZIP content differs: ' + name)
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
    artifacts = [staging / jar.name, source_zip]
    checksums = {p.name: sha(p) for p in artifacts}
    (staging / 'SHA256SUMS').write_text(''.join(f'{digest}  {name}\n' for name, digest in checksums.items()), encoding='utf-8')
    (staging / 'manifest.json').write_text(json.dumps({
        'name': 'Workbench Simple', 'version': VERSION, 'baseExtensionVersion': '0.53.4',
        'embeddedMapperVersion': '0.3.5', 'artifacts': checksums,
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
