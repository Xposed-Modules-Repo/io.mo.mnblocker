package io.mo.mnblocker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
    private static final int COLOR_BG = 0xFFF6F7FB;
    private static final int COLOR_CARD = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFF172033;
    private static final int COLOR_SUB = 0xFF6D7484;
    private static final int COLOR_LINE = 0xFFE7EAF0;
    private static final int COLOR_PRIMARY = 0xFF3F6DF6;
    private static final int COLOR_DANGER = 0xFFC62828;

    /** Quiet period after the last keystroke before the app list is re-filtered. */
    private static final long SEARCH_DEBOUNCE_MS = 150;

    private EditText allowInput;
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

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(header());
        root.addView(allowCard());
        root.addView(appCard());
        setContentView(scroll);
        SystemBars.edgeToEdge(this, scroll, root, root);

        loadCurrent();
    }

    /** In-page title, replacing the platform ActionBar this screen used to show. */
    private View header()
    {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(16));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(30);
        back.setTextColor(COLOR_TEXT);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(getString(R.string.nav_back));
        back.setClickable(true);
        back.setOnClickListener(v -> finish());
        row.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView title = new TextView(this);
        title.setText(getString(R.string.whitelist_title));
        title.setTextSize(22);
        title.setTextColor(COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        row.addView(title, lp);
        return row;
    }

    // ------------------------------------------------------------------
    // Allow-regex card
    // ------------------------------------------------------------------

    private View allowCard()
    {
        LinearLayout card = cardLayout();
        card.addView(sectionTitle(getString(R.string.allow_card_title),
                getString(R.string.allow_card_desc)));

        allowInput = new EditText(this);
        allowInput.setMinLines(4);
        allowInput.setGravity(Gravity.TOP | Gravity.START);
        allowInput.setTextSize(13);
        allowInput.setTextColor(COLOR_TEXT);
        allowInput.setHint(getString(R.string.allow_hint));
        allowInput.setHintTextColor(0xFFB0B6C3);
        allowInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        allowInput.setBackground(roundStrokeBg(Color.WHITE, dp(14), COLOR_LINE, 1));
        card.addView(allowInput);

        Button save = primaryButton(getString(R.string.action_save_allow));
        save.setOnClickListener(v -> onSaveAllow());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(12);
        card.addView(save, lp);
        return card;
    }

    private void onSaveAllow()
    {
        String bad = firstInvalidRegex(allowInput.getText().toString());
        if (bad != null)
        {
            Toast.makeText(this, getString(R.string.toast_regex_invalid_fmt, bad), Toast.LENGTH_LONG).show();
            return;
        }
        // persist() reports success unconditionally in root-free mode (nothing to
        // sync), so the success string has to be picked by mode here — reusing the
        // root one claimed it had synced to a hook that does not exist.
        final boolean rf = rootFree();
        persist(ok -> Toast.makeText(this,
                ok ? getString(rf ? R.string.toast_saved_rootfree : R.string.toast_saved_synced)
                        : getString(R.string.toast_allow_save_partial),
                Toast.LENGTH_LONG).show());
    }

    // ------------------------------------------------------------------
    // App-whitelist card
    // ------------------------------------------------------------------

    private View appCard()
    {
        LinearLayout card = cardLayout();
        card.addView(sectionTitle(getString(R.string.app_card_title),
                getString(R.string.app_card_desc)));

        Button add = primaryButton(getString(R.string.action_add_app));
        add.setOnClickListener(v -> onAddApp());
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        addLp.bottomMargin = dp(8);
        card.addView(add, addLp);

        appListContainer = new LinearLayout(this);
        appListContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(appListContainer);
        return card;
    }

    private void onAddApp()
    {
        Toast.makeText(this, getString(R.string.toast_loading_apps), Toast.LENGTH_SHORT).show();
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
            runOnUiThread(() -> showPicker(items));
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

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        EditText search = new EditText(this);
        search.setHint(getString(R.string.picker_search_hint));
        search.setHintTextColor(0xFFB0B6C3);
        search.setTextColor(COLOR_TEXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        search.setBackground(roundStrokeBg(Color.WHITE, dp(12), COLOR_LINE, 1));
        box.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView empty = new TextView(this);
        empty.setText(getString(R.string.picker_no_match));
        empty.setTextColor(COLOR_SUB);
        empty.setTextSize(13);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(24), 0, dp(24));
        empty.setVisibility(View.GONE);
        box.addView(empty);

        final ListView list = new ListView(this);
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
            if (!picked.remove(pkg))
            {
                picked.add(pkg);
            }
            adapter.notifyDataSetChanged();
        });
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380));
        listLp.topMargin = dp(8);
        box.addView(list, listLp);

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
                    persist(ok -> Toast.makeText(this,
                            ok ? getString(R.string.toast_app_whitelist_saved_fmt, appWhitelist.size())
                                    : getString(R.string.toast_app_whitelist_save_failed),
                            Toast.LENGTH_SHORT).show());
                })
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private void renderAppList()
    {
        appListContainer.removeAllViews();
        if (appWhitelist.isEmpty())
        {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.app_whitelist_empty));
            empty.setTextSize(12);
            empty.setTextColor(COLOR_SUB);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(14), dp(20), dp(14), dp(20));
            empty.setBackground(roundStrokeBg(0xFFF8FAFF, dp(16), COLOR_LINE, 1));
            appListContainer.addView(empty);
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
            row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(10), dp(4), dp(10));

            ImageView icon = new ImageView(this);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(36), dp(36));
            iconLp.rightMargin = dp(12);
            row.addView(icon, iconLp);

            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView name = new TextView(this);
            name.setTextSize(14);
            name.setTextColor(COLOR_TEXT);
            text.addView(name);

            TextView meta = new TextView(this);
            meta.setTextSize(10);
            meta.setTextColor(COLOR_SUB);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            text.addView(meta);
            row.addView(text);

            CheckBox box = new CheckBox(this);
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
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(10), dp(10));
        row.setBackground(roundStrokeBg(Color.WHITE, dp(16), COLOR_LINE, 1));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(8);
        row.setLayoutParams(rowLp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(appLabel(pkg));
        name.setTextSize(14);
        name.setTextColor(COLOR_TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(name);

        TextView meta = new TextView(this);
        meta.setText(pkg);
        meta.setTextSize(10);
        meta.setTextColor(COLOR_SUB);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(0, dp(3), 0, 0);
        text.addView(meta);
        row.addView(text);

        Button remove = new Button(this);
        remove.setText(getString(R.string.action_remove));
        remove.setAllCaps(false);
        remove.setTextSize(12);
        remove.setTypeface(Typeface.DEFAULT_BOLD);
        remove.setTextColor(Color.WHITE);
        remove.setBackground(roundBg(COLOR_DANGER, dp(12)));
        remove.setPadding(dp(12), 0, dp(12), 0);
        remove.setOnClickListener(v ->
        {
            appWhitelist.remove(pkg);
            renderAppList();
            persist(null);
        });
        row.addView(remove, new LinearLayout.LayoutParams(dp(64), dp(40)));
        return row;
    }

    private String appLabel(String pkg)
    {
        try
        {
            PackageManager pm = getPackageManager();
            CharSequence l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            if (l != null && l.length() > 0)
            {
                return l.toString();
            }
        }
        catch (Throwable ignored)
        {
        }
        return pkg;
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

    // ------------------------------------------------------------------
    // style helpers (self-contained, same look as the other screens)
    // ------------------------------------------------------------------

    private LinearLayout cardLayout()
    {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(roundBg(COLOR_CARD, dp(22)));
        card.setElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);
        return card;
    }

    private View sectionTitle(String title, String desc)
    {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, dp(12));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(17);
        t.setTextColor(COLOR_TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(12);
        d.setTextColor(COLOR_SUB);
        d.setPadding(0, dp(4), 0, 0);
        box.addView(d);
        return box;
    }

    private Button primaryButton(String text)
    {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setTextColor(Color.WHITE);
        b.setBackground(roundBg(COLOR_PRIMARY, dp(14)));
        return b;
    }

    private GradientDrawable roundBg(int color, int radius)
    {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        return gd;
    }

    private GradientDrawable roundStrokeBg(int color, int radius, int strokeColor, int strokeWidth)
    {
        GradientDrawable gd = roundBg(color, radius);
        gd.setStroke(strokeWidth, strokeColor);
        return gd;
    }

    private int dp(int v)
    {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
