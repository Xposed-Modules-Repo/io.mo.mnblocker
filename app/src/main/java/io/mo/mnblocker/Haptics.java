package io.mo.mnblocker;

import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * Haptic vocabulary for the settings UI, with API-level fallbacks.
 *
 * Deliberately small and only ever fired by a user's own action, in step with
 * the visual change it confirms:
 *   tick    — a selection moved (tab, segment)
 *   toggle  — a switch flipped
 *   confirm — an action succeeded (save, batch apply)
 *   reject  — an action was refused (invalid regex, nothing selected)
 *
 * All of these go through {@link View#performHapticFeedback}, which already
 * honours the system "touch feedback" setting — nothing here bypasses it.
 */
final class Haptics {

    private Haptics() {}

    static void tick(View v) {
        if (Build.VERSION.SDK_INT >= 34) {
            v.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK);
        } else {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    static void toggle(View v, boolean on) {
        if (Build.VERSION.SDK_INT >= 34) {
            v.performHapticFeedback(on
                    ? HapticFeedbackConstants.TOGGLE_ON
                    : HapticFeedbackConstants.TOGGLE_OFF);
        } else {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    static void confirm(View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        } else {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    static void reject(View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            v.performHapticFeedback(HapticFeedbackConstants.REJECT);
        } else {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    }
}
