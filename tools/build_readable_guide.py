#!/usr/bin/env python3
"""Build the readable 0.25.4 edition while preserving earlier guide files."""
from pathlib import Path
import hashlib, re
from reportlab import rl_config
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak, Table, TableStyle, Image, KeepTogether
import build_guide as original

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT/'docs/GUIDE_0.25.4_KR.md'
ASSETS = ROOT/'docs/guide-assets/0.25.4'
OUTPUT = ROOT/'downloads/guides/0.25.4-readable/Burp_Workbench_KR_0.25.4_guide.pdf'
INK = colors.HexColor('#20282F')
MUTED = colors.HexColor('#58636D')
ACCENT = colors.HexColor('#16756E')
RULE = colors.HexColor('#DCE6E5')
PALE = colors.HexColor('#F1F6F5')
BLUE = colors.HexColor('#EEF3F9')
WIDTH = 507.276


def build():
    rl_config.invariant = 1
    pdfmetrics.registerFont(TTFont('GuideKR', str(original.FONT)))
    pdfmetrics.registerFontFamily('GuideKR', normal='GuideKR', bold='GuideKR', italic='GuideKR', boldItalic='GuideKR')
    original.EASY_KO = True
    body = ParagraphStyle('body', fontName='GuideKR', fontSize=10.6, leading=16.0, textColor=INK,
                          wordWrap='CJK', spaceAfter=7)
    title = ParagraphStyle('chapter', parent=body, fontSize=21, leading=28, spaceAfter=14)
    subtitle = ParagraphStyle('subtitle', parent=body, fontSize=12.8, leading=19, spaceBefore=5, spaceAfter=10)
    caption = ParagraphStyle('caption', parent=body, fontSize=8.6, leading=12.5, textColor=MUTED, spaceAfter=9)
    cell = ParagraphStyle('cell', parent=body, fontSize=9.7, leading=14.3, spaceAfter=0)
    head = ParagraphStyle('head', parent=cell, textColor=colors.white)
    cover = ParagraphStyle('cover', parent=body, fontSize=32, leading=41, spaceAfter=20)
    step_style = ParagraphStyle('step', parent=body, spaceAfter=0)
    number_style = ParagraphStyle('number', parent=body, fontSize=12.5, leading=17, spaceAfter=0)
    pages = SOURCE.read_text().strip().split('\n---\n')
    story = []

    def box(text, background=PALE):
        table = Table([[Paragraph(original.esc(text), body)]], colWidths=[WIDTH])
        table.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,-1),background),('BOX',(0,0),(-1,-1),.5,RULE),
             ('LINEBEFORE',(0,0),(0,-1),3,ACCENT),('LEFTPADDING',(0,0),(-1,-1),11),
             ('RIGHTPADDING',(0,0),(-1,-1),11),('TOPPADDING',(0,0),(-1,-1),8),('BOTTOMPADDING',(0,0),(-1,-1),3)]))
        return table

    for number, source in enumerate(pages):
        if number:
            story.append(PageBreak())
        else:
            story.append(Spacer(1, 15))
        lines = source.strip().splitlines()
        i = 0
        while i < len(lines):
            line = lines[i].strip()
            if not line:
                i += 1
                continue
            if line.startswith('# '):
                story.append(Paragraph(original.esc(line[2:]), cover if number == 0 else title))
            elif line.startswith('## '):
                story.append(Paragraph(original.esc(line[3:]), subtitle))
            elif line.startswith('<!-- diagram:'):
                name, label = line[len('<!-- diagram:'):-3].strip().split('|', 1)
                story.append(KeepTogether([original.UseCaseMap(name), Spacer(1, 5), Paragraph(original.esc(label), caption)]))
            elif line.startswith('<!-- figure:'):
                name, label = line[len('<!-- figure:'):-3].strip().split('|', 1)
                picture = Image(str(ASSETS/name))
                scale = min(WIDTH/picture.drawWidth, 305/picture.drawHeight)
                picture.drawWidth *= scale
                picture.drawHeight *= scale
                story.append(KeepTogether([picture, Spacer(1, 5), Paragraph(original.esc(label), caption)]))
            elif line.startswith('```'):
                code = []
                i += 1
                while i < len(lines) and not lines[i].startswith('```'):
                    code.append(lines[i])
                    i += 1
                story.extend([box('\n'.join(code), BLUE), Spacer(1, 8)])
            elif line.startswith('|'):
                raw = []
                while i < len(lines) and lines[i].strip().startswith('|'):
                    values = [v.strip() for v in lines[i].strip().strip('|').split('|')]
                    if not all(re.fullmatch(r'[-: ]+', v) for v in values):
                        raw.append(values)
                    i += 1
                i -= 1
                ratios = [0.31, .69] if len(raw[0]) == 2 else ([.30,.42,.28] if number == 1 else [.25,.37,.38])
                rows = [[Paragraph(original.esc(v), head if row == 0 else cell) for v in values] for row, values in enumerate(raw)]
                table = Table(rows, colWidths=[WIDTH*x for x in ratios], repeatRows=1, hAlign='LEFT')
                table.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),ACCENT),('ROWBACKGROUNDS',(0,1),(-1,-1),[PALE,colors.white]),
                    ('VALIGN',(0,0),(-1,-1),'TOP'),('LEFTPADDING',(0,0),(-1,-1),8),('RIGHTPADDING',(0,0),(-1,-1),8),
                    ('TOPPADDING',(0,0),(-1,-1),6),('BOTTOMPADDING',(0,0),(-1,-1),6),('LINEBELOW',(0,0),(-1,0),.5,RULE)]))
                story.extend([table, Spacer(1, 10)])
            elif match := re.match(r'^(\d+)\.\s+(.*)', line):
                table = Table([[Paragraph(match[1].zfill(2), number_style), Paragraph(original.esc(match[2]), step_style)]], colWidths=[30,WIDTH-30])
                table.setStyle(TableStyle([('BACKGROUND',(0,0),(0,0),BLUE),('VALIGN',(0,0),(-1,-1),'TOP'),
                    ('LEFTPADDING',(0,0),(0,0),4),('RIGHTPADDING',(0,0),(0,0),4),('LEFTPADDING',(1,0),(1,0),9),
                    ('TOPPADDING',(0,0),(-1,-1),3),('BOTTOMPADDING',(0,0),(-1,-1),3)]))
                story.extend([table, Spacer(1, 4)])
            elif line.startswith(('**확인할 결과', '**저장 후', '**화면에서 막히면', '**목록이 비어 있다면', '**버튼을 누르기 전에')):
                story.extend([box(line), Spacer(1, 8)])
            else:
                story.append(Paragraph(original.esc(line), body))
            i += 1

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)

    def frame(canvas, doc):
        canvas.setFillColor(ACCENT)
        canvas.rect(44, 799, 36, 4, fill=1, stroke=0)
        canvas.setFillColor(MUTED)
        canvas.setFont('GuideKR', 8)
        canvas.drawString(90, 798, 'WORKBENCH KR / 0.25.4')
        canvas.drawRightString(551, 798, '읽기 쉬운 사용 안내서 · 실습 3.5.0')
        canvas.setStrokeColor(RULE)
        canvas.line(44, 43, 551, 43)
        canvas.drawString(44, 28, '화면은 사용법 설명을 위한 예시 데이터입니다.')
        canvas.drawRightString(551, 28, f'{doc.page:02d} / {len(pages)}')

    class Guide(SimpleDocTemplate):
        chapters = 0
        def afterFlowable(self, flowable):
            if isinstance(flowable, Paragraph) and flowable.style.name in ('chapter','cover'):
                self.chapters += 1
                if self.page != self.chapters:
                    raise RuntimeError(f'Chapter {self.chapters} overflowed to page {self.page}')
                key = f'chapter-{self.chapters}'
                self.canv.bookmarkPage(key)
                self.canv.addOutlineEntry(flowable.getPlainText(),key,0,False)

    doc = Guide(str(OUTPUT), pagesize=original.PAGE, rightMargin=44,leftMargin=44,topMargin=63,bottomMargin=58,
                title='Burp Workbench KR 0.25.4 읽기 쉬운 사용 안내서',author='Burp Workbench KR',pageCompression=1)
    doc.build(story,onFirstPage=frame,onLaterPages=frame)
    if doc.page != len(pages):
        raise RuntimeError(f'Expected {len(pages)} pages, got {doc.page}')
    digest = hashlib.sha256(OUTPUT.read_bytes()).hexdigest()
    OUTPUT.with_name('SHA256SUMS.txt').write_text(f'{digest}  {OUTPUT.name}\n')
    print(f'Built {OUTPUT.relative_to(ROOT)}; {doc.page} pages; SHA-256 {digest}')


if __name__ == '__main__':
    build()
