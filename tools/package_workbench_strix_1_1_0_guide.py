#!/usr/bin/env python3
"""Create an offline chat guide from the final 1.1.0 JAR and verified captures.

Capture JSON contract: releaseJarSha256, nativeBurpValidation=false,
syntheticConversation=true, modelCalls=0, targetRequests=0, and captures[]
with id, title, path (relative to manifest), sha256. Never renders a fake chat
or edits a capture. All publication output is outside simple/.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
from html import escape
from html.parser import HTMLParser
import io
import json
from pathlib import Path
import re
from urllib.parse import quote
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.1.0"
REPORT = ROOT / "docs/reports/2026-11-17-workbench-strix-chat"
OUTPUT = ROOT / "downloads/guides/strix-1.1.0"
JAR_NAME = f"burp-workbench-strix-{VERSION}.jar"
HTML_NAME = f"Workbench-Strix-{VERSION}-사용가이드.html"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def archive(entries: dict[str, bytes]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name, data in sorted(entries.items()):
            if Path(name).is_absolute() or ".." in Path(name).parts:
                raise ValueError("Unsafe ZIP path")
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            z.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    result = output.getvalue()
    with zipfile.ZipFile(io.BytesIO(result)) as z:
        if z.testzip() is not None:
            raise ValueError("ZIP CRC failure")
    return result


class Resources(HTMLParser):
    def __init__(self):
        super().__init__(); self.remote = []; self.images = 0

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if tag in ("img", "script", "iframe", "link", "source", "audio", "video"):
            value = values.get("src") or values.get("href")
            if value and not value.startswith("data:image/png;base64,"):
                self.remote.append((tag, value))
            if tag == "img":
                self.images += 1


def document(images: list[tuple[str, str, bytes]], jar_hash: str, branch: str) -> bytes:
    figures = "".join(
        '<figure id="' + escape(name, quote=True) + '"><figcaption>' + escape(title)
        + '</figcaption><input class="zoom" type="checkbox" id="' + escape(name, quote=True)
        + '-zoom"><label class="zoom-label" for="' + escape(name, quote=True)
        + '-zoom">원래 크기로 보기 · 해제하면 화면에 맞춤</label>'
        + '<div class="image-scroll"><img alt="' + escape(title, quote=True)
        + ' · production Swing 화면, 합성 세션 예시" src="data:image/png;base64,'
        + base64.b64encode(data).decode("ascii") + '"></div></figure>'
        for name, title, data in images
    )
    repo = "https://github.com/ohjun1998/burp-workbench-kr"
    jar_url = repo + "/raw/refs/heads/" + quote(branch, safe="/") + "/simple/downloads/strix/1.1.0/" + JAR_NAME
    source_url = jar_url.replace(".jar", "-source.zip")
    template = '''<!doctype html>
<html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Workbench Strix 1.1.0 · 진단 중 대화 사용 가이드</title>
<style>
:root{color-scheme:light dark;--bg:#10151d;--card:#1c2430;--text:#e8edf5;--muted:#afbdcf;--line:#374457;--accent:#87baff}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.75 system-ui,'Noto Sans KR',sans-serif}main{max-width:1120px;margin:auto;padding:32px 24px 70px}h1{font-size:clamp(24px,4vw,38px);line-height:1.3;margin:0 0 16px}h2{font-size:23px;margin:30px 0 12px}h3{font-size:18px}p{margin:10px 0}a{color:var(--accent)}.intro{font-size:18px}.meta{color:var(--muted);font-size:14px}.card,figure{border:1px solid var(--line);border-radius:12px;background:var(--card);padding:20px;margin:20px 0}.actions{display:flex;flex-wrap:wrap;gap:12px}.actions a{border:1px solid var(--line);padding:8px 14px;border-radius:8px;text-decoration:none}ol,ul{padding-left:24px}li{margin:8px 0}.scroll{overflow:auto}table{width:100%;border-collapse:collapse;min-width:580px}th,td{padding:12px;text-align:left;vertical-align:top;border-bottom:1px solid var(--line)}th{color:var(--accent)}code{font:14px ui-monospace,monospace;overflow-wrap:anywhere}blockquote{margin:16px 0;border-left:3px solid var(--accent);padding:10px 16px;background:#243449}figure{padding:12px}figcaption{padding:4px 4px 12px;font-weight:600}img{width:100%;height:auto;display:block;border-radius:6px}.image-scroll{overflow:auto;margin-top:10px}.zoom:checked~.image-scroll{max-height:80vh}.zoom:checked~.image-scroll img{width:auto;max-width:none}.zoom-label{color:var(--accent);font-size:14px;cursor:pointer}.zoom{margin:0 8px 0 4px;accent-color:var(--accent)}.hash{word-break:break-all;font-size:13px;color:var(--muted)}@media(max-width:560px){main{padding:22px 14px}.card{padding:16px}h2{font-size:21px}figure{padding:6px}}
</style><main>
<p class="meta">WORKBENCH STRIX ·1.1.0 · 오프라인 사용 가이드</p>
<h1>진단 에이전트에게<br>지금 보고 있는 결과를 물어보세요</h1>
<p class="intro">현재 Strix 진단 세션의 에이전트와 질문을 이어갑니다. 기존 Simple1.4.21 메인 디자인과13개 도구는 유지하고, 별도 Strix 창에 대화를 추가했습니다.</p>
<div class="actions"><a href="__JAR_URL__">설치 JAR</a><a href="__SOURCE_URL__">소스 ZIP</a><a href="#flow">대화 사용 순서</a><a href="#screens">실제 패널 화면</a></div>
<section class="card"><h2 style="margin-top:0">무엇이 달라졌나요?</h2>
<div class="scroll"><table><tr><th>작업</th><th>이전1.0.1</th><th>이번1.1.0</th></tr>
<tr><td>진단 결과 질문</td><td>후속 질문 입력 없음</td><td>같은 세션에서 질문·답변 확인</td></tr>
<tr><td>맥락과 기록</td><td>로그·보고서 확인</td><td>기존 에이전트 세션·대화 기록 사용</td></tr>
<tr><td>실행 선택</td><td>비대화형 진단</td><td>대화 연결 또는 기존 비대화형 진단</td></tr>
<tr><td>기존 도구</td><td>Simple1.4.21 메인</td><td>같은 디자인·배치·기능 유지</td></tr></table></div>
<p>질문마다 새 진단이나 독립 LLM 작업을 만들지 않습니다. 선택한 제공자와 Strix 자체 인증으로 현재 에이전트에게 전달합니다.</p></section>
<h2 id="flow">처음 사용하는 순서</h2><ol>
<li><strong>설치:</strong> 이전 Simple/Strix 확장을 언로드하고1.1.0 JAR를 로드합니다. 기존 설정·진단 기록은 같은 저장 공간을 사용합니다.</li>
<li><strong>원본 준비:</strong> 현재 프로젝트에서 원본을 선택하고 우클릭 <strong>선택 원본으로 Strix 진단 준비</strong>를 누릅니다.</li>
<li><strong>조건 확인:</strong> 대상·SCOPE·제공자·모델을 확인하고 대화 연결을 선택합니다. 네 번째 <strong>원본·테스트 정책</strong> 탭에서 기대 정책을 작성하고 진단 입력 미리보기를 확인합니다.</li>
<li><strong>실행:</strong> 실행계획에서 대상과 영향을 확인하고 승인합니다. 대화 연결 상태가 준비될 때까지 기다립니다.</li>
<li><strong>질문:</strong> 내용을 입력하고 전송 확인창에서 대상·질문을 읽은 뒤 승인합니다. 취소하면 보내지 않습니다.</li>
<li><strong>답변 확인:</strong> 전송 수락과 답변 도착을 구분해 읽습니다. 같은 세션에서 후속 질문을 이어가거나 중지합니다.</li></ol>
<blockquote>“방금 계정 간 주문 조회 차이는 어떤 원본에서 확인했나요? 관찰과 추정을 구분하고, 추가 확인이 필요한 점을 설명해 주세요.”</blockquote>
<p>원본 패킷 본문·쿠키·토큰은 자동 전달하지 않습니다. 질문에도 인증값·비밀번호를 붙여넣지 마세요.</p>
<section class="card"><h2 style="margin-top:0">전송 전 확인이 필요한 이유</h2>
<p>Strix 에이전트는 질문에 답하는 과정에서 도구와 대상 요청을 수행할 수 있습니다. 전송은 읽기 전용 분석을 보장하는 기능이 아니므로 <strong>매번 기본 취소인 확인</strong>을 거칩니다. 현재 원본·프로젝트·SCOPE·진단 세션이 바뀌면 이전 실행으로 질문하지 않습니다.</p>
<p>최대 에이전트 턴은 HTTP 요청 예산이 아닙니다. SCOPE 확인과 지시문은 Strix 전체 네트워크를 격리하거나 가용성 무영향을 보장하지 않습니다.</p></section>
<p>별도 Strix 창의 중앙은 <strong>진단 대화 / 진행 로그 / 이번 실행 보고서 / 원본·테스트 정책</strong> 중 선택한 탭이 넓게 사용합니다. 기존 정책 입력·미리보기는 네 번째 탭에 있으며 메인 메뉴·도구 배치는 유지합니다.</p>
<h2 id="screens">실제 패널 화면</h2>
<p class="meta">아래는 배포 JAR의 production Swing 화면을 합성 API/세션으로 실행해 캡처한 이미지입니다. 실제 사용자 Burp·모델 추론·대상 진단 성공을 나타내지 않습니다. PNG 픽셀은 수정하지 않았습니다.</p>
__FIGURES__
<h2>연결 상태 읽기</h2><ul>
<li><strong>연결 대기:</strong> 현재 실행의 live viewer가 준비되는 중입니다. 답변을 받은 상태가 아닙니다.</li>
<li><strong>연결됨:</strong> 이번 실행의 인증된 로컬 viewer에 연결했습니다. 전송 수락과 모델 답변은 별도입니다.</li>
<li><strong>종료·실패:</strong> 현재 세션으로 새 질문을 보낼 수 없습니다. 자동 새 진단·재전송·다른 제공자 전환은 하지 않습니다.</li></ul>
<p>화면은 모든 에이전트를 합한 최근 대화 최대200개·128Ki자 안에서 선택한 에이전트의 내용을 보여줍니다. 표시 범위 밖의 원본 이력은 Strix 실행 폴더에 유지됩니다.</p>
<p>대화는 Strix에 확정해 저장된 기록을 주기적으로 갱신합니다. 토큰 단위 스트리밍이나 즉시 답변을 보장하지 않습니다. 초기 진단 작업이 완료돼도 소유한 Strix 프로세스가 살아 있고 실행 상한 안이면 후속 질문을 이어갈 수 있습니다.</p>
<p>질문으로 에이전트가 재개되면 초기 턴 예산이 늘어날 수 있습니다. 최대 에이전트 턴은 전체 대화의 엄격한 턴 상한이 아닙니다. 처음 시작한 실행 시간 상한은 질문으로 초기화되지 않습니다. 중지·프로세스 종료·시간 초과 때 연결을 끝냅니다.</p>
<h2>필요한 환경과 오류 해결</h2><p>Linux/macOS의 Python3, 대화 가능한 Strix CLI와 기존 <code>~/.strix/bin/strix</code> 래퍼가 필요합니다. Windows의 POSIX PTY 대화 연결은 지원하지 않습니다. 기존 비대화형 진단 흐름은 유지하지만 Windows 실행은 이번 검증에 포함하지 않았습니다. 지원 환경에서 대화 연결을 사용할 수 없을 때는 기존 비대화형 진단의 실행·로그·보고서를 사용할 수 있습니다.</p>
<p>Halogen/Qwen 또는 Strix 자체 ChatGPT 구독 인증을 사용합니다. Codex CLI 로그인과는 별개입니다. 전역 모델 설정·OAuth 파일을 변경하거나 인증 토큰을 복사하지 않습니다. viewer 주소·토큰은 로그나 가이드에 표시하지 않습니다.</p>
<p><strong>중지</strong>는 이번 실행이 소유한 프로세스에 적용됩니다. 이미 대상 서버에서 시작한 작업을 되돌리거나 별도 Docker 작업의 완전한 종료를 보장하지 않습니다.</p>
<h2>배포 파일 확인</h2><p>JAR SHA-256:</p><p class="hash">__JAR_HASH__</p>
<p class="meta">이 HTML은 스타일과 이미지를 포함하므로 저장한 뒤 인터넷 없이 열 수 있습니다. 설치·소스 링크를 누를 때만 GitHub로 이동합니다. 실제 수행한 검사 수·환경과 남은 제한은 저장소의 이번 버전 검증 기록을 확인하세요.</p>
</main></html>'''
    result = template.replace("__JAR_URL__", escape(jar_url, quote=True)).replace("__SOURCE_URL__", escape(source_url, quote=True)).replace("__FIGURES__", figures).replace("__JAR_HASH__", jar_hash)
    parser = Resources(); parser.feed(result)
    if parser.remote or parser.images != len(images):
        raise ValueError("Guide must have only embedded capture resources")
    return result.encode("utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture-manifest", type=Path, required=True)
    parser.add_argument("--branch", default="account-a/strix-chat")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_./-]+", args.branch) or ".." in args.branch.split("/"):
        raise ValueError("Invalid branch")
    jar = ROOT / "simple/downloads/strix/1.1.0" / JAR_NAME
    jar_data = jar.read_bytes(); digest = sha(jar_data)
    release = json.loads((jar.parent / "manifest.json").read_text())
    if release.get("version") != VERSION or release.get("artifacts", {}).get(JAR_NAME) != digest:
        raise ValueError("Final release manifest/JAR mismatch")
    with zipfile.ZipFile(io.BytesIO(jar_data)) as z:
        if z.testzip() is not None:
            raise ValueError("JAR CRC failure")
        manifest = z.read("META-INF/MANIFEST.MF").decode()
        if not re.search(r"(?m)^Implementation-Version: 1\.1\.0\r?$", manifest) or "Implementation-Title: Burp Workbench Strix KR" not in manifest:
            raise ValueError("Wrong JAR identity")
    manifest_path = args.capture_manifest.resolve()
    capture = json.loads(manifest_path.read_text())
    if capture.get("releaseJarSha256") != digest:
        raise ValueError("Captures do not belong to final JAR")
    if capture.get("nativeBurpValidation") is not False or capture.get("syntheticConversation") is not True:
        raise ValueError("Capture provenance must explicitly identify synthetic sessions")
    if any(capture.get(name) != 0 for name in ("modelCalls", "targetRequests")):
        raise ValueError("Guide captures must not issue real model/target requests")
    images = []; entries = {}; seen = set()
    for item in capture.get("captures", []):
        name, title = item["id"], item["title"]
        if not re.fullmatch(r"[a-z0-9-]{1,70}", name) or name in seen or not isinstance(title, str) or not title.strip():
            raise ValueError("Invalid/duplicate capture identity")
        seen.add(name)
        path = (manifest_path.parent / item["path"]).resolve()
        if not path.is_relative_to(manifest_path.parent):
            raise ValueError("Capture path escapes its manifest directory")
        data = path.read_bytes()
        if sha(data) != item["sha256"] or not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError("Capture checksum/PNG mismatch")
        images.append((name, title, data)); entries[f"images/{name}.png"] = data
    if len(images) < 2:
        raise ValueError("At least two current-version captures are required")
    html_data = document(images, digest, args.branch)
    usage = (REPORT / "USAGE_KR.md").read_bytes()
    image_entries = dict(entries)
    bundled_capture = dict(capture)
    bundled_capture["captures"] = [dict(item, path="images/" + item["id"] + ".png") for item in capture["captures"]]
    image_entries["CAPTURE_PROVENANCE.json"] = (json.dumps(bundled_capture, ensure_ascii=False, indent=2) + "\n").encode()
    image_entries["README_KR.md"] = ("# Workbench Strix 1.1.0 · 대화 화면\n\n최종 JAR의 production Swing 패널과 합성 세션 예시입니다. 실제 사용자 Burp·모델·대상 진단 성공이 아닙니다. 원본 PNG는 수정하지 않았습니다.\n").encode()
    image_zip = archive(image_entries)
    bundle_entries = {HTML_NAME: html_data, JAR_NAME: jar_data, "USAGE_KR.md": usage}
    bundle_entries["SHA256SUMS"] = "".join(f"{sha(data)}  {name}\n" for name, data in sorted(bundle_entries.items())).encode()
    bundle = archive(bundle_entries)
    outputs = {HTML_NAME: html_data, f"Workbench-Strix-{VERSION}-설치와가이드.zip": bundle, f"Workbench-Strix-{VERSION}-화면이미지.zip": image_zip}
    checksums = "".join(f"{sha(data)}  {name}\n" for name, data in sorted(outputs.items())).encode()
    for name, data in dict(outputs, SHA256SUMS=checksums).items():
        destination = OUTPUT / name
        if destination.exists() and destination.read_bytes() != data:
            raise ValueError(f"Existing published guide differs: {destination}")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, data in dict(outputs, SHA256SUMS=checksums).items():
        (OUTPUT / name).write_bytes(data)
    validation = {"version": VERSION, "releaseJarSha256": digest, "embeddedPngCount": len(images), "externalResourceCount": 0, "javascriptCount": 0, "zipCrc": "passed", "nativeBurpValidation": False, "syntheticConversation": True, "artifacts": {name: {"sha256": sha(data), "bytes": len(data)} for name, data in outputs.items()}}
    REPORT.mkdir(parents=True, exist_ok=True)
    (REPORT / "guide-verification.json").write_text(json.dumps(validation, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(validation, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
