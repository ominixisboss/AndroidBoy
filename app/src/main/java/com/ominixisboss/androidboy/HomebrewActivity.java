package com.ominixisboss.androidboy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Browses Homebrew Hub, the gbdev community's collection of free homebrew for the Game Boy and
 * Game Boy Color, and adds games to the library.
 */
public final class HomebrewActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Homebrew.Entry> entries = new ArrayList<>();
    private ArrayAdapter<Homebrew.Entry> adapter;
    private TextView status;
    private EditText search;
    private String platform = Homebrew.PLATFORM_GB;
    private String query = "";
    private int loadedPage;
    private int pageTotal = 1;
    /** Bumped for every new search, so results from an older one are dropped. */
    private int generation;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Homebrew Hub");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(8), pad, 0);

        search = new EditText(this);
        search.setHint("Search games, demos and music");
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                query = search.getText().toString();
                hideKeyboard();
                reload();
                return true;
            }
            return false;
        });
        root.addView(search);

        RadioGroup platforms = new RadioGroup(this);
        platforms.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton gb = new RadioButton(this);
        gb.setId(View.generateViewId());
        gb.setText("Game Boy");
        RadioButton gbc = new RadioButton(this);
        gbc.setId(View.generateViewId());
        gbc.setText("Game Boy Color");
        platforms.addView(gb);
        platforms.addView(gbc);
        platforms.check(gb.getId());
        platforms.setOnCheckedChangeListener((group, checkedId) -> {
            platform = checkedId == gbc.getId() ? Homebrew.PLATFORM_GBC : Homebrew.PLATFORM_GB;
            reload();
        });
        root.addView(platforms);

        status = new TextView(this);
        status.setPadding(0, dp(4), 0, dp(4));
        root.addView(status);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<Homebrew.Entry>(this, 0, entries) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Row row = convertView instanceof Row ? (Row) convertView : new Row();
                row.bind(getItem(position));
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> showEntry(entries.get(position)));
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {}

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                // Fetch the next page a little before reaching the end.
                if (total > 0 && first + visible >= total - 4) loadNextPage();
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView credit = new TextView(this);
        credit.setText("Games from Homebrew Hub (hh.gbdev.io) by the gbdev community. Each one keeps its own license.");
        credit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        credit.setPadding(0, dp(6), 0, dp(10));
        root.addView(credit);

        setContentView(root);
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void reload() {
        generation++;
        entries.clear();
        adapter.notifyDataSetChanged();
        loadedPage = 0;
        pageTotal = 1;
        loading = false;
        loadNextPage();
    }

    private void loadNextPage() {
        if (loading || loadedPage >= pageTotal) return;
        loading = true;
        int page = loadedPage + 1;
        int request = generation;
        String requestPlatform = platform;
        String requestQuery = query;
        if (entries.isEmpty()) status.setText("Loading…");
        io.execute(() -> {
            Homebrew.Page result = null;
            String error = null;
            try {
                result = Homebrew.search(requestPlatform, requestQuery, page);
            } catch (IOException e) {
                error = e.getMessage();
            }
            Homebrew.Page loaded = result;
            String message = error;
            main.post(() -> {
                if (request != generation || isDestroyed()) return;
                loading = false;
                if (loaded == null) {
                    status.setText("Couldn't reach Homebrew Hub" + (message != null ? ": " + message : "") + ". Search or switch tabs to try again.");
                    pageTotal = loadedPage; // Stop auto-loading until the next search.
                    return;
                }
                loadedPage = Math.max(page, loaded.page);
                pageTotal = loaded.pageTotal;
                entries.addAll(loaded.entries);
                adapter.notifyDataSetChanged();
                status.setText(loaded.results == 0 ? "Nothing found"
                        : loaded.results + (loaded.results == 1 ? " entry" : " entries"));
            });
        });
    }

    private void showEntry(Homebrew.Entry entry) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, dp(8), pad, 0);
        String screenshot = entry.screenshotUrl();
        if (screenshot != null) {
            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Badges.load(image, screenshot);
            content.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));
        }
        content.addView(text(entry.subtitle(), 14, false));
        if (!entry.tags.isEmpty()) content.addView(text(TextUtils.join(", ", entry.tags), 13, false));
        Homebrew.RomFile rom = entry.rom();
        if (rom == null) content.addView(text("This entry has no ROM to download.", 13, false));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(entry.title)
                .setView(content)
                .setNeutralButton("Website", (d, which) -> open(entry.pageUrl()))
                .setNegativeButton(android.R.string.cancel, null);
        if (rom != null) builder.setPositiveButton("Download", (d, which) -> download(entry, rom));
        builder.show();
    }

    private void download(Homebrew.Entry entry, Homebrew.RomFile rom) {
        Toast.makeText(this, "Downloading " + entry.title + "…", Toast.LENGTH_SHORT).show();
        RomLibrary library = new RomLibrary(this);
        io.execute(() -> {
            File added = null;
            String error = null;
            try {
                added = library.addRom(rom.filename, Homebrew.download(entry, rom));
            } catch (IOException e) {
                error = e.getMessage();
            }
            File file = added;
            String message = error;
            main.post(() -> {
                if (isDestroyed()) return;
                if (file == null) {
                    Toast.makeText(this, "Download failed: " + message, Toast.LENGTH_LONG).show();
                    return;
                }
                EmulatorActivity.forgetLoadedRom(file);
                new AlertDialog.Builder(this)
                        .setTitle(entry.title)
                        .setMessage("Added to your games.")
                        .setPositiveButton(R.string.play, (d, which) -> {
                            Intent intent = new Intent(this, EmulatorActivity.class);
                            intent.putExtra(EmulatorActivity.EXTRA_ROM, file.getName());
                            startActivity(intent);
                        })
                        .setNegativeButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    private void hideKeyboard() {
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    /** A list row: the first screenshot, the title and "developer · type · license". */
    private final class Row extends LinearLayout {
        final ImageView thumbnail;
        final TextView title;
        final TextView detail;

        Row() {
            super(HomebrewActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, dp(6), 0, dp(6));
            thumbnail = new ImageView(getContext());
            thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);
            // Game Boy screens are 10:9.
            LayoutParams thumbParams = new LayoutParams(dp(80), dp(72));
            thumbParams.setMarginEnd(dp(12));
            addView(thumbnail, thumbParams);
            LinearLayout text = new LinearLayout(getContext());
            text.setOrientation(VERTICAL);
            title = text(" ", 16, true);
            detail = text(" ", 13, false);
            text.addView(title);
            text.addView(detail);
            addView(text, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        }

        void bind(Homebrew.Entry entry) {
            title.setText(entry.title);
            detail.setText(entry.subtitle());
            Badges.load(thumbnail, entry.screenshotUrl());
        }
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setPadding(0, dp(2), 0, dp(2));
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
