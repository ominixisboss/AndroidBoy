package com.ominixisboss.androidboy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Pixels are really drawn.
public class ClearSkinTest {
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 2340;
    private static final int DPAD = Emulator.KEY_UP | Emulator.KEY_DOWN | Emulator.KEY_LEFT | Emulator.KEY_RIGHT;

    @After
    public void resetCustomization() {
        ClearSkin.customize(null, false, 1f);
    }

    private static Skin.Layout layout(Skin skin) {
        Skin.Layout layout = new Skin.Layout();
        skin.layout(layout, WIDTH, HEIGHT, 160, 144, true, false);
        return layout;
    }

    private static Bitmap background(Skin skin, Skin.Layout layout) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        skin.drawBackground(new Canvas(bitmap), layout);
        return bitmap;
    }

    /** A spot on the shell clear of the controls, lens, chips and speaker. */
    private static int shellPixel(Bitmap bitmap) {
        return bitmap.getPixel(Math.round(WIDTH * 0.1f), Math.round(HEIGHT * 0.75f));
    }

    @Test
    public void backdropShowsThroughThePlastic() {
        ClearSkin skin = ClearSkin.find("clear");
        Skin.Layout layout = layout(skin);
        int board = shellPixel(background(skin, layout));
        assertFalse("the board isn't red", Color.red(board) > Color.green(board) + 40);

        ClearSkin.customize(new ColorDrawable(Color.RED), false, 1f);
        int tinted = shellPixel(background(skin, layout));
        assertTrue("red shows through: " + Integer.toHexString(tinted), Color.red(tinted) > Color.green(tinted) + 60);
        assertFalse("a still picture doesn't keep redrawing", skin.animated());

        ClearSkin.customize(new ColorDrawable(Color.RED), false, 0f);
        int clear = shellPixel(background(skin, layout));
        assertTrue("no tint shows it brighter", Color.red(clear) >= Color.red(tinted) && Color.green(clear) <= Color.green(tinted) + 2);
    }

    @Test
    public void backdropIsSavedAndApplied() throws IOException {
        Application app = RuntimeEnvironment.getApplication();
        File picture = new File(app.getCacheDir(), "backdrop.png");
        Bitmap blue = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888);
        blue.eraseColor(Color.BLUE);
        try (FileOutputStream out = new FileOutputStream(picture)) {
            blue.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        ClearBackdrop.save(app, Uri.fromFile(picture));
        assertTrue(ClearBackdrop.exists(app));
        ClearSkin skin = (ClearSkin) new SkinLibrary(app).load("clear:clear");
        assertNotNull(skin);
        int pixel = shellPixel(background(skin, layout(skin)));
        assertTrue("blue shows through: " + Integer.toHexString(pixel), Color.blue(pixel) > Color.red(pixel) + 60);

        ClearBackdrop.remove(app);
        assertFalse(ClearBackdrop.exists(app));
        pixel = shellPixel(background(skin, layout(skin)));
        assertFalse("back to the board", Color.blue(pixel) > Color.red(pixel) + 60);
    }

    @Test(expected = IOException.class)
    public void notAPictureIsRefused() throws IOException {
        Application app = RuntimeEnvironment.getApplication();
        File text = new File(app.getCacheDir(), "notes.txt");
        try (FileOutputStream out = new FileOutputStream(text)) {
            out.write("hello".getBytes("UTF-8"));
        }
        ClearBackdrop.save(app, Uri.fromFile(text));
    }

    /** However far the d-pad rocks, nothing is drawn outside its own area. */
    @Test
    public void rockingDpadStaysInItsWell() {
        for (String id : new String[] {"clear", "crystal"}) {
            ClearSkin skin = ClearSkin.find(id);
            Skin.Layout layout = layout(skin);
            RectF bounds = null;
            int index = -1;
            for (int i = 0; i < layout.controls.size(); i++) {
                if (layout.controls.get(i).keys == DPAD) {
                    bounds = layout.controls.get(i).bounds;
                    index = i;
                }
            }
            assertNotNull(bounds);
            // Just the d-pad, at rest and rocked as far as the springs go, on transparent bitmaps.
            Skin.Layout only = new Skin.Layout();
            only.reset(WIDTH, HEIGHT, true);
            only.extras = layout.extras;
            only.controls.add(layout.controls.get(index));
            for (float[] tilt : new float[][] {{1.3f, 0}, {-1.3f, 0}, {0, 1.3f}, {0, -1.3f}, {1.3f, 1.3f}}) {
                Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
                Skin.Motion motion = new Skin.Motion();
                motion.ensureSize(1);
                motion.tiltX = tilt[0];
                motion.tiltY = tilt[1];
                skin.drawControls(new Canvas(bitmap), only, DPAD, motion);
                RectF allowed = new RectF(bounds);
                allowed.inset(-bounds.width() * 0.03f, -bounds.height() * 0.03f);
                int[] pixels = new int[WIDTH * HEIGHT];
                bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
                for (int y = 0; y < HEIGHT; y++) {
                    for (int x = 0; x < WIDTH; x++) {
                        if (allowed.contains(x, y)) continue;
                        assertEquals(skin.id() + " tilted " + tilt[0] + "," + tilt[1] + " drew outside at " + x + "," + y,
                                0, Color.alpha(pixels[y * WIDTH + x]));
                    }
                }
            }
        }
    }
}
