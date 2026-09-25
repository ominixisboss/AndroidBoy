package com.ominixisboss.androidboy;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Built-in skins, drawn in code: a minimal translucent overlay, plus themes styled after the
 * colours of the original handhelds. Everything scales with the view, in both orientations.
 */
final class ThemeSkin extends Skin {
    // How the controls are drawn.
    /** Raised plastic and rubber, shaded like the real handhelds' buttons. */
    private static final int SOLID = 0;
    /** Flat, see-through shapes; labels inside. */
    private static final int TRANSLUCENT = 1;
    /** Thin outlines that fill in when pressed. */
    private static final int OUTLINE = 2;
    /** Glowing outlines. */
    private static final int NEON = 3;

    /** Colours for one theme. A theme with {@code minimal} set draws no body or bezel. */
    private static final class Palette {
        int body;
        int bodyShade;
        int bezel;
        /** Outline around the bezel, or 0 for none. */
        int bezelStroke;
        int accent1;
        int accent2;
        int dpad;
        int buttons;
        int startSelect;
        int label;
        int led;
        /** Edge of translucent controls, or 0 for none. */
        int rim;
        int style = SOLID;
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
        MINIMAL.style = TRANSLUCENT;
        MINIMAL.body = Color.BLACK;
        MINIMAL.bodyShade = Color.BLACK;
        MINIMAL.dpad = MINIMAL.buttons = MINIMAL.startSelect = Color.argb(90, 255, 255, 255);
        MINIMAL.label = Color.argb(200, 0, 0, 0);
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

    private static Palette superGrey() {
        // Styled after the Super Game Boy's grey plastic and lilac buttons.
        Palette p = palette(0xFFA7A7AE, 0xFF4A4A52, 0xFF2E2E33, 0xFF6F5FA6, 0xFF5B5B63, 0xFF3A3552);
        p.accent1 = 0xFF6F5FA6;
        p.accent2 = 0xFFB0344F;
        return p;
    }

    private static Palette redAndWhite() {
        Palette p = palette(0xFFEAE4D6, 0xFF7C1F27, 0xFF2A2A2D, 0xFFC4202F, 0xFF9C9486, 0xFF7C1F27);
        p.accent1 = 0xFFC4202F;
        p.accent2 = 0xFFD9A441;
        return p;
    }

    private static Palette gold() {
        Palette p = palette(0xFFE4C160, 0xFF2B2A28, 0xFF2B2A28, 0xFF3C3A36, 0xFF7A6634, 0xFF3A2E12);
        p.bodyShade = 0xFF9E7B24;
        p.roundedCorner = true;
        return p;
    }

    private static Palette oled() {
        Palette p = palette(Color.BLACK, 0xFF080808, 0xFFE6E6E6, 0xFFFF5A5F, 0xFFB0B0B0, 0xFFE6E6E6);
        p.bodyShade = Color.BLACK;
        p.bezelStroke = 0xFF3A3A3A;
        p.style = OUTLINE;
        p.led = 0;
        p.speaker = false;
        return p;
    }

    private static Palette neon() {
        Palette p = palette(0xFF15122E, 0xFF0B0A1A, 0xFF22E6FF, 0xFFFF3CD2, 0xFF8BFF5A, 0xFF22E6FF);
        p.bodyShade = 0xFF06050F;
        p.bezelStroke = 0xFF22E6FF;
        p.style = NEON;
        p.led = 0xFFFF3CD2;
        p.speaker = false;
        return p;
    }

    private static Palette pastel() {
        Palette p = palette(0xFFF8DAE7, 0xFF8E86B6, 0xFF8C84BA, 0xFF86D9BD, 0xFFB6AFD9, 0xFF6A6395);
        p.bodyShade = 0xFFD5D0F5;
        p.led = 0xFFFF8FB1;
        return p;
    }

    private static Palette glass() {
        Palette p = palette(0xFF2A4A86, 0x40FFFFFF, 0x50FFFFFF, 0x50FFFFFF, 0x50FFFFFF, 0xFFFFFFFF);
        p.bodyShade = 0xFF6C3D8F;
        p.bezelStroke = 0x90FFFFFF;
        p.rim = 0xA0FFFFFF;
        p.style = TRANSLUCENT;
        p.led = 0xFF7CF3FF;
        p.speaker = false;
        return p;
    }

    static final ThemeSkin[] ALL = {
            new ThemeSkin("minimal", "Minimal (translucent controls)", MINIMAL),
            new ThemeSkin("classic", "Classic grey", classic()),
            new ThemeSkin("pocket", "Pocket silver",
                    palette(0xFFC4C7CA, 0xFF2F3033, 0xFF2A2A2C, 0xFF3A3A3E, 0xFF6E7075, 0xFF26272A)),
            new ThemeSkin("pocket-black", "Pocket black",
                    palette(0xFF2C2C31, 0xFF151517, 0xFF1B1B1E, 0xFF45454C, 0xFF55555C, 0xFFD8D8DE)),
            new ThemeSkin("pocket-red", "Pocket red", color(0xFFC62A3A)),
            new ThemeSkin("pocket-pink", "Pocket pink", color(0xFFEA93B8)),
            new ThemeSkin("light", "Light gold",
                    palette(0xFFD6B45C, 0xFF2B2A28, 0xFF2A2A2A, 0xFF3B3A38, 0xFF6D5B30, 0xFF3A2E12)),
            new ThemeSkin("gold", "Gold edition", gold()),
            new ThemeSkin("sgb", "Super grey", superGrey()),
            new ThemeSkin("red-white", "Red & white", redAndWhite()),
            new ThemeSkin("berry", "Berry", color(0xFFD02A5C)),
            new ThemeSkin("grape", "Grape", color(0xFF5B3190)),
            new ThemeSkin("kiwi", "Kiwi", color(0xFF86BE2A)),
            new ThemeSkin("dandelion", "Dandelion", color(0xFFF2C21C)),
            new ThemeSkin("teal", "Teal", color(0xFF14A2B2)),
            new ThemeSkin("ice", "Ice blue", color(0xFF9CCFE8)),
            new ThemeSkin("atomic", "Atomic purple", color(0xFF7B67B5)),
            new ThemeSkin("indigo", "Advance indigo",
                    palette(0xFF474C9E, 0xFF1F1F24, 0xFF2A2A2E, 0xFFD2D2DA, 0xFF2A2A2E, 0xFFE6E6F0)),
            new ThemeSkin("oled", "OLED black (outlines)", oled()),
            new ThemeSkin("neon", "Neon", neon()),
            new ThemeSkin("pastel", "Pastel", pastel()),
            new ThemeSkin("glass", "Frosted glass", glass()),
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
        if (palette.bezelStroke != 0) {
            float width = Math.max(1.5f, g.pad * 0.07f);
            if (palette.style == NEON) {
                stroke.setStrokeWidth(width * 5);
                stroke.setColor((palette.bezelStroke & 0xFFFFFF) | 0x24000000);
                canvas.drawPath(path, stroke);
                stroke.setStrokeWidth(width * 2.4f);
                stroke.setColor((palette.bezelStroke & 0xFFFFFF) | 0x60000000);
                canvas.drawPath(path, stroke);
            }
            stroke.setStrokeWidth(width);
            stroke.setColor(palette.bezelStroke);
            canvas.drawPath(path, stroke);
        }

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

    // Controls are drawn in "unit" coordinates: the canvas is moved to the control's centre and
    // scaled so its radius (half the width, for pills) is 1. The shaders below are made once in
    // that space, so they fit every control size.

    /** Start and Select are slanted like the original's; their length shrinks to keep the rotated pill in bounds. */
    private static final float PILL_ANGLE = -25f;
    private static final float PILL_LENGTH = 0.86f;
    /** The d-pad rocks up to this far towards the held direction. */
    private static final float SOLID_TILT_DEGREES = 13f;
    private static final float FLAT_TILT_DEGREES = 8f;
    private static final float[] SQUARE = {-1, -1, 1, -1, 1, 1, -1, 1};
    private static final float ARM = 1f / 3;

    private final Matrix tiltMatrix = new Matrix();
    private final float[] tilted = new float[8];
    private Shader wellShader;
    private Shader buttonShader;
    private Shader dpadShader;
    private Shader pillShader;
    private Shader utilityShader;
    private Shader dimpleShader;

    private void makeShaders() {
        if (wellShader != null || palette.style != SOLID) return;
        // Recess in the body around each control: darker at the top, where the lip casts a shadow.
        wellShader = new LinearGradient(0, -1.1f, 0, 1.1f, shade(palette.body, 0.58f), shade(palette.body, 0.9f),
                Shader.TileMode.CLAMP);
        // Convex buttons, lit from the top left.
        buttonShader = new RadialGradient(-0.32f, -0.38f, 1.5f,
                new int[] {lighten(palette.buttons, 0.3f), palette.buttons, shade(palette.buttons, 0.68f)},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
        dpadShader = new LinearGradient(-1, -1, 1, 1, lighten(palette.dpad, 0.16f), shade(palette.dpad, 0.78f),
                Shader.TileMode.CLAMP);
        pillShader = new LinearGradient(0, -0.4f, 0, 0.4f, lighten(palette.startSelect, 0.22f),
                shade(palette.startSelect, 0.75f), Shader.TileMode.CLAMP);
        int utility = shade(palette.body, 0.8f);
        utilityShader = new RadialGradient(-0.3f, -0.35f, 1.5f,
                new int[] {lighten(utility, 0.18f), utility, shade(utility, 0.75f)},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
        dimpleShader = new RadialGradient(0.04f, 0.06f, 0.2f, shade(palette.dpad, 0.62f), lighten(palette.dpad, 0.08f),
                Shader.TileMode.CLAMP);
    }

    @Override
    void drawControls(Canvas canvas, Layout layout, int pressed, Motion motion) {
        Geometry g = (Geometry) layout.extras;
        makeShaders();
        for (int i = 0; i < layout.controls.size(); i++) {
            Control control = layout.controls.get(i);
            if (!control.visible) continue;
            float press = motion.press(i);
            switch (control.shape) {
                case Control.DPAD:
                    drawDpad(canvas, control.bounds, motion.tiltX, motion.tiltY);
                    break;
                case Control.PILL:
                    drawPill(canvas, g, control, press);
                    break;
                default:
                    if (control.keys == KEY_MENU || control.keys == KEY_REWIND || control.keys == KEY_FAST_FORWARD) {
                        drawUtility(canvas, control.bounds, control.keys, press);
                    } else {
                        drawButton(canvas, g, control, press);
                    }
                    break;
            }
        }
    }

    /** Moves the canvas to a control's centre, scaled so its radius is 1. */
    private static void enterUnit(Canvas canvas, float cx, float cy, float radius) {
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(radius, radius);
    }

    // ---- D-pad ----

    private void drawDpad(Canvas canvas, RectF bounds, float tiltX, float tiltY) {
        float radius = bounds.width() / 2;
        enterUnit(canvas, bounds.centerX(), bounds.centerY(), radius);
        if (palette.style == SOLID) {
            drawSolidDpad(canvas, tiltX, tiltY);
        } else {
            drawFlatDpad(canvas, radius, tiltX, tiltY);
        }
        canvas.restore();
    }

    private void drawSolidDpad(Canvas canvas, float tiltX, float tiltY) {
        // The recess the cross sits in, then its shadow, which leans the way the cross rocks.
        fill.setShader(wellShader);
        drawCross(canvas, 0, 0.03f, 1.07f, ARM + 0.06f, 0.14f);
        fill.setShader(null);
        fill.setColor(0x66000000);
        canvas.save();
        canvas.translate(clamp(tiltX) * 0.03f, 0.07f + clamp(tiltY) * 0.02f);
        drawUnitShape(canvas, CROSS_SHAPE, fill);
        canvas.restore();

        canvas.save();
        applyTilt(canvas, tiltX, tiltY, SOLID_TILT_DEGREES);
        fill.setShader(dpadShader);
        drawCross(canvas, 0, 0, 1f, ARM, 0.1f);
        fill.setShader(null);
        // The held arm dips into shadow; the opposite one rises into the light.
        shadeArms(canvas, tiltX, tiltY, 0x000000, 90, 0xFFFFFF, 45);
        // Arrows moulded into the arms (a light edge below a dark recess), and the dimple in the middle.
        for (int direction = 0; direction < 4; direction++) {
            fill.setColor(lighten(palette.dpad, 0.22f));
            drawArrow(canvas, direction, 0.72f, 0.13f, 0.022f);
            fill.setColor(shade(palette.dpad, 0.45f));
            drawArrow(canvas, direction, 0.72f, 0.13f, 0);
        }
        fill.setShader(dimpleShader);
        canvas.drawCircle(0, 0, 0.19f, fill);
        fill.setShader(null);
        stroke.setColor(shade(palette.dpad, 0.5f));
        stroke.setStrokeWidth(0.025f);
        drawUnitShape(canvas, CROSS_SHAPE, stroke);
        canvas.restore();
    }

    private void drawFlatDpad(Canvas canvas, float radius, float tiltX, float tiltY) {
        canvas.save();
        applyTilt(canvas, tiltX, tiltY, FLAT_TILT_DEGREES);
        int color = palette.dpad;
        if (palette.style == TRANSLUCENT) {
            fill.setColor(color);
            // One shape, so the see-through middle isn't covered twice.
            drawUnitShape(canvas, CROSS_SHAPE, fill);
            shadeArms(canvas, tiltX, tiltY, 0xFFFFFF, Math.min(255, Color.alpha(color)), 0, 0);
            if (palette.rim != 0) {
                stroke.setColor(palette.rim);
                stroke.setStrokeWidth(lineWidth(radius));
                drawUnitShape(canvas, CROSS_SHAPE, stroke);
            }
            fill.setColor(palette.label);
        } else {
            shadeArms(canvas, tiltX, tiltY, color & 0xFFFFFF, palette.style == NEON ? 110 : 150, 0, 0);
            drawOutline(canvas, radius, color, 1f, CROSS_SHAPE);
            fill.setColor(palette.style == NEON ? lighten(color, 0.45f) : color);
        }
        for (int direction = 0; direction < 4; direction++) drawArrow(canvas, direction, 0.7f, 0.11f, 0);
        canvas.restore();
    }

    /**
     * Rocks the canvas in perspective: the held side of the d-pad moves away from the viewer and the
     * opposite side comes closer. Tilts are -1 to 1 on each axis; the springs overshoot slightly.
     */
    private void applyTilt(Canvas canvas, float tiltX, float tiltY, float maxDegrees) {
        if (tiltX == 0 && tiltY == 0) return;
        double angleX = Math.toRadians(clamp(tiltX) * maxDegrees);
        double angleY = Math.toRadians(clamp(tiltY) * maxDegrees);
        float distance = 4f; // viewer distance, in d-pad radii
        for (int i = 0; i < 8; i += 2) {
            float x = SQUARE[i];
            float y = SQUARE[i + 1];
            float depth = (float) (x * Math.sin(angleX) + y * Math.sin(angleY));
            float scale = distance / (distance + depth);
            tilted[i] = (float) (x * Math.cos(angleX)) * scale;
            tilted[i + 1] = (float) (y * Math.cos(angleY)) * scale;
        }
        tiltMatrix.setPolyToPoly(SQUARE, 0, tilted, 0, 4);
        canvas.concat(tiltMatrix);
    }

    /** Tints each arm by how far the pad tilts towards it (held) or away from it (raised). */
    private void shadeArms(Canvas canvas, float tiltX, float tiltY, int heldRgb, int heldAlpha, int raisedRgb, int raisedAlpha) {
        float[] amounts = {Math.max(0, -tiltY), Math.max(0, tiltY), Math.max(0, -tiltX), Math.max(0, tiltX)};
        for (int direction = 0; direction < 4; direction++) {
            float held = Math.min(1, amounts[direction]);
            float raised = Math.min(1, amounts[direction ^ 1]);
            if (held > 0.01f && heldAlpha > 0) {
                fill.setColor(((int) (heldAlpha * held) << 24) | heldRgb);
                drawArmRect(canvas, direction);
            }
            if (raised > 0.01f && raisedAlpha > 0) {
                fill.setColor(((int) (raisedAlpha * raised) << 24) | raisedRgb);
                drawArmRect(canvas, direction);
            }
        }
    }

    /** Directions: 0 up, 1 down, 2 left, 3 right. */
    private void drawArmRect(Canvas canvas, int direction) {
        switch (direction) {
            case 0: rect.set(-ARM, -1, ARM, -ARM); break;
            case 1: rect.set(-ARM, ARM, ARM, 1); break;
            case 2: rect.set(-1, -ARM, -ARM, ARM); break;
            default: rect.set(ARM, -ARM, 1, ARM); break;
        }
        canvas.drawRoundRect(rect, 0.1f, 0.1f, fill);
    }

    private void drawArrow(Canvas canvas, int direction, float distance, float size, float offsetY) {
        canvas.save();
        canvas.translate(0, offsetY);
        canvas.rotate(direction == 0 ? 0 : direction == 1 ? 180 : direction == 2 ? 270 : 90);
        path.reset();
        path.moveTo(0, -distance - size * 0.6f);
        path.lineTo(size * 0.8f, -distance + size * 0.5f);
        path.lineTo(-size * 0.8f, -distance + size * 0.5f);
        path.close();
        canvas.drawPath(path, fill);
        canvas.restore();
    }

    private void drawCross(Canvas canvas, float cx, float cy, float radius, float arm, float corner) {
        rect.set(cx - arm, cy - radius, cx + arm, cy + radius);
        canvas.drawRoundRect(rect, corner, corner, fill);
        rect.set(cx - radius, cy - arm, cx + radius, cy + arm);
        canvas.drawRoundRect(rect, corner, corner, fill);
    }

    // ---- A and B ----

    private void drawButton(Canvas canvas, Geometry g, Control control, float press) {
        RectF b = control.bounds;
        float radius = b.width() / 2;
        String label = control.keys == Emulator.KEY_A ? "A" : "B";
        float down = clamp01(press);
        if (palette.style != SOLID) {
            float scale = 1 - 0.08f * press;
            enterUnit(canvas, b.centerX(), b.centerY(), radius * scale);
            drawFlatShape(canvas, radius * scale, palette.buttons, down, CIRCLE_SHAPE);
            canvas.restore();
            text.setColor(palette.style == TRANSLUCENT ? palette.label : flatInk(palette.buttons, down));
            text.setTextSize(radius * 0.7f * scale);
            drawCentered(canvas, label, b.centerX(), b.centerY());
            return;
        }
        enterUnit(canvas, b.centerX(), b.centerY(), radius);
        fill.setShader(wellShader);
        canvas.drawCircle(0, 0.04f, 1.1f, fill);
        fill.setShader(null);
        // The drop shadow shortens as the button sinks.
        fill.setColor(0x60000000);
        canvas.drawCircle(0, 0.11f * (1 - clamp(press)), 1f, fill);
        canvas.translate(0, 0.08f * press);
        float scale = 1 - 0.04f * press;
        canvas.scale(scale, scale);
        fill.setShader(buttonShader);
        canvas.drawCircle(0, 0, 1f, fill);
        fill.setShader(null);
        if (down > 0) {
            fill.setColor(Color.argb((int) (70 * down), 0, 0, 0));
            canvas.drawCircle(0, 0, 1f, fill);
        }
        stroke.setColor(shade(palette.buttons, 0.55f));
        stroke.setStrokeWidth(0.05f);
        canvas.drawCircle(0, 0, 0.975f, stroke);
        // Glint on the curve, dimming as the button goes down.
        fill.setColor(Color.argb((int) (80 * (1 - 0.6f * down)), 255, 255, 255));
        rect.set(-0.62f, -0.72f, -0.08f, -0.36f);
        canvas.save();
        canvas.rotate(-28, -0.35f, -0.54f);
        canvas.drawOval(rect, fill);
        canvas.restore();
        canvas.restore();
        text.setColor(palette.label);
        text.setTextSize(g.labelSize);
        drawCentered(canvas, label, b.centerX() + radius * 0.35f, b.bottom + g.labelSize * 1.1f);
    }

    // ---- Start and Select ----

    private void drawPill(Canvas canvas, Geometry g, Control control, float press) {
        RectF b = control.bounds;
        float halfWidth = b.width() / 2;
        String label = control.keys == Emulator.KEY_START ? "START" : "SELECT";
        float down = clamp01(press);
        pillHalfHeight = b.height() / b.width();
        if (palette.style != SOLID) {
            float scale = 1 - 0.08f * press;
            pillHalfLength = 1;
            enterUnit(canvas, b.centerX(), b.centerY(), halfWidth * scale);
            drawFlatShape(canvas, halfWidth * scale, palette.startSelect, down, PILL_SHAPE);
            canvas.restore();
            text.setColor(palette.style == TRANSLUCENT ? palette.label : flatInk(palette.startSelect, down));
            text.setTextSize(b.height() * 0.45f * scale);
            drawCentered(canvas, label, b.centerX(), b.centerY());
            return;
        }
        pillHalfLength = PILL_LENGTH;
        float h = pillHalfHeight;
        canvas.save();
        canvas.rotate(PILL_ANGLE, b.centerX(), b.centerY());
        enterUnit(canvas, b.centerX(), b.centerY(), halfWidth);
        fill.setShader(wellShader);
        rect.set(-PILL_LENGTH - 0.07f, -h - 0.07f, PILL_LENGTH + 0.07f, h + 0.07f);
        canvas.drawRoundRect(rect, h + 0.07f, h + 0.07f, fill);
        fill.setShader(null);
        fill.setColor(0x60000000);
        rect.set(-PILL_LENGTH, -h, PILL_LENGTH, h);
        rect.offset(0, 0.1f * (1 - clamp(press)));
        canvas.drawRoundRect(rect, h, h, fill);
        canvas.translate(0, 0.07f * press);
        fill.setShader(pillShader);
        rect.set(-PILL_LENGTH, -h, PILL_LENGTH, h);
        canvas.drawRoundRect(rect, h, h, fill);
        fill.setShader(null);
        if (down > 0) {
            fill.setColor(Color.argb((int) (70 * down), 0, 0, 0));
            canvas.drawRoundRect(rect, h, h, fill);
        }
        canvas.restore();
        text.setColor(palette.label);
        text.setTextSize(Math.min(g.labelSize * 0.7f, b.height() * 0.6f));
        drawCentered(canvas, label, b.centerX(), b.bottom + text.getTextSize() * 1.2f);
        canvas.restore();
    }

    // ---- Menu, rewind and fast-forward ----

    /** The small round buttons: menu (☰), rewind (◀◀) and fast-forward (▶▶). */
    private void drawUtility(Canvas canvas, RectF b, int keys, float press) {
        float radius = b.width() / 2;
        float down = clamp01(press);
        int ink;
        if (palette.style == SOLID) {
            enterUnit(canvas, b.centerX(), b.centerY(), radius);
            fill.setColor(0x50000000);
            canvas.drawCircle(0, 0.1f * (1 - clamp(press)), 1f, fill);
            canvas.translate(0, 0.07f * press);
            fill.setShader(utilityShader);
            canvas.drawCircle(0, 0, 1f, fill);
            fill.setShader(null);
            if (down > 0) {
                fill.setColor(Color.argb((int) (60 * down), 0, 0, 0));
                canvas.drawCircle(0, 0, 1f, fill);
            }
            ink = palette.label;
        } else {
            float scale = 1 - 0.08f * press;
            enterUnit(canvas, b.centerX(), b.centerY(), radius * scale);
            int color = palette.startSelect;
            drawFlatShape(canvas, radius * scale, color, down, CIRCLE_SHAPE);
            ink = palette.style == TRANSLUCENT ? palette.label : flatInk(color, down);
        }
        drawGlyph(canvas, keys, ink);
        canvas.restore();
    }

    /** ☰ or two triangles pointing back (rewind) or forward (fast-forward), in unit coordinates. */
    private void drawGlyph(Canvas canvas, int keys, int color) {
        if (keys != KEY_MENU) {
            float direction = keys == KEY_REWIND ? -1 : 1;
            float h = 0.32f;
            float w = 0.3f;
            fill.setColor(color);
            for (int i = 0; i < 2; i++) {
                float start = direction * (i * w - w);
                path.reset();
                path.moveTo(start, -h);
                path.lineTo(start + direction * w, 0);
                path.lineTo(start, h);
                path.close();
                canvas.drawPath(path, fill);
            }
            return;
        }
        stroke.setColor(color);
        stroke.setStrokeWidth(0.12f);
        for (int i = -1; i <= 1; i++) {
            canvas.drawLine(-0.45f, i * 0.3f, 0.45f, i * 0.3f, stroke);
        }
    }

    // ---- Flat styles: translucent, outline and neon ----

    private static final int CIRCLE_SHAPE = 0;
    private static final int PILL_SHAPE = 1;
    private static final int CROSS_SHAPE = 2;
    private final Path crossPath = new Path();
    private float pillHalfLength = 1;
    private float pillHalfHeight = 0.36f;

    /** Draws a circle or pill in unit coordinates, in the palette's flat style; {@code down} is 0 to 1. */
    private void drawFlatShape(Canvas canvas, float radius, int color, float down, int shape) {
        if (palette.style == TRANSLUCENT) {
            int alpha = Color.alpha(color);
            alpha += (int) ((Math.min(255, alpha * 1.9f) - alpha) * down);
            fill.setColor((color & 0xFFFFFF) | (alpha << 24));
            drawUnitShape(canvas, shape, fill);
            if (palette.rim != 0) {
                stroke.setColor(palette.rim);
                stroke.setStrokeWidth(lineWidth(radius));
                drawUnitShape(canvas, shape, stroke);
            }
            return;
        }
        if (down > 0) {
            fill.setColor(((int) ((palette.style == NEON ? 110 : 230) * down) << 24) | (color & 0xFFFFFF));
            drawUnitShape(canvas, shape, fill);
        }
        drawOutline(canvas, radius, color, 1f + 0.6f * down, shape);
    }

    /**
     * Strokes a shape's outline. Neon gets a soft glow without blur filters (which hardware
     * rendering doesn't support): wide, faint strokes under narrower, brighter ones.
     */
    private void drawOutline(Canvas canvas, float radius, int color, float glow, int shape) {
        float width = lineWidth(radius);
        if (palette.style == NEON) {
            int r = Color.red(color);
            int gr = Color.green(color);
            int bl = Color.blue(color);
            stroke.setStrokeWidth(width * 5f);
            stroke.setColor(Color.argb((int) Math.min(255, 28 * glow), r, gr, bl));
            drawUnitShape(canvas, shape, stroke);
            stroke.setStrokeWidth(width * 2.4f);
            stroke.setColor(Color.argb((int) Math.min(255, 80 * glow), r, gr, bl));
            drawUnitShape(canvas, shape, stroke);
            color = lighten(color, 0.45f);
        }
        stroke.setStrokeWidth(width);
        stroke.setColor(color);
        drawUnitShape(canvas, shape, stroke);
    }

    private void drawUnitShape(Canvas canvas, int shape, Paint paint) {
        if (shape == CIRCLE_SHAPE) {
            canvas.drawCircle(0, 0, 1f, paint);
        } else if (shape == PILL_SHAPE) {
            rect.set(-pillHalfLength, -pillHalfHeight, pillHalfLength, pillHalfHeight);
            canvas.drawRoundRect(rect, pillHalfHeight, pillHalfHeight, paint);
        } else {
            // The d-pad's cross as one outline; two stroked rectangles would cross in the middle.
            path.reset();
            rect.set(-ARM, -1, ARM, 1);
            path.addRoundRect(rect, 0.1f, 0.1f, Path.Direction.CW);
            crossPath.reset();
            rect.set(-1, -ARM, 1, ARM);
            crossPath.addRoundRect(rect, 0.1f, 0.1f, Path.Direction.CW);
            path.op(crossPath, Path.Op.UNION);
            canvas.drawPath(path, paint);
        }
    }

    /** Label colour on a flat control: its own colour, or black once it's filled in. */
    private int flatInk(int color, float down) {
        if (palette.style == NEON) return lighten(color, 0.3f * down);
        return down > 0.5f ? (luminance(color) > 0.5f ? Color.BLACK : Color.WHITE) : color;
    }

    /** A line about 2dp wide, in unit coordinates for a control of this radius. */
    private static float lineWidth(float radius) {
        return Math.max(1.5f, 2f * density()) / Math.max(1, radius);
    }

    private static float clamp(float value) {
        return Math.max(-1.3f, Math.min(1.3f, value));
    }

    private static float clamp01(float value) {
        return Math.max(0, Math.min(1, value));
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
