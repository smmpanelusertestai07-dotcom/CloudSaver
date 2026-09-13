package com.pocketagent.mobile;

import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** A warning for measured account limits, never for unknown quota or conversation token counts. */
final class UsageNotice {
    final boolean visible, reached, stale;
    final String title, detail;
    final double remainingPercent;
    final long resetsAt;
    private UsageNotice(boolean visible, boolean reached, boolean stale, String title, String detail, double remaining, long reset) {
        this.visible=visible;this.reached=reached;this.stale=stale;this.title=title;this.detail=detail;remainingPercent=remaining;resetsAt=reset;
    }
    static UsageNotice read(UsageDisplay usage) {
        JSONObject tightest=null; double remaining=Double.NaN;
        for(int i=0;i<usage.windows.length();i++) {
            JSONObject window=usage.windows.optJSONObject(i); if(window==null || !(window.opt("remainingPercent") instanceof Number))continue;
            double value=((Number)window.opt("remainingPercent")).doubleValue();
            if(Double.isNaN(value)||Double.isInfinite(value)||value<0||value>100)continue;
            if(tightest==null||value<remaining) { tightest=window;remaining=value; }
        }
        boolean reached=usage.blocked || (!Double.isNaN(remaining) && remaining<=0);
        boolean visible=reached || (!Double.isNaN(remaining) && remaining<=10);
        if(!visible)return new UsageNotice(false,false,usage.stale,"","",remaining,0);
        String title=reached?"Usage limit reached":"Usage running low";
        String detail=Double.isNaN(remaining)||(usage.blocked&&remaining>0)?"Your account has a usage restriction.":
            tightest.optString("label", "Usage")+" · "+String.format(Locale.ROOT,remaining==Math.floor(remaining)?"%.0f%% left":"%.1f%% left",remaining);
        long reset=usage.blocked&&remaining>0?0:AgentActivity.secondsToMillis(tightest,"resetsAt");
        if(reset>0)detail+=" · Resets "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(reset));
        if(usage.stale)detail="Last reported: "+detail;
        return new UsageNotice(true,reached,usage.stale,title,detail,remaining,reset);
    }
}
