package com.ominixisboss.androidboy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Patch downloads against a little local web server standing in for a hack's host. */
public class PatchDownloaderTest {
    private static final byte[] PATCH = ("PATCH" + "\0\0\u0010\0\u0001B" + "EOF").getBytes(StandardCharsets.ISO_8859_1);

    private TinyServer server;
    private CookieHandler previousCookies;
    private String base;
    private final Map<String, String> seen = new HashMap<>();

    @Before
    public void start() throws IOException {
        // Another test may have installed a JVM-wide cookie handler, which hides Set-Cookie
        // headers; the app installs none.
        previousCookies = CookieHandler.getDefault();
        CookieHandler.setDefault(null);
        server = new TinyServer(request -> {
            switch (request.path) {
                // A download link that redirects twice (once to a relative address) before the file.
                case "/download/hack":
                    return Response.redirect(base + "/mirror/hack");
                case "/mirror/hack":
                    return Response.redirect("../files/hack.bin");
                case "/files/hack.bin":
                    seen.put("cookie", request.headers.get("cookie"));
                    seen.put("referer", request.headers.get("referer"));
                    return new Response(200, PATCH)
                            .header("Content-Disposition", "attachment; filename=\"My Hack v1.1.ips\"")
                            .header("set-cookie", "downloaded=1"); // Spelt as some servers do.
                // A file host's "can't scan this file" page, then the file once confirmed.
                case "/drive":
                    if (request.query != null && request.query.contains("confirm=t") && request.query.contains("uuid=abc")) {
                        return new Response(200, PATCH);
                    }
                    return new Response(200, ("<!DOCTYPE html><html><body>Google Drive can't scan this file for viruses."
                            + "<form id=\"download-form\" action=\"" + base + "/drive\" method=\"get\">"
                            + "<input type=\"submit\" value=\"Download anyway\"/>"
                            + "<input type=\"hidden\" name=\"id\" value=\"xyz\">"
                            + "<input type=\"hidden\" name=\"confirm\" value=\"t\">"
                            + "<input type=\"hidden\" name=\"uuid\" value=\"abc\"></form></body></html>")
                            .getBytes(StandardCharsets.UTF_8));
                case "/members-only":
                    return new Response(403, new byte[0]);
                default:
                    return new Response(404, new byte[0]);
            }
        });
        base = "http://127.0.0.1:" + server.port();
    }

    @After
    public void stop() throws IOException {
        server.close();
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

    // ---- A tiny HTTP server: one request per connection ----

    private static final class Request {
        String path;
        String query;
        final Map<String, String> headers = new HashMap<>();
    }

    private static final class Response {
        final int status;
        final byte[] body;
        final StringBuilder headers = new StringBuilder();

        Response(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }

        Response header(String name, String value) {
            headers.append(name).append(": ").append(value).append("\r\n");
            return this;
        }

        static Response redirect(String location) {
            return new Response(302, new byte[0]).header("Location", location);
        }
    }

    private interface Handler {
        Response handle(Request request) throws IOException;
    }

    private static final class TinyServer implements Closeable {
        private final ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));

        TinyServer(Handler handler) throws IOException {
            Thread thread = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket client = socket.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(),
                                StandardCharsets.ISO_8859_1));
                        String line = in.readLine();
                        if (line == null) continue;
                        Request request = new Request();
                        String target = line.split(" ")[1];
                        int question = target.indexOf('?');
                        request.path = question >= 0 ? target.substring(0, question) : target;
                        request.query = question >= 0 ? target.substring(question + 1) : null;
                        while ((line = in.readLine()) != null && !line.isEmpty()) {
                            int colon = line.indexOf(':');
                            if (colon > 0) {
                                request.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                                        line.substring(colon + 1).trim());
                            }
                        }
                        Response response = handler.handle(request);
                        OutputStream out = client.getOutputStream();
                        out.write(("HTTP/1.1 " + response.status + " X\r\nContent-Length: " + response.body.length
                                + "\r\nConnection: close\r\n" + response.headers + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                        out.write(response.body);
                        out.flush();
                    } catch (IOException e) {
                        // Closed, or a client gave up.
                    }
                }
            }, "tiny-server");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
