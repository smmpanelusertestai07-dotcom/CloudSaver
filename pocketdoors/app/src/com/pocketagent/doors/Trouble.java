package com.pocketagent.doors;

/**
 * Turns what went wrong into something the owner can act on.
 *
 * A set-up that stops shows its last words, and those words come from apt, dpkg, curl and the
 * kernel. "E: dpkg was interrupted, you must manually run 'dpkg --configure -a' to correct the
 * problem." is a perfectly good sentence written for somebody at a keyboard on a computer they
 * administer. On a phone it is a dead end: there is no prompt to type that at, and the one
 * button on the screen does not do it.
 *
 * So the raw output stays -- it is the truth and it is what makes a report useful -- but above
 * it goes a sentence that says what happened, and one that says what to do about it. Nothing
 * here invents a cause: each entry matches a message this project has actually seen, and
 * anything unrecognised says so plainly rather than guessing.
 */
final class Trouble {

    /** What happened, and what to do, in the owner's words. */
    static final class Advice {
        final String what;
        final String next;
        /** What the button should say when this is the reason. */
        final String action;

        Advice(String what, String next, String action) {
            this.what = what;
            this.next = next;
            this.action = action;
        }
    }

    private static final Advice UNKNOWN = new Advice(
            "Set-up stopped before it finished.",
            "Its own last words are below. Opening this again carries on from where it got to, "
                    + "so trying once more is usually worth it.",
            "Try again");

    /**
     * The advice for one failure, read from everything the set-up said.
     *
     * The whole transcript rather than the last line: the line that names the cause is often
     * several above the one that stopped the run, and the last line is frequently only the
     * summary ("Node could not be installed").
     */
    static Advice read(String transcript) {
        String said = transcript == null ? "" : transcript.toLowerCase(java.util.Locale.US);

        if (said.contains("dpkg was interrupted")) {
            return new Advice(
                    "An install was cut off part way through, and the package system needs "
                            + "putting back in order before anything else can be installed.",
                    "This version repairs that by itself at the start of every run, so opening "
                            + "this again should get past it. It happens when the app is closed "
                            + "or the phone stops it while packages are being configured -- "
                            + "keeping the screen on and the phone plugged in avoids it.",
                    "Repair and continue");
        }
        if (said.contains("temporary failure resolving") || said.contains("resolver is empty")) {
            return new Advice(
                    "The workspace could not look up any address, so nothing could be downloaded.",
                    "This is the workspace having no resolver rather than the phone being "
                            + "offline. Close the app completely and open it again. If that does "
                            + "not help, switch once between mobile data and Wi-Fi.",
                    "Try again");
        }
        if (said.contains("no installation candidate") || said.contains("came back empty")) {
            return new Advice(
                    "Ubuntu's package list arrived empty for this phone's architecture.",
                    "That is almost always a connection that rewrote the download on the way "
                            + "through, which some mobile networks and public Wi-Fi do. Try again "
                            + "on a different connection.",
                    "Try again");
        }
        if (said.contains("no space left") || said.contains("enough room")
                || said.contains("free some space")) {
            return new Advice(
                    "The phone ran out of room part way through.",
                    "Free some space and open this again. What already downloaded is kept, so it "
                            + "carries on rather than starting over.",
                    "Try again");
        }
        // Node's own word for a native abort, and on a 3.9 GB phone running an Electron editor
        // it almost always means the memory ran out. The trace that came with it was a stack
        // through node:internal/modules/esm/worker -- the loader thread, dying while unpacking
        // a few hundred megabytes of extension.
        if (said.contains("aborted") || said.contains("out of memory")
                || said.contains("javascript heap")) {
            return new Advice(
                    "The editor ran out of memory while installing the extension.",
                    "This phone has under four gigabytes and the editor is a desktop "
                            + "application, so unpacking a few hundred megabytes is the heaviest "
                            + "moment of the whole set-up. Close every other app, then open this "
                            + "again — the download is kept, so only that last step repeats.",
                    "Try that step again");
        }
        // Electron's own dialog, which appears inside the editor rather than in this transcript,
        // but its wording reaches the log: the renderer process died. On this phone that is
        // memory or the software renderer, and both are helped by the same thing.
        if (said.contains("'crashed'") || said.contains("render process gone")
                || said.contains("window terminated")) {
            return new Advice(
                    "The editor's window crashed and it offered to start again.",
                    "This phone draws the editor in software, with no graphics chip to help, and "
                            + "has under four gigabytes to do it in. Closing other apps makes it "
                            + "much less likely. If it keeps happening, the workspace is still "
                            + "there — reopening starts a fresh window rather than installing "
                            + "anything again.",
                    "Open it again");
        }
        if (said.contains("arrived incomplete")) {
            return new Advice(
                    "A download stopped part way and what arrived did not match its stated size.",
                    "It was not installed. What arrived is kept, so opening this again resumes "
                            + "rather than starting from nothing.",
                    "Resume");
        }
        if (said.contains("checksum") || said.contains("did not match")) {
            return new Advice(
                    "Something downloaded did not match what its publisher signed.",
                    "It was discarded rather than installed. This is usually a download cut "
                            + "short or altered in transit; trying again on a different "
                            + "connection is the fix.",
                    "Try again");
        }
        if (said.contains("could not be downloaded") || said.contains("timed out")
                || said.contains("connection")) {
            return new Advice(
                    "A download did not finish.",
                    "What arrived is kept, so opening this again resumes rather than starting "
                            + "from nothing. A steadier connection helps.",
                    "Resume");
        }
        return UNKNOWN;
    }

    private Trouble() {}
}
