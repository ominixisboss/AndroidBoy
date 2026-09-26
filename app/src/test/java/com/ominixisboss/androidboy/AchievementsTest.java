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
