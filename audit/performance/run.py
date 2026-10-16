#!/usr/bin/env python3
"""Compare isolated source snapshots with identical synthetic capture/Mapper workloads."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
WB = Path("project/burp-development-workspace/burp-workbench-kr/burp-workbench")
HARNESS = Path(__file__).resolve().parent


def fingerprint(files, root):
    digest = hashlib.sha256()
    for path in sorted(files):
        digest.update(str(path.relative_to(root)).encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
    return digest.hexdigest()


def run_variant(source, dependencies, output, label, component, java):
    target = output / label / component
    target.mkdir(parents=True, exist_ok=True)
    classes = target / "classes"
    classes.mkdir(exist_ok=True)
    if any(classes.iterdir()):
        raise SystemExit(f"Refusing stale classes: choose a fresh --output directory ({classes})")
    if component == "capture":
        base = source / WB
        sources = sorted((base / "src/main/java").rglob("*.java"))
        # Compile adapter support from the same snapshot without running the regression suite.
        sources += sorted((base / "src/test/java").rglob("*.java"))
        jars = sorted((dependencies / WB / "deps").glob("*.jar"))
        harness, main = "CapturePerformanceCheck.java", "kr.workbench.CapturePerformanceCheck"
    else:
        base = source / "feature-mapper"
        sources = sorted((base / "src/main/java").rglob("*.java"))
        jars = sorted((dependencies / "feature-mapper/deps").glob("*.jar"))
        harness, main = "MapperPerformanceCheck.java", "kr.featuremapper.MapperPerformanceCheck"
    if not sources or not jars:
        raise SystemExit(f"Missing sources/dependencies for {label}/{component}")
    source_sha = fingerprint(sources, source)
    cp = os.pathsep.join(map(str, jars))
    compile_cmd = [java, "com.sun.tools.javac.Main", "--release", "17", "-encoding", "UTF-8",
                   "-cp", cp, "-d", str(classes), *map(str, sources), str(HARNESS / harness)]
    with (target / "compile.log").open("w", encoding="utf-8") as log:
        subprocess.run(compile_cmd, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
    if fingerprint(sources, source) != source_sha:
        raise SystemExit(f"Sources changed during compile for {label}/{component}; rerun after edits finish")
    result = target / "result.json"
    command = [java, "-Xms64m", "-Xmx512m", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8",
               "-Duser.language=en", "-Duser.country=US", f"-Dqa.output={result}",
               "-cp", str(classes) + os.pathsep + cp, main]
    started = time.time()
    with (target / "run.log").open("w", encoding="utf-8") as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=240)
    report = json.loads(result.read_text(encoding="utf-8"))
    report.update(label=label, sourceFingerprintSha256=source_sha,
                  harnessSha256=hashlib.sha256((HARNESS / harness).read_bytes()).hexdigest(),
                  runElapsedSeconds=round(time.time() - started, 3),
                  dependencySha256={p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in jars})
    result.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"PASS {label}/{component}: {result}", flush=True)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, help="Read-only repository source snapshot, e.g. 0.25.5")
    parser.add_argument("--candidate", type=Path, help="Read-only candidate repository source snapshot")
    parser.add_argument("--dependencies", type=Path, default=ROOT, help="Repository containing verified deps/*.jar")
    parser.add_argument("--component", choices=["capture", "mapper", "both"], default="both")
    parser.add_argument("--output", type=Path, default=ROOT / "tmp/performance" / str(time.time_ns()))
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    if not args.baseline and not args.candidate:
        parser.error("provide --baseline and/or --candidate")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    report = {"scope": "Local synthetic host; no native Burp/browser/network targets", "runs": []}
    components = ["capture", "mapper"] if args.component == "both" else [args.component]
    for label, source in [("baseline", args.baseline), ("candidate", args.candidate)]:
        if source:
            for component in components:
                report["runs"].append(run_variant(source.resolve(), args.dependencies.resolve(), output,
                                                  label, component, args.java))
    # Separate component invocations may share one output root; retain their completed reports.
    report["runs"] = [json.loads(path.read_text(encoding="utf-8"))
                      for path in sorted(output.glob("*/*/result.json"))]
    (output / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(output / "summary.json")


if __name__ == "__main__":
    main()
