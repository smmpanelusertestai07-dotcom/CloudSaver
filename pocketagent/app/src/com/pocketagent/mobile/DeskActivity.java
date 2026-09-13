package com.pocketagent.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native touch client. Agent engines own authentication, execution and permission decisions. */
public final class DeskActivity extends Activity {
    private int BG, CARD, FIELD, LINE, INK, MUTED, ACCENT, SOFT_BLUE, AMBER, RED;
    private static final String[] PROVIDERS = {"codex", "cursor", "claude", "antigravity"};
    private static final String[] NAMES = {"Codex", "Cursor", "Claude Code", "Antigravity"};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout root, body, nav, composer, feed, brandBar, agentBar;
    private FrameLayout lockShell, drawerOverlay;
    private LinearLayout drawerRows;
    private EditText drawerSearch;
    private JSONArray drawerCached = new JSONArray();
    private String drawerCacheScope = "", drawerIndexSignature = "";
    private boolean drawerIndexBusy, drawerArchived, drawerFetchPending;
    private int drawerVisible = 100;
    private final Runnable drawerQuery = () -> refreshDrawerSessions();
    private Button navigationButton;
    private String drawerSignature = "", dictationScope = "", reconnectScope = "";
    private ScrollView scroll, composerScroll;
    private TextView projectLabel, connectionLabel, modelLabel, effortLabel, modeLabel, usageLabel, selectedSkillLabel, attachmentsLabel;
    private EditText prompt;
    private Button send, stopButton, voiceButton;
    private InlineDictation inlineDictation;
    private MessageSpeech messageSpeech;
    private ComposerAttachments attachmentStrip;
    private SessionActionsSheet sessionSheet;
    private LinearLayout usageWarning;
    private TextView usageWarningTitle,usageWarningDetail;
    private Button usageWarningAction;
    private boolean pendingCodeExport;
    private final Runnable composerLayout=()->updateKeyboardLayout();
    private String interactionScope="",pendingCodeText="",pendingCodeName="";
    private static final int SAVE_CODE=4820;
    private final Map<String,TextView> messageTimes=new HashMap<>();
    private final Map<String,Button> messageSpeakers=new HashMap<>();
    private final Map<String,TextView> activityHeaders=new HashMap<>(),activityBodies=new HashMap<>();
    private final java.util.Set<String> expandedActivities=new java.util.HashSet<>();
    private boolean keyboardVisible, compactWindow;
    private UsageDisplay displayedUsage;
    private AlertDialog usageDialog;
    private UsagePanel usagePanel;
    private LinearLayout fileRows;
    private TextView changesOutput, workspaceStatus;
    private long workspaceDelay = 5000;
    private boolean workspaceReadBusy;
    private long workspaceReadAt, lastPresenceAt;
    private String fileListSignature = "";
    private final String clientId = java.util.UUID.randomUUID().toString();
    private final Runnable autoTick = new Runnable() {
        @Override public void run() {
            if (!resumed || isFinishing() || isDestroyed() || AppLock.isLocked(DeskActivity.this)) return;
            if (android.os.SystemClock.elapsedRealtime() - lastPresenceAt >= 45000) signalClient(true);
            readWorkspace(false);
            updateMessageFooters();
            if (usagePanel != null && usageDialog != null && usageDialog.isShowing()) usagePanel.update(UsageDisplay.read(state, provider));
            main.postDelayed(this, 2000);
        }
    };
    private TextView accountStatus, accountDescription, accountUsage, accountCode;
    private Button accountPrimary, accountCancel;
    private ProgressBar accountProgress;
    private SharedPreferences prefs;
    private JSONObject state = new JSONObject();
    private int tab, renderId, transcriptId, artifactCardsRendered, thumbnailsRendered;
    private String provider = "codex", folder = "", attachedPath = "";
    private String selectedSkillPath = "", selectedSkillName = "";
    private String selectedAppId = "", selectedAppName = "";
    private JSONArray imageAttachments = new JSONArray();
    private final java.util.ArrayList<String> fileAttachments = new java.util.ArrayList<>();
    private String themeSignature = "", pendingImportProject = "", handledToken = "";
    private boolean resumed, paletteOpen, changingDraft;
    private boolean sendPending, resolvingReferences;
    private ComposerTokens.Token pendingReference, toolToken;
    private AlertDialog toolSheet;
    private android.widget.ArrayAdapter<String> toolAdapter;
    private java.util.ArrayList<Runnable> toolActions;
    private String toolScope="",toolFilter="",toolSignature="";
    private int sendTicket;
    private String pendingDraft="", pendingExpected="", pendingSendProject="", pendingSendProvider="";
    private final java.util.HashSet<String> pendingBeforeIds=new java.util.HashSet<>();
    private final java.util.ArrayList<Runnable> pendingUi = new java.util.ArrayList<>();
    private static final int PICK_FILES = 4811, DICTATE = 4812;
    private String lastTranscript = "";
    private final Map<String, TextView> messageViews = new HashMap<>();
    private final java.util.Set<AlertDialog> dialogs = new java.util.HashSet<>();
    private boolean registered, refreshing;
    private boolean connectAfterSetup, connectAfterProject, connectionRequestPending;
    private String pendingAgentSwitch = "", switchTargetProvider = "", switchTargetProject = "";
    private String switchOriginProvider="",switchOriginProject="",switchOriginAccount="";
    private final Runnable retryAgentSwitch=()->{if(!pendingAgentSwitch.isEmpty()&&resumed&&!AppLock.isLocked(this)){refreshState();if(!pendingAgentSwitch.isEmpty())dispatchAgentSwitch();}};
    private File project;
    private File exportingProject;
    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (PreviewService.EVENT.equals(intent.getAction())) {
                int port = intent.getIntExtra(PreviewService.EXTRA_PORT, 0);
                if (intent.getBooleanExtra(PreviewService.EXTRA_ERROR, false)) {
                    deliverUi(() -> {prefs.edit().putBoolean("open_preview_when_ready", false).apply();error(intent.getStringExtra(PreviewService.EXTRA_MESSAGE));});
                }
                else if (port > 0) maybeOpenPreview(port);
                return;
            }
            if (!refreshing) {
                refreshing = true;
                main.postDelayed(() -> { refreshing = false; refreshState(); }, 120);
            }
        }
    };

    @Override public void onCreate(Bundle saved) {
        DeskStyle.apply(this);
        super.onCreate(saved);
        BG = DeskStyle.BG; CARD = DeskStyle.SURFACE; FIELD = DeskStyle.FIELD; LINE = DeskStyle.LINE;
        INK = DeskStyle.TEXT; MUTED = DeskStyle.MUTED; ACCENT = DeskStyle.ACCENT; SOFT_BLUE = DeskStyle.ACCENT;
        AMBER = DeskStyle.WARNING; RED = DeskStyle.ERROR; themeSignature = DeskStyle.themeSignature(this);
        prefs = getSharedPreferences("pocketagent_native", MODE_PRIVATE);
        provider = prefs.getString("provider", "codex");
        if (saved != null) {
            pendingAgentSwitch=saved.getString("agent_switch", "");switchTargetProvider=saved.getString("switch_provider", "");switchTargetProject=saved.getString("switch_project", "");
            switchOriginProvider=saved.getString("switch_origin_provider", "");switchOriginProject=saved.getString("switch_origin_project", "");switchOriginAccount=saved.getString("switch_origin_account", "");
            dictationScope=saved.getString("dictation_scope", "");
            pendingCodeText=saved.getString("code_text", "");pendingCodeName=saved.getString("code_name", "");pendingCodeExport=saved.getBoolean("code_pending",false);
            tab = saved.getInt("tab", 0);
            connectAfterSetup = saved.getBoolean("connect_after_setup", false);
            connectAfterProject = saved.getBoolean("connect_after_project", false);
            selectedSkillPath = saved.getString("skill_path", ""); selectedSkillName = saved.getString("skill_name", "");
            selectedAppId = saved.getString("app_id", ""); selectedAppName = saved.getString("app_name", "");
            pendingImportProject = saved.getString("import_project", "");
            try { imageAttachments = new JSONArray(saved.getString("image_attachments", "[]")); } catch (Exception ignored) { }
            java.util.ArrayList<String> files = saved.getStringArrayList("file_attachments"); if (files != null) fileAttachments.addAll(files);
        }
        state = AgentService.snapshot();
        if (state.optBoolean("connected") || state.optBoolean("installing")) provider = state.optString("provider", provider);
        try { project = WorkspaceTools.selectedProject(this); } catch (Exception ignored) { }
        if (state.optBoolean("connected") || state.optBoolean("installing")) {
            String activeProject = state.optString("project");
            if (activeProject.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                File active = new File(ContainerRuntime.workspaceRoot(this), activeProject);
                if (active.isDirectory()) project = active;
            }
        }
        if (!state.optBoolean("connected") && !state.optBoolean("installing") && project != null) {
            String remembered=prefs.getString("project_agent_"+project.getName(),provider);
            if(AgentCatalog.isValid(remembered))provider=remembered;
        }
        if (saved != null) {
            if (project == null || !project.getName().equals(saved.getString("context_project", "")) || !provider.equals(saved.getString("context_provider", ""))) {
                selectedSkillPath = ""; selectedSkillName = ""; selectedAppId = ""; selectedAppName = "";
                imageAttachments = new JSONArray(); fileAttachments.clear();
            }
            String exporting = saved.getString("exporting_project", "");
            if (exporting.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) exportingProject = new File(ContainerRuntime.workspaceRoot(this), exporting);
        }
        messageSpeech=new MessageSpeech(this,new MessageSpeech.Listener(){public void onChanged(){updateMessageFooters();}public void onNotice(String message){if(resumed&&!AppLock.isLocked(DeskActivity.this))toast(message);}});
        buildShell();
        initializeAgentDraft();
        renderPage();
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::onBackPressed);
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(AgentService.EVENT);
        filter.addAction(LinuxService.ACTION_STATUS);
        filter.addAction(PreviewService.EVENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(events, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(events, filter);
        registered = true;
        refreshState();
        JSONObject preview = PreviewService.snapshot();
        if (preview.optBoolean("running")) maybeOpenPreview(preview.optInt("port"));
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;reconnectScope="";
        if (!themeSignature.equals(DeskStyle.themeSignature(this))) { recreate(); return; }
        AppLock.applyWindowSecurity(this);
        if (lockShell != null && AppLock.isLocked(this)) AppLock.show(this, lockShell, () -> { refreshState(); drainUi(); resumeLiveUi(); });
        else { drainUi(); resumeLiveUi(); }
    }

    @Override protected void onPause() { if(inlineDictation!=null)inlineDictation.onPause();if(messageSpeech!=null)messageSpeech.cancelScope();if(sessionSheet!=null){sessionSheet.dismiss();sessionSheet=null;}closeDrawer(); signalClient(false); resumed = false; main.removeCallbacks(tokenChanged); main.removeCallbacks(autoTick); super.onPause(); }

    private void resumeLiveUi() { if (!resumed || AppLock.isLocked(this)) return; if(!pendingAgentSwitch.isEmpty()){main.removeCallbacks(retryAgentSwitch);main.post(retryAgentSwitch);}else reconnectRemembered(); signalClient(true); main.removeCallbacks(autoTick); main.post(autoTick); }
    private void reconnectRemembered() {
        if(project==null||AppLock.isLocked(this)||!resumed)return;
        String scope=provider+":"+project.getName();if(scope.equals(reconnectScope))return;reconnectScope=scope;
        if(AgentService.canReconnect(this,provider,project.getName())) {
            try {startService(agentIntent(AgentService.ACTION_RECONNECT));}catch(RuntimeException ignored){/* Explicit Connect remains available. */}
        }
    }
    private void signalClient(boolean active) {
        if (active && (project == null || AppLock.isLocked(this))) return;
        JSONObject engine=AgentService.snapshot();
        if(!engine.optBoolean("engineRunning")&&!engine.optBoolean("connecting")&&!engine.optBoolean("authenticating")&&!engine.optBoolean("installing"))return;
        lastPresenceAt = android.os.SystemClock.elapsedRealtime();
        try { startService(agentIntent(AgentService.ACTION_CLIENT).putExtra(AgentService.EXTRA_CLIENT_ID, clientId).putExtra(AgentService.EXTRA_ACTIVE, active)); }
        catch (RuntimeException ignored) { /* Android may defer background service delivery. */ }
    }

    @Override protected void onStop() {
        if (registered) { unregisterReceiver(events); registered = false; }
        if (AppLock.enabled(this)) for (AlertDialog d : new java.util.ArrayList<>(dialogs)) d.dismiss();
        saveAgentDraft();
        super.onStop();
    }

    @Override protected void onDestroy() {
        for (AlertDialog d : new java.util.ArrayList<>(dialogs)) d.dismiss();
        closeDrawer();
        main.removeCallbacksAndMessages(null);
        if(inlineDictation!=null)inlineDictation.close();if(messageSpeech!=null)messageSpeech.close();if(attachmentStrip!=null)attachmentStrip.release();
        io.shutdown();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("agent_switch",pendingAgentSwitch);out.putString("switch_provider",switchTargetProvider);out.putString("switch_project",switchTargetProject);
        out.putString("switch_origin_provider",switchOriginProvider);out.putString("switch_origin_project",switchOriginProject);out.putString("switch_origin_account",switchOriginAccount);
        out.putInt("tab", tab);out.putString("dictation_scope",dictationScope);
        out.putString("code_text",pendingCodeText);out.putString("code_name",pendingCodeName);out.putBoolean("code_pending",pendingCodeExport);
        out.putBoolean("connect_after_setup", connectAfterSetup);
        out.putBoolean("connect_after_project", connectAfterProject);
        out.putString("skill_path", selectedSkillPath); out.putString("skill_name", selectedSkillName);
        out.putString("app_id", selectedAppId); out.putString("app_name", selectedAppName);
        out.putString("image_attachments", imageAttachments.toString()); out.putStringArrayList("file_attachments", new java.util.ArrayList<>(fileAttachments));
        out.putString("import_project", pendingImportProject);
        out.putString("context_project", project == null ? "" : project.getName()); out.putString("context_provider", provider);
        if (exportingProject != null) out.putString("exporting_project", exportingProject.getName());
        saveAgentDraft();
        super.onSaveInstanceState(out);
    }

    private void buildShell() {
        root = column(); root.setBackground(DeskStyle.background(this));
        root.setPadding(dp(16), dp(8), dp(16), 0);
        lockShell = new FrameLayout(this);
        lockShell.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(lockShell);
        AppLock.applyWindowSecurity(this);
        // Android 15 enforces edge-to-edge. Include the IME insets so the composer stays touchable.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                int room = Math.max(0, (getResources().getDisplayMetrics().widthPixels - bars.left - bars.right - dp(680)) / 2);
                root.setPadding(dp(16) + bars.left + room, dp(8) + bars.top, dp(16) + bars.right + room, Math.max(bars.bottom, ime.bottom));
                keyboardVisible = ime.bottom > bars.bottom;
                int height = root.getHeight() > 0 ? root.getHeight() : getResources().getDisplayMetrics().heightPixels;
                compactWindow = height - bars.top - Math.max(bars.bottom, ime.bottom) < dp(480);
            } else {
                root.setPadding(dp(16) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(),
                        dp(16) + insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                keyboardVisible = insets.getSystemWindowInsetBottom() > dp(160);
                int height = root.getHeight() > 0 ? root.getHeight() : getResources().getDisplayMetrics().heightPixels;
                compactWindow = height - insets.getSystemWindowInsetTop() - insets.getSystemWindowInsetBottom() < dp(480);
            }
            updateKeyboardLayout();
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        DeskStyle.applySystemBars(this);

        LinearLayout brand = row(); brandBar = brand;
        navigationButton = iconButton("Open menu", "menu", false, v -> { if(tab==0)openDrawer();else navigate(0); });
        brand.addView(navigationButton, new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout title = column(); title.setGravity(Gravity.CENTER_VERTICAL); title.setPadding(dp(4),0,dp(4),0);
        projectLabel = label("PocketAgent",16,INK); projectLabel.setSingleLine(true); projectLabel.setEllipsize(TextUtils.TruncateAt.END);
        connectionLabel = text("",11,MUTED); connectionLabel.setSingleLine(true); connectionLabel.setEllipsize(TextUtils.TruncateAt.END);
        connectionLabel.setOnClickListener(v->providerPicker());connectionLabel.setContentDescription("Switch coding agent");
        title.addView(projectLabel); title.addView(connectionLabel);
        title.setContentDescription("Choose project or agent"); title.setBackground(DeskStyle.plain(this));
        title.setOnClickListener(v -> workspaceMenu());
        brand.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
        brand.addView(iconButton("New chat","new",false,v -> newChat()),new LinearLayout.LayoutParams(dp(48),dp(48)));
        brand.addView(iconButton("Chat options","more",false,v -> moreMenu()),new LinearLayout.LayoutParams(dp(48),dp(48)));
        root.addView(brand,lp(-1,52,0,2));

        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        body = column(); body.setPadding(0, dp(6), 0, dp(12)); scroll.addView(body);
        // Reserve the bottom navigation first. In short windows the composer itself scrolls,
        // keeping its Send control reachable instead of spilling behind the keyboard.
        LinearLayout workspace = column(); root.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));
        workspace.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        composer = column(); composer.setPadding(dp(8),dp(4),dp(8),dp(4)); composer.setBackground(DeskStyle.composer(this));
        selectedSkillLabel = text("", 12, ACCENT); selectedSkillLabel.setMinHeight(dp(36)); selectedSkillLabel.setGravity(Gravity.CENTER_VERTICAL);
        selectedSkillLabel.setPadding(dp(8), 0, dp(8), 0); selectedSkillLabel.setMaxLines(2);
        selectedSkillLabel.setBackground(Ui.tappable(this, DeskStyle.field(this), true)); decorate(selectedSkillLabel, "file", ACCENT, true);
        selectedSkillLabel.setOnClickListener(v -> selectedContextPicker());
        composer.addView(selectedSkillLabel);
        attachmentStrip=new ComposerAttachments(this,()->resumed&&tab==0&&!AppLock.isLocked(this),this::removeAttachment);
        composer.addView(attachmentStrip,new LinearLayout.LayoutParams(-1,-2));
        prompt = new EditText(this); prompt.setId(3001); prompt.setTextColor(INK); prompt.setHintTextColor(MUTED);
        prompt.setSaveEnabled(false);
        prompt.setTextSize(16); prompt.setHint("Message " + providerName(provider) + "…"); prompt.setBackgroundColor(Color.TRANSPARENT);
        prompt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        prompt.setMinLines(1); prompt.setMaxLines(5); prompt.setText(prefs.getString("draft", ""));
        prompt.setPadding(dp(4), dp(10), dp(4), dp(6));
        prompt.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); prompt.setPrivateImeOptions("nm");
        prompt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int a,int c,int f) { }
            @Override public void afterTextChanged(Editable value) { }
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {
                updateSendAction();
                if (changingDraft) return;
                main.removeCallbacks(tokenChanged); main.postDelayed(tokenChanged, 250);
            }
        });
        inlineDictation=new InlineDictation(this,new InlineDictation.Host(){
            public String scope(){return currentDictationScope();}
            public String draft(){return prompt.getText().toString();}
            public int selectionStart(){return prompt.getSelectionStart();}
            public int selectionEnd(){return prompt.getSelectionEnd();}
            public boolean isAvailable(){return resumed&&tab==0&&!sendPending&&!AppLock.isLocked(DeskActivity.this);}
            public boolean applyDraft(String expected,String replacement,int cursor){if(!expected.equals(prompt.getText().toString())||AppLock.isLocked(DeskActivity.this))return false;setDraft(replacement);prompt.setSelection(Math.max(0,Math.min(cursor,prompt.length())));return true;}
            public void onStateChanged(boolean active){updateSendAction();updateKeyboardLayout();}
            public void onMessage(String value){if(resumed&&!AppLock.isLocked(DeskActivity.this))toast(value);}
        });
        composer.addView(inlineDictation.view(),new LinearLayout.LayoutParams(-1,-2));
        LinearLayout promptRow=row();promptRow.setGravity(Gravity.BOTTOM);
        promptRow.addView(iconButton("Add files and tools","plus",false,v->addMenu()),new LinearLayout.LayoutParams(dp(48),dp(48)));
        promptRow.addView(prompt,new LinearLayout.LayoutParams(0,-2,1));
        voiceButton=iconButton("Dictate","mic",false,v->dictate());
        voiceButton.setOnLongClickListener(v->{inlineDictation.chooseLanguage();return true;});
        promptRow.addView(voiceButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        stopButton=iconButton("Stop","stop",true,v->{if(state.optBoolean("busy"))agentAction(AgentService.ACTION_CANCEL);else cancelConnection();});stopButton.setBackground(Ui.tappable(this,shape(DeskStyle.PRIMARY,24),DeskStyle.isDark(this)));
        promptRow.addView(stopButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        send=iconButton("Send message","send",true,v->composerAction());send.setBackground(Ui.tappable(this,shape(DeskStyle.PRIMARY,24),DeskStyle.isDark(this)));
        promptRow.addView(send,new LinearLayout.LayoutParams(dp(48),dp(48)));
        composer.addView(promptRow,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout controls=row();controls.setGravity(Gravity.CENTER_VERTICAL);
        modeLabel=controlChip("Ask","plan",v->modePicker());modeLabel.setCompoundDrawables(null,null,null,null);
        modeLabel.setSingleLine(true);modeLabel.setTextSize(12);modeLabel.setPadding(dp(4),0,dp(4),0);modeLabel.setMinWidth(dp(48));modeLabel.setMaxWidth(dp(64));
        controls.addView(modeLabel,new LinearLayout.LayoutParams(-2,dp(48)));
        modelLabel=controlChip("Model","model",v->modelPicker());modelLabel.setSingleLine(true);modelLabel.setTextSize(12);modelLabel.setGravity(Gravity.CENTER);
        modelLabel.setCompoundDrawables(null,null,null,null);modelLabel.setPadding(dp(4),0,dp(4),0);modelLabel.setOnLongClickListener(v->{effortPicker();return true;});
        controls.addView(modelLabel,new LinearLayout.LayoutParams(0,dp(48),1));
        effortLabel=controlChip("Auto","effort",v->effortPicker());effortLabel.setCompoundDrawables(null,null,null,null);
        effortLabel.setTextSize(12);effortLabel.setPadding(dp(2),0,dp(2),0);effortLabel.setGravity(Gravity.CENTER);effortLabel.setMaxLines(2);
        controls.addView(effortLabel,new LinearLayout.LayoutParams(dp(48),dp(48)));
        usageLabel=text("Usage",12,MUTED);usageLabel.setMaxLines(2);usageLabel.setEllipsize(TextUtils.TruncateAt.END);usageLabel.setGravity(Gravity.CENTER);
        usageLabel.setMinHeight(dp(48));usageLabel.setPadding(dp(4),dp(2),dp(4),dp(2));usageLabel.setBackground(DeskStyle.plain(this));
        usageLabel.setOnClickListener(v->usageDetails());usageLabel.setFocusable(true);
        controls.addView(usageLabel,new LinearLayout.LayoutParams(dp(getResources().getDisplayMetrics().widthPixels<dp(350)?80:100),dp(48)));composer.addView(controls);
        usageWarning=column();usageWarning.setPadding(dp(14),dp(10),dp(14),dp(10));usageWarning.setBackground(DeskStyle.field(this));
        usageWarningTitle=label("",14,RED);decorate(usageWarningTitle,"usage",RED,false);usageWarning.addView(usageWarningTitle);
        usageWarningDetail=text("",12,MUTED);usageWarning.addView(usageWarningDetail,lp(-1,-2,6,2));
        usageWarningAction=actionButton("View usage","chevron_right",false,v->usageDetails());usageWarning.addView(usageWarningAction);usageWarning.setOnClickListener(v->usageDetails());usageWarning.setFocusable(true);usageWarning.setVisibility(View.GONE);
        workspace.addView(usageWarning,lp(-1,-2,2,4));
        composerScroll = new ScrollView(this);composerScroll.setVerticalScrollBarEnabled(false);
        composerScroll.addView(composer,new FrameLayout.LayoutParams(-1,-2));
        workspace.addView(composerScroll,lp(-1,-2,4,8));
        updateHeader();
    }

    private void renderPage() {
        if(inlineDictation!=null)inlineDictation.onPause();if(tab!=0&&messageSpeech!=null)messageSpeech.stop();
        messageTimes.clear();messageSpeakers.clear();activityHeaders.clear();activityBodies.clear();
        renderId++;
        accountStatus = null; accountPrimary = null; accountCancel = null;
        accountDescription = null; accountUsage = null; accountCode = null; accountProgress = null;
        fileRows = null; changesOutput = null; workspaceStatus = null; fileListSignature = ""; workspaceReadAt = 0; workspaceDelay = 5000;
        body.removeAllViews(); feed = null; lastTranscript = ""; messageViews.clear();
        composerScroll.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        updateKeyboardLayout();
        updateHeader();
        if (tab == 0) buildPage();
        else if (tab == 1) filesPage();
        else if (tab == 2) changesPage();
        else if (tab == 3) previewPage();
        else accountPage();
    }

    private String chatTitle() {
        String active=state.optString("threadId");JSONObject sessions=state.optJSONObject("sessions");
        JSONArray rows=sessions==null?null:sessions.optJSONArray("rows");
        if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject item=rows.optJSONObject(i);if(item!=null&&active.equals(item.optString("id"))&&!item.optString("name").isEmpty())return item.optString("name");}
        JSONArray messages=state.optJSONArray("messages");
        if(messages!=null)for(int i=0;i<messages.length();i++){JSONObject item=messages.optJSONObject(i);if(item!=null&&"user".equals(item.optString("role"))){String title=item.optString("text").replace('\n',' ').trim();if(!title.isEmpty())return title.length()>80?title.substring(0,80):title;}}
        return project==null?"PocketAgent":project.getName();
    }
    private void hideKeyboard() {
        if(prompt!=null)((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(prompt.getWindowToken(),0);
    }
    private void navigate(int destination) {closeDrawer();hideKeyboard();tab=destination;renderPage();}
    private void workspaceMenu() {
        showMenu("Workspace",new String[]{project==null?"Choose project":project.getName(),"Agent · "+providerName(provider),"Live workspace"},
                new String[]{"folder","model","preview"},new Runnable[]{this::projectPicker,this::providerPicker,this::liveWorkspace});
    }
    private void newChat() {
        if(AppLock.isLocked(this))return;closeDrawer();state=AgentService.snapshot();
        if(!matchingSession()){connect();return;}
        if(state.optBoolean("busy")||sendPending){toast("Finish or stop this task before starting another chat.");return;}
        if(prompt.length()>0) {
            dialog().setTitle("Start a new chat?").setMessage("Your draft stays in the composer.")
                    .setPositiveButton("New chat",(d,w)->{agentAction(AgentService.ACTION_NEW_SESSION);clearAttachments();navigate(0);}).setNegativeButton("Cancel",null).show();
        } else {agentAction(AgentService.ACTION_NEW_SESSION);clearAttachments();navigate(0);}
    }
    private void moreMenu() {
        state=AgentService.snapshot();
        showMenu("Chat",new String[]{"Background tasks","Files","Changes","Preview","Workspace","Rename","Share","Archive","Delete","Environment"},
                new String[]{"tasks","files","changes","preview","terminal","edit","share","history","trash","settings"},
                new Runnable[]{()->{if(project==null)projectPicker();else SessionsActivity.open(this,project.getName(),true);},
                    ()->navigate(1),()->navigate(2),()->navigate(3),this::liveWorkspace,this::renameChat,this::shareChat,()->manageCurrentChat("archive"),()->manageCurrentChat("delete"),
                    ()->{if(project==null)projectPicker();else EnvironmentActivity.open(this,project.getName());}});
    }
    private void manageCurrentChat(String operation) {
        if(project==null){projectPicker();return;}
        openChatActions(state.optString("threadId"),chatTitle(),false,operation);
    }
    private void openChatActions(String id,String title,boolean archived,String operation) {
        if(project==null||AppLock.isLocked(this))return;closeDrawer();
        if(sessionSheet!=null)sessionSheet.dismiss();
        sessionSheet=SessionActionsSheet.open(this,project.getName(),id,title,archived,operation,()->{drawerIndexSignature="";refreshState();});
    }
    private void showMenu(String title,String[] names,String[] icons,Runnable[] actions) {
        if(AppLock.isLocked(this))return;hideKeyboard();
        LinearLayout content=column();content.setPadding(dp(12),dp(8),dp(12),dp(8));
        final AlertDialog[] holder={null};
        for(int i=0;i<names.length;i++){final int index=i;TextView item=menuRow(names[i],icons[i]);
            item.setOnClickListener(v->{if(holder[0]!=null)holder[0].dismiss();if(!AppLock.isLocked(this))actions[index].run();});content.addView(item,lp(-1,-2,0,0));}
        ScrollView list=new ScrollView(this);list.addView(content);
        AlertDialog sheet=dialog().setTitle(title).setView(list).setNegativeButton("Close",null).show();holder[0]=sheet;
        int maximum=(int)(getResources().getDisplayMetrics().heightPixels*.60f);
        content.measure(View.MeasureSpec.makeMeasureSpec(Math.min(getResources().getDisplayMetrics().widthPixels,dp(680))-dp(24),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        if(content.getMeasuredHeight()>maximum)list.getLayoutParams().height=maximum;
        bottomSheet(sheet);
    }
    private TextView menuRow(String name,String icon) {
        TextView item=text(name,15,INK);item.setGravity(Gravity.CENTER_VERTICAL);item.setMinHeight(dp(48));item.setPadding(dp(10),dp(10),dp(10),dp(10));
        item.setMaxLines(2);item.setEllipsize(TextUtils.TruncateAt.END);decorate(item,icon,MUTED,false);item.setCompoundDrawablePadding(dp(12));
        item.setBackground(DeskStyle.plain(this));item.setFocusable(true);return item;
    }
    private void openDrawer() {
        if(AppLock.isLocked(this)||drawerOverlay!=null)return;hideKeyboard();
        drawerOverlay=new FrameLayout(this);drawerOverlay.setClickable(true);
        View scrim=new View(this);scrim.setBackgroundColor(0x77000000);scrim.setContentDescription("Close menu");scrim.setOnClickListener(v->closeDrawer());
        drawerOverlay.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout panel=column();panel.setBackgroundColor(CARD);
        int top=dp(12),bottom=dp(12);WindowInsets inset=lockShell.getRootWindowInsets();
        if(inset!=null){if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=inset.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());top+=bars.top;bottom+=bars.bottom;}
            else {top+=inset.getSystemWindowInsetTop();bottom+=Math.min(inset.getSystemWindowInsetBottom(),dp(48));}}
        panel.setPadding(dp(12),top,dp(12),bottom);
        LinearLayout heading=row();TextView name=label("PocketAgent",21,INK);heading.addView(name,new LinearLayout.LayoutParams(0,dp(48),1));name.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(iconButton("Close menu","close",false,v->closeDrawer()),new LinearLayout.LayoutParams(dp(48),dp(48)));panel.addView(heading);
        drawerSearch=input("Search chats",true);drawerSearch.setTextSize(14);decorate(drawerSearch,"search",MUTED,false);panel.addView(drawerSearch,lp(-1,48,8,10));
        ScrollView menuScroll=new ScrollView(this);LinearLayout content=column();menuScroll.addView(content);panel.addView(menuScroll,new LinearLayout.LayoutParams(-1,0,1));
        String[] names={"New chat","Projects","Agents","Apps & tools"},icons={"new","folder","model","apps"};
        Runnable[] actions={this::newChat,this::projectPicker,this::providerPicker,()->openConnections(0)};
        for(int i=0;i<names.length;i++){final int index=i;TextView item=menuRow(names[i],icons[i]);item.setOnClickListener(v->{closeDrawer();actions[index].run();});content.addView(item);}
        LinearLayout chatsHeading=row();TextView recent=text("Chats",12,MUTED);recent.setGravity(Gravity.CENTER_VERTICAL);recent.setPadding(dp(10),0,0,0);
        chatsHeading.addView(recent,new LinearLayout.LayoutParams(0,dp(48),1));
        Button section=button(drawerArchived?"Archived":"Recent",false,null);section.setTextSize(12);section.setMinWidth(dp(72));
        section.setOnClickListener(v->{drawerArchived=!drawerArchived;section.setText(drawerArchived?"Archived":"Recent");drawerVisible=100;drawerSignature="";updateDrawer();refreshDrawerSessions();});
        chatsHeading.addView(section,new LinearLayout.LayoutParams(-2,dp(48)));content.addView(chatsHeading,lp(-1,-2,12,0));
        drawerRows=column();content.addView(drawerRows);

        View line=new View(this);line.setBackgroundColor(LINE);panel.addView(line,lp(-1,1,8,8));
        TextView account=menuRow("Account & settings","account");account.setOnClickListener(v->navigate(4));panel.addView(account);
        int width=Math.min(dp(320),(int)(getResources().getDisplayMetrics().widthPixels*.82f));
        drawerOverlay.addView(panel,new FrameLayout.LayoutParams(width,-1,Gravity.START));lockShell.addView(drawerOverlay,new FrameLayout.LayoutParams(-1,-1));
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        drawerSearch.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable e){drawerVisible=100;drawerSignature="";updateDrawer();main.removeCallbacks(drawerQuery);main.postDelayed(drawerQuery,450);}public void onTextChanged(CharSequence s,int a,int b,int c){}});
        drawerVisible=100;drawerSignature="";drawerIndexSignature="";updateDrawerIndex();updateDrawer();refreshDrawerSessions();
    }
    private JSONObject drawerState() {
        return project!=null&&project.getName().equals(state.optString("project"))&&provider.equals(state.optString("provider"))?state:new JSONObject();
    }
    private String drawerScope() {
        JSONObject current=drawerState();return provider+"\n"+(project==null?"":project.getName())+"\n"+current.optString("accountScopeToken");
    }
    private void updateDrawerIndex() {
        if(project==null||!resumed||isDestroyed()||AppLock.isLocked(this))return;
        final String scope=drawerScope();
        if(!scope.equals(drawerCacheScope)){drawerCacheScope=scope;drawerCached=new JSONArray();drawerIndexSignature="";drawerSignature="";}
        JSONObject captured=drawerState();JSONObject sessions=captured.optJSONObject("sessions");
        StringBuilder key=new StringBuilder(scope).append(captured.optString("threadId")).append(String.valueOf(sessions));
        JSONArray messages=captured.optJSONArray("messages");
        if(messages!=null)for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m!=null&&"user".equals(m.optString("role"))){key.append(m.optString("id")).append(m.optString("text"));break;}}
        final String signature=key.toString();if(signature.equals(drawerIndexSignature)||drawerIndexBusy)return;
        drawerIndexBusy=true;drawerIndexSignature=signature;final String selectedProvider=provider,selectedProject=project.getName();
        io.execute(()->{JSONArray result=new JSONArray();boolean success=false;
            try{JSONObject latest=AgentService.snapshot();
                if(selectedProvider.equals(latest.optString("provider"))&&selectedProject.equals(latest.optString("project"))
                        &&captured.optString("accountScopeToken").equals(latest.optString("accountScopeToken")))
                    LocalSessionIndex.update(getFilesDir(),selectedProvider,selectedProject,captured);
                result=LocalSessionIndex.load(getFilesDir(),selectedProvider,selectedProject,captured);success=true;
            }catch(Exception ignored){/* The live list remains usable if disk storage is unavailable. */}
            final JSONArray loaded=result;final boolean saved=success;main.post(()->{drawerIndexBusy=false;if(isDestroyed())return;
                if(scope.equals(drawerScope())&&saved){drawerCached=loaded;drawerSignature="";updateDrawer();}
                updateDrawerIndex();
            });
        });
    }
    private void refreshDrawerSessions() {
        drawerFetchPending=drawerOverlay!=null;
        if(drawerOverlay==null||AppLock.isLocked(this)||!matchingSession()||!"codex".equals(provider)||state.optBoolean("busy"))return;
        JSONObject sessions=state.optJSONObject("sessions");
        if(sessions!=null&&sessions.optBoolean("loading")){main.removeCallbacks(drawerQuery);main.postDelayed(drawerQuery,650);return;}
        drawerFetchPending=false;main.removeCallbacks(drawerQuery);
        sessionAction("refresh",AgentProtocol.object("archived",drawerArchived,"search",drawerSearch==null?"":drawerSearch.getText().toString().trim()));
    }
    private void updateDrawer() {
        if(drawerOverlay==null||drawerRows==null||AppLock.isLocked(this))return;
        JSONObject snapshot=drawerState(),sessions=snapshot.optJSONObject("sessions");
        String query=drawerSearch==null?"":drawerSearch.getText().toString().trim();
        JSONArray rows=LocalSessionIndex.combine(snapshot,drawerCacheScope.equals(drawerScope())?drawerCached:new JSONArray(),drawerArchived,query);
        String signature=rows.toString()+query+drawerArchived+drawerVisible+String.valueOf(sessions)+matchingSession();
        if(signature.equals(drawerSignature))return;drawerSignature=signature;drawerRows.removeAllViews();
        final String scope=drawerScope();int shown=0;
        for(int i=0;i<rows.length()&&shown<drawerVisible;i++){
            JSONObject item=rows.optJSONObject(i);if(item==null)continue;
            final String title=first(item.optString("name"),"New chat"),id=item.optString("id");if(id.isEmpty())continue;
            LinearLayout line=row();TextView entry=menuRow(title,"chat");entry.setSingleLine(true);entry.setTooltipText(title);
            if(id.equals(snapshot.optString("threadId")))line.setBackground(DeskStyle.field(this));
            entry.setOnClickListener(v->{state=AgentService.snapshot();if(!scope.equals(drawerScope())||AppLock.isLocked(this))return;
                if(state.optBoolean("busy")||sendPending){toast("Finish this task before switching chats.");return;}
                if(drawerArchived){openChatActions(id,title,true,"unarchive");return;}
                closeDrawer();sessionAction("resume",AgentProtocol.object("threadId",id));navigate(0);});
            line.addView(entry,new LinearLayout.LayoutParams(0,dp(48),1));
            line.addView(iconButton("Chat options: "+title,"more",false,v->{if(!scope.equals(drawerScope())||AppLock.isLocked(this))return;openChatActions(id,title,drawerArchived,"");}),new LinearLayout.LayoutParams(dp(48),dp(48)));
            drawerRows.addView(line);shown++;
        }
        if(shown==0){TextView note=text(query.isEmpty()?(drawerArchived?"No archived chats":"No chats yet"):"No matching chats",12,MUTED);note.setPadding(dp(10),dp(10),dp(10),dp(10));drawerRows.addView(note);}
        final boolean moreLocal=rows.length()>shown;
        boolean moreRemote=matchingSession()&&sessions!=null&&sessions.optBoolean("archived")==drawerArchived&&query.equals(sessions.optString("search"))&&sessions.optBoolean("hasMore");
        if(moreLocal||moreRemote){TextView more=menuRow("Load earlier chats","history");more.setOnClickListener(v->{if(moreLocal){drawerVisible+=100;drawerSignature="";updateDrawer();}else sessionAction("more",new JSONObject());});drawerRows.addView(more);}
        if(sessions!=null&&sessions.optBoolean("loading")){TextView loading=text("Loading chats…",12,MUTED);loading.setPadding(dp(10),dp(8),dp(10),dp(8));drawerRows.addView(loading);}
    }
    private void closeDrawer() {drawerFetchPending=false;main.removeCallbacks(drawerQuery);if(drawerOverlay!=null&&lockShell!=null)lockShell.removeView(drawerOverlay);drawerOverlay=null;drawerRows=null;drawerSearch=null;drawerSignature="";if(root!=null)root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);}
    @Override public void onBackPressed() {if(drawerOverlay!=null){closeDrawer();return;}if(tab!=0){navigate(0);return;}super.onBackPressed();}
    private void sessionAction(String operation,JSONObject payload) {
        if(AppLock.isLocked(this)||project==null||!"codex".equals(provider)){toast("Connect Codex to manage its chats.");return;}
        if(!matchingSession()&&!("resume".equals(operation)&&AgentService.canReconnect(this,provider,project.getName()))){toast("Connect Codex to open this chat. Your saved chat is still here.");return;}
        startService(agentIntent(AgentService.ACTION_SESSION).putExtra(AgentService.EXTRA_OPERATION,operation).putExtra(AgentService.EXTRA_PAYLOAD,payload.toString()));main.postDelayed(this::refreshState,150);
    }
    private void renameChat() {
        if(!matchingSession()||!"codex".equals(provider)){openSessions();return;}if(state.optBoolean("busy")){toast("Finish this task first.");return;}
        final String id=state.optString("threadId");EditText name=input("Chat name",true);name.setText(chatTitle());
        AlertDialog edit=dialog().setTitle("Rename chat").setView(padded(name)).setPositiveButton("Save",null).setNegativeButton("Cancel",null).show();
        edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{if(!id.equals(AgentService.snapshot().optString("threadId")))throw new IllegalStateException("The chat changed.");sessionAction("rename",CodexSessionControls.renameParams(id,name.getText().toString()));edit.dismiss();}catch(Exception invalid){name.setError(invalid.getMessage());}});
    }
    private void shareChat() {
        JSONArray messages=state.optJSONArray("messages");if(messages==null||messages.length()==0){toast("No messages to share yet.");return;}
        StringBuilder out=new StringBuilder();for(int i=0;i<messages.length()&&out.length()<100000;i++){JSONObject item=messages.optJSONObject(i);if(item!=null&&("user".equals(item.optString("role"))||"assistant".equals(item.optString("role"))))out.append(item.optString("role").equals("user")?"You":"Agent").append(":\n").append(item.optString("text")).append("\n\n");}
        final String text=out.toString();ScrollView preview=new ScrollView(this);preview.addView(padded(selectable(text,14,INK)));
        AlertDialog sheet=dialog().setTitle("Review before sharing").setView(preview).setPositiveButton("Share text",(d,w)->startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"Share conversation"))).setNegativeButton("Cancel",null).show();
        preview.getLayoutParams().height=Math.min(dp(380),getResources().getDisplayMetrics().heightPixels/2);bottomSheet(sheet);
    }

    private void updateHeader() {
        if (composerScroll != null) composerScroll.setVisibility(tab == 0 && project != null && ContainerRuntime.isWorkspaceInstalled(this) ? View.VISIBLE : View.GONE);
        projectLabel.setText(tab==0?chatTitle():new String[]{"Chat","Files","Changes","Preview","Account"}[tab]);
        String p = state.optBoolean("connected") || state.optBoolean("installing") ? state.optString("provider",provider) : provider;
        String status=state.optBoolean("recoveringSession")||state.optBoolean("sessionOpening")?"Opening…"
                :state.optBoolean("busy")?"Working…":state.optBoolean("authenticating")?"Sign in"
                :state.optBoolean("connecting")||connectionRequestPending?"Connecting…":state.optBoolean("connected")?"":"Disconnected";
        connectionLabel.setText(pendingAgentSwitch.isEmpty()?providerName(p)+(status.isEmpty()?"":" · "+status):"Switching to "+providerName(switchTargetProvider)+"…");
        connectionLabel.setContentDescription("Agent: "+providerName(p)+". Tap to switch agent.");
        navigationButton.setContentDescription(tab==0?"Open menu":"Back to chat");
        decorateComposer(navigationButton,tab==0?"menu":"chevron_left",INK);
        updateSendAction();
        updateComposerControls();
    }

    private boolean hasComposerContent() {
        return prompt!=null&&(!prompt.getText().toString().trim().isEmpty()||imageAttachments.length()>0||!fileAttachments.isEmpty()
                ||!attachedPath.isEmpty()||!selectedSkillPath.isEmpty()||!selectedAppId.isEmpty());
    }
    private void updateSendAction() {
        if(send==null)return;
        boolean working=state.optBoolean("busy")||state.optBoolean("installing")||state.optBoolean("connecting")||state.optBoolean("authenticating")
                ||state.optBoolean("sessionOpening")||LinuxService.isBusy()||connectionRequestPending||sendPending;
        if(stopButton!=null)stopButton.setVisibility(working?View.VISIBLE:View.GONE);send.setVisibility(working?View.GONE:View.VISIBLE);send.setText("");
        boolean voice=!hasComposerContent()&&"codex".equals(provider)&&matchingSession();
        decorateComposer(send,voice?"voice":"send",DeskStyle.PRIMARY_TEXT);
        String description=voice?"Start voice conversation":matchingSession()?"Send message":"Connect agent";
        send.setContentDescription(description);send.setTooltipText(description);
        boolean dictating=inlineDictation!=null&&inlineDictation.active();boolean switching=!pendingAgentSwitch.isEmpty();send.setEnabled(!dictating&&!switching);send.setAlpha(dictating||switching?.45f:1f);
        if(switching){stopButton.setVisibility(View.GONE);send.setVisibility(View.VISIBLE);}
        if(voiceButton!=null){voiceButton.setEnabled(!sendPending);voiceButton.setAlpha(sendPending?.45f:1f);decorateComposer(voiceButton,dictating?"stop":"mic",INK);voiceButton.setContentDescription(dictating?"Stop dictation":"Dictate. Hold to choose language");}
    }
    private void composerAction() {
        if(inlineDictation!=null&&inlineDictation.active())return;
        state=AgentService.snapshot();
        if(!hasComposerContent()&&"codex".equals(provider)&&matchingSession())openVoice();else sendPrompt();
    }

    private void updateKeyboardLayout() {
        if(brandBar!=null)brandBar.setVisibility(View.VISIBLE);
        boolean compact=keyboardVisible||compactWindow;
        if(prompt!=null)prompt.setMaxLines(compact?3:5);
        if(usageWarning!=null){usageWarningDetail.setVisibility(compact?View.GONE:View.VISIBLE);usageWarningAction.setVisibility(compact?View.GONE:View.VISIBLE);
            usageWarning.setPadding(dp(14),compact?0:dp(10),dp(14),compact?0:dp(10));usageWarningTitle.setMinHeight(compact?dp(48):0);usageWarningTitle.setGravity(Gravity.CENTER_VERTICAL);}
        if(composerScroll!=null&&root!=null&&root.getHeight()>0&&composerScroll.getWidth()>0){
            int usable=root.getHeight()-root.getPaddingTop()-root.getPaddingBottom()-dp(60);
            int warning=usageWarning!=null&&usageWarning.getVisibility()==View.VISIBLE?(compact?dp(48):Math.max(dp(120),usageWarning.getMeasuredHeight())):0;
            int limit=Math.max(dp(72),Math.min(dp(280),usable-warning-dp(64)));
            composer.measure(View.MeasureSpec.makeMeasureSpec(composerScroll.getWidth(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
            int height=Math.min(composer.getMeasuredHeight(),limit);
            if(composerScroll.getLayoutParams().height!=height){composerScroll.getLayoutParams().height=height;composerScroll.requestLayout();}
            composerScroll.setFillViewport(false);
        }
    }

    private void refreshState() {
        state = AgentService.snapshot();
        acknowledgeAgentSwitch();
        String scope=currentDictationScope();if(!scope.equals(interactionScope)){interactionScope=scope;if(messageSpeech!=null)messageSpeech.cancelScope();if(inlineDictation!=null)inlineDictation.onPause();expandedActivities.clear();}
        acknowledgePrompt();
        if (state.optBoolean("connecting") || state.optBoolean("installing") || state.optBoolean("authenticating")
                || state.optBoolean("connected") || state.optBoolean("error")) connectionRequestPending = false;
        if (connectAfterSetup && ContainerRuntime.isWorkspaceInstalled(this) && !LinuxService.isBusy() && registered && !AppLock.isLocked(this)) {
            connectAfterSetup = false;
            connect();
            return;
        }
        if (connectAfterSetup && LinuxService.lastWasError() && !LinuxService.isBusy()) connectAfterSetup = false;
        updateHeader();
        updateDrawerIndex();
        updateDrawer();
        if(drawerFetchPending)refreshDrawerSessions();
        updateToolPicker();
        if (tab == 0) {
            if (feed == null || !ContainerRuntime.isWorkspaceInstalled(this)) {
                buildPageRefresh();
            } else renderTranscript();
        }
        else if (tab == 4) updateAccountControls();
        else if (tab == 1 || tab == 2) readWorkspace(false);
        // Account fields update in place: no touch controls are replaced during progress delivery.
    }

    private ConnectionFlow.Step connectionStep() {
        boolean matching = provider.equals(state.optString("provider", provider))
                && project != null && project.getName().equals(state.optString("project", project.getName()));
        return ConnectionFlow.next(ContainerRuntime.isWorkspaceInstalled(this), LinuxService.isBusy(), project != null,
                matching && state.optBoolean("connected"), state.optBoolean("installing"),
                matching && (state.optBoolean("connecting") || state.optBoolean("sessionOpening")), matching && state.optBoolean("authenticating"),
                connectionRequestPending, state.optBoolean("error"), matching ? state.optString("authUrl") : "");
    }

    private void showConnectionProgress() {
        tab = 0; renderPage();
        scroll.post(() -> scroll.smoothScrollTo(0, 0));
    }

    private void buildPageRefresh() { body.removeAllViews(); feed = null; lastTranscript = ""; messageViews.clear(); buildPage(); }

    private void buildPage() {
        if (!ContainerRuntime.isWorkspaceInstalled(this)) {
            LinearLayout hero = card();
            hero.addView(text("YOUR IDE, IN YOUR POCKET", 11, ACCENT));
            hero.addView(label("Big ideas.\nSmall screen.", 34, INK), lp(-1, -2, 12, 12));
            hero.addView(text("A native place to build with AI. Your Ubuntu tools run quietly on this phone.", 15, MUTED));
            body.addView(hero, lp(-1, -2, 0, 14));
            LinearLayout setup = card();
            setup.addView(label(LinuxService.isBusy() ? "Preparing your workspace" : "Let's get you set up", 19, INK));
            String detail = LinuxService.lastDetail();
            setup.addView(text(LinuxService.isBusy() || LinuxService.lastWasError()
                    ? safe(LinuxService.lastMessage()) + "\n" + safe(detail)
                    : "Ubuntu · Git · Node.js · Python · build tools\n\nOne-time download over Wi-Fi. Android 10+ and an ARM64 phone are required. Keep several GB free; larger projects need more.", 14,
                    LinuxService.lastWasError() ? RED : MUTED), lp(-1, -2, 10, 10));
            if (LinuxService.isBusy()) {
                ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                int value = LinuxService.lastProgress(); progress.setIndeterminate(value < 0); progress.setProgress(Math.max(0, value)); setup.addView(progress);
                setup.addView(actionButton("Pause setup", "stop", false, v -> cancelConnection()));
            } else setup.addView(actionButton("Set up workspace", "settings", true, v -> setupWorkspace()));
            body.addView(setup, lp(-1, -2, 0, 12));
            body.addView(text("Your account stays yours. Sign in with the agent's official flow after setup. Provider plans and limits still apply.", 12, MUTED));
            return;
        }
        if (project == null) {
            LinearLayout intro = card(); intro.addView(label("Your workspace is ready", 24, INK));
            intro.addView(text("Start a project or bring a repository. Then tell your agent what to build.", 15, MUTED), lp(-1, -2, 10, 14));
            intro.addView(actionButton("Create project", "new", true, v -> createProject())); intro.addView(actionButton("Import from Git", "folder", false, v -> cloneProject()));
            body.addView(intro, lp(-1, -2, 0, 12));
            return;
        }
        feed = column(); body.addView(feed); renderTranscript();
    }

    private void renderTranscript() {
        if (feed == null) return;
        JSONArray messages = state.optJSONArray("messages");
        JSONObject permission = state.optJSONObject("permission");
        boolean nearBottom = scroll.getChildAt(0).getHeight() - scroll.getHeight() - scroll.getScrollY() < dp(180);
        int firstMessage = messages == null ? 0 : Math.max(0, messages.length() - 40);
        StringBuilder structure = new StringBuilder(String.valueOf(permission) + state.optString("status") + state.optString("authUrl") + state.optString("authCode")
                + state.optBoolean("busy") + state.optBoolean("connected") + state.optBoolean("accountConnected")
                + state.optBoolean("recoveringSession") + state.optBoolean("sessionOpening") + connectionStep());
        JSONObject controls = state.optJSONObject("controls");
        if(controls!=null)structure.append(controls.optString("operation")).append(controls.optString("error"));
        if (messages != null) for (int i = firstMessage; i < messages.length(); i++) {
            JSONObject m=messages.optJSONObject(i);if(m!=null){structure.append('|').append(m.optString("id",String.valueOf(i))).append(':').append(m.optString("role")).append(m.optJSONArray("media")).append(m.optJSONArray("attachments")).append(m.optBoolean("streaming")).append(m.optLong("completedAt"));
                JSONObject activity=m.optJSONObject("activity");if(activity!=null)structure.append(activity.optString("kind")).append(activity.optString("status"));
                if(!m.optBoolean("streaming")&&!state.optBoolean("busy"))structure.append(m.optString("text").hashCode());}
        }
        String signature = structure.toString();
        if (signature.equals(lastTranscript)) {
            // Streaming changes only the affected native text view. Keep scroll, focus and composer intact.
            if (messages != null) for (int i = firstMessage; i < messages.length(); i++) {
                JSONObject m = messages.optJSONObject(i); if (m == null) continue;
                TextView t = messageViews.get(m.optString("id", String.valueOf(i)));
                String value = m.optString("text"); if (t != null && !value.equals(t.getTag())) {
                    t.setTag(value); t.setText("assistant".equals(m.optString("role")) ? ChatMarkdown.render(value,FIELD,ACCENT,this::openChatLink) : value);
                }
            }
            updateActivityViews(messages);updateMessageFooters();
            if (nearBottom) scroll.post(() -> { if (!isFinishing()) scroll.fullScroll(View.FOCUS_DOWN); });
            return;
        }
        lastTranscript = signature; transcriptId++;messageViews.clear();messageTimes.clear();messageSpeakers.clear();activityHeaders.clear();activityBodies.clear();
        artifactCardsRendered=0;thumbnailsRendered=0;
        feed.removeAllViews();
        if(controls!=null && (!controls.optString("error").isEmpty() || !controls.optString("operation").isEmpty())) {
            LinearLayout controlCard=card();controlCard.addView(label("Codex action",15,INK));
            controlCard.addView(text(first(controls.optString("error"),controls.optString("status"),"Working…"),13,controls.optString("error").isEmpty()?MUTED:RED));
            feed.addView(controlCard,lp(-1,-2,0,10));
        }
        String status = userStatus();
        boolean waiting = state.optBoolean("busy") || ConnectionFlow.waiting(connectionStep()) || state.optBoolean("sessionOpening");
        String authUrl = state.optString("authUrl");
        if (!state.optBoolean("connected") || state.optBoolean("error")) {
            LinearLayout session = card(); session.setPadding(dp(14), dp(12), dp(14), dp(12));
            String title = state.optBoolean("recoveringSession") ? "Opening a new chat"
                    : !authUrl.isEmpty() ? "Finish signing in"
                    : state.optBoolean("busy") ? providerName(provider) + " is working"
                    : state.optBoolean("accountConnected") ? "Open your chat" : "Connect " + providerName(provider);
            session.addView(label(title, 17, INK));
            session.addView(text(status, 13, state.optBoolean("error") ? RED : MUTED), lp(-1, -2, 6, 6));
            if (waiting) {
                ProgressBar busy = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                busy.setIndeterminate(true); session.addView(busy);
            }
            if (!authUrl.isEmpty()) {
                String code = state.optString("authCode");
                if (!code.isEmpty()) session.addView(selectable("Sign-in code: " + code, 16, ACCENT));
                session.addView(actionButton("Continue sign-in", "account", true, v -> openAuth(authUrl)));
            }
            if (state.optBoolean("error")) session.addView(actionButton("Error details", "file", false, v -> diagnostics()));
            feed.addView(session, lp(-1, -2, 0, 12));
        }
        if ((messages == null || messages.length() == 0) && state.optBoolean("connected") && !waiting) {
            LinearLayout welcome = column(); welcome.setPadding(dp(8), dp(16), dp(8), dp(12));
            welcome.addView(label("What would you like to build?", 21, INK));
            welcome.addView(actionButton("Build a website", "preview", false, v -> fillPrompt("Build a beautiful, responsive website in this project. First propose a brief plan, then implement it and explain how to preview it.")));
            welcome.addView(actionButton("Explain this project", "folder", false, v -> fillPrompt("Explore this project. Explain its architecture and suggest the next useful improvement. Do not change files yet.")));
            feed.addView(welcome, lp(-1, -2, 0, 12));
        } else if (messages != null) {
            if (firstMessage > 0) feed.addView(text("Earlier messages are in Chats.", 12, MUTED), lp(-1, -2, 0, 12));
            for (int i = firstMessage; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i); if (message == null) continue;
            String role = message.optString("role", "assistant");
            String messageBody = message.optString("text");
            if(role.equals("tool")) {
                addAgentActivity(message);
                if(message.optJSONArray("media")!=null)addRemoteMedia(feed,messageBody,message.optJSONArray("media"));
                if(message.optJSONArray("attachments")!=null)addArtifactCards(feed,messageBody,message.optJSONArray("attachments"));
                continue;
            }
            if (role.equals("system") && technicalMessage(messageBody)) {
                feed.addView(actionButton("Technical details", "file", false, v -> details("Agent details", messageBody)), lp(-1, -2, 0, 8));
                continue;
            }
            LinearLayout bubble = column(); bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
            if (role.equals("user")) bubble.setBackground(DeskStyle.field(this));
            else if (!role.equals("assistant")) bubble.addView(label("PocketAgent", 11, MUTED));
            final String messageId=message.optString("id",String.valueOf(i)),scope=currentDictationScope();
            boolean streaming=message.optBoolean("streaming")||(!message.has("streaming")&&state.optBoolean("busy")&&"assistant".equals(role)&&i==messages.length()-1);
            if("assistant".equals(role)&&!streaming){
                RichMessageView rich=new RichMessageView(this,messageBody,DeskStyle.isDark(this),this::openChatLink,new RichMessageView.Actions(){
                    public void copy(String code){if(scope.equals(currentDictationScope()))copyText(code);}
                    public void save(String name,String mime,String code){if(scope.equals(currentDictationScope()))saveCode(name,mime,code);}
                });bubble.addView(rich,new LinearLayout.LayoutParams(-1,-2));
            }else{
                TextView messageText=selectable(messageBody,16,INK);messageText.setTag(messageBody);
                if("assistant".equals(role)){messageText.setText(ChatMarkdown.render(messageBody,FIELD,ACCENT,this::openChatLink));messageText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());}
                messageText.setOnLongClickListener(v->{if(scope.equals(currentDictationScope()))messageMenu(currentMessage(messageId,messageBody),role);return true;});
                messageViews.put(messageId,messageText);bubble.addView(messageText,lp(-1,-2,0,0));
            }
            if(!streaming){
                addArtifactCards(bubble,messageBody,message.optJSONArray("attachments"));
                if("assistant".equals(role))addRemoteMedia(bubble,messageBody,message.optJSONArray("media"));
                if("assistant".equals(role)||"user".equals(role))addMessageActions(bubble,messageId,messageBody,role,message,scope);
            }
            LinearLayout.LayoutParams messageSpace=lp(-1,-2,0,16);if(role.equals("user")){messageSpace.leftMargin=dp(36);messageSpace.gravity=Gravity.END;}else bubble.setPadding(dp(2),dp(8),dp(2),dp(8));
            feed.addView(bubble, messageSpace);
            }
        }
        if(state.optBoolean("busy") && (permission==null||permission.length()==0)) {
            LinearLayout activity=row();ProgressBar progress=new ProgressBar(this);activity.addView(progress,new LinearLayout.LayoutParams(dp(16),dp(16)));
            TextView note=text("Working…",12,MUTED);note.setPadding(dp(10),dp(8),0,dp(8));activity.addView(note);feed.addView(activity,lp(-1,-2,0,8));
        }
        if (permission != null && permission.length() > 0) {
            LinearLayout approval = card(); approval.setBackground(outline(CARD, AMBER, 18));
            approval.addView(label("Approval needed", 18, AMBER));
            approval.addView(selectable(permission.optString("title", "Agent request") + "\n" + permission.optString("detail"), 14, INK), lp(-1, -2, 10, 10));
            if (permission.optJSONObject("elicitation") != null) {
                approval.addView(actionButton("Review MCP request", "settings", true, v -> reviewMcpRequest(permission)));
                approval.addView(actionButton("Decline request", "stop", false, v -> reply(permission.optString("id"), "decline", "")));
            } else {
            JSONArray options = permission.optJSONArray("options");
            if (options != null) for (int i = 0; i < options.length(); i++) {
                JSONObject option = options.optJSONObject(i); if (option == null) continue;
                String optionId = option.optString("id");
                approval.addView(button(option.optString("label", optionId), false, v -> reply(permission.optString("id"), optionId, "")));
            }
            if (permission.optBoolean("allowText")) {
                EditText answer = input("Your answer", false); approval.addView(answer);
                approval.addView(button("Send answer", true, v -> reply(permission.optString("id"), "text", answer.getText().toString())));
            }
            }
            feed.addView(approval, lp(-1, -2, 0, 12));
        }
        updateActivityViews(messages);updateMessageFooters();
        if (nearBottom) scroll.post(() -> { if (!isFinishing()) scroll.fullScroll(View.FOCUS_DOWN); });
    }

    private void setupWorkspace() {
        if (state.optBoolean("connected") || state.optBoolean("installing")) { toast("Disconnect the agent before workspace setup."); return; }
        showConnectionProgress();
        if (LinuxService.isBusy()) return;
        notificationPermission();
        try { startForegroundService(new Intent(this, LinuxService.class).setAction(LinuxService.ACTION_SETUP_WORKSPACE)); }
        catch (RuntimeException blocked) {
            connectAfterSetup = false;
            error("Android could not start workspace setup: " + safe(blocked.getMessage()));
            return;
        }
        main.postDelayed(this::refreshState, 400);
    }

    private void providerPicker() {
        if(AppLock.isLocked(this))return;hideKeyboard();state=AgentService.snapshot();
        LinearLayout content=column();content.setPadding(dp(16),dp(4),dp(16),dp(12));
        content.addView(text("Shared project files. Separate chats, accounts and usage. One agent works at a time.",13,MUTED),lp(-1,-2,0,12));
        final AlertDialog[] holder={null};
        for(String id:AgentCatalog.IDS){
            String unavailable=AgentInstaller.unavailableReason(id);
            boolean active=id.equals(state.optString("provider"))&&project!=null&&project.getName().equals(state.optString("project"));
            String detail=!unavailable.isEmpty()?"Unavailable in this build":active&&state.optBoolean("connected")?"Connected to this project"
                    :project!=null&&AgentService.canReconnect(this,id,project.getName())?"Previously connected · reconnect automatically"
                    :AgentInstaller.isInstalled(this,id)?"Installed · connect account":"Set up and connect";
            LinearLayout item=column();item.setPadding(dp(12),dp(10),dp(12),dp(10));item.setMinimumHeight(dp(64));item.setBackground(id.equals(provider)?DeskStyle.field(this):DeskStyle.plain(this));
            TextView name=label(AgentCatalog.name(id)+(id.equals(provider)?" · Selected":""),16,INK);item.addView(name);
            item.addView(text(detail,12,MUTED),lp(-1,-2,4,0));item.setFocusable(true);
            item.setContentDescription(AgentCatalog.name(id)+". "+detail);
            item.setOnClickListener(v->{if(holder[0]!=null)holder[0].dismiss();if(!unavailable.isEmpty()){details(AgentCatalog.name(id),unavailable);return;}switchAgent(id);});
            content.addView(item,lp(-1,-2,0,4));
        }
        ScrollView list=new ScrollView(this);list.addView(content);
        AlertDialog sheet=dialog().setTitle("Coding agent").setView(list).setNegativeButton("Close",null).show();holder[0]=sheet;
        content.measure(View.MeasureSpec.makeMeasureSpec(getResources().getDisplayMetrics().widthPixels-dp(32),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int maximum=(int)(getResources().getDisplayMetrics().heightPixels*.60f);if(content.getMeasuredHeight()>maximum)list.getLayoutParams().height=maximum;bottomSheet(sheet);
    }

    private boolean switchingBlocked() {
        state=AgentService.snapshot();
        if(!pendingAgentSwitch.isEmpty()||sendPending||resolvingReferences||connectionRequestPending||LinuxService.isBusy()){toast("Wait for the current task to finish.");return true;}
        if(!state.optBoolean("switchAllowed",!state.optBoolean("busy")&&!state.optBoolean("installing")&&!state.optBoolean("connecting")&&!state.optBoolean("authenticating")&&!state.optBoolean("sessionOpening"))){toast(state.optString("switchBlockedReason","Finish or stop the current task before switching agents."));return true;}
        return false;
    }

    private void switchAgent(String next) {
        if(AppLock.isLocked(this)||!AgentCatalog.isValid(next)||switchingBlocked())return;
        if(next.equals(provider)){return;}
        if(project==null){provider=next;prefs.edit().putString("provider",provider).apply();renderPage();return;}
        requestWorkspaceSwitch(next,project);
    }

    private void requestWorkspaceSwitch(String next,File target) {
        if(AppLock.isLocked(this)||!AgentCatalog.isValid(next)||target==null||switchingBlocked())return;
        if(inlineDictation!=null)inlineDictation.onPause();if(messageSpeech!=null)messageSpeech.cancelScope();
        saveAgentDraft();
        pendingAgentSwitch=java.util.UUID.randomUUID().toString();switchTargetProvider=next;switchTargetProject=target.getName();
        switchOriginProvider=state.optString("provider","codex");switchOriginProject=state.optString("project","my-project");switchOriginAccount=state.optString("accountScopeToken");
        dispatchAgentSwitch();updateHeader();
    }

    private void dispatchAgentSwitch() {
        if(pendingAgentSwitch.isEmpty()||AppLock.isLocked(this))return;
        Intent action=new Intent(this,AgentService.class).setAction(AgentService.ACTION_SWITCH)
                .putExtra(AgentService.EXTRA_PROVIDER,switchTargetProvider).putExtra(AgentService.EXTRA_PROJECT,switchTargetProject)
                .putExtra(AgentService.EXTRA_EXPECTED_PROVIDER,switchOriginProvider)
                .putExtra(AgentService.EXTRA_EXPECTED_PROJECT,switchOriginProject)
                .putExtra(AgentService.EXTRA_EXPECTED_ACCOUNT_TOKEN,switchOriginAccount)
                .putExtra(AgentService.EXTRA_SWITCH_ID,pendingAgentSwitch);
        try{startService(action);}catch(RuntimeException failure){pendingAgentSwitch="";toast("Could not switch agent. Please try again.");}
        main.postDelayed(this::refreshState,150);main.removeCallbacks(retryAgentSwitch);main.postDelayed(retryAgentSwitch,10000);
    }

    private void acknowledgeAgentSwitch() {
        if(pendingAgentSwitch.isEmpty()||!pendingAgentSwitch.equals(state.optString("switchRequestId")))return;
        String failure=state.optString("switchError");
        if(!failure.isEmpty()){pendingAgentSwitch="";main.removeCallbacks(retryAgentSwitch);toast(failure);return;}
        if(!switchTargetProvider.equals(state.optString("provider"))||!switchTargetProject.equals(state.optString("project")))return;
        pendingAgentSwitch="";main.removeCallbacks(retryAgentSwitch);
        File target=new File(ContainerRuntime.workspaceRoot(this),switchTargetProject);
        try{WorkspaceTools.selectProject(this,target);}catch(Exception missing){toast("Could not open this project.");return;}
        saveAgentDraft();project=target;folder="";provider=switchTargetProvider;rememberProjectAgent();
        boolean shouldConnect=connectAfterProject;connectAfterSetup=false;connectAfterProject=false;reconnectScope=provider+":"+project.getName();
        restoreAgentDraft();drawerCached=new JSONArray();drawerCacheScope="";drawerIndexSignature="";drawerSignature="";
        tab=0;renderPage();
        if(shouldConnect&&!state.optBoolean("connected")&&!state.optBoolean("connecting"))main.post(this::connect);
    }

    private void rememberProjectAgent(){android.content.SharedPreferences.Editor edit=prefs.edit().putString("provider",provider);if(project!=null)edit.putString("project_agent_"+project.getName(),provider);edit.apply();}

    private void initializeAgentDraft(){
        if(project==null)return;String key=AgentDraftState.key(provider,project.getName());
        if(!key.isEmpty()&&prefs.contains("draft")){if(!prefs.contains(key))saveAgentDraft();prefs.edit().remove("draft").apply();}
        restoreAgentDraft();
    }
    private void saveAgentDraft(){
        if(prompt==null||prefs==null||project==null)return;String key=AgentDraftState.key(provider,project.getName());if(key.isEmpty())return;
        String value=AgentDraftState.encode(provider,project.getName(),prompt.getText().toString(),imageAttachments,fileAttachments,attachedPath,selectedSkillPath,selectedSkillName,selectedAppId,selectedAppName);
        if(!value.isEmpty())prefs.edit().putString(key,value).apply();
    }
    private void restoreAgentDraft(){
        if(prompt==null||project==null)return;
        AgentDraftState.State saved=AgentDraftState.decode(provider,project.getName(),prefs.getString(AgentDraftState.key(provider,project.getName()),""));
        imageAttachments=saved.imageAttachments();fileAttachments.clear();fileAttachments.addAll(saved.fileAttachments);attachedPath=saved.attachedPath;
        selectedSkillPath=saved.selectedSkillPath;selectedSkillName=saved.selectedSkillName;selectedAppId=saved.selectedAppId;selectedAppName=saved.selectedAppName;
        pendingReference=null;setDraft(saved.prompt);
    }

    private void connect() {
        if(!pendingAgentSwitch.isEmpty())return;
        state = AgentService.snapshot();
        ConnectionFlow.Step step = connectionStep();
        if (step == ConnectionFlow.Step.SETUP || step == ConnectionFlow.Step.WAIT_SETUP) {
            connectAfterSetup = true;
            setupWorkspace();
            return;
        }
        if (step == ConnectionFlow.Step.PROJECT) {
            connectAfterProject = true; showConnectionProgress(); createProject(); return;
        }
        if (step == ConnectionFlow.Step.WAIT_AGENT) { showConnectionProgress(); return; }
        if (step == ConnectionFlow.Step.SIGN_IN) { openAuth(state.optString("authUrl")); return; }
        if (step == ConnectionFlow.Step.READY) { agentAction(AgentService.ACTION_REFRESH); return; }
        String unavailable = AgentInstaller.unavailableReason(provider);
        if (unavailable != null && !unavailable.isEmpty()) { error(unavailable); return; }
        showConnectionProgress();
        notificationPermission();
        if (provider.equals("codex")) {
            dialog().setTitle("Choose Codex access")
                    .setMessage("Restricted mode asks before tools and uses Codex's workspace sandbox. Some phones cannot run that sandbox.\n\nUbuntu access allows changes throughout PocketAgent's private Ubuntu environment, including other projects and saved agent sign-ins. It allows network access. PRoot is not a security sandbox. Broad phone-folder mounts are disabled. Import or save individual files only through Android's picker.")
                    .setPositiveButton("Restricted", (d,w) -> connectNow(false))
                    .setNeutralButton("Ubuntu access", (d,w) -> connectNow(true))
                    .setNegativeButton("Cancel", null).show();
        } else connectNow(false);
    }

    private void connectNow(boolean fullAccess) {
        if (connectionRequestPending) return;
        connectionRequestPending = true;
        Intent intent = agentIntent(AgentService.ACTION_CONNECT);
        intent.putExtra(AgentService.EXTRA_FULL_ACCESS, fullAccess);
        showConnectionProgress();
        try { startForegroundService(intent); }
        catch (RuntimeException blocked) {
            connectionRequestPending = false;
            error("Android could not start " + providerName(provider) + ": " + safe(blocked.getMessage()));
            renderPage(); return;
        }
        main.postDelayed(this::refreshState, 350);
        main.postDelayed(() -> {
            if (!connectionRequestPending || isFinishing() || isDestroyed()) return;
            connectionRequestPending = false;
            refreshState();
            error("Android did not report a connection status. Tap Connect to retry; any workspace or agent setup error will appear here.");
        }, 10000);
    }

    private void cancelConnection() {
        connectAfterSetup = false; connectAfterProject = false; connectionRequestPending = false;
        if (LinuxService.isBusy()) startService(new Intent(this, LinuxService.class).setAction(LinuxService.ACTION_STOP));
        else agentAction(AgentService.ACTION_DISCONNECT);
        main.postDelayed(this::refreshState, 250);
    }

    private Intent agentIntent(String action) {
        return new Intent(this, AgentService.class).setAction(action)
                .putExtra(AgentService.EXTRA_PROVIDER, provider)
                .putExtra(AgentService.EXTRA_PROJECT, project == null ? "" : project.getName());
    }
    private void agentAction(String action) { startService(agentIntent(action)); main.postDelayed(this::refreshState, 150); }

    private void sendPrompt() {sendPrompt(false);}
    private void sendPrompt(boolean referencesReady) {
        if (AppLock.isLocked(this)) return;
        if(resolvingReferences){toast("Checking attached context…");return;}
        if(sendPending){toast("Waiting for the agent to accept your message.");return;}
        String commandText = prompt.getText().toString().trim();
        if (commandText.equals("/") || ComposerTokens.leadingCommand(commandText)!=null) { dispatchSlash(commandText); return; }
        state = AgentService.snapshot();
        if (!state.optBoolean("connected")) { connect(); return; }
        if (project == null || !project.getName().equals(state.optString("project")) || !provider.equals(state.optString("provider"))) {
            toast("This session belongs to a different project or agent. Disconnect and reconnect before sending."); return;
        }
        String value = prompt.getText().toString().trim();
        if (value.isEmpty()&&imageAttachments.length()==0&&fileAttachments.isEmpty()&&attachedPath.isEmpty()) { toast("Write a message first."); return; }
        if (state.optBoolean("busy")) { toast("An agent task is already running."); return; }
        if(!referencesReady && resolveReferences(value))return;
        if(imageAttachments.length()>0 && (!"codex".equals(provider)||!state.optBoolean("imageInputSupported"))) {
            toast("The selected model has not advertised image input. Choose a supported model or remove the images. Your draft is still here.");return;
        }
        JSONObject toolState = state.optJSONObject("integrations");
        if (toolState != null && CodexIntegrations.mutates(toolState.optString("operation"))) { toast("Wait for the tool or connection change to finish. Your draft is still here."); return; }
        if ((!selectedSkillPath.isEmpty() && (toolState == null || !toolState.optBoolean("skillsKnown") || toolState.optBoolean("skillsLoading")))
                || (!selectedAppId.isEmpty() && (toolState == null || !toolState.optBoolean("appsKnown") || toolState.optBoolean("appsLoading")))) {
            toast("Wait for your attached tools to finish refreshing, or choose them again in Tools. Your draft is still here."); return;
        }
        if (!selectedSkillPath.isEmpty()) {
            JSONObject integrations = state.optJSONObject("integrations");
            JSONArray skills = integrations == null ? null : integrations.optJSONArray("skills");
            JSONObject selected = null;
            if (skills != null) for (int i = 0; i < skills.length(); i++) {
                JSONObject skill = skills.optJSONObject(i);
                if (skill != null && selectedSkillPath.equals(skill.optString("path")) && skill.optBoolean("enabled")) { selected = skill; break; }
            }
            if (selected == null || !"codex".equals(provider)) { toast("Choose an enabled skill in Tools. Your draft is saved."); return; }
        }
        if (!selectedAppId.isEmpty()) {
            JSONObject integrations = state.optJSONObject("integrations");
            JSONArray apps = integrations == null ? null : integrations.optJSONArray("apps");
            boolean callable = false;
            if (apps != null) for (int i = 0; i < apps.length(); i++) {
                JSONObject app = apps.optJSONObject(i);
                if (app != null && selectedAppId.equals(app.optString("id")) && app.optBoolean("runtimeKnown") && app.optBoolean("runtimeCallable")) { callable = true; break; }
            }
            if (!callable || !"codex".equals(provider)) { toast("Choose a connected app in Tools. Your draft is saved."); return; }
        }
        if (!attachedPath.isEmpty()) value += "\n\nUse this project file as context: " + attachedPath;
        for (String path : fileAttachments) value += "\n\nAttached project file: " + path;
        if (value.length() > 64000) { toast("This prompt is too long. Add a project file and describe the task briefly."); return; }
        sendPending=true;final int ticket=++sendTicket;pendingDraft=prompt.getText().toString();pendingExpected=AgentProtocol.clean(value.trim()+(imageAttachments.length()==0?"":"\n["+imageAttachments.length()+" image attachment(s)]"),32000);
        pendingSendProject=project.getName();pendingSendProvider=provider;pendingBeforeIds.clear();
        JSONArray previous=state.optJSONArray("messages");if(previous!=null)for(int i=0;i<previous.length();i++){JSONObject row=previous.optJSONObject(i);if(row!=null)pendingBeforeIds.add(row.optString("id"));}
        try { startService(agentIntent(AgentService.ACTION_PROMPT).putExtra(AgentService.EXTRA_TEXT, value)
                .putExtra(AgentService.EXTRA_SKILL_PATH, selectedSkillPath).putExtra(AgentService.EXTRA_APP_ID, selectedAppId)
                .putExtra(AgentService.EXTRA_ATTACHMENTS, imageAttachments.toString())); }
        catch(RuntimeException blocked){sendPending=false;error("Could not send. Your draft is still here.");return;}
        updateHeader();
        main.postDelayed(()->{if(sendPending&&sendTicket==ticket){sendPending=false;updateHeader();if(resumed&&!AppLock.isLocked(this))toast("The agent has not accepted this message yet. Your draft is still here; check the connection before retrying.");}},10000);
        main.postDelayed(this::refreshState, 160);
    }
    private boolean resolveReferences(String text) {
        java.util.List<ComposerTokens.Token> tokens=ComposerTokens.references(text);if(tokens.isEmpty())return false;
        final String scope=currentDictationScope(),draft=prompt.getText().toString();final File selected=project;
        JSONObject integrations=state.optJSONObject("integrations");JSONArray skills=integrations==null?null:integrations.optJSONArray("skills"),apps=integrations==null?null:integrations.optJSONArray("apps");
        String skillPath=selectedSkillPath,skillName=selectedSkillName,appId=selectedAppId,appName=selectedAppName;
        java.util.ArrayList<String> paths=new java.util.ArrayList<>();
        for(ComposerTokens.Token token:tokens) {
            if(token.prefix=='@'){if(!ComposerTokens.isProjectPath(token.query)){toast("Choose a project file with @.");return true;}if(!paths.contains(token.query))paths.add(token.query);continue;}
            if(!"codex".equals(provider))continue;
            JSONObject skillMatch=null,appMatch=null;int matches=0;
            if(skills!=null)for(int i=0;i<skills.length();i++){JSONObject item=skills.optJSONObject(i);if(item!=null&&item.optBoolean("enabled")&&token.query.equals(item.optString("name"))){skillMatch=item;matches++;}}
            if(apps!=null)for(int i=0;i<apps.length();i++) {
                JSONObject item=apps.optJSONObject(i);if(item==null||!item.optBoolean("runtimeKnown")||!item.optBoolean("runtimeCallable"))continue;
                boolean same=token.query.equals(item.optString("name"))||token.query.equals(item.optString("id"))
                    ||(!item.optString("slug").isEmpty()&&token.query.equals(item.optString("slug")));
                if(same){appMatch=item;matches++;}
            }
            if(matches==0)continue;
            if(matches>1){toast("That name matches more than one tool. Choose it from + → $.");return true;}
            if(skillMatch!=null){String path=skillMatch.optString("path");if(!skillPath.isEmpty()&&!skillPath.equals(path)){toast("Choose one skill for this message. Your draft is saved.");return true;}skillPath=path;skillName=skillMatch.optString("name");}
            if(appMatch!=null){String id=appMatch.optString("id");if(!appId.isEmpty()&&!appId.equals(id)){toast("Choose one connected app for this message. Your draft is saved.");return true;}appId=id;appName=appMatch.optString("name");}
        }
        if(paths.size()>12){toast("Attach up to 12 project files per message.");return true;}
        final String nextSkillPath=skillPath,nextSkillName=skillName,nextAppId=appId,nextAppName=appName;
        resolvingReferences=true;
        io.execute(()->{JSONArray files=new JSONArray();String failure="";try{for(String path:paths)files.put(WorkspaceMedia.describe(this,selected,path));}catch(Exception invalid){failure="A mentioned file is unavailable. Choose it from @ Project files.";}
            final String error=failure;deliverUi(()->{resolvingReferences=false;if(!scope.equals(currentDictationScope())||!draft.equals(prompt.getText().toString())){toast("The draft changed. Review it before sending.");return;}if(!error.isEmpty()){toast(error);return;}
                if(!attachmentCapacity(files)){return;}
                selectedSkillPath=nextSkillPath;selectedSkillName=nextSkillName;selectedAppId=nextAppId;selectedAppName=nextAppName;
                for(int i=0;i<files.length();i++)addAttachment(files.optJSONObject(i));updateComposerControls();sendPrompt(true);});});
        return true;
    }

    private void acknowledgePrompt() {
        if(!sendPending)return;
        if(!pendingSendProject.equals(state.optString("project"))||!pendingSendProvider.equals(state.optString("provider"))){sendPending=false;return;}
        JSONArray messages=state.optJSONArray("messages");
        if(messages!=null)for(int i=messages.length()-1;i>=0;i--){JSONObject row=messages.optJSONObject(i);if(row==null)continue;
            if(!pendingBeforeIds.contains(row.optString("id")) && "user".equals(row.optString("role")) && pendingExpected.equals(row.optString("text"))) {
                sendPending=false;
                if(pendingDraft.equals(prompt.getText().toString())){setDraft("");prefs.edit().remove("draft").apply();}
                clearAttachments();saveAgentDraft();pendingDraft="";pendingExpected="";pendingBeforeIds.clear();return;
            }
        }
        if(state.optBoolean("error")&&!state.optBoolean("busy"))sendPending=false;
    }
    private void fillPrompt(String value) { prompt.setText(value); prompt.requestFocus(); }

    private void openConnections(int section) {
        if (!"codex".equals(provider)) { toast("Choose Codex to manage its tools and connections."); return; }
        if (project == null) { projectPicker(); return; }
        startActivityForResult(new Intent(this, ConnectionsActivity.class).putExtra(AgentService.EXTRA_PROJECT, project.getName()).putExtra("section", section), ConnectionsActivity.REQUEST);
    }

    private void openSessions() {
        if (AppLock.isLocked(this)) return;
        if (!"codex".equals(provider)) { chatHistory(); return; }
        if (project == null) { projectPicker(); return; }
        SessionsActivity.open(this, project.getName());
    }

    private void openProjectTools() {
        if (AppLock.isLocked(this)) return;
        ProjectToolsActivity.open(this, project == null ? "" : project.getName());
    }

    private void setDraft(String value) {
        changingDraft = true; prompt.setText(value); prompt.setSelection(prompt.length()); changingDraft = false;
        handledToken = ""; main.removeCallbacks(tokenChanged);
    }

    private final Runnable tokenChanged = () -> {
        if(!resumed||paletteOpen||AppLock.isLocked(this)||prompt==null||!dialogs.isEmpty())return;
        ComposerTokens.Token token=ComposerTokens.atCursor(prompt.getText().toString(),prompt.getSelectionStart());
        if(token==null)return;String key=token.start+":"+token.raw;
        if(key.equals(handledToken))return;handledToken=key;
        if(token.prefix=='/')commandPicker(token.query);
        else if(token.prefix=='@'){pendingReference=token;mentionPicker();}
        else if(token.prefix=='$'){pendingReference=token;skillPicker(token.query);}
    };

    private void commandPicker(String filter) {
        if(AppLock.isLocked(this)||paletteOpen)return;state=AgentService.snapshot();
        java.util.List<ChatCommandCatalog.Entry> entries=ChatCommandCatalog.filter(filter,provider);
        java.util.ArrayList<String> labels=new java.util.ArrayList<>();java.util.ArrayList<Runnable> routes=new java.util.ArrayList<>();
        for(ChatCommandCatalog.Entry entry:entries){labels.add("/"+entry.key+"  ·  "+entry.label);routes.add(()->runSlash(entry.key,""));}
        JSONObject integration=state.optJSONObject("integrations");JSONArray skills=integration==null?null:integration.optJSONArray("skills");
        if("codex".equals(provider)&&skills!=null)for(int i=0;i<skills.length()&&labels.size()<100;i++){
            JSONObject skill=skills.optJSONObject(i);if(skill==null||!skill.optBoolean("enabled"))continue;String name=skill.optString("name"),path=skill.optString("path");
            if(!name.toLowerCase(Locale.ROOT).startsWith(filter.toLowerCase(Locale.ROOT))||ChatCommandCatalog.find(name)!=null)continue;
            final String scope=currentDictationScope();labels.add("/"+name+"  ·  Skill");routes.add(()->{if(!scope.equals(currentDictationScope()))return;selectSlashSkill(name,path,"");});
        }
        if(labels.isEmpty()){toast("No matching command. Your draft is still here.");return;}
        paletteOpen=true;AlertDialog menu=dialog().setTitle("Commands /").setItems(labels.toArray(new String[0]),(d,w)->{paletteOpen=false;routes.get(w).run();}).setNegativeButton("Close",null).show();
        menu.setOnDismissListener(d->{dialogs.remove(menu);paletteOpen=false;});bottomSheet(menu);
    }
    private void selectSlashSkill(String name,String path,String arguments) {
        if(AppLock.isLocked(this)||sendPending)return;JSONObject integrations=AgentService.snapshot().optJSONObject("integrations");JSONArray skills=integrations==null?null:integrations.optJSONArray("skills");
        boolean valid=false;if(skills!=null)for(int i=0;i<skills.length();i++){JSONObject item=skills.optJSONObject(i);if(item!=null&&path.equals(item.optString("path"))&&item.optBoolean("enabled"))valid=true;}
        if(!valid){toast("This skill is no longer available.");return;}
        selectedSkillName=name;selectedSkillPath=path;
        if(ComposerTokens.leadingCommand(prompt.getText().toString())!=null||prompt.getText().toString().trim().equals("/"))setDraft(arguments);
        updateComposerControls();prompt.requestFocus();
    }
    private void dispatchSlash(String value) {
        ComposerTokens.Command command=ComposerTokens.leadingCommand(value);
        if(command==null){if(value.trim().equals("/"))commandPicker("");else toast("Choose a command from + → Commands.");return;}
        ChatCommandCatalog.Entry entry=ChatCommandCatalog.find(command.key);
        if(entry==null){
            state=AgentService.snapshot();JSONObject integrations=state.optJSONObject("integrations");JSONArray skills=integrations==null?null:integrations.optJSONArray("skills");JSONObject match=null;int matches=0;
            if("codex".equals(provider)&&skills!=null)for(int i=0;i<skills.length();i++){JSONObject item=skills.optJSONObject(i);if(item!=null&&item.optBoolean("enabled")&&command.key.equals(item.optString("name"))){match=item;matches++;}}
            if(matches==1){selectSlashSkill(match.optString("name"),match.optString("path"),command.arguments);return;}
            toast(matches>1?"Choose this skill from + → Skills.":"This command is not available here. Nothing was sent.");return;
        }
        if(!entry.acceptsText&&!command.arguments.isEmpty()){toast("This command does not take text. Your draft is still here.");return;}
        runSlash(command.key,command.arguments);
    }

    private void runSlash(String key, String inline) {
        if (AppLock.isLocked(this)) return;
        if (!"codex".equals(provider) && !"model".equals(key) && !"status".equals(key) && !"diff".equals(key)) {
            toast("Choose Codex to use its official actions."); return;
        }
        if (!inline.isEmpty() && !"plan".equals(key) && !"review".equals(key) && !"new".equals(key) && !"mention".equals(key)) { toast("This action does not take prompt text. Your draft is still here."); return; }
        if (prompt.getText().toString().trim().startsWith("/")) setDraft(inline);
        if ("model".equals(key)) modelPicker();
        else if ("reasoning".equals(key)) effortPicker();
        else if ("project".equals(key)) projectPicker();
        else if ("mention".equals(key)) {if(inline.isEmpty())mentionPicker();else if(ComposerTokens.isProjectPath(inline))attachProjectPath(inline);else toast("Choose a file inside this project.");}
        else if ("permissions".equals(key)) modePicker();
        else if ("plan".equals(key)) chooseMode("plan");
        else if ("resume".equals(key)) openSessions();
        else if ("ps".equals(key)) {if(project==null)projectPicker();else SessionsActivity.open(this,project.getName(),true);}
        else if ("new".equals(key)) {
            if (!matchingSession() || state.optBoolean("busy")) { toast("Connect Codex and finish the current task first."); return; }
            agentAction(AgentService.ACTION_NEW_SESSION); clearAttachments(); tab = 0; renderPage();
        } else if ("compact".equals(key)) confirmControl("Compact this chat?", "Codex will condense earlier context using its own compaction method. Your project files stay unchanged.", "compact", new JSONObject());
        else if ("review".equals(key)) {
            JSONObject payload = inline.isEmpty() ? AgentProtocol.object("type", "uncommittedChanges") : AgentProtocol.object("type", "custom", "instructions", inline);
            confirmControl("Start Codex review?", inline.isEmpty() ? "Review uncommitted changes in the current project." : inline, "review", payload);
        } else if ("diff".equals(key)) { tab = 2; renderPage(); }
        else if ("status".equals(key)) usageDetails();
        else if ("mcp".equals(key)) openConnections(1);
        else if ("skills".equals(key)) skillPicker("");
        else if ("apps".equals(key)) openConnections(0);
        else if ("plugins".equals(key)) openConnections(3);
        else if ("logout".equals(key)) logout();
    }

    private void modePicker() {
        if (!"codex".equals(provider) || !matchingSession()) { toast("Connect Codex to read supported modes."); return; }
        JSONArray modes = state.optJSONArray("modeOptions");
        if (modes == null || modes.length() == 0) { toast(first(state.optString("modeError"), "Codex has not reported available modes yet.")); return; }
        String[] labels = new String[modes.length()];
        for (int i = 0; i < modes.length(); i++) {
            JSONObject mode = modes.optJSONObject(i); labels[i] = mode == null ? "Unavailable" : mode.optString("label") + "\n" + mode.optString("description") + (mode.optBoolean("available") ? "" : " · unavailable");
        }
        dialog().setTitle("Codex mode").setItems(labels, (d,w) -> { JSONObject choice = modes.optJSONObject(w); if (choice != null) chooseMode(choice.optString("id")); }).setNegativeButton("Close", null).show();
    }

    private void chooseMode(String id) {
        state = AgentService.snapshot();
        if (!matchingSession() || state.optBoolean("busy")) { toast("Connect Codex and finish the current task first."); return; }
        JSONArray modes = state.optJSONArray("modeOptions"); JSONObject selected = null;
        if (modes != null) for (int i=0;i<modes.length();i++) { JSONObject mode=modes.optJSONObject(i); if(mode!=null && id.equals(mode.optString("id")) && mode.optBoolean("available")) selected=mode; }
        if (selected == null) { toast("This mode is not currently exposed by your Codex engine."); return; }
        final String chosen = id; String description = selected.optString("description");
        dialog().setTitle(selected.optString("label")).setMessage(description + "\n\nThis is an on-device Ubuntu process. Approval settings do not make PRoot a separate security sandbox.")
                .setPositiveButton("Use mode", (d,w) -> { startService(agentIntent(AgentService.ACTION_MODE).putExtra(AgentService.EXTRA_MODE, chosen)); main.postDelayed(this::refreshState, 200); })
                .setNegativeButton("Cancel", null).show();
    }

    private void confirmControl(String title, String text, String operation, JSONObject payload) {
        if (AppLock.isLocked(this)) return;
        dialog().setTitle(title).setMessage(text).setPositiveButton("Continue", (d,w) -> control(operation,payload)).setNegativeButton("Cancel",null).show();
    }
    private void control(String operation, JSONObject payload) {
        if (AppLock.isLocked(this) || !"codex".equals(provider)) return;
        if (project == null && !"logout".equals(operation)) { projectPicker(); return; }
        Intent action = agentIntent(AgentService.ACTION_CONTROL);
        if (project == null) {
            String localScope = AgentService.snapshot().optString("project", "my-project");
            if (!localScope.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) localScope = "my-project";
            action.putExtra(AgentService.EXTRA_PROJECT, localScope);
        }
        startService(action.putExtra(AgentService.EXTRA_OPERATION, operation).putExtra(AgentService.EXTRA_PAYLOAD,payload.toString()));
        main.postDelayed(this::refreshState, 200);
    }
    private void logout() {
        confirmControl("Sign out of Codex on this phone?", "This stops any current Codex task or login and removes the local Codex account login. It keeps project files and local conversation history. It does not sign you out of ChatGPT in your browser or revoke separate GitHub connections.", "logout", AgentProtocol.object("confirmed",true));
    }

    private void addMenu() {
        pendingReference=null;
        showMenu("Add",new String[]{"Photos & files","Project file","Skills & apps","Commands"},
                new String[]{"attach","at","dollar","slash"},
                new Runnable[]{this::pickFiles,this::mentionPicker,()->skillPicker(""),()->commandPicker("")});
    }
    private void mentionPicker() {
        if(AppLock.isLocked(this)||paletteOpen||sendPending)return;
        if(pendingReference==null){ComposerTokens.Token token=ComposerTokens.atCursor(prompt.getText().toString(),prompt.getSelectionStart());if(token!=null&&token.prefix=='@')pendingReference=token;}
        projectFilePicker("");
    }
    private void skillPicker(String filter) {
        if(AppLock.isLocked(this)||paletteOpen||sendPending)return;state=AgentService.snapshot();
        if(!"codex".equals(provider)||!matchingSession()){toast("Connect Codex to choose its skills and apps.");return;}
        toolScope=currentDictationScope();toolToken=pendingReference;pendingReference=null;toolFilter=filter.toLowerCase(Locale.ROOT);toolSignature="";
        toolActions=new java.util.ArrayList<>();toolAdapter=new android.widget.ArrayAdapter<>(this,android.R.layout.simple_list_item_1,new java.util.ArrayList<String>());
        updateToolPicker();paletteOpen=true;
        toolSheet=dialog().setTitle("Skills & apps $").setAdapter(toolAdapter,(d,w)->{paletteOpen=false;if(toolActions!=null&&w>=0&&w<toolActions.size())toolActions.get(w).run();}).setNegativeButton("Close",null).show();
        final AlertDialog own=toolSheet;toolSheet.setOnDismissListener(d->{dialogs.remove(own);paletteOpen=false;if(toolSheet==own){toolSheet=null;toolAdapter=null;toolActions=null;toolToken=null;}});bottomSheet(toolSheet);
        JSONObject integrations=state.optJSONObject("integrations");if(integrations==null||!integrations.optBoolean("skillsKnown")||!integrations.optBoolean("appsKnown"))requestIntegrations();
    }
    private void updateToolPicker() {
        if(toolAdapter==null||toolActions==null)return;
        if(!toolScope.equals(currentDictationScope())||AppLock.isLocked(this)){if(toolSheet!=null)toolSheet.dismiss();return;}
        JSONObject integrations=state.optJSONObject("integrations");String signature=String.valueOf(integrations);if(signature.equals(toolSignature))return;toolSignature=signature;
        final String scope=toolScope;final ComposerTokens.Token captured=toolToken;
        java.util.ArrayList<String> names=new java.util.ArrayList<>();java.util.ArrayList<Runnable> actions=new java.util.ArrayList<>();
        JSONArray skills=integrations==null?null:integrations.optJSONArray("skills"),apps=integrations==null?null:integrations.optJSONArray("apps");
        if(skills!=null)for(int i=0;i<skills.length()&&names.size()<80;i++){JSONObject skill=skills.optJSONObject(i);if(skill==null||!skill.optBoolean("enabled"))continue;
            String name=skill.optString("name"),path=skill.optString("path");if(!name.toLowerCase(Locale.ROOT).contains(toolFilter))continue;
            names.add("$"+name+" · Skill");actions.add(()->{if(!scope.equals(currentDictationScope()))return;selectedSkillName=name;selectedSkillPath=path;if(captured!=null)setDraft(ComposerTokens.replace(prompt.getText().toString(),captured,""));updateComposerControls();});}
        if(apps!=null)for(int i=0;i<apps.length()&&names.size()<100;i++){JSONObject app=apps.optJSONObject(i);if(app==null||!app.optBoolean("runtimeKnown")||!app.optBoolean("runtimeCallable"))continue;
            String name=app.optString("name"),id=app.optString("id");if(!name.toLowerCase(Locale.ROOT).contains(toolFilter)&&!id.toLowerCase(Locale.ROOT).contains(toolFilter))continue;
            names.add(name+" · Connected app");actions.add(()->{if(!scope.equals(currentDictationScope()))return;selectedAppId=id;selectedAppName=name;if(captured!=null)setDraft(ComposerTokens.replace(prompt.getText().toString(),captured,""));updateComposerControls();});}
        if(names.isEmpty()) {names.add(integrations==null||integrations.optBoolean("loading")?"Loading tools…":"No matching tools");actions.add(()->toast("Choose Manage skills or Connect an app."));}
        names.add("Manage skills");actions.add(()->openConnections(2));names.add("Connect an app");actions.add(()->openConnections(0));
        toolActions.clear();toolActions.addAll(actions);toolAdapter.setNotifyOnChange(false);toolAdapter.clear();toolAdapter.addAll(names);toolAdapter.notifyDataSetChanged();
    }
    private void consumeReference() {if(pendingReference!=null){setDraft(ComposerTokens.replace(prompt.getText().toString(),pendingReference,""));pendingReference=null;}}
    private void removeMentionToken() {consumeReference();}
    private void requestIntegrations() {
        if(!matchingSession()||!"codex".equals(provider)||AppLock.isLocked(this))return;
        JSONObject integrations=state.optJSONObject("integrations");
        if(integrations!=null&&(!integrations.optString("operation").isEmpty()||integrations.optBoolean("loading")))return;
        startService(agentIntent(AgentService.ACTION_INTEGRATION).putExtra(AgentService.EXTRA_OPERATION,"refresh").putExtra(AgentService.EXTRA_PAYLOAD,"{}"));
    }

    private void projectFilePicker(String directory) {
        ComposerTokens.Token token=pendingReference!=null&&pendingReference.prefix=='@'?pendingReference:null;pendingReference=null;
        projectFilePicker(directory,currentDictationScope(),token);
    }
    private void projectFilePicker(String directory,String scope,ComposerTokens.Token token) {
        if(project==null){projectPicker();return;}final File selected=project;
        work(()->WorkspaceTools.listFiles(this,selected,directory),entries->{
            if(!scope.equals(currentDictationScope())||AppLock.isLocked(this))return;
            String[] labels=new String[entries.size()];for(int i=0;i<entries.size();i++)labels[i]=(entries.get(i).directory?"Folder · ":"")+entries.get(i).name;
            AlertDialog picker=dialog().setTitle(directory.isEmpty()?"Project files @":directory).setItems(labels,(d,w)->{
                if(!scope.equals(currentDictationScope()))return;WorkspaceTools.Entry entry=entries.get(w);
                if(entry.directory)projectFilePicker(entry.path,scope,token);else attachProjectPath(entry.path,scope,token);
            }).setNegativeButton("Close",null).show();bottomSheet(picker);
        });
    }
    private void pickFiles() {
        if(project==null){projectPicker();return;}
        pendingImportProject=project.getName();
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        try{startActivityForResult(intent,PICK_FILES);}catch(RuntimeException unavailable){toast("No system file picker is available.");}
    }
    private void attachProjectPath(String path) {attachProjectPath(path,currentDictationScope(),null);}
    private void attachProjectPath(String path,String scope,ComposerTokens.Token token) {
        if(project==null||sendPending)return;final File selected=project;
        work(()->WorkspaceMedia.describe(this,selected,path),entry->{
            if(!scope.equals(currentDictationScope())||sendPending)return;
            JSONArray values=new JSONArray();values.put(entry);if(!attachmentCapacity(values))return;
            addAttachment(entry);if(token!=null)setDraft(ComposerTokens.replace(prompt.getText().toString(),token,""));
        });
    }
    private boolean attachmentCapacity(JSONArray files) {
        java.util.HashSet<String> images=new java.util.HashSet<>(),documents=new java.util.HashSet<>(fileAttachments);
        for(int i=0;i<imageAttachments.length();i++)images.add(imageAttachments.optString(i));
        for(int i=0;i<files.length();i++){JSONObject item=files.optJSONObject(i);if(item==null)continue;String path=item.optString("path");if(path.isEmpty())continue;if(item.optBoolean("imageInput")&&"codex".equals(provider))images.add(path);else documents.add(path);}
        if(images.size()>4||documents.size()>12){toast("Attach up to 4 images and 12 files. Your draft is saved.");return false;}
        if(!images.isEmpty()&&(!"codex".equals(provider)||!AgentService.snapshot().optBoolean("imageInputSupported"))){toast("Choose a model that supports images before attaching these files.");return false;}
        return true;
    }
    private void addAttachment(JSONObject entry) {
        if(sendPending){toast("The file is imported. Wait for the current message before attaching it again.");return;}
         String path=entry.optString("path");
        if(entry.optBoolean("imageInput") && "codex".equals(provider)) {
            for(int i=0;i<imageAttachments.length();i++)if(path.equals(imageAttachments.optString(i)))return;
            if(imageAttachments.length()>=4){toast("Attach up to four images per prompt.");return;} imageAttachments.put(path);
        } else { if(fileAttachments.size()>=12){toast("Attach up to twelve project files per prompt.");return;} if(!fileAttachments.contains(path))fileAttachments.add(path); }
        updateComposerControls(); tab=0; renderPage(); prompt.requestFocus();
    }
    private void clearAttachments() { pendingReference=null;imageAttachments=new JSONArray();fileAttachments.clear();attachedPath="";selectedSkillPath="";selectedSkillName="";selectedAppId="";selectedAppName="";updateComposerControls(); }
    private void attachmentPicker() {
        java.util.ArrayList<String> paths=new java.util.ArrayList<>(); for(int i=0;i<imageAttachments.length();i++)paths.add(imageAttachments.optString(i));paths.addAll(fileAttachments);
        if(paths.isEmpty())return;
        dialog().setTitle("Attached files · choose one").setItems(paths.toArray(new String[0]),(d,w)-> {
            String path=paths.get(w); dialog().setTitle(new File(path).getName()).setItems(new String[]{"Preview / save / share","Remove from message"},(dd,action)-> {
                if(action==0 && project!=null)WorkspaceMedia.openPreview(this,project,path);
                else { for(int i=imageAttachments.length()-1;i>=0;i--)if(path.equals(imageAttachments.optString(i)))imageAttachments.remove(i);fileAttachments.remove(path);updateComposerControls(); }
            }).setNegativeButton("Close",null).show();
        }).setNegativeButton("Close",null).show();
    }
    private String currentDictationScope() {JSONObject current=AgentService.snapshot();return provider+"\n"+(project==null?"":project.getName())+"\n"+current.optString("threadId")+"\n"+current.optString("accountScopeToken");}
    private void dictate() {
        if(AppLock.isLocked(this)||sendPending)return;
        if(messageSpeech!=null)messageSpeech.stop();
        if(inlineDictation!=null)inlineDictation.toggle();
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(inlineDictation!=null)inlineDictation.onRequestPermissionsResult(request,permissions,results);
    }
    private void removeAttachment(String path) {
        if(sendPending||AppLock.isLocked(this))return;
        for(int i=imageAttachments.length()-1;i>=0;i--)if(path.equals(imageAttachments.optString(i)))imageAttachments.remove(i);
        fileAttachments.remove(path);if(path.equals(attachedPath))attachedPath="";updateComposerControls();
    }
    private void selectedContextPicker() {
        if (sendPending) { toast("Wait for the agent to accept your message before changing attachments."); return; }
        java.util.ArrayList<String> choices = new java.util.ArrayList<>();
        if (!selectedSkillPath.isEmpty()) choices.add("Remove skill: " + selectedSkillName);
        if (!selectedAppId.isEmpty()) choices.add("Remove app: " + selectedAppName);
        choices.add("Choose a skill"); choices.add("Choose an app");
        dialog().setTitle("Attached to next prompt").setItems(choices.toArray(new String[0]), (d,w) -> {
            if (sendPending) return;
            String choice = choices.get(w);
            if (choice.startsWith("Remove skill:")) { selectedSkillPath = ""; selectedSkillName = ""; }
            else if (choice.startsWith("Remove app:")) { selectedAppId = ""; selectedAppName = ""; }
            else openConnections(choice.equals("Choose a skill") ? 2 : 0);
            updateComposerControls();
        }).setNegativeButton("Keep", null).show();
    }

    private String userStatus() {
        if (connectionRequestPending) return "Starting " + providerName(provider) + "…";
        if (state.optBoolean("recoveringSession")) return "The previous chat is unavailable. Starting a new conversation; saved messages stay in Chat history.";
        if (state.optBoolean("sessionOpening") && !state.optBoolean("error")) return "Opening your conversation…";
        String raw = state.optString("status");
        if (state.optBoolean("error")) {
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.contains("no rollout found")) return "The previous chat could not be reopened. Retry to start a new chat. Your project files are unchanged.";
            if (technicalMessage(raw)) return "The agent could not complete this action. Open Error details, then retry.";
        }
        return raw.isEmpty() ? "Use your own account to start a conversation." : raw;
    }

    private static boolean technicalMessage(String value) {
        String lower = safe(value).toLowerCase(Locale.ROOT);
        return lower.contains("codex_app_server") || lower.contains("no rollout found") || lower.contains("2 mb transport limit")
                || lower.contains("stack trace") || lower.matches("(?s)^\\d{4}-\\d{2}-\\d{2}t\\d{2}:.*");
    }

    private void details(String title, String value) {
        if (AppLock.isLocked(this)) return;
        TextView content = selectable(value, 14, INK); content.setPadding(dp(20), dp(8), dp(20), dp(16));
        ScrollView container = new ScrollView(this); container.addView(content);
        dialog().setTitle(title).setView(container).setPositiveButton("Done", null)
                .setNeutralButton("Copy", (d,w) -> {
                    android.content.ClipData clip = android.content.ClipData.newPlainText(title, value);
                    android.os.PersistableBundle extras = new android.os.PersistableBundle();
                    extras.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(extras);
                    ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);
                    toast("Copied to clipboard.");
                }).show();
    }

    private void diagnostics() {
        StringBuilder result = new StringBuilder();
        if (state.optBoolean("error")) result.append(state.optString("status")).append("\n\n");
        JSONArray logs = state.optJSONArray("runtimeDiagnostics");
        if (logs != null) for (int i = 0; i < logs.length(); i++) {
            JSONObject entry = logs.optJSONObject(i); if (entry == null) continue;
            result.append(entry.optString("message")).append("\n\n");
        }
        details("Technical details", result.length() == 0 ? "No recent diagnostic messages." : result.toString().trim());
    }

    private void chatHistory() {
        if (project == null) { toast("Choose a project to view its chat history."); return; }
        final String historyProvider = provider, historyProject = project.getName();
        work(() -> ChatHistory.list(getFilesDir(), historyProvider, historyProject), entries -> {
            if (AppLock.isLocked(this)) return;
            if (entries.length() == 0) {
                dialog().setTitle("Chat history").setMessage("No archived chats for this project yet. A chat saved during recovery will appear here.")
                        .setPositiveButton("Done", null).show(); return;
            }
            String[] titles = new String[entries.length()], ids = new String[entries.length()];
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                ids[i] = entry == null ? "" : entry.optString("id");
                long time = entry == null ? 0 : entry.optLong("createdAt");
                titles[i] = (time > 0 ? java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(new java.util.Date(time)) : "Saved chat") + " · " + (entry == null ? 0 : entry.optInt("messageCount")) + " messages";
            }
            dialog().setTitle("Chat history · " + historyProject).setItems(titles, (d,w) ->
                    work(() -> ChatHistory.read(getFilesDir(), historyProvider, historyProject, ids[w]), archived -> {
                        StringBuilder transcript = new StringBuilder("Saved for reference. These messages are not part of your new conversation.\n\n");
                        JSONArray messages = archived.optJSONArray("messages");
                        if (messages != null) for (int i = 0; i < messages.length(); i++) {
                            JSONObject message = messages.optJSONObject(i); if (message == null) continue;
                            String role = message.optString("role");
                            transcript.append("user".equals(role) ? "You" : "assistant".equals(role) ? providerName(historyProvider) : "PocketAgent")
                                    .append("\n").append(message.optString("text")).append("\n\n");
                        }
                        details("Saved chat · " + historyProject, transcript.toString().trim());
                    })).setNegativeButton("Done", null).show();
        });
    }
    private void reply(String id, String reply, String answer) {
        if (AppLock.isLocked(this)) return;
        JSONObject live = AgentService.snapshot(), permission = live.optJSONObject("permission");
        if (project == null || !project.getName().equals(live.optString("project")) || !provider.equals(live.optString("provider"))
                || permission == null || !id.equals(permission.optString("id"))) {
            toast("This request is no longer waiting for a response."); refreshState(); return;
        }
        startService(agentIntent(AgentService.ACTION_REPLY_PERMISSION).putExtra(AgentService.EXTRA_PERMISSION_ID, id)
                .putExtra(AgentService.EXTRA_REPLY, reply).putExtra(AgentService.EXTRA_TEXT, answer));
    }

    private void reviewMcpRequest(JSONObject permission) {
        if (AppLock.isLocked(this)) return;
        final String id = permission.optString("id");
        try {
            McpElicitationView form = new McpElicitationView(this, permission.getJSONObject("elicitation"));
            final boolean[] answered = {false};
            AlertDialog.Builder builder = dialog().setTitle(form.isUrl() ? "MCP browser request" : "MCP form request").setView(form)
                    .setPositiveButton(form.isUrl() ? "Done in browser" : "Submit", null)
                    .setNegativeButton("Decline", (d,w) -> { answered[0] = true; reply(id, "decline", ""); });
            if (form.isUrl()) builder.setNeutralButton("Open browser", null);
            AlertDialog request = builder.show();
            request.setOnCancelListener(d -> { if (!answered[0]) { answered[0] = true; reply(id, "cancel", ""); } });
            request.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (AppLock.isLocked(this)) { request.dismiss(); return; }
                try {
                    String answer = form.isUrl() ? "" : form.content().toString();
                    if (form.isUrl()) {
                        dialog().setTitle("Finished in your browser?")
                                .setMessage("Confirm only after completing the requested browser step. The MCP server will verify its result.")
                                .setPositiveButton("Confirm completion", (d,w) -> { answered[0] = true; reply(id, "accept", ""); request.dismiss(); })
                                .setNegativeButton("Go back", null).show();
                    } else { answered[0] = true; reply(id, "accept", answer); request.dismiss(); }
                } catch (IllegalArgumentException invalid) { toast(invalid.getMessage()); }
            });
            if (form.isUrl()) request.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (AppLock.isLocked(this)) return;
                Uri destination = Uri.parse(form.url());
                dialog().setTitle("Open " + destination.getHost() + "?")
                        .setMessage("Requested by " + form.request().serverName + ". Review the destination and requested permissions before continuing. Opening the browser does not approve this request.")
                        .setPositiveButton("Open browser", (d,w) -> {
                            try { startActivity(new Intent(Intent.ACTION_VIEW, destination).addCategory(Intent.CATEGORY_BROWSABLE)); }
                            catch (RuntimeException failure) { error("Could not open browser: " + safe(failure.getMessage())); }
                        }).setNegativeButton("Cancel", null).show();
            });
        } catch (Exception invalid) { error("This MCP request cannot be displayed: " + safe(invalid.getMessage())); }
    }
    private void modelPicker() {
        if(AppLock.isLocked(this)||!pendingAgentSwitch.isEmpty())return;state=AgentService.snapshot();
        if (!matchingSession()) {
            showMenu(providerName(provider)+" · Model",new String[]{"Connect "+providerName(provider),"Switch agent"},new String[]{"account","model"},new Runnable[]{this::connect,this::providerPicker});return;
        }
        if (state.optBoolean("busy")) { toast("Finish the current turn before changing model."); return; }
        JSONArray models = state.optJSONArray("models");
        if (models == null || models.length() == 0) {
            AlertDialog sheet=dialog().setTitle(providerName(provider)+" · Model").setMessage("This agent has not provided a model list. Its own default model is used.").setNeutralButton("Switch agent",(d,w)->providerPicker()).setNegativeButton("Close",null).show();bottomSheet(sheet);return;
        }
        final String expectedScope=currentDictationScope();
        String[] labels = new String[models.length()]; String[] ids = new String[models.length()];
        for (int i=0; i<models.length(); i++) { JSONObject m = models.optJSONObject(i); ids[i] = m == null ? models.optString(i) : m.optString("id"); labels[i] = m == null ? ids[i] : m.optString("name", ids[i]); }
        AlertDialog sheet=dialog().setTitle(providerName(provider)+" · Model").setItems(labels, (d,w) -> {
            state=AgentService.snapshot();
            if(AppLock.isLocked(this)||!expectedScope.equals(currentDictationScope())||!matchingSession()||!pendingAgentSwitch.isEmpty()){toast("The agent changed. Choose its model again.");return;}
            if (state.optBoolean("busy")||sendPending) { toast("Finish the current turn before changing model."); return; }
            startService(agentIntent(AgentService.ACTION_MODEL).putExtra(AgentService.EXTRA_MODEL, ids[w])
                    .putExtra(AgentService.EXTRA_EXPECTED_ACCOUNT_TOKEN,state.optString("accountScopeToken"))
                    .putExtra("expectedThreadId",state.optString("threadId")));
            main.postDelayed(this::refreshState, 150);
        }).setNeutralButton("Switch agent",(d,w)->providerPicker()).setNegativeButton("Close", null).show();bottomSheet(sheet);
    }

    private boolean matchingSession() {
        return state.optBoolean("connected") && provider.equals(state.optString("provider"))
                && project != null && project.getName().equals(state.optString("project"));
    }

    private String modelName(String id) {
        JSONArray models = state.optJSONArray("models");
        if (models != null) for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model != null && id.equals(model.optString("id"))) return first(model.optString("name"), id);
        }
        return id;
    }

    private String effortTitle() {
        String chosen = state.optString("effort", "auto");
        String active = state.optString("effectiveEffort");
        JSONArray options = state.optJSONArray("effortOptions");
        if ("auto".equals(chosen) && !active.isEmpty()) return "Auto · " + CodexEffort.label(active);
        if (options != null) for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option != null && chosen.equals(option.optString("id"))) return option.optString("label", "Auto");
        }
        return "Auto";
    }

    private void updateComposerControls() {
        boolean matching = matchingSession();
        boolean available = matching && !state.optBoolean("busy") && !sendPending;
        String selected = matching ? first(state.optString("effectiveModel"), state.optString("model")) : "";
        String model = selected.isEmpty() ? "Automatic" : modelName(selected);
        modelLabel.setText(model);
        modelLabel.setContentDescription(providerName(provider)+" model: " + model+". Tap for models or switch agent.");
        modelLabel.setTooltipText(providerName(provider)+" · "+model);
        modelLabel.setEnabled(pendingAgentSwitch.isEmpty()); modelLabel.setAlpha(pendingAgentSwitch.isEmpty() ? 1f : .6f);
        boolean codex = "codex".equals(provider);
        if (voiceButton != null) {voiceButton.setText("");decorateComposer(voiceButton,"mic",INK);voiceButton.setContentDescription("Dictate");}
        if (modeLabel != null) {
            String modeName = "Ask approvals";
            JSONArray modes = state.optJSONArray("modeOptions");
            if (modes != null) for(int i=0;i<modes.length();i++) { JSONObject mode=modes.optJSONObject(i); if(mode!=null && state.optString("mode","ask").equals(mode.optString("id")))modeName=mode.optString("label",modeName); }
            String shortMode="plan".equals(state.optString("mode"))?"Plan":"auto".equals(state.optString("mode"))?"Auto edits":"Ask";
            modeLabel.setText(shortMode);modeLabel.setContentDescription("Mode: "+modeName); modeLabel.setVisibility(codex?View.VISIBLE:View.GONE); modeLabel.setEnabled(available); modeLabel.setAlpha(available?1f:.6f);
        }
        effortLabel.setVisibility(codex ? View.VISIBLE : View.GONE);
        String effort = matching ? effortTitle() : "Auto";
        effortLabel.setText("auto".equals(state.optString("effort","auto"))?"Auto":effort);
        effortLabel.setContentDescription("Reasoning effort. Current: " + effort);
        effortLabel.setTooltipText("Reasoning effort: " + effort);
        effortLabel.setEnabled(available && codex); effortLabel.setAlpha(available ? 1f : .6f);
        selectedSkillLabel.setText((selectedSkillPath.isEmpty() ? "" : "Skill · " + selectedSkillName)
                + (selectedSkillPath.isEmpty() || selectedAppId.isEmpty() ? "" : "\n") + (selectedAppId.isEmpty() ? "" : "App · " + selectedAppName));
        selectedSkillLabel.setContentDescription(selectedSkillLabel.getText() + ". Tap to change attached tools.");
        selectedSkillLabel.setVisibility(codex && (!selectedSkillPath.isEmpty() || !selectedAppId.isEmpty()) ? View.VISIBLE : View.GONE);
        if(attachmentStrip!=null)attachmentStrip.update(project,imageAttachments,fileAttachments,attachedPath,!sendPending);
        displayedUsage = UsageDisplay.read(state, provider);
        String quota=displayedUsage.compact.replace("Usage · ","");
        usageLabel.setText(quota);usageLabel.setVisibility(View.VISIBLE);
        usageLabel.setTextColor(displayedUsage.blocked?RED:MUTED);
        usageLabel.setContentDescription("Usage. "+displayedUsage.compact+". Tap for limits and reset time.");
        usageLabel.setTooltipText(displayedUsage.compact);
        UsageNotice notice=UsageNotice.read(displayedUsage);
        if(usageWarning!=null){usageWarning.setVisibility(tab==0&&notice.visible?View.VISIBLE:View.GONE);usageWarningTitle.setText(notice.title);usageWarningTitle.setTextColor(notice.reached?RED:AMBER);decorate(usageWarningTitle,"usage",notice.reached?RED:AMBER,false);usageWarningDetail.setText(notice.detail);}
        if (attachedPath.isEmpty()) prompt.setHint("Message " + providerName(provider) + "…");
        if (usagePanel != null && usageDialog != null && usageDialog.isShowing()) usagePanel.update(displayedUsage);
        main.removeCallbacks(composerLayout);if(composer!=null)composer.post(composerLayout);
        updateSendAction();
    }

    private void effortPicker() {
        state = AgentService.snapshot();
        if (!"codex".equals(provider) || !matchingSession()) { toast("Connect Codex first."); return; }
        if (state.optBoolean("busy") || sendPending) { toast("Wait for this task to finish."); return; }
        final JSONArray options = state.optJSONArray("effortOptions");
        if (options == null || options.length() <= 1) {
            dialog().setTitle("Reasoning effort").setMessage("This model uses its default effort.").setPositiveButton("Done", null).show(); return;
        }
        final String chosenProject = project.getName(), chosenModel = first(state.optString("effectiveModel"), state.optString("model"));
        int selected = 0;
        for (int i=0;i<options.length();i++) if (options.optJSONObject(i) != null && state.optString("effort","auto").equals(options.optJSONObject(i).optString("id"))) selected=i;
        LinearLayout content = column(); content.setPadding(dp(20), dp(8), dp(20), dp(12));
        TextView value = label("", 27, INK); content.addView(value, lp(-1,-2,0,8));
        TextView description = text("", 13, MUTED); description.setMinLines(2); description.setMaxLines(4); description.setEllipsize(TextUtils.TruncateAt.END);
        SeekBar slider = new SeekBar(this); slider.setMax(options.length()-1); slider.setMinHeight(dp(56));
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        GradientDrawable dot = shape(LINE, 3); dot.setSize(dp(4),dp(4)); slider.setTickMark(dot);
        final int[] choice = {selected};
        Runnable update = () -> {
            JSONObject option = options.optJSONObject(choice[0]);
            value.setText(option == null ? "Auto" : option.optString("label",option.optString("id")));
            description.setText(choice[0] == 0 ? "Use the model default." : option == null ? "" : option.optString("description"));
            slider.setContentDescription("Reasoning effort: "+value.getText());
        };
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int position,boolean fromUser) { choice[0]=position;update.run(); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        slider.setProgress(selected); update.run(); content.addView(slider);
        LinearLayout ends = row(); TextView first = text("Auto",12,MUTED), last = text(options.optJSONObject(options.length()-1).optString("label"),12,MUTED); last.setGravity(Gravity.END);
        ends.addView(first,new LinearLayout.LayoutParams(0,-2,1));ends.addView(last,new LinearLayout.LayoutParams(0,-2,1));content.addView(ends);content.addView(description,lp(-1,-2,14,0));
        AlertDialog sheet=dialog().setTitle("Reasoning effort").setView(content).setPositiveButton("Apply",(d,w)-> {
            state=AgentService.snapshot();
            if (!matchingSession() || state.optBoolean("busy") || sendPending || !chosenProject.equals(project.getName()) || !chosenModel.equals(first(state.optString("effectiveModel"),state.optString("model")))) { toast("The session changed. Choose effort again.");return; }
            JSONObject option=options.optJSONObject(choice[0]);if(option==null)return;
            startService(agentIntent(AgentService.ACTION_EFFORT).putExtra(AgentService.EXTRA_EFFORT,option.optString("id")));main.postDelayed(this::refreshState,150);
        }).setNegativeButton("Cancel",null).show(); bottomSheet(sheet);
    }

    private void usageDetails() {
        if (AppLock.isLocked(this)) return;
        state=AgentService.snapshot(); displayedUsage=UsageDisplay.read(state,provider);
        usagePanel=new UsagePanel(this,"codex".equals(provider),this::useResetCredit,
                () -> openAuth(AgentCatalog.accountUrl(provider)),
                () -> details("Usage details",UsageDisplay.read(AgentService.snapshot(),provider).details));
        usagePanel.update(displayedUsage);
        ScrollView contents=new ScrollView(this); contents.addView(usagePanel);
        usageDialog=dialog().setTitle("Usage").setView(contents).setPositiveButton("Done",null).show();
        usageDialog.setOnDismissListener(d->{dialogs.remove(usageDialog);usagePanel=null;usageDialog=null;});
        bottomSheet(usageDialog);signalClient(true);
    }
    private void useResetCredit() {
        state=AgentService.snapshot();UsageDisplay usage=UsageDisplay.read(state,provider);
        if (!"codex".equals(provider) || !matchingSession() || !usage.canResetCredits) {toast("No reset credit is available right now.");return;}
        final String scopeProject=project==null?"":project.getName();
        final String accountToken=state.optString("accountScopeToken");
        if(accountToken.isEmpty()){toast("Wait for your account to finish updating.");return;}
        dialog().setTitle(usage.resetCanRetry?"Retry this reset?":"Use a reset credit?")
                .setMessage(usage.resetCanRetry?"Retry the same pending reset with OpenAI. This reuses its existing request, rather than requesting another reset.":"Use one earned reset credit on this account. Your limits update when OpenAI confirms the result.")
                .setPositiveButton(usage.resetCanRetry?"Retry reset":"Use credit",(d,w)->{
                    state=AgentService.snapshot();
                    if(!matchingSession()||project==null||!scopeProject.equals(project.getName())||!"codex".equals(provider)||!accountToken.equals(state.optString("accountScopeToken"))||!UsageDisplay.read(state,provider).canResetCredits){toast("Your account or usage changed. Review it again.");return;}
                    control("credits_reset",AgentProtocol.object("confirmed",true,"expectedAccountToken",accountToken));
                }).setNegativeButton("Cancel",null).show();
    }
    private void bottomSheet(AlertDialog sheet) {
        if(sheet.getWindow()==null)return;
        sheet.getWindow().setGravity(Gravity.BOTTOM);
        sheet.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels,dp(680)),-2);
    }
    private void openVoice() {
        if(!"codex".equals(provider)){dictate();return;}
        if(AppLock.isLocked(this))return;
        state=AgentService.snapshot();
        if(!matchingSession()||state.optString("threadId").isEmpty()){toast("Open a Codex chat first.");return;}
        if(sendPending){toast("Wait for your message to be accepted.");return;}
        if(inlineDictation!=null)inlineDictation.onPause();if(messageSpeech!=null)messageSpeech.stop();
        VoiceActivity.open(this,project.getName());
    }
    private void liveWorkspace() { if(project==null){projectPicker();return;} WorkspaceActivity.open(this,project.getName()); }

    private void projectPicker() {
        if (!ContainerRuntime.isWorkspaceInstalled(this)) { tab = 0; renderPage(); return; }
        work(() -> WorkspaceTools.projects(this), items -> {
            String[] names = new String[items.size() + 2];
            for(int i=0; i<items.size();i++) names[i] = items.get(i).getName();
            names[items.size()] = "+  Create project"; names[items.size()+1] = "↓  Clone repository";
            dialog().setTitle("Your projects").setItems(names, (d,w) -> {
                if (w == items.size()) createProject();
                else if (w == items.size()+1) cloneProject();
                else selectProject(items.get(w));
            }).setNegativeButton("Close", null).show();
        });
    }
    private void createProject() {
        if (!ContainerRuntime.isWorkspaceInstalled(this)) { toast("Prepare the workspace first."); return; }
        if (state.optBoolean("busy") || state.optBoolean("installing") || state.optBoolean("connecting") || state.optBoolean("sessionOpening")) { toast("Stop the agent before creating a project."); return; }
        EditText name = input("my-next-idea", true);
        dialog().setTitle("New project").setView(padded(name))
                .setPositiveButton("Create", (d,w) -> work(() -> { File made = WorkspaceTools.createProject(this, name.getText().toString().trim()); WorkspaceTools.initializeGit(this, made); return made; }, this::selectProject))
                .setNegativeButton("Cancel", (d,w) -> connectAfterProject = false).setOnCancelListener(d -> connectAfterProject = false).show();
    }
    private void cloneProject() {
        if (state.optBoolean("busy") || state.optBoolean("installing") || state.optBoolean("connecting") || state.optBoolean("sessionOpening")) { toast("Stop the agent before importing a project."); return; }
        LinearLayout fields = column(); fields.setPadding(dp(20), dp(8), dp(20), dp(8));
        EditText url = input("https://github.com/owner/project.git", true); url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText name = input("Project folder name", true); fields.addView(url); fields.addView(name);
        fields.addView(text("Connect GitHub in Project tools for private repositories.", 12, MUTED));
        dialog().setTitle("Clone repository").setView(fields)
                .setPositiveButton("Clone", (d,w) -> {
                    toast("Cloning repository…");
                    work(() -> WorkspaceTools.cloneProject(this, url.getText().toString(), name.getText().toString(), line -> {}), this::selectProject);
                }).setNegativeButton("Cancel", null).show();
    }
    private void selectProject(File chosen) {
        if(switchingBlocked())return;
        String next=prefs.getString("project_agent_"+chosen.getName(),provider);if(!AgentCatalog.isValid(next))next=provider;
        if(project!=null&&project.equals(chosen)&&next.equals(provider)){tab=0;renderPage();return;}
        requestWorkspaceSwitch(next,chosen);
    }
    private boolean needProject() {
        if (project != null && ContainerRuntime.isWorkspaceInstalled(this)) return false;
        body.addView(label("Open a project", 24, INK), lp(-1, -2, 15, 10));
        body.addView(text("Prepare the workspace, then create or open a project from the menu above.", 15, MUTED));
        body.addView(actionButton("Set up workspace", "settings", true, v -> { tab = 0; renderPage(); }), lp(-1, 50, 18, 0)); return true;
    }

    private void filesPage() {
        if (needProject()) return;
        body.addView(label("Files", 22, INK));
        body.addView(text(folder.isEmpty()?project.getName():folder,12,MUTED),lp(-1,-2,4,12));
        LinearLayout controls=row(); controls.addView(actionButton("New file","new",false,v->newFile()),new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(0,-2,1);gap.leftMargin=dp(8);
        controls.addView(actionButton("Export ZIP","export",false,v->exportProject()),gap);body.addView(controls);
        body.addView(actionButton("Git & import","folder",false,v->openProjectTools()));
        body.addView(actionButton("Add files","attach",false,v->pickFiles()));
        if(!folder.isEmpty())body.addView(iconButton("Parent folder","chevron_left",false,v->{int slash=folder.lastIndexOf('/');folder=slash<0?"":folder.substring(0,slash);renderPage();}),lp(48,48,0,0));
        workspaceStatus=text("Loading…",12,MUTED);body.addView(workspaceStatus,lp(-1,-2,8,8));
        fileRows=column();body.addView(fileRows);readWorkspace(true);
    }
    private void readWorkspace(boolean immediate) {
        if(!resumed||AppLock.isLocked(this)||project==null||workspaceReadBusy||WorkspaceTools.isProjectOperationBusy()||(tab!=1&&tab!=2))return;
        if(!dialogs.isEmpty()||(!immediate&&android.os.SystemClock.elapsedRealtime()-workspaceReadAt<workspaceDelay))return;
        final int generation=renderId, page=tab;final File selected=project;final String path=folder;
        workspaceReadBusy=true;workspaceReadAt=android.os.SystemClock.elapsedRealtime();
        io.execute(()->{
            List<WorkspaceTools.Entry> entries=null;String diff=null,error=null;
            try { if(page==1)entries=WorkspaceTools.listFiles(this,selected,path);else diff=WorkspaceTools.gitStatus(this,selected)+"\n\n"+WorkspaceTools.gitDiff(this,selected); }
            catch(Exception failure){error="Couldn't load this workspace. Retrying automatically.";}
            final List<WorkspaceTools.Entry> listing=entries;final String output=diff,problem=error;
            main.post(()->{workspaceReadBusy=false;deliverUi(()->{
                if(generation!=renderId||page!=tab||project==null||!project.equals(selected))return;
                if(problem!=null){workspaceDelay=Math.min(60000,workspaceDelay*2);if(workspaceStatus!=null){workspaceStatus.setVisibility(View.VISIBLE);workspaceStatus.setText(problem);workspaceStatus.setTextColor(RED);}return;}
                workspaceDelay=5000;if(workspaceStatus!=null){workspaceStatus.setText("");workspaceStatus.setVisibility(View.GONE);}
                if(page==2&&changesOutput!=null){String value=output==null||output.trim().isEmpty()?"No changes":output;if(!value.contentEquals(changesOutput.getText()))changesOutput.setText(value);return;}
                if(fileRows==null||listing==null)return;
                StringBuilder fingerprint=new StringBuilder();for(WorkspaceTools.Entry entry:listing)fingerprint.append(entry.path).append('|').append(entry.directory).append('|').append(entry.size).append('\n');
                if(fingerprint.toString().equals(fileListSignature)&&fileRows.getChildCount()>0)return;
                fileListSignature=fingerprint.toString();fileRows.removeAllViews();
                if(listing.isEmpty())fileRows.addView(text("No files yet",14,MUTED));
                for(WorkspaceTools.Entry entry:listing){
                    LinearLayout line=row();line.setPadding(dp(10),dp(10),dp(10),dp(10));line.setMinimumHeight(dp(56));
                    TextView name=label(entry.name,14,INK);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);decorate(name,entry.directory?"folder":"file",MUTED,false);
                    line.addView(name,new LinearLayout.LayoutParams(0,-2,1));if(!entry.directory)line.addView(text(readableSize(entry.size),11,MUTED));
                    line.setBackground(DeskStyle.plain(this));line.setOnClickListener(v->{if(entry.directory){folder=entry.path;renderPage();}else fileActions(entry.path);});fileRows.addView(line);
                }
            });});
        });
    }
    private void fileActions(String path) {
        if(project==null||AppLock.isLocked(this))return;
        final File selected=project;
        dialog().setTitle(new File(path).getName()).setItems(new String[]{"Preview, play, save or share","Edit as text","Add to message @","Rename file","Delete file"},(d,w)-> {
            if(project==null||!project.equals(selected))return;
            if(w==0)WorkspaceMedia.openPreview(this,selected,path);
            else if(w==1)editFile(path);
            else if(w==2)attachProjectPath(path);
            else if(w==3) {
                EditText name=input("New file name",true);name.setText(new File(path).getName());
                AlertDialog rename=dialog().setTitle("Rename file").setView(padded(name)).setPositiveButton("Rename",null).setNegativeButton("Cancel",null).show();
                rename.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                    String next=name.getText().toString().trim();
                    if(next.isEmpty()||next.contains("/")||next.contains("\\")){name.setError("Enter one file name.");return;}
                    work(()->{WorkspaceTools.renameFile(this,selected,path,next);return true;},ok->{rename.dismiss();clearAttachments();if(project!=null&&project.equals(selected))renderPage();});
                });
            } else dialog().setTitle("Delete this file?").setMessage(path+"\n\nThis removes the project file from PocketAgent. This action cannot be undone here.")
                    .setPositiveButton("Delete",(dd,ww)->work(()->{WorkspaceTools.deleteFile(this,selected,path);return true;},ok->{clearAttachments();if(project!=null&&project.equals(selected))renderPage();}))
                    .setNegativeButton("Cancel",null).show();
        }).setNegativeButton("Close",null).show();
    }

    private void openChatLink(String value) {
        if(AppLock.isLocked(this)||project==null)return;
        final String scope=currentDictationScope();
        try {
            Uri link=Uri.parse(value);
            if("https".equalsIgnoreCase(link.getScheme())) {
                java.net.URI checked=new java.net.URI(value);
                if(checked.getHost()==null||checked.getUserInfo()!=null)throw new IllegalArgumentException("Invalid destination.");
                String path=checked.getPath()==null?"":checked.getPath().toLowerCase(Locale.ROOT);
                if(path.matches(".*\\.(apk|aab|zip|tar|gz|tgz|7z|pdf|txt|md|java|kt|py|js|ts|json|csv|xml|html|css|c|cpp|h|rs|go|sh|docx|xlsx|pptx|bin)$")){downloadChatFile(value,scope);return;}
                if(RemoteMediaCards.openLink(this,value))return;
                dialog().setTitle("Open "+checked.getHost()+"?").setMessage("This link was included in the conversation. Review the destination before sharing any account or project information.")
                        .setPositiveButton("Open browser",(d,w)->{if(AppLock.isLocked(this)||!scope.equals(currentDictationScope()))return;try{startActivity(new Intent(Intent.ACTION_VIEW,link).addCategory(Intent.CATEGORY_BROWSABLE));}catch(RuntimeException missing){error("Could not open browser.");}})
                        .setNeutralButton("Download file",(d,w)->downloadChatFile(value,scope))
                        .setNegativeButton("Cancel",null).show();return;
            }
            final File selected=project;
            toast("Opening file…");
            work(()->WorkspaceMedia.resolveArtifactCopy(this,selected,value),uri->{
                if(!scope.equals(currentDictationScope())||AppLock.isLocked(this))return;
                WorkspaceMedia.openSharedPreview(this,uri);
            });
        } catch(Exception invalid){toast("This link cannot be opened safely.");}
    }

    private void downloadChatFile(String url,String scope) {
        if(AppLock.isLocked(this)||!scope.equals(currentDictationScope()))return;
        toast("Downloading file…");
        work(()->WorkspaceMedia.remoteCopy(this,url),uri->{if(scope.equals(currentDictationScope())&&!AppLock.isLocked(this))WorkspaceMedia.openSharedPreview(this,uri);});
    }

    private void copyText(String value) {
        if(AppLock.isLocked(this))return;
        android.content.ClipData clip=android.content.ClipData.newPlainText("PocketAgent",value);
        android.os.PersistableBundle extra=new android.os.PersistableBundle();extra.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extra);
        ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);toast("Copied");
    }
    private void copyMessage(String value) {
        showMenu("Copy",new String[]{"Copy text","Copy Markdown"},new String[]{"copy","copy"},
                new Runnable[]{()->copyText(MarkdownBlocks.plainText(value)),()->copyText(value)});
    }
    private void shareMessage(String value) {
        if(AppLock.isLocked(this))return;
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,value),"Share message"));
    }
    private void messageMenu(String value,String role) {
        if(AppLock.isLocked(this))return;
        showMenu("Message",new String[]{"Copy text","Copy Markdown","user".equals(role)?"Edit as new draft":"Quote","Share","Save as text"},
                new String[]{"copy","copy","edit","share","download"},new Runnable[]{()->copyText(MarkdownBlocks.plainText(value)),()->copyText(value),
                    ()->{if(sendPending)return;setDraft(value);navigate(0);prompt.requestFocus();},()->shareMessage(value),()->saveCode("message.txt","text/plain",MarkdownBlocks.plainText(value))});
    }
    private void saveCode(String name,String mime,String content) {
        if(AppLock.isLocked(this))return;
        String safeName=new File(name==null?"code.txt":name).getName().replaceAll("[^A-Za-z0-9._-]","_");
        if(safeName.isEmpty()||safeName.equals(".")||safeName.equals(".."))safeName="code.txt";
        if(pendingCodeExport){toast("Finish saving the current file first.");return;}pendingCodeName=safeName;pendingCodeText=content;pendingCodeExport=true;
        try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime==null?"text/plain":mime).putExtra(Intent.EXTRA_TITLE,safeName),SAVE_CODE);}
        catch(RuntimeException unavailable){pendingCodeText="";pendingCodeName="";pendingCodeExport=false;toast("No system file picker is available.");}
    }
    private void addMessageActions(LinearLayout parent,String id,String text,String role,JSONObject message,String scope) {
        LinearLayout actions=row();actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(iconButton("Copy message","copy",false,v->{if(scope.equals(currentDictationScope()))copyMessage(currentMessage(id,text));}),new LinearLayout.LayoutParams(dp(48),dp(48)));
        if("assistant".equals(role)){
            Button speaker=iconButton("Read aloud","speaker",false,v->{if(!scope.equals(currentDictationScope())||AppLock.isLocked(this))return;
                if(inlineDictation!=null&&inlineDictation.active()){toast("Finish dictation before reading aloud.");return;}
                if(messageSpeech.isReading(id))messageSpeech.stop();else messageSpeech.showOptions(id,currentMessage(id,text));});
            actions.addView(speaker,new LinearLayout.LayoutParams(dp(48),dp(48)));messageSpeakers.put(id,speaker);
        }
        actions.addView(iconButton("Share message","share",false,v->{if(scope.equals(currentDictationScope()))shareMessage(currentMessage(id,text));}),new LinearLayout.LayoutParams(dp(48),dp(48)));
        actions.addView(iconButton("Message options","more",false,v->{if(scope.equals(currentDictationScope()))messageMenu(currentMessage(id,text),role);}),new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView time=text("",10,MUTED);time.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);time.setMaxLines(2);
        long timestamp=message.optLong("completedAt");if(timestamp<=0)timestamp=message.optLong("time",message.optLong("timestamp",message.optLong("createdAt")));time.setTag(timestamp);
        actions.addView(time,new LinearLayout.LayoutParams(0,dp(48),1));messageTimes.put(id,time);parent.addView(actions,lp(-1,-2,4,0));
    }
    private void updateMessageFooters() {
        long now=System.currentTimeMillis();for(TextView time:messageTimes.values()){Object stamp=time.getTag();if(stamp instanceof Long)time.setText(MessageTime.relative((Long)stamp,now));}
        if(messageSpeech!=null)for(Map.Entry<String,Button> entry:messageSpeakers.entrySet()){
            boolean playing=messageSpeech.isReading(entry.getKey());Button button=entry.getValue();decorateComposer(button,playing?"stop":"speaker",MUTED);
            button.setContentDescription(playing?"Stop reading":"Read aloud. Choose English or Hindi");button.setTooltipText(playing?"Stop reading":"Read aloud · "+messageSpeech.languageLabel());}
    }
    private void addAgentActivity(JSONObject message) {
        String id=message.optString("id");JSONObject activity=message.optJSONObject("activity");
        String kind=activity==null?"tool":activity.optString("kind");
        LinearLayout container=column();TextView heading=menuRow("Agent activity","reasoning".equals(kind)?"effort":"search".equals(kind)?"search":"terminal");
        heading.setTextSize(13);heading.setTextColor(MUTED);heading.setCompoundDrawablesWithIntrinsicBounds(DeskStyle.icon(this,"reasoning".equals(kind)?"effort":"search".equals(kind)?"search":"terminal",MUTED),null,DeskStyle.icon(this,"chevron_right",MUTED),null);
        TextView details=selectable("",13,MUTED);details.setPadding(dp(12),0,dp(12),dp(10));details.setVisibility(expandedActivities.contains(id)?View.VISIBLE:View.GONE);
        heading.setOnClickListener(v->{if(AppLock.isLocked(this))return;if(expandedActivities.contains(id))expandedActivities.remove(id);else expandedActivities.add(id);details.setVisibility(expandedActivities.contains(id)?View.VISIBLE:View.GONE);heading.setContentDescription((expandedActivities.contains(id)?"Collapse ":"Expand ")+heading.getText());});
        container.addView(heading);container.addView(details);feed.addView(container,lp(-1,-2,0,2));activityHeaders.put(id,heading);activityBodies.put(id,details);
    }
    private void updateActivityViews(JSONArray messages) {
        if(messages==null)return;
        for(int i=0;i<messages.length();i++){JSONObject message=messages.optJSONObject(i);if(message==null)continue;String id=message.optString("id");
            TextView heading=activityHeaders.get(id),body=activityBodies.get(id);if(heading==null||body==null)continue;JSONObject activity=message.optJSONObject("activity");
            String title=activity==null?"Agent activity":activity.optString("title","Agent activity"),status=activity==null?"":activity.optString("status");
            heading.setText(title+("running".equals(status)?"…":"failed".equals(status)?" · Failed":"cancelled".equals(status)?" · Stopped":"pending".equals(status)?" · Waiting":"completed".equals(status)?" · Done":""));
            String summary=activity==null?"":activity.optString("summary"),detail=activity==null?message.optString("text"):activity.optString("details");
            String value=summary.isEmpty()?detail:detail.isEmpty()||summary.equals(detail)?summary:summary+"\n\n"+detail;
            if(value.isEmpty())value="reasoning".equals(activity==null?"":activity.optString("kind"))?"No reasoning summary was provided.":"Waiting for activity details…";
            if(!value.equals(body.getTag())){body.setTag(value);body.setText(ChatMarkdown.render(value,FIELD,ACCENT,this::openChatLink));}
            heading.setContentDescription((expandedActivities.contains(id)?"Collapse ":"Expand ")+heading.getText());
        }
    }
    private String currentMessage(String id,String fallback) {
        JSONArray messages=AgentService.snapshot().optJSONArray("messages");
        if(messages!=null)for(int i=0;i<messages.length();i++){JSONObject row=messages.optJSONObject(i);if(row!=null&&id.equals(row.optString("id")))return row.optString("text",fallback);}
        return fallback;
    }

    private void addRemoteMedia(LinearLayout parent,String text,JSONArray media) {
        final int generation=renderId,transcript=transcriptId;final File selected=project;
        RemoteMediaCards.append(this,parent,text,media,()->generation==renderId&&transcript==transcriptId&&tab==0&&resumed&&project!=null&&project.equals(selected)&&parent.isAttachedToWindow()&&!AppLock.isLocked(this));
    }

    private void addArtifactCards(LinearLayout parent,String markdown) {
        addArtifactCards(parent,markdown,null);
    }
    private void addArtifactCards(LinearLayout parent,String markdown,JSONArray attachments) {
        if(project==null || (attachments==null && markdown.indexOf("](")<0))return;
        final File selected=project;final int generation=renderId,transcript=transcriptId;
        work(()-> {
            JSONArray rows=WorkspaceMedia.artifacts(this,selected,markdown);
            if(attachments!=null)for(int i=0;i<attachments.length() && rows.length()<12;i++) {
                String path=attachments.optString(i);if(path.isEmpty())continue;
                try {JSONObject item=WorkspaceMedia.describe(this,selected,path);boolean duplicate=false;for(int j=0;j<rows.length();j++)if(path.equals(rows.optJSONObject(j).optString("path")))duplicate=true;if(!duplicate)rows.put(item);}catch(Exception ignored){}
            }
            return rows;
        },items -> {
            if(generation!=renderId||transcript!=transcriptId||tab!=0||project==null||!project.equals(selected)||parent.getParent()==null)return;
            for(int i=0;i<items.length();i++) {
                if(i>=4||artifactCardsRendered>=24){parent.addView(text("More files are available from the message links or menu → Files.",11,MUTED));break;}
                JSONObject item=items.optJSONObject(i);if(item==null)continue;String path=item.optString("path"),kind=item.optString("kind");
                artifactCardsRendered++;
                LinearLayout card=column();card.setPadding(dp(10),dp(8),dp(10),dp(8));card.setBackground(DeskStyle.field(this));
                card.addView(label(item.optString("name"),13,INK));
                if("image".equals(kind) && thumbnailsRendered<8) {
                    thumbnailsRendered++;
                    ImageView preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setAdjustViewBounds(true);preview.setContentDescription("Image preview: "+item.optString("name"));
                    preview.setOnClickListener(v->{if(!AppLock.isLocked(this)&&project!=null&&project.equals(selected))WorkspaceMedia.openPreview(this,selected,path);});
                    card.addView(preview,new LinearLayout.LayoutParams(-1,dp(150)));
                    work(()->WorkspaceMedia.thumbnail(this,selected,path,640),bitmap->{if(bitmap!=null){if(generation==renderId&&transcript==transcriptId&&parent.getParent()!=null)preview.setImageBitmap(bitmap);else bitmap.recycle();}});
                }
                String action="video".equals(kind)||"audio".equals(kind)?"Play":"Open";
                card.addView(actionButton(action,"video".equals(kind)?"video":"download",false,v->WorkspaceMedia.openPreview(this,selected,path)));
                parent.addView(card,lp(-1,-2,10,0));
            }
        });
    }
    private void newFile() {
        EditText name = input("index.js", true);
        dialog().setTitle("New text file").setView(padded(name)).setPositiveButton("Create", (d,w) -> {
            String path = (folder.isEmpty() ? "" : folder + "/") + name.getText().toString().trim();
            work(() -> { File f = new File(project, path); if (f.exists()) throw new java.io.IOException("A file with this name already exists."); WorkspaceTools.saveText(this, project, path, ""); return path; }, this::editFile);
        }).setNegativeButton("Cancel", null).show();
    }
    private void editFile(String path) {
        final File selected = project;
        work(() -> WorkspaceTools.readText(this, selected, path), contents -> {
            LinearLayout layout = column(); layout.setPadding(dp(14), dp(8), dp(14), dp(8));
            EditText editor = input("File contents", false); editor.setText(contents); editor.setTypeface(Typeface.MONOSPACE); editor.setTextSize(13);
            editor.setGravity(Gravity.TOP | Gravity.START); editor.setMinLines(12); editor.setMaxLines(22); editor.setHorizontallyScrolling(true);
            editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            layout.addView(editor, new LinearLayout.LayoutParams(-1, dp(350)));
            dialog().setTitle(path).setView(layout)
                    .setPositiveButton("Save", (d,w) -> {
                        if (state.optBoolean("busy")) { toast("Wait for the agent to finish before editing its files."); return; }
                        String edited = editor.getText().toString();
                        work(() -> { WorkspaceTools.saveTextIfUnchanged(this, selected, path, edited, contents); return true; }, ok -> { toast("File saved"); if (tab == 1) renderPage(); });
                    })
                    .setNeutralButton("Add to chat", (d,w) -> attachProjectPath(path))
                    .setNegativeButton("Close", null).show();
        });
    }
    private void attachFile() {
        if (project == null) { projectPicker(); return; }
        toast("Open a project file, then tap Add to chat."); tab = 1; renderPage();
    }
    private void exportProject() {
        if (project == null) return;
        if (state.optBoolean("busy")) { toast("Finish the agent turn before taking a project snapshot."); return; }
        exportingProject = project;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, project.getName() + "-source.zip");
        startActivityForResult(intent, 71);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (AppLock.handleResult(this, lockShell, request, result, () -> { refreshState(); drainUi(); })) return;
        deliverUi(() -> externalResult(request,result,data));
    }
    private void externalResult(int request,int result,Intent data) {
        if(request==SAVE_CODE){
            boolean pending=pendingCodeExport;String text=pendingCodeText;pendingCodeText="";pendingCodeName="";pendingCodeExport=false;
            if(pending&&result==RESULT_OK&&data!=null&&data.getData()!=null&&"content".equals(data.getData().getScheme())){final Uri destination=data.getData();
                work(()->{try(java.io.OutputStream out=getContentResolver().openOutputStream(destination,"wt")){if(out==null)throw new java.io.IOException("Could not open this file.");out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}return true;},ok->toast("File saved"));}
            return;
        }
        if(request==DICTATE) {
            String expected=dictationScope;dictationScope="";
            if(result!=RESULT_OK||data==null)return;
            if(expected.isEmpty()||!expected.equals(currentDictationScope())){toast("The chat changed. Dictate again in this chat.");return;}
            java.util.ArrayList<String> words=data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if(words!=null && !words.isEmpty())setDraft(prompt.getText().toString()+(prompt.length()>0?" ":"")+words.get(0)); return;
        }
        if(request==PICK_FILES && result==RESULT_OK && data!=null) {
            if(project==null||!project.getName().equals(pendingImportProject)){toast("Select the original project before importing these files.");return;}
            final File target=project; java.util.ArrayList<Uri> selected=new java.util.ArrayList<>();
            if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount()&&i<12;i++)selected.add(data.getClipData().getItemAt(i).getUri());
            else if(data.getData()!=null)selected.add(data.getData());
            pendingImportProject="";
            work(() -> { JSONArray imported=new JSONArray(); for(Uri uri:selected)imported.put(WorkspaceMedia.importUri(this,target,uri));return imported; }, imported -> {
                if(project==null||!target.equals(project))return;
                for(int i=0;i<imported.length();i++){JSONObject entry=imported.optJSONObject(i);if(entry!=null)addAttachment(entry);}
            });return;
        }
        if(request==ProjectToolsActivity.REQUEST && result==RESULT_OK) {
            try{File selected=WorkspaceTools.selectedProject(this);if(selected!=null)selectProject(selected);}catch(Exception failed){error(failed.getMessage());}return;
        }
        if(request==EnvironmentActivity.REQUEST && result==RESULT_OK && data!=null) {
            if(project!=null&&project.getName().equals(data.getStringExtra(AgentService.EXTRA_PROJECT))&&"codex".equals(provider)) {
                String draft=data.getStringExtra(EnvironmentActivity.EXTRA_DRAFT);if(draft!=null&&!draft.isEmpty()){setDraft(draft+(prompt.length()>0?"\n\n"+prompt.getText():""));tab=0;renderPage();}
            }return;
        }
        if (request == ConnectionsActivity.REQUEST && result == RESULT_OK && data != null) {
            if (project == null || !project.getName().equals(data.getStringExtra(AgentService.EXTRA_PROJECT)) || !"codex".equals(provider)) {
                toast("Open tools for the currently selected Codex project."); return;
            }
            if (data.getBooleanExtra(ConnectionsActivity.EXTRA_CONNECT, false)) { tab = 0; renderPage(); if (!AppLock.isLocked(this)) connect(); return; }
            if (sendPending) { toast("Wait for the current message, then choose the app or skill again. Your connection is saved."); return; }
            String path = data.getStringExtra(ConnectionsActivity.EXTRA_SKILL_PATH);
            if (path != null && !path.isEmpty()) { selectedSkillPath = path; selectedSkillName = safe(data.getStringExtra(ConnectionsActivity.EXTRA_SKILL_NAME)); }
            String appId = data.getStringExtra(ConnectionsActivity.EXTRA_APP_ID);
            if (appId != null && !appId.isEmpty()) { selectedAppId = appId; selectedAppName = safe(data.getStringExtra(ConnectionsActivity.EXTRA_APP_NAME)); }
            String draft = data.getStringExtra(ConnectionsActivity.EXTRA_DRAFT);
            if (draft != null && !draft.isEmpty()) prompt.setText(draft + prompt.getText().toString());
            tab = 0; renderPage(); prompt.requestFocus(); return;
        }
        if (request == 71 && result == RESULT_OK && data != null && data.getData() != null && exportingProject != null) {
            final Uri target = data.getData(); final File selected = exportingProject; exportingProject = null;
            work(() -> {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
                    if (out == null) throw new java.io.IOException("Could not open the export destination.");
                    WorkspaceTools.exportZip(this, selected, out);
                }
                return true;
            }, ok -> toast("Source ZIP saved. Dependencies, Git history and private configuration files are excluded."));
        }
    }

    private void changesPage() {
        if(needProject())return;
        body.addView(label("Changes",22,INK));
        body.addView(actionButton("Git & commits","changes",false,v->openProjectTools()));
        if("codex".equals(provider))body.addView(actionButton("Review","check",false,v->runSlash("review","")));
        workspaceStatus=text("Loading…",12,MUTED);body.addView(workspaceStatus,lp(-1,-2,10,8));
        changesOutput=selectable("",13,INK);changesOutput.setTypeface(Typeface.MONOSPACE);body.addView(changesOutput);readWorkspace(true);
    }

    private void previewPage() {
        if (needProject()) return;
        body.addView(label("Preview", 22, INK));
        LinearLayout web = card(); web.addView(label("Web preview", 19, INK));
        web.addView(text("HTML pages or a project dev server.", 13, MUTED), lp(-1, -2, 8, 12));
        web.addView(actionButton("Preview HTML", "preview", true, v -> startPreview(false)));
        web.addView(actionButton("Start app preview", "preview", false, v -> dialog().setTitle("Run this project's code?")
                .setMessage("The dev script and its dependencies can access this app's Ubuntu files and network. Run projects you trust. The server will listen on this phone only.")
                .setPositiveButton("Run preview", (d,w) -> startPreview(true)).setNegativeButton("Cancel", null).show()));
        web.addView(actionButton("Stop preview", "stop", false, v -> { prefs.edit().putBoolean("open_preview_when_ready", false).apply(); startService(new Intent(this, PreviewService.class).setAction(PreviewService.ACTION_STOP)); toast("Stopping preview"); }));
        web.addView(actionButton("Set up preview", "chat", false, v -> { tab = 0; renderPage(); fillPrompt("Prepare this project for local preview. Inspect its README and package scripts, install the required dependencies after my approval, then tell me which preview option and port to use."); }));
        body.addView(web, lp(-1, -2, 0, 16));
        LinearLayout port = card(); port.addView(label("Open a port", 18, INK));
        EditText number = input("Local port, e.g. 3000", true); number.setInputType(InputType.TYPE_CLASS_NUMBER); port.addView(number);
        port.addView(actionButton("Open preview port", "preview", false, v -> { try { int p = Integer.parseInt(number.getText().toString()); PreviewActivity.open(this, p); } catch(Exception e) { toast("Enter a port between 1024 and 65535."); } })); body.addView(port);
        body.addView(actionButton("Preview details","info",false,v->details("Preview","Web preview runs on this phone. Native Android/iOS apps need their own build tools and runtime. This is not a phone emulator.")));
        body.addView(actionButton("Test & build", "chat", false, v -> { tab = 0; renderPage(); fillPrompt("Inspect this project's build instructions. Run its meaningful tests and production build, installing missing tools only after my approval. Report actual results and where the output artifacts are saved. Do not deploy or publish."); }), lp(-1, 50, 14, 0));
    }
    private void startPreview(boolean npm) {
        notificationPermission(); prefs.edit().putBoolean("open_preview_when_ready", true).apply(); toast("Starting preview…");
        startForegroundService(new Intent(this, PreviewService.class).setAction(PreviewService.ACTION_START)
                .putExtra(PreviewService.EXTRA_PROJECT, project.getName()).putExtra(PreviewService.EXTRA_NPM, npm));
    }

    private void accountPage() {
        state=AgentService.snapshot();body.addView(label("Account",22,INK));
        LinearLayout account=card();account.addView(label(providerName(provider),18,INK));
        String unavailable=AgentInstaller.unavailableReason(provider);
        if(unavailable!=null&&!unavailable.isEmpty())account.addView(actionButton("Availability","info",false,v->details(providerName(provider),unavailable)));
        accountDescription=text("",15,MUTED);account.addView(accountDescription,lp(-1,-2,10,8));
        accountStatus=text("",13,MUTED);account.addView(accountStatus,lp(-1,-2,0,8));
        accountProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);accountProgress.setIndeterminate(true);account.addView(accountProgress);
        accountCode=selectable("",16,ACCENT);account.addView(accountCode,lp(-1,-2,8,8));
        accountPrimary=actionButton("Connect","account",true,v->connect());account.addView(accountPrimary);
        accountCancel=actionButton("Cancel","stop",false,v->cancelConnection());account.addView(accountCancel);
        account.addView(actionButton("Manage account","account",false,v->openProviderAccount()));
        account.addView(actionButton("Switch agent","model",false,v->providerPicker()));
        account.addView(actionButton("Disconnect","stop",false,v->{agentAction(AgentService.ACTION_DISCONNECT);main.postDelayed(this::refreshState,200);}));
        if("codex".equals(provider))account.addView(actionButton("Sign out","logout",false,v->logout()));
        body.addView(account,lp(-1,-2,12,14));
        LinearLayout usage=card();usage.addView(label("Usage",16,INK));accountUsage=text("",14,MUTED);usage.addView(accountUsage,lp(-1,-2,8,8));
        usage.addView(actionButton("View usage","usage",false,v->usageDetails()));body.addView(usage,lp(-1,-2,0,14));
        if("codex".equals(provider)) {
            body.addView(actionButton("Apps & tools","settings",false,v->openConnections(0)));
            body.addView(actionButton("Environment","settings",false,v->{if(project==null)projectPicker();else EnvironmentActivity.open(this,project.getName());}));
        }
        body.addView(actionButton("Privacy & notifications","settings",false,v->AccountSettingsActivity.open(this,provider)));
        body.addView(actionButton("Appearance","theme",false,v->startActivity(new Intent(this,AppearanceActivity.class))));
        body.addView(actionButton("Chats & tasks","history",false,v->openSessions()));
        body.addView(actionButton("Saved history","file",false,v->chatHistory()));
        body.addView(actionButton("Ubuntu settings","settings",false,v->{
            if(state.optBoolean("connected")||state.optBoolean("installing")){toast("Disconnect the agent first.");return;}
            JSONObject preview=PreviewService.snapshot();if(preview.optBoolean("running")||preview.optBoolean("starting")){toast("Stop preview first.");return;}
            startActivity(new Intent(this,MainActivity.class));
        }));
        body.addView(actionButton("About PocketAgent","info",false,v->details("PocketAgent", "Version "+MainActivity.VERSION+"\n\nNative controls for official coding engines. The workspace runs on this phone. Available models, effort levels and account tools update from the engine automatically. New engine protocols still need a tested app update.\n\nThis build requires real-device and account testing. PRoot is not a security sandbox; approved commands can access this app's workspace, credentials and network.")));
        body.addView(actionButton("Diagnostics","file",false,v->diagnostics()));updateAccountControls();
    }
    private void openProviderAccount() {
        if(AppLock.isLocked(this))return;
        for(ProviderAccountLinks.Link link:ProviderAccountLinks.forProvider(provider))if("account".equals(link.id)){openAuth(link.url);return;}
    }

    private void updateAccountControls() {
        if (accountPrimary == null || accountStatus == null) return;
        ConnectionFlow.Step step = connectionStep();
        boolean matching = provider.equals(state.optString("provider", provider));
        JSONObject info = matching ? state.optJSONObject("account") : null;
        String description = "Sign in to your account";
        if (info != null && info.length() > 0) {
            description = first(info.optString("email"), info.optString("name"), "Official account");
            String plan = first(info.optString("planType"), info.optString("plan"), info.optString("type"));
            if (!plan.isEmpty()) description += "\nPlan: " + plan;
        }
        accountDescription.setText(description);
        String status;
        if (step == ConnectionFlow.Step.SETUP) {
            status = LinuxService.lastWasError() ? safe(LinuxService.lastMessage()) + "\n" + safe(LinuxService.lastDetail())
                    : "First set up Ubuntu on this phone. Progress opens in Chat; account connection continues after setup.";
        } else if (step == ConnectionFlow.Step.WAIT_SETUP) status = first(LinuxService.lastMessage(), "Preparing the workspace…") + "\n" + safe(LinuxService.lastDetail());
        else if (step == ConnectionFlow.Step.PROJECT) status = "Create or select a project first. Connection continues after you create it.";
        else if (connectionRequestPending) status = "Starting " + providerName(provider) + "…";
        else status = matching ? userStatus() : "Ready to connect your account.";
        JSONObject control = matching ? state.optJSONObject("controls") : null;
        boolean controlBusy = control != null && !control.optString("operation").isEmpty();
        boolean controlFailed = control != null && !control.optString("error").isEmpty();
        if (controlBusy || controlFailed) status = first(control.optString("error"), control.optString("status"), "Working…");
        accountStatus.setText(status);
        accountStatus.setTextColor(controlFailed || (matching && state.optBoolean("error")) || (step == ConnectionFlow.Step.SETUP && LinuxService.lastWasError()) ? RED : MUTED);
        boolean waiting = ConnectionFlow.waiting(step);
        accountProgress.setVisibility(waiting || controlBusy ? View.VISIBLE : View.GONE);
        accountPrimary.setText(step == ConnectionFlow.Step.WAIT_AGENT && state.optBoolean("installing")
                ? "Installing " + providerName(provider) + "…" : step == ConnectionFlow.Step.CONNECT && state.optBoolean("accountConnected")
                ? "Retry chat connection" : ConnectionFlow.button(step));
        accountPrimary.setVisibility(matching && state.optBoolean("connected") ? View.GONE : View.VISIBLE);
        accountPrimary.setEnabled(!waiting && !controlBusy); accountPrimary.setAlpha(waiting || controlBusy ? .55f : 1f);
        accountCancel.setVisibility(waiting || step == ConnectionFlow.Step.SIGN_IN ? View.VISIBLE : View.GONE);
        accountCancel.setText(step == ConnectionFlow.Step.WAIT_SETUP ? "Pause setup" : "Cancel connection");
        String code = matching ? state.optString("authCode") : "";
        accountCode.setText(code.isEmpty() ? "" : "Sign-in code: " + code);
        accountCode.setVisibility(code.isEmpty() ? View.GONE : View.VISIBLE);
        accountUsage.setText(UsageDisplay.read(state, provider).compact);
    }
    private void openAuth(String value) {
        try {
            Uri uri = Uri.parse(value); String host = uri.getHost();
            if (!("https".equals(uri.getScheme()) && host != null) && !("http".equals(uri.getScheme()) && ("localhost".equals(host) || "127.0.0.1".equals(host)))) throw new IllegalArgumentException("Unsupported sign-in URL");
            // User explicitly opens the URL surfaced by the official agent; no embedded credential form.
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (Exception e) { error("Could not open the official sign-in page: " + e.getMessage()); }
    }
    private void notificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 31);
    }
    private void maybeOpenPreview(int port) {
        deliverUi(()->{if(port>0&&prefs.getBoolean("open_preview_when_ready",false)){prefs.edit().putBoolean("open_preview_when_ready",false).apply();PreviewActivity.open(this,port);}});
    }

    private interface Job<T> { T run() throws Exception; }
    private interface Done<T> { void done(T result); }
    private <T> void work(Job<T> job, Done<T> done) {
        io.execute(() -> {
            try { T result = job.run(); main.post(() -> deliverUi(() -> done.done(result))); }
            catch (Exception e) { main.post(() -> deliverUi(() -> error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))); }
        });
    }
    private void deliverUi(Runnable task) {
        if(isFinishing()||isDestroyed())return;
        if(!resumed||AppLock.isLocked(this)){pendingUi.add(task);return;}
        task.run();
    }
    private void drainUi() {
        if(!resumed||AppLock.isLocked(this))return;
        java.util.ArrayList<Runnable> tasks=new java.util.ArrayList<>(pendingUi);pendingUi.clear();
        for(Runnable task:tasks)deliverUi(task);
    }
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this) {
            @Override public AlertDialog show() {
                AlertDialog value = super.create();
                if (value.getWindow() != null) {
                    if (AppLock.enabled(DeskActivity.this)) value.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                    value.getWindow().setBackgroundDrawable(DeskStyle.card(DeskActivity.this));
                }
                dialogs.add(value);
                value.setOnDismissListener(ignored -> dialogs.remove(value));
                value.show();
                return value;
            }
        };
    }
    private void error(String value) {
        if (!resumed || AppLock.isLocked(this)) {deliverUi(()->error(value));return;}
        dialog().setTitle("Couldn't complete that").setMessage(value).setPositiveButton("OK", null).show();
    }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private String providerName(String id) { for(int i=0;i<PROVIDERS.length;i++) if(PROVIDERS[i].equals(id)) return NAMES[i]; return "Agent"; }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String first(String... values) { for (String v : values) if (v != null && !v.isEmpty() && !v.equals("null")) return v; return ""; }
    private static String readableSize(long bytes) { return bytes < 1024 ? bytes + " B" : bytes < 1048576 ? (bytes / 1024) + " KB" : String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0); }
    private int dp(int v) { return Ui.dp(this, v); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout card() { LinearLayout v = column(); v.setPadding(dp(18), dp(18), dp(18), dp(18)); v.setBackground(DeskStyle.card(this)); return v; }
    private GradientDrawable shape(int fill, int radius) { return Ui.background(fill, radius, this); }
    private GradientDrawable outline(int fill, int line, int radius) { return Ui.outlined(fill, line, radius, this); }
    private LinearLayout.LayoutParams lp(int w, int h, int top, int bottom) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h)); p.setMargins(0, dp(top), 0, dp(bottom)); return p; }
    private TextView text(String value, int size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextColor(color); t.setTextSize(size); t.setLineSpacing(dp(2), 1.08f); return t; }
    private TextView label(String value, int size, int color) { TextView t = text(value, size, color); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return t; }
    private TextView selectable(String value, int size, int color) { TextView t = text(value, size, color); t.setTextIsSelectable(true); return t; }
    private void decorate(TextView view, String icon, int color, boolean chevron) {
        Drawable leading = DeskStyle.icon(this, icon, color); leading.setBounds(0, 0, dp(18), dp(18));
        Drawable trailing = chevron ? DeskStyle.icon(this, "chevron", MUTED) : null;
        if (trailing != null) trailing.setBounds(0, 0, dp(14), dp(14));
        view.setCompoundDrawablesRelative(leading, null, trailing, null); view.setCompoundDrawablePadding(dp(6));
    }
    private TextView controlChip(String title, String icon, View.OnClickListener action) {
        TextView chip = label(title, 12, INK); chip.setMaxLines(2); chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setGravity(Gravity.CENTER_VERTICAL); chip.setMinHeight(dp(48)); chip.setPadding(dp(8), dp(4), dp(8), dp(4));
        chip.setBackground(Ui.tappable(this, shape(Color.TRANSPARENT, 12), true)); decorate(chip, icon, ACCENT, true);
        chip.setFocusable(true); chip.setOnClickListener(action); return chip;
    }
    private Button actionButton(String title, String icon, boolean primary, View.OnClickListener action) {
        Button button = button(title, primary, action); decorate(button, icon, primary ? DeskStyle.PRIMARY_TEXT : ACCENT, false);
        button.setContentDescription(title); return button;
    }
    private Button button(String title, boolean primary, View.OnClickListener action) {
        Button b = new Button(this); b.setText(title); b.setTextSize(13); b.setAllCaps(false); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48));
        b.setTextColor(primary ? DeskStyle.PRIMARY_TEXT : INK); b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(primary ? DeskStyle.primary(this) : Ui.tappable(this, DeskStyle.field(this), true)); b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setLayoutParams(lp(-1, -2, 4, 4)); b.setOnClickListener(action); b.setStateListAnimator(null); return b;
    }
    private EditText input(String hint, boolean single) { EditText e = new EditText(this); e.setHint(hint); e.setHintTextColor(MUTED); e.setTextColor(INK); e.setTextSize(15); e.setSingleLine(single); e.setPadding(dp(12), dp(12), dp(12), dp(12)); e.setBackground(DeskStyle.field(this)); return e; }
    private Button iconButton(String description,String name,boolean primary,View.OnClickListener action) {
        Button button=button("",primary,action);button.setMinWidth(0);button.setMinimumWidth(0);
        button.setMinHeight(dp(48));button.setMinimumHeight(dp(48));button.setPadding(dp(14),dp(10),dp(14),dp(10));
        if(!primary)button.setBackground(DeskStyle.plain(this));
        decorateComposer(button,name,primary?DeskStyle.PRIMARY_TEXT:INK);button.setContentDescription(description);button.setTooltipText(description);
        return button;
    }
    private Button composerButton(String title,String icon,boolean primary,View.OnClickListener action) {
        Button button=button(title,primary,action);button.setTextSize(10);button.setMinWidth(0);button.setMinimumWidth(0);button.setPadding(dp(2),dp(4),dp(2),dp(4));
        decorateComposer(button,icon,primary?DeskStyle.PRIMARY_TEXT:INK);button.setContentDescription(title);return button;
    }
    private void decorateComposer(TextView view,String name,int color) {
        Drawable icon=DeskStyle.icon(this,name,color);icon.setBounds(0,0,dp(20),dp(20));view.setCompoundDrawables(icon,null,null,null);view.setCompoundDrawablePadding(0);
    }
    private LinearLayout padded(View view) { LinearLayout c = column(); c.setPadding(dp(20), dp(8), dp(20), dp(8)); c.addView(view); return c; }
}
