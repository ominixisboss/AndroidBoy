package com.ominixisboss.androidboy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The patch search screen: opened for a game, it searches straight away. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PatchSearchTest {
    @Test
    public void searchesForTheGameItWasOpenedFor() {
        Intent intent = PatchSearchActivity.intent(RuntimeEnvironment.getApplication(), "Pokemon Emerald");
        PatchSearchActivity activity = Robolectric.buildActivity(PatchSearchActivity.class, intent).setup().get();
        WebView web = findWebView(activity.getWindow().getDecorView());
        assertNotNull(web);
        String url = shadowOf(web).getLastLoadedUrl();
        assertTrue(url, url.contains("Pokemon+Emerald") && url.contains("romhacking.net"));
    }

    @Test
    public void itchModeBrowsesItchAndOnlyTakesGames() throws Exception {
        PatchSearchActivity activity = Robolectric.buildActivity(PatchSearchActivity.class,
                PatchSearchActivity.itchIntent(RuntimeEnvironment.getApplication())).setup().get();
        String url = shadowOf(findWebView(activity.getWindow().getDecorView())).getLastLoadedUrl();
        assertTrue(url, url.equals("https://itch.io/games/free/tag-gameboy-rom"));

        assertTrue(PatchSearchActivity.onItch("https://itch.io/games/free/tag-gbc"));
        assertTrue(PatchSearchActivity.onItch("https://someone.itch.io/a-game"));
        assertTrue(!PatchSearchActivity.onItch("https://notitch.io/") && !PatchSearchActivity.onItch("https://itch.io.evil.example/"));

        // A Game Boy game is taken, bare or zipped next to a Windows build's files.
        byte[] game = new byte[0x8000];
        game[0x104] = (byte) 0xCE;
        game[0x105] = (byte) 0xED;
        game[0x106] = 0x66;
        game[0x107] = 0x66;
        assertEquals("Jam Game.gb", PatchSearchActivity.gameFromDownload("Jam Game.gb", game).name);
        java.io.ByteArrayOutputStream zipped = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(zipped)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("readme.txt"));
            zip.write(1);
            zip.putNextEntry(new java.util.zip.ZipEntry("rom/Jam Game.gbc"));
            zip.write(game);
        }
        assertEquals("Jam Game.gbc", PatchSearchActivity.gameFromDownload("jam.zip", zipped.toByteArray()).name);
        // A web page, a Windows build and a file that isn't a game are refused.
        byte[][] refused = {"<!DOCTYPE html><html></html>".getBytes(), new byte[0x8000]};
        for (byte[] download : refused) {
            try {
                PatchSearchActivity.gameFromDownload("download", download);
                org.junit.Assert.fail("Took something that isn't a game");
            } catch (java.io.IOException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    private static WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView found = findWebView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
