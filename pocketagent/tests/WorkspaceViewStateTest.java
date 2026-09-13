package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import static com.pocketagent.mobile.AgentProtocol.*;

public final class WorkspaceViewStateTest {
    private static int assertions;
    public static void main(String[] arguments) throws Exception {
        JSONObject snapshot = object("project", "project-a", "provider", "codex", "connected", true,
                "accountConnected", true, "threadId", "thread-a", "controls", object("background", object("threadId", "thread-a", "known", true, "rows", array(object("processId", "pid-a")))));
        check(WorkspaceViewState.canReadTasks(snapshot, "project-a"), "matching connected Codex can read real tasks");
        check(!WorkspaceViewState.canReadTasks(snapshot, "project-b"), "other project cannot request task status");
        check(list(WorkspaceViewState.tasks(snapshot, "project-b"), "rows").length() == 0, "other project's background rows stay hidden");
        check(list(WorkspaceViewState.tasks(snapshot, "project-a"), "rows").length() == 1, "matching thread exposes actual engine row");
        snapshot.put("threadId", "thread-b");
        check(list(WorkspaceViewState.tasks(snapshot, "project-a"), "rows").length() == 0, "stale thread's rows cannot appear after reconnect");
        snapshot.put("threadId", "thread-a").put("connected", false);
        check(!WorkspaceViewState.canReadTasks(snapshot, "project-a"), "disconnected state does not imply live task access");
        snapshot.put("connected", true).put("accountConnected", false);
        check(!WorkspaceViewState.canReadTasks(snapshot, "project-a"), "unknown account metadata is not connected");
        snapshot.put("accountConnected", true).put("sessionOpening", true);
        check(!WorkspaceViewState.canReadTasks(snapshot, "project-a"), "opening thread cannot request old tasks");
        JSONArray messages = new JSONArray();
        String oversized = new String(new char[20000]).replace('\0', 'x');
        for (int i = 0; i < 30; i++) messages.put(object("role", "tool", "id", "output-" + i, "text", oversized));
        snapshot.put("messages", messages);
        JSONArray output = WorkspaceViewState.output(snapshot, "project-a");
        check(output.length() == 14, "live screen bounds message view count");
        check(output.getJSONObject(0).optString("id").equals("output-16"), "recent actual output is retained in order");
        check(output.getJSONObject(0).optString("text").length() <= 6030, "individual output is bounded");
        check(WorkspaceViewState.output(snapshot, "project-b").length() == 0, "another project's transcript is never displayed");
        snapshot.put("messages", array(object("role", "unrecognized-private-event", "text", "secret"), object("role", "assistant", "text", "Visible result")));
        check(WorkspaceViewState.output(snapshot, "project-a").length() == 1, "only known visible message roles are presented");
        JSONObject preview = object("project", "project-a", "running", true, "port", 4312);
        check(WorkspaceViewState.previewMatches(preview, "project-a"), "matching actual local preview is openable");
        check(!WorkspaceViewState.previewMatches(preview, "project-b"), "preview cannot point to another project");
        preview.put("port", 80); check(!WorkspaceViewState.previewMatches(preview, "project-a"), "out-of-contract port rejected");
        preview.put("port", 4312).put("running", false); check(!WorkspaceViewState.previewMatches(preview, "project-a"), "stopped preview is not represented as interactive");
        System.out.println("PASS WorkspaceViewStateTest (" + assertions + " assertions)");
    }
    private static void check(boolean condition, String detail) { assertions++; if (!condition) throw new AssertionError(detail); }
}
