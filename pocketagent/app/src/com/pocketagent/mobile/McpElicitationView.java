package com.pocketagent.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Native fields only. The host owns secure dialogs, browser confirmation and final submission. */
final class McpElicitationView extends ScrollView {
    private final McpElicitation.Request request;
    private final Map<McpElicitation.Field, View> inputs = new LinkedHashMap<>();
    private final Map<McpElicitation.Field, ArrayList<CheckBox>> multi = new LinkedHashMap<>();

    McpElicitationView(Context context, JSONObject spec) {
        super(context); request = McpElicitation.parse(spec);
        setFillViewport(false); setClipToPadding(false);
        LinearLayout body = new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(16)); addView(body);
        label(body, "Requested by " + request.serverName, 13, DeskStyle.MUTED);
        label(body, request.message, 15, DeskStyle.TEXT);
        if (request.isUrl()) {
            label(body, "Browser destination", 13, DeskStyle.MUTED);
            TextView url = label(body, request.url, 15, DeskStyle.ACCENT); url.setTextIsSelectable(true);
            label(body, "Open this destination only if you trust it. Return here after the browser flow and confirm completion.", 13, DeskStyle.MUTED);
            return;
        }
        for (McpElicitation.Field field : request.fields) {
            label(body, field.title + (field.required ? " *" : " (optional)"), 15, DeskStyle.TEXT);
            if (!field.description.isEmpty()) label(body, field.description, 12, DeskStyle.MUTED);
            if ("array".equals(field.type)) {
                ArrayList<CheckBox> choices = new ArrayList<>();
                JSONArray defaults = field.defaultValue instanceof JSONArray ? (JSONArray) field.defaultValue : new JSONArray();
                for (int i = 0; i < field.choices.size(); i++) {
                    CheckBox choice = new CheckBox(context); choice.setText(field.choiceLabels.get(i)); choice.setTextColor(DeskStyle.TEXT);
                    choice.setButtonTintList(ColorStateList.valueOf(DeskStyle.ACCENT));
                    for (int j = 0; j < defaults.length(); j++) if (field.choices.get(i).equals(defaults.optString(j))) choice.setChecked(true);
                    body.addView(choice); choices.add(choice);
                }
                multi.put(field, choices);
            } else if ("boolean".equals(field.type) || !field.choices.isEmpty()) {
                Spinner spinner = new Spinner(context);
                ArrayList<String> labels = new ArrayList<>(); labels.add("Choose…");
                if ("boolean".equals(field.type)) { labels.add("Yes"); labels.add("No"); }
                else labels.addAll(field.choiceLabels);
                ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, labels) {
                    @Override public View getView(int position, View convert, android.view.ViewGroup parent) {
                        TextView text = (TextView) super.getView(position, convert, parent); text.setTextColor(DeskStyle.TEXT); text.setTextSize(15); return text;
                    }
                };
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter);
                spinner.setBackground(DeskStyle.field(context)); body.addView(spinner, new LinearLayout.LayoutParams(-1, dp(52)));
                if (field.defaultValue != null) {
                    if ("boolean".equals(field.type)) spinner.setSelection(Boolean.TRUE.equals(field.defaultValue) ? 1 : 2);
                    else spinner.setSelection(field.choices.indexOf(String.valueOf(field.defaultValue)) + 1);
                }
                inputs.put(field, spinner);
            } else {
                EditText input = new EditText(context); input.setTextColor(DeskStyle.TEXT); input.setHintTextColor(DeskStyle.MUTED);
                input.setTextSize(15); input.setBackground(DeskStyle.field(context)); input.setPadding(dp(14), dp(10), dp(14), dp(10));
                input.setMinHeight(dp(50)); input.setMaxLines(5);
                input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
                input.setPrivateImeOptions("nm");
                if ("integer".equals(field.type) || "number".equals(field.type)) {
                    input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | ("number".equals(field.type) ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
                    input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(140)});
                } else {
                    int kind = "email".equals(field.format) ? InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS : "uri".equals(field.format) ? InputType.TYPE_TEXT_VARIATION_URI : InputType.TYPE_TEXT_FLAG_MULTI_LINE;
                    input.setInputType(InputType.TYPE_CLASS_TEXT | kind | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                    input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(McpElicitation.MAX_STRING)});
                }
                if (field.defaultValue != null) input.setText(String.valueOf(field.defaultValue));
                body.addView(input, new LinearLayout.LayoutParams(-1, -2)); inputs.put(field, input);
            }
        }
    }

    boolean isUrl() { return request.isUrl(); }
    String url() { return request.url; }
    McpElicitation.Request request() { return request; }

    /** Raises a user-readable validation error. Hosts should keep the dialog open on failure. */
    JSONObject content() {
        JSONObject result = new JSONObject();
        try {
            for (McpElicitation.Field field : request.fields) {
                View view = inputs.get(field); Object value;
                if ("array".equals(field.type)) {
                    JSONArray choices = new JSONArray(); ArrayList<CheckBox> boxes = multi.get(field);
                    for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isChecked()) choices.put(field.choices.get(i));
                    if (choices.length() == 0 && !field.required) continue; value = choices;
                } else if (view instanceof Spinner) {
                    int selected = ((Spinner) view).getSelectedItemPosition();
                    if (selected == 0) { if (field.required) throw new IllegalArgumentException(field.title + " is required."); else continue; }
                    value = "boolean".equals(field.type) ? Boolean.valueOf(selected == 1) : field.choices.get(selected - 1);
                } else {
                    EditText input = (EditText) view; String text = input.getText().toString();
                    if (text.isEmpty() && !field.required) continue;
                    try { value = field.valueFromText(text); field.validate(value); }
                    catch (IllegalArgumentException error) { input.setError(error.getMessage()); input.requestFocus(); throw error; }
                }
                field.validate(value); result.put(field.name, value);
            }
        } catch (org.json.JSONException error) { throw new IllegalArgumentException("The form could not be read.", error); }
        return request.isUrl() ? result : AgentProtocol.child(request.response("accept", result), "content");
    }

    private TextView label(LinearLayout body, String text, int size, int color) {
        TextView label = new TextView(getContext()); label.setText(text); label.setTextSize(size); label.setTextColor(color);
        label.setPadding(0, dp(8), 0, dp(7)); body.addView(label, new LinearLayout.LayoutParams(-1, -2)); return label;
    }
    private int dp(int value) { return Ui.dp(getContext(), value); }
}
