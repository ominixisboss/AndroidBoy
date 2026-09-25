package com.ominixisboss.androidboy;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Draws the active skin around the game screen, and turns touches on its controls into key presses.
 * The game screen itself is a separate view placed underneath, at {@link Skin.Layout#screen}.
 */
final class SkinView extends View {
    interface Listener {
        /** Game keys held by touch, as Emulator.KEY_* bits. */
        void onTouchKeysChanged(int mask);

        void onFastForwardTouched(boolean held);

        void onRewindTouched(boolean held);

        void onMenuPressed();

        /** The controls are hidden (a gamepad is in use) and the user touched the screen. */
        void onShowControlsRequested();

        /** Where the game screen should be, in this view's coordinates. */
        void onScreenRectChanged(Rect screen);
    }

    private final Listener listener;
    private final Skin.Layout layout = new Skin.Layout();
    private final SparseIntArray pointerKeys = new SparseIntArray();
    private final Rect lastScreen = new Rect();
    private Skin skin;
    private int frameWidth = 160;
    private int frameHeight = 144;
    private boolean controlsVisible = true;
    private boolean integerScaling;
    private boolean haptics = true;
    private int mask;

    // Press animation: one damped spring per control, plus two for the d-pad's tilt. A press is
    // quick with a slight overshoot; a release springs back past rest and settles, like rubber.
    private static final float PRESS_STIFFNESS = 1500f;
    private static final float PRESS_DAMPING = 0.55f;
    private static final float RELEASE_DAMPING = 0.28f;
    private static final float TILT_STIFFNESS = 900f;
    private static final float TILT_DAMPING = 0.42f;
    private static final float MAX_STEP = 1f / 480;
    private final Skin.Motion motion = new Skin.Motion();
    private float[] velocity = new float[0];
    private float tiltVelocityX;
    private float tiltVelocityY;
    private boolean animations = true;
    private boolean animating;
    private long lastAnimationTime;

    SkinView(Context context, Skin skin, Listener listener) {
        super(context);
        this.skin = skin;
        this.listener = listener;
    }

    void setSkin(Skin newSkin) {
        skin = newSkin;
        relayout();
    }

    Skin getSkin() {
        return skin;
    }

    void setFrameSize(int width, int height) {
        if (width == frameWidth && height == frameHeight) return;
        frameWidth = width;
        frameHeight = height;
        relayout();
    }

    void setControlsVisible(boolean visible) {
        if (controlsVisible == visible) return;
        controlsVisible = visible;
        releaseAll();
        relayout();
    }

    void setIntegerScaling(boolean enabled) {
        integerScaling = enabled;
        relayout();
    }

    void setHaptics(boolean enabled) {
        haptics = enabled;
    }

    /** Animated presses; also off when the system's animations are (developer options, accessibility). */
    void setAnimations(boolean enabled) {
        animations = enabled;
        if (!animationsEnabled()) settle();
        invalidate();
    }

    private boolean animationsEnabled() {
        return animations && ValueAnimator.areAnimatorsEnabled();
    }

    Skin.Motion getMotion() {
        return motion;
    }

    /** Releases every touch-held key. */
    void releaseAll() {
        pointerKeys.clear();
        updateMask();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        relayout();
    }

    private void relayout() {
        if (getWidth() == 0 || getHeight() == 0) return;
        skin.layout(layout, getWidth(), getHeight(), frameWidth, frameHeight, controlsVisible, integerScaling);
        // Touches may now be over different controls.
        pointerKeys.clear();
        updateMask();
        settle();
        if (!layout.screen.equals(lastScreen)) {
            lastScreen.set(layout.screen);
            listener.onScreenRectChanged(new Rect(layout.screen));
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (layout.width == 0) return;
        // Leave a hole where the game screen (a view underneath) shows through.
        canvas.save();
        canvas.clipOutRect(layout.screen);
        skin.drawBackground(canvas, layout);
        canvas.restore();
        if (!controlsVisible) return;
        if (animating) {
            long now = SystemClock.uptimeMillis();
            // Cap the step so a stalled frame doesn't make the springs jump.
            float seconds = Math.min(0.05f, (now - lastAnimationTime) / 1000f);
            lastAnimationTime = now;
            if (advanceAnimations(seconds)) {
                postInvalidateOnAnimation();
            } else {
                animating = false;
            }
        }
        skin.drawControls(canvas, layout, mask, motion);
    }

    /** Puts every control where it rests for the current keys, with no animation. */
    private void settle() {
        motion.snap(layout, mask);
        velocity = new float[motion.press.length];
        tiltVelocityX = 0;
        tiltVelocityY = 0;
        animating = false;
    }

    /**
     * Moves the springs {@code seconds} forward. Returns whether anything is still moving; once
     * everything is close enough to rest, it's snapped there exactly.
     */
    boolean advanceAnimations(float seconds) {
        int count = layout.controls.size();
        if (motion.press.length != count || velocity.length != count) {
            motion.ensureSize(count);
            velocity = new float[count];
        }
        float targetX = Skin.tiltTargetX(mask);
        float targetY = Skin.tiltTargetY(mask);
        float remaining = seconds;
        while (remaining > 0) {
            float dt = Math.min(MAX_STEP, remaining);
            remaining -= dt;
            for (int i = 0; i < count; i++) {
                boolean down = Skin.isDown(layout.controls.get(i), mask);
                float damping = down ? PRESS_DAMPING : RELEASE_DAMPING;
                velocity[i] += spring(motion.press[i], velocity[i], down ? 1 : 0, PRESS_STIFFNESS, damping) * dt;
                motion.press[i] += velocity[i] * dt;
            }
            tiltVelocityX += spring(motion.tiltX, tiltVelocityX, targetX, TILT_STIFFNESS, TILT_DAMPING) * dt;
            motion.tiltX += tiltVelocityX * dt;
            tiltVelocityY += spring(motion.tiltY, tiltVelocityY, targetY, TILT_STIFFNESS, TILT_DAMPING) * dt;
            motion.tiltY += tiltVelocityY * dt;
        }
        boolean moving = !atRest(motion.tiltX, tiltVelocityX, targetX) || !atRest(motion.tiltY, tiltVelocityY, targetY);
        for (int i = 0; i < count && !moving; i++) {
            moving = !atRest(motion.press[i], velocity[i], Skin.isDown(layout.controls.get(i), mask) ? 1 : 0);
        }
        if (!moving) settle();
        return moving;
    }

    /** Acceleration of a damped spring; {@code damping} is the damping ratio (1 = no overshoot). */
    private static float spring(float position, float velocity, float target, float stiffness, float damping) {
        return -stiffness * (position - target) - 2 * damping * (float) Math.sqrt(stiffness) * velocity;
    }

    private static boolean atRest(float position, float velocity, float target) {
        return Math.abs(position - target) < 0.002f && Math.abs(velocity) < 0.05f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!controlsVisible) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) listener.onShowControlsRequested();
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                pointerKeys.put(event.getPointerId(index), hitTest(event.getX(index), event.getY(index), 0));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int id = event.getPointerId(i);
                    int previous = pointerKeys.get(id, 0);
                    // A finger that went down on Menu stays there; others can slide between controls.
                    if ((previous & Skin.KEY_MENU) != 0) continue;
                    pointerKeys.put(id, hitTest(event.getX(i), event.getY(i), previous) & ~Skin.KEY_MENU);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP: {
                int index = event.getActionIndex();
                int id = event.getPointerId(index);
                boolean menuTap = (pointerKeys.get(id, 0) & Skin.KEY_MENU) != 0
                        && (hitTest(event.getX(index), event.getY(index), 0) & Skin.KEY_MENU) != 0;
                pointerKeys.delete(id);
                updateMask();
                if (menuTap) listener.onMenuPressed();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pointerKeys.clear();
                break;
            default:
                return true;
        }
        updateMask();
        return true;
    }

    private void updateMask() {
        int newMask = 0;
        for (int i = 0; i < pointerKeys.size(); i++) {
            newMask |= pointerKeys.valueAt(i);
        }
        if (newMask == mask) return;
        int pressed = newMask & ~mask;
        boolean fastForwardChanged = ((newMask ^ mask) & Skin.KEY_FAST_FORWARD) != 0;
        boolean rewindChanged = ((newMask ^ mask) & Skin.KEY_REWIND) != 0;
        boolean gameKeysChanged = ((newMask ^ mask) & Skin.GAME_KEYS) != 0;
        mask = newMask;
        if (pressed != 0 && haptics) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        if (gameKeysChanged) listener.onTouchKeysChanged(mask & Skin.GAME_KEYS);
        if (fastForwardChanged) listener.onFastForwardTouched((mask & Skin.KEY_FAST_FORWARD) != 0);
        if (rewindChanged) listener.onRewindTouched((mask & Skin.KEY_REWIND) != 0);
        if (!animationsEnabled()) {
            settle();
        } else if (!animating) {
            animating = true;
            lastAnimationTime = SystemClock.uptimeMillis();
        }
        invalidate();
    }

    private static final int DIRECTIONS = Emulator.KEY_UP | Emulator.KEY_DOWN | Emulator.KEY_LEFT | Emulator.KEY_RIGHT;

    /** Returns the keys under a touch point; {@code previous} makes a held d-pad a little stickier. */
    private int hitTest(float x, float y, int previous) {
        Skin.Control best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Skin.Control control : layout.controls) {
            RectF b = control.bounds;
            float halfWidth = b.width() / 2;
            float halfHeight = b.height() / 2;
            float dx = (x - b.centerX()) / halfWidth;
            float dy = (y - b.centerY()) / halfHeight;
            float distance;
            float reach;
            if (control.shape == Skin.Control.DPAD) {
                distance = (float) Math.hypot(dx, dy);
                reach = (previous & DIRECTIONS) != 0 ? 1.8f : 1.35f;
            } else if (control.shape == Skin.Control.CIRCLE) {
                distance = (float) Math.hypot(dx, dy);
                reach = 1.3f;
            } else if (control.shape == Skin.Control.PILL) {
                // Start and Select are short: allow more slop vertically than horizontally.
                distance = Math.max(Math.abs(dx) / 1.3f, Math.abs(dy) / 2.4f);
                reach = 1f;
            } else {
                distance = Math.max(Math.abs(dx), Math.abs(dy));
                reach = 1.15f;
            }
            if (distance < reach && distance < bestDistance) {
                best = control;
                bestDistance = distance;
            }
        }
        if (best == null) return 0;
        if (best.shape != Skin.Control.DPAD) return best.keys;

        // D-pad: 8 directions, with a small dead zone in the middle.
        RectF b = best.bounds;
        float dx = x - b.centerX();
        float dy = y - b.centerY();
        if (Math.hypot(dx, dy) < b.width() / 2 * 0.15f) return previous & DIRECTIONS;
        double angle = Math.toDegrees(Math.atan2(-dy, dx)); // 0 = right, 90 = up
        if (angle < 0) angle += 360;
        int keys = 0;
        if (angle < 67.5 || angle > 292.5) keys |= Emulator.KEY_RIGHT;
        if (angle > 22.5 && angle < 157.5) keys |= Emulator.KEY_UP;
        if (angle > 112.5 && angle < 247.5) keys |= Emulator.KEY_LEFT;
        if (angle > 202.5 && angle < 337.5) keys |= Emulator.KEY_DOWN;
        return keys & best.keys;
    }
}
