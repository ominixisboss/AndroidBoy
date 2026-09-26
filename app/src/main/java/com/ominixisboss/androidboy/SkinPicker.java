package com.ominixisboss.androidboy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The skin chooser dialog, and importing skins from .zip files. Shared by both activities. */
final class SkinPicker {
    interface Callbacks {
        /** The active skin changed (chosen, imported, or deleted). */
        void onSkinChanged();

        /** The dialog closed. */
        void onDismissed();
    }

    private SkinPicker() {}

    /**
     * The skin chooser: first the categories (the one holding the current skin is marked), then
     * the skins in the chosen one. Picking a skin closes both.
     */
    static void show(Activity activity, SkinLibrary library, int importRequestCode, Callbacks callbacks) {
        List<String> categories = new ArrayList<>();
        for (String category : SkinLibrary.CATEGORIES) {
            // Imported only appears once there's something in it.
            if (!library.list(category).isEmpty()) categories.add(category);
        }
        Settings.TwoLineAdapter adapter = new Settings.TwoLineAdapter(activity, categories.size()) {
            @Override
            void bind(int position, TextView title, TextView detail) {
                List<SkinLibrary.Entry> entries = library.list(categories.get(position));
                title.setText(categories.get(position));
                String count = entries.size() == 1 ? "1 skin" : entries.size() + " skins";
                for (SkinLibrary.Entry entry : entries) {
                    if (entry.id.equals(library.activeId())) count += " · using " + entry.name;
                }
                detail.setText(count);
            }
        };
        AlertDialog[] top = new AlertDialog[1];
        top[0] = new AlertDialog.Builder(activity)
                .setTitle(R.string.skin)
                .setView(Settings.listView(activity, adapter, position ->
                        showCategory(activity, library, categories.get(position), callbacks, top[0], adapter)))
                .setNeutralButton(R.string.import_skin, (d, which) -> startImport(activity, importRequestCode))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        top[0].setOnDismissListener(d -> callbacks.onDismissed());
        top[0].show();
    }

    private static void showCategory(Activity activity, SkinLibrary library, String category, Callbacks callbacks,
                                     AlertDialog top, Settings.TwoLineAdapter categoryList) {
        List<SkinLibrary.Entry> entries = library.list(category);
        String[] labels = new String[entries.size()];
        int checked = -1;
        for (int i = 0; i < entries.size(); i++) {
            labels[i] = entries.get(i).name;
            if (entries.get(i).id.equals(library.activeId())) checked = i;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(category)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    library.setActive(entries.get(which).id);
                    callbacks.onSkinChanged();
                    d.dismiss();
                    top.dismiss();
                })
                .setNegativeButton(R.string.back, null)
                .create();
        dialog.show();
        dialog.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
            SkinLibrary.Entry entry = entries.get(position);
            if (entry.dir == null) return false; // Only imported skins can be deleted.
            new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.delete_title, entry.name))
                    .setPositiveButton(R.string.delete, (d, which) -> {
                        library.delete(entry);
                        callbacks.onSkinChanged();
                        dialog.dismiss();
                        categoryList.notifyDataSetChanged();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
    }

    static void startImport(Activity activity, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        activity.startActivityForResult(intent, requestCode);
    }

    /** Imports on a background thread, makes the new skin active, then calls {@code onImported} on the main thread. */
    static void importSkin(Activity activity, SkinLibrary library, Uri uri, Runnable onImported) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                SkinLibrary.Entry entry = library.importSkin(activity, uri);
                library.setActive(entry.id);
                main.post(() -> {
                    Toast.makeText(activity, activity.getString(R.string.skin_imported, entry.name), Toast.LENGTH_SHORT).show();
                    onImported.run();
                });
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                main.post(() -> Toast.makeText(activity, activity.getString(R.string.skin_import_failed, message),
                        Toast.LENGTH_LONG).show());
            }
        }, "Skin import").start();
    }
}
