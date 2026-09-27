package com.ominixisboss.androidboy;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * A lifelike button, seen from a little in front: it sits in a socket in the shell, stands on a
 * short cylindrical side wall, and has a domed (round) or cushioned (pill) cap lit from the top
 * left, with a darker rim, light bouncing up from below and, for plastic, a sharp glint. Pressing
 * it pushes the cap down into its socket: the wall shortens, the shadow tightens and the glint
 * dims. Used by every skin that draws its buttons.
 */
final class Button3D {
    /** Glossy moulded plastic, like A and B. */
    static final int PLASTIC = 0;
    /** Matte rubber, like Start and Select. */
    static final int RUBBER = 1;

    /** How far the cap stands above its socket at rest, and sinks below it pressed, in radii. */
    private static final float LIFT = 0.075f;
    private static final float SINK = 0.035f;
    /** How far below the cap's centre the wall's foot is, in radii. */
    private static final float FOOT = 0.1f;

    final int color;
    final int material;
    /** An outline around the cap and wall (for drawn-in-ink styles); 0 for none. */
    int outline;
    float outlineWidth;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    Button3D(int color, int material) {
        this.color = color | 0xFF000000;
        this.material = material;
        stroke.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD));
    }

    Button3D outlined(int color, float width) {
        outline = color;
        outlineWidth = width;
        return this;
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    /** Where the cap's centre is, pressed {@code press}: above the socket at rest, a little below it held. */
    static float capY(float cy, float radius, float press) {
        float p = Math.max(-0.2f, Math.min(1.15f, press)); // The springs overshoot a little either way.
        return cy - radius * LIFT * (1 - p) + radius * SINK * p;
    }

    // ---- Round buttons ----

    /**
     * A round button centred on ({@code cx}, {@code cy}) with a cap of {@code radius}, pressed
     * {@code press} (0 to 1). {@code surround} is the shell's colour around it (0 if unknown).
     */
    void drawRound(Canvas canvas, float cx, float cy, float radius, float press, int surround) {
        float down = clamp01(press);
        float r = radius;
        float capY = capY(cy, r, press);
        float footY = cy + r * FOOT;

        // The socket: a dark gap around the button, shadowed under its top edge, with light
        // catching its lower lip.
        int hole = surround != 0 ? ThemeSkin.shade(surround, 0.42f) : 0xFF141418;
        shaded(new RadialGradient(cx, cy + r * 0.02f, r * 1.12f,
                new int[] {ThemeSkin.shade(hole, 0.55f), hole, withAlpha(hole, 0)}, new float[] {0, 0.9f, 1},
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy + r * 0.02f, r * 1.12f, fill);
        fill.setShader(null);
        stroke.setShader(null);
        stroke.setStrokeWidth(r * 0.05f);
        stroke.setColor(surround != 0 ? withAlpha(ThemeSkin.lighten(surround, 0.35f), 0x70) : 0x40FFFFFF);
        rect.set(cx - r * 1.1f, cy - r * 1.08f, cx + r * 1.1f, cy + r * 1.12f);
        canvas.drawArc(rect, 25, 130, false, stroke);

        // Its shadow on the socket floor, tighter and darker as it goes down.
        float spread = 1.08f - 0.06f * down;
        shaded(new RadialGradient(cx + r * 0.04f, footY + r * 0.06f, r * spread,
                new int[] {0xA0000000, 0x80000000, 0x00000000}, new float[] {0, 0.82f, 1}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx + r * 0.04f, footY + r * 0.06f, r * spread, fill);
        fill.setShader(null);

        // The side wall, from the cap down to its foot: shaded round the cylinder, darker lower down.
        path.reset();
        path.addCircle(cx, footY, r, Path.Direction.CW);
        rect.set(cx - r, capY, cx + r, footY);
        path.addRect(rect, Path.Direction.CW);
        shaded(new LinearGradient(cx - r, 0, cx + r, 0,
                new int[] {ThemeSkin.shade(color, 0.42f), ThemeSkin.shade(color, 0.72f), ThemeSkin.shade(color, 0.6f),
                        ThemeSkin.shade(color, 0.38f)},
                new float[] {0, 0.32f, 0.68f, 1}, Shader.TileMode.CLAMP));
        canvas.drawPath(path, fill);
        shaded(new LinearGradient(0, capY, 0, footY + r, 0x00000000, 0x60000000, Shader.TileMode.CLAMP));
        canvas.drawPath(path, fill);
        fill.setShader(null);
        if (outline != 0) {
            stroke.setColor(outline);
            stroke.setStrokeWidth(outlineWidth);
            canvas.drawCircle(cx, footY, r, stroke);
            canvas.drawLine(cx - r, capY, cx - r, footY, stroke);
            canvas.drawLine(cx + r, capY, cx + r, footY, stroke);
        }

        // The cap: domed, lit from the top left.
        float cr = r * 0.985f;
        boolean plastic = material == PLASTIC;
        shaded(new RadialGradient(cx - cr * 0.34f, capY - cr * 0.42f, cr * 1.6f,
                new int[] {ThemeSkin.lighten(color, plastic ? 0.4f : 0.22f), ThemeSkin.lighten(color, 0.08f), color,
                        ThemeSkin.shade(color, 0.7f)},
                new float[] {0, 0.3f, 0.62f, 1}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, capY, cr, fill);
        fill.setShader(null);
        if (down > 0.01f) {
            fill.setColor(Color.argb((int) (45 * down), 0, 0, 0));
            canvas.drawCircle(cx, capY, cr, fill);
        }
        // Light bouncing up off the shell onto the lower curve.
        stroke.setStrokeWidth(cr * 0.09f);
        stroke.setColor(Color.argb(plastic ? 46 : 30, 255, 255, 255));
        rect.set(cx - cr * 0.84f, capY - cr * 0.84f, cx + cr * 0.84f, capY + cr * 0.84f);
        canvas.drawArc(rect, 35, 110, false, stroke);
        // The rim, where the dome turns down into the wall.
        stroke.setStrokeWidth(cr * 0.06f);
        stroke.setColor(withAlpha(ThemeSkin.shade(color, 0.5f), 0xB0));
        canvas.drawCircle(cx, capY, cr * 0.97f, stroke);

        // Highlights: a soft window reflection and, on plastic, a sharp glint, dimming as it goes down.
        float bright = 1 - 0.45f * down;
        canvas.save();
        canvas.rotate(-32, cx - cr * 0.33f, capY - cr * 0.52f);
        shaded(new RadialGradient(cx - cr * 0.33f, capY - cr * 0.52f, cr * 0.42f,
                Color.argb((int) ((plastic ? 120 : 60) * bright), 255, 255, 255), 0x00FFFFFF, Shader.TileMode.CLAMP));
        rect.set(cx - cr * 0.75f, capY - cr * 0.8f, cx + cr * 0.09f, capY - cr * 0.24f);
        canvas.drawOval(rect, fill);
        fill.setShader(null);
        if (plastic) {
            fill.setColor(Color.argb((int) (215 * bright), 255, 255, 255));
            rect.set(cx - cr * 0.5f, capY - cr * 0.64f, cx - cr * 0.18f, capY - cr * 0.44f);
            canvas.drawOval(rect, fill);
        }
        canvas.restore();
        if (plastic) {
            // A faint second glint low on the right, from light behind the player.
            fill.setColor(Color.argb((int) (55 * bright), 255, 255, 255));
            rect.set(cx + cr * 0.3f, capY + cr * 0.42f, cx + cr * 0.56f, capY + cr * 0.56f);
            canvas.drawOval(rect, fill);
        }
        if (outline != 0) {
            stroke.setColor(outline);
            stroke.setStrokeWidth(outlineWidth);
            canvas.drawCircle(cx, capY, cr, stroke);
        }
    }

    /**
     * A letter moulded into a round cap: a dark upper edge, a light lower edge and the letter in
     * {@code ink}. Call with the same values as {@link #drawRound}.
     */
    void drawLetter(Canvas canvas, String letter, float cx, float cy, float radius, float press, int ink) {
        float y = capY(cy, radius, press);
        text.setTextSize(radius * 0.92f);
        float baseline = y - (text.descent() + text.ascent()) / 2;
        boolean light = ThemeSkin.luminance(ink) > 0.5f;
        text.setColor(light ? 0x50000000 : 0x60FFFFFF);
        canvas.drawText(letter, cx, baseline + radius * (light ? -0.035f : 0.045f), text);
        text.setColor(light ? 0x38FFFFFF : 0x70000000);
        canvas.drawText(letter, cx, baseline + radius * (light ? 0.045f : -0.035f), text);
        text.setColor(ink);
        canvas.drawText(letter, cx, baseline, text);
    }

    // ---- Pills ----

    /**
     * A pill-shaped button filling {@code bounds} (wider than tall), pressed {@code press}. The
     * caller rotates the canvas for a slanted one. Returns the cap's centre y, for a label on it.
     */
    float drawPill(Canvas canvas, RectF bounds, float press, int surround) {
        float down = clamp01(press);
        float h = bounds.height() / 2;
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        float p = Math.max(-0.2f, Math.min(1.15f, press));
        float capY = cy - h * 0.16f * (1 - p) + h * 0.07f * p;
        float footY = cy + h * 0.22f;
        float halfWidth = bounds.width() / 2;

        // The socket.
        int hole = surround != 0 ? ThemeSkin.shade(surround, 0.42f) : 0xFF141418;
        float grow = h * 0.3f;
        rect.set(cx - halfWidth - grow, cy - h - grow * 0.8f, cx + halfWidth + grow, cy + h + grow * 1.2f);
        fill.setColor(withAlpha(hole, 0xD0));
        canvas.drawRoundRect(rect, h + grow, h + grow, fill);
        stroke.setStrokeWidth(h * 0.1f);
        stroke.setColor(surround != 0 ? withAlpha(ThemeSkin.lighten(surround, 0.35f), 0x60) : 0x38FFFFFF);
        canvas.save();
        canvas.clipRect(rect.left, rect.centerY(), rect.right, rect.bottom + h);
        canvas.drawRoundRect(rect, h + grow, h + grow, stroke);
        canvas.restore();

        // Its shadow, then the wall.
        fill.setColor(Color.argb((int) (120 - 30 * down), 0, 0, 0));
        rect.set(cx - halfWidth + h * 0.05f, footY - h + h * 0.12f, cx + halfWidth + h * 0.08f, footY + h + h * 0.12f);
        canvas.drawRoundRect(rect, h, h, fill);
        rect.set(cx - halfWidth, footY - h, cx + halfWidth, footY + h);
        shaded(new LinearGradient(0, capY, 0, footY + h, ThemeSkin.shade(color, 0.62f),
                ThemeSkin.shade(color, 0.38f), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, h, h, fill);
        rect.set(cx - halfWidth, capY, cx + halfWidth, footY);
        canvas.drawRect(rect, fill);
        fill.setShader(null);
        if (outline != 0) {
            stroke.setColor(outline);
            stroke.setStrokeWidth(outlineWidth);
            rect.set(cx - halfWidth, footY - h, cx + halfWidth, footY + h);
            canvas.drawRoundRect(rect, h, h, stroke);
        }

        // The cap: cushioned, lighter on top, with a soft sheen along its upper edge.
        rect.set(cx - halfWidth, capY - h, cx + halfWidth, capY + h);
        boolean plastic = material == PLASTIC;
        shaded(new LinearGradient(0, rect.top, 0, rect.bottom,
                new int[] {ThemeSkin.lighten(color, plastic ? 0.3f : 0.2f), color, ThemeSkin.shade(color, 0.74f)},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, h, h, fill);
        fill.setShader(null);
        if (down > 0.01f) {
            fill.setColor(Color.argb((int) (45 * down), 0, 0, 0));
            canvas.drawRoundRect(rect, h, h, fill);
        }
        float bright = 1 - 0.45f * down;
        fill.setColor(Color.argb((int) ((plastic ? 110 : 55) * bright), 255, 255, 255));
        RectF sheen = new RectF(rect.left + h * 0.55f, rect.top + h * 0.2f, rect.right - h * 0.55f, rect.top + h * 0.62f);
        canvas.drawRoundRect(sheen, sheen.height() / 2, sheen.height() / 2, fill);
        stroke.setStrokeWidth(h * 0.08f);
        stroke.setColor(withAlpha(ThemeSkin.shade(color, 0.5f), 0xA0));
        canvas.drawRoundRect(rect, h, h, stroke);
        if (outline != 0) {
            stroke.setColor(outline);
            stroke.setStrokeWidth(outlineWidth);
            canvas.drawRoundRect(rect, h, h, stroke);
        }
        return capY;
    }

    // ---- Symbols ----

    /** The menu (☰), rewind (◀◀) or fast-forward (▶▶) symbol, {@code size} across, centred on (cx, cy). */
    static void drawIcon(Canvas canvas, Paint paint, int keys, float cx, float cy, float size, int color) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        float s = size / 2;
        if (keys == Skin.KEY_MENU) {
            for (int i = -1; i <= 1; i++) {
                float y = cy + i * s * 0.6f;
                canvas.drawRoundRect(cx - s, y - s * 0.14f, cx + s, y + s * 0.14f, s * 0.14f, s * 0.14f, paint);
            }
            return;
        }
        float direction = keys == Skin.KEY_REWIND ? -1 : 1;
        Path triangle = new Path();
        for (int i = 0; i < 2; i++) {
            float tx = cx + direction * (i - 0.5f) * s * 0.95f;
            triangle.reset();
            triangle.moveTo(tx + direction * s * 0.62f, cy);
            triangle.lineTo(tx - direction * s * 0.42f, cy - s * 0.72f);
            triangle.lineTo(tx - direction * s * 0.42f, cy + s * 0.72f);
            triangle.close();
            canvas.drawPath(triangle, paint);
        }
    }

    /** Fills with a gradient; the paint's colour alpha would otherwise still apply to it. */
    private void shaded(Shader shader) {
        fill.setColor(Color.BLACK);
        fill.setShader(shader);
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
