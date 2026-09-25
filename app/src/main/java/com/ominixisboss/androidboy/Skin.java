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
    static final int GAME_KEYS = 0xFF;

    /** A touch control. Bounds are in view pixels. */
    static final class Control {
        static final int DPAD = 0;
        static final int CIRCLE = 1;
        static final int PILL = 2;
        static final int RECT = 3;

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

        void reset(int w, int h, boolean controls) {
            width = w;
            height = h;
            controlsVisible = controls;
            screen.setEmpty();
            this.controls.clear();
            extras = null;
        }
    }

    abstract String id();

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

    /** Draws everything except the game screen (the caller clips it out) and the controls. */
    abstract void drawBackground(Canvas canvas, Layout layout);

    /** Draws the touch controls; {@code pressed} is the mask of held keys, including pseudo-keys. */
    abstract void drawControls(Canvas canvas, Layout layout, int pressed);

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
