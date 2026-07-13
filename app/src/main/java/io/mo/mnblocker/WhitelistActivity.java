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
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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

    private EditText allowInput;
    private LinearLayout appListContainer;
    private final Set<String> appWhitelist = new LinkedHashSet<>();
    private final Collator zhCollator = Collator.getInstance(Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setTitle("白名单设置");

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(allowCard());
        root.addView(appCard());
        setContentView(scroll);

        loadCurrent();
    }

    // ------------------------------------------------------------------
    // Allow-regex card
    // ------------------------------------------------------------------

    private View allowCard()
    {
        LinearLayout card = cardLayout();
        card.addView(sectionTitle("放行白名单（正则）",
                "命中的通知类别永不被拦截，优先级高于拦截规则。"));

        allowInput = new EditText(this);
        allowInput.setMinLines(4);
        allowInput.setGravity(Gravity.TOP | Gravity.START);
        allowInput.setTextSize(13);
        allowInput.setTextColor(COLOR_TEXT);
        allowInput.setHint("例如：\n.*(验证码|动态密码).*\n.*(微信|QQ|短信).*");
        allowInput.setHintTextColor(0xFFB0B6C3);
        allowInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        allowInput.setBackground(roundStrokeBg(Color.WHITE, dp(14), COLOR_LINE, 1));
        card.addView(allowInput);

        Button save = primaryButton("保存放行白名单");
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
            Toast.makeText(this, "正则有误：" + bad, Toast.LENGTH_LONG).show();
            return;
        }
        boolean ok = persist();
        Toast.makeText(this, ok ? "已保存并同步到 Hook" : "已保存到本地，但同步失败，请检查 root 授权",
                Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------
    // App-whitelist card
    // ------------------------------------------------------------------

    private View appCard()
    {
        LinearLayout card = cardLayout();
        card.addView(sectionTitle("App 白名单",
                "名单内应用的通知完全不被拦截（含通道级与内容级）。"));

        Button add = primaryButton("添加应用");
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
        Toast.makeText(this, "正在加载应用列表…", Toast.LENGTH_SHORT).show();
        new Thread(() ->
        {
            final PackageManager pm = getPackageManager();
            List<ApplicationInfo> installed = pm.getInstalledApplications(0);
            final List<String[]> items = new ArrayList<>(); // {label, pkg}
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
                items.add(new String[]{label, ai.packageName});
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

    private void showPicker(List<String[]> items)
    {
        final int n = items.size();
        final CharSequence[] labels = new CharSequence[n];
        final String[] pkgs = new String[n];
        final boolean[] checked = new boolean[n];
        for (int i = 0; i < n; i++)
        {
            String label = items.get(i)[0];
            String pkg = items.get(i)[1];
            labels[i] = label.equals(pkg) ? pkg : (label + "\n" + pkg);
            pkgs[i] = pkg;
            checked[i] = appWhitelist.contains(pkg);
        }

        new AlertDialog.Builder(this)
                .setTitle("选择要放行的应用")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("确定", (d, w) ->
                {
                    appWhitelist.clear();
                    for (int i = 0; i < n; i++)
                    {
                        if (checked[i])
                        {
                            appWhitelist.add(pkgs[i]);
                        }
                    }
                    boolean ok = persist();
                    renderAppList();
                    Toast.makeText(this,
                            ok ? "已保存 " + appWhitelist.size() + " 个应用" : "保存失败，请检查 root 授权",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void renderAppList()
    {
        appListContainer.removeAllViews();
        if (appWhitelist.isEmpty())
        {
            TextView empty = new TextView(this);
            empty.setText("尚未添加任何应用。点击“添加应用”选择要整体放行的 App。");
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
        remove.setText("移除");
        remove.setAllCaps(false);
        remove.setTextSize(12);
        remove.setTypeface(Typeface.DEFAULT_BOLD);
        remove.setTextColor(Color.WHITE);
        remove.setBackground(roundBg(COLOR_DANGER, dp(12)));
        remove.setPadding(dp(12), 0, dp(12), 0);
        remove.setOnClickListener(v ->
        {
            appWhitelist.remove(pkg);
            persist();
            renderAppList();
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

    private void loadCurrent()
    {
        SharedPreferences sp = prefs();
        String allow = sp.getString(RegexConfig.KEY_ALLOW_RULES, "");
        String appList = sp.getString(RegexConfig.KEY_APP_WHITELIST, "");

        ConfigFileStore.ConfigSnapshot disk = ConfigFileStore.readForApp();
        if (disk.hasValue)
        {
            allow = disk.allowRules;
            appList = disk.appWhitelist;
        }

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

    private boolean persist()
    {
        String allow = allowInput.getText().toString();
        String appList = TextUtils.join("\n", appWhitelist);

        SharedPreferences sp = prefs();
        sp.edit()
                .putString(RegexConfig.KEY_ALLOW_RULES, allow)
                .putString(RegexConfig.KEY_APP_WHITELIST, appList)
                .apply();

        // Preserve everything the main screen owns.
        ConfigFileStore.ConfigSnapshot cur = ConfigFileStore.readForApp();
        boolean master = cur.hasValue ? cur.masterEnabled
                : sp.getBoolean(RegexConfig.KEY_MASTER_ENABLED, true);
        boolean matchDesc = cur.hasValue ? cur.matchDescription
                : sp.getBoolean(RegexConfig.KEY_MATCH_DESC, true);
        String rules = cur.hasValue ? cur.rules : sp.getString(RegexConfig.KEY_RULES, "");
        String overrides = cur.hasValue ? cur.overrides : sp.getString(RegexConfig.KEY_OVERRIDES, "");
        boolean contentEnabled = cur.hasValue ? cur.contentEnabled
                : sp.getBoolean(RegexConfig.KEY_CONTENT_ENABLED, false);
        String contentRules = cur.hasValue ? cur.contentRules
                : sp.getString(RegexConfig.KEY_CONTENT_RULES, "");

        return ConfigFileStore.writeFromApp(master, matchDesc, rules, allow, overrides,
                contentEnabled, contentRules, appList);
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
