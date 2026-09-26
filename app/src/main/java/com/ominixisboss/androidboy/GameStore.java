package com.ominixisboss.androidboy;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** What the game list remembers about each game: when it was last played, and favourites. */
final class GameStore {
    /** How many recently played games get their own section. */
    static final int RECENT_COUNT = 4;

    private static final String PLAYED = "played:";
    private static final String FAVOURITE = "favourite:";

    /** A row in the game list: a section heading or a game. */
    static final class Row {
        final String heading;
        final File rom;

        private Row(String heading, File rom) {
            this.heading = heading;
            this.rom = rom;
        }

        static Row heading(String text) {
            return new Row(text, null);
        }

        static Row game(File rom) {
            return new Row(null, rom);
        }

        boolean isHeading() {
            return heading != null;
        }
    }

    private final SharedPreferences prefs;

    GameStore(Context context) {
        prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE);
    }

    long lastPlayed(File rom) {
        return prefs.getLong(PLAYED + rom.getName(), 0);
    }

    void markPlayed(File rom, long time) {
        prefs.edit().putLong(PLAYED + rom.getName(), time).apply();
    }

    boolean isFavourite(File rom) {
        return prefs.getBoolean(FAVOURITE + rom.getName(), false);
    }

    void setFavourite(File rom, boolean favourite) {
        if (favourite) {
            prefs.edit().putBoolean(FAVOURITE + rom.getName(), true).apply();
        } else {
            prefs.edit().remove(FAVOURITE + rom.getName()).apply();
        }
    }

    /** Forgets a deleted game. */
    void forget(File rom) {
        prefs.edit().remove(PLAYED + rom.getName()).remove(FAVOURITE + rom.getName()).apply();
    }

    /** The most recently played games, newest first. */
    List<File> recent(List<File> roms, int count) {
        List<File> played = new ArrayList<>();
        for (File rom : roms) {
            if (lastPlayed(rom) > 0) played.add(rom);
        }
        played.sort(Comparator.comparingLong(this::lastPlayed).reversed());
        return played.subList(0, Math.min(count, played.size()));
    }

    /**
     * The game list: favourites, then recently played games that aren't favourites, then every
     * game. Headings only appear when there's more than the plain list to show.
     */
    List<Row> arrange(List<File> roms) {
        List<File> favourites = new ArrayList<>();
        for (File rom : roms) {
            if (isFavourite(rom)) favourites.add(rom);
        }
        List<File> recent = new ArrayList<>();
        for (File rom : recent(roms, RECENT_COUNT + favourites.size())) {
            if (!isFavourite(rom) && recent.size() < RECENT_COUNT) recent.add(rom);
        }
        List<Row> rows = new ArrayList<>();
        if (favourites.isEmpty() && recent.isEmpty()) {
            for (File rom : roms) rows.add(Row.game(rom));
            return rows;
        }
        if (!favourites.isEmpty()) {
            rows.add(Row.heading("Favourites"));
            for (File rom : favourites) rows.add(Row.game(rom));
        }
        if (!recent.isEmpty()) {
            rows.add(Row.heading("Recently played"));
            for (File rom : recent) rows.add(Row.game(rom));
        }
        rows.add(Row.heading("All games"));
        for (File rom : roms) rows.add(Row.game(rom));
        return rows;
    }
}
