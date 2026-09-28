package com.ominixisboss.androidboy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finding ROM hack patches online: a web search limited to where hacks are published, in a
 * browser inside the app. Downloading a patch (.ips, .ups, .bps, or a .zip holding one) hands it
 * straight back to the game library, which asks which game to apply it to. Anything that isn't a
 * patch is refused: this only fetches patches, never games.
 */
public final class PatchSearchActivity extends Activity {
    /** What to search for, such as a game's name. */
    static final String EXTRA_QUERY = "query";
    /** In the result: the downloaded patch's file (in the cache) and its name. */
    static final String EXTRA_FILE = "file";
    static final String EXTRA_NAME = "name";

    /** Where to look, as words added to the search. */
    static final String[][] SOURCES = {
            {"Romhacking.net", "site:romhacking.net/hacks"},
            {"PokéCommunity", "site:pokecommunity.com rom hack"},
            {"Anywhere", "rom hack patch (ips OR ups OR bps)"},
    };
    private static final int MAX_DOWNLOAD = Patcher.MAX_OUTPUT;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EditText search;
    private WebView web;
    private ProgressBar progress;
    private TextView status;
    private int source;
    private boolean downloading;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Find ROM hack patches");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);

        search = new EditText(this);
        search.setHint("Game or hack name, e.g. Pokémon Red");
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        String query = getIntent().getStringExtra(EXTRA_QUERY);
        if (query != null) search.setText(query);
        search.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                runSearch();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        searchParams.setMargins(pad, dp(6), pad, 0);
        root.addView(search, searchParams);

        RadioGroup sources = new RadioGroup(this);
        sources.setOrientation(RadioGroup.HORIZONTAL);
        sources.setPadding(pad, 0, pad, 0);
        int[] ids = new int[SOURCES.length];
        for (int i = 0; i < SOURCES.length; i++) {
            RadioButton button = new RadioButton(this);
            ids[i] = View.generateViewId();
            button.setId(ids[i]);
            button.setText(SOURCES[i][0]);
            sources.addView(button);
        }
        sources.check(ids[0]);
        sources.setOnCheckedChangeListener((group, checkedId) -> {
            for (int i = 0; i < ids.length; i++) if (ids[i] == checkedId) source = i;
            runSearch();
        });
        root.addView(sources);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setPadding(pad, dp(2), pad, dp(4));
        status.setText("Open a hack's page and download its patch; it's applied to your own copy of the game.");
        root.addView(status);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); // Search results and hack pages need it.
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Web pages stay here; anything else (mail, other apps) isn't followed.
                return !(url.startsWith("https://") || url.startsWith("http://"));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) ->
                download(url, userAgent, URLUtil.guessFileName(url, contentDisposition, mimeType)));
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else if (query != null && !query.trim().isEmpty()) {
            runSearch();
        }
    }

    /** The web search for {@code query} on {@code source} (an index into {@link #SOURCES}). */
    static String searchUrl(String query, int source) {
        String words = (query == null ? "" : query.trim()) + " " + SOURCES[source][1];
        try {
            return "https://duckduckgo.com/html/?q=" + URLEncoder.encode(words.trim(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    private void runSearch() {
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(search.getWindowToken(), 0);
        String query = search.getText().toString();
        if (query.trim().isEmpty()) {
            search.requestFocus();
            return;
        }
        web.loadUrl(searchUrl(query, source));
    }

    /** Fetches a download the page started; keeps it if it's a patch, refuses it otherwise. */
    private void download(String url, String userAgent, String name) {
        if (downloading) return;
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            showError("That download can't be opened here. Try the hack's direct patch download link.");
            return;
        }
        downloading = true;
        status.setText("Downloading " + name + "…");
        String cookies = CookieManager.getInstance().getCookie(url);
        io.execute(() -> {
            try {
                byte[] data = fetch(url, userAgent, cookies);
                Patcher.Patch patch = Patcher.fromDownload(name, data);
                File file = new File(getCacheDir(), "patch-download");
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(patch.data);
                }
                main.post(() -> {
                    Intent result = new Intent();
                    result.putExtra(EXTRA_FILE, file.getPath());
                    result.putExtra(EXTRA_NAME, patch.name);
                    setResult(RESULT_OK, result);
                    finish();
                });
            } catch (IOException | OutOfMemoryError e) {
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                main.post(() -> {
                    downloading = false;
                    status.setText("Open a hack's page and download its patch; it's applied to your own copy of the game.");
                    showError(message);
                });
            }
        });
    }

    private static byte[] fetch(String url, String userAgent, String cookies) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            if (userAgent != null) connection.setRequestProperty("User-Agent", userAgent);
            if (cookies != null) connection.setRequestProperty("Cookie", cookies);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("The site answered " + status);
            long length = connection.getContentLengthLong();
            if (length > MAX_DOWNLOAD) throw new IOException("That download is too big to be a patch");
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_DOWNLOAD) throw new IOException("That download is too big to be a patch");
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private void showError(String message) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Not a patch")
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        web.destroy();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** For links elsewhere in the app. */
    static Intent intent(android.content.Context from, String query) {
        Intent intent = new Intent(from, PatchSearchActivity.class);
        if (query != null) intent.putExtra(EXTRA_QUERY, query);
        return intent;
    }

    /** A game's name as a search: its file name without the extension or tags like "(USA)" and "[!]". */
    static String queryFor(String fileName) {
        String name = fileName;
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.replaceAll("\\s*[(\\[][^)\\]]*[)\\]]", "").replace('_', ' ').trim();
    }
}
