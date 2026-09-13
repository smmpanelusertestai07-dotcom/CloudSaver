package com.pocketagent.mobile;

public final class VoiceTypingPolicyTest {
    private static int count;
    private static void check(boolean pass, String name) { count++; if (!pass) throw new AssertionError(name); }
    public static void main(String[] args) {
        VoiceTypingPolicy policy = new VoiceTypingPolicy();
        check(!policy.active(), "No recording on construction");
        check(!policy.accepts(0, true, true), "Unstarted callback rejected");
        long first = policy.begin();
        check(policy.active(), "Explicit session begins");
        check(policy.accepts(first, true, true), "Live foreground callback accepted");
        check(!policy.accepts(first, false, true), "Background callback rejected");
        check(!policy.accepts(first, true, false), "Locked callback rejected");
        policy.cancel();
        check(!policy.active(), "Pause cancels active recording");
        check(!policy.accepts(first, true, true), "Delayed result after pause rejected");
        long second = policy.begin();
        check(!policy.accepts(first, true, true), "Old callback cannot enter new session");
        check(policy.accepts(second, true, true), "Only current retry accepted");
        policy.cancel(); policy.cancel();
        check(!policy.accepts(second, true, true), "Repeated cleanup remains safe");
        check(VoiceTypingPolicy.clean(null).isEmpty(), "Missing recognition handled");
        check(VoiceTypingPolicy.clean(" \n \t").isEmpty(), "Empty recognized speech");
        check("Hello दुनिया 👋".equals(VoiceTypingPolicy.clean(" Hello दुनिया 👋 ")), "Multilingual voice result retained");
        check("ab\nc\td".equals(VoiceTypingPolicy.clean("a\u0000b\nc\td\u001b")), "Control bytes removed");
        check("xyz".equals(VoiceTypingPolicy.clean("x\ud800y\udc00z")), "Broken UTF-16 removed");
        StringBuilder huge = new StringBuilder(); for (int i = 0; i < VoiceTypingPolicy.MAX_TEXT + 99; i++) huge.append('x');
        check(VoiceTypingPolicy.clean(huge.toString()).length() == VoiceTypingPolicy.MAX_TEXT, "Remote result size bounded");
        String edge = huge.substring(0, VoiceTypingPolicy.MAX_TEXT - 1) + "👋";
        check(VoiceTypingPolicy.clean(edge).length() == VoiceTypingPolicy.MAX_TEXT - 1, "Size bound does not split surrogate pair");
        check("/plan @file $skill".equals(VoiceTypingPolicy.clean("/plan @file $skill")), "Transcript remains text, no commands executed");
        System.out.println("Voice typing: " + count + " assertions passed");
    }
}
