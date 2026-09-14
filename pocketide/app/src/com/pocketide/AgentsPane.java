package com.pocketide;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Extensions, in three tiers, with the reason for the tiers stated on the screen.
 *
 * Tier one is the three recommended agents: verified publishers, pinned to a checksum this app
 * verifies before anything is installed. Tier two is the whole Open VSX registry filtered to
 * verified publishers. Tier three is everything else, off by default, behind a warning.
 *
 * The tiers are not caution for its own sake. Through 2026 counterfeit extensions appeared on
 * both major registries -- 73 cloned packages in April, 77 impersonating AMD, Azure, Salesforce
 * and a US government agency in the summer -- and every one of them was published by an account
 * unaffiliated with the publisher it imitated. Verified namespaces were not the vulnerability;
 * they were the thing the counterfeits could not obtain. Filtering on that is the single most
 * effective thing this screen can do, and it costs nothing an honest publisher would miss.
 */
final class AgentsPane implements Pane {

    private Activity host;

    @Override public void shown(Activity activity) { host = activity; }

    @Override public void hidden(Activity activity) {
        // The search box schedules work on a delay; a keystroke still in flight when the pane
        // leaves would otherwise redraw a screen nobody is looking at.
        handler.removeCallbacksAndMessages(null);
    }

    /** Coming back keeps the search and its results; a rebuild would empty both. */
    @Override public boolean rebuildOnReturn() { return false; }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout searchResults;
    private TextView searchState;
    private EditText searchBox;
    private LinearLayout recommendedList;
    private volatile int searchGeneration;




    // ------------------------------------------------------------------ the screen

    @Override public String key() { return "agents"; }

    @Override public View build(Activity activity) {
        host = activity;
        boolean dark = Ui.dark(host);

        LinearLayout content = Ui.column(host);
        content.addView(intro(dark));
        content.addView(recommendedCard(dark), Ui.wide(host, 16));
        content.addView(searchCard(dark), Ui.wide(host, 16));
        content.addView(unverifiedCard(dark), Ui.wide(host, 16));
        // Filled here, not left for an install to fill: the recommended list opened EMPTY
        // until something was installed or removed, because nothing else ever asked for it.
        refreshRecommended();

        // The page itself, unwrapped, so the frame can let it scroll under the floating bar.
        return Ui.page(host, content, dark);
    }

    private View intro(boolean dark) {
        LinearLayout card = Ui.card(host, dark);
        card.addView(Ui.bold(host, "Anything on Open VSX", 17, Ui.text(dark)));
        card.addView(Ui.text(host,
                "This app is not limited to three agents. Open VSX is the extension registry "
                        + "that every non-Microsoft build of VS Code uses — including Google's "
                        + "own Antigravity IDE — and all of it is available here.",
                13.5f, Ui.muted(dark)), Ui.wide(host, 8));
        card.addView(Ui.text(host,
                "Extensions are not sandboxed: VS Code's own documentation says an extension "
                        + "can do anything the editor can. So verified publishers are shown by "
                        + "default, and every install shows you the publisher and the checksum "
                        + "first.",
                12.5f, Ui.muted(dark)), Ui.wide(host, 10));
        card.addView(Ui.text(host,
                "Whatever you install here, and whatever you install from inside the editor, "
                        + "shows up on this screen: the list is read from the editor itself "
                        + "rather than from anything this app remembers doing.",
                12.5f, Ui.muted(dark)), Ui.wide(host, 8));
        return card;
    }

    private View recommendedCard(boolean dark) {
        LinearLayout column = Ui.column(host);
        column.addView(Ui.sectionLabel(host, "Recommended · verified and checksum-pinned", dark));
        recommendedList = Ui.column(host);
        recommendedList.setBackground(Ui.glass(host, dark, 20));
        column.addView(recommendedList, Ui.wide(host, 8));
        TextView why = Ui.text(host, "Why only these three?", 13f, Ui.link(dark));
        why.setPadding(Ui.dp(host, 4), Ui.dp(host, 10), 0, 0);
        why.setMinHeight(Ui.dp(host, Ui.TOUCH_TARGET_DP));
        why.setGravity(android.view.Gravity.CENTER_VERTICAL);
        why.setClickable(true);
        why.setFocusable(true);
        Ui.asButton(why);
        why.setOnClickListener(v ->
                Dialogs.message(host, "Why these three", Agents.WHY_THESE_THREE));
        column.addView(why);
        return column;
    }

    private View searchCard(boolean dark) {
        LinearLayout column = Ui.column(host);
        column.addView(Ui.sectionLabel(host, "Search verified publishers", dark));

        searchBox = new EditText(host);
        searchBox.setHint("Search Open VSX");
        searchBox.setSingleLine(true);
        searchBox.setTextColor(Ui.text(dark));
        searchBox.setHintTextColor(Ui.muted(dark));
        searchBox.setTextSize(15f);
        int pad = Ui.dp(host, 14);
        searchBox.setPadding(pad, pad, pad, pad);
        searchBox.setMinHeight(Ui.dp(host, Ui.TOUCH_TARGET_DP));
        searchBox.setBackground(Ui.outlined(host, Ui.card(dark), Ui.line(dark), 14));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable text) {
                // Typing a letter at a time would fire a request per keystroke. A short pause
                // after the last one is both kinder to the registry and to a metered connection.
                handler.removeCallbacksAndMessages("search");
                handler.postAtTime(() -> runSearch(text.toString()), "search",
                        android.os.SystemClock.uptimeMillis() + 450);
            }
        });
        column.addView(searchBox, Ui.wide(host, 8));

        searchState = Ui.text(host, "Type to search.", 12.5f, Ui.muted(dark));
        column.addView(searchState, Ui.wide(host, 10));

        searchResults = Ui.column(host);
        column.addView(searchResults, Ui.wide(host, 8));
        return column;
    }

    private View unverifiedCard(boolean dark) {
        boolean allowed = Prefs.of(host).getBoolean(Prefs.ALLOW_UNVERIFIED, false);
        LinearLayout column = Ui.column(host);
        column.addView(Ui.sectionLabel(host, "Everything else", dark));
        LinearLayout list = Ui.column(host);
        list.setBackground(Ui.glass(host, dark, 20));
        Ui.Row row = Ui.row(host, dark, R.drawable.ic_shield, "Unverified publishers",
                allowed ? "Shown in search · your risk" : "Hidden — recommended",
                v -> toggleUnverified(!allowed));
        if (allowed) row.setState(Ui.needsYou(dark));
        list.addView(row);
        column.addView(list, Ui.wide(host, 8));
        return column;
    }

    private void toggleUnverified(boolean enable) {
        if (!enable) {
            Prefs.of(host).edit().putBoolean(Prefs.ALLOW_UNVERIFIED, false).apply();
            MainActivity.rebuild(host);
            return;
        }
        Dialogs.confirm(host, "Show unverified publishers?",
                "Open VSX marks a publisher name verified only when it has a real owner. Every "
                        + "counterfeit extension found on the registry in 2026 came from an "
                        + "account unaffiliated with the publisher it imitated — 73 cloned "
                        + "packages in April, 77 impersonating AMD, Azure, Salesforce and a US "
                        + "government agency over the summer.\n\n"
                        + "Extensions are not sandboxed. One can read every file in your "
                        + "Linux, reach the network and run programs.\n\n"
                        + "Turn this on only if you know the publisher.",
                "Show them", true, () -> {
                    Prefs.of(host).edit().putBoolean(Prefs.ALLOW_UNVERIFIED, true).apply();
                    MainActivity.rebuild(host);
                });
    }

    // ------------------------------------------------------------------ recommended

    private void refreshRecommended() {
        if (recommendedList == null) return;
        boolean dark = Ui.dark(host);
        recommendedList.removeAllViews();
        List<String> present = Registry.installed(host);
        boolean first = true;
        for (Agents.Agent agent : Agents.ALL) {
            if (!first) recommendedList.addView(Ui.divider(host, dark, true));
            first = false;
            // Extensions.has, not contains: the registry publishes "Google.google-
            // antigravity" and the editor records it lower-cased, so equals() is false between
            // two spellings of the same extension and everything showed as not installed.
            boolean here = Extensions.has(present, agent.id);
            String value = agent.publisher + " · official · "
                    + (here ? "Installed — tap to open it"
                            : DeviceProbe.formatBytes(agent.sizeBytes) + " · " + agent.plan);
            Ui.Row row = Ui.row(host, dark,
                    here ? R.drawable.ic_check : R.drawable.ic_install,
                    agent.name, value, v -> onAgentTapped(agent, here));
            if (here) row.setState(Ui.running(dark));
            else if (agent.free) row.setState(Ui.accent(dark));
            recommendedList.addView(row);
        }
    }

    private void onAgentTapped(Agents.Agent agent, boolean installed) {
        if (!Workspace.installed(host)) {
            Dialogs.confirm(host, "Set up first",
                    "The editor has to be installed before an extension can go into it.",
                    "Set up", () -> host.startActivity(new Intent(host, SetupActivity.class)));
            return;
        }
        if (installed) {
            // Two things can be done with something already installed, and the one an owner
            // actually wants is the first. "Open kaise karu" -- how do I open it -- was the
            // report that produced this row: the agent was installed, and the only thing the
            // app offered to do with it was delete it.
            Dialogs.choose(host, agent.name,
                    new String[]{"Open it in the editor", "Remove it"},
                    new int[]{R.drawable.ic_code, R.drawable.ic_delete}, -1, index -> {
                        if (index == 0) {
                            host.startActivity(new Intent(host, WorkspaceActivity.class));
                            return;
                        }
                        Dialogs.confirm(host, "Remove " + agent.name + "?",
                                "The extension is removed from the editor. Your files and your "
                                        + "sign-in with " + agent.publisher + " are not touched.",
                                "Remove", true, () -> remove(agent.id));
                    });
            return;
        }
        Dialogs.confirm(host, "Install " + agent.name + "?",
                agent.summary + "\n\n"
                        + "Publisher: " + agent.publisher + " (verified)\n"
                        + "Download: " + DeviceProbe.formatBytes(agent.sizeBytes) + "\n"
                        + "Plan: " + agent.plan + "\n\n"
                        + "The download is checked against the checksum Open VSX publishes "
                        + "before anything is installed.",
                "Install", () -> install(agent.namespace(), agent.shortName(), agent.platform,
                        agent.name));
    }

    // ------------------------------------------------------------------ search

    private void runSearch(String query) {
        if (searchResults == null) return;
        boolean dark = Ui.dark(host);
        final int generation = ++searchGeneration;
        searchResults.removeAllViews();
        if (query == null || query.trim().length() < 2) {
            searchState.setText("Type to search.");
            return;
        }
        searchState.setText("Searching…");
        new Thread(() -> {
            try {
                List<Registry.Listing> found = Registry.search(host, query.trim(), 20);
                host.runOnUiThread(() -> {
                    if (generation != searchGeneration || host.isFinishing()) return;
                    showResults(found, dark);
                });
            } catch (IOException failure) {
                host.runOnUiThread(() -> {
                    if (generation != searchGeneration || host.isFinishing()) return;
                    searchState.setText("Could not reach the registry. " + failure.getMessage());
                });
            }
        }, "search-open-vsx").start();
    }

    private void showResults(List<Registry.Listing> found, boolean dark) {
        searchResults.removeAllViews();
        if (found.isEmpty()) {
            searchState.setText(Prefs.of(host).getBoolean(Prefs.ALLOW_UNVERIFIED, false)
                    ? "Nothing found."
                    : "Nothing found among verified publishers.");
            return;
        }
        searchState.setText(found.size() + " found");
        LinearLayout list = Ui.column(host);
        list.setBackground(Ui.glass(host, dark, 20));
        List<String> present = Registry.installed(host);
        boolean first = true;
        for (Registry.Listing listing : found) {
            if (!first) list.addView(Ui.divider(host, dark, true));
            first = false;
            boolean here = Extensions.has(present, listing.id);
            StringBuilder value = new StringBuilder();
            value.append(listing.namespace);
            // Official is a stronger claim than verified and is kept separate from it. Verified
            // says Open VSX confirmed the publisher name has a real owner; official says the
            // publisher IS the company whose model the extension talks to.
            if (Agents.official(listing.namespace)) value.append(" · official");
            value.append(listing.verified ? " · verified" : " · UNVERIFIED");
            if (listing.downloads > 0) {
                value.append(" · ").append(shortCount(listing.downloads)).append(" downloads");
            }
            if (listing.rating >= 0) {
                value.append(" · ").append(String.format(java.util.Locale.ROOT, "%.1f", listing.rating))
                        .append("★");
            }
            Ui.Row row = Ui.row(host, dark,
                    here ? R.drawable.ic_check : R.drawable.ic_install,
                    listing.name, value.toString(), v -> onListingTapped(listing, here));
            if (here) row.setState(Ui.running(dark));
            else if (!listing.verified) row.setState(Ui.needsYou(dark));
            list.addView(row);
        }
        searchResults.addView(list);
    }

    private static String shortCount(long value) {
        if (value >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.1fM", value / 1e6);
        if (value >= 1_000) return String.format(java.util.Locale.ROOT, "%.0fK", value / 1e3);
        return String.valueOf(value);
    }

    private void onListingTapped(Registry.Listing listing, boolean installed) {
        if (!Workspace.installed(host)) {
            Dialogs.message(host, "Set up first",
                    "The editor has to be installed before an extension can go into it.");
            return;
        }
        if (installed) {
            Dialogs.confirm(host, "Remove " + listing.name + "?",
                    "The extension is removed from the editor.", "Remove", true,
                    () -> remove(listing.id));
            return;
        }
        String warning = listing.verified ? ""
                : "\n\nThis publisher is NOT verified. Open VSX marks a publisher name verified only "
                        + "when it has a real owner, and every counterfeit extension found on "
                        + "the registry in 2026 came from an unverified account.";
        Dialogs.confirm(host, "Install " + listing.name + "?",
                (listing.description == null || listing.description.isEmpty()
                        ? "" : listing.description + "\n\n")
                        + "Publisher: " + listing.namespace
                        + (listing.verified ? " (verified)" : " (not verified)")
                        + "\nVersion: " + listing.version
                        + warning,
                "Install", !listing.verified,
                () -> install(listing.namespace, listing.name, null, listing.name));
    }

    // ------------------------------------------------------------------ install

    /**
     * Downloads the .vsix, checks it against the checksum the registry publishes, and only then
     * hands it to the editor.
     *
     * Letting the editor fetch by identifier would be one line shorter and would skip the check
     * entirely. The check is the point: a registry compromised between listing and download --
     * which is precisely what the 2026 scanner-bypass bug allowed -- cannot put code into the
     * workspace if the bytes are compared against what was published.
     */
    private void install(String namespace, String name, String platform, String label) {
        // The app's own live dialog rather than a bare platform one: the same surface as every
        // other dialog here, the last lines of output as they happen, and a Done button at the
        // end -- the platform box had none and could only be dismissed by the code.
        final Dialogs.Live live = Dialogs.live(host, "Installing " + label,
                "Downloaded, checked against the registry's checksum, then handed to the editor.");
        new Thread(() -> {
            String failure = null;
            try {
                Registry.Listing listing = Registry.lookup(namespace, name, platform);
                if (listing.downloadUrl == null || listing.downloadUrl.isEmpty()) {
                    throw new IOException("The registry did not offer a download for this build.");
                }
                live.line("Downloading " + listing.version + "…");
                File vsix = new File(host.getCacheDir(), namespace + "." + name + ".vsix");
                fetch(listing.downloadUrl, vsix, live);

                live.line("Checking the download…");
                String published = Registry.publishedChecksum(listing.sha256Url);
                String actual = Workspace.checksum(vsix);
                if (!published.isEmpty() && !published.equalsIgnoreCase(actual)) {
                    // Refused and deleted. Keeping it would make a later attempt trust a file
                    // this one already decided not to.
                    vsix.delete();
                    throw new IOException("The download did not match the checksum Open VSX "
                            + "publishes for it, and was discarded.");
                }

                live.line("Installing into the editor…");
                StringBuilder output = new StringBuilder();
                int code = Workspace.run(host,
                        "bash /opt/pocketide/pocketide-editor.sh install-extension "
                                + namespace + "." + name + " " + vsix.getAbsolutePath(),
                        line -> {
                            output.append(line).append('\n');
                            live.line(line);
                        });
                vsix.delete();
                if (code != 0) throw new IOException(output.toString().trim());
                Registry.remember(host, namespace + "." + name, true);
            } catch (Throwable error) {
                failure = error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
            }
            final String reported = failure;
            host.runOnUiThread(() -> {
                if (host.isFinishing()) {
                    live.close();
                    return;
                }
                // What the editor has just changed is no longer what was read a moment ago.
                Extensions.forget();
                if (reported != null) {
                    live.close();
                    String advice = Trouble.advice(reported);
                    Dialogs.details(host, label + " was not installed",
                            advice != null ? advice
                                    : "Nothing was changed in the editor.",
                            reported, "Copy details");
                } else {
                    live.done(true, label + " is installed. Open the editor and it is in the "
                            + "side panel. Sign in there with your own account — this app never "
                            + "sees it.");
                }
                refreshRecommended();
                runSearch(searchBox.getText().toString());
            });
        }, "install-extension").start();
    }

    private void remove(String id) {
        new Thread(() -> {
            try {
                Workspace.run(host, "bash /opt/pocketide/pocketide-editor.sh remove-extension "
                        + id, null);
            } catch (IOException ignored) {
                // Removing something already gone is not a failure worth a dialog.
            }
            Registry.remember(host, id, false);
            Extensions.forget();
            host.runOnUiThread(() -> {
                if (host.isFinishing()) return;
                refreshRecommended();
                runSearch(searchBox.getText().toString());
            });
        }, "remove-extension").start();
    }

    private void fetch(String url, File target, Dialogs.Live live) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            throw new IOException("The registry answered " + status + " for the download.");
        }
        long total = connection.getContentLengthLong();
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536];
            long done = 0;
            long announced = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                done += read;
                if (done - announced > 2L * 1024 * 1024) {
                    announced = done;
                    live.line("Downloading… " + DeviceProbe.formatBytes(done)
                            + (total > 0 ? " of " + DeviceProbe.formatBytes(total) : ""));
                }
            }
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }
    }
}
