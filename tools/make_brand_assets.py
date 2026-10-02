"""焰火TV 图形资源生成。

输入（放在项目 `design/` 目录，由设计稿提供）：
  design/logo.png    方形 Logo（焰火 + 电视框），同时用作 App 图标与开屏 Logo
  design/splash.png  横版开屏主视觉（Logo + slogan「焰火随想 美好随现」）

输出：
  app/src/main/res/mipmap-*/ic_launcher.png          自适应图标前景/常规图标
  app/src/main/res/mipmap-*/ic_launcher_round.png    圆形图标
  app/src/main/res/drawable-*/ic_launcher_foreground.png  自适应图标前景
  app/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml     自适应图标描述
  app/src/main/res/drawable-nodpi/splash_brand.png   开屏主视觉（整图，含 slogan）
  app/src/main/res/drawable-nodpi/logo_yanhuo.png    开屏/加载用 Logo
  app/src/main/res/drawable-xhdpi/app_banner.png     电视 Leanback 横幅 320x180

要求：只依赖 Pillow。slogan 与图标内容不做任何改动，只做裁切/缩放/格式转换。
"""
from __future__ import annotations

import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
DESIGN = os.path.join(ROOT, "design")

LOGO_SRC = os.path.join(DESIGN, "logo.png")
SPLASH_SRC = os.path.join(DESIGN, "splash.png")

# 图标在 Android 8+ 的自适应图标里，前景只占中间 66% 左右
ADAPTIVE_SAFE = 0.66


def load(path: str) -> Image.Image:
    if not os.path.exists(path):
        raise SystemExit(f"缺少设计稿：{path}")
    img = Image.open(path)
    # 设计稿是 JPEG 但扩展名写成 png，统一转成真正的 RGBA
    if img.mode != "RGBA":
        img = img.convert("RGBA")
    return img


def fit_square(img: Image.Image, size: int) -> Image.Image:
    """等比缩放到能盖住 size×size，然后居中裁切。"""
    w, h = img.size
    scale = max(size / w, size / h)
    new = img.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.LANCZOS)
    left = (new.width - size) // 2
    top = (new.height - size) // 2
    return new.crop((left, top, left + size, top + size))


def rounded(img: Image.Image, radius_ratio: float = 0.22) -> Image.Image:
    w, h = img.size
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, w - 1, h - 1), int(min(w, h) * radius_ratio), fill=255
    )
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def circle(img: Image.Image) -> Image.Image:
    w, h = img.size
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, w - 1, h - 1), fill=255)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def make_banner(logo: Image.Image, splash: Image.Image) -> Image.Image:
    """电视横幅 320x180。

    注意不要直接把开屏左半图铺上去又叠文字 —— 左半图里自带了 slogan，
    会和后画的文字叠在一起（第一版就踩了这个坑）。
    这里改成：左侧只放「图形 Logo」（从开屏图里裁出圆形图形区），
    右侧重新排品牌名 + slogan，保证清晰不重叠。
    """
    W, H = 320, 180
    # 深色底：取开屏图右上角那块纯背景色，保持品牌色调一致
    sw, sh = splash.size
    bg_patch = splash.crop((int(sw * 0.72), 0, sw, int(sh * 0.35))).resize((W, H), Image.LANCZOS)
    bg = bg_patch.convert("RGBA")

    # 左侧：从开屏图裁出圆形图形区（开屏图里 Logo 位于左约 3%~40% 宽度）
    glyph_box = (int(sw * 0.015), int(sh * 0.02), int(sh * 0.98), sh)
    glyph = splash.crop(glyph_box)
    badge = int(H * 0.78)
    glyph = fit_square(glyph, badge)
    glyph = circle(glyph)
    # 圆外发光，让它在深底上更立体
    glow = Image.new("RGBA", (badge + 16, badge + 16), (0, 0, 0, 0))
    halo = Image.new("RGBA", glow.size, (110, 170, 255, 120))
    halo.putalpha(glyph.split()[3].resize(glow.size).filter(ImageFilter.GaussianBlur(7)))
    glow.alpha_composite(halo)
    glow.alpha_composite(glyph, (8, 8))
    gx, gy = int(H * 0.09), (H - glow.height) // 2
    bg.alpha_composite(glow, (gx, gy))

    # 右侧文字
    draw = ImageDraw.Draw(bg, "RGBA")
    font = _font(r"C:\Windows\Fonts\msyhbd.ttc", 30)
    small = _font(r"C:\Windows\Fonts\msyh.ttc", 13)
    tx = gx + glow.width + 10
    if font is not None:
        draw.text((tx, H // 2 - 20), "焰火TV", font=font, fill=(255, 255, 255, 255))
    if small is not None:
        draw.text((tx + 1, H // 2 + 16), "焰火随想 美好随现", font=small, fill=(178, 200, 236, 240))
    return bg.convert("RGB")


def _font(path: str, size: int):
    try:
        from PIL import ImageFont
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
        for p in (r"C:\Windows\Fonts\msyhbd.ttc", r"C:\Windows\Fonts\msyh.ttc",
                  r"C:\Windows\Fonts\simhei.ttf"):
            if os.path.exists(p):
                return ImageFont.truetype(p, size)
    except Exception:
        pass
    return None


def main() -> None:
    logo = load(LOGO_SRC)
    splash = load(SPLASH_SRC)
    print("logo", logo.size, "splash", splash.size)

    # ---------- 图标 ----------
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
        square = fit_square(logo, size)
        rounded(square).save(os.path.join(d, "ic_launcher.png"))
        circle(square).save(os.path.join(d, "ic_launcher_round.png"))
        print("icon", folder, size)

    # 自适应图标前景：内容缩到安全区，四周透明，避免被系统裁掉边缘
    for folder, size in densities.items():
        d = os.path.join(RES, folder)
        inner = max(1, int(size * ADAPTIVE_SAFE))
        fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        piece = fit_square(logo, inner)
        off = (size - inner) // 2
        fg.paste(piece, (off, off), piece)
        fg.save(os.path.join(d, "ic_launcher_foreground.png"))

    # 自适应图标描述
    # 注意：前景资源统一用 ic_launcher_foreground，圆形图标也复用同一个前景，
    # 不要写成 ic_launcher_round_foreground（那个资源不存在，会导致资源链接失败）。
    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    for name in ("ic_launcher", "ic_launcher_round"):
        xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ink" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
        with open(os.path.join(anydpi, name + ".xml"), "w", encoding="utf-8") as f:
            f.write(xml)
    print("adaptive icon xml")

    # ---------- 开屏主视觉（整图，含 slogan，不做任何改动）----------
    nodpi = os.path.join(RES, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    # 1638x922 够 1080p 用，体积可控
    splash_scaled = splash.resize((1638, int(1638 * splash.height / splash.width)), Image.LANCZOS)
    splash_scaled.convert("RGB").save(os.path.join(nodpi, "splash_brand.png"), optimize=True)
    print("splash_brand", splash_scaled.size)

    # 开屏 Logo（只取图形，不含文案，便于做动画时单独处理）
    side = min(splash.size)
    logo_part = splash.crop((0, 0, side, side))
    logo_part.resize((512, 512), Image.LANCZOS).convert("RGB").save(
        os.path.join(nodpi, "logo_yanhuo.png"), optimize=True)
    print("logo_yanhuo 512")

    # ---------- 电视横幅 ----------
    xhdpi = os.path.join(RES, "drawable-xhdpi")
    os.makedirs(xhdpi, exist_ok=True)
    make_banner(logo, splash).save(os.path.join(xhdpi, "app_banner.png"), optimize=True)
    print("app_banner 320x180")

    # 清掉旧的临时 logo
    old = os.path.join(RES, "drawable-xxhdpi", "logo_bawan.png")
    if os.path.exists(old):
        os.remove(old)
        print("removed old logo_bawan.png")


if __name__ == "__main__":
    main()
