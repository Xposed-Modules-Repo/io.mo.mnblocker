package io.mo.mnblocker;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Package icon / label cache shared by the settings screens.
 *
 * {@code PackageManager.getApplicationIcon} is a binder round-trip plus an APK
 * asset decode. It used to be called straight from every row bind — inside
 * {@code ListView.getView()} (so, on every scroll frame) and from the channel
 * list rebuild (up to 1000 rows). Caching it turns all of that into a map hit.
 *
 * Static on purpose: the two activities show overlapping sets of packages, and
 * the cache should survive navigating between them.
 */
final class AppIconCache {

    /** Plenty for a screenful of rows plus the ranking; icons are not tiny. */
    private static final int MAX_ICONS = 64;

    private static final LruCache<String, Drawable> ICONS = new LruCache<>(MAX_ICONS);
    private static final Map<String, String> LABELS = new ConcurrentHashMap<>();

    private AppIconCache() {}

    /**
     * Show {@code pkg}'s icon in {@code view}.
     *
     * A cached icon is applied synchronously, so re-renders and scrolling never
     * flicker. A miss shows the generic icon and loads the real one on {@link Bg}.
     *
     * The view is tagged with the package it is currently showing and the tag is
     * re-checked when the load lands: rows get recycled, and without that check a
     * slow load would paint its icon onto whatever row had taken the view over.
     */
    static void bindIcon(Activity activity, ImageView view, final String pkg) {
        view.setTag(pkg);

        Drawable cached = ICONS.get(pkg);
        if (cached != null) {
            view.setImageDrawable(cached);
            return;
        }

        view.setImageDrawable(fallbackIcon(activity));
        Bg.load(activity,
                () -> loadIcon(activity, pkg),
                icon -> {
                    if (icon != null) {
                        ICONS.put(pkg, icon);
                    }
                    if (pkg.equals(view.getTag())) {
                        view.setImageDrawable(icon != null ? icon : fallbackIcon(activity));
                    }
                });
    }

    /**
     * The app's display name, cached. Cheap enough to resolve synchronously — it
     * is a label lookup, not an asset decode — and callers render it inline.
     */
    static String label(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return "<unknown>";
        }
        String cached = LABELS.get(pkg);
        if (cached != null) {
            return cached;
        }
        String label = pkg;
        try {
            PackageManager pm = context.getPackageManager();
            CharSequence l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            if (l != null && l.length() > 0) {
                label = l.toString();
            }
        } catch (Throwable ignored) {
            // Uninstalled or hidden by package-visibility rules: fall back to the id.
        }
        LABELS.put(pkg, label);
        return label;
    }

    private static Drawable loadIcon(Context context, String pkg) {
        try {
            return context.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static Drawable fallbackIcon(Context context) {
        return context.getResources().getDrawable(android.R.drawable.sym_def_app_icon);
    }
}
