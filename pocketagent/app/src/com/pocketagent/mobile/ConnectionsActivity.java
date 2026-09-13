package com.pocketagent.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Native manager for capabilities actually reported by this phone's Codex engine. */
public final class ConnectionsActivity extends Activity {
    static final int REQUEST = 730;
    static final String EXTRA_SKILL_PATH = "selected_skill_path", EXTRA_SKILL_NAME = "selected_skill_name";
    static final String EXTRA_APP_ID = "selected_app_id", EXTRA_APP_NAME = "selected_app_name";
    static final String EXTRA_DRAFT = "integration_draft", EXTRA_CONNECT = "connect_codex";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<AlertDialog> dialogs = new HashSet<>();
    private final String[] sections = {"apps", "mcp", "skills", "plugins"};
    private final String[] titles = {"Apps", "MCP", "Skills", "Plugins"};
    private FrameLayout lockShell;
    private LinearLayout root, tabs, body;
    private ScrollView scroll;
    private EditText search;
    private TextView status;
    private JSONObject state = new JSONObject(), integrations = new JSONObject();
    private String project = "", lastSignature = "";
    private int section, shown = 60;
    private boolean registered, firstLoad, pendingRender, resumed, browserDeparted;
    private String pendingPlugin = "", lastPluginDetails = "";
    private String browserRefreshOperation = "";
    private String appearance = "";
    private final ReadRefresh reads = new ReadRefresh();
    private final ClientPresence presence = new ClientPresence(this);
    private boolean refreshDue = true, automaticRequest;
    private final Runnable autoTick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            presence.update(project, safeToInteract()); refreshState();
            main.postDelayed(this, 5000);
        }
    };
    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (pendingRender) return;
            pendingRender = true;
            main.postDelayed(() -> { pendingRender = false; refreshState(); }, 180);
        }
    };

    @Override public void onCreate(Bundle saved) {
        DeskStyle.apply(this); appearance = DeskStyle.themeSignature(this);
        super.onCreate(saved);
        try { project = AgentProtocol.project(getIntent().getStringExtra(AgentService.EXTRA_PROJECT)); }
        catch (RuntimeException invalid) { finish(); return; }
        section = Math.max(0, Math.min(3, getIntent().getIntExtra("section", 0)));
        if (saved != null) {
            section = Math.max(0, Math.min(3, saved.getInt("section"))); firstLoad = saved.getBoolean("loaded");
            pendingPlugin = saved.getString("pending_plugin", "");
            browserRefreshOperation = saved.getString("browser_refresh", "");
            browserDeparted = saved.getBoolean("browser_departed");
        }
        build(); refreshState();
    }
    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(AgentService.EVENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(events, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(events, filter);
        registered = true; refreshState();
    }
    @Override protected void onResume() {
        super.onResume();
        if (!appearance.equals(DeskStyle.themeSignature(this))) { recreate(); return; }
        resumed = true; DeskStyle.applySystemBars(this); AppLock.applyWindowSecurity(this);
        if (lockShell != null && AppLock.isLocked(this)) AppLock.show(this, lockShell, this::resumeWork);
        else resumeWork();
    }
    @Override protected void onPause() {
        resumed = false; main.removeCallbacks(autoTick); if (!browserRefreshOperation.isEmpty()) browserDeparted = true;
        super.onPause();
    }
    @Override protected void onStop() {
        presence.update(project, false);
        if (registered) { unregisterReceiver(events); registered = false; }
        if (AppLock.enabled(this)) for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        super.onStop();
    }
    @Override protected void onDestroy() { presence.update(project, false); main.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle saved) {
        saved.putInt("section", section); saved.putBoolean("loaded", firstLoad);
        saved.putString("pending_plugin", pendingPlugin); saved.putString("browser_refresh", browserRefreshOperation);
        saved.putBoolean("browser_departed", browserDeparted);
        super.onSaveInstanceState(saved);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        AppLock.handleResult(this, lockShell, request, result, this::resumeWork);
    }

    private void build() {
        DeskStyle.apply(this);
        root = column(); root.setBackground(DeskStyle.background(this)); root.setPadding(dp(16), dp(8), dp(16), dp(8));
        lockShell = new FrameLayout(this); lockShell.addView(root); setContentView(lockShell); AppLock.applyWindowSecurity(this);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                int extra = Math.max(0, (getResources().getDisplayMetrics().widthPixels - bars.left - bars.right - dp(680)) / 2);
                root.setPadding(dp(16) + bars.left + extra, dp(8) + bars.top, dp(16) + bars.right + extra, dp(8) + Math.max(bars.bottom, ime.bottom));
            } else root.setPadding(dp(16) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(), dp(16) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        LinearLayout top = row();
        Button back = button("Back", "back", false, v -> finish()); top.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = label("Tools", 19, DeskStyle.TEXT); title.setPadding(dp(12), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(top);
        root.addView(text("Codex · " + project, 12, DeskStyle.MUTED), lp(0, 8));
        tabs = row(); root.addView(tabs, lp(0, 8)); renderTabs();
        search = input("Search " + titles[section].toLowerCase(Locale.ROOT)); search.setSingleLine(true);
        search.setId(7330); root.addView(search, lp(0, 6));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { shown = 60; main.removeCallbacks(searchChanged); main.postDelayed(searchChanged, 180); }
            @Override public void afterTextChanged(Editable value) {}
        });
        LinearLayout bar = row(); status = text("", 12, DeskStyle.MUTED);
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(button("Details", "file", false, v -> sectionDetails()), new LinearLayout.LayoutParams(-2, -2)); root.addView(bar, lp(0, 6));
        scroll = new ScrollView(this); body = column(); scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    private final Runnable searchChanged = () -> { lastSignature = ""; render(); };
    private void renderTabs() {
        tabs.removeAllViews();
        String[] icons = {"account", "settings", "file", "folder"};
        for (int i = 0; i < titles.length; i++) {
            final int target = i;
            TextView tab = label(titles[i], 12, section == i ? DeskStyle.TEXT : DeskStyle.MUTED);
            tab.setGravity(Gravity.CENTER); tab.setMinHeight(dp(56)); tab.setPadding(dp(2), dp(4), dp(2), dp(4));
            Drawable icon = DeskStyle.icon(this, icons[i], section == i ? DeskStyle.ACCENT : DeskStyle.MUTED); icon.setBounds(0, 0, dp(20), dp(20));
            tab.setCompoundDrawables(null, icon, null, null); tab.setCompoundDrawablePadding(dp(3));
            tab.setBackground(Ui.tappable(this, section == i ? DeskStyle.indicator(this) : Ui.background(0, 14, this), DeskStyle.isDark(this)));
            tab.setSelected(section == i); tab.setContentDescription(titles[i] + (section == i ? ", selected" : "")); tab.setFocusable(true);
            tab.setOnClickListener(v -> { section = target; shown = 60; renderTabs(); search.setHint("Search " + titles[target].toLowerCase(Locale.ROOT)); search.setText(""); lastSignature = ""; refreshDue = true; render(); refreshAutomatically(); });
            tabs.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
        }
    }
    private boolean usable() {
        return "codex".equals(state.optString("provider")) && project.equals(state.optString("project"))
                && (state.optBoolean("connected") || state.optBoolean("accountConnected"));
    }
    private boolean safeToInteract() { return resumed && !isFinishing() && !AppLock.isLocked(this); }
    private void resumeWork() {
        if (!safeToInteract()) return;
        presence.update(project, true); refreshDue = true; refreshState();
        main.removeCallbacks(autoTick); main.postDelayed(autoTick, 5000);
    }
    private boolean canRead() {
        return safeToInteract() && usable() && dialogs.isEmpty() && !integrations.optBoolean("loading")
                && !state.optBoolean("connecting") && !state.optBoolean("authenticating") && !state.optBoolean("installing")
                && !state.optBoolean("sessionOpening") && !approvalWaiting()
                && child(state, "controls").optString("operation").isEmpty() && !child(state, "sessions").optBoolean("loading")
                && reads.available(SystemClock.elapsedRealtime());
    }
    private void refreshAutomatically() {
        if (!canRead()) return;
        boolean returned = browserDeparted && ("apps_refresh".equals(browserRefreshOperation) || "mcp_refresh".equals(browserRefreshOperation));
        if (section == 3 && !integrations.optBoolean("pluginPreview") && !returned) return;
        String key = sections[section];
        long interval = section == 1 ? 15000 : section == 3 ? 60000 : 30000;
        boolean error = !first(integrations.optString(key + "Error"), integrations.optString("error")).isEmpty();
        if (!reads.due(SystemClock.elapsedRealtime(), interval, error, refreshDue)) return;
        String operation = returned ? browserRefreshOperation : new String[]{"apps_refresh", "mcp_refresh", "skills_refresh", "plugins_load"}[section];
        refreshDue = false; automaticRequest = true;
        boolean accepted;
        try { accepted = request(operation, new JSONObject()); } finally { automaticRequest = false; }
        if (accepted) {
            firstLoad = true;
            if (returned) { browserRefreshOperation = ""; browserDeparted = false; }
        }
    }
    private void refreshState() {
        state = AgentService.snapshot(); integrations = state.optJSONObject("integrations");
        if (integrations == null) integrations = new JSONObject(); render();
        JSONObject detail = integrations.optJSONObject("pluginDetails");
        if (safeToInteract() && usable() && !integrations.optBoolean("loading") && !pendingPlugin.isEmpty() && detail != null && detail.length() > 0 && !detail.toString().equals(lastPluginDetails)) {
            String id = first(detail.optString("id"), child(detail, "summary").optString("id"), child(detail, "plugin").optString("id"));
            if (pendingPlugin.equals(id) && showPluginDetails(detail)) { lastPluginDetails = detail.toString(); pendingPlugin = ""; }
        }
        refreshAutomatically();
    }
    private void refreshSection() {
        if (section == 3 && !integrations.optBoolean("pluginPreview")) { pluginPreview(); return; }
        refreshDue = true; refreshAutomatically();
    }
    private boolean request(String operation, JSONObject payload) {
        if (!safeToInteract()) return false;
        if (!usable()) { toast("Connect Codex for this project first."); return false; }
        if (!reads.available(SystemClock.elapsedRealtime()) || integrations.optBoolean("loading")) { toast("Wait for the current request to finish."); return false; }
        if (CodexIntegrations.mutates(operation) && !mutationsAllowed()) { toast("Finish the current task or connection request first."); return false; }
        try {
            startService(new Intent(this, AgentService.class).setAction(AgentService.ACTION_INTEGRATION)
                    .putExtra(AgentService.EXTRA_PROVIDER, "codex").putExtra(AgentService.EXTRA_PROJECT, project)
                    .putExtra(AgentService.EXTRA_OPERATION, operation).putExtra(AgentService.EXTRA_PAYLOAD, payload.toString()));
            reads.attempted(SystemClock.elapsedRealtime()); status.setText("Updating…"); main.postDelayed(this::refreshState, 250); main.postDelayed(this::refreshState, 1600); return true;
        } catch (RuntimeException error) { reads.failed(SystemClock.elapsedRealtime()); if (!automaticRequest) message("Could not start", error.getMessage()); return false; }
    }

    private void sectionDetails() {
        if (!safeToInteract()) return;
        String[] descriptions = {
            "Connect services through your ChatGPT account. " + runtimeScopeDescription() + " Connections and permissions are checked again when you return from sign-in.",
            "MCP servers add tools to Codex. Only connect servers you trust: tools can send project content to their service. Reload connections after changing server configuration outside PocketAgent.",
            "Skills provide reusable instructions. Review a skill before enabling it, then choose Use in chat to attach it to a prompt.",
            "Official plugin-management methods are under development. Preview loads the catalogs your Codex engine exposes. Provider availability and permissions still apply."
        };
        AlertDialog.Builder detail = dialog().setTitle(titles[section]).setMessage(descriptions[section]).setNegativeButton("Done", null);
        if (section == 1 && mutationsAllowed()) detail.setPositiveButton("Reload connections", (d,w) -> request("mcp_reload", new JSONObject()));
        detail.show();
    }

    private void render() {
        if (body == null) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        String signature = section + ":" + shown + ":" + query + ":" + usable() + ":" + state.optBoolean("busy") + ":" + state.opt("permission") + ":" + reads.available(SystemClock.elapsedRealtime()) + ":" + integrations;
        if (signature.equals(lastSignature)) return;
        lastSignature = signature; body.removeAllViews();
        String key = sections[section];
        status.setText(integrations.optBoolean("loading") ? "Updating…" : !usable() ? "Offline"
                : !first(integrations.optString(key + "Error"), integrations.optString("error")).isEmpty() ? "Needs attention"
                : integrations.optBoolean(key + "Known") ? "Updates automatically" : "Waiting for Codex…");
        if (!usable()) {
            note("Connect Codex first", "Open a Codex conversation for this project to manage its tools and connections.");
            add(button("Go to Codex connection", "account", true, v -> { setResult(RESULT_OK, resultIntent().putExtra(EXTRA_CONNECT, true)); finish(); })); return;
        }
        if (approvalWaiting()) {
            note("Your approval is needed", "Codex is waiting for your decision in Chat. Review the request before continuing.");
            add(button("Return to chat", "chat", true, v -> { setResult(RESULT_OK, resultIntent()); finish(); }));
        }
        if (state.optBoolean("busy")) add(text("Task running · changes available when it finishes", 12, DeskStyle.MUTED));
        if (section == 0) {

            if (Boolean.FALSE.equals(integrations.opt("appsFeatureEnabled"))) add(mutationButton("Enable app connections", "account", true, v -> confirm("Enable apps in Codex?", "This allows Codex to load apps available to your ChatGPT account. Each provider connection still needs its own permission.", () -> request("apps_enable", new JSONObject()))));
            add(button("Find GitHub", "changes", false, v -> search.setText("GitHub")));
            add(button("Official app directory", "account", false, v -> browser("https://chatgpt.com/apps", "ChatGPT app directory", false)));
        } else if (section == 1) {

            add(mutationButton("Add MCP server", "new", true, v -> addMcp()));

        } else if (section == 2) {

            add(mutationButton("Create project skill", "new", true, v -> createSkill()));
        } else {
            add(text("Preview · official plugin methods are under development", 12, DeskStyle.MUTED));
            if (!integrations.optBoolean("pluginPreview")) { add(button("Load plugin preview", "folder", true, v -> pluginPreview())); return; }
        }
        String error = first(integrations.optString(key + "Error"), integrations.optString("error"));
        if (!error.isEmpty()) { note("Could not update", error); add(button("Retry", "refresh", false, v -> refreshSection())); }
        if (integrations.optBoolean(key + "Loading")) { ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true); add(progress); }
        renderBrowser();
        JSONArray rows = integrations.optJSONArray(key); if (rows == null) rows = new JSONArray();
        ArrayList<JSONObject> matches = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i); if (item == null) continue;
            String searchable = (item.optString("name") + " " + item.optString("displayName") + " " + item.optString("description")).toLowerCase(Locale.ROOT);
            if (query.isEmpty() || searchable.contains(query)) matches.add(item);
        }
        if (matches.isEmpty() && !integrations.optBoolean(key + "Loading")) {
            add(text(query.isEmpty() ? (integrations.optBoolean(key + "Known") ? "No " + key + " reported for this account or project yet." : "This section loads automatically when Codex is ready.") : "No matching items. Availability depends on your account and Codex engine.", 14, DeskStyle.MUTED));
        }
        for (int i = 0; i < Math.min(shown, matches.size()); i++) {
            JSONObject item = matches.get(i);
            if (section == 0) appCard(item); else if (section == 1) mcpCard(item); else if (section == 2) skillCard(item); else pluginCard(item);
        }
        if (matches.size() > shown) add(button("Show more (" + (matches.size() - shown) + ")", "new", false, v -> { shown += 60; lastSignature = ""; render(); }));
        if (integrations.optBoolean(key + "Truncated")) add(text("This catalog is larger than the client can display. Some items are omitted; the list is not complete.", 12, DeskStyle.MUTED));
        long updated = integrations.optLong(key + "UpdatedAt");
        if (updated > 0) add(text("Last checked: " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date(updated)), 11, DeskStyle.MUTED));
        if (section == 2) showListErrors(integrations.optJSONArray("skillErrors"));
        if (section == 3) showListErrors(integrations.optJSONArray("marketplaceErrors"));
    }
    private void appCard(JSONObject item) {
        String id = item.optString("id"), name = first(item.optString("name"), id);
        LinearLayout card = card(name, "account", item.optString("description"));
        boolean known = item.optBoolean("runtimeKnown"), ready = known && item.optBoolean("runtimeCallable");
        String label = ready ? runtimeReadyLabel() : known && !item.optBoolean("runtimeEnabled") ? "Disabled in reported runtime"
                : Boolean.FALSE.equals(item.opt("isEnabled")) ? "Disabled in Codex" : Boolean.TRUE.equals(item.opt("isAccessible")) ? "Account access available · tools not confirmed ready" : "Connection needed or unavailable";
        card.addView(text(label, 12, ready ? DeskStyle.ACCENT : DeskStyle.MUTED), lp(6, 6));
        if (!item.optString("installUrl").isEmpty()) card.addView(button("Open connection", "account", false, v -> request("app_open", obj("id", id))));
        if (item.opt("isEnabled") instanceof Boolean) {
            boolean enabled = item.optBoolean("isEnabled");
            card.addView(mutationButton(enabled ? "Disable in Codex" : "Enable in Codex", "settings", v -> confirm(enabled ? "Disable " + name + "?" : "Enable " + name + "?",
                    "This changes access inside Codex. It does not revoke your provider login. Manage account permissions on the official connection page.", () -> request("app_toggle", obj("id", id, "enabled", !enabled)))));
        }
        if (ready) card.addView(button("Use in chat", "chat", true, v -> {
            setResult(RESULT_OK, resultIntent().putExtra(EXTRA_APP_ID, id).putExtra(EXTRA_APP_NAME, name)
                    .putExtra(EXTRA_DRAFT, "Use " + name + " to ")); finish();
        }));
        add(card);
    }
    private String runtimeScope() { return first(integrations.optString("appRuntimeScope"), integrations.optString("runtimeScope")); }
    private String runtimeReadyLabel() {
        String scope = runtimeScope();
        return "account".equals(scope) || "global".equals(scope) ? "Ready in account runtime"
                : "thread".equals(scope) ? "Ready in this chat" : "Tools reported ready · scope not supplied";
    }
    private String runtimeScopeDescription() {
        String scope = runtimeScope();
        return "account".equals(scope) || "global".equals(scope)
                ? "Readiness describes the account runtime; the current chat's saved settings may differ."
                : "thread".equals(scope) ? "Readiness describes this chat's runtime."
                : "The engine has not supplied a runtime scope; readiness is not a guarantee of access in this chat.";
    }
    private void mcpCard(JSONObject item) {
        String name = item.optString("name");
        LinearLayout card = card(name, "settings", "");
        String runtime = item.isNull("runtimeStatus") ? "Not reported" : first(item.optString("runtimeStatus"), "Not reported");
        String configured = item.opt("enabled") instanceof Boolean ? item.optBoolean("enabled") ? "Enabled" : "Disabled" : "Not reported";
        card.addView(text("Server setting: " + configured + "\nRuntime: " + runtime + "\nSign-in: " + mcpAuthLabel(item.optString("authStatus"))
                + "\n" + item.optInt("toolCount") + " tools · " + item.optInt("resourceCount") + " resources", 12, DeskStyle.MUTED), lp(6, 6));
        String error = item.optString("toolsError"); if (!error.isEmpty() && !"null".equals(error)) card.addView(text(error, 12, DeskStyle.MUTED));
        if (!"unsupported".equals(item.optString("authStatus"))) card.addView(mutationButton("Connect / sign in", "account", v -> confirm("Connect " + name + "?", "Codex will request this server's official sign-in link. You will review the destination before opening your browser.", () -> request("mcp_oauth", obj("name", name)))));
        if (!first(item.optString("pluginId")).isEmpty()) card.addView(text("Server configuration is managed by its plugin.", 12, DeskStyle.MUTED), lp(6, 0));
        else if (item.opt("enabled") instanceof Boolean) {
            boolean enabled = item.optBoolean("enabled");
            card.addView(mutationButton(enabled ? "Disable server" : "Enable server", "settings", v -> confirm(
                    (enabled ? "Disable " : "Enable ") + name + "?", "This changes the server setting inside Codex. Disabling does not revoke provider credentials.",
                    () -> request("mcp_toggle", obj("name", name, "enabled", !enabled)))));
        } else card.addView(mutationButton("Configure server", "settings", v -> {
            if (!safeToInteract()) return;
            dialog().setTitle("Set server access").setItems(new String[]{"Enable in Codex", "Disable in Codex"}, (d,w) -> confirm(
                    (w == 0 ? "Enable " : "Disable ") + name + "?", "The current enabled setting was not reported. This writes your selected setting; it does not revoke provider credentials.",
                    () -> request("mcp_toggle", obj("name", name, "enabled", w == 0)))).setNegativeButton("Cancel", null).show();
        }));
        add(card);
    }
    private static String mcpAuthLabel(String value) {
        if ("unsupported".equals(value)) return "OAuth not supported by this server";
        if ("notLoggedIn".equals(value)) return "Sign-in needed";
        if ("oAuth".equals(value) || "oauth".equals(value)) return "OAuth credentials reported";
        if ("bearerToken".equals(value)) return "Token credentials configured";
        return "Status not reported";
    }
    private void skillCard(JSONObject item) {
        String name = first(item.optString("displayName"), item.optString("name"));
        LinearLayout card = card(name, "file", item.optString("description"));
        boolean enabled = item.optBoolean("enabled");
        card.addView(text((enabled ? "Enabled" : "Disabled") + " · " + item.optString("scope"), 12, enabled ? DeskStyle.ACCENT : DeskStyle.MUTED), lp(6, 6));
        card.addView(mutationButton(enabled ? "Disable skill" : "Enable skill", "settings", v -> request("skill_toggle", obj("path", item.optString("path"), "enabled", !enabled))));
        if (enabled) card.addView(button("Use in chat", "chat", true, v -> {
            setResult(RESULT_OK, resultIntent().putExtra(EXTRA_SKILL_PATH, item.optString("path")).putExtra(EXTRA_SKILL_NAME, item.optString("name"))); finish();
        }));
        add(card);
    }
    private void pluginCard(JSONObject item) {
        String id = item.optString("id"), name = first(item.optString("displayName"), item.optString("name"), id);
        LinearLayout card = card(name, "folder", item.optString("description"));
        card.addView(text((item.optBoolean("installed") ? "Installed" : "Not installed") + " · " + item.optString("marketplaceName"), 12, DeskStyle.MUTED), lp(6, 6));
        String reason = item.optString("disabledReason"); if (!reason.isEmpty() && !"null".equals(reason)) card.addView(text(reason, 12, DeskStyle.MUTED));
        card.addView(button("Review plugin", "file", false, v -> {
            if (request("plugin_read", obj("id", id))) { pendingPlugin = id; lastPluginDetails = ""; }
        }));
        if (item.optBoolean("installed")) card.addView(mutationButton("Uninstall", "stop", v -> confirm("Uninstall " + name + "?", "This removes the plugin from local Codex. Account connections may remain authorized at their providers.", () -> request("plugin_uninstall", obj("id", id, "confirmed", true)))));
        add(card);
    }
    private boolean showPluginDetails(JSONObject detail) {
        if (!safeToInteract() || !usable()) return false;
        JSONObject plugin = detail.optJSONObject("plugin"); if (plugin == null) plugin = detail;
        String id = first(plugin.optString("id"), child(plugin, "summary").optString("id"), detail.optString("id"));
        JSONObject row = find(integrations.optJSONArray("plugins"), id);
        if (row == null) return false;
        JSONObject summary = child(plugin, "summary");
        boolean complete = !detail.optBoolean("detailsTruncated") && !plugin.optBoolean("detailsTruncated") && id.equals(summary.optString("id"));
        String name = first(row.optString("displayName"), row.optString("name"));
        String description = first(plugin.optString("description"), row.optString("description"));
        StringBuilder info = new StringBuilder(name).append("\n\n").append(description)
                .append("\n\nMarketplace: ").append(first(plugin.optString("marketplaceName"), row.optString("marketplaceName"), "Not reported"))
                .append("\nMarketplace path: ").append(first(plugin.optString("marketplacePath"), row.optString("marketplacePath"), "Not reported"))
                .append("\nSource: ").append(pretty(firstValue(summary, row, "source")))
                .append("\nInstall policy: ").append(first(summary.optString("installPolicy"), row.optString("installPolicy"), "Provider controlled"))
                .append("\nAuthentication policy: ").append(first(summary.optString("authPolicy"), row.optString("authPolicy"), "Provider controlled"));
        String policyError = "";
        try { CodexIntegrationsProtocol.pluginInstallParams(row); CodexIntegrationsProtocol.pluginInstallParams(mergePolicy(row, summary)); }
        catch (RuntimeException restricted) { policyError = restricted.getMessage(); }
        if (!complete) info.append("\n\nThe engine's review details are incomplete or too large to display fully. Installation is unavailable until complete details can be reviewed.");
        if (!policyError.isEmpty()) info.append("\n\nInstallation: ").append(policyError);
        appendCapabilities(info, plugin, "skills", "Skills");
        appendCapabilities(info, plugin, "apps", "Connected apps");
        appendCapabilities(info, plugin, "mcpServers", "MCP servers");
        appendCapabilities(info, plugin, "hooks", "Hooks (reported metadata)");
        appendCapabilities(info, plugin, "appTemplates", "App templates");
        appendCapabilities(info, plugin, "scheduledTasks", "Scheduled tasks");
        info.append("\n\nPlugins can add instructions and tools that access your project or connected services. Install only sources you trust. Installing does not automatically authorize its app connections.");
        ScrollView contents = new ScrollView(this); TextView content = text(info.toString(), 14, DeskStyle.TEXT); content.setTextIsSelectable(true); content.setPadding(dp(20), dp(8), dp(20), dp(12)); contents.addView(content);
        AlertDialog.Builder dialog = dialog().setTitle("Review plugin · Preview").setView(contents).setNegativeButton("Close", null);
        boolean installable = complete && policyError.isEmpty() && !row.optBoolean("installed");
        if (installable) dialog.setPositiveButton("Install", null);
        AlertDialog opened = dialog.show();
        if (installable) {
            final String reviewedDetails = detail.toString();
            Button install = opened.getButton(AlertDialog.BUTTON_POSITIVE);
            install.setEnabled(mutationsAllowed());
            install.setOnClickListener(v -> {
                if (!safeToInteract() || !usable()) return;
                JSONObject current = integrations.optJSONObject("pluginDetails");
                if (current == null || !reviewedDetails.equals(current.toString())) { message("Review changed", "Reopen and review the latest plugin details before installing."); return; }
                if (request("plugin_install", obj("id", id, "confirmed", true))) opened.dismiss();
            });
        }
        return true;
    }
    private static JSONObject mergePolicy(JSONObject row, JSONObject summary) {
        JSONObject merged = obj();
        for (java.util.Iterator<String> keys = row.keys(); keys.hasNext();) { String key = keys.next(); try { merged.put(key, row.opt(key)); } catch (Exception ignored) { } }
        for (String key : new String[]{"availability", "installPolicy", "disabledReason"}) if (summary.has(key))
            try { merged.put(key, summary.opt(key)); } catch (Exception ignored) { }
        return merged;
    }
    private static Object firstValue(JSONObject first, JSONObject second, String key) {
        Object value = first.opt(key); return value == null || value == JSONObject.NULL ? second.opt(key) : value;
    }
    private static void appendCapabilities(StringBuilder out, JSONObject plugin, String key, String title) {
        out.append("\n\n").append(title).append('\n');
        Object value = plugin.opt(key);
        if (value instanceof JSONArray && ((JSONArray)value).length() == 0) out.append("None reported.");
        else if (value instanceof JSONObject && ((JSONObject)value).length() == 0) out.append("None reported.");
        else out.append(pretty(value));
    }
    private static String pretty(Object value) {
        if (value == null || value == JSONObject.NULL) return "Not reported by the engine.";
        try {
            if (value instanceof JSONObject) return ((JSONObject)value).toString(2);
            if (value instanceof JSONArray) return ((JSONArray)value).toString(2);
        } catch (Exception ignored) { }
        return String.valueOf(value);
    }
    private void renderBrowser() {
        String oauth = integrations.optString("oauthUrl"), app = integrations.optString("browserUrl");
        if (!oauth.isEmpty() && !"null".equals(oauth)) {
            note("Finish MCP sign-in", "Server: " + integrations.optString("oauthServer") + ". Review the sign-in page in your browser. PocketAgent checks the reported status when you return and unlock.");
            add(button("Continue in browser", "account", true, v -> browser(oauth, integrations.optString("oauthServer"), true)));
        }
        if (!app.isEmpty() && !"null".equals(app)) add(button("Continue " + first(integrations.optString("browserTitle"), "connection"), "account", true, v -> browser(app, integrations.optString("browserTitle"), false)));
        JSONArray needed = integrations.optJSONArray("appsNeedingAuth");
        if (needed != null && needed.length() > 0) note("App connection still needed", "The plugin installed, but its services need account authorization. Open Apps and connect each service. Status updates when you return.");
    }
    private void pluginPreview() {
        confirm("Load plugin preview?", "These official Codex methods are under development. Browse the engine's catalog and review each plugin before installing. This beta does not claim full desktop compatibility.", () -> request("plugins_load", new JSONObject()));
    }
    private void addMcp() {
        if (!safeToInteract() || !mutationsAllowed()) return;
        ScrollView scroller = new ScrollView(this);
        LinearLayout fields = column(); fields.setPadding(dp(20), dp(8), dp(20), dp(8));
        scroller.addView(fields);
        EditText name = input("Server name, e.g. my-docs"), url = input("https://your-server.example/mcp");
        name.setSingleLine(true); url.setSingleLine(true); url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        fields.addView(name, lp(0, 8)); fields.addView(url, lp(0, 8));
        fields.addView(text("Use a server you trust. Its tools may send project content to that service. OAuth sign-in happens in your browser; do not paste passwords or tokens here.", 13, DeskStyle.MUTED));
        AlertDialog form = dialog().setTitle("Add remote MCP server").setView(scroller).setNegativeButton("Cancel", null)
                .setPositiveButton("Add server", null).show();
        form.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            name.setError(null); url.setError(null);
            String serverName, endpoint;
            try { serverName = CodexIntegrationsProtocol.mcpName(name.getText().toString().trim()); }
            catch (RuntimeException invalid) { fieldError(name, invalid.getMessage()); return; }
            if (find(integrations.optJSONArray("mcp"), serverName, "name") != null) { fieldError(name, "This server name is already listed. Choose a different name."); return; }
            try { endpoint = CodexIntegrationsProtocol.remoteMcpUrl(url.getText().toString().trim()); }
            catch (RuntimeException invalid) { fieldError(url, invalid.getMessage()); return; }
            if (request("mcp_add", obj("name", serverName, "url", endpoint))) form.dismiss();
        });
    }
    private void createSkill() {
        if (!safeToInteract() || !mutationsAllowed()) return;
        ScrollView scroller = new ScrollView(this); LinearLayout fields = column(); fields.setPadding(dp(20), dp(8), dp(20), dp(8)); scroller.addView(fields);
        EditText name = input("Skill name, e.g. review-code"), description = input("When should Codex use this skill?"), instructions = input("Instructions for Codex");
        name.setSingleLine(true); description.setMaxLines(3); instructions.setMinLines(4); instructions.setMaxLines(8);
        fields.addView(name, lp(0, 8)); fields.addView(description, lp(0, 8)); fields.addView(instructions, lp(0, 8));
        fields.addView(text("Saved inside this project's .agents/skills folder. Review instructions before enabling them. Existing skills are never overwritten.", 12, DeskStyle.MUTED));
        AlertDialog form = dialog().setTitle("Create project skill").setView(scroller).setNegativeButton("Cancel", null)
                .setPositiveButton("Create", null).show();
        form.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            name.setError(null); description.setError(null); instructions.setError(null);
            String skillName = name.getText().toString().trim();
            if (skillName.length() > CodexSkillFiles.MAX_NAME || !skillName.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")) {
                fieldError(name, "Use 1–64 lowercase letters, numbers and single hyphens; start with a letter."); return;
            }
            if (!requiredField(description, CodexSkillFiles.MAX_DESCRIPTION, "Description")
                    || !requiredField(instructions, CodexSkillFiles.MAX_INSTRUCTIONS, "Instructions")) return;
            String summary = description.getText().toString().trim(), body = instructions.getText().toString();
            try { CodexSkillFiles.markdown(skillName, summary, body); }
            catch (java.io.IOException invalid) { fieldError(instructions, invalid.getMessage()); return; }
            if (request("skill_create", obj("name", skillName, "description", summary, "instructions", body))) form.dismiss();
        });
    }
    private boolean requiredField(EditText field, int maximum, String label) {
        String value = field.getText().toString();
        if (value.trim().isEmpty()) { fieldError(field, label + " is required."); return false; }
        if (value.length() > maximum) { fieldError(field, label + " must be " + maximum + " characters or fewer."); return false; }
        if (value.indexOf('\0') >= 0) { fieldError(field, label + " must contain text, not binary data."); return false; }
        return true;
    }
    private void fieldError(EditText field, String message) { field.setError(message); field.requestFocus(); }
    private void browser(String value, String name, boolean custom) {
        if (!safeToInteract()) return;
        try {
            String validated = custom ? CodexIntegrationsProtocol.oauthUrl(value)
                    : CodexIntegrationsProtocol.appInstallUrl(obj("installUrl", value));
            java.net.URI uri = new java.net.URI(validated);
            String host = uri.getHost();
            confirm("Open " + host + "?", "Connection: " + first(name, "Account") + "\n\nReview the website and requested permissions before signing in. Returning to PocketAgent does not by itself confirm a successful connection.", () -> {
                if (!safeToInteract()) return;
                try {
                    browserRefreshOperation = custom ? "mcp_refresh" : "apps_refresh"; browserDeparted = false;
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(validated)).addCategory(Intent.CATEGORY_BROWSABLE));
                } catch (RuntimeException error) {
                    browserRefreshOperation = ""; browserDeparted = false;
                    message("Could not open browser", error.getMessage());
                }
            });
        } catch (Exception error) { message("Unsupported connection link", error.getMessage()); }
    }
    private Intent resultIntent() { return new Intent().putExtra(AgentService.EXTRA_PROJECT, project); }
    private void draft(String value) { setResult(RESULT_OK, resultIntent().putExtra(EXTRA_DRAFT, value)); finish(); }
    private void showListErrors(JSONArray errors) {
        if (errors == null || errors.length() == 0) return;
        add(button("View loading details", "file", false, v -> message("Loading details", errors.toString())));
    }
    private void confirm(String title, String content, Runnable action) { if (safeToInteract()) dialog().setTitle(title).setMessage(content).setPositiveButton("Continue", (d,w) -> { if (safeToInteract()) action.run(); }).setNegativeButton("Cancel", null).show(); }
    private void message(String title, String content) { if (safeToInteract()) dialog().setTitle(title).setMessage(content).setPositiveButton("Done", null).show(); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, DeskStyle.dialogTheme(this)) {
            @Override public AlertDialog show() {
                AlertDialog value = super.create();
                if (value.getWindow() != null) {
                    if (AppLock.enabled(ConnectionsActivity.this)) value.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                    value.getWindow().setBackgroundDrawable(DeskStyle.card(ConnectionsActivity.this));
                }
                dialogs.add(value); value.setOnDismissListener(d -> dialogs.remove(value)); value.show(); return value;
            }
        };
    }
    private Button mutationButton(String title, String icon, View.OnClickListener listener) {
        return mutationButton(title, icon, false, listener);
    }
    private Button mutationButton(String title, String icon, boolean primary, View.OnClickListener listener) {
        Button button = button(title, icon, primary, v -> {
            if (!mutationsAllowed()) { toast("Finish the current task or connection request first."); return; }
            listener.onClick(v);
        });
        button.setEnabled(mutationsAllowed()); button.setAlpha(button.isEnabled() ? 1f : .5f); return button;
    }
    private boolean approvalWaiting() { JSONObject permission = state.optJSONObject("permission"); return permission != null && permission.length() > 0; }
    private boolean mutationsAllowed() { return usable() && !state.optBoolean("busy") && !state.optBoolean("sessionOpening")
            && !state.optBoolean("connecting") && !state.optBoolean("authenticating") && !integrations.optBoolean("loading")
            && !child(state, "sessions").optBoolean("loading") && child(state, "controls").optString("operation").isEmpty()
            && reads.available(SystemClock.elapsedRealtime()) && !approvalWaiting(); }
    private Button button(String title, String iconName, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false); button.setTextSize(13); button.setTextColor(primary ? DeskStyle.PRIMARY_TEXT : DeskStyle.TEXT);
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setPadding(dp(12), dp(6), dp(12), dp(6)); button.setStateListAnimator(null);
        button.setBackground(primary ? DeskStyle.primary(this) : Ui.tappable(this, DeskStyle.field(this), DeskStyle.isDark(this)));
        Drawable icon = DeskStyle.icon(this, iconName, primary ? DeskStyle.PRIMARY_TEXT : DeskStyle.ACCENT); icon.setBounds(0, 0, dp(18), dp(18)); button.setCompoundDrawablesRelative(icon, null, null, null); button.setCompoundDrawablePadding(dp(6));
        button.setOnClickListener(listener); button.setLayoutParams(lp(4, 4)); return button;
    }
    private LinearLayout card(String title, String iconName, String description) {
        LinearLayout card = column(); card.setPadding(dp(14), dp(12), dp(14), dp(12)); card.setBackground(DeskStyle.card(this));
        TextView heading = label(title, 16, DeskStyle.TEXT); Drawable icon = DeskStyle.icon(this, iconName, DeskStyle.ACCENT); icon.setBounds(0, 0, dp(20), dp(20)); heading.setCompoundDrawablesRelative(icon, null, null, null); heading.setCompoundDrawablePadding(dp(8)); card.addView(heading);
        if (!description.isEmpty() && !"null".equals(description)) { TextView text = text(description, 13, DeskStyle.MUTED); text.setMaxLines(4); text.setEllipsize(TextUtils.TruncateAt.END); card.addView(text, lp(6, 2)); }
        return card;
    }
    private void note(String title, String detail) { LinearLayout card = card(title, "file", detail); add(card); }
    private void add(View view) { body.addView(view, lp(0, 10)); }
    private LinearLayout column() { LinearLayout result = new LinearLayout(this); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private LinearLayout row() { LinearLayout result = new LinearLayout(this); result.setOrientation(LinearLayout.HORIZONTAL); result.setGravity(Gravity.CENTER_VERTICAL); return result; }
    private TextView text(String value, int size, int color) { TextView result = new TextView(this); result.setText(value); result.setTextSize(size); result.setTextColor(color); result.setLineSpacing(dp(2), 1.05f); return result; }
    private TextView label(String value, int size, int color) { TextView result = text(value, size, color); result.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return result; }
    private EditText input(String hint) { EditText result = new EditText(this); result.setHint(hint); result.setTextSize(14); result.setTextColor(DeskStyle.TEXT); result.setHintTextColor(DeskStyle.MUTED); result.setPadding(dp(12), dp(10), dp(12), dp(10)); result.setBackground(DeskStyle.field(this)); return result; }
    private LinearLayout.LayoutParams lp(int top, int bottom) { LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(-1, -2); result.setMargins(0, dp(top), 0, dp(bottom)); return result; }
    private int dp(int value) { return Ui.dp(this, value); }
    private static JSONObject obj(Object... fields) { return AgentProtocol.object(fields); }
    private static JSONObject child(JSONObject value, String key) { JSONObject child = value.optJSONObject(key); return child == null ? new JSONObject() : child; }
    private static JSONObject find(JSONArray values, String id) { if (values != null) for (int i = 0; i < values.length(); i++) { JSONObject value = values.optJSONObject(i); if (value != null && id.equals(value.optString("id"))) return value; } return null; }
    private static JSONObject find(JSONArray values, String value, String key) { if (values != null) for (int i = 0; i < values.length(); i++) { JSONObject row = values.optJSONObject(i); if (row != null && value.equals(row.optString(key))) return row; } return null; }
    private static String first(String... values) { for (String value : values) if (value != null && !value.isEmpty() && !"null".equals(value)) return value; return ""; }
    /** Monotonic request throttle; shared by secondary screens and host-testable without Android. */
    static final class ReadRefresh {
        private long last = -1, pendingUntil, retryAfter;
        boolean available(long now) { return now >= pendingUntil; }
        boolean due(long now, long interval, boolean error, boolean force) {
            if (!available(now)) return false;
            return force || (now >= retryAfter && (last < 0 || now - last >= (error ? Math.max(60000, interval) : interval)));
        }
        void attempted(long now) { last = now; pendingUntil = now + 1500; retryAfter = 0; }
        void failed(long now) { attempted(now); retryAfter = now + 60000; }
    }
    /** One lease per Activity avoids a stopping screen disabling another visible screen. */
    static final class ClientPresence {
        private final Activity activity;
        private final String id = java.util.UUID.randomUUID().toString();
        private boolean active;
        private long lastSent;
        ClientPresence(Activity activity) { this.activity = activity; }
        void update(String project, boolean visible) {
            JSONObject snapshot = AgentService.snapshot();
            if (!snapshot.optBoolean("engineRunning") && !snapshot.optBoolean("connecting")
                    && !snapshot.optBoolean("authenticating") && !snapshot.optBoolean("installing")) {
                active = false;
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (visible == active && (!visible || now - lastSent < 45000)) return;
            try {
                activity.startService(new Intent(activity, AgentService.class).setAction(AgentService.ACTION_CLIENT)
                        .putExtra(AgentService.EXTRA_PROVIDER, "codex").putExtra(AgentService.EXTRA_PROJECT, project)
                        .putExtra(AgentService.EXTRA_CLIENT_ID, id).putExtra(AgentService.EXTRA_ACTIVE, visible));
                active = visible; lastSent = now;
            } catch (RuntimeException ignored) { /* Visibility never reconnects or prompts for sign-in. */ }
        }
    }

}
