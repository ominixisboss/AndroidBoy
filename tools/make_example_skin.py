#!/usr/bin/env python3
"""Draws the example skin in docs/skins/example (needs Pillow: pip install pillow).

The example shows every part of the skin format: portrait and landscape layouts, a pressed-state
image, and all control types. Zip the folder's contents to import it in the app.
"""
import json
import os

from PIL import Image, ImageDraw, ImageFont

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "skins", "example")

BACKGROUND = (18, 22, 40)
BODY_TOP = (34, 40, 74)
BODY_BOTTOM = (20, 24, 46)
BEZEL = (10, 12, 22)
BUTTON = (70, 196, 214)
BUTTON_PRESSED = (160, 238, 246)
DARK_BUTTON = (58, 66, 110)
DARK_BUTTON_PRESSED = (110, 122, 190)
LABEL = (200, 210, 240)


def font(size):
    for path in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                 "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf"):
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def gradient(size):
    width, height = size
    image = Image.new("RGB", size)
    draw = ImageDraw.Draw(image)
    for y in range(height):
        t = y / max(1, height - 1)
        color = tuple(int(a + (b - a) * t) for a, b in zip(BODY_TOP, BODY_BOTTOM))
        draw.line([(0, y), (width, y)], fill=color)
    return image


def draw_controls(draw, controls, pressed):
    """Draws the controls; with pressed=True, everything is drawn in its pressed colours."""
    button = BUTTON_PRESSED if pressed else BUTTON
    dark = DARK_BUTTON_PRESSED if pressed else DARK_BUTTON
    x, y, w, h = controls["dpad"]
    arm = w / 3
    draw.rounded_rectangle([x + arm, y, x + 2 * arm, y + h], radius=arm * 0.25, fill=dark)
    draw.rounded_rectangle([x, y + arm, x + w, y + 2 * arm], radius=arm * 0.25, fill=dark)
    for name in ("a", "b"):
        x, y, w, h = controls[name]
        draw.ellipse([x, y, x + w, y + h], fill=button)
        draw.text((x + w / 2, y + h / 2), name.upper(), fill=BACKGROUND, font=font(int(h * 0.45)), anchor="mm")
    for name, text in (("select", "SELECT"), ("start", "START"), ("menu", "MENU"), ("fastForward", ">>"),
                       ("rewind", "<<")):
        x, y, w, h = controls[name]
        draw.rounded_rectangle([x, y, x + w, y + h], radius=h / 2, fill=dark)
        draw.text((x + w / 2, y + h / 2), text, fill=LABEL, font=font(int(h * 0.42)), anchor="mm")


def render(name, size, screen, controls):
    image = gradient(size)
    draw = ImageDraw.Draw(image)
    x, y, w, h = screen
    pad = w * 0.05
    draw.rounded_rectangle([x - pad, y - pad, x + w + pad, y + h + pad * 1.6], radius=pad, fill=BEZEL)
    draw.text((x + w / 2, y + h + pad * 0.8), "ANDROIDBOY", fill=LABEL, font=font(int(pad * 0.9)), anchor="mm")
    draw.rectangle([x, y, x + w, y + h], fill=(0, 0, 0))
    pressed = image.copy()
    draw_controls(draw, controls, False)
    draw_controls(ImageDraw.Draw(pressed), controls, True)
    image.save(os.path.join(OUT, name + ".png"), optimize=True)
    pressed.save(os.path.join(OUT, name + "_pressed.png"), optimize=True)


def main():
    os.makedirs(OUT, exist_ok=True)
    portrait = {
        "image": "portrait.png",
        "pressedImage": "portrait_pressed.png",
        "screen": [90, 140, 900, 810],
        "controls": {
            "dpad": [80, 1180, 360, 360],
            "b": [600, 1330, 170, 170],
            "a": [820, 1220, 170, 170],
            "ab": [770, 1330, 70, 70],
            "select": [300, 1660, 200, 70],
            "start": [580, 1660, 200, 70],
            "menu": [440, 1070, 200, 70],
            "fastForward": [840, 1060, 160, 70],
            "rewind": [80, 1060, 160, 70],
        },
    }
    landscape = {
        "image": "landscape.png",
        "pressedImage": "landscape_pressed.png",
        "screen": [540, 90, 840, 756],
        "controls": {
            "dpad": [90, 360, 340, 340],
            "b": [1500, 500, 160, 160],
            "a": [1700, 380, 160, 160],
            "ab": [1650, 490, 70, 70],
            "select": [130, 900, 200, 70],
            "start": [1590, 900, 200, 70],
            "menu": [1700, 40, 180, 70],
            "fastForward": [1500, 40, 160, 70],
            "rewind": [40, 40, 160, 70],
        },
    }
    render("portrait", (1080, 1920), portrait["screen"], portrait["controls"])
    render("landscape", (1920, 1080), landscape["screen"], landscape["controls"])
    skin = {
        "name": "Midnight (example)",
        "author": "AndroidBoy",
        "backgroundColor": "#121628",
        "portrait": portrait,
        "landscape": landscape,
    }
    with open(os.path.join(OUT, "skin.json"), "w") as f:
        json.dump(skin, f, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
