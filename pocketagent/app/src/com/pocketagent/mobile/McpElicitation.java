package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Bounded MCP form validation. This class does not authenticate, open URLs or grant approval. */
final class McpElicitation {
    static final int MAX_FIELDS = 20, MAX_OPTIONS = 40, MAX_STRING = 4096;
    private McpElicitation() {}

    static final class UnsupportedRequest extends IllegalArgumentException {
        UnsupportedRequest(String message) { super(message); }
    }

    static final class Request {
        final String mode, serverName, message, url;
        final List<Field> fields;
        private final JSONObject source;
        Request(String mode, String server, String message, String url, List<Field> fields, JSONObject source) {
            this.mode = mode; this.serverName = server; this.message = message; this.url = url;
            this.fields = Collections.unmodifiableList(fields); this.source = source;
        }
        boolean isUrl() { return "url".equals(mode); }
        JSONObject toJson() { return copy(source); }

        /** Decline and cancel always discard data; opening a URL alone is not acceptance. */
        JSONObject response(String action, JSONObject content) {
            if ("decline".equals(action) || "cancel".equals(action)) return object("action", action);
            if (!"accept".equals(action)) throw new IllegalArgumentException("Choose Accept, Decline or Cancel.");
            if (isUrl()) {
                if (content != null && content.length() != 0) throw new IllegalArgumentException("A browser request does not accept form data.");
                return object("action", "accept");
            }
            JSONObject values = content == null ? new JSONObject() : content;
            Set<String> names = new HashSet<>(); for (Field field : fields) names.add(field.name);
            Iterator<String> keys = values.keys();
            while (keys.hasNext()) if (!names.contains(keys.next())) throw new IllegalArgumentException("The form contains an unexpected field.");
            JSONObject accepted = new JSONObject();
            try {
                for (Field field : fields) {
                    if (!values.has(field.name) || values.isNull(field.name)) {
                        if (field.required) throw new IllegalArgumentException(field.title + " is required.");
                        continue;
                    }
                    Object value = values.opt(field.name); field.validate(value); accepted.put(field.name, value);
                }
            } catch (org.json.JSONException invalid) { throw new IllegalArgumentException("The form could not be encoded.", invalid); }
            return object("action", "accept", "content", accepted);
        }
    }

    static final class Field {
        final String name, title, description, type, format;
        final boolean required;
        final int minLength, maxLength, minItems, maxItems;
        final BigDecimal minimum, maximum;
        final List<String> choices, choiceLabels;
        final Object defaultValue;
        Field(String name, JSONObject schema, boolean required) {
            this.name = name; this.required = required;
            type = exactString(schema, "type", true, 20);
            title = optionalString(schema, "title", name, 180);
            description = optionalString(schema, "description", "", 1200);
            format = optionalString(schema, "format", "", 30);
            if (sensitive(name + " " + title + " " + description, type))
                throw unsupported("This form asks for a credential or secret. Use the service's official browser sign-in instead.");
            if (!set("string", "integer", "number", "boolean", "array").contains(type))
                throw unsupported("This form uses a nested or unsupported field type: " + type);
            Set<String> allowed = set("type", "title", "description", "default");
            if ("string".equals(type)) Collections.addAll(allowed, "format", "minLength", "maxLength", "enum", "enumNames", "oneOf");
            if ("integer".equals(type) || "number".equals(type)) Collections.addAll(allowed, "minimum", "maximum");
            if ("array".equals(type)) Collections.addAll(allowed, "items", "minItems", "maxItems");
            checkKeys(schema, allowed, "field " + name);
            minLength = bound(schema, "minLength", 0, MAX_STRING);
            maxLength = bound(schema, "maxLength", MAX_STRING, MAX_STRING);
            minItems = bound(schema, "minItems", 0, MAX_OPTIONS);
            maxItems = bound(schema, "maxItems", MAX_OPTIONS, MAX_OPTIONS);
            if (minLength > maxLength || minItems > maxItems) throw unsupported("This form has contradictory field limits.");
            minimum = decimalBound(schema, "minimum"); maximum = decimalBound(schema, "maximum");
            if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) throw unsupported("This form has contradictory number limits.");
            if (!format.isEmpty() && !set("email", "uri", "date", "date-time").contains(format)) throw unsupported("This form uses an unsupported text format.");
            ArrayList<String> values = new ArrayList<>(), labels = new ArrayList<>();
            JSONObject options = schema;
            String titled = "oneOf";
            if ("array".equals(type)) {
                options = schema.optJSONObject("items");
                if (options == null) throw unsupported("Only arrays of predefined choices are supported.");
                checkKeys(options, set("type", "enum", "anyOf"), "choice items");
                if (options.has("enum") && !"string".equals(options.optString("type"))) throw unsupported("Choice values must be strings.");
                titled = "anyOf";
            }
            JSONArray enumeration = options.optJSONArray("enum"), titledOptions = options.optJSONArray(titled);
            if (enumeration != null && titledOptions != null) throw unsupported("This form declares conflicting choice lists.");
            if (options.has("enum") && enumeration == null) throw unsupported("This form contains an invalid choice list.");
            if (options.has(titled) && titledOptions == null) throw unsupported("This form contains invalid titled choices.");
            JSONArray names = schema.optJSONArray("enumNames");
            if (names != null && (enumeration == null || names.length() != enumeration.length())) throw unsupported("Choice labels do not match their values.");
            int count = enumeration != null ? enumeration.length() : titledOptions != null ? titledOptions.length() : 0;
            if ((enumeration != null || titledOptions != null || "array".equals(type)) && (count == 0 || count > MAX_OPTIONS)) throw unsupported("This form has too many or no choices.");
            for (int i = 0; i < count; i++) {
                String value, label;
                if (enumeration != null) {
                    value = stringValue(enumeration.opt(i), "Choice", 512);
                    label = names == null ? value : stringValue(names.opt(i), "Choice label", 180);
                } else {
                    JSONObject option = titledOptions.optJSONObject(i);
                    if (option == null) throw unsupported("A choice is not an object.");
                    checkKeys(option, set("const", "title"), "choice");
                    value = exactString(option, "const", true, 512); label = exactString(option, "title", true, 180);
                }
                if (values.contains(value)) throw unsupported("This form contains duplicate choices.");
                values.add(value); labels.add(label);
            }
            choices = Collections.unmodifiableList(values); choiceLabels = Collections.unmodifiableList(labels);
            defaultValue = schema.isNull("default") ? null : schema.opt("default");
            if (defaultValue != null) {
                try { validate(defaultValue); }
                catch (IllegalArgumentException error) { throw unsupported("This form has an invalid default value: " + title); }
            }
        }

        Object valueFromText(String text) {
            String value = text == null ? "" : text;
            if ("string".equals(type)) return value;
            if ("number".equals(type) || "integer".equals(type)) {
                try {
                    String number = value.trim();
                    if (!number.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]{1,3})?")) throw new NumberFormatException();
                    BigDecimal parsed = new BigDecimal(number); validate(parsed); return parsed;
                } catch (NumberFormatException error) { throw invalid("Enter a valid number."); }
            }
            throw invalid("Choose a value from the form.");
        }

        void validate(Object value) {
            if ("string".equals(type)) {
                if (!(value instanceof String)) throw invalid("Enter text.");
                String text = (String) value; int length = text.codePointCount(0, text.length());
                if (length < minLength || length > maxLength) throw invalid("Use " + minLength + "–" + maxLength + " characters.");
                if (hasControls(text)) throw invalid("Control characters are not accepted.");
                if (!choices.isEmpty() && !choices.contains(text)) throw invalid("Choose one of the listed values.");
                validateFormat(text);
            } else if ("boolean".equals(type)) {
                if (!(value instanceof Boolean)) throw invalid("Choose Yes or No.");
            } else if ("array".equals(type)) {
                if (!(value instanceof JSONArray)) throw invalid("Select values from the list.");
                JSONArray selected = (JSONArray) value;
                if (selected.length() < minItems || selected.length() > maxItems) throw invalid("Select " + minItems + "–" + maxItems + " choices.");
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < selected.length(); i++) {
                    Object item = selected.opt(i);
                    if (!(item instanceof String) || !choices.contains(item) || !seen.add((String) item)) throw invalid("Choose unique values from the list.");
                }
            } else {
                if (!(value instanceof Number)) throw invalid("Enter a number.");
                BigDecimal number;
                try { number = new BigDecimal(value.toString()); }
                catch (NumberFormatException error) { throw invalid("Enter a finite number."); }
                if (number.precision() > 40 || Math.abs((long) number.scale()) > 100) throw invalid("This number is too large or too precise.");
                if ("integer".equals(type) && number.stripTrailingZeros().scale() > 0) throw invalid("Enter a whole number.");
                if (minimum != null && number.compareTo(minimum) < 0) throw invalid("The minimum is " + minimum.toPlainString() + ".");
                if (maximum != null && number.compareTo(maximum) > 0) throw invalid("The maximum is " + maximum.toPlainString() + ".");
            }
        }

        private void validateFormat(String value) {
            try {
                if ("email".equals(format) && (!value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || value.length() > 254)) throw new IllegalArgumentException();
                if ("uri".equals(format) && !new URI(value).isAbsolute()) throw new IllegalArgumentException();
                if ("date".equals(format)) LocalDate.parse(value);
                if ("date-time".equals(format)) OffsetDateTime.parse(value);
            } catch (Exception error) { throw invalid("Enter a valid " + format + " value."); }
        }
        private IllegalArgumentException invalid(String detail) { return new IllegalArgumentException(title + ": " + detail); }
    }

    static Request parse(JSONObject params) {
        if (params == null || params.toString().length() > 65536) throw unsupported("This request is too large to display safely.");
        String mode = exactString(params, "mode", true, 30);
        String server = optionalString(params, "serverName", "MCP server", 180);
        String message = exactString(params, "message", true, 8000);
        ArrayList<Field> fields = new ArrayList<>();
        if ("url".equals(mode)) {
            String url = exactString(params, "url", true, 8192); checkedUrl(url);
            String id = exactString(params, "elicitationId", true, 1024);
            if (id.isEmpty()) throw unsupported("The browser request has no identifier.");
            return new Request(mode, server, message, url, fields, object("mode", mode, "serverName", server, "message", message, "url", url, "elicitationId", id));
        }
        if (!set("form", "openai/form", "openaiForm").contains(mode)) throw unsupported("This MCP request mode is not supported.");
        JSONObject schema = params.optJSONObject("requestedSchema");
        if (schema == null || !"object".equals(schema.optString("type"))) throw unsupported("This request does not contain a supported form.");
        checkKeys(schema, set("type", "properties", "required", "$schema", "additionalProperties"), "form");
        if (schema.has("additionalProperties") && !Boolean.FALSE.equals(schema.opt("additionalProperties"))) throw unsupported("Open-ended form properties are not supported.");
        JSONObject properties = schema.optJSONObject("properties");
        if (properties == null || properties.length() > MAX_FIELDS) throw unsupported("This form has too many fields or no field schema.");
        Set<String> required = new HashSet<>();
        JSONArray requiredList = schema.optJSONArray("required");
        if (schema.has("required") && !schema.isNull("required") && requiredList == null) throw unsupported("This form has an invalid required-fields list.");
        if (requiredList != null) for (int i = 0; i < requiredList.length(); i++) {
            String key = stringValue(requiredList.opt(i), "Required field", 100);
            if (!properties.has(key) || !required.add(key)) throw unsupported("This form has an invalid required field.");
        }
        Iterator<String> keys = properties.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            if (name.isEmpty() || name.length() > 100 || hasControls(name) || name.equals("__proto__") || name.equals("constructor") || name.equals("prototype")) throw unsupported("This form has an unsupported field name.");
            JSONObject field = properties.optJSONObject(name);
            if (field == null) throw unsupported("This form contains an invalid field.");
            fields.add(new Field(name, field, required.contains(name)));
        }
        boolean hasText = false;
        for (Field field : fields) if ("string".equals(field.type)) hasText = true;
        if (sensitive(message, hasText ? "string" : ""))
            throw unsupported("This form asks for a credential or secret. Use the service's official browser sign-in instead.");
        return new Request(mode, server, message, "", fields, object("mode", mode, "serverName", server, "message", message, "requestedSchema", copy(schema)));
    }

    static String checkedUrl(String value) {
        try {
            if (value == null || value.length() > 8192 || hasControls(value)) throw new IllegalArgumentException();
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isEmpty() || uri.getUserInfo() != null || uri.getPort() > 65535 || uri.getPort() == 0)
                throw new IllegalArgumentException();
            return uri.toASCIIString();
        } catch (Exception error) { throw unsupported("This request needs a valid HTTPS browser URL without embedded credentials."); }
    }
    private static boolean sensitive(String text, String type) {
        String lower = text.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).replaceAll("[_-]+", " ");
        if (lower.matches("(?s).*\\b(password|passphrase|credentials?|secrets?|otp|private key|api key|access token|refresh token|auth token|bearer token|client secret|verification code|authorization code|auth code|recovery code|one time code)\\b.*")) return true;
        return "string".equals(type) && lower.matches("(?s).*\\b(token|pin)\\b.*");
    }
    private static int bound(JSONObject schema, String key, int fallback, int cap) {
        if (schema.isNull(key)) return fallback;
        Object raw = schema.opt(key);
        if (!(raw instanceof Number)) throw unsupported("This form has an invalid size limit.");
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < 0 || value > cap) throw unsupported("This form exceeds PocketAgent's supported limits.");
        return (int) value;
    }
    private static BigDecimal decimalBound(JSONObject schema, String key) {
        if (schema.isNull(key)) return null;
        Object raw = schema.opt(key);
        if (!(raw instanceof Number)) throw unsupported("This form has an invalid number limit.");
        try {
            BigDecimal value = new BigDecimal(raw.toString());
            if (value.precision() > 40 || Math.abs((long) value.scale()) > 100) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) { throw unsupported("This form has an unsupported number limit."); }
    }
    private static String exactString(JSONObject value, String key, boolean required, int cap) {
        if ((!value.has(key) || value.isNull(key)) && !required) return "";
        return stringValue(value.opt(key), key, cap);
    }
    private static String optionalString(JSONObject value, String key, String fallback, int cap) {
        return value.isNull(key) ? fallback : exactString(value, key, false, cap);
    }
    private static String stringValue(Object value, String name, int cap) {
        if (!(value instanceof String) || ((String) value).length() > cap || hasControls((String) value)) throw unsupported("This request contains an invalid " + name + ".");
        return (String) value;
    }
    private static boolean hasControls(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 32 && c != '\n' && c != '\r' && c != '\t') || c == 127 || c == '\u202e' || c == '\u202d' || c == '\u2066' || c == '\u2067' || c == '\u2068' || c == '\u2069') return true;
        }
        return false;
    }
    private static void checkKeys(JSONObject value, Set<String> allowed, String label) {
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) if (!allowed.contains(keys.next())) throw unsupported("This " + label + " uses a constraint this build cannot safely validate.");
    }
    private static Set<String> set(String... values) { Set<String> result = new HashSet<>(); Collections.addAll(result, values); return result; }
    private static JSONObject copy(JSONObject value) { try { return new JSONObject(value.toString()); } catch (Exception error) { throw unsupported("The request is not valid JSON."); } }
    private static UnsupportedRequest unsupported(String message) { return new UnsupportedRequest(message); }
}
