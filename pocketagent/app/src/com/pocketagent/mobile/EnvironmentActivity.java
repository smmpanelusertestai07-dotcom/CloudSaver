package com.pocketagent.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
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
import java.util.HashSet;
import java.util.Set;

/** Names-only GUI for Codex's fixed shell environment configuration surface. */
public final class EnvironmentActivity extends Activity {
    static final int REQUEST = 731;
    static final String EXTRA_DRAFT = "environment_draft";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<AlertDialog> dialogs = new HashSet<>();
    private FrameLayout lockShell;
    private LinearLayout names;
    private TextView status, empty, setupStatus;
    private Button refresh, add;
    private ProgressBar progress;
    private String project = "", appearance = "", namesSignature = "";
    private JSONObject state = new JSONObject(), controls = new JSONObject(), environment = new JSONObject();
    private boolean resumed, registered, firstRead, requestPending, refreshQueued;
    private boolean refreshDue = true, automaticRequest;
    private final ConnectionsActivity.ReadRefresh reads = new ConnectionsActivity.ReadRefresh();
    private final ConnectionsActivity.ClientPresence presence = new ConnectionsActivity.ClientPresence(this);
    private final Runnable autoTick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            presence.update(project, safe()); refreshState(); main.postDelayed(this, 5000);
        }
    };
    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (refreshQueued) return;
            refreshQueued = true;
            main.postDelayed(() -> { refreshQueued = false; refreshState(); }, 160);
        }
    };

    static void open(Activity activity, String project) {
        try {
            String selected = AgentProtocol.project(project);
            activity.startActivityForResult(new Intent(activity, EnvironmentActivity.class)
                    .putExtra(AgentService.EXTRA_PROJECT, selected), REQUEST);
        } catch (RuntimeException invalid) {
            Toast.makeText(activity, "Choose a project first.", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onCreate(Bundle saved) {
        DeskStyle.apply(this); appearance = DeskStyle.themeSignature(this);
        super.onCreate(saved);
        try { project = AgentProtocol.project(getIntent().getStringExtra(AgentService.EXTRA_PROJECT)); }
        catch (RuntimeException invalid) { finish(); return; }
        if (saved != null) firstRead = saved.getBoolean("names_requested");
        build(); refreshState();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(AgentService.EVENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(events, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(events, filter);
        registered = true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (!appearance.equals(DeskStyle.themeSignature(this))) { recreate(); return; }
        resumed = true; DeskStyle.applySystemBars(this); secureWindow();
        if (AppLock.isLocked(this)) AppLock.show(this, lockShell, this::resumeWork);
        else resumeWork();
    }

    @Override protected void onPause() { resumed = false; main.removeCallbacks(autoTick); super.onPause(); }

    @Override protected void onStop() {
        presence.update(project, false);
        if (registered) { unregisterReceiver(events); registered = false; }
        // Secret entry never stays in a background dialog or in activity saved state.
        for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        super.onStop();
    }

    @Override protected void onDestroy() { presence.update(project, false); main.removeCallbacksAndMessages(null); super.onDestroy(); }

    @Override protected void onSaveInstanceState(Bundle saved) {
        saved.putBoolean("names_requested", firstRead); super.onSaveInstanceState(saved);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        AppLock.handleResult(this, lockShell, request, result, this::resumeWork);
        secureWindow();
    }

    private void secureWindow() {
        AppLock.applyWindowSecurity(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void build() {
        DeskStyle.apply(this);
        LinearLayout root = column(); root.setBackground(DeskStyle.background(this));
        root.setPadding(dp(16), dp(8), dp(16), dp(8));
        lockShell = new FrameLayout(this); lockShell.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(lockShell); secureWindow();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                int extra = Math.max(0, (getResources().getDisplayMetrics().widthPixels - bars.left - bars.right - dp(680)) / 2);
                root.setPadding(dp(16) + bars.left + extra, dp(8) + bars.top, dp(16) + bars.right + extra, dp(8) + Math.max(bars.bottom, ime.bottom));
            } else root.setPadding(dp(16) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(),
                    dp(16) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        LinearLayout toolbar = row(); toolbar.addView(button("Back", "back", false, v -> finish()), new LinearLayout.LayoutParams(-2, -2));
        TextView title = heading("Environment"); title.setPadding(dp(12), 0, 0, 0);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(toolbar);
        ScrollView scroll = new ScrollView(this); LinearLayout body = column(); body.setPadding(dp(2), dp(18), dp(2), dp(16));
        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout workspace = card(); workspace.addView(heading("On this phone · Ubuntu"));
        workspace.addView(text("Project · " + project, 14, DeskStyle.TEXT), space(8, 6));
        workspace.addView(button("Details", "file", false, v -> environmentDetails()), space(8, 0));
        body.addView(workspace, space(0, 20));

        body.addView(heading("Environment variables"), space(0, 8));
        body.addView(text("Names only · values stay hidden", 13, DeskStyle.MUTED), space(0, 12));
        status = text("Connect Codex to read its environment settings.", 13, DeskStyle.MUTED); body.addView(status, space(0, 8));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true); progress.setVisibility(View.GONE); body.addView(progress, space(0, 8));
        LinearLayout actions = row();
        refresh = button("Retry", "refresh", false, v -> { refreshDue = true; refreshAutomatically(); });
        refresh.setVisibility(View.GONE);
        add = button("Add variable", "new", true, v -> editVariable(""));
        actions.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams addSpace = new LinearLayout.LayoutParams(0, -2, 1); addSpace.leftMargin = dp(8); actions.addView(add, addSpace);
        body.addView(actions, space(0, 12));
        empty = text("Variable names have not been loaded.", 13, DeskStyle.MUTED); body.addView(empty, space(0, 8));
        names = column(); body.addView(names, space(0, 10));
        setupStatus = text("After saving a change, reconnect Codex and start a fresh chat before relying on it. Existing commands and saved chats can retain older settings.", 13, DeskStyle.MUTED);
        body.addView(setupStatus, space(0, 18));

        body.addView(heading("Project setup"), space(0, 8));

        body.addView(button("Inspect setup in chat", "chat", false, v -> {
            if (!safe()) return;
            setResult(RESULT_OK, new Intent().putExtra(AgentService.EXTRA_PROJECT, project).putExtra(EXTRA_DRAFT,
                    "Inspect this project's README, configuration and setup scripts. Explain the dependencies and environment variable names it needs. Do not read or print secret values, change files, install dependencies or execute setup scripts yet."));
            finish();
        }), space(0, 8));
    }

    private boolean safe() { return resumed && !isFinishing() && !AppLock.isLocked(this); }

    private void resumeWork() {
        if (!safe()) return;
        presence.update(project, true); refreshDue = true; refreshState();
        main.removeCallbacks(autoTick); main.postDelayed(autoTick, 5000);
    }

    private void refreshAutomatically() {
        if (!safe() || !idle() || !dialogs.isEmpty()) return;
        if (!reads.due(SystemClock.elapsedRealtime(), 30000, !controls.optString("error").isEmpty(), refreshDue)) return;
        refreshDue = false; automaticRequest = true;
        try { if (sendControl("env_read", new JSONObject())) firstRead = true; } finally { automaticRequest = false; }
    }

    private void environmentDetails() {
        if (!safe()) return;
        showDialog(new AlertDialog.Builder(this, DeskStyle.dialogTheme(this)).setTitle("Environment")
                .setMessage("Project files stay in PocketAgent's private workspace. Import or export selected files with Android's file picker.\n\nCodex passes environment values to future shell commands. This screen reads names only. Values apply across projects using this phone's Codex profile.\n\nAfter saving, reconnect Codex and start a fresh chat. Existing commands and saved chats can retain older settings.\n\nUbuntu through PRoot is not a network firewall. Project commands can still use the network.")
                .setPositiveButton("Done", null));
    }

    private boolean matching() {
        return "codex".equals(state.optString("provider")) && project.equals(state.optString("project"))
                && (state.optBoolean("connected") || state.optBoolean("accountConnected"));
    }

    private boolean idle() {
        JSONObject integrations = state.optJSONObject("integrations"), sessions = state.optJSONObject("sessions"), permission = state.optJSONObject("permission");
        return matching() && !requestPending && reads.available(SystemClock.elapsedRealtime()) && !state.optBoolean("busy") && !state.optBoolean("sessionOpening")
                && !state.optBoolean("connecting") && !state.optBoolean("authenticating") && !state.optBoolean("installing")
                && controls.optString("operation").isEmpty() && (permission == null || permission.length() == 0)
                && (integrations == null || !integrations.optBoolean("loading")) && (sessions == null || !sessions.optBoolean("loading"));
    }

    private void refreshState() {
        if (status == null) return;
        state = AgentService.snapshot(); controls = state.optJSONObject("controls");
        if (controls == null) controls = new JSONObject();
        environment = controls.optJSONObject("environment"); if (environment == null) environment = new JSONObject();
        boolean scope = matching(), working = requestPending || !controls.optString("operation").isEmpty();
        String error = controls.optString("error");
        status.setText(!scope ? "Connect Codex for this project."
                : !error.isEmpty() ? error : working ? "Updating names…"
                : environment.optBoolean("known") ? "Updates automatically" : state.optBoolean("busy") ? "Waiting for the current task" : "Waiting for Codex…");
        status.setTextColor(scope && !error.isEmpty() ? DeskStyle.ERROR : DeskStyle.MUTED);
        progress.setVisibility(scope && working ? View.VISIBLE : View.GONE);
        refresh.setVisibility(scope && !error.isEmpty() ? View.VISIBLE : View.GONE);
        refresh.setEnabled(idle()); refresh.setAlpha(refresh.isEnabled() ? 1f : .5f);
        add.setEnabled(idle()); add.setAlpha(add.isEnabled() ? 1f : .5f);
        JSONArray listed = scope ? environment.optJSONArray("names") : null;
        String signature = scope + ":" + environment.optBoolean("known") + ":" + String.valueOf(listed) + ":" + idle();
        if (!signature.equals(namesSignature)) {
            namesSignature = signature; names.removeAllViews();
            int count = listed == null ? 0 : listed.length();
            empty.setText(!scope || !environment.optBoolean("known") ? "Variable names have not been loaded."
                    : count == 0 ? "No custom variable names are configured." : count >= 128 ? "Showing up to 128 configured names. Values are hidden." : count + " configured " + (count == 1 ? "name" : "names") + " · values hidden");
            if (listed != null) for (int i = 0; i < listed.length(); i++) {
                String name = listed.optString(i); if (name.isEmpty()) continue;
                Button entry = button(name + " · Replace", "settings", false, v -> editVariable(name));
                boolean allowed = true; try { CodexEnvironment.validateName(name); } catch (RuntimeException reserved) { allowed = false; entry.setText(name + " · Managed by agent"); }
                entry.setEnabled(idle() && allowed); entry.setAlpha(entry.isEnabled() ? 1f : .6f); names.addView(entry, space(0, 6));
            }
        }
        setupStatus.setText("Saved · reconnect Codex and start a fresh chat to apply the change.");
        setupStatus.setVisibility(environment.optBoolean("reconnectRequired") ? View.VISIBLE : View.GONE);
        refreshAutomatically();
    }

    private boolean sendControl(String operation, JSONObject payload) {
        if (!safe()) return false;
        if (!idle()) { toast("Connect Codex and finish the current task before changing its environment."); return false; }
        try {
            requestPending = true;
            startService(new Intent(this, AgentService.class).setAction(AgentService.ACTION_CONTROL)
                    .putExtra(AgentService.EXTRA_PROVIDER, "codex").putExtra(AgentService.EXTRA_PROJECT, project)
                    .putExtra(AgentService.EXTRA_OPERATION, operation).putExtra(AgentService.EXTRA_PAYLOAD, payload.toString()));
            reads.attempted(SystemClock.elapsedRealtime()); refresh.setEnabled(false); add.setEnabled(false);
            main.postDelayed(() -> { requestPending = false; refreshState(); }, 1600);
            return true;
        } catch (RuntimeException failure) {
            requestPending = false; reads.failed(SystemClock.elapsedRealtime()); if (!automaticRequest) toast("Could not send the environment request to Codex."); return false;
        }
    }

    private void editVariable(String existing) {
        if (!safe() || !idle()) return;
        LinearLayout fields = column(); fields.setPadding(dp(20), dp(8), dp(20), dp(12));
        fields.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        ScrollView scroller = new ScrollView(this); scroller.addView(fields);
        EditText name = input("Name, e.g. APP_ENV", false), value = input("Value · hidden", true);
        name.setText(existing); fields.addView(text("Name", 13, DeskStyle.MUTED), space(0, 4)); fields.addView(name, space(0, 12));
        fields.addView(text("Value", 13, DeskStyle.MUTED), space(0, 4)); fields.addView(value, space(0, 12));
        fields.addView(text(existing.isEmpty() ? "Enter the value for your project tools. This does not change agent account sign-in."
                : "Existing values are never loaded into this screen. Enter the replacement value.", 13, DeskStyle.MUTED), space(0, 8));
        fields.addView(text("Stored in this phone's Codex profile. Other projects using that profile can receive the value in future commands.", 13, DeskStyle.MUTED));
        AlertDialog form = showDialog(new AlertDialog.Builder(this, DeskStyle.dialogTheme(this))
                .setTitle(existing.isEmpty() ? "Add environment variable" : "Replace environment variable")
                .setView(scroller).setNegativeButton("Cancel", null).setPositiveButton("Review save", null));
        form.setOnDismissListener(d -> { value.setText(""); dialogs.remove(form); });
        form.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!safe() || !idle()) return;
            name.setError(null); value.setError(null);
            String variable = name.getText().toString().trim();
            try { CodexEnvironment.validateName(variable); }
            catch (RuntimeException invalid) { name.setError(invalid.getMessage()); name.requestFocus(); return; }
            String entered = value.getText().toString();
            try { CodexEnvironment.writeEntryParams(variable, entered); }
            catch (Exception invalid) { value.setError("Use at most 4096 characters without NUL characters."); value.requestFocus(); return; }
            String description = "Save " + variable + " in this phone's Codex profile?\n\nProject commands can read this value, and tools or project code can send it over the network. Other projects using the same profile can also receive it. Only save values you want those commands to access."
                    + (entered.isEmpty() ? "\n\nYou are saving an empty value. This replaces any existing value with an empty string; it does not remove the variable." : "")
                    + "\n\nReconnect Codex and start a fresh chat before relying on the change.";
            showDialog(new AlertDialog.Builder(this, DeskStyle.dialogTheme(this)).setTitle("Confirm environment change")
                    .setMessage(description).setNegativeButton("Go back", null).setPositiveButton("Save value", (d,w) -> {
                        if (!safe() || !idle()) return;
                        try {
                            JSONObject payload = new JSONObject().put("name", variable).put("value", entered).put("confirmed", true);
                            if (sendControl("env_set", payload)) { value.setText(""); form.dismiss(); }
                        } catch (Exception invalid) { toast("Could not prepare the environment change."); }
                    }));
        });
    }

    private AlertDialog showDialog(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getWindow().setBackgroundDrawable(DeskStyle.card(this));
        }
        dialogs.add(dialog); dialog.setOnDismissListener(d -> dialogs.remove(dialog)); dialog.show(); return dialog;
    }

    private EditText input(String hint, boolean secret) {
        EditText input = new EditText(this); input.setHint(hint); input.setTextSize(15); input.setTextColor(DeskStyle.TEXT); input.setHintTextColor(DeskStyle.MUTED);
        input.setSingleLine(true); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        if (secret) input.setTransformationMethod(PasswordTransformationMethod.getInstance());
        input.setSaveEnabled(false); input.setFreezesText(false); input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setPadding(dp(12), dp(12), dp(12), dp(12)); input.setBackground(DeskStyle.field(this)); return input;
    }
    private Button button(String title, String name, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false); button.setTextSize(14);
        button.setTextColor(primary ? DeskStyle.PRIMARY_TEXT : DeskStyle.TEXT); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(8)); button.setStateListAnimator(null);
        button.setBackground(primary ? DeskStyle.primary(this) : Ui.tappable(this, DeskStyle.field(this), DeskStyle.isDark(this)));
        Drawable icon = DeskStyle.icon(this, name, primary ? DeskStyle.PRIMARY_TEXT : DeskStyle.TEXT); icon.setBounds(0, 0, dp(18), dp(18));
        button.setCompoundDrawablesRelative(icon, null, null, null); button.setCompoundDrawablePadding(dp(8)); button.setOnClickListener(listener); return button;
    }
    private LinearLayout card() { LinearLayout view = column(); view.setPadding(dp(16), dp(16), dp(16), dp(16)); view.setBackground(DeskStyle.card(this)); return view; }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView text(String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(0, 1.12f); return view; }
    private TextView heading(String value) { TextView view = text(value, 19, DeskStyle.TEXT); view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return view; }
    private LinearLayout.LayoutParams space(int top, int bottom) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(top), 0, dp(bottom)); return p; }
    private int dp(int value) { return Ui.dp(this, value); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private static String first(String first, String fallback) { return first == null || first.isEmpty() || "null".equals(first) ? fallback : first; }
}
