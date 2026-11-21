# Handy functions for poking the test box by hand: source it, then `k <linux key code> [wait]`, `shot <name>`.
[ -f "$(dirname "${BASH_SOURCE[0]}")/box.env" ] && . "$(dirname "${BASH_SOURCE[0]}")/box.env"
export MSYS_NO_PATHCONV=1
A="${ADB:-adb} -s ${BOX:?set BOX in tools/box.env}"
S="${SHOTS:-/d/rl-build/shots}"; mkdir -p "$S"
k() { $A shell "sendevent /dev/input/event0 1 $1 1; sendevent /dev/input/event0 0 0 0; sendevent /dev/input/event0 1 $1 0; sendevent /dev/input/event0 0 0 0"; sleep ${2:-0.5}; }
shot() { $A exec-out screencap -p > "$S/$1.png"; }
# The Xbox pad by its real events (find its event number with `getevent -p`; PAD=/dev/input/event3 on the MXQ):
# `hat <x> <y> [wait]` presses the D-pad (-1/0/1) and lets go; `btn <code> [wait]` presses a button
# (304 A, 305 B, 307 X, 308 Y, 310 LB, 311 RB, 314 Back, 315 Start).
PAD="${PAD:-/dev/input/event3}"
hat() { $A shell "sendevent $PAD 3 16 $1; sendevent $PAD 3 17 $2; sendevent $PAD 0 0 0; sleep 0.12; sendevent $PAD 3 16 0; sendevent $PAD 3 17 0; sendevent $PAD 0 0 0"; sleep ${3:-0.6}; }
btn() { $A shell "sendevent $PAD 1 $1 1; sendevent $PAD 0 0 0; sleep 0.1; sendevent $PAD 1 $1 0; sendevent $PAD 0 0 0"; sleep ${2:-0.6}; }
