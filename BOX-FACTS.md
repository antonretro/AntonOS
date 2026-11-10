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
