package com.pocketagent.mobile;

import org.json.JSONObject;
import org.json.JSONArray;

public final class AgentActivityTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        summaries(); tools(); malformed(); historyTimes(); restartState();
        System.out.println("PASS AgentActivityTest (" + assertions + " assertions)");
    }
    private static void summaries() throws Exception {
        JSONObject source = new JSONObject("{\"type\":\"reasoning\",\"id\":\"r1\",\"summary\":[\"Checking the project.\"],\"content\":[\"RAW_PRIVATE_CONTENT\"],\"encrypted_content\":\"ENCRYPTED_SECRET\"}");
        JSONObject activity = AgentActivity.codex(source,false);
        check(activity.optString("summary").equals("Checking the project."), "public summary renders");
        check(!activity.toString().contains("PRIVATE")&&!activity.toString().contains("SECRET"), "raw and encrypted reasoning never retained");
        activity=AgentActivity.summaryDelta(null,new JSONObject("{\"itemId\":\"r1\",\"summaryIndex\":0,\"delta\":\"Checking \"}"));
        activity=AgentActivity.summaryDelta(activity,new JSONObject("{\"itemId\":\"r1\",\"summaryIndex\":0,\"delta\":\"files.\"}"));
        activity=AgentActivity.summaryDelta(activity,new JSONObject("{\"itemId\":\"r1\",\"summaryIndex\":1,\"delta\":\"Reading tests.\"}"));
        check(activity.optString("summary").equals("Checking files.\n\nReading tests."), "indexed public summary deltas join correctly");
        String before=activity.toString();
        for(String index:new String[]{"-1","32","1000000000000","0.5","\"0\""}) {
            JSONObject result=AgentActivity.summaryDelta(activity,new JSONObject("{\"summaryIndex\":"+index+",\"delta\":\"BAD\"}"));
            check(result.toString().equals(before), "invalid summary index rejected: "+index);
        }
        StringBuilder large=new StringBuilder();for(int i=0;i<20000;i++)large.append('x');
        for(int i=0;i<40;i++)activity=AgentActivity.summaryDelta(activity,new JSONObject().put("summaryIndex",i%32).put("delta",large.toString()));
        check(activity.optString("summary").length()<=AgentActivity.SUMMARY_LIMIT, "streaming summary has a fixed bound");
        check(activity.toString().length()<18000, "parts plus public display stay bounded");
        check(source.optJSONArray("content").getString(0).equals("RAW_PRIVATE_CONTENT"), "mapper does not mutate provider item");
    }
    private static void tools() throws Exception {
        JSONObject search=AgentActivity.codex(new JSONObject("{\"type\":\"webSearch\",\"id\":\"w\",\"query\":\"Android speech\",\"action\":{\"type\":\"search\"}}"),false);
        check(search.optString("kind").equals("search")&&search.optString("status").equals("running"), "web search start has live status");
        check(search.optString("summary").equals("Android speech"),"actual search query available");
        JSONObject opened=AgentActivity.codex(new JSONObject("{\"type\":\"webSearch\",\"action\":{\"type\":\"openPage\",\"url\":\"https://example.com\"}}"),true);
        check(opened.optString("title").equals("Reading a web page")&&opened.optString("status").equals("completed"),"official open page action mapped");
        JSONObject command=AgentActivity.codex(new JSONObject("{\"type\":\"commandExecution\",\"command\":\"./gradlew test\",\"status\":\"failed\",\"aggregatedOutput\":\"One test failed\",\"exitCode\":1}"),true);
        check(command.optString("status").equals("failed"),"failure not overwritten by completion notification");
        check(command.optString("details").contains("One test failed")&&command.optString("summary").contains("gradlew"),"command and output inspectable");
        JSONObject mcp=AgentActivity.codex(new JSONObject("{\"type\":\"mcpToolCall\",\"tool\":\"github.search\",\"arguments\":{\"query\":\"repo\",\"api_key\":\"KEY_SECRET\"},\"result\":{\"content\":[{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"ENCODED_BYTES\"},{\"type\":\"thinking\",\"thinking\":\"PRIVATE_THOUGHT\"}]}}"),true);
        check(mcp.optString("title").equals("Using github.search"),"actual tool name retained");
        check(!mcp.toString().contains("KEY_SECRET")&&!mcp.toString().contains("ENCODED_BYTES")&&!mcp.toString().contains("PRIVATE_THOUGHT"),"details omit secrets, encoded blobs and hidden reasoning");
        check(mcp.optString("details").contains("repo")&&mcp.optString("details").contains("image/png"),"safe original tool metadata retained");
        check(AgentActivity.codex(new JSONObject("{\"type\":\"userMessage\"}"),true)==null,"user text not turned into activity");
        JSONObject acp=AgentActivity.acp(new JSONObject("{\"kind\":\"search\",\"title\":\"Search documentation\",\"status\":\"pending\"}"),null);
        acp=AgentActivity.acp(new JSONObject("{\"status\":\"completed\"}"),acp);
        check(acp.optString("title").equals("Search documentation")&&acp.optString("status").equals("completed"),"partial ACP status update preserves actual activity title");
    }
    private static void malformed() throws Exception {
        JSONObject deep=new JSONObject();JSONObject child=deep;
        for(int i=0;i<100;i++){JSONObject next=new JSONObject();child.put("nested",next);child=next;}
        JSONObject source=new JSONObject().put("type","mcpToolCall").put("arguments",deep);
        check(AgentActivity.codex(source,true).toString().length()<2000,"deep arbitrary tool metadata bounded");
        StringBuilder large=new StringBuilder();for(int i=0;i<50000;i++)large.append('"');
        source.put("arguments",large.toString());
        check(AgentActivity.codex(source,true).optString("details").length()<=AgentActivity.DETAILS_LIMIT,"escaped output remains display bounded");
        check(AgentActivity.secondsToMillis(new JSONObject().put("time",Long.MAX_VALUE),"time")==0,"overflowing timestamp rejected");
        check(AgentActivity.secondsToMillis(new JSONObject().put("time","123"),"time")==0,"string timestamp not invented");
    }
    private static void historyTimes() throws Exception {
        JSONObject turn=new JSONObject("{\"id\":\"turn\",\"status\":\"completed\",\"startedAt\":1800000000,\"completedAt\":1800000010,\"items\":[{\"id\":\"r\",\"type\":\"reasoning\",\"summary\":[\"Checking tests\"],\"content\":[\"PRIVATE\"]},{\"id\":\"w\",\"type\":\"webSearch\",\"query\":\"Android\"},{\"id\":\"a\",\"type\":\"agentMessage\",\"text\":\"Done\"}]}");
        JSONArray result=CodexControls.threadMessages(new JSONObject().put("turns",new JSONArray().put(turn)));
        check(result.length()==3&&!result.toString().contains("PRIVATE"),"resumed history includes public activities only");
        check(result.getJSONObject(2).getLong("completedAt")==1800000010000L,"provider seconds converted to display milliseconds");
        turn.remove("startedAt");turn.remove("completedAt");
        result=CodexControls.threadMessages(new JSONObject().put("turns",new JSONArray().put(turn)));
        check(!result.getJSONObject(2).has("time")&&!result.getJSONObject(2).has("completedAt"),"missing history times remain missing");
    }
    private static void restartState() throws Exception {
        JSONObject live=new JSONObject("{\"id\":\"reply\",\"role\":\"assistant\",\"text\":\"Partial answer\",\"streaming\":true,\"time\":1800000000000}");
        // The service writes and reloads whole message JSON objects; mirror that process boundary.
        JSONObject restored=new JSONObject(new JSONObject().put("messages",new JSONArray().put(live)).toString()).getJSONArray("messages").getJSONObject(0);
        AgentActivity.restoreCachedMessage(restored);
        check(!restored.getBoolean("streaming"),"killed response no longer appears live after disk restore");
        check(!restored.has("completedAt")&&restored.getLong("time")==1800000000000L,"restart does not fabricate completion time or replace original time");
        check(restored.getString("text").equals("Partial answer")&&live.getBoolean("streaming"),"saved text retained and original live fixture untouched");
        JSONObject tool=new JSONObject("{\"streaming\":true,\"activity\":{\"kind\":\"reasoning\",\"status\":\"running\",\"summary\":\"Checking files\"}}");
        AgentActivity.restoreCachedMessage(tool);
        check(tool.getJSONObject("activity").getString("status").equals("cancelled"),"interrupted activity is stopped on restore");
        check(tool.getJSONObject("activity").getString("summary").equals("Checking files"),"public summary survives restore normalization");
        JSONObject complete=new JSONObject("{\"streaming\":false,\"completedAt\":1800000005000,\"activity\":{\"status\":\"completed\"}}");
        String before=complete.toString();AgentActivity.restoreCachedMessage(complete);
        check(complete.toString().equals(before),"completed message and actual timestamp unchanged");
        JSONObject legacy=new JSONObject("{\"role\":\"assistant\",\"text\":\"Old reply\",\"time\":1800000000000}");
        before=legacy.toString();AgentActivity.restoreCachedMessage(legacy);
        check(legacy.toString().equals(before),"legacy history without stream metadata unchanged");
    }
    private static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
}
