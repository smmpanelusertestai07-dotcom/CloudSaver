package com.pocketdesk;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Single source of truth for colours, spacing and the reusable rows of the app.
 *
 * Vocabulary, named once and used everywhere: the machine is "the Linux computer"; what it
 * shows is "the desktop"; the system on it is "Ubuntu 24.04"; the device in hand is "your
 * phone"; the four AI programs are "AI desktop apps". Calling one thing by four names on one
 * screen ("Linux", "the desktop", "your Linux screen", "the container") is how an app reads as
 * unfinished, so no screen invents a fifth.
 */
final class Ui {
    /**
     * The widest any page of the home screen gets. Every phone is narrower than this in either
     * orientation, so on a phone it does nothing; on a tablet or a foldable opened flat the
     * content centres itself instead of running a line of text across the whole glass.
     */
    static final int CONTENT_MAX_WIDTH_DP = 600;
    /** Nothing tappable is smaller than this: a constraint about fingers, not looks. */
    static final int TOUCH_TARGET_DP = 48;

    // Light surfaces
    static final int LIGHT_BG = Color.rgb(244, 246, 251);
    static final int LIGHT_CARD = Color.WHITE;
    static final int LIGHT_TEXT = Color.rgb(14, 23, 41);
    static final int LIGHT_MUTED = Color.rgb(90, 100, 120);
    static final int LIGHT_LINE = Color.rgb(226, 231, 240);
    static final int LIGHT_FIELD = Color.rgb(246, 248, 252);

    // Dark surfaces
    static final int DARK_BG = Color.rgb(8, 13, 26);
    static final int DARK_CARD = Color.rgb(16, 26, 46);
    static final int DARK_TEXT = Color.rgb(241, 245, 251);
    static final int DARK_MUTED = Color.rgb(154, 167, 189);
    static final int DARK_LINE = Color.rgb(35, 48, 74);
    static final int DARK_FIELD = Color.rgb(13, 21, 38);

    // Brand
    static final int PRIMARY = Color.rgb(23, 70, 196);
    static final int PRIMARY_DEEP = Color.rgb(16, 43, 107);
    static final int PRIMARY_ON_DARK = Color.rgb(122, 155, 255);
    static final int SUCCESS = Color.rgb(18, 145, 80);
    static final int WARNING = Color.rgb(184, 116, 0);
    static final int DANGER = Color.rgb(199, 54, 43);

    private Ui() {}

    static int text(boolean dark) { return dark ? DARK_TEXT : LIGHT_TEXT; }
    static int muted(boolean dark) { return dark ? DARK_MUTED : LIGHT_MUTED; }
    static int line(boolean dark) { return dark ? DARK_LINE : LIGHT_LINE; }
    static int surface(boolean dark) { return dark ? DARK_CARD : LIGHT_CARD; }
    static int field(boolean dark) { return dark ? DARK_FIELD : LIGHT_FIELD; }
    static int accent(boolean dark) { return dark ? PRIMARY_ON_DARK : PRIMARY; }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable background(int color, float radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    static GradientDrawable outlined(int fill, int stroke, float radiusDp, Context context) {
        GradientDrawable drawable = background(fill, radiusDp, context);
        drawable.setStroke(Math.max(1, dp(context, 1)), stroke);
        return drawable;
    }

    static GradientDrawable brandGradient(Context context, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(23, 60, 150), Color.rgb(16, 43, 107), Color.rgb(10, 24, 62)});
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    /** Wraps a background in a ripple so every tappable row gives touch feedback. */
    static Drawable tappable(Context context, Drawable content, boolean dark) {
        int ripple = dark ? Color.argb(60, 150, 180, 255) : Color.argb(38, 23, 70, 196);
        return new RippleDrawable(ColorStateList.valueOf(ripple), content, null);
    }

    static TextView text(Context context, CharSequence value, float sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.12f);
        return view;
    }

    static TextView title(Context context, CharSequence value, float sizeSp, int color) {
        TextView view = text(context, value, sizeSp, color);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    static TextView bold(Context context, CharSequence value, float sizeSp, int color) {
        TextView view = text(context, value, sizeSp, color);
        view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return view;
    }

    static TextView badge(Context context, String value, int foreground, int background) {
        TextView badge = title(context, value, 11.5f, foreground);
        badge.setGravity(Gravity.CENTER);
        badge.setAllCaps(true);
        badge.setLetterSpacing(0.06f);
        badge.setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5));
        badge.setBackground(background(background, 99, context));
        return badge;
    }

    /** Pass a tint of 0 for artwork that carries its own colours, such as an app's real logo. */
    static ImageView icon(Context context, int iconRes, int tint, int sizeDp) {
        ImageView view = new ImageView(context);
        view.setImageResource(iconRes);
        if (tint != 0) view.setImageTintList(ColorStateList.valueOf(tint));
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int size = dp(context, sizeDp);
        view.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return view;
    }

    static Button primaryButton(Context context, String label, int iconRes) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(15.5f);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 54));
        button.setPadding(dp(context, 18), 0, dp(context, 18), 0);
        button.setStateListAnimator(null);
        button.setBackground(tappable(context, background(PRIMARY, 16, context), true));
        if (iconRes != 0) setStartIcon(button, iconRes, Color.WHITE, context, 21);
        return button;
    }

    static Button secondaryButton(Context context, String label, boolean dark, int iconRes) {
        Button button = primaryButton(context, label, 0);
        int foreground = text(dark);
        button.setTextColor(foreground);
        button.setBackground(tappable(context,
                outlined(surface(dark), line(dark), 16, context), dark));
        if (iconRes != 0) setStartIcon(button, iconRes, accent(dark), context, 21);
        return button;
    }

    static TextView sectionTitle(Context context, String label, int iconRes, boolean dark) {
        TextView title = bold(context, label, 17, text(dark));
        setStartIcon(title, iconRes, accent(dark), context, 21);
        return title;
    }

    static void setStartIcon(TextView view, int iconRes, int tint, Context context, int sizeDp) {
        Drawable icon = context.getDrawable(iconRes);
        if (icon == null) return;
        icon = icon.mutate();
        icon.setTint(tint);
        int size = dp(context, sizeDp);
        icon.setBounds(0, 0, size, size);
        view.setCompoundDrawablesRelative(icon, null, null, null);
        view.setCompoundDrawablePadding(dp(context, 10));
    }

    static LinearLayout card(Context context, boolean dark) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));
        card.setBackground(outlined(surface(dark), line(dark), 20, context));
        card.setElevation(dark ? 0 : dp(context, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(context, 12));
        card.setLayoutParams(lp);
        return card;
    }

    /** A compact live metric: icon, short label, big value. */
    static final class Tile extends LinearLayout {
        private final TextView value;

        Tile(Context context, int iconRes, String label, boolean dark) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(dp(context, 4), dp(context, 11), dp(context, 4), dp(context, 9));
            setBackground(outlined(surface(dark), line(dark), 16, context));
            addView(icon(context, iconRes, accent(dark), 19));
            // The reading is what the eye should land on; the category names it underneath, small
            // and spaced, so four of these stay legible side by side on a phone.
            value = bold(context, "—", 17f, text(dark));
            value.setGravity(Gravity.CENTER);
            value.setMaxLines(1);
            value.setIncludeFontPadding(false);
            LayoutParams valueLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            valueLp.topMargin = dp(context, 7);
            addView(value, valueLp);
            TextView caption = text(context, label.toUpperCase(java.util.Locale.ROOT), 9.5f, muted(dark));
            caption.setGravity(Gravity.CENTER);
            caption.setMaxLines(1);
            caption.setLetterSpacing(0.05f);
            // TEMPERATURE is longer than the tile is wide; shrinking beats losing letters.
            caption.setAutoSizeTextTypeUniformWithConfiguration(7, 10, 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP);
            caption.setIncludeFontPadding(false);
            LayoutParams captionLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            captionLp.topMargin = dp(context, 5);
            addView(caption, captionLp);
        }

        void set(String text) { value.setText(text); }

        void set(String text, int colour) {
            value.setText(text);
            value.setTextColor(colour);
        }
    }

    /**
     * Icon + title + current value + an optional state pill + trailing icon.
     * The pill is what makes "on or off" readable at a glance on the permissions list.
     */
    static final class Row extends LinearLayout {
        private final TextView valueView;
        private final TextView statusView;
        private final boolean dark;

        Row(Context context, int iconRes, String title, String value, int trailingIcon,
            boolean dark, OnClickListener onClick) {
            this(context, iconRes, false, title, value, trailingIcon, dark, onClick);
        }

        Row(Context context, int iconRes, boolean fullColour, String title, String value,
            int trailingIcon, boolean dark, OnClickListener onClick) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(context, 56));
            setPadding(dp(context, 12), dp(context, 11), dp(context, 12), dp(context, 11));
            setBackground(tappable(context, background(field(dark), 14, context), dark));
            setClickable(onClick != null);
            setFocusable(onClick != null);
            if (onClick != null) setOnClickListener(onClick);

            addView(icon(context, iconRes, fullColour ? 0 : accent(dark), fullColour ? 26 : 22));

            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(VERTICAL);
            LayoutParams labelLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            labelLp.setMarginStart(dp(context, 12));
            labelLp.setMarginEnd(dp(context, 8));
            addView(labels, labelLp);
            labels.addView(title(context, title, 14.5f, text(dark)));
            valueView = text(context, value == null ? "" : value, 12.5f, muted(dark));
            valueView.setVisibility(value == null ? GONE : VISIBLE);
            labels.addView(valueView);

            this.dark = dark;
            statusView = badge(context, "", muted(dark), field(dark));
            statusView.setVisibility(GONE);
            LayoutParams statusLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            statusLp.setMarginEnd(dp(context, 8));
            addView(statusView, statusLp);

            if (trailingIcon != 0) {
                trailingView = icon(context, trailingIcon, muted(dark), 18);
                addView(trailingView);
            }
        }

        private ImageView trailingView;

        /** Turns the trailing chevron to point down while what it opens is showing. */
        void setExpanded(boolean expanded) {
            if (trailingView == null) return;
            trailingView.animate().rotation(expanded ? 90f : 0f).setDuration(150).start();
        }

        void setValue(String value) {
            valueView.setText(value);
            valueView.setVisibility(value == null || value.isEmpty() ? GONE : VISIBLE);
        }

        /** Shows a coloured ON / OFF style pill. Pass null to hide it again. */
        void setStatus(String label, int colour) {
            if (label == null || label.isEmpty()) {
                statusView.setVisibility(GONE);
                return;
            }
            statusView.setVisibility(VISIBLE);
            statusView.setText(label);
            statusView.setTextColor(colour);
            statusView.setBackground(background(tint(colour, dark), 99, getContext()));
        }
    }

    /** A low-opacity wash of a semantic colour, readable on either surface. */
    static int tint(int colour, boolean dark) {
        return Color.argb(dark ? 46 : 30, Color.red(colour), Color.green(colour), Color.blue(colour));
    }

    /** Icon + title + subtitle + platform switch, so toggles read like the rest of the list. */
    static final class Toggle extends LinearLayout {
        final Switch control;

        Toggle(Context context, int iconRes, String title, String subtitle, boolean checked, boolean dark) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(context, 56));
            setPadding(dp(context, 12), dp(context, 11), dp(context, 12), dp(context, 11));
            setBackground(background(field(dark), 14, context));

            addView(icon(context, iconRes, accent(dark), 22));

            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(VERTICAL);
            LayoutParams labelLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            labelLp.setMarginStart(dp(context, 12));
            labelLp.setMarginEnd(dp(context, 8));
            addView(labels, labelLp);
            labels.addView(title(context, title, 14.5f, text(dark)));
            if (subtitle != null) labels.addView(text(context, subtitle, 12.5f, muted(dark)));

            control = new Switch(context);
            control.setChecked(checked);
            addView(control);
            setOnClickListener(v -> control.toggle());
        }
    }

    /** A small heading over a group of settings rows: "Appearance", "Data and files". */
    static TextView groupLabel(Context context, String label, boolean dark) {
        TextView view = title(context, label, 13f, muted(dark));
        view.setPadding(dp(context, 4), dp(context, 10), dp(context, 4), dp(context, 6));
        view.setLetterSpacing(0.02f);
        return view;
    }

    /**
     * One tab of the bottom bar: icon over a short label, a pill behind the icon when it is
     * the current tab, and a small dot when something on that tab needs attention.
     */
    static final class NavItem extends LinearLayout {
        private final ImageView icon;
        private final TextView label;
        private final View dot;
        private final boolean dark;
        private final int iconRes;

        NavItem(Context context, int iconRes, String text, boolean dark) {
            super(context);
            this.dark = dark;
            this.iconRes = iconRes;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setMinimumHeight(dp(context, TOUCH_TARGET_DP));
            setPadding(0, dp(context, 8), 0, dp(context, 8));
            setClickable(true);
            setFocusable(true);
            setBackground(tappable(context, background(Color.TRANSPARENT, 16, context), dark));
            setContentDescription(text);

            android.widget.FrameLayout pill = new android.widget.FrameLayout(context);
            icon = Ui.icon(context, iconRes, muted(dark), 24);
            android.widget.FrameLayout.LayoutParams iconLp = new android.widget.FrameLayout.LayoutParams(
                    dp(context, 24), dp(context, 24), Gravity.CENTER);
            pill.addView(icon, iconLp);
            dot = new View(context);
            dot.setBackground(background(DANGER, 99, context));
            dot.setVisibility(GONE);
            android.widget.FrameLayout.LayoutParams dotLp = new android.widget.FrameLayout.LayoutParams(
                    dp(context, 8), dp(context, 8), Gravity.TOP | Gravity.END);
            dotLp.topMargin = dp(context, 4);
            dotLp.rightMargin = dp(context, 10);
            pill.addView(dot, dotLp);
            addView(pill, new LayoutParams(dp(context, 64), dp(context, 32)));

            label = title(context, text, 12f, muted(dark));
            label.setMaxLines(1);
            label.setGravity(Gravity.CENTER);
            LayoutParams labelLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            labelLp.topMargin = dp(context, 4);
            addView(label, labelLp);
            setActive(false);
        }

        void setActive(boolean active) {
            View pill = (View) icon.getParent();
            pill.setBackground(active ? background(tint(accent(dark), dark), 16, getContext()) : null);
            icon.setImageTintList(ColorStateList.valueOf(active ? accent(dark) : muted(dark)));
            label.setTextColor(active ? text(dark) : muted(dark));
            setSelected(active);
        }

        void setDot(boolean visible) {
            dot.setVisibility(visible ? VISIBLE : GONE);
        }
    }

    static void addSpace(LinearLayout parent, Context context, int dp) {
        View space = new View(context);
        parent.addView(space, new LinearLayout.LayoutParams(1, Ui.dp(context, dp)));
    }

    static LinearLayout.LayoutParams matchWrap(Context context, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, topDp);
        return lp;
    }
}
