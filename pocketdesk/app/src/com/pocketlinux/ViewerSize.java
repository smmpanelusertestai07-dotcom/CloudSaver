package com.pocketlinux;

/** Bounds the framebuffer independently of phone toolbars and app window minimum sizes. */
final class ViewerSize {
    static final long MAX_PIXELS = 2_300_000L;
    static final int WIDE_WIDTH = 1100;

    /**
     * How much smaller than the screen the Linux desktop is made, so that everything on it --
     * text, icons, title bars, close buttons -- is drawn bigger.
     *
     * This is the only size control that works on a desktop that is already running. The real
     * one, the screen's dpi, is read by every Linux program when it starts, so changing it can
     * only apply to the next session; asking for a smaller desktop and letting the viewer scale
     * it up to fill the screen applies at once, to everything, with no restart and nothing
     * cropped. The cost is a little softness, which is the right trade when the alternative is
     * type too small to read.
     */
    static final int[] STEPS = { 100, 115, 130, 150 };

    private ViewerSize() { }

    static int[] choose(int viewWidth, int viewHeight, boolean wide) {
        return choose(viewWidth, viewHeight, wide, 100);
    }

    static int[] choose(int viewWidth, int viewHeight, boolean wide, int magnification) {
        int percent = Math.max(100, Math.min(magnification, 200));
        int width = Math.max(2, Math.round(Math.max(2, viewWidth) * 100f / percent));
        int height = Math.max(2, Math.round(Math.max(2, viewHeight) * 100f / percent));
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
