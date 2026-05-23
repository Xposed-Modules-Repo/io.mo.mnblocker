package io.mo.mnblocker;

import android.app.NotificationChannel;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.LinkedHashMap;
import java.util.Map;

final class OriginalChannelStateStore {

    private static final String FILE = HookLogger.DIR + "/original_channel_states.json";

    private final Map<String, OriginalState> states = new LinkedHashMap<>();
    private boolean loaded;

    synchronized void saveIfAbsent(String pkg, NotificationChannel channel, int importance) {
        ensureLoaded();
        String key = key(pkg, channel.getId());
        if (states.containsKey(key)) {
            return;
        }
        boolean showBadge = true;
        boolean bypassDnd = false;
        try {
            showBadge = channel.canShowBadge();
        } catch (Throwable ignored) {
        }
        try {
            bypassDnd = channel.canBypassDnd();
        } catch (Throwable ignored) {
        }
        states.put(key, new OriginalState(importance, showBadge, bypassDnd));
        flush();
        HookLogger.i("Saved original notification channel state"
                + " | caller=" + pkg
                + " | id=" + HookLogger.safe(channel.getId())
                + " | importance=" + importance);
    }

    synchronized OriginalState get(String pkg, String channelId) {
        ensureLoaded();
        return states.get(key(pkg, channelId));
    }

    synchronized void remove(String pkg, String channelId) {
        ensureLoaded();
        if (states.remove(key(pkg, channelId)) != null) {
            flush();
        }
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        states.clear();
        try {
            String json = readNormal(FILE);
            if (json != null && !json.trim().isEmpty()) {
                JSONObject root = new JSONObject(json);
                for (java.util.Iterator<String> it = root.keys(); it.hasNext(); ) {
                    String key = it.next();
                    JSONObject o = root.optJSONObject(key);
                    if (o != null) {
                        states.put(key, new OriginalState(
                                o.optInt("importance", -1),
                                o.optBoolean("showBadge", true),
                                o.optBoolean("bypassDnd", false)));
                    }
                }
            }
        } catch (Throwable t) {
            HookLogger.e("Failed to load original channel states", t);
        }
        loaded = true;
    }

    private void flush() {
        try {
            HookLogger.ensureDir();
            JSONObject root = new JSONObject();
            for (Map.Entry<String, OriginalState> e : states.entrySet()) {
                OriginalState s = e.getValue();
                JSONObject o = new JSONObject();
                o.put("importance", s.importance);
                o.put("showBadge", s.showBadge);
                o.put("bypassDnd", s.bypassDnd);
                root.put(e.getKey(), o);
            }
            File f = new File(FILE);
            try (FileWriter fw = new FileWriter(f, false)) {
                fw.write(root.toString());
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
        } catch (Throwable t) {
            HookLogger.e("Failed to persist original channel states", t);
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

    private static String key(String pkg, String channelId) {
        return (pkg == null ? "<unknown>" : pkg) + "|" + (channelId == null ? "" : channelId);
    }

    static final class OriginalState {
        final int importance;
        final boolean showBadge;
        final boolean bypassDnd;

        OriginalState(int importance, boolean showBadge, boolean bypassDnd) {
            this.importance = importance;
            this.showBadge = showBadge;
            this.bypassDnd = bypassDnd;
        }
    }
}
