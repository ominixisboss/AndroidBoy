package com.ominixisboss.androidboy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
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
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;

/** Runs a game: screen, on-screen controls, hardware input, save states and the in-game menu. */
public final class EmulatorActivity extends Activity implements EmulatorThread.Host, GamepadView.Listener {
    static final String EXTRA_ROM = "rom";
    private static final String TAG = "AndroidBoy";

    // The core is a process-wide singleton; remember which ROM it holds.
    private static String loadedRomPath;
    private static int loadedSampleRate;
    private static boolean loadedRomIsCgb;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Settings settings;
    private RomLibrary library;
    private File rom;

    private ScreenView screen;
    private GamepadView gamepad;
    private EmulatorThread thread;
    private Vibrator vibrator;

    private int touchKeys;
    private int hardwareKeys;
    private int axisKeys;
    private boolean fastForwardHeld;
    private boolean fastForwardToggled;
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

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        screen = new ScreenView(this);
        gamepad = new GamepadView(this, screen, this);
        root.addView(screen, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(gamepad, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener(this::applyInsets);
        // Only reachable while the controls are hidden: touching the screen brings them back.
        screen.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && controlsHiddenByGamepad) {
                controlsHiddenByGamepad = false;
                updateControlsVisibility();
            }
            return true;
        });
        setContentView(root);

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
        thread = new EmulatorThread(this, loadedSampleRate);
        thread.setFastForwardSpeed(settings.get(Settings.FAST_FORWARD));
        thread.setMuted(!settings.isOn(Settings.SOUND));
        thread.setPaused(openDialogs > 0);
        updateFastForward();
        pushKeys();
        thread.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (thread == null) return;
        thread.shutdown();
        thread = null;
        if (settings.isOn(Settings.AUTO_SAVE)) {
            saveState(RomLibrary.AUTO_SLOT);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing() && rom != null && rom.getPath().equals(loadedRomPath)) {
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

        File battery = library.batteryFile(rom);
        if (battery.isFile()) {
            try {
                Emulator.nativeLoadBattery(RomLibrary.readFile(battery));
            } catch (IOException e) {
                Log.w(TAG, "Could not read battery save", e);
            }
        }

        File autoState = library.stateFile(rom, RomLibrary.AUTO_SLOT);
        if (settings.isOn(Settings.AUTO_SAVE) && autoState.isFile()) {
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
            Emulator.nativeUnload();
            loadedRomPath = null;
        }
    }

    private int preferredModel() {
        return settings.get(loadedRomIsCgb ? Settings.CGB_MODEL : Settings.DMG_MODEL);
    }

    // ---- EmulatorThread.Host (called on the emulation thread) ----

    @Override
    public void onFrame(Bitmap frame) {
        screen.setFrame(frame);
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
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Could not write save state", e);
            return false;
        }
    }

    /** Emulation thread (or stopped). */
    private boolean loadState(int slot) {
        try {
            return Emulator.nativeLoadState(RomLibrary.readFile(library.stateFile(rom, slot)));
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
        showMenu();
    }

    private void showMenu() {
        if (openDialogs > 0 || isFinishing()) return;
        boolean fastForward = fastForwardToggled;
        String[] items = {
                "Resume",
                "Save state…",
                "Load state…",
                fastForward ? "Stop fast-forward" : "Fast-forward",
                "Reset",
                "Settings",
                "Quit to game list",
        };
        showDialog(new AlertDialog.Builder(this)
                .setTitle(RomLibrary.baseName(rom))
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 1: showStateSlots(true); break;
                        case 2: showStateSlots(false); break;
                        case 3:
                            fastForwardToggled = !fastForward;
                            updateFastForward();
                            break;
                        case 4: confirmReset(); break;
                        case 5:
                            dialogOpened();
                            settings.showDialog(this, this::onSettingChanged, this::dialogClosed);
                            break;
                        case 6: finish(); break;
                        default: break;
                    }
                })
                .create());
    }

    private void showStateSlots(boolean save) {
        String[] labels = new String[RomLibrary.STATE_SLOTS];
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (int slot = 1; slot <= RomLibrary.STATE_SLOTS; slot++) {
            File file = library.stateFile(rom, slot);
            labels[slot - 1] = "Slot " + slot + " — "
                    + (file.isFile() ? format.format(new Date(file.lastModified())) : "empty");
        }
        showDialog(new AlertDialog.Builder(this)
                .setTitle(save ? "Save state" : "Load state")
                .setItems(labels, (d, which) -> {
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
            if (thread != null) thread.setPaused(false);
            hideSystemBars();
        }
    }

    private void onSettingChanged(Settings.Choice choice) {
        if (choice == Settings.DMG_MODEL || choice == Settings.CGB_MODEL) {
            if (preferredModel() != Emulator.nativeGetModel()) {
                Toast.makeText(this, "The new model is used after you reset the game", Toast.LENGTH_LONG).show();
            }
        } else if (choice == Settings.COLOR_CORRECTION || choice == Settings.DMG_PALETTE
                || choice == Settings.BORDER || choice == Settings.HIGHPASS || choice == Settings.RUMBLE) {
            onEmulationThread(settings::applyToCore);
        } else if (choice == Settings.SOUND) {
            if (thread != null) thread.setMuted(!settings.isOn(Settings.SOUND));
        } else if (choice == Settings.FAST_FORWARD) {
            if (thread != null) thread.setFastForwardSpeed(settings.get(Settings.FAST_FORWARD));
        } else {
            applyUiSettings();
        }
    }

    private void applyUiSettings() {
        screen.setIntegerScaling(settings.get(Settings.SCALING) == 1);
        gamepad.setHaptics(settings.isOn(Settings.HAPTICS));
        updateControlsVisibility();
    }

    // ---- Input ----

    @Override
    public void onTouchKeysChanged(int mask) {
        touchKeys = mask;
        pushKeys();
    }

    private void pushKeys() {
        if (thread != null) thread.setKeys(touchKeys | hardwareKeys | axisKeys);
    }

    private void updateFastForward() {
        if (thread != null) thread.setFastForward(fastForwardToggled || fastForwardHeld);
    }

    private static int mapKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_W: return Emulator.KEY_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_S: return Emulator.KEY_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_A: return Emulator.KEY_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_D: return Emulator.KEY_RIGHT;
            // Positional mapping: the right face button is A and the bottom one is B, as on a Game Boy.
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_X: case KeyEvent.KEYCODE_L:
                return Emulator.KEY_A;
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_Z: case KeyEvent.KEYCODE_K:
                return Emulator.KEY_B;
            case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_ENTER: return Emulator.KEY_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_SHIFT_RIGHT: case KeyEvent.KEYCODE_DEL:
                return Emulator.KEY_SELECT;
            default: return 0;
        }
    }

    private static boolean isFastForwardKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_R1 || keyCode == KeyEvent.KEYCODE_BUTTON_R2
                || keyCode == KeyEvent.KEYCODE_SPACE;
    }

    private static boolean isMenuKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_MODE || keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_ESCAPE;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (openDialogs > 0) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = event.getAction() == KeyEvent.ACTION_UP;

        int key = mapKey(code);
        if (key != 0) {
            if (down) hardwareKeys |= key;
            else if (up) hardwareKeys &= ~key;
            pushKeys();
            onHardwareInput();
            return true;
        }
        if (isFastForwardKey(code)) {
            fastForwardHeld = down;
            updateFastForward();
            onHardwareInput();
            return true;
        }
        if (isMenuKey(code)) {
            if (up) showMenu();
            return true;
        }
        return super.dispatchKeyEvent(event);
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
        if (!visible) {
            gamepad.releaseAll();
        }
        gamepad.setVisibility(visible ? View.VISIBLE : View.GONE);
        screen.setReserveControlsSpace(visible);
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
