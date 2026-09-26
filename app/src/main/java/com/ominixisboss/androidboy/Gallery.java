package com.ominixisboss.androidboy;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Saves pictures (screenshots, Game Boy Printer printouts, camera shots) to Pictures/AndroidBoy. */
final class Gallery {
    static final String FOLDER = "AndroidBoy";

    private Gallery() {}

    /** Scales a picture up by a whole number with sharp pixels, so tiny Game Boy images stay crisp. */
    static Bitmap scaleUp(Bitmap bitmap, int factor) {
        if (factor <= 1) return bitmap;
        return Bitmap.createScaledBitmap(bitmap, bitmap.getWidth() * factor, bitmap.getHeight() * factor, false);
    }

    /** "Tetris 2026-09-26 12-30-05". */
    static String fileName(String prefix, long time) {
        return prefix + " " + new SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(new Date(time));
    }

    /**
     * Saves {@code bitmap} as a PNG called {@code name} (no extension). Android 10 and later need no
     * permission; older versions need WRITE_EXTERNAL_STORAGE, which the caller asks for.
     * Returns where it went. Call off the main thread.
     */
    static Uri savePng(Context context, Bitmap bitmap, String name) throws IOException {
        String safeName = name.replaceAll("[/\\\\:*?\"<>|\\p{Cntrl}]", "_") + ".png";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, safeName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("The gallery didn't accept the picture");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IOException("Could not write the picture");
                }
            } catch (IOException e) {
                resolver.delete(uri, null, null);
                throw e;
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        }
        return saveLegacy(context, bitmap, safeName);
    }

    @SuppressWarnings("deprecation") // Only used before Android 10, where this is the way to do it.
    private static Uri saveLegacy(Context context, Bitmap bitmap, String fileName) throws IOException {
        File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Could not create Pictures/" + FOLDER);
        File file = new File(folder, fileName);
        try (OutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("Could not write the picture");
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DATA, file.getPath());
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        return uri != null ? uri : Uri.fromFile(file);
    }

    /** Whether saving needs the storage permission first (Android 9 and older). */
    static boolean needsPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
}
