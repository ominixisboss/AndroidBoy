package com.ominixisboss.androidboy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Cover pictures for the game list, from the libretro thumbnail collection (the one RetroArch
 * shows). A game is identified by its checksum in the No-Intro list of known cartridges, which
 * gives the exact name its picture is filed under; failing that, by a close match on its name.
 */
final class BoxArt {
    static final String SITE = "https://github.com/libretro-thumbnails";
    static final String SYSTEM_GB = "Nintendo - Game Boy";
    static final String SYSTEM_GBC = "Nintendo - Game Boy Color";
    private static final String DATS = "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/no-intro/";
    private static final String THUMBNAILS = "https://raw.githubusercontent.com/libretro-thumbnails/";
    private static final String TREES = "https://api.github.com/repos/libretro-thumbnails/";
    /** The lists of games change rarely. */
    private static final long LIST_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;
    /** A game with no picture is tried again after this long, in case one was added. */
    private static final long RETRY_MS = 30L * 24 * 60 * 60 * 1000;
    /** Pictures are stored this tall at most; the list shows them small. */
    private static final int MAX_HEIGHT = 320;
    private static final Pattern DAT_GAME = Pattern.compile("game \\(\\s*name \"((?:[^\"\\\\]|\\\\.)*)\"(.*?)\\r?\\n\\)",
            Pattern.DOTALL);
    private static final Pattern DAT_CRC = Pattern.compile("\\scrc ([0-9A-Fa-f]{8})\\s");

    /** What a game was identified as. */
    static final class Match {
        final String system;
        final String name;

        Match(String system, String name) {
            this.system = system;
            this.name = name;
        }
    }

    private final File artDir;
    private final File cacheDir;

    BoxArt(Context context) {
        artDir = new File(context.getFilesDir(), "boxart");
        cacheDir = context.getCacheDir();
        artDir.mkdirs();
    }

    File artFile(File rom) {
        return new File(artDir, RomLibrary.baseName(rom) + ".png");
    }

    private File missingFile(File rom) {
        return new File(artDir, RomLibrary.baseName(rom) + ".none");
    }

    /** Whether it's worth looking for {@code rom}'s picture: none yet, and none looked for recently. */
    boolean needsFetch(File rom) {
        if (artFile(rom).isFile()) return false;
        File missing = missingFile(rom);
        return !missing.isFile() || System.currentTimeMillis() - missing.lastModified() > RETRY_MS;
    }

    /** Removes the picture and doesn't look for one again for a while, as for a game with no picture. */
    void remove(File rom) throws IOException {
        artFile(rom).delete();
        RomLibrary.writeAtomically(missingFile(rom), new byte[0]);
    }

    void delete(File rom) {
        artFile(rom).delete();
        missingFile(rom).delete();
    }

    /** The picture, or null. */
    Bitmap load(File rom) {
        File file = artFile(rom);
        return file.isFile() ? BitmapFactory.decodeFile(file.getPath()) : null;
    }

    /** Uses a picture the player chose. Call off the main thread. */
    void setCustom(File rom, InputStream in) throws IOException {
        Bitmap bitmap = BitmapFactory.decodeStream(in);
        if (bitmap == null) throw new IOException("That isn't a picture Android can read");
        save(rom, bitmap);
    }

    /** Finds and downloads {@code rom}'s picture. Returns whether it now has one. Call off the main thread. */
    boolean fetch(File rom) throws IOException {
        byte[] data = RomLibrary.readFile(rom);
        boolean color = data.length > 0x143 && (data[0x143] & 0x80) != 0;
        String[] systems = color ? new String[] {SYSTEM_GBC, SYSTEM_GB} : new String[] {SYSTEM_GB, SYSTEM_GBC};
        Match match = identify(crc32(data), color);
        // First the exact name: the known cartridge's, or else the file's.
        String name = match != null ? match.name : RomLibrary.baseName(rom);
        List<Match> exact = new ArrayList<>();
        if (match != null) {
            exact.add(match);
        } else {
            for (String system : systems) exact.add(new Match(system, name));
        }
        if (tryDownload(rom, exact)) return true;
        // Then the closest picture of the same game, e.g. another region's box.
        List<Match> close = new ArrayList<>();
        for (String system : systems) {
            try {
                String closest = closest(pictureNames(system), name);
                if (closest != null) close.add(new Match(system, closest));
            } catch (IOException e) {
                // No list of pictures (offline, or GitHub's hourly limit): nothing more to try.
            }
        }
        if (tryDownload(rom, close)) return true;
        RomLibrary.writeAtomically(missingFile(rom), new byte[0]);
        return false;
    }

    private boolean tryDownload(File rom, List<Match> candidates) throws IOException {
        for (Match candidate : candidates) {
            byte[] png;
            try {
                png = Http.get(pictureUrl(candidate), 4 * 1024 * 1024, "GitHub");
            } catch (java.io.FileNotFoundException e) {
                continue; // No picture by that name; try the next one. Other errors (offline) stop here.
            }
            Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (bitmap == null) continue;
            save(rom, bitmap);
            return true;
        }
        return false;
    }

    private void save(File rom, Bitmap bitmap) throws IOException {
        if (bitmap.getHeight() > MAX_HEIGHT) {
            int width = Math.max(1, bitmap.getWidth() * MAX_HEIGHT / bitmap.getHeight());
            bitmap = Bitmap.createScaledBitmap(bitmap, width, MAX_HEIGHT, true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        RomLibrary.writeAtomically(artFile(rom), out.toByteArray());
        missingFile(rom).delete();
    }

    /** The cartridge with this checksum, looking in its own system's list first. */
    Match identify(long crc, boolean color) throws IOException {
        for (String system : color ? new String[] {SYSTEM_GBC, SYSTEM_GB} : new String[] {SYSTEM_GB, SYSTEM_GBC}) {
            String name = knownGames(system).get(crc);
            if (name != null) return new Match(system, name);
        }
        return null;
    }

    /** Checksum → name, from No-Intro's list (via libretro-database), cached. */
    private Map<Long, String> knownGames(String system) throws IOException {
        File cache = new File(cacheDir, "nointro-" + slug(system) + ".txt");
        if (!isFresh(cache)) {
            try {
                String dat = new String(Http.get(DATS + Uri.encode(system) + ".dat", 8 * 1024 * 1024, "GitHub"),
                        StandardCharsets.UTF_8);
                RomLibrary.writeAtomically(cache, formatIndex(parseDat(dat)).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                if (!cache.isFile()) throw e; // Otherwise an old list will do.
            }
        }
        return parseIndex(new String(RomLibrary.readFile(cache), StandardCharsets.UTF_8));
    }

    /** The names of the pictures for a system, cached. */
    private List<String> pictureNames(String system) throws IOException {
        File cache = new File(cacheDir, "boxart-" + slug(system) + ".txt");
        if (!isFresh(cache)) {
            try {
                String repo = TREES + repoName(system) + "/git/trees/";
                String root = new String(Http.get(repo + "master", 1024 * 1024, "GitHub"), StandardCharsets.UTF_8);
                String folder = CheatDatabase.childSha(root, "Named_Boxarts");
                List<String> names = new ArrayList<>();
                for (String file : CheatDatabase.fileNames(new String(Http.get(repo + folder, 8 * 1024 * 1024, "GitHub"),
                        StandardCharsets.UTF_8))) {
                    if (file.endsWith(".png")) names.add(file.substring(0, file.length() - 4));
                }
                RomLibrary.writeAtomically(cache, String.join("\n", names).getBytes(StandardCharsets.UTF_8));
            } catch (IOException | JSONException e) {
                if (!cache.isFile()) throw new IOException("Could not list the pictures", e);
            }
        }
        List<String> names = new ArrayList<>();
        for (String line : new String(RomLibrary.readFile(cache), StandardCharsets.UTF_8).split("\n")) {
            if (!line.isEmpty()) names.add(line);
        }
        return names;
    }

    private static boolean isFresh(File cache) {
        return cache.isFile() && System.currentTimeMillis() - cache.lastModified() < LIST_MAX_AGE_MS;
    }

    static String pictureUrl(Match match) {
        return THUMBNAILS + repoName(match.system) + "/master/Named_Boxarts/" + Uri.encode(fileName(match.name)) + ".png";
    }

    /** "Nintendo - Game Boy" → "Nintendo_-_Game_Boy", the thumbnail repository's name. */
    static String repoName(String system) {
        return system.replace(' ', '_');
    }

    private static String slug(String system) {
        return system.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    /** Pictures are filed under the game's name with characters that can't be in file names replaced by "_". */
    static String fileName(String gameName) {
        return gameName.replaceAll("[&*/:`<>?\\\\|\"]", "_");
    }

    /**
     * The picture closest to {@code name}: the same title (ignoring region and version tags),
     * preferring the most tags in common. Null if no title matches.
     */
    static String closest(List<String> names, String name) {
        String title = String.join(" ", CheatDatabase.words(CheatDatabase.stripTags(name)));
        if (title.isEmpty()) return null;
        List<String> tags = CheatDatabase.words(name);
        String best = null;
        int bestScore = -1;
        for (String candidate : names) {
            if (!title.equals(String.join(" ", CheatDatabase.words(CheatDatabase.stripTags(candidate))))) continue;
            int score = 0;
            for (String word : CheatDatabase.words(candidate)) {
                if (tags.contains(word)) score++;
            }
            if (score > bestScore || (score == bestScore && candidate.compareTo(best) < 0)) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    /** CRC-32 → game name, from a clrmamepro .dat file. */
    static Map<Long, String> parseDat(String dat) {
        Map<Long, String> games = new HashMap<>();
        Matcher game = DAT_GAME.matcher(dat);
        while (game.find()) {
            String name = game.group(1).replace("\\\"", "\"");
            Matcher crc = DAT_CRC.matcher(game.group(2));
            while (crc.find()) games.put(Long.parseLong(crc.group(1), 16), name);
        }
        return games;
    }

    static String formatIndex(Map<Long, String> games) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Long, String> entry : games.entrySet()) {
            text.append(Long.toHexString(entry.getKey())).append('\t').append(entry.getValue()).append('\n');
        }
        return text.toString();
    }

    static Map<Long, String> parseIndex(String text) {
        Map<Long, String> games = new HashMap<>();
        for (String line : text.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            try {
                games.put(Long.parseLong(line.substring(0, tab), 16), line.substring(tab + 1));
            } catch (NumberFormatException e) {
                // Skip a damaged line.
            }
        }
        return games;
    }

    static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }
}
