#!/usr/bin/env bash
# Give the box its sound back (the exact levels saved by tools/mute.sh). Run once, when testing is finished.
[ -f "$(dirname "$0")/box.env" ] && . "$(dirname "$0")/box.env"   # your box address and adb, kept out of git
DEV="${1:-${BOX:?set BOX=<box-ip>:5555 in tools/box.env}}"; ADB="${ADB:-adb}"; export MSYS_NO_PATHCONV=1
timeout 30 "$ADB" -s "$DEV" shell 'am broadcast -a com.antonretro.launcher.TEST_AUDIO -n com.antonretro.launcher/.TestAudio --ez mute false | grep -o "data=.*"; rm -f /sdcard/RetroLauncher/.test-mute; sed -i "/^audio_mute_enable/d" /sdcard/RetroLauncher/retroarch.cfg'
