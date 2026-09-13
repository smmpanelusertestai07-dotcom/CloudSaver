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
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Real local task activity, files and preview; this Activity never starts a desktop or an agent. */
public final class WorkspaceActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<AlertDialog> dialogs = new HashSet<>();
    private final String clientId = UUID.randomUUID().toString();
    private FrameLayout lockRoot;
    private LinearLayout root, body;
    private TextView status;
    private ScrollView scroll;
    private File directory;
    private String project = "", folder = "", theme = "", signature = "", fileError = "";
    private JSONObject state = new JSONObject(), preview = new JSONObject(), git = new JSONObject();
    private List<WorkspaceTools.Entry> files = new ArrayList<>();
    private boolean resumed, registered, destroyed, fileReading, delayed;
    private int section, readGeneration, fileLimit = 80;
    private long filesReadAt, tasksReadAt, presenceAt;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { schedule(); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed || destroyed) return;
            if (!AppLock.isLocked(WorkspaceActivity.this)) {
                update(); readTasks(false); if (section == 1) readFiles(false);
                if (SystemClock.elapsedRealtime() - presenceAt >= 45000) presence(true);
            }
            main.postDelayed(this, 3000);
        }
    };
    static void open(Activity activity, String project) {
        activity.startActivity(new Intent(activity, WorkspaceActivity.class).putExtra(AgentService.EXTRA_PROJECT, project));
    }
    @Override protected void onCreate(Bundle saved) {
        DeskStyle.apply(this); super.onCreate(saved); theme = DeskStyle.themeSignature(this);
        try {
            project = AgentProtocol.project(getIntent().getStringExtra(AgentService.EXTRA_PROJECT));
            directory = WorkspaceTools.safeChild(ContainerRuntime.workspaceRoot(this), project);
            WorkspaceTools.guestPath(this, directory);
        } catch (Exception invalid) { finish(); return; }
        if (saved != null) { section = Math.max(0, Math.min(2, saved.getInt("section"))); folder = saved.getString("folder", ""); }
        build(); update();
    }
    @Override protected void onStart() {
        super.onStart(); IntentFilter filter = new IntentFilter(AgentService.EVENT); filter.addAction(PreviewService.EVENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(receiver, filter);
        registered = true;
    }
    @Override protected void onResume() {
        super.onResume(); if (!theme.equals(DeskStyle.themeSignature(this))) { recreate(); return; }
        resumed = true; AppLock.applyWindowSecurity(this);
        if (AppLock.isLocked(this)) AppLock.show(this, lockRoot, this::resumeWork); else resumeWork();
    }
    private void resumeWork() {
        if (!resumed || AppLock.isLocked(this)) return;
        update(); presence(true); readTasks(false); if (section == 1) readFiles(true);
        main.removeCallbacks(tick); main.postDelayed(tick, 3000);
    }
    @Override protected void onPause() { presence(false); resumed = false; main.removeCallbacks(tick); super.onPause(); }
    @Override protected void onStop() {
        if (registered) { unregisterReceiver(receiver); registered = false; }
        if (AppLock.enabled(this)) for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        super.onStop();
    }
    @Override protected void onDestroy() { destroyed = true; readGeneration++; main.removeCallbacksAndMessages(null); io.shutdownNow(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle saved) { saved.putInt("section", section); saved.putString("folder", folder); super.onSaveInstanceState(saved); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); AppLock.handleResult(this, lockRoot, request, result, this::resumeWork);
    }
    private void presence(boolean active) {
        JSONObject current = AgentService.snapshot();
        if (!WorkspaceViewState.matches(current, project) || !current.optBoolean("connected")) return;
        if (active && (!resumed || AppLock.isLocked(this))) return;
        try {
            startService(scoped(AgentService.ACTION_CLIENT, current).putExtra(AgentService.EXTRA_CLIENT_ID, clientId).putExtra(AgentService.EXTRA_ACTIVE, active));
            presenceAt = SystemClock.elapsedRealtime();
        } catch (RuntimeException ignored) { }
    }
    private void build() {
        root = column(); root.setBackground(DeskStyle.background(this)); lockRoot = new FrameLayout(this); lockRoot.addView(root); setContentView(lockRoot);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                root.setPadding(dp(16) + bars.left, dp(8) + bars.top, dp(16) + bars.right, dp(8) + bars.bottom);
            } else root.setPadding(dp(16), dp(8) + insets.getSystemWindowInsetTop(), dp(16), dp(8) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        LinearLayout top = row(); top.addView(button("Back", false, v -> finish()), new LinearLayout.LayoutParams(-2, -2));
        TextView title = text("Live workspace", 21, DeskStyle.TEXT); title.setPadding(dp(12), 0, 0, 0); top.addView(title); root.addView(top);
        root.addView(text(project + " · Ubuntu on this phone", 13, DeskStyle.ACCENT), margin(6));
        status = text("", 13, DeskStyle.MUTED); root.addView(status, margin(8));
        LinearLayout tabs = row(); String[] names = {"Activity", "Changes", "Preview"};
        for (int i = 0; i < names.length; i++) { final int selected = i; tabs.addView(button(names[i], false, v -> {
            section = selected; signature = ""; update(); if (section == 1) readFiles(true);
        }), new LinearLayout.LayoutParams(0, dp(48), 1)); }
        root.addView(tabs, margin(10)); scroll = new ScrollView(this); body = column(); scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    private void schedule() {
        if (!resumed || delayed) return; delayed = true;
        main.postDelayed(() -> { delayed = false; if (resumed && !AppLock.isLocked(this)) update(); }, 180);
    }
    private void update() {
        if (destroyed) return;
        state = AgentService.snapshot(); preview = PreviewService.snapshot();
        boolean matching = WorkspaceViewState.matches(state, project);
        status.setText(matching ? clean(state.optString("status", "Agent idle"), 400) : "Open this project's chat to connect an agent. Files and local preview remain available.");
        String next = section + "|" + (section == 0 ? WorkspaceViewState.output(state, project).toString() + WorkspaceViewState.tasks(state, project).toString() + state.optBoolean("busy") + state.optInt("pendingApprovals") : section == 1 ? git.toString() + folder + fileError + filesReadAt : preview.toString())
                + "|" + LinuxService.isDesktopRunning();
        if (next.equals(signature)) return; signature = next;
        int y = scroll.getScrollY(); body.removeAllViews();
        if (section == 0) renderActivity(); else if (section == 1) renderChanges(); else renderPreview();
        scroll.post(() -> { if (!destroyed) scroll.scrollTo(0, y); });
    }
    private void renderActivity() {
        boolean matching = WorkspaceViewState.matches(state, project);
        LinearLayout task = card(matching && state.optBoolean("busy") ? "Agent working" : "Task activity");
        task.addView(text("Actual tool activity and command output from the project's agent. Output is read-only here; return to chat to give instructions or answer approvals.", 14, DeskStyle.MUTED), margin(8));
        if (matching && state.optInt("pendingApprovals") > 0) task.addView(text("Waiting for your approval in chat", 15, DeskStyle.ACCENT), margin(8));
        if (matching && state.optBoolean("busy")) task.addView(button("Stop current turn", false, v -> stopTurn()));
        task.addView(button("Return to chat", true, v -> finish())); body.addView(task, margin(12));
        JSONObject tasks = WorkspaceViewState.tasks(state, project); JSONArray rows = list(tasks, "rows");
        LinearLayout background = card("Background tasks");
        background.addView(text(!WorkspaceViewState.canReadTasks(state, project) ? "Available when this project's Codex conversation is connected." : tasks.optBoolean("known") ? rows.length() + " tasks reported by Codex" : tasks.optBoolean("loading") ? "Reading tasks…" : tasks.optString("error", "Waiting for the engine's task list…"), 14, DeskStyle.MUTED), margin(8));
        for (int i = 0; i < Math.min(8, rows.length()); i++) { JSONObject value = rows.optJSONObject(i); if (value != null) background.addView(selectable(value.optString("command") + "\n" + value.optString("cwd")), margin(8)); }
        if (!tasks.optString("error").isEmpty()) background.addView(button("Retry task status", false, v -> readTasks(true)));
        background.addView(button("Manage reported tasks", false, v -> { if (!AppLock.isLocked(this)) SessionsActivity.open(this, project, true); })); body.addView(background, margin(12));
        JSONArray output = WorkspaceViewState.output(state, project);
        if (output.length() == 0) body.addView(text("No activity reported for this project yet.", 14, DeskStyle.MUTED), margin(12));
        for (int i = output.length() - 1; i >= 0; i--) {
            JSONObject value = output.optJSONObject(i); LinearLayout entry = card(value.optString("role").equals("tool") ? "Tool output" : value.optString("role").equals("user") ? "Your task" : value.optString("role").equals("assistant") ? "Agent" : "Progress");
            entry.addView(selectable(value.optString("text")), margin(8)); body.addView(entry, margin(12));
        }
    }
    private void stopTurn() {
        JSONObject reviewed = AgentService.snapshot();
        if (!WorkspaceViewState.matches(reviewed, project) || !reviewed.optBoolean("busy")) return;
        confirm("Stop the current turn?", "Request the agent to stop this turn. Completed file changes remain in your project.", () -> {
            JSONObject current = AgentService.snapshot();
            if (!WorkspaceViewState.matches(current, project) || !current.optBoolean("busy")
                    || !reviewed.optString("threadId").equals(current.optString("threadId"))
                    || !reviewed.optString("provider").equals(current.optString("provider"))) {
                Toast.makeText(this, "The active conversation changed. Review its current task first.", Toast.LENGTH_SHORT).show(); update(); return;
            }
            startService(scoped(AgentService.ACTION_CANCEL, current));
        });
    }
    private void readTasks(boolean force) {
        if (!resumed || AppLock.isLocked(this)) return; JSONObject current = AgentService.snapshot();
        JSONObject tasks = WorkspaceViewState.tasks(current, project);
        if (!WorkspaceViewState.canReadTasks(current, project) || tasks.optBoolean("loading")) return;
        long now = SystemClock.elapsedRealtime(); if (!force && (tasksReadAt != 0 && now - tasksReadAt < 15000 || !tasks.optString("error").isEmpty())) return;
        tasksReadAt = now;
        try { startService(scoped(AgentService.ACTION_CONTROL, current).putExtra(AgentService.EXTRA_OPERATION, "tasks_refresh").putExtra(AgentService.EXTRA_PAYLOAD, "{}")); }
        catch (RuntimeException failed) { status.setText("Could not request task status. Return to chat and reconnect."); }
    }
    private void readFiles(boolean force) {
        if (!resumed || AppLock.isLocked(this) || fileReading || WorkspaceTools.isProjectOperationBusy()) return;
        long now = SystemClock.elapsedRealtime(); if (!force && (now - filesReadAt < 6000 || !fileError.isEmpty())) return;
        fileReading = true; final int own = ++readGeneration; final String path = folder;
        io.execute(() -> {
            JSONObject overview = new JSONObject(); List<WorkspaceTools.Entry> entries = new ArrayList<>(); String error = "";
            try { entries = WorkspaceTools.listFiles(this, directory, path); overview = WorkspaceTools.gitOverview(this, directory); }
            catch (Exception failed) { error = clean(failed.getMessage(), 600); }
            final JSONObject result = overview; final List<WorkspaceTools.Entry> listed = entries; final String failure = error;
            main.post(() -> {
                if (destroyed || own != readGeneration) return; fileReading = false; filesReadAt = SystemClock.elapsedRealtime();
                if (!folder.equals(path)) { readFiles(true); return; }
                git = result; files = listed; fileError = failure; signature = ""; if (resumed && !AppLock.isLocked(this)) update();
            });
        });
    }
    private void renderChanges() {
        LinearLayout changes = card("Working tree");
        changes.addView(text(fileError.isEmpty() ? filesReadAt == 0 ? "Reading project files…" : "Updates automatically while this screen is open. A running agent may change files after this read." : fileError, 13, DeskStyle.MUTED), margin(8));
        if (!fileError.isEmpty()) changes.addView(button("Retry files and changes", false, v -> { fileError = ""; readFiles(true); }));
        if (git.optBoolean("repository")) {
            changes.addView(text("Branch: " + git.optString("branch", "Detached HEAD"), 14, DeskStyle.ACCENT), margin(8));
            changes.addView(selectable(git.optString("status").isEmpty() ? "Working tree clean" : git.optString("status")), margin(8));
            if (!git.optString("diff").isEmpty()) changes.addView(button("Read current diff", false, v -> showText("Current Git diff", git.optString("diff"))));
        } else if (filesReadAt > 0 && fileError.isEmpty()) changes.addView(text("This source folder has no Git history yet. Files are still available below.", 14, DeskStyle.MUTED), margin(8));
        changes.addView(button("Project tools · Git & export", false, v -> { if (!AppLock.isLocked(this)) ProjectToolsActivity.open(this, project); })); body.addView(changes, margin(12));
        LinearLayout listing = card(folder.isEmpty() ? "Project files" : folder);
        if (!folder.isEmpty()) listing.addView(button("← Parent folder", false, v -> { int slash = folder.lastIndexOf('/'); folder = slash < 0 ? "" : folder.substring(0, slash); fileLimit = 80; readFiles(true); }));
        for (WorkspaceTools.Entry value : files.subList(0, Math.min(fileLimit, files.size()))) listing.addView(button((value.directory ? "Folder · " : "File · ") + value.name, false, v -> {
            if (AppLock.isLocked(this)) return;
            if (value.directory) { folder = value.path; fileLimit = 80; readFiles(true); } else readText(value.path);
        }));
        if (files.size() > fileLimit) listing.addView(button("Show more files (" + fileLimit + " / " + files.size() + ")", false, v -> { fileLimit += 80; signature = ""; update(); }));
        body.addView(listing, margin(12));
    }
    private void readText(String path) {
        if (AppLock.isLocked(this)) return;
        io.execute(() -> {
            String value; try { value = WorkspaceTools.readText(this, directory, path); } catch (Exception failure) { value = clean(failure.getMessage(), 600); }
            final String content = value; main.post(() -> { if (!destroyed && resumed && !AppLock.isLocked(this)) showText(path, content); });
        });
    }
    private void renderPreview() {
        LinearLayout card = card("Interact with your project");
        boolean active = WorkspaceViewState.previewMatches(preview, project), starting = project.equals(preview.optString("project")) && preview.optBoolean("starting");
        card.addView(text(active || starting ? preview.optString("message") : "Start a local web preview to tap through the project yourself. Preview opens the real project server on this phone.", 14, DeskStyle.MUTED), margin(8));
        if (active) card.addView(button("Open interactive preview", true, v -> {
            JSONObject now = PreviewService.snapshot(); if (!AppLock.isLocked(this) && WorkspaceViewState.previewMatches(now, project)) PreviewActivity.open(this, now.optInt("port"), now.optString("token"));
        }));
        if (active || starting) card.addView(button("Stop this preview", false, v -> {
            JSONObject now = PreviewService.snapshot(); if (!AppLock.isLocked(this) && project.equals(now.optString("project"))) startService(new Intent(this, PreviewService.class).setAction(PreviewService.ACTION_STOP));
        }));
        else {
            if (preview.optBoolean("running") || preview.optBoolean("starting")) card.addView(text("Another project's preview is active: " + preview.optString("project") + ". Stop it in that project before starting this one.", 14, DeskStyle.ACCENT), margin(8));
            else {
                card.addView(button("Start static web preview", true, v -> startPreview(false)));
                card.addView(button("Start npm dev preview", false, v -> confirm("Run this project's dev script?", "This runs package.json's dev script inside Ubuntu and can execute project code. Dependencies must already be installed. The server is requested on phone localhost; project scripts can change their own listening behavior.", () -> startPreview(true))));
            }
            if (project.equals(preview.optString("project")) && preview.optBoolean("error")) card.addView(text(preview.optString("message"), 14, DeskStyle.ACCENT), margin(8));
        }
        body.addView(card, margin(12));
        if (ContainerRuntime.isInstalled(this) && LinuxService.isDesktopRunning()) {
            LinearLayout desktop = card("Existing Linux desktop");
            desktop.addView(text("Your existing desktop is already running in the same Ubuntu workspace. Open its real screen to use its mouse and keyboard controls. This is separate from the agent's task output.", 14, DeskStyle.MUTED), margin(8));
            desktop.addView(button("Open existing desktop", false, v -> { if (!AppLock.isLocked(this) && ContainerRuntime.isInstalled(this) && LinuxService.isDesktopRunning()) startActivity(new Intent(this, DesktopActivity.class)); }));
            body.addView(desktop, margin(12));
        }
    }
    private void startPreview(boolean npm) {
        if (AppLock.isLocked(this)) return;
        if (WorkspaceTools.isProjectOperationBusy()) { Toast.makeText(this, "Wait for the project operation to finish.", Toast.LENGTH_SHORT).show(); return; }
        JSONObject current = PreviewService.snapshot(); if (current.optBoolean("running") || current.optBoolean("starting")) { update(); return; }
        try { startForegroundService(new Intent(this, PreviewService.class).setAction(PreviewService.ACTION_START).putExtra(PreviewService.EXTRA_PROJECT, project).putExtra(PreviewService.EXTRA_NPM, npm)); }
        catch (RuntimeException failed) { showText("Preview", "Android could not start the preview. Keep PocketAgent open and retry."); }
    }
    private Intent scoped(String action, JSONObject current) { return new Intent(this, AgentService.class).setAction(action).putExtra(AgentService.EXTRA_PROJECT, project).putExtra(AgentService.EXTRA_PROVIDER, current.optString("provider")); }
    private void confirm(String title, String detail, Runnable action) { if (!AppLock.isLocked(this)) dialog().setTitle(title).setMessage(detail).setPositiveButton("Continue", (d, w) -> { if (!AppLock.isLocked(this)) action.run(); }).setNegativeButton("Cancel", null).show(); }
    private void showText(String title, String content) { if (AppLock.isLocked(this)) return; ScrollView view = new ScrollView(this); TextView value = selectable(clean(content, 60000)); value.setPadding(dp(18), dp(12), dp(18), dp(12)); view.addView(value); dialog().setTitle(title).setView(view).setPositiveButton("Close", null).show(); }
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, DeskStyle.dialogTheme(this)) { @Override public AlertDialog show() {
            AlertDialog value = super.create(); if (AppLock.enabled(WorkspaceActivity.this) && value.getWindow() != null) value.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            dialogs.add(value); value.setOnDismissListener(d -> dialogs.remove(value)); value.show(); return value;
        }};
    }
    private int dp(int value) { return Ui.dp(this, value); }
    private LinearLayout column() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private LinearLayout row() { LinearLayout value = new LinearLayout(this); value.setGravity(android.view.Gravity.CENTER_VERTICAL); return value; }
    private TextView text(String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(dp(3), 1); return view; }
    private TextView selectable(String value) { TextView view = text(value, 13, DeskStyle.TEXT); view.setTextIsSelectable(true); view.setTypeface(Typeface.MONOSPACE); return view; }
    private LinearLayout card(String name) { LinearLayout value = column(); value.setPadding(dp(15), dp(14), dp(15), dp(14)); value.setBackground(DeskStyle.card(this)); TextView title = text(name, 18, DeskStyle.TEXT); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); value.addView(title); return value; }
    private Button button(String label, boolean primary, View.OnClickListener action) { Button value = new Button(this); value.setText(label); value.setAllCaps(false); value.setTextSize(14); value.setTextColor(primary ? DeskStyle.PRIMARY_TEXT : DeskStyle.TEXT); value.setBackground(primary ? DeskStyle.primary(this) : DeskStyle.field(this)); value.setMinHeight(dp(48)); value.setOnClickListener(action); value.setLayoutParams(margin(8)); return value; }
    private LinearLayout.LayoutParams margin(int top) { LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(-1, -2); value.setMargins(0, dp(top), 0, 0); return value; }
}
