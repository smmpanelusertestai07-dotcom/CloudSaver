package com.pocketagent.doors;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Handler;
import android.os.HandlerThread;

import java.net.InetAddress;
import java.util.List;

/**
 * Keeps the workspace's resolver on the phone's active network.
 *
 * Android does not give a container a working /etc/resolv.conf. Without one, glibc has no
 * nameserver at all and every fetch fails with "Temporary failure resolving" -- which is exactly
 * how the first set-up on a real phone died, before a single package could be downloaded. The
 * phone's own DNS servers are written in, and rewritten whenever it moves between mobile data
 * and Wi-Fi, because a set-up takes long enough to cross that boundary.
 *
 * Only the phone's own servers are ever used. If it reports none, nothing is written and the
 * failure is reported honestly rather than quietly routed through somebody else's resolver.
 */
final class Dns {
    private final Context context;
    private final ConnectivityManager connectivity;
    private final HandlerThread thread = new HandlerThread("doors-network-dns");
    private boolean registered;
    private Network defaultNetwork;
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            if (network.equals(defaultNetwork)) update(context, properties);
        }
        // Link properties arrive in their own ordered callback. Querying them synchronously
        // from onAvailable can read the previous network's DNS during a handover.
        @Override public void onAvailable(Network network) { defaultNetwork = network; }
        @Override public void onLost(Network network) {
            if (network.equals(defaultNetwork)) defaultNetwork = null;
        }
    };

    Dns(Context context) {
        this.context = context.getApplicationContext();
        connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    void start() {
        if (connectivity == null) return;
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        try {
            connectivity.registerDefaultNetworkCallback(callback, handler);
            registered = true;
        } catch (RuntimeException unavailable) {
            // Per-launch refresh remains available on an OEM that refuses callbacks.
        }
        handler.post(() -> refresh(context));
    }

    void stop() {
        if (registered) {
            try { connectivity.unregisterNetworkCallback(callback); } catch (RuntimeException ignored) {}
            registered = false;
        }
        thread.quitSafely();
    }

    static void refresh(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager == null ? null : manager.getActiveNetwork();
            if (network != null) update(context, manager.getLinkProperties(network));
        } catch (RuntimeException unavailable) { /* Do not turn a transient network loss into a desktop exit. */ }
    }

    private static void update(Context context, LinkProperties properties) {
        if (properties == null) return;
        List<InetAddress> servers = properties.getDnsServers();
        // An empty list while switching networks must not destroy the last working resolver.
        if (servers.isEmpty()) return;
        try {
            ResolverConfig.write(Ubuntu.root(context), servers);
        } catch (java.io.IOException unavailable) { /* Rootfs may still be extracting. Retry at launch. */ }
    }
}
