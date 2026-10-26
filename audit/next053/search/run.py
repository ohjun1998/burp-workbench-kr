#!/usr/bin/env python3
"""Run identical disk-backed search workloads against frozen source snapshots."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

WB = Path("project/burp-development-workspace/burp-workbench-kr/burp-workbench")
HERE = Path(__file__).resolve().parent


def fingerprint(paths, root):
    digest = hashlib.sha256()
    for path in sorted(paths):
        digest.update(path.relative_to(root).as_posix().encode() + b"\0" + path.read_bytes())
    return digest.hexdigest()


def variant(source, dependencies, output, label, java, portable_compile):
    target = output / label
    classes = target / "classes"
    classes.mkdir(parents=True, exist_ok=False)
    sources = sorted((source / WB / "src/main/java").rglob("*.java"))
    sources += sorted((source / WB / "src/test/java").rglob("*.java"))
    sources += [p for p in sorted((source / "feature-mapper/src/main/java/kr/featuremapper").rglob("*.java")) if p.name != "FeatureMapperExtension.java"]
    jars = sorted((dependencies / WB / "deps").glob("*.jar"))
    source_hash = fingerprint(sources, source)
    classpath = os.pathsep.join(map(str, jars))
    compiler = ["-source", "17", "-target", "17"] if portable_compile else ["--release", "17"]
    with (target / "compile.log").open("w") as log:
        subprocess.run([java, "com.sun.tools.javac.Main", *compiler, "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes), *map(str, sources), str(HERE / "TrafficSearchBenchmark.java")], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
    if fingerprint(sources, source) != source_hash:
        raise RuntimeError("Source changed during benchmark compilation")
    result = target / "result.json"
    classpath = os.pathsep.join([str(classes), classpath, str(source / WB / "src/main/resources")])
    with (target / "run.log").open("w") as log:
        subprocess.run([java, "-Xms64m", "-Xmx512m", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US", f"-Dqa.output={result}", "-cp", classpath, "kr.workbench.TrafficSearchBenchmark"], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=240)
    report = json.loads(result.read_text())
    report.update(label=label, sourceFingerprintSha256=source_hash, harnessSha256=hashlib.sha256((HERE / "TrafficSearchBenchmark.java").read_bytes()).hexdigest(), dependencySha256={p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in jars}, compilerMode="source/target 17 on host JDK; not Java 17 API verification" if portable_compile else "release 17")
    result.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--dependencies", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--portable-compile", action="store_true", help="Explicitly label source/target-only compilation on stripped JDKs")
    args = parser.parse_args()
    results = [variant(source.resolve(), args.dependencies.resolve(), args.output.resolve(), label, args.java, args.portable_compile) for label, source in [("baseline", args.baseline), ("candidate", args.candidate)]]
    baseline, candidate = results
    for before, after in zip(baseline["results"], candidate["results"], strict=True):
        if (before["scenario"], before["resultSha256"]) != (after["scenario"], after["resultSha256"]):
            raise RuntimeError("Search membership changed between versions")
    (args.output / "comparison.json").write_text(json.dumps({"runs": results, "membershipEquivalent": True, "timingIsReleaseGate": False}, ensure_ascii=False, indent=2) + "\n")
    print(args.output / "comparison.json")


if __name__ == "__main__":
    main()
