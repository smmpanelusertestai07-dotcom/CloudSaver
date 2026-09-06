package com.pocketlinux;

import java.util.LinkedHashSet;
import java.util.Set;

/** The input that can remain down while a thumb leaves the screen for a toolbar button. */
final class HeldInput {
    interface Output {
        void key(int keysym, boolean down);
        void pointer(int x, int y, int mask);
    }

    private final Output output;
    private final Set<Integer> modifiers = new LinkedHashSet<>();
    private boolean dragging;
    private boolean anchored;
    private double x, y;
    private float lastX, lastY;
    /** Which mouse button is being held: 1 is the left one, 4 the right one. */
    private int button = 1;

    HeldInput(Output output) { this.output = output; }

    boolean isDragging() { return dragging; }
    boolean hasModifier(int keysym) { return modifiers.contains(keysym); }

    void toggleModifier(int keysym) {
        boolean down = !modifiers.remove(keysym);
        if (down) modifiers.add(keysym);
        output.key(keysym, down);
    }

    void releaseModifiers() {
        for (int keysym : modifiers) output.key(keysym, false);
        modifiers.clear();
    }

    void startDrag(int pointerX, int pointerY) {
        startDrag(pointerX, pointerY, 1);
    }

    /**
     * @param mask which button to hold: 1 for the left one (move a divider, drag an item), 4 for
     *             the right one, which with Alt held is how every window manager -- Openbox
     *             included -- resizes a window from anywhere inside it rather than from its edge.
     *             A phone has no window edge worth aiming at, so that is the resize that works.
     */
    void startDrag(int pointerX, int pointerY, int mask) {
        if (dragging) return;
        x = pointerX;
        y = pointerY;
        anchored = false;
        dragging = true;
        button = mask;
        output.pointer(pointerX, pointerY, mask);
    }

    void beginStroke(float touchX, float touchY) {
        lastX = touchX;
        lastY = touchY;
        anchored = true;
    }

    void moveStroke(float touchX, float touchY, int width, int height, float sensitivity) {
        if (!dragging) return;
        if (!anchored) { beginStroke(touchX, touchY); return; }
        // Keep fractional travel: repeated tiny movements must still reach a narrow divider.
        x = Math.max(0, Math.min(width - 1, x + (touchX - lastX) * sensitivity));
        y = Math.max(0, Math.min(height - 1, y + (touchY - lastY) * sensitivity));
        lastX = touchX;
        lastY = touchY;
        output.pointer((int) Math.round(x), (int) Math.round(y), button);
    }

    /** Lift/reposition a thumb without releasing the remotely held mouse button. */
    void endStroke() { anchored = false; }

    void releasePointer() {
        if (dragging) output.pointer((int) Math.round(x), (int) Math.round(y), 0);
        dragging = false;
        anchored = false;
        button = 1;
    }

    void releaseAll() {
        releasePointer();
        releaseModifiers();
    }
}
