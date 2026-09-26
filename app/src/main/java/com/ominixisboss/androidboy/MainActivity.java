package com.ominixisboss.androidboy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The game library: import ROMs, launch them and manage their save files. */
public final class MainActivity extends Activity {
    private static final int REQUEST_IMPORT_ROM = 1;
    private static final int REQUEST_IMPORT_SAVE = 2;
    private static final int REQUEST_EXPORT_SAVE = 3;
    private static final int REQUEST_IMPORT_SKIN = 4;
    private static final String STATE_PENDING_ROM = "pending_rom";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<File> roms = new ArrayList<>();
    private RomLibrary library;
    private ArrayAdapter<String> adapter;
    /** The game whose save file is being imported or exported. */
    private String pendingRom;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        library = new RomLibrary(this);
        if (savedInstanceState != null) {
            pendingRom = savedInstanceState.getString(STATE_PENDING_ROM);
        }

        ListView list = findViewById(R.id.rom_list);
        adapter = new ArrayAdapter<>(this, R.layout.item_rom, R.id.rom_name);
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));
        list.setOnItemClickListener((parent, view, position, id) -> launch(roms.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showGameOptions(roms.get(position));
            return true;
        });
        Button add = findViewById(R.id.add_rom);
        add.setOnClickListener(v -> pickRom());
        TextView version = findViewById(R.id.version);
        version.setText(getString(R.string.powered_by, BuildConfig.SAMEBOY_VERSION));

        if (savedInstanceState == null) handleViewIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
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
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.add_game).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, 4, 1, R.string.skin);
        menu.add(0, 2, 2, R.string.settings);
        menu.add(0, 5, 3, R.string.retroachievements);
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
                AchievementDialogs.showAccount(this, false, null, () -> {});
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
        adapter.clear();
        for (File rom : roms) {
            adapter.add(RomLibrary.baseName(rom));
        }
        adapter.notifyDataSetChanged();
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
        String[] items = {
                getString(R.string.play),
                getString(R.string.import_save),
                getString(R.string.export_save),
                getString(R.string.delete),
        };
        new AlertDialog.Builder(this)
                .setTitle(RomLibrary.baseName(rom))
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: launch(rom); break;
                        case 1: pickSaveToImport(rom); break;
                        case 2: exportSave(rom); break;
                        case 3: confirmDelete(rom); break;
                        default: break;
                    }
                })
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
