package com.pocketagent.mobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Device-local appearance preferences; typography follows Android's font and text scaling. */
public final class AppearanceActivity extends Activity {
    private FrameLayout lockShell;
    private String appearance;

    @Override protected void onCreate(Bundle saved) {
        DeskStyle.apply(this); appearance = DeskStyle.themeSignature(this);
        super.onCreate(saved); build();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!appearance.equals(DeskStyle.themeSignature(this))) { recreate(); return; }
        DeskStyle.applySystemBars(this); AppLock.applyWindowSecurity(this);
        if (AppLock.isLocked(this)) AppLock.show(this, lockShell, () -> { });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        AppLock.handleResult(this, lockShell, request, result, () -> { });
    }

    private void build() {
        DeskStyle.apply(this);
        LinearLayout root = column(); root.setBackground(DeskStyle.background(this));
        lockShell = new FrameLayout(this); lockShell.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(lockShell); AppLock.applyWindowSecurity(this);
        root.setPadding(dp(16), dp(8), dp(16), dp(8));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int extra = Math.max(0, (getResources().getDisplayMetrics().widthPixels - bars.left - bars.right - dp(680)) / 2);
                root.setPadding(dp(16) + bars.left + extra, dp(8) + bars.top, dp(16) + bars.right + extra, dp(8) + bars.bottom);
            } else root.setPadding(dp(16) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(),
                    dp(16) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        DeskStyle.applySystemBars(this);
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setMinimumHeight(dp(56));
        Button back = button("Back", "back", v -> finish()); back.setBackground(DeskStyle.plain(this));
        toolbar.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = text("Appearance", 20, DeskStyle.TEXT); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(12), 0, 0, 0); toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(toolbar);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body = column(); body.setPadding(0, dp(16), 0, dp(24));
        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(heading("Theme"), space(0, 8));
        RadioGroup choices = new RadioGroup(this); choices.setOrientation(RadioGroup.VERTICAL);
        choices.setBackground(DeskStyle.card(this));
        String[] labels = {"System", "Light", "Dark"};
        String[] values = {"system", "light", "dark"};
        int selected = 9100;
        for (int i = 0; i < labels.length; i++) {
            RadioButton option = new RadioButton(this); option.setId(9100 + i); option.setText(labels[i]);
            option.setTextSize(15); option.setTextColor(DeskStyle.TEXT); option.setMinHeight(dp(56));
            option.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            option.setPadding(dp(16), dp(10), dp(16), dp(10));
            option.setButtonTintList(android.content.res.ColorStateList.valueOf(DeskStyle.TEXT));
            option.setBackground(DeskStyle.plain(this));
            choices.addView(option, new RadioGroup.LayoutParams(-1, -2));
            if (values[i].equals(DeskStyle.themeChoice(this))) selected = option.getId();
        }
        choices.check(selected);
        choices.setOnCheckedChangeListener((group, checked) -> {
            if (AppLock.isLocked(this)) return;
            int index = checked - 9100; if (index < 0 || index >= values.length) return;
            if (!values[index].equals(DeskStyle.themeChoice(this))) { DeskStyle.setThemeChoice(this, values[index]); recreate(); }
        });
        body.addView(choices, space(0, 8));
        TextView system = text("System follows your device appearance.", 13, DeskStyle.MUTED);
        system.setPadding(dp(4), 0, dp(4), 0); body.addView(system, space(0, 24));
        body.addView(heading("Text size"), space(0, 8));
        int scale = Math.round(getResources().getConfiguration().fontScale * 100);
        body.addView(button("Text size · " + scale + "%", "settings", v -> {
            if (AppLock.isLocked(this)) return;
            try { startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); }
            catch (RuntimeException unavailable) { Toast.makeText(this, "Open Android Settings → Display.", Toast.LENGTH_LONG).show(); }
        }), space(0, 8));
        TextView sizeHint = text("Uses your Android display settings.", 13, DeskStyle.MUTED);
        sizeHint.setPadding(dp(4), 0, dp(4), 0); body.addView(sizeHint);
    }

    private TextView heading(String value) { TextView title = text(value, 13, DeskStyle.MUTED); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return title; }
    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setLineSpacing(0, 1.12f); return text;
    }
    private Button button(String title, String name, View.OnClickListener action) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false); button.setTextSize(14);
        button.setTextColor(DeskStyle.TEXT); button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(8)); button.setStateListAnimator(null);
        button.setBackground(DeskStyle.secondary(this));
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        Drawable icon = DeskStyle.icon(this, name, DeskStyle.TEXT); icon.setBounds(0, 0, dp(18), dp(18));
        button.setCompoundDrawablesRelative(icon, null, null, null); button.setCompoundDrawablePadding(dp(8));
        button.setOnClickListener(v -> { if (!AppLock.isLocked(this)) action.onClick(v); }); return button;
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout.LayoutParams space(int top, int bottom) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(top), 0, dp(bottom)); return p; }
    private int dp(int value) { return Ui.dp(this, value); }
}
