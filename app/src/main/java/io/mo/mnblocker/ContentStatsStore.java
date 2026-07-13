package io.mo.mnblocker;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cumulative counter for content-level interception.
 *
 * Direction: hook (system_server) writes, app UI reads.
 *
 * Persists a total block count, the wall-clock time of the last block, and a
 * per-package breakdown (package name -> block count) that feeds the stats
 * page's app ranking. Deliberately NO notification text is stored: the file is
 * world-readable (so the app, a different uid, can read it) and notification
 * titles can be sensitive; package names are the same granularity already kept
 * in {@link DetectedChannelsStore}.
 *
 * File: /data/system/mnblocker/content_stats.json
 *   {"count":N,"lastBlocked":ms,"perApp":{"pkg":n,...}}
 *
 * The hook side keeps the value in memory but re-syncs from disk whenever the
 * file's mtime changes, so an app-side "reset to zero" (written through the
 * app's own root) is picked up on the next block instead of being clobbered.
 */
final class ContentStatsStore {

    private static final String FILE = HookLogger.DIR + "/content_stats.json";

    // ---- hook-side live state ----
    private long count = -1L; // -1 = not yet loaded from disk
    private long lastBlocked = 0L;
    private final LinkedHashMap<String, Long> perApp = new LinkedHashMap<>();
    private long fileMtime = Long.MIN_VALUE;

    ContentStatsStore() {}

    /**
     * Record one content-level block by {@code pkg} and persist. Called from the
     * hook. Synchronised: NotificationManagerService enqueues from several threads.
     */
    synchronized void recordBlock(String pkg) {
        syncFromDiskIfNeeded();
        count++;
        lastBlocked = System.currentTimeMillis();
        String key = (pkg == null || pkg.isEmpty()) ? "<unknown>" : pkg;
        Long prev = perApp.get(key);
        perApp.put(key, (prev == null ? 0L : prev) + 1L);
        flush();
    }

    /** Reload from disk on first use or if the file changed underneath us. */
    private void syncFromDiskIfNeeded() {
        try {
            long m = new File(FILE).lastModified();
            if (count < 0L || m != fileMtime) {
                Snapshot s = readFromDisk();
                count = Math.max(0L, s.count);
                lastBlocked = s.lastBlocked;
                perApp.clear();
                perApp.putAll(s.perApp);
                fileMtime = m;
            }
        } catch (Throwable t) {
            if (count < 0L) {
                count = 0L;
            }
        }
    }

    private void flush() {
        try {
            HookLogger.ensureDir();
            JSONObject o = new JSONObject();
            o.put("count", count);
            o.put("lastBlocked", lastBlocked);
            JSONObject apps = new JSONObject();
            for (Map.Entry<String, Long> e : perApp.entrySet()) {
                apps.put(e.getKey(), e.getValue());
            }
            o.put("perApp", apps);
            File f = new File(FILE);
            try (FileWriter fw = new FileWriter(f, false)) {
                fw.write(o.toString());
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
            fileMtime = f.lastModified();
        } catch (Throwable t) {
            HookLogger.e("Failed to persist content stats", t);
        }
    }

    // ---- shared read side ----

    static Snapshot readFromDisk() {
        return parse(readNormal(FILE));
    }

    /** App UI variant: direct read first; if SELinux denies it, fall back to su. */
    static Snapshot readForApp() {
        String normal = readNormal(FILE);
        if (normal != null) {
            return parse(normal);
        }
        if (ShellUtils.missIsConclusive(FILE)) {
            return parse(null); // nothing blocked yet — su would tell us the same
        }
        return parse(ShellUtils.suReadFile(FILE));
    }

    /**
     * Reset the counter to zero. Written through the app's own root (the file
     * lives in /data/system, which a normal app uid cannot write directly).
     * The hook notices the mtime change and re-syncs before its next block.
     */
    static boolean resetFromApp() {
        try {
            JSONObject o = new JSONObject();
            o.put("count", 0L);
            o.put("lastBlocked", 0L);
            o.put("perApp", new JSONObject());
            return ShellUtils.suWriteFile(FILE, o.toString());
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

    private static Snapshot parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new Snapshot(0L, 0L, new LinkedHashMap<>());
        }
        try {
            JSONObject o = new JSONObject(json);
            LinkedHashMap<String, Long> apps = new LinkedHashMap<>();
            JSONObject a = o.optJSONObject("perApp");
            if (a != null) {
                for (java.util.Iterator<String> it = a.keys(); it.hasNext(); ) {
                    String k = it.next();
                    apps.put(k, a.optLong(k, 0L));
                }
            }
            return new Snapshot(o.optLong("count", 0L), o.optLong("lastBlocked", 0L), apps);
        } catch (Throwable t) {
            return new Snapshot(0L, 0L, new LinkedHashMap<>());
        }
    }

    static final class Snapshot {
        final long count;
        final long lastBlocked;
        /** package name -> content-level block count. */
        final Map<String, Long> perApp;

        Snapshot(long count, long lastBlocked, Map<String, Long> perApp) {
            this.count = count;
            this.lastBlocked = lastBlocked;
            this.perApp = perApp;
        }
    }
}
