#!/usr/bin/env python3
"""Install the pinned, optional Katana binary outside the Simple distribution.

Only the official HTTPS release asset is downloaded. Its source-pinned digest
is checked before any executable is extracted or run. No package manager,
administrator installation, shell, PATH edit or HOME override is used. The
upstream version command may clean Katana resume files older than ten days in
its normal user configuration directory. A matching existing installation can
be reused; different installation files are never overwritten.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import ssl
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path


VERSION = "1.8.0"
SIMPLE_ROOT = Path(__file__).resolve().parents[1]
DEPENDENCY_FILE = SIMPLE_ROOT / "docs" / "KATANA_DEPENDENCY.json"
DOWNLOAD_BASE = f"https://github.com/projectdiscovery/katana/releases/download/v{VERSION}/"
PLATFORMS = (
    "linux_386", "linux_amd64", "linux_arm64", "macOS_amd64", "macOS_arm64",
    "windows_386", "windows_amd64", "windows_arm64",
)
DOWNLOAD_HOSTS = frozenset((
    "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
))
MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
MAX_EXECUTABLE_BYTES = 256 * 1024 * 1024
SOCKET_TIMEOUT_SECONDS = 20
TOTAL_DOWNLOAD_SECONDS = 120
VERSION_TIMEOUT_SECONDS = 15
METADATA_NAME = "katana-install.json"


class InstallError(Exception):
    """An error whose fixed message can safely be shown without network secrets."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(256 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_dependency() -> dict:
    dependency = json.loads(DEPENDENCY_FILE.read_text(encoding="utf-8"))
    if (not isinstance(dependency, dict) or dependency.get("version") != VERSION
            or not isinstance(dependency.get("assets"), dict)
            or set(dependency["assets"]) != set(PLATFORMS)):
        raise InstallError("Pinned Katana dependency metadata is invalid.")
    for key, asset in dependency["assets"].items():
        if not isinstance(asset, dict):
            raise InstallError("Pinned Katana asset metadata is invalid.")
        expected_name = f"katana_{VERSION}_{key}.zip"
        expected_binary = "katana.exe" if key.startswith("windows_") else "katana"
        if (asset.get("fileName") != expected_name
                or asset.get("url") != DOWNLOAD_BASE + expected_name
                or asset.get("executable") != expected_binary
                or not re.fullmatch(r"[0-9a-f]{64}", str(asset.get("sha256", "")))
                or not isinstance(asset.get("sizeBytes"), int)
                or not 0 < asset["sizeBytes"] <= MAX_ARCHIVE_BYTES):
            raise InstallError("Pinned Katana asset metadata is invalid.")
    return dependency


def detect_platform() -> str:
    system = {"Linux": "linux", "Darwin": "macOS", "Windows": "windows"}.get(platform.system())
    machine = platform.machine().lower()
    if machine in ("x86_64", "amd64", "x64"):
        architecture = "amd64"
    elif machine in ("arm64", "aarch64"):
        architecture = "arm64"
    elif machine in ("x86", "i386", "i486", "i586", "i686"):
        architecture = "386"
    else:
        raise InstallError("This operating system or architecture has no pinned Katana asset.")
    selected = f"{system}_{architecture}"
    if selected not in PLATFORMS:
        raise InstallError("This operating system or architecture has no pinned Katana asset.")
    return selected


def validate_destination(raw: str) -> Path:
    chosen = Path(raw)
    if not chosen.is_absolute():
        raise InstallError("--dest must be an explicit absolute directory path.")
    if chosen.is_symlink():
        raise InstallError("The installation directory must not be a symbolic link.")
    destination = chosen.resolve()
    if destination == SIMPLE_ROOT or SIMPLE_ROOT in destination.parents:
        raise InstallError("Choose a directory outside Simple; external binaries must not enter its source ZIP.")
    if destination.exists() and not destination.is_dir():
        raise InstallError("The installation destination is not a directory.")
    return destination


def validate_download_url(url: str) -> None:
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "https" or parsed.hostname not in DOWNLOAD_HOSTS
            or parsed.username is not None or parsed.password is not None
            or parsed.port not in (None, 443)):
        raise InstallError("The download redirected outside official HTTPS release storage.")


class OfficialReleaseRedirect(urllib.request.HTTPRedirectHandler):
    def __init__(self, deadline: float):
        super().__init__()
        self.deadline = deadline

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if time.monotonic() > self.deadline:
            raise InstallError("The Katana download exceeded its total time limit.")
        validate_download_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download_archive(asset: dict, target: Path) -> None:
    validate_download_url(asset["url"])
    started = time.monotonic()
    opener = urllib.request.build_opener(
        OfficialReleaseRedirect(started + TOTAL_DOWNLOAD_SECONDS),
        urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    request = urllib.request.Request(asset["url"], headers={
        "User-Agent": "Workbench-Simple-Katana-Installer/1.3", "Accept": "application/octet-stream",
    })
    digest = hashlib.sha256()
    total = 0
    try:
        with opener.open(request, timeout=SOCKET_TIMEOUT_SECONDS) as response, target.open("xb") as stream:
            validate_download_url(response.geturl())
            if response.status != 200:
                raise InstallError("The official Katana release download did not return HTTP 200.")
            content_length = response.headers.get("Content-Length")
            if content_length is not None and int(content_length) != asset["sizeBytes"]:
                raise InstallError("The Katana archive size differs from its pinned metadata.")
            while True:
                if time.monotonic() - started > TOTAL_DOWNLOAD_SECONDS:
                    raise InstallError("The Katana download exceeded its total time limit.")
                # read1 performs at most one socket read, so a slow-drip response
                # cannot hide inside a buffered read while the deadline expires.
                chunk = response.read1(64 * 1024)
                if time.monotonic() - started > TOTAL_DOWNLOAD_SECONDS:
                    raise InstallError("The Katana download exceeded its total time limit.")
                if not chunk:
                    break
                total += len(chunk)
                if total > min(MAX_ARCHIVE_BYTES, asset["sizeBytes"]):
                    raise InstallError("The Katana archive exceeded its pinned size limit.")
                digest.update(chunk)
                stream.write(chunk)
    except urllib.error.HTTPError as error:
        raise InstallError(f"Official Katana download failed with HTTP {error.code}.") from None
    except (urllib.error.URLError, TimeoutError, ssl.SSLError):
        raise InstallError("Official Katana download failed or timed out; no executable was installed.") from None
    except ValueError:
        raise InstallError("The release server returned invalid download metadata.") from None
    if total != asset["sizeBytes"] or digest.hexdigest() != asset["sha256"]:
        raise InstallError("Katana archive SHA-256 or size verification failed; nothing was installed.")


def extract_executable(archive: Path, name: str, target: Path) -> dict:
    try:
        with zipfile.ZipFile(archive) as zipped:
            matches = [entry for entry in zipped.infolist() if entry.filename == name]
            if len(matches) != 1:
                raise InstallError("The pinned Katana archive does not contain exactly one expected executable.")
            entry = matches[0]
            mode = entry.external_attr >> 16
            if (entry.is_dir() or entry.flag_bits & 1 or stat.S_ISLNK(mode)
                    or (stat.S_IFMT(mode) and not stat.S_ISREG(mode))
                    or not 0 < entry.file_size <= MAX_EXECUTABLE_BYTES):
                raise InstallError("The Katana executable archive entry is invalid.")
            digest = hashlib.sha256()
            total = 0
            # No archive path is used as a filesystem destination; only this exact entry is read.
            with zipped.open(entry) as source, target.open("xb") as output:
                while True:
                    chunk = source.read(256 * 1024)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > min(MAX_EXECUTABLE_BYTES, entry.file_size):
                        raise InstallError("The Katana executable exceeded its extraction limit.")
                    digest.update(chunk)
                    output.write(chunk)
            if total != entry.file_size:
                raise InstallError("The Katana executable extraction was incomplete.")
            target.chmod(0o755)
            return {"fileName": name, "sha256": digest.hexdigest(), "sizeBytes": total}
    except (zipfile.BadZipFile, RuntimeError, NotImplementedError):
        raise InstallError("The pinned Katana archive could not be read safely.") from None


def probe_version(executable: Path, scratch: Path) -> None:
    scratch.mkdir()
    # Preserve the caller's HOME/USERPROFILE exactly. Only application config and
    # temporary directories are redirected. Katana v1.8.0 may independently clean
    # resume-* files older than ten days under its normal user config at startup.
    # No raw version output or caller credentials are written to installation logs.
    environment = {key: os.environ[key] for key in (
        "SystemRoot", "WINDIR", "COMSPEC", "PATHEXT", "LANG", "LC_ALL", "HOME", "USERPROFILE",
    ) if key in os.environ}
    for key in ("XDG_CONFIG_HOME", "XDG_CACHE_HOME", "XDG_DATA_HOME",
                "APPDATA", "LOCALAPPDATA", "TEMP", "TMP", "TMPDIR"):
        environment[key] = str(scratch)
    try:
        with tempfile.TemporaryFile(dir=scratch) as output:
            result = subprocess.run(
                [str(executable), "-duc", "-version"], cwd=scratch, env=environment,
                stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
                timeout=VERSION_TIMEOUT_SECONDS, check=False,
            )
            output.seek(0)
            report = output.read(8193)
    except subprocess.TimeoutExpired:
        raise InstallError("Katana version verification timed out; nothing was installed.") from None
    except OSError:
        raise InstallError("The selected Katana binary could not run on this host; nothing was installed.") from None
    text = report.decode("utf-8", errors="replace")
    versions = re.findall(r"(?im)\b(?:current\s+)?version\s*[:=]?\s*v?(\d+\.\d+\.\d+)\b", text)
    if result.returncode != 0 or len(report) > 8192 or VERSION not in versions:
        raise InstallError("Katana did not report the pinned version; nothing was installed.")


def check_existing(path: Path, expected_sha256: str) -> bool:
    if path.is_symlink():
        raise InstallError("An installation output is a symbolic link; choose a fresh destination.")
    if path.exists():
        if not path.is_file() or sha256_file(path) != expected_sha256:
            raise InstallError("A different installation file already exists; it was preserved. Choose a fresh directory.")
        return True
    return False


def install(destination: Path, selected: str, dependency: dict) -> dict:
    asset = dependency["assets"][selected]
    destination.mkdir(parents=True, exist_ok=True)
    lock = destination / ".katana-install.lock"
    try:
        lock_descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        raise InstallError("An installation lock exists; wait for that installer or choose a fresh directory.") from None
    os.close(lock_descriptor)
    try:
        with tempfile.TemporaryDirectory(prefix=".katana-install-", dir=destination) as temporary:
            stage = Path(temporary)
            archive = stage / asset["fileName"]
            staged_binary = stage / asset["executable"]
            download_archive(asset, archive)
            executable_info = extract_executable(archive, asset["executable"], staged_binary)
            probe_version(staged_binary, stage / "probe-config")
            metadata = {
                "schemaVersion": 1, "name": "Katana", "version": VERSION, "platform": selected,
                "distribution": "external-optional", "bundled": False,
                "asset": {key: asset[key] for key in ("fileName", "url", "sizeBytes", "sha256")},
                "executable": executable_info,
                "versionProbe": {
                    "arguments": ["-duc", "-version"], "version": VERSION,
                    "callerHomePreserved": True, "upstreamMayCleanResumeFilesOlderThanDays": 10,
                },
            }
            staged_metadata = stage / METADATA_NAME
            staged_metadata.write_text(json.dumps(metadata, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            staged_metadata.chmod(0o644)
            outputs = (
                (staged_binary, destination / asset["executable"], executable_info["sha256"]),
                (staged_metadata, destination / METADATA_NAME, sha256_file(staged_metadata)),
            )
            present = [check_existing(final, digest) for _, final, digest in outputs]
            if present[0] and os.name != "nt" and not os.access(outputs[0][1], os.X_OK):
                raise InstallError("The existing Katana file is not executable; it was preserved. Choose a fresh directory.")
            created = []
            try:
                for (staged, final, _), exists in zip(outputs, present):
                    if not exists:
                        # Same-directory hard-link creation publishes a complete file atomically
                        # and, unlike rename/replace, can never overwrite an existing file.
                        os.link(staged, final)
                        created.append((staged, final))
            except OSError:
                for staged, final in reversed(created):
                    if final.exists() and final.samefile(staged):
                        final.unlink()
                raise InstallError("Atomic installation failed; existing files were preserved.") from None
            return {
                "status": "already-installed" if all(present) else "installed",
                "version": VERSION, "platform": selected,
                "executable": str(destination / asset["executable"]),
                "metadata": str(destination / METADATA_NAME),
            }
    finally:
        lock.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dest", required=True, help="Absolute user-selected directory outside Simple")
    parser.add_argument("--platform", default="auto", choices=("auto",) + PLATFORMS)
    args = parser.parse_args()
    try:
        dependency = load_dependency()
        destination = validate_destination(args.dest)
        selected = detect_platform() if args.platform == "auto" else args.platform
        print(json.dumps(install(destination, selected, dependency), ensure_ascii=False))
        return 0
    except InstallError as error:
        print(f"Katana installation failed: {error}", file=sys.stderr)
    except (OSError, json.JSONDecodeError, KeyError, TypeError):
        # Do not echo exception details: proxy URLs, redirected query strings and
        # caller environment values must not become installation logs.
        print("Katana installation failed: local I/O or pinned metadata could not be processed.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
