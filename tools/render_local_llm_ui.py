#!/usr/bin/env python3
"""Render actual Next AI and request-confirmation Swing UI with synthetic fixtures.

Requires a completed `python dev.py test` build. Never builds production sources,
calls a model, opens login, or sends target traffic. Measurements are offscreen
layout QA, not a native Burp or Halogen/Codex end-to-end test.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import subprocess

ROOT = Path(__file__).resolve().parents[1]
WB = ROOT / "project/burp-development-workspace/burp-workbench-kr/burp-workbench"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java", help="JDK 17+ java executable (default: java)")
    parser.add_argument("--jar", type=Path, help="Completed release JAR; defaults to current release_info.py version")
    parser.add_argument("--out", type=Path, default=ROOT / "tmp/local-llm-ui")
    parser.add_argument("--sizes", nargs="+", default=["1024x720", "1280x800"])
    parser.add_argument("--themes", nargs="+", choices=["light", "dark"], default=["light", "dark"])
    parser.add_argument("--check", action="store_true", help="Fail for clipped enabled buttons or useful viewports below 50px")
    args = parser.parse_args()
    for size in args.sizes:
        if not re.fullmatch(r"[1-9]\d{2,3}x[1-9]\d{2,3}", size):
            parser.error(f"Invalid size: {size}")
    version = runpy.run_path(str(WB / "release_info.py"))["VERSION"]
    jar = (args.jar or WB / f"dist/burp-workbench-kr-{version}.jar").resolve()
    tests = WB / "build/tests"
    if not jar.is_file() or not (tests / "kr/workbench/WorkbenchTest.class").is_file():
        raise SystemExit("A completed release JAR and test adapters are required. Run python dev.py test first.")
    out = args.out.resolve()
    classes = out / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    sources = [ROOT / "tools/LocalLlmUiPreview.java", Path(__file__).resolve()]
    source_hashes = {str(path.relative_to(ROOT)): digest(path) for path in sources}
    jar_hash = digest(jar)
    classpath = os.pathsep.join(map(str, [jar, *(WB / "deps" / name for name in
        ["montoya-2026.jar", "legacy-api.jar", "rhino.jar"]), tests]))
    subprocess.run([args.java, "com.sun.tools.javac.Main", "--release", "17", "-encoding", "UTF-8",
        "-cp", classpath, "-d", str(classes), str(sources[0])], check=True, timeout=60)
    runs: list[dict] = []
    all_violations: list[dict] = []
    for theme in dict.fromkeys(args.themes):
        for size in dict.fromkeys(args.sizes):
            width, height = size.split("x")
            run_out = out / theme / size
            subprocess.run([args.java, f"-Duser.home={run_out / 'host-home'}", "-Djava.awt.headless=true",
                "-Dfile.encoding=UTF-8", "-cp", classpath + os.pathsep + str(classes),
                "kr.workbench.LocalLlmUiPreview", str(run_out),
                str(WB / "src/main/resources/fonts/NanumGothic-Regular.ttf"), theme, width, height],
                check=True, timeout=90)
            measured = json.loads((run_out / "measurements.json").read_text(encoding="utf-8"))
            for view in measured["views"]:
                png = run_out / view["png"]
                if not png.is_file():
                    raise SystemExit(f"Missing preview: {png}")
                view["png"] = str(png.relative_to(out))
                view["sha256"] = digest(png)
            for violation in measured["violations"]:
                all_violations.append({"theme": theme, "size": size, **violation})
            runs.append(measured)
    if digest(jar) != jar_hash:
        raise SystemExit("Release JAR changed while rendering; retry after the build completes.")
    report = {
        "description": "Actual Next production AI panel, settings and request-confirmation pane; synthetic fixtures only",
        "nativeBurpValidation": False,
        "limitations": ["Simulated Swing light/dark appearance; native Burp appearance may differ",
            "No real Halogen inference, Codex login/subscription request, or target HTTP was performed",
            "Request-confirmation preview never shows a modal dialog, invokes the prompt, or supplies a decision",
            "Settings controls outside a scroll viewport remain reachable by scrolling"],
        "releaseJar": str(jar), "releaseJarSha256": jar_hash, "previewSourceSha256": source_hashes,
        "java": args.java, "pngCount": sum(run["pngCount"] for run in runs),
        "targetRequests": sum(run["targetRequests"] for run in runs),
        "inferenceActionsInvoked": sum(run["inferenceActionsInvoked"] for run in runs),
        "enabledButtonsMeasured": sum(sum(control["enabled"] for control in run["controls"]) for run in runs),
        "minimumUsefulViewportHeight": 50,
        "violationCount": len(all_violations), "violations": all_violations, "runs": runs,
    }
    report_path = out / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{report['pngCount']} PNGs; {report['violationCount']} layout violations; report: {report_path}")
    if args.check and all_violations:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
