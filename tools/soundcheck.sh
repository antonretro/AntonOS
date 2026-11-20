#!/usr/bin/env bash
# One sound at a time: with a song playing, starting anything else must pause the song (with a short fade), so only
# the new thing sounds; going back to the playing song must carry on, not restart. Sound stays muted the whole run.
#   bash tools/soundcheck.sh [device]
set -u
[ -f "$(dirname "$0")/box.env" ] && . "$(dirname "$0")/box.env"
DEV="${1:-${BOX:?set BOX=<box-ip>:5555 in tools/box.env}}"
ADB="${ADB:-adb}"; export MSYS_NO_PATHCONV=1
a() { timeout 60 "$ADB" -s "$DEV" shell "$@"; }
PKG=com.antonretro.launcher; ACT=$PKG/.HomeActivity; LOG=/sdcard/Android/data/$PKG/files/perf.log
fails=0
check() { if [ "$2" = ok ]; then echo "PASS  $1"; else echo "FAIL  $1 ($2)"; fails=$((fails+1)); fi; }
lines() { a "cat $LOG | wc -l" | tr -d '\r '; }
logsince() { a "tail -n +$1 $LOG"; }
# the launcher logs "music playing=true/false" whenever its music starts or stops sounding
musicOn() { a "grep 'music playing=' $LOG | tail -n 1" | grep -q "playing=true" && echo yes || echo no; }
open() { a "am start -n $ACT -a android.intent.action.MAIN --es rl_open '$1'" >/dev/null; }
home() { a "input keyevent KEYCODE_HOME"; sleep 2; }

trap 'bash "$(dirname "$0")/unmute.sh" "$DEV" >/dev/null 2>&1' EXIT   # sound always comes back, even if this stops early
bash "$(dirname "$0")/mute.sh" "$DEV" >/dev/null || { echo "FAIL  could not mute"; exit 9; }

song() { home; open test:song; sleep 4; a "input keyevent KEYCODE_BACK"; sleep 1.5; }

# going back to the playing song carries on
song; sleep 4; n=$(lines); open test:song; sleep 3
logsince "$n" | grep -q "music start" && check "picking the playing song again carries on" "it restarted" || check "picking the playing song again carries on" ok
[ "$(musicOn)" = yes ] && check "the song is still playing after that" ok || check "the song is still playing after that" "it stopped"
a "input keyevent KEYCODE_BACK"; sleep 1

case_run() {   # $1 name, $2 what to start
  song
  [ "$(musicOn)" = yes ] || { check "$1" "the song wasn't playing before the test"; return; }
  n=$(lines); eval "$2"; sleep 6
  local f=no; logsince "$n" | grep -q "music fade-pause" && f=yes
  [ "$(musicOn)" = no ] && check "$1 (faded out: $f)" ok || check "$1" "the song is still playing alongside it"
  # leave whatever started: a RetroArch game through the launcher's own Leave, SmartTube closed, then Home
  a "am start -n $ACT -a android.intent.action.MAIN --es rl_game leave" >/dev/null 2>&1; sleep 2
  a "am force-stop org.smarttube.stable" >/dev/null 2>&1
  a "input keyevent KEYCODE_BACK"; sleep 1; home
}
case_run "TV channel pauses the music"     'open test:tv'
case_run "radio station pauses the music"  'open test:radio'
case_run "video pauses the music"          'open test:video'
case_run "built-in game pauses the music"  'open bi:tiki'
case_run "SmartTube (opened from Home) pauses the music" 'open "app:org.smarttube.stable/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"'
# a game whose file name has no quote in it (quotes would break the command)
r=$(a "ls -d /sdcard/RetroLauncher/Games/*/*" 2>/dev/null | tr -d '' | grep -v "[*']" | head -n 1)
[ -n "$r" ] && case_run "RetroArch game pauses the music" "open 'rom:$r'" || echo "SKIP  RetroArch game (no game file on the box)"

# end with the music paused, so nothing plays when the sound comes back
a "am start -n $ACT -a android.intent.action.MAIN --es rl_open test:tv" >/dev/null 2>&1; sleep 4; a "input keyevent KEYCODE_BACK"; sleep 1; home
echo; [ $fails -eq 0 ] && echo "ALL PASSED" || echo "$fails FAILED"
exit $fails
