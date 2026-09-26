package com.ominixisboss.androidboy;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;

/**
 * The banner at the top of the game screen for RetroAchievements events: an achievement
 * unlocked, a leaderboard attempt, the game's achievements loading. Events arriving together
 * are shown one after another.
 */
final class AchievementPopup extends LinearLayout {
    private static final long SHOW_MS = 4500;
    private static final long SLIDE_MS = 250;

    private final ImageView badge;
    private final TextView header;
    private final TextView title;
    private final TextView description;
    private final ArrayDeque<Achievements.Event> queue = new ArrayDeque<>();
    private boolean showing;

    AchievementPopup(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(12);
        setPadding(pad, pad, dp(16), pad);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xE8141418);
        background.setCornerRadius(dp(14));
        background.setStroke(Math.max(1, dp(1)), 0x40FFFFFF);
        setBackground(background);
        setElevation(dp(8));
        setVisibility(GONE);

        badge = new ImageView(context);
        badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LayoutParams badgeParams = new LayoutParams(dp(48), dp(48));
        badgeParams.setMarginEnd(dp(12));
        addView(badge, badgeParams);

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(VERTICAL);
        header = label(context, 11, 0xFFFFC94D, true);
        header.setLetterSpacing(0.08f);
        title = label(context, 15, Color.WHITE, true);
        description = label(context, 13, 0xFFC8C8D0, false);
        description.setMaxLines(2);
        text.addView(header);
        text.addView(title);
        text.addView(description);
        addView(text, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
    }

    /** Layout for adding the popup to the game's FrameLayout: top centre, clear of the status bar area. */
    FrameLayout.LayoutParams layoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.min(dp(420), getResources().getDisplayMetrics().widthPixels - dp(24)),
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = dp(20);
        return params;
    }

    void show(Achievements.Event event) {
        queue.add(event);
        if (!showing) showNext();
    }

    /** What the banner says for an event; null for events that aren't shown. */
    static String[] describe(Achievements.Event event) {
        switch (event.type) {
            case Achievements.EVENT_ACHIEVEMENT:
                return new String[] {"ACHIEVEMENT UNLOCKED · " + event.points + (event.points == 1 ? " POINT" : " POINTS"),
                        event.title, event.description};
            case Achievements.EVENT_GAME_LOADED:
                return new String[] {"RETROACHIEVEMENTS", event.title, event.description};
            case Achievements.EVENT_GAME_COMPLETED:
                return new String[] {"GAME MASTERED", event.title, "Every achievement unlocked"};
            case Achievements.EVENT_LEADERBOARD_STARTED:
                return new String[] {"LEADERBOARD ATTEMPT STARTED", event.title, null};
            case Achievements.EVENT_LEADERBOARD_FAILED:
                return new String[] {"LEADERBOARD ATTEMPT FAILED", event.title, null};
            case Achievements.EVENT_LEADERBOARD_SUBMITTED:
                return new String[] {"LEADERBOARD SCORE SUBMITTED", event.title, event.description};
            case Achievements.EVENT_SCOREBOARD:
                return new String[] {"LEADERBOARD RANK", event.title, event.description};
            case Achievements.EVENT_SERVER_ERROR:
                return new String[] {"RETROACHIEVEMENTS", "Something went wrong", event.description};
            case Achievements.EVENT_DISCONNECTED:
                return new String[] {"RETROACHIEVEMENTS", "Offline",
                        "Unlocks will be sent when the connection is back"};
            case Achievements.EVENT_RECONNECTED:
                return new String[] {"RETROACHIEVEMENTS", "Back online", "Waiting unlocks were sent"};
            default:
                return null;
        }
    }

    private void showNext() {
        Achievements.Event event;
        String[] text = null;
        while ((event = queue.poll()) != null && (text = describe(event)) == null) {
            // Skip events without a banner.
        }
        if (event == null) {
            showing = false;
            setVisibility(GONE);
            return;
        }
        showing = true;
        header.setText(text[0]);
        title.setText(text[1] != null ? text[1] : "");
        description.setText(text[2] != null ? text[2] : "");
        description.setVisibility(TextUtils.isEmpty(text[2]) ? GONE : VISIBLE);
        badge.setVisibility(event.imageUrl != null ? VISIBLE : GONE);
        if (event.imageUrl != null) Badges.load(badge, event.imageUrl);
        setVisibility(VISIBLE);
        boolean animate = ValueAnimator.areAnimatorsEnabled();
        if (animate) {
            setAlpha(0f);
            setTranslationY(-dp(40));
            animate().alpha(1f).translationY(0).setDuration(SLIDE_MS).start();
        } else {
            setAlpha(1f);
            setTranslationY(0);
        }
        postDelayed(() -> {
            if (animate) {
                animate().alpha(0f).translationY(-dp(40)).setDuration(SLIDE_MS).withEndAction(this::showNext).start();
            } else {
                showNext();
            }
        }, SHOW_MS);
    }

    private TextView label(Context context, int sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setSingleLine(false);
        view.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
