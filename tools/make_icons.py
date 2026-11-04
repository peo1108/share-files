"""Generates the app icon for Windows and iOS. Needs Pillow. Run from the repo root."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

SIZE = 1024
SCALE = 2
STOPS = [(0.0, (124, 77, 255)), (0.55, (255, 77, 157)), (1.0, (255, 181, 71))]


def gradient(size: int) -> Image.Image:
    image = Image.new("RGB", (size, size))
    pixels = image.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            for (t0, c0), (t1, c1) in zip(STOPS, STOPS[1:]):
                if t <= t1:
                    k = (t - t0) / (t1 - t0)
                    pixels[x, y] = tuple(round(a + (b - a) * k) for a, b in zip(c0, c1))
                    break
    return image


def arrows(size: int) -> Image.Image:
    """Two opposed arrows: send and receive."""
    layer = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(layer)
    u = size / 1024
    stroke = round(92 * u)

    def arrow(x: float, top: float, bottom: float, up: bool) -> None:
        tip, tail = (top, bottom) if up else (bottom, top)
        direction = 1 if up else -1
        draw.line([(x * u, tip * u), (x * u, tail * u)], fill=255, width=stroke)
        wing = 150
        draw.line([(x * u, tip * u), ((x - wing) * u, (tip + direction * wing) * u)], fill=255, width=stroke)
        draw.line([(x * u, tip * u), ((x + wing) * u, (tip + direction * wing) * u)], fill=255, width=stroke)
        for cx, cy in [(x, tip), (x, tail), (x - wing, tip + direction * wing), (x + wing, tip + direction * wing)]:
            r = stroke / 2
            draw.ellipse([cx * u - r, cy * u - r, cx * u + r, cy * u + r], fill=255)

    arrow(372, 226, 798, up=True)
    arrow(652, 226, 798, up=False)
    return layer


def base_icon() -> Image.Image:
    size = SIZE * SCALE
    image = gradient(size // 8).resize((size, size), Image.Resampling.BICUBIC)
    mask = arrows(size)
    shadow = mask.filter(ImageFilter.GaussianBlur(24 * SCALE))
    image.paste((60, 20, 110), (0, 8 * SCALE), shadow.point(lambda v: v * 0.45))
    image.paste((255, 255, 255), (0, 0), mask)
    return image.resize((SIZE, SIZE), Image.Resampling.LANCZOS)


def rounded(image: Image.Image) -> Image.Image:
    size = image.width * 4
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=round(size * 0.225), fill=255)
    result = image.convert("RGBA")
    result.putalpha(mask.resize(image.size, Image.Resampling.LANCZOS))
    return result


def main() -> None:
    icon = base_icon()

    ios = Path("ios/ShareFiles/Assets.xcassets/AppIcon.appiconset")
    ios.mkdir(parents=True, exist_ok=True)
    icon.save(ios / "icon-1024.png")  # iOS applies its own mask and rejects alpha.

    windows = Path("pc/src/ShareFiles.App/Assets")
    windows.mkdir(parents=True, exist_ok=True)
    rounded(icon).save(windows / "app.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    rounded(icon).resize((256, 256), Image.Resampling.LANCZOS).save(windows / "app.png")


if __name__ == "__main__":
    main()
