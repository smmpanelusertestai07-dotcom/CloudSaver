package com.pocketlinux;

/** Bounds the framebuffer independently of phone toolbars and app window minimum sizes. */
final class ViewerSize {
    static final long MAX_PIXELS = 2_300_000L;
    static final int WIDE_WIDTH = 1100;
    private ViewerSize() { }

    static int[] choose(int viewWidth, int viewHeight, boolean wide) {
        int width = Math.max(2, viewWidth);
        int height = Math.max(2, viewHeight);
        if (wide && width < WIDE_WIDTH) {
            height = (int) Math.round(height * (WIDE_WIDTH / (double) width));
            width = WIDE_WIDTH;
        }
        long pixels = (long) width * height;
        if (pixels > MAX_PIXELS) {
            double shrink = Math.sqrt(MAX_PIXELS / (double) pixels);
            width = Math.max(2, (int) Math.floor(width * shrink));
            height = Math.max(2, (int) Math.floor(height * shrink));
            if (wide && width < WIDE_WIDTH) {
                // On exceptionally tall phones keep useful app width and letterbox the shorter
                // framebuffer instead of allocating unbounded height or cropping its sides.
                width = WIDE_WIDTH;
                height = (int) (MAX_PIXELS / width);
            }
        }
        return new int[] { width - width % 2, height - height % 2 };
    }
}
