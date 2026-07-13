package io.mo.mnblocker;

import android.animation.ArgbEvaluator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Tab-bar motion, built only on framework animation APIs (no AndroidX — the
 * whole app deliberately has zero support-library dependencies).
 *
 * The feel is modelled on the iOS App Store tab bar: pressing a tab dips it,
 * releasing pops the icon back with a springy overshoot, and the selected
 * tint crossfades rather than snapping.
 */
final class Springs {

    private Springs() {}

    /**
     * Damped sine — overshoots ~7% around t=0.45 and settles by t=1.
     *
     * {@code OvershootInterpolator} would also overshoot, but its tension is a
     * single knob over a quadratic; separating damping from frequency here is
     * what makes the bounce read as a spring rather than a rubber band.
     */
    static final TimeInterpolator SPRING = new TimeInterpolator() {
        private static final double DAMPING = 6.0;
        private static final double FREQUENCY = 7.0;

        @Override
        public float getInterpolation(float t) {
            if (t <= 0f) {
                return 0f;
            }
            if (t >= 1f) {
                return 1f;
            }
            return (float) (1 - Math.exp(-DAMPING * t) * Math.cos(FREQUENCY * t));
        }
    };

    /** iOS's default ease curve. Used for the page crossfade. */
    static final TimeInterpolator EASE_OUT = new PathInterpolator(0.25f, 0.1f, 0.25f, 1f);

    private static final TimeInterpolator LINEAR = new LinearInterpolator();

    private static final long DIP_MS = 90;
    private static final long POP_MS = 260;

    /** Dip the icon, then spring it back — played on the newly selected tab. */
    static void popIcon(final View icon) {
        icon.animate().cancel();
        icon.setScaleX(1f);
        icon.setScaleY(1f);
        icon.animate()
                .scaleX(0.86f).scaleY(0.86f)
                .setDuration(DIP_MS)
                .setInterpolator(LINEAR)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        icon.animate()
                                .scaleX(1f).scaleY(1f)
                                .setDuration(POP_MS)
                                .setInterpolator(SPRING)
                                .start();
                    }
                })
                .start();
    }

    /** Crossfade a tab's icon tint and label colour together. */
    static void tint(final ImageView icon, final TextView label,
                     int from, int to, long durationMs) {
        ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        a.setDuration(durationMs);
        a.setInterpolator(EASE_OUT);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                int c = (Integer) animation.getAnimatedValue();
                icon.setColorFilter(c);
                label.setTextColor(c);
            }
        });
        a.start();
    }

    /**
     * Press feedback for a tab container: dips while held, restores on release.
     *
     * Returns {@code false} so the view's own OnClickListener still fires — this
     * listener only draws, it never consumes the touch.
     */
    static View.OnTouchListener pressFeedback(final View tab) {
        return new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        tab.animate().scaleX(0.92f).scaleY(0.92f).alpha(0.6f)
                                .setDuration(80).setInterpolator(EASE_OUT).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        tab.animate().scaleX(1f).scaleY(1f).alpha(1f)
                                .setDuration(140).setInterpolator(EASE_OUT).start();
                        break;
                    default:
                        break;
                }
                return false;
            }
        };
    }
}
