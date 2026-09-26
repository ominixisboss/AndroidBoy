package com.ominixisboss.androidboy;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The Clear skins' customization: a picture or GIF of the player's, seen through the plastic in
 * place of the circuit board, whether the board still shows over it, and how strongly the
 * plastic is tinted. Shared by every Clear skin. GIFs animate on Android 9 and later; on 8 they
 * show their first frame.
 */
final class ClearBackdrop {
    private static final String TAG = "ClearBackdrop";
    private static final String FILE = "clear-backdrop";
    private static final String PREFS = "clear_skins";
    private static final String PREF_BOARD = "board";
    private static final String PREF_TINT = "tint";
    private static final String PREF_STAMP = "stamp";
    /** Pictures bigger than this are refused; a phone screen needs far less. */
    static final long MAX_BYTES = 25L * 1024 * 1024;
    /** Decoded no bigger than this on either side, to keep memory down. */
    private static final int MAX_SIDE = 1440;

    static final int TINT_NORMAL = 0;
    static final int TINT_LIGHT = 1;
    static final int TINT_NONE = 2;
    static final String[] TINT_NAMES = {"As designed", "Light", "None (clear plastic)"};
    private static final float[] TINT_SCALES = {1f, 0.45f, 0f};

    /** What was last applied, so loading a Clear skin doesn't decode the picture again. */
    private static long appliedStamp = -1;

    private ClearBackdrop() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE);
    }

    static boolean exists(Context context) {
        return file(context).isFile();
    }

    static boolean showsBoard(Context context) {
        return prefs(context).getBoolean(PREF_BOARD, false);
    }

    static int tint(Context context) {
        int tint = prefs(context).getInt(PREF_TINT, TINT_NORMAL);
        return tint >= 0 && tint < TINT_SCALES.length ? tint : TINT_NORMAL;
    }

    /** Copies the picture at {@code uri} in as the backdrop; throws if it isn't one. Any thread. */
    static void save(Context context, Uri uri) throws IOException {
        File target = file(context);
        File temp = new File(context.getFilesDir(), FILE + ".new");
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open the file");
            try (OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_BYTES) throw new IOException("The picture is too big (over 25 MB)");
                    out.write(buffer, 0, read);
                }
            }
            if (decode(temp) == null) throw new IOException("That isn't a picture or GIF");
            if (!temp.renameTo(target)) throw new IOException("Could not save the picture");
        } finally {
            if (temp.exists() && !temp.delete()) Log.w(TAG, "Could not delete " + temp);
        }
        changed(context);
    }

    static void remove(Context context) {
        if (file(context).exists() && !file(context).delete()) Log.w(TAG, "Could not delete the backdrop");
        changed(context);
    }

    static void setShowsBoard(Context context, boolean show) {
        prefs(context).edit().putBoolean(PREF_BOARD, show).apply();
        changed(context);
    }

    static void setTint(Context context, int tint) {
        prefs(context).edit().putInt(PREF_TINT, tint).apply();
        changed(context);
    }

    private static void changed(Context context) {
        SharedPreferences prefs = prefs(context);
        prefs.edit().putLong(PREF_STAMP, prefs.getLong(PREF_STAMP, 0) + 1).apply();
        SkinPreviews.forget("clear:");
        apply(context);
    }

    /** Hands the current settings to the Clear skins, if they've changed since last time. */
    static synchronized void apply(Context context) {
        long stamp = prefs(context).getLong(PREF_STAMP, 0);
        if (stamp == appliedStamp) return;
        appliedStamp = stamp;
        File file = file(context);
        Drawable picture = file.isFile() ? decode(file) : null;
        ClearSkin.customize(picture, showsBoard(context), TINT_SCALES[tint(context)]);
    }

    /** The picture in {@code file}, animated if it's an animated GIF (Android 9+), or null. */
    static Drawable decode(File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                Drawable drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file), (decoder, info, source) -> {
                    int width = info.getSize().getWidth();
                    int height = info.getSize().getHeight();
                    int longest = Math.max(width, height);
                    if (longest > MAX_SIDE) {
                        float scale = (float) MAX_SIDE / longest;
                        decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
                    }
                });
                if (drawable instanceof AnimatedImageDrawable) {
                    ((AnimatedImageDrawable) drawable).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                }
                return drawable;
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "Could not decode the backdrop as an animation; trying it as a still picture", e);
            }
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= MAX_SIDE) {
                options.inSampleSize *= 2;
            }
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
            return bitmap != null ? new BitmapDrawable(null, bitmap) : null;
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "Could not load the backdrop", e);
            return null;
        }
    }
}
