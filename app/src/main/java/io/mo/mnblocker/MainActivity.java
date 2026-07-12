package io.mo.mnblocker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Configuration UI.
 *
 * Modernized programmatic layout.
 */
public final class MainActivity extends Activity
{
    private static final int SORT_BY_APP = 0;
    private static final int SORT_BY_ALPHA = 1;
    private static final String KEY_UI_ONLY_MATCHED = "ui_only_matched";

    private static final int COLOR_BG = 0xFFF6F7FB;
    private static final int COLOR_CARD = 0xFFFFFFFF;
    private static final int COLOR_TEXT = 0xFF172033;
    private static final int COLOR_SUB = 0xFF6D7484;
    private static final int COLOR_LINE = 0xFFE7EAF0;
    private static final int COLOR_PRIMARY = 0xFF3F6DF6;
    private static final int COLOR_PRIMARY_DARK = 0xFF254FD8;
    private static final int COLOR_DANGER = 0xFFC62828;
    private static final int COLOR_SUCCESS = 0xFF2E7D32;
    private static final int COLOR_WARN_BG = 0xFFFFF5DD;
    private static final int COLOR_WARN_TEXT = 0xFF9A5B00;

    private EditText rulesInput;
    private EditText allowRulesInput;
    private Switch contentEnabledSwitch;
    private EditText contentRulesInput;
    private TextView contentStatsView;
    private Switch masterSwitch;
    private Switch matchDescSwitch;
    private TextView statusView;
    private Button clearSafeModeButton;
    private TextView listHeader;
    private TextView batchHint;
    private Switch onlyMatchedSwitch;
    private LinearLayout listContainer;
    private Button sortButton;
    private Button multiSelectButton;
    private Button selectAllButton;

    private final List<ChannelRecord> channels = new ArrayList<>();
    private final Map<String, Boolean> overrides = new HashMap<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private int sortMode = SORT_BY_APP;
    private boolean multiSelectMode;
    // Safe-mode state is read via su (the app uid cannot stat /data/system
    // directly), so it is cached here and refreshed on load / manual refresh
    // rather than probed on every refreshStatus() call.
    private boolean safeModeCached;

    private final Collator zhCollator = Collator.getInstance(Locale.CHINA);
    private FrameLayout rootFrame;

    // Shared block/allow engine, cached and rebuilt only when the rule text
    // changes so per-row rendering does not recompile regex repeatedly.
    private RuleMatcher cachedMatcher;
    private String cachedMatcherKey;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        // Repair dir ownership + check root in one background thread.
        new Thread(() -> {
            boolean hasRoot = ShellUtils.fixDirPermissions();
            if (!hasRoot) {
                runOnUiThread(() -> showFadeHint("未授权 root 权限，模块可能无法正常运行"));
            }
        }).start();

        rootFrame = new FrameLayout(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(heroCard());
        root.addView(globalCard());
        root.addView(rulesCard());
        root.addView(channelCard());

        rootFrame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootFrame);

        loadSwitchesAndRules();
        reloadChannelsAndOverrides();
        renderList();
    }

    private View heroCard()
    {
        LinearLayout card = cardLayout();
        card.setBackground(roundBg(COLOR_PRIMARY, dp(22)));
        card.setPadding(dp(18), dp(18), dp(18), dp(16));

        LinearLayout header = rowLayout();
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(this);
        badge.setText("LSPosed Module");
        badge.setTextColor(0xFFEFF3FF);
        badge.setTextSize(11);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(10), dp(4), dp(10), dp(4));
        badge.setBackground(roundBg(0x33FFFFFF, dp(999)));
        header.addView(badge, wrapParams());

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        ImageButton aboutButton = new ImageButton(this);
        aboutButton.setImageResource(R.drawable.ic_about);
        aboutButton.setColorFilter(Color.WHITE);
        aboutButton.setBackground(roundBg(0x33FFFFFF, dp(999)));
        aboutButton.setContentDescription("关于");
        aboutButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        aboutButton.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        header.addView(aboutButton, new LinearLayout.LayoutParams(dp(40), dp(40)));

        card.addView(header);

        TextView title = new TextView(this);
        title.setText("营销通知拦截器");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(12), 0, dp(4));
        card.addView(title);

        TextView desc = new TextView(this);
        desc.setText("通过正则与单独覆盖规则，精细控制 App 通知类别。");
        desc.setTextColor(0xFFE7ECFF);
        desc.setTextSize(13);
        desc.setLineSpacing(dp(2), 1.0f);
        card.addView(desc);

        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setTextColor(Color.WHITE);
        statusView.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusView.setBackground(roundBg(0x22FFFFFF, dp(14)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        card.addView(statusView, lp);

        clearSafeModeButton = baseButton("关闭安全模式");
        clearSafeModeButton.setTextColor(Color.WHITE);
        clearSafeModeButton.setBackground(roundBg(COLOR_WARN_TEXT, dp(14)));
        clearSafeModeButton.setVisibility(View.GONE);
        clearSafeModeButton.setOnClickListener(v -> onClearSafeMode());

        LinearLayout.LayoutParams safeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46));
        safeLp.topMargin = dp(10);
        card.addView(clearSafeModeButton, safeLp);

        return card;
    }

    private void onClearSafeMode()
    {
        new AlertDialog.Builder(this)
                .setTitle("关闭安全模式")
                .setMessage("安全模式是在系统界面反复崩溃时自动触发的保护。"
                        + "确认拦截规则已修正后，清除标志即可恢复拦截。\n\n"
                        + "模块会在系统进程内监听该标志，通常无需重启即可自动恢复。"
                        + "若长时间未恢复，可手动重启一次。")
                .setPositiveButton("清除并恢复", (d, w) -> new Thread(() ->
                {
                    boolean ok = ShellUtils.clearSafeMode();
                    runOnUiThread(() ->
                    {
                        if (!ok)
                        {
                            Toast.makeText(this,
                                    "清除失败，请检查 root 授权",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        safeModeCached = false;
                        refreshStatus();
                        Toast.makeText(this, "已清除，拦截将自动恢复，无需重启",
                                Toast.LENGTH_LONG).show();
                    });
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    private View globalCard()
    {
        LinearLayout card = cardLayout();
        card.addView(sectionTitle("全局开关", "控制 Hook 是否工作，以及是否匹配通知类别描述。"));

        masterSwitch = cleanSwitch("启用拦截", "总开关关闭后，Hook 不再拦截通知类别。");
        masterSwitch.setOnCheckedChangeListener((b, v) -> persistSwitches());
        card.addView(masterSwitch);

        card.addView(divider());

        matchDescSwitch = cleanSwitch("匹配描述文本", "同时检查通知类别 description，命中更完整。 ");
        matchDescSwitch.setOnCheckedChangeListener((b, v) -> persistSwitches());
        card.addView(matchDescSwitch);

        return card;
    }

    private View rulesCard()
    {
        LinearLayout card = cardLayout();
        card.addView(sectionTitle("正则规则", "每行一条规则，支持 id / 名称 / 描述匹配。"));

        TextView hint = new TextView(this);
        hint.setText("示例：\n.*营销.*\n.*(推广|促销|优惠).*\n^ads?_.*");
        hint.setTextColor(COLOR_SUB);
        hint.setTextSize(12);
        hint.setPadding(dp(12), dp(10), dp(12), dp(10));
        hint.setBackground(roundStrokeBg(0xFFF8FAFF, dp(14), COLOR_LINE, 1));
        card.addView(hint);

        rulesInput = new EditText(this);
        rulesInput.setMinLines(5);
        rulesInput.setGravity(Gravity.TOP | Gravity.START);
        rulesInput.setTextSize(13);
        rulesInput.setTextColor(COLOR_TEXT);
        rulesInput.setHint("输入正则规则，一行一个");
        rulesInput.setHintTextColor(0xFFB0B6C3);
        rulesInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        rulesInput.setBackground(roundStrokeBg(Color.WHITE, dp(14), COLOR_LINE, 1));

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(12);
        card.addView(rulesInput, inputLp);

        TextView allowTitle = new TextView(this);
        allowTitle.setText("放行白名单（正则）");
        allowTitle.setTextColor(COLOR_TEXT);
        allowTitle.setTextSize(14);
        allowTitle.setTypeface(Typeface.DEFAULT_BOLD);
        allowTitle.setPadding(0, dp(16), 0, dp(2));
        card.addView(allowTitle);

        TextView allowDesc = new TextView(this);
        allowDesc.setText("命中白名单的通知类别永不被拦截，优先级高于上方拦截规则。"
                + "用于保护验证码、即时通讯等重要通知。");
        allowDesc.setTextColor(COLOR_SUB);
        allowDesc.setTextSize(12);
        allowDesc.setPadding(0, 0, 0, dp(4));
        card.addView(allowDesc);

        allowRulesInput = new EditText(this);
        allowRulesInput.setMinLines(3);
        allowRulesInput.setGravity(Gravity.TOP | Gravity.START);
        allowRulesInput.setTextSize(13);
        allowRulesInput.setTextColor(COLOR_TEXT);
        allowRulesInput.setHint("例如：\n.*(验证码|动态密码).*\n.*(微信|QQ|短信).*");
        allowRulesInput.setHintTextColor(0xFFB0B6C3);
        allowRulesInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        allowRulesInput.setBackground(roundStrokeBg(Color.WHITE, dp(14), COLOR_LINE, 1));

        LinearLayout.LayoutParams allowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        allowLp.topMargin = dp(8);
        card.addView(allowRulesInput, allowLp);

        card.addView(divider());

        TextView contentTitle = new TextView(this);
        contentTitle.setText("内容级拦截（实验性）");
        contentTitle.setTextColor(COLOR_TEXT);
        contentTitle.setTextSize(14);
        contentTitle.setTypeface(Typeface.DEFAULT_BOLD);
        contentTitle.setPadding(0, dp(10), 0, dp(2));
        card.addView(contentTitle);

        TextView contentDesc = new TextView(this);
        contentDesc.setText("按通知的标题/正文匹配并拦截整条通知，可拦下共享或“默认”通道推送的营销通知。"
                + "作用于每一条通知；更新模块后需重启设备一次以载入 Hook，之后开关与规则修改均即时生效。"
                + "使用下方独立规则，并同样受上方放行白名单保护；前台服务通知不会被拦截。默认关闭，请谨慎开启。");
        contentDesc.setTextColor(COLOR_SUB);
        contentDesc.setTextSize(12);
        contentDesc.setPadding(0, 0, 0, dp(2));
        card.addView(contentDesc);

        contentEnabledSwitch = cleanSwitch("启用内容级拦截", "在通知入队时按内容匹配（实验性）。");
        contentEnabledSwitch.setOnCheckedChangeListener((b, v) -> persistSwitches());
        card.addView(contentEnabledSwitch);

        contentRulesInput = new EditText(this);
        contentRulesInput.setMinLines(3);
        contentRulesInput.setGravity(Gravity.TOP | Gravity.START);
        contentRulesInput.setTextSize(13);
        contentRulesInput.setTextColor(COLOR_TEXT);
        contentRulesInput.setHint("内容拦截正则，一行一个，例如：\n.*(限时特惠|内购|直播间).*");
        contentRulesInput.setHintTextColor(0xFFB0B6C3);
        contentRulesInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        contentRulesInput.setBackground(roundStrokeBg(Color.WHITE, dp(14), COLOR_LINE, 1));

        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        contentLp.topMargin = dp(8);
        card.addView(contentRulesInput, contentLp);

        LinearLayout statsRow = rowLayout();
        statsRow.setGravity(Gravity.CENTER_VERTICAL);
        statsRow.setPadding(dp(12), dp(8), dp(8), dp(8));
        statsRow.setBackground(roundBg(0xFFF8FAFF, dp(12)));

        LinearLayout.LayoutParams statsRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statsRowLp.topMargin = dp(10);
        card.addView(statsRow, statsRowLp);

        contentStatsView = new TextView(this);
        contentStatsView.setTextSize(12);
        contentStatsView.setTextColor(COLOR_SUB);
        contentStatsView.setText("累计拦截：— 条");
        statsRow.addView(contentStatsView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button resetStatsButton = softButton("清零");
        resetStatsButton.setOnClickListener(v -> onResetContentStats());
        statsRow.addView(resetStatsButton, new LinearLayout.LayoutParams(
                dp(64), dp(38)));

        Button saveRules = primaryButton("保存规则与开关");
        saveRules.setOnClickListener(v -> onSaveRules());

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46));
        btnLp.topMargin = dp(12);
        card.addView(saveRules, btnLp);

        return card;
    }

    private View channelCard()
    {
        LinearLayout card = cardLayout();
        card.addView(sectionTitle("已检测到的通知类别", "列表来自 system_server 中 Hook 记录的实时数据。"));

        listHeader = new TextView(this);
        listHeader.setTextSize(12);
        listHeader.setTextColor(COLOR_SUB);
        listHeader.setPadding(0, 0, 0, dp(8));
        card.addView(listHeader);

        onlyMatchedSwitch = cleanSwitch("仅显示正则命中的类别", "关闭后会显示 Hook 记录到的全部通知类别。 ");
        onlyMatchedSwitch.setOnCheckedChangeListener((b, v) ->
        {
            prefs().edit().putBoolean(KEY_UI_ONLY_MATCHED, v).apply();
            selected.clear();
            renderList();
        });
        card.addView(onlyMatchedSwitch);

        LinearLayout toolbar1 = rowLayout();
        toolbar1.setPadding(0, dp(10), 0, 0);

        Button refreshButton = softButton("刷新");
        refreshButton.setOnClickListener(v ->
        {
            reloadChannelsAndOverrides();
            renderList();
            Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show();
        });
        toolbar1.addView(refreshButton, equalWeightWithGap(true));

        sortButton = softButton("排序");
        sortButton.setOnClickListener(v ->
        {
            sortMode = (sortMode == SORT_BY_APP) ? SORT_BY_ALPHA : SORT_BY_APP;
            renderList();
        });
        toolbar1.addView(sortButton, equalWeightWithGap(true));

        multiSelectButton = softButton("多选");
        multiSelectButton.setOnClickListener(v ->
        {
            multiSelectMode = !multiSelectMode;
            selected.clear();
            renderList();
        });
        toolbar1.addView(multiSelectButton, equalWeightWithGap(false));
        card.addView(toolbar1);

        LinearLayout toolbar2 = rowLayout();
        toolbar2.setPadding(0, dp(8), 0, 0);

        Button blockAll = dangerButton("批量拦截");
        blockAll.setOnClickListener(v -> batchSet(true));
        toolbar2.addView(blockAll, equalWeightWithGap(true));

        Button allowAll = successButton("批量允许");
        allowAll.setOnClickListener(v -> batchSet(false));
        toolbar2.addView(allowAll, equalWeightWithGap(true));

        selectAllButton = softButton("全选/清空");
        selectAllButton.setOnClickListener(v ->
        {
            List<ChannelRecord> visible = visibleChannels();
            if (selected.size() < visible.size())
            {
                for (ChannelRecord r : visible)
                {
                    selected.add(r.key());
                }
            }
            else
            {
                selected.clear();
            }
            renderList();
        });
        toolbar2.addView(selectAllButton, equalWeightWithGap(false));
        card.addView(toolbar2);

        batchHint = new TextView(this);
        batchHint.setTextSize(12);
        batchHint.setTextColor(COLOR_SUB);
        batchHint.setPadding(dp(12), dp(8), dp(12), dp(8));
        batchHint.setBackground(roundBg(0xFFF8FAFF, dp(12)));

        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(10);
        card.addView(batchHint, hintLp);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = dp(8);
        card.addView(listContainer, listLp);

        return card;
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

    private void loadSwitchesAndRules()
    {
        SharedPreferences sp = prefs();
        boolean master = sp.getBoolean(RegexConfig.KEY_MASTER_ENABLED, true);
        boolean matchDesc = sp.getBoolean(RegexConfig.KEY_MATCH_DESC, true);
        String rules = sp.getString(RegexConfig.KEY_RULES, "");
        String allowRules = sp.getString(RegexConfig.KEY_ALLOW_RULES, "");
        boolean contentEnabled = sp.getBoolean(RegexConfig.KEY_CONTENT_ENABLED, false);
        String contentRules = sp.getString(RegexConfig.KEY_CONTENT_RULES, "");

        ConfigFileStore.ConfigSnapshot disk = ConfigFileStore.readForApp();
        if (disk.hasValue)
        {
            master = disk.masterEnabled;
            matchDesc = disk.matchDescription;
            rules = disk.rules;
            allowRules = disk.allowRules;
            contentEnabled = disk.contentEnabled;
            contentRules = disk.contentRules;
        }

        setCheckedSilently(masterSwitch, master);
        setCheckedSilently(matchDescSwitch, matchDesc);
        setCheckedSilently(contentEnabledSwitch, contentEnabled);
        setCheckedSilently(onlyMatchedSwitch, sp.getBoolean(KEY_UI_ONLY_MATCHED, true));
        rulesInput.setText(rules);
        allowRulesInput.setText(allowRules);
        contentRulesInput.setText(contentRules);
        refreshStatus();
    }

    private void persistSwitches()
    {
        prefs().edit()
                .putBoolean(RegexConfig.KEY_MASTER_ENABLED, masterSwitch.isChecked())
                .putBoolean(RegexConfig.KEY_MATCH_DESC, matchDescSwitch.isChecked())
                .putBoolean(RegexConfig.KEY_CONTENT_ENABLED,
                        contentEnabledSwitch != null && contentEnabledSwitch.isChecked())
                .apply();
        persistConfigFile(false);
        refreshStatus();
    }

    private void onSaveRules()
    {
        String rules = rulesInput.getText().toString();
        String allowRules = allowRulesInput.getText().toString();
        String contentRules = contentRulesInput.getText().toString();
        String bad = firstInvalidRegex(rules);
        if (bad == null)
        {
            bad = firstInvalidRegex(allowRules);
        }
        if (bad == null)
        {
            bad = firstInvalidRegex(contentRules);
        }
        if (bad != null)
        {
            Toast.makeText(this, "正则有误：" + bad, Toast.LENGTH_LONG).show();
            return;
        }

        boolean ok = prefs().edit()
                .putBoolean(RegexConfig.KEY_MASTER_ENABLED, masterSwitch.isChecked())
                .putBoolean(RegexConfig.KEY_MATCH_DESC, matchDescSwitch.isChecked())
                .putBoolean(RegexConfig.KEY_CONTENT_ENABLED, contentEnabledSwitch.isChecked())
                .putString(RegexConfig.KEY_RULES, rules)
                .putString(RegexConfig.KEY_ALLOW_RULES, allowRules)
                .putString(RegexConfig.KEY_CONTENT_RULES, contentRules)
                .commit();
        boolean bridgeOk = persistConfigFile(false);

        Toast.makeText(this,
                ok && bridgeOk ? "已保存并同步到 Hook" : "已保存到本地，但同步到 Hook 失败，请检查 root 授权",
                Toast.LENGTH_LONG).show();
        refreshStatus();
        renderList();
    }

    private void reloadChannelsAndOverrides()
    {
        channels.clear();
        channels.addAll(DetectedChannelsStore.readAllFromDiskForApp());

        overrides.clear();
        ConfigFileStore.ConfigSnapshot disk = ConfigFileStore.readForApp();
        String json = disk.hasValue
                ? disk.overrides
                : prefs().getString(RegexConfig.KEY_OVERRIDES, "");
        if (!TextUtils.isEmpty(json))
        {
            try
            {
                JSONObject o = new JSONObject(json);
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); )
                {
                    String k = it.next();
                    overrides.put(k, o.optBoolean(k, false));
                }
            }
            catch (Throwable ignored)
            {
            }
        }

        safeModeCached = ShellUtils.isSafeModeTripped();
        selected.retainAll(keySet());
        refreshStatus();
        refreshContentStats();
    }

    private void refreshContentStats()
    {
        if (contentStatsView == null)
        {
            return;
        }
        ContentStatsStore.Snapshot s = ContentStatsStore.readForApp();
        String when = s.lastBlocked > 0
                ? DateUtils.getRelativeTimeSpanString(s.lastBlocked,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                : "—";
        contentStatsView.setText("累计拦截：" + s.count + " 条 · 最近：" + when);
    }

    private void onResetContentStats()
    {
        new AlertDialog.Builder(this)
                .setTitle("清零拦截统计")
                .setMessage("将内容级拦截的累计次数清零？此操作不影响你的规则与开关。")
                .setPositiveButton("清零", (d, w) -> new Thread(() ->
                {
                    boolean ok = ContentStatsStore.resetFromApp();
                    runOnUiThread(() ->
                    {
                        Toast.makeText(this,
                                ok ? "已清零" : "清零失败，请检查 root 授权",
                                Toast.LENGTH_SHORT).show();
                        refreshContentStats();
                    });
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    private void persistOverrides()
    {
        JSONObject o = new JSONObject();
        try
        {
            for (Map.Entry<String, Boolean> e : overrides.entrySet())
            {
                o.put(e.getKey(), e.getValue());
            }
        }
        catch (Throwable ignored)
        {
        }
        prefs().edit().putString(RegexConfig.KEY_OVERRIDES, o.toString()).apply();
        persistConfigFile(false);
    }

    private boolean persistConfigFile(boolean showToast)
    {
        boolean ok = ConfigFileStore.writeFromApp(
                masterSwitch != null && masterSwitch.isChecked(),
                matchDescSwitch == null || matchDescSwitch.isChecked(),
                rulesInput == null ? "" : rulesInput.getText().toString(),
                allowRulesInput == null ? "" : allowRulesInput.getText().toString(),
                overridesJson(),
                contentEnabledSwitch != null && contentEnabledSwitch.isChecked(),
                contentRulesInput == null ? "" : contentRulesInput.getText().toString());
        if (!ok && showToast)
        {
            Toast.makeText(this, "同步到 /data/system/mnblocker/config.json 失败，请检查 root 授权", Toast.LENGTH_LONG).show();
        }
        return ok;
    }

    private String overridesJson()
    {
        JSONObject o = new JSONObject();
        try
        {
            for (Map.Entry<String, Boolean> e : overrides.entrySet())
            {
                o.put(e.getKey(), e.getValue());
            }
        }
        catch (Throwable ignored)
        {
        }
        return o.toString();
    }

    private void renderList()
    {
        sortButton.setText(sortMode == SORT_BY_APP ? "按应用" : "按字母");
        multiSelectButton.setText(multiSelectMode ? "多选中" : "多选");
        selectAllButton.setEnabled(multiSelectMode);
        selectAllButton.setAlpha(multiSelectMode ? 1.0f : 0.45f);

        List<ChannelRecord> visible = visibleChannels();

        if (multiSelectMode)
        {
            batchHint.setText("多选模式：批量按钮仅作用于已勾选的 " + selected.size() + " 项");
        }
        else
        {
            batchHint.setText("普通模式：批量按钮作用于当前显示的 " + visible.size() + " 项");
        }

        listContainer.removeAllViews();

        if (visible.isEmpty())
        {
            TextView empty = new TextView(this);
            empty.setText(channels.isEmpty()
                    ? "暂无数据。等待 App 创建通知类别后点刷新；若仍为空，请确认 root 读取权限。"
                    : "当前没有正则命中的类别。可关闭“仅显示正则命中的类别”查看全部。 ");
            empty.setTextSize(12);
            empty.setTextColor(COLOR_SUB);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(14), dp(22), dp(14), dp(22));
            empty.setBackground(roundStrokeBg(0xFFF8FAFF, dp(16), COLOR_LINE, 1));
            listContainer.addView(empty);
            listHeader.setText("显示 0 个 · 已记录 " + channels.size() + " 个");
            return;
        }

        List<ChannelRecord> sorted = new ArrayList<>(visible);
        Collections.sort(sorted, comparator());

        listHeader.setText("显示 " + sorted.size() + " 个 · 已记录 " + channels.size()
                + " 个 · 当前显示中 " + countBlocked(sorted) + " 个处于拦截状态");

        String lastApp = null;
        for (ChannelRecord r : sorted)
        {
            if (sortMode == SORT_BY_APP && !r.pkg.equals(lastApp))
            {
                lastApp = r.pkg;
                listContainer.addView(appGroupHeader(r.pkg));
            }
            listContainer.addView(channelRow(r));
        }
    }

    private Comparator<ChannelRecord> comparator()
    {
        if (sortMode == SORT_BY_APP)
        {
            return (a, b) ->
            {
                int c = a.pkg.compareToIgnoreCase(b.pkg);
                if (c != 0)
                {
                    return c;
                }
                return zhCollator.compare(displayName(a), displayName(b));
            };
        }

        return (a, b) ->
        {
            int c = zhCollator.compare(displayName(a), displayName(b));
            if (c != 0)
            {
                return c;
            }
            return a.pkg.compareToIgnoreCase(b.pkg);
        };
    }

    private TextView appGroupHeader(String pkg)
    {
        TextView t = new TextView(this);
        t.setText(pkg);
        t.setTextSize(11);
        t.setTextColor(COLOR_SUB);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(2), dp(14), dp(2), dp(6));
        return t;
    }

    private View channelRow(ChannelRecord r)
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

        final String key = r.key();
        final boolean blocked = effectiveBlocked(r);

        if (multiSelectMode)
        {
            CheckBox cb = new CheckBox(this);
            cb.setChecked(selected.contains(key));
            cb.setOnCheckedChangeListener((b, checked) ->
            {
                if (checked)
                {
                    selected.add(key);
                }
                else
                {
                    selected.remove(key);
                }
                batchHint.setText("多选模式：批量按钮仅作用于已勾选的 " + selected.size() + " 项");
            });
            row.addView(cb);
        }

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(multiSelectMode ? 0 : dp(2), 0, dp(8), 0);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f);
        text.setLayoutParams(tlp);

        TextView nameView = new TextView(this);
        nameView.setText(displayName(r));
        nameView.setTextSize(14);
        nameView.setTextColor(COLOR_TEXT);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(nameView);

        TextView metaView = new TextView(this);
        metaView.setText(r.pkg + "  ·  id=" + (TextUtils.isEmpty(r.id) ? "—" : r.id));
        metaView.setTextSize(10);
        metaView.setTextColor(COLOR_SUB);
        metaView.setSingleLine(true);
        metaView.setEllipsize(TextUtils.TruncateAt.END);
        metaView.setPadding(0, dp(3), 0, dp(3));
        text.addView(metaView);

        TextView statusLine = new TextView(this);
        Boolean ov = overrides.get(key);
        String src;
        if (ov != null)
        {
            src = "单独覆盖";
        }
        else
        {
            RuleMatcher m = matcher();
            String[] cand = candidates(r);
            if (m.firstAllowMatch(cand) != null)
            {
                src = "白名单放行";
            }
            else if (m.firstBlockMatch(cand) != null)
            {
                src = "正则命中";
            }
            else
            {
                src = "正则未命中";
            }
        }
        statusLine.setText(src + "  ·  当前：" + (blocked ? "拦截" : "允许"));
        statusLine.setTextSize(10);
        statusLine.setTextColor(blocked ? COLOR_DANGER : COLOR_SUCCESS);
        statusLine.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(statusLine);

        row.addView(text);

        Switch sw = new Switch(this);
        sw.setChecked(blocked);
        sw.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
        {
            overrides.put(key, checked);
            persistOverrides();
            statusLine.setText("单独覆盖  ·  当前：" + (checked ? "拦截" : "允许"));
            statusLine.setTextColor(checked ? COLOR_DANGER : COLOR_SUCCESS);
            List<ChannelRecord> visible = visibleChannels();
            listHeader.setText("显示 " + visible.size() + " 个 · 已记录 " + channels.size()
                    + " 个 · 当前显示中 " + countBlocked(visible) + " 个处于拦截状态");
        });
        row.addView(sw);

        if (multiSelectMode)
        {
            row.setOnClickListener(v ->
            {
                if (selected.contains(key))
                {
                    selected.remove(key);
                }
                else
                {
                    selected.add(key);
                }
                renderList();
            });
        }

        return row;
    }

    private void batchSet(boolean block)
    {
        Set<String> targets;
        if (multiSelectMode)
        {
            if (selected.isEmpty())
            {
                Toast.makeText(this, "请先勾选要操作的类别", Toast.LENGTH_SHORT).show();
                return;
            }
            targets = new LinkedHashSet<>(selected);
        }
        else
        {
            targets = visibleKeySet();
            if (targets.isEmpty())
            {
                Toast.makeText(this, "列表为空", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        for (String k : targets)
        {
            overrides.put(k, block);
        }

        persistOverrides();
        renderList();
        Toast.makeText(this,
                (block ? "已拦截 " : "已允许 ") + targets.size() + " 项",
                Toast.LENGTH_SHORT).show();
    }

    private boolean effectiveBlocked(ChannelRecord r)
    {
        Boolean ov = overrides.get(r.key());
        return (ov != null) ? ov : uiRegexMatched(r);
    }

    private int countBlocked(List<ChannelRecord> list)
    {
        int n = 0;
        for (ChannelRecord r : list)
        {
            if (effectiveBlocked(r))
            {
                n++;
            }
        }
        return n;
    }

    private List<ChannelRecord> visibleChannels()
    {
        if (onlyMatchedSwitch == null || !onlyMatchedSwitch.isChecked())
        {
            return new ArrayList<>(channels);
        }

        List<ChannelRecord> out = new ArrayList<>();
        for (ChannelRecord r : channels)
        {
            if (uiRegexMatched(r))
            {
                out.add(r);
            }
        }
        return out;
    }

    private Set<String> visibleKeySet()
    {
        Set<String> s = new LinkedHashSet<>();
        for (ChannelRecord r : visibleChannels())
        {
            s.add(r.key());
        }
        return s;
    }

    private Set<String> keySet()
    {
        Set<String> s = new LinkedHashSet<>();
        for (ChannelRecord r : channels)
        {
            s.add(r.key());
        }
        return s;
    }

    /**
     * Net regex verdict for a channel using the SAME engine as the hook, so the
     * UI never disagrees with what actually gets blocked. Reflects the allow
     * whitelist (allow beats block) and the "匹配描述文本" toggle.
     */
    private boolean uiRegexMatched(ChannelRecord r)
    {
        return matcher().shouldBlock(candidates(r));
    }

    /** Shared engine built from the current rule text; cached until text changes. */
    private RuleMatcher matcher()
    {
        String block = rulesInput == null ? "" : rulesInput.getText().toString();
        String allow = allowRulesInput == null ? "" : allowRulesInput.getText().toString();
        String key = block + " " + allow;
        if (!key.equals(cachedMatcherKey))
        {
            cachedMatcher = RuleMatcher.compile(splitLines(block), splitLines(allow));
            cachedMatcherKey = key;
        }
        return cachedMatcher;
    }

    /** Candidate strings to test: id + name, plus description when the toggle is on. */
    private String[] candidates(ChannelRecord r)
    {
        boolean useDesc = matchDescSwitch != null && matchDescSwitch.isChecked()
                && !TextUtils.isEmpty(r.desc);
        return useDesc
                ? new String[]{r.id, r.name, r.desc}
                : new String[]{r.id, r.name};
    }

    private static List<String> splitLines(String blob)
    {
        List<String> out = new ArrayList<>();
        if (blob == null)
        {
            return out;
        }
        for (String line : blob.split("\\r?\\n"))
        {
            out.add(line);
        }
        return out;
    }

    private static int countRules(String blob)
    {
        int n = 0;
        if (!TextUtils.isEmpty(blob))
        {
            for (String l : blob.split("\\r?\\n"))
            {
                String t = l.trim();
                if (!t.isEmpty() && !t.startsWith("#"))
                {
                    n++;
                }
            }
        }
        return n;
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

    private static String displayName(ChannelRecord r)
    {
        if (!TextUtils.isEmpty(r.name))
        {
            return r.name;
        }
        if (!TextUtils.isEmpty(r.id))
        {
            return r.id;
        }
        return "(无名称)";
    }

    private void refreshStatus()
    {
        if (statusView == null)
        {
            return;
        }

        boolean safeMode = safeModeCached;
        String state = safeMode
                ? "⚠ 安全模式已触发，Hook 已停用"
                : (masterSwitch != null && masterSwitch.isChecked() ? "正常运行" : "总开关已关闭");

        int count = countRules(rulesInput == null
                ? prefs().getString(RegexConfig.KEY_RULES, "")
                : rulesInput.getText().toString());
        int allowCount = countRules(allowRulesInput == null
                ? prefs().getString(RegexConfig.KEY_ALLOW_RULES, "")
                : allowRulesInput.getText().toString());
        int contentCount = countRules(contentRulesInput == null
                ? prefs().getString(RegexConfig.KEY_CONTENT_RULES, "")
                : contentRulesInput.getText().toString());
        boolean contentOn = contentEnabledSwitch != null && contentEnabledSwitch.isChecked();

        statusView.setText("状态：" + state
                + "\n自定义正则：" + count + " 条 · 内置默认：1 条 · 白名单：" + allowCount
                + " 条 · 单独覆盖：" + overrides.size() + " 项"
                + "\n内容级拦截：" + (contentOn ? "开启" : "关闭") + " · 内容规则：" + contentCount + " 条");

        if (safeMode)
        {
            statusView.setTextColor(COLOR_WARN_TEXT);
            statusView.setBackground(roundBg(COLOR_WARN_BG, dp(14)));
        }
        else
        {
            statusView.setTextColor(Color.WHITE);
            statusView.setBackground(roundBg(0x22FFFFFF, dp(14)));
        }

        if (clearSafeModeButton != null)
        {
            clearSafeModeButton.setVisibility(safeMode ? View.VISIBLE : View.GONE);
        }
    }

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

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(17);
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(titleView);

        TextView descView = new TextView(this);
        descView.setText(desc);
        descView.setTextSize(12);
        descView.setTextColor(COLOR_SUB);
        descView.setPadding(0, dp(4), 0, 0);
        box.addView(descView);

        return box;
    }

    private Switch cleanSwitch(String title, String sub)
    {
        Switch sw = new Switch(this);
        sw.setText(title + "\n" + sub);
        sw.setTextSize(13);
        sw.setTextColor(COLOR_TEXT);
        sw.setPadding(0, dp(8), 0, dp(8));
        return sw;
    }

    private View divider()
    {
        View v = new View(this);
        v.setBackgroundColor(COLOR_LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        v.setLayoutParams(lp);
        return v;
    }

    private Button primaryButton(String text)
    {
        Button b = baseButton(text);
        b.setTextColor(Color.WHITE);
        b.setBackground(roundBg(COLOR_PRIMARY, dp(14)));
        return b;
    }

    private Button softButton(String text)
    {
        Button b = baseButton(text);
        b.setTextColor(COLOR_PRIMARY_DARK);
        b.setBackground(roundBg(0xFFEFF3FF, dp(14)));
        return b;
    }

    private Button dangerButton(String text)
    {
        Button b = baseButton(text);
        b.setTextColor(Color.WHITE);
        b.setBackground(roundBg(0xFFE53935, dp(14)));
        return b;
    }

    private Button successButton(String text)
    {
        Button b = baseButton(text);
        b.setTextColor(Color.WHITE);
        b.setBackground(roundBg(0xFF43A047, dp(14)));
        return b;
    }

    private Button baseButton(String text)
    {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(dp(8), 0, dp(8), 0);
        return b;
    }

    private LinearLayout rowLayout()
    {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams equalWeightWithGap(boolean hasRightGap)
    {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0,
                dp(42),
                1f);
        if (hasRightGap)
        {
            lp.rightMargin = dp(8);
        }
        return lp;
    }

    private LinearLayout.LayoutParams wrapParams()
    {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
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

    private void setCheckedSilently(Switch sw, boolean checked)
    {
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(checked);
        if (sw == onlyMatchedSwitch)
        {
            sw.setOnCheckedChangeListener((b, v) ->
            {
                prefs().edit().putBoolean(KEY_UI_ONLY_MATCHED, v).apply();
                selected.clear();
                renderList();
            });
        }
        else
        {
            sw.setOnCheckedChangeListener((b, v) -> persistSwitches());
        }
    }

    /**
     * Show a hint anchored near the bottom of the screen that fades out
     * after 2 seconds.
     */
    private void showFadeHint(String text)
    {
        android.widget.TextView hint = new android.widget.TextView(this);
        hint.setText(text);
        hint.setTextColor(android.graphics.Color.WHITE);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(24), dp(12), dp(24), dp(12));
        hint.setBackground(roundBg(0xCC333333, dp(24)));
        hint.setAlpha(1f);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(80);

        rootFrame.addView(hint, lp);

        new Handler(Looper.getMainLooper()).postDelayed(() ->
                hint.animate()
                        .alpha(0f)
                        .setDuration(800)
                        .withEndAction(() -> rootFrame.removeView(hint))
                        .start(),
                2000);
    }

    private int dp(int v)
    {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
