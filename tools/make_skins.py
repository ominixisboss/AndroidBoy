#!/usr/bin/env python3
"""Draws the image skins (needs Pillow: pip install pillow).

Writes the example skin in docs/skins/example, which shows every part of the skin format, and
the skins bundled with the app in app/src/main/assets/skins. Each has portrait and landscape
layouts, a pressed-state image, and all control types.
"""
import json
import math
import os
import random

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
EXAMPLE = os.path.join(ROOT, "docs", "skins", "example")
BUNDLED = os.path.join(ROOT, "app", "src", "main", "assets", "skins")
# Drawn at this multiple of the final size, then scaled down, for smooth edges.
SUPERSAMPLE = 2

PORTRAIT = {
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
LANDSCAPE = {
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


def font(size):
    for path in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                 "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf"):
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def lerp(a, b, t):
    return tuple(int(x + (y - x) * t) for x, y in zip(a, b))


def lighten(color, t):
    return lerp(color, (255, 255, 255), t)


def darken(color, t):
    return lerp(color, (0, 0, 0), t)


def vertical_gradient(size, top, bottom):
    width, height = size
    image = Image.new("RGB", size)
    draw = ImageDraw.Draw(image)
    for y in range(height):
        draw.line([(0, y), (width, y)], fill=lerp(top, bottom, y / max(1, height - 1)))
    return image


def ball(draw, box, color, light=0.45, shade=0.35):
    """A shaded sphere-like disc, lit from the top left."""
    x0, y0, x1, y1 = box
    cx, cy, r = (x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / 2
    draw.ellipse(box, fill=darken(color, shade))
    steps = 24
    for i in range(1, steps + 1):
        t = i / steps
        rr = r * (1 - t * 0.92)
        ox, oy = -r * 0.28 * t, -r * 0.32 * t
        draw.ellipse([cx + ox - rr, cy + oy - rr, cx + ox + rr, cy + oy + rr],
                     fill=lerp(darken(color, shade), lighten(color, light), t ** 1.6))


def label(draw, xy, text, size, color):
    draw.text(xy, text, fill=color, font=font(int(size)), anchor="mm")


def pill(draw, box, color, text, text_color, glossy):
    x0, y0, x1, y1 = box
    h = y1 - y0
    draw.rounded_rectangle(box, radius=h / 2, fill=color)
    if glossy:
        draw.rounded_rectangle([x0 + h * 0.2, y0 + h * 0.12, x1 - h * 0.2, y0 + h * 0.45], radius=h * 0.2,
                               fill=lighten(color, 0.3))
    label(draw, ((x0 + x1) / 2, (y0 + y1) / 2), text, h * 0.42, text_color)


# ---- Styles ----
# Each draws the body (everything behind the controls) and the controls, pressed or not.

class Midnight:
    name = "Midnight"
    background = "#121628"
    BACKGROUND = (18, 22, 40)
    BODY_TOP = (34, 40, 74)
    BODY_BOTTOM = (20, 24, 46)
    BEZEL = (10, 12, 22)
    BUTTON = (70, 196, 214)
    BUTTON_PRESSED = (160, 238, 246)
    DARK = (58, 66, 110)
    DARK_PRESSED = (110, 122, 190)
    LABEL = (200, 210, 240)

    def body(self, size, screen, s):
        image = vertical_gradient(size, self.BODY_TOP, self.BODY_BOTTOM)
        draw = ImageDraw.Draw(image)
        x, y, w, h = screen
        pad = w * 0.05
        draw.rounded_rectangle([x - pad, y - pad, x + w + pad, y + h + pad * 1.6], radius=pad, fill=self.BEZEL)
        label(draw, (x + w / 2, y + h + pad * 0.8), "ANDROIDBOY", pad * 0.9, self.LABEL)
        return image

    def controls(self, draw, controls, pressed):
        button = self.BUTTON_PRESSED if pressed else self.BUTTON
        dark = self.DARK_PRESSED if pressed else self.DARK
        x, y, w, h = controls["dpad"]
        arm = w / 3
        draw.rounded_rectangle([x + arm, y, x + 2 * arm, y + h], radius=arm * 0.25, fill=dark)
        draw.rounded_rectangle([x, y + arm, x + w, y + 2 * arm], radius=arm * 0.25, fill=dark)
        for name in ("a", "b"):
            x, y, w, h = controls[name]
            draw.ellipse([x, y, x + w, y + h], fill=button)
            label(draw, (x + w / 2, y + h / 2), name.upper(), h * 0.45, self.BACKGROUND)
        for name, text in (("select", "SELECT"), ("start", "START"), ("menu", "MENU"), ("fastForward", ">>"),
                           ("rewind", "<<")):
            x, y, w, h = controls[name]
            pill(draw, [x, y, x + w, y + h], dark, text, self.LABEL, False)


class Arcade:
    """A cabinet control panel: glossy arcade buttons and a ball-top joystick for the d-pad."""
    name = "Arcade"
    background = "#0C0C10"
    PANEL_TOP = (28, 28, 34)
    PANEL_BOTTOM = (12, 12, 16)
    STRIPES = [(230, 57, 70), (247, 127, 0), (252, 191, 73)]
    A = (230, 40, 55)
    B = (40, 110, 230)
    PILLS = (252, 191, 73)
    UTILITY = (60, 60, 72)
    LABEL = (240, 240, 245)

    def body(self, size, screen, s):
        width, height = size
        image = vertical_gradient(size, self.PANEL_TOP, self.PANEL_BOTTOM)
        draw = ImageDraw.Draw(image)
        # Racing stripes across the panel, below the screen.
        x, y, w, h = screen
        band = height * 0.93
        for i, color in enumerate(self.STRIPES):
            top = band + i * 14 * s
            draw.polygon([(0, top), (width, top - 40 * s), (width, top - 30 * s), (0, top + 10 * s)], fill=color)
        pad = w * 0.045
        draw.rounded_rectangle([x - pad, y - pad * 2.2, x + w + pad, y + h + pad], radius=pad * 0.6,
                               fill=(20, 20, 24), outline=(90, 90, 100), width=int(3 * s))
        label(draw, (x + w / 2, y - pad * 1.1), "★ INSERT COIN ★", pad * 0.9, self.STRIPES[2])
        return image

    def controls(self, draw, controls, pressed):
        # Joystick: a base plate, the shaft's collar, and the ball top.
        x, y, w, h = controls["dpad"]
        cx, cy = x + w / 2, y + h / 2
        draw.ellipse([x, y, x + w, y + h], fill=(34, 34, 40), outline=(80, 80, 92), width=int(w * 0.02))
        for angle in range(0, 360, 90):
            ax = cx + math.cos(math.radians(angle)) * w * 0.4
            ay = cy - math.sin(math.radians(angle)) * h * 0.4
            r = w * 0.05
            draw.polygon([(ax + math.cos(math.radians(angle)) * r * 1.4, ay - math.sin(math.radians(angle)) * r * 1.4),
                          (ax + math.cos(math.radians(angle + 120)) * r, ay - math.sin(math.radians(angle + 120)) * r),
                          (ax + math.cos(math.radians(angle - 120)) * r, ay - math.sin(math.radians(angle - 120)) * r)],
                         fill=(150, 150, 165))
        draw.ellipse([cx - w * 0.14, cy - h * 0.14, cx + w * 0.14, cy + h * 0.14], fill=(18, 18, 22))
        r = w * 0.2
        drop = w * 0.03 if pressed else 0
        ball(draw, [cx - r, cy - r + drop, cx + r, cy + r + drop], lighten(self.A, 0.25) if pressed else self.A)
        for name, color in (("a", self.A), ("b", self.B)):
            x, y, w, h = controls[name]
            ring = w * 0.08
            draw.ellipse([x, y, x + w, y + h], fill=(225, 225, 232))
            inset = ring * (1.4 if pressed else 1)
            ball(draw, [x + inset, y + inset, x + w - inset, y + h - inset],
                 lighten(color, 0.3) if pressed else color, light=0.35, shade=0.25)
            label(draw, (x + w / 2, y + h / 2), name.upper(), h * 0.36, (255, 255, 255))
        for name, text in (("select", "SELECT"), ("start", "START")):
            x, y, w, h = controls[name]
            pill(draw, [x, y, x + w, y + h], lighten(self.PILLS, 0.3) if pressed else self.PILLS,
                 text, (30, 20, 0), True)
        for name, text in (("menu", "MENU"), ("fastForward", ">>"), ("rewind", "<<")):
            x, y, w, h = controls[name]
            pill(draw, [x, y, x + w, y + h], lighten(self.UTILITY, 0.35) if pressed else self.UTILITY,
                 text, self.LABEL, False)


class Woodgrain:
    """A 1970s-style handheld: wood-grain body, cream faceplate, orange buttons."""
    name = "Woodgrain"
    background = "#3B2414"
    WOOD = (122, 74, 40)
    WOOD_DARK = (84, 48, 24)
    CREAM = (239, 230, 210)
    ORANGE = (226, 104, 42)
    BROWN = (58, 40, 28)
    LABEL = (58, 40, 28)

    def body(self, size, screen, s):
        width, height = size
        image = Image.new("RGB", size, self.WOOD)
        draw = ImageDraw.Draw(image)
        rng = random.Random(7)
        # Grain: wavy lines of darker wood.
        for i in range(int(height / (9 * s))):
            base = i * 9 * s + rng.uniform(-3, 3) * s
            phase = rng.uniform(0, math.tau)
            amp = rng.uniform(3, 12) * s
            color = lerp(self.WOOD, self.WOOD_DARK, rng.uniform(0.2, 0.9))
            points = [(x, base + math.sin(x / (160 * s) + phase) * amp + math.sin(x / (37 * s)) * amp * 0.2)
                      for x in range(0, width + int(20 * s), int(20 * s))]
            draw.line(points, fill=color, width=int(rng.uniform(1, 4) * s))
        # Cream faceplate behind everything, with a chrome trim line.
        margin = min(width, height) * 0.03
        draw.rounded_rectangle([margin, margin, width - margin, height - margin], radius=margin * 2,
                               fill=self.CREAM, outline=(190, 190, 196), width=int(4 * s))
        x, y, w, h = screen
        pad = w * 0.05
        draw.rounded_rectangle([x - pad, y - pad, x + w + pad, y + h + pad * 1.6], radius=pad * 0.5, fill=self.BROWN)
        label(draw, (x + w / 2, y + h + pad * 0.8), "ANDROIDBOY  DELUXE", pad * 0.8, self.CREAM)
        return image

    def controls(self, draw, controls, pressed):
        x, y, w, h = controls["dpad"]
        arm = w / 3
        color = lighten(self.BROWN, 0.25) if pressed else self.BROWN
        draw.ellipse([x - w * 0.04, y - h * 0.04, x + w * 1.04, y + h * 1.04], fill=darken(self.CREAM, 0.12))
        draw.rounded_rectangle([x + arm, y, x + 2 * arm, y + h], radius=arm * 0.2, fill=color)
        draw.rounded_rectangle([x, y + arm, x + w, y + 2 * arm], radius=arm * 0.2, fill=color)
        draw.ellipse([x + w / 2 - arm * 0.3, y + h / 2 - arm * 0.3, x + w / 2 + arm * 0.3, y + h / 2 + arm * 0.3],
                     fill=darken(color, 0.3))
        for name in ("a", "b"):
            x, y, w, h = controls[name]
            draw.ellipse([x - w * 0.05, y - h * 0.05, x + w * 1.05, y + h * 1.05], fill=darken(self.CREAM, 0.12))
            ball(draw, [x, y, x + w, y + h], lighten(self.ORANGE, 0.2) if pressed else self.ORANGE, shade=0.2)
            label(draw, (x + w / 2, y + h / 2), name.upper(), h * 0.4, self.CREAM)
        for name, text in (("select", "SELECT"), ("start", "START"), ("menu", "MENU"), ("fastForward", ">>"),
                           ("rewind", "<<")):
            x, y, w, h = controls[name]
            pill(draw, [x, y, x + w, y + h], lighten(self.BROWN, 0.3) if pressed else self.BROWN,
                 text, self.CREAM, False)


class Space:
    """Deep space: a starfield with a ringed planet, chrome buttons and cyan trim."""
    name = "Space"
    background = "#05060F"
    TOP = (12, 14, 38)
    BOTTOM = (3, 3, 10)
    CYAN = (80, 230, 255)
    CHROME = (190, 198, 214)
    DARK = (30, 34, 58)

    def body(self, size, screen, s):
        width, height = size
        image = vertical_gradient(size, self.TOP, self.BOTTOM)
        draw = ImageDraw.Draw(image)
        rng = random.Random(42)
        for _ in range(int(width * height / (2600 * s * s))):
            x, y = rng.uniform(0, width), rng.uniform(0, height)
            r = rng.choice((0.8, 1, 1, 1.4, 2.2)) * s
            shade = rng.randint(150, 255)
            draw.ellipse([x - r, y - r, x + r, y + r], fill=(shade, shade, min(255, shade + 20)))
        # A ringed planet in a corner the controls leave free.
        px, py, pr = (width * 0.83, height * 0.955, width * 0.1) if height > width else (width * 0.135, height * 0.74, height * 0.065)
        draw.ellipse([px - pr * 1.9, py - pr * 0.45, px + pr * 1.9, py + pr * 0.45], outline=(210, 170, 120), width=int(5 * s))
        ball(draw, [px - pr, py - pr, px + pr, py + pr], (200, 110, 70), light=0.35, shade=0.6)
        draw.arc([px - pr * 1.9, py - pr * 0.45, px + pr * 1.9, py + pr * 0.45], 0, 180, fill=(230, 190, 140), width=int(5 * s))
        x, y, w, h = screen
        pad = w * 0.05
        draw.rounded_rectangle([x - pad, y - pad, x + w + pad, y + h + pad * 1.6], radius=pad,
                               fill=(8, 10, 24), outline=self.CYAN, width=int(3 * s))
        label(draw, (x + w / 2, y + h + pad * 0.8), "ANDROIDBOY  ·  ORBIT", pad * 0.8, self.CYAN)
        return image

    def controls(self, draw, controls, pressed):
        x, y, w, h = controls["dpad"]
        arm = w / 3
        color = (60, 70, 110) if pressed else self.DARK
        for box in ([x + arm, y, x + 2 * arm, y + h], [x, y + arm, x + w, y + 2 * arm]):
            draw.rounded_rectangle(box, radius=arm * 0.2, fill=color, outline=self.CYAN, width=max(2, int(w * 0.012)))
        draw.rectangle([x + arm + 3, y + arm + 3, x + 2 * arm - 3, y + 2 * arm - 3], fill=color)
        for name in ("a", "b"):
            x, y, w, h = controls[name]
            ring = w * 0.07
            draw.ellipse([x - ring, y - ring, x + w + ring, y + h + ring], fill=self.CYAN if pressed else (40, 90, 120))
            ball(draw, [x, y, x + w, y + h], lighten(self.CHROME, 0.2) if pressed else self.CHROME, light=0.7, shade=0.55)
            label(draw, (x + w / 2, y + h / 2), name.upper(), h * 0.4, (20, 30, 60))
        for name, text in (("select", "SELECT"), ("start", "START"), ("menu", "MENU"), ("fastForward", ">>"),
                           ("rewind", "<<")):
            x, y, w, h = controls[name]
            draw.rounded_rectangle([x, y, x + w, y + h], radius=h / 2, fill=(40, 90, 120) if pressed else self.DARK,
                                   outline=self.CYAN, width=max(2, int(h * 0.05)))
            label(draw, (x + w / 2, y + h / 2), text, h * 0.42, self.CYAN)


class Camo:
    """Woodland camouflage, with a stencilled faceplate and olive rubber buttons."""
    name = "Camo"
    background = "#3E4A2A"
    COLORS = [(92, 104, 62), (62, 74, 42), (120, 110, 76), (34, 38, 26)]
    OLIVE = (86, 92, 52)
    KHAKI = (176, 160, 110)

    def body(self, size, screen, s):
        width, height = size
        image = Image.new("RGB", size, self.COLORS[0])
        draw = ImageDraw.Draw(image)
        rng = random.Random(3)
        for color in self.COLORS[1:]:
            for _ in range(int(width * height / (26000 * s * s))):
                cx, cy = rng.uniform(-50, width + 50), rng.uniform(-50, height + 50)
                r = rng.uniform(40, 110) * s
                points = []
                for k in range(9):
                    angle = k / 9 * math.tau
                    rr = r * rng.uniform(0.55, 1.15)
                    points.append((cx + math.cos(angle) * rr * 1.4, cy + math.sin(angle) * rr))
                draw.polygon(points, fill=color)
        x, y, w, h = screen
        pad = w * 0.05
        draw.rounded_rectangle([x - pad, y - pad, x + w + pad, y + h + pad * 1.6], radius=pad * 0.4,
                               fill=(28, 30, 22), outline=self.KHAKI, width=int(3 * s))
        label(draw, (x + w / 2, y + h + pad * 0.8), "FIELD UNIT  AB-01", pad * 0.8, self.KHAKI)
        return image

    def controls(self, draw, controls, pressed):
        x, y, w, h = controls["dpad"]
        arm = w / 3
        color = (120, 116, 80) if pressed else (76, 80, 50)
        edge = max(2, int(w * 0.012))
        draw.ellipse([x - w * 0.05, y - h * 0.05, x + w * 1.05, y + h * 1.05], fill=(28, 30, 22))
        draw.rounded_rectangle([x + arm, y, x + 2 * arm, y + h], radius=arm * 0.15, fill=color, outline=self.KHAKI, width=edge)
        draw.rounded_rectangle([x, y + arm, x + w, y + 2 * arm], radius=arm * 0.15, fill=color, outline=self.KHAKI, width=edge)
        draw.rectangle([x + arm + edge, y + arm + edge, x + 2 * arm - edge, y + 2 * arm - edge], fill=color)
        for name in ("a", "b"):
            x, y, w, h = controls[name]
            draw.ellipse([x - w * 0.06, y - h * 0.06, x + w * 1.06, y + h * 1.06], fill=(28, 30, 22))
            ball(draw, [x, y, x + w, y + h], lighten(self.OLIVE, 0.25) if pressed else self.OLIVE, light=0.3, shade=0.3)
            label(draw, (x + w / 2, y + h / 2), name.upper(), h * 0.4, self.KHAKI)
        for name, text in (("select", "SELECT"), ("start", "START"), ("menu", "MENU"), ("fastForward", ">>"),
                           ("rewind", "<<")):
            x, y, w, h = controls[name]
            pill(draw, [x, y, x + w, y + h], (70, 74, 52) if pressed else (28, 30, 22), text, self.KHAKI, False)


class Candy:
    """Pink and white candy stripes with sweet-coloured buttons."""
    name = "Candy"
    background = "#FFD6E7"
    PINK = (255, 150, 190)
    CREAM = (255, 244, 248)
    BERRY = (214, 64, 120)
    MINT = (120, 220, 190)
    GRAPE = (150, 110, 210)

    def body(self, size, screen, s):
        width, height = size
        image = Image.new("RGB", size, self.CREAM)
        draw = ImageDraw.Draw(image)
        stripe = 70 * s
        for i in range(-int(height / stripe) - 2, int(width / stripe) + 2, 2):
            x0 = i * stripe
            draw.polygon([(x0, 0), (x0 + stripe, 0), (x0 + stripe + height, height), (x0 + height, height)], fill=self.PINK)
        x, y, w, h = screen
        pad = w * 0.05
        draw.rounded_rectangle([x - pad * 1.4, y - pad * 1.4, x + w + pad * 1.4, y + h + pad * 2.2], radius=pad * 1.5,
                               fill=self.CREAM, outline=self.BERRY, width=int(6 * s))
        draw.rounded_rectangle([x - pad * 0.5, y - pad * 0.5, x + w + pad * 0.5, y + h + pad * 0.5], radius=pad * 0.5,
                               fill=(60, 30, 50))
        label(draw, (x + w / 2, y + h + pad * 1.35), "ANDROIDBOY  SWEETS", pad * 0.8, self.BERRY)
        return image

    def controls(self, draw, controls, pressed):
        x, y, w, h = controls["dpad"]
        arm = w / 3
        color = lighten(self.GRAPE, 0.3) if pressed else self.GRAPE
        draw.rounded_rectangle([x + arm - 6, y - 6, x + 2 * arm + 6, y + h + 6], radius=arm * 0.4, fill=self.CREAM)
        draw.rounded_rectangle([x - 6, y + arm - 6, x + w + 6, y + 2 * arm + 6], radius=arm * 0.4, fill=self.CREAM)
        draw.rounded_rectangle([x + arm, y, x + 2 * arm, y + h], radius=arm * 0.4, fill=color)
        draw.rounded_rectangle([x, y + arm, x + w, y + 2 * arm], radius=arm * 0.4, fill=color)
        for name, color in (("a", self.BERRY), ("b", self.MINT)):
            x, y, w, h = controls[name]
            draw.ellipse([x - w * 0.07, y - h * 0.07, x + w * 1.07, y + h * 1.07], fill=self.CREAM)
            ball(draw, [x, y, x + w, y + h], lighten(color, 0.3) if pressed else color, light=0.55, shade=0.15)
            label(draw, (x + w / 2, y + h / 2), name.upper(), h * 0.4, (255, 255, 255))
        for name, text in (("select", "SELECT"), ("start", "START"), ("menu", "MENU"), ("fastForward", ">>"),
                           ("rewind", "<<")):
            x, y, w, h = controls[name]
            draw.rounded_rectangle([x - 5, y - 5, x + w + 5, y + h + 5], radius=h / 2 + 5, fill=self.CREAM)
            pill(draw, [x, y, x + w, y + h], lighten(self.BERRY, 0.3) if pressed else self.BERRY, text, (255, 255, 255), True)


class Carbon:
    """Carbon-fibre weave with a red racing trim and glossy red buttons."""
    name = "Carbon"
    background = "#111214"
    RED = (225, 30, 45)
    LIGHT = (44, 46, 52)
    DARK = (22, 23, 26)

    def body(self, size, screen, s):
        width, height = size
        image = Image.new("RGB", size, self.DARK)
        draw = ImageDraw.Draw(image)
        cell = 18 * s
        for row in range(int(height / cell) + 1):
            for col in range(int(width / cell) + 1):
                x0, y0 = col * cell, row * cell
                # Alternate the direction of each tow's sheen, like a 2x2 twill weave.
                if (row + col) % 2 == 0:
                    for k in range(int(cell)):
                        draw.line([(x0, y0 + k), (x0 + cell, y0 + k)], fill=lerp(self.LIGHT, self.DARK, k / cell))
                else:
                    for k in range(int(cell)):
                        draw.line([(x0 + k, y0), (x0 + k, y0 + cell)], fill=lerp(self.DARK, self.LIGHT, k / cell * 0.8))
        x, y, w, h = screen
        pad = w * 0.05
        draw.rounded_rectangle([x - pad, y - pad, x + w + pad, y + h + pad * 1.6], radius=pad * 0.5, fill=(8, 8, 10))
        draw.line([(x - pad, y + h + pad * 1.6 + 8 * s), (x + w + pad, y + h + pad * 1.6 + 8 * s)], fill=self.RED, width=int(6 * s))
        label(draw, (x + w / 2, y + h + pad * 0.8), "ANDROIDBOY  CARBON", pad * 0.8, self.RED)
        return image

    def controls(self, draw, controls, pressed):
        x, y, w, h = controls["dpad"]
        arm = w / 3
        color = (96, 98, 108) if pressed else (52, 54, 60)
        draw.rounded_rectangle([x + arm, y, x + 2 * arm, y + h], radius=arm * 0.2, fill=color, outline=(150, 152, 162), width=int(w * 0.01) + 1)
        draw.rounded_rectangle([x, y + arm, x + w, y + 2 * arm], radius=arm * 0.2, fill=color, outline=(150, 152, 162), width=int(w * 0.01) + 1)
        draw.rectangle([x + arm + 2, y + arm + 2, x + 2 * arm - 2, y + 2 * arm - 2], fill=color)
        for name in ("a", "b"):
            x, y, w, h = controls[name]
            draw.ellipse([x - w * 0.06, y - h * 0.06, x + w * 1.06, y + h * 1.06], fill=(8, 8, 10))
            ball(draw, [x, y, x + w, y + h], lighten(self.RED, 0.3) if pressed else self.RED, light=0.5, shade=0.4)
            label(draw, (x + w / 2, y + h / 2), name.upper(), h * 0.4, (255, 255, 255))
        for name, text in (("select", "SELECT"), ("start", "START"), ("menu", "MENU"), ("fastForward", ">>"),
                           ("rewind", "<<")):
            x, y, w, h = controls[name]
            pill(draw, [x, y, x + w, y + h], (90, 20, 26) if pressed else (38, 40, 44), text, (235, 235, 240), False)


def scaled(rect, s):
    return [v * s for v in rect]


def render(style, out, name, size, layout):
    s = SUPERSAMPLE
    big = (size[0] * s, size[1] * s)
    controls = {key: scaled(rect, s) for key, rect in layout["controls"].items()}
    image = style.body(big, scaled(layout["screen"], s), s)
    draw = ImageDraw.Draw(image)
    x, y, w, h = scaled(layout["screen"], s)
    draw.rectangle([x, y, x + w, y + h], fill=(0, 0, 0))
    pressed = image.copy()
    style.controls(draw, controls, False)
    style.controls(ImageDraw.Draw(pressed), controls, True)
    for picture, suffix in ((image, ""), (pressed, "_pressed")):
        picture.resize(size, Image.LANCZOS).save(os.path.join(out, name + suffix + ".png"), optimize=True)


def write_skin(style, out, display_name):
    os.makedirs(out, exist_ok=True)
    portrait = dict(image="portrait.png", pressedImage="portrait_pressed.png", **PORTRAIT)
    landscape = dict(image="landscape.png", pressedImage="landscape_pressed.png", **LANDSCAPE)
    render(style, out, "portrait", (1080, 1920), PORTRAIT)
    render(style, out, "landscape", (1920, 1080), LANDSCAPE)
    skin = {
        "name": display_name,
        "author": "AndroidBoy",
        "backgroundColor": style.background,
        "portrait": portrait,
        "landscape": landscape,
    }
    with open(os.path.join(out, "skin.json"), "w") as f:
        json.dump(skin, f, indent=2)
        f.write("\n")


def main():
    write_skin(Midnight(), EXAMPLE, "Midnight (example)")
    for style in (Midnight(), Arcade(), Woodgrain(), Space(), Camo(), Candy(), Carbon()):
        write_skin(style, os.path.join(BUNDLED, style.name.lower()), style.name)


if __name__ == "__main__":
    main()
