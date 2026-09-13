package com.pocketagent.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;

/** On-device dictation in the existing composer. No Activity launch, audio files or auto-send. */
final class InlineDictation {
    interface Host {
        String scope();
        String draft();
        int selectionStart();
        int selectionEnd();
        boolean isAvailable();
        boolean applyDraft(String expectedDraft, String replacement, int cursor);
        void onStateChanged(boolean active);
        void onMessage(String message);
    }

    private static final int MICROPHONE = 8310;
    private static final long CAPTURE_MS = 60000, FINISH_MS = 8000;
    private final Activity activity;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final VoiceTypingPolicy session = new VoiceTypingPolicy();
    private final LinearLayout panel;
    private final TextView status, transcript;
    private final Button stop, use;
    private final Waveform waveform;
    private SpeechRecognizer recognizer;
    private AlertDialog languageDialog;
    private DictationDraft insertion;
    private String words = "", pendingScope = "", languageTag;
    private boolean closed, finishing, permissionPending;
    private final ViewTreeObserver.OnWindowFocusChangeListener focusListener = focused -> {
        if (!focused && session.active()) cancel();
    };
    private final Runnable captureTimeout = this::finishListening;
    private final Runnable resultTimeout = () -> failure("Dictation timed out. Try again.");

    InlineDictation(Activity activity, Host host) {
        this.activity = activity; this.host = host;
        languageTag = activity.getSharedPreferences("pocketagent_dictation", Context.MODE_PRIVATE)
                .getString("language", Locale.getDefault().toLanguageTag());
        if (languageTag == null || Locale.forLanguageTag(languageTag).getLanguage().isEmpty())
            languageTag = Locale.getDefault().toLanguageTag();
        panel = new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(4), 0, dp(4)); panel.setVisibility(View.GONE);
        panel.setSaveEnabled(false);
        panel.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        LinearLayout controls = new LinearLayout(activity); controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(icon("Cancel dictation", "close", v -> cancel()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        waveform = new Waveform(activity); controls.addView(waveform, new LinearLayout.LayoutParams(0, dp(40), 1));
        stop = icon("Stop dictation", "stop", v -> finishListening());
        controls.addView(stop, new LinearLayout.LayoutParams(dp(48), dp(48)));
        use = icon("Use dictated text", "check", v -> useReviewed()); use.setVisibility(View.GONE);
        controls.addView(use, new LinearLayout.LayoutParams(dp(48), dp(48))); panel.addView(controls);
        status = text("", 12, DeskStyle.MUTED); status.setPadding(dp(8), 0, dp(8), dp(4));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); panel.addView(status);
        transcript = text("", 15, DeskStyle.TEXT); transcript.setMaxLines(3);
        transcript.setPadding(dp(8), 0, dp(8), dp(6)); transcript.setSaveEnabled(false);
        transcript.setVisibility(View.GONE); panel.addView(transcript);
        activity.getWindow().getDecorView().getViewTreeObserver().addOnWindowFocusChangeListener(focusListener);
    }

    View view() { return panel; }
    boolean active() { return session.active(); }

    /** A user tap begins capture; a second tap stops capture and awaits final words. */
    void toggle() {
        if (!usable()) return;
        if (active()) { finishListening(); return; }
        if (!words.isEmpty()) { host.onMessage("Use the checkmark to insert your words, or cancel to dictate again."); return; }
        if (!onDeviceAvailable()) {
            host.onMessage("Offline dictation is unavailable on this phone. Use your keyboard’s microphone."); return;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionPending) {
                permissionPending = true;
                try { activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE); }
                catch (RuntimeException unavailable) {
                    permissionPending = false; host.onMessage("Microphone permission could not be requested. Try again.");
                }
            }
            return;
        }
        start();
    }

    boolean onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        if (request != MICROPHONE) return false;
        permissionPending = false;
        if (usable()) host.onMessage(grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED
                ? "Microphone allowed. Tap Dictate to start."
                : "Microphone permission is needed for dictation. You can use your keyboard’s microphone.");
        return true;
    }

    /** Locale picker only; choosing a language never initiates recording. */
    void chooseLanguage() {
        if (!usable() || active() || languageDialog != null) return;
        final String[] tags = {Locale.getDefault().toLanguageTag(), "en-IN", "hi-IN", "en-US"};
        String[] names = {"Device language", "English (India)", "हिन्दी (भारत)", "English (US)"};
        int selected = 0;
        for (int i = tags.length - 1; i >= 0; i--) if (tags[i].equals(languageTag)) selected = i;
        final String scope = host.scope();
        languageDialog = new AlertDialog.Builder(activity, DeskStyle.dialogTheme(activity)).setTitle("Dictation language")
                .setSingleChoiceItems(names, selected, (dialog, which) -> {
                    if (usable() && scope.equals(host.scope())) {
                        languageTag = tags[which];
                        activity.getSharedPreferences("pocketagent_dictation", Context.MODE_PRIVATE)
                                .edit().putString("language", languageTag).apply();
                    }
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).create();
        languageDialog.setOnDismissListener(dialog -> languageDialog = null);
        languageDialog.show();
    }

    void onPause() { cancel(); if (languageDialog != null) languageDialog.dismiss(); }
    void close() {
        onPause(); closed = true; main.removeCallbacksAndMessages(null);
        ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnWindowFocusChangeListener(focusListener);
    }
    void cancel() {
        stopRecognizer(); clearPanel(); permissionPending = false;
    }

    private boolean usable() {
        return !closed && !activity.isFinishing() && !activity.isDestroyed()
                && !AppLock.isLocked(activity) && host.isAvailable();
    }

    private boolean onDeviceAvailable() {
        if (Build.VERSION.SDK_INT < 31) return false;
        try { return SpeechRecognizer.isOnDeviceRecognitionAvailable(activity); }
        catch (RuntimeException unavailable) { return false; }
    }

    private boolean current(long token) {
        // An old recognizer callback must not cancel a newer recording.
        if (!session.accepts(token, true, true)) return false;
        boolean valid = session.accepts(token, usable(), !AppLock.isLocked(activity))
                && insertion != null && insertion.scope.equals(host.scope());
        if (!valid && session.active()) cancel();
        return valid;
    }

    private void start() {
        if (!usable() || Build.VERSION.SDK_INT < 31) return;
        stopRecognizer(); clearPanel();
        insertion = new DictationDraft(host.scope(), host.draft(), host.selectionStart(), host.selectionEnd());
        pendingScope = insertion.scope;
        final long token = session.begin(); finishing = false;
        panel.setVisibility(View.VISIBLE); stop.setVisibility(View.VISIBLE); stop.setEnabled(true);
        status.setText("Starting dictation…"); host.onStateChanged(true);
        try {
            // EXTRA_PREFER_OFFLINE alone is not a guarantee. Never substitute the network-capable factory.
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(activity);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    if (current(token)) status.setText("Listening · " + languageName());
                }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rms) { if (current(token)) waveform.sample(rms); }
                @Override public void onBufferReceived(byte[] bytes) { /* Raw microphone audio is not retained. */ }
                @Override public void onEndOfSpeech() {
                    if (!current(token)) return;
                    finishing = true; stop.setEnabled(false); status.setText("Transcribing…");
                    main.removeCallbacks(captureTimeout); main.removeCallbacks(resultTimeout);
                    main.postDelayed(resultTimeout, FINISH_MS);
                }
                @Override public void onError(int code) { if (current(token)) failure(errorText(code)); }
                @Override public void onResults(Bundle result) {
                    if (!current(token)) return;
                    String finalWords = resultWords(result);
                    if (!finalWords.isEmpty()) words = finalWords;
                    else { failure("No final transcript. Review the words or try again."); return; }
                    DictationDraft original = insertion;
                    stopRecognizer();
                    DictationDraft.Proposal proposal = original.propose(host.scope(), host.draft(), words);
                    if (usable() && proposal != null && host.applyDraft(proposal.expected, proposal.text, proposal.cursor)) clearPanel();
                    else review("Your draft changed. Use the checkmark to insert these words at the current cursor.");
                }
                @Override public void onPartialResults(Bundle partial) {
                    if (!current(token)) return;
                    String value = resultWords(partial);
                    if (!value.isEmpty()) { words = value; transcript.setText(words); transcript.setVisibility(View.VISIBLE); }
                }
                @Override public void onEvent(int type, Bundle params) { }
            });
            recognizer.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true));
            main.postDelayed(captureTimeout, CAPTURE_MS);
        } catch (RuntimeException unavailable) {
            failure("Offline dictation could not start. Check the installed speech language on your phone.");
        }
    }

    private void finishListening() {
        if (!active() || finishing || recognizer == null) return;
        if (!usable() || insertion == null || !insertion.scope.equals(host.scope())) { cancel(); return; }
        finishing = true; stop.setEnabled(false); status.setText("Transcribing…");
        main.removeCallbacks(captureTimeout); main.removeCallbacks(resultTimeout);
        main.postDelayed(resultTimeout, FINISH_MS);
        try { recognizer.stopListening(); }
        catch (RuntimeException failure) { failure("Dictation stopped. Review the words or try again."); }
    }

    private void failure(String message) {
        boolean show = usable() && pendingScope.equals(host.scope());
        stopRecognizer();
        if (show && !words.isEmpty()) review(message + " Use the checkmark to keep these words.");
        else { clearPanel(); if (show) host.onMessage(message); }
    }

    private void review(String message) {
        if (!usable() || !pendingScope.equals(host.scope())) { clearPanel(); return; }
        status.setText(message); transcript.setText(words); transcript.setVisibility(View.VISIBLE);
        stop.setVisibility(View.GONE); use.setVisibility(View.VISIBLE); waveform.reset();
    }

    private void useReviewed() {
        if (!usable() || active()) return;
        if (!pendingScope.equals(host.scope())) { cancel(); return; }
        DictationDraft now = new DictationDraft(host.scope(), host.draft(), host.selectionStart(), host.selectionEnd());
        DictationDraft.Proposal proposal = now.propose(host.scope(), host.draft(), words);
        if (proposal != null && host.applyDraft(proposal.expected, proposal.text, proposal.cursor)) clearPanel();
    }

    private void stopRecognizer() {
        boolean wasActive = session.active(); session.cancel(); finishing = false;
        main.removeCallbacks(captureTimeout); main.removeCallbacks(resultTimeout);
        SpeechRecognizer previous = recognizer; recognizer = null;
        if (previous != null) {
            try { previous.cancel(); } catch (RuntimeException ignored) { }
            try { previous.destroy(); } catch (RuntimeException ignored) { }
        }
        if (wasActive && !closed) host.onStateChanged(false);
    }

    private void clearPanel() {
        insertion = null; pendingScope = ""; words = "";
        transcript.setText(""); transcript.setVisibility(View.GONE); status.setText("");
        panel.setVisibility(View.GONE); use.setVisibility(View.GONE); waveform.reset();
    }

    private static String resultWords(Bundle result) {
        try {
            ArrayList<String> alternatives = result == null ? null : result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            return alternatives == null || alternatives.isEmpty() ? "" : VoiceTypingPolicy.clean(alternatives.get(0));
        } catch (RuntimeException malformed) { return ""; }
    }

    private static String errorText(int code) {
        if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) return "Microphone permission is off.";
        if (code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            return "This language is unavailable offline. Hold Dictate to choose another language.";
        if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            return "No speech recognized. Try again.";
        if (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) return "The microphone is busy. Try again.";
        return "Dictation could not finish. Try again.";
    }

    private String languageName() { return Locale.forLanguageTag(languageTag).getDisplayLanguage(); }
    private int dp(int value) { return Ui.dp(activity, value); }
    private TextView text(String value, int size, int color) {
        TextView result = new TextView(activity); result.setText(value); result.setTextSize(size);
        result.setTextColor(color); result.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); return result;
    }
    private Button icon(String label, String name, View.OnClickListener action) {
        Button result = new Button(activity); result.setText(""); result.setContentDescription(label); result.setTooltipText(label);
        result.setMinWidth(0); result.setMinimumWidth(0); result.setMinHeight(0); result.setMinimumHeight(0);
        result.setPadding(dp(13), dp(13), dp(13), dp(13)); result.setBackground(DeskStyle.plain(activity));
        android.graphics.drawable.Drawable drawable = DeskStyle.icon(activity, name, DeskStyle.TEXT);
        drawable.setBounds(0, 0, dp(22), dp(22)); result.setCompoundDrawablesRelative(drawable, null, null, null);
        result.setOnClickListener(action); return result;
    }

    /** Real RMS samples only. A flat waveform means no samples have been reported. */
    private static final class Waveform extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] levels = new float[32];
        Waveform(Context context) {
            super(context); paint.setColor(DeskStyle.MUTED); paint.setStrokeCap(Paint.Cap.ROUND);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void sample(float value) {
            System.arraycopy(levels, 1, levels, 0, levels.length - 1);
            levels[levels.length - 1] = Float.isNaN(value) ? 0 : Math.max(0, Math.min(1, (value + 2) / 12)); invalidate();
        }
        void reset() { java.util.Arrays.fill(levels, 0); invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); float gap = getWidth() / (float) levels.length;
            paint.setStrokeWidth(Math.max(1, Math.min(Ui.dp(getContext(), 3), gap * .45f)));
            float mid = getHeight() * .5f;
            for (int i = 0; i < levels.length; i++) {
                float half = Math.max(Ui.dp(getContext(), 2), levels[i] * getHeight() * .40f);
                canvas.drawLine(gap * (i + .5f), mid - half, gap * (i + .5f), mid + half, paint);
            }
        }
    }
}
