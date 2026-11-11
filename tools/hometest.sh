#!/usr/bin/env bash
# "Back to Home" checklist over ADB, for the remote and an Xbox-style pad (sound muted, test mode on).
#   bash launcher/tools/hometest.sh [device]
# Remote: Back = 4, Home = 3. Pad: B = 97 (Back), Guide = 110 (Home). Menu (82) opens a card's options.
set -u
[ -f "$(dirname "$0")/box.env" ] && . "$(dirname "$0")/box.env"   # your box address and adb, kept out of git
DEV="${1:-${BOX:?set BOX=<box-ip>:5555 in tools/box.env}}"
ADB="${ADB:-adb}"
export MSYS_NO_PATHCONV=1
P=/sdcard/Android/data/com.antonretro.launcher/files/perf.log
a() { timeout 90 "$ADB" -s "$DEV" shell "$@"; }
fails=0
keys() { local s=""; for k in "$@"; do s="$s input keyevent $k; sleep 0.7;"; done; a "$s"; sleep 1; }
state() { a "grep 'RLNote state' $P" | tr -d '\r' | tail -n 1; }   # tail on the PC: the box's tail returned several lines
expect() { local st; st=$(state); if echo "$st" | grep -q "$2"; then echo "PASS  $1"; else echo "FAIL  $1   [$st]"; fails=$((fails+1)); fi; }

a "touch /sdcard/RetroLauncher/.test-mute; rm -f $P"
for input in remote pad; do
  if [ $input = remote ]; then B=4; H=3; else B=97; H=110; fi
  echo "--- $input"
  keys $H;                                        expect "$input: Home goes to the Home tab" "tab=Home tabbar=false player=false dialog=false keyboard=false"
  keys $B;                                        expect "$input: Back on the Home tab does nothing" "tab=Home tabbar=false"
  keys 19 22 22 22 22 22 22 22 20 22;             expect "$input: (opened a Settings page)" "tab=Settings"
  keys $B;                                        expect "$input: Back in a Settings page goes up to the categories" "tab=Settings"
  keys $B;                                        expect "$input: Back at the top of Settings goes to the Home tab" "tab=Home tabbar=false"
  keys 19 22 20 20 20;                            expect "$input: (down in the TV tab)" "tab=TV"
  keys $B;                                        expect "$input: Back in a tab goes up to its first row" "tab=TV"
  keys $B;                                        expect "$input: Back at the top of a tab goes to the Home tab" "tab=Home"
  keys 19 22 22 22 22 22 22 22 20 20 22 $H;       expect "$input: Home from inside Settings" "tab=Home player=false dialog=false keyboard=false"
  keys 19 22 20 23; sleep 5; a "true";            expect "$input: (a channel is playing)" "player=true"
  keys $H;                                        expect "$input: Home from the TV player" "tab=Home player=false"
  keys 19 22 20 23; sleep 4; keys $B;             expect "$input: Back from the TV player" "player=false"
  keys $H 82;                                     expect "$input: (options dialog open)" "dialog=true"
  keys $B;                                        expect "$input: Back closes the dialog" "dialog=false"
  keys 82 $H;                                     expect "$input: Home from a dialog" "tab=Home dialog=false"
  keys 19 22 22 22 20 23;                         expect "$input: (keyboard open)" "keyboard=true"
  keys $B;                                        expect "$input: Back closes the keyboard" "keyboard=false"
  keys 23 $H;                                     expect "$input: Home from the keyboard" "tab=Home keyboard=false"
done
echo "--- mouse"
keys 19 22 22 22 22 22 22 22 20
a "input mouse tap 87 54"; sleep 2; keys 0 2>/dev/null
expect "mouse: clicking the Home tab from Settings" "tab=Home"
a "rm -f /sdcard/RetroLauncher/.test-mute"
echo "$fails failure(s)"
exit $fails
