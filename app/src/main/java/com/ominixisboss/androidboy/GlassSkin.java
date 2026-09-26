package com.ominixisboss.androidboy;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * Glass skins: controls made of glass over a colourful backdrop. Each control is filled with a
 * blurred copy of the backdrop behind it (frosted glass), tinted, with a bright rim and a
 * reflection. Pressing a direction tilts the d-pad: the scene seen through it shifts (refraction),
 * the glare slides across it, and the pressed arm catches the light. Buttons do the same as
 * they're pressed. Same layout as the Soft skins, with the toolbars for the menu.
 */
final class GlassSkin extends Skin {
    static final String CATEGORY = "Glass";

    /** One glass theme: its backdrop and its glass. */
    static final class Variant {
        final String id;
        final String name;
        /** Backdrop gradient, top to bottom. */
        final int top;
        final int bottom;
        /** Soft coloured lights in the backdrop, seen blurred through the glass. */
        final int[] lights;
        /** Colour of the glass itself (alpha is how strongly it tints). */
        final int tint;
        /** Labels and arrows. */
        final int label;

        Variant(String id, String name, int top, int bottom, int tint, int label, int... lights) {
            this.id = id;
            this.name = name;
            this.top = top;
            this.bottom = bottom;
            this.tint = tint;
            this.label = label;
            this.lights = lights;
        }
    }

    static final Variant[] VARIANTS = {
            new Variant("frosted", "Frosted", 0xFF2A4A86, 0xFF6C3D8F, 0x38FFFFFF, 0xF2FFFFFF,
                    0xFF4FC3F7, 0xFFFF80AB, 0xFFB388FF, 0xFF64FFDA),
            new Variant("smoke", "Smoke", 0xFF3A3E47, 0xFF121418, 0x30C9CED8, 0xE6F2F4F8,
                    0xFFFF8A65, 0xFF90A4AE, 0xFFFFD180, 0xFF546E7A),
            new Variant("aqua", "Aqua", 0xFF006C8E, 0xFF003B5C, 0x3380E8FF, 0xF2E8FDFF,
                    0xFF18FFFF, 0xFF00B0FF, 0xFFA7FFEB, 0xFF1DE9B6),
            new Variant("rose", "Rose quartz", 0xFFB4637F, 0xFF5E2A45, 0x40FFD1E0, 0xF2FFF4F8,
                    0xFFFF80AB, 0xFFFFCCBC, 0xFFF48FB1, 0xFFCE93D8),
            new Variant("emerald", "Emerald", 0xFF0B6B4B, 0xFF06301F, 0x3869F0AE, 0xF2EFFFF6,
                    0xFF00E676, 0xFFB9F6CA, 0xFF1DE9B6, 0xFFFFE57F),
            new Variant("amber", "Amber", 0xFF8A4B0B, 0xFF3D1E05, 0x40FFC56B, 0xF2FFF8EC,
                    0xFFFFAB40, 0xFFFFD740, 0xFFFF6E40, 0xFFFFE0B2),
            new Variant("amethyst", "Amethyst", 0xFF4A2A7A, 0xFF1E0F38, 0x38D1B3FF, 0xF2F8F2FF,
                    0xFFB388FF, 0xFFEA80FC, 0xFF8C9EFF, 0xFFFF80AB),
            new Variant("sapphire", "Sapphire", 0xFF14307A, 0xFF070F33, 0x388CA8FF, 0xF2EEF3FF,
                    0xFF448AFF, 0xFF82B1FF, 0xFF18FFFF, 0xFF7C4DFF),
            new Variant("ruby", "Ruby", 0xFF7A1024, 0xFF33040C, 0x38FF8A9B, 0xF2FFF0F2,
                    0xFFFF1744, 0xFFFF8A80, 0xFFFF6E40, 0xFFF50057),
            new Variant("sea", "Sea glass", 0xFF5E9E97, 0xFF2D5C5A, 0x40D8FFF4, 0xF2F4FFFB,
                    0xFFA7FFEB, 0xFFCCFF90, 0xFF80D8FF, 0xFFFFFFFF),
            new Variant("opal", "Opal", 0xFFB9C3DD, 0xFF7C7FA8, 0x40FFFFFF, 0xF2FFFFFF,
                    0xFFFF80AB, 0xFF80D8FF, 0xFFCCFF90, 0xFFFFE57F),
            new Variant("obsidian", "Obsidian", 0xFF121216, 0xFF050506, 0x28FFFFFF, 0xE6FFFFFF,
                    0xFF7C4DFF, 0xFF00E5FF, 0xFFFF4081, 0xFF3D5AFE),
    };

    static final GlassSkin[] ALL;

    static {
        ALL = new GlassSkin[VARIANTS.length];
        for (int i = 0; i < VARIANTS.length; i++) ALL[i] = new GlassSkin(VARIANTS[i]);
    }

    static GlassSkin find(String variant) {
        for (GlassSkin skin : ALL) {
            if (skin.variant.id.equals(variant)) return skin;
        }
        return null;
    }

    /** How far (as a fraction of a control's size) the scene through the d-pad shifts at full tilt. */
    static final float REFRACTION = 0.16f;
    /** How far the glare slides across the d-pad at full tilt, as a fraction of its size. */
    static final float GLARE_TRAVEL = 0.75f;

    private final Variant variant;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    // The backdrop, sharp and blurred, for the current view size.
    private Bitmap backdrop;
    private Bitmap frosted;
    private BitmapShader frostedShader;

    private GlassSkin(Variant variant) {
        this.variant = variant;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
    }

    @Override
    String id() {
        return "glass:" + variant.id;
    }

    @Override
    String name() {
        return variant.name;
    }

    @Override
    int backgroundColor() {
        return variant.bottom;
    }

    @Override
    boolean usesToolbars() {
        return true;
    }

    @Override
    void layout(Layout out, int width, int height, int frameWidth, int frameHeight,
                boolean controlsVisible, boolean integerScaling) {
        SoftSkin.layoutHandheld(out, width, height, frameWidth, frameHeight, controlsVisible, integerScaling);
    }

    // ---- Backdrop ----

    /** The backdrop for a view size, and its frosted (blurred) copy, made once per size. */
    private void ensureBackdrop(int width, int height) {
        if (backdrop != null && backdrop.getWidth() == width && backdrop.getHeight() == height) return;
        backdrop = Bitmap.createBitmap(Math.max(1, width), Math.max(1, height), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(backdrop);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, width * 0.3f, height, variant.top, variant.bottom, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        // Soft lights, placed the same way every time.
        float[][] spots = {{0.18f, 0.62f, 0.42f}, {0.85f, 0.55f, 0.38f}, {0.55f, 0.88f, 0.45f}, {0.35f, 0.4f, 0.3f},
                {0.8f, 0.92f, 0.3f}};
        float unit = Math.max(width, height);
        for (int i = 0; i < spots.length; i++) {
            int color = variant.lights[i % variant.lights.length];
            float cx = spots[i][0] * width;
            float cy = spots[i][1] * height;
            float radius = spots[i][2] * unit * 0.5f;
            paint.setShader(new RadialGradient(cx, cy, radius, withAlpha(color, 0xB0), withAlpha(color, 0),
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius, paint);
        }
        frosted = blur(backdrop);
        frostedShader = new BitmapShader(frosted, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    }

    /** A cheap strong blur: shrink a lot with filtering, then grow back with filtering, twice. */
    static Bitmap blur(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        Bitmap small = Bitmap.createScaledBitmap(source, Math.max(1, width / 24), Math.max(1, height / 24), true);
        Bitmap smaller = Bitmap.createScaledBitmap(small, Math.max(1, width / 48), Math.max(1, height / 48), true);
        Bitmap back = Bitmap.createScaledBitmap(smaller, Math.max(1, width / 24), Math.max(1, height / 24), true);
        return Bitmap.createScaledBitmap(back, width, height, true);
    }

    @Override
    void drawBackground(Canvas canvas, Layout layout) {
        ensureBackdrop(layout.width, layout.height);
        canvas.drawBitmap(backdrop, 0, 0, null);
        fill.setShader(null);
        fill.setColor(Color.BLACK);
        if (layout.height >= layout.width && layout.controlsVisible) {
            canvas.drawRect(0, layout.screen.top, layout.width, layout.screen.bottom, fill);
        } else {
            canvas.drawRect(layout.screen, fill);
        }
    }

    // ---- Controls ----

    @Override
    void drawControls(Canvas canvas, Layout layout, int pressed, Motion motion) {
        ensureBackdrop(layout.width, layout.height);
        float density = Math.max(1, layout.width / 400f);
        for (int i = 0; i < layout.controls.size(); i++) {
            Control control = layout.controls.get(i);
            if (!control.visible) continue;
            float press = Math.max(0, Math.min(1.2f, motion.press(i)));
            if (control.shape == Control.DPAD) {
                drawDpad(canvas, control.bounds, pressed & control.keys, motion.tiltX, motion.tiltY, density);
            } else {
                drawButton(canvas, control, press, density);
            }
        }
    }

    private void drawDpad(Canvas canvas, RectF b, int held, float tiltX, float tiltY, float density) {
        float size = b.width();
        float arm = size * 0.34f;
        Path shape = roundedPlus(b, arm, arm * 0.26f);
        // A soft shadow under the glass.
        drawShadow(canvas, shape, density);
        // The scene through the glass, shifted the way the pad leans.
        drawGlass(canvas, shape, b, -tiltX * size * REFRACTION, -tiltY * size * REFRACTION, 1f);
        // The pressed arm catches the light.
        canvas.save();
        canvas.clipPath(shape);
        float armLength = (size - arm) / 2;
        float cx = b.centerX();
        float cy = b.centerY();
        if ((held & Emulator.KEY_UP) != 0) glint(canvas, cx - arm / 2, b.top, cx + arm / 2, b.top + armLength, 0, -1);
        if ((held & Emulator.KEY_DOWN) != 0) glint(canvas, cx - arm / 2, b.bottom - armLength, cx + arm / 2, b.bottom, 0, 1);
        if ((held & Emulator.KEY_LEFT) != 0) glint(canvas, b.left, cy - arm / 2, b.left + armLength, cy + arm / 2, -1, 0);
        if ((held & Emulator.KEY_RIGHT) != 0) glint(canvas, b.right - armLength, cy - arm / 2, b.right, cy + arm / 2, 1, 0);
        // The glare: a bright diagonal streak that slides across as the pad tilts.
        drawGlare(canvas, b, tiltX, tiltY);
        canvas.restore();
        drawRim(canvas, shape, b, density);
        // Arrows etched into the glass.
        fill.setShader(null);
        fill.setColor(variant.label);
        float tip = armLength * 0.4f;
        float half = arm * 0.18f;
        arrow(canvas, cx, b.top + armLength * 0.34f, 0, -1, tip, half);
        arrow(canvas, cx, b.bottom - armLength * 0.34f, 0, 1, tip, half);
        arrow(canvas, b.left + armLength * 0.34f, cy, -1, 0, tip, half);
        arrow(canvas, b.right - armLength * 0.34f, cy, 1, 0, tip, half);
    }

    private void drawButton(Canvas canvas, Control control, float press, float density) {
        RectF b = control.bounds;
        boolean shoulder = control.shape == Control.SHOULDER;
        boolean pill = control.shape == Control.PILL || shoulder;
        float shrink = 1 - 0.035f * Math.min(1, press);
        float halfWidth = b.width() / 2 * shrink;
        float halfHeight = b.height() / 2 * shrink;
        rect.set(b.centerX() - halfWidth, b.centerY() - halfHeight, b.centerX() + halfWidth, b.centerY() + halfHeight);
        Path shape = new Path();
        float corner = pill ? rect.height() / 2 : rect.width() / 2;
        shape.addRoundRect(rect, corner, corner, Path.Direction.CW);
        drawShadow(canvas, shape, density);
        // Pressing pushes the glass in: the scene through it magnifies a little and shifts down.
        drawGlass(canvas, shape, rect, 0, rect.height() * 0.08f * press, 1 + 0.08f * press);
        canvas.save();
        canvas.clipPath(shape);
        // The reflection slides down as it's pressed.
        drawGlare(canvas, rect, 0, press * 0.9f);
        canvas.restore();
        drawRim(canvas, shape, rect, density);
        if (shoulder) {
            text.setColor(variant.label);
            text.setTextSize(rect.height() * 0.6f);
            canvas.drawText(control.keys == Emulator.KEY_L ? "L" : "R", rect.centerX(),
                    rect.centerY() - (text.descent() + text.ascent()) / 2, text);
            return;
        }
        if (pill) {
            text.setColor(withAlpha(variant.label, 0xC0));
            text.setTextSize(b.height() * 0.62f);
            canvas.drawText(control.keys == Emulator.KEY_START ? "START" : "SELECT", b.centerX(),
                    b.bottom + b.height() * 1.05f, text);
            return;
        }
        if (control.keys == KEY_MENU) {
            drawMenuGlyph(canvas, stroke, rect.centerX(), rect.centerY(), rect.width() * 0.46f, variant.label);
            stroke.setStrokeCap(Paint.Cap.BUTT);
            return;
        }
        String letter = control.keys == Emulator.KEY_A ? "A" : control.keys == Emulator.KEY_B ? "B" : "";
        text.setColor(variant.label);
        text.setTextSize(rect.width() * 0.5f);
        text.setShadowLayer(density * 2, 0, density, 0x40000000);
        canvas.drawText(letter, rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2, text);
        text.clearShadowLayer();
    }

    /** The plus shape with rounded corners. */
    private static Path roundedPlus(RectF b, float arm, float corner) {
        Path out = new Path();
        Paint rounding = new Paint();
        rounding.setStyle(Paint.Style.FILL);
        rounding.setPathEffect(new CornerPathEffect(corner));
        rounding.getFillPath(SoftSkin.plusPath(b, arm), out);
        return out;
    }

    private void drawShadow(Canvas canvas, Path shape, float density) {
        canvas.save();
        canvas.translate(0, 4 * density);
        fill.setShader(null);
        fill.setColor(0x30000000);
        canvas.drawPath(shape, fill);
        canvas.restore();
    }

    /**
     * Frosted glass: the blurred backdrop behind {@code shape}, moved by (shiftX, shiftY) and
     * magnified by {@code zoom} about its middle (refraction), lightened and tinted.
     */
    private void drawGlass(Canvas canvas, Path shape, RectF bounds, float shiftX, float shiftY, float zoom) {
        matrix.reset();
        matrix.postScale(zoom, zoom, bounds.centerX(), bounds.centerY());
        matrix.postTranslate(shiftX, shiftY);
        frostedShader.setLocalMatrix(matrix);
        fill.setColor(Color.BLACK);
        fill.setShader(frostedShader);
        canvas.drawPath(shape, fill);
        // Frost: glass scatters light, so it's lighter than what's behind it; then the glass's own colour.
        fill.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom, 0x40FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawPath(shape, fill);
        fill.setShader(null);
        fill.setColor(variant.tint);
        canvas.drawPath(shape, fill);
    }

    /** A bright streak across the glass, moving with (tiltX, tiltY) from -1 to 1. */
    private void drawGlare(Canvas canvas, RectF b, float tiltX, float tiltY) {
        float size = Math.max(b.width(), b.height());
        // The streak runs from top-left to bottom-right; it moves across as the glass tilts.
        float offset = (tiltX + tiltY) * 0.5f * size * GLARE_TRAVEL;
        float x0 = b.left + size * 0.1f + offset;
        float y0 = b.top + size * 0.1f + offset;
        float x1 = x0 + size * 0.45f;
        float y1 = y0 + size * 0.45f;
        fill.setShader(new LinearGradient(x0, y0, x1, y1,
                new int[] {0x00FFFFFF, 0x18FFFFFF, 0xA0FFFFFF, 0x18FFFFFF, 0x00FFFFFF},
                new float[] {0f, 0.3f, 0.42f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(b.left - size, b.top - size, b.right + size, b.bottom + size, fill);
        // A softer sheen over the top half, like light on a curved surface.
        fill.setShader(new LinearGradient(0, b.top, 0, b.centerY() + tiltY * b.height() * 0.15f,
                0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(b, fill);
        fill.setShader(null);
    }

    /** The light a pressed arm catches: brightest at its tip. */
    private void glint(Canvas canvas, float left, float top, float right, float bottom, int dx, int dy) {
        float cx = (left + right) / 2;
        float cy = (top + bottom) / 2;
        float tipX = dx < 0 ? left : dx > 0 ? right : cx;
        float tipY = dy < 0 ? top : dy > 0 ? bottom : cy;
        fill.setShader(new LinearGradient(tipX, tipY, cx - dx * (right - left) * 0.3f, cy - dy * (bottom - top) * 0.3f,
                0xD8FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(left, top, right, bottom, fill);
        // A bright line where the pressed edge catches the light.
        float edge = Math.max(right - left, bottom - top) * 0.06f;
        fill.setShader(null);
        fill.setColor(0xF0FFFFFF);
        if (dx < 0) canvas.drawRect(left, top, left + edge, bottom, fill);
        if (dx > 0) canvas.drawRect(right - edge, top, right, bottom, fill);
        if (dy < 0) canvas.drawRect(left, top, right, top + edge, fill);
        if (dy > 0) canvas.drawRect(left, bottom - edge, right, bottom, fill);
    }

    /** A glass edge: bright where the light is (top left), fading round to a darker lower edge. */
    private void drawRim(Canvas canvas, Path shape, RectF b, float density) {
        stroke.setStrokeWidth(1.8f * density);
        stroke.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[] {0xE0FFFFFF, 0x50FFFFFF, 0x30FFFFFF, 0x70FFFFFF},
                new float[] {0f, 0.45f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(shape, stroke);
        stroke.setShader(null);
    }

    private void arrow(Canvas canvas, float x, float y, int dx, int dy, float length, float half) {
        Path path = new Path();
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

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
