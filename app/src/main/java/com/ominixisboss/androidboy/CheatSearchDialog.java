package com.ominixisboss.androidboy;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The cheat search screen: start a search, filter by how a number changed, and turn what's left
 * into a cheat. The search itself lives in the game screen, so it carries on between visits.
 */
final class CheatSearchDialog {
    /** Results are listed once there are this few left. */
    static final int SHOWN_RESULTS = 60;

    interface Host {
        /** Runs {@code task} where memory can be read (the emulation thread), then {@code done} on the main thread. */
        void runWithMemory(Runnable task, Runnable done);

        /** A cheat was made from a result. */
        void onCheatMade(Cheat cheat);
    }

    private CheatSearchDialog() {}

    static void show(Activity activity, CheatSearch search, Host host, Runnable onDismiss) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 20);
        content.setPadding(pad, dp(activity, 8), pad, 0);

        TextView status = text(activity, "", 14);
        content.addView(status);
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.VERTICAL);
        content.addView(buttons);
        ListView results = new ListView(activity);
        content.addView(results, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        List<CheatSearch.Result> shown = new ArrayList<>();
        ArrayAdapter<CheatSearch.Result> adapter = new ArrayAdapter<CheatSearch.Result>(activity, 0, shown) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = convertView instanceof TextView ? (TextView) convertView : text(activity, "", 15);
                view.setTypeface(Typeface.MONOSPACE);
                view.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
                CheatSearch.Result result = getItem(position);
                view.setText(String.format(Locale.ROOT, "$%04X   %3d  (0x%02X)", result.address, result.value, result.value));
                return view;
            }
        };
        results.setAdapter(adapter);

        List<Button> filterButtons = new ArrayList<>();
        Runnable update = () -> {
            shown.clear();
            if (!search.isStarted()) {
                status.setText("Find where the game keeps a number, like lives or money. Start a search, play until "
                        + "the number changes, then come back and say how it changed. Repeat until a few are left.");
            } else {
                int count = search.count();
                status.setText(count == 0
                        ? "Nothing left. Start a new search."
                        : count <= SHOWN_RESULTS
                        ? count + (count == 1 ? " address left." : " addresses left.") + " Tap one to make a cheat."
                        : String.format(Locale.getDefault(), "%,d addresses left. Keep playing and filtering.", count));
                if (count <= SHOWN_RESULTS) shown.addAll(search.results(SHOWN_RESULTS));
            }
            adapter.notifyDataSetChanged();
            for (Button button : filterButtons) button.setEnabled(search.isStarted() && search.count() > 0);
        };

        Button start = new Button(activity);
        start.setText("Start a new search");
        start.setOnClickListener(v -> host.runWithMemory(() -> search.start(Emulator::nativeReadMemory), update));
        buttons.addView(start);
        LinearLayout row = null;
        CheatSearch.Filter[] filters = CheatSearch.Filter.values();
        for (int i = 0; i < filters.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                buttons.addView(row);
            }
            CheatSearch.Filter filter = filters[i];
            Button button = new Button(activity);
            button.setText(filter.label);
            button.setAllCaps(false);
            button.setOnClickListener(v -> {
                if (filter.needsValue) {
                    askNumber(activity, filter.label.replace("…", ""), "", value ->
                            host.runWithMemory(() -> search.filter(Emulator::nativeReadMemory, filter, value), update));
                } else {
                    host.runWithMemory(() -> search.filter(Emulator::nativeReadMemory, filter, 0), update);
                }
            });
            filterButtons.add(button);
            row.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        results.setOnItemClickListener((parent, view, position, id) -> {
            CheatSearch.Result result = shown.get(position);
            makeCheat(activity, search, result, host);
        });
        update.run();

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Cheat search")
                .setView(content)
                .setPositiveButton("Back to the game", null)
                .create();
        dialog.setOnDismissListener(d -> onDismiss.run());
        dialog.show();
    }

    private static void makeCheat(Activity activity, CheatSearch search, CheatSearch.Result result, Host host) {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 20);
        form.setPadding(pad, dp(activity, 8), pad, 0);
        form.addView(text(activity, String.format(Locale.ROOT,
                "Keep $%04X at a value (0-255). It's %d now.", result.address, result.value), 14));
        EditText value = numberField(activity, String.valueOf(result.value));
        EditText description = new EditText(activity);
        description.setHint("Description, e.g. Infinite lives");
        description.setSingleLine(true);
        form.addView(value);
        form.addView(description);
        new AlertDialog.Builder(activity)
                .setTitle("Make a cheat")
                .setView(form)
                .setPositiveButton("Add cheat", (d, which) -> {
                    int number = parseByte(value.getText().toString());
                    if (number < 0) {
                        Toast.makeText(activity, "Enter a number from 0 to 255", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String code = search.code(result.address, number);
                    String name = description.getText().toString().trim();
                    host.onCheatMade(new Cheat(name.isEmpty()
                            ? String.format(Locale.ROOT, "$%04X = %d", result.address, number) : name, code, true));
                    Toast.makeText(activity, "Added and turned on: " + code, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    interface NumberCallback {
        void onNumber(int value);
    }

    private static void askNumber(Activity activity, String title, String initial, NumberCallback callback) {
        EditText field = numberField(activity, initial);
        LinearLayout box = new LinearLayout(activity);
        int pad = dp(activity, 20);
        box.setPadding(pad, dp(activity, 8), pad, 0);
        box.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage("A number from 0 to 255. Games often store what's shown on screen, "
                        + "but sometimes one less (lives) or in a different form.")
                .setView(box)
                .setPositiveButton("Filter", (d, which) -> {
                    int number = parseByte(field.getText().toString());
                    if (number < 0) {
                        Toast.makeText(activity, "Enter a number from 0 to 255", Toast.LENGTH_SHORT).show();
                    } else {
                        callback.onNumber(number);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 0-255 from decimal, or hex with 0x or $; -1 if it isn't one. */
    static int parseByte(String text) {
        text = text.trim();
        try {
            int value;
            if (text.startsWith("0x") || text.startsWith("0X")) value = Integer.parseInt(text.substring(2), 16);
            else if (text.startsWith("$")) value = Integer.parseInt(text.substring(1), 16);
            else value = Integer.parseInt(text);
            return value >= 0 && value <= 255 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static EditText numberField(Activity activity, String initial) {
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setText(initial);
        field.setSelectAllOnFocus(true);
        return field;
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
