package com.ominixisboss.androidboy;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.util.Random;

import static com.ominixisboss.androidboy.ThemeSkin.lighten;
import static com.ominixisboss.androidboy.ThemeSkin.luminance;
import static com.ominixisboss.androidboy.ThemeSkin.shade;

/**
 * Clear skins: a see-through handheld shell, like the clear editions of the colour Game Boy.
 * The circuit board shows through the tinted, frosted plastic: copper traces, a processor, memory,
 * capacitors and gold button contacts, placed in the gaps between the controls, plus the screen's
 * ribbon cable, screw posts, rubber membranes and the speaker with its wires. The screen sits in a
 * glossy black lens. The d-pad is a glossy cross sunk in a rimmed,
 * cross-shaped well, with outlined arrows and a dimple in the middle; it rocks in 3D towards the
 * held direction. Uses the toolbars for the menu, like the Soft and Glass skins.
 */
final class ClearSkin extends Skin {
    static final String CATEGORY = "Clear";

    /** One edition: the plastic and the keys. */
    static final class Variant {
        final String id;
        final String name;
        /** The plastic's colour; its alpha is how much it veils the insides. */
        final int shell;
        /** D-pad, A and B. */
        final int keys;
        /** Start and Select rubber. */
        final int pills;

        Variant(String id, String name, int shell, int keys, int pills) {
            this.id = id;
            this.name = name;
            this.shell = shell;
            this.keys = keys;
            this.pills = pills;
        }
    }

    static final Variant[] VARIANTS = {
            new Variant("clear", "Clear", 0x38E4E6EE, 0xFF151518, 0xFF45474E),
            new Variant("atomic", "Atomic purple", 0x886A52B4, 0xFF33285A, 0xFF4E4270),
            new Variant("grape", "Grape", 0x9A55307F, 0xFF1D1426, 0xFF3B2C48),
            new Variant("teal", "Teal", 0x8A1497A8, 0xFF10262B, 0xFF2B4148),
            new Variant("fire", "Fire red", 0x90CC2233, 0xFF1A0E10, 0xFF4A2A2E),
            new Variant("jungle", "Jungle green", 0x8A2F9447, 0xFF0F1D12, 0xFF2C4232),
            new Variant("ice", "Ice blue", 0x6A7FC4EE, 0xFF1B2A3A, 0xFF3E5064),
            new Variant("tangerine", "Tangerine", 0x90FF8A1E, 0xFF2A160A, 0xFF5A3A22),
            new Variant("bubblegum", "Bubblegum", 0x7AFF7FBF, 0xFF3A1428, 0xFF5E3A4E),
            new Variant("smoke", "Smoke", 0xB0222328, 0xFF0B0B0D, 0xFF303238),
            new Variant("glow", "Glow in the dark", 0x70D6FF9E, 0xFF22301A, 0xFF46553A),
            new Variant("crystal", "Crystal white", 0x30E8ECF4, 0xFFE6E7EC, 0xFFB8BAC2),
    };

    static final ClearSkin[] ALL;

    static {
        ALL = new ClearSkin[VARIANTS.length];
        for (int i = 0; i < VARIANTS.length; i++) ALL[i] = new ClearSkin(VARIANTS[i]);
    }

    static ClearSkin find(String variant) {
        for (ClearSkin skin : ALL) {
            if (skin.variant.id.equals(variant)) return skin;
        }
        return null;
    }

    // The player's backdrop, shared by every Clear skin (see ClearBackdrop): a picture or GIF seen
    // through the plastic in place of the circuit board.
    private static Drawable backdrop;
    /** Whether the circuit board still shows, over the backdrop. */
    private static boolean boardOverBackdrop;
    /** How strongly the plastic is tinted: 1 as designed, less for a clearer view of the backdrop. */
    private static float tintScale = 1f;
    /** Changes whenever the settings above do, so the cached body is redrawn. */
    private static int customization;

    /** Sets what every Clear skin shows through its plastic; a null backdrop shows the circuit board. */
    static void customize(Drawable picture, boolean showBoard, float tint) {
        if (backdrop instanceof Animatable) ((Animatable) backdrop).stop();
        backdrop = picture;
        boardOverBackdrop = showBoard;
        tintScale = tint;
        customization++;
        if (picture instanceof Animatable) ((Animatable) picture).start();
    }

    /** The d-pad rocks up to this far towards the held direction (as on every skin). */
    static final float TILT_DEGREES = DPAD_TILT_DEGREES;
    /** How thick the d-pad is, in d-pad radii: its side walls show as it rocks. */
    private static final float THICKNESS = 0.36f;
    private static final int WALL_LAYERS = 16;
    /** How far the pad sinks when a direction is held, in d-pad radii. */
    private static final float SINK = 0.035f;
    /** Half the width of a d-pad arm, in d-pad radii. */
    private static final float ARM = 0.37f;
    /** The pad's size within its control bounds, leaving room for the rim around the well. */
    private static final float PAD_SCALE = 0.8f;

    /** Colours of "COLOR" in the wordmark. */
    private static final int[] WORDMARK = {0xFFE8354A, 0xFF8E5BD8, 0xFF3DBE5A, 0xFFF5C518, 0xFF2F8FE8};

    /** Geometry computed in {@link #layout}. */
    private static final class Geometry {
        final RectF lens = new RectF();
        boolean hasLens;
        boolean wordmark;
        boolean portrait;
        float speakerX;
        float speakerY;
        /** 0 when there's no room for the speaker. */
        float speakerRadius;
    }

    @Override
    void offsetExtras(Object extras, float dy) {
        if (!(extras instanceof Geometry)) return;
        Geometry g = (Geometry) extras;
        g.lens.offset(0, dy);
        g.speakerY += dy;
    }

    private final Variant variant;
    /** The plastic, opaque. */
    private final int plastic;
    /** What the shell looks like with nothing behind it but light. */
    private final int backingTop;
    private final int backingBottom;
    private final int board;
    private final int trace;
    private final int membrane;
    private final int rim;
    /** Printing on the shell. */
    private final int ink;
    private final int surface;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Matrix tiltMatrix = new Matrix();
    private final float[] tilted = new float[8];
    private final Path rimPath = cross(1.22f, ARM + 0.17f, 0.24f);
    private final Path wellPath = cross(1.13f, ARM + 0.09f, 0.16f);
    private final Path padPath = cross(1f, ARM, 0.13f);
    private final Path arrowPath = arrow();
    /** The shell, drawn once (see {@link #body}); shared by every Clear skin. */
    private static Bitmap body;
    private static int bodyKey;
    /** Bigger screens get a smaller picture of the shell, scaled up (about 11 MB at most). */
    private static final double MAX_BODY_PIXELS = 2_800_000;
    private final Paint bodyPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private ClearSkin(Variant variant) {
        this.variant = variant;
        plastic = variant.shell | 0xFF000000;
        backingTop = lighten(plastic, 0.72f);
        backingBottom = lighten(plastic, 0.52f);
        board = mix(0xFF1C5C3A, plastic, 0.2f);
        trace = lighten(board, 0.4f);
        membrane = mix(0xFFB5E8D6, plastic, 0.35f);
        rim = lighten(plastic, 0.72f);
        surface = mix(mix(backingTop, board, 0.3f), plastic, Color.alpha(variant.shell) / 255f);
        ink = luminance(surface) > 0.45f ? shade(plastic, 0.35f) : lighten(plastic, 0.7f);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
    }

    @Override
    String id() {
        return "clear:" + variant.id;
    }

    @Override
    String name() {
        return variant.name;
    }

    @Override
    int backgroundColor() {
        return surface;
    }

    @Override
    boolean animated() {
        return backdrop instanceof Animatable && ((Animatable) backdrop).isRunning();
    }

    @Override
    boolean usesToolbars() {
        return true;
    }

    // ---- Layout ----

    @Override
    void layout(Layout out, int width, int height, int frameWidth, int frameHeight,
                boolean controlsVisible, boolean integerScaling) {
        Geometry g = new Geometry();
        if (controlsVisible && height >= width) {
            layoutPortrait(out, g, width, height, frameWidth, frameHeight, integerScaling);
        } else {
            SoftSkin.layoutHandheld(out, width, height, frameWidth, frameHeight, controlsVisible, integerScaling);
            // A lens around the screen, as far as it fits.
            float pad = Math.min(width, height) * 0.035f;
            Rect s = out.screen;
            g.lens.set(Math.max(0, s.left - pad), Math.max(0, s.top - pad),
                    Math.min(width, s.right + pad), Math.min(height, s.bottom + pad));
            g.hasLens = g.lens.width() > s.width() + 1 || g.lens.height() > s.height() + 1;
        }
        out.extras = g; // After the layout, which resets the extras.
    }

    private static void layoutPortrait(Layout out, Geometry g, int width, int height, int frameWidth, int frameHeight,
                                       boolean integerScaling) {
        out.reset(width, height, true);
        g.portrait = true;
        float margin = width * 0.05f;
        float side = width * 0.1f;
        float above = width * 0.055f;
        float below = width * 0.12f;
        float screenWidth = width - 2 * (margin + side);
        float screenHeight = Math.min(height * 0.42f, screenWidth * frameHeight / (float) frameWidth);
        fitScreen(out.screen, new RectF(margin + side, margin + above, width - margin - side, margin + above + screenHeight),
                frameWidth, frameHeight, integerScaling);
        g.lens.set(margin, out.screen.top - above, width - margin, out.screen.bottom + below);
        g.hasLens = true;
        g.wordmark = true;

        float top = g.lens.bottom;
        float area = height - top;
        float unit = Math.min(width, area * 1.1f);
        float dpad = unit * 0.38f;
        float padY = top + area * 0.3f;
        out.controls.add(new Control(Emulator.KEY_UP | Emulator.KEY_DOWN | Emulator.KEY_LEFT | Emulator.KEY_RIGHT,
                Control.DPAD, square(width * 0.26f, padY, dpad / 2), true));
        float radius = unit * 0.088f;
        SoftSkin.addFaceButtons(out, width * 0.81f, padY - radius * 0.75f, width * 0.61f, padY + radius * 0.6f, radius);
        float pillY = top + area * 0.6f;
        SoftSkin.addPills(out, width / 2f, pillY, unit * 0.125f, unit * 0.042f, unit * 0.075f);
        SoftSkin.addMenu(out, width / 2f, top + area * 0.11f, unit * 0.04f);
        // The speaker in the bottom-right corner, if there's room under the buttons.
        float speaker = unit * 0.11f;
        float speakerY = height - speaker * 1.35f;
        if (speakerY - speaker > padY + radius * 2.2f) {
            g.speakerX = width * 0.8f;
            g.speakerY = speakerY;
            g.speakerRadius = speaker;
            out.controls.add(new Control(KEY_SPEAKER, Control.SPEAKER, square(g.speakerX, speakerY, speaker), true));
        }
    }

    private static RectF square(float cx, float cy, float half) {
        return new RectF(cx - half, cy - half, cx + half, cy + half);
    }

    // ---- The shell and what's inside it ----

    @Override
    void drawBackground(Canvas canvas, Layout layout) {
        Geometry g = layout.extras instanceof Geometry ? (Geometry) layout.extras : new Geometry();
        // The speaker goes wherever its control has been moved (the body is redrawn when it moves).
        for (Control control : layout.controls) {
            if (control.shape != Control.SPEAKER) continue;
            g.speakerX = control.bounds.centerX();
            g.speakerY = control.bounds.centerY();
            g.speakerRadius = control.bounds.width() / 2;
        }
        Drawable picture = backdrop;
        if (picture != null) drawBackdrop(canvas, picture, layout.width, layout.height);
        synchronized (ClearSkin.class) {
            Bitmap cached = body(layout, g);
            if (cached != null) {
                rect.set(0, 0, layout.width, layout.height);
                canvas.drawBitmap(cached, null, rect, bodyPaint);
            } else {
                drawBody(canvas, layout, g); // No memory for the picture: draw it straight onto the screen.
            }
        }
    }

    /**
     * The shell and everything in it, drawn once into a picture that's kept until the layout or the
     * settings change. One picture is shared by every Clear skin, so switching between them doesn't
     * pile up full-screen pictures; a very big screen gets one at a lower resolution. Null if
     * there's no memory for it.
     */
    private Bitmap body(Layout layout, Geometry g) {
        int key = 31 * bodyKey(layout) + variant.id.hashCode();
        if (body != null && bodyKey == key) return body;
        float scale = (float) Math.min(1, Math.sqrt(MAX_BODY_PIXELS / Math.max(1.0, (double) layout.width * layout.height)));
        int width = Math.max(1, Math.round(layout.width * scale));
        int height = Math.max(1, Math.round(layout.height * scale));
        try {
            if (body == null || body.getWidth() != width || body.getHeight() != height) {
                body = null; // Let the old one go before making the new one.
                body = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            } else {
                body.eraseColor(Color.TRANSPARENT);
            }
            Canvas canvas = new Canvas(body);
            canvas.scale(scale, scale);
            drawBody(canvas, layout, g);
            bodyKey = key;
            return body;
        } catch (OutOfMemoryError | RuntimeException e) {
            Log.w("ClearSkin", "Could not draw the shell into a picture", e);
            body = null;
            bodyKey = 0;
            return null;
        }
    }

    /** The backdrop filling the view, cropped to fit, like a picture behind the plastic. */
    private static void drawBackdrop(Canvas canvas, Drawable picture, int width, int height) {
        int pictureWidth = Math.max(1, picture.getIntrinsicWidth());
        int pictureHeight = Math.max(1, picture.getIntrinsicHeight());
        float scale = Math.max((float) width / pictureWidth, (float) height / pictureHeight);
        int drawnWidth = Math.round(pictureWidth * scale);
        int drawnHeight = Math.round(pictureHeight * scale);
        int left = (width - drawnWidth) / 2;
        int top = (height - drawnHeight) / 2;
        canvas.save();
        canvas.clipRect(0, 0, width, height);
        picture.setBounds(left, top, left + drawnWidth, top + drawnHeight);
        picture.draw(canvas);
        canvas.restore();
    }

    /** Identifies what the cached body was drawn for: the size, the screen and where the controls are. */
    private static int bodyKey(Layout layout) {
        int key = 31 * layout.width + layout.height;
        key = 31 * key + layout.screen.hashCode();
        key = 31 * key + (layout.controlsVisible ? 1 : 0);
        key = 31 * key + customization;
        for (Control control : layout.controls) key = 31 * key + control.bounds.hashCode();
        return key;
    }

    private void drawBody(Canvas canvas, Layout layout, Geometry g) {
        int w = layout.width;
        int h = layout.height;
        // With a backdrop, the body is drawn over it with see-through gaps.
        boolean custom = backdrop != null;
        if (!custom) {
            useShader(new LinearGradient(0, 0, 0, h, backingTop, backingBottom, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
        }
        if (!custom || boardOverBackdrop) drawBoard(canvas, layout, g);
        drawParts(canvas, layout, g);
        // The plastic over it all.
        fill.setColor(withAlpha(variant.shell, Math.round(Color.alpha(variant.shell) * tintScale)));
        canvas.drawRect(0, 0, w, h, fill);
        drawShellDetails(canvas, layout, g);
        if (g.hasLens) drawLens(canvas, layout, g);
        drawPrinting(canvas, layout);
    }

    /**
     * The circuit board: a shaped green board with copper traces running between its parts,
     * solder pads and white silkscreen labels. The parts are placed in the gaps between the
     * controls (wherever the player has moved them), so none hides under a button.
     */
    private void drawBoard(Canvas canvas, Layout layout, Geometry g) {
        int w = layout.width;
        int h = layout.height;
        float u = Math.min(w, h);
        Random random = new Random(0x6B0BL); // The same board every time.
        RectF boardRect = new RectF(u * 0.045f, u * 0.045f, w - u * 0.045f, h - u * 0.045f);
        if (g.portrait) boardRect.top = Math.max(boardRect.top, g.lens.top + u * 0.02f);
        // The board, with its corners rounded.
        float corner = u * 0.04f;
        path.reset();
        path.addRoundRect(boardRect, corner, corner, Path.Direction.CW);
        useShader(new LinearGradient(0, boardRect.top, 0, boardRect.bottom, withAlpha(lighten(board, 0.08f), 0xC8),
                withAlpha(shade(board, 0.85f), 0xC8), Shader.TileMode.CLAMP));
        canvas.drawPath(path, fill);
        fill.setShader(null);
        stroke.setStrokeWidth(u * 0.004f);
        stroke.setColor(withAlpha(lighten(board, 0.3f), 0x90));
        canvas.drawPath(path, stroke);
        canvas.save();
        canvas.clipPath(path);

        // Where parts may go: not under the controls or the lens, and not on each other.
        java.util.List<RectF> taken = new java.util.ArrayList<>();
        for (Control control : layout.controls) {
            if (!control.visible && control.shape != Control.SPEAKER) continue;
            RectF b = new RectF(control.bounds);
            float grow = Math.max(b.width(), b.height()) * (control.shape == Control.PILL ? 0.5f : 0.12f);
            b.inset(-grow, -grow);
            if (control.shape == Control.PILL) b.bottom += control.bounds.height() * 2.4f; // Its label.
            taken.add(b);
        }
        if (g.hasLens) taken.add(new RectF(g.lens.left, g.lens.top, g.lens.right, g.lens.bottom + u * 0.09f));
        RectF inner = new RectF(boardRect);
        inner.inset(u * 0.03f, u * 0.03f);

        // The parts, biggest first: the processor, memory, a crystal, capacitors and a row of resistors.
        java.util.List<RectF> parts = new java.util.ArrayList<>();
        RectF cpu = place(random, inner, taken, u * 0.15f, u * 0.15f);
        RectF ram = place(random, inner, taken, u * 0.14f, u * 0.065f);
        RectF crystal = place(random, inner, taken, u * 0.075f, u * 0.035f);
        if (cpu != null) parts.add(cpu);
        if (ram != null) parts.add(ram);
        if (crystal != null) parts.add(crystal);
        java.util.List<RectF> caps = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RectF cap = place(random, inner, taken, u * 0.06f, u * 0.06f);
            if (cap != null) caps.add(cap);
        }
        java.util.List<RectF> smalls = new java.util.ArrayList<>();
        for (int i = 0; i < 14; i++) {
            boolean across = random.nextBoolean();
            RectF small = place(random, inner, taken, u * (across ? 0.03f : 0.016f), u * (across ? 0.016f : 0.03f));
            if (small != null) smalls.add(small);
        }

        // Copper: wide power tracks along the edges, then signal traces fanning out of the chips.
        int copper = mix(0xFFC8923E, board, 0.25f);
        stroke.setStrokeWidth(u * 0.012f);
        stroke.setColor(withAlpha(copper, 0x60));
        rect.set(inner);
        rect.inset(-u * 0.012f, -u * 0.012f);
        canvas.drawRoundRect(rect, corner * 0.7f, corner * 0.7f, stroke);
        stroke.setStrokeWidth(u * 0.0042f);
        stroke.setColor(withAlpha(copper, 0xA8));
        for (RectF part : parts) {
            int count = part == cpu ? 18 : 8;
            for (int i = 0; i < count; i++) {
                float[] start = edgePoint(random, part);
                float[] end = drawTraceFrom(canvas, random, inner, u, start[0], start[1], (int) start[2], 2 + random.nextInt(2));
                drawVia(canvas, end[0], end[1], u, copper);
            }
        }
        for (int i = 0; i < 40; i++) {
            float x = inner.left + random.nextFloat() * inner.width();
            float y = inner.top + random.nextFloat() * inner.height();
            float[] end = drawTraceFrom(canvas, random, inner, u, x, y, random.nextInt(4) * 2, 2 + random.nextInt(2));
            drawVia(canvas, x, y, u, copper);
            drawVia(canvas, end[0], end[1], u, copper);
        }
        // Gold contacts under each button: the rubber's carbon pill closes these rings.
        for (Control control : layout.controls) {
            if (!control.visible || control.shape == Control.SPEAKER) continue;
            RectF b = control.bounds;
            if (control.shape == Control.DPAD) {
                for (int d = 0; d < 4; d++) {
                    double angle = Math.PI / 2 * d;
                    drawContact(canvas, b.centerX() + (float) Math.cos(angle) * b.width() * 0.3f,
                            b.centerY() + (float) Math.sin(angle) * b.width() * 0.3f, b.width() * 0.1f, copper);
                }
            } else if (control.shape != Control.PILL) {
                drawContact(canvas, b.centerX(), b.centerY(), b.width() * 0.3f, copper);
            }
        }

        // The parts themselves.
        text.setTypeface(Typeface.MONOSPACE);
        if (cpu != null) drawQuadChip(canvas, cpu, "AB-CPU", "CGB 2026", u);
        if (ram != null) drawRamChip(canvas, ram, u);
        if (crystal != null) drawCrystal(canvas, crystal, u);
        for (RectF cap : caps) drawCapacitor(canvas, cap, u);
        for (int i = 0; i < smalls.size(); i++) drawSmallPart(canvas, smalls.get(i), random.nextInt(3) == 0, u);
        // Silkscreen: part names, and the board's own name along the bottom.
        text.setTextSize(u * 0.018f);
        text.setColor(0xB0F2F2EE);
        if (cpu != null) canvas.drawText("U1", cpu.left + u * 0.02f, cpu.top - u * 0.018f, text);
        if (ram != null) canvas.drawText("U2", ram.left + u * 0.02f, ram.top - u * 0.02f, text);
        if (crystal != null) canvas.drawText("X1", crystal.centerX(), crystal.top - u * 0.01f, text);
        for (int i = 0; i < caps.size(); i++) {
            RectF cap = caps.get(i);
            canvas.drawText("C" + (i + 3), cap.centerX(), cap.bottom + u * 0.022f, text);
        }
        text.setTextSize(u * 0.02f);
        text.setColor(0x90F2F2EE);
        RectF free = place(random, inner, taken, u * 0.42f, u * 0.03f);
        if (free != null) canvas.drawText("ANDROIDBOY MAIN BOARD  REV 2", free.centerX(), free.bottom, text);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        canvas.restore(); // The board's clip.

        // The screen's ribbon cable, from under the lens down to its connector.
        if (g.portrait && g.hasLens) {
            float ribbonWidth = u * 0.11f;
            float x = g.lens.left + u * 0.1f;
            float top = g.lens.bottom - u * 0.02f;
            float bottom = g.lens.bottom + u * 0.12f;
            rect.set(x, top, x + ribbonWidth, bottom);
            fill.setColor(0xB8C98A2A);
            canvas.drawRect(rect, fill);
            stroke.setStrokeWidth(u * 0.0025f);
            stroke.setColor(0x80FFD890);
            for (int i = 1; i < 10; i++) {
                float lx = x + ribbonWidth * i / 10f;
                canvas.drawLine(lx, top, lx, bottom - u * 0.012f, stroke);
            }
            fill.setColor(0xE8E8E4DA);
            canvas.drawRect(x - u * 0.008f, bottom - u * 0.014f, x + ribbonWidth + u * 0.008f, bottom + u * 0.01f, fill);
        }
    }

    /**
     * A free spot for a part {@code width}×{@code height} inside {@code area}, clear of everything in
     * {@code taken} (which it's then added to), or null if there's no room.
     */
    private static RectF place(Random random, RectF area, java.util.List<RectF> taken, float width, float height) {
        if (area.width() < width || area.height() < height) return null;
        for (int attempt = 0; attempt < 60; attempt++) {
            float x = area.left + random.nextFloat() * (area.width() - width);
            float y = area.top + random.nextFloat() * (area.height() - height);
            RectF spot = new RectF(x, y, x + width, y + height);
            RectF padded = new RectF(spot);
            padded.inset(-width * 0.25f - area.width() * 0.01f, -height * 0.25f - area.width() * 0.01f);
            boolean clear = true;
            for (RectF other : taken) {
                if (RectF.intersects(padded, other)) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                taken.add(padded);
                return spot;
            }
        }
        return null;
    }

    /** A random point on the edge of {@code part}, and the direction (0 right, 2 down, 4 left, 6 up) out of it. */
    private static float[] edgePoint(Random random, RectF part) {
        int side = random.nextInt(4);
        float t = 0.1f + random.nextFloat() * 0.8f;
        switch (side) {
            case 0: return new float[] {part.right, part.top + part.height() * t, 0};
            case 1: return new float[] {part.left + part.width() * t, part.bottom, 2};
            case 2: return new float[] {part.left, part.top + part.height() * t, 4};
            default: return new float[] {part.left + part.width() * t, part.top, 6};
        }
    }

    /** A trace: straight and 45° runs from ({@code x}, {@code y}), starting out {@code direction}. Returns where it ends. */
    private float[] drawTraceFrom(Canvas canvas, Random random, RectF area, float unit, float x, float y, int direction,
                                  int segments) {
        path.reset();
        path.moveTo(x, y);
        for (int i = 0; i < segments; i++) {
            float length = unit * (0.03f + random.nextFloat() * (i == 0 ? 0.06f : 0.14f));
            double angle = Math.toRadians(direction * 45);
            x = clampTo(x + (float) Math.cos(angle) * length, area.left, area.right);
            y = clampTo(y + (float) Math.sin(angle) * length, area.top, area.bottom);
            path.lineTo(x, y);
            direction = (direction + (random.nextBoolean() ? 1 : 7)) % 8;
            if (i + 1 < segments) {
                // Back to straight after a diagonal, as boards are routed.
                length = unit * (0.02f + random.nextFloat() * 0.05f);
                angle = Math.toRadians(direction * 45);
                x = clampTo(x + (float) Math.cos(angle) * length, area.left, area.right);
                y = clampTo(y + (float) Math.sin(angle) * length, area.top, area.bottom);
                path.lineTo(x, y);
                direction = (direction + (random.nextBoolean() ? 1 : 7)) % 8;
            }
        }
        canvas.drawPath(path, stroke);
        return new float[] {x, y};
    }

    private void drawVia(Canvas canvas, float x, float y, float unit, int copper) {
        fill.setColor(withAlpha(lighten(copper, 0.2f), 0xE0));
        canvas.drawCircle(x, y, unit * 0.0075f, fill);
        fill.setColor(0xFF0C1A12);
        canvas.drawCircle(x, y, unit * 0.003f, fill);
    }

    /** Interleaved gold rings, which a button's carbon pill bridges when it's pressed. */
    private void drawContact(Canvas canvas, float cx, float cy, float radius, int copper) {
        if (radius <= 0) return;
        fill.setColor(withAlpha(lighten(copper, 0.25f), 0xD0));
        canvas.drawCircle(cx, cy, radius, fill);
        stroke.setStrokeWidth(radius * 0.12f);
        stroke.setColor(withAlpha(shade(board, 0.6f), 0xE0));
        canvas.drawCircle(cx, cy, radius * 0.72f, stroke);
        canvas.drawCircle(cx, cy, radius * 0.4f, stroke);
        canvas.drawLine(cx - radius, cy, cx + radius, cy, stroke);
    }

    /** The processor: a square chip with legs on all four sides and its name printed on it. */
    private void drawQuadChip(Canvas canvas, RectF chip, String name, String line, float u) {
        fill.setColor(0xF0D8DCE2);
        int legs = 12;
        float pitch = chip.width() * 0.8f / legs;
        float leg = chip.width() * 0.07f;
        for (int i = 0; i < legs; i++) {
            float along = chip.left + chip.width() * 0.1f + (i + 0.5f) * pitch;
            float down = chip.top + chip.height() * 0.1f + (i + 0.5f) * pitch;
            canvas.drawRect(along - pitch * 0.2f, chip.top - leg, along + pitch * 0.2f, chip.top + leg, fill);
            canvas.drawRect(along - pitch * 0.2f, chip.bottom - leg, along + pitch * 0.2f, chip.bottom + leg, fill);
            canvas.drawRect(chip.left - leg, down - pitch * 0.2f, chip.left + leg, down + pitch * 0.2f, fill);
            canvas.drawRect(chip.right - leg, down - pitch * 0.2f, chip.right + leg, down + pitch * 0.2f, fill);
        }
        rect.set(chip);
        rect.inset(leg * 0.6f, leg * 0.6f);
        useShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom, 0xFF34363C, 0xFF16171A,
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, u * 0.006f, u * 0.006f, fill);
        fill.setShader(null);
        fill.setColor(0x50FFFFFF);
        canvas.drawCircle(rect.left + rect.width() * 0.12f, rect.top + rect.height() * 0.12f, rect.width() * 0.035f, fill);
        text.setTextSize(rect.width() * 0.13f);
        text.setColor(0xB0E8E8E8);
        canvas.drawText(name, rect.centerX(), rect.centerY(), text);
        text.setTextSize(rect.width() * 0.09f);
        text.setColor(0x80E8E8E8);
        canvas.drawText(line, rect.centerX(), rect.centerY() + rect.height() * 0.18f, text);
    }

    /** A memory chip: legs along its long sides. */
    private void drawRamChip(Canvas canvas, RectF chip, float u) {
        fill.setColor(0xF0D0D4DA);
        int legs = 10;
        float pitch = chip.width() / legs;
        for (int i = 0; i < legs; i++) {
            float x = chip.left + (i + 0.5f) * pitch;
            canvas.drawRect(x - pitch * 0.2f, chip.top - chip.height() * 0.14f, x + pitch * 0.2f,
                    chip.bottom + chip.height() * 0.14f, fill);
        }
        rect.set(chip);
        fill.setColor(0xF01C1D21);
        canvas.drawRect(rect, fill);
        fill.setColor(0x30FFFFFF);
        rect.set(chip.left, chip.top, chip.right, chip.top + chip.height() * 0.2f);
        canvas.drawRect(rect, fill);
        text.setTextSize(chip.height() * 0.3f);
        text.setColor(0x90E8E8E8);
        canvas.drawText("SRAM 64K", chip.centerX(), chip.centerY() + chip.height() * 0.12f, text);
    }

    /** A crystal: a rounded silver can. */
    private void drawCrystal(Canvas canvas, RectF can, float u) {
        useShader(new LinearGradient(0, can.top, 0, can.bottom, 0xFFF2F3F5, 0xFF8C9098, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(can, can.height() / 2, can.height() / 2, fill);
        fill.setShader(null);
        stroke.setStrokeWidth(u * 0.002f);
        stroke.setColor(0x80505458);
        rect.set(can);
        rect.inset(can.height() * 0.15f, can.height() * 0.15f);
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, stroke);
    }

    /** An electrolytic capacitor from above: a can with a cross stamped in its top and a stripe down one side. */
    private void drawCapacitor(Canvas canvas, RectF cap, float u) {
        float cx = cap.centerX();
        float cy = cap.centerY();
        float r = cap.width() / 2;
        fill.setColor(0x60000000);
        canvas.drawCircle(cx + r * 0.1f, cy + r * 0.14f, r, fill);
        fill.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.4f, 0xFF3C5FA8, 0xFF16264A,
                Shader.TileMode.CLAMP));
        fill.setColor(Color.BLACK);
        canvas.drawCircle(cx, cy, r, fill);
        fill.setShader(null);
        // The polarity stripe.
        fill.setColor(0xB0D8DCE6);
        rect.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(rect, 120, 60, true, fill);
        // The top: a silver disc with its vent cross.
        fill.setShader(new RadialGradient(cx - r * 0.2f, cy - r * 0.2f, r, 0xFFEEF0F4, 0xFF8E939C, Shader.TileMode.CLAMP));
        fill.setColor(Color.BLACK);
        canvas.drawCircle(cx, cy, r * 0.72f, fill);
        fill.setShader(null);
        stroke.setStrokeWidth(r * 0.08f);
        stroke.setColor(0x90505460);
        canvas.drawLine(cx - r * 0.5f, cy, cx + r * 0.5f, cy, stroke);
        canvas.drawLine(cx, cy - r * 0.5f, cx, cy + r * 0.5f, stroke);
    }

    /** A resistor or capacitor the size of a grain of rice, with its metal ends. */
    private void drawSmallPart(Canvas canvas, RectF part, boolean resistor, float u) {
        fill.setColor(resistor ? 0xF0202226 : 0xF0A07850);
        canvas.drawRect(part, fill);
        fill.setColor(0xF0D6D9DE);
        boolean across = part.width() > part.height();
        float end = (across ? part.width() : part.height()) * 0.22f;
        if (across) {
            canvas.drawRect(part.left, part.top, part.left + end, part.bottom, fill);
            canvas.drawRect(part.right - end, part.top, part.right, part.bottom, fill);
        } else {
            canvas.drawRect(part.left, part.top, part.right, part.top + end, fill);
            canvas.drawRect(part.left, part.bottom - end, part.right, part.bottom, fill);
        }
    }

    /** The parts in front of the board: membranes, screw posts and the speaker. */
    private void drawParts(Canvas canvas, Layout layout, Geometry g) {
        int w = layout.width;
        int h = layout.height;
        float u = Math.min(w, h);
        // Rubber membranes under the keys, see-through, with the dark carbon pill under each key.
        for (Control control : layout.controls) {
            if (!control.visible || control.shape == Control.SPEAKER) continue;
            RectF b = control.bounds;
            if (control.shape == Control.DPAD) {
                drawMembraneCircle(canvas, b.centerX(), b.centerY(), b.width() * 0.6f, u);
            } else if (control.shape == Control.PILL) {
                float grow = b.height() * 0.7f;
                rect.set(b.left - grow, b.top - grow, b.right + grow, b.bottom + grow);
                fill.setColor(withAlpha(membrane, 0x90));
                canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill);
                stroke.setStrokeWidth(u * 0.004f);
                stroke.setColor(withAlpha(lighten(membrane, 0.4f), 0xA0));
                canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, stroke);
            } else {
                drawMembraneCircle(canvas, b.centerX(), b.centerY(), b.width() * 0.66f, u);
            }
        }
        // Screw posts, moulded into the back of the shell.
        float[][] screws = g.portrait
                ? new float[][] {{u * 0.1f, g.lens.bottom + u * 0.07f}, {w - u * 0.1f, g.lens.bottom + u * 0.07f},
                        {u * 0.09f, h - u * 0.09f}, {w * 0.5f, h - u * 0.07f}}
                : new float[][] {{u * 0.08f, u * 0.08f}, {w - u * 0.08f, u * 0.08f}, {u * 0.08f, h - u * 0.08f},
                        {w - u * 0.08f, h - u * 0.08f}};
        for (float[] screw : screws) drawScrew(canvas, screw[0], screw[1], u);
        // The speaker behind its grille: its cone, the magnet in the middle, and two wires to the board.
        if (g.speakerRadius > 0) {
            float r = g.speakerRadius;
            stroke.setStrokeWidth(u * 0.006f);
            stroke.setColor(0xE0C8302C);
            canvas.drawLine(g.speakerX - r * 0.2f, g.speakerY - r * 0.9f, g.speakerX - r * 1.1f, g.speakerY - r * 1.5f, stroke);
            stroke.setColor(0xE0202224);
            canvas.drawLine(g.speakerX + r * 0.2f, g.speakerY - r * 0.9f, g.speakerX - r * 0.8f, g.speakerY - r * 1.65f, stroke);
            fill.setShader(new RadialGradient(g.speakerX, g.speakerY, r,
                    new int[] {0xFF9EA2AA, 0xFF55585E, 0xFFB08A60, 0xFF7A5E40, 0xFF3A3836}, new float[] {0f, 0.28f, 0.34f, 0.85f, 1f},
                    Shader.TileMode.CLAMP));
            fill.setColor(Color.BLACK);
            canvas.drawCircle(g.speakerX, g.speakerY, r, fill);
            fill.setShader(null);
            stroke.setStrokeWidth(u * 0.003f);
            stroke.setColor(0x50000000);
            for (int i = 2; i <= 5; i++) canvas.drawCircle(g.speakerX, g.speakerY, r * i / 5.6f, stroke);
            fill.setColor(0x60FFFFFF);
            canvas.drawCircle(g.speakerX - r * 0.08f, g.speakerY - r * 0.1f, r * 0.08f, fill);
        }
    }

    private void drawMembraneCircle(Canvas canvas, float cx, float cy, float radius, float unit) {
        if (radius <= 0) return;
        fill.setColor(withAlpha(membrane, 0x90));
        canvas.drawCircle(cx, cy, radius, fill);
        stroke.setStrokeWidth(unit * 0.004f);
        stroke.setColor(withAlpha(lighten(membrane, 0.4f), 0xA0));
        canvas.drawCircle(cx, cy, radius, stroke);
        stroke.setColor(withAlpha(shade(membrane, 0.7f), 0x80));
        canvas.drawCircle(cx, cy, radius * 0.88f, stroke);
    }

    /** A screw in its post: the post is clear plastic, a ring of light around the metal head. */
    private void drawScrew(Canvas canvas, float x, float y, float unit) {
        float post = unit * 0.032f;
        fill.setColor(withAlpha(shade(plastic, 0.7f), 0x70));
        canvas.drawCircle(x, y + post * 0.12f, post, fill);
        stroke.setStrokeWidth(post * 0.14f);
        stroke.setColor(withAlpha(lighten(plastic, 0.7f), 0x90));
        rect.set(x - post, y - post, x + post, y + post);
        canvas.drawArc(rect, 180, 110, false, stroke);
        float r = unit * 0.017f;
        fill.setShader(new RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.4f, 0xFFE4E6EA, 0xFF7C8088, Shader.TileMode.CLAMP));
        fill.setColor(Color.BLACK);
        canvas.drawCircle(x, y, r, fill);
        fill.setShader(null);
        stroke.setStrokeWidth(r * 0.25f);
        stroke.setColor(0xFF55585E);
        canvas.drawLine(x - r * 0.55f, y, x + r * 0.55f, y, stroke);
        canvas.drawLine(x, y - r * 0.55f, x, y + r * 0.55f, stroke);
    }

    private void drawShellDetails(Canvas canvas, Layout layout, Geometry g) {
        int w = layout.width;
        int h = layout.height;
        float u = Math.min(w, h);
        // Light across the plastic.
        fill.setShader(new LinearGradient(0, 0, w, h, new int[] {0x30FFFFFF, 0x00FFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                new float[] {0f, 0.35f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        fill.setColor(Color.BLACK);
        canvas.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
        // The plastic is thicker towards the edges, so its colour gathers there.
        float edge = u * 0.09f;
        int thick = withAlpha(plastic, Math.min(255, Math.round(0x70 * Math.max(0.3f, tintScale))));
        useShader(new LinearGradient(0, 0, edge, 0, thick, withAlpha(plastic, 0), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, edge, h, fill);
        useShader(new LinearGradient(w, 0, w - edge, 0, thick, withAlpha(plastic, 0), Shader.TileMode.CLAMP));
        canvas.drawRect(w - edge, 0, w, h, fill);
        useShader(new LinearGradient(0, 0, 0, edge, thick, withAlpha(plastic, 0), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, edge, fill);
        useShader(new LinearGradient(0, h, 0, h - edge, thick, withAlpha(plastic, 0), Shader.TileMode.CLAMP));
        canvas.drawRect(0, h - edge, w, h, fill);
        fill.setShader(null);
        // A faint frosting on the inside of the plastic.
        Paint frost = new Paint();
        frost.setShader(new BitmapShader(SoftSkin.grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        frost.setAlpha(0x90);
        canvas.drawRect(0, 0, w, h, frost);
        // The shell's side walls, seen edge on through the clear plastic.
        float wall = u * 0.022f;
        stroke.setStrokeWidth(wall);
        stroke.setColor(withAlpha(lighten(plastic, 0.35f), 0x70));
        canvas.drawRect(wall / 2, wall / 2, w - wall / 2, h - wall / 2, stroke);
        stroke.setStrokeWidth(u * 0.004f);
        stroke.setColor(withAlpha(shade(plastic, 0.6f), 0x60));
        canvas.drawRect(wall, wall, w - wall, h - wall, stroke);
        // A moulded badge under the lens.
        if (g.portrait) {
            float y = g.lens.bottom + u * 0.05f;
            rect.set(w / 2f - u * 0.14f, y - u * 0.03f, w / 2f + u * 0.14f, y + u * 0.03f);
            stroke.setStrokeWidth(u * 0.005f);
            stroke.setColor(0x50FFFFFF);
            canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, stroke);
            rect.offset(0, -u * 0.004f);
            stroke.setColor(0x24000000);
            canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, stroke);
            text.setTextSize(u * 0.032f);
            text.setColor(0x40FFFFFF);
            canvas.drawText("ANDROIDBOY", w / 2f, y + u * 0.012f, text);
        }
        // The speaker grille: rings of holes.
        if (g.speakerRadius > 0) {
            float r = g.speakerRadius;
            for (int ring = 0; ring <= 4; ring++) {
                int count = ring == 0 ? 1 : ring * 6;
                float distance = r * 0.21f * ring;
                for (int i = 0; i < count; i++) {
                    double angle = 2 * Math.PI * i / count;
                    float x = g.speakerX + (float) Math.cos(angle) * distance;
                    float y = g.speakerY + (float) Math.sin(angle) * distance;
                    fill.setColor(0x50FFFFFF);
                    canvas.drawCircle(x, y + r * 0.015f, r * 0.058f, fill);
                    fill.setColor(0xA0141416);
                    canvas.drawCircle(x, y, r * 0.055f, fill);
                }
            }
        }
    }

    private void drawLens(Canvas canvas, Layout layout, Geometry g) {
        float u = Math.min(layout.width, layout.height);
        Rect screen = layout.screen;
        RectF lens = g.lens;
        float corner = g.portrait ? u * 0.035f : u * 0.02f;
        useShader(new LinearGradient(0, lens.top, 0, lens.bottom, 0xFF2A2C32, 0xFF0E0F12, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(lens, corner, corner, fill);
        fill.setShader(null);
        // A glossy streak across the glass.
        canvas.save();
        path.reset();
        path.addRoundRect(lens, corner, corner, Path.Direction.CW);
        canvas.clipPath(path);
        fill.setShader(new LinearGradient(lens.left, lens.top, lens.left + lens.width() * 0.5f, lens.top + lens.width() * 0.5f,
                new int[] {0x00FFFFFF, 0x1CFFFFFF, 0x00FFFFFF}, new float[] {0.35f, 0.5f, 0.65f}, Shader.TileMode.CLAMP));
        fill.setColor(Color.BLACK);
        canvas.drawRect(lens, fill);
        fill.setShader(null);
        canvas.restore();
        stroke.setStrokeWidth(Math.max(1f, u * 0.004f));
        stroke.setColor(0x40FFFFFF);
        canvas.drawRoundRect(lens, corner, corner, stroke);
        // The black border around the screen.
        float edge = u * 0.01f;
        fill.setColor(0xFF08080A);
        canvas.drawRect(screen.left - edge, screen.top - edge, screen.right + edge, screen.bottom + edge, fill);

        // The power light, if there's room beside the screen.
        float side = screen.left - lens.left;
        if (side > u * 0.06f) {
            float x = lens.left + side * 0.3f;
            float y = screen.top + screen.height() * 0.28f;
            float r = u * 0.011f;
            fill.setShader(new RadialGradient(x, y, r * 3, 0x70FF3030, 0x00FF3030, Shader.TileMode.CLAMP));
            fill.setColor(Color.BLACK);
            canvas.drawCircle(x, y, r * 3, fill);
            fill.setShader(null);
            fill.setColor(0xFFFF3B30);
            canvas.drawCircle(x, y, r, fill);
            stroke.setStrokeWidth(u * 0.004f);
            stroke.setColor(0xFF8A8C94);
            for (int i = 1; i <= 3; i++) {
                float arc = r * (1.3f + i * 0.9f);
                rect.set(x + r * 0.8f - arc, y - arc, x + r * 0.8f + arc, y + arc);
                canvas.drawArc(rect, -35, 70, false, stroke);
            }
            text.setTextSize(u * 0.022f);
            text.setColor(0xFF8A8C94);
            text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            canvas.drawText("POWER", lens.left + side * 0.45f, y + r * 3.4f, text);
        }
        if (g.wordmark) drawWordmark(canvas, lens.centerX(), screen.bottom + (lens.bottom - screen.bottom) * 0.62f, u * 0.056f);
    }

    /** "ANDROIDBOY COLOR", the last word in rainbow letters. */
    private void drawWordmark(Canvas canvas, float cx, float baseline, float size) {
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC));
        text.setTextSize(size);
        text.setTextAlign(Paint.Align.LEFT);
        String first = "ANDROIDBOY ";
        String second = "COLOR";
        float total = text.measureText(first) + text.measureText(second);
        float x = cx - total / 2;
        text.setColor(0xFFD8DAE2);
        canvas.drawText(first, x, baseline, text);
        x += text.measureText(first);
        for (int i = 0; i < second.length(); i++) {
            String letter = second.substring(i, i + 1);
            text.setColor(WORDMARK[i % WORDMARK.length]);
            canvas.drawText(letter, x, baseline, text);
            x += text.measureText(letter);
        }
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
    }

    /** SELECT and START printed under their buttons. */
    private void drawPrinting(Canvas canvas, Layout layout) {
        for (Control control : layout.controls) {
            if (!control.visible || control.shape != Control.PILL) continue;
            RectF b = control.bounds;
            text.setTextSize(b.height() * 0.8f);
            text.setColor(ink);
            float y = b.bottom + b.height() * 1.9f;
            // Above the button when there's no room below it.
            if (y > layout.height - b.height() * 0.4f) y = b.top - b.height() * 0.9f;
            canvas.drawText(control.keys == Emulator.KEY_START ? "START" : "SELECT", b.centerX(), y, text);
        }
    }

    // ---- Controls ----

    @Override
    void drawControls(Canvas canvas, Layout layout, int pressed, Motion motion) {
        for (int i = 0; i < layout.controls.size(); i++) {
            Control control = layout.controls.get(i);
            if (!control.visible || control.shape == Control.SPEAKER) continue; // Part of the body.
            float press = Math.max(0, Math.min(1.2f, motion.press(i)));
            if (control.shape == Control.DPAD) {
                drawDpad(canvas, control.bounds, motion.tiltX, motion.tiltY);
            } else if (control.shape == Control.PILL) {
                drawPill(canvas, control.bounds, press);
            } else if (control.shape == Control.SHOULDER) {
                // Rubber, like Start and Select.
                int color = variant.pills;
                drawShoulder(canvas, control, press, lighten(color, 0.22f), shade(color, 0.8f), 0x70000000,
                        luminance(color) > 0.5f ? 0xFF303030 : 0xE0FFFFFF);
            } else {
                drawButton(canvas, control, press);
            }
        }
    }

    /**
     * The d-pad, drawn in unit coordinates (its radius is 1): a raised rim, the dark well inside
     * it, and the pad, whose side walls are stacked slices under a glossy face, all rocked in
     * perspective towards the held direction.
     */
    private void drawDpad(Canvas canvas, RectF bounds, float tiltX, float tiltY) {
        float radius = bounds.width() / 2 * PAD_SCALE;
        int keys = variant.keys;
        boolean dark = luminance(keys) < 0.3f;
        float amount = Math.min(1f, (float) Math.hypot(tiltX, tiltY));
        float sink = SINK * amount;
        canvas.save();
        canvas.translate(bounds.centerX(), bounds.centerY());
        canvas.scale(radius, radius);

        // The raised rim around the well, lit from the top left, with a shadow on the shell below it.
        canvas.save();
        canvas.translate(0.02f, 0.05f);
        fill.setColor(0x30000000);
        canvas.drawPath(rimPath, fill);
        canvas.restore();
        useShader(new LinearGradient(-1, -1, 1, 1, lighten(rim, 0.45f), shade(rim, 0.72f), Shader.TileMode.CLAMP));
        canvas.drawPath(rimPath, fill);
        fill.setShader(null);
        // The well: dark, deepest at the top where the rim shades it.
        useShader(new LinearGradient(0, -1.13f, 0, 1.13f, 0xFF050506, 0xFF1A1A1E, Shader.TileMode.CLAMP));
        canvas.drawPath(wellPath, fill);
        fill.setShader(null);
        // The pad never leaves its well, however it rocks.
        canvas.save();
        canvas.clipPath(wellPath);

        // The pad's shadow on the well floor, moving with the tilt.
        canvas.save();
        canvas.translate(0.03f, 0.06f);
        rock(canvas, tiltX, tiltY, sink + THICKNESS + 0.05f);
        fill.setColor(0x90000000);
        canvas.drawPath(padPath, fill);
        canvas.restore();

        // The side walls: slices from the bottom up. The raised side swings out from under the face.
        for (int i = 0; i < WALL_LAYERS; i++) {
            float t = (float) i / WALL_LAYERS;
            canvas.save();
            rock(canvas, tiltX, tiltY, sink + THICKNESS * (1 - t));
            fill.setColor(dark ? lighten(keys, 0.12f + 0.26f * t) : shade(keys, 0.4f + 0.35f * t));
            canvas.drawPath(padPath, fill);
            if (i == WALL_LAYERS - 1) {
                // The wall's top edge, where it meets the face, catches a little light.
                stroke.setStrokeWidth(0.02f);
                stroke.setColor(dark ? 0x30FFFFFF : 0x40FFFFFF);
                canvas.drawPath(padPath, stroke);
            }
            canvas.restore();
        }

        // The face.
        canvas.save();
        rock(canvas, tiltX, tiltY, sink);
        useShader(new LinearGradient(-1, -1, 1, 1, lighten(keys, dark ? 0.16f : 0.25f), shade(keys, 0.78f),
                Shader.TileMode.CLAMP));
        canvas.drawPath(padPath, fill);
        canvas.save();
        canvas.clipPath(padPath);
        // Gloss: the light's reflection in the upper left, and a soft band along the top.
        fill.setShader(new RadialGradient(-0.35f, -0.5f, 0.9f, dark ? 0x48FFFFFF : 0x60FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        canvas.drawPath(padPath, fill);
        fill.setShader(new LinearGradient(0, -ARM, 0, ARM * 0.2f, dark ? 0x22FFFFFF : 0x30FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        canvas.drawRect(-1, -ARM, 1, ARM, fill);
        if (amount > 0.01f) {
            // Rocking away from the light darkens the held side and catches it on the raised one.
            float length = (float) Math.hypot(tiltX, tiltY);
            float nx = tiltX / length;
            float ny = tiltY / length;
            fill.setShader(new LinearGradient(nx, ny, -nx, -ny,
                    new int[] {Color.argb((int) (140 * amount), 0, 0, 0), 0x00000000,
                            Color.argb((int) (90 * amount), 255, 255, 255)},
                    null, Shader.TileMode.CLAMP));
            canvas.drawPath(padPath, fill);
        }
        // Each arm is rounded across its width: lighter along its middle, darker towards its sides.
        int[] roundness = {0x2A000000, 0x14FFFFFF, 0x00FFFFFF, 0x2A000000};
        float[] stops = {0f, 0.35f, 0.6f, 1f};
        fill.setShader(new LinearGradient(0, -ARM, 0, ARM, roundness, stops, Shader.TileMode.CLAMP));
        canvas.drawRect(-1, -ARM, 1, ARM, fill);
        fill.setShader(new LinearGradient(-ARM, 0, ARM, 0, roundness, stops, Shader.TileMode.CLAMP));
        canvas.drawRect(-ARM, -1, ARM, -ARM, fill);
        canvas.drawRect(-ARM, ARM, ARM, 1, fill);
        // A rounded bevel all round the face: lit along the top and left, shaded along the bottom and right.
        stroke.setStrokeWidth(0.16f);
        stroke.setShader(new LinearGradient(-1, -1, 1, 1,
                new int[] {dark ? 0x60FFFFFF : 0x80FFFFFF, 0x00FFFFFF, 0x00000000, dark ? 0x90000000 : 0x60000000},
                new float[] {0f, 0.42f, 0.58f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(padPath, stroke);
        stroke.setShader(null);
        fill.setShader(null);
        canvas.restore();

        // Arrows moulded into each arm: outlines, dark where they dip, light on the far edge.
        for (int direction = 0; direction < 4; direction++) {
            canvas.save();
            canvas.rotate(direction * 90);
            stroke.setStrokeWidth(0.03f);
            canvas.save();
            canvas.translate(0.012f, 0.016f);
            stroke.setColor(dark ? 0x50FFFFFF : lighten(keys, 0.4f));
            canvas.drawPath(arrowPath, stroke);
            canvas.restore();
            stroke.setColor(dark ? 0xFF000000 : shade(keys, 0.5f));
            canvas.drawPath(arrowPath, stroke);
            canvas.restore();
        }
        // The dimple in the middle: concave, so it's lit on the lower right and shaded on the upper left.
        float dimple = 0.22f;
        useShader(new LinearGradient(-dimple, -dimple, dimple, dimple, dark ? 0xFF000000 : shade(keys, 0.55f),
                lighten(keys, dark ? 0.22f : 0.3f), Shader.TileMode.CLAMP));
        canvas.drawCircle(0, 0, dimple, fill);
        fill.setShader(null);
        stroke.setStrokeWidth(0.025f);
        stroke.setColor(dark ? 0x30FFFFFF : 0x50FFFFFF);
        rect.set(-dimple, -dimple, dimple, dimple);
        canvas.drawArc(rect, 20, 140, false, stroke);
        // The light caught on its lower curve: one crescent.
        stroke.setStrokeWidth(0.04f);
        stroke.setColor(0x80FFFFFF);
        rect.set(-dimple * 0.7f, -dimple * 0.7f, dimple * 0.7f, dimple * 0.7f);
        canvas.drawArc(rect, 25, 70, false, stroke);
        // A bright edge along the top of the face and a dark outline.
        canvas.save();
        canvas.clipRect(-1.1f, -1.1f, 1.1f, 0f);
        stroke.setStrokeWidth(0.03f);
        stroke.setColor(dark ? 0x38FFFFFF : 0x60FFFFFF);
        canvas.drawPath(padPath, stroke);
        canvas.restore();
        stroke.setStrokeWidth(0.02f);
        stroke.setColor(dark ? 0xFF000000 : shade(keys, 0.55f));
        canvas.drawPath(padPath, stroke);
        canvas.restore();
        canvas.restore(); // The well's clip.

        canvas.restore();
    }

    /** A domed, glossy A or B button with its letter moulded in, sitting in a hole in the shell. */
    private Button3D key3d;
    private Button3D pill3d;

    private void drawButton(Canvas canvas, Control control, float press) {
        RectF b = control.bounds;
        float r = b.width() / 2 * 0.95f;
        float cx = b.centerX();
        int keys = variant.keys;
        boolean dark = luminance(keys) < 0.3f;
        if (key3d == null) key3d = new Button3D(keys, Button3D.PLASTIC);
        // A glossy key standing in its hole in the shell (see Button3D).
        key3d.drawRound(canvas, cx, b.centerY(), r, press, plastic);
        float y = Button3D.capY(b.centerY(), r, press);
        if (control.keys == KEY_MENU) {
            drawMenuGlyph(canvas, stroke, cx, y, r, dark ? 0xB0FFFFFF : shade(keys, 0.45f));
            return;
        }
        String letter = control.keys == Emulator.KEY_A ? "A" : control.keys == Emulator.KEY_B ? "B" : "";
        key3d.drawLetter(canvas, letter, cx, b.centerY(), r, press, dark ? 0xFF050506 : shade(keys, 0.55f));
    }

    /** A rubber Start or Select button. */
    private void drawPill(Canvas canvas, RectF b, float press) {
        if (pill3d == null) pill3d = new Button3D(variant.pills, Button3D.RUBBER);
        pill3d.drawPill(canvas, b, press, plastic);
    }

    // ---- Helpers ----

    /**
     * Rocks the canvas like {@link ThemeSkin#rock}, but seen from further away and a little lower,
     * so the pad's thick sides show under its face even at rest.
     */
    private void rock(Canvas canvas, float tiltX, float tiltY, float depth) {
        ThemeSkin.rock(canvas, tiltMatrix, tilted, tiltX, tiltY, TILT_DEGREES, depth, DPAD_VIEW_DISTANCE, DPAD_VIEW_SLANT);
    }

    /** Fills with a gradient; the paint's own alpha would otherwise still apply to it. */
    private void useShader(Shader shader) {
        fill.setColor(Color.BLACK);
        fill.setShader(shader);
    }

    /** A plus shape in unit coordinates, as one outline so strokes don't cross in the middle. */
    private static Path cross(float radius, float arm, float corner) {
        Path out = new Path();
        out.addRoundRect(new RectF(-arm, -radius, arm, radius), corner, corner, Path.Direction.CW);
        Path across = new Path();
        across.addRoundRect(new RectF(-radius, -arm, radius, arm), corner, corner, Path.Direction.CW);
        out.op(across, Path.Op.UNION);
        return out;
    }

    /** The outlined arrow on the up arm: a head on a short stem, pointing up. */
    private static Path arrow() {
        Path out = new Path();
        out.moveTo(0, -0.9f);
        out.lineTo(0.2f, -0.66f);
        out.lineTo(0.1f, -0.66f);
        out.lineTo(0.1f, -0.46f);
        out.lineTo(-0.1f, -0.46f);
        out.lineTo(-0.1f, -0.66f);
        out.lineTo(-0.2f, -0.66f);
        out.close();
        return out;
    }

    private static float clampTo(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** {@code a} blended towards {@code b} by {@code amount} (0 to 1); opaque. */
    static int mix(int a, int b, float amount) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * amount),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * amount),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * amount));
    }
}
