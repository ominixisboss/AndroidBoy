package com.ominixisboss.androidboy;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** RetroAchievements screens: the account (log in or out, hardcore mode) and a game's achievements. */
final class AchievementDialogs {
    static final String SITE = "https://retroachievements.org";

    interface HardcoreListener {
        /** Hardcore mode was switched; called after the setting is saved. */
        void onHardcoreChanged(boolean enabled);
    }

    private AchievementDialogs() {}

    /**
     * The account: a login form, or who's logged in with the hardcore option. {@code inGame}
     * warns that turning hardcore on restarts the game. {@code onDismiss} runs when it closes.
     */
    static void showAccount(Activity activity, boolean inGame, HardcoreListener hardcoreListener, Runnable onDismiss) {
        Achievements achievements = Achievements.get(activity);
        if (achievements.hasAccount()) {
            showLoggedIn(activity, achievements, inGame, hardcoreListener, onDismiss);
        } else {
            showLogin(activity, achievements, onDismiss);
        }
    }

    private static void showLogin(Activity activity, Achievements achievements, Runnable onDismiss) {
        LinearLayout form = column(activity);
        TextView intro = text(activity, "Earn achievements in your games with a free retroachievements.org account. "
                + "Your password is only used to log in; AndroidBoy keeps a login token, not the password.", 14);
        EditText username = new EditText(activity);
        username.setHint("Username");
        username.setSingleLine(true);
        username.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        EditText password = new EditText(activity);
        password.setHint("Password");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView status = text(activity, "", 14);
        status.setVisibility(View.GONE);
        form.addView(intro);
        form.addView(username);
        form.addView(password);
        form.addView(status);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("RetroAchievements")
                .setView(form)
                .setPositiveButton("Log in", null) // Set below, so a failed login keeps the dialog open.
                .setNeutralButton("Create account", (d, which) -> openSite(activity, SITE + "/createaccount.php"))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(d -> onDismiss.run());
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String user = username.getText().toString().trim();
            String pass = password.getText().toString();
            if (user.isEmpty() || pass.isEmpty()) {
                status.setText("Enter your username and password.");
                status.setVisibility(View.VISIBLE);
                return;
            }
            v.setEnabled(false);
            status.setText("Logging in…");
            status.setVisibility(View.VISIBLE);
            achievements.login(user, pass, error -> {
                if (error == null) {
                    Toast.makeText(activity, "Logged in as " + achievements.username(), Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                } else {
                    status.setText(error);
                    v.setEnabled(true);
                }
            });
        });
    }

    private static void showLoggedIn(Activity activity, Achievements achievements, boolean inGame,
                                     HardcoreListener hardcoreListener, Runnable onDismiss) {
        LinearLayout content = column(activity);
        String[] info = achievements.userInfo();
        String who = info != null
                ? "Logged in as " + info[0] + "\n" + info[1] + " points (" + info[2] + " softcore)"
                : "Logged in as " + achievements.username() + " (connecting…)";
        content.addView(text(activity, who, 15));
        CheckBox hardcore = new CheckBox(activity);
        hardcore.setText("Hardcore mode");
        hardcore.setChecked(achievements.isHardcoreEnabled());
        content.addView(hardcore);
        content.addView(text(activity, "Unlocks count as hardcore, the way RetroAchievements ranks players. "
                + "Loading save states, rewinding and cheats are off while it's on."
                + (inGame ? " Turning it on restarts the game." : ""), 13));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("RetroAchievements")
                .setView(content)
                .setPositiveButton("Done", null)
                .setNeutralButton("Log out", (d, which) -> {
                    achievements.logout();
                    Toast.makeText(activity, "Logged out", Toast.LENGTH_SHORT).show();
                })
                .create();
        hardcore.setOnCheckedChangeListener((button, checked) -> {
            achievements.setHardcoreEnabled(checked);
            if (hardcoreListener != null) hardcoreListener.onHardcoreChanged(checked);
        });
        dialog.setOnDismissListener(d -> onDismiss.run());
        dialog.show();
    }

    /**
     * The loaded game's achievements, locked and unlocked, grouped the way RetroAchievements groups
     * them; tap one for its details. {@code richPresence} (what the game says the player is doing)
     * is shown at the top if the game has it. {@code onProfile} opens the player's profile.
     */
    static void showList(Activity activity, String richPresence, Runnable onAccount, Runnable onProfile,
                         Runnable onDismiss) {
        Achievements achievements = Achievements.get(activity);
        String[] summary = achievements.gameSummary();
        List<Achievements.Achievement> list = achievements.achievementList();
        if (summary == null || list.isEmpty()) {
            showNone(activity, summary != null ? summary[0] : null, onAccount, onProfile, onDismiss);
            return;
        }
        boolean hardcore = achievements.isHardcoreEnabled();
        List<Object> items = group(list);
        List<Achievements.Leaderboard> leaderboards = achievements.leaderboards();
        Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);

        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(GameMenuView.BACKGROUND);
        page.setFitsSystemWindows(true);
        page.addView(topBar(activity, "Achievements", dialog::dismiss));
        page.addView(divider(activity));

        ListView view = new ListView(activity);
        view.setDivider(null);
        view.setSelector(new ColorDrawable(0x22FFFFFF));
        view.setClipToPadding(false);
        int pad = dp(activity, 20);
        view.setPadding(pad, 0, pad, dp(activity, 24));
        view.addHeaderView(summaryHeader(activity, summary, hardcore, richPresence,
                () -> {
                    dialog.dismiss();
                    onProfile.run();
                },
                leaderboards.isEmpty() ? null : () -> showLeaderboards(activity, leaderboards),
                () -> {
                    dialog.dismiss();
                    onAccount.run();
                }), null, false);
        view.setAdapter(new ArrayAdapter<Object>(activity, 0, items) {
            @Override
            public int getViewTypeCount() {
                return 2;
            }

            @Override
            public int getItemViewType(int position) {
                return getItem(position) instanceof String ? 0 : 1;
            }

            @Override
            public boolean isEnabled(int position) {
                return getItem(position) instanceof Achievements.Achievement;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Object item = getItem(position);
                if (item instanceof String) {
                    TextView heading = convertView instanceof TextView ? (TextView) convertView : sectionLabel(activity);
                    heading.setText((String) item);
                    return heading;
                }
                Row row = convertView instanceof Row ? (Row) convertView : new Row(activity);
                row.bind((Achievements.Achievement) item);
                return row;
            }
        });
        view.setOnItemClickListener((parent, row, position, id) -> {
            Object item = parent.getItemAtPosition(position);
            if (item instanceof Achievements.Achievement) {
                showDetails(activity, (Achievements.Achievement) item, hardcore);
            }
        });
        page.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        dialog.setContentView(page);
        dialog.setOnDismissListener(d -> onDismiss.run());
        dialog.setOnShowListener(d -> hideSystemBars(dialog.getWindow()));
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(GameMenuView.BACKGROUND));
            window.setWindowAnimations(android.R.style.Animation_InputMethod); // Slides up from below.
        }
        dialog.show();
    }

    /** The page's top bar: a back arrow and its title, like the Game Menu's. */
    private static View topBar(Activity activity, String heading, Runnable onBack) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(activity, 6), dp(activity, 8), dp(activity, 20), dp(activity, 8));
        ImageView back = new ImageView(activity);
        back.setImageDrawable(Icons.drawable(Icons.BACK, GameMenuView.TITLE));
        back.setContentDescription("Back to the game");
        int pad = dp(activity, 12);
        back.setPadding(pad, pad, pad, pad);
        back.setBackground(ripple());
        back.setOnClickListener(v -> onBack.run());
        bar.addView(back, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
        TextView title = new TextView(activity);
        title.setText(heading);
        title.setTextColor(GameMenuView.TITLE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(activity, 8), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return bar;
    }

    private static View divider(Activity activity) {
        View line = new View(activity);
        line.setBackgroundColor(GameToolbars.DIVIDER);
        line.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(activity, 1))));
        return line;
    }

    /** A group heading in the list: small capitals, like the Game Menu's sections. */
    private static TextView sectionLabel(Activity activity) {
        TextView label = new TextView(activity);
        label.setAllCaps(true);
        label.setLetterSpacing(0.08f);
        label.setTextColor(GameMenuView.SECTION);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(0, dp(activity, 18), 0, dp(activity, 6));
        return label;
    }

    /** A rounded, outlined text button for the summary's actions. */
    private static TextView chip(Activity activity, String label, Runnable action) {
        TextView chip = new TextView(activity);
        chip.setText(label);
        chip.setTextColor(GameMenuView.TITLE);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setGravity(Gravity.CENTER);
        int h = dp(activity, 16);
        chip.setPadding(h, dp(activity, 8), h, dp(activity, 8));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(activity, 18));
        shape.setStroke(Math.max(1, dp(activity, 1)), GameToolbars.DIVIDER);
        shape.setColor(0x14FFFFFF);
        chip.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null));
        chip.setOnClickListener(v -> action.run());
        return chip;
    }

    private static RippleDrawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, new ColorDrawable(0xFFFFFFFF));
    }

    private static void hideSystemBars(Window window) {
        if (window == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /**
     * The list's rows: a heading (a String, "Locked · 12") before each group, then its achievements.
     * Groups come from rcheevos ("Recently Unlocked", "Almost There", "Locked", "Unlocked"…), or,
     * without them, locked and unlocked.
     */
    static List<Object> group(List<Achievements.Achievement> list) {
        List<Object> items = new ArrayList<>();
        int i = 0;
        while (i < list.size()) {
            String name = groupName(list.get(i));
            int end = i;
            while (end < list.size() && groupName(list.get(end)).equals(name)) end++;
            items.add(name + "  ·  " + (end - i));
            items.addAll(list.subList(i, end));
            i = end;
        }
        return items;
    }

    private static String groupName(Achievements.Achievement achievement) {
        if (!achievement.group.isEmpty()) return achievement.group;
        return achievement.unlocked ? "Unlocked" : "Locked";
    }

    /** The game's badge and name, how much is unlocked, and what the player is doing. */
    private static View summaryHeader(Activity activity, String[] summary, boolean hardcore, String richPresence,
                                      Runnable onProfile, Runnable onLeaderboards, Runnable onAccount) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, dp(activity, 20), 0, dp(activity, 4));
        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        String badgeUrl = summary.length > 6 ? summary[6] : "";
        if (!badgeUrl.isEmpty()) {
            ImageView badge = new ImageView(activity);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(activity, 56), dp(activity, 56));
            params.setMarginEnd(dp(activity, 14));
            top.addView(badge, params);
            Badges.load(badge, badgeUrl);
        }
        LinearLayout names = new LinearLayout(activity);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(activity, summary[0], 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(GameMenuView.TITLE);
        names.addView(title);
        TextView totals = text(activity, summary[1] + " of " + summary[2] + " unlocked  ·  "
                + summary[3] + " of " + summary[4] + " points" + (hardcore ? "  ·  hardcore" : ""), 14);
        totals.setTextColor(GameMenuView.SUBTITLE);
        names.addView(totals);
        top.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(top);

        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(Math.max(1, parse(summary[2])));
        bar.setProgress(parse(summary[1]));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = dp(activity, 8);
        header.addView(bar, barParams);
        if (richPresence != null && !richPresence.isEmpty()) {
            TextView now = text(activity, "Now: " + richPresence, 14);
            now.setTextColor(GameMenuView.SUBTITLE);
            header.addView(now);
        }
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(activity, 10), 0, dp(activity, 2));
        addChip(actions, chip(activity, "My profile", onProfile));
        if (onLeaderboards != null) addChip(actions, chip(activity, "Leaderboards", onLeaderboards));
        addChip(actions, chip(activity, "Account", onAccount));
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(activity);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(actions);
        header.addView(scroller);
        return header;
    }

    private static void addChip(LinearLayout row, View chip) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(dp(row.getContext(), 8));
        row.addView(chip, params);
    }

    private static int parse(String number) {
        try {
            return Integer.parseInt(number);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * When the game has no achievements (or RetroAchievements doesn't know it): says so, and offers
     * the player's profile instead. {@code gameTitle} is null for a game it doesn't know.
     */
    private static void showNone(Activity activity, String gameTitle, Runnable onAccount, Runnable onProfile,
                                 Runnable onDismiss) {
        Achievements achievements = Achievements.get(activity);
        String message = achievements.isLoggingIn()
                ? "Still connecting to RetroAchievements. Try again in a moment."
                : gameTitle != null
                ? "RetroAchievements doesn't have any achievements for " + gameTitle + " yet."
                : "RetroAchievements doesn't have any achievements for this game.";
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("No achievements")
                .setMessage(message + "\n\nYou can still see your profile: your points and every game you've "
                        + "earned achievements in.")
                .setPositiveButton("View my profile", (d, which) -> onProfile.run())
                .setNeutralButton("Account", (d, which) -> onAccount.run())
                .setNegativeButton("Close", null)
                .create();
        dialog.setOnDismissListener(d -> onDismiss.run());
        dialog.show();
    }

    /** One achievement in full: badge, description, whether and when it was unlocked, progress, rarity. */
    static void showDetails(Activity activity, Achievements.Achievement achievement, boolean hardcore) {
        LinearLayout content = column(activity);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(content.getPaddingLeft(), dp(activity, 24), content.getPaddingRight(), dp(activity, 8));
        ImageView badge = new ImageView(activity);
        content.addView(badge, new LinearLayout.LayoutParams(dp(activity, 96), dp(activity, 96)));
        boolean earned = achievement.unlockedIn != 0;
        Badges.load(badge, earned ? achievement.unlockedBadgeUrl : achievement.badgeUrl);
        if (!earned) badge.setAlpha(0.8f);

        TextView title = text(activity, achievement.title, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(activity, 12), 0, 0);
        content.addView(title, matchWidth());
        TextView points = text(activity, achievement.points + (achievement.points == 1 ? " point" : " points"), 14);
        points.setGravity(Gravity.CENTER);
        content.addView(points, matchWidth());
        TextView description = text(activity, achievement.description, 16);
        description.setGravity(Gravity.CENTER);
        description.setPadding(0, dp(activity, 10), 0, dp(activity, 6));
        content.addView(description, matchWidth());

        if (!achievement.unlocked && achievement.percent > 0) {
            ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgress(Math.round(achievement.percent * 10));
            content.addView(bar, matchWidth());
        }
        for (String line : detailLines(achievement, hardcore, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT))) {
            TextView detail = text(activity, line, 14);
            detail.setGravity(Gravity.CENTER);
            content.addView(detail, matchWidth());
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(content);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setView(scroll)
                .setPositiveButton("Close", null);
        if (achievement.id > 0) {
            builder.setNeutralButton("Website", (d, which) -> openSite(activity, achievement.pageUrl()));
        }
        builder.show();
    }

    /** The details under an achievement's description: status, progress, kind and rarity. */
    static List<String> detailLines(Achievements.Achievement achievement, boolean hardcore, DateFormat dates) {
        List<String> lines = new ArrayList<>();
        boolean inHardcore = (achievement.unlockedIn & Achievements.Achievement.UNLOCKED_HARDCORE) != 0;
        String when = achievement.unlockTime > 0 ? " on " + dates.format(new Date(achievement.unlockTime * 1000)) : "";
        if (achievement.unlocked) {
            lines.add((inHardcore ? "✓ Unlocked in hardcore" : "✓ Unlocked") + when);
        } else if (achievement.unlockedIn != 0) {
            // Hardcore is on, and this was only earned without it.
            lines.add("Unlocked in softcore" + when + ". Earn it again with hardcore on to count it in hardcore.");
        } else {
            lines.add("Locked");
        }
        if (!achievement.unlocked && !achievement.progress.isEmpty()) {
            lines.add("Progress: " + achievement.progress
                    + (achievement.percent > 0 ? String.format(Locale.ROOT, " (%d%%)", (int) achievement.percent) : ""));
        }
        switch (achievement.type) {
            case Achievements.Achievement.TYPE_MISSABLE:
                lines.add("Missable: it can be missed for good if you don't earn it at the right point.");
                break;
            case Achievements.Achievement.TYPE_PROGRESSION:
                lines.add("Progression: earned by playing through the game.");
                break;
            case Achievements.Achievement.TYPE_WIN:
                lines.add("Win condition: earned by beating the game.");
                break;
            default:
                break;
        }
        if (achievement.rarity > 0) {
            lines.add(String.format(Locale.ROOT, "Unlocked by %s of players (%s in hardcore)",
                    percent(achievement.rarity), percent(achievement.rarityHardcore)));
        }
        return lines;
    }

    /** "12.5%", "3%", "<0.1%". */
    static String percent(float value) {
        if (value > 0 && value < 0.1f) return "<0.1%";
        String text = String.format(Locale.ROOT, "%.1f", value);
        if (text.endsWith(".0")) text = text.substring(0, text.length() - 2);
        return text + "%";
    }

    private static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static void showLeaderboards(Activity activity, List<Achievements.Leaderboard> leaderboards) {
        Settings.TwoLineAdapter adapter = new Settings.TwoLineAdapter(activity, leaderboards.size()) {
            @Override
            void bind(int position, TextView title, TextView detail) {
                title.setText(leaderboards.get(position).title);
                detail.setText(leaderboards.get(position).description);
            }
        };
        new AlertDialog.Builder(activity)
                .setTitle("Leaderboards")
                .setView(Settings.listView(activity, adapter, position -> showLeaderboard(activity, leaderboards.get(position))))
                .setPositiveButton("Back", null)
                .show();
    }

    /** The top ten, and the entries around the player. */
    private static void showLeaderboard(Activity activity, Achievements.Leaderboard leaderboard) {
        Achievements achievements = Achievements.get(activity);
        LinearLayout content = column(activity);
        TextView description = text(activity, leaderboard.description, 13);
        TextView top = text(activity, "Loading…", 14);
        top.setTypeface(Typeface.MONOSPACE);
        TextView around = text(activity, "", 14);
        around.setTypeface(Typeface.MONOSPACE);
        content.addView(description);
        content.addView(heading(activity, "Top 10"));
        content.addView(top);
        content.addView(heading(activity, "Around you"));
        content.addView(around);
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(leaderboard.title)
                .setView(scroll)
                .setPositiveButton("Back", null)
                .setNeutralButton("Website", (d, which) -> openSite(activity, leaderboard.pageUrl()))
                .show();
        String me = achievements.username();
        achievements.fetchLeaderboard(leaderboard.id, false, 10, (entries, total, error) -> {
            if (!dialog.isShowing()) return;
            top.setText(entries == null ? error : formatEntries(entries, me, total));
            // One request at a time for each leaderboard: ask for the player's neighbours next.
            around.setText("Loading…");
            achievements.fetchLeaderboard(leaderboard.id, true, 7, (nearby, count, problem) -> {
                if (!dialog.isShowing()) return;
                around.setText(nearby == null ? problem
                        : nearby.isEmpty() ? "You haven't got a score on this one yet." : formatEntries(nearby, me, count));
            });
        });
    }

    /** "  1  Champion        004321", the player's own row marked with a ▶. */
    static String formatEntries(List<Achievements.LeaderboardEntry> entries, String me, int total) {
        if (entries.isEmpty()) return "No scores yet.";
        StringBuilder text = new StringBuilder();
        for (Achievements.LeaderboardEntry entry : entries) {
            if (text.length() > 0) text.append('\n');
            boolean mine = entry.user.equalsIgnoreCase(me == null ? "" : me);
            text.append(mine ? "▶" : " ")
                    .append(String.format(java.util.Locale.ROOT, "%4d  %-16s %s", entry.rank,
                            entry.user.length() > 16 ? entry.user.substring(0, 15) + "…" : entry.user, entry.score));
        }
        if (total > 0) text.append("\n\n").append(total).append(total == 1 ? " player" : " players");
        return text.toString();
    }

    private static TextView heading(Activity activity, String value) {
        TextView view = text(activity, value, 14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(activity, 12), 0, dp(activity, 2));
        return view;
    }

    /** One achievement: badge, title with points, description and progress. */
    private static final class Row extends LinearLayout {
        final ImageView badge;
        final TextView title;
        final TextView detail;

        Row(Activity activity) {
            super(activity);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int pad = dp(activity, 8);
            setPadding(0, pad, 0, pad);
            setMinimumHeight(dp(activity, 64));
            badge = new ImageView(activity);
            LayoutParams badgeParams = new LayoutParams(dp(activity, 48), dp(activity, 48));
            badgeParams.setMarginEnd(dp(activity, 12));
            addView(badge, badgeParams);
            LinearLayout text = new LinearLayout(activity);
            text.setOrientation(VERTICAL);
            title = text(activity, "", 16);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(GameMenuView.TITLE);
            detail = text(activity, "", 14);
            detail.setTextColor(GameMenuView.SUBTITLE);
            text.addView(title);
            text.addView(detail);
            addView(text, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            ImageView chevron = new ImageView(activity);
            chevron.setImageDrawable(Icons.drawable(Icons.CHEVRON, GameMenuView.SUBTITLE));
            LayoutParams chevronParams = new LayoutParams(dp(activity, 18), dp(activity, 18));
            chevronParams.setMarginStart(dp(activity, 8));
            addView(chevron, chevronParams);
        }

        void bind(Achievements.Achievement achievement) {
            title.setText((achievement.unlocked ? "✓ " : "") + achievement.title + " · " + achievement.points
                    + (achievement.points == 1 ? " point" : " points"));
            String progress = achievement.progress.isEmpty() || achievement.unlocked ? "" : " (" + achievement.progress + ")";
            detail.setText(achievement.description + progress);
            setAlpha(achievement.unlocked ? 1f : 0.7f);
            Badges.load(badge, achievement.badgeUrl);
        }
    }

    private static void openSite(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, url, Toast.LENGTH_LONG).show();
        }
    }

    private static LinearLayout column(Activity activity) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 20);
        layout.setPadding(pad, dp(activity, 8), pad, 0);
        return layout;
    }

    private static TextView text(Activity activity, String value, int sp) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setPadding(0, dp(activity, 4), 0, dp(activity, 4));
        return view;
    }

    private static int dp(android.content.Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
