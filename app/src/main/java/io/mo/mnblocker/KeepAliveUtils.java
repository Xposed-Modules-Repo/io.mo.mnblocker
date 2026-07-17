package io.mo.mnblocker;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Keep-alive helpers for the root-free path.
 *
 * Root-free interception only works while this process is alive and its
 * {@link RootFreeNotificationListener} is bound. The root path has no such
 * problem — its hook lives inside system_server — so none of this applies there.
 *
 * Two very different kinds of setting live here, and the difference matters for
 * how the UI may present them:
 *
 *  - Battery-optimisation exemption is standard AOSP: readable via
 *    {@link #isIgnoringBatteryOptimizations} and requestable with a documented
 *    intent. The UI can show real state for it.
 *  - Everything else (MIUI's own power policy, autostart) is vendor-specific
 *    with NO readable state and no documented intent. We can only best-effort
 *    deep-link and let the user look. "Lock in recents" has no API at all and is
 *    text-only guidance.
 *
 * Never claim a vendor setting is "configured" — we genuinely cannot know.
 */
final class KeepAliveUtils {

    private KeepAliveUtils() {}

    /** @return true if this app is exempt from Doze / App Standby battery limits. */
    static boolean isIgnoringBatteryOptimizations(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Ask the system for the battery-optimisation exemption. Shows the platform's
     * own confirmation dialog; there is no way to grant it silently.
     *
     * Falls back to the general battery-optimisation list if the direct request is
     * unavailable (some ROMs remove it), and to app details if even that is gone.
     */
    @SuppressLint("BatteryLife") // Interception dies with the process — that IS the feature.
    static void requestIgnoreBatteryOptimizations(Context context) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + context.getPackageName()));
            context.startActivity(i);
            return;
        } catch (Throwable ignored) {
            // Fall through to the list screen.
        }
        try {
            context.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Throwable ignored) {
            openAppDetails(context);
        }
    }

    /**
     * Try to land on MIUI's per-app power policy ("省电策略"), which is a separate
     * system from AOSP battery optimisation and is what actually kills apps on
     * HyperOS. The intent is not public API and drifts between MIUI versions, so
     * every candidate is attempted in turn and app details is the last resort —
     * the same "try several, swallow per-candidate failures" shape the hook uses
     * for Android internals (see CLAUDE.md).
     */
    static void openPowerPolicySettings(Context context) {
        Intent miui = new Intent();
        miui.setComponent(new ComponentName("com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"));
        miui.putExtra("package_name", context.getPackageName());
        miui.putExtra("package_label", context.getString(R.string.app_name));
        if (tryStart(context, miui)) {
            return;
        }
        openAppDetails(context);
    }

    /** Try MIUI's autostart manager; falls back to app details. */
    static void openAutoStartSettings(Context context) {
        Intent miui = new Intent();
        miui.setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"));
        if (tryStart(context, miui)) {
            return;
        }
        openAppDetails(context);
    }

    /** This app's entry in system settings — the fallback every ROM has. */
    static void openAppDetails(Context context) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + context.getPackageName()));
            context.startActivity(i);
        } catch (Throwable ignored) {
            // Nothing left to try; the UI's text tells the user where to look.
        }
    }

    private static boolean tryStart(Context context, Intent intent) {
        try {
            if (intent.resolveActivity(context.getPackageManager()) == null) {
                return false;
            }
            context.startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
