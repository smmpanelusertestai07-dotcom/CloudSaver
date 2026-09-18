package com.pocketide;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The app's one way of asking the running editor to do something: a request file in the
 * folder the app binds into the editor as /run/pocketide, read once and deleted by the
 * PocketIDE Companion extension (app/assets/companion). Two requests exist: run a command in
 * a new terminal, and run one of the editor's own commands, such as bringing an agent's panel
 * to the front. Nothing is read back, and a request left by anything else in that Linux gains
 * it nothing it could not already do.
 */
final class Companion {

    private static final String INBOX = "editor-inbox";

    private Companion() {}

    /** Asks for a terminal in {@code cwd} running {@code command}. */
    static boolean terminal(Context context, String name, String cwd, String command) {
        try {
            return leave(context, new JSONObject()
                    .put("action", "terminal")
                    .put("name", name)
                    .put("cwd", cwd)
                    .put("command", command));
        } catch (JSONException impossible) {
            return false;
        }
    }

    /** Asks the editor to run one of its own commands, for example a panel's focus command. */
    static boolean command(Context context, String command) {
        try {
            return leave(context, new JSONObject().put("action", "command").put("command", command));
        } catch (JSONException impossible) {
            return false;
        }
    }

    private static boolean leave(Context context, JSONObject request) {
        File inbox = new File(PhoneBroker.bridgeDir(context), INBOX);
        if (!inbox.isDirectory() && !inbox.mkdirs()) return false;
        File file = new File(inbox, System.currentTimeMillis() + "-" + System.nanoTime() % 1000 + ".json");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException failed) {
            Log.w(App.TAG, "A request to the editor could not be written", failed);
            return false;
        }
    }
}
