package com.ominixisboss.androidboy;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;
import android.util.Log;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Runs the core. Emulation speed is paced by blocking audio writes, so audio never
 * underruns; while fast-forwarding, audio is skipped and pacing falls back to the clock.
 */
final class EmulatorThread extends Thread {
    interface Host {
        void onFrame(Frame frame);
        void onRumble(double amplitude);
        /** Called every few seconds and when stopping, only if the game wrote to its save RAM. */
        void onBatteryDirty();
    }

    private static final String TAG = "AndroidBoy";
    private static final double FRAME_RATE = 4194304.0 / 70224.0; // ~59.73 Hz
    private static final int BATTERY_CHECK_FRAMES = 180;

    static int outputSampleRate() {
        int rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
        return rate > 0 ? rate : 48000;
    }

    private final Host host;
    private final int sampleRate;
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final Object pauseLock = new Object();
    // Three frames so the one being written is never the one the renderer may still be reading.
    private final Frame[] frames = {new Frame(), new Frame(), new Frame()};
    private int frameIndex;

    private volatile boolean running = true;
    private volatile boolean paused;
    private volatile int keys;
    private volatile boolean fastForward;
    private volatile int fastForwardSpeed = 4;
    private volatile boolean muted;
    private double lastRumble;

    EmulatorThread(Host host, int sampleRate) {
        super("Emulation");
        this.host = host;
        this.sampleRate = sampleRate;
    }

    void setKeys(int mask) {
        keys = mask;
    }

    void setFastForward(boolean enabled) {
        fastForward = enabled;
    }

    boolean isFastForward() {
        return fastForward;
    }

    /** 0 means unlimited. */
    void setFastForwardSpeed(int speed) {
        fastForwardSpeed = speed;
    }

    void setMuted(boolean mute) {
        muted = mute;
    }

    void setPaused(boolean pause) {
        synchronized (pauseLock) {
            paused = pause;
            pauseLock.notifyAll();
        }
    }

    /** Runs {@code task} on the emulation thread between frames (also while paused). */
    void post(Runnable task) {
        tasks.add(task);
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
    }

    /** Stops the thread and waits for it, running any tasks still queued. */
    void shutdown() {
        running = false;
        setPaused(false);
        boolean interrupted = false;
        while (isAlive()) {
            try {
                join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int frameSamples = (int) Math.ceil(sampleRate / FRAME_RATE) * 2;
        short[] audio = new short[frameSamples * 4];
        AudioTrack track = createAudioTrack(frameSamples);
        track.play();

        long nextFrameTime = System.nanoTime();
        int framesSinceBatteryCheck = 0;
        boolean wasMuted = !muted;

        try {
            while (running) {
                runTasks();
                if (paused) {
                    track.pause();
                    waitWhilePaused();
                    if (!running) break;
                    track.play();
                    nextFrameTime = System.nanoTime();
                    continue;
                }

                if (wasMuted != muted) {
                    wasMuted = muted;
                    track.setVolume(muted ? 0f : 1f);
                }

                Emulator.nativeSetKeys(keys);
                int count = Emulator.nativeRunFrame(audio);
                publishFrame();
                publishRumble();

                if (++framesSinceBatteryCheck >= BATTERY_CHECK_FRAMES) {
                    framesSinceBatteryCheck = 0;
                    if (Emulator.nativeTakeBatteryDirty()) host.onBatteryDirty();
                }

                if (fastForward) {
                    int speed = fastForwardSpeed;
                    if (speed > 0) {
                        nextFrameTime += (long) (1e9 / (FRAME_RATE * speed));
                        sleepUntil(nextFrameTime);
                    }
                    long now = System.nanoTime();
                    if (speed == 0 || nextFrameTime < now - 100_000_000L) nextFrameTime = now;
                } else {
                    // Blocks while the audio buffer is full, which paces emulation at real time.
                    track.write(audio, 0, count);
                    nextFrameTime = System.nanoTime();
                }
            }
            runTasks();
            if (Emulator.nativeTakeBatteryDirty()) host.onBatteryDirty();
        } finally {
            lastRumble = 0;
            host.onRumble(0);
            track.stop();
            track.release();
        }
    }

    private AudioTrack createAudioTrack(int frameSamples) {
        int minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        // About three frames of audio: low latency, but enough slack for the occasional slow frame.
        int bufferBytes = Math.max(minBuffer, frameSamples * 2 * 3);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();
        return new AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();
    }

    private void publishFrame() {
        frameIndex = (frameIndex + 1) % frames.length;
        Frame frame = frames[frameIndex];
        frame.width = Emulator.nativeGetFrameWidth();
        frame.height = Emulator.nativeGetFrameHeight();
        frame.odd = Emulator.nativeIsOddFrame();
        if (Emulator.nativeCopyFrame(frame.pixels)) {
            host.onFrame(frame);
        } else {
            Log.w(TAG, "Could not copy frame");
        }
    }

    private void publishRumble() {
        double rumble = Emulator.nativeGetRumble();
        if (rumble != lastRumble) {
            lastRumble = rumble;
            host.onRumble(rumble);
        }
    }

    private void runTasks() {
        Runnable task;
        while ((task = tasks.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException e) {
                Log.e(TAG, "Emulation task failed", e);
            }
        }
    }

    private void waitWhilePaused() {
        while (true) {
            synchronized (pauseLock) {
                while (paused && running && tasks.isEmpty()) {
                    try {
                        pauseLock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            // Woken up either to resume, to stop, or to run tasks while staying paused.
            runTasks();
            if (!paused || !running) return;
        }
    }

    private static void sleepUntil(long deadline) {
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            try {
                Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
