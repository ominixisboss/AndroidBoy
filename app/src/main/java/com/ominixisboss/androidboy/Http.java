package com.ominixisboss.androidboy;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Plain HTTPS GETs for the app's downloads (Homebrew Hub, the cheat database). */
final class Http {
    private Http() {}

    /**
     * Fetches {@code url}, failing if the body is over {@code limit} bytes; a missing page is a
     * {@link FileNotFoundException}. Call off the main thread.
     */
    static byte[] get(String url, int limit, String service) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("User-Agent", "AndroidBoy");
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) throw new FileNotFoundException(service + " has no " + url);
            if (status != HttpURLConnection.HTTP_OK) throw new IOException(service + " answered " + status);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > limit) throw new IOException("The download is too large");
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }
}
