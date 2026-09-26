package com.ominixisboss.androidboy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/** The skin picker: skins grouped into categories, and choosing one. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
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
        assertEquals(entries.size(), colourList.getListView().getAdapter().getCount());
        int berry = -1;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id.equals("theme:berry")) berry = i;
        assertTrue(berry >= 0);
        colourList.getListView().performItemClick(null, berry, berry);
        idle();
        assertEquals("theme:berry", library.activeId());
        assertEquals(1, changed[0]);
        assertFalse(colourList.isShowing());
        assertFalse(top.isShowing());
        assertEquals(1, dismissed[0]);
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
