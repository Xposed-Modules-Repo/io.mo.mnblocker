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
            String cmd = "mkdir -p '" + dir + "'"
                    + " && chmod 0755 '" + dir + "'"
                    + " && printf '%s' '" + b64 + "' | base64 -d > '" + path + "'"
                    + " && chmod 0644 '" + path + "'";
            ShellResult r = runSu(cmd);
            return r.exitCode == 0;
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
