package com.ominixisboss.androidboy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** The two-phone link: setting up, and both phones getting the same keys for every frame. */
public class NetLinkTest {
    /** Two connected ends of a real socket on this machine. */
    private static NetLink.Connection[] pipe() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
            Socket accepted = server.accept();
            return new NetLink.Connection[] {new NetLink.Connection(accepted), new NetLink.Connection(client)};
        }
    }

    private static NetLink[] handshake(NetLink.Connection host, NetLink.Connection guest) throws Exception {
        NetLink.Game guestGame = new NetLink.Game("Blue", 0x002, new byte[] {1, 2, 3}, new byte[] {9});
        NetLink.Game hostGame = new NetLink.Game("Red", 0x205, new byte[] {4, 5}, new byte[] {7, 7});
        AtomicReference<NetLink.JoinResult> joined = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread guestThread = new Thread(() -> {
            try {
                joined.set(NetLink.join(guest, guestGame));
            } catch (Exception e) {
                failure.set(e);
            }
        });
        guestThread.start();
        NetLink.Game received = NetLink.hostReceive(host);
        assertEquals("Blue", received.name);
        assertEquals(0x002, received.model);
        assertArrayEquals(new byte[] {1, 2, 3}, received.rom);
        assertArrayEquals(new byte[] {9}, received.data);
        NetLink hostLink = NetLink.hostSend(host, received, hostGame, new byte[] {8, 8, 8});
        guestThread.join(5000);
        assertNull(failure.get());
        NetLink.JoinResult result = joined.get();
        assertNotNull(result);
        assertEquals("Red", result.host.name);
        assertEquals(0x205, result.host.model);
        assertArrayEquals(new byte[] {4, 5}, result.host.rom);
        assertArrayEquals(new byte[] {7, 7}, result.host.data);
        assertArrayEquals(new byte[] {8, 8, 8}, result.guestState);
        assertEquals("Blue", hostLink.partnerName());
        assertEquals("Red", result.link.partnerName());
        return new NetLink[] {hostLink, result.link};
    }

    /** Runs {@code frames} frames on one side, pressing a different key each frame; records (local, remote). */
    private static Thread play(NetLink link, int frames, int seed, List<int[]> seen) {
        link.setKeySink((local, remote) -> seen.add(new int[] {local, remote}));
        Thread thread = new Thread(() -> {
            int frame = 0;
            while (frame < frames) {
                int status = link.applyKeys((frame * seed) & 0xFF);
                if (status == LinkSession.READY) frame++;
                if (status == LinkSession.ENDED) return;
            }
        });
        thread.start();
        return thread;
    }

    @Test
    public void bothPhonesGetTheSameKeysEveryFrame() throws Exception {
        NetLink.Connection[] ends = pipe();
        NetLink[] links = handshake(ends[0], ends[1]);
        links[0].start();
        links[1].start();
        List<int[]> hostSaw = new ArrayList<>();
        List<int[]> guestSaw = new ArrayList<>();
        Thread host = play(links[0], 60, 3, hostSaw);
        Thread guest = play(links[1], 60, 7, guestSaw);
        host.join(10000);
        guest.join(10000);
        assertEquals(60, hostSaw.size());
        assertEquals(60, guestSaw.size());
        for (int frame = 0; frame < 60; frame++) {
            int[] h = hostSaw.get(frame);
            int[] g = guestSaw.get(frame);
            // The same two players' keys on both phones...
            assertEquals("frame " + frame, h[0], g[1]);
            assertEquals("frame " + frame, h[1], g[0]);
            // ...pressed DELAY frames earlier (nothing before that).
            int pressedAt = frame - NetLink.DELAY;
            assertEquals(pressedAt < 0 ? 0 : (pressedAt * 3) & 0xFF, h[0]);
            assertEquals(pressedAt < 0 ? 0 : (pressedAt * 7) & 0xFF, g[0]);
        }
        links[0].close();
        // The other phone hears the cable come out.
        int status = LinkSession.READY;
        for (int i = 0; i < 500 && status != LinkSession.ENDED; i++) status = links[1].applyKeys(0);
        assertEquals(LinkSession.ENDED, status);
        assertTrue(links[1].endReason().contains("Red"));
        links[1].close();
    }

    @Test
    public void waitsForTheOtherPhone() throws Exception {
        NetLink.Connection[] ends = pipe();
        NetLink[] links = handshake(ends[0], ends[1]);
        links[0].start();
        links[0].setKeySink((local, remote) -> { });
        // The first DELAY frames need nothing from the other side; then it has to catch up.
        for (int frame = 0; frame < NetLink.DELAY; frame++) assertEquals(LinkSession.READY, links[0].applyKeys(0));
        assertEquals(LinkSession.WAITING, links[0].applyKeys(0));
        links[0].close();
        links[1].close();
    }

    @Test
    public void rejectsOtherVersions() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(NetLink.MAGIC);
        out.writeInt(NetLink.VERSION + 1);
        try {
            NetLink.readHello(new java.io.DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("version"));
        }
        bytes.reset();
        out.writeInt(0x12345678);
        try {
            NetLink.readHello(new java.io.DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("AndroidBoy"));
        }
    }

    @Test
    public void refusesHugeBlobs() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(Integer.MAX_VALUE);
        try {
            NetLink.readBlob(new java.io.DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), 1024);
            fail();
        } catch (IOException expected) {
        }
    }

    @Test
    public void addresses() {
        assertArrayEquals(new String[] {"192.168.1.20", "40123"}, LinkDialogs.parseAddress(" 192.168.1.20:40123 "));
        assertNull(LinkDialogs.parseAddress("192.168.1.20"));
        assertNull(LinkDialogs.parseAddress("192.168.1.20:99999"));
        assertNull(LinkDialogs.parseAddress(":40123"));
    }
}
