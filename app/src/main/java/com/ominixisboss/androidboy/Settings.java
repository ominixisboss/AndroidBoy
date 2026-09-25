package com.ominixisboss.androidboy;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ArrayAdapter;

/** User preferences, stored in SharedPreferences, plus a simple framework-only settings dialog. */
final class Settings {
    /** A multiple-choice preference; the stored value is the index into {@link #labels}. */
    static final class Choice {
        final String key;
        final String title;
        final String[] labels;
        final int[] values;
        final int defaultIndex;

        Choice(String key, String title, String[] labels, int[] values, int defaultIndex) {
            this.key = key;
            this.title = title;
            this.labels = labels;
            this.values = values;
            this.defaultIndex = defaultIndex;
        }
    }

    static final Choice DMG_MODEL = new Choice("dmg_model", "Model for Game Boy games",
            new String[] {"Game Boy (DMG)", "Game Boy Pocket", "Super Game Boy", "Super Game Boy 2",
                    "Game Boy Color", "Game Boy Advance"},
            new int[] {Emulator.MODEL_DMG_B, Emulator.MODEL_MGB, Emulator.MODEL_SGB, Emulator.MODEL_SGB2,
                    Emulator.MODEL_CGB_E, Emulator.MODEL_AGB_A},
            4);

    static final Choice CGB_MODEL = new Choice("cgb_model", "Model for Game Boy Color games",
            new String[] {"Game Boy Color", "Game Boy Advance"},
            new int[] {Emulator.MODEL_CGB_E, Emulator.MODEL_AGB_A},
            0);

    // GB_color_correction_mode_t order
    static final Choice COLOR_CORRECTION = new Choice("color_correction", "Color correction",
            new String[] {"Disabled", "Correct color curves", "Modern – balanced", "Modern – boost contrast",
                    "Reduce contrast", "Harsh reality (low contrast)", "Modern – accurate"},
            new int[] {0, 1, 2, 3, 4, 5, 6},
            2);

    static final Choice DMG_PALETTE = new Choice("dmg_palette", "Monochrome palette",
            new String[] {"Greyscale", "Lime (Game Boy)", "Olive (Game Boy Pocket)", "Teal (Game Boy Light)"},
            new int[] {0, 1, 2, 3},
            0);

    // GB_border_mode_t values: SGB = 0, NEVER = 1, ALWAYS = 2
    static final Choice BORDER = new Choice("border", "Super Game Boy border",
            new String[] {"Never", "Super Game Boy only", "Always"},
            new int[] {1, 0, 2},
            1);

    // GB_highpass_mode_t order
    static final Choice HIGHPASS = new Choice("highpass", "Audio high-pass filter",
            new String[] {"Off (keep DC offset)", "Accurate", "Remove DC offset"},
            new int[] {0, 1, 2},
            1);

    // GB_rumble_mode_t order
    static final Choice RUMBLE = new Choice("rumble", "Rumble",
            new String[] {"Disabled", "Rumble cartridges only", "All games"},
            new int[] {0, 1, 2},
            1);

    static final Choice FAST_FORWARD = new Choice("fast_forward", "Fast-forward speed",
            new String[] {"2×", "3×", "4×", "8×", "Unlimited"},
            new int[] {2, 3, 4, 8, 0},
            2);

    static final Choice CONTROLS = new Choice("controls", "On-screen controls",
            new String[] {"Hide while a gamepad is in use", "Always show", "Never show"},
            new int[] {0, 1, 2},
            0);

    static final Choice SCALING = new Choice("scaling", "Screen scaling",
            new String[] {"Fit to screen", "Integer multiples only"},
            new int[] {0, 1},
            0);

    static final Choice FILTER = new Choice("filter", "Screen filter",
            Filters.LABELS, indices(Filters.LABELS.length), 0);

    // Mixes each frame with the previous one, which some games rely on for flicker transparency.
    static final Choice FRAME_BLENDING = new Choice("frame_blending", "Frame blending",
            new String[] {"Off", "Simple", "Accurate"},
            new int[] {0, 1, 2},
            2);

    static final Choice SOUND = new Choice("sound", "Sound",
            new String[] {"On", "Off"},
            new int[] {1, 0},
            0);

    static final Choice HAPTICS = new Choice("haptics", "Vibrate on button press",
            new String[] {"On", "Off"},
            new int[] {1, 0},
            0);

    static final Choice AUTO_SAVE = new Choice("auto_save", "Resume where you left off",
            new String[] {"On", "Off"},
            new int[] {1, 0},
            0);

    static final Choice[] ALL = {
            DMG_MODEL, CGB_MODEL, COLOR_CORRECTION, DMG_PALETTE, FILTER, FRAME_BLENDING, BORDER, SCALING, CONTROLS,
            SOUND, HIGHPASS, HAPTICS, RUMBLE, FAST_FORWARD, AUTO_SAVE,
    };

    private static int[] indices(int count) {
        int[] values = new int[count];
        for (int i = 0; i < count; i++) values[i] = i;
        return values;
    }

    interface Listener {
        void onSettingChanged(Choice choice);
    }

    private final SharedPreferences prefs;

    Settings(Context context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    int index(Choice choice) {
        int index = prefs.getInt(choice.key, choice.defaultIndex);
        return index >= 0 && index < choice.values.length ? index : choice.defaultIndex;
    }

    int get(Choice choice) {
        return choice.values[index(choice)];
    }

    boolean isOn(Choice choice) {
        return get(choice) != 0;
    }

    void set(Choice choice, int index) {
        prefs.edit().putInt(choice.key, index).apply();
    }

    /** Pushes every emulation-related setting to the core. Call from the emulation thread. */
    void applyToCore() {
        Emulator.nativeSetColorCorrection(get(COLOR_CORRECTION));
        Emulator.nativeSetDmgPalette(get(DMG_PALETTE));
        Emulator.nativeSetBorderMode(get(BORDER));
        Emulator.nativeSetHighpass(get(HIGHPASS));
        Emulator.nativeSetRumbleMode(get(RUMBLE));
    }

    void showDialog(Context context, Listener listener, Runnable onDismiss) {
        String[] rows = new String[ALL.length];
        for (int i = 0; i < ALL.length; i++) {
            rows[i] = ALL[i].title + "\n" + ALL[i].labels[index(ALL[i])];
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, rows);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Settings")
                .setAdapter(adapter, (d, which) -> showChoice(context, ALL[which], listener, onDismiss))
                .setPositiveButton("Done", null)
                .create();
        dialog.setOnDismissListener(d -> {
            // Picking a row dismisses this dialog too; only report when no choice dialog is opening.
            if (!pendingChoice) onDismiss.run();
            pendingChoice = false;
        });
        dialog.show();
    }

    private boolean pendingChoice;

    private void showChoice(Context context, Choice choice, Listener listener, Runnable onDismiss) {
        pendingChoice = true;
        new AlertDialog.Builder(context)
                .setTitle(choice.title)
                .setSingleChoiceItems(choice.labels, index(choice), (d, which) -> {
                    set(choice, which);
                    listener.onSettingChanged(choice);
                    d.dismiss();
                })
                .setOnDismissListener(d -> showDialog(context, listener, onDismiss))
                .show();
    }
}
