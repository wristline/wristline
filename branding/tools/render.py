"""Render the PNGs in branding/png/ from the SVGs in branding/.

Usage: python3 branding/tools/render.py    (needs cairosvg and Pillow)
Output is deterministic, so a re-run with unchanged SVGs leaves git clean.
"""
import io
import re
from pathlib import Path

import cairosvg
from PIL import Image

BRANDING = Path(__file__).resolve().parent.parent
OUT = BRANDING / "png"

ACCENT = "#2F8FE8"
NAVY = "#16324F"       # launcher icon background
DARK_BG = "#0F1115"
LIGHT_BG = "#FFFFFF"
ICON_MARK = "#FFFFFF"  # store icon / avatar mark color: white on navy, same as the launcher icon


def svg_body(name, color=None):
    """Children of the root <svg> element of branding/<name>, with the accent optionally replaced."""
    body = re.search(r"<svg[^>]*>(.*)</svg>", (BRANDING / name).read_text(), re.S).group(1)
    return body.replace(ACCENT, color) if color else body


def painted_bbox(name):
    """Bounding box of the painted pixels of branding/<name>, in its own units (from a 4x render)."""
    im = Image.open(io.BytesIO(cairosvg.svg2png(url=str(BRANDING / name), scale=4)))
    return [v / 4 for v in im.getchannel("A").getbbox()]


def placed(name, w, h, bg, width_frac, color=None):
    """A w x h SVG filled with bg, with the painted part of <name> scaled to width_frac * w and centered."""
    x0, y0, x1, y1 = painted_bbox(name)
    k = width_frac * w / (x1 - x0)
    tx, ty = w / 2 - k * (x0 + x1) / 2, h / 2 - k * (y0 + y1) / 2
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}">'
            f'<rect width="{w}" height="{h}" fill="{bg}"/>'
            f'<g transform="translate({tx:.4f} {ty:.4f}) scale({k:.6f})">{svg_body(name, color)}</g></svg>')


def save(png_bytes, out, mode):
    Image.open(io.BytesIO(png_bytes)).convert(mode).save(OUT / out, optimize=True)
    print(f"png/{out}")


def main():
    OUT.mkdir(exist_ok=True)
    # Play Store icon: full-bleed 512x512 square, no transparency (Play applies its own mask).
    # Kept RGBA with alpha 255 everywhere, since the Play spec asks for a 32-bit PNG.
    icon = cairosvg.svg2png(bytestring=placed("wristline-mark.svg", 512, 512, NAVY, 0.8, ICON_MARK).encode())
    save(icon, "icon-512.png", "RGBA")
    save(icon, "avatar-512.png", "RGBA")  # GitHub org avatar
    # Play feature graphic: 24-bit PNG, no alpha.
    feature = placed("wristline-lockup-dark.svg", 1024, 500, DARK_BG, 0.6)
    save(cairosvg.svg2png(bytestring=feature.encode()), "feature-graphic-1024x500.png", "RGB")
    for variant, bg in (("dark", DARK_BG), ("light", LIGHT_BG)):
        lockup = cairosvg.svg2png(url=str(BRANDING / f"wristline-lockup-{variant}.svg"), background_color=bg)
        save(lockup, f"lockup-{variant}-1600x500.png", "RGB")
    mark = cairosvg.svg2png(url=str(BRANDING / "wristline-mark.svg"), output_width=512, output_height=512)
    save(mark, "mark-512-transparent.png", "RGBA")


if __name__ == "__main__":
    main()
