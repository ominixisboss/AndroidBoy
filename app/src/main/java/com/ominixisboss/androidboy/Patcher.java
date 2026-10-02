package com.ominixisboss.androidboy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ROM hack patches: IPS, UPS and BPS, the formats fan translations and hacks are shared in. A
 * patch holds only the hack's changes, so it's applied to the player's own copy of the original
 * game. UPS and BPS patches carry checksums of the game they were made for and of the result, so a
 * patch for another version of the game is caught before anything is saved; IPS has none.
 */
final class Patcher {
    /** Games are at most 32 MB (the biggest GBA cartridge); a patched game can't be bigger than this. */
    static final int MAX_OUTPUT = 64 * 1024 * 1024;
    private static final String[] EXTENSIONS = {".ips", ".ups", ".bps"};

    private Patcher() {}

    /** A patch file: its name and contents. */
    static final class Patch {
        final String name;
        final byte[] data;

        Patch(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }

        /** "IPS", "UPS" or "BPS". */
        String format() {
            return Patcher.format(data);
        }

        /**
         * The CRC-32 of the game this patch was made for, or -1 if the patch doesn't say (IPS).
         */
        long sourceCrc() {
            String format = format();
            if (!"UPS".equals(format) && !"BPS".equals(format) || data.length < 12) return -1;
            return readLe32(data, data.length - 12);
        }

        /** The name for the patched game: the patch's name, without its extension. */
        String gameName() {
            String base = name == null ? "Patched game" : new File(name).getName();
            int dot = base.lastIndexOf('.');
            return dot > 0 ? base.substring(0, dot) : base;
        }
    }

    /** Thrown when a patch can't be applied; the message is shown to the player. */
    static final class PatchException extends IOException {
        PatchException(String message) {
            super(message);
        }
    }

    /** "IPS", "UPS" or "BPS" if {@code data} is a patch, or null. */
    static String format(byte[] data) {
        if (startsWith(data, "PATCH")) return "IPS";
        if (startsWith(data, "UPS1")) return "UPS";
        if (startsWith(data, "BPS1")) return "BPS";
        return null;
    }

    static boolean isPatchName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    /**
     * The patch in a picked file: the file itself, or the first patch inside a .zip (hacks are
     * often zipped with a readme). Null if it isn't one.
     */
    static Patch find(String name, byte[] data) throws IOException {
        List<Patch> all = findAll(name, data);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Every patch in a picked file: the file itself, or each patch inside a .zip (hacks often come
     * with several, such as one per version of the game or per language), in the zip's order.
     */
    static List<Patch> findAll(String name, byte[] data) throws IOException {
        List<Patch> patches = new ArrayList<>();
        if (format(data) != null) {
            patches.add(new Patch(name, data));
            return patches;
        }
        if (data.length < 4 || data[0] != 'P' || data[1] != 'K' || data[2] != 3 || data[3] != 4) return patches;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !isPatchName(entry.getName())) continue;
                byte[] patch = readFully(zip, MAX_OUTPUT);
                if (format(patch) != null) patches.add(new Patch(new File(entry.getName()).getName(), patch));
            }
        }
        return patches;
    }

    /**
     * The patches in a file downloaded from the web, or a {@link PatchException} saying why there
     * are none. Only patches are taken this way, never games.
     */
    static List<Patch> fromDownload(String name, byte[] data) throws IOException {
        List<Patch> patches = findAll(name, data);
        if (!patches.isEmpty()) return patches;
        throw new PatchException(PatchDownloader.whyNotAPatch(name, data));
    }

    static long crc32(byte[] data, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, length);
        return crc.getValue();
    }

    /** Applies {@code patch} to {@code rom}, returning the patched game. */
    static byte[] apply(byte[] rom, byte[] patch) throws PatchException {
        String format = format(patch);
        if (format == null) throw new PatchException("That isn't an IPS, UPS or BPS patch");
        try {
            switch (format) {
                case "IPS": return applyIps(rom, patch);
                case "UPS": return applyUps(rom, patch);
                default: return applyBps(rom, patch);
            }
        } catch (ArrayIndexOutOfBoundsException | NegativeArraySizeException e) {
            throw new PatchException("The patch is damaged");
        }
    }

    // ---- IPS ----

    private static byte[] applyIps(byte[] rom, byte[] patch) throws PatchException {
        byte[] out = Arrays.copyOf(rom, rom.length);
        int size = rom.length;
        int pos = 5;
        while (true) {
            if (pos + 3 > patch.length) throw new PatchException("The patch is damaged (it ends too soon)");
            int offset = readBe(patch, pos, 3);
            pos += 3;
            if (offset == 0x454F46) break; // "EOF".
            if (pos + 2 > patch.length) throw new PatchException("The patch is damaged (it ends too soon)");
            int length = readBe(patch, pos, 2);
            pos += 2;
            if (length > 0) {
                if (pos + length > patch.length) throw new PatchException("The patch is damaged (it ends too soon)");
                out = grow(out, offset + length);
                System.arraycopy(patch, pos, out, offset, length);
                pos += length;
                size = Math.max(size, offset + length);
            } else {
                // A run of one repeated byte.
                if (pos + 3 > patch.length) throw new PatchException("The patch is damaged (it ends too soon)");
                int count = readBe(patch, pos, 2);
                byte value = patch[pos + 2];
                pos += 3;
                out = grow(out, offset + count);
                Arrays.fill(out, offset, offset + count, value);
                size = Math.max(size, offset + count);
            }
        }
        // An optional size to cut the game down to, after "EOF".
        if (pos + 3 <= patch.length) size = Math.min(readBe(patch, pos, 3), MAX_OUTPUT);
        return size == out.length ? out : Arrays.copyOf(out, size);
    }

    private static byte[] grow(byte[] data, int size) throws PatchException {
        if (size > MAX_OUTPUT) throw new PatchException("The patched game would be too big");
        return size <= data.length ? data : Arrays.copyOf(data, size);
    }

    // ---- UPS ----

    private static byte[] applyUps(byte[] rom, byte[] patch) throws PatchException {
        checkFooter(patch);
        int[] pos = {4};
        long sourceSize = readVarint(patch, pos);
        long targetSize = readVarint(patch, pos);
        if (targetSize > MAX_OUTPUT) throw new PatchException("The patched game would be too big");
        checkSource(rom, sourceSize, readLe32(patch, patch.length - 12), readLe32(patch, patch.length - 8));
        byte[] out = Arrays.copyOf(rom, (int) targetSize);
        long offset = 0;
        int end = patch.length - 12;
        while (pos[0] < end) {
            offset += readVarint(patch, pos);
            while (true) {
                if (pos[0] >= end) throw new PatchException("The patch is damaged");
                int x = patch[pos[0]++] & 0xFF;
                if (x == 0) {
                    offset++;
                    break;
                }
                if (offset < out.length) out[(int) offset] ^= (byte) x;
                offset++;
            }
        }
        checkTarget(out, readLe32(patch, patch.length - 8));
        return out;
    }

    // ---- BPS ----

    private static byte[] applyBps(byte[] rom, byte[] patch) throws PatchException {
        checkFooter(patch);
        int[] pos = {4};
        long sourceSize = readVarint(patch, pos);
        long targetSize = readVarint(patch, pos);
        long metadataSize = readVarint(patch, pos);
        if (targetSize > MAX_OUTPUT) throw new PatchException("The patched game would be too big");
        pos[0] += (int) metadataSize;
        checkSource(rom, sourceSize, readLe32(patch, patch.length - 12), readLe32(patch, patch.length - 8));
        byte[] out = new byte[(int) targetSize];
        int outOffset = 0;
        long sourceRelative = 0;
        long targetRelative = 0;
        int end = patch.length - 12;
        while (pos[0] < end) {
            long data = readVarint(patch, pos);
            int command = (int) (data & 3);
            long length = (data >> 2) + 1;
            if (outOffset + length > out.length) throw new PatchException("The patch is damaged");
            switch (command) {
                case 0: // Source read: the same bytes as the original, in the same place.
                    for (long i = 0; i < length; i++, outOffset++) {
                        out[outOffset] = outOffset < rom.length ? rom[outOffset] : 0;
                    }
                    break;
                case 1: // Target read: new bytes, from the patch.
                    System.arraycopy(patch, pos[0], out, outOffset, (int) length);
                    pos[0] += (int) length;
                    outOffset += (int) length;
                    break;
                case 2: { // Source copy: bytes from elsewhere in the original.
                    long d = readVarint(patch, pos);
                    sourceRelative += ((d & 1) != 0 ? -1 : 1) * (d >> 1);
                    for (long i = 0; i < length; i++) out[outOffset++] = rom[(int) sourceRelative++];
                    break;
                }
                default: { // Target copy: bytes already written, which may overlap.
                    long d = readVarint(patch, pos);
                    targetRelative += ((d & 1) != 0 ? -1 : 1) * (d >> 1);
                    for (long i = 0; i < length; i++) out[outOffset++] = out[(int) targetRelative++];
                    break;
                }
            }
        }
        checkTarget(out, readLe32(patch, patch.length - 8));
        return out;
    }

    // ---- Checks ----

    private static void checkFooter(byte[] patch) throws PatchException {
        if (patch.length < 16) throw new PatchException("The patch is damaged (it's too short)");
        if (crc32(patch, patch.length - 4) != readLe32(patch, patch.length - 4)) {
            throw new PatchException("The patch is damaged (its checksum is wrong); try downloading it again");
        }
    }

    private static void checkSource(byte[] rom, long size, long crc, long targetCrc) throws PatchException {
        long actual = crc32(rom, rom.length);
        if (actual == crc && rom.length == size) return;
        if (actual == targetCrc) throw new PatchException("That game already has this patch applied");
        throw new PatchException(String.format(Locale.ROOT,
                "This patch is for a different version of the game (it needs one with CRC32 %08X; this one is %08X)",
                crc, actual));
    }

    private static void checkTarget(byte[] out, long crc) throws PatchException {
        if (crc32(out, out.length) != crc) throw new PatchException("The patched game came out wrong; the patch may be damaged");
    }

    // ---- Reading ----

    private static boolean startsWith(byte[] data, String magic) {
        if (data == null || data.length < magic.length()) return false;
        for (int i = 0; i < magic.length(); i++) {
            if (data[i] != magic.charAt(i)) return false;
        }
        return true;
    }

    private static int readBe(byte[] data, int pos, int bytes) {
        int value = 0;
        for (int i = 0; i < bytes; i++) value = (value << 8) | (data[pos + i] & 0xFF);
        return value;
    }

    static long readLe32(byte[] data, int pos) {
        return (data[pos] & 0xFFL) | (data[pos + 1] & 0xFFL) << 8 | (data[pos + 2] & 0xFFL) << 16 | (data[pos + 3] & 0xFFL) << 24;
    }

    /** UPS and BPS numbers: seven bits a byte, the last byte marked by its top bit. */
    private static long readVarint(byte[] data, int[] pos) throws PatchException {
        long value = 0;
        long shift = 1;
        while (true) {
            if (pos[0] >= data.length) throw new PatchException("The patch is damaged");
            int x = data[pos[0]++] & 0xFF;
            value += (x & 0x7F) * shift;
            if ((x & 0x80) != 0) break;
            shift <<= 7;
            value += shift;
            if (shift > (1L << 42)) throw new PatchException("The patch is damaged");
        }
        return value;
    }

    private static byte[] readFully(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > limit) throw new IOException("The patch is too big");
        }
        return out.toByteArray();
    }
}
