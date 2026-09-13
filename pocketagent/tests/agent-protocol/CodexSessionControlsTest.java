package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Official history/task controller fixtures; transport is local and no account is accessed. */
public final class CodexSessionControlsTest {
    private static int checks;
    private static final String CWD = "/home/coder/Projects/demo", T1 = "019a-chat-one", T2 = "019a-chat-two";
    private static void check(boolean pass, String message) { checks++; if (!pass) throw new AssertionError(message); }
    private static JSONObject thread(String id, String cwd) { return object("id", id, "cwd", cwd, "modelProvider", "openai", "name", "Build demo", "preview", "Create the app", "updatedAt", 1234, "createdAt", 1200); }
    private static final class Call {
        String method; JSONObject params; CodexSessionControls.Reply sessionReply; CodexBackgroundTasks.Reply taskReply;
        void reply(JSONObject result) { if (sessionReply != null) sessionReply.receive(result, null); else taskReply.receive(result, null); }
        void fail() { if (sessionReply != null) sessionReply.receive(new JSONObject(), object("message", "Permission denied")); else taskReply.receive(new JSONObject(), object("message", "Permission denied")); }
    }
    private static final class Host implements CodexSessionControls.Host, CodexBackgroundTasks.Host {
        ArrayDeque<Call> calls = new ArrayDeque<>(); String resumed = "", closed = "";
        @Override public void request(String method, JSONObject params, CodexSessionControls.Reply reply) { Call call = new Call(); call.method = method; call.params = params; call.sessionReply = reply; calls.add(call); }
        @Override public void request(String method, JSONObject params, CodexBackgroundTasks.Reply reply) { Call call = new Call(); call.method = method; call.params = params; call.taskReply = reply; calls.add(call); }
        @Override public void resume(String id) { resumed = id; }
        @Override public void changed() { }
        @Override public void closedThread(String id, String operation) { closed = id + ":" + operation; }
        Call next(String expected) { Call call = calls.remove(); check(expected.equals(call.method), "Expected official RPC " + expected + " got " + call.method); return call; }
    }
    private static void load(CodexSessionControls controls, Host host, boolean archived) {
        controls.dispatch("refresh", object("archived", archived));
        host.next("thread/list").reply(object("data", array(thread(T1, CWD), thread(T2, CWD)), "nextCursor", null));
    }

    public static void main(String[] args) throws Exception {
        Host host = new Host(); CodexSessionControls controls = new CodexSessionControls(host); controls.reset(CWD, "account-a", T1);
        controls.dispatch("refresh", object("archived", false, "search", "demo")); Call page = host.next("thread/list");
        check(CWD.equals(page.params.optString("cwd")), "History query scoped to exact project cwd");
        check("demo".equals(page.params.optString("searchTerm")), "Native search uses supported searchTerm");
        check(!page.params.has("accountId") && !page.params.has("originators"), "No fabricated account or unsupported hosted filter");
        check("openai".equals(page.params.getJSONArray("modelProviders").getString(0)), "Only official OpenAI provider history requested");
        page.reply(object("data", array(thread(T1, CWD), thread(T2, CWD), thread("other-project", CWD + "-other"), object("id", "other-provider", "cwd", CWD, "modelProvider", "custom")), "nextCursor", null));
        check(list(controls.snapshot(), "rows").length() == 2, "Returned out-of-project/provider rows filtered");
        controls.dispatch("resume", object("threadId", "cached-chat")); host.next("thread/read").reply(object("thread", thread("cached-chat", CWD + "/other"))); check(host.resumed.isEmpty(), "Cached IDs still require official workspace verification");
        controls.dispatch("archive", object("threadId", T1, "confirmed", true)); host.next("thread/read").reply(object("thread", thread(T1, CWD))); host.next("thread/archive").fail();
        check(host.closed.isEmpty(), "Failed active archive never detaches the current thread");
        controls.dispatch("delete", object("threadId", T1)); check(host.calls.isEmpty(), "Active delete still requires explicit confirmation");
        controls.dispatch("archive", object("threadId", T2)); check(host.calls.isEmpty(), "Archive requires explicit confirmation");
        controls.dispatch("rename", object("threadId", T2, "name", "New title")); Call metadata = host.next("thread/read");
        check(!metadata.params.optBoolean("includeTurns"), "Mutation validation does not read full private transcript");
        metadata.reply(object("thread", thread(T2, CWD + "/moved"))); check(host.calls.isEmpty(), "Changed cwd rejected before mutation");
        controls.dispatch("rename", object("threadId", T2, "name", "New title")); host.next("thread/read").reply(object("thread", thread(T2, CWD)));
        Call rename = host.next("thread/name/set"); check("New title".equals(rename.params.optString("name")) && T2.equals(rename.params.optString("threadId")), "Pinned rename payload uses name and threadId");
        rename.fail(); check(!controls.snapshot().optString("error").isEmpty() && host.calls.isEmpty(), "Failed rename never reported as success");
        controls.dispatch("archive", object("threadId", T2, "confirmed", true)); host.next("thread/read").reply(object("thread", thread(T2, CWD)));
        host.next("thread/archive").reply(new JSONObject()); host.next("thread/list").reply(object("data", array(thread(T1, CWD)), "nextCursor", null));
        check(list(controls.snapshot(), "rows").length() == 1, "Archive success refreshes engine-owned list");
        load(controls, host, true);
        controls.dispatch("unarchive", object("threadId", T2, "confirmed", true)); host.next("thread/read").reply(object("thread", thread(T2, CWD)));
        host.next("thread/unarchive").reply(new JSONObject()); host.next("thread/list").reply(object("data", new JSONArray(), "nextCursor", null));
        load(controls, host, false);
        controls.dispatch("delete", object("threadId", T2, "confirmed", true)); host.next("thread/read").reply(object("thread", thread(T2, CWD)));
        host.next("thread/delete").reply(new JSONObject()); host.next("thread/list").reply(object("data", array(thread(T1, CWD)), "nextCursor", null));
        load(controls, host, false);
        controls.dispatch("resume", object("threadId", T2)); host.next("thread/read").reply(object("thread", thread(T2, CWD)));
        check(T2.equals(host.resumed) && controls.changing(), "Resume delegates to service and remains pending until actual adoption");
        controls.resumed(T2); check(T2.equals(controls.snapshot().optString("activeThread")) && !controls.changing(), "Only service adoption completes resume");
        controls.dispatch("refresh", new JSONObject()); Call stale = host.next("thread/list"); controls.reset(CWD, "account-b", "");
        stale.reply(object("data", array(thread(T1, CWD)), "nextCursor", null)); check(list(controls.snapshot(), "rows").length() == 0, "Account change drops stale account list callbacks");
        check(controls.snapshot().optString("scopeNote").contains("does not report"), "UI never invents per-chat account ownership");
        load(controls, host, false);
        JSONObject history = thread(T1, CWD); history.put("turns", array(object("items", array(
            object("type", "userMessage", "content", array(object("type", "text", "text", "Build a site"))),
            object("type", "agentMessage", "text", "The site is ready"),
            object("type", "commandExecution", "aggregatedOutput", "DO_NOT_SHARE_TOOL_SECRET"),
            object("type", "reasoning", "text", "DO_NOT_SHARE_REASONING")))));
        controls.dispatch("export", object("threadId", T1)); Call export = host.next("thread/read"); check(export.params.optBoolean("includeTurns"), "Export requests actual stored turns"); export.reply(object("thread", history));
        String shared = controls.snapshot().optString("shareText");
        check(shared.contains("Build a site") && shared.contains("The site is ready"), "Share contains actual conversation text");
        check(!shared.contains("DO_NOT_SHARE"), "Tool arguments/results and internal reasoning excluded");
        check(!controls.snapshot().has("shareUrl"), "No fabricated public conversation URL");
        controls.dispatch("dismiss_share", new JSONObject()); check(controls.snapshot().optString("shareText").isEmpty(), "Dismiss removes transient sharing data");
        StringBuilder big = new StringBuilder(); for (int i = 0; i < 40000; i++) big.append('x');
        history.put("turns", array(object("items", array(object("type", "agentMessage", "text", big.toString())))));
        JSONObject excerpt = CodexSessionControls.transcript(history); check(excerpt.optBoolean("truncated") && excerpt.optString("text").length() <= CodexSessionControls.MAX_SHARE_CHARS, "Large conversation gets bounded explicitly labeled excerpt");
        controls.dispatch("refresh", new JSONObject()); JSONArray many = new JSONArray(); for (int i = 0; i < 300; i++) { JSONObject item = thread("thread-" + i, CWD); item.put("name", big.substring(0, 160)); item.put("preview", big.substring(0, 500)); many.put(item); }
        host.next("thread/list").reply(object("data", many, "nextCursor", "next")); check(list(controls.snapshot(), "rows").toString().length() <= CodexSessionControls.MAX_CATALOG_CHARS && controls.snapshot().optBoolean("hasMore") && !controls.snapshot().optBoolean("truncated"), "Bounded transport window retains next-page access past 60 rows");
        controls.dispatch("more", new JSONObject()); host.next("thread/list").reply(object("data", array(thread("after-cap", CWD)), "nextCursor", null));
        check(list(controls.snapshot(), "rows").toString().contains("after-cap"), "Older engine pages remain reachable");
        int before = list(controls.snapshot(), "rows").length(); controls.dispatch("refresh", new JSONObject()); Call retry = host.next("thread/list");
        check(list(controls.snapshot(), "rows").length() == before, "Same-scope refresh keeps displayed history"); retry.fail();
        controls.closed(); check(list(controls.snapshot(), "rows").length() == before, "Disconnect preserves observed rows");
        controls.reset(CWD, "account-a", T1); load(controls, host, false);
        controls.dispatch("delete", object("threadId", T1, "confirmed", true)); host.next("thread/read").reply(object("thread", thread(T1, CWD)));
        host.next("thread/delete").reply(new JSONObject()); check((T1 + ":delete").equals(host.closed), "Only successful active delete closes the current thread");
        check(T1.equals(child(controls.snapshot(), "lastChange").optString("id")), "Confirmed change exposed for private cache reconciliation");
        host.next("thread/list").reply(object("data", array(thread(T2, CWD)), "nextCursor", null));

        Host taskHost = new Host(); CodexBackgroundTasks tasks = new CodexBackgroundTasks(taskHost); tasks.reset(T1);
        tasks.dispatch("tasks_terminate", object("threadId", T1, "processId", "123", "confirmed", true)); check(taskHost.calls.isEmpty(), "Task stop requires loaded engine provenance");
        tasks.dispatch("tasks_refresh", new JSONObject()); Call taskPage = taskHost.next("thread/backgroundTerminals/list"); check(T1.equals(taskPage.params.optString("threadId")), "Tasks scoped to active thread");
        JSONObject task = object("processId", "123", "itemId", "item-1", "command", "npm run dev", "cwd", CWD, "cpuPercent", 2.5, "rssKb", 20480);
        taskPage.reply(object("data", array(task), "nextCursor", null)); check(list(tasks.snapshot(), "rows").getJSONObject(0).optDouble("cpuPercent") == 2.5, "Task stats come from engine data");
        tasks.dispatch("tasks_terminate", object("threadId", T2, "processId", "123", "confirmed", true)); check(taskHost.calls.isEmpty(), "Old native task dialog cannot target a new conversation");
        tasks.dispatch("tasks_terminate", object("threadId", T1, "processId", "999", "confirmed", true)); check(taskHost.calls.isEmpty(), "Unknown process ID cannot be stopped");
        tasks.dispatch("tasks_terminate", object("threadId", T1, "processId", "123")); check(taskHost.calls.isEmpty(), "Task stop requires confirmation");
        tasks.dispatch("tasks_terminate", object("threadId", T1, "processId", "123", "confirmed", true)); Call terminate = taskHost.next("thread/backgroundTerminals/terminate"); check(T1.equals(terminate.params.optString("threadId")) && "123".equals(terminate.params.optString("processId")), "Termination uses official thread/process handle pair");
        terminate.reply(new JSONObject()); taskHost.next("thread/backgroundTerminals/list").reply(object("data", new JSONArray(), "nextCursor", null)); check(list(tasks.snapshot(), "rows").length() == 0, "Termination refreshes actual remaining tasks");
        tasks.dispatch("tasks_refresh", new JSONObject()); Call oldTasks = taskHost.next("thread/backgroundTerminals/list"); tasks.reset(T2); oldTasks.reply(object("data", array(task), "nextCursor", null)); check(list(tasks.snapshot(), "rows").length() == 0, "Old thread task results cannot populate new thread");
        tasks.dispatch("tasks_refresh", new JSONObject()); taskHost.next("thread/backgroundTerminals/list").reply(object("data", array(task), "nextCursor", "more"));
        tasks.dispatch("tasks_clean", object("threadId", T2, "confirmed", true)); check(taskHost.calls.isEmpty(), "Stop all denied while unseen task pages remain");
        tasks.dispatch("tasks_more", new JSONObject()); taskHost.next("thread/backgroundTerminals/list").reply(object("data", new JSONArray(), "nextCursor", null));
        tasks.dispatch("tasks_clean", object("threadId", T2, "confirmed", true)); Call clean = taskHost.next("thread/backgroundTerminals/clean"); check(T2.equals(clean.params.optString("threadId")), "Clean scoped to active conversation"); clean.reply(new JSONObject()); taskHost.next("thread/backgroundTerminals/list").reply(object("data", new JSONArray(), "nextCursor", null));
        System.out.println("CodexSessionControlsTest: " + checks + " assertions passed");
    }
}
