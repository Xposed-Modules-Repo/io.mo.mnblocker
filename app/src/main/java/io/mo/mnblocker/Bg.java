package io.mo.mnblocker;

import android.app.Activity;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The settings UI's background worker.
 *
 * Everything under /data/system is reached through {@link ShellUtils} — i.e. by
 * spawning an {@code su} process, which blocks for 100 ms to well over a second
 * (longer still on the first call, when the root manager prompts). None of that
 * may happen on the main thread.
 *
 * Deliberately a SINGLE thread: config reads and writes are last-write-wins on
 * one JSON file, so serialising them keeps a save from racing a reload.
 */
final class Bg {

    private static final Executor IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mnblocker-io");
        t.setDaemon(true);
        return t;
    });

    private Bg() {}

    interface Producer<T> {
        T get();
    }

    interface Consumer<T> {
        void accept(T value);
    }

    /** Fire-and-forget work off the main thread. */
    static void run(Runnable work) {
        IO.execute(work);
    }

    /**
     * Produce a value off the main thread, then consume it back on the main one.
     * The callback is dropped if the activity is gone by the time the work lands.
     */
    static <T> void load(final Activity activity, final Producer<T> work,
                         final Consumer<T> then) {
        IO.execute(() -> {
            final T value;
            try {
                value = work.get();
            } catch (Throwable t) {
                return;
            }
            if (isGone(activity)) {
                return;
            }
            activity.runOnUiThread(() -> {
                if (!isGone(activity)) {
                    then.accept(value);
                }
            });
        });
    }

    private static boolean isGone(Activity a) {
        return a == null || a.isFinishing() || a.isDestroyed();
    }
}
