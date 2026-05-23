package io.mo.mnblocker;

import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Installs the actual hooks inside the system framework (system_server).
 *
 * Two things are hooked, both living in the "android" package:
 *
 *  1. The notification-channel store. Whenever an app creates or updates a
 *     channel, we test its id / name / description against the regex rules and,
 *     on a match, force importance to IMPORTANCE_NONE — i.e. the channel is
 *     silenced ("一键关闭").
 *
 *  2. ActivityManagerService app-death, purely to feed {@link SafetyManager}
 *     so it can detect a SystemUI crash loop. We do NOT hook anything inside
 *     SystemUI itself — only the framework, as required.
 *
 * Compatibility:
 *  - "api101" / modern Android: channel store is
 *    com.android.server.notification.PreferencesHelper.
 *  - "legacy" (Android 8.0-8.1): it was com.android.server.notification.RankingHelper.
 *  Method overloads differ across releases, so we hook *every* overload by name
 *  (hookAllMethods) and locate the NotificationChannel argument by type instead
 *  of relying on a fixed parameter index.
 */
final class NotificationHook {

    private static final String SYSTEMUI_PKG = "com.android.systemui";

    private final SafetyManager safety;
    private volatile RegexConfig config;
    /** Live record of every channel seen, surfaced to the settings UI. */
    private final DetectedChannelsStore detectedStore = new DetectedChannelsStore();
    private final OriginalChannelStateStore originalStateStore = new OriginalChannelStateStore();

    NotificationHook(SafetyManager safety) {
        this.safety = safety;
    }

    void install(LoadPackageParam lpparam) {
        // Loaded once here; the callback then calls config.reloadIfChanged() on
        // every event, so UI edits (rules + per-channel switches) take effect on
        // a channel's next create/update without needing a reboot.
        this.config = RegexConfig.load();

        // The channel store is always hooked: even when the master switch is off
        // we still want to *populate the detected list* for the UI — we just
        // won't modify importance in that case (decided inside the callback).
        hookChannelStore(lpparam);
        hookChannelQueries(lpparam);

        // Safety watcher is installed regardless of the master switch.
        hookAmsForSafety(lpparam);

        HookLogger.i("NotificationHook.install() finished for package=" + lpparam.packageName);
    }

    // ------------------------------------------------------------------
    // (1) notification channel store
    // ------------------------------------------------------------------

    private void hookChannelStore(LoadPackageParam lpparam) {
        String[] candidateClasses = {
                "com.android.server.notification.PreferencesHelper", // modern
                "com.android.server.notification.RankingHelper"      // legacy (8.0/8.1)
        };
        String[] candidateMethods = {
                "createNotificationChannel",
                "updateNotificationChannel"
        };

        int hookedMethods = 0;
        for (String className : candidateClasses) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
            if (clazz == null) {
                continue;
            }
            for (String method : candidateMethods) {
                try {
                    XposedBridge.hookAllMethods(clazz, method, channelCallback);
                    hookedMethods++;
                    HookLogger.i("Hooked " + className + "#" + method);
                } catch (Throwable t) {
                    HookLogger.e("Failed to hook " + className + "#" + method, t);
                }
            }
        }

        if (hookedMethods == 0) {
            HookLogger.e("No channel-store method could be hooked — "
                    + "Android version may be unsupported.", null);
        }
    }

    /**
     * Observe the read-side APIs used by Android Settings. This backfills
     * channels that already existed before the module was enabled and therefore
     * never pass through create/update while the hook is active.
     */
    private void hookChannelQueries(LoadPackageParam lpparam) {
        int hooked = 0;
        String[] classes = {
                "com.android.server.notification.NotificationManagerService",
                "com.android.server.notification.NotificationManagerService$BinderService",
                "com.android.server.notification.PreferencesHelper",
                "com.android.server.notification.RankingHelper"
        };
        String[] methods = {
                "getNotificationChannel",
                "getNotificationChannels",
                "getNotificationChannelsForPackage",
                "getConversationNotificationChannel",
                "getConversations",
                "getConversationsForPackage",
                "getNotificationChannelGroup",
                "getNotificationChannelGroups",
                "getNotificationChannelGroupsForPackage",
                "getNotificationChannelGroupForPackage"
        };
        for (String className : classes) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
            if (clazz == null) {
                continue;
            }
            for (String method : methods) {
                try {
                    XposedBridge.hookAllMethods(clazz, method, channelQueryCallback);
                    hooked++;
                    HookLogger.i("Hooked " + className + "#" + method + " for channel backfill");
                } catch (Throwable ignored) {
                    // Method availability varies across Android versions and ROMs.
                }
            }
        }

        if (hooked == 0) {
            HookLogger.w("Could not hook any NotificationManagerService channel query method.");
        }
    }

    private final XC_MethodHook channelCallback = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            // Re-check safe mode on every event: it can be tripped at runtime.
            if (!safety.hookingAllowed()) {
                return;
            }
            RegexConfig cfg = config;
            if (cfg == null) {
                return;
            }

            try {
                // Pick up any edits the settings UI made (rules + per-channel
                // switches) since the last event.
                cfg.reloadIfChanged();

                NotificationChannel channel = findChannelArg(param.args);
                if (channel == null) {
                    return;
                }

                String pkg = describeCaller(param.args);
                String id = channel.getId();
                CharSequence nameCs = channel.getName();
                String name = nameCs == null ? null : nameCs.toString();
                String desc = cfg.isMatchDescription() ? channel.getDescription() : null;
                int before = channel.getImportance();

                String matchedRule = cfg.firstMatch(id, name, desc);
                boolean regexMatched = matchedRule != null;

                // ---- always record the channel for the settings UI list ----
                recordObservedChannel(pkg, channel, before, regexMatched);

                // ---- decide: per-channel override wins over regex ----
                Boolean override = cfg.overrideFor(pkg, id);
                boolean shouldBlock;
                String decisionReason;
                if (override != null) {
                    shouldBlock = override;
                    decisionReason = "override(" + (override ? "block" : "allow") + ")";
                } else {
                    shouldBlock = regexMatched;
                    decisionReason = regexMatched ? "regex:" + matchedRule : "no-match";
                }

                if (!cfg.isMasterEnabled()) {
                    // List is populated, but the master switch forbids changes.
                    if (shouldBlock) {
                        HookLogger.i("Master OFF — would block but skipping"
                                + " | caller=" + pkg + " | id=" + HookLogger.safe(id)
                                + " | reason=" + decisionReason);
                    }
                    restoreChannelIfAllowed(pkg, channel, before, override, true, decisionReason);
                    return;
                }

                if (!shouldBlock) {
                    // Either no rule matched, or the user explicitly allowed it.
                    if (override != null) {
                        HookLogger.i("ALLOWED by user override"
                                + " | caller=" + pkg + " | id=" + HookLogger.safe(id));
                    }
                    restoreChannelIfAllowed(pkg, channel, before, override, false, decisionReason);
                    return;
                }

                if (before == NotificationManager.IMPORTANCE_NONE) {
                    HookLogger.i("Channel already silenced, no change"
                            + " | caller=" + pkg + " | id=" + HookLogger.safe(id)
                            + " | reason=" + decisionReason);
                    return;
                }

                // ---- the actual "关闭" ----
                originalStateStore.saveIfAbsent(pkg, channel, before);
                channel.setImportance(NotificationManager.IMPORTANCE_NONE);
                try {
                    channel.setShowBadge(false);
                    channel.setBypassDnd(false);
                } catch (Throwable ignored) {
                    // non-essential extras; ignore if a ROM strips them
                }

                HookLogger.i("BLOCKED notification channel"
                        + " | caller=" + pkg
                        + " | id=" + HookLogger.safe(id)
                        + " | name=" + HookLogger.safe(name)
                        + " | reason=" + decisionReason
                        + " | importance " + before + " -> 0");
            } catch (Throwable t) {
                // Never let our logic break channel creation for the user.
                HookLogger.e("channelCallback error — passing through untouched", t);
            }
        }
    };

    private final XC_MethodHook channelQueryCallback = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!safety.hookingAllowed()) {
                return;
            }
            RegexConfig cfg = config;
            if (cfg == null) {
                return;
            }

            try {
                cfg.reloadIfChanged();
                String pkg = describeCaller(param.args);
                if ("<unknown>".equals(pkg)) {
                    return;
                }
                int[] count = new int[]{0};
                int[] changed = new int[]{0};
                collectAndApplyChannels(pkg, param.getResult(), cfg, new HashSet<>(), count, changed);
                if (changed[0] > 0) {
                    persistNotificationPreferences(param.thisObject);
                }
                if (count[0] > 0) {
                    HookLogger.i("Backfilled " + count[0] + " notification channels"
                            + " | caller=" + pkg
                            + " | changed=" + changed[0]);
                }
            } catch (Throwable t) {
                HookLogger.e("channelQueryCallback error", t);
            }
        }
    };

    /** Locate the NotificationChannel argument regardless of overload shape. */
    private static NotificationChannel findChannelArg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a instanceof NotificationChannel) {
                return (NotificationChannel) a;
            }
        }
        return null;
    }

    private void recordObservedChannel(String pkg, NotificationChannel channel,
                                       int importance, boolean regexMatched) {
        try {
            CharSequence nameCs = channel.getName();
            detectedStore.record(new ChannelRecord(
                    pkg,
                    channel.getId(),
                    nameCs == null ? null : nameCs.toString(),
                    importance,
                    regexMatched,
                    System.currentTimeMillis()));
        } catch (Throwable t) {
            HookLogger.e("Failed to record detected channel", t);
        }
    }

    private void collectAndApplyChannels(String pkg, Object value, RegexConfig cfg,
                                         Set<Object> seen, int[] count, int[] applied) {
        if (value == null || seen.contains(value)) {
            return;
        }
        seen.add(value);

        if (value instanceof NotificationChannel) {
            NotificationChannel c = (NotificationChannel) value;
            CharSequence nameCs = c.getName();
            String name = nameCs == null ? null : nameCs.toString();
            String desc = cfg.isMatchDescription() ? c.getDescription() : null;
            String matchedRule = cfg.firstMatch(c.getId(), name, desc);
            boolean matched = matchedRule != null;
            int before = c.getImportance();
            recordObservedChannel(pkg, c, before, matched);
            if (applyExistingChannelDecision(pkg, c, before, matched, matchedRule, cfg)) {
                applied[0]++;
            }
            count[0]++;
            return;
        }

        if (value instanceof NotificationChannelGroup) {
            collectAndApplyChannels(pkg, ((NotificationChannelGroup) value).getChannels(),
                    cfg, seen, count, applied);
            return;
        }

        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                collectAndApplyChannels(pkg, item, cfg, seen, count, applied);
            }
            return;
        }

        Class<?> cls = value.getClass();
        if (cls.isArray()) {
            int n = Array.getLength(value);
            for (int i = 0; i < n; i++) {
                collectAndApplyChannels(pkg, Array.get(value, i), cfg, seen, count, applied);
            }
            return;
        }

        // android.content.pm.ParceledListSlice and ROM variants commonly expose
        // getList(). Use reflection so this module does not depend on hidden APIs.
        Object list = invokeNoArg(value, "getList");
        if (list != null && list != value) {
            collectAndApplyChannels(pkg, list, cfg, seen, count, applied);
        }
    }

    private boolean applyExistingChannelDecision(String pkg, NotificationChannel channel,
                                                 int before, boolean regexMatched,
                                                 String matchedRule, RegexConfig cfg) {
        if (!cfg.isMasterEnabled()) {
            return restoreChannelIfAllowed(pkg, channel, before, cfg.overrideFor(pkg, channel.getId()),
                    true, "master-off");
        }

        Boolean override = cfg.overrideFor(pkg, channel.getId());
        boolean shouldBlock;
        String reason;
        if (override != null) {
            shouldBlock = override;
            reason = "override(" + (override ? "block" : "allow") + ")";
        } else {
            shouldBlock = regexMatched;
            reason = regexMatched ? "regex:" + matchedRule : "no-match";
        }

        if (!shouldBlock) {
            return restoreChannelIfAllowed(pkg, channel, before, override, false, reason);
        }

        if (before == NotificationManager.IMPORTANCE_NONE) {
            return false;
        }

        originalStateStore.saveIfAbsent(pkg, channel, before);
        channel.setImportance(NotificationManager.IMPORTANCE_NONE);
        try {
            channel.setShowBadge(false);
            channel.setBypassDnd(false);
        } catch (Throwable ignored) {
        }

        HookLogger.i("APPLIED existing notification channel decision"
                + " | caller=" + pkg
                + " | id=" + HookLogger.safe(channel.getId())
                + " | name=" + HookLogger.safe(channel.getName() == null
                ? null : channel.getName().toString())
                + " | reason=" + reason
                + " | importance " + before + " -> 0");
        return true;
    }

    private boolean restoreChannelIfAllowed(String pkg, NotificationChannel channel, int before,
                                            Boolean override, boolean masterOff, String reason) {
        OriginalChannelStateStore.OriginalState original =
                originalStateStore.get(pkg, channel.getId());

        if (before != NotificationManager.IMPORTANCE_NONE) {
            if (original != null && (masterOff || Boolean.FALSE.equals(override))) {
                originalStateStore.remove(pkg, channel.getId());
            }
            return false;
        }

        boolean explicitAllow = Boolean.FALSE.equals(override);
        if (original == null && !explicitAllow) {
            return false;
        }

        int restoredImportance = original != null && original.importance > NotificationManager.IMPORTANCE_NONE
                ? original.importance
                : NotificationManager.IMPORTANCE_DEFAULT;
        channel.setImportance(restoredImportance);

        try {
            if (original != null) {
                channel.setShowBadge(original.showBadge);
                channel.setBypassDnd(original.bypassDnd);
            }
        } catch (Throwable ignored) {
        }

        originalStateStore.remove(pkg, channel.getId());
        HookLogger.i("RESTORED notification channel"
                + " | caller=" + pkg
                + " | id=" + HookLogger.safe(channel.getId())
                + " | name=" + HookLogger.safe(channel.getName() == null
                ? null : channel.getName().toString())
                + " | reason=" + reason
                + " | source=" + (original == null ? "fallback-default" : "saved-original")
                + " | importance " + before + " -> " + restoredImportance);
        return true;
    }

    private void persistNotificationPreferences(Object owner) {
        if (owner == null) {
            return;
        }
        if (invokeNoArgIfExists(owner, "updateConfig")
                || invokeNoArgIfExists(owner, "updateConfigLocked")) {
            return;
        }

        Object helper = readObjectField(owner, "mPreferencesHelper");
        if (helper == null) {
            helper = readObjectField(owner, "mRankingHelper");
        }
        if (helper != null && helper != owner) {
            persistNotificationPreferences(helper);
            return;
        }

        Object outer = readObjectField(owner, "this$0");
        if (outer != null && outer != owner) {
            persistNotificationPreferences(outer);
        }
    }

    private static Object invokeNoArg(Object obj, String methodName) {
        try {
            Method m = findNoArgMethod(obj.getClass(), methodName);
            if (m == null) {
                return null;
            }
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean invokeNoArgIfExists(Object obj, String methodName) {
        try {
            Method m = findNoArgMethod(obj.getClass(), methodName);
            if (m == null) {
                return false;
            }
            m.setAccessible(true);
            m.invoke(obj);
            HookLogger.i("Persisted notification channel config via "
                    + obj.getClass().getName() + "#" + methodName);
            return true;
        } catch (Throwable t) {
            HookLogger.e("Failed to persist notification channel config via "
                    + obj.getClass().getName() + "#" + methodName, t);
            return false;
        }
    }

    private static Method findNoArgMethod(Class<?> cls, String name) {
        while (cls != null) {
            try {
                Method m = cls.getDeclaredMethod(name);
                if (m.getParameterTypes().length == 0) {
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static Object readObjectField(Object obj, String fieldName) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            if (f == null) {
                return null;
            }
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Best-effort: the calling package is usually the first String arg. */
    private static String describeCaller(Object[] args) {
        if (args == null) {
            return "<unknown>";
        }
        for (Object a : args) {
            if (a instanceof String) {
                return (String) a;
            }
        }
        return "<unknown>";
    }

    // ------------------------------------------------------------------
    // (2) AMS app-death watcher -> SafetyManager
    // ------------------------------------------------------------------

    private void hookAmsForSafety(LoadPackageParam lpparam) {
        Class<?> ams = XposedHelpers.findClassIfExists(
                "com.android.server.am.ActivityManagerService", lpparam.classLoader);
        if (ams == null) {
            HookLogger.w("ActivityManagerService not found — SystemUI crash-loop "
                    + "detection unavailable on this ROM.");
            return;
        }

        int hooked = 0;
        // Method name varies across versions; hook whatever exists.
        for (String m : new String[]{"appDiedLocked", "handleAppDiedLocked"}) {
            try {
                XposedBridge.hookAllMethods(ams, m, appDeathCallback);
                hooked++;
                HookLogger.i("Hooked ActivityManagerService#" + m + " for safety watch");
            } catch (Throwable t) {
                // not every version has both — that's fine
            }
        }

        if (hooked == 0) {
            HookLogger.w("Could not hook any AMS app-death method — safe mode "
                    + "will not auto-trip (manual flag still works).");
        }
    }

    private final XC_MethodHook appDeathCallback = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                String processName = extractProcessName(param.args);
                if (SYSTEMUI_PKG.equals(processName)) {
                    safety.onSystemUiDied();
                }
            } catch (Throwable t) {
                HookLogger.e("appDeathCallback error", t);
            }
        }
    };

    /** Pull the process name out of a ProcessRecord-like argument. */
    private static String extractProcessName(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a == null) {
                continue;
            }
            // ProcessRecord exposes a public String field "processName".
            String name = readStringField(a, "processName");
            if (name != null) {
                return name;
            }
            // Fallback: ProcessRecord.info.packageName
            try {
                Object info = XposedHelpers.getObjectField(a, "info");
                if (info != null) {
                    String pkg = readStringField(info, "packageName");
                    if (pkg != null) {
                        return pkg;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String readStringField(Object obj, String field) {
        try {
            Field f = findField(obj.getClass(), field);
            if (f == null) {
                return null;
            }
            f.setAccessible(true);
            Object v = f.get(obj);
            return (v instanceof String) ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findField(Class<?> cls, String name) {
        while (cls != null) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }
}
