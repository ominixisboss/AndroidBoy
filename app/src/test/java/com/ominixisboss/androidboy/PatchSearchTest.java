package com.ominixisboss.androidboy;

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
