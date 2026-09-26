package com.ominixisboss.androidboy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Backing up and restoring saves, states, settings and (optionally) games. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BackupTest {
    @Test
    public void roundTrip() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File root = context.getFilesDir();
        RomLibrary library = new RomLibrary(context);
        byte[] rom = new byte[0x8000];
        File game = library.addRom("Game.gb", rom);
        RomLibrary.writeAtomically(library.batteryFile(game), new byte[] {1, 2, 3});
        RomLibrary.writeAtomically(library.stateFile(game, 1), new byte[] {4, 5});
        RomLibrary.writeAtomically(library.cheatFile(game), "cheats = 0\n".getBytes(StandardCharsets.UTF_8));
        new Settings(context).set(Settings.FILTER, 3);
        new GameStore(context).setFavourite(game, true);
        SharedPreferences login = context.getSharedPreferences("retroachievements", Context.MODE_PRIVATE);
        login.edit().putString("token", "secret").apply();

        ByteArrayOutputStream withoutGames = new ByteArrayOutputStream();
        Backup.write(context, withoutGames, false);
        ByteArrayOutputStream withGames = new ByteArrayOutputStream();
        Backup.write(context, withGames, true);
        assertFalse(new String(withGames.toByteArray(), StandardCharsets.ISO_8859_1).contains("secret"));

        // Lose everything, then restore.
        game.delete();
        library.batteryFile(game).delete();
        library.stateFile(game, 1).delete();
        library.cheatFile(game).delete();
        new Settings(context).set(Settings.FILTER, 0);
        new GameStore(context).setFavourite(game, false);

        Backup.Summary summary = Backup.restore(context, new ByteArrayInputStream(withoutGames.toByteArray()));
        assertEquals(0, summary.games);
        assertTrue(summary.settings);
        assertFalse(game.exists());
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(library.batteryFile(game).toPath()));
        assertArrayEquals(new byte[] {4, 5}, Files.readAllBytes(library.stateFile(game, 1).toPath()));
        assertTrue(library.cheatFile(game).isFile());
        assertEquals(3, new Settings(context).index(Settings.FILTER));
        assertTrue(new GameStore(context).isFavourite(game));

        summary = Backup.restore(context, new ByteArrayInputStream(withGames.toByteArray()));
        assertEquals(1, summary.games);
        assertArrayEquals(rom, Files.readAllBytes(game.toPath()));
        assertTrue(new File(root, "roms").isDirectory());
    }

    @Test
    public void rejectsOtherZips() throws Exception {
        try {
            Backup.restore(RuntimeEnvironment.getApplication(), new ByteArrayInputStream(zip("saves/x.sav", "hi")));
            fail();
        } catch (IOException expected) {
        }
    }

    @Test
    public void staysInsideTheAppsFiles() throws Exception {
        File root = RuntimeEnvironment.getApplication().getFilesDir();
        assertNull(Backup.safeTarget(root, "saves/../../evil"));
        assertNull(Backup.safeTarget(root, "../saves/x"));
        assertNull(Backup.safeTarget(root, "shared_prefs/retroachievements.xml"));
        assertNull(Backup.safeTarget(root, "saves"));
        assertNull(Backup.safeTarget(root, "saves//x"));
        assertEquals(new File(root, "states/Game.s1"), Backup.safeTarget(root, "states/Game.s1"));
        assertEquals(new File(root, "skins/Mine/skin.json"), Backup.safeTarget(root, "skins/Mine/skin.json"));
    }

    private static byte[] zip(String name, String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(text.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
