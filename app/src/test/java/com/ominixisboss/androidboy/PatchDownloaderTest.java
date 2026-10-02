package com.ominixisboss.androidboy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Patch downloads against a little local web server standing in for a hack's host. */
public class PatchDownloaderTest {
    private static final byte[] PATCH = ("PATCH" + "\0\0\u0010\0\u0001B" + "EOF").getBytes(StandardCharsets.ISO_8859_1);

    private HttpServer server;
    private CookieHandler previousCookies;
    private String base;
    private final Map<String, String> seen = new HashMap<>();

    @Before
    public void start() throws IOException {
        // Another test may have installed a JVM-wide cookie handler, which hides Set-Cookie
        // headers; the app installs none.
        previousCookies = CookieHandler.getDefault();
        CookieHandler.setDefault(null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // A download link that redirects twice (once to a relative address) before the file.
        server.createContext("/download/hack", exchange -> redirect(exchange, base + "/mirror/hack"));
        server.createContext("/mirror/hack", exchange -> redirect(exchange, "../files/hack.bin"));
        server.createContext("/files/hack.bin", exchange -> {
            seen.put("cookie", exchange.getRequestHeaders().getFirst("Cookie"));
            seen.put("referer", exchange.getRequestHeaders().getFirst("Referer"));
            exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"My Hack v1.1.ips\"");
            exchange.getResponseHeaders().add("Set-Cookie", "downloaded=1");
            send(exchange, 200, PATCH);
        });
        // A file host's "can't scan this file" page, then the file once confirmed.
        server.createContext("/drive", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("confirm=t") && query.contains("uuid=abc")) {
                send(exchange, 200, PATCH);
            } else {
                send(exchange, 200, ("<!DOCTYPE html><html><body>Google Drive can't scan this file for viruses."
                        + "<form id=\"download-form\" action=\"" + base + "/drive\" method=\"get\">"
                        + "<input type=\"submit\" value=\"Download anyway\"/>"
                        + "<input type=\"hidden\" name=\"id\" value=\"xyz\">"
                        + "<input type=\"hidden\" name=\"confirm\" value=\"t\">"
                        + "<input type=\"hidden\" name=\"uuid\" value=\"abc\"></form></body></html>")
                        .getBytes(StandardCharsets.UTF_8));
            }
        });
        server.createContext("/members-only", exchange -> send(exchange, 403, new byte[0]));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stop() {
        server.stop(0);
        CookieHandler.setDefault(previousCookies);
    }

    @Test
    public void followsRedirectsWithTheBrowsersCookiesAndPage() throws IOException {
        Map<String, String> jar = new HashMap<>();
        PatchDownloader.Cookies cookies = new PatchDownloader.Cookies() {
            @Override public String get(String url) {
                return "session=42";
            }

            @Override public void set(String url, String cookie) {
                jar.put(url, cookie);
            }
        };
        long[] progress = {0};
        PatchDownloader.Result result = PatchDownloader.fetch(base + "/download/hack", "hack", "AndroidBoy test",
                base + "/hacks/123", cookies, (received, total) -> progress[0] = received);
        assertArrayEquals(PATCH, result.data);
        assertEquals("My Hack v1.1.ips", result.name); // The site's name for it, not the link's.
        assertEquals("session=42", seen.get("cookie"));
        assertEquals(base + "/mirror/hack", seen.get("referer")); // As a browser would, after redirects.
        assertEquals("downloaded=1", jar.get(base + "/files/hack.bin"));
        assertEquals(PATCH.length, progress[0]);
    }

    @Test
    public void getsPastFileHostConfirmPages() throws IOException {
        PatchDownloader.Result result = PatchDownloader.fetch(base + "/drive?id=xyz", "hack.ips", null, null, null, null);
        assertArrayEquals(PATCH, result.data);
    }

    @Test
    public void explainsRefusals() {
        try {
            PatchDownloader.fetch(base + "/members-only", "hack.ips", null, null, null, null);
            fail("A refused download came back");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("refused the download (403)"));
        }
    }

    @Test
    public void saysWhatCameBackInstead() {
        byte[] sevenZip = {'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C, 0, 4};
        assertTrue(PatchDownloader.whyNotAPatch("hack.7z", sevenZip).contains(".7z archive"));
        assertTrue(PatchDownloader.whyNotAPatch("hack", "Rar!\u001a\u0007\0".getBytes(StandardCharsets.ISO_8859_1))
                .contains(".rar archive"));
        assertTrue(PatchDownloader.whyNotAPatch("download", "\n <!DOCTYPE html><html>Mirror</html>".getBytes(StandardCharsets.UTF_8))
                .contains("web page"));
        byte[] game = new byte[0x8000];
        game[0x104] = (byte) 0xCE;
        game[0x105] = (byte) 0xED;
        game[0x106] = 0x66;
        game[0x107] = 0x66;
        assertTrue(PatchDownloader.whyNotAPatch("Game (USA).gb", game).contains("is a game, not a patch"));
        assertNull(PatchDownloader.confirmUrl("<html>Nothing to confirm</html>".getBytes(StandardCharsets.UTF_8),
                "https://example.com/"));
    }

    @Test
    public void namesFromHeadersAndLinks() {
        assertEquals("Crystal Clear 2.0.bps", PatchDownloader.fileName("attachment; filename*=UTF-8''Crystal%20Clear%202.0.bps"));
        assertEquals("hack.ups", PatchDownloader.fileName("attachment; filename=hack.ups"));
        assertNull(PatchDownloader.fileName("inline"));
        assertEquals("Red++ 4.0.zip", PatchDownloader.nameFromUrl("https://example.com/files/Red%2B%2B%204.0.zip?dl=1"));
    }

    @Test
    public void everyPatchInAZipIsOffered() throws IOException {
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipped)) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("Pick the one for your version".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("Hack (Red).ips"));
            zip.write(PATCH);
            zip.putNextEntry(new ZipEntry("Hack (Blue).ips"));
            zip.write(PATCH);
        }
        List<Patcher.Patch> patches = Patcher.fromDownload("Hack.zip", zipped.toByteArray());
        assertEquals(2, patches.size());
        assertEquals("Hack (Red).ips", patches.get(0).name);
        assertEquals("Hack (Blue).ips", patches.get(1).name);
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        send(exchange, 302, new byte[0]);
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }
}
