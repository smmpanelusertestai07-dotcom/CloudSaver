package com.pocketide;

import android.app.Activity;
import android.view.View;

/**
 * One destination's content, without an Activity of its own.
 *
 * The previous build gave every screen its own Activity, which is why it had no bottom bar:
 * a bar that belongs to five separate Activities is five separate bars, and switching between
 * them animates the whole window. Android's own answer to this is one host with swappable
 * content, and this is that content.
 *
 * A pane is told when it comes into view and when it leaves, because the things these screens
 * do -- polling free space every five seconds, listening for the workspace to change state,
 * reading /proc -- must stop the moment the pane is not on screen. A timer left running behind
 * a hidden pane is a battery complaint nobody can diagnose.
 */
interface Pane {

    /** The pane's own name, used for the bar and for restoring the last place after a restart. */
    String key();

    /** Builds the content. Called again when the theme or the window changes. */
    View build(Activity host);

    /** Called when this pane becomes the visible one. Start timers and receivers here. */
    void shown(Activity host);

    /** Called when it stops being visible, and before the host goes away. Stop them here. */
    void hidden(Activity host);

    /**
     * Whether coming back to the app should build this pane again.
     *
     * Settings draws its rows from permissions the owner may just have changed in the phone's
     * own Settings, so it is rebuilt. Home and Activity refresh themselves every few seconds
     * and are only told they are back; Agents keeps what was typed into its search box, which
     * a rebuild would throw away.
     */
    default boolean rebuildOnReturn() { return true; }
}
