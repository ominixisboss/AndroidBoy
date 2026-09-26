package com.ominixisboss.androidboy;

import static org.junit.Assert.assertEquals;

import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

/** Game Boy Printer printouts become pictures; games remember what's in their link port. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PrintoutTest {
    @Test
    public void printoutPixels() {
        int[] pixels = new int[160 * 2];
        pixels[0] = 0xFF000000;          // Black.
        pixels[1] = 0xFFFFFFFF;          // White.
        pixels[160] = 0xFF0000FF;        // Red, in the core's 0xAABBGGRR.
        pixels[161] = 0xFFFF0000;        // Blue.
        Bitmap bitmap = EmulatorActivity.printoutBitmap(pixels);
        assertEquals(160, bitmap.getWidth());
        assertEquals(2, bitmap.getHeight());
        assertEquals(0xFF000000, bitmap.getPixel(0, 0));
        assertEquals(0xFFFFFFFF, bitmap.getPixel(1, 0));
        assertEquals(0xFFFF0000, bitmap.getPixel(0, 1));
        assertEquals(0xFF0000FF, bitmap.getPixel(1, 1));
        assertEquals(640, Gallery.scaleUp(bitmap, 4).getWidth());
    }

    @Test
    public void linkPortIsRemembered() {
        GameStore store = new GameStore(RuntimeEnvironment.getApplication());
        File rom = new File("Camera.gb");
        assertEquals(Emulator.LINK_NOTHING, store.linkAccessory(rom));
        store.setLinkAccessory(rom, Emulator.LINK_PRINTER);
        assertEquals(Emulator.LINK_PRINTER, store.linkAccessory(rom));
        store.forget(rom);
        assertEquals(Emulator.LINK_NOTHING, store.linkAccessory(rom));
    }
}
