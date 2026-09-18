package com.pocketide;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONObject;

/**
 * Every chat the agents keep on this phone: by agent, newest first, with its project, its
 * date and its size. Tap one to carry on with it in the editor; select to delete.
 *
 * Opening goes through the companion extension: this screen leaves a request in the folder
 * the app binds into the editor as /run/pocketide, opens the editor, and the companion runs
 * the agent's own resume command in a terminal in the chat's project. Kilo Code has no such
 * command; its chats open from its own History view, and the screen says so.
 */
public final class ChatsActivity extends Activity {

    private static final String INBOX = "editor-inbox";

    private boolean dark;
    private LinearLayout content;
    private TextView state;
    private TextView selectButton;
    private TextView deleteButton;
    private final List<Chats.Chat> chats = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private boolean selecting;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Theme.apply(this);
        setContentView(build());
        load();
    }

    @Override protected void onResume() {
        super.onResume();
        // A chat that was opened and written to has moved; the list follows it.
        if (content != null) load();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Theme.apply(this);
        setContentView(build());
        render();
    }

    private View build() {
        dark = Ui.dark(this);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.bg(dark));
        root.addView(Ui.topBar(this, dark, "Agent chats", v -> finish()));

        LinearLayout column = Ui.column(this);
        column.addView(Ui.text(this,
                "Kept by each agent on this phone, in its own files. They are not fetched back "
                        + "from your account: the copy here is the one the agent resumes from. "
                        + "Tap a chat to carry on with it in the editor.",
                13.5f, Ui.muted(dark)), Ui.wide(this, 12));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        selectButton = Ui.button(this, "Select", false, dark);
        selectButton.setOnClickListener(v -> toggleSelecting());
        deleteButton = Ui.button(this, "Delete", false, dark);
        deleteButton.setOnClickListener(v -> deleteSelected());
        deleteButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half.rightMargin = Ui.dp(this, 8);
        actions.addView(selectButton, half);
        actions.addView(deleteButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(actions, Ui.wide(this, 12));

        state = Ui.text(this, "Reading…", 13.5f, Ui.muted(dark));
        column.addView(state, Ui.wide(this, 16));
        content = Ui.column(this);
        column.addView(content);

        TextView clear = Ui.button(this, "Delete every chat", false, dark);
        clear.setOnClickListener(v -> confirmClearAll());
        column.addView(clear, Ui.wide(this, 20));

        ScrollView page = Ui.page(this, column, dark);
        root.addView(page, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    // ------------------------------------------------------------------ the list

    private void load() {
        new Thread(() -> {
            final List<Chats.Chat> found = Workspace.installed(this)
                    ? Chats.all(this) : new ArrayList<>();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                chats.clear();
                chats.addAll(found);
                selected.clear();
                render();
            });
        }, "chats").start();
    }

    private void render() {
        if (content == null) return;
        content.removeAllViews();
        if (chats.isEmpty()) {
            state.setText(Workspace.installed(this)
                    ? "No chats on this phone yet. They appear here once an agent has been used."
                    : "Nothing is set up yet, so there are no chats on this phone.");
            state.setVisibility(View.VISIBLE);
            selectButton.setVisibility(View.GONE);
            deleteButton.setVisibility(View.GONE);
            return;
        }
        state.setVisibility(View.GONE);
        selectButton.setVisibility(View.VISIBLE);
        selectButton.setText(selecting ? "Cancel" : "Select");
        deleteButton.setVisibility(selecting ? View.VISIBLE : View.GONE);
        deleteButton.setText(selected.isEmpty() ? "Delete" : "Delete " + selected.size());

        DateFormat when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (String agent : new String[]{Chats.CLAUDE, Chats.CODEX, Chats.KILO}) {
            List<Chats.Chat> these = new ArrayList<>();
            long bytes = 0;
            for (Chats.Chat chat : chats) {
                if (agent.equals(chat.agent)) {
                    these.add(chat);
                    bytes += chat.bytes;
                }
            }
            if (these.isEmpty()) continue;
            content.addView(Ui.sectionLabel(this, agent + " · " + these.size()
                    + (these.size() == 1 ? " chat · " : " chats · ")
                    + DeviceProbe.formatBytes(bytes), dark), Ui.wide(this, 14));
            LinearLayout list = Ui.column(this);
            list.setBackground(Ui.glass(this, dark, 20));
            boolean first = true;
            for (final Chats.Chat chat : these) {
                if (!first) list.addView(Ui.divider(this, dark, true));
                first = false;
                boolean picked = selected.contains(key(chat));
                String title = chat.title.isEmpty()
                        ? "Chat from " + when.format(new Date(chat.updated)) : chat.title;
                String project = chat.project.isEmpty() ? ""
                        : new File(chat.project).getName() + " · ";
                Ui.Row row = Ui.row(this, dark,
                        picked ? R.drawable.ic_check : R.drawable.ic_chat, title,
                        project + when.format(new Date(chat.updated)) + " · "
                                + DeviceProbe.formatBytes(chat.bytes),
                        v -> {
                            if (selecting) toggle(chat);
                            else open(chat);
                        });
                if (picked) row.setState(Ui.accent(dark));
                list.addView(row);
            }
            content.addView(list, Ui.wide(this, 8));
        }
    }

    private static String key(Chats.Chat chat) {
        return chat.agent + "/" + chat.id + "/" + chat.path.getName();
    }

    private void toggleSelecting() {
        selecting = !selecting;
        selected.clear();
        render();
    }

    private void toggle(Chats.Chat chat) {
        String key = key(chat);
        if (!selected.remove(key)) selected.add(key);
        render();
    }

    // ------------------------------------------------------------------ opening

    /**
     * Leaves the resume request where the companion extension reads it, then opens the
     * editor. The request is one file; the companion deletes it before acting on it.
     */
    private void open(Chats.Chat chat) {
        if (chat.resume == null) {
            Dialogs.confirm(this, "Open in Kilo Code",
                    "Kilo Code keeps its chats in its own History view inside the editor: open "
                            + "the editor, then the Kilo Code panel, then History.",
                    "Open the editor", () -> startActivity(new Intent(this, WorkspaceActivity.class)));
            return;
        }
        File inbox = new File(PhoneBroker.bridgeDir(this), INBOX);
        if (!inbox.isDirectory() && !inbox.mkdirs()) {
            Dialogs.message(this, "Could not ask the editor",
                    "The folder the editor reads requests from could not be created.");
            return;
        }
        try {
            JSONObject request = new JSONObject()
                    .put("action", "terminal")
                    .put("name", chat.agent)
                    .put("cwd", chat.project.isEmpty() ? "/root/projects" : chat.project)
                    .put("command", chat.resume);
            File file = new File(inbox, System.currentTimeMillis() + ".json");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | org.json.JSONException failed) {
            Dialogs.message(this, "Could not ask the editor",
                    "The request to the editor could not be written.");
            return;
        }
        Toast.makeText(this, "Opening it in the editor…", Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, WorkspaceActivity.class));
    }

    // ------------------------------------------------------------------ deleting

    private boolean editorInTheWay(String title) {
        if (WorkspaceService.editorRunning()) {
            Dialogs.message(this, title,
                    "Close the editor first: an agent in the middle of a chat would be writing "
                            + "into what this deletes.");
            return true;
        }
        return false;
    }

    private void deleteSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "Tap the chats to delete first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (editorInTheWay("Delete chats")) return;
        final List<Chats.Chat> doomed = new ArrayList<>();
        for (Chats.Chat chat : chats) if (selected.contains(key(chat))) doomed.add(chat);
        Dialogs.confirm(this, doomed.size() == 1 ? "Delete this chat?"
                        : "Delete " + doomed.size() + " chats?",
                "The transcript on this phone is deleted; what the agent's company holds on "
                        + "its own servers is theirs to delete, under their terms.\n\nThis "
                        + "cannot be undone.",
                "Delete", true, () -> {
                    final Dialogs.Live live = Dialogs.live(this, "Deleting", "Deleting…");
                    new Thread(() -> {
                        int gone = 0;
                        for (Chats.Chat chat : doomed) if (Chats.delete(chat)) gone++;
                        final int count = gone;
                        live.done(count == doomed.size(), count == doomed.size()
                                ? "Deleted." : "Some could not be deleted.");
                        runOnUiThread(() -> {
                            if (isFinishing()) return;
                            selecting = false;
                            load();
                        });
                    }, "delete-chats").start();
                });
    }

    private void confirmClearAll() {
        if (!Workspace.installed(this)) {
            Dialogs.message(this, "Delete every chat", "Nothing is set up yet, so there are "
                    + "no chats on this phone.");
            return;
        }
        if (editorInTheWay("Delete every chat")) return;
        Dialogs.confirm(this, "Delete every chat?",
                "This deletes the conversation transcripts the agents keep on this phone: "
                        + "Claude Code's sessions and history, Codex's sessions and history, "
                        + "and Kilo Code's tasks. Sign-ins, settings and your projects are not "
                        + "touched. What each company already holds on its own servers is "
                        + "theirs to delete, under their terms.\n\nThis cannot be undone.",
                "Delete all", true, () -> {
                    final Dialogs.Live live = Dialogs.live(this, "Deleting every chat",
                            "Deleting the transcripts…");
                    new Thread(() -> {
                        long freed = Stored.clearChats(this);
                        live.done(true, "Deleted. " + DeviceProbe.formatBytes(freed) + " freed.");
                        runOnUiThread(() -> {
                            if (isFinishing()) return;
                            selecting = false;
                            load();
                        });
                    }, "clear-chats").start();
                });
    }
}
