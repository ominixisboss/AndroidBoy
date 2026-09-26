package com.ominixisboss.androidboy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

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

    /** The loaded game's achievements, unlocked ones first. */
    static void showList(Activity activity, Runnable onAccount, Runnable onDismiss) {
        Achievements achievements = Achievements.get(activity);
        String[] summary = achievements.gameSummary();
        List<Achievements.Achievement> list = achievements.achievementList();
        ListView view = new ListView(activity);
        view.setAdapter(new ArrayAdapter<Achievements.Achievement>(activity, 0, list) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Row row = convertView instanceof Row ? (Row) convertView : new Row(activity);
                row.bind(getItem(position));
                return row;
            }
        });
        String title = summary != null ? summary[0] : "Achievements";
        String subtitle = summary != null
                ? summary[1] + " of " + summary[2] + " unlocked · " + summary[3] + " of " + summary[4] + " points"
                    + (achievements.isHardcoreEnabled() ? " · hardcore" : "")
                : "";
        int pad = dp(activity, 20);
        view.setPadding(pad, 0, pad, 0);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(subtitle)
                .setView(view)
                .setPositiveButton("Done", null)
                .setNeutralButton("Account", (d, which) -> onAccount.run())
                .create();
        dialog.setOnDismissListener(d -> onDismiss.run());
        dialog.show();
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
            badge = new ImageView(activity);
            LayoutParams badgeParams = new LayoutParams(dp(activity, 44), dp(activity, 44));
            badgeParams.setMarginEnd(dp(activity, 12));
            addView(badge, badgeParams);
            LinearLayout text = new LinearLayout(activity);
            text.setOrientation(VERTICAL);
            title = text(activity, "", 15);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            detail = text(activity, "", 13);
            text.addView(title);
            text.addView(detail);
            addView(text, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
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

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
