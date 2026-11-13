#!/usr/bin/env bash
# Speed experiment: run the dock<->rows move with some of RetroOS's test switches on (kit/Kit.kt perfFlags).
#   bash tools/perfflag.sh "<flags>" [device]      e.g. "noglass nofade"   ("" = normal)
set -u
[ -f "$(dirname "$0")/box.env" ] && . "$(dirname "$0")/box.env"
DEV="${2:-${BOX:?set BOX=<box-ip>:5555 in tools/box.env}}"
ADB="${ADB:-adb}"; export MSYS_NO_PATHCONV=1
a() { timeout 120 "$ADB" -s "$DEV" shell "$@"; }
a "echo '$1' > /sdcard/RetroLauncher/.perf-flags; am force-stop com.antonretro.launcher; am start -n com.antonretro.launcher/.HomeActivity" >/dev/null
sleep 7
a "settings put secure enabled_accessibility_services com.antonretro.launcher/com.antonretro.launcher.RetroKeys; settings put secure accessibility_enabled 1"
sleep 2
for r in 1 2; do
  a "input keyevent KEYCODE_HOME" >/dev/null; sleep 2
  out=$(a "dumpsys gfxinfo com.antonretro.launcher reset >/dev/null; for k in 20 19 20 19 20 19 20 19; do input keyevent \$k; sleep 0.3; done; dumpsys gfxinfo com.antonretro.launcher")
  echo "[${1:-normal}] $(echo "$out" | grep -oE 'Janky frames: [0-9]+ \([0-9.]+%\)')  slowDraw $(echo "$out" | grep -oE 'Slow issue draw commands: [0-9]+' | grep -oE '[0-9]+$')"
done
a "rm -f /sdcard/RetroLauncher/.perf-flags"
