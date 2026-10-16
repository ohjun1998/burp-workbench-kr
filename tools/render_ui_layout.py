#!/usr/bin/env python3
"""Render actual Swing components with synthetic data; no Burp GUI or network.

Requires a prior `python dev.py test` to build the release JAR and test adapters.
The default classpath uses that JAR, including its bundled font. --jar allows
comparison against an immutable release while current source is being edited.
"""
from __future__ import annotations
import runpy
import argparse
import csv
import json
import hashlib
import os
import re
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[1]
WB=ROOT/'project/burp-development-workspace/burp-workbench-kr/burp-workbench'

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar',type=Path,help='Release JAR override; otherwise use the current build_all.py dist JAR')
    p.add_argument('--out',type=Path,default=ROOT/'tmp/ui-layout')
    p.add_argument('--java',default='java')
    p.add_argument('--check',action='store_true',help='Fail if visible buttons clip or key work areas collapse')
    p.add_argument('--sizes',nargs='+',help='Default: 1024x720 1280x800 1440x920; guide-only: 760x480 560x300')
    p.add_argument('--guide-only',action='store_true',help='Check the production feature-guide dialog wrapper separately')
    p.add_argument('--scope-only',action='store_true',help='Check the production Scope editor at default and minimum dialog sizes')
    p.add_argument('--packet-only',action='store_true',help='Render Pretty/search, Raw and Hex in the production packet panes')
    p.add_argument('--js-only',action='store_true',help='Render JS/text analysis workspace and its modeless list/packet windows')
    p.add_argument('--themes',nargs='+',choices=['light','dark'],default=['light','dark'])
    args=p.parse_args()
    if sum([args.guide_only,args.scope_only,args.packet_only,args.js_only])>1:p.error('Choose one preview mode')
    if args.sizes is None:args.sizes=['760x480','560x300'] if args.guide_only or args.scope_only else ['1024x720','1280x800','1440x920']
    version=runpy.run_path(str(WB/'release_info.py'))['VERSION']
    jar=(args.jar if args.jar else WB/f'dist/burp-workbench-kr-{version}.jar').resolve()
    if not jar.is_file():raise SystemExit(f'Missing release JAR: {jar}. Run python dev.py test first.')
    jar_hash=hashlib.sha256(jar.read_bytes()).hexdigest()
    source_name=jar.relative_to(ROOT).as_posix() if jar.is_relative_to(ROOT) else str(jar)
    classes=args.out/'classes';classes.mkdir(parents=True,exist_ok=True)
    cp=os.pathsep.join(map(str,[jar,*(WB/'deps'/n for n in ['montoya-2026.jar','legacy-api.jar','rhino.jar']),WB/'build/tests']))
    subprocess.run([args.java,'com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(ROOT/'tools/UiLayoutPreview.java')],check=True)
    all_rows=[]
    content_rows=[]
    text_rows=[]
    guide_rows=[]
    rendered_views=0
    for theme in args.themes:
        for size in args.sizes:
            w,h=size.split('x');out=args.out/theme/size
            subprocess.run([args.java,'-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-cp',cp+os.pathsep+str(classes),'kr.workbench.UiLayoutPreview',str(out),str(WB/'src/main/resources/fonts/NanumGothic-Regular.ttf'),theme,w,h]+(['guide'] if args.guide_only else ['scope'] if args.scope_only else ['packet'] if args.packet_only else ['js'] if args.js_only else []),check=True)
            with (out/'controls.csv').open() as source:
                all_rows.extend(csv.DictReader(source))
            with (out/'content.csv').open() as source:
                content_rows.extend(csv.DictReader(source))
            with (out/'text.csv').open() as source:
                current_text=list(csv.DictReader(source));text_rows.extend(current_text)
            rendered_views+=len(list(out.glob('guide-*.png' if args.guide_only else '*.png')))
            if args.guide_only:
                with (out/'guides.csv').open() as source:guide_rows.extend(csv.DictReader(source))
    for name,measurements in [('controls',all_rows),('content',content_rows),('text',text_rows),('guides',guide_rows)]:
        if measurements:
            with (args.out/f'{name}.csv').open('w') as target:
                writer=csv.DictWriter(target,fieldnames=measurements[0].keys());writer.writeheader();writer.writerows(measurements)
    clipped=[r for r in all_rows if r['fullyVisible']=='false' and r['scrollable']=='false']
    print(f'{len(all_rows)} controls measured; {len(clipped)} clipped outside scrollable containers.')
    # Match stable preview-only names, then require real visible content rather than
    # merely asserting that a Swing component exists or has a non-zero outer size.
    required={
        ('tool-00.png','wb.traffic.request.text'):100,
        ('tool-00.png','wb.traffic.response.text'):100,
        ('tool-03.png','preview.js.source'):80,
        ('js-horizontal.png','preview.js.source'):80,
        ('bounty-1.png','preview.workflow.input'):80,
        ('bounty-1.png','preview.workflow.detail'):80,
        ('bounty-3.png','preview.evidence.impact'):70,
        ('bounty-3.png','preview.evidence.repro'):70,
    }
    if args.guide_only:required={}
    if args.scope_only:required={('scope-list.png','wb.traffic.scope.table'):60}
    if args.packet_only:required={(view,'wb.traffic.'+side+'.text'):100 for view in ['packet-pretty-search.png','packet-raw.png','packet-hex.png'] for side in ['request','response']}
    if args.js_only:
        required={('js-workspace.png','wb.js.source'):80,('js-analysis-off.png','wb.js.source'):80,
                  ('js-packets.png','wb.js.packet.request.text'):80,('js-packets.png','wb.js.packet.response.text'):80,
                  ('js-packets-small.png','wb.js.packet.request.text'):50,('js-packets-small.png','wb.js.packet.response.text'):50,
                  ('js-analysis-list.png','wb.js.history.table'):60,('js-analysis-list-small.png','wb.js.history.table'):40,
                  ('js-repeater.png','wb.js.repeater.request.text'):160,('js-repeater.png','wb.js.repeater.response.text'):160,
                  ('js-repeater-small.png','wb.js.repeater.request.text'):100,('js-repeater-small.png','wb.js.repeater.response.text'):100}
    gate_failures=[]
    for theme in args.themes:
        for size in args.sizes:
            w,h=size.split('x')
            for (view,name),minimum in required.items():
                expected_w,expected_h=w,h
                copies=1
                if args.js_only and view in {'js-packets.png','js-analysis-list.png','js-packets-small.png','js-analysis-list-small.png'}:
                    expected_w,expected_h=('560','300') if 'small' in view else ('760','480')
                    copies=len(args.sizes)
                if args.js_only and view in {'js-repeater.png','js-repeater-small.png'}:
                    expected_w,expected_h=('780','440') if 'small' in view else ('1120','720')
                    copies=len(args.sizes)
                matches=[r for r in content_rows if (r['theme'],r['width'],r['height'],r['view'],r['name'])==(theme,expected_w,expected_h,view,name)]
                if len(matches)!=copies or any(int(r['visibleHeight'])<minimum or int(r['visibleWidth'])<160 for r in matches):
                    gate_failures.append({'theme':theme,'size':size,'view':view,'name':name,'minimumHeight':minimum,'actual':matches})
    small=[r for r in all_rows if int(r['controlHeight'])<24 or int(r['controlWidth'])<24]
    text_failures=[r for r in text_rows if r['selected']=='true' and (r['fullyVisible']=='false' or r['textFits']=='false')]
    guide_failures=[r for r in guide_rows if r['horizontalScroll']=='true' or int(r['contentWidth'])>int(r['viewportWidth']) or r['controlsVisible']=='false' or int(r['maxGlyphRight'])>int(r['textWidth']) or int(r['lastGlyphBottom'])>int(r['textHeight'])]
    report={
        'method':'Production Swing components with synthetic metadata and test API adapters; EDT layout queue drained before painting. No native Burp or browser execution.',
        'source':source_name,
        'releaseJarSha256':jar_hash,
        'themes':args.themes,'sizes':args.sizes,
        'renderedViews':rendered_views,
        'controlsMeasured':len(all_rows),'clippedControlsOutsideScrollers':len(clipped),
        'smallControls':len(small),'collapsedRequiredWorkAreas':len(gate_failures),
        'tabAndHeadingLabelsMeasured':len(text_rows),'clippedSelectedTabOrHeadingLabels':len(text_failures),
        'minimumWorkAreas':{view+':'+name:minimum for (view,name),minimum in required.items()},
        'failures':gate_failures,
        'textFailures':text_failures,
        'guideBodiesMeasured':len(guide_rows),'guideLayoutFailures':len(guide_failures),'guideFailures':guide_failures,
    }
    if hashlib.sha256(jar.read_bytes()).hexdigest()!=jar_hash:raise SystemExit('The JAR changed during rendering. Re-run after the build finishes.')
    (args.out/'layout-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'{len(gate_failures)} collapsed key work areas; {len(small)} controls below 24px.')
    print(f'{len(text_rows)} tab/header labels measured; {len(text_failures)} selected tabs or headings clipped.')
    if args.guide_only:print(f'{len(guide_rows)} guide bodies measured; {len(guide_failures)} wrap or control visibility failures.')
    for r in clipped[:35]:print(f"{r['theme']} {r['width']}x{r['height']} {r['view']}: {r['label']} [{r['x']},{r['y']},{r['controlWidth']},{r['controlHeight']}]")

    if args.check and (clipped or small or gate_failures or text_failures or guide_failures):raise SystemExit('Layout gate failed; inspect layout-report.json and PNGs.')

if __name__=='__main__':main()
