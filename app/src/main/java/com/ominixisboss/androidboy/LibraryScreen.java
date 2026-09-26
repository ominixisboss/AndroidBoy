package com.ominixisboss.androidboy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The game library screen: a header (title, search, add, menu), then {@link GameLibrary}'s list:
 * a "continue playing" card, strips of recent and favourite games, filter chips and a grid of
 * covers. Styled like the in-game menu. The activity supplies the games and what taps do.
 */
final class LibraryScreen extends FrameLayout {
    static final int BACKGROUND = GameMenuView.BACKGROUND;
    static final int SURFACE = 0xFF231D1F;
    static final int SURFACE_HIGH = GameToolbars.TILE;
    static final int TEXT = GameMenuView.TITLE;
    static final int SUBTEXT = GameMenuView.SUBTITLE;
    static final int ACCENT = 0xFFE0508C;
    /** Badge and placeholder colours per system (indexed like GameLibrary's filters). */
    static final int[] SYSTEM_COLORS = {0, 0, 0xFF7D8A55, 0xFF8A55D4, 0xFF4A66E0};

    interface Host {
        void play(File rom);
        void showOptions(File rom);
        void addGames();
        void showMenu(View anchor);
        void openHomebrewHub();
        /** The game's box art, or null (it may be downloaded later; call {@link #artChanged()}). */
        Bitmap art(File rom);
        boolean isFavourite(File rom);
        long lastPlayed(File rom);
    }

    private final Host host;
    private final ListView list;
    private final LinearLayout empty;
    private final TextView subtitle;
    private final EditText search;
    private final GameLibrary.View view = new GameLibrary.View();
    private final Adapter adapter = new Adapter();
    private final List<File> games = new ArrayList<>();
    private final Map<String, Integer> systems = new HashMap<>();
    private final List<GameLibrary.Item> items = new ArrayList<>();
    private String footer = "";
    private int columns = 3;

    LibraryScreen(Context context, Host host) {
        super(context);
        this.host = host;
        setBackgroundColor(BACKGROUND);
        setFitsSystemWindows(true);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        // Header: the app's name and how many games, then search, add and the menu.
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(14), dp(8), dp(8));
        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(context.getString(R.string.app_name), 26, TEXT, true);
        subtitle = text("", 13, SUBTEXT, false);
        titles.addView(title);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(iconButton(Icons.SEARCH, "Search", v -> toggleSearch()));
        header.addView(iconButton(Icons.PLUS, "Add games", v -> host.addGames()));
        header.addView(iconButton(Icons.MORE, "More", host::showMenu));
        column.addView(header);

        search = new EditText(context);
        search.setHint("Search your games");
        search.setSingleLine(true);
        search.setTextColor(TEXT);
        search.setHintTextColor(SUBTEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setBackground(rounded(SURFACE_HIGH, dp(14)));
        search.setPadding(dp(16), dp(10), dp(16), dp(10));
        search.setVisibility(GONE);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                view.query = s.toString();
                rebuild();
            }
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        searchParams.setMargins(dp(16), 0, dp(16), dp(8));
        column.addView(search, searchParams);

        list = new ListView(context);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(24));
        list.setAdapter(adapter);
        column.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        empty = emptyView();
        column.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        addView(column);
        showEmpty(true);
    }

    /** The games (and which system each is for, by file name). */
    void setGames(List<File> roms, Map<String, Integer> systemsByName) {
        games.clear();
        games.addAll(roms);
        systems.clear();
        systems.putAll(systemsByName);
        subtitle.setText(roms.size() == 1 ? "1 game" : roms.size() + " games");
        // A filter that no longer applies (e.g. the last favourite was removed) goes back to All.
        if (!GameLibrary.filters(games, systems, host::isFavourite).contains(view.filter)) view.filter = GameLibrary.ALL;
        rebuild();
    }

    void setFooter(String text) {
        footer = text;
        adapter.notifyDataSetChanged();
    }

    /** Box art arrived: redraw the covers. */
    void artChanged() {
        adapter.notifyDataSetChanged();
    }

    /** Back closes the search first. Returns whether it did. */
    boolean closeSearch() {
        if (search.getVisibility() != VISIBLE) return false;
        search.setText("");
        search.setVisibility(GONE);
        hideKeyboard();
        return true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Covers about 116dp wide, at least three a row on a phone.
        int fit = Math.max(3, (int) ((w / getResources().getDisplayMetrics().density - 24) / 116));
        if (fit != columns) {
            columns = fit;
            post(this::rebuild);
        }
    }

    private void rebuild() {
        items.clear();
        items.addAll(GameLibrary.arrange(games, systems, host::isFavourite, host::lastPlayed, view, columns));
        showEmpty(games.isEmpty());
        adapter.notifyDataSetChanged();
    }

    private void showEmpty(boolean show) {
        empty.setVisibility(show ? VISIBLE : GONE);
        list.setVisibility(show ? GONE : VISIBLE);
    }

    private void toggleSearch() {
        if (closeSearch()) return;
        search.setVisibility(VISIBLE);
        search.requestFocus();
        InputMethodManager keyboard = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    // ---- The list ----

    private final class Adapter extends BaseAdapter {
        // One more kind than GameLibrary's: the footer.
        private static final int FOOTER = 6;

        @Override
        public int getCount() {
            return items.size() + (items.isEmpty() ? 0 : 1);
        }

        @Override
        public Object getItem(int position) {
            return position < items.size() ? items.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 7;
        }

        @Override
        public int getItemViewType(int position) {
            return position < items.size() ? items.get(position).kind : FOOTER;
        }

        @Override
        public boolean isEnabled(int position) {
            return false; // Cards and chips handle their own taps.
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (position >= items.size()) {
                TextView footerView = convertView instanceof TextView ? (TextView) convertView : text("", 12, SUBTEXT, false);
                footerView.setGravity(Gravity.CENTER);
                footerView.setPadding(dp(16), dp(20), dp(16), dp(4));
                footerView.setText(footer);
                return footerView;
            }
            GameLibrary.Item item = items.get(position);
            switch (item.kind) {
                case GameLibrary.Item.HERO:
                    return heroView(item.rom);
                case GameLibrary.Item.HEADING: {
                    TextView heading = convertView instanceof TextView ? (TextView) convertView : headingView();
                    heading.setText(item.text.toUpperCase(java.util.Locale.getDefault()));
                    return heading;
                }
                case GameLibrary.Item.STRIP:
                    return stripView(item.games);
                case GameLibrary.Item.FILTERS:
                    return filtersView();
                case GameLibrary.Item.NOTHING: {
                    TextView nothing = text(item.text, 15, SUBTEXT, false);
                    nothing.setGravity(Gravity.CENTER);
                    nothing.setPadding(dp(24), dp(32), dp(24), dp(32));
                    return nothing;
                }
                default:
                    return rowView(item.games, convertView);
            }
        }
    }

    /** "Continue playing": the last game played, big, with a Continue button. */
    private View heroView(File rom) {
        Context context = getContext();
        int system = system(rom);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {blend(SYSTEM_COLORS[system], SURFACE, 0.55f), SURFACE});
        background.setCornerRadius(dp(22));
        card.setBackground(background);
        card.setPadding(dp(14), dp(14), dp(16), dp(14));
        card.setOnClickListener(v -> host.play(rom));
        card.setOnLongClickListener(v -> {
            host.showOptions(rom);
            return true;
        });
        card.setContentDescription("Continue " + RomLibrary.baseName(rom));

        View cover = cover(rom, dp(112));
        card.addView(cover, new LinearLayout.LayoutParams(dp(112), dp(112)));

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(16), 0, 0, 0);
        TextView label = text("CONTINUE PLAYING", 12, blend(SYSTEM_COLORS[system], Color.WHITE, 0.5f), true);
        label.setLetterSpacing(0.08f);
        TextView name = text(RomLibrary.baseName(rom), 20, TEXT, true);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView when = text(playedText(rom), 13, SUBTEXT, false);
        TextView play = text("▶  Continue", 15, Color.WHITE, true);
        play.setBackground(rounded(ACCENT, dp(20)));
        play.setPadding(dp(18), dp(9), dp(20), dp(9));
        play.setOnClickListener(v -> host.play(rom));
        text.addView(label);
        text.addView(name);
        text.addView(when);
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        playParams.topMargin = dp(10);
        text.addView(play, playParams);
        card.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        FrameLayout frame = new FrameLayout(context);
        frame.setPadding(dp(16), dp(4), dp(16), dp(4));
        frame.addView(card);
        return frame;
    }

    private TextView headingView() {
        TextView heading = text("", 13, GameMenuView.SECTION, true);
        heading.setLetterSpacing(0.08f);
        heading.setPadding(dp(20), dp(18), dp(20), dp(8));
        return heading;
    }

    /** A sideways-scrolling row of small covers. */
    private View stripView(List<File> roms) {
        Context context = getContext();
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (File rom : roms) {
            View card = coverCard(rom, dp(96));
            row.addView(card, new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        scroll.addView(row);
        return scroll;
    }

    /** The filter chips, and the sort order at the end. */
    private View filtersView() {
        Context context = getContext();
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(14), dp(12), dp(14), 0);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int filter : GameLibrary.filters(games, systems, host::isFavourite)) {
            boolean selected = filter == view.filter;
            TextView chip = chip(GameLibrary.FILTER_NAMES[filter], selected);
            chip.setOnClickListener(v -> {
                view.filter = filter;
                rebuild();
            });
            row.addView(chip);
        }
        TextView sort = chip(view.sort == GameLibrary.SORT_RECENT ? "Recent first" : "A–Z", false);
        android.graphics.drawable.Drawable sortIcon = Icons.drawable(Icons.SORT, SUBTEXT);
        sortIcon.setBounds(0, 0, dp(16), dp(16));
        sort.setCompoundDrawablesRelative(sortIcon, null, null, null);
        sort.setCompoundDrawablePadding(dp(6));
        sort.setContentDescription("Sort: " + sort.getText());
        sort.setOnClickListener(v -> {
            view.sort = view.sort == GameLibrary.SORT_RECENT ? GameLibrary.SORT_NAME : GameLibrary.SORT_RECENT;
            rebuild();
        });
        row.addView(sort);
        scroll.addView(row);
        return scroll;
    }

    private TextView chip(String label, boolean selected) {
        TextView chip = text(label, 14, selected ? Color.WHITE : TEXT, selected);
        chip.setBackground(selected ? rounded(ACCENT, dp(18)) : outlined(SURFACE, GameToolbars.DIVIDER, dp(18)));
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(dp(8));
        chip.setLayoutParams(params);
        return chip;
    }

    /** A grid row of covers, each 1/columns of the width. */
    private View rowView(List<File> roms, View convertView) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), 0, dp(12), dp(6));
        int cellWidth = (getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels) - dp(24);
        int coverSize = cellWidth / columns - dp(8);
        for (int i = 0; i < columns; i++) {
            View cell = i < roms.size() ? coverCard(roms.get(i), coverSize) : new View(context);
            row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        return row;
    }

    /** A cover with the game's name under it and its system badge; tap to play, long-press for options. */
    private View coverCard(File rom, int size) {
        Context context = getContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(4), dp(4), dp(4), dp(8));
        card.setOnClickListener(v -> host.play(rom));
        card.setOnLongClickListener(v -> {
            host.showOptions(rom);
            return true;
        });
        card.setContentDescription(RomLibrary.baseName(rom));
        card.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, rounded(Color.WHITE, dp(16))));
        FrameLayout picture = new FrameLayout(context);
        picture.addView(cover(rom, size), new FrameLayout.LayoutParams(size, size));
        // The system and, for favourites, a star, over the cover's bottom corners.
        int system = system(rom);
        TextView badge = text(GameLibrary.BADGES[system], 10, Color.WHITE, true);
        badge.setBackground(rounded(SYSTEM_COLORS[system], dp(8)));
        badge.setPadding(dp(6), dp(1), dp(6), dp(1));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        badgeParams.setMargins(dp(6), 0, 0, dp(6));
        picture.addView(badge, badgeParams);
        if (host.isFavourite(rom)) {
            ImageView star = new ImageView(context);
            star.setImageDrawable(Icons.drawable(Icons.STAR, 0xFFFFD54F));
            star.setBackground(rounded(0x99000000, dp(12)));
            star.setPadding(dp(4), dp(4), dp(4), dp(4));
            star.setContentDescription("Favourite");
            FrameLayout.LayoutParams starParams = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.BOTTOM | Gravity.END);
            starParams.setMargins(0, 0, dp(6), dp(6));
            picture.addView(star, starParams);
        }
        card.addView(picture, new LinearLayout.LayoutParams(size, size));
        TextView name = text(RomLibrary.baseName(rom), 13, TEXT, false);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(dp(2), dp(6), dp(2), 0);
        card.addView(name, new LinearLayout.LayoutParams(size, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    /** The box art, rounded; or, without any, the system's colour with its name and the game's initials. */
    private View cover(File rom, int size) {
        Context context = getContext();
        int system = system(rom);
        Bitmap art = host.art(rom);
        View view;
        if (art != null) {
            ImageView image = new ImageView(context);
            image.setImageBitmap(art);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackgroundColor(SURFACE_HIGH);
            view = image;
        } else {
            LinearLayout placeholder = new LinearLayout(context);
            placeholder.setOrientation(LinearLayout.VERTICAL);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {
                    blend(SYSTEM_COLORS[system], Color.WHITE, 0.12f), blend(SYSTEM_COLORS[system], Color.BLACK, 0.45f)}));
            TextView initials = text(initials(RomLibrary.baseName(rom)), Math.max(16, size / dp(1) / 4), Color.WHITE, true);
            initials.setGravity(Gravity.CENTER);
            TextView label = text(GameLibrary.FILTER_NAMES[system].toUpperCase(java.util.Locale.ROOT), 9, 0xCCFFFFFF, true);
            label.setLetterSpacing(0.1f);
            label.setGravity(Gravity.CENTER);
            placeholder.addView(initials);
            placeholder.addView(label);
            view = placeholder;
        }
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14));
            }
        });
        view.setClipToOutline(true);
        return view;
    }

    /** "PR" for "Pokemon Red": the first letters of the first two words. */
    static String initials(String name) {
        List<String> words = CheatDatabase.words(CheatDatabase.stripTags(name));
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (out.length() == 2) break;
            if (!word.isEmpty()) out.append(Character.toUpperCase(word.charAt(0)));
        }
        return out.length() > 0 ? out.toString() : "?";
    }

    private String playedText(File rom) {
        long played = host.lastPlayed(rom);
        return played > 0
                ? "Played " + DateUtils.getRelativeTimeSpanString(played, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                : "Not played yet";
    }

    private int system(File rom) {
        Integer system = systems.get(rom.getName());
        return system != null ? system : GameLibrary.GB;
    }

    // ---- The empty library ----

    private LinearLayout emptyView() {
        Context context = getContext();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(32), dp(32), dp(32), dp(32));
        ImageView icon = new ImageView(context);
        icon.setImageDrawable(Icons.drawable(Icons.GAMEPAD, ACCENT));
        box.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView title = text("Your library is empty", 22, TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(16), 0, dp(6));
        box.addView(title);
        TextView detail = text(context.getString(R.string.empty_library), 15, SUBTEXT, false);
        detail.setGravity(Gravity.CENTER);
        box.addView(detail);
        TextView add = text("Add games", 16, Color.WHITE, true);
        add.setGravity(Gravity.CENTER);
        add.setBackground(rounded(ACCENT, dp(24)));
        add.setPadding(dp(28), dp(12), dp(28), dp(12));
        add.setOnClickListener(v -> host.addGames());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        addParams.topMargin = dp(24);
        box.addView(add, addParams);
        TextView homebrew = text("Find free homebrew games", 15, ACCENT, true);
        homebrew.setGravity(Gravity.CENTER);
        homebrew.setPadding(dp(16), dp(14), dp(16), dp(10));
        homebrew.setOnClickListener(v -> host.openHomebrewHub());
        box.addView(homebrew);
        return box;
    }

    // ---- Helpers ----

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private ImageView iconButton(int icon, String description, View.OnClickListener onClick) {
        ImageView button = new ImageView(getContext());
        button.setImageDrawable(Icons.drawable(icon, TEXT));
        button.setContentDescription(description);
        int pad = dp(12);
        button.setPadding(pad, pad, pad, pad);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, null));
        button.setOnClickListener(onClick);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }

    private GradientDrawable outlined(int color, int edge, float radius) {
        GradientDrawable shape = rounded(color, radius);
        shape.setStroke(Math.max(1, dp(1)), edge);
        return shape;
    }

    static int blend(int from, int to, float amount) {
        return SoftSkin.blend(from | 0xFF000000, to, amount);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
