package com.ominixisboss.androidboy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** The settings dialogs: categories, changing a value, and staying open while doing so. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SettingsTest {

    @Test
    public void everySettingIsInExactlyOneCategory() throws IllegalAccessException {
        List<Settings.Choice> all = new ArrayList<>();
        for (Field field : Settings.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Settings.Choice.class) {
                all.add((Settings.Choice) field.get(null));
            }
        }
        assertTrue(all.size() >= 16);
        for (Settings.Choice choice : all) {
            int found = 0;
            for (Settings.Category category : Settings.CATEGORIES) {
                for (Settings.Choice c : category.choices) if (c == choice) found++;
            }
            assertEquals(choice.title + " should be in one category", 1, found);
        }
        for (Settings.Category category : Settings.CATEGORIES) {
            assertTrue(category.title + " is short", category.choices.length <= 6);
        }
    }

    @Test
    public void changingASettingKeepsItsListOpenAndUpdated() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Settings settings = new Settings(activity);
        List<Settings.Choice> changed = new ArrayList<>();
        int[] dismissed = {0};
        settings.showDialog(activity, changed::add, () -> dismissed[0]++);

        // Top level: the four categories, each with a summary line.
        AlertDialog top = latest();
        ListView categories = listIn(top);
        assertEquals(Settings.CATEGORIES.length, categories.getAdapter().getCount());
        assertRow(categories, 1, "Emulation", Settings.CATEGORIES[1].summary);

        // Emulation: rewind defaults to 30 seconds.
        categories.performItemClick(null, 1, 1);
        idle();
        AlertDialog emulation = latest();
        ListView rows = listIn(emulation);
        int rewindRow = indexOf(Settings.CATEGORIES[1], Settings.REWIND);
        assertRow(rows, rewindRow, "Rewind", "30 seconds");

        // Pick "Off": the value is saved and reported, and the Emulation list stays up, updated.
        rows.performItemClick(null, rewindRow, rewindRow);
        idle();
        AlertDialog choice = latest();
        choice.getListView().performItemClick(null, 0, 0);
        idle();
        assertEquals(0, settings.get(Settings.REWIND));
        assertEquals(List.of(Settings.REWIND), changed);
        assertTrue("choice closes", !choice.isShowing());
        assertTrue("category stays open", emulation.isShowing());
        assertTrue("settings stay open", top.isShowing());
        assertRow(rows, rewindRow, "Rewind", "Off");
        assertEquals(0, dismissed[0]);

        // Back to the categories, then Done: the caller hears about it once.
        emulation.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        assertTrue(top.isShowing());
        assertEquals(0, dismissed[0]);
        top.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
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

    private static int indexOf(Settings.Category category, Settings.Choice choice) {
        for (int i = 0; i < category.choices.length; i++) {
            if (category.choices[i] == choice) return i;
        }
        throw new AssertionError(choice.title + " not in " + category.title);
    }
}
