package com.ominixisboss.androidboy;

import android.graphics.Bitmap;
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
import android.graphics.Typeface;

import java.util.Random;

import static com.ominixisboss.androidboy.ThemeSkin.lighten;
import static com.ominixisboss.androidboy.ThemeSkin.luminance;
import static com.ominixisboss.androidboy.ThemeSkin.shade;

/**
 * Clear skins: a see-through handheld shell, like the clear editions of the colour Game Boy.
 * The circuit board, chips, screws, rubber membranes and speaker show through tinted plastic,
 * and the screen sits in a glossy black lens. The d-pad is a glossy cross sunk in a rimmed,
 * cross-shaped well, with outlined arrows and a dimple in the middle; it rocks in 3D towards the
 * held direction. Uses the toolbars for the menu, like the Soft and Glass skins.
 */
final class ClearSkin extends Skin {
    static final String CATEGORY = "Clear";

    /** One edition: the plastic and the keys. */
    static final class Variant {
        final String id;
        final String name;
        /** The plastic's colour; its alpha is how much it veils the insides. */
        final int shell;
        /** D-pad, A and B. */
        final int keys;
        /** Start and Select rubber. */
        final int pills;

        Variant(String id, String name, int shell, int keys, int pills) {
            this.id = id;
            this.name = name;
            this.shell = shell;
            this.keys = keys;
            this.pills = pills;
        }
    }

    static final Variant[] VARIANTS = {
            new Variant("clear", "Clear", 0x38E4E6EE, 0xFF151518, 0xFF45474E),
            new Variant("atomic", "Atomic purple", 0x886A52B4, 0xFF33285A, 0xFF4E4270),
            new Variant("grape", "Grape", 0x9A55307F, 0xFF1D1426, 0xFF3B2C48),
            new Variant("teal", "Teal", 0x8A1497A8, 0xFF10262B, 0xFF2B4148),
            new Variant("fire", "Fire red", 0x90CC2233, 0xFF1A0E10, 0xFF4A2A2E),
            new Variant("jungle", "Jungle green", 0x8A2F9447, 0xFF0F1D12, 0xFF2C4232),
            new Variant("ice", "Ice blue", 0x6A7FC4EE, 0xFF1B2A3A, 0xFF3E5064),
            new Variant("tangerine", "Tangerine", 0x90FF8A1E, 0xFF2A160A, 0xFF5A3A22),
            new Variant("bubblegum", "Bubblegum", 0x7AFF7FBF, 0xFF3A1428, 0xFF5E3A4E),
            new Variant("smoke", "Smoke", 0xB0222328, 0xFF0B0B0D, 0xFF303238),
            new Variant("glow", "Glow in the dark", 0x70D6FF9E, 0xFF22301A, 0xFF46553A),
            new Variant("crystal", "Crystal white", 0x30E8ECF4, 0xFFE6E7EC, 0xFFB8BAC2),
    };

    static final ClearSkin[] ALL;

    static {
        ALL = new ClearSkin[VARIANTS.length];
        for (int i = 0; i < VARIANTS.length; i++) ALL[i] = new ClearSkin(VARIANTS[i]);
    }

    static ClearSkin find(String variant) {
        for (ClearSkin skin : ALL) {
            if (skin.variant.id.equals(variant)) return skin;
        }
        return null;
    }

    /** The d-pad rocks up to this far towards the held direction. */
    static final float TILT_DEGREES = 14f;
    /** How thick the d-pad is, in d-pad radii: its side walls show as it rocks. */
    private static final float THICKNESS = 0.2f;
    private static final int WALL_LAYERS = 8;
    /** How far the pad sinks when a direction is held, in d-pad radii. */
    private static final float SINK = 0.07f;
    /** Half the width of a d-pad arm, in d-pad radii. */
    private static final float ARM = 0.34f;
    /** The pad's size within its control bounds, leaving room for the rim around the well. */
    private static final float PAD_SCALE = 0.86f;

    /** Colours of "COLOR" in the wordmark. */
    private static final int[] WORDMARK = {0xFFE8354A, 0xFF8E5BD8, 0xFF3DBE5A, 0xFFF5C518, 0xFF2F8FE8};

    /** Geometry computed in {@link #layout}. */
    private static final class Geometry {
        final RectF lens = new RectF();
        boolean hasLens;
        boolean wordmark;
        boolean portrait;
        float speakerX;
        float speakerY;
        /** 0 when there's no room for the speaker. */
        float speakerRadius;
    }

    private final Variant variant;
    /** The plastic, opaque. */
    private final int plastic;
    /** What the shell looks like with nothing behind it but light. */
    private final int backingTop;
    private final int backingBottom;
    private final int board;
    private final int trace;
    private final int membrane;
    private final int rim;
    /** Printing on the shell. */
    private final int ink;
    private final int surface;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Matrix tiltMatrix = new Matrix();
    private final float[] tilted = new float[8];
    private final Path rimPath = cross(1.14f, ARM + 0.14f, 0.2f);
    private final Path wellPath = cross(1.06f, ARM + 0.06f, 0.14f);
    private final Path padPath = cross(1f, ARM, 0.1f);
    private final Path arrowPath = arrow();
    private Bitmap body;
    private int bodyKey;

    private ClearSkin(Variant variant) {
        this.variant = variant;
        plastic = variant.shell | 0xFF000000;
        backingTop = lighten(plastic, 0.72f);
        backingBottom = lighten(plastic, 0.52f);
        board = mix(0xFF2E6B4F, plastic, 0.3f);
        trace = lighten(board, 0.4f);
        membrane = mix(0xFFB5E8D6, plastic, 0.35f);
        rim = lighten(plastic, 0.72f);
        surface = mix(mix(backingTop, board, 0.3f), plastic, Color.alpha(variant.shell) / 255f);
        ink = luminance(surface) > 0.45f ? shade(plastic, 0.35f) : lighten(plastic, 0.7f);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
    }

    @Override
    String id() {
        return "clear:" + variant.id;
    }

    @Override
    String name() {
        return variant.name;
    }

    @Override
    int backgroundColor() {
        return surface;
    }

    @Override
    boolean hasMenuButton() {
        return false;
    }

    // ---- Layout ----

    @Override
    void layout(Layout out, int width, int height, int frameWidth, int frameHeight,
                boolean controlsVisible, boolean integerScaling) {
        Geometry g = new Geometry();
        if (controlsVisible && height >= width) {
            layoutPortrait(out, g, width, height, frameWidth, frameHeight, integerScaling);
        } else {
            SoftSkin.layoutHandheld(out, width, height, frameWidth, frameHeight, controlsVisible, integerScaling);
            // A lens around the screen, as far as it fits.
            float pad = Math.min(width, height) * 0.035f;
            Rect s = out.screen;
            g.lens.set(Math.max(0, s.left - pad), Math.max(0, s.top - pad),
                    Math.min(width, s.right + pad), Math.min(height, s.bottom + pad));
            g.hasLens = g.lens.width() > s.width() + 1 || g.lens.height() > s.height() + 1;
        }
        out.extras = g; // After the layout, which resets the extras.
    }

    private static void layoutPortrait(Layout out, Geometry g, int width, int height, int frameWidth, int frameHeight,
                                       boolean integerScaling) {
        out.reset(width, height, true);
        g.portrait = true;
        float margin = width * 0.05f;
        float side = width * 0.1f;
        float above = width * 0.055f;
        float below = width * 0.12f;
        float screenWidth = width - 2 * (margin + side);
        float screenHeight = Math.min(height * 0.42f, screenWidth * frameHeight / (float) frameWidth);
        fitScreen(out.screen, new RectF(margin + side, margin + above, width - margin - side, margin + above + screenHeight),
                frameWidth, frameHeight, integerScaling);
        g.lens.set(margin, out.screen.top - above, width - margin, out.screen.bottom + below);
        g.hasLens = true;
        g.wordmark = true;

        float top = g.lens.bottom;
        float area = height - top;
        float unit = Math.min(width, area * 1.1f);
        float dpad = unit * 0.38f;
        float padY = top + area * 0.3f;
        out.controls.add(new Control(Emulator.KEY_UP | Emulator.KEY_DOWN | Emulator.KEY_LEFT | Emulator.KEY_RIGHT,
                Control.DPAD, square(width * 0.26f, padY, dpad / 2), true));
        float radius = unit * 0.088f;
        SoftSkin.addFaceButtons(out, width * 0.81f, padY - radius * 0.75f, width * 0.61f, padY + radius * 0.6f, radius);
        float pillY = top + area * 0.6f;
        SoftSkin.addPills(out, width / 2f, pillY, unit * 0.125f, unit * 0.042f, unit * 0.075f);
        // The speaker in the bottom-right corner, if there's room under the buttons.
        float speaker = unit * 0.11f;
        float speakerY = height - speaker * 1.35f;
        if (speakerY - speaker > padY + radius * 2.2f) {
            g.speakerX = width * 0.8f;
            g.speakerY = speakerY;
            g.speakerRadius = speaker;
        }
    }

    private static RectF square(float cx, float cy, float half) {
        return new RectF(cx - half, cy - half, cx + half, cy + half);
    }

    // ---- The shell and what's inside it ----

    @Override
    void drawBackground(Canvas canvas, Layout layout) {
        Geometry g = layout.extras instanceof Geometry ? (Geometry) layout.extras : new Geometry();
        int key = bodyKey(layout);
        if (body == null || body.getWidth() != layout.width || body.getHeight() != layout.height || bodyKey != key) {
            body = Bitmap.createBitmap(Math.max(1, layout.width), Math.max(1, layout.height), Bitmap.Config.ARGB_8888);
            drawBody(new Canvas(body), layout, g);
            bodyKey = key;
        }
        canvas.drawBitmap(body, 0, 0, null);
    }

    /** Identifies what the cached body was drawn for: the size, the screen and where the controls are. */
    private static int bodyKey(Layout layout) {
        int key = 31 * layout.width + layout.height;
        key = 31 * key + layout.screen.hashCode();
        key = 31 * key + (layout.controlsVisible ? 1 : 0);
        for (Control control : layout.controls) key = 31 * key + control.bounds.hashCode();
        return key;
    }

    private void drawBody(Canvas canvas, Layout layout, Geometry g) {
        int w = layout.width;
        int h = layout.height;
        useShader(new LinearGradient(0, 0, 0, h, backingTop, backingBottom, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
        drawInsides(canvas, layout, g);
        // The plastic over it all.
        fill.setColor(variant.shell);
        canvas.drawRect(0, 0, w, h, fill);
        drawShellDetails(canvas, layout, g);
        if (g.hasLens) drawLens(canvas, layout, g);
        drawPrinting(canvas, layout);
    }

    private void drawInsides(Canvas canvas, Layout layout, Geometry g) {
        int w = layout.width;
        int h = layout.height;
        float u = Math.min(w, h);
        // The same board every time.
        Random random = new Random(0x6B0BL);
        RectF boardRect = new RectF(u * 0.04f, u * 0.04f, w - u * 0.04f, h - u * 0.04f);
        fill.setColor(withAlpha(board, 0xA8));
        canvas.drawRoundRect(boardRect, u * 0.03f, u * 0.03f, fill);
        // A few wide ground and power tracks, then lots of thin signal traces ending in vias.
        stroke.setColor(withAlpha(trace, 0x70));
        stroke.setStrokeWidth(u * 0.014f);
        for (int i = 0; i < 6; i++) drawTrace(canvas, random, boardRect, u, 3);
        stroke.setStrokeWidth(u * 0.0045f);
        stroke.setColor(withAlpha(trace, 0xB0));
        for (int i = 0; i < 110; i++) {
            float[] end = drawTrace(canvas, random, boardRect, u, 2 + random.nextInt(3));
            fill.setColor(withAlpha(trace, 0xD0));
            canvas.drawCircle(end[0], end[1], u * 0.0075f, fill);
            fill.setColor(withAlpha(board, 0xFF));
            canvas.drawCircle(end[0], end[1], u * 0.0032f, fill);
        }
        // Small parts: resistors and capacitors.
        for (int i = 0; i < 40; i++) {
            float x = boardRect.left + random.nextFloat() * boardRect.width();
            float y = boardRect.top + random.nextFloat() * boardRect.height();
            float length = u * (0.016f + random.nextFloat() * 0.01f);
            boolean across = random.nextBoolean();
            rect.set(x, y, x + (across ? length : length * 0.5f), y + (across ? length * 0.5f : length));
            fill.setColor(random.nextInt(3) == 0 ? 0xE08C6E4A : 0xE0303034);
            canvas.drawRect(rect, fill);
            fill.setColor(0xE0C8CCD2);
            if (across) {
                canvas.drawRect(rect.left, rect.top, rect.left + length * 0.2f, rect.bottom, fill);
                canvas.drawRect(rect.right - length * 0.2f, rect.top, rect.right, rect.bottom, fill);
            } else {
                canvas.drawRect(rect.left, rect.top, rect.right, rect.top + length * 0.2f, fill);
                canvas.drawRect(rect.left, rect.bottom - length * 0.2f, rect.right, rect.bottom, fill);
            }
        }
        // The chips: a big one with a grid of solder balls, and one with legs.
        RectF dpad = null;
        RectF pills = null;
        for (Control control : layout.controls) {
            if (control.shape == Control.DPAD) dpad = control.bounds;
            if (control.shape == Control.PILL) {
                if (pills == null) pills = new RectF(control.bounds); else pills.union(control.bounds);
            }
        }
        if (g.portrait && dpad != null) {
            // Low down, clear of the Start and Select labels.
            float size = u * 0.17f;
            float lowest = pills != null ? pills.bottom + pills.height() * 2.4f : dpad.bottom;
            float chipY = Math.max(lowest + size * 0.6f, h - u * 0.2f);
            if (chipY + size / 2 < h - u * 0.03f) drawGridChip(canvas, u * 0.24f, chipY, size);
            float legsY = Math.max(lowest + u * 0.06f, h - u * 0.2f);
            if (legsY + u * 0.05f < h - u * 0.1f) drawLeggedChip(canvas, w * 0.5f, legsY, u * 0.11f, u * 0.07f);
        } else {
            float side = layout.screen.left;
            if (side > u * 0.2f) {
                drawGridChip(canvas, side / 2, h * 0.17f, Math.min(side * 0.4f, u * 0.17f));
                drawLeggedChip(canvas, w - side / 2, h * 0.17f, Math.min(side * 0.4f, u * 0.14f), Math.min(side * 0.25f, u * 0.09f));
            }
        }
        // Rubber membranes under the keys, their mint green showing through.
        for (Control control : layout.controls) {
            if (!control.visible) continue;
            RectF b = control.bounds;
            if (control.shape == Control.DPAD) {
                drawMembraneCircle(canvas, b.centerX(), b.centerY(), b.width() * 0.6f, u);
            } else if (control.shape == Control.PILL) {
                float grow = b.height() * 0.7f;
                rect.set(b.left - grow, b.top - grow, b.right + grow, b.bottom + grow);
                fill.setColor(withAlpha(membrane, 0xC8));
                canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill);
                stroke.setStrokeWidth(u * 0.004f);
                stroke.setColor(withAlpha(shade(membrane, 0.8f), 0xA0));
                canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, stroke);
            } else {
                drawMembraneCircle(canvas, b.centerX(), b.centerY(), b.width() * 0.66f, u);
            }
        }
        // Screw posts.
        float[][] screws = g.portrait
                ? new float[][] {{u * 0.1f, g.lens.bottom + u * 0.07f}, {w - u * 0.1f, g.lens.bottom + u * 0.07f},
                        {u * 0.09f, h - u * 0.09f}, {w * 0.5f, h - u * 0.07f}}
                : new float[][] {{u * 0.08f, u * 0.08f}, {w - u * 0.08f, u * 0.08f}, {u * 0.08f, h - u * 0.08f},
                        {w - u * 0.08f, h - u * 0.08f}};
        for (float[] screw : screws) drawScrew(canvas, screw[0], screw[1], u);
        // The speaker behind its grille.
        if (g.speakerRadius > 0) {
            float r = g.speakerRadius;
            fill.setShader(new RadialGradient(g.speakerX, g.speakerY, r,
                    new int[] {0xFFD2A574, 0xFFA07A58, 0xFF6E6A66, 0xFF4A4846}, new float[] {0f, 0.3f, 0.75f, 1f},
                    Shader.TileMode.CLAMP));
            fill.setColor(Color.BLACK);
            canvas.drawCircle(g.speakerX, g.speakerY, r, fill);
            fill.setShader(null);
            stroke.setStrokeWidth(u * 0.004f);
            stroke.setColor(0x60000000);
            for (int i = 1; i <= 3; i++) canvas.drawCircle(g.speakerX, g.speakerY, r * i / 3.4f, stroke);
        }
    }

    /** A trace from a random point on the board: straight and 45° runs. Returns where it ends. */
    private float[] drawTrace(Canvas canvas, Random random, RectF area, float unit, int segments) {
        float x = area.left + random.nextFloat() * area.width();
        float y = area.top + random.nextFloat() * area.height();
        path.reset();
        path.moveTo(x, y);
        int direction = random.nextInt(4) * 2; // Start straight: 0 right, 2 down, 4 left, 6 up.
        for (int i = 0; i < segments; i++) {
            float length = unit * (0.04f + random.nextFloat() * 0.16f);
            double angle = Math.toRadians(direction * 45);
            x = clampTo(x + (float) Math.cos(angle) * length, area.left + unit * 0.01f, area.right - unit * 0.01f);
            y = clampTo(y + (float) Math.sin(angle) * length, area.top + unit * 0.01f, area.bottom - unit * 0.01f);
            path.lineTo(x, y);
            direction = (direction + (random.nextBoolean() ? 1 : 7)) % 8;
        }
        canvas.drawPath(path, stroke);
        return new float[] {x, y};
    }

    private void drawGridChip(Canvas canvas, float cx, float cy, float size) {
        rect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
        fill.setColor(0xD8222226);
        canvas.drawRoundRect(rect, size * 0.04f, size * 0.04f, fill);
        int count = 8;
        float step = size / (count + 1);
        fill.setColor(0xE8B8BCC4);
        for (int i = 1; i <= count; i++) {
            for (int j = 1; j <= count; j++) {
                canvas.drawCircle(rect.left + i * step, rect.top + j * step, step * 0.28f, fill);
            }
        }
    }

    private void drawLeggedChip(Canvas canvas, float cx, float cy, float width, float height) {
        fill.setColor(0xE0C0C4CA);
        int legs = 8;
        float pitch = width / legs;
        for (int i = 0; i < legs; i++) {
            float x = cx - width / 2 + (i + 0.5f) * pitch;
            canvas.drawRect(x - pitch * 0.22f, cy - height / 2 - height * 0.18f, x + pitch * 0.22f, cy + height / 2 + height * 0.18f, fill);
        }
        rect.set(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2);
        fill.setColor(0xE01C1C20);
        canvas.drawRect(rect, fill);
        fill.setColor(0x40FFFFFF);
        canvas.drawCircle(rect.left + height * 0.2f, rect.top + height * 0.2f, height * 0.08f, fill);
    }

    private void drawMembraneCircle(Canvas canvas, float cx, float cy, float radius, float unit) {
        fill.setColor(withAlpha(membrane, 0xC8));
        canvas.drawCircle(cx, cy, radius, fill);
        stroke.setStrokeWidth(unit * 0.004f);
        stroke.setColor(withAlpha(shade(membrane, 0.8f), 0xA0));
        canvas.drawCircle(cx, cy, radius, stroke);
        canvas.drawCircle(cx, cy, radius * 0.9f, stroke);
    }

    private void drawScrew(Canvas canvas, float x, float y, float unit) {
        fill.setColor(withAlpha(lighten(plastic, 0.5f), 0x90));
        canvas.drawCircle(x, y, unit * 0.03f, fill);
        float r = unit * 0.017f;
        fill.setShader(new RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.4f, 0xFFE4E6EA, 0xFF7C8088, Shader.TileMode.CLAMP));
        fill.setColor(Color.BLACK);
        canvas.drawCircle(x, y, r, fill);
        fill.setShader(null);
        stroke.setStrokeWidth(r * 0.25f);
        stroke.setColor(0xFF55585E);
        canvas.drawLine(x - r * 0.55f, y, x + r * 0.55f, y, stroke);
        canvas.drawLine(x, y - r * 0.55f, x, y + r * 0.55f, stroke);
    }

    private void drawShellDetails(Canvas canvas, Layout layout, Geometry g) {
        int w = layout.width;
        int h = layout.height;
        float u = Math.min(w, h);
        // Light across the plastic.
        fill.setShader(new LinearGradient(0, 0, w, h, new int[] {0x30FFFFFF, 0x00FFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                new float[] {0f, 0.35f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        fill.setColor(Color.BLACK);
        canvas.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
        // The shell's side walls, seen edge on through the clear plastic.
        float wall = u * 0.022f;
        stroke.setStrokeWidth(wall);
        stroke.setColor(withAlpha(lighten(plastic, 0.35f), 0x70));
        canvas.drawRect(wall / 2, wall / 2, w - wall / 2, h - wall / 2, stroke);
        stroke.setStrokeWidth(u * 0.004f);
        stroke.setColor(withAlpha(shade(plastic, 0.6f), 0x60));
        canvas.drawRect(wall, wall, w - wall, h - wall, stroke);
        // A moulded badge under the lens.
        if (g.portrait) {
            float y = g.lens.bottom + u * 0.05f;
            rect.set(w / 2f - u * 0.14f, y - u * 0.03f, w / 2f + u * 0.14f, y + u * 0.03f);
            stroke.setStrokeWidth(u * 0.005f);
            stroke.setColor(0x50FFFFFF);
            canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, stroke);
            rect.offset(0, -u * 0.004f);
            stroke.setColor(0x24000000);
            canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, stroke);
            text.setTextSize(u * 0.032f);
            text.setColor(0x40FFFFFF);
            canvas.drawText("ANDROIDBOY", w / 2f, y + u * 0.012f, text);
        }
        // The speaker grille: rings of holes.
        if (g.speakerRadius > 0) {
            float r = g.speakerRadius;
            for (int ring = 0; ring <= 4; ring++) {
                int count = ring == 0 ? 1 : ring * 6;
                float distance = r * 0.21f * ring;
                for (int i = 0; i < count; i++) {
                    double angle = 2 * Math.PI * i / count;
                    float x = g.speakerX + (float) Math.cos(angle) * distance;
                    float y = g.speakerY + (float) Math.sin(angle) * distance;
                    fill.setColor(0x50FFFFFF);
                    canvas.drawCircle(x, y + r * 0.015f, r * 0.058f, fill);
                    fill.setColor(0xA0141416);
                    canvas.drawCircle(x, y, r * 0.055f, fill);
                }
            }
        }
    }

    private void drawLens(Canvas canvas, Layout layout, Geometry g) {
        float u = Math.min(layout.width, layout.height);
        Rect screen = layout.screen;
        RectF lens = g.lens;
        float corner = g.portrait ? u * 0.035f : u * 0.02f;
        useShader(new LinearGradient(0, lens.top, 0, lens.bottom, 0xFF2A2C32, 0xFF0E0F12, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(lens, corner, corner, fill);
        fill.setShader(null);
        // A glossy streak across the glass.
        canvas.save();
        path.reset();
        path.addRoundRect(lens, corner, corner, Path.Direction.CW);
        canvas.clipPath(path);
        fill.setShader(new LinearGradient(lens.left, lens.top, lens.left + lens.width() * 0.5f, lens.top + lens.width() * 0.5f,
                new int[] {0x00FFFFFF, 0x1CFFFFFF, 0x00FFFFFF}, new float[] {0.35f, 0.5f, 0.65f}, Shader.TileMode.CLAMP));
        fill.setColor(Color.BLACK);
        canvas.drawRect(lens, fill);
        fill.setShader(null);
        canvas.restore();
        stroke.setStrokeWidth(Math.max(1f, u * 0.004f));
        stroke.setColor(0x40FFFFFF);
        canvas.drawRoundRect(lens, corner, corner, stroke);
        // The black border around the screen.
        float edge = u * 0.01f;
        fill.setColor(0xFF08080A);
        canvas.drawRect(screen.left - edge, screen.top - edge, screen.right + edge, screen.bottom + edge, fill);

        // The power light, if there's room beside the screen.
        float side = screen.left - lens.left;
        if (side > u * 0.06f) {
            float x = lens.left + side * 0.3f;
            float y = screen.top + screen.height() * 0.28f;
            float r = u * 0.011f;
            fill.setShader(new RadialGradient(x, y, r * 3, 0x70FF3030, 0x00FF3030, Shader.TileMode.CLAMP));
            fill.setColor(Color.BLACK);
            canvas.drawCircle(x, y, r * 3, fill);
            fill.setShader(null);
            fill.setColor(0xFFFF3B30);
            canvas.drawCircle(x, y, r, fill);
            stroke.setStrokeWidth(u * 0.004f);
            stroke.setColor(0xFF8A8C94);
            for (int i = 1; i <= 3; i++) {
                float arc = r * (1.3f + i * 0.9f);
                rect.set(x + r * 0.8f - arc, y - arc, x + r * 0.8f + arc, y + arc);
                canvas.drawArc(rect, -35, 70, false, stroke);
            }
            text.setTextSize(u * 0.022f);
            text.setColor(0xFF8A8C94);
            text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            canvas.drawText("POWER", lens.left + side * 0.45f, y + r * 3.4f, text);
        }
        if (g.wordmark) drawWordmark(canvas, lens.centerX(), screen.bottom + (lens.bottom - screen.bottom) * 0.62f, u * 0.056f);
    }

    /** "ANDROIDBOY COLOR", the last word in rainbow letters. */
    private void drawWordmark(Canvas canvas, float cx, float baseline, float size) {
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC));
        text.setTextSize(size);
        text.setTextAlign(Paint.Align.LEFT);
        String first = "ANDROIDBOY ";
        String second = "COLOR";
        float total = text.measureText(first) + text.measureText(second);
        float x = cx - total / 2;
        text.setColor(0xFFD8DAE2);
        canvas.drawText(first, x, baseline, text);
        x += text.measureText(first);
        for (int i = 0; i < second.length(); i++) {
            String letter = second.substring(i, i + 1);
            text.setColor(WORDMARK[i % WORDMARK.length]);
            canvas.drawText(letter, x, baseline, text);
            x += text.measureText(letter);
        }
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
    }

    /** SELECT and START printed under their buttons. */
    private void drawPrinting(Canvas canvas, Layout layout) {
        for (Control control : layout.controls) {
            if (!control.visible || control.shape != Control.PILL) continue;
            RectF b = control.bounds;
            text.setTextSize(b.height() * 0.8f);
            text.setColor(ink);
            float y = b.bottom + b.height() * 1.9f;
            // Above the button when there's no room below it.
            if (y > layout.height - b.height() * 0.4f) y = b.top - b.height() * 0.9f;
            canvas.drawText(control.keys == Emulator.KEY_START ? "START" : "SELECT", b.centerX(), y, text);
        }
    }

    // ---- Controls ----

    @Override
    void drawControls(Canvas canvas, Layout layout, int pressed, Motion motion) {
        for (int i = 0; i < layout.controls.size(); i++) {
            Control control = layout.controls.get(i);
            if (!control.visible) continue;
            float press = Math.max(0, Math.min(1.2f, motion.press(i)));
            if (control.shape == Control.DPAD) {
                drawDpad(canvas, control.bounds, motion.tiltX, motion.tiltY);
            } else if (control.shape == Control.PILL) {
                drawPill(canvas, control.bounds, press);
            } else {
                drawButton(canvas, control, press);
            }
        }
    }

    /**
     * The d-pad, drawn in unit coordinates (its radius is 1): a raised rim, the dark well inside
     * it, and the pad, whose side walls are stacked slices under a glossy face, all rocked in
     * perspective towards the held direction.
     */
    private void drawDpad(Canvas canvas, RectF bounds, float tiltX, float tiltY) {
        float radius = bounds.width() / 2 * PAD_SCALE;
        int keys = variant.keys;
        boolean dark = luminance(keys) < 0.3f;
        float amount = Math.min(1f, (float) Math.hypot(tiltX, tiltY));
        float sink = SINK * amount;
        canvas.save();
        canvas.translate(bounds.centerX(), bounds.centerY());
        canvas.scale(radius, radius);

        // The raised rim around the well, lit from the top left, with a shadow on the shell below it.
        canvas.save();
        canvas.translate(0.02f, 0.05f);
        fill.setColor(0x30000000);
        canvas.drawPath(rimPath, fill);
        canvas.restore();
        useShader(new LinearGradient(-1, -1, 1, 1, lighten(rim, 0.45f), shade(rim, 0.72f), Shader.TileMode.CLAMP));
        canvas.drawPath(rimPath, fill);
        fill.setShader(null);
        // The well: dark, deepest at the top where the rim shades it.
        useShader(new LinearGradient(0, -1.06f, 0, 1.06f, 0xFF050506, 0xFF1A1A1E, Shader.TileMode.CLAMP));
        canvas.drawPath(wellPath, fill);
        fill.setShader(null);

        // The pad's shadow on the well floor, moving with the tilt.
        canvas.save();
        canvas.translate(0.03f, 0.06f);
        ThemeSkin.rock(canvas, tiltMatrix, tilted, tiltX, tiltY, TILT_DEGREES, sink + THICKNESS + 0.05f);
        fill.setColor(0x90000000);
        canvas.drawPath(padPath, fill);
        canvas.restore();

        // The side walls: slices from the bottom up. The raised side swings out from under the face.
        for (int i = 0; i < WALL_LAYERS; i++) {
            float t = (float) i / WALL_LAYERS;
            canvas.save();
            ThemeSkin.rock(canvas, tiltMatrix, tilted, tiltX, tiltY, TILT_DEGREES, sink + THICKNESS * (1 - t));
            fill.setColor(dark ? lighten(keys, 0.05f + 0.12f * t) : shade(keys, 0.45f + 0.3f * t));
            canvas.drawPath(padPath, fill);
            canvas.restore();
        }

        // The face.
        canvas.save();
        ThemeSkin.rock(canvas, tiltMatrix, tilted, tiltX, tiltY, TILT_DEGREES, sink);
        useShader(new LinearGradient(-1, -1, 1, 1, lighten(keys, dark ? 0.16f : 0.25f), shade(keys, 0.78f),
                Shader.TileMode.CLAMP));
        canvas.drawPath(padPath, fill);
        canvas.save();
        canvas.clipPath(padPath);
        // Gloss: the light's reflection in the upper left, and a soft band along the top.
        fill.setShader(new RadialGradient(-0.35f, -0.5f, 0.9f, dark ? 0x48FFFFFF : 0x60FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        canvas.drawPath(padPath, fill);
        fill.setShader(new LinearGradient(0, -ARM, 0, ARM * 0.2f, dark ? 0x22FFFFFF : 0x30FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        canvas.drawRect(-1, -ARM, 1, ARM, fill);
        if (amount > 0.01f) {
            // Rocking away from the light darkens the held side and catches it on the raised one.
            float length = (float) Math.hypot(tiltX, tiltY);
            float nx = tiltX / length;
            float ny = tiltY / length;
            fill.setShader(new LinearGradient(nx, ny, -nx, -ny,
                    new int[] {Color.argb((int) (140 * amount), 0, 0, 0), 0x00000000,
                            Color.argb((int) (90 * amount), 255, 255, 255)},
                    null, Shader.TileMode.CLAMP));
            canvas.drawPath(padPath, fill);
        }
        fill.setShader(null);
        canvas.restore();

        // Arrows moulded into each arm: outlines, dark where they dip, light on the far edge.
        for (int direction = 0; direction < 4; direction++) {
            canvas.save();
            canvas.rotate(direction * 90);
            stroke.setStrokeWidth(0.03f);
            canvas.save();
            canvas.translate(0.012f, 0.016f);
            stroke.setColor(dark ? 0x50FFFFFF : lighten(keys, 0.4f));
            canvas.drawPath(arrowPath, stroke);
            canvas.restore();
            stroke.setColor(dark ? 0xFF000000 : shade(keys, 0.5f));
            canvas.drawPath(arrowPath, stroke);
            canvas.restore();
        }
        // The dimple in the middle: concave, so it's lit on the lower right and shaded on the upper left.
        float dimple = 0.22f;
        useShader(new LinearGradient(-dimple, -dimple, dimple, dimple, dark ? 0xFF000000 : shade(keys, 0.55f),
                lighten(keys, dark ? 0.22f : 0.3f), Shader.TileMode.CLAMP));
        canvas.drawCircle(0, 0, dimple, fill);
        fill.setShader(null);
        stroke.setStrokeWidth(0.025f);
        stroke.setColor(dark ? 0x30FFFFFF : 0x50FFFFFF);
        rect.set(-dimple, -dimple, dimple, dimple);
        canvas.drawArc(rect, 20, 140, false, stroke);
        // The light caught on its lower curve: one crescent.
        stroke.setStrokeWidth(0.04f);
        stroke.setColor(0x80FFFFFF);
        rect.set(-dimple * 0.7f, -dimple * 0.7f, dimple * 0.7f, dimple * 0.7f);
        canvas.drawArc(rect, 25, 70, false, stroke);
        // A bright edge along the top of the face and a dark outline.
        canvas.save();
        canvas.clipRect(-1.1f, -1.1f, 1.1f, 0f);
        stroke.setStrokeWidth(0.03f);
        stroke.setColor(dark ? 0x38FFFFFF : 0x60FFFFFF);
        canvas.drawPath(padPath, stroke);
        canvas.restore();
        stroke.setStrokeWidth(0.02f);
        stroke.setColor(dark ? 0xFF000000 : shade(keys, 0.55f));
        canvas.drawPath(padPath, stroke);
        canvas.restore();

        canvas.restore();
    }

    /** A domed, glossy A or B button with its letter moulded in, sitting in a hole in the shell. */
    private void drawButton(Canvas canvas, Control control, float press) {
        RectF b = control.bounds;
        float r = b.width() / 2;
        float cx = b.centerX();
        float cy = b.centerY();
        float down = Math.min(1f, press);
        int keys = variant.keys;
        boolean dark = luminance(keys) < 0.3f;
        // The hole in the shell.
        fill.setColor(0x70000000);
        canvas.drawCircle(cx, cy + r * 0.03f, r * 1.08f, fill);
        // The button's shadow, which it covers as it goes down.
        fill.setColor(0x50000000);
        canvas.drawCircle(cx, cy + r * 0.12f * (1 - down), r * 0.98f, fill);
        float radius = r * (1 - 0.05f * down);
        float y = cy + r * 0.05f * down;
        fill.setShader(new RadialGradient(cx - radius * 0.35f, y - radius * 0.4f, radius * 1.5f,
                new int[] {lighten(keys, dark ? 0.3f : 0.35f), keys, shade(keys, 0.7f)}, new float[] {0, 0.55f, 1},
                Shader.TileMode.CLAMP));
        fill.setColor(Color.BLACK);
        canvas.drawCircle(cx, y, radius, fill);
        fill.setShader(null);
        if (down > 0.01f) {
            fill.setColor(Color.argb((int) (70 * down), 0, 0, 0));
            canvas.drawCircle(cx, y, radius, fill);
        }
        // The window's reflection.
        fill.setColor(Color.argb((int) ((dark ? 110 : 150) * (1 - 0.5f * down)), 255, 255, 255));
        rect.set(cx - radius * 0.55f, y - radius * 0.7f, cx - radius * 0.05f, y - radius * 0.35f);
        canvas.save();
        canvas.rotate(-30, rect.centerX(), rect.centerY());
        canvas.drawOval(rect, fill);
        canvas.restore();
        stroke.setStrokeWidth(r * 0.04f);
        stroke.setColor(dark ? 0xFF000000 : shade(keys, 0.6f));
        canvas.drawCircle(cx, y, radius, stroke);
        // The letter, moulded in: a light edge under a dark one.
        String letter = control.keys == Emulator.KEY_A ? "A" : control.keys == Emulator.KEY_B ? "B" : "";
        text.setTextSize(radius * 0.85f);
        float baseline = y - (text.descent() + text.ascent()) / 2;
        text.setColor(dark ? 0x40FFFFFF : 0x80FFFFFF);
        canvas.drawText(letter, cx + radius * 0.03f, baseline + radius * 0.04f, text);
        text.setColor(dark ? 0xFF050506 : shade(keys, 0.55f));
        canvas.drawText(letter, cx, baseline, text);
    }

    /** A rubber Start or Select button. */
    private void drawPill(Canvas canvas, RectF b, float press) {
        float down = Math.min(1f, press);
        float grow = b.height() * 0.18f;
        rect.set(b.left - grow, b.top - grow, b.right + grow, b.bottom + grow);
        fill.setColor(0x70000000);
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill);
        float shrink = 1 - 0.05f * down;
        float halfWidth = b.width() / 2 * shrink;
        float halfHeight = b.height() / 2 * shrink;
        float y = b.centerY() + b.height() * 0.06f * down;
        rect.set(b.centerX() - halfWidth, y - halfHeight, b.centerX() + halfWidth, y + halfHeight);
        int color = variant.pills;
        useShader(new LinearGradient(0, rect.top, 0, rect.bottom, lighten(color, 0.22f), shade(color, 0.8f),
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, halfHeight, halfHeight, fill);
        fill.setShader(null);
        if (down > 0.01f) {
            fill.setColor(Color.argb((int) (60 * down), 0, 0, 0));
            canvas.drawRoundRect(rect, halfHeight, halfHeight, fill);
        }
    }

    // ---- Helpers ----

    /** Fills with a gradient; the paint's own alpha would otherwise still apply to it. */
    private void useShader(Shader shader) {
        fill.setColor(Color.BLACK);
        fill.setShader(shader);
    }

    /** A plus shape in unit coordinates, as one outline so strokes don't cross in the middle. */
    private static Path cross(float radius, float arm, float corner) {
        Path out = new Path();
        out.addRoundRect(new RectF(-arm, -radius, arm, radius), corner, corner, Path.Direction.CW);
        Path across = new Path();
        across.addRoundRect(new RectF(-radius, -arm, radius, arm), corner, corner, Path.Direction.CW);
        out.op(across, Path.Op.UNION);
        return out;
    }

    /** The outlined arrow on the up arm: a head on a short stem, pointing up. */
    private static Path arrow() {
        Path out = new Path();
        out.moveTo(0, -0.9f);
        out.lineTo(0.2f, -0.66f);
        out.lineTo(0.1f, -0.66f);
        out.lineTo(0.1f, -0.46f);
        out.lineTo(-0.1f, -0.46f);
        out.lineTo(-0.1f, -0.66f);
        out.lineTo(-0.2f, -0.66f);
        out.close();
        return out;
    }

    private static float clampTo(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** {@code a} blended towards {@code b} by {@code amount} (0 to 1); opaque. */
    static int mix(int a, int b, float amount) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * amount),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * amount),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * amount));
    }
}
