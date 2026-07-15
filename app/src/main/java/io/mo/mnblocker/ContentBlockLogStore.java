package io.mo.mnblocker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-app history of the individual notifications that content-level
 * interception dropped — the data behind the stats page's "tap an app to see
 * what was blocked" detail screen.
 *
 * Direction: hook (system_server) writes, app UI reads.
 *
 * Unlike {@link ContentStatsStore} (which is a world-readable counter and
 * deliberately stores NO notification text), this file DOES persist the blocked
 * notification's title/text, so it is written PRIVATE (owner-only, 0600, owned
 * by system uid 1000). A normal app uid cannot read it directly and must go
 * through {@code su} — the same "root only" channel the module already uses for
 * everything under /data/system. Notification titles can carry sensitive data
 * (verification codes, private messages), so they must never be left
 * world-readable. {@link ShellUtils#fixDirPermissions()} re-locks this file to
 * 0600 after its blanket {@code chmod 0666 dir/*} so a startup repair cannot
 * silently widen it.
 *
 * Only content-level blocks produce entries here: channel-level blocking just
 * lowers a channel's importance, it never intercepts an individual post, so
 * there is nothing per-notification to record for it.
 *
 * File: /data/system/mnblocker/content_block_log.json
 *   {"perApp":{"pkg":[{"t":ms,"ti":"title","tx":"text","r":"rule"}, ...], ...}}
 *
 * The hook keeps the value in memory but re-syncs from disk whenever the file's
 * mtime changes, so an app-side clear is picked up before the next block instead
 * of being clobbered.
 */
final class ContentBlockLogStore {

    static final String FILE = HookLogger.DIR + "/content_block_log.json";

    /** Ring cap per app: keep only the most recent N blocked notifications. */
    private static final int MAX_PER_APP = 100;
    /** Safety cap on distinct apps so the file cannot grow without bound. */
    private static final int MAX_APPS = 300;
    /** Defensive cap on stored text length; matching never needs the full body. */
    private static final int MAX_TEXT_LEN = 500;

    // ---- hook-side live state ----
    // Insertion-ordered: least-recently-active app first, so eviction is cheap.
    private final LinkedHashMap<String, List<Entry>> perApp = new LinkedHashMap<>();
    private boolean loaded = false;
    private long fileMtime = Long.MIN_VALUE;

    ContentBlockLogStore() {}

    /**
     * Record one dropped content notification and persist. Called from the hook.
     * Synchronised: NotificationManagerService enqueues from several threads.
     */
    synchronized void record(String pkg, String title, String text, String rule) {
        syncFromDiskIfNeeded();
        String key = (pkg == null || pkg.isEmpty()) ? "<unknown>" : pkg;

        List<Entry> list = perApp.remove(key); // remove+put => mark app most-recent
        if (list == null) {
            list = new ArrayList<>();
        }
        list.add(new Entry(System.currentTimeMillis(), clip(title), clip(text), clip(rule)));
        while (list.size() > MAX_PER_APP) {
            list.remove(0);
        }
        perApp.put(key, list);

        while (perApp.size() > MAX_APPS) {
            String oldest = perApp.keySet().iterator().next();
            perApp.remove(oldest);
        }
        flush();
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_TEXT_LEN ? s.substring(0, MAX_TEXT_LEN) : s;
    }

    /** Reload from disk on first use or if the file changed underneath us. */
    private void syncFromDiskIfNeeded() {
        try {
            long m = new File(FILE).lastModified();
            if (!loaded || m != fileMtime) {
                perApp.clear();
                perApp.putAll(parse(readNormal(FILE)));
                loaded = true;
                fileMtime = m;
            }
        } catch (Throwable t) {
            loaded = true;
        }
    }

    private void flush() {
        try {
            HookLogger.ensureDir();
            String json = serialize(perApp);
            File f = new File(FILE);
            try (FileWriter fw = new FileWriter(f, false)) {
                fw.write(json);
            }
            // PRIVATE: owner (system) read/write only — never world-readable,
            // because unlike the counter file this one holds notification text.
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(false, false);
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, true);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(false, false);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, true);
            fileMtime = f.lastModified();
        } catch (Throwable t) {
            HookLogger.e("Failed to persist content block log", t);
        }
    }

    // ---- app read side ----

    /**
     * Entries for one app, newest first. The file is 0600, so a direct read
     * always fails for the app uid; fall back to su unless the file is
     * conclusively absent (fresh install / nothing blocked yet).
     */
    static List<Entry> readForApp(String pkg) {
        String json = readNormal(FILE);
        if (json == null && !ShellUtils.missIsConclusive(FILE)) {
            json = ShellUtils.suReadFile(FILE);
        }
        Map<String, List<Entry>> all = parse(json);
        List<Entry> list = all.get(pkg);
        List<Entry> out = new ArrayList<>(list == null ? new ArrayList<>() : list);
        java.util.Collections.reverse(out); // stored oldest-first => show newest-first
        return out;
    }

    /**
     * Clear the whole log. Written through the app's own root (the file lives in
     * /data/system, which a normal app uid cannot write directly). Writing an
     * empty object leaves no notification text behind; the hook notices the mtime
     * change and re-syncs (and re-locks to 0600) before its next block.
     */
    static boolean resetFromApp() {
        return ShellUtils.suWriteFile(FILE, "{}");
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

    private static String serialize(Map<String, List<Entry>> perApp) {
        JSONObject apps = new JSONObject();
        try {
            for (Map.Entry<String, List<Entry>> e : perApp.entrySet()) {
                JSONArray arr = new JSONArray();
                for (Entry en : e.getValue()) {
                    JSONObject o = new JSONObject();
                    o.put("t", en.time);
                    o.put("ti", en.title);
                    o.put("tx", en.text);
                    o.put("r", en.rule);
                    arr.put(o);
                }
                apps.put(e.getKey(), arr);
            }
            JSONObject root = new JSONObject();
            root.put("perApp", apps);
            return root.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    private static Map<String, List<Entry>> parse(String json) {
        LinkedHashMap<String, List<Entry>> out = new LinkedHashMap<>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        try {
            JSONObject apps = new JSONObject(json).optJSONObject("perApp");
            if (apps == null) {
                return out;
            }
            for (java.util.Iterator<String> it = apps.keys(); it.hasNext(); ) {
                String pkg = it.next();
                JSONArray arr = apps.optJSONArray(pkg);
                if (arr == null) {
                    continue;
                }
                List<Entry> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        list.add(new Entry(o.optLong("t", 0L),
                                o.optString("ti", ""),
                                o.optString("tx", ""),
                                o.optString("r", "")));
                    }
                }
                out.put(pkg, list);
            }
        } catch (Throwable t) {
            // app side has no HookLogger file guarantee; swallow quietly
        }
        return out;
    }

    /** One dropped notification. */
    static final class Entry {
        final long time;
        final String title;
        final String text;
        final String rule;

        Entry(long time, String title, String text, String rule) {
            this.time = time;
            this.title = title;
            this.text = text;
            this.rule = rule;
        }
    }
}
