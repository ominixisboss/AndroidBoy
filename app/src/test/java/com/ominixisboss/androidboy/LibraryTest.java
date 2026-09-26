package com.ominixisboss.androidboy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** The game list's sections, and finding box art. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LibraryTest {
    private static final String DAT = "clrmamepro (\n\tname \"Nintendo - Game Boy\"\n)\n\n"
            + "game (\n\tname \"Tetris (World) (Rev 1)\"\n\tregion \"World\"\n"
            + "\trom ( name \"Tetris (World) (Rev 1).gb\" size 32768 crc 46DF91AD md5 AB sha1 CD )\n)\n"
            + "game (\n\tname \"Link's \\\"Quest\\\" (USA)\"\n"
            + "\trom ( name \"x.gb\" size 1 crc 0000ABCD md5 AB sha1 CD )\n)\n";

    @Test
    public void arrangesSections() {
        GameStore store = new GameStore(RuntimeEnvironment.getApplication());
        List<File> roms = new ArrayList<>();
        for (String name : new String[] {"A.gb", "B.gb", "C.gb", "D.gb", "E.gb", "F.gb", "G.gb"}) roms.add(new File(name));

        // Nothing played: a plain list.
        List<GameStore.Row> rows = store.arrange(roms);
        assertEquals(7, rows.size());
        assertFalse(rows.get(0).isHeading());

        for (int i = 0; i < 6; i++) store.markPlayed(roms.get(i), 1000 + i); // F newest.
        store.setFavourite(roms.get(4), true); // E
        rows = store.arrange(roms);
        assertEquals("Favourites", rows.get(0).heading);
        assertEquals("E.gb", rows.get(1).rom.getName());
        assertEquals("Recently played", rows.get(2).heading);
        // Newest first, favourites left out, at most four.
        assertEquals(Arrays.asList("F.gb", "D.gb", "C.gb", "B.gb"), names(rows.subList(3, 7)));
        assertEquals("All games", rows.get(7).heading);
        assertEquals(7, rows.size() - 8);

        store.forget(roms.get(4));
        assertFalse(store.isFavourite(roms.get(4)));
        assertEquals(0, store.lastPlayed(roms.get(4)));
    }

    @Test
    public void readsNoIntroList() {
        Map<Long, String> games = BoxArt.parseDat(DAT);
        assertEquals(2, games.size());
        assertEquals("Tetris (World) (Rev 1)", games.get(0x46DF91ADL));
        assertEquals("Link's \"Quest\" (USA)", games.get(0xABCDL));
        assertEquals(games, BoxArt.parseIndex(BoxArt.formatIndex(games)));
        assertEquals(0xCBF43926L, BoxArt.crc32("123456789".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void pictureNames() {
        assertEquals("Link's _Quest_ (USA)", BoxArt.fileName("Link's \"Quest\" (USA)"));
        assertEquals("Mario _ Yoshi (Europe)", BoxArt.fileName("Mario & Yoshi (Europe)"));
        assertEquals("https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Game_Boy_Color/master/"
                        + "Named_Boxarts/Tetris%20DX%20(World).png",
                BoxArt.pictureUrl(new BoxArt.Match(BoxArt.SYSTEM_GBC, "Tetris DX (World)")));

        List<String> pictures = Arrays.asList("Tetris (Japan)", "Tetris (World) (Rev 1)", "Tetris 2 (USA)",
                "Tetris DX (World)");
        assertEquals("Tetris (World) (Rev 1)", BoxArt.closest(pictures, "Tetris (World) (Rev A)"));
        assertEquals("Tetris (Japan)", BoxArt.closest(pictures, "tetris")); // Same title; first by name.
        assertNull(BoxArt.closest(pictures, "Tetris Attack (USA)"));
        assertNull(BoxArt.closest(pictures, "(Proto)"));
    }

    @Test
    public void missingArtIsRetriedLater() throws Exception {
        BoxArt art = new BoxArt(RuntimeEnvironment.getApplication());
        File rom = new File("Game.gb");
        assertTrue(art.needsFetch(rom));
        art.remove(rom);
        assertFalse(art.needsFetch(rom));
        art.delete(rom);
        assertTrue(art.needsFetch(rom));
    }

    private static List<String> names(List<GameStore.Row> rows) {
        List<String> names = new ArrayList<>();
        for (GameStore.Row row : rows) names.add(row.rom.getName());
        return names;
    }

    /** The start of a Game Boy Advance cartridge header: the logo's first bytes and the fixed 0x96. */
    private static byte[] gbaRom() {
        byte[] rom = new byte[0x400];
        rom[4] = 0x24;
        rom[5] = (byte) 0xFF;
        rom[6] = (byte) 0xAE;
        rom[7] = 0x51;
        rom[0xB2] = (byte) 0x96;
        return rom;
    }

    @Test
    public void recognisesGbaGames() throws Exception {
        byte[] gba = gbaRom();
        assertTrue(RomLibrary.isGbaRom(gba));
        byte[] gb = new byte[0x400];
        gb[0x143] = (byte) 0x80;
        assertFalse(RomLibrary.isGbaRom(gb));
        assertTrue(Arrays.equals(new String[] {BoxArt.SYSTEM_GBA}, BoxArt.systemsFor(gba)));
        assertTrue(Arrays.equals(new String[] {BoxArt.SYSTEM_GBC, BoxArt.SYSTEM_GB}, BoxArt.systemsFor(gb)));

        RomLibrary library = new RomLibrary(RuntimeEnvironment.getApplication());
        File added = library.addRom("Advance Game", gba);
        assertEquals("Advance Game.gba", added.getName());
        assertTrue(RomLibrary.isGbaFile(added));
        assertTrue(library.list().contains(added));
        assertFalse(RomLibrary.isGbaFile(library.addRom("Colour Game", gb)));
    }
}
