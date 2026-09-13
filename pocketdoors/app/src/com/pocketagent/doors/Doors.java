package com.pocketagent.doors;

/**
 * Three agents, one editor, and nowhere else to go.
 *
 * Every earlier design had a different shape per maker: an extension for two of them and a
 * command line for the third, and a second place to see the same session for two of them and
 * nothing for the other. Three shapes wearing one name, and two of them were a command line or
 * a web page rather than the interface its maker actually builds.
 *
 * There is one shape now, and it is not a preference -- it falls out of what the three publish
 * for linux-arm64. Google's Antigravity IDE is a real desktop application, published for arm64
 * in their own signed apt repository, and one line in the product.json of the package they ship
 * decides everything else:
 *
 *     "extensionsGallery": { "serviceUrl": "https://open-vsx.org/vscode/gallery" }
 *
 * Antigravity's own marketplace is Open VSX, which is exactly where Anthropic and OpenAI publish
 * their own extensions for arm64. So the other two makers' interfaces install into Google's
 * interface, and every agent is reached the way its own maker built it, in one window.
 *
 * What is deliberately gone: the Claude phone app and Google's web dashboard. Both showed the
 * same session from somewhere else, and "somewhere else" is an extra thing to learn, decide
 * about and be disappointed by. One editor. Nothing beside it.
 */
final class Doors {

    static final class Agent {
        final String id;
        final String name;
        /** What starts inside Ubuntu. One script, one argument: there is only one way in. */
        final String start;
        /**
         * The maker's own extension on Open VSX, or empty when the maker *is* the editor.
         *
         * Empty for Google alone, and not because anything is missing: Antigravity is the
         * application the other two install into. Writing an identifier here for them would be
         * inventing an extension Google does not publish.
         */
        final String extension;
        /** What the maker charges. Said plainly, because it decides whether you can use it. */
        final String cost;
        final boolean free;
        /** What is known to be missing or awkward, in the owner's words. Empty when nothing is. */
        final String limit;
        /** True when this has been run on a phone; false while it is only documented. */
        final boolean proven;

        Agent(String id, String name, String start, String extension,
              String cost, boolean free, String limit, boolean proven) {
            this.id = id;
            this.name = name;
            this.start = start;
            this.extension = extension;
            this.cost = cost;
            this.free = free;
            this.limit = limit;
            this.proven = proven;
        }

        /** True when this agent arrives as an extension rather than as the editor itself. */
        boolean isExtension() {
            return !extension.isEmpty();
        }
    }

    /**
     * Three, and only three.
     *
     * Not a shortlist of favourites -- a list of everyone who publishes what this app needs. An
     * agent can only appear here if its maker ships three things: a coding agent built for
     * linux-arm64, an interface of their own that this editor can show, and an update path they
     * control. Miss any one and there is nothing honest to put on the screen.
     *
     * Who that leaves out, and why, is written down in Reasons.java and shown in the app.
     */
    static final Agent[] ALL = {
            new Agent("claude", "Claude Code",
                    "doors-workspace.sh start claude",
                    "Anthropic.claude-code",
                    "Claude Pro, Max, Team or Enterprise, from $20 a month", false,
                    "Anthropic's own extension, the same one their desktop editor loads. It "
                            + "brings plan review, inline diffs, @-mentions of exact line ranges, "
                            + "subagents, MCP servers and its own model picker. It updates itself "
                            + "from Open VSX. An API key will not do -- they support subscriptions.",
                    false),

            new Agent("codex", "Codex",
                    "doors-workspace.sh start codex",
                    "openai.chatgpt",
                    "ChatGPT Plus, Pro, Business, Edu or Enterprise", false,
                    "OpenAI's own extension, the same one their desktop editor loads -- not the "
                            + "Codex application for macOS, which is a different product. Open "
                            + "files and selections go into the prompt on their own, edits are "
                            + "reviewed in place, and longer work can be handed to Codex on the "
                            + "web. It updates itself from Open VSX.",
                    false),

            new Agent("antigravity", "Antigravity",
                    "doors-workspace.sh start antigravity",
                    "",
                    "Free tier", true,
                    "Google's own editor, which is what the other two run inside. It is installed "
                            + "from Google's own signed package repository and updated by the same "
                            + "one, so it improves without this app being rebuilt. Its agent is "
                            + "built in: planning mode, a diff viewer, multi-agent reasoning and "
                            + "per-tool permissions, with file access outside the workspace off "
                            + "until you turn it on.",
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
