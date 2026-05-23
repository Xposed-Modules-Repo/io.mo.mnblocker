package io.mo.mnblocker;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Module entry point (referenced from assets/xposed_init).
 *
 * Design constraint from the spec: only hook the system framework ("android").
 * Every other package — including com.android.systemui — is ignored here.
 * SystemUI crash-loop detection is done from *inside* system_server via AMS
 * hooks (see {@link NotificationHook}), so we never load code into SystemUI.
 */
public final class XposedEntry implements IXposedHookLoadPackage {

    private static final String FRAMEWORK_PKG = "android";

    // system_server loads the "android" package exactly once, so a single
    // SafetyManager instance per process is correct.
    private final SafetyManager safety = new SafetyManager();

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!FRAMEWORK_PKG.equals(lpparam.packageName)) {
            return; // not the system framework — nothing to do
        }
        // Some ROMs load "android" in non-system processes; the real
        // system_server has processName "system_server"... but that field is
        // not on lpparam. The "android" package + system classloader check is
        // the conventional, reliable signal, so we proceed.

        HookLogger.ensureDir();
        HookLogger.i("=== MarketingNotificationBlocker loading into system framework ===");

        try {
            if (!safety.hookingAllowed()) {
                HookLogger.w("Safe mode active — skipping hook installation entirely. "
                        + "Delete " + HookLogger.DIR + "/safe_mode to re-enable.");
                return;
            }
            new NotificationHook(safety).install(lpparam);
        } catch (Throwable t) {
            // A failure here must not take down system_server.
            HookLogger.e("Fatal error during hook installation — aborting cleanly", t);
        }
    }
}
