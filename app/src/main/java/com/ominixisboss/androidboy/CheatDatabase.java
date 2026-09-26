package com.ominixisboss.androidboy;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Cheats from the libretro database (github.com/libretro/libretro-database, the collection
 * RetroArch downloads): one .cht file per game, for Game Boy, Game Boy Color and Game Boy Advance.
 */
final class CheatDatabase {
    static final String SITE = "https://github.com/libretro/libretro-database";
    private static final String TREES = "https://api.github.com/repos/libretro/libretro-database/git/trees/";
    private static final String RAW = "https://raw.githubusercontent.com/libretro/libretro-database/master/cht/";
    static final String FOLDER_GB = "Nintendo - Game Boy";
    static final String FOLDER_GBC = "Nintendo - Game Boy Color";
    static final String FOLDER_GBA = "Nintendo - Game Boy Advance";
    /** The list of games changes rarely; GitHub allows few requests an hour without an account. */
    static final long INDEX_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final Set<String> SMALL_WORDS = new HashSet<>(Arrays.asList("the", "a", "an", "of", "and"));

    /** One game's cheat file. */
    static final class Entry {
        final String folder;
        final String name;

        Entry(String folder, String name) {
            this.folder = folder;
            this.name = name;
        }

        /** "Tetris (World) (Rev A)". */
        String title() {
            return name.endsWith(".cht") ? name.substring(0, name.length() - 4) : name;
        }

        boolean isGba() {
            return FOLDER_GBA.equals(folder);
        }

        boolean isColor() {
            return FOLDER_GBC.equals(folder);
        }

        String url() {
            return RAW + Uri.encode(folder) + "/" + Uri.encode(name);
        }
    }

    private CheatDatabase() {}

    /** The list of cheat files, from {@code cache} if it's recent. Call off the main thread. */
    static List<Entry> index(File cache) throws IOException {
        if (cache.isFile() && System.currentTimeMillis() - cache.lastModified() < INDEX_MAX_AGE_MS) {
            List<Entry> cached = parseIndex(new String(RomLibrary.readFile(cache), StandardCharsets.UTF_8));
            // Lists from before Game Boy Advance support have no GBA files: fetch a new one.
            boolean hasGba = false;
            for (Entry entry : cached) hasGba |= entry.isGba();
            if (!cached.isEmpty() && hasGba) return cached;
        }
        try {
            List<Entry> entries = fetchIndex();
            RomLibrary.writeAtomically(cache, formatIndex(entries).getBytes(StandardCharsets.UTF_8));
            return entries;
        } catch (IOException e) {
            // Offline or rate-limited: an old list is better than none.
            if (cache.isFile()) {
                List<Entry> stale = parseIndex(new String(RomLibrary.readFile(cache), StandardCharsets.UTF_8));
                if (!stale.isEmpty()) return stale;
            }
            throw e;
        }
    }

    /** Walks the repository tree: root → cht → the Game Boy, Color and Advance folders. */
    private static List<Entry> fetchIndex() throws IOException {
        try {
            String cht = childSha(get(TREES + "master"), "cht");
            String chtTree = get(TREES + cht);
            List<Entry> entries = new ArrayList<>();
            for (String folder : new String[] {FOLDER_GB, FOLDER_GBC, FOLDER_GBA}) {
                for (String name : fileNames(get(TREES + childSha(chtTree, folder)))) {
                    if (name.toLowerCase(Locale.ROOT).endsWith(".cht")) entries.add(new Entry(folder, name));
                }
            }
            return entries;
        } catch (JSONException e) {
            throw new IOException("GitHub sent something unexpected", e);
        }
    }

    /** The SHA of the folder called {@code name} in a git/trees response. */
    static String childSha(String json, String name) throws JSONException, IOException {
        JSONArray tree = new JSONObject(json).getJSONArray("tree");
        for (int i = 0; i < tree.length(); i++) {
            JSONObject item = tree.getJSONObject(i);
            if (name.equals(item.optString("path")) && "tree".equals(item.optString("type"))) {
                return item.getString("sha");
            }
        }
        throw new IOException("The cheat database has no \"" + name + "\" folder");
    }

    /** The files in a git/trees response. */
    static List<String> fileNames(String json) throws JSONException {
        JSONArray tree = new JSONObject(json).getJSONArray("tree");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tree.length(); i++) {
            JSONObject item = tree.getJSONObject(i);
            if ("blob".equals(item.optString("type"))) names.add(item.optString("path"));
        }
        return names;
    }

    static String formatIndex(List<Entry> entries) {
        StringBuilder text = new StringBuilder();
        for (Entry entry : entries) text.append(entry.folder).append('\t').append(entry.name).append('\n');
        return text.toString();
    }

    static List<Entry> parseIndex(String text) {
        List<Entry> entries = new ArrayList<>();
        for (String line : text.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0 && tab < line.length() - 1) entries.add(new Entry(line.substring(0, tab), line.substring(tab + 1)));
        }
        return entries;
    }

    /**
     * Entries whose title has every word of {@code query} (words may be shortened: "poke red"
     * finds "Pokemon - Red Version"). Closest titles first, then the console the game is for.
     */
    static List<Entry> search(List<Entry> index, String query, boolean preferColor, int limit) {
        return search(index, query, preferColor, false, limit);
    }

    /** As above, but only Game Boy Advance files for a GBA game ({@code gba}), and none for a Game Boy one. */
    static List<Entry> search(List<Entry> index, String query, boolean preferColor, boolean gba, int limit) {
        List<String> wanted = words(query);
        if (wanted.isEmpty()) return Collections.emptyList();
        String wantedTitle = String.join(" ", wanted);
        List<Entry> matches = new ArrayList<>();
        List<int[]> ranks = new ArrayList<>();
        for (Entry entry : index) {
            if (entry.isGba() != gba) continue;
            List<String> have = words(entry.title());
            if (!matchesAll(wanted, have)) continue;
            // Exact game name (ignoring region and version tags) first, then the fewest extra words.
            String base = String.join(" ", words(stripTags(entry.title())));
            int rank = (base.equals(wantedTitle) ? 0 : 1000) + have.size() - wanted.size();
            matches.add(entry);
            ranks.add(new int[] {rank, entry.isColor() == preferColor ? 0 : 1});
        }
        Integer[] order = new Integer[matches.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            int[] ra = ranks.get(a);
            int[] rb = ranks.get(b);
            if (ra[0] != rb[0]) return Integer.compare(ra[0], rb[0]);
            if (ra[1] != rb[1]) return Integer.compare(ra[1], rb[1]);
            return matches.get(a).name.compareToIgnoreCase(matches.get(b).name);
        });
        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < order.length && result.size() < limit; i++) result.add(matches.get(order[i]));
        return result;
    }

    private static boolean matchesAll(List<String> wanted, List<String> have) {
        for (String word : wanted) {
            boolean found = false;
            for (String candidate : have) {
                if (candidate.startsWith(word)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /** Lower-case words without accents or small words: "The Legend of Zelda - Link's Awakening" → legend, zelda, links, awakening. */
    static List<String> words(String text) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace("'", "");
        List<String> words = new ArrayList<>();
        for (String word : plain.split("[^a-z0-9]+")) {
            if (!word.isEmpty() && !SMALL_WORDS.contains(word)) words.add(word);
        }
        return words;
    }

    /** "Tetris (World) (Rev A) [!]" → "Tetris". */
    static String stripTags(String title) {
        return title.replaceAll("\\([^)]*\\)|\\[[^]]*]", " ").replace('_', ' ').replaceAll("\\s+", " ").trim();
    }

    /** What to search for first for a ROM file called {@code romName}. */
    static String defaultQuery(String romName) {
        String query = stripTags(romName);
        return query.isEmpty() ? romName : query;
    }

    /** Downloads and reads one game's cheats. Call off the main thread. */
    static List<Cheat> download(Entry entry) throws IOException {
        List<Cheat> cheats = Cheat.parse(new String(Http.get(entry.url(), 1024 * 1024, "GitHub"), StandardCharsets.UTF_8));
        for (Cheat cheat : cheats) cheat.enabled = false;
        return cheats;
    }

    private static String get(String url) throws IOException {
        return new String(Http.get(url, 8 * 1024 * 1024, "GitHub"), StandardCharsets.UTF_8);
    }
}
