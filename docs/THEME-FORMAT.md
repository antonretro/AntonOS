# AntonOS theme files

A theme is one small JSON file in `/sdcard/RetroLauncher/Themes/` on the box (for example `sunset.json`). AntonOS lists it in **Settings → Themes** under the built-in ones. Files over 16 KB, unknown values or colours that aren't `#RRGGBB` / `#AARRGGBB` are ignored, so a broken file can never half-apply.

```json
{
  "name": "Sunset",
  "about": "Warm orange glass",
  "accent": "#FF8C28",
  "focusFill": "#FEFFC440",
  "focusText": "#281406",
  "glassTint": "#7C301A0C",
  "glassStrong": "#B2281608",
  "glassEdge": "#30FFC478",
  "text": "#FFF4DC",
  "textDim": "#AAFFE4BE",
  "dimScrolled": "#96140A04",
  "textScale": 1.0
}
```

| Key | What it colours |
|---|---|
| `accent` | progress bars, highlights |
| `focusFill` | the focused card, button or tab (keep its alpha at FE: a fully solid fill draws square corners on Android 7) |
| `focusText` | text on the focused item (AntonOS swaps it for black or white if the contrast is under 4.5:1) |
| `glassTint`, `glassStrong`, `glassEdge` | the frosted glass panels, the stronger glass of dialogs and banners, and their thin edge |
| `text`, `textDim` | normal and secondary text |
| `dimScrolled` | how much the wallpaper darkens under the rows |
| `textScale` | type size, 0.9 to 1.3 |

Any key left out keeps the Glass theme's value. Only colours and type size for now: no images, fonts or code.
