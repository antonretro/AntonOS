# AntonOS

*by AntonRetro.* A home screen and system for cheap Android TV boxes: TV, radio, YouTube, music, games and apps in one place.

> Name: AntonOS (renamed from Retro Launcher on 2026-11-12). The app ID stays `com.antonretro.launcher` and the box folder stays `/sdcard/RetroLauncher/`, so existing installs keep their settings.

**Early preview.** A home screen for cheap Android TV boxes (built and tested on an MXQ Pro, Android 7.1, 1280x720), in the spirit of a modern TV interface: live TV with a guide, radio, YouTube shelves, your own music and videos, retro and Android games, and one Settings menu, all driven by a remote, a game controller or a mouse.

- **TV:** free channels by country with numbers, favourites and a TV guide.
- **Media:** your music (with a built-in visualizer) and videos.
- **Games:** your retro games with box art (RetroArch runs hidden as the engine), your Android games, and built-in pixel games.
- **Never stuck:** Home or the Xbox button always gets you out, with a menu over games.

Box setup (installing, tuning and the channel list) is done by the companion tool [android-tv-box-skill](https://github.com/antonretro/android-tv-box-skill).

## Building

Android Studio or the Gradle wrapper: `./gradlew assembleRelease`. `tools/` holds the test scripts used on a real box; put your box's address and adb path in `tools/box.env` (not committed):

```
BOX=192.168.1.50:5555
ADB=adb
```

The design, decisions and what is still to do are in [PLAN.md](PLAN.md); facts about the test box are in [BOX-FACTS.md](BOX-FACTS.md).

## Licence

MIT (see [LICENSE](LICENSE)). Third-party parts and credits: [CREDITS.md](CREDITS.md). No ROMs, channel lists or box art are included; channel lists come from public sources the user chooses.
