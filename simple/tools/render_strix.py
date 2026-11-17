#!/usr/bin/env python3
"""Capture the exact release-JAR Strix UI with synthetic fixtures, never a real CLI/model/target.

Requires a Simple test build. Outputs stay outside simple/ by default so guide
images do not modify frozen release inputs. This is not native Burp validation.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import runpy
import subprocess


ROOT = Path(__file__).resolve().parents[2]
SIMPLE = ROOT / "simple"
WB = SIMPLE / "burp-workbench"
STAGES = ["01-prepared.png", "02-halogen.png", "03-chatgpt-auth.png", "04-execution-plan.png",
          "05-running-log.png", "06-stopped.png", "07-reports.png"]


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=ROOT / "tmp/simple-strix-layout")
    parser.add_argument("--jar", type=Path, help="Immutable release JAR override")
    parser.add_argument("--java", default="java", help="JDK java supporting --release 17 compiler")
    parser.add_argument("--test-classes", type=Path, default=WB / "build/tests")
    parser.add_argument("--themes", nargs="+", choices=["light", "dark"], default=["light", "dark"])
    parser.add_argument("--sizes", nargs="+", default=["1024x720", "1280x800"])
    parser.add_argument("--check", action="store_true", help="Fail on clipped named controls or content viewport below 50px")
    args = parser.parse_args()
    version = runpy.run_path(str(WB / "release_info.py"))["VERSION"]
    artifact_id = runpy.run_path(str(WB / "release_info.py"))["ARTIFACT_ID"]
    jar = (args.jar or WB / f"dist/{artifact_id}-{version}.jar").resolve()
    test_classes = args.test_classes.resolve()
    if not jar.is_file() or not (test_classes / "kr/workbench/SimpleStrixIntegrationTest.class").is_file():
        parser.error("Build the release and test adapters first: python simple/dev.py test")
    for size in args.sizes:
        try:
            width, height = map(int, size.split("x"))
            if not 640 <= width <= 3840 or not 480 <= height <= 2160:
                raise ValueError()
        except ValueError:
            parser.error(f"Unsupported preview size: {size}")
    out = args.out.resolve(); classes = out / ".classes"; classes.mkdir(parents=True, exist_ok=True)
    source = SIMPLE / "tools/StrixPanelPreview.java"
    jar_hash = sha(jar)
    source_hashes = {p.relative_to(ROOT).as_posix(): sha(p) for p in [Path(__file__).resolve(), source,
                     WB / "src/test/java/kr/workbench/SimpleStrixIntegrationTest.java"]}
    # JAR must be first: the Java harness verifies its actual production code source.
    classpath = os.pathsep.join(map(str, [jar, classes, *sorted((WB / "deps").glob("*.jar")), test_classes]))
    subprocess.run([args.java, "com.sun.tools.javac.Main", "--release", "17", "-encoding", "UTF-8",
                    "-cp", classpath, "-d", str(classes), str(source)], check=True)
    runs = []; missing = []; process_failures = []; violations = []
    for theme in args.themes:
        for size in args.sizes:
            width, height = map(int, size.split("x"))
            run_out = out / theme / size; run_out.mkdir(parents=True, exist_ok=True)
            home = run_out / ".host-home"; home.mkdir(exist_ok=True)
            font_cache = run_out / ".font-cache"; font_cache.mkdir(exist_ok=True)
            command = [args.java, f"-Duser.home={home}", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8",
                       "-cp", classpath, "kr.workbench.StrixPanelPreview", str(run_out), theme,
                       str(width), str(height), str(jar)]
            if args.check:
                command.append("check")
            done = subprocess.run(command, text=True, encoding="utf-8", errors="replace", capture_output=True,
                                  timeout=90, env={**os.environ, "XDG_CACHE_HOME": str(font_cache)})
            if done.stdout:
                print(done.stdout.strip(), flush=True)
            if done.returncode:
                process_failures.append({"theme": theme, "size": size, "exitCode": done.returncode})
                print(done.stderr[-3000:], flush=True)
            measurement = run_out / "measurements.json"
            if not measurement.is_file():
                missing.append({"theme": theme, "size": size, "file": "measurements.json"})
                continue
            result = json.loads(measurement.read_text(encoding="utf-8"))
            if result["releaseJarSha256"] != jar_hash:
                raise RuntimeError("Measured JAR differs from the selected release")
            for name in STAGES:
                png = run_out / name
                if not png.is_file():
                    missing.append({"theme": theme, "size": size, "file": name})
            for view in result["views"]:
                png = run_out / view["png"]
                view["png"] = png.relative_to(out).as_posix()
                view["sha256"] = sha(png)
            violations.extend({"theme": theme, "size": size, "description": message} for message in result["violations"])
            runs.append(result)
    if sha(jar) != jar_hash:
        raise RuntimeError("Release JAR changed during capture; rerun after the build finishes")
    report = {"description": "Actual release-JAR Simple Strix panel; synthetic runner and source metadata",
              "releaseJar": str(jar), "releaseJarSha256": jar_hash, "nativeBurpValidation": False,
              "strixCliExecutions": 0, "modelCalls": 0, "targetRequests": 0,
              "previewSources": source_hashes, "pngCount": sum(len(run["views"]) for run in runs),
              "minimumContentViewportHeight": 50, "violationCount": len(violations), "violations": violations,
              "missing": missing, "processFailures": process_failures, "runs": runs,
              "limitations": ["Not a native user Burp screenshot or model/OAuth test",
                  "Running/report/auth states come from the injected synthetic runner",
                  "Approval content rendered without a modal decision or real process launch",
                  "Measured named control bounds and content viewports; not every native theme/DPI/IME combination"]}
    (out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Strix UI: {report['pngCount']} screens; {len(violations)} layout violations; actual CLI/model/target 0", flush=True)
    return 1 if missing or process_failures or (args.check and violations) else 0


if __name__ == "__main__":
    raise SystemExit(main())
