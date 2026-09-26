package com.ominixisboss.androidboy;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

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

    // Moving and resizing controls: where the skin put them, the player's changes, and a drag in progress.
    private ControlLayout controlLayout;
    private final List<RectF> baseBounds = new ArrayList<>();
    private boolean editing;
    private int editIndex = -1;
    private final ControlLayout.Adjustment editStart = new ControlLayout.Adjustment();
    private float editTouchX;
    private float editTouchY;
    private float editSpan;
    private final Paint editPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Press animation: one damped spring per control, plus two for the d-pad's tilt. A press is
    // quick with a slight overshoot; a release springs back past rest and settles, like rubber.
    private static final float PRESS_STIFFNESS = 1500f;
    private static final float PRESS_DAMPING = 0.55f;
    private static final float RELEASE_DAMPING = 0.28f;
    // The d-pad rocks quickly onto its pivot with one small bounce, like a rubber-backed pad.
    private static final float TILT_STIFFNESS = 1300f;
    private static final float TILT_DAMPING = 0.5f;
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

    /** Where the player moved the controls; null leaves them where the skin puts them. */
    void setControlLayout(ControlLayout controlLayout) {
        this.controlLayout = controlLayout;
        relayout();
    }

    /**
     * Edit mode: dragging a control moves it and pinching resizes it, instead of pressing it.
     * Only for skins whose controls can move ({@link Skin#movableControls}).
     */
    void setEditing(boolean enabled) {
        editing = enabled;
        editIndex = -1;
        releaseAll();
        invalidate();
    }

    boolean isEditing() {
        return editing;
    }

    /** Puts the current skin's controls back, in the current orientation. */
    void resetControlLayout() {
        if (controlLayout == null) return;
        controlLayout.reset(skin.id(), ControlLayout.orientation(getWidth(), getHeight()));
        relayout();
    }

    void setSkin(Skin newSkin) {
        skin = newSkin;
        relayout();
    }

    /** Whether a menu button is on screen now (not when a gamepad has hidden the controls). */
    boolean hasMenuControl() {
        for (Skin.Control control : layout.controls) {
            if (control.visible && (control.keys & Skin.KEY_MENU) != 0) return true;
        }
        return false;
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
        baseBounds.clear();
        for (Skin.Control control : layout.controls) baseBounds.add(new RectF(control.bounds));
        if (controlLayout != null && skin.movableControls()) {
            controlLayout.apply(skin.id(), getWidth(), getHeight(), layout.controls, baseBounds);
        }
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
        if (editing) drawEditOutlines(canvas);
    }

    private void drawEditOutlines(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        editPaint.setStyle(Paint.Style.STROKE);
        editPaint.setStrokeWidth(2 * density);
        for (int i = 0; i < layout.controls.size(); i++) {
            Skin.Control control = layout.controls.get(i);
            if (!control.visible) continue;
            editPaint.setColor(i == editIndex ? Color.YELLOW : Color.WHITE);
            editPaint.setPathEffect(i == editIndex ? null : new DashPathEffect(new float[] {6 * density, 4 * density}, 0));
            canvas.drawRect(control.bounds, editPaint);
        }
        editPaint.setPathEffect(null);
    }

    /** Edit mode touches: pick a control, drag it, pinch it. Changes are saved as they're made. */
    private boolean onEditTouch(MotionEvent event) {
        if (!skin.movableControls() || controlLayout == null) return true;
        String orientation = ControlLayout.orientation(getWidth(), getHeight());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                editIndex = controlAt(event.getX(), event.getY());
                if (editIndex >= 0) beginEdit(event, orientation);
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                // A finger joined or left: carry on from where things are.
                if (editIndex >= 0) beginEdit(event, orientation);
                break;
            case MotionEvent.ACTION_MOVE: {
                if (editIndex < 0) break;
                Skin.Control control = layout.controls.get(editIndex);
                ControlLayout.Adjustment adjustment = new ControlLayout.Adjustment();
                adjustment.dx = editStart.dx + (event.getX(0) - editTouchX) / getWidth();
                adjustment.dy = editStart.dy + (event.getY(0) - editTouchY) / getHeight();
                adjustment.scale = editStart.scale;
                if (event.getPointerCount() >= 2 && editSpan > 0) {
                    adjustment.scale = ControlLayout.clampScale(editStart.scale * span(event) / editSpan);
                }
                controlLayout.put(skin.id(), orientation, control.keys, adjustment);
                controlLayout.apply(skin.id(), getWidth(), getHeight(), layout.controls, baseBounds);
                break;
            }
            default:
                break;
        }
        invalidate();
        return true;
    }

    private void beginEdit(MotionEvent event, String orientation) {
        Skin.Control control = layout.controls.get(editIndex);
        ControlLayout.Adjustment current = controlLayout.get(skin.id(), orientation, control.keys);
        editStart.dx = current.dx;
        editStart.dy = current.dy;
        editStart.scale = current.scale;
        // After a finger lifts, index 0 may be a different finger: use whichever stay down.
        int lifted = event.getActionMasked() == MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1;
        int first = lifted == 0 ? 1 : 0;
        editTouchX = event.getX(first);
        editTouchY = event.getY(first);
        // Pinching needs two fingers; when one of two lifts, the other just drags.
        editSpan = lifted < 0 && event.getPointerCount() >= 2 ? span(event) : 0;
    }

    private static float span(MotionEvent event) {
        return (float) Math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1));
    }

    /** The visible control under a point (with some slack), or -1. */
    private int controlAt(float x, float y) {
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < layout.controls.size(); i++) {
            Skin.Control control = layout.controls.get(i);
            if (!control.visible) continue;
            RectF b = control.bounds;
            float dx = Math.max(0, Math.abs(x - b.centerX()) - b.width() / 2);
            float dy = Math.max(0, Math.abs(y - b.centerY()) - b.height() / 2);
            float distance = (float) Math.hypot(dx, dy);
            if (distance < 24 * getResources().getDisplayMetrics().density && distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
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
        if (editing) return onEditTouch(event);
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
