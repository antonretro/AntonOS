#!/usr/bin/env bash
# Install Retro Launcher on the box. ALWAYS use this, never a bare `adb install`:
# Android switches the key helper (the Xbox button / exit panel) off when the app is reinstalled, and a box
# without it can trap you in a game. This puts it back and fails loudly if it isn't running afterwards.
#   bash launcher/tools/install.sh [device] [apk]
set -u
[ -f "$(dirname "$0")/box.env" ] && . "$(dirname "$0")/box.env"   # your box address and adb, kept out of git
DEV="${1:-${BOX:?set BOX=<box-ip>:5555 in tools/box.env}}"
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="${2:-$HERE/../app/build/outputs/apk/release/app-release.apk}"
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

# put the key helper back: keep any other services that were on, add ours once
cur=$(a "settings get secure enabled_accessibility_services" | tr -d '\r')
[ "$cur" = "null" ] && cur=""
case ":$cur:" in *":$ME:"*) new="$cur";; *) new="${cur:+$cur:}$ME";; esac
a "settings put secure enabled_accessibility_services '$new'; settings put secure accessibility_enabled 1"

bound=no
for i in $(seq 1 15); do
  if a "dumpsys accessibility" | grep "services:{Service" | grep -q -E "label=(Retro Launcher buttons|com.antonretro.launcher.RetroKeys)"; then bound=yes; break; fi
  sleep 1
done
if [ $bound = yes ]; then echo "PASS  key helper switched on and running"; else echo "FAIL  key helper is NOT running: the Xbox button won't get anyone out of a game"; exit 2; fi

# ahead-of-time compile for speed (runs on the box; the launcher keeps working meanwhile)
a "cmd package compile -m speed -f $PKG >/dev/null 2>&1; echo compiled" | sed 's/^/  /'
# compiling restarts nothing, but check once more that the helper survived it
a "dumpsys accessibility" | grep "services:{Service" | grep -q -E "label=(Retro Launcher buttons|com.antonretro.launcher.RetroKeys)" && echo "PASS  key helper still running" || { echo "FAIL  key helper stopped after compile"; exit 2; }
