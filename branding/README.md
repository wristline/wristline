# Wristline brand assets

The Wristline mark is a watch case (a solid rounded square) with a round dial cut out of it and a
terminal prompt `>_` in the dial. The wordmark is Inter Display SemiBold converted to outlines, so
no font is needed to render any of these files.

## Colors

| Name | Hex | Used for |
| --- | --- | --- |
| Accent | `#2F8FE8` | The mark |
| Navy | `#16324F` | Launcher icon and store icon background (the mark is white on it) |
| Dark background | `#0F1115` | Feature graphic, dark lockup PNG |
| Wordmark on dark | `#EDEFF3` | `wristline-lockup-dark.svg` |
| Wordmark on light | `#14171C` | `wristline-lockup-light.svg` |

## Clear space

Keep empty space of at least a quarter of the mark's height on every side of the mark or the
lockup. The lockup files already include more than that; the mark files have only a small margin
(40 of 512 units), so add padding around them. Do not stretch, rotate, outline or add effects;
only the mono files may be set in another single solid color.

## Files

| File | Use |
| --- | --- |
| `wristline-mark.svg` | The mark in accent blue, transparent background. Master for everything else. |
| `wristline-mark-mono.svg` | One-color mark (black; may be set in any single color). |
| `wristline-lockup-dark.svg` | Mark + wordmark for dark backgrounds (light text). |
| `wristline-lockup-light.svg` | Mark + wordmark for light backgrounds (dark text). |
| `wristline-lockup-mono.svg` | One-color lockup. |
| `png/icon-512.png` | Google Play app icon: 512×512, full-bleed, opaque, white mark on navy like the launcher icon. Play rounds the corners itself. |
| `png/avatar-512.png` | GitHub organization avatar (same image as the Play icon). |
| `png/feature-graphic-1024x500.png` | Google Play feature graphic: dark lockup on `#0F1115`, 24-bit, no alpha. |
| `png/lockup-dark-1600x500.png` | Dark lockup on `#0F1115` (slides, posts). |
| `png/lockup-light-1600x500.png` | Light lockup on white. |
| `png/mark-512-transparent.png` | The mark, transparent background. |
| `tools/render.py` | Regenerates everything in `png/`. |

Copies elsewhere (update them when the mark changes):

- `app/src/main/res/drawable/ic_launcher_foreground.xml`: the launcher icon foreground, a
  VectorDrawable version of the mark scaled into the 66dp adaptive-icon safe zone.
- `docs/assets/wristline-mark.svg`: for the GitHub Pages site, which only serves `docs/`.
- `docs/brand/` in [wristline-bridge](https://github.com/wristline/wristline-bridge): the lockup
  SVGs for its README.

## Regenerating the PNGs

```sh
pip install cairosvg pillow
python3 branding/tools/render.py
```

The output is deterministic: re-running it with unchanged SVGs leaves `git status` clean.

## License

The mark, the lockups and every file in this directory are under the MIT License, like the code
(see [LICENSE](../LICENSE)).
