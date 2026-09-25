package com.ominixisboss.androidboy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.IOException;
import java.nio.ByteOrder;

/** Save-state thumbnails: encoding frames, where they're stored, cleanup and the slot list. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StateThumbnailTest {

    /** A frame whose pixel (x, y) has red = x, green = y and blue = 0x5A, all opaque. */
    private static Frame patternFrame(int width, int height) {
        Frame frame = new Frame();
        frame.width = width;
        frame.height = height;
        frame.pixels.order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                frame.pixels.put(i, (byte) x);
                frame.pixels.put(i + 1, (byte) y);
                frame.pixels.put(i + 2, (byte) 0x5A);
                frame.pixels.put(i + 3, (byte) 0xFF);
            }
        }
        return frame;
    }

    @Test
    public void thumbnailsKeepEveryPixelAndTheFrameSize() throws IOException {
        Application app = RuntimeEnvironment.getApplication();
        for (int[] size : new int[][] {{160, 144}, {256, 224}}) {
            File file = new File(app.getCacheDir(), "thumb-" + size[0] + ".png");
            StateThumbnails.write(file, patternFrame(size[0], size[1]));
            Bitmap bitmap = StateThumbnails.read(file);
            assertNotNull(bitmap);
            assertEquals(size[0], bitmap.getWidth());
            assertEquals(size[1], bitmap.getHeight());
            for (int[] p : new int[][] {{0, 0}, {37, 91}, {size[0] - 1, size[1] - 1}}) {
                int expected = 0xFF000000 | (p[0] << 16) | (p[1] << 8) | 0x5A;
                assertEquals("pixel " + p[0] + "," + p[1], Integer.toHexString(expected),
                        Integer.toHexString(bitmap.getPixel(p[0], p[1])));
            }
        }
        assertNull(StateThumbnails.read(new File(app.getCacheDir(), "missing.png")));
    }

    @Test
    public void thumbnailsLiveNextToTheirStatesAndGoAwayWithTheGame() throws IOException {
        Application app = RuntimeEnvironment.getApplication();
        RomLibrary library = new RomLibrary(app);
        File rom = library.romFile("Tetris.gb");
        RomLibrary.writeAtomically(rom, new byte[0x150]);
        assertEquals("Tetris.s3.png", library.thumbnailFile(rom, 3).getName());
        assertEquals(library.stateFile(rom, 3).getParentFile(), library.thumbnailFile(rom, 3).getParentFile());
        for (int slot = 1; slot <= RomLibrary.STATE_SLOTS; slot++) {
            RomLibrary.writeAtomically(library.stateFile(rom, slot), new byte[] {1});
            StateThumbnails.write(library.thumbnailFile(rom, slot), patternFrame(160, 144));
        }
        library.delete(rom);
        for (int slot = 1; slot <= RomLibrary.STATE_SLOTS; slot++) {
            assertFalse(library.thumbnailFile(rom, slot).exists());
            assertFalse(library.stateFile(rom, slot).exists());
        }
    }

    @Test
    public void slotListShowsThumbnailsDatesAndEmptySlots() throws IOException {
        Application app = RuntimeEnvironment.getApplication();
        // Needs the app's compiled layouts, which Gradle provides (unitTests.includeAndroidResources).
        assumeTrue("app resources not available",
                app.getResources().getIdentifier("item_state_slot", "layout", app.getPackageName()) != 0);
        RomLibrary library = new RomLibrary(app);
        File rom = library.romFile("Zelda.gbc");
        RomLibrary.writeAtomically(rom, new byte[0x150]);
        RomLibrary.writeAtomically(library.stateFile(rom, 2), new byte[] {1});
        StateThumbnails.write(library.thumbnailFile(rom, 2), patternFrame(160, 144));
        // A state saved before thumbnails existed.
        RomLibrary.writeAtomically(library.stateFile(rom, 5), new byte[] {1});

        SlotAdapter adapter = new SlotAdapter(app, library, rom);
        assertEquals(9, adapter.getCount());
        assertEquals(Integer.valueOf(2), adapter.getItem(1));
        FrameLayout parent = new FrameLayout(app);

        View used = adapter.getView(1, null, parent);
        assertEquals("Slot 2", ((TextView) used.findViewById(R.id.slot_title)).getText().toString());
        assertFalse(((TextView) used.findViewById(R.id.slot_detail)).getText().toString().equals("Empty"));
        BitmapDrawable drawable = (BitmapDrawable) ((ImageView) used.findViewById(R.id.slot_thumbnail)).getDrawable();
        assertNotNull(drawable);
        assertEquals(160, drawable.getBitmap().getWidth());
        assertFalse("thumbnails are drawn with sharp pixels", drawable.getPaint().isFilterBitmap());

        View old = adapter.getView(4, null, parent);
        assertNull("states from before thumbnails have none", ((ImageView) old.findViewById(R.id.slot_thumbnail)).getDrawable());
        assertFalse(((TextView) old.findViewById(R.id.slot_detail)).getText().toString().equals("Empty"));

        View empty = adapter.getView(0, used, parent);
        assertEquals("Slot 1", ((TextView) empty.findViewById(R.id.slot_title)).getText().toString());
        assertEquals("Empty", ((TextView) empty.findViewById(R.id.slot_detail)).getText().toString());
        assertNull("recycled row loses the old thumbnail", ((ImageView) empty.findViewById(R.id.slot_thumbnail)).getDrawable());
    }
}
