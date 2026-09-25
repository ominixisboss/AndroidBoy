package com.ominixisboss.androidboy;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/** Screenshots saved next to save states, shown in the save/load slot lists. */
final class StateThumbnails {
    private StateThumbnails() {}

    /** The frame at its native resolution (160×144, or 256×224 with a Super Game Boy border) as PNG. */
    static byte[] encode(Frame frame) {
        // Frame pixels are RGBA bytes. Convert to ARGB colour ints rather than copying raw memory,
        // whose byte order depends on the platform's graphics library.
        int count = frame.width * frame.height;
        int[] colors = new int[count];
        for (int i = 0; i < count; i++) {
            int r = frame.pixels.get(i * 4) & 0xFF;
            int g = frame.pixels.get(i * 4 + 1) & 0xFF;
            int b = frame.pixels.get(i * 4 + 2) & 0xFF;
            colors[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        Bitmap bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(colors, 0, frame.width, 0, 0, frame.width, frame.height);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        return out.toByteArray();
    }

    static void write(File file, Frame frame) throws IOException {
        RomLibrary.writeAtomically(file, encode(frame));
    }

    /** The thumbnail, or null if there isn't a readable one. */
    static Bitmap read(File file) {
        return file.isFile() ? BitmapFactory.decodeFile(file.getPath()) : null;
    }
}
