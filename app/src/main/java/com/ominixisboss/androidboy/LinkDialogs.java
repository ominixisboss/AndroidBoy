package com.ominixisboss.androidboy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Plugging in the link cable: to another game on this phone, or to another phone on the same
 * Wi-Fi (one hosts, the other joins; they find each other on the network, or by address).
 */
final class LinkDialogs {
    private static final String TAG = "AndroidBoy";
    private static final int CONNECT_TIMEOUT_MS = 8000;

    interface Callbacks {
        /** Games that can be linked to this one on this phone. */
        List<File> otherGames();

        /** Links a second game on this phone. */
        void linkLocal(File partner);

        /** Hosting: another phone connected. Main thread; the connection is ready for {@link NetLink#hostReceive}. */
        void hostConnected(NetLink.Connection connection);

        /** Joining: connected to a hosting phone. Main thread. */
        void joinConnected(NetLink.Connection connection);
    }

    private LinkDialogs() {}

    static void show(Activity activity, Callbacks callbacks, Runnable onDismiss) {
        String[] items = {
                "Link with another game on this phone",
                "Host a game on Wi-Fi",
                "Join a game on Wi-Fi",
        };
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Link cable")
                .setItems(items, (d, which) -> {
                    if (which == 0) chooseLocal(activity, callbacks);
                    else if (which == 1) host(activity, callbacks);
                    else join(activity, callbacks);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(d -> onDismiss.run());
        dialog.show();
    }

    private static void chooseLocal(Activity activity, Callbacks callbacks) {
        List<File> games = callbacks.otherGames();
        if (games.isEmpty()) {
            Toast.makeText(activity, "Add another game to link with first", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[games.size()];
        for (int i = 0; i < names.length; i++) names[i] = RomLibrary.baseName(games.get(i));
        new AlertDialog.Builder(activity)
                .setTitle("Link with…")
                .setItems(names, (d, which) -> new AlertDialog.Builder(activity)
                        .setTitle("Link with " + names[which] + "?")
                        .setMessage("Both games start again from their last in-game save, as when you plug a real "
                                + "cable in and turn two Game Boys on. Switch between them from the menu.")
                        .setPositiveButton("Link", (d2, w2) -> callbacks.linkLocal(games.get(which)))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- Hosting ----

    private static void host(Activity activity, Callbacks callbacks) {
        ServerSocket server;
        try {
            server = new ServerSocket(0);
        } catch (IOException e) {
            Toast.makeText(activity, "Could not start hosting: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        int port = server.getLocalPort();
        String address = localAddress(activity);
        NsdManager nsd = activity.getSystemService(NsdManager.class);
        NsdManager.RegistrationListener registration = new NsdManager.RegistrationListener() {
            @Override public void onRegistrationFailed(NsdServiceInfo info, int error) {
                Log.w(TAG, "Could not advertise the link: " + error);
            }
            @Override public void onUnregistrationFailed(NsdServiceInfo info, int error) { }
            @Override public void onServiceRegistered(NsdServiceInfo info) { }
            @Override public void onServiceUnregistered(NsdServiceInfo info) { }
        };
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("AndroidBoy " + Build.MODEL);
        info.setServiceType(NetLink.SERVICE_TYPE);
        info.setPort(port);
        boolean advertised = false;
        if (nsd != null) {
            try {
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
                advertised = true;
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Could not advertise the link", e);
            }
        }
        boolean unregister = advertised;
        Runnable stopAdvertising = () -> {
            if (unregister) {
                try {
                    nsd.unregisterService(registration);
                } catch (IllegalArgumentException e) {
                    // Already gone.
                }
            }
        };

        AlertDialog waiting = new AlertDialog.Builder(activity)
                .setTitle("Waiting for the other phone")
                .setMessage("On the other phone, open this menu and choose Link cable → Join a game on Wi-Fi. "
                        + "Both phones need to be on the same Wi-Fi."
                        + (address != null ? "\n\nThis phone's address: " + address + ":" + port : ""))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        waiting.setOnDismissListener(d -> {
            stopAdvertising.run();
            closeQuietly(server);
        });
        waiting.show();
        new Thread(() -> {
            try {
                Socket socket = server.accept();
                NetLink.Connection connection = new NetLink.Connection(socket);
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) {
                        connection.close();
                        return;
                    }
                    waiting.dismiss(); // Stops advertising; the connection stays open.
                    callbacks.hostConnected(connection);
                });
            } catch (IOException e) {
                // Cancelled (the server was closed) or failed; nothing to do.
            }
        }, "Link host").start();
    }

    /** This phone's IPv4 address on its current network, for joining by hand. */
    static String localAddress(Context context) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) return null;
        try {
            Network network = connectivity.getActiveNetwork();
            LinkProperties properties = network == null ? null : connectivity.getLinkProperties(network);
            if (properties == null) return null;
            for (LinkAddress address : properties.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address && !address.getAddress().isLoopbackAddress()) {
                    return address.getAddress().getHostAddress();
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Could not read the network address", e);
        }
        return null;
    }

    // ---- Joining ----

    private static void join(Activity activity, Callbacks callbacks) {
        NsdManager nsd = activity.getSystemService(NsdManager.class);
        List<NsdServiceInfo> found = new ArrayList<>();
        List<String> names = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, names);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 20);
        content.setPadding(pad, dp(activity, 8), pad, 0);
        TextView status = new TextView(activity);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setText("Looking for phones hosting a game on this Wi-Fi…");
        content.addView(status);
        ListView list = new ListView(activity);
        list.setAdapter(adapter);
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String type, int error) {
                activity.runOnUiThread(() -> status.setText("Couldn't look for other phones. Enter an address instead."));
            }
            @Override public void onStopDiscoveryFailed(String type, int error) { }
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onServiceFound(NsdServiceInfo service) {
                activity.runOnUiThread(() -> {
                    for (NsdServiceInfo known : found) {
                        if (known.getServiceName().equals(service.getServiceName())) return;
                    }
                    found.add(service);
                    names.add(service.getServiceName());
                    adapter.notifyDataSetChanged();
                    status.setText("Tap the phone to link with:");
                });
            }
            @Override public void onServiceLost(NsdServiceInfo service) {
                activity.runOnUiThread(() -> {
                    for (int i = 0; i < found.size(); i++) {
                        if (found.get(i).getServiceName().equals(service.getServiceName())) {
                            found.remove(i);
                            names.remove(i);
                            adapter.notifyDataSetChanged();
                            return;
                        }
                    }
                });
            }
        };
        boolean discovering = false;
        if (nsd != null) {
            try {
                nsd.discoverServices(NetLink.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
                discovering = true;
            } catch (IllegalArgumentException e) {
                status.setText("Couldn't look for other phones. Enter an address instead.");
            }
        }
        boolean stop = discovering;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Join a game")
                .setView(content)
                .setNeutralButton("Enter address", (d, which) -> askAddress(activity, callbacks))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (stop) {
                try {
                    nsd.stopServiceDiscovery(discovery);
                } catch (IllegalArgumentException e) {
                    // Already stopped.
                }
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            NsdServiceInfo service = found.get(position);
            status.setText("Connecting to " + service.getServiceName() + "…");
            resolve(nsd, service, (host, port) -> {
                activity.runOnUiThread(dialog::dismiss);
                connect(activity, callbacks, host, port);
            }, () -> activity.runOnUiThread(() -> status.setText("Couldn't reach that phone. Try again, or enter its address.")));
        });
        dialog.show();
    }

    interface Resolved {
        void onResolved(String host, int port);
    }

    @SuppressWarnings("deprecation") // resolveService is replaced on Android 14, but it's what older versions have.
    private static void resolve(NsdManager nsd, NsdServiceInfo service, Resolved resolved, Runnable failed) {
        nsd.resolveService(service, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo info, int error) {
                failed.run();
            }

            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                if (info.getHost() == null) {
                    failed.run();
                } else {
                    resolved.onResolved(info.getHost().getHostAddress(), info.getPort());
                }
            }
        });
    }

    private static void askAddress(Activity activity, Callbacks callbacks) {
        EditText field = new EditText(activity);
        field.setHint("e.g. 192.168.1.20:40123");
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout box = new LinearLayout(activity);
        int pad = dp(activity, 20);
        box.setPadding(pad, dp(activity, 8), pad, 0);
        box.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(activity)
                .setTitle("Other phone's address")
                .setMessage("The hosting phone shows its address while it waits.")
                .setView(box)
                .setPositiveButton("Connect", (d, which) -> {
                    String[] parts = parseAddress(field.getText().toString());
                    if (parts == null) {
                        Toast.makeText(activity, "Enter the address as shown, like 192.168.1.20:40123",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    connect(activity, callbacks, parts[0], Integer.parseInt(parts[1]));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** "host:port" → {host, port}, or null. */
    static String[] parseAddress(String text) {
        text = text.trim();
        int colon = text.lastIndexOf(':');
        if (colon <= 0 || colon == text.length() - 1) return null;
        String host = text.substring(0, colon).trim();
        String port = text.substring(colon + 1).trim();
        try {
            int number = Integer.parseInt(port);
            if (number <= 0 || number > 65535) return null;
        } catch (NumberFormatException e) {
            return null;
        }
        return host.isEmpty() ? null : new String[] {host, port};
    }

    private static void connect(Activity activity, Callbacks callbacks, String host, int port) {
        activity.runOnUiThread(() -> Toast.makeText(activity, "Connecting…", Toast.LENGTH_SHORT).show());
        new Thread(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                NetLink.Connection connection = new NetLink.Connection(socket);
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) {
                        connection.close();
                    } else {
                        callbacks.joinConnected(connection);
                    }
                });
            } catch (IOException e) {
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        "Couldn't connect to the other phone: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "Link join").start();
    }

    private static void closeQuietly(ServerSocket server) {
        try {
            server.close();
        } catch (IOException e) {
            // Closing anyway.
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
