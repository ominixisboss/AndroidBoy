package com.ominixisboss.androidboy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * What the game library shows: a "continue playing" card for the last game played, strips of
 * recently played and favourite games, the filters, and a grid of the games that match the
 * filter and search, sorted. Worked out here, without views, so it can be tested.
 */
final class GameLibrary {
    // Filters, in the order their chips are shown.
    static final int ALL = 0;
    static final int FAVOURITES = 1;
    static final int GB = 2;
    static final int GBC = 3;
    static final int GBA = 4;
    static final String[] FILTER_NAMES = {"All", "Favourites", "Game Boy", "Game Boy Color", "Game Boy Advance"};
    /** Short system names, for the badges on the covers (indexed like the filters). */
    static final String[] BADGES = {"", "", "GB", "GBC", "GBA"};

    static final int SORT_RECENT = 0;
    static final int SORT_NAME = 1;

    /** How many games the "recently played" strip shows. */
    static final int RECENT_COUNT = 10;

    /** One entry in the list. */
    static final class Item {
        static final int HERO = 0;
        static final int HEADING = 1;
        static final int STRIP = 2;
        static final int FILTERS = 3;
        static final int ROW = 4;
        static final int NOTHING = 5;

        final int kind;
        /** The hero's game. */
        final File rom;
        /** A strip's or a grid row's games. */
        final List<File> games;
        /** A heading's or NOTHING's text. */
        final String text;

        private Item(int kind, File rom, List<File> games, String text) {
            this.kind = kind;
            this.rom = rom;
            this.games = games;
            this.text = text;
        }
    }

    /** What the list is showing: the filter, sort and search text. */
    static final class View {
        int filter = ALL;
        int sort = SORT_RECENT;
        String query = "";

        boolean browsing() {
            return filter == ALL && query.trim().isEmpty();
        }
    }

    private GameLibrary() {}

    /** Which system a game is for (GB, GBC or GBA), from its extension or else its header. */
    static int systemOf(File rom) {
        String name = rom.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".gba") || name.endsWith(".agb")) return GBA;
        if (name.endsWith(".gbc") || name.endsWith(".cgb")) return GBC;
        byte[] header = new byte[0x150];
        int read = 0;
        try (InputStream in = new FileInputStream(rom)) {
            int n;
            while (read < header.length && (n = in.read(header, read, header.length - read)) > 0) read += n;
        } catch (IOException e) {
            return GB;
        }
        return systemOf(header, read);
    }

    /** Which system a ROM is for, from its first {@code length} bytes. */
    static int systemOf(byte[] header, int length) {
        if (length >= 0xC0 && RomLibrary.isGbaRom(header)) return GBA;
        // The Color flag: 0x80 works on both, 0xC0 only on a Color; both are listed as Color games.
        if (length > 0x143 && (header[0x143] & 0x80) != 0) return GBC;
        return GB;
    }

    /** The filters worth showing: All, Favourites if there are any, and each system in the library. */
    static List<Integer> filters(List<File> roms, Map<String, Integer> systems, Predicate<File> favourite) {
        boolean[] present = new boolean[FILTER_NAMES.length];
        boolean anyFavourite = false;
        for (File rom : roms) {
            present[system(rom, systems)] = true;
            anyFavourite |= favourite.test(rom);
        }
        List<Integer> filters = new ArrayList<>();
        filters.add(ALL);
        if (anyFavourite) filters.add(FAVOURITES);
        int kinds = 0;
        for (int system = GB; system <= GBA; system++) if (present[system]) kinds++;
        // A filter per system, when there's more than one to choose between.
        if (kinds > 1) {
            for (int system = GB; system <= GBA; system++) if (present[system]) filters.add(system);
        }
        return filters;
    }

    private static int system(File rom, Map<String, Integer> systems) {
        Integer system = systems.get(rom.getName());
        return system != null ? system : GB;
    }

    /** Whether every word of {@code query} is in the game's name ("poke red" finds "Pokemon Red"). */
    static boolean matches(File rom, String query) {
        List<String> wanted = CheatDatabase.words(query);
        if (wanted.isEmpty()) return true;
        String name = String.join(" ", CheatDatabase.words(RomLibrary.baseName(rom)));
        for (String word : wanted) {
            if (!name.contains(word)) return false;
        }
        return true;
    }

    /**
     * The list for {@code view}. {@code columns} is how many covers fit in a grid row. Empty when
     * there are no games at all (the activity shows its empty screen then).
     */
    static List<Item> arrange(List<File> roms, Map<String, Integer> systems, Predicate<File> favourite,
                              ToLongFunction<File> lastPlayed, View view, int columns) {
        List<Item> items = new ArrayList<>();
        if (roms.isEmpty()) return items;
        List<File> played = new ArrayList<>();
        for (File rom : roms) {
            if (lastPlayed.applyAsLong(rom) > 0) played.add(rom);
        }
        played.sort(Comparator.comparingLong(lastPlayed).reversed());

        if (view.browsing()) {
            if (!played.isEmpty()) {
                items.add(new Item(Item.HERO, played.get(0), null, null));
                List<File> recent = new ArrayList<>(played.subList(1, Math.min(played.size(), RECENT_COUNT + 1)));
                if (!recent.isEmpty()) {
                    items.add(new Item(Item.HEADING, null, null, "Recently played"));
                    items.add(new Item(Item.STRIP, null, recent, null));
                }
            }
            List<File> favourites = new ArrayList<>();
            for (File rom : roms) if (favourite.test(rom)) favourites.add(rom);
            if (!favourites.isEmpty()) {
                favourites.sort(byName());
                items.add(new Item(Item.HEADING, null, null, "Favourites"));
                items.add(new Item(Item.STRIP, null, favourites, null));
            }
        }

        items.add(new Item(Item.FILTERS, null, null, null));
        List<File> shown = new ArrayList<>();
        for (File rom : roms) {
            boolean wanted;
            switch (view.filter) {
                case FAVOURITES: wanted = favourite.test(rom); break;
                case GB: case GBC: case GBA: wanted = system(rom, systems) == view.filter; break;
                default: wanted = true; break;
            }
            if (wanted && matches(rom, view.query)) shown.add(rom);
        }
        if (view.sort == SORT_RECENT) {
            // Most recently played first, then the rest by name.
            shown.sort(Comparator.comparingLong(lastPlayed).reversed().thenComparing(byName()));
        } else {
            shown.sort(byName());
        }
        String title = view.query.trim().isEmpty()
                ? (view.filter == ALL ? "All games" : FILTER_NAMES[view.filter])
                : "Results";
        items.add(new Item(Item.HEADING, null, null, title + "  ·  " + shown.size()));
        if (shown.isEmpty()) {
            items.add(new Item(Item.NOTHING, null, null, view.query.trim().isEmpty()
                    ? "No games here yet." : "No games match “" + view.query.trim() + "”."));
        }
        int perRow = Math.max(1, columns);
        for (int i = 0; i < shown.size(); i += perRow) {
            items.add(new Item(Item.ROW, null, Collections.unmodifiableList(
                    new ArrayList<>(shown.subList(i, Math.min(shown.size(), i + perRow)))), null));
        }
        return items;
    }

    private static Comparator<File> byName() {
        return (a, b) -> RomLibrary.baseName(a).compareToIgnoreCase(RomLibrary.baseName(b));
    }
}
