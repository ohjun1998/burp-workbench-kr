#!/usr/bin/env python3
"""Run one preserved Workbench Simple probe without rebuilding or modifying the product."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import time

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
SIMPLE = REPO / "simple" / "burp-workbench"
TEST_SOURCES = SIMPLE / "src" / "test" / "java" / "kr" / "workbench"
PROBES = {
    "reading": (TEST_SOURCES / "ScopeInspectionReadingTest.java", "kr.workbench.ScopeInspectionReadingTest"),
    "target": (TEST_SOURCES / "ScopeInspectionTargetFlowTest.java", "kr.workbench.ScopeInspectionTargetFlowTest"),
    "tables": (HERE / "InspectionUpdateProbe.java", "kr.workbench.InspectionUpdateProbe"),
    "urls": (HERE / "PassiveImportBenchmark.java", "kr.workbench.PassiveImportBenchmark"),
    "dashboard": (HERE / "DashboardNavigationProbe.java", "kr.workbench.DashboardNavigationProbe"),
    "loop": (HERE / "LoopWorkflowAudit.java", "kr.workbench.LoopWorkflowAudit"),
    "whole": (HERE / "WholeWorkbenchWorkflowAudit.java", "kr.workbench.WholeWorkbenchWorkflowAudit"),
}
WHOLE_MODULES = ("traffic", "mapper", "auth", "js", "environment", "response.recon", "payload", "bounty")
REQUIRED_ADAPTERS = (
    "kr/workbench/WorkbenchTest.class", "kr/workbench/ScopeInspectionFixture.class",
    "kr/workbench/SimpleWorkflowTest.class", "kr/workbench/PayloadIntegrationTest.class",
    "kr/workbench/ScopeInspectionUiTest.class",
)
REMOVED_ENV = ("CLASSPATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
SECRET_PATTERNS = {
    "credential header": re.compile(r"(?im)^\s*(?:authorization|proxy-authorization|cookie|set-cookie|x-api-key)\s*:\s*\S+"),
    "credential assignment": re.compile(
        r'''(?ix)(?:["']?(?:password|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|session[_-]?token)["']?\s*[:=]\s*["'])(?!\[?(?:redacted|가림)\]?)[^"'\s]{4,}'''),
    "session cookie value": re.compile(r"(?i)\b(?:JSESSIONID|SESSIONID|SID)\s*=\s*[^\s;\"']{6,}"),
    "private key block": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "provider token": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{16,}|sk_live_[A-Za-z0-9]{16,})"),
    "fixture credential literal": re.compile(r"LabOnly!2026|SCOPE_PRIVATE_API_VALUE_|SCOPE_ALICE_SESSION|REALM_A_ONLY|CYCLE5_(?:ALICE|BOB)_EXAMPLE"),
}


class ProbeError(RuntimeError):
    pass


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def tree_index(root: Path) -> dict[str, dict[str, object]]:
    """Only adapter bytecode is copied, never arbitrary reports or runtime files."""
    result = {}
    for path in sorted(root.rglob("*.class")):
        if path.is_symlink():
            raise ProbeError("Adapter bytecode symlinks are not supported")
        result[path.relative_to(root).as_posix()] = {"sha256": sha(path), "bytes": path.stat().st_size}
    return result


def index_sha(index: dict) -> str:
    return hashlib.sha256(json.dumps(index, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def snapshot_adapters(source: Path, destination: Path) -> dict:
    # A concurrent build can remove/recompile its test directory. Retry a bounded number
    # of times and accept a snapshot only when source and copy have identical hashes.
    temporary = destination.with_name(destination.name + ".incomplete")
    for attempt in range(3):
        shutil.rmtree(temporary, ignore_errors=True)
        try:
            before = tree_index(source)
            if not all(name in before for name in REQUIRED_ADAPTERS):
                raise OSError("Required test adapters are not currently available")
            temporary.mkdir()
            for name in before:
                target = temporary / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source / name, target)
            copied, after = tree_index(temporary), tree_index(source)
            if before != copied or before != after:
                raise OSError("Adapter build changed while taking its snapshot")
            temporary.rename(destination)
            return {"source": str(source), "snapshot": ".adapters", "classFiles": len(copied),
                    "treeSHA256": index_sha(copied), "stableDuringCopy": True, "copyAttempts": attempt + 1}
        except OSError:
            if attempt == 2:
                raise ProbeError("Could not snapshot stable test adapters; finish the product test build and retry with a new --out")
            time.sleep(0.1)
        finally:
            shutil.rmtree(temporary, ignore_errors=True)
    raise AssertionError("unreachable adapter snapshot state")


def frozen_file(source: Path, destination: Path) -> dict:
    before = sha(source)
    shutil.copyfile(source, destination)
    if sha(destination) != before or sha(source) != before:
        raise ProbeError("An input artifact changed while taking its snapshot; retry with a stable artifact")
    return {"source": str(source), "snapshot": destination.name, "sha256": before, "bytes": destination.stat().st_size}


def java_executable(value: str | None) -> Path:
    if value:
        selected = shutil.which(value) or value
    else:
        candidates = sorted((REPO / ".dev-tools").glob("jdk-17*/bin/java"))
        selected = str(candidates[0]) if candidates else shutil.which("java")
    if not selected or not Path(selected).is_file():
        raise ProbeError("Java 17 was not found; pass its executable with --java")
    return Path(selected).resolve()


def repo_head() -> str | None:
    try:
        result = subprocess.run(["git", "-C", str(REPO), "rev-parse", "HEAD"], capture_output=True,
                                text=True, timeout=10, check=False)
        return result.stdout.strip() if result.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def command(argv: list[str], log: Path, env: dict[str, str], records: list[dict], runtime: Path,
            timeout: int = 180) -> None:
    record = {"argv": [arg.replace(str(runtime), "<private-runtime>") for arg in argv], "timeoutSeconds": timeout}
    records.append(record)
    with log.open("wb") as stream:
        try:
            result = subprocess.run(argv, cwd=runtime / "work", env=env, stdout=stream, stderr=subprocess.STDOUT,
                                    check=False, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            record["timedOut"] = True
            raise ProbeError("A probe command exceeded its deadline; private runtime will be removed") from error
    record["returnCode"] = result.returncode
    if result.returncode:
        raise ProbeError(f"A probe command returned {result.returncode}; inspect the reviewed output logs")


def credential_markers(raw: bytes) -> list[str]:
    text = raw.decode("utf-8", errors="replace")
    return [name for name, pattern in SECRET_PATTERNS.items() if pattern.search(text)]


def promote(source: Path, destination: Path, manifest: dict) -> bool:
    if source.is_symlink() or not source.is_file():
        return False
    if source.stat().st_size > 64 * 1024 * 1024:
        manifest["withheldOutputs"].append({"file": destination.name, "reason": "file exceeds the evidence size bound"})
        return False
    raw = source.read_bytes()
    if source.suffix.lower() in (".json", ".log"):
        markers = credential_markers(raw)
        if markers:
            manifest["withheldOutputs"].append({"file": destination.name, "reason": "credential-shaped text", "markerClasses": markers})
            return False
        if source.suffix.lower() == ".json":
            json.loads(raw)
    elif source.suffix.lower() == ".png":
        if manifest["probe"] != "dashboard":
            manifest["withheldOutputs"].append({"file": destination.name, "reason": "only synthetic dashboard PNG evidence is promoted"})
            return False
        if not raw.startswith(b"\x89PNG\r\n\x1a\n"):
            return False
    else:
        return False  # In particular, raw report ZIPs, original packets and runtime files are never promoted.
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        raise ProbeError("Refusing to overwrite a result file")
    destination.write_bytes(raw)
    manifest["resultFiles"].append({"path": destination.relative_to(manifest["_out"]).as_posix(),
                                    "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)})
    return True


def dashboard_summary(work: Path, phase: str) -> dict:
    results = []
    for theme in ("light", "dark"):
        for width, height in ((1024, 720), (1280, 800)):
            results.append(json.loads((work / f"{theme}-{width}x{height}" / "result.json").read_text(encoding="utf-8")))
    observations = [dict(theme=result["theme"], width=result["width"], height=result["height"], **item)
                    for result in results for item in result["observations"]]
    return {
        "phase": phase, "nativeBurp": False,
        "fixture": results[0]["fixture"], "surface": results[0]["surface"],
        "matrix": [{"theme": result["theme"], "width": result["width"], "height": result["height"]} for result in results],
        "measured_navigation_tasks": len(observations),
        "one_click_with_list_and_detail_visible": sum(not item["additional_explicit_scroll_required"] for item in observations),
        "additional_explicit_scroll_required": sum(item["additional_explicit_scroll_required"] for item in observations),
        "wheel_event_counts_estimated": False,
        "target_http_calls": sum(result["checks"]["target_http_calls"] for result in results),
        "all_original_account_empty_checks_passed": all(result["checks"][key] for result in results
            for key in ("exact_original_selection", "account_condition_preserved", "empty_condition_recovered")),
        "all_automatic_refresh_scroll_checks_passed": all(result["checks"]["automatic_refresh_preserves_user_scroll"] for result in results),
        "observations": observations,
    }


def whole_summary_contract(summary: dict, jar_sha: str) -> dict:
    """Validate provenance and eight-module presence, preserving the harness's own outcome values."""
    modules = summary.get("modules", {})
    return {
        "expectedModuleIds": list(WHOLE_MODULES),
        "allEightModuleLabelsPresent": isinstance(modules, dict) and set(WHOLE_MODULES).issubset(modules),
        "jarSHA256MatchesExecutedSnapshot": summary.get("jarSHA256") == jar_sha,
        "originalLoopOriginRecorded": summary.get("baseURL") == "http://127.0.0.1:18888",
        "actualEngineHttpObserved": isinstance(summary.get("engineHttpRequests"), int) and summary["engineHttpRequests"] > 0,
        "nativeBurpFalse": summary.get("nativeBurp") is False,
    }


def run(args: argparse.Namespace) -> int:
    if args.out.exists() or args.out.is_symlink():
        raise ProbeError("--out must name a new path; existing evidence and symlinks are never overwritten")
    output, jar, adapters = args.out.resolve(), args.jar.resolve(), args.adapters.resolve()
    if output.exists() or output.is_symlink():
        raise ProbeError("--out must name a new path; existing evidence is never overwritten, even if its directory is empty")
    source, entry = PROBES[args.probe]
    if not jar.is_file() or not adapters.is_dir() or not source.is_file():
        raise ProbeError("The product JAR, compiled test adapters and selected probe source must already exist")
    dependencies = sorted((SIMPLE / "deps").glob("*.jar"))
    if not dependencies:
        raise ProbeError("Prepared dependency JARs are missing from simple/burp-workbench/deps")
    preserved = json.loads((HERE / "harnesses.json").read_text(encoding="utf-8"))
    additional = preserved.get("additional_new_harnesses", {})
    if args.probe == "whole" and source.name not in additional:
        raise ProbeError("The new whole-workflow harness must be finalized and SHA-registered before whole-round execution")
    for name, expected in {**preserved["files"], **additional}.items():
        if sha(HERE / name) != expected["sha256"]:
            raise ProbeError("A registered harness differs from its recorded SHA; finalize sources before starting the round series")
    runtime_java = java_executable(args.java)
    output.mkdir(parents=True, mode=0o700)
    manifest = {
        "schemaVersion": 1, "probe": args.probe, "phase": args.phase, "startedAtUtc": now(),
        "nativeBurp": False, "browserDOM": False, "osClicks": False,
        "repositoryHeadAtLaunch": repo_head(), "repositoryHeadProvesJarSource": False,
        "harnessSource": {"path": str(source.relative_to(REPO)), "sha256": sha(source)},
        "preservedHarnessSHA256": {name: entry["sha256"] for name, entry in preserved["files"].items()},
        "additionalNewHarnessSHA256": {name: entry["sha256"] for name, entry in additional.items()},
        "strictJava17": False, "commands": [], "resultFiles": [], "withheldOutputs": [],
        "runtimeIsolation": {"userHome": True, "javaPrefs": True, "javaTmp": True,
                             "rawReportZipsPromoted": False, "privateRuntimeRemoved": False},
        "textReview": "Heuristic checks only; no claim of complete secret detection. PNGs are synthetic-fixture captures and require visual review before sharing.",
        "status": "preparing", "_out": output,
    }
    if args.probe in ("loop", "whole"):
        manifest["loop"] = {"baseURL": "http://127.0.0.1:18888", "serverStartedByRunner": False,
                            "requiresPreparedOriginalLoop3100": True}
        if args.probe == "loop":
            manifest["loop"].update({"historicalHarnessSourceCommit": "81b48defb81db7c656e913a8ebd6848e30621cf1",
                                     "historicalCommitProvesExecutedJar": False})
    if args.probe == "whole":
        manifest["wholeWorkflow"] = {"sourceClassification": "additional newly authored harness, not an external byte-exact copy",
            "expectedModuleIds": list(WHOLE_MODULES),
            "roundMeaning": "all eight core workflows before one cumulative improvement and all eight again afterwards",
            "focusedFiveCasesArePreliminaryInvestigation": True}
    failure = None
    private_runtime = None
    try:
        manifest["productJar"] = frozen_file(jar, output / ".product.jar")
        source_dir = output / ".sources"; source_dir.mkdir()
        manifest["harnessSourceSnapshot"] = frozen_file(source, source_dir / source.name)
        manifest["adapters"] = snapshot_adapters(adapters, output / ".adapters")
        dep_dir = output / ".deps"; dep_dir.mkdir()
        manifest["dependencies"] = [frozen_file(dep, dep_dir / dep.name) for dep in dependencies]
        classes = output / ".classes"; classes.mkdir()
        environment = os.environ.copy()
        manifest["removedJvmEnvironmentNames"] = [name for name in REMOVED_ENV if name in environment]
        for name in REMOVED_ENV:
            environment.pop(name, None)  # Never log the removed values or modify HOME.
        with tempfile.TemporaryDirectory(prefix="workbench-simple-probe-") as temporary:
            private_runtime = Path(temporary)
            home, prefs, java_tmp, work, logs = [private_runtime / name for name in ("home", "prefs", "tmp", "work", "logs")]
            for folder in (home, prefs / "user", prefs / "system", java_tmp, work, logs):
                folder.mkdir(parents=True, exist_ok=True)
            options = [str(runtime_java), f"-Duser.home={home}", f"-Djava.util.prefs.userRoot={prefs / 'user'}",
                       f"-Djava.util.prefs.systemRoot={prefs / 'system'}", f"-Djava.io.tmpdir={java_tmp}",
                       "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8"]
            classpath = os.pathsep.join(map(str, [output / ".product.jar", classes, output / ".adapters", *sorted(dep_dir.glob("*.jar"))]))
            try:
                command([str(runtime_java), "-version"], logs / "java-version.log", environment, manifest["commands"], private_runtime, 20)
                version_text = (logs / "java-version.log").read_text(encoding="utf-8", errors="replace")
                if not re.search(r'(?:openjdk|java) version "17(?:\.|\")', version_text):
                    raise ProbeError("This runner requires a Java 17 runtime; pass --java pointing to a prepared JDK 17 executable")
                manifest["java"] = {"executable": str(runtime_java), "version": version_text.strip(), "major": 17}
                command([*options, "com.sun.tools.javac.Main", "--release", "17", "-encoding", "UTF-8",
                         "-cp", classpath, "-d", str(classes), str(source_dir / source.name)], logs / "compile.log",
                        environment, manifest["commands"], private_runtime)
                majors = sorted({struct.unpack(">H", path.read_bytes()[6:8])[0] for path in classes.rglob("*.class")})
                if not majors or majors != [61]:
                    raise ProbeError("Probe compilation did not produce Java 17 bytecode")
                manifest["strictJava17"] = True
                manifest["compiledProbeClassMajorVersions"] = majors
                base = [*options, "-cp", classpath, entry]
                if args.probe in ("reading", "target"):
                    command([*base, "--probe", str(work / "result.json")], logs / "probe.log", environment, manifest["commands"], private_runtime)
                elif args.probe == "tables":
                    command([*base, args.phase, str(work / "result.json"), "2000"], logs / "probe.log", environment, manifest["commands"], private_runtime)
                elif args.probe == "urls":
                    command([*base, str(work / "result.json")], logs / "probe.log", environment, manifest["commands"], private_runtime)
                elif args.probe == "dashboard":
                    for theme in ("light", "dark"):
                        for width, height in ((1024, 720), (1280, 800)):
                            name = f"{theme}-{width}x{height}"; case = work / name; case.mkdir()
                            command([*base, str(case), theme, str(width), str(height)], logs / f"{name}.log",
                                    environment, manifest["commands"], private_runtime, 120)
                    write_json(work / "summary.json", dashboard_summary(work, args.phase))
                else:
                    # Readiness only: the launcher never starts, resets or modifies the LOOP server.
                    try:
                        with socket.create_connection(("127.0.0.1", 18888), timeout=3):
                            pass
                    except OSError as error:
                        raise ProbeError("Prepare the original LOOP 3.10.0 server on 127.0.0.1:18888 before running the loop or whole probe") from error
                    command([*base, str(work), manifest["productJar"]["sha256"]], logs / "probe.log",
                            environment, manifest["commands"], private_runtime, 240)
                    if args.probe == "whole":
                        contract = whole_summary_contract(json.loads((work / "summary.json").read_text(encoding="utf-8")),
                                                          manifest["productJar"]["sha256"])
                        manifest["wholeWorkflow"]["summaryContract"] = contract
                        if not all(value for key, value in contract.items() if key != "expectedModuleIds"):
                            raise ProbeError("Whole-workflow summary lacks required module, original HTTP or executed-JAR provenance")
                manifest["status"] = "completed"
            finally:
                # Keep only reviewed public evidence. Raw ZIPs and all private home/prefs/tmp
                # files stay under TemporaryDirectory, even when the JVM fails or times out.
                for path in sorted(work.rglob("*")):
                    if path.is_file() and path.suffix.lower() in (".json", ".png"):
                        promote(path, output / "results" / path.relative_to(work), manifest)
                for path in sorted(logs.glob("*.log")):
                    promote(path, output / "logs" / path.name, manifest)
    except KeyboardInterrupt:
        manifest["status"] = "interrupted"
        manifest["failure"] = {"class": "KeyboardInterrupt", "message": "Probe interrupted; private runtime cleanup was requested."}
    except (OSError, subprocess.SubprocessError, ProbeError, ValueError) as error:
        failure = error.__class__.__name__
        manifest["status"] = "failed"
        # Do not echo an arbitrary probe/exception string that could contain response data.
        manifest["failure"] = {"class": failure, "message": str(error) if isinstance(error, ProbeError)
                               else "Probe setup or execution failed; inspect reviewed logs and withheld-output metadata."}
    finally:
        manifest["runtimeIsolation"]["privateRuntimeRemoved"] = private_runtime is None or not private_runtime.exists()
        if not manifest["runtimeIsolation"]["privateRuntimeRemoved"]:
            manifest["runtimeIsolation"]["cleanupFailureDirectory"] = str(private_runtime)
        try:
            manifest["inputJarSHA256After"] = sha(jar)
            manifest["inputJarUnchanged"] = manifest.get("productJar", {}).get("sha256") == manifest["inputJarSHA256After"]
            manifest["productSnapshotUnchanged"] = sha(output / ".product.jar") == manifest.get("productJar", {}).get("sha256")
            manifest["harnessSourceUnchanged"] = sha(source) == manifest["harnessSource"]["sha256"]
            manifest["harnessSnapshotUnchanged"] = sha(output / ".sources" / source.name) == manifest["harnessSource"]["sha256"]
        except OSError:
            manifest["inputJarUnchanged"] = manifest["productSnapshotUnchanged"] = False
            manifest["harnessSourceUnchanged"] = manifest["harnessSnapshotUnchanged"] = False
        if manifest["withheldOutputs"]:
            manifest["status"] = "withheld-output"
        if not manifest["runtimeIsolation"]["privateRuntimeRemoved"] or not manifest["inputJarUnchanged"] or not manifest["productSnapshotUnchanged"] \
                or not manifest["harnessSourceUnchanged"] or not manifest["harnessSnapshotUnchanged"]:
            manifest["status"] = "failed"
        manifest["finishedAtUtc"] = now()
        manifest.pop("_out", None)
        write_json(output / "metadata.json", manifest)
    print(json.dumps({"probe": args.probe, "phase": args.phase, "status": manifest["status"],
                      "output": str(output), "nativeBurp": False}, ensure_ascii=False))
    return 0 if manifest["status"] == "completed" else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe", required=True, choices=tuple(PROBES))
    parser.add_argument("--jar", required=True, type=Path, help="Existing product JAR; this runner never rebuilds it")
    parser.add_argument("--out", required=True, type=Path, help="New evidence directory; existing paths are rejected")
    parser.add_argument("--java", help="Prepared JDK 17 java executable; defaults to a repository JDK 17 or PATH java")
    parser.add_argument("--adapters", type=Path, default=SIMPLE / "build" / "tests", help="Existing compiled test-adapter directory")
    parser.add_argument("--phase", default="audit", help="Evidence label, e.g. before, progressive-before or after")
    args = parser.parse_args()
    previous_termination = signal.getsignal(signal.SIGTERM)
    def terminate(_signum: int, _frame: object) -> None:
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, terminate)
    try:
        return run(args)
    except (ProbeError, OSError) as error:
        parser.exit(2, f"{error}\n")
    finally:
        signal.signal(signal.SIGTERM, previous_termination)


if __name__ == "__main__":
    raise SystemExit(main())
