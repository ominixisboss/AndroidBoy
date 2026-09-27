#!/usr/bin/env python3
"""Draws the image skins (needs Pillow: pip install pillow).

Writes the example skin in docs/skins/example, which shows every part of the skin format, and
the skins bundled with the app in app/src/main/assets/skins. Each has portrait and landscape
layouts, a pressed-state image, and all control types.

Everything is drawn at twice the final size and scaled down, from a few pieces shared by every
skin: a console body (the style's texture), a screen bezel with a glass lens, a power light and
the AndroidBoy wordmark, speaker slots, and controls modelled as lit 3D objects with soft
shadows, bevelled edges and a gloss. Each style picks the materials and colours, and some draw
their own parts (the arcade joystick, the pixel-art buttons).
"""
import json
import math
import os
import random

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont, ImageOps

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

FONTS = {
    "bold": ("LiberationSans-Bold.ttf", "DejaVuSans-Bold.ttf"),
    "italic": ("LiberationSans-BoldItalic.ttf", "FreeSansBoldOblique.ttf", "DejaVuSans-Bold.ttf"),
    "mono": ("LiberationMono-Bold.ttf", "DejaVuSansMono-Bold.ttf"),
    "serif": ("LiberationSerif-BoldItalic.ttf", "DejaVuSerif-Bold.ttf"),
}
FONT_DIRS = ("/usr/share/fonts/truetype/liberation", "/usr/share/fonts/truetype/dejavu",
             "/usr/share/fonts/truetype/freefont", "/usr/share/fonts/liberation", "/usr/share/fonts/dejavu")


def font(size, kind="bold"):
    for name in FONTS[kind]:
        for folder in FONT_DIRS:
            path = os.path.join(folder, name)
            if os.path.exists(path):
                return ImageFont.truetype(path, max(1, int(size)))
    return ImageFont.load_default()


# ---- Colours ----

def rgb(hex_color):
    hex_color = hex_color.lstrip("#")
    return tuple(int(hex_color[i:i + 2], 16) for i in (0, 2, 4))


def lerp(a, b, t):
    return tuple(int(round(x + (y - x) * t)) for x, y in zip(a, b))


def lighten(color, t):
    return lerp(color, (255, 255, 255), t)


def darken(color, t):
    return lerp(color, (0, 0, 0), t)


def hexify(color):
    return "#%02X%02X%02X" % color


# ---- Images ----

def solid(size, color, alpha=255):
    return Image.new("RGBA", size, tuple(color) + (alpha,))


def gradient(size, top, bottom, horizontal=False):
    """A two-colour gradient, top to bottom (or left to right)."""
    ramp = Image.linear_gradient("L")
    if horizontal:
        ramp = ramp.rotate(90, expand=True).transpose(Image.FLIP_LEFT_RIGHT)
    ramp = ramp.resize(size, Image.BILINEAR)
    return ImageOps.colorize(ramp, top, bottom).convert("RGBA")


def radial(size, inner, outer, center=(0.5, 0.5), radius=0.7, reach=None):
    """
    A radial gradient from {@code inner} at {@code center} (fractions of the size) to {@code outer}
    at {@code reach} pixels away (or {@code radius} times the larger side).
    """
    w, h = size
    reach = reach if reach is not None else radius * max(w, h)
    # PIL's gradient reaches 181 at the middle of each edge: stretch it so that's the full 255.
    ramp = Image.radial_gradient("L").point(lambda v: min(255, int(v * 255 / 181)))
    d = max(2, int(reach * 2))
    big = ramp.resize((d, d), Image.BILINEAR)
    canvas = Image.new("L", size, 255)
    canvas.paste(big, (int(center[0] * w - d / 2), int(center[1] * h - d / 2)))
    return ImageOps.colorize(canvas, inner, outer).convert("RGBA")


def noise(size, cell, seed, octaves=1, persistence=0.5):
    """Smooth value noise, 0-255: random cells scaled up, several octaves summed."""
    w, h = size
    rng = random.Random(seed)
    total = None
    weight_sum = 0
    weight = 1.0
    for octave in range(octaves):
        c = max(1, int(cell / (2 ** octave)))
        gw, gh = max(2, w // c + 3), max(2, h // c + 3)
        small = Image.frombytes("L", (gw, gh), rng.randbytes(gw * gh))
        layer = small.resize((gw * c, gh * c), Image.BICUBIC).crop((c, c, c + w, c + h))
        if total is None:
            total = layer
        else:
            total = Image.blend(total, layer, weight / (weight_sum + weight))
        weight_sum += weight
        weight *= persistence
    return ImageOps.autocontrast(total, cutoff=1)


def grain(image, amount, seed):
    """Fine speckle over an image, like a matte plastic's texture."""
    n = noise(image.size, 2, seed)
    n = n.point(lambda v: 128 + (v - 128) * amount / 64)
    overlay = Image.merge("RGBA", (n, n, n, Image.new("L", image.size, int(255 * 0.18))))
    return overlay_blend(image, overlay)


def overlay_blend(image, overlay):
    """Soft-light-ish: grey 128 leaves the image, lighter lightens and darker darkens."""
    base = image.convert("RGB")
    over = overlay.convert("RGB")
    mixed = ImageChops.soft_light(base, over)
    alpha = overlay.getchannel("A") if overlay.mode == "RGBA" else None
    return Image.composite(mixed, base, alpha).convert("RGBA") if alpha else mixed.convert("RGBA")


def tint(mask, color, opacity=1.0):
    """{@code color}, shaped by {@code mask} (an L image) at {@code opacity}."""
    alpha = mask if opacity >= 1 else mask.point(lambda v: int(v * opacity))
    layer = Image.new("RGBA", mask.size, tuple(color) + (0,))
    layer.putalpha(alpha)
    return layer


def shifted(mask, dx, dy):
    out = Image.new("L", mask.size, 0)
    out.paste(mask, (int(round(dx)), int(round(dy))))
    return out


def clip(mask, by):
    return ImageChops.multiply(mask, by)


def blur(image, radius):
    return image.filter(ImageFilter.GaussianBlur(radius)) if radius > 0 else image


# ---- Shapes, drawn as masks ----

class Shape:
    """An outline to fill: a rounded rectangle, an ellipse, a cross or a polygon, in image space."""

    def __init__(self, kind, box, radius=0, arm=1 / 3, points=None):
        self.kind = kind
        self.box = [float(v) for v in box]
        self.radius = radius
        self.arm = arm
        self.points = points

    def draw(self, draw, dx=0, dy=0, fill=255):
        x0, y0, x1, y1 = self.box
        box = [x0 + dx, y0 + dy, x1 + dx, y1 + dy]
        if self.kind == "rect":
            r = min(self.radius, (box[2] - box[0]) / 2, (box[3] - box[1]) / 2)
            draw.rounded_rectangle(box, radius=r, fill=fill)
        elif self.kind == "ellipse":
            draw.ellipse(box, fill=fill)
        elif self.kind == "cross":
            w, h = box[2] - box[0], box[3] - box[1]
            a = self.arm
            r = self.radius
            draw.rounded_rectangle([box[0] + w * (0.5 - a / 2), box[1], box[0] + w * (0.5 + a / 2), box[3]], radius=r, fill=fill)
            draw.rounded_rectangle([box[0], box[1] + h * (0.5 - a / 2), box[2], box[1] + h * (0.5 + a / 2)], radius=r, fill=fill)
        elif self.kind == "polygon":
            draw.polygon([(x + dx, y + dy) for x, y in self.points], fill=fill)

    def inset(self, amount):
        x0, y0, x1, y1 = self.box
        return Shape(self.kind, [x0 + amount, y0 + amount, x1 - amount, y1 - amount],
                     max(0, self.radius - amount), self.arm, self.points)

    def moved(self, dx, dy):
        x0, y0, x1, y1 = self.box
        points = [(x + dx, y + dy) for x, y in self.points] if self.points else None
        return Shape(self.kind, [x0 + dx, y0 + dy, x1 + dx, y1 + dy], self.radius, self.arm, points)


def rect(box, radius=0):
    return Shape("rect", box, radius)


def ellipse(box):
    return Shape("ellipse", box)


def cross(box, arm=1 / 3, radius=0):
    return Shape("cross", box, radius, arm)


def box_of(x, y, w, h, scale=1.0):
    """The box of a control's rectangle, scaled about its centre."""
    cx, cy = x + w / 2, y + h / 2
    return [cx - w * scale / 2, cy - h * scale / 2, cx + w * scale / 2, cy + h * scale / 2]


class Region:
    """Part of the picture to work on: a crop around a shape, with room for its shadow."""

    def __init__(self, image, shape, margin):
        x0, y0, x1, y1 = shape.box
        if shape.points:
            xs = [p[0] for p in shape.points]
            ys = [p[1] for p in shape.points]
            x0, y0, x1, y1 = min(xs), min(ys), max(xs), max(ys)
        self.x = max(0, int(x0 - margin))
        self.y = max(0, int(y0 - margin))
        right = min(image.width, int(math.ceil(x1 + margin)))
        bottom = min(image.height, int(math.ceil(y1 + margin)))
        self.size = (max(1, right - self.x), max(1, bottom - self.y))
        self.image = image
        self.crop = image.crop((self.x, self.y, right, bottom)).convert("RGBA")

    def mask(self, shape):
        m = Image.new("L", self.size, 0)
        shape.draw(ImageDraw.Draw(m), -self.x, -self.y)
        return m

    def put(self, layer):
        self.crop = Image.alpha_composite(self.crop, layer)

    def done(self):
        self.image.paste(self.crop.convert(self.image.mode), (self.x, self.y))


# ---- Materials ----

def drop_shadow(region, mask, offset, radius, opacity, color=(0, 0, 0)):
    region.put(tint(blur(shifted(mask, 0, offset), radius), color, opacity))


def inner_shadow(region, mask, offset, radius, opacity, color=(0, 0, 0)):
    """Darkens just inside the top edge, as if the shape were a hollow."""
    outside = ImageOps.invert(mask)
    region.put(tint(clip(blur(shifted(outside, 0, offset), radius), mask), color, opacity))


def bevel(region, mask, size, light=0.35, dark=0.3, light_color=(255, 255, 255), dark_color=(0, 0, 0)):
    """A lit top edge and a shaded bottom edge, so the shape reads as raised."""
    top = ImageChops.subtract(mask, shifted(mask, 0, size))
    bottom = ImageChops.subtract(mask, shifted(mask, 0, -size))
    soft = max(1, size * 0.6)
    region.put(tint(clip(blur(top, soft), mask), light_color, light))
    region.put(tint(clip(blur(bottom, soft), mask), dark_color, dark))


def rim(region, mask, width, color, opacity=1.0):
    """A thin line around the inside of the edge."""
    inner = mask.filter(ImageFilter.MinFilter(max(3, int(width) * 2 + 1)))
    region.put(tint(ImageChops.subtract(mask, inner), color, opacity))


def paint(image, shape, color, *, lift=6, shadow=0.45, shadow_blur=None, light=0.18, dark=0.28, bevel_size=None,
          bevel_light=0.4, bevel_dark=0.35, shine=0.0, outline=None, outline_width=None, texture=None,
          shading="vertical"):
    """
    Draws {@code shape} as a solid object standing {@code lift} pixels off the surface: a soft
    shadow below, a body lit from above, bevelled edges and an optional gloss and outline.
    {@code texture} (an RGBA image the size of the picture) replaces the flat colour.
    """
    s = SUPERSAMPLE
    x0, y0, x1, y1 = shape.box
    size = max(x1 - x0, y1 - y0)
    shadow_blur = shadow_blur if shadow_blur is not None else max(2, lift * 1.2 + size * 0.02)
    region = Region(image, shape, shadow_blur * 3 + lift + 4 * s)
    mask = region.mask(shape)
    if shadow > 0 and lift > 0:
        drop_shadow(region, mask, lift, shadow_blur, shadow)
        drop_shadow(region, mask, lift * 0.3, max(1, lift * 0.3), shadow * 0.6)
    # The body: a gradient, lighter at the top.
    if texture is not None:
        body = texture.crop((region.x, region.y, region.x + region.size[0], region.y + region.size[1]))
    elif shading == "radial":
        # Lit from above left: brightest a little off centre, darkening towards the far edge.
        w, h = x1 - x0, y1 - y0
        cx = (x0 - region.x + w * 0.38) / region.size[0]
        cy = (y0 - region.y + h * 0.32) / region.size[1]
        body = radial(region.size, lighten(color, light), darken(color, dark), center=(cx, cy), reach=max(w, h) * 0.85)
    else:
        body = solid(region.size, color)
        local_top, local_bottom = y0 - region.y, y1 - region.y
        g = gradient((1, max(1, int(local_bottom - local_top))), lighten(color, light), darken(color, dark))
        body.paste(g.resize((region.size[0], g.height)), (0, int(local_top)))
    layer = body.copy()
    layer.putalpha(mask)
    region.put(layer)
    if texture is not None:
        region.put(tint(mask, (255, 255, 255), 0))
    b = bevel_size if bevel_size is not None else max(2, size * 0.025)
    bevel(region, mask, b, bevel_light, bevel_dark)
    if shine > 0:
        spot_region_gloss(region, shape, shine)
    if outline is not None:
        rim(region, mask, outline_width or max(2, size * 0.02), outline)
    region.done()


def spot_region_gloss(region, shape, strength):
    x0, y0, x1, y1 = shape.box
    w, h = x1 - x0, y1 - y0
    local = Shape(shape.kind, [x0 - region.x, y0 - region.y, x1 - region.x, y1 - region.y], shape.radius, shape.arm)
    m = Image.new("L", region.size, 0)
    ImageDraw.Draw(m).ellipse([local.box[0] + w * 0.16, local.box[1] + h * 0.04,
                               local.box[2] - w * 0.16, local.box[1] + h * 0.46], fill=255)
    m = clip(blur(m, max(w, h) * 0.08), region.mask(shape))
    region.put(tint(m, (255, 255, 255), strength))


def hollow(image, shape, color=None, depth=6, opacity=0.55, lip=0.25):
    """A recess in the surface: darkened inside, shadowed under its top edge, lit along its bottom lip."""
    region = Region(image, shape, depth * 3)
    mask = region.mask(shape)
    if color is not None:
        region.put(tint(mask, color, 1.0))
    inner_shadow(region, mask, depth, depth * 1.2, opacity)
    lower = ImageChops.subtract(mask, shifted(mask, 0, -max(1, depth * 0.35)))
    region.put(tint(blur(lower, max(1, depth * 0.3)), (255, 255, 255), lip))
    region.done()


def text(image, xy, value, size, color, kind="bold", anchor="mm", engrave=0.0, shadow=0.0, spacing=0):
    """Text, optionally pressed into the surface (a dark top and light bottom edge) or with a shadow."""
    f = font(size, kind)
    draw = ImageDraw.Draw(image)
    x, y = xy
    if spacing:
        # Letter-spaced: draw each character in turn, centred as a whole.
        widths = [draw.textlength(c, font=f) for c in value]
        total = sum(widths) + spacing * (len(value) - 1)
        left = x - total / 2 if anchor[0] == "m" else x
        for c, w in zip(value, widths):
            text(image, (left + w / 2, y), c, size, color, kind, "m" + anchor[1], engrave, shadow)
            left += w + spacing
        return
    if shadow:
        layer = Image.new("L", image.size, 0)
        ImageDraw.Draw(layer).text((x, y + size * 0.06), value, fill=255, font=f, anchor=anchor)
        if image.mode == "RGBA":
            image.alpha_composite(tint(blur(layer, size * 0.12), (0, 0, 0), shadow))
            image.alpha_composite(tint(blur(layer, size * 0.04), (0, 0, 0), shadow * 0.6))
    if engrave:
        draw.text((x, y - size * 0.045), value, fill=darken(color, 0.55), font=f, anchor=anchor)
        draw.text((x, y + size * 0.045), value, fill=lighten(color, 0.45), font=f, anchor=anchor)
    draw.text((x, y), value, fill=color, font=f, anchor=anchor)


def text_width(value, size, kind="bold"):
    return ImageDraw.Draw(Image.new("L", (1, 1))).textlength(value, font=font(size, kind))


def rounded_points(box, radii, steps=12):
    """A rectangle's outline with each corner rounded by its own radius (top-left, top-right, bottom-right, bottom-left)."""
    x0, y0, x1, y1 = box
    corners = [(x0, y0, 180), (x1, y0, 270), (x1, y1, 0), (x0, y1, 90)]
    signs = [(1, 1), (-1, 1), (-1, -1), (1, -1)]
    points = []
    for (cx, cy, start), (sx, sy), r in zip(corners, signs, radii):
        ox, oy = cx + sx * r, cy + sy * r
        for i in range(steps + 1):
            a = math.radians(start + 90 * i / steps)
            points.append((ox + math.cos(a) * r, oy + math.sin(a) * r))
    return points


def poly(points):
    return Shape("polygon", [min(p[0] for p in points), min(p[1] for p in points),
                             max(p[0] for p in points), max(p[1] for p in points)], points=points)


def capsule_points(cx, cy, length, width, angle, steps=10):
    """A pill shape centred on (cx, cy), {@code length} long, turned {@code angle} degrees."""
    a = math.radians(angle)
    ux, uy = math.cos(a), math.sin(a)
    r = width / 2
    half = max(0.0, length / 2 - r)
    points = []
    for end, base in ((1, -90), (-1, 90)):
        ex, ey = cx + ux * half * end, cy + uy * half * end
        for i in range(steps + 1):
            t = math.radians(angle + base + 180 * i / steps)
            points.append((ex + math.cos(t) * r, ey + math.sin(t) * r))
    return points


def triangle(cx, cy, size, direction):
    """A small arrowhead pointing {@code direction} degrees (0 = right, 90 = down)."""
    a = math.radians(direction)
    tip = (cx + math.cos(a) * size, cy + math.sin(a) * size)
    left = (cx + math.cos(a + 2.4) * size, cy + math.sin(a + 2.4) * size)
    right = (cx + math.cos(a - 2.4) * size, cy + math.sin(a - 2.4) * size)
    return [tip, left, right]


def stamp(image, shape, color, opacity=1.0):
    """Fills {@code shape} flat, with no lighting: printing, paint."""
    region = Region(image, shape, 2)
    region.put(tint(region.mask(shape), color, opacity))
    region.done()


def glow(image, shape, color, radius, opacity):
    region = Region(image, shape, radius * 3)
    region.put(tint(blur(region.mask(shape), radius), color, opacity))
    region.done()


def plain_edge(image, height, top=False):
    """
    Blends the bottom (or top) {@code height} rows into their own average, column by column and
    softened, so stretching the last row to fill a taller screen shows no streaks.
    """
    w, h = image.size
    height = int(height)
    box = (0, 0, w, height) if top else (0, h - height, w, h)
    band = image.crop(box).convert("RGBA")
    even = blur(band.resize((w, 1), Image.BOX).resize((w, height)), 24 * SUPERSAMPLE)
    ramp = Image.linear_gradient("L").resize((w, height))
    if top:
        ramp = ramp.transpose(Image.FLIP_TOP_BOTTOM)
    image.paste(Image.composite(even, band, ramp), box[:2])


def fade_edge(image, color, height, top=False):
    """Blends the bottom (or top) {@code height} pixels into a flat colour, so the edge can be stretched."""
    w, h = image.size
    ramp = gradient((1, max(1, int(height))), (0, 0, 0), (255, 255, 255)).convert("L").resize((w, max(1, int(height))))
    if top:
        ramp = ramp.transpose(Image.FLIP_TOP_BOTTOM)
    layer = Image.new("RGBA", (w, int(height)), tuple(color) + (0,))
    layer.putalpha(ramp)
    band = image.crop((0, 0 if top else h - int(height), w, int(height) if top else h)).convert("RGBA")
    image.paste(Image.alpha_composite(band, layer).convert(image.mode), (0, 0 if top else h - int(height)))


# ---- The style every skin starts from ----

class Style:
    """A console's look. Subclasses set the colours and draw the body; the parts are shared."""
    name = "Style"
    subtitle = ""
    body_top = (60, 60, 70)
    body_bottom = (40, 40, 48)
    bezel = (20, 20, 26)
    bezel_edge = None           # A coloured line around the bezel.
    print_color = (200, 200, 210)  # Text printed on the body.
    wordmark = (235, 235, 240)
    accent = (120, 200, 255)       # The subtitle and the power light.
    dpad = (40, 40, 48)
    arrows = None               # Arrowheads on the d-pad arms; darker than the pad if not set.
    a = (200, 40, 70)
    b = (200, 40, 70)
    letters = (255, 255, 255)
    pill = (70, 70, 80)
    utility = (70, 70, 80)
    icon = (220, 220, 230)
    well = True                 # Recesses under the d-pad and buttons.
    grille = True               # Speaker slots in portrait.
    top_print = True            # "OFF • ON" by the camera, like the original's power switch.
    button_shine = 0.4
    button_lift = 9
    print_shadow = 0.0          # A shadow under printed text, for busy bodies.
    wordmark_font = "italic"

    background_color = None       # Shown where the picture doesn't reach; the body's bottom colour if not set.

    @property
    def background(self):
        return hexify(self.background_color or self.body_bottom)

    # The body: everything behind the parts.
    def body(self, size, s):
        image = gradient(size, self.body_top, self.body_bottom)
        return grain(image, 10, 1)

    def decorate(self, image, layout, s, portrait):
        """Extra artwork over the body, under the parts."""

    # The screen's bezel, with its lens, power light and wordmark.
    def bezel_shape(self, screen, s):
        x, y, w, h = screen
        pad = w * 0.05
        box = [x - pad, y - pad, x + w + pad, y + h + pad * 1.75]
        small = pad * 0.5
        return poly(rounded_points(box, [small, small, pad * 2.4, small])), pad

    def draw_bezel(self, image, screen, s, portrait):
        shape, pad = self.bezel_shape(screen, s)
        paint(image, shape, self.bezel, lift=3 * s, shadow=0.35, light=0.08, dark=0.12, bevel_size=2 * s,
              bevel_light=0.18, bevel_dark=0.4, outline=self.bezel_edge, outline_width=3 * s)
        x, y, w, h = screen
        # A faint reflection across the glass.
        region = Region(image, shape, 0)
        sheen = Image.new("L", region.size, 0)
        sw, sh = region.size
        ImageDraw.Draw(sheen).polygon([(sw * 0.55, 0), (sw * 0.8, 0), (sw * 0.25, sh), (0, sh)], fill=255)
        sheen = clip(blur(sheen, 12 * s), region.mask(shape))
        region.put(tint(sheen, (255, 255, 255), 0.05))
        region.done()
        # The power light, in the margin left of the screen.
        cx, cy, r = x - pad / 2, y + h * 0.28, pad * 0.14
        light = ellipse([cx - r, cy - r, cx + r, cy + r])
        glow(image, light, self.accent, r * 1.6, 0.8)
        paint(image, light, lighten(self.accent, 0.2), lift=0, shadow=0, light=0.5, dark=0.1, bevel_size=1 * s)
        text(image, (cx, cy + r * 3.2), "POWER", pad * 0.2, darken(self.print_on_bezel(), 0.1), "bold")
        self.draw_wordmark(image, x + w / 2, y + h + pad * 0.88, pad * 0.78)

    def print_on_bezel(self):
        return lerp(self.bezel, self.wordmark, 0.55)

    def draw_wordmark(self, image, cx, cy, size):
        main = "AndroidBoy"
        main_w = text_width(main, size, self.wordmark_font)
        sub = self.subtitle
        sub_size = size * 0.62
        gap = size * 0.45 if sub else 0
        spacing = sub_size * 0.12
        sub_w = (text_width(sub, sub_size, "bold") + spacing * (len(sub) - 1)) if sub else 0
        left = cx - (main_w + gap + sub_w) / 2
        text(image, (left, cy), main, size, self.wordmark, self.wordmark_font, anchor="lm")
        if sub:
            text(image, (left + main_w + gap + sub_w / 2, cy + size * 0.04), sub, sub_size, self.accent, "bold",
                 spacing=spacing)

    # The parts on the body.
    def draw_top(self, image, width, s):
        """Printing along the top edge, clear of the camera in the middle."""
        if not self.top_print:
            return
        y = 48 * s
        size = 17 * s
        x = 150 * s
        text(image, (x, y), "OFF", size, self.print_color, "bold", anchor="rm", shadow=self.print_shadow)
        dot = ellipse([x + 9 * s, y - 4 * s, x + 17 * s, y + 4 * s])
        stamp(image, dot, self.print_color)
        text(image, (x + 26 * s, y), "ON", size, self.print_color, "bold", anchor="lm", shadow=self.print_shadow)
        for direction, ax in ((180, x - 50 * s), (0, x + 70 * s)):
            stamp(image, poly(triangle(ax, y, 7 * s, direction)), self.print_color)

    def draw_grille(self, image, s, area):
        if not self.grille:
            return
        x0, y0, x1, y1 = area
        count = 6
        for i in range(count):
            t = (i + 0.5) / count
            cx = x0 + (x1 - x0) * t
            cy = (y0 + y1) / 2 + (0.5 - t) * (y1 - y0) * 0.25
            slot = poly(capsule_points(cx, cy, (y1 - y0) * 0.55, 12 * s, -60))
            hollow(image, slot, darken(self.body_bottom, 0.3), depth=4 * s, opacity=0.55, lip=0.22)

    def draw_labels(self, image, controls, s):
        for name, value in (("select", "SELECT"), ("start", "START")):
            x, y, w, h = controls[name]
            text(image, (x + w / 2, y + h + 17 * s), value, 17 * s, self.print_color, "bold", spacing=2 * s,
                 shadow=self.print_shadow)

    def draw_wells(self, image, controls, s):
        if not self.well:
            return
        x, y, w, h = controls["dpad"]
        hollow(image, ellipse(box_of(x, y, w, h, 0.98)), darken(self.body_bottom, 0.12), depth=5 * s, opacity=0.35)
        for name in ("a", "b"):
            x, y, w, h = controls[name]
            hollow(image, ellipse(box_of(x, y, w, h, 1.08)), darken(self.body_bottom, 0.15), depth=4 * s, opacity=0.45)

    def draw_dpad(self, image, box, pressed, s):
        x, y, w, h = box
        shape = cross(box_of(x, y, w, h, 0.86), arm=0.34, radius=w * 0.05)
        color = darken(self.dpad, 0.12) if pressed else self.dpad
        lift = 2 * s if pressed else 8 * s
        paint(image, shape.moved(0, 3 * s if pressed else 0), color, lift=lift, shadow=0.55, light=0.22, dark=0.25,
              bevel_size=4 * s, bevel_light=0.3, bevel_dark=0.45)
        cx, cy = x + w / 2, y + h / 2 + (3 * s if pressed else 0)
        arrow = self.arrows or darken(self.dpad, 0.45)
        for direction, dx, dy in ((270, 0, -1), (90, 0, 1), (180, -1, 0), (0, 1, 0)):
            ax, ay = cx + dx * w * 0.31, cy + dy * h * 0.31
            stamp(image, poly(triangle(ax, ay, w * 0.035, direction)), arrow, 0.9)
        dimple = ellipse(box_of(x, y + (3 * s if pressed else 0), w, h, 0.17))
        hollow(image, dimple, None, depth=3 * s, opacity=0.4, lip=0.15)

    def draw_button(self, image, name, box, pressed, s):
        x, y, w, h = box
        color = self.a if name == "a" else self.b
        drop = 3 * s if pressed else 0
        shape = ellipse(box_of(x, y + drop, w, h, 0.9))
        paint(image, shape, darken(color, 0.12) if pressed else color, lift=(2 if pressed else self.button_lift) * s,
              shadow=0.5, light=0.35, dark=0.3, shading="radial", bevel_size=3 * s, bevel_light=0.25,
              bevel_dark=0.35, shine=self.button_shine * (0.6 if pressed else 1))
        text(image, (x + w / 2, y + h / 2 + drop), name.upper(), h * 0.4, self.letters, "bold", engrave=0.0)

    def draw_pill(self, image, box, pressed, s):
        x, y, w, h = box
        drop = 2 * s if pressed else 0
        points = capsule_points(x + w / 2, y + h / 2 + drop, w * 0.78, h * 0.5, 0)
        paint(image, poly(points), darken(self.pill, 0.12) if pressed else self.pill,
              lift=(1 if pressed else 5) * s, shadow=0.5, light=0.2, dark=0.25, bevel_size=2 * s)

    def draw_utility(self, image, name, box, pressed, s):
        x, y, w, h = box
        drop = 2 * s if pressed else 0
        points = capsule_points(x + w / 2, y + h / 2 + drop, w * 0.9, h * 0.72, 0)
        paint(image, poly(points), darken(self.utility, 0.12) if pressed else self.utility,
              lift=(1 if pressed else 5) * s, shadow=0.45, light=0.2, dark=0.25, bevel_size=2 * s)
        draw_icon(image, name, x + w / 2, y + h / 2 + drop, h * 0.2, lighten(self.icon, 0.2) if pressed else self.icon)


def draw_icon(image, name, cx, cy, size, color):
    """The menu, fast forward and rewind symbols."""
    if name == "menu":
        for i in (-1, 0, 1):
            stamp(image, rect([cx - size * 1.1, cy + i * size * 0.6 - size * 0.14, cx + size * 1.1,
                               cy + i * size * 0.6 + size * 0.14], size * 0.14), color)
    else:
        direction = 0 if name == "fastForward" else 180
        sign = 1 if direction == 0 else -1
        for offset in (-0.55, 0.55):
            tx = cx + offset * size * 1.1
            points = [(tx + sign * size * 0.75, cy), (tx - sign * size * 0.55, cy - size * 0.8),
                      (tx - sign * size * 0.55, cy + size * 0.8)]
            stamp(image, poly(points), color)


# ---- Rendering ----

# Where a hole-punch camera sits in the portrait pictures: the middle of the strip above the
# screen. The app lines this point up with the phone's camera.
CAMERA = [540, 48]
GRILLE = [800, 1610, 985, 1830]
# Portrait pictures carry this much more body below the controls, for phones taller than 16:9.
BELOW = 480


def scaled(rect, s):
    return [v * s for v in rect]


def render(style, out, name, size, layout, portrait, quantize=False, mask_pressed=False, extension="png"):
    s = SUPERSAMPLE
    big = (size[0] * s, size[1] * s)
    controls = {key: scaled(r, s) for key, r in layout["controls"].items()}
    screen = scaled(layout["screen"], s)
    image = style.body(big, s).convert("RGBA")
    # Where the layout ends: extra body below it is only seen on taller phones.
    style.layout_height = big[1] - (BELOW * s if portrait else 0)
    style.decorate(image, controls, s, portrait)
    if portrait:
        style.draw_top(image, big[0], s)
        style.draw_grille(image, s, scaled(GRILLE, s))
    style.draw_bezel(image, screen, s, portrait)
    style.draw_wells(image, controls, s)
    style.draw_labels(image, controls, s)

    pictures = []
    for pressed in (False, True):
        picture = image.copy()
        style.draw_dpad(picture, controls["dpad"], pressed, s)
        for key in ("b", "a"):
            style.draw_button(picture, key, controls[key], pressed, s)
        for key in ("select", "start"):
            style.draw_pill(picture, controls[key], pressed, s)
        for key in ("menu", "fastForward", "rewind"):
            style.draw_utility(picture, key, controls[key], pressed, s)
        x, y, w, h = screen
        ImageDraw.Draw(picture).rectangle([x, y, x + w, y + h], fill=(0, 0, 0, 255))
        pictures.append(picture)
    if portrait:
        # The very top and bottom rows are stretched on phones taller still: make them even.
        for picture in pictures:
            plain_edge(picture, 70 * s)
            plain_edge(picture, 5 * s, top=True)
    if mask_pressed:
        # The app only ever copies the controls' own rectangles out of the pressed picture: leave
        # the rest plain, which makes the file much smaller.
        keep = Image.new("L", big, 0)
        draw = ImageDraw.Draw(keep)
        for key, (x, y, w, h) in controls.items():
            draw.rectangle([x - 2 * s, y - 2 * s, x + w + 2 * s, y + h + 2 * s], fill=255)
        plain = Image.new("RGBA", big, tuple(style.body_bottom) + (255,))
        pictures[1] = Image.composite(pictures[1], plain, keep)
    for picture, suffix in zip(pictures, ("", "_pressed")):
        picture = picture.convert("RGB").resize(size, Image.LANCZOS)
        path = os.path.join(out, name + suffix + "." + extension)
        if extension == "webp":
            # The textures are too detailed for a small PNG; WebP keeps them sharp at a fraction of the size.
            picture.save(path, quality=86, method=6)
            continue
        if quantize:
            # 256 colours with dithering: indistinguishable on a phone, and far smaller.
            picture = picture.quantize(256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.FLOYDSTEINBERG)
        picture.save(path, optimize=True)


def write_skin(style, out, display_name, quantize=False, mask_pressed=False, extension="png"):
    os.makedirs(out, exist_ok=True)
    for old in os.listdir(out):
        if old.endswith((".png", ".webp")):
            os.remove(os.path.join(out, old))
    e = extension
    portrait = dict(image="portrait." + e, pressedImage="portrait_pressed." + e, camera=CAMERA, extendsBelow=BELOW,
                    **PORTRAIT)
    landscape = dict(image="landscape." + e, pressedImage="landscape_pressed." + e, **LANDSCAPE)
    render(style, out, "portrait", (1080, 1920 + BELOW), PORTRAIT, True, quantize, mask_pressed, e)
    render(style, out, "landscape", (1920, 1080), LANDSCAPE, False, quantize, mask_pressed, e)
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


# ---- The styles ----

class Midnight(Style):
    """Deep indigo soft-touch plastic, cyan buttons and a blue-black lens."""
    name = "Midnight"
    subtitle = "MIDNIGHT"
    body_top = (42, 48, 96)
    body_bottom = (22, 25, 52)
    bezel = (12, 14, 30)
    print_color = (150, 160, 215)
    wordmark = (225, 230, 250)
    accent = (80, 220, 240)
    dpad = (30, 34, 66)
    a = (64, 200, 222)
    b = (64, 200, 222)
    letters = (14, 40, 60)
    pill = (48, 54, 100)
    utility = (48, 54, 100)
    icon = (180, 196, 250)

    def body(self, size, s):
        image = gradient(size, self.body_top, self.body_bottom)
        # Light falling from the top left.
        light = radial(size, (255, 255, 255), (0, 0, 0), center=(0.15, 0.05), radius=0.5).convert("L")
        image = Image.composite(lighten_image(image, 0.08), image, light)
        return grain(image, 12, 2)


def lighten_image(image, t):
    return Image.blend(image.convert("RGBA"), solid(image.size, (255, 255, 255)), t)


def stops(size, colors, horizontal=False):
    """A gradient through several colours, evenly spaced, top to bottom (or left to right)."""
    n = 256
    column = Image.new("RGB", (1, n))
    for i in range(n):
        t = i / (n - 1) * (len(colors) - 1)
        k = min(len(colors) - 2, int(t))
        column.putpixel((0, i), lerp(colors[k], colors[k + 1], t - k))
    if horizontal:
        column = column.rotate(90, expand=True).transpose(Image.FLIP_LEFT_RIGHT)
    return column.resize(size, Image.BILINEAR).convert("RGBA")


GOLD = [(250, 226, 150), (212, 168, 72), (160, 116, 40), (236, 200, 110), (180, 134, 52)]
CHROME = [(250, 252, 255), (180, 186, 198), (120, 126, 140), (226, 230, 238), (150, 156, 170)]


def metal(image, shape, colors, lift=3, outline=None):
    """A polished metal part: a banded gradient, bevelled."""
    x0, y0, x1, y1 = shape.box
    texture = Image.new("RGBA", image.size, (0, 0, 0, 0))
    band = stops((max(1, int(x1 - x0)), max(1, int(y1 - y0))), colors)
    texture.paste(band, (int(x0), int(y0)))
    paint(image, shape, colors[1], lift=lift, shadow=0.4, texture=texture, bevel_size=2 * SUPERSAMPLE,
          bevel_light=0.4, bevel_dark=0.35, outline=outline)


def speckles(image, count, colors, radius, seed, area=None, glow_radius=0, alpha=255):
    rng = random.Random(seed)
    w, h = image.size
    x0, y0, x1, y1 = area or (0, 0, w, h)
    layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    for _ in range(count):
        x, y = rng.uniform(x0, x1), rng.uniform(y0, y1)
        r = radius * rng.uniform(0.4, 1.3)
        color = rng.choice(colors)
        draw.ellipse([x - r, y - r, x + r, y + r], fill=tuple(color) + (alpha,))
    if glow_radius:
        image.alpha_composite(blur(layer, glow_radius))
    image.alpha_composite(layer)


def threshold(n, low, high=256):
    return n.point(lambda v: 255 if low <= v < high else 0)


class Arcade(Style):
    """A cabinet's control panel: neon stripes, a ball-top joystick and concave arcade buttons."""
    name = "Arcade"
    subtitle = "ARCADE"
    body_top = (30, 30, 38)
    body_bottom = (12, 12, 16)
    bezel = (10, 10, 12)
    bezel_edge = (120, 120, 132)
    print_color = (252, 191, 73)
    wordmark = (245, 245, 250)
    accent = (252, 191, 73)
    a = (232, 40, 56)
    b = (40, 110, 232)
    pill = (252, 191, 73)
    utility = (58, 58, 68)
    icon = (240, 240, 245)
    grille = False
    STRIPES = [(230, 57, 70), (247, 127, 0), (252, 191, 73)]

    def body(self, size, s):
        image = gradient(size, self.body_top, self.body_bottom)
        return grain(image, 18, 3)

    def decorate(self, image, controls, s, portrait):
        w, h = image.size[0], self.layout_height
        # Neon racing stripes low across the panel, glowing.
        base = h * (0.935 if portrait else 0.955)
        rise = (26 if portrait else 60) * s
        for i, color in enumerate(self.STRIPES):
            top = base + i * 14 * s
            band = poly([(0, top), (w, top - rise), (w, top - rise + 10 * s), (0, top + 10 * s)])
            glow(image, band, color, 14 * s, 0.6)
            stamp(image, band, color)

    def draw_top(self, image, width, s):
        text(image, (150 * s, 48 * s), "1 PLAYER", 18 * s, self.print_color, "bold", spacing=2 * s)
        text(image, (width - 170 * s, 48 * s), "INSERT COIN", 18 * s, self.print_color, "bold", spacing=2 * s)

    def draw_wells(self, image, controls, s):
        pass

    def draw_dpad(self, image, box, pressed, s):
        # A joystick: a round base plate with a gate, the shaft's collar and a ball top.
        x, y, w, h = box
        cx, cy = x + w / 2, y + h / 2
        plate = ellipse(box_of(x, y, w, h, 0.96))
        paint(image, plate, (40, 40, 48), lift=4 * s, shadow=0.5, light=0.15, dark=0.3, outline=(90, 90, 100),
              outline_width=3 * s)
        gate = poly([(cx + math.cos(math.radians(22.5 + 45 * i)) * w * 0.2, cy + math.sin(math.radians(22.5 + 45 * i)) * h * 0.2)
                     for i in range(8)])
        hollow(image, gate, (12, 12, 14), depth=4 * s, opacity=0.6)
        for direction, dx, dy in ((270, 0, -1), (90, 0, 1), (180, -1, 0), (0, 1, 0)):
            stamp(image, poly(triangle(cx + dx * w * 0.38, cy + dy * h * 0.38, w * 0.035, direction)), (150, 150, 165))
        drop = 5 * s if pressed else 0
        shaft = rect([cx - w * 0.035, cy - h * 0.02, cx + w * 0.035, cy + h * 0.12], w * 0.03)
        paint(image, shaft, (170, 170, 180), lift=0, shadow=0, light=0.4, dark=0.4)
        r = w * 0.19
        ball = ellipse([cx - r, cy - r * 1.05 + drop, cx + r, cy + r * 0.95 + drop])
        paint(image, ball, darken(self.a, 0.1) if pressed else self.a, lift=(4 if pressed else 12) * s, shadow=0.55,
              light=0.45, dark=0.45, shading="radial", shine=0.5, bevel_size=2 * s, bevel_light=0.1, bevel_dark=0.2)

    def draw_button(self, image, name, box, pressed, s):
        # Concave caps in a white bezel ring.
        x, y, w, h = box
        color = self.a if name == "a" else self.b
        ring = ellipse(box_of(x, y, w, h, 0.96))
        paint(image, ring, (228, 228, 234), lift=6 * s, shadow=0.55, light=0.2, dark=0.25, shading="radial")
        drop = 3 * s if pressed else 0
        cap = ellipse(box_of(x, y + drop, w, h, 0.72))
        region = Region(image, cap, 10 * s)
        mask = region.mask(cap)
        body = radial(region.size, darken(color, 0.3), lighten(color, 0.2 if not pressed else 0.05),
                      center=(0.5, 0.45), reach=w * 0.42)
        body.putalpha(mask)
        region.put(body)
        inner_shadow(region, mask, 5 * s, 6 * s, 0.6)
        region.done()
        text(image, (x + w / 2, y + h / 2 + drop), name.upper(), h * 0.3, (255, 255, 255), "bold")

    def draw_pill(self, image, box, pressed, s):
        x, y, w, h = box
        drop = 2 * s if pressed else 0
        cap = poly(capsule_points(x + w / 2, y + h / 2 + drop, w * 0.82, h * 0.62, 0))
        paint(image, cap, darken(self.pill, 0.15) if pressed else self.pill, lift=(1 if pressed else 5) * s,
              shadow=0.5, light=0.3, dark=0.2, shine=0.35, bevel_size=2 * s)


class Woodgrain(Style):
    """A 1970s deluxe handheld: walnut sides, a cream faceplate with chrome trim, orange buttons."""
    name = "Woodgrain"
    subtitle = "DELUXE"
    body_top = (116, 70, 38)
    body_bottom = (84, 50, 26)
    CREAM = (240, 231, 212)
    bezel = (58, 40, 28)
    print_color = (110, 86, 64)
    wordmark = (240, 231, 212)
    accent = (232, 128, 60)
    dpad = (62, 44, 32)
    a = (228, 106, 44)
    b = (228, 106, 44)
    letters = (255, 244, 226)
    pill = (74, 54, 40)
    utility = (74, 54, 40)
    icon = (240, 231, 212)
    wordmark_font = "serif"

    def body(self, size, s):
        w, h = size
        # Grain: streaky noise stretched along the board, turned into rings.
        streaks = noise((max(2, w // 16), h), 5 * s, 11, octaves=3).resize((w, h), Image.BICUBIC)
        ramp = Image.linear_gradient("L").resize((w, h))
        combined = ImageChops.add(ramp.point(lambda v: v // 3), streaks.point(lambda v: v * 2 // 3))
        rings = combined.point(lambda v: int(128 + 127 * math.sin(v * 0.55)))
        fine = noise((max(2, w // 30), h), 1 * s, 12).resize((w, h), Image.BICUBIC)
        rings = ImageChops.multiply(rings.point(lambda v: 150 + v * 105 // 255), fine.point(lambda v: 200 + v * 55 // 255))
        return ImageOps.colorize(rings, (58, 32, 16), (150, 94, 52)).convert("RGBA")

    def plate(self, size, s, portrait):
        w, h = size
        side = 30 * s
        if portrait:
            return rect([side, 12 * s, w - side, h + 200 * s], 36 * s)  # Runs off the bottom.
        return rect([side, side, w - side, h - side], 36 * s)

    def decorate(self, image, controls, s, portrait):
        shape = self.plate(image.size, s, portrait)
        texture = gradient(image.size, lighten(self.CREAM, 0.05), darken(self.CREAM, 0.05))
        texture = grain(texture, 6, 13)
        paint(image, shape, self.CREAM, lift=6 * s, shadow=0.5, texture=texture, bevel_size=3 * s,
              outline=(200, 200, 206), outline_width=5 * s)

    def draw_wells(self, image, controls, s):
        x, y, w, h = controls["dpad"]
        hollow(image, ellipse(box_of(x, y, w, h, 1.02)), darken(self.CREAM, 0.12), depth=5 * s, opacity=0.3)
        for name in ("a", "b"):
            x, y, w, h = controls[name]
            hollow(image, ellipse(box_of(x, y, w, h, 1.1)), darken(self.CREAM, 0.12), depth=4 * s, opacity=0.35)


class Space(Style):
    """Deep space: a nebula and stars, a ringed planet, chrome buttons and cyan trim."""
    name = "Space"
    subtitle = "ORBIT"
    body_top = (14, 16, 40)
    body_bottom = (4, 4, 12)
    bezel = (8, 10, 24)
    bezel_edge = (80, 230, 255)
    print_color = (120, 210, 240)
    wordmark = (230, 240, 255)
    accent = (80, 230, 255)
    dpad = (34, 38, 64)
    arrows = (80, 230, 255)
    a = (205, 212, 226)
    b = (205, 212, 226)
    letters = (24, 34, 70)
    pill = (34, 38, 64)
    utility = (34, 38, 64)
    icon = (80, 230, 255)
    grille = False

    def body(self, size, s):
        w, h = size
        image = gradient(size, self.body_top, self.body_bottom)
        for seed, color, cell, strength in ((21, (120, 60, 200), 260, 0.55), (22, (30, 150, 190), 200, 0.4),
                                            (23, (200, 70, 150), 160, 0.25)):
            n = noise((w // 4, h // 4), cell * s // 4, seed, octaves=4).resize(size, Image.BICUBIC)
            mask = n.point(lambda v: max(0, v - 110) * 2)
            image = Image.composite(Image.blend(image, solid(size, color), strength), image, mask)
        rng = random.Random(24)
        stars = Image.new("RGBA", size, (0, 0, 0, 0))
        draw = ImageDraw.Draw(stars)
        big = []
        for _ in range(int(w * h / (3000 * s * s))):
            x, y = rng.uniform(0, w), rng.uniform(0, h)
            r = rng.choice((0.7, 0.9, 1, 1.2, 1.6, 2.4)) * s
            v = rng.randint(160, 255)
            draw.ellipse([x - r, y - r, x + r, y + r], fill=(v, v, min(255, v + 20), 255))
            if r > 2 * s:
                big.append((x, y, r))
        image.alpha_composite(blur(stars, 3 * s))
        image.alpha_composite(stars)
        for x, y, r in big:
            for length, width in ((r * 6, r * 0.5), (r * 0.5, r * 6)):
                stamp(image, ellipse([x - length, y - width, x + length, y + width]), (220, 235, 255), 0.5)
        return image

    def decorate(self, image, controls, s, portrait):
        w, h = image.size[0], self.layout_height
        px, py, pr = (w * 0.84, h * 0.915, w * 0.075) if portrait else (w * 0.12, h * 0.84, h * 0.07)
        ring_box = [px - pr * 2, py - pr * 0.5, px + pr * 2, py + pr * 0.5]
        ring = Image.new("L", image.size, 0)
        ImageDraw.Draw(ring).ellipse(ring_box, outline=255, width=int(7 * s))
        back = ring.copy()
        ImageDraw.Draw(back).rectangle([0, py, w, h], fill=0)
        image.alpha_composite(tint(back, (214, 176, 126), 0.8))
        planet = ellipse([px - pr, py - pr, px + pr, py + pr])
        glow(image, planet, (255, 150, 90), pr * 0.25, 0.35)
        paint(image, planet, (206, 112, 70), lift=0, shadow=0, light=0.35, dark=0.75, shading="radial",
              bevel_size=1 * s, bevel_light=0, bevel_dark=0)
        front = ring.copy()
        ImageDraw.Draw(front).rectangle([0, 0, w, py], fill=0)
        image.alpha_composite(tint(front, (236, 200, 150), 0.95))

    def draw_button(self, image, name, box, pressed, s):
        x, y, w, h = box
        glow(image, ellipse(box_of(x, y, w, h, 0.96)), self.accent, 8 * s, 0.9 if pressed else 0.45)
        super().draw_button(image, name, box, pressed, s)


class Camo(Style):
    """Woodland camouflage with a stencilled faceplate, screws and olive rubber buttons."""
    name = "Camo"
    subtitle = "FIELD UNIT AB-01"
    COLORS = [(96, 108, 64), (64, 76, 44), (124, 112, 78), (36, 40, 28)]
    body_top = (96, 108, 64)
    body_bottom = (80, 90, 54)
    bezel = (34, 38, 26)
    print_color = (214, 200, 150)
    wordmark = (214, 200, 150)
    accent = (180, 200, 110)
    dpad = (58, 64, 40)
    arrows = (150, 144, 104)
    a = (90, 98, 56)
    b = (90, 98, 56)
    letters = (214, 200, 150)
    pill = (52, 58, 36)
    utility = (52, 58, 36)
    icon = (214, 200, 150)
    button_shine = 0.12
    wordmark_font = "mono"
    print_shadow = 1.0

    def body(self, size, s):
        w, h = size
        image = solid(size, self.COLORS[0])
        for i, color in enumerate(self.COLORS[1:]):
            n = noise((w // 4, h // 4), 60 * s // 4, 31 + i, octaves=3).resize(size, Image.BICUBIC)
            mask = blur(threshold(n, 150), 1.5 * s)
            image = Image.composite(solid(size, color), image, mask)
        return grain(image, 22, 32)

    def draw_top(self, image, width, s):
        super().draw_top(image, width, s)
        for x, y in ((34 * s, 34 * s), (width - 34 * s, 34 * s)):
            screw(image, x, y, 13 * s, s)


def screw(image, x, y, r, s):
    head = ellipse([x - r, y - r, x + r, y + r])
    hollow(image, ellipse([x - r * 1.25, y - r * 1.25, x + r * 1.25, y + r * 1.25]), None, depth=2 * s, opacity=0.4)
    paint(image, head, (170, 170, 164), lift=2 * s, shadow=0.4, shading="radial", light=0.3, dark=0.35)
    slot = poly(capsule_points(x, y, r * 1.5, r * 0.3, 35))
    hollow(image, slot, (60, 60, 58), depth=1 * s, opacity=0.5)


class Candy(Style):
    """Pastel stripes and sprinkles with glossy jelly buttons."""
    name = "Candy"
    subtitle = "SWEETS"
    body_top = (255, 196, 222)
    body_bottom = (255, 168, 204)
    bezel = (72, 34, 72)
    print_color = (176, 64, 124)
    wordmark = (255, 236, 246)
    accent = (255, 140, 190)
    dpad = (156, 116, 236)
    arrows = (236, 226, 255)
    a = (255, 84, 146)
    b = (84, 214, 190)
    letters = (255, 255, 255)
    pill = (255, 248, 252)
    utility = (255, 248, 252)
    icon = (220, 80, 150)
    button_shine = 0.7
    grille = False

    def body(self, size, s):
        w, h = size
        image = gradient(size, self.body_top, self.body_bottom)
        stripes = Image.new("L", size, 0)
        draw = ImageDraw.Draw(stripes)
        step = 90 * s
        for i in range(-int(h / step) - 2, int(w / step) + 2):
            x = i * step
            draw.polygon([(x, 0), (x + step / 2, 0), (x + step / 2 + h, h), (x + h, h)], fill=255)
        image = Image.composite(lighten_image(image, 0.28), image, blur(stripes, 2 * s))
        rng = random.Random(41)
        colors = [(255, 255, 255), (120, 210, 255), (255, 230, 120), (170, 130, 250), (120, 230, 170)]
        for _ in range(int(w * h / (26000 * s * s))):
            x, y = rng.uniform(0, w), rng.uniform(0, h)
            sprinkle = poly(capsule_points(x, y, 26 * s, 8 * s, rng.uniform(0, 180)))
            paint(image, sprinkle, rng.choice(colors), lift=2 * s, shadow=0.25, light=0.3, dark=0.15, bevel_size=1 * s)
        return image


class Carbon(Style):
    """Carbon-fibre weave with a glossy sheen, red trim and red buttons."""
    name = "Carbon"
    subtitle = "CARBON"
    body_top = (34, 34, 38)
    body_bottom = (20, 20, 22)
    bezel = (10, 10, 12)
    bezel_edge = (220, 30, 50)
    print_color = (170, 170, 178)
    wordmark = (236, 236, 240)
    accent = (236, 40, 60)
    dpad = (26, 26, 30)
    arrows = (90, 90, 98)
    a = (214, 26, 44)
    b = (214, 26, 44)
    pill = (48, 48, 54)
    utility = (48, 48, 54)
    icon = (220, 220, 228)

    def body(self, size, s):
        w, h = size
        t = 12 * s
        tile = Image.new("RGBA", (t * 2, t * 2))
        across = gradient((t, t), (70, 70, 76), (18, 18, 20))
        down = gradient((t, t), (58, 58, 64), (14, 14, 16), horizontal=True)
        tile.paste(across, (0, 0))
        tile.paste(down, (t, 0))
        tile.paste(down, (0, t))
        tile.paste(across, (t, t))
        image = Image.new("RGBA", size)
        for y in range(0, h, t * 2):
            for x in range(0, w, t * 2):
                image.paste(tile, (x, y))
        # A broad diagonal sheen, as if lit by a window.
        sheen = Image.new("L", size, 0)
        ImageDraw.Draw(sheen).polygon([(w * 0.2, 0), (w * 0.75, 0), (w * 0.2, h), (-w * 0.35, h)], fill=255)
        return Image.composite(lighten_image(image, 0.12), image, blur(sheen, 120 * s))

    def draw_bezel(self, image, screen, s, portrait):
        x, y, w, h = screen
        pad = w * 0.05
        # A red pinstripe under the bezel.
        line = rect([x - pad * 1.3, y + h + pad * 2.25, x + w + pad * 1.3, y + h + pad * 2.25 + 4 * s], 2 * s)
        glow(image, line, self.accent, 6 * s, 0.5)
        stamp(image, line, self.accent)
        super().draw_bezel(image, screen, s, portrait)


class Ocean(Style):
    """Under the sea: light rays, rippling caustics, bubbles and a sandy floor, with pearl buttons."""
    name = "Ocean"
    subtitle = "REEF"
    body_top = (16, 118, 150)
    body_bottom = (8, 50, 84)
    bezel = (6, 34, 54)
    bezel_edge = (90, 210, 230)
    print_color = (180, 236, 244)
    wordmark = (220, 248, 252)
    accent = (100, 230, 240)
    dpad = (10, 64, 92)
    arrows = (120, 220, 236)
    a = (238, 232, 236)
    b = (238, 232, 236)
    letters = (40, 90, 120)
    pill = (12, 76, 106)
    utility = (12, 76, 106)
    icon = (170, 240, 250)
    grille = False
    button_shine = 0.6

    def body(self, size, s):
        w, h = size
        image = gradient(size, self.body_top, self.body_bottom)
        rays = Image.new("L", size, 0)
        draw = ImageDraw.Draw(rays)
        rng = random.Random(51)
        for _ in range(7):
            x = rng.uniform(-0.1, 1.0) * w
            spread = rng.uniform(0.05, 0.12) * w
            draw.polygon([(x, 0), (x + spread * 0.5, 0), (x + spread * 3, h), (x + spread * 1.2, h)], fill=255)
        fade = Image.linear_gradient("L").resize(size).point(lambda v: 255 - v)
        image = Image.composite(lighten_image(image, 0.18), image, clip(blur(rays, 30 * s), fade))
        n = noise((w // 2, h // 2), 30 * s // 2, 52, octaves=2).resize(size, Image.BICUBIC)
        caustics = blur(n.point(lambda v: 255 if abs(v - 128) < 7 else 0), 1.5 * s)
        image = Image.composite(lighten_image(image, 0.3), image, clip(caustics, fade.point(lambda v: v * 3 // 4)))
        return image

    def decorate(self, image, controls, s, portrait):
        w, h = image.size
        rng = random.Random(53)
        for _ in range(26):
            x, y = rng.uniform(0, w), rng.uniform(0, h * 0.85)
            r = rng.uniform(4, 14) * s
            bubble = ellipse([x - r, y - r, x + r, y + r])
            region = Region(image, bubble, 2 * s)
            m = region.mask(bubble)
            rim(region, m, max(2, r * 0.15), (220, 250, 255), 0.55)
            region.put(tint(m, (255, 255, 255), 0.08))
            region.done()
            stamp(image, ellipse([x - r * 0.5, y - r * 0.6, x - r * 0.1, y - r * 0.2]), (255, 255, 255), 0.6)

    def draw_button(self, image, name, box, pressed, s):
        super().draw_button(image, name, box, pressed, s)
        # A pearl's pink and blue sheen.
        x, y, w, h = box
        drop = 3 * s if pressed else 0
        shape = ellipse(box_of(x, y + drop, w, h, 0.9))
        region = Region(image, shape, 2)
        m = region.mask(shape)
        sheen = gradient(region.size, (255, 190, 220), (170, 220, 255), horizontal=True)
        sheen.putalpha(m.point(lambda v: v * 30 // 255))
        region.put(sheen)
        region.done()
        text(image, (x + w / 2, y + h / 2 + drop), name.upper(), h * 0.4, self.letters, "bold")


class Lava(Style):
    """Cooling basalt split by glowing magma, with molten orange buttons."""
    name = "Lava"
    subtitle = "MAGMA"
    body_top = (46, 38, 36)
    body_bottom = (22, 18, 18)
    bezel = (14, 10, 10)
    bezel_edge = (255, 110, 30)
    print_color = (255, 150, 80)
    wordmark = (255, 226, 200)
    accent = (255, 120, 40)
    dpad = (30, 24, 24)
    arrows = (255, 110, 40)
    a = (240, 92, 24)
    b = (240, 92, 24)
    letters = (255, 240, 220)
    pill = (44, 36, 34)
    utility = (44, 36, 34)
    icon = (255, 150, 80)
    grille = False
    print_shadow = 1.0

    def body(self, size, s):
        w, h = size
        rock = noise((w // 2, h // 2), 6 * s, 61, octaves=4).resize(size, Image.BICUBIC)
        image = ImageOps.colorize(rock, (18, 14, 14), (70, 58, 54)).convert("RGBA")
        image = Image.composite(image, gradient(size, self.body_top, self.body_bottom), Image.new("L", size, 150))
        n = noise((w // 4, h // 4), 140 * s // 4, 62, octaves=3).resize(size, Image.BICUBIC)
        cracks = n.point(lambda v: 255 if abs(v - 128) < 3 else 0)
        image.alpha_composite(tint(blur(cracks, 16 * s), (255, 70, 0), 0.9))
        image.alpha_composite(tint(blur(cracks, 5 * s), (255, 130, 20), 1.0))
        image.alpha_composite(tint(blur(cracks, 1 * s), (255, 230, 150), 1.0))
        speckles(image, int(w * h / (60000 * s * s)), [(255, 160, 60), (255, 220, 120)], 2.5 * s, 63,
                 glow_radius=4 * s)
        return image

    def draw_button(self, image, name, box, pressed, s):
        x, y, w, h = box
        glow(image, ellipse(box_of(x, y, w, h, 0.9)), (255, 100, 20), 12 * s, 0.9 if pressed else 0.5)
        super().draw_button(image, name, box, pressed, s)


class Pixel(Style):
    """8-bit pixel art: chunky stepped blocks, a checkered body and sprite-style shading."""
    name = "Pixel"
    subtitle = "PRESS START"
    body_top = (43, 45, 92)
    body_bottom = (43, 45, 92)
    BLUE2 = (52, 55, 110)
    YELLOW = (255, 214, 64)
    RED = (232, 64, 72)
    GREEN = (72, 200, 112)
    INK = (16, 16, 32)
    print_color = (255, 214, 64)
    wordmark = (255, 214, 64)
    accent = (255, 214, 64)
    wordmark_font = "mono"

    @staticmethod
    def block_rect(image, box, color, px, border=None, shade=True):
        """A rectangle with stepped-off corners, outlined in ink, with a pixel highlight and shadow."""
        draw = ImageDraw.Draw(image)

        def stepped(b, fill):
            x0, y0, x1, y1 = b
            draw.rectangle([x0 + px, y0, x1 - px, y1], fill=fill)
            draw.rectangle([x0, y0 + px, x1, y1 - px], fill=fill)

        x0, y0, x1, y1 = box
        stepped([x0 + px, y0 + px, x1 + px, y1 + px], (10, 10, 24))  # Hard drop shadow.
        if border:
            stepped(box, border)
            box = [x0 + px, y0 + px, x1 - px, y1 - px]
        stepped(box, color)
        if shade:
            bx0, by0, bx1, by1 = box
            draw.rectangle([bx0 + px, by1 - px, bx1 - px, by1], fill=darken(color, 0.3))
            draw.rectangle([bx0 + px, by0 + px, bx0 + px * 2, by0 + px * 2], fill=lighten(color, 0.6))
            draw.rectangle([bx0 + px * 2, by0 + px, bx0 + px * 3, by0 + px * 2], fill=lighten(color, 0.35))

    def body(self, size, s):
        w, h = size
        image = solid(size, self.body_top)
        draw = ImageDraw.Draw(image)
        cell = 40 * s
        for row in range(int(h / cell) + 1):
            for col in range(int(w / cell) + 1):
                if (row + col) % 2:
                    draw.rectangle([col * cell, row * cell, (col + 1) * cell - 1, (row + 1) * cell - 1], fill=self.BLUE2)
        return image

    def draw_top(self, image, width, s):
        text(image, (150 * s, 48 * s), "OFF ■ ON", 20 * s, self.YELLOW, "mono")

    def draw_bezel(self, image, screen, s, portrait):
        x, y, w, h = screen
        pad = w * 0.05
        px = 8 * s
        self.block_rect(image, [x - pad, y - pad, x + w + pad, y + h + pad * 1.75], self.INK, px, border=self.YELLOW,
                        shade=False)
        ImageDraw.Draw(image).rectangle([x - pad * 0.62, y + h * 0.28 - px, x - pad * 0.62 + px * 2, y + h * 0.28 + px],
                                        fill=self.RED)
        text(image, (x + w / 2, y + h + pad * 0.88), "ANDROIDBOY · PRESS START", pad * 0.62, self.YELLOW, "mono")

    def draw_wells(self, image, controls, s):
        pass

    def draw_grille(self, image, s, area):
        x0, y0, x1, y1 = area
        px = 10 * s
        draw = ImageDraw.Draw(image)
        for row in range(5):
            for col in range(5):
                if (row + col) % 2 == 0:
                    x = x0 + col * px * 3 + px * 2
                    y = y0 + row * px * 3 + px * 3
                    draw.rectangle([x, y, x + px * 1.6, y + px * 1.6], fill=self.INK)

    def draw_labels(self, image, controls, s):
        for name, value in (("select", "SELECT"), ("start", "START")):
            x, y, w, h = controls[name]
            text(image, (x + w / 2, y + h + 18 * s), value, 18 * s, self.YELLOW, "mono")

    def draw_dpad(self, image, box, pressed, s):
        px = 8 * s
        x, y, w, h = box
        arm = w / 3
        drop = px if pressed else 0
        color = (96, 96, 140) if pressed else (70, 70, 104)
        self.block_rect(image, [x + arm, y + drop, x + 2 * arm, y + h + drop], color, px, border=self.INK)
        self.block_rect(image, [x, y + arm + drop, x + w, y + 2 * arm + drop], color, px, border=self.INK)
        ImageDraw.Draw(image).rectangle([x + arm + px, y + arm + px * 2 + drop, x + 2 * arm - px, y + 2 * arm - px * 2 + drop],
                                        fill=color)
        cx, cy = x + w / 2, y + h / 2 + drop
        for direction, dx, dy in ((270, 0, -1), (90, 0, 1), (180, -1, 0), (0, 1, 0)):
            stamp(image, poly(triangle(cx + dx * w * 0.33, cy + dy * h * 0.33, w * 0.045, direction)), self.INK)

    def draw_button(self, image, name, box, pressed, s):
        x, y, w, h = box
        drop = 8 * s if pressed else 0
        color = lighten(self.RED if name == "a" else self.GREEN, 0.25 if pressed else 0)
        self.block_rect(image, [x + w * 0.04, y + h * 0.04 + drop, x + w * 0.9, y + h * 0.9 + drop], color, w * 0.12,
                        border=self.INK)
        text(image, (x + w * 0.47, y + h * 0.47 + drop), name.upper(), h * 0.42, self.INK, "mono")

    def draw_pill(self, image, box, pressed, s):
        x, y, w, h = box
        drop = 6 * s if pressed else 0
        self.block_rect(image, [x + w * 0.1, y + h * 0.15 + drop, x + w * 0.86, y + h * 0.75 + drop],
                        lighten(self.YELLOW, 0.35) if pressed else self.YELLOW, 6 * s, border=self.INK)

    def draw_utility(self, image, name, box, pressed, s):
        x, y, w, h = box
        drop = 6 * s if pressed else 0
        self.block_rect(image, [x + w * 0.04, y + h * 0.08 + drop, x + w * 0.92, y + h * 0.86 + drop],
                        lighten(self.YELLOW, 0.35) if pressed else self.YELLOW, 8 * s, border=self.INK)
        draw_icon(image, name, x + w * 0.48, y + h * 0.47 + drop, h * 0.18, self.INK)


class Marble(Style):
    """White marble veined with grey, polished gold trim and onyx buttons."""
    name = "Marble"
    subtitle = ""
    body_top = (244, 242, 238)
    body_bottom = (226, 222, 216)
    bezel = (26, 24, 22)
    print_color = (150, 120, 60)
    wordmark = (222, 184, 96)
    accent = (222, 184, 96)
    dpad = (30, 28, 26)
    arrows = (200, 160, 80)
    a = (34, 32, 30)
    b = (34, 32, 30)
    letters = (226, 190, 104)
    pill = (34, 32, 30)
    utility = (34, 32, 30)
    icon = (226, 190, 104)
    wordmark_font = "serif"

    def body(self, size, s):
        w, h = size
        image = gradient(size, self.body_top, self.body_bottom)
        n = noise((w // 3, h // 3), 110 * s // 3, 71, octaves=5).resize(size, Image.BICUBIC)
        diagonal = Image.linear_gradient("L").rotate(35, expand=True).resize(size)
        field = ImageChops.add(diagonal.point(lambda v: v // 2), n.point(lambda v: v // 2))
        veins = field.point(lambda v: 255 if abs(math.sin(v * 0.16)) < 0.05 else 0)
        image.alpha_composite(tint(blur(veins, 3 * s), (120, 120, 128), 0.5))
        image.alpha_composite(tint(blur(veins, 0.8 * s), (110, 110, 118), 0.6))
        faint = field.point(lambda v: 255 if abs(math.sin(v * 0.43 + 1)) < 0.04 else 0)
        image.alpha_composite(tint(blur(faint, 1 * s), (170, 170, 176), 0.35))
        gold = field.point(lambda v: 255 if abs(math.sin(v * 0.07 + 2)) < 0.012 else 0)
        image.alpha_composite(tint(blur(gold, 1 * s), (200, 160, 70), 0.5))
        return image

    def draw_bezel(self, image, screen, s, portrait):
        shape, pad = self.bezel_shape(screen, s)
        x0, y0, x1, y1 = shape.box
        grow = pad * 0.28
        trim = poly(rounded_points([x0 - grow, y0 - grow, x1 + grow, y1 + grow], [pad * 0.7, pad * 0.7, pad * 2.7, pad * 0.7]))
        metal(image, trim, GOLD, lift=5 * s)
        super().draw_bezel(image, screen, s, portrait)

    def draw_wells(self, image, controls, s):
        x, y, w, h = controls["dpad"]
        metal(image, cross(box_of(x, y, w, h, 0.93), arm=0.4, radius=w * 0.07), GOLD, lift=3 * s)
        for name in ("a", "b"):
            x, y, w, h = controls[name]
            metal(image, ellipse(box_of(x, y, w, h, 1.04)), GOLD, lift=4 * s)

    def draw_pill(self, image, box, pressed, s):
        x, y, w, h = box
        metal(image, poly(capsule_points(x + w / 2, y + h / 2, w * 0.86, h * 0.66, 0)), GOLD, lift=3 * s)
        super().draw_pill(image, box, pressed, s)


STYLES = [Midnight, Arcade, Woodgrain, Space, Camo, Candy, Carbon, Ocean, Lava, Pixel, Marble]


def main(only=None):
    if not only:
        write_skin(Midnight(), EXAMPLE, "Midnight (example)", quantize=True, mask_pressed=True)
    for cls in STYLES:
        if only and cls.name.lower() not in only:
            continue
        style = cls()
        write_skin(style, os.path.join(BUNDLED, style.name.lower()), style.name, mask_pressed=True, extension="webp")


if __name__ == "__main__":
    import sys
    main(set(sys.argv[1:]) or None)
