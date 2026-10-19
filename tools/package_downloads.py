#!/usr/bin/env python3
"""Create immutable, versioned test downloads after `python dev.py check`."""
from pathlib import Path
import argparse
import hashlib
import io
import json
import re
import runpy
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WB = ROOT / 'project/burp-development-workspace/burp-workbench-kr/burp-workbench'
LAB = ROOT / 'project/burp-development-workspace/community-security-lab'
DOWNLOADS = ROOT / 'downloads'
MAPPER = ROOT / 'feature-mapper'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def extension_version():
    return runpy.run_path(str(WB / 'release_info.py'))['VERSION']


def mapper_info():
    return runpy.run_path(str(MAPPER / 'release_info.py'))


def lab_version():
    return re.search(r'<artifactId>loop-community-lab</artifactId><version>([^<]+)', (LAB / 'pom.xml').read_text()).group(1)


def source_files():
    files = [ROOT / 'dev.py', ROOT / 'tools/package_downloads.py']
    for part in ['src', 'deps']:
        files.extend(p for p in (WB / part).rglob('*') if p.is_file())
    files.extend(WB / name for name in ['build_all.py', 'release_info.py', 'shade_rhino.py', 'engine-lock.json', 'LICENSE', 'THIRD_PARTY_NOTICES.md'])
    files.extend(p for p in (WB / 'validation/all-features').glob('*') if p.is_file())
    files.extend(p for p in (ROOT / 'audit/0.23.0').glob('*') if p.is_file() and p.suffix in ('.java', '.py'))
    files.extend(mapper_info()['source_files']())
    return sorted(files)


def docker_files():
    files = [LAB / name for name in ['pom.xml', 'Dockerfile.source', '.dockerignore', 'LICENSE', 'FONT-LICENSE.txt', 'README_KR.md', 'DIAGNOSTICS_3.4_KR.md', 'BOUNTY_LAB_3.5_KR.md', 'compose.yaml', 'compose.verify.yaml', 'start.sh', 'start.ps1', 'verify-stack.sh', 'verify-stack.ps1', 'verify_stack.py']]
    for folder in ['src', 'infra', 'protocols']:
        files.extend(p for p in (LAB / folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix not in ('.pem', '.key', '.crt', '.pyc'))
    return sorted(files)


def fingerprint(files):
    return {p.relative_to(ROOT).as_posix(): sha(p.read_bytes()) for p in files}


def archive(entries):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100755 if name.endswith('.sh') else 0o100644) << 16
            z.writestr(info, data)
    return out.getvalue()


def publish(folder, payloads, sources):
    # Check the complete version before touching any existing release file.
    for name, data in payloads.items():
        destination = folder / name
        if destination.exists() and destination.read_bytes() != data:
            raise RuntimeError(f'Existing version cannot be overwritten: {destination}. Bump the version first.')
    manifest = json.dumps({'files': {n: sha(d) for n, d in payloads.items()}, 'sources': fingerprint(sources)}, ensure_ascii=False, indent=2).encode() + b'\n'
    target = folder / 'manifest.json'
    if target.exists() and target.read_bytes() != manifest:
        raise RuntimeError(f'Existing manifest cannot be overwritten: {target}. Bump the version first.')
    folder.mkdir(parents=True, exist_ok=True)
    for name, data in payloads.items():
        (folder / name).write_bytes(data)
    target.write_bytes(manifest)
    (folder / 'SHA256SUMS').write_text(''.join(f'{sha(data)}  {name}\n' for name, data in payloads.items()), encoding='utf-8')


def verify(folder, expected_sources):
    manifest = json.loads((folder / 'manifest.json').read_text())
    if manifest['sources'] != fingerprint(expected_sources):
        raise RuntimeError(f'Sources changed since packaging: {folder}. Build/check and bump the version.')
    for name, digest in manifest['files'].items():
        if Path(name).name != name or sha((folder / name).read_bytes()) != digest:
            raise RuntimeError(f'Download checksum mismatch: {name}')
    expected = ''.join(f'{digest}  {name}\n' for name, digest in manifest['files'].items())
    if (folder / 'SHA256SUMS').read_text() != expected:
        raise RuntimeError(f'SHA256SUMS mismatch: {folder}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    version, lab = extension_version(), lab_version()
    mapper = mapper_info()
    mapper_version = mapper['VERSION']
    mapper_folder = DOWNLOADS / 'feature-mapper' / mapper_version
    mapper_sources = mapper['source_files']()
    ext_folder, docker_folder = DOWNLOADS / 'extensions' / version, DOWNLOADS / 'docker' / lab
    if args.verify:
        verify(ext_folder, source_files())
        verify(docker_folder, docker_files())
        verify(mapper_folder, mapper_sources)
        print('Versioned download hashes and source fingerprints verified.')
        return
    jar = WB / f'dist/burp-workbench-kr-{version}.jar'
    with zipfile.ZipFile(jar) as z:
        if f'Implementation-Version: {version}' not in z.read('META-INF/MANIFEST.MF').decode():
            raise RuntimeError('Built JAR version does not match source version')
    sources = {p.relative_to(ROOT).as_posix(): p.read_bytes() for p in source_files()}
    publish(ext_folder, {jar.name: jar.read_bytes(), f'burp-workbench-kr-{version}-source.zip': archive(sources)}, source_files())
    mapper_artifacts = [f'feature-mapper-kr-{mapper_version}.jar',
                        f'feature-mapper-browser-{mapper_version}.zip',
                        f'feature-mapper-kr-{mapper_version}-source.zip']
    current_mapper_sources = {p.relative_to(MAPPER).as_posix(): sha(p.read_bytes()) for p in mapper_sources}
    built_mapper = json.loads((MAPPER / 'dist/manifest.json').read_text())
    if built_mapper.get('version') != mapper_version or built_mapper.get('sources') != current_mapper_sources:
        raise RuntimeError('Mapper build is stale; run python feature-mapper/build.py --test')
    mapper_payloads = {name: (MAPPER / 'dist' / name).read_bytes() for name in mapper_artifacts}
    if {name: sha(data) for name, data in mapper_payloads.items()} != built_mapper['artifacts']:
        raise RuntimeError('Mapper artifact checksums do not match its build manifest')
    publish(mapper_folder, mapper_payloads, mapper_sources)
    files = {p.relative_to(LAB).as_posix(): p.read_bytes() for p in docker_files()}
    # Compile the current source in Docker; never ship a stale prebuilt WAR.
    files['Dockerfile'] = files['Dockerfile.source']
    compose = files['compose.yaml'].decode().replace('enterprise-lab-was:3.0', f'enterprise-lab-was:{lab}')
    files['compose.yaml'] = compose.encode()
    files['START_HERE_KR.md'] = f'''# 커뮤니티 실습 Docker 패키지 {lab}

ZIP을 한 폴더에 풀고 Docker Desktop 또는 Docker Engine + Compose v2를 실행하세요.
최초 빌드에는 이미지와 Maven 의존성 다운로드를 위한 인터넷 연결이 필요합니다.

Windows PowerShell: `./start.ps1`
Linux/macOS: `sh start.sh`
공통: `docker compose up -d --build --wait --wait-timeout 900`

접속: http://127.0.0.1:8088
상태 확인: `docker compose ps`
로그: `docker compose logs --tail 100`
종료: `docker compose down` (DB 볼륨 보존)

이 패키지는 현재 소스를 Docker 안에서 빌드합니다. 사전 빌드 이미지/오프라인 설치 파일이 아닙니다.
Oracle 시작에 시간이 필요합니다. 포트 8088, 8443, 8091, 8092 충돌을 먼저 확인하세요.
실습용 자격정보를 포함한 로컬 학습 사이트입니다. 모든 공개 포트는 127.0.0.1로 제한합니다.
현재 배포의 실제 Docker/Oracle/Nginx 기동 검증은 미실행입니다.
새 모의 버그바운티 실습: http://127.0.0.1:8088/bounty-lab
자세한 테스트 절차는 README_KR.md 및 DIAGNOSTICS_3.4_KR.md를 확인하세요.
'''.encode()
    publish(docker_folder, {f'community-security-lab-{lab}-docker.zip': archive(files)}, docker_files())
    # Preserve the original shipped 0.21.0 extension as an easy download as well.
    old = ROOT / 'release/burp-workbench-kr-0.21.0.jar'
    if old.exists():
        publish(DOWNLOADS / 'extensions/0.21.0', {old.name: old.read_bytes()}, [old])
    print(f'Prepared extensions/{version} and docker/{lab}')


if __name__ == '__main__':
    main()
