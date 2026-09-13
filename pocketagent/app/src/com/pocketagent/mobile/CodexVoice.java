package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Official 0.154 WebRTC signaling. The engine owns ChatGPT authentication and tool execution. */
final class CodexVoice {
    interface Reply { void receive(JSONObject result, JSONObject error); }
    interface Host {
        void request(String method, JSONObject params, Reply reply) throws Exception;
        void changed();
        void signal(String owner, String kind, String value);
        void later(Runnable action, long delayMillis);
    }
    private final Host host;
    private String thread = "", owner = "", phase = "idle", status = "Start a voice conversation", error = "";
    private String userCaption = "", assistantCaption = "";
    private final JSONArray transcript = new JSONArray();
    private long epoch;
    private boolean accepted, transportReady, blocked;
    CodexVoice(Host host) { this.host = host; }
    void reset(String id) { closeLocal("Voice stopped"); thread = id == null ? "" : id; blocked = false; phase = "idle"; status = "Start a voice conversation"; error = ""; clearCaptions(); host.changed(); }
    void closed() { closeLocal("Voice disconnected"); thread = ""; phase = "idle"; clearCaptions(); host.changed(); }
    void accountChanged() {
        String message = "Account changed. Reconnect Codex before starting voice again.";
        blocked = true; error = message; status = message; clearCaptions();
        if (active()) stop(message, true); else phase = "unavailable";
        host.changed();
    }
    boolean active() { return "connecting".equals(phase) || "live".equals(phase) || "stopping".equals(phase); }
    boolean changing() { return active(); }
    JSONObject snapshot() {
        return object("phase", phase, "active", active(), "blocked", blocked, "status", status, "error", error,
            "threadId", thread, "owner", owner, "userCaption", userCaption, "assistantCaption", assistantCaption, "transcript", transcript);
    }
    void dispatch(String operation, JSONObject payload) {
        try {
            if (!thread.equals(payload.optString("threadId", "")) || thread.isEmpty()) throw new IllegalArgumentException("The conversation changed. Open Voice from the current chat.");
            String requestedOwner = payload.optString("owner", ""); VoiceTransport.validOwner(requestedOwner);
            if ("start".equals(operation)) {
                if (active()) throw new IllegalStateException("A voice conversation is already open.");
                if (blocked) throw new IllegalStateException("Voice is unavailable on this connection. Reconnect Codex before retrying.");
                if (!VoiceTransport.attached(requestedOwner)) throw new IllegalArgumentException("The voice screen has closed.");
                JSONObject params = startParams(thread, payload.optString("sdp", ""));
                owner = requestedOwner; accepted = false; transportReady = false; error = ""; clearCaptions();
                phase = "connecting"; status = "Connecting official Codex voice…";
                final long own = ++epoch; host.changed();
                host.request("thread/realtime/start", params, (result, failure) -> {
                    if (own != epoch) return;
                    if (failure != null) { unavailable(safeError(failure.optString("message", ""))); return; }
                    accepted = true; ready();
                });
                host.later(() -> { if (own == epoch && "connecting".equals(phase)) stop("Voice did not connect within 30 seconds. Reconnect Codex before retrying.", true); }, 30000);
            } else {
                if (!requestedOwner.equals(owner)) return; // A stale screen cannot end a newer call.
                if ("stop".equals(operation)) stop("Voice ended · microphone off", false);
                else if ("transport_ready".equals(operation)) { if (active() && !"stopping".equals(phase)) { transportReady = true; ready(); } }
                else if ("transport_error".equals(operation)) stop("Voice audio connection ended. The microphone is off.", false);
                else throw new IllegalArgumentException("This voice control is not supported.");
            }
        } catch (Exception failure) {
            if (active() && "connecting".equals(phase) && "start".equals(operation)) unavailable("The Codex voice request could not be sent. Reconnect before retrying.");
            else if (active() && "start".equals(operation)) { status = "A voice conversation is already open."; host.changed(); }
            else if (!active()) { error = clean(failure.getMessage(), 450); status = error; host.changed(); }
        }
    }
    boolean event(String method, JSONObject params) {
        if (!method.startsWith("thread/realtime/")) return false;
        if (!thread.equals(params.optString("threadId", "")) || !active()) return true;
        if ("stopping".equals(phase) && !"thread/realtime/closed".equals(method)) return true;
        if ("thread/realtime/sdp".equals(method)) {
            try { String answer = validSdp(params.optString("sdp", "")); host.signal(owner, "answer", answer); }
            catch (Exception invalid) { stop("Codex returned an unsupported voice connection. Microphone off.", true); }
        } else if ("thread/realtime/started".equals(method)) { accepted = true; ready(); }
        else if ("thread/realtime/error".equals(method)) unavailable(safeError(params.optString("message", "")));
        else if ("thread/realtime/closed".equals(method)) { closeLocal("Voice ended · microphone off"); phase = "idle"; host.changed(); }
        else if ("thread/realtime/transcript/delta".equals(method)) caption(params.optString("role"), params.optString("delta"), false);
        else if ("thread/realtime/transcript/done".equals(method)) caption(params.optString("role"), params.optString("text"), true);
        // WebRTC carries audio directly. Raw outputAudio and arbitrary backend items are never retained.
        return true;
    }
    private void caption(String role, String text, boolean complete) {
        if (!("user".equals(role) || "assistant".equals(role))) return;
        String old = "user".equals(role) ? userCaption : assistantCaption;
        String value = clean(complete ? text : old + clean(text, 3000), 6000);
        if (complete) {
            if (!value.trim().isEmpty()) transcript.put(object("role", role, "text", value));
            while (transcript.length() > 12 || transcript.toString().length() > 16000) transcript.remove(0);
            value = "";
        }
        if ("user".equals(role)) userCaption = value; else assistantCaption = value;
        host.changed();
    }
    private void ready() {
        if (!active() || "stopping".equals(phase)) return;
        if (accepted && transportReady) { phase = "live"; status = "Voice connected"; }
        host.changed();
    }
    private void unavailable(String message) { stop(message, true); }
    private void stop(String message, boolean disable) {
        if (!active()) return;
        if ("stopping".equals(phase)) return;
        final String stopThread = thread, stopOwner = owner;
        final long own = ++epoch;
        blocked |= disable; phase = "stopping"; status = message; if (disable) error = message;
        host.signal(stopOwner, "close", message); host.changed();
        try {
            host.request("thread/realtime/stop", object("threadId", stopThread), (result, failure) -> {
                if (own != epoch) return;
                if (failure != null) { blocked = true; error = "Codex could not confirm voice ended. Reconnect before starting another call."; }
                closeLocal(message); phase = blocked ? "unavailable" : "idle"; host.changed();
            });
        } catch (Exception ignored) {
            blocked = true; error = "Codex could not confirm voice ended. Reconnect before starting another call.";
            closeLocal(message); phase = "unavailable"; host.changed();
        }
        host.later(() -> { if (own == epoch) { blocked = true; error = "Codex did not confirm voice ended. Reconnect before starting another call."; closeLocal(message); phase = "unavailable"; host.changed(); } }, 5000);
    }
    private void closeLocal(String message) {
        if (!owner.isEmpty()) host.signal(owner, "close", message);
        ++epoch; accepted = false; transportReady = false; owner = ""; phase = "idle"; status = message;
    }
    private void clearCaptions() { userCaption = ""; assistantCaption = ""; while (transcript.length() > 0) transcript.remove(0); }
    static JSONObject startParams(String thread, String sdp) {
        if (thread == null || !thread.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")) throw new IllegalArgumentException("Open a Codex conversation first.");
        // V1 is the official WebRTC default. Do not force a model, provider or credential fallback.
        return object("threadId", thread, "outputModality", "audio", "transport", object("type", "webrtc", "sdp", validSdp(sdp)), "version", "v1");
    }
    static String validSdp(String value) {
        if (value == null || value.length() < 5 || value.length() > 65536 || !value.startsWith("v=0") || value.indexOf('\0') >= 0
                || !value.contains("m=audio ") || !value.contains("m=application ") || value.contains("m=video "))
            throw new IllegalArgumentException("This device could not create a supported audio-only WebRTC connection.");
        return value;
    }
    static String safeError(String source) {
        String text = source == null ? "" : source.toLowerCase(java.util.Locale.ROOT);
        if (text.contains("api key") || text.contains("api-key")) return "This Codex voice route requested API-key billing. PocketAgent stopped it; your subscription was not replaced with API billing.";
        if (text.contains("unauthor") || text.contains("authenticat") || text.contains("401")) return "Codex could not authorize voice with this account. Reconnect your ChatGPT account.";
        if (text.contains("403") || text.contains("not available") || text.contains("eligib") || text.contains("access") || text.contains("permission")) return "Codex has not granted voice access to this account or workspace.";
        if (text.contains("429") || text.contains("limit") || text.contains("quota")) return "Codex reported a voice usage limit. Check your official account limits before retrying.";
        if (text.contains("method") || text.contains("unsupported") || text.contains("experimental")) return "This Codex engine does not support the required voice protocol. Update and reconnect.";
        return "Codex could not establish the voice connection. Microphone off; reconnect before retrying.";
    }
}
