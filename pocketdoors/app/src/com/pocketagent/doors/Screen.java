package com.pocketagent.doors;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/**
 * The shape of the screen the editor has to fit into.
 *
 * The display server was started at 1280x720 -- a landscape desktop, on a phone that is
 * 720x1600 portrait. So the editor drew a window wider than anything that could be shown, and
 * its own dialogs opened half off the right-hand edge. Nothing was wrong with the editor; it was
 * given a screen that does not exist on this phone.
 *
 * What it is given now is the phone's own screen, in the phone's own orientation, with one
 * number worked out from it: how much larger than its desktop default the editor should draw, so
 * that a menu written for a mouse can be hit with a finger.
 */
final class Screen {

    /**
     * The longest side the workspace is allowed.
     *
     * A display server holds a full framebuffer, and so does the viewer's copy of it: at four
     * bytes a pixel, 720x1600 is about 4.6 MB each. That is affordable. A phone that reports a
     * far larger screen is not worth believing on a device with this much memory, so it is
     * capped rather than trusted.
     */
    private static final int LONG_SIDE_CAP = 1920;

    /**
     * The width at which a desktop editor's own controls are about the size of a fingertip.
     *
     * Worked out on the reference phone and used unchanged by the other app in this repository:
     * the short side divided by this is how much larger than default to draw. On 720 pixels that
     * is 1.28, which puts a menu row at roughly a finger's width.
     */
    private static final float COMFORTABLE_SHORT_SIDE = 560f;

    /** The workspace's screen size, as "WIDTHxHEIGHT", in the phone's own orientation. */
    static String geometry(Context context) {
        int[] size = pixels(context);
        return size[0] + "x" + size[1];
    }

    /**
     * How much larger than its desktop default the editor should draw, to two places.
     *
     * Between 1 and 2: below 1 the editor would be smaller than its designers intended and
     * unreadable here, and above 2 a single panel fills the screen and nothing else fits.
     */
    static String scale(Context context) {
        int[] size = pixels(context);
        int shortSide = Math.min(size[0], size[1]);
        float scale = shortSide / COMFORTABLE_SHORT_SIDE;
        if (scale < 1f) scale = 1f;
        if (scale > 2f) scale = 2f;
        return String.format(java.util.Locale.US, "%.2f", scale);
    }

    /** The phone's own reported density, which X uses to size everything it draws itself. */
    static String dpi(Context context) {
        DisplayMetrics metrics = metrics(context);
        int dpi = Math.round(metrics.xdpi);
        if (dpi < 96 || dpi > 640) dpi = metrics.densityDpi;
        if (dpi < 96) dpi = 160;
        return String.valueOf(dpi);
    }

    /**
     * The screen in pixels, portrait-shaped, even and capped.
     *
     * Even, because a framebuffer with an odd width is a row of torn pixels down one edge in
     * several encodings; portrait, because turning the phone should not mean the editor's
     * windows no longer fit; capped, because the memory is this phone's to spend elsewhere.
     */
    private static int[] pixels(Context context) {
        DisplayMetrics metrics = metrics(context);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        if (width <= 0 || height <= 0) { width = 720; height = 1440; }
        // Always short side first: a phone handed a landscape desktop draws windows wider than
        // the screen, which is the whole reason this class exists.
        int shortSide = Math.min(width, height);
        int longSide = Math.max(width, height);
        if (longSide > LONG_SIDE_CAP) {
            shortSide = Math.round(shortSide * (LONG_SIDE_CAP / (float) longSide));
            longSide = LONG_SIDE_CAP;
        }
        return new int[]{shortSide - (shortSide % 2), longSide - (longSide % 2)};
    }

    private static DisplayMetrics metrics(Context context) {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windows = context.getSystemService(WindowManager.class);
        if (windows != null) {
            android.graphics.Rect bounds =
                    windows.getMaximumWindowMetrics().getBounds();
            metrics = new DisplayMetrics();
            metrics.setTo(context.getResources().getDisplayMetrics());
            metrics.widthPixels = bounds.width();
            metrics.heightPixels = bounds.height();
            return metrics;
        }
        return context.getResources().getDisplayMetrics();
    }

    private Screen() {}
}
