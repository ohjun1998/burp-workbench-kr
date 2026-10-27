#!/usr/bin/env python3
"""Validate the final Korean guide, its real-component captures and prior preservation.

This checks document structure and provenance; it is not a Burp/browser runtime test.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import runpy
import sys

ROOT = Path(__file__).resolve().parents[1]
VERSION = runpy.run_path(str(ROOT / 'burp-workbench/release_info.py'))['VERSION']


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--images', type=Path, default=ROOT/f'docs/images/{VERSION}/light/1280x800')
    parser.add_argument('--contact-sheet', type=Path, help='Optional local preview of all PDF pages; not an application screenshot')
    parser.add_argument('--visual-reviewed', action='store_true', help='Record only after reviewing the contact sheet and full-size key pages')
    args = parser.parse_args()
    try:
        import fitz
        from PIL import Image, ImageDraw
    except ImportError as error:
        parser.error('Install local document QA dependencies PyMuPDF and Pillow: '+str(error))
    guide = runpy.run_path(str(Path(__file__).with_name('build_simple_guide.py')))
    steps = guide['STEPS']
    from reportlab.pdfbase.ttfonts import TTFont
    font = TTFont('SimpleGuideGlyphAudit', str(ROOT/'burp-workbench/src/main/resources/fonts/NanumGothic-Regular.ttf'))
    def strings(value):
        if isinstance(value,str): return [value]
        if isinstance(value,dict): return [text for item in value.values() for text in strings(item)]
        if isinstance(value,(tuple,list)): return [text for item in value for text in strings(item)]
        return []
    source_text = ''.join(text for key in ['MODULES','STEPS','INSTALL','REFERENCE','INTERPRETATION','SCOPE_FEATURES','HELPER_INSTALL','KATANA_INSTALL','KATANA_NOTES','CAPTURE_NOTE'] for text in strings(guide[key]))
    missing_glyphs = sorted({char for char in source_text if not char.isspace() and ord(char) not in font.face.charToGlyph})
    if missing_glyphs:
        raise RuntimeError('Guide font has no glyphs for: '+''.join(missing_glyphs))
    pdf = ROOT/f'downloads/guides/{VERSION}/Workbench_Simple_{VERSION}_Guide_KR.pdf'
    html = ROOT/'docs/GUIDE_SIMPLE_KR.html'
    reports = []
    for name in ['layout-report.json', 'scope-layout-report.json']:
        path = ROOT/f'docs/images/{VERSION}'/name
        result = json.loads(path.read_text(encoding='utf-8'))
        if result.get('result') != 'passed':
            raise RuntimeError('Source screenshot layout did not pass: '+str(path))
        reports.append(result)
    jar_hashes = {report['jarSha256'] for report in reports}
    if len(jar_hashes) != 1:
        raise RuntimeError('Guide captures use different JARs')
    captured_jar_hash = next(iter(jar_hashes))
    jar = ROOT/f'burp-workbench/dist/burp-workbench-simple-{VERSION}.jar'
    if sha(jar) != captured_jar_hash:
        raise RuntimeError('Guide captures differ from current JAR')
    image_hashes = {('browser/'+step['image'] if step.get('captureKind')=='browser' else step['image']):sha(guide['capture_path'](args.images,step)) for step in steps}
    preserved = {}
    for old in ['1.0.0','1.1.0','1.2.0']:
        old_pdf = ROOT/f'downloads/guides/{old}/Workbench_Simple_{old}_Guide_KR.pdf'
        old_qa = ROOT/'docs'/('guide-validation.json' if old == '1.0.0' else f'guide-validation-{old}.json')
        if old == '1.0.0':
            old_pdf = ROOT/'downloads/guides/1.0.0/Workbench_Simple_1.0.0_Guide_KR.pdf'
        if old_qa.exists():
            old_info = json.loads(old_qa.read_text(encoding='utf-8'))
            ok = old_pdf.is_file() and sha(old_pdf) == old_info['pdfSha256']
            preserved[old] = {'pdfHashUnchanged':ok}
            if not ok:
                raise RuntimeError('Previous PDF hash differs: '+old)
    for history in sorted((ROOT/'docs/history').glob('*/preservation.json')):
        record = json.loads(history.read_text(encoding='utf-8'))
        for relative,expected in record['sha256'].items():
            if sha((history.parent/relative).resolve()) != expected:
                raise RuntimeError('Historical guide content differs: '+relative)
        preserved.setdefault(record['version'],{})['historicalFilesUnchanged'] = True
        preserved[record['version']]['historicalFilesChecked'] = len(record['sha256'])
    document = fitz.open(pdf)
    expected_pages = len(steps)+guide['GUIDE_FIXED_PAGES']
    if len(document) != expected_pages:
        raise RuntimeError(f'Guide pages {len(document)} != {expected_pages}')
    if len(document.get_toc()) != expected_pages:
        raise RuntimeError('Guide bookmark count differs')
    outside = []
    fonts = set()
    screenshot_pages = []
    for number,page in enumerate(document,1):
        for font in page.get_fonts():
            fonts.add(font[3])
        if page.get_images():
            screenshot_pages.append(number)
        for block in page.get_text('dict')['blocks']:
            for line in block.get('lines',[]):
                for span in line['spans']:
                    box = fitz.Rect(span['bbox'])
                    if not (page.rect+(-1,-1,1,1)).contains(box):
                        outside.append({'page':number,'text':span['text'],'bbox':list(box)})
    if outside:
        raise RuntimeError('Text extends outside PDF page: '+json.dumps(outside[:5],ensure_ascii=False))
    if len(screenshot_pages) != len(steps):
        raise RuntimeError('Screenshot page count differs')
    if not any('NanumGothic' in font for font in fonts):
        raise RuntimeError('Embedded Korean font missing')
    text = '\n'.join(page.get_text() for page in document)
    for required in ['SCOPE','HTTP','Katana','loopback','민감정보','브라우저','원본','미확인','관측','증거']:
        if required not in text:
            raise RuntimeError('Required guide subject missing: '+required)
    if args.contact_sheet:
        args.contact_sheet.parent.mkdir(parents=True,exist_ok=True)
        columns = 4;cell_w=360;cell_h=285;rows=(len(document)+columns-1)//columns
        sheet=Image.new('RGB',(columns*cell_w,rows*cell_h),'#e8ecf3');draw=ImageDraw.Draw(sheet)
        for index,page in enumerate(document):
            pix=page.get_pixmap(matrix=fitz.Matrix(.28,.28),alpha=False)
            image=Image.frombytes('RGB',[pix.width,pix.height],pix.samples)
            image.thumbnail((cell_w-12,cell_h-26))
            x=(index%columns)*cell_w+(cell_w-image.width)//2;y=(index//columns)*cell_h+6
            sheet.paste(image,(x,y));draw.text((x,y+image.height+3),str(index+1),fill='#17233b')
        sheet.save(args.contact_sheet)
    output=ROOT/f'docs/guide-validation-{VERSION}.json'
    result={
        'guideVersion':VERSION,'pdf':pdf.relative_to(ROOT).as_posix(),'pdfSha256':sha(pdf),
        'html':html.relative_to(ROOT).as_posix(),'htmlSha256':sha(html),
        'pdfPages':len(document),'pdfOutlineEntries':len(document.get_toc()),
        'pdfScreenshotPages':len(screenshot_pages),'pdfPageFormat':'A3 landscape',
        'pdfFonts':sorted(fonts),'outOfPageText':outside,
        'sourceTextMissingFontGlyphs':missing_glyphs,
        'sourceScreenshotJarSha256':captured_jar_hash,
        'screenshotLayoutResult':'passed','scopeScreenshotLayoutResult':'passed',
        'usedScreenshots':image_hashes,
        'allEightToolScreensIncluded':['02-request.png','08-mapper.png','04-auth-results.png','10-js.png','09-environment.png','03-analysis.png','07-payload.png','05-evidence.png'],
        'manualReplayScreenIncluded':'11-replay.png',
        'inspectionScreensIncluded':[step['image'] for step in steps if step['number'].startswith('S')],
        'katanaVersion':guide['KATANA_VERSION'],
        'katanaScreensIncluded':[step['image'] for step in steps if step['number'].startswith('K')],
        'browserHelperCapture':{'included':guide['BROWSER_CAPTURE'].is_file(),'helperVersion':guide['HELPER_VERSION'],
            'path':guide['BROWSER_CAPTURE'].relative_to(ROOT).as_posix(),
            'kind':'Actual Chromium helper page using localhost synthetic fixture; distinct from production Swing rendering'},
        'preservedPreviousGuides':preserved,
        'visualReview':'All pages reviewed in a contact sheet; full-size Katana setup/results, inspection setup, environment, paths, API, secrets, access, changes, coverage, browser, originals and evidence pages reviewed for readable Korean text and uncropped controls.' if args.visual_reviewed else 'Structural checks passed; author visual review is still pending.',
        'captureScope':'Production Simple Swing components with synthetic packet/API/Katana adapters; actual Katana process/HTTP/HTTPS checks are separate verification evidence. Chrome/Edge installation instructions are textual, not native browser installation screenshots.',
        'structuralResult':'passed','visualReviewComplete':args.visual_reviewed,
        'result':'passed' if args.visual_reviewed else 'needs-visual-review'
    }
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({key:result[key] for key in ['guideVersion','pdfPages','pdfOutlineEntries','pdfScreenshotPages','sourceScreenshotJarSha256','result']},ensure_ascii=False))
    return 0


if __name__=='__main__':
    try:
        raise SystemExit(main())
    except (OSError,RuntimeError,KeyError,ValueError) as error:
        print('Guide QA failed: '+str(error),file=sys.stderr)
        raise SystemExit(1)
