#!/usr/bin/env python3
"""Capture production Simple Swing UI using isolated synthetic packet adapters.

These PNGs render the real extension components. They are not native Burp GUI
screenshots and do not execute HTTP comparisons, scans or external services.
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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, help='Default: docs/images/<current version>; older capture sets remain unchanged')
    parser.add_argument('--java', default='java')
    parser.add_argument('--themes', nargs='+', choices=['light', 'dark'], default=['light', 'dark'])
    parser.add_argument('--sizes', nargs='+', default=['1024x720', '1280x800'])
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--source-target', action='store_true',
                        help='Explicit incomplete-JDK fallback: -source 17 -target 17; does not validate Java 17 platform APIs')
    args = parser.parse_args()
    if args.source_target:
        print('WARNING: Explicit --source-target compilation uses Java 21 source/target 17 here; Java 17 platform API compatibility is NOT verified. Strict --release 17 remains the default.', flush=True)
    version = runpy.run_path(str(WB / 'release_info.py'))['VERSION']
    jar = WB / f'dist/burp-workbench-simple-{version}.jar'
    if not jar.exists() or not (WB / 'build/tests').exists():
        parser.error('Build Simple and test adapters first: python simple/dev.py test')
    args.out = (args.out if args.out else ROOT / 'docs/images' / version).resolve()
    classes = args.out / '.classes'
    classes.mkdir(parents=True, exist_ok=True)
    classpath = os.pathsep.join(map(str, [jar, *sorted((WB / 'deps').glob('*.jar')), WB / 'build/tests']))
    compiler_options = ['-source', '17', '-target', '17'] if args.source_target else ['--release', '17']
    subprocess.run([args.java, 'com.sun.tools.javac.Main', *compiler_options, '-encoding', 'UTF-8',
                    '-cp', classpath, '-d', str(classes), str(ROOT / 'tools/SimplePreview.java')], check=True)
    all_controls, all_content, all_scenarios = [], [], []
    views = 0
    for theme in args.themes:
        for size in args.sizes:
            try:
                width, height = map(int, size.split('x'))
            except ValueError:
                parser.error(f'Invalid size: {size}')
            output = args.out / theme / size
            output.mkdir(parents=True, exist_ok=True)
            (output / '.host-home').mkdir(exist_ok=True)
            (output / '.font-cache').mkdir(exist_ok=True)
            preview_env = dict(os.environ, XDG_CACHE_HOME=str(output / '.font-cache'))
            subprocess.run([args.java, f'-Duser.home={output / ".host-home"}', '-Djava.awt.headless=true',
                            '-Dfile.encoding=UTF-8', '-cp', classpath + os.pathsep + str(classes),
                            'kr.workbench.SimplePreview', str(output),
                            str(WB / 'src/main/resources/fonts/NanumGothic-Regular.ttf'), theme, str(width), str(height)],
                           check=True, timeout=120, env=preview_env)
            views += len(list(output.glob('*.png')))
            for name, rows in [('controls', all_controls), ('content', all_content), ('scenarios', all_scenarios)]:
                with (output / f'{name}.csv').open(encoding='utf-8') as source:
                    rows.extend(csv.DictReader(source))
    clipped = [row for row in all_controls if row['fullyVisible'] != 'true' and row['scrollable'] != 'true']
    failed = [row for row in all_scenarios if row['passed'] != 'true']
    # Verify the principal reader and evidence writing areas retain usable height.
    required = {
        ('02-request.png', 'wb.traffic.request.text'): 65,
        ('02-request.png', 'wb.traffic.response.text'): 65,
        ('05-evidence.png', 'wb.simple.evidence.impact'): 55,
        ('05-evidence.png', 'wb.simple.evidence.repro'): 55,
        ('06-redaction.png', 'wb.simple.evidence.preview.text'): 200,
        ('11-replay.png', 'wb.simple.replay.request'): 90,
        ('11-replay.png', 'wb.simple.replay.result'): 65,
    }
    collapsed = []
    for (view, name), minimum in required.items():
        matching = [row for row in all_content if row['view'] == view and row['name'] == name]
        if not matching:
            collapsed.append({'view': view, 'name': name, 'reason': 'missing'})
        else:
            collapsed.extend(row for row in matching if int(row['visibleHeight']) < minimum or int(row['visibleWidth']) < 120)
    for name, rows in [('controls', all_controls), ('content', all_content), ('scenarios', all_scenarios)]:
        if rows:
            with (args.out / f'{name}.csv').open('w', encoding='utf-8', newline='') as target:
                writer = csv.DictWriter(target, fieldnames=rows[0].keys())
                writer.writeheader(); writer.writerows(rows)
    result = {
        'version': version, 'artifact': jar.relative_to(ROOT).as_posix(),
        'toolchainMode': 'explicit-source17-target17-api-unverified' if args.source_target else 'strict-release17',
        'java17ApiCompatibilityVerified': not args.source_target,
        'compilerNote': 'Explicit -source/-target fallback on Java 21; --release 17 API compatibility was not validated.' if args.source_target else 'Compiled with --release 17.',
        'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
        'renderedViews': views, 'controls': len(all_controls), 'scenarios': len(all_scenarios),
        'retainedPrimaryTools': ['traffic','mapper','auth','js','environment','response.recon','payload','bounty'],
        'replayEvidenceModes': ['manual-request-replay','evidence-editor'],
        'clippedControls': clipped, 'collapsedAreas': collapsed, 'failedScenarios': failed,
        'scope': 'Production Swing component captures with synthetic packet/API adapters; zero HTTP. Not actual Burp GUI, Windows DPI/IME or browser validation.',
        'result': 'passed' if not clipped and not collapsed and not failed else 'failed',
    }
    (args.out / 'layout-report.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: value for key, value in result.items() if key not in ['clippedControls', 'collapsedAreas']}, ensure_ascii=False))
    print(f'Clipped controls: {len(clipped)}; collapsed required areas: {len(collapsed)}')
    return 1 if args.check and result['result'] != 'passed' else 0


if __name__ == '__main__':
    raise SystemExit(main())
