package com.pocketagent.doors;

/**
 * Three agents, one way in.
 *
 * An earlier design offered three "doors" -- a remote-control daemon, an extension host, a
 * desktop application -- and let the owner pick. That was a menu, and a menu is a question
 * somebody has to answer before they can start working. Worse, the answer was never really
 * theirs: for each maker exactly one route is the best one their own publishing supports, so
 * offering the others was offering worse choices politely.
 *
 * So there is one workspace now. code-server runs on the phone and is the screen for all three.
 * Inside it, each agent is reached the single way its own maker supports best:
 *
 *   Anthropic   their VS Code extension, with Remote Control switched on at startup, so their
 *               phone app shows the same session without anyone touching a setting.
 *   OpenAI      their VS Code extension. Their Remote Control hosts only on macOS, so there is
 *               no second surface to offer and none is pretended.
 *   Google      their CLI in the workspace's own terminal, with Remote Control started beside
 *               it, so their dashboard shows the same session.
 *
 * The workspace gives every agent a file tree, a terminal, a diff view and git, which two of
 * the three would otherwise have no way to show on a phone at all.
 */
final class Doors {

    /**
     * The one address the workspace answers on.
     *
     * It is a constant rather than three identical strings on purpose: there is one workspace,
     * and writing it once is what stops a later change from quietly giving one agent a different
     * way in. The port is loopback only -- it is refused from anywhere but this phone.
     */
    static final String WORKSPACE = "http://127.0.0.1:8391/";

    static final class Agent {
        final String id;
        final String name;
        /** What starts inside Ubuntu. One script, one argument: there is only one way in. */
        final String start;
        /**
         * What signs in, when the maker needs that done first and separately.
         *
         * Google's own instruction for a headless host is "run agy, complete the sign-in flow,
         * then exit", and only then start the daemon. Empty where the maker's own extension
         * handles sign-in inside its own interface, which is both of the others.
         */
        final String login;
        /** The workspace, where the editor appears. The same address for all three. */
        final String surface;
        /**
         * The maker's own phone surface, when they publish one, and empty when they do not.
         *
         * This is not a second option to choose between -- it is the same session seen from a
         * phone, and it is switched on automatically. Empty for OpenAI, whose Remote Control
         * hosts only on macOS; nothing is offered there because nothing exists.
         */
        final String phone;
        /** What the phone surface is, in the owner's words, so a link is never a surprise. */
        final String phoneIs;
        /** What the maker charges. Said plainly, because it decides whether you can use it. */
        final String cost;
        final boolean free;
        /** What is known to be missing, in the owner's words. Empty when nothing is. */
        final String limit;
        /** True when this has been run on a phone; false while it is only documented. */
        final boolean proven;

        Agent(String id, String name, String start, String login, String surface,
              String phone, String phoneIs, String cost, boolean free, String limit,
              boolean proven) {
            this.id = id;
            this.name = name;
            this.start = start;
            this.login = login;
            this.surface = surface;
            this.phone = phone;
            this.phoneIs = phoneIs;
            this.cost = cost;
            this.free = free;
            this.limit = limit;
            this.proven = proven;
        }

        /**
         * True when this maker needs a sign-in run before anything starts.
         *
         * Only Google. The other two sign in inside their own panel in the editor, so asking the
         * app to run a sign-in command for them would be asking it to run nothing.
         */
        boolean signsInSeparately() {
            return !login.isEmpty();
        }

        /** True when this agent also appears on a surface the maker built for a phone. */
        boolean hasPhoneSurface() {
            return !phone.isEmpty();
        }

        /**
         * True when the maker's phone surface is a site rather than this phone's own server.
         *
         * It decides where that surface opens, and the reason is the makers': neither Google nor
         * Anthropic will complete a sign-in inside an embedded view, so a dashboard shown in
         * this app's own window would be permanently signed out. Their own instruction is to use
         * a browser, or their app.
         */
        boolean phoneOpensOutside() {
            return phone.startsWith("https://");
        }
    }

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
            new Agent("claude", "Claude Code",
                    "doors-workspace.sh start claude",
                    "",
                    WORKSPACE,
                    "https://claude.ai/code",
                    "Anthropic's own app. Open Claude on this phone, tap Code, and this session "
                            + "is in the list.",
                    "Claude Pro, Max, Team or Enterprise, from $20 a month", false,
                    "Remote Control is switched on for every session, so the same work is in the "
                            + "editor here and in Anthropic's app at the same time. An API key "
                            + "cannot do this -- Anthropic support it on subscriptions only. Two "
                            + "commands stay local only: /plugin and /resume.",
                    false),

            new Agent("codex", "Codex",
                    "doors-workspace.sh start codex",
                    "",
                    WORKSPACE,
                    "",
                    "",
                    "ChatGPT Plus, Pro, Business, Edu or Enterprise", false,
                    "OpenAI's Remote Control hosts the session on a Mac -- Windows is listed as "
                            + "coming and Linux is not listed at all -- so there is no phone "
                            + "surface to offer here, and none is pretended. What runs is their "
                            + "own VS Code extension, the same one their desktop editor loads, "
                            + "and not the Codex application for macOS.",
                    false),

            new Agent("antigravity", "Antigravity",
                    "doors-workspace.sh start antigravity",
                    "doors-workspace.sh login antigravity",
                    WORKSPACE,
                    "https://antigravity.google.com",
                    "Google's dashboard in your browser. Add it to the home screen and it sends "
                            + "notifications like an app; Google publish no separate app.",
                    "Free tier", true,
                    "Google's own words for where a session runs: \"your desktop or server\". This "
                            + "phone is the server. Their dashboard approves terminal commands and "
                            + "file writes, and the editor here gives the terminal and diff it has "
                            + "no other way to show. Their CLI keeps its sign-in in the system "
                            + "keyring, and a workspace has none, so Google may ask again.",
                    false),
    };

    static Agent byId(String id) {
        for (Agent agent : ALL) if (agent.id.equals(id)) return agent;
        return null;
    }

    /** Every script that is copied into Ubuntu at set-up. */
    static final String[] SCRIPTS = {"doors-bootstrap.sh", "doors-workspace.sh"};

    private Doors() {}
}
