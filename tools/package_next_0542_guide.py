#!/usr/bin/env python3
"""Package the public Next 0.54.2 guide and runtime-check files, without network.

The source release JAR/ZIP are verified and never modified. Only guide outputs
and the optional user-selected standalone copy are regenerated.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
from html.parser import HTMLParser
import html
import io
import json
from pathlib import Path
import re
import sys
from urllib.parse import quote, urlsplit
import zipfile


ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.54.2"
BRANCH = "account-a/next-0542-runtime-guide"
GUIDE = ROOT / "docs/guides" / f"next-{VERSION}"
RELEASE = ROOT / "downloads/extensions" / VERSION
STANDALONE = f"Workbench-Next-{VERSION}-사용가이드.html"
BUNDLE = f"Workbench-Next-{VERSION}-설치와가이드.zip"
PREFIX = f"Workbench-Next-{VERSION}/"
EXPECTED_IMAGES = (
    "01-ai-start.png", "02-settings-local.png", "03-settings-subscription.png",
    "04-functions.png", "05-outbound-preview.png", "06-report-draft.png",
    "07-request-approval.png",
)
WB = "project/burp-development-workspace/burp-workbench-kr/burp-workbench"
# Explicit public allowlist; never recurse into user/configuration directories.
PUBLIC_FILES = (
    f"downloads/extensions/{VERSION}/burp-workbench-kr-{VERSION}.jar",
    f"downloads/extensions/{VERSION}/burp-workbench-kr-{VERSION}-source.zip",
    f"downloads/extensions/{VERSION}/SHA256SUMS",
    f"downloads/extensions/{VERSION}/manifest.json",
    "tools/check_next_0542_runtime.py",
    "tools/Next0542RuntimeProof.java",
    f"docs/RELEASE_{VERSION}_NEXT_KR.md",
    f"docs/VERIFICATION_{VERSION}.json",
    f"{WB}/deps/montoya-2026.jar",
    f"{WB}/deps/legacy-api.jar",
    f"{WB}/deps/rhino.jar",
)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class LocalResources(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.resources: list[tuple[str, str]] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attrs_map = dict(attrs)
        if tag in ("script", "img", "iframe", "source", "video", "audio", "link"):
            value = attrs_map.get("src") or attrs_map.get("href")
            if value:
                self.resources.append((tag, value))


def checked_zip(data: bytes, label: str) -> None:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        bad = archive.testzip()
        if bad:
            raise ValueError(f"ZIP CRC mismatch: {label}: {bad}")


def verify_release(inputs: dict[str, bytes]) -> None:
    manifest_path = f"downloads/extensions/{VERSION}/manifest.json"
    manifest = json.loads(inputs[manifest_path])
    expected_names = {f"burp-workbench-kr-{VERSION}.jar", f"burp-workbench-kr-{VERSION}-source.zip"}
    if set(manifest["files"]) != expected_names:
        raise ValueError("Release manifest must name only the expected JAR and source ZIP")
    for name, digest in manifest["files"].items():
        data = inputs[f"downloads/extensions/{VERSION}/{name}"]
        if sha(data) != digest:
            raise ValueError(f"Release manifest checksum mismatch: {name}")
        checked_zip(data, name)
    sums = inputs[f"downloads/extensions/{VERSION}/SHA256SUMS"].decode("utf-8")
    parsed = {}
    for line in sums.splitlines():
        if not line.strip():
            continue
        digest, name = line.split(maxsplit=1)
        parsed[name.removeprefix("*")] = digest
    if parsed != manifest["files"]:
        raise ValueError("Release SHA256SUMS does not match manifest.json")
    for name in PUBLIC_FILES:
        if "/deps/" in name:
            expected = manifest["sources"].get(name)
            if expected is None or sha(inputs[name]) != expected:
                raise ValueError(f"Public dependency checksum mismatch: {name}")
    with zipfile.ZipFile(io.BytesIO(inputs[f"downloads/extensions/{VERSION}/burp-workbench-kr-{VERSION}.jar"])) as jar:
        identity = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
        if not re.search(rf"(?m)^Implementation-Version: {re.escape(VERSION)}\r?$", identity):
            raise ValueError("Release JAR has an unexpected version")


def standalone(inputs: dict[str, bytes]) -> bytes:
    html_path = GUIDE / "index.html"
    source = inputs[html_path.relative_to(ROOT).as_posix()].decode("utf-8")
    css = inputs[(GUIDE / "guide.css").relative_to(ROOT).as_posix()].decode("utf-8")
    js = inputs[(GUIDE / "guide.js").relative_to(ROOT).as_posix()].decode("utf-8")
    if re.search(r"</style", css, re.I) or re.search(r"</script", js, re.I):
        raise ValueError("Inline CSS/JS unexpectedly contains a closing element")
    if re.search(r"\b(fetch|XMLHttpRequest|WebSocket|EventSource|sendBeacon)\b", js):
        raise ValueError("Guide JavaScript must not contain network APIs")
    if re.search(r"@import|url\(\s*[\"']?https?://", css, re.I):
        raise ValueError("Guide CSS must not fetch external resources")
    source, css_count = re.subn(r'<link\s+rel="stylesheet"\s+href="guide\.css"\s*>', lambda _: f"<style>\n{css}\n</style>", source)
    source, js_count = re.subn(r'<script\s+src="guide\.js"\s+defer\s*>\s*</script>', lambda _: f"<script defer>\n{js}\n</script>", source)
    if (css_count, js_count) != (1, 1):
        raise ValueError("Expected exactly one local stylesheet and one deferred script")
    # Inline script with `defer` executes immediately; keep the original deferred
    # initialization behavior when the standalone file is opened without a server.
    source = source.replace("<script defer>\n", '<script>\ndocument.addEventListener("DOMContentLoaded", () => {\n', 1)
    source = source.replace(f"{js}\n</script>", f"{js}\n}}, {{once: true}});\n</script>", 1)
    for name in EXPECTED_IMAGES:
        relative = f"assets/{name}"
        data = inputs[(GUIDE / relative).relative_to(ROOT).as_posix()]
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError(f"Expected a PNG image: {name}")
        pattern = rf'\bsrc="{re.escape(relative)}"'
        encoded = base64.b64encode(data).decode("ascii")
        source, count = re.subn(pattern, lambda _: f'src="data:image/png;base64,{encoded}"', source)
        if count != 1:
            raise ValueError(f"Expected exactly one guide image: {name}")

    def canonical_link(match: re.Match[str]) -> str:
        value = html.unescape(match.group(1))
        split = urlsplit(value)
        if split.scheme or split.netloc or value.startswith("#"):
            return match.group(0)
        destination = (GUIDE / split.path).resolve()
        try:
            path = destination.relative_to(ROOT).as_posix()
        except ValueError as error:
            raise ValueError(f"Relative guide link escapes repository: {value}") from error
        url = f"https://github.com/ohjun1998/burp-workbench-kr/blob/{BRANCH}/{quote(path, safe='/')}"
        if destination.suffix.lower() in (".jar", ".zip"):
            url += "?raw=true"
        elif split.query:
            url += "?" + split.query
        if split.fragment:
            url += "#" + split.fragment
        return 'href="' + html.escape(url, quote=True) + '"'

    source = re.sub(r'\bhref="([^\"]+)"', canonical_link, source)
    parser = LocalResources()
    parser.feed(source)
    remaining = [(tag, url) for tag, url in parser.resources if not url.startswith("data:image/png;base64,")]
    if remaining:
        raise ValueError(f"Standalone guide still has runtime resource references: {remaining}")
    return source.encode("utf-8")


def readme() -> bytes:
    return f"""Workbench Next {VERSION} · 설치와 사용 가이드

1. downloads/guides/{VERSION}/{STANDALONE}을 브라우저로 엽니다.
   CSS·JS·화면 이미지가 포함된 단일 HTML이며 오프라인에서 읽을 수 있습니다.
   GitHub·공식 문서·설치 파일의 웹 링크를 선택하면 인터넷 연결이 필요합니다.

2. 기존 Workbench 확장을 언로드한 뒤 Burp의 Extensions → Installed → Add → Java에서
   downloads/extensions/{VERSION}/burp-workbench-kr-{VERSION}.jar를 선택합니다.
   기존 프로젝트·설정·JAR는 보관하고 화면에 표시되는 버전을 확인합니다.

3. 실제 PC에서 연결을 확인하려면 터미널을 이 폴더(Workbench-Next-{VERSION})로 이동해 실행합니다.
   python3 tools/check_next_0542_runtime.py --live --provider both --timeout 180
   기본 점검은 --live 없이 실행할 수 있습니다. Java/JDK17 이상이 필요합니다.
   가이드에 적힌 ~/workbench-next-0542 경로가 없으면 위 상대 경로 명령을 사용하세요.
   합성 자료만 입력하며 대상 사이트 HTTP 요청은 보내지 않습니다.
   --live는 실제 로컬 모델 또는 구독을 호출하므로 해당 사용량이 적용됩니다.

4. 설치·검증 상태는 docs/RELEASE_{VERSION}_NEXT_KR.md와 docs/VERIFICATION_{VERSION}.json을 확인합니다.
   예시 화면이나 로그인 확인 성공이 실제 모델 추론·native Burp 검증 성공을 뜻하지 않습니다.

배포 JAR·소스 ZIP의 체크섬은 downloads/extensions/{VERSION}/SHA256SUMS에 있습니다.
이 묶음에는 공개 배포·가이드·점검 도구·검증된 API 의존성만 포함합니다.
인증값·개인 환경설정·원문 모델 응답·사용자 패킷은 포함하지 않습니다.
""".encode("utf-8")


def archive(entries: dict[str, bytes]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as bundle:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(PREFIX + name, (2026, 1, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            bundle.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    result = output.getvalue()
    checked_zip(result, BUNDLE)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=ROOT / "downloads/guides" / VERSION)
    parser.add_argument("--copy-to", type=Path, default=Path.home() / "문서" / STANDALONE,
                        help="Optional standalone copy destination (default: ~/문서/...html)")
    parser.add_argument("--no-copy", action="store_true", help="Do not create the optional user-folder copy")
    args = parser.parse_args()
    guide_files = [GUIDE / name for name in ("index.html", "guide.css", "guide.js")]
    guide_files += [GUIDE / "assets" / name for name in EXPECTED_IMAGES]
    names = [*PUBLIC_FILES, *(path.relative_to(ROOT).as_posix() for path in guide_files)]
    inputs: dict[str, bytes] = {}
    for name in names:
        path = ROOT / name
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"Missing or linked public packaging input: {name}")
        inputs[name] = path.read_bytes()
    verify_release(inputs)
    document = standalone(inputs)
    entries = {name: inputs[name] for name in PUBLIC_FILES}
    entries[f"downloads/guides/{VERSION}/{STANDALONE}"] = document
    entries["먼저읽기.txt"] = readme()
    zipped = archive(entries)
    # A simultaneous source edit must not silently produce a mixed package.
    for name, data in inputs.items():
        if (ROOT / name).read_bytes() != data:
            raise ValueError(f"Packaging input changed during the run: {name}; retry after editing finishes")
    out = args.out.expanduser().resolve()
    destinations = [out / STANDALONE, out / BUNDLE, out / "SHA256SUMS"]
    if not args.no_copy:
        destinations.append(args.copy_to.expanduser().resolve())
    source_paths = {(ROOT / name).resolve() for name in names}
    if any(path in source_paths for path in destinations):
        raise ValueError("Output destination would overwrite a source input")
    out.mkdir(parents=True, exist_ok=True)
    outputs = {STANDALONE: document, BUNDLE: zipped}
    for name, data in outputs.items():
        (out / name).write_bytes(data)
    sums = "".join(f"{sha(data)}  {name}\n" for name, data in outputs.items())
    (out / "SHA256SUMS").write_text(sums, encoding="utf-8")
    copied = None
    if not args.no_copy:
        copied = args.copy_to.expanduser().resolve()
        copied.parent.mkdir(parents=True, exist_ok=True)
        copied.write_bytes(document)
    for name, data in outputs.items():
        if sha((out / name).read_bytes()) != sha(data):
            raise ValueError(f"Guide output checksum mismatch: {name}")
    if copied is not None and sha(copied.read_bytes()) != sha(document):
        raise ValueError("Standalone user-folder copy checksum mismatch")
    print(json.dumps({"version": VERSION, "output": str(out), "copy": str(copied) if copied else None,
                      "files": {name: {"bytes": len(data), "sha256": sha(data)} for name, data in outputs.items()},
                      "zipCrcValid": True, "publicBundleEntries": len(entries),
                      "releaseManifestChecksumsVerified": True, "networkRequests": 0}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print(f"가이드 패키징 중단: {error}", file=sys.stderr)
        raise SystemExit(1)
