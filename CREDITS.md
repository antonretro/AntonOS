# Credits

- **AntonOS**: AntonRetro (MIT).
- **Built-in music**: "Dial-up Daydream" and "CD-ROM Lounge" by AntonRetro & Claude, and "Jetpack Postcard", "Slap Happy Forklift" and "Lemonade Satellite" by AntonRetro (MIT), in two versions: rendered with the **MuseScore General** SoundFont (MIT; FluidR3 by Frank Wen, FluidR3Mono by Michael Cowgill, adaptation by S. Christian Collins, Temple Blocks by Ethan Winer, Drumline Cymbals by Michael Schorsch; notices in `app/src/main/assets/music/LICENSE-MuseScore-General.md`), and the original MIDI files played by the box's own synthesiser.
- **Music visualizer**: Retro Visualizer by AntonRetro (MIT, `app/src/main/assets/visualizer/LICENSE`). It contains maths lookup tables from **AMD LibM** (aocl-libm-ose), BSD 3-Clause (`app/src/main/assets/visualizer/THIRD_PARTY_AMD_LIBM.txt`). No Microsoft code or data is included.
- **Totem Tide** (built-in game): original game, pixel art and sounds by this project, made by the scripts in `games/`.
- **Media playback**: AndroidX Media3 / ExoPlayer (Apache 2.0).
- **TV channel list** (fetched at runtime, not bundled): the [iptv-org](https://github.com/iptv-org/iptv) public playlists and channel database.
- **TV guide data** (fetched at runtime, not bundled): public XMLTV feeds from [i.mjh.nz](https://i.mjh.nz) and [epgshare01](https://epgshare01.online).
- **Game box art** (fetched at runtime when the user says yes, not bundled): [libretro-thumbnails](https://github.com/libretro-thumbnails/libretro-thumbnails).
- **Game engine**: RetroArch / libretro (installed separately, GPL-3.0). Extra libretro cores are downloaded at runtime from libretro's build server when the user asks (each under its own licence), not bundled.
- **System intro sounds**: original, synthesised by `tools/make_system_sounds.py` (no console's real start-up sound).

## World photo wallpapers

31 photos of places around the world, used as the default rotating wallpaper and in the screensaver (app/src/main/assets/wallpapers, cropped to 1280x720). All from Unsplash under the [Unsplash License](https://unsplash.com/license) (free to use, no permission needed; credit given here and in the screensaver caption).

| # | Place | Photographer | Photo page |
|---|---|---|---|
| 00 | Lofoten, Norway | Danny Rienecker | https://unsplash.com/photos/henningsvaer-fishing-village-in-norway-GmgCJ0z2lyc |
| 01 | Santorini, Greece | James Ting | https://unsplash.com/photos/white-buildings-on-oia-cliffside-6N2mSJsKTtA |
| 02 | Lauterbrunnen, Switzerland | Robin Ulrich | https://unsplash.com/photos/houses-on-green-grass-field-near-mountain-under-blue-sky-during-daytime-QuZ6kOAeW2o |
| 03 | Dolomites, Italy | Michiel Ronde | https://unsplash.com/photos/seceda-mountain-peaks-in-the-dolomites-p7S6AxykCl0 |
| 04 | Isle of Skye, Scotland | martin bennie | https://unsplash.com/photos/the-storr-range-with-a-body-of-water-in-the-foreground-r3peJAPApQw |
| 05 | Kirkjufell, Iceland | Joseph Corl | https://unsplash.com/photos/green-mountain-with-waterfalls-under-cloudy-sky-5ERrD2oamUc |
| 06 | Paris, France | Léonard Cotte | https://unsplash.com/photos/pont-alexandre-iii-bridge-in-paris-R5scocnOOdM |
| 07 | Kyoto, Japan | Su San Lee | https://unsplash.com/photos/pagoda-surrounded-by-trees-E_eWwM29wfU |
| 08 | Mount Fuji, Japan | Clay Banks | https://unsplash.com/photos/mount-fuji-at-night-in-japan-u27Rrbs9Dwc |
| 09 | Ha Long Bay, Vietnam | Vivu Vietnam | https://unsplash.com/photos/karst-islands-in-the-ocean-at-sunset-wTJQjCQm90k |
| 10 | Bali, Indonesia | Niklas Weiss | https://unsplash.com/photos/rice-terraces-in-tegelalang-bali--2WlTWZLnRc |
| 11 | Hong Kong | Natalya Letunova | https://unsplash.com/photos/illuminated-skyline-and-harbor-in-hong-kong-lZXyGjsRnP0 |
| 12 | Sahara, Morocco | Bernd Dittrich | https://unsplash.com/photos/golden-sand-dunes-stretch-across-a-vast-desert-landscape-AIqyp2TEPgw |
| 13 | Deadvlei, Namibia | NIR HIMI | https://unsplash.com/photos/desert-landscape-with-sand-dunes-and-dry-grass-xxjqHn_moso |
| 14 | Cape Town, South Africa | Hennie Stander | https://unsplash.com/photos/table-mountain-visible-across-the-ocean-at-sunset-61QtMtEmVGc |
| 15 | Serengeti, Tanzania | Tanzania Wild Sky | https://unsplash.com/photos/three-hot-air-balloons-flying-over-a-river-rcGU1VUgLos |
| 16 | Moraine Lake, Canada | John Lee | https://unsplash.com/photos/scenery-of-mountain-oMneOBYhJxY |
| 17 | Grand Canyon, USA | Steve Gribble | https://unsplash.com/photos/a-winding-river-flows-through-a-grand-canyon-AmYCD-vXkIU |
| 18 | Yosemite, USA | Aniket Deole | https://unsplash.com/photos/granite-cliffs-and-waterfall-in-yosemite-M6XC789HLe8 |
| 19 | Torres del Paine, Chile | Marek Piwnicki | https://unsplash.com/photos/a-mountain-range-with-a-lake-in-the-foreground-wgsu3WzFZ5c |
| 20 | Machu Picchu, Peru | Willian Justen de Vasconcellos | https://unsplash.com/photos/machu-picchu-ruins-among-misty-mountains-4hMET7vYTAQ |
| 21 | New York, USA | Tintinburgh | https://unsplash.com/photos/new-york-city-skyline-illuminated-at-night-across-the-water-RQOGrHWDEXM |
| 22 | Rio de Janeiro, Brazil | Alan Aprilio | https://unsplash.com/photos/christ-the-redeemer-statue-overlooking-rio-de-janeiro-at-sunset-zUJFq4YOYIQ |
| 23 | Milford Sound, New Zealand | Sébastien Goldberg | https://unsplash.com/photos/body-of-water-near-mountain-under-blue-sky-during-daytime-oXgPDa0OtAk |
| 24 | Twelve Apostles, Australia | Wenhao Ji | https://unsplash.com/photos/rock-formation-on-seashore-QwouEQZocac |
| 25 | Uluru, Australia | Wietse Jongsma | https://unsplash.com/photos/uluru-at-sunset-with-vast-desert-landscape-XtdoflZSMYI |
| 26 | Atacama, Chile | Daniel Arroyo | https://unsplash.com/photos/a-view-of-a-mountain-range-from-a-high-point-of-view-xGTGUDQDTIA |
| 27 | Valletta, Malta | Micaela Parente | https://unsplash.com/photos/brown-concrete-dome-building-near-body-of-water--Jd2XVXLQ0U |
| 28 | Marsaxlokk, Malta | CALIN STAN | https://unsplash.com/photos/blue-and-yellow-boat-on-water-near-brown-concrete-building-during-daytime-3F904UCiZGw |
| 29 | Toronto, Ontario | Jochem Raat | https://unsplash.com/photos/toronto-skyline-with-cn-tower-s0grRYEDaL4 |
| 30 | Muskoka, Ontario | April Barber | https://unsplash.com/photos/brown-wooden-bench-on-dock-near-lake-during-daytime-M4RVCkMpb1I |
