#!/usr/bin/env python3
"""Run dev.py release with fresh output while preserving every committed download byte."""
import hashlib
import json
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    downloads = ROOT / "downloads"
    pom = ROOT / "project/burp-development-workspace/community-security-lab/pom.xml"
    version = re.search(r'<artifactId>loop-community-lab</artifactId><version>([^<]+)', pom.read_text()).group(1)
    expected_zip = downloads / f"docker/{version}/community-security-lab-{version}-docker.zip"
    expected = digest(expected_zip)
    before = {p.relative_to(downloads).as_posix(): digest(p) for p in downloads.rglob("*") if p.is_file()}
    with tempfile.TemporaryDirectory(prefix="workbench-release-outputs-") as directory:
        saved = Path(directory) / "downloads"
        shutil.move(str(downloads), str(saved))
        try:
            subprocess.run([sys.executable, "-u", str(ROOT / "dev.py"), "release"], cwd=ROOT, check=True)
            subprocess.run([sys.executable, str(ROOT / "tools/package_downloads.py"), "--verify"], cwd=ROOT, check=True)
            if digest(expected_zip) != expected:
                raise AssertionError(f"Fresh release Docker ZIP differs from committed {version} download")
        finally:
            if downloads.exists():
                shutil.rmtree(downloads)
            shutil.move(str(saved), str(downloads))
    after = {p.relative_to(downloads).as_posix(): digest(p) for p in downloads.rglob("*") if p.is_file()}
    if after != before:
        raise AssertionError("Committed download bytes changed")
    summary = {"result": "passed", "command": "python dev.py release", "freshOutputDirectory": True,
               "committedDownloadsPreserved": True,
               "dockerVersion": version, "dockerZipSha256": expected, "freshDockerZipMatchesCommitted": True}
    current = json.loads((ROOT / "project/burp-development-workspace/burp-workbench-kr/burp-workbench/build/check-summary.json").read_text())
    summary.update({key: current[key] for key in ["version", "regressionGroups", "regressionChecks", "payloadHttpChecks", "allFeaturesHttpChecks", "bountyHttpChecks", "totalChecks"]})
    output = ROOT / "tmp/durability/release.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary))

if __name__ == "__main__":
    main()
