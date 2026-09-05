package com.pocketdesk;

public final class VncOutboxTest {
    public static void main(String[] args) throws Exception {
        VncOutbox<String> queue = new VncOutbox<>(8);
        queue.offer("shift-down");
        queue.offerPointer("press-at-border", 1);
        for (int i = 0; i < 10000; i++) {
            check(queue.offerPointer("drag-" + i, 1), "motion flood rejected");
        }
        check(queue.offerPointer("release-at-end", 0), "mouse release dropped");
        check(queue.offer("shift-up"), "key release dropped");
        expect(queue, "shift-down", "press-at-border", "drag-9999", "release-at-end", "shift-up");

        queue.offerPointer("before-key", 0);
        queue.offer("key-pair");
        queue.offerPointer("after-key", 0);
        expect(queue, "before-key", "key-pair", "after-key");

        // A button transition remains discrete, including wheel press/release pairs.
        for (int i = 0; i < 4; i++) {
            check(queue.offerPointer("wheel-" + i, 8), "wheel press dropped");
            check(queue.offerPointer("up-" + i, 0), "wheel release dropped");
        }
        check(queue.offerPointer("expendable-move", 0), "full-queue motion must be harmless");
        check(!queue.offer("critical-key"), "discrete saturation must be reported");
        for (int i = 0; i < 4; i++) {
            check(("wheel-" + i).equals(queue.poll(1)), "wheel ordering");
            check(("up-" + i).equals(queue.poll(1)), "wheel release ordering");
        }
        queue.offer("pending"); queue.clear();
        check(queue.poll(1) == null, "close must discard queued work");
        System.out.println("PASS VncOutboxTest (10,000 drag samples, exact transitions, key ordering, bounded saturation)");
    }
    private static void expect(VncOutbox<String> queue, String... values) throws Exception {
        for (String value : values) check(value.equals(queue.poll(1)), "expected " + value);
        check(queue.poll(1) == null, "unexpected trailing event");
    }
    private static void check(boolean okay, String message) {
        if (!okay) throw new AssertionError(message);
    }
}
