package com.ominixisboss.androidboy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RetroAchievements (retroachievements.org), through the rcheevos client in native code
 * (app/src/main/cpp/achievements.c). This class keeps the login, makes the web requests
 * rcheevos asks for, and passes its events on to the main thread.
 *
 * Only the login token is stored (in its own preferences file, excluded from backups); the
 * password is sent once, to log in, and never saved.
 */
final class Achievements {
    // rc_client event types (rc_client.h)
    static final int EVENT_ACHIEVEMENT = 1;
    static final int EVENT_LEADERBOARD_STARTED = 2;
    static final int EVENT_LEADERBOARD_FAILED = 3;
    static final int EVENT_LEADERBOARD_SUBMITTED = 4;
    static final int EVENT_RESET = 14;
    static final int EVENT_GAME_COMPLETED = 15;
    static final int EVENT_SERVER_ERROR = 16;
    static final int EVENT_DISCONNECTED = 17;
    static final int EVENT_RECONNECTED = 18;
    /** Not from rcheevos: a game finished loading; {@link Event#description} summarizes progress. */
    static final int EVENT_GAME_LOADED = 100;

    // rc_error.h
    private static final int RC_OK = 0;
    private static final int RC_NO_GAME_LOADED = -29;
    private static final int RC_INVALID_CREDENTIALS = -34;
    private static final int RC_EXPIRED_TOKEN = -35;
    // rc_api_request.h: a network failure worth retrying.
    private static final int RETRYABLE_CLIENT_ERROR = -2;

    static final String PREFS = "retroachievements";
    private static final String PREF_USER = "user";
    private static final String PREF_TOKEN = "token";
    private static final String PREF_HARDCORE = "hardcore";
    private static final String TAG = "AndroidBoy";
    /** Field separator in the strings native code returns (a control character, never in titles). */
    static final String SEPARATOR = "\u001f";

    static final class Event {
        final int type;
        final String title;
        final String description;
        final String imageUrl;
        final int points;

        Event(int type, String title, String description, String imageUrl, int points) {
            this.type = type;
            this.title = title;
            this.description = description;
            this.imageUrl = imageUrl;
            this.points = points;
        }
    }

    /** One of the current game's achievements. */
    static final class Achievement {
        final boolean unlocked;
        final int points;
        final String title;
        final String description;
        final String badgeUrl;
        /** E.g. "3/10" for achievements that count something; empty otherwise. */
        final String progress;

        Achievement(boolean unlocked, int points, String title, String description, String badgeUrl, String progress) {
            this.unlocked = unlocked;
            this.points = points;
            this.title = title;
            this.description = description;
            this.badgeUrl = badgeUrl;
            this.progress = progress;
        }

        /** Parses a row from nativeAchievementList. */
        static Achievement parse(String row) {
            String[] f = row.split(SEPARATOR, -1);
            return new Achievement("1".equals(f[0]), Integer.parseInt(f[1]), f[2], f[3], f[4], f.length > 5 ? f[5] : "");
        }
    }

    interface Listener {
        /** Main thread. */
        void onAchievementEvent(Event event);
    }

    interface LoginCallback {
        /** Main thread. {@code error} is null on success. */
        void onLoginResult(String error);
    }

    private static Achievements instance;

    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService http = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "RetroAchievements");
        thread.setDaemon(true);
        return thread;
    });
    private final String userAgent;
    private Listener listener;
    private LoginCallback pendingLogin;
    private volatile boolean loggedIn;
    private volatile boolean gameLoaded;

    static synchronized boolean isCreated() {
        return instance != null;
    }

    /** The instance, if {@link #get} has been called. */
    static synchronized Achievements existing() {
        return instance;
    }

    static synchronized Achievements get(Context context) {
        if (instance == null) instance = new Achievements(context.getApplicationContext());
        return instance;
    }

    private Achievements(Context context) {
        // Native calls below can call straight back (e.g. logging in with the saved token).
        instance = this;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Emulator.loadLibrary();
        nativeInit();
        nativeSetHardcore(isHardcoreEnabled());
        String version;
        try {
            version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "unknown";
        }
        // RetroAchievements asks clients to identify themselves: app/version, platform, rcheevos version.
        userAgent = "AndroidBoy/" + version.split(" ")[0] + " (Android " + Build.VERSION.RELEASE + ") "
                + nativeUserAgentClause();
        String user = prefs.getString(PREF_USER, null);
        String token = prefs.getString(PREF_TOKEN, null);
        if (user != null && token != null) nativeLoginWithToken(user, token);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Stops sending events to {@code listener}, unless another one has replaced it already. */
    void removeListener(Listener listener) {
        if (this.listener == listener) this.listener = null;
    }

    /** A username is known (logged in, or logging in with a saved token). */
    boolean hasAccount() {
        return prefs.getString(PREF_TOKEN, null) != null;
    }

    String username() {
        return prefs.getString(PREF_USER, null);
    }

    /** Display name and points, or null if the login hasn't completed. */
    String[] userInfo() {
        String info = nativeUserInfo();
        return info == null ? null : info.split(SEPARATOR, -1);
    }

    void login(String username, String password, LoginCallback callback) {
        pendingLogin = callback;
        nativeLoginWithPassword(username, password);
    }

    void logout() {
        nativeLogout();
        loggedIn = false;
        gameLoaded = false;
        prefs.edit().remove(PREF_USER).remove(PREF_TOKEN).apply();
    }

    boolean isHardcoreEnabled() {
        return prefs.getBoolean(PREF_HARDCORE, false);
    }

    /**
     * Hardcore mode: unlocks count as hardcore, but loading states and rewinding are off.
     * Turning it on during a game restarts the game (rcheevos asks for that with EVENT_RESET).
     */
    void setHardcoreEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_HARDCORE, enabled).apply();
        nativeSetHardcore(enabled);
    }

    /** Whether hardcore rules apply right now: signed in, hardcore on, and the game has achievements. */
    boolean hardcoreActive() {
        return hasAccount() && isHardcoreEnabled() && gameLoaded;
    }

    /** Starts identifying and loading a game's achievements. Emulation must be stopped or on its thread. */
    void loadGame(byte[] rom) {
        gameLoaded = false;
        if (!hasAccount()) return;
        nativeLoadGame(rom);
    }

    void unloadGame() {
        gameLoaded = false;
        nativeUnloadGame();
    }

    /** Title, unlocked count, total, points unlocked, points total; or null with no game. */
    String[] gameSummary() {
        String summary = nativeGameSummary();
        return summary == null ? null : summary.split(SEPARATOR, -1);
    }

    List<Achievement> achievementList() {
        List<Achievement> list = new ArrayList<>();
        for (String row : nativeAchievementList()) list.add(Achievement.parse(row));
        return list;
    }

    /** After the game is reset or rewound: achievements watch the game from its new state. */
    void onGameReset() {
        nativeReset();
    }

    /** Progress to store with a save state. Emulation thread. */
    byte[] saveProgress() {
        return nativeSaveProgress();
    }

    /** After a save state was loaded, with the progress stored with it (null if none). Emulation thread. */
    void loadProgress(byte[] progress) {
        nativeLoadProgress(progress);
    }

    /** Keeps the session alive while the game is paused. Any thread. */
    void idle() {
        nativeIdle();
    }

    // ---- Called from native code ----

    /** rcheevos wants a web request made; the answer goes back through nativeHttpResponse. */
    @SuppressWarnings("unused")
    private static void serverCall(String url, String postData, String contentType, long callback, long callbackData) {
        Achievements self = instance;
        self.http.execute(() -> {
            byte[] body = null;
            int status;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(30_000);
                connection.setRequestProperty("User-Agent", self.userAgent);
                if (postData != null) {
                    connection.setDoOutput(true);
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type",
                            contentType != null ? contentType : "application/x-www-form-urlencoded");
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(postData.getBytes(StandardCharsets.UTF_8));
                    }
                }
                status = connection.getResponseCode();
                InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                body = in == null ? new byte[0] : readAll(in);
            } catch (IOException e) {
                Log.w(TAG, "RetroAchievements request failed: " + e);
                status = RETRYABLE_CLIENT_ERROR;
                body = null;
            } finally {
                if (connection != null) connection.disconnect();
            }
            nativeHttpResponse(callback, callbackData, body, status);
        });
    }

    @SuppressWarnings("unused")
    private static void onLogin(int result, String error, String username, String token) {
        Achievements self = instance;
        if (result == RC_OK) {
            self.loggedIn = true;
            self.prefs.edit().putString(PREF_USER, username).putString(PREF_TOKEN, token).apply();
        } else if (result == RC_INVALID_CREDENTIALS || result == RC_EXPIRED_TOKEN) {
            // The saved token no longer works; the user has to log in again.
            self.prefs.edit().remove(PREF_USER).remove(PREF_TOKEN).apply();
        }
        String message = result == RC_OK ? null : error != null ? error : "Could not log in (" + result + ")";
        self.main.post(() -> {
            LoginCallback callback = self.pendingLogin;
            self.pendingLogin = null;
            if (callback != null) callback.onLoginResult(message);
        });
    }

    @SuppressWarnings("unused")
    private static void onGameLoaded(int result, String error) {
        Achievements self = instance;
        self.gameLoaded = result == RC_OK;
        Event event;
        if (result == RC_OK) {
            String[] s = self.gameSummary();
            event = s == null ? null : new Event(EVENT_GAME_LOADED, s[0],
                    s[2].equals("0") ? "No achievements yet" : s[1] + " of " + s[2] + " achievements unlocked", null, 0);
        } else if (result == RC_NO_GAME_LOADED) {
            event = null; // Not a game RetroAchievements knows; nothing to say.
        } else {
            event = new Event(EVENT_SERVER_ERROR, "RetroAchievements", error != null ? error : "Could not load achievements", null, 0);
        }
        if (event != null) self.dispatch(event);
    }

    @SuppressWarnings("unused")
    private static void onEvent(int type, String title, String description, String imageUrl, int points) {
        instance.dispatch(new Event(type, title, description, imageUrl, points));
    }

    private void dispatch(Event event) {
        main.post(() -> {
            if (listener != null) listener.onAchievementEvent(event);
        });
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    private static native void nativeInit();
    private static native String nativeUserAgentClause();
    private static native void nativeHttpResponse(long callback, long callbackData, byte[] body, int status);
    private static native void nativeLoginWithPassword(String username, String password);
    private static native void nativeLoginWithToken(String username, String token);
    private static native void nativeLogout();
    private static native String nativeUserInfo();
    private static native void nativeLoadGame(byte[] rom);
    private static native void nativeUnloadGame();
    private static native String nativeGameSummary();
    private static native String[] nativeAchievementList();
    private static native void nativeSetHardcore(boolean enabled);
    private static native boolean nativeIsHardcore();
    private static native void nativeReset();
    private static native void nativeIdle();
    private static native byte[] nativeSaveProgress();
    private static native void nativeLoadProgress(byte[] progress);
}
