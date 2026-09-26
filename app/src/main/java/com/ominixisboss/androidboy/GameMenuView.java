package com.ominixisboss.androidboy;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-game menu: full screen, grouped into sections, each item with an icon, a title, a line
 * about what it does, and an arrow.
 */
final class GameMenuView extends FrameLayout {
    static final int BACKGROUND = 0xFF161213;
    static final int TITLE = 0xFFF4E8EC;
    static final int SUBTITLE = 0xFFB5A7AC;
    static final int SECTION = 0xFFA0939A;

    /** One row: what it's called, a line about it, and what it does. */
    static final class Item {
        final int icon;
        final String title;
        final String subtitle;
        final Runnable action;

        Item(int icon, String title, String subtitle, Runnable action) {
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.action = action;
        }
    }

    /** A group of items under a heading. */
    static final class Section {
        final String title;
        final List<Item> items = new ArrayList<>();

        Section(String title) {
            this.title = title;
        }

        Section add(int icon, String title, String subtitle, Runnable action) {
            items.add(new Item(icon, title, subtitle, action));
            return this;
        }
    }

    interface Listener {
        /** An item was chosen; the menu closes after its action runs. */
        void onItemChosen(Item item);

        void onClose();
    }

    GameMenuView(Context context, String heading, List<Section> sections, Listener listener) {
        super(context);
        setBackgroundColor(BACKGROUND);
        setClickable(true); // Nothing underneath reacts while it's open.
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(14), dp(10), dp(14));
        TextView title = new TextView(context);
        title.setText(heading);
        title.setTextColor(TITLE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        ImageView close = new ImageView(context);
        close.setImageDrawable(Icons.drawable(Icons.CLOSE, TITLE));
        close.setContentDescription("Close the menu");
        int pad = dp(12);
        close.setPadding(pad, pad, pad, pad);
        close.setBackground(ripple());
        close.setOnClickListener(v -> listener.onClose());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        column.addView(header);
        column.addView(divider());

        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(24));
        for (int s = 0; s < sections.size(); s++) {
            Section section = sections.get(s);
            if (section.items.isEmpty()) continue;
            if (list.getChildCount() > 0) list.addView(divider());
            TextView label = new TextView(context);
            label.setText(section.title);
            label.setAllCaps(true);
            label.setLetterSpacing(0.08f);
            label.setTextColor(SECTION);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setPadding(dp(20), dp(18), dp(20), dp(6));
            list.addView(label);
            for (Item item : section.items) list.addView(row(item, listener));
        }
        scroll.addView(list);
        column.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        addView(column);
    }

    private View row(Item item, Listener listener) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(12), dp(14), dp(12));
        row.setMinimumHeight(dp(64));
        row.setBackground(ripple());
        row.setOnClickListener(v -> listener.onItemChosen(item));

        ImageView icon = new ImageView(context);
        icon.setImageDrawable(Icons.drawable(item.icon, TITLE));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        iconParams.setMarginEnd(dp(18));
        row.addView(icon, iconParams);

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText(item.title);
        title.setTextColor(TITLE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        text.addView(title);
        if (item.subtitle != null && !item.subtitle.isEmpty()) {
            TextView subtitle = new TextView(context);
            subtitle.setText(item.subtitle);
            subtitle.setTextColor(SUBTITLE);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            text.addView(subtitle);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        ImageView chevron = new ImageView(context);
        chevron.setImageDrawable(Icons.drawable(Icons.CHEVRON, SUBTITLE));
        row.addView(chevron, new LinearLayout.LayoutParams(dp(20), dp(20)));
        return row;
    }

    /** Slides in from below. */
    void animateIn() {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        setAlpha(0f);
        setTranslationY(dp(40));
        animate().alpha(1f).translationY(0).setDuration(180).start();
    }

    private View divider() {
        View line = new View(getContext());
        line.setBackgroundColor(GameToolbars.DIVIDER);
        line.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return line;
    }

    private RippleDrawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, new android.graphics.drawable.ColorDrawable(0xFFFFFFFF));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
