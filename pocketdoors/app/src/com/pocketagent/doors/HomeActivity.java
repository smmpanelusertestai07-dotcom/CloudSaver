package com.pocketagent.doors;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The list of agents, and the one honest sentence about each.
 *
 * This is the only screen PocketAgent draws for itself. It says how an agent is reached, what
 * its maker charges, what is known to be missing, and -- once anyone has actually opened it on
 * this phone -- whether it worked. Nothing is claimed that has not happened; an agent nobody
 * has started here says so.
 *
 * There is one route per agent and no choice to make, so a card is a name and a button.
 */
public final class HomeActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DoorService.ensureChannel(this);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        boolean dark = Ui.dark(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.bg(dark));
        scroll.setFillViewport(true);
        LinearLayout root = Ui.column(this);
        int side = Ui.dp(this, 20);
        root.setPadding(side, Ui.dp(this, 28), side, Ui.dp(this, 40));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        // Header: the app's own icon, the same one on the home screen.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.pocketagent_icon);
        mark.setContentDescription("PocketAgent");
        header.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        LinearLayout titles = Ui.column(this);
        LinearLayout.LayoutParams titlesLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titlesLp.setMarginStart(Ui.dp(this, 12));
        header.addView(titles, titlesLp);
        TextView name = Ui.bold(this, "PocketAgent", 22, Ui.text(dark));
        name.setLetterSpacing(-0.015f);
        titles.addView(name);
        titles.addView(Ui.text(this, "AI agents that build real software on your phone",
                12.5f, Ui.muted(dark)));
        root.addView(header);

        // Whose agents, by name, under the app's own name rather than inside it.
        //
        // Putting "Claude", "Codex" or "Antigravity" in the app's name is not allowed -- each of
        // the three asks that its mark not become part of somebody else's product name -- and it
        // would read as an official app, which this is not. Naming them here is the opposite
        // claim: this is what it runs, said plainly, so anyone can see what they are getting
        // before they spend a gigabyte of mobile data finding out.
        TextView runs = Ui.mono(this, "CLAUDE CODE  ·  CODEX  ·  ANTIGRAVITY", 11,
                dark ? Brand.ACCENT_ON_DARK : Brand.TILE_FLAT);
        runs.setLetterSpacing(0.06f);
        root.addView(runs, Ui.wide(this, 14));

        if (!Ubuntu.installed(this)) {
            root.addView(setupCard(dark), Ui.wide(this, 22));
            return;
        }

        root.addView(Ui.text(this,
                "One workspace on this phone, and one way into each agent -- the way its own "
                        + "maker supports best. No options to weigh up first.",
                14, Ui.muted(dark)), Ui.wide(this, 20));

        root.addView(Ui.sectionLabel(this, "Agents", dark), Ui.wide(this, 22));
        String running = DoorService.runningAgent();
        for (Doors.Agent agent : Doors.ALL) {
            root.addView(agentCard(agent, dark, agent.id.equals(running)), Ui.wide(this, 14));
        }

        if (running != null) {
            TextView stop = Ui.button(this, "Stop the running agent", false, dark);
            stop.setOnClickListener(v -> { DoorService.stop(this); v.postDelayed(this::render, 600); });
            root.addView(stop, Ui.wide(this, 20));
        }

        root.addView(Ui.text(this,
                "One agent at a time. This phone has less memory than a second one would need.",
                12.5f, Ui.muted(dark)), Ui.wide(this, 18));

        // Three agents looks like an unfinished app until the reason is somewhere to be read.
        android.widget.TextView why = Ui.button(this, "Why these three", false, dark);
        why.setOnClickListener(v -> startActivity(new Intent(this, ReasonsActivity.class)));
        root.addView(why, Ui.wide(this, 14));
    }

    private View setupCard(boolean dark) {
        LinearLayout card = Ui.card(this, dark);
        card.addView(Ui.sectionLabel(this, "First run", dark));
        card.addView(Ui.bold(this, "Set up the workspace", 18, Ui.text(dark)), Ui.wide(this, 4));
        card.addView(Ui.text(this,
                "Ubuntu " + Ubuntu.IMAGE_LABEL.replace("Ubuntu ", "") + ", about 30 MB to download "
                        + "and roughly 1.2 GB once the agents are installed. It lives inside this "
                        + "app and goes when the app goes.", 14, Ui.muted(dark)), Ui.wide(this, 8));
        TextView go = Ui.button(this, "Set up", true, dark);
        go.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));
        card.addView(go, Ui.wide(this, 16));
        return card;
    }

    private View agentCard(Doors.Agent agent, boolean dark, boolean running) {
        LinearLayout card = Ui.card(this, dark);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.bold(this, agent.name, 17.5f, Ui.text(dark));
        top.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(statePill(agent, running));
        card.addView(top);

        // One line about how this agent is reached, not a menu of ways to reach it.
        card.addView(Ui.text(this, routeOf(agent), 12.5f, Ui.muted(dark)), Ui.wide(this, 4));
        card.addView(Ui.text(this, agent.cost, 13, agent.free ? Ui.RUNNING : Ui.muted(dark)),
                Ui.wide(this, 6));

        if (!agent.limit.isEmpty()) {
            card.addView(Ui.text(this, agent.limit, 12.5f, Ui.muted(dark)), Ui.wide(this, 8));
        }

        String failure = Probe.FAILED.equals(Probe.state(this, agent.id))
                ? Probe.detail(this, agent.id) : "";
        if (!failure.isEmpty()) {
            TextView words = Ui.mono(this, failure.trim(), 11.5f, Ui.muted(dark));
            int inner = Ui.dp(this, 10);
            words.setPadding(inner, inner, inner, inner);
            words.setBackground(Ui.glass(this, dark, 10));
            card.addView(words, Ui.wide(this, 10));
        }

        TextView open = Ui.button(this, running ? "Open" : "Start " + agent.name, !running, dark);
        open.setOnClickListener(v -> startActivity(
                new Intent(this, DoorActivity.class).putExtra(DoorActivity.EXTRA_AGENT, agent.id)));
        card.addView(open, Ui.wide(this, 14));
        return card;
    }

    /**
     * How this agent arrives, in one line.
     *
     * Two of the three are their maker's own extension; the third is the editor those extensions
     * install into. Saying which is not a detail -- it is the whole reason there is one window.
     */
    private String routeOf(Doors.Agent agent) {
        return agent.isExtension()
                ? "Their own extension, in the editor on this phone"
                : "The editor on this phone, from Google's own repository";
    }

    private TextView statePill(Doors.Agent agent, boolean running) {
        if (running) return Ui.pill(this, "running", Ui.RUNNING);
        String state = Probe.state(this, agent.id);
        if (Probe.WORKS.equals(state)) return Ui.pill(this, "works here", Ui.RUNNING);
        if (Probe.FAILED.equals(state)) return Ui.pill(this, "failed here", Ui.FAILED);
        return Ui.pill(this, "untried", Ui.NEEDS_YOU);
    }
}
