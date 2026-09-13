package com.pocketagent.mobile;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Pinned engine error reproduction; no account credentials or model turns are needed. */
public final class CodexRecoveryTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        JSONObject packet = new JSONObject(new String(Files.readAllBytes(Paths.get(args[0]).resolve(
                "tests/agent-protocol/schemas/codex-0.154.0/MissingRolloutResponse.json")), StandardCharsets.UTF_8));
        JSONObject missing = packet.getJSONObject("error");
        String saved = "01a09623-1392-7951-8a8e-f1c594714430";
        check(AgentProtocol.missingCodexRollout("thread/resume", saved, missing), "actual pinned missing-rollout error is recoverable");
        check(!AgentProtocol.missingCodexRollout("thread/start", saved, missing), "fresh start failure must never trigger a retry loop");
        check(!AgentProtocol.missingCodexRollout("thread/resume", "another-thread", missing), "another thread's error cannot clear this saved session");
        check(!AgentProtocol.missingCodexRollout("thread/resume", "", missing), "empty saved identity is not a recoverable resume");
        for (JSONObject error : new JSONObject[]{
                AgentProtocol.object("code", -32600, "message", "invalid thread id"),
                AgentProtocol.object("code", -32600, "message", "Authentication required"),
                AgentProtocol.object("code", -32600, "message", "Permission denied reading rollout"),
                AgentProtocol.object("code", -32098, "message", "The engine did not answer thread/resume in time."),
                AgentProtocol.object("code", -32603, "message", missing.getString("message")),
                AgentProtocol.object("code", "-32600", "message", missing.getString("message")),
                AgentProtocol.object("code", -32600.5, "message", missing.getString("message")),
                AgentProtocol.object("code", -32600, "message", missing.getString("message") + " authentication failed"),
                new JSONObject()}) {
            check(!AgentProtocol.missingCodexRollout("thread/resume", saved, error), "unrelated, malformed, auth and transient errors must remain visible");
        }
        AgentProtocol.CodexRecovery recovery = new AgentProtocol.CodexRecovery();
        check(!recovery.claim("thread/resume", saved, AgentProtocol.object("code", -32098, "message", "Timed out")), "transient failure does not consume recovery or erase context");
        check(recovery.claim("thread/resume", saved, missing), "first confirmed missing session gets one recovery");
        check(!recovery.claim("thread/resume", saved, missing), "duplicate missing-rollout callback cannot archive/start twice");
        check(!recovery.claim("thread/start", "", missing), "failed fallback start is not retried");
        recovery.reset();
        check(recovery.claim("thread/resume", saved, missing), "an explicit new connection resets the one-shot guard");

        for (boolean full : new boolean[]{false, true}) {
            JSONObject resume = AgentProtocol.codexSessionParams("/home/coder/Projects/test", full, saved, "model-choice");
            JSONObject fresh = AgentProtocol.codexSessionParams("/home/coder/Projects/test", full, "", "model-choice");
            check(resume.getString("threadId").equals(saved) && !fresh.has("threadId"), "recovery removes the stale ID only from the fresh request");
            check(fresh.getString("approvalPolicy").equals("untrusted"), "recovery never disables tool approvals");
            check(fresh.getString("sandbox").equals(full ? "danger-full-access" : "workspace-write"), "recovery retains original explicit workspace permission mode");
            check(fresh.getString("model").equals("model-choice"), "recovery retains the chosen model");
            check(!fresh.has("history") && !fresh.has("input") && !fresh.has("messages"), "old visible transcript is not fabricated as model context");
        }
        check(AgentProtocol.internalRolloutDiagnostic("ERROR state db missing rollout path for saved thread"), "known internal rollout diagnostic stays out of chat");
        check(!AgentProtocol.internalRolloutDiagnostic("Permission denied while writing project file"), "unrelated runtime failure remains inspectable");
        check(AgentProtocol.chatgptAccount(AgentProtocol.object("type", "chatgpt")), "account/read identity survives a chat-only error");
        check(AgentProtocol.chatgptAccount(AgentProtocol.object("authMode", "chatgpt")), "account/updated uses its actual pinned authMode field");
        check(!AgentProtocol.chatgptAccount(AgentProtocol.object("type", "chatgpt", "authMode", null)), "explicit signed-out update cannot inherit old login");
        check(!AgentProtocol.chatgptAccount(AgentProtocol.object("authMode", "apikey")), "API-key mode does not masquerade as eligible ChatGPT account");
        System.out.println("PASS CodexRecoveryTest (" + assertions + " assertions)");
    }
    private static void check(boolean okay, String why) { assertions++; if (!okay) throw new AssertionError(why); }
}
