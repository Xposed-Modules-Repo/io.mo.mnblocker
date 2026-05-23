package io.mo.mnblocker;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
    static final String KEY_MASTER_ENABLED = "master_enabled";
    static final String KEY_MATCH_DESC = "match_description";
    static final String KEY_OVERRIDES = "overrides";

    private static final String MODULE_PKG = "io.mo.mnblocker";
    private static final String RULES_FILE = HookLogger.DIR + "/rules.txt";

    /** The literal default rule: any channel whose text contains 营销. */
    private static final String DEFAULT_RULE = ".*营销.*";

    private final XSharedPreferences xsp;

    private final List<Pattern> patterns = new ArrayList<>();
    private final List<String> rawRules = new ArrayList<>();
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
        Set<String> raw = new LinkedHashSet<>();
        raw.add(DEFAULT_RULE);
        overrides.clear();

        // ---- (b) XSharedPreferences ----
        try {
            if (xsp != null) {
                xsp.reload();
                if (xsp.getFile().canRead()) {
                    masterEnabled = xsp.getBoolean(KEY_MASTER_ENABLED, true);
                    matchDescription = xsp.getBoolean(KEY_MATCH_DESC, true);
                    addLines(raw, xsp.getString(KEY_RULES, ""));
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
                addLines(raw, disk.rules);
                parseOverrides(disk.overrides);
            }
        } catch (Throwable t) {
            HookLogger.e("Failed reading config.json", t);
        }

        // ---- (d) plain text fallback file ----
        try {
            File f = new File(RULES_FILE);
            if (f.exists() && f.canRead()) {
                byte[] buf = new byte[(int) f.length()];
                try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                    int n = fis.read(buf);
                    if (n > 0) {
                        addLines(raw, new String(buf, 0, n, "UTF-8"));
                    }
                }
            }
        } catch (Throwable t) {
            HookLogger.e("Failed reading rules.txt", t);
        }

        // ---- compile ----
        patterns.clear();
        rawRules.clear();
        for (String r : raw) {
            String trimmed = r.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            try {
                patterns.add(Pattern.compile(trimmed));
                rawRules.add(trimmed);
            } catch (PatternSyntaxException pse) {
                HookLogger.w("Invalid regex skipped: " + trimmed + " (" + pse.getDescription() + ")");
            }
        }
        if (patterns.isEmpty()) {
            patterns.add(Pattern.compile(DEFAULT_RULE));
            rawRules.add(DEFAULT_RULE);
        }

        HookLogger.i("Loaded " + patterns.size() + " regex rule(s) + "
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
        return Collections.unmodifiableList(rawRules);
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
     * @return the first regex rule that matched, or {@code null} if none did.
     */
    synchronized String firstMatch(String... candidates) {
        for (Pattern p : patterns) {
            for (String cand : candidates) {
                if (cand == null || cand.isEmpty()) {
                    continue;
                }
                try {
                    if (p.matcher(cand).matches() || p.matcher(cand).find()) {
                        return p.pattern();
                    }
                } catch (Throwable t) {
                    HookLogger.e("Regex match error for " + p.pattern(), t);
                }
            }
        }
        return null;
    }
}
