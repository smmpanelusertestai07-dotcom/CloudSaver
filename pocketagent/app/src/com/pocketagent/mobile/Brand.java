package com.pocketagent.mobile;

import android.graphics.Color;

/**
 * PocketAgent's colours, in the one place code is allowed to name them.
 *
 * The values here are the same ones in branding/tokens.json, which tools/make_brand.py draws the
 * icon from and tests/brand-assets-test.py checks this file against. If the two ever disagree the
 * test fails rather than the app quietly shipping a mark in one violet and a screen in another.
 *
 * There are only three families, and the split between them is the whole design:
 *
 *   Brand      One deep violet. It appears on the launcher icon, the splash, the first-run
 *              screen and nowhere a person is meant to read a status from. The colour was
 *              settled by rendering the mark beside the four agents this app hosts, at launcher
 *              size, and looking at the row: Codex and Cursor are both dark tiles, Claude is
 *              terracotta, and Antigravity's arch is a multicolour rainbow. Violet is the slot
 *              nothing else occupies -- and black, the obvious choice for a developer tool, is
 *              the one colour that would disappear between Codex and Cursor on the same home
 *              screen. It is also far from the three status colours below, so brand and status
 *              cannot be confused.
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
    static final int TILE_TOP = Color.parseColor("#23232C");
    static final int TILE_BOTTOM = Color.parseColor("#121217");
    static final int TILE_FLAT = Color.parseColor("#1A1A21");
    static final int ACCENT = Color.parseColor("#8B7BFF");
    static final int ACCENT_ON_DARK = Color.parseColor("#B79BF5");
    /** The brackets: warm bone, never pure white, so they sit with the neutrals. */
    static final int MARK = Color.parseColor("#F7F3EB");
    /** The spark between them, and the only coloured thing in the mark. */
    static final int SPARK = Color.parseColor("#8B7BFF");

    /** Readable body text on the brand tile, and the quieter line under it. */
    static final int ON_BRAND = MARK;
    static final int ON_BRAND_MUTED = Color.parseColor("#A9A4C4");
}
