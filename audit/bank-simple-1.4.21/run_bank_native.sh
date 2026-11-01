#!/usr/bin/env bash
set -euo pipefail
# One immutable-JAR run against the already running loopback bank. All raw data stays outside Git.
repository=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$repository"
if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo 'Usage: bash audit/bank-simple-1.4.21/run_bank_native.sh PRIVATE_STATE NEW_RUN_DIRECTORY [DISPLAY_NUMBER]' >&2
  exit 2
fi
state=$(realpath "$1")
run=$(realpath -m "$2")
case "$run" in "$repository"|"$repository"/*)
  echo 'Raw authenticated results must be written outside the Git checkout.' >&2; exit 2;;
esac
display_number=${3:-94}
if [[ ! "$display_number" =~ ^[0-9]+$ ]]; then echo 'Display number must be numeric.' >&2; exit 2; fi
mkdir -p "$run/.cache" "$run/.config"
chmod 700 "$run"
export XDG_CACHE_HOME="$run/.cache" XDG_CONFIG_HOME="$run/.config"
# Service activation must inherit the writable XDG paths before starting D-Bus.
if [[ "${BANK_NATIVE_DBUS_READY:-}" != 1 ]]; then
  exec dbus-run-session -- env BANK_NATIVE_DBUS_READY=1 bash "$0" "$@"
fi
if [[ -e "$run/.bank-native-run-started" ]]; then echo "Preserve previous output; choose a fresh run directory: $run" >&2; exit 1; fi
touch "$run/.bank-native-run-started"
java=${BANK_JAVA:-/workspace/tools/burp-workbench/jdk-17.0.21+9/bin/java}
javac=${BANK_JAVAC:-/workspace/tools/burp-workbench/jdk-17.0.21+9/bin/javac}
jar="$repository/simple/downloads/1.4.21/burp-workbench-simple-1.4.21.jar"
expected=9d3d20af946add3498da79a021be31fafe8d84e046352463c4a559616187301c
actual=$(sha256sum "$jar"); actual=${actual%% *}
if [[ "$actual" != "$expected" ]]; then echo 'Simple 1.4.21 artifact SHA-256 differs.' >&2; exit 1; fi
printf '%s  %s\n' "$actual" "$jar" > "$run/artifact.sha256"
test -f "$state"
classpath="$jar"
for dependency in "$repository"/simple/burp-workbench/deps/*.jar; do classpath="$classpath:$dependency"; done
mkdir -p "$run/.classes" "$run/.adapter-classes" "$run/.home" "$run/private-results" "$run/public-results" "$run/images/workbench"
adapter_sources=${BANK_ADAPTER_SOURCES:-$repository/simple/burp-workbench/src/test/java/kr/workbench}
adapters=(SimpleWorkflowTest.java WorkbenchTest.java PayloadIntegrationTest.java ScopeInspectionFixture.java)
adapter_files=()
for adapter in "${adapters[@]}"; do adapter_files+=("$adapter_sources/$adapter"); done
"$javac" --release 17 -encoding UTF-8 -cp "$classpath" -d "$run/.adapter-classes" \
  "${adapter_files[@]}" > "$run/adapter-compile.log" 2>&1 || {
    cat "$run/adapter-compile.log"; exit 1;
  }
python3 - "$run" "${adapter_files[@]}" <<'PY'
import hashlib,json,sys
from pathlib import Path
files={Path(name).name:hashlib.sha256(Path(name).read_bytes()).hexdigest() for name in sys.argv[2:]}
(Path(sys.argv[1])/'adapter-sources.json').write_text(json.dumps({
    'toolchain':'strict-release17','sources':files,'usesPrebuiltTestClasses':False,
},indent=2)+'\n')
PY
classpath="$classpath:$run/.adapter-classes"
main=${BANK_MAIN:-kr.workbench.BankSimpleAudit}
sources=(audit/bank-simple-1.4.21/BankSimpleAudit.java audit/bank-simple-1.4.21/BankWorkbenchCapture.java)
case "$main" in
  kr.workbench.BankSimpleAudit) ;;
  kr.workbench.BankBusinessAudit) sources+=(audit/bank-simple-1.4.21/BankBusinessAudit.java) ;;
  *) echo 'BANK_MAIN must name one of the two bank audit entry points.' >&2; exit 2;;
esac
"$javac" --release 17 -encoding UTF-8 -cp "$classpath" -d "$run/.classes" \
  "${sources[@]}" > "$run/compile.log" 2>&1 || {
    cat "$run/compile.log"; exit 1;
  }
classpath="$run/.classes:$classpath"
if [[ -S "/tmp/.X11-unix/X$display_number" || -e "/tmp/.X$display_number-lock" ]]; then
  echo "Display :$display_number is already in use; choose another display." >&2; exit 1
fi
cat > "$run/xorg.conf" <<'XORG'
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
Xorg ":$display_number" -config "$run/xorg.conf" -noreset -nolisten tcp \
  -logfile "$run/xorg.log" > "$run/xorg-console.log" 2>&1 &
xorg_pid=$!; export DISPLAY=":$display_number"
display_ready=false
for attempt in {1..100}; do
  if xdpyinfo -display "$DISPLAY" > "$run/display.txt" 2>/dev/null; then display_ready=true; break; fi
  if ! kill -0 "$xorg_pid" 2>/dev/null; then cat "$run/xorg-console.log"; exit 1; fi
  sleep 0.1
done
if [[ "$display_ready" != true ]]; then echo 'Isolated Xorg did not become ready.' >&2; exit 1; fi
xfwm4 --compositor=off > "$run/wm.log" 2>&1 &
wm_pid=$!; wm_ready=false
for attempt in {1..100}; do
  if xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | rg -q 'window id # 0x[1-9a-f]'; then wm_ready=true; break; fi
  if ! kill -0 "$wm_pid" 2>/dev/null; then cat "$run/wm.log"; exit 1; fi
  sleep 0.1
done
if [[ "$wm_ready" != true ]]; then echo 'Isolated window manager did not become ready.' >&2; exit 1; fi
set +e
timeout --signal=TERM --kill-after=10s 600s "$java" -Djava.awt.headless=false -Dfile.encoding=UTF-8 \
  "-Duser.home=$run/.home" -cp "$classpath" "$main" \
  "$state" "$run/private-results" "$run/public-results" "$run/images/workbench" \
  > "$run/execution.log" 2>&1
result=$?
set -e
after=$(sha256sum "$jar"); after=${after%% *}
if [[ "$after" != "$expected" ]]; then echo 'Artifact changed during execution.' >&2; exit 1; fi
python3 - "$run" "$jar" "$actual" "$result" <<'PY'
import json,sys
from pathlib import Path
output=Path(sys.argv[1])
(output/'runtime.json').write_text(json.dumps({
    'version':'1.4.21','jar':sys.argv[2],'jarSha256':sys.argv[3],
    'artifactUnchanged':True,'exitCode':int(sys.argv[4]),
    'nativeSwing':True,'nativeBurpGui':False,
    'scope':'Released Workbench Simple JAR on isolated Linux Xorg/xfwm4 with an actual loopback-bank HTTP adapter.',
},indent=2)+'\n')
PY
if [[ "$result" != 0 ]]; then echo "Bank run failed; inspect private execution.log in $run" >&2; exit "$result"; fi
printf 'Bank run completed: %s\n' "$run"
