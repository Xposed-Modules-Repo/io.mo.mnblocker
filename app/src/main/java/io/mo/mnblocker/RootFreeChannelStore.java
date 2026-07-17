package io.mo.mnblocker;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Root-free mirror of {@link DetectedChannelsStore}. The listener runs in the
 * app's own process/uid, so this is a plain app-private file
 * (getFilesDir()/rootfree_channels.json) — no world-readable file, no su
 * bridge (see docs/rootfree-mode-plan.md §4).
 *
 * Unlike the hook side there is no non-privileged way to enumerate
 * pre-existing channels, so this list can only grow as notifications are
 * actually observed — see docs/rootfree-mode-plan.md §7.
 *
 * File constructor takes the target file directly so tests can point it at a
 * temp dir instead of a real app data directory.
 */
final class RootFreeChannelStore {

    private static final String FILE_NAME = "rootfree_channels.json";
    /** Same cap as DetectedChannelsStore, for the same reason. */
    private static final int MAX_ENTRIES = 1000;

    private final File file;
    private final LinkedHashMap<String, ChannelRecord> live = new LinkedHashMap<>();
    private boolean loadedFromDisk;

    RootFreeChannelStore(Context context) {
        this(fileFor(context));
    }

    RootFreeChannelStore(File file) {
        this.file = file;
    }

    static File fileFor(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    /** Record (insert or update) a channel and persist. Called from the listener. */
    synchronized void record(ChannelRecord r) {
        if (!loadedFromDisk) {
            for (ChannelRecord prev : readAll(file)) {
                live.put(prev.key(), prev);
            }
            loadedFromDisk = true;
        }
        live.remove(r.key());
        live.put(r.key(), r);
        while (live.size() > MAX_ENTRIES) {
            live.remove(live.keySet().iterator().next());
        }
        flush();
    }

    private void flush() {
        try {
            JSONArray arr = new JSONArray();
            for (ChannelRecord r : live.values()) {
                arr.put(r.toJson());
            }
            try (FileWriter fw = new FileWriter(file, false)) {
                fw.write(arr.toString());
            }
        } catch (Throwable ignored) {
            // Best-effort persistence; a failed write must never crash the app.
        }
    }

    static List<ChannelRecord> readAll(File file) {
        return parse(readNormal(file));
    }

    static List<ChannelRecord> readAll(Context context) {
        return readAll(fileFor(context));
    }

    static boolean clear(File file) {
        try {
            try (FileWriter fw = new FileWriter(file, false)) {
                fw.write("[]");
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean clear(Context context) {
        return clear(fileFor(context));
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

    private static List<ChannelRecord> parse(String json) {
        List<ChannelRecord> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.add(ChannelRecord.fromJson(o));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
