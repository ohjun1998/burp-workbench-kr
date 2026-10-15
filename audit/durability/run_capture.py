#!/usr/bin/env python3
"""Run production capture durability checks with an adapter host, without changing user settings."""
import argparse
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
WB = ROOT / "project/burp-development-workspace/burp-workbench-kr/burp-workbench"

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--mode", choices=["soak", "disk-full"], default="soak")
    p.add_argument("--seconds", type=int, default=180)
    p.add_argument("--interval-ms", type=int, default=50)
    p.add_argument("--java", default="java")
    p.add_argument("--temp-dir", type=Path)
    p.add_argument("--output", type=Path, default=ROOT / "tmp/durability/summary.json")
    a = p.parse_args()
    if not 15 <= a.seconds <= 10800 or not 10 <= a.interval_ms <= 1000:
        p.error("seconds must be 15..10800 and interval-ms 10..1000")
    if a.mode == "soak" and a.seconds * 1000 // a.interval_ms <= 250:
        p.error("soak must send more than 250 packets")
    if a.mode == "disk-full" and a.temp_dir is None:
        p.error("disk-full requires --temp-dir pointing to a dedicated tmpfs <=64 MiB")
    version = re.search(r"dist/burp-workbench-kr-([0-9.]+)\.jar", (WB / "build_all.py").read_text()).group(1)
    jar = WB / f"dist/burp-workbench-kr-{version}.jar"
    if not jar.is_file() or not (WB / "build/tests/kr/workbench/WorkbenchTest.class").is_file():
        p.error("run python build_all.py --test in the extension folder first")
    classes = ROOT / "tmp/durability/classes"
    classes.mkdir(parents=True, exist_ok=True)
    cp = os.pathsep.join(map(str, [jar, WB / "build/tests", *WB.glob("deps/*.jar")]))
    subprocess.run([a.java, "com.sun.tools.javac.Main", "--release", "17", "-encoding", "UTF-8",
                    "-cp", cp, "-d", str(classes), str(Path(__file__).with_name("CaptureDurabilityCheck.java"))], check=True)
    options = [a.java, "-Xmx512m", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8",
               "-Duser.language=en", "-Duser.country=US", f"-Dqa.mode={a.mode}",
               f"-Dqa.seconds={a.seconds}", f"-Dqa.interval={a.interval_ms}", f"-Dqa.output={a.output.resolve()}"]
    if a.temp_dir is not None:
        folder = a.temp_dir.resolve(strict=True)
        options.append(f"-Djava.io.tmpdir={folder}")
    subprocess.run(options + ["-cp", str(classes) + os.pathsep + cp, "kr.workbench.CaptureDurabilityCheck"],
                   check=True, timeout=a.seconds + 180)
    print(a.output.read_text(encoding="utf-8"))

if __name__ == "__main__":
    main()
