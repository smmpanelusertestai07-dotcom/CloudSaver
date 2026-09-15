package com.pocketide;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/**
 * How large the editor should draw itself on this particular phone.
 *
 * Visual Studio Code was designed for a window about four times as wide as a phone, and the one
 * number that decides whether it is usable here is its zoom level. Get it wrong in one
 * direction and the text is too small to read; wrong in the other and a single panel fills the
 * screen with nothing beside it. The right value is different on a 5-inch phone, a 6.7-inch
 * phone and a folded-open tablet, which is why it is worked out rather than chosen.
 *
 * The arithmetic, and it is arithmetic rather than taste:
 *
 *   The WebView hands code-server roughly one CSS pixel per density-independent pixel, so the
 *   editor starts with the phone's own width in dp. A zoom level z scales everything by
 *   1.2^z -- VS Code's own step, not an invention here -- which leaves the editor an EFFECTIVE
 *   width of widthDp / 1.2^z to lay itself out in. The web build of the editor has no
 *   window.zoomLevel of its own (that is an Electron setting, and the one the app once wrote
 *   did nothing), so WorkspaceActivity applies z as the page's viewport: that width as the
 *   layout width and 1.2^z as the scale, which is the same arithmetic in the place a browser
 *   keeps it.
 *
 *   Below about 300 effective pixels the editor stops being an editor: the command centre
 *   truncates to an ellipsis, the agents' panels get a column too narrow for a diff, and the
 *   terminal wraps every path. So the largest z that still leaves 300 is the answer:
 *
 *       z = log(widthDp / 300) / log(1.2)
 *
 *   On a 360 dp phone that is 1.0; on a 411 dp phone 1.7; on a folded-open 673 dp screen it
 *   reaches the 2.5 cap. A phone narrower than 300 dp does not exist, and if one did it would
 *   get z = 1.0 rather than something smaller than the editor's own default.
 *
 * The owner's text size is honoured on top of that. Someone who has set Android's font scale to
 * 1.3 has said, in the only place the system offers, that they want text larger everywhere --
 * and an editor that ignores it is an editor they cannot read.
 */
final class Screen {

    /** The narrowest the editor can be and still be one. Measured, not guessed: see the class note. */
    private static final float MIN_EFFECTIVE_DP = 300f;

    /**
     * The width the editor is never taken below, whatever else is asked for.
     *
     * This is the fix for a screenshot an owner sent of the editor with its own panel running
     * off the side of the screen. The font scale used to be ADDED to the worked-out zoom with
     * nothing holding the result: a 360 dp phone set to 1.3x text reached the 2.5 cap and left
     * Visual Studio Code 228 effective pixels, well under the 300 the rest of this class is
     * built around. At that width the workbench stops wrapping and starts overflowing, which is
     * precisely what the screenshot shows.
     *
     * 280 rather than 300 so that a larger text setting still does something. Below 280 it
     * stops being a text-size preference and becomes a broken layout, so that is where it ends.
     */
    private static final float FLOOR_EFFECTIVE_DP = 280f;

    /** VS Code's own zoom step. Each level is 20 % larger than the one below it. */
    private static final double STEP = 1.2;

    /**
     * Zero, not one, and the difference only ever shows on a genuinely narrow phone.
     *
     * This used to be 1.0 on the reasoning that no phone is narrower than 300 dp, so the
     * worked-out value could never want less. That was true of the width alone and stopped
     * being true once the owner's text scale was folded in: on a 320 dp phone the floor below
     * wants a zoom under 1.0, and a minimum of 1.0 overrode it and handed the workbench 267
     * effective pixels -- under its own floor, on the narrowest phones, which are the ones that
     * could least afford it. Zero is Visual Studio Code's own 100 %, so nothing is ever
     * rendered smaller than the editor's untouched default.
     */
    private static final double MIN_ZOOM = 0.0;
    private static final double MAX_ZOOM = 2.5;
    /** The same cap, for the menu's larger-text step, which counts in tenths. */
    static final int MAX_ZOOM_TENTHS = 25;

    /** Past this the phone is a tablet or an open foldable, and the desktop layout fits. */
    static final int DESKTOP_WIDTH_DP = 600;

    private Screen() {}

    /**
     * The phone's UPRIGHT width in density-independent pixels, whichever way it is held.
     *
     * The zoom is worked out once from this and left alone when the phone is turned, as the
     * Help says; a zoom taken from the long edge is one that overflows the screen the moment
     * the phone comes back upright, and the desktop layout is for screens that are wide
     * standing up, not phones lying down.
     */
    static int widthDp(Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int widthPixels = Math.min(metrics.widthPixels, metrics.heightPixels);
        WindowManager windows = context.getSystemService(WindowManager.class);
        if (windows != null) {
            try {
                android.graphics.Rect bounds = windows.getMaximumWindowMetrics().getBounds();
                widthPixels = Math.min(bounds.width(), bounds.height());
            } catch (Throwable unavailable) {
                // Some builds refuse this outside an Activity context. The metrics above are
                // already a reasonable answer; a thrown exception must not become no answer.
            }
        }
        float density = metrics.density <= 0 ? 1f : metrics.density;
        int dp = Math.round(widthPixels / density);
        // A phone reporting an implausible width is not worth believing. 320 is the narrowest
        // Android has ever shipped and 2000 is wider than any foldable opens to.
        if (dp < 320 || dp > 2000) return 360;
        return dp;
    }

    /** The window's width as it is held right now: what the editor's layout has to fill. */
    static int currentWidthDp(Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int widthPixels = metrics.widthPixels;
        WindowManager windows = context.getSystemService(WindowManager.class);
        if (windows != null) {
            try {
                widthPixels = windows.getMaximumWindowMetrics().getBounds().width();
            } catch (Throwable unavailable) {
                // As above: the metrics are a fair answer when the window's are refused.
            }
        }
        float density = metrics.density <= 0 ? 1f : metrics.density;
        int dp = Math.round(widthPixels / density);
        if (dp < 320 || dp > 2000) return widthDp(context);
        return dp;
    }

    /** The scale the editor is drawn at: 1.2 to the zoom level, as VS Code counts it. */
    static double scale(Context context) {
        return Math.pow(STEP, zoomTenths(context) / 10.0);
    }

    /** True when this screen is wide enough that the full desktop layout is the better one. */
    static boolean wideEnoughForDesktop(Context context) {
        return widthDp(context) >= DESKTOP_WIDTH_DP;
    }

    /**
     * The zoom level to give the editor, as VS Code's own setting expects it.
     *
     * Returned in tenths so it can be stored in the same preference a manual choice uses, and
     * so the value in Settings and the value the editor receives cannot drift.
     */
    static int automaticZoomTenths(Context context) {
        double width = widthDp(context);
        double zoom = Math.log(width / MIN_EFFECTIVE_DP) / Math.log(STEP);

        // Android's own font scale, honoured rather than overridden. A person who set text to
        // 1.3 has said what they want in the one place the system provides for saying it.
        //
        // This does cost width, and the cost is real rather than hidden: at font scale 1.3 a
        // 412 dp phone reaches the 2.5 cap and the editor is left about 260 effective pixels
        // instead of 300. That is the right way round. Someone who asked for larger text wants
        // to read; cramped and legible beats roomy and illegible, and the cap is what stops it
        // going further than cramped.
        float fontScale = context.getResources().getConfiguration().fontScale;
        if (fontScale > 0) {
            zoom += Math.log(fontScale) / Math.log(STEP);
        }

        // The floor, applied after the font scale rather than before it. This is the line
        // that was missing: without it the addition above could ask for any zoom at all and
        // the only thing stopping it was a cap chosen for wide screens.
        double widest = Math.log(width / FLOOR_EFFECTIVE_DP) / Math.log(STEP);
        if (zoom > widest) zoom = widest;

        if (zoom < MIN_ZOOM) zoom = MIN_ZOOM;
        if (zoom > MAX_ZOOM) zoom = MAX_ZOOM;
        // Rounded DOWN to the tenth the editor is actually given. Rounding to the nearest one
        // can round up, and a zoom one tenth higher than the clamp above allowed is a zoom that
        // breaks the floor the clamp exists to hold: at 393 dp and 1.15x text it put the
        // workbench back under 280 effective pixels. Down always errs towards more room.
        return (int) Math.floor(zoom * 10);
    }

    /**
     * What the editor is actually started with: the owner's choice, or the worked-out value.
     *
     * Zero means automatic. It is the default, because the whole point is that nobody should
     * have to find this setting before the editor is usable.
     */
    static int zoomTenths(Context context) {
        int chosen = Prefs.of(context).getInt(Prefs.EDITOR_ZOOM, 0);
        return chosen > 0 ? chosen : automaticZoomTenths(context);
    }

    /** The layout the editor is started with: the owner's choice, or the one this screen fits. */
    static String layout(Context context) {
        String chosen = Prefs.of(context).getString(Prefs.EDITOR_LAYOUT, "auto");
        if ("phone".equals(chosen) || "desktop".equals(chosen)) return chosen;
        return wideEnoughForDesktop(context) ? "desktop" : "phone";
    }

    /** What the automatic choice works out to, for the Settings row to show. */
    static String describeAutomatic(Context context) {
        int tenths = automaticZoomTenths(context);
        return widthDp(context) + " dp wide · zoom " + (tenths / 10) + "." + (tenths % 10)
                + " · " + effectiveDp(context) + " px for the editor"
                + " · " + (wideEnoughForDesktop(context) ? "desktop" : "phone") + " layout";
    }

    /**
     * The width the editor is left with once the zoom is applied -- the number that decides
     * whether the workbench fits on the screen or runs off the side of it.
     */
    static int effectiveDp(Context context) {
        int tenths = zoomTenths(context);
        return (int) Math.round(widthDp(context) / Math.pow(STEP, tenths / 10.0));
    }
}
