package com.pocketide;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What the editor actually has installed, read from the editor's own records.
 *
 * This class exists because of a bug an owner reported with two screenshots. They installed
 * Google Antigravity from inside the editor -- which is the normal way to install an extension,
 * the way the editor's own Extensions panel offers -- and the app went on showing it as not
 * installed, while Claude Code and Codex, which the app itself had installed, showed correctly.
 * "Download krne pe dikh nahi raha": downloaded, and not shown.
 *
 * The cause was that the app kept its OWN list in a preference and never asked the editor. A
 * list the app writes only when the app installs something is a list that is wrong the moment
 * anybody installs anything any other way -- and the editor's Extensions panel is not some other
 * way, it is the main way.
 *
 * So nothing is remembered here. The editor keeps its own record in extensions.json beside the
 * extensions themselves, exactly as Visual Studio Code does, and that file is the answer. It is
 * read off the phone's own disk with no Linux running and no PRoot started, because the
 * workspace is a directory in this app's storage and that directory is readable whether or not
 * anything inside it is running.
 *
 * IDENTIFIER CASE IS THE SECOND HALF OF THE SAME BUG. The registry publishes "Google.google-
 * antigravity" with a capital G, and that is the spelling in Agents.java; the editor normalises
 * identifiers to lower case when it records them. A plain equals() between the two is false, so
 * even a correct list would have compared as "not installed". Everything here compares with
 * same(), which folds case, and every caller was changed to do the same.
 */
final class Extensions {

    private Extensions() {}

    /** Where the editor keeps its extensions. The same path pocketide-editor.sh passes it. */
    static File directory(Context context) {
        return new File(Workspace.root(context), "root/.local/share/code-server/extensions");
    }

    /** Where the editor keeps settings.json and keybindings.json. */
    static File userDirectory(Context context) {
        return new File(Workspace.root(context), "root/.local/share/code-server/User");
    }

    /** Two extension identifiers, compared the way the registry and the editor both mean them. */
    static boolean same(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    /** True when {@code id} is somewhere in {@code ids}, whatever either one capitalises. */
    static boolean has(List<String> ids, String id) {
        if (ids == null || id == null) return false;
        for (String each : ids) if (same(each, id)) return true;
        return false;
    }

    // ------------------------------------------------------------------ what is installed

    private static volatile List<String> cachedIds;
    private static volatile long cachedAt;

    /**
     * Every extension the editor has, by identifier, or null when there is no editor yet.
     *
     * Null rather than an empty list on purpose: "the editor has none" and "there is no editor
     * to ask" are different answers, and a caller that cannot tell them apart shows an owner an
     * empty list where it should show nothing at all.
     *
     * Cheap enough for a screen that redraws every few seconds: one small JSON file, and its
     * answer is kept until the directory's timestamp moves.
     */
    static List<String> ids(Context context) {
        File dir = directory(context);
        if (!dir.isDirectory()) return null;
        long stamp = dir.lastModified();
        List<String> known = cachedIds;
        if (known != null && stamp == cachedAt) return known;

        List<String> found = fromRecord(new File(dir, "extensions.json"));
        if (found == null) found = fromFolderNames(dir);
        cachedIds = found;
        cachedAt = stamp;
        return found;
    }

    /** Forgets the cached answer, for the moment straight after an install or a removal. */
    static void forget() {
        cachedIds = null;
        cachedAt = 0L;
    }

    /**
     * The editor's own record: a JSON array of what it has, which it rewrites on every install
     * and every removal. This is the authority, and it is a few kilobytes.
     */
    private static List<String> fromRecord(File record) {
        if (!record.isFile()) return null;
        try {
            JSONArray entries = new JSONArray(read(record));
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                JSONObject identifier = entry.optJSONObject("identifier");
                String id = identifier == null ? "" : identifier.optString("id", "");
                if (!id.isEmpty() && !has(ids, id)) ids.add(id);
            }
            return Collections.unmodifiableList(ids);
        } catch (Throwable unreadable) {
            // Half-written during an install, or a format this build does not know. The folder
            // names below are the fallback rather than an error: they are the same information
            // in a form that cannot be half-written.
            return null;
        }
    }

    /**
     * The fallback: the extensions are one folder each, named publisher.name-version.
     *
     * Used when the record is missing or being rewritten. The version suffix is trimmed at the
     * last hyphen followed by a digit, because a name may contain hyphens -- "google-
     * antigravity" does -- and trimming at the first one would cut the name in half.
     */
    private static List<String> fromFolderNames(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return Collections.emptyList();
        List<String> ids = new ArrayList<>();
        for (File child : children) {
            if (!child.isDirectory()) continue;
            String id = withoutVersion(child.getName());
            if (id.indexOf('.') <= 0) continue;
            if (!has(ids, id)) ids.add(id);
        }
        Collections.sort(ids);
        return Collections.unmodifiableList(ids);
    }

    private static String withoutVersion(String folder) {
        for (int i = folder.length() - 1; i > 0; i--) {
            if (folder.charAt(i) != '-') continue;
            if (i + 1 < folder.length() && Character.isDigit(folder.charAt(i + 1))) {
                return folder.substring(0, i);
            }
        }
        return folder;
    }

    // ------------------------------------------------------------------ opening one

    /**
     * An extension that has a panel of its own, and the editor command that opens it.
     *
     * This is what answers the other half of the owner's report -- "Open kaise karu", how do I
     * open it. An agent extension puts itself in the editor's activity bar, which on a phone is
     * a row of small icons that is easy to miss and easy to mistake for something else. The app
     * can open it directly instead, because the extension says in its own manifest which
     * container it contributes, and the editor has a command for focusing a container by name.
     */
    static final class Panel {
        final String extensionId;
        final String title;
        /** The editor command that brings this panel to the front. */
        final String command;

        Panel(String extensionId, String title, String command) {
            this.extensionId = extensionId;
            this.title = title;
            this.command = command;
        }
    }

    /**
     * Every installed extension that contributes a panel, newest information each time.
     *
     * Reads one package.json per extension, which is why this is not called from a screen that
     * redraws on a timer: an agent's manifest can be hundreds of kilobytes. The editor menu
     * asks for it once, on a background thread, when it is opened.
     */
    static List<Panel> panels(Context context) {
        File dir = directory(context);
        File[] children = dir.listFiles();
        if (children == null) return Collections.emptyList();
        Arrays.sort(children);
        List<Panel> panels = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (File child : children) {
            if (!child.isDirectory()) continue;
            File manifest = new File(child, "package.json");
            if (!manifest.isFile()) continue;
            try {
                JSONObject json = new JSONObject(read(manifest));
                String publisher = json.optString("publisher", "");
                String name = json.optString("name", "");
                if (publisher.isEmpty() || name.isEmpty()) continue;
                String id = publisher + "." + name;
                if (has(seen, id)) continue;
                String container = firstActivityBarContainer(json);
                if (container.isEmpty()) continue;
                String title = json.optString("displayName", "");
                if (title.isEmpty() || title.startsWith("%")) title = name;
                seen.add(id);
                panels.add(new Panel(id, title, "workbench.view.extension." + container));
            } catch (Throwable unreadable) {
                // An extension whose manifest cannot be read simply has no shortcut here. It
                // is still installed and still reachable from the editor's own activity bar.
            }
        }
        return panels;
    }

    /** The editor command that brings {@code extensionId}'s panel to the front, or "". */
    static String panelCommand(Context context, String extensionId) {
        for (Panel panel : panels(context)) {
            if (panel.extensionId.equalsIgnoreCase(extensionId)) return panel.command;
        }
        return "";
    }

    private static String firstActivityBarContainer(JSONObject manifest) {
        JSONObject contributes = manifest.optJSONObject("contributes");
        if (contributes == null) return "";
        JSONObject containers = contributes.optJSONObject("viewsContainers");
        if (containers == null) return "";
        JSONArray activityBar = containers.optJSONArray("activitybar");
        if (activityBar == null || activityBar.length() == 0) return "";
        JSONObject first = activityBar.optJSONObject(0);
        return first == null ? "" : first.optString("id", "");
    }

    // ------------------------------------------------------------------ the app's own keys

    /**
     * The function keys the editor's menu presses, written as the editor's own keybindings.
     *
     * This is how a phone reaches commands that were designed for a keyboard, and it is the
     * editor's documented mechanism rather than a trick: keybindings.json is the same file a
     * person would write by hand. The app writes it because the app is what presses the keys,
     * and because three of the bindings are worked out from whichever agents are installed --
     * a file shipped as a fixed asset could not name them.
     *
     * Single unmodified keys, never combinations. A synthetic Ctrl+Shift+P has to carry two
     * modifier bits and a shifted letter through the WebView's key translation and arrives as
     * nothing, which is what "Commands does not work" was. An F key is one code with no
     * modifiers and survives the trip.
     *
     * Overriding the editor's own F-key defaults (F2 rename, F5 debug, F12 go-to-definition) is
     * deliberate and costs nothing here: nothing on a phone types an F key, and every command
     * they displace is still in the Command Palette, which is the first entry in the menu.
     */
    static final int FIRST_PANEL_KEY = 5;
    static final int PANEL_KEYS = 3;

    /** Writes the file and returns the panels it bound, so a menu can be built from the same list. */
    static List<Panel> writeKeybindings(Context context) {
        List<Panel> panels = panels(context);
        File user = userDirectory(context);
        if (!user.isDirectory() && !user.mkdirs()) return panels;
        File file = new File(user, "keybindings.json");
        StringBuilder json = new StringBuilder();
        json.append("// Written by PocketIDE. The editor's menu presses F1 to F11; anything "
                + "else here is yours and is kept.\n[\n");
        for (String kept : ownersBindings(file)) json.append("  ").append(kept).append(",\n");
        append(json, "f1", "workbench.action.showCommands");
        append(json, "f2", "workbench.view.explorer");
        append(json, "f3", "workbench.action.terminal.toggleTerminal");
        append(json, "f4", "workbench.view.extensions");
        for (int i = 0; i < PANEL_KEYS && i < panels.size(); i++) {
            append(json, "f" + (FIRST_PANEL_KEY + i), panels.get(i).command);
        }
        // F8 to F10 once bound workbench.action.zoomOut, zoomIn and zoomReset. Those exist
        // only in the desktop build; the web build the phone runs answered "command not
        // found", and the text size is the WebView's own business now (WorkspaceActivity).
        append(json, "f11", "workbench.action.toggleSidebarVisibility");
        // The trailing comma of the last entry, removed: a comment-tolerant parser still
        // refuses a dangling comma before the closing bracket.
        int comma = json.lastIndexOf(",");
        if (comma > 0) json.deleteCharAt(comma);
        json.append("]\n");
        write(file, json.toString());
        return panels;
    }

    /**
     * The bindings in the file that are not this app's, kept across a rewrite.
     *
     * An owner who added a binding of their own through the editor's Keyboard Shortcuts
     * screen wrote it into this same file, and a rewrite that started from nothing threw it
     * away at every start. Everything whose key is not one of the app's F keys is carried
     * over as it was. A file that cannot be parsed -- half-written, or a format this build
     * does not know -- keeps nothing, which is only what happened before at every start.
     */
    private static List<String> ownersBindings(File file) {
        List<String> kept = new ArrayList<>();
        if (!file.isFile()) return kept;
        try {
            StringBuilder plain = new StringBuilder();
            for (String line : read(file).split("\n")) {
                if (!line.trim().startsWith("//")) plain.append(line).append('\n');
            }
            JSONArray entries = new JSONArray(plain.toString());
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                String key = entry.optString("key", "").trim().toLowerCase(Locale.ROOT);
                if (key.matches("f([1-9]|1[01])")) continue;
                kept.add(entry.toString());
            }
        } catch (Throwable unreadable) {
            kept.clear();
        }
        return kept;
    }

    private static void append(StringBuilder json, String key, String command) {
        json.append("  { \"key\": \"").append(key).append("\", \"command\": \"")
                .append(command).append("\" },\n");
    }

    // ------------------------------------------------------------------ files

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File file, String text) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Throwable notWritten) {
            // The editor still starts; its menu keys simply fall back to whatever the editor
            // binds them to itself, and F1 is the palette in either case.
        }
    }

}
