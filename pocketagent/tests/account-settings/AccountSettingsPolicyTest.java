package com.pocketagent.mobile;

import java.net.URI;

public final class AccountSettingsPolicyTest {
    private static int checks;
    private static void check(boolean condition,String message) { checks++;if(!condition)throw new AssertionError(message); }
    public static void main(String[] args) {
        String key = AgentNotificationPolicy.eventKey("complete","codex","private-project","thread-secret","turn-secret");
        check(key.matches("[0-9a-f]{64}"),"Persist only fixed-length event hashes");
        check(!key.contains("private-project")&&!key.contains("thread-secret"),"No raw private identifiers in ledger");
        String state = AgentNotificationPolicy.remember("",key);
        check(state.equals(key),"First actual event is accepted");
        check(AgentNotificationPolicy.remember(state,key)==null,"Repeated notification cannot be delivered twice");
        check(AgentNotificationPolicy.remember(new String(state),key)==null,"Dedupe survives reloading stored state");
        for(String[] event:new String[][]{
                {"complete","codex","private-project","thread-secret","next-turn"},
                {"error","codex","private-project","thread-secret","turn-secret"},
                {"complete","claude","private-project","thread-secret","turn-secret"},
                {"complete","codex","another-project","thread-secret","turn-secret"},
                {"complete","codex","private-project","another-thread","turn-secret"}})
            check(!key.equals(AgentNotificationPolicy.eventKey(event[0],event[1],event[2],event[3],event[4])),"Independent events are not suppressed");
        check(AgentNotificationPolicy.eventKey("complete","codex","project","", "event").isEmpty(),"No guessed notification without session identity");
        check(AgentNotificationPolicy.eventKey("complete","codex","project","thread", "").isEmpty(),"No notification inferred from generic idle state");
        check(AgentNotificationPolicy.eventKey("snapshot","codex","project","thread", "event").isEmpty(),"Snapshot events cannot notify");
        check(AgentNotificationPolicy.eventKey("complete","unknown","project","thread", "event").isEmpty(),"Unknown provider rejected");
        check(AgentNotificationPolicy.eventKey("complete","codex","project","thread", new String(new char[513])).isEmpty(),"Oversized identity rejected");
        for(int i=0;i<300;i++)state=AgentNotificationPolicy.remember(state,AgentNotificationPolicy.eventKey("approval","codex","project","thread","request-"+i));
        check(state.split("\n").length==AgentNotificationPolicy.HISTORY_LIMIT,"Event history remains bounded");
        check(state.length()<=AgentNotificationPolicy.HISTORY_LIMIT*65,"Event history storage remains bounded");
        String last=AgentNotificationPolicy.eventKey("approval","codex","project","thread","request-299");
        check(AgentNotificationPolicy.remember(state,last)==null,"Newest event remains idempotent after pruning");
        check(AgentNotificationPolicy.remember(state,"not-a-hash")==null,"Raw untrusted ledger key rejected");
        for(String provider:AgentCatalog.IDS) {
            ProviderAccountLinks.Link[] links=ProviderAccountLinks.forProvider(provider);
            check(links.length>=4,"Provider exposes account/usage/connections/privacy destinations");
            java.util.HashSet<String> categories=new java.util.HashSet<>();
            for(ProviderAccountLinks.Link link:links) {
                URI uri=URI.create(link.url);
                check("https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getRawUserInfo()==null,"Official destination is HTTPS without embedded credentials");
                check(ProviderAccountLinks.allows(provider,link.url),"Compiled destination allowed");
                check(!ProviderAccountLinks.allows(provider,link.url+"?token=untrusted"),"Caller cannot append authorization data or redirects");
                check(categories.add(link.id),"Account settings rows are unambiguous");
            }
            check(categories.contains("account")&&categories.contains("usage")&&categories.contains("connections")&&categories.contains("privacy"),"All requested account categories exist");
        }
        check(!ProviderAccountLinks.allows("codex","https://chatgpt.com.evil.example/"),"Lookalike account host rejected");
        check(!ProviderAccountLinks.allows("claude","javascript:alert(1)"),"Active URL scheme rejected");
        check(ProviderAccountLinks.forProvider("unknown").length==0,"Unknown account provider has no fallback login");
        check(ProviderAccountLinks.note("antigravity").contains("unavailable"),"Account links do not fake an available Antigravity agent");
        System.out.println("PASS AccountSettingsPolicyTest ("+checks+" checks)");
    }
}
