# TV launcher redesign: build plan

Handoff for the model that builds this. Written Nov 10, 2026. Read `C:\tmp\mxq-pro\AGENTS.md` and the `android-tv-box` skill first: their rules (ADB path, backups, ask before pressing keys, D: for big files) all apply.

## Goal

Replace Projectivy Launcher on Anthony's MXQ Pro with our own home screen app, **Retro Launcher**, that feels like a modern Apple TV: calm, sharp, big artwork, smooth focus motion. Then ship it in this repo so the skill installs it on any box.

Anthony's brief, in his words: it should feel like one operating system, not a pile of separate apps, "so clean and easy to use that even an old person could use it". Three things follow from that, and they matter more than any visual detail:

1. **Live TV is part of the home screen.** No opening Kodi, no Kodi menus. Pick a channel, it plays.
2. **Retro games are part of the home screen.** Pick a game by its box art, it starts.
3. **Everything is fast.** Moving around, opening things, coming back Home.

### The simplicity test

Every screen must pass this: someone who has never seen it, holding only a remote with arrows, OK, Back and Home, can turn the box on and be watching a channel within three presses, without reading instructions. Anthony's own example: an older person thinks "I want TV", sees the word TV, goes there. No steps to learn. So: a few big tabs and nothing else at the top level, large text (never under 24 sp at 1080p), plain words ("TV", "Radio", "YouTube", "Videos", "Games", "Apps", "Settings"), no hidden gestures needed for normal use, Back always goes one step up and Home always goes Home, and the box remembers where you were (last channel, last game).

Anthony's complaints about the current home screen (`media/home-screen-now.png`):

- Backgrounds look low resolution.
- The UI is messy: mismatched tiles (some banners, some grey boxes with tiny icons), a faded second row, a nag toast about accessibility, clutter at the bottom edge.
- It doesn't feel premium or fun to use.

## What "Apple TV style" means here

Match the layout, spacing, motion and feel. Do **not** copy Apple's own artwork, wallpapers, icons, sounds, fonts (San Francisco) or the Apple name/logo. Everything shipped must be ours or freely licensed, because the repo is public and MIT.

Before designing, open Apple's tvOS pages (apple.com/tvos, apple.com/apple-tv-4k) and the tvOS Human Interface Guidelines in the browser and study the current home screen. Take notes on proportions; don't save their images into the repo.

The traits to reproduce:

1. **Full-bleed backdrop.** One sharp image fills the whole screen. Content floats over it.
2. **Top shelf.** The upper half of the screen is artwork for whichever dock app is focused, with a slow crossfade (about 400 ms) when focus moves.
3. **Dock row.** The first row on Home is 5 to 6 favourites (apps, channels or games) on a frosted glass strip.
4. **Uniform app cards.** Every app is the same 16:9 rounded card (about 20 dp radius at 1080p), 5 per row, generous even gaps, nothing half faded.
5. **Focus motion.** The focused card grows to about 1.12x, lifts with a soft shadow and a faint light edge, and its name fades in underneath. About 200 ms, decelerate curve. A small dip when OK is pressed.
6. **Scroll behaviour.** Scrolling down past the dock blurs and dims the backdrop so the app grid reads clearly.
7. **Quiet status area.** Top right: time only, plus a small Wi-Fi mark when offline. No battery icon (it's a TV box), no toasts.
8. **Control panel.** Holding Home (or pressing Menu) slides a glass panel in from the right: Wi-Fi, Settings, wallpaper, sleep, edit home screen, switch to the classic launcher.
9. **Edit mode.** Long press OK on a card: move, add to or remove from dock, hide, app info, uninstall.
10. **Idle screensaver.** After a few minutes, a slow pan and zoom through the wallpapers with the clock. Any key returns.

## The tabs

A single row of large words at the top: **Home · TV · Radio · YouTube · Videos · Games · Apps · Settings**. Left and right switch between them; the content below slides over. Eight is the ceiling: add nothing more at the top level, and Settings → Home screen lets a user hide tabs they don't use (Home, Apps and Settings always stay). Settings is a normal tab like the rest, not a hidden gear.

### Home

The dock of favourites (anything can be pinned: a channel, a game, an app), then "Keep watching" (last few channels), "Keep playing" (last few games). This is where the box lands on power-on. A first-time user sees a few sensible defaults, never an empty screen.

### TV (built in, no Kodi needed)

- Plays the same channel list the skill already builds (`build_channels.py` output: TV by country, Weather, Radio). The launcher reads the `.m3u` file directly; point it at `/sdcard/RetroLauncher/channels.m3u`, and import the existing Kodi list on first run if it's there.
- Player: **AndroidX Media3 ExoPlayer** inside the launcher (handles the HLS streams these lists use; works on Android 5+). Hardware decoding only, 1080p max.
- Browsing: channel cards with logos, grouped by country, plus Favourites and Recent at the top. A star on long press.
- While watching: Up/Down changes channel straight away with a small name banner that fades; OK shows the channel strip along the bottom; Back returns to the list. Opening TV resumes the last channel.
- A dead stream shows "This channel isn't working right now" for two seconds and moves on. Never a spinner forever, never a freeze (the Kodi failure in the skill notes).
- **TV Guide.** Anthony wants a real guide: what's on now and what's coming up.
  - *Now and next everywhere:* each channel card shows the current programme with a thin progress bar; the banner while watching shows "Now: ... / Next: ... at 8:00".
  - *Guide screen:* a "Guide" button at the top of the TV tab, and the Menu key while watching. Channels down the side, time across (now to +24 h), programmes as blocks in our card style, current time as a vertical line. Focusing a block shows its title, time and description; OK on something on now plays it; OK on something later offers "Remind me" (a notice a minute before it starts). Favourites first, then by country. Scrolling must hold 60 fps: draw only visible blocks.
  - *Data:* guide data is XMLTV, matched to channels by the `tvg-id` already in the playlist. Free sources are per provider and coverage is partial, so support a short list of guide URLs in `/sdcard/RetroLauncher/guide-sources.txt`, filled by the skill for the chosen countries (candidates to evaluate: the iptv-org/epg project's site list, and public XMLTV feeds for the free ad-supported services whose channels are in the list). Check each source's terms before shipping it as a default.
  - *On a 1 GB box:* never load a whole guide file into memory. Download gzip, stream-parse, keep only channels that are in the playlist and only the next 48 hours, store compactly in SQLite. Refresh once a day in the background and when the TV tab opens with data older than 12 hours; never while a video is starting.
  - *Honesty in the UI:* channels with no data show "No guide information" and sit below the ones that have it. Report to Anthony what share of his channels actually get a guide.
- Same rule as the skill: some iptv-org entries are unofficial rebroadcasts. Use the list as built; don't hand-pick, test or promote those, and add no other sources.
- Kodi stays installed and shows up under Apps for anyone who wants it. It's just no longer needed for TV, which also frees roughly 300 MB of RAM.

### Radio (built in)

Its own tab, because "listen to the radio" is a different wish from "watch TV". Station cards with logos, grouped by country, Favourites and Recent on top. Plays in the same built-in player with the station logo, name and a clock over the blurred wallpaper. Home leaves it playing in the background with a small "Now playing" chip on Home; pressing the chip opens it, a long press stops it.

### YouTube

A YouTube tab in our own style, with SmartTube (already installed by the skill) doing the playing. The user never has to find or learn SmartTube.

- **Search** at the top: a big search box (on-screen keyboard, or type on the phone remote). Submitting opens the results in SmartTube through its search intent.
- **Your channels:** pin channels by name or link; each becomes a shelf of its newest videos with thumbnails and titles, read from YouTube's own public channel feeds (`youtube.com/feeds/videos.xml?channel_id=...`: official, no key, no sign-in, newest 15 videos). A few starter channels suggested in the welcome, all removable.
- **Keep watching:** videos opened from here recently.
- Pressing a video zooms the card and opens it in SmartTube by its normal YouTube link; Back or Home returns to the tab on the same card.
- Signing in to YouTube, if wanted, happens inside SmartTube and Anthony does it himself.
- Do not write our own YouTube player, scrape YouTube pages, or pull video streams directly: that breaks YouTube's terms and breaks whenever they change the site. Confirm SmartTube's exact intents (search, video link, channel link) against the installed version; if search can't be handed over, the search box opens SmartTube's own search screen.

### Videos (built in)

Your own videos and films, one press away. Big poster cards (a frame grabbed from the video when there's no artwork), "Keep watching" with a progress bar on top, then folders. Files come from the browser's Downloads, `/sdcard/RetroLauncher/Videos/`, and USB drives. An "Add videos" card copies from a USB drive in one press, and plugging in a drive with videos shows "Found 8 videos. Press OK to add them" (or just watch them from the drive). Plays in the same built-in player as TV, remembers where you stopped, and offers subtitle files that sit next to the video. Music files can live here too in a simple list.

### Games (built in)

- One shelf per system, box art cards, plus "Keep playing". Anthony's own Android games (Subway Surfers DE, Theme Park, Mario Multiverse) sit in the same section, so "Games" means every game, whatever it runs on.
- **Version 1: the launcher is the library, RetroArch is the hidden engine.** Install the official 32-bit RetroArch, hide it from Apps, and start a game by sending it the game file and the right core directly (`com.retroarch.ra32` / `RetroActivityFuture` with `ROM`, `LIBRETRO`, `CONFIGFILE` extras; confirm the exact names against the installed version). The user never sees a RetroArch menu: press a box, the game runs; press Home, back in Retro Launcher. Ship a tuned RetroArch config (controller auto-mapped, menu hotkey on one combo, save on exit, load on start so games resume).
- **Later option:** run the emulator cores inside the launcher itself (the LibretroDroid library). Truly one app, but it's GPL-3, so the launcher's licence would have to change from MIT, and it's a lot more work. Do version 1 first and only go here if the hand-off to RetroArch feels clunky on the real box.
- **Adding games is part of the tab, not a file-manager job.** An "Add games" card at the end of the Games tab: pick a USB drive or folder, the launcher finds game files, works out the system from the file type, shows them with box art, and one press copies them into the library. Plugging in a USB drive with games on it also shows the notice "Found 12 games. Press OK to add them". Removing a game is a long press like any other card.
- **Systems are invisible.** Users see games, not emulators or cores. The system only shows as a shelf title ("Nintendo", "Sega", ...) and a small badge. Per-game options (controls, save slots, reset) live in one in-game pause screen styled like the launcher, opened by Home or the controller's menu button combo.
- Game files: the launcher scans `/sdcard/RetroLauncher/Games/<system>/`. Anthony supplies his own game files. Don't download, bundle or link to commercial game files. Free homebrew is fine to suggest.
- Box art: fetched on the box from the libretro thumbnails project by game name and cached; a clean generated card (title on the system's colour) when there's no match. No box art committed to the repo.
- Stick to systems the S905W runs well: NES, SNES, Game Boy / Color / Advance, Mega Drive, Master System, PC Engine, PS1 at a push. Don't offer N64 or newer.

### Apps

Everything else, same 16:9 cards, most used first. Long press for move, pin to Home, hide, app info, uninstall.

### Settings: one menu, not three

Today the box has Android's Settings, the box maker's settings and Projectivy's settings, all different. Retro Launcher has **one** Settings tab, styled like the rest, with plain categories:

- **Wi-Fi & Internet**: scan, pick a network, type the password with a big on-screen keyboard (done in our own screen through `WifiManager`, which apps may still do on Android 7).
- **Picture**: resolution, screen size / overscan, wallpaper, screensaver timing.
- **Power & TV**: in plain words, "When the TV turns off: put the box to sleep / keep the box on" (today the box always sleeps with the TV, through HDMI-CEC), "Turn the TV on when the box wakes", and a sleep timer. Find where the S905W firmware keeps the CEC switches (Android `hdmi_control_*` global settings and/or the Amlogic `persist.sys.hdmi`/`sys.cec` properties and maker settings app) and drive them from here; if one needs system rights, grant them once over ADB at setup.
- **Sound**: volume feedback, navigation sounds on/off.
- **Controllers & Remotes**: what's connected, pair Bluetooth, test buttons.
- **Home screen**: dock, hidden apps, card order, reset layout.
- **Apps**: list with size, open, force stop, clear, uninstall.
- **Storage**: space used, USB drives.
- **About**: box model, Android version, Retro Launcher version, check for launcher update.

Honest limits: some switches need system rights an ordinary app doesn't have (screen resolution, HDMI output mode, date/time, factory reset). For those: grant `WRITE_SECURE_SETTINGS` once over ADB during setup where that's enough; otherwise the row opens the exact Android page for that one thing (a direct intent, not the Android Settings home), and Back returns to our Settings. Find out on the box which items need which route and list them in `AGENTS.md`. Android's own Settings app and the maker's settings app are hidden from Apps (still reachable from About → "Advanced Android settings"). Projectivy gets uninstalled at the end, with Anthony's OK, so its settings go too.

### Remote, controller and mouse, all first class

- **Remote:** arrows, OK, Back, Home, and the Menu key opens options for the focused card.
- **Controller:** D-pad and left stick move focus (stick with repeat and a dead zone), A opens, B goes back, L1/R1 switch tabs, Start opens options, Y pins/unpins. Button hints along the bottom show the controller's own button glyphs only while a controller is in use.
- **Mouse:** hovering a card focuses it with the same lift, clicking opens it, right click opens options, the wheel scrolls rows and tabs respond to clicks. Every target is big enough to hit easily. The pointer hides after a few seconds still.
- Switching between them is seamless: whichever was used last decides what's shown (focus ring vs hover), with no setting to change.

### Web browser (built in)

The box has no good browser: its own `cyx.browser` is old and the system WebView is version 76 (2019), which breaks many current sites. Retro Launcher gets a **Web** card on Home and in Apps that opens our own browser, styled exactly like the rest.

- **Screens:** a start page with big tiles for favourite sites and a search box; the page view with a slim glass bar on top (back, address, reload, bookmark) that hides while reading; tabs as cards; Downloads; History; Bookmarks. All in the same cards, glass and type as the launcher.
- **Remote and controller:** a soft on-screen pointer moved with the arrows or the left stick (speeds up when held), OK clicks, a separate scroll mode, Back goes back a page. Mouse just works. Big on-screen keyboard for typing.
- **Downloads:** a proper Downloads page with progress, file type icons and "Open". APK downloads say plainly that installing apps from the web can be risky and need a second confirmation; open other files with the right built-in viewer (video and music in our player, pictures in a simple viewer).
- **Engine, decide on the box by measuring:** (a) update Android System WebView to version 119, the last one for Android 7 (official Google signed APK only; ask Anthony where to get it from), and build our browser on it: small and light; or (b) Mozilla's **GeckoView** (current Firefox engine, supports Android 5+ and 32-bit ARM, MPL licence): modern and safe but adds about 50 MB and needs more memory. Try (a) first. Put the browser in its own module so the engine can change without touching the launcher.
- No ad-blocking lists that break sites, no extension store. Keep it simple.

### One design system for every screen

Anthony's rule: every menu must look like it belongs to the same system. Build a small shared kit first and use it everywhere (launcher tabs, Settings, TV, Radio, Games, game pause screen, browser, Downloads, notices, dialogs): one set of colours, one type scale (Inter), one card shape and radius, one glass panel, one focus/hover style, one button, one list row, one toggle, one on-screen keyboard, one dialog, one timing file. No screen is allowed its own one-off styles. The stage 2 mockup must include a Settings page, a dialog, the keyboard and the browser so the kit is agreed before building.

### Home styles (switchable)

Settings → Home screen → Style, shown as three big preview cards. Picking one morphs the screen into it straight away (cards glide to their new places, about 400 ms), no restart. Same tabs, same content, same design kit in all three; only the arrangement changes.

- **Cinema** (default): the Apple-like layout described above. Big artwork up top, dock, rows.
- **Classic grid:** a nod to the box's original menu that Anthony likes (`media/before-classic-menu.png`): a few large colourful tiles for TV, Radio, Videos, Games, Apps, Web and Settings filling the screen, with a small row of favourite apps along the bottom. Redrawn in our own style, not a copy of the maker's artwork.
- **Simple:** for someone who wants the least possible: extra-large tiles, extra-large text, only the things that were pinned, nothing scrolls.

Build Cinema first; the other two reuse its parts, so keep layout separate from content from the start.

### First-time welcome

Shown once, on first start (and again from Settings → About → "Show the welcome again"). Short, skippable, one idea per screen, each with a little looping animation:

1. Welcome, with the intro animation.
2. Wi-Fi, if not connected (our own Wi-Fi screen).
3. "This is how you move": press the arrows and OK on the real remote and watch it respond; if a controller or mouse is connected, show that instead.
4. "Back goes back. Home always brings you here."
5. Pick a home style (the three previews) and a wallpaper.
6. Pick your countries for TV and radio (only if no channel list is on the box yet).
7. "You're ready", landing on Home with a channel, a game shelf and Web already pinned.

No accounts, no sign-ups, no terms screens. Under a minute end to end.

### Phone as a remote

No app to install on the phone, so iPhone and Android both work. Settings → Controllers & Remotes → "Use your phone" shows a QR code; scanning it opens a remote in the phone's browser, served by a tiny web server inside Retro Launcher on the home network.

- The phone page: swipe pad and arrows, OK, Back, Home, volume, the tab names as shortcuts, and a **keyboard** so typing Wi-Fi passwords, searches and web addresses happens on the phone. Styled like the launcher.
- Pairing: the TV shows a 4-digit code the first time a phone connects; remembered phones are listed in Settings and can be removed. Home network only, off until switched on, never reachable from the internet.
- Honest limit: an ordinary app can fully drive its own screens (so Home, TV, Radio, Videos, Games library, browser and Settings all work), and can send Back and Home anywhere through an accessibility service, but Android does not let it press arrows inside *other* apps. Check on the box whether its debug build allows key injection another way; if not, say so in the phone page ("arrows work in Retro Launcher only") rather than failing silently.
- Build it late: it is an extra, and nothing else depends on it.

### Seeing where you are (Anthony's main complaint about Projectivy)

In Projectivy, moving down hides or fades the rows you came from, so you lose your place and can't see all your apps. Rules for every grid and shelf here:

- Apps is a plain grid, 5 across, with at least three full rows visible at once. No row is ever faded, shrunk or pushed off because another row has focus.
- Scrolling moves one row at a time and keeps the row above the focused one on screen, so you can always see where you came from. A cut-off row at the bottom edge hints that there is more.
- Focus is remembered: Back from an app, or switching sections and returning, puts you on the card you left.
- It runs every Android app on the box, TV-style or phone-style, in the same grid with the same card shape. No separate "TV apps" and "mobile apps" areas.

### Friendly notices

A small glass pill slides in at the top right for three seconds, with an icon and plain words, then goes away by itself. It never takes focus and never needs a button press.

- Controller connected / disconnected (with its name), mouse, keyboard, USB drive plugged in or removed (via `InputManager.InputDeviceListener` and the storage broadcast).
- Wi-Fi or network lost / back.
- App installed / removed.
- A USB drive notice offers "Press OK to open" only while it's showing.

No nags, no tips, no "enable this service" messages, ever.

### About using Projectivy as a base

Projectivy is closed source (its GitHub page only hosts the downloads), so its code can't be reused or reskinned. Take the ideas that work (reading both TV and phone apps, per-app card art, instant refresh when an app is added or disabled) and write our own.

## Speed

What we control, and what we don't, stated honestly:

- **The home screen itself:** always in memory, small, never rebuilt on return. Home press to usable screen under 300 ms. This is the biggest felt difference.
- **Opening things:** the card zooms into the app (scale-up launch animation from the card's position) so there is no dead black pause, even when the app itself takes a second to start. TV and game shelves need no app launch at all for TV, since the player lives inside the launcher.
- **System:** animation scales 0.5x, background process limit of 2, vendor junk disabled from auto-starting (with Anthony's OK, disable not uninstall), launcher set as the only home app at boot.
- **Not fixable from an app:** how fast a heavy app like Kodi loads on this chip, and boot time. Going further means different firmware, which is out of scope here. Say this plainly rather than promising it.

### Speed budget (a stage fails if it misses these on the real box)

| Action | Budget |
|---|---|
| Key press to focus starting to move | under 50 ms (next frame or the one after) |
| Any animation | 60 fps, no more than 1 slow frame in 100 (`dumpsys gfxinfo`) |
| Home key to Home usable | under 300 ms |
| Tab switch | under 250 ms, content already there (no loading spinners between tabs) |
| Press a channel to picture | under 2 s on a good stream; channel up/down under 1.5 s |
| Launcher cold start after boot | under 1.5 s to first frame |
| Launcher memory, idle | under 80 MB |

How to get there:

- **Compile everything ahead of time.** Android 7 normally compiles apps lazily. Run `cmd package compile -m speed -f <package>` over ADB for Retro Launcher and for the apps people open most (SmartTube, RetroArch, Kodi); this makes those apps themselves start and run faster, which is the "going from the system into an app" slowness Anthony noticed. Measure start time before and after (`am start -W`) and put the command in the skill.
- **Never wait on the main thread.** Disk, network, image decoding and playlist parsing all happen in the background; the screen draws from what is already in memory. Parse the channel list once and keep a compact cached copy so TV opens instantly.
- **Preload the next thing.** Neighbouring tabs are built before you reach them; card images are decoded just ahead of scrolling; the player is created at start-up so the first channel doesn't pay for it.
- **Small and plain.** R8 shrinking on, no dependency-injection frameworks, no reflection, flat view layouts, images at exactly the size shown.
- **Stay alive.** Keep memory low so Android doesn't kill the launcher behind a game and have to restart it on Home.
- **System side** (with Anthony's OK, all reversible, record each in `AGENTS.md`): animation scales, background process limit, disable vendor auto-start junk, check whether compressed swap (zram) is on and whether storage trim has run (`sm fstrim`), since full, untrimmed flash is a common cause of old boxes feeling slow.
- **Measure, don't guess.** A small script in `launcher/tools/` that runs the table above over ADB and prints pass or fail; run it at the end of every stage and paste the numbers in the stage report.

## Hard limits of the box

| | |
|---|---|
| Chip | Amlogic S905W, Mali-450 GPU (OpenGL ES 2.0 only) |
| RAM | 1 GB, roughly 460 MB free with Kodi open |
| Android | 7.1.2 (SDK 25), 32-bit only |
| WebView | version 76 (too old; do not build the UI in a WebView) |
| Screen | Screenshots come out 1280x720, so the UI is probably drawn at 720p and stretched to 1080p |

That last row is likely the real cause of the "low resolution" look, and no wallpaper fixes it. **First job when the box is on:** run `wm size`, `wm density`, `dumpsys display | grep -i mode`, `getprop | grep -i -E "density|osd|display-size"`. If the UI is 720p, try `wm size 1920x1080` with `wm density 240` (undo with `wm size reset; wm density reset`), check that every app still looks right, and measure smoothness. Ask Anthony before leaving it changed. If 1080p is too slow, stay at 720p and say so plainly.

Consequences for the design:

- No live blur. Android 7 has no blur effect and the GPU is weak. Make "glass" from a copy of the wallpaper that is shrunk, blurred once, saved to disk, then drawn behind panels with a translucent tint. Rebuild it only when the wallpaper changes.
- Animate only scale, position and opacity. No animated shadows drawn per frame: use a pre-made shadow image behind the card.
- Decode images at the size they are shown. Keep one wallpaper and one top-shelf image in memory at a time.
- Targets: focus moves at a steady 60 fps (check with `dumpsys gfxinfo`), cold start under 1.5 s, under 80 MB of RAM when not playing video, channel change under 2 s on a good stream, APK under 15 MB.

## Technical choices

- **Native Android app, Kotlin, classic Views** (RecyclerView, ConstraintLayout, property animations). Not Jetpack Compose: too heavy for this box. Not Leanback's stock rows: they look like the Android TV we're trying to get away from.
- `minSdk 21`, `targetSdk 34`, universal APK (no native code, so 32-bit boxes are fine).
- Gradle project with the wrapper committed. Gradle isn't on PATH; JDK 21 is, and Android Studio plus SDK build-tools 34 and 36.1 are installed. `C:\tmp\motion-playground-app` shows the hand-rolled build used last time; use real Gradle here.
- Few dependencies: AndroidX core, RecyclerView, Palette, Media3 ExoPlayer (HLS module only). Write a small image cache instead of pulling in a big image library.
- Name **Retro Launcher** (Anthony's choice), package `com.antonretro.launcher`. Keep the display name in one place in case it changes.
- Font: Inter (open licence), bundled.
- Signing: make one release key, store it outside the repo on D:, and tell Anthony where. Never commit it.

### Launcher plumbing

- Manifest: main activity with `HOME`, `DEFAULT`, `LEANBACK_LAUNCHER` and `LAUNCHER` categories, `singleTask`, no history animation.
- App list: merge `LEANBACK_LAUNCHER` and `LAUNCHER` activities, drop duplicates, listen for package added/removed/changed.
- Card art, in order: a hand-made 640x360 card bundled for known apps (Kodi, SmartTube, F-Droid, RetroArch, Settings, File Browser, Movie Player, Play Store, and Anthony's own builds); else the app's own TV banner if it is sharp enough; else a generated card: the icon centred on a soft gradient taken from the icon's main colours. No grey boxes.
- Top-shelf art: bundled per known app; otherwise the wallpaper stays.
- Order, dock, hidden apps and settings saved in one small JSON file so a setup can be copied to another box.
- Set as default with `cmd package set-home-activity com.antonretro.launcher/.HomeActivity`. **Keep Projectivy and the classic launcher (`com.droidlogic.mboxlauncher`) installed** until Anthony has used the new one and says to remove Projectivy. A broken default launcher on a TV box is hard to recover without ADB, so the control panel always offers "classic launcher".

### Look: dark only

One dark theme, no light mode: it's a TV in a living room, often at night, and one theme is half the polish work. Text is white or soft grey on dark glass.

### Wallpapers

Anthony wants plenty of choice, like the landscape screensavers on Google's TV devices.

- **Landscapes:** about 20 royalty-free photos (Unsplash, Pexels; mountains, coast, forest, desert, night sky, cities at dusk), 1920x1080 high-quality JPEG, about 400 KB each. Record author and link for each in `launcher/CREDITS.md`. Keep 4K originals out of the APK. Show Anthony a contact sheet to pick from.
- **Gradients:** 6 to 8 deep colour gradients drawn in code (cost nothing in APK size and always look sharp at any resolution), each with a very slow colour drift that only redraws a few times a second.
- **Picker:** Settings → Picture → Wallpaper shows them as big cards with a live preview behind the menu. Options: one fixed, or a new one each day.
- Each photo gets a pre-made bottom darkening gradient so white text always reads.
- The screensaver uses the same set.

### Animation quality

Motion is where "Apple quality" is felt, so it gets its own stage and its own checks.

- **Start-up intro:** when Retro Launcher starts after power-on, a short (about 1.5 s) logo intro: the Retro Launcher mark fades and scales in over black, the wallpaper fades up behind it, then the tabs and dock rise into place one after another. Skippable with any key. Never shown again until the next boot.
- **Boot animation (optional, ask first):** the Android boot animation can be replaced with a matching Retro Launcher one (`bootanimation.zip`), but that means writing to the box's system partition. Plan it as a separate, optional step with a backup of the original file and Anthony's OK.
- **Opening an app:** the focused card zooms to fill the screen, then the app appears. Coming back Home: Home reappears with a gentle scale-down from 1.05x and fade, focus on the same card.
- **Opening a channel or game:** the card expands into the player; the channel name banner slides up and fades.
- **Tab switching:** content slides sideways and cross-fades (about 250 ms), the tab underline glides to the new tab.
- **Panels and notices:** glass panels slide in with a slight overshoot-free deceleration; notices slide down from the top right and fade out.
- One shared set of timings and curves in one file, so everything feels like the same system.
- Rules: animate only scale, position and opacity; nothing drops below 60 fps on the box (measure with `dumpsys gfxinfo`); every animation respects a "Reduce motion" switch in Settings.

## Build order

Each stage ends with a screenshot from the real box shown to Anthony. Don't move on if it isn't smooth.

1. **Box facts and resolution.** The checks above. Decide 720p or 1080p. Back up Projectivy's data and the current home setting.
2. **Design mockup.** HTML at 1920x1080: Home, each tab, scrolled grid, Settings page, a dialog, on-screen keyboard, control panel, edit mode, browser, a notice. Get Anthony's yes on the look before writing the app. Cheap to change here, expensive later.
3. **Design kit.** The shared components, in code, shown on one test screen on the box.
4. **Skeleton.** Gradle project, launches as a home app, all tabs, lists apps, opens them. Test on an Android TV emulator image if the box is off.
5. **Home and Apps.** Wallpaper, dock, grid, card art pipeline, focus motion, top shelf crossfade, clock, zoom-into-app launch.
6. **TV, Radio, YouTube and Videos.** Playlist reader, channel and station browsers, the shared player, channel up/down, favourites, resume, dead-stream handling, radio in the background, video library with resume, YouTube tab with channel shelves and SmartTube hand-off. Test TV with the official iptv-org entries only.
7. **Games.** Folder scan, add-from-USB, box art, RetroArch hand-off with the tuned config, pause screen, resume, Anthony's Android games in the same tab. Test with free homebrew.
8. **Glass and scroll.** Pre-blurred backdrop, dim on scroll, control panel.
9. **Settings.** The one-menu Settings tab, including Power & TV. Edit mode: move, pin, hide, uninstall, saved layout.
10. **Browser.** Engine check on the box, then start page, page view, pointer, tabs, Downloads, history, bookmarks.
11. **Home styles, first-time welcome, phone remote.** Classic grid and Simple styles with the live morph between them; the welcome flow; the phone remote page.
12. **Intro, transitions, screensaver** (the Animation quality section) and small touches (soft original navigation sounds, off by default).
13. **Polish pass on the box.** Frame timing, memory, cold start, every input (remote, controller, mouse; Back on Home does nothing), app install/uninstall while open, no network, first run. Then the simplicity test with a real person who hasn't seen it.
14. **Ship.** See below.

## Shipping in this repo

- Source lives in `launcher/`. The signed APK goes out as a GitHub Release, not into git.
- `skills/android-tv-box/SKILL.md` (and the private copy at `C:\Users\Anthony\.claude\skills\android-tv-box\SKILL.md`): step 7 installs Retro Launcher from the release instead of Projectivy and RetroArch as its hidden game engine; step 6 pushes the channel list to `/sdcard/RetroLauncher/channels.m3u` (Kodi's IPTV add-on becomes optional); step 8 sets it as default home; keep the "classic launcher stays" rule.
- `README.md`: new before/after image, replace the Projectivy mention, add a short "The home screen" section, update the "not affiliated" line.
- Update `C:\tmp\mxq-pro\AGENTS.md` and the video doc.
- Work is on branch `feat/tv-launcher`. Commit as you go. **Ask Anthony before pushing, opening the PR, or publishing the release.**

## Things to settle with Anthony

- Whether to switch the box to 1080p if it turns out to be drawing at 720p.
- When to remove Projectivy.
- Whether Kodi stays installed once TV works without it.
- Which wallpapers (contact sheet), and whether to replace the boot animation.
- Which game systems he wants shelves for.

## Not in scope

- No recreation of Apple's TV app, store, or any Apple artwork.
- No unlicensed streaming apps, rows or "recommended" feeds from them.
- No firmware flashing. This is an app that takes over the home screen, not a new Android ROM, even though it should feel like one.
- No downloading or bundling commercial game files or box art in the repo.
