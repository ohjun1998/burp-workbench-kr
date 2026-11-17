#!/usr/bin/env bash
set -euo pipefail
# The managed cloud instance provides this optional activation file.
if [ -f /workspace/.dev-tools/activate-burp-workbench.sh ]; then
  source /workspace/.dev-tools/activate-burp-workbench.sh
fi
repository=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$repository"
out=${NATIVE_OUT:-$repository/tmp/pdf-alignment/native}
mkdir -p "$out/classes" "$out/.cache" "$out/.config"
export XDG_CACHE_HOME="$out/.cache"
export XDG_CONFIG_HOME="$out/.config"
version=$(python -c 'import sys; sys.path.insert(0,"simple/burp-workbench"); from release_info import VERSION; print(VERSION)')
jar="$repository/simple/burp-workbench/dist/burp-workbench-simple-$version.jar"
test -f "$jar"
test -d simple/burp-workbench/build/tests
classpath="$jar:$repository/simple/burp-workbench/build/tests"
for dependency in "$repository"/simple/burp-workbench/deps/*.jar; do classpath="$classpath:$dependency"; done
javac --release 17 -encoding UTF-8 -cp "$classpath" -d "$out/classes" \
  simple/tools/ButtonPlacementNative.java simple/tools/WorkbenchWindowNative.java simple/tools/VisualGuideNative.java
classpath="$out/classes:$classpath"
display_number=${NATIVE_DISPLAY_NUMBER:-91}
if [ -S "/tmp/.X11-unix/X$display_number" ]; then
  echo "Display :$display_number is already owned by another process; choose NATIVE_DISPLAY_NUMBER." >&2
  exit 1
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
xorg_pid=
wm_pid=
cleanup() {
  if [ -n "$wm_pid" ]; then kill "$wm_pid" 2>/dev/null || true; wait "$wm_pid" 2>/dev/null || true; fi
  if [ -n "$xorg_pid" ]; then kill "$xorg_pid" 2>/dev/null || true; wait "$xorg_pid" 2>/dev/null || true; fi
}
trap cleanup EXIT
Xorg ":$display_number" -config "$out/xorg.conf" -noreset -nolisten tcp -logfile "$out/xorg.log" > "$out/xorg-console.log" 2>&1 &
xorg_pid=$!
export DISPLAY=":$display_number"
display_ready=false
for attempt in {1..100}; do
  if xdpyinfo -display "$DISPLAY" > "$out/display.txt" 2>/dev/null; then display_ready=true; break; fi
  if ! kill -0 "$xorg_pid" 2>/dev/null; then cat "$out/xorg-console.log"; exit 1; fi
  sleep 0.1
done
if [ "$display_ready" != true ]; then echo 'Isolated display did not become ready.' >&2; exit 1; fi
xfwm4 --compositor=off > "$out/wm.log" 2>&1 &
wm_pid=$!
wm_ready=false
for attempt in {1..100}; do
  if xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | rg -q 'window id # 0x[1-9a-f]'; then wm_ready=true; break; fi
  if ! kill -0 "$wm_pid" 2>/dev/null; then cat "$out/wm.log"; exit 1; fi
  sleep 0.1
done
if [ "$wm_ready" != true ]; then echo 'Isolated window manager did not become ready.' >&2; exit 1; fi
kind=${1:-all}
selected_theme=${2:-both}
case "$kind" in all) suites=(buttons windows guides);; buttons|windows|guides) suites=("$kind");; *) echo 'Use all, buttons, windows, or guides.' >&2; exit 2;; esac
case "$selected_theme" in both) themes=(light dark);; light|dark) themes=("$selected_theme");; *) echo 'Use light, dark, or both.' >&2; exit 2;; esac
for theme in "${themes[@]}"; do
  for suite in "${suites[@]}"; do
    case "$suite" in buttons) main=ButtonPlacementNative;; windows) main=WorkbenchWindowNative;; guides) main=VisualGuideNative;; esac
    result="$out/$suite/$theme"
    mkdir -p "$result/.home"
    timeout --signal=TERM --kill-after=10s 240s java -Djava.awt.headless=false -Dfile.encoding=UTF-8 \
      "-Duser.home=$result/.home" -cp "$classpath" "kr.workbench.$main" "$result" "$theme" \
      2>&1 | tee "$result/execution.log"
  done
done
