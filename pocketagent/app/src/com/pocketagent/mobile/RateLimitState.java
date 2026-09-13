package com.pocketagent.mobile;

import org.json.JSONObject;
import java.util.Iterator;

/** Pinned Codex rolling quota notifications are sparse, not replacement account snapshots. */
final class RateLimitState {
    private RateLimitState() {}

    static JSONObject read(JSONObject previous, JSONObject incoming) {
        JSONObject result = copy(incoming);
        if (!sameAccount(previous, incoming)) return result;
        if (text(incoming, "accountId").isEmpty() && !text(previous, "accountId").isEmpty())
            put(result, "accountId", previous.opt("accountId"));
        // Null is unavailable, and cannot prove recovery from a previously observed restriction.
        if (incoming.isNull("ordinaryUsageAllowed") && Boolean.FALSE.equals(previous.opt("ordinaryUsageAllowed")))
            put(result, "ordinaryUsageAllowed", false);
        JSONObject primary = incoming.optJSONObject("rateLimits");
        if (primary != null) preserveRestriction(primaryFor(previous, primary), result.optJSONObject("rateLimits"));
        JSONObject buckets = result.optJSONObject("rateLimitsByLimitId");
        if (buckets != null) for (Iterator<String> names = buckets.keys(); names.hasNext();) {
            String name = names.next();
            preserveRestriction(child(child(previous, "rateLimitsByLimitId"), name), buckets.optJSONObject(name));
        }
        return result;
    }

    static JSONObject rolling(JSONObject previous, JSONObject update) {
        if (!sameAccount(previous, update)) return copy(update);
        JSONObject result = copy(previous);
        for (Iterator<String> keys = update.keys(); keys.hasNext();) {
            String key = keys.next(); Object value = update.opt(key);
            if (value == null || value == JSONObject.NULL || "rateLimits".equals(key) || "rateLimitsByLimitId".equals(key)) continue;
            put(result, key, value);
        }
        JSONObject incomingBuckets = update.optJSONObject("rateLimitsByLimitId");
        JSONObject buckets = copy(child(previous, "rateLimitsByLimitId"));
        if (incomingBuckets != null) for (Iterator<String> names = incomingBuckets.keys(); names.hasNext();) {
            String name = names.next(); JSONObject incoming = incomingBuckets.optJSONObject(name);
            if (incoming != null) put(buckets, name, mergeBucket(child(buckets, name), incoming));
        }
        JSONObject incoming = update.optJSONObject("rateLimits");
        if (incoming != null) {
            JSONObject merged = mergeBucket(primaryFor(previous, incoming), incoming);
            put(result, "rateLimits", merged);
            String id = text(merged, "limitId");
            if (!id.isEmpty()) put(buckets, id, mergeBucket(child(buckets, id), merged));
        }
        if (buckets.length() > 0) put(result, "rateLimitsByLimitId", buckets);
        return result;
    }

    private static JSONObject primaryFor(JSONObject previous, JSONObject incoming) {
        JSONObject primary = child(previous, "rateLimits");
        String oldId = text(primary, "limitId"), newId = text(incoming, "limitId");
        if (newId.isEmpty() || oldId.isEmpty() || newId.equals(oldId)) return primary;
        return child(child(previous, "rateLimitsByLimitId"), newId);
    }

    private static JSONObject mergeBucket(JSONObject previous, JSONObject incoming) {
        String oldId = text(previous, "limitId"), newId = text(incoming, "limitId");
        if (!oldId.isEmpty() && !newId.isEmpty() && !oldId.equals(newId)) return copy(incoming);
        return available(previous, incoming);
    }

    private static JSONObject available(JSONObject previous, JSONObject incoming) {
        JSONObject result = copy(previous);
        for (Iterator<String> keys = incoming.keys(); keys.hasNext();) {
            String key = keys.next(); Object value = incoming.opt(key);
            // A nullable restriction cannot prove recovery. Supplied windows are
            // new observations: missing/null duration, percentage or reset stays unknown.
            if ((value == null || value == JSONObject.NULL) && "spendControlReached".equals(key)) continue;
            if (value instanceof JSONObject) value = copy((JSONObject) value);
            put(result, key, value);
        }
        return result;
    }

    private static void preserveRestriction(JSONObject previous, JSONObject current) {
        if (current != null && current.isNull("spendControlReached") && Boolean.TRUE.equals(previous.opt("spendControlReached")))
            put(current, "spendControlReached", true);
    }
    private static boolean sameAccount(JSONObject previous, JSONObject incoming) {
        String oldId = text(previous, "accountId"), newId = text(incoming, "accountId");
        return oldId.isEmpty() || newId.isEmpty() || oldId.equals(newId);
    }
    private static String text(JSONObject source, String key) { Object value = source.opt(key); return value instanceof String ? (String) value : ""; }
    private static JSONObject child(JSONObject source, String key) { JSONObject value = source.optJSONObject(key); return value == null ? new JSONObject() : value; }
    private static JSONObject copy(JSONObject source) {
        try { return new JSONObject(source.toString()); }
        catch (Exception impossible) { throw new IllegalArgumentException(impossible); }
    }
    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); }
        catch (Exception impossible) { throw new IllegalArgumentException(impossible); }
    }
}
