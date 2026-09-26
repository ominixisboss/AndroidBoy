package com.ominixisboss.androidboy;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.KeyEvent;

/** Lets the player choose which controller (or keyboard) button does each action. */
final class ControllerDialog {
    private ControllerDialog() {}

    /** {@code onChanged} runs after each change, {@code onDismiss} when the list closes. */
    static void show(Activity activity, ControllerMapping mapping, Runnable onChanged, Runnable onDismiss) {
        ControllerMapping.Action[] actions = ControllerMapping.Action.values();
        Settings.TwoLineAdapter adapter = new Settings.TwoLineAdapter(activity, actions.length) {
            @Override
            void bind(int position, android.widget.TextView title, android.widget.TextView detail) {
                title.setText(actions[position].label);
                detail.setText(mapping.describe(actions[position]));
            }
        };
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Controller buttons")
                .setView(Settings.listView(activity, adapter, position ->
                        waitForButton(activity, mapping, actions[position], () -> {
                            adapter.notifyDataSetChanged();
                            onChanged.run();
                        })))
                .setPositiveButton("Done", null)
                .setNeutralButton("Reset all", (d, which) -> {
                    mapping.reset();
                    onChanged.run();
                })
                .create();
        dialog.setOnDismissListener(d -> onDismiss.run());
        dialog.show();
    }

    private static void waitForButton(Activity activity, ControllerMapping mapping, ControllerMapping.Action action,
                                      Runnable onChanged) {
        AlertDialog prompt = new AlertDialog.Builder(activity)
                .setTitle(action.label)
                .setMessage("Press the button to use for " + action.label + ".\n\nNow: " + mapping.describe(action))
                .setNeutralButton("No button", (d, which) -> {
                    mapping.clear(action);
                    onChanged.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        prompt.setOnKeyListener((d, keyCode, event) -> {
            // Back closes the prompt as usual; touch-screen navigation keys can't be assigned.
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME
                    || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                mapping.assign(action, keyCode);
                onChanged.run();
                d.dismiss();
            }
            return true;
        });
        prompt.show();
    }
}
