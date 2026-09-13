package com.pocketagent.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Native local Codex conversations and engine-reported background tasks. */
public final class SessionsActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<AlertDialog> dialogs = new HashSet<>();
    private FrameLayout lockShell;
    private LinearLayout root, tabs, body, searchRow;
    private TextView status;
    private EditText searchInput;
    private JSONObject state = new JSONObject(), sessions = new JSONObject(), tasks = new JSONObject();
    private JSONArray localRows = new JSONArray();
    private final ExecutorService indexWorker = Executors.newSingleThreadExecutor();
    private boolean indexPending;
    private String indexSignature = "", pendingManage = "", pendingManageOperation = "";
    private String project = "", theme = "", signature = "", pendingResume = "", displayedShare = "";
    private boolean registered, resumed, firstLoad, delayed;
    private int section;
    private ScrollView scroll;
    private boolean refreshDue = true, expanded, automaticRequest;
    private String visibleScope = "";
    private final ConnectionsActivity.ReadRefresh reads = new ConnectionsActivity.ReadRefresh();
    private final ConnectionsActivity.ClientPresence presence = new ConnectionsActivity.ClientPresence(this);
    private final Runnable searchChanged = () -> { expanded = false; refreshDue = true; refreshAutomatically(); };
    private final Runnable autoTick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            presence.update(project, safe()); refreshState();
            main.postDelayed(this, 5000);
        }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (delayed) return; delayed = true;
            main.postDelayed(() -> { delayed = false; refreshState(); }, 160);
        }
    };

    static void open(Activity activity, String project) { open(activity, project, false); }
    static void open(Activity activity, String project, boolean tasks) {
        activity.startActivity(new Intent(activity, SessionsActivity.class).putExtra(AgentService.EXTRA_PROJECT, project).putExtra("tasks", tasks));
    }
    static void openManage(Activity activity, String project, String threadId) { openManage(activity, project, threadId, ""); }
    static void openManage(Activity activity, String project, String threadId, String operation) {
        activity.startActivity(new Intent(activity, SessionsActivity.class).putExtra(AgentService.EXTRA_PROJECT, project)
                .putExtra("manageThread", threadId).putExtra("manageOperation", operation));
    }
    @Override public void onCreate(Bundle saved) {
        DeskStyle.apply(this); super.onCreate(saved); theme = DeskStyle.themeSignature(this);
        try { project = AgentProtocol.project(getIntent().getStringExtra(AgentService.EXTRA_PROJECT)); }
        catch (Exception bad) { finish(); return; }
        section = getIntent().getBooleanExtra("tasks", false) ? 2 : 0;
        pendingManage = getIntent().getStringExtra("manageThread"); if (pendingManage == null) pendingManage = "";
        pendingManageOperation = getIntent().getStringExtra("manageOperation"); if (pendingManageOperation == null) pendingManageOperation = "";
        if (saved != null) { section = saved.getInt("section", section); firstLoad = saved.getBoolean("loaded"); displayedShare = saved.getString("share", ""); pendingResume = saved.getString("resume", ""); }
        build(); if (saved != null) searchInput.setText(saved.getString("search", "")); refreshState();
    }
    @Override protected void onStart() {
        super.onStart(); IntentFilter filter = new IntentFilter(AgentService.EVENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(receiver, filter);
        registered = true;
    }
    @Override protected void onResume() {
        super.onResume();
        if (!theme.equals(DeskStyle.themeSignature(this))) { recreate(); return; }
        resumed = true; AppLock.applyWindowSecurity(this);
        if (AppLock.isLocked(this)) AppLock.show(this, lockShell, this::resumeWork); else resumeWork();
    }
    @Override protected void onPause() { resumed = false; main.removeCallbacks(autoTick); main.removeCallbacks(searchChanged); super.onPause(); }
    @Override protected void onStop() {
        presence.update(project, false);
        if (registered) { unregisterReceiver(receiver); registered = false; }
        if (AppLock.enabled(this)) for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        super.onStop();
    }
    @Override protected void onDestroy() { presence.update(project, false); main.removeCallbacksAndMessages(null); indexWorker.shutdown(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle saved) {
        saved.putInt("section", section); saved.putBoolean("loaded", firstLoad); saved.putString("search", searchInput.getText().toString());
        saved.putString("share", displayedShare); saved.putString("resume", pendingResume); super.onSaveInstanceState(saved);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) { super.onActivityResult(request, result, data); AppLock.handleResult(this, lockShell, request, result, this::resumeWork); }
    private void resumeWork() {
        if (!safe()) return;
        presence.update(project, true); refreshDue = true; expanded = false; refreshState();
        main.removeCallbacks(autoTick); main.postDelayed(autoTick, 5000);
    }
    private boolean canRead() {
        JSONObject controls = child(state, "controls");
        return safe() && usable() && pendingManage.isEmpty() && dialogs.isEmpty() && !sessions.optBoolean("loading") && !tasks.optBoolean("loading")
                && !state.optBoolean("connecting") && !state.optBoolean("authenticating") && !state.optBoolean("installing")
                && !state.optBoolean("sessionOpening") && child(state, "permission").length() == 0
                && controls.optString("operation").isEmpty() && !child(state, "integrations").optBoolean("loading")
                && (section == 2 ? state.optBoolean("connected") && !tasks.optString("threadId").isEmpty() : !state.optBoolean("busy"))
                && reads.available(SystemClock.elapsedRealtime());
    }
    private void refreshAutomatically() {
        if (!canRead()) return;
        if (!refreshDue && (expanded || (scroll != null && scroll.getScrollY() > dp(24)))) return;
        JSONObject shown = section == 2 ? tasks : sessions;
        if (!reads.due(SystemClock.elapsedRealtime(), section == 2 ? 5000 : 15000,
                !shown.optString("error").isEmpty(), refreshDue)) return;
        refreshDue = false; automaticRequest = true;
        boolean accepted;
        try { accepted = section == 2 ? control("tasks_refresh", new JSONObject())
                : session("refresh", object("archived", section == 1, "search", searchInput.getText().toString())); }
        finally { automaticRequest = false; }
        if (accepted) firstLoad = true;
    }
    private boolean safe() { return resumed && !isFinishing() && !AppLock.isLocked(this); }
    private boolean usable() { return "codex".equals(state.optString("provider")) && project.equals(state.optString("project")) && (state.optBoolean("connected") || state.optBoolean("accountConnected")); }
    private boolean historyIdle() { return usable() && !state.optBoolean("busy") && !state.optBoolean("connecting") && !state.optBoolean("sessionOpening")
            && !sessions.optBoolean("loading") && !tasks.optBoolean("loading") && !child(state, "integrations").optBoolean("loading")
            && child(state, "controls").optString("operation").isEmpty() && reads.available(SystemClock.elapsedRealtime())
            && child(state, "permission").length() == 0; }

    private void build() {
        root = column(); root.setBackground(DeskStyle.background(this)); root.setPadding(dp(16), dp(8), dp(16), dp(8));
        lockShell = new FrameLayout(this); lockShell.addView(root); setContentView(lockShell); AppLock.applyWindowSecurity(this);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        DeskStyle.applySystemBars(this);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                int extra = Math.max(0, (getResources().getDisplayMetrics().widthPixels - bars.left - bars.right - dp(720)) / 2);
                root.setPadding(dp(16) + bars.left + extra, dp(8) + bars.top, dp(16) + bars.right + extra, dp(8) + Math.max(bars.bottom, ime.bottom));
            } else root.setPadding(dp(16) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(), dp(16) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout heading = row(); heading.addView(button("Back", false, v -> finish()), new LinearLayout.LayoutParams(-2, -2));
        TextView title = text("History", 20, DeskStyle.TEXT); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); title.setPadding(dp(12), 0, 0, 0);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(heading);
        root.addView(text("Codex · " + project, 12, DeskStyle.MUTED), margins(5, 12));
        tabs = row(); root.addView(tabs, margins(0, 12)); renderTabs();
        searchRow = row(); searchInput = new EditText(this); searchInput.setSingleLine(true); searchInput.setHint("Search conversation titles");
        searchInput.setTextColor(DeskStyle.TEXT); searchInput.setHintTextColor(DeskStyle.MUTED); searchInput.setTextSize(14); searchInput.setBackground(DeskStyle.field(this));
        searchInput.setPadding(dp(12), dp(10), dp(12), dp(10)); searchInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setOnEditorActionListener((view, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { refreshList(); return true; } return false; });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { main.removeCallbacks(searchChanged); main.postDelayed(searchChanged, 550); }
            @Override public void afterTextChanged(Editable text) { }
        });
        searchRow.addView(searchInput, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(searchRow, margins(0, 8));
        LinearLayout controls = row(); status = text("", 12, DeskStyle.MUTED); controls.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(button("Details", false, v -> details())); root.addView(controls, margins(0, 8));
        scroll = new ScrollView(this); body = column(); scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    private void renderTabs() {
        tabs.removeAllViews(); String[] names = {"Recent", "Archived", "Tasks"};
        for (int i = 0; i < names.length; i++) {
            final int target = i; Button button = button(names[i], i == section, v -> {
                if (!safe() || sessions.optBoolean("loading") || tasks.optBoolean("loading")) return;
                section = target; expanded = false; refreshDue = true; renderTabs(); signature = ""; render(); refreshAutomatically();
            });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1); if (i > 0) size.leftMargin = dp(6); tabs.addView(button, size);
        }
    }
    private void refreshState() {
        state = AgentService.snapshot(); sessions = child(state, "sessions"); tasks = child(child(state, "controls"), "background");
        if (!safe()) return;
        String scope = project + ":" + child(state, "account").toString();
        if (!scope.equals(visibleScope)) { visibleScope = scope; signature = ""; localRows = new JSONArray(); refreshDue = true; expanded = false; }
        cacheHistory();
        if (!pendingResume.isEmpty() && state.optBoolean("connected") && !state.optBoolean("sessionOpening") && pendingResume.equals(state.optString("threadId")) && !sessions.optBoolean("loading")) { pendingResume = ""; finish(); return; }
        if (!pendingResume.isEmpty() && !sessions.optBoolean("loading") && !sessions.optString("error").isEmpty()) pendingResume = "";
        String next = sessions.toString() + tasks.toString() + localRows.toString() + usable() + state.optBoolean("busy") + child(state, "permission").toString();
        if (!next.equals(signature)) { signature = next; render(); }
        JSONObject shown = section == 2 ? tasks : sessions;
        if (usable() && shown.optBoolean("known") && !shown.optBoolean("loading") && shown.optString("error").isEmpty())
            status.setText(expanded || (scroll != null && scroll.getScrollY() > dp(24)) ? "Updates paused while browsing" : "Updates automatically");
        String share = sessions.optString("shareId", "");
        if (!share.isEmpty() && !share.equals(displayedShare) && !sessions.optString("shareText").isEmpty()) { displayedShare = share; reviewShare(); }
        refreshAutomatically();
        if (!pendingManage.isEmpty() && !sessions.optBoolean("loading")) {
            String id = pendingManage, operation = pendingManageOperation; pendingManage = ""; pendingManageOperation = "";
            JSONObject selected = object("id", id, "name", "Conversation", "archived", section == 1);
            JSONArray entries = LocalSessionIndex.combine(state, localRows, section == 1, "");
            for (int i = 0; i < entries.length(); i++) if (id.equals(entries.optJSONObject(i).optString("id"))) selected = entries.optJSONObject(i);
            if (operation.isEmpty()) actions(selected); else manage(selected, operation);
        }
    }
    private void cacheHistory() {
        String requested = state.optString("provider") + state.optString("project") + child(state, "account").toString()
                + state.optString("threadId") + sessions.toString() + list(state, "messages").toString();
        if (indexPending || requested.equals(indexSignature)) return;
        indexPending = true; indexSignature = requested; final JSONObject captured = state; final String scope = visibleScope;
        indexWorker.execute(() -> {
            JSONArray result; try { LocalSessionIndex.update(getFilesDir(), "codex", project, captured);
                result = LocalSessionIndex.load(getFilesDir(), "codex", project, captured);
            } catch (Exception failure) { result = null; }
            final JSONArray ready = result; main.post(() -> { indexPending = false;
                if (!isFinishing() && scope.equals(visibleScope) && ready != null) { localRows = ready; signature = ""; if (safe()) refreshState(); }
            });
        });
    }
    private void render() {
        if (!safe()) return;
        JSONObject updating = section == 2 ? tasks : sessions;
        // Preserve the visible page during a read; controllers may clear their rows until the reply arrives.
        if (usable() && updating.optBoolean("loading") && body.getChildCount() > 0) { status.setText("Updating…"); return; }
        body.removeAllViews(); searchRow.setVisibility(section == 2 ? View.GONE : View.VISIBLE);
        if (!usable() && section == 2) { status.setText("Reconnect to view running tasks"); body.addView(button("Return to chat", true, v -> finish())); return; }
        JSONObject shown = section == 2 ? tasks : sessions; status.setText(!usable() ? "Saved on this phone · reconnect to continue" : shown.optBoolean("loading") ? "Updating…"
                : !shown.optString("error").isEmpty() ? "Needs attention" : shown.optBoolean("known") ? "Updates automatically"
                : state.optBoolean("busy") && section != 2 ? "Waiting for the current task" : "Waiting for Codex…");
        if (shown.optBoolean("loading")) { ProgressBar loading = new ProgressBar(this); body.addView(loading, new LinearLayout.LayoutParams(-1, dp(34))); }
        if (child(state, "permission").length() > 0) { note("Your approval is needed", "Return to the conversation to answer the agent's pending request."); body.addView(button("Return to approval", true, v -> finish())); }
        if (!shown.optString("error").isEmpty()) { note("Could not update", shown.optString("error")); body.addView(button("Retry", false, v -> refreshList()), margins(0, 8)); }
        if (section == 2) { renderTasks(); return; }

        if (!sessions.optString("shareText").isEmpty()) body.addView(button("Review share draft", false, v -> reviewShare()), margins(0, 10));
        JSONArray values = LocalSessionIndex.combine(state, localRows, section == 1, searchInput.getText().toString());
        if (sessions.optBoolean("known") && values.length() == 0) note("No conversations found", section == 1 ? "Archived chats for this project will appear here." : "Send a prompt in Codex or try another title search.");
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i); if (item == null) continue;
            LinearLayout card = card(); String name = item.optString("name", "Untitled conversation");
            card.addView(text(name, 16, DeskStyle.TEXT));
            String meta = date(item.optLong("updatedAt")); if (item.optString("id").equals(sessions.optString("activeThread"))) meta = "Current conversation · " + meta;
            card.addView(text(meta, 12, DeskStyle.MUTED), margins(4, 6));
            if (!item.optString("preview").isEmpty()) card.addView(text(item.optString("preview"), 13, DeskStyle.MUTED));
            card.setFocusable(true); card.setContentDescription(name + ", conversation actions"); card.setOnClickListener(v -> actions(item)); body.addView(card, margins(0, 10));
        }
        if (usable() && sessions.optBoolean("archived") == (section == 1) && sessions.optBoolean("hasMore")) body.addView(button("Load more", false, v -> { if (session("more", new JSONObject())) expanded = true; }));
        if (sessions.optBoolean("truncated")) note("Could not load another page", "Search titles or retry to continue.");
    }
    private void renderTasks() {
        final String reviewedThread = tasks.optString("threadId", "");

        JSONArray values = list(tasks, "rows");
        if (tasks.optBoolean("known") && values.length() == 0) note("No background tasks", "The engine has not reported a background command for this conversation.");
        for (int i = 0; i < values.length(); i++) {
            JSONObject task = values.optJSONObject(i); if (task == null) continue;
            LinearLayout card = card(); card.addView(text(task.optString("command", "Background command"), 14, DeskStyle.TEXT));
            card.addView(text(task.optString("cwd"), 12, DeskStyle.MUTED), margins(5, 5));
            String usage = "";
            if (task.opt("cpuPercent") instanceof Number) usage += String.format(Locale.ROOT, "CPU %.1f%%", task.optDouble("cpuPercent"));
            if (task.opt("rssKb") instanceof Number) usage += (usage.isEmpty() ? "" : " · ") + String.format(Locale.ROOT, "RAM %.1f MB", task.optDouble("rssKb") / 1024d);
            if (!usage.isEmpty()) card.addView(text(usage, 12, DeskStyle.MUTED));
            card.addView(button("Stop task", false, v -> confirm("Stop this background task?", task.optString("command") + "\n\nThis terminates the engine-reported command. Any unfinished work may remain incomplete.", () -> control("tasks_terminate", object("threadId", reviewedThread, "processId", task.optString("processId"), "confirmed", true)))), margins(8, 0));
            body.addView(card, margins(0, 10));
        }
        if (tasks.optBoolean("hasMore")) body.addView(button("Load more", false, v -> { if (control("tasks_more", new JSONObject())) expanded = true; }));
        if (tasks.optBoolean("known") && values.length() > 0 && !tasks.optBoolean("hasMore") && !tasks.optBoolean("truncated"))
            body.addView(button("Stop all tasks", false, v -> confirm("Stop all background tasks?", "This stops every background command in the current Codex conversation. Review the list above first.", () -> control("tasks_clean", object("threadId", reviewedThread, "confirmed", true)))));
        if (tasks.optBoolean("truncated")) note("Task list truncated", "Stop tasks individually. Stop all is available after the complete list loads.");
    }
    private void actions(JSONObject item) {
        if (!safe()) return; String id = item.optString("id"), name = item.optString("name", "Conversation");
        boolean isArchived = item.optBoolean("archived"), active = usable() && id.equals(state.optString("threadId"));
        String[] labels = {active ? "Return to this conversation" : isArchived ? "Restore conversation" : "Open conversation", "Rename", isArchived ? "Restore from archive" : "Archive", "Review and share text", "Delete from Codex"};
        dialog().setTitle(name).setItems(labels, (dialog, which) -> {
            if (!safe()) return;
            if (which == 0) {
                if (active) { finish(); return; }
                if (isArchived) confirm("Restore this conversation?", "The chat will return to the Recent list, where you can open it.", () -> session("unarchive", object("threadId", id, "confirmed", true)));
                else if ((!usable() || historyIdle()) && session("resume", object("threadId", id))) pendingResume = id;
                else if (!historyIdle()) toast("Finish the current task before opening another conversation.");
            } else if (which == 1) rename(item);
            else if (which == 2) {
                confirm(isArchived ? "Restore this conversation?" : "Archive this conversation?", isArchived ? "The chat will return to the Recent list." : "The chat will move to Archived and can be restored later.", () -> session(isArchived ? "unarchive" : "archive", object("threadId", id, "confirmed", true)));
            } else if (which == 3) session("export", object("threadId", id));
            else {
                confirm("Permanently delete from Codex?", "Codex will delete this conversation and its child conversations. Separately saved PocketAgent transcript copies may remain in local Chat history.\n\n" + name, () -> session("delete", object("threadId", id, "confirmed", true)));
            }
        }).setNegativeButton("Cancel", null).show();
    }
    private void manage(JSONObject item, String operation) {
        if (!safe()) return;
        if ("rename".equals(operation)) { rename(item); return; }
        if ("export".equals(operation)) { session("export", object("threadId", item.optString("id"))); return; }
        if ("archive".equals(operation) || "unarchive".equals(operation) || "delete".equals(operation)) {
            String title = "delete".equals(operation) ? "Delete conversation?" : "unarchive".equals(operation) ? "Restore conversation?" : "Archive conversation?";
            String detail = "delete".equals(operation) ? "Codex will permanently delete this conversation and its child conversations. Saved local transcript copies remain on this phone."
                    : "unarchive".equals(operation) ? "Move this chat back to Recent." : "Move this chat to Archived.";
            confirm(title, detail, () -> session(operation, object("threadId", item.optString("id"), "confirmed", true)));
        } else actions(item);
    }
    private void rename(JSONObject item) {
        if (!historyIdle()) { toast("Finish the current task before renaming a conversation."); return; }
        EditText field = new EditText(this); field.setText(item.optString("name")); field.setSingleLine(true); field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
        field.setTextColor(DeskStyle.TEXT); field.setPadding(dp(20), dp(12), dp(20), dp(12));
        AlertDialog dialog = dialog().setTitle("Rename conversation").setView(field).setNegativeButton("Cancel", null).setPositiveButton("Save", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!safe() || !historyIdle()) return;
            try { JSONObject params = CodexSessionControls.renameParams(item.optString("id"), field.getText().toString()); if (session("rename", params)) dialog.dismiss(); }
            catch (Exception invalid) { field.setError(invalid.getMessage()); }
        });
    }
    private void reviewShare() {
        if (!safe()) return; String text = sessions.optString("shareText"), title = sessions.optString("shareTitle", "Conversation"), shareId = sessions.optString("shareId");
        ScrollView scroll = new ScrollView(this); TextView preview = text(text, 14, DeskStyle.TEXT); preview.setPadding(dp(20), dp(12), dp(20), dp(12)); preview.setTextIsSelectable(true); scroll.addView(preview);
        AlertDialog dialog = dialog().setTitle(sessions.optBoolean("shareTruncated") ? "Review conversation excerpt" : "Review before sharing").setView(scroll)
            .setNegativeButton("Close", (d, w) -> session("dismiss_share", new JSONObject())).setPositiveButton("Share text…", null).show();
        scroll.getLayoutParams().height = Math.min(dp(420), (int) (getResources().getDisplayMetrics().heightPixels * .6f)); scroll.requestLayout();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!safe() || !shareId.equals(child(AgentService.snapshot(), "sessions").optString("shareId"))) return;
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text);
            try { startActivity(Intent.createChooser(share, "Share conversation text")); dialog.dismiss(); session("dismiss_share", new JSONObject()); }
            catch (Exception unavailable) { toast("No sharing app is available."); }
        });
    }
    private void refreshList() { expanded = false; refreshDue = true; refreshAutomatically(); }
    private void details() {
        if (!safe()) return;
        String content = section == 2
                ? "Background commands reported by this Codex conversation. Android can stop work if the app disconnects or resources run low. Updates pause while you review task actions or browse additional pages."
                : sessions.optString("scopeNote", "Local Codex history for this project on this phone. The engine does not report which account originally created each conversation.")
                    + " Updates pause while you browse additional pages or review conversation actions.";
        dialog().setTitle(section == 2 ? "Tasks" : "History").setMessage(content).setPositiveButton("Done", null).show();
    }
    private boolean session(String operation, JSONObject payload) { return request(AgentService.ACTION_SESSION, operation, payload); }
    private boolean control(String operation, JSONObject payload) { return request(AgentService.ACTION_CONTROL, operation, payload); }
    private boolean request(String action, String operation, JSONObject payload) {
        if (!safe()) return false;
        boolean reconnectResume = action.equals(AgentService.ACTION_SESSION) && "resume".equals(operation) && !usable();
        if (!usable() && !reconnectResume) { toast("Reconnect Codex to manage this chat."); return false; }
        if (!operation.equals("dismiss_share") && (!reads.available(SystemClock.elapsedRealtime()) || sessions.optBoolean("loading") || tasks.optBoolean("loading"))) return false;
        if (action.equals(AgentService.ACTION_SESSION) && !operation.equals("refresh") && !operation.equals("more") && !operation.equals("export") && !operation.equals("dismiss_share") && !historyIdle() && !reconnectResume) { toast("Finish the current task before changing conversations."); return false; }
        try { startForegroundService(new Intent(this, AgentService.class).setAction(action).putExtra(AgentService.EXTRA_PROVIDER, "codex").putExtra(AgentService.EXTRA_PROJECT, project).putExtra(AgentService.EXTRA_OPERATION, operation).putExtra(AgentService.EXTRA_PAYLOAD, payload.toString())); reads.attempted(SystemClock.elapsedRealtime()); main.postDelayed(this::refreshState, 250); return true; }
        catch (Exception error) { reads.failed(SystemClock.elapsedRealtime()); if (!automaticRequest) toast(error.getMessage()); return false; }
    }
    private void confirm(String title, String message, Runnable yes) {
        if (!safe()) return;
        dialog().setTitle(title).setMessage(message).setNegativeButton("Cancel", null).setPositiveButton("Confirm", (d, w) -> { if (safe()) yes.run(); }).show();
    }
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, DeskStyle.dialogTheme(this)) {
            @Override public AlertDialog show() {
                AlertDialog dialog = super.create();
                if (dialog.getWindow() != null) { if (AppLock.enabled(SessionsActivity.this)) dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE); dialog.getWindow().setBackgroundDrawable(DeskStyle.card(SessionsActivity.this)); }
                dialogs.add(dialog); dialog.setOnDismissListener(d -> dialogs.remove(dialog)); dialog.show(); return dialog;
            }
        };
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout card() { LinearLayout card = column(); card.setPadding(dp(16), dp(14), dp(16), dp(14)); card.setBackground(DeskStyle.card(this)); return card; }
    private void note(String title, String detail) { LinearLayout card = card(); card.addView(text(title, 15, DeskStyle.TEXT)); card.addView(text(detail, 13, DeskStyle.MUTED), margins(5, 0)); body.addView(card, margins(0, 10)); }
    private TextView text(String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextColor(color); view.setTextSize(size); view.setLineSpacing(dp(2), 1.03f); return view; }
    private Button button(String title, boolean primary, View.OnClickListener listener) { Button button = new Button(this); button.setText(title); button.setAllCaps(false); button.setTextSize(13); button.setTextColor(primary ? DeskStyle.PRIMARY_TEXT : DeskStyle.TEXT); button.setMinHeight(dp(48)); button.setPadding(dp(12), dp(7), dp(12), dp(7)); button.setBackground(primary ? DeskStyle.primary(this) : Ui.tappable(this, DeskStyle.field(this), true)); button.setStateListAnimator(null); button.setOnClickListener(v -> { if (safe()) listener.onClick(v); }); return button; }
    private LinearLayout.LayoutParams margins(int top, int bottom) { LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(-1, -2); value.setMargins(0, dp(top), 0, dp(bottom)); return value; }
    private int dp(int value) { return Ui.dp(this, value); }
    private void toast(String message) { if (message != null && safe()) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private static String date(long seconds) { return seconds <= 0 ? "Updated time unavailable" : java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(new Date(seconds * 1000L)); }
}
