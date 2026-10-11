package io.mo.mnblocker;

import java.lang.reflect.Method;

/** Resolve the complete native cancellation path before making any state changes. */
final class NotificationCleanupMethods {
    private final Method remove;
    private final Method cancel;
    private final Method updateLights;

    NotificationCleanupMethods(Class<?> service, Class<?> record) throws NoSuchMethodException {
        remove = find(service, "removeFromNotificationListsLocked", record);
        Method resolved;
        try {
            // Android 15/16 add the elapsed-realtime timestamp; Android 11 has five args.
            resolved = find(service, "cancelNotificationLocked", record, boolean.class,
                    int.class, boolean.class, String.class, long.class);
        } catch (NoSuchMethodException legacy) {
            resolved = find(service, "cancelNotificationLocked", record, boolean.class,
                    int.class, boolean.class, String.class);
        }
        cancel = resolved;
        Method lights = null;
        try {
            lights = find(service, "updateLightsLocked");
        } catch (NoSuchMethodException ignored) {
            // Newer releases clear effects in their NotificationAttentionHelper instead.
        }
        updateLights = lights;
    }

    /** Caller must hold the notification lock and verify all versions of this key first. */
    void cancel(Object service, Object record, int reason, long elapsedRealtime) throws Exception {
        boolean wasPosted = (Boolean) remove.invoke(service, record);
        // No deleteIntent: cleanup must not tell the originating app to launch/repost anything.
        if (cancel.getParameterTypes().length == 6) {
            cancel.invoke(service, record, false, reason, wasPosted, null, elapsedRealtime);
        } else {
            cancel.invoke(service, record, false, reason, wasPosted, null);
        }
        if (updateLights != null) updateLights.invoke(service);
    }

    private static Method find(Class<?> type, String name, Class<?>... params)
            throws NoSuchMethodException {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                Method method = cursor.getDeclaredMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Vendor service subclasses often inherit the AOSP implementation.
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }
}
