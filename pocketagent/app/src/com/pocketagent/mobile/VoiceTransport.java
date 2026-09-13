package com.pocketagent.mobile;

import java.util.ArrayDeque;

/** Ephemeral in-process signaling only. SDP never enters Intent broadcasts or disk. */
final class VoiceTransport {
    static final class Signal { final String kind, value; Signal(String k, String v) { kind = k; value = v; } }
    private static final ArrayDeque<Signal> queue = new ArrayDeque<>();
    private static String attached = "";
    private VoiceTransport() { }
    static synchronized void attach(String owner) { validOwner(owner); attached = owner; queue.clear(); }
    static synchronized boolean attached(String owner) { return owner != null && owner.equals(attached) && !owner.isEmpty(); }
    static synchronized void signal(String owner, String kind, String value) {
        if (!attached(owner)) return;
        if (!("answer".equals(kind) || "close".equals(kind))) return;
        if (value == null || value.length() > 65536) return;
        // There is one SDP answer per connection. Bound transient control messages too.
        if (queue.size() >= 4) { queue.clear(); queue.add(new Signal("close", "Voice signaling overflowed. Reconnect before trying again.")); return; }
        queue.add(new Signal(kind, value));
    }
    static synchronized Signal poll(String owner) { return attached(owner) ? queue.poll() : null; }
    static synchronized void detach(String owner) { if (attached(owner)) { queue.clear(); attached = ""; } }
    static void validOwner(String owner) {
        if (owner == null || !owner.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Open Voice again to start a new connection.");
    }
}
