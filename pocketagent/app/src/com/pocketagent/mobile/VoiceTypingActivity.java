package com.pocketagent.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;

/** Reviewed voice typing. Independent from the coding agent's bidirectional Voice session. */
public final class VoiceTypingActivity extends Activity {
    private static final int PHONE = 8201, MICROPHONE = 8202;
    private static final long MAX_LISTEN_MS = 60000;
    private final VoiceTypingPolicy session = new VoiceTypingPolicy();
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout lockShell;
    private TextView status;
    private EditText transcript;
    private Button local, phone, stop, use, language;
    private SpeechRecognizer recognizer;
    private android.app.AlertDialog languageDialog;
    private boolean visible, externalPending;
    private String pendingText, languageTag = Locale.getDefault().toLanguageTag();
    private final Runnable timeout = () -> stopCapture("Stopped. Tap On-device to try again.");

    public static void open(Activity activity, int requestCode) {
        if (!AppLock.isLocked(activity)) activity.startActivityForResult(
                new Intent(activity, VoiceTypingActivity.class), requestCode);
    }

    @Override protected void onCreate(Bundle saved) {
        DeskStyle.apply(this); super.onCreate(saved); setResult(RESULT_CANCELED); build();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    @Override protected void onResume() {
        super.onResume(); visible = true; DeskStyle.applySystemBars(this); AppLock.applyWindowSecurity(this);
        if (AppLock.isLocked(this)) {
            stopCapture(null); AppLock.show(this, lockShell, this::showPending);
        } else showPending();
    }

    @Override protected void onPause() {
        visible = false; stopCapture(session.active() ? "Stopped when you left this screen." : null);
        if (languageDialog != null) { languageDialog.dismiss(); languageDialog = null; }
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && session.active()) stopCapture("Stopped after an interruption. Tap On-device to continue.");
    }

    @Override protected void onDestroy() {
        visible = false; main.removeCallbacksAndMessages(null); stopCapture(null);
        pendingText = null;
        if (transcript != null) transcript.setText("");
        super.onDestroy();
    }

    private boolean usable() { return visible && !isFinishing() && !isDestroyed() && !AppLock.isLocked(this); }

    private void build() {
        LinearLayout root = column(); root.setBackground(DeskStyle.background(this));
        root.setPadding(dp(20), dp(8), dp(20), dp(16));
        lockShell = new FrameLayout(this); lockShell.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(lockShell); AppLock.applyWindowSecurity(this);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                root.setPadding(dp(20) + bars.left, dp(8) + bars.top, dp(20) + bars.right, dp(12) + bars.bottom);
            } else root.setPadding(dp(20), dp(8) + insets.getSystemWindowInsetTop(), dp(20), dp(12) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("Back", "back", v -> finish()); back.setBackground(DeskStyle.plain(this));
        head.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = text("Voice typing", 20, DeskStyle.TEXT); title.setPadding(dp(12), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(head);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body = column(); body.setPadding(0, dp(18), 0, dp(12));
        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(text("Speak, review, then add to your message.", 14, DeskStyle.MUTED), space(0, 20));
        language = button(languageLabel(), "settings", v -> chooseLanguage());
        body.addView(language, space(0, 12));
        local = button("On-device", "mic", v -> requestLocal()); body.addView(local, space(0, 6));
        body.addView(text(onDeviceAvailable() ? "Offline speech recognition. Microphone permission is required."
                : "Needs Android 12+ and an installed on-device speech service.", 13, DeskStyle.MUTED), space(0, 18));
        local.setEnabled(onDeviceAvailable()); local.setAlpha(local.isEnabled() ? 1f : .5f);
        phone = button("Phone dictation", "mic", v -> launchPhone()); body.addView(phone, space(0, 6));
        body.addView(text("No app microphone permission needed. Your phone's speech app controls recording and may use the internet; offline is requested.", 13, DeskStyle.MUTED), space(0, 20));
        status = text("", 14, DeskStyle.MUTED); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status, space(0, 8));
        transcript = new EditText(this); transcript.setTextSize(17); transcript.setTextColor(DeskStyle.TEXT);
        transcript.setHintTextColor(DeskStyle.MUTED); transcript.setHint("Your words will appear here…");
        transcript.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); transcript.setGravity(Gravity.TOP);
        transcript.setMinLines(5); transcript.setBackground(DeskStyle.composer(this));
        transcript.setPadding(dp(16), dp(16), dp(16), dp(16));
        transcript.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        transcript.setFilters(new InputFilter[]{new InputFilter.LengthFilter(VoiceTypingPolicy.MAX_TEXT)});
        // Dictated text stays only in memory, never Activity state, logs, or the clipboard.
        transcript.setSaveEnabled(false); transcript.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        body.addView(transcript, space(0, 12));
        stop = button("Stop", "stop", v -> finishListening()); stop.setVisibility(View.GONE); body.addView(stop, space(0, 8));
        use = button("Use text", "check", v -> returnText()); use.setBackground(DeskStyle.primary(this));
        use.setTextColor(DeskStyle.BG);
        android.graphics.drawable.Drawable useIcon = DeskStyle.icon(this, "check", DeskStyle.BG);
        useIcon.setBounds(0, 0, dp(18), dp(18)); use.setCompoundDrawablesRelative(useIcon, null, null, null);
        root.addView(use, space(8, 0));
    }

    private boolean onDeviceAvailable() {
        if (Build.VERSION.SDK_INT < 31) return false;
        try { return SpeechRecognizer.isOnDeviceRecognitionAvailable(this); }
        catch (RuntimeException unsupported) { return false; }
    }

    private void requestLocal() {
        if (!usable() || session.active()) return;
        if (!onDeviceAvailable()) { status.setText("On-device speech is unavailable on this phone."); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Granting access never starts listening implicitly. A visible second tap is required.
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE); return;
        }
        startLocal();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != MICROPHONE) return;
        status.setText(grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED
                ? "Microphone allowed. Tap On-device to start."
                : "Microphone not allowed. Phone dictation is still available.");
    }

    private Intent recognitionIntent() {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Dictate your message");
    }

    private void launchPhone() {
        if (!usable() || externalPending || session.active()) return;
        stopCapture(null); externalPending = true;
        try { startActivityForResult(recognitionIntent(), PHONE); }
        catch (RuntimeException missing) {
            externalPending = false;
            status.setText("No phone dictation app is available. You can use your keyboard's microphone.");
            transcript.requestFocus();
            android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(transcript, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (AppLock.handleResult(this, lockShell, request, result, this::showPending)) return;
        if (request != PHONE || !externalPending) return;
        externalPending = false;
        if (result == RESULT_OK && data != null) {
            try {
                ArrayList<String> words = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                pendingText = words == null || words.isEmpty() ? "" : VoiceTypingPolicy.clean(words.get(0));
            } catch (RuntimeException malformed) { pendingText = ""; }
        }
        if (usable()) showPending();
    }

    private void showPending() {
        if (!usable() || pendingText == null) return;
        String words = pendingText; pendingText = null;
        append(words); status.setText(words.isEmpty() ? "No speech recognized. Try again." : "Review your text before using it.");
    }

    private void startLocal() {
        if (!usable() || Build.VERSION.SDK_INT < 31 || !onDeviceAvailable()) return;
        stopCapture(null);
        final long token = session.begin();
        try {
            // Do not replace this with createSpeechRecognizer: that permits a network engine.
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                private boolean current() { return session.accepts(token, usable(), !AppLock.isLocked(VoiceTypingActivity.this)); }
                @Override public void onReadyForSpeech(Bundle params) { if (current()) status.setText("Listening on-device…"); }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float value) { }
                @Override public void onBufferReceived(byte[] bytes) { /* Never retain raw audio. */ }
                @Override public void onEndOfSpeech() { if (current()) status.setText("Finishing…"); }
                @Override public void onError(int code) { if (current()) stopCapture(errorText(code)); }
                @Override public void onResults(Bundle results) {
                    if (!current()) return;
                    ArrayList<String> words = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String value = words == null || words.isEmpty() ? "" : VoiceTypingPolicy.clean(words.get(0));
                    stopCapture(value.isEmpty() ? "No speech recognized. Try again." : "Review your text before using it."); append(value);
                }
                @Override public void onPartialResults(Bundle partial) { /* Final results only; do not replace an edited draft. */ }
                @Override public void onEvent(int type, Bundle params) { }
            });
            recognizer.startListening(recognitionIntent());
            local.setEnabled(false); phone.setEnabled(false); language.setEnabled(false); use.setEnabled(false);
            stop.setVisibility(View.VISIBLE); status.setText("Starting on-device…"); main.postDelayed(timeout, MAX_LISTEN_MS);
        } catch (RuntimeException unavailable) { stopCapture("On-device speech is unavailable. Check your phone's installed speech languages."); }
    }

    private void finishListening() {
        if (!usable() || !session.active() || recognizer == null) return;
        try { recognizer.stopListening(); status.setText("Finishing…"); }
        catch (RuntimeException failure) { stopCapture("Stopped. Tap On-device to try again."); }
        main.removeCallbacks(timeout); main.postDelayed(timeout, 8000);
    }

    private void stopCapture(String message) {
        session.cancel(); main.removeCallbacks(timeout);
        SpeechRecognizer current = recognizer; recognizer = null;
        if (current != null) {
            try { current.cancel(); } catch (RuntimeException ignored) { }
            try { current.destroy(); } catch (RuntimeException ignored) { }
        }
        if (local != null) local.setEnabled(onDeviceAvailable());
        if (phone != null) phone.setEnabled(true);
        if (language != null) language.setEnabled(true);
        if (use != null) use.setEnabled(true);
        if (stop != null) stop.setVisibility(View.GONE);
        if (message != null && status != null) status.setText(message);
    }

    private static String errorText(int code) {
        if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) return "Microphone access was denied or turned off.";
        if (code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            return "This language is not installed for offline speech. Choose another language or install its speech pack in Android settings.";
        if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) return "No speech recognized. Try again.";
        if (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) return "Microphone or speech service is busy. Try again.";
        return "On-device speech could not finish. Tap On-device to try again.";
    }

    private void append(String words) {
        if (words == null || words.isEmpty()) return;
        transcript.setText(VoiceTypingPolicy.clean(transcript.getText().toString() + (transcript.length() > 0 ? " " : "") + words));
        transcript.setSelection(transcript.length());
    }

    private void returnText() {
        if (!usable() || session.active()) return;
        String value = VoiceTypingPolicy.clean(transcript.getText().toString());
        if (value.isEmpty()) { status.setText("Dictate or type some text first."); return; }
        ArrayList<String> words = new ArrayList<>(); words.add(value);
        setResult(RESULT_OK, new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, words)); finish();
    }

    private void chooseLanguage() {
        if (!usable() || session.active()) return;
        String[] tags = {Locale.getDefault().toLanguageTag(), "en-IN", "hi-IN", "en-US"};
        String[] labels = {"Device language", "English (India)", "Hindi (India)", "English (US)"};
        // A short language picker is local UI; opening it cannot initiate capture.
        languageDialog = new android.app.AlertDialog.Builder(this).setTitle("Speech language").setItems(labels, (dialog, which) -> {
            if (!usable()) return; languageTag = tags[which]; language.setText(languageLabel());
        }).setNegativeButton("Cancel", null).create();
        languageDialog.setOnDismissListener(dialog -> languageDialog = null); languageDialog.show();
    }

    private String languageLabel() { return Locale.forLanguageTag(languageTag).getDisplayName(); }
    private LinearLayout column() { LinearLayout result = new LinearLayout(this); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private TextView text(String value, int size, int color) { TextView result = new TextView(this); result.setText(value); result.setTextSize(size); result.setTextColor(color); result.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); result.setLineSpacing(0, 1.12f); return result; }
    private Button button(String title, String iconName, View.OnClickListener action) {
        Button result = new Button(this); result.setText(title); result.setAllCaps(false); result.setTextSize(15); result.setTextColor(DeskStyle.TEXT);
        result.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); result.setMinHeight(dp(48)); result.setMinimumHeight(dp(48)); result.setMinWidth(0); result.setMinimumWidth(0);
        result.setPadding(dp(14), dp(10), dp(14), dp(10)); result.setBackground(DeskStyle.secondary(this)); result.setStateListAnimator(null);
        android.graphics.drawable.Drawable icon = DeskStyle.icon(this, iconName, DeskStyle.TEXT); icon.setBounds(0, 0, dp(18), dp(18));
        result.setCompoundDrawablesRelative(icon, null, null, null); result.setCompoundDrawablePadding(dp(10));
        result.setOnClickListener(v -> { if (usable()) action.onClick(v); }); return result;
    }
    private LinearLayout.LayoutParams space(int top, int bottom) { LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(-1, -2); result.setMargins(0, dp(top), 0, dp(bottom)); return result; }
    private int dp(int value) { return Ui.dp(this, value); }
}
