package com.ominixisboss.androidboy;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Built-in themes plus skins imported from .zip files, and which one is active. */
final class SkinLibrary {
    private static final String TAG = "AndroidBoy";
    private static final String PREF_SKIN = "skin";
    private static final long MAX_UNZIPPED_BYTES = 40L * 1024 * 1024;
    private static final int MAX_ENTRIES = 200;

    static final class Entry {
        final String id;
        final String name;
        /** Imported skins have a directory; built-in themes don't. */
        final File dir;

        Entry(String id, String name, File dir) {
            this.id = id;
            this.name = name;
            this.dir = dir;
        }
    }

    private final File skinsDir;
    private final SharedPreferences prefs;

    SkinLibrary(Context context) {
        skinsDir = new File(context.getFilesDir(), "skins");
        skinsDir.mkdirs();
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        for (ThemeSkin theme : ThemeSkin.ALL) {
            entries.add(new Entry(theme.id(), theme.name(), null));
        }
        File[] dirs = skinsDir.listFiles(f -> f.isDirectory() && new File(f, ImageSkin.MANIFEST).isFile());
        if (dirs != null) {
            List<Entry> custom = new ArrayList<>();
            for (File dir : dirs) {
                custom.add(new Entry("custom:" + dir.getName(), ImageSkin.readName(dir), dir));
            }
            custom.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            entries.addAll(custom);
        }
        return entries;
    }

    String activeId() {
        return prefs.getString(PREF_SKIN, ThemeSkin.fallback().id());
    }

    void setActive(String id) {
        prefs.edit().putString(PREF_SKIN, id).apply();
    }

    /** The active skin; falls back to the minimal theme if an imported skin can't be loaded. */
    Skin loadActive() {
        String id = activeId();
        if (id.startsWith("theme:")) {
            ThemeSkin theme = ThemeSkin.find(id.substring("theme:".length()));
            return theme != null ? theme : ThemeSkin.fallback();
        }
        if (id.startsWith("custom:")) {
            File dir = new File(skinsDir, id.substring("custom:".length()));
            try {
                return ImageSkin.load(dir);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "Could not load skin " + dir, e);
            }
        }
        return ThemeSkin.fallback();
    }

    void delete(Entry entry) {
        if (entry.dir == null) return;
        deleteRecursively(entry.dir);
        if (entry.id.equals(activeId())) setActive(ThemeSkin.fallback().id());
    }

    /**
     * Extracts a skin .zip into the library and checks that it loads. Call off the main thread.
     * Throws IOException with a message suitable for showing to the user.
     */
    Entry importSkin(Context context, Uri uri) throws IOException {
        File temp = new File(skinsDir, ".import-" + System.nanoTime());
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Could not open the file");
                extract(in, temp);
            }
            // Allow the files to be at the top of the zip or inside a single folder.
            File root = temp;
            if (!new File(root, ImageSkin.MANIFEST).isFile()) {
                File[] children = temp.listFiles();
                if (children != null && children.length == 1 && children[0].isDirectory()
                        && new File(children[0], ImageSkin.MANIFEST).isFile()) {
                    root = children[0];
                } else {
                    throw new IOException("The zip doesn't contain a skin.json");
                }
            }
            ImageSkin.load(root); // Validates the layout and images.

            File target = new File(skinsDir, "skin-" + System.currentTimeMillis());
            if (!root.renameTo(target)) throw new IOException("Could not save the skin");
            return new Entry("custom:" + target.getName(), ImageSkin.readName(target), target);
        } finally {
            deleteRecursively(temp);
        }
    }

    private static void extract(InputStream in, File dir) throws IOException {
        if (!dir.mkdirs()) throw new IOException("Could not create a folder for the skin");
        String base = dir.getCanonicalPath() + File.separator;
        long total = 0;
        int count = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) throw new IOException("The zip has too many files to be a skin");
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("__MACOSX/") || name.endsWith("/.DS_Store")) continue;
                File file = new File(dir, name);
                // Refuse entries that would land outside the skin's folder ("zip slip").
                if (!file.getCanonicalPath().startsWith(base)) throw new IOException("The zip contains an unsafe path: " + name);
                if (entry.isDirectory()) {
                    file.mkdirs();
                    continue;
                }
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream out = new FileOutputStream(file)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_UNZIPPED_BYTES) throw new IOException("The skin is too large (over 40 MB unzipped)");
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
        if (count == 0) throw new IOException("This isn't a zip file");
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            Arrays.stream(children).forEach(SkinLibrary::deleteRecursively);
        }
        file.delete();
    }
}
