package com.ominixisboss.androidboy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/** Fallback renderer for devices without OpenGL ES 3.0: sharp pixels only, no filters or blending. */
final class CanvasScreenView extends View implements GameScreen {
    private final Paint paint = new Paint();
    private final Rect destination = new Rect();
    private final Object lock = new Object();
    private Bitmap bitmap;

    CanvasScreenView(Context context) {
        super(context);
        paint.setFilterBitmap(false);
    }

    @Override
    public void setFrame(Frame frame) {
        synchronized (lock) {
            if (bitmap == null || bitmap.getWidth() != frame.width || bitmap.getHeight() != frame.height) {
                bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888);
            }
            // Frame pixels are RGBA bytes, the same memory layout as ARGB_8888.
            frame.pixels.position(0);
            frame.pixels.limit(frame.width * frame.height * 4);
            bitmap.copyPixelsFromBuffer(frame.pixels);
            frame.pixels.clear();
        }
        postInvalidateOnAnimation();
    }

    @Override
    public void setFilter(int filter) {}

    @Override
    public void setFrameBlending(int mode) {}

    @Override
    public View view() {
        return this;
    }

    @Override
    public void onResume() {}

    @Override
    public void onPause() {}

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        synchronized (lock) {
            if (bitmap == null) return;
            destination.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(bitmap, null, destination, paint);
        }
    }
}
