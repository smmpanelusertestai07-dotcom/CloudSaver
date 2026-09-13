package com.pocketagent.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.pocketagent.mobile.AgentProtocol.*;

/**
 * A single foreground, user-started agent connection. Engines own their login and credentials.
 * Transport callbacks and UI actions are serialized; an Activity never owns a Linux process.
 */
public final class AgentService extends Service {
    static final String ACTION_CONNECT = "com.pocketagent.mobile.agent.CONNECT";
    static final String ACTION_SWITCH = "com.pocketagent.mobile.agent.SWITCH";
    static final String ACTION_RECONNECT = "com.pocketagent.mobile.agent.RECONNECT";
    static final String ACTION_PROMPT = "com.pocketagent.mobile.agent.PROMPT";
    static final String ACTION_CANCEL = "com.pocketagent.mobile.agent.CANCEL";
    static final String ACTION_REPLY_PERMISSION = "com.pocketagent.mobile.agent.REPLY_PERMISSION";
    static final String ACTION_REFRESH = "com.pocketagent.mobile.agent.REFRESH";
    static final String ACTION_DISCONNECT = "com.pocketagent.mobile.agent.DISCONNECT";
    static final String ACTION_MODEL = "com.pocketagent.mobile.agent.MODEL";
    static final String ACTION_EFFORT = "com.pocketagent.mobile.agent.EFFORT";
    static final String ACTION_INSTALL = "com.pocketagent.mobile.agent.INSTALL";
    static final String ACTION_NEW_SESSION = "com.pocketagent.mobile.agent.NEW_SESSION";
    static final String ACTION_INTEGRATION = "com.pocketagent.mobile.agent.INTEGRATION";
    static final String ACTION_MODE = "com.pocketagent.mobile.agent.MODE";
    static final String ACTION_SESSION = "com.pocketagent.mobile.agent.SESSION";
    static final String ACTION_CONTROL = "com.pocketagent.mobile.agent.CONTROL";
    static final String ACTION_CLIENT = "com.pocketagent.mobile.agent.CLIENT";
    static final String ACTION_VOICE = "com.pocketagent.mobile.agent.VOICE";
    static final String EVENT = "com.pocketagent.mobile.agent.EVENT";
    static final String EXTRA_SNAPSHOT = "snapshot";
    static final String EXTRA_PROVIDER = "provider";
    static final String EXTRA_PROJECT = "project";
    static final String EXTRA_EXPECTED_PROVIDER = "expectedProvider";
    static final String EXTRA_EXPECTED_PROJECT = "expectedProject";
    static final String EXTRA_EXPECTED_ACCOUNT_TOKEN = "expectedAccountToken";
    static final String EXTRA_SWITCH_ID = "switchId";
    private static final String EXTRA_SWITCH_REJECTION = "switchIngressRejection";
    static final String EXTRA_TEXT = "text";
    static final String EXTRA_MODEL = "model";
    static final String EXTRA_EFFORT = "effort";
    static final String EXTRA_OPERATION = "operation";
    static final String EXTRA_PAYLOAD = "payload";
    static final String EXTRA_SKILL_PATH = "skill_path";
    static final String EXTRA_APP_ID = "app_id";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_ATTACHMENTS = "attachments";
    static final String EXTRA_CLIENT_ID = "client_id";
    static final String EXTRA_ACTIVE = "active";
    static final String EXTRA_PERMISSION_ID = "permission_id";
    static final String EXTRA_REPLY = "reply";
    static final String EXTRA_FULL_ACCESS = "full_access";

    private static final int NOTIFICATION_ID = 2314;
    private static final String CHANNEL = "pocketagent_agent";
    private static final Pattern URL = Pattern.compile("https://[^\\s<>\\\"']+");
    private static volatile String latestSnapshot = "{}";
    private final ExecutorService serial = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<String, Approval> approvals = new LinkedHashMap<>();
    private final ArrayList<JSONObject> messages = new ArrayList<>();
    private final String permissionEpoch = java.util.UUID.randomUUID().toString();
    private volatile Process process;
    private OutputStream stdin;
    private PowerManager.WakeLock wakeLock;
    private String provider = "codex", projectName = "my-project", cwd = "";
    private String status = "Choose an agent", threadId = "", turnId = "", selectedModel = "";
    private String selectedEffort = CodexEffort.AUTO, effortModel = "", confirmedModel = "", confirmedEffort = "";
    private String selectedMode = CodexControls.ASK, modeError = "", controlOperation = "", controlError = "", controlStatus = "";
    private JSONArray collaborationModes = new JSONArray();
    private JSONObject environment = new JSONObject();
    private boolean modeKnown, confirmedPlan, manualSessionResume, modelChanging;
    private String pendingRestoredModel = "", switchRequestId = "", switchError = "";
    private String sessionAccountScope = "";
    private final CodexRefresh metadataRefresh = new CodexRefresh();
    private boolean codexInitialized, initialAccountRead, accountVerified, modeLoading;
    private long accountEpoch, lastModeAttempt;
    private JSONObject creditsResetState = new JSONObject();
    private String authUrl = "", authCode = "", activeAssistantId = "";
    private JSONObject account = new JSONObject(), rateLimits = new JSONObject();
    private JSONObject tokenUsage = new JSONObject();
    private long usageUpdatedAt, tokenUsageUpdatedAt;
    private JSONArray models = new JSONArray(), configOptions = new JSONArray();
    private final ArrayList<JSONObject> runtimeDiagnostics = new ArrayList<>();
    private boolean connected, busy, fullAccess, acpCanLoad, authenticating, automaticConnection;
    private final AgentReconnect.Recovery connectionRecovery = new AgentReconnect.Recovery();
    private JSONObject deferredSessionResume;
    private String historyNotice = "";
    private boolean sessionOpening, recoveringSession, detachedConversation;
    private final AgentProtocol.CodexRecovery sessionRecovery = new AgentProtocol.CodexRecovery();
    private volatile boolean connecting;
    private boolean hasError;
    private volatile boolean installing;
    private volatile Thread installationThread;
    private volatile String installationStopReason = "";
    private boolean closing;
    private volatile boolean destroyed;
    private long generation, sequence, messageSequence, permissionSequence;
    private final CodexControls.TaskEpoch taskEpoch = new CodexControls.TaskEpoch();
    private final AgentSnapshotDelivery snapshotDelivery = new AgentSnapshotDelivery(
            task -> main.postDelayed(task, 100), this::deliverSnapshot);
    private long lastPersist;
    private JSONArray authMethods = new JSONArray();
    private ClaudeBridge claude;
    private final CodexIntegrations integrations = new CodexIntegrations(new CodexIntegrations.Host() {
        @Override public void request(String method, JSONObject params, CodexIntegrations.Reply reply) throws Exception {
            AgentService.this.request(method, params, (result, error) -> reply.receive(result, error));
        }
        @Override public void createSkill(String name, String description, String instructions) throws Exception {
            File project = new File(ContainerRuntime.workspaceRoot(AgentService.this), projectName);
            CodexSkillFiles.create(AgentService.this, project, name, description, instructions);
        }
        @Override public void changed() { publish(); }
    });
    private final CodexSessionControls sessions = new CodexSessionControls(new CodexSessionControls.Host() {
        @Override public void request(String method, JSONObject params, CodexSessionControls.Reply reply) throws Exception {
            AgentService.this.request(method, params, (result, error) -> reply.receive(result, error));
        }
        @Override public void resume(String id) throws Exception {
            if (id.equals(threadId)) { sessions.resumed(id); return; }
            detachConversation(threadId, "switch-session");
            manualSessionResume = true; sessionRecovery.reset();
            openCodexSession(id);
        }
        @Override public void closedThread(String id, String operation) throws Exception {
            if (!id.equals(threadId)) return;
            detachConversation(id, operation);
            openCodexSession("");
        }
        @Override public void observed(JSONArray rows) throws Exception {
            if (isCodex() && accountVerified && AgentProtocol.chatgptAccount(account))
                LocalSessionIndex.observe(getFilesDir(), provider, projectName, account, rateLimits, rows);
        }
        @Override public void changedThread(JSONObject change) throws Exception {
            if (isCodex() && accountVerified && AgentProtocol.chatgptAccount(account))
                LocalSessionIndex.changed(getFilesDir(), provider, projectName, account, rateLimits, change);
        }
        @Override public void changed() { publish(); }
    });
    private final CodexBackgroundTasks backgroundTasks = new CodexBackgroundTasks(new CodexBackgroundTasks.Host() {
        @Override public void request(String method, JSONObject params, CodexBackgroundTasks.Reply reply) throws Exception {
            AgentService.this.request(method, params, (result, error) -> reply.receive(result, error));
        }
        @Override public void changed() { publish(); }
    });
    private final CodexVoice voice = new CodexVoice(new CodexVoice.Host() {
        @Override public void request(String method, JSONObject params, CodexVoice.Reply reply) throws Exception {
            AgentService.this.request(method, params, (result, error) -> reply.receive(result, error));
        }
        @Override public void changed() { if (voice != null && voice.active()) lease(); publish(); }
        @Override public void signal(String owner, String kind, String value) { VoiceTransport.signal(owner, kind, value); }
        @Override public void later(Runnable action, long delayMillis) { main.postDelayed(() -> execute(action), delayMillis); }
    });

    private interface Reply { void receive(JSONObject result, JSONObject error) throws Exception; }
    private static final class Pending {
        final String method; final Reply reply;
        Pending(String method, Reply reply) { this.method = method; this.reply = reply; }
    }
    private static final class Approval {
        Object wireId; String method, key; JSONObject params, card;
        String notificationSession = "", notificationId = "";
        JSONArray questions; int questionIndex;
        JSONObject answers = new JSONObject();
        JSONArray cursorAnswers = new JSONArray();
    }

    static JSONObject snapshot() {
        try { return new JSONObject(latestSnapshot); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    /** Reopen only a previously successful connection; never restores a browser login or installs an engine. */
    static boolean canReconnect(android.content.Context context, String provider, String project) {
        try {
            return AgentReconnect.eligible(connectionMemory(context, provider, project), provider, project)
                    && ContainerRuntime.isWorkspaceInstalled(context) && AgentInstaller.isInstalled(context, provider);
        } catch (Exception unavailable) { return false; }
    }

    private static JSONObject connectionMemory(android.content.Context context, String provider, String project) {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("pocketagent_sessions", MODE_PRIVATE);
            String key = AgentWorkspaceState.connectionKey(provider, project);
            JSONObject saved = new JSONObject(prefs.getString(key, "{}"));
            if (prefs.contains(key)) return AgentReconnect.eligible(saved, provider, project) ? saved : new JSONObject();
            JSONObject legacy = new JSONObject(prefs.getString("connectionMemory", "{}"));
            if (!AgentReconnect.eligible(legacy, provider, project)) return new JSONObject();
            // Migrate only this exact successful scope; never copy another provider's access choice.
            prefs.edit().putString(key, legacy.toString()).remove("connectionMemory").apply();
            return legacy;
        } catch (Exception ignored) { return new JSONObject(); }
    }
    private JSONObject connectionMemory() { return connectionMemory(this, provider, projectName); }
    private void rememberConnection() {
        getSharedPreferences("pocketagent_sessions", MODE_PRIVATE).edit()
                .putString(AgentWorkspaceState.connectionKey(provider, projectName), AgentReconnect.remember(provider, projectName, fullAccess).toString()).apply();
    }
    private void forgetReconnect() {
        android.content.SharedPreferences prefs = getSharedPreferences("pocketagent_sessions", MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = prefs.edit().putString(AgentWorkspaceState.connectionKey(provider, projectName), "{}");
        try {
            if (AgentReconnect.eligible(new JSONObject(prefs.getString("connectionMemory", "{}")), provider, projectName)) editor.remove("connectionMemory");
        } catch (Exception ignored) { }
        editor.apply();
    }
    private void forgetProviderConnections() {
        android.content.SharedPreferences prefs = getSharedPreferences("pocketagent_sessions", MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = prefs.edit();
        String prefix = "connection:" + provider + ":";
        for (String key : prefs.getAll().keySet()) if (key.startsWith(prefix)) editor.putString(key, "{}");
        try { if (provider.equals(new JSONObject(prefs.getString("connectionMemory", "{}")).optString("provider"))) editor.remove("connectionMemory"); }
        catch (Exception ignored) { }
        editor.apply();
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "PocketAgent agent", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps your local agent running while you use another app.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketAgent:Agent");
        wakeLock.setReferenceCounted(false);
        execute(() -> { restore(); publish(); });
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                execute(() -> pumpRefresh());
                main.postDelayed(this, 10000L);
            }
        }, 10000L);
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                final String guard = protectionReason();
                if (!guard.isEmpty()) {
                    if (installing) {
                        installationStopReason = guard;
                        Thread worker = installationThread;
                        if (worker != null) worker.interrupt();
                    }
                    execute(() -> {
                        if (process != null) { disconnect(false); fail(guard); }
                    });
                } else {
                    execute(() -> { if (busy || installing || authenticating || voice.active()) lease(); });
                    if (installing) lease();
                }
                main.postDelayed(this, 30000);
            }
        }, 30000);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CLIENT.equals(intent.getAction()) && process == null && !connecting && !installing && !authenticating) {
            stopSelf(startId); return START_NOT_STICKY;
        }
        foreground("PocketAgent workspace");
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if ((ACTION_CONNECT.equals(intent.getAction()) || ACTION_RECONNECT.equals(intent.getAction())) && (process != null || installing || connecting)) {
            JSONObject current = snapshot();
            if ((current.optBoolean("connected") || !current.optBoolean("error"))
                    && current.optString("provider").equals(intent.getStringExtra(EXTRA_PROVIDER))
                    && current.optString("project").equals(intent.getStringExtra(EXTRA_PROJECT))
                    && (ACTION_RECONNECT.equals(intent.getAction()) || current.optBoolean("fullAccess") == intent.getBooleanExtra(EXTRA_FULL_ACCESS, false))) {
                // Repeated taps must not queue a second install or cancel an active login.
                snapshotDelivery.publish();
                return START_NOT_STICKY;
            }
        }
        if ((ACTION_CANCEL.equals(intent.getAction()) || ACTION_DISCONNECT.equals(intent.getAction())) && installing) {
            if (ACTION_DISCONNECT.equals(intent.getAction())) forgetReconnect();
            installationStopReason = "Installation stopped. You can tap Connect to resume setup.";
            Thread worker = installationThread;
            if (worker != null) worker.interrupt();
            return START_NOT_STICKY;
        }
        final Intent action = new Intent(intent);
        if (ACTION_SWITCH.equals(action.getAction()) || ACTION_CONNECT.equals(action.getAction())) {
            // Capture busy state now: an intent queued behind setup must not switch later.
            JSONObject before = snapshot();
            if (before.has("switchAllowed") && !before.optBoolean("switchAllowed"))
                action.putExtra(EXTRA_SWITCH_REJECTION, before.optString("switchBlockedReason", "Wait for the current action to finish."));
        }
        execute(() -> {
            try { dispatch(action); }
            catch (Exception failure) {
                String reason = installationStopReason; installationStopReason = "";
                fail(reason.isEmpty() ? failure.getMessage() : reason);
            }
            finally { if (process == null && !installing && !connecting) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); } }
        });
        return START_NOT_STICKY;
    }

    private void dispatch(Intent action) throws Exception {
        String kind = action.getAction();
        if (ACTION_CLIENT.equals(kind)) {
            if (!provider.equals(action.getStringExtra(EXTRA_PROVIDER)) || !projectName.equals(action.getStringExtra(EXTRA_PROJECT))) return;
            metadataRefresh.client(action.getStringExtra(EXTRA_CLIENT_ID), action.getBooleanExtra(EXTRA_ACTIVE, false), SystemClock.elapsedRealtime());
            connectionRecovery.client(sessionKey(), action.getStringExtra(EXTRA_CLIENT_ID), action.getBooleanExtra(EXTRA_ACTIVE, false), SystemClock.elapsedRealtime());
            pumpRefresh(); publish();
        } else if (ACTION_VOICE.equals(kind)) {
            voiceAction(action);
        } else if (ACTION_SWITCH.equals(kind)) {
            switchAgent(action);
        } else if (ACTION_RECONNECT.equals(kind)) {
            // A delayed Activity intent cannot revive an explicitly disconnected account, switch
            // another live workspace, grant broader access, or install missing software.
            if (process != null || installing || connecting || authenticating) { publish(); return; }
            String id = action.getStringExtra(EXTRA_PROVIDER), name = action.getStringExtra(EXTRA_PROJECT);
            JSONObject remembered = connectionMemory(this, id, name);
            if (!canReconnect(this, id, name)) { publish(); return; }
            connect(id, name, remembered.optBoolean("fullAccess"), true);
        } else if (ACTION_CONNECT.equals(kind)) {
            String blocked = action.getStringExtra(EXTRA_SWITCH_REJECTION);
            if (blocked == null || blocked.isEmpty()) blocked = switchBlockedReason();
            if (!blocked.isEmpty()) { controlError = blocked; publish(); return; }
            connectionRecovery.explicitConnect();
            connect(action.getStringExtra(EXTRA_PROVIDER), action.getStringExtra(EXTRA_PROJECT), action.getBooleanExtra(EXTRA_FULL_ACCESS, false));
        } else if (ACTION_INTEGRATION.equals(kind)) {
            integrationAction(action);
        } else if (ACTION_SESSION.equals(kind)) {
            sessionAction(action);
        } else if (ACTION_CONTROL.equals(kind)) {
            controlAction(action);
        } else if (ACTION_MODE.equals(kind)) {
            requireScope(action); requireIdleCodex();
            String mode = action.getStringExtra(EXTRA_MODE);
            CodexControls.validateMode(mode, collaborationModes, effectiveModel());
            selectedMode = mode; persistSettings(); hasError = false; status = "Mode selected · applies to your next prompt"; publish();
        } else if (ACTION_INSTALL.equals(kind)) {
            if (process != null || busy) throw new IllegalStateException("Disconnect the active agent before installing another engine.");
            install(action.getStringExtra(EXTRA_PROVIDER));
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else if (ACTION_PROMPT.equals(kind)) {
            if (!provider.equals(action.getStringExtra(EXTRA_PROVIDER)) || !projectName.equals(action.getStringExtra(EXTRA_PROJECT)))
                throw new IllegalStateException("The selected agent or project changed. Connect that project before sending a prompt.");
            prompt(action.getStringExtra(EXTRA_TEXT), action.getStringExtra(EXTRA_SKILL_PATH), action.getStringExtra(EXTRA_APP_ID), action.getStringExtra(EXTRA_ATTACHMENTS));
        }
        else if (ACTION_CANCEL.equals(kind)) { if (action.hasExtra(EXTRA_PROJECT)) requireScope(action); cancel(); }
        else if (ACTION_DISCONNECT.equals(kind)) { if (action.hasExtra(EXTRA_PROJECT)) requireScope(action); forgetReconnect(); disconnect(true); stopSelf(); }
        else if (ACTION_REFRESH.equals(kind)) { if (action.hasExtra(EXTRA_PROJECT)) requireScope(action); refresh(); }
        else if (ACTION_REPLY_PERMISSION.equals(kind)) answer(action.getStringExtra(EXTRA_PERMISSION_ID), action.getStringExtra(EXTRA_REPLY), action.getStringExtra(EXTRA_TEXT));
        else if (ACTION_MODEL.equals(kind) || ACTION_EFFORT.equals(kind)) {
            if (!provider.equals(action.getStringExtra(EXTRA_PROVIDER)) || !projectName.equals(action.getStringExtra(EXTRA_PROJECT)))
                throw new IllegalStateException("The selected agent or project changed. Connect that project before changing its model or reasoning level.");
            if (action.hasExtra(EXTRA_EXPECTED_ACCOUNT_TOKEN) && !accountScopeToken().equals(action.getStringExtra(EXTRA_EXPECTED_ACCOUNT_TOKEN)))
                throw new IllegalStateException("The connected account changed. Choose the model again.");
            if (action.hasExtra("expectedThreadId") && !threadId.equals(action.getStringExtra("expectedThreadId")))
                throw new IllegalStateException("The conversation changed. Choose the model again.");
            if (ACTION_EFFORT.equals(kind)) selectEffort(action.getStringExtra(EXTRA_EFFORT));
            else selectModel(action.getStringExtra(EXTRA_MODEL));
        }
        else if (ACTION_NEW_SESSION.equals(kind)) {
            requireProjectIdle();
            if (voice.active()) throw new IllegalStateException("End the voice conversation before starting another chat.");
            if (sessions.changing() || !controlOperation.isEmpty()) throw new IllegalStateException("Wait for the current conversation action to finish.");
            if (busy) throw new IllegalStateException("Stop the current task before starting a new chat.");
            if (integrations.changing()) throw new IllegalStateException("Wait for the integration change before starting a new chat.");
            if (sessionOpening || connecting) throw new IllegalStateException("Wait for the current connection before starting a new chat.");
            if (!provider.equals(action.getStringExtra(EXTRA_PROVIDER)) || !projectName.equals(action.getStringExtra(EXTRA_PROJECT)))
                throw new IllegalStateException("The selected agent or project changed. Open that project before starting a new chat.");
            hasError = false;
            detachConversation(getSharedPreferences("pocketagent_sessions", MODE_PRIVATE).getString(sessionKey(), ""), "new-chat");
            if (process != null) createSession(); else publish();
        }
    }

    private String switchBlockedReason() {
        return AgentSwitchPolicy.blocked(busy, installing, connecting, authenticating, sessionOpening,
                recoveringSession, !approvals.isEmpty(), voice.active(), modelChanging,
                integrations.changing() || integrations.snapshot().optBoolean("oauthPending"),
                sessions.pending(), backgroundTasks.changing() || !controlOperation.isEmpty(),
                WorkspaceTools.isProjectOperationBusy());
    }

    private void switchAgent(Intent action) {
        String requestId = clean(action.getStringExtra(EXTRA_SWITCH_ID), 160);
        if (!requestId.isEmpty() && requestId.equals(switchRequestId)) { publish(); return; }
        switchError = "";
        boolean selected = false;
        try {
            String target = action.getStringExtra(EXTRA_PROVIDER), name = AgentProtocol.project(action.getStringExtra(EXTRA_PROJECT));
            if (!AgentCatalog.isValid(target)) throw new IllegalArgumentException("Choose an available agent.");
            // A retried ticket after process recreation may find its target already selected.
            // This acknowledgement cannot stop a task or start a second engine.
            if (provider.equals(target) && projectName.equals(name)) { switchRequestId = requestId; publish(); return; }
            String rejection = action.getStringExtra(EXTRA_SWITCH_REJECTION);
            if (rejection != null && !rejection.isEmpty()) throw new IllegalStateException(rejection);
            AgentSwitchPolicy.requireOrigin(provider, projectName, accountScopeToken(),
                    action.getStringExtra(EXTRA_EXPECTED_PROVIDER), action.getStringExtra(EXTRA_EXPECTED_PROJECT),
                    action.hasExtra(EXTRA_EXPECTED_ACCOUNT_TOKEN) ? action.getStringExtra(EXTRA_EXPECTED_ACCOUNT_TOKEN) : null);
            String blocked = switchBlockedReason();
            if (!blocked.isEmpty()) throw new IllegalStateException(blocked);
            persistSettings(); disconnect(false);
            provider = target; projectName = name; cwd = "/home/coder/Projects/" + name;
            clearConnectionMetadata(); messages.clear(); restoreSettings(); restoreConversation();
            JSONObject remembered = connectionMemory(); fullAccess = remembered.optBoolean("fullAccess", false);
            hasError = false; status = "Connect " + AgentCatalog.name(provider); selected = true;
            persist(); switchRequestId = requestId; publish();
            if (canReconnect(this, provider, projectName)) {
                connectionRecovery.explicitConnect();
                connect(provider, projectName, fullAccess, true);
            }
        } catch (Exception failure) {
            if (selected) fail(failure.getMessage());
            else { switchRequestId = requestId; switchError = clean(failure.getMessage(), 350); publish(); }
        }
    }

    private void requireScope(Intent action) {
        if (!provider.equals(action.getStringExtra(EXTRA_PROVIDER)) || !projectName.equals(action.getStringExtra(EXTRA_PROJECT)))
            throw new IllegalStateException("The selected agent or project changed. Reopen that project first.");
    }

    private void requireProjectIdle() {
        if (WorkspaceTools.isProjectOperationBusy()) throw new IllegalStateException("Project tools are changing files. Wait for them to finish.");
    }

    private void requireIdleCodex() {
        requireProjectIdle();
        if (!isCodex() || process == null || !accountVerified || !AgentProtocol.chatgptAccount(account))
            throw new IllegalStateException("Connect your ChatGPT account in Codex first.");
        if (busy || voice.active() || sessionOpening || connecting || authenticating || installing || integrations.changing() || sessions.changing() || backgroundTasks.changing() || !controlOperation.isEmpty())
            throw new IllegalStateException("Finish the current task or connection before changing Codex controls.");
    }

    private JSONObject actionPayload(Intent action) throws Exception {
        String value = action.getStringExtra(EXTRA_PAYLOAD);
        if (value == null || value.isEmpty()) return new JSONObject();
        if (value.length() > 40000) throw new IllegalArgumentException("This action is too large.");
        return new JSONObject(value);
    }

    private void voiceAction(Intent action) {
        String owner = "";
        try {
            requireScope(action);
            String encoded = action.getStringExtra(EXTRA_PAYLOAD);
            if (encoded == null || encoded.length() > 80000) throw new IllegalArgumentException("Unsupported voice signaling data.");
            JSONObject payload = new JSONObject(encoded); owner = payload.optString("owner", "");
            String operation = action.getStringExtra(EXTRA_OPERATION);
            if ("start".equals(operation)) {
                requireIdleCodex();
                if (!connected || !accountVerified || !accountScopeToken().equals(payload.optString("expectedAccountToken", "")))
                    throw new IllegalStateException("The verified account changed. Reopen Voice from the current conversation.");
            }
            if (!isCodex() || process == null || !threadId.equals(payload.optString("threadId"))) throw new IllegalStateException("The Codex conversation changed.");
            voice.dispatch(operation, payload);
        } catch (Exception failure) {
            try { VoiceTransport.signal(owner, "close", "Voice could not start on this account or conversation. Reopen Voice after the current action finishes."); } catch (RuntimeException ignored) { }
        }
    }

    private void sessionAction(Intent action) {
        if (sessions.pending()) return; // Only the owning controller callback may release its operation.
        try {
            requireScope(action);
            JSONObject payload = actionPayload(action);
            if (payload.has("expectedAccountToken") && (!accountVerified || accountScopeToken().isEmpty()
                    || !accountScopeToken().equals(payload.optString("expectedAccountToken", ""))))
                throw new IllegalStateException("Your account changed. Reopen chat options and try again.");
            if ("resume".equals(action.getStringExtra(EXTRA_OPERATION)) && isCodex() && process == null
                    && !installing && !connecting && !authenticating) {
                String id = payload.optString("threadId", "");
                if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,159}")) throw new IllegalArgumentException("Choose a saved conversation.");
                if (!canReconnect(this, provider, projectName)) throw new IllegalStateException("Connect Codex to open this conversation.");
                boolean rememberedAccess = connectionMemory().optBoolean("fullAccess");
                connectionRecovery.explicitConnect();
                connect(provider, projectName, rememberedAccess, true);
                deferredSessionResume = object("threadId", id);
                return;
            }
            requireIdleCodex();
            sessions.dispatch(action.getStringExtra(EXTRA_OPERATION), payload);
        } catch (Exception failure) { if (process == null && !installing) connecting = false; sessions.reject(failure.getMessage()); }
    }

    private void controlAction(Intent action) {
        String requested = action.getStringExtra(EXTRA_OPERATION);
        if ("logout".equals(requested)) { logoutCodex(action); return; }
        if (requested != null && requested.startsWith("tasks_")) {
            try {
                requireScope(action);
                if (!isCodex() || process == null || !connected || !accountVerified || !AgentProtocol.chatgptAccount(account) || sessionOpening || sessions.changing())
                    throw new IllegalStateException("Open the selected Codex conversation before managing background tasks.");
                backgroundTasks.dispatch(requested, actionPayload(action));
            } catch (Exception failure) { controlError = clean(failure.getMessage(), 500); publish(); }
            return;
        }
        // A rejected second tap must never clear the first operation's lock.
        if (!controlOperation.isEmpty()) { controlError = "Wait for the current Codex action to finish."; publish(); return; }
        try {
            requireScope(action); requireIdleCodex();
            JSONObject payload = actionPayload(action);
            String operation = action.getStringExtra(EXTRA_OPERATION);
            if ("credits_reset".equals(operation)) {
                redeemResetCredit(payload);
            } else if ("compact".equals(operation) || "review".equals(operation)) {
                if (!connected || threadId.isEmpty()) throw new IllegalStateException("Open a Codex conversation first.");
                JSONObject params = "compact".equals(operation) ? object("threadId", threadId) : CodexControls.reviewParams(threadId, payload);
                final long ownTask = taskEpoch.begin();
                beginControl(operation, "compact".equals(operation) ? "Requesting context compaction…" : "Starting Codex review…");
                busy = true; lease(); publish();
                request("compact".equals(operation) ? "thread/compact/start" : "review/start", params, (result, error) -> {
                    if (!taskEpoch.current(ownTask) || !operation.equals(controlOperation)) return;
                    if (error != null) { busy = false; releaseLease(); endControlError(error.optString("message", "Codex could not start this action.")); return; }
                    String returnedTurn = child(result, "turn").optString("id", "");
                    if (!returnedTurn.isEmpty()) turnId = returnedTurn;
                    controlStatus = "compact".equals(operation) ? "Codex accepted compaction · waiting for completion" : "Codex review is running";
                    status = controlStatus; publish();
                });
            } else if ("env_read".equals(operation)) {
                beginControl(operation, "Reading environment variable names…");
                request("config/read", CodexEnvironment.readParams(), (result, error) -> {
                    if (error != null) { endControlError("Codex could not read environment settings."); return; }
                    environment = object("known", true, "names", CodexEnvironment.names(result), "updatedAt", System.currentTimeMillis(), "reconnectRequired", environment.optBoolean("reconnectRequired"));
                    endControl("Environment variable names loaded");
                });
            } else if ("env_set".equals(operation)) {
                if (!Boolean.TRUE.equals(payload.opt("confirmed"))) throw new IllegalArgumentException("Confirm saving this environment variable first.");
                JSONObject params = CodexEnvironment.writeEntryParams(payload.optString("name", ""), payload.optString("value", ""));
                beginControl(operation, "Saving environment variable…");
                request("config/value/write", params, (result, error) -> {
                    if (error != null) { endControlError("Codex could not save the environment variable. Its value was not added to chat."); return; }
                    environment = object("known", false, "reconnectRequired", true);
                    endControl("Environment saved. Reconnect Codex before running commands with this value.");
                });
            } else throw new IllegalArgumentException("This Codex control is not supported by this build.");
        } catch (Exception failure) { endControlError(failure.getMessage()); }
    }

    private void logoutCodex(Intent action) {
        if ("logout".equals(controlOperation)) return;
        boolean ownsLogout = false;
        try {
            JSONObject payload = actionPayload(action);
            if (!Boolean.TRUE.equals(payload.opt("confirmed"))) throw new IllegalArgumentException("Confirm signing out and stopping any current Codex task or login first.");
            if (!"codex".equals(action.getStringExtra(EXTRA_PROVIDER))) throw new IllegalArgumentException("This official logout action belongs to Codex.");
            String requestedProject = AgentProtocol.project(action.getStringExtra(EXTRA_PROJECT));
            if (installing) throw new IllegalStateException("Let the engine installation finish before signing out.");
            if (process != null || connecting || busy) requireScope(action);
            else if (!isCodex() || !projectName.equals(requestedProject)) {
                // Logout concerns the local Codex account. An idle project selection never requires
                // connecting an account merely to remove its official credentials.
                persist(); provider = "codex"; projectName = requestedProject;
                clearConnectionMetadata(); messages.clear(); restoreConversation();
                cwd = "/home/coder/Projects/" + projectName;
            }
            if (!ContainerRuntime.isWorkspaceInstalled(this) || !AgentInstaller.isInstalled(this, "codex"))
                throw new IllegalStateException("The installed Codex workspace is required to run its official logout command.");

            boolean readyRpc = process != null && connected && !busy && !connecting && !authenticating
                    && !sessionOpening && controlOperation.isEmpty() && !integrations.changing() && !sessions.changing();
            ownsLogout = true; forgetProviderConnections();
            if (readyRpc) {
                beginControl("logout", "Signing out of Codex…");
                request("account/logout", null, (result, error) -> {
                    if (error != null) { logoutFailed("Codex could not confirm sign-out. Disconnect and try signing out again."); return; }
                    finishLogout();
                });
                return;
            }

            // Confirmation covers stopping an active turn or browser login. Stop the old process
            // before logout so a late login callback cannot write credentials back afterwards.
            disconnect(false);
            foreground("Signing out of Codex"); beginControl("logout", "Signing out of Codex…"); lease();
            final Process logoutProcess = ContainerRuntime.startAgentContainer(this, CodexControls.logoutCommand());
            process = logoutProcess; stdin = null;
            try { CodexLogout.run(logoutProcess, ProotProcess::stopAndWait, 30000L); }
            finally { if (process == logoutProcess) process = null; releaseLease(); }
            finishLogout();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); logoutFailed("Codex sign-out was interrupted. Its completion could not be confirmed; try again.");
        } catch (Exception failure) {
            String message = clean(failure.getMessage(), 500);
            if (ownsLogout) logoutFailed(message);
            else { controlError = message; publish(); }
        }
    }

    private void finishLogout() {
        account = new JSONObject(); rateLimits = new JSONObject(); usageUpdatedAt = 0; clearTokenUsage();
        integrations.reset(cwd, ""); sessions.reset(cwd, "", ""); sessionAccountScope = ""; environment = new JSONObject();
        controlOperation = ""; controlError = ""; controlStatus = "Signed out · project files and local chat copies kept";
        disconnect(false); hasError = false; status = controlStatus; publish();
    }

    private void logoutFailed(String message) {
        endControlError(message); status = controlError; hasError = true; publish();
    }

    private void beginControl(String operation, String message) { controlOperation = operation; controlError = ""; controlStatus = message; publish(); }
    private void endControl(String message) { controlOperation = ""; controlError = ""; controlStatus = message; publish(); }
    private void endControlError(String message) { controlOperation = ""; controlError = clean(message, 500); controlStatus = controlError; publish(); }

    private void redeemResetCredit(JSONObject payload) throws Exception {
        if (!Boolean.TRUE.equals(payload.opt("confirmed"))) throw new IllegalArgumentException("Confirm consuming one earned reset credit first.");
        String expected = payload.optString("expectedAccountToken", "");
        CodexCredits.consent(expected, accountScopeToken());
        if (!accountVerified || usageUpdatedAt == 0 || child(metadataRefresh.snapshot(SystemClock.elapsedRealtime(), System.currentTimeMillis()), "usage").optBoolean("stale"))
            throw new IllegalStateException("Wait for a fresh, verified account-usage response before using an earned reset.");
        final String preferenceKey = CodexCredits.scopeKey(sessionAccountScope, rateLimits.isNull("accountId") ? "" : rateLimits.optString("accountId"));
        android.content.SharedPreferences saved = getSharedPreferences("pocketagent_credit_resets", MODE_PRIVATE);
        JSONObject attempt = new JSONObject(saved.getString(preferenceKey, "{}"));
        String creditId = payload.optString("creditId", "");
        if (attempt.has("idempotencyKey")) {
            String prior = attempt.optString("creditId", "");
            if (!creditId.isEmpty() && !creditId.equals(prior)) throw new IllegalStateException("Retry the pending reset request before selecting another earned credit.");
            creditId = prior;
        } else {
            CodexCredits.validateSelection(rateLimits, creditId);
            attempt = CodexCredits.params(java.util.UUID.randomUUID().toString(), creditId);
            if (!saved.edit().putString(preferenceKey, attempt.toString()).commit()) throw new IllegalStateException("Cannot safely save the reset receipt. Free storage and try again.");
        }
        JSONObject params = CodexCredits.params(attempt.optString("idempotencyKey"), creditId);
        final long ownAccount = accountEpoch;
        creditsResetState = object("operation", "credits_reset", "status", "Asking Codex to use one earned reset…", "error", "", "canRetry", false, "awaitingRefresh", false);
        beginControl("credits_reset", "Requesting an earned rate-limit reset…");
        try {
            request("account/rateLimitResetCredit/consume", params, (result, error) -> {
                if (ownAccount != accountEpoch) return;
                String outcome = error == null ? CodexCredits.outcome(result.optString("outcome")) : "";
                if (outcome.isEmpty()) {
                    String message = error == null ? "Codex returned an unrecognized reset result." : clean(error.optString("message", "The reset result could not be confirmed."), 350);
                    creditsResetState = object("operation", "", "status", "Reset result unconfirmed. Retry reuses the same request.", "error", message, "canRetry", true, "awaitingRefresh", false);
                    endControlError(message);
                } else {
                    boolean cleared = saved.edit().remove(preferenceKey).commit();
                    creditsResetState = object("operation", "", "status", outcome, "error", cleared ? "" : "Codex confirmed the result, but this device could not clear the saved receipt.", "canRetry", !cleared, "awaitingRefresh", true);
                    endControl(outcome);
                }
                metadataRefresh.need("usage", true); pumpRefresh(); publish();
            });
        } catch (Exception failure) {
            creditsResetState = object("operation", "", "status", "Reset result unconfirmed. Retry reuses the same request.", "error", "Codex could not receive the reset request.", "canRetry", true);
            endControlError("Codex could not receive the reset request. Retry will reuse its saved receipt.");
        }
    }

    private void integrationAction(Intent action) {
        try {
            if (!provider.equals(action.getStringExtra(EXTRA_PROVIDER)) || !projectName.equals(action.getStringExtra(EXTRA_PROJECT)))
                throw new IllegalStateException("Connect the selected Codex project before managing its integrations.");
            if (!isCodex() || process == null || !accountVerified || !AgentProtocol.chatgptAccount(account))
                throw new IllegalStateException("Connect your ChatGPT account in Codex before managing integrations.");
            String operation = action.getStringExtra(EXTRA_OPERATION);
            if (CodexIntegrations.mutates(operation) && (busy || sessionOpening || recoveringSession || installing || authenticating || sessions.changing() || !controlOperation.isEmpty()))
                throw new IllegalStateException("Finish the current task or connection before changing integrations.");
            String encoded = action.getStringExtra(EXTRA_PAYLOAD);
            if (encoded != null && encoded.length() > 70000) throw new IllegalArgumentException("The integration form is too large.");
            JSONObject payload = encoded == null || encoded.isEmpty() ? new JSONObject() : new JSONObject(encoded);
            integrations.dispatch(operation, payload);
        } catch (Exception failure) { integrations.reject(failure.getMessage()); }
    }

    private void install(String id) throws Exception {
        if (!AgentCatalog.isValid(id)) throw new IllegalArgumentException("Choose a supported agent.");
        if (!ContainerRuntime.isWorkspaceInstalled(this)) throw new IllegalStateException("Set up the Ubuntu workspace first.");
        String guard = protectionReason(); if (!guard.isEmpty()) throw new IllegalStateException(guard);
        if (!provider.equals(id)) { persist(); provider = id; clearConnectionMetadata(); messages.clear(); restoreConversation(); }
        hasError = false; installing = true; busy = true; status = "Installing " + AgentCatalog.name(id); publish();
        lease();
        installationThread = Thread.currentThread();
        try { AgentInstaller.install(this, id, detail -> { status = clean(detail, 220); publish(); }); }
        finally { installationThread = null; installing = false; busy = false; Thread.interrupted(); releaseLease(); publish(); }
        if (!installationStopReason.isEmpty()) throw new IllegalStateException(installationStopReason);
        status = AgentCatalog.name(id) + " installed"; publish();
    }

    private void connect(String id, String name, boolean allowFull) throws Exception {
        connect(id, name, allowFull, false);
    }

    private void connect(String id, String name, boolean allowFull, boolean automatic) throws Exception {
        requireProjectIdle();
        if (!AgentCatalog.isValid(id)) throw new IllegalArgumentException("Choose a supported agent.");
        String guard = protectionReason(); if (!guard.isEmpty()) throw new IllegalStateException(guard);
        String safeName = AgentProtocol.project(name);
        if (process != null && (connected || !hasError) && provider.equals(id) && projectName.equals(safeName)
                && fullAccess == allowFull) { publish(); return; }
        String blocked = switchBlockedReason();
        if (!blocked.isEmpty()) throw new IllegalStateException(blocked);
        persistSettings();
        disconnect(false);
        foreground("Connecting your local agent");
        provider = id; projectName = safeName; fullAccess = allowFull;
        clearConnectionMetadata();
        restoreSettings();
        messages.clear(); restoreConversation(); automaticConnection = automatic;
        hasError = false; connecting = true;
        status = "Preparing " + AgentCatalog.name(id) + " connection"; publish();
        if (!ContainerRuntime.isWorkspaceInstalled(this)) throw new IllegalStateException("Set up the Ubuntu workspace first.");
        String block = AgentInstaller.unavailableReason(id);
        if (!block.isEmpty()) throw new IllegalStateException(block);
        if (!AgentInstaller.isInstalled(this, id)) {
            if (automatic) throw new IllegalStateException("Connect to finish engine setup.");
            install(id);
        }
        File root = ContainerRuntime.workspaceRoot(this).getCanonicalFile();
        File target = new File(root, safeName).getCanonicalFile();
        if (!target.getParentFile().equals(root)) throw new IllegalArgumentException("Project path is outside the workspace.");
        if (!target.isDirectory() && !target.mkdirs()) throw new IllegalStateException("Cannot create the project folder.");
        cwd = "/home/coder/Projects/" + safeName;
        integrations.reset(cwd, "");
        sessions.reset(cwd, "", "");
        authUrl = ""; authCode = ""; authenticating = false;
        threadId = ""; turnId = "";
        if (!installationStopReason.isEmpty()) throw new IllegalStateException(installationStopReason);
        status = "Connecting to " + AgentCatalog.name(id); publish(); lease();
        if ("claude".equals(provider)) {
            claude = new ClaudeBridge(new ClaudeBridge.Host() {
                @Override public void send(JSONObject packet) throws Exception { AgentService.this.send(packet); }
                @Override public void sendText(String text) throws Exception {
                    if (stdin == null) throw new IllegalStateException("The Claude process has closed.");
                    stdin.write((text + "\n").getBytes(StandardCharsets.UTF_8)); stdin.flush();
                }
                @Override public void event(String kind, JSONObject data) { claudeEvent(kind, data); }
                @Override public void restart(String command) throws Exception {
                    Process old = process; process = null; stdin = null; ++generation;
                    connected = false; connecting = true; authenticating = false;
                    if (old != null) ProotProcess.stopAndWait(old);
                    launchAgent(command);
                }
                @Override public void onReady() {
                    connected = true; connecting = false; hasError = false; busy = false; authenticating = false; authUrl = ""; authCode = "";
                    status = "Claude Code ready"; rememberConnection(); releaseLease(); restoreAdvertisedModel(); publish();
                }
                @Override public void onError(String text) { modelChanging = false; if (automaticConnection && !connected) forgetReconnect(); busy = false; releaseLease(); fail(text); }
                @Override public void onLoginUrl(String url) { authenticating = true; offerLogin(url); }
            }, cwd);
            claude.allowInteractiveLogin(!automaticConnection);
            claude.setResumeSession(getSharedPreferences("pocketagent_sessions", MODE_PRIVATE).getString(sessionKey(), ""));
            launchAgent(claude.command()); claude.start();
        } else {
            claude = null;
            launchAgent(isCodex() ? CodexControls.nativeCommand() : AgentCatalog.command(id));
            if (isCodex()) initializeCodex(); else initializeAcp();
        }
    }

    private void launchAgent(String command) throws Exception {
        final long ownGeneration = ++generation;
        // A bridge may supply a fixed shell pipeline (for example auth-status JSON framing).
        // Project names are quoted; bridge commands are never assembled from a user's prompt.
        process = ContainerRuntime.startAgentContainer(this, "cd -- " + quote(cwd) + " && " + command);
        stdin = process.getOutputStream();
        final Process ownProcess = process;
        final Thread outputReader = reader(ownProcess.getInputStream(), false, ownGeneration);
        final Thread errorReader = reader(ownProcess.getErrorStream(), true, ownGeneration);
        main.postDelayed(() -> execute(() -> {
            if (generation == ownGeneration && process != null && !connected && !authenticating) {
                recoverableConnectionFailure(AgentCatalog.name(provider) + " took too long to connect.");
            }
        }), 90000L);
        Thread watcher = new Thread(() -> {
            int exit = -1;
            try {
                exit = ownProcess.waitFor();
                // Readers apply bounded backpressure. Drain their final packets before queuing
                // process-exit cleanup; otherwise a large final image could hide the next result.
                outputReader.join(); errorReader.join();
            }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            final int code = exit;
            execute(() -> {
                if (generation != ownGeneration || closing) return;
                if (claude != null) {
                    process = null; stdin = null;
                    try { claude.onProcessExit(code); }
                    catch (Exception error) { connected = false; busy = false; releaseLease(); fail(error.getMessage()); }
                    if (process == null) { connected = false; connecting = false; authenticating = false; clearApprovals(); publish(); stopForeground(STOP_FOREGROUND_REMOVE); }
                    return;
                }
                if (isCodex()) {
                    recoverableConnectionFailure("Codex connection stopped (exit " + code + ").");
                    return;
                }
                process = null; stdin = null; connected = false; connecting = false; busy = false; authenticating = false;
                integrations.closed();
                sessions.closed(); controlOperation = "";
                backgroundTasks.closed();
                sessionOpening = false; recoveringSession = false;
                pending.clear(); clearApprovals(); releaseLease();
                status = "Agent stopped (exit " + code + ")";
                hasError = code != 0;
                if (code != 0) addMessage("system", "The official engine stopped. Check its last message, then reconnect. Android may also stop a heavy process when memory is low.", "");
                publish(); persist(); stopForeground(STOP_FOREGROUND_REMOVE);
            });
        }, "PocketAgent-agent-exit"); watcher.setDaemon(true); watcher.start();
    }

    private void initializeCodex() throws Exception {
        request("initialize", object("clientInfo", object("name", "pocketagent", "title", "PocketAgent", "version", MainActivity.VERSION), "capabilities", object("experimentalApi", true)), (result, error) -> {
            if (error != null) { recoverableConnectionFailure("Codex could not initialize. " + clean(error.optString("message"), 180)); return; }
            notifyAgent("initialized", new JSONObject());
            codexInitialized = true; initialAccountRead = true;
            request("account/read", object("refreshToken", false), (value, failure) -> {
                initialAccountRead = false;
                if (failure != null) {
                    if (AgentReconnect.authenticationRejected(failure)) {
                        forgetReconnect(); disconnect(false); fail("Your ChatGPT sign-in expired. Connect Codex to sign in again.");
                    } else recoverableConnectionFailure("Codex could not verify the saved account. " + clean(failure.optString("message"), 180));
                    return;
                }
                updateAccount(child(value, "account"));
                accountVerified = true;
                metadataRefresh.observed("account", SystemClock.elapsedRealtime(), System.currentTimeMillis());
                if (account.length() == 0) loginCodex();
                else if (!"chatgpt".equalsIgnoreCase(account.optString("type"))) {
                    addMessage("system", "Connect your ChatGPT account to use your eligible subscription. The existing API-key login will not be used for a task.", "");
                    loginCodex();
                } else { metadataRefresh.need("models", true); metadataRefresh.need("usage", true); pumpRefresh(); createSession(); }
                publish();
            });
        });
    }

    private void loginCodex() throws Exception {
        if (automaticConnection) { forgetReconnect(); disconnect(false); fail("Sign in to reconnect your ChatGPT account."); return; }
        if (authenticating) return;
        hasError = false; authenticating = true; status = "Sign in with your ChatGPT account"; publish();
        request("account/login/start", object("type", "chatgpt"), (result, error) -> {
            if (error != null) { authenticating = false; failRpc("ChatGPT sign-in", error); return; }
            offerLogin(result.optString("authUrl", result.optString("verificationUrl", "")));
            authCode = result.optString("userCode", ""); publish();
        });
    }

    private void initializeAcp() throws Exception {
        request("initialize", object("protocolVersion", 1, "clientCapabilities", object("fs", object("readTextFile", false, "writeTextFile", false), "terminal", false, "auth", object("terminal", false)), "clientInfo", object("name", "pocketagent", "title", "PocketAgent", "version", MainActivity.VERSION)), (result, error) -> {
            if (error != null) { failRpc("initialize", error); return; }
            if (result.optInt("protocolVersion", -1) != 1) { fail("This engine needs a newer ACP protocol. Update PocketAgent before connecting."); disconnect(false); return; }
            authMethods = list(result, "authMethods");
            acpCanLoad = child(result, "agentCapabilities").optBoolean("loadSession", false);
            createSession();
        });
    }

    private void loginAcp() throws Exception {
        if (automaticConnection) { forgetReconnect(); disconnect(false); fail("Sign in to reconnect your " + AgentCatalog.name(provider) + " account."); return; }
        if (authenticating) { fail("Sign-in is not complete. Finish the official browser flow and reconnect."); return; }
        String methodId = "";
        for (int i = 0; i < authMethods.length(); i++) {
            JSONObject method = authMethods.optJSONObject(i);
            if (method == null) continue;
            String candidate = method.optString("id", "");
            String lower = candidate.toLowerCase(java.util.Locale.ROOT);
            if (("cursor".equals(provider) && candidate.equals("cursor_login")) ||
                ("antigravity".equals(provider) && candidate.equals("oauth-personal")) ||
                ("claude".equals(provider) && (lower.contains("claudeai") || lower.contains("claude.ai") || lower.contains("subscription")))) {
                if (!"terminal".equals(method.optString("type"))) methodId = candidate;
            }
        }
        if (methodId.isEmpty()) { fail("This engine did not advertise a supported account sign-in method. PocketAgent will not substitute API-key billing."); return; }
        hasError = false; authenticating = true; status = "Sign in with " + AgentCatalog.name(provider); publish();
        request("authenticate", object("methodId", methodId), (result, error) -> {
            authenticating = false;
            if (error != null) { failRpc("Account sign-in", error); return; }
            authUrl = ""; authCode = ""; createSession();
        });
    }

    private void createSession() throws Exception {
        if (claude != null) {
            claude.setResumeSession("");
            claude.restartSession();
            return;
        }
        String saved = getSharedPreferences("pocketagent_sessions", MODE_PRIVATE).getString(sessionKey(), "");
        if (isCodex()) {
            openCodexSession(saved);
        } else {
            boolean resume = acpCanLoad && !saved.isEmpty();
            if (!resume && !messages.isEmpty()) detachConversation(saved, "engine-new-session");
            JSONObject params = object("cwd", cwd, "mcpServers", new JSONArray());
            if (resume) params.put("sessionId", saved);
            request(resume ? "session/load" : "session/new", params, (result, error) -> {
                if (error != null) {
                    if (error.optInt("code") == -32000 || error.optInt("code") == -32001 || error.optString("message").toLowerCase(java.util.Locale.ROOT).contains("auth")) loginAcp();
                    else failRpc("Open chat", error);
                    return;
                }
                threadId = resume ? saved : result.optString("sessionId", "");
                readAcpOptions(result); sessionReady(); restoreAdvertisedModel();
            });
        }
    }

    private void openCodexSession(String saved) throws Exception {
        if (sessionOpening) return;
        sessionOpening = true;
        final long ownGeneration = generation;
        final String scope = sessionKey();
        // Keep the user's current workspace/approval choice during recovery.
        JSONObject params = CodexControls.sessionParams(cwd, fullAccess, saved, selectedModel, selectedMode);
        String method = saved.isEmpty() ? "thread/start" : "thread/resume";
        status = saved.isEmpty() ? "Opening a new Codex conversation" : "Opening your saved Codex conversation";
        publish();
        try {
            request(method, params, (result, error) -> {
                if (generation != ownGeneration || !scope.equals(sessionKey())) return;
                sessionOpening = false;
                if (error != null) {
                    recoveringSession = false;
                    if (!manualSessionResume && sessionRecovery.claim(method, saved, error)) {
                        try {
                            manualSessionResume = false;
                            detachConversation(saved, "missing-session");
                            recoveringSession = true; status = "Previous conversation saved · opening a new chat"; publish();
                            openCodexSession(""); // One fresh start only; never repeat the failed resume.
                        } catch (Exception recoveryError) {
                            recoveringSession = false;
                            fail("Could not open a new chat safely. " + clean(recoveryError.getMessage(), 220));
                        }
                    } else { manualSessionResume = false; sessions.reject("Codex could not open this conversation. " + clean(error.optString("message"), 250)); failRpc("Open chat", error); }
                    return;
                }
                threadId = child(result, "thread").optString("id", "");
                confirmedModel = result.isNull("model") ? "" : result.optString("model", "");
                confirmedEffort = result.isNull("reasoningEffort") ? "" : result.optString("reasoningEffort", "");
                // Resume does not report collaborationMode; require an explicit default preset before
                // claiming a resumed sticky Plan conversation has returned to ordinary execution.
                confirmedPlan = !saved.isEmpty();
                if (manualSessionResume || (!saved.isEmpty() && messages.isEmpty())) {
                    messages.clear();
                    JSONArray restored = CodexControls.threadMessages(child(result, "thread"));
                    for (int i = 0; i < restored.length(); i++) { JSONObject entry = restored.optJSONObject(i); if (entry != null) messages.add(entry); }
                    if (restored.length() == 0) historyNotice = "Earlier messages are unavailable; the agent may retain their context.";
                    manualSessionResume = false;
                }
                reconcileEffort(); sessionReady();
            });
        } catch (Exception error) { sessionOpening = false; recoveringSession = false; manualSessionResume = false; sessions.reject(error.getMessage()); throw error; }
    }

    private String detachedKey() { return sessionKey() + ":detached-archive"; }
    private void detachConversation(String saved, String reason) throws Exception {
        JSONArray previous = new JSONArray(); for (JSONObject message : messages) previous.put(message);
        JSONObject archive = ChatHistory.archive(getFilesDir(), provider, projectName, saved, previous, reason);
        // Archive first. If persistence fails, do not discard the old chat or start a new engine context.
        boolean committed = getSharedPreferences("pocketagent_sessions", MODE_PRIVATE).edit()
                .remove(sessionKey()).putString(detachedKey(), archive.optString("id")).commit();
        if (!committed) {
            getSharedPreferences("pocketagent_sessions", MODE_PRIVATE).edit().putString(sessionKey(), saved).remove(detachedKey()).apply();
            throw new IllegalStateException("Your previous messages are saved in Chat history, but the active chat could not be reset. Free some storage and reconnect.");
        }
        detachedConversation = true; messages.clear();
        taskEpoch.invalidate();
        historyNotice = "missing-session".equals(reason) ? "Previous chat saved. Started a new conversation." : "";
        threadId = ""; turnId = ""; activeAssistantId = ""; confirmedModel = ""; confirmedEffort = "";
        connected = false; busy = false; clearApprovals(); clearTokenUsage();
        backgroundTasks.reset("");
        confirmedPlan = false;
        persist();
    }

    private void sessionReady() throws Exception {
        if (threadId.isEmpty()) { fail("The engine did not return a chat ID. Please update the engine and reconnect."); return; }
        getSharedPreferences("pocketagent_sessions", MODE_PRIVATE).edit().putString(sessionKey(), threadId).apply();
        connected = true; connecting = false; authenticating = false; hasError = false;
        rememberConnection();
        connectionRecovery.ready(SystemClock.elapsedRealtime());
        integrations.thread(threadId);
        if (isCodex()) sessions.resumed(threadId);
        backgroundTasks.reset(isCodex() ? threadId : "");
        voice.reset(isCodex() ? threadId : "");
        sessionOpening = false; recoveringSession = false;
        authUrl = ""; authCode = "";
        busy = false; status = AgentCatalog.name(provider) + " ready"; releaseLease();
        if (!isCodex()) account = object("provider", AgentCatalog.name(provider), "status", "Official agent session ready", "usage", "Use the official account dashboard for plan and remaining limits.");
        publish(); persist(); pumpRefresh();
        if (isCodex()) integrations.autoRefresh(SystemClock.elapsedRealtime());
        if (isCodex() && deferredSessionResume != null) {
            JSONObject wanted = deferredSessionResume; deferredSessionResume = null;
            sessions.dispatch("resume", wanted);
        }
    }

    private void prompt(String text, String skillPath, String appId, String attachments) throws Exception {
        requireProjectIdle();
        if (!connected || process == null || (claude == null && threadId.isEmpty())) throw new IllegalStateException("Connect your agent account first.");
        if (isCodex() && (!accountVerified || !AgentProtocol.chatgptAccount(account))) throw new IllegalStateException("Wait for Codex to verify your ChatGPT account, or reconnect if signed out. Other billing methods will not be used.");
        if (busy) throw new IllegalStateException("Wait for this task or tap Stop before sending another prompt.");
        if (modelChanging || integrations.changing() || sessions.changing() || backgroundTasks.changing() || voice.active() || !controlOperation.isEmpty()) throw new IllegalStateException("Wait for the current agent action or voice conversation to finish before sending a prompt.");
        if (text == null) text = "";
        if (text.length() > 64000) throw new IllegalArgumentException("This prompt is too long. Add a project file and describe the task briefly.");
        JSONArray imagePaths = CodexControls.attachmentPaths(attachments), imageInputs = new JSONArray();
        if (text.trim().isEmpty() && imagePaths.length() == 0) return;
        if (imagePaths.length() > 0) {
            if (!isCodex() || !CodexControls.imageSupported(effortMetadata())) throw new IllegalArgumentException("The selected engine model has not advertised image input. Choose an image-capable model or remove these attachments.");
            File project = new File(ContainerRuntime.workspaceRoot(this), projectName);
            for (int i = 0; i < imagePaths.length(); i++) imageInputs.put(object("type", "localImage", "path", WorkspaceMedia.localImagePath(this, project, imagePaths.getString(i))));
        }
        JSONObject selectedSkill = null;
        if (skillPath != null && !skillPath.isEmpty()) {
            if (!isCodex()) throw new IllegalArgumentException("This skill belongs to Codex. Remove it before switching agents.");
            selectedSkill = integrations.skillInput(skillPath);
        }
        JSONObject selectedApp = null;
        if (appId != null && !appId.isEmpty()) {
            if (!isCodex()) throw new IllegalArgumentException("This app connection belongs to Codex. Remove it before switching agents.");
            selectedApp = integrations.appInput(appId);
        }
        boolean imageOnly = text.trim().isEmpty() && imageInputs.length() > 0;
        JSONObject input = imageOnly ? imageInputs.getJSONObject(0) : object("type", "text", "text", text.trim());
        JSONObject codexParams = null;
        if (isCodex()) {
            reconcileEffort();
            codexParams = CodexControls.applyTurn(CodexEffort.turnParams(threadId, input, selectedModel, effortMetadata(), selectedEffort), selectedMode, collaborationModes, effectiveModel(), fullAccess, confirmedPlan);
            for (int i = imageOnly ? 1 : 0; i < imageInputs.length(); i++) codexParams.getJSONArray("input").put(imageInputs.getJSONObject(i));
            if (selectedSkill != null) codexParams.getJSONArray("input").put(selectedSkill);
            if (selectedApp != null) codexParams.getJSONArray("input").put(selectedApp);
        }
        hasError = false; busy = true; status = AgentCatalog.name(provider) + " is working"; activeAssistantId = ""; lease();
        final long ownTask = taskEpoch.begin();
        addMessage("user", text.trim() + (imagePaths.length() == 0 ? "" : "\n[" + imagePaths.length() + " image attachment(s)]"), "");
        if (imagePaths.length() > 0 && !messages.isEmpty()) messages.get(messages.size() - 1).put("attachments", imagePaths);
        publish(); persist();
        if (claude != null) { claude.prompt(text.trim()); return; }
        if (isCodex()) {
            JSONObject params = codexParams;
            final String turnModel = effectiveModel();
            final String turnEffort = params.optString("effort", "");
            request("turn/start", params, (result, error) -> {
                if (!taskEpoch.current(ownTask)) return; // A completed earlier task cannot overwrite a newer turn.
                if (error != null) { busy = false; releaseLease(); failRpc("Send prompt", error); return; }
                if (!turnModel.isEmpty()) confirmedModel = turnModel;
                if (!turnEffort.isEmpty()) confirmedEffort = turnEffort;
                confirmedPlan = CodexControls.PLAN.equals(child(params, "collaborationMode").optString("mode"));
                turnId = child(result, "turn").optString("id", ""); publish();
            });
        } else request("session/prompt", object("sessionId", threadId, "prompt", array(input)), (result, error) -> {
            if (!taskEpoch.current(ownTask)) return;
            finishMessageActivity("", error == null ? "completed" : "failed");
            busy = false; clearApprovals(); activeAssistantId = ""; releaseLease();
            if (error != null) failRpc("Agent task", error);
            else status = "Task " + result.optString("stopReason", "finished").replace('_', ' ');
            publish(); persist();
        });
    }

    private void cancel() throws Exception {
        if (installing) { status = "Installation is completing its current safe step"; publish(); return; }
        if (process == null) return;
        if (claude != null) {
            if (claude.isAuthenticating()) { disconnect(true); return; }
            claude.cancel(); status = "Stopping task…"; publish(); scheduleCancelFallback(); return;
        }
        if (isCodex() && !threadId.isEmpty() && !turnId.isEmpty()) request("turn/interrupt", object("threadId", threadId, "turnId", turnId), (result, error) -> { if (error != null) failRpc("Stop task", error); });
        else if (!isCodex() && !threadId.isEmpty()) notifyAgent("session/cancel", object("sessionId", threadId));
        else { disconnect(true); return; }
        status = "Stopping task…"; publish();
        scheduleCancelFallback();
    }

    private void scheduleCancelFallback() {
        final long ownGeneration = generation;
        main.postDelayed(() -> execute(() -> {
            if (generation == ownGeneration && busy) { disconnect(false); status = "Stopped the unresponsive agent. Reconnect to continue your saved chat."; publish(); }
        }), 10000);
    }

    private void refresh() throws Exception {
        if (process == null) { publish(); return; }
        if (claude != null) { claude.refresh(); publish(); return; }
        if (isCodex()) { metadataRefresh.all(true); pumpRefresh(); }
        else { status = connected ? "Connected · open official account usage for remaining limits" : status; publish(); }
    }

    private String refreshPauseReason() {
        if (!isCodex() || process == null || !codexInitialized) return "Connect Codex to refresh account data";
        if (initialAccountRead || authenticating || connecting || sessionOpening || recoveringSession) return "Waiting for the account connection";
        if (busy) return "Live usage notifications remain active; polling resumes after this task";
        if (voice.active()) return "Live usage notifications remain active during voice";
        if (installing || integrations.changing() || sessions.changing() || backgroundTasks.changing() || !controlOperation.isEmpty() || WorkspaceTools.isProjectOperationBusy()) return "Waiting for the current workspace action";
        if (integrations.snapshot().optBoolean("oauthPending")) return "Waiting for account authorization";
        return "";
    }

    private void pumpRefresh() {
        String pause = refreshPauseReason(); metadataRefresh.pause(pause);
        long now = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
        if (pause.isEmpty() && connected && accountVerified && AgentProtocol.chatgptAccount(account) && metadataRefresh.active(now)) integrations.autoRefresh(now);
        for (int n = 0; n < 3; n++) {
            CodexRefresh.Ticket ticket = metadataRefresh.next(now, wall, pause.isEmpty());
            if (ticket == null) break;
            String section = ticket.section;
            if (!"account".equals(section) && (!accountVerified || !AgentProtocol.chatgptAccount(account))) {
                metadataRefresh.failure(ticket, now, "Sign in with your ChatGPT account to read this data."); continue;
            }
            final long ownAccount = accountEpoch;
            String method = "account".equals(section) ? "account/read" : "models".equals(section) ? "model/list" : "account/rateLimits/read";
            JSONObject params = "account".equals(section) ? object("refreshToken", false) : "models".equals(section) ? object("limit", 100) : new JSONObject();
            try {
                request(method, params, (result, error) -> {
                    if (ownAccount != accountEpoch || !metadataRefresh.current(ticket)) { metadataRefresh.discard(ticket); publish(); return; }
                    long received = SystemClock.elapsedRealtime(), receivedWall = System.currentTimeMillis();
                    if (error != null) {
                        if ("account".equals(section) && AgentReconnect.authenticationRejected(error)) {
                            forgetReconnect(); disconnect(false); fail("Your ChatGPT sign-in expired. Connect Codex to sign in again."); return;
                        }
                        metadataRefresh.failure(ticket, received, error.optString("message", "Codex did not provide this data."));
                        publish(); return;
                    }
                    if ("account".equals(section)) {
                        updateAccount(child(result, "account")); accountVerified = true;
                        if (!AgentProtocol.chatgptAccount(account)) {
                            forgetReconnect(); disconnect(false); fail("ChatGPT is signed out. Connect Codex to sign in again."); return;
                        }
                        if (ownAccount != accountEpoch) metadataRefresh.observed("account", received, receivedWall);
                        else metadataRefresh.success(ticket, received, receivedWall);
                    } else if ("models".equals(section)) {
                        models = CodexControls.models(list(result, "data")); restoreAdvertisedModel(); reconcileEffort();
                        metadataRefresh.success(ticket, received, receivedWall); refreshModes();
                    } else {
                        updateRateLimits(result, false); metadataRefresh.success(ticket, received, receivedWall);
                    }
                    publish(); pumpRefresh();
                });
            } catch (Exception failure) { metadataRefresh.failure(ticket, now, failure.getMessage()); }
            publish();
        }
    }

    private void refreshModes() {
        long now = SystemClock.elapsedRealtime();
        if (modeLoading || (lastModeAttempt > 0 && now - lastModeAttempt < 60000L)) return;
        modeLoading = true; lastModeAttempt = now; final long ownAccount = accountEpoch;
        try {
            request("collaborationMode/list", new JSONObject(), (result, error) -> {
                if (ownAccount != accountEpoch) return;
                modeLoading = false;
                if (error == null) { collaborationModes = list(result, "data"); modeKnown = true; modeError = ""; }
                else modeError = "Official mode refresh unavailable: " + clean(error.optString("message"), 220);
                publish();
            });
        } catch (Exception failure) {
            modeLoading = false; modeError = "Official mode refresh could not be sent. Reconnect Codex to retry.";
            publish();
        }
    }

    private void selectModel(String id) throws Exception {
        requireProjectIdle();
        if (!connected || process == null) throw new IllegalStateException("Connect your agent account before choosing a model.");
        if (busy || modelChanging || sessionOpening || integrations.changing() || sessions.changing() || voice.active() || !controlOperation.isEmpty()) throw new IllegalStateException("Finish or stop the current task before switching models.");
        boolean available = false;
        for (int i = 0; i < models.length(); i++) if (id != null && id.equals(childAt(models, i).optString("id"))) available = true;
        if (!available) throw new IllegalArgumentException("Choose a model advertised by the connected engine.");
        hasError = false;
        if (claude != null) { modelChanging = true; try { claude.selectModel(id); } catch (Exception failure) { modelChanging = false; throw failure; } status = "Switching model…"; publish(); return; }
        if (isCodex()) { selectedModel = id; reconcileEffort(); persistSettings(); status = "Model selected · applies to your next prompt"; publish(); return; }
        String configId = "";
        for (int i = 0; i < configOptions.length(); i++) {
            JSONObject option = childAt(configOptions, i);
            if ("model".equals(option.optString("category"))) configId = option.optString("id");
        }
        JSONObject params = configId.isEmpty() ? object("sessionId", threadId, "modelId", id) : object("sessionId", threadId, "configId", configId, "value", id);
        modelChanging = true;
        try {
            request(configId.isEmpty() ? "session/set_model" : "session/set_config_option", params, (result, error) -> {
                modelChanging = false;
                if (error != null) { failRpc("Select model", error); return; }
                selectedModel = id; readAcpOptions(result); persistSettings(); status = "Model selected"; publish();
            });
        } catch (Exception failure) { modelChanging = false; throw failure; }
        publish();
    }

    private void restoreAdvertisedModel() {
        if (pendingRestoredModel.isEmpty() || models.length() == 0 || !connected) return;
        String saved = pendingRestoredModel; pendingRestoredModel = "";
        boolean found = false;
        for (int i = 0; i < models.length(); i++) if (saved.equals(childAt(models, i).optString("id"))) found = true;
        if (!found || saved.equals(selectedModel)) { persistSettings(); return; }
        try { selectModel(saved); }
        catch (Exception unavailable) { controlError = "The saved model could not be restored. Choose an available model."; }
    }

    private String effectiveModel() { return isCodex() ? CodexEffort.effectiveModel(models, selectedModel, confirmedModel) : selectedModel; }
    private JSONObject effortMetadata() { return isCodex() ? CodexEffort.model(models, effectiveModel()) : new JSONObject(); }
    private String effortKey() { return sessionKey() + ":" + effectiveModel(); }
    private void reconcileEffort() {
        if (!isCodex()) { selectedEffort = CodexEffort.AUTO; effortModel = ""; return; }
        if (!selectedModel.isEmpty() && CodexEffort.model(models, selectedModel).length() == 0) selectedModel = "";
        String model = effectiveModel();
        if (!effortMetadata().has("supportedReasoningEfforts")) {
            selectedEffort = CodexEffort.AUTO; effortModel = ""; return;
        }
        if (!model.equals(effortModel)) {
            effortModel = model;
            selectedEffort = getSharedPreferences("pocketagent_effort", MODE_PRIVATE).getString(effortKey(), CodexEffort.AUTO);
        }
        selectedEffort = CodexEffort.reconcile(effortMetadata(), selectedEffort);
    }

    private void selectEffort(String id) {
        requireProjectIdle();
        if (!isCodex()) throw new IllegalStateException("Reasoning controls are available for connected Codex models.");
        if (!connected || process == null) throw new IllegalStateException("Connect Codex before choosing a reasoning level.");
        if (busy || sessionOpening || integrations.changing() || sessions.changing() || !controlOperation.isEmpty()) throw new IllegalStateException("Finish or stop the current task before changing reasoning effort.");
        JSONObject metadata = effortMetadata();
        if (CodexEffort.AUTO.equals(id)) {
            if (!CodexEffort.AUTO.equals(selectedEffort) && CodexEffort.defaultEffort(metadata).isEmpty())
                throw new IllegalStateException("This model did not advertise a default reasoning level. Choose an available level; Auto cannot safely reset this chat yet.");
        } else if (!CodexEffort.supported(metadata, id)) {
            throw new IllegalArgumentException("Choose a reasoning level advertised by this Codex model.");
        }
        selectedEffort = id; hasError = false;
        getSharedPreferences("pocketagent_effort", MODE_PRIVATE).edit().putString(effortKey(), id).apply();
        status = "Reasoning selected · applies to your next prompt"; publish();
    }

    private void readAcpOptions(JSONObject result) {
        JSONObject modelState = child(result, "models");
        if (modelState.has("availableModels")) models = models(list(modelState, "availableModels"));
        if (modelState.has("currentModelId")) selectedModel = modelState.optString("currentModelId");
        if (result.has("configOptions")) configOptions = list(result, "configOptions");
        for (int i = 0; i < configOptions.length(); i++) {
            JSONObject option = childAt(configOptions, i);
            if (!"model".equals(option.optString("category"))) continue;
            JSONArray normalized = new JSONArray();
            addConfigModels(list(option, "options"), normalized);
            if (normalized.length() > 0) models = normalized;
            selectedModel = option.optString("currentValue", selectedModel);
        }
    }

    private void addConfigModels(JSONArray source, JSONArray target) {
        for (int i = 0; i < source.length() && target.length() < 100; i++) {
            JSONObject option = childAt(source, i);
            if (option.has("options")) addConfigModels(list(option, "options"), target);
            else if (option.has("value")) target.put(object("id", option.optString("value"), "name", option.optString("name", option.optString("value"))));
        }
    }

    private Thread reader(InputStream stream, boolean stderr, long ownGeneration) {
        final Process readerProcess = process;
        Thread reader = new Thread(() -> {
            java.util.function.BooleanSupplier active = () -> !destroyed && process == readerProcess;
            final boolean[] warned = new boolean[2];
            File frames = new File(getCacheDir(), "agent-frames");
            try (AgentFrameReader input = stderr
                    ? new AgentFrameReader(stream, frames, 256 * 1024, 64 * 1024)
                    : new AgentFrameReader(stream, frames)) {
                AgentFrameReader.Frame frame;
                while (active.getAsBoolean() && (frame = input.next(active)) != null) {
                    if (!AgentFrameReader.deliver(frame, serial, active, packet -> {
                        if (generation != ownGeneration) return;
                        if (packet.oversized) {
                            if (!warned[0]) {
                                warned[0] = true;
                                if (!stderr) {
                                    addMessage("system", "This output was too large to display. Ask the agent to save it as a project file. Your connection is still active.", "");
                                    publish();
                                }
                            }
                            return;
                        }
                        try {
                            String value = packet.readUtf8();
                            if (stderr) diagnostic(value); else receive(value);
                        } catch (java.nio.charset.CharacterCodingException corrupt) {
                            if (!warned[1] && !stderr) {
                                warned[1] = true;
                                addMessage("system", "One reply contained unreadable text. The connection is still active.", "");
                                publish();
                            }
                        } catch (Exception error) {
                            // Never copy raw packet content, base64 data or credentials into diagnostics.
                            fail("An agent reply could not be displayed. Your connection is still active.");
                        }
                    })) break;
                }
            } catch (Exception error) {
                execute(() -> {
                    if (generation == ownGeneration && !closing)
                        recoverableConnectionFailure("The connection to the local agent was interrupted.");
                });
            }
        }, stderr ? "PocketAgent-agent-log" : "PocketAgent-agent-rpc");
        reader.setDaemon(true); reader.start(); return reader;
    }

    private void receive(String line) throws Exception {
        if (line.trim().isEmpty()) return;
        JSONObject packet;
        try { packet = new JSONObject(line); } catch (Exception notJson) { diagnostic(line); return; }
        if (claude != null) { claude.receive(packet); return; }
        if (packet.has("method")) {
            String method = packet.optString("method"); JSONObject params = child(packet, "params");
            if (isCodex() && "mcpServer/elicitation/request".equals(method)) {
                String remoteThread = params.isNull("threadId") ? "" : params.optString("threadId");
                String remoteTurn = params.isNull("turnId") ? "" : params.optString("turnId");
                if (threadId.isEmpty() || !threadId.equals(remoteThread) || (!remoteTurn.isEmpty() && !turnId.equals(remoteTurn))) {
                    if (packet.has("id")) send(response(false, packet.get("id"), object("code", -32602, "message", "This MCP request belongs to an inactive conversation or turn."), true));
                    return;
                }
            }
            if (!AgentProtocol.matchesScope(isCodex(), threadId, turnId, busy || (isCodex() && voice.active()), method, params, packet.has("id"))) {
                if (packet.has("id")) send(response(!isCodex(), packet.get("id"), object("code", -32602, "message", "The request belongs to an inactive PocketAgent conversation or turn."), true));
                return;
            }
            if (packet.has("id")) serverRequest(packet.get("id"), method, params);
            else event(method, params);
            return;
        }
        String id = String.valueOf(packet.opt("id"));
        Pending waiting = pending.remove(id);
        if (waiting != null) waiting.reply.receive(child(packet, "result"), packet.optJSONObject("error"));
    }

    private void event(String method, JSONObject params) throws Exception {
        if (isCodex() && voice.event(method, params)) return;
        if (isCodex() && integrations.event(method, params)) return;
        if ("account/login/completed".equals(method)) {
            authenticating = false;
            if (params.optBoolean("success")) { hasError = false; authUrl = ""; authCode = ""; refresh(); if (!connected && !sessionOpening) createSession(); }
            else fail(params.optString("error", "Sign-in did not complete."));
        } else if ("account/updated".equals(method)) {
            voice.accountChanged();
            // This notification carries auth mode/plan, not the full account/read identity.
            accountEpoch++; accountVerified = false; metadataRefresh.reset();
            creditsResetState = new JSONObject(); if ("credits_reset".equals(controlOperation)) controlOperation = "";
            rateLimits = new JSONObject(); usageUpdatedAt = 0; models = new JSONArray(); integrations.reset(cwd, threadId);
            modeLoading = false; lastModeAttempt = 0;
            sessions.reset(cwd, "", threadId); sessionAccountScope = "";
            if (!"chatgpt".equals(params.optString("authMode"))) {
                account = new JSONObject();
                if (params.has("authMode")) forgetReconnect();
            }
            if (!"logout".equals(controlOperation)) { metadataRefresh.all(true); pumpRefresh(); }
            publish();
        }
        else if ("account/rateLimits/updated".equals(method)) {
            if (accountVerified && AgentProtocol.chatgptAccount(account) && !"logout".equals(controlOperation)) {
                updateRateLimits(params, true);
                metadataRefresh.observed("usage", SystemClock.elapsedRealtime(), System.currentTimeMillis()); publish();
            }
        }
        else if ("model/rerouted".equals(method) || "model/verification".equals(method)) { metadataRefresh.need("models", false); pumpRefresh(); }
        else if ("thread/tokenUsage/updated".equals(method)) { putStateUsage(params); }
        else if ("turn/started".equals(method)) { turnId = child(params, "turn").optString("id", turnId); busy = true; publish(); }
        else if ("turn/completed".equals(method)) {
            taskEpoch.invalidate();
            JSONObject turn = child(params, "turn");
            finishMessageActivity(turn.optString("id", turnId), turn.optString("status", "completed"));
            busy = false; turnId = ""; activeAssistantId = ""; clearApprovals(); releaseLease();
            String finishedId = turn.optString("id", "");
            if ("completed".equals(turn.optString("status")) && turn.optJSONObject("error") == null)
                AgentNotifications.completed(this, provider, projectName, threadId, finishedId);
            else if ("failed".equals(turn.optString("status")) || turn.optJSONObject("error") != null)
                AgentNotifications.error(this, provider, projectName, threadId, finishedId);
            status = "Task " + turn.optString("status", "completed");
            if ("compact".equals(controlOperation) || "review".equals(controlOperation)) {
                controlOperation = ""; controlStatus = status;
                controlError = turn.optJSONObject("error") == null ? "" : clean(child(turn, "error").optString("message", "Task failed"), 500);
            }
            if (turn.optJSONObject("error") != null) addMessage("system", child(turn, "error").optString("message", "Task failed"), "");
            metadataRefresh.need("usage", false);
            metadataRefresh.need("account", false);
            publish(); persist();
            pumpRefresh();
        } else if ("item/agentMessage/delta".equals(method)) {
            appendAssistant(params.optString("itemId", "reply"), params.optString("delta", ""), false);
        } else if ("item/reasoning/summaryTextDelta".equals(method) || "item/reasoning/summaryPartAdded".equals(method)) {
            String key = "tool-" + params.optString("itemId", "");
            if (!params.optString("itemId", "").isEmpty()) {
                JSONObject prior = findMessage(key);
                JSONObject activity = AgentActivity.summaryDelta(prior == null ? null : prior.optJSONObject("activity"), params);
                if (activity != null) {
                    upsert(key, "tool", activity.optString("summary"));
                    setMessageActivity(key, activity, false); publish();
                }
            }
        } else if ("item/commandExecution/outputDelta".equals(method) || "item/fileChange/outputDelta".equals(method)
                || "item/mcpToolCall/progress".equals(method)) {
            String key = "tool-" + params.optString("itemId", "");
            if (!params.optString("itemId", "").isEmpty()) {
                JSONObject prior = findMessage(key);
                boolean mcp = "item/mcpToolCall/progress".equals(method), files = "item/fileChange/outputDelta".equals(method);
                JSONObject activity = AgentActivity.progress(prior == null ? null : prior.optJSONObject("activity"),
                    mcp ? "mcp" : files ? "files" : "command", mcp ? "Using a tool" : files ? "Updating files" : "Running command",
                    mcp ? "\n" + params.optString("message") : params.optString("delta"));
                upsert(key, "tool", activity.optString("details")); setMessageActivity(key, activity, false); publish();
            }
        } else if ("item/completed".equals(method) || "item/started".equals(method)) {
            JSONObject item = child(params, "item"); String type = item.optString("type"), id = item.optString("id");
            if ("agentMessage".equals(type) && item.has("text")) appendAssistant(id, item.optString("text"), "item/completed".equals(method));
            else if ("commandExecution".equals(type)) upsert("tool-" + id, "tool", item.optString("command") + "\n" + item.optString("status") + (item.has("aggregatedOutput") ? "\n" + clean(item.optString("aggregatedOutput"), 12000) : ""));
            else if ("fileChange".equals(type)) upsert("tool-" + id, "tool", "File changes · " + item.optString("status") + "\n" + clean(list(item, "changes").toString(2), 16000));
            else if ("plan".equals(type)) upsert("plan-" + id, "plan", item.optString("text"));
            else if ("contextCompaction".equals(type)) upsert("compact-" + id, "system", "item/completed".equals(method) ? "Codex compacted the conversation context." : "Codex is compacting the conversation context…");
            else if ("enteredReviewMode".equals(type)) upsert("review-" + id, "system", "Codex review started.");
            else if ("exitedReviewMode".equals(type)) upsert("review-" + id, "assistant", clean(item.optString("review", "Codex review completed."), 32000));
            else if ("imageGeneration".equals(type)) {
                String key = "image-" + id;
                upsert(key, "assistant", "Image generation · " + clean(item.optString("status"), 100));
                String path = item.isNull("savedPath") ? "" : item.optString("savedPath");
                if ("item/completed".equals(method) && !path.isEmpty() && path.startsWith(cwd + "/")) {
                    try {
                        String relative = path.substring(cwd.length() + 1);
                        CodexControls.attachmentPaths(array(relative).toString());
                        File project = new File(ContainerRuntime.workspaceRoot(this), projectName);
                        WorkspaceMedia.localImagePath(this, project, relative);
                        JSONObject message = findMessage(key);
                        if (message != null) message.put("attachments", array(relative));
                    } catch (Exception unavailable) { upsert(key, "assistant", "Image generation finished, but the engine did not expose a readable image inside this project."); }
                } else if ("item/completed".equals(method)) {
                    upsert(key, "assistant", "Image generation · " + clean(item.optString("status"), 100) + ". The engine did not expose an image file inside this project.");
                }
                if ("item/completed".equals(method) && list(findMessage(key), "attachments").length() == 0) {
                    JSONArray output = new JSONArray();
                    cacheInlineOutput(output, "", item.optString("result", ""), "Generated image");
                    if (output.length() > 0) { findMessage(key).put("media", output); upsert(key, "assistant", "Image"); }
                }
                // Only expiring private-cache references reach snapshots; encoded bytes never do.
            }
            else if ("imageView".equals(type)) {
                upsert("tool-" + id, "tool", "Image");
                if ("item/completed".equals(method)) {
                    String path = item.optString("path", "");
                    if (path.startsWith(cwd + "/")) try {
                        String relative = path.substring(cwd.length() + 1);
                        WorkspaceMedia.localImagePath(this, new File(ContainerRuntime.workspaceRoot(this), projectName), relative);
                        findMessage("tool-" + id).put("attachments", array(relative));
                    } catch (Exception unavailable) { /* No project-readable image was supplied. */ }
                }
            }
            else if (type.endsWith("ToolCall")) {
                String key = "tool-" + id;
                String output = "item/completed".equals(method) ? AgentToolMedia.text(item) : "";
                upsert(key, "tool", type + " · " + item.optString("tool", item.optString("server", "")) + " · " + item.optString("status")
                        + (output.isEmpty() ? "" : "\n" + output));
                if ("item/completed".equals(method)) {
                    JSONArray media = AgentToolMedia.remote(item);
                    cacheToolImages(item, media);
                    findMessage(key).put("media", media);
                }
            }
            JSONObject activity = AgentActivity.codex(item, "item/completed".equals(method));
            if (activity != null && !id.isEmpty()) {
                String key = "tool-" + id;
                JSONObject existing = findMessage(key);
                // A completed item may omit a summary that was already streamed publicly.
                if ("reasoning".equals(type) && activity.optString("summary").isEmpty() && existing != null) {
                    JSONObject previous = existing.optJSONObject("activity");
                    if (previous != null) {
                        activity.put("summary", previous.optString("summary"));
                        if (previous.has("summaryParts")) activity.put("summaryParts", previous.opt("summaryParts"));
                    }
                }
                if (existing == null || "reasoning".equals(type) || "webSearch".equals(type))
                    upsert(key, "tool", "reasoning".equals(type) ? activity.optString("summary") : activity.optString("details"));
                setMessageActivity(key, activity, "item/completed".equals(method));
            }
            publish();
        } else if ("turn/plan/updated".equals(method)) { upsert("plan-" + params.optString("turnId"), "plan", clean(list(params, "plan").toString(2), 16000)); publish(); }
        else if ("turn/diff/updated".equals(method)) { upsert("diff-" + params.optString("turnId"), "tool", "Changes\n" + clean(params.optString("diff"), 20000)); publish(); }
        else if ("serverRequest/resolved".equals(method)) { removeApprovalWire(String.valueOf(params.opt("requestId"))); publish(); }
        else if ("error".equals(method)) fail(child(params, "error").optString("message", "The agent reported an error."));
        else if ("session/update".equals(method)) acpUpdate(child(params, "update"));
        else if (method.startsWith("cursor/")) { addMessage("tool", method.substring(7).replace('_', ' ') + "\n" + clean(params.toString(2), 10000), ""); publish(); }
    }

    /** Executed on the serialized service worker. Never decodes media on the UI or copies bytes to history. */
    private void cacheInlineOutput(JSONArray media, String mime, String encoded, String name) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > WorkspaceMedia.MAX_INLINE_ENCODED_CHARS || media.length() >= 15) return;
        try {
            android.net.Uri cached = WorkspaceMedia.cacheInlineImage(this, mime, encoded);
            media.put(object("cachedUri", cached.toString(), "name", name));
        } catch (Exception unavailable) { /* Keep the actual text/reference when no safe preview can be cached. */ }
    }
    private void cacheToolImages(JSONObject item, JSONArray media) {
        String kind = item.optString("type");
        JSONArray blocks = "mcpToolCall".equals(kind) ? list(child(item, "result"), "content")
                : "dynamicToolCall".equals(kind) ? list(item, "contentItems") : new JSONArray();
        int count = 0;
        for (int i = 0; i < blocks.length() && i < 100 && count < 3; i++) {
            JSONObject block = childAt(blocks, i);
            if ("mcpToolCall".equals(kind) && "image".equals(block.optString("type"))) {
                cacheInlineOutput(media, block.optString("mimeType"), block.optString("data"), "Image"); count++;
            } else if ("dynamicToolCall".equals(kind) && "inputImage".equals(block.optString("type"))) {
                String data = block.optString("imageUrl"); int boundary = data.indexOf(";base64,");
                if (data.startsWith("data:image/") && boundary > 5 && boundary < 80) {
                    cacheInlineOutput(media, data.substring(5, boundary), data.substring(boundary + 8), "Image"); count++;
                }
            }
        }
    }

    private void acpUpdate(JSONObject update) throws Exception {
        String type = update.optString("sessionUpdate");
        if ("agent_message_chunk".equals(type)) {
            JSONObject content = child(update, "content");
            if ("text".equals(content.optString("type"))) {
                if (activeAssistantId.isEmpty()) activeAssistantId = "acp-" + (++messageSequence);
                appendAssistant(activeAssistantId, content.optString("text"), false);
            }
        } else if ("tool_call".equals(type) || "tool_call_update".equals(type)) {
            activeAssistantId = "";
            String key = "tool-" + update.optString("toolCallId");
            String text = update.optString("title", "Agent tool") + " · " + update.optString("status", "working");
            JSONArray content = list(update, "content");
            for (int i = 0; i < content.length(); i++) {
                JSONObject item = childAt(content, i);
                if ("content".equals(item.optString("type"))) text += "\n" + child(item, "content").optString("text");
                else if ("diff".equals(item.optString("type"))) text += "\n" + item.optString("path") + "\n" + item.optString("newText");
            }
            upsert(key, "tool", clean(text, 14000));
            JSONObject activity = AgentActivity.acp(update, findMessage(key).optJSONObject("activity"));
            setMessageActivity(key, activity, "completed".equals(activity.optString("status")) || "failed".equals(activity.optString("status")));
            publish();
        } else if ("plan".equals(type)) { upsert("acp-plan", "plan", clean(list(update, "entries").toString(2), 16000)); publish(); }
        else if ("config_option_update".equals(type) || "available_commands_update".equals(type)) { readAcpOptions(update); publish(); }
        else if ("usage_update".equals(type)) { rateLimits = object("sessionUsage", update, "note", "Session usage is not your remaining subscription quota."); publish(); }
    }

    private void putStateUsage(JSONObject usage) throws Exception {
        tokenUsage = child(usage, "tokenUsage"); tokenUsageUpdatedAt = System.currentTimeMillis();
        rateLimits.put("threadUsage", usage); publish();
    }

    private void clearTokenUsage() {
        tokenUsage = new JSONObject(); tokenUsageUpdatedAt = 0;
        rateLimits.remove("threadUsage"); rateLimits.remove("sessionUsage");
    }

    private void clearConnectionMetadata() {
        taskEpoch.invalidate();
        accountEpoch++; accountVerified = false; codexInitialized = false; initialAccountRead = false;
        modeLoading = false; lastModeAttempt = 0; metadataRefresh.reset();
        creditsResetState = new JSONObject();
        account = new JSONObject(); models = new JSONArray(); rateLimits = new JSONObject(); configOptions = new JSONArray();
        authUrl = ""; authCode = ""; authenticating = false;
        selectedModel = ""; pendingRestoredModel = ""; modelChanging = false; selectedEffort = CodexEffort.AUTO; effortModel = ""; confirmedModel = ""; confirmedEffort = "";
        sessionOpening = false; recoveringSession = false; sessionRecovery.reset(); runtimeDiagnostics.clear();
        integrations.reset("", "");
        sessions.reset("", "", ""); sessionAccountScope = "";
        backgroundTasks.reset("");
        voice.closed();
        collaborationModes = new JSONArray(); modeKnown = false; modeError = ""; selectedMode = CodexControls.ASK; confirmedPlan = false;
        controlOperation = ""; controlError = ""; controlStatus = ""; environment = new JSONObject(); manualSessionResume = false;
        detachedConversation = false; historyNotice = ""; automaticConnection = false;
        usageUpdatedAt = 0; clearTokenUsage();
    }

    private void updateAccount(JSONObject value) {
        account = value;
        String scope = AgentProtocol.chatgptAccount(account) ? account.optString("email", "") + "|" + account.optString("planType", "") : "";
        if (scope.equals("|")) scope = "chatgpt-connected";
        if (!scope.equals(sessionAccountScope)) {
            if (!sessionAccountScope.isEmpty() || voice.active()) voice.accountChanged();
            accountEpoch++; metadataRefresh.reset();
            creditsResetState = new JSONObject();
            if ("credits_reset".equals(controlOperation)) controlOperation = "";
            rateLimits = new JSONObject(); usageUpdatedAt = 0; models = new JSONArray();
            modeLoading = false; lastModeAttempt = 0; collaborationModes = new JSONArray(); modeKnown = false;
            sessionAccountScope = scope; sessions.reset(cwd, scope, threadId);
            environment = new JSONObject(); integrations.reset(cwd, threadId);
            if (!scope.isEmpty()) { metadataRefresh.need("models", true); metadataRefresh.need("usage", true); }
        }
    }

    private String accountScopeToken() {
        if (!accountVerified || sessionAccountScope.isEmpty()) return "";
        return permissionEpoch + ":" + accountEpoch + ":" + CodexCredits.scopeKey(sessionAccountScope, rateLimits.isNull("accountId") ? "" : rateLimits.optString("accountId"));
    }

    private void updateRateLimits(JSONObject value, boolean rolling) throws Exception {
        JSONObject threadUsage = rateLimits.optJSONObject("threadUsage"), sessionUsage = rateLimits.optJSONObject("sessionUsage");
        rateLimits = rolling ? RateLimitState.rolling(rateLimits, value) : RateLimitState.read(rateLimits, value);
        rateLimits.remove("unavailable");
        if (threadUsage != null) rateLimits.put("threadUsage", threadUsage);
        if (sessionUsage != null) rateLimits.put("sessionUsage", sessionUsage);
        usageUpdatedAt = System.currentTimeMillis(); publish();
        metadataRefresh.resetDeadline(rateLimits);
        if (!rolling) {
            creditsResetState.put("awaitingRefresh", false);
            if (!sessionAccountScope.isEmpty()) {
                String key = CodexCredits.scopeKey(sessionAccountScope, rateLimits.isNull("accountId") ? "" : rateLimits.optString("accountId"));
                if (getSharedPreferences("pocketagent_credit_resets", MODE_PRIVATE).contains(key)) creditsResetState.put("canRetry", true);
            }
        }
    }

    private void claudeEvent(String kind, JSONObject data) {
        try {
            if ("message".equals(kind)) {
                String id = data.optString("id", "claude-message-" + (++messageSequence));
                String role = data.optString("role", "assistant");
                if ("assistant".equals(role)) appendAssistant(id, data.optString("text"), !data.optBoolean("append", false));
                else { upsert(id, role, data.optString("text")); publish(); }
            } else if ("permission".equals(kind)) {
                String id = data.optString("id");
                if (id.isEmpty() || list(data, "options").length() == 0) { fail("Claude supplied an incomplete permission prompt. No permission was granted."); return; }
                removeApprovalWire(id);
                Approval approval = new Approval(); approval.wireId = id; approval.key = nextPermissionKey(); approval.method = "claude";
                approval.card = new JSONObject(data.toString()); approval.card.put("id", approval.key);
                approvals.put(approval.key, approval); notifyApproval(approval); status = "Your approval is needed"; publish();
            } else if ("permission_resolved".equals(kind) || "permissionResolved".equals(kind)) { removeApprovalWire(data.optString("id")); publish(); }
            else if ("account".equals(kind)) { account = data; publish(); }
            else if ("models".equals(kind)) {
                models = models(list(data, "models"));
                if (data.has("currentModel")) selectedModel = data.optString("currentModel");
                publish();
            } else if ("model".equals(kind)) { modelChanging = false; selectedModel = data.optString("id", selectedModel); persistSettings(); publish(); }
            else if ("usage".equals(kind)) { rateLimits.put("sessionUsage", data); publish(); }
            else if ("rateLimits".equals(kind)) { rateLimits.put("rateLimits", data); publish(); }
            else if ("session".equals(kind)) {
                threadId = data.optString("id", "");
                if (!threadId.isEmpty()) getSharedPreferences("pocketagent_sessions", MODE_PRIVATE).edit().putString(sessionKey(), threadId).apply();
                publish();
            } else if ("status".equals(kind)) {
                status = clean(data.optString("text", status), 350);
                if (data.has("busy")) busy = data.optBoolean("busy");
                if (!busy) { finishMessageActivity("", "completed"); releaseLease(); persist(); } else lease();
                publish();
            } else if ("ready".equals(kind)) { connected = true; connecting = false; authenticating = false; hasError = false; busy = false; status = "Claude Code ready"; rememberConnection(); publish(); }
        } catch (Exception failure) { fail(failure.getMessage()); }
    }

    private void serverRequest(Object wireId, String method, JSONObject params) throws Exception {
        if ("mcpServer/elicitation/request".equals(method) && !isCodex()) {
            send(response(true, wireId, object("code", -32601, "message", "This native MCP form protocol is available only for Codex."), true)); return;
        }
        if (!"session/request_permission".equals(method) && !"item/commandExecution/requestApproval".equals(method)
            && !"item/fileChange/requestApproval".equals(method) && !"item/permissions/requestApproval".equals(method)
            && !"item/tool/requestUserInput".equals(method) && !"cursor/ask_question".equals(method) && !"cursor/create_plan".equals(method)
            && !"mcpServer/elicitation/request".equals(method)) {
            send(response(!isCodex(), wireId, object("code", -32601, "message", "PocketAgent does not implement this client method: " + method), true));
            addMessage("system", "The agent requested a feature this build cannot handle: " + method + ". The request was declined, not auto-approved.", ""); publish(); return;
        }
        Approval approval = new Approval(); approval.wireId = wireId; approval.method = method;
        approval.key = nextPermissionKey(); approval.params = params;
        if ("mcpServer/elicitation/request".equals(method)) {
            try {
                McpElicitation.Request request = McpElicitation.parse(params);
                JSONArray options = array(object("id", "accept", "label", request.isUrl() ? "I completed sign-in" : "Send form", "kind", "allow_once"),
                        object("id", "decline", "label", "Decline", "kind", "reject_once"), object("id", "cancel", "label", "Cancel", "kind", "reject_once"));
                approval.card = object("id", approval.key, "title", "Request from " + request.serverName,
                        "detail", request.message, "options", options, "allowText", false, "elicitation", request.toJson());
            } catch (McpElicitation.UnsupportedRequest unsupported) {
                send(response(false, wireId, object("action", "decline"), false));
                addMessage("system", "The MCP request was declined: " + clean(unsupported.getMessage(), 500), ""); publish(); return;
            }
        } else if ("item/tool/requestUserInput".equals(method) || "cursor/ask_question".equals(method)) {
            approval.questions = list(params, "questions");
            if (approval.questions.length() == 0) {
                send(response(!isCodex(), wireId, object("code", -32602, "message", "No questions supplied"), true)); return;
            }
            questionCard(approval);
        } else {
            JSONArray options = new JSONArray();
            String title = params.optString("reason", "Permission needed"); String detail = "";
            if ("session/request_permission".equals(method)) {
                JSONObject tool = child(params, "toolCall"); title = tool.optString("title", "Allow this agent action?");
                detail = clean(tool.toString(2), 16000);
                JSONArray given = list(params, "options");
                for (int i = 0; i < given.length(); i++) {
                    JSONObject option = childAt(given, i);
                    options.put(object("id", option.optString("optionId"), "label", option.optString("name", option.optString("optionId")), "kind", option.optString("kind")));
                }
                options.put(object("id", "cancel", "label", "Cancel", "kind", "reject_once"));
            } else if ("cursor/create_plan".equals(method)) {
                title = params.optString("name", "Approve this plan?"); detail = params.optString("overview") + "\n" + params.optString("plan");
                options = array(object("id", "accept", "label", "Approve plan", "kind", "allow_once"), object("id", "decline", "label", "Reject", "kind", "reject_once"));
            } else {
                detail = clean(params.toString(2), 20000);
                JSONArray decisions = list(params, "availableDecisions");
                if (decisions.length() > 0) {
                    for (int i = 0; i < decisions.length(); i++) {
                        String decision = decisions.optString(i, "");
                        if ("accept".equals(decision)) options.put(object("id", "accept", "label", "Allow once", "kind", "allow_once"));
                        else if ("decline".equals(decision)) options.put(object("id", "decline", "label", "Decline", "kind", "reject_once"));
                        else if ("cancel".equals(decision)) options.put(object("id", "cancel", "label", "Cancel", "kind", "reject_once"));
                    }
                    if (options.length() == 0) {
                        send(response(false, wireId, object("code", -32602, "message", "This approval requires a decision type PocketAgent does not support."), true));
                        fail("The agent requested an unsupported approval type. No permission was granted."); return;
                    }
                } else options = array(object("id", "accept", "label", "Allow once", "kind", "allow_once"), object("id", "decline", "label", "Decline", "kind", "reject_once"));
            }
            approval.card = object("id", approval.key, "title", clean(title, 300), "detail", clean(detail, 20000), "options", options, "allowText", false);
        }
        approvals.put(approval.key, approval); notifyApproval(approval); status = "Your approval is needed"; publish();
    }

    private void questionCard(Approval approval) {
        approval.key = nextPermissionKey();
        JSONObject question = childAt(approval.questions, approval.questionIndex);
        JSONArray options = new JSONArray(); JSONArray given = list(question, "options");
        for (int i = 0; i < given.length(); i++) {
            JSONObject option = childAt(given, i);
            String value = "cursor/ask_question".equals(approval.method) ? option.optString("id") : option.optString("label");
            options.put(object("id", "option:" + i, "label", option.optString("label", value), "kind", "answer"));
        }
        boolean freeText = !"cursor/ask_question".equals(approval.method);
        if (freeText) options.put(object("id", "text", "label", "Send answer", "kind", "answer"));
        options.put(object("id", "cancel", "label", "Cancel question", "kind", "reject_once"));
        approval.card = object("id", approval.key, "title", question.optString("header", "Question " + (approval.questionIndex + 1)),
            "detail", question.optString("question", question.optString("prompt", "Choose an answer")), "options", options, "allowText", freeText);
    }

    private void answer(String key, String option, String text) throws Exception {
        Approval approval = approvals.get(key);
        if (approval == null) throw new IllegalStateException("That permission request is no longer active.");
        boolean valid = false;
        JSONArray options = list(approval.card, "options");
        for (int i = 0; i < options.length(); i++) if (childAt(options, i).optString("id").equals(option)) valid = true;
        if (!valid) throw new IllegalArgumentException("Choose one of the displayed options.");
        if ("claude".equals(approval.method) && claude != null) {
            approvals.remove(key);
            try { claude.answer(String.valueOf(approval.wireId), option, text); }
            catch (Exception error) { approvals.put(key, approval); throw error; }
            clearApprovalNotice(approval);
            publish(); return;
        }
        JSONObject result;
        if ("mcpServer/elicitation/request".equals(approval.method)) {
            McpElicitation.Request request = McpElicitation.parse(approval.params);
            if (text != null && text.length() > 64000) throw new IllegalArgumentException("The MCP form is too large.");
            JSONObject content = "accept".equals(option) && !request.isUrl() && text != null && !text.isEmpty() ? new JSONObject(text) : new JSONObject();
            result = request.response(option, content);
        } else if (approval.questions != null) {
            if ("cancel".equals(option)) {
                result = "cursor/ask_question".equals(approval.method) ? object("outcome", object("outcome", "cancelled")) : object("answers", new JSONObject());
            } else {
                JSONObject question = childAt(approval.questions, approval.questionIndex); String value;
                if ("text".equals(option)) {
                    if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Enter an answer first.");
                    value = clean(text.trim(), 12000);
                } else {
                    int index = Integer.parseInt(option.substring(7)); JSONObject choice = childAt(list(question, "options"), index);
                    value = "cursor/ask_question".equals(approval.method) ? choice.optString("id") : choice.optString("label");
                }
                String questionId = question.optString("id");
                if ("cursor/ask_question".equals(approval.method)) approval.cursorAnswers.put(object("questionId", questionId, "selectedOptionIds", array(value)));
                else approval.answers.put(questionId, object("answers", array(value)));
                approval.questionIndex++;
                if (approval.questionIndex < approval.questions.length()) {
                    approvals.remove(key); questionCard(approval); approvals.put(approval.key, approval); publish(); return;
                }
                result = "cursor/ask_question".equals(approval.method) ? object("outcome", object("outcome", "answered", "answers", approval.cursorAnswers)) : object("answers", approval.answers);
            }
        } else if ("session/request_permission".equals(approval.method)) {
            result = "cancel".equals(option) ? object("outcome", object("outcome", "cancelled")) : object("outcome", object("outcome", "selected", "optionId", option));
        } else if ("cursor/create_plan".equals(approval.method)) result = object("outcome", object("outcome", "accept".equals(option) ? "accepted" : "rejected"));
        else if ("item/permissions/requestApproval".equals(approval.method)) result = object("permissions", "accept".equals(option) ? child(approval.params, "permissions") : new JSONObject(), "scope", "turn");
        else result = object("decision", option);
        send(response(!isCodex(), approval.wireId, result, false)); approvals.remove(key); clearApprovalNotice(approval);
        status = approvals.isEmpty() ? AgentCatalog.name(provider) + " is working" : "Your approval is needed"; publish();
    }

    private void request(String method, JSONObject params, Reply reply) throws Exception {
        final String id = String.valueOf(++sequence); final long ownGeneration = generation;
        pending.put(id, new Pending(method, reply));
        try { send(AgentProtocol.request(!isCodex(), id, method, params)); }
        catch (Exception failure) { pending.remove(id); throw failure; }
        if (!"session/prompt".equals(method)) {
            long timeout = "authenticate".equals(method) ? 10 * 60 * 1000L : 90000L;
            main.postDelayed(() -> execute(() -> {
                if (generation != ownGeneration) return;
                Pending waiting = pending.remove(id);
                if (waiting == null) return;
                try { waiting.reply.receive(new JSONObject(), object("code", -32098, "message", "The engine did not answer " + method + " in time. Reconnect to try again.")); }
                catch (Exception e) { fail(e.getMessage()); }
            }), timeout);
        }
    }

    private void notifyAgent(String method, JSONObject params) throws Exception { send(AgentProtocol.request(!isCodex(), null, method, params)); }
    private void send(JSONObject value) throws Exception {
        if (stdin == null) throw new IllegalStateException("The agent connection has closed.");
        stdin.write((value.toString() + "\n").getBytes(StandardCharsets.UTF_8)); stdin.flush();
    }

    private void diagnostic(String line) {
        if (claude != null) {
            try { claude.onDiagnostic(line); }
            catch (Exception error) { fail(error.getMessage()); }
        }
        String cleanLine = clean(line, 12000);
        Matcher matcher = URL.matcher(cleanLine);
        boolean foundLogin = false;
        while (matcher.find()) {
            String candidate = matcher.group().replaceAll("[),.;]+$", "");
            if (AgentProtocol.loginUrl(provider, candidate) && (authenticating || cleanLine.contains("POCKETAGENT_AUTH_URL:") || cleanLine.toLowerCase(java.util.Locale.ROOT).contains("login") || cleanLine.toLowerCase(java.util.Locale.ROOT).contains("authorize"))) {
                offerLogin(candidate); foundLogin = true;
            }
        }
        // Do not persist OAuth URLs, bearer tokens or arbitrary engine stderr to transcript.
        if (foundLogin || cleanLine.trim().isEmpty()) return;
        if (isCodex() && AgentProtocol.internalRolloutDiagnostic(cleanLine)) return;
        String lower = cleanLine.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("error") || lower.contains("fatal") || lower.contains("not found") || lower.contains("permission denied")) {
            String redacted = cleanLine.replaceAll("(?i)(bearer|token|api[_-]?key|access[_-]?token)[ :=]+[^\\s,}]+", "$1 [redacted]");
            runtimeDiagnostics.add(object("time", System.currentTimeMillis(), "message", clean(redacted, 2500)));
            while (runtimeDiagnostics.size() > 8) runtimeDiagnostics.remove(0);
            publish();
        }
    }

    private void offerLogin(String value) {
        if (!AgentProtocol.loginUrl(provider, value)) { if (!value.isEmpty()) fail("The engine supplied an unexpected sign-in domain. Open its official account app to check."); return; }
        hasError = false; authenticating = true;
        authUrl = value; status = "Open official sign-in, then return here"; publish();
    }

    private void appendAssistant(String id, String text, boolean replace) {
        String key = "assistant-" + id;
        JSONObject existing = findMessage(key);
        if (existing == null) addMessage("assistant", clean(text, 32000), key);
        else {
            try { existing.put("text", clean(replace ? text : existing.optString("text") + text, 32000)); }
            catch (Exception ignored) {}
        }
        JSONObject message = findMessage(key);
        if (message != null) try {
            message.put("streaming", !replace);
            if (replace) message.put("completedAt", System.currentTimeMillis());
            else message.remove("completedAt");
        } catch (Exception ignored) { }
        publish();
    }

    private void setMessageActivity(String key, JSONObject activity, boolean complete) {
        JSONObject message = findMessage(key); if (message == null) return;
        try {
            message.put("activity", activity).put("streaming", !complete);
            if (complete) message.put("completedAt", System.currentTimeMillis());
            else message.remove("completedAt");
        } catch (Exception ignored) { }
    }

    private void finishMessageActivity(String finishedTurn, String outcome) {
        for (JSONObject message : messages) {
            if ((!finishedTurn.isEmpty() && !finishedTurn.equals(message.optString("turnId"))) || !message.optBoolean("streaming")) continue;
            try {
                message.put("streaming", false).put("completedAt", System.currentTimeMillis());
                JSONObject activity = message.optJSONObject("activity");
                if (activity != null) activity.put("status", "completed".equals(outcome) ? "completed" : "failed".equals(outcome) ? "failed" : "cancelled");
            } catch (Exception ignored) { }
        }
    }

    private void upsert(String id, String role, String text) {
        JSONObject existing = findMessage(id);
        if (existing == null) addMessage(role, text, id);
        else { try { existing.put("text", clean(text, 32000)); } catch (Exception ignored) {} }
    }

    private JSONObject findMessage(String id) { for (JSONObject item : messages) if (id.equals(item.optString("id"))) return item; return null; }
    private void addMessage(String role, String text, String id) {
        messages.add(object("id", id.isEmpty() ? "message-" + System.currentTimeMillis() + "-" + (++messageSequence) : id, "role", role, "text", clean(text, 32000), "time", System.currentTimeMillis(), "turnId", turnId));
        trimMessages();
    }
    private void trimMessages() {
        int size = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            // Include artifact references in the bound; otherwise long signed media URLs can overflow Binder.
            size += messages.get(i).toString().length();
            if (size > 160000 || messages.size() - i > 80) { for (int j = i; j >= 0; j--) messages.remove(j); break; }
        }
    }

    private void publish() {
        if (destroyed) return;
        trimMessages();
        JSONArray list = new JSONArray(); for (JSONObject message : messages) list.put(message);
        JSONArray diagnostics = new JSONArray(); for (JSONObject entry : runtimeDiagnostics) diagnostics.put(entry);
        JSONObject permission = approvals.isEmpty() ? null : approvals.values().iterator().next().card;
        String update = object("provider", provider, "project", projectName, "status", status,
            "engineRunning", process != null,
            "switchAllowed", switchBlockedReason().isEmpty(), "switchBlockedReason", switchBlockedReason(),
            "switchRequestId", switchRequestId, "switchError", switchError, "modelChanging", modelChanging,
            "connected", connected, "busy", busy, "installing", installing, "authUrl", authUrl, "authCode", authCode,
            "connecting", connecting, "authenticating", authenticating, "error", hasError,
            "accountConnected", isCodex() ? accountVerified && AgentProtocol.chatgptAccount(account) : connected,
            "sessionOpening", sessionOpening, "recoveringSession", recoveringSession, "historyNotice", historyNotice, "runtimeDiagnostics", diagnostics,
            "integrations", integrations.snapshot(),
            "sessions", sessions.snapshot(),
            "refresh", metadataRefresh.snapshot(SystemClock.elapsedRealtime(), System.currentTimeMillis()),
            "creditsReset", creditsResetState,
            "voice", voice.snapshot(),
            "accountEpoch", accountEpoch, "accountScopeToken", accountScopeToken(),
            "controls", object("operation", controlOperation, "error", controlError, "status", controlStatus, "environment", environment, "background", backgroundTasks.snapshot()),
            "mode", selectedMode, "modeOptions", isCodex() ? CodexControls.modeOptions(collaborationModes, effectiveModel()) : new JSONArray(),
            "modeKnown", modeKnown, "modeError", modeError,
            "imageInputSupported", isCodex() && CodexControls.imageSupported(effortMetadata()),
            "inputModalities", list(effortMetadata(), "inputModalities"),
            "threadId", threadId,
            "account", account, "models", models, "model", selectedModel, "rateLimits", rateLimits,
            "effectiveModel", effectiveModel(), "confirmedModel", confirmedModel,
            "effort", isCodex() ? selectedEffort : CodexEffort.AUTO,
            "effectiveEffort", isCodex() ? CodexEffort.effectiveEffort(effortMetadata(), selectedEffort,
                effectiveModel().equals(confirmedModel) ? confirmedEffort : "") : "",
            "effortOptions", CodexEffort.options(effortMetadata()),
            "tokenUsage", tokenUsage, "usageUpdatedAt", usageUpdatedAt, "tokenUsageUpdatedAt", tokenUsageUpdatedAt,
            "messages", list, "permission", permission, "pendingApprovals", approvals.size(), "fullAccess", fullAccess).toString();
        synchronized (this) {
            if (destroyed) return;
            latestSnapshot = update;
        }
        snapshotDelivery.publish();
    }

    private void deliverSnapshot() {
        if (destroyed) return;
        sendBroadcast(new Intent(EVENT).setPackage(getPackageName())
                .putExtra(EXTRA_SNAPSHOT, latestSnapshot));
        // The installer owns the worker and already renews its wake lock. Queuing
        // a callback for every progress line would delay login after setup ends.
        if (!installing) execute(() -> {
            if (busy) lease();
            if (System.currentTimeMillis() - lastPersist > 5000) persist();
        });
    }

    private void failRpc(String context, JSONObject error) {
        String message = error.optString("message", "The agent rejected this request.");
        if (isCodex() && AgentProtocol.internalRolloutDiagnostic(message))
            message = "The saved conversation is unavailable. Your project files are unchanged; start a new chat to continue.";
        fail(context + ": " + message);
    }
    private void fail(String message) {
        hasError = true; connecting = false;
        if (!connected) { authenticating = false; authUrl = ""; authCode = ""; }
        status = clean(message == null ? "Something went wrong. Reconnect to try again." : message, 350);
        addMessage("system", status, ""); publish();
    }

    private boolean isCodex() { return "codex".equals(provider); }
    private String nextPermissionKey() { return "approval-" + permissionEpoch + "-" + generation + "-" + (++permissionSequence); }
    private void notifyApproval(Approval approval) {
        approval.notificationSession = threadId;
        approval.notificationId = permissionEpoch + ":" + generation + ":" + String.valueOf(approval.wireId);
        AgentNotifications.approval(this, provider, projectName, approval.notificationSession, approval.notificationId);
    }
    private void clearApprovalNotice(Approval approval) {
        AgentNotifications.clearApproval(this, provider, projectName, approval.notificationSession, approval.notificationId);
    }
    private void clearApprovals() {
        for (Approval approval : approvals.values()) clearApprovalNotice(approval);
        approvals.clear();
    }
    private void removeApprovalWire(String id) {
        java.util.Iterator<Map.Entry<String, Approval>> iterator = approvals.entrySet().iterator();
        while (iterator.hasNext()) {
            Approval approval = iterator.next().getValue();
            if (id.equals(String.valueOf(approval.wireId))) { clearApprovalNotice(approval); iterator.remove(); }
        }
    }
    private String sessionKey() { return provider + ":" + projectName; }
    private File transcriptFile() { return new File(new File(getFilesDir(), "desk-chats"), provider + "-" + projectName + ".json"); }
    private void persistSettings() {
        try {
            JSONObject settings = AgentWorkspaceState.settings(pendingRestoredModel.isEmpty() ? selectedModel : pendingRestoredModel, selectedMode);
            getSharedPreferences("pocketagent_sessions", MODE_PRIVATE).edit()
                    .putString(AgentWorkspaceState.settingsKey(provider, projectName), settings.toString()).apply();
        } catch (RuntimeException ignored) { }
    }
    private void restoreSettings() {
        try {
            JSONObject settings = AgentWorkspaceState.settings(new JSONObject(getSharedPreferences("pocketagent_sessions", MODE_PRIVATE)
                    .getString(AgentWorkspaceState.settingsKey(provider, projectName), "{}")));
            // Wait for this engine's fresh advertised model catalog before applying a saved choice.
            pendingRestoredModel = settings.optString("model");
            selectedMode = isCodex() ? settings.optString("mode", CodexControls.ASK) : CodexControls.ASK;
        } catch (Exception ignored) { }
    }
    private void persist() {
        persistSettings();
        lastPersist = System.currentTimeMillis();
        try {
            File target = transcriptFile(); if (!target.getParentFile().isDirectory()) target.getParentFile().mkdirs();
            JSONArray transcript = new JSONArray(); for (JSONObject message : messages) transcript.put(message);
            JSONObject saved = object("provider", provider, "project", projectName, "messages", transcript);
            File temporary = new File(target.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(saved.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
            if (!temporary.renameTo(target)) { temporary.delete(); return; }
            android.content.SharedPreferences.Editor editor = getSharedPreferences("pocketagent_sessions", MODE_PRIVATE).edit()
                    .putString("lastProvider", provider).putString("lastProject", projectName);
            if (detachedConversation) { editor.remove(detachedKey()); detachedConversation = false; }
            editor.apply();
        } catch (Exception ignored) { /* Engine history remains authoritative even if a UI cache cannot be saved. */ }
    }
    private void restore() {
        String savedProvider = getSharedPreferences("pocketagent_sessions", MODE_PRIVATE).getString("lastProvider", "codex");
        String savedProject = getSharedPreferences("pocketagent_sessions", MODE_PRIVATE).getString("lastProject", "my-project");
        try { projectName = AgentProtocol.project(savedProject); if (AgentCatalog.isValid(savedProvider)) provider = savedProvider; restoreSettings(); restoreConversation(); }
        catch (Exception ignored) {}
    }
    private void restoreConversation() {
        try {
            String archive = getSharedPreferences("pocketagent_sessions", MODE_PRIVATE).getString(detachedKey(), "");
            if (!archive.isEmpty()) {
                // A crash/storage interruption after archiving cannot restore old messages as new model context.
                detachedConversation = true;
                historyNotice = "Previous chat saved. Started a new conversation.";
                return;
            }
            File source = transcriptFile(); if (!source.isFile() || source.length() > 700000) return;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (FileInputStream in = new FileInputStream(source)) { byte[] block = new byte[4096]; int n; while ((n = in.read(block)) != -1) bytes.write(block, 0, n); }
            JSONArray history = list(new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)), "messages");
            for (int i = 0; i < history.length(); i++) {
                JSONObject message = history.optJSONObject(i);
                if (message != null && !AgentReconnect.routingNotice(message)) {
                    AgentActivity.restoreCachedMessage(message);
                    messages.add(message);
                }
            }
            trimMessages();
        } catch (Exception ignored) {}
    }

    private void disconnect(boolean announce) {
        if (announce) forgetReconnect();
        finishMessageActivity("", "interrupted");
        taskEpoch.invalidate();
        metadataRefresh.closed(); codexInitialized = false; initialAccountRead = false;
        closing = true; ++generation; connected = false; connecting = false; busy = false; authenticating = false; modelChanging = false;
        accountVerified = false; deferredSessionResume = null;
        integrations.closed();
        sessions.closed(); controlOperation = ""; manualSessionResume = false;
        backgroundTasks.closed();
        voice.closed();
        sessionOpening = false; recoveringSession = false;
        pending.clear(); clearApprovals(); authUrl = ""; authCode = ""; threadId = ""; turnId = "";
        Process old = process; process = null; stdin = null;
        claude = null;
        if (old != null) ProotProcess.stopAndWait(old);
        releaseLease(); if (announce) { hasError = false; status = "Disconnected · chat saved"; }
        persist(); publish(); closing = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    /** Recover the connection only. A lost turn, approval or credit request is never replayed. */
    private void recoverableConnectionFailure(String message) {
        String failedProvider = provider, failedProject = projectName;
        boolean eligible = isCodex() && canReconnect(this, provider, projectName);
        long now = SystemClock.elapsedRealtime();
        long delay = eligible && !AppLock.isLocked(this) && protectionReason().isEmpty()
                ? connectionRecovery.failureDelay(sessionKey(), now) : -1;
        disconnect(false);
        if (delay < 0) {
            fail(message + " Your chat is saved. Tap Connect to continue.");
            return;
        }
        final long recoveryGeneration = generation;
        connecting = true; hasError = false;
        status = "Reconnecting Codex…";
        foreground(status); publish();
        main.postDelayed(() -> execute(() -> {
            if (destroyed || generation != recoveryGeneration || process != null || installing) return;
            connecting = false;
            if (!provider.equals(failedProvider) || !projectName.equals(failedProject)
                    || !canReconnect(this, failedProvider, failedProject)
                    || !connectionRecovery.visible(sessionKey(), SystemClock.elapsedRealtime())
                    || AppLock.isLocked(this) || !protectionReason().isEmpty()) {
                status = "Connection paused · chat saved"; publish(); stopForeground(STOP_FOREGROUND_REMOVE); return;
            }
            try { connect(failedProvider, failedProject, connectionMemory().optBoolean("fullAccess"), true); }
            catch (Exception failure) { recoverableConnectionFailure(clean(failure.getMessage(), 220)); }
        }), delay);
    }

    private void foreground(String text) {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent launch = PendingIntent.getActivity(this, 2314, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2315, new Intent(this, AgentService.class).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_pocketagent)
            .setContentTitle("PocketAgent").setContentText(text).setContentIntent(launch).setOngoing(true)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Disconnect", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION_ID, notification);
    }

    private void lease() { if (wakeLock != null) wakeLock.acquire(120000L); }
    private void releaseLease() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
    private String protectionReason() {
        try {
            DeviceProbe device = DeviceProbe.read(this);
            if (device.batteryTempC >= 49f || device.thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL)
                return "Agent paused because the phone is too hot. Let it cool, then reconnect; your files and chat are saved.";
            BatteryManager battery = (BatteryManager) getSystemService(BATTERY_SERVICE);
            boolean charging = battery != null && battery.isCharging();
            if (device.batteryPercent >= 0 && device.batteryPercent <= 3 && !charging)
                return "Agent paused because battery is at 3% or less. Connect a charger, then reconnect.";
        } catch (RuntimeException ignored) { /* An unavailable sensor is not a reason to stop work. */ }
        return "";
    }
    private void execute(Runnable task) { if (!destroyed) try { serial.execute(task); } catch (RejectedExecutionException ignored) {} }
    private static JSONObject childAt(JSONArray list, int index) { JSONObject item = list.optJSONObject(index); return item == null ? new JSONObject() : item; }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        final String stoppedSnapshot;
        synchronized (this) {
            destroyed = true;
            JSONObject stopped = snapshot();
            try {
                boolean wasActive = stopped.optBoolean("connecting") || stopped.optBoolean("connected")
                        || stopped.optBoolean("authenticating") || stopped.optBoolean("installing");
                if (wasActive && !stopped.optBoolean("error"))
                    stopped.put("status", "Agent stopped · reconnect to continue");
                stopped.put("connected", false).put("connecting", false).put("authenticating", false)
                        .put("engineRunning", false)
                        .put("sessionOpening", false).put("recoveringSession", false)
                        .put("installing", false).put("busy", false).put("authUrl", "").put("authCode", "")
                        .put("permission", JSONObject.NULL).put("pendingApprovals", 0);
                latestSnapshot = stopped.toString();
            } catch (Exception ignored) { /* Preserve the last complete status if normalization fails. */ }
            stoppedSnapshot = latestSnapshot;
        }
        main.removeCallbacksAndMessages(null); releaseLease();
        Process old = process; process = null;
        if (old != null) ProotProcess.requestStop(old);
        // stopSelf can destroy the service before the coalesced 100 ms callback.
        // Deliver the last failure synchronously so the visible UI leaves Connecting.
        sendBroadcast(new Intent(EVENT).setPackage(getPackageName()).putExtra(EXTRA_SNAPSHOT, stoppedSnapshot));
        serial.shutdownNow(); super.onDestroy();
    }
}
