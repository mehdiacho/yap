"""Generate Play Store graphics (app icon 512x512 + feature graphic 1024x500) from a brand.

Reusable pattern: 3-stop diagonal gradient background, a white glyph drawn on a transparent
overlay and alpha-composited (so semi-transparent parts blend correctly), font fallback, and the
two exact sizes Play wants. Swap the CONFIG block per app; replace draw_glyph() with your mark.

    pip install pillow
    python generate_play_assets.py

Worked example this is derived from: Yap's 4-bar waveform + spark mark (see git history of
scratchpad/gen_play_assets.py in the Yap project).
"""
from PIL import Image, ImageDraw, ImageFont
import os

# ---------------------------------------------------------------- CONFIG (edit per app)
OUT = os.path.join(os.path.dirname(__file__), "out")
GRADIENT = [(0x6D, 0x5B, 0xEA), (0x5B, 0x5B, 0xD6), (0x4F, 0x46, 0xC8)]  # 3 stops, top-left -> bottom-right
WORDMARK = "App"                     # feature-graphic text
TAGLINE = "Your one-line pitch."     # feature-graphic subtitle
GLYPH_LETTER = "A"                   # fallback glyph = this letter (replace draw_glyph for a real mark)
# ----------------------------------------------------------------

os.makedirs(OUT, exist_ok=True)
C1, C2, C3 = GRADIENT


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(w, h):
    """Diagonal 3-stop gradient (top-left -> bottom-right)."""
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / w + y / h) / 2
            c = lerp(C1, C2, t / 0.55) if t < 0.55 else lerp(C2, C3, (t - 0.55) / 0.45)
            px[x, y] = c
    return img


def font(size, bold=True):
    for name in (["segoeuib.ttf", "arialbd.ttf", "DejaVuSans-Bold.ttf"] if bold
                 else ["segoeui.ttf", "arial.ttf", "DejaVuSans.ttf"]):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def draw_glyph(draw, cx, cy, scale):
    """The brand mark, centered at (cx, cy). REPLACE THIS with your real glyph.
    Default: the app's first letter in white. `scale` is roughly the glyph half-size in px."""
    f = font(int(scale * 1.6))
    box = draw.textbbox((0, 0), GLYPH_LETTER, font=f)
    w, h = box[2] - box[0], box[3] - box[1]
    draw.text((cx - w / 2 - box[0], cy - h / 2 - box[1]), GLYPH_LETTER, font=f, fill=(255, 255, 255, 255))


# ---------- App icon 512x512 (full-bleed square; Play masks corners itself) ----------
icon = gradient(512, 512).convert("RGBA")
hl = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
ImageDraw.Draw(hl).ellipse([-180, -220, 420, 240], fill=(255, 255, 255, 26))  # soft top highlight
icon = Image.alpha_composite(icon, hl)
draw_glyph(ImageDraw.Draw(icon), 256, 256, scale=150)
icon.convert("RGB").save(os.path.join(OUT, "icon-512.png"))

# ---------- Feature graphic 1024x500 ----------
fg = gradient(1024, 500).convert("RGBA")
hl = Image.new("RGBA", (1024, 500), (0, 0, 0, 0))
ImageDraw.Draw(hl).ellipse([-300, -320, 700, 260], fill=(255, 255, 255, 22))
fg = Image.alpha_composite(fg, hl)

# Glyph tile on the left, drawn on a transparent overlay so its alpha composites properly.
tile_cx, tile_cy, tile_half = 200, 250, 120
ov = Image.new("RGBA", fg.size, (0, 0, 0, 0))
ImageDraw.Draw(ov).rounded_rectangle(
    [tile_cx - tile_half, tile_cy - tile_half, tile_cx + tile_half, tile_cy + tile_half],
    radius=56, fill=(255, 255, 255, 34))
fg = Image.alpha_composite(fg, ov)
d = ImageDraw.Draw(fg)
draw_glyph(d, tile_cx, tile_cy, scale=78)
d.text((380, 155), WORDMARK, font=font(120), fill=(255, 255, 255, 255))
d.text((384, 300), TAGLINE, font=font(44, bold=False), fill=(238, 236, 255, 235))
fg.convert("RGB").save(os.path.join(OUT, "feature-1024x500.png"))

print("assets written to", OUT, "->", os.listdir(OUT))
