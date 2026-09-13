package com.pocketagent.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
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
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native source import, file management and explicit Git actions. No terminal or arbitrary command field. */
public final class ProjectToolsActivity extends Activity {
    static final int REQUEST = 4810;
    static final String EXTRA_PROJECT = "project";
    private static final int IMPORT = 4811, EXPORT = 4812;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<AlertDialog> dialogs = new HashSet<>();
    private FrameLayout lockShell;
    private LinearLayout root, body;
    private TextView status;
    private ProgressBar progress;
    private File project;
    private JSONObject overview = new JSONObject();
    private boolean busy, loaded, destroyed, githubRegistered, githubPending, resumed, readingGit;
    private long gitReadAt, githubReadAt;
    private String gitReadError = "";
    private final Runnable automaticStatus = () -> autoStatus();
    private TextView githubStatus, githubCode;
    private Button githubPrimary, githubRefresh, githubLogout, githubCancel, githubCopy;
    private final BroadcastReceiver githubReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (GitHubAuthService.EVENT.equals(intent.getAction())) { githubPending = false; renderGithub(); }
            queueStatus(600);
        }
    };
    private Uri pendingUri;
    private int pendingAction;
    private Runnable pendingCompletion;
    private String folder = "", pendingImportName = "";
    private interface Job<T> { T run() throws Exception; }
    private interface Done<T> { void accept(T value); }

    static void open(Activity activity, String project) {
        activity.startActivityForResult(new Intent(activity, ProjectToolsActivity.class)
                .putExtra(EXTRA_PROJECT, project == null ? "" : project), REQUEST);
    }
    @Override public void onCreate(Bundle saved) {
        DeskStyle.apply(this);
        super.onCreate(saved);
        String name = getIntent().getStringExtra(EXTRA_PROJECT);
        if (name != null && name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            project = new File(ContainerRuntime.workspaceRoot(this), name);
        if (saved != null) {
            pendingImportName = saved.getString("import_name", ""); folder = saved.getString("folder", "");
            String document = saved.getString("pending_document", "");
            if (!document.isEmpty()) { pendingUri = Uri.parse(document); pendingAction = saved.getInt("pending_action"); }
        }
        build(); render();
    }
    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(GitHubAuthService.EVENT); filter.addAction(AgentService.EVENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(githubReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(githubReceiver, filter);
        githubRegistered = true; githubPending = false; renderGithub();
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true; AppLock.applyWindowSecurity(this);
        if (AppLock.isLocked(this)) AppLock.show(this, lockShell, this::resumeWork);
        else resumeWork();
    }
    private void resumeWork() {
        if (AppLock.isLocked(this)) return;
        if (pendingCompletion != null) { Runnable completed = pendingCompletion; pendingCompletion = null; completed.run(); }
        else if (pendingUri != null) { Uri uri = pendingUri; int action = pendingAction; pendingUri = null; completePicker(action, uri); }
        else queueStatus(350);
    }
    @Override protected void onPause() { resumed = false; main.removeCallbacks(automaticStatus); super.onPause(); }
    @Override protected void onStop() {
        if (githubRegistered) { unregisterReceiver(githubReceiver); githubRegistered = false; }
        if (AppLock.enabled(this)) for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle saved) {
        saved.putString("import_name", pendingImportName); saved.putString("folder", folder);
        if (pendingUri != null) { saved.putString("pending_document", pendingUri.toString()); saved.putInt("pending_action", pendingAction); }
        super.onSaveInstanceState(saved);
    }
    @Override protected void onDestroy() {
        destroyed = true; main.removeCallbacksAndMessages(null); io.shutdownNow(); super.onDestroy();
    }
    @Override public void onBackPressed() { leave(); }
    private void leave() {
        if (!busy) { finish(); return; }
        dialog().setTitle("Stop project operation?").setMessage("The current operation will be cancelled. A Git request already accepted by the remote server may have completed.")
                .setPositiveButton("Stop and close", (d, w) -> finish()).setNegativeButton("Keep working", null).show();
    }

    private void build() {
        root = column(); root.setBackground(DeskStyle.background(this));
        lockShell = new FrameLayout(this); lockShell.addView(root); setContentView(lockShell); AppLock.applyWindowSecurity(this);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                root.setPadding(dp(16) + bars.left, dp(8) + bars.top, dp(16) + bars.right, dp(8) + Math.max(bars.bottom, ime.bottom));
            } else root.setPadding(dp(16) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(), dp(16) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        LinearLayout top = row(); top.addView(button("Back", false, v -> leave()), new LinearLayout.LayoutParams(-2, -2));
        TextView title = text("Project tools", 21, DeskStyle.TEXT); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); title.setPadding(dp(14), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(top);
        root.addView(text(project == null ? "Import source and start a project" : project.getName(), 13, DeskStyle.ACCENT), space(4, 12));
        status = text("", 13, DeskStyle.MUTED); status.setTextIsSelectable(true); root.addView(status, space(0, 8));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true); progress.setVisibility(View.GONE); root.addView(progress);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); body = column(); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void render() {
        body.removeAllViews();
        LinearLayout github = card("GitHub account");
        github.addView(text("Connect through the official GitHub CLI, then clone private repositories and push with your account permissions. This sign-in is separate from Codex and ChatGPT connectors.", 14, DeskStyle.MUTED), space(7, 10));
        githubStatus = selectable(""); github.addView(githubStatus, space(0, 8));
        githubCode = text("", 24, DeskStyle.ACCENT); githubCode.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); githubCode.setTextIsSelectable(true); github.addView(githubCode, space(4, 6));
        githubPrimary = button("Check GitHub setup", true, v -> githubPrimaryAction()); github.addView(githubPrimary);
        githubCopy = button("Copy one-time code", false, v -> {
            if (AppLock.isLocked(this)) return;
            String code = GitHubAuthService.snapshot().optString("code");
            if (code.matches("[A-Z0-9]{4}-[A-Z0-9]{4}")) {
                android.content.ClipData clip = android.content.ClipData.newPlainText("GitHub one-time code", code);
                android.os.PersistableBundle sensitive = new android.os.PersistableBundle(); sensitive.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(sensitive);
                ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip); toast("One-time code copied");
            }
        }); github.addView(githubCopy);
        githubRefresh = button("Retry GitHub status", false, v -> startGithub("status", "")); github.addView(githubRefresh);
        githubLogout = button("Sign out of GitHub locally", false, v -> githubLogout()); github.addView(githubLogout);
        githubCancel = button("Cancel GitHub operation", false, v -> startGithub("cancel", "")); github.addView(githubCancel);
        github.addView(text("Sign-in continues while you use the browser. The official CLI keeps its credentials inside this app's private Ubuntu storage; on Ubuntu this can be a plain config file. Local sign-out leaves projects intact and does not revoke GitHub authorization globally.", 12, DeskStyle.MUTED), space(12, 0));
        body.addView(github, space(0, 14)); renderGithub();
        LinearLayout transfer = card("Bring your project");
        transfer.addView(text("Import a source ZIP into a new project, or clone an HTTPS Git repository. Existing project files are never replaced by import.", 14, DeskStyle.MUTED), space(7, 12));
        transfer.addView(button("Import source ZIP", true, v -> importDialog()));
        transfer.addView(button("Clone GitHub / HTTPS repository", false, v -> cloneDialog()));
        transfer.addView(text("ZIP limits: 64 MB compressed, 128 MB expanded, 32 MB per file. Folder layout is kept. Export ordinary source without .git history or links.", 12, DeskStyle.MUTED), space(8, 0));
        body.addView(transfer, space(0, 14));
        if (project == null) return;
        LinearLayout source = card("Files & source");
        source.addView(button("Browse, rename or delete files", false, v -> browse(folder)));
        source.addView(button("Export source ZIP", false, v -> exportDialog()));
        source.addView(text("Source export skips Git history, dependencies and conventional credential files. Review your source before sharing; file contents are not a full secret scan.", 12, DeskStyle.MUTED), space(8, 0));
        body.addView(source, space(0, 14));
        LinearLayout git = card("Git & GitHub");
        if (!gitReadError.isEmpty()) {
            git.addView(text(gitReadError, 13, DeskStyle.ACCENT), space(8, 8));
            git.addView(button("Retry Git status", false, v -> refresh()));
        }
        if (!overview.optBoolean("repository")) {
            git.addView(text("This source folder has no Git repository yet.", 14, DeskStyle.MUTED), space(8, 10));
            git.addView(button("Initialize Git locally", false, v -> confirm("Initialize Git?", "Create local Git history for this project. Nothing is sent online.",
                    () -> work("Initializing Git…", () -> WorkspaceTools.initializeGit(this, project), value -> { status.setText(value); refresh(); }))));
        } else {
            git.addView(text("Branch: " + (overview.optString("branch").isEmpty() ? "Detached HEAD" : overview.optString("branch")), 15, DeskStyle.TEXT), space(10, 6));
            git.addView(selectable(overview.optString("remote").isEmpty() ? "Origin: not set" : "Origin: " + overview.optString("remote")), space(0, 8));
            if (!overview.optString("remoteNote").isEmpty()) git.addView(text(overview.optString("remoteNote"), 13, DeskStyle.ACCENT), space(0, 8));
            String changes = overview.optString("status");
            git.addView(selectable(changes.isEmpty() ? "Working tree clean" : changes), space(0, 10));
            git.addView(button("Review changes", false, v -> showText("Git changes", overview.optString("diff").isEmpty() ? "No text changes to display." : overview.optString("diff"))));
            git.addView(button("Set HTTPS origin", false, v -> remoteDialog()));
            git.addView(button("Commit all changes locally", false, v -> commitDialog()));
            git.addView(button("Pull · fast-forward only", false, v -> sync(false)));
            git.addView(button("Push this branch", true, v -> sync(true)));
        }
        git.addView(text("Connect GitHub above for private HTTPS clone, pull and push on github.com. Public clone works without sign-in. Other HTTPS hosts use their existing Git credentials. Set origin to a repository you can access; these actions do not create or publish a repository automatically.", 13, DeskStyle.MUTED), space(12, 0));
        body.addView(git, space(0, 18));
    }

    private void renderGithub() {
        if (githubStatus == null || destroyed) return;
        JSONObject state = GitHubAuthService.snapshot();
        boolean running = githubPending || state.optBoolean("busy"), known = state.has("installed"), installed = state.optBoolean("installed");
        boolean device = running && "https://github.com/login/device".equals(state.optString("url")) && state.optString("code").matches("[A-Z0-9]{4}-[A-Z0-9]{4}");
        String detail = state.optString("status", "GitHub setup and account status are checked automatically when the workspace is available.");
        if (!state.optString("version").isEmpty()) detail += "\n" + state.optString("version");
        if (state.optBoolean("connected") && !state.optString("account").isEmpty()) detail += "\nConnected: @" + state.optString("account");
        githubStatus.setText(detail); githubStatus.setTextColor(state.optBoolean("error") ? DeskStyle.ACCENT : DeskStyle.TEXT);
        githubCode.setText(device ? state.optString("code") : ""); githubCode.setVisibility(device ? View.VISIBLE : View.GONE);
        githubPrimary.setText(device ? "Continue at github.com" : running ? "GitHub operation running…" : !known ? "Checking GitHub setup…" : !installed ? "Install GitHub CLI" : state.optBoolean("connected") ? "Connect another GitHub account" : "Connect GitHub account");
        githubPrimary.setEnabled((!running && known) || device);
        githubCopy.setVisibility(device ? View.VISIBLE : View.GONE);
        githubRefresh.setVisibility(!running && (state.optBoolean("error") || installed && !state.optBoolean("connected")) ? View.VISIBLE : View.GONE);
        githubLogout.setVisibility(installed && !running ? View.VISIBLE : View.GONE);
        githubCancel.setVisibility(running ? View.VISIBLE : View.GONE);
    }
    private void githubPrimaryAction() {
        if (AppLock.isLocked(this)) return;
        JSONObject state = GitHubAuthService.snapshot();
        if (state.optBoolean("busy") && "https://github.com/login/device".equals(state.optString("url"))) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/login/device")).addCategory(Intent.CATEGORY_BROWSABLE)); }
            catch (RuntimeException blocked) { showText("GitHub sign-in", "Android could not open a browser. Use github.com/login/device with the displayed one-time code."); }
            return;
        }
        if (!state.has("installed")) { startGithub("status", ""); return; }
        if (!state.optBoolean("installed")) {
            if (!ready()) return;
            dialog().setTitle("Install official GitHub CLI?").setMessage("Download GitHub CLI from Ubuntu's signed package repository into this app's Ubuntu workspace. A network connection and free storage are needed. No account is connected until you complete GitHub's browser sign-in.")
                    .setPositiveButton("Install GitHub CLI", (d, w) -> startGithub("install", "")).setNegativeButton("Cancel", null).show();
        } else startGithub("login", "");
    }
    private void githubLogout() {
        if (!ready()) return;
        EditText username = input("GitHub username (optional if one account)"); username.setText(GitHubAuthService.snapshot().optString("account"));
        dialog().setTitle("Sign out of GitHub locally?").setMessage("Remove this account from the official CLI on this phone. Your projects and Codex sign-in are kept. GitHub authorization can be revoked separately in GitHub Settings → Applications.")
                .setView(padded(username)).setPositiveButton("Sign out", (d, w) -> startGithub("logout", username.getText().toString().trim())).setNegativeButton("Cancel", null).show();
    }
    private void startGithub(String action, String user) {
        if (AppLock.isLocked(this)) return;
        if (!"cancel".equals(action) && (githubPending || !ready())) return;
        Intent intent = new Intent(this, GitHubAuthService.class).putExtra(GitHubAuthService.EXTRA_ACTION, action).putExtra(GitHubAuthService.EXTRA_USER, user);
        try {
            githubReadAt = SystemClock.elapsedRealtime();
            githubPending = true; renderGithub(); startForegroundService(intent);
        } catch (RuntimeException blocked) {
            githubPending = false; renderGithub(); showText("GitHub connection", "Android could not start GitHub setup. Keep PocketAgent open and retry.");
        }
    }

    private boolean ready() {
        if (AppLock.isLocked(this)) return false;
        if (busy || WorkspaceTools.isProjectOperationBusy()) { toast("Wait for the current project operation."); return false; }
        if (!ContainerRuntime.isWorkspaceInstalled(this)) { showText("Workspace needed", "Finish Ubuntu workspace setup on the main screen first."); return false; }
        return true;
    }
    private void refresh() {
        if (!ready()) return;
        if (project == null) { loaded = true; return; }
        readingGit = true; gitReadAt = SystemClock.elapsedRealtime(); gitReadError = "";
        work("Reading project status…", () -> WorkspaceTools.gitOverview(this, project), value -> {
            overview = value; loaded = true; status.setText("Project status updated"); render();
        });
    }
    private void queueStatus(long delay) {
        main.removeCallbacks(automaticStatus);
        if (resumed && !destroyed) main.postDelayed(automaticStatus, delay);
    }
    private void autoStatus() {
        if (!resumed || destroyed || AppLock.isLocked(this)) return;
        if (!ContainerRuntime.isWorkspaceInstalled(this)) { status.setText("Finish Ubuntu workspace setup to use project tools."); return; }
        if (busy || githubPending || GitHubAuthService.snapshot().optBoolean("busy") || WorkspaceTools.isProjectOperationBusy()) { queueStatus(1500); return; }
        long now = SystemClock.elapsedRealtime();
        if (project != null && gitReadError.isEmpty() && (gitReadAt == 0 || now - gitReadAt >= 10000)) { refresh(); return; }
        JSONObject agent = AgentService.snapshot();
        boolean engineWorking = agent.optBoolean("busy") || agent.optBoolean("installing") || agent.optBoolean("connecting") || agent.optBoolean("authenticating") || agent.optBoolean("sessionOpening");
        if (githubReadAt == 0 || now - githubReadAt >= 60000) {
            if (engineWorking || LinuxService.isBusy() || LinuxService.isInstalling()) { queueStatus(2500); return; }
            startGithub("status", "");
        }
        queueStatus(10000);
    }
    private void importDialog() {
        if (!ready()) return;
        EditText name = input("New project name");
        dialog().setTitle("Import source ZIP").setMessage("Choose a new project name. Then select the ZIP from your phone.")
                .setView(padded(name)).setPositiveButton("Choose ZIP", (d, w) -> {
                    pendingImportName = name.getText().toString().trim();
                    if (!validProjectName(pendingImportName)) { showText("Project name", "Use 1–64 letters, numbers, dots, hyphens or underscores; start with a letter or number."); return; }
                    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE);
                    pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
                    try { startActivityForResult(pick, IMPORT); } catch (RuntimeException blocked) { showText("File picker", "Android could not open a document picker."); }
                }).setNegativeButton("Cancel", null).show();
    }
    private void cloneDialog() {
        if (!ready()) return;
        LinearLayout fields = column(); EditText url = input("https://github.com/owner/repository.git"), name = input("New project name");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); fields.addView(url); fields.addView(name);
        dialog().setTitle("Clone repository").setView(padded(fields)).setPositiveButton("Clone", (d, w) -> {
                    String source = url.getText().toString(), target = name.getText().toString().trim();
                    work("Cloning source…", () -> WorkspaceTools.cloneProject(this, source, target, this::showProgress), this::selected);
                })
                .setNegativeButton("Cancel", null).show();
    }
    private void exportDialog() {
        if (!ready()) return;
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, project.getName() + "-source.zip");
        try { startActivityForResult(save, EXPORT); } catch (RuntimeException blocked) { showText("File picker", "Android could not open a save picker."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (AppLock.handleResult(this, lockShell, request, result, this::resumeWork)) return;
        if ((request != IMPORT && request != EXPORT) || result != RESULT_OK || data == null || data.getData() == null) return;
        pendingUri = data.getData(); pendingAction = request;
        if (!AppLock.isLocked(this)) resumeWork();
    }
    private void completePicker(int action, Uri uri) {
        if (!ready()) return;
        if (action == IMPORT) {
            final String name = pendingImportName;
            work("Checking and importing ZIP…", () -> {
                try (InputStream input = getContentResolver().openInputStream(uri)) { return WorkspaceTools.importZip(this, input, name, this::showProgress); }
            }, this::selected);
        } else if (action == EXPORT && project != null) {
            work("Exporting project source…", () -> {
                try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                    if (output == null) throw new java.io.IOException("Android could not open the destination.");
                    WorkspaceTools.exportZip(this, project, output); return "Source ZIP saved";
                }
            }, value -> status.setText(value));
        }
    }
    private void selected(File value) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_PROJECT, value.getName()));
        toast("Project ready: " + value.getName()); finish();
    }
    private void remoteDialog() {
        if (!ready()) return;
        EditText remote = input("https://github.com/owner/repository.git"); remote.setText(overview.optString("remote"));
        dialog().setTitle("Set origin URL").setMessage("This changes only the local repository's remote address. It does not create a GitHub repository or upload code.")
                .setView(padded(remote)).setPositiveButton("Save origin", (d, w) -> {
                    String url = remote.getText().toString();
                    work("Saving origin…", () -> { WorkspaceTools.setGitRemote(this, project, url); return "Origin saved"; }, value -> refresh());
                }).setNegativeButton("Cancel", null).show();
    }
    private void commitDialog() {
        if (!ready()) return;
        LinearLayout fields = column(); EditText message = input("Commit message"), author = input("Your name"), email = input("Your commit email");
        author.setText(getPreferences(MODE_PRIVATE).getString("author", "")); email.setText(getPreferences(MODE_PRIVATE).getString("email", ""));
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        fields.addView(message); fields.addView(author); fields.addView(email);
        dialog().setTitle("Commit all local changes").setMessage("Stages new, changed and deleted files according to .gitignore. Review your files first; avoid committing credentials. This stays on your phone until you explicitly push.")
                .setView(padded(fields)).setPositiveButton("Commit locally", (d, w) -> {
                    String who = author.getText().toString(), address = email.getText().toString(), commit = message.getText().toString();
                    work("Creating local commit…", () -> WorkspaceTools.commitAll(this, project, commit, who, address), value -> {
                        getPreferences(MODE_PRIVATE).edit().putString("author", who).putString("email", address).apply();
                        status.setText(value); refresh();
                    });
                }).setNegativeButton("Cancel", null).show();
    }
    private void sync(boolean push) {
        if (!ready()) return;
        String remote = overview.optString("remote"), branch = overview.optString("branch");
        if (remote.isEmpty() || branch.isEmpty()) { showText("Git sync", "Set a plain HTTPS origin and use a named branch first."); return; }
        confirm(push ? "Push this branch?" : "Pull latest changes?", (push ? "Upload committed source to " : "Download and fast-forward from ")
                + remote + "\nBranch: " + branch + (push ? "\nNo force push is used." : "\nLocal changes must be committed first; divergent history is left untouched."),
                () -> work(push ? "Pushing branch…" : "Pulling changes…", () -> WorkspaceTools.syncGit(this, project, push, remote, branch, this::showProgress), value -> { showText("Git result", value); refresh(); }));
    }
    private void browse(String relative) {
        if (!ready()) return;
        work("Reading files…", () -> WorkspaceTools.listFiles(this, project, relative), entries -> {
            folder = relative; String[] labels = new String[entries.size() + (relative.isEmpty() ? 0 : 1)];
            int offset = relative.isEmpty() ? 0 : 1; if (offset == 1) labels[0] = "← Parent folder";
            for (int i = 0; i < entries.size(); i++) labels[i + offset] = (entries.get(i).directory ? "Folder · " : "File · ") + entries.get(i).name;
            dialog().setTitle(relative.isEmpty() ? project.getName() : relative).setItems(labels, (d, which) -> {
                if (offset == 1 && which == 0) { int slash = relative.lastIndexOf('/'); browse(slash < 0 ? "" : relative.substring(0, slash)); return; }
                WorkspaceTools.Entry entry = entries.get(which - offset);
                if (entry.directory) browse(entry.path); else fileActions(entry);
            }).setNegativeButton("Close", null).show();
        });
    }
    private void fileActions(WorkspaceTools.Entry entry) {
        dialog().setTitle(entry.name).setItems(new String[]{"View text", "Rename file", "Delete file"}, (d, which) -> {
            if (which == 0) work("Opening text…", () -> WorkspaceTools.readText(this, project, entry.path), value -> showText(entry.path, value));
            else if (which == 1) {
                EditText name = input("New file name"); name.setText(entry.name);
                dialog().setTitle("Rename file").setView(padded(name)).setPositiveButton("Rename", (dialog, w) -> {
                            String target = name.getText().toString().trim();
                            work("Renaming file…", () -> { WorkspaceTools.renameFile(this, project, entry.path, target); return "File renamed"; }, value -> { status.setText(value); browse(folder); });
                        })
                        .setNegativeButton("Cancel", null).show();
            } else confirm("Delete this file?", entry.path + "\nThis removes the local file. Recovery requires a Git commit or your backup.",
                    () -> work("Deleting file…", () -> { WorkspaceTools.deleteFile(this, project, entry.path); return "File deleted"; }, value -> { status.setText(value); browse(folder); }));
        }).setNegativeButton("Close", null).show();
    }

    private <T> void work(String message, Job<T> task, Done<T> done) {
        if (!ready()) return;
        busy = true; status.setText(message); progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            T result = null; Exception failure = null;
            try { result = task.run(); } catch (Exception error) { failure = error; }
            final T value = result; final Exception error = failure;
            main.post(() -> {
                if (destroyed || isFinishing()) return;
                busy = false; progress.setVisibility(View.GONE);
                if (readingGit) { readingGit = false; gitReadError = error == null ? "" : clean(error.getMessage()); if (error != null) render(); }
                if (error != null) { status.setText(clean(error.getMessage())); if (!AppLock.isLocked(this)) showText("Project operation", clean(error.getMessage())); }
                else if (!AppLock.isLocked(this)) done.accept(value);
                else { pendingCompletion = () -> done.accept(value); status.setText("Operation completed. Unlock to continue."); }
                queueStatus(500);
            });
        });
    }
    private void showProgress(String value) { main.post(() -> { if (!destroyed && busy) status.setText(clean(value)); }); }
    private void confirm(String title, String detail, Runnable action) {
        if (!ready()) return;
        dialog().setTitle(title).setMessage(detail).setPositiveButton("Continue", (d, w) -> action.run()).setNegativeButton("Cancel", null).show();
    }
    private void showText(String title, String value) {
        if (AppLock.isLocked(this)) return;
        ScrollView scroll = new ScrollView(this); TextView content = selectable(clean(value)); content.setPadding(dp(18), dp(12), dp(18), dp(12)); scroll.addView(content);
        dialog().setTitle(title).setView(scroll).setPositiveButton("Close", null).show();
    }
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, DeskStyle.dialogTheme(this)) {
            @Override public AlertDialog show() {
                AlertDialog value = super.create();
                if (value.getWindow() != null) {
                    if (AppLock.enabled(ProjectToolsActivity.this)) value.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                    value.getWindow().setBackgroundDrawable(DeskStyle.card(ProjectToolsActivity.this));
                }
                dialogs.add(value); value.setOnDismissListener(d -> dialogs.remove(value)); value.show(); return value;
            }
        };
    }
    private int dp(int value) { return Ui.dp(this, value); }
    private LinearLayout column() { LinearLayout result = new LinearLayout(this); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private LinearLayout row() { LinearLayout result = new LinearLayout(this); result.setGravity(android.view.Gravity.CENTER_VERTICAL); return result; }
    private LinearLayout card(String title) { LinearLayout result = column(); result.setPadding(dp(16), dp(15), dp(16), dp(15)); result.setBackground(DeskStyle.card(this)); TextView heading = text(title, 19, DeskStyle.TEXT); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD); result.addView(heading); return result; }
    private TextView text(String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(dp(3), 1); return view; }
    private TextView selectable(String value) { TextView view = text(value, 13, DeskStyle.TEXT); view.setTextIsSelectable(true); return view; }
    private EditText input(String hint) { EditText value = new EditText(this); value.setHint(hint); value.setSingleLine(true); value.setTextColor(DeskStyle.TEXT); value.setHintTextColor(DeskStyle.MUTED); value.setBackground(DeskStyle.field(this)); value.setPadding(dp(12), dp(10), dp(12), dp(10)); value.setMinHeight(dp(48)); return value; }
    private Button button(String title, boolean primary, View.OnClickListener action) { Button value = new Button(this); value.setText(title); value.setAllCaps(false); value.setTextSize(14); value.setTextColor(primary ? DeskStyle.PRIMARY_TEXT : DeskStyle.TEXT); value.setBackground(primary ? DeskStyle.primary(this) : DeskStyle.field(this)); value.setMinHeight(dp(48)); value.setPadding(dp(12), dp(8), dp(12), dp(8)); value.setOnClickListener(action); value.setLayoutParams(space(9, 0)); return value; }
    private LinearLayout padded(View content) { LinearLayout result = column(); result.setPadding(dp(18), dp(8), dp(18), dp(8)); result.addView(content); return result; }
    private LinearLayout.LayoutParams space(int top, int bottom) { LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(-1, -2); result.setMargins(0, dp(top), 0, dp(bottom)); return result; }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private static String clean(String value) { return AgentProtocol.clean(value == null ? "The project operation could not complete." : value, 24000); }
    private static boolean validProjectName(String name) { return name != null && name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"); }
}
