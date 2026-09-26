# Native methods are bound by name from jni_bridge.c.
-keep class com.ominixisboss.androidboy.Emulator { native <methods>; }

# RetroAchievements (achievements.c): native methods are bound by name, and native code calls
# these static methods back by name and signature, so neither may be renamed or removed.
-keep class com.ominixisboss.androidboy.Achievements {
    native <methods>;
    static void serverCall(java.lang.String, java.lang.String, java.lang.String, long, long);
    static void onLogin(int, java.lang.String, java.lang.String, java.lang.String);
    static void onGameLoaded(int, java.lang.String);
    static void onEvent(int, java.lang.String, java.lang.String, java.lang.String, int);
}
-keep class com.ominixisboss.androidboy.Achievements {
    static void onProgress(int, int, java.lang.String, int[]);
    static void onTitles(int, java.lang.String, int[], java.lang.String[], java.lang.String[]);
}
