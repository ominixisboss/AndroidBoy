package com.ominixisboss.androidboy;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Built-in skins, drawn in code: a minimal translucent overlay, plus themes styled after the
 * colours of the original handhelds. Everything scales with the view, in both orientations.
 */
final class ThemeSkin extends Skin {
    /** Colours for one theme. A theme with {@code minimal} set draws no body or bezel. */
    private static final class Palette {
        int body;
        int bodyShade;
        int bezel;
        int accent1;
        int accent2;
        int dpad;
        int buttons;
        int startSelect;
        int label;
        int led;
        boolean speaker;
        boolean roundedCorner;
        boolean minimal;
    }

    private static Palette palette(int body, int bezel, int dpad, int buttons, int startSelect, int label) {
        Palette p = new Palette();
        p.body = body;
        p.bodyShade = shade(body, 0.86f);
        p.bezel = bezel;
        p.dpad = dpad;
        p.buttons = buttons;
        p.startSelect = startSelect;
        p.label = label;
        p.led = 0xFFE3262B;
        p.speaker = true;
        return p;
    }

    private static final Palette MINIMAL = new Palette();
    static {
        MINIMAL.minimal = true;
        MINIMAL.body = Color.BLACK;
        MINIMAL.bodyShade = Color.BLACK;
    }

    private static Palette classic() {
        Palette p = palette(0xFFC9C5BF, 0xFF65636F, 0xFF29292C, 0xFFA3195B, 0xFF8D8C92, 0xFF2E3192);
        p.accent1 = 0xFF8A2247;
        p.accent2 = 0xFF2E3192;
        p.roundedCorner = true;
        return p;
    }

    private static Palette color(int body) {
        // Colour handhelds: dark bezel and buttons; labels readable on the body colour.
        return palette(body, 0xFF26262A, 0xFF2B2B2F, 0xFF303035, 0xFF4A4A50,
                luminance(body) > 0.45f ? 0xFF1E1E22 : 0xFFF2F2F2);
    }

    static final ThemeSkin[] ALL = {
            new ThemeSkin("minimal", "Minimal (translucent controls)", MINIMAL),
            new ThemeSkin("classic", "Classic grey", classic()),
            new ThemeSkin("pocket", "Pocket silver",
                    palette(0xFFC4C7CA, 0xFF2F3033, 0xFF2A2A2C, 0xFF3A3A3E, 0xFF6E7075, 0xFF26272A)),
            new ThemeSkin("light", "Light gold",
                    palette(0xFFD6B45C, 0xFF2B2A28, 0xFF2A2A2A, 0xFF3B3A38, 0xFF6D5B30, 0xFF3A2E12)),
            new ThemeSkin("berry", "Berry", color(0xFFD02A5C)),
            new ThemeSkin("grape", "Grape", color(0xFF5B3190)),
            new ThemeSkin("kiwi", "Kiwi", color(0xFF86BE2A)),
            new ThemeSkin("dandelion", "Dandelion", color(0xFFF2C21C)),
            new ThemeSkin("teal", "Teal", color(0xFF14A2B2)),
            new ThemeSkin("atomic", "Atomic purple", color(0xFF7B67B5)),
            new ThemeSkin("indigo", "Advance indigo",
                    palette(0xFF474C9E, 0xFF1F1F24, 0xFF2A2A2E, 0xFFD2D2DA, 0xFF2A2A2E, 0xFFE6E6F0)),
    };

    static ThemeSkin find(String id) {
        for (ThemeSkin skin : ALL) {
            if (skin.id.equals(id)) return skin;
        }
        return null;
    }

    static ThemeSkin fallback() {
        return ALL[0];
    }

    /** Geometry computed in {@link #layout}, used when drawing. */
    private static final class Geometry {
        final RectF bezel = new RectF();
        float pad;
        float labelSize;
        boolean portrait;
    }

    private final String id;
    private final String name;
    private final Palette palette;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private LinearGradient bodyGradient;
    private int gradientHeight;

    private ThemeSkin(String id, String name, Palette palette) {
        this.id = id;
        this.name = name;
        this.palette = palette;
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    String id() {
        return "theme:" + id;
    }

    @Override
    String name() {
        return name;
    }

    @Override
    int backgroundColor() {
        return palette.minimal ? Color.BLACK : palette.body;
    }

    // ---- Layout ----

    @Override
    void layout(Layout out, int width, int height, int frameWidth, int frameHeight,
                boolean controlsVisible, boolean integerScaling) {
        out.reset(width, height, controlsVisible);
        Geometry g = new Geometry();
        out.extras = g;
        g.portrait = height > width;
        float unit = Math.min(width, height);
        // Bezel thickness and the body margin around it; none for the minimal theme.
        g.pad = palette.minimal ? 0 : unit * 0.04f;
        float margin = palette.minimal ? 0 : unit * 0.045f;
        float topPad = g.pad * 1.6f;   // room for the accent stripes above the screen
        float bottomPad = g.pad * 1.8f;

        RectF area = new RectF();
        if (!controlsVisible) {
            area.set(margin + g.pad, margin + topPad, width - margin - g.pad, height - margin - bottomPad);
        } else if (g.portrait) {
            float frameAspect = (float) frameHeight / frameWidth;
            float screenWidth = width - 2 * (margin + g.pad);
            // Leave room for the controls, but never less than half the height for the game.
            float gameHeight = Math.max(height / 2f,
                    Math.min(height - width * 0.75f, screenWidth * frameAspect + 2 * margin + topPad + bottomPad));
            area.set(margin + g.pad, margin + topPad, width - margin - g.pad, gameHeight - margin - bottomPad);
        } else {
            area.set(margin + g.pad, margin + topPad, width - margin - g.pad, height - margin - bottomPad);
        }
        fitScreen(out.screen, area, frameWidth, frameHeight, integerScaling);
        g.bezel.set(out.screen.left - g.pad, out.screen.top - topPad,
                out.screen.right + g.pad, out.screen.bottom + bottomPad);
        if (palette.minimal) g.bezel.set(out.screen.left, out.screen.top, out.screen.right, out.screen.bottom);

        if (controlsVisible) {
            if (g.portrait) {
                layoutPortraitControls(out, g, width, height);
            } else {
                layoutLandscapeControls(out, g, width, height);
            }
        }
    }

    private void layoutPortraitControls(Layout out, Geometry g, int width, int height) {
        float density = density();
        float top = Math.max(g.bezel.bottom + g.pad, height / 2f);
        float areaHeight = height - top;
        float dpadRadius = Math.min(width * 0.2f, Math.min(areaHeight * 0.3f, 90 * density));
        float buttonRadius = dpadRadius * 0.42f;
        float centerY = top + areaHeight * 0.42f;
        float dpadX = width * 0.06f + dpadRadius;
        float aX = width - width * 0.06f - buttonRadius;
        float aY = centerY - buttonRadius * 0.6f;
        float bX = aX - buttonRadius * 2.4f;
        float bY = centerY + buttonRadius * 0.6f;
        float pillWidth = Math.min(width * 0.18f, 72 * density);
        float pillHeight = pillWidth * 0.36f;
        float pillY = Math.min(height - pillHeight * 2.2f, centerY + dpadRadius + pillHeight * 1.8f);
        float menuRadius = pillHeight * 0.9f;
        addControls(out, g, dpadX, centerY, dpadRadius, aX, aY, bX, bY, buttonRadius,
                width / 2f - pillWidth * 0.75f, width / 2f + pillWidth * 0.75f, pillY, pillY, pillWidth, pillHeight,
                width / 2f, top + menuRadius * 1.6f, menuRadius);
        // Rewind and fast-forward flank the menu button.
        addUtilityButtons(out, width / 2f - menuRadius * 2.6f, top + menuRadius * 1.6f,
                width / 2f + menuRadius * 2.6f, top + menuRadius * 1.6f, menuRadius);
    }

    private void layoutLandscapeControls(Layout out, Geometry g, int width, int height) {
        float density = density();
        // Controls sit either side of the screen, overlapping it on narrow displays.
        float side = Math.max(g.bezel.left, width * 0.18f);
        float dpadRadius = Math.min(side * 0.42f, Math.min(height * 0.2f, 80 * density));
        float buttonRadius = dpadRadius * 0.45f;
        float dpadX = Math.max(side / 2f, dpadRadius + 12 * density);
        float dpadY = height * 0.5f;
        float rightCenter = width - dpadX;
        float pillWidth = Math.min(side * 0.5f, 64 * density);
        float pillHeight = pillWidth * 0.36f;
        // Themed skins put labels under the buttons, so leave room for them.
        float pillY = height - pillHeight * (palette.minimal ? 2f : 2.8f);
        float menuRadius = pillHeight * 0.9f;
        addControls(out, g, dpadX, dpadY, dpadRadius,
                rightCenter + buttonRadius * 1.2f, height * 0.5f - buttonRadius * 0.6f,
                rightCenter - buttonRadius * 1.2f, height * 0.5f + buttonRadius * 0.6f, buttonRadius,
                dpadX, rightCenter, pillY, pillY, pillWidth, pillHeight,
                width - menuRadius * 1.6f, menuRadius * 1.6f, menuRadius);
        // Rewind in the top-left corner; fast-forward next to the menu in the top-right one.
        addUtilityButtons(out, menuRadius * 1.6f, menuRadius * 1.6f,
                width - menuRadius * 4.2f, menuRadius * 1.6f, menuRadius);
    }

    private static void addUtilityButtons(Layout out, float rewindX, float rewindY,
                                          float fastForwardX, float fastForwardY, float radius) {
        out.controls.add(new Control(KEY_REWIND, Control.CIRCLE, circle(rewindX, rewindY, radius), true));
        out.controls.add(new Control(KEY_FAST_FORWARD, Control.CIRCLE, circle(fastForwardX, fastForwardY, radius), true));
    }

    private void addControls(Layout out, Geometry g, float dpadX, float dpadY, float dpadRadius,
                             float aX, float aY, float bX, float bY, float buttonRadius,
                             float selectX, float startX, float selectY, float startY,
                             float pillWidth, float pillHeight,
                             float menuX, float menuY, float menuRadius) {
        out.controls.add(new Control(Emulator.KEY_UP | Emulator.KEY_DOWN | Emulator.KEY_LEFT | Emulator.KEY_RIGHT,
                Control.DPAD, circle(dpadX, dpadY, dpadRadius), true));
        out.controls.add(new Control(Emulator.KEY_A, Control.CIRCLE, circle(aX, aY, buttonRadius), true));
        out.controls.add(new Control(Emulator.KEY_B, Control.CIRCLE, circle(bX, bY, buttonRadius), true));
        // The gap between A and B presses both, like rolling a thumb across the real buttons.
        out.controls.add(new Control(Emulator.KEY_A | Emulator.KEY_B, Control.CIRCLE,
                circle((aX + bX) / 2, (aY + bY) / 2, buttonRadius * 0.5f), false));
        out.controls.add(new Control(Emulator.KEY_SELECT, Control.PILL, pill(selectX, selectY, pillWidth, pillHeight), true));
        out.controls.add(new Control(Emulator.KEY_START, Control.PILL, pill(startX, startY, pillWidth, pillHeight), true));
        out.controls.add(new Control(KEY_MENU, Control.CIRCLE, circle(menuX, menuY, menuRadius), true));
        g.labelSize = buttonRadius * 0.55f;
    }

    private static RectF circle(float x, float y, float radius) {
        return new RectF(x - radius, y - radius, x + radius, y + radius);
    }

    private static RectF pill(float x, float y, float width, float height) {
        return new RectF(x - width / 2, y - height / 2, x + width / 2, y + height / 2);
    }

    /** Display density, for capping control sizes in dp on large screens. */
    private static float density() {
        return android.content.res.Resources.getSystem().getDisplayMetrics().density;
    }

    // ---- Drawing ----

    @Override
    void drawBackground(Canvas canvas, Layout layout) {
        if (palette.minimal) {
            canvas.drawColor(Color.BLACK);
            return;
        }
        Geometry g = (Geometry) layout.extras;
        if (bodyGradient == null || gradientHeight != layout.height) {
            gradientHeight = layout.height;
            bodyGradient = new LinearGradient(0, 0, 0, layout.height, palette.body, palette.bodyShade,
                    Shader.TileMode.CLAMP);
        }
        fill.setShader(bodyGradient);
        canvas.drawRect(0, 0, layout.width, layout.height, fill);
        fill.setShader(null);

        drawBezel(canvas, g, layout.screen);
        if (palette.speaker && g.portrait && layout.controlsVisible) drawSpeaker(canvas, layout, g);
    }

    private void drawBezel(Canvas canvas, Geometry g, Rect screen) {
        float radius = g.pad * 0.8f;
        // The classic model's bezel has one big rounded corner at the bottom right.
        float big = palette.roundedCorner ? g.pad * 4f : radius;
        float[] radii = {radius, radius, radius, radius, big, big, radius, radius};
        path.reset();
        path.addRoundRect(g.bezel, radii, Path.Direction.CW);
        fill.setColor(palette.bezel);
        canvas.drawPath(path, fill);

        // Thin black frame just around the screen, like the real LCD's border.
        fill.setColor(0xFF101012);
        float edge = Math.max(1f, g.pad * 0.12f);
        canvas.drawRect(screen.left - edge, screen.top - edge, screen.right + edge, screen.bottom + edge, fill);

        if (palette.accent1 != 0) {
            float y1 = g.bezel.top + g.pad * 0.55f;
            float y2 = y1 + g.pad * 0.3f;
            stroke.setStrokeWidth(g.pad * 0.14f);
            stroke.setColor(palette.accent1);
            canvas.drawLine(g.bezel.left + g.pad * 0.6f, y1, g.bezel.right - g.pad * 0.6f, y1, stroke);
            stroke.setColor(palette.accent2);
            canvas.drawLine(g.bezel.left + g.pad * 0.6f, y2, g.bezel.right - g.pad * 0.6f, y2, stroke);
        }

        if (palette.led != 0 && g.pad > 0) {
            float ledX = g.bezel.left + g.pad * 0.5f;
            float ledY = screen.top + screen.height() * 0.33f;
            fill.setColor(palette.led);
            canvas.drawCircle(ledX, ledY, g.pad * 0.2f, fill);
        }
    }

    private void drawSpeaker(Canvas canvas, Layout layout, Geometry g) {
        // Six diagonal slots in the bottom-right corner, if they fit below the controls.
        float lowest = 0;
        for (Control control : layout.controls) {
            if (control.visible && control.bounds.right > layout.width * 0.55f) {
                lowest = Math.max(lowest, control.bounds.bottom);
            }
        }
        float slot = layout.width * 0.11f;
        float centerX = layout.width * 0.8f;
        float centerY = layout.height - slot * 0.9f;
        if (centerY - slot * 0.6f < lowest) return;
        stroke.setColor(shade(palette.body, 0.62f));
        stroke.setStrokeWidth(layout.width * 0.012f);
        canvas.save();
        canvas.rotate(-30, centerX, centerY);
        for (int i = -3; i < 3; i++) {
            float x = centerX + (i + 0.5f) * layout.width * 0.028f;
            canvas.drawLine(x, centerY - slot / 2, x, centerY + slot / 2, stroke);
        }
        canvas.restore();
    }

    @Override
    void drawControls(Canvas canvas, Layout layout, int pressed) {
        Geometry g = (Geometry) layout.extras;
        for (Control control : layout.controls) {
            if (!control.visible) continue;
            boolean down = (pressed & control.keys) != 0;
            switch (control.shape) {
                case Control.DPAD:
                    drawDpad(canvas, control.bounds, pressed);
                    break;
                case Control.PILL:
                    drawPill(canvas, g, control, down);
                    break;
                default:
                    if (control.keys == KEY_MENU || control.keys == KEY_REWIND || control.keys == KEY_FAST_FORWARD) {
                        drawUtility(canvas, control.bounds, control.keys, down);
                    } else {
                        drawButton(canvas, g, control, down);
                    }
                    break;
            }
        }
    }

    private void drawDpad(Canvas canvas, RectF bounds, int pressed) {
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        float radius = bounds.width() / 2;
        float arm = radius / 3f;
        float corner = arm * 0.3f;
        if (palette.minimal) {
            fill.setColor(Color.argb(90, 255, 255, 255));
            drawCross(canvas, cx, cy, radius, arm, corner);
            fill.setColor(Color.argb(170, 255, 255, 255));
        } else {
            fill.setColor(shade(palette.body, 0.7f));
            drawCross(canvas, cx, cy + radius * 0.05f, radius, arm, corner);
            fill.setColor(palette.dpad);
            drawCross(canvas, cx, cy, radius, arm, corner);
            fill.setColor(lighten(palette.dpad, 0.1f));
            canvas.drawCircle(cx, cy, arm * 0.55f, fill);
            fill.setColor(Color.argb(90, 0, 0, 0));
        }
        corner = radius / 10f;
        if ((pressed & Emulator.KEY_UP) != 0) drawArm(canvas, cx - arm, cy - radius, cx + arm, cy - arm, corner);
        if ((pressed & Emulator.KEY_DOWN) != 0) drawArm(canvas, cx - arm, cy + arm, cx + arm, cy + radius, corner);
        if ((pressed & Emulator.KEY_LEFT) != 0) drawArm(canvas, cx - radius, cy - arm, cx - arm, cy + arm, corner);
        if ((pressed & Emulator.KEY_RIGHT) != 0) drawArm(canvas, cx + arm, cy - arm, cx + radius, cy + arm, corner);
    }

    private void drawCross(Canvas canvas, float cx, float cy, float radius, float arm, float corner) {
        rect.set(cx - arm, cy - radius, cx + arm, cy + radius);
        canvas.drawRoundRect(rect, corner, corner, fill);
        rect.set(cx - radius, cy - arm, cx + radius, cy + arm);
        canvas.drawRoundRect(rect, corner, corner, fill);
    }

    private void drawArm(Canvas canvas, float l, float t, float r, float b, float corner) {
        rect.set(l, t, r, b);
        canvas.drawRoundRect(rect, corner, corner, fill);
    }

    private void drawButton(Canvas canvas, Geometry g, Control control, boolean down) {
        RectF b = control.bounds;
        float radius = b.width() / 2;
        String label = control.keys == Emulator.KEY_A ? "A" : "B";
        if (palette.minimal) {
            fill.setColor(down ? Color.argb(170, 255, 255, 255) : Color.argb(90, 255, 255, 255));
            canvas.drawCircle(b.centerX(), b.centerY(), radius, fill);
            text.setColor(Color.argb(200, 0, 0, 0));
            text.setTextSize(radius * 0.7f);
            drawCentered(canvas, label, b.centerX(), b.centerY());
            return;
        }
        fill.setColor(shade(palette.body, 0.7f));
        canvas.drawCircle(b.centerX(), b.centerY() + radius * 0.1f, radius, fill);
        fill.setColor(down ? shade(palette.buttons, 0.75f) : palette.buttons);
        canvas.drawCircle(b.centerX(), b.centerY() + (down ? radius * 0.06f : 0), radius, fill);
        text.setColor(palette.label);
        text.setTextSize(g.labelSize);
        drawCentered(canvas, label, b.centerX() + radius * 0.35f, b.bottom + g.labelSize * 1.1f);
    }

    private void drawPill(Canvas canvas, Geometry g, Control control, boolean down) {
        RectF b = control.bounds;
        float half = b.height() / 2;
        String label = control.keys == Emulator.KEY_START ? "START" : "SELECT";
        if (palette.minimal) {
            fill.setColor(down ? Color.argb(170, 255, 255, 255) : Color.argb(90, 255, 255, 255));
            canvas.drawRoundRect(b, half, half, fill);
            text.setColor(Color.argb(200, 0, 0, 0));
            text.setTextSize(b.height() * 0.45f);
            drawCentered(canvas, label, b.centerX(), b.centerY());
            return;
        }
        rect.set(b.left, b.top + half * 0.3f, b.right, b.bottom + half * 0.3f);
        fill.setColor(shade(palette.body, 0.7f));
        canvas.drawRoundRect(rect, half, half, fill);
        fill.setColor(down ? shade(palette.startSelect, 0.75f) : palette.startSelect);
        canvas.drawRoundRect(b, half, half, fill);
        text.setColor(palette.label);
        text.setTextSize(Math.min(g.labelSize * 0.7f, b.height() * 0.6f));
        drawCentered(canvas, label, b.centerX(), b.bottom + text.getTextSize() * 1.2f);
    }

    /** The small round buttons: menu (☰), rewind (◀◀) and fast-forward (▶▶). */
    private void drawUtility(Canvas canvas, RectF b, int keys, boolean down) {
        float radius = b.width() / 2;
        int background;
        int lines;
        if (palette.minimal) {
            background = down ? Color.argb(170, 255, 255, 255) : Color.argb(90, 255, 255, 255);
            lines = Color.argb(200, 0, 0, 0);
        } else {
            background = shade(palette.body, down ? 0.65f : 0.8f);
            lines = palette.label;
        }
        fill.setColor(background);
        canvas.drawCircle(b.centerX(), b.centerY(), radius, fill);
        if (keys != KEY_MENU) {
            // Two small triangles pointing back (rewind) or forward (fast-forward).
            float direction = keys == KEY_REWIND ? -1 : 1;
            float h = radius * 0.32f;
            float w = radius * 0.3f;
            fill.setColor(lines);
            for (int i = 0; i < 2; i++) {
                float start = b.centerX() + direction * (i * w - w);
                path.reset();
                path.moveTo(start, b.centerY() - h);
                path.lineTo(start + direction * w, b.centerY());
                path.lineTo(start, b.centerY() + h);
                path.close();
                canvas.drawPath(path, fill);
            }
            return;
        }
        stroke.setColor(lines);
        stroke.setStrokeWidth(radius * 0.12f);
        float half = radius * 0.45f;
        float gap = radius * 0.3f;
        for (int i = -1; i <= 1; i++) {
            canvas.drawLine(b.centerX() - half, b.centerY() + i * gap, b.centerX() + half, b.centerY() + i * gap, stroke);
        }
    }

    private void drawCentered(Canvas canvas, String label, float x, float y) {
        canvas.drawText(label, x, y - (text.ascent() + text.descent()) / 2, text);
    }

    // ---- Colour helpers ----

    static int shade(int color, float factor) {
        return Color.argb(Color.alpha(color), (int) (Color.red(color) * factor),
                (int) (Color.green(color) * factor), (int) (Color.blue(color) * factor));
    }

    private static int lighten(int color, float amount) {
        return Color.argb(Color.alpha(color),
                (int) (Color.red(color) + (255 - Color.red(color)) * amount),
                (int) (Color.green(color) + (255 - Color.green(color)) * amount),
                (int) (Color.blue(color) + (255 - Color.blue(color)) * amount));
    }

    private static float luminance(int color) {
        return (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f;
    }
}
