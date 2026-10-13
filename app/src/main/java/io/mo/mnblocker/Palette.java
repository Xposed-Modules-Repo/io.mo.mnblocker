package io.mo.mnblocker;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;

/**
 * Colour tokens for the settings UI — the only place a colour literal may live.
 *
 * Roles follow Material 3 naming so they read the same as any M3 spec. Three
 * sources, picked per activity in {@link #of}:
 *
 *  - Android 12+: dynamic colour from the wallpaper ({@code system_accent1_*} /
 *    {@code system_neutral*_*}). Those palettes are tone-indexed (_600 = tone 40,
 *    _100 = tone 90, ...), so a role mapped to a fixed tone keeps a predictable
 *    contrast whatever the hue. The mapping is still verified at runtime against
 *    {@link #MIN_TEXT} / {@link #MIN_UI} and abandoned for the static set if an
 *    OEM overlay ships palettes that break it.
 *  - Below 12, or when the check fails: the static brand-blue set.
 *  - Semantic roles (danger / success / warn) are static in every case: they
 *    carry meaning, so they must not drift with the wallpaper.
 *
 * Verified ratios of the static sets (WCAG 2.x), body text >= 4.5, UI >= 3:
 *   light  onSurface/surface 17.1  onSurfaceVariant/surface 7.2
 *          outline/surface 4.0     onPrimary/primary 5.8   primary/surface 5.8
 *   dark   onSurface/surface 13.3  onSurfaceVariant/surface 9.6
 *          outline/surface 5.5     onPrimary/primary 7.8   primary/surface 9.8
 */
final class Palette {

    static final double MIN_TEXT = 4.5;
    static final double MIN_UI = 3.0;

    final boolean dark;
    final boolean dynamic;

    /** Page background. */
    final int bg;
    /** Cards and the tab bar. */
    final int surface;
    /** Fills inset into a card: inputs, tiles, skeletons. */
    final int surfaceVariant;
    /**
     * Floating surfaces (popups). In the dark a shadow is invisible, so height
     * is shown by a lighter tone instead.
     */
    final int surfaceHigh;
    final int onSurface;
    /** Secondary text; still >= 4.5:1 on every surface above. */
    final int onSurfaceVariant;
    /** Input borders and other boundaries that carry meaning (>= 3:1). */
    final int outline;
    /** Decorative hairlines only. */
    final int outlineVariant;

    final int primary;
    final int onPrimary;
    final int primaryContainer;
    final int onPrimaryContainer;

    final int danger;
    final int dangerContainer;
    final int onDangerContainer;
    final int success;
    final int successContainer;
    final int onSuccessContainer;
    final int warnContainer;
    final int onWarnContainer;

    /** Snackbar. */
    final int inverseSurface;
    final int onInverseSurface;

    /** Ripple / pressed overlay, alpha baked in. */
    final int ripple;

    private Palette(boolean dark, boolean dynamic, int[] n, int[] p) {
        this.dark = dark;
        this.dynamic = dynamic;
        bg = n[0];
        surface = n[1];
        surfaceVariant = n[2];
        surfaceHigh = dark ? n[2] : n[1];
        onSurface = n[3];
        onSurfaceVariant = n[4];
        outline = n[5];
        outlineVariant = n[6];
        inverseSurface = n[7];
        onInverseSurface = n[8];

        primary = p[0];
        onPrimary = p[1];
        primaryContainer = p[2];
        onPrimaryContainer = p[3];

        if (dark) {
            danger = 0xFFFFB4AB;
            dangerContainer = 0xFF7A1D17;
            onDangerContainer = 0xFFFFDAD6;
            success = 0xFF8DD8A2;
            successContainer = 0xFF13472A;
            onSuccessContainer = 0xFFB8F2C8;
            warnContainer = 0xFF4A3505;
            onWarnContainer = 0xFFFFDFA3;
        } else {
            danger = 0xFFBA1A1A;
            dangerContainer = 0xFFFFDAD6;
            onDangerContainer = 0xFF5C0007;
            success = 0xFF1B6C3A;
            successContainer = 0xFFCDEFD6;
            onSuccessContainer = 0xFF0B3A1C;
            warnContainer = 0xFFFFEDC7;
            onWarnContainer = 0xFF5A3A00;
        }
        ripple = withAlpha(onSurface, dark ? 0.16f : 0.12f);
    }

    // bg, surface, surfaceVariant, onSurface, onSurfaceVariant, outline,
    // outlineVariant, inverseSurface, onInverseSurface
    private static final int[] LIGHT_NEUTRAL = {
            0xFFF4F6FB, 0xFFFFFFFF, 0xFFECEFF6, 0xFF171C26, 0xFF4F5869,
            0xFF767F90, 0xFFE1E5EE, 0xFF2E313A, 0xFFEFF0F7};
    private static final int[] DARK_NEUTRAL = {
            0xFF111318, 0xFF1B1E25, 0xFF262A33, 0xFFE3E5EE, 0xFFBFC5D2,
            0xFF8D94A3, 0xFF353A45, 0xFFE3E5EE, 0xFF2E313A};
    // primary, onPrimary, primaryContainer, onPrimaryContainer
    private static final int[] LIGHT_PRIMARY = {0xFF2F5BD8, 0xFFFFFFFF, 0xFFDCE3FF, 0xFF0A1F5C};
    private static final int[] DARK_PRIMARY = {0xFFB4C5FF, 0xFF00287A, 0xFF22409E, 0xFFDCE3FF};

    static Palette of(Context context) {
        boolean dark = isDark(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Palette p = dynamic(context, dark);
                if (p.passes()) {
                    return p;
                }
            } catch (Throwable ignored) {
                // Some OEM builds strip the system palette resources.
            }
        }
        return dark
                ? new Palette(true, false, DARK_NEUTRAL, DARK_PRIMARY)
                : new Palette(false, false, LIGHT_NEUTRAL, LIGHT_PRIMARY);
    }

    static boolean isDark(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.S)
    private static Palette dynamic(Context c, boolean dark) {
        int n1_10 = c.getColor(android.R.color.system_neutral1_10);
        int n1_50 = c.getColor(android.R.color.system_neutral1_50);
        int n1_100 = c.getColor(android.R.color.system_neutral1_100);
        int n1_800 = c.getColor(android.R.color.system_neutral1_800);
        int n1_900 = c.getColor(android.R.color.system_neutral1_900);
        int n2_100 = c.getColor(android.R.color.system_neutral2_100);
        int n2_200 = c.getColor(android.R.color.system_neutral2_200);
        int n2_400 = c.getColor(android.R.color.system_neutral2_400);
        int n2_500 = c.getColor(android.R.color.system_neutral2_500);
        int n2_700 = c.getColor(android.R.color.system_neutral2_700);
        int a1_0 = c.getColor(android.R.color.system_accent1_0);
        int a1_100 = c.getColor(android.R.color.system_accent1_100);
        int a1_200 = c.getColor(android.R.color.system_accent1_200);
        int a1_600 = c.getColor(android.R.color.system_accent1_600);
        int a1_700 = c.getColor(android.R.color.system_accent1_700);
        int a1_800 = c.getColor(android.R.color.system_accent1_800);
        int a1_900 = c.getColor(android.R.color.system_accent1_900);

        if (dark) {
            // The palettes step in tones of 10; M3's dark surfaces sit between
            // tone 10 and 20, so those two are blended rather than snapped.
            int surface = blend(n1_900, n1_800, 0.45f);
            int[] n = {n1_900, surface, blend(n1_900, n1_800, 0.85f), n1_100, n2_200,
                    n2_400, n2_700, n1_100, n1_800};
            int[] p = {a1_200, a1_800, a1_700, a1_100};
            return new Palette(true, true, n, p);
        }
        int[] n = {n1_50, n1_10, blend(n1_50, n1_100, 0.5f), n1_900, n2_700,
                n2_500, n2_100, n1_800, n1_50};
        int[] p = {a1_600, a1_0, a1_100, a1_900};
        return new Palette(false, true, n, p);
    }

    /** The pairs the UI actually draws; a dynamic set that fails any of them is rejected. */
    private boolean passes() {
        return contrast(onSurface, surface) >= MIN_TEXT
                && contrast(onSurfaceVariant, surface) >= MIN_TEXT
                && contrast(onSurfaceVariant, bg) >= MIN_TEXT
                && contrast(onSurfaceVariant, surfaceVariant) >= MIN_TEXT
                && contrast(onPrimary, primary) >= MIN_TEXT
                && contrast(onPrimaryContainer, primaryContainer) >= MIN_TEXT
                && contrast(primary, surface) >= MIN_TEXT
                && contrast(outline, surface) >= MIN_UI
                && contrast(onInverseSurface, inverseSurface) >= MIN_TEXT;
    }

    /** WCAG 2.x contrast ratio, 1..21. */
    static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int c) {
        return 0.2126 * channel(Color.red(c))
                + 0.7152 * channel(Color.green(c))
                + 0.0722 * channel(Color.blue(c));
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    static int withAlpha(int color, float alpha) {
        return (Math.round(alpha * 255) << 24) | (color & 0x00FFFFFF);
    }

    /** Straight RGB lerp — only ever between two tones of one palette, where it is close enough. */
    static int blend(int from, int to, float t) {
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }
}
