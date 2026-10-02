"""生成八万TV 的图标与 TV 横幅（Android TV banner 必须是 320x180 的 xhdpi 图）。

只依赖 Pillow，输出：
  app/src/main/res/mipmap-xxxhdpi/ic_launcher.png        (192)
  app/src/main/res/mipmap-xxhdpi/ic_launcher.png         (144)
  app/src/main/res/mipmap-xhdpi/ic_launcher.png          (96)
  app/src/main/res/mipmap-hdpi/ic_launcher.png           (72)
  app/src/main/res/mipmap-mdpi/ic_launcher.png           (48)
  app/src/main/res/drawable-xhdpi/app_banner.png         (320x180)
"""
from __future__ import annotations

import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

INK_DEEP = (7, 8, 16)
VIOLET = (26, 18, 64)
ACCENT = (90, 169, 255)
ACCENT_BRIGHT = (142, 197, 255)
PINK = (255, 107, 159)

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
    r"C:\Windows\Fonts\arialbd.ttf",
]


def load_font(size: int) -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    return ImageFont.load_default()


def vertical_gradient(size: tuple[int, int], top: tuple[int, int, int],
                      bottom: tuple[int, int, int]) -> Image.Image:
    w, h = size
    img = Image.new("RGB", (1, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        px[0, y] = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
    return img.resize((w, h), Image.BILINEAR)


def radial_glow(base: Image.Image, center: tuple[int, int], radius: int,
                color: tuple[int, int, int], strength: float) -> None:
    """在 base 上叠加一团径向光晕（用一个小图放大做，速度可接受）。"""
    r = max(8, radius)
    glow = Image.new("L", (64, 64), 0)
    d = ImageDraw.Draw(glow)
    d.ellipse((0, 0, 63, 63), fill=255)
    glow = glow.resize((r * 2, r * 2), Image.BICUBIC)
    glow = glow.point(lambda v: int(v * strength))
    layer = Image.new("RGB", glow.size, color)
    base.paste(layer, (center[0] - r, center[1] - r), glow)


def rounded_mask(size: tuple[int, int], radius: int) -> Image.Image:
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return mask


def draw_centered(draw: ImageDraw.ImageDraw, box: tuple[int, int], text: str,
                  font: ImageFont.FreeTypeFont, fill, anchor_center: tuple[int, int]) -> None:
    draw.text(anchor_center, text, font=font, fill=fill, anchor="mm")


def make_icon(size: int) -> Image.Image:
    """方形图标：深空底 + 双色光晕 + 「八万」字样。"""
    base = vertical_gradient((size, size), VIOLET, INK_DEEP)
    radial_glow(base, (int(size * 0.22), int(size * 0.24)), int(size * 0.55), ACCENT, 0.55)
    radial_glow(base, (int(size * 0.86), int(size * 0.84)), int(size * 0.48), PINK, 0.40)

    draw = ImageDraw.Draw(base, "RGBA")
    # 外描边
    draw.rounded_rectangle(
        (2, 2, size - 3, size - 3), max(6, size // 8),
        outline=ACCENT_BRIGHT + (110,), width=max(2, size // 64),
    )
    # 底部装饰弧（象征屏幕/信号）
    arc_w = max(2, size // 40)
    for i, alpha in enumerate((150, 100, 60)):
        inset = int(size * (0.14 + i * 0.10))
        draw.arc(
            (inset, int(size * 0.46), size - inset, int(size * 1.22)),
            start=200, end=340, fill=ACCENT_BRIGHT + (alpha,), width=arc_w,
        )

    big = load_font(int(size * 0.44))
    small = load_font(int(size * 0.15))
    draw_centered(draw, (size, size), "八万", big, (255, 255, 255), (size // 2, int(size * 0.44)))
    draw_centered(draw, (size, size), "TV", small, ACCENT_BRIGHT, (size // 2, int(size * 0.72)))

    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(base, (0, 0), rounded_mask((size, size), max(6, size // 6)))
    return out


def make_banner() -> Image.Image:
    """TV 横幅 320x180：左侧 logo，右侧字标。"""
    w, h = 320, 180
    base = vertical_gradient((w, h), VIOLET, INK_DEEP)
    radial_glow(base, (40, 34), 130, ACCENT, 0.60)
    radial_glow(base, (280, 156), 120, PINK, 0.40)

    draw = ImageDraw.Draw(base, "RGBA")
    # 左侧圆角方块 + 八万
    box = 96
    x0, y0 = 18, (h - box) // 2
    draw.rounded_rectangle(
        (x0, y0, x0 + box, y0 + box), 24,
        fill=(10, 12, 26, 200), outline=ACCENT_BRIGHT + (160,), width=2,
    )
    draw.text((x0 + box // 2, y0 + box // 2 - 6), "八万",
              font=load_font(38), fill=(255, 255, 255), anchor="mm")
    draw.text((x0 + box // 2, y0 + box // 2 + 26), "TV",
              font=load_font(16), fill=ACCENT_BRIGHT, anchor="mm")

    # 右侧文案
    tx = x0 + box + 16
    draw.text((tx, 62), "八万TV", font=load_font(34), fill=(255, 255, 255), anchor="lm")
    draw.text((tx, 98), "央视直播 · 影视聚合", font=load_font(15), fill=ACCENT_BRIGHT, anchor="lm")
    draw.text((tx, 122), "为电视大屏而生", font=load_font(13), fill=(150, 160, 190), anchor="lm")
    # 底部彩条
    draw.rounded_rectangle((tx, 138, tx + 116, 142), 2, fill=ACCENT_BRIGHT)
    draw.rounded_rectangle((tx + 118, 138, tx + 168, 142), 2, fill=PINK)
    return base


def main() -> None:
    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, size in densities.items():
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)
        icon = make_icon(size)
        icon.save(os.path.join(d, "ic_launcher.png"))
        # 圆形图标（部分桌面会用）
        round_icon = make_icon(size)
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(round_icon, (0, 0), mask)
        out.save(os.path.join(d, "ic_launcher_round.png"))
        print("icon", folder, size)

    # TV banner 必须放在 drawable-xhdpi
    bdir = os.path.join(RES, "drawable-xhdpi")
    os.makedirs(bdir, exist_ok=True)
    banner = make_banner()
    banner.save(os.path.join(bdir, "app_banner.png"))
    print("banner", banner.size)

    # 开屏图（复用图标的大尺寸版本，给纯 XML 场景兜底）
    ddir = os.path.join(RES, "drawable-xxhdpi")
    os.makedirs(ddir, exist_ok=True)
    make_icon(384).save(os.path.join(ddir, "logo_bawan.png"))
    print("logo done")


if __name__ == "__main__":
    main()
