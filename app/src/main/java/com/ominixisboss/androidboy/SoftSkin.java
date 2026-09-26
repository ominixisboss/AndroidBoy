package com.ominixisboss.androidboy;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.Random;

/**
 * Soft skins: a plain, lightly textured background with big pastel controls outlined in dark
 * ink: a d-pad with arrows and a dimple, and round A and B with large letters. The game screen
 * runs edge to edge. They have no menu or speed buttons of their own; the game screen's
 * toolbars do that.
 */
final class SoftSkin extends Skin {
    static final String CATEGORY = "Soft";

    /** Colours for one variant. */
    static final class Variant {
        final String id;
        final String name;
        final int background;
        final int button;
        final int ink;
        final int label;

        Variant(String id, String name, int background, int button, int ink, int label) {
            this.id = id;
            this.name = name;
            this.background = background;
            this.button = button;
            this.ink = ink;
            this.label = label;
        }
    }

    static final Variant[] VARIANTS = {
            new Variant("slate", "Slate", 0xFF5E6B74, 0xFFD2CFDF, 0xFF26272E, 0xFF8C889E),
            new Variant("lilac", "Lilac", 0xFF6A6284, 0xFFE3DCF2, 0xFF25222F, 0xFF8E84A8),
            new Variant("sage", "Sage", 0xFF6B7A66, 0xFFE0E5D3, 0xFF252A23, 0xFF818C75),
            new Variant("blush", "Blush", 0xFF8D6C77, 0xFFF3DCE3, 0xFF2E2226, 0xFFA78791),
            new Variant("sand", "Sand", 0xFF9C8B70, 0xFFF1E7D4, 0xFF2D2820, 0xFFA1937A),
            new Variant("ocean", "Ocean", 0xFF3F6177, 0xFFD5E5EE, 0xFF1E2A32, 0xFF6D8DA1),
            new Variant("charcoal", "Charcoal", 0xFF33353B, 0xFFB8BBC3, 0xFF141518, 0xFF6C7079),
            new Variant("midnight", "Midnight", 0xFF1C2130, 0xFF3A4154, 0xFF0B0D13, 0xFF9AA4BD),
    };

    static final SoftSkin[] ALL;

    static {
        ALL = new SoftSkin[VARIANTS.length];
        for (int i = 0; i < VARIANTS.length; i++) ALL[i] = new SoftSkin(VARIANTS[i]);
    }

    static SoftSkin find(String variant) {
        for (SoftSkin skin : ALL) {
            if (skin.variant.id.equals(variant)) return skin;
        }
        return null;
    }

    private static Bitmap grain;

    /** A small tile of speckles, repeated over the background for a matte, slightly rough look. */
    private static synchronized Bitmap grain() {
        if (grain == null) {
            int size = 96;
            int[] pixels = new int[size * size];
            Random random = new Random(7);
            for (int i = 0; i < pixels.length; i++) {
                int alpha = random.nextInt(22);
                pixels[i] = random.nextBoolean() ? Color.argb(alpha, 255, 255, 255) : Color.argb(alpha, 0, 0, 0);
            }
            grain = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        }
        return grain;
    }

    private final Variant variant;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private Paint grainPaint;

    private SoftSkin(Variant variant) {
        this.variant = variant;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
    }

    @Override
    String id() {
        return "soft:" + variant.id;
    }

    @Override
    String name() {
        return variant.name;
    }

    @Override
    int backgroundColor() {
        return variant.background;
    }

    @Override
    boolean hasMenuButton() {
        return false;
    }

    // ---- Layout ----

    @Override
    void layout(Layout out, int width, int height, int frameWidth, int frameHeight,
                boolean controlsVisible, boolean integerScaling) {
        layoutHandheld(out, width, height, frameWidth, frameHeight, controlsVisible, integerScaling);
    }

    /**
     * The layout Soft and Glass skins share: the screen edge to edge at the top (portrait) or in
     * the middle (landscape), a big d-pad and A/B, and Start/Select; no menu or speed buttons.
     */
    static void layoutHandheld(Layout out, int width, int height, int frameWidth, int frameHeight,
                               boolean controlsVisible, boolean integerScaling) {
        out.reset(width, height, controlsVisible);
        if (!controlsVisible) {
            fitScreen(out.screen, new RectF(0, 0, width, height), frameWidth, frameHeight, integerScaling);
            return;
        }
        if (height >= width) {
            layoutPortrait(out, width, height, frameWidth, frameHeight, integerScaling);
        } else {
            layoutLandscape(out, width, height, frameWidth, frameHeight, integerScaling);
        }
    }

    private static void layoutPortrait(Layout out, int width, int height, int frameWidth, int frameHeight,
                                boolean integerScaling) {
        // The screen spans the width at the top; the controls fill the rest.
        float screenHeight = Math.min(height * 0.5f, width * frameHeight / (float) frameWidth);
        fitScreen(out.screen, new RectF(0, 0, width, screenHeight), frameWidth, frameHeight, integerScaling);
        float top = out.screen.bottom;
        float area = height - top;
        float unit = Math.min(width, area * 1.15f);
        float dpad = unit * 0.40f;
        float padCenterY = top + area * 0.40f;
        float dpadX = width * 0.28f;
        out.controls.add(new Control(Emulator.KEY_UP | Emulator.KEY_DOWN | Emulator.KEY_LEFT | Emulator.KEY_RIGHT,
                Control.DPAD, square(dpadX, padCenterY, dpad / 2), true));
        float radius = unit * 0.093f;
        float ax = width * 0.80f;
        float ay = padCenterY - radius * 0.95f;
        float bx = width * 0.64f;
        float by = padCenterY + radius * 1.05f;
        addFaceButtons(out, ax, ay, bx, by, radius);
        float pillY = top + area * 0.83f;
        addPills(out, width / 2f, pillY, unit * 0.14f, unit * 0.042f, unit * 0.2f);
    }

    private static void layoutLandscape(Layout out, int width, int height, int frameWidth, int frameHeight,
                                 boolean integerScaling) {
        // The screen in the middle, full height; controls either side.
        float side = Math.max(width * 0.22f, (width - height * frameWidth / (float) frameHeight) / 2);
        fitScreen(out.screen, new RectF(side, 0, width - side, height), frameWidth, frameHeight, integerScaling);
        float unit = Math.min(side * 1.9f, height * 0.9f);
        float dpad = unit * 0.46f;
        float centerY = height * 0.52f;
        out.controls.add(new Control(Emulator.KEY_UP | Emulator.KEY_DOWN | Emulator.KEY_LEFT | Emulator.KEY_RIGHT,
                Control.DPAD, square(side / 2, centerY, dpad / 2), true));
        float radius = unit * 0.105f;
        float rightCenter = width - side / 2;
        addFaceButtons(out, rightCenter + radius * 1.05f, centerY - radius * 0.9f,
                rightCenter - radius * 1.05f, centerY + radius * 0.9f, radius);
        float pillWidth = unit * 0.15f;
        float pillHeight = unit * 0.045f;
        float pillY = height * 0.9f;
        out.controls.add(new Control(Emulator.KEY_SELECT, Control.PILL,
                new RectF(side / 2 - pillWidth / 2, pillY - pillHeight / 2, side / 2 + pillWidth / 2, pillY + pillHeight / 2), true));
        out.controls.add(new Control(Emulator.KEY_START, Control.PILL,
                new RectF(rightCenter - pillWidth / 2, pillY - pillHeight / 2, rightCenter + pillWidth / 2, pillY + pillHeight / 2), true));
    }

    static void addFaceButtons(Layout out, float ax, float ay, float bx, float by, float radius) {
        out.controls.add(new Control(Emulator.KEY_A, Control.CIRCLE, square(ax, ay, radius), true));
        out.controls.add(new Control(Emulator.KEY_B, Control.CIRCLE, square(bx, by, radius), true));
        // Between the two: both at once.
        float mx = (ax + bx) / 2;
        float my = (ay + by) / 2;
        out.controls.add(new Control(Emulator.KEY_A | Emulator.KEY_B, Control.CIRCLE, square(mx, my, radius * 0.35f), false));
    }

    static void addPills(Layout out, float centerX, float y, float pillWidth, float pillHeight, float gap) {
        float selectX = centerX - gap / 2 - pillWidth / 2;
        float startX = centerX + gap / 2 + pillWidth / 2;
        out.controls.add(new Control(Emulator.KEY_SELECT, Control.PILL,
                new RectF(selectX - pillWidth / 2, y - pillHeight / 2, selectX + pillWidth / 2, y + pillHeight / 2), true));
        out.controls.add(new Control(Emulator.KEY_START, Control.PILL,
                new RectF(startX - pillWidth / 2, y - pillHeight / 2, startX + pillWidth / 2, y + pillHeight / 2), true));
    }

    private static RectF square(float cx, float cy, float half) {
        return new RectF(cx - half, cy - half, cx + half, cy + half);
    }

    // ---- Drawing ----

    @Override
    void drawBackground(Canvas canvas, Layout layout) {
        canvas.drawColor(variant.background);
        if (grainPaint == null) {
            grainPaint = new Paint();
            grainPaint.setShader(new BitmapShader(grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        }
        canvas.drawRect(0, 0, layout.width, layout.height, grainPaint);
        // Black behind the game screen, across the full width in portrait.
        fill.setShader(null);
        fill.setColor(Color.BLACK);
        if (layout.height >= layout.width && layout.controlsVisible) {
            canvas.drawRect(0, layout.screen.top, layout.width, layout.screen.bottom, fill);
        } else {
            canvas.drawRect(layout.screen, fill);
        }
    }

    @Override
    void drawControls(Canvas canvas, Layout layout, int pressed, Motion motion) {
        float density = Math.max(1, layout.width / 400f);
        for (int i = 0; i < layout.controls.size(); i++) {
            Control control = layout.controls.get(i);
            if (!control.visible) continue;
            float press = Math.max(0, Math.min(1.2f, motion.press(i)));
            switch (control.shape) {
                case Control.DPAD:
                    drawDpad(canvas, control.bounds, pressed & control.keys, motion, density);
                    break;
                case Control.PILL:
                    drawPill(canvas, control, press, density);
                    break;
                default:
                    drawButton(canvas, control, press, density);
                    break;
            }
        }
    }

    /** A paint's alpha applies to its shader too: go opaque first, or a shadow's alpha would carry over. */
    private void useShader(Shader shader) {
        fill.setColor(Color.BLACK);
        fill.setShader(shader);
    }

    /** Lighter at the top, a little darker below, like soft matte plastic; darker still while held. */
    private Shader plastic(RectF b, float press) {
        int top = blend(variant.button, Color.WHITE, 0.18f * (1 - press));
        int bottom = blend(variant.button, Color.BLACK, 0.08f + 0.14f * press);
        return new LinearGradient(0, b.top, 0, b.bottom, top, bottom, Shader.TileMode.CLAMP);
    }

    private void drawDpad(Canvas canvas, RectF bounds, int held, Motion motion, float density) {
        float size = bounds.width();
        float arm = size * 0.34f;
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        float corner = arm * 0.22f;
        // A plus of two rounded bars; the pressed arm sinks a little along with the tilt.
        canvas.save();
        canvas.translate(motion.tiltX * size * 0.012f, motion.tiltY * size * 0.012f);
        Path plus = plusPath(bounds, arm);
        fill.setPathEffect(new CornerPathEffect(corner));
        stroke.setPathEffect(fill.getPathEffect());
        // Shadow, fill, outline.
        fill.setShader(null);
        fill.setColor(0x33000000);
        canvas.save();
        canvas.translate(0, 3 * density);
        canvas.drawPath(plus, fill);
        canvas.restore();
        useShader(plastic(bounds, 0));
        canvas.drawPath(plus, fill);
        fill.setShader(null);
        // Held arms darken, inside the d-pad's rounded outline.
        canvas.save();
        Path rounded = new Path();
        new Paint(fill).getFillPath(plus, rounded);
        canvas.clipPath(rounded);
        fill.setColor(0x26000000);
        float armLength = (size - arm) / 2;
        if ((held & Emulator.KEY_UP) != 0) canvas.drawRect(cx - arm / 2, bounds.top, cx + arm / 2, bounds.top + armLength, fill);
        if ((held & Emulator.KEY_DOWN) != 0) canvas.drawRect(cx - arm / 2, bounds.bottom - armLength, cx + arm / 2, bounds.bottom, fill);
        if ((held & Emulator.KEY_LEFT) != 0) canvas.drawRect(bounds.left, cy - arm / 2, bounds.left + armLength, cy + arm / 2, fill);
        if ((held & Emulator.KEY_RIGHT) != 0) canvas.drawRect(bounds.right - armLength, cy - arm / 2, bounds.right, cy + arm / 2, fill);
        canvas.restore();
        stroke.setColor(variant.ink);
        stroke.setStrokeWidth(2.2f * density);
        canvas.drawPath(plus, stroke);
        fill.setPathEffect(null);
        stroke.setPathEffect(null);
        // Arrows.
        fill.setColor(variant.label);
        float tip = armLength * 0.42f;
        float half = arm * 0.2f;
        drawArrow(canvas, cx, bounds.top + armLength * 0.32f, 0, -1, tip, half);
        drawArrow(canvas, cx, bounds.bottom - armLength * 0.32f, 0, 1, tip, half);
        drawArrow(canvas, bounds.left + armLength * 0.32f, cy, -1, 0, tip, half);
        drawArrow(canvas, bounds.right - armLength * 0.32f, cy, 1, 0, tip, half);
        // The dimple in the middle.
        float dimple = arm * 0.3f;
        useShader(new RadialGradient(cx, cy - dimple * 0.3f, dimple * 1.3f,
                blend(variant.button, Color.BLACK, 0.16f), blend(variant.button, Color.WHITE, 0.1f), Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, dimple, fill);
        fill.setShader(null);
        canvas.restore();
    }

    /** The d-pad's outline: a plus of arms {@code arm} wide filling {@code b}. */
    static Path plusPath(RectF b, float arm) {
        float cx = b.centerX();
        float cy = b.centerY();
        float h = arm / 2;
        Path plus = new Path();
        plus.moveTo(cx - h, b.top);
        plus.lineTo(cx + h, b.top);
        plus.lineTo(cx + h, cy - h);
        plus.lineTo(b.right, cy - h);
        plus.lineTo(b.right, cy + h);
        plus.lineTo(cx + h, cy + h);
        plus.lineTo(cx + h, b.bottom);
        plus.lineTo(cx - h, b.bottom);
        plus.lineTo(cx - h, cy + h);
        plus.lineTo(b.left, cy + h);
        plus.lineTo(b.left, cy - h);
        plus.lineTo(cx - h, cy - h);
        plus.close();
        return plus;
    }

    /** A triangle pointing (dx, dy), its tip {@code length} from (x, y)'s far side. */
    private void drawArrow(Canvas canvas, float x, float y, int dx, int dy, float length, float half) {
        path.reset();
        float tipX = x + dx * length / 2;
        float tipY = y + dy * length / 2;
        float baseX = x - dx * length / 2;
        float baseY = y - dy * length / 2;
        path.moveTo(tipX, tipY);
        path.lineTo(baseX + dy * half, baseY + dx * half);
        path.lineTo(baseX - dy * half, baseY - dx * half);
        path.close();
        canvas.drawPath(path, fill);
    }

    private void drawButton(Canvas canvas, Control control, float press, float density) {
        RectF b = control.bounds;
        float radius = b.width() / 2 * (1 - 0.04f * press);
        float cx = b.centerX();
        float cy = b.centerY() + 2 * density * press;
        fill.setShader(null);
        fill.setColor(0x33000000);
        canvas.drawCircle(b.centerX(), b.centerY() + 3 * density, radius, fill);
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        useShader(plastic(rect, Math.min(1, press)));
        canvas.drawCircle(cx, cy, radius, fill);
        fill.setShader(null);
        stroke.setColor(variant.ink);
        stroke.setStrokeWidth(2.2f * density);
        canvas.drawCircle(cx, cy, radius, stroke);
        String letter = control.keys == Emulator.KEY_A ? "A" : control.keys == Emulator.KEY_B ? "B" : "";
        text.setColor(variant.label);
        text.setTextSize(radius * 1.05f);
        text.setTextScaleX(0.9f);
        canvas.drawText(letter, cx, cy - (text.descent() + text.ascent()) / 2, text);
    }

    private void drawPill(Canvas canvas, Control control, float press, float density) {
        RectF b = control.bounds;
        float lift = 1.5f * density * press;
        rect.set(b.left, b.top + lift, b.right, b.bottom + lift);
        float corner = b.height() / 2;
        useShader(plastic(rect, Math.min(1, press)));
        canvas.drawRoundRect(rect, corner, corner, fill);
        fill.setShader(null);
        stroke.setColor(variant.ink);
        stroke.setStrokeWidth(2f * density);
        canvas.drawRoundRect(rect, corner, corner, stroke);
        text.setColor(blend(variant.background, Color.WHITE, 0.55f));
        text.setTextSize(b.height() * 0.62f);
        text.setTextScaleX(1f);
        canvas.drawText(control.keys == Emulator.KEY_START ? "START" : "SELECT", b.centerX(),
                b.bottom + b.height() * 1.05f, text);
    }

    static int blend(int from, int to, float amount) {
        float keep = 1 - amount;
        return Color.argb(Color.alpha(from),
                Math.round(Color.red(from) * keep + Color.red(to) * amount),
                Math.round(Color.green(from) * keep + Color.green(to) * amount),
                Math.round(Color.blue(from) * keep + Color.blue(to) * amount));
    }
}
