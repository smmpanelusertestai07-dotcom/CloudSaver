package com.pocketide;

import android.graphics.Color;

/**
 * PocketIDE's colours, in the one place code is allowed to name them.
 *
 * These values are mirrored in three other files -- branding/tokens.json, res/values/colors.xml
 * and plan/make_icon.py -- and tests/brand_tokens.py fails the build if any of them disagree.
 * That gate exists because a mark drawn in one violet beside a screen painted in another is the
 * kind of fault nobody notices in review and everybody notices on a home screen.
 *
 * There are only three families, and the split between them is the whole design:
 *
 *   Brand      One deep violet, and the reason is the home screen rather than taste. Rendered
 *              beside the agents this app hosts, the row is Codex (a dark tile), Claude
 *              (terracotta), Antigravity (a multicolour arch) and VS Code (blue). Violet is the
 *              slot none of them occupy. Black -- the obvious choice for a developer tool -- is
 *              the one colour that would disappear between Codex and Cursor on the same screen.
 *              It appears on the launcher icon, the splash, the hero card, and nowhere a person
 *              is meant to read a status from.
 *
 *   Neutral    Warm, not grey. The interface is deliberately colourless: a button is ink on
 *              cream, or bone on ink. That restraint is what lets the third family mean
 *              something.
 *
 *   Semantic   Green, amber, red, and each says exactly one thing: this is running, this needs
 *              you, this failed. Because nothing decorative is coloured, a coloured pixel in
 *              this app is always information.
 */
final class Brand {
    private Brand() {}

    /** The launcher tile, top-left to bottom-right, and the flat tone a splash has to take. */
    static final int TILE_TOP = Color.parseColor("#7A3CD6");
    static final int TILE_BOTTOM = Color.parseColor("#33146F");
    static final int TILE_FLAT = Color.parseColor("#56289F");

    static final int ACCENT = Color.parseColor("#8B55E8");
    static final int ACCENT_ON_DARK = Color.parseColor("#B79BF5");

    /** The mark: warm bone, never pure white, so it sits with the neutrals. */
    static final int MARK = Color.parseColor("#F7F3EB");

    /** Readable body text on the brand tile, and the quieter line under it. */
    static final int ON_BRAND = MARK;
    static final int ON_BRAND_MUTED = Color.parseColor("#C3B2E8");

    static int accent(boolean dark) { return dark ? ACCENT_ON_DARK : ACCENT; }
}
