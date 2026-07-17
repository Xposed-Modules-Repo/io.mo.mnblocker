package io.mo.mnblocker;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Root-free mirror of {@link ContentBlockLogStore}. Same
 * {@code {"perApp":{"pkg":[{"t","ti","tx","r"}]}}} JSON shape AND the same
 * {@link ContentBlockLogStore.Entry} type as the root path, so
 * {@code BlockedNotificationsActivity} only needs to pick which store to read
 * from, not a different rendering path.
 *
 * Unlike the root version this file needs no 0600 lock-down: getFilesDir() is
 * already private to this app's own uid (see docs/rootfree-mode-plan.md §4 note).
 *
 * File constructor takes the target file directly so tests can point it at a
 * temp dir instead of a real app data directory.
 */
final class RootFreeBlockLogStore {

    private static final String FILE_NAME = "rootfree_block_log.json";
    /** Same caps as ContentBlockLogStore, for the same reasons. */
    private static final int MAX_PER_APP = 100;
    private static final int MAX_APPS = 300;
    private static final int MAX_TEXT_LEN = 500;

    private final File file;
    // Insertion-ordered: least-recently-active app first, so eviction is cheap.
    private final LinkedHashMap<String, List<ContentBlockLogStore.Entry>> perApp = new LinkedHashMap<>();
    private boolean loaded;
    private long fileMtime = Long.MIN_VALUE;

    RootFreeBlockLogStore(Context context) {
        this(fileFor(context));
    }

    RootFreeBlockLogStore(File file) {
        this.file = file;
    }

    static File fileFor(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    /** Record one dropped content notification and persist. Called from the listener. */
    synchronized void record(String pkg, String title, String text, String rule) {
        syncFromDiskIfNeeded();
        String key = (pkg == null || pkg.isEmpty()) ? "<unknown>" : pkg;

        List<ContentBlockLogStore.Entry> list = perApp.remove(key); // mark app most-recent
        if (list == null) {
            list = new ArrayList<>();
        }
        list.add(new ContentBlockLogStore.Entry(
                System.currentTimeMillis(), clip(title), clip(text), clip(rule)));
        while (list.size() > MAX_PER_APP) {
            list.remove(0);
        }
        perApp.put(key, list);

        while (perApp.size() > MAX_APPS) {
            perApp.remove(perApp.keySet().iterator().next());
        }
        flush();
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_TEXT_LEN ? s.substring(0, MAX_TEXT_LEN) : s;
    }

    private void syncFromDiskIfNeeded() {
        try {
            long m = file.lastModified();
            if (!loaded || m != fileMtime) {
                perApp.clear();
                perApp.putAll(parse(readNormal(file)));
                loaded = true;
                fileMtime = m;
            }
        } catch (Throwable t) {
            loaded = true;
        }
    }

    private void flush() {
        try {
            String json = serialize(perApp);
            try (FileWriter fw = new FileWriter(file, false)) {
                fw.write(json);
            }
            fileMtime = file.lastModified();
        } catch (Throwable ignored) {
            // Best-effort persistence; a failed write must never crash the app.
        }
    }

    /** Entries for one app, newest first. */
    static List<ContentBlockLogStore.Entry> readForApp(File file, String pkg) {
        Map<String, List<ContentBlockLogStore.Entry>> all = parse(readNormal(file));
        List<ContentBlockLogStore.Entry> list = all.get(pkg);
        List<ContentBlockLogStore.Entry> out = new ArrayList<>(list == null ? new ArrayList<>() : list);
        Collections.reverse(out); // stored oldest-first => show newest-first
        return out;
    }

    static List<ContentBlockLogStore.Entry> readForApp(Context context, String pkg) {
        return readForApp(fileFor(context), pkg);
    }

    /** Clear the whole log. No su needed — the file is already app-private. */
    static boolean reset(File file) {
        try {
            try (FileWriter fw = new FileWriter(file, false)) {
                fw.write("{}");
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

    private static String serialize(Map<String, List<ContentBlockLogStore.Entry>> perApp) {
        JSONObject apps = new JSONObject();
        try {
            for (Map.Entry<String, List<ContentBlockLogStore.Entry>> e : perApp.entrySet()) {
                JSONArray arr = new JSONArray();
                for (ContentBlockLogStore.Entry en : e.getValue()) {
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

    private static Map<String, List<ContentBlockLogStore.Entry>> parse(String json) {
        LinkedHashMap<String, List<ContentBlockLogStore.Entry>> out = new LinkedHashMap<>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        try {
            JSONObject apps = new JSONObject(json).optJSONObject("perApp");
            if (apps == null) {
                return out;
            }
            for (Iterator<String> it = apps.keys(); it.hasNext(); ) {
                String pkg = it.next();
                JSONArray arr = apps.optJSONArray(pkg);
                if (arr == null) {
                    continue;
                }
                List<ContentBlockLogStore.Entry> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        list.add(new ContentBlockLogStore.Entry(o.optLong("t", 0L),
                                o.optString("ti", ""), o.optString("tx", ""), o.optString("r", "")));
                    }
                }
                out.put(pkg, list);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
