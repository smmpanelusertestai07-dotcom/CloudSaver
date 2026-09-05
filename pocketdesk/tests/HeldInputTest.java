package com.pocketdesk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class HeldInputTest {
    private static final class Wire implements HeldInput.Output {
        final List<String> events = new ArrayList<>();
        @Override public void key(int key, boolean down) { events.add("key " + key + " " + down); }
        @Override public void pointer(int x, int y, int mask) { events.add(x + "," + y + " " + mask); }
        void expect(String... expected) {
            if (!events.equals(Arrays.asList(expected))) throw new AssertionError(events.toString());
            events.clear();
        }
    }

    public static void main(String[] args) {
        // Resize from the existing remote pointer, then lift/reposition a thumb. The second
        // stroke must neither click again nor jump to the finger's absolute desktop position.
        Wire wire = new Wire();
        HeldInput input = new HeldInput(wire);
        input.startDrag(320, 240);
        input.beginStroke(10, 20);
        input.moveStroke(30, 25, 800, 600, 1f);
        input.endStroke();
        input.beginStroke(200, 400);
        input.moveStroke(210, 390, 800, 600, 1f);
        input.releasePointer();
        wire.expect("320,240 1", "340,245 1", "350,235 1", "350,235 0");

        // A narrow splitter can be adjusted by several subpixel motions without losing them.
        input.startDrag(100, 100);
        input.beginStroke(0, 0);
        input.moveStroke(.2f, 0, 800, 600, 1f);
        input.moveStroke(.4f, 0, 800, 600, 1f);
        input.moveStroke(.6f, 0, 800, 600, 1f);
        input.releasePointer();
        wire.expect("100,100 1", "100,100 1", "100,100 1", "101,100 1", "101,100 0");

        // Window edges clamp the drag safely, without invisible overshoot that makes a pointer
        // stick at the edge after changing direction.
        input.startDrag(795, 598);
        input.beginStroke(0, 0);
        input.moveStroke(100, 100, 800, 600, 1f);
        input.moveStroke(98, 98, 800, 600, 1f);
        input.releasePointer();
        wire.expect("795,598 1", "799,599 1", "797,597 1", "797,597 0");

        // Shift+Ctrl selection can coexist with held mouse input. Background/disconnect must
        // release every state once, and a later desktop session must begin with no held keys.
        input.toggleModifier(0xffe1);
        input.toggleModifier(0xffe3);
        input.startDrag(50, 60);
        input.releaseAll();
        input.releaseAll();
        if (input.isDragging() || input.hasModifier(0xffe1) || input.hasModifier(0xffe3))
            throw new AssertionError("Input remained held after leaving the viewer");
        wire.expect("key 65505 true", "key 65507 true", "50,60 1", "50,60 0",
                "key 65505 false", "key 65507 false");

        // Cancelling a modifier must not release another modifier in a combined shortcut.
        input.toggleModifier(0xffe1);
        input.toggleModifier(0xffe9);
        input.toggleModifier(0xffe1);
        if (input.hasModifier(0xffe1) || !input.hasModifier(0xffe9))
            throw new AssertionError("Combined shortcut changed unexpectedly");
        input.releaseModifiers();
        wire.expect("key 65505 true", "key 65513 true", "key 65505 false", "key 65513 false");

        // Once cancelled, late MOVE events from the old gesture must never press anything.
        input.startDrag(30, 40);
        input.beginStroke(0, 0);
        input.releaseAll();
        input.moveStroke(20, 20, 800, 600, 1f);
        wire.expect("30,40 1", "30,40 0");
        System.out.println("PASS HeldInput (multi-stroke resizing, precision, bounds, Shift and lifecycle release)");
    }
}
