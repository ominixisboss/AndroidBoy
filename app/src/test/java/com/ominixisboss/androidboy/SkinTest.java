package com.ominixisboss.androidboy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.SystemClock;
import android.view.MotionEvent;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Lays out and draws every built-in theme and the example skin (docs/skins/example) in both
 * orientations, checks the geometry and touch handling, and writes previews to build/skin-previews.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SkinTest {
    private static final int PHONE_SHORT = 1080;
    private static final int PHONE_LONG = 2340;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static File exampleSkinDir() {
        // Gradle runs tests in app/; other runners may use the repository root.
        for (String path : new String[] {"../docs/skins/example", "docs/skins/example"}) {
            File dir = new File(path);
            if (new File(dir, "skin.json").isFile()) return dir;
        }
        String root = System.getProperty("androidboy.root");
        if (root != null) return new File(root, "docs/skins/example");
        throw new AssertionError("docs/skins/example not found");
    }

    private static List<Skin> allSkins() throws IOException {
        List<Skin> skins = new ArrayList<>();
        for (ThemeSkin theme : ThemeSkin.ALL) skins.add(theme);
        skins.add(ImageSkin.load(exampleSkinDir()));
        skins.addAll(bundledSkins());
        return skins;
    }

    /** The image skins in assets/skins, loaded the way the app does. */
    private static List<Skin> bundledSkins() throws IOException {
        SkinLibrary library = new SkinLibrary(RuntimeEnvironment.getApplication());
        List<Skin> skins = new ArrayList<>();
        for (SkinLibrary.Entry entry : library.list()) {
            if (!entry.id.startsWith("bundled:")) continue;
            // Loaded directly, so a failure shows its reason; then as the active skin, as the app does.
            Skin skin = library.loadBundled(entry.id.substring("bundled:".length()));
            assertEquals(entry.id, skin.id());
            assertEquals(entry.name, skin.name());
            library.setActive(entry.id);
            assertTrue(entry.id + " loads as the active skin", library.loadActive() instanceof ImageSkin);
            skins.add(skin);
        }
        library.setActive(ThemeSkin.fallback().id());
        return skins;
    }

    @Test
    public void everySkinLaysOutSensiblyInBothOrientations() throws IOException {
        for (Skin skin : allSkins()) {
            for (boolean portrait : new boolean[] {true, false}) {
                int width = portrait ? PHONE_SHORT : PHONE_LONG;
                int height = portrait ? PHONE_LONG : PHONE_SHORT;
                String what = skin.id() + (portrait ? " portrait" : " landscape");
                Skin.Layout layout = new Skin.Layout();
                skin.layout(layout, width, height, 160, 144, true, false);

                Rect screen = layout.screen;
                assertTrue(what + ": screen inside the view", screen.left >= 0 && screen.top >= 0
                        && screen.right <= width && screen.bottom <= height);
                assertEquals(what + ": 10:9 screen", 160f / 144f, (float) screen.width() / screen.height(), 0.02f);
                // The screen should fill most of whichever dimension limits it.
                float fill = portrait ? (float) screen.width() / width : (float) screen.height() / height;
                assertTrue(what + ": screen uses most of the view (" + fill + ")", fill >= 0.7f);

                boolean hasMenu = false;
                boolean hasDpad = false;
                for (Skin.Control control : layout.controls) {
                    RectF b = control.bounds;
                    assertTrue(what + ": control inside the view " + b,
                            b.left >= -1 && b.top >= -1 && b.right <= width + 1 && b.bottom <= height + 1);
                    if (portrait && control.visible) {
                        assertFalse(what + ": control over the screen " + b, RectF.intersects(b, new RectF(screen)));
                    }
                    hasMenu |= control.keys == Skin.KEY_MENU;
                    hasDpad |= control.shape == Skin.Control.DPAD;
                }
                assertTrue(what + ": has a menu control", hasMenu);
                if (skin instanceof ThemeSkin) {
                    boolean hasRewind = false;
                    boolean hasFastForward = false;
                    for (Skin.Control control : layout.controls) {
                        hasRewind |= control.keys == Skin.KEY_REWIND;
                        hasFastForward |= control.keys == Skin.KEY_FAST_FORWARD;
                    }
                    assertTrue(what + ": has rewind and fast-forward buttons", hasRewind && hasFastForward);
                }
                assertTrue(what + ": has a d-pad", hasDpad);

                if (skin instanceof ThemeSkin) {
                    List<Skin.Control> visible = new ArrayList<>();
                    for (Skin.Control c : layout.controls) if (c.visible) visible.add(c);
                    for (int i = 0; i < visible.size(); i++) {
                        for (int j = i + 1; j < visible.size(); j++) {
                            assertFalse(what + ": controls overlap", RectF.intersects(visible.get(i).bounds, visible.get(j).bounds));
                        }
                    }
                }

                // Without controls (gamepad in use), the screen grows.
                Skin.Layout full = new Skin.Layout();
                skin.layout(full, width, height, 160, 144, false, false);
                assertTrue(what + ": bigger screen without controls", full.screen.width() >= screen.width());
                assertTrue(what + ": no controls laid out when hidden", full.controls.isEmpty());

                // Super Game Boy border: 256×224.
                Skin.Layout sgb = new Skin.Layout();
                skin.layout(sgb, width, height, 256, 224, true, false);
                assertEquals(what + ": SGB aspect", 256f / 224f, (float) sgb.screen.width() / sgb.screen.height(), 0.02f);

                // Integer scaling gives exact multiples.
                Skin.Layout integer = new Skin.Layout();
                skin.layout(integer, width, height, 160, 144, true, true);
                assertEquals(what + ": integer width", 0, integer.screen.width() % 160);
                assertEquals(what + ": integer height", 0, integer.screen.height() % 144);
            }
        }
    }

    @Test
    public void touchingEachControlPressesItsKeys() throws IOException {
        Application app = RuntimeEnvironment.getApplication();
        for (Skin skin : allSkins()) {
            for (boolean portrait : new boolean[] {true, false}) {
                int width = portrait ? PHONE_SHORT : PHONE_LONG;
                int height = portrait ? PHONE_LONG : PHONE_SHORT;
                String what = skin.id() + (portrait ? " portrait" : " landscape");
                RecordingListener listener = new RecordingListener();
                SkinView view = new SkinView(app, skin, listener);
                view.setHaptics(false);
                view.layout(0, 0, width, height);
                assertNotNull(what + ": screen rect reported", listener.screen);

                Skin.Layout layout = new Skin.Layout();
                skin.layout(layout, width, height, 160, 144, true, false);
                for (Skin.Control control : layout.controls) {
                    float x = control.bounds.centerX();
                    float y = control.bounds.centerY();
                    if (control.shape == Skin.Control.DPAD) {
                        float r = control.bounds.width() / 2;
                        checkTouch(view, listener, x, y - r * 0.7f, Emulator.KEY_UP, what + " up");
                        checkTouch(view, listener, x + r * 0.7f, y, Emulator.KEY_RIGHT, what + " right");
                        checkTouch(view, listener, x + r * 0.5f, y + r * 0.5f,
                                Emulator.KEY_RIGHT | Emulator.KEY_DOWN, what + " down-right");
                    } else if (control.keys == Skin.KEY_MENU) {
                        touch(view, MotionEvent.ACTION_DOWN, x, y);
                        touch(view, MotionEvent.ACTION_UP, x, y);
                        assertEquals(what + ": menu tap", 1, listener.menuPresses);
                        listener.menuPresses = 0;
                    } else if (control.keys == Skin.KEY_FAST_FORWARD) {
                        touch(view, MotionEvent.ACTION_DOWN, x, y);
                        assertTrue(what + ": fast-forward held", listener.fastForward);
                        touch(view, MotionEvent.ACTION_UP, x, y);
                        assertFalse(what + ": fast-forward released", listener.fastForward);
                    } else if (control.keys == Skin.KEY_REWIND) {
                        touch(view, MotionEvent.ACTION_DOWN, x, y);
                        assertTrue(what + ": rewind held", listener.rewind);
                        assertEquals(what + ": rewind presses no game keys", 0, listener.keys);
                        touch(view, MotionEvent.ACTION_UP, x, y);
                        assertFalse(what + ": rewind released", listener.rewind);
                    } else {
                        checkTouch(view, listener, x, y, control.keys, what + " " + Integer.toHexString(control.keys));
                    }
                }
                // Touching nothing presses nothing.
                checkTouch(view, listener, 2, 2, 0, what + " corner");
            }
        }
    }

    @Test
    public void slidingBetweenButtonsAndMultiTouch() {
        Application app = RuntimeEnvironment.getApplication();
        Skin skin = ThemeSkin.find("classic");
        RecordingListener listener = new RecordingListener();
        SkinView view = new SkinView(app, skin, listener);
        view.setHaptics(false);
        view.layout(0, 0, PHONE_SHORT, PHONE_LONG);
        Skin.Layout layout = new Skin.Layout();
        skin.layout(layout, PHONE_SHORT, PHONE_LONG, 160, 144, true, false);
        RectF a = find(layout, Emulator.KEY_A).bounds;
        RectF b = find(layout, Emulator.KEY_B).bounds;
        RectF dpad = find(layout, Emulator.KEY_UP | Emulator.KEY_DOWN | Emulator.KEY_LEFT | Emulator.KEY_RIGHT).bounds;

        long now = SystemClock.uptimeMillis();
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, b.centerX(), b.centerY(), 0));
        assertEquals(Emulator.KEY_B, listener.keys);
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE,
                (a.centerX() + b.centerX()) / 2, (a.centerY() + b.centerY()) / 2, 0));
        assertEquals("between A and B presses both", Emulator.KEY_A | Emulator.KEY_B, listener.keys);
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, a.centerX(), a.centerY(), 0));
        assertEquals(Emulator.KEY_A, listener.keys);

        // Second finger on the d-pad while A is held.
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties(), new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[1].id = 1;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords(), new MotionEvent.PointerCoords()};
        coords[0].x = a.centerX();
        coords[0].y = a.centerY();
        coords[1].x = dpad.centerX() - dpad.width() * 0.35f;
        coords[1].y = dpad.centerY();
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, props, coords, 0, 0, 1, 1, 0, 0, 0, 0));
        assertEquals(Emulator.KEY_A | Emulator.KEY_LEFT, listener.keys);
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_POINTER_UP, 2, props, coords, 0, 0, 1, 1, 0, 0, 0, 0));
        assertEquals(Emulator.KEY_LEFT, listener.keys);
        MotionEvent.PointerProperties[] second = {props[1]};
        MotionEvent.PointerCoords[] secondCoords = {coords[1]};
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 1, second, secondCoords, 0, 0, 1, 1, 0, 0, 0, 0));
        assertEquals(0, listener.keys);

        // Hidden controls: a touch asks for them back instead of pressing anything.
        view.setControlsVisible(false);
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, a.centerX(), a.centerY(), 0));
        assertEquals(0, listener.keys);
        assertTrue(listener.showControlsRequested);
    }

    @Test
    public void rendersPreviews() throws IOException {
        File out = new File(System.getProperty("skinPreviewDir", "build/skin-previews"));
        out.mkdirs();
        for (Skin skin : allSkins()) {
            for (boolean portrait : new boolean[] {true, false}) {
                int width = portrait ? PHONE_SHORT : PHONE_LONG;
                int height = portrait ? PHONE_LONG : PHONE_SHORT;
                Skin.Layout layout = new Skin.Layout();
                skin.layout(layout, width, height, 160, 144, true, false);
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                canvas.save();
                canvas.clipOutRect(layout.screen);
                skin.drawBackground(canvas, layout);
                canvas.restore();
                drawTestScreen(canvas, layout.screen);
                // Show a few keys pressed so the pressed look is visible too.
                int pressed = Emulator.KEY_A | Emulator.KEY_RIGHT | Skin.KEY_REWIND;
                Skin.Motion motion = new Skin.Motion();
                motion.snap(layout, pressed);
                skin.drawControls(canvas, layout, pressed, motion);
                String name = skin.id().replace(':', '-') + (portrait ? "-portrait" : "-landscape") + ".png";
                try (OutputStream stream = new FileOutputStream(new File(out, name))) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                }
                // The skin must not paint over the game screen.
                Rect s = layout.screen;
                assertEquals(skin.id() + ": screen untouched", 0xFF0F380F, bitmap.getPixel(s.left + 2, s.top + 2));
            }
        }
    }

    @Test
    public void bundledSkinsAreListed() {
        List<String> ids = new ArrayList<>();
        for (SkinLibrary.Entry entry : new SkinLibrary(RuntimeEnvironment.getApplication()).list()) ids.add(entry.id);
        String[] bundled = {"midnight", "arcade", "woodgrain", "space", "camo", "candy", "carbon"};
        for (String name : bundled) {
            assertTrue(ids + " has " + name, ids.contains("bundled:" + name));
        }
        assertEquals("every theme is listed", ThemeSkin.ALL.length + bundled.length, ids.size());
    }

    @Test
    public void pressesBounceAndTheDpadTilts() {
        Application app = RuntimeEnvironment.getApplication();
        Skin skin = ThemeSkin.find("classic");
        RecordingListener listener = new RecordingListener();
        SkinView view = new SkinView(app, skin, listener);
        view.setHaptics(false);
        view.layout(0, 0, PHONE_SHORT, PHONE_LONG);
        Skin.Layout layout = new Skin.Layout();
        skin.layout(layout, PHONE_SHORT, PHONE_LONG, 160, 144, true, false);
        int aIndex = layout.controls.indexOf(find(layout, Emulator.KEY_A));
        RectF a = layout.controls.get(aIndex).bounds;
        RectF dpad = find(layout, Emulator.KEY_UP | Emulator.KEY_DOWN | Emulator.KEY_LEFT | Emulator.KEY_RIGHT).bounds;
        Skin.Motion motion = view.getMotion();

        touch(view, MotionEvent.ACTION_DOWN, a.centerX(), a.centerY());
        assertEquals("the key goes down at once, whatever the animation", Emulator.KEY_A, listener.keys);
        assertEquals(0, motion.press(aIndex), 0);
        float peak = 0;
        for (int i = 0; i < 30; i++) {
            view.advanceAnimations(1 / 120f);
            peak = Math.max(peak, motion.press(aIndex));
        }
        assertTrue("a press is quick: " + motion.press(aIndex), motion.press(aIndex) > 0.95f);
        assertTrue("and overshoots a little: " + peak, peak > 1.01f && peak < 1.3f);
        while (view.advanceAnimations(1 / 60f)) { /* settle */ }
        assertEquals(1, motion.press(aIndex), 0);

        touch(view, MotionEvent.ACTION_UP, a.centerX(), a.centerY());
        float lowest = 1;
        int frames = 0;
        while (view.advanceAnimations(1 / 60f)) {
            lowest = Math.min(lowest, motion.press(aIndex));
            assertTrue("settles within a second", ++frames < 60);
        }
        assertTrue("a release springs back past rest: " + lowest, lowest < -0.05f && lowest > -0.6f);
        assertEquals(0, motion.press(aIndex), 0);

        // The d-pad rocks towards the held direction, diagonals included.
        touch(view, MotionEvent.ACTION_DOWN, dpad.right - dpad.width() * 0.1f, dpad.top + dpad.height() * 0.1f);
        assertEquals(Emulator.KEY_RIGHT | Emulator.KEY_UP, listener.keys);
        while (view.advanceAnimations(1 / 60f)) { /* settle */ }
        assertEquals(1, motion.tiltX, 0);
        assertEquals(-1, motion.tiltY, 0);
        touch(view, MotionEvent.ACTION_UP, dpad.centerX(), dpad.centerY());

        // With animations off, everything jumps straight to where it rests.
        view.setAnimations(false);
        touch(view, MotionEvent.ACTION_DOWN, a.centerX(), a.centerY());
        assertEquals(1, motion.press(aIndex), 0);
        assertEquals(0, motion.tiltX, 0);
        assertFalse(view.advanceAnimations(1 / 60f));
        touch(view, MotionEvent.ACTION_UP, a.centerX(), a.centerY());
        assertEquals(0, motion.press(aIndex), 0);
    }

    /** Frames of a press and release, side by side, for looking at the animation in the previews. */
    @Test
    public void rendersAnimationStrips() throws IOException {
        File out = new File(System.getProperty("skinPreviewDir", "build/skin-previews"));
        out.mkdirs();
        Application app = RuntimeEnvironment.getApplication();
        for (String id : new String[] {"classic", "sgb", "neon", "oled", "glass", "minimal"}) {
            Skin skin = ThemeSkin.find(id);
            SkinView view = new SkinView(app, skin, new RecordingListener());
            view.setHaptics(false);
            view.layout(0, 0, PHONE_SHORT, PHONE_LONG);
            Skin.Layout layout = new Skin.Layout();
            skin.layout(layout, PHONE_SHORT, PHONE_LONG, 160, 144, true, false);
            RectF a = find(layout, Emulator.KEY_A).bounds;
            RectF dpad = find(layout, Emulator.KEY_UP | Emulator.KEY_DOWN | Emulator.KEY_LEFT | Emulator.KEY_RIGHT).bounds;
            // The part of the view with the d-pad and buttons.
            Rect crop = new Rect(0, (int) (dpad.top - dpad.height() * 0.2f), PHONE_SHORT, (int) (dpad.bottom + dpad.height() * 0.2f));
            int frames = 10;
            Bitmap strip = Bitmap.createBitmap(crop.width(), crop.height() * frames, Bitmap.Config.ARGB_8888);
            Canvas stripCanvas = new Canvas(strip);
            Bitmap frame = Bitmap.createBitmap(PHONE_SHORT, PHONE_LONG, Bitmap.Config.ARGB_8888);
            long now = SystemClock.uptimeMillis();
            MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties(), new MotionEvent.PointerProperties()};
            props[0].id = 0;
            props[1].id = 1;
            MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords(), new MotionEvent.PointerCoords()};
            coords[0].x = a.centerX();
            coords[0].y = a.centerY();
            coords[1].x = dpad.centerX() + dpad.width() * 0.4f;
            coords[1].y = dpad.centerY();
            for (int i = 0; i < frames; i++) {
                if (i == 0) {
                    view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1, new MotionEvent.PointerProperties[] {props[0]},
                            new MotionEvent.PointerCoords[] {coords[0]}, 0, 0, 1, 1, 0, 0, 0, 0));
                    view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                            2, props, coords, 0, 0, 1, 1, 0, 0, 0, 0));
                } else if (i == 5) {
                    view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, a.centerX(), a.centerY(), 0));
                }
                view.advanceAnimations(i == 0 ? 0.03f : 0.035f);
                frame.eraseColor(0);
                Canvas canvas = new Canvas(frame);
                skin.drawBackground(canvas, layout);
                skin.drawControls(canvas, layout, i < 5 ? Emulator.KEY_A | Emulator.KEY_RIGHT : 0, view.getMotion());
                stripCanvas.drawBitmap(frame, crop, new Rect(0, i * crop.height(), crop.width(), (i + 1) * crop.height()), null);
            }
            try (OutputStream stream = new FileOutputStream(new File(out, "animation-" + id + ".png"))) {
                strip.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
        }
    }

    @Test
    public void invalidSkinsAreRejectedWithAReason() throws IOException {
        assertRejected("{}", "portrait");
        assertRejected("{\"portrait\": {\"image\": \"missing.png\", \"screen\": [0,0,100,90], \"controls\": {\"menu\": [0,0,10,10]}}}",
                "missing.png");
        assertRejected("{\"portrait\": {\"image\": \"../outside.png\", \"screen\": [0,0,100,90], \"controls\": {\"menu\": [0,0,10,10]}}}",
                "isn't in the skin");
        assertRejected(exampleWith("\"menu\"", "\"menuu\""), "unknown control");
        assertRejected(exampleWith("\"screen\": [\n      90,", "\"screen\": [\n      5000,"), "outside the skin");
        assertRejected("not json", "invalid");
    }

    @Test
    public void importsZippedSkinsAndRefusesUnsafeZips() throws IOException {
        Application app = RuntimeEnvironment.getApplication();
        SkinLibrary library = new SkinLibrary(app);

        // Wrapped in a folder, as zips made by file managers often are.
        File zip = temp.newFile("example.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            for (File file : exampleSkinDir().listFiles()) {
                out.putNextEntry(new ZipEntry("Midnight/" + file.getName()));
                Files.copy(file.toPath(), out);
            }
        }
        SkinLibrary.Entry entry = library.importSkin(app, Uri.fromFile(zip));
        assertEquals("Midnight (example)", entry.name);
        boolean listed = false;
        for (SkinLibrary.Entry e : library.list()) listed |= e.id.equals(entry.id);
        assertTrue(listed);
        library.setActive(entry.id);
        assertTrue(library.loadActive() instanceof ImageSkin);
        library.delete(entry);
        assertTrue("deleting the active skin falls back to a theme", library.loadActive() instanceof ThemeSkin);

        File evil = temp.newFile("evil.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(evil))) {
            out.putNextEntry(new ZipEntry("../../escaped.txt"));
            out.write("x".getBytes(StandardCharsets.UTF_8));
        }
        try {
            library.importSkin(app, Uri.fromFile(evil));
            fail("zip slip accepted");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("unsafe path"));
        }
        assertFalse(new File(app.getFilesDir(), "escaped.txt").exists());
    }

    // ---- Helpers ----

    private String exampleWith(String from, String to) throws IOException {
        String json = new String(Files.readAllBytes(new File(exampleSkinDir(), "skin.json").toPath()), StandardCharsets.UTF_8);
        assertTrue("example skin.json contains " + from, json.contains(from));
        return json.replace(from, to);
    }

    private void assertRejected(String json, String expected) throws IOException {
        File dir = temp.newFolder();
        for (File file : exampleSkinDir().listFiles()) {
            if (file.getName().endsWith(".png")) Files.copy(file.toPath(), new File(dir, file.getName()).toPath());
        }
        Files.write(new File(dir, "skin.json").toPath(), json.getBytes(StandardCharsets.UTF_8));
        try {
            ImageSkin.load(dir);
            fail("accepted: " + json);
        } catch (IOException e) {
            assertTrue("\"" + e.getMessage() + "\" should mention " + expected, e.getMessage().contains(expected));
        }
    }

    private static Skin.Control find(Skin.Layout layout, int keys) {
        for (Skin.Control c : layout.controls) if (c.keys == keys) return c;
        throw new AssertionError("no control for " + keys);
    }

    private static void checkTouch(SkinView view, RecordingListener listener, float x, float y, int expected, String what) {
        touch(view, MotionEvent.ACTION_DOWN, x, y);
        assertEquals(what, expected, listener.keys);
        touch(view, MotionEvent.ACTION_UP, x, y);
        assertEquals(what + " released", 0, listener.keys);
    }

    private static void touch(SkinView view, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        view.onTouchEvent(event);
        event.recycle();
    }

    /** A stand-in for the game: greenish stripes, so previews show where the screen goes. */
    private static void drawTestScreen(Canvas canvas, Rect screen) {
        Paint paint = new Paint();
        paint.setColor(0xFF0F380F);
        canvas.drawRect(screen, paint);
        paint.setColor(0xFF8BAC0F);
        float stripe = screen.height() / 18f;
        for (int i = 1; i < 18; i += 2) {
            canvas.drawRect(screen.left + screen.width() * 0.1f, screen.top + i * stripe,
                    screen.right - screen.width() * 0.1f, screen.top + (i + 0.5f) * stripe, paint);
        }
    }

    private static final class RecordingListener implements SkinView.Listener {
        int keys;
        boolean fastForward;
        boolean rewind;
        int menuPresses;
        boolean showControlsRequested;
        Rect screen;

        @Override
        public void onTouchKeysChanged(int mask) {
            keys = mask;
        }

        @Override
        public void onFastForwardTouched(boolean held) {
            fastForward = held;
        }

        @Override
        public void onRewindTouched(boolean held) {
            rewind = held;
        }

        @Override
        public void onMenuPressed() {
            menuPresses++;
        }

        @Override
        public void onShowControlsRequested() {
            showControlsRequested = true;
        }

        @Override
        public void onScreenRectChanged(Rect rect) {
            screen = rect;
        }
    }
}
