#!/usr/bin/env bash
# AntonOS smoke test: run after every install, before telling anyone it's ready.
#   bash tools/smoke.sh [device] [--game]
# Checks: the key helper (exit button) is running, the launcher starts without crashing, it is the Home app, Home brings it back after it was
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

# sound off for the whole run (games, chime, TV); it stays off until tools/unmute.sh at the end of the session
trap 'bash "$(dirname "$0")/unmute.sh" "$DEV" >/dev/null 2>&1' EXIT   # sound always comes back, even if this stops early
bash "$(dirname "$0")/mute.sh" "$DEV" || { echo "FAIL  could not mute the box: not testing with sound on"; exit 9; }
a "logcat -c"
bound() { a "dumpsys accessibility" | grep -q "services:{Service[label=(AntonOS|com.antonretro.launcher.RetroKeys)" && echo ok || echo "not running: the Xbox button can't get anyone out of a game"; }
check "key helper running" "$(bound)"
# the helper must pass REAL remote keys on (a stale helper once swallowed every key and left the remote dead)
LOG=/sdcard/Android/data/$PKG/files/perf.log
realkey() { n=$(a "cat $LOG | wc -l" | tr -d '
 '); a "sendevent /dev/input/event0 1 114 1; sendevent /dev/input/event0 0 0 0; sendevent /dev/input/event0 1 114 0; sendevent /dev/input/event0 0 0 0"; sleep 2; a "tail -n +$((n+1)) $LOG" | grep -q "keys helper saw 25" && echo ok || echo "a real remote key did not get through"; }
check "real remote keys pass through the key helper" "$(realkey)"
a "cmd package set-home-activity $ACT" >/dev/null 2>&1
home=$(a "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" | tail -1 | tr -d '\r')
check "AntonOS is the Home app" "$([ "$home" = "$ACT" ] && echo ok || echo "Home goes to $home")"

# never force-stop the launcher here: Android then switches its key helper off (accessibility rule).
# "am kill" while another app is in front is what a low-memory close looks like.
a "am start -n com.android.settings/.Settings" >/dev/null; sleep 2; a "am kill $PKG"; sleep 1
a "am start -W -n $ACT" >/dev/null; sleep 3
check "cold start shows AntonOS" "$(focus | grep -q $PKG && echo ok || echo "$(focus)")"
# every home style must survive a start-up with it already chosen (Box classic once crashed AntonOS on every start).
# "test:recreate" runs AntonOS's start-up again in the same process, so the key helper stays on.
for style in 1 2 3 0; do
  a "am start -n $PKG/.HomeActivity -a android.intent.action.MAIN --es rl_open test:style:$style" >/dev/null; sleep 2
  a "am start -n $PKG/.HomeActivity -a android.intent.action.MAIN --es rl_open test:recreate" >/dev/null; sleep 6
  check "start-up with home style $style" "$([ "$(crashed)" = no ] && focus | grep -q "$PKG.HomeActivity" && echo ok || echo "$(focus)")"
done
check "no crash at start" "$([ "$(crashed)" = no ] && echo ok || echo crashed)"

# killed behind another app, then Home (what happens when memory is short during a game)
a "am start -n com.android.settings/.Settings" >/dev/null; sleep 2; a "am kill $PKG; input keyevent 3"; sleep 5
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
    check "game $i: Home returns to AntonOS" "$( [ "$ingame" = ok ] && (focus | grep -q $PKG && echo ok || echo "$(focus)") || echo "$ingame")"
  done
  # the Xbox pad's Guide button, sent as a real device press (the key helper ignores simulated keys)
  PAD=$(a "getevent -p 2>/dev/null" | tr -d '
' | awk '/^add device/{d=$4} /X-Box|Xbox|xbox|Gamepad|Controller/{print d; exit}')
  if [ -n "$PAD" ]; then
    press() { a "sendevent $PAD 1 $1 1; sendevent $PAD 0 0 0; sendevent $PAD 1 $1 0; sendevent $PAD 0 0 0"; }
    a "am force-stop com.retroarch.ra32"; a "am start -n com.retroarch.ra32/com.retroarch.browser.retroactivity.RetroActivityFuture -e ROM '$ROM' -e LIBRETRO /data/data/com.retroarch.ra32/cores/gambatte_libretro_android.so -e CONFIGFILE $CFG" >/dev/null; sleep 8
    press 316; sleep 2; check "pad Guide in a game opens the game Home Menu" "$(focus | grep -q "$PKG.HomeActivity" && echo ok || echo "$(focus)")"
    press 305; sleep 2; check "pad B (Back on the menu) resumes the game" "$(focus | grep -q retroarch && echo ok || echo "$(focus)")"
    press 316; sleep 3; press 316; sleep 3; check "pad Guide twice in a game: menu, then back to the game" "$(focus | grep -q retroarch && echo ok || echo "$(focus)")"
    # a pad without a Guide button: Select + Start
    a "am start -n com.retroarch.ra32/com.retroarch.browser.retroactivity.RetroActivityFuture -e ROM '$ROM' -e LIBRETRO /data/data/com.retroarch.ra32/cores/gambatte_libretro_android.so -e CONFIGFILE $CFG" >/dev/null; sleep 6
    a "sendevent $PAD 1 314 1; sendevent $PAD 0 0 0; sendevent $PAD 1 315 1; sendevent $PAD 0 0 0; sendevent $PAD 1 315 0; sendevent $PAD 0 0 0; sendevent $PAD 1 314 0; sendevent $PAD 0 0 0"; sleep 2
    check "pad Select+Start in a game quits to the game Home Menu" "$(focus | grep -q "$PKG.HomeActivity" && echo ok || echo "$(focus)")"
    a "input keyevent 3"; sleep 3; check "Home from the game" "$(focus | grep -q $PKG/ && echo ok || echo "$(focus)")"
    # outside games: Guide over Android's own Settings
    a "am start -n com.android.settings/.Settings" >/dev/null; sleep 3
    press 316; sleep 2; check "pad Guide over Settings opens the quick panel" "$(focus | grep -q QuickPanel && echo ok || echo "$(focus)")"
    press 304; sleep 3; check "then A (Go Home) goes Home" "$(focus | grep -q "$PKG.HomeActivity" && echo ok || echo "$(focus)")"
  else echo "SKIP  no pad connected: Guide checks"; fi
  # the remote (always connected): real hardware presses on the IR keypad, which the key helper does see
  KP=/dev/input/event0
  rk() { a "sendevent $KP 1 $1 1; sendevent $KP 0 0 0; sleep ${2:-0}; sendevent $KP 1 $1 0; sendevent $KP 0 0 0"; }
  a "am start -n com.retroarch.ra32/com.retroarch.browser.retroactivity.RetroActivityFuture -e ROM '$ROM' -e LIBRETRO /data/data/com.retroarch.ra32/cores/gambatte_libretro_android.so -e CONFIGFILE $CFG" >/dev/null; sleep 8
  rk 158; sleep 2; check "remote Back in a game opens the game Home Menu" "$(focus | grep -q "$PKG.HomeActivity" && echo ok || echo "$(focus)")"
  rk 158; sleep 2; check "Back on the menu returns to the game" "$(focus | grep -q retroarch && echo ok || echo "$(focus)")"
  rk 102; sleep 3; check "remote Home from the game" "$(focus | grep -q $PKG/ && echo ok || echo "$(focus)")"
  a "input keyevent 4"; sleep 1
  a "am start -n com.android.settings/.Settings" >/dev/null; sleep 3
  rk 158 1.4; sleep 2; check "hold Back over Settings opens the quick panel" "$(focus | grep -q QuickPanel && echo ok || echo "$(focus)")"
  a "input keyevent 23"; sleep 3; check "then OK (Go Home) goes Home" "$(focus | grep -q "$PKG.HomeActivity" && echo ok || echo "$(focus)")"
  a "am force-stop com.retroarch.ra32"
  a "mv $CFG.smokebak $CFG"
  check "key helper still running" "$(bound)"
  check "no crash after games" "$([ "$(crashed)" = no ] && echo ok || echo crashed)"
fi
echo "$fails failure(s)"
echo "NOTE  the box is still muted: run tools/unmute.sh when testing is finished"
exit $fails
