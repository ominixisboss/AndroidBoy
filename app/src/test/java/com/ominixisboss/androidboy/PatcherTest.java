package com.ominixisboss.androidboy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** IPS, UPS and BPS patches, built here and applied. */
public class PatcherTest {
    private static byte[] game(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    // ---- IPS ----

    @Test
    public void ipsRecordsRunsAndGrowing() throws IOException {
        byte[] rom = game(0x2000, 1);
        ByteArrayOutputStream patch = new ByteArrayOutputStream();
        patch.write("PATCH".getBytes(StandardCharsets.US_ASCII));
        // Three bytes at 0x100.
        patch.write(new byte[] {0x00, 0x01, 0x00, 0x00, 0x03, 0x11, 0x22, 0x33});
        // A run of 0x10 bytes of 0x7F at 0x1800.
        patch.write(new byte[] {0x00, 0x18, 0x00, 0x00, 0x00, 0x00, 0x10, 0x7F});
        // Two bytes past the end, growing the game.
        patch.write(new byte[] {0x00, 0x20, 0x04, 0x00, 0x02, 0x44, 0x55});
        patch.write("EOF".getBytes(StandardCharsets.US_ASCII));

        byte[] out = Patcher.apply(rom, patch.toByteArray());
        byte[] expected = Arrays.copyOf(rom, 0x2006);
        expected[0x100] = 0x11;
        expected[0x101] = 0x22;
        expected[0x102] = 0x33;
        Arrays.fill(expected, 0x1800, 0x1810, (byte) 0x7F);
        expected[0x2004] = 0x44;
        expected[0x2005] = 0x55;
        assertArrayEquals(expected, out);
        // The original is untouched.
        assertArrayEquals(game(0x2000, 1), rom);
    }

    @Test
    public void ipsTruncates() throws IOException {
        byte[] rom = game(0x1000, 2);
        byte[] patch = concat("PATCH".getBytes(StandardCharsets.US_ASCII),
                new byte[] {0x00, 0x00, 0x10, 0x00, 0x01, 0x42},
                "EOF".getBytes(StandardCharsets.US_ASCII), new byte[] {0x00, 0x08, 0x00});
        byte[] out = Patcher.apply(rom, patch);
        assertEquals(0x800, out.length);
        assertEquals(0x42, out[0x10]);
    }

    @Test
    public void damagedIpsIsRefused() {
        byte[] patch = concat("PATCH".getBytes(StandardCharsets.US_ASCII), new byte[] {0x00, 0x01, 0x00, 0x00, 0x10, 0x01});
        try {
            Patcher.apply(game(0x1000, 3), patch);
            fail("A cut-off patch was applied");
        } catch (Patcher.PatchException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("damaged"));
        }
    }

    // ---- UPS ----

    @Test
    public void upsAppliesAndChecksTheGame() throws IOException {
        byte[] source = game(0x4000, 4);
        byte[] target = Arrays.copyOf(source, 0x4800);
        target[5] ^= 0x5A;
        for (int i = 0x1000; i < 0x1040; i++) target[i] = (byte) i;
        for (int i = 0x4000; i < 0x4800; i++) target[i] = (byte) (i * 7);
        byte[] patch = ups(source, target);

        Patcher.Patch found = Patcher.find("My Hack.ups", patch);
        assertNotNull(found);
        assertEquals("UPS", found.format());
        assertEquals("My Hack", found.gameName());
        assertEquals(Patcher.crc32(source, source.length), found.sourceCrc());
        assertArrayEquals(target, Patcher.apply(source, patch));

        // Another version of the game, and a game that's already patched, are caught.
        byte[] other = source.clone();
        other[0x200] ^= 1;
        assertRefused(other, patch, "different version");
        assertRefused(target, patch, "already has this patch");
        // A damaged patch is caught by its own checksum.
        byte[] damaged = patch.clone();
        damaged[8] ^= 1;
        assertRefused(source, damaged, "damaged");
    }

    // ---- BPS ----

    @Test
    public void bpsAllFourCommands() throws IOException {
        byte[] source = game(0x3000, 5);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] expected = new byte[0x100 + 5 + 0x40 + 20 + 0x10];
        int at = 0;
        long[] relative = {0, 0}; // Where the last source copy and target copy ended.
        // Source read: the first 0x100 bytes unchanged.
        command(body, 0, 0x100);
        System.arraycopy(source, 0, expected, at, 0x100);
        at += 0x100;
        // Target read: 5 new bytes.
        byte[] fresh = {1, 2, 3, 4, 5};
        command(body, 1, fresh.length);
        body.write(fresh);
        System.arraycopy(fresh, 0, expected, at, fresh.length);
        at += fresh.length;
        // Source copy: 0x40 bytes from 0x2000 in the original.
        copy(body, 2, 0x40, 0x2000, relative);
        System.arraycopy(source, 0x2000, expected, at, 0x40);
        at += 0x40;
        // Target copy, overlapping: the last 5 bytes written, repeated to fill 20.
        int from = at - 5;
        copy(body, 3, 20, from, relative);
        for (int i = 0; i < 20; i++) expected[at + i] = expected[from + i];
        at += 20;
        // Source copy from earlier in the original, a negative offset from the last one.
        copy(body, 2, 0x10, 0x1000, relative);
        System.arraycopy(source, 0x1000, expected, at, 0x10);

        byte[] patch = bps(source, expected, body.toByteArray());
        assertEquals("BPS", Patcher.format(patch));
        assertArrayEquals(expected, Patcher.apply(source, patch));
        byte[] other = source.clone();
        other[0] ^= 1;
        assertRefused(other, patch, "different version");
    }

    @Test
    public void patchesAreFoundInsideZips() throws IOException {
        byte[] source = game(0x1000, 6);
        byte[] target = source.clone();
        target[1] ^= 3;
        byte[] patch = ups(source, target);
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipped)) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("Apply to your own copy".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("Hack v1.2/Hack v1.2.ups"));
            zip.write(patch);
        }
        Patcher.Patch found = Patcher.find("Hack.zip", zipped.toByteArray());
        assertNotNull(found);
        assertEquals("Hack v1.2", found.gameName());
        assertArrayEquals(target, Patcher.apply(source, found.data));
        // A game isn't a patch.
        assertNull(Patcher.find("game.gb", source));
    }

    @Test
    public void onlyPatchesAreTakenFromTheWeb() throws IOException {
        byte[] source = game(0x1000, 7);
        byte[] target = source.clone();
        target[2] ^= 9;
        byte[] patch = ups(source, target);
        assertArrayEquals(patch, Patcher.fromDownload("hack.ups", patch).get(0).data);

        // A game, a zip holding a game, and a web page are all refused.
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipped)) {
            zip.putNextEntry(new ZipEntry("Game (USA).gba"));
            zip.write(source);
        }
        byte[][] refused = {source, zipped.toByteArray(), "<html>Download</html>".getBytes(StandardCharsets.UTF_8)};
        for (byte[] download : refused) {
            try {
                Patcher.fromDownload("download.bin", download);
                fail("Took something that isn't a patch");
            } catch (Patcher.PatchException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("isn't a ROM hack patch")
                        || e.getMessage().contains("web page"));
            }
        }
    }

    @Test
    public void patchSearches() {
        assertEquals("Pokemon - Crystal Version", PatchSearchActivity.queryFor("Pokemon - Crystal Version (USA, Europe) (Rev 1) [!].gbc"));
        assertEquals("Super Mario Land 2", PatchSearchActivity.queryFor("Super_Mario_Land_2.gb"));
        String url = PatchSearchActivity.searchUrl("Pokémon Red", 0);
        assertTrue(url, url.startsWith("https://duckduckgo.com/html/?q="));
        assertTrue(url, url.contains("Pok%C3%A9mon+Red+site%3Aromhacking.net%2Fhacks"));
    }

    // ---- Building patches ----

    private static void assertRefused(byte[] rom, byte[] patch, String reason) {
        try {
            Patcher.apply(rom, patch);
            fail("Expected refusal: " + reason);
        } catch (Patcher.PatchException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(reason));
        }
    }

    private static byte[] ups(byte[] source, byte[] target) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("UPS1".getBytes(StandardCharsets.US_ASCII));
        varint(out, source.length);
        varint(out, target.length);
        int last = 0;
        int i = 0;
        while (i < target.length) {
            int a = i < source.length ? source[i] & 0xFF : 0;
            int b = target[i] & 0xFF;
            if (a == b) {
                i++;
                continue;
            }
            varint(out, i - last);
            while (i < target.length) {
                a = i < source.length ? source[i] & 0xFF : 0;
                b = target[i] & 0xFF;
                if (a == b) break;
                out.write(a ^ b);
                i++;
            }
            out.write(0);
            i++;
            last = i;
        }
        footer(out, source, target);
        return out.toByteArray();
    }

    private static byte[] bps(byte[] source, byte[] target, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("BPS1".getBytes(StandardCharsets.US_ASCII));
        varint(out, source.length);
        varint(out, target.length);
        byte[] metadata = "<hack/>".getBytes(StandardCharsets.UTF_8);
        varint(out, metadata.length);
        out.write(metadata);
        out.write(body);
        footer(out, source, target);
        return out.toByteArray();
    }

    private static void command(ByteArrayOutputStream out, int command, int length) {
        varint(out, ((long) (length - 1) << 2) | command);
    }

    /**
     * A source (2) or target (3) copy of {@code length} bytes from {@code absolute}, written as an
     * offset from where the last copy of its kind ended, as BPS does.
     */
    private static void copy(ByteArrayOutputStream out, int command, int length, long absolute, long[] relative) {
        command(out, command, length);
        int kind = command == 2 ? 0 : 1;
        long offset = absolute - relative[kind];
        varint(out, (Math.abs(offset) << 1) | (offset < 0 ? 1 : 0));
        relative[kind] = absolute + length;
    }

    private static void varint(ByteArrayOutputStream out, long value) {
        while (true) {
            long x = value & 0x7F;
            value >>= 7;
            if (value == 0) {
                out.write((int) (0x80 | x));
                break;
            }
            out.write((int) x);
            value--;
        }
    }

    private static void footer(ByteArrayOutputStream out, byte[] source, byte[] target) throws IOException {
        le32(out, Patcher.crc32(source, source.length));
        le32(out, Patcher.crc32(target, target.length));
        byte[] sofar = out.toByteArray();
        le32(out, Patcher.crc32(sofar, sofar.length));
    }

    private static void le32(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 4; i++) out.write((int) (value >> (8 * i)) & 0xFF);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }
}
