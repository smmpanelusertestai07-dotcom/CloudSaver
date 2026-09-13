package com.pocketagent.mobile;

import android.graphics.Color;

/**
 * PocketAgent's colours, in the one place code is allowed to name them.
 *
 * The values here are the same ones in branding/tokens.json, which tools/make_brand.py draws the
 * icon from and tests/brand-assets-test.py checks this file against. If the two ever disagree the
 * test fails rather than the app quietly shipping a mark in one teal and a screen in another.
 *
 * There are only three families, and the split between them is the whole design:
 *
 *   Brand      One deep teal. It appears on the launcher icon, the splash, the first-run screen
 *              and nowhere a person is meant to read a status from. Teal was chosen against the
 *              four agents this app hosts -- Claude is terracotta, Codex and Cursor are
 *              monochrome, Antigravity is blue-violet -- so the mark is never mistaken for one
 *              of theirs, and against the three status colours below, so brand and status can
 *              never be confused either.
 *
 *   Neutral    Warm, not grey. The interface itself is deliberately colourless: a button is ink
 *              on cream, or bone on ink. That restraint is what lets the third family mean
 *              something.
 *
 *   Semantic   Green, amber, red, and each one says exactly one thing: this was added, this
 *              needs you, this was removed or failed. Because nothing decorative is coloured,
 *              a coloured pixel in this app is always information.
 */
final class Brand {
    private Brand() {}

    // Brand -- the launcher icon's tile, top-left to bottom-right, and the flat tone between
    // them that a window background has to be (a splash background takes a colour, not a ramp).
    static final int TILE_TOP = Color.parseColor("#0E6E78");
    static final int TILE_BOTTOM = Color.parseColor("#073F4C");
    static final int TILE_FLAT = Color.parseColor("#0B5560");
    static final int ACCENT = Color.parseColor("#128F9C");
    static final int ACCENT_ON_DARK = Color.parseColor("#5BC6D2");
    /** The mark itself: warm bone, never pure white, so it sits with the neutrals. */
    static final int MARK = Color.parseColor("#F7F3EB");

    /** Readable body text on the brand tile, and the quieter line under it. */
    static final int ON_BRAND = MARK;
    static final int ON_BRAND_MUTED = Color.parseColor("#A9D4D8");
}
