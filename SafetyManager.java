package io.mo.mnblocker;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One detected notification channel, as observed by the hook inside
 * system_server and surfaced to the settings UI.
 *
 * Serialised to JSON for the hook -> app hand-off (see {@link DetectedChannelsStore}).
 */
final class ChannelRecord {

    /** Owning app package, e.g. "com.taobao.taobao". */
    final String pkg;
    /** Channel id as registered by the app. */
    final String id;
    /** Human-readable channel name (may equal id on some apps). */
    final String name;
    /** Importance last seen BEFORE the module touched it. */
    final int importance;
    /** True if a regex rule matched this channel. */
    final boolean regexMatched;
    /** Wall-clock time the channel was last observed. */
    final long lastSeen;

    ChannelRecord(String pkg, String id, String name,
                  int importance, boolean regexMatched, long lastSeen) {
        this.pkg = pkg == null ? "<unknown>" : pkg;
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.importance = importance;
        this.regexMatched = regexMatched;
        this.lastSeen = lastSeen;
    }

    /** Stable identity for a channel: package + id. Used as map key / override key. */
    String key() {
        return pkg + "|" + id;
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("pkg", pkg);
            o.put("id", id);
            o.put("name", name);
            o.put("importance", importance);
            o.put("regexMatched", regexMatched);
            o.put("lastSeen", lastSeen);
        } catch (JSONException ignored) {
            // keys are constant; this cannot realistically throw
        }
        return o;
    }

    static ChannelRecord fromJson(JSONObject o) {
        return new ChannelRecord(
                o.optString("pkg", "<unknown>"),
                o.optString("id", ""),
                o.optString("name", ""),
                o.optInt("importance", -1),
                o.optBoolean("regexMatched", false),
                o.optLong("lastSeen", 0L));
    }
}
