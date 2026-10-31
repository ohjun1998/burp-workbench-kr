#!/usr/bin/env python3
"""Package approved workflow Guide16 from measured production Swing captures.

Default mode stages output under /tmp. --apply is intentionally explicit and is
used only after final capture approval. No build or screenshot renderer runs.
Every marker must hit a named, fully visible component in BOTH source themes.
"""
from pathlib import Path
import argparse
import csv
import hashlib
import json
import math
import shutil
from PIL import Image

SOURCES={
 'traffic':'02-request.png','mapper':'08-mapper.png','js':'10-js.png',
 'environment':'09-environment.png','exposure.search':'12-exposure-search.png',
 'exposure.cve':'13-exposure-cve.png','auth':'04-auth-results.png',
 'response.recon':'03-analysis.png','payload':'07-payload.png','data':'14-data.png',
 'activity':'15-progress.png','bounty':'05-evidence.png','dashboard':'16-dashboard.png',
 'payload.results':'07-payload-results.png','bounty.replay':'11-replay.png',
 'auth.accounts':'04-auth-accounts.png'}
THEMES=('light','dark')
BADGE_EDGE=12
MIN_MARKER_DISTANCE=46


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    return json.loads(path.read_text())


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n')


def theme_directory(source, theme):
    candidates=[source/theme/'1600x1000',source/(theme+'-1600'),
                source/(theme+'-1600x1000'),source/theme]
    existing=[p for p in candidates if (p/'anchors.csv').is_file()]
    require(bool(existing),f'Final {theme} captures/anchors.csv missing under {source}')
    return existing[0]


def measured_rectangle(tables, theme, view, anchor, expected_size):
    rows=[r for r in tables[theme] if r['view']==view and r['name']==anchor]
    require(len(rows)==1,f'{theme}/{view}: expected one anchor {anchor}, found {len(rows)}')
    row=rows[0]
    require(row['fullyVisible'].lower()=='true',f'{theme}/{view}: anchor is not fully visible: {anchor}')
    require((int(row['width']),int(row['height']))==expected_size,
            f'{theme}/{view}: anchor/image dimensions disagree for {anchor}')
    rectangle=tuple(float(row[k]) for k in ('x','y','anchorWidth','anchorHeight'))
    require(rectangle[2]>0 and rectangle[3]>0,f'Empty measured anchor: {anchor}')
    return rectangle


def choose_marker(key, recipe, rectangles, image_size, previous):
    width,height=image_size
    dx,dy,dw,dh=rectangles['dark']
    lx,ly,lw,lh=rectangles['light']
    left=max(dx,lx); top=max(dy,ly); right=min(dx+dw,lx+lw); bottom=min(dy+dh,ly+lh)
    require(right>left and bottom>top,f'{key}/{recipe["number"]}: light/dark anchor bounds do not intersect')
    fx,fy=recipe.get('fraction',(.5,.5))
    preferred=(dx+dw*fx,dy+(16 if recipe.get('firstRow') else dh*fy))

    def valid(point):
        x,y=point
        return (left<=x<=right and top<=y<=bottom and BADGE_EDGE<=x<=width-BADGE_EDGE
                and BADGE_EDGE<=y<=height-BADGE_EDGE
                and all(math.hypot(x-px,y-py)>MIN_MARKER_DISTANCE for px,py in previous))

    if valid(preferred):
        return preferred
    # Include the intersection center and off-center positions so nearby row
    # badges remain readable without pointing to a hidden or adjacent control.
    candidates=[(left+(right-left)*x,top+(bottom-top)*y)
                for x in (.5,.25,.75,.1,.9) for y in (.5,.2,.8)]
    candidates=[p for p in candidates if valid(p)]
    require(bool(candidates),f'{key}/{recipe["number"]}: no non-overlapping position inside visible anchor {recipe["anchor"]}')
    return min(candidates,key=lambda p:math.hypot(p[0]-preferred[0],p[1]-preferred[1]))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo',type=Path,default=Path('/workspace/burp-workbench-kr'))
    parser.add_argument('--source',type=Path,default=Path('tmp/pdf-alignment/guide-layout'))
    parser.add_argument('--recipes',type=Path,default=Path('docs/reports/2026-10-31-pdf-alignment/1.4.17/guide-recipes.json'))
    parser.add_argument('--stage',type=Path,default=Path('tmp/pdf-alignment/guide-staging'))
    parser.add_argument('--example-version',default='1.4.17')
    parser.add_argument('--apply',action='store_true',help='After complete validation, replace the packaged catalog and32 source screenshots')
    args=parser.parse_args()
    source=args.source if args.source.is_absolute() else args.repo/args.source
    resources=args.repo/'simple/burp-workbench/src/main/resources/guides'
    catalog=read_json(resources/'catalog.json')
    recipes=read_json(args.recipes)
    require(set(recipes)==set(SOURCES),'Recipes must cover exactly16 guide screens')
    require({g['id'] for g in catalog['guides']}==set(SOURCES),'Catalog screen IDs changed')
    directories={theme:theme_directory(source,theme) for theme in THEMES}
    tables={theme:list(csv.DictReader((directories[theme]/'anchors.csv').open())) for theme in THEMES}
    manifest=[];proof=[];mapping=[];image_sources=[]
    for guide in catalog['guides']:
        key=guide['id'];view=SOURCES[key]
        require(len(guide['steps'])==len(recipes[key])==6,f'{key}: expected six usage steps')
        image_size=(1000,760) if key=='auth.accounts' else (1600,1000)
        hashes={}
        for theme in THEMES:
            original=directories[theme]/view
            require(original.is_file(),f'Missing screenshot: {original}')
            with Image.open(original) as image:
                require(image.size==image_size,f'{theme}/{key}: expected {image_size}, got {image.size}')
                image.verify()
            hashes[theme]=hashlib.sha256(original.read_bytes()).hexdigest()
            relative=Path('screens')/theme/guide['image']
            image_sources.append((original,relative))
            manifest.append({'guide':key,'theme':theme,'source':str(original),'image':str(relative),
                             'width':image_size[0],'height':image_size[1],'sha256':hashes[theme]})
        require(hashes['light']!=hashes['dark'],f'{key}: light/dark screenshots unexpectedly identical')
        previous=[]
        for step,recipe in zip(guide['steps'],recipes[key]):
            number=step['number']
            require(number==recipe['number'],f'{key}: usage step order changed')
            require(isinstance(recipe.get('anchor'),str) and recipe['anchor'],f'{key}/{number}: named visible anchor required')
            rectangles={theme:measured_rectangle(tables,theme,view,recipe['anchor'],image_size) for theme in THEMES}
            px,py=choose_marker(key,recipe,rectangles,image_size,previous)
            step.update(x=round(px/image_size[0],6),y=round(py/image_size[1],6),
                        title=recipe['title'],description=recipe['description'])
            # Check the rounded packaged coordinate, rather than only the
            # higher precision pre-serialization point.
            rx=step['x']*image_size[0];ry=step['y']*image_size[1]
            require(all(math.hypot(rx-x,ry-y)>40 for x,y in previous),f'{key}/{number}: packaged badges overlap')
            previous.append((rx,ry))
            for theme in THEMES:
                x,y,w,h=rectangles[theme]
                require(x<=rx<=x+w and y<=ry<=y+h,f'{theme}/{key}/{number}: rounded marker leaves visible anchor')
                proof.append({'guide':key,'step':number,'theme':theme,'anchor':recipe['anchor'],
                              'x':step['x'],'y':step['y'],'pixelCenter':[rx,ry],
                              'visibleRectangle':[x,y,w,h],'centerWithinVisibleAnchor':True})
            mapping.append({'guide':key,'step':number,'anchor':recipe['anchor'],'title':step['title']})
    require(len(manifest)==32 and len(proof)==192 and len(mapping)==96,'Guide16 invariant failed')
    catalog['exampleVersion']=args.example_version
    catalog['exampleData']='synthetic'
    verification={'guideScreens':16,'sourceImages':32,'steps':96,'themeChecks':192,'result':'passed',
                  'minimumSourceBadgeDistance':MIN_MARKER_DISTANCE,'source':str(source),'checks':proof,
                  'scope':'Every numbered marker is centered inside a named fully visible production Swing component in both synthetic themes; hidden source buttons and collapsed children are excluded.'}
    # Stage the entire validated package before any production resources change.
    args.stage.mkdir(parents=True,exist_ok=True)
    write_json(args.stage/'catalog.json',catalog)
    for original,relative in image_sources:
        destination=args.stage/relative;destination.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(original,destination)
    write_json(Path('/tmp/pdf-alignment-guide-assets.json'),manifest)
    write_json(Path('/tmp/pdf-alignment-guide-anchor-verification.json'),verification)
    write_json(Path('/tmp/pdf-alignment-guide-anchor-map.json'),mapping)
    if args.apply:
        for _,relative in image_sources:
            shutil.copyfile(args.stage/relative,resources/relative)
        shutil.copyfile(args.stage/'catalog.json',resources/'catalog.json')
    print(json.dumps({'mode':'applied' if args.apply else 'staged','guideScreens':16,'sourceImages':32,'steps':96,
                      'themeChecks':192,'stage':str(args.stage),'resources':str(resources) if args.apply else None,
                      'verification':'/tmp/pdf-alignment-guide-anchor-verification.json'},ensure_ascii=False))


if __name__=='__main__':
    main()
