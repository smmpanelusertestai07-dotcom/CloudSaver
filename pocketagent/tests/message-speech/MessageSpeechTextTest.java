package com.pocketagent.mobile;

public final class MessageSpeechTextTest {
    private static int assertions;

    private static void check(boolean value, String detail) {
        assertions++;
        if (!value) throw new AssertionError(detail);
    }

    private static void chunks(String text, int bound) {
        int position = 0;
        StringBuilder restored = new StringBuilder();
        while (position < text.length()) {
            int end = MessageSpeechText.nextEnd(text, position, bound);
            check(end > position && end - position <= bound, "bounded forward progress");
            check(end == text.length() || !Character.isLowSurrogate(text.charAt(end)), "surrogate not split");
            restored.append(text, position, end); position = end;
        }
        check(restored.toString().equals(text), "no truncation or duplication");
    }

    public static void main(String[] args) {
        check("en".equals(MessageSpeechText.locale("en", "नमस्ते").getLanguage()), "explicit English retained");
        check("hi".equals(MessageSpeechText.locale("hi", "Hello").getLanguage()), "explicit Hindi retained");
        check("hi".equals(MessageSpeechText.locale("auto", "Hello, नमस्ते दुनिया").getLanguage()), "Hindi script detected");
        check("en".equals(MessageSpeechText.locale("auto", "Hello world").getLanguage()), "English auto");
        check("en".equals(MessageSpeechText.locale(null, null).getLanguage()), "null safe");
        check("auto".equals(MessageSpeechText.choice("javascript:bad")), "invalid preference rejected");
        chunks("Short message.", 3000);
        chunks("A sentence. Another line.\nHindi text: नमस्ते दुनिया। Yes! Next?", 16);
        chunks("1234567😀abcdefgh😀😀😀😀", 8);
        chunks("😀😀😀😀😀", 2);
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 10000; i++) large.append("नमस्ते 😀 Hello. ");
        chunks(large.toString(), 3000);
        check(MessageSpeechText.nextEnd("hello", 5, 10) == 5, "end of input");
        check(MessageSpeechText.nextEnd("hello", 0, 2) == 2, "long word split bounded");
        boolean rejected = false;
        try { MessageSpeechText.nextEnd("hello", 0, 1); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "invalid bound rejected");
        System.out.println("MessageSpeechTextTest: " + assertions + " checks passed");
    }
}
