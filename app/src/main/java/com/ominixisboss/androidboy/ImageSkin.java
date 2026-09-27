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
        CONTROL_KEYS.put("speaker", Skin.KEY_SPEAKER);
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
        /** Where a camera cutout at the top of the display should sit in the picture; null if not given. */
        float[] camera;
        /** How much of the picture, below {@link #height}, is extra body that needn't be shown. */
        float below;
        /** The picture without its controls, so they can be moved; null if the skin has none. */
        Bitmap bare;
        /** Each control's artwork lifted off the picture (and pressed), made when first moved. */
        final Map<String, Bitmap> sprites = new java.util.HashMap<>();
        final Map<String, Bitmap> pressedSprites = new java.util.HashMap<>();

        /** The whole picture's height, extra body included. */
        float fullHeight() {
            return height + below;
        }
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
    /** The d-pad drawn live over the picture, in 3D; null when the picture's own d-pad is used. */
    private final Dpad3D dpad;
    /** The buttons drawn live over the picture, in 3D; null when the picture's own buttons are used. */
    private final Buttons buttons;

    /**
     * {@code "buttonStyle"}: A, B, Start, Select and the menu and speed buttons drawn live as 3D
     * buttons (see {@link Button3D}); the picture then only shows what's under them.
     */
    static final class Buttons {
        final Button3D a;
        final Button3D b;
        final Button3D pills;
        final Button3D utility;
        final int letters;
        final int icons;
        final int surround;
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private Buttons(Button3D a, Button3D b, Button3D pills, Button3D utility, int letters, int icons, int surround) {
            this.a = a;
            this.b = b;
            this.pills = pills;
            this.utility = utility;
            this.letters = letters;
            this.icons = icons;
            this.surround = surround;
        }

        static Buttons parse(JSONObject json, int background) throws IOException {
            String material = json.optString("material", "plastic");
            if (!material.equals("plastic") && !material.equals("rubber")) {
                throw new IOException("buttonStyle: \"material\" must be \"plastic\" or \"rubber\"");
            }
            int m = material.equals("rubber") ? Button3D.RUBBER : Button3D.PLASTIC;
            int a = color(json, "a", 0xFFC0304A);
            int pills = color(json, "pills", 0xFF505058);
            return new Buttons(new Button3D(a, m), new Button3D(color(json, "b", a), m),
                    new Button3D(pills, Button3D.RUBBER), new Button3D(color(json, "utility", pills), Button3D.RUBBER),
                    color(json, "letters", 0xFFFFFFFF), color(json, "icons", 0xFFFFFFFF),
                    json.has("surround") ? color(json, "surround", background) : background);
        }

        private static int color(JSONObject json, String key, int fallback) throws IOException {
            if (!json.has(key)) return fallback;
            try {
                return Color.parseColor(json.optString(key)) | 0xFF000000;
            } catch (IllegalArgumentException e) {
                throw new IOException("buttonStyle: \"" + key + "\" isn't a colour like #102030");
            }
        }

        /** Draws {@code control} if it's one of the buttons this style draws; false if it isn't. */
        boolean draw(Canvas canvas, Control control, float press) {
            RectF r = control.bounds;
            int keys = control.keys;
            if (keys == Emulator.KEY_A || keys == Emulator.KEY_B) {
                Button3D button = keys == Emulator.KEY_A ? a : b;
                float radius = Math.min(r.width(), r.height()) / 2 * 0.9f;
                button.drawRound(canvas, r.centerX(), r.centerY(), radius, press, surround);
                button.drawLetter(canvas, keys == Emulator.KEY_A ? "A" : "B", r.centerX(), r.centerY(), radius, press, letters);
                return true;
            }
            if (keys == Emulator.KEY_START || keys == Emulator.KEY_SELECT) {
                RectF pill = scaled(r, 0.78f, 0.5f);
                pills.drawPill(canvas, pill, press, surround);
                return true;
            }
            if (keys == KEY_MENU || keys == KEY_FAST_FORWARD || keys == KEY_REWIND) {
                RectF pill = scaled(r, 0.9f, 0.72f);
                float capY = utility.drawPill(canvas, pill, press, surround);
                Button3D.drawIcon(canvas, iconPaint, keys, pill.centerX(), capY, pill.height() * 0.55f, icons);
                return true;
            }
            return false;
        }

        private static RectF scaled(RectF r, float sx, float sy) {
            float hw = r.width() * sx / 2;
            float hh = r.height() * sy / 2;
            return new RectF(r.centerX() - hw, r.centerY() - hh, r.centerX() + hw, r.centerY() + hh);
        }
    }
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Matrix tiltMatrix = new android.graphics.Matrix();
    private final RectF edge = new RectF();
    private final float[] tilted = new float[8];
    private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect source = new Rect();
    private final RectF destination = new RectF();

    private ImageSkin(String id, String name, int background, Orientation portrait, Orientation landscape, Dpad3D dpad,
                      Buttons buttons) {
        this.id = id;
        this.dpad = dpad;
        this.buttons = buttons;
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
            JSONObject dpadStyle = root.optJSONObject("dpadStyle");
            Dpad3D dpad = dpadStyle != null ? Dpad3D.parse(dpadStyle) : null;
            JSONObject buttonStyle = root.optJSONObject("buttonStyle");
            Buttons buttons = buttonStyle != null ? Buttons.parse(buttonStyle, background) : null;
            return new ImageSkin(id, name, background, portrait, landscape, dpad, buttons);
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
        if (json.has("bareImage")) {
            o.bare = decode(dir, json.getString("bareImage"), new int[2]);
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
        // Extra body below the controls, shown on screens taller than the rest of the picture.
        o.below = (float) json.optDouble("extendsBelow", 0);
        if (o.below < 0 || o.below >= o.height / 2) {
            throw new IOException(what + ": \"extendsBelow\" must be less than half the picture's height");
        }
        o.height -= o.below;

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
        JSONArray camera = json.optJSONArray("camera");
        if (camera != null) {
            if (camera.length() != 2) throw new IOException(what + ": \"camera\" must be [x, y]");
            float x = (float) camera.getDouble(0);
            float y = (float) camera.getDouble(1);
            if (x < 0 || y < 0 || x > o.width || y >= o.screen.top) {
                throw new IOException(what + ": \"camera\" must be inside the picture, above the screen");
            }
            o.camera = new float[] {x, y};
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
        int inset = out.topInset;
        if (o == null || !controlsVisible) {
            // No artwork for this orientation, or a gamepad is in use: lay out like the minimal theme,
            // on this skin's background colour. Layout.extras then isn't a Placement.
            Skin fallback = ThemeSkin.fallback();
            fallback.layout(out, width, height - inset, frameWidth, frameHeight, controlsVisible, integerScaling);
            fallback.moveDown(out, inset);
            return;
        }
        out.reset(width, height, true);
        out.extras = place(o, width, height, inset, out.topCutout);
        Placement p = (Placement) out.extras;

        fitScreen(out.screen, map(p, o.screen), frameWidth, frameHeight, integerScaling);
        for (Map.Entry<String, RectF> entry : o.controls.entrySet()) {
            String key = entry.getKey();
            int shape = key.equals("dpad") ? Control.DPAD : key.equals("speaker") ? Control.SPEAKER : Control.RECT;
            // The spot between A and B only exists for touch, as in every skin: the layout editor
            // doesn't offer it, and it follows A and B when they're moved.
            out.controls.add(new Control(CONTROL_KEYS.get(key), shape, map(p, entry.getValue()), !key.equals("ab")));
        }
    }

    @Override
    boolean handlesTopInset() {
        return true;
    }

    /**
     * Where the picture goes in a {@code width}×{@code height} view whose top {@code inset} pixels
     * are behind a camera cutout ({@code cutout}, empty if unknown). A picture that marks where the
     * camera goes is lined up with it, reaching up behind it; others fit in the space below.
     */
    private static Placement place(Orientation o, int width, int height, int inset, RectF cutout) {
        Placement p = new Placement();
        p.orientation = o;
        if (o.camera == null || inset <= 0) {
            float top = Math.max(0, inset);
            float room = height - top;
            p.scale = Math.min(width / o.width, room / o.height);
            p.offsetX = (width - o.width * p.scale) / 2;
            p.offsetY = o.camera != null ? 0 : top + (room - o.height * p.scale) / 2;
            return p;
        }
        // Big enough to fill the width, but small enough that the screen clears the cutout with
        // the whole picture still on screen.
        p.scale = Math.min(width / o.width, Math.min(height / o.height, (height - inset) / (o.height - o.screen.top)));
        p.offsetX = (width - o.width * p.scale) / 2;
        float cameraY = cutout.isEmpty() ? inset / 2f : cutout.centerY();
        float lowest = inset - o.screen.top * p.scale;          // Any higher and the screen is under the cutout.
        float highest = Math.max(lowest, height - o.height * p.scale); // Any lower and the bottom is cut off.
        p.offsetY = Math.max(lowest, Math.min(highest, cameraY - o.camera[1] * p.scale));
        return p;
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
                p.offsetY + p.orientation.fullHeight() * p.scale);
        Bitmap image = p.orientation.image;
        canvas.drawBitmap(image, null, destination, imagePaint);
        if (p.orientation.camera != null) {
            // A picture made to line up with the camera: its top and bottom edges carry on to the
            // edges of the view, so the body looks continuous on phones taller than the picture.
            if (destination.top > 0.5f) {
                source.set(0, 0, image.getWidth(), 1);
                edge.set(destination.left, 0, destination.right, destination.top + 1);
                canvas.drawBitmap(image, source, edge, imagePaint);
            }
            if (destination.bottom < layout.height - 0.5f) {
                source.set(0, image.getHeight() - 1, image.getWidth(), image.getHeight());
                edge.set(destination.left, destination.bottom - 1, destination.right, layout.height);
                canvas.drawBitmap(image, source, edge, imagePaint);
            }
        }
        drawMovedControls(canvas, layout, p);
    }

    /**
     * The buttons are painted into the picture: they can move when the skin also has the picture
     * without them, to fill in where they were.
     */
    @Override
    boolean movableControls() {
        return (portrait == null || portrait.bare != null) && (landscape == null || landscape.bare != null);
    }

    // ---- Moved controls ----

    /** The part of the picture a control's artwork covers: its rectangle, plus room for its well, shadow and label. */
    private static RectF artworkArea(RectF r, Orientation o) {
        float margin = Math.max(Math.max(r.width(), r.height()) * 0.12f, 30);
        return new RectF(Math.max(0, r.left - margin), Math.max(0, r.top - margin),
                Math.min(o.width, r.right + margin), Math.min(o.fullHeight(), r.bottom + margin));
    }

    /** Where a control's artwork goes on screen, following the control from {@code base} to {@code bounds}. */
    private static RectF movedArea(RectF area, RectF r, Placement p, RectF bounds) {
        float k = bounds.width() / Math.max(1e-3f, r.width() * p.scale) * p.scale;
        return new RectF(bounds.left - (r.left - area.left) * k, bounds.top - (r.top - area.top) * k,
                bounds.right + (area.right - r.right) * k, bounds.bottom + (area.bottom - r.bottom) * k);
    }

    private static boolean moved(RectF bounds, RectF base) {
        return Math.abs(bounds.left - base.left) > 0.5f || Math.abs(bounds.top - base.top) > 0.5f
                || Math.abs(bounds.width() - base.width()) > 0.5f;
    }

    /**
     * A control's artwork from {@code picture}: see-through wherever it matches the bare picture,
     * so its well, shadow and label come with it but not the body around them.
     */
    private static Bitmap lift(Bitmap picture, Bitmap bare, RectF area, Orientation o) {
        float sx = picture.getWidth() / o.width;
        float sy = picture.getHeight() / o.fullHeight();
        int left = Math.max(0, (int) (area.left * sx));
        int top = Math.max(0, (int) (area.top * sy));
        int w = Math.max(1, Math.min(picture.getWidth() - left, (int) Math.ceil(area.width() * sx)));
        int h = Math.max(1, Math.min(picture.getHeight() - top, (int) Math.ceil(area.height() * sy)));
        int[] art = new int[w * h];
        picture.getPixels(art, 0, w, left, top, w, h);
        // The bare picture may be a different size: sample it at the same places.
        Bitmap plain = Bitmap.createScaledBitmap(Bitmap.createBitmap(bare,
                Math.max(0, (int) (area.left * bare.getWidth() / o.width)),
                Math.max(0, (int) (area.top * bare.getHeight() / o.fullHeight())),
                Math.max(1, Math.min(bare.getWidth() - (int) (area.left * bare.getWidth() / o.width),
                        (int) Math.ceil(area.width() * bare.getWidth() / o.width))),
                Math.max(1, Math.min(bare.getHeight() - (int) (area.top * bare.getHeight() / o.fullHeight()),
                        (int) Math.ceil(area.height() * bare.getHeight() / o.fullHeight())))), w, h, true);
        int[] under = new int[w * h];
        plain.getPixels(under, 0, w, 0, 0, w, h);
        for (int i = 0; i < art.length; i++) {
            int a = art[i];
            int b = under[i];
            int d = Math.max(Math.abs(Color.red(a) - Color.red(b)),
                    Math.max(Math.abs(Color.green(a) - Color.green(b)), Math.abs(Color.blue(a) - Color.blue(b))));
            // Small differences are compression noise; from there it fades in.
            int alpha = Math.max(0, Math.min(255, (d - 10) * 255 / 30));
            art[i] = (alpha << 24) | (a & 0xFFFFFF);
        }
        return Bitmap.createBitmap(art, w, h, Bitmap.Config.ARGB_8888);
    }

    /**
     * Fills in where moved controls were, from the bare picture, then draws their artwork where
     * they are now. Everything is filled in first, so a control can be moved over another's place.
     */
    private void drawMovedControls(Canvas canvas, Layout layout, Placement p) {
        Orientation o = p.orientation;
        if (o.bare == null) return;
        int i = 0;
        boolean any = false;
        for (Map.Entry<String, RectF> entry : o.controls.entrySet()) {
            Control control = layout.controls.get(i++);
            // Invisible controls (the A+B spot) have no artwork of their own: their patch of the
            // picture is A's and B's.
            if (!control.visible || !moved(control.bounds, map(p, entry.getValue()))) continue;
            any = true;
            RectF area = artworkArea(entry.getValue(), o);
            float sx = o.bare.getWidth() / o.width;
            float sy = o.bare.getHeight() / o.fullHeight();
            source.set((int) (area.left * sx), (int) (area.top * sy), (int) Math.ceil(area.right * sx),
                    (int) Math.ceil(area.bottom * sy));
            canvas.drawBitmap(o.bare, source, map(p, area), imagePaint);
        }
        if (!any) return;
        i = 0;
        for (Map.Entry<String, RectF> entry : o.controls.entrySet()) {
            Control control = layout.controls.get(i++);
            RectF r = entry.getValue();
            if (!control.visible || !moved(control.bounds, map(p, r))) continue;
            Bitmap sprite = o.sprites.get(entry.getKey());
            RectF area = artworkArea(r, o);
            if (sprite == null) {
                sprite = lift(o.image, o.bare, area, o);
                o.sprites.put(entry.getKey(), sprite);
            }
            canvas.drawBitmap(sprite, null, movedArea(area, r, p, control.bounds), imagePaint);
        }
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
            if (control.shape == Control.SPEAKER) continue; // Painted in the picture; moved like the others.
            if (buttons != null && control.visible && buttons.draw(canvas, control, motion.press(index))) continue;
            if (!control.visible) continue; // The A+B spot: A and B show themselves pressed.
            if (control.shape == Control.DPAD && dpad != null) {
                // Drawn live: a solid cross that rocks, or a joystick that leans.
                dpad.draw(canvas, control.bounds, motion.tiltX, motion.tiltY);
                continue;
            }
            if (control.shape == Control.DPAD) {
                canvas.save();
                if (Math.abs(motion.tiltX) > 0.01f || Math.abs(motion.tiltY) > 0.01f) {
                    // Tipped towards the held direction like every skin's d-pad: the pad's part of
                    // the picture, redrawn tilted over its place (which then shows as its hole).
                    tiltDpad(canvas, control.bounds, motion.tiltX, motion.tiltY, tiltMatrix, tilted);
                    RectF r = entry.getValue();
                    float sx = o.image.getWidth() / o.width;
                    float sy = o.image.getHeight() / o.fullHeight();
                    source.set((int) (r.left * sx), (int) (r.top * sy), (int) Math.ceil(r.right * sx),
                            (int) Math.ceil(r.bottom * sy));
                    canvas.drawBitmap(o.image, source, control.bounds, imagePaint);
                }
                // Then the pressed arms, following the tilt.
                drawDpadArms(canvas, o, entry.getValue(), control.bounds, motion);
                canvas.restore();
                continue;
            }
            // Pressed artwork fades in and out with the press animation.
            float amount = Math.min(1, motion.press(index));
            if (amount <= 0.01f) continue;
            int alpha = Math.round(255 * amount);
            RectF base = map(p, entry.getValue());
            if (o.pressedImage != null && o.bare != null && moved(control.bounds, base)) {
                // Moved: the pressed artwork lifted off its picture, following the control. Only
                // its own rectangle: pressed pictures may leave everything else out.
                RectF r = entry.getValue();
                Bitmap sprite = o.pressedSprites.get(entry.getKey());
                if (sprite == null) {
                    sprite = lift(o.pressedImage, o.bare, r, o);
                    o.pressedSprites.put(entry.getKey(), sprite);
                }
                imagePaint.setAlpha(alpha);
                canvas.drawBitmap(sprite, null, control.bounds, imagePaint);
                imagePaint.setAlpha(255);
            } else if (o.pressedImage != null) {
                // Copy the pressed artwork for just this control's area.
                RectF r = entry.getValue();
                float sx = o.pressedImage.getWidth() / o.width;
                float sy = o.pressedImage.getHeight() / o.fullHeight();
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
            float sy = o.pressedImage.getHeight() / o.fullHeight();
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
