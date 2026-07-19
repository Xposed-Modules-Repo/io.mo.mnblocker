package io.mo.mnblocker;

import android.os.SystemClock;

import java.io.File;
import java.io.FileWriter;

/**
 * Liveness beacon for the Xposed hook, written in system_server and read by the
 * settings UI.
 *
 * The two processes share no IPC, so the UI cannot ask the hook "are you
 * there?". Before this existed the root-mode status line simply asserted
 * "running" — it stayed green with the module disabled in LSPosed, with the
 * "Android System Framework" scope unticked, and after an APK replace with no
 * reboot. That is the same lie the root-free banner used to tell by reporting on
 * the notification-access grant instead of the actual binding.
 *
 * So the hook drops a beacon when its hooks go in: the wall clock at that moment
 * and the module version it was built from. The UI then distinguishes:
 *
 *  - no beacon, or one written before this boot -> the hook did not load this
 *    boot (module off, scope unticked, or not rebooted since install)
 *  - beacon version != installed version -> an older hook is live inside
 *    system_server; the freshly installed APK needs a reboot to take effect
 *  - otherwise -> a hook matching this APK is live
 */
final class HookAliveStore {

    static final String FILE = HookLogger.DIR + "/hook_alive";

    /**
     * Slack on the "written before this boot" test, for wall-clock corrections.
     *
     * The beacon is written seconds into boot, so its timestamp normally sits
     * just after the derived boot instant. But the clock can be corrected (NTP,
     * RTC drift) after the beacon is written, which moves the derived boot
     * instant forward and would make a perfectly live hook look stale.
     *
     * The cost of the slack is a false "alive" if the user reboots within 90s of
     * the previous boot's beacon AND the hook does not load on the new boot.
     * That is narrow enough to accept; erring the other way would show a scary
     * red banner every time the clock nudges forward.
     */
    private static final long BOOT_SLACK_MS = 90_000L;

    private HookAliveStore() {}

    /** What the UI concluded about the hook. */
    enum State {
        /** No beacon from this boot: not enabled, not scoped, or not rebooted. */
        NOT_LOADED,
        /** A hook is live, but built from a different APK than the installed one. */
        VERSION_MISMATCH,
        /** A hook matching this APK is live in system_server. */
        ALIVE
    }

    // ------------------------------------------------------------------
    // hook side (system_server)
    // ------------------------------------------------------------------

    /** Drop the beacon. Never throws — this runs inside system_server. */
    static void mark() {
        try {
            HookLogger.ensureDir();
            File f = new File(FILE);
            try (FileWriter fw = new FileWriter(f, false)) {
                fw.write(System.currentTimeMillis() + "\n" + BuildConfig.VERSION_NAME);
            }
            // The settings app is a different uid and only ever reads this.
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
        } catch (Throwable t) {
            HookLogger.e("Failed to write hook liveness beacon", t);
        }
    }

    // ------------------------------------------------------------------
    // app side (settings UI)
    // ------------------------------------------------------------------

    /**
     * Read the beacon and judge it. Blocking (may spawn su) — call off the main
     * thread, via {@link Bg}.
     */
    static State readForApp() {
        String raw = readRaw();
        if (raw == null) {
            return State.NOT_LOADED;
        }

        String[] lines = raw.trim().split("\n");
        long written;
        try {
            written = Long.parseLong(lines[0].trim());
        } catch (Throwable t) {
            return State.NOT_LOADED; // unreadable beacon is no beacon
        }

        long bootWall = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        if (written < bootWall - BOOT_SLACK_MS) {
            return State.NOT_LOADED; // left over from an earlier boot
        }

        String version = lines.length > 1 ? lines[1].trim() : "";
        return BuildConfig.VERSION_NAME.equals(version)
                ? State.ALIVE
                : State.VERSION_MISMATCH;
    }

    /**
     * Direct read first, su only when the miss is inconclusive — a beacon that is
     * genuinely absent (module never loaded) is the answer we are looking for,
     * not a permission failure, and shelling out to rediscover that would spawn
     * su on the healthy "module is off" path.
     *
     * The direct attempt must be an actual open, the way ConfigFileStore does it.
     * Gating it on {@link File#canRead()} first was a live bug: canRead() is an
     * access(2) call, which on a SELinux ROM checks DAC only — the beacon is 0666,
     * so it answered "yes", the open that followed was denied by SELinux
     * (untrusted_app reading system_data_file), and the exception left us
     * returning null without ever reaching the su fallback. A perfectly live hook
     * then reported NOT_LOADED forever.
     */
    private static String readRaw() {
        String direct = readDirect();
        if (direct != null) {
            return direct;
        }
        if (ShellUtils.missIsConclusive(FILE)) {
            return null; // directory visible, no beacon => hook never ran
        }
        return ShellUtils.suReadFile(FILE);
    }

    /** Plain read; null on "absent" and on "denied" alike — the caller sorts it out. */
    private static String readDirect() {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(new File(FILE))) {
            byte[] buf = new byte[128];
            int n = fis.read(buf);
            return n > 0 ? new String(buf, 0, n, "UTF-8") : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
