package io.mo.mnblocker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Dedicated whitelist settings screen, opened from the main page.
 *
 * Merges the two "never block" mechanisms:
 *   1. 放行白名单（正则） — the allow-regex (key {@link RegexConfig#KEY_ALLOW_RULES}).
 *   2. App 白名单 — whole apps exempt from any interception (key
 *      {@link RegexConfig#KEY_APP_WHITELIST}, newline-joined package names).
 *
 * Both live in the shared prefs + /data/system/mnblocker/config.json bridge. On
 * every write we read the OTHER config fields (master switch, rules, overrides,
 * content) and write them back unchanged, so editing here never clobbers what
 * the main screen owns.
 */
public final class WhitelistActivity extends Activity
{
    /** Quiet period after the last keystroke before the app list is re-filtered. */
    private static final long SEARCH_DEBOUNCE_MS = 150;
    /** Height of the picker's scrolling list inside its dialog. */
    private static final int PICKER_LIST_DP = 380;

    private Ui ui;
    private FrameLayout rootFrame;
    private EditText allowInput;
    private Button addButton;
    private LinearLayout appListContainer;
    private final Set<String> appWhitelist = new LinkedHashSet<>();
    private final Collator zhCollator = Collator.getInstance(Locale.CHINA);

    @Override
    protected void attachBaseContext(Context newBase)
    {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        ui = new Ui(this);

        rootFrame = new FrameLayout(this);
        Ui.Page page = ui.page();
        LinearLayout root = page.content;

        root.addView(ui.topBar(getString(R.string.whitelist_title), null, null));
        root.addView(allowCard());
        root.addView(appCard());

        rootFrame.addView(page.scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(rootFrame);
        SystemBars.edgeToEdge(this, rootFrame, root, root);

        loadCurrent();
    }

    // ------------------------------------------------------------------
    // Allow-regex card
    // ------------------------------------------------------------------

    private View allowCard()
    {
        LinearLayout card = ui.card();
        card.addView(ui.sectionTitle(getString(R.string.allow_card_title),
                getString(R.string.allow_card_desc)));

        allowInput = ui.field(getString(R.string.allow_hint), 4, true);
        card.addView(allowInput, Ui.matchWrap());

        Button save = ui.button(Ui.BTN_PRIMARY, getString(R.string.action_save_allow));
        save.setOnClickListener(v -> onSaveAllow());
        card.addView(save, ui.margins(Ui.matchWrap(), 0, Ui.S3, 0, 0));
        return card;
    }

    private void onSaveAllow()
    {
        String bad = firstInvalidRegex(allowInput.getText().toString());
        if (bad != null)
        {
            ui.markError(allowInput);
            ui.snackbar(rootFrame, getString(R.string.toast_regex_invalid_fmt, bad), true);
            return;
        }
        // persist() reports success unconditionally in root-free mode (nothing to
        // sync), so the success string has to be picked by mode here — reusing the
        // root one claimed it had synced to a hook that does not exist.
        final boolean rf = rootFree();
        persist(ok -> report(ok,
                getString(rf ? R.string.toast_saved_rootfree : R.string.toast_saved_synced),
                getString(R.string.toast_allow_save_partial)));
    }

    /** Outcome of a save: confirm haptic + message, or the error message. */
    private void report(boolean ok, String success, String failure)
    {
        if (ok)
        {
            Haptics.confirm(rootFrame);
        }
        ui.snackbar(rootFrame, ok ? success : failure, !ok);
    }

    // ------------------------------------------------------------------
    // App-whitelist card
    // ------------------------------------------------------------------

    private View appCard()
    {
        LinearLayout card = ui.card();
        card.addView(ui.sectionTitle(getString(R.string.app_card_title),
                getString(R.string.app_card_desc)));

        addButton = ui.button(Ui.BTN_TONAL, getString(R.string.action_add_app));
        addButton.setOnClickListener(v -> onAddApp());
        card.addView(addButton, ui.margins(Ui.matchWrap(), 0, 0, 0, Ui.S3));

        appListContainer = ui.column();
        card.addView(appListContainer);
        return card;
    }

    private void onAddApp()
    {
        // The installed-app scan takes a moment; the button says so by going
        // inert rather than by a toast that may outlive it.
        Ui.setEnabledLook(addButton, false);
        addButton.setText(getString(R.string.toast_loading_apps));
        new Thread(() ->
        {
            final PackageManager pm = getPackageManager();
            List<ApplicationInfo> installed = pm.getInstalledApplications(0);
            // {label, pkg, label-lowercased, pkg-lowercased}. The lowercase forms
            // are folded once here rather than per keystroke in the search filter.
            final List<String[]> items = new ArrayList<>();
            for (ApplicationInfo ai : installed)
            {
                String label;
                try
                {
                    CharSequence l = pm.getApplicationLabel(ai);
                    label = (l == null || l.length() == 0) ? ai.packageName : l.toString();
                }
                catch (Throwable t)
                {
                    label = ai.packageName;
                }
                items.add(new String[]{label, ai.packageName,
                        label.toLowerCase(Locale.ROOT),
                        ai.packageName.toLowerCase(Locale.ROOT)});
            }
            Collections.sort(items, new Comparator<String[]>()
            {
                @Override
                public int compare(String[] a, String[] b)
                {
                    int c = zhCollator.compare(a[0], b[0]);
                    return c != 0 ? c : a[1].compareToIgnoreCase(b[1]);
                }
            });
            runOnUiThread(() ->
            {
                if (isFinishing())
                {
                    return;
                }
                Ui.setEnabledLook(addButton, true);
                addButton.setText(getString(R.string.action_add_app));
                showPicker(items);
            });
        }).start();
    }

    /**
     * Multi-select picker with a search box. The checked state is tracked by
     * package name (not list position), so filtering the list never loses or
     * misapplies a tick.
     */
    private void showPicker(List<String[]> items)
    {
        final Set<String> picked = new LinkedHashSet<>(appWhitelist);
        final List<String[]> shown = new ArrayList<>(items);

        LinearLayout box = ui.column();
        box.setPadding(ui.dp(Ui.S5), ui.dp(Ui.S2), ui.dp(Ui.S5), 0);

        final EditText search = ui.searchField(getString(R.string.picker_search_hint));
        box.addView(search, Ui.matchWrap());

        final View empty = ui.emptyState(R.drawable.ic_apps,
                getString(R.string.picker_no_match), 0);
        empty.setVisibility(View.GONE);
        box.addView(empty);

        final ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(ui.ripple(ui.shape(android.graphics.Color.TRANSPARENT, Ui.R_SMALL),
                Ui.R_SMALL));
        final BaseAdapter adapter = new BaseAdapter()
        {
            @Override
            public int getCount()
            {
                return shown.size();
            }

            @Override
            public Object getItem(int position)
            {
                return shown.get(position);
            }

            @Override
            public long getItemId(int position)
            {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent)
            {
                String[] item = shown.get(position);
                return pickerRow(item[0], item[1], picked.contains(item[1]), convertView);
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
        {
            String pkg = shown.get(position)[1];
            boolean nowPicked = !picked.remove(pkg);
            if (nowPicked)
            {
                picked.add(pkg);
            }
            Haptics.toggle(view, nowPicked);
            adapter.notifyDataSetChanged();
        });
        box.addView(list, ui.margins(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(PICKER_LIST_DP)), 0, Ui.S2, 0, 0));

        // Debounced: a rescan re-binds every visible row, so running one per
        // keystroke made typing lag on devices with a few hundred apps installed.
        final Handler handler = new Handler(Looper.getMainLooper());
        search.addTextChangedListener(new TextWatcher()
        {
            private final Runnable filter = new Runnable()
            {
                @Override
                public void run()
                {
                    String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
                    shown.clear();
                    for (String[] item : items)
                    {
                        if (q.isEmpty() || item[2].contains(q) || item[3].contains(q))
                        {
                            shown.add(item);
                        }
                    }
                    adapter.notifyDataSetChanged();
                    empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
                    list.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
                }
            };

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s)
            {
                handler.removeCallbacks(filter);
                handler.postDelayed(filter, SEARCH_DEBOUNCE_MS);
            }
        });

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.picker_title))
                .setView(box)
                .setPositiveButton(getString(R.string.action_confirm), (d, w) ->
                {
                    appWhitelist.clear();
                    appWhitelist.addAll(picked);
                    renderAppList(); // optimistic — the save reports back below
                    persist(ok -> report(ok,
                            getString(R.string.toast_app_whitelist_saved_fmt, appWhitelist.size()),
                            getString(R.string.toast_app_whitelist_save_failed)));
                })
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private void renderAppList()
    {
        appListContainer.removeAllViews();
        if (appWhitelist.isEmpty())
        {
            appListContainer.addView(ui.emptyState(R.drawable.ic_apps,
                    getString(R.string.app_whitelist_empty), ui.p.surfaceVariant));
            return;
        }
        for (String pkg : appWhitelist)
        {
            appListContainer.addView(appRow(pkg));
        }
    }

    /** One row of the searchable picker: icon, label + package, and a tick. */
    private View pickerRow(String label, String pkg, boolean checked, View convertView)
    {
        LinearLayout row;
        if (convertView instanceof LinearLayout)
        {
            row = (LinearLayout) convertView;
        }
        else
        {
            row = ui.row();
            row.setMinimumHeight(ui.dp(Ui.ROW_MIN));
            row.setPadding(ui.dp(Ui.S1), ui.dp(Ui.S2), ui.dp(Ui.S1), ui.dp(Ui.S2));

            ImageView icon = new ImageView(this);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(icon, ui.margins(ui.size(Ui.APP_ICON, Ui.APP_ICON), 0, 0, Ui.S3, 0));

            LinearLayout text = ui.column();
            text.addView(ui.text("", Ui.Type.BODY, ui.p.onSurface));
            TextView meta = ui.text("", Ui.Type.CAPTION, ui.p.onSurfaceVariant);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            text.addView(meta);
            row.addView(text, Ui.fillRow());

            CheckBox box = ui.checkBox();
            // The row itself handles the toggle; a focusable child would swallow it.
            box.setClickable(false);
            box.setFocusable(false);
            row.addView(box);
        }

        AppIconCache.bindIcon(this, (ImageView) row.getChildAt(0), pkg);
        LinearLayout text = (LinearLayout) row.getChildAt(1);
        ((TextView) text.getChildAt(0)).setText(label);
        ((TextView) text.getChildAt(1)).setText(pkg);
        ((CheckBox) row.getChildAt(2)).setChecked(checked);
        return row;
    }

    private View appRow(final String pkg)
    {
        LinearLayout row = ui.row();
        row.setMinimumHeight(ui.dp(Ui.ROW_MIN));
        row.setPadding(ui.dp(Ui.S3), ui.dp(Ui.S2), ui.dp(Ui.S2), ui.dp(Ui.S2));
        row.setBackground(ui.shape(ui.p.surfaceVariant, Ui.R_MEDIUM));
        row.setLayoutParams(ui.margins(Ui.matchWrap(), 0, 0, 0, Ui.S2));

        ImageView icon = new ImageView(this);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        AppIconCache.bindIcon(this, icon, pkg);
        row.addView(icon, ui.margins(ui.size(Ui.APP_ICON, Ui.APP_ICON), 0, 0, Ui.S3, 0));

        LinearLayout text = ui.column();
        text.addView(ui.text(AppIconCache.label(this, pkg), Ui.Type.TITLE_SMALL, ui.p.onSurface));
        TextView meta = ui.text(pkg, Ui.Type.CAPTION, ui.p.onSurfaceVariant);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(meta, ui.margins(Ui.matchWrap(), 0, Ui.S_HALF, 0, 0));
        row.addView(text, Ui.fillRow());

        Button remove = ui.button(Ui.BTN_DANGER, getString(R.string.action_remove));
        remove.setOnClickListener(v ->
        {
            Haptics.confirm(v);
            appWhitelist.remove(pkg);
            renderAppList();
            persist(null);
        });
        row.addView(remove, ui.margins(Ui.wrapWrap(), Ui.S2, 0, 0, 0));
        return row;
    }

    // ------------------------------------------------------------------
    // Persistence (preserves the fields owned by the main screen)
    // ------------------------------------------------------------------

    /**
     * Root mode: config lives under /data/system and may need su, so read it
     * off the main thread. Root-free mode: mnblocker_prefs is already the
     * source of truth (same uid as the listener), so this applies the pref
     * values synchronously — no su, no wait.
     */
    private void loadCurrent()
    {
        SharedPreferences sp = prefs();
        final String prefAllow = sp.getString(RegexConfig.KEY_ALLOW_RULES, "");
        final String prefApps = sp.getString(RegexConfig.KEY_APP_WHITELIST, "");

        if (rootFree())
        {
            applyLoadedState(prefAllow, prefApps);
            return;
        }

        // Skeleton until the su read lands, so the list does not flash "empty".
        appListContainer.addView(ui.skeletonBlock(Ui.ROW_MIN));
        Bg.load(this, ConfigFileStore::readForApp, disk ->
                applyLoadedState(
                        disk.hasValue ? disk.allowRules : prefAllow,
                        disk.hasValue ? disk.appWhitelist : prefApps));
    }

    private void applyLoadedState(String allow, String appList)
    {
        allowInput.setText(allow);
        appWhitelist.clear();
        if (!TextUtils.isEmpty(appList))
        {
            for (String line : appList.split("\\r?\\n"))
            {
                String t = line.trim();
                if (!t.isEmpty())
                {
                    appWhitelist.add(t);
                }
            }
        }
        renderAppList();
    }

    /**
     * Save the allow rules + app whitelist to the config bridge.
     *
     * This is a read-modify-write — the main screen owns the other keys in the
     * file and they must survive — and BOTH halves go through su. The whole
     * cycle therefore runs on {@link Bg}; {@code then} (may be null) gets the
     * outcome back on the main thread.
     */
    private void persist(final Bg.Consumer<Boolean> then)
    {
        final String allow = allowInput.getText().toString();
        final String appList = TextUtils.join("\n", appWhitelist);

        final SharedPreferences sp = prefs();
        sp.edit()
                .putString(RegexConfig.KEY_ALLOW_RULES, allow)
                .putString(RegexConfig.KEY_APP_WHITELIST, appList)
                .apply();

        if (rootFree())
        {
            // The listener reads mnblocker_prefs directly (same uid, no su
            // bridge) — nothing here can fail, so this always reports success.
            if (then != null)
            {
                then.accept(true);
            }
            return;
        }

        Bg.load(this, () ->
        {
            ConfigFileStore.ConfigSnapshot cur = ConfigFileStore.readForApp();
            boolean master = cur.hasValue ? cur.masterEnabled
                    : sp.getBoolean(RegexConfig.KEY_MASTER_ENABLED, true);
            boolean matchDesc = cur.hasValue ? cur.matchDescription
                    : sp.getBoolean(RegexConfig.KEY_MATCH_DESC, true);
            String rules = cur.hasValue ? cur.rules : sp.getString(RegexConfig.KEY_RULES, "");
            String overrides = cur.hasValue ? cur.overrides
                    : sp.getString(RegexConfig.KEY_OVERRIDES, "");
            boolean contentEnabled = cur.hasValue ? cur.contentEnabled
                    : sp.getBoolean(RegexConfig.KEY_CONTENT_ENABLED, false);
            String contentRules = cur.hasValue ? cur.contentRules
                    : sp.getString(RegexConfig.KEY_CONTENT_RULES, "");
            String mode = cur.hasValue ? cur.operatingMode
                    : sp.getString(RegexConfig.KEY_OPERATING_MODE, RegexConfig.MODE_ROOT);

            return ConfigFileStore.writeFromApp(master, matchDesc, rules, allow, overrides,
                    contentEnabled, contentRules, appList, mode);
        }, ok ->
        {
            if (then != null)
            {
                then.accept(ok);
            }
        });
    }

    @SuppressWarnings("deprecation")
    private SharedPreferences prefs()
    {
        try
        {
            return getSharedPreferences(RegexConfig.PREFS_NAME, Context.MODE_WORLD_READABLE);
        }
        catch (SecurityException e)
        {
            return getSharedPreferences(RegexConfig.PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    /** See MainActivity#rootFree() — same pref, same "never spawns su" contract. */
    private boolean rootFree()
    {
        return RegexConfig.MODE_ROOTFREE.equals(
                prefs().getString(RegexConfig.KEY_OPERATING_MODE, RegexConfig.MODE_ROOT));
    }

    private String firstInvalidRegex(String blob)
    {
        if (blob == null)
        {
            return null;
        }
        for (String line : blob.split("\\r?\\n"))
        {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#"))
            {
                continue;
            }
            try
            {
                Pattern.compile(t);
            }
            catch (Exception e)
            {
                return t;
            }
        }
        return null;
    }
}
