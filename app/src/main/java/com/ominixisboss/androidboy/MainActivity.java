package com.ominixisboss.androidboy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private static final int REQUEST_PATCH_BASE = 9;
    private static final int REQUEST_FIND_PATCH = 10;
    private static final String STATE_PENDING_ROM = "pending_rom";
    private static final String TAG = "AndroidBoy";
    /** Home-screen shortcuts open a game through here (the game screen itself isn't exported). */
    static final String ACTION_PLAY = "com.ominixisboss.androidboy.PLAY";
    private static final int SHORTCUT_ICON_SIZE = 192;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService artLoader = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<File> roms = new ArrayList<>();
    /** Which system each game is for (GameLibrary.GB, GBC or GBA), by file name; read once from its header. */
    private final Map<String, Integer> systems = new HashMap<>();
    /** Pictures by ROM file name; a missing key means "not loaded yet", null values aren't stored. */
    private final LruCache<String, Bitmap> art = new LruCache<>(80);
    private final Set<String> artRequested = new HashSet<>();
    private RomLibrary library;
    private GameStore store;
    private BoxArt boxArt;
    private LibraryScreen screen;
    /** The game whose save file is being imported or exported. */
    private String pendingRom;
    /** A ROM hack patch waiting for the player to pick the game it goes on. */
    private Patcher.Patch pendingPatch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        library = new RomLibrary(this);
        store = new GameStore(this);
        boxArt = new BoxArt(this);
        if (savedInstanceState != null) {
            pendingRom = savedInstanceState.getString(STATE_PENDING_ROM);
        }
        screen = new LibraryScreen(this, new LibraryScreen.Host() {
            @Override public void play(File rom) {
                launch(rom);
            }

            @Override public void showOptions(File rom) {
                showGameOptions(rom);
            }

            @Override public void addGames() {
                pickRom();
            }

            @Override public void showMenu(View anchor) {
                showMainMenu(anchor);
            }

            @Override public void openHomebrewHub() {
                startActivity(new Intent(MainActivity.this, HomebrewActivity.class));
            }

            @Override public Bitmap art(File rom) {
                return artFor(rom);
            }

            @Override public boolean isFavourite(File rom) {
                return store.isFavourite(rom);
            }

            @Override public long lastPlayed(File rom) {
                return store.lastPlayed(rom);
            }
        });
        screen.setFooter(getString(R.string.powered_by, BuildConfig.SAMEBOY_VERSION));
        setContentView(screen);

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

    /** The header's ⋮ menu. */
    private void showMainMenu(View anchor) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
        onCreateOptionsMenu(popup.getMenu());
        popup.setOnMenuItemClickListener(this::onOptionsItemSelected);
        popup.show();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (screen.closeSearch()) return;
        super.onBackPressed();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.add_game);
        menu.add(0, 8, 0, "Apply a ROM hack patch…");
        menu.add(0, 9, 0, "Find ROM hack patches online…");
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
            case 8:
                explainPatches();
                return true;
            case 9:
                findPatches(null);
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
        for (File rom : roms) {
            if (!systems.containsKey(rom.getName())) systems.put(rom.getName(), GameLibrary.systemOf(rom));
        }
        screen.setGames(roms, systems);
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
        screen.artChanged();
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
                RomLibrary.Picked picked = library.read(this, uri);
                // A ROM hack patch goes on a game the player already has.
                Patcher.Patch patch = Patcher.find(picked.name, picked.data);
                if (patch != null) {
                    mainHandler.post(() -> choosePatchBase(patch));
                    return;
                }
                File rom = library.addRom(picked.name, picked.data);
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

    /** What patches are and how applying one works, then the file picker. */
    private void explainPatches() {
        new AlertDialog.Builder(this)
                .setTitle("Apply a ROM hack patch")
                .setMessage("ROM hacks and fan translations are shared as patch files (.ips, .ups or .bps, "
                        + "sometimes inside a .zip). A patch only holds the hack's changes, so it goes on your own "
                        + "copy of the original game.\n\nChoose the patch next, then the game to apply it to. The "
                        + "patched game is added to your library as a new game; the original stays as it is.")
                .setPositiveButton("Choose a patch", (d, which) -> pickRom())
                .setNeutralButton("Search online", (d, which) -> findPatches(null))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Searches the web for patches, for {@code query} if given; a downloaded patch comes back here. */
    private void findPatches(String query) {
        startActivityForResult(PatchSearchActivity.intent(this, query), REQUEST_FIND_PATCH);
    }

    /**
     * Asks which game {@code patch} goes on. Games that match the checksum the patch carries
     * (UPS and BPS) are marked and listed first.
     */
    private void choosePatchBase(Patcher.Patch patch) {
        List<File> games = library.list();
        long wanted = patch.sourceCrc();
        io.execute(() -> {
            List<File> matching = new ArrayList<>();
            if (wanted >= 0) {
                for (File game : games) {
                    try {
                        byte[] data = RomLibrary.readFile(game);
                        if (Patcher.crc32(data, data.length) == wanted) matching.add(game);
                    } catch (IOException | OutOfMemoryError ignored) {
                        // Not readable: just not marked.
                    }
                }
            }
            mainHandler.post(() -> {
                if (isFinishing()) return;
                List<File> ordered = new ArrayList<>(matching);
                for (File game : games) if (!matching.contains(game)) ordered.add(game);
                List<String> labels = new ArrayList<>();
                for (File game : ordered) {
                    String name = RomLibrary.baseName(game);
                    labels.add(matching.contains(game) ? "\u2713 " + name + "  (matches)" : name);
                }
                labels.add("Another file…");
                String title = "Apply " + patch.gameName() + " (" + patch.format() + ") to…";
                if (wanted >= 0 && matching.isEmpty()) {
                    Toast.makeText(this, String.format(java.util.Locale.ROOT,
                            "None of your games is the version this patch was made for (CRC32 %08X)", wanted),
                            Toast.LENGTH_LONG).show();
                }
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setItems(labels.toArray(new String[0]), (d, which) -> {
                            if (which < ordered.size()) {
                                File base = ordered.get(which);
                                applyPatch(patch, () -> new RomLibrary.Picked(base.getName(), RomLibrary.readFile(base)));
                            } else {
                                pendingPatch = patch;
                                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                                intent.addCategory(Intent.CATEGORY_OPENABLE);
                                intent.setType("*/*");
                                startActivityForResult(intent, REQUEST_PATCH_BASE);
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    private interface Source {
        RomLibrary.Picked get() throws IOException;
    }

    /** Applies {@code patch} to the game from {@code base} and adds the result to the library as a new game. */
    private void applyPatch(Patcher.Patch patch, Source base) {
        Toast.makeText(this, "Applying the patch…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                RomLibrary.Picked picked = base.get();
                RomLibrary.Picked rom = RomLibrary.romIn(picked.name, picked.data);
                byte[] patched = Patcher.apply(rom.data, patch.data);
                // Named after the patch, never replacing a game already there.
                String extension = RomLibrary.extensionFor(patched);
                String name = patch.gameName().replaceAll("[/\\\\:*?\"<>|\\p{Cntrl}]", "_");
                String candidate = name + extension;
                for (int i = 2; library.romFile(candidate).exists(); i++) candidate = name + " (" + i + ")" + extension;
                File game = library.addRom(candidate, patched);
                mainHandler.post(() -> {
                    refresh();
                    Toast.makeText(this, "Added " + RomLibrary.baseName(game), Toast.LENGTH_LONG).show();
                    launch(game);
                });
            } catch (IOException | SecurityException | OutOfMemoryError e) {
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                mainHandler.post(() -> new AlertDialog.Builder(this)
                        .setTitle("Couldn't apply the patch")
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show());
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
        labels.add("Find patches for this game…");
        actions.add(() -> findPatches(PatchSearchActivity.queryFor(rom.getName())));
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
        // SameBoy (the Game Boy core) and rcheevos (RetroAchievements), MIT-style; mGBA (the GBA core), MPL-2.0.
        String[][] licenses = {{"SameBoy", "licenses/SameBoy.txt"}, {"mGBA", "licenses/mGBA.txt"},
                {"rcheevos (RetroAchievements)", "licenses/rcheevos.txt"}};
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
        if (requestCode == SkinPicker.REQUEST_CLEAR_BACKDROP) {
            if (data.getData() != null) SkinPicker.importClearBackdrop(this, new SkinLibrary(this), data.getData(), () -> {});
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

        if (requestCode == REQUEST_FIND_PATCH) {
            String path = data.getStringExtra(PatchSearchActivity.EXTRA_FILE);
            String name = data.getStringExtra(PatchSearchActivity.EXTRA_NAME);
            if (path == null) return;
            io.execute(() -> {
                try {
                    byte[] bytes = RomLibrary.readFile(new File(path));
                    Patcher.Patch patch = Patcher.fromDownload(name, bytes);
                    mainHandler.post(() -> choosePatchBase(patch));
                } catch (IOException e) {
                    mainHandler.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
            return;
        }

        if (requestCode == REQUEST_PATCH_BASE) {
            Patcher.Patch patch = pendingPatch;
            pendingPatch = null;
            Uri uri = data.getData();
            if (patch != null && uri != null) applyPatch(patch, () -> library.read(this, uri));
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
