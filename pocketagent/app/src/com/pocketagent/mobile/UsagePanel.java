package com.pocketagent.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

/** A stable live view of provider-reported usage. It never infers a quota reset. */
final class UsagePanel extends LinearLayout {
    private final TextView status, balance, resetCount, resetResult;
    private final LinearLayout limits;
    private final Button reset;
    private final ArrayList<WindowRow> rows = new ArrayList<>();
    private final boolean codex;

    UsagePanel(Context context, boolean codex, Runnable useReset, Runnable manageCredits, Runnable details) {
        super(context); this.codex = codex;
        setOrientation(VERTICAL); setPadding(dp(20), dp(8), dp(20), dp(12));
        status = text("Updating…", 12, DeskStyle.MUTED); addView(status, space(0, 16));
        limits = new LinearLayout(context); limits.setOrientation(VERTICAL); addView(limits);
        balance = text("", 16, DeskStyle.TEXT); addView(balance, space(10, 10));
        resetCount = text("", 13, DeskStyle.MUTED); addView(resetCount, space(2, 4));
        reset = button("Use reset credit", true, useReset); addView(reset, space(4, 4));
        resetResult = text("", 12, DeskStyle.MUTED); addView(resetResult, space(4, 8));
        addView(button(codex ? "Manage credits" : "Open usage", false, manageCredits), space(4, 2));
        addView(button("Details", false, details), space(2, 0));
    }

    void update(UsageDisplay usage) {
        String freshness = usage.refreshing ? "Updating…" : usage.stale ? "Waiting for latest usage"
                : usage.updatedAt > 0 ? "Updated " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(usage.updatedAt)) : "Usage unavailable";
        status.setText(codex ? freshness : "View usage in your provider account");
        JSONArray windows = usage.windows;
        int count = Math.min(6, windows.length());
        if (rows.size() != count) {
            limits.removeAllViews(); rows.clear();
            for (int i = 0; i < count; i++) { WindowRow row = new WindowRow(); rows.add(row); limits.addView(row.root, space(0, 18)); }
        }
        for (int i = 0; i < count; i++) {
            JSONObject window = windows.optJSONObject(i); if (window == null) continue;
            WindowRow row = rows.get(i);
            row.title.setText(window.optString("label", "Usage"));
            double remaining = Math.max(0, Math.min(100, window.optDouble("remainingPercent", 0)));
            row.value.setText(String.format(java.util.Locale.ROOT, "%.0f%% left", remaining));
            row.bar.setProgress((int)Math.round(remaining * 10));
            row.bar.setProgressTintList(ColorStateList.valueOf(remaining <= 10 ? DeskStyle.WARNING : DeskStyle.ACCENT));
            long seconds = window.optLong("resetsAt", 0);
            String resetAt = "";
            if (seconds > 0 && seconds < Long.MAX_VALUE / 1000) {
                long at = seconds * 1000;
                resetAt = at <= System.currentTimeMillis() ? "Checking reset…"
                        : "Resets " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(at));
            }
            row.time.setText(resetAt); row.time.setVisibility(resetAt.isEmpty() ? GONE : VISIBLE);
        }
        balance.setText(usage.creditsBalance.isEmpty() ? "" : "Credits  ·  " + usage.creditsBalance);
        balance.setVisibility(codex && !usage.creditsBalance.isEmpty() ? VISIBLE : GONE);
        resetCount.setText(usage.resetCredits >= 0 ? "Reset credits  ·  " + usage.resetCredits : "");
        resetCount.setVisibility(codex && usage.resetCredits >= 0 ? VISIBLE : GONE);
        reset.setText(usage.resetCanRetry ? "Retry reset" : "Use reset credit");
        reset.setVisibility(codex && (usage.resetCredits >= 0 || usage.resetCanRetry) ? VISIBLE : GONE);
        reset.setEnabled(usage.canResetCredits); reset.setAlpha(usage.canResetCredits ? 1f : .45f);
        String message = !usage.resetError.isEmpty() ? usage.resetError : usage.resetStatus;
        resetResult.setText(message); resetResult.setTextColor(usage.resetError.isEmpty() ? DeskStyle.MUTED : DeskStyle.ERROR);
        resetResult.setVisibility(message.isEmpty() ? GONE : VISIBLE);
    }

    private final class WindowRow {
        final LinearLayout root = new LinearLayout(getContext());
        final TextView title = text("", 14, DeskStyle.TEXT), value = text("", 14, DeskStyle.TEXT), time = text("", 12, DeskStyle.MUTED);
        final ProgressBar bar = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        WindowRow() {
            root.setOrientation(VERTICAL);
            LinearLayout heading = new LinearLayout(getContext()); heading.setGravity(Gravity.CENTER_VERTICAL);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            heading.addView(title, new LayoutParams(0, -2, 1)); heading.addView(value); root.addView(heading);
            bar.setMax(1000); bar.setProgressBackgroundTintList(ColorStateList.valueOf(DeskStyle.LINE));
            LayoutParams line = new LayoutParams(-1, dp(5)); line.topMargin = dp(10); line.bottomMargin = dp(8); root.addView(bar, line);
            root.addView(time);
        }
    }
    private TextView text(String value, int size, int color) { TextView v = new TextView(getContext()); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v; }
    private Button button(String label, boolean primary, Runnable action) {
        Button v = new Button(getContext()); v.setText(label); v.setTextSize(14); v.setAllCaps(false); v.setMinHeight(dp(48));
        v.setTextColor(primary ? DeskStyle.PRIMARY_TEXT : DeskStyle.TEXT); v.setBackground(primary ? DeskStyle.primary(getContext()) : Ui.tappable(getContext(), DeskStyle.field(getContext()), true));
        v.setOnClickListener(view -> action.run()); return v;
    }
    private LayoutParams space(int top, int bottom) { LayoutParams p = new LayoutParams(-1, -2); p.topMargin = dp(top); p.bottomMargin = dp(bottom); return p; }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
