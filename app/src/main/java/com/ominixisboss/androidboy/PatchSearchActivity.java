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
    /** What to do if the page can't hand over the download it was asked for. */
    private Runnable pageFallback;
    private static final String HINT =
            "Open a hack's page and download its patch; it's applied to your own copy of the game.";
    private String hint = HINT;

    /** Set to "itch" to browse free homebrew on itch.io instead: downloads there are games, added to the library. */
    static final String EXTRA_MODE = "mode";
    /** itch.io's lists of free games made for these systems. */
    static final String[][] ITCH_SOURCES = {
            {"Game Boy", "https://itch.io/games/free/tag-gameboy-rom"},
            {"Color", "https://itch.io/games/free/tag-gbc"},
            {"Advance", "https://itch.io/games/free/tag-gameboy-advance"},
            {"GB Studio", "https://itch.io/games/free/tag-gbstudio"},
    };
    private boolean itch;

    static Intent itchIntent(android.content.Context from) {
        return new Intent(from, PatchSearchActivity.class).putExtra(EXTRA_MODE, "itch");
    }

    /** Whether a page belongs to itch.io (the site, a creator's page on it, or its file servers). */
    static boolean onItch(String url) {
        String host = android.net.Uri.parse(url).getHost();
        if (host == null) return false;
        host = host.toLowerCase(java.util.Locale.ROOT);
        return host.equals("itch.io") || host.endsWith(".itch.io") || host.equals("itch.zone") || host.endsWith(".itch.zone");
    }

    /**
     * The Game Boy or GBA game in a download from itch.io (the file, or the first game in a zip),
     * or an exception saying why there isn't one: many pages also offer Windows or web builds.
     */
    static RomLibrary.Picked gameFromDownload(String name, byte[] data) throws IOException {
        if (PatchDownloader.isHtml(data)) {
            throw new IOException("That link opened a web page, not a file. Only itch.io pages open here; "
                    + "use the page's own download button.");
        }
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".7z") || lower.endsWith(".rar")) {
            throw new IOException("That download is a " + lower.substring(lower.lastIndexOf('.'))
                    + " archive, which AndroidBoy can't open. Save it with your browser, extract it, then add the game.");
        }
        RomLibrary.Picked game = RomLibrary.romIn(name, data);
        if (!RomLibrary.isGbaRom(game.data) && !PatchDownloader.hasGameBoyLogo(game.data)) {
            throw new IOException("That download isn't a Game Boy or Game Boy Advance game. "
                    + "Look for the .gb, .gbc or .gba download on the page.");
        }
        return game;
    }

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        itch = "itch".equals(getIntent().getStringExtra(EXTRA_MODE));
        setTitle(itch ? "itch.io homebrew" : "Find ROM hack patches");
        if (itch) hint = "Free games their makers share on itch.io. Open one and download its .gb, .gbc or .gba file.";
        String[][] tabs = itch ? ITCH_SOURCES : SOURCES;
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);

        search = new EditText(this);
        search.setHint(itch ? "Search itch.io" : "Game or hack name, e.g. Pokémon Red");
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
        int[] ids = new int[tabs.length];
        for (int i = 0; i < tabs.length; i++) {
            RadioButton button = new RadioButton(this);
            ids[i] = View.generateViewId();
            button.setId(ids[i]);
            button.setText(tabs[i][0]);
            sources.addView(button);
        }
        sources.check(ids[0]);
        sources.setOnCheckedChangeListener((group, checkedId) -> {
            for (int i = 0; i < ids.length; i++) if (ids[i] == checkedId) source = i;
            if (itch) {
                search.setText("");
                web.loadUrl(ITCH_SOURCES[source][1]);
            } else {
                runSearch();
            }
        });
        root.addView(sources);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setPadding(pad, dp(2), pad, dp(4));
        status.setText(hint);
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
                if (!(url.startsWith("https://") || url.startsWith("http://"))) return true;
                if (itch && !onItch(url)) {
                    // Off itch.io: only a file is taken (downloads come from other servers), never a page.
                    download(url, view.getSettings().getUserAgentString(), PatchDownloader.nameFromUrl(url));
                    return true;
                }
                return false;
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
        } else if (itch) {
            web.loadUrl(ITCH_SOURCES[0][1]);
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
        if (itch) {
            try {
                web.loadUrl("https://itch.io/search?q=" + URLEncoder.encode(query.trim(), "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                throw new AssertionError(e);
            }
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
        // Sites behind bot protection (Romhacking.net is behind Cloudflare) let the browser through
        // but refuse a separate request from the app, even with the same cookies. So the page
        // fetches the file itself first; the app's own request is only the fallback, for files
        // on another site that the page isn't allowed to read.
        fetchInPage(url, name, () -> fetchDirectly(url, userAgent, name));
    }

    /** Downloads {@code url} with the app's own request, as the browser would have. */
    private void fetchDirectly(String url, String userAgent, String name) {
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
                    status.setText(hint);
                    showError("Couldn't download", message, url);
                });
            }
        });
    }

    /** A download the page built itself (a "blob:" address, which only the page can read). */
    private void downloadBlob(String url, String name) {
        fetchInPage(url, name, () -> {
            downloading = false;
            status.setText(hint);
            showError("Couldn't download", "The page didn't hand over its download.", null);
        });
    }

    /**
     * Asks the page to fetch {@code url} and hand it over, as the browser itself (same cookies,
     * same connection, past any bot check); runs {@code otherwise} if the page can't.
     */
    private void fetchInPage(String url, String name, Runnable otherwise) {
        downloading = true;
        awaitingBlob = name;
        pageFallback = otherwise;
        status.setText("Downloading " + name + "…");
        String script = "(function(){try{var x=new XMLHttpRequest();x.open('GET'," + quote(url) + ",true);"
                + "x.withCredentials=true;x.responseType='blob';"
                + "x.onload=function(){if(x.status!==200&&x.status!==0){AndroidBoyDownloads.failed();return;}"
                + "var d=x.getResponseHeader('Content-Disposition')||'';var r=new FileReader();"
                + "r.onloadend=function(){var s=String(r.result);"
                + "AndroidBoyDownloads.receive(s.substring(s.indexOf(',')+1),d);};"
                + "r.onerror=function(){AndroidBoyDownloads.failed();};r.readAsDataURL(x.response);};"
                + "x.onerror=function(){AndroidBoyDownloads.failed();};x.send();}"
                + "catch(e){AndroidBoyDownloads.failed();}})();";
        web.evaluateJavascript(script, null);
        // If the page never answers (it was left, or the request hangs), go on without it.
        main.postDelayed(() -> {
            if (pageFallback != otherwise) return;
            awaitingBlob = null;
            pageFallback = null;
            otherwise.run();
        }, 45_000);
    }

    private static String quote(String text) {
        return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** What pages can call: only the answer to a blob download this screen asked for. */
    private final class BlobReceiver {
        @JavascriptInterface
        public void receive(String base64, String disposition) {
            main.post(() -> {
                String asked = awaitingBlob;
                awaitingBlob = null;
                pageFallback = null;
                if (asked == null) return; // Not asked for.
                String named = PatchDownloader.fileName(disposition);
                String name = named != null ? named : asked;
                byte[] data;
                try {
                    if (base64.length() > PatchDownloader.MAX_DOWNLOAD / 3 * 4 + 4) throw new IllegalArgumentException();
                    data = Base64.decode(base64, Base64.DEFAULT);
                } catch (IllegalArgumentException | OutOfMemoryError e) {
                    downloading = false;
                    status.setText(hint);
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
                Runnable otherwise = pageFallback;
                pageFallback = null;
                if (otherwise != null) otherwise.run();
            });
        }
    }

    /** A finished download: back to the library if it holds a patch, or why not. */
    private void received(String name, byte[] data, String url) {
        if (itch) {
            io.execute(() -> {
                try {
                    RomLibrary.Picked game = gameFromDownload(name, data);
                    File file = new File(getCacheDir(), "game-download");
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        out.write(game.data);
                    }
                    main.post(() -> {
                        setResult(RESULT_OK, new Intent().putExtra(EXTRA_FILE, file.getPath()).putExtra(EXTRA_NAME, game.name));
                        finish();
                    });
                } catch (IOException | OutOfMemoryError e) {
                    main.post(() -> {
                        downloading = false;
                        status.setText(hint);
                        showError("Not a game", e.getMessage() != null ? e.getMessage() : "Out of memory", null);
                    });
                }
            });
            return;
        }
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
                    status.setText(hint);
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
