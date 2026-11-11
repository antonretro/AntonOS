# Built-in games: art and sound files

Every built-in game reads its pictures and sounds from files, so new art can be swapped in without changing code.

## Where files are read from

1. `/sdcard/RetroLauncher/GameArt/<game>/<file>` on the box, if it exists (drop your own files here to try them).
2. Otherwise the copy inside the app: `launcher/app/src/main/assets/games/<game>/<file>`.

Use the same file names and the same layout described below. A file that's missing or the wrong size falls back to the built-in one.

## Shared style (all four games)

- **Pixel art**, drawn at the size it's shown on a 320×180 screen, scaled up ×4 with sharp pixels on the TV (1280×720). Never draw at a larger size and shrink it.
- **Outline:** 1 px, colour `#2B1408` (dark carved brown), on the outside of every piece.
- **Palette:** few colours per piece: a body colour, one lighter tone (top edge/highlight), one darker tone (bottom edge), the outline, `#F4EAD5` for eyes and teeth, and one accent colour. Keep pieces distinguishable by **shape as well as colour** (for colour-blind players).
- **Transparent background** (PNG with alpha). No anti-aliasing, no soft shadows, no gradients inside a piece.
- **Sounds:** short mono WAV files, 22050 Hz, 16-bit, under 1 second (the game-over sound may be up to 2 s), quiet and soft, original (not taken from any existing game or console).

## Totem Tide (`tiki`)

### `tiles.png`: 84 × 42 px

A grid of 14 × 14 px tiles: **6 columns × 3 rows**.

| Column | Mask | Body colour | What makes it different without colour |
|---|---|---|---|
| 0 | Ember | red `#D64834` | round eyes, wide toothy grin, two little horns |
| 1 | Sun | gold `#E8AA28` | heavy brows, small "O" mouth, a crown of rays |
| 2 | Fern | green `#58A648` | angry slanted eyes, zigzag mouth, a leaf on top |
| 3 | Wave | teal `#289CAA` | wavy band across the forehead, closed eyes, smile |
| 4 | Moon | purple `#825CC4` | tall oval eyes, flat mouth, a moon mark on top |
| 5 | Shell | coral `#EC7096` | dot eyes, rosy cheeks, tongue out |

| Row | Use |
|---|---|
| 0 | normal |
| 1 | flash (shown while a match clears): the same mask, much lighter |
| 2 | dimmed (the incoming row rising from the bottom): the same mask at about 45 % brightness |

Each mask fills its tile: rounded top corners, the face inside a 10 px wide body, a 1 px crest pixel row at the top, an empty bottom row (1 px gap between stacked tiles).

### Sounds

| File | When |
|---|---|
| `swap.wav` | two masks swap (a light wooden knock) |
| `land.wav` | masks land after falling (very soft) |
| `clear.wav` | a match clears (a rising three-note marimba) |
| `chain.wav` | a chain (a longer, higher arpeggio) |
| `raise.wav` | the stack is raised by hand (a low knock) |
| `danger.wav` | the stack is at the top (two low notes) |
| `over.wav` | game over (a falling three-note phrase) |

The current files are made by `launcher/games/make_tiki_assets.py` (run it to rebuild them).

## Prompt you can give an image generator

> Pixel art sprite sheet, 84 by 42 pixels, transparent background, no anti-aliasing. A grid of 14x14 pixel tiles, 6 columns and 3 rows. Each column is a different carved wooden tiki mask, front view, filling its tile, 1-pixel dark brown outline (#2B1408): (1) red mask with round eyes and a wide toothy grin, (2) gold mask with heavy brows, a small O mouth and a crown of rays, (3) green mask with slanted angry eyes and a zigzag mouth and a leaf on top, (4) teal mask with a wavy forehead band, closed eyes and a smile, (5) purple mask with tall oval eyes and a flat mouth and a small moon mark on top, (6) coral pink mask with dot eyes, rosy cheeks and its tongue out. Row 1: the six masks normal. Row 2: the same six masks much lighter (flash). Row 3: the same six masks darker, at about 45% brightness. Original design, not based on any existing game.

Check the result at 100 % size (it should look clean at 14 × 14 per mask) before copying it to `/sdcard/RetroLauncher/GameArt/tiki/tiles.png`.
