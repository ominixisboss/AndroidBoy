package com.ominixisboss.androidboy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Multi-touch on-screen controls: d-pad, A/B, Start/Select and a menu button. */
final class GamepadView extends View {
    interface Listener {
        void onTouchKeysChanged(int mask);
        void onMenuPressed();
    }

    private static final int MENU = 1 << 16; // Pseudo-key, never sent to the core

    private final ScreenView screen;
    private final Listener listener;
    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressedFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseIntArray pointerKeys = new SparseIntArray();
    private final RectF rect = new RectF();

    private float dpadX, dpadY, dpadRadius;
    private float aX, aY, bX, bY, buttonRadius;
    private float startX, startY, selectX, selectY, pillWidth, pillHeight;
    private float menuX, menuY, menuRadius;
    private int mask;
    private boolean haptics = true;

    GamepadView(Context context, ScreenView screen, Listener listener) {
        super(context);
        this.screen = screen;
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        fill.setColor(Color.argb(90, 255, 255, 255));
        pressedFill.setColor(Color.argb(170, 255, 255, 255));
        label.setColor(Color.argb(200, 0, 0, 0));
        label.setTextAlign(Paint.Align.CENTER);
        label.setFakeBoldText(true);
        screen.setLayoutListener(this::updateLayout);
    }

    void setHaptics(boolean enabled) {
        haptics = enabled;
    }

    /** Releases every touch-held key, e.g. when the controls are hidden. */
    void releaseAll() {
        pointerKeys.clear();
        updateMask();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateLayout();
    }

    private void updateLayout() {
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) return;
        Rect game = screen.getScreenRect();
        float dp = density;

        if (height > width) {
            // Portrait: controls fill the area under the game screen.
            float top = Math.max(game.bottom, height / 2f);
            float areaHeight = height - top;
            dpadRadius = Math.min(width * 0.2f, Math.min(areaHeight * 0.3f, 90 * dp));
            buttonRadius = dpadRadius * 0.42f;
            float centerY = top + areaHeight * 0.42f;
            dpadX = width * 0.06f + dpadRadius;
            dpadY = centerY;
            aX = width - width * 0.06f - buttonRadius;
            aY = centerY - buttonRadius * 0.6f;
            bX = aX - buttonRadius * 2.4f;
            bY = centerY + buttonRadius * 0.6f;
            pillWidth = Math.min(width * 0.18f, 72 * dp);
            pillHeight = pillWidth * 0.36f;
            float pillY = Math.min(height - pillHeight * 1.8f, centerY + dpadRadius + pillHeight * 1.8f);
            selectX = width / 2f - pillWidth * 0.75f;
            startX = width / 2f + pillWidth * 0.75f;
            selectY = startY = pillY;
            menuRadius = pillHeight * 0.9f;
            menuX = width / 2f;
            menuY = top + menuRadius * 1.6f;
        } else {
            // Landscape: controls on either side of the game screen, overlapping it on narrow displays.
            float side = Math.max(game.left, width * 0.18f);
            dpadRadius = Math.min(side * 0.42f, Math.min(height * 0.2f, 80 * dp));
            buttonRadius = dpadRadius * 0.45f;
            dpadX = Math.max(side / 2f, dpadRadius + 12 * dp);
            dpadY = height * 0.5f;
            float rightCenter = width - dpadX;
            aX = rightCenter + buttonRadius * 1.2f;
            aY = height * 0.5f - buttonRadius * 0.6f;
            bX = rightCenter - buttonRadius * 1.2f;
            bY = height * 0.5f + buttonRadius * 0.6f;
            pillWidth = Math.min(side * 0.5f, 64 * dp);
            pillHeight = pillWidth * 0.36f;
            selectX = dpadX;
            startX = rightCenter;
            selectY = startY = height - pillHeight * 1.5f;
            menuRadius = pillHeight * 0.9f;
            menuX = width - menuRadius * 1.6f;
            menuY = menuRadius * 1.6f;
        }
        label.setTextSize(buttonRadius * 0.7f);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawDpad(canvas);
        drawCircle(canvas, aX, aY, buttonRadius, Emulator.KEY_A, "A");
        drawCircle(canvas, bX, bY, buttonRadius, Emulator.KEY_B, "B");
        drawPill(canvas, selectX, selectY, Emulator.KEY_SELECT, "SELECT");
        drawPill(canvas, startX, startY, Emulator.KEY_START, "START");
        drawMenu(canvas);
    }

    private void drawDpad(Canvas canvas) {
        float arm = dpadRadius / 3f;
        float corner = arm * 0.3f;
        rect.set(dpadX - arm, dpadY - dpadRadius, dpadX + arm, dpadY + dpadRadius);
        canvas.drawRoundRect(rect, corner, corner, fill);
        rect.set(dpadX - dpadRadius, dpadY - arm, dpadX + dpadRadius, dpadY + arm);
        canvas.drawRoundRect(rect, corner, corner, fill);
        drawDpadArm(canvas, Emulator.KEY_UP, dpadX - arm, dpadY - dpadRadius, dpadX + arm, dpadY - arm);
        drawDpadArm(canvas, Emulator.KEY_DOWN, dpadX - arm, dpadY + arm, dpadX + arm, dpadY + dpadRadius);
        drawDpadArm(canvas, Emulator.KEY_LEFT, dpadX - dpadRadius, dpadY - arm, dpadX - arm, dpadY + arm);
        drawDpadArm(canvas, Emulator.KEY_RIGHT, dpadX + arm, dpadY - arm, dpadX + dpadRadius, dpadY + arm);
    }

    private void drawDpadArm(Canvas canvas, int key, float l, float t, float r, float b) {
        if ((mask & key) == 0) return;
        rect.set(l, t, r, b);
        float corner = dpadRadius / 10f;
        canvas.drawRoundRect(rect, corner, corner, pressedFill);
    }

    private void drawCircle(Canvas canvas, float x, float y, float radius, int key, String text) {
        canvas.drawCircle(x, y, radius, (mask & key) != 0 ? pressedFill : fill);
        canvas.drawText(text, x, y - (label.ascent() + label.descent()) / 2, label);
    }

    private void drawPill(Canvas canvas, float x, float y, int key, String text) {
        rect.set(x - pillWidth / 2, y - pillHeight / 2, x + pillWidth / 2, y + pillHeight / 2);
        canvas.drawRoundRect(rect, pillHeight / 2, pillHeight / 2, (mask & key) != 0 ? pressedFill : fill);
        float size = label.getTextSize();
        label.setTextSize(pillHeight * 0.45f);
        canvas.drawText(text, x, y - (label.ascent() + label.descent()) / 2, label);
        label.setTextSize(size);
    }

    private void drawMenu(Canvas canvas) {
        canvas.drawCircle(menuX, menuY, menuRadius, (mask & MENU) != 0 ? pressedFill : fill);
        float half = menuRadius * 0.45f;
        float gap = menuRadius * 0.3f;
        label.setStrokeWidth(menuRadius * 0.12f);
        for (int i = -1; i <= 1; i++) {
            canvas.drawLine(menuX - half, menuY + i * gap, menuX + half, menuY + i * gap, label);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
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
                    if ((previous & MENU) != 0) continue;
                    pointerKeys.put(id, hitTest(event.getX(i), event.getY(i), previous) & ~MENU);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP: {
                int index = event.getActionIndex();
                int id = event.getPointerId(index);
                boolean menuTap = (pointerKeys.get(id, 0) & MENU) != 0
                        && (hitTest(event.getX(index), event.getY(index), 0) & MENU) != 0;
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
        mask = newMask;
        if (pressed != 0 && haptics) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        listener.onTouchKeysChanged(mask & ~MENU);
        invalidate();
    }

    private int hitTest(float x, float y, int previous) {
        // D-pad: generous circular zone, 8 directions with a small dead zone in the middle.
        float dx = x - dpadX;
        float dy = y - dpadY;
        float distance = (float) Math.hypot(dx, dy);
        boolean wasOnDpad = (previous & (Emulator.KEY_UP | Emulator.KEY_DOWN | Emulator.KEY_LEFT | Emulator.KEY_RIGHT)) != 0;
        if (distance < dpadRadius * (wasOnDpad ? 1.8f : 1.35f)) {
            if (distance < dpadRadius * 0.15f) return previous & ~MENU;
            double angle = Math.toDegrees(Math.atan2(-dy, dx)); // 0 = right, 90 = up
            if (angle < 0) angle += 360;
            int keys = 0;
            if (angle < 67.5 || angle > 292.5) keys |= Emulator.KEY_RIGHT;
            if (angle > 22.5 && angle < 157.5) keys |= Emulator.KEY_UP;
            if (angle > 112.5 && angle < 247.5) keys |= Emulator.KEY_LEFT;
            if (angle > 202.5 && angle < 337.5) keys |= Emulator.KEY_DOWN;
            return keys;
        }

        float hit = buttonRadius * 1.3f;
        boolean onA = Math.hypot(x - aX, y - aY) < hit;
        boolean onB = Math.hypot(x - bX, y - bY) < hit;
        // The gap between A and B presses both, like rolling a thumb across the real buttons.
        boolean between = Math.hypot(x - (aX + bX) / 2, y - (aY + bY) / 2) < buttonRadius * 0.5f;
        if (between) return Emulator.KEY_A | Emulator.KEY_B;
        if (onA) return Emulator.KEY_A;
        if (onB) return Emulator.KEY_B;

        if (inPill(x, y, startX, startY)) return Emulator.KEY_START;
        if (inPill(x, y, selectX, selectY)) return Emulator.KEY_SELECT;
        if (Math.hypot(x - menuX, y - menuY) < menuRadius * 1.4f) return MENU;
        return 0;
    }

    private boolean inPill(float x, float y, float cx, float cy) {
        return Math.abs(x - cx) < pillWidth * 0.65f && Math.abs(y - cy) < pillHeight * 1.2f;
    }
}
