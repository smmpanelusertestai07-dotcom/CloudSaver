package com.pocketagent.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import java.util.Locale;
import java.util.Set;

/** Device read aloud, separate from the agent's realtime voice conversation. Main-thread API. */
final class MessageSpeech {
    interface Listener {
        void onChanged();
        void onNotice(String text);
    }

    private static final String PREFS = "pocketagent_read_aloud", KEY_LANGUAGE = "language";
    private final Activity activity;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private TextToSpeech engine;
    private AudioFocusRequest focus;
    private AlertDialog sheet;
    private boolean ready, closed;
    private long generation, engineGeneration;
    private String messageId = "", text = "", utteranceId = "";
    private int offset;

    MessageSpeech(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
    }

    String language() {
        return MessageSpeechText.choice(activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, "auto"));
    }

    String languageLabel() {
        String selected = language();
        return "hi".equals(selected) ? "Hindi" : "en".equals(selected) ? "English" : "Auto";
    }

    boolean isReading(String id) {
        return !messageId.isEmpty() && messageId.equals(id);
    }

    /** A tap starts/stops speech in the saved language. Long press may open showOptions(). */
    void toggle(String id, String markdown) {
        if (isReading(id)) { stop(); return; }
        read(id, markdown);
    }

    void showOptions(String id, String markdown) {
        if (!available()) return;
        dismissSheet();
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Ui.dp(activity, 20);
        content.setPadding(padding, 0, padding, padding / 2);
        RadioGroup choices = new RadioGroup(activity);
        choices.setOrientation(RadioGroup.VERTICAL);
        String[] values = {"auto", "en", "hi"};
        String[] labels = {"Auto", "English", "Hindi"};
        for (int i = 0; i < values.length; i++) {
            RadioButton row = new RadioButton(activity);
            row.setId(200 + i); row.setText(labels[i]); row.setTextSize(16);
            row.setTextColor(DeskStyle.TEXT); row.setMinHeight(Ui.dp(activity, 48));
            choices.addView(row);
            row.setChecked(values[i].equals(language()));
        }
        content.addView(choices);
        TextView detail = new TextView(activity);
        detail.setText("Uses a downloaded device voice. Language changes pronunciation, not the message text.");
        detail.setTextColor(DeskStyle.MUTED); detail.setTextSize(13);
        detail.setPadding(0, Ui.dp(activity, 10), 0, 0);
        content.addView(detail);
        choices.setOnCheckedChangeListener((group, checkedId) -> {
            int index = checkedId - 200;
            if (index < 0 || index >= values.length || !available()) return;
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_LANGUAGE, values[index]).apply();
            stop();
            if (sheet != null && sheet.getButton(AlertDialog.BUTTON_POSITIVE) != null)
                sheet.getButton(AlertDialog.BUTTON_POSITIVE).setText("Read aloud");
        });
        boolean reading = isReading(id);
        AlertDialog dialog = new AlertDialog.Builder(activity, DeskStyle.dialogTheme(activity))
                .setTitle("Read aloud").setView(content)
                .setPositiveButton(reading ? "Stop" : "Read aloud", (d, w) -> {
                    if (reading && isReading(id)) stop(); else read(id, markdown);
                }).setNegativeButton("Cancel", null).create();
        sheet = dialog;
        dialog.setOnDismissListener(d -> { if (sheet == dialog) sheet = null; });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setGravity(Gravity.BOTTOM);
            dialog.getWindow().setLayout(Math.min(activity.getResources().getDisplayMetrics().widthPixels,
                    Ui.dp(activity, 680)), -2);
        }
    }

    private void read(String id, String markdown) {
        if (!available()) return;
        stop();
        String spoken = MarkdownBlocks.spokenText(markdown == null ? "" : markdown).trim();
        if (spoken.isEmpty()) { notice("This message has no text to read aloud."); return; }
        if (id == null || id.isEmpty()) { notice("This message is not available."); return; }
        messageId = id; text = spoken; offset = 0;
        changed();
        if (ready) start(); else initialize();
    }

    private void initialize() {
        if (engine != null) return;
        long token = ++engineGeneration;
        try {
            engine = new TextToSpeech(activity.getApplicationContext(), result -> main.post(() -> {
                if (closed || token != engineGeneration) return;
                if (result != TextToSpeech.SUCCESS || engine == null) {
                    releaseEngine(); fail("Read aloud is unavailable. Add a text-to-speech voice in Android settings.");
                    return;
                }
                ready = true;
                engine.setAudioAttributes(attributes);
                engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { main.post(() -> {
                        if (acceptCallback(id)) next();
                    }); }
                    @Override public void onError(String id) { onError(id, TextToSpeech.ERROR); }
                    @Override public void onError(String id, int code) { main.post(() -> {
                        if (!acceptCallback(id)) return;
                        fail(code == TextToSpeech.ERROR_NOT_INSTALLED_YET
                                ? "Download this voice in Android text-to-speech settings first."
                                : "Read aloud stopped. Check the device voice and try again.");
                    }); }
                    @Override public void onStop(String id, boolean interrupted) { main.post(() -> {
                        if (acceptCallback(id)) stop();
                    }); }
                });
                if (!messageId.isEmpty()) start();
            }));
        } catch (RuntimeException unavailable) {
            releaseEngine(); fail("Read aloud is unavailable on this device.");
        }
    }

    private void start() {
        if (!available() || !ready || messageId.isEmpty()) { stop(); return; }
        try {
            Locale requested = MessageSpeechText.locale(language(), text);
            Voice voice = installedVoice(requested);
            if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) {
                fail("Download an offline " + ("hi".equals(requested.getLanguage()) ? "Hindi" : "English")
                        + " voice in Android text-to-speech settings to read aloud.");
                return;
            }
            final long focusGeneration = generation;
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> {
                        if (focusGeneration != generation) return;
                        if (change == AudioManager.AUDIOFOCUS_LOSS
                                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) stop();
                    }, main).build();
            if (audio == null || audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                fail("Audio is busy. Try read aloud again in a moment."); return;
            }
            next();
        } catch (RuntimeException unavailable) {
            fail("Read aloud is unavailable. Check the device voice and try again.");
        }
    }

    private Voice installedVoice(Locale locale) {
        Set<Voice> voices = engine.getVoices();
        if (voices == null) return null;
        Voice best = null; int bestScore = Integer.MIN_VALUE;
        for (Voice voice : voices) {
            if (voice == null || voice.isNetworkConnectionRequired() || voice.getLocale() == null
                    || !locale.getLanguage().equals(voice.getLocale().getLanguage())) continue;
            Set<String> features = voice.getFeatures();
            if (features != null && features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
            int score = voice.getQuality() - voice.getLatency();
            if (locale.getCountry().equals(voice.getLocale().getCountry())) score += 1000;
            if (best == null || score > bestScore || (score == bestScore
                    && voice.getName().compareTo(best.getName()) < 0)) { best = voice; bestScore = score; }
        }
        return best;
    }

    private void next() {
        if (!available() || messageId.isEmpty()) { stop(); return; }
        while (offset < text.length() && Character.isWhitespace(text.charAt(offset))) offset++;
        if (offset >= text.length()) { stop(); return; }
        int limit = Math.max(2, Math.min(3000, TextToSpeech.getMaxSpeechInputLength()));
        int end = MessageSpeechText.nextEnd(text, offset, limit);
        String part = text.substring(offset, end); offset = end;
        utteranceId = "pocketagent-read-" + generation + "-" + end;
        try {
            if (engine.speak(part, TextToSpeech.QUEUE_FLUSH, new Bundle(), utteranceId) != TextToSpeech.SUCCESS)
                fail("Read aloud could not start. Check the device voice and try again.");
        } catch (RuntimeException unavailable) {
            fail("Read aloud stopped. Check the device voice and try again.");
        }
    }

    private boolean acceptCallback(String id) {
        return available() && !messageId.isEmpty() && id != null && id.equals(utteranceId);
    }

    /** Call whenever the activity leaves the foreground; never resume speech automatically. */
    void stop() {
        generation++;
        boolean active = !messageId.isEmpty();
        messageId = ""; text = ""; utteranceId = ""; offset = 0;
        if (engine != null) try { engine.stop(); } catch (RuntimeException ignored) { }
        if (focus != null && audio != null) try { audio.abandonAudioFocusRequest(focus); } catch (RuntimeException ignored) { }
        focus = null;
        if (active) changed();
    }

    /** Account/thread/project change or app lock invalidates both playback and open controls. */
    void cancelScope() { dismissSheet(); stop(); }

    void close() {
        closed = true; cancelScope(); releaseEngine();
    }

    private void releaseEngine() {
        engineGeneration++; ready = false;
        if (engine != null) try { engine.shutdown(); } catch (RuntimeException ignored) { }
        engine = null;
    }

    private void dismissSheet() {
        AlertDialog current = sheet; sheet = null;
        if (current != null) current.dismiss();
    }

    private boolean available() {
        return !closed && !activity.isFinishing() && !activity.isDestroyed() && !AppLock.isLocked(activity);
    }

    private void fail(String detail) { stop(); notice(detail); }
    private void notice(String detail) { if (available() && listener != null) listener.onNotice(detail); }
    private void changed() { if (!closed && listener != null) listener.onChanged(); }
}
