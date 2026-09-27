package com.ominixisboss.androidboy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The Java side of RetroAchievements that doesn't need the native library: reading what native
 * code returns, and what the in-game banner says. (tests/achievements_test.c covers rcheevos itself.)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AchievementsTest {
    private static final String S = Achievements.SEPARATOR;

    @Test
    public void parsesAchievementRows() {
        Achievements.Achievement a = Achievements.Achievement.parse(
                "1" + S + "10" + S + "Tab\tin title" + S + "Beat the boss" + S + "https://media/b.png" + S + "");
        assertTrue(a.unlocked);
        assertEquals(10, a.points);
        assertEquals("Tab\tin title", a.title);
        assertEquals("Beat the boss", a.description);
        assertEquals("https://media/b.png", a.badgeUrl);
        assertEquals("", a.progress);

        Achievements.Achievement b = Achievements.Achievement.parse("0" + S + "5" + S + "Collector" + S + "" + S + "" + S + "3/10");
        assertFalse(b.unlocked);
        assertEquals("", b.description);
        assertEquals("3/10", b.progress);
    }

    @Test
    public void parsesAchievementDetails() {
        Achievements.Achievement a = Achievements.Achievement.parse(String.join(S, "0", "25", "Speedrunner",
                "Beat the game in an hour", "https://media/b_lock.png", "12/60", "4321", "1700000000", "12.50",
                "3.25", "3", "20.00", "1", "Almost There", "https://media/b.png"));
        assertFalse(a.unlocked);
        assertEquals(4321, a.id);
        assertEquals(1700000000L, a.unlockTime);
        assertEquals(12.5f, a.rarity, 0.001f);
        assertEquals(3.25f, a.rarityHardcore, 0.001f);
        assertEquals(Achievements.Achievement.TYPE_WIN, a.type);
        assertEquals(20f, a.percent, 0.001f);
        assertEquals(Achievements.Achievement.UNLOCKED_SOFTCORE, a.unlockedIn);
        assertEquals("Almost There", a.group);
        assertEquals("https://media/b_lock.png", a.badgeUrl);
        assertEquals("https://media/b.png", a.unlockedBadgeUrl);
        assertEquals("https://retroachievements.org/achievement/4321", a.pageUrl());
        // Old-style rows still parse.
        Achievements.Achievement old = Achievements.Achievement.parse("1" + S + "5" + S + "T" + S + "D" + S + "u" + S + "");
        assertEquals(0, old.id);
        assertEquals("", old.group);
        assertEquals("u", old.unlockedBadgeUrl);
        assertEquals(Achievements.Achievement.UNLOCKED_SOFTCORE, old.unlockedIn);
    }

    private static Achievements.Achievement achievement(boolean unlocked, String group, int unlockedIn, long time,
                                                        String progress, float percent, int type, float rarity) {
        return new Achievements.Achievement(unlocked, 10, "T", "D", "", progress, 1, time, rarity, rarity / 2,
                type, percent, unlockedIn, group, "");
    }

    @Test
    public void groupsTheListUnderHeadings() {
        List<Achievements.Achievement> list = new ArrayList<>();
        list.add(achievement(false, "Locked", 0, 0, "", 0, 0, 0));
        list.add(achievement(false, "Locked", 0, 0, "", 0, 0, 0));
        list.add(achievement(true, "Unlocked", 3, 5, "", 0, 0, 0));
        List<Object> items = AchievementDialogs.group(list);
        assertEquals(5, items.size());
        assertEquals("Locked  ·  2", items.get(0));
        assertEquals(list.get(0), items.get(1));
        assertEquals("Unlocked  ·  1", items.get(3));
        // Without group names from rcheevos: by whether they're unlocked.
        List<Achievements.Achievement> plain = new ArrayList<>();
        plain.add(achievement(true, "", 1, 0, "", 0, 0, 0));
        plain.add(achievement(false, "", 0, 0, "", 0, 0, 0));
        List<Object> plainItems = AchievementDialogs.group(plain);
        assertEquals("Unlocked  ·  1", plainItems.get(0));
        assertEquals("Locked  ·  1", plainItems.get(2));
        assertTrue(AchievementDialogs.group(new ArrayList<>()).isEmpty());
    }

    @Test
    public void describesAnAchievement() {
        java.text.DateFormat dates = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT);
        dates.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        List<String> locked = AchievementDialogs.detailLines(
                achievement(false, "", 0, 0, "3/10", 30, Achievements.Achievement.TYPE_MISSABLE, 12.5f), false, dates);
        assertEquals("Locked", locked.get(0));
        assertEquals("Progress: 3/10 (30%)", locked.get(1));
        assertTrue(locked.get(2).startsWith("Missable"));
        assertEquals("Unlocked by 12.5% of players (6.3% in hardcore)", locked.get(3));

        List<String> hardcore = AchievementDialogs.detailLines(
                achievement(true, "", 3, 86400, "", 0, 0, 0), true, dates);
        assertEquals(java.util.Arrays.asList("✓ Unlocked in hardcore on 1970-01-02"), hardcore);

        List<String> softcoreOnly = AchievementDialogs.detailLines(
                achievement(false, "", 1, 86400, "", 0, Achievements.Achievement.TYPE_PROGRESSION, 0), true, dates);
        assertTrue(softcoreOnly.get(0).startsWith("Unlocked in softcore on 1970-01-02."));
        assertTrue(softcoreOnly.get(1).startsWith("Progression"));

        assertEquals("<0.1%", AchievementDialogs.percent(0.05f));
        assertEquals("3%", AchievementDialogs.percent(3f));
        assertEquals("45.2%", AchievementDialogs.percent(45.21f));
    }

    @Test
    public void detailsOpenFullScreen() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Achievements.Achievement a = new Achievements.Achievement(false, 25, "Speedrunner", "Beat the game in an hour",
                "", "12/60", 42, 0, 12.5f, 3f, Achievements.Achievement.TYPE_WIN, 20f, 0, "Locked", "");
        AchievementDialogs.showDetails(activity, a, false);
        android.app.Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        android.view.Window window = dialog.getWindow();
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, window.getAttributes().width);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, window.getAttributes().height);
        List<String> texts = new ArrayList<>();
        collectText(window.getDecorView(), texts);
        assertTrue(texts.toString(), texts.contains("Achievement"));
        assertTrue(texts.contains("Speedrunner"));
        assertTrue(texts.contains("25 points"));
        assertTrue(texts.contains("Progress: 12/60 (20%)"));
        assertTrue(texts.contains("View on RetroAchievements"));
    }

    private static void collectText(View view, List<String> texts) {
        if (view instanceof TextView) texts.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectText(group.getChildAt(i), texts);
        }
    }

    @Test
    public void combinesProgressFromBothConsoles() {
        // id, total, unlocked, unlocked hardcore
        List<int[]> progress = new ArrayList<>();
        progress.add(new int[] {10, 20, 5, 0, 11, 30, 0, 0, 12, 8, 8, 8});
        progress.add(new int[] {13, 10, 5, 5, 10, 20, 2, 0});
        assertArrayEquals(new int[] {10, 12, 13}, Achievements.playedGames(progress));

        List<Achievements.GameProgress> games = Achievements.combineProgress(progress,
                new int[] {10, 12, 13}, new String[] {"Zeta", "Mastered", "Alpha"}, new String[] {"b10", "b12", ""});
        // Never-played game 11 is left out; 10 is listed once, with its best numbers.
        assertEquals(3, games.size());
        assertEquals("Mastered", games.get(0).title);
        assertTrue(games.get(0).mastered());
        assertEquals("Alpha", games.get(1).title); // 50%, same as Zeta: sorted by title.
        assertEquals("Zeta", games.get(2).title);
        assertEquals(5, games.get(2).unlocked);
        assertEquals("b10", games.get(2).badgeUrl);
        assertFalse(games.get(2).mastered());
        assertEquals("https://retroachievements.org/game/10", games.get(2).pageUrl());

        // A title the server didn't send.
        assertEquals("Game 13", Achievements.combineProgress(progress, new int[0], new String[0], new String[0])
                .get(1).title);
    }

    @Test
    public void progressPageText() {
        Achievements.GameProgress partial = new Achievements.GameProgress(1, 20, 12, 8, "A", "");
        Achievements.GameProgress softcore = new Achievements.GameProgress(2, 20, 1, 0, "B", "");
        Achievements.GameProgress mastered = new Achievements.GameProgress(3, 4, 4, 4, "C", "");
        assertEquals("12 of 20 unlocked · 8 hardcore", AchievementsActivity.progressText(partial));
        assertEquals("1 of 20 unlocked", AchievementsActivity.progressText(softcore));
        assertEquals("Mastered · 4 of 4", AchievementsActivity.progressText(mastered));
        assertEquals("3 games · 17 achievements (12 hardcore) · 1 mastered",
                AchievementsActivity.summary(java.util.Arrays.asList(partial, softcore, mastered)));
        assertEquals("1 game · 1 achievement", AchievementsActivity.summary(java.util.Arrays.asList(softcore)));
        assertTrue(AchievementsActivity.summary(new ArrayList<>()).startsWith("No Game Boy achievements"));
    }

    @Test
    public void leaderboards() {
        String S = Achievements.SEPARATOR;
        Achievements.Leaderboard board = Achievements.Leaderboard.parse("7" + S + "Speedrun" + S + "Fastest start");
        assertEquals(7, board.id);
        assertEquals("Speedrun", board.title);
        assertEquals("https://retroachievements.org/leaderboardinfo.php?i=7", board.pageUrl());
        List<Achievements.LeaderboardEntry> entries = new ArrayList<>();
        entries.add(Achievements.LeaderboardEntry.parse("1" + S + "Champion" + S + "004321"));
        entries.add(Achievements.LeaderboardEntry.parse("2" + S + "Tester" + S + "001234"));
        assertEquals("    1  Champion         004321\n▶   2  Tester           001234\n\n2 players",
                AchievementDialogs.formatEntries(entries, "tester", 2));
        assertEquals("No scores yet.", AchievementDialogs.formatEntries(new ArrayList<>(), "me", 0));
        assertEquals(new String[] {"LEADERBOARD RANK", "Speedrun", "Rank 2 of 9"}[2],
                AchievementPopup.describe(event(Achievements.EVENT_SCOREBOARD, "Speedrun", "Rank 2 of 9", 2))[2]);
    }

    @Test
    public void trackersAndProgressStayOffTheBanner() {
        TrackerOverlay overlay = new TrackerOverlay(org.robolectric.RuntimeEnvironment.getApplication());
        assertTrue(overlay.handle(event(Achievements.EVENT_TRACKER_SHOW, null, "0:12.34", 7)));
        assertEquals(2, overlay.getChildCount()); // The progress box, then the tracker.
        assertTrue(overlay.handle(event(Achievements.EVENT_TRACKER_UPDATE, null, "0:13.00", 7)));
        assertEquals("0:13.00", ((TextView) overlay.getChildAt(1)).getText().toString());
        assertTrue(overlay.handle(event(Achievements.EVENT_TRACKER_HIDE, null, null, 7)));
        assertEquals(1, overlay.getChildCount());
        assertTrue(overlay.handle(event(Achievements.EVENT_PROGRESS_SHOW, "Collector", "3/10", 0)));
        assertEquals(View.VISIBLE, overlay.getChildAt(0).getVisibility());
        assertFalse(overlay.handle(event(Achievements.EVENT_ACHIEVEMENT, "Marker", "", 5)));
        assertNull(AchievementPopup.describe(event(Achievements.EVENT_TRACKER_SHOW, null, "1", 1)));
    }

    @Test
    public void bannerText() {
        assertArrayEquals(new String[] {"ACHIEVEMENT UNLOCKED · 5 POINTS", "Marker", "Write the marker"},
                AchievementPopup.describe(event(Achievements.EVENT_ACHIEVEMENT, "Marker", "Write the marker", 5)));
        assertEquals("ACHIEVEMENT UNLOCKED · 1 POINT",
                AchievementPopup.describe(event(Achievements.EVENT_ACHIEVEMENT, "One", "", 1))[0]);
        assertArrayEquals(new String[] {"RETROACHIEVEMENTS", "Test Cartridge", "3 of 20 achievements unlocked"},
                AchievementPopup.describe(event(Achievements.EVENT_GAME_LOADED, "Test Cartridge",
                        "3 of 20 achievements unlocked", 0)));
        assertEquals("LEADERBOARD SCORE SUBMITTED",
                AchievementPopup.describe(event(Achievements.EVENT_LEADERBOARD_SUBMITTED, "Speedrun", "1:23", 0))[0]);
        assertEquals("Offline", AchievementPopup.describe(event(Achievements.EVENT_DISCONNECTED, null, null, 0))[1]);
        // A reset request restarts the game instead of showing a banner.
        assertNull(AchievementPopup.describe(event(Achievements.EVENT_RESET, null, null, 0)));
    }

    @Test
    public void bannerShowsEventsInTurn() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AchievementPopup popup = new AchievementPopup(activity);
        assertEquals(View.GONE, popup.getVisibility());
        popup.show(event(Achievements.EVENT_ACHIEVEMENT, "First", "The first one", 5));
        popup.show(event(Achievements.EVENT_ACHIEVEMENT, "Second", "The second one", 10));
        assertEquals(View.VISIBLE, popup.getVisibility());
        List<String> texts = texts(popup);
        assertTrue(texts.toString(), texts.contains("First"));
        assertFalse("one at a time", texts.contains("Second"));
    }

    private static Achievements.Event event(int type, String title, String description, int points) {
        return new Achievements.Event(type, title, description, null, points);
    }

    private static List<String> texts(View view) {
        List<String> out = new ArrayList<>();
        if (view instanceof TextView) out.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) out.addAll(texts(group.getChildAt(i)));
        }
        return out;
    }
}
