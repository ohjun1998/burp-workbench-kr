#!/usr/bin/env bash
set -euo pipefail
repository=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repository"
out=${NATIVE_OUT:-$repository/tmp/grouped-tools-native}
mkdir -p "$out/classes" "$out/.cache" "$out/.config"
out=$(cd "$out" && pwd)
export XDG_CACHE_HOME="$out/.cache" XDG_CONFIG_HOME="$out/.config"
if [[ "${GROUPED_TOOLS_NATIVE_DBUS_READY:-}" != 1 ]]; then
  exec dbus-run-session -- env GROUPED_TOOLS_NATIVE_DBUS_READY=1 bash "$0" "$@"
fi
version=$(python3 -c 'import sys;sys.path.insert(0,"simple/burp-workbench");from release_info import VERSION;print(VERSION)')
jar=${NATIVE_JAR:-$repository/simple/burp-workbench/dist/burp-workbench-simple-$version.jar}
runtime=${NATIVE_JAVA:-java}
test -f "$jar"
sha256sum "$jar" > "$out/artifact.sha256"
classpath="$jar:$repository/simple/burp-workbench/build/tests"
for dependency in "$repository"/simple/burp-workbench/deps/*.jar; do classpath="$classpath:$dependency"; done
"$runtime" com.sun.tools.javac.Main --release 17 -encoding UTF-8 -cp "$classpath" \
  -d "$out/classes" tools/HeaderToolsNative.java tools/GroupedToolsNavigationNative.java
classpath="$out/classes:$classpath"
display_number=${NATIVE_DISPLAY_NUMBER:-91}
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
wm_pid=$!
wm_ready=false
for attempt in {1..100}; do
  if xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | rg -q 'window id # 0x[1-9a-f]'; then wm_ready=true; break; fi
  if ! kill -0 "$wm_pid" 2>/dev/null; then cat "$out/wm.log"; exit 1; fi
  sleep 0.1
done
if [[ "$wm_ready" != true ]]; then echo 'Isolated window manager did not become ready.' >&2; exit 1; fi
results=()
for theme in ${NATIVE_THEMES:-light dark}; do
  for dimensions in ${NATIVE_DIMENSIONS:-1600x1000 1280x800 1024x768}; do
    width=${dimensions%x*}; height=${dimensions#*x}; result="$out/$theme/$dimensions"
    if [[ -e "$result" ]]; then echo "Refusing existing result directory: $result" >&2; exit 1; fi
    mkdir -p "$result/.home"
    timeout --signal=TERM --kill-after=10s 180s "$runtime" -Djava.awt.headless=false -Dfile.encoding=UTF-8 "-Dqa.lookAndFeel=${NATIVE_LAF:-metal}" \
      "-Duser.home=$result/.home" -cp "$classpath" kr.workbench.GroupedToolsNavigationNative \
      "$result" "$theme" "$width" "$height" > "$result/execution.log" 2>&1 || {
        cat "$result/execution.log"; exit 1;
      }
    tail -1 "$result/execution.log"
    results+=("$result/verification.json")
  done
done
python3 - "$out" "$jar" "${results[@]}" <<'PY'
from pathlib import Path
import hashlib, json, sys

out, jar = map(Path, sys.argv[1:3])
jar_sha = hashlib.sha256(jar.read_bytes()).hexdigest()
assert (out/'artifact.sha256').read_text().split()[0] == jar_sha
cases = [json.loads(Path(name).read_text()) for name in sys.argv[3:]]
for case in cases:
    assert case['result'] == 'passed' and case['checks'] > 0
    assert case['jarSha256'] == jar_sha and case['targetHttpRequests'] == 0
    assert case['nativeSwing'] and not case['nativeBurpGui']
    assert case['inspectionStarted'] is False and case['syntheticSnapshot'] is True
    assert len(case['captures']) == 13
summary = {'result':'passed','jarSha256':jar_sha,'checks':sum(c['checks'] for c in cases),
           'configurations':cases,'captureCount':sum(len(c['captures']) for c in cases),'nativeSwing':True,
           'nativeBurpGui':False,'syntheticPackets':True,'targetHttpRequests':0}
(out/'verification.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n')
print(json.dumps({k:v for k,v in summary.items() if k!='configurations'}, ensure_ascii=False))
PY
