package com.pocketagent.doors;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * The screen that answers "why only three?" before anyone has to ask.
 *
 * A short list of agents reads as an unfinished app unless the reason is visible. So the reason
 * is here, with the figures that produced it and where each one came from, and with a plain
 * statement that this is not a ranking of anybody's models.
 */
public final class ReasonsActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Why these three");
        boolean dark = Ui.dark(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.bg(dark));
        LinearLayout root = Ui.column(this);
        int side = Ui.dp(this, 20);
        root.setPadding(side, Ui.dp(this, 26), side, Ui.dp(this, 36));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        root.addView(Ui.bold(this, "Why these three", 24, Ui.text(dark)));
        root.addView(Ui.text(this,
                "Not a shortlist of favourites. These are the makers who publish everything this "
                        + "app needs to run their agent on a phone, with their own interface.",
                15, Ui.muted(dark)), Ui.wide(this, 8));

        for (Reasons.Lab lab : Reasons.LABS) {
            root.addView(labCard(lab, dark), Ui.wide(this, 16));
        }

        root.addView(heading("What it takes to be on this list", dark), Ui.wide(this, 28));
        root.addView(Ui.text(this,
                "Three things, and all three are needed. Two out of three leaves nothing honest to "
                        + "put on the screen.", 14.5f, Ui.muted(dark)), Ui.wide(this, 6));
        int n = 1;
        for (String rule : Reasons.BAR) {
            root.addView(bullet(n++ + ".", rule, dark), Ui.wide(this, 10));
        }

        root.addView(heading("Who that leaves out today", dark), Ui.wide(this, 28));
        for (Reasons.Missing missing : Reasons.NOT_HERE) {
            root.addView(missingCard(missing, dark), Ui.wide(this, 14));
        }

        root.addView(Ui.text(this, Reasons.NOT_A_VERDICT, 14.5f, Ui.text(dark)), Ui.wide(this, 24));

        root.addView(heading("Where the figures came from", dark), Ui.wide(this, 26));
        root.addView(Ui.text(this, Reasons.SOURCES, 13, Ui.muted(dark)), Ui.wide(this, 6));
    }

    private android.widget.TextView heading(String words, boolean dark) {
        return Ui.bold(this, words, 18, Ui.text(dark));
    }

    private LinearLayout labCard(Reasons.Lab lab, boolean dark) {
        LinearLayout card = Ui.column(this);
        int pad = Ui.dp(this, 16);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(Ui.fill(this, Ui.card(dark), 12));
        card.addView(Ui.bold(this, lab.name, 18, Ui.text(dark)));
        card.addView(row("Compute", lab.compute, dark), Ui.wide(this, 10));
        card.addView(row("Reach", lab.reach, dark), Ui.wide(this, 10));
        card.addView(row("Here", lab.agent, dark), Ui.wide(this, 10));
        return card;
    }

    private LinearLayout missingCard(Reasons.Missing missing, boolean dark) {
        LinearLayout card = Ui.column(this);
        int pad = Ui.dp(this, 14);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(Ui.outlined(this, Ui.bg(dark), Ui.line(dark), 12));
        card.addView(Ui.bold(this, missing.name, 16, Ui.text(dark)));
        card.addView(row("Has", missing.has, dark), Ui.wide(this, 9));
        card.addView(row("Missing", missing.lacks, dark), Ui.wide(this, 9));
        return card;
    }

    /** A small label above a line of prose; the label is what makes three cards comparable. */
    private LinearLayout row(String label, String words, boolean dark) {
        LinearLayout holder = Ui.column(this);
        android.widget.TextView tag = Ui.mono(this, label.toUpperCase(java.util.Locale.US),
                11, Ui.muted(dark));
        tag.setLetterSpacing(0.09f);
        holder.addView(tag);
        holder.addView(Ui.text(this, words, 14.5f, Ui.text(dark)), Ui.wide(this, 3));
        return holder;
    }

    private LinearLayout bullet(String number, String words, boolean dark) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.TextView index = Ui.mono(this, number, 14, dark ? Brand.ACCENT_ON_DARK : Brand.TILE_FLAT);
        LinearLayout.LayoutParams indexLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 24), ViewGroup.LayoutParams.WRAP_CONTENT);
        line.addView(index, indexLp);
        line.addView(Ui.text(this, words, 14.5f, Ui.text(dark)), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return line;
    }
}
