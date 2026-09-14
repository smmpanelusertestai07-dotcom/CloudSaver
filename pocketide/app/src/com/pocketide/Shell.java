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
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * The frame every top-level screen sits in: the app's name and mark along the top, its
 * destinations floating along the bottom, and the screen itself running underneath both.
 *
 * Why a bottom bar at all. The previous build was a stack of separate screens reached by
 * tapping rows on the home screen and backing out again -- which is how a settings menu works,
 * not how an app someone opens twenty times a day works. Every app this one sits beside on a
 * home screen puts its destinations along the bottom, within reach of a thumb, and shows you
 * where you are without your having to remember.
 *
 * The numbers are Material 3 Expressive's flexible navigation bar, not invented here:
 *
 *   64 dp   bar height, as a MINIMUM. The earlier 80 dp is Material 3's older navigation bar;
 *           Expressive tightened it, and a bar 16 dp taller than the spec is 16 dp taken from
 *           the content on every single screen. A minimum rather than a fixed height, because a
 *           fixed one is what once sliced the bottom off every label on a phone set to large
 *           text.
 *   24 dp   icon
 *   56 x 32 the active indicator, fully rounded
 *   12 sp   label, always shown -- an icon alone is a guessing game for anyone who has not
 *           used the app before, and this app's icons are not universal symbols
 *   3-5     destinations
 *
 * The bar FLOATS. It is a capsule inset from both edges and lifted off the gesture bar, and the
 * page scrolls underneath it rather than stopping at it -- which is the shape Telegram, Arc and
 * Google's own 2026 apps settled on, and the thing that makes it read as a piece of glass laid
 * over the screen instead of a slab nailed across the bottom of it. Every page reserves room
 * under the capsule (see padUnderBar) so its last row can always be scrolled out from under it;
 * a floating bar that forgets this leaves the last row unreachable, which is the one way a
 * floating bar is worse than a slab.
 *
 * The editor is deliberately NOT one of those destinations, and that is the spec rather than a
 * preference: Material is explicit that navigation bars belong to primary pages and toolbars to
 * the pages reached from them, and that the two must never share a screen. The editor is a
 * page reached from one -- full screen, its own toolbar along the bottom, no navigation bar at
 * all. It is reached from the button on Home and from the labelled button in this bar whenever
 * there is an editor to open, which is one tap from anywhere, the same as a tab would have been.
 *
 * The top bar carries the mark and the name because a person arriving from a notification, a
 * share sheet or a recents card should not have to work out which app they are in. It is the
 * small top app bar, 64 dp minimum, title at 19 sp with the tagline under it.
 */
final class Shell {

    /** Bar heights, as Material 3 specifies them. */
    static final int TOP_BAR_DP = 64;
    static final int NAV_BAR_DP = 64;
    private static final int ICON_DP = 24;
    private static final int INDICATOR_W_DP = 56;
    private static final int INDICATOR_H_DP = 32;

    /** What makes the bar float: the gutter beside it, the lift under it, its corner. */
    private static final int BAR_SIDE_DP = 14;
    private static final int BAR_LIFT_DP = 12;
    private static final float BAR_RADIUS_DP = NAV_BAR_DP / 2f;
    /** The air above the capsule, so a page's last row is never flush against its top edge. */
    private static final int BAR_TOP_GAP_DP = 8;

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
     * The content and the bar share one stage: the bar sits at the bottom of it, over the
     * content, and the content is given padding equal to the bar's full height (measured, not
     * assumed -- it grows with the font scale and with the gesture inset) so everything on the
     * page can be scrolled clear of it. A ScrollView is told not to clip to that padding, which
     * is what lets its rows keep drawing as they slide under the glass.
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
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View seam = new View(host);
        seam.setBackgroundColor(Ui.line(dark));
        top.addView(seam, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(host, 0.5f))));
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout stage = new FrameLayout(host);
        stage.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final View bottom = navBar(host, dark, tabs, selected, onTab);
        stage.addView(bottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Theme.fitBars(root, top, bottom);
        // Measured after layout, because the bar's height is not a constant: it grows with the
        // owner's text size and with the gesture bar's inset, and a page padded to a guess is
        // a page whose last row is either hidden or floating in space.
        bottom.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                padUnderBar(content, b - t));
        return root;
    }

    /**
     * Reserves the bar's height at the foot of the page, so nothing ends up beneath it.
     *
     * A scrolling page keeps drawing into that padding (clipToPadding false), which is what
     * makes rows visibly pass under the glass; anything else simply stops above the bar.
     */
    static void padUnderBar(View content, int barPx) {
        if (content == null || content.getPaddingBottom() == barPx) return;
        if (content instanceof ScrollView) ((ScrollView) content).setClipToPadding(false);
        content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                content.getPaddingRight(), barPx);
    }

    /**
     * The app's own bar: the mark, the name, what the app is for, and at most one action.
     *
     * The mark is the launcher icon's foreground layer on the brand tile, drawn at the same
     * proportions the launcher shows, so the thing at the top of the screen is recognisably the
     * thing that was tapped to get here.
     *
     * The line under the name is the app's tagline. It is here because it was nowhere an owner
     * could read it: it appeared for six-tenths of a second on the opening frame and then never
     * again, which is not a tagline, it is a flicker. Material's title-and-subtitle app bar is
     * the slot that exists for exactly this. The bar holds 64 dp as a MINIMUM and lets its
     * content decide the rest; a fixed height would cut the tagline off at a large text size
     * exactly as a fixed 64 dp once cut off every destination's label below.
     */
    static LinearLayout topBar(Context context, boolean dark, View action) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.bg(dark));
        bar.setMinimumHeight(Ui.dp(context, TOP_BAR_DP));
        int padY = Ui.dp(context, 6);
        bar.setPadding(Ui.dp(context, 16), padY, Ui.dp(context, 12), padY);

        bar.addView(mark(context, 36));

        LinearLayout words = Ui.column(context);
        TextView name = Ui.bold(context, "PocketIDE", 19f, Ui.text(dark));
        name.setSingleLine(true);
        words.addView(name);

        TextView line = Ui.text(context, context.getString(R.string.tagline_short), 12f,
                Ui.muted(dark));
        line.setSingleLine(true);
        // The short tagline is sized to fit the narrowest phone this app supports with the
        // Editor button present. Ellipsis is the backstop for a font scale past that.
        line.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(line);

        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wordsParams.leftMargin = Ui.dp(context, 11);
        wordsParams.rightMargin = Ui.dp(context, 8);
        bar.addView(words, wordsParams);

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

    /**
     * The bottom bar: a floating glass capsule over the page, not a slab painted across it.
     *
     * The capsule wraps its content and is held at 64 dp by a minimum height rather than a
     * fixed one, so a phone set to large text grows the bar instead of cutting the words. It is
     * inset from each side, lifted off the gesture bar, fully rounded, lit along its top edge
     * and slightly see-through, and clipped to its own outline so a ripple cannot square the
     * ends off. The holder it sits in paints nothing at all: what shows around and, faintly,
     * through the capsule is the page itself, which is the whole of the floating effect.
     *
     * Real frosted glass is not available to a View on Android -- there is no backdrop blur --
     * so this is what glass does to light rather than an imitation of blur: a top highlight, a
     * darker foot, a hairline edge, and a shadow underneath in the light theme where a shadow
     * can be seen.
     */
    private static View navBar(Activity host, boolean dark, List<Tab> tabs,
                               int selected, OnTab onTab) {
        LinearLayout capsule = new LinearLayout(host);
        capsule.setOrientation(LinearLayout.HORIZONTAL);
        capsule.setGravity(Gravity.CENTER_VERTICAL);
        capsule.setBackground(Ui.floatingGlass(host, dark, BAR_RADIUS_DP));
        capsule.setElevation(Ui.dp(host, dark ? 6 : 10));
        capsule.setMinimumHeight(Ui.dp(host, NAV_BAR_DP));
        int capsulePad = Ui.dp(host, 6);
        capsule.setPadding(capsulePad, 0, capsulePad, 0);
        // Clipped to its own outline, so a ripple that runs to the end of the first or last
        // destination stops at the curve instead of squaring the capsule off.
        capsule.setClipToOutline(true);

        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            capsule.addView(item(host, dark, tabs.get(i), i == selected,
                    v -> { if (index != selected) onTab.selected(index); }),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        FrameLayout holder = new FrameLayout(host);
        holder.setBackgroundColor(Color.TRANSPARENT);
        int side = Ui.dp(host, BAR_SIDE_DP);
        // The bottom is left at zero on purpose: Theme.fitBars writes the gesture bar's height
        // into it, and the capsule's own margin keeps it clear of that.
        holder.setPadding(side, Ui.dp(host, BAR_TOP_GAP_DP), side, 0);
        // A shadow is drawn outside the view that casts it. Clipped, the capsule's lift is
        // sliced off square at the gutter and the whole thing reads as pasted on.
        holder.setClipToPadding(false);
        holder.setClipChildren(false);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(host, BAR_LIFT_DP);
        holder.addView(capsule, params);
        return holder;
    }

    private static View item(Context context, boolean dark, Tab tab, boolean active,
                             View.OnClickListener onClick) {
        LinearLayout column = Ui.column(context);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        // 62 dp of content inside a 64 dp minimum: 6 above, a 32 dp indicator, 2, a 12 sp
        // label, 6 below. It used to come to 68 inside a fixed 64, which is what cut the
        // bottom off every label on the screen.
        column.setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 6));
        column.setClickable(true);
        column.setFocusable(true);
        column.setBackground(Ui.tappable(context, Ui.fill(context, Color.TRANSPARENT, 24), dark));
        column.setOnClickListener(onClick);
        column.setContentDescription(tab.description);
        // So a screen reader says "selected" for the destination that is showing, instead of
        // leaving four identical-sounding buttons and no way to tell which one you are on.
        column.setSelected(active);

        FrameLayout indicator = new FrameLayout(context);
        if (active) {
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(Ui.dp(context, INDICATOR_H_DP / 2f));
            // The indicator is the accent at low opacity rather than a solid fill: a solid one
            // fights the icon on top of it in dark mode, where the icon is already light.
            pill.setColor(Ui.alpha(Ui.accent(dark), dark ? 56 : 40));
            indicator.setBackground(pill);
        }

        ImageView icon = new ImageView(context);
        icon.setImageResource(tab.icon);
        // The active icon is the ON-container tone, not the accent. The accent on its own
        // indicator measures 3.28:1 in the light theme -- see Ui.onAccentContainer.
        icon.setImageTintList(ColorStateList.valueOf(
                active ? Ui.onAccentContainer(dark) : Ui.muted(dark)));
        int iconSize = Ui.dp(context, ICON_DP);
        FrameLayout.LayoutParams iconParams =
                new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER);
        indicator.addView(icon, iconParams);

        column.addView(indicator, new LinearLayout.LayoutParams(
                Ui.dp(context, INDICATOR_W_DP), Ui.dp(context, INDICATOR_H_DP)));

        // Material 3 gives the active label the plain text colour rather than the accent, and
        // the measurement agrees: the accent on the lower half of the light capsule is 3.97:1,
        // under the floor for the one word that says which screen you are on. Weight is what
        // marks it as selected, which is what the spec uses too.
        TextView label = active
                ? Ui.medium(context, tab.label, 12f, Ui.text(dark))
                : Ui.text(context, tab.label, 12f, Ui.muted(dark));
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        // Five labels on a 360 dp screen leave 72 dp each. Ellipsis rather than wrap, because a
        // label that wraps to two lines pushes the bar taller than the icons above it.
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = Ui.dp(context, 2);
        column.addView(label, labelParams);
        return column;
    }
}
