package com.ominixisboss.androidboy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** The game screen's toolbars and full-screen menu; also writes previews next to the skin previews. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GameChromeTest {
    private static final int WIDTH = 1233;
    private static final int HEIGHT = 2673;

    private static final class Recorder implements GameToolbars.Actions {
        final List<String> calls = new ArrayList<>();
        @Override public void onBack() { calls.add("back"); }
        @Override public void onMenu() { calls.add("menu"); }
        @Override public void onAchievements() { calls.add("achievements"); }
        @Override public void onLinkCable() { calls.add("link"); }
        @Override public void onScreenshot() { calls.add("screenshot"); }
        @Override public void onRotationLock() { calls.add("lock"); }
        @Override public void onSpeed(int speed) { calls.add("speed" + speed); }
        @Override public void onPause() { calls.add("pause"); }
        @Override public void onRewind(boolean held) { calls.add("rewind" + held); }
        @Override public void onMute() { calls.add("mute"); }
        @Override public void onFullScreen() { calls.add("fullscreen"); }
    }

    @Test
    public void toolbarsAroundTheSoftSkin() throws IOException {
        Application app = RuntimeEnvironment.getApplication();
        for (SoftSkin skin : new SoftSkin[] {SoftSkin.find("slate"), SoftSkin.find("blush"), SoftSkin.find("midnight")}) {
            Recorder recorder = new Recorder();
            GameToolbars toolbars = new GameToolbars(app, recorder);
            toolbars.setTitle("Infinity");
            toolbars.setMuted(false);
            toolbars.setPaused(false);
            LinearLayout column = new LinearLayout(app);
            column.setOrientation(LinearLayout.VERTICAL);
            column.addView(toolbars.top);
            FrameLayout area = new FrameLayout(app);
            area.addView(new SkinPreview(app, skin), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            column.addView(area, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            column.addView(toolbars.bottom);
            Bitmap bitmap = render(column);
            write(bitmap, "chrome-" + skin.id().replace(':', '-') + ".png");
            // Every toolbar button does something.
            clickAll(toolbars.top, recorder);
            clickAll(toolbars.bottom, recorder);
            assertTrue(recorder.calls.toString(), recorder.calls.containsAll(java.util.Arrays.asList(
                    "back", "menu", "achievements", "link", "screenshot", "lock", "speed0", "speed1", "speed2",
                    "pause", "mute", "fullscreen")));
            toolbars.setVisible(false);
            assertEquals(View.GONE, toolbars.top.getVisibility());
        }
    }

    @Test
    public void menu() throws IOException {
        Application app = RuntimeEnvironment.getApplication();
        List<String> chosen = new ArrayList<>();
        List<GameMenuView.Section> sections = new ArrayList<>();
        sections.add(new GameMenuView.Section("Quick access")
                .add(Icons.SAVE, "Save state", "Save this moment to one of 9 slots", () -> chosen.add("save"))
                .add(Icons.LOAD, "Load state", "Go back to a saved moment", () -> { })
                .add(Icons.CHEAT, "Cheats", "Add, turn on or find cheat codes", () -> { })
                .add(Icons.SEARCH, "Cheat search", "Find where the game keeps lives, money or health", () -> { }));
        sections.add(new GameMenuView.Section("Input")
                .add(Icons.GAMEPAD, "Controller buttons", "Choose what each button does", () -> { })
                .add(Icons.TOUCH, "Touch controls", "Move and resize the on-screen buttons", () -> { })
                .add(Icons.TURBO, "Turbo", "Make A or B fire again and again while held", () -> { }));
        sections.add(new GameMenuView.Section("Empty"));
        sections.add(new GameMenuView.Section("Display")
                .add(Icons.SKIN, "Skin", "Change how the game screen looks", () -> { })
                .add(Icons.SETTINGS, "Settings", "Screen filter, colours, sound, emulation", () -> { }));
        boolean[] closed = {false};
        GameMenuView menu = new GameMenuView(app, "Game Menu", sections, new GameMenuView.Listener() {
            @Override public void onItemChosen(GameMenuView.Item item) {
                item.action.run();
            }

            @Override public void onClose() {
                closed[0] = true;
            }
        });
        write(render(menu), "chrome-menu.png");
        List<String> texts = new ArrayList<>();
        collectTexts(menu, texts);
        assertTrue(texts.contains("Game Menu"));
        assertTrue(texts.contains("Quick access"));
        assertFalse("empty sections are left out", texts.contains("Empty"));
        assertTrue(texts.contains("Move and resize the on-screen buttons"));
        // Tapping "Save state" runs it; the close button closes.
        View save = findRow(menu, "Save state");
        save.performClick();
        assertEquals(1, chosen.size());
        clickDescribed(menu, "Close the menu");
        assertTrue(closed[0]);
    }

    @Test
    public void iconsDraw() {
        Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        for (int type = 0; type < Icons.COUNT; type++) {
            bitmap.eraseColor(Color.TRANSPARENT);
            android.graphics.drawable.Drawable icon = Icons.drawable(type, Color.WHITE);
            icon.setBounds(0, 0, 48, 48);
            icon.draw(new Canvas(bitmap));
            int painted = 0;
            for (int y = 0; y < 48; y++) for (int x = 0; x < 48; x++) if (Color.alpha(bitmap.getPixel(x, y)) > 0) painted++;
            assertTrue("icon " + type + " draws something", painted > 20);
        }
    }

    /** A skin drawn the way the game screen draws it, with a stand-in game picture. */
    private static final class SkinPreview extends View {
        private final Skin skin;

        SkinPreview(android.content.Context context, Skin skin) {
            super(context);
            this.skin = skin;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Skin.Layout layout = new Skin.Layout();
            skin.layout(layout, getWidth(), getHeight(), 160, 144, true, false);
            skin.drawBackground(canvas, layout);
            Rect s = layout.screen;
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setShader(new android.graphics.LinearGradient(0, s.top, 0, s.bottom, 0xFF2D7BD8, 0xFF0B2E6B,
                    android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(s, paint);
            Skin.Motion motion = new Skin.Motion();
            motion.snap(layout, 0);
            skin.drawControls(canvas, layout, 0, motion);
        }
    }

    private static Bitmap render(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, WIDTH, HEIGHT);
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLACK);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static void write(Bitmap bitmap, String name) throws IOException {
        File out = new File(System.getProperty("skinPreviewDir", "build/skin-previews"));
        out.mkdirs();
        try (OutputStream stream = new FileOutputStream(new File(out, name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
    }

    private static void clickAll(View view, Recorder recorder) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) clickAll(group.getChildAt(i), recorder);
        } else if (view.hasOnClickListeners()) {
            view.performClick();
        }
    }

    private static void collectTexts(View view, List<String> texts) {
        if (view instanceof TextView) texts.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectTexts(group.getChildAt(i), texts);
        }
    }

    /** The clickable row holding a text. */
    private static View findRow(View view, String text) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            List<String> texts = new ArrayList<>();
            collectTexts(view, texts);
            if (view.hasOnClickListeners() && texts.contains(text)) return view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findRow(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void clickDescribed(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) {
            view.performClick();
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) clickDescribed(group.getChildAt(i), description);
        }
    }
}
