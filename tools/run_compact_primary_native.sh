#!/usr/bin/env bash
set -euo pipefail
repository=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repository"
out=${NATIVE_OUT:-$repository/tmp/compact-primary-native}
mkdir -p "$out/classes" "$out/.cache" "$out/.config"
out=$(cd "$out" && pwd)
export XDG_CACHE_HOME="$out/.cache" XDG_CONFIG_HOME="$out/.config"
# Service activation must inherit these paths before the session bus starts.
if [[ "${COMPACT_NATIVE_DBUS_READY:-}" != 1 ]]; then
  exec dbus-run-session -- env COMPACT_NATIVE_DBUS_READY=1 bash "$0" "$@"
fi
version=$(python3 -c 'import sys;sys.path.insert(0,"simple/burp-workbench");from release_info import VERSION;print(VERSION)')
jar=${NATIVE_JAR:-$repository/simple/burp-workbench/dist/burp-workbench-simple-$version.jar}
test -f "$jar"
classpath="$jar:$repository/simple/burp-workbench/build/tests"
for dependency in "$repository"/simple/burp-workbench/deps/*.jar; do classpath="$classpath:$dependency"; done
sha256sum "$jar" > "$out/artifact.sha256"
javac --release 17 -encoding UTF-8 -cp "$classpath" -d "$out/classes" \
  tools/HeaderToolsNative.java tools/CompactPrimaryNavigationNative.java
classpath="$out/classes:$classpath"
display_number=${NATIVE_DISPLAY_NUMBER:-93}
if [[ -S "/tmp/.X11-unix/X$display_number" || -e "/tmp/.X$display_number-lock" ]]; then
  echo "Display :$display_number is already in use." >&2; exit 1
fi
cat > "$out/xorg.conf" <<'XORG'
Section "ServerFlags"
  Option "AutoAddDevices" "false"
  Option "AutoEnableDevices" "false"
  Option "AutoAddGPU" "false"
  Option "AllowMouseOpenFail" "true"
EndSection
Section "Device"
  Identifier "Dummy"
  Driver "dummy"
  VideoRam 256000
EndSection
Section "Monitor"
  Identifier "Monitor"
  HorizSync 5.0-1000.0
  VertRefresh 5.0-200.0
  Modeline "1920x1200" 193.25 1920 2056 2256 2592 1200 1203 1209 1245 -HSync +VSync
EndSection
Section "Screen"
  Identifier "Screen"
  Device "Dummy"
  Monitor "Monitor"
  DefaultDepth 24
  SubSection "Display"
    Depth 24
    Modes "1920x1200"
    Virtual 1920 1200
  EndSubSection
EndSection
XORG
xorg_pid= wm_pid=
cleanup() {
  if [[ -n "$wm_pid" ]]; then kill "$wm_pid" 2>/dev/null || true; wait "$wm_pid" 2>/dev/null || true; fi
  if [[ -n "$xorg_pid" ]]; then kill "$xorg_pid" 2>/dev/null || true; wait "$xorg_pid" 2>/dev/null || true; fi
}
trap cleanup EXIT
Xorg ":$display_number" -config "$out/xorg.conf" -noreset -nolisten tcp \
  -logfile "$out/xorg.log" > "$out/xorg-console.log" 2>&1 &
xorg_pid=$!; export DISPLAY=":$display_number"
display_ready=false
for attempt in {1..100}; do
  if xdpyinfo -display "$DISPLAY" > "$out/display.txt" 2>/dev/null; then display_ready=true; break; fi
  if ! kill -0 "$xorg_pid" 2>/dev/null; then cat "$out/xorg-console.log"; exit 1; fi
  sleep 0.1
done
if [[ "$display_ready" != true ]]; then echo 'Isolated display did not become ready.' >&2; exit 1; fi
xfwm4 --compositor=off > "$out/wm.log" 2>&1 &
wm_pid=$!; wm_ready=false
for attempt in {1..100}; do
  if xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | rg -q 'window id # 0x[1-9a-f]'; then wm_ready=true; break; fi
  if ! kill -0 "$wm_pid" 2>/dev/null; then cat "$out/wm.log"; exit 1; fi
  sleep 0.1
done
if [[ "$wm_ready" != true ]]; then echo 'Isolated window manager did not become ready.' >&2; exit 1; fi
results=()
for theme in ${NATIVE_THEMES:-light dark}; do
  for dimensions in ${NATIVE_DIMENSIONS:-1600x1000 1280x800 1024x768 1024x600}; do
    width=${dimensions%x*}; height=${dimensions#*x}; result="$out/$theme/$dimensions"
    if [[ -e "$result" ]]; then echo "Refusing existing result directory: $result" >&2; exit 1; fi
    mkdir -p "$result/.home"
    timeout --signal=TERM --kill-after=10s 180s java -Djava.awt.headless=false -Dfile.encoding=UTF-8 \
      "-Duser.home=$result/.home" -cp "$classpath" kr.workbench.CompactPrimaryNavigationNative \
      "$result" "$theme" "$width" "$height" > "$result/execution.log" 2>&1 || {
        cat "$result/execution.log"; exit 1;
      }
    tail -1 "$result/execution.log"
    results+=("$result/verification.json")
  done
done
python3 - "$out" "$jar" "${results[@]}" <<'PY'
from pathlib import Path
import hashlib, json, struct, sys, zlib

out, jar = map(Path, sys.argv[1:3])
def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024*1024), b""):
            digest.update(chunk)
    return digest.hexdigest()
def png_size(path):
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Invalid PNG signature: " + str(path))
    offset, size, ended = 8, None, False
    while offset < len(data):
        length = struct.unpack(">I", data[offset:offset+4])[0]
        kind = data[offset+4:offset+8]
        content = data[offset+8:offset+8+length]
        expected = struct.unpack(">I",data[offset+8+length:offset+12+length])[0]
        if zlib.crc32(kind+content) & 0xffffffff != expected:
            raise ValueError("PNG CRC mismatch: " + str(path))
        if kind == b"IHDR":
            size = list(struct.unpack(">II",content[:8]))
        offset += length+12
        if kind == b"IEND":
            ended = True
            break
    if not ended or size is None or offset != len(data):
        raise ValueError("Incomplete PNG: " + str(path))
    return size
jar_sha = sha(jar)
assert (out/"artifact.sha256").read_text().split()[0] == jar_sha, "JAR changed during native matrix"
cases = []
for result in map(Path,sys.argv[3:]):
    data = json.loads(result.read_text())
    assert data["result"] == "passed" and data["version"] == "1.4.21"
    assert data["jarSha256"] == jar_sha and data["checks"] > 0
    assert data["targetHttpRequests"] == 0 and data["inspectionStarted"] is False
    assert data["nativeSwing"] is True and data["nativeBurpGui"] is False
    expected = data["captures"]
    pictures = {p.name:p for p in result.parent.glob("*.png")}
    assert pictures.keys() == expected.keys(), "Missing or stale PNG in " + str(result.parent)
    hashes = {}
    for name,path in pictures.items():
        assert png_size(path) == expected[name], str(path)
        hashes[name] = sha(path)
    cases.append({
        "theme":data["theme"],"windowSize":str(data["windowWidth"])+"x"+str(data["windowHeight"]),
        "checks":data["checks"],"geometry":data["primaryGeometry"],
        "evidence":str(result.relative_to(out)),"capturedPngSha256":hashes,
        "capturedPngDimensions":expected,
    })
assert cases
summary = {
    "result":"passed","version":"1.4.21","jarSha256":jar_sha,
    "checks":sum(case["checks"] for case in cases),"configurations":cases,
    "captureCount":sum(len(case["capturedPngSha256"]) for case in cases),
    "nativeSwing":True,"nativeBurpGui":False,"syntheticPackets":True,
    "targetHttpRequests":0,"inspectionStarted":False,
    "scope":"Actual compact primary rows, physical click/Tab/Space/Enter/history, retained preparation and dashboard state, lower-tree scrolling, sidebar and theme on isolated Xorg/xfwm4.",
}
(out/"verification.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2)+"\n")
print(json.dumps({key:value for key,value in summary.items() if key!="configurations"},ensure_ascii=False))
PY
