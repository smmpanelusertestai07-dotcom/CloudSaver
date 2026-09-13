package com.pocketagent.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Session actions stay over the chat. Only an explicit Share opens Android's share chooser. */
final class SessionActionsSheet {
    private final Activity activity;
    private final String project, threadId, accountToken, sourceThread;
    private final boolean archived;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<View> actions = new ArrayList<>();
    private AlertDialog dialog;
    private LinearLayout body;
    private TextView heading, status;
    private String title, page = "", pendingOperation = "", previousError = "", previousShare = "";
    private long beforeRevision, requestedAt;
    private boolean closed, awaiting, observedPending, uncertain;
    private EditText rename;

    static SessionActionsSheet open(Activity activity, String project, String threadId, String title,
                                   boolean archived, String operation, Runnable changed) {
        if (activity.isFinishing() || activity.isDestroyed() || AppLock.isLocked(activity)) return null;
        if (project == null || !project.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                || threadId == null || !threadId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,159}")) {
            Toast.makeText(activity, "Open a saved chat first.", Toast.LENGTH_SHORT).show(); return null;
        }
        SessionActionsSheet sheet = new SessionActionsSheet(activity, project, threadId, title, archived, changed);
        sheet.show(operation == null ? "" : operation);
        return sheet;
    }

    private SessionActionsSheet(Activity owner, String workspace, String id, String name, boolean isArchived, Runnable callback) {
        activity = owner; project = workspace; threadId = id; title = SessionActionState.title(name);
        archived = isArchived; changed = callback;
        JSONObject state = AgentService.snapshot(); accountToken = state.optString("accountScopeToken"); sourceThread = state.optString("threadId");
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            refresh();
            if (!closed) main.postDelayed(this, 350);
        }
    };

    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityPaused(Activity value) { if (value == activity) dismiss(); }
        @Override public void onActivityDestroyed(Activity value) { if (value == activity) dismiss(); }
        @Override public void onActivityCreated(Activity value, Bundle state) { }
        @Override public void onActivityStarted(Activity value) { }
        @Override public void onActivityResumed(Activity value) { }
        @Override public void onActivityStopped(Activity value) { }
        @Override public void onActivitySaveInstanceState(Activity value, Bundle state) { }
    };

    private void show(String operation) {
        LinearLayout container = column(); container.setPadding(dp(12), dp(6), dp(12), dp(14));
        container.setBackground(DeskStyle.card(activity));
        LinearLayout header = row(); heading = text(title, 17, DeskStyle.TEXT);
        heading.setTypeface(null, Typeface.BOLD); heading.setMaxLines(2); heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setPadding(dp(8), dp(10), dp(4), dp(10));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(icon("Close", "close", this::dismiss), new LinearLayout.LayoutParams(dp(48), dp(48)));
        container.addView(header);
        body = column(); ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(false); scroll.addView(body);
        container.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        status = text("", 13, DeskStyle.MUTED); status.setPadding(dp(10), dp(8), dp(10), 0);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); container.addView(status);
        dialog = new AlertDialog.Builder(activity, DeskStyle.dialogTheme(activity)).setView(container).create();
        dialog.setOnDismissListener(value -> cleanup());
        Window window = dialog.getWindow();
        if (window != null && AppLock.enabled(activity)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
        dialog.show();
        window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(Math.min(activity.getResources().getDisplayMetrics().widthPixels, dp(680)), -2);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        page(operation); main.post(tick);
        scroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            int maximum = (int) (activity.getResources().getDisplayMetrics().heightPixels * .58f);
            if (scroll.getHeight() > maximum) { scroll.getLayoutParams().height = maximum; scroll.requestLayout(); }
        });
    }

    void dismiss() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        cleanup();
    }

    private void cleanup() {
        if (closed) return; closed = true; main.removeCallbacks(tick);
        activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
        if (rename != null) ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(rename.getWindowToken(), 0);
    }

    private boolean safe() {
        if (closed || activity.isFinishing() || activity.isDestroyed() || AppLock.isLocked(activity)) return false;
        return SessionActionState.sameScope(AgentService.snapshot(), project, accountToken);
    }

    private void page(String operation) {
        if (!safe()) { dismiss(); return; }
        page = operation; body.removeAllViews(); actions.clear(); rename = null;
        status.setText(""); status.setTextColor(DeskStyle.MUTED); uncertain = false;
        heading.setText(operation.isEmpty() ? title : "rename".equals(operation) ? "Rename chat"
                : "delete".equals(operation) ? "Delete chat?" : "archive".equals(operation) ? "Archive chat?"
                : "unarchive".equals(operation) ? "Restore chat?" : "export".equals(operation) ? "Share chat" : title);
        if (!operation.isEmpty()) {
            LinearLayout navigation = row(); navigation.addView(icon("Back", "back", () -> { if (awaiting) dismiss(); else page(""); }), new LinearLayout.LayoutParams(dp(48), dp(48)));
            body.addView(navigation);
        }
        if (operation.isEmpty()) {
            add("Rename", "edit", () -> page("rename"), false);
            add("Share", "share", () -> page("export"), false);
            add(archived ? "Restore" : "Archive", archived ? "restore" : "archive", () -> page(archived ? "unarchive" : "archive"), false);
            add("Delete", "trash", () -> page("delete"), true);
        } else if ("rename".equals(operation)) {
            rename = new EditText(activity); rename.setText(title); rename.setSelectAllOnFocus(true);
            rename.setSingleLine(true); rename.setTextColor(DeskStyle.TEXT); rename.setHintTextColor(DeskStyle.MUTED);
            rename.setHint("Chat name"); rename.setContentDescription("Chat name"); rename.setTextSize(16);
            rename.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
            rename.setPadding(dp(12), dp(12), dp(12), dp(12)); rename.setBackground(DeskStyle.field(activity));
            body.addView(rename); actions.add(rename);
            add("Save", "check", () -> {
                try { JSONObject payload = CodexSessionControls.renameParams(threadId, rename.getText().toString()); request("rename", payload); }
                catch (RuntimeException invalid) { rename.setError(invalid.getMessage()); }
            }, false);
        } else if ("archive".equals(operation) || "unarchive".equals(operation) || "delete".equals(operation)) {
            detail("delete".equals(operation)
                    ? "Permanently delete this Codex chat and its child chats. Saved transcript copies on this phone are kept."
                    : "archive".equals(operation) ? "Move this chat to Archived. You can restore it later."
                    : "Move this chat back to Recent.");
            add("delete".equals(operation) ? "Delete chat" : "archive".equals(operation) ? "Archive chat" : "Restore chat",
                    "delete".equals(operation) ? "trash" : "unarchive".equals(operation) ? "restore" : "archive", () -> request(operation, object("threadId", threadId, "confirmed", true)), "delete".equals(operation));
        } else if ("export".equals(operation)) {
            detail("Review the chat text before choosing where to share it.");
            add("Prepare share", "share", () -> request("export", object("threadId", threadId)), false);
        } else { page(""); return; }
        refresh();
        if ("export".equals(operation) && safe() && SessionActionState.idle(AgentService.snapshot()))
            request("export", object("threadId", threadId));
    }

    private void request(String operation, JSONObject payload) {
        if (!safe()) { dismiss(); return; }
        JSONObject state = AgentService.snapshot();
        if (awaiting || !SessionActionState.idle(state) || WorkspaceTools.isProjectOperationBusy()) {
            status.setText(SessionActionState.connected(state) ? "Wait for the current task to finish." : "Reconnect Codex to change this chat."); return;
        }
        JSONObject sessions = child(state, "sessions");
        beforeRevision = child(sessions, "lastChange").optLong("revision"); previousError = sessions.optString("error");
        previousShare = sessions.optString("shareId"); requestedAt = SystemClock.elapsedRealtime();
        pendingOperation = operation; awaiting = true; observedPending = false; uncertain = false;
        try {
            payload.put("expectedAccountToken", accountToken);
            activity.startForegroundService(intent(operation, payload));
            status.setText("export".equals(operation) ? "Preparing chat…" : "Saving…"); status.setTextColor(DeskStyle.MUTED);
            enable(false);
        } catch (Exception failure) { awaiting = false; status.setText(clean(failure.getMessage(), 300)); status.setTextColor(DeskStyle.ERROR); }
    }

    private Intent intent(String operation, JSONObject payload) {
        return new Intent(activity, AgentService.class).setAction(AgentService.ACTION_SESSION)
                .putExtra(AgentService.EXTRA_PROVIDER, "codex").putExtra(AgentService.EXTRA_PROJECT, project)
                .putExtra(AgentService.EXTRA_OPERATION, operation).putExtra(AgentService.EXTRA_PAYLOAD, payload.toString());
    }

    private void refresh() {
        if (!safe()) { dismiss(); return; }
        JSONObject state = AgentService.snapshot(), sessions = child(state, "sessions");
        if (awaiting) {
            if ("export".equals(pendingOperation) && SessionActionState.exported(sessions, threadId, previousShare)) {
                awaiting = false; reviewShare(sessions); return;
            }
            if (SessionActionState.changed(sessions, threadId, pendingOperation, beforeRevision)) {
                String message = "rename".equals(pendingOperation) ? "Chat renamed" : "delete".equals(pendingOperation) ? "Chat deleted"
                        : "unarchive".equals(pendingOperation) ? "Chat restored" : "Chat archived";
                awaiting = false; dismiss();
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                if (changed != null) changed.run(); return;
            }
            if (!sourceThread.equals(state.optString("threadId")) && !sessions.optBoolean("loading")) { dismiss(); return; }
            if (pendingOperation.equals(sessions.optString("operation"))) observedPending = true;
            String error = sessions.optString("error");
            if (!error.isEmpty() && (!error.equals(previousError) || observedPending)) {
                awaiting = false; uncertain = false; status.setText(error); status.setTextColor(DeskStyle.ERROR); enable(SessionActionState.idle(state)); return;
            }
            if (!SessionActionState.connected(state)) {
                status.setText("Codex disconnected. Reconnect and check the chat before trying again."); status.setTextColor(DeskStyle.WARNING); enable(false); return;
            }
            if (SystemClock.elapsedRealtime() - requestedAt > 30000) {
                uncertain = true; status.setText("No confirmation yet. Check the chat before trying again."); status.setTextColor(DeskStyle.WARNING);
            } else if (observedPending) status.setText(sessions.optString("status", "Saving…"));
            enable(false); return;
        }
        if (!sourceThread.equals(state.optString("threadId"))) { dismiss(); return; }
        if ("review".equals(page)) return;
        boolean connected = SessionActionState.connected(state), idle = SessionActionState.idle(state) && !WorkspaceTools.isProjectOperationBusy();
        enable(page.isEmpty() || idle);
        // Keep an RPC failure visible instead of replacing it with a generic ready label.
        if (status.getCurrentTextColor() != DeskStyle.ERROR && !uncertain) {
            status.setText(!connected ? "Reconnect Codex to change this chat." : !idle ? "Wait for the current task to finish." : "");
            status.setTextColor(DeskStyle.MUTED);
        }
    }

    private void reviewShare(JSONObject sessions) {
        if (!safe()) return;
        page = "review"; body.removeAllViews(); actions.clear();
        heading.setText(sessions.optBoolean("shareTruncated") ? "Share chat excerpt" : "Share chat");
        final String shareId = sessions.optString("shareId"), shareText = sessions.optString("shareText"), subject = sessions.optString("shareTitle", title);
        TextView preview = text(shareText, 14, DeskStyle.TEXT); preview.setTextIsSelectable(true); preview.setPadding(dp(10), dp(6), dp(10), dp(14));
        ScrollView scroll = new ScrollView(activity); scroll.addView(preview); body.addView(scroll, new LinearLayout.LayoutParams(-1, dp(240)));
        if (sessions.optBoolean("shareTruncated")) detail("This is an excerpt. The full chat remains in Codex.");
        add("Copy", "copy", () -> {
            if (!currentShare(shareId)) return;
            ((ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(subject, shareText));
            Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show();
        }, false);
        add("Share", "share", () -> {
            if (!currentShare(shareId)) return;
            try {
                Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, shareText);
                activity.startActivity(Intent.createChooser(share, "Share chat")); dismissShare(shareId); dismiss();
            } catch (Exception unavailable) { status.setText("No sharing app is available."); status.setTextColor(DeskStyle.ERROR); }
        }, false);
        status.setText(""); status.setTextColor(DeskStyle.MUTED);
    }

    private boolean currentShare(String id) { return safe() && id.equals(child(AgentService.snapshot(), "sessions").optString("shareId")); }

    private void dismissShare(String id) {
        if (!currentShare(id)) return;
        try { activity.startForegroundService(intent("dismiss_share", object("expectedAccountToken", accountToken))); }
        catch (RuntimeException ignored) { }
    }

    private void enable(boolean enabled) { for (View view : actions) { view.setEnabled(enabled); view.setAlpha(enabled ? 1f : .45f); } }
    private void detail(String value) { TextView view = text(value, 15, DeskStyle.MUTED); view.setPadding(dp(10), dp(10), dp(10), dp(12)); body.addView(view); }
    private void add(String label, String name, Runnable action, boolean destructive) {
        TextView view = text(label, 16, destructive ? DeskStyle.ERROR : DeskStyle.TEXT); view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinHeight(dp(52)); view.setPadding(dp(10), dp(12), dp(10), dp(12)); view.setFocusable(true);
        Drawable glyph = DeskStyle.icon(activity, name, destructive ? DeskStyle.ERROR : DeskStyle.MUTED);
        glyph.setBounds(0, 0, dp(22), dp(22)); view.setCompoundDrawablesRelative(glyph, null, null, null); view.setCompoundDrawablePadding(dp(16));
        view.setBackground(DeskStyle.plain(activity)); view.setOnClickListener(v -> { if (safe()) action.run(); }); body.addView(view); actions.add(view);
    }
    private Button icon(String label, String name, Runnable action) {
        Button button = new Button(activity); button.setText(""); button.setContentDescription(label); button.setTooltipText(label);
        button.setMinHeight(0); button.setMinimumHeight(0); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(dp(12), dp(12), dp(12), dp(12)); button.setBackground(DeskStyle.plain(activity)); button.setStateListAnimator(null);
        Drawable glyph = DeskStyle.icon(activity, name, DeskStyle.MUTED); glyph.setBounds(0, 0, dp(24), dp(24)); button.setCompoundDrawables(glyph, null, null, null);
        button.setOnClickListener(v -> { if (safe()) action.run(); else dismiss(); }); return button;
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(activity); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(activity); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView text(String value, int size, int color) { TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(dp(2), 1.02f); return view; }
    private int dp(int value) { return Ui.dp(activity, value); }
}
