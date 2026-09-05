package com.pocketdesk;

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
        System.out.println("PASS ViewerSize (native, wide, orientation and 84 bounded display cases)");
    }
}
