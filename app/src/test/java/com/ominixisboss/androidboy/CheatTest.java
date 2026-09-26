package com.ominixisboss.androidboy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Cheat codes, .cht files and searching the cheat database. (tests/host_test.c checks the codes take effect.) */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CheatTest {
    // Shaped like the libretro database's files, including a heading entry and a placeholder code.
    private static final String CHT = "cheats = 5 \n\n"
            + "cheat0_desc = \"Infinite Health\"\ncheat0_code = \"1CF-35E\"\ncheat0_enable = false \n\n"
            + "cheat1_desc = \"Monsters &amp; Party\"\ncheat1_code = \"0AE-B6B-B3A+0AE-B9B-E6A\"\ncheat1_enable = true\n\n"
            + "cheat2_desc = \"-- Items --\"\ncheat2_code = \"\"\ncheat2_enable = false\n\n"
            + "cheat3_desc = \"Have item ??\"\ncheat3_code = \"01??E4CB\"\ncheat3_enable = false\n\n"
            + "cheat4_desc = \"\"\ncheat4_code = \"01ff16d0\"\ncheat4_enable = false\n";

    @Test
    public void validatesCodes() {
        assertTrue(Cheat.isValidCode("01FF16D0"));
        assertTrue(Cheat.isValidCode(" 01ff 16d0 "));
        assertTrue(Cheat.isValidCode("1CF-35E"));
        assertTrue(Cheat.isValidCode("00A-17B-C49"));
        assertTrue(Cheat.isValidCode("00A17BC49"));
        assertFalse(Cheat.isValidCode("01??E4CB"));
        assertFalse(Cheat.isValidCode("$8C243F2A"));
        assertFalse(Cheat.isValidCode("1CF-35"));
        assertFalse(Cheat.isValidCode(""));
        // Game Genie only patches ROM ($0000-$7FFF); this one would point at $8000.
        assertFalse(Cheat.isValidCode("000-007"));
        assertTrue(Cheat.isValidCode("99F-F08"));
    }

    @Test
    public void readsChtFiles() {
        List<Cheat> cheats = Cheat.parse(CHT);
        assertEquals(4, cheats.size()); // The heading has no code.
        assertEquals("Infinite Health", cheats.get(0).description);
        assertFalse(cheats.get(0).enabled);
        assertEquals("Monsters & Party", cheats.get(1).description);
        assertTrue(cheats.get(1).enabled);
        assertEquals(Arrays.asList("0AE-B6B-B3A", "0AE-B9B-E6A"), cheats.get(1).codes());
        assertTrue(cheats.get(1).isValid());
        assertFalse(cheats.get(2).isValid());
        assertEquals("01ff16d0", cheats.get(3).description); // No description: the code stands in.
    }

    @Test
    public void activeCodesSkipOffAndInvalidCheats() {
        List<Cheat> cheats = Cheat.parse(CHT);
        cheats.get(2).enabled = true; // Placeholder: never sent.
        cheats.get(3).enabled = true;
        assertEquals("0AE-B6B-B3A\n0AE-B9B-E6A\n01FF16D0\n", Cheat.activeCodes(cheats));
    }

    @Test
    public void savesAndLoads() throws Exception {
        File file = new File(RuntimeEnvironment.getApplication().getFilesDir(), "game.cht");
        List<Cheat> cheats = new ArrayList<>();
        cheats.add(new Cheat("Say \"hi\"", "01FF16D0", true));
        cheats.add(new Cheat("Two codes", "1CF-35E+00A-17B-C49", false));
        Cheat.save(file, cheats);
        List<Cheat> loaded = Cheat.load(file);
        assertEquals(2, loaded.size());
        assertEquals("Say 'hi'", loaded.get(0).description);
        assertTrue(loaded.get(0).enabled);
        assertEquals("1CF-35E+00A-17B-C49", loaded.get(1).code);
        assertFalse(loaded.get(1).enabled);

        Cheat.save(file, new ArrayList<>());
        assertFalse(file.exists());
        assertTrue(Cheat.load(file).isEmpty());
    }

    @Test
    public void mergeSkipsDuplicates() {
        List<Cheat> cheats = new ArrayList<>(Arrays.asList(new Cheat("Mine", "01ff16d0", true)));
        int added = Cheat.merge(cheats, Arrays.asList(new Cheat("Same", "01FF16D0", false), new Cheat("New", "1CF-35E", false)));
        assertEquals(1, added);
        assertEquals(2, cheats.size());
        assertEquals("New", cheats.get(1).description);
    }

    @Test
    public void validatesGbaCodes() {
        assertTrue(Cheat.isValidCode("D8BAE4D9 4864DCE5")); // GameShark / Action Replay
        assertTrue(Cheat.isValidCode("32000010 0055"));     // CodeBreaker
        assertTrue(Cheat.isValidCode("02000010:55"));       // VBA
        assertFalse(Cheat.isValidCode("02000010:"));
        // Cheat files split GBA codes into halves joined with +.
        assertTrue(new Cheat("", "D8BAE4D9+4864DCE5+A86CDBA5+19BB7B06", true).isValid());
        assertTrue(new Cheat("", "32000010+0055", true).isValid());
        assertFalse(new Cheat("", "0055", true).isValid());
        assertEquals("32000010\n0055\n", Cheat.activeCodes(Arrays.asList(new Cheat("x", "32000010+0055", true))));
    }

    @Test
    public void searchesGbaCheatsOnlyForGbaGames() {
        List<CheatDatabase.Entry> index = CheatDatabase.parseIndex(
                CheatDatabase.FOLDER_GB + "\tPokemon - Red Version (USA, Europe).cht\n"
                + CheatDatabase.FOLDER_GBA + "\tPokemon - Ruby Version (USA, Europe).cht\n"
                + CheatDatabase.FOLDER_GBA + "\tPokemon - Emerald Version (USA, Europe).cht\n");
        assertEquals(1, CheatDatabase.search(index, "pokemon", false, 10).size());
        List<CheatDatabase.Entry> gba = CheatDatabase.search(index, "pokemon", false, true, 10);
        assertEquals(2, gba.size());
        assertTrue(gba.get(0).isGba() && !gba.get(0).isColor());
    }

    @Test
    public void searchesTheDatabase() {
        List<CheatDatabase.Entry> index = CheatDatabase.parseIndex(
                CheatDatabase.FOLDER_GB + "\tTetris (World) (Rev A).cht\n"
                + CheatDatabase.FOLDER_GB + "\tTetris 2 (USA).cht\n"
                + CheatDatabase.FOLDER_GBC + "\tTetris DX (World).cht\n"
                + CheatDatabase.FOLDER_GB + "\tPokemon - Red Version (USA, Europe).cht\n"
                + CheatDatabase.FOLDER_GBC + "\tLegend of Zelda, The - Link's Awakening DX (USA, Europe) (Rev 2).cht\n"
                + "broken line\n");
        assertEquals(5, index.size());
        assertEquals(index.size(), CheatDatabase.parseIndex(CheatDatabase.formatIndex(index)).size());

        List<CheatDatabase.Entry> tetris = CheatDatabase.search(index, "Tetris", false, 10);
        assertEquals(3, tetris.size());
        assertEquals("Tetris (World) (Rev A)", tetris.get(0).title()); // The exact name comes first.
        assertEquals(1, CheatDatabase.search(index, "tet dx", false, 10).size());
        assertEquals(1, CheatDatabase.search(index, "poké red", false, 10).size());
        assertEquals(1, CheatDatabase.search(index, "links awakening", true, 10).size());
        assertTrue(CheatDatabase.search(index, "  ", false, 10).isEmpty());
        assertTrue(CheatDatabase.search(index, "mario", false, 10).isEmpty());
        assertEquals(2, CheatDatabase.search(index, "tetris", false, 2).size());

        assertEquals("https://raw.githubusercontent.com/libretro/libretro-database/master/cht/"
                + "Nintendo%20-%20Game%20Boy/Tetris%20(World)%20(Rev%20A).cht", index.get(0).url());
        assertTrue(index.get(2).isColor());
    }

    @Test
    public void defaultQueryDropsTags() {
        assertEquals("Tetris", CheatDatabase.defaultQuery("Tetris (World) (Rev A) [!]"));
        assertEquals("Super Mario Land", CheatDatabase.defaultQuery("Super_Mario_Land"));
        assertEquals("(Proto)", CheatDatabase.defaultQuery("(Proto)"));
    }

    @Test
    public void readsGitTrees() throws Exception {
        String json = "{\"sha\":\"root\",\"tree\":["
                + "{\"path\":\"README.md\",\"type\":\"blob\",\"sha\":\"a\"},"
                + "{\"path\":\"cht\",\"type\":\"tree\",\"sha\":\"b\"},"
                + "{\"path\":\"Tetris.cht\",\"type\":\"blob\",\"sha\":\"c\"}],\"truncated\":false}";
        assertEquals("b", CheatDatabase.childSha(json, "cht"));
        assertEquals(Arrays.asList("README.md", "Tetris.cht"), CheatDatabase.fileNames(json));
        try {
            CheatDatabase.childSha(json, "README.md"); // A file, not a folder.
            org.junit.Assert.fail();
        } catch (java.io.IOException expected) {
        }
    }
}
