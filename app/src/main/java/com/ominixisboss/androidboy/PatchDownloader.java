package com.ominixisboss.androidboy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads for the patch search: follows redirects from one site to another (which Java's own
 * redirects won't do between http and https), sends the page's cookies and address along like a
 * browser, gets past file hosts' "download anyway" pages, and says plainly what came back when it
 * isn't something AndroidBoy can open.
 */
final class PatchDownloader {
    static final int MAX_DOWNLOAD = Patcher.MAX_OUTPUT;
    private static final int MAX_REDIRECTS = 10;
    private static final int MAX_CONFIRM_PAGES = 2;

    /** Cookies, shared with the browser so a download is made as the logged-in page would make it. */
    interface Cookies {
        String get(String url);

        void set(String url, String cookie);
    }

    /** Called as a download comes in; {@code total} is -1 when the site doesn't say. */
    interface Progress {
        void update(long received, long total);
    }

    /** A finished download: what the site called it and its contents. */
    static final class Result {
        final String name;
        final byte[] data;

        Result(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    private PatchDownloader() {}

    /** Downloads {@code url} as the page at {@code referer} would. Call off the main thread. */
    static Result fetch(String url, String name, String userAgent, String referer, Cookies cookies, Progress progress)
            throws IOException {
        for (int page = 0; ; page++) {
            Result result = fetchOne(url, name, userAgent, referer, cookies, progress);
            // A file host's "this file is too big to scan, download anyway?" page.
            String confirm = page < MAX_CONFIRM_PAGES ? confirmUrl(result.data, url) : null;
            if (confirm == null) return result;
            referer = url;
            url = confirm;
        }
    }

    private static Result fetchOne(String url, String name, String userAgent, String referer, Cookies cookies,
                                   Progress progress) throws IOException {
        for (int redirects = 0; ; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(60_000);
                if (userAgent != null) connection.setRequestProperty("User-Agent", userAgent);
                if (referer != null) connection.setRequestProperty("Referer", referer);
                String cookie = cookies != null ? cookies.get(url) : null;
                if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
                int status = connection.getResponseCode();
                if (cookies != null) {
                    // Header names aren't case-sensitive, and servers spell this one differently.
                    for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                        if (!"Set-Cookie".equalsIgnoreCase(header.getKey())) continue;
                        for (String value : header.getValue()) cookies.set(url, value);
                    }
                }
                if (status >= 300 && status < 400 && status != 304) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("The site sent the download nowhere (" + status + ")");
                    if (redirects >= MAX_REDIRECTS) throw new IOException("The download kept being redirected");
                    referer = url;
                    url = new URL(new URL(url), location).toString();
                    continue;
                }
                if (status == 403 || status == 401) {
                    throw new IOException("The site refused the download (" + status + "). Some sites only allow "
                            + "downloads from their own pages: open the hack's page and tap its download link there.");
                }
                if (status == 404 || status == 410) throw new IOException("That file isn't there any more (" + status + ")");
                if (status != HttpURLConnection.HTTP_OK) throw new IOException("The site answered " + status);
                long length = connection.getContentLengthLong();
                if (length > MAX_DOWNLOAD) throw new IOException("That download is too big to be a patch");
                String fileName = fileName(connection.getHeaderField("Content-Disposition"));
                if (fileName == null) fileName = name != null ? name : nameFromUrl(url);
                try (InputStream in = connection.getInputStream()) {
                    return new Result(fileName, readAll(in, length, progress));
                }
            } finally {
                connection.disconnect();
            }
        }
    }

    private static byte[] readAll(InputStream in, long total, Progress progress) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_DOWNLOAD) throw new IOException("That download is too big to be a patch");
            if (progress != null) progress.update(out.size(), total);
        }
        return out.toByteArray();
    }

    // ---- What came back ----

    /**
     * Why a download that isn't a patch can't be used, in words for the player: an archive format
     * AndroidBoy can't open, a web page, a game, or something else.
     */
    static String whyNotAPatch(String name, byte[] data) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (startsWith(data, new byte[] {'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C}) || lower.endsWith(".7z")
                || startsWith(data, "Rar!".getBytes(StandardCharsets.US_ASCII)) || lower.endsWith(".rar")) {
            return "That download is a " + (lower.endsWith(".rar") || startsWith(data, "Rar!".getBytes(StandardCharsets.US_ASCII))
                    ? ".rar" : ".7z") + " archive, which AndroidBoy can't open. Save it with your browser instead, "
                    + "extract it with your phone's file manager, then use \"Apply a ROM hack patch…\" and pick the "
                    + ".ips, .ups or .bps file from inside it.";
        }
        if (isHtml(data)) {
            return "That link opened a web page rather than a file. On file-hosting sites, tap the page's own "
                    + "download button; if the hack is hosted somewhere that needs an account, download it with your "
                    + "browser and use \"Apply a ROM hack patch…\".";
        }
        if (RomLibrary.isGbaRom(data) || hasGameBoyLogo(data)) {
            return "That download is a game, not a patch. Only patches can be downloaded here; they're applied to "
                    + "your own copy of the game.";
        }
        return "That download isn't a ROM hack patch (.ips, .ups or .bps, or a .zip with one in it). Look for the "
                + "hack's patch download.";
    }

    static boolean isHtml(byte[] data) {
        int start = 0;
        while (start < data.length && start < 512 && (data[start] <= ' ' || (data[start] & 0xFF) == 0xEF
                || (data[start] & 0xFF) == 0xBB || (data[start] & 0xFF) == 0xBF)) {
            start++;
        }
        String head = new String(data, start, Math.min(data.length - start, 256), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<head")
                || head.startsWith("<!--") && head.contains("<html");
    }

    /** The Nintendo logo every Game Boy cartridge carries at 0x104. */
    private static boolean hasGameBoyLogo(byte[] data) {
        return data.length >= 0x150 && (data[0x104] & 0xFF) == 0xCE && (data[0x105] & 0xFF) == 0xED
                && (data[0x106] & 0xFF) == 0x66 && (data[0x107] & 0xFF) == 0x66;
    }

    // ---- File hosts ----

    private static final Pattern FORM = Pattern.compile(
            "<form[^>]*id=[\"']download-form[\"'][^>]*action=[\"']([^\"']+)[\"'][^>]*>(.*?)</form>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FORM_ACTION_FIRST = Pattern.compile(
            "<form[^>]*action=[\"']([^\"']+)[\"'][^>]*id=[\"']download-form[\"'][^>]*>(.*?)</form>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern INPUT = Pattern.compile("<input[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile("(name|value|type)=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONFIRM_LINK = Pattern.compile(
            "href=[\"']([^\"']*[?&](?:amp;)?confirm=[^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * Where to go to get past a file host's "can't scan this file, download anyway?" page (Google
     * Drive's), or null if {@code page} isn't one.
     */
    static String confirmUrl(byte[] page, String pageUrl) {
        if (!isHtml(page)) return null;
        String html = new String(page, StandardCharsets.UTF_8);
        Matcher form = FORM.matcher(html);
        boolean found = form.find();
        if (!found) {
            form = FORM_ACTION_FIRST.matcher(html);
            found = form.find();
        }
        try {
            if (found) {
                StringBuilder url = new StringBuilder(unescape(form.group(1)));
                char separator = url.indexOf("?") >= 0 ? '&' : '?';
                Matcher input = INPUT.matcher(form.group(2));
                while (input.find()) {
                    String name = null;
                    String value = "";
                    boolean hidden = false;
                    Matcher attribute = ATTRIBUTE.matcher(input.group());
                    while (attribute.find()) {
                        String key = attribute.group(1).toLowerCase(Locale.ROOT);
                        if (key.equals("name")) name = attribute.group(2);
                        if (key.equals("value")) value = unescape(attribute.group(2));
                        if (key.equals("type")) hidden = attribute.group(2).equalsIgnoreCase("hidden");
                    }
                    if (name == null || !hidden) continue;
                    url.append(separator).append(URLEncoder.encode(name, "UTF-8")).append('=')
                            .append(URLEncoder.encode(value, "UTF-8"));
                    separator = '&';
                }
                return new URL(new URL(pageUrl), url.toString()).toString();
            }
            Matcher link = CONFIRM_LINK.matcher(html);
            if (link.find()) return new URL(new URL(pageUrl), unescape(link.group(1))).toString();
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private static String unescape(String html) {
        return html.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    // ---- Names ----

    private static final Pattern FILENAME_STAR = Pattern.compile("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME = Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    /** The file name in a Content-Disposition header, or null. */
    static String fileName(String contentDisposition) {
        if (contentDisposition == null) return null;
        try {
            Matcher star = FILENAME_STAR.matcher(contentDisposition);
            if (star.find()) return clean(URLDecoder.decode(star.group(1).trim(), "UTF-8"));
        } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
            // Fall back to the plain name.
        }
        Matcher plain = FILENAME.matcher(contentDisposition);
        return plain.find() ? clean(plain.group(1).trim()) : null;
    }

    static String nameFromUrl(String url) {
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        try {
            last = URLDecoder.decode(last, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
            // Keep it as it is.
        }
        return last.isEmpty() ? "download" : clean(last);
    }

    private static String clean(String name) {
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        return base.isEmpty() ? "download" : base;
    }

    private static boolean startsWith(byte[] data, byte[] magic) {
        if (data.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) return false;
        }
        return true;
    }
}
