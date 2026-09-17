package com.pocketide;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Where everything this app holds actually is, measured, and the one kind of it an owner may
 * want gone on its own: the agents' chats.
 *
 * An owner asked where the chats and the files go. The answer is one place -- this app's own
 * storage, which Android encrypts, keeps from every other app, never backs up (allowBackup is
 * off) and deletes with the app -- but "one place" is not an answer a person can act on. This
 * class names the folders inside it, measures each, and can clear the transcripts the agents
 * keep, which are the part an owner sharing a phone cares about. What it will not do is guess:
 * each path below is the one the agent's own documentation or source names, and an agent
 * whose local files are not documented is measured as part of the editor's storage and said
 * to be.
 *
 * Nothing here follows a link. A folder inside Linux can be a symbolic link to anywhere the
 * app can reach, and a walk that followed one out of the rootfs would measure -- or delete --
 * what it pointed at.
 */
final class Stored {

    static final class Area {
        final String name;
        final String where;
        final long bytes;

        Area(String name, String where, long bytes) {
            this.name = name;
            this.where = where;
            this.bytes = bytes;
        }
    }

    private Stored() {}

    /** /root inside Linux, which is where every agent and the editor keep their files. */
    static File home(Context context) {
        return new File(Workspace.root(context), "root");
    }

    /** The editor's own data: settings, extensions, and every extension's storage. */
    static File editorData(Context context) {
        return new File(home(context), ".local/share/code-server");
    }

    /**
     * The transcripts the agents keep inside Linux, each at the path its own documentation or
     * source names. Sign-ins are not in this list and are never touched by clearChats.
     *
     *   Claude Code   ~/.claude/projects/    one folder per project, one file per session;
     *                 kept 30 days by default (cleanupPeriodDays), per Anthropic's docs
     *                 ~/.claude/history.jsonl   the prompt history
     *   Codex         ~/.codex/sessions/     rollout-<time>-<id>.jsonl, by date
     *                 ~/.codex/history.jsonl
     *   Kilo Code     the editor's storage for the extension, tasks/, one folder per task
     *                 (api_conversation_history.json and ui_messages.json, from its source)
     *   Antigravity   whatever it keeps, it keeps in its own extension storage under the
     *                 editor's; Google's documentation does not name a path, so none is
     *                 listed here and clearChats leaves it alone.
     */
    static List<File> chatFolders(Context context) {
        File home = home(context);
        List<File> folders = new ArrayList<>();
        folders.add(new File(home, ".claude/projects"));
        folders.add(new File(home, ".claude/history.jsonl"));
        folders.add(new File(home, ".codex/sessions"));
        folders.add(new File(home, ".codex/history.jsonl"));
        folders.add(new File(editorData(context), "User/globalStorage/kilocode.kilo-code/tasks"));
        return folders;
    }

    /** Every area, measured. Walks the tree, so call it off the drawing thread. */
    static List<Area> measure(Context context) {
        File root = Workspace.root(context);
        File home = home(context);
        List<Area> areas = new ArrayList<>();
        areas.add(new Area("Linux, all of it",
                "files/linux — Ubuntu, the editor, your projects and the agents' files; "
                        + "the rows below are parts of this one",
                Workspace.sizeOf(root)));
        areas.add(new Area("Your projects", "~/projects",
                Workspace.sizeOf(Workspace.projects(context))));
        areas.add(new Area("The editor and its extensions",
                "/opt/code-server and ~/.local/share/code-server, which holds every "
                        + "extension's own storage and sign-ins",
                Workspace.sizeOf(new File(root, "opt/code-server"))
                        + Workspace.sizeOf(editorData(context))));
        long chats = 0;
        for (File folder : chatFolders(context)) chats += Workspace.sizeOf(folder);
        areas.add(new Area("Agents' chats",
                "~/.claude/projects, ~/.codex/sessions and Kilo Code's tasks — the "
                        + "transcripts kept on the phone; each company also holds what was "
                        + "sent to it, under its own terms",
                chats));
        areas.add(new Area("Agents' settings and sign-ins",
                "~/.claude and ~/.codex apart from the chats",
                Math.max(0, Workspace.sizeOf(new File(home, ".claude"))
                        + Workspace.sizeOf(new File(home, ".codex"))
                        - Workspace.sizeOf(new File(home, ".claude/projects"))
                        - Workspace.sizeOf(new File(home, ".claude/history.jsonl"))
                        - Workspace.sizeOf(new File(home, ".codex/sessions"))
                        - Workspace.sizeOf(new File(home, ".codex/history.jsonl")))));
        areas.add(new Area("Test on this phone",
                "files/phone — the app's own adb, the pairing key, the allow-list",
                Workspace.sizeOf(new File(context.getFilesDir(), "phone"))));
        areas.add(new Area("Crash record", "files/last-crash.txt",
                new File(context.getFilesDir(), Crash.FILE).length()));
        return areas;
    }

    /**
     * Deletes the agents' transcripts and nothing else: not the sign-ins, not the settings,
     * not the projects. Returns the bytes freed. Off the drawing thread, with Linux stopped,
     * because an agent half way through a session would be writing into what is deleted.
     */
    static long clearChats(Context context) {
        long freed = 0;
        for (File folder : chatFolders(context)) {
            freed += Workspace.sizeOf(folder);
            Workspace.delete(folder);
        }
        return freed;
    }

    static final String EXPLANATION =
            "Everything this app has is in its own storage, /data/data/com.pocketide/files. "
                    + "Android encrypts that storage, keeps it from every other app, never "
                    + "backs it up, and deletes it with the app. Nothing is written to "
                    + "Downloads, Documents or an SD card unless you turn on The phone's "
                    + "files and then save something into ~/phone yourself.\n\n"
                    + "The agents' chats are the one part that also exists somewhere else: "
                    + "every prompt and reply goes to that agent's company as part of using "
                    + "its model, and is kept there under that company's own terms — "
                    + "Anthropic, OpenAI, Google, or whichever provider Kilo Code is pointed "
                    + "at. This app sends nothing itself.\n\n"
                    + "Clear agent chats deletes the transcripts on the phone: Claude Code's "
                    + "sessions and history, Codex's sessions and history, and Kilo Code's "
                    + "tasks. Sign-ins, settings and projects stay. Antigravity's local files "
                    + "are not touched, because Google's documentation does not say where "
                    + "they are; they live in its own storage under the editor's, and go "
                    + "with Remove everything.";
}
