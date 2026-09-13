package com.pocketagent.doors;

/**
 * The four agents, and the door each one actually opened.
 *
 * This class is the whole design in one file. PocketAgent does not draw an agent's interface --
 * it runs the agent and hands you the interface its own publisher ships. What differs between
 * the four is only how that interface is reached, and each of them decided that for themselves:
 *
 *   Door A, REMOTE_CONTROL  A headless daemon here, and the publisher's own web or phone app as
 *                           the screen. Google and Anthropic both call this "Remote Control".
 *                           Nothing but one process runs on the phone.
 *
 *   Door B, EXTENSION_HOST  A VS Code server here, with the publisher's own extension inside it.
 *                           The interface is their extension's, the editor and terminal around
 *                           it are real. No X server, no Electron.
 *
 *   Door C, DESKTOP_APP     The publisher's whole desktop application, drawn on an X display and
 *                           watched through the viewer. The heaviest door, and the only one some
 *                           publishers have opened.
 *
 * Where a field says a thing is unverified, it is unverified: it is documented by the publisher
 * and has not been run on a phone under PRoot. Probe records what actually happened, and the
 * home screen shows that instead of this guess once there is one.
 */
final class Doors {

    enum Door {
        REMOTE_CONTROL("Remote Control", "Publisher's own app, daemon here", 1),
        EXTENSION_HOST("Extension Host", "Publisher's own extension, editor here", 2),
        DESKTOP_APP("Desktop app", "Publisher's whole application, on a display here", 4);

        final String title;
        final String summary;
        /** How much of the phone it asks for, 1 to 4. Used to order and to warn. */
        final int weight;

        Door(String title, String summary, int weight) {
            this.title = title;
            this.summary = summary;
            this.weight = weight;
        }
    }

    static final class Agent {
        final String id;
        final String name;
        final Door door;
        /** What starts inside Ubuntu. Empty for a door that is not implemented yet. */
        final String start;
        /**
         * What signs in, when the publisher needs that done first and separately.
         *
         * Google's own instruction for a headless host is "run agy, complete the sign-in flow,
         * then exit", and only then start the daemon -- the daemon has no credentials of its
         * own. On a machine with no desktop the CLI prints an authorisation link and waits for
         * the code the browser gives back, so this command is run with its input still
         * connected and the app relays both halves. Empty where the publisher's own extension
         * handles sign-in inside its interface, which is the case for Door B.
         */
        final String login;
        /** Where the interface appears once it is running. */
        final String surface;
        /** What the publisher charges. Said plainly, because it decides whether you can use it. */
        final String cost;
        final boolean free;
        /** What is known to be missing, in the owner's words. Empty when nothing is. */
        final String limit;
        /** True when this has been run on a phone; false while it is only documented. */
        final boolean proven;

        Agent(String id, String name, Door door, String start, String login, String surface,
              String cost, boolean free, String limit, boolean proven) {
            this.id = id;
            this.name = name;
            this.door = door;
            this.start = start;
            this.login = login;
            this.surface = surface;
            this.cost = cost;
            this.free = free;
            this.limit = limit;
            this.proven = proven;
        }

        boolean implemented() {
            return !start.isEmpty();
        }

        boolean signsInSeparately() {
            return !login.isEmpty();
        }

        /**
         * True when the interface is the publisher's own site rather than a server on this phone.
         *
         * It decides where the interface is opened, and the reason is Google's: they refuse an
         * OAuth sign-in inside an embedded view, so a dashboard shown in this app's own window
         * would be permanently signed out. Their documentation says to open it in a browser and
         * add it to the home screen, so that is what this app does -- it hands the link to the
         * phone's real browser, where the session is already signed in and the notifications
         * their web app sends actually arrive. A loopback address is the other case: nothing
         * outside this phone can reach it and there is no account to sign into, so it is shown
         * in the window.
         */
        boolean opensInBrowser() {
            return surface.startsWith("https://");
        }
    }

    /** Ordered lightest door first, and within a door, free before paid. */
    /**
     * Three, and only three.
     *
     * Not a shortlist of favourites -- a list of everyone who publishes what this app needs. An
     * agent can only appear here if its maker ships three things: a coding agent built for
     * linux-arm64, an interface of their own that a phone can reach, and an update path they
     * control. Miss any one and there is nothing honest to put on the screen.
     *
     * Who that leaves out, and why, is written down in Reasons.java and shown in the app.
     */
    static final Agent[] ALL = {
            new Agent("antigravity", "Antigravity", Door.REMOTE_CONTROL,
                    "doors-antigravity.sh start",
                    "doors-antigravity.sh login",
                    "https://antigravity.google.com",
                    "Free tier", true,
                    "Google's own words for where a session runs: \"your desktop or server\". This "
                            + "phone is the server. Their dashboard shows conversations, tasks, plans "
                            + "and artifacts, and approves terminal commands and file writes.",
                    false),

            new Agent("claude", "Claude Code", Door.REMOTE_CONTROL,
                    "doors-claude.sh start",
                    "doors-claude.sh login",
                    "https://claude.ai/code",
                    "Claude Pro or Max, from $20 a month", false,
                    "Anthropic's own mobile app is the screen. Remote Control is confirmed working "
                            + "on a headless Linux host, which is what this workspace is. It needs a "
                            + "real terminal, and this app gives it one.",
                    false),

            new Agent("codex", "Codex", Door.EXTENSION_HOST,
                    "doors-codeserver.sh start codex",
                    "",
                    "http://127.0.0.1:8391/",
                    "ChatGPT Plus, Pro, Business, Edu or Enterprise", false,
                    "OpenAI's Remote Control needs a Mac to host the session -- Windows is listed as "
                            + "coming, Linux is not listed at all. So this is their VS Code extension "
                            + "instead, the same one their desktop editor runs, driving the same Codex "
                            + "engine. It is not the separate Codex application for macOS.",
                    false),
    };

    static Agent byId(String id) {
        for (Agent agent : ALL) if (agent.id.equals(id)) return agent;
        return null;
    }

    /** The script every door's start line lives in, copied into Ubuntu at setup. */
    static final String[] SCRIPTS = {"doors-bootstrap.sh", "doors-antigravity.sh",
            "doors-claude.sh", "doors-codeserver.sh"};

    private Doors() {}
}
