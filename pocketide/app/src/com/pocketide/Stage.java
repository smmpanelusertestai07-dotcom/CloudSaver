package com.pocketide;

/**
 * The steps set-up goes through, named, sized, and in order.
 *
 * Set-up takes twenty minutes or more on mobile data, and for most of that nothing visible
 * happens: apt prints no progress per file, and unpacking a base image is thousands of small
 * writes. A screen that says "Setting up…" for twenty minutes is indistinguishable from a screen
 * that has hung, and closing the app during that silence is exactly what leaves dpkg half-applied
 * and makes the next attempt fail before it starts.
 *
 * So set-up is a list rather than a spinner. Each stage shows its own state, its own elapsed
 * time and its own share of the total, and the owner can see which one is moving. The weights
 * are rough by design -- they are there to make a bar move honestly, not to promise a deadline.
 */
enum Stage {
    CHECK("Checking this phone", "Space, network and architecture", 1),
    UBUNTU_DOWNLOAD("Downloading Ubuntu", "Ubuntu 24.04.5 LTS · ARM64 · from Canonical", 12),
    UBUNTU_UNPACK("Unpacking Ubuntu", "Thousands of small files", 10),
    UBUNTU_TOOLS("Installing tools", "git, python3, curl, build tools", 18),
    EDITOR("Installing the editor", "code-server — Visual Studio Code", 45),
    READY("Finishing", "First start of the editor", 14);

    final String title;
    final String detail;
    /** Rough share of the whole, used only to move a progress bar honestly. */
    final int weight;

    Stage(String title, String detail, int weight) {
        this.title = title;
        this.detail = detail;
        this.weight = weight;
    }

    static int totalWeight() {
        int total = 0;
        for (Stage stage : values()) total += stage.weight;
        return total;
    }

    /** Percentage complete once every stage up to and including this one has finished. */
    int percentAfter() {
        int done = 0;
        for (Stage stage : values()) {
            done += stage.weight;
            if (stage == this) break;
        }
        return Math.round(done * 100f / totalWeight());
    }

    int percentBefore() {
        int done = 0;
        for (Stage stage : values()) {
            if (stage == this) break;
            done += stage.weight;
        }
        return Math.round(done * 100f / totalWeight());
    }

    /** Elapsed time in the form the set-up screen shows: 0:42, 7:13, 1:04:09. */
    static String clock(long millis) {
        if (millis < 0) millis = 0;
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        if (hours > 0) return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest);
        return String.format(java.util.Locale.ROOT, "%d:%02d", minutes, rest);
    }
}
