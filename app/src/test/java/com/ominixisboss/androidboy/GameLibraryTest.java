package com.ominixisboss.androidboy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class GameLibraryTest {
    private final File tetris = new File("Tetris (World).gb");
    private final File zelda = new File("Legend of Zelda, The - Link's Awakening DX (USA).gbc");
    private final File pokemonRed = new File("Pokemon - Red Version (USA).gb");
    private final File emerald = new File("Pokemon - Emerald Version (USA).gba");
    private final File metroid = new File("Metroid Fusion (USA).gba");
    private final List<File> roms = Arrays.asList(tetris, zelda, pokemonRed, emerald, metroid);
    private final Map<String, Integer> systems = new HashMap<>();
    private final Set<File> favourites = new HashSet<>();
    private final Map<File, Long> played = new HashMap<>();

    public GameLibraryTest() {
        systems.put(tetris.getName(), GameLibrary.GB);
        systems.put(zelda.getName(), GameLibrary.GBC);
        systems.put(pokemonRed.getName(), GameLibrary.GB);
        systems.put(emerald.getName(), GameLibrary.GBA);
        systems.put(metroid.getName(), GameLibrary.GBA);
    }

    private List<GameLibrary.Item> arrange(GameLibrary.View view, int columns) {
        return GameLibrary.arrange(roms, systems, favourites::contains, rom -> played.getOrDefault(rom, 0L), view, columns);
    }

    private static List<Integer> kinds(List<GameLibrary.Item> items) {
        List<Integer> kinds = new ArrayList<>();
        for (GameLibrary.Item item : items) kinds.add(item.kind);
        return kinds;
    }

    /** The games in the grid, in order. */
    private static List<File> grid(List<GameLibrary.Item> items) {
        List<File> games = new ArrayList<>();
        for (GameLibrary.Item item : items) if (item.kind == GameLibrary.Item.ROW) games.addAll(item.games);
        return games;
    }

    @Test
    public void readsTheSystemFromTheHeader() {
        byte[] header = new byte[0x150];
        assertEquals(GameLibrary.GB, GameLibrary.systemOf(header, header.length));
        header[0x143] = (byte) 0x80;
        assertEquals(GameLibrary.GBC, GameLibrary.systemOf(header, header.length));
        header[0x143] = (byte) 0xC0;
        assertEquals(GameLibrary.GBC, GameLibrary.systemOf(header, header.length));
        header[4] = 0x24;
        header[5] = (byte) 0xFF;
        header[6] = (byte) 0xAE;
        header[7] = 0x51;
        header[0xB2] = (byte) 0x96;
        assertEquals(GameLibrary.GBA, GameLibrary.systemOf(header, header.length));
        assertEquals(GameLibrary.GB, GameLibrary.systemOf(new byte[0x100], 0x100));
        // By extension, without reading the file.
        assertEquals(GameLibrary.GBA, GameLibrary.systemOf(new File("missing.gba")));
        assertEquals(GameLibrary.GBC, GameLibrary.systemOf(new File("missing.gbc")));
    }

    @Test
    public void newLibraryIsJustTheGrid() {
        List<GameLibrary.Item> items = arrange(new GameLibrary.View(), 3);
        assertEquals(Arrays.asList(GameLibrary.Item.FILTERS, GameLibrary.Item.HEADING, GameLibrary.Item.ROW,
                GameLibrary.Item.ROW), kinds(items));
        assertEquals("All games  ·  5", items.get(1).text);
        assertEquals(3, items.get(2).games.size());
        assertEquals(2, items.get(3).games.size());
        // Nothing played yet: by name.
        assertEquals(Arrays.asList(zelda, metroid, emerald, pokemonRed, tetris), grid(items));
        assertTrue(GameLibrary.arrange(new ArrayList<>(), systems, f -> false, f -> 0, new GameLibrary.View(), 3).isEmpty());
    }

    @Test
    public void continueRecentAndFavourites() {
        played.put(emerald, 300L);
        played.put(tetris, 200L);
        played.put(zelda, 100L);
        favourites.add(metroid);
        List<GameLibrary.Item> items = arrange(new GameLibrary.View(), 4);
        assertEquals(Arrays.asList(GameLibrary.Item.HERO, GameLibrary.Item.HEADING, GameLibrary.Item.STRIP,
                GameLibrary.Item.HEADING, GameLibrary.Item.STRIP, GameLibrary.Item.FILTERS, GameLibrary.Item.HEADING,
                GameLibrary.Item.ROW, GameLibrary.Item.ROW), kinds(items));
        assertEquals(emerald, items.get(0).rom);
        assertEquals("Recently played", items.get(1).text);
        assertEquals(Arrays.asList(tetris, zelda), items.get(2).games);
        assertEquals("Favourites", items.get(3).text);
        assertEquals(Arrays.asList(metroid), items.get(4).games);
        // Most recently played first, then by name.
        assertEquals(Arrays.asList(emerald, tetris, zelda, metroid, pokemonRed), grid(items));
    }

    @Test
    public void filtersSearchesAndSorts() {
        played.put(tetris, 100L);
        favourites.add(zelda);
        assertEquals(Arrays.asList(GameLibrary.ALL, GameLibrary.FAVOURITES, GameLibrary.GB, GameLibrary.GBC, GameLibrary.GBA),
                GameLibrary.filters(roms, systems, favourites::contains));
        assertEquals(Arrays.asList(GameLibrary.ALL),
                GameLibrary.filters(Arrays.asList(tetris, pokemonRed), systems, f -> false));

        GameLibrary.View view = new GameLibrary.View();
        view.filter = GameLibrary.GBA;
        List<GameLibrary.Item> items = arrange(view, 3);
        assertEquals("Only the list, no continue card while filtering", GameLibrary.Item.FILTERS, items.get(0).kind);
        assertEquals("Game Boy Advance  ·  2", items.get(1).text);
        assertEquals(Arrays.asList(metroid, emerald), grid(items));

        view.filter = GameLibrary.FAVOURITES;
        assertEquals(Arrays.asList(zelda), grid(arrange(view, 3)));

        view.filter = GameLibrary.ALL;
        view.query = "poke";
        items = arrange(view, 3);
        assertEquals("Results  ·  2", items.get(1).text);
        assertEquals(Arrays.asList(emerald, pokemonRed), grid(items));
        view.query = "poké red";
        assertEquals(Arrays.asList(pokemonRed), grid(arrange(view, 3)));
        view.query = "mario";
        items = arrange(view, 3);
        assertEquals(GameLibrary.Item.NOTHING, items.get(2).kind);
        assertTrue(items.get(2).text.contains("mario"));

        view.query = "";
        view.sort = GameLibrary.SORT_NAME;
        List<File> byName = grid(arrange(view, 3));
        assertEquals(zelda, byName.get(0));
        assertEquals(tetris, byName.get(4));
        view.sort = GameLibrary.SORT_RECENT;
        assertEquals(tetris, grid(arrange(view, 3)).get(0));
    }

    @Test
    public void matchesEveryWord() {
        assertTrue(GameLibrary.matches(zelda, "links awakening"));
        assertTrue(GameLibrary.matches(zelda, "  "));
        assertFalse(GameLibrary.matches(zelda, "oracle"));
        assertEquals("PR", LibraryScreen.initials("Pokemon - Red Version (USA)"));
        assertEquals("T", LibraryScreen.initials("Tetris (World)"));
        assertEquals("?", LibraryScreen.initials("(Beta)"));
    }
}
