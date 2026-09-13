'use strict';
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync(process.argv[2],'utf8').match(/<script>([\s\S]*)<\/script>/)[1];
let capture=0,closedTracks=0,ready=0,failed=0,offers=[],dc,pc;
const track={enabled:true,stop(){closedTracks++}};
const speaker={pause(){},play(){return Promise.resolve()},srcObject:null};
class Peer {
  constructor(settings){assert.deepEqual(settings,{iceServers:[]});pc=this;this.iceGatheringState='complete';this.remoteDescription=null;this.connectionState='new';}
  addTransceiver(kind,settings){assert.equal(kind,'audio');assert.equal(settings.direction,'sendrecv');return{sender:{replaceTrack:async t=>assert.equal(t,track)}};}
  createDataChannel(name,settings){assert.equal(name,'oai-events');assert.equal(settings.ordered,true);dc={close(){}};return dc;}
  async createOffer(){return{type:'offer',sdp:'v=0\r\nm=audio 9\r\nm=application 9\r\n'}}
  async setLocalDescription(value){this.localDescription=value;}
  async setRemoteDescription(value){this.remoteDescription=value;}
  close(){this.connectionState='closed';}
}
const context={window:{isSecureContext:true,RTCPeerConnection:Peer,addEventListener(){}},RTCPeerConnection:Peer,
  navigator:{mediaDevices:{getUserMedia:async options=>{assert.equal(options.video,false);capture++;return{getAudioTracks:()=>[track],getTracks:()=>[track]}}}},
  document:{getElementById:()=>speaker,addEventListener(){}},MediaStream:class{},
  PocketVoiceHost:{offer:s=>offers.push(s),ready:()=>ready++,failed:()=>failed++},setTimeout,clearTimeout};
vm.runInNewContext(source,context);
(async()=>{
  await context.window.pocketVoice.start();assert.equal(capture,0,'No microphone capture during offer/account eligibility');assert.equal(offers.length,1);
  await context.window.pocketVoice.answer('v=0\r\n');assert.equal(capture,0,'Remote SDP alone does not start recording');
  await dc.onopen();assert.equal(capture,1);assert.equal(ready,1);assert.equal(failed,0);
  context.window.pocketVoice.mute(true);assert.equal(track.enabled,false);context.window.pocketVoice.mute(false);assert.equal(track.enabled,true);
  context.window.pocketVoice.close();assert.equal(closedTracks,1);assert.equal(pc.connectionState,'closed');
  assert.equal(speaker.srcObject,null);console.log('Trusted voice page lifecycle: 10 assertions passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
