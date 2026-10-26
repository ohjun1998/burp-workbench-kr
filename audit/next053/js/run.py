#!/usr/bin/env python3
"""Compare unchanged archive queries against two exact release JARs, in isolated JVMs."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import platform
import statistics
import subprocess
import tempfile


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-jar', type=Path, required=True)
    parser.add_argument('--current-jar', type=Path, required=True)
    parser.add_argument('--api-jar', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--records', type=int, default=3000)
    parser.add_argument('--forks', type=int, default=3)
    args = parser.parse_args()
    if not 400 <= args.records <= 100000 or not 1 <= args.forks <= 10:
        parser.error('records: 400..100000; forks: 1..10')
    args.out.mkdir(parents=True, exist_ok=True)
    source = Path(__file__).with_name('JsArchiveBenchmark.java').resolve()
    jars = {'baseline': args.baseline_jar.resolve(), 'current': args.current_jar.resolve()}
    api = args.api_jar.resolve()
    classpaths = {}
    for name, jar in jars.items():
        classes = args.out.resolve() / ('classes-' + name)
        classes.mkdir(exist_ok=True)
        subprocess.run([args.java, 'com.sun.tools.javac.Main', '--release', '17', '-encoding', 'UTF-8',
                        '-cp', os.pathsep.join(map(str, (jar, api))), '-d', str(classes), str(source)], check=True)
        classpaths[name] = os.pathsep.join(map(str, (classes, jar, api)))

    results = {'baseline': [], 'current': []}
    def run(name, mode, fixture):
        command = [args.java, '-Xms128m', '-Xmx256m', '-cp', classpaths[name],
                   'kr.workbench.JsArchiveBenchmark', mode, str(fixture), str(args.records)]
        result = subprocess.run(command, check=True, capture_output=True, text=True, encoding='utf-8', timeout=600)
        return result.stdout.strip()

    with tempfile.TemporaryDirectory(prefix='archive-fixture-', dir=args.out.resolve()) as temporary:
        fixture = Path(temporary)
        run('baseline', 'seed', fixture)
        for fork in range(args.forks):
            # Alternate order to reduce systematic filesystem/JIT/host-load ordering bias.
            for name in (('baseline', 'current') if fork % 2 == 0 else ('current', 'baseline')):
                result = json.loads(run(name, 'run', fixture).splitlines()[-1])
                results[name].append(result)
                (args.out / f'{name}-{fork + 1}.json').write_text(json.dumps(result, indent=2) + '\n')
    checksums = {result['checksum'] for group in results.values() for result in group}
    if len(checksums) != 1:
        raise RuntimeError('archive result checksum mismatch')
    metrics = ('coldSearchMs', 'searchP50Ms', 'searchP95Ms', 'sequentialPageP50Ms',
               'sequentialPageP95Ms', 'sampledPeakHeapBytes', 'gcCollectionMs')
    report = {
        'fixture': {'records': args.records, 'pageSize': 200, 'forks': args.forks,
                    'source': 'deterministic synthetic metadata; no network requests',
                    'orderingAndTotalAssertions': 'passed', 'checksum': checksums.pop()},
        'environment': {'platform': platform.platform(), 'cpuCount': os.cpu_count(),
                        'java': results['current'][0]['java'], 'heap': '-Xms128m -Xmx256m'},
        'artifacts': {name: {'path': str(jar), 'sha256': sha(jar)} for name, jar in jars.items()},
        'harnessSha256': sha(source),
        'medianOfForks': {name: {metric: statistics.median(row[metric] for row in rows)
                                  for metric in metrics} for name, rows in results.items()},
        'runs': results,
        'limits': ['Archive metadata microbenchmark, not native Burp throughput.',
                   'Heap is sampled after operations, not a guaranteed process peak.',
                   'GC collection time is JVM-reported cumulative time, not individual pause percentiles.',
                   'Cold search includes first-query scanning; repeated queries use the same archive revision.'],
    }
    (args.out / 'comparison.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    print(json.dumps(report['medianOfForks'], indent=2))


if __name__ == '__main__':
    main()
