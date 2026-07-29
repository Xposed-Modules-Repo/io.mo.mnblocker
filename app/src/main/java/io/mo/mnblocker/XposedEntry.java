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
    private static final String SYSTEMUI_PKG = "com.android.systemui";

    // system_server loads the "android" package exactly once, so a single
    // SafetyManager instance per process is correct.
    private final SafetyManager safety = new SafetyManager();

    // Retained for the process lifetime so safe-mode FileObservers
    // and hooks are not garbage-collected.
    @SuppressWarnings("FieldCanBeLocal")
    private NotificationHook hook;
    @SuppressWarnings("FieldCanBeLocal")
    private SystemUiHook systemUiHook;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (FRAMEWORK_PKG.equals(lpparam.packageName)) {
            HookLogger.ensureDir();
            HookLogger.i("=== MarketingNotificationBlocker loading into system framework ===");

            try {
                hook = new NotificationHook(safety);
                hook.install(lpparam);
            } catch (Throwable t) {
                HookLogger.e("Fatal error during framework hook installation — aborting cleanly", t);
            }
        } else if (SYSTEMUI_PKG.equals(lpparam.packageName)) {
            HookLogger.ensureDir();
            HookLogger.i("=== MarketingNotificationBlocker loading into SystemUI ===");

            try {
                systemUiHook = new SystemUiHook(safety);
                systemUiHook.install(lpparam);
            } catch (Throwable t) {
                HookLogger.e("Fatal error during SystemUI hook installation — aborting cleanly", t);
            }
        }
    }
}
