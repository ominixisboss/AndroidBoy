package com.ominixisboss.androidboy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Turbo buttons press for half of each period and release for the other half. */
public class TurboTest {
    @Test
    public void turboAlternates() {
        int a = Emulator.KEY_A;
        int start = Emulator.KEY_START;
        StringBuilder pattern = new StringBuilder();
        for (int frame = 0; frame < 8; frame++) {
            int keys = EmulatorThread.effectiveKeys(a | start, a, frame, 4);
            assertEquals(start, keys & start); // Ordinary keys stay held.
            pattern.append((keys & a) != 0 ? 'x' : '.');
        }
        assertEquals("xx..xx..", pattern.toString());
    }

    @Test
    public void slowerPeriods() {
        StringBuilder pattern = new StringBuilder();
        for (int frame = 0; frame < 12; frame++) {
            pattern.append((EmulatorThread.effectiveKeys(0, Emulator.KEY_B, frame, 6) & Emulator.KEY_B) != 0 ? 'x' : '.');
        }
        assertEquals("xxx...xxx...", pattern.toString());
    }

    @Test
    public void noTurboLeavesKeysAlone() {
        assertEquals(Emulator.KEY_A | Emulator.KEY_UP,
                EmulatorThread.effectiveKeys(Emulator.KEY_A | Emulator.KEY_UP, 0, 3, 4));
    }
}
