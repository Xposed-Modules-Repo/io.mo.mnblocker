package io.mo.mnblocker;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Root-mode cleanup policy, kept independent of Android for race/order tests. */
final class NotificationCleanup {
    static final long SUMMARY_DELAY_MS = 500;
    static final long GROUP_TTL_MS = 60_000;
    static final int MAX_TRACKED = 256;

    interface Backend {
        boolean isEnabled();
        boolean post(Runnable task, long delayMs);
        // Both the snapshot and every cancel in action must hold NMS's notification lock.
        void withNotifications(Consumer<List<Entry>> action);
        void cancel(Entry entry);
    }

    static final class Id {
        final String pkg;
        final int userId;
        final int id;
        final String tag;

        Id(String pkg, int userId, int id, String tag) {
            this.pkg = pkg;
            this.userId = userId;
            this.id = id;
            this.tag = tag;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Id)) return false;
            Id that = (Id) other;
            return userId == that.userId && id == that.id
                    && pkg.equals(that.pkg) && Objects.equals(tag, that.tag);
        }

        @Override public int hashCode() {
            return Objects.hash(pkg, userId, id, tag);
        }
    }

    static final class Entry {
        final Id id;
        final String groupKey;
        final boolean summary;
        final boolean protectedNotification;
        final Object record;

        Entry(Id id, String groupKey, boolean summary, boolean protectedNotification,
              Object record) {
            this.id = id;
            this.groupKey = groupKey;
            this.summary = summary;
            this.protectedNotification = protectedNotification;
            this.record = record;
        }
    }

    private static final class Group {
        final String pkg;
        final int userId;
        final String key;

        Group(Id id, String key) {
            this.pkg = id.pkg;
            this.userId = id.userId;
            this.key = key;
        }

        boolean contains(Entry entry) {
            return userId == entry.id.userId && pkg.equals(entry.id.pkg)
                    && key.equals(entry.groupKey);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Group)) return false;
            Group that = (Group) other;
            return userId == that.userId && pkg.equals(that.pkg) && key.equals(that.key);
        }

        @Override public int hashCode() {
            return Objects.hash(pkg, userId, key);
        }
    }

    private final LongSupplier clock;
    private final Map<Id, Object> blocked = new LinkedHashMap<>();
    private final Map<Group, Long> groups = new LinkedHashMap<>();
    private boolean sweepScheduled;

    NotificationCleanup(LongSupplier clock) {
        this.clock = clock;
    }

    /** Any later enqueue supersedes an older cleanup ticket, including an allowed update. */
    synchronized void onEnqueue(Id id) {
        blocked.remove(id);
    }

    void onBlocked(Backend backend, Id id, String groupKey, boolean summary) {
        Object ticket = new Object();
        synchronized (this) {
            blocked.put(id, ticket);
            trim(blocked);
            if (!summary) rememberGroup(id, groupKey);
        }
        // Run behind earlier enqueue work, so a not-yet-posted placeholder is visible too.
        if (!backend.post(() -> cleanupOld(backend, id, ticket), 0)) {
            synchronized (this) {
                if (blocked.get(id) == ticket) blocked.remove(id);
            }
        }
        onEnqueueFinished(backend);
    }

    void onEnqueueFinished(Backend backend) {
        synchronized (this) {
            pruneGroups();
            if (groups.isEmpty() || sweepScheduled) return;
            sweepScheduled = true;
        }
        // Also called after allowed posts: catches summaries posted AFTER the blocked child.
        if (!backend.post(() -> cleanupGroups(backend), SUMMARY_DELAY_MS)) {
            synchronized (this) { sweepScheduled = false; }
        }
    }

    private void cleanupOld(Backend backend, Id id, Object ticket) {
        try {
            if (!backend.isEnabled()) return;
            backend.withNotifications(entries -> {
                synchronized (this) {
                    if (blocked.get(id) != ticket) return;
                    Entry target = null;
                    for (Entry entry : entries) {
                        if (!id.equals(entry.id)) continue;
                        // An FGS/whitelisted record in EITHER list protects this identity.
                        if (entry.protectedNotification || (entry.summary
                                && hasChildren(new Group(id, entry.groupKey), entries))) return;
                        if (target == null) target = entry; // posted list precedes pending list
                    }
                    if (target != null) {
                        for (Entry entry : entries) {
                            if (id.equals(entry.id) && !entry.summary) {
                                rememberGroup(id, entry.groupKey); // includes automatic grouping
                            }
                        }
                        backend.cancel(target);
                    }
                }
            });
        } finally {
            synchronized (this) {
                if (blocked.get(id) == ticket) blocked.remove(id);
            }
            onEnqueueFinished(backend);
        }
    }

    private void cleanupGroups(Backend backend) {
        synchronized (this) { sweepScheduled = false; }
        if (!backend.isEnabled()) {
            synchronized (this) { groups.clear(); }
            return;
        }
        backend.withNotifications(entries -> {
            synchronized (this) {
                pruneGroups();
                for (Group group : new ArrayList<>(groups.keySet())) {
                    if (hasChildren(group, entries)) {
                        // A surviving/pending child means this is no longer an orphan caused
                        // by our block. Forget the evidence rather than deleting it later.
                        groups.remove(group);
                        continue;
                    }
                    List<Id> canceled = new ArrayList<>();
                    for (Entry entry : entries) {
                        if (!group.contains(entry) || !entry.summary
                                || canceled.contains(entry.id)) continue;
                        // removeFromNotificationListsLocked removes ALL versions of a key.
                        // A protected pending update must protect the posted summary as well.
                        boolean protectedVersion = entries.stream().anyMatch(version ->
                                entry.id.equals(version.id) && (version.protectedNotification
                                        || !version.summary || !group.contains(version)));
                        if (!protectedVersion) {
                            backend.cancel(entry);
                            canceled.add(entry.id);
                        }
                    }
                    // Keep short-lived evidence even if no summary exists yet: some apps
                    // send the summary after the blocked children. Never scan unrelated groups.
                }
            }
        });
    }

    private static boolean hasChildren(Group group, List<Entry> entries) {
        if (group.key == null) return false;
        for (Entry entry : entries) {
            if (group.contains(entry) && !entry.summary) return true;
        }
        return false;
    }

    private void rememberGroup(Id id, String key) {
        if (key == null) return;
        pruneGroups();
        Group group = new Group(id, key);
        groups.remove(group);
        groups.put(group, clock.getAsLong());
        trim(groups);
    }

    private void pruneGroups() {
        long now = clock.getAsLong();
        groups.values().removeIf(at -> now - at >= GROUP_TTL_MS);
    }

    private static void trim(Map<?, ?> map) {
        while (map.size() > MAX_TRACKED) {
            Iterator<?> keys = map.keySet().iterator();
            keys.next();
            keys.remove();
        }
    }
}
