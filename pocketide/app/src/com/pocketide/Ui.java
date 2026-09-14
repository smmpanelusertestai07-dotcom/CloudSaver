package com.pocketide;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * The whole design system, hand-built, in one file.
 *
 * There is no Material components library here and no AndroidX, which is a deliberate trade and
 * worth stating plainly: it costs this file, and it buys an APK that does not need a yearly
 * migration to keep compiling. The platform's own View classes have not broken in a decade;
 * the support libraries break every year.
 *
 * The language is Material 3 Expressive, because that is Android's language in 2026 -- Google
 * has publicly ruled out Liquid Glass for Android, so shipping a Liquid Glass clone here would
 * read as an iOS port rather than a modern Android app. What Expressive asks for is bolder
 * colour, generous shape and real hierarchy; what it does not ask for is colour everywhere, and
 * this app keeps colour for meaning.
 *
 * Glass is the one flourish, and it is honest about what Android can do. There is no backdrop
 * blur for a View -- RenderEffect blurs a view's own content, not what is painted behind it --
 * so real frosted glass is not available and faking it costs a frame for a grey box. What reads
 * as glass is what glass does to light: a lit top edge, a slightly darker bottom, and a border
 * thin enough to be a highlight rather than a box. That is one cached drawable.
 */
final class Ui {

    // ------------------------------------------------------------------ palette
    // Warm neutrals, not grey. A pure grey reads as unconsidered next to a violet this deep.

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

    /** The only three colours that carry meaning. Everything else is neutral on purpose. */
    /**
     * Green, amber, red -- one value per ground rather than one value for both.
     *
     * A single value cannot work: a green dark enough to read on cream is too dark to read on
     * near-black, and a red light enough for near-black is too light for cream. The app shipped
     * one of each and every one of them failed somewhere -- the failure red measured 2.99:1 on
     * the dark card, which is under the floor even for large text, and it was the colour on the
     * "Remove everything" button.
     *
     * Each pair below is at least 4.5:1 on its own card and its own page, computed rather than
     * eyeballed, and tests/contrast.py recomputes them on every build.
     */
    private static final int RUNNING_LIGHT = Color.parseColor("#0F7038");
    private static final int RUNNING_DARK = Color.parseColor("#4ADE80");
    private static final int NEEDS_YOU_LIGHT = Color.parseColor("#8A5200");
    private static final int NEEDS_YOU_DARK = Color.parseColor("#F5B93D");
    private static final int FAILED_LIGHT = Color.parseColor("#B3261E");
    private static final int FAILED_DARK = Color.parseColor("#FF7A6E");

    static int running(boolean dark) { return dark ? RUNNING_DARK : RUNNING_LIGHT; }
    static int needsYou(boolean dark) { return dark ? NEEDS_YOU_DARK : NEEDS_YOU_LIGHT; }
    static int failed(boolean dark) { return dark ? FAILED_DARK : FAILED_LIGHT; }

    /**
     * The least a finger may be asked to hit, from Android's own accessibility guidance. It is
     * named here rather than typed at each call site so the number and its reason cannot drift.
     */
    static final int TOUCH_TARGET_DP = 48;

    /** Past this, a line of text is too long to track back to on a phone. */
    static final int CONTENT_MAX_DP = 600;

    private Ui() {}

    // ------------------------------------------------------------------ theme

    static boolean dark(Context context) {
        String choice = Theme.choice(context);
        if ("light".equals(choice)) return false;
        if ("dark".equals(choice)) return true;
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    static int bg(boolean dark) { return dark ? DARK_BG : LIGHT_BG; }
    static int card(boolean dark) { return dark ? DARK_CARD : LIGHT_CARD; }
    static int text(boolean dark) { return dark ? DARK_TEXT : LIGHT_TEXT; }
    static int muted(boolean dark) { return dark ? DARK_MUTED : LIGHT_MUTED; }
    static int line(boolean dark) { return dark ? DARK_LINE : LIGHT_LINE; }
    static int accent(boolean dark) { return Brand.accent(dark); }

    /**
     * What is drawn ON a surface tinted with the accent -- the icon inside the navigation bar's
     * active indicator, and anything else that sits on an accent-coloured container.
     *
     * It is a separate colour from the accent because it has to be, and the arithmetic says so:
     * the indicator is the accent at low opacity over the bar's glass, and the accent ON that
     * measures 3.28:1 on the light theme. Under the floor, on the one control in the app that
     * says which screen you are looking at.
     *
     * Material 3 models this properly and this follows it: the container is a light tone of the
     * hue and what sits on it is a DARK tone of the same hue -- TILE_FLAT, the deep violet the
     * launcher tile is built from. On the dark theme the relationship inverts, so the light
     * tone goes on top and MARK, the brand's off-white, is that tone. Both measure between
     * 6.8:1 and 10:1 on their own indicator, and tests/contrast.py recomputes them over the
     * composited pill rather than over a flat colour, because a flat colour is not what is
     * there.
     */
    static int onAccentContainer(boolean dark) { return dark ? Brand.MARK : Brand.TILE_FLAT; }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ type

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

    static TextView medium(Context context, CharSequence words, float size, int colour) {
        TextView view = text(context, words, size, colour);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    static TextView mono(Context context, CharSequence words, float size, int colour) {
        TextView view = text(context, words, size, colour);
        view.setTypeface(Typeface.MONOSPACE);
        return view;
    }

    /** A small capitalised label above a group: names a section without a heavy heading. */
    static TextView sectionLabel(Context context, String label, boolean dark) {
        TextView view = mono(context, label.toUpperCase(Locale.US), 11, muted(dark));
        view.setLetterSpacing(0.09f);
        return view;
    }

    // ------------------------------------------------------------------ surfaces

    /** The surface everything sits on: a shallow gradient inside a hairline border. */
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
     * Brushed metal across the brand, for the one surface that leads a screen.
     *
     * Three stops rather than two, because two reads as a flat tint at card size. The sweep
     * runs corner to corner so the highlight crosses the card instead of banding across it.
     */
    static GradientDrawable metal(Context context, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Brand.TILE_TOP, Brand.TILE_FLAT, Brand.TILE_BOTTOM});
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
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

    /**
     * A background that responds to a finger.
     *
     * The third argument used to be null, and that is the difference between a ripple and no
     * ripple at all. RippleDrawable with no explicit mask masks the ripple against the
     * composite of its content layers -- and nearly every call here passes a TRANSPARENT
     * GradientDrawable as that content, because the row underneath wants no fill of its own.
     * A transparent composite multiplies the ripple away completely: every settings row, every
     * permission row, every extension row and the back button on two screens did nothing
     * visible when pressed. Nobody reports a missing ripple; they just find the app
     * unresponsive and tap again.
     *
     * A radius of zero does not escape it either, which is worth stating because it looks like
     * it should: GradientDrawable reports OPAQUE only when its solid colour is opaque, and
     * TRANSPARENT never is.
     *
     * So the mask is built here, from the base's own corner radius, which means a rounded row
     * gets a rounded ripple and a square one gets a square ripple without any call site having
     * to say so.
     */
    static RippleDrawable tappable(Context context, Drawable base, boolean dark) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        if (base instanceof GradientDrawable) {
            mask.setCornerRadius(((GradientDrawable) base).getCornerRadius());
        }
        return new RippleDrawable(
                ColorStateList.valueOf(dark ? 0x28FFFFFF : 0x1C000000), base, mask);
    }

    /** The same colour at a chosen opacity, for tints, tonal fills and indicator pills. */
    static int alpha(int colour, int opacity) {
        return Color.argb(opacity, Color.red(colour), Color.green(colour), Color.blue(colour));
    }

    /**
     * The surface a floating element sits on: glass, lifted, and rounded on every corner.
     *
     * The difference from glass() is the shadow rather than the fill. A card is part of the
     * page and a floating bar is above it, and the only thing that says which is which is
     * whether light gets under the edge.
     */
    static GradientDrawable floatingGlass(Context context, boolean dark, float radiusDp) {
        GradientDrawable drawable = glass(context, dark, radiusDp);
        // A stronger edge than a card's, because this one has nothing behind it to sit against:
        // on a dark ground the hairline IS the boundary between the bar and the page.
        drawable.setStroke(Math.max(1, dp(context, 1)),
                dark ? Color.rgb(86, 86, 82) : Color.rgb(210, 206, 196));
        return drawable;
    }

    /**
     * Material 3's filled tonal icon button: a 40 dp container centred in a 48 dp touch target.
     *
     * The bare tinted glyph this replaces is what an owner reported as "the code-looking thing
     * in the top right corner". It was a real control with a real action behind it, and it read
     * as decoration, because nothing around it said it could be pressed. A tonal container says
     * it -- and it is what every Google app on the same phone puts an app-bar action inside.
     *
     * The container is inset rather than sized, so the thing a finger has to hit stays 48 dp
     * while the thing an eye sees is 40. An icon button that is visually 48 dp crowds a bar;
     * one whose TARGET is 40 dp is a control people miss.
     */
    static ImageView iconButton(Context context, boolean dark, int iconRes, int tint,
                                int container, CharSequence description,
                                View.OnClickListener onClick) {
        ImageView view = new ImageView(context);
        view.setImageResource(iconRes);
        view.setImageTintList(ColorStateList.valueOf(tint));
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int inset = dp(context, 4);
        int pad = inset + dp(context, 9);          // 40 - 2 x 9 = a 22 dp glyph
        view.setPadding(pad, pad, pad, pad);
        // The mask is what keeps the ripple inside the circle. Without one a RippleDrawable
        // fills its whole bounds, so a round button flashes a 48 dp square when it is pressed.
        view.setBackground(new RippleDrawable(
                ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x22000000),
                new InsetDrawable(fill(context, container, 999), inset),
                new InsetDrawable(fill(context, Color.WHITE, 999), inset)));
        view.setClickable(true);
        view.setFocusable(true);
        view.setContentDescription(description);
        view.setOnClickListener(onClick);
        return view;
    }

    /** A glass panel with the padding every card on every screen shares. */
    static LinearLayout card(Context context, boolean dark) {
        LinearLayout card = column(context);
        int pad = dp(context, 16);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(glass(context, dark, 20));
        // Elevation in light only. On a dark ground a shadow is invisible and the overdraw is
        // paid for anyway, which on a four-year-old phone is a frame the editor could have had.
        card.setElevation(dark ? 0 : dp(context, 1));
        return card;
    }

    // ------------------------------------------------------------------ controls

    /** A button that says exactly what it does, sized for a finger. */
    static TextView button(Context context, CharSequence label, boolean primary, boolean dark) {
        TextView view = bold(context, label, 15.5f,
                primary ? (dark ? DARK_BG : LIGHT_BG) : text(dark));
        view.setGravity(Gravity.CENTER);
        int padY = dp(context, 14);
        int padX = dp(context, 20);
        view.setPadding(padX, padY, padX, padY);
        view.setMinHeight(dp(context, TOUCH_TARGET_DP));
        view.setBackground(tappable(context,
                primary ? fill(context, text(dark), 14)
                        : outlined(context, card(dark), line(dark), 14), dark));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    /** The brand-coloured button, for the one action a screen exists to offer. */
    static TextView primaryButton(Context context, CharSequence label, boolean dark) {
        TextView view = bold(context, label, 15.5f, Brand.ON_BRAND);
        view.setGravity(Gravity.CENTER);
        int padY = dp(context, 15);
        int padX = dp(context, 22);
        view.setPadding(padX, padY, padX, padY);
        view.setMinHeight(dp(context, TOUCH_TARGET_DP));
        view.setBackground(tappable(context, metal(context, 14), true));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    /** A small capsule saying one word about state. Its colour is the state, not decoration. */
    static TextView pill(Context context, CharSequence label, int colour) {
        TextView view = bold(context, label, 11.5f, colour);
        view.setLetterSpacing(0.05f);
        int padX = dp(context, 9);
        int padY = dp(context, 4);
        view.setPadding(padX, padY, padX, padY);
        view.setBackground(outlined(context, alpha(colour, 28), alpha(colour, 90), 999));
        return view;
    }

    /**
     * One row: an icon, a title, a value under it, and a chevron when it leads somewhere.
     *
     * Every list in this app is made of these, so a settings row, a permission row and an
     * extension row are the same object with different words -- which is why they stay aligned
     * with each other without anyone maintaining three layouts.
     */
    static final class Row extends LinearLayout {
        final ImageView icon;
        final TextView title;
        final TextView value;
        final ImageView chevron;
        private final boolean dark;

        Row(Context context, boolean dark, int iconRes, CharSequence titleText,
            CharSequence valueText, boolean leadsSomewhere) {
            super(context);
            this.dark = dark;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int padX = dp(context, 14);
            int padY = dp(context, 13);
            setPadding(padX, padY, padX, padY);
            setMinimumHeight(dp(context, TOUCH_TARGET_DP + 8));

            icon = new ImageView(context);
            if (iconRes != 0) icon.setImageResource(iconRes);
            icon.setImageTintList(ColorStateList.valueOf(muted(dark)));
            int size = dp(context, 22);
            LayoutParams iconParams = new LayoutParams(size, size);
            iconParams.rightMargin = dp(context, 14);
            addView(icon, iconParams);
            if (iconRes == 0) icon.setVisibility(GONE);

            LinearLayout words = column(context);
            title = medium(context, titleText, 15f, text(dark));
            words.addView(title);
            value = text(context, valueText, 13f, muted(dark));
            value.setVisibility(valueText == null || valueText.length() == 0 ? GONE : VISIBLE);
            LayoutParams valueParams = new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            valueParams.topMargin = dp(context, 2);
            words.addView(value, valueParams);
            addView(words, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

            chevron = new ImageView(context);
            chevron.setImageResource(R.drawable.ic_chevron);
            chevron.setImageTintList(ColorStateList.valueOf(line(dark)));
            LayoutParams chevronParams = new LayoutParams(dp(context, 20), dp(context, 20));
            addView(chevron, chevronParams);
            chevron.setVisibility(leadsSomewhere ? VISIBLE : GONE);

            if (leadsSomewhere) {
                setBackground(tappable(context, fill(context, Color.TRANSPARENT, 14), dark));
                setClickable(true);
                setFocusable(true);
            }
        }

        void setValue(CharSequence words) {
            value.setText(words);
            value.setVisibility(words == null || words.length() == 0 ? GONE : VISIBLE);
        }

        /** Colours the icon to say something. Used only for states, never for decoration. */
        void setState(int colour) {
            icon.setImageTintList(ColorStateList.valueOf(colour));
        }

        void setMutedIcon() {
            icon.setImageTintList(ColorStateList.valueOf(muted(dark)));
        }
    }

    static Row row(Context context, boolean dark, int iconRes, CharSequence title,
                   CharSequence value, View.OnClickListener onClick) {
        Row row = new Row(context, dark, iconRes, title, value, onClick != null);
        if (onClick != null) row.setOnClickListener(onClick);
        return row;
    }

    /** A hairline between rows, inset past the icon so it separates words rather than boxes. */
    static View divider(Context context, boolean dark, boolean insetPastIcon) {
        View view = new View(context);
        view.setBackgroundColor(line(dark));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 0.7f)));
        if (insetPastIcon) params.leftMargin = dp(context, 50);
        view.setLayoutParams(params);
        return view;
    }

    // ------------------------------------------------------------------ layout

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

    /**
     * A scrolling page with the side gutter every screen shares, capped so a line of text never
     * runs the full width of a tablet.
     */
    static ScrollView page(Context context, LinearLayout content, boolean dark) {
        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(bg(dark));
        scroll.setFillViewport(true);
        int side = dp(context, 16);
        content.setPadding(side, dp(context, 8), side, dp(context, 28));
        LinearLayout centring = column(context);
        centring.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        content.setLayoutParams(params);
        // Past roughly 600dp a line of text is too long to track back to. On a phone this
        // never bites; on a tablet or a folded-open screen it is the difference between a
        // readable column and a page that runs wall to wall.
        content.setOnHierarchyChangeListener(null);
        final int maxPx = dp(context, CONTENT_MAX_DP);
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (content.getWidth() > maxPx && content.getLayoutParams().width != maxPx) {
                content.getLayoutParams().width = maxPx;
                content.requestLayout();
            }
        });
        centring.addView(content);
        scroll.addView(centring, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /** A back bar: one target, the title beside it, nothing else. */
    static LinearLayout topBar(Context context, boolean dark, CharSequence title,
                               View.OnClickListener onBack) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(bg(dark));
        int pad = dp(context, 8);
        bar.setPadding(pad, pad, dp(context, 16), pad);

        ImageView back = new ImageView(context);
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setImageTintList(ColorStateList.valueOf(text(dark)));
        int target = dp(context, TOUCH_TARGET_DP);
        back.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        back.setBackground(tappable(context, fill(context, Color.TRANSPARENT, 999), dark));
        back.setClickable(true);
        back.setFocusable(true);
        back.setContentDescription("Back");
        back.setOnClickListener(onBack);
        bar.addView(back, new LinearLayout.LayoutParams(target, target));

        TextView label = bold(context, title, 19f, text(dark));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.leftMargin = dp(context, 6);
        bar.addView(label, labelParams);
        return bar;
    }
}
