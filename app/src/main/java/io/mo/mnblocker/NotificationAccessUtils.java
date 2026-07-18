package io.mo.mnblocker;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

/**
 * Notification-listener access checks/prompts for the root-free path.
 *
 * Deliberately does NOT use {@code NotificationManager.getEnabledNotificationListeners()}
 * (a {@code @SystemApi}/{@code @hide} method a normal app cannot call) or
 * AndroidX's {@code NotificationManagerCompat} — this project has zero AndroidX
 * dependency and must stay that way (see CLAUDE.md).
 * {@code Settings.Secure.ENABLED_NOTIFICATION_LISTENERS} (referenced here by its
 * literal key, since the constant field is not present in this compileSdk's
 * android.jar) is the public, documented mechanism every third-party listener
 * app already relies on.
 */
final class NotificationAccessUtils {

    private static final String ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners";

    private NotificationAccessUtils() {}

    /** @return true if the user has granted this app notification-listener access. */
    static boolean isListenerAccessGranted(Context context) {
        try {
            String pkg = context.getPackageName();
            String flat = Settings.Secure.getString(context.getContentResolver(),
                    ENABLED_NOTIFICATION_LISTENERS);
            if (TextUtils.isEmpty(flat)) {
                return false;
            }
            for (String name : flat.split(":")) {
                ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && pkg.equals(cn.getPackageName())) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Force the system to bind our listener again. No-op if it is already bound.
     *
     * Access being granted does not mean the listener is running: replacing the
     * APK drops the binding but leaves the grant intact, and root-free mode then
     * silently intercepts nothing until the next reboot.
     *
     * {@link NotificationListenerService#requestRebind} alone does NOT fix that.
     * It reaches {@code ManagedServices.setComponentState(component, user, true)},
     * which early-returns when the component is not currently snoozed — so it only
     * ever undoes an explicit {@link NotificationListenerService#requestUnbind},
     * never a binding the system dropped by itself. Verified on-device: the
     * listener stayed unbound.
     *
     * Cycling our own component's enabled state does force a fresh bind, because
     * NotificationManagerService reacts to the package change by unregistering and
     * re-registering the service. The grant lives in Settings.Secure keyed by
     * component name and survives the cycle. {@code DONT_KILL_APP} keeps this
     * process (and the settings UI calling in here) alive across it.
     *
     * Only works while this process runs. If the ROM killed the app outright,
     * nothing in-process can help and the user has to exempt it from battery
     * optimisation.
     */
    static void requestRebind(Context context) {
        ComponentName cn = new ComponentName(context, RootFreeNotificationListener.class);
        try {
            NotificationListenerService.requestRebind(cn);
        } catch (Throwable ignored) {
            // Cheap, and the right call in the snoozed case; the cycle below is
            // what actually recovers a system-dropped binding.
        }
        try {
            PackageManager pm = context.getPackageManager();
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable ignored) {
            // A rebind attempt must never break the settings UI.
        }
    }

    /** Jump to the system's "notification access" settings page. */
    static void openListenerSettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Throwable ignored) {
            // A ROM without this settings screen must not crash the caller.
        }
    }
}
