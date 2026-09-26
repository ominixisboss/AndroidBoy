package com.ominixisboss.androidboy;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.RectF;

import java.util.List;
import java.util.Locale;

/**
 * The player's changes to where the on-screen buttons are: each control can be moved and resized,
 * separately for each skin in portrait and landscape. Stored as an offset (a fraction of the
 * view's width and height, so it survives other screen sizes) and a scale.
 */
final class ControlLayout {
    static final float MIN_SCALE = 0.5f;
    static final float MAX_SCALE = 2.5f;

    /** How one control is changed from where the skin puts it. */
    static final class Adjustment {
        float dx;
        float dy;
        float scale = 1;

        boolean isIdentity() {
            return dx == 0 && dy == 0 && scale == 1;
        }
    }

    private final SharedPreferences prefs;

    ControlLayout(Context context) {
        prefs = context.getSharedPreferences("control_layout", Context.MODE_PRIVATE);
    }

    static String orientation(int width, int height) {
        return width > height ? "landscape" : "portrait";
    }

    private static String key(String skinId, String orientation, int keys) {
        return skinId + "|" + orientation + "|" + keys;
    }

    Adjustment get(String skinId, String orientation, int keys) {
        Adjustment adjustment = new Adjustment();
        String value = prefs.getString(key(skinId, orientation, keys), null);
        if (value == null) return adjustment;
        String[] parts = value.split(",");
        if (parts.length != 3) return adjustment;
        try {
            adjustment.dx = Float.parseFloat(parts[0]);
            adjustment.dy = Float.parseFloat(parts[1]);
            adjustment.scale = clampScale(Float.parseFloat(parts[2]));
        } catch (NumberFormatException e) {
            return new Adjustment();
        }
        return adjustment;
    }

    void put(String skinId, String orientation, int keys, Adjustment adjustment) {
        String key = key(skinId, orientation, keys);
        if (adjustment.isIdentity()) {
            prefs.edit().remove(key).apply();
        } else {
            prefs.edit().putString(key, String.format(Locale.ROOT, "%.5f,%.5f,%.4f",
                    adjustment.dx, adjustment.dy, adjustment.scale)).apply();
        }
    }

    /** Puts a skin's buttons back where it had them, in one orientation. */
    void reset(String skinId, String orientation) {
        String prefix = skinId + "|" + orientation + "|";
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(prefix)) editor.remove(key);
        }
        editor.apply();
    }

    static float clampScale(float scale) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    /**
     * Moves and resizes {@code controls} from where the skin put them ({@code base}, same order).
     * The invisible spot between A and B follows the two buttons.
     */
    void apply(String skinId, int width, int height, List<Skin.Control> controls, List<RectF> base) {
        String orientation = orientation(width, height);
        for (int i = 0; i < controls.size(); i++) {
            Skin.Control control = controls.get(i);
            if (!control.visible) continue;
            transform(base.get(i), get(skinId, orientation, control.keys), width, height, control.bounds);
        }
        RectF a = null;
        RectF b = null;
        for (Skin.Control control : controls) {
            if (control.visible && control.keys == Emulator.KEY_A) a = control.bounds;
            if (control.visible && control.keys == Emulator.KEY_B) b = control.bounds;
        }
        if (a == null || b == null) return;
        for (Skin.Control control : controls) {
            if (!control.visible && control.keys == (Emulator.KEY_A | Emulator.KEY_B)) {
                // A small target halfway between the buttons, as the skins make it.
                float size = Math.min(a.width(), b.width()) * 0.6f;
                float cx = (a.centerX() + b.centerX()) / 2;
                float cy = (a.centerY() + b.centerY()) / 2;
                control.bounds.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
            }
        }
    }

    /** {@code base} moved by the adjustment's offset and scaled about its centre, kept on screen. */
    static void transform(RectF base, Adjustment adjustment, int width, int height, RectF out) {
        float halfWidth = base.width() / 2 * adjustment.scale;
        float halfHeight = base.height() / 2 * adjustment.scale;
        float cx = base.centerX() + adjustment.dx * width;
        float cy = base.centerY() + adjustment.dy * height;
        // Keep at least the middle of the control on screen.
        cx = Math.max(0, Math.min(width, cx));
        cy = Math.max(0, Math.min(height, cy));
        out.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight);
    }
}
