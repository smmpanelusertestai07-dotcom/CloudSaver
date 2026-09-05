package com.pocketdesk;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rule that a whole release was lost to: an Activity is not a usable Context yet when its
 * own fields are built.
 *
 * Android creates an Activity in two steps. First it calls the constructor -- and a Java
 * constructor runs every field initializer before its own body. Only afterwards does Android
 * attach the base context. So this line, which reads like ordinary Java,
 *
 *     private final MicBridge microphone = new MicBridge(this);
 *
 * hands over an Activity whose base context is still null. The moment the other end asks it
 * anything -- getApplicationContext(), getSystemService(), getResources() -- it throws a
 * NullPointerException from inside the constructor. The screen is never created, so the phone
 * shows a black flash and falls back to the previous screen, and Android's own tidying-up then
 * throws "Activity client record must not be null" on top, which is what gets reported. Every
 * symptom points at Android; the cause is one word in one field.
 *
 * There is no way to catch this at runtime -- the failure happens before any code of ours runs --
 * and no way to see it in a compiler. It has to be read out of the source, so that is what this
 * does: find every field initializer that hands `this` to another class, then read that class's
 * constructor and fail if it asks the context anything at all.
 */
public final class ActivityStartupTest {

    /** Everything an Activity cannot answer before Android attaches it. */
    private static final String[] NEEDS_A_REAL_CONTEXT = {
            "getApplicationContext(",
            "getSystemService(",
            "getResources(",
            "getPackageManager(",
            "getPackageName(",
            "getContentResolver(",
            "getSharedPreferences(",
            "getFilesDir(",
            "getCacheDir(",
            "getDir(",
            "getAssets(",
            "getTheme(",
            "getString(",
            "getText(",
            "getColor(",
            "getExternalFilesDir(",
            "getMainLooper(",
    };

    /**
     * A field initializer, told from a local variable by its indentation: a class body is
     * indented four spaces in this project and a method body eight or more.
     */
    private static final Pattern FIELD_WITH_THIS = Pattern.compile(
            "(?m)^ {4}(?:(?:private|protected|public|static|final|volatile|transient)\\s+)*"
                    + "[\\w.<>\\[\\]]+\\s+(\\w+)\\s*=\\s*new\\s+([\\w.]+)\\s*\\(\\s*this\\s*[,)]");

    private static int checks;

    public static void main(String[] args) throws Exception {
        File project = new File(args.length > 0 ? args[0] : ".");
        File sources = new File(project, "app/src/com/pocketdesk");
        require(sources.isDirectory(), "sources", "app/src/com/pocketdesk is missing at " + sources);

        List<File> activities = new ArrayList<>();
        File[] all = sources.listFiles();
        if (all != null) {
            for (File one : all) {
                if (one.getName().endsWith("Activity.java")) activities.add(one);
            }
        }
        require(!activities.isEmpty(), "sources", "no Activity source was found to check");

        Set<String> handedThis = new LinkedHashSet<>();
        for (File activity : activities) {
            String text = read(activity);
            Matcher matcher = FIELD_WITH_THIS.matcher(text);
            while (matcher.find()) {
                handedThis.add(matcher.group(2) + " (field " + matcher.group(1)
                        + " of " + activity.getName() + ")");
                checkConstructor(sources, matcher.group(2), matcher.group(1), activity.getName());
            }
        }
        System.out.println("       checked " + handedThis.size()
                + " field(s) built with `this` in an Activity: " + handedThis);

        System.out.println("PASS ActivityStartup (" + checks + " checks)");
    }

    /** Fails when a class built as an Activity field asks its context anything in its constructor. */
    private static void checkConstructor(File sources, String type, String field, String activity)
            throws IOException {
        File file = new File(sources, type + ".java");
        if (!file.isFile()) return;              // a framework class: not ours to read
        String body = constructorBody(read(file), type);
        if (body == null) return;                // no constructor written: nothing can run early
        for (String forbidden : NEEDS_A_REAL_CONTEXT) {
            require(!body.contains(forbidden),
                    activity + "." + field,
                    "it is built as a field with `new " + type + "(this)`, so it runs before "
                            + "Android attaches the screen -- and " + type + "'s constructor "
                            + "calls " + forbidden + "), which throws. Build it in onCreate, or "
                            + "keep the context untouched until it is used.");
        }
    }

    /** The body of the named class's constructor, or null when it has none. */
    private static String constructorBody(String source, String type) {
        Matcher head = Pattern.compile("(?m)^\\s*(?:(?:private|protected|public)\\s+)?"
                + Pattern.quote(type) + "\\s*\\([^)]*\\)\\s*\\{").matcher(source);
        if (!head.find()) return null;
        int depth = 0;
        for (int at = head.end() - 1; at < source.length(); at++) {
            char c = source.charAt(at);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(head.end(), at);
        }
        return null;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String what, String why) {
        checks++;
        if (!condition) {
            System.out.println("FAIL ActivityStartup: " + what + " -- " + why);
            System.exit(1);
        }
    }
}
