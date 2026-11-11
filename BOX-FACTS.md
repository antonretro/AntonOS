# MXQ Pro facts for Retro Launcher (checked over ADB, Nov 10 2026)

- UI framebuffer is **1280x720 @ 160 dpi** (`wm size`, `/sys/class/graphics/fb0/virtual_size` = 1280x2160, triple buffered). HDMI output is `1080p60hz`; the OSD free-scales 1280x720 up to a 1872x1053 window (`window_axis 47 26 1872 1053`, built-in overscan margin).
- `wm size 1920x1080` + `wm density 240` only renders at 1080p and then shrinks back into the same 720p framebuffer (screencap stays 1280x720), and it **crashed System UI**. Reset with `wm size reset; wm density reset`. Real 1080p UI would need a firmware change, which is out of scope.
- Decision: **design for 1280x720 at 160 dpi** (1 dp = 1 px). Mockup is drawn at 1920x1080; divide by 1.5 for dp. Make all art sharp at 1280x720 (wallpapers 1280x720 on the box, card art 2x where it's cheap).
- App area is 1280x672 by default (48 px reserved): the launcher must use fullscreen/immersive flags.
- Free RAM right after boot: ~35 MB plus 413 MB free swap; Projectivy alone uses ~85 MB PSS. Target for Retro Launcher stays under 80 MB.
- Current home: Projectivy 4.73 (`com.spocky.projengmenu/.ui.home.MainActivity`). Also installed: `com.droidlogic.mboxlauncher` (classic), `com.android.launcher`. RetroArch is **not installed** yet.
- Input devices seen: CEC, IR keypad, USB optical mouse, Logitech UVC webcam (046d:080a).
- Animation scales are all 1.0.
- Backup of Projectivy data + home setting: `D:\Moved from C\MXQ-Pro-Backup-2026-11-04\launcher-2026-11-10\`.

## Never stuck: what works on this box (checked over ADB, Nov 11 2026)

- **Key helper (accessibility key filter) does work here** for real hardware keys: remote presses sent with `sendevent` on `aml_keypad` reached `RetroKeys.onKeyEvent`. It ignores keys injected with `input keyevent` (normal Android behaviour), so test it with `sendevent`, never `input keyevent`.
- **Android switches the helper off on every reinstall.** Always install with `launcher/tools/install.sh`; the launcher also switches it back on at start.
- Right after a reinstall `dumpsys accessibility` lists the helper as `label=com.antonretro.launcher.RetroKeys` instead of "Retro Launcher buttons"; both mean it is bound.
- Our own overlay windows (hint, corner button) send window-change events under our package; they must not be taken as "the launcher is in front" (that bug made the helper think it was on Home while a game was open).
- **RetroArch 1.22.2's network command port never opens on this box** (`network_cmd_enable = "true"` is read, the string is in `libretroarch-activity.so`, but RetroArch has no UDP/TCP socket at all while running). So SAVE_STATE / LOAD_STATE / RESET / QUIT sent from our panels do nothing; those choices are not offered. Quit from Home ends the engine with `killBackgroundProcesses`; `autosave_interval = "10"` keeps in-game saves.
- RetroArch exits on every pad without the helper: `input_quit_gamepad_combo = "4"` (Start + Select, saves the exact place via `savestate_auto_save`) and `input_exit_emulator_btn = "110"` (Guide). Not yet proven on the real pad (it was unplugged during the test).
- The "Generic X-Box pad" (24c6:550d) uses `/system/usr/keylayout/Generic.kl` (`key 316 BUTTON_MODE`), **not** `Vendor_045e_Product_028e.kl` (genuine Microsoft 360 pads). A per-pad Home mapping would need a new `Vendor_24c6_Product_550d.kl` (system partition: ask first).
- Something on the box reports a mouse source even with no mouse plugged in (the corner Home button showed over SmartTube with only the remote, CEC and webcam connected). Check which device before relying on "a mouse is connected".

Proven routes (Nov 11 2026, remote via `sendevent`): Back in a game opens the quick panel and Back returns to the game; holding Back in Android Settings opens the quick panel; a short Back still works as Back; Home from a game shows the game panel on Home (Stay on Home first), also after the quick panel was used; Quit there closes RetroArch.
Not yet proven: anything with the real Xbox pad (Guide, Start+Select, hold Start), the mouse corner button and top-edge pill, SmartTube/Kodi/browser/Android game.
