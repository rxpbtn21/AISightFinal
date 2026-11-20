#!/usr/bin/env python3
"""
Regenerates every AISight icon from branding/aisight-logo-source.png.

    python3 tools/make_icons.py                       # plain Lanczos upscaling
    python3 tools/make_icons.py --edsr EDSR_x4.pb     # AI super-resolution (needs opencv-contrib-python)

The source logo is small (177x191 px). If you ever get a bigger or vector version, just replace
branding/aisight-logo-source.png and run this again: the output sizes are generated for you.
Writes into app/src/main/res/ (drawable-nodpi, mipmap-*, mipmap-anydpi-v26) and branding/icon-512.png.
"""
import argparse
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
NAVY = (11, 23, 34, 255)          # #0B1722, same as @color/ic_launcher_background


def upscale(path, edsr):
    img = Image.open(path).convert("RGB")
    if edsr and os.path.exists(edsr):
        try:
            import cv2
            sr = cv2.dnn_superres.DnnSuperResImpl_create()
            sr.readModel(edsr)
            sr.setModel("edsr", 4)
            out = sr.upsample(cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR))
            return Image.fromarray(cv2.cvtColor(out, cv2.COLOR_BGR2RGB)), "EDSR x4 super-resolution"
        except Exception as exc:                                  # noqa: BLE001
            print("EDSR not usable, falling back to Lanczos:", exc)
    return img.resize((img.width * 4, img.height * 4), Image.LANCZOS), "Lanczos x4"


def find_emblem(img):
    """The white disc with the thin grey ring: bounding box of the dark pixels in the top 3/4 (no text there)."""
    gray = np.array(img.convert("L"))
    h, w = gray.shape
    ys, xs = np.where(gray[: int(h * 0.75), :] < 150)
    left, right, top = xs.min(), xs.max(), ys.min()
    diameter = right - left
    cx = (left + right) / 2.0
    cy = top + diameter / 2.0
    return cx, cy, diameter / 2.0


def circular_emblem(img, cx, cy, radius, size=1024):
    """Crop the disc, make everything outside the ring transparent (anti-aliased), return a size x size RGBA."""
    r = radius + 1.5
    box = (int(round(cx - r)), int(round(cy - r)), int(round(cx + r)), int(round(cy + r)))
    crop = img.crop(box).convert("RGB").resize((size, size), Image.LANCZOS)
    crop = crop.filter(ImageFilter.UnsharpMask(radius=1.4, percent=70, threshold=2))
    ss = 4
    mask = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * ss - 1, size * ss - 1), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = crop.convert("RGBA")
    out.putalpha(mask)
    return out


def fit(emblem, diameter):
    return emblem.resize((diameter, diameter), Image.LANCZOS)


def centered(canvas_size, content, bg=(0, 0, 0, 0)):
    canvas = Image.new("RGBA", (canvas_size, canvas_size), bg)
    off = (canvas_size - content.width) // 2
    canvas.alpha_composite(content, (off, off))
    return canvas


def save(img, *parts):
    path = os.path.join(*parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    return path


def rounded_square(size, bg, radius_frac=0.2):
    ss = 4
    big = Image.new("RGBA", (size * ss, size * ss), (0, 0, 0, 0))
    ImageDraw.Draw(big).rounded_rectangle((0, 0, size * ss - 1, size * ss - 1), radius=int(size * ss * radius_frac), fill=bg)
    return big.resize((size, size), Image.LANCZOS)


def round_disc(size, bg):
    ss = 4
    big = Image.new("RGBA", (size * ss, size * ss), (0, 0, 0, 0))
    ImageDraw.Draw(big).ellipse((0, 0, size * ss - 1, size * ss - 1), fill=bg)
    return big.resize((size, size), Image.LANCZOS)


def legacy(emblem, size, shape):
    ss = 4
    s = size * ss
    base = rounded_square(s, NAVY) if shape == "square" else round_disc(s, NAVY)
    base.alpha_composite(centered(s, fit(emblem, int(s * 0.80))))
    return base.resize((size, size), Image.LANCZOS)


def monochrome(emblem, canvas, diameter):
    """Themed (Android 13+) icon: only the dark linework of the logo, as an alpha shape."""
    e = fit(emblem, diameter)
    lum = np.array(e.convert("L")).astype(np.float32)
    alpha = np.clip((205.0 - lum) / 95.0, 0.0, 1.0) * (np.array(e.split()[3]).astype(np.float32) / 255.0)
    mono = np.zeros((diameter, diameter, 4), np.uint8)
    mono[..., 3] = (alpha * 255).astype(np.uint8)
    return centered(canvas, Image.fromarray(mono, "RGBA"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", default=os.path.join(ROOT, "branding", "aisight-logo-source.png"))
    ap.add_argument("--edsr", default=None, help="path to EDSR_x4.pb (optional, sharper upscale)")
    args = ap.parse_args()

    up, how = upscale(args.source, args.edsr)
    cx, cy, radius = find_emblem(up)
    print(f"upscaled with {how}: {up.size}; emblem centre=({cx:.0f},{cy:.0f}) radius={radius:.0f}px")
    emblem = circular_emblem(up, cx, cy, radius)

    # in-app logo + adaptive icon layers (512 px)
    save(fit(emblem, 512), RES, "drawable-nodpi", "aisight_logo.png")
    save(centered(512, fit(emblem, int(512 * 0.64))), RES, "drawable-nodpi", "ic_launcher_foreground.png")
    save(monochrome(emblem, 512, int(512 * 0.64)), RES, "drawable-nodpi", "ic_launcher_monochrome.png")

    # legacy launcher icons for Android 6-7 (no adaptive icons there)
    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        save(legacy(emblem, size, "square"), RES, f"mipmap-{density}", "ic_launcher.png")
        save(legacy(emblem, size, "round"), RES, f"mipmap-{density}", "ic_launcher_round.png")

    # adaptive icon definitions (Android 8+, themed icon on 13+)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@color/ic_launcher_background" />\n'
           '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
           '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
           '</adaptive-icon>\n')
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        path = os.path.join(RES, "mipmap-anydpi-v26", name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(xml)

    # 512 px store / README icon
    store = rounded_square(512, NAVY, 0.22)
    store.alpha_composite(centered(512, fit(emblem, int(512 * 0.80))))
    save(store, ROOT, "branding", "icon-512.png")
    print("icons written.")


if __name__ == "__main__":
    main()
