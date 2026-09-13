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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
     * The licence notices, read from the file the build copies into the APK.
     *
     * The GPL-2.0 notice for the bundled PRoot has to reach whoever receives the APK, and the
     * APK is the only thing they receive — so it ships inside it rather than living in a
     * repository nobody installing this app will ever see.
     */
    private View notices(boolean dark) {
        LinearLayout column = Ui.column(this);
        column.addView(Ui.sectionLabel(this, "Open-source notices", dark));
        LinearLayout card = Ui.card(this, dark);
        card.addView(Ui.text(this,
                "This app carries software written by other people, under their licences. "
                        + "PocketIDE's own code is Apache-2.0.", 13.5f, Ui.muted(dark)));
        TextView open = Ui.button(this, "Read the notices", false, dark);
        open.setOnClickListener(v -> Dialogs.details(this, "Open-source notices", null,
                readAsset("open-source-notices.md"), "Copy"));
        card.addView(open, Ui.wide(this, 14));
        column.addView(card, Ui.wide(this, 8));
        return column;
    }

    private String readAsset(String name) {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
        } catch (IOException missing) {
            return "The notices file is missing from this build, which is a packaging fault. "
                    + "The licences still apply: PRoot is GPL-2.0, code-server and Code-OSS are "
                    + "MIT, Ubuntu's packages carry their own, and each extension carries its "
                    + "publisher's.";
        }
        return text.toString();
    }
}
