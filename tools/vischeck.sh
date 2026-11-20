#!/usr/bin/env bash
# The music visualizer must be live after anything else played (a TV channel left with each Back setting, a video,
# a radio station, a built-in game, a RetroArch game). A frozen video frame left on top once hid it.
# Two screenshots 1.5 s apart must differ (the visualizer moves; a frozen frame doesn't). Sound stays muted.
#   bash tools/vischeck.sh [device]
set -u
[ -f "$(dirname "$0")/box.env" ] && . "$(dirname "$0")/box.env"
DEV="${1:-${BOX:?set BOX=<box-ip>:5555 in tools/box.env}}"
ADB="${ADB:-adb}"; export MSYS_NO_PATHCONV=1
a() { timeout 60 "$ADB" -s "$DEV" shell "$@"; }
ACT=com.antonretro.launcher/.HomeActivity
TMP="${TMPDIR:-/tmp}/vischeck"; mkdir -p "$TMP"
PYTMP="$TMP"; command -v cygpath >/dev/null 2>&1 && PYTMP="$(cygpath -w "$TMP")"   # Windows Python needs a Windows path
open() { a "am start -n $ACT -a android.intent.action.MAIN --es rl_open '$1'" >/dev/null 2>&1; }
key() { a "input keyevent $1"; sleep "${2:-1}"; }
fails=0
live() {   # 1: name
  sleep 6
  timeout 30 "$ADB" -s "$DEV" exec-out screencap -p > "$TMP/a.png"; sleep 1.5
  timeout 30 "$ADB" -s "$DEV" exec-out screencap -p > "$TMP/b.png"
  d=$(python -I -c "
from PIL import Image, ImageChops
a=Image.open(r'$PYTMP/a.png').convert('L').resize((160,90)); b=Image.open(r'$PYTMP/b.png').convert('L').resize((160,90))
print(sum(ImageChops.difference(a,b).getdata()))")
  if [ "${d:-0}" -gt 3000 ]; then echo "PASS  visualizer live after $1 (change $d)"; else echo "FAIL  visualizer frozen or blank after $1 (change $d)"; fails=$((fails+1)); fi
  key KEYCODE_BACK 1; key KEYCODE_HOME 2
}
trap 'bash "$(dirname "$0")/unmute.sh" "$DEV" >/dev/null 2>&1' EXIT   # sound always comes back, even if this stops early
bash "$(dirname "$0")/mute.sh" "$DEV" >/dev/null
for m in 0 1 2; do
  open "test:backmode:$m"; sleep 1
  open test:tv; sleep 9; key KEYCODE_BACK 2; open test:song; live "a TV channel (Back setting $m)"
done
open test:backmode:0
open test:radio; sleep 7; key KEYCODE_BACK 2; open test:song; live "a radio station"
open test:video; sleep 6; key KEYCODE_BACK 2; open test:song; live "a video"
open bi:tiki; sleep 4; key KEYCODE_BACK 1; key KEYCODE_BACK 1; key KEYCODE_HOME 2; open test:song; live "a built-in game"
echo; [ $fails -eq 0 ] && echo "ALL PASSED" || echo "$fails FAILED"
exit $fails
