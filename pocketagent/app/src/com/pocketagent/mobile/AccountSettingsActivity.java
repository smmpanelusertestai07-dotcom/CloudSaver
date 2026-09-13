package com.pocketagent.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;

/** Browser-managed provider settings plus real device-local event notification preferences. */
public final class AccountSettingsActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 286;
    private final ArrayList<AlertDialog> dialogs = new ArrayList<>();
    private FrameLayout lockShell;
    private TextView notificationStatus;
    private String provider = "codex", appearance;
    private boolean resumed;

    static void open(Activity activity, String provider) {
        activity.startActivity(new Intent(activity, AccountSettingsActivity.class).putExtra("provider", provider));
    }
    @Override protected void onCreate(Bundle saved) {
        DeskStyle.apply(this); appearance = DeskStyle.themeSignature(this); super.onCreate(saved);
        String selected = saved == null ? getIntent().getStringExtra("provider") : saved.getString("provider");
        if (AgentCatalog.isValid(selected)) provider = selected;
        AgentNotifications.ensureChannels(this); build();
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (!appearance.equals(DeskStyle.themeSignature(this))) { recreate(); return; }
        DeskStyle.applySystemBars(this); AppLock.applyWindowSecurity(this);
        if (AppLock.isLocked(this)) AppLock.show(this, lockShell, this::refreshNotificationStatus);
        else refreshNotificationStatus();
    }
    @Override protected void onPause() { resumed = false; super.onPause(); }
    @Override protected void onStop() {
        for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle saved) { saved.putString("provider", provider); super.onSaveInstanceState(saved); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); AppLock.handleResult(this, lockShell, request, result, this::refreshNotificationStatus);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants); if (request == REQUEST_NOTIFICATIONS) refreshNotificationStatus();
    }
    private boolean safe() { return resumed && !isFinishing() && !AppLock.isLocked(this); }

    private void build() {
        LinearLayout root = column(); root.setBackground(DeskStyle.background(this));
        lockShell = new FrameLayout(this); lockShell.addView(root, new FrameLayout.LayoutParams(-1, -1)); setContentView(lockShell);
        root.setPadding(dp(16), dp(8), dp(16), dp(8));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int extra = Math.max(0, (getResources().getDisplayMetrics().widthPixels - bars.left - bars.right - dp(680)) / 2);
                root.setPadding(dp(16) + bars.left + extra, dp(8) + bars.top, dp(16) + bars.right + extra, dp(8) + bars.bottom);
            } else root.setPadding(dp(16) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(), dp(16) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(button("Back", v -> finish()), new LinearLayout.LayoutParams(-2, -2));
        TextView title = heading("Account settings", 21); title.setPadding(dp(12), 0, 0, 0); toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(toolbar);
        ScrollView scroll = new ScrollView(this); LinearLayout body = column(); body.setPadding(dp(4), dp(16), dp(4), dp(18));
        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(button(AgentCatalog.name(provider) + "  ·  Change", v -> providerPicker()), space(0, 8));
        body.addView(text(ProviderAccountLinks.note(provider), 13, DeskStyle.MUTED), space(0, 14));
        for (ProviderAccountLinks.Link link : ProviderAccountLinks.forProvider(provider)) {
            LinearLayout card = card(); card.addView(button(link.title + "  ↗", v -> openLink(link)));
            card.addView(text(link.detail, 13, DeskStyle.MUTED), space(5, 0)); body.addView(card, space(0, 8));
        }
        body.addView(heading("Notifications on this phone", 18), space(20, 8));
        notificationStatus = text("", 13, DeskStyle.MUTED); body.addView(notificationStatus, space(0, 8));
        LinearLayout choices = card();
        addToggle(choices, "Task completed", "complete"); addToggle(choices, "Needs approval", "approval"); addToggle(choices, "Agent errors", "error");
        body.addView(choices, space(0, 8));
        body.addView(button("Allow notifications", v -> {
            if (!safe()) return;
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            else openNotificationSettings();
        }), space(0, 4));
        body.addView(button("Android notification settings", v -> openNotificationSettings()), space(0, 8));
        body.addView(text("These choices apply across providers. Alerts use generic text and never approve tools. Delivery depends on Android and the engine's supported events.", 12, DeskStyle.MUTED), space(0, 12));
        body.addView(button("Storage & privacy details", v -> privacyDetails()));
        AppLock.applyWindowSecurity(this); refreshNotificationStatus();
    }
    private void addToggle(LinearLayout parent, String label, String kind) {
        Switch toggle = new Switch(this); toggle.setText(label); toggle.setTextColor(DeskStyle.TEXT); toggle.setTextSize(15);
        toggle.setMinHeight(dp(56)); toggle.setPadding(dp(4), dp(8), dp(4), dp(8)); toggle.setChecked(AgentNotifications.enabled(this, kind));
        toggle.setOnCheckedChangeListener((view, checked) -> {
            if (!safe()) { toggle.setChecked(AgentNotifications.enabled(this, kind)); return; }
            AgentNotifications.setEnabled(this, kind, checked); refreshNotificationStatus();
        });
        parent.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
    }
    private void refreshNotificationStatus() {
        if (notificationStatus != null) notificationStatus.setText(AgentNotifications.systemAllowed(this)
                ? "Android allows app notifications. Sound and individual channels can be changed in Android settings."
                : "Android notifications are off. Your category choices are saved; allow notifications to receive alerts.");
    }
    private void providerPicker() {
        if (!safe()) return;
        String[] ids = AgentCatalog.IDS.clone(), labels = new String[ids.length]; for (int i=0;i<ids.length;i++) labels[i]=AgentCatalog.name(ids[i]);
        show(new AlertDialog.Builder(this, DeskStyle.dialogTheme(this)).setTitle("Provider account")
                .setItems(labels, (dialog, which) -> { if (safe()) { provider = ids[which]; build(); } }).setNegativeButton("Close", null));
    }
    private void openLink(ProviderAccountLinks.Link link) {
        if (!safe() || !ProviderAccountLinks.allows(provider, link.url)) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (RuntimeException unavailable) { toast("No browser is available to open the provider's website."); }
    }
    private void openNotificationSettings() {
        if (!safe()) return;
        try { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); }
        catch (RuntimeException unavailable) { toast("Open Android Settings → Apps → PocketAgent → Notifications."); }
    }
    private void privacyDetails() {
        if (!safe()) return;
        show(new AlertDialog.Builder(this, DeskStyle.dialogTheme(this)).setTitle("Privacy in PocketAgent")
                .setMessage("Workspaces and saved CLI credentials use this app's private storage. Android backup is disabled. Phone folders are not mounted; you choose imports and exports with Android's picker.\n\nAI requests and connected tools can send project data to their providers. Approved Ubuntu tools share local files and saved credentials; PRoot is not a separate security sandbox.\n\nAccount training and retention settings belong to the provider. The links above open the real controls or official instructions; PocketAgent does not claim those choices are enabled or disabled.\n\nLocal notifications show generic text. If you separately pair Android Wireless debugging for testing, that grants additional phone control.")
                .setPositiveButton("Done", null));
    }
    private void show(AlertDialog.Builder builder) {
        if (!safe()) return; AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            if (AppLock.enabled(this)) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getWindow().setBackgroundDrawable(DeskStyle.card(this));
        }
        dialogs.add(dialog); dialog.setOnDismissListener(d -> dialogs.remove(dialog)); dialog.show();
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout card() { LinearLayout view=column();view.setPadding(dp(12),dp(10),dp(12),dp(10));view.setBackground(DeskStyle.card(this));return view; }
    private TextView text(String value,int size,int color) { TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setLineSpacing(0,1.1f);return view; }
    private TextView heading(String value,int size) { TextView view=text(value,size,DeskStyle.TEXT);view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return view; }
    private Button button(String value,View.OnClickListener listener) { Button view=new Button(this);view.setText(value);view.setTextSize(14);view.setAllCaps(false);view.setTextColor(DeskStyle.TEXT);view.setMinHeight(dp(48));view.setBackground(Ui.tappable(this,DeskStyle.field(this),DeskStyle.isDark(this)));view.setOnClickListener(listener);return view; }
    private LinearLayout.LayoutParams space(int top,int bottom) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(top),0,dp(bottom));return p; }
    private int dp(int value) { return Ui.dp(this,value); }
    private void toast(String value) { Toast.makeText(this,value,Toast.LENGTH_LONG).show(); }
}
