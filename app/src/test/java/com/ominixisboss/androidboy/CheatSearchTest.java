package com.ominixisboss.androidboy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Narrowing RAM down to the address holding a number, and making a code for it. */
public class CheatSearchTest {
    /** A fake Game Boy's RAM: $C000-$DFFF and $FF80-$FFFE, no cartridge RAM. */
    private static final class FakeMemory implements CheatSearch.Memory {
        final byte[] ram = new byte[0x10000];
        boolean cartRam;

        @Override
        public int read(int address, byte[] buffer) {
            if (address >= 0xA000 && address < 0xC000 && !cartRam) return 0;
            int count = Math.min(buffer.length, 0x10000 - address);
            System.arraycopy(ram, address, buffer, 0, count);
            return count;
        }
    }

    @Test
    public void findsTheLivesCounter() {
        FakeMemory memory = new FakeMemory();
        memory.ram[0xFF70] = (byte) 0xFF; // A Game Boy: no work RAM banks.
        memory.ram[0xC123] = 3; // Lives.
        memory.ram[0xC500] = 3; // Something else that happens to be 3...
        memory.ram[0xD000] = 7; // ...and a timer that keeps going down.
        CheatSearch search = new CheatSearch();
        assertFalse(search.isStarted());
        search.start(memory);
        assertEquals(0x2000 + 0x7F, search.count()); // No cartridge RAM to search.

        memory.ram[0xC123] = 2; // Lost a life.
        memory.ram[0xD000] = 6;
        search.filter(memory, CheatSearch.Filter.DECREASED, 0);
        assertEquals(2, search.count());

        memory.ram[0xD000] = 5;
        search.filter(memory, CheatSearch.Filter.UNCHANGED, 0);
        assertEquals(1, search.count());
        List<CheatSearch.Result> results = search.results(10);
        assertEquals(0xC123, results.get(0).address);
        assertEquals(2, results.get(0).value);
        assertEquals("0109 23C1".replace(" ", ""), search.code(0xC123, 9));
    }

    @Test
    public void equalAndComparisons() {
        FakeMemory memory = new FakeMemory();
        memory.cartRam = true;
        memory.ram[0xA010] = 100;
        CheatSearch search = new CheatSearch();
        search.start(memory);
        assertEquals(1, search.filter(memory, CheatSearch.Filter.EQUAL, 100));
        assertEquals(1, search.filter(memory, CheatSearch.Filter.GREATER, 99));
        assertEquals(0, search.filter(memory, CheatSearch.Filter.LESS, 100));
        assertTrue(search.results(5).isEmpty());
    }

    @Test
    public void colorWorkRamBanks() {
        assertEquals(1, CheatSearch.wramBank(0xFF));
        assertEquals(1, CheatSearch.wramBank(0xF8)); // Bank 0 selects bank 1.
        assertEquals(3, CheatSearch.wramBank(0xFB));
        // $D000-$DFFF in bank 3 pins the bank; other addresses use 01, any bank.
        assertEquals("0305 10D0".replace(" ", ""), CheatSearch.code(0xD010, 5, 3));
        assertEquals("0105 10C0".replace(" ", ""), CheatSearch.code(0xC010, 5, 3));
        assertEquals("0105 10D0".replace(" ", ""), CheatSearch.code(0xD010, 5, 1));
        assertTrue(Cheat.isValidCode(CheatSearch.code(0xFF90, 255, 1)));
    }

    @Test
    public void addressesAcrossRegions() {
        assertEquals(0xA000, CheatSearch.addressAt(0));
        assertEquals(0xC000, CheatSearch.addressAt(0x2000));
        assertEquals(0xFF80, CheatSearch.addressAt(0x4000));
        assertEquals(0xFFFE, CheatSearch.addressAt(CheatSearch.SIZE - 1));
    }

    @Test
    public void parsesNumbers() {
        assertEquals(12, CheatSearchDialog.parseByte(" 12 "));
        assertEquals(255, CheatSearchDialog.parseByte("0xFF"));
        assertEquals(16, CheatSearchDialog.parseByte("$10"));
        assertEquals(-1, CheatSearchDialog.parseByte("256"));
        assertEquals(-1, CheatSearchDialog.parseByte("lots"));
    }
}
