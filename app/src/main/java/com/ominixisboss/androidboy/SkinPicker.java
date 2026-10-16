package com.ominixisboss.androidboy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The skin chooser dialog, and importing skins from .zip files. Shared by both activities. */
final class SkinPicker {
    interface Callbacks {
        /** The active skin changed (chosen, imported, or deleted). */
        void onSkinChanged();

        /** The dialog closed. */
        void onDismissed();
    }

    /** Request code for choosing the Clear skins' backdrop; both activities pass the result to {@link #importClearBackdrop}. */
    static final int REQUEST_CLEAR_BACKDROP = 0x0C1E;

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
        // Two shortcuts first: searching every skin by name, and a random one.
        int shortcuts = 2;
        Settings.TwoLineAdapter adapter = new Settings.TwoLineAdapter(activity, categories.size() + shortcuts) {
            @Override
            void bind(int position, TextView title, TextView detail) {
                if (position == 0) {
                    title.setText("Search skins…");
                    detail.setText("Find any skin by name, in every group");
                    return;
                }
                if (position == 1) {
                    title.setText("Surprise me");
                    detail.setText("Switch to a random skin");
                    return;
                }
                position -= shortcuts;
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
                .setView(Settings.listView(activity, adapter, position -> {
                    if (position == 0) {
                        search(activity, library, callbacks, top[0], adapter);
                    } else if (position == 1) {
                        surprise(library, callbacks, top[0]);
                    } else {
                        String category = categories.get(position - shortcuts);
                        showCategory(activity, library, category, new ArrayList<>(library.list(category)), callbacks,
                                top[0], adapter);
                    }
                }))
                .setNeutralButton(R.string.import_skin, (d, which) -> startImport(activity, importRequestCode))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        top[0].setOnDismissListener(d -> callbacks.onDismissed());
        top[0].show();
    }

    /** Preview cards per row in a category. */
    /** Two to a row, so the previews are big enough to see the details. */
    static final int COLUMNS = 2;

    /** The skins in a category, as preview cards; tap one to use it, long-press an imported one to delete it. */
    /** Asks for a name, then shows every skin whose name or group has all its words. */
    private static void search(Activity activity, SkinLibrary library, Callbacks callbacks, AlertDialog top,
                               Settings.TwoLineAdapter categoryList) {
        android.widget.EditText field = new android.widget.EditText(activity);
        field.setHint("e.g. pocket, neon, clear, purple");
        field.setSingleLine(true);
        int pad = dp(activity, 20);
        android.widget.FrameLayout box = new android.widget.FrameLayout(activity);
        box.setPadding(pad, dp(activity, 8), pad, 0);
        box.addView(field);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Search skins")
                .setView(box)
                .setPositiveButton("Search", (d, which) -> {
                    List<SkinLibrary.Entry> found = matching(library.list(), field.getText().toString());
                    if (found.isEmpty()) {
                        android.widget.Toast.makeText(activity, "No skins match", android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        showCategory(activity, library, "\u201C" + field.getText().toString().trim() + "\u201D", found,
                                callbacks, top, categoryList);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
    }

    /** The skins whose name or group contains every word of {@code query}, ignoring case. */
    static List<SkinLibrary.Entry> matching(List<SkinLibrary.Entry> entries, String query) {
        String[] words = query.toLowerCase(java.util.Locale.ROOT).trim().split("\\s+");
        List<SkinLibrary.Entry> found = new ArrayList<>();
        for (SkinLibrary.Entry entry : entries) {
            String haystack = (entry.name + " " + entry.category).toLowerCase(java.util.Locale.ROOT);
            boolean all = true;
            for (String word : words) {
                if (!word.isEmpty() && !haystack.contains(word)) {
                    all = false;
                    break;
                }
            }
            if (all) found.add(entry);
        }
        return found;
    }

    /** Switches to a random skin other than the current one. */
    private static void surprise(SkinLibrary library, Callbacks callbacks, AlertDialog top) {
        List<SkinLibrary.Entry> all = new ArrayList<>(library.list());
        all.removeIf(entry -> entry.id.equals(library.activeId()));
        if (all.isEmpty()) return;
        library.setActive(all.get(new java.util.Random().nextInt(all.size())).id);
        callbacks.onSkinChanged();
        top.dismiss();
    }

    private static void showCategory(Activity activity, SkinLibrary library, String category,
                                     List<SkinLibrary.Entry> entries, Callbacks callbacks,
                                     AlertDialog top, Settings.TwoLineAdapter categoryList) {
        GridView grid = new GridView(activity);
        grid.setNumColumns(COLUMNS);
        int gap = dp(activity, 10);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(gap);
        grid.setPadding(gap * 2, gap, gap * 2, gap);
        grid.setClipToPadding(false);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        ExecutorService loader = Executors.newSingleThreadExecutor();
        Handler main = new Handler(Looper.getMainLooper());
        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return entries.size();
            }

            @Override
            public SkinLibrary.Entry getItem(int position) {
                return entries.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                PreviewCard card = convertView instanceof PreviewCard ? (PreviewCard) convertView : new PreviewCard(activity);
                SkinLibrary.Entry entry = entries.get(position);
                card.bind(entry, entry.id.equals(library.activeId()));
                Bitmap preview = SkinPreviews.cached(entry.id);
                if (preview != null) {
                    card.image.setImageBitmap(preview);
                } else {
                    card.image.setImageDrawable(null);
                    loadPreview(library, entry, card, loader, main);
                }
                return card;
            }
        };
        grid.setAdapter(adapter);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(category)
                .setView(grid)
                .setNegativeButton(R.string.back, null);
        boolean clear = ClearSkin.CATEGORY.equals(category);
        if (clear) builder.setNeutralButton("Backdrop…", null);
        AlertDialog dialog = builder.create();
        if (clear) {
            // Set the listener once shown, so tapping it doesn't close the grid.
            dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    showBackdropOptions(activity, adapter, callbacks, () -> {
                        dialog.dismiss();
                        top.dismiss();
                    })));
        }
        dialog.setOnDismissListener(d -> loader.shutdownNow());
        grid.setOnItemClickListener((parent, view, position, id) -> {
            library.setActive(entries.get(position).id);
            callbacks.onSkinChanged();
            dialog.dismiss();
            top.dismiss();
        });
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
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
        dialog.show();
    }

    /**
     * The Clear skins' customization: a GIF or picture seen through the plastic, whether the
     * circuit board shows over it, and how strongly the plastic is tinted. {@code closePicker}
     * closes the skin picker while the player chooses a file.
     */
    static void showBackdropOptions(Activity activity, BaseAdapter previews, Callbacks callbacks, Runnable closePicker) {
        boolean has = ClearBackdrop.exists(activity);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(has ? "Change the GIF or picture…" : "Choose a GIF or picture…");
        actions.add(() -> {
            closePicker.run();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            activity.startActivityForResult(intent, REQUEST_CLEAR_BACKDROP);
        });
        Runnable refresh = () -> {
            previews.notifyDataSetChanged();
            callbacks.onSkinChanged();
        };
        if (has) {
            boolean board = ClearBackdrop.showsBoard(activity);
            labels.add(board ? "Hide the circuit board" : "Show the circuit board over it");
            actions.add(() -> {
                ClearBackdrop.setShowsBoard(activity, !board);
                refresh.run();
            });
        }
        labels.add("Plastic tint: " + ClearBackdrop.TINT_NAMES[ClearBackdrop.tint(activity)]);
        actions.add(() -> new AlertDialog.Builder(activity)
                .setTitle("Plastic tint")
                .setSingleChoiceItems(ClearBackdrop.TINT_NAMES, ClearBackdrop.tint(activity), (d, which) -> {
                    ClearBackdrop.setTint(activity, which);
                    refresh.run();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        if (has) {
            labels.add("Remove the backdrop");
            actions.add(() -> {
                ClearBackdrop.remove(activity);
                refresh.run();
            });
        }
        new AlertDialog.Builder(activity)
                .setTitle("Clear skins backdrop")
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Saves the chosen picture as the Clear skins' backdrop on a background thread, switches to a
     * Clear skin if another is in use, then calls {@code onDone} on the main thread.
     */
    static void importClearBackdrop(Activity activity, SkinLibrary library, Uri uri, Runnable onDone) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                ClearBackdrop.save(activity, uri);
                main.post(() -> {
                    if (!library.activeId().startsWith("clear:")) library.setActive(ClearSkin.ALL[0].id());
                    Toast.makeText(activity, "Backdrop set for the Clear skins", Toast.LENGTH_SHORT).show();
                    onDone.run();
                });
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                main.post(() -> Toast.makeText(activity, "Could not use that picture: " + message, Toast.LENGTH_LONG).show());
            }
        }, "Clear backdrop").start();
    }

    /**
     * Draws a card's preview. Built-in skins are shared with the game screen and quick to draw,
     * so they're drawn on the main thread; image skins load their artwork in the background, each
     * into its own copy.
     */
    private static void loadPreview(SkinLibrary library, SkinLibrary.Entry entry, PreviewCard card,
                                    ExecutorService loader, Handler main) {
        card.pendingId = entry.id;
        boolean builtIn = entry.id.startsWith("theme:") || entry.id.startsWith("soft:") || entry.id.startsWith("glass:")
                || entry.id.startsWith("clear:");
        Runnable show = () -> {
            // The card may have been reused for another skin meanwhile.
            Bitmap preview = SkinPreviews.cached(entry.id);
            if (preview != null && entry.id.equals(card.pendingId)) card.image.setImageBitmap(preview);
        };
        if (builtIn) {
            main.post(() -> {
                Skin skin = library.load(entry.id);
                if (skin != null) SkinPreviews.get(skin);
                show.run();
            });
            return;
        }
        try {
            loader.execute(() -> {
                Skin skin = library.load(entry.id);
                if (skin == null) return;
                try {
                    SkinPreviews.get(skin);
                } catch (RuntimeException | OutOfMemoryError e) {
                    return; // No preview; the name is still there.
                }
                main.post(show);
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // The dialog closed.
        }
    }

    /** A skin's preview with its name underneath; the one in use is outlined. */
    private static final class PreviewCard extends LinearLayout {
        final ImageView image;
        final TextView name;
        String pendingId;

        PreviewCard(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = dp(context, 4);
            setPadding(pad, pad, pad, pad);
            image = new ImageView(context) {
                @Override
                protected void onMeasure(int widthSpec, int heightSpec) {
                    // Phone-shaped, whatever the column width.
                    int width = MeasureSpec.getSize(widthSpec);
                    setMeasuredDimension(width, width * SkinPreviews.HEIGHT / SkinPreviews.WIDTH);
                }
            };
            image.setScaleType(ImageView.ScaleType.FIT_XY);
            image.setClipToOutline(true);
            image.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(view.getContext(), 8));
                }
            });
            addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(0, dp(context, 6), 0, dp(context, 2));
            addView(name, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }

        void bind(SkinLibrary.Entry entry, boolean active) {
            name.setText(active ? "✓ " + entry.name : entry.name);
            name.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(getContext(), 12));
            background.setColor(active ? 0x2233B5E5 : 0x00000000);
            if (active) background.setStroke(dp(getContext(), 2), 0xFF33B5E5);
            setBackground(background);
            setContentDescription(entry.name + (active ? ", in use" : ""));
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
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
