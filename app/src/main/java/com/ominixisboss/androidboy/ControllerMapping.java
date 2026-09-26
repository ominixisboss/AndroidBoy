package com.ominixisboss.androidboy;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Which controller and keyboard buttons do what. Every action starts with sensible buttons
 * (positional on a gamepad: the right face button is A, the bottom one B, as on a Game Boy);
 * the player can give any action a different button.
 */
final class ControllerMapping {
    enum Action {
        UP("Up", Emulator.KEY_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W),
        DOWN("Down", Emulator.KEY_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S),
        LEFT("Left", Emulator.KEY_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A),
        RIGHT("Right", Emulator.KEY_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D),
        A("A", Emulator.KEY_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_L),
        B("B", Emulator.KEY_B, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_K),
        START("Start", Emulator.KEY_START, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER),
        SELECT("Select", Emulator.KEY_SELECT, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SHIFT_RIGHT,
                KeyEvent.KEYCODE_DEL),
        // Game Boy Advance games only; in Game Boy games these buttons do what they're set to below.
        L("L (Game Boy Advance)", Emulator.KEY_L, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_Q),
        R("R (Game Boy Advance)", Emulator.KEY_R, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_E),
        TURBO_A("Turbo A", Emulator.KEY_A, KeyEvent.KEYCODE_BUTTON_Y),
        TURBO_B("Turbo B", Emulator.KEY_B, KeyEvent.KEYCODE_BUTTON_X),
        FAST_FORWARD("Fast-forward (hold)", 0, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_SPACE),
        REWIND("Rewind (hold)", 0, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_R),
        MENU("Menu", 0, KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_ESCAPE);

        final String label;
        /** The Game Boy key it presses, if any. */
        final int gameKey;
        final int[] defaults;

        Action(String label, int gameKey, int... defaults) {
            this.label = label;
            this.gameKey = gameKey;
            this.defaults = defaults;
        }

        boolean isTurbo() {
            return this == TURBO_A || this == TURBO_B;
        }
    }

    private final SharedPreferences prefs;

    ControllerMapping(Context context) {
        prefs = context.getSharedPreferences("controller", Context.MODE_PRIVATE);
    }

    /** The buttons for an action. */
    int[] keys(Action action) {
        String value = prefs.getString(action.name(), null);
        if (value == null) return action.defaults;
        if (value.isEmpty()) return new int[0];
        String[] parts = value.split(",");
        int[] keys = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) keys[i] = Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return action.defaults;
        }
        return keys;
    }

    /** Every button's action for a Game Boy game, for looking up key presses quickly. */
    android.util.SparseArray<Action> table() {
        return table(false);
    }

    /**
     * Every button's action, for looking up key presses quickly. In a Game Boy Advance game
     * ({@code gba}) L and R come first, so L1 and R1 are the shoulder buttons; in a Game Boy
     * game they're left out, and those buttons rewind and fast-forward.
     */
    android.util.SparseArray<Action> table(boolean gba) {
        android.util.SparseArray<Action> table = new android.util.SparseArray<>();
        if (gba) {
            for (Action action : new Action[] {Action.L, Action.R}) {
                for (int key : keys(action)) table.put(key, action);
            }
        }
        for (Action action : Action.values()) {
            if (action == Action.L || action == Action.R) continue;
            for (int key : keys(action)) {
                if (table.get(key) == null) table.put(key, action);
            }
        }
        return table;
    }

    /** The action a button does, or null. */
    Action actionFor(int keyCode) {
        for (Action action : Action.values()) {
            for (int key : keys(action)) {
                if (key == keyCode) return action;
            }
        }
        return null;
    }

    /** Makes {@code keyCode} do {@code action} (only), taking it off whatever it did before. */
    void assign(Action action, int keyCode) {
        SharedPreferences.Editor editor = prefs.edit();
        for (Action other : Action.values()) {
            if (other == action) continue;
            int[] keys = keys(other);
            if (contains(keys, keyCode)) editor.putString(other.name(), join(without(keys, keyCode)));
        }
        editor.putString(action.name(), String.valueOf(keyCode));
        editor.apply();
    }

    void clear(Action action) {
        prefs.edit().putString(action.name(), "").apply();
    }

    void reset() {
        prefs.edit().clear().apply();
    }

    /** "D-pad up, W", for showing what an action is on. */
    String describe(Action action) {
        int[] keys = keys(action);
        if (keys.length == 0) return "Not set";
        List<String> names = new ArrayList<>();
        for (int key : keys) names.add(keyName(key));
        return String.join(", ", names);
    }

    /** Names for gamepad buttons, which are what's usually remapped. */
    private static final android.util.SparseArray<String> NAMES = new android.util.SparseArray<>();

    static {
        NAMES.put(KeyEvent.KEYCODE_BUTTON_A, "Button A");
        NAMES.put(KeyEvent.KEYCODE_BUTTON_B, "Button B");
        NAMES.put(KeyEvent.KEYCODE_BUTTON_X, "Button X");
        NAMES.put(KeyEvent.KEYCODE_BUTTON_Y, "Button Y");
        NAMES.put(KeyEvent.KEYCODE_BUTTON_L1, "L1");
        NAMES.put(KeyEvent.KEYCODE_BUTTON_R1, "R1");
        NAMES.put(KeyEvent.KEYCODE_BUTTON_L2, "L2");
        NAMES.put(KeyEvent.KEYCODE_BUTTON_R2, "R2");
        NAMES.put(KeyEvent.KEYCODE_BUTTON_THUMBL, "Left stick press");
        NAMES.put(KeyEvent.KEYCODE_BUTTON_THUMBR, "Right stick press");
        NAMES.put(KeyEvent.KEYCODE_BUTTON_START, "Start");
        NAMES.put(KeyEvent.KEYCODE_BUTTON_SELECT, "Select");
        NAMES.put(KeyEvent.KEYCODE_BUTTON_MODE, "Home/Mode");
        NAMES.put(KeyEvent.KEYCODE_DPAD_UP, "D-pad up");
        NAMES.put(KeyEvent.KEYCODE_DPAD_DOWN, "D-pad down");
        NAMES.put(KeyEvent.KEYCODE_DPAD_LEFT, "D-pad left");
        NAMES.put(KeyEvent.KEYCODE_DPAD_RIGHT, "D-pad right");
        NAMES.put(KeyEvent.KEYCODE_ENTER, "Enter");
        NAMES.put(KeyEvent.KEYCODE_SPACE, "Space");
        NAMES.put(KeyEvent.KEYCODE_ESCAPE, "Esc");
        NAMES.put(KeyEvent.KEYCODE_DEL, "Backspace");
        NAMES.put(KeyEvent.KEYCODE_SHIFT_RIGHT, "Right Shift");
        NAMES.put(KeyEvent.KEYCODE_MENU, "Menu");
    }

    /** A button's name: "Button A", "D-pad up", "W". */
    static String keyName(int keyCode) {
        String known = NAMES.get(keyCode);
        if (known != null) return known;
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return String.valueOf((char) ('A' + keyCode - KeyEvent.KEYCODE_A));
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return String.valueOf((char) ('0' + keyCode - KeyEvent.KEYCODE_0));
        }
        String name = KeyEvent.keyCodeToString(keyCode);
        if (!name.startsWith("KEYCODE_")) return "Button " + name; // Unnamed: its number.
        name = name.substring(8).replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static boolean contains(int[] keys, int key) {
        for (int k : keys) {
            if (k == key) return true;
        }
        return false;
    }

    private static int[] without(int[] keys, int key) {
        return Arrays.stream(keys).filter(k -> k != key).toArray();
    }

    private static String join(int[] keys) {
        StringBuilder text = new StringBuilder();
        for (int key : keys) {
            if (text.length() > 0) text.append(',');
            text.append(key);
        }
        return text.toString();
    }
}
