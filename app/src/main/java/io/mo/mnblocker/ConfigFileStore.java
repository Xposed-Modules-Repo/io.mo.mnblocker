package io.mo.mnblocker;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;

/**
 * SELinux-friendly config bridge.
 *
 * App -> hook config is stored in /data/system/mnblocker/config.json.
 * The settings UI writes it through root; system_server reads it directly.
 */
final class ConfigFileStore {

    static final String CONFIG_FILE = HookLogger.DIR + "/config.json";

    private ConfigFileStore() {}

    static ConfigSnapshot empty() {
        return new ConfigSnapshot(false, true, true, "", "", "", false, "", -1L);
    }

    static long lastModifiedForHook() {
        try {
            return new File(CONFIG_FILE).lastModified();
        } catch (Throwable t) {
            return -1L;
        }
    }

    static ConfigSnapshot readForHook() {
        return parse(readNormal(CONFIG_FILE), lastModifiedForHook());
    }

    static ConfigSnapshot readForApp() {
        String normal = readNormal(CONFIG_FILE);
        if (normal != null) {
            return parse(normal, lastModifiedForHook());
        }
        String viaSu = ShellUtils.suReadFile(CONFIG_FILE);
        return parse(viaSu, lastModifiedForHook());
    }

    static boolean writeFromApp(boolean masterEnabled,
                                boolean matchDescription,
                                String rules,
                                String allowRules,
                                String overrides,
                                boolean contentEnabled,
                                String contentRules) {
        try {
            JSONObject o = new JSONObject();
            o.put(RegexConfig.KEY_MASTER_ENABLED, masterEnabled);
            o.put(RegexConfig.KEY_MATCH_DESC, matchDescription);
            o.put(RegexConfig.KEY_RULES, rules == null ? "" : rules);
            o.put(RegexConfig.KEY_ALLOW_RULES, allowRules == null ? "" : allowRules);
            o.put(RegexConfig.KEY_OVERRIDES, overrides == null ? "" : overrides);
            o.put(RegexConfig.KEY_CONTENT_ENABLED, contentEnabled);
            o.put(RegexConfig.KEY_CONTENT_RULES, contentRules == null ? "" : contentRules);
            o.put("updatedAt", System.currentTimeMillis());
            return ShellUtils.suWriteFile(CONFIG_FILE, o.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("unused")
    static boolean writeForHook(boolean masterEnabled,
                                boolean matchDescription,
                                String rules,
                                String allowRules,
                                String overrides,
                                boolean contentEnabled,
                                String contentRules) {
        try {
            HookLogger.ensureDir();
            JSONObject o = new JSONObject();
            o.put(RegexConfig.KEY_MASTER_ENABLED, masterEnabled);
            o.put(RegexConfig.KEY_MATCH_DESC, matchDescription);
            o.put(RegexConfig.KEY_RULES, rules == null ? "" : rules);
            o.put(RegexConfig.KEY_ALLOW_RULES, allowRules == null ? "" : allowRules);
            o.put(RegexConfig.KEY_OVERRIDES, overrides == null ? "" : overrides);
            o.put(RegexConfig.KEY_CONTENT_ENABLED, contentEnabled);
            o.put(RegexConfig.KEY_CONTENT_RULES, contentRules == null ? "" : contentRules);
            o.put("updatedAt", System.currentTimeMillis());
            try (FileWriter fw = new FileWriter(CONFIG_FILE, false)) {
                fw.write(o.toString());
            }
            //noinspection ResultOfMethodCallIgnored
            new File(CONFIG_FILE).setReadable(true, false);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readNormal(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.canRead()) {
                return null;
            }
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream fis = new FileInputStream(f)) {
                int n = fis.read(buf);
                return n > 0 ? new String(buf, 0, n, "UTF-8") : "";
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static ConfigSnapshot parse(String json, long lastModified) {
        if (json == null || json.trim().isEmpty()) {
            return empty();
        }
        try {
            JSONObject o = new JSONObject(json);
            return new ConfigSnapshot(
                    true,
                    o.optBoolean(RegexConfig.KEY_MASTER_ENABLED, true),
                    o.optBoolean(RegexConfig.KEY_MATCH_DESC, true),
                    o.optString(RegexConfig.KEY_RULES, ""),
                    o.optString(RegexConfig.KEY_ALLOW_RULES, ""),
                    o.optString(RegexConfig.KEY_OVERRIDES, ""),
                    o.optBoolean(RegexConfig.KEY_CONTENT_ENABLED, false),
                    o.optString(RegexConfig.KEY_CONTENT_RULES, ""),
                    lastModified);
        } catch (Throwable t) {
            return empty();
        }
    }

    static final class ConfigSnapshot {
        final boolean hasValue;
        final boolean masterEnabled;
        final boolean matchDescription;
        final String rules;
        final String allowRules;
        final String overrides;
        final boolean contentEnabled;
        final String contentRules;
        final long lastModified;

        ConfigSnapshot(boolean hasValue,
                       boolean masterEnabled,
                       boolean matchDescription,
                       String rules,
                       String allowRules,
                       String overrides,
                       boolean contentEnabled,
                       String contentRules,
                       long lastModified) {
            this.hasValue = hasValue;
            this.masterEnabled = masterEnabled;
            this.matchDescription = matchDescription;
            this.rules = rules == null ? "" : rules;
            this.allowRules = allowRules == null ? "" : allowRules;
            this.overrides = overrides == null ? "" : overrides;
            this.contentEnabled = contentEnabled;
            this.contentRules = contentRules == null ? "" : contentRules;
            this.lastModified = lastModified;
        }
    }
}
