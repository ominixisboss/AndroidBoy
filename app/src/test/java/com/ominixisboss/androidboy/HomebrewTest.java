package com.ominixisboss.androidboy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Reading Homebrew Hub search results, and adding downloaded ROMs to the library. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class HomebrewTest {
    // Trimmed from a real /api/search response.
    private static final String PAGE = "{\"results\": 3, \"page_total\": 2, \"page_current\": 1, \"page_elements\": 3,"
            + "\"entries\": ["
            + "{\"slug\": \"ucity\", \"title\": \"µCity\", \"developer\": \"AntonioND\", \"typetag\": \"game\","
            + " \"platform\": \"GBC\", \"license\": \"GPL-3.0\", \"basepath\": \"database-gb\","
            + " \"tags\": [\"Simulation\", \"Open Source\"], \"screenshots\": [\"title screen.png\"],"
            + " \"files\": [{\"filename\": \"ucity-src.zip\"}, {\"filename\": \"ucity.gbc\", \"playable\": true, \"default\": true}]},"
            + "{\"slug\": \"tuff\", \"title\": \"Tuff\", \"typetag\": \"game\", \"basepath\": \"database-gb\","
            + " \"files\": [{\"filename\": \"tuff.gb\", \"playable\": true}]},"
            + "{\"slug\": \"some-hack\", \"title\": \"A hack\", \"typetag\": \"hackrom\","
            + " \"files\": [{\"filename\": \"hack.gb\", \"playable\": true}]}"
            + "]}";

    @Test
    public void parsesSearchResults() throws Exception {
        Homebrew.Page page = Homebrew.parsePage(PAGE);
        assertEquals(1, page.page);
        assertEquals(2, page.pageTotal);
        // The ROM hack is left out.
        assertEquals(2, page.entries.size());

        Homebrew.Entry city = page.entries.get(0);
        assertEquals("ucity", city.slug);
        assertEquals("µCity", city.title);
        assertEquals("AntonioND · game · GPL-3.0", city.subtitle());
        assertEquals(Arrays.asList("Simulation", "Open Source"), city.tags);
        assertEquals("ucity.gbc", city.rom().filename);
        assertEquals("https://hh3.gbdev.io/static/database-gb/entries/ucity/title%20screen.png", city.screenshotUrl());
        assertEquals("https://hh3.gbdev.io/static/database-gb/entries/ucity/ucity.gbc",
                Homebrew.fileUrl(city, city.rom().filename));
        assertEquals("https://hh.gbdev.io/entry/ucity", city.pageUrl());

        Homebrew.Entry tuff = page.entries.get(1);
        assertEquals("game", tuff.subtitle());
        assertEquals("tuff.gb", tuff.rom().filename);
        assertNull(tuff.screenshotUrl());
    }

    @Test
    public void choosesTheRomToDownload() {
        assertEquals("b.gb", entry(file("a.gb", true, false), file("b.gb", true, true)).rom().filename);
        assertEquals("b.gbc", entry(file("a.zip", false, false), file("b.gbc", true, false)).rom().filename);
        assertEquals("a.zip", entry(file("readme.txt", false, false), file("a.zip", false, false)).rom().filename);
        assertNull(entry(file("readme.txt", false, false)).rom());
        assertNull(entry().rom());
    }

    @Test
    public void toleratesMissingFields() throws Exception {
        Homebrew.Page page = Homebrew.parsePage("{\"entries\": [{\"slug\": \"x\"}, {\"title\": \"no slug\"}]}");
        assertEquals(1, page.entries.size());
        assertEquals("x", page.entries.get(0).title);
        assertEquals("", page.entries.get(0).subtitle());
        assertNull(page.entries.get(0).rom());
        // Files are served from the Game Boy database unless the entry says otherwise.
        assertTrue(Homebrew.fileUrl(page.entries.get(0), "x.gb").startsWith("https://hh3.gbdev.io/static/database-gb/"));
        assertEquals(0, Homebrew.parsePage("{}").entries.size());
    }

    @Test
    public void searchUrl() {
        Uri uri = Uri.parse(Homebrew.searchUrl(Homebrew.PLATFORM_GBC, " tetris clone ", 3));
        assertEquals("/api/search", uri.getPath());
        assertEquals("GBC", uri.getQueryParameter("platform"));
        assertEquals("3", uri.getQueryParameter("page"));
        assertEquals(String.valueOf(Homebrew.PAGE_SIZE), uri.getQueryParameter("results"));
        assertEquals("tetris clone", uri.getQueryParameter("q"));
        assertNull(Uri.parse(Homebrew.searchUrl(Homebrew.PLATFORM_GB, "  ", 1)).getQueryParameter("q"));
    }

    @Test
    public void addsDownloadedRoms() throws Exception {
        RomLibrary library = new RomLibrary(RuntimeEnvironment.getApplication());
        byte[] rom = new byte[0x8000];
        rom[0x143] = (byte) 0x80; // Game Boy Color.

        File plain = library.addRom("ucity.gbc", rom);
        assertEquals("ucity.gbc", plain.getName());
        assertArrayEquals(rom, Files.readAllBytes(plain.toPath()));

        File zipped = library.addRom("game.zip", zip("folder/inner.gb", rom));
        assertEquals("inner.gb", zipped.getName());
        assertArrayEquals(rom, Files.readAllBytes(zipped.toPath()));

        assertEquals("no extension.gbc", library.addRom("no extension", rom).getName());
        assertEquals("a_b.gb", library.addRom("a/b.gb", rom).getName());

        try {
            library.addRom("tiny.gb", new byte[16]);
            fail();
        } catch (IOException expected) {
        }
        try {
            library.addRom("docs.zip", zip("readme.txt", rom));
            fail();
        } catch (IOException expected) {
        }
    }

    private static byte[] zip(String name, byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(data);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static Homebrew.RomFile file(String name, boolean playable, boolean isDefault) {
        return new Homebrew.RomFile(name, playable, isDefault);
    }

    private static Homebrew.Entry entry(Homebrew.RomFile... files) {
        return new Homebrew.Entry("slug", "Title", "", "", "", "", "", Collections.<String>emptyList(),
                Collections.<String>emptyList(), Arrays.asList(files));
    }
}
