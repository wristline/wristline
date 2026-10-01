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

## Provider badge colors

The session list and detail screens mark each session with a 16dp provider badge
(`ProviderBadge` in `app/src/main/kotlin/dev/wristline/watch/ui/Common.kt`). Its colors are sampled
from each provider's official icon; the white bold "C" on them is our own monogram, not either
provider's logo.

| Badge | Color | Sampled from |
| --- | --- | --- |
| Claude Code: circle | `#D97757` | [claude.ai/favicon.ico](https://claude.ai/favicon.ico) (every opaque pixel) and the top of [claude.ai/apple-touch-icon.png](https://claude.ai/apple-touch-icon.png), whose background runs to `#DB6945` at the bottom. The "Claude by Anthropic" icon on [Google Play](https://play.google.com/store/apps/details?id=com.anthropic.claude) has the same gradient (`#DA7353` to `#DB6A46` after WebP compression). |
| Codex: rounded square, vertical gradient | `#B7AEFF` (top) to `#3B36FF` (bottom) | `ChatGPT.app/Contents/Resources/icon-codex-light.png` (1024×1024, Display P3) in the Codex desktop app, [ChatGPT-darwin-arm64-26.928.21956.zip](https://persistent.oaistatic.com/codex-app-prod/ChatGPT-darwin-arm64-26.928.21956.zip) from the app's update feed ([appcast.xml](https://persistent.oaistatic.com/codex-app-prod/appcast.xml)). Converted to sRGB, then averaged across the cloud shape in two bands a tenth of its height tall, 5 to 15% and 85 to 95% of the way down. |

White on the orange is 3.1:1; on the Codex gradient it runs from 2.0:1 at the very top to 6.7:1 at
the bottom (3.7:1 at the middle, behind the letter's center).

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
- `app/src/main/res/drawable/ic_notification.xml`: the notification, Now Bar and ongoing-activity
  icon, the mono mark in white at its own 512-unit coordinates.
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
