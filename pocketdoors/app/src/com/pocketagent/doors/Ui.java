package com.pocketagent.doors;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The few things every screen needs, in one place.
 *
 * There is very little interface in this app on purpose -- each agent's own publisher draws
 * theirs. What is here is the frame around them: a set-up screen, a list of agents, and the
 * bar of keys a touch screen cannot otherwise reach. So these helpers are deliberately small.
 *
 * Colours come from Brand, which mirrors the same tokens PocketAgent's icon is drawn from.
 * The interface itself stays neutral: ink on cream, or bone on ink. Colour in this app means
 * something -- running, needs you, failed -- and is never decoration.
 */
final class Ui {
    // Warm neutrals, not grey.
    static final int LIGHT_BG = Color.rgb(250, 249, 246);
    static final int LIGHT_CARD = Color.rgb(240, 238, 232);
    static final int LIGHT_TEXT = Color.rgb(38, 38, 36);
    static final int LIGHT_MUTED = Color.rgb(104, 102, 96);
    static final int LIGHT_LINE = Color.rgb(219, 216, 208);

    static final int DARK_BG = Color.rgb(23, 23, 23);
    static final int DARK_CARD = Color.rgb(35, 35, 35);
    static final int DARK_TEXT = Color.rgb(240, 238, 233);
    static final int DARK_MUTED = Color.rgb(176, 174, 168);
    static final int DARK_LINE = Color.rgb(60, 60, 57);

    // The only three colours that carry meaning.
    static final int RUNNING = Color.rgb(18, 145, 80);
    static final int NEEDS_YOU = Color.rgb(184, 116, 0);
    static final int FAILED = Color.rgb(199, 54, 43);

    static final int CONTENT_MAX_DP = 600;

    private Ui() {}

    static boolean dark(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    static int bg(boolean dark) { return dark ? DARK_BG : LIGHT_BG; }
    static int card(boolean dark) { return dark ? DARK_CARD : LIGHT_CARD; }
    static int text(boolean dark) { return dark ? DARK_TEXT : LIGHT_TEXT; }
    static int muted(boolean dark) { return dark ? DARK_MUTED : LIGHT_MUTED; }
    static int line(boolean dark) { return dark ? DARK_LINE : LIGHT_LINE; }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context context, CharSequence words, float size, int colour) {
        TextView view = new TextView(context);
        view.setText(words);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(colour);
        view.setLineSpacing(dp(context, 2), 1f);
        return view;
    }

    static TextView bold(Context context, CharSequence words, float size, int colour) {
        TextView view = text(context, words, size, colour);
        view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return view;
    }

    static TextView mono(Context context, CharSequence words, float size, int colour) {
        TextView view = text(context, words, size, colour);
        view.setTypeface(Typeface.MONOSPACE);
        return view;
    }

    /**
     * The least a finger may be asked to hit. Android's own accessibility guidance, and the
     * reason nothing here is smaller than this however tight the layout gets.
     */
    static final int TOUCH_TARGET_DP = 48;

    /**
     * The surface everything sits on: a shallow top-to-bottom gradient inside a hairline border.
     *
     * Android has no backdrop blur for a View -- RenderEffect blurs a view's own content, not
     * what is painted behind it -- so real frosted glass is not available here, and pretending
     * otherwise costs a frame and gets a grey box. What actually reads as glass is what glass
     * does to light: a lit top edge, a slightly darker bottom, and a border thin enough to be a
     * highlight rather than a box. That is this, and it is one cached drawable.
     */
    static GradientDrawable glass(Context context, boolean dark, float radiusDp) {
        int top = dark ? Color.rgb(46, 46, 45) : Color.rgb(255, 255, 254);
        int bottom = dark ? Color.rgb(30, 30, 29) : Color.rgb(240, 238, 232);
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        drawable.setCornerRadius(dp(context, radiusDp));
        drawable.setStroke(Math.max(1, dp(context, 1)),
                dark ? Color.rgb(72, 72, 69) : LIGHT_LINE);
        return drawable;
    }

    /**
     * Brushed metal: a diagonal sweep across the brand, for the one surface that leads a screen.
     *
     * Three stops rather than two, because two reads as a flat tint at this size. The sweep runs
     * corner to corner so the highlight crosses the card instead of banding across it.
     */
    static GradientDrawable metal(Context context, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Brand.TILE_TOP, Brand.TILE_FLAT, Brand.TILE_BOTTOM});
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    /** A glass panel with the padding and spacing every card on every screen shares. */
    static LinearLayout card(Context context, boolean dark) {
        LinearLayout card = column(context);
        int pad = dp(context, 16);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(glass(context, dark, 20));
        // Elevation in light only. On a dark ground a shadow is invisible and the overdraw is
        // paid for anyway, which on this phone is a frame that could have gone to the editor.
        card.setElevation(dark ? 0 : dp(context, 1));
        return card;
    }

    /**
     * A small capitalised label above a group, with the letter-spacing that makes it read as a
     * label rather than shouting. The standard way to name a section without a heavy heading.
     */
    static TextView sectionLabel(Context context, String label, boolean dark) {
        TextView view = mono(context, label.toUpperCase(java.util.Locale.US), 11, muted(dark));
        view.setLetterSpacing(0.09f);
        return view;
    }

    static GradientDrawable fill(Context context, int colour, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(colour);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    static GradientDrawable outlined(Context context, int fill, int stroke, float radiusDp) {
        GradientDrawable drawable = fill(context, fill, radiusDp);
        drawable.setStroke(Math.max(1, dp(context, 1)), stroke);
        return drawable;
    }

    static RippleDrawable tappable(Context context, android.graphics.drawable.Drawable base, boolean dark) {
        return new RippleDrawable(
                ColorStateList.valueOf(dark ? 0x28FFFFFF : 0x1C000000), base, null);
    }

    /** A button that says exactly what it does, sized for a finger. */
    static TextView button(Context context, CharSequence label, boolean primary, boolean dark) {
        TextView view = bold(context, label, 15.5f, primary ? (dark ? DARK_BG : LIGHT_BG) : text(dark));
        view.setGravity(Gravity.CENTER);
        int padY = dp(context, 14);
        int padX = dp(context, 20);
        view.setPadding(padX, padY, padX, padY);
        // A finger, not a cursor. 48dp is Android's own guidance, named here so the
        // number and the reason for it cannot drift apart.
        view.setMinHeight(dp(context, TOUCH_TARGET_DP));
        view.setBackground(tappable(context,
                primary ? fill(context, text(dark), 12)
                        : outlined(context, card(dark), line(dark), 12), dark));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout.LayoutParams wide(Context context, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, topMarginDp);
        return params;
    }

    /** A small capsule saying one word about state. Its colour is the state, not decoration. */
    static TextView pill(Context context, CharSequence label, int colour) {
        TextView view = bold(context, label, 11.5f, colour);
        view.setAllCaps(true);
        view.setLetterSpacing(0.06f);
        int padX = dp(context, 8);
        int padY = dp(context, 3);
        view.setPadding(padX, padY, padX, padY);
        GradientDrawable background = fill(context, (colour & 0x00FFFFFF) | 0x22000000, 5);
        view.setBackground(background);
        return view;
    }

    static View divider(Context context, boolean dark) {
        View view = new View(context);
        view.setBackgroundColor(line(dark));
        return view;
    }
}
