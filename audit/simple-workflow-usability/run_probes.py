#!/usr/bin/env python3
"""Run the follow-up workflow probes against one immutable Simple JAR."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import sys
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
WB = REPO / "simple/burp-workbench"
HELPER = REPO / "audit/simple-usability/run_probes.py"
HELPER_SHA = "c80f012d9191be4b2ed076438821c3053735f65dec687223e473350f81c7bd22"
if hashlib.sha256(HELPER.read_bytes()).hexdigest() != HELPER_SHA:
    raise RuntimeError("The preserved probe utility source differs from its recorded hash")
spec = importlib.util.spec_from_file_location("preserved_probe_utilities", HELPER)
util = importlib.util.module_from_spec(spec)
spec.loader.exec_module(util)

PROBES = {
    "environment": "EnvironmentWorkflowProbe",
    "passive": "PassiveTargetFlowProbe",
    "readiness": "TrafficReadinessProbe",
}


def run(args: argparse.Namespace) -> int:
    if args.out.is_symlink():
        raise util.ProbeError("Use a fresh output directory, not a symbolic link")
    jar, output, adapters = args.jar.resolve(), args.out.resolve(), args.adapters.resolve()
    source = HERE / (PROBES[args.probe] + ".java")
    if output.exists():
        raise util.ProbeError("Use a fresh output directory; evidence is never overwritten")
    if not jar.is_file() or not source.is_file() or not adapters.is_dir():
        raise util.ProbeError("Prepare the product JAR, probe source and compiled test adapters first")
    with zipfile.ZipFile(jar) as archive:
        identity = archive.read("META-INF/MANIFEST.MF").decode()
        build = json.loads(archive.read("META-INF/simple-build.json"))
        if "Implementation-Title: Burp Workbench Simple KR" not in identity:
            raise util.ProbeError("The supplied JAR is not Workbench Simple")
        if not build.get("java17ApiCompatibilityVerified"):
            raise util.ProbeError("The product must have a strict Java 17 build")
    java = util.java_executable(args.java)
    output.mkdir(parents=True, mode=0o700)
    metadata = {
        "schemaVersion": 1, "probe": args.probe, "phase": args.phase,
        "startedAtUtc": util.now(), "status": "preparing",
        "nativeBurp": False, "osClicks": False, "browserDOM": False,
        "repositoryHeadAtLaunch": util.repo_head(), "repositoryHeadProvesJarSource": False,
        "runnerSHA256": util.sha(Path(__file__)), "preservedUtilitySHA256": HELPER_SHA,
        "harnessSource": {"path": str(source.relative_to(REPO)), "sha256": util.sha(source)},
        "commands": [], "resultFiles": [], "withheldOutputs": [], "_out": output,
        "runtimeIsolation": {"userHome": True, "javaPreferences": True,
                             "javaTmp": True, "rawPacketsPromoted": False,
                             "rawReportZipsPromoted": False},
        "resultMeaning": "A completed probe can report expected baseline defects. Compare the JSON conditions; completion alone is not an improvement or correctness claim.",
    }
    private = None
    try:
        metadata["productJar"] = util.frozen_file(jar, output / ".product.jar")
        source_dir, classes, dependencies = [output / name for name in (".sources", ".classes", ".deps")]
        for directory in (source_dir, classes, dependencies):
            directory.mkdir()
        metadata["sourceSnapshot"] = util.frozen_file(source, source_dir / source.name)
        metadata["adapters"] = util.snapshot_adapters(adapters, output / ".adapters")
        metadata["dependencies"] = [util.frozen_file(WB / "deps" / name, dependencies / name)
                                    for name in ("montoya-2026.jar", "legacy-api.jar", "rhino.jar", "brotli-dec-0.1.2.jar")]
        environment = os.environ.copy()
        metadata["removedJvmEnvironmentNames"] = [name for name in util.REMOVED_ENV if name in environment]
        for name in util.REMOVED_ENV:
            environment.pop(name, None)
        with tempfile.TemporaryDirectory(prefix="simple-workflow-goals-") as temporary:
            private = Path(temporary)
            work, logs = private / "work", private / "logs"
            for directory in (work, logs, private / "home", private / "prefs/user", private / "prefs/system", private / "tmp"):
                directory.mkdir(parents=True, exist_ok=True)
            options = [str(java), f"-Duser.home={private / 'home'}",
                       f"-Djava.util.prefs.userRoot={private / 'prefs/user'}",
                       f"-Djava.util.prefs.systemRoot={private / 'prefs/system'}",
                       f"-Djava.io.tmpdir={private / 'tmp'}", "-Djava.awt.headless=true",
                       "-Dfile.encoding=UTF-8"]
            classpath = os.pathsep.join(map(str, [output / ".product.jar", classes,
                                                 output / ".adapters", *sorted(dependencies.glob("*.jar"))]))
            try:
                util.command([str(java), "-version"], logs / "java-version.log", environment,
                             metadata["commands"], private, 20)
                if not re.search(r'(?:openjdk|java) version "17(?:\.|\")', (logs / "java-version.log").read_text()):
                    raise util.ProbeError("Use JDK 17 for comparable diagnostics")
                util.command([*options, "com.sun.tools.javac.Main", "--release", "17",
                              "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes),
                              str(source_dir / source.name)], logs / "compile.log", environment,
                             metadata["commands"], private)
                util.command([*options, "-cp", classpath, "kr.workbench." + PROBES[args.probe],
                              str(work), args.phase], logs / "probe.log", environment,
                             metadata["commands"], private, 240)
                if not (work / "result.json").is_file():
                    raise util.ProbeError("The probe did not write result.json")
                metadata["status"] = "completed"
            finally:
                for path in sorted(work.rglob("*.json")):
                    util.promote(path, output / "results" / path.relative_to(work), metadata)
                for path in sorted(logs.glob("*.log")):
                    util.promote(path, output / "logs" / path.name, metadata)
    except (OSError, ValueError, util.ProbeError, KeyboardInterrupt) as error:
        metadata["status"] = "failed"
        metadata["failureClass"] = type(error).__name__
        metadata["failure"] = "Probe setup or execution failed; inspect reviewed logs and withheld-output metadata."
    finally:
        metadata["runtimeIsolation"]["privateRuntimeRemoved"] = private is None or not private.exists()
        try:
            metadata["inputJarUnchanged"] = util.sha(jar) == metadata["productJar"]["sha256"]
            metadata["productSnapshotUnchanged"] = util.sha(output / ".product.jar") == metadata["productJar"]["sha256"]
            metadata["harnessSourceUnchanged"] = util.sha(source) == metadata["harnessSource"]["sha256"]
            metadata["harnessSnapshotUnchanged"] = util.sha(output / ".sources" / source.name) == metadata["harnessSource"]["sha256"]
        except (OSError, KeyError):
            metadata["inputJarUnchanged"] = False
        metadata["runnerSourceUnchanged"] = util.sha(Path(__file__)) == metadata["runnerSHA256"]
        metadata["preservedUtilityUnchanged"] = util.sha(HELPER) == HELPER_SHA
        if metadata["withheldOutputs"] or not all(metadata.get(key, False) for key in
                ("inputJarUnchanged", "productSnapshotUnchanged", "harnessSourceUnchanged", "harnessSnapshotUnchanged",
                 "runnerSourceUnchanged", "preservedUtilityUnchanged")) \
                or not metadata["runtimeIsolation"]["privateRuntimeRemoved"]:
            metadata["status"] = "failed"
        metadata["finishedAtUtc"] = util.now()
        metadata.pop("_out", None)
        util.write_json(output / "metadata.json", metadata)
    print(json.dumps({"probe": args.probe, "phase": args.phase,
                      "status": metadata["status"], "output": str(output)}, ensure_ascii=False))
    return 0 if metadata["status"] == "completed" else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe", choices=tuple(PROBES), required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--adapters", type=Path, default=WB / "build/tests")
    parser.add_argument("--java")
    parser.add_argument("--phase", default="audit")
    args = parser.parse_args()
    def terminate(_signal, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, terminate)
    try:
        return run(args)
    except (OSError, util.ProbeError) as error:
        parser.exit(2, str(error) + "\n")


if __name__ == "__main__":
    sys.exit(main())
