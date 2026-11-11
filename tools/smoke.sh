#!/usr/bin/env bash
# Retro Launcher smoke test: run after every install, before telling anyone it's ready.
#   bash launcher/tools/smoke.sh [device] [--game]
# Checks: the launcher starts without crashing, it is the Home app, Home brings it back after it was
# killed (the "killed behind a game" case), and (with --game, sound muted) Home from inside a RetroArch
# game returns to it three times in a row. Exit code 0 = all passed.
set -u
[ -f "$(dirname "$0")/box.env" ] && . "$(dirname "$0")/box.env"   # your box address and adb, kept out of git
DEV="${1:-${BOX:?set BOX=<box-ip>:5555 in tools/box.env}}"
ADB="${ADB:-adb}"
export MSYS_NO_PATHCONV=1
a() { timeout 60 "$ADB" -s "$DEV" shell "$@"; }
PKG=com.antonretro.launcher; ACT=$PKG/.HomeActivity
fails=0
check() { if [ "$2" = ok ]; then echo "PASS  $1"; else echo "FAIL  $1 ($2)"; fails=$((fails+1)); fi; }
focus() { a dumpsys window | grep mCurrentFocus | head -1; }
crashed() { a "logcat -d -b crash 2>/dev/null; logcat -d" | grep -q "FATAL EXCEPTION" && echo yes || echo no; }

a "logcat -c"
a "cmd package set-home-activity $ACT" >/dev/null 2>&1
home=$(a "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" | tail -1 | tr -d '\r')
check "Retro Launcher is the Home app" "$([ "$home" = "$ACT" ] && echo ok || echo "Home goes to $home")"

a "am force-stop $PKG"; sleep 1
a "am start -W -n $ACT" >/dev/null; sleep 3
check "cold start shows Retro Launcher" "$(focus | grep -q $PKG && echo ok || echo "$(focus)")"
check "no crash at start" "$([ "$(crashed)" = no ] && echo ok || echo crashed)"

# killed behind another app, then Home (what happens when memory is short during a game)
a "am force-stop $PKG; input keyevent 3"; sleep 4
check "Home after the launcher was killed" "$(focus | grep -q $PKG && echo ok || echo "$(focus)")"
check "still no crash" "$([ "$(crashed)" = no ] && echo ok || echo crashed)"

if [ "${2:-}" = "--game" ]; then
  # a copy with a plain name (brackets in game names trip the shell); hidden folders aren't shown in Games
  a 'mkdir -p /sdcard/RetroLauncher/.smoke; for f in /sdcard/RetroLauncher/Games/gb/*.gb; do cp "$f" /sdcard/RetroLauncher/.smoke/smoke.gb; break; done'
  ROM=/sdcard/RetroLauncher/.smoke/smoke.gb
  CFG=/sdcard/RetroLauncher/retroarch.cfg
  a "cp $CFG $CFG.smokebak; echo 'audio_mute_enable = \"true\"' >> $CFG"
  for i in 1 2 3; do
    a "am force-stop com.retroarch.ra32"; sleep 1   # what the launcher does before switching games
    a "am start -n com.retroarch.ra32/com.retroarch.browser.retroactivity.RetroActivityFuture -e ROM '$ROM' -e LIBRETRO /data/data/com.retroarch.ra32/cores/gambatte_libretro_android.so -e CONFIGFILE $CFG" >/dev/null
    sleep 8
    ingame=$(focus | grep -q retroarch && echo ok || echo "game didn't open: $(focus)")
    a "input keyevent 3"; sleep 4
    check "game $i: Home returns to Retro Launcher" "$( [ "$ingame" = ok ] && (focus | grep -q $PKG && echo ok || echo "$(focus)") || echo "$ingame")"
  done
  a "mv $CFG.smokebak $CFG"
  check "no crash after games" "$([ "$(crashed)" = no ] && echo ok || echo crashed)"
fi
echo "$fails failure(s)"
exit $fails
