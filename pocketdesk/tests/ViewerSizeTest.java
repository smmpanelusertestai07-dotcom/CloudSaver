package com.pocketlinux;

public final class ViewerSizeTest {
    private static void size(int width, int height, boolean wide, int expectedWidth, int expectedHeight) {
        int[] actual = ViewerSize.choose(width, height, wide);
        if (actual[0] != expectedWidth || actual[1] != expectedHeight)
            throw new AssertionError(actual[0] + "x" + actual[1] + " expected " + expectedWidth + "x" + expectedHeight);
    }
    public static void main(String[] args) {
        size(720, 1200, false, 720, 1200);
        size(720, 1200, true, 1100, 1832);
        size(1600, 720, true, 1600, 720);
        size(721, 1201, false, 720, 1200);
        // A tall phone keeps a usable settings width without exceeding the framebuffer budget.
        size(360, 1200, true, 1100, 2090);
        for (int width : new int[] {320, 360, 720, 1080, 1440, 2560, 4096}) {
            for (int height : new int[] {320, 640, 1200, 2160, 3840, 10000}) {
                for (boolean wide : new boolean[] {false, true}) {
                    int[] actual = ViewerSize.choose(width, height, wide);
                    if ((actual[0] & 1) != 0 || (actual[1] & 1) != 0 || actual[0] < 2 || actual[1] < 2)
                        throw new AssertionError("Invalid framebuffer dimensions");
                    if ((long) actual[0] * actual[1] > ViewerSize.MAX_PIXELS)
                        throw new AssertionError("Framebuffer memory budget exceeded");
                    if (wide && actual[0] < ViewerSize.WIDE_WIDTH)
                        throw new AssertionError("Wide workspace lost useful app width");
                }
            }
        }
        // Bigger interface: the desktop is asked to be smaller than the screen, and the viewer
        // scales what comes back up to fill it. Every step must stay even, stay inside the
        // memory budget, and never grow the framebuffer.
        int[] plain = ViewerSize.choose(720, 1440, false, 100);
        if (plain[0] != 720 || plain[1] != 1440)
            throw new AssertionError("100 % must be the size it always was");
        int previousWidth = Integer.MAX_VALUE;
        for (int step : ViewerSize.STEPS) {
            int[] scaled = ViewerSize.choose(720, 1440, false, step);
            if (scaled[0] > 720 || scaled[1] > 1440)
                throw new AssertionError("A bigger interface must ask for a SMALLER desktop");
            if (step > 100 && scaled[0] >= previousWidth)
                throw new AssertionError("Each step must make the desktop smaller than the last");
            if ((scaled[0] & 1) != 0 || (scaled[1] & 1) != 0)
                throw new AssertionError("Odd framebuffer width or height");
            if ((long) scaled[0] * scaled[1] > ViewerSize.MAX_PIXELS)
                throw new AssertionError("Framebuffer memory budget exceeded");
            previousWidth = scaled[0];
        }
        // Out-of-range magnification is clamped rather than trusted.
        int[] silly = ViewerSize.choose(720, 1440, false, 5000);
        if (silly[0] < 2 || silly[1] < 2 || (long) silly[0] * silly[1] > ViewerSize.MAX_PIXELS)
            throw new AssertionError("An absurd magnification must still give a usable size");
        System.out.println("PASS ViewerSize (native, wide, orientation, magnification and 84 bounded display cases)");
    }
}
