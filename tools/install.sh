#!/usr/bin/env bash
# Install Retro Launcher on the box. ALWAYS use this, never a bare `adb install`:
# Android switches the key helper (the Xbox button / exit panel) off when the app is reinstalled, and a box
# without it can trap you in a game. This puts it back and fails loudly if it isn't running afterwards.
#   bash tools/install.sh [device] [apk]
set -u
[ -f "$(dirname "$0")/box.env" ] && . "$(dirname "$0")/box.env"   # your box address and adb, kept out of git
DEV="${1:-${BOX:?set BOX=<box-ip>:5555 in tools/box.env}}"
HERE="$(cd "$(dirname "$0")" && pwd)"
# builds now go to D: (tools/build.sh); fall back to the old place on C:
APK_D="/d/rl-build/out/app/outputs/apk/release/app-release.apk"
APK="${2:-$([ -f "$APK_D" ] && echo "$APK_D" || echo "$HERE/../app/build/outputs/apk/release/app-release.apk")}"
ADB="${ADB:-adb}"
# Windows adb needs a Windows path for the file (path conversion is off for the shell commands below)
command -v cygpath >/dev/null 2>&1 && APK="$(cygpath -w "$APK")"
export MSYS_NO_PATHCONV=1
PKG=com.antonretro.launcher
ME=$PKG/$PKG.RetroKeys
a() { timeout 120 "$ADB" -s "$DEV" shell "$@"; }

timeout 20 "$ADB" connect "$DEV" >/dev/null 2>&1
echo "Installing $(basename "$APK")"
out=$(timeout 180 "$ADB" -s "$DEV" install -r "$APK" 2>&1 | tail -1)
echo "  $out"
case "$out" in *Success*) ;; *) echo "FAIL  install"; exit 1;; esac

# 1. ahead-of-time compile FIRST: it kills the app process, and the key helper lives in that process.
#    (Compiling after switching the helper on left Android holding a dead helper that swallowed every remote key.)
a "cmd package compile -m speed -f $PKG >/dev/null 2>&1; echo compiled" | sed 's/^/  /'

# the music visualizer reads the music player's own output; Android files that under the microphone permission
a "pm grant $PKG android.permission.RECORD_AUDIO" 2>/dev/null

# 2. installing is the start of a test session: sound off before anything can play (the chime plays on the next start)
bash "$HERE/mute.sh" "$DEV" | sed "s/^/  /"

# 3. key helper: off, then on, so Android always makes a fresh connection (keep any other services that were on)
LOG=/sdcard/Android/data/$PKG/files/perf.log
cur=$(a "settings get secure enabled_accessibility_services" | tr -d '\r')
[ "$cur" = "null" ] && cur=""
others=$(echo "$cur" | tr ':' '\n' | grep -v -i "antonretro" | paste -sd: -)
before=$(a "cat $LOG 2>/dev/null | wc -l" | tr -d '\r ')
a "settings put secure enabled_accessibility_services '${others}'"; sleep 1
a "settings put secure enabled_accessibility_services '${others:+$others:}$ME'; settings put secure accessibility_enabled 1"

# 4. wait for the helper's own "connected" line
ok=no
for i in $(seq 1 20); do
  if a "tail -n +$((before+1)) $LOG 2>/dev/null" | grep -q "keys helper connected"; then ok=yes; break; fi
  sleep 1
done
[ $ok = yes ] || { echo "FAIL  key helper did not connect: the Xbox button won't get anyone out of a game"; exit 2; }

# 5. prove a REAL remote key gets through the helper (Volume Down: harmless while muted; the saved level comes back on unmute)
n=$(a "cat $LOG | wc -l" | tr -d '\r ')
a "sendevent /dev/input/event0 1 114 1; sendevent /dev/input/event0 0 0 0; sendevent /dev/input/event0 1 114 0; sendevent /dev/input/event0 0 0 0"; sleep 2
if a "tail -n +$((n+1)) $LOG" | grep -q "keys helper saw 25"; then echo "PASS  key helper connected and real remote keys pass through it"
else echo "FAIL  remote keys are NOT reaching the helper: switching it off so the remote keeps working"; a "settings put secure enabled_accessibility_services '${others}'"; exit 3; fi
