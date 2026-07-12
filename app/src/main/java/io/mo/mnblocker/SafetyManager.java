package io.mo.mnblocker;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Safety net.
 *
 * The notification hook lives inside system_server. A bad interaction with a
 * channel can make SystemUI ("系统界面") crash-loop. To avoid bricking the UI we:
 *
 *   - watch SystemUI process deaths reported by AMS (see {@link NotificationHook}),
 *   - if SystemUI restarts MORE THAN TWICE within {@link #WINDOW_MS},
 *     we trip safe mode,
 *   - safe mode writes a persistent flag file. While the flag exists the module
 *     defers installing the notification hooks (staying inert), and disables
 *     any already-installed hooks in-memory immediately.
 *
 * The flag is intentionally sticky: it must be cleared by the user (from the
 * app, or by deleting /data/system/mnblocker/safe_mode). Better an un-hooked
 * phone than a boot-looping one. Clearing it does NOT require a reboot:
 * {@link NotificationHook} watches this file and, via {@link #syncFromDisk()},
 * re-enables the hooks in place when it is removed.
 *
 * All state that must survive a system_server restart goes to /data/system,
 * which the system_server SELinux domain is permitted to write.
 */
final class SafetyManager {

    /** "超过两次" -> the 3rd restart inside the window trips it. */
    private static final int MAX_RESTARTS = 2;
    private static final long WINDOW_MS = 30_000L; // 30 seconds

    private static final String FLAG_FILE = HookLogger.DIR + "/safe_mode";

    private final Deque<Long> systemUiDeaths = new ArrayDeque<>();
    private volatile boolean safeModeTripped;

    SafetyManager() {
        this.safeModeTripped = readFlag();
        if (safeModeTripped) {
            HookLogger.w("Safe mode flag present on startup — hooks will stay disabled "
                    + "until /data/system/mnblocker/safe_mode is removed.");
        }
    }

    /** @return true if hooking is currently allowed. */
    boolean hookingAllowed() {
        return !safeModeTripped;
    }

    boolean isSafeMode() {
        return safeModeTripped;
    }

    /**
     * Re-read the on-disk flag into the in-memory state. Called when the flag
     * file changes at runtime (e.g. the settings app cleared it), so hooks can
     * resume without a reboot. Returns the resulting safe-mode state.
     */
    synchronized boolean syncFromDisk() {
        boolean onDisk = readFlag();
        if (onDisk != safeModeTripped) {
            safeModeTripped = onDisk;
            HookLogger.i("Safe mode flag re-synced from disk: "
                    + (onDisk ? "tripped" : "cleared"));
        }
        return safeModeTripped;
    }

    /**
     * Report that the SystemUI process died. Called from the AMS hook.
     * Synchronized: AMS can report deaths from several threads.
     */
    synchronized void onSystemUiDied() {
        long now = System.currentTimeMillis();
        systemUiDeaths.addLast(now);

        // prune anything outside the sliding window
        while (!systemUiDeaths.isEmpty() && now - systemUiDeaths.peekFirst() > WINDOW_MS) {
            systemUiDeaths.pollFirst();
        }

        int restartsInWindow = systemUiDeaths.size();
        HookLogger.w("SystemUI death observed (" + restartsInWindow
                + " within " + (WINDOW_MS / 1000) + "s window)");

        if (restartsInWindow > MAX_RESTARTS && !safeModeTripped) {
            tripSafeMode("SystemUI restarted " + restartsInWindow
                    + " times within " + (WINDOW_MS / 1000) + "s");
        }
    }

    private void tripSafeMode(String reason) {
        safeModeTripped = true;
        HookLogger.e("!!! SAFE MODE TRIPPED !!! reason=" + reason
                + " — disabling all notification hooks now and on next boot.", null);
        writeFlag(reason);
    }

    // ----- flag file persistence -----

    private boolean readFlag() {
        try {
            return new File(FLAG_FILE).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    private void writeFlag(String reason) {
        try {
            HookLogger.ensureDir();
            try (FileWriter fw = new FileWriter(FLAG_FILE, false)) {
                fw.write("tripped_at=" + System.currentTimeMillis() + "\n");
                fw.write("reason=" + reason + "\n");
            }
        } catch (Throwable t) {
            HookLogger.e("Could not persist safe_mode flag", t);
        }
    }

}
