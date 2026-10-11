package io.mo.mnblocker;

import android.app.Notification;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import de.robv.android.xposed.XposedHelpers;

/** NMS adapter for content-block cleanup. Never modifies notification lists itself. */
final class RootNotificationCleanup {
    private final NotificationCleanup cleanup = new NotificationCleanup(SystemClock::elapsedRealtime);
    private final BooleanSupplier enabled;
    private final Predicate<StatusBarNotification> exempt;
    private Backend backend;
    private Object unsupportedService;

    RootNotificationCleanup(BooleanSupplier enabled, Predicate<StatusBarNotification> exempt) {
        this.enabled = enabled;
        this.exempt = exempt;
    }

    void onEnqueue(Object[] args) {
        try {
            NotificationCleanup.Id id = identity(args);
            if (id != null) cleanup.onEnqueue(id);
        } catch (Throwable t) {
            HookLogger.e("Could not identify notification update for cleanup", t);
        }
    }

    void onBlocked(Object service, Object[] args, Notification notification) {
        try {
            NotificationCleanup.Id id = identity(args);
            if (id == null) {
                HookLogger.w("Content blocked, but enqueue signature/user is unsupported for cleanup");
                return;
            }
            // The skipped enqueue would normally perform this ownership/delegation check.
            // Preserve it before cancellation so a spoofed target package cannot trigger cleanup.
            int notificationUid = (Integer) XposedHelpers.callMethod(service,
                    "resolveNotificationUid", args[1], id.pkg, args[2], id.userId);
            if (notificationUid < 0) return;
            Backend access = backend(service);
            if (access == null) return;
            String groupKey = null;
            if (notification.getGroup() != null) {
                // Use Android's own key format; group strings are NOT globally unique.
                StatusBarNotification sbn = new StatusBarNotification(id.pkg, (String) args[1],
                        id.id, id.tag, notificationUid, (Integer) args[3], 0, notification,
                        (UserHandle) XposedHelpers.callStaticMethod(UserHandle.class, "of", id.userId),
                        System.currentTimeMillis());
                groupKey = sbn.getGroupKey();
            }
            cleanup.onBlocked(access, id, groupKey,
                    (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0);
        } catch (Throwable t) {
            HookLogger.e("Content blocked, but notification cleanup failed", t);
        }
    }

    void onEnqueueFinished(Object service) {
        Backend access;
        synchronized (this) { access = backend; }
        if (access != null && access.service == service) {
            try {
                cleanup.onEnqueueFinished(access);
            } catch (Throwable t) {
                HookLogger.e("Could not schedule notification summary cleanup", t);
            }
        }
    }

    private synchronized Backend backend(Object service) {
        if (backend != null && backend.service == service) return backend;
        if (unsupportedService == service) return null;
        try {
            backend = new Backend(service);
            return backend;
        } catch (Throwable t) {
            unsupportedService = service;
            HookLogger.e("Notification cleanup unavailable on this ROM; content blocking remains active", t);
            return null;
        }
    }

    private static NotificationCleanup.Id identity(Object[] args) {
        // Shared prefix on Android 11/15/16, including their additional boolean/tracker args.
        // Fail closed on unknown ROM signatures instead of guessing notification IDs/users.
        if (args == null || args.length < 8 || !(args[0] instanceof String)
                || (args[1] != null && !(args[1] instanceof String))
                || !(args[2] instanceof Integer) || !(args[3] instanceof Integer)
                || (args[4] != null && !(args[4] instanceof String))
                || !(args[5] instanceof Integer) || !(args[6] instanceof Notification)
                || !(args[7] instanceof Integer)) return null;
        int userId = (Integer) args[7];
        if (userId < 0) {
            // Resolve USER_CURRENT with the same permission checks as NMS. Never cancel with
            // USER_ALL, whose wildcard semantics could remove another user's notification.
            userId = (Integer) XposedHelpers.callStaticMethod(android.app.ActivityManager.class,
                    "handleIncomingUser", args[3], args[2], userId, true, false,
                    "enqueueNotification", args[0]);
        }
        if (userId < 0) return null;
        return new NotificationCleanup.Id((String) args[0], userId,
                (Integer) args[5], (String) args[4]);
    }

    private final class Backend implements NotificationCleanup.Backend {
        final Object service;
        private final Object lock;
        private final Handler handler;
        private final NotificationCleanupMethods methods;

        Backend(Object service) throws NoSuchMethodException {
            this.service = service;
            lock = XposedHelpers.getObjectField(service, "mNotificationLock");
            handler = (Handler) XposedHelpers.getObjectField(service, "mHandler");
            if (lock == null || handler == null) throw new IllegalStateException("NMS not ready");
            Class<?> record = XposedHelpers.findClass(
                    "com.android.server.notification.NotificationRecord", service.getClass().getClassLoader());
            methods = new NotificationCleanupMethods(service.getClass(), record);
            // Validate BOTH lists before enabling cancellation; an incomplete snapshot is unsafe.
            readList("mNotificationList");
            readList("mEnqueuedNotifications");
        }

        @Override public boolean isEnabled() {
            return enabled.getAsBoolean();
        }

        @Override public boolean post(Runnable task, long delayMs) {
            return handler.postDelayed(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    HookLogger.e("Notification cleanup task failed", t);
                }
            }, delayMs);
        }

        @Override public void withNotifications(Consumer<List<NotificationCleanup.Entry>> action) {
            synchronized (lock) {
                List<NotificationCleanup.Entry> entries = new ArrayList<>();
                append(entries, readList("mNotificationList"));
                append(entries, readList("mEnqueuedNotifications"));
                action.accept(entries);
            }
        }

        private List<?> readList(String name) {
            Object value = XposedHelpers.getObjectField(service, name);
            if (!(value instanceof List)) throw new IllegalStateException("Missing NMS list " + name);
            return (List<?>) value;
        }

        private void append(List<NotificationCleanup.Entry> entries, List<?> records) {
            for (Object record : records) {
                StatusBarNotification sbn = (StatusBarNotification)
                        XposedHelpers.callMethod(record, "getSbn");
                Notification n = sbn.getNotification();
                NotificationCleanup.Id id = new NotificationCleanup.Id(sbn.getPackageName(),
                        sbn.getUserId(), sbn.getId(), sbn.getTag());
                entries.add(new NotificationCleanup.Entry(id, sbn.isGroup() ? sbn.getGroupKey() : null,
                        (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0, exempt.test(sbn), record));
            }
        }

        @Override public void cancel(NotificationCleanup.Entry entry) {
            if (!Thread.holdsLock(lock)) throw new IllegalStateException("NMS lock required");
            try {
                // Native removal updates the key index, pending list, listeners, group maps,
                // notification history and effects. Under this lock no child can sneak between
                // the orphan check and removal, and we never invoke cancelGroupChildrenLocked.
                methods.cancel(service, entry.record, NotificationListenerService.REASON_APP_CANCEL,
                        SystemClock.elapsedRealtime());
                HookLogger.i("CLEANED notification | caller=" + entry.id.pkg
                        + " | id=" + entry.id.id + " | user=" + entry.id.userId
                        + " | summary=" + entry.summary);
            } catch (Exception t) {
                throw new IllegalStateException("Native notification cancellation failed", t);
            }
        }
    }
}
