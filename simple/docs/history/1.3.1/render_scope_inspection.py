#!/usr/bin/env python3
"""Render actual scoped-inspection Swing components with disclosed synthetic evidence.

No target HTTP, native Burp GUI, or live browser DOM is executed by this renderer.
The complete integration/loopback and browser tests are separate commands.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import runpy
import subprocess

ROOT = Path(__file__).resolve().parents[1]
WB = ROOT / 'burp-workbench'
CAPTURES = [
    '12-inspection-setup.png', '13-inspection-progress.png',
    '14-inspection-environment.png', '15-inspection-paths.png',
    '16-inspection-api.png', '17-inspection-secrets.png',
    '18-inspection-access.png', '19-inspection-changes.png',
    '20-inspection-coverage.png', '21-inspection-browser.png',
    '22-inspection-original.png',
    '23-katana-settings.png', '24-katana-results.png',
    '25-inspection-report.png',
]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, help='Default: docs/images/<current version>; preserves previous versions')
    parser.add_argument('--java', default='java')
    parser.add_argument('--themes', nargs='+', choices=['light', 'dark'], default=['light', 'dark'])
    parser.add_argument('--sizes', nargs='+', default=['1024x720', '1280x800'])
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--source-target', action='store_true', help='Explicit source/target 17 mode; Java 17 platform APIs are not verified')
    args = parser.parse_args()
    version = runpy.run_path(str(WB / 'release_info.py'))['VERSION']
    args.out = (args.out or ROOT / 'docs/images' / version).resolve()
    jar = WB / f'dist/burp-workbench-simple-{version}.jar'
    if not jar.is_file() or not (WB / 'build/tests').exists():
        parser.error('Build the current Simple JAR and test adapters first.')
    args.out.mkdir(parents=True, exist_ok=True)
    classes = args.out / '.classes'
    classes.mkdir(exist_ok=True)
    cp = os.pathsep.join(map(str, [jar, *sorted((WB / 'deps').glob('*.jar')), WB / 'build/tests']))
    compiler = ['-source', '17', '-target', '17'] if args.source_target else ['--release', '17']
    if args.source_target:
        print('WARNING: Explicit source/target 17 mode does not validate Java 17 platform APIs.', flush=True)
    subprocess.run([args.java, 'com.sun.tools.javac.Main', *compiler, '-encoding', 'UTF-8',
                    '-cp', cp, '-d', str(classes), str(ROOT / 'tools/SimplePreview.java'),
                    str(ROOT / 'tools/ScopeInspectionPreview.java')], check=True)
    controls, content, scenarios, missing = [], [], [], []
    views = 0
    for theme in args.themes:
        for size in args.sizes:
            try:
                width, height = map(int, size.split('x'))
            except ValueError:
                parser.error(f'Invalid size: {size}')
            output = args.out / theme / size
            output.mkdir(parents=True, exist_ok=True)
            home, cache = output / '.host-home', output / '.font-cache'
            home.mkdir(exist_ok=True); cache.mkdir(exist_ok=True)
            env = dict(os.environ, XDG_CACHE_HOME=str(cache))
            subprocess.run([args.java, f'-Duser.home={home}', '-Djava.awt.headless=true',
                            '-Dfile.encoding=UTF-8', '-cp', cp + os.pathsep + str(classes),
                            'kr.workbench.ScopeInspectionPreview', str(output),
                            str(WB / 'src/main/resources/fonts/NanumGothic-Regular.ttf'),
                            theme, str(width), str(height)], check=True, timeout=120, env=env)
            for capture in CAPTURES:
                if (output / capture).is_file():
                    views += 1
                else:
                    missing.append({'theme': theme, 'size': size, 'view': capture})
            for name, rows in [('controls', controls), ('content', content), ('scenarios', scenarios)]:
                with (output / f'scope-{name}.csv').open(encoding='utf-8') as source:
                    rows.extend(csv.DictReader(source))
    clipped = [r for r in controls if r['fullyVisible'] != 'true' and r['scrollable'] != 'true']
    failed = [r for r in scenarios if r['passed'] != 'true']
    collapsed = []
    required = {
        ('15-inspection-paths.png', 'wb.scope.inspection.paths'): 100,
        ('17-inspection-secrets.png', 'wb.scope.inspection.secrets'): 100,
        ('18-inspection-access.png', 'wb.scope.inspection.access'): 100,
        ('19-inspection-changes.png', 'wb.scope.inspection.changes'): 100,
        ('20-inspection-coverage.png', 'wb.scope.inspection.coverage'): 100,
        ('21-inspection-browser.png', 'wb.scope.inspection.browser'): 80,
        ('22-inspection-original.png', 'wb.scope.inspection.original.request.text'): 100,
        ('22-inspection-original.png', 'wb.scope.inspection.original.response.text'): 100,
        ('24-katana-results.png', 'wb.scope.inspection.katana'): 100,
    }
    for (view, name), minimum in required.items():
        found = [r for r in content if r['view'] == view and r['name'] == name]
        if not found:
            collapsed.append({'view': view, 'name': name, 'reason': 'missing'})
        else:
            collapsed.extend(r for r in found if int(r['visibleHeight']) < minimum or int(r['visibleWidth']) < 200)
    for name, rows in [('controls', controls), ('content', content), ('scenarios', scenarios)]:
        if rows:
            with (args.out / f'scope-{name}.csv').open('w', encoding='utf-8', newline='') as target:
                writer = csv.DictWriter(target, fieldnames=rows[0].keys())
                writer.writeheader(); writer.writerows(rows)
    result = {
        'version': version,
        'artifact': jar.relative_to(ROOT).as_posix(),
        'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
        'toolchainMode': 'explicit-source17-target17-api-unverified' if args.source_target else 'strict-release17',
        'java17ApiCompatibilityVerified': not args.source_target,
        'renderedViews': views, 'controls': len(controls), 'scenarios': len(scenarios),
        'captureFiles': CAPTURES,
        'clippedControls': clipped, 'collapsedAreas': collapsed, 'failedScenarios': failed, 'missingCaptures': missing,
        'scope': 'Production Scope inspection components with synthetic wire packets and browser observations; zero target HTTP. Not native Burp GUI, live browser DOM or a live target assessment.',
        'result': 'passed' if not clipped and not collapsed and not failed and not missing else 'failed',
    }
    (args.out / 'scope-layout-report.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k not in ['clippedControls', 'collapsedAreas', 'captureFiles']}, ensure_ascii=False))
    print(f'Clipped inspection controls: {len(clipped)}; collapsed required areas: {len(collapsed)}')
    return 1 if args.check and result['result'] != 'passed' else 0


if __name__ == '__main__':
    raise SystemExit(main())
