#!/usr/bin/env python3
"""Package final Simple 1.4.17 captures beside the preserved approved PDF images.

Run after the final layout renderer passes. This writes only a new 1.4.17 report
directory; it never changes production/guide inputs or the reference images.

An optional auth appendix JSON can contain {"jarSha256": "...", "captures": [
  {"theme": "light", "tab": "인증 후보 연결", "file": "light/1600x1000/full.png"}, ...
]}. Supply exactly three distinct tab captures for each theme. Relative capture
paths resolve from --source. Otherwise the renderer's six named full captures
are used automatically. They are full-window captures, separate from the
auth.accounts comparison whose approved reference covers one connection panel.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import io
import json
from pathlib import Path
import shutil
import tempfile
import zipfile

import fitz
from fontTools import subset
from fontTools.ttLib import TTFont
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.4.17"
REFERENCE = ROOT / "docs/reports/2026-10-31-workflow-design"
ORDER = ["traffic", "mapper", "js", "environment", "exposure.search", "exposure.cve",
         "auth", "response.recon", "payload", "data", "activity", "bounty", "dashboard",
         "payload.results", "bounty.replay", "auth.accounts"]
FILES = {
    "traffic": "02-request.png", "mapper": "08-mapper.png", "js": "10-js.png",
    "environment": "09-environment.png", "exposure.search": "12-exposure-search.png",
    "exposure.cve": "13-exposure-cve.png", "auth": "04-auth-results.png",
    "response.recon": "03-analysis.png", "payload": "07-payload.png", "data": "14-data.png",
    "activity": "15-progress.png", "bounty": "05-evidence.png", "dashboard": "16-dashboard.png",
    "payload.results": "07-payload-results.png", "bounty.replay": "11-replay.png",
    "auth.accounts": "04-auth-accounts.png",
}
THEMES = {"light": "라이트", "dark": "다크"}
SCOPE = ("실제 운영 Swing 컴포넌트에 합성 패킷·계정 데이터를 넣은 캡처입니다. "
         "실제 Windows Burp 본체·DPI/IME·외부 서비스 검증은 별도입니다.")
DATA_NOTE = ("시안과 실제 캡처의 합성 데이터·선택 상태·결과 건수는 서로 다릅니다. "
             "각 원본 PNG를 제공하므로 배치와 표시 내용을 함께 대조할 수 있습니다. "
             "PDF 원본 삽입 검사는 이미지 보존 검사이며 화면 배치의 픽셀 일치 판정이 아닙니다.")
ACCOUNT_NOTE = ("인증 후보 연결 시안은 연결 영역만 보여 줍니다. 이 비교와 별도로 부록에 "
                "실제 준비 창의 세 탭 전체를 테마별로 수록했습니다. 연결 영역만의 비교를 "
                "준비 창 전체의 검증 결과로 사용하지 않습니다.")
FOCUS = {
    "bounty": "가운데 보고서 작성 카드 안의 실제 영향·재현 절차, 왼쪽 증거 목록, 오른쪽 원문 대조를 확인합니다.",
    "payload.results": "결과 단계의 상단 주 동작, 결과 필터와 응답 시간 열, 선택 요청·응답과 판정 근거를 확인합니다.",
    "bounty.replay": "요청 준비와 실행 결과, 응답·요청 전환, 접힌 변수 추출·단계 기대 조건을 확인합니다.",
    "auth.accounts": ACCOUNT_NOTE,
}


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def image_size(path: Path) -> tuple[int, int]:
    with Image.open(path) as image:
        return image.size


def relative(path: Path, parent: Path) -> str:
    try:
        return path.relative_to(parent).as_posix()
    except ValueError:
        return str(path)


def fit_image(page: fitz.Page, path: Path, box: fitz.Rect) -> None:
    width, height = image_size(path)
    ratio = min(box.width / width, box.height / height)
    x = box.x0 + (box.width - width * ratio) / 2
    rect = fitz.Rect(x, box.y0, x + width * ratio, box.y0 + height * ratio)
    page.insert_image(rect, filename=str(path), keep_proportion=True)


def text(page: fitz.Page, box: fitz.Rect, value: str, size: float = 16,
         color: tuple[float, float, float] = (.22, .27, .34)) -> None:
    remaining = page.insert_textbox(box, value, fontname="KR", fontsize=size,
                                    color=color, lineheight=1.35)
    if remaining < 0:
        raise RuntimeError(f"PDF text does not fit its box: {value!r}")


def load_inputs(source: Path, appendix_file: Path | None) -> tuple[dict, dict, list[dict], list[dict]]:
    layout = json.loads((source / "layout-report.json").read_text(encoding="utf-8"))
    if layout.get("version") != VERSION or layout.get("result") != "passed":
        raise RuntimeError("Final 1.4.17 layout report must exist and pass before packaging")
    for key in ["missingCaptures", "failedScenarios", "clippedControls", "collapsedAreas"]:
        if layout.get(key):
            raise RuntimeError(f"Final layout still contains {key}")
    artifact = ROOT / f"simple/burp-workbench/dist/burp-workbench-simple-{VERSION}.jar"
    if not artifact.is_file() or digest(artifact) != layout.get("jarSha256"):
        raise RuntimeError("Current JAR and final layout report do not describe the same artifact")
    approved = json.loads((REFERENCE / "catalog.json").read_text(encoding="utf-8"))
    if set(approved) != set(ORDER):
        raise RuntimeError("Approved screen catalog differs from the declared sixteen screens")
    pairs = []
    for theme in THEMES:
        for key in ORDER:
            actual = source / theme / "1600x1000" / FILES[key]
            reference = REFERENCE / "images" / theme / f"{key}.png"
            for image in [actual, reference]:
                if not image.is_file():
                    raise RuntimeError(f"Missing original capture: {image}")
            actual_width, actual_height = image_size(actual)
            expected_actual = ((actual_width, actual_height) == (1600, 1000) if key != "auth.accounts"
                               else actual_width >= 1000 and actual_height >= 600)
            if not expected_actual or image_size(reference) != (1600, 1000):
                raise RuntimeError(f"Unexpected capture dimensions: {actual} / {reference}")
            pairs.append({"screen": key, "theme": theme, "actual": actual, "reference": reference})
    if appendix_file is not None:
        appendix = json.loads(appendix_file.read_text(encoding="utf-8"))
    else:
        tabs = {"overview": "1 적용 상태", "accounts": "2 계정·쓰기 조회", "policy": "3 세션·권한표"}
        appendix = {"jarSha256": layout["jarSha256"], "captures": [
            {"theme": theme, "tab": title, "file": f"{theme}/1600x1000/04-auth-accounts-full-{key}.png"}
            for theme in THEMES for key, title in tabs.items()
        ]}
    if appendix.get("jarSha256") != layout["jarSha256"]:
        raise RuntimeError("Full auth-tab capture manifest must identify the same final JAR")
    records = appendix.get("captures", [])
    if len(records) != 6:
        raise RuntimeError("Auth appendix requires all three tabs in each of the two themes")
    seen: set[tuple[str, str]] = set()
    paths: set[Path] = set()
    normalized = []
    for entry in records:
        theme, tab = entry.get("theme"), entry.get("tab", "")
        if theme not in THEMES or not tab or (theme, tab) in seen:
            raise RuntimeError("Auth appendix contains an unknown theme, empty tab or duplicate tab")
        seen.add((theme, tab))
        path = Path(entry["file"])
        path = (path if path.is_absolute() else source / path).resolve()
        if path in paths or not path.is_file():
            raise RuntimeError(f"Missing or reused full auth-tab capture: {path}")
        paths.add(path)
        width, height = image_size(path)
        if width < 1000 or height < 600:
            raise RuntimeError(f"Auth appendix must use full-window captures, not a connection-panel crop: {path}")
        normalized.append({"theme": theme, "tab": tab, "source": path})
    if any(sum(e["theme"] == theme for e in normalized) != 3 for theme in THEMES):
        raise RuntimeError("Auth appendix needs exactly three full tabs for each theme")
    if {e["tab"] for e in normalized if e["theme"] == "light"} != {e["tab"] for e in normalized if e["theme"] == "dark"}:
        raise RuntimeError("Auth appendix tab labels must match across themes")
    return layout, approved, pairs, normalized


def build_pdf(destination: Path, approved: dict, pairs: list[dict], appendix: list[dict]) -> dict:
    pdf = fitz.open()
    toc: list[list] = []
    expected_images: list[list[Path]] = []
    appendix_title = "부록 · 인증 준비 창 전체 세 탭"
    with tempfile.TemporaryDirectory(prefix="workbench-pdf-report-") as temporary:
        font_path = Path(temporary) / "report-font.ttf"
        labels = ("Workbench Simple 1.4.17 · PDF 시안 대조 · 최종 복구 작업", "승인 시안", "실제 1.4.17", "주 동작", SCOPE, DATA_NOTE, ACCOUNT_NOTE, appendix_title, "라이트 다크 전체 화면 연결 영역 비교와 구분")
        chars = "".join(chr(i) for i in range(32, 127)) + "".join(labels) + "".join(FOCUS.values())
        chars += "".join(str(value) for value in approved.values()) + "".join(e["tab"] for e in appendix)
        font = TTFont(ROOT / "simple/burp-workbench/src/main/resources/fonts/NanumGothic-Regular.ttf")
        font_subset = subset.Subsetter(); font_subset.populate(text=chars); font_subset.subset(font); font.save(font_path)
        count = len(pairs) + len(appendix)
        for pair in pairs:
            key, theme = pair["screen"], pair["theme"]
            obj = approved[key]
            page = pdf.new_page(width=2400, height=1130)
            page.insert_font(fontname="KR", fontfile=str(font_path))
            page.draw_rect(page.rect, fill=(.965, .972, .983), color=None)
            text(page, fitz.Rect(30, 25, 2350, 53), labels[0], 17, (0, .35, .72))
            text(page, fitz.Rect(30, 66, 2350, 110), obj["title"] + " · " + THEMES[theme], 29)
            text(page, fitz.Rect(30, 124, 1175, 156), "승인 시안", 20)
            text(page, fitz.Rect(1225, 124, 2370, 156), "실제 1.4.17", 20)
            fit_image(page, pair["reference"], fitz.Rect(30, 166, 1175, 882))
            fit_image(page, pair["actual"], fitz.Rect(1225, 166, 2370, 882))
            note = "주 동작 · " + obj["primary"] + "\n" + FOCUS.get(key, obj["goal"])
            text(page, fitz.Rect(30, 910, 1175, 1032), note, 18)
            text(page, fitz.Rect(1225, 910, 2370, 1032), DATA_NOTE, 17)
            text(page, fitz.Rect(30, 1064, 2210, 1110), SCOPE, 13)
            text(page, fitz.Rect(2240, 1064, 2370, 1110), f"{len(pdf)} / {count}", 15)
            expected_images.append([pair["reference"], pair["actual"]])
            toc.append([1, obj["title"] + " · " + THEMES[theme], len(pdf)])
        for item in appendix:
            page = pdf.new_page(width=2400, height=1130)
            page.insert_font(fontname="KR", fontfile=str(font_path))
            page.draw_rect(page.rect, fill=(.965, .972, .983), color=None)
            text(page, fitz.Rect(30, 25, 2370, 66), appendix_title + " · " + THEMES[item["theme"]], 27)
            text(page, fitz.Rect(30, 76, 2370, 113), item["tab"], 23)
            fit_image(page, item["actual"], fitz.Rect(320, 130, 2080, 940))
            text(page, fitz.Rect(30, 962, 2370, 1040), ACCOUNT_NOTE, 18)
            text(page, fitz.Rect(30, 1064, 2210, 1110), SCOPE, 13)
            text(page, fitz.Rect(2240, 1064, 2370, 1110), f"{len(pdf)} / {count}", 15)
            expected_images.append([item["actual"]])
            toc.append([1, appendix_title + " · " + item["tab"] + " · " + THEMES[item["theme"]], len(pdf)])
        pdf.set_toc(toc)
        pdf.set_metadata({"title": "Workbench Simple 1.4.17 · PDF 시안과 실제 화면 대조", "author": "Workbench KR", "subject": SCOPE + " " + DATA_NOTE})
        output = destination / "workbench-simple-1.4.17-pdf-alignment.pdf"
        pdf.save(output, garbage=4, deflate=True)
        pdf.close()
    verified = verify_pdf(output, expected_images)
    verified.update({"sha256": digest(output), "bytes": output.stat().st_size})
    return verified


def verify_pdf(output: Path, expected: list[list[Path]]) -> dict:
    checked = 0
    with fitz.open(output) as pdf:
        if len(pdf) != len(expected):
            raise RuntimeError("PDF page count is incomplete")
        for index, page in enumerate(pdf):
            entries = page.get_images(full=True)
            if len(entries) != len(expected[index]):
                raise RuntimeError(f"Wrong number of inserted images on page {index + 1}")
            for source in expected[index]:
                with Image.open(source) as original:
                    original = original.convert("RGB")
                    match = None
                    for entry in entries:
                        with Image.open(io.BytesIO(pdf.extract_image(entry[0])["image"])) as embedded:
                            embedded = embedded.convert("RGB")
                            if embedded.size == original.size and embedded.tobytes() == original.tobytes():
                                match = entry; break
                    if match is None:
                        raise RuntimeError(f"PDF changed source image pixels on page {index + 1}: {source}")
                    for rect in page.get_image_rects(match[0]):
                        if abs(rect.width / rect.height - original.width / original.height) > .0001:
                            raise RuntimeError(f"PDF stretched image on page {index + 1}: {source}")
                    checked += 1
            for block in page.get_text("dict")["blocks"]:
                for line in block.get("lines", []):
                    for span in line["spans"]:
                        if not page.rect.contains(fitz.Rect(span["bbox"])):
                            raise RuntimeError(f"PDF text outside page bounds: {span['text']!r}")
        return {"result": "passed", "pages": len(pdf), "comparisonPages": 32, "authFullTabAppendixPages": 6,
                "originalImagePixelMatches": checked, "imageAspectRatiosPreserved": True,
                "textWithinPageBounds": True, "scope": "Document-integrity checks; not pixel equality between design and production."}


def gallery(destination: Path, approved: dict, pairs: list[dict], appendix: list[dict]) -> None:
    esc = html.escape
    web = '''<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Simple 1.4.17 · PDF 시안 대조</title><style>body{margin:28px;font:16px system-ui;background:#f5f6f8;color:#202630}h1{font-size:27px}p{line-height:1.8;color:#526071}section{margin:40px 0}article{display:grid;grid-template-columns:1fr 1fr;gap:18px}img{width:100%;border:1px solid #d8dde5}figure{margin:0}figcaption{margin:9px 0;font-weight:600}a{color:#0067d6}.appendix{max-width:1500px;margin:20px auto}@media(max-width:900px){article{grid-template-columns:1fr}}</style><h1>Simple 1.4.17 · PDF 시안과 실제 화면 대조</h1>'''
    web += "<p>" + esc(SCOPE) + "<br>" + esc(DATA_NOTE) + '</p><p><a href="workbench-simple-1.4.17-pdf-alignment.pdf">32개 비교 + 인증 전체 6개 부록 PDF</a></p>'
    if (destination / "ALIGNMENT_KR.md").is_file():
        web += '<p><a href="ALIGNMENT_KR.md">화면별 구현 대조와 남은 차이 기록</a></p>'
    for pair in pairs:
        key, theme = pair["screen"], pair["theme"]
        web += "<section><h2>" + esc(approved[key]["title"] + " · " + THEMES[theme]) + "</h2><p>" + esc(FOCUS.get(key, approved[key]["goal"])) + "</p><article>"
        for label, path in [("승인 시안", pair["reference"]), ("실제 1.4.17", pair["actual"])]:
            link = relative(path, destination)
            web += '<figure><figcaption>' + label + '</figcaption><a href="' + esc(link, quote=True) + '"><img loading="lazy" src="' + esc(link, quote=True) + '" alt="' + esc(approved[key]["title"] + " " + label, quote=True) + '"></a></figure>'
        web += "</article></section>"
    web += "<section><h2>부록 · 인증 준비 창 전체 세 탭</h2><p>" + esc(ACCOUNT_NOTE) + "</p>"
    for item in appendix:
        link = relative(item["actual"], destination)
        web += '<figure class="appendix"><figcaption>' + esc(item["tab"] + " · " + THEMES[item["theme"]]) + '</figcaption><a href="' + esc(link, quote=True) + '"><img loading="lazy" src="' + esc(link, quote=True) + '" alt="' + esc(item["tab"], quote=True) + '"></a></figure>'
    (destination / "gallery.html").write_text(web + "</section></html>\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=ROOT / "tmp/pdf-alignment/final-layout")
    parser.add_argument("--out", type=Path, default=ROOT / "docs/reports/2026-10-31-pdf-alignment/1.4.17")
    parser.add_argument("--auth-appendix", type=Path, help="Optional alternative manifest for six full-window auth-tab captures; defaults to the renderer's named outputs")
    args = parser.parse_args()
    source, destination = args.source.resolve(), args.out.resolve()
    if destination.name != VERSION or destination == source or source in destination.parents:
        parser.error("Output must be a separate, new report directory named 1.4.17")
    layout, approved, pairs, appendix = load_inputs(source, args.auth_appendix.resolve() if args.auth_appendix else None)
    destination.mkdir(parents=True, exist_ok=True)
    manifest = []
    for pair in pairs:
        for kind in ["actual", "reference"]:
            original = pair[kind]
            path = destination / ("images" if kind == "actual" else "reference") / pair["theme"] / f"{pair['screen']}.png"
            path.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(original, path)
            width, height = image_size(path)
            manifest.append({"kind": kind, "screen": pair["screen"], "theme": pair["theme"], "file": relative(path, destination),
                             "source": relative(original, ROOT), "width": width, "height": height, "sha256": digest(path)})
            pair[kind] = path
    for index, item in enumerate(appendix):
        path = destination / "auth-full-tabs" / item["theme"] / f"{index % 3 + 1:02d}.png"
        # Use stable per-theme numbering even when the provided manifest interleaves themes.
        number = sum(previous["theme"] == item["theme"] for previous in appendix[:index]) + 1
        path = path.with_name(f"{number:02d}.png"); path.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(item["source"], path)
        width, height = image_size(path)
        item["actual"] = path
        manifest.append({"kind": "auth-full-tab", "theme": item["theme"], "tab": item["tab"], "file": relative(path, destination),
                         "source": relative(item["source"], ROOT), "width": width, "height": height, "sha256": digest(path)})
    report = build_pdf(destination, approved, pairs, appendix)
    report["jarSha256"] = layout["jarSha256"]
    write_json(destination / "pdf-verification.json", report)
    write_json(destination / "image-manifest.json", {"version": VERSION, "jarSha256": layout["jarSha256"],
               "actualComparisonScreens": 32, "referenceScreens": 32, "authFullTabScreens": 6, "files": manifest})
    gallery(destination, approved, pairs, appendix)
    readme = "# Simple 1.4.17 · PDF 시안과 실제 화면 대조\n\n"
    readme += "[나란히 비교 PDF](workbench-simple-1.4.17-pdf-alignment.pdf) · [오프라인 갤러리](gallery.html) · [원본 PNG 포함 ZIP](workbench-simple-1.4.17-pdf-alignment-images.zip)\n\n"
    readme += "이번 자료는 중단되었던 PDF 맞춤 작업을 1.4.16 원격 소스에서 복구한 1.4.17 결과입니다. 이전 1.4.16의 완료 기록과 이번 작업의 결과를 구분합니다.\n\n"
    if (destination / "ALIGNMENT_KR.md").is_file():
        readme += "[화면별 구현 대조와 남은 차이](ALIGNMENT_KR.md)도 함께 확인하세요.\n\n"
    readme += SCOPE + "\n\n" + DATA_NOTE + "\n\n" + ACCOUNT_NOTE + "\n\n"
    readme += "16개 화면 × 두 테마의 비교 32페이지와 인증 준비 창 전체 세 탭 × 두 테마의 부록 6페이지를 수록했습니다. 원본 이미지에 UI 내용을 덧그리거나 시안의 예시 값을 주입하지 않았습니다.\n\n"
    readme += "[이미지 SHA-256 목록](image-manifest.json) · [PDF 문서 무결성 검사](pdf-verification.json)\n\n기능·회귀·네이티브 창·Katana·배포 패키지 검증 결과는 상위 검증 기록에서 별도로 확인하세요.\n"
    (destination / "README.md").write_text(readme, encoding="utf-8")
    archive = destination / "workbench-simple-1.4.17-pdf-alignment-images.zip"
    included = [p for p in sorted(destination.rglob("*")) if p.is_file() and p != archive and p.name != "artifact-verification.json"]
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as zipped:
        for path in included:
            zipped.write(path, relative(path, destination))
    with zipfile.ZipFile(archive) as zipped:
        if zipped.testzip() is not None:
            raise RuntimeError("Report ZIP CRC verification failed")
        files = len(zipped.namelist())
    artifact = {"version": VERSION, "jarSha256": layout["jarSha256"], "comparisonPages": 32, "authFullTabAppendixPages": 6,
                "actualImages": 32, "referenceImages": 32, "fullAuthTabImages": 6, "pdfPages": report["pages"],
                "originalImagePixelMatches": report["originalImagePixelMatches"], "pdfSha256": report["sha256"],
                "imageZipSha256": digest(archive), "zipFiles": files, "zipCrcPassed": True, "scope": SCOPE}
    write_json(destination / "artifact-verification.json", artifact)
    print(json.dumps({"result": "passed", "output": str(destination), **artifact}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
