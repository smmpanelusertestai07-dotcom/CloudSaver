package com.pocketagent.mobile;

/** Constant local-only media transport. No remote scripts, links, tokens or conversation content. */
final class VoiceWebPage {
    private VoiceWebPage() { }
    static String html() {
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head><body><audio id='speaker' autoplay></audio><script>"
            + "'use strict';(()=>{let pc=null,dc=null,stream=null,sender=null,closed=false,opened=false,muted=false;"
            + "const audio=document.getElementById('speaker');"
            + "function close(){closed=true;if(stream){for(const t of stream.getTracks())t.stop();stream=null;}audio.pause();audio.srcObject=null;if(dc){dc.close();dc=null;}if(pc){pc.close();pc=null;}}"
            + "function fail(){if(closed)return;close();PocketVoiceHost.failed();}"
            + "async function devices(){if(closed||opened)return;opened=true;try{const captured=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true},video:false});if(closed){for(const t of captured.getTracks())t.stop();return;}stream=captured;const track=stream.getAudioTracks()[0];if(!track)throw Error();track.enabled=!muted;track.onended=fail;await sender.replaceTrack(track);if(closed)return;PocketVoiceHost.ready();}catch(_){fail();}}"
            + "window.pocketVoice={async start(){try{if(pc||closed)return;if(!window.isSecureContext||!window.RTCPeerConnection||!navigator.mediaDevices)throw Error();pc=new RTCPeerConnection({iceServers:[]});sender=pc.addTransceiver('audio',{direction:'sendrecv'}).sender;pc.ontrack=e=>{if(closed)return;audio.srcObject=new MediaStream([e.track]);audio.play().catch(fail);};pc.ondatachannel=e=>e.channel.close();pc.onconnectionstatechange=()=>{if(pc&&['failed','disconnected','closed'].includes(pc.connectionState))fail();};dc=pc.createDataChannel('oai-events',{ordered:true});dc.onopen=devices;dc.onerror=fail;dc.onclose=()=>{if(!closed)fail();};dc.onmessage=()=>{};await pc.setLocalDescription(await pc.createOffer());if(pc.iceGatheringState!=='complete')await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error()),10000);pc.addEventListener('icegatheringstatechange',()=>{if(pc&&pc.iceGatheringState==='complete'){clearTimeout(timer);resolve();}});});if(!closed&&pc.localDescription)PocketVoiceHost.offer(pc.localDescription.sdp);}catch(_){fail();}},"
            + "async answer(sdp){try{if(closed||!pc||pc.remoteDescription)return;await pc.setRemoteDescription({type:'answer',sdp});}catch(_){fail();}},mute(value){muted=!!value;if(stream)for(const t of stream.getAudioTracks())t.enabled=!muted;},close};window.addEventListener('pagehide',close);document.addEventListener('visibilitychange',()=>{if(document.hidden)close();});})();"
            + "</script></body></html>";
    }
}
