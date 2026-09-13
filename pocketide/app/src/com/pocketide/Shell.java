package com.pocketide;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The frame every top-level screen sits in: the app's name and mark along the top, its
 * destinations along the bottom, and the screen itself in between.
 *
 * Why a bottom bar at all. The previous build was a stack of separate screens reached by
 * tapping rows on the home screen and backing out again -- which is how a settings menu works,
 * not how an app someone opens twenty times a day works. Every app this one sits beside on a
 * home screen puts its destinations along the bottom, within reach of a thumb, and shows you
 * where you are without your having to remember. GitHub, Termius, Working Copy and Replit all
 * do the same thing, and so does every Google app on the phone.
 *
 * The numbers are Material 3 Expressive's flexible navigation bar, not invented here:
 *
 *   64 dp   bar height. The earlier 80 dp is Material 3's older navigation bar; Expressive
 *           tightened it, and a bar 16 dp taller than the spec is 16 dp taken from the content
 *           on every single screen.
 *   24 dp   icon
 *   56 x 32 the active indicator, fully rounded
 *   12 sp   label, always shown -- an icon alone is a guessing game for anyone who has not
 *           used the app before, and this app's icons are not universal symbols
 *   3-5     destinations
 *
 * The editor is deliberately NOT one of those destinations, and that is the spec rather than a
 * preference: Material is explicit that navigation bars belong to primary pages and toolbars to
 * the pages reached from them, and that the two must never share a screen. The editor is a
 * page reached from one -- full screen, its own toolbar along the bottom, no navigation bar at
 * all. It is reached from the button on Home and from the action in this bar whenever the
 * workspace is running, which is one tap from anywhere, the same as a tab would have been.
 *
 * The top bar carries the mark and the name because a person arriving from a notification, a
 * share sheet or a recents card should not have to work out which app they are in. It is the
 * small top app bar, 64 dp, title at 22 sp, which is the size Material gives a name rather
 * than a page heading.
 */
final class Shell {

    /** Bar heights, as Material 3 specifies them. */
    static final int TOP_BAR_DP = 64;
    static final int NAV_BAR_DP = 64;
    private static final int ICON_DP = 24;
    private static final int INDICATOR_W_DP = 56;
    private static final int INDICATOR_H_DP = 32;

    /** One destination on the bottom bar. */
    static final class Tab {
        final String label;
        final int icon;
        final String description;

        Tab(String label, int icon, String description) {
            this.label = label;
            this.icon = icon;
            this.description = description;
        }
    }

    interface OnTab { void selected(int index); }

    private Shell() {}

    /**
     * Builds the whole frame.
     *
     * The content view is given the space between the two bars and nothing else, so a screen
     * can never draw under the navigation bar and leave a row unreachable -- which is what
     * happens when a scrolling page is simply laid over a bar and the last item sits beneath it.
     */
    static View frame(Activity host, View content, List<Tab> tabs, int selected,
                      OnTab onTab, View action) {
        boolean dark = Ui.dark(host);
        LinearLayout root = Ui.column(host);
        root.setBackgroundColor(Ui.bg(dark));

        // The top bar lives inside a container that also paints the strip behind the status
        // bar, because on Android 15 the app IS that strip -- setStatusBarColor does nothing
        // and the window starts at y=0. The container takes the inset as padding, and the
        // hairline under it is what puts a boundary back between the phone and the app: in dark
        // the status bar, the bar and the page were one flat field with no edge anywhere.
        LinearLayout top = Ui.column(host);
        top.setBackgroundColor(Ui.bg(dark));
        top.addView(topBar(host, dark, action), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(host, TOP_BAR_DP)));
        View seam = new View(host);
        seam.setBackgroundColor(Ui.line(dark));
        top.addView(seam, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(host, 0.5f))));
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout body = new FrameLayout(host);
        body.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // WRAP_CONTENT, not a fixed height: the bar has to be able to grow by the height of the
        // gesture bar. Fixed, the last few pixels of every destination sat under it and could
        // not be tapped.
        View bottom = navBar(host, dark, tabs, selected, onTab);
        root.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Theme.fitBars(root, top, bottom);
        return root;
    }

    /**
     * The app's own bar: the mark, the name, and at most one action.
     *
     * The mark is the launcher icon's foreground layer on the brand tile, drawn at 28 dp inside
     * a 32 dp rounded square -- the same proportions the launcher shows, so the thing at the top
     * of the screen is recognisably the thing that was tapped to get here.
     */
    static LinearLayout topBar(Context context, boolean dark, View action) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.bg(dark));
        bar.setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 8), 0);

        bar.addView(mark(context, 32));

        TextView name = Ui.bold(context, "PocketIDE", 22f, Ui.text(dark));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.leftMargin = Ui.dp(context, 10);
        bar.addView(name, nameParams);

        if (action != null) bar.addView(action);
        return bar;
    }

    /**
     * The mark on its tile, at any size, for the top bar and anywhere else the app has to
     * identify itself. Built from the launcher's own layers rather than a separate drawing, so
     * it cannot drift away from the icon on the home screen.
     */
    static View mark(Context context, int sizeDp) {
        FrameLayout tile = new FrameLayout(context);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Brand.TILE_TOP, Brand.TILE_BOTTOM});
        background.setCornerRadius(Ui.dp(context, sizeDp * 0.28f));
        tile.setBackground(background);

        ImageView glyph = new ImageView(context);
        glyph.setImageResource(R.mipmap.ic_launcher_foreground);
        // The foreground layer is drawn on the 108 dp adaptive canvas, of which only the
        // middle 72 dp is ever shown. Scaling it up by 108/72 puts the mark at the size a
        // launcher displays rather than leaving it marooned in the middle of its bleed.
        glyph.setScaleX(108f / 72f);
        glyph.setScaleY(108f / 72f);
        tile.addView(glyph, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int size = Ui.dp(context, sizeDp);
        tile.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return tile;
    }

    /** The bottom bar. Every item is a 48 dp target whatever the label does. */
    private static LinearLayout navBar(Activity host, boolean dark, List<Tab> tabs,
                                       int selected, OnTab onTab) {
        LinearLayout bar = new LinearLayout(host);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Ui.card(dark));
        bar.setGravity(Gravity.CENTER_VERTICAL);

        View topLine = new View(host);
        topLine.setBackgroundColor(Ui.line(dark));

        LinearLayout wrapper = Ui.column(host);
        wrapper.addView(topLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(host, 0.5f))));
        wrapper.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(host, NAV_BAR_DP)));
        wrapper.setBackgroundColor(Ui.card(dark));

        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            bar.addView(item(host, dark, tabs.get(i), i == selected,
                    v -> { if (index != selected) onTab.selected(index); }),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        return wrapper;
    }

    private static View item(Context context, boolean dark, Tab tab, boolean active,
                             View.OnClickListener onClick) {
        LinearLayout column = Ui.column(context);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        // 64 dp total: 8 above, a 32 dp indicator, 4, a 12 sp label, 8 below.
        column.setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8));
        column.setClickable(true);
        column.setFocusable(true);
        column.setBackground(Ui.tappable(context, Ui.fill(context, Color.TRANSPARENT, 0), dark));
        column.setOnClickListener(onClick);
        column.setContentDescription(tab.description);

        FrameLayout indicator = new FrameLayout(context);
        if (active) {
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(Ui.dp(context, INDICATOR_H_DP / 2f));
            // The indicator is the accent at low opacity rather than a solid fill: a solid one
            // fights the icon on top of it in dark mode, where the icon is already light.
            pill.setColor(withAlpha(Ui.accent(dark), dark ? 56 : 40));
            indicator.setBackground(pill);
        }

        ImageView icon = new ImageView(context);
        icon.setImageResource(tab.icon);
        icon.setImageTintList(ColorStateList.valueOf(
                active ? Ui.accent(dark) : Ui.muted(dark)));
        int iconSize = Ui.dp(context, ICON_DP);
        FrameLayout.LayoutParams iconParams =
                new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER);
        indicator.addView(icon, iconParams);

        column.addView(indicator, new LinearLayout.LayoutParams(
                Ui.dp(context, INDICATOR_W_DP), Ui.dp(context, INDICATOR_H_DP)));

        TextView label = active
                ? Ui.medium(context, tab.label, 12f, Ui.accent(dark))
                : Ui.text(context, tab.label, 12f, Ui.muted(dark));
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        // Five labels on a 360 dp screen leave 72 dp each. Ellipsis rather than wrap, because a
        // label that wraps to two lines pushes the bar taller than the icons above it.
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = Ui.dp(context, 4);
        column.addView(label, labelParams);
        return column;
    }

    private static int withAlpha(int colour, int alpha) {
        return Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour));
    }
}
