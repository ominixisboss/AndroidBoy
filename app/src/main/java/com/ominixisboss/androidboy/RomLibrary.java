package com.ominixisboss.androidboy;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** ROMs, battery saves and save states, all kept in the app's private storage. */
final class RomLibrary {
    /** Slot used for the automatic "resume where you left off" state. */
    static final int AUTO_SLOT = 0;
    static final int STATE_SLOTS = 9;

    // The largest official cartridges are 8 MiB; allow some headroom for homebrew mappers.
    private static final int MAX_ROM_SIZE = 16 * 1024 * 1024;
    private static final String[] ROM_EXTENSIONS = {".gb", ".gbc", ".sgb", ".cgb", ".dmg"};

    private final File romsDir;
    private final File savesDir;
    private final File statesDir;

    RomLibrary(Context context) {
        File root = context.getFilesDir();
        romsDir = new File(root, "roms");
        savesDir = new File(root, "saves");
        statesDir = new File(root, "states");
        romsDir.mkdirs();
        savesDir.mkdirs();
        statesDir.mkdirs();
    }

    List<File> list() {
        File[] files = romsDir.listFiles(File::isFile);
        if (files == null) return new ArrayList<>();
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return new ArrayList<>(Arrays.asList(files));
    }

    File romFile(String name) {
        return new File(romsDir, name);
    }

    File batteryFile(File rom) {
        return new File(savesDir, baseName(rom) + ".sav");
    }

    File stateFile(File rom, int slot) {
        return new File(statesDir, baseName(rom) + (slot == AUTO_SLOT ? ".auto" : ".s" + slot));
    }

    /** Screenshot taken when the state in {@code slot} was saved. */
    File thumbnailFile(File rom, int slot) {
        return new File(statesDir, stateFile(rom, slot).getName() + ".png");
    }

    /** RetroAchievements progress saved with a state, so partly-done achievements carry on. */
    File achievementProgressFile(File rom, int slot) {
        return new File(statesDir, stateFile(rom, slot).getName() + ".ra");
    }

    void delete(File rom) {
        rom.delete();
        batteryFile(rom).delete();
        for (int slot = 0; slot <= STATE_SLOTS; slot++) {
            stateFile(rom, slot).delete();
            thumbnailFile(rom, slot).delete();
            achievementProgressFile(rom, slot).delete();
        }
    }

    /**
     * Copies a ROM (or the first ROM inside a .zip) from a content URI into the library.
     * Call off the main thread. Returns the imported file.
     */
    File importRom(Context context, Uri uri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String name = displayName(resolver, uri);
        byte[] data;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open the file");
            data = readFully(in, MAX_ROM_SIZE * 4);
        }
        return addRom(name, data);
    }

    /**
     * Adds a ROM (or the first ROM inside a .zip) to the library, named after {@code name}.
     * Returns the new file. Call off the main thread.
     */
    File addRom(String name, byte[] data) throws IOException {
        if (data.length > MAX_ROM_SIZE * 4) throw new IOException("This file is too large to be a Game Boy ROM");
        if (isZip(data)) {
            try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
                data = null;
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory() && hasRomExtension(entry.getName())) {
                        name = new File(entry.getName()).getName();
                        data = readFully(zip, MAX_ROM_SIZE);
                        break;
                    }
                }
            }
            if (data == null) throw new IOException("The zip file doesn't contain a Game Boy ROM");
        }

        if (data.length < 0x150) throw new IOException("This file is too small to be a Game Boy ROM");
        if (name == null || name.isEmpty()) name = "Game.gb";
        name = name.replaceAll("[/\\\\:*?\"<>|\\p{Cntrl}]", "_");
        if (!hasRomExtension(name)) {
            name += (data[0x143] & 0x80) != 0 ? ".gbc" : ".gb";
        }

        File target = new File(romsDir, name);
        writeAtomically(target, data);
        return target;
    }

    static byte[] readFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readFully(in, Integer.MAX_VALUE);
        }
    }

    /** Writes via a temporary file so a crash mid-write never corrupts an existing save. */
    static void writeAtomically(File file, byte[] data) throws IOException {
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!temp.renameTo(file)) {
            temp.delete();
            throw new IOException("Could not write " + file.getName());
        }
    }

    static String baseName(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static boolean hasRomExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : ROM_EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    private static boolean isZip(byte[] data) {
        return data.length >= 4 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4;
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null) return name;
            }
        } catch (RuntimeException ignored) {
            // Some providers don't support queries; fall back to the path.
        }
        String path = uri.getLastPathSegment();
        return path == null ? null : new File(path).getName();
    }

    private static byte[] readFully(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > limit) throw new IOException("This file is too large to be a Game Boy ROM");
        }
        return out.toByteArray();
    }
}
