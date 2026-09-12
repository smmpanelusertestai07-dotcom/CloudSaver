package com.pocketlinux;

import java.util.ArrayDeque;

/** Bounded sender queue. Only consecutive motion samples may be coalesced. */
final class VncOutbox<T> {
    private static final class Entry<T> {
        final T task;
        final int mask;
        final boolean motion;
        Entry(T task, int mask, boolean motion) {
            this.task = task; this.mask = mask; this.motion = motion;
        }
    }
    private final ArrayDeque<Entry<T>> pending = new ArrayDeque<>();
    private final int capacity;
    private int lastPointerMask;

    VncOutbox(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Positive capacity required");
        this.capacity = capacity;
    }

    synchronized boolean offer(T task) { return append(task, -1, false); }

    synchronized boolean offerPointer(T task, int mask) {
        mask &= 255;
        boolean motion = mask == lastPointerMask;
        Entry<T> tail = pending.peekLast();
        // Keep the original button-down position. Moving it to the end of a drag would
        // turn a resize into a click at a completely different place.
        if (motion && tail != null && tail.motion && tail.mask == mask) {
            pending.removeLast();
        } else if (motion && pending.size() >= capacity) {
            return true; // A later pointer event supplies the latest position.
        }
        if (!append(task, mask, motion)) return false;
        lastPointerMask = mask;
        return true;
    }

    private boolean append(T task, int mask, boolean motion) {
        if (pending.size() >= capacity) return false;
        pending.addLast(new Entry<>(task, mask, motion));
        notifyAll();
        return true;
    }

    synchronized T poll(long timeoutMillis) throws InterruptedException {
        if (pending.isEmpty()) wait(timeoutMillis);
        Entry<T> next = pending.pollFirst();
        return next == null ? null : next.task;
    }

    synchronized void clear() { pending.clear(); notifyAll(); }
}
