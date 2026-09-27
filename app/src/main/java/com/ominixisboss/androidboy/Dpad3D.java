package com.ominixisboss.androidboy;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import org.json.JSONObject;

/**
 * A d-pad drawn live over an image skin's picture, so it moves in 3D: a solid cross that rocks
 * towards the held direction, its side walls showing, or an arcade joystick whose ball-top stick
 * leans the way it's pushed. Image skins ask for one with {@code "dpadStyle"}; their picture then
 * only shows what's under the pad (its well, or the joystick's base plate).
 */
final class Dpad3D {
    static final String CROSS = "cross";
    static final String JOYSTICK = "joystick";

    /** How thick the cross is, in d-pad radii: its sides show as it rocks. */
    private static final float THICKNESS = 0.2f;
    private static final int WALL_LAYERS = 8;
    /** How far the pad sinks when held, in d-pad radii. */
    private static final float SINK = 0.06f;
    /** How far the joystick's ball travels when pushed all the way, in d-pad radii. */
    static final float STICK_TRAVEL = 0.3f;
    /** Where the ball rests: above the pivot, so a little of the shaft shows beneath it. */
    static final float REST_Y = -0.3f;

    final String style;
    /** The cross's (or the joystick's shaft's) colour. */
    final int color;
    final int arrows;
    /** An outline around the cross; 0 for none. */
    final int outline;
    /** Flat colours, no gradients: for pixel-art skins. */
    final boolean flat;
    /** The joystick's ball. */
    final int ball;
    /** How much of the control's square the cross (or the stick's reach) fills. */
    final float size;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final float[] scratch = new float[8];
    private final Path cross = new Path();
    private final RectF rect = new RectF();
    private Shader faceShader;
    private Shader ballShader;
    private Shader shaftShader;

    Dpad3D(String style, int color, int arrows, int outline, boolean flat, int ball, float size) {
        this.style = style;
        this.color = color;
        this.arrows = arrows;
        this.outline = outline;
        this.flat = flat;
        this.ball = ball;
        this.size = size;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        // The cross in unit coordinates, as one outline so strokes don't cross in the middle.
        float arm = 0.36f;
        rect.set(-arm, -1, arm, 1);
        cross.addRoundRect(rect, 0.12f, 0.12f, Path.Direction.CW);
        Path across = new Path();
        rect.set(-1, -arm, 1, arm);
        across.addRoundRect(rect, 0.12f, 0.12f, Path.Direction.CW);
        cross.op(across, Path.Op.UNION);
    }

    /** Reads {@code "dpadStyle"} from a skin.json; see docs/skins.md. */
    static Dpad3D parse(JSONObject json) throws java.io.IOException {
        String style = json.optString("style", CROSS);
        if (!style.equals(CROSS) && !style.equals(JOYSTICK)) {
            throw new java.io.IOException("dpadStyle: \"style\" must be \"cross\" or \"joystick\"");
        }
        int color = color(json, "color", 0xFF2A2A30);
        float size = (float) json.optDouble("size", style.equals(CROSS) ? 0.86 : 1);
        if (size <= 0.2f || size > 1.2f) throw new java.io.IOException("dpadStyle: \"size\" must be between 0.2 and 1.2");
        return new Dpad3D(style, color, color(json, "arrows", ThemeSkin.shade(color, 0.45f)),
                json.has("outline") ? color(json, "outline", 0) : 0, json.optBoolean("flat", false),
                color(json, "ball", 0xFFE02838), size);
    }

    private static int color(JSONObject json, String key, int fallback) throws java.io.IOException {
        if (!json.has(key)) return fallback;
        try {
            return Color.parseColor(json.optString(key)) | 0xFF000000;
        } catch (IllegalArgumentException e) {
            throw new java.io.IOException("dpadStyle: \"" + key + "\" isn't a colour like #102030");
        }
    }

    void draw(Canvas canvas, RectF bounds, float tiltX, float tiltY) {
        canvas.save();
        canvas.translate(bounds.centerX(), bounds.centerY());
        float radius = bounds.width() / 2 * size;
        canvas.scale(radius, radius);
        if (style.equals(JOYSTICK)) {
            drawJoystick(canvas, tiltX, tiltY);
        } else {
            drawCross(canvas, tiltX, tiltY);
        }
        canvas.restore();
    }

    // ---- The cross ----

    private void rock(Canvas canvas, float tiltX, float tiltY, float depth) {
        ThemeSkin.rock(canvas, matrix, scratch, tiltX, tiltY, Skin.DPAD_TILT_DEGREES * 1.6f, depth);
    }

    private void drawCross(Canvas canvas, float tiltX, float tiltY) {
        float amount = Math.min(1f, (float) Math.hypot(tiltX, tiltY));
        float sink = SINK * amount;

        // Its shadow in the well, cast from the bottom of the pad, so it moves with the tilt.
        canvas.save();
        canvas.translate(0.03f, 0.07f);
        rock(canvas, tiltX, tiltY, sink + THICKNESS + 0.05f);
        fill.setShader(null);
        fill.setColor(0x66000000);
        canvas.drawPath(cross, fill);
        canvas.restore();

        // The sides: slices from the bottom up, darker further down. The side that lifts swings
        // out from under the face; the side that's pressed tucks away beneath it.
        for (int i = 0; i < WALL_LAYERS; i++) {
            float t = (float) i / WALL_LAYERS;
            canvas.save();
            rock(canvas, tiltX, tiltY, sink + THICKNESS * (1 - t));
            fill.setColor(ThemeSkin.shade(color, flat ? 0.5f : 0.3f + 0.3f * t));
            canvas.drawPath(cross, fill);
            if (outline != 0 && i == 0) {
                stroke.setColor(outline);
                stroke.setStrokeWidth(0.07f);
                canvas.drawPath(cross, stroke);
            }
            canvas.restore();
        }

        // The face.
        canvas.save();
        rock(canvas, tiltX, tiltY, sink);
        if (flat) {
            fill.setColor(color);
        } else {
            if (faceShader == null) {
                faceShader = new LinearGradient(0, -1, 0, 1, ThemeSkin.lighten(color, 0.22f),
                        ThemeSkin.shade(color, 0.82f), Shader.TileMode.CLAMP);
            }
            fill.setShader(faceShader);
        }
        canvas.drawPath(cross, fill);
        fill.setShader(null);
        if (amount > 0.01f) {
            // Light falls off across the face towards the held side, and catches the raised side.
            float nx = tiltX / (float) Math.hypot(tiltX, tiltY);
            float ny = tiltY / (float) Math.hypot(tiltX, tiltY);
            fill.setShader(new LinearGradient(nx, ny, -nx, -ny,
                    new int[] {Color.argb((int) (120 * amount), 0, 0, 0), 0x00000000,
                            Color.argb((int) (70 * amount), 255, 255, 255)},
                    null, Shader.TileMode.CLAMP));
            canvas.drawPath(cross, fill);
            fill.setShader(null);
        }
        // Arrows moulded into the arms: a dark recess over a light lower edge.
        for (int direction = 0; direction < 4; direction++) {
            if (!flat) {
                fill.setColor(ThemeSkin.lighten(color, 0.25f));
                arrow(canvas, direction, 0.024f);
            }
            fill.setColor(arrows);
            arrow(canvas, direction, 0);
        }
        if (!flat) {
            // The dimple in the middle, and a bright rim along the top edge of the face.
            fill.setShader(new RadialGradient(0, -0.04f, 0.2f, ThemeSkin.shade(color, 0.6f), ThemeSkin.lighten(color, 0.08f),
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(0, 0, 0.18f, fill);
            fill.setShader(null);
            stroke.setColor(0x50FFFFFF);
            stroke.setStrokeWidth(0.03f);
            canvas.save();
            canvas.translate(0, 0.015f);
            canvas.clipRect(-1.1f, -1.1f, 1.1f, 0.05f);
            canvas.drawPath(cross, stroke);
            canvas.restore();
        }
        stroke.setColor(outline != 0 ? outline : ThemeSkin.shade(color, 0.45f));
        stroke.setStrokeWidth(outline != 0 ? 0.07f : 0.025f);
        canvas.drawPath(cross, stroke);
        canvas.restore();
    }

    private final Path arrowPath = new Path();

    /** The arrowhead on arm {@code direction} (0 up, 1 down, 2 left, 3 right), {@code dy} lower. */
    private void arrow(Canvas canvas, int direction, float dy) {
        float tip = 0.82f;
        float base = 0.62f;
        float half = 0.12f;
        arrowPath.reset();
        switch (direction) {
            case 0:
                arrowPath.moveTo(0, -tip);
                arrowPath.lineTo(half, -base);
                arrowPath.lineTo(-half, -base);
                break;
            case 1:
                arrowPath.moveTo(0, tip);
                arrowPath.lineTo(half, base);
                arrowPath.lineTo(-half, base);
                break;
            case 2:
                arrowPath.moveTo(-tip, 0);
                arrowPath.lineTo(-base, half);
                arrowPath.lineTo(-base, -half);
                break;
            default:
                arrowPath.moveTo(tip, 0);
                arrowPath.lineTo(base, half);
                arrowPath.lineTo(base, -half);
                break;
        }
        arrowPath.close();
        canvas.save();
        canvas.translate(0, dy);
        canvas.drawPath(arrowPath, fill);
        canvas.restore();
    }

    // ---- The joystick ----

    /**
     * Where the ball's centre is for a tilt, in unit coordinates: pushed towards the held
     * direction, and at rest a little above the pivot (the stick seen from slightly in front).
     */
    static float[] ballCenter(float tiltX, float tiltY) {
        // Pushing diagonally goes no further than straight: the stick moves in a circular gate.
        float length = (float) Math.hypot(tiltX, tiltY);
        float scale = length > 1 ? 1 / length : 1;
        return new float[] {tiltX * scale * STICK_TRAVEL, REST_Y + tiltY * scale * STICK_TRAVEL * 0.85f};
    }

    private void drawJoystick(Canvas canvas, float tiltX, float tiltY) {
        float[] c = ballCenter(tiltX, tiltY);
        float bx = c[0];
        float by = c[1];
        float lean = Math.min(1f, (float) Math.hypot(tiltX, tiltY));
        float r = 0.32f;

        // The ball's shadow on the base plate, further off the more it leans.
        fill.setShader(null);
        fill.setColor(0x55000000);
        rect.set(bx * 1.25f - r * 0.95f + 0.05f, by * 0.4f + 0.2f, bx * 1.25f + r * 0.95f + 0.05f, by * 0.4f + 0.2f + r * 0.8f);
        canvas.drawOval(rect, fill);

        // The dust washer where the shaft goes into the panel.
        fill.setColor(0xFF141418);
        canvas.drawCircle(0, 0.1f, 0.2f, fill);
        fill.setColor(0xFF2A2A30);
        canvas.drawCircle(0, 0.08f, 0.16f, fill);

        // The shaft, from the pivot to the ball: metal, shaded across its width.
        float sx = 0;
        float sy = 0.1f;
        float angle = (float) Math.atan2(by - sy, bx - sx);
        float length = (float) Math.hypot(bx - sx, by - sy);
        canvas.save();
        canvas.translate(sx, sy);
        canvas.rotate((float) Math.toDegrees(angle) - 90 + 180);
        if (shaftShader == null) {
            shaftShader = new LinearGradient(-0.07f, 0, 0.07f, 0,
                    new int[] {ThemeSkin.shade(color, 0.55f), ThemeSkin.lighten(color, 0.55f), ThemeSkin.shade(color, 0.7f)},
                    new float[] {0, 0.35f, 1}, Shader.TileMode.CLAMP);
        }
        fill.setShader(shaftShader);
        rect.set(-0.07f, -length, 0.07f, 0.02f);
        canvas.drawRoundRect(rect, 0.05f, 0.05f, fill);
        fill.setShader(null);
        canvas.restore();

        // The ball: lit from above left, a glint, and a dark rim, a touch larger as it leans towards the viewer.
        float ballR = r * (1 + 0.04f * Math.max(0, tiltY) * lean);
        if (ballShader == null) {
            ballShader = new RadialGradient(-0.3f, -0.35f, 1.35f,
                    new int[] {ThemeSkin.lighten(ball, 0.55f), ball, ThemeSkin.shade(ball, 0.45f)},
                    new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
        }
        canvas.save();
        canvas.translate(bx, by);
        canvas.scale(ballR, ballR);
        fill.setShader(ballShader);
        canvas.drawCircle(0, 0, 1, fill);
        fill.setShader(null);
        fill.setColor(0xB0FFFFFF);
        rect.set(-0.55f, -0.72f, 0.05f, -0.32f);
        canvas.drawOval(rect, fill);
        stroke.setColor(ThemeSkin.shade(ball, 0.35f));
        stroke.setStrokeWidth(0.05f);
        canvas.drawCircle(0, 0, 0.98f, stroke);
        canvas.restore();
    }
}
