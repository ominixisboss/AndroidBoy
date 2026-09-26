package com.ominixisboss.androidboy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Turning camera frames into the Game Boy Camera's 128×112 picture. */
public class CameraFeedTest {
    /** A 320×240 frame, dark on the left and bright on the right, with padded rows. */
    private static byte[] halves(int width, int height, int rowStride) {
        byte[] luma = new byte[rowStride * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) luma[y * rowStride + x] = (byte) (x < width / 2 ? 20 : 220);
        }
        return luma;
    }

    private static int at(byte[] pixels, int x, int y) {
        return pixels[y * CameraFeed.WIDTH + x] & 0xFF;
    }

    @Test
    public void cropsAndShrinks() {
        byte[] out = CameraFeed.toSensor(halves(320, 240, 336), 320, 240, 336, 1, 0, false);
        assertEquals(CameraFeed.WIDTH * CameraFeed.HEIGHT, out.length);
        assertEquals(20, at(out, 10, 50));
        assertEquals(220, at(out, 117, 50));
    }

    @Test
    public void mirrorsTheFrontCamera() {
        byte[] out = CameraFeed.toSensor(halves(320, 240, 320), 320, 240, 320, 1, 0, true);
        assertEquals(220, at(out, 10, 50));
        assertEquals(20, at(out, 117, 50));
    }

    @Test
    public void rotatesUpright() {
        // Sensor mounted at 90°: its left half becomes the upright picture's top half.
        byte[] out = CameraFeed.toSensor(halves(320, 240, 320), 320, 240, 320, 1, 90, false);
        assertEquals(20, at(out, 64, 5));
        assertEquals(220, at(out, 64, 106));
        // ...and at 270°, the bottom half.
        out = CameraFeed.toSensor(halves(320, 240, 320), 320, 240, 320, 1, 270, false);
        assertEquals(220, at(out, 64, 5));
        assertEquals(20, at(out, 64, 106));
    }
}
