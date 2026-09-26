package com.ominixisboss.androidboy;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.LruCache;

/**
 * Small pictures of skins for the skin picker: each skin drawn the way the game screen draws it,
 * in a phone-shaped portrait frame, with a little Game Boy scene where the game goes.
 */
final class SkinPreviews {
    /** Preview size in pixels; phone-shaped (9:19.5), shown scaled to fit its card. */
    static final int WIDTH = 270;
    static final int HEIGHT = 585;

    // Rendering an image skin loads its artwork, so keep the recent ones.
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private SkinPreviews() {}

    static Bitmap cached(String id) {
        return CACHE.get(id);
    }

    /** The preview, from the cache or drawn now. Any thread. */
    static Bitmap get(Skin skin) {
        Bitmap bitmap = CACHE.get(skin.id());
        if (bitmap == null) {
            bitmap = render(skin, WIDTH, HEIGHT);
            CACHE.put(skin.id(), bitmap);
        }
        return bitmap;
    }

    /** Draws {@code skin} at {@code width}×{@code height} with its controls at rest. */
    static Bitmap render(Skin skin, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(skin.backgroundColor());
        Skin.Layout layout = new Skin.Layout();
        skin.layout(layout, width, height, 160, 144, true, false);
        canvas.save();
        canvas.clipOutRect(layout.screen);
        skin.drawBackground(canvas, layout);
        canvas.restore();
        drawScene(canvas, layout.screen);
        Skin.Motion motion = new Skin.Motion();
        motion.snap(layout, 0);
        skin.drawControls(canvas, layout, 0, motion);
        return bitmap;
    }

    /** A stand-in for the game: the classic green screen with a sun and rolling hills. */
    static void drawScene(Canvas canvas, Rect screen) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFF9BBC0F);
        canvas.drawRect(screen, paint);
        float w = screen.width();
        float h = screen.height();
        paint.setColor(0xFF8BAC0F);
        canvas.drawCircle(screen.left + w * 0.74f, screen.top + h * 0.3f, h * 0.12f, paint);
        Path hills = new Path();
        hills.moveTo(screen.left, screen.bottom);
        hills.lineTo(screen.left, screen.top + h * 0.72f);
        hills.quadTo(screen.left + w * 0.25f, screen.top + h * 0.5f, screen.left + w * 0.5f, screen.top + h * 0.7f);
        hills.quadTo(screen.left + w * 0.75f, screen.top + h * 0.86f, screen.right, screen.top + h * 0.62f);
        hills.lineTo(screen.right, screen.bottom);
        hills.close();
        paint.setColor(0xFF306230);
        canvas.drawPath(hills, paint);
        paint.setColor(0xFF0F380F);
        canvas.drawRect(screen.left, screen.bottom - h * 0.08f, screen.right, screen.bottom, paint);
    }
}
