#!/usr/bin/env python3
"""Render and check production Mapper Swing with synthetic data, without native Burp."""
from pathlib import Path
import argparse
import csv
import hashlib
import json
import os
import runpy
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=ROOT.parent / 'tmp/mapper-ui')
    parser.add_argument('--java', default='java')
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--sizes', nargs='+', default=['1024x720', '1280x800', '1440x920'])
    parser.add_argument('--themes', nargs='+', choices=['light', 'dark'], default=['light', 'dark'])
    args = parser.parse_args()
    version = runpy.run_path(str(ROOT / 'release_info.py'))['VERSION']
    jar = ROOT / f'dist/feature-mapper-kr-{version}.jar'
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    cp = os.pathsep.join(map(str, [jar, ROOT / 'build/tests', *sorted((ROOT / 'deps').glob('*.jar'))]))
    controls = []
    for theme in args.themes:
        for size in args.sizes:
            width, height = size.split('x')
            out = args.out / theme / size
            subprocess.run([args.java, '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8', '-cp', cp,
                            'kr.featuremapper.MapperUiPreview', str(out), theme, width, height], check=True)
            with (out / 'controls.csv').open() as source:
                controls.extend(csv.DictReader(source))
    if hashlib.sha256(jar.read_bytes()).hexdigest() != digest:
        raise SystemExit('The Mapper JAR changed during rendering; re-run after build completion.')
    clipped = [row for row in controls if row['visible'] == 'false' and row['scrollable'] == 'false']
    small = [row for row in controls if int(row['controlWidth']) < 24 or int(row['controlHeight']) < 24]
    report = {'version': version, 'jarSha256': digest,
              'method': 'Production Swing; API adapters and synthetic metadata, no native Burp GUI.',
              'themes': args.themes, 'sizes': args.sizes,
              'renderedViews': len(args.themes) * len(args.sizes) * 5,
              'controlsMeasured': len(controls), 'clippedControls': clipped, 'smallControls': small,
              'result': 'passed' if not clipped and not small else 'failed'}
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / 'layout-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: value for key, value in report.items() if key not in ('clippedControls', 'smallControls')}))
    if args.check and (clipped or small):
        raise SystemExit(f'Mapper layout gate failed: {len(clipped)} clipped, {len(small)} small controls')


if __name__ == '__main__':
    main()
