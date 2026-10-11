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
import math

from reportlab.graphics.shapes import Drawing, Rect, Ellipse, Circle, Line, String, Polygon
from reportlab.graphics import renderPDF

from reportlab import rl_config
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, PageBreak, Table, TableStyle, Image,
    KeepTogether, Flowable,
)

ROOT = Path(__file__).resolve().parents[1]
WB = ROOT / "project/burp-development-workspace/burp-workbench-kr/burp-workbench"
FONT = WB / "src/main/resources/fonts/NanumGothic-Regular.ttf"
ASSETS = ROOT / "docs/guide-assets/0.25.0"
SOURCE = ROOT / "docs/GUIDE_0.25.0_KR.md"
OUTPUT = ROOT / "downloads/guides/0.25.0/Burp_Workbench_KR_0.25.0_guide.pdf"
EASY_KO = False
INK = colors.HexColor("#182936")
TEAL = colors.HexColor("#007A7C")
GRAY = colors.HexColor("#5D6A73")
PALE = colors.HexColor("#EDF5F4")
RULE = colors.HexColor("#D7E3E5")
PAGE = (595.276, 841.89)
WIDTH = PAGE[0] - 88


def refresh_previews(java: str):
    output = ROOT / "tmp/pdfs/guide-preview-classes-0.25.0"
    output.mkdir(parents=True, exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    dependencies = [WB / "deps" / name for name in ("montoya-2026.jar", "legacy-api.jar", "rhino.jar")]
    release_jar = WB / "dist/burp-workbench-kr-0.25.0.jar"
    if not release_jar.is_file():
        raise FileNotFoundError("Build the current release JAR and test adapters with python dev.py test first.")
    classpath = os.pathsep.join(map(str, [*dependencies, release_jar, WB / "build/tests"]))
    subprocess.run([java, "com.sun.tools.javac.Main", "--release", "17", "-encoding", "UTF-8", "-cp", classpath,
                    "-d", str(output), str(ROOT / "tools/GuidePreview.java")], check=True)
    subprocess.run([java, "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-cp", classpath + os.pathsep + str(output),
                    "kr.workbench.GuidePreview", str(ASSETS), str(FONT)], check=True)


def esc(text: str):
    value = html.escape(text)
    value = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", value)
    return value.replace("`", "")


class UseCaseMap(Flowable):
    """Exact vector map: system boundary, actors, use cases and labelled handoffs.

    Arrows show information handoff, not automatic execution or UML include/extend.
    Every active comparison is explicitly requested by the user.
    """
    def __init__(self, kind: str):
        super().__init__()
        self.width, self.height = WIDTH, 286
        self.map = Drawing(self.width, self.height)
        d = self.map

        def text(x, y, words, size=8.6, color=INK, anchor="middle"):
            for i, word in enumerate(words.split("\n")):
                d.add(String(x, y-i*11, word, fontName="GuideKR", fontSize=size,
                             fillColor=color, textAnchor=anchor))

        def actor(x, y, name):
            d.add(Circle(x, y+19, 6, strokeColor=INK, fillColor=None))
            for a,b,c,e in [(x,y+13,x,y-7),(x-11,y+7,x+11,y+7),
                            (x,y-7,x-9,y-20),(x,y-7,x+9,y-20)]:
                d.add(Line(a,b,c,e,strokeColor=INK,strokeWidth=1))
            text(x,y-33,name,8.5)

        def box(x,y,w,h,label):
            d.add(Rect(x,y,w,h,rx=5,ry=5,strokeColor=RULE,fillColor=PALE))
            text(x+w/2,y+h/2+3,label,8.3)

        def use(x,y,w,label):
            d.add(Ellipse(x,y,w/2,20,strokeColor=TEAL,strokeWidth=1,
                          fillColor=colors.white))
            text(x,y+3,label,8.7)

        def arrow(points, label=None, at=None):
            for first, second in zip(points, points[1:]):
                d.add(Line(*first,*second,strokeColor=GRAY,strokeWidth=.8))
            (x0,y0),(x,y)=points[-2:]
            angle=math.atan2(y-y0,x-x0)
            wing=4.5
            d.add(Polygon([x,y,x-wing*math.cos(angle-.5),y-wing*math.sin(angle-.5),
                           x-wing*math.cos(angle+.5),y-wing*math.sin(angle+.5)],
                          fillColor=GRAY,strokeColor=GRAY))
            if label and at:text(*at,label,7.6,GRAY)

        d.add(Rect(74,15,344,263,rx=7,ry=7,strokeColor=TEAL,
                   strokeWidth=1,fillColor=colors.HexColor("#F7FAFB")))
        text(246,263,"WORKBENCH KR",9.4,TEAL)
        actor(29,166,"점검자")
        if kind == "inspect":
            use(246,229,191,"SCOPE에 점검할 주소 등록" if EASY_KO else "SCOPE에 허용 범위 등록")
            use(246,165,184,"목록에서 요청 선택" if EASY_KO else "관측 요청·응답 선택")
            use(165,81,153,"다른 계정으로 요청 보내기" if EASY_KO else "계정을 바꿔 비교 실행")
            use(333,81,143,"Repeater 요청 준비")
            box(435,210,67,40,"Burp\n브라우저")
            box(435,98,67,37,"Burp\nRepeater")
            box(435,23,67,38,"대상 서버")
            arrow([(41,166),(153,166)],"선택",(102,175))
            arrow([(42,189),(60,229),(150,229)],"범위 지정",(99,240))
            arrow([(246,208),(246,185)],"주소 확인" if EASY_KO else "공통 범위",(287,194))
            arrow([(435,229),(427,229),(427,166),(339,166)],"요청·응답" if EASY_KO else "관측",(386,178))
            arrow([(213,144),(183,102)],"직접 실행" if EASY_KO else "명시적 실행",(159,126))
            arrow([(279,144),(315,102)],"요청 가져오기" if EASY_KO else "편집 초안",(335,125))
            arrow([(165,60),(165,35),(435,35)],"요청 전송 · 응답 비교",(292,41))
            arrow([(404,81),(426,81),(426,116),(435,116)])
        elif kind == "evidence":
            use(246,228,228,"확인한 요청·응답 모으기" if EASY_KO else "검사 결과와 원본을 증거로 연결")
            use(246,164,220,"결과 설명 적기\n민감한 정보 가리기" if EASY_KO else "영향·재현 작성 / 가림 미리보기")
            use(174,84,155,"증거 ZIP 저장")
            use(335,84,147,"남은 점검 확인\n업무 완료 누르기" if EASY_KO else "업무 조건 확인\n직접 완료")
            box(435,201,67,47,"Burp에 기록된\n요청·응답" if EASY_KO else "Burp 트래픽\n원본 패킷")
            box(435,23,67,42,"저장한\n증거 파일" if EASY_KO else "사용자\n로컬 파일")
            arrow([(435,224),(361,224)],"요청·응답" if EASY_KO else "원본",(400,235))
            arrow([(246,207),(246,185)],"저장할 자료",(291,194))
            arrow([(41,166),(135,166)],"직접 작성·확인",(89,178))
            arrow([(213,143),(182,105)],"직접 저장",(166,124))
            arrow([(174,63),(174,40),(435,40)],"ZIP 파일 연결" if EASY_KO else "파일 생성 후 연결",(282,45))
            arrow([(435,62),(426,91),(409,91)],"파일 확인",(460,91))
            arrow([(41,146),(57,83),(95,83)],"저장 선택",(59,113))
        else:
            raise ValueError(f"Unknown diagram: {kind}")

    def draw(self):
        renderPDF.draw(self.map, self.canv, 0, 0)



def build():
    rl_config.invariant = 1
    pdfmetrics.registerFont(TTFont("GuideKR", str(FONT)))
    pdfmetrics.registerFontFamily("GuideKR", normal="GuideKR", bold="GuideKR", italic="GuideKR", boldItalic="GuideKR")
    body = ParagraphStyle("body", fontName="GuideKR", fontSize=10.3, leading=15.8,
                          textColor=INK, spaceAfter=7, wordWrap="CJK", alignment=TA_LEFT)
    styles = {
        "body": body,
        "title": ParagraphStyle("title", parent=body, fontSize=22, leading=29, textColor=INK, spaceAfter=15),
        "subtitle": ParagraphStyle("subtitle", parent=body, fontSize=13, leading=19, textColor=TEAL, spaceBefore=6, spaceAfter=10),
        "caption": ParagraphStyle("caption", parent=body, fontSize=8.6, leading=12, textColor=GRAY, spaceAfter=12),
        "cell": ParagraphStyle("cell", parent=body, fontSize=9.3, leading=14, spaceAfter=0),
        "head": ParagraphStyle("head", parent=body, fontSize=9.3, leading=14, textColor=colors.white, spaceAfter=0),
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
            elif line.startswith("<!-- diagram:"):
                name, caption = line[len("<!-- diagram:"):-3].strip().split("|", 1)
                story.append(KeepTogether([UseCaseMap(name), Spacer(1, 6),
                    Paragraph(esc(caption), styles["caption"])]))
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
                ratios = [0.31, .69] if columns == 2 else ([.30, .42, .28] if number == 1 else [.25, .37, .38])
                if columns not in (2,3):
                    raise ValueError(f"Page {number+1}: unsupported table width {columns}")
                data = [[Paragraph(esc(cell), styles["head" if row == 0 else "cell"]) for cell in values]
                        for row, values in enumerate(raw)]
                table = Table(data, colWidths=[WIDTH * x for x in ratios], hAlign="LEFT", repeatRows=1)
                table.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, 0), TEAL),
                                           ("ROWBACKGROUNDS", (0, 1), (-1, -1), [PALE, colors.white]),
                                           ("VALIGN", (0, 0), (-1, -1), "TOP"),
                                           ("LEFTPADDING", (0, 0), (-1, -1), 9),
                                           ("RIGHTPADDING", (0, 0), (-1, -1), 9),
                                           ("TOPPADDING", (0, 0), (-1, -1), 7),
                                           ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
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
        canvas.drawString(44, PAGE[1] - 31, "WORKBENCH KR / 0.25.0")
        canvas.drawRightString(PAGE[0] - 44, PAGE[1] - 31, "실습 사이트 3.5.0 · 쉬운 한국어 개정판" if EASY_KO else "실습 사이트 3.5.0 · 한국어 가이드")
        canvas.setStrokeColor(RULE)
        canvas.line(44, 43, PAGE[0] - 44, 43)
        canvas.setFillColor(GRAY)
        canvas.drawString(44, 28, "사용법을 설명하기 위해 만든 예시 화면" if EASY_KO else "운영 UI 미리보기 · 실제 Burp / Docker 실행 검증과 구분")
        canvas.drawRightString(PAGE[0] - 44, 28, f"{doc.page:02d} / {len(pages)}")

    class GuideDocument(SimpleDocTemplate):
        chapters = 0
        def afterFlowable(self, flowable):
            if isinstance(flowable, Paragraph) and flowable.style.name in ("title", "cover"):
                self.chapters += 1
                if self.page != self.chapters:
                    raise RuntimeError(f"Guide overflow before chapter {self.chapters}: title landed on page {self.page}. Shorten that chapter before publication.")
                key = f"chapter-{self.page}"
                self.canv.bookmarkPage(key)
                self.canv.addOutlineEntry(flowable.getPlainText(), key, 0, False)

    document = GuideDocument(str(OUTPUT), pagesize=PAGE, rightMargin=44, leftMargin=44,
                                topMargin=63, bottomMargin=58, title="Burp Workbench KR 0.25.0 쉬운 한국어 사용 안내서" if EASY_KO else "Burp Workbench KR 0.25.0 처음 시작하는 웹 보안 점검",
                                author="Burp Workbench KR", pageCompression=1)
    document.build(story, onFirstPage=page_frame, onLaterPages=page_frame)
    if document.page != len(pages):
        raise RuntimeError(f"Guide overflow: expected {len(pages)} pages, got {document.page}; render and fix before publishing.")
    checksum = hashlib.sha256(OUTPUT.read_bytes()).hexdigest()
    OUTPUT.with_name("SHA256SUMS.txt").write_text(f"{checksum}  {OUTPUT.name}\n", encoding="utf-8")
    print(f"Built {OUTPUT.relative_to(ROOT)} ({OUTPUT.stat().st_size:,} bytes); expected {len(pages)} pages")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--refresh-previews", action="store_true")
    parser.add_argument("--java", default="java")
    parser.add_argument("--edition", choices=("original", "easy-ko"), default="original")
    args = parser.parse_args()
    if args.edition == "easy-ko":
        EASY_KO = True
        SOURCE = ROOT / "docs/GUIDE_0.25.0_EASY_KR.md"
        OUTPUT = ROOT / "downloads/guides/0.25.0-easy-ko/Burp_Workbench_KR_0.25.0_easy_guide.pdf"
    if args.refresh_previews:
        refresh_previews(args.java)
    build()
