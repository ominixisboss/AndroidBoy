package com.ominixisboss.androidboy;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

/** User preferences, stored in SharedPreferences, plus framework-only settings dialogs. */
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

    static final Choice ANIMATIONS = new Choice("animations", "Button animations",
            new String[] {"On", "Off"},
            new int[] {1, 0},
            0);

    // Seconds of gameplay kept for rewinding (GB_set_rewind_length).
    static final Choice REWIND = new Choice("rewind", "Rewind",
            new String[] {"Off", "10 seconds", "30 seconds", "1 minute", "2 minutes", "5 minutes"},
            new int[] {0, 10, 30, 60, 120, 300},
            2);

    static final Choice AUTO_SAVE = new Choice("auto_save", "Resume where you left off",
            new String[] {"On", "Off"},
            new int[] {1, 0},
            0);

    /** A group of related settings, shown as one entry in the settings dialog. */
    static final class Category {
        final String title;
        final String summary;
        final Choice[] choices;

        Category(String title, String summary, Choice... choices) {
            this.title = title;
            this.summary = summary;
            this.choices = choices;
        }
    }

    static final Category[] CATEGORIES = {
            new Category("Display", "Screen filter, colours, scaling, Super Game Boy border",
                    FILTER, FRAME_BLENDING, COLOR_CORRECTION, DMG_PALETTE, SCALING, BORDER),
            new Category("Emulation", "Game Boy models, rewind, fast-forward, resuming",
                    DMG_MODEL, CGB_MODEL, REWIND, FAST_FORWARD, AUTO_SAVE),
            new Category("Controls", "On-screen controls, animations, vibration, rumble",
                    CONTROLS, ANIMATIONS, HAPTICS, RUMBLE),
            new Category("Sound", "Sound on or off, audio filter",
                    SOUND, HIGHPASS),
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
        Emulator.nativeSetRewindLength(get(REWIND));
    }

    /**
     * Shows the settings: a short list of categories, each opening its own few settings. Dialogs
     * stack, so changing a value keeps its list open (and updated) instead of starting over.
     * {@code onDismiss} runs once, when the settings are closed.
     */
    void showDialog(Context context, Listener listener, Runnable onDismiss) {
        TwoLineAdapter adapter = new TwoLineAdapter(context, CATEGORIES.length) {
            @Override
            void bind(int position, TextView title, TextView summary) {
                title.setText(CATEGORIES[position].title);
                summary.setText(CATEGORIES[position].summary);
            }
        };
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Settings")
                .setView(listView(context, adapter, position ->
                        showCategory(context, CATEGORIES[position], listener)))
                .setPositiveButton("Done", null)
                .create();
        dialog.setOnDismissListener(d -> onDismiss.run());
        dialog.show();
    }

    private void showCategory(Context context, Category category, Listener listener) {
        TwoLineAdapter adapter = new TwoLineAdapter(context, category.choices.length) {
            @Override
            void bind(int position, TextView title, TextView value) {
                Choice choice = category.choices[position];
                title.setText(choice.title);
                value.setText(choice.labels[index(choice)]);
            }
        };
        new AlertDialog.Builder(context)
                .setTitle(category.title)
                .setView(listView(context, adapter, position ->
                        showChoice(context, category.choices[position], listener, adapter)))
                .setPositiveButton("Back", null)
                .show();
    }

    private void showChoice(Context context, Choice choice, Listener listener, TwoLineAdapter category) {
        new AlertDialog.Builder(context)
                .setTitle(choice.title)
                .setSingleChoiceItems(choice.labels, index(choice), (d, which) -> {
                    set(choice, which);
                    listener.onSettingChanged(choice);
                    category.notifyDataSetChanged();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private interface OnRowClick {
        void onClick(int position);
    }

    /** A list whose rows don't close its dialog when tapped (unlike AlertDialog.setAdapter). */
    private static ListView listView(Context context, TwoLineAdapter adapter, OnRowClick onClick) {
        ListView list = new ListView(context);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> onClick.onClick(position));
        return list;
    }

    /** Rows with a title and a second line (a summary or the current value). */
    private abstract static class TwoLineAdapter extends ArrayAdapter<Integer> {
        TwoLineAdapter(Context context, int count) {
            super(context, android.R.layout.simple_list_item_2, android.R.id.text1);
            for (int i = 0; i < count; i++) add(i);
        }

        abstract void bind(int position, TextView title, TextView detail);

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            bind(position, view.findViewById(android.R.id.text1), view.findViewById(android.R.id.text2));
            return view;
        }
    }
}
