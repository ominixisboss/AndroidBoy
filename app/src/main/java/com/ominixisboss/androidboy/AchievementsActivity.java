package com.ominixisboss.androidboy;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The player's RetroAchievements: points, and every Game Boy, Game Boy Color and Game Boy Advance
 * game they've unlocked achievements in, with their progress. Also where to log in or out.
 */
public final class AchievementsActivity extends Activity {
    private final List<Achievements.GameProgress> games = new ArrayList<>();
    private Achievements achievements;
    private ArrayAdapter<Achievements.GameProgress> adapter;
    private ImageView avatar;
    private TextView name;
    private TextView points;
    private TextView status;
    private Button accountButton;
    private Button profileButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.retroachievements);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        achievements = Achievements.get(this);

        ListView list = new ListView(this);
        list.setDividerHeight(0);
        int pad = dp(16);
        list.setPadding(pad, 0, pad, pad);
        list.setClipToPadding(false);
        list.addHeaderView(header(), null, false);
        adapter = new ArrayAdapter<Achievements.GameProgress>(this, 0, games) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Row row = convertView instanceof Row ? (Row) convertView : new Row();
                row.bind(getItem(position));
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount();
            if (index >= 0 && index < games.size()) open(games.get(index).pageUrl());
        });
        setContentView(list);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private View header() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, dp(16), 0, dp(8));

        LinearLayout who = new LinearLayout(this);
        who.setOrientation(LinearLayout.HORIZONTAL);
        who.setGravity(Gravity.CENTER_VERTICAL);
        avatar = new ImageView(this);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(64), dp(64));
        avatarParams.setMarginEnd(dp(16));
        who.addView(avatar, avatarParams);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        name = text("", 22, true);
        points = text("", 15, false);
        text.addView(name);
        text.addView(points);
        who.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(who);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(8), 0, 0);
        accountButton = new Button(this);
        accountButton.setOnClickListener(v -> AchievementDialogs.showAccount(this, false, null, this::refresh));
        profileButton = new Button(this);
        profileButton.setText("Profile on website");
        profileButton.setOnClickListener(v -> open(Achievements.SITE + "/user/" + Uri.encode(achievements.username())));
        buttons.addView(accountButton);
        buttons.addView(profileButton);
        header.addView(buttons);

        status = text("", 14, false);
        status.setPadding(0, dp(12), 0, dp(4));
        status.setOnClickListener(v -> refresh());
        header.addView(status);
        return header;
    }

    private void refresh() {
        games.clear();
        adapter.notifyDataSetChanged();
        if (!achievements.hasAccount()) {
            avatar.setVisibility(View.GONE);
            name.setText("Not logged in");
            points.setText("Log in with a free retroachievements.org account to earn achievements as you play, "
                    + "and see them here.");
            accountButton.setText("Log in");
            profileButton.setVisibility(View.GONE);
            status.setText("");
            return;
        }
        accountButton.setText("Account");
        profileButton.setVisibility(View.VISIBLE);
        String[] info = achievements.userInfo();
        if (info == null) {
            avatar.setVisibility(View.GONE);
            name.setText(achievements.username());
            status.setText("");
            points.setText("Connecting…");
            // Checks the saved login again; after a failure (say, no network) this also retries it.
            achievements.reconnect(error -> {
                if (isDestroyed()) return;
                if (error == null) {
                    refresh();
                } else if (!achievements.hasAccount()) {
                    refresh(); // The login expired; show the log in button.
                } else {
                    points.setText("Couldn't connect: " + error + ". Tap to try again.");
                    points.setOnClickListener(v -> refresh());
                }
            });
            return;
        }
        name.setText(info[0]);
        points.setText(info[1] + " points · " + info[2] + " softcore");
        points.setOnClickListener(null);
        avatar.setVisibility(info.length > 3 && !info[3].isEmpty() ? View.VISIBLE : View.GONE);
        if (info.length > 3) Badges.load(avatar, info[3]);
        status.setText("Loading your games…");
        achievements.fetchProgress((list, error) -> {
            if (isDestroyed()) return;
            if (list == null) {
                status.setText("Couldn't load your progress: " + error + ". Tap to try again.");
                return;
            }
            games.clear();
            games.addAll(list);
            adapter.notifyDataSetChanged();
            status.setText(summary(list));
        });
    }

    /** "3 games · 42 achievements (30 hardcore) · 1 mastered". */
    static String summary(List<Achievements.GameProgress> list) {
        if (list.isEmpty()) return "No Game Boy achievements yet. Play a game with an achievement set to start.";
        int unlocked = 0;
        int hardcore = 0;
        int mastered = 0;
        for (Achievements.GameProgress game : list) {
            unlocked += game.unlocked;
            hardcore += game.unlockedHardcore;
            if (game.mastered()) mastered++;
        }
        StringBuilder text = new StringBuilder();
        text.append(list.size()).append(list.size() == 1 ? " game · " : " games · ")
                .append(unlocked).append(unlocked == 1 ? " achievement" : " achievements");
        if (hardcore > 0) text.append(" (").append(hardcore).append(" hardcore)");
        if (mastered > 0) text.append(" · ").append(mastered).append(" mastered");
        return text.toString();
    }

    /** "12 of 20 unlocked · 8 hardcore", or "Mastered · 20 of 20". */
    static String progressText(Achievements.GameProgress game) {
        if (game.mastered()) return "Mastered · " + game.total + " of " + game.total;
        String text = game.unlocked + " of " + game.total + " unlocked";
        if (game.unlockedHardcore > 0) text += " · " + game.unlockedHardcore + " hardcore";
        return text;
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    /** A game: badge, title, progress text and a bar. */
    private final class Row extends LinearLayout {
        final ImageView badge;
        final TextView title;
        final TextView detail;
        final ProgressBar bar;

        Row() {
            super(AchievementsActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, dp(8), 0, dp(8));
            badge = new ImageView(getContext());
            LayoutParams badgeParams = new LayoutParams(dp(56), dp(56));
            badgeParams.setMarginEnd(dp(14));
            addView(badge, badgeParams);
            LinearLayout text = new LinearLayout(getContext());
            text.setOrientation(VERTICAL);
            title = text("", 16, true);
            detail = text("", 13, false);
            bar = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
            text.addView(title);
            text.addView(detail);
            text.addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, dp(8)));
            addView(text, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        }

        void bind(Achievements.GameProgress game) {
            title.setText(game.title);
            detail.setText(progressText(game));
            bar.setMax(Math.max(1, game.total));
            bar.setProgress(game.unlocked);
            bar.setSecondaryProgress(0);
            Badges.load(badge, game.badgeUrl);
        }
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
