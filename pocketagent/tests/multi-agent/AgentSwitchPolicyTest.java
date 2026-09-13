package com.pocketagent.mobile;

import org.json.JSONObject;

public final class AgentSwitchPolicyTest {
    private static int checks;
    private static void check(boolean condition, String name) { checks++; if (!condition) throw new AssertionError(name); }
    private static String blocked(boolean[] f) {
        return AgentSwitchPolicy.blocked(f[0],f[1],f[2],f[3],f[4],f[5],f[6],f[7],f[8],f[9],f[10],f[11],f[12]);
    }
    private static void rejects(Runnable task, String name) {
        boolean rejected = false; try { task.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        check(rejected, name);
    }
    public static void main(String[] args) throws Exception {
        check(blocked(new boolean[13]).isEmpty(), "idle permits switch");
        String[] states = {"task","install","connect","auth","session open","recovery","approval","voice","model change","integration","session action","control","project tools"};
        for (int i = 0; i < states.length; i++) { boolean[] f = new boolean[13]; f[i] = true; check(!blocked(f).isEmpty(), states[i] + " blocks switch"); }
        AgentSwitchPolicy.requireOrigin("codex", "shared", "account1", "codex", "shared", "account1"); checks++;
        AgentSwitchPolicy.requireOrigin("claude", "shared", "", "claude", "shared", null); checks++;
        rejects(() -> AgentSwitchPolicy.requireOrigin("codex", "shared", "a", "claude", "shared", "a"), "old harness rejected");
        rejects(() -> AgentSwitchPolicy.requireOrigin("codex", "shared", "a", "codex", "other", "a"), "old project rejected");
        rejects(() -> AgentSwitchPolicy.requireOrigin("codex", "shared", "a2", "codex", "shared", "a1"), "old account rejected");
        rejects(() -> AgentSwitchPolicy.requireOrigin("codex", "shared", "a", null, "shared", null), "missing origin rejected");
        check(!AgentWorkspaceState.connectionKey("codex", "shared").equals(AgentWorkspaceState.connectionKey("claude", "shared")), "harness connection isolation");
        check(!AgentWorkspaceState.settingsKey("codex", "shared").equals(AgentWorkspaceState.settingsKey("codex", "other")), "project preference isolation");
        check(!AgentWorkspaceState.connectionKey("codex", "shared").equals(AgentWorkspaceState.settingsKey("codex", "shared")), "preference type isolation");
        rejects(() -> AgentWorkspaceState.scope("fake", "shared"), "invalid engine rejected");
        rejects(() -> AgentWorkspaceState.scope("codex", "../private"), "path escape rejected");
        JSONObject codex = AgentReconnect.remember("codex", "shared", true), claude = AgentReconnect.remember("claude", "shared", false);
        check(AgentReconnect.eligible(codex,"codex","shared"), "same scope reconnects");
        check(!AgentReconnect.eligible(codex,"claude","shared"), "access cannot transfer provider");
        check(!AgentReconnect.eligible(codex,"codex","other"), "access cannot transfer project");
        check(!claude.optBoolean("fullAccess"), "target retains restricted access");
        JSONObject store = new JSONObject().put(AgentWorkspaceState.connectionKey("codex","shared"), codex).put(AgentWorkspaceState.connectionKey("claude","shared"), claude);
        store.put(AgentWorkspaceState.connectionKey("codex","shared"), new JSONObject());
        check(AgentReconnect.eligible(store.getJSONObject(AgentWorkspaceState.connectionKey("claude","shared")),"claude","shared"), "disconnect one preserves other provider");
        check(!AgentReconnect.eligible(store.getJSONObject(AgentWorkspaceState.connectionKey("codex","shared")),"codex","shared"), "disconnected scope cannot reconnect");
        JSONObject settings = AgentWorkspaceState.settings("catalog-model", "plan");
        check("catalog-model".equals(settings.optString("model")) && "plan".equals(settings.optString("mode")), "model and plan round trip");
        check("ask".equals(AgentWorkspaceState.settings("", "bypass").optString("mode")), "invalid mode cannot restore permissions");
        check(AgentWorkspaceState.settings("model\nunsafe", "auto").optString("model").isEmpty(), "control characters rejected");
        check(AgentWorkspaceState.settings((JSONObject)null).optString("model").isEmpty(), "first use no model invented");
        check(!settings.has("account") && !settings.has("token") && !settings.has("fullAccess"), "settings never carry credentials or access");
        System.out.println("PASS " + checks + " multi-agent switch and scope checks");
    }
}
