#!/usr/bin/env python3
"""Build the Korean guide from Markdown + reviewed production Swing previews.

Install reportlab; optionally use --refresh-previews after python dev.py test.
Previews use test API adapters and synthetic data, not native Burp or HTTP.
"""
from __future__ import annotations

import argparse
import html
import hashlib
import os
from pathlib import Path
import re
import subprocess

from reportlab import rl_config
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, PageBreak, Table, TableStyle, Image,
    KeepTogether,
)

ROOT = Path(__file__).resolve().parents[1]
WB = ROOT / "project/burp-development-workspace/burp-workbench-kr/burp-workbench"
FONT = WB / "src/main/resources/fonts/NanumGothic-Regular.ttf"
ASSETS = ROOT / "docs/guide-assets/0.23.0"
SOURCE = ROOT / "docs/GUIDE_0.23.0_KR.md"
OUTPUT = ROOT / "downloads/guides/0.23.0/Burp_Workbench_KR_0.23.0_guide.pdf"
INK = colors.HexColor("#182936")
TEAL = colors.HexColor("#007A7C")
GRAY = colors.HexColor("#5D6A73")
PALE = colors.HexColor("#EDF5F4")
RULE = colors.HexColor("#D7E3E5")
PAGE = (595.276, 841.89)
WIDTH = PAGE[0] - 88


def refresh_previews(java: str):
    output = ROOT / "tmp/pdfs/guide-preview-classes"
    output.mkdir(parents=True, exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    dependencies = [WB / "deps" / name for name in ("montoya-2026.jar", "legacy-api.jar", "rhino.jar")]
    classpath = os.pathsep.join(map(str, [*dependencies, WB / "build/classes", WB / "build/tests"]))
    subprocess.run([java, "com.sun.tools.javac.Main", "--release", "17", "-encoding", "UTF-8", "-cp", classpath,
                    "-d", str(output), str(ROOT / "tools/GuidePreview.java")], check=True)
    subprocess.run([java, "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-cp", classpath + os.pathsep + str(output),
                    "kr.workbench.GuidePreview", str(ASSETS), str(FONT)], check=True)


def esc(text: str):
    return html.escape(text).replace("**", "")


def build():
    rl_config.invariant = 1
    pdfmetrics.registerFont(TTFont("GuideKR", str(FONT)))
    body = ParagraphStyle("body", fontName="GuideKR", fontSize=10, leading=16.2,
                          textColor=INK, spaceAfter=9, wordWrap="CJK", alignment=TA_LEFT)
    styles = {
        "body": body,
        "title": ParagraphStyle("title", parent=body, fontSize=23, leading=31, textColor=INK, spaceAfter=20),
        "subtitle": ParagraphStyle("subtitle", parent=body, fontSize=14, leading=21, textColor=TEAL, spaceBefore=7, spaceAfter=12),
        "caption": ParagraphStyle("caption", parent=body, fontSize=8, leading=11.5, textColor=GRAY, spaceAfter=12),
        "cell": ParagraphStyle("cell", parent=body, fontSize=9, leading=14, spaceAfter=0),
        "head": ParagraphStyle("head", parent=body, fontSize=9, leading=14, textColor=colors.white, spaceAfter=0),
        "code": ParagraphStyle("code", parent=body, fontSize=9, leading=15, textColor=TEAL, spaceAfter=0),
        "cover": ParagraphStyle("cover", parent=body, fontSize=35, leading=44, textColor=INK, spaceAfter=20),
    }
    pages = SOURCE.read_text(encoding="utf-8").strip().split("\n---\n")
    story = []
    for number, source in enumerate(pages):
        if number:
            story.append(PageBreak())
        else:
            story.append(Spacer(1, 28))
        lines = source.strip().splitlines()
        i = 0
        while i < len(lines):
            line = lines[i].strip()
            if not line:
                i += 1
                continue
            if line.startswith("# "):
                story.append(Paragraph(esc(line[2:]), styles["cover" if number == 0 else "title"]))
            elif line.startswith("## "):
                story.append(Paragraph(esc(line[3:]), styles["subtitle"]))
            elif line.startswith("<!-- figure:"):
                name, caption = line[len("<!-- figure:"):-3].strip().split("|", 1)
                path = ASSETS / name
                if not path.is_file():
                    raise FileNotFoundError(f"Missing preview {path}. Run --refresh-previews after dev.py test.")
                picture = Image(str(path))
                picture.drawHeight *= WIDTH / picture.drawWidth
                picture.drawWidth = WIDTH
                # Reduce the settings image without making normal tool UI smaller.
                if picture.drawHeight > 335:
                    picture.drawWidth *= 335 / picture.drawHeight
                    picture.drawHeight = 335
                story.append(KeepTogether([picture, Spacer(1, 6), Paragraph(esc(caption), styles["caption"])]))
            elif line.startswith("```"):
                content = []
                i += 1
                while i < len(lines) and not lines[i].startswith("```"):
                    content.append(Paragraph(esc(lines[i]) or " ", styles["code"]))
                    i += 1
                table = Table([[content]], colWidths=[WIDTH])
                table.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, -1), PALE),
                                           ("BOX", (0, 0), (-1, -1), .5, RULE),
                                           ("LEFTPADDING", (0, 0), (-1, -1), 12),
                                           ("TOPPADDING", (0, 0), (-1, -1), 9),
                                           ("BOTTOMPADDING", (0, 0), (-1, -1), 9)]))
                story.extend([table, Spacer(1, 13)])
            elif line.startswith("|"):
                raw = []
                while i < len(lines) and lines[i].strip().startswith("|"):
                    cells = [x.strip() for x in lines[i].strip().strip("|").split("|")]
                    if not all(re.fullmatch(r"[-: ]+", x) for x in cells):
                        raw.append(cells)
                    i += 1
                i -= 1
                columns = len(raw[0])
                ratios = [0.31, .69] if columns == 2 else [.25, .37, .38]
                data = [[Paragraph(esc(cell), styles["head" if row == 0 else "cell"]) for cell in values]
                        for row, values in enumerate(raw)]
                table = Table(data, colWidths=[WIDTH * x for x in ratios], hAlign="LEFT")
                table.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, 0), TEAL),
                                           ("ROWBACKGROUNDS", (0, 1), (-1, -1), [PALE, colors.white]),
                                           ("VALIGN", (0, 0), (-1, -1), "TOP"),
                                           ("LEFTPADDING", (0, 0), (-1, -1), 9),
                                           ("RIGHTPADDING", (0, 0), (-1, -1), 9),
                                           ("TOPPADDING", (0, 0), (-1, -1), 8),
                                           ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
                                           ("LINEBELOW", (0, 0), (-1, -1), .4, RULE)]))
                story.extend([table, Spacer(1, 14)])
            else:
                story.append(Paragraph(esc(line), body))
            i += 1

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)

    def page_frame(canvas, doc):
        canvas.setFillColor(TEAL)
        canvas.rect(0, PAGE[1] - 8, PAGE[0], 8, fill=1, stroke=0)
        canvas.setFont("GuideKR", 8)
        canvas.setFillColor(GRAY)
        canvas.drawString(44, PAGE[1] - 31, "WORKBENCH KR / 0.23.0")
        canvas.drawRightString(PAGE[0] - 44, PAGE[1] - 31, "실습 사이트 3.5.0 · 한국어 가이드")
        canvas.setStrokeColor(RULE)
        canvas.line(44, 43, PAGE[0] - 44, 43)
        canvas.setFillColor(GRAY)
        canvas.drawString(44, 28, "운영 UI 미리보기 · 실제 Burp / Docker 실행 검증과 구분")
        canvas.drawRightString(PAGE[0] - 44, 28, f"{doc.page:02d}")

    class GuideDocument(SimpleDocTemplate):
        def afterFlowable(self, flowable):
            if isinstance(flowable, Paragraph) and flowable.style.name in ("title", "cover"):
                key = f"chapter-{self.page}"
                self.canv.bookmarkPage(key)
                self.canv.addOutlineEntry(flowable.getPlainText(), key, 0, False)

    document = GuideDocument(str(OUTPUT), pagesize=PAGE, rightMargin=44, leftMargin=44,
                                topMargin=63, bottomMargin=58, title="Burp Workbench KR 0.23.0 실전 점검 가이드",
                                author="Burp Workbench KR", pageCompression=1)
    document.build(story, onFirstPage=page_frame, onLaterPages=page_frame)
    checksum = hashlib.sha256(OUTPUT.read_bytes()).hexdigest()
    OUTPUT.with_name("SHA256SUMS.txt").write_text(f"{checksum}  {OUTPUT.name}\n", encoding="utf-8")
    print(f"Built {OUTPUT.relative_to(ROOT)} ({OUTPUT.stat().st_size:,} bytes); expected {len(pages)} pages")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--refresh-previews", action="store_true")
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    if args.refresh_previews:
        refresh_previews(args.java)
    build()
