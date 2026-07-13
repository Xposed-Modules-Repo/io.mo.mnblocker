package io.mo.mnblocker;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import java.util.Locale;

/**
 * UI language selection.
 *
 * This is a pure settings-app concern — the hook never renders text, so unlike
 * {@link RegexConfig} this has nothing to do with the XSharedPreferences /
 * config.json bridge and lives entirely in a private, normal SharedPreferences
 * file owned by this app's own uid.
 *
 * On first launch (no stored preference yet) the system locale decides the
 * default: Chinese systems get "zh", everything else gets "en". Once the user
 * has picked a language explicitly (or the default has been resolved once),
 * that choice sticks regardless of what the system locale does afterwards.
 */
final class LocaleManager {

    static final String LANG_ZH = "zh";
    static final String LANG_EN = "en";

    private static final String PREFS_NAME = "mnblocker_ui_prefs";
    private static final String KEY_LANGUAGE = "language";

    private LocaleManager() {}

    /** The active language, resolving and persisting the first-launch default if unset. */
    static String getLanguage(Context context) {
        SharedPreferences sp = prefs(context);
        String stored = sp.getString(KEY_LANGUAGE, null);
        if (stored != null) {
            return stored;
        }
        String detected = defaultForSystemLocale();
        sp.edit().putString(KEY_LANGUAGE, detected).apply();
        return detected;
    }

    static void setLanguage(Context context, String lang) {
        prefs(context).edit().putString(KEY_LANGUAGE, lang).apply();
    }

    private static String defaultForSystemLocale() {
        Locale system = Locale.getDefault();
        return LANG_ZH.equals(system.getLanguage()) ? LANG_ZH : LANG_EN;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Wrap a context so its resources resolve against the chosen language,
     * regardless of the device's actual system locale. Call from every
     * Activity's {@code attachBaseContext}.
     */
    static Context wrap(Context base) {
        String lang = getLanguage(base);
        Locale locale = new Locale(lang);
        Locale.setDefault(locale);

        Resources res = base.getResources();
        Configuration config = new Configuration(res.getConfiguration());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale);
            return base.createConfigurationContext(config);
        }
        //noinspection deprecation
        config.locale = locale;
        //noinspection deprecation
        res.updateConfiguration(config, res.getDisplayMetrics());
        return base;
    }
}
