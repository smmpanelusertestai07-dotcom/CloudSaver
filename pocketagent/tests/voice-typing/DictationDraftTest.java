package com.pocketagent.mobile;

public final class DictationDraftTest {
    private static int assertions;
    private static void check(boolean condition, String reason) {
        assertions++; if (!condition) throw new AssertionError(reason);
    }
    private static DictationDraft.Proposal insertion(String draft, int start, int end, String speech) {
        return new DictationDraft("chat-a/account-a", draft, start, end).propose("chat-a/account-a", draft, speech);
    }
    public static void main(String[] args) {
        DictationDraft empty = new DictationDraft("chat-a", "", 0, 0);
        check("Hello दुनिया 👋".equals(empty.propose("chat-a", "", " Hello दुनिया 👋 ").text), "Multilingual final text inserted intact");
        DictationDraft draft = new DictationDraft("chat-a", "Hello world", 6, 11);
        check("Hello everyone".equals(draft.propose("chat-a", "Hello world", "everyone").text), "Selection replacement preserves prefix");
        check(draft.propose("chat-b", "Hello world", "everyone") == null, "Final result cannot enter another chat");
        check(draft.propose("chat-a/account-b", "Hello world", "everyone") == null, "Final result cannot enter another account scope");
        check(draft.propose("chat-a", "Hello edited world", "everyone") == null, "Concurrent draft edits are never overwritten");
        check(draft.propose("chat-a", "Hello world", " \n ") == null, "Empty recognition does not delete selected text");
        DictationDraft.Proposal middle = insertion("Hello world", 5, 5, "beautiful");
        check("Hello beautiful world".equals(middle.text), "Insert separates adjacent spoken words");
        check(middle.cursor == 15, "Cursor follows inserted words");
        check("Hello world!".equals(insertion("Hello !", 6, 6, "world").text), "No spurious space before punctuation");
        check("Hello world!".equals(insertion("Hello old!", 9, 6, "world").text), "Reversed selection is normalized");
        check("नमस्ते दुनिया".equals(insertion("नमस्ते", 6, 6, "दुनिया").text), "Hindi vowel marks still separate words");
        check("Hello 👋".equals(insertion("Hello", -1, -1, "👋").text), "Missing cursor appends safely");
        check("Hello 👋".equals(insertion("Hello", 999, 999, "👋").text), "Oversized cursor stays within draft");
        check("X hello 👋 Y".equals(insertion("X 👋 Y", 3, 3, "hello").text), "Cursor inside emoji does not split surrogate pair");
        check("X hello Y".equals(insertion("X 👋 Y", 2, 3, "hello").text), "Selection touching emoji removes the complete pair");
        check("/plan @file $skill".equals(insertion("", 0, 0, "/plan @file $skill").text), "Recognized symbols remain draft text only");
        VoiceTypingPolicy tokens = new VoiceTypingPolicy(); long old = tokens.begin(); tokens.cancel(); long current = tokens.begin();
        check(!tokens.accepts(old, true, true), "Late callbacks from an old recognizer are rejected");
        check(tokens.accepts(current, true, true), "Rejecting old callbacks leaves the current recording active");
        tokens.cancel(); check(!tokens.accepts(current, true, true), "Pause invalidates delayed final results");
        System.out.println("Inline dictation: " + assertions + " assertions passed");
    }
}
