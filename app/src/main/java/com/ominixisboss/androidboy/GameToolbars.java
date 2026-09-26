package com.ominixisboss.androidboy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The bars above and below the game: back, the menu and quick buttons on top with the game's
 * name under them; speed, pause, rewind, sound, screenshot and full screen at the bottom.
 */
final class GameToolbars {
    static final int BAR = 0xFF1D1719;
    static final int TILE = 0xFF2D2528;
    static final int TILE_SELECTED = 0xFF55404A;
    static final int ICON = 0xFFF0E4E8;
    static final int TEXT = 0xFFE9DDE1;
    static final int DIVIDER = 0xFF3A3135;

    static final int SPEED_SLOW = 0;
    static final int SPEED_NORMAL = 1;
    static final int SPEED_FAST = 2;

    interface Actions {
        void onBack();
        void onMenu();
        void onAchievements();
        void onLinkCable();
        void onScreenshot();
        void onRotationLock();
        void onSpeed(int speed);
        void onPause();
        /** Rewind held (true) or let go (false). */
        void onRewind(boolean held);
        void onMute();
        void onFullScreen();
    }

    final LinearLayout top;
    final LinearLayout bottom;
    private final Context context;
    private final TextView title;
    private final ImageView pause;
    private final ImageView mute;
    private final ImageView lock;
    private final ImageView[] speeds = new ImageView[3];

    GameToolbars(Context context, Actions actions) {
        this.context = context;
        top = new LinearLayout(context);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(BAR);
        LinearLayout buttons = row();
        buttons.addView(tile(Icons.BACK, "Back to the game list", v -> actions.onBack()));
        buttons.addView(tile(Icons.MENU, "Menu", v -> actions.onMenu()));
        buttons.addView(spacer());
        buttons.addView(tile(Icons.TROPHY, "Achievements", v -> actions.onAchievements()));
        buttons.addView(tile(Icons.LINK, "Link cable", v -> actions.onLinkCable()));
        buttons.addView(tile(Icons.CAMERA, "Screenshot", v -> actions.onScreenshot()));
        lock = tile(Icons.UNLOCK, "Lock the screen's rotation", v -> actions.onRotationLock());
        buttons.addView(lock);
        top.addView(buttons);
        View line = new View(context);
        line.setBackgroundColor(DIVIDER);
        top.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        title = new TextView(context);
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.06f);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(16), dp(10), dp(16), dp(10));
        top.addView(title);

        bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(BAR);
        LinearLayout controls = row();
        // Speed: slow motion, normal, fast-forward, as one segmented group.
        LinearLayout segment = new LinearLayout(context);
        segment.setBackground(rounded(TILE));
        segment.setPadding(dp(4), dp(4), dp(4), dp(4));
        int[] icons = {Icons.SLOW, Icons.PLAY, Icons.FAST};
        String[] names = {"Slow motion", "Normal speed", "Fast-forward"};
        for (int i = 0; i < 3; i++) {
            int speed = i;
            ImageView option = icon(icons[i], names[i]);
            option.setOnClickListener(v -> actions.onSpeed(speed));
            speeds[i] = option;
            segment.addView(option, new LinearLayout.LayoutParams(dp(44), dp(40)));
        }
        controls.addView(segment);
        controls.addView(spacer());
        pause = tile(Icons.PAUSE, "Pause", v -> actions.onPause());
        controls.addView(pause);
        controls.addView(holdTile(Icons.REWIND, "Rewind (hold)", actions));
        mute = tile(Icons.SOUND, "Sound", v -> actions.onMute());
        controls.addView(mute);
        controls.addView(tile(Icons.FULLSCREEN, "Full screen", v -> actions.onFullScreen()));
        bottom.addView(controls);
        setSpeed(SPEED_NORMAL);
    }

    void setTitle(String text) {
        title.setText(text.toUpperCase(java.util.Locale.getDefault()));
    }

    void setSpeed(int speed) {
        for (int i = 0; i < speeds.length; i++) {
            speeds[i].setBackground(i == speed ? rounded(TILE_SELECTED) : null);
        }
    }

    void setPaused(boolean paused) {
        pause.setImageDrawable(Icons.drawable(paused ? Icons.PLAY : Icons.PAUSE, ICON));
        pause.setContentDescription(paused ? "Resume" : "Pause");
    }

    void setMuted(boolean muted) {
        mute.setImageDrawable(Icons.drawable(muted ? Icons.MUTE : Icons.SOUND, ICON));
        mute.setContentDescription(muted ? "Sound off" : "Sound on");
    }

    void setRotationLocked(boolean locked) {
        lock.setImageDrawable(Icons.drawable(locked ? Icons.LOCK : Icons.UNLOCK, ICON));
        lock.setContentDescription(locked ? "Rotation locked" : "Lock the screen's rotation");
    }

    void setVisible(boolean visible) {
        top.setVisibility(visible ? View.VISIBLE : View.GONE);
        bottom.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        return row;
    }

    private View spacer() {
        View space = new View(context);
        space.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        return space;
    }

    private ImageView icon(int type, String description) {
        ImageView view = new ImageView(context);
        view.setImageDrawable(Icons.drawable(type, ICON));
        view.setContentDescription(description);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(9);
        view.setPadding(pad, pad, pad, pad);
        return view;
    }

    private ImageView tile(int type, String description, View.OnClickListener onClick) {
        ImageView view = icon(type, description);
        view.setBackground(rounded(TILE));
        view.setOnClickListener(onClick);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
        params.setMarginStart(dp(8));
        view.setLayoutParams(params);
        int pad = dp(12);
        view.setPadding(pad, pad, pad, pad);
        return view;
    }

    /** A tile that acts while held, like the rewind button on the skins. */
    @SuppressLint("ClickableViewAccessibility") // Holding is the point; a tap alone does nothing.
    private ImageView holdTile(int type, String description, Actions actions) {
        ImageView view = tile(type, description, null);
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setBackground(rounded(TILE_SELECTED));
                    actions.onRewind(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setBackground(rounded(TILE));
                    actions.onRewind(false);
                    return true;
                default:
                    return true;
            }
        });
        return view;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(12));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
