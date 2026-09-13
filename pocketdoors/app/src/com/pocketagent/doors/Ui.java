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
        view.setMinHeight(dp(context, 48));
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
