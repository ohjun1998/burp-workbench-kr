#!/usr/bin/env python3
"""Export original-resolution approved/Robot images and their verification evidence."""
from pathlib import Path
import argparse
import csv
import hashlib
import html
import json
import re
import shutil
import zipfile
import fitz
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
VIEWS = {
    'traffic': ('요청·응답 목록', '02-request.png'),
    'mapper': ('기능 자동 매핑', '08-mapper.png'),
    'js': ('JS에서 API 찾기', '10-js.png'),
    'response.recon': ('응답 보안 분석', '03-analysis.png'),
    'auth': ('계정 권한 비교', '04-auth-results.png'),
    'auth.accounts': ('인증 후보 연결', '04-auth-connections.png'),
    'payload': ('입력값 검사 · 준비', '07-payload.png'),
    'payload.results': ('입력값 검사 · 결과', '07-payload-results.png'),
    'environment': ('서버·경로 탐색', '09-environment.png'),
    'exposure.search': ('외부 노출 조사', '12-exposure-search.png'),
    'exposure.cve': ('CVE 후보', '13-exposure-cve.png'),
    'activity': ('점검 진행 현황', '15-progress.png'),
    'bounty': ('증거 정리', '05-evidence.png'),
    'bounty.replay': ('업무 흐름 재현', '11-replay.png'),
    'dashboard': ('점검 대시보드', '16-dashboard.png'),
    'data': ('데이터 변환·비교', '14-data.png'),
}

# Order follows the panel elements measured from the approved prototype.
FRAMES = {
    'traffic': ['wb.traffic.request^', 'wb.traffic.response^'],
    'mapper': ['fm.navigation.split:left', 'wb.mapper.request^', 'wb.mapper.response^'],
    'js': ['wb.pdf.js.call.source'], 'response.recon': ['wb.card.pdf.response.reader'],
    'auth': ['wb.card.Record History', 'wb.auth.inline', 'wb.card.기준 응답', 'wb.card.계정 응답'],
    'auth.accounts': ['wb.auth.connections'],
    'payload': ['text=검사 요청^^', 'wb.flow.payload.conditions'],
    'payload.results': ['wb.payload.workspace:top', 'wb.card.실제 요청', 'wb.card.실제 응답'],
    'environment': ['wb.environment.candidates', 'wb.flow.environment.inspector'],
    'exposure.search': ['wb.flow.exposure.search.masterdetail:left', 'wb.card.선택 URL 근거'],
    'exposure.cve': ['wb.flow.exposure.cve.masterdetail:left', 'wb.flow.exposure.cve.masterdetail:right'],
    'activity': ['wb.card.URL 점검 목록', 'wb.flow.activity.inspector'],
    'bounty': ['wb.bounty.evidence.list', 'wb.bounty.evidence.report', 'wb.bounty.evidence.source.card'],
    'bounty.replay': ['wb.card.요청 준비', 'wb.card.실행 결과'],
    'dashboard': ['wb.card.해당 결과 목록', 'wb.card.선택 항목 근거'],
    'data': ['wb.card.입력', 'wb.card.출력'],
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def jar_version(path):
    with zipfile.ZipFile(path) as jar:
        manifest = jar.read('META-INF/MANIFEST.MF').decode('utf-8')
    headers = []
    for line in manifest.splitlines():
        if not line:
            break  # Named sections do not describe the application version.
        if line.startswith(' ') and headers:
            headers[-1] += line[1:]
        else:
            headers.append(line)
    attributes = {name.casefold(): value.strip() for header in headers
                  for name, separator, value in [header.partition(':')] if separator}
    version = attributes.get('implementation-version', '')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]*', version):
        raise ValueError('JAR manifest requires a filename-safe Implementation-Version')
    return version


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def frame(data, selector):
    index = {r['id']: r for r in data}
    parents = len(selector) - len(selector.rstrip('^'))
    selector = selector.rstrip('^')
    side = None
    if ':' in selector and not selector.startswith('text='):
        selector, side = selector.rsplit(':', 1)
    matches = [r for r in data if (r['text'] == selector[5:] if selector.startswith('text=') else r['name'] == selector)]
    if len(matches) != 1:
        raise ValueError(f'Ambiguous measured frame: {selector}: {len(matches)}')
    selected = matches[0]
    for _ in range(parents):
        selected = index[selected['parent']]
    if side:
        children = [r for r in data if r['parent'] == selected['id'] and r['layout']
                    and r['rect'][2] > 80 and r['rect'][3] > 80]
        axis = 0 if side in ('left', 'right') else 1
        selected = sorted(children, key=lambda r: r['rect'][axis])[0 if side in ('left', 'top') else -1]
    return selected['rect']


def geometry(captures, reference_file, out):
    reference = json.loads(reference_file.read_text())
    rows = []
    for key, selectors in FRAMES.items():
        panels = [r for r in reference[key] if r['tag'] != 'HEADER' and 'panel' in r['cls'].split()]
        if len(panels) != len(selectors):
            raise ValueError(f'Reference frame inventory changed: {key}')
        data = json.loads((captures / 'light' / '1600x1000' / (VIEWS[key][1] + '.json')).read_text())
        for panel, selector in zip(panels, selectors):
            actual = frame(data, selector)
            differences = [round(a-b, 2) for a, b in zip(actual, panel['rect'])]
            rows.append({'view': key, 'anchor': selector, 'reference': panel['rect'],
                         'actual': actual, 'deltaXYWH': differences})
    write(out / 'frame-measurements.json', {
        'reference': 'Approved prototype DOM at1600x1000; the approved PNGs are supplied alongside actual screenshots.',
        'scope': 'Principal cards and readers, rather than an exhaustive pixel comparison.',
        'frames': rows, 'maximumAbsoluteCoordinateDifference': max(abs(d) for r in rows for d in r['deltaXYWH'])})


def audit(captures, jar):
    evidence = []
    for theme in ('light', 'dark'):
        for size in ('1600x1000', '1280x800', '1024x768'):
            folder = captures / theme / size
            controls = list(csv.DictReader((folder / 'controls.csv').open()))
            scenarios = list(csv.DictReader((folder / 'scenarios.csv').open()))
            clipped = [r for r in controls if r['fullyVisible'] != 'true' and r['scrollable'] != 'true']
            failed = [r for r in scenarios if r['passed'] != 'true']
            expected = tuple(map(int, size.split('x')))
            for _, name in VIEWS.values():
                with Image.open(folder / name) as picture:
                    if picture.size != expected:
                        raise ValueError(f'Unexpected image dimensions: {folder / name}')
                    picture.verify()
            if clipped or failed:
                raise ValueError(f'Failed capture validation {theme}/{size}: {clipped}, {failed}')
            log = (folder / 'execution.log').read_text()
            if 'zero HTTP' not in log or 'Exception' in log or 'AssertionError' in log:
                raise ValueError(f'Failed native execution: {theme}/{size}')
            evidence.append({'theme': theme, 'size': size, 'screens': len(list(folder.glob('*.png'))),
                             'controls': len(controls), 'scenarios': len(scenarios),
                             'clippedControlsOutsideScroll': 0, 'failedScenarios': 0})
    actual = (captures / 'artifact.sha256').read_text().split()[0]
    if actual != sha(jar):
        raise ValueError('Final native captures and delivered JAR do not have the same hash')
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--captures', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--reference-geometry', type=Path)
    args = parser.parse_args()
    version = jar_version(args.jar)
    out = args.out
    out.mkdir(parents=True, exist_ok=True)
    evidence = audit(args.captures, args.jar)
    approved = ROOT / 'docs/reports/2026-10-31-workflow-design/images'
    image_manifest = []
    for theme in ('light', 'dark'):
        for key, (title, filename) in VIEWS.items():
            source = args.captures / theme / '1600x1000' / filename
            destination = out / 'images' / theme / f'{key}.png'
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
            image_manifest.append({'view': key, 'title': title, 'theme': theme,
                                   'actual': str(destination.relative_to(out)), 'actualSha256': sha(destination),
                                   'approvedSha256': sha(approved / theme / f'{key}.png')})
        appendix = out / 'appendix' / theme
        appendix.mkdir(parents=True, exist_ok=True)
        for suffix in ('overview', 'accounts', 'policy'):
            filename = f'04-auth-accounts-full-{suffix}.png'
            shutil.copyfile(args.captures / theme / '1600x1000' / filename, appendix / filename)
    for result in evidence:
        folder = args.captures / result['theme'] / result['size']
        target = out / 'measurements' / result['theme'] / result['size']
        target.mkdir(parents=True, exist_ok=True)
        for name in ('controls.csv', 'anchors.csv', 'content.csv', 'scenarios.csv', 'execution.log'):
            shutil.copyfile(folder / name, target / name)
    write(out / 'native-verification.json', {'version': version, 'jarSha256': sha(args.jar),
          'runtime': 'Linux Xorg dummy / xfwm4 / Java17 / Robot screen capture',
          'fixture': 'Production Swing with synthetic Burp adapters; zero target HTTP',
          'sets': evidence, 'result': 'passed',
          'limits': 'No claim of native Windows Burp, external provider verification or zero pixel differences.'})
    write(out / 'images-manifest.json', image_manifest)
    if args.reference_geometry:
        geometry(args.captures, args.reference_geometry, out)
    document = fitz.open()
    font = ROOT / 'simple/burp-workbench/src/main/resources/fonts/NanumGothic-Regular.ttf'
    for theme in ('light', 'dark'):
        for key, (title, _) in VIEWS.items():
            page = document.new_page(width=1680, height=650)
            page.insert_font(fontname='KR', fontfile=str(font))
            page.insert_text((24, 30), f'Workbench Simple {version} · {title} · {theme}', fontname='KR', fontsize=18)
            page.insert_text((24, 64), '승인 시안', fontname='KR', fontsize=13)
            page.insert_text((856, 64), '실제 실행 창 · Robot 캡처', fontname='KR', fontsize=13)
            page.insert_image(fitz.Rect(24, 82, 824, 582), filename=str(approved / theme / f'{key}.png'))
            page.insert_image(fitz.Rect(856, 82, 1656, 582), filename=str(out / 'images' / theme / f'{key}.png'))
            page.insert_text((24, 615), '합성 데이터·건수·선택 상태는 다릅니다. Java/브라우저 렌더링까지 픽셀 차이 0을 검증한 자료가 아닙니다.', fontname='KR', fontsize=11)
    pdf = out / f'workbench-simple-{version}-approved-actual.pdf'
    document.save(pdf, garbage=4, deflate=True)
    document.close()
    cards = []
    for theme in ('light', 'dark'):
        for key, (title, _) in VIEWS.items():
            # The report is one version directory below its date/task report.
            ref = f'../../2026-10-31-workflow-design/images/{theme}/{key}.png'
            actual = f'images/{theme}/{key}.png'
            cards.append(f'<section data-theme="{theme}" id="{key}-{theme}"><h2>{html.escape(title)} · {theme}</h2>'
                         f'<div class="pair"><figure><figcaption>승인 시안</figcaption><a href="{ref}"><img loading="lazy" src="{ref}"></a></figure>'
                         f'<figure><figcaption>실제 실행 창</figcaption><a href="{actual}"><img loading="lazy" src="{actual}"></a></figure></div></section>')
    (out / 'COMPARE.html').write_text(f'''<!doctype html><html lang="ko"><meta charset="utf-8"><title>Simple{html.escape(version)} 승인 시안·실제 창</title>
''' + '''
<style>body{font:14px system-ui;margin:24px;background:#f4f5f7;color:#202631}nav{position:sticky;top:0;padding:12px;background:#fff;border:1px solid #ddd}button{margin-right:8px;padding:8px 14px}.pair{display:grid;grid-template-columns:1fr 1fr;gap:16px}figure{margin:0}figcaption{margin:8px 0}img{width:100%;border:1px solid #dbe2ec}section{margin:36px 0}h1{font-size:23px}@media(max-width:900px){.pair{grid-template-columns:1fr}}</style>
''' + f'''<h1>Workbench Simple{html.escape(version)} · 승인 시안과 실제 창</h1><p>이미지를 누르면 원본1600×1000을 엽니다. 실제 Swing 창은 합성 Burp 어댑터로 실행하고 Robot으로 캡처했습니다. 실제 데이터·건수와 Java 글자 렌더링은 시안과 다릅니다.</p>
<nav><button onclick="show('light')">라이트</button><button onclick="show('dark')">다크</button><a href="{pdf.name}">전체 비교 PDF</a></nav>
''' + ''.join(cards) + '''<script>function show(theme){document.querySelectorAll('section').forEach(s=>s.hidden=s.dataset.theme!==theme)}show('light')</script></html>''')
    print(json.dumps({'result': 'passed', 'version': version, 'nativeSets': len(evidence), 'actualImages': len(image_manifest), 'pdfPages': 32, 'pdfSha256': sha(pdf)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
