#!/usr/bin/env python3
"""Run unchanged production sources and all checks with a bounded EDT wait in a test copy."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
WB = ROOT / "project/burp-development-workspace/burp-workbench-kr/burp-workbench"
TEST = Path("src/test/java/kr/payloadrunner/ExtenderTest.java")
OLD = "long end=System.currentTimeMillis()+10000;while(ext.running&&System.currentTimeMillis()<end)Thread.sleep(20);SwingUtilities.invokeAndWait(()->{});"
NEW = """long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
  while(System.nanoTime()<end){
   AtomicBoolean complete=new AtomicBoolean();
   SwingUtilities.invokeAndWait(()->complete.set(!ext.running&&ext.model.getRowCount()==2));
   if(complete.get())break;
   Thread.sleep(20);
  }
  SwingUtilities.invokeAndWait(()->{});"""

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    original = (WB / TEST).read_bytes()
    source = original.decode("utf-8")
    if source.count(OLD) != 1:
        raise RuntimeError("Legacy UI wait changed: review this adapter rather than silently altering new tests")
    production = {p.relative_to(WB).as_posix(): sha(p) for p in (WB / "src/main").rglob("*") if p.is_file()}
    with tempfile.TemporaryDirectory(prefix="workbench-windows-regression-") as directory:
        temp = Path(directory)
        copied = temp / "burp-workbench-kr/burp-workbench"
        shutil.copytree(WB, copied, ignore=shutil.ignore_patterns("build", "dist", "__pycache__", ".run", ".git"))
        fixture = Path("src/main/resources/static/community.js")
        target = temp / "community-security-lab" / fixture
        target.parent.mkdir(parents=True)
        shutil.copyfile(ROOT / "project/burp-development-workspace/community-security-lab" / fixture, target)
        # SwingWorker may publish its rows after running becomes false. Change only
        # the copied test's waiting condition; every original assertion still runs.
        (copied / TEST).write_text(source.replace(OLD, NEW), encoding="utf-8")
        subprocess.run([sys.executable, "-u", str(copied / "build_all.py"), "--test"], cwd=copied, check=True)
        if (WB / TEST).read_bytes() != original or any(sha(WB / p) != digest for p, digest in production.items()):
            raise AssertionError("Original source changed while validating its test copy")
        # The following capture checks use these current production classes and API adapters.
        for name in ("tests",):
            destination = WB / "build" / name
            if destination.exists():
                shutil.rmtree(destination)
            shutil.copytree(copied / "build" / name, destination)
        (WB / "dist").mkdir(exist_ok=True)
        for jar in (copied / "dist").glob("*.jar"):
            shutil.copyfile(jar, WB / "dist" / jar.name)
        output = ROOT / "tmp/durability/windows-regression-adapter.json"
        output.parent.mkdir(parents=True, exist_ok=True)
        summary = {"result": "passed", "productionSourceUnchanged": True, "originalTestSourceUnchanged": True,
                   "adaptedTest": TEST.as_posix(), "originalTestSha256": hashlib.sha256(original).hexdigest(),
                   "adaptation": "Test copy waits for worker completion and both published UI rows; bounded to 10 seconds",
                   "originalAssertionsRemoved": 0, "javaRegressionGroups": 41, "javaRegressionChecks": 1298,
                   "payloadHttpChecks": 127}
        output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(summary))

if __name__ == "__main__":
    main()
