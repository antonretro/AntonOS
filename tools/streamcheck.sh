#!/usr/bin/env bash
# Leaving a TV channel for music or a video must really stop the channel's download. AntonOS logs every byte its
# player downloads ("stream bytes" in perf.log, test mode). For each "Back while watching TV" setting and for a song
# and a video afterwards: the count must not grow in the 10 s after leaving. Sound stays muted.
#   bash tools/streamcheck.sh [device]
set -u
[ -f "$(dirname "$0")/box.env" ] && . "$(dirname "$0")/box.env"
DEV="${1:-${BOX:?set BOX=<box-ip>:5555 in tools/box.env}}"
ADB="${ADB:-adb}"; export MSYS_NO_PATHCONV=1
a() { timeout 60 "$ADB" -s "$DEV" shell "$@"; }
LOG=/sdcard/Android/data/com.antonretro.launcher/files/perf.log
open() { a "am start -n com.antonretro.launcher/.HomeActivity -a android.intent.action.MAIN --es rl_open '$1'" >/dev/null 2>&1; }
sb() { a "grep 'stream bytes' $LOG | tail -n 1" | grep -oE "[0-9]+$"; }
fails=0
trap 'bash "$(dirname "$0")/unmute.sh" "$DEV" >/dev/null 2>&1' EXIT   # sound always comes back, even if this stops early
bash "$(dirname "$0")/mute.sh" "$DEV" >/dev/null
for m in 0 1 2; do
  for after in song video; do
    open "test:backmode:$m"; a "input keyevent KEYCODE_HOME"; sleep 2
    open test:tv; sleep 10; w0=$(sb); sleep 6; w1=$(sb)
    a "input keyevent KEYCODE_BACK"; sleep 2
    open "test:$after"; sleep 8; l0=$(sb); sleep 12; l1=$(sb)
    w=$(( (w1 - w0) / 1024 )); l=$(( (l1 - l0) / 1024 ))
    if [ "$w" -gt 50 ] && [ "$l" -eq 0 ]; then r=PASS; elif [ "$w" -le 50 ]; then r=SKIP; else r=FAIL; fails=$((fails+1)); fi
    echo "$r  Back setting $m, then a $after: the channel downloaded $w KB while watching, $l KB after leaving"
    a "input keyevent KEYCODE_BACK"; sleep 1; a "input keyevent KEYCODE_BACK"; sleep 1
  done
done
open test:backmode:0
echo; [ $fails -eq 0 ] && echo "ALL PASSED" || echo "$fails FAILED"
exit $fails
