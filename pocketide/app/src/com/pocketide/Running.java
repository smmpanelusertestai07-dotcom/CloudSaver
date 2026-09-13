package com.pocketide;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the workspace is actually doing, read from the phone's own /proc.
 *
 * This exists because of the one thing a phone changes about a development machine: on a laptop
 * a runaway build is a fan noise, and here it is a battery at 40 % by lunchtime with nothing on
 * screen to explain it. An agent that has been told to "run the tests" may spend twenty minutes
 * compiling, and until this screen existed there was no way to see that, no way to tell it from
 * a hang, and no way to stop it short of stopping everything.
 *
 * What can be read, and what cannot. Android has restricted /proc since Nougat: a process sees
 * its own and its children's entries and nothing else on the phone. That restriction is exactly
 * what makes this safe to ship -- everything here is the app looking at its own processes. No
 * permission is requested for it and none exists to request; other apps' processes are not
 * visible to this one and this screen could not show them if it wanted to.
 *
 * Fields are read from:
 *   /proc/<pid>/cmdline   the command, NUL-separated
 *   /proc/<pid>/stat      field 3 is the state letter, fields 14-15 the CPU jiffies
 *   /proc/<pid>/statm     field 2 is the resident set in pages
 */
final class Running {

    /** One process inside the workspace. */
    static final class Process {
        final int pid;
        final String command;
        final String detail;
        final char state;
        final long residentBytes;

        Process(int pid, String command, String detail, char state, long residentBytes) {
            this.pid = pid;
            this.command = command;
            this.detail = detail;
            this.state = state;
            this.residentBytes = residentBytes;
        }

        /** The state letter, in words an owner can act on. */
        String stateWords() {
            switch (state) {
                case 'R': return "running";
                case 'S': return "waiting";
                case 'D': return "reading or writing";
                case 'T': return "stopped";
                case 'Z': return "finished";
                default: return "unknown";
            }
        }

        boolean working() { return state == 'R' || state == 'D'; }
    }

    private Running() {}

    private static final long PAGE_SIZE = 4096;

    /**
     * Every process of this app's that belongs to the workspace, most demanding first.
     *
     * "Belongs to the workspace" is judged by the command line rather than by a recorded pid,
     * because PRoot re-executes itself and the editor spawns its own children -- an extension
     * host, a language server, whatever the agent runs -- and none of those are known in
     * advance. Anything running out of the app's own files directory, or named as one of the
     * pieces the app installed, counts.
     */
    static List<Process> workspace(Context context) {
        List<Process> found = new ArrayList<>();
        File proc = new File("/proc");
        File[] entries = proc.listFiles();
        if (entries == null) return found;

        String root = Workspace.root(context).getAbsolutePath();
        for (File entry : entries) {
            int pid = asPid(entry.getName());
            if (pid <= 0) continue;
            String cmdline = read(new File(entry, "cmdline")).replace('\0', ' ').trim();
            if (cmdline.isEmpty()) continue;
            if (!belongs(cmdline, root)) continue;

            String stat = read(new File(entry, "stat"));
            char state = stateOf(stat);
            long resident = residentOf(read(new File(entry, "statm")));
            found.add(new Process(pid, name(cmdline), shorten(cmdline), state, resident));
        }
        Collections.sort(found, (a, b) -> {
            if (a.working() != b.working()) return a.working() ? -1 : 1;
            return Long.compare(b.residentBytes, a.residentBytes);
        });
        return found;
    }

    /** What all of it is holding in memory, which is the number that decides whether Android kills it. */
    static long totalResidentBytes(List<Process> processes) {
        long total = 0;
        for (Process process : processes) total += process.residentBytes;
        return total;
    }

    private static boolean belongs(String cmdline, String root) {
        if (cmdline.contains(root)) return true;
        // PRoot and the editor both re-exec with paths that are inside the guest rather than
        // inside the app's own directory, so the app's root does not appear in them.
        return cmdline.contains("proot")
                || cmdline.contains("code-server")
                || cmdline.contains("/opt/code-server")
                || cmdline.contains("pocketide-");
    }

    /** The recognisable part of a command line: the program, not its forty arguments. */
    private static String name(String cmdline) {
        String first = cmdline.split(" ")[0];
        int slash = first.lastIndexOf('/');
        String program = slash >= 0 ? first.substring(slash + 1) : first;
        if (program.isEmpty()) return cmdline;
        // What a person would call it, rather than what it calls itself.
        if (cmdline.contains("code-server") && program.startsWith("node")) return "Visual Studio Code";
        if (program.startsWith("proot")) return "The workspace";
        if (cmdline.contains("extensionHost") || cmdline.contains("extension-host")) {
            return "Extension host";
        }
        if (cmdline.contains("bootstrap")) return "Setting up";
        return program;
    }

    /** The arguments, trimmed to something that fits a phone without hiding what is happening. */
    private static String shorten(String cmdline) {
        String trimmed = cmdline.replaceAll("\\s+", " ").trim();
        if (trimmed.length() <= 120) return trimmed;
        return trimmed.substring(0, 117) + "…";
    }

    private static int asPid(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < '0' || name.charAt(i) > '9') return -1;
        }
        try {
            return name.isEmpty() ? -1 : Integer.parseInt(name);
        } catch (NumberFormatException notAPid) {
            return -1;
        }
    }

    /**
     * The state letter out of /proc/<pid>/stat.
     *
     * Parsed from the LAST ')' rather than by splitting on spaces, because field 2 is the
     * program name in brackets and a program is entitled to have a space or a bracket in its
     * name. Splitting naively gets the wrong field for exactly the processes worth watching.
     */
    private static char stateOf(String stat) {
        int close = stat.lastIndexOf(')');
        if (close < 0 || close + 2 >= stat.length()) return '?';
        return stat.charAt(close + 2);
    }

    private static long residentOf(String statm) {
        String[] fields = statm.trim().split("\\s+");
        if (fields.length < 2) return 0;
        try {
            return Long.parseLong(fields[1]) * PAGE_SIZE;
        } catch (NumberFormatException unreadable) {
            return 0;
        }
    }

    private static String read(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException gone) {
            // A process that exits between listing and reading is ordinary, not an error.
            return "";
        }
    }
}
