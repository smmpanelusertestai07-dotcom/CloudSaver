#!/usr/bin/env python3
"""Exercise the production microphone lifecycle with deterministic Android audio/FIFO doubles.

The doubles inject loss of the pipe reader, stalled consumption, dead audio objects and
late cleanup. They do not establish microphone hardware or PulseAudio compatibility.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PROJECT = Path(__file__).resolve().parents[1]
STUBS = {
'android/content/Context.java': '''package android.content;
public class Context { public Context getApplicationContext() { return this; } }''',
'com/pocketlinux/ContainerRuntime.java': '''package com.pocketlinux;
public class ContainerRuntime {
 public static java.io.File rootfs(android.content.Context context) { return new java.io.File("/test-root"); }
}''',
'android/os/SystemClock.java': '''package android.os;
public final class SystemClock { public static long elapsedRealtime() { return System.nanoTime()/1000000L; } }''',
'android/media/AudioFormat.java': '''package android.media;
public class AudioFormat { public static final int CHANNEL_IN_MONO=16, ENCODING_PCM_16BIT=2; }''',
'android/media/MediaRecorder.java': '''package android.media;
public class MediaRecorder { public static class AudioSource { public static final int VOICE_RECOGNITION=6; } }''',
'android/media/AudioRecord.java': r'''package android.media;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class AudioRecord {
 public static final int STATE_INITIALIZED=1, READ_NON_BLOCKING=1;
 public static final AtomicInteger made=new AtomicInteger(), active=new AtomicInteger(),
   released=new AtomicInteger(), reads=new AtomicInteger();
 public static volatile int result=0;
 public static volatile boolean initialized=true, holdConstructor=false;
 public static final CountDownLatch constructing=new CountDownLatch(1), releaseConstructor=new CountDownLatch(1);
 private boolean recording, closed;
 public static int getMinBufferSize(int rate,int channel,int format) { return 1024; }
 public AudioRecord(int source,int rate,int channel,int format,int size) {
  made.incrementAndGet();
  if (holdConstructor) {
   constructing.countDown();
   boolean interrupted=false;
   for (;;) { try { releaseConstructor.await(); break; } catch (InterruptedException e) { interrupted=true; } }
   if (interrupted) Thread.currentThread().interrupt();
  }
 }
 public int getState() { return initialized ? STATE_INITIALIZED : 0; }
 public synchronized void startRecording() { if(closed)throw new IllegalStateException(); recording=true; active.incrementAndGet(); }
 public synchronized int read(byte[] data,int offset,int size,int mode) {
  if(mode!=READ_NON_BLOCKING)throw new AssertionError("blocking audio read");
  reads.incrementAndGet();
  return closed ? -6 : result;
 }
 public synchronized void stop() { if(recording) { recording=false; active.decrementAndGet(); } }
 public synchronized void release() { if(!closed) { stop(); closed=true; released.incrementAndGet(); } }
}''',
'android/system/ErrnoException.java': '''package android.system;
public class ErrnoException extends Exception { public final int errno;
 public ErrnoException(String what,int errno) { super(what); this.errno=errno; } }''',
'android/system/StructStat.java': '''package android.system;
public class StructStat { public final int st_mode; public StructStat(int mode) { st_mode=mode; } }''',
'android/system/OsConstants.java': '''package android.system;
public class OsConstants {
 public static final int O_WRONLY=1,O_NONBLOCK=2,O_CLOEXEC=4,O_NOFOLLOW=8,EAGAIN=11,EINTR=4;
 public static boolean S_ISFIFO(int mode) { return mode==4096; }
}''',
'android/system/Os.java': r'''package android.system;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class Os {
 public static volatile boolean fifo=true, reader=true, blocked=false, holdFirstClose=false;
 public static final AtomicInteger opened=new AtomicInteger(), closed=new AtomicInteger(), writes=new AtomicInteger();
 public static final CountDownLatch closing=new CountDownLatch(1), releaseClose=new CountDownLatch(1);
 public static StructStat lstat(String path) throws ErrnoException { return new StructStat(fifo?4096:32768); }
 public static StructStat fstat(FileDescriptor descriptor) { return new StructStat(fifo?4096:32768); }
 public static FileDescriptor open(String path,int flags,int mode) throws ErrnoException {
  if((flags & OsConstants.O_NONBLOCK)==0 || (flags & OsConstants.O_NOFOLLOW)==0)
   throw new AssertionError("unsafe FIFO open flags");
  if(!reader)throw new ErrnoException("No reader",6);
  opened.incrementAndGet(); return new FileDescriptor();
 }
 public static int write(FileDescriptor fd,byte[] bytes,int offset,int count) throws ErrnoException {
  writes.incrementAndGet();
  if(blocked)throw new ErrnoException("Backpressure",OsConstants.EAGAIN);
  return Math.min(128,count);
 }
 public static void close(FileDescriptor fd) throws ErrnoException {
  int n=closed.incrementAndGet();
  if(holdFirstClose && n==1) {
   closing.countDown();
   boolean interrupted=false;
   for(;;) { try { releaseClose.await(); break; } catch(InterruptedException e) { interrupted=true; } }
   if(interrupted)Thread.currentThread().interrupt();
  }
 }
}''',
'com/pocketlinux/MicBridgeHarness.java': r'''package com.pocketlinux;
import android.media.AudioRecord;
import android.system.Os;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
public class MicBridgeHarness {
 static void check(boolean yes,String message) { if(!yes)throw new AssertionError(message); }
 static void await(BooleanSupplier condition,String message) throws Exception {
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  while(!condition.getAsBoolean() && System.nanoTime()<deadline)Thread.sleep(5);
  check(condition.getAsBoolean(),message);
 }
 public static void main(String[] args) throws Exception {
  MicBridge bridge=new MicBridge(new android.content.Context());
  switch(args[0]) {
   case "no-reader":
    Os.reader=false; bridge.start(); await(()->!bridge.isRunning(),"No-reader start stuck");
    check(AudioRecord.made.get()==0,"Recording opened before checking reader");
    check(bridge.problem()!=null,"No-reader failure hidden"); break;
   case "regular-file":
    Os.fifo=false; bridge.start(); check(!bridge.isRunning(),"Regular file accepted");
    check(AudioRecord.made.get()==0 && Os.opened.get()==0,"Regular file opened/recorded"); break;
   case "dead-audio":
    AudioRecord.result=-6; bridge.start(); await(()->!bridge.isRunning(),"Dead audio spins forever");
    check(AudioRecord.reads.get()==1,"Invalid audio recorder retried");
    check(AudioRecord.active.get()==0 && AudioRecord.released.get()==1,"Dead audio leaked recorder"); break;
   case "backpressure-stop":
    AudioRecord.result=2048; Os.blocked=true; bridge.start(); await(()->Os.writes.get()>0,"No FIFO write");
    long at=System.nanoTime(); bridge.stop();
    check(System.nanoTime()-at<TimeUnit.SECONDS.toNanos(1),"Stop blocked on FIFO");
    check(AudioRecord.active.get()==0 && AudioRecord.released.get()==1,"Stop kept recording");
    await(()->Os.closed.get()==1,"Stop leaked FIFO"); break;
   case "backpressure-timeout":
    AudioRecord.result=2048; Os.blocked=true; bridge.start();
    await(()->!bridge.isRunning(),"Backpressure never timed out");
    check(bridge.problem()!=null && AudioRecord.active.get()==0,"Timeout leaked recording");
    check(Os.writes.get()<500,"Backpressure busy-spin"); break;
   case "cancel-initialization":
    AudioRecord.holdConstructor=true; bridge.start();
    check(AudioRecord.constructing.await(2,TimeUnit.SECONDS),"No recorder initialization");
    bridge.stop(); AudioRecord.releaseConstructor.countDown();
    await(()->AudioRecord.released.get()==1,"Cancelled candidate not released");
    check(AudioRecord.active.get()==0,"Recording started after Stop"); break;
   case "rapid-restart":
    Os.holdFirstClose=true; bridge.start(); await(()->AudioRecord.active.get()==1,"First session not active");
    bridge.stop(); check(Os.closing.await(2,TimeUnit.SECONDS),"Old cleanup not reached");
    bridge.start(); await(()->AudioRecord.active.get()==1,"New session not active");
    Os.releaseClose.countDown(); Thread.sleep(100);
    check(bridge.isRunning() && AudioRecord.active.get()==1,"Old cleanup stopped new recording");
    bridge.stop(); check(AudioRecord.active.get()==0,"New session not stopped"); break;
   case "silent-audio":
    bridge.start(); await(()->AudioRecord.reads.get()>1,"No audio reads"); Thread.sleep(150);
    bridge.stop(); check(AudioRecord.reads.get()<30,"Empty nonblocking reads busy-spin");
    check(AudioRecord.active.get()==0,"Silence stop kept recording"); break;
   case "uninitialized":
    AudioRecord.initialized=false; bridge.start(); await(()->!bridge.isRunning(),"Uninitialized start stuck");
    check(AudioRecord.released.get()==1 && AudioRecord.active.get()==0,"Uninitialized recorder leaked"); break;
   default: throw new AssertionError("Unknown case");
  }
  System.out.println("PASS microphone " + args[0]);
 }
}'''
}


class MicrophoneTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='pd-microphone-')
        cls.folder = Path(cls.tmp.name)
        for name, text in STUBS.items():
            path = cls.folder / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        java = shutil.which('java')
        javac = [shutil.which('javac')] if shutil.which('javac') else [java, '-m', 'jdk.compiler/com.sun.tools.javac.Main']
        cls.java = java
        compilation = subprocess.run(javac + ['-encoding', 'UTF-8', '-source', '8', '-target', '8', '-d', str(cls.folder)]
                       + [str(cls.folder / name) for name in STUBS]
                       + [str(PROJECT / 'app/src/com/pocketlinux/MicBridge.java')],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if compilation.returncode:
            raise AssertionError(compilation.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def check_case(self, case):
        result = subprocess.run([self.java, '-cp', str(self.folder), 'com.pocketlinux.MicBridgeHarness', case],
                                timeout=10, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


for scenario in ('no-reader', 'regular-file', 'dead-audio', 'backpressure-stop', 'backpressure-timeout',
                 'cancel-initialization', 'rapid-restart', 'silent-audio', 'uninitialized'):
    setattr(MicrophoneTest, 'test_' + scenario.replace('-', '_'),
            lambda self, scenario=scenario: self.check_case(scenario))

if __name__ == '__main__':
    unittest.main()
