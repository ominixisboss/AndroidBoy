package com.ominixisboss.androidboy;

import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * A link cable in use: decides, each frame, which keys each of the two linked Game Boys gets.
 * Runs on the emulation thread.
 */
abstract class LinkSession {
    /** Result of {@link #applyKeys}. */
    static final int READY = 0;
    /** The other player hasn't sent this frame's keys yet: try again shortly, without running a frame. */
    static final int WAITING = 1;
    /** The link is over (see {@link #endReason}). */
    static final int ENDED = 2;

    /** Sets both Game Boys' keys for the next frame, given this player's keys. */
    abstract int applyKeys(int localKeys);

    /** Called every few seconds and when the link ends, e.g. to save the other game. */
    void periodic() {
    }

    /** Why the link ended, for the player; null while it's up. */
    String endReason() {
        return null;
    }

    /** Ends the link. Called on the emulation thread (or while it's stopped). */
    void close() {
    }

    /** Whether the keys and screen are the other game's (only for two games on one phone). */
    boolean showingPartner() {
        return false;
    }

    /**
     * Two games on this phone, both played here: the player controls and watches one at a time
     * and switches between them, e.g. to trade between two save files.
     */
    static final class Local extends LinkSession {
        private static final String TAG = "AndroidBoy";
        private final File partnerSave;
        final String partnerName;
        private volatile boolean controlPartner;

        Local(File partnerSave, String partnerName) {
            this.partnerSave = partnerSave;
            this.partnerName = partnerName;
        }

        /** Switches which game the keys go to and which is shown. */
        void setControlPartner(boolean partner) {
            controlPartner = partner;
        }

        @Override
        boolean showingPartner() {
            return controlPartner;
        }

        @Override
        int applyKeys(int localKeys) {
            boolean partner = controlPartner;
            Emulator.nativeShowPartner(partner);
            Emulator.nativeSetKeys(partner ? 0 : localKeys);
            Emulator.nativeSetPartnerKeys(partner ? localKeys : 0);
            return READY;
        }

        @Override
        void periodic() {
            if (!Emulator.nativeTakePartnerBatteryDirty()) return;
            byte[] data = Emulator.nativeSavePartnerBattery();
            if (data == null) return;
            try {
                RomLibrary.writeAtomically(partnerSave, data);
            } catch (IOException e) {
                Log.e(TAG, "Could not save " + partnerName, e);
            }
        }

        @Override
        void close() {
            periodic();
        }
    }
}
