package com.ominixisboss.androidboy;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A skin imported from a file: images plus a skin.json saying where the screen and each control
 * are, separately for portrait and landscape. See docs/skins.md for the format.
 */
final class ImageSkin extends Skin {
    static final String MANIFEST = "skin.json";
    /** Images are scaled down on load so their longer side is at most this, to bound memory use. */
    private static final int MAX_IMAGE_SIDE = 2400;

    private static final Map<String, Integer> CONTROL_KEYS = new LinkedHashMap<>();
    static {
        CONTROL_KEYS.put("dpad", Emulator.KEY_UP | Emulator.KEY_DOWN | Emulator.KEY_LEFT | Emulator.KEY_RIGHT);
        CONTROL_KEYS.put("up", Emulator.KEY_UP);
        CONTROL_KEYS.put("down", Emulator.KEY_DOWN);
        CONTROL_KEYS.put("left", Emulator.KEY_LEFT);
        CONTROL_KEYS.put("right", Emulator.KEY_RIGHT);
        CONTROL_KEYS.put("a", Emulator.KEY_A);
        CONTROL_KEYS.put("b", Emulator.KEY_B);
        CONTROL_KEYS.put("ab", Emulator.KEY_A | Emulator.KEY_B);
        CONTROL_KEYS.put("start", Emulator.KEY_START);
        CONTROL_KEYS.put("select", Emulator.KEY_SELECT);
        CONTROL_KEYS.put("menu", KEY_MENU);
        CONTROL_KEYS.put("fastForward", KEY_FAST_FORWARD);
        CONTROL_KEYS.put("rewind", KEY_REWIND);
    }

    /** One orientation's artwork and layout, in the skin's own coordinate space. */
    private static final class Orientation {
        Bitmap image;
        Bitmap pressedImage;
        float width;
        float height;
        final RectF screen = new RectF();
        final Map<String, RectF> controls = new LinkedHashMap<>();
    }

    private static final class Placement {
        Orientation orientation;
        float scale;
        float offsetX;
        float offsetY;
    }

    private final String id;
    private final String name;
    private final int background;
    private final Orientation portrait;
    private final Orientation landscape;
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect source = new Rect();
    private final RectF destination = new RectF();

    private ImageSkin(String id, String name, int background, Orientation portrait, Orientation landscape) {
        this.id = id;
        this.name = name;
        this.background = background;
        this.portrait = portrait;
        this.landscape = landscape;
        highlight.setColor(Color.argb(80, 255, 255, 255));
    }

    /** Loads and validates a skin from an extracted directory. Throws with a user-readable message. */
    static ImageSkin load(File dir) throws IOException {
        return load(dir, "custom:" + dir.getName());
    }

    static ImageSkin load(File dir, String id) throws IOException {
        try {
            JSONObject root = new JSONObject(new String(RomLibrary.readFile(new File(dir, MANIFEST)), "UTF-8"));
            String name = root.optString("name", dir.getName()).trim();
            if (name.isEmpty()) name = dir.getName();
            int background = parseColor(root.optString("backgroundColor", "#000000"));
            Orientation portrait = root.has("portrait") ? parseOrientation(dir, root.getJSONObject("portrait"), "portrait") : null;
            Orientation landscape = root.has("landscape") ? parseOrientation(dir, root.getJSONObject("landscape"), "landscape") : null;
            if (portrait == null && landscape == null) {
                throw new IOException("skin.json needs a \"portrait\" or \"landscape\" layout");
            }
            return new ImageSkin(id, name, background, portrait, landscape);
        } catch (JSONException e) {
            throw new IOException("skin.json is invalid: " + e.getMessage(), e);
        }
    }

    /** Reads only the name, for listing skins without decoding their images. */
    static String readName(File dir) {
        try {
            JSONObject root = new JSONObject(new String(RomLibrary.readFile(new File(dir, MANIFEST)), "UTF-8"));
            String name = root.optString("name", "").trim();
            return name.isEmpty() ? dir.getName() : name;
        } catch (IOException | JSONException e) {
            return dir.getName();
        }
    }

    private static Orientation parseOrientation(File dir, JSONObject json, String what) throws IOException, JSONException {
        Orientation o = new Orientation();
        int[] imageSize = new int[2];
        o.image = decode(dir, json.getString("image"), imageSize);
        if (json.has("pressedImage")) {
            o.pressedImage = decode(dir, json.getString("pressedImage"), new int[2]);
        }
        JSONArray size = json.optJSONArray("size");
        if (size != null) {
            if (size.length() != 2) throw new IOException(what + ": \"size\" must be [width, height]");
            o.width = (float) size.getDouble(0);
            o.height = (float) size.getDouble(1);
        } else {
            o.width = imageSize[0];
            o.height = imageSize[1];
        }
        if (o.width <= 0 || o.height <= 0) throw new IOException(what + ": the size must be positive");

        parseRect(o.screen, json.getJSONArray("screen"), o, what + " screen");
        if (o.screen.width() < 16 || o.screen.height() < 16) throw new IOException(what + ": the screen is too small");

        JSONObject controls = json.getJSONObject("controls");
        for (Iterator<String> it = controls.keys(); it.hasNext(); ) {
            String key = it.next();
            if (!CONTROL_KEYS.containsKey(key)) {
                throw new IOException(what + ": unknown control \"" + key + "\" (known: " + CONTROL_KEYS.keySet() + ")");
            }
            RectF rect = new RectF();
            parseRect(rect, controls.getJSONArray(key), o, what + " " + key);
            o.controls.put(key, rect);
        }
        if (!o.controls.containsKey("menu")) {
            throw new IOException(what + ": a \"menu\" control is required, or the menu can only be opened with Back");
        }
        return o;
    }

    private static void parseRect(RectF out, JSONArray array, Orientation o, String what) throws IOException, JSONException {
        if (array.length() != 4) throw new IOException(what + " must be [x, y, width, height]");
        float x = (float) array.getDouble(0);
        float y = (float) array.getDouble(1);
        float w = (float) array.getDouble(2);
        float h = (float) array.getDouble(3);
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > o.width + 0.5f || y + h > o.height + 0.5f) {
            throw new IOException(what + " is outside the skin (" + o.width + "×" + o.height + ")");
        }
        out.set(x, y, x + w, y + h);
    }

    private static Bitmap decode(File dir, String name, int[] originalSize) throws IOException {
        File file = new File(dir, name);
        // Only files inside the skin's own directory.
        if (!file.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator) || !file.isFile()) {
            throw new IOException("image \"" + name + "\" isn't in the skin");
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("\"" + name + "\" isn't a PNG or JPEG image");
        originalSize[0] = bounds.outWidth;
        originalSize[1] = bounds.outHeight;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > MAX_IMAGE_SIDE) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
        if (bitmap == null) throw new IOException("could not decode \"" + name + "\"");
        return bitmap;
    }

    private static int parseColor(String value) throws IOException {
        try {
            return Color.parseColor(value) | 0xFF000000;
        } catch (IllegalArgumentException e) {
            throw new IOException("backgroundColor \"" + value + "\" isn't a colour like #102030");
        }
    }

    @Override
    String id() {
        return id;
    }

    @Override
    String name() {
        return name;
    }

    @Override
    int backgroundColor() {
        return background;
    }

    /** Skins without a layout for the current orientation use the minimal theme instead. */
    private Orientation orientationFor(int width, int height) {
        return height > width ? portrait : landscape;
    }

    @Override
    void layout(Layout out, int width, int height, int frameWidth, int frameHeight,
                boolean controlsVisible, boolean integerScaling) {
        Orientation o = orientationFor(width, height);
        if (o == null || !controlsVisible) {
            // No artwork for this orientation, or a gamepad is in use: lay out like the minimal theme,
            // on this skin's background colour. Layout.extras then isn't a Placement.
            ThemeSkin.fallback().layout(out, width, height, frameWidth, frameHeight, controlsVisible, integerScaling);
            return;
        }
        out.reset(width, height, true);
        Placement p = new Placement();
        p.orientation = o;
        p.scale = Math.min(width / o.width, height / o.height);
        p.offsetX = (width - o.width * p.scale) / 2;
        p.offsetY = (height - o.height * p.scale) / 2;
        out.extras = p;

        fitScreen(out.screen, map(p, o.screen), frameWidth, frameHeight, integerScaling);
        for (Map.Entry<String, RectF> entry : o.controls.entrySet()) {
            String key = entry.getKey();
            int shape = key.equals("dpad") ? Control.DPAD : Control.RECT;
            out.controls.add(new Control(CONTROL_KEYS.get(key), shape, map(p, entry.getValue()), true));
        }
    }

    private static RectF map(Placement p, RectF r) {
        return new RectF(p.offsetX + r.left * p.scale, p.offsetY + r.top * p.scale,
                p.offsetX + r.right * p.scale, p.offsetY + r.bottom * p.scale);
    }

    @Override
    void drawBackground(Canvas canvas, Layout layout) {
        canvas.drawColor(background);
        if (!(layout.extras instanceof Placement)) return;
        Placement p = (Placement) layout.extras;
        destination.set(p.offsetX, p.offsetY, p.offsetX + p.orientation.width * p.scale,
                p.offsetY + p.orientation.height * p.scale);
        canvas.drawBitmap(p.orientation.image, null, destination, imagePaint);
    }

    @Override
    boolean movableControls() {
        return false; // The buttons are painted into the skin's picture.
    }

    @Override
    void drawControls(Canvas canvas, Layout layout, int pressed, Motion motion) {
        if (!(layout.extras instanceof Placement)) {
            // No artwork for this orientation: show the minimal theme's controls instead.
            ThemeSkin.fallback().drawControls(canvas, layout, pressed, motion);
            return;
        }
        Placement p = (Placement) layout.extras;
        Orientation o = p.orientation;
        int i = 0;
        for (Map.Entry<String, RectF> entry : o.controls.entrySet()) {
            int index = i++;
            Control control = layout.controls.get(index);
            if (control.shape == Control.DPAD) {
                // Follows the pad's tilt, arm by arm.
                drawDpadArms(canvas, o, entry.getValue(), control.bounds, motion);
                continue;
            }
            // Pressed artwork fades in and out with the press animation.
            float amount = Math.min(1, motion.press(index));
            if (amount <= 0.01f) continue;
            int alpha = Math.round(255 * amount);
            if (o.pressedImage != null) {
                // Copy the pressed artwork for just this control's area.
                RectF r = entry.getValue();
                float sx = o.pressedImage.getWidth() / o.width;
                float sy = o.pressedImage.getHeight() / o.height;
                source.set((int) (r.left * sx), (int) (r.top * sy), (int) Math.ceil(r.right * sx), (int) Math.ceil(r.bottom * sy));
                imagePaint.setAlpha(alpha);
                canvas.drawBitmap(o.pressedImage, source, control.bounds, imagePaint);
                imagePaint.setAlpha(255);
            } else {
                highlight.setAlpha(Math.round(80 * amount));
                canvas.drawOval(control.bounds, highlight);
            }
        }
        // L and R for Game Boy Advance games: the artwork has none, so draw them plainly.
        for (int index = 0; index < layout.controls.size(); index++) {
            Control control = layout.controls.get(index);
            if (control.shape != Control.SHOULDER || !control.visible) continue;
            drawShoulder(canvas, control, Math.min(1, motion.press(index)), 0x66FFFFFF, 0x40FFFFFF, 0x99FFFFFF, 0xE6FFFFFF);
        }
    }

    private final RectF armSource = new RectF();
    private final RectF armBounds = new RectF();

    /**
     * Shows the d-pad pressed only where it's held: each arm's third of the control fades in as the
     * pad tilts towards it (both arms on a diagonal), and the middle with whichever is strongest.
     */
    private void drawDpadArms(Canvas canvas, Orientation o, RectF r, RectF bounds, Motion motion) {
        float up = Math.min(1, Math.max(0, -motion.tiltY));
        float down = Math.min(1, Math.max(0, motion.tiltY));
        float left = Math.min(1, Math.max(0, -motion.tiltX));
        float right = Math.min(1, Math.max(0, motion.tiltX));
        drawDpadPart(canvas, o, r, bounds, 1, 0, up);
        drawDpadPart(canvas, o, r, bounds, 1, 2, down);
        drawDpadPart(canvas, o, r, bounds, 0, 1, left);
        drawDpadPart(canvas, o, r, bounds, 2, 1, right);
        drawDpadPart(canvas, o, r, bounds, 1, 1, Math.max(Math.max(up, down), Math.max(left, right)));
    }

    /** Draws cell ({@code column}, {@code row}) of the d-pad's 3×3 grid pressed, at {@code amount} opacity. */
    private void drawDpadPart(Canvas canvas, Orientation o, RectF r, RectF bounds, int column, int row, float amount) {
        if (amount <= 0.01f) return;
        armSource.set(r.left + r.width() * column / 3, r.top + r.height() * row / 3,
                r.left + r.width() * (column + 1) / 3, r.top + r.height() * (row + 1) / 3);
        armBounds.set(bounds.left + bounds.width() * column / 3, bounds.top + bounds.height() * row / 3,
                bounds.left + bounds.width() * (column + 1) / 3, bounds.top + bounds.height() * (row + 1) / 3);
        if (o.pressedImage != null) {
            float sx = o.pressedImage.getWidth() / o.width;
            float sy = o.pressedImage.getHeight() / o.height;
            source.set((int) (armSource.left * sx), (int) (armSource.top * sy),
                    (int) Math.ceil(armSource.right * sx), (int) Math.ceil(armSource.bottom * sy));
            imagePaint.setAlpha(Math.round(255 * amount));
            canvas.drawBitmap(o.pressedImage, source, armBounds, imagePaint);
            imagePaint.setAlpha(255);
        } else {
            highlight.setAlpha(Math.round(80 * amount));
            float corner = armBounds.width() * 0.25f;
            canvas.drawRoundRect(armBounds, corner, corner, highlight);
        }
    }
}
