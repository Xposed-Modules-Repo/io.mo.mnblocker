package io.mo.mnblocker;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Root-free mirror of {@link ContentStatsStore}, filling the gap where content
 * interception stats would otherwise stay at zero forever under the root-free
 * path (see docs/rootfree-mode-plan.md §4).
 *
 * Deliberately reuses {@link ContentStatsStore.Snapshot} itself — same
 * {count, lastBlocked, perApp} JSON shape AND the same type — so
 * {@code MainActivity.renderStats()} needs no branching beyond which store it
 * read from.
 *
 * File constructor takes the target file directly so tests can point it at a
 * temp dir instead of a real app data directory.
 */
final class RootFreeStatsStore {

    private static final String FILE_NAME = "rootfree_stats.json";

    private final File file;
    private long count = -1L; // -1 = not yet loaded from disk
    private long lastBlocked = 0L;
    private final LinkedHashMap<String, Long> perApp = new LinkedHashMap<>();
    private long fileMtime = Long.MIN_VALUE;

    RootFreeStatsStore(Context context) {
        this(fileFor(context));
    }

    RootFreeStatsStore(File file) {
        this.file = file;
    }

    static File fileFor(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    /** Record one content-level block by {@code pkg} and persist. Called from the listener. */
    synchronized void recordBlock(String pkg) {
        syncFromDiskIfNeeded();
        count++;
        lastBlocked = System.currentTimeMillis();
        String key = (pkg == null || pkg.isEmpty()) ? "<unknown>" : pkg;
        Long prev = perApp.get(key);
        perApp.put(key, (prev == null ? 0L : prev) + 1L);
        flush();
    }

    private void syncFromDiskIfNeeded() {
        try {
            long m = file.lastModified();
            if (count < 0L || m != fileMtime) {
                ContentStatsStore.Snapshot s = readFromDisk(file);
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
            JSONObject o = new JSONObject();
            o.put("count", count);
            o.put("lastBlocked", lastBlocked);
            JSONObject apps = new JSONObject();
            for (Map.Entry<String, Long> e : perApp.entrySet()) {
                apps.put(e.getKey(), e.getValue());
            }
            o.put("perApp", apps);
            try (FileWriter fw = new FileWriter(file, false)) {
                fw.write(o.toString());
            }
            fileMtime = file.lastModified();
        } catch (Throwable ignored) {
            // Best-effort persistence; a failed write must never crash the app.
        }
    }

    static ContentStatsStore.Snapshot readFromDisk(File file) {
        return parse(readNormal(file));
    }

    static ContentStatsStore.Snapshot readFromDisk(Context context) {
        return readFromDisk(fileFor(context));
    }

    /** Reset the counters to zero. No su needed — the file is already app-private. */
    static boolean reset(File file) {
        try {
            JSONObject o = new JSONObject();
            o.put("count", 0L);
            o.put("lastBlocked", 0L);
            o.put("perApp", new JSONObject());
            try (FileWriter fw = new FileWriter(file, false)) {
                fw.write(o.toString());
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean reset(Context context) {
        return reset(fileFor(context));
    }

    private static String readNormal(File f) {
        try {
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

    private static ContentStatsStore.Snapshot parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ContentStatsStore.Snapshot(0L, 0L, new LinkedHashMap<>());
        }
        try {
            JSONObject o = new JSONObject(json);
            LinkedHashMap<String, Long> apps = new LinkedHashMap<>();
            JSONObject a = o.optJSONObject("perApp");
            if (a != null) {
                for (Iterator<String> it = a.keys(); it.hasNext(); ) {
                    String k = it.next();
                    apps.put(k, a.optLong(k, 0L));
                }
            }
            return new ContentStatsStore.Snapshot(o.optLong("count", 0L), o.optLong("lastBlocked", 0L), apps);
        } catch (Throwable t) {
            return new ContentStatsStore.Snapshot(0L, 0L, new LinkedHashMap<>());
        }
    }
}
