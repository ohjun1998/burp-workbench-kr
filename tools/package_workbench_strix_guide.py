#!/usr/bin/env python3
"""Publish an offline Workbench Strix guide and small public bundles, without network.

Requires a checksum-verified final JAR and its successful 28-view render report.
Only root downloads/docs-copy outputs are regenerated; simple/ is read-only.
"""
from __future__ import annotations

import argparse
import base64
from html.parser import HTMLParser
import hashlib
import html
import io
import json
from pathlib import Path
import re
import sys
from urllib.parse import quote, urlsplit
import zipfile


ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.0.0"
REPORT_ROOT = ROOT / "docs/reports/2026-11-17-workbench-strix"
GUIDE = REPORT_ROOT / "guide"
JAR_NAME = f"burp-workbench-strix-{VERSION}.jar"
HTML_NAME = f"Workbench-Strix-{VERSION}-사용가이드.html"
BUNDLE_NAME = f"Workbench-Strix-{VERSION}-설치와가이드.zip"
IMAGES_NAME = f"Workbench-Strix-{VERSION}-화면이미지.zip"
IMAGES = ("01-prepared.png", "02-halogen.png", "03-chatgpt-auth.png", "04-execution-plan.png",
          "05-running-log.png", "06-stopped.png", "07-reports.png")
USAGE = REPORT_ROOT / "USAGE_KR.md"
REPO = "https://github.com/ohjun1998/burp-workbench-kr"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def checksum_file(entries: dict[str, bytes]) -> bytes:
    return "".join(f"{sha(data)}  {name}\n" for name, data in sorted(entries.items())).encode("utf-8")


def checked_zip(data: bytes, label: str) -> None:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        bad = archive.testzip()
        if bad:
            raise ValueError(f"ZIP CRC mismatch: {label}: {bad}")


def zipped(prefix: str, entries: dict[str, bytes]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            if Path(name).is_absolute() or ".." in Path(name).parts:
                raise ValueError("Unsafe bundle entry")
            info = zipfile.ZipInfo(prefix + "/" + name, (2026, 1, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    data = output.getvalue(); checked_zip(data, prefix)
    return data


class GuideParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(); self.resources = []; self.ids = []; self.anchors = []; self.images = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if values.get("id"):
            self.ids.append(values["id"])
        if tag == "a" and values.get("href", "").startswith("#"):
            self.anchors.append(values["href"][1:])
        if tag in ("img", "script", "link", "iframe", "video", "audio", "source"):
            value = values.get("src") or values.get("href")
            if value:
                self.resources.append((tag, value))
                if tag == "img" and value.startswith("data:image/png;base64,"):
                    self.images += 1


def verify_jar(jar: Path, data: bytes, expected: str | None, manifest: bytes | None) -> str:
    digest = sha(data)
    if expected and not re.fullmatch(r"[0-9a-fA-F]{64}", expected):
        raise ValueError("--expected-sha must contain 64 hexadecimal characters")
    if expected and digest != expected.lower():
        raise ValueError("Final JAR does not match --expected-sha")
    published = None
    if manifest is not None:
        identity = json.loads(manifest)
        if identity.get("version") != VERSION:
            raise ValueError("Release manifest version differs")
        published = identity.get("artifacts", {}).get(JAR_NAME)
        if not published or digest != published:
            raise ValueError("Final JAR does not match release manifest")
    if not expected and not published:
        raise ValueError("Final JAR approval missing: supply --expected-sha or its release manifest")
    checked_zip(data, jar.name)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        text = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
        if not re.search(rf"(?m)^Implementation-Version: {re.escape(VERSION)}\r?$", text):
            raise ValueError("JAR implementation version differs")
        if "Implementation-Title: Burp Workbench Strix KR" not in text:
            raise ValueError("JAR is not the Workbench Strix release")
    return digest


def verify_images(report_data: bytes, jar_hash: str, images: dict[str, bytes]) -> str:
    report = json.loads(report_data)
    if report.get("releaseJarSha256") != jar_hash:
        raise ValueError("Guide render report is not from the final JAR")
    if report.get("pngCount") != 28 or report.get("violationCount") != 0:
        raise ValueError("Final render must pass all 28 views with zero layout violations")
    if report.get("missing") or report.get("processFailures"):
        raise ValueError("Render report contains missing views or process failures")
    if any(report.get(name) != 0 for name in ("strixCliExecutions", "modelCalls", "targetRequests")):
        raise ValueError("Guide captures must use no actual CLI, model or target calls")
    if report.get("nativeBurpValidation") is not False:
        raise ValueError("Synthetic captures must not claim native Burp validation")
    for run in report.get("runs", []):
        hashes = {Path(view["png"]).name: view.get("sha256") for view in run.get("views", [])}
        if all(hashes.get(name) == sha(images[name]) for name in IMAGES):
            return f"{run.get('theme')} · {run.get('width')}×{run.get('height')}"
    raise ValueError("Guide PNG files do not all match one exact final render run")


def document(inputs: dict[Path, bytes], images: dict[str, bytes], branch: str) -> bytes:
    source = inputs[GUIDE / "index.html"].decode("utf-8")
    css = inputs[GUIDE / "guide.css"].decode("utf-8")
    js = inputs[GUIDE / "guide.js"].decode("utf-8")
    if re.search(r"</style", css, re.I) or re.search(r"</script", js, re.I):
        raise ValueError("Unexpected closing element inside inline assets")
    if re.search(r"\b(fetch|XMLHttpRequest|WebSocket|EventSource|sendBeacon)\b", js):
        raise ValueError("Guide script must not contain external communication APIs")
    if re.search(r"@import|url\(\s*[\"']?https?://", css, re.I):
        raise ValueError("Guide CSS must not load external resources")
    source, css_count = re.subn(r'<link\s+rel="stylesheet"\s+href="guide\.css"\s*>',
                              lambda _: f"<style>\n{css}\n</style>", source)
    source, js_count = re.subn(r'<script\s+src="guide\.js"\s+defer\s*>\s*</script>',
                             lambda _: '<script>\ndocument.addEventListener("DOMContentLoaded", () => {\n'
                             + js + '\n}, {once: true});\n</script>', source)
    if (css_count, js_count) != (1, 1):
        raise ValueError("Expected one local stylesheet and one deferred script")
    for name in IMAGES:
        data = images[name]
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError(f"Expected PNG: {name}")
        source, count = re.subn(rf'\bsrc="assets/{re.escape(name)}"',
                               lambda _, data=data: 'src="data:image/png;base64,'
                               + base64.b64encode(data).decode("ascii") + '"', source)
        if count != 1:
            raise ValueError(f"Expected exactly one reference to {name}")

    def public_link(match: re.Match[str]) -> str:
        value = html.unescape(match.group(1)); parsed = urlsplit(value)
        if parsed.scheme or parsed.netloc or value.startswith("#"):
            return match.group(0)
        destination = (GUIDE / parsed.path).resolve()
        try:
            relative = destination.relative_to(ROOT).as_posix()
        except ValueError as error:
            raise ValueError(f"Guide link escapes repository: {value}") from error
        if not destination.is_file():
            raise ValueError(f"Guide links to a missing public file: {relative}")
        url = f"{REPO}/blob/{branch}/{quote(relative, safe='/')}"
        if destination.suffix.lower() in (".jar", ".zip"):
            url += "?raw=true"
        elif parsed.query:
            url += "?" + parsed.query
        if parsed.fragment:
            url += "#" + parsed.fragment
        return 'href="' + html.escape(url, quote=True) + '"'

    source = re.sub(r'\bhref="([^\"]+)"', public_link, source)
    parsed = GuideParser(); parsed.feed(source)
    if len(parsed.ids) != len(set(parsed.ids)) or not set(parsed.anchors).issubset(parsed.ids):
        raise ValueError("Guide contains duplicate IDs or broken section links")
    if parsed.images != 7 or any(not value.startswith("data:image/png;base64,") for _, value in parsed.resources):
        raise ValueError("Standalone guide must have seven inline PNGs and no external runtime resources")
    return source.encode("utf-8")


def readme(jar_hash: str, branch: str) -> bytes:
    source_link = f"{REPO}/blob/{branch}/simple/downloads/strix/{VERSION}/burp-workbench-strix-{VERSION}-source.zip?raw=true"
    return f"""Workbench Strix {VERSION} · Strix 진단 설치와 가이드

1. {HTML_NAME}을 브라우저로 여세요.
   이미지·CSS·JS가 포함된 단일 HTML로 오프라인에서 읽을 수 있습니다.

2. 이전 Simple 또는 Strix 연동 확장을 언로드하고 Burp Extensions → Installed → Add → Java에서
   {JAR_NAME}를 선택합니다. 기존 JAR·프로젝트·설정을 보관하고 Workbench Strix 1.0.0 이름과 버전을 확인하세요.
   기존 simple.* 설정과 .burp-workbench-simple 저장 위치는 호환성을 위해 유지됩니다.
   일반 Simple과 이 제품을 동시에 로드하지 않습니다.

3. 기존 로컬 Strix CLI가 설치되어 있어야 합니다. 이 묶음은 Strix 설치·래퍼·모니터를 변경하지 않습니다.
   원본/SCOPE 준비 → 제공자·모델 확인 → 실행계획 승인 → 진행/중지 → 보고서 순서로 사용합니다.
   ChatGPT 구독은 Strix 자체 인증이며 Codex CLI 로그인과 별도입니다.

4. 화면은 정확한 배포 JAR의 실제 패널을 합성 Runner로 그린 예시입니다.
   실제 사용자 Burp 캡처나 모델/OAuth/대상 진단 성공 증거가 아닙니다.
   최대 에이전트 턴은 HTTP 요청 예산이 아니며 지시문은 네트워크 차단 장치가 아닙니다.

배포 JAR SHA-256:
{jar_hash}

폴더의 SHA256SUMS는 이 묶음 내부 파일의 해시입니다. 원본 소스 ZIP은 용량 때문에 포함하지 않습니다.
소스 다운로드(인터넷 필요):
{source_link}

상세 사용법은 사용안내.md, 실제 검사 상태와 제한은 HTML의 검증 기록 링크에서 확인하세요.
가이드의 웹 링크는 사용자가 선택했을 때 인터넷을 사용합니다.
""".encode("utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "simple/downloads/strix" / VERSION / JAR_NAME)
    parser.add_argument("--expected-sha", help="Final CI-approved JAR SHA-256; required without a release manifest")
    parser.add_argument("--manifest", type=Path, help="Defaults to manifest.json next to the selected JAR, if present")
    parser.add_argument("--render-report", type=Path, required=True, help="Successful final render_strix.py report.json")
    parser.add_argument("--branch", default="account-a/workbench-strix-identity", help="Public GitHub branch containing the final guide/release")
    parser.add_argument("--out", type=Path, default=ROOT / "downloads/guides/strix-1.0.0")
    parser.add_argument("--copy-to", type=Path, default=Path.home() / "문서" / HTML_NAME)
    parser.add_argument("--no-copy", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_./-]+", args.branch) or ".." in args.branch or args.branch.startswith("/"):
        parser.error("Invalid public branch")
    jar = args.jar.expanduser().resolve(); report = args.render_report.expanduser().resolve()
    manifest = args.manifest.expanduser().resolve() if args.manifest else jar.parent / "manifest.json"
    paths = [GUIDE / name for name in ("index.html", "guide.css", "guide.js")]
    capture_dir = report.parent / "light/1280x800"
    paths += [capture_dir / name for name in IMAGES]
    paths += [jar, report, USAGE]
    if manifest.is_file():
        paths.append(manifest)
    elif args.manifest:
        raise ValueError("Explicit release manifest does not exist")
    inputs = {}
    for path in paths:
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"Missing or linked public packaging input: {path}")
        inputs[path] = path.read_bytes()
    jar_hash = verify_jar(jar, inputs[jar], args.expected_sha, inputs.get(manifest))
    images = {name: inputs[capture_dir / name] for name in IMAGES}
    render_run = verify_images(inputs[report], jar_hash, images)
    standalone = document(inputs, images, args.branch)
    image_note = (f"Workbench Strix {VERSION} · 실제 운영 패널 / 합성 예시\n\n"
                  f"배포 JAR SHA-256: {jar_hash}\n캡처: {render_run}\n"
                  "이 7장은 가짜 Runner·관찰 자료로 만든 실제 패널과 확인 내용입니다.\n"
                  "실제 Strix CLI·모델·대상 호출은 0회이며 native 사용자 Burp 캡처가 아닙니다.\n"
                  "로그·인증·완료·보고서 상태는 합성 예시이며 실제 성공 증거가 아닙니다.\n").encode("utf-8")
    image_entries = {**images, "화면설명.txt": image_note}
    image_entries["SHA256SUMS"] = checksum_file(image_entries)
    images_zip = zipped(f"Workbench-Strix-{VERSION}-화면", image_entries)
    bundle_entries = {HTML_NAME: standalone, JAR_NAME: inputs[jar], "사용안내.md": inputs[USAGE],
                      "먼저읽기.txt": readme(jar_hash, args.branch)}
    bundle_entries["SHA256SUMS"] = checksum_file(bundle_entries)
    bundle = zipped(f"Workbench-Strix-{VERSION}", bundle_entries)
    if len(bundle) > 10 * 1024 * 1024:
        raise ValueError("Install/guide bundle exceeds the 10 MiB public delivery limit")
    for path, data in inputs.items():
        if path.read_bytes() != data:
            raise ValueError(f"Packaging input changed while running: {path}; retry after editing")
    out = args.out.expanduser().resolve()
    outputs = {HTML_NAME: standalone, BUNDLE_NAME: bundle, IMAGES_NAME: images_zip}
    copy = None if args.no_copy else args.copy_to.expanduser().resolve()
    destinations = [out / name for name in (*outputs, "SHA256SUMS")]
    if copy:
        destinations.append(copy)
    if any(destination in inputs for destination in destinations):
        raise ValueError("Packaging output would overwrite an input")
    out.mkdir(parents=True, exist_ok=True)
    (GUIDE / "assets").mkdir(parents=True, exist_ok=True)
    for name, data in images.items():
        (GUIDE / "assets" / name).write_bytes(data)
    for name, data in outputs.items():
        (out / name).write_bytes(data)
    (out / "SHA256SUMS").write_bytes(checksum_file(outputs))
    if copy:
        copy.parent.mkdir(parents=True, exist_ok=True); copy.write_bytes(standalone)
    for name, data in outputs.items():
        if sha((out / name).read_bytes()) != sha(data):
            raise ValueError(f"Output checksum mismatch: {name}")
    if copy and sha(copy.read_bytes()) != sha(standalone):
        raise ValueError("Standalone document copy differs")
    print(json.dumps({"version": VERSION, "output": str(out), "copy": str(copy) if copy else None,
        "releaseJarSha256": jar_hash, "capture": render_run, "zipCrcValid": True,
        "inlineImages": 7, "externalRuntimeResources": 0, "networkRequests": 0,
        "files": {name: {"bytes": len(data), "sha256": sha(data)} for name, data in outputs.items()}}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print(f"Strix 가이드 패키징 중단: {error}", file=sys.stderr)
        raise SystemExit(1)
