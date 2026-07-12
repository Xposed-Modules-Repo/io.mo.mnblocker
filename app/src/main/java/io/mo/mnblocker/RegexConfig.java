package io.mo.mnblocker;

import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XSharedPreferences;

/**
 * Configuration shared from the settings UI to the hook.
 *
 * Two kinds of decision data:
 *
 *  1. Regex rules — whether a never-before-overridden channel counts as
 *     "marketing". Sources merged together:
 *       a. built-in default ( .*营销.* )
 *       b. user rules from XSharedPreferences (key {@code rules})
 *       c. plain-text fallback /data/system/mnblocker/rules.txt
 *     Plus an optional allow / whitelist rule set (key {@code allow_rules}) that
 *     protects matching channels (verification codes, IM, ...) from ever being
 *     blocked by a regex. The actual matching lives in {@link RuleMatcher}, which
 *     is shared with the settings UI so both sides agree.
 *
 *  2. Per-channel overrides — an explicit user decision for ONE channel,
 *     keyed by "pkg|id", stored as a JSON object under {@code overrides}:
 *       value true  -> always block this channel
 *       value false -> always allow this channel (even if a regex matches)
 *     An override ALWAYS wins over the regex rules.
 *
 * The instance is reloadable: {@link #reloadIfChanged()} cheaply re-reads the
 * prefs file when the UI has written to it, so toggles take effect without a
 * reboot (on the channel's next create/update event).
 */
final class RegexConfig {

    static final String PREFS_NAME = "mnblocker_prefs";
    static final String KEY_RULES = "rules";
    static final String KEY_ALLOW_RULES = "allow_rules";
    static final String KEY_MASTER_ENABLED = "master_enabled";
    static final String KEY_MATCH_DESC = "match_description";
    static final String KEY_OVERRIDES = "overrides";

    private static final String MODULE_PKG = "io.mo.mnblocker";
    private static final String RULES_FILE = HookLogger.DIR + "/rules.txt";

    private final XSharedPreferences xsp;

    /** Shared block/allow engine; rebuilt on every {@link #reload()}. Never null. */
    private volatile RuleMatcher matcher = RuleMatcher.compile(null, null);
    private long configFileLastModified = -1L;
    /** key "pkg|id" -> true (force block) / false (force allow). */
    private final Map<String, Boolean> overrides = new HashMap<>();
    private boolean masterEnabled = true;
    private boolean matchDescription = true;

    private RegexConfig(XSharedPreferences xsp) {
        this.xsp = xsp;
    }

    /** Build and fully populate a config instance (called once at hook install). */
    static RegexConfig load() {
        XSharedPreferences xsp = null;
        try {
            xsp = new XSharedPreferences(MODULE_PKG, PREFS_NAME);
            xsp.makeWorldReadable();
        } catch (Throwable t) {
            HookLogger.e("Could not open XSharedPreferences", t);
        }
        RegexConfig c = new RegexConfig(xsp);
        c.reload();
        return c;
    }

    /** Re-read everything only if the prefs file or /data/system config changed. */
    void reloadIfChanged() {
        try {
            boolean prefsChanged = xsp != null && xsp.hasFileChanged();
            long lm = ConfigFileStore.lastModifiedForHook();
            boolean configChanged = lm != configFileLastModified;
            if (prefsChanged || configChanged) {
                HookLogger.i("Config changed — reloading"
                        + " | prefsChanged=" + prefsChanged
                        + " configChanged=" + configChanged);
                reload();
            }
        } catch (Throwable t) {
            HookLogger.e("reloadIfChanged failed", t);
        }
    }

    /** Full (re)load from all sources. */
    synchronized void reload() {
        Set<String> blockRaw = new LinkedHashSet<>();
        Set<String> allowRaw = new LinkedHashSet<>();
        overrides.clear();

        // ---- (b) XSharedPreferences ----
        try {
            if (xsp != null) {
                xsp.reload();
                if (xsp.getFile().canRead()) {
                    masterEnabled = xsp.getBoolean(KEY_MASTER_ENABLED, true);
                    matchDescription = xsp.getBoolean(KEY_MATCH_DESC, true);
                    addLines(blockRaw, xsp.getString(KEY_RULES, ""));
                    addLines(allowRaw, xsp.getString(KEY_ALLOW_RULES, ""));
                    parseOverrides(xsp.getString(KEY_OVERRIDES, ""));
                } else {
                    HookLogger.w("XSharedPreferences not readable yet, using defaults + file");
                }
            }
        } catch (Throwable t) {
            HookLogger.e("Failed reading XSharedPreferences", t);
        }

        // ---- (c) root-writable JSON bridge under /data/system ----
        try {
            ConfigFileStore.ConfigSnapshot disk = ConfigFileStore.readForHook();
            configFileLastModified = disk.lastModified;
            if (disk.hasValue) {
                masterEnabled = disk.masterEnabled;
                matchDescription = disk.matchDescription;
                addLines(blockRaw, disk.rules);
                addLines(allowRaw, disk.allowRules);
                parseOverrides(disk.overrides);
            }
        } catch (Throwable t) {
            HookLogger.e("Failed reading config.json", t);
        }

        // ---- (d) plain text fallback file (block rules only, legacy) ----
        try {
            File f = new File(RULES_FILE);
            if (f.exists() && f.canRead()) {
                byte[] buf = new byte[(int) f.length()];
                try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                    int n = fis.read(buf);
                    if (n > 0) {
                        addLines(blockRaw, new String(buf, 0, n, "UTF-8"));
                    }
                }
            }
        } catch (Throwable t) {
            HookLogger.e("Failed reading rules.txt", t);
        }

        // ---- compile via the shared engine (default block rule injected there) ----
        matcher = RuleMatcher.compile(blockRaw, allowRaw);

        HookLogger.i("Loaded " + matcher.blockRules().size() + " block rule(s) + "
                + matcher.allowRules().size() + " allow rule(s) + "
                + overrides.size() + " override(s)"
                + " | masterEnabled=" + masterEnabled
                + " matchDescription=" + matchDescription);
    }

    private void parseOverrides(String json) {
        if (json == null || json.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject o = new JSONObject(json);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                overrides.put(k, o.optBoolean(k, false));
            }
        } catch (Throwable t) {
            HookLogger.e("Failed to parse channel overrides JSON", t);
        }
    }

    private static void addLines(Set<String> dst, String blob) {
        if (blob == null) {
            return;
        }
        for (String line : blob.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                dst.add(line.trim());
            }
        }
    }

    boolean isMasterEnabled() {
        return masterEnabled;
    }

    boolean isMatchDescription() {
        return matchDescription;
    }

    List<String> rawRules() {
        return Collections.unmodifiableList(matcher.blockRules());
    }

    List<String> rawAllowRules() {
        return Collections.unmodifiableList(matcher.allowRules());
    }

    /**
     * Per-channel override decision.
     *
     * @return Boolean.TRUE  -> user forced "block"
     *         Boolean.FALSE -> user forced "allow"
     *         null          -> no override, fall back to regex
     */
    Boolean overrideFor(String pkg, String channelId) {
        synchronized (this) {
            return overrides.get(pkg + "|" + channelId);
        }
    }

    /**
     * @return the first block rule that matched, or {@code null} if none did.
     */
    String firstMatch(String... candidates) {
        return matcher.firstBlockMatch(candidates);
    }

    /**
     * @return the first allow (whitelist) rule that matched, or {@code null}.
     *         A non-null result means the channel must NOT be blocked by regex.
     */
    String firstAllowMatch(String... candidates) {
        return matcher.firstAllowMatch(candidates);
    }
}
