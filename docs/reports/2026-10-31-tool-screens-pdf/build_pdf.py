from pathlib import Path
import hashlib
import json
import fitz
from PIL import Image
from fontTools.ttLib import TTFont
from fontTools import subset

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / 'docs/reports/2026-10-31-contextual-button-layout/images/actual/dark'
OUT = Path(__file__).parent
SCREENS = [
    ('요청·응답 목록', 'traffic'),
    ('기능 자동 매핑', 'mapper'),
    ('JS에서 API 찾기', 'js'),
    ('서버·경로 탐색', 'environment'),
    ('외부 노출 조사 · Google 검색', 'exposure-search'),
    ('외부 노출 조사 · CVE 후보', 'exposure-cve'),
    ('계정 권한 비교', 'auth'),
    ('계정 권한 비교 · 인증 후보 연결', 'auth-accounts'),
    ('응답 보안 분석', 'response-recon'),
    ('입력값 보안 검사 · 요청 준비', 'payload'),
    ('입력값 보안 검사 · 실행·결과', 'payload-results'),
    ('데이터 변환·비교', 'data'),
    ('점검 진행 현황', 'activity'),
    ('재현·증거 정리', 'bounty'),
    ('재현·증거 정리 · 요청 재현', 'bounty-replay'),
    ('점검 대시보드', 'dashboard'),
]

font_path = OUT / 'NanumGothic-Subset.ttf'
embedded_font = TTFont(ROOT / 'simple/burp-workbench/src/main/resources/fonts/NanumGothic-Regular.ttf')
options = subset.Options()
options.desubroutinize = True
options.hinting = False
subsetter = subset.Subsetter(options=options)
subsetter.populate(text=''.join(title for title, _ in SCREENS) +
                   'WORKBENCH SIMPLE 1.4.12실제 Swing 구현 화면 · 예시 데이터 · 다크 테마0123456789 /')
subsetter.subset(embedded_font)
embedded_font.save(font_path)
font = fitz.Font(fontfile=str(font_path))
assert font.has_glyph(ord('요'))

pdf = fitz.open()
pdf.set_metadata({
    'title': 'Workbench Simple 1.4.12 · 도구별 적용 화면',
    'author': 'Burp Workbench KR',
    'subject': '실제 Swing 구현 화면 16개 · 예시 데이터 · 다크 테마',
    'keywords': 'Workbench, Simple 1.4.12, 적용 화면, 다크 테마',
    'creator': 'PyMuPDF · original PNG screenshots',
})
width, height = fitz.paper_size('a3-l')
margin = 22
image_box = fitz.Rect(margin, 80, width-margin, height-38)
accent = (1, .39, .12)
ink = (.10, .14, .19)
muted = (.40, .45, .51)
for number, (title, name) in enumerate(SCREENS, start=1):
    page = pdf.new_page(width=width, height=height)
    page.insert_font(fontname='WorkbenchKR', fontfile=str(font_path))
    page.insert_text((margin, 23), 'WORKBENCH SIMPLE 1.4.12', fontname='WorkbenchKR', fontsize=10, color=muted)
    page.insert_text((margin, 55), f'{number:02d}  {title}', fontname='WorkbenchKR', fontsize=21, color=ink)
    page.draw_line((margin, 67), (width-margin, 67), color=(.84, .87, .90), width=.7)
    page.draw_line((margin, 67), (margin+45, 67), color=accent, width=2.5)
    page.insert_image(image_box, filename=str(SOURCE / f'{name}.png'), keep_proportion=True)
    page.insert_text((margin, height-14), '실제 Swing 구현 화면 · 예시 데이터 · 다크 테마', fontname='WorkbenchKR', fontsize=9, color=muted)
    counter = f'{number:02d} / {len(SCREENS):02d}'
    tw = font.text_length(counter, fontsize=9)
    page.insert_text((width-margin-tw, height-14), counter, fontname='WorkbenchKR', fontsize=9, color=muted)

pdf.set_toc([[1, title, number] for number, (title, _) in enumerate(SCREENS, 1)])
pdf_path = OUT / 'workbench-simple-1.4.12-tool-screens.pdf'
pdf.save(pdf_path, garbage=4, deflate=True, deflate_images=True, deflate_fonts=True)
pdf.close()

doc = fitz.open(pdf_path)
assert len(doc) == 16
assert doc.get_toc() == [[1, title, number] for number, (title, _) in enumerate(SCREENS, 1)]
checks = []
for number, (title, name) in enumerate(SCREENS, 1):
    page = doc[number-1]
    text = page.get_text().replace('\u00a0', ' ')
    assert title in text, (number, title, text)
    assert f'{number:02d} / 16' in text
    infos = page.get_image_info(xrefs=True)
    assert len(infos) == 1, (number, infos)
    info = infos[0]
    rect = fitz.Rect(info['bbox'])
    assert image_box.contains(rect), (number, rect, image_box)
    assert abs(rect.width/rect.height - info['width']/info['height']) < .0001
    pix = fitz.Pixmap(doc, info['xref'])
    with Image.open(SOURCE / f'{name}.png') as original:
        original = original.convert('RGB')
        restored = Image.frombytes('RGB', (pix.width, pix.height), pix.samples)
        assert original.size == restored.size
        assert original.tobytes() == restored.tobytes(), number
    checks.append({'page': number, 'title': title, 'source': f'{name}.png',
                   'width': info['width'], 'height': info['height'], 'pixelsPreserved': True,
                   'koreanTitleExtractable': True, 'imageInsidePage': True})
for i in [0, 5, 7, 15]:
    doc[i].get_pixmap(matrix=fitz.Matrix(1, 1), alpha=False).save(OUT / f'preview-{i+1:02d}.png')
manifest = {'file': pdf_path.name, 'pages': len(doc), 'bookmarks': len(doc.get_toc()),
            'bytes': pdf_path.stat().st_size, 'sha256': hashlib.sha256(pdf_path.read_bytes()).hexdigest(),
            'theme': 'dark', 'sourceVersion': 'Simple 1.4.12', 'checks': checks}
(OUT / 'verification.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps({k:v for k,v in manifest.items() if k!='checks'}, ensure_ascii=False))
doc.close()
font_path.unlink()
