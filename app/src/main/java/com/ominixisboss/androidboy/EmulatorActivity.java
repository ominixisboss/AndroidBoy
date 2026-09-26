package com.ominixisboss.androidboy;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Runs a game: screen, on-screen controls, hardware input, save states and the in-game menu. */
public final class EmulatorActivity extends Activity
        implements EmulatorThread.Host, SkinView.Listener, Achievements.Listener {
    static final String EXTRA_ROM = "rom";
    private static final String TAG = "AndroidBoy";
    private static final int REQUEST_IMPORT_SKIN = 1;
    private static final int REQUEST_STORAGE = 2;
    private static final int REQUEST_CAMERA = 3;
    /** Screenshots are saved at 4× the Game Boy's resolution, with sharp pixels. */
    private static final int SCREENSHOT_SCALE = 4;

    // The core is a process-wide singleton; remember which ROM it holds.
    private static String loadedRomPath;
    private static int loadedSampleRate;
    private static boolean loadedRomIsCgb;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Settings settings;
    private RomLibrary library;
    private File rom;

    private SkinLibrary skins;
    /** The game area: the screen, the skin and overlays. */
    private FrameLayout root;
    /** Everything: toolbars around the game area, and the full-screen menu on top when open. */
    private FrameLayout frame;
    private GameToolbars toolbars;
    private GameMenuView menuView;
    /** Hidden with the full-screen button; a small button in the corner brings them back. */
    private boolean toolbarsHidden;
    private View showToolbarsButton;
    private boolean pausedByPlayer;
    private boolean rotationLocked;
    private GameScreen screen;
    private SkinView skinView;
    private Achievements achievements;
    private AchievementPopup achievementPopup;
    private TrackerOverlay trackerOverlay;
    private int shownFrameWidth = 160;
    private int shownFrameHeight = 144;
    private EmulatorThread thread;
    private Vibrator vibrator;

    private int touchKeys;
    private int hardwareKeys;
    private int axisKeys;
    private boolean fastForwardHeld;
    private boolean fastForwardTouched;
    private boolean rewindHeld;
    private boolean rewindTouched;
    private boolean fastForwardToggled;
    private boolean slowMotion;
    /** On-screen buttons that act as turbo buttons (Emulator.KEY_A/KEY_B), chosen from the menu. */
    private int touchTurboKeys;
    /** Keys held on a controller's turbo buttons (Y for A, X for B). */
    private int hardwareTurboKeys;
    /** Carries on between visits to the cheat search screen while this game is open. */
    private final CheatSearch cheatSearch = new CheatSearch();
    private GameStore gameStore;
    /** Printouts waiting for the storage permission (Android 9 and older). */
    private final List<int[]> pendingPrintouts = new ArrayList<>();
    private ControllerMapping controllerMapping;
    private android.util.SparseArray<ControllerMapping.Action> keyTable;
    /** The phone's camera, while a Game Boy Camera cartridge is running. */
    private CameraFeed cameraFeed;
    private boolean cameraPermissionAsked;
    /** The link cable, while plugged in. */
    private volatile LinkSession link;
    /** "Waiting for the other phone…", shown when a linked game is held up. */
    private android.widget.TextView linkWaitingView;
    /** Shown while the on-screen buttons are being moved. */
    private View editBar;
    private boolean controlsHiddenByGamepad;
    private int openDialogs;

    @Override
    @SuppressWarnings("deprecation") // Pre-API 35 window setup and the pre-API 31 vibrator service
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Emulator.init(getAssets());
        settings = new Settings(this);
        library = new RomLibrary(this);

        String name = getIntent().getStringExtra(EXTRA_ROM);
        rom = name == null ? null : library.romFile(name);
        if (rom == null || !rom.isFile()) {
            Toast.makeText(this, "Game not found", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setTitle(RomLibrary.baseName(rom));
        gameStore = new GameStore(this);
        gameStore.markPlayed(rom, System.currentTimeMillis());

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }

        skins = new SkinLibrary(this);
        Skin skin = skins.loadActive();
        root = new FrameLayout(this);
        root.setBackgroundColor(skin.backgroundColor());
        // The game screen sits under the skin, which leaves a hole for it; see onScreenRectChanged.
        screen = supportsGles3() ? new GlScreenView(this) : new CanvasScreenView(this);
        skinView = new SkinView(this, skin, this);
        skinView.setControlLayout(new ControlLayout(this));
        controllerMapping = new ControllerMapping(this);
        keyTable = controllerMapping.table();
        root.addView(screen.view(), new FrameLayout.LayoutParams(0, 0));
        root.addView(skinView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        achievements = Achievements.get(this);
        achievements.setListener(this);
        achievementPopup = new AchievementPopup(this);
        root.addView(achievementPopup, achievementPopup.layoutParams());
        trackerOverlay = new TrackerOverlay(this);
        root.addView(trackerOverlay, trackerOverlay.layoutParams());
        toolbars = new GameToolbars(this, toolbarActions());
        toolbars.setTitle(RomLibrary.baseName(rom));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(toolbars.top);
        column.addView(root, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        column.addView(toolbars.bottom);
        frame = new FrameLayout(this);
        frame.addView(column);
        frame.setOnApplyWindowInsetsListener(this::applyInsets);
        setContentView(frame);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        applyUiSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rom == null) return;
        hideSystemBars();
        if (!ensureLoaded()) {
            finish();
            return;
        }
        screen.onResume();
        thread = new EmulatorThread(this, loadedSampleRate);
        thread.setFastForwardSpeed(settings.get(Settings.FAST_FORWARD));
        thread.setTurboPeriod(settings.get(Settings.TURBO_SPEED));
        thread.setLink(link);
        updateSpeed();
        thread.setMuted(!settings.isOn(Settings.SOUND));
        thread.setPaused(openDialogs > 0 || pausedByPlayer);
        updateFastForward();
        updateRewind();
        pushKeys();
        thread.start();
        startCameraIfNeeded();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (thread == null) return;
        screen.onPause();
        if (cameraFeed != null) cameraFeed.stop();
        thread.shutdown();
        thread = null;
        if (settings.isOn(Settings.AUTO_SAVE)) {
            saveState(RomLibrary.AUTO_SLOT);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (achievements != null) achievements.removeListener(this);
        if (link != null) {
            // Emulation has stopped (onPause), so this runs right here.
            link.close();
            link = null;
            Emulator.nativeUnlink();
        }
        if (isFinishing() && rom != null && rom.getPath().equals(loadedRomPath)) {
            achievements.unloadGame();
            Emulator.nativeUnload();
            loadedRomPath = null;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    /** Loads this activity's ROM into the core unless it's already there. Emulation must be stopped. */
    private boolean ensureLoaded() {
        if (rom.getPath().equals(loadedRomPath)) return true;
        byte[] data;
        try {
            data = RomLibrary.readFile(rom);
        } catch (IOException e) {
            Toast.makeText(this, "Could not read the game: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
        loadedRomPath = null;
        loadedRomIsCgb = data.length > 0x143 && (data[0x143] & 0x80) != 0;

        settings.applyToCore();
        int sampleRate = EmulatorThread.outputSampleRate();
        if (!Emulator.nativeLoadRom(data, preferredModel(), sampleRate)) {
            Toast.makeText(this, "This doesn't look like a Game Boy game", Toast.LENGTH_LONG).show();
            return false;
        }
        loadedRomPath = rom.getPath();
        loadedSampleRate = sampleRate;
        achievements.loadGame(data);
        applyCheats();
        Emulator.nativeSetLinkAccessory(gameStore.linkAccessory(rom));

        File battery = library.batteryFile(rom);
        if (battery.isFile()) {
            try {
                Emulator.nativeLoadBattery(RomLibrary.readFile(battery));
            } catch (IOException e) {
                Log.w(TAG, "Could not read battery save", e);
            }
        }

        File autoState = library.stateFile(rom, RomLibrary.AUTO_SLOT);
        // Hardcore RetroAchievements doesn't allow save states, the automatic one included.
        boolean hardcore = achievements.hasAccount() && achievements.isHardcoreEnabled();
        if (hardcore && settings.isOn(Settings.AUTO_SAVE) && autoState.isFile()) {
            Toast.makeText(this, "Hardcore mode: starting from the game's own save", Toast.LENGTH_LONG).show();
        }
        if (!hardcore && settings.isOn(Settings.AUTO_SAVE) && autoState.isFile()) {
            try {
                if (!Emulator.nativeLoadState(RomLibrary.readFile(autoState))) {
                    Log.w(TAG, "Auto-save state could not be loaded");
                    Emulator.nativeReset();
                }
            } catch (IOException e) {
                Log.w(TAG, "Could not read auto-save state", e);
            }
        }
        return true;
    }

    /**
     * Makes the next launch of {@code romFile} reload it from disk, e.g. after its save file was replaced.
     * Only call while no game is running.
     */
    static void forgetLoadedRom(File romFile) {
        if (romFile.getPath().equals(loadedRomPath)) {
            if (Achievements.isCreated()) Achievements.existing().unloadGame();
            Emulator.nativeUnload();
            loadedRomPath = null;
        }
    }

    private int preferredModel() {
        return settings.get(loadedRomIsCgb ? Settings.CGB_MODEL : Settings.DMG_MODEL);
    }

    // ---- EmulatorThread.Host (called on the emulation thread) ----

    @Override
    public void onFrame(Frame frame) {
        screen.setFrame(frame);
        if (frame.width != shownFrameWidth || frame.height != shownFrameHeight) {
            // E.g. a Super Game Boy border appeared: the screen's aspect ratio changes.
            shownFrameWidth = frame.width;
            shownFrameHeight = frame.height;
            int width = frame.width;
            int height = frame.height;
            mainHandler.post(() -> skinView.setFrameSize(width, height));
        }
    }

    @Override
    public void onRumble(double amplitude) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (amplitude <= 0) {
            vibrator.cancel();
        } else {
            int strength = Math.max(1, Math.min(255, (int) (amplitude * 255)));
            // Long one-shot; cancelled when the game stops the motor.
            vibrator.vibrate(VibrationEffect.createOneShot(2000, strength));
        }
    }

    @Override
    public void onBatteryDirty() {
        byte[] data = Emulator.nativeSaveBattery();
        if (data == null) return;
        try {
            RomLibrary.writeAtomically(library.batteryFile(rom), data);
        } catch (IOException e) {
            Log.e(TAG, "Could not write battery save", e);
            mainHandler.post(() -> Toast.makeText(this, "Could not write the save file", Toast.LENGTH_LONG).show());
        }
    }

    @Override
    public void onIdle() {
        achievements.idle();
    }

    @Override
    public void onRewindFinished() {
        achievements.onGameReset();
    }

    // ---- RetroAchievements ----

    @Override
    public void onAchievementEvent(Achievements.Event event) {
        if (trackerOverlay.handle(event)) return;
        if (event.type == Achievements.EVENT_RESET) {
            trackerOverlay.clear();
            // Restarting one side would put two phones out of step.
            if (link instanceof NetLink) unplugLink("Hardcore mode restarted the game, so the link cable was unplugged");
            // Hardcore mode was turned on: the game has to start over.
            onEmulationThread(() -> {
                Emulator.nativeReset();
                achievements.onGameReset();
            });
            applyCheats();
            return;
        }
        achievementPopup.show(event);
    }

    private void showAchievements() {
        String[] summary = achievements.gameSummary();
        if (summary == null) {
            showAchievementAccount();
            return;
        }
        dialogOpened();
        // Rich presence reads game memory: ask on the emulation thread, then show the list.
        onEmulationThread(() -> {
            String presence = achievements.richPresence();
            mainHandler.post(() -> {
                if (isFinishing()) {
                    dialogClosed();
                    return;
                }
                AchievementDialogs.showList(this, presence, this::showAchievementAccount, this::dialogClosed);
            });
        });
    }

    private void showAchievementAccount() {
        dialogOpened();
        AchievementDialogs.showAccount(this, true, enabled -> {
            if (enabled && achievements.gameSummary() != null) {
                Toast.makeText(this, "Hardcore mode: the game restarts", Toast.LENGTH_SHORT).show();
            }
            updateRewind();
            applyCheats();
        }, () -> {
            applyCheats(); // Logging in or out can switch hardcore mode too.
            dialogClosed();
        });
    }

    // ---- Cheats ----

    private void showCheats() {
        dialogOpened();
        CheatDialogs.show(this, library.cheatFile(rom), RomLibrary.baseName(rom), loadedRomIsCgb,
                achievements.hasAccount() && achievements.isHardcoreEnabled(), this::applyCheats, this::dialogClosed);
    }

    private void showCheatSearch() {
        if (achievements.hasAccount() && achievements.isHardcoreEnabled()) {
            Toast.makeText(this, "Cheats are off in hardcore mode", Toast.LENGTH_SHORT).show();
            return;
        }
        dialogOpened();
        CheatSearchDialog.show(this, cheatSearch, new CheatSearchDialog.Host() {
            @Override
            public void runWithMemory(Runnable task, Runnable done) {
                onEmulationThread(() -> {
                    task.run();
                    mainHandler.post(done);
                });
            }

            @Override
            public void onCheatMade(Cheat cheat) {
                File file = library.cheatFile(rom);
                List<Cheat> cheats = Cheat.load(file);
                cheats.add(cheat);
                try {
                    Cheat.save(file, cheats);
                } catch (IOException e) {
                    Log.e(TAG, "Could not save cheats", e);
                    Toast.makeText(EmulatorActivity.this, "Could not save the cheat", Toast.LENGTH_LONG).show();
                }
                applyCheats();
            }
        }, this::dialogClosed);
    }

    /** Sends the game's enabled cheats to the core; none in hardcore mode. */
    private void applyCheats() {
        boolean hardcore = achievements.hasAccount() && achievements.isHardcoreEnabled();
        // Linked to another phone, a cheat here would put the two out of step.
        boolean off = hardcore || link instanceof NetLink;
        String codes = off ? "" : Cheat.activeCodes(Cheat.load(library.cheatFile(rom)));
        onEmulationThread(() -> Emulator.nativeSetCheats(codes));
    }

    // ---- Emulation-thread helpers ----

    /** Runs on the emulation thread, or right away if emulation is stopped. */
    private void onEmulationThread(Runnable task) {
        if (thread != null) {
            thread.post(task);
        } else {
            task.run();
        }
    }

    /** Emulation thread (or stopped). */
    private boolean saveState(int slot) {
        byte[] state = Emulator.nativeSaveState();
        if (state == null) return false;
        try {
            RomLibrary.writeAtomically(library.stateFile(rom, slot), state);
        } catch (IOException e) {
            Log.e(TAG, "Could not write save state", e);
            return false;
        }
        if (slot != RomLibrary.AUTO_SLOT) saveThumbnail(slot);
        saveAchievementProgress(slot);
        return true;
    }

    /** Emulation thread (or stopped). */
    private void saveAchievementProgress(int slot) {
        File file = library.achievementProgressFile(rom, slot);
        byte[] progress = achievements.saveProgress();
        try {
            if (progress != null) {
                RomLibrary.writeAtomically(file, progress);
            } else {
                file.delete();
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not save achievement progress", e);
            file.delete();
        }
    }

    /** Emulation thread (or stopped). A missing thumbnail never fails the save itself. */
    private void saveThumbnail(int slot) {
        File file = library.thumbnailFile(rom, slot);
        Frame frame = new Frame();
        frame.width = Emulator.nativeGetFrameWidth();
        frame.height = Emulator.nativeGetFrameHeight();
        try {
            if (!Emulator.nativeCopyFrame(frame.pixels)) throw new IOException("no frame");
            StateThumbnails.write(file, frame);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not save the state's thumbnail", e);
            // Don't leave the previous state's picture next to the new state.
            file.delete();
        }
    }

    /** Emulation thread (or stopped). */
    private boolean loadState(int slot) {
        try {
            if (!Emulator.nativeLoadState(RomLibrary.readFile(library.stateFile(rom, slot)))) return false;
            File progress = library.achievementProgressFile(rom, slot);
            achievements.loadProgress(progress.isFile() ? RomLibrary.readFile(progress) : null);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Could not read save state", e);
            return false;
        }
    }

    private void toastFromAnyThread(String message) {
        mainHandler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    // ---- Menu ----

    @Override
    public void onMenuPressed() {
        showMenu();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (editBar != null) {
            stopEditingControls();
            return;
        }
        if (menuView != null) {
            closeMenu();
            return;
        }
        showMenu();
    }

    /** The full-screen game menu. */
    private void showMenu() {
        if (openDialogs > 0 || isFinishing() || menuView != null) return;
        dialogOpened();
        menuView = new GameMenuView(this, "Game Menu", menuSections(), new GameMenuView.Listener() {
            @Override
            public void onItemChosen(GameMenuView.Item item) {
                // Runs first, so a dialog it opens keeps the game paused as the menu closes.
                item.action.run();
                closeMenu();
            }

            @Override
            public void onClose() {
                closeMenu();
            }
        });
        frame.addView(menuView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        menuView.animateIn();
    }

    private void closeMenu() {
        if (menuView == null) return;
        frame.removeView(menuView);
        menuView = null;
        dialogClosed();
    }

    private List<GameMenuView.Section> menuSections() {
        LinkSession session = link;
        boolean linked = session != null;
        List<GameMenuView.Section> sections = new ArrayList<>();

        GameMenuView.Section quick = new GameMenuView.Section("Quick access");
        if (session instanceof LinkSession.Local) {
            LinkSession.Local local = (LinkSession.Local) session;
            boolean other = local.showingPartner();
            String next = other ? RomLibrary.baseName(rom) : local.partnerName;
            quick.add(Icons.SWAP, "Play " + next, "Switch to the other linked game", () -> {
                local.setControlPartner(!other);
                skinView.releaseAll();
            });
        }
        if (!linked) {
            // States would unlink two Game Boys in time, so they're only for a game on its own.
            quick.add(Icons.SAVE, "Save state", "Save this moment to one of 9 slots", () -> showStateSlots(true));
            quick.add(Icons.LOAD, "Load state", "Go back to a saved moment", () -> showStateSlots(false));
        }
        quick.add(Icons.CHEAT, "Cheats", "Add, turn on or find cheat codes", this::showCheats);
        if (!linked) {
            quick.add(Icons.SEARCH, "Cheat search", "Find where the game keeps lives, money or health",
                    this::showCheatSearch);
        }
        quick.add(Icons.CAMERA, "Screenshot", "Save the screen to your gallery", this::takeScreenshot);
        sections.add(quick);

        GameMenuView.Section input = new GameMenuView.Section("Input");
        input.add(Icons.GAMEPAD, "Controller buttons", "Choose what each button does", () -> {
            dialogOpened();
            ControllerDialog.show(this, controllerMapping, () -> keyTable = controllerMapping.table(),
                    this::dialogClosed);
        });
        input.add(Icons.TOUCH, "Touch controls", "Move and resize the on-screen buttons", this::startEditingControls);
        input.add(Icons.TURBO, "Turbo", "Make A or B fire again and again while held", this::showTurboChoice);
        sections.add(input);

        GameMenuView.Section display = new GameMenuView.Section("Display");
        display.add(Icons.SKIN, "Skin", "Change how the game screen looks", this::showSkinPicker);
        display.add(Icons.SETTINGS, "Settings", "Screen filter, colours, sound, emulation", () -> {
            dialogOpened();
            settings.showDialog(this, this::onSettingChanged, this::dialogClosed);
        });
        display.add(Icons.FULLSCREEN, toolbarsHidden ? "Show toolbars" : "Hide toolbars",
                "The bars above and below the game", () -> setToolbarsHidden(!toolbarsHidden));
        sections.add(display);

        GameMenuView.Section speed = new GameMenuView.Section("Speed");
        boolean fastForward = fastForwardToggled;
        speed.add(Icons.FAST, fastForward ? "Stop fast-forward" : "Fast-forward",
                "Play at " + speedLabel(settings.get(Settings.FAST_FORWARD)), () -> {
                    fastForwardToggled = !fastForward;
                    updateFastForward();
                });
        boolean slow = slowMotion;
        speed.add(Icons.SLOW, slow ? "Normal speed" : "Slow motion",
                "Play at " + settings.get(Settings.SLOW_MOTION) + "% speed", () -> {
                    slowMotion = !slow;
                    updateSpeed();
                });
        sections.add(speed);

        GameMenuView.Section system = new GameMenuView.Section("System");
        if (!linked) system.add(Icons.RESET, "Reset", "Start the game again from its last save", this::confirmReset);
        if (!linked) system.add(Icons.PRINTER, "Link port", "Plug in a Game Boy Printer", this::showLinkPort);
        if (cameraFeed != null) {
            boolean front = cameraFeed.isFront();
            system.add(Icons.CAMERA, front ? "Use the back camera" : "Use the front camera",
                    "What the Game Boy Camera sees", () -> cameraFeed.setFront(!front));
        }
        sections.add(system);

        GameMenuView.Section more = new GameMenuView.Section("More");
        more.add(Icons.TROPHY, "Achievements", "RetroAchievements for this game", this::showAchievements);
        if (linked) {
            more.add(Icons.UNPLUG, "Unplug the link cable", "Carry on playing alone", () -> unplugLink(null));
        } else {
            more.add(Icons.LINK, "Link cable", "Connect two games, or two phones", this::showLinkCable);
        }
        more.add(Icons.EXIT, "Quit to game list", "Your game is saved as you leave", this::finish);
        sections.add(more);
        return sections;
    }

    private static String speedLabel(int speed) {
        return speed == 0 ? "full speed, as fast as it goes" : speed + "× speed";
    }

    // ---- Toolbars ----

    private GameToolbars.Actions toolbarActions() {
        return new GameToolbars.Actions() {
            @Override public void onBack() {
                finish();
            }

            @Override public void onMenu() {
                showMenu();
            }

            @Override public void onAchievements() {
                if (openDialogs == 0) showAchievements();
            }

            @Override public void onLinkCable() {
                if (openDialogs > 0) return;
                if (link != null) {
                    showMenu();
                } else {
                    showLinkCable();
                }
            }

            @Override public void onScreenshot() {
                takeScreenshot();
            }

            @Override public void onRotationLock() {
                setRotationLocked(!rotationLocked);
            }

            @Override public void onSpeed(int speed) {
                slowMotion = speed == GameToolbars.SPEED_SLOW;
                fastForwardToggled = speed == GameToolbars.SPEED_FAST;
                updateSpeed();
                updateFastForward();
            }

            @Override public void onPause() {
                pausedByPlayer = !pausedByPlayer;
                if (thread != null) thread.setPaused(openDialogs > 0 || pausedByPlayer);
                toolbars.setPaused(pausedByPlayer);
            }

            @Override public void onRewind(boolean held) {
                onRewindTouched(held);
            }

            @Override public void onMute() {
                boolean soundOn = !settings.isOn(Settings.SOUND);
                settings.set(Settings.SOUND, soundOn ? 0 : 1); // "On" is the first choice.
                onSettingChanged(Settings.SOUND);
            }

            @Override public void onFullScreen() {
                setToolbarsHidden(true);
            }
        };
    }

    /** Shows the toolbars if they're wanted: always, never, or for skins without their own menu button. */
    private void updateToolbars() {
        int mode = settings.get(Settings.TOOLBARS);
        boolean wanted = mode == 1 || (mode == 0 && !skinView.getSkin().hasMenuButton());
        boolean visible = wanted && !toolbarsHidden;
        toolbars.setVisible(visible);
        toolbars.setMuted(!settings.isOn(Settings.SOUND));
        toolbars.setPaused(pausedByPlayer);
        toolbars.setRotationLocked(rotationLocked);
        showSpeed();
        // Around the notch and system bars: the toolbars' colour, or the skin's.
        frame.setBackgroundColor(visible ? GameToolbars.BAR : skinView.getSkin().backgroundColor());
        // Hidden with the full-screen button: a small way back in the corner.
        boolean showButton = wanted && toolbarsHidden;
        if (showButton && showToolbarsButton == null) {
            android.widget.ImageView button = new android.widget.ImageView(this);
            button.setImageDrawable(Icons.drawable(Icons.FULLSCREEN, GameToolbars.ICON));
            button.setContentDescription("Show toolbars");
            int pad = Math.round(10 * getResources().getDisplayMetrics().density);
            button.setPadding(pad, pad, pad, pad);
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
            background.setColor(0x99000000);
            background.setCornerRadius(pad * 1.2f);
            button.setBackground(background);
            button.setOnClickListener(v -> setToolbarsHidden(false));
            int size = Math.round(44 * getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, android.view.Gravity.TOP | android.view.Gravity.END);
            params.setMargins(pad, pad, pad, pad);
            root.addView(button, params);
            showToolbarsButton = button;
        } else if (!showButton && showToolbarsButton != null) {
            root.removeView(showToolbarsButton);
            showToolbarsButton = null;
        }
    }

    private void setToolbarsHidden(boolean hidden) {
        toolbarsHidden = hidden;
        updateToolbars();
    }

    /** Keeps the screen the way it's turned now, or lets it follow the phone again. */
    private void setRotationLocked(boolean locked) {
        rotationLocked = locked;
        setRequestedOrientation(locked ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
        toolbars.setRotationLocked(locked);
        Toast.makeText(this, locked ? "Rotation locked" : "Rotation unlocked", Toast.LENGTH_SHORT).show();
    }

    // ---- Screenshots ----

    private void takeScreenshot() {
        if (Gallery.needsPermission(this)) {
            requestPermissions(new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
            return;
        }
        String name = Gallery.fileName(RomLibrary.baseName(rom), System.currentTimeMillis());
        onEmulationThread(() -> {
            Frame frame = new Frame();
            frame.width = Emulator.nativeGetFrameWidth();
            frame.height = Emulator.nativeGetFrameHeight();
            if (!Emulator.nativeCopyFrame(frame.pixels)) {
                toastFromAnyThread("Could not take a screenshot");
                return;
            }
            // Encoding and saving can take a moment; don't hold up the game.
            new Thread(() -> {
                try {
                    Gallery.savePng(this, Gallery.scaleUp(StateThumbnails.toBitmap(frame), SCREENSHOT_SCALE), name);
                    toastFromAnyThread("Screenshot saved to Pictures/" + Gallery.FOLDER);
                } catch (IOException | RuntimeException e) {
                    Log.e(TAG, "Could not save screenshot", e);
                    toastFromAnyThread("Could not save the screenshot");
                }
            }, "Screenshot").start();
        });
    }

    // ---- Link cable ----

    private void showLinkCable() {
        dialogOpened();
        LinkDialogs.show(this, new LinkDialogs.Callbacks() {
            @Override
            public List<File> otherGames() {
                List<File> games = library.list();
                games.removeIf(game -> game.getName().equals(rom.getName()));
                return games;
            }

            @Override
            public void linkLocal(File partner) {
                startLocalLink(partner);
            }

            @Override
            public void hostConnected(NetLink.Connection connection) {
                startHostedLink(connection);
            }

            @Override
            public void joinConnected(NetLink.Connection connection) {
                startJoinedLink(connection);
            }
        }, this::dialogClosed);
    }

    /** Two games on this phone: power both on, linked, from their saves. */
    private void startLocalLink(File partner) {
        byte[] partnerRom;
        try {
            partnerRom = RomLibrary.readFile(partner);
        } catch (IOException e) {
            Toast.makeText(this, "Could not read " + RomLibrary.baseName(partner), Toast.LENGTH_LONG).show();
            return;
        }
        File partnerSave = library.batteryFile(partner);
        boolean partnerCgb = partnerRom.length > 0x143 && (partnerRom[0x143] & 0x80) != 0;
        int model = settings.get(partnerCgb ? Settings.CGB_MODEL : Settings.DMG_MODEL);
        LinkSession.Local session = new LinkSession.Local(partnerSave, RomLibrary.baseName(partner));
        onEmulationThread(() -> {
            flushBattery();
            if (!Emulator.nativeLink(partnerRom, model, false, System.nanoTime())) {
                toastFromAnyThread("Could not start " + session.partnerName);
                return;
            }
            if (partnerSave.isFile()) {
                try {
                    Emulator.nativeLoadPartnerBattery(RomLibrary.readFile(partnerSave));
                } catch (IOException e) {
                    Log.w(TAG, "Could not read " + partnerSave, e);
                }
            }
            linkStarted(session, "Linked with " + session.partnerName + ". Switch games from the menu.");
        });
    }

    /** Hosting: power both games on, linked, and send the other phone everything it needs to run the same. */
    private void startHostedLink(NetLink.Connection connection) {
        Toast.makeText(this, "Connected. Setting up the link…", Toast.LENGTH_SHORT).show();
        byte[] myRom;
        try {
            myRom = RomLibrary.readFile(rom);
        } catch (IOException e) {
            connection.close();
            return;
        }
        new Thread(() -> {
            NetLink.Game guest;
            try {
                guest = NetLink.hostReceive(connection);
            } catch (IOException e) {
                connection.close();
                toastFromAnyThread("The link failed: " + e.getMessage());
                return;
            }
            onEmulationThread(() -> {
                flushBattery();
                if (!Emulator.nativeLink(guest.rom, guest.model, false, System.nanoTime())) {
                    connection.close();
                    toastFromAnyThread("Could not start " + guest.name);
                    return;
                }
                if (guest.data.length > 0) Emulator.nativeLoadPartnerBattery(guest.data);
                byte[] myState = Emulator.nativeSaveState();
                byte[] guestState = Emulator.nativeSavePartnerState();
                NetLink.Game mine = new NetLink.Game(RomLibrary.baseName(rom), Emulator.nativeGetModel(), myRom, myState);
                try {
                    NetLink session = NetLink.hostSend(connection, guest, mine, guestState);
                    linkStarted(session, "Linked with " + guest.name);
                } catch (IOException e) {
                    connection.close();
                    Emulator.nativeUnlink();
                    toastFromAnyThread("The link failed: " + e.getMessage());
                }
            });
        }, "Link setup").start();
    }

    /** Joining: send this game, then run both exactly as the hosting phone set them up. */
    private void startJoinedLink(NetLink.Connection connection) {
        Toast.makeText(this, "Connected. Setting up the link…", Toast.LENGTH_SHORT).show();
        byte[] myRom;
        try {
            myRom = RomLibrary.readFile(rom);
        } catch (IOException e) {
            connection.close();
            return;
        }
        onEmulationThread(() -> {
            flushBattery();
            byte[] save = Emulator.nativeSaveBattery();
            NetLink.Game mine = new NetLink.Game(RomLibrary.baseName(rom), Emulator.nativeGetModel(), myRom,
                    save != null ? save : new byte[0]);
            new Thread(() -> {
                NetLink.JoinResult result;
                try {
                    result = NetLink.join(connection, mine);
                } catch (IOException e) {
                    connection.close();
                    toastFromAnyThread("The link failed: " + e.getMessage());
                    return;
                }
                onEmulationThread(() -> {
                    boolean ok = Emulator.nativeLink(result.host.rom, result.host.model, true, 0)
                            && Emulator.nativeLoadState(result.guestState)
                            && Emulator.nativeLoadPartnerState(result.host.data);
                    if (!ok) {
                        result.link.close();
                        Emulator.nativeUnlink();
                        toastFromAnyThread("The link failed: the games didn't start the same on both phones");
                        return;
                    }
                    linkStarted(result.link, "Linked with " + result.host.name);
                });
            }, "Link setup").start();
        });
    }

    /**
     * Emulation thread (or stopped): the Game Boys are linked; start playing. The session takes
     * over the keys from the very next frame, so both phones count frames from the same moment.
     */
    private void linkStarted(LinkSession session, String message) {
        if (session instanceof NetLink) ((NetLink) session).start();
        link = session;
        Thread current = Thread.currentThread();
        if (current instanceof EmulatorThread) ((EmulatorThread) current).setLink(session);
        // Otherwise emulation is stopped, and onResume hands the session to the new thread.
        achievements.onGameReset(); // Both Game Boys were powered on afresh.
        mainHandler.post(() -> {
            applyCheats();
            updateRewind();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    /** Unplugs the cable; the game carries on alone. {@code reason} is shown if given. */
    private void unplugLink(String reason) {
        LinkSession session = link;
        if (session == null) return;
        link = null;
        if (thread != null) thread.setLink(null);
        onEmulationThread(() -> {
            session.close();
            Emulator.nativeUnlink();
        });
        onLinkWaiting(false);
        applyCheats();
        Toast.makeText(this, reason != null ? reason : "Link cable unplugged", Toast.LENGTH_LONG).show();
    }

    /** Emulation thread (or stopped): writes the game's save now, before it's powered off and on. */
    private void flushBattery() {
        if (Emulator.nativeTakeBatteryDirty()) onBatteryDirty();
    }

    @Override
    public void onLinkEnded(String reason) {
        // Emulation thread: the other phone went away. Carry on alone.
        LinkSession session = link;
        if (session != null) session.close();
        Emulator.nativeUnlink();
        mainHandler.post(() -> {
            link = null;
            onLinkWaiting(false);
            applyCheats();
            Toast.makeText(this, reason != null ? reason : "Link cable unplugged", Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onLinkWaiting(boolean waiting) {
        mainHandler.post(() -> {
            if (waiting && linkWaitingView == null) {
                linkWaitingView = new android.widget.TextView(this);
                linkWaitingView.setText("Waiting for the other player…");
                linkWaitingView.setTextColor(0xFFFFFFFF);
                linkWaitingView.setBackgroundColor(0xCC000000);
                int pad = Math.round(12 * getResources().getDisplayMetrics().density);
                linkWaitingView.setPadding(pad, pad / 2, pad, pad / 2);
                root.addView(linkWaitingView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));
            } else if (!waiting && linkWaitingView != null) {
                root.removeView(linkWaitingView);
                linkWaitingView = null;
            }
        });
    }

    // ---- Link port: Game Boy Printer ----

    private void showLinkPort() {
        String[] choices = {"Nothing", "Game Boy Printer"};
        int[] values = {Emulator.LINK_NOTHING, Emulator.LINK_PRINTER};
        int current = gameStore.linkAccessory(rom) == Emulator.LINK_PRINTER ? 1 : 0;
        showDialog(new AlertDialog.Builder(this)
                .setTitle("Link port")
                .setSingleChoiceItems(choices, current, (d, which) -> {
                    int accessory = values[which];
                    gameStore.setLinkAccessory(rom, accessory);
                    onEmulationThread(() -> Emulator.nativeSetLinkAccessory(accessory));
                    if (accessory == Emulator.LINK_PRINTER) {
                        Toast.makeText(this, "Printouts are saved to Pictures/" + Gallery.FOLDER, Toast.LENGTH_LONG).show();
                    }
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    @Override
    public void onPrintout(int[] pixels) {
        mainHandler.post(() -> {
            if (Gallery.needsPermission(this)) {
                pendingPrintouts.add(pixels);
                requestPermissions(new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
                return;
            }
            savePrintout(pixels);
        });
    }

    private void savePrintout(int[] pixels) {
        String name = Gallery.fileName(RomLibrary.baseName(rom) + " printout", System.currentTimeMillis());
        new Thread(() -> {
            try {
                Gallery.savePng(this, Gallery.scaleUp(printoutBitmap(pixels), SCREENSHOT_SCALE), name);
                toastFromAnyThread("Printed! Saved to Pictures/" + Gallery.FOLDER);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Could not save printout", e);
                toastFromAnyThread("Could not save the printout");
            }
        }, "Printout").start();
    }

    /** A printout's pixels (0xAABBGGRR, 160 wide) as a bitmap. */
    static android.graphics.Bitmap printoutBitmap(int[] pixels) {
        int width = 160;
        int height = Math.max(1, pixels.length / width);
        int[] colors = new int[width * height];
        for (int i = 0; i < colors.length && i < pixels.length; i++) {
            int p = pixels[i];
            colors[i] = 0xFF000000 | ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF);
        }
        return android.graphics.Bitmap.createBitmap(colors, width, height, android.graphics.Bitmap.Config.ARGB_8888);
    }

    // ---- Game Boy Camera ----

    /** For a Game Boy Camera cartridge, shows it what the phone's camera sees (asking permission once). */
    private void startCameraIfNeeded() {
        if (!Emulator.nativeHasCamera()) return;
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!cameraPermissionAsked) {
                cameraPermissionAsked = true;
                requestPermissions(new String[] {android.Manifest.permission.CAMERA}, REQUEST_CAMERA);
            }
            return;
        }
        if (cameraFeed == null) {
            cameraFeed = new CameraFeed(this, pixels -> {
                EmulatorThread running = thread;
                if (running != null) running.post(() -> Emulator.nativeSetCameraImage(pixels));
            });
        }
        cameraFeed.start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQUEST_CAMERA) {
            if (granted) {
                startCameraIfNeeded();
            } else {
                Toast.makeText(this, "Without the camera, the Game Boy Camera only sees static", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode != REQUEST_STORAGE) return;
        if (!pendingPrintouts.isEmpty()) {
            if (granted) {
                for (int[] printout : pendingPrintouts) savePrintout(printout);
            } else {
                Toast.makeText(this, "Saving printouts needs access to storage", Toast.LENGTH_LONG).show();
            }
            pendingPrintouts.clear();
            return;
        }
        if (granted) {
            takeScreenshot();
        } else {
            Toast.makeText(this, "Saving pictures needs access to storage", Toast.LENGTH_LONG).show();
        }
    }

    private void showSkinPicker() {
        dialogOpened();
        SkinPicker.show(this, skins, REQUEST_IMPORT_SKIN, new SkinPicker.Callbacks() {
            @Override
            public void onSkinChanged() {
                applySkin();
            }

            @Override
            public void onDismissed() {
                dialogClosed();
            }
        });
    }

    private void applySkin() {
        Skin skin = skins.loadActive();
        root.setBackgroundColor(skin.backgroundColor());
        skinView.setSkin(skin);
        updateToolbars();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_SKIN && resultCode == RESULT_OK && data != null && data.getData() != null) {
            SkinPicker.importSkin(this, skins, data.getData(), this::applySkin);
        }
    }

    private void showStateSlots(boolean save) {
        if (!save && achievements.hardcoreActive()) {
            Toast.makeText(this, "Loading states is off in hardcore mode", Toast.LENGTH_SHORT).show();
            return;
        }
        showDialog(new AlertDialog.Builder(this)
                .setTitle(save ? "Save state" : "Load state")
                .setAdapter(new SlotAdapter(this, library, rom), (d, which) -> {
                    int slot = which + 1;
                    if (!save && !library.stateFile(rom, slot).isFile()) {
                        Toast.makeText(this, "Slot " + slot + " is empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    onEmulationThread(() -> {
                        if (save) {
                            toastFromAnyThread(saveState(slot) ? "Saved to slot " + slot : "Could not save the state");
                        } else {
                            toastFromAnyThread(loadState(slot) ? "Loaded slot " + slot : "Could not load the state");
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .create());
    }

    private void confirmReset() {
        showDialog(new AlertDialog.Builder(this)
                .setTitle("Reset the game?")
                .setMessage("Progress since your last in-game save will be lost.")
                .setPositiveButton("Reset", (d, which) -> {
                    int model = preferredModel();
                    onEmulationThread(() -> {
                        if (model != Emulator.nativeGetModel()) {
                            Emulator.nativeSwitchModel(model);
                        } else {
                            Emulator.nativeReset();
                        }
                        achievements.onGameReset();
                    });
                })
                .setNegativeButton("Cancel", null)
                .create());
    }

    private void showDialog(AlertDialog dialog) {
        dialogOpened();
        dialog.setOnDismissListener(d -> dialogClosed());
        dialog.show();
    }

    // Emulation pauses while any dialog is open. A dialog opened from another's click handler
    // is counted before the first one is dismissed, so there's no gap in between.
    private void dialogOpened() {
        openDialogs++;
        if (thread != null) thread.setPaused(true);
    }

    private void dialogClosed() {
        openDialogs = Math.max(0, openDialogs - 1);
        if (openDialogs == 0) {
            if (thread != null) thread.setPaused(pausedByPlayer);
            hideSystemBars();
        }
    }

    private void onSettingChanged(Settings.Choice choice) {
        if (choice == Settings.DMG_MODEL || choice == Settings.CGB_MODEL) {
            if (preferredModel() != Emulator.nativeGetModel()) {
                Toast.makeText(this, "The new model is used after you reset the game", Toast.LENGTH_LONG).show();
            }
        } else if (choice == Settings.COLOR_CORRECTION || choice == Settings.DMG_PALETTE
                || choice == Settings.BORDER || choice == Settings.HIGHPASS || choice == Settings.RUMBLE
                || choice == Settings.REWIND) {
            onEmulationThread(settings::applyToCore);
        } else if (choice == Settings.SOUND) {
            if (thread != null) thread.setMuted(!settings.isOn(Settings.SOUND));
            updateToolbars();
        } else if (choice == Settings.TOOLBARS) {
            updateToolbars();
        } else if (choice == Settings.FAST_FORWARD) {
            if (thread != null) thread.setFastForwardSpeed(settings.get(Settings.FAST_FORWARD));
        } else if (choice == Settings.TURBO_SPEED) {
            if (thread != null) thread.setTurboPeriod(settings.get(Settings.TURBO_SPEED));
        } else if (choice == Settings.SLOW_MOTION) {
            updateSpeed();
        } else if (choice == Settings.FILTER || choice == Settings.FRAME_BLENDING) {
            applyScreenSettings();
        } else {
            applyUiSettings();
        }
    }

    private void applyUiSettings() {
        skinView.setIntegerScaling(settings.get(Settings.SCALING) == 1);
        skinView.setHaptics(settings.isOn(Settings.HAPTICS));
        skinView.setAnimations(settings.isOn(Settings.ANIMATIONS));
        applyScreenSettings();
        updateControlsVisibility();
        updateToolbars();
    }

    private void applyScreenSettings() {
        screen.setFilter(settings.get(Settings.FILTER));
        screen.setFrameBlending(settings.get(Settings.FRAME_BLENDING));
    }

    private boolean supportsGles3() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        return manager != null && manager.getDeviceConfigurationInfo().reqGlEsVersion >= 0x30000;
    }

    // ---- SkinView.Listener ----

    @Override
    public void onScreenRectChanged(Rect rect) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(rect.width(), rect.height());
        params.leftMargin = rect.left;
        params.topMargin = rect.top;
        // Often called while the skin is being laid out; move the screen on the next pass.
        mainHandler.post(() -> screen.view().setLayoutParams(params));
    }

    @Override
    public void onShowControlsRequested() {
        controlsHiddenByGamepad = false;
        updateControlsVisibility();
    }

    @Override
    public void onRewindTouched(boolean held) {
        rewindTouched = held;
        updateRewind();
    }

    @Override
    public void onFastForwardTouched(boolean held) {
        fastForwardTouched = held;
        updateFastForward();
    }

    // ---- Input ----

    @Override
    public void onTouchKeysChanged(int mask) {
        touchKeys = mask;
        pushKeys();
    }

    private void pushKeys() {
        if (thread == null) return;
        thread.setKeys(touchKeys | hardwareKeys | axisKeys | hardwareTurboKeys);
        thread.setTurboKeys(hardwareTurboKeys | (touchKeys & touchTurboKeys));
    }

    private void updateSpeed() {
        if (thread != null) thread.setSpeedPercent(slowMotion ? settings.get(Settings.SLOW_MOTION) : 100);
        showSpeed();
    }

    /** The toolbar's speed buttons follow slow motion and fast-forward, however they were chosen. */
    private void showSpeed() {
        if (toolbars == null) return;
        toolbars.setSpeed(fastForwardToggled ? GameToolbars.SPEED_FAST
                : slowMotion ? GameToolbars.SPEED_SLOW : GameToolbars.SPEED_NORMAL);
    }

    /** Which on-screen buttons fire repeatedly while held. Controllers have turbo on Y (A) and X (B). */
    private void showTurboChoice() {
        boolean[] checked = {(touchTurboKeys & Emulator.KEY_A) != 0, (touchTurboKeys & Emulator.KEY_B) != 0};
        showDialog(new AlertDialog.Builder(this)
                .setTitle("Turbo on-screen buttons")
                .setMultiChoiceItems(new String[] {"A", "B"}, checked, (d, which, isChecked) -> {
                    int key = which == 0 ? Emulator.KEY_A : Emulator.KEY_B;
                    touchTurboKeys = isChecked ? touchTurboKeys | key : touchTurboKeys & ~key;
                    pushKeys();
                })
                .setPositiveButton("Done", null)
                .create());
    }

    // ---- Moving the on-screen buttons ----

    private void startEditingControls() {
        if (!skinView.getSkin().movableControls()) {
            Toast.makeText(this, "This skin's buttons are part of its picture. Pick a built-in skin to move them.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        dialogOpened(); // Paused while editing.
        skinView.setControlsVisible(true);
        skinView.setEditing(true);

        android.widget.LinearLayout bar = new android.widget.LinearLayout(this);
        bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xDD000000);
        int pad = Math.round(8 * getResources().getDisplayMetrics().density);
        bar.setPadding(pad * 2, pad, pad, pad);
        android.widget.TextView hint = new android.widget.TextView(this);
        hint.setText("Drag a button to move it, pinch to resize");
        hint.setTextColor(0xFFFFFFFF);
        bar.addView(hint, new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        android.widget.Button reset = new android.widget.Button(this);
        reset.setText("Reset");
        reset.setOnClickListener(v -> skinView.resetControlLayout());
        android.widget.Button done = new android.widget.Button(this);
        done.setText("Done");
        done.setOnClickListener(v -> stopEditingControls());
        bar.addView(reset);
        bar.addView(done);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP);
        root.addView(bar, params);
        editBar = bar;
    }

    private void stopEditingControls() {
        if (editBar == null) return;
        root.removeView(editBar);
        editBar = null;
        skinView.setEditing(false);
        updateControlsVisibility();
        dialogClosed();
    }

    private void updateRewind() {
        boolean rewind = rewindHeld || rewindTouched;
        if (rewind && settings.get(Settings.REWIND) == 0) {
            Toast.makeText(this, "Rewind is off. Turn it on in Settings → Emulation.", Toast.LENGTH_SHORT).show();
            rewind = false;
        }
        if (rewind && link != null) {
            Toast.makeText(this, "Rewind is off while the link cable is plugged in", Toast.LENGTH_SHORT).show();
            rewind = false;
        }
        if (rewind && achievements.hardcoreActive()) {
            Toast.makeText(this, "Rewind is off in hardcore mode", Toast.LENGTH_SHORT).show();
            rewind = false;
        }
        if (thread != null) thread.setRewinding(rewind);
    }

    private void updateFastForward() {
        if (thread != null) thread.setFastForward(fastForwardToggled || fastForwardHeld || fastForwardTouched);
        showSpeed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (openDialogs > 0) return super.dispatchKeyEvent(event);
        ControllerMapping.Action action = keyTable.get(event.getKeyCode());
        if (action == null) return super.dispatchKeyEvent(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        switch (action) {
            case FAST_FORWARD:
                fastForwardHeld = down;
                updateFastForward();
                break;
            case REWIND:
                // Key repeats would re-show the "rewind is off" message; only act on changes.
                if (event.getRepeatCount() == 0 && rewindHeld != down) {
                    rewindHeld = down;
                    updateRewind();
                }
                break;
            case MENU:
                if (up) showMenu();
                return true;
            default:
                if (action.isTurbo()) {
                    if (down) hardwareTurboKeys |= action.gameKey;
                    else if (up) hardwareTurboKeys &= ~action.gameKey;
                } else {
                    if (down) hardwareKeys |= action.gameKey;
                    else if (up) hardwareKeys &= ~action.gameKey;
                }
                pushKeys();
                break;
        }
        onHardwareInput();
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        boolean joystick = (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (!joystick || event.getActionMasked() != MotionEvent.ACTION_MOVE || openDialogs > 0) {
            return super.dispatchGenericMotionEvent(event);
        }
        float x = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(x) < 0.5f && Math.abs(y) < 0.5f) {
            x = event.getAxisValue(MotionEvent.AXIS_X);
            y = event.getAxisValue(MotionEvent.AXIS_Y);
        }
        int keys = 0;
        if (x < -0.5f) keys |= Emulator.KEY_LEFT;
        if (x > 0.5f) keys |= Emulator.KEY_RIGHT;
        if (y < -0.5f) keys |= Emulator.KEY_UP;
        if (y > 0.5f) keys |= Emulator.KEY_DOWN;

        float trigger = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS));
        boolean held = trigger > 0.5f;
        if (held != fastForwardHeld && (held || trigger < 0.2f)) {
            fastForwardHeld = held;
            updateFastForward();
        }
        float leftTrigger = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE));
        boolean rewind = leftTrigger > 0.5f;
        if (rewind != rewindHeld && (rewind || leftTrigger < 0.2f)) {
            rewindHeld = rewind;
            updateRewind();
        }
        if (keys != axisKeys) {
            axisKeys = keys;
            pushKeys();
            onHardwareInput();
        }
        return true;
    }

    private void onHardwareInput() {
        if (!controlsHiddenByGamepad && settings.get(Settings.CONTROLS) == 0) {
            controlsHiddenByGamepad = true;
            updateControlsVisibility();
        }
    }

    private void updateControlsVisibility() {
        int mode = settings.get(Settings.CONTROLS);
        boolean visible = mode == 1 || (mode == 0 && !controlsHiddenByGamepad);
        skinView.setControlsVisible(visible);
    }

    // ---- Window ----

    @SuppressWarnings("deprecation") // The pre-API 30 insets getters
    private WindowInsets applyInsets(View view, WindowInsets insets) {
        int left, top, right, bottom;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            left = safe.left;
            top = safe.top;
            right = safe.right;
            bottom = safe.bottom;
        } else {
            left = insets.getSystemWindowInsetLeft();
            top = insets.getSystemWindowInsetTop();
            right = insets.getSystemWindowInsetRight();
            bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && insets.getDisplayCutout() != null) {
                left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
                top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
                bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
            }
        }
        view.setPadding(left, top, right, bottom);
        return insets;
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
