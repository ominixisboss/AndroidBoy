package com.ominixisboss.androidboy;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * Finds where a game keeps a number (lives, money, health) by narrowing down its RAM: take a
 * snapshot, play until the number changes, keep the addresses that changed the same way, and
 * repeat. A found address becomes a GameShark code that holds it at a chosen value.
 */
final class CheatSearch {
    /** The RAM a search covers: cartridge RAM, work RAM, and the CPU's high RAM. */
    static final int[][] REGIONS = {{0xA000, 0x2000}, {0xC000, 0x2000}, {0xFF80, 0x7F}};
    /** SVBK, the Game Boy Color's work RAM bank register. */
    static final int WRAM_BANK_REGISTER = 0xFF70;
    static final int SIZE;

    static {
        int size = 0;
        for (int[] region : REGIONS) size += region[1];
        SIZE = size;
    }

    enum Filter {
        EQUAL("Equal to…", true),
        CHANGED("Changed", false),
        UNCHANGED("Didn't change", false),
        INCREASED("Went up", false),
        DECREASED("Went down", false),
        GREATER("Greater than…", true),
        LESS("Less than…", true);

        final String label;
        final boolean needsValue;

        Filter(String label, boolean needsValue) {
            this.label = label;
            this.needsValue = needsValue;
        }
    }

    /** Reads memory; in the emulator this is {@link Emulator#nativeReadMemory}. */
    interface Memory {
        int read(int address, byte[] buffer);
    }

    /** An address still in the running, and what's there now. */
    static final class Result {
        final int address;
        final int value;

        Result(int address, int value) {
            this.address = address;
            this.value = value;
        }
    }

    private final BitSet candidates = new BitSet(SIZE);
    private byte[] previous;
    private int wramBank = 1;

    boolean isStarted() {
        return previous != null;
    }

    int count() {
        return candidates.cardinality();
    }

    /** Starts over: every address that exists in this game is a candidate. */
    void start(Memory memory) {
        candidates.clear();
        previous = new byte[SIZE];
        BitSet readable = snapshot(memory, previous);
        candidates.or(readable);
    }

    /** Keeps the addresses whose values pass {@code filter}. Returns how many are left. */
    int filter(Memory memory, Filter filter, int value) {
        if (!isStarted()) start(memory);
        byte[] now = new byte[SIZE];
        BitSet readable = snapshot(memory, now);
        candidates.and(readable);
        for (int i = candidates.nextSetBit(0); i >= 0; i = candidates.nextSetBit(i + 1)) {
            int before = previous[i] & 0xFF;
            int after = now[i] & 0xFF;
            if (!passes(filter, before, after, value)) candidates.clear(i);
        }
        previous = now;
        return count();
    }

    static boolean passes(Filter filter, int before, int after, int value) {
        switch (filter) {
            case EQUAL: return after == value;
            case CHANGED: return after != before;
            case UNCHANGED: return after == before;
            case INCREASED: return after > before;
            case DECREASED: return after < before;
            case GREATER: return after > value;
            case LESS: return after < value;
            default: return false;
        }
    }

    /** Up to {@code max} remaining addresses with their current values. */
    List<Result> results(int max) {
        List<Result> results = new ArrayList<>();
        if (!isStarted()) return results;
        for (int i = candidates.nextSetBit(0); i >= 0 && results.size() < max; i = candidates.nextSetBit(i + 1)) {
            results.add(new Result(addressAt(i), previous[i] & 0xFF));
        }
        return results;
    }

    /** Reads every region into {@code out}; returns which bytes exist (a game may have no cartridge RAM). */
    private BitSet snapshot(Memory memory, byte[] out) {
        BitSet readable = new BitSet(SIZE);
        int offset = 0;
        for (int[] region : REGIONS) {
            byte[] buffer = new byte[region[1]];
            int read = Math.max(0, Math.min(region[1], memory.read(region[0], buffer)));
            System.arraycopy(buffer, 0, out, offset, read);
            readable.set(offset, offset + read);
            offset += region[1];
        }
        byte[] bank = new byte[1];
        if (memory.read(WRAM_BANK_REGISTER, bank) == 1) wramBank = wramBank(bank[0] & 0xFF);
        return readable;
    }

    /** The work RAM bank at $D000: 1-7 on a Game Boy Color (0 means 1); a Game Boy reads $FF there. */
    static int wramBank(int svbk) {
        if (svbk == 0xFF) return 1;
        int bank = svbk & 7;
        return bank == 0 ? 1 : bank;
    }

    static int addressAt(int index) {
        for (int[] region : REGIONS) {
            if (index < region[1]) return region[0] + index;
            index -= region[1];
        }
        throw new IndexOutOfBoundsException();
    }

    /**
     * A GameShark code that makes {@code address} read as {@code value}: 01 (any bank) or the work
     * RAM bank the value was found in, the value, then the address low byte first.
     */
    String code(int address, int value) {
        return code(address, value, wramBank);
    }

    static String code(int address, int value, int wramBank) {
        // Bank 01 means any bank. $D000-$DFFF is banked on the Color: pin other banks.
        int bank = address >= 0xD000 && address < 0xE000 && wramBank > 1 ? wramBank : 1;
        return String.format(Locale.ROOT, "%02X%02X%02X%02X", bank, value & 0xFF, address & 0xFF, (address >> 8) & 0xFF);
    }
}
