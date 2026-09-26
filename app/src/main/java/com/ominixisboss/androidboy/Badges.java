package com.ominixisboss.androidboy;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads RetroAchievements badge images into ImageViews, with a small memory cache. */
final class Badges {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService LOADER = Executors.newFixedThreadPool(3, r -> {
        Thread thread = new Thread(r, "Badges");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Badges() {}

    /** Shows the image at {@code url} in {@code view} once loaded; nothing if it fails. Main thread. */
    static void load(ImageView view, String url) {
        view.setTag(url);
        view.setImageDrawable(null);
        if (url == null || url.isEmpty()) return;
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        LOADER.execute(() -> {
            Bitmap bitmap = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(20_000);
                try (InputStream in = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(in);
                }
            } catch (Exception e) {
                // No badge; the text is enough.
            } finally {
                if (connection != null) connection.disconnect();
            }
            if (bitmap == null) return;
            Bitmap loaded = bitmap;
            CACHE.put(url, loaded);
            MAIN.post(() -> {
                // The view may have been reused for another badge meanwhile.
                if (url.equals(view.getTag())) view.setImageBitmap(loaded);
            });
        });
    }
}
