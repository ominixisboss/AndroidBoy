package com.ominixisboss.androidboy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The game library: import ROMs, launch them and manage their save files. */
public final class MainActivity extends Activity {
    private static final int REQUEST_IMPORT_ROM = 1;
    private static final int REQUEST_IMPORT_SAVE = 2;
    private static final int REQUEST_EXPORT_SAVE = 3;
    private static final int REQUEST_IMPORT_SKIN = 4;
    private static final int REQUEST_PICK_ART = 5;
    private static final int REQUEST_BACKUP = 6;
    private static final int REQUEST_BACKUP_WITH_GAMES = 7;
    private static final int REQUEST_RESTORE = 8;
    private static final String STATE_PENDING_ROM = "pending_rom";
    private static final String TAG = "AndroidBoy";
    /** Home-screen shortcuts open a game through here (the game screen itself isn't exported). */
    static final String ACTION_PLAY = "com.ominixisboss.androidboy.PLAY";
    private static final int SHORTCUT_ICON_SIZE = 192;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService artLoader = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<File> roms = new ArrayList<>();
    private final List<GameStore.Row> rows = new ArrayList<>();
    /** Pictures by ROM file name; a missing key means "not loaded yet", null values aren't stored. */
    private final LruCache<String, Bitmap> art = new LruCache<>(80);
    private final Set<String> artRequested = new HashSet<>();
    private RomLibrary library;
    private GameStore store;
    private BoxArt boxArt;
    private GameListAdapter adapter;
    /** The game whose save file is being imported or exported. */
    private String pendingRom;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        library = new RomLibrary(this);
        store = new GameStore(this);
        boxArt = new BoxArt(this);
        if (savedInstanceState != null) {
            pendingRom = savedInstanceState.getString(STATE_PENDING_ROM);
        }

        ListView list = findViewById(R.id.rom_list);
        adapter = new GameListAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));
        list.setOnItemClickListener((parent, view, position, id) -> {
            GameStore.Row row = rows.get(position);
            if (!row.isHeading()) launch(row.rom);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            GameStore.Row row = rows.get(position);
            if (row.isHeading()) return false;
            showGameOptions(row.rom);
            return true;
        });
        Button add = findViewById(R.id.add_rom);
        add.setOnClickListener(v -> pickRom());
        TextView version = findViewById(R.id.version);
        version.setText(getString(R.string.powered_by, BuildConfig.SAMEBOY_VERSION));

        if (savedInstanceState == null) handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && ACTION_PLAY.equals(intent.getAction())) {
            String name = intent.getStringExtra(EmulatorActivity.EXTRA_ROM);
            File rom = name == null ? null : library.romFile(name);
            if (rom != null && rom.isFile()) {
                launch(rom);
            } else {
                Toast.makeText(this, "That game isn't in the library any more", Toast.LENGTH_LONG).show();
            }
            return;
        }
        handleViewIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_PENDING_ROM, pendingRom);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
        artLoader.shutdownNow();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.add_game).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, 4, 1, R.string.skin);
        menu.add(0, 2, 2, R.string.settings);
        menu.add(0, 6, 1, R.string.homebrew_hub);
        menu.add(0, 5, 3, R.string.retroachievements);
        menu.add(0, 7, 3, "Backup & restore");
        menu.add(0, 3, 4, R.string.about);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                pickRom();
                return true;
            case 2:
                new Settings(this).showDialog(this, choice -> {}, () -> {});
                return true;
            case 3:
                showAbout();
                return true;
            case 5:
                startActivity(new Intent(this, AchievementsActivity.class));
                return true;
            case 6:
                startActivity(new Intent(this, HomebrewActivity.class));
                return true;
            case 7:
                showBackupOptions();
                return true;
            case 4:
                SkinPicker.show(this, new SkinLibrary(this), REQUEST_IMPORT_SKIN, new SkinPicker.Callbacks() {
                    @Override
                    public void onSkinChanged() {}

                    @Override
                    public void onDismissed() {}
                });
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void refresh() {
        roms.clear();
        roms.addAll(library.list());
        rows.clear();
        rows.addAll(store.arrange(roms));
        adapter.notifyDataSetChanged();
        updateRecentShortcuts();
        if (new Settings(this).isOn(Settings.BOX_ART)) fetchMissingArt();
    }

    // ---- Backup ----

    private void showBackupOptions() {
        String[] items = {
                "Back up saves, states, cheats and settings",
                "Back up all that and the games",
                "Restore from a backup…",
        };
        new AlertDialog.Builder(this)
                .setTitle("Backup & restore")
                .setItems(items, (d, which) -> {
                    if (which == 2) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        startActivityForResult(intent, REQUEST_RESTORE);
                        return;
                    }
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/zip");
                    intent.putExtra(Intent.EXTRA_TITLE, Gallery.fileName("AndroidBoy backup", System.currentTimeMillis())
                            .replace(' ', '-') + ".zip");
                    startActivityForResult(intent, which == 1 ? REQUEST_BACKUP_WITH_GAMES : REQUEST_BACKUP);
                })
                .show();
    }

    private void writeBackup(Uri uri, boolean includeGames) {
        Toast.makeText(this, "Backing up…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            String message;
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Could not open the file");
                Backup.write(this, out, includeGames);
                message = "Backup saved";
            } catch (IOException | SecurityException e) {
                message = "Could not back up: " + e.getMessage();
            }
            String result = message;
            mainHandler.post(() -> Toast.makeText(this, result, Toast.LENGTH_LONG).show());
        });
    }

    private void confirmRestore(Uri uri) {
        new AlertDialog.Builder(this)
                .setTitle("Restore this backup?")
                .setMessage("Saves, states, cheats and settings in the backup replace the ones here with the same "
                        + "names. Everything else is kept.")
                .setPositiveButton("Restore", (d, which) -> {
                    for (File rom : roms) EmulatorActivity.forgetLoadedRom(rom);
                    io.execute(() -> {
                        String message;
                        try (InputStream in = getContentResolver().openInputStream(uri)) {
                            if (in == null) throw new IOException("Could not open the file");
                            Backup.Summary summary = Backup.restore(this, in);
                            message = "Restored " + summary.files + (summary.files == 1 ? " file" : " files")
                                    + (summary.games > 0 ? ", including " + summary.games
                                    + (summary.games == 1 ? " game" : " games") : "");
                        } catch (IOException | SecurityException e) {
                            message = "Could not restore: " + e.getMessage();
                        }
                        String result = message;
                        mainHandler.post(() -> {
                            art.evictAll();
                            artRequested.clear();
                            refresh();
                            Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                        });
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- Box art ----

    /** Looks for pictures of games that don't have one, one at a time in the background. */
    private void fetchMissingArt() {
        for (File rom : roms) {
            if (!artRequested.add(rom.getName()) || !boxArt.needsFetch(rom)) continue;
            artLoader.execute(() -> {
                try {
                    if (boxArt.fetch(rom)) mainHandler.post(() -> artChanged(rom));
                } catch (IOException e) {
                    // Offline or rate-limited; try again next time the list is shown.
                    mainHandler.post(() -> artRequested.remove(rom.getName()));
                    Log.i(TAG, "No box art for " + rom.getName() + " yet: " + e.getMessage());
                }
            });
        }
    }

    private void artChanged(File rom) {
        art.remove(rom.getName());
        adapter.notifyDataSetChanged();
        updateRecentShortcuts();
    }

    /** The cached picture, or null (loading it if it hasn't been yet). */
    private Bitmap artFor(File rom) {
        Bitmap bitmap = art.get(rom.getName());
        if (bitmap == null && boxArt.artFile(rom).isFile()) {
            bitmap = boxArt.load(rom);
            if (bitmap != null) art.put(rom.getName(), bitmap);
        }
        return bitmap;
    }

    private void showArtOptions(File rom) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Choose a picture…");
        actions.add(() -> {
            pendingRom = rom.getName();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, REQUEST_PICK_ART);
        });
        labels.add("Download again");
        actions.add(() -> {
            boxArt.delete(rom);
            artRequested.remove(rom.getName());
            art.remove(rom.getName());
            artLoader.execute(() -> {
                String message;
                try {
                    message = boxArt.fetch(rom) ? null : "No box art found for this game";
                } catch (IOException e) {
                    message = "Could not download the box art: " + e.getMessage();
                }
                String result = message;
                mainHandler.post(() -> {
                    artChanged(rom);
                    if (result != null) Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                });
            });
        });
        if (boxArt.artFile(rom).isFile()) {
            labels.add("Remove picture");
            actions.add(() -> {
                artRequested.add(rom.getName()); // Don't download it again straight away.
                try {
                    boxArt.remove(rom);
                } catch (IOException e) {
                    Log.w(TAG, "Could not remove the picture", e);
                }
                artChanged(rom);
            });
        }
        new AlertDialog.Builder(this)
                .setTitle("Box art")
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void setCustomArt(File rom, Uri uri) {
        artLoader.execute(() -> {
            String message;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Could not open the picture");
                boxArt.setCustom(rom, in);
                message = null;
            } catch (IOException | SecurityException e) {
                message = "Could not use that picture: " + e.getMessage();
            }
            String result = message;
            mainHandler.post(() -> {
                artChanged(rom);
                if (result != null) Toast.makeText(this, result, Toast.LENGTH_LONG).show();
            });
        });
    }

    // ---- Shortcuts ----

    private Intent playIntent(File rom) {
        return new Intent(ACTION_PLAY, null, this, MainActivity.class)
                .putExtra(EmulatorActivity.EXTRA_ROM, rom.getName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    private ShortcutInfo shortcut(File rom) {
        Bitmap picture = artFor(rom);
        Icon icon = picture != null
                ? Icon.createWithBitmap(squareIcon(picture))
                : Icon.createWithResource(this, R.mipmap.ic_launcher);
        String name = RomLibrary.baseName(rom);
        return new ShortcutInfo.Builder(this, "game:" + rom.getName())
                .setShortLabel(CheatDatabase.defaultQuery(name))
                .setLongLabel(name)
                .setIcon(icon)
                .setIntent(playIntent(rom))
                .build();
    }

    /** Box art fitted inside a square with a little room, as launchers expect. */
    private static Bitmap squareIcon(Bitmap picture) {
        int size = SHORTCUT_ICON_SIZE;
        Bitmap icon = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(icon);
        float scale = Math.min(size * 0.9f / picture.getWidth(), size * 0.9f / picture.getHeight());
        float width = picture.getWidth() * scale;
        float height = picture.getHeight() * scale;
        android.graphics.RectF target = new android.graphics.RectF((size - width) / 2, (size - height) / 2,
                (size + width) / 2, (size + height) / 2);
        canvas.drawBitmap(picture, null, target, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        return icon;
    }

    /** Long-pressing the app icon offers the last few games played. */
    private void updateRecentShortcuts() {
        ShortcutManager manager = getSystemService(ShortcutManager.class);
        if (manager == null) return;
        List<ShortcutInfo> shortcuts = new ArrayList<>();
        int max = Math.min(3, manager.getMaxShortcutCountPerActivity());
        for (File rom : store.recent(roms, max)) shortcuts.add(shortcut(rom));
        try {
            manager.setDynamicShortcuts(shortcuts);
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "Could not update shortcuts", e);
        }
    }

    private void addToHomeScreen(File rom) {
        ShortcutManager manager = getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "Your home screen doesn't support adding shortcuts", Toast.LENGTH_LONG).show();
            return;
        }
        manager.requestPinShortcut(shortcut(rom), null);
    }

    // ---- Game list ----

    /** Section headings and games with their box art, how recently they were played, and a star for favourites. */
    private final class GameListAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public GameStore.Row getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).isHeading() ? 0 : 1;
        }

        @Override
        public boolean isEnabled(int position) {
            return !rows.get(position).isHeading();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            GameStore.Row row = rows.get(position);
            if (row.isHeading()) {
                TextView heading = convertView instanceof TextView ? (TextView) convertView : headingView();
                heading.setText(row.heading);
                return heading;
            }
            GameRow view = convertView instanceof GameRow ? (GameRow) convertView : new GameRow();
            view.bind(row.rom);
            return view;
        }

        private TextView headingView() {
            TextView view = new TextView(MainActivity.this);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            view.setTypeface(Typeface.DEFAULT_BOLD);
            view.setAllCaps(true);
            view.setPadding(dp(16), dp(16), dp(16), dp(4));
            return view;
        }
    }

    private final class GameRow extends LinearLayout {
        final ImageView picture;
        final TextView title;
        final TextView detail;

        GameRow() {
            super(MainActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(16), dp(6), dp(16), dp(6));
            setMinimumHeight(dp(64));
            picture = new ImageView(getContext());
            picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LayoutParams pictureParams = new LayoutParams(dp(52), dp(52));
            pictureParams.setMarginEnd(dp(14));
            addView(picture, pictureParams);
            LinearLayout text = new LinearLayout(getContext());
            text.setOrientation(VERTICAL);
            title = new TextView(getContext());
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            detail = new TextView(getContext());
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            detail.setSingleLine(true);
            text.addView(title);
            text.addView(detail);
            addView(text, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        }

        void bind(File rom) {
            title.setText(RomLibrary.baseName(rom));
            long played = store.lastPlayed(rom);
            String when = played > 0
                    ? "Played " + DateUtils.getRelativeTimeSpanString(played, System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS)
                    : "Not played yet";
            detail.setText(store.isFavourite(rom) ? "★ " + when : when);
            Bitmap bitmap = artFor(rom);
            if (bitmap != null) {
                picture.setImageBitmap(bitmap);
            } else {
                picture.setImageResource(R.mipmap.ic_launcher);
            }
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void launch(File rom) {
        Intent intent = new Intent(this, EmulatorActivity.class);
        intent.putExtra(EmulatorActivity.EXTRA_ROM, rom.getName());
        startActivity(intent);
    }

    private void handleViewIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        importRom(intent.getData(), true);
    }

    private void pickRom() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // ROMs rarely have a registered MIME type, so allow any file.
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_IMPORT_ROM);
    }

    private void importRom(Uri uri, boolean launchAfterImport) {
        io.execute(() -> {
            try {
                File rom = library.importRom(this, uri);
                mainHandler.post(() -> {
                    EmulatorActivity.forgetLoadedRom(rom);
                    refresh();
                    if (launchAfterImport) launch(rom);
                });
            } catch (IOException | SecurityException e) {
                mainHandler.post(() -> Toast.makeText(this,
                        getString(R.string.import_failed, e.getMessage()), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showGameOptions(File rom) {
        boolean favourite = store.isFavourite(rom);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(getString(R.string.play));
        actions.add(() -> launch(rom));
        labels.add(favourite ? "Remove from favourites" : "Add to favourites");
        actions.add(() -> {
            store.setFavourite(rom, !favourite);
            refresh();
        });
        labels.add("Add to home screen");
        actions.add(() -> addToHomeScreen(rom));
        labels.add("Box art…");
        actions.add(() -> showArtOptions(rom));
        labels.add(getString(R.string.import_save));
        actions.add(() -> pickSaveToImport(rom));
        labels.add(getString(R.string.export_save));
        actions.add(() -> exportSave(rom));
        labels.add(getString(R.string.delete));
        actions.add(() -> confirmDelete(rom));
        new AlertDialog.Builder(this)
                .setTitle(RomLibrary.baseName(rom))
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void pickSaveToImport(File rom) {
        pendingRom = rom.getName();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT_SAVE);
    }

    private void exportSave(File rom) {
        if (!library.batteryFile(rom).isFile()) {
            Toast.makeText(this, R.string.no_save, Toast.LENGTH_SHORT).show();
            return;
        }
        pendingRom = rom.getName();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, RomLibrary.baseName(rom) + ".sav");
        startActivityForResult(intent, REQUEST_EXPORT_SAVE);
    }

    private void confirmDelete(File rom) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.delete_title, RomLibrary.baseName(rom)))
                .setMessage(R.string.delete_message)
                .setPositiveButton(R.string.delete, (d, which) -> {
                    EmulatorActivity.forgetLoadedRom(rom);
                    library.delete(rom);
                    store.forget(rom);
                    boxArt.delete(rom);
                    art.remove(rom.getName());
                    ShortcutManager manager = getSystemService(ShortcutManager.class);
                    if (manager != null) {
                        manager.disableShortcuts(java.util.Collections.singletonList("game:" + rom.getName()),
                                "This game was deleted");
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(getString(R.string.about_text, BuildConfig.SAMEBOY_VERSION))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.licenses, (d, which) -> showLicense())
                .show();
    }

    private void showLicense() {
        StringBuilder text = new StringBuilder();
        // SameBoy (the emulator core) and rcheevos (RetroAchievements), both MIT-style licenses.
        String[][] licenses = {{"SameBoy", "licenses/SameBoy.txt"}, {"rcheevos (RetroAchievements)", "licenses/rcheevos.txt"}};
        for (String[] license : licenses) {
            if (text.length() > 0) text.append("\n\n");
            text.append(license[0]).append("\n\n");
            try (InputStream in = getAssets().open(license[1])) {
                text.append(new String(readSmallFile(in), java.nio.charset.StandardCharsets.UTF_8).trim());
            } catch (IOException e) {
                text.append(e.getMessage());
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.licenses)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQUEST_IMPORT_SKIN) {
            if (data.getData() != null) SkinPicker.importSkin(this, new SkinLibrary(this), data.getData(), () -> {});
            return;
        }

        if (requestCode == REQUEST_BACKUP || requestCode == REQUEST_BACKUP_WITH_GAMES) {
            if (data.getData() != null) writeBackup(data.getData(), requestCode == REQUEST_BACKUP_WITH_GAMES);
            return;
        }
        if (requestCode == REQUEST_RESTORE) {
            if (data.getData() != null) confirmRestore(data.getData());
            return;
        }

        if (requestCode == REQUEST_IMPORT_ROM) {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    importRom(data.getClipData().getItemAt(i).getUri(), false);
                }
            } else if (data.getData() != null) {
                importRom(data.getData(), true);
            }
            return;
        }

        if (pendingRom == null || data.getData() == null) return;
        File rom = library.romFile(pendingRom);
        Uri uri = data.getData();
        pendingRom = null;
        if (requestCode == REQUEST_PICK_ART) {
            setCustomArt(rom, uri);
            return;
        }
        if (requestCode == REQUEST_IMPORT_SAVE) {
            copySave(uri, rom, true);
        } else if (requestCode == REQUEST_EXPORT_SAVE) {
            copySave(uri, rom, false);
        }
    }

    private void copySave(Uri uri, File rom, boolean importing) {
        if (importing) EmulatorActivity.forgetLoadedRom(rom);
        io.execute(() -> {
            String message;
            try {
                if (importing) {
                    byte[] save;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IOException("Could not open the file");
                        save = readSmallFile(in);
                    }
                    RomLibrary.writeAtomically(library.batteryFile(rom), save);
                    // The resume state holds its own copy of the save RAM and would override the import.
                    library.stateFile(rom, RomLibrary.AUTO_SLOT).delete();
                    message = getString(R.string.save_imported);
                } else {
                    byte[] save = RomLibrary.readFile(library.batteryFile(rom));
                    try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                        if (out == null) throw new IOException("Could not open the file");
                        out.write(save);
                    }
                    message = getString(R.string.save_exported);
                }
            } catch (IOException | SecurityException e) {
                message = getString(R.string.save_failed, e.getMessage());
            }
            String result = message;
            mainHandler.post(() -> Toast.makeText(this, result, Toast.LENGTH_LONG).show());
        });
    }

    private static byte[] readSmallFile(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            // Largest real saves are 128 KiB of RAM plus RTC data.
            if (out.size() > 1024 * 1024) throw new IOException("This file is too large to be a save file");
        }
        return out.toByteArray();
    }
}
