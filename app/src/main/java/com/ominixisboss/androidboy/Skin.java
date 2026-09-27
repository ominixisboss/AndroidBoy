package com.ominixisboss.androidboy;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * The look of the game screen: where the screen and the touch controls go, and how they're drawn.
 * Built-in themes are {@link ThemeSkin}s; skins imported from files are {@link ImageSkin}s.
 */
abstract class Skin {
    // Pseudo-keys handled by the frontend, never sent to the core.
    static final int KEY_MENU = 1 << 16;
    static final int KEY_FAST_FORWARD = 1 << 17;
    static final int KEY_REWIND = 1 << 18;
    /** Names the speaker grille for the layout editor; it's never pressed. */
    static final int KEY_SPEAKER = 1 << 19;
    /** Keys that go to the game: the Game Boy's eight, plus L and R for Game Boy Advance games. */
    static final int GAME_KEYS = 0x3FF;

    /** A touch control. Bounds are in view pixels. */
    static final class Control {
        static final int DPAD = 0;
        static final int CIRCLE = 1;
        static final int PILL = 2;
        static final int RECT = 3;
        /** A Game Boy Advance shoulder button (L or R), added by {@link #addShoulderButtons}. */
        static final int SHOULDER = 4;
        /**
         * A speaker grille: nothing happens when it's touched, but it can be moved and resized
         * with the buttons. Its keys are {@link #KEY_SPEAKER}, which is never pressed.
         */
        static final int SPEAKER = 5;

        final int keys;
        final int shape;
        final RectF bounds;
        /** Invisible controls only exist for touch, like the A+B spot between the two buttons. */
        final boolean visible;

        Control(int keys, int shape, RectF bounds, boolean visible) {
            this.keys = keys;
            this.shape = shape;
            this.bounds = bounds;
            this.visible = visible;
        }
    }

    /** The result of laying out a skin for one view size. */
    static final class Layout {
        int width;
        int height;
        boolean controlsVisible;
        final Rect screen = new Rect();
        final List<Control> controls = new ArrayList<>();
        /** Skin-specific extras computed during layout (e.g. bezel geometry). */
        Object extras;
        /**
         * Given by the view, and kept through {@link #reset}: how far down from the top the camera
         * cutout (or anything else the screen and controls shouldn't go under) reaches, and the
         * camera's own bounds (empty if there's no cutout at the top). The skin's body still
         * reaches up behind it.
         */
        int topInset;
        final RectF topCutout = new RectF();

        void reset(int w, int h, boolean controls) {
            width = w;
            height = h;
            controlsVisible = controls;
            screen.setEmpty();
            this.controls.clear();
            extras = null;
        }
    }

    /**
     * Animation state for the controls, driven by {@link SkinView}. Press values are 0 at rest and
     * 1 fully pressed; the springs overshoot a little either side, which skins show as a bounce.
     */
    static final class Motion {
        /** Press progress for each control, indexed like {@link Layout#controls}. */
        float[] press = new float[0];
        /** D-pad tilt towards the held direction: -1 (left/up) to 1 (right/down). */
        float tiltX;
        float tiltY;

        /** Press progress of control {@code index}, or 0 if it isn't animated. */
        float press(int index) {
            return index < press.length ? press[index] : 0;
        }

        /** Sets every value to where it would settle for the {@code pressed} key mask. */
        void snap(Layout layout, int pressed) {
            ensureSize(layout.controls.size());
            for (int i = 0; i < press.length; i++) press[i] = isDown(layout.controls.get(i), pressed) ? 1 : 0;
            tiltX = tiltTargetX(pressed);
            tiltY = tiltTargetY(pressed);
        }

        void ensureSize(int count) {
            if (press.length != count) press = new float[count];
        }
    }

    /** Whether a control shows as held: the d-pad for any direction, others (like "ab") need all their keys. */
    static boolean isDown(Control control, int pressed) {
        return control.shape == Control.DPAD
                ? (pressed & control.keys) != 0
                : (pressed & control.keys) == control.keys;
    }

    static float tiltTargetX(int pressed) {
        return ((pressed & Emulator.KEY_RIGHT) != 0 ? 1 : 0) - ((pressed & Emulator.KEY_LEFT) != 0 ? 1 : 0);
    }

    static float tiltTargetY(int pressed) {
        return ((pressed & Emulator.KEY_DOWN) != 0 ? 1 : 0) - ((pressed & Emulator.KEY_UP) != 0 ? 1 : 0);
    }

    abstract String id();

    /**
     * Whether the game screen shows the toolbars with this skin (they fade out; its menu button
     * or Back brings them back). Other skins' menu buttons open the menu straight away.
     */
    boolean usesToolbars() {
        return false;
    }

    /** Whether the background moves (an animated backdrop), so the view keeps redrawing. */
    boolean animated() {
        return false;
    }

    /** Whether the player can move and resize the controls (not when they're part of a picture). */
    boolean movableControls() {
        return true;
    }

    abstract String name();

    /** Color shown around the skin, e.g. behind display cutouts. */
    abstract int backgroundColor();

    /**
     * Lays out the skin in a {@code width}×{@code height} view for a frame of
     * {@code frameWidth}×{@code frameHeight}. With {@code controlsVisible} false (a gamepad is in use),
     * the screen should get as much room as possible and no controls are laid out.
     */
    abstract void layout(Layout out, int width, int height, int frameWidth, int frameHeight,
                         boolean controlsVisible, boolean integerScaling);

    /**
     * Whether {@link #layout} places everything around {@link Layout#topInset} itself. Otherwise
     * the view lays the skin out in the space below it and moves it down with {@link #moveDown},
     * so the body still covers the top.
     */
    boolean handlesTopInset() {
        return false;
    }

    /**
     * Moves a layout made for the space below a top inset down into place: the screen, controls
     * and extras shift down by {@code dy} and the body covers the whole view, up behind the cutout.
     */
    final void moveDown(Layout out, int dy) {
        if (dy == 0) return;
        out.height += dy;
        out.screen.offset(0, dy);
        for (Control control : out.controls) control.bounds.offset(0, dy);
        offsetExtras(out.extras, dy);
    }

    /** Shifts this skin's {@link Layout#extras} down by {@code dy}, for {@link #moveDown}. */
    void offsetExtras(Object extras, float dy) {
    }

    /** Draws everything except the game screen (the caller clips it out) and the controls. */
    abstract void drawBackground(Canvas canvas, Layout layout);

    /**
     * Draws the touch controls. {@code pressed} is the mask of held keys, including pseudo-keys;
     * {@code motion} says how far each control has moved, for animating presses.
     */
    abstract void drawControls(Canvas canvas, Layout layout, int pressed, Motion motion);

    // Every skin's d-pad rocks the same way when a direction is held: tipped slightly towards it,
    // in perspective, as seen from a little in front (what the Clear skins introduced).
    /** How far the d-pad tips towards the held direction. */
    static final float DPAD_TILT_DEGREES = 5f;
    /** The viewer's distance, in d-pad radii: further is flatter. */
    static final float DPAD_VIEW_DISTANCE = 9f;
    /** Deeper parts show this much lower down (how thick sides show under the face). */
    static final float DPAD_VIEW_SLANT = 0.5f;

    /**
     * Rocks the canvas like the d-pad in {@code bounds}, for drawing it (or parts of it) tilted by
     * (tiltX, tiltY), each -1 to 1. Does nothing at rest. {@code matrix} and {@code scratch}
     * (8 floats) are the caller's, reused between frames.
     */
    static void tiltDpad(Canvas canvas, RectF bounds, float tiltX, float tiltY, android.graphics.Matrix matrix,
                         float[] scratch) {
        if (tiltX == 0 && tiltY == 0) return;
        float radius = Math.max(1, bounds.width() / 2);
        canvas.translate(bounds.centerX(), bounds.centerY());
        canvas.scale(radius, radius);
        ThemeSkin.rock(canvas, matrix, scratch, tiltX, tiltY, DPAD_TILT_DEGREES, 0, DPAD_VIEW_DISTANCE, DPAD_VIEW_SLANT);
        canvas.scale(1 / radius, 1 / radius);
        canvas.translate(-bounds.centerX(), -bounds.centerY());
    }

    /** A menu glyph: three short lines, centred on (cx, cy) and {@code size} wide. */
    static void drawMenuGlyph(Canvas canvas, android.graphics.Paint stroke, float cx, float cy, float size, int color) {
        stroke.setShader(null);
        stroke.setColor(color);
        stroke.setStyle(android.graphics.Paint.Style.STROKE);
        stroke.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        stroke.setStrokeWidth(size * 0.14f);
        float half = size / 2;
        for (int i = -1; i <= 1; i++) {
            float y = cy + i * size * 0.32f;
            canvas.drawLine(cx - half, y, cx + half, y, stroke);
        }
    }

    /**
     * Adds the Game Boy Advance's L and R buttons to a laid-out skin: L above the d-pad and R
     * above A and B, in the space between them and whatever is above (the screen, or other
     * buttons in the same column), so any skin gets them without a layout of its own.
     */
    static void addShoulderButtons(Layout out) {
        if (!out.controlsVisible) return;
        RectF dpad = null;
        RectF buttons = null;
        for (Control control : out.controls) {
            if (!control.visible) continue;
            if (control.shape == Control.DPAD) dpad = control.bounds;
            if (control.keys == Emulator.KEY_A || control.keys == Emulator.KEY_B) {
                if (buttons == null) buttons = new RectF(control.bounds); else buttons.union(control.bounds);
            }
        }
        if (dpad == null || buttons == null) return;
        // Above the d-pad and the buttons, like the real shoulders; below them if a skin leaves no room above.
        RectF left = shoulderSpace(out, dpad, true);
        RectF right = shoulderSpace(out, buttons, true);
        float above = Math.min(left.height(), right.height());
        RectF lowerLeft = shoulderSpace(out, dpad, false);
        RectF lowerRight = shoulderSpace(out, buttons, false);
        if (Math.min(lowerLeft.height(), lowerRight.height()) > above * 1.5f) {
            left = lowerLeft;
            right = lowerRight;
        }
        // Both the same size, from the smaller of the two spaces, so they match.
        float unit = Math.min(out.width, out.height);
        float height = Math.min(Math.min(left.height(), right.height()) * 0.6f, unit * 0.075f);
        float width = Math.min(height * 3.2f, Math.min(left.width(), right.width()));
        // Level with each other when both columns have room at the same height.
        float top = Math.max(left.top, right.top);
        float bottom = Math.min(left.bottom, right.bottom);
        if (bottom - top >= height / 0.6f) {
            left.top = right.top = top;
            left.bottom = right.bottom = bottom;
        }
        out.controls.add(new Control(Emulator.KEY_L, Control.SHOULDER, centred(left, width, height), true));
        out.controls.add(new Control(Emulator.KEY_R, Control.SHOULDER, centred(right, width, height), true));
    }

    /** How much of a column's width the L and R buttons may use (and the free space is measured over). */
    private static final float SHOULDER_COLUMN = 0.7f;

    /**
     * The free band {@code above} (or below) {@code under}, over the middle of its column: up to
     * the nearest thing there (the screen, another control, or the edge of the view).
     */
    private static RectF shoulderSpace(Layout out, RectF under, boolean above) {
        float half = under.width() * SHOULDER_COLUMN / 2;
        float left = under.centerX() - half;
        float right = under.centerX() + half;
        float limit = above ? 0 : out.height;
        List<RectF> obstacles = new ArrayList<>();
        obstacles.add(new RectF(out.screen));
        for (Control control : out.controls) {
            if (control.visible && control.bounds != under) obstacles.add(control.bounds);
        }
        for (RectF b : obstacles) {
            if (b.left >= right || b.right <= left) continue;
            if (above && b.bottom <= under.top + 1) limit = Math.max(limit, b.bottom);
            if (!above && b.top >= under.bottom - 1) limit = Math.min(limit, b.top);
        }
        return above ? new RectF(left, limit, right, under.top) : new RectF(left, under.bottom, right, limit);
    }

    private static RectF centred(RectF area, float width, float height) {
        float cx = area.centerX();
        float cy = area.centerY();
        return new RectF(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2);
    }

    /**
     * Draws an L or R button: a rounded tab with its letter, shaded from {@code top} to
     * {@code bottom} (both 0 for just an outline), outlined in {@code edge} (0 for none), with the
     * letter in {@code ink}. It sinks and darkens as {@code press} goes from 0 to 1.
     */
    static void drawShoulder(Canvas canvas, Control control, float press, int top, int bottom, int edge, int ink) {
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        RectF b = new RectF(control.bounds);
        float down = Math.max(0, Math.min(1, press));
        b.offset(0, b.height() * 0.06f * down);
        float corner = b.height() * 0.45f;
        paint.setColor(0x30000000);
        canvas.drawRoundRect(b.left, b.top + b.height() * 0.08f * (1 - down), b.right,
                b.bottom + b.height() * 0.08f * (1 - down), corner, corner, paint);
        if (top != 0 || bottom != 0) {
            paint.setColor(android.graphics.Color.BLACK);
            paint.setShader(new android.graphics.LinearGradient(0, b.top, 0, b.bottom, top, bottom,
                    android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRoundRect(b, corner, corner, paint);
            paint.setShader(null);
        }
        if (down > 0.01f) {
            paint.setColor(android.graphics.Color.argb(Math.round(70 * down), 0, 0, 0));
            if (top == 0 && bottom == 0) paint.setColor(((Math.round(120 * down)) << 24) | (edge & 0xFFFFFF));
            canvas.drawRoundRect(b, corner, corner, paint);
        }
        if (edge != 0) {
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, b.height() * 0.05f));
            paint.setColor(edge);
            canvas.drawRoundRect(b, corner, corner, paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);
        }
        paint.setColor(ink);
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(b.height() * 0.6f);
        String letter = control.keys == Emulator.KEY_L ? "L" : "R";
        canvas.drawText(letter, b.centerX(), b.centerY() - (paint.descent() + paint.ascent()) / 2, paint);
    }

    /** Largest rectangle with the frame's aspect ratio inside {@code area}, centered. */
    static void fitScreen(Rect out, RectF area, int frameWidth, int frameHeight, boolean integerScaling) {
        float areaWidth = Math.max(1, area.width());
        float areaHeight = Math.max(1, area.height());
        float scale = Math.min(areaWidth / frameWidth, areaHeight / frameHeight);
        if (integerScaling && scale >= 1) scale = (float) Math.floor(scale);
        int width = Math.max(1, Math.round(frameWidth * scale));
        int height = Math.max(1, Math.round(frameHeight * scale));
        int left = Math.round(area.centerX() - width / 2f);
        int top = Math.round(area.centerY() - height / 2f);
        out.set(left, top, left + width, top + height);
    }
}
