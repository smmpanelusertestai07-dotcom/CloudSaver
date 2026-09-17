package com.pocketide;

import java.util.Locale;

/**
 * Turns what actually went wrong into something a person can act on.
 *
 * The owner of this app has no computer. Showing them "dpkg: error: dpkg frontend lock was
 * locked by another process" and leaving is not reporting a failure, it is abandoning someone
 * in front of a wall of text. Every entry below is a failure this project has actually seen on
 * a real phone, paired with the one sentence that tells the owner what to do about it.
 *
 * The raw output is never thrown away -- Help keeps it, and the button that shows it says so.
 * This only decides what is said first.
 */
final class Trouble {

    /** Plain advice for a raw failure, or null when there is nothing useful to add. */
    static String advice(String raw) {
        if (raw == null) return null;
        String text = raw.toLowerCase(Locale.ROOT);

        if (text.contains("dpkg was interrupted") || text.contains("frontend lock")) {
            return "A previous set-up was stopped part way through. Tap Try again — it repairs "
                    + "the half-finished step first, then carries on. Keep the app open this "
                    + "time; closing it mid-step is what leaves this behind.";
        }
        if (text.contains("temporary failure resolving") || text.contains("could not resolve")) {
            return "Linux could not look up the download servers. Check the phone is "
                    + "online, then tap Try again. Switching between Wi-Fi and mobile data "
                    + "mid-download causes this most often.";
        }
        if (text.contains("no space left") || text.contains("enospc")) {
            return "The phone ran out of space. Free some up, then tap Try again — everything "
                    + "downloaded so far is kept, so it resumes rather than starting over.";
        }
        if (text.contains("no installation candidate") || text.contains("unable to locate package")) {
            return "A package was not available from Ubuntu's servers. Tap Try again; this is "
                    + "usually a mirror that was briefly out of step.";
        }
        if (text.contains("checksum") || text.contains("did not match")) {
            return "A download arrived damaged and was refused rather than used. Tap Try again "
                    + "to fetch it cleanly.";
        }
        if (text.contains("killed") || text.contains("out of memory") || text.contains("oom")) {
            return "The phone ran out of memory during this step. Close other apps and tap Try "
                    + "again. Plugging the phone in also helps, because Android is less "
                    + "aggressive about reclaiming memory while charging.";
        }
        if (text.contains("arrived incomplete") || text.contains("unexpected end")) {
            return "The download was cut off. Tap Try again — it continues from where it "
                    + "stopped instead of starting over.";
        }
        if (text.contains("mobile data limit") || text.contains("waiting for wi-fi")) {
            return null; // DataBudget already said it in the owner's own terms.
        }
        if (text.contains("connection refused") || text.contains("econnrefused")) {
            return "The editor was not answering yet. Tap Try again; if it keeps happening, "
                    + "Stop Linux from the notification and open it once more.";
        }
        if (text.contains("not 64-bit") || text.contains("arm64")) {
            return "Nothing can be done about this one on this phone — the software simply is "
                    + "not published for its processor.";
        }
        return null;
    }

    private Trouble() {}
}
