package com.ominixisboss.androidboy;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Everything worth keeping in one zip file, to move to a new phone or keep safe: saves, save
 * states, cheats, imported skins, chosen box art, settings and the game list's favourites; the
 * games themselves if asked. The RetroAchievements login is never included.
 */
final class Backup {
    static final String MANIFEST = "androidboy-backup.json";
    private static final int VERSION = 1;
    /** Folders under the app's files that go in a backup ("roms" only when asked). */
    private static final List<String> FOLDERS = Arrays.asList("saves", "states", "skins", "boxart");
    private static final String ROMS = "roms";
    /** SharedPreferences files that go in a backup, as prefs/<name>.json. */
    private static final List<String> PREFS = Arrays.asList("settings", "library", "controller", "control_layout");
    /** Nothing the app writes is anywhere near this; it stops a damaged or hostile zip filling the disk. */
    private static final int MAX_ENTRY = 64 * 1024 * 1024;

    /** What a restore brought back. */
    static final class Summary {
        int files;
        int games;
        boolean settings;
    }

    private Backup() {}

    /** Writes a backup. Call off the main thread. */
    static void write(Context context, OutputStream out, boolean includeGames) throws IOException {
        File root = context.getFilesDir();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            JSONObject manifest = new JSONObject();
            try {
                manifest.put("version", VERSION);
                manifest.put("created", System.currentTimeMillis());
                manifest.put("games", includeGames);
            } catch (JSONException e) {
                throw new IOException(e);
            }
            put(zip, MANIFEST, manifest.toString().getBytes(StandardCharsets.UTF_8));
            for (String folder : FOLDERS) addFolder(zip, new File(root, folder), folder);
            if (includeGames) addFolder(zip, new File(root, ROMS), ROMS);
            for (String name : PREFS) {
                SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
                put(zip, "prefs/" + name + ".json", prefsToJson(prefs.getAll()).toString().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static void addFolder(ZipOutputStream zip, File folder, String path) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;
        Arrays.sort(files);
        for (File file : files) {
            String name = path + "/" + file.getName();
            if (file.isDirectory()) {
                addFolder(zip, file, name);
            } else if (file.isFile() && !file.getName().endsWith(".tmp")) {
                put(zip, name, RomLibrary.readFile(file));
            }
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    /**
     * Restores a backup over what's there: files in the backup replace files with the same name,
     * other files are kept. Call off the main thread, with no game running.
     */
    static Summary restore(Context context, InputStream in) throws IOException {
        File root = context.getFilesDir();
        Summary summary = new Summary();
        boolean sawManifest = false;
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.equals(MANIFEST)) {
                    sawManifest = true;
                    readEntry(zip);
                    continue;
                }
                if (name.startsWith("prefs/") && name.endsWith(".json")) {
                    String prefsName = name.substring(6, name.length() - 5);
                    if (!PREFS.contains(prefsName)) continue;
                    try {
                        restorePrefs(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
                                new JSONObject(new String(readEntry(zip), StandardCharsets.UTF_8)));
                    } catch (JSONException e) {
                        throw new IOException("The backup's settings are damaged", e);
                    }
                    summary.settings = true;
                    continue;
                }
                File target = safeTarget(root, name);
                if (target == null) continue; // Not something a backup holds; ignore it.
                target.getParentFile().mkdirs();
                RomLibrary.writeAtomically(target, readEntry(zip));
                summary.files++;
                if (name.startsWith(ROMS + "/")) summary.games++;
            }
        }
        if (!sawManifest) throw new IOException("This isn't an AndroidBoy backup");
        return summary;
    }

    /** Where a backup entry goes, or null if its path isn't one a backup writes (or tries to escape). */
    static File safeTarget(File root, String name) throws IOException {
        String[] parts = name.split("/");
        if (parts.length < 2) return null;
        if (!FOLDERS.contains(parts[0]) && !ROMS.equals(parts[0])) return null;
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.contains("\\")) return null;
        }
        File target = new File(root, name);
        String rootPath = root.getCanonicalPath() + File.separator;
        return target.getCanonicalPath().startsWith(rootPath) ? target : null;
    }

    private static byte[] readEntry(ZipInputStream zip) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = zip.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_ENTRY) throw new IOException("The backup has a file that's too large");
        }
        return out.toByteArray();
    }

    /** Preferences as JSON, keeping each value's type: {"key": {"type": "int", "value": 3}}. */
    static JSONObject prefsToJson(Map<String, ?> values) {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            String type;
            if (value instanceof Integer) type = "int";
            else if (value instanceof Long) type = "long";
            else if (value instanceof Boolean) type = "boolean";
            else if (value instanceof Float) type = "float";
            else if (value instanceof String) type = "string";
            else continue; // String sets aren't used.
            try {
                json.put(entry.getKey(), new JSONObject().put("type", type).put("value", value));
            } catch (JSONException e) {
                // Only non-finite floats fail, and none are stored.
            }
        }
        return json;
    }

    static void restorePrefs(SharedPreferences prefs, JSONObject json) throws JSONException {
        SharedPreferences.Editor editor = prefs.edit();
        for (Iterator<String> keys = json.keys(); keys.hasNext(); ) {
            String key = keys.next();
            JSONObject item = json.getJSONObject(key);
            switch (item.getString("type")) {
                case "int": editor.putInt(key, item.getInt("value")); break;
                case "long": editor.putLong(key, item.getLong("value")); break;
                case "boolean": editor.putBoolean(key, item.getBoolean("value")); break;
                case "float": editor.putFloat(key, (float) item.getDouble("value")); break;
                case "string": editor.putString(key, item.getString("value")); break;
                default: break;
            }
        }
        editor.apply();
    }
}
