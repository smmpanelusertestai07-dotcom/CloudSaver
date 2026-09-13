package com.pocketagent.mobile;

import org.json.JSONObject;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import static com.pocketagent.mobile.AgentProtocol.*;

public final class CodexVoiceTest {
    private static int count;
    private static final String OWNER = "12345678-1234-1234-1234-123456789abc";
    private static final String SDP = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=ice-ufrag:private-ice\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";
    private static final class Request { String method; JSONObject params; CodexVoice.Reply reply; Request(String m, JSONObject p, CodexVoice.Reply r) { method=m;params=p;reply=r; } }
    private static final class Host implements CodexVoice.Host {
        final ArrayList<Request> requests = new ArrayList<>(); final ArrayList<Runnable> timers = new ArrayList<>();
        boolean failStop;
        public void request(String m, JSONObject p, CodexVoice.Reply r) { if(failStop&&"thread/realtime/stop".equals(m))throw new IllegalStateException("pipe closed");requests.add(new Request(m,p,r)); }
        public void changed() { }
        public void signal(String o,String k,String v) { VoiceTransport.signal(o,k,v); }
        public void later(Runnable r,long delay) { timers.add(r); }
        Request last() { return requests.get(requests.size()-1); }
    }
    static void check(boolean v,String message) { if(!v)throw new AssertionError(message);count++; }
    static void rejects(Runnable r) { try {r.run();throw new AssertionError("Expected invalid input");}catch(IllegalArgumentException expected){count++;} }
    static JSONObject payload() { return object("threadId","thread-1","owner",OWNER,"sdp",SDP); }
    public static void main(String[] args) throws Exception {
        JSONObject start=CodexVoice.startParams("thread-1",SDP);
        check("webrtc".equals(child(start,"transport").optString("type")),"Only WebRTC subscription route");
        check("audio".equals(start.optString("outputModality")),"Actual audio output requested");
        check("v1".equals(start.optString("version")),"Pinned official WebRTC default");
        check(!start.has("model")&&!start.has("apiKey")&&!start.has("prompt"),"No fabricated model, prompt or API auth");
        rejects(()->CodexVoice.validSdp("v=0\r\nm=video 9\r\nm=application 9\r\n"));
        rejects(()->CodexVoice.validSdp("v=0\r\nm=audio 9\r\n"));
        rejects(()->CodexVoice.validSdp(SDP+new String(new char[65536])));
        rejects(()->CodexVoice.validSdp(SDP+"\0"));
        rejects(()->CodexVoice.startParams("../bad",SDP));
        rejects(()->VoiceTransport.attach("bad-owner"));
        Host host=new Host();CodexVoice voice=new CodexVoice(host);voice.reset("thread-1");
        VoiceTransport.attach(OWNER);voice.dispatch("start",payload());
        check(voice.active()&&voice.changing(),"Connection blocks competing sessions");
        check("thread/realtime/start".equals(host.last().method),"Official start RPC");
        check(!voice.snapshot().toString().contains("private-ice"),"SDP never in snapshot");
        CodexVoice.Reply oldStart=host.last().reply;
        voice.event("thread/realtime/sdp",object("threadId","other-thread","sdp",SDP));
        check(VoiceTransport.poll(OWNER)==null,"Foreign thread answer ignored");
        voice.event("thread/realtime/sdp",object("threadId","thread-1","sdp",SDP));
        check(SDP.equals(VoiceTransport.poll(OWNER).value),"Answer reaches private owner queue");
        oldStart.receive(new JSONObject(),null);
        check("connecting".equals(voice.snapshot().optString("phase")),"RPC ACK is not a connected microphone");
        voice.dispatch("transport_ready",payload());
        check("live".equals(voice.snapshot().optString("phase")),"Device transport confirms live audio");
        voice.event("thread/realtime/transcript/delta",object("threadId","thread-1","role","user","delta","Hello"));
        check("Hello".equals(voice.snapshot().optString("userCaption")),"User live transcript");
        voice.event("thread/realtime/transcript/done",object("threadId","thread-1","role","assistant","text","Hi"));
        check(list(voice.snapshot(),"transcript").length()==1,"Completed transcript retained bounded");
        voice.event("thread/realtime/transcript/done",object("threadId","thread-1","role","analysis","text","private reasoning"));
        check(!voice.snapshot().toString().contains("private reasoning"),"Private reasoning role excluded");
        for(int i=0;i<30;i++)voice.event("thread/realtime/transcript/done",object("threadId","thread-1","role","user","text",new String(new char[3000]).replace('\0','x')));
        check(list(voice.snapshot(),"transcript").length()<=12&&list(voice.snapshot(),"transcript").toString().length()<=16000,"Bounded captions");
        voice.event("thread/realtime/outputAudio/delta",object("threadId","thread-1","audio",object("data","RAW_AUDIO_SECRET")));
        check(!voice.snapshot().toString().contains("RAW_AUDIO_SECRET"),"Audio never in snapshot");
        voice.dispatch("stop",payload());check("thread/realtime/stop".equals(host.last().method),"Real engine stop");
        check("close".equals(VoiceTransport.poll(OWNER).kind),"Local media closed immediately before engine ACK");
        voice.event("thread/realtime/sdp",object("threadId","thread-1","sdp",SDP));
        check(VoiceTransport.poll(OWNER)==null,"Late answer while stopping ignored");
        host.last().reply.receive(new JSONObject(),null);check(!voice.active(),"Stop ACK completes");
        voice.reset("thread-1");VoiceTransport.attach(OWNER);voice.dispatch("start",payload());
        host.last().reply.receive(new JSONObject(),object("message","403 access denied Bearer SECRET_TOKEN "+SDP));
        check(voice.snapshot().optBoolean("blocked"),"Account rejection blocks repeated calls");
        check(!voice.snapshot().toString().contains("SECRET_TOKEN")&&!voice.snapshot().toString().contains("private-ice"),"Engine error private payload not exposed");
        host.last().reply.receive(new JSONObject(),null);int size=host.requests.size();voice.dispatch("start",payload());
        check(host.requests.size()==size,"No automatic or repeated denied start");
        voice.reset("thread-1");VoiceTransport.attach(OWNER);voice.dispatch("start",payload());oldStart=host.last().reply;
        voice.reset("thread-2");oldStart.receive(new JSONObject(),null);check(!voice.active(),"Old ACK cannot revive replaced thread");
        VoiceTransport.attach(OWNER);VoiceTransport.signal("87654321-1234-1234-1234-123456789abc","answer",SDP);check(VoiceTransport.poll(OWNER)==null,"Private signaling owner bound");
        VoiceTransport.signal(OWNER,"answer",SDP);VoiceTransport.detach(OWNER);check(VoiceTransport.poll(OWNER)==null,"Signal erased on screen close");
        check(CodexVoice.safeError("realtime conversation requires API key auth").contains("stopped"),"API fallback explicitly blocked");
        voice.reset("thread-1");VoiceTransport.attach(OWNER);voice.dispatch("start",payload());host.last().reply.receive(new JSONObject(),null);voice.dispatch("transport_ready",payload());
        host.failStop=true;voice.dispatch("stop",payload());check(voice.snapshot().optBoolean("blocked")&&!voice.active(),"Unconfirmed stop-send failure blocks a new call");
        size=host.requests.size();voice.dispatch("start",payload());check(host.requests.size()==size,"Stop-send failure cannot overlap a fresh same-thread call");
        host.failStop=false;voice.reset("thread-1");VoiceTransport.attach(OWNER);voice.dispatch("start",payload());host.last().reply.receive(new JSONObject(),null);voice.dispatch("transport_ready",payload());
        voice.event("thread/realtime/transcript/delta",object("threadId","thread-1","role","user","delta","previous account draft"));
        voice.event("thread/realtime/transcript/done",object("threadId","thread-1","role","assistant","text","previous account answer"));
        voice.accountChanged();check("thread/realtime/stop".equals(host.last().method),"Account change explicitly stops official voice");
        check(voice.snapshot().optBoolean("blocked")&&"thread-1".equals(voice.snapshot().optString("threadId")),"Account change requires reconnect without losing stop scope");
        check(!voice.snapshot().toString().contains("previous account"),"Account change erases prior account captions");
        host.last().reply.receive(new JSONObject(),null);check(voice.snapshot().optBoolean("blocked")&&!voice.active(),"Confirmed account-change stop remains blocked");
        voice.reset("thread-1");check(!voice.snapshot().optBoolean("blocked"),"Only actual session reset restores voice eligibility");
        VoiceTransport.attach(OWNER);voice.dispatch("start",payload());host.last().reply.receive(new JSONObject(),null);voice.dispatch("transport_ready",payload());
        voice.event("thread/realtime/transcript/done",object("threadId","thread-1","role","assistant","text","private old caption"));voice.closed();
        check(list(voice.snapshot(),"transcript").length()==0&&voice.snapshot().optString("assistantCaption").isEmpty(),"Disconnect clears voice captions");
        if(args.length>0){Files.write(Paths.get(args[0]+"/voice-start.json"),start.toString().getBytes(StandardCharsets.UTF_8));Files.write(Paths.get(args[0]+"/voice.html"),VoiceWebPage.html().getBytes(StandardCharsets.UTF_8));}
        System.out.println("Codex voice: "+count+" assertions passed");
    }
}
