#!/usr/bin/env python3
"""Render actual Swing components with synthetic data; no Burp GUI or network.

Requires a prior `python dev.py test` to build the release JAR and test adapters.
The default classpath uses that JAR, including its bundled font. --jar allows
comparison against an immutable release while current source is being edited.
"""
from __future__ import annotations
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
    p.add_argument('--sizes',nargs='+',default=['1024x720','1280x800','1440x920'])
    p.add_argument('--themes',nargs='+',choices=['light','dark'],default=['light','dark'])
    args=p.parse_args()
    version=re.search(r'dist/burp-workbench-kr-([0-9.]+)\.jar',(WB/'build_all.py').read_text(encoding='utf-8'))
    if not version:raise SystemExit('Cannot locate the current build_all.py release version.')
    jar=(args.jar if args.jar else WB/f'dist/burp-workbench-kr-{version.group(1)}.jar').resolve()
    if not jar.is_file():raise SystemExit(f'Missing release JAR: {jar}. Run python dev.py test first.')
    jar_hash=hashlib.sha256(jar.read_bytes()).hexdigest()
    source_name=jar.relative_to(ROOT).as_posix() if jar.is_relative_to(ROOT) else str(jar)
    classes=args.out/'classes';classes.mkdir(parents=True,exist_ok=True)
    cp=os.pathsep.join(map(str,[jar,*(WB/'deps'/n for n in ['montoya-2026.jar','legacy-api.jar','rhino.jar']),WB/'build/tests']))
    subprocess.run([args.java,'com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(ROOT/'tools/UiLayoutPreview.java')],check=True)
    all_rows=[]
    content_rows=[]
    for theme in args.themes:
        for size in args.sizes:
            w,h=size.split('x');out=args.out/theme/size
            subprocess.run([args.java,'-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-cp',cp+os.pathsep+str(classes),'kr.workbench.UiLayoutPreview',str(out),str(WB/'src/main/resources/fonts/NanumGothic-Regular.ttf'),theme,w,h],check=True)
            with (out/'controls.csv').open() as source:
                all_rows.extend(csv.DictReader(source))
            with (out/'content.csv').open() as source:
                content_rows.extend(csv.DictReader(source))
    with (args.out/'controls.csv').open('w') as target:
        writer=csv.DictWriter(target,fieldnames=all_rows[0].keys());writer.writeheader();writer.writerows(all_rows)
    with (args.out/'content.csv').open('w') as target:
        writer=csv.DictWriter(target,fieldnames=content_rows[0].keys());writer.writeheader();writer.writerows(content_rows)
    clipped=[r for r in all_rows if r['fullyVisible']=='false' and r['scrollable']=='false']
    print(f'{len(all_rows)} controls measured; {len(clipped)} clipped outside scrollable containers.')
    # Match stable preview-only names, then require real visible content rather than
    # merely asserting that a Swing component exists or has a non-zero outer size.
    required={
        ('tool-03.png','preview.js.source'):80,
        ('js-horizontal.png','preview.js.source'):80,
        ('bounty-1.png','preview.workflow.input'):80,
        ('bounty-1.png','preview.workflow.detail'):80,
        ('bounty-3.png','preview.evidence.impact'):70,
        ('bounty-3.png','preview.evidence.repro'):70,
    }
    gate_failures=[]
    for theme in args.themes:
        for size in args.sizes:
            w,h=size.split('x')
            for (view,name),minimum in required.items():
                matches=[r for r in content_rows if (r['theme'],r['width'],r['height'],r['view'],r['name'])==(theme,w,h,view,name)]
                if len(matches)!=1 or int(matches[0]['visibleHeight'])<minimum or int(matches[0]['visibleWidth'])<160:
                    gate_failures.append({'theme':theme,'size':size,'view':view,'name':name,'minimumHeight':minimum,'actual':matches})
    small=[r for r in all_rows if int(r['controlHeight'])<24 or int(r['controlWidth'])<24]
    report={
        'method':'Production Swing components with synthetic metadata and test API adapters; EDT layout queue drained before painting. No native Burp or browser execution.',
        'source':source_name,
        'releaseJarSha256':jar_hash,
        'themes':args.themes,'sizes':args.sizes,
        'renderedViews':len(args.themes)*len(args.sizes)*16,
        'controlsMeasured':len(all_rows),'clippedControlsOutsideScrollers':len(clipped),
        'smallControls':len(small),'collapsedRequiredWorkAreas':len(gate_failures),
        'minimumWorkAreas':{view+':'+name:minimum for (view,name),minimum in required.items()},
        'failures':gate_failures,
    }
    if hashlib.sha256(jar.read_bytes()).hexdigest()!=jar_hash:raise SystemExit('The JAR changed during rendering. Re-run after the build finishes.')
    (args.out/'layout-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'{len(gate_failures)} collapsed key work areas; {len(small)} controls below 24px.')
    for r in clipped[:35]:print(f"{r['theme']} {r['width']}x{r['height']} {r['view']}: {r['label']} [{r['x']},{r['y']},{r['controlWidth']},{r['controlHeight']}]")

    if args.check and (clipped or small or gate_failures):raise SystemExit('Layout gate failed; inspect layout-report.json and PNGs.')

if __name__=='__main__':main()
