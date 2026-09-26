package com.ominixisboss.androidboy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A game's cheats: switch them on and off, type in codes, and find more in the libretro cheat
 * database. Changes are saved as they're made, and {@code onChanged} applies them.
 */
final class CheatDialogs {
    private static final String TAG = "AndroidBoy";
    private static final int SEARCH_RESULTS = 60;

    private CheatDialogs() {}

    /**
     * Shows the cheat list for a game. {@code romName} seeds the online search, {@code isColor}
     * ranks Game Boy Color results first, {@code gba} is for a Game Boy Advance game (its own
     * codes and cheat files); {@code hardcore} explains that cheats are off.
     */
    static void show(Activity activity, File file, String romName, boolean isColor, boolean gba, boolean hardcore,
                     Runnable onChanged, Runnable onDismiss) {
        List<Cheat> cheats = Cheat.load(file);
        LinearLayout content = column(activity);
        if (hardcore) {
            content.addView(text(activity, "Cheats are off in RetroAchievements hardcore mode. "
                    + "You can still set them up; they apply when hardcore mode is off.", 13));
        }
        TextView empty = text(activity, gba
                ? "No cheats yet. Find some online, or add a GameShark, Action Replay or CodeBreaker code."
                : "No cheats yet. Find some online, or add a GameShark or Game Genie code.", 14);
        content.addView(empty);
        ListView list = new ListView(activity);
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Runnable save = () -> {
            try {
                Cheat.save(file, cheats);
            } catch (IOException e) {
                Log.e(TAG, "Could not save cheats", e);
                Toast.makeText(activity, "Could not save the cheats", Toast.LENGTH_LONG).show();
            }
            onChanged.run();
        };
        ArrayAdapter<Cheat> adapter = new ArrayAdapter<Cheat>(activity, 0, cheats) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return cheatRow(activity, getItem(position), convertView);
            }
        };
        Runnable refresh = () -> {
            adapter.notifyDataSetChanged();
            empty.setVisibility(cheats.isEmpty() ? View.VISIBLE : View.GONE);
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Cheat cheat = cheats.get(position);
            if (!cheat.isValid()) {
                Toast.makeText(activity, "Long-press to fix this code first", Toast.LENGTH_SHORT).show();
                return;
            }
            cheat.enabled = !cheat.enabled;
            save.run();
            refresh.run();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            Cheat cheat = cheats.get(position);
            new AlertDialog.Builder(activity)
                    .setTitle(cheat.description)
                    .setItems(new String[] {"Edit", "Delete"}, (d, which) -> {
                        if (which == 0) {
                            showEditor(activity, cheat, gba, edited -> {
                                save.run();
                                refresh.run();
                            });
                        } else {
                            cheats.remove(position);
                            save.run();
                            refresh.run();
                        }
                    })
                    .show();
            return true;
        });
        refresh.run();

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Cheats")
                .setView(content)
                .setPositiveButton("Done", null)
                .setNeutralButton("Add code", null)
                .setNegativeButton("Find online", null)
                .create();
        dialog.setOnDismissListener(d -> onDismiss.run());
        dialog.show();
        // These open another dialog over this one rather than closing it.
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v ->
                showEditor(activity, null, gba, added -> {
                    cheats.add(added);
                    save.run();
                    refresh.run();
                }));
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v ->
                showSearch(activity, romName, isColor, gba, found -> {
                    int added = Cheat.merge(cheats, found);
                    Toast.makeText(activity, added == 1 ? "Added 1 cheat" : "Added " + added + " cheats",
                            Toast.LENGTH_SHORT).show();
                    if (added > 0) {
                        save.run();
                        refresh.run();
                    }
                }));
    }

    /** Checkbox with the description, and the code underneath. */
    private static View cheatRow(Activity activity, Cheat cheat, View convertView) {
        LinearLayout row;
        CheckBox box;
        TextView code;
        if (convertView instanceof LinearLayout) {
            row = (LinearLayout) convertView;
            box = (CheckBox) row.getChildAt(0);
            code = (TextView) row.getChildAt(1);
        } else {
            row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(activity, 4), 0, dp(activity, 6));
            box = new CheckBox(activity);
            // The list handles taps (toggle) and long-presses (edit), so the box only shows the state.
            box.setClickable(false);
            box.setFocusable(false);
            code = text(activity, "", 12);
            code.setTypeface(Typeface.MONOSPACE);
            code.setPadding(dp(activity, 32), 0, 0, 0);
            row.addView(box);
            row.addView(code);
        }
        boolean valid = cheat.isValid();
        box.setText(cheat.description);
        box.setChecked(valid && cheat.enabled);
        box.setEnabled(valid);
        code.setText(valid ? cheat.code : cheat.code + "\nCan't use this code: it has ?s to fill in, or isn't a code this game's system understands. "
                + "Long-press to edit.");
        return row;
    }

    interface CheatCallback {
        void onCheat(Cheat cheat);
    }

    /** Adds a cheat ({@code existing} null) or edits one. */
    private static void showEditor(Activity activity, Cheat existing, boolean gba, CheatCallback callback) {
        LinearLayout form = column(activity);
        EditText description = new EditText(activity);
        description.setHint("Description (optional)");
        description.setSingleLine(true);
        EditText code = new EditText(activity);
        code.setHint("Code, e.g. 01FF16D0 or 00A-17B-C49");
        code.setSingleLine(true);
        code.setTypeface(Typeface.MONOSPACE);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (existing != null) {
            description.setText(existing.description);
            code.setText(existing.code);
        }
        TextView hint = text(activity, gba
                ? "GameShark and Action Replay codes have 8 + 8 digits, CodeBreaker codes 8 + 4. "
                        + "Join several codes with +."
                : "GameShark codes have 8 digits, Game Genie codes 6 or 9. Join several codes with +.", 13);
        form.addView(description);
        form.addView(code);
        form.addView(hint);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(existing == null ? "Add cheat" : "Edit cheat")
                .setView(form)
                .setPositiveButton(existing == null ? "Add" : "Save", null) // Set below, to keep it open on a bad code.
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String codeText = code.getText().toString().trim();
            Cheat cheat = new Cheat("", codeText, true);
            if (!cheat.isValid()) {
                hint.setText(gba ? "That isn't a GameShark, Action Replay or CodeBreaker code."
                        : "That isn't a GameShark (8 digits) or Game Genie (6 or 9 digits) code.");
                return;
            }
            String name = description.getText().toString().trim();
            cheat.description = name.isEmpty() ? String.join(" + ", cheat.codes()) : name;
            if (existing != null) {
                existing.description = cheat.description;
                existing.code = codeText;
                existing.enabled = true;
                cheat = existing;
            }
            dialog.dismiss();
            callback.onCheat(cheat);
        });
    }

    interface FoundCallback {
        void onFound(List<Cheat> cheats);
    }

    /** Searches the cheat database, then lets the player pick cheats from one game. */
    private static void showSearch(Activity activity, String romName, boolean isColor, boolean gba,
                                   FoundCallback callback) {
        LinearLayout content = column(activity);
        EditText query = new EditText(activity);
        query.setHint("Game name");
        query.setSingleLine(true);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setText(CheatDatabase.defaultQuery(romName));
        TextView status = text(activity, "Loading the list of games…", 14);
        ListView results = new ListView(activity);
        TextView credit = text(activity, "Cheats from the libretro database (github.com/libretro/libretro-database)", 11);
        content.addView(query);
        content.addView(status);
        content.addView(results, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(credit);

        List<CheatDatabase.Entry> shown = new ArrayList<>();
        ArrayAdapter<CheatDatabase.Entry> adapter = new ArrayAdapter<CheatDatabase.Entry>(activity, 0, shown) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = convertView instanceof TextView ? (TextView) convertView : text(activity, "", 15);
                view.setPadding(0, dp(activity, 10), 0, dp(activity, 10));
                CheatDatabase.Entry entry = getItem(position);
                view.setText(entry.title() + (entry.isGba() ? "  ·  Game Boy Advance"
                        : entry.isColor() ? "  ·  Game Boy Color" : "  ·  Game Boy"));
                return view;
            }
        };
        results.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Find cheats")
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        List<List<CheatDatabase.Entry>> index = new ArrayList<>(); // Holds the index once loaded.
        Runnable search = () -> {
            if (index.isEmpty()) return;
            shown.clear();
            shown.addAll(CheatDatabase.search(index.get(0), query.getText().toString(), isColor, gba, SEARCH_RESULTS));
            adapter.notifyDataSetChanged();
            status.setText(shown.isEmpty() ? "No games found. Try fewer or shorter words." : "Tap a game to see its cheats.");
        };
        query.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId != EditorInfo.IME_ACTION_SEARCH && !enter) return false;
            search.run();
            return true;
        });
        results.setOnItemClickListener((parent, view, position, id) -> {
            CheatDatabase.Entry entry = shown.get(position);
            status.setText("Downloading " + entry.title() + "…");
            background(activity, () -> CheatDatabase.download(entry), (found, error) -> {
                if (!dialog.isShowing()) return;
                if (error != null) {
                    status.setText("Couldn't download the cheats: " + error);
                    return;
                }
                status.setText("Tap a game to see its cheats.");
                showPicker(activity, entry, found, picked -> {
                    dialog.dismiss();
                    callback.onFound(picked);
                });
            });
        });
        dialog.show();

        File cache = new File(activity.getCacheDir(), "cheat-index.txt");
        background(activity, () -> CheatDatabase.index(cache), (loaded, error) -> {
            if (!dialog.isShowing()) return;
            if (error != null) {
                status.setText("Couldn't load the list of games: " + error);
                return;
            }
            index.add(loaded);
            search.run();
        });
    }

    /** One game's cheats from the database, to choose from. */
    private static void showPicker(Activity activity, CheatDatabase.Entry entry, List<Cheat> found, FoundCallback callback) {
        if (found.isEmpty()) {
            Toast.makeText(activity, "That file has no cheats in it", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[found.size()];
        for (int i = 0; i < names.length; i++) {
            Cheat cheat = found.get(i);
            names[i] = cheat.description + (cheat.isValid() ? "" : " (needs editing)");
        }
        boolean[] checked = new boolean[found.size()];
        new AlertDialog.Builder(activity)
                .setTitle(entry.title())
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Add and turn on", (d, which) -> {
                    List<Cheat> picked = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (!checked[i]) continue;
                        Cheat cheat = found.get(i);
                        cheat.enabled = cheat.isValid();
                        picked.add(cheat);
                    }
                    callback.onFound(picked);
                })
                .setNeutralButton("Add all, off", (d, which) -> callback.onFound(found))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    interface Work<T> {
        T run() throws IOException;
    }

    interface Result<T> {
        /** Main thread; {@code error} is null on success. */
        void onResult(T value, String error);
    }

    private static <T> void background(Activity activity, Work<T> work, Result<T> result) {
        new Thread(() -> {
            T value = null;
            String error = null;
            try {
                value = work.run();
            } catch (IOException e) {
                error = e.getMessage() != null ? e.getMessage() : "no connection";
            }
            T finalValue = value;
            String finalError = error;
            activity.runOnUiThread(() -> {
                if (!activity.isDestroyed()) result.onResult(finalValue, finalError);
            });
        }, "Cheats").start();
    }

    private static LinearLayout column(Activity activity) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 20);
        layout.setPadding(pad, dp(activity, 8), pad, 0);
        return layout;
    }

    private static TextView text(Activity activity, String value, int sp) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setPadding(0, dp(activity, 4), 0, dp(activity, 4));
        return view;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
