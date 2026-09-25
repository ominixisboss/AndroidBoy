package com.ominixisboss.androidboy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/** Draws the emulator's frames with nearest-neighbor scaling. */
final class ScreenView extends View {
    private final Paint paint = new Paint();
    private final Rect source = new Rect();
    private final Rect screenRect = new Rect();
    private final Object lock = new Object();
    private Bitmap frame;
    private int frameWidth = 160;
    private int frameHeight = 144;
    private boolean integerScaling;
    private boolean reserveControlsSpace = true;
    private Runnable layoutListener;

    ScreenView(Context context) {
        super(context);
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
    }

    /** Called from the emulation thread with a bitmap it will not touch until the next-but-one frame. */
    void setFrame(Bitmap bitmap) {
        boolean sizeChanged;
        synchronized (lock) {
            sizeChanged = bitmap.getWidth() != frameWidth || bitmap.getHeight() != frameHeight;
            frame = bitmap;
            frameWidth = bitmap.getWidth();
            frameHeight = bitmap.getHeight();
        }
        if (sizeChanged) {
            post(this::updateLayout);
        }
        postInvalidateOnAnimation();
    }

    void setIntegerScaling(boolean enabled) {
        integerScaling = enabled;
        updateLayout();
    }

    /** In portrait, keep the lower part of the view free for the on-screen controls. */
    void setReserveControlsSpace(boolean reserve) {
        if (reserveControlsSpace == reserve) return;
        reserveControlsSpace = reserve;
        updateLayout();
    }

    void setLayoutListener(Runnable listener) {
        layoutListener = listener;
    }

    /** Where the game screen is drawn, in this view's coordinates. */
    Rect getScreenRect() {
        return screenRect;
    }

    boolean isPortrait() {
        return getHeight() > getWidth();
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

        int fw, fh;
        synchronized (lock) {
            fw = frameWidth;
            fh = frameHeight;
        }

        int availableHeight = height;
        if (isPortrait() && reserveControlsSpace) {
            // Leave room for the d-pad and buttons, but never less than half the height for the game.
            availableHeight = Math.max(height / 2, Math.min(height - width * 3 / 4, width * fh / fw));
        }

        int drawWidth, drawHeight;
        if (integerScaling) {
            int scale = Math.max(1, Math.min(width / fw, availableHeight / fh));
            drawWidth = fw * scale;
            drawHeight = fh * scale;
        } else if (width * fh <= availableHeight * fw) {
            drawWidth = width;
            drawHeight = width * fh / fw;
        } else {
            drawHeight = availableHeight;
            drawWidth = availableHeight * fw / fh;
        }

        int left = (width - drawWidth) / 2;
        int top = (availableHeight - drawHeight) / 2;
        screenRect.set(left, top, left + drawWidth, top + drawHeight);
        invalidate();
        if (layoutListener != null) layoutListener.run();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        synchronized (lock) {
            if (frame == null || frame.isRecycled()) return;
            source.set(0, 0, frame.getWidth(), frame.getHeight());
            canvas.drawBitmap(frame, source, screenRect, paint);
        }
    }
}
