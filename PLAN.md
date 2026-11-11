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

#### Channel numbers (Anthony: "you can put in the number of the channel and then it will come up")

Every channel and radio station gets a stable number: favourites first, then by country, saved in the layout file so numbers never shuffle when the list is rebuilt; renumbering in Settings → TV. The number shows on channel cards, in the guide and in the watching banner. Typing digits (remote, keyboard or the phone remote's number pad) in the TV or Radio tab or while watching shows a large number with the matching channel name as you type, then tunes after about 1.5 s or on OK; an unknown number says "No channel 123".

### TV: what Anthony asked for after using it (in this order)

1. **Bug first: the channel list doesn't scroll** ("it's like fixed and you can't scroll up and down"). Reproduce with remote, pad and mouse wheel in every country group, fix, and add a smoke check (focus the last row, then the first, in the longest group).
2. **Bug: the watching banner** is "a little square" that cuts off the programme name. It becomes a wide glass bar along the bottom: number and logo at the left, channel name large, "Now: *programme*" with a progress bar and "Next: *programme* at *time*" when the guide has them, the clock at the right. Never clipped: sized to its content, ellipsized only when a title is genuinely longer than the full width. Check a very long name, a channel with no logo and one with no guide data, with before/after screenshots.
3. **Channel logos always load.** Today one failed download hides a logo until the launcher restarts, the cache sits where Android deletes it first (about 360 MB free), three downloads at a time with no cancelling, and SVG logos or http-to-https redirects never load. Fix: keep logos in the app's own storage at card size (about 15 MB for all channels), retry failures later instead of giving up, follow redirects, put on-screen cards first and drop requests for cards already scrolled away, and fetch the rest slowly in the background once a day (favourites and recent first). Channels whose logo can't be read keep the coloured name card.
4. **Navigation** ("there's a lot of channels, but it's hard to navigate ... the guide should be probably default"): the Guide becomes the TV tab's default view, with Cards one press away (remembered). A left rail of filters: Favourites, Recent, All, categories from the playlist's group tags (News, Sports, Kids, Movies, Music), then Countries. Search by name (on-screen keyboard or phone), channel numbers, hold Up/Down or the shoulder buttons to jump a page, A to Z letter jump, and "Hide channels that don't work" fed by a gentle background check (only channels he opens or favourites, plus a slow idle sweep; never hammering the sources). The guide scrolls smoothly both ways with focus always visible.
5. **Player controls** ("when you move the mouse around or press a button, it should showcase like the menus"): in TV, Radio, Music and Videos any key or mouse movement shows the control overlay for about 5 s: the wide banner, a transport row (play/pause, back 10 s, forward 30 s where seeking works, Guide, favourite, audio/subtitle track, quality, record) and, for the mouse, clickable buttons with a Back/Home pill. It never steals Up/Down channel zapping.
6. **Pause and rewind live TV** (scoped honestly): level 1, where a stream's own playlist offers a seek-back window, pause and rewind inside it at no cost; level 2, an optional time-shift buffer on a USB drive (not internal flash: about 2 GB free and it wears), off by default, in Settings → TV when a drive is present. Always show "Live" or "Behind live by 2:15" with a jump-to-live button.
7. **Recording** ("a recorded episode ... or you can schedule another thing to record"): record now, and scheduled recordings from the guide (one-off, or a series by title) onto a USB drive into Videos → Recordings with the programme name and time; a Recordings/Scheduled page, padding minutes, conflict and low-space warnings, and a note that the box must stay on. One recording while watching another channel only if the box keeps 60 fps (measure). Personal use only: no sharing or export, and never hand-pick or promote unofficial rebroadcast streams. Items 6 and 7 come after the guide and navigation are solid.

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

### Music player (late stage, in the Radio tab)

Anthony: "a music player hasn't been built, a custom one". No ninth tab: the Radio tab becomes the home for all listening (keep the label "Radio" unless Anthony prefers "Music"; ask). "My music" shelves above the stations: Albums, Artists, Playlists, Recently played, read from `/sdcard/RetroLauncher/Music/`, Downloads and USB drives (tags and embedded cover art; a generated cover when there is none), and an "Add music" card like Games and Videos. Now Playing: large cover over the blurred wallpaper, title and artist, progress, previous/play/next, shuffle, repeat, queue. Keeps playing in the background with the same "Now playing" chip as radio; media keys on remote, pad and keyboard work everywhere. Same Media3 player as TV and radio; MP3, AAC/M4A, FLAC, OGG, WAV. Local files only, no streaming services.


**Optional visualizer in Now Playing** (late, with the music player): Anthony's own engine from his `WMP-Visualizations-Decomp` project (plain-JS players plus an audio analysis module). That project prepares an embeddable folder with a small JS API (set preset, feed audio, resize); Retro Launcher hosts it in a WebView behind Now Playing and feeds it PCM tapped from Media3. It must run on the box's Chrome 76 WebView at 1280x720. Measure first: if it can't hold 60 fps alongside audio on this box, use a simple native bars view instead. Setting "Use original presets" on/off. **Licensing rule:** only Anthony's own engine code ships in this public MIT repo and the APK. The "Classic pack" of original preset data and names is Microsoft's: never in the repo or the release, only loaded from a folder on the box if he puts it there himself (`/sdcard/RetroLauncher/Visualizer/classic/`).

*Visualizer status (2026-11-11):* an embeddable build exists at `C:/Users/Anthony/Documents/Projects/WMP-Visualizations-Decomp/dist/embed/` (`index.html` + `visualizer.js`, 141 KB; API in its `README.md`: feedPcmBase64 in batches, feedPcm, feedLevel, setVisualizer, setPreset, listPresets, setOriginals, loadClassicPack, setSize, pause/resume). It reports no Microsoft data inside and passes a Chrome 76 parse check, but frame times were measured on a throttled desktop only (light presets 6.5 to 17 ms, heavy classic presets 36 to 69 ms), so some presets will miss 60 fps on the S905W. Measure on the box first; consider capping the visualizer at 30 fps or a smaller internal buffer while the audio stays smooth. **Before copying anything into this public repo:** read the files, confirm they hold only Anthony's own engine code, and wait for Anthony's OK.

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

#### Everything in our own style (Anthony, Nov 10)

His words: "I want like all the settings for like RetroArch to look in the style of the OS. I want the TV settings, the guide system, all the things to look in the settings of the OS as well." The rule: **no setting is reached through another app's menu.** Everything lives in Retro Launcher's Settings tab, built from the shared kit.

- **Settings → Games:** what people actually change in RetroArch, written to the tuned `retroarch.cfg` (per-game overrides where it matters): controllers and button layout per pad, save and resume behaviour, picture (shape, whole-pixel scaling, smoothing, a few filter presets the Mali-450 can run), sound volume, rewind and fast-forward on/off, per-system engine (core) choice, BIOS files status, library folders and rescan. Curate, don't mirror RetroArch's hundreds of options; an "Advanced" row only for something common that's missing. RetroArch's own menu never needs to appear: the in-game pause panel (Guide, Start+Select or Back) drives RetroArch over its local command port.
- **Settings → TV:** channel list source and countries shown, hidden channels, favourites management and order, guide sources and refresh, player options (start fast vs. smoother, preferred quality), reminders.
- The TV Guide, the player banner, the in-game pause panel and every dialog use the same cards, glass, type and timings.

#### Foreign-UI audit (Nov 10): screens reachable from the launcher that are not ours yet

| Screen | Reached from | Plan |
|---|---|---|
| Android display settings | Settings → Picture → Screen size and position | Build ours if the overscan/position values can be written with `WRITE_SECURE_SETTINGS` or `wm overscan` over ADB at setup; otherwise keep as the one "advanced" exception. Resolution/HDMI mode is vendor-only (system rights). |
| Android sound settings | Settings → Sound → Volume and sound output | Build ours: volume via `AudioManager`; HDMI passthrough is vendor-only, keep as advanced. |
| Android Wi-Fi settings | Settings → Wi-Fi → Advanced | Ours already covers scan, join, password; keep "Advanced" for proxies/static IP (rare). |
| Android Bluetooth settings | Settings → Controllers → Pair a Bluetooth controller | Build ours: discovery + pairing with `BluetoothAdapter` (needs location permission granted at setup). |
| Android storage details | Settings → Storage → Storage details | Build ours (per-app sizes, clear cache) where `StorageStatsManager`-style data is available on 7.1; else drop the row. |
| Android app uninstall confirmation | Hold OK → Uninstall, Settings → Apps | Can't be replaced: Android requires its own confirmation for an ordinary app. Keep; it's one plain dialog. |
| Android "Advanced Android settings" | Settings → About | Kept on purpose as the escape hatch. |
| Classic launcher (vendor) | Settings → About → Classic launcher | Kept on purpose as the fallback home screen. |
| Android runtime permission dialog | First run on a new box (storage) | Avoid: the skill grants permissions over ADB at setup. |
| Android "app has stopped" dialogs, volume overlay | System | Can't be replaced by an app (System UI). Prevent crashes instead (smoke test). |
| RetroArch menu | In a game (Start+Select, Back) | Replaced by our pause panel once the "Retro Launcher buttons" helper is on; RetroArch's fallback menu set to its simplest dark theme. |
| SmartTube | YouTube videos and search results | Can't be replaced by design: no own YouTube player (YouTube's terms). Our tab does search entry and channel shelves. |
| Other apps (Kodi, games, Play Store, F-Droid) | Apps | By design: they're the user's apps. The "Get apps" page replaces store browsing for common apps. |

### Remote, controller and mouse, all first class

- **Remote:** arrows, OK, Back, Home, and the Menu key opens options for the focused card.
- **Controller:** D-pad and left stick move focus (stick with repeat and a dead zone), A opens, B goes back, L1/R1 switch tabs, Start opens options, Y pins/unpins. Button hints along the bottom show the controller's own button glyphs only while a controller is in use.
- **Mouse:** hovering a card focuses it with the same lift, clicking opens it, right click opens options, the wheel scrolls rows and tabs respond to clicks. Every target is big enough to hit easily. The pointer hides after a few seconds still.
- Switching between them is seamless: whichever was used last decides what's shown (focus ring vs hover), with no setting to change.

#### Knowing what's plugged in (Anthony: "it's like, oh, it's Xbox ... or mouse connected, it understands that")

Identify each device by `InputDevice` vendor/product ID and sources, from a small built-in table: Xbox (360/One/Series and "Generic X-Box pad" clones), PlayStation (DS3/DS4/DualSense), Nintendo (Switch Pro, Joy-Con), 8BitDo, Logitech, plus generic gamepad, mouse, keyboard, air-mouse remote, webcam, USB drive. Unknown pads are "Controller" with generic glyphs. The notice says what it is in plain words with our own drawn icon (never the makers' logos): "Xbox controller connected", "Mouse connected", "USB drive connected: 32 GB". Button hints everywhere switch to that family's labels (A/B/X/Y, Cross/Circle, Nintendo's swapped A/B), or to remote or mouse hints when those were used last. Settings → Controllers & Remotes lists each device with its type, a live button test, and Player 1/2 order; the right RetroArch autoconfig is applied per pad so games map correctly with no setup.

### Never stuck: the quick panel (release rule, built before anything else new)

Anthony, after getting stuck in a game: "make sure that there's always a way to exit the app ... let's say you press the Xbox home button, it should be a thing that pops up so you can go home ... really good and really easy". **A release fails if any app can trap you.**

- **Triggers, from any app** (the key helper, our accessibility service, sees keys before apps do): the pad's Guide/Xbox button; on pads without one, hold Start about 1 s, or Select+Start together; on the remote, Home as normal, plus hold Back about 1 s (a short Back still goes to the app as Back); on a keyboard, hold Esc or the Home key; with a mouse, a small Home pill that appears when the pointer touches the top edge. RetroArch's own menu combos (Start+Select, Back) stay on in the tuned `retroarch.cfg` only as a fallback: the helper takes those keys first, so the two never fight, and if the helper ever dies there is still a way out. The triggers are shown once in the welcome and always in Settings → Controllers.
- **The panel:** glass, our style, over whatever is running, with big choices: **Go Home** (focused first, so trigger then OK always gets you home), **Back to the app**, **Close this app** (stops it and goes Home), **Recent** (the last few apps, channels and games), volume and the clock. In a game the same panel adds Save, Load and Restart: one pause/exit panel, not two.
- **Guarantees:** pressing the trigger twice goes straight Home with no panel; if the panel can't open over an app, it goes Home instead; the helper lives in the launcher's process, and Retro Launcher switches it back on at start if Android turned it off. If an app swallows a key before the helper sees it, find another route on this box (ask before any system-partition change).
- **Routes that need no helper** (the guarantee must not rest on one mechanism): in games, RetroArch's own Start + Select quits and saves on any pad, and Guide is RetroArch's exit hotkey; Home during a game shows the game panel inside Retro Launcher (Stay on Home first, Back to the game, Quit). Save/Load/Restart from our panels wait until RetroArch's command port works on this box (see BOX-FACTS.md).
- **Installs:** always `bash launcher/tools/install.sh`, never a bare `adb install`. Android switches the helper off on every reinstall (that trapped Anthony in an NES game on 2026-11-10); the script switches it back on and fails if it isn't running, and the launcher does the same at every start and says so plainly if it can't.
- **Showing the way out:** a short hint at the bottom when a game starts ("Xbox button or hold Start: menu and Home"), once per app for other apps, and with a mouse plugged in a small Home button in the top-right corner of every app and game.
- **Test matrix** (muted, added to the smoke test): RetroArch, SmartTube while playing, Kodi, a full-screen Android game, the browser and Android's own Settings, each with the remote, the Xbox pad and the mouse. Report any app where it fails.

### The game Home Menu (Anthony: "pressing home should show up like a Wii-like thing ... right now ... you're just kind of boxed into this")

One menu for every way out of a game: the remote's Home, the Xbox button, Select+Start, Back in a game, or the game closing by itself. Our own glass style; no console maker's artwork or sounds.

- **What it shows:** the game's box art and title, its system, the controllers with player numbers, and big choices: **Resume** (first; pressing Home again also resumes), **Leave game** (confirm: "Leave *game*?" Leave / Stay), **Game options**, **Controls** (what each button does on that system), **All game settings** (Settings → Games).
- **How it works on this box:** Home pauses RetroArch in the background (`pause_nonactive`), so Resume brings the game back exactly. The menu lives in Retro Launcher, so no overlay or key helper is needed.
- **Game options**, per game, written as a RetroArch game override (`RetroLauncher/<core>/<game>.cfg`): picture (Sharp pixels / Smooth), screen (Whole pixels / Fill the screen), sound (Normal / Quieter / Louder), rewind (on / off). Applying restarts the game behind an "Applying…" card. Which pad is Player 1 waits for testing with real pads.
- **Saving, honestly:** RetroArch's command port never opens on this box and no save state has ever been written here (`Saves/states` is empty), so the menu can't save your exact spot. Leaving or applying options goes back to the game's own last save (written every 10 s). The menu says so plainly instead of promising "your progress is saved". Find out why states aren't written (RetroArch's own Start+Select quit, `savestate_auto_save`, folder rights) and bring back Save/Load slots and "exactly where you were" when it works. RetroArch's `QUITFOCUS` launch option quits on Home but saves nothing: not used.
- **Ordinary apps:** possible from the launcher side (the key helper knows the app that was in front, and Resume just reopens it), but it adds a step to every Home press, so it waits for Anthony's choice. For now Home goes straight Home from apps.

### Built-in games (late stage: after never-stuck, the game Home Menu, channel numbers and the TV Guide are solid)

A "Built-in games" shelf at the top of the Games tab: four small original games, each a few hundred KB at most, playable with remote, pad and mouse. All original: own names, characters, art, sounds and level designs (genre mechanics are fine; no existing game's name, characters, art, music, levels or exact look).

1. **Rising-stack tile swap** with carved tiki-mask tiles: swap two neighbouring tiles sideways, match three or more, chains, the stack rises.
2. **Fruit match-three** (Anthony confirmed: swap neighbours to match three, not a bubble shooter): own name, fruit pieces, own board shapes and level goals.
3. **Falling-block puzzle** with its own name, piece colours, layout and presentation (not the Tetris name or trade dress).
4. **Gear & Ghost**, Anthony's own game. Concept image `launcher/reference/gear-and-ghost-concept.webp` (reference only, never shipped): a steampunk workshop grid of gears, chains and conduits; Kiko the ghost moves on the grid; route energy from an Energy Source to a Portal; side panel with level name ("Level 4-2: Clockwork Path"), move limit ("24/35"), goal ("Activate portal"), spare-gear inventory, Restart and Hints. First write concrete rules (what a move is, how gears and chains pass or block energy, what placing a gear does) as a one-page design with 10 starter levels, and show Anthony before building.

How: native Kotlin on a Canvas inside the launcher (not WebView: the box's is version 76), one small shared shell (fixed-step loop, input for remote/pad/mouse, pause through the same game Home Menu, local high scores), 60 fps on the box, nearest-neighbour scaling from a low-resolution canvas. Art: pixel art is the real first-release style (Anthony: "we'll start with pixel art for now"), made with care: one palette, one tile size and one outline rule across all four games. Art loads from PNG sprite sheets with a documented layout so Anthony can swap in his own later with no code change; the spec (tile size, grid, palette, file names) goes in `launcher/games/ASSETS.md`.

### Corner mini player (with the player controls work, after the current bugs)

Anthony: "kind of like how phones do it". One thing plays at a time; starting something new replaces it.
- **Music and radio:** leaving Now Playing keeps the sound going and shows a small glass card in a screen corner on every tab: cover or logo, title, a thin progress line. It can be focused (OK opens Now Playing, play/pause on it, long press or a close control stops) but never takes focus by itself and never covers the dock. This replaces the earlier "Now playing chip on Home only".
- **TV and Videos:** leaving the player with Back offers the same with the live picture in a small corner window while browsing the guide or other tabs (the same player surface shrunk, not a second decoder). Measure on the box: if browsing drops below 60 fps or the video stutters, make it sound plus a still frame, or off by default with a Settings switch, and say which. OK on it returns to full screen.
- **YouTube** plays inside SmartTube, so our corner player can't show it. Check whether SmartTube's own picture-in-picture works on Android 7.1 here; surface it if it does, otherwise say plainly that YouTube stops when he leaves it.

### Interface sounds and lively motion (Animation quality stage)

Anthony: "buttons for menus ... that sound kind of like how an Xbox or PS5 sounds ... cool animations when you click buttons ... not as static". **Changed decision: navigation sounds are now on by default** (a switch and a volume level stay in Settings → Sound).
- **Sounds**, all original and synthesised like the start-up chime: the feel of a modern console interface (soft, short, tonal, quiet) without sampling or imitating any console's real sounds. Move focus (a very soft tick, a slightly different pitch per direction), select, back, tab change (a gentle whoosh), panel open/close, toggle on/off, "can't go further" bump, notice, game or app launch swell, favourite added. Preloaded in a SoundPool for zero delay, move ticks rate-limited so a held key never turns into noise, and silent automatically while TV, video or music plays or a game runs.
- **Motion**, from the shared timing values: cards dip and spring back on OK; buttons get a quick light sweep and pulse; toggles slide with a small overshoot; the focus ring glides between items instead of jumping; the end of a list gives a small rubber-band bump with the bump sound; the tab highlight stretches as it travels; dialogs grow from the control that opened them. All within the speed budget and "Reduce motion". Test that held-key scrolling stays at 60 fps with sounds on.

### Themes (designed now, extra themes built late)

Anthony: "I want this menu to have flavor ... you can install themes". This affects the kit now: **every colour, radius, type size, glass tint, focus style, timing and sound comes from one theme object, never hard-coded**, so a theme is a data swap.
- A theme is data only, no code: a small JSON of tokens (accent and surface colours, glass tint and opacity, card radius and shape, focus style such as glow, ring or lift, a font from the bundled ones, animation personality calm/snappy/bouncy), plus optional wallpapers, a sound pack, tab icons and a start-up intro variant. The default theme is today's look.
- **Settings → Themes:** big preview cards; applying morphs live (colours cross-fade, no restart); try before keeping. Five or six built-in original themes with real character: the default dark glass, a warm retro-arcade one with pixel accents, neon, soft pastel, a high-contrast large-type one for accessibility, and a "classic box" nod. Text contrast is checked automatically in every theme.
- **Installing:** a theme pack is a zip with `theme.json` and assets, installed from a USB drive, Downloads or Files, validated strictly (size limits, only known keys, images and sounds decoded safely, nothing executable). Later a small gallery read from a folder in the GitHub repo. Format documented in `launcher/themes/THEME-FORMAT.md`.
- Themes combine with the three home styles and the wallpaper picker. No copies of other platforms' themes, logos or sounds.

### Voice control (late, optional)

Anthony: "you can speak to it ... if it's not quite sure what you said, it will ask you to repeat ... I want to watch Bob the Sponge, or I want to listen to this music ... a cool animation, and then open the thing". A microphone button on Home (and a long press of a remote button) opens a listening card with a live sound-level animation; what was heard appears as text, then the launcher does it: open a channel by name ("watch Bob l'éponge"), a station, a game, an app, a video, play music by artist or album, go to a tab, search YouTube. When it isn't sure (low confidence, or two close matches), it says so and asks to repeat, or shows the two best matches to pick from. Microphone: the Logitech webcam already plugged into the box (046d:080a) has one; a USB microphone or the phone page's mic also work. Recognition: this box has no Google speech service, so use an offline recogniser (e.g. Vosk with a small English model, about 50 MB, checked for speed and memory on the 1 GB box) and a small fixed grammar built from the box's own channel, game and app names, so it understands those names well. The phone page's mic needs https (same constraint as the motion pointer). Off until switched on in Settings; nothing is recorded or sent anywhere.

### Button hints on every screen (a kit component)

Anthony: "if you have a controller, it'll show you press A for this, B for this". **Not built yet (checked 2026-11-11):** only the TV Guide, Totem Tide and the watching banner have their own hint text; no screen has the shared bar. Build one bottom bar in the kit, used by every screen, panel, dialog, player overlay, the game Home Menu, the keyboard, the built-in games and the browser: only the actions that work right now (e.g. A Open · B Back · Y Pin · Menu Options · LB/RB Tabs), with glyphs for the last-used input (Xbox letters and colours, PlayStation shapes, Nintendo layout, remote keys, mouse clicks, keyboard keys) that switch instantly when the input changes. Hidden while video plays unless the controls are up; inside the 720p safe area, never over content. Smoke check: the bar is present and not empty on each tab.

### Default wallpapers: real places around the world

Anthony: default images "of like world things, like kind of how Google" does. The default theme's wallpaper becomes a rotating set of royalty-free photos of places around the world (landscapes, coasts, mountains, cities at dusk, night skies), changing slowly by default (when the box wakes or every 30 minutes, with a slow cross-fade); "one fixed" and "daily" stay as options. The screensaver uses the same set with a small caption: place name and photographer. Check how many of today's 16 wallpapers are real place photos and grow the set to 20 to 30 across continents, sized for 1280x720 so the APK stays small; credits in `launcher/CREDITS.md`; extra packs downloadable later through themes. Home text must stay readable on every photo (check the bottom darkening). No photos from Google's or Apple's own collections.

### Launch splash (polish stage)

Anthony: "like the Wii U splash ... it shows the splash screen as it loaded the game". Opening an app or game today leaves a black gap of a second or two. Instead, show the thing's own art centred over the blurred wallpaper while it starts: games their box art and title, built-in games their pixel logo, apps their icon and name. It never adds waiting: it only covers the time the app is already taking and goes the moment the app draws its first picture, so a fast app gets almost none. On by default; Settings can turn it off for one app or for all. Themes may restyle it later.

### Closing back "into its box" (polish stage)

Anthony: "when you close the app for it to kind of go into the box of where it came from, like Apple ... nice and juicy". Android 7 doesn't let a launcher animate another app's window as it closes (only the opening zoom, which we already do), so we fake the return: the moment Retro Launcher comes back, the focused card's art starts full screen and shrinks smoothly into its card with a small spring at the end, while the rest of Home fades in. Same for built-in games and the players. Respects Reduce motion; stays within the speed budget.

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
- **A first-class design, not a utility page** (Anthony: "the mobile interface and the mobile app, I want to look really nice and be like designed correctly"). Same dark glass look as the launcher, built mobile-first for iPhone Safari and Android Chrome: safe-area insets, big thumb-reach targets, press feedback that feels like haptics, no layout jumps, portrait and one-handed, an "Add to Home Screen" manifest and icon so it opens full screen like an app. Sections: **Remote** (swipe pad, OK, Back, Home, volume), **Keyboard**, a **number pad** for channels, **tab shortcuts**, **Send to TV**, and **Now Playing** (current channel or video). Draw it in the mockup first and check it at phone sizes in the browser pane before wiring it up. Keep the page's transport (how presses reach the box) behind one small interface.
- **Send to TV** (approved): a box where a YouTube link is pasted (opens in SmartTube) or a direct video/audio link (plays in the built-in player), plus pick-a-file upload from the phone into Videos if that's cheap. Home network only, same pairing code as the remote.
- **Phone as a game controller** (Anthony's idea): RetroArch has a built-in network gamepad ("network RetroPad", UDP port 55400 and up, one port per player). The phone page can show a controller layout and send button states to it, so a phone works as player 1 or 2 in games. Turn it on in `retroarch.cfg` (`network_remote_enable`) only while the phone remote is switched on.
- **Later, optional: hosted remote.** The phone page could also be served from Anthony's site (`antonretro.com/remote`) as a proper installable web app, with the box holding an outbound connection to his server that relays key presses (a secure website can't call the box on the home network directly). Off by default, switched on in Settings; local stays the default. Open concerns: added delay; depends on his server and the internet; careful pairing, because the TV becomes reachable from outside; other people's installs would run through his server.

#### Settings → Casting (approved, build late with the phone remote)

In our style and plain words, explain and set up what the box really supports: linking the phone's YouTube app to SmartTube with its TV code; Miracast screen mirroring from Android/Windows (start the box's `com.droidlogic.miracast` from our page); receiving media over the home network through the box's Media Center. No foreign menus where avoidable. **Not in scope:** real Google Cast receiving (needs a Google device certificate this box doesn't have; the workarounds reuse keys extracted from real devices: don't implement or install any of that). No closed-source AirPlay receiver apps without running the adware scan and asking Anthony first.

- **Later: the phone as a motion pointer** (like pointing a remote at the screen). Constraint: phone browsers only give motion sensors to secure (https) pages, and iPhones also need a permission tap, so it can't work on the plain local http page; it needs the later hosted mode on Anthony's site or a local https setup. When available: a "Pointer" mode on the phone page (hold to point, tap to click, a recentre button) driving Retro Launcher's own on-screen pointer. It works in Retro Launcher, its built-in games and browser, not inside other apps.
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

## Works with no PC: the plain-APK rule

Anthony asked about people who "don't really know tech". **Retro Launcher must be fully usable when installed as a plain APK on a box, with no PC, no ADB and no AI.** Everything ADB does today needs an in-app path, guided by the first-time welcome:
- **Home app:** the welcome says "press Home and choose Retro Launcher, then Always" and notices when it's done.
- **Key helper:** a guided screen opens Android's accessibility page with a picture of exactly what to switch on, and notices when it works. Without it the launcher stays safe and never-stuck (RetroArch's Start+Select quit, the launcher's own menus).
- **Anything that needs `WRITE_SECURE_SETTINGS` or root** (HDMI power setting, animation speeds, pre-compiling, the GPU speed lock): optional extras, hidden or shown as "needs the PC setup" when the permission isn't there. Never a switch that silently does nothing.
- **RetroArch:** the Games tab offers to download it from the official libretro site and hands it to Android's installer (or the need disappears if the emulator cores are built in later).
- **Channels and guide:** built on the box from the welcome's country choice (port the logic of `build_channels.py`, or fetch a prebuilt list); no file push needed.
- **Updates:** the launcher checks the GitHub release feed, shows "Update available", downloads, checks the signature matches, and hands over to Android's installer.

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
- GPU speed lock (tested 2026-11-10, root shell): `echo 4 > /sys/class/mpgpu/min_freq; echo 3 > /sys/class/mpgpu/min_pp` holds the Mali-450 at 666 MHz with all 3 cores instead of scaling down to 500 MHz. Moving across Home: slowest 1% of frames 31 → 22 ms in the steadier run, slow frames ~7% either way, chip 65–66 °C. A small gain; it resets on restart (undo now: `echo 0 > min_freq; echo 1 > min_pp`). Applied at every startup by `/system/etc/init/retro-gpu.rc` (an init rule on `sys.boot_completed=1`; the launcher itself can't, apps have no root here). Verified across a restart. To remove it: remount `/system` read-write, delete that file, restart.
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

### Late extras: our own Files and Store (after TV Guide, browser and the phone page)

Reached from cards in Apps and Home, not new tabs (eight is the cap). Anthony: "I want the file manager to be redesigned in this OS style ... like the app store."

1. **Files**, in the kit's style: internal storage, USB drives and Downloads as big cards; folders as rows with type icons and thumbnails; open with the built-in player/viewer; copy, move, rename, delete (confirm dialog), new folder; "Add to Games/Videos"; APK install with the same risk confirmation as the browser. Hide the vendor File Browser from Apps once ours covers it.
2. **Store**, our own front end to F-Droid's catalogue: read the official repository index (signed: verify it, and every APK's hash and signature, as the F-Droid client does), stream-parse it (it is large), keep only apps that install on this box (minSdk ≤ 25, armeabi-v7a or universal). Screens: Featured for TV (a short curated list kept in this repo), categories, search, app page with screenshots and description, Install/Update/Remove through Android's package installer, an Updates list. The official F-Droid app stays installed as the fallback until ours is proven. Open-source catalogue only: no Play Store scraping, no other APK sites.
3. Then list the remaining vendor and third-party utilities still visible (gallery, music, clock, downloads, vendor Media Center and so on) and propose, for Anthony to choose, which deserve a built-in version and which should just be hidden. Don't build those unasked.

### Retro games look: sharp pixels by default

Anthony: "the games to be like pixelized, like Nearest Neighbor ... by default. And then obviously there's settings to change that." The tuned `retroarch.cfg` uses nearest-neighbour (`video_smooth = "false"`), no shader, and whole-number scaling: on the box's 720-line output NES and PlayStation (240 lines ×3) and Game Boy (144 ×5) fill the height exactly; SNES and Mega Drive get 672 lines and GBA 640 with a thin border, which avoids the shimmer of uneven pixels. Settings → Games → Picture style switches between Sharp pixels (default) and Smooth. Retro games only, not Android games.

## Build order

Each stage ends with a screenshot from the real box shown to Anthony. Don't move on if it isn't smooth.

0. **Never stuck (do first).** The quick panel above, with its test matrix passing on the box.
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

- **Target for the skill (Anthony): running it on a box sets up everything.** It downloads the signed Retro Launcher APK from the GitHub release, installs it and RetroArch with the tuned config, pushes the channel list and guide sources, grants the launcher's permissions and switches its key helper on over ADB (with the real-key check from `tools/install.sh`), pre-compiles the main apps, applies the approved speed tuning, sets Retro Launcher as the home app (the classic launcher stays as a fallback), and finishes with the smoke test. README and screenshots updated to match. Nothing is pushed, released or published without Anthony's OK.
- **The visualizer's "Classic pack" is never hosted in or downloaded from this repo or a release** (the data is Microsoft's). The only allowed route: an optional skill step that builds the pack on the user's own PC from their own installed Windows Media Player (the visualizer project's export step reading their `wmp.dll`), then pushes the result to `/sdcard/RetroLauncher/Visualizer/classic/` over ADB. It skips silently when the PC has no such file. Coordinate the export tool with the "Visualizations decomp Windows" session at that stage.

- **The skill sets itself up** (Anthony): when someone uses the Android TV box skill in Claude Code, it detects their computer (Windows, macOS or Linux) and fetches what it needs itself, such as Android's platform tools (ADB) from Google's official download, into a folder it controls, instead of asking them to install things by hand. It asks before downloading and says where it put them.

- Source lives in `launcher/`. The signed APK goes out as a GitHub Release, not into git.
- `skills/android-tv-box/SKILL.md` (and the private copy at `C:\Users\Anthony\.claude\skills\android-tv-box\SKILL.md`): step 7 installs Retro Launcher from the release instead of Projectivy and RetroArch as its hidden game engine; step 6 pushes the channel list to `/sdcard/RetroLauncher/channels.m3u` (Kodi's IPTV add-on becomes optional); step 8 sets it as default home; keep the "classic launcher stays" rule.
- `README.md`: new before/after image, replace the Projectivy mention, add a short "The home screen" section, update the "not affiliated" line.
- Update `C:\tmp\mxq-pro\AGENTS.md` and the video doc.
- Work is on branch `feat/tv-launcher`. Commit as you go. **Ask Anthony before pushing, opening the PR, or publishing the release.**

## After release: "Retro Launcher Setup" desktop app

Anthony: "an application version that ... makes it easy and connects with ADB ... very user friendly", for people without Claude Code or any AI tool. A small wizard, Windows first (kept portable to Mac and Linux), in Retro Launcher's visual style:
1. Turn on USB debugging, with pictures of the seven taps on "Build number".
2. Find the box on the network (scan for port 5555, or type its IP address).
3. Approve the prompt on the TV.
4. One **"Set up my box"** button that runs the same steps as the skill's Ship flow, with a progress list and plain-language results, a full backup first, and an **"Undo / restore my original home screen"** button.

The skill's scripts are the engine (one source of truth). Google's platform-tools are fetched from the official URL on first run rather than bundled (check the licence either way). Only the safe, predictable steps (install, configure, tune, verify); the judgement steps the skill does with an AI (auditing unknown apps, deciding what to remove) stay out, shown at most as a read-only report. It never flashes firmware.

## Things to settle with Anthony

- **Own repository: approved by Anthony (2026-11-11).** The launcher moves to `antonretro/retro-launcher` (private until the first release, public only with his OK), with APK releases; `android-tv-box-skill` stays the box-makeover tool and its launcher step downloads the latest signed APK from that release (Projectivy stays as the fallback until the first release exists).

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
