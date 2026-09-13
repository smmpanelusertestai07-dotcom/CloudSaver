package com.pocketagent.doors;

/**
 * Why these three, and what a fourth would have to publish.
 *
 * This exists because "only three agents" looks like laziness unless the reason is on the screen.
 * It is not a shortlist of favourites and it is not a judgement about whose models are better.
 * It is the list of makers who publish everything this app needs, and a plain statement of what
 * the others have not published yet.
 *
 * Every figure below was checked against a primary source before it was written down. Where a
 * thing was read from a publisher's own words, the words are quoted. Nothing here is an estimate.
 */
final class Reasons {

    /** One maker, and the three things that put them on the list. */
    static final class Lab {
        final String name;
        final String lab;
        /** What they own that cannot be bought in a hurry. */
        final String compute;
        /** How many people they already reach. */
        final String reach;
        /** The agent this app actually runs, and how it is reached here. */
        final String agent;

        Lab(String name, String lab, String compute, String reach, String agent) {
            this.name = name;
            this.lab = lab;
            this.compute = compute;
            this.reach = reach;
            this.agent = agent;
        }
    }

    static final Lab[] LABS = {
            new Lab("Google", "DeepMind",
                    "The only one of the three that makes its own chips. TPUs, and $175–185 billion "
                            + "of infrastructure spending planned for 2026.",
                    "Search, Android, Chrome and YouTube. Distribution on a scale nobody else in "
                            + "this field has.",
                    "Antigravity. Its Remote Control runs the session on, in Google's own words, "
                            + "\"your desktop or server\" — and this phone is the server."),

            new Lab("OpenAI", "OpenAI",
                    "No chips of its own; it rents. But it creates enough demand that the companies "
                            + "building the data centres cite it when they justify their own spending.",
                    "ChatGPT. For most people, it is what the words \"AI app\" mean.",
                    "Codex, through OpenAI's own VS Code extension. Their Remote Control needs a Mac "
                            + "to host the session — Windows is listed as coming, Linux is not listed."),

            new Lab("Anthropic", "Anthropic",
                    "No chips of its own either, but it is the named tenant behind three of the five "
                            + "flagship data centre projects now being built.",
                    "Smaller than ChatGPT with the public. Larger than anyone with developers: "
                            + "Claude Code is the most used coding agent at work, at 39%.",
                    "Claude Code, through Anthropic's own phone app. Remote Control is confirmed "
                            + "working on a headless Linux host, which is what this workspace is."),
    };

    /**
     * The bar. Not opinion -- these are the three things without which there is nothing to build.
     */
    static final String[] BAR = {
            "A coding agent built for linux-arm64, because that is what a phone is.",
            "An interface of their own that a phone can reach — their app, their web dashboard, "
                    + "or an extension on Open VSX.",
            "An update path they control, so the agent keeps improving without this app being "
                    + "rewritten.",
    };

    /** Who does not clear it today, and the exact thing that is missing. */
    static final class Missing {
        final String name;
        final String has;
        final String lacks;

        Missing(String name, String has, String lacks) {
            this.name = name;
            this.has = has;
            this.lacks = lacks;
        }
    }

    static final Missing[] NOT_HERE = {
            new Missing("Cursor",
                    "Its agent runs on linux-arm64 and speaks an open protocol.",
                    "No extension on Open VSX, and its remote sessions run on Cursor's own cloud "
                            + "machines rather than this phone. Neither route ends here."),

            new Missing("xAI · Grok Build",
                    "Its agent runs on linux-arm64 and has a full headless mode.",
                    "No official interface on any platform — no app, no web dashboard, no extension. "
                            + "The Grok app's Build Mode is a different product: it writes apps in a "
                            + "chat, it does not work on your files."),

            new Missing("Meta · Muse Code",
                    "Its agent runs on linux-arm64 and prints machine-readable output.",
                    "Terminal only. No extension, no desktop application, no web interface — and no "
                            + "free tier."),
    };

    /** The sentence that has to be there, so none of this reads as a ranking of models. */
    static final String NOT_A_VERDICT =
            "None of this is a judgement about whose models are better. It is a list of what each "
                    + "maker has published. The day any of them ships an interface a phone can "
                    + "reach, they belong here, and adding them is a small change.";

    /** Where the figures came from, named so they can be checked rather than trusted. */
    static final String SOURCES =
            "Adoption from the JetBrains Developer Ecosystem Survey 2026, over 15,000 professional "
                    + "developers. Infrastructure spending and revenue from the companies' own "
                    + "reporting. Everything about what runs where was checked against each "
                    + "publisher's own documentation and package registries in September 2026.";

    private Reasons() {}
}
