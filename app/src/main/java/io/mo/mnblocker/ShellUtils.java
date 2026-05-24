package io.mo.mnblocker;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/** Small root-shell helpers used only by the settings UI process. */
final class ShellUtils {

    private ShellUtils() {}

    static String suReadFile(String path) {
        try {
            ShellResult r = runSu("cat '" + path + "'");
            return r.exitCode == 0 ? r.stdout : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean suWriteFile(String path, String content) {
        try {
            String b64 = Base64.encodeToString(content.getBytes("UTF-8"), Base64.NO_WRAP);
            File parent = new File(path).getParentFile();
            String dir = parent == null ? "/data/system/mnblocker" : parent.getAbsolutePath();
            // chown to system (UID 1000) so system_server can write files
            // (hook.log, detected_channels.json, etc.) into this directory.
            // Without this, the directory is owned by root and system_server
            // gets EACCES on OnePlus / ColorOS ROMs with strict DAC checks.
            String cmd = "mkdir -p '" + dir + "'"
                    + " && chown 1000:1000 '" + dir + "'"
                    + " && chmod 0771 '" + dir + "'"
                    + " && chcon u:object_r:system_data_file:s0 '" + dir + "' 2>/dev/null; "
                    + "printf '%s' '" + b64 + "' | base64 -d > '" + path + "'"
                    + " && chmod 0666 '" + path + "'"
                    + " && chown 1000:1000 '" + path + "'";
            ShellResult r = runSu(cmd);
            return r.exitCode == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * One-shot repair: ensure /data/system/mnblocker/ is owned by system (UID
     * 1000) with correct permissions and SELinux label. Call from the settings
     * UI on every startup so existing broken installs are healed without
     * requiring a manual "su chown".
     */
    static boolean fixDirPermissions() {
        try {
            String dir = "/data/system/mnblocker";
            String cmd = "mkdir -p '" + dir + "'"
                    + " && chown -R 1000:1000 '" + dir + "'"
                    + " && chmod 0771 '" + dir + "'"
                    + " && chmod 0666 '" + dir + "'/* 2>/dev/null"
                    + "; chcon -R u:object_r:system_data_file:s0 '" + dir + "' 2>/dev/null"
                    + "; true";
            ShellResult r = runSu(cmd);
            return r.exitCode == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- debug logging helpers (used by DebugActivity) ---------------------

    private static final String FLAG_FILE = "/data/system/mnblocker/debug_logging";
    private static final String HOOK_LOG  = "/data/system/mnblocker/hook.log";

    /** Create or remove the flag file that HookLogger checks. */
    static boolean setDebugLogging(boolean enabled) {
        try {
            if (enabled) {
                String cmd = "mkdir -p '/data/system/mnblocker'"
                        + " && echo 1 > '" + FLAG_FILE + "'"
                        + " && chmod 0666 '" + FLAG_FILE + "'"
                        + " && chown 1000:1000 '" + FLAG_FILE + "'";
                return runSu(cmd).exitCode == 0;
            } else {
                return runSu("rm -f '" + FLAG_FILE + "'").exitCode == 0;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** Read current debug logging state. Tries direct file check, falls back to su. */
    static boolean isDebugLogging() {
        try {
            if (new java.io.File(FLAG_FILE).exists()) {
                return true;
            }
            ShellResult r = runSu("test -f '" + FLAG_FILE + "'");
            return r.exitCode == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Check whether hook.log exists at all. */
    static boolean hookLogExists() {
        try {
            if (new java.io.File(HOOK_LOG).canRead()
                    && new java.io.File(HOOK_LOG).length() > 0) {
                return true;
            }
            ShellResult r = runSu("test -s '" + HOOK_LOG + "'");
            return r.exitCode == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Copy hook.log to /sdcard/ so the user can share it. */
    static boolean exportHookLog() {
        try {
            String cmd = "cp '" + HOOK_LOG + "' '/sdcard/hook.log'"
                    + " && chmod 0644 '/sdcard/hook.log'";
            return runSu(cmd).exitCode == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static ShellResult runSu(String command) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
        StreamReader out = new StreamReader(p.getInputStream());
        StreamReader err = new StreamReader(p.getErrorStream());
        out.start();
        err.start();
        int code = p.waitFor();
        out.join();
        err.join();
        return new ShellResult(code, out.text(), err.text());
    }

    private static final class ShellResult {
        final int exitCode;
        final String stdout;
        @SuppressWarnings("unused")
        final String stderr;

        ShellResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static final class StreamReader extends Thread {
        private final InputStream in;
        private String text = "";

        StreamReader(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    bos.write(buf, 0, n);
                }
                text = bos.toString("UTF-8");
            } catch (Throwable ignored) {
                text = "";
            }
        }

        String text() {
            return text;
        }
    }
}
