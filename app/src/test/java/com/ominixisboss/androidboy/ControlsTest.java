package com.ominixisboss.androidboy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.RectF;
import android.view.KeyEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/** Controller button mapping, and moving and resizing on-screen controls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ControlsTest {
    @Test
    public void defaultsAndRemapping() {
        ControllerMapping mapping = new ControllerMapping(RuntimeEnvironment.getApplication());
        mapping.reset();
        assertEquals(ControllerMapping.Action.A, mapping.actionFor(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(ControllerMapping.Action.TURBO_A, mapping.actionFor(KeyEvent.KEYCODE_BUTTON_Y));
        assertEquals(ControllerMapping.Action.MENU, mapping.table().get(KeyEvent.KEYCODE_BUTTON_MODE));

        // Swap A onto the bottom button; it stops being B.
        mapping.assign(ControllerMapping.Action.A, KeyEvent.KEYCODE_BUTTON_A);
        assertEquals(ControllerMapping.Action.A, mapping.actionFor(KeyEvent.KEYCODE_BUTTON_A));
        assertNull(mapping.actionFor(KeyEvent.KEYCODE_BUTTON_B));
        assertArrayEquals(new int[] {KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_K}, mapping.keys(ControllerMapping.Action.B));
        assertEquals("Button A", mapping.describe(ControllerMapping.Action.A));

        mapping.clear(ControllerMapping.Action.MENU);
        assertEquals("Not set", mapping.describe(ControllerMapping.Action.MENU));
        mapping.reset();
        assertEquals(ControllerMapping.Action.B, mapping.actionFor(KeyEvent.KEYCODE_BUTTON_A));
        assertEquals("D-pad up, W", mapping.describe(ControllerMapping.Action.UP));
    }

    @Test
    public void movesAndResizesControls() {
        ControlLayout controls = new ControlLayout(RuntimeEnvironment.getApplication());
        List<Skin.Control> list = new ArrayList<>();
        list.add(new Skin.Control(Emulator.KEY_A, Skin.Control.CIRCLE, new RectF(800, 1500, 900, 1600), true));
        list.add(new Skin.Control(Emulator.KEY_B, Skin.Control.CIRCLE, new RectF(650, 1550, 750, 1650), true));
        list.add(new Skin.Control(Emulator.KEY_A | Emulator.KEY_B, Skin.Control.CIRCLE,
                new RectF(740, 1540, 800, 1600), false));
        List<RectF> base = new ArrayList<>();
        for (Skin.Control control : list) base.add(new RectF(control.bounds));

        ControlLayout.Adjustment moveA = new ControlLayout.Adjustment();
        moveA.dx = -0.1f; // 100 px left on a 1000 px wide view.
        moveA.scale = 2;
        controls.put("theme:test", "portrait", Emulator.KEY_A, moveA);
        controls.apply("theme:test", 1000, 2000, list, base);
        assertEquals(new RectF(650, 1450, 850, 1650), list.get(0).bounds);
        assertEquals(base.get(1), list.get(1).bounds); // B stays.
        // The A+B spot sits between them.
        assertEquals(725, list.get(2).bounds.centerX(), 0.01f);
        assertEquals(1575, list.get(2).bounds.centerY(), 0.01f);

        // Landscape is separate.
        assertTrue(controls.get("theme:test", "landscape", Emulator.KEY_A).isIdentity());
        controls.reset("theme:test", "portrait");
        assertTrue(controls.get("theme:test", "portrait", Emulator.KEY_A).isIdentity());
    }

    @Test
    public void staysOnScreenAndInRange() {
        ControlLayout.Adjustment far = new ControlLayout.Adjustment();
        far.dx = 5;
        far.scale = 1;
        RectF out = new RectF();
        ControlLayout.transform(new RectF(0, 0, 100, 100), far, 1000, 1000, out);
        assertEquals(1000, out.centerX(), 0.01f);
        assertEquals(ControlLayout.MAX_SCALE, ControlLayout.clampScale(9), 0);
        assertEquals(ControlLayout.MIN_SCALE, ControlLayout.clampScale(0.1f), 0);
    }

    @Test
    public void shoulderButtonsOnlyInGbaGames() {
        ControllerMapping mapping = new ControllerMapping(RuntimeEnvironment.getApplication());
        mapping.reset();
        assertEquals(ControllerMapping.Action.REWIND, mapping.table().get(KeyEvent.KEYCODE_BUTTON_L1));
        assertEquals(ControllerMapping.Action.FAST_FORWARD, mapping.table(false).get(KeyEvent.KEYCODE_BUTTON_R1));
        assertEquals(ControllerMapping.Action.L, mapping.table(true).get(KeyEvent.KEYCODE_BUTTON_L1));
        assertEquals(ControllerMapping.Action.R, mapping.table(true).get(KeyEvent.KEYCODE_BUTTON_R1));
        // L2 and R2 still rewind and fast-forward in GBA games.
        assertEquals(ControllerMapping.Action.REWIND, mapping.table(true).get(KeyEvent.KEYCODE_BUTTON_L2));
        assertEquals(Emulator.KEY_L, ControllerMapping.Action.L.gameKey);
        assertEquals(0x3FF, Skin.GAME_KEYS);
    }
}
