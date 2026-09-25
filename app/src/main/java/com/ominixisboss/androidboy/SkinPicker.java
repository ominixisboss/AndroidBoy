package com.ominixisboss.androidboy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.IOException;
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

    static void show(Activity activity, SkinLibrary library, int importRequestCode, Callbacks callbacks) {
        List<SkinLibrary.Entry> entries = library.list();
        String[] labels = new String[entries.size()];
        int checked = 0;
        for (int i = 0; i < entries.size(); i++) {
            SkinLibrary.Entry entry = entries.get(i);
            labels[i] = entry.dir != null ? entry.name + " (imported)" : entry.name;
            if (entry.id.equals(library.activeId())) checked = i;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.skin)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    library.setActive(entries.get(which).id);
                    callbacks.onSkinChanged();
                    d.dismiss();
                })
                .setNeutralButton(R.string.import_skin, (d, which) -> startImport(activity, importRequestCode))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(d -> callbacks.onDismissed());
        dialog.show();
        dialog.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
            SkinLibrary.Entry entry = entries.get(position);
            if (entry.dir == null) return false;
            new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.delete_title, entry.name))
                    .setPositiveButton(R.string.delete, (d, which) -> {
                        library.delete(entry);
                        callbacks.onSkinChanged();
                        dialog.dismiss();
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
