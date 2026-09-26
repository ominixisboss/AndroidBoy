package com.ominixisboss.androidboy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import android.widget.ListView;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/** The skin picker: skins grouped into categories, and choosing one. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Previews are really drawn.
public class SkinPickerTest {

    @Test
    public void everyThemeIsInOneCategory() {
        List<String> categories = List.of(SkinLibrary.CATEGORIES);
        int total = 0;
        for (ThemeSkin theme : ThemeSkin.ALL) {
            assertTrue(theme.id() + " has a known category", categories.contains(theme.category()));
        }
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SkinLibrary library = new SkinLibrary(activity);
        for (String category : SkinLibrary.CATEGORIES) {
            List<SkinLibrary.Entry> entries = library.list(category);
            for (SkinLibrary.Entry entry : entries) assertEquals(category, entry.category);
            total += entries.size();
            if (!category.equals(SkinLibrary.ARTWORK) && !category.equals(SkinLibrary.IMPORTED)) {
                assertTrue(category + " has a few themes", entries.size() >= 5);
            }
        }
        assertEquals("the categories hold every skin", library.list().size(), total);
    }

    @Test
    public void choosingASkinFromACategory() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SkinLibrary library = new SkinLibrary(activity);
        List<String> shown = new ArrayList<>();
        for (String category : SkinLibrary.CATEGORIES) {
            if (!library.list(category).isEmpty()) shown.add(category);
        }
        assertFalse("nothing imported, so no Imported group", shown.contains(SkinLibrary.IMPORTED));
        int[] changed = {0};
        int[] dismissed = {0};
        SkinPicker.show(activity, library, 1, new SkinPicker.Callbacks() {
            @Override
            public void onSkinChanged() {
                changed[0]++;
            }

            @Override
            public void onDismissed() {
                dismissed[0]++;
            }
        });

        // Categories, with a count; the one holding the current skin (Minimal) says so.
        AlertDialog top = latest();
        ListView categories = listIn(top);
        assertEquals(shown.size(), categories.getAdapter().getCount());
        int modern = shown.indexOf(ThemeSkin.MODERN);
        int colours = shown.indexOf(ThemeSkin.COLOURS);
        assertRow(categories, modern, ThemeSkin.MODERN,
                library.list(ThemeSkin.MODERN).size() + " skins · using Minimal (translucent controls)");
        assertRow(categories, colours, ThemeSkin.COLOURS, library.list(ThemeSkin.COLOURS).size() + " skins");

        // Colours lists only colour themes; picking one applies it and closes the picker.
        categories.performItemClick(null, colours, colours);
        idle();
        AlertDialog colourList = latest();
        List<SkinLibrary.Entry> entries = library.list(ThemeSkin.COLOURS);
        GridView grid = find(colourList.getWindow().getDecorView(), GridView.class);
        assertNotNull("skins are shown as a grid of previews", grid);
        assertEquals(entries.size(), grid.getAdapter().getCount());
        // Each card shows the skin's name; its preview is drawn right after.
        View card = grid.getAdapter().getView(0, null, grid);
        assertEquals(entries.get(0).name, find(card, TextView.class).getText().toString());
        idle();
        assertNotNull("the first card's preview was drawn", SkinPreviews.cached(entries.get(0).id));
        writePreview(colourList, "picker-colours.png");
        int berry = -1;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id.equals("theme:berry")) berry = i;
        assertTrue(berry >= 0);
        grid.performItemClick(null, berry, berry);
        idle();
        assertEquals("theme:berry", library.activeId());
        assertEquals(1, changed[0]);
        assertFalse(colourList.isShowing());
        assertFalse(top.isShowing());
        assertEquals(1, dismissed[0]);
    }

    @Test
    public void frostedAndSmokeBecameGlassSkins() {
        SkinLibrary library = new SkinLibrary(org.robolectric.RuntimeEnvironment.getApplication());
        library.setActive("theme:glass");
        assertEquals("glass:frosted", library.activeId());
        assertTrue(library.loadActive() instanceof GlassSkin);
        library.setActive("theme:smoke");
        assertEquals("glass:smoke", library.loadActive().id());
        assertTrue(library.list(GlassSkin.CATEGORY).size() >= 10);
    }

    @Test
    public void clearSkinsAreListedAndLoad() {
        SkinLibrary library = new SkinLibrary(org.robolectric.RuntimeEnvironment.getApplication());
        List<SkinLibrary.Entry> clear = library.list(ClearSkin.CATEGORY);
        assertTrue(clear.size() >= 10);
        for (SkinLibrary.Entry entry : clear) assertTrue(entry.id, library.load(entry.id) instanceof ClearSkin);
        library.setActive("clear:atomic");
        assertEquals("Atomic purple", library.loadActive().name());
    }

    @Test
    public void previewsLookLikeTheirSkins() {
        Bitmap soft = SkinPreviews.render(SoftSkin.find("blush"), SkinPreviews.WIDTH, SkinPreviews.HEIGHT);
        Bitmap theme = SkinPreviews.render(ThemeSkin.find("kiwi"), SkinPreviews.WIDTH, SkinPreviews.HEIGHT);
        assertEquals(SkinPreviews.WIDTH, soft.getWidth());
        assertEquals(SkinPreviews.HEIGHT, soft.getHeight());
        // Each shows its own colours (bottom corner is background), and the green stand-in screen.
        int corner = soft.getPixel(4, SkinPreviews.HEIGHT - 4);
        int background = SoftSkin.find("blush").backgroundColor();
        // The background's grain varies it a little.
        assertTrue(Math.abs(android.graphics.Color.red(corner) - android.graphics.Color.red(background)) < 16
                && Math.abs(android.graphics.Color.blue(corner) - android.graphics.Color.blue(background)) < 16);
        assertTrue(soft.getPixel(4, SkinPreviews.HEIGHT - 4) != theme.getPixel(4, SkinPreviews.HEIGHT - 4));
        Skin.Layout layout = new Skin.Layout();
        ThemeSkin.find("kiwi").layout(layout, SkinPreviews.WIDTH, SkinPreviews.HEIGHT, 160, 144, true, false);
        assertEquals(0xFF9BBC0F, theme.getPixel(layout.screen.left + 3, layout.screen.top + 3));
        // Cached once drawn.
        assertTrue(SkinPreviews.get(SoftSkin.find("sage")) == SkinPreviews.get(SoftSkin.find("sage")));
    }

    /** A picture of the dialog, next to the skin previews. */
    private static void writePreview(AlertDialog dialog, String name) {
        View decor = dialog.getWindow().getDecorView();
        int width = 1080;
        int height = 2000;
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
        idle(); // Previews drawn for the cards just laid out.
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(decor.getMeasuredWidth(), decor.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFFFFFFFF);
        decor.draw(new android.graphics.Canvas(bitmap));
        java.io.File out = new java.io.File(System.getProperty("skinPreviewDir", "build/skin-previews"));
        out.mkdirs();
        try (java.io.OutputStream stream = new java.io.FileOutputStream(new java.io.File(out, name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void idle() {
        ShadowLooper.idleMainLooper();
    }

    private static AlertDialog latest() {
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        return (AlertDialog) dialog;
    }

    private static ListView listIn(AlertDialog dialog) {
        ListView list = find(dialog.getWindow().getDecorView(), ListView.class);
        assertNotNull("dialog has a list", list);
        return list;
    }

    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void assertRow(ListView list, int position, String title, String detail) {
        View row = list.getAdapter().getView(position, null, list);
        assertEquals(title, ((TextView) row.findViewById(android.R.id.text1)).getText().toString());
        assertEquals(detail, ((TextView) row.findViewById(android.R.id.text2)).getText().toString());
    }
}
