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
        /** Imported skins have a directory; built-in themes and bundled skins don't. */
        final File dir;
        /** The group it's listed in; one of {@link #CATEGORIES}. */
        final String category;

        Entry(String id, String name, File dir, String category) {
            this.id = id;
            this.name = name;
            this.dir = dir;
            this.category = category;
        }
    }

    static final String ARTWORK = "Artwork";
    static final String IMPORTED = "Imported";
    /** Skin picker groups, in the order they're shown. */
    static final String[] CATEGORIES = {SoftSkin.CATEGORY, ThemeSkin.CLASSICS, ThemeSkin.COLOURS, ThemeSkin.MODERN, ARTWORK, IMPORTED};

    /** Image skins that ship with the app, in assets/skins/<name>/. */
    static final String BUNDLED_ASSETS = "skins";

    private final Context context;
    private final File skinsDir;
    private final File bundledDir;
    private final SharedPreferences prefs;

    SkinLibrary(Context context) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        skinsDir = new File(context.getFilesDir(), "skins");
        skinsDir.mkdirs();
        bundledDir = new File(context.getFilesDir(), "bundled-skins");
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** Names of the bundled skins, as asset folder names. */
    private String[] bundledNames() {
        List<String> names = new ArrayList<>();
        for (String name : assetChildren(BUNDLED_ASSETS)) {
            if (assetChildren(BUNDLED_ASSETS + "/" + name).contains(ImageSkin.MANIFEST)) names.add(name);
        }
        return names.toArray(new String[0]);
    }

    /**
     * The files and folders directly inside an asset folder. Android lists just their names, but
     * some asset managers (Robolectric's) list every file below the folder by its relative path,
     * so this keeps only the first part of each, once.
     */
    private List<String> assetChildren(String path) {
        List<String> children = new ArrayList<>();
        try {
            String[] entries = context.getAssets().list(path);
            if (entries == null) return children;
            for (String entry : entries) {
                if (entry.startsWith(path + "/")) entry = entry.substring(path.length() + 1);
                int slash = entry.indexOf('/');
                String child = slash >= 0 ? entry.substring(0, slash) : entry;
                if (!child.isEmpty() && !children.contains(child)) children.add(child);
            }
        } catch (IOException e) {
            // No such folder.
        }
        java.util.Collections.sort(children);
        return children;
    }

    /**
     * Bundled skins are copied out of the APK once, since skins load from files. They're copied
     * again after the app is updated, in case the artwork changed.
     */
    private synchronized File bundledSkinDir(String name) throws IOException {
        String version = appVersion();
        File marker = new File(bundledDir, ".version");
        if (!marker.isFile() || !version.equals(new String(RomLibrary.readFile(marker), "UTF-8"))) {
            deleteRecursively(bundledDir);
            if (!bundledDir.mkdirs()) throw new IOException("Could not create " + bundledDir);
            byte[] buffer = new byte[64 * 1024];
            for (String skin : bundledNames()) {
                File dir = new File(bundledDir, skin);
                dir.mkdirs();
                for (String file : assetChildren(BUNDLED_ASSETS + "/" + skin)) {
                    try (InputStream in = context.getAssets().open(BUNDLED_ASSETS + "/" + skin + "/" + file);
                         OutputStream out = new FileOutputStream(new File(dir, file))) {
                        int read;
                        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    }
                }
            }
            try (OutputStream out = new FileOutputStream(marker)) {
                out.write(version.getBytes("UTF-8"));
            }
        }
        return new File(bundledDir, name);
    }

    /** Loads the bundled skin in assets/skins/{@code name}. */
    ImageSkin loadBundled(String name) throws IOException {
        if (!Arrays.asList(bundledNames()).contains(name)) throw new IOException("No bundled skin " + name);
        return ImageSkin.load(bundledSkinDir(name), "bundled:" + name);
    }

    private String appVersion() {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName + ":" + info.lastUpdateTime;
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException e) {
            return "0";
        }
    }

    List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        for (SoftSkin soft : SoftSkin.ALL) {
            entries.add(new Entry(soft.id(), soft.name(), null, SoftSkin.CATEGORY));
        }
        for (ThemeSkin theme : ThemeSkin.ALL) {
            entries.add(new Entry(theme.id(), theme.name(), null, theme.category()));
        }
        for (String name : bundledNames()) {
            entries.add(new Entry("bundled:" + name, bundledName(name), null, ARTWORK));
        }
        File[] dirs = skinsDir.listFiles(f -> f.isDirectory() && new File(f, ImageSkin.MANIFEST).isFile());
        if (dirs != null) {
            List<Entry> custom = new ArrayList<>();
            for (File dir : dirs) {
                custom.add(new Entry("custom:" + dir.getName(), ImageSkin.readName(dir), dir, IMPORTED));
            }
            custom.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            entries.addAll(custom);
        }
        return entries;
    }

    /** A bundled skin's display name, read from its skin.json inside the APK. */
    private String bundledName(String name) {
        try (InputStream in = context.getAssets().open(BUNDLED_ASSETS + "/" + name + "/" + ImageSkin.MANIFEST)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) bytes.write(buffer, 0, read);
            String value = new org.json.JSONObject(bytes.toString("UTF-8")).optString("name", "").trim();
            return value.isEmpty() ? name : value;
        } catch (IOException | org.json.JSONException e) {
            return name;
        }
    }

    /** The skins in one category, in list order. */
    List<Entry> list(String category) {
        List<Entry> entries = new ArrayList<>();
        for (Entry entry : list()) {
            if (entry.category.equals(category)) entries.add(entry);
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
        if (id.startsWith("soft:")) {
            SoftSkin soft = SoftSkin.find(id.substring("soft:".length()));
            if (soft != null) return soft;
        }
        if (id.startsWith("bundled:")) {
            String name = id.substring("bundled:".length());
            try {
                return loadBundled(name);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "Could not load bundled skin " + name, e);
            }
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
            return new Entry("custom:" + target.getName(), ImageSkin.readName(target), target, IMPORTED);
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
