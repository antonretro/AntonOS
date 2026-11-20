# Handy functions for poking the test box by hand: source it, then `k <linux key code> [wait]`, `shot <name>`.
[ -f "$(dirname "${BASH_SOURCE[0]}")/box.env" ] && . "$(dirname "${BASH_SOURCE[0]}")/box.env"
export MSYS_NO_PATHCONV=1
A="${ADB:-adb} -s ${BOX:?set BOX in tools/box.env}"
S="${SHOTS:-/d/rl-build/shots}"; mkdir -p "$S"
k() { $A shell "sendevent /dev/input/event0 1 $1 1; sendevent /dev/input/event0 0 0 0; sendevent /dev/input/event0 1 $1 0; sendevent /dev/input/event0 0 0 0"; sleep ${2:-0.5}; }
shot() { $A exec-out screencap -p > "$S/$1.png"; }
