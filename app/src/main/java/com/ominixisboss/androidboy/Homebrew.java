package com.ominixisboss.androidboy;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Homebrew Hub (hh3.gbdev.io): the gbdev community's database of free homebrew games, demos and
 * music for the Game Boy and Game Boy Color. Its API is documented at
 * https://github.com/gbdev/homebrewhub/blob/master/API.md.
 */
final class Homebrew {
    static final String HOST = "https://hh3.gbdev.io";
    static final String SITE = "https://hh.gbdev.io";
    static final String PLATFORM_GB = "GB";
    static final String PLATFORM_GBC = "GBC";
    static final int PAGE_SIZE = 20;
    /** Downloads bigger than this aren't Game Boy games (the largest cartridges are 8 MB). */
    private static final int MAX_DOWNLOAD = 32 * 1024 * 1024;

    /** A ROM file attached to an entry. */
    static final class RomFile {
        final String filename;
        final boolean playable;
        final boolean isDefault;

        RomFile(String filename, boolean playable, boolean isDefault) {
            this.filename = filename;
            this.playable = playable;
            this.isDefault = isDefault;
        }
    }

    /** One game, demo or music disk. */
    static final class Entry {
        final String slug;
        final String title;
        final String developer;
        /** "game", "homebrew", "demo", "music", ... */
        final String type;
        final String platform;
        final String license;
        /** Where the entry's files are served from (which of Homebrew Hub's databases it's in). */
        final String basepath;
        final List<String> screenshots;
        final List<String> tags;
        final List<RomFile> files;

        Entry(String slug, String title, String developer, String type, String platform, String license,
              String basepath, List<String> screenshots, List<String> tags, List<RomFile> files) {
            this.slug = slug;
            this.title = title;
            this.developer = developer;
            this.type = type;
            this.platform = platform;
            this.license = license;
            this.basepath = basepath;
            this.screenshots = screenshots;
            this.tags = tags;
            this.files = files;
        }

        /** The ROM to download: the default playable file, else any playable one, else any .gb/.gbc/.zip. */
        RomFile rom() {
            RomFile fallback = null;
            for (RomFile file : files) {
                if (file.playable && file.isDefault) return file;
                if (file.playable && (fallback == null || !fallback.playable)) fallback = file;
                if (fallback == null && isRomName(file.filename)) fallback = file;
            }
            return fallback;
        }

        String screenshotUrl() {
            return screenshots.isEmpty() ? null : fileUrl(this, screenshots.get(0));
        }

        String pageUrl() {
            return SITE + "/entry/" + slug;
        }

        /** "Developer · game · MIT", leaving out what's unknown. */
        String subtitle() {
            StringBuilder text = new StringBuilder();
            for (String part : new String[] {developer, type, license}) {
                if (part == null || part.isEmpty()) continue;
                if (text.length() > 0) text.append(" · ");
                text.append(part);
            }
            return text.toString();
        }
    }

    /** A page of search results. */
    static final class Page {
        final List<Entry> entries;
        final int page;
        final int pageTotal;
        final int results;

        Page(List<Entry> entries, int page, int pageTotal, int results) {
            this.entries = entries;
            this.page = page;
            this.pageTotal = pageTotal;
            this.results = results;
        }
    }

    private Homebrew() {}

    /** The search URL for one page of a platform's entries, newest first, optionally matching {@code query}. */
    static String searchUrl(String platform, String query, int page) {
        Uri.Builder uri = Uri.parse(HOST + "/api/search").buildUpon()
                .appendQueryParameter("platform", platform)
                .appendQueryParameter("results", String.valueOf(PAGE_SIZE))
                .appendQueryParameter("page", String.valueOf(page));
        if (query != null && !query.trim().isEmpty()) uri.appendQueryParameter("q", query.trim());
        return uri.build().toString();
    }

    /** Where one of an entry's files (a ROM or a screenshot) can be downloaded. */
    static String fileUrl(Entry entry, String filename) {
        String base = entry.basepath == null || entry.basepath.isEmpty() ? "database-gb" : entry.basepath;
        return HOST + "/static/" + Uri.encode(base) + "/entries/" + Uri.encode(entry.slug) + "/" + Uri.encode(filename);
    }

    /** Parses a /api/search response. ROM hacks are left out: they're modified commercial games. */
    static Page parsePage(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray array = root.optJSONArray("entries");
        List<Entry> entries = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                Entry entry = parseEntry(array.getJSONObject(i));
                if (entry != null) entries.add(entry);
            }
        }
        return new Page(entries, root.optInt("page_current", 1), root.optInt("page_total", 1), root.optInt("results", entries.size()));
    }

    static Entry parseEntry(JSONObject o) {
        String slug = o.optString("slug", "");
        String type = o.optString("typetag", "");
        if (slug.isEmpty() || type.equalsIgnoreCase("hackrom")) return null;
        List<RomFile> files = new ArrayList<>();
        JSONArray fileArray = o.optJSONArray("files");
        for (int i = 0; fileArray != null && i < fileArray.length(); i++) {
            JSONObject f = fileArray.optJSONObject(i);
            if (f == null || f.optString("filename", "").isEmpty()) continue;
            files.add(new RomFile(f.optString("filename"), f.optBoolean("playable", false), f.optBoolean("default", false)));
        }
        return new Entry(slug, o.optString("title", slug), o.optString("developer", ""), type,
                o.optString("platform", ""), o.optString("license", ""), o.optString("basepath", ""),
                strings(o.optJSONArray("screenshots")), strings(o.optJSONArray("tags")), files);
    }

    private static List<String> strings(JSONArray array) {
        if (array == null) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            // Screenshots and tags are plain strings; tolerate objects with a "filename".
            Object value = array.opt(i);
            if (value instanceof String) {
                list.add((String) value);
            } else if (value instanceof JSONObject && ((JSONObject) value).has("filename")) {
                list.add(((JSONObject) value).optString("filename"));
            }
        }
        return list;
    }

    static boolean isRomName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".gb") || lower.endsWith(".gbc") || lower.endsWith(".zip");
    }

    /** Fetches one page of search results. Call off the main thread. */
    static Page search(String platform, String query, int page) throws IOException {
        try {
            return parsePage(new String(get(searchUrl(platform, query, page), 4 * 1024 * 1024), "UTF-8"));
        } catch (JSONException e) {
            throw new IOException("Homebrew Hub sent something unexpected", e);
        }
    }

    /** Downloads an entry's ROM. Call off the main thread. */
    static byte[] download(Entry entry, RomFile file) throws IOException {
        return get(fileUrl(entry, file.filename), MAX_DOWNLOAD);
    }

    private static byte[] get(String url, int limit) throws IOException {
        return Http.get(url, limit, "Homebrew Hub");
    }
}
