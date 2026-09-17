package com.pocketide;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything the app has to say about itself: what it is for, how it fits together, the
 * questions people actually ask, the terms, the privacy position, and the licences of everything
 * it carries.
 *
 * It is one screen rather than five because the questions run into each other -- "is my code
 * uploaded" is a FAQ, a privacy statement and a term at the same time -- and because someone
 * deciding whether to trust this app should be able to read the whole case in one sitting.
 *
 * Answers open in place. A dialog per question would mean tapping twenty-eight times to read
 * the page.
 */
public final class HelpActivity extends Activity {

    private final Map<Integer, TextView> answers = new LinkedHashMap<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Theme.apply(this);
        setContentView(build());
    }

    /**
     * The phone's Dark theme flipped while this screen was on it.
     *
     * Every activity in this app declares uiMode in android:configChanges, so Android does NOT
     * recreate them for a theme change -- and nothing overrode this, so the app went on
     * painting the old palette while every other app on the phone flipped. It is a quick
     * settings tile, so it happens with the app fully on screen.
     *
     * Theme.apply() has to run too, not just the rebuild: setSystemBarsAppearance is sticky
     * per window, so a light-to-dark switch left the clock and battery dark on the app's own
     * near-black strip until the activity was recreated for some other reason.
     */
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Theme.apply(this);
        answers.clear();
        setContentView(build());
    }

    private View build() {
        boolean dark = Ui.dark(this);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.bg(dark));
        root.addView(Ui.topBar(this, dark, "About PocketIDE", v -> finish()));

        LinearLayout content = Ui.column(this);
        content.addView(hero(dark));
        content.addView(prose(dark, Texts.MISSION_TITLE, Texts.MISSION), Ui.wide(this, 16));
        content.addView(prose(dark, Texts.HOW_TITLE, Texts.HOW), Ui.wide(this, 16));
        content.addView(prose(dark, Texts.VISION_TITLE, Texts.VISION), Ui.wide(this, 16));
        content.addView(prose(dark, Texts.SURFACES_TITLE, Texts.SURFACES), Ui.wide(this, 16));
        content.addView(faq(dark), Ui.wide(this, 20));
        content.addView(permissionList(dark), Ui.wide(this, 20));
        content.addView(prose(dark, Texts.PRIVACY_TITLE,
                Texts.PRIVACY_SUMMARY + "\n\n" + Texts.PRIVACY), Ui.wide(this, 20));
        content.addView(prose(dark, Texts.TERMS_TITLE, Texts.TERMS), Ui.wide(this, 20));
        content.addView(notices(dark), Ui.wide(this, 20));

        ScrollView page = Ui.page(this, content, dark);
        root.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // The back bar was drawn under the clock and the page ran under the gesture bar, on
        // every phone, because only the main screen ever asked for insets. See Theme.fitScreen.
        Theme.fitScreen(root, page);
        return root;
    }

    private View hero(boolean dark) {
        LinearLayout card = Ui.column(this);
        int pad = Ui.dp(this, 20);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(Ui.metal(this, 24));
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.mipmap.ic_launcher_foreground);
        int size = Ui.dp(this, 72);
        card.addView(mark, new LinearLayout.LayoutParams(size, size));

        TextView name = Ui.bold(this, getString(R.string.app_name), 22, Brand.ON_BRAND);
        name.setGravity(Gravity.CENTER);
        card.addView(name, Ui.wide(this, 4));

        TextView tagline = Ui.text(this, getString(R.string.tagline), 14f, Brand.ON_BRAND);
        tagline.setGravity(Gravity.CENTER);
        card.addView(tagline, Ui.wide(this, 6));

        TextView version = Ui.text(this, BuildFacts.VERSION_NAME
                + " · Apache-2.0 · everything runs on this phone", 12f, Brand.ON_BRAND_MUTED);
        version.setGravity(Gravity.CENTER);
        card.addView(version, Ui.wide(this, 8));
        return card;
    }

    private View prose(boolean dark, String title, String body) {
        LinearLayout column = Ui.column(this);
        column.addView(Ui.sectionLabel(this, title, dark));
        LinearLayout card = Ui.card(this, dark);
        TextView text = Ui.text(this, body, 14f, Ui.muted(dark));
        // A block of prose needs a touch more leading than a label does, or the wrapped lines
        // close up and the paragraph reads as a wall.
        text.setLineSpacing(Ui.dp(this, 4), 1f);
        card.addView(text);
        column.addView(card, Ui.wide(this, 8));
        return column;
    }

    /** Grouped questions, each opening in place. */
    private View faq(boolean dark) {
        LinearLayout column = Ui.column(this);
        column.addView(Ui.sectionLabel(this, "Questions", dark));

        String currentGroup = "";
        LinearLayout list = null;
        for (int i = 0; i < Texts.FAQ.length; i++) {
            String group = Texts.FAQ[i][0];
            String question = Texts.FAQ[i][1];
            String answer = Texts.FAQ[i][2];
            if (!group.equals(currentGroup)) {
                currentGroup = group;
                if (list != null) column.addView(list, Ui.wide(this, 8));
                column.addView(Ui.text(this, group, 12.5f, Ui.muted(dark)), Ui.wide(this, 14));
                list = Ui.column(this);
                list.setBackground(Ui.glass(this, dark, 20));
            }
            if (list.getChildCount() > 0) list.addView(Ui.divider(this, dark, false));

            LinearLayout entry = Ui.column(this);
            final int index = i;
            Ui.Row row = Ui.row(this, dark, 0, question, null, v -> toggle(index));
            row.chevron.setVisibility(View.VISIBLE);
            entry.addView(row);
            TextView body = Ui.text(this, answer, 13.5f, Ui.muted(dark));
            body.setLineSpacing(Ui.dp(this, 3), 1f);
            int side = Ui.dp(this, 14);
            body.setPadding(side, 0, side, Ui.dp(this, 14));
            body.setVisibility(View.GONE);
            entry.addView(body);
            answers.put(index, body);
            list.addView(entry);
        }
        if (list != null) column.addView(list, Ui.wide(this, 8));
        return column;
    }

    private void toggle(int index) {
        TextView body = answers.get(index);
        if (body == null) return;
        body.setVisibility(body.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    /** What the app asks for, and — just as important — what it does not. */
    private View permissionList(boolean dark) {
        LinearLayout column = Ui.column(this);
        column.addView(Ui.sectionLabel(this, "What this app asks your phone for", dark));
        LinearLayout list = Ui.column(this);
        list.setBackground(Ui.glass(this, dark, 20));
        for (int i = 0; i < Texts.PERMISSIONS.length; i++) {
            if (i > 0) list.addView(Ui.divider(this, dark, true));
            list.addView(Ui.row(this, dark, R.drawable.ic_shield,
                    Texts.PERMISSIONS[i][1], Texts.PERMISSIONS[i][2], null));
        }
        column.addView(list, Ui.wide(this, 8));
        TextView not = Ui.text(this, Texts.NOT_REQUESTED, 12.5f, Ui.muted(dark));
        not.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        column.addView(not, Ui.wide(this, 10));
        return column;
    }

    /**
     * The licence notices, read straight out of the app's own source.
     *
     * The GPL-2.0 notice for the bundled PRoot has to reach whoever receives the APK, and the
     * APK is the only thing they receive -- so the text is a constant in Texts, compiled into
     * the app, rather than a file in a repository nobody installing this app will ever see.
     * It was an asset copied in by build.sh once, which meant a build that skipped the copy
     * shipped an app with no notices in it and nothing to say so.
     */
    private View notices(boolean dark) {
        LinearLayout column = Ui.column(this);
        column.addView(Ui.sectionLabel(this, Texts.NOTICES_TITLE, dark));
        LinearLayout card = Ui.card(this, dark);
        card.addView(Ui.text(this, Texts.NOTICES_SUMMARY, 13.5f, Ui.muted(dark)));
        TextView open = Ui.button(this, "Read the notices", false, dark);
        open.setOnClickListener(v -> Dialogs.details(this, Texts.NOTICES_TITLE, null,
                Texts.NOTICES, "Copy"));
        card.addView(open, Ui.wide(this, 14));
        column.addView(card, Ui.wide(this, 8));
        return column;
    }
}
