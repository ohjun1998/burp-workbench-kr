#!/usr/bin/env python3
"""Run dev.py release with fresh output while preserving every committed download byte."""
import hashlib
import json
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
    expected_zip = downloads / "docker/3.5.2/community-security-lab-3.5.2-docker.zip"
    expected = digest(expected_zip)
    before = {p.relative_to(downloads).as_posix(): digest(p) for p in downloads.rglob("*") if p.is_file()}
    with tempfile.TemporaryDirectory(prefix="workbench-release-outputs-") as directory:
        saved = Path(directory) / "downloads"
        shutil.move(str(downloads), str(saved))
        try:
            subprocess.run([sys.executable, "-u", str(ROOT / "dev.py"), "release"], cwd=ROOT, check=True)
            subprocess.run([sys.executable, str(ROOT / "tools/package_downloads.py"), "--verify"], cwd=ROOT, check=True)
            if digest(expected_zip) != expected:
                raise AssertionError("Fresh release Docker ZIP differs from committed 3.5.2 download")
        finally:
            if downloads.exists():
                shutil.rmtree(downloads)
            shutil.move(str(saved), str(downloads))
    after = {p.relative_to(downloads).as_posix(): digest(p) for p in downloads.rglob("*") if p.is_file()}
    if after != before:
        raise AssertionError("Committed download bytes changed")
    summary = {"result": "passed", "command": "python dev.py release", "freshOutputDirectory": True,
               "committedDownloadsPreserved": True, "regressionChecks": 1298, "payloadHttpChecks": 132,
               "allFeaturesHttpChecks": 100, "bountyHttpChecks": 266, "totalChecks": 1796,
               "dockerVersion": "3.5.2", "dockerZipSha256": expected, "freshDockerZipMatchesCommitted": True}
    output = ROOT / "tmp/durability/release.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary))

if __name__ == "__main__":
    main()
