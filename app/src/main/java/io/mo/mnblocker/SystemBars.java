package io.mo.mnblocker;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/**
 * Edge-to-edge system bars.
 *
 * The window is laid out behind the status bar and the gesture pill (both are
 * transparent, see {@code AppTheme}), so the page background runs to both screen
 * edges. The bar heights are then handed back to the layout as padding, which is
 * what keeps content clear of the clock and of the pill.
 *
 * Every screen draws its own header, so the platform ActionBar is gone — without
 * that, laying out behind the status bar would push it under the clock.
 */
final class SystemBars {

    private SystemBars() {}

    /**
     * @param root         view the window insets are dispatched to (content root)
     * @param topTarget    view padded by the status-bar height
     * @param bottomTarget view padded by the gesture-pill height; its background is
     *                     what shows behind the pill (may be the same as topTarget)
     */
    static void edgeToEdge(Activity activity, View root, View topTarget, View bottomTarget) {
        Window w = activity.getWindow();
        applyDecorFlags(w);
        applyLightBars(w);

        final int baseTop = topTarget.getPaddingTop();
        final int baseBottom = bottomTarget.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            topTarget.setPadding(
                    topTarget.getPaddingLeft(),
                    baseTop + systemBarTop(insets),
                    topTarget.getPaddingRight(),
                    topTarget.getPaddingBottom());
            bottomTarget.setPadding(
                    bottomTarget.getPaddingLeft(),
                    bottomTarget.getPaddingTop(),
                    bottomTarget.getPaddingRight(),
                    baseBottom + systemBarBottom(insets));
            return insets;
        });
        root.requestApplyInsets();
    }

    @SuppressWarnings("deprecation")
    private static void applyDecorFlags(Window w) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false);
        } else {
            View decor = w.getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Without this the system paints its own scrim over our background.
            w.setStatusBarContrastEnforced(false);
            w.setNavigationBarContrastEnforced(false);
        }
    }

    /** The app background is light, so clock and pill have to be drawn dark. */
    @SuppressWarnings("deprecation")
    private static void applyLightBars(Window w) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light, light);
            }
            return;
        }
        View decor = w.getDecorView();
        int vis = decor.getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            vis |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vis |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(vis);
    }

    private static int systemBarTop(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsets(WindowInsets.Type.systemBars()).top;
        }
        //noinspection deprecation
        return insets.getSystemWindowInsetTop();
    }

    private static int systemBarBottom(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsets(WindowInsets.Type.systemBars()).bottom;
        }
        //noinspection deprecation
        return insets.getSystemWindowInsetBottom();
    }
}
