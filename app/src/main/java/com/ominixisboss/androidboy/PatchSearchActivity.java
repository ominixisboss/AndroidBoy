package com.ominixisboss.androidboy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
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
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
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

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EditText search;
    private WebView web;
    private ProgressBar progress;
    private TextView status;
    private int source;
    private boolean downloading;
    /** The name of a blob download the page has been asked for, or null. */
    private String awaitingBlob;
    private static final String HINT =
            "Open a hack's page and download its patch; it's applied to your own copy of the game.";

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
        status.setText(HINT);
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
        web.addJavascriptInterface(new BlobReceiver(), "AndroidBoyDownloads");
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

    /** Fetches a download the page started; keeps it if it holds a patch, refuses it otherwise. */
    private void download(String url, String userAgent, String name) {
        if (downloading) return;
        if (url.startsWith("blob:")) {
            downloadBlob(url, name);
            return;
        }
        if (url.startsWith("data:")) {
            byte[] data;
            try {
                int comma = url.indexOf(',');
                String header = comma > 0 ? url.substring(0, comma) : "";
                String body = comma > 0 ? url.substring(comma + 1) : "";
                data = header.endsWith(";base64") ? Base64.decode(body, Base64.DEFAULT)
                        : java.net.URLDecoder.decode(body, "UTF-8").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            } catch (IllegalArgumentException | java.io.UnsupportedEncodingException e) {
                showError("Couldn't download", "The page's download link is broken.", null);
                return;
            }
            received(name, data, null);
            return;
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            showError("Couldn't download", "That kind of download link can't be opened here.", null);
            return;
        }
        downloading = true;
        status.setText("Downloading " + name + "…");
        String referer = web.getUrl();
        CookieManager cookieManager = CookieManager.getInstance();
        PatchDownloader.Cookies cookies = new PatchDownloader.Cookies() {
            @Override
            public String get(String address) {
                return cookieManager.getCookie(address);
            }

            @Override
            public void set(String address, String cookie) {
                cookieManager.setCookie(address, cookie);
            }
        };
        long[] lastUpdate = {0};
        PatchDownloader.Progress progress = (received, total) -> {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastUpdate[0] < 150) return;
            lastUpdate[0] = now;
            String amount = total > 0 ? (received * 100 / total) + "%" : (received / 1024) + " KB";
            main.post(() -> status.setText("Downloading " + name + "… " + amount));
        };
        io.execute(() -> {
            try {
                PatchDownloader.Result result = PatchDownloader.fetch(url, name, userAgent, referer, cookies, progress);
                main.post(() -> received(result.name, result.data, url));
            } catch (IOException | OutOfMemoryError e) {
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                main.post(() -> {
                    downloading = false;
                    status.setText(HINT);
                    showError("Couldn't download", message, url);
                });
            }
        });
    }

    /**
     * A download the page built itself (a "blob:" address, which only the page can read): the page
     * is asked to hand it over.
     */
    private void downloadBlob(String url, String name) {
        downloading = true;
        awaitingBlob = name;
        status.setText("Downloading " + name + "…");
        String script = "(function(){var x=new XMLHttpRequest();x.open('GET'," + quote(url) + ",true);"
                + "x.responseType='blob';x.onload=function(){var r=new FileReader();r.onloadend=function(){"
                + "var s=String(r.result);AndroidBoyDownloads.receive(s.substring(s.indexOf(',')+1));};"
                + "r.readAsDataURL(x.response);};x.onerror=function(){AndroidBoyDownloads.failed();};x.send();})();";
        web.evaluateJavascript(script, null);
    }

    private static String quote(String text) {
        return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** What pages can call: only the answer to a blob download this screen asked for. */
    private final class BlobReceiver {
        @JavascriptInterface
        public void receive(String base64) {
            main.post(() -> {
                String name = awaitingBlob;
                awaitingBlob = null;
                if (name == null) return; // Not asked for.
                byte[] data;
                try {
                    if (base64.length() > PatchDownloader.MAX_DOWNLOAD / 3 * 4 + 4) throw new IllegalArgumentException();
                    data = Base64.decode(base64, Base64.DEFAULT);
                } catch (IllegalArgumentException | OutOfMemoryError e) {
                    downloading = false;
                    status.setText(HINT);
                    showError("Couldn't download", "That download is too big to be a patch.", null);
                    return;
                }
                received(name, data, null);
            });
        }

        @JavascriptInterface
        public void failed() {
            main.post(() -> {
                if (awaitingBlob == null) return;
                awaitingBlob = null;
                downloading = false;
                status.setText(HINT);
                showError("Couldn't download", "The page didn't hand over its download.", null);
            });
        }
    }

    /** A finished download: back to the library if it holds a patch, or why not. */
    private void received(String name, byte[] data, String url) {
        io.execute(() -> {
            try {
                List<Patcher.Patch> patches = Patcher.fromDownload(name, data);
                // The whole download goes back, so a zip of several patches can offer each one.
                File file = new File(getCacheDir(), "patch-download");
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(data);
                }
                main.post(() -> {
                    Intent result = new Intent();
                    result.putExtra(EXTRA_FILE, file.getPath());
                    result.putExtra(EXTRA_NAME, name);
                    setResult(RESULT_OK, result);
                    Toast.makeText(this, patches.size() == 1 ? "Got the patch " + patches.get(0).name
                            : "Got " + patches.size() + " patches", Toast.LENGTH_SHORT).show();
                    finish();
                });
            } catch (IOException e) {
                main.post(() -> {
                    downloading = false;
                    status.setText(HINT);
                    showError("Not a patch", e.getMessage(), url);
                });
            }
        });
    }

    /** Explains a failed download; offers the phone's browser, which can save anything, when there's a link. */
    private void showError(String title, String message, String url) {
        if (isFinishing()) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null);
        if (url != null) {
            builder.setNeutralButton("Open in browser", (d, which) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                } catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show();
                }
            });
        }
        builder.show();
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
