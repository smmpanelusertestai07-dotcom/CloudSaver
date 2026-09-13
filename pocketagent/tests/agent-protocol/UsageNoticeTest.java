package com.pocketagent.mobile;
import org.json.JSONObject;

public final class UsageNoticeTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        check(!read("{}").visible,"unknown quota not a warning");
        check(!read("{\"primary\":{\"usedPercent\":89.9}}").visible,"more than ten percent left not warning");
        UsageNotice low=read("{\"primary\":{\"usedPercent\":90,\"windowDurationMins\":300,\"resetsAt\":2000000000}}");
        check(low.visible&&!low.reached&&low.remainingPercent==10,"ten percent threshold measured");
        check(low.resetsAt==2000000000000L&&low.detail.contains("Resets"),"official reset time retained");
        UsageNotice reached=read("{\"primary\":{\"usedPercent\":100,\"resetsAt\":1}}");
        check(reached.visible&&reached.reached,"past reset never invents restored usage");
        UsageNotice two=read("{\"primary\":{\"usedPercent\":0},\"secondary\":{\"usedPercent\":99,\"windowDurationMins\":10080}}");
        check(two.remainingPercent==1&&two.detail.contains("Weekly"),"tightest measured window shown");
        JSONObject state=state("{}");state.getJSONObject("rateLimits").put("ordinaryUsageAllowed",false);
        UsageNotice restricted=UsageNotice.read(UsageDisplay.read(state,"codex"));
        check(restricted.reached&&restricted.visible&&Double.isNaN(restricted.remainingPercent),"explicit restriction requires no invented quota");
        check(!UsageNotice.read(UsageDisplay.read(state,"claude")).visible,"other provider never receives Codex quota");
        state=state("{\"primary\":{\"usedPercent\":97}}");state.put("refresh",new JSONObject().put("usage",new JSONObject().put("stale",true)));
        UsageNotice stale=UsageNotice.read(UsageDisplay.read(state,"codex"));
        check(stale.stale&&stale.detail.startsWith("Last reported:"),"stale warning labelled honestly");
        check(!read("{\"primary\":{\"usedPercent\":\"100\"}}").visible,"invalid quota string not parsed as zero remaining");
        System.out.println("PASS UsageNoticeTest ("+assertions+" assertions)");
    }
    private static JSONObject state(String bucket)throws Exception{return new JSONObject().put("provider","codex").put("connected",true).put("accountConnected",true).put("usageUpdatedAt",System.currentTimeMillis()).put("rateLimits",new JSONObject().put("rateLimits",new JSONObject(bucket)));}
    private static UsageNotice read(String bucket)throws Exception{return UsageNotice.read(UsageDisplay.read(state(bucket),"codex"));}
    private static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
}
