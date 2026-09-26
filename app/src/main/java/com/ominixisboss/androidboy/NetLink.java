package com.ominixisboss.androidboy;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A link cable between two phones on the same network. Each phone runs both Game Boys and only
 * the players' keys cross the network, so the link's timing is exact however slow the network:
 *
 * <ol>
 *   <li>The joining phone sends its game (ROM, save, model).</li>
 *   <li>The hosting phone powers both games on, linked, and sends back its own game and the
 *       exact state of both Game Boys.</li>
 *   <li>Every frame, each phone sends the keys its player will press {@link #DELAY} frames from
 *       now, and runs a frame once it has both players' keys for it.</li>
 * </ol>
 */
final class NetLink extends LinkSession {
    static final String SERVICE_TYPE = "_androidboy._tcp";
    static final int MAGIC = 0x41424C4B; // "ABLK"
    static final int VERSION = 1;
    /** Frames between pressing a key and it taking effect: covers a Wi-Fi round trip. */
    static final int DELAY = 4;
    private static final int MAX_ROM = 16 * 1024 * 1024;
    private static final int MAX_BLOB = 4 * 1024 * 1024;
    private static final byte MSG_KEYS = 1;
    private static final byte MSG_BYE = 2;
    private static final String TAG = "AndroidBoy";

    /** A game as sent over the link. */
    static final class Game {
        final String name;
        final int model;
        final byte[] rom;
        /** The save file (joining phone) or the save state (hosting phone). */
        final byte[] data;

        Game(String name, int model, byte[] rom, byte[] data) {
            this.name = name;
            this.model = model;
            this.rom = rom;
            this.data = data;
        }
    }

    /** A socket with its streams, which must stay the same from the handshake on (they buffer). */
    static final class Connection {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            socket.setTcpNoDelay(true);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        /** For tests: any pair of streams. */
        Connection(InputStream input, OutputStream output) {
            socket = null;
            in = new DataInputStream(input);
            out = new DataOutputStream(output);
        }

        void close() {
            try {
                if (socket != null) {
                    socket.close();
                } else {
                    in.close();
                    out.close();
                }
            } catch (IOException e) {
                Log.w(TAG, "Closing the link", e);
            }
        }
    }

    /** Where each frame's keys go; the two Game Boys, except in tests. */
    interface KeySink {
        void setKeys(int local, int remote);
    }

    private KeySink sink = (local, remote) -> {
        Emulator.nativeSetKeys(local);
        Emulator.nativeSetPartnerKeys(remote);
    };
    private final Connection connection;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final LinkedBlockingQueue<Integer> remoteKeys = new LinkedBlockingQueue<>();
    private final int[] localKeys = new int[DELAY];
    private final String partnerName;
    private int frame;
    private volatile String endReason;
    private volatile boolean closed;
    private Thread reader;

    private NetLink(Connection connection, String partnerName) {
        this.connection = connection;
        this.in = connection.in;
        this.out = connection.out;
        this.partnerName = partnerName;
    }

    String partnerName() {
        return partnerName;
    }

    // ---- Setting up (off the main thread, before the link starts) ----

    /** What the joining phone does once connected: send its game, get back the host's and both states. */
    static final class JoinResult {
        final NetLink link;
        final Game host;
        final byte[] guestState;

        JoinResult(NetLink link, Game host, byte[] guestState) {
            this.link = link;
            this.host = host;
            this.guestState = guestState;
        }
    }

    static JoinResult join(Connection connection, Game mine) throws IOException {
        writeHello(connection.out);
        writeGame(connection.out, mine);
        connection.out.flush();
        readHello(connection.in);
        Game host = readGame(connection.in);
        byte[] guestState = readBlob(connection.in, MAX_BLOB);
        return new JoinResult(new NetLink(connection, host.name), host, guestState);
    }

    /** The hosting phone's first half: read the joining phone's game. */
    static Game hostReceive(Connection connection) throws IOException {
        readHello(connection.in);
        return readGame(connection.in);
    }

    /**
     * The hosting phone's second half, once both games are powered on and linked: send its game
     * with its state, and the state of the joining phone's game. The link starts on both sides now.
     */
    static NetLink hostSend(Connection connection, Game guest, Game mine, byte[] guestState) throws IOException {
        writeHello(connection.out);
        writeGame(connection.out, mine);
        writeBlob(connection.out, guestState);
        connection.out.flush();
        return new NetLink(connection, guest.name);
    }

    static void writeHello(DataOutputStream out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
    }

    static void readHello(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("That isn't AndroidBoy on the other end");
        int version = in.readInt();
        if (version != VERSION) {
            throw new IOException("The other phone has a different version of AndroidBoy; update both");
        }
    }

    static void writeGame(DataOutputStream out, Game game) throws IOException {
        out.writeUTF(game.name);
        out.writeInt(game.model);
        writeBlob(out, game.rom);
        writeBlob(out, game.data);
    }

    static Game readGame(DataInputStream in) throws IOException {
        String name = in.readUTF();
        int model = in.readInt();
        byte[] rom = readBlob(in, MAX_ROM);
        byte[] data = readBlob(in, MAX_BLOB);
        return new Game(name, model, rom, data);
    }

    static void writeBlob(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data == null ? 0 : data.length);
        if (data != null) out.write(data);
    }

    static byte[] readBlob(DataInputStream in, int limit) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > limit) throw new IOException("The other phone sent something too large");
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    // ---- Running ----

    void setKeySink(KeySink keySink) {
        sink = keySink;
    }

    /** Starts exchanging keys. The first DELAY frames have no keys pressed on either side. */
    void start() {
        for (int i = 0; i < DELAY; i++) remoteKeys.add(0);
        reader = new Thread(this::readLoop, "Link");
        reader.start();
    }

    private void readLoop() {
        try {
            while (!closed) {
                byte type = in.readByte();
                if (type == MSG_KEYS) {
                    remoteKeys.add(in.readUnsignedByte());
                } else if (type == MSG_BYE) {
                    end(partnerName + " unplugged the link cable");
                    return;
                } else {
                    end("The link got mixed up");
                    return;
                }
            }
        } catch (IOException e) {
            end("Lost the connection to " + partnerName);
        }
    }

    private void end(String reason) {
        if (endReason == null) endReason = reason;
        closed = true;
        remoteKeys.add(-1); // Wakes a frame waiting for keys.
    }

    @Override
    int applyKeys(int keys) {
        if (closed) return ENDED;
        // Keys pressed now take effect DELAY frames from now, on both phones.
        int slot = frame % DELAY;
        int now = localKeys[slot];
        Integer remote;
        try {
            remote = remoteKeys.poll(20, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return WAITING;
        }
        if (remote == null) return WAITING;
        if (remote < 0) return ENDED;
        // Only now that this frame will run: send the keys for DELAY frames ahead.
        localKeys[slot] = keys & 0xFF;
        try {
            out.writeByte(MSG_KEYS);
            out.writeByte(keys & 0xFF);
            out.flush();
        } catch (IOException e) {
            end("Lost the connection to " + partnerName);
            return ENDED;
        }
        sink.setKeys(now, remote);
        frame++;
        return READY;
    }

    @Override
    String endReason() {
        return endReason;
    }

    @Override
    void close() {
        if (!closed) {
            try {
                out.writeByte(MSG_BYE);
                out.flush();
            } catch (IOException e) {
                // Going anyway.
            }
        }
        closed = true;
        connection.close();
    }
}
