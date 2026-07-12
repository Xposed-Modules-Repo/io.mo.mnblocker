package io.mo.mnblocker;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;

/**
 * Cumulative counter for content-level interception.
 *
 * Direction: hook (system_server) writes, app UI reads.
 *
 * Only two numbers are persisted — the total block count and the wall-clock
 * time of the last block. Deliberately NO notification text is stored: the file
 * is world-readable (so the app, a different uid, can read it) and notification
 * titles can be sensitive.
 *
 * File: /data/system/mnblocker/content_stats.json  {"count":N,"lastBlocked":ms}
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
    private long fileMtime = Long.MIN_VALUE;

    ContentStatsStore() {}

    /**
     * Record one content-level block and persist. Called from the hook.
     * Synchronised: NotificationManagerService enqueues from several threads.
     */
    synchronized void recordBlock() {
        syncFromDiskIfNeeded();
        count++;
        lastBlocked = System.currentTimeMillis();
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
            return new Snapshot(0L, 0L);
        }
        try {
            JSONObject o = new JSONObject(json);
            return new Snapshot(o.optLong("count", 0L), o.optLong("lastBlocked", 0L));
        } catch (Throwable t) {
            return new Snapshot(0L, 0L);
        }
    }

    static final class Snapshot {
        final long count;
        final long lastBlocked;

        Snapshot(long count, long lastBlocked) {
            this.count = count;
            this.lastBlocked = lastBlocked;
        }
    }
}
