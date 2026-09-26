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
        /** Called about once a second while paused (RetroAchievements keeps its session alive). */
        void onIdle();
        /** Rewinding stopped: the game is somewhere earlier than it was. */
        void onRewindFinished();
        /** The Game Boy Printer finished a printout: 160-pixel rows of 0xAABBGGRR pixels. */
        void onPrintout(int[] pixels);
        /** The link cable came unplugged (e.g. the other phone went away), with a reason for the player. */
        void onLinkEnded(String reason);
        /** Whether the game is held up waiting for the other phone (to show a message after a moment). */
        void onLinkWaiting(boolean waiting);
    }

    private static final String TAG = "AndroidBoy";
    private static final double FRAME_RATE = 4194304.0 / 70224.0; // ~59.73 Hz
    private static final int BATTERY_CHECK_FRAMES = 180;
    private static final int PRINTOUT_CHECK_FRAMES = 30;

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
    /** Held keys that press and release by themselves (turbo). */
    private volatile int turboKeys;
    private volatile int turboPeriod = 4;
    private volatile int speedPercent = 100;
    private int turboFrame;
    private volatile LinkSession link;
    private boolean linkWaiting;
    private long linkWaitStart;
    private volatile boolean fastForward;
    private volatile boolean rewinding;
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

    /** A link cable in use (or null): it sets both Game Boys' keys each frame. */
    void setLink(LinkSession session) {
        link = session;
    }

    /** Keys held in turbo mode: pressed for half of every {@link #setTurboPeriod period}, released for the rest. */
    void setTurboKeys(int mask) {
        turboKeys = mask;
    }

    /** Frames per turbo press-and-release; at least 2. */
    void setTurboPeriod(int frames) {
        turboPeriod = Math.max(2, frames);
    }

    /** Game speed in percent, for slow motion; audio plays slower and lower to match. */
    void setSpeedPercent(int percent) {
        speedPercent = Math.max(10, Math.min(100, percent));
    }

    /** The keys the game sees this frame: turbo keys are only down for the first half of each period. */
    static int effectiveKeys(int keys, int turbo, int frame, int period) {
        boolean turboDown = frame % period < period / 2;
        return (keys & ~turbo) | (turboDown ? turbo : 0);
    }

    void setFastForward(boolean enabled) {
        fastForward = enabled;
    }

    boolean isFastForward() {
        return fastForward;
    }

    /** While set, emulation runs backwards through the rewind history, silently, at normal speed. */
    void setRewinding(boolean enabled) {
        rewinding = enabled;
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
        int playbackSpeed = 100;
        boolean wasRewinding = false;

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

                if (playbackSpeed != speedPercent) {
                    // Writes block for longer at a lower rate, which slows the game down with it.
                    playbackSpeed = speedPercent;
                    track.setPlaybackRate(sampleRate * playbackSpeed / 100);
                }
                int turbo = turboKeys;
                if (turbo == 0) turboFrame = 0;
                int frameKeys = effectiveKeys(keys, turbo, turboFrame, turboPeriod);
                LinkSession session = link;
                if (session != null) {
                    int status = session.applyKeys(frameKeys);
                    if (status == LinkSession.ENDED) {
                        link = null;
                        setLinkWaiting(false);
                        host.onLinkEnded(session.endReason());
                        continue;
                    }
                    if (status == LinkSession.WAITING) {
                        // The other phone is behind (or paused): check back without running a frame.
                        if (!linkWaiting) {
                            linkWaiting = true;
                            linkWaitStart = System.nanoTime();
                        } else if (System.nanoTime() - linkWaitStart > 1_000_000_000L) {
                            host.onLinkWaiting(true);
                        }
                        nextFrameTime = System.nanoTime();
                        continue;
                    }
                    setLinkWaiting(false);
                } else {
                    Emulator.nativeSetKeys(frameKeys);
                }
                turboFrame++;
                if (wasRewinding != rewinding) {
                    wasRewinding = rewinding;
                    if (!wasRewinding) host.onRewindFinished();
                }
                if (rewinding && session == null) {
                    // Stays on the oldest frame once the history runs out. (Never while linked:
                    // this frame's keys are already exchanged, and the core keeps no history then.)
                    Emulator.nativeRewindFrame(audio);
                    publishFrame();
                    nextFrameTime += (long) (1e9 / FRAME_RATE);
                    sleepUntil(nextFrameTime);
                    if (nextFrameTime < System.nanoTime() - 100_000_000L) nextFrameTime = System.nanoTime();
                    continue;
                }
                int count = Emulator.nativeRunFrame(audio);
                publishFrame();
                publishRumble();

                if (++framesSinceBatteryCheck >= BATTERY_CHECK_FRAMES) {
                    framesSinceBatteryCheck = 0;
                    if (Emulator.nativeTakeBatteryDirty()) host.onBatteryDirty();
                    LinkSession linked = link;
                    if (linked != null) linked.periodic();
                }
                if (framesSinceBatteryCheck % PRINTOUT_CHECK_FRAMES == 0) {
                    int[] printout = Emulator.nativeTakePrintout();
                    if (printout != null) host.onPrintout(printout);
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

    private void setLinkWaiting(boolean waiting) {
        if (linkWaiting && !waiting) host.onLinkWaiting(false);
        linkWaiting = waiting;
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
                        pauseLock.wait(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (paused && running && tasks.isEmpty()) host.onIdle();
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
