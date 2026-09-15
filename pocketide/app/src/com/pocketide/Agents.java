package com.pocketide;

import java.util.Arrays;
import java.util.List;

/**
 * The three coding agents this app sets up in one tap, and every fact shown about them.
 *
 * These are not the only agents the app can run -- the Extensions screen opens the whole Open
 * VSX registry -- they are the three that are pre-set, and the test for being here is narrow and
 * checkable: the company publishes its own agentic coding extension, under its own verified
 * namespace, and keeps it current.
 *
 * Every identifier, platform and engine below was read from the Open VSX API on 15 September
 * 2026 and is re-checked by tests/agent_facts.py against the live registry, which fails the
 * build rather than let the app quietly claim a namespace that is no longer verified. The
 * sizes are from the same day and are shown as "about": a publisher ships a new build every
 * week or two, so the exact figure is read from the download itself the moment it starts
 * (AgentsPane.install shows the registry's Content-Length), and the number here only has to
 * be near enough to decide yes or not today. The gate exists because an earlier build of this
 * project told an owner a download was 700 MB when it was 143, and because the same project
 * once searched for Google's extension, did not find it under 507 community results, and told
 * the owner it did not exist. It does. It is the first row here.
 */
final class Agents {

    /**
     * One agent, as the app knows it.
     *
     * @param id          the Open VSX identifier, exactly as published
     * @param name        what the publisher calls it
     * @param publisher   whose extension it is
     * @param platform    the Open VSX target platform to install
     * @param sizeBytes   the .vsix download, so the screen can say it before spending data
     * @param engine      the minimum VS Code version, checked against what code-server bundles
     * @param plan        what it costs, in the publisher's own words
     * @param free        true only where the publisher states a free tier
     */
    static final class Agent {
        final String id;
        final String name;
        final String publisher;
        final String platform;
        final long sizeBytes;
        final String engine;
        final String plan;
        final boolean free;
        final String summary;

        Agent(String id, String name, String publisher, String platform, long sizeBytes,
              String engine, String plan, boolean free, String summary) {
            this.id = id;
            this.name = name;
            this.publisher = publisher;
            this.platform = platform;
            this.sizeBytes = sizeBytes;
            this.engine = engine;
            this.plan = plan;
            this.free = free;
            this.summary = summary;
        }

        String namespace() { return id.substring(0, id.indexOf('.')); }
        String shortName() { return id.substring(id.indexOf('.') + 1); }
    }

    /**
     * Ordered by what an owner with no subscription can actually use today: Antigravity has a
     * real free tier, the other two do not.
     */
    static final List<Agent> ALL = Arrays.asList(
            new Agent(
                    "Google.google-antigravity", "Antigravity", "Google",
                    "universal", 3_725_666L, "^1.80.0",
                    "Free tier available · Google AI Pro and Ultra for higher limits", true,
                    "Google's agent-first development platform. Plans before it edits, runs "
                            + "subagents in its own sandboxes, shows changes as inline diffs, and "
                            + "connects tools through MCP."),
            new Agent(
                    "Anthropic.claude-code", "Claude Code", "Anthropic",
                    "linux-arm64", 105_000_000L, "^1.94.0",
                    "Pro, Max, Team or Enterprise · or pay-as-you-go", false,
                    "Anthropic's coding agent inside the editor, with the same tools and agent "
                            + "loop as Claude Code in a terminal."),
            new Agent(
                    "openai.chatgpt", "Codex", "OpenAI",
                    "linux-arm64", 242_000_000L, "^1.96.2",
                    "Included in ChatGPT Plus, Pro, Business, Edu and Enterprise", false,
                    "OpenAI's coding agent, working in the editor alongside the rest of Codex.")
    );

    private Agents() {}

    /**
     * Whether a publisher is the company whose model the extension talks to.
     *
     * Open VSX verifies that a publisher name has a real owner, which is the check that keeps
     * counterfeits out -- but a verified publisher is not the same thing as the company itself.
     * A perfectly honest third party can publish a perfectly verified Claude client. This says
     * which rows are the company's own, so the search results can say "official" where it is
     * true and only "verified" where that is all that is known.
     */
    static boolean official(String publisherName) {
        if (publisherName == null) return false;
        for (Agent agent : ALL) {
            if (agent.namespace().equalsIgnoreCase(publisherName)) return true;
        }
        return false;
    }

    static Agent byId(String id) {
        for (Agent agent : ALL) if (agent.id.equals(id)) return agent;
        return null;
    }

    /**
     * Why only these three are pre-set, in the words the Help screen uses.
     *
     * Kept next to the list rather than in the screen, so the claim and the thing it describes
     * cannot be edited apart from each other.
     */
    static final String WHY_THESE_THREE =
            "Three companies pass the same four tests: they train their own frontier model "
                    + "family, they ship their own first-party agentic coding tool, they own "
                    + "frontier-scale compute, and they publish a frontier-safety policy. "
                    + "Independent 2026 trackers place Anthropic, Google DeepMind and OpenAI "
                    + "level with each other on agentic coding and ahead of the rest.\n\n"
                    + "They are pre-set, not a fence. Any extension on Open VSX can be "
                    + "installed from the Extensions screen. When a fourth company publishes a "
                    + "first-party coding agent under a verified namespace, it belongs here too "
                    + "— and you will be able to install it the day it appears, without waiting "
                    + "for this app to be updated.";
}
