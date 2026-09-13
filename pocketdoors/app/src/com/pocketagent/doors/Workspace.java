package com.pocketagent.doors;

/**
 * What the viewer asks about the thing it is drawing.
 *
 * The viewer came from the other app in this repository, where it watched a whole Linux desktop
 * and asked a service about it by name. It is left calling the same four questions here, through
 * this, rather than edited: it is 2,134 lines that already work on this phone, and every line of
 * it changed here is a line that has to be re-proved. What differs is only the answers -- there
 * is one editor to watch, not a desktop full of programs.
 */
final class Workspace {

    /** True once the editor has answered and is still answering. */
    static boolean isDesktopRunning() {
        return DoorService.runningAgent() != null && DoorService.isReady();
    }

    /** True while the workspace is being installed or started and has not answered yet. */
    static boolean isDesktopStarting() {
        return DoorService.runningAgent() != null && !DoorService.isReady();
    }

    /**
     * True once there is something worth showing.
     *
     * In the other app this waited for a file manager to paint a wallpaper, because the display
     * answered a minute before anything was drawn on it and that minute looked like a black
     * rectangle. Here the script does not say READY until the editor has been alive for several
     * seconds, so answering is the same event.
     */
    static boolean desktopDrawn() {
        return DoorService.isReady();
    }

    /** The last line the workspace said, so a long wait says what it is waiting on. */
    static String startupPhase() {
        return DoorService.lastLine();
    }

    private Workspace() {}
}
