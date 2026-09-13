package com.pocketagent.mobile;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ComposerTokensTest {
    private static int checks;
    public static void main(String[] args) {
        token("/", '/', ""); token("/mod", '/', "mod");
        token("  /model", '/', "model"); token("Explain @src/main.ts", '@', "src/main.ts");
        token("Use $skill-creator", '$', "skill-creator"); token("Use $", '$', "");
        noToken("Explain /model"); noToken("a@github.com"); noToken("cost $12");
        noToken("use \\$skill"); noToken("use `@src/main.ts"); noToken("```\n$skill");
        noToken("~~~\n@file"); noToken("$(command)"); noToken("$$"); noToken("/usr/local");
        noToken("say \"$skill"); noToken("bad @../file");
        check(ComposerTokens.atCursor("@src/main.ts", 4) == null, "Do not replace half a token");
        ComposerTokens.Token inMiddle = ComposerTokens.atCursor("Use $skill next", 10);
        check(inMiddle != null && inMiddle.query.equals("skill"), "Cursor supports complete middle token");
        eq("Use picked next", ComposerTokens.replace("Use $skill next", inMiddle, "picked"));
        eq("Use changed next", ComposerTokens.replace("Use changed next", inMiddle, "picked"));
        eq("$skill", ComposerTokens.replace("$skill", null, ""));

        ComposerTokens.Command command = ComposerTokens.leadingCommand(" \n/PLAN inspect this\nthen explain");
        check(command != null && command.key.equals("plan"), "Case-insensitive command");
        eq("inspect this\nthen explain", command.arguments);
        check(ComposerTokens.leadingCommand("Use /plan please") == null, "No prose commands");
        check(ComposerTokens.leadingCommand("`/model`") == null, "No inline code command");
        check(ComposerTokens.leadingCommand("/tmp/file") == null, "No absolute-path command");
        check(ComposerTokens.leadingCommand("/") == null, "Bare slash opens chooser only");
        check(ComposerTokens.leadingCommand("/model(tab)") == null, "No truncated command");
        check(ComposerTokens.leadingCommand("/unknown") != null, "Unknown parsed for explanatory UI");
        check(ChatCommandCatalog.find("unknown") == null, "Unknown command never executes");

        refs("Use $skill-creator with @src/index.ts", "$skill-creator", "@src/index.ts");
        refs("$one, $two. @file.ts!", "$one", "$two", "@file.ts");
        refs("a@b.com $99 \\$escaped ` $inside @hidden ` $real", "$real");
        refs("```java\n$hidden @secret\n```\n$visible", "$visible");
        refs("~~~text\n@hidden\n~~~\n@visible", "@visible");
        refs("`` a ` $hidden `` $visible", "$visible");
        refs("```\n$hidden\n``\n$stillhidden");
        refs("```\n$hidden\n```not a closing fence\n$stillhidden");
        refs("$tool/path $tool() $tool=x @name@email.com @path(thing) $tool+more");
        refs("$ @");
        refs("Explain /model and $valid", "$valid");
        refs("  ```\n$hidden\n  ```\n@visible", "@visible");
        refs("$first\n`unclosed $hidden", "$first");

        check(ComposerTokens.isProjectPath("src/main.ts"), "Relative file allowed");
        check(ComposerTokens.isProjectPath("folder with spaces/a.txt"), "Picker allows filenames with spaces");
        check(ComposerTokens.isProjectPath(".github/workflows/build.yml"), "Hidden project file allowed");
        for (String path : new String[]{"", "/etc/passwd", "../file", "a/../file", "./file", "a//file", "a/", "C:/file", "a\\file", "a\nfile", "a\u0000file"})
            check(!ComposerTokens.isProjectPath(path), "Reject non-contained path");

        Set<String> keys = new HashSet<>();
        for (ChatCommandCatalog.Entry entry : ChatCommandCatalog.entries()) {
            check(keys.add(entry.key), "Unique command");
            check(ChatCommandCatalog.find(entry.key) == entry, "Exact catalog lookup");
        }
        check(keys.size() == 18, "Only implemented commands advertised");
        check(ChatCommandCatalog.filter("/mo", "codex").size() == 1, "Slash filtering");
        check(ChatCommandCatalog.filter("", "claude").size() == 3, "Hide unsupported Codex actions");
        check(ChatCommandCatalog.find("MENTION").acceptsText, "Mention permits path argument");
        ChatCommandCatalog.Entry[] clone = ChatCommandCatalog.entries(); clone[0] = null;
        check(ChatCommandCatalog.entries()[0] != null, "Catalog array cannot be changed by caller");

        StringBuilder maximum = new StringBuilder(); while (maximum.length() < 63990) maximum.append('a');
        maximum.append(" $skill"); refs(maximum.toString(), "$skill");
        maximum.append("extra text"); check(ComposerTokens.references(maximum.toString()).isEmpty(), "Oversized input bounded");
        check(ComposerTokens.atCursor(null, 1) == null, "Null input");
        check(ComposerTokens.atCursor("a", -1) == null, "Invalid cursor");
        System.out.println("PASS " + checks + " composer token/catalog assertions");
    }

    private static void refs(String text, String... expected) {
        List<ComposerTokens.Token> found = ComposerTokens.references(text);
        check(found.size() == expected.length, "Reference count: " + text.substring(0, Math.min(100, text.length())));
        for (int i = 0; i < expected.length; i++) eq(expected[i], found.get(i).raw);
    }
    private static void token(String text, char prefix, String query) {
        ComposerTokens.Token token = ComposerTokens.atCursor(text, text.length());
        check(token != null && token.prefix == prefix && query.equals(token.query), "Token " + text);
    }
    private static void noToken(String text) { check(ComposerTokens.atCursor(text, text.length()) == null, "No token " + text); }
    private static void eq(String expected, String actual) { check(expected.equals(actual), "Expected " + expected + ", received " + actual); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
