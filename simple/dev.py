#!/usr/bin/env python3
"""Workbench Simple standalone build, regression and immutable packaging commands."""
from pathlib import Path
import argparse
import json
import os
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
WB = ROOT / 'burp-workbench'


def run(command, **kwargs):
    print('+', ' '.join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), check=True, **kwargs)


def node_test_options(help_text):
    """Use only the isolation spelling advertised by the installed Node parser."""
    options = ['--test', '--test-reporter=tap']
    if '--test-isolation' in help_text:
        options.append('--test-isolation=none')
    elif '--experimental-test-isolation' in help_text:
        options.append('--experimental-test-isolation=none')
    return options


def mapper_test(runtime, source_target=False):
    """Exercise the same Mapper classes that are packaged in the Simple JAR."""
    sys.path.insert(0, str(WB))
    from release_info import VERSION
    mapper = ROOT / 'feature-mapper'
    tests = mapper / 'build/tests'
    shutil.rmtree(tests, ignore_errors=True)
    tests.mkdir(parents=True)
    cp = os.pathsep.join(map(str, [WB / 'deps/montoya-2026.jar',
                                  WB / f'dist/burp-workbench-simple-{VERSION}.jar']))
    source_options = ['-source', '17', '-target', '17'] if source_target else ['--release', '17']
    run([runtime, 'com.sun.tools.javac.Main', *source_options, '-encoding', 'UTF-8',
         '-cp', cp, '-d', tests] + sorted((mapper / 'src/test/java').rglob('*.java')))
    test_home = mapper / 'build/test-home'
    test_home.mkdir(parents=True, exist_ok=True)
    result = subprocess.run([runtime, '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8',
                             '-Duser.home=' + str(test_home),
                             '-cp', cp + os.pathsep + str(tests), 'kr.featuremapper.MapperTest'],
                            cwd=mapper, capture_output=True, text=True, encoding='utf-8',
                            errors='replace', timeout=180)
    report = result.stdout + result.stderr
    (mapper / 'build/test-results.txt').write_text(report, encoding='utf-8')
    print(report, end='')
    if result.returncode:
        raise subprocess.CalledProcessError(result.returncode, ['MapperTest'])
    counted = re.search(r'(\d+) checks passed', report)
    if not counted:
        raise RuntimeError('Mapper test count missing')
    node_help = subprocess.run(['node', '--help'], capture_output=True, text=True,
                               encoding='utf-8', errors='replace', timeout=30)
    if node_help.returncode:
        raise subprocess.CalledProcessError(node_help.returncode, ['node', '--help'])
    node_options = node_test_options(node_help.stdout + node_help.stderr)
    test_source = mapper / 'tests/browser-helper.test.mjs'
    expected_node_count = len(re.findall(r'(?m)^\s*test\(', test_source.read_text(encoding='utf-8')))
    print('+ node ' + ' '.join(node_options) + ' ' + str(test_source), flush=True)
    node = subprocess.run(['node', *node_options, str(test_source)],
                          cwd=mapper, capture_output=True, text=True, encoding='utf-8',
                          errors='replace', timeout=120)
    node_report = node.stdout + node.stderr
    (mapper / 'build/browser-helper-results.txt').write_text(node_report, encoding='utf-8')
    print(node_report, end='')
    if node.returncode:
        raise subprocess.CalledProcessError(node.returncode, ['node', '--test'])
    node_count = re.search(r'# tests (\d+)', node_report)
    if not node_count:
        raise RuntimeError('Browser-helper test count missing')
    actual_node_count = int(node_count.group(1))
    if expected_node_count < 1 or actual_node_count != expected_node_count:
        raise RuntimeError(f'Browser-helper runner reported {actual_node_count} tests; '
                           f'expected {expected_node_count} declared tests. A file-level summary is insufficient.')
    return int(counted.group(1)), actual_node_count, node_options


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['doctor', 'build', 'test', 'check', 'package', 'verify', 'release'])
    parser.add_argument('--java', default='java')
    parser.add_argument('--javac')
    parser.add_argument('--group', action='append', default=[], help='Run only these Workbench groups; repeatable')
    parser.add_argument('--skip-payload', action='store_true', help='Skip the payload loopback suite for focused checks')
    parser.add_argument('--source-target', action='store_true', help='Explicit source/target 17 mode; Java 17 API compatibility is not verified')
    args = parser.parse_args()
    runtime = shutil.which(args.java)
    if not runtime:
        parser.error('A JDK 17+ is required. Set --java to its Java executable.')
    try:
        if args.command == 'doctor':
            run([runtime, '-version'])
            run([runtime, 'com.sun.tools.javac.Main', '-version'])
            print('Python:', sys.version.split()[0])
            print('Simple source:', ROOT)
            print('Node:', shutil.which('node') or 'missing (required for browser helper regression)')
            return 0
        if args.command in ['build', 'test', 'check', 'release']:
            command = [sys.executable, WB / 'build_all.py', '--java', runtime]
            if args.source_target:
                command += ['--source-target']
            if args.javac:
                command += ['--javac', args.javac]
            if args.command != 'build':
                command += ['--test']
                for group in args.group:
                    command += ['--test-group', group]
                if args.skip_payload:
                    command += ['--skip-payload']
            run(command, cwd=WB)
        if args.command in ['check', 'release']:
            if args.group or args.skip_payload:
                raise RuntimeError('check/release require the complete declared suite; use test for focused checks')
            count, browser_count, node_options = mapper_test(runtime, args.source_target)
            summary = json.loads((WB / 'build/test-summary.json').read_text(encoding='utf-8'))
            summary['mapperJavaChecks'] = count
            summary['mapperBrowserHelperChecks'] = browser_count
            summary['mapperBrowserHelperNodeOptions'] = node_options
            summary['scope'] = 'Simple packaged-JAR regressions, loopback payload HTTP, Mapper Java and browser-helper Node tests'
            summary['notRun'] = ['Native Burp GUI', 'Windows DPI/IME', 'manual browser operation',
                                  'Docker lab stack', 'live external OAST/BApp/AI services',
                                  'original lab all-features and bounty HTTP harnesses']
            (WB / 'build/check-summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
        if args.command in ['package', 'verify', 'release']:
            command = [sys.executable, ROOT / 'package_simple.py']
            if args.command == 'verify':
                command += ['--verify']
            run(command, cwd=ROOT)
        return 0
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print('ERROR:', error, file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
