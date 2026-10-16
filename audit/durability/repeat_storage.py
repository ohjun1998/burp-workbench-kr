#!/usr/bin/env python3
"""Repeat the two production-capture regression groups involved in the Windows failure."""
import argparse
import json
import os
from pathlib import Path
import runpy
import subprocess

ROOT = Path(__file__).resolve().parents[2]
WB = ROOT / 'project/burp-development-workspace/burp-workbench-kr/burp-workbench'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--iterations', type=int, default=10)
    args = parser.parse_args()
    if not 1 <= args.iterations <= 20:
        parser.error('iterations must be 1..20')
    version = runpy.run_path(str(WB / 'release_info.py'))['VERSION']
    cp = os.pathsep.join(map(str, [WB / f'dist/burp-workbench-kr-{version}.jar',
                                  WB / 'build/tests', *sorted(WB.glob('deps/*.jar'))]))
    groups = ['CapturePipelineTest', 'TrafficCaptureRecoveryTest']
    for iteration in range(args.iterations):
        for group in groups:
            subprocess.run(['java', '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8',
                            '-cp', cp, 'kr.workbench.TestLauncher', 'kr.workbench.' + group],
                           cwd=WB, check=True, timeout=120)
        print(f'STORAGE_REPEAT {iteration + 1}/{args.iterations}', flush=True)
    result = {'result': 'passed', 'version': version, 'iterations': args.iterations,
              'groups': groups, 'executions': args.iterations * len(groups),
              'note': 'Repeated executions are not additional unique check counts.'}
    output = ROOT / 'tmp/durability/storage-repeat.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
