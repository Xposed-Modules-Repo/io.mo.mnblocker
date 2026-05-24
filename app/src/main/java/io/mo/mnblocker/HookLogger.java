package io.mo.mnblocker;

import android.text.TextUtils;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * Lightweight logger.
 *
 * Everything is mirrored to {@link XposedBridge#log(String)} (visible in the
 * LSPosed log viewer) and, best-effort, appended to a file that system_server
 * is allowed to write: {@code /data/system/mnblocker/hook.log}.
 *
 * All file IO is wrapped in try/catch — logging must never crash a hook.
 */
final class HookLogger {

    static final String DIR = "/data/system/mnblocker";
    private static final String LOG_FILE = DIR + "/hook.log";
    private static final String TAG = "[MNBlocker] ";
    private static final long MAX_SIZE = 512 * 1024; // rotate at 512 KB

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private HookLogger() {}

    static void ensureDir() {
        try {
            File d = new File(DIR);
            if (!d.exists()) {
                //noinspection ResultOfMethodCallIgnored
                d.mkdirs();
            }
            // The settings app runs as a different uid and needs to traverse +
            // list this directory to read detected_channels.json / safe_mode.
            // DAC-level world rx; SELinux may still restrict on strict ROMs.
            //noinspection ResultOfMethodCallIgnored
            d.setExecutable(true, false);
            //noinspection ResultOfMethodCallIgnored
            d.setReadable(true, false);
            // World-writable so system_server (UID 1000) can create files even
            // if the directory was initially created by root via su.
            // This call succeeds when we are the owner, and silently fails
            // (returns false) otherwise — which is fine as a best-effort fix.
            //noinspection ResultOfMethodCallIgnored
            d.setWritable(true, false);
        } catch (Throwable ignored) {
        }
    }

    static void i(String msg) {
        write("I", msg, null);
    }

    static void w(String msg) {
        write("W", msg, null);
    }

    static void e(String msg, Throwable t) {
        write("E", msg, t);
    }

    /**
     * Check if the debug_logging flag file exists. When absent (the default),
     * file-based logging is skipped — only XposedBridge.log is written.
     */
    private static boolean isFileLoggingEnabled() {
        try {
            return new File(DIR + "/debug_logging").exists();
        } catch (Throwable t) {
            return false;
        }
    }

    private static synchronized void write(String level, String msg, Throwable t) {
        String line = TS.format(new Date()) + " " + level + "/ " + msg;

        // 1) Always go to the Xposed log.
        try {
            XposedBridge.log(TAG + line);
            if (t != null) {
                XposedBridge.log(t);
            }
        } catch (Throwable ignored) {
        }

        // 2) File log only when the debug_logging flag file exists.
        if (!isFileLoggingEnabled()) {
            return;
        }
        try {
            rotateIfNeeded();
            StringBuilder sb = new StringBuilder(line);
            if (t != null) {
                sb.append('\n').append(android.util.Log.getStackTraceString(t));
            }
            sb.append('\n');
            try (FileWriter fw = new FileWriter(LOG_FILE, true)) {
                fw.write(sb.toString());
            }
        } catch (Throwable ignored) {
        }
    }

    private static void rotateIfNeeded() {
        try {
            File f = new File(LOG_FILE);
            if (f.exists() && f.length() > MAX_SIZE) {
                File bak = new File(LOG_FILE + ".1");
                if (bak.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    bak.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(bak);
            }
        } catch (Throwable ignored) {
        }
    }

    static String safe(String s) {
        return TextUtils.isEmpty(s) ? "<null>" : s;
    }
}
