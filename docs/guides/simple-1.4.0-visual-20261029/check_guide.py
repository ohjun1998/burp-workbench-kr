#!/usr/bin/env python3
"""Verify document evidence without loading the extension or requesting a target."""
from __future__ import annotations

import argparse
import base64
from collections import Counter
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import urllib.parse

import fitz
from PIL import Image
from reportlab.pdfbase.ttfonts import TTFont

HERE=Path(__file__).resolve().parent
BASE='Workbench_Simple_1.4.0_Visual_Guide_20261029_KR'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class Document(HTMLParser):
    def __init__(self):
        super().__init__();self.ids=[];self.links=[];self.images=[];self.resources=[]

    def handle_starttag(self,tag,attrs):
        attrs=dict(attrs)
        if 'id' in attrs:self.ids.append(attrs['id'])
        if tag=='a':self.links.append(attrs.get('href',''))
        if tag=='image':self.images.append(attrs.get('href',''))
        if tag in ['script','link','img','iframe']:
            url=attrs.get('src') or attrs.get('href')
            if url:self.resources.append(url)


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--product-source',type=Path)
    parser.add_argument('--preservation-manifest',type=Path)
    args=parser.parse_args()
    data=json.loads((HERE/'content.json').read_text())
    manifest=json.loads((HERE/'source-manifest.json').read_text())
    browser=json.loads((HERE/'browser-validation.json').read_text())
    visual=json.loads((HERE/'pdf-review.json').read_text())
    html_file,pdf_file=HERE/(BASE+'.html'),HERE/(BASE+'.pdf')
    checks={};steps=data['steps'];shots=manifest['screenshots']
    checks['33_steps_95_explanations']=len(steps)==len(shots)==33 and sum(len(x['actions']) for x in steps)==95
    checks['one_to_one_callouts']=all(len(s['actions'])==len(s['highlights']) for s in steps)
    seen=set();geometry=[]
    for step,shot in zip(steps,shots):
        path=HERE/shot['file'];width,height=Image.open(path).size
        assert sha(path)==shot['sha256']==step['sourceImageSha256'],path
        assert [width,height]==[shot['width'],shot['height']]
        seen.add(path.name)
        for point in shot['highlights']:
            x,y,w,h=point['rect'];assert 0<=x<x+w<=width and 0<=y<y+h<=height
        zx,zy,zw,zh=shot['zoomBox'];assert 0<=zx<zx+zw<=width and 0<=zy<zy+zh<=height
        normal=min((fitz.paper_size('a3')[0]-72)/width,480/height)
        enlarged=min(360/zw,112/zh)
        assert enlarged/normal>=1.2,(path.name,enlarged/normal)
        geometry.append({'source':path.name,'zoomScaleRelativeToMain':round(enlarged/normal,3)})
    checks['source_hashes_and_all_callout_bounds']=len(seen)==33
    checks['all_detail_panels_enlarge_at_least_1_2x']=True
    document=Document();document.feed(html_file.read_text())
    assert not [x for x,n in Counter(document.ids).items() if n>1]
    for url in document.links:
        if url.startswith('#'):assert url[1:] in document.ids,url
        elif not urllib.parse.urlsplit(url).scheme:
            assert (HERE/urllib.parse.unquote(url)).is_file() or url=='validation.json',url
    assert not document.resources,document.resources
    embedded=Counter(hashlib.sha256(base64.b64decode(x.split(',',1)[1])).hexdigest() for x in document.images)
    assert len(embedded)==33 and all(n==2 for n in embedded.values())
    assert set(embedded)=={x['sha256'] for x in shots}
    checks['html_ids_links_and_embedded_original_images']=True
    pdf=fitz.open(pdf_file);outside=[];text='';font_names=set();screenshot_pages=[]
    for index,page in enumerate(pdf):
        text+=page.get_text()
        font_names.update(x[3] for x in page.get_fonts())
        for block in page.get_text('dict')['blocks']:
            if block.get('type')!=0:continue
            for line in block['lines']:
                for span in line['spans']:
                    x0,y0,x1,y1=span['bbox']
                    if min(x0,y0)<-1 or x1>page.rect.width+1 or y1>page.rect.height+1:
                        outside.append({'page':index+1,'text':span['text'],'bbox':span['bbox']})
        if 4<=index<37:
            assert page.get_images(),index+1
            screenshot_pages.append(index+1)
    assert len(pdf)==40 and len(pdf.get_toc())==40 and not outside
    assert '\ufffd' not in text and any('NanumGothic' in x for x in font_names)
    font=TTFont('GuideGlyphCheck',str(HERE.parents[2]/'simple/burp-workbench/src/main/resources/fonts/NanumGothic-Regular.ttf'))
    missing=sorted({c for c in text if not c.isspace() and ord(c) not in font.face.charToGlyph})
    assert not missing,missing
    for step in steps:assert step['title'] in pdf[4+steps.index(step)].get_text().replace('\n',' '),step['title']
    checks['pdf_40_pages_40_bookmarks_all_captures_fonts_bounds']=True
    assert browser['htmlSha256']==sha(html_file)
    assert browser['result']=='passed' and browser['failed']==0 and browser['passed']==33
    assert not browser['consoleErrors'] and not browser['pageErrors'] and not browser['externalRequests']
    checks['current_html_actual_chromium_33_checks']=True
    assert visual['pdfSha256']==sha(pdf_file) and visual['result']=='passed'
    checks['current_pdf_all_40_pages_visually_reviewed']=True
    preservation={'scope':'not_requested'}
    if args.product_source or args.preservation_manifest:
        assert args.product_source and args.preservation_manifest
        old=json.loads(args.preservation_manifest.read_text())
        changed=[name for name,digest in old.items() if sha(args.product_source/name)!=digest]
        assert not changed,changed
        preservation={'scope':'original Simple product and historical artifacts compared with pre-document hashes','checkedFiles':len(old),'changedFiles':[],'result':'passed'}
        checks['original_product_inputs_unchanged']=True
    artifacts={path.name:{'sha256':sha(path),'bytes':path.stat().st_size} for path in [html_file,pdf_file]}
    result={'result':'passed','scope':'New document-only verification. No extension build, Katana, Burp GUI, native MV3, or target-site scan. Historical product metrics remain separate.','documentRevision':'2026-10-29','steps':33,'callouts':95,'pdfPages':40,'pdfBookmarks':40,'checks':checks,'allDetailZoomScaleAtLeast':min(x['zoomScaleRelativeToMain'] for x in geometry),'pdfOutsideText':outside,'pdfMissingGlyphs':missing,'pdfFonts':sorted(font_names),'browserChecks':browser['passed'],'browserScope':browser['scope'],'fileProtocol':browser['fileProtocol'],'pdfVisualReview':'all 40 pages; separate detailed review in pdf-review.json','preservation':preservation,'artifacts':artifacts}
    (HERE/'validation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False,indent=2))


if __name__=='__main__':
    main()
