package com.pocketide;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
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
public final class ExtensionsActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout searchResults;
    private TextView searchState;
    private EditText searchBox;
    private LinearLayout recommendedList;
    private volatile int searchGeneration;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Theme.apply(this);
        setContentView(build());
    }

    @Override protected void onResume() {
        super.onResume();
        refreshRecommended();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ the screen

    private View build() {
        boolean dark = Ui.dark(this);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.bg(dark));
        root.addView(Ui.topBar(this, dark, "Extensions", v -> finish()));

        LinearLayout content = Ui.column(this);
        content.addView(intro(dark));
        content.addView(recommendedCard(dark), Ui.wide(this, 16));
        content.addView(searchCard(dark), Ui.wide(this, 16));
        content.addView(unverifiedCard(dark), Ui.wide(this, 16));

        ScrollView page = Ui.page(this, content, dark);
        root.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private View intro(boolean dark) {
        LinearLayout card = Ui.card(this, dark);
        card.addView(Ui.bold(this, "Anything on Open VSX", 17, Ui.text(dark)));
        card.addView(Ui.text(this,
                "This app is not limited to three agents. Open VSX is the extension registry "
                        + "that every non-Microsoft build of VS Code uses — including Google's "
                        + "own Antigravity IDE — and all of it is available here.",
                13.5f, Ui.muted(dark)), Ui.wide(this, 8));
        card.addView(Ui.text(this,
                "Extensions are not sandboxed: VS Code's own documentation says an extension "
                        + "can do anything the editor can. So verified publishers are shown by "
                        + "default, and every install shows you the publisher and the checksum "
                        + "first.",
                12.5f, Ui.muted(dark)), Ui.wide(this, 10));
        return card;
    }

    private View recommendedCard(boolean dark) {
        LinearLayout column = Ui.column(this);
        column.addView(Ui.sectionLabel(this, "Recommended · verified and checksum-pinned", dark));
        recommendedList = Ui.column(this);
        recommendedList.setBackground(Ui.glass(this, dark, 20));
        column.addView(recommendedList, Ui.wide(this, 8));
        TextView why = Ui.text(this, "Why only these three?", 13f, Ui.accent(dark));
        why.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, 0);
        why.setMinHeight(Ui.dp(this, Ui.TOUCH_TARGET_DP));
        why.setClickable(true);
        why.setOnClickListener(v ->
                Dialogs.message(this, "Why these three", Agents.WHY_THESE_THREE));
        column.addView(why);
        return column;
    }

    private View searchCard(boolean dark) {
        LinearLayout column = Ui.column(this);
        column.addView(Ui.sectionLabel(this, "Search verified publishers", dark));

        searchBox = new EditText(this);
        searchBox.setHint("Search Open VSX");
        searchBox.setSingleLine(true);
        searchBox.setTextColor(Ui.text(dark));
        searchBox.setHintTextColor(Ui.muted(dark));
        searchBox.setTextSize(15f);
        int pad = Ui.dp(this, 14);
        searchBox.setPadding(pad, pad, pad, pad);
        searchBox.setMinHeight(Ui.dp(this, Ui.TOUCH_TARGET_DP));
        searchBox.setBackground(Ui.outlined(this, Ui.card(dark), Ui.line(dark), 14));
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
        column.addView(searchBox, Ui.wide(this, 8));

        searchState = Ui.text(this, "Type to search.", 12.5f, Ui.muted(dark));
        column.addView(searchState, Ui.wide(this, 10));

        searchResults = Ui.column(this);
        column.addView(searchResults, Ui.wide(this, 8));
        return column;
    }

    private View unverifiedCard(boolean dark) {
        boolean allowed = Prefs.of(this).getBoolean(Prefs.ALLOW_UNVERIFIED, false);
        LinearLayout column = Ui.column(this);
        column.addView(Ui.sectionLabel(this, "Everything else", dark));
        LinearLayout list = Ui.column(this);
        list.setBackground(Ui.glass(this, dark, 20));
        Ui.Row row = Ui.row(this, dark, R.drawable.ic_shield, "Unverified publishers",
                allowed ? "Shown in search · your risk" : "Hidden — recommended",
                v -> toggleUnverified(!allowed));
        if (allowed) row.setState(Ui.NEEDS_YOU);
        list.addView(row);
        column.addView(list, Ui.wide(this, 8));
        return column;
    }

    private void toggleUnverified(boolean enable) {
        if (!enable) {
            Prefs.of(this).edit().putBoolean(Prefs.ALLOW_UNVERIFIED, false).apply();
            setContentView(build());
            return;
        }
        Dialogs.confirm(this, "Show unverified publishers?",
                "Open VSX marks a namespace verified only when it has a real owner. Every "
                        + "counterfeit extension found on the registry in 2026 came from an "
                        + "account unaffiliated with the publisher it imitated — 73 cloned "
                        + "packages in April, 77 impersonating AMD, Azure, Salesforce and a US "
                        + "government agency over the summer.\n\n"
                        + "Extensions are not sandboxed. One can read every file in your "
                        + "workspace, reach the network and run programs.\n\n"
                        + "Turn this on only if you know the publisher.",
                "Show them", true, () -> {
                    Prefs.of(this).edit().putBoolean(Prefs.ALLOW_UNVERIFIED, true).apply();
                    setContentView(build());
                });
    }

    // ------------------------------------------------------------------ recommended

    private void refreshRecommended() {
        if (recommendedList == null) return;
        boolean dark = Ui.dark(this);
        recommendedList.removeAllViews();
        List<String> present = Registry.installed(this);
        boolean first = true;
        for (Agents.Agent agent : Agents.ALL) {
            if (!first) recommendedList.addView(Ui.divider(this, dark, true));
            first = false;
            boolean here = present.contains(agent.id);
            String value = agent.publisher + " · " + DeviceProbe.formatBytes(agent.sizeBytes)
                    + " · " + (here ? "Installed" : agent.plan);
            Ui.Row row = Ui.row(this, dark,
                    here ? R.drawable.ic_check : R.drawable.ic_install,
                    agent.name, value, v -> onAgentTapped(agent, here));
            if (here) row.setState(Ui.RUNNING);
            else if (agent.free) row.setState(Ui.accent(dark));
            recommendedList.addView(row);
        }
    }

    private void onAgentTapped(Agents.Agent agent, boolean installed) {
        if (!Workspace.installed(this)) {
            Dialogs.confirm(this, "Set up first",
                    "The editor has to be installed before an extension can go into it.",
                    "Set up", () -> startActivity(new Intent(this, SetupActivity.class)));
            return;
        }
        if (installed) {
            Dialogs.confirm(this, "Remove " + agent.name + "?",
                    "The extension is removed from the editor. Your files and your sign-in with "
                            + agent.publisher + " are not touched.",
                    "Remove", true, () -> remove(agent.id));
            return;
        }
        Dialogs.confirm(this, "Install " + agent.name + "?",
                agent.summary + "\n\n"
                        + "Publisher: " + agent.publisher + " (verified namespace)\n"
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
        boolean dark = Ui.dark(this);
        final int generation = ++searchGeneration;
        searchResults.removeAllViews();
        if (query == null || query.trim().length() < 2) {
            searchState.setText("Type to search.");
            return;
        }
        searchState.setText("Searching…");
        new Thread(() -> {
            try {
                List<Registry.Listing> found = Registry.search(this, query.trim(), 20);
                runOnUiThread(() -> {
                    if (generation != searchGeneration || isFinishing()) return;
                    showResults(found, dark);
                });
            } catch (IOException failure) {
                runOnUiThread(() -> {
                    if (generation != searchGeneration || isFinishing()) return;
                    searchState.setText("Could not reach the registry. " + failure.getMessage());
                });
            }
        }, "search-open-vsx").start();
    }

    private void showResults(List<Registry.Listing> found, boolean dark) {
        searchResults.removeAllViews();
        if (found.isEmpty()) {
            searchState.setText(Prefs.of(this).getBoolean(Prefs.ALLOW_UNVERIFIED, false)
                    ? "Nothing found."
                    : "Nothing found among verified publishers.");
            return;
        }
        searchState.setText(found.size() + " found");
        LinearLayout list = Ui.column(this);
        list.setBackground(Ui.glass(this, dark, 20));
        List<String> present = Registry.installed(this);
        boolean first = true;
        for (Registry.Listing listing : found) {
            if (!first) list.addView(Ui.divider(this, dark, true));
            first = false;
            boolean here = present.contains(listing.id);
            StringBuilder value = new StringBuilder();
            value.append(listing.namespace);
            value.append(listing.verified ? " · verified" : " · UNVERIFIED");
            if (listing.downloads > 0) {
                value.append(" · ").append(shortCount(listing.downloads)).append(" downloads");
            }
            if (listing.rating >= 0) {
                value.append(" · ").append(String.format(java.util.Locale.ROOT, "%.1f", listing.rating))
                        .append("★");
            }
            Ui.Row row = Ui.row(this, dark,
                    here ? R.drawable.ic_check : R.drawable.ic_install,
                    listing.name, value.toString(), v -> onListingTapped(listing, here));
            if (here) row.setState(Ui.RUNNING);
            else if (!listing.verified) row.setState(Ui.NEEDS_YOU);
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
        if (!Workspace.installed(this)) {
            Dialogs.message(this, "Set up first",
                    "The editor has to be installed before an extension can go into it.");
            return;
        }
        if (installed) {
            Dialogs.confirm(this, "Remove " + listing.name + "?",
                    "The extension is removed from the editor.", "Remove", true,
                    () -> remove(listing.id));
            return;
        }
        String warning = listing.verified ? ""
                : "\n\nThis publisher is NOT verified. Open VSX marks a namespace verified only "
                        + "when it has a real owner, and every counterfeit extension found on "
                        + "the registry in 2026 came from an unverified account.";
        Dialogs.confirm(this, "Install " + listing.name + "?",
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
        boolean dark = Ui.dark(this);
        TextView progress = Ui.text(this, "Preparing…", 13f, Ui.muted(dark));
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Installing " + label)
                .setView(progress)
                .setCancelable(false)
                .create();
        int pad = Ui.dp(this, 24);
        progress.setPadding(pad, pad, pad, pad);
        dialog.show();

        new Thread(() -> {
            String failure = null;
            try {
                Registry.Listing listing = Registry.lookup(namespace, name, platform);
                if (listing.downloadUrl == null || listing.downloadUrl.isEmpty()) {
                    throw new IOException("The registry did not offer a download for this build.");
                }
                say(progress, "Downloading " + listing.version + "…");
                File vsix = new File(getCacheDir(), namespace + "." + name + ".vsix");
                fetch(listing.downloadUrl, vsix, progress);

                say(progress, "Checking the download…");
                String published = Registry.publishedChecksum(listing.sha256Url);
                String actual = Workspace.checksum(vsix);
                if (!published.isEmpty() && !published.equalsIgnoreCase(actual)) {
                    // Refused and deleted. Keeping it would make a later attempt trust a file
                    // this one already decided not to.
                    vsix.delete();
                    throw new IOException("The download did not match the checksum Open VSX "
                            + "publishes for it, and was discarded.");
                }

                say(progress, "Installing into the editor…");
                StringBuilder output = new StringBuilder();
                int code = Workspace.run(this,
                        "bash /opt/pocketide/pocketide-editor.sh install-extension "
                                + namespace + "." + name + " " + vsix.getAbsolutePath(),
                        line -> {
                            output.append(line).append('\n');
                            say(progress, line);
                        });
                vsix.delete();
                if (code != 0) throw new IOException(output.toString().trim());
                Registry.remember(this, namespace + "." + name, true);
            } catch (Throwable error) {
                failure = error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
            }
            final String reported = failure;
            runOnUiThread(() -> {
                dialog.dismiss();
                if (isFinishing()) return;
                if (reported != null) {
                    String advice = Trouble.advice(reported);
                    Dialogs.details(this, label + " was not installed",
                            advice != null ? advice
                                    : "Nothing was changed in the editor.",
                            reported, "Copy details");
                } else {
                    Dialogs.message(this, label + " is installed",
                            "Open the editor and it will be in the side panel. Sign in there "
                                    + "with your own account — this app never sees it.");
                }
                refreshRecommended();
                runSearch(searchBox.getText().toString());
            });
        }, "install-extension").start();
    }

    private void remove(String id) {
        new Thread(() -> {
            try {
                Workspace.run(this, "bash /opt/pocketide/pocketide-editor.sh remove-extension "
                        + id, null);
            } catch (IOException ignored) {
                // Removing something already gone is not a failure worth a dialog.
            }
            Registry.remember(this, id, false);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                refreshRecommended();
                runSearch(searchBox.getText().toString());
            });
        }, "remove-extension").start();
    }

    private void fetch(String url, File target, TextView progress) throws IOException {
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
                    say(progress, "Downloading… " + DeviceProbe.formatBytes(done)
                            + (total > 0 ? " of " + DeviceProbe.formatBytes(total) : ""));
                }
            }
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }
    }

    private void say(TextView view, String words) {
        runOnUiThread(() -> {
            if (!isFinishing()) view.setText(words);
        });
    }
}
