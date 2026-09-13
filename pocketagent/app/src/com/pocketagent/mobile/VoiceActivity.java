package com.pocketagent.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.UUID;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Native voice controls; a sealed local WebRTC page owns only media, never account tokens. */
public final class VoiceActivity extends Activity {
    private static final String ORIGIN = "https://pocketagent-voice.invalid", PAGE = ORIGIN + "/";
    private static final int MICROPHONE_REQUEST = 8642;
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout lockShell, transportHost;
    private TextView statusView, captions, detail;
    private Button start, mute, stop, approval;
    private String project, thread = "", owner = "", expectedAccountToken = "", appearance, localStatus = "", renderedCaptions = "";
    private WebView transport;
    private boolean resumed, running, consented, muted, requestSent;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocus;
    private AlertDialog consentDialog;

    public static void open(Activity activity, String project) {
        activity.startActivity(new Intent(activity, VoiceActivity.class).putExtra(AgentService.EXTRA_PROJECT, project));
    }
    @Override protected void onCreate(Bundle saved) {
        DeskStyle.apply(this); appearance = DeskStyle.themeSignature(this); super.onCreate(saved);
        try { project = AgentProtocol.project(getIntent().getStringExtra(AgentService.EXTRA_PROJECT)); }
        catch (Exception bad) { finish(); return; }
        build();
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (appearance != null && !appearance.equals(DeskStyle.themeSignature(this))) { recreate(); return; }
        DeskStyle.applySystemBars(this); AppLock.applyWindowSecurity(this);
        if (AppLock.isLocked(this)) AppLock.show(this, lockShell, this::render);
        main.post(poll);
    }
    @Override protected void onPause() {
        resumed = false; main.removeCallbacks(poll);
        end("Voice ended because this screen is no longer active.");
        if (consentDialog != null) consentDialog.dismiss(); consentDialog = null; consented = false;
        super.onPause();
    }
    @Override protected void onDestroy() { end("Voice ended"); main.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); AppLock.handleResult(this, lockShell, request, result, this::render);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == MICROPHONE_REQUEST && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            // Permission dialogs can pause this Activity. A fresh Start tap remains necessary.
            localStatus = "Microphone permission granted. Tap Start voice to connect.";
        } else if (request == MICROPHONE_REQUEST) localStatus = "Microphone access is required for a voice conversation.";
        consented = false; render();
    }
    private boolean safe() { return resumed && !isFinishing() && !AppLock.isLocked(this); }
    private boolean scope(JSONObject state) { return "codex".equals(state.optString("provider")) && project.equals(state.optString("project")); }
    private boolean accountCurrent(JSONObject state) {
        return scope(state) && state.optBoolean("accountConnected") && !expectedAccountToken.isEmpty()
            && expectedAccountToken.equals(state.optString("accountScopeToken", ""));
    }
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed || isFinishing()) return;
            if (!safe()) { end("Voice stopped while PocketAgent is locked."); main.postDelayed(this, 200); return; }
            if (running) {
                JSONObject state = AgentService.snapshot();
                if (!accountCurrent(state) || !thread.equals(state.optString("threadId")) || !state.optBoolean("connected")) end("The verified Codex account or conversation changed. Microphone off.");
                else {
                    VoiceTransport.Signal signal;
                    while ((signal = VoiceTransport.poll(owner)) != null) {
                        if ("close".equals(signal.kind)) { end(signal.value); break; }
                        if ("answer".equals(signal.kind) && transport != null) javascript("window.pocketVoice.answer(" + JSONObject.quote(signal.value) + ")");
                    }
                }
            }
            render(); main.postDelayed(this, 200);
        }
    };
    private void askStart() {
        if (!safe() || running) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_REQUEST); return;
        }
        consentDialog = new AlertDialog.Builder(this, DeskStyle.dialogTheme(this)).setTitle("Start Codex voice?")
            .setMessage("Your microphone audio will be sent to the official Codex voice service using your connected ChatGPT account. Codex can discuss and work on this project; tool approvals still require your action.\n\nVoice has its own account allowance; provider quota or workspace credits may apply. Coding tasks started by voice also use your Codex budget.\n\nAudio stops when you leave this screen, lock the app or receive an audio interruption. No audio recording is saved by PocketAgent.")
            .setNegativeButton("Cancel", null).setPositiveButton("Start voice", (d, which) -> { if (safe()) { consented = true; begin(); } }).create();
        if (consentDialog.getWindow() != null && AppLock.enabled(this)) consentDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        consentDialog.show();
    }
    private void begin() {
        JSONObject state = AgentService.snapshot(); JSONObject voice = child(state, "voice");
        if (!safe() || !consented || !scope(state) || !state.optBoolean("connected") || !state.optBoolean("accountConnected")
                || state.optBoolean("busy") || state.optInt("pendingApprovals") > 0 || voice.optBoolean("blocked") || voice.optBoolean("active")) {
            localStatus = "Connect an idle Codex conversation before starting voice."; render(); return;
        }
        thread = state.optString("threadId"); if (thread.isEmpty()) return;
        expectedAccountToken = state.optString("accountScopeToken", "");
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(change -> { if (change < 0) main.post(() -> end("Voice stopped for an audio interruption.")); }, main).build();
        if (audioManager == null || audioManager.requestAudioFocus(audioFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            localStatus = "Another app is using audio. End that call or playback, then retry."; consented = false; render(); return;
        }
        owner = UUID.randomUUID().toString(); VoiceTransport.attach(owner); running = true; muted = false; requestSent = false;
        localStatus = "Preparing secure voice audio…"; render();
        try { createTransport(owner); }
        catch (RuntimeException unavailable) { end("Android WebView could not start voice. Update Android System WebView, then reconnect."); }
        final String own = owner;
        main.postDelayed(() -> { if (running && own.equals(owner) && !requestSent) end("This device did not create a voice connection in time."); }, 20000);
        main.postDelayed(() -> {
            if (running && own.equals(owner)) {
                JSONObject voiceState = child(AgentService.snapshot(), "voice");
                if (!own.equals(voiceState.optString("owner")) || !"live".equals(voiceState.optString("phase")))
                    end("Codex voice did not connect in time. Microphone off.");
            }
        }, 45000);
    }
    @SuppressWarnings("SetJavaScriptEnabled") private void createTransport(String own) {
        transport = new WebView(this); transport.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        WebSettings settings = transport.getSettings(); settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false); settings.setDomStorageEnabled(false); settings.setDatabaseEnabled(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false); settings.setMediaPlaybackRequiresUserGesture(false);
        transport.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                boolean trusted = PAGE.equals(request.getUrl().toString()) && "GET".equals(request.getMethod()) && request.isForMainFrame();
                byte[] bytes = trusted ? VoiceWebPage.html().getBytes(StandardCharsets.UTF_8) : new byte[0];
                HashMap<String, String> headers = new HashMap<>(); headers.put("Cache-Control", "no-store");
                headers.put("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; media-src 'self' blob:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'");
                headers.put("Permissions-Policy", "microphone=(self), camera=(), geolocation=()");
                return new WebResourceResponse("text/html", "UTF-8", trusted ? 200 : 403, trusted ? "OK" : "Forbidden", headers, new ByteArrayInputStream(bytes));
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (running && own.equals(owner) && safe() && PAGE.equals(url)) javascript("window.pocketVoice.start()");
            }
            @Override public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) { handler.cancel(); main.post(() -> end("Voice page could not be verified.")); }
            @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) { main.post(() -> end("The audio transport stopped. Microphone off.")); return true; }
        });
        transport.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) { return true; }
            @Override public void onPermissionRequest(PermissionRequest request) {
                main.post(() -> {
                    String[] resources = request.getResources();
                    boolean audioOnly = resources.length == 1 && PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resources[0]);
                    if (safe() && running && consented && own.equals(owner) && accountCurrent(AgentService.snapshot()) && ORIGIN.equals(request.getOrigin().toString().replaceAll("/$", ""))
                            && transport != null && PAGE.equals(transport.getUrl()) && audioOnly
                            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    else request.deny();
                });
            }
        });
        transport.addJavascriptInterface(new Object() {
            @JavascriptInterface public void offer(String sdp) { main.post(() -> {
                if (!safe() || !running || !own.equals(owner) || requestSent) return;
                try { CodexVoice.validSdp(sdp); requestSent = true; localStatus = "Connecting official Codex voice…"; action("start", object("sdp", sdp, "expectedAccountToken", expectedAccountToken)); }
                catch (Exception invalid) { end("This device returned an unsupported voice connection."); }
            }); }
            @JavascriptInterface public void ready() { main.post(() -> {
                if (safe() && running && own.equals(owner)) {
                    if (!accountCurrent(AgentService.snapshot())) { end("The verified Codex account changed. Microphone off."); return; }
                    localStatus = "Voice connected · microphone on"; action("transport_ready", new JSONObject()); render();
                }
            }); }
            @JavascriptInterface public void failed() { main.post(() -> { if (running && own.equals(owner)) { action("transport_error", new JSONObject()); end("Voice audio could not connect or was interrupted. Microphone off."); } }); }
        }, "PocketVoiceHost");
        transportHost.addView(transport, new FrameLayout.LayoutParams(dp(1), dp(1))); transport.loadUrl(PAGE);
    }
    private void javascript(String code) { if (transport != null) transport.evaluateJavascript(code, null); }
    private void action(String operation, JSONObject payload) {
        if (owner.isEmpty() || thread.isEmpty()) return;
        try {
            payload.put("owner", owner).put("threadId", thread);
            startService(new Intent(this, AgentService.class).setAction(AgentService.ACTION_VOICE)
                .putExtra(AgentService.EXTRA_PROVIDER, "codex").putExtra(AgentService.EXTRA_PROJECT, project)
                .putExtra(AgentService.EXTRA_OPERATION, operation).putExtra(AgentService.EXTRA_PAYLOAD, payload.toString()));
        } catch (Exception ignored) { end("The Codex voice service is unavailable. Microphone off.", false); }
    }
    private void end(String message) { end(message, true); }
    private void end(String message, boolean notifyEngine) {
        boolean wasRunning = running; running = false; consented = false; muted = false;
        if (transport != null) {
            WebView old = transport; transport = null;
            try { old.evaluateJavascript("window.pocketVoice && window.pocketVoice.close()", null); } catch (RuntimeException ignored) { }
            try { old.stopLoading(); old.onPause(); } catch (RuntimeException ignored) { }
            try { old.removeJavascriptInterface("PocketVoiceHost"); } catch (RuntimeException ignored) { }
            try { transportHost.removeView(old); } catch (RuntimeException ignored) { }
            try { old.destroy(); } catch (RuntimeException ignored) { }
        }
        if (notifyEngine && wasRunning && requestSent) action("stop", new JSONObject());
        VoiceTransport.detach(owner); owner = ""; requestSent = false;
        if (audioManager != null && audioFocus != null) audioManager.abandonAudioFocusRequest(audioFocus); audioFocus = null;
        if (wasRunning) localStatus = message;
        if (safe()) render();
    }
    private void render() {
        if (!safe() || statusView == null) return;
        JSONObject state = AgentService.snapshot(), voice = child(state, "voice");
        String current = !localStatus.isEmpty() ? localStatus : voice.optString("status", "Start a voice conversation");
        if (scope(state) && !voice.optString("error").isEmpty()) current = voice.optString("error");
        if (running && muted) current = "Voice connected · microphone muted";
        statusView.setText(current); start.setEnabled(!running && scope(state) && state.optBoolean("connected") && state.optBoolean("accountConnected")
            && !state.optBoolean("busy") && !voice.optBoolean("active") && !voice.optBoolean("blocked") && state.optInt("pendingApprovals") == 0);
        start.setText(voice.optBoolean("blocked") ? "Voice unavailable · reconnect Codex" : "Start voice"); mute.setEnabled(running); stop.setEnabled(running);
        mute.setText(muted ? "Unmute microphone" : "Mute microphone");
        approval.setVisibility(scope(state) && state.optInt("pendingApprovals") > 0 ? View.VISIBLE : View.GONE);
        detail.setText("Official Codex voice · ChatGPT account\nAudio stays off until you start. Your phone needs an updated Android System WebView. Voice availability and limits come from Codex. Leaving this screen stops audio; an already accepted coding task may continue in chat.");
        StringBuilder transcript = new StringBuilder();
        if (scope(state) && state.optBoolean("accountConnected") && (expectedAccountToken.isEmpty() || accountCurrent(state))) {
            JSONArray entries = list(voice, "transcript");
            for (int i = 0; i < entries.length(); i++) { JSONObject item = entries.optJSONObject(i); if (item != null) transcript.append("user".equals(item.optString("role")) ? "You: " : "Codex: ").append(item.optString("text")).append("\n\n"); }
            if (!voice.optString("userCaption").isEmpty()) transcript.append("You: ").append(voice.optString("userCaption")).append("\n\n");
            if (!voice.optString("assistantCaption").isEmpty()) transcript.append("Codex: ").append(voice.optString("assistantCaption"));
        }
        String text = transcript.length() == 0 ? "Live conversation text will appear here." : transcript.toString();
        if (!text.equals(renderedCaptions)) { renderedCaptions = text; captions.setText(text); }
    }
    private void build() {
        LinearLayout root = column(); root.setBackground(DeskStyle.background(this)); root.setPadding(dp(20), dp(12), dp(20), dp(16));
        lockShell = new FrameLayout(this); lockShell.addView(root); setContentView(lockShell);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) { android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()); root.setPadding(dp(20) + bars.left, dp(12) + bars.top, dp(20) + bars.right, dp(16) + bars.bottom); }
            else root.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(12) + insets.getSystemWindowInsetTop(), dp(20) + insets.getSystemWindowInsetRight(), dp(16) + insets.getSystemWindowInsetBottom()); return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(button("Back", false, v -> finish()), new LinearLayout.LayoutParams(-2, -2));
        TextView title = text("Codex voice", 23, DeskStyle.TEXT); title.setPadding(dp(16), 0, 0, 0); header.addView(title); root.addView(header);
        TextView projectName = text(project, 13, DeskStyle.MUTED); root.addView(projectName, spacing(14));
        statusView = text("Start a voice conversation", 20, DeskStyle.TEXT); root.addView(statusView, spacing(16));
        detail = text("", 13, DeskStyle.MUTED); root.addView(detail, spacing(16));
        ScrollView scroll = new ScrollView(this); captions = text("Live conversation text will appear here.", 16, DeskStyle.TEXT); captions.setTextIsSelectable(true); captions.setPadding(dp(16), dp(16), dp(16), dp(16)); captions.setBackground(DeskStyle.card(this)); scroll.addView(captions); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        approval = button("Review tool approval in chat", false, v -> { end("Voice ended to review a tool request."); finish(); }); root.addView(approval, spacing(12)); approval.setVisibility(View.GONE);
        start = button("Start voice", true, v -> askStart()); root.addView(start, spacing(12));
        mute = button("Mute microphone", false, v -> { if (safe() && running) { muted = !muted; javascript("window.pocketVoice.mute(" + muted + ")"); render(); } }); root.addView(mute, spacing(8));
        stop = button("End voice", false, v -> end("Voice ended · microphone off")); root.addView(stop, spacing(8));
        transportHost = new FrameLayout(this); root.addView(transportHost, new LinearLayout.LayoutParams(1, 1));
    }
    private LinearLayout column() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private TextView text(String value, int size, int color) { TextView result = new TextView(this); result.setText(value); result.setTextSize(size); result.setTextColor(color); result.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); result.setLineSpacing(0, 1.12f); return result; }
    private Button button(String label, boolean primary, View.OnClickListener click) { Button result = new Button(this); result.setText(label); result.setAllCaps(false); result.setTextSize(14); result.setMinHeight(dp(50)); result.setTextColor(primary ? DeskStyle.PRIMARY_TEXT : DeskStyle.TEXT); result.setBackground(primary ? DeskStyle.primary(this) : DeskStyle.field(this)); result.setOnClickListener(v -> { if (safe()) click.onClick(v); }); return result; }
    private LinearLayout.LayoutParams spacing(int top) { LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(-1, -2); value.topMargin = dp(top); return value; }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
