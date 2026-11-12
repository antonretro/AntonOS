#!/usr/bin/env bash
# Frame-drop check on Home: the speedcheck key sequence (Right x6, Down x2, Up x2, Left x6), three runs,
# prints janky % and HWUI's "slow" counters per run. Box must be on Home and nobody using it.
#   bash tools/jankcheck.sh [device]
set -u
[ -f "$(dirname "$0")/box.env" ] && . "$(dirname "$0")/box.env"
DEV="${1:-${BOX:?set BOX=<box-ip>:5555 in tools/box.env}}"
ADB="${ADB:-adb}"; export MSYS_NO_PATHCONV=1
PKG=com.antonretro.launcher
for run in 1 2 3; do
  timeout 60 "$ADB" -s "$DEV" shell "input keyevent KEYCODE_HOME" >/dev/null; sleep 2
  out=$(timeout 120 "$ADB" -s "$DEV" shell "dumpsys gfxinfo $PKG reset >/dev/null; for k in 22 22 22 22 22 22 20 20 19 19 21 21 21 21 21 21; do input keyevent \$k; sleep 0.25; done; dumpsys gfxinfo $PKG")
  echo "run $run: $(echo "$out" | grep -oE 'Janky frames: [0-9]+ \([0-9.]+%\)')  p90 $(echo "$out" | grep -oE '90th percentile: [0-9]+ms' | grep -oE '[0-9]+ms')  slowUI $(echo "$out" | grep -oE 'Slow UI thread: [0-9]+' | grep -oE '[0-9]+$')  slowDraw $(echo "$out" | grep -oE 'Slow issue draw commands: [0-9]+' | grep -oE '[0-9]+$')  slowBitmap $(echo "$out" | grep -oE 'Slow bitmap uploads: [0-9]+' | grep -oE '[0-9]+$')"
done
