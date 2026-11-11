#!/usr/bin/env bash
# Silence the box before ANY test (games, TV, the start-up chime, navigation sounds).
# Leave it muted between test runs; give the sound back once, at the end of the session, with tools/unmute.sh.
[ -f "$(dirname "$0")/box.env" ] && . "$(dirname "$0")/box.env"   # your box address and adb, kept out of git
DEV="${1:-${BOX:?set BOX=<box-ip>:5555 in tools/box.env}}"; ADB="${ADB:-adb}"; export MSYS_NO_PATHCONV=1
timeout 30 "$ADB" -s "$DEV" shell 'am broadcast -a com.antonretro.launcher.TEST_AUDIO -n com.antonretro.launcher/.TestAudio --ez mute true | grep -o "data=.*"; touch /sdcard/RetroLauncher/.test-mute'
m=$(timeout 30 "$ADB" -s "$DEV" shell 'dumpsys audio' | tr -d '
' | awk '/- STREAM_MUSIC:/{f=1} f&&/Muted:/{print $2; exit}')
if [ "$m" = "true" ]; then echo "MUTED  music stream muted (HDMI included)"; else echo "CHECK  music stream not muted ($m)"; exit 1; fi
