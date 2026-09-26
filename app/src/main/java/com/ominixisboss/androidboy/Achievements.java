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
    // Shown in the corner rather than as a banner: an achievement's progress, and the live value of a
    // leaderboard attempt (the event's points carry the tracker's id).
    static final int EVENT_PROGRESS_SHOW = 7;
    static final int EVENT_PROGRESS_HIDE = 8;
    static final int EVENT_PROGRESS_UPDATE = 9;
    static final int EVENT_TRACKER_SHOW = 10;
    static final int EVENT_TRACKER_HIDE = 11;
    static final int EVENT_TRACKER_UPDATE = 12;
    /** A submitted score's rank (points carry the rank). */
    static final int EVENT_SCOREBOARD = 13;
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
        // What kind of achievement it is (rc_client.h).
        static final int TYPE_STANDARD = 0;
        static final int TYPE_MISSABLE = 1;
        static final int TYPE_PROGRESSION = 2;
        static final int TYPE_WIN = 3;
        // Unlocked flags.
        static final int UNLOCKED_SOFTCORE = 1;
        static final int UNLOCKED_HARDCORE = 2;

        /** Unlocked in the current mode (hardcore when hardcore is on). */
        final boolean unlocked;
        final int points;
        final String title;
        final String description;
        /** The badge as it looks now: greyed out while locked. */
        final String badgeUrl;
        /** E.g. "3/10" for achievements that count something; empty otherwise. */
        final String progress;
        final int id;
        /** When it was unlocked, in seconds since 1970; 0 if it isn't. */
        final long unlockTime;
        /** Percent of players who've unlocked it, and in hardcore; 0 if unknown. */
        final float rarity;
        final float rarityHardcore;
        final int type;
        /** How far along a counting achievement is, 0 to 100. */
        final float percent;
        /** UNLOCKED_SOFTCORE and UNLOCKED_HARDCORE, whichever it's been unlocked in. */
        final int unlockedIn;
        /** The group it's listed under ("Locked", "Recently Unlocked", …). */
        final String group;
        /** The full-colour badge, whether or not it's unlocked. */
        final String unlockedBadgeUrl;

        Achievement(boolean unlocked, int points, String title, String description, String badgeUrl, String progress) {
            this(unlocked, points, title, description, badgeUrl, progress, 0, 0, 0, 0, TYPE_STANDARD, 0,
                    unlocked ? UNLOCKED_SOFTCORE : 0, "", badgeUrl);
        }

        Achievement(boolean unlocked, int points, String title, String description, String badgeUrl, String progress,
                    int id, long unlockTime, float rarity, float rarityHardcore, int type, float percent,
                    int unlockedIn, String group, String unlockedBadgeUrl) {
            this.unlocked = unlocked;
            this.points = points;
            this.title = title;
            this.description = description;
            this.badgeUrl = badgeUrl;
            this.progress = progress;
            this.id = id;
            this.unlockTime = unlockTime;
            this.rarity = rarity;
            this.rarityHardcore = rarityHardcore;
            this.type = type;
            this.percent = percent;
            this.unlockedIn = unlockedIn;
            this.group = group;
            this.unlockedBadgeUrl = unlockedBadgeUrl;
        }

        /** Parses a row from nativeAchievementList. */
        static Achievement parse(String row) {
            String[] f = row.split(SEPARATOR, -1);
            boolean unlocked = "1".equals(f[0]);
            String badge = f[4];
            return new Achievement(unlocked, Integer.parseInt(f[1]), f[2], f[3], badge, field(f, 5),
                    (int) number(f, 6), (long) number(f, 7), (float) number(f, 8), (float) number(f, 9),
                    (int) number(f, 10), (float) number(f, 11),
                    f.length > 12 ? (int) number(f, 12) : unlocked ? UNLOCKED_SOFTCORE : 0,
                    field(f, 13), f.length > 14 && !f[14].isEmpty() ? f[14] : badge);
        }

        private static String field(String[] f, int index) {
            return index < f.length ? f[index] : "";
        }

        private static double number(String[] f, int index) {
            try {
                return index < f.length ? Double.parseDouble(f[index]) : 0;
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        String pageUrl() {
            return SITE + "/achievement/" + id;
        }
    }

    /** One of the current game's leaderboards. */
    static final class Leaderboard {
        final int id;
        final String title;
        final String description;

        Leaderboard(int id, String title, String description) {
            this.id = id;
            this.title = title;
            this.description = description;
        }

        /** Parses a row from nativeLeaderboardList. */
        static Leaderboard parse(String row) {
            String[] f = row.split(SEPARATOR, -1);
            return new Leaderboard(Integer.parseInt(f[0]), f[1], f.length > 2 ? f[2] : "");
        }

        String pageUrl() {
            return SITE + "/leaderboardinfo.php?i=" + id;
        }
    }

    /** A leaderboard entry: rank, player and their score or time. */
    static final class LeaderboardEntry {
        final int rank;
        final String user;
        final String score;

        LeaderboardEntry(int rank, String user, String score) {
            this.rank = rank;
            this.user = user;
            this.score = score;
        }

        static LeaderboardEntry parse(String row) {
            String[] f = row.split(SEPARATOR, -1);
            return new LeaderboardEntry(Integer.parseInt(f[0]), f[1], f.length > 2 ? f[2] : "");
        }
    }

    interface LeaderboardCallback {
        /** Main thread. {@code entries} is null if the request failed, with {@code error} saying why. */
        void onEntries(List<LeaderboardEntry> entries, int total, String error);
    }

    // rc_consoles.h
    static final int CONSOLE_GAMEBOY = 4;
    static final int CONSOLE_GAMEBOY_COLOR = 6;
    static final int CONSOLE_GAMEBOY_ADVANCE = 5;
    static final String SITE = "https://retroachievements.org";

    /** The player's progress in one game, for the achievements page. */
    static final class GameProgress {
        final int gameId;
        final int total;
        final int unlocked;
        final int unlockedHardcore;
        final String title;
        final String badgeUrl;

        GameProgress(int gameId, int total, int unlocked, int unlockedHardcore, String title, String badgeUrl) {
            this.gameId = gameId;
            this.total = total;
            this.unlocked = unlocked;
            this.unlockedHardcore = unlockedHardcore;
            this.title = title;
            this.badgeUrl = badgeUrl;
        }

        boolean mastered() {
            return total > 0 && unlockedHardcore >= total;
        }

        String pageUrl() {
            return SITE + "/game/" + gameId;
        }
    }

    interface ProgressCallback {
        /** Main thread. {@code games} is null if something failed, with {@code error} saying what. */
        void onProgress(List<GameProgress> games, String error);
    }

    /**
     * Puts together the games the player has unlocked something in, from nativeFetchProgress
     * results (four ints per game: id, total, unlocked, unlocked in hardcore) and their titles.
     * Most-completed first, then by title.
     */
    static List<GameProgress> combineProgress(List<int[]> progress, int[] ids, String[] titles, String[] badges) {
        java.util.Map<Integer, String[]> names = new java.util.HashMap<>();
        for (int i = 0; i < ids.length; i++) names.put(ids[i], new String[] {titles[i], badges[i]});
        java.util.Map<Integer, GameProgress> games = new java.util.LinkedHashMap<>();
        for (int[] values : progress) {
            for (int i = 0; i + 3 < values.length; i += 4) {
                int id = values[i];
                if (values[i + 2] == 0 && values[i + 3] == 0) continue; // Never played.
                String[] name = names.get(id);
                GameProgress game = new GameProgress(id, values[i + 1], values[i + 2], values[i + 3],
                        name != null && !name[0].isEmpty() ? name[0] : "Game " + id, name != null ? name[1] : "");
                GameProgress existing = games.get(id);
                if (existing == null || game.unlocked > existing.unlocked) games.put(id, game);
            }
        }
        List<GameProgress> list = new ArrayList<>(games.values());
        list.sort((a, b) -> {
            double pa = a.total == 0 ? 0 : (double) a.unlocked / a.total;
            double pb = b.total == 0 ? 0 : (double) b.unlocked / b.total;
            if (pa != pb) return Double.compare(pb, pa);
            return a.title.compareToIgnoreCase(b.title);
        });
        return list;
    }

    /** Game ids with any unlocks, from nativeFetchProgress results. */
    static int[] playedGames(List<int[]> progress) {
        java.util.Set<Integer> ids = new java.util.LinkedHashSet<>();
        for (int[] values : progress) {
            for (int i = 0; i + 3 < values.length; i += 4) {
                if (values[i + 2] > 0 || values[i + 3] > 0) ids.add(values[i]);
            }
        }
        int[] result = new int[ids.size()];
        int i = 0;
        for (int id : ids) result[i++] = id;
        return result;
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
    private ProgressCallback pendingProgress;
    private final List<int[]> progressResults = new ArrayList<>();
    /** Waiting leaderboard requests, by leaderboard id (one at a time for each). */
    private final java.util.Map<Integer, LeaderboardCallback> pendingLeaderboards = new java.util.HashMap<>();
    private volatile boolean loggedIn;
    private volatile boolean loggingIn;
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
        if (user != null && token != null) {
            loggingIn = true;
            nativeLoginWithToken(user, token);
        }
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

    /**
     * Fetches the player's Game Boy, Game Boy Color and Game Boy Advance progress: the games they've unlocked
     * something in, with titles and badges. Main thread; a new request replaces one in flight.
     */
    void fetchProgress(ProgressCallback callback) {
        boolean running = pendingProgress != null;
        pendingProgress = callback;
        if (running) return; // The request in flight answers the new callback.
        progressResults.clear();
        nativeFetchProgress(CONSOLE_GAMEBOY);
    }

    /** Display name, points, softcore points and avatar URL; or null if the login hasn't completed. */
    String[] userInfo() {
        String info = nativeUserInfo();
        return info == null ? null : info.split(SEPARATOR, -1);
    }

    /** Whether the saved login is still being checked with the server. */
    boolean isLoggingIn() {
        return loggingIn;
    }

    /** Tries the saved login again, e.g. after the network was down at startup. Main thread. */
    void reconnect(LoginCallback callback) {
        String user = prefs.getString(PREF_USER, null);
        String token = prefs.getString(PREF_TOKEN, null);
        if (loggedIn || user == null || token == null) {
            callback.onLoginResult(loggedIn ? null : "Not logged in");
            return;
        }
        pendingLogin = callback;
        if (loggingIn) return; // The attempt in flight answers the callback.
        loggingIn = true;
        nativeLoginWithToken(user, token);
    }

    void login(String username, String password, LoginCallback callback) {
        pendingLogin = callback;
        loggingIn = true;
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

    /**
     * What the game says the player is doing ("World 1-2, 3 lives"), or null. Reads game memory,
     * so call on the emulation thread (or while it's stopped).
     */
    String richPresence() {
        return gameLoaded ? nativeRichPresence() : null;
    }

    List<Leaderboard> leaderboards() {
        List<Leaderboard> list = new ArrayList<>();
        if (!gameLoaded) return list;
        for (String row : nativeLeaderboardList()) list.add(Leaderboard.parse(row));
        return list;
    }

    /** The top {@code count} entries, or those around the player. Main thread. */
    void fetchLeaderboard(int id, boolean aroundPlayer, int count, LeaderboardCallback callback) {
        boolean running = pendingLeaderboards.containsKey(id);
        pendingLeaderboards.put(id, callback);
        if (!running) nativeFetchLeaderboard(id, aroundPlayer, count);
    }

    @SuppressWarnings("unused")
    private static void onLeaderboardEntries(int id, int result, String error, String[] rows, int total, int userIndex) {
        Achievements self = instance;
        List<LeaderboardEntry> entries = new ArrayList<>();
        for (String row : rows) entries.add(LeaderboardEntry.parse(row));
        String message = result == RC_OK ? null : error != null ? error : "Could not load the leaderboard (" + result + ")";
        self.main.post(() -> {
            LeaderboardCallback callback = self.pendingLeaderboards.remove(id);
            if (callback != null) callback.onEntries(message == null ? entries : null, total, message);
        });
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
        self.loggingIn = false;
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
    private static void onProgress(int console, int result, String error, int[] values) {
        Achievements self = instance;
        self.main.post(() -> {
            if (self.pendingProgress == null) return;
            if (result != RC_OK) {
                failProgress(self, error != null ? error : "Could not load your progress (" + result + ")");
                return;
            }
            self.progressResults.add(values);
            if (console == CONSOLE_GAMEBOY) {
                nativeFetchProgress(CONSOLE_GAMEBOY_COLOR);
            } else if (console == CONSOLE_GAMEBOY_COLOR) {
                nativeFetchProgress(CONSOLE_GAMEBOY_ADVANCE);
            } else {
                nativeFetchTitles(playedGames(self.progressResults));
            }
        });
    }

    @SuppressWarnings("unused")
    private static void onTitles(int result, String error, int[] ids, String[] titles, String[] badges) {
        Achievements self = instance;
        self.main.post(() -> {
            if (self.pendingProgress == null) return;
            if (result != RC_OK) {
                failProgress(self, error != null ? error : "Could not load game titles (" + result + ")");
                return;
            }
            ProgressCallback callback = self.pendingProgress;
            self.pendingProgress = null;
            callback.onProgress(combineProgress(self.progressResults, ids, titles, badges), null);
        });
    }

    private static void failProgress(Achievements self, String error) {
        ProgressCallback callback = self.pendingProgress;
        self.pendingProgress = null;
        callback.onProgress(null, error);
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
    private static native void nativeFetchProgress(int console);
    private static native void nativeFetchTitles(int[] gameIds);
    private static native String nativeRichPresence();
    private static native String[] nativeLeaderboardList();
    private static native void nativeFetchLeaderboard(int id, boolean aroundUser, int count);
}
