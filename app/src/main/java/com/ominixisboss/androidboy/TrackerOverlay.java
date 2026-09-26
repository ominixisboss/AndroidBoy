package com.ominixisboss.androidboy;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small boxes in the corner of the game screen for RetroAchievements: the live score or time of
 * each leaderboard attempt in progress, and how far along an achievement is ("3/10") as it changes.
 */
final class TrackerOverlay extends LinearLayout {
    /** Progress only shows for a moment after it changes. */
    private static final long PROGRESS_MS = 3000;

    private final SparseArray<TextView> trackers = new SparseArray<>();
    private final LinearLayout progress;
    private final ImageView progressBadge;
    private final TextView progressText;
    private final Runnable hideProgress;

    TrackerOverlay(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.END);

        progress = new LinearLayout(context);
        progress.setOrientation(HORIZONTAL);
        progress.setGravity(Gravity.CENTER_VERTICAL);
        progress.setBackground(chipBackground());
        progress.setPadding(dp(6), dp(4), dp(10), dp(4));
        progressBadge = new ImageView(context);
        LayoutParams badgeParams = new LayoutParams(dp(24), dp(24));
        badgeParams.setMarginEnd(dp(6));
        progress.addView(progressBadge, badgeParams);
        progressText = chipText(context);
        progress.addView(progressText);
        progress.setVisibility(GONE);
        addView(progress, chipParams());
        hideProgress = () -> progress.setVisibility(GONE);
    }

    /** Layout for the game's FrameLayout: bottom right, above the system bars' area. */
    FrameLayout.LayoutParams layoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = dp(12);
        params.rightMargin = dp(12);
        return params;
    }

    /** Handles tracker and progress events; returns false for others (the banner shows those). */
    boolean handle(Achievements.Event event) {
        switch (event.type) {
            case Achievements.EVENT_TRACKER_SHOW:
            case Achievements.EVENT_TRACKER_UPDATE: {
                TextView tracker = trackers.get(event.points);
                if (tracker == null) {
                    tracker = chipText(getContext());
                    tracker.setBackground(chipBackground());
                    tracker.setPadding(dp(10), dp(4), dp(10), dp(4));
                    tracker.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                    trackers.put(event.points, tracker);
                    addView(tracker, chipParams());
                }
                tracker.setText(event.description);
                return true;
            }
            case Achievements.EVENT_TRACKER_HIDE: {
                TextView tracker = trackers.get(event.points);
                if (tracker != null) {
                    removeView(tracker);
                    trackers.remove(event.points);
                }
                return true;
            }
            case Achievements.EVENT_PROGRESS_SHOW:
            case Achievements.EVENT_PROGRESS_UPDATE:
                progressText.setText(event.description == null || event.description.isEmpty()
                        ? event.title : event.title + "  " + event.description);
                progressBadge.setVisibility(event.imageUrl != null ? VISIBLE : GONE);
                if (event.imageUrl != null) Badges.load(progressBadge, event.imageUrl);
                progress.setVisibility(VISIBLE);
                removeCallbacks(hideProgress);
                postDelayed(hideProgress, PROGRESS_MS);
                return true;
            case Achievements.EVENT_PROGRESS_HIDE:
                removeCallbacks(hideProgress);
                progress.setVisibility(GONE);
                return true;
            default:
                return false;
        }
    }

    /** Clears everything, e.g. when the game is reset. */
    void clear() {
        for (int i = 0; i < trackers.size(); i++) removeView(trackers.valueAt(i));
        trackers.clear();
        removeCallbacks(hideProgress);
        progress.setVisibility(GONE);
    }

    private LayoutParams chipParams() {
        LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(6);
        return params;
    }

    private GradientDrawable chipBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xCC141418);
        background.setCornerRadius(dp(10));
        return background;
    }

    private TextView chipText(Context context) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(Color.WHITE);
        view.setMaxWidth(dp(220));
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
