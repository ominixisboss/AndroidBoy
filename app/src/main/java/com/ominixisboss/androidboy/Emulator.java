package com.ominixisboss.androidboy;

import android.content.res.AssetManager;

/**
 * JNI bindings for the SameBoy core (see app/src/main/cpp/jni_bridge.c).
 * The core is a single global instance; every method except {@link #init} must be called
 * from the emulation thread, or while that thread is stopped.
 */
final class Emulator {
    static {
        System.loadLibrary("androidboy");
    }

    private Emulator() {}

    /** Loads the native library; for other classes with native methods in it (Achievements). */
    static void loadLibrary() {
        // The static initializer above does the work.
    }

    // GB_model_t values from sameboy/Core/model.h
    static final int MODEL_DMG_B = 0x002;
    static final int MODEL_SGB = 0x004;
    static final int MODEL_MGB = 0x100;
    static final int MODEL_SGB2 = 0x101;
    static final int MODEL_CGB_E = 0x205;
    static final int MODEL_AGB_A = 0x207;

    // Key bits, in GB_key_t order from sameboy/Core/joypad.h
    static final int KEY_RIGHT = 1;
    static final int KEY_LEFT = 1 << 1;
    static final int KEY_UP = 1 << 2;
    static final int KEY_DOWN = 1 << 3;
    static final int KEY_A = 1 << 4;
    static final int KEY_B = 1 << 5;
    static final int KEY_SELECT = 1 << 6;
    static final int KEY_START = 1 << 7;

    private static boolean initialized;

    static synchronized void init(AssetManager assets) {
        if (initialized) return;
        nativeInit(assets);
        initialized = true;
    }

    static native void nativeInit(AssetManager assets);
    static native int nativePickModel(byte[] rom, int dmgModel, int cgbModel);
    static native boolean nativeLoadRom(byte[] rom, int model, int sampleRate);
    static native void nativeUnload();
    static native void nativeReset();
    static native void nativeSwitchModel(int model);
    static native int nativeGetModel();
    static native void nativeSetKeys(int mask);
    /** Runs one frame and moves the audio it produced into {@code audio}; returns the number of shorts written. */
    static native int nativeRunFrame(short[] audio);
    /** Steps one frame back in the rewind history (discarding its audio); false at the oldest frame. */
    static native boolean nativeRewindFrame(short[] audio);
    static native void nativeSetRewindLength(int seconds);
    static native int nativeGetFrameWidth();
    static native int nativeGetFrameHeight();
    /** Copies the latest frame, tightly packed RGBA, into a direct buffer of at least 256×224 pixels. */
    static native boolean nativeCopyFrame(java.nio.ByteBuffer buffer);
    static native boolean nativeIsOddFrame();
    static native byte[] nativeSaveBattery();
    static native void nativeLoadBattery(byte[] data);
    static native boolean nativeTakeBatteryDirty();
    static native byte[] nativeSaveState();
    static native boolean nativeLoadState(byte[] data);
    static native void nativeSetColorCorrection(int mode);
    static native void nativeSetDmgPalette(int index);
    static native void nativeSetBorderMode(int mode);
    static native void nativeSetHighpass(int mode);
    static native void nativeSetRumbleMode(int mode);
    /**
     * Reads memory without side effects, in RetroAchievements' address map ($0000-$FFFF is what the
     * CPU sees). Returns how many bytes were read.
     */
    static native int nativeReadMemory(int address, byte[] buffer);
    /** Replaces the cheats with these codes, one per line; returns how many were valid. Cleared by loading a ROM. */
    static native int nativeSetCheats(String codes);
    static native double nativeGetRumble();
    static native String nativeGetTitle();
}
